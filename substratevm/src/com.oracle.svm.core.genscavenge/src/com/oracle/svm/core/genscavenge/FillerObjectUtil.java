package com.oracle.svm.core.genscavenge;

import org.graalvm.compiler.word.Word;
import org.graalvm.word.Pointer;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.UnmanagedMemoryUtil;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.config.ObjectLayout;
import com.oracle.svm.core.heap.FillerObject;
import com.oracle.svm.core.util.VMError;
import jdk.vm.ci.meta.JavaKind;

class FillerObjectUtil {

    private static final int[] ARRAY = new int[0];

    private static final FillerObject OBJECT = new FillerObject();

    /**
     * Inspired by {@link com.oracle.svm.hosted.image.NativeImageHeap#addFillerObject}.
     */
    static void writeFillerObjectAt(Pointer dest, int size) {
        ObjectLayout objectLayout = ConfigurationValues.getObjectLayout();

        int minArraySize = objectLayout.getMinImageHeapArraySize();
        if (size >= minArraySize) {
            int elementSize = objectLayout.getArrayIndexScale(JavaKind.Int);
            int arrayLength = (size - minArraySize) / elementSize;
            assert objectLayout.getArraySize(JavaKind.Int, arrayLength, true) == size;

            Pointer src = Word.objectToUntrackedPointer(ARRAY);
            UnmanagedMemoryUtil.copyLongsForward(src, dest, WordFactory.unsigned(minArraySize));
            dest.writeInt(objectLayout.getArrayLengthOffset(), arrayLength);
            return;
        }

        int minObjectSize = objectLayout.getMinImageHeapObjectSize();
        if (size >= minObjectSize) {
            Pointer src = Word.objectToUntrackedPointer(OBJECT);
            UnmanagedMemoryUtil.copyLongsForward(src, dest, WordFactory.unsigned(minObjectSize));
            return;
        }

        VMError.shouldNotReachHere();
    }
}
