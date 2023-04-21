package com.oracle.svm.core.genscavenge.tenured;

import org.graalvm.compiler.word.Word;
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
import com.oracle.svm.core.hub.LayoutEncoding;

public class PlanningVisitor implements AlignedHeapChunk.Visitor {

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
        Pointer cursor = AlignedHeapChunk.getObjectsStart(chunk);
        Pointer top = HeapChunk.getTopPointer(chunk); // top can't move here, therefore it's fine to read once

        Pointer relocationInfoPointer = WordFactory.nullPointer();
        Pointer relocationPointer = AlignedHeapChunk.getObjectsStart(chunk);
        UnsignedWord gapSize =  WordFactory.zero();

        while (cursor.belowThan(top)) {
            Word header = ObjectHeaderImpl.readHeaderFromPointer(cursor);
            Object obj = cursor.toObject();

            /*
             * Adding the optional identity hash field will increase the object's size,
             * but in here, when compacting the tenured space, we expect that there aren't any marked objects
             * which have their "IdentityHashFromAddress" object header flag set.
             */
            assert !ObjectHeaderImpl.hasIdentityHashFromAddressInline(header);
            UnsignedWord objSize = LayoutEncoding.getSizeFromObjectInlineInGC(obj);

            if (ObjectHeaderImpl.hasMarkedBit(obj)) {
                ObjectHeaderImpl.clearMarkedBit(obj);

                if (gapSize.notEqual(0)) {

                    /*
                     * Update previous relocation info or set the chunk's "FirstRelocationInfo" pointer.
                     */
                    if (relocationInfoPointer.isNull()) {
                        chunk.setFirstRelocationInfo(cursor);
                    } else {
                        int offset = (int) cursor.subtract(relocationInfoPointer).rawValue();
                        RelocationInfo.writeNextPlugOffset(relocationInfoPointer, offset);
                    }

                    /*
                     * Write the current relocation info at the gap end.
                     */
                    relocationInfoPointer = cursor;
                    RelocationInfo.writeRelocationPointer(relocationInfoPointer, relocationPointer);
                    RelocationInfo.writeGapSize(relocationInfoPointer, (int) gapSize.rawValue());
                    RelocationInfo.writeNextPlugOffset(relocationInfoPointer, 0);

                    gapSize = WordFactory.zero();
                }

                relocationPointer = relocationPointer.add(objSize);
            } else {
                gapSize = gapSize.add(objSize);
            }

            cursor = cursor.add(objSize);
        }

        /*
         * Check for a gap at chunk end that requires updating the chunk top offset to clear that memory.
         */
        if (gapSize.notEqual(0)) {
            chunk.setTopOffset(chunk.getTopOffset().subtract(gapSize));
        }

        return true;
    }
}