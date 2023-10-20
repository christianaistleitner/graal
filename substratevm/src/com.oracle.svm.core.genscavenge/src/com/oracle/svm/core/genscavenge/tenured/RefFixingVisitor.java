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

import com.oracle.svm.core.genscavenge.AlignedHeapChunk;
import com.oracle.svm.core.genscavenge.HeapChunk;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.log.Log;
import org.graalvm.compiler.word.Word;
import com.oracle.svm.core.genscavenge.AlignedHeapChunk;
import com.oracle.svm.core.genscavenge.HeapChunk;
import com.oracle.svm.core.genscavenge.HeapImpl;
import com.oracle.svm.core.genscavenge.ObjectHeaderImpl;
import com.oracle.svm.core.genscavenge.remset.RememberedSet;
import com.oracle.svm.core.heap.ObjectReferenceVisitor;
import com.oracle.svm.core.heap.ReferenceAccess;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.log.Log;
import jdk.compiler.graal.word.Word;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;

import java.lang.ref.Reference;

public class RefFixingVisitor implements ObjectReferenceVisitor {

    public boolean debug = false;

    @Override
    public boolean visitObjectReference(Pointer objRef, boolean compressed, Object holderObject) {
        return visitObjectReferenceInline(objRef, 0, compressed, holderObject);
    }

    /**
     * @param objRef Pointer to Pointer
     */
    @Override
    public boolean visitObjectReferenceInline(Pointer objRef, int innerOffset, boolean compressed, Object holderObject) {
        assert innerOffset == 0; // Will always be 0.

        Pointer p = ReferenceAccess.singleton().readObjectAsUntrackedPointer(objRef, compressed);
        if (p.isNull()) {
            return true;
        }

        if (HeapImpl.getHeapImpl().isInImageHeap(p)) {
            return true;
        }

        Object obj = p.toObject();
        if (ObjectHeaderImpl.isUnalignedObject(obj)) {
            if (HeapImpl.getHeapImpl().isInImageHeap(holderObject)){
                RememberedSet.get().dirtyCardIfNecessary(holderObject, obj);
            }
            return true;
        }

        Pointer newLocation = RelocationInfo.getRelocatedObjectPointer(p);
        if (!(newLocation.isNonNull() || holderObject == null || holderObject instanceof Reference<?>)) {

            AlignedHeapChunk.AlignedHeader c = AlignedHeapChunk.getEnclosingChunkFromObjectPointer(p);
            Pointer i = HeapChunk.getTopPointer(c);
            while (i.belowOrEqual(p)) {
                Object o = i.toObject();
                UnsignedWord s = LayoutEncoding.getSizeFromObjectInlineInGC(o);
                Log.log().string("Re-check, Obj ").object(o).string(" of size ")
                        .unsigned(s).string(" is marked=").bool(ObjectHeaderImpl.hasMarkedBit(o))
                        .newline().flush();
                i = i.add(s);
            }

            Pointer relocatedObjectPointerBackup = RelocationInfo.getRelocatedObjectPointerBackup(p);
            Word header = ObjectHeaderImpl.readHeaderFromObject(obj);
            Log.log().string("Didn't find new location of object")
                    .string(", newLocation=").zhex(newLocation)
                    .string(", holderObject=").object(holderObject)
                    .string(", obj=").object(obj)
                    .string(", marked=").bool(ObjectHeaderImpl.hasMarkedBit(obj))
                    .string(", forwarded=").bool(ObjectHeaderImpl.isForwardedHeader(header))
                    .string(", identityHashFromAddress=").bool(ObjectHeaderImpl.hasIdentityHashFromAddressInline(header))
                    .string(", backup=").zhex(relocatedObjectPointerBackup)
                    .newline().flush();
        }
        assert newLocation.isNonNull() || holderObject == null || holderObject instanceof Reference<?>;

        Object relocatedObj = newLocation.toObject();
        ReferenceAccess.singleton().writeObjectAt(objRef, relocatedObj, compressed);

        if (HeapImpl.getHeapImpl().isInImageHeap(holderObject)){
            RememberedSet.get().dirtyCardIfNecessary(holderObject, relocatedObj);
        }

        return true;
    }
}
