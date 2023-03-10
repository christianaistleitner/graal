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
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.SLOW_PATH_PROBABILITY;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.VERY_SLOW_PATH_PROBABILITY;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.probability;

import com.oracle.svm.core.NeverInline;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.UnmanagedMemoryUtil;
import com.oracle.svm.core.code.RuntimeCodeInfoMemory;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.graal.RuntimeCompilation;
import com.oracle.svm.core.heap.Heap;
import com.oracle.svm.core.heap.ObjectReferenceVisitor;
import com.oracle.svm.core.heap.ReferenceAccess;
import com.oracle.svm.core.heap.ReferenceInternals;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.hub.InteriorObjRefWalker;
import com.oracle.svm.core.identityhashcode.IdentityHashCodeSupport;
import com.oracle.svm.core.snippets.KnownIntrinsics;
import com.oracle.svm.core.stack.JavaStackWalk;
import com.oracle.svm.core.stack.JavaStackWalker;
import com.oracle.svm.core.thread.VMThreads;
import org.graalvm.compiler.word.ObjectAccess;
import org.graalvm.compiler.word.Word;
import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.function.CodePointer;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.AlwaysInline;
import com.oracle.svm.core.MemoryWalker;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.genscavenge.GCImpl.ChunkReleaser;
import com.oracle.svm.core.genscavenge.remset.RememberedSet;
import com.oracle.svm.core.heap.ObjectVisitor;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.thread.VMOperation;
import com.oracle.svm.core.util.VMError;

import java.lang.ref.Reference;

/**
 * The old generation has only one {@link Space} for existing, newly-allocated or promoted objects
 * and uses a mark–compact algorithm for garbage collection.
 */
public final class OldGeneration extends Generation {

    private final Space space;

    private final GreyObjectsWalker toGreyObjectsWalker = new GreyObjectsWalker();
    private final PlanningVisitor planningVisitor = new PlanningVisitor();
    private final FixingVisitor fixingVisitor = new FixingVisitor();
    private final RefFixingVisitor refFixingVisitor = new RefFixingVisitor();
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
        assert originalSpace.isFromSpace();
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

    void sweep() {
        // Phase 1: Compute and write relocation info
        Log.log().string("[OldGeneration.compactAndReleaseSpaces: planning phase]").newline().flush();
        AlignedHeapChunk.AlignedHeader aChunk = space.getFirstAlignedHeapChunk();
        while (aChunk.isNonNull()) {
            Log.log().string("[OldGeneration.sweep: planning phase, chunk=").zhex(aChunk).string("]").newline().flush();
            planningVisitor.init(aChunk);
            RelocationInfo.walkObjects(aChunk, planningVisitor);
            planningVisitor.finish();
            aChunk = HeapChunk.getNext(aChunk);
        }
    }

