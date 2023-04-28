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

import org.graalvm.word.Pointer;

import com.oracle.svm.core.genscavenge.HeapImpl;
import com.oracle.svm.core.genscavenge.ObjectHeaderImpl;
import com.oracle.svm.core.genscavenge.remset.RememberedSet;
import com.oracle.svm.core.heap.ObjectReferenceVisitor;
import com.oracle.svm.core.heap.ReferenceAccess;
import com.oracle.svm.core.log.Log;

public class RefFixingVisitor implements ObjectReferenceVisitor {

    public boolean debug = false;

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

        Pointer newLocation = RelocationInfo.getRelocatedObjectPointer(p);
        if (newLocation.isNull()) {
            Log.log().string("ERROR - holder=").object(holderObject).newline().flush();
            // assert false;
        }

        Object offsetObj = (innerOffset == 0) ? newLocation.toObject() : newLocation.add(innerOffset).toObject();
        ReferenceAccess.singleton().writeObjectAt(objRef, offsetObj, compressed);

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
                    .newline().flush();
        }

        return true;
    }
}

