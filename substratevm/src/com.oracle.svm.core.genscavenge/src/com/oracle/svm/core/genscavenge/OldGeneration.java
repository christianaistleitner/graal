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

import static com.oracle.svm.core.snippets.KnownIntrinsics.readCallerStackPointer;
import static com.oracle.svm.core.snippets.KnownIntrinsics.readReturnAddress;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.EXTREMELY_SLOW_PATH_PROBABILITY;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.probability;

import com.oracle.svm.core.NeverInline;
import com.oracle.svm.core.code.RuntimeCodeInfoMemory;
import com.oracle.svm.core.genscavenge.tenured.AllObjectsMarkingVisitor;
import com.oracle.svm.core.genscavenge.tenured.CompactingVisitor;
import com.oracle.svm.core.genscavenge.tenured.FixingVisitor;
import com.oracle.svm.core.genscavenge.tenured.PlanningVisitor;
import com.oracle.svm.core.genscavenge.tenured.RefFixingVisitor;
import com.oracle.svm.core.genscavenge.tenured.RelocationInfo;
import com.oracle.svm.core.graal.RuntimeCompilation;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.function.CodePointer;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;

import com.oracle.svm.core.AlwaysInline;
import com.oracle.svm.core.MemoryWalker;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.genscavenge.GCImpl.ChunkReleaser;
import com.oracle.svm.core.genscavenge.remset.RememberedSet;
import com.oracle.svm.core.heap.ObjectVisitor;
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
    private final PlanningVisitor planningVisitor = new PlanningVisitor();
    private final RefFixingVisitor refFixingVisitor = new RefFixingVisitor();
    private final FixingVisitor fixingVisitor = new FixingVisitor(refFixingVisitor);
    private final CompactingVisitor compactingVisitor = new CompactingVisitor();
    private final AllObjectsMarkingVisitor allObjectsMarkingVisitor = new AllObjectsMarkingVisitor();
    private final RuntimeCodeCacheWalker runtimeCodeCacheWalker = new RuntimeCodeCacheWalker(refFixingVisitor);

    @Platforms(Platform.HOSTED_ONLY.class)
    OldGeneration(String name) {
        super(name);
        int age = HeapParameters.getMaxSurvivorSpaces() + 1;
        this.space = new Space("tenuredSpace", true, age);
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
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    @Override
    public Object promoteAlignedObject(Object original, AlignedHeapChunk.AlignedHeader originalChunk, Space originalSpace) {
        assert originalSpace.isFromSpace() && !originalSpace.isOldSpace();
        Object copy = getSpace().promoteAlignedObject(original, originalSpace);
        ObjectHeaderImpl.setMarkedBit(copy);
        return copy;
    }

    @AlwaysInline("GC performance")
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
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
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
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
        Log.log().string("Pinned object!!!!!!!!!!!!!!!!!!!!\n").flush();
        // TODO: Pinned objects mustn't move when compressing chunks!
    }

    void planning() {
        // Phase 1: Compute and write relocation info
        planningVisitor.init(space);
        space.walkAlignedHeapChunks(planningVisitor);
    }

    @NeverInline("Starting a stack walk in the caller frame.")
    void fixing(ChunkReleaser chunkReleaser, Timers timers) {
        // Phase 2: Fix object references
        timers.tenuredFixingAlignedChunks.open();
        AlignedHeapChunk.AlignedHeader aChunk = space.getFirstAlignedHeapChunk();
        while (aChunk.isNonNull()) {
            Log.log().string("[OldGeneration.fixing: fixing phase, chunk=").zhex(aChunk)
                    .string(", top=").zhex(HeapChunk.getTopPointer(aChunk))
                    .string("]").newline().flush();
            RelocationInfo.walkObjects(aChunk, fixingVisitor);
            aChunk = HeapChunk.getNext(aChunk);
        }
        timers.tenuredFixingAlignedChunks.close();

        timers.tenuredFixingImageHeap.open();
        HeapImpl.getHeapImpl().walkImageHeapObjects(fixingVisitor);
        timers.tenuredFixingImageHeap.close();

        timers.tenuredFixingThreadLocal.open();
        ThreadLocalMTWalker.walk(refFixingVisitor);
        timers.tenuredFixingThreadLocal.close();

        timers.tenuredFixingRuntimeCodeCache.open();
        refFixingVisitor.debug = true;
        if (RuntimeCompilation.isEnabled()) {
            RuntimeCodeInfoMemory.singleton().walkRuntimeMethodsDuringGC(runtimeCodeCacheWalker);
        }
        refFixingVisitor.debug = false;
        timers.tenuredFixingRuntimeCodeCache.close();

        /*
         * Fix object references located on the stack.
         */
        timers.tenuredFixingStack.open();
        try {
            Pointer sp = readCallerStackPointer();
            CodePointer ip = readReturnAddress();
            GCImpl.walkStackRoots(refFixingVisitor, sp, ip);
        } finally {
            timers.tenuredFixingStack.close();
        }

        /*
         * Check unaligned objects. Fix its contained references if the object is marked.
         * Add the chunk to the releaser's list in case the object is not marked and thus won't survive.
         */
        timers.tenuredFixingUnalignedChunks.open();
        try {
            UnalignedHeapChunk.UnalignedHeader uChunk = space.getFirstUnalignedHeapChunk();
            while (uChunk.isNonNull()) {
                Pointer objPointer = UnalignedHeapChunk.getObjectStart(uChunk);
                Object obj = objPointer.toObject();
                if (ObjectHeaderImpl.hasMarkedBit(obj)) {
                    ObjectHeaderImpl.clearMarkedBit(obj);
                    RememberedSet.get().clearRememberedSet(uChunk);
                    fixingVisitor.visitObject(obj);
                } else {
                    space.extractUnalignedHeapChunk(uChunk);
                    chunkReleaser.add(uChunk);
                }
                uChunk = HeapChunk.getNext(uChunk);
            }
        } finally {
            timers.tenuredFixingUnalignedChunks.close();
        }
    }

    void compacting(Timers timers) {
        // Phase 3: Copy objects to their new location
        timers.tenuredCompactingCunks.open();
        AlignedHeapChunk.AlignedHeader chunk = space.getFirstAlignedHeapChunk();
        while (chunk.isNonNull()) {
            Log.log().string("[OldGeneration.compacting: chunk=").zhex(chunk)
                    .string("]\n").flush();

            compactingVisitor.init(chunk);
            RelocationInfo.walkObjects(chunk, compactingVisitor);
            compactingVisitor.finish();

            chunk = HeapChunk.getNext(chunk);
        }
        timers.tenuredCompactingCunks.close();

        chunk = space.getFirstAlignedHeapChunk();
        timers.tenuredUpdatingRememberedSet.open();
        while (chunk.isNonNull()) {
            Log.log().string("[OldGeneration.compacting: chunk=").zhex(chunk)
                    .string(", top=").zhex(HeapChunk.getTopPointer(chunk))
                    .string(", done]\n").flush();

            // clear CardTable and update FirstObjectTable
            // TODO: Build the FirstObjectTable during compaction.
            RememberedSet.get().enableRememberedSetForChunk(chunk);

            chunk = HeapChunk.getNext(chunk);
        }
        timers.tenuredUpdatingRememberedSet.close();
    }

    void releaseSpaces(ChunkReleaser chunkReleaser) {
        // Release empty aligned chunks.
        AlignedHeapChunk.AlignedHeader aChunk = space.getFirstAlignedHeapChunk();
        while (aChunk.isNonNull()) {
            if (HeapChunk.getTopPointer(aChunk).equal(AlignedHeapChunk.getObjectsStart(aChunk))) {
                // Release the empty aligned chunk.
                space.extractAlignedHeapChunk(aChunk);
                chunkReleaser.add(aChunk);
            }
            aChunk = HeapChunk.getNext(aChunk);
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    void prepareForPromotion() {
        toGreyObjectsWalker.setScanStart(getSpace());
    }

    /**
     * @return {@code true} if grey objects still exist
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
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
        return space.getChunkBytes();
    }

    UnsignedWord computeObjectBytes() {
        return space.computeObjectBytes();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    @SuppressWarnings("static-method")
    AlignedHeapChunk.AlignedHeader requestAlignedChunk() {
        assert VMOperation.isGCInProgress() : "Should only be called from the collector.";
        AlignedHeapChunk.AlignedHeader chunk = HeapImpl.getChunkProvider().produceAlignedChunk();
        if (probability(EXTREMELY_SLOW_PATH_PROBABILITY, chunk.isNull())) {
            throw VMError.shouldNotReachHere("OldGeneration.requestAlignedChunk: failure to allocate aligned chunk");
        }
        RememberedSet.get().enableRememberedSetForChunk(chunk);
        return chunk;
    }
}