    @NeverInline("")
    void compact() {
        // Phase 2: Fix object references
        Log.log().string("[OldGeneration.compactAndReleaseSpaces: fixing phase]").newline().flush();
        AlignedHeapChunk.AlignedHeader aChunk = space.getFirstAlignedHeapChunk();
        while (aChunk.isNonNull()) {
            Log.log().string("[OldGeneration.compact: fixing phase, chunk=").zhex(aChunk)
                    .string(", top=").zhex(HeapChunk.getTopPointer(aChunk))
                    .string("]").newline().flush();
            fixingVisitor.setChunk(aChunk);
            RelocationInfo.walkObjects(aChunk, fixingVisitor);
            aChunk = HeapChunk.getNext(aChunk);
        }
        HeapImpl.getHeapImpl().walkImageHeapObjects(fixingVisitor);
        ThreadLocalMTWalker.walk(refFixingVisitor);
        refFixingVisitor.debug = true;
        if (RuntimeCompilation.isEnabled()) {
            RuntimeCodeInfoMemory.singleton().walkRuntimeMethodsDuringGC(runtimeCodeCacheWalker);
        }
        refFixingVisitor.debug = false;
        GCImpl.getGCImpl().blackenStackRoots(refFixingVisitor);
        UnalignedHeapChunk.UnalignedHeader uChunk = space.getFirstUnalignedHeapChunk();
        while (uChunk.isNonNull()) {
            Log.log().string("[OldGeneration.compact: fixing phase, chunk=").zhex(uChunk)
                    .string(", unaligned]").newline().flush();
            Pointer objPointer = UnalignedHeapChunk.getObjectStart(uChunk);
            Object obj = objPointer.toObject();
            fixingVisitor.visitObject(obj);
            uChunk = HeapChunk.getNext(uChunk);
        }

        // Phase 3: Copy objects to their new location
        Log.log().string("[OldGeneration.compactAndReleaseSpaces: compacting phase]").newline().flush();
        aChunk = space.getFirstAlignedHeapChunk();
        while (aChunk.isNonNull()) {
            Log.log().string("[OldGeneration.compactAndReleaseSpaces: compacting phase, chunk=").zhex(aChunk)
                    .string(", oldTop=").zhex(HeapChunk.getTopPointer(aChunk))
                    .string(", firstRelocInfo=").zhex(aChunk.getFirstRelocationInfo())
                    .string("]").newline().flush();
            compactingVisitor.setChunk(aChunk);
            RelocationInfo.walkObjects(aChunk, compactingVisitor);
            RememberedSet.get().clearRememberedSet(aChunk);
            RememberedSet.get().enableRememberedSetForChunk(aChunk); // update FirstObjectTable
            aChunk.setFirstRelocationInfo(null);
            Log.log().string("[OldGeneration.compactAndReleaseSpaces: compacting phase, chunk=").zhex(aChunk)
                    .string(", newTop=").zhex(HeapChunk.getTopPointer(aChunk))
                    .string("]").newline().flush();
            aChunk = HeapChunk.getNext(aChunk);
        }
    }

