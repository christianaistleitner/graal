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
    private AlignedHeapChunk.AlignedHeader chunk;

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

        // if (isInOldSpace(holderObject) && !ObjectHeaderImpl.hasMarkedBit(holderObject)) {
        //     Log.log().string("[ERROR: Fixing dead holder object died, obj=").object(obj)
        //             .string(", holderObject=").object(holderObject)
        //             .string("]\n").flush();
        //     assert false : "Referred object died!";
        // }

        // if (!ObjectHeaderImpl.hasMarkedBit(obj)) {
        //     Log.log().string("[ERROR: Referred object died, obj=").object(obj)
        //             .string(", holderObject=").object(holderObject)
        //             .string("]\n").flush();
        //     assert false : "Referred object died!";
        // }

        Pointer relocationInfo = AlignedHeapChunk.getEnclosingChunkFromObjectPointer(p).getFirstRelocationInfo();
        if (relocationInfo.isNull() || relocationInfo.aboveThan(p)) {
            if (HeapImpl.getHeapImpl().isInImageHeap(holderObject)){
                RememberedSet.get().dirtyCardIfNecessary(holderObject, obj);
            }
            return true;
        }

        Pointer nextRelocationInfo = RelocationInfo.getNextRelocationInfo(relocationInfo);
        while (nextRelocationInfo.isNonNull() && nextRelocationInfo.belowOrEqual(p)) {
            relocationInfo = nextRelocationInfo;
            nextRelocationInfo = RelocationInfo.getNextRelocationInfo(relocationInfo);
        }

        Pointer relocationPointer = RelocationInfo.readRelocationPointer(relocationInfo);
        Pointer newLocationOld = relocationPointer.add(p.subtract(relocationInfo));

        Pointer newLocation = RelocationInfo.getRelocatedObjectPointer(p);
        assert newLocation.equal(newLocationOld);

        Object offsetObj = (innerOffset == 0) ? newLocation.toObject() : newLocation.add(innerOffset).toObject();
        ReferenceAccess.singleton().writeObjectAt(objRef, offsetObj, compressed);

        // if (ObjectHeaderImpl.hasRememberedSet(ObjectHeaderImpl.readHeaderFromObject(holderObject))) {
        //     RememberedSet.get().dirtyCardIfNecessary(holderObject, newLocation.toObject());
        // }
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
                    .string(", relocationInfo=").zhex(relocationInfo)
                    .string(", relocationPointer=").zhex(relocationPointer)
                    .newline().flush();
        }

        // if (!ObjectHeaderImpl.hasMarkedBit(obj)) {
        //     Log.log().string("Updated location but object is not marked, old=").zhex(p)
        //             .string(", new=").zhex(newLocation)
        //             .string(", diff=").signed(newLocation.subtract(p))
        //             .string(", holderObject=").object(holderObject)
        //             .string(", objRef=").zhex(objRef)
        //             .string(", innerOffset=").signed(innerOffset)
        //             .string(", compressed=").bool(compressed)
        //             .string(", relocationInfo=").zhex(relocationInfo)
        //             .string(", relocationPointer=").zhex(relocationPointer)
        //             .newline().flush();
        // }

        chunk = AlignedHeapChunk.getEnclosingChunkFromObjectPointer(p);
        if (AlignedHeapChunk.getObjectsStart(chunk).aboveThan(newLocation) || AlignedHeapChunk.getObjectsEnd(chunk).belowOrEqual(newLocation)) {
            Log.log().string("ERROR - Updated location, old=").zhex(p)
                    .string(", new=").zhex(newLocation)
                    .string(", diff=").signed(newLocation.subtract(p))
                    .string(", holderObject=").zhex(Word.objectToUntrackedPointer(holderObject))
                    .string(", objRef=").zhex(objRef)
                    .string(", relocationInfo=").zhex(relocationInfo)
                    .string(", relocationPointer=").zhex(relocationPointer)
                    .newline().flush();
        }

        if (p.and(15).isNull()) {
            Log.noopLog().string("Updated location, old=").zhex(p)
                    .string(", new=").zhex(newLocation)
                    .string(", diff=").signed(newLocation.subtract(p))
                    .string(", holderObject=").zhex(Word.objectToUntrackedPointer(holderObject))
                    .string(", objRef=").zhex(objRef)
                    .string(", relocationInfo=").zhex(relocationInfo)
                    .string(", relocationPointer=").zhex(relocationPointer)
                    .newline().flush();
        }

        return true;
    }

    public void setChunk(AlignedHeapChunk.AlignedHeader chunk) {
        this.chunk = chunk;
    }

    static boolean isInOldSpace(Object obj) {
        if (obj != null) {
            HeapChunk.Header<?> chunk = HeapChunk.getEnclosingHeapChunk(obj);
            Space space = HeapChunk.getSpace(chunk);
            if (space != null) {
                return space.isOldSpace();
            }
        }
        return false;
    }
}

