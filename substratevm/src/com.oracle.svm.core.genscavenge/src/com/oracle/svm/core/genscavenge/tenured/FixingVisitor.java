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

import com.oracle.svm.core.heap.ObjectVisitor;
import com.oracle.svm.core.hub.InteriorObjRefWalker;

public class FixingVisitor implements ObjectVisitor {

    private final RefFixingVisitor refFixingVisitor;

    public FixingVisitor(RefFixingVisitor refFixingVisitor) {
        this.refFixingVisitor = refFixingVisitor;
    }

    @Override
    public boolean visitObject(Object obj) {

        // fixes Target_java_lang_ref_Reference.referent
            /*
            DynamicHub hub = KnownIntrinsics.readHub(obj);
            if (probability(SLOW_PATH_PROBABILITY, hub.isReferenceInstanceClass())) {
                Reference<?> dr = (Reference<?>) obj;

                Pointer objRef = ReferenceInternals.getReferentFieldAddress(dr);

                Pointer p = ReferenceAccess.singleton().readObjectAsUntrackedPointer(objRef, true);

                if (p.isNonNull() && !Heap.getHeap().isInImageHeap(p)) {
                    Log.log().string("A").newline().flush();
                    if (ObjectHeaderImpl.hasMarkedBit(p.toObject())) {
                        Log.log().string("B").newline().flush();
                        refFixingVisitor.visitObjectReference(objRef, true, dr);
                    } else {
                        Log.log().string("C").newline().flush();
                        ReferenceInternals.setReferent(dr, null); // dead
                        Reference<?> next = (ReferenceObjectProcessing.rememberedRefsList != null) ? ReferenceObjectProcessing.rememberedRefsList : dr;
                        ReferenceInternals.setNextDiscovered(dr, next);
                        ReferenceObjectProcessing.rememberedRefsList = dr;
                    }
                }
            }
            */

        InteriorObjRefWalker.walkObjectInline(obj, refFixingVisitor);
        return true;
    }
}