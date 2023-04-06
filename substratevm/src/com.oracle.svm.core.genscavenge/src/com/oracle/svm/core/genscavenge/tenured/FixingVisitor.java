package com.oracle.svm.core.genscavenge.tenured;

import com.oracle.svm.core.genscavenge.AlignedHeapChunk;
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

    public void setChunk(AlignedHeapChunk.AlignedHeader chunk) {
        refFixingVisitor.setChunk(chunk);
    }
}