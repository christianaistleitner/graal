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
package com.oracle.svm.core.genscavenge.remset;

import jdk.graal.compiler.api.replacements.Fold;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.genscavenge.AlignedHeapChunk;
import com.oracle.svm.core.genscavenge.HeapChunk;
import com.oracle.svm.core.genscavenge.HeapParameters;
import com.oracle.svm.core.util.UnsignedUtils;

/**
 * Inspired by the .NET CoreCLR, the {@link BrickTable} speeds up relocation pointer lookups
 * by acting as a lookup table for {@link com.oracle.svm.core.genscavenge.tenured.RelocationInfo}.
 * Each entry stores a pointer to the start of the first plug of the chunk fraction it covers.
 * <br/>
 * Note that we borrow the memory of a chunk's {@link CardTable} to store that table.
 */
public class BrickTable {

    /**
     * We reuse the {@link CardTable}'s memory and double the covered bytes as we need 2 bytes per entry.
     */
    public static final int BYTES_COVERED_BY_ENTRY = CardTable.BYTES_COVERED_BY_ENTRY * 2;

    /**
     * @return The table index whose entry covers the given object pointer.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static UnsignedWord getIndex(AlignedHeapChunk.AlignedHeader chunk, Pointer pointer) {
        Pointer objectsStart = AlignedHeapChunk.getObjectsStart(chunk);
        UnsignedWord index = pointer.subtract(objectsStart).unsignedDivide(BYTES_COVERED_BY_ENTRY);
        assert index.aboveOrEqual(0) && index.belowThan(getLength()) : "Index out of range";
        return index;
    }

    @Fold
    public static UnsignedWord getLength() {
        UnsignedWord memoryCovered = UnsignedUtils.roundUp(
                HeapParameters.getAlignedHeapChunkSize().subtract(AlignedHeapChunk.getObjectsStartOffset()),
                WordFactory.unsigned(BYTES_COVERED_BY_ENTRY)
        );
        return memoryCovered.unsignedDivide(BYTES_COVERED_BY_ENTRY);
    }

    /**
     * @return A pointer to the nearest {@link com.oracle.svm.core.genscavenge.tenured.RelocationInfo}.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static Pointer getEntry(AlignedHeapChunk.AlignedHeader chunk, UnsignedWord index) {
        Pointer chunkStart = HeapChunk.asPointer(chunk);
        Pointer tableStart = chunkStart.add(AlignedChunkRememberedSet.getFirstObjectTableStartOffset());

        short entry = tableStart.readShort(index.shiftLeft(1));

        assert ConfigurationValues.getObjectLayout().getAlignment() == 8;
        UnsignedWord offset = WordFactory.unsigned(entry & 0xffffL).shiftLeft(3);

        return chunkStart.add(offset);
    }

    /**
     * @param pointer The pointer to the nearest {@link com.oracle.svm.core.genscavenge.tenured.RelocationInfo}.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static void setEntry(AlignedHeapChunk.AlignedHeader chunk, UnsignedWord index, Pointer pointer) {
        Pointer chunkStart = HeapChunk.asPointer(chunk);
        Pointer tableStart = chunkStart.add(AlignedChunkRememberedSet.getFirstObjectTableStartOffset());
        UnsignedWord offset = pointer.subtract(chunkStart);

        assert ConfigurationValues.getObjectLayout().getAlignment() == 8;
        short entry = (short) offset.unsignedShiftRight(3).rawValue();

        tableStart.writeShort(index.shiftLeft(1), entry);

        assert getEntry(chunk, index).equal(pointer) : "Serialization failure";
    }
}
