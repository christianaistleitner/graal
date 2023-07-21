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

import jdk.graal.compiler.word.Word;
import org.graalvm.word.Pointer;

import com.oracle.svm.core.AlwaysInline;
import com.oracle.svm.core.NeverInline;
import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.heap.Heap;
import com.oracle.svm.core.heap.ObjectHeader;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.hub.LayoutEncoding;

public class SweepingVisitor implements RelocationInfo.Visitor {

    @NeverInline("")
    @Override
    public boolean visit(Pointer p) {
        return visitInline(p);
    }

    @AlwaysInline("")
    @Override
    public boolean visitInline(Pointer p) {
        DynamicHub hub = SubstrateUtil.cast(byte[].class, DynamicHub.class);
        assert LayoutEncoding.isArray(hub.getLayoutEncoding());

        int size = RelocationInfo.readGapSize(p);
        if (size == 0) {
            RelocationInfo.writeNextPlugOffset(p, 0);
            RelocationInfo.writeRelocationPointer(p, p);
            return true;
        }

        Pointer gap = p.subtract(size);

        ObjectHeader header = Heap.getHeap().getObjectHeader();
        Word encodedHeader = header.encodeAsUnmanagedObjectHeader(hub);
        header.initializeHeaderOfNewObject(gap, encodedHeader, true);

        int length = size - ConfigurationValues.getObjectLayout().getMinImageHeapArraySize();
        gap.writeInt(ConfigurationValues.getObjectLayout().getArrayLengthOffset(), length);
        assert LayoutEncoding.getSizeFromObjectInGC(gap.toObject()).equal(size);

        return true;
    }
}
