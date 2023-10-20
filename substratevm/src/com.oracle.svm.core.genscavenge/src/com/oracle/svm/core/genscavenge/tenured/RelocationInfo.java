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
import com.oracle.svm.core.genscavenge.remset.BrickTable;
import com.oracle.svm.core.heap.ObjectVisitor;
import com.oracle.svm.core.heap.ReferenceAccess;
import com.oracle.svm.core.heap.RestrictHeapAccess;
import com.oracle.svm.core.hub.LayoutEncoding;

import com.oracle.svm.core.log.Log;
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
 * | relocation pointer (4B) | gap size (2B) | next plug offset (2B) |
 * +-----------------------------------------------------------------+
 *                                                                   ^pointer
 * </pre>
 */
public class RelocationInfo {

    public static void writeRelocationPointer(Pointer p, Pointer relocationPointer) {
        if (ReferenceAccess.singleton().haveCompressedReferences()) {
            long offset = relocationPointer.subtract(p).rawValue();
            assert offset == (int) offset : "must fit in 4 bytes";
            p.writeInt(-8, (int) offset);
        } else {
            p.writeWord(-16, relocationPointer);
        }
        assert readRelocationPointer(p).equal(relocationPointer) : "persistence check";
    }

    public static Pointer readRelocationPointer(Pointer p) {
        if (ReferenceAccess.singleton().haveCompressedReferences()) {
            int offset = p.readInt(-8);
            return p.add(offset);
        } else {
            return p.readWord(-16);
        }
    }

    public static void writeGapSize(Pointer p, int gapSize) {
        if (ReferenceAccess.singleton().haveCompressedReferences()) {
            int data = gapSize / ConfigurationValues.getObjectLayout().getAlignment();
            assert data == (((short) data) & 0xffff) : "must fit in 2 bytes";
            p.writeShort(-4, (short) data);
        } else {
            p.writeInt(-8, gapSize);
        }
        assert readGapSize(p) == gapSize : "persistence check";
    }

    public static int readGapSize(Pointer p) {
        if (ReferenceAccess.singleton().haveCompressedReferences()) {
            return (p.readShort(-4) & 0xffff) * ConfigurationValues.getObjectLayout().getAlignment();
        } else {
            return p.readInt(-8);
        }
    }

    public static void writeNextPlugOffset(Pointer p, int offset) {
        if (ReferenceAccess.singleton().haveCompressedReferences()) {
            int data = offset / ConfigurationValues.getObjectLayout().getAlignment();
            assert data == (((short) data) & 0xffff) : "must fit in 2 bytes";
            p.writeShort(-2, (short) data);
        } else {
            p.writeInt(-4, offset);
        }
        assert readNextPlugOffset(p) == offset : "persistence check";
    }

    public static int readNextPlugOffset(Pointer p) {
        if (ReferenceAccess.singleton().haveCompressedReferences()) {
            return (p.readShort(-2) & 0xffff) * ConfigurationValues.getObjectLayout().getAlignment();
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

        Log.noopLog().string("Fixing chunk ").zhex(chunkHeader).string(" at top ").zhex(top).newline().flush();

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
            // Object is located in gap at chunk end.
            return WordFactory.nullPointer(); // object didn't survive
        }

        Pointer relocationInfo = BrickTable.getEntry(chunk, BrickTable.getIndex(chunk, p));
        if (relocationInfo.aboveThan(p)) {
            // Object didn't survive and was located in a gap across a brick table entry border.
            return WordFactory.nullPointer();
        }

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

    public static Pointer getRelocatedObjectPointerBackup(Pointer p) {
        assert ObjectHeaderImpl.isAlignedObject(p.toObject()) : "Unaligned objects are not supported!";

        AlignedHeapChunk.AlignedHeader chunk = AlignedHeapChunk.getEnclosingChunkFromObjectPointer(p);

        Pointer topPointer = HeapChunk.getTopPointer(chunk);
        if (p.aboveOrEqual(topPointer)) {
            // Object is located in gap at chunk end.
            Log.log().string("Object above top pointer")
                    .string(", chunk=").zhex(chunk)
                    .string(", top=").zhex(topPointer)
                    .string(", p=").zhex(p)
                    .newline().flush();
            return WordFactory.nullPointer(); // object didn't survive
        }

        Pointer relocationInfo = AlignedHeapChunk.getObjectsStart(chunk);

        Pointer nextRelocationInfo = RelocationInfo.getNextRelocationInfo(relocationInfo);
        while (nextRelocationInfo.isNonNull() && nextRelocationInfo.belowOrEqual(p)) {
            relocationInfo = nextRelocationInfo;
            nextRelocationInfo = RelocationInfo.getNextRelocationInfo(relocationInfo);
        }

        if (nextRelocationInfo.isNonNull() && nextRelocationInfo.subtract(RelocationInfo.readGapSize(nextRelocationInfo)).belowOrEqual(p)) {
            Log.log().string("Object is located in gap.").newline().flush();
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

    public static void visit(AlignedHeapChunk.AlignedHeader chunk, Visitor visitor) {
        Pointer cursor = AlignedHeapChunk.getObjectsStart(chunk);
        while (cursor.isNonNull()) {
            // Visitor might write new data at cursor location.
            Pointer next = RelocationInfo.getNextRelocationInfo(cursor);
            boolean success = visitor.visitInline(cursor);
            if (!success) {
                return;
            }
            cursor = next;
        }
    }

    /**
     * Supply a closure to be applied to {@link Pointer}s to relocation info locations.
     */
    public interface Visitor {

        /**
         * Visit a {@link Pointer} to relocation info.
         *
         * @param p The {@link Pointer} to be visited.
         * @return {@code true} if visiting should continue, {@code false} if visiting should stop.
         */
        @RestrictHeapAccess(
                access = RestrictHeapAccess.Access.NO_ALLOCATION,
                reason = "Must not allocate while visiting the heap."
        )
        boolean visit(Pointer p);

        /**
         * Visit a {@link Pointer} to relocation info like {@link #visit}, but inlined for performance.
         *
         * @param p The {@link Pointer} to be visited.
         * @return {@code true} if visiting should continue, {@code false} if visiting should stop.
         */
        @RestrictHeapAccess(
                access = RestrictHeapAccess.Access.NO_ALLOCATION,
                reason = "Must not allocate while visiting the heap."
        )
        boolean visitInline(Pointer p);
    }
}
