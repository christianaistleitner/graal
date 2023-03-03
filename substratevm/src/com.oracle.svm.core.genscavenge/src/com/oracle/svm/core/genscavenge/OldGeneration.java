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

import com.oracle.svm.core.UnmanagedMemoryUtil;
import com.oracle.svm.core.heap.ReferenceAccess;
import com.oracle.svm.core.hub.LayoutEncoding;
import org.graalvm.compiler.word.Word;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;

import com.oracle.svm.core.MemoryWalker;
import com.oracle.svm.core.AlwaysInline;
import com.oracle.svm.core.MemoryWalker;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.genscavenge.GCImpl.ChunkReleaser;
import com.oracle.svm.core.genscavenge.remset.RememberedSet;
import com.oracle.svm.core.heap.ObjectVisitor;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.thread.VMOperation;
import com.oracle.svm.core.util.VMError;
import org.graalvm.word.WordFactory;

import java.io.IOException;

/**
 * The old generation has only one {@link Space} for existing, newly-allocated or promoted objects
 * and uses a mark–compact algorithm for garbage collection.
 */
public final class OldGeneration extends Generation {

    private final Space space;

    private final GreyObjectsWalker toGreyObjectsWalker = new GreyObjectsWalker();
    private final CleanupVisitor cleanupVisitor = new CleanupVisitor();
    private final AllObjectsMarkingVisitor allObjectsMarkingVisitor = new AllObjectsMarkingVisitor();

    @Platforms(Platform.HOSTED_ONLY.class)
    OldGeneration(String name) {
        super(name);
        int age = HeapParameters.getMaxSurvivorSpaces() + 1;
        this.space = new Space("Old", true, age);
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
     * Promote an Object.
     */
    @AlwaysInline("GC performance")
    @Override
    public Object promoteAlignedObject(Object original, AlignedHeapChunk.AlignedHeader originalChunk, Space originalSpace) {
        if (originalSpace.isOldSpace()) {
            // TODO: Mark objects
            ObjectHeaderImpl.setMarkedBit(original);
            Log.noopLog().string("promoteAlignedObject (noop): ").object(original).newline().flush();
            return original;
        }
        assert originalSpace.isFromSpace();
        Log.noopLog().string("promoteAlignedObject (promote): ").object(original).newline().flush();
        Object copy = getSpace().promoteAlignedObject(original, originalSpace);
        ObjectHeaderImpl.setMarkedBit(copy);
        return copy;
    }

    @AlwaysInline("GC performance")
    @Override
    protected Object promoteUnalignedObject(Object original, UnalignedHeapChunk.UnalignedHeader originalChunk, Space originalSpace) {
        assert originalSpace.isFromSpace() || originalSpace.isOldSpace();
        if (!originalSpace.isOldSpace()) {
            Log.noopLog().string("promoteUnalignedObject (promote): ").object(original).newline().flush();
            getSpace().promoteUnalignedHeapChunk(originalChunk, originalSpace);
        } else {
            Log.noopLog().string("promoteUnalignedObject (noop): ").object(original).newline().flush();
            // RememberedSet.get().clearRememberedSet(originalChunk);
        }
        UnalignedHeapChunk.walkObjects(originalChunk, allObjectsMarkingVisitor);
        return original;
    }

    @Override
    protected boolean promoteChunk(HeapChunk.Header<?> originalChunk, boolean isAligned, Space originalSpace) {
        Log.log().string("promoteChunk").newline().flush();
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

    void releaseSpaces(ChunkReleaser chunkReleaser) {
        space.walkObjects(cleanupVisitor);
        // space.report(Log.log(), true);
        // getSpace().releaseChunks(chunkReleaser); TODO: dont free memory here as we would destroy data
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
            //if (!HeapChunk.getSpace(AlignedHeapChunk.getEnclosingChunk(obj)).isOldSpace()) {
            //    Log.log().object(HeapChunk.getSpace(AlignedHeapChunk.getEnclosingChunk(obj)));
            //}
            if (ObjectHeaderImpl.hasMarkedBit(obj)) {
                ObjectHeaderImpl.clearMarkedBit(obj);
                Log.noopLog().string("Cleared").newline().flush();
            } else {
                UnsignedWord size = LayoutEncoding.getSizeFromObjectInGC(obj);

                Pointer originalMemory = Word.objectToUntrackedPointer(obj);
                while (size.aboveOrEqual(16)) {
                    Log.noopLog().string("Filling ").unsigned(size).string(" bytes").newline().flush();
                    if (size.unsignedRemainder(16).aboveThan(0)) {
                        Pointer sampleMemory = Word.objectToUntrackedPointer(sampleObj24);
                        UnmanagedMemoryUtil.copyLongsForward(sampleMemory, originalMemory, WordFactory.unsigned(24));

                        UnsignedWord size1 = LayoutEncoding.getSizeFromObjectInGC(originalMemory.toObject());
                        if (size1.notEqual(24)) {
                            Log.log().string("size not equal, 24 != ").unsigned(size1).newline().flush();
                        }
                        size = size.subtract(size1);
                        originalMemory = originalMemory.add(size1);
                    } else {
                        Pointer sampleMemory = Word.objectToUntrackedPointer(sampleObj16);
                        UnmanagedMemoryUtil.copyLongsForward(sampleMemory, originalMemory, WordFactory.unsigned(16));

                        UnsignedWord size1 = LayoutEncoding.getSizeFromObjectInGC(originalMemory.toObject());
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

                //Log.log().string("Size that should be reclaimed: ").unsigned(size).newline().flush();
                // ReferenceAccess.singleton().writeObjectBarrieredAt(obj, WordFactory.zero(), new Object(), true);
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
}