    void compactAndReleaseSpaces(ChunkReleaser chunkReleaser) {

        // Phase 0: Release unmarked unaligned chunks
        UnalignedHeapChunk.UnalignedHeader uChunk = space.getFirstUnalignedHeapChunk();
        while (uChunk.isNonNull()) {
            Pointer objPointer = UnalignedHeapChunk.getObjectStart(uChunk);
            Object obj = objPointer.toObject();
            if (ObjectHeaderImpl.hasMarkedBit(obj)) {
                // Clear and keep chunk
                ObjectHeaderImpl.clearMarkedBit(obj);
                RememberedSet.get().clearRememberedSet(uChunk);
            } else {
                // Release the enclosing unaligned chunk
                space.extractUnalignedHeapChunk(uChunk);
                chunkReleaser.add(uChunk);
            }
            uChunk = HeapChunk.getNext(uChunk);
        }

        //// Phase 3: Copy objects to their new location
        //Log.log().string("[OldGeneration.compactAndReleaseSpaces: compacting phase]").newline().flush();
        //AlignedHeapChunk.AlignedHeader aChunk = space.getFirstAlignedHeapChunk();
        //while (aChunk.isNonNull()) {
        //    Log.log().string("[OldGeneration.compactAndReleaseSpaces: compacting phase, chunk=").zhex(aChunk)
        //            .string(", oldTopOffset=").zhex(aChunk.getTopOffset())
        //            .string(", firstRelocInfo=").zhex(aChunk.getFirstRelocationInfo())
        //            .string("]").newline().flush();
        //    compactingVisitor.setChunk(aChunk);
        //    RelocationInfo.walkObjects(aChunk, compactingVisitor);
        //    RememberedSet.get().clearRememberedSet(aChunk);
        //    aChunk.setFirstRelocationInfo(null);
        //    Log.log().string("[OldGeneration.compactAndReleaseSpaces: compacting phase, chunk=").zhex(aChunk)
        //            .string(", newTopOffset=").zhex(aChunk.getTopOffset())
        //            .string("]").newline().flush();
        //    aChunk = HeapChunk.getNext(aChunk);
        //}

    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    void prepareForPromotion() {
        toGreyObjectsWalker.setScanStart(getSpace());
    }

    /**
     * @return {@code true} if gray objects still exist
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

    private class AllObjectsMarkingVisitor implements ObjectVisitor {

        @Override
        public boolean visitObject(Object obj) {
            ObjectHeaderImpl.setMarkedBit(obj);
            return true;
        }
    }

    private class PlanningVisitor implements ObjectVisitor {

        private Pointer relocationInfoPointer;

        private Pointer relocationPointer;

        private AlignedHeapChunk.AlignedHeader chunk;

        private UnsignedWord gapSize;

        @NeverInline("Debug")
        public boolean visitObject(Object obj) {
            //Log.log().string("B").newline().flush();
            Pointer objPointer = Word.objectToUntrackedPointer(obj);
            UnsignedWord objSize = LayoutEncoding.getSizeFromObjectInGC(obj);
            if (ObjectHeaderImpl.hasMarkedBit(obj)) {
                // ObjectHeaderImpl.clearMarkedBit(obj); TODO: clear later
                if (gapSize.notEqual(0)) {
                    Log.log().string("Gap from ").zhex(objPointer.subtract(gapSize))
                            .string(" to ").zhex(objPointer)
                            .string(" (").unsigned(gapSize).string(" bytes)")
                            .newline().flush();

                    if (relocationInfoPointer.isNull()) {
                        if (chunk.isNull()){
                            Log.log().string("Nooooo! chunk=").zhex(chunk).newline().flush();
                        }
                        chunk.setFirstRelocationInfo(objPointer);
                    } else {
                        int offset = (int) objPointer.subtract(relocationInfoPointer).rawValue();
                        RelocationInfo.writeNextPlugOffset(relocationInfoPointer, offset);

                        Log.log().string("Updated relocation info at ").zhex(relocationInfoPointer)
                                .string(": nextPlugOffset=").zhex(offset)
                                .newline().flush();
                    }
                    relocationInfoPointer = objPointer;
                    RelocationInfo.writeRelocationPointer(relocationInfoPointer, relocationPointer);
                    RelocationInfo.writeGapSize(relocationInfoPointer, (int) gapSize.rawValue());
                    RelocationInfo.writeNextPlugOffset(relocationInfoPointer, 0);

                    Log.log().string("Wrote relocation info at ").zhex(relocationInfoPointer)
                            .string(": relocationPointer=").zhex(relocationPointer)
                            .string(": gapSize=").unsigned(gapSize)
                            .string(": nextPlugOffset=").zhex(0)
                            .newline().flush();

                    gapSize = WordFactory.zero();
                }
                relocationPointer = relocationPointer.add(getMovedObjectSize(obj));
            } else {
                gapSize = gapSize.add(objSize);
                if (gapSize.unsignedRemainder(64).equal(0)) {
                    Log.noopLog().string("Added ").unsigned(objSize).string(" to gap size => ").unsigned(gapSize)
                            .string(", obj=").object(obj).newline().flush();
                }
            }
            return true;
        }

        public void init(AlignedHeapChunk.AlignedHeader chunk) {
            this.chunk = chunk;
            this.relocationInfoPointer = WordFactory.nullPointer();
            this.relocationPointer = AlignedHeapChunk.getObjectsStart(chunk);
            this.gapSize =  WordFactory.zero();
        }

        public void finish() {
            if (gapSize.notEqual(0)) {
                Pointer topPointer = HeapChunk.getTopPointer(chunk);
                Log.log().string("Gap at chunk end from ").zhex(topPointer.subtract(gapSize))
                        .string(" to ").zhex(topPointer)
                        .string(" (").unsigned(gapSize).string(" bytes)")
                        .newline().flush();

                if (relocationInfoPointer.isNull()) {
                    if (chunk.isNull()){
                        Log.log().string("Nooooo! chunk=").zhex(chunk).newline().flush();
                    }
                    chunk.setFirstRelocationInfo(topPointer);
                } else {
                    int offset = (int) topPointer.subtract(relocationInfoPointer).rawValue();
                    RelocationInfo.writeNextPlugOffset(relocationInfoPointer, offset);

                    Log.log().string("Updated relocation info at ").zhex(relocationInfoPointer)
                            .string(": nextPlugOffset=").zhex(offset)
                            .newline().flush();
                }
                relocationInfoPointer = topPointer;
                RelocationInfo.writeRelocationPointer(relocationInfoPointer, relocationPointer);
                RelocationInfo.writeGapSize(relocationInfoPointer, (int) gapSize.rawValue());
                RelocationInfo.writeNextPlugOffset(relocationInfoPointer, 0);

                Log.log().string("Wrote relocation info at ").zhex(relocationInfoPointer)
                        .string(": relocationPointer=").zhex(relocationPointer)
                        .string(": gapSize=").unsigned(gapSize)
                        .string(": nextPlugOffset=").zhex(0)
                        .newline().flush();
            }
        }
    }

    private class FixingVisitor implements ObjectVisitor {

        private AlignedHeapChunk.AlignedHeader chunk;

        @Override
        public boolean visitObject(Object obj) {

            // fixes Target_java_lang_ref_Reference.referent
            /*
            DynamicHub hub = KnownIntrinsics.readHub(obj);
            if (probability(SLOW_PATH_PROBABILITY, hub.isReferenceInstanceClass())) {
                Reference<?> dr = (Reference<?>) obj;

                Pointer objRef = ReferenceInternals.getReferentFieldAddress(dr);

                Pointer p = ReferenceAccess.singleton().readObjectAsUntrackedPointer(objRef, true);

                if (p.isNonNull() && !Heap.getHeap().isInImageHeap(p)) {
                    Log.log().string("A").newline().flush();
                    if (ObjectHeaderImpl.hasMarkedBit(p.toObject())) {
                        Log.log().string("B").newline().flush();
                        refFixingVisitor.visitObjectReference(objRef, true, dr);
                    } else {
                        Log.log().string("C").newline().flush();
                        ReferenceInternals.setReferent(dr, null); // dead
                        Reference<?> next = (ReferenceObjectProcessing.rememberedRefsList != null) ? ReferenceObjectProcessing.rememberedRefsList : dr;
                        ReferenceInternals.setNextDiscovered(dr, next);
                        ReferenceObjectProcessing.rememberedRefsList = dr;
                    }
                }
            }
            */

            InteriorObjRefWalker.walkObjectInline(obj, refFixingVisitor);
            return true;
        }

