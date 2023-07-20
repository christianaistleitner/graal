package com.oracle.svm.core.genscavenge;

import org.graalvm.compiler.word.ObjectAccess;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.struct.RawField;
import org.graalvm.nativeimage.c.struct.RawStructure;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.c.struct.UniqueLocationIdentity;
import org.graalvm.word.PointerBase;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.AlwaysInline;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.headers.LibC;

import static com.oracle.svm.core.Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE;

class MarkQueue {

    private final static int ENTRIES_PER_SEGMENT = 1_000;

    private Segment first;

    private Segment last;

    private int pushCursor = 0;

    private int popCursor = 0;

    @Platforms(Platform.HOSTED_ONLY.class)
    MarkQueue() {
    }

    @AlwaysInline("GC performance")
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    void push(Object obj) {
        assert obj != null;

        if (last.isNull()) {
            last = allocateSegment();
            first = last;
        }

        if (pushCursor == ENTRIES_PER_SEGMENT) {
            Segment seg = allocateSegment();
            last.setNext(seg);
            last = seg;
            pushCursor = 0;
        }

        UnsignedWord offset = SizeOf.unsigned(Segment.class).add(
                pushCursor * ConfigurationValues.getObjectLayout().getReferenceSize()
        );
        ObjectAccess.writeObject(last, offset, obj);

        pushCursor++;
    }

    @AlwaysInline("GC performance")
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    Object pop() {
        if (isEmpty()) {
            return null;
        }

        UnsignedWord offset = SizeOf.unsigned(Segment.class).add(
                popCursor * ConfigurationValues.getObjectLayout().getReferenceSize()
        );
        Object obj = ObjectAccess.readObject(first, offset);
        assert obj != null;

        popCursor++;
        if (popCursor == ENTRIES_PER_SEGMENT) {
            Segment next = first.getNext();
            if (next.isNonNull()) {
                LibC.free(first);
                first = next;
            } else {
                pushCursor = 0;
            }
            popCursor = 0;
        }

        return obj;
    }

    @AlwaysInline("GC performance")
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    boolean isEmpty() {
        return first.isNull() || first.equal(last) && popCursor == pushCursor;
    }

    @RawStructure
    interface Segment extends PointerBase {

        @RawField
        @UniqueLocationIdentity
        Segment getNext();

        @RawField
        @UniqueLocationIdentity
        void setNext(Segment p);
    }

    @AlwaysInline("GC performance")
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    private static Segment allocateSegment() {
        UnsignedWord size = SizeOf.unsigned(Segment.class).add(
                ENTRIES_PER_SEGMENT * ConfigurationValues.getObjectLayout().getReferenceSize()
        );
        Segment segment = LibC.malloc(size);
        segment.setNext(WordFactory.nullPointer());
        return segment;
    }
}
