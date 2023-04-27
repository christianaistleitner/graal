package com.oracle.svm.core.genscavenge.tenured;

import com.oracle.svm.core.genscavenge.AlignedHeapChunk;
import com.oracle.svm.core.genscavenge.HeapChunk;
import com.oracle.svm.core.genscavenge.HeapImpl;
import com.oracle.svm.core.genscavenge.ObjectHeaderImpl;
import com.oracle.svm.core.genscavenge.Space;
import com.oracle.svm.core.genscavenge.remset.RememberedSet;
import com.oracle.svm.core.heap.ObjectReferenceVisitor;
import com.oracle.svm.core.heap.ReferenceAccess;
import com.oracle.svm.core.log.Log;
import org.graalvm.compiler.word.Word;
import org.graalvm.word.Pointer;

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