        public void setChunk(AlignedHeapChunk.AlignedHeader chunk) {
            this.chunk = chunk;
            refFixingVisitor.setChunk(chunk);
        }
    }

    private class RefFixingVisitor implements ObjectReferenceVisitor {

        public boolean debug = false;
        private AlignedHeapChunk.AlignedHeader chunk;

        @Override
        public boolean visitObjectReference(Pointer objRef, boolean compressed, Object holderObject) {
            return visitObjectReferenceInline(objRef, 0, compressed, holderObject);
        }

        @Override
        public boolean visitObjectReferenceInline(Pointer objRef, int innerOffset, boolean compressed, Object holderObject) {
            Pointer offsetP = ReferenceAccess.singleton().readObjectAsUntrackedPointer(objRef, compressed);
            assert offsetP.isNonNull() || innerOffset == 0;

            Pointer p = offsetP.subtract(innerOffset);
            if (p.isNull() || HeapImpl.getHeapImpl().isInImageHeap(p)) {
                return true;
            }

            Object obj = p.toObject();
            if (ObjectHeaderImpl.isUnalignedObject(obj)) {
                if (HeapImpl.getHeapImpl().isInImageHeap(holderObject)){
                    RememberedSet.get().dirtyCardIfNecessary(holderObject, obj);
                }
                return true;
            }

            if (ReferenceObjectProcessing.isInOldSpace(holderObject) && !ObjectHeaderImpl.hasMarkedBit(holderObject)) {
                Log.log().string("[ERROR: Fixing dead holder object died, obj=").object(obj)
                        .string(", holderObject=").object(holderObject)
                        .string("]\n").flush();
                assert false : "Referred object died!";
            }

            if (!ObjectHeaderImpl.hasMarkedBit(obj)) {
                Log.log().string("[ERROR: Referred object died, obj=").object(obj)
                        .string(", holderObject=").object(holderObject)
                        .string("]\n").flush();
                assert false : "Referred object died!";
            }

            Pointer relocationInfo = AlignedHeapChunk.getEnclosingChunkFromObjectPointer(p).getFirstRelocationInfo();
            if (relocationInfo.isNull() || relocationInfo.aboveThan(p)) {
                if (HeapImpl.getHeapImpl().isInImageHeap(holderObject)){
                    RememberedSet.get().dirtyCardIfNecessary(holderObject, obj);
                }
                return true;
            }

            Pointer nextRelocationInfo = RelocationInfo.getNextRelocationInfo(relocationInfo);
            while (nextRelocationInfo.isNonNull() && nextRelocationInfo.belowOrEqual(p)) {
                relocationInfo = nextRelocationInfo;
                nextRelocationInfo = RelocationInfo.getNextRelocationInfo(relocationInfo);
            }

            Pointer relocationPointer = RelocationInfo.readRelocationPointer(relocationInfo);
            Pointer newLocationOld = relocationPointer.add(p.subtract(relocationInfo));

            Pointer newLocation = getRelocatedObjectPointer(p);
            assert newLocation.equal(newLocationOld);

            Object offsetObj = (innerOffset == 0) ? newLocation.toObject() : newLocation.add(innerOffset).toObject();
            ReferenceAccess.singleton().writeObjectAt(objRef, offsetObj, compressed);

            // if (ObjectHeaderImpl.hasRememberedSet(ObjectHeaderImpl.readHeaderFromObject(holderObject))) {
            //     RememberedSet.get().dirtyCardIfNecessary(holderObject, newLocation.toObject());
            // }
            if (HeapImpl.getHeapImpl().isInImageHeap(holderObject)){
                RememberedSet.get().dirtyCardIfNecessary(holderObject, newLocation.toObject());
            }

            if (debug) {
                Log.log().string("Updated location, old=").zhex(p)
                        .string(", new=").zhex(newLocation)
                        .string(", diff=").signed(newLocation.subtract(p))
                        .string(", holderObject=").object(holderObject)
                        .string(", objRef=").zhex(objRef)
                        .string(", innerOffset=").signed(innerOffset)
                        .string(", compressed=").bool(compressed)
                        .string(", relocationInfo=").zhex(relocationInfo)
                        .string(", relocationPointer=").zhex(relocationPointer)
                        .newline().flush();
            }

            if (!ObjectHeaderImpl.hasMarkedBit(obj)) {
                Log.log().string("Updated location but object is not marked, old=").zhex(p)
                        .string(", new=").zhex(newLocation)
                        .string(", diff=").signed(newLocation.subtract(p))
                        .string(", holderObject=").object(holderObject)
                        .string(", objRef=").zhex(objRef)
                        .string(", innerOffset=").signed(innerOffset)
                        .string(", compressed=").bool(compressed)
                        .string(", relocationInfo=").zhex(relocationInfo)
                        .string(", relocationPointer=").zhex(relocationPointer)
                        .newline().flush();
            }

            chunk = AlignedHeapChunk.getEnclosingChunkFromObjectPointer(p);
            if (AlignedHeapChunk.getObjectsStart(chunk).aboveThan(newLocation) || AlignedHeapChunk.getObjectsEnd(chunk).belowOrEqual(newLocation)) {
                Log.log().string("ERROR - Updated location, old=").zhex(p)
                        .string(", new=").zhex(newLocation)
                        .string(", diff=").signed(newLocation.subtract(p))
                        .string(", holderObject=").zhex(Word.objectToUntrackedPointer(holderObject))
                        .string(", objRef=").zhex(objRef)
                        .string(", relocationInfo=").zhex(relocationInfo)
                        .string(", relocationPointer=").zhex(relocationPointer)
                        .newline().flush();
            }

            if (p.and(15).isNull()) {
                Log.noopLog().string("Updated location, old=").zhex(p)
                        .string(", new=").zhex(newLocation)
                        .string(", diff=").signed(newLocation.subtract(p))
                        .string(", holderObject=").zhex(Word.objectToUntrackedPointer(holderObject))
                        .string(", objRef=").zhex(objRef)
                        .string(", relocationInfo=").zhex(relocationInfo)
                        .string(", relocationPointer=").zhex(relocationPointer)
                        .newline().flush();
            }

            return true;
        }

        public void setChunk(AlignedHeapChunk.AlignedHeader chunk) {
            this.chunk = chunk;
        }
    }


    private class CompactingVisitor implements ObjectVisitor {

        private Pointer relocationPointer;
        private Pointer relocationInfoPointer;
        private Pointer nextRelocationInfoPointer;

        private AlignedHeapChunk.AlignedHeader chunk;

        @Override
        public boolean visitObject(Object obj) {
            ObjectHeaderImpl.clearMarkedBit(obj); // TODO: ?
            if (relocationInfoPointer.isNull()) {
                return true;
            }

            Pointer objPointer = Word.objectToUntrackedPointer(obj);
            if (objPointer.belowThan(relocationInfoPointer)) {
                return true;
            }

            if (nextRelocationInfoPointer.isNonNull() && objPointer.aboveOrEqual(nextRelocationInfoPointer)) {
                relocationInfoPointer = nextRelocationInfoPointer;
                nextRelocationInfoPointer = RelocationInfo.getNextRelocationInfo(relocationInfoPointer);
                relocationPointer = RelocationInfo.readRelocationPointer(relocationInfoPointer);
                Log.log().string("Jumped relocation info, current=").zhex(relocationInfoPointer)
                        .string(", next=").zhex(nextRelocationInfoPointer)
                        .newline().flush();
            }

            Pointer newLocation = objPointer.subtract(relocationInfoPointer).add(relocationPointer);

            Log.noopLog().string("New relocation")
                    .string(", newLocation=").zhex(newLocation)
                    .string(", objPointer=").zhex(objPointer)
                    .string(", relocationInfoPointer=").zhex(relocationInfoPointer)
                    .string(", relocationPointer=").zhex(relocationPointer)
                    .newline().flush();

            UnsignedWord copySize = copyObject(obj, newLocation);

            HeapChunk.setTopPointer(chunk, newLocation.add(copySize));

            return true;
        }

        public void setChunk(AlignedHeapChunk.AlignedHeader chunk) {
            this.chunk = chunk;
            relocationInfoPointer = chunk.getFirstRelocationInfo();
            nextRelocationInfoPointer = WordFactory.nullPointer();
            relocationPointer = WordFactory.nullPointer();
            if (relocationInfoPointer.isNonNull()) {
                int offset = RelocationInfo.readNextPlugOffset(relocationInfoPointer);
                if (offset > 0) {
                    nextRelocationInfoPointer = relocationInfoPointer.add(offset);
                }
                relocationPointer = RelocationInfo.readRelocationPointer(relocationInfoPointer);
            }
        }
    }

    private static UnsignedWord getMovedObjectSize(Object obj) {
        if (!ConfigurationValues.getObjectLayout().hasFixedIdentityHashField()) {
            Word header = ObjectHeaderImpl.readHeaderFromObject(obj);
            if (probability(SLOW_PATH_PROBABILITY, ObjectHeaderImpl.hasIdentityHashFromAddressInline(header))) {
                return LayoutEncoding.getSizeFromObjectInlineInGC(obj, true);
            }
        }
        return LayoutEncoding.getSizeFromObjectInlineInGC(obj, false);
    }

    private UnsignedWord copyObject(Object obj, Pointer dest) {
        assert VMOperation.isGCInProgress();
        assert ObjectHeaderImpl.isAlignedObject(obj);

        // TODO: code cleanup

        UnsignedWord originalSize = LayoutEncoding.getSizeFromObjectInlineInGC(obj, false);
        UnsignedWord copySize = originalSize;
        boolean addIdentityHashField = false;
        if (!ConfigurationValues.getObjectLayout().hasFixedIdentityHashField()) {
            Word header = ObjectHeaderImpl.readHeaderFromObject(obj);
            if (probability(SLOW_PATH_PROBABILITY, ObjectHeaderImpl.hasIdentityHashFromAddressInline(header))) {
                addIdentityHashField = true;
                copySize = LayoutEncoding.getSizeFromObjectInlineInGC(obj, true);
            }
        }

        if (probability(VERY_SLOW_PATH_PROBABILITY, dest.isNull())) {
            return WordFactory.zero();
        }

        /*
         * This does a direct memory copy, without regard to whether the copied data contains object
         * references. That's okay, because all references in the copy are visited and overwritten
         * later on anyways (the card table is also updated at that point if necessary).
         */
        Pointer originalMemory = Word.objectToUntrackedPointer(obj);
        Log.noopLog().string("Copying object from ").zhex(originalMemory).character('-').zhex(originalMemory.add(originalSize))
                .string(" (").unsigned(originalSize).string(" B)")
                .string(" to ").zhex(dest).character('-').zhex(dest.add(copySize))
                .string(" (").unsigned(copySize).string(" B)")
                .newline().flush();
        UnmanagedMemoryUtil.copyLongsForward(originalMemory, dest, copySize);

        if (probability(SLOW_PATH_PROBABILITY, addIdentityHashField)) {
            Log.log().string("Added identity hash field!\n").flush();
            Object copy = dest.toObject();
            // Must do first: ensures correct object size below and in other places
            int value = IdentityHashCodeSupport.computeHashCodeFromAddress(obj);
            int offset = LayoutEncoding.getOptionalIdentityHashOffset(copy);
            ObjectAccess.writeInt(copy, offset, value, IdentityHashCodeSupport.IDENTITY_HASHCODE_LOCATION);
            ObjectHeaderImpl.getObjectHeaderImpl().setIdentityHashInField(copy);
        }

        return copySize;
    }

    /**
     * Binary layout:
     * <pre>
     * 64-bit mode:
     * +-------------------------+---------------+-----------------------+
     * | relocation pointer (8B) | gap size (4B) | next plug offset (4B) |
     * +-------------------------+---------------+-----------------------+
     *                                                                   ^p
     * 32-bit mode:
     * +-------------------------+---------------+-----------------------+
     * | relocation pointer (4B) | gap size (2B) | next plug offset (2B) |
     * +-------------------------+---------------+-----------------------+
     *                                                                   ^p
     * </pre>
     */
    private static class RelocationInfo {

        private static void writeRelocationPointer(Pointer p, Pointer relocationPointer) {
            if (ObjectHeaderImpl.getReferenceSize() == Integer.BYTES) {
                // TODO
                assert false;
            } else {
                p.writeWord(-16, relocationPointer);
            }
        }

        private static Pointer readRelocationPointer(Pointer p) {
            if (ObjectHeaderImpl.getReferenceSize() == Integer.BYTES) {
                // TODO
                assert false;
                return WordFactory.nullPointer();
            } else {
                return p.readWord(-16);
            }
        }

        private static void writeGapSize(Pointer p, int gapSize) {
            if (ObjectHeaderImpl.getReferenceSize() == Integer.BYTES) {
                // TODO
                assert false;
            } else {
                p.writeInt(-8, gapSize);
            }
        }

        private static int readGapSize(Pointer p) {
            if (ObjectHeaderImpl.getReferenceSize() == Integer.BYTES) {
                // TODO
                assert false;
                return 0;
            } else {
                return p.readInt(-8);
            }
        }

        private static void writeNextPlugOffset(Pointer p, int offset) {
            if (ObjectHeaderImpl.getReferenceSize() == Integer.BYTES) {
                // TODO
                assert false;
            } else {
                p.writeInt(-4, offset);
            }
        }

        private static int readNextPlugOffset(Pointer p) {
            if (ObjectHeaderImpl.getReferenceSize() == Integer.BYTES) {
                // TODO
                assert false;
                return 0;
            } else {
                return p.readInt(-4);
            }
        }

        private static Pointer getNextRelocationInfo(Pointer p) {
            int offset = readNextPlugOffset(p);
            if (offset == 0) {
                return WordFactory.nullPointer();
            }
            return p.add(offset);
        }

        /**
         * Special implementation that is capable of handling gaps by reading the relocation info.
         *
         * @see HeapChunk#walkObjectsFrom
         * @see AlignedHeapChunk#walkObjects
         */
        private static void walkObjects(AlignedHeapChunk.AlignedHeader chunkHeader, ObjectVisitor visitor) {
            Pointer cursor = AlignedHeapChunk.getObjectsStart(chunkHeader);
            Pointer top = HeapChunk.getTopPointer(chunkHeader); // top cannot move in this case
            Pointer relocationInfo = chunkHeader.getFirstRelocationInfo();

            while (cursor.belowThan(top)) {

                // jump gaps
                if (relocationInfo.isNonNull()) {
                    int gapSize = RelocationInfo.readGapSize(relocationInfo);
                    if (cursor.aboveOrEqual(relocationInfo.subtract(gapSize))) {
                        cursor = relocationInfo;
                        relocationInfo = RelocationInfo.getNextRelocationInfo(relocationInfo);
                        continue;
                    }
                }

                Object obj = cursor.toObject();
                UnsignedWord objSize = LayoutEncoding.getSizeFromObjectInlineInGC(obj);

                if (!visitor.visitObject(obj)) {
                    return;
                }

                cursor = cursor.add(objSize);
            }
        }
    }

    static Object getRelocatedObject(Pointer p) {
        return getRelocatedObjectPointer(p).toObject();
    }

    static Pointer getRelocatedObjectPointer(Pointer p) {
        assert ObjectHeaderImpl.isAlignedObject(p.toObject()) : "Unaligned objects are not supported!";

        AlignedHeapChunk.AlignedHeader aChunk = AlignedHeapChunk.getEnclosingChunkFromObjectPointer(p);
        Pointer relocationInfo = aChunk.getFirstRelocationInfo();
        if (relocationInfo.isNull() || p.belowThan(relocationInfo)) {
            return p; // not relocated
        }

        Pointer nextRelocationInfo = RelocationInfo.getNextRelocationInfo(relocationInfo);
        while (nextRelocationInfo.isNonNull() && nextRelocationInfo.belowOrEqual(p)) {
            relocationInfo = nextRelocationInfo;
            nextRelocationInfo = RelocationInfo.getNextRelocationInfo(relocationInfo);
        }

        assert relocationInfo.belowOrEqual(p);

        Pointer relocationPointer = RelocationInfo.readRelocationPointer(relocationInfo);
        Pointer relocationOffset = p.subtract(relocationInfo);

        return relocationPointer.add(relocationOffset);
    }
}
