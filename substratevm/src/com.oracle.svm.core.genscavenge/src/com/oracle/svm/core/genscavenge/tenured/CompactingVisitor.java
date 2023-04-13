package com.oracle.svm.core.genscavenge.tenured;

import com.oracle.svm.core.UnmanagedMemoryUtil;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.genscavenge.AlignedHeapChunk;
import com.oracle.svm.core.genscavenge.HeapChunk;
import com.oracle.svm.core.genscavenge.ObjectHeaderImpl;
import com.oracle.svm.core.heap.ObjectVisitor;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.identityhashcode.IdentityHashCodeSupport;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.thread.VMOperation;
import org.graalvm.compiler.word.ObjectAccess;
import org.graalvm.compiler.word.Word;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.SLOW_PATH_PROBABILITY;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.VERY_SLOW_PATH_PROBABILITY;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.probability;

public class CompactingVisitor implements ObjectVisitor {

    private Pointer relocationPointer;
    private Pointer relocationInfoPointer;
    private Pointer nextRelocationInfoPointer;

    private AlignedHeapChunk.AlignedHeader chunk;

    @Override
    public boolean visitObject(Object obj) {
        if (relocationInfoPointer.isNull()) {
            return true;
        }

        Pointer objPointer = Word.objectToUntrackedPointer(obj);
        if (objPointer.belowThan(relocationInfoPointer)) {
            return true;
        }

        if (nextRelocationInfoPointer.isNonNull() && objPointer.aboveOrEqual(nextRelocationInfoPointer)) {
            relocationInfoPointer = nextRelocationInfoPointer;
            nextRelocationInfoPointer = RelocationInfo.getNextRelocationInfo(relocationInfoPointer);
            relocationPointer = RelocationInfo.readRelocationPointer(relocationInfoPointer);
        }

        Pointer newLocation = objPointer.subtract(relocationInfoPointer).add(relocationPointer);

        Log.noopLog().string("New relocation")
                .string(", newLocation=").zhex(newLocation)
                .string(", objPointer=").zhex(objPointer)
                .string(", relocationInfoPointer=").zhex(relocationInfoPointer)
                .string(", relocationPointer=").zhex(relocationPointer)
                .newline().flush();

        UnsignedWord copySize = copyObject(obj, newLocation);

        HeapChunk.setTopPointer(chunk, newLocation.add(copySize));

        return true;
    }

    public void init(AlignedHeapChunk.AlignedHeader chunk) {
        this.chunk = chunk;
        relocationInfoPointer = chunk.getFirstRelocationInfo();
        nextRelocationInfoPointer = WordFactory.nullPointer();
        relocationPointer = WordFactory.nullPointer();
        if (relocationInfoPointer.isNonNull()) {
            int offset = RelocationInfo.readNextPlugOffset(relocationInfoPointer);
            if (offset > 0) {
                nextRelocationInfoPointer = relocationInfoPointer.add(offset);
            }
            relocationPointer = RelocationInfo.readRelocationPointer(relocationInfoPointer);
        }
    }

    public void finish() {
        chunk.setFirstRelocationInfo(null);
    }

    private UnsignedWord copyObject(Object obj, Pointer dest) {
        assert VMOperation.isGCInProgress();
        assert ObjectHeaderImpl.isAlignedObject(obj);

        // TODO: code cleanup

        UnsignedWord originalSize = LayoutEncoding.getSizeFromObjectInlineInGC(obj, false);
        UnsignedWord copySize = originalSize;
        boolean addIdentityHashField = false;
        if (!ConfigurationValues.getObjectLayout().hasFixedIdentityHashField()) {
            Word header = ObjectHeaderImpl.readHeaderFromObject(obj);
            if (probability(SLOW_PATH_PROBABILITY, ObjectHeaderImpl.hasIdentityHashFromAddressInline(header))) {
                addIdentityHashField = true;
                copySize = LayoutEncoding.getSizeFromObjectInlineInGC(obj, true);
            }
        }

        if (probability(VERY_SLOW_PATH_PROBABILITY, dest.isNull())) {
            return WordFactory.zero();
        }

        /*
         * This does a direct memory copy, without regard to whether the copied data contains object
         * references. That's okay, because all references in the copy are visited and overwritten
         * later on anyways (the card table is also updated at that point if necessary).
         */
        Pointer originalMemory = Word.objectToUntrackedPointer(obj);
        Log.noopLog().string("Copying object from ").zhex(originalMemory).character('-').zhex(originalMemory.add(originalSize))
                .string(" (").unsigned(originalSize).string(" B)")
                .string(" to ").zhex(dest).character('-').zhex(dest.add(copySize))
                .string(" (").unsigned(copySize).string(" B)")
                .newline().flush();
        UnmanagedMemoryUtil.copyLongsForward(originalMemory, dest, copySize);

        if (probability(SLOW_PATH_PROBABILITY, addIdentityHashField)) {
            Log.log().string("Added identity hash field!\n").flush();
            Object copy = dest.toObject();
            // Must do first: ensures correct object size below and in other places
            int value = IdentityHashCodeSupport.computeHashCodeFromAddress(obj);
            int offset = LayoutEncoding.getOptionalIdentityHashOffset(copy);
            ObjectAccess.writeInt(copy, offset, value, IdentityHashCodeSupport.IDENTITY_HASHCODE_LOCATION);
            ObjectHeaderImpl.getObjectHeaderImpl().setIdentityHashInField(copy);
        }

        return copySize;
    }
}
