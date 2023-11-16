/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.genscavenge;

import com.oracle.svm.core.code.CodeInfo;
import com.oracle.svm.core.code.CodeInfoAccess;
import com.oracle.svm.core.code.RuntimeCodeCache.CodeInfoVisitor;
import com.oracle.svm.core.code.RuntimeCodeInfoAccess;
import com.oracle.svm.core.code.UntetheredCodeInfoAccess;
import com.oracle.svm.core.genscavenge.tenured.RelocationInfo;
import com.oracle.svm.core.heap.ObjectReferenceVisitor;
import com.oracle.svm.core.util.DuplicatedInNativeCode;
import jdk.graal.compiler.word.Word;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

final class RuntimeCodeCacheWalker2 implements CodeInfoVisitor {

    private final ObjectReferenceVisitor greyToBlackObjectVisitor;

    @Platforms(Platform.HOSTED_ONLY.class)
    RuntimeCodeCacheWalker2(ObjectReferenceVisitor greyToBlackObjectVisitor) {
        this.greyToBlackObjectVisitor = greyToBlackObjectVisitor;
    }

    @Override
    @DuplicatedInNativeCode
    public boolean visitCode(CodeInfo codeInfo) {
        if (RuntimeCodeInfoAccess.areAllObjectsOnImageHeap(codeInfo)) {
            return true;
        }

        Object tether = UntetheredCodeInfoAccess.getTetherUnsafe(codeInfo);
        // if (HeapImpl.getHeapImpl().isInImageHeap(tether)) {
        //     return true;
        // }

        if (tether != null && !isReachable(tether)) {
            int state = CodeInfoAccess.getState(codeInfo);
            if (state == CodeInfo.STATE_UNREACHABLE || state == CodeInfo.STATE_READY_FOR_INVALIDATION) {
                RuntimeCodeInfoAccess.walkObjectFields(codeInfo, greyToBlackObjectVisitor);
                return true;
            }
        }

        RuntimeCodeInfoAccess.walkStrongReferences(codeInfo, greyToBlackObjectVisitor);
        RuntimeCodeInfoAccess.walkWeakReferences(codeInfo, greyToBlackObjectVisitor);

        return true;
    }

    public static boolean isReachable(Object tether) {
        if (HeapImpl.getHeapImpl().isInImageHeap(tether)) {
            return true;
        }

        Space space = HeapChunk.getSpace(HeapChunk.getEnclosingHeapChunk(tether));
        if (space == null) {
            return false;
        }
        if (!space.isOldSpace()) {
            return false;
        }

        Word ptr = Word.objectToUntrackedPointer(tether);
        return RelocationInfo.getRelocatedObjectPointer(
                ptr
        ).isNonNull();
    }
}
