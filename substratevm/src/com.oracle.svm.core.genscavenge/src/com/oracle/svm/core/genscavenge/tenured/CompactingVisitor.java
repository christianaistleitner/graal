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

import org.graalvm.compiler.word.Word;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.UnmanagedMemoryUtil;
import com.oracle.svm.core.genscavenge.AlignedHeapChunk;
import com.oracle.svm.core.genscavenge.HeapChunk;
import com.oracle.svm.core.genscavenge.ObjectHeaderImpl;
import com.oracle.svm.core.heap.ObjectVisitor;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.thread.VMOperation;

public class CompactingVisitor implements ObjectVisitor {

    private Pointer relocationPointer;
    private Pointer relocationInfoPointer;
    private Pointer nextRelocationInfoPointer;

    private AlignedHeapChunk.AlignedHeader chunk;

    @Override
    public boolean visitObject(Object obj) {
        if (relocationInfoPointer.isNull()) {
            return false; // no gaps
        }

        Pointer objPointer = Word.objectToUntrackedPointer(obj);

        if (nextRelocationInfoPointer.isNonNull() && objPointer.aboveOrEqual(nextRelocationInfoPointer)) {
            relocationInfoPointer = nextRelocationInfoPointer;
            nextRelocationInfoPointer = RelocationInfo.getNextRelocationInfo(relocationInfoPointer);
            relocationPointer = RelocationInfo.readRelocationPointer(relocationInfoPointer);
        }

        Pointer newLocation = objPointer.subtract(relocationInfoPointer).add(relocationPointer);

        Log.noopLog().string("New relocation")
                .string(", newLocation=").zhex(newLocation)
                .string(", objPointer=").zhex(objPointer)
                .string(", relocationInfoPointer=").zhex(relocationInfoPointer)
                .string(", relocationPointer=").zhex(relocationPointer)
                .newline().flush();

        UnsignedWord copySize = copyObject(obj, newLocation);

        // TODO: Find a more elegant way to set the top pointer during/after compaction.
        AlignedHeapChunk.AlignedHeader newChunk = AlignedHeapChunk.getEnclosingChunkFromObjectPointer(newLocation);
        HeapChunk.setTopPointer(
                newChunk,
                newLocation.add(copySize)
        );
        if (chunk.notEqual(newChunk)) {
            HeapChunk.setTopPointer(
                    chunk,
                    AlignedHeapChunk.getObjectsStart(chunk)
            );
        }

        return true;
    }

    public void init(AlignedHeapChunk.AlignedHeader chunk) {
        this.chunk = chunk;
        relocationInfoPointer = AlignedHeapChunk.getObjectsStart(chunk);
        relocationPointer = RelocationInfo.readRelocationPointer(relocationInfoPointer);

        int offset = RelocationInfo.readNextPlugOffset(relocationInfoPointer);
        if (offset > 0) {
            nextRelocationInfoPointer = relocationInfoPointer.add(offset);
        } else {
            nextRelocationInfoPointer = WordFactory.nullPointer();
        }
    }

    /**
     * @return the number of copied bytes
     */
    private UnsignedWord copyObject(Object obj, Pointer dest) {
        assert VMOperation.isGCInProgress();
        assert ObjectHeaderImpl.isAlignedObject(obj);
        assert dest.isNonNull();

        UnsignedWord objSize = LayoutEncoding.getSizeFromObjectInlineInGC(obj);
        Pointer src = Word.objectToUntrackedPointer(obj);

        UnmanagedMemoryUtil.copyLongsForward(src, dest, objSize);

        return objSize;
    }
}
