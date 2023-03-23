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
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.SLOW_PATH_PROBABILITY;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.VERY_SLOW_PATH_PROBABILITY;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.probability;

import com.oracle.svm.core.NeverInline;
import com.oracle.svm.core.UnmanagedMemoryUtil;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.heap.ObjectReferenceVisitor;
import com.oracle.svm.core.heap.ReferenceAccess;
import com.oracle.svm.core.hub.InteriorObjRefWalker;
import com.oracle.svm.core.identityhashcode.IdentityHashCodeSupport;
import org.graalvm.compiler.word.ObjectAccess;
import org.graalvm.compiler.word.Word;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
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

        // Phase 1: Compute and write relocation info
        Log.log().string("[OldGeneration.compactAndReleaseSpaces: planning phase]").newline().flush();
        AlignedHeapChunk.AlignedHeader aChunk = space.getFirstAlignedHeapChunk();
        while (aChunk.isNonNull()) {
            Log.log().string("planning chunk=").zhex(aChunk).newline().flush();
            planningVisitor.init(aChunk);
            RelocationInfo.walkObjects(aChunk, planningVisitor);
            aChunk = HeapChunk.getNext(aChunk);
        }

        // Phase 2: Fix object references
        Log.log().string("[OldGeneration.compactAndReleaseSpaces: fixing phase]").newline().flush();
        aChunk = space.getFirstAlignedHeapChunk();
        while (aChunk.isNonNull()) {
            fixingVisitor.setChunk(aChunk);
            RelocationInfo.walkObjects(aChunk, fixingVisitor);
            aChunk = HeapChunk.getNext(aChunk);
        }

        // Phase 3: Copy objects to their new location
        Log.log().string("[OldGeneration.compactAndReleaseSpaces: compacting phase]").newline().flush();
        aChunk = space.getFirstAlignedHeapChunk();
        while (aChunk.isNonNull()) {
            Log.log().string("[OldGeneration.compactAndReleaseSpaces: compacting phase, chunk=").zhex(aChunk)
                    .string(", oldTopOffset=").zhex(aChunk.getTopOffset())
                    .string(", firstRelocInfo=").zhex(aChunk.getFirstRelocationInfo())
                    .string("]").newline().flush();
            compactingVisitor.setChunk(aChunk);
            RelocationInfo.walkObjects(aChunk, compactingVisitor);
            RememberedSet.get().clearRememberedSet(aChunk);
            aChunk.setFirstRelocationInfo(null);
            Log.log().string("[OldGeneration.compactAndReleaseSpaces: compacting phase, chunk=").zhex(aChunk)
                    .string(", newTopOffset=").zhex(aChunk.getTopOffset())
                    .string("]").newline().flush();
            aChunk = HeapChunk.getNext(aChunk);
        }
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

    private class AllObjectsMarkingVisitor implements ObjectVisitor {

        @Override
        public boolean visitObject(Object obj) {
            ObjectHeaderImpl.setMarkedBit(obj);
            return true;
        }
    }

    private class PlanningVisitor implements ObjectVisitor {

        private Pointer relocationInfoPointer = WordFactory.nullPointer();

        private Pointer relocationPointer;

        private AlignedHeapChunk.AlignedHeader chunk;

        private UnsignedWord gapSize = WordFactory.zero();

        @NeverInline("Debug")
        public boolean visitObject(Object obj) {
            //Log.log().string("B").newline().flush();
            Pointer objPointer = Word.objectToUntrackedPointer(obj);
            UnsignedWord objSize = LayoutEncoding.getSizeFromObjectInGC(obj);
            if (ObjectHeaderImpl.hasMarkedBit(obj)) {
                ObjectHeaderImpl.clearMarkedBit(obj);
                if (gapSize.notEqual(0)) {
                    if (relocationInfoPointer.isNull()) {
                        if (chunk.isNull()){
                            Log.log().string("Nooooo! chunk=").zhex(chunk).newline().flush();
                        }
                        chunk.setFirstRelocationInfo(objPointer);
                    } else {
                        int offset = (int) objPointer.subtract(relocationInfoPointer).rawValue();
                        RelocationInfo.writeNextPlugOffset(relocationInfoPointer, offset);

                        Log.log().string("Updated relocation info at ").zhex(relocationInfoPointer)
                                .string(": relocationPointer=").zhex(relocationPointer)
                                .string(": gapSize=").unsigned(gapSize)
                                .string(": nextPlugOffset=").zhex(offset)
                                .newline().flush();
                    }
                    relocationInfoPointer = objPointer;
                    Log.log().string("new relocationInfoPointer= ").zhex(relocationInfoPointer).newline().flush();
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
            }
            return true;
        }

        public void init(AlignedHeapChunk.AlignedHeader chunk) {
            this.chunk = chunk;
            this.relocationPointer = AlignedHeapChunk.getObjectsStart(chunk);
        }
    }

    private class FixingVisitor implements ObjectVisitor {

        private AlignedHeapChunk.AlignedHeader chunk;

        @Override
        public boolean visitObject(Object obj) {
            InteriorObjRefWalker.walkObjectInline(obj, refFixingVisitor);
            return true;
        }

        public void setChunk(AlignedHeapChunk.AlignedHeader chunk) {
            this.chunk = chunk;
            refFixingVisitor.setChunk(chunk);
        }
    }

    private class RefFixingVisitor implements ObjectReferenceVisitor {

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
                return true;
            }

            Pointer relocationInfo = AlignedHeapChunk.getEnclosingChunkFromObjectPointer(p).getFirstRelocationInfo();
            if (relocationInfo.isNull() || relocationInfo.aboveThan(p)) {
                return true;
            }

            Pointer nextRelocationInfo = RelocationInfo.getNextRelocationInfo(relocationInfo);
            while (nextRelocationInfo.isNonNull() && nextRelocationInfo.belowThan(p)) {
                relocationInfo = nextRelocationInfo;
                nextRelocationInfo = RelocationInfo.getNextRelocationInfo(relocationInfo);
            }

            Pointer relocationPointer = RelocationInfo.readRelocationPointer(relocationInfo);
            Pointer newLocation = relocationPointer.add(p.subtract(relocationInfo));

            Object offsetObj = (innerOffset == 0) ? obj : newLocation.add(innerOffset).toObject();
            ReferenceAccess.singleton().writeObjectAt(objRef, offsetObj, compressed);

            if (p.and(15).isNull()) {
                Log.log().string("Updated location, old=").zhex(p)
                        .string(", new=").zhex(newLocation)
                        .string(", diff=").signed(newLocation.subtract(p))
                        .string(", p=").zhex(p)
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

        private Pointer relocationInfoPointer;
        private Pointer nextRelocationInfoPointer;

        private AlignedHeapChunk.AlignedHeader chunk;

        @Override
        public boolean visitObject(Object obj) {
            if (relocationInfoPointer.isNull()) {
                return true;
            }

            Pointer objPointer = Word.objectToUntrackedPointer(obj);
            if (objPointer.belowThan(relocationInfoPointer)) {
                return true;
            }

            if (nextRelocationInfoPointer.isNonNull() && objPointer.aboveOrEqual(nextRelocationInfoPointer)) {
                relocationInfoPointer = nextRelocationInfoPointer;
                int offset = RelocationInfo.readNextPlugOffset(relocationInfoPointer);
                if (offset > 0) {
                    nextRelocationInfoPointer = relocationInfoPointer.add(offset);
                } {
                    nextRelocationInfoPointer = WordFactory.nullPointer();
                }
                Log.log().string("Jumped relocation info, current=").zhex(relocationInfoPointer)
                        .string(", next=").zhex(nextRelocationInfoPointer)
                        .newline().flush();
            }

            Pointer newLocation = objPointer.subtract(relocationInfoPointer).add(RelocationInfo.readRelocationPointer(relocationInfoPointer));

            Log.log().string("New relocation")
                    .string(", newLocation=").zhex(newLocation)
                    .string(", objPointer=").zhex(objPointer)
                    .string(", relocationInfoPointer=").zhex(relocationInfoPointer)
                    .string(", relocationPointer=").zhex(RelocationInfo.readRelocationPointer(relocationInfoPointer))
                    .newline().flush();

            UnsignedWord copySize = copyObject(obj, newLocation);

            UnsignedWord newTop = objPointer.add(copySize);
            chunk.setTopOffset(newTop.subtract(HeapChunk.asPointer(chunk)));

            return true;
        }

        public void setChunk(AlignedHeapChunk.AlignedHeader chunk) {
            this.chunk = chunk;
            relocationInfoPointer = chunk.getFirstRelocationInfo();
            if (relocationInfoPointer.isNonNull()) {
                int offset = RelocationInfo.readNextPlugOffset(relocationInfoPointer);
                if (offset > 0) {
                    nextRelocationInfoPointer = relocationInfoPointer.add(offset);
                } {
                    nextRelocationInfoPointer = WordFactory.nullPointer();
                }
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
        Log.log().string("Copying object from ").zhex(originalMemory).character('-').zhex(originalMemory.add(originalSize))
                .string(" (").unsigned(originalSize).string(" B)")
                .string(" to ").zhex(dest).character('-').zhex(dest.add(copySize))
                .string(" (").unsigned(copySize).string(" B)")
                .newline().flush();
        UnmanagedMemoryUtil.copyLongsForward(originalMemory, dest, copySize);

        if (probability(SLOW_PATH_PROBABILITY, addIdentityHashField)) {
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
}
