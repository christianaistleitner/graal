package com.oracle.svm.core.genscavenge.tenured;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.AlwaysInline;
import com.oracle.svm.core.NeverInline;
import com.oracle.svm.core.genscavenge.AlignedHeapChunk;
import com.oracle.svm.core.genscavenge.HeapChunk;
import com.oracle.svm.core.genscavenge.ObjectHeaderImpl;
import com.oracle.svm.core.genscavenge.Space;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.log.Log;

public class PlanningVisitor implements AlignedHeapChunk.Visitor {

    private AlignedHeapChunk.AlignedHeader chunk;

    private Pointer allocationPointer;

    @Platforms(Platform.HOSTED_ONLY.class)
    public PlanningVisitor() {
    }

    @Override
    @NeverInline("Non-performance critical version")
    public boolean visitChunk(AlignedHeapChunk.AlignedHeader chunk) {
        return visitChunkInline(chunk);
    }

    @Override
    @AlwaysInline("GC performance")
    public boolean visitChunkInline(AlignedHeapChunk.AlignedHeader chunk) {
        Log.noopLog().string("[PlanningVisitor.visitChunkInline: chunk=").zhex(chunk).string("]\n").flush();

        Pointer cursor = AlignedHeapChunk.getObjectsStart(chunk);
        Pointer top = HeapChunk.getTopPointer(chunk); // top can't move here, therefore it's fine to read once

        Pointer relocationInfoPointer = AlignedHeapChunk.getObjectsStart(chunk);
        UnsignedWord gapSize =  WordFactory.zero();
        UnsignedWord plugSize =  WordFactory.zero();

        /*
         * Write the first relocation info just before objects start.
         */
        RelocationInfo.writeRelocationPointer(relocationInfoPointer, allocationPointer);
        RelocationInfo.writeGapSize(relocationInfoPointer, 0);
        RelocationInfo.writeNextPlugOffset(relocationInfoPointer, 0);

        Log.log().string("Wrote first relocation info at ").zhex(relocationInfoPointer)
                .string(": relocationPointer=").zhex(allocationPointer)
                .string(": gapSize=").unsigned(0)
                .string(": nextPlugOffset=").zhex(0)
                .newline().flush();


        while (cursor.belowThan(top)) {
            Object obj = cursor.toObject();

            /*
             * Adding the optional identity hash field will increase the object's size,
             * but in here, when compacting the tenured space, we expect that there aren't any marked objects
             * which have their "IdentityHashFromAddress" object header flag set.
             *
             * TODO: Add assertion.
             */
            UnsignedWord objSize = LayoutEncoding.getSizeFromObjectInlineInGC(obj);

            if (ObjectHeaderImpl.hasMarkedBit(obj)) {
                ObjectHeaderImpl.clearMarkedBit(obj);

                if (gapSize.notEqual(0)) {
                    Log.log().string("Gap from ").zhex(cursor.subtract(gapSize))
                            .string(" to ").zhex(cursor)
                            .string(" (").unsigned(gapSize).string(" bytes)")
                            .newline().flush();

                    /*
                     * Update previous relocation info or set the chunk's "FirstRelocationInfo" pointer.
                     */
                    int offset = (int) cursor.subtract(relocationInfoPointer).rawValue();
                    RelocationInfo.writeNextPlugOffset(relocationInfoPointer, offset);

                    Log.log().string("Updated relocation info at ").zhex(relocationInfoPointer)
                            .string(": nextPlugOffset=").zhex(offset)
                            .newline().flush();

                    /*
                     * Write the current relocation info at the gap end.
                     */
                    relocationInfoPointer = cursor;
                    RelocationInfo.writeGapSize(relocationInfoPointer, (int) gapSize.rawValue());
                    RelocationInfo.writeNextPlugOffset(relocationInfoPointer, 0);

                    Log.log().string("Wrote relocation info at ").zhex(relocationInfoPointer)
                            .string(": gapSize=").unsigned(gapSize)
                            .string(": nextPlugOffset=").zhex(0)
                            .newline().flush();

                    gapSize = WordFactory.zero();
                }

                plugSize = plugSize.add(objSize);
            } else {
                if (plugSize.notEqual(0)) {
                    /*
                     * Update previous relocation info to set its relocation pointer.
                     */
                    Pointer relocationPointer = getRelocationPointer(plugSize);
                    RelocationInfo.writeRelocationPointer(relocationInfoPointer, relocationPointer);

                    Log.log().string("Updated relocation info at ").zhex(relocationInfoPointer)
                            .string(": relocationPointer=").zhex(relocationPointer)
                            .newline().flush();

                    Log.log().string("Plug from ").zhex(relocationInfoPointer)
                            .string(" to ").zhex(relocationInfoPointer.add(plugSize))
                            .string(" (").unsigned(plugSize).string(" bytes)")
                            .string(" will be moved to ").zhex(relocationPointer)
                            .character('-').zhex(relocationPointer.add(plugSize))
                            .newline().flush();

                    plugSize = WordFactory.zero();
                }

                gapSize = gapSize.add(objSize);
            }

            cursor = cursor.add(objSize);
        }

        /*
         * Sanity check
         */
        assert gapSize.equal(0) || plugSize.equal(0);

        /*
         * Check for a gap at chunk end that requires updating the chunk top offset to clear that memory.
         */
        if (gapSize.notEqual(0)) {
            Pointer topPointer = HeapChunk.getTopPointer(chunk);
            Pointer gapStart = topPointer.subtract(gapSize);
            Log.log().string("Gap at chunk end from ").zhex(gapStart)
                    .string(" to ").zhex(topPointer)
                    .string(" (").unsigned(gapSize).string(" bytes)")
                    .newline().flush();

            chunk.setTopOffset(chunk.getTopOffset().subtract(gapSize));
        }

        if (plugSize.notEqual(0)) {
            Pointer relocationPointer = getRelocationPointer(plugSize);
            RelocationInfo.writeRelocationPointer(relocationInfoPointer, relocationPointer);

            Pointer topPointer = HeapChunk.getTopPointer(chunk);
            Pointer plugStart = topPointer.subtract(plugSize);
            Log.log().string("Plug at chunk end from ").zhex(plugStart)
                    .string(" to ").zhex(topPointer)
                    .string(" (").unsigned(plugSize).string(" bytes)")
                    .newline().flush();

            Log.log().string("Plug from ").zhex(relocationInfoPointer)
                    .string(" to ").zhex(relocationInfoPointer.add(plugSize))
                    .string(" (").unsigned(plugSize).string(" bytes)")
                    .string(" will be moved to ").zhex(relocationPointer)
                    .character('-').zhex(relocationPointer.add(plugSize))
                    .newline().flush();
        }

        return true;
    }

    private Pointer getRelocationPointer(UnsignedWord size) {
        Pointer relocationPointer = allocationPointer;
        allocationPointer = allocationPointer.add(size);
        if (AlignedHeapChunk.getObjectsEnd(chunk).belowThan(allocationPointer)) {
            chunk = HeapChunk.getNext(chunk);
            relocationPointer = AlignedHeapChunk.getObjectsStart(chunk);
            allocationPointer = relocationPointer.add(size);
        }
        return relocationPointer;
    }

    public void init(Space space) {
        chunk = space.getFirstAlignedHeapChunk();
        allocationPointer = AlignedHeapChunk.getObjectsStart(chunk);
    }
}