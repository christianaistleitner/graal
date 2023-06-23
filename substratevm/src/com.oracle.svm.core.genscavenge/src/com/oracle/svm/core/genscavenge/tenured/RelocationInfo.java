/*
 * Copyright (c) 2023, 2023, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.genscavenge.tenured;

import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.genscavenge.AlignedHeapChunk;
import com.oracle.svm.core.genscavenge.HeapChunk;
import com.oracle.svm.core.genscavenge.ObjectHeaderImpl;
import com.oracle.svm.core.heap.ObjectVisitor;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.util.VMError;

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
 *                                                                   ^pointer
 * 32-bit mode:
 * +-----------------------------------------------------------------+
 * | TODO                                                            |
 * +-----------------------------------------------------------------+
 *                                                                   ^pointer
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
        Pointer relocationInfo = AlignedHeapChunk.getObjectsStart(chunkHeader);

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

            if (!visitor.visitObjectInline(obj)) {
                return;
            }

            cursor = cursor.add(objSize);
        }
    }

    public static Object getRelocatedObject(Pointer p) {
        p = getRelocatedObjectPointer(p);
        return p.isNull() ? null : p.toObject();
    }

    public static Pointer getRelocatedObjectPointer(Pointer p) {
        assert ObjectHeaderImpl.isAlignedObject(p.toObject()) : "Unaligned objects are not supported!";

        AlignedHeapChunk.AlignedHeader chunk = AlignedHeapChunk.getEnclosingChunkFromObjectPointer(p);

        Pointer topPointer = HeapChunk.getTopPointer(chunk);
        if (p.aboveOrEqual(topPointer)) {
            throw VMError.shouldNotReachHere("Object is above top pointer.");
        }

        Pointer relocationInfo = AlignedHeapChunk.getObjectsStart(chunk);
        Pointer nextRelocationInfo = RelocationInfo.getNextRelocationInfo(relocationInfo);
        while (nextRelocationInfo.isNonNull() && nextRelocationInfo.belowOrEqual(p)) {
            relocationInfo = nextRelocationInfo;
            nextRelocationInfo = RelocationInfo.getNextRelocationInfo(relocationInfo);
        }

        if (nextRelocationInfo.isNonNull() && nextRelocationInfo.subtract(RelocationInfo.readGapSize(nextRelocationInfo)).belowOrEqual(p)) {
            return WordFactory.nullPointer(); // object didn't survive
        }

        assert relocationInfo.belowOrEqual(p);

        Pointer relocationPointer = RelocationInfo.readRelocationPointer(relocationInfo);
        Pointer relocationOffset = p.subtract(relocationInfo);

        return relocationPointer.add(relocationOffset);
    }

    public static int getSize() {
        return 16; // TODO
    }
}
