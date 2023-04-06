package com.oracle.svm.core.genscavenge.tenured;

import com.oracle.svm.core.genscavenge.ObjectHeaderImpl;
import com.oracle.svm.core.heap.ObjectVisitor;

public class AllObjectsMarkingVisitor implements ObjectVisitor {

    @Override
    public boolean visitObject(Object obj) {
        ObjectHeaderImpl.setMarkedBit(obj);
        return true;
    }
}

