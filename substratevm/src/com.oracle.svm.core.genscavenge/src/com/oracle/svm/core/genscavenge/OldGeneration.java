/*
 * Copyright (c) 2014, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.genscavenge;

import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.EXTREMELY_SLOW_PATH_PROBABILITY;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.probability;

import org.graalvm.compiler.word.Word;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordBase;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.AlwaysInline;
import com.oracle.svm.core.MemoryWalker;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.UnmanagedMemoryUtil;
import com.oracle.svm.core.genscavenge.GCImpl.ChunkReleaser;
import com.oracle.svm.core.genscavenge.remset.RememberedSet;
import com.oracle.svm.core.heap.ObjectVisitor;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.thread.VMOperation;
import com.oracle.svm.core.util.VMError;

/**
 * The old generation has only one {@link Space} for existing, newly-allocated or promoted objects
 * and uses a mark–compact algorithm for garbage collection.
 */
public final class OldGeneration extends Generation {

    private final Space space;

    private final GreyObjectsWalker toGreyObjectsWalker = new GreyObjectsWalker();
    private final CleanupVisitor cleanupVisitor = new CleanupVisitor();
    private final SweepVisitor sweepVisitor = new SweepVisitor();
    private final AllObjectsMarkingVisitor allObjectsMarkingVisitor = new AllObjectsMarkingVisitor();

    @Platforms(Platform.HOSTED_ONLY.class)
    OldGeneration(String name) {
        super(name);
        int age = HeapParameters.getMaxSurvivorSpaces() + 1;
        this.space = new Space("tenuredSpace", false, age);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    void tearDown() {
        space.tearDown();
    }

    @Override
    public boolean walkObjects(ObjectVisitor visitor) {
        return getSpace().walkObjects(visitor);
    }

    /**
     * Promote an Object from {@link YoungGeneration} to {@link OldGeneration}.
     */
    @AlwaysInline("GC performance")
    @Override
    public Object promoteAlignedObject(Object original, AlignedHeapChunk.AlignedHeader originalChunk, Space originalSpace) {
        assert originalSpace.isFromSpace();
        Object copy = getSpace().promoteAlignedObject(original, originalSpace);
        ObjectHeaderImpl.setMarkedBit(copy);
        return copy;
    }

    @AlwaysInline("GC performance")
    @Override
    protected Object promoteUnalignedObject(Object original, UnalignedHeapChunk.UnalignedHeader originalChunk, Space originalSpace) {
        assert originalSpace.isFromSpace() || originalSpace.isOldSpace();
        if (originalSpace.isOldSpace()) {
            RememberedSet.get().clearRememberedSet(originalChunk);
        } else {
            getSpace().promoteUnalignedHeapChunk(originalChunk, originalSpace);
        }
        ObjectHeaderImpl.setMarkedBit(original);
        return original;
    }

    @Override
    protected boolean promoteChunk(HeapChunk.Header<?> originalChunk, boolean isAligned, Space originalSpace) {
        assert originalSpace.isFromSpace();
        if (isAligned) {
            getSpace().promoteAlignedHeapChunk((AlignedHeapChunk.AlignedHeader) originalChunk, originalSpace);
            AlignedHeapChunk.walkObjects((AlignedHeapChunk.AlignedHeader) originalChunk, allObjectsMarkingVisitor);
        } else {
            getSpace().promoteUnalignedHeapChunk((UnalignedHeapChunk.UnalignedHeader) originalChunk, originalSpace);
            UnalignedHeapChunk.walkObjects((UnalignedHeapChunk.UnalignedHeader) originalChunk, allObjectsMarkingVisitor);
        }
        return true;
    }

    void pinAlignedObject(Object original) {
        assert HeapChunk.getSpace(HeapChunk.getEnclosingHeapChunk(original)) != space;
        ObjectHeaderImpl.setMarkedBit(original);
        // TODO: Pinned objects mustn't move when compressing chunks!
    }

    void releaseSpaces(ChunkReleaser chunkReleaser) {
        sweepVisitor.setChunkReleaser(chunkReleaser);
        space.walkObjects(sweepVisitor);

        space.walkObjects(cleanupVisitor);

        AlignedHeapChunk.AlignedHeader aChunk = space.getFirstAlignedHeapChunk();
        while (aChunk.isNonNull()) {
            RememberedSet.get().clearRememberedSet(aChunk);
            aChunk = HeapChunk.getNext(aChunk);
        }

        UnalignedHeapChunk.UnalignedHeader uChunk = space.getFirstUnalignedHeapChunk();
        while (uChunk.isNonNull()) {
            RememberedSet.get().clearRememberedSet(uChunk);
            uChunk = HeapChunk.getNext(uChunk);
        }

        // TODO: Don't free memory here as we would destroy data!
    }

    void prepareForPromotion() {
        toGreyObjectsWalker.setScanStart(getSpace());
    }

    /**
     * @return {@code true} if gray objects still exist
     */
    boolean scanGreyObjects() {
        if (!toGreyObjectsWalker.haveGreyObjects()) {
            return false;
        }
        toGreyObjectsWalker.walkGreyObjects();
        return true;
    }

    @Override
    public Log report(Log log, boolean traceHeapChunks) {
        log.string("Old generation: ").indent(true);
        getSpace().report(log, traceHeapChunks).newline();
        log.redent(false);
        return log;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    Space getSpace() {
        return space;
    }

    boolean walkHeapChunks(MemoryWalker.Visitor visitor) {
        return getSpace().walkHeapChunks(visitor);
    }

    /**
     * This value is only updated during a GC. Be careful when calling this method during a GC as it
     * might wrongly include chunks that will be freed at the end of the GC.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    UnsignedWord getChunkBytes() {
        return getSpace().getChunkBytes();
    }

    @SuppressWarnings("static-method")
    AlignedHeapChunk.AlignedHeader requestAlignedChunk() {
        assert VMOperation.isGCInProgress() : "Should only be called from the collector.";
        AlignedHeapChunk.AlignedHeader chunk = HeapImpl.getChunkProvider().produceAlignedChunk();
        if (probability(EXTREMELY_SLOW_PATH_PROBABILITY, chunk.isNull())) {
            Log.log().string("[! OldGeneration.requestAlignedChunk: failure to allocate aligned chunk!]");
            throw VMError.shouldNotReachHere("Promotion failure");
        }
        RememberedSet.get().enableRememberedSetForChunk(chunk);
        return chunk;
    }

    private static Object sampleObj16 = new byte[]{};
    private static Object sampleObj24 = new byte[]{1, 2};

    private class CleanupVisitor implements ObjectVisitor {

        @Override
        public boolean visitObject(Object obj) {
            if (ObjectHeaderImpl.hasMarkedBit(obj)) {
                ObjectHeaderImpl.clearMarkedBit(obj);
            } else {
                UnsignedWord size = LayoutEncoding.getSizeFromObjectInGC(obj);

                Pointer originalMemory = Word.objectToUntrackedPointer(obj);
                while (size.aboveOrEqual(16)) {
                    Object obj1 = originalMemory.toObject();
                    if (size.unsignedRemainder(16).aboveThan(0)) {
                        Pointer sampleMemory = Word.objectToUntrackedPointer(sampleObj24);
                        UnmanagedMemoryUtil.copyLongsForward(sampleMemory, originalMemory, WordFactory.unsigned(24));
                        ObjectHeaderImpl.setRememberedSetBit(obj1);

                        UnsignedWord size1 = LayoutEncoding.getSizeFromObjectInGC(obj1);
                        if (size1.notEqual(24)) {
                            Log.log().string("size not equal, 24 != ").unsigned(size1).newline().flush();
                        }
                        size = size.subtract(size1);
                        originalMemory = originalMemory.add(size1);
                    } else {
                        Pointer sampleMemory = Word.objectToUntrackedPointer(sampleObj16);
                        UnmanagedMemoryUtil.copyLongsForward(sampleMemory, originalMemory, WordFactory.unsigned(16));
                        ObjectHeaderImpl.setRememberedSetBit(obj1);

                        UnsignedWord size1 = LayoutEncoding.getSizeFromObjectInGC(obj1);
                        if (size1.notEqual(16)) {
                            Log.log().string("size not equal, 16 != ").unsigned(size1).newline().flush();
                        }

                        size = size.subtract(size1);
                        originalMemory = originalMemory.add(size1);
                    }
                }

                if (size.notEqual(0)) {
                    Log.log().string("oh no, size=").unsigned(size).newline().flush();
                }
            }
            return true;
        }
    }

    private class AllObjectsMarkingVisitor implements ObjectVisitor {

        @Override
        public boolean visitObject(Object obj) {
            ObjectHeaderImpl.setMarkedBit(obj);
            return true;
        }
    }

    private class SweepVisitor implements ObjectVisitor {

        private ChunkReleaser chunkReleaser;

        @Override
        public boolean visitObject(Object obj) {
            if (ObjectHeaderImpl.hasMarkedBit(obj)) {
                // ObjectHeaderImpl.clearMarkedBit(obj);
            } else {
                if (ObjectHeaderImpl.isAlignedObject(obj)) {
                    UnsignedWord objSize = LayoutEncoding.getSizeFromObjectInGC(obj);
                    AlignedHeapChunk.AlignedHeader chunk = AlignedHeapChunk.getEnclosingChunk(obj);

                    Pointer p = Word.objectToUntrackedPointer(obj);
                    Pointer freeListHead = chunk.getFreeListHead();
                    if (freeListHead.isNull()) {
                        // Init free list head
                        chunk.setFreeListHead(p);
                        p.writeWord(0, objSize);
                    } else if (p.equal(freeListHead.readWord(0))) {
                        // Extend gap
                        // freeListHead.writeWord(0, p.add(objSize));
                    } else {
                        // New gap
                    }
                    
                    boolean ZeroOutForTesting = true;
                    if (ZeroOutForTesting) {
                        UnsignedWord offset = WordFactory.unsigned(8);
                        while (objSize.aboveThan(offset)) {
                            p.writeWord(offset, WordFactory.nullPointer());
                            offset = offset.add(8);
                        }
                    }
                } else {
                    // Release the enclosing unaligned chunk.
                    UnalignedHeapChunk.UnalignedHeader chunk = UnalignedHeapChunk.getEnclosingChunk(obj);
                    space.extractUnalignedHeapChunk(chunk);
                    chunkReleaser.add(chunk);
                }
            }
            return true;
        }

        public void setChunkReleaser(ChunkReleaser chunkReleaser) {
            this.chunkReleaser = chunkReleaser;
        }
    }
}
