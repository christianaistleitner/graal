package com.oracle.svm.core.genscavenge.tenured;

import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.genscavenge.AlignedHeapChunk;
import com.oracle.svm.core.genscavenge.HeapChunk;
import com.oracle.svm.core.genscavenge.ObjectHeaderImpl;
import com.oracle.svm.core.heap.ObjectVisitor;
import com.oracle.svm.core.hub.LayoutEncoding;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

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
public class RelocationInfo {

    public static void writeRelocationPointer(Pointer p, Pointer relocationPointer) {
        if (ConfigurationValues.getObjectLayout().getReferenceSize() == Integer.BYTES) {
            // TODO
            assert false;
        } else {
            p.writeWord(-16, relocationPointer);
        }
    }

    public static Pointer readRelocationPointer(Pointer p) {
        if (ConfigurationValues.getObjectLayout().getReferenceSize() == Integer.BYTES) {
            // TODO
            assert false;
            return WordFactory.nullPointer();
        } else {
            return p.readWord(-16);
        }
    }

    public static void writeGapSize(Pointer p, int gapSize) {
        if (ConfigurationValues.getObjectLayout().getReferenceSize() == Integer.BYTES) {
            // TODO
            assert false;
        } else {
            p.writeInt(-8, gapSize);
        }
    }

    public static int readGapSize(Pointer p) {
        if (ConfigurationValues.getObjectLayout().getReferenceSize() == Integer.BYTES) {
            // TODO
            assert false;
            return 0;
        } else {
            return p.readInt(-8);
        }
    }

    public static void writeNextPlugOffset(Pointer p, int offset) {
        if (ConfigurationValues.getObjectLayout().getReferenceSize() == Integer.BYTES) {
            // TODO
            assert false;
        } else {
            p.writeInt(-4, offset);
        }
    }

    public static int readNextPlugOffset(Pointer p) {
        if (ConfigurationValues.getObjectLayout().getReferenceSize() == Integer.BYTES) {
            // TODO
            assert false;
            return 0;
        } else {
            return p.readInt(-4);
        }
    }

    public static Pointer getNextRelocationInfo(Pointer p) {
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
    public static void walkObjects(AlignedHeapChunk.AlignedHeader chunkHeader, ObjectVisitor visitor) {
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

    public static Object getRelocatedObject(Pointer p) {
        return getRelocatedObjectPointer(p).toObject();
    }

    public static Pointer getRelocatedObjectPointer(Pointer p) {
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