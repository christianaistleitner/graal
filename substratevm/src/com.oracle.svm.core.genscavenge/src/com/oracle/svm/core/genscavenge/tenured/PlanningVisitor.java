package com.oracle.svm.core.genscavenge.tenured;

import org.graalvm.compiler.word.Word;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.AlwaysInline;
import com.oracle.svm.core.NeverInline;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.genscavenge.AlignedHeapChunk;
import com.oracle.svm.core.genscavenge.HeapChunk;
import com.oracle.svm.core.genscavenge.ObjectHeaderImpl;
import com.oracle.svm.core.heap.ObjectVisitor;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.log.Log;

import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.SLOW_PATH_PROBABILITY;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.probability;

public class PlanningVisitor implements ObjectVisitor {

    private AlignedHeapChunk.AlignedHeader chunk;

    private Pointer relocationInfoPointer;

    private Pointer relocationPointer;

    private UnsignedWord gapSize;

    @Platforms(Platform.HOSTED_ONLY.class)
    public PlanningVisitor() {
    }

    @Override
    @NeverInline("Non-performance critical version")
    public boolean visitObject(Object o) {
        return visitObjectInline(o);
    }

    @Override
    @AlwaysInline("GC performance")
    public boolean visitObjectInline(Object obj) {
        UnsignedWord objSize = LayoutEncoding.getSizeFromObjectInlineInGC(obj);

        if (ObjectHeaderImpl.hasMarkedBit(obj)) {
            ObjectHeaderImpl.clearMarkedBit(obj);

            Pointer objPointer = Word.objectToUntrackedPointer(obj);

            if (gapSize.notEqual(0)) {
                Log.noopLog().string("Gap from ").zhex(objPointer.subtract(gapSize))
                        .string(" to ").zhex(objPointer)
                        .string(" (").unsigned(gapSize).string(" bytes)")
                        .newline().flush();

                /*
                 * Update previous relocation info or set the chunk's "FirstRelocationInfo" pointer.
                 */
                if (relocationInfoPointer.isNull()) {
                    chunk.setFirstRelocationInfo(objPointer);
                } else {
                    int offset = (int) objPointer.subtract(relocationInfoPointer).rawValue();
                    RelocationInfo.writeNextPlugOffset(relocationInfoPointer, offset);

                    Log.noopLog().string("Updated relocation info at ").zhex(relocationInfoPointer)
                            .string(": nextPlugOffset=").zhex(offset)
                            .newline().flush();
                }

                /*
                 * Write the current relocation info at the gap end.
                 */
                relocationInfoPointer = objPointer;
                RelocationInfo.writeRelocationPointer(relocationInfoPointer, relocationPointer);
                RelocationInfo.writeGapSize(relocationInfoPointer, (int) gapSize.rawValue());
                RelocationInfo.writeNextPlugOffset(relocationInfoPointer, 0);

                Log.noopLog().string("Wrote relocation info at ").zhex(relocationInfoPointer)
                        .string(": relocationPointer=").zhex(relocationPointer)
                        .string(": gapSize=").unsigned(gapSize)
                        .string(": nextPlugOffset=").zhex(0)
                        .newline().flush();

                gapSize = WordFactory.zero();
            }

            /*
             * Adding the optional identity hash field will increase the object's size.
             */
            if (!ConfigurationValues.getObjectLayout().hasFixedIdentityHashField()) {
                Word header = ObjectHeaderImpl.readHeaderFromObject(obj);
                if (probability(SLOW_PATH_PROBABILITY, ObjectHeaderImpl.hasIdentityHashFromAddressInline(header))) {
                    objSize = LayoutEncoding.getSizeFromObjectInlineInGC(obj, true);
                }
            }

            relocationPointer = relocationPointer.add(objSize);
        } else {
            gapSize = gapSize.add(objSize);
        }
        return true;
    }

    public void init(AlignedHeapChunk.AlignedHeader chunk) {
        this.chunk = chunk;
        this.relocationInfoPointer = WordFactory.nullPointer();
        this.relocationPointer = AlignedHeapChunk.getObjectsStart(chunk);
        this.gapSize =  WordFactory.zero();
    }

    public void finish() {
        /*
         * Check for a gap at chunk end that requires updating the chunk top offset to clear that memory.
         */
        if (gapSize.notEqual(0)) {
            Pointer topPointer = HeapChunk.getTopPointer(chunk);
            Pointer gapStart = topPointer.subtract(gapSize);
            Log.noopLog().string("Gap at chunk end from ").zhex(gapStart)
                    .string(" to ").zhex(topPointer)
                    .string(" (").unsigned(gapSize).string(" bytes)")
                    .newline().flush();

            chunk.setTopOffset(chunk.getTopOffset().subtract(gapSize));
        }
    }
}