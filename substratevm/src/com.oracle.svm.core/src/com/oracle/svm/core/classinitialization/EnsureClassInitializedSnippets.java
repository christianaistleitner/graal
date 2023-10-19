/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.classinitialization;

import java.util.Map;

import jdk.compiler.graal.api.replacements.Snippet;
import jdk.compiler.graal.core.common.spi.ForeignCallDescriptor;
import jdk.compiler.graal.graph.Node;
import jdk.compiler.graal.graph.Node.ConstantNodeParameter;
import jdk.compiler.graal.graph.Node.NodeIntrinsic;
import jdk.compiler.graal.nodes.PiNode;
import jdk.compiler.graal.nodes.SnippetAnchorNode;
import jdk.compiler.graal.nodes.extended.BranchProbabilityNode;
import jdk.compiler.graal.nodes.spi.LoweringTool;
import jdk.compiler.graal.options.OptionValues;
import jdk.compiler.graal.phases.util.Providers;
import jdk.compiler.graal.replacements.SnippetTemplate;
import jdk.compiler.graal.replacements.SnippetTemplate.Arguments;
import jdk.compiler.graal.replacements.SnippetTemplate.SnippetInfo;
import jdk.compiler.graal.replacements.Snippets;
import org.graalvm.word.LocationIdentity;

import com.oracle.svm.core.graal.nodes.ForeignCallWithExceptionNode;
import com.oracle.svm.core.graal.snippets.NodeLoweringProvider;
import com.oracle.svm.core.graal.snippets.SubstrateTemplates;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.snippets.SnippetRuntime;
import com.oracle.svm.core.snippets.SnippetRuntime.SubstrateForeignCallDescriptor;

public final class EnsureClassInitializedSnippets extends SubstrateTemplates implements Snippets {
    private static final SubstrateForeignCallDescriptor INITIALIZE = SnippetRuntime.findForeignCall(ClassInitializationInfo.class, "initialize", false, LocationIdentity.any());

    public static final SubstrateForeignCallDescriptor[] FOREIGN_CALLS = new SubstrateForeignCallDescriptor[]{
                    INITIALIZE,
    };

    /**
     * The nodes that are generated by this snippet may end up in uninterruptible methods that are
     * annotated with calleeMustBe = false.
     */
    @Snippet
    private static void ensureClassIsInitializedSnippet(@Snippet.NonNullParameter DynamicHub hub) {
        ClassInitializationInfo info = hub.getClassInitializationInfo();
        /*
         * The ClassInitializationInfo field is always initialized by the image generator. We can
         * save the explicit null check.
         */
        ClassInitializationInfo infoNonNull = (ClassInitializationInfo) PiNode.piCastNonNull(info, SnippetAnchorNode.anchor());

        if (BranchProbabilityNode.probability(BranchProbabilityNode.EXTREMELY_SLOW_PATH_PROBABILITY, !infoNonNull.isInitialized())) {
            callInitialize(INITIALIZE, infoNonNull, DynamicHub.toClass(hub));
        }
    }

    @NodeIntrinsic(value = ForeignCallWithExceptionNode.class)
    private static native void callInitialize(@ConstantNodeParameter ForeignCallDescriptor descriptor, ClassInitializationInfo info, Class<?> clazz);

    @SuppressWarnings("unused")
    public static void registerLowerings(OptionValues options, Providers providers, Map<Class<? extends Node>, NodeLoweringProvider<?>> lowerings) {
        new EnsureClassInitializedSnippets(options, providers, lowerings);
    }

    private final SnippetInfo ensureClassIsInitialized;

    private EnsureClassInitializedSnippets(OptionValues options, Providers providers, Map<Class<? extends Node>, NodeLoweringProvider<?>> lowerings) {
        super(options, providers);

        this.ensureClassIsInitialized = snippet(providers, EnsureClassInitializedSnippets.class, "ensureClassIsInitializedSnippet", LocationIdentity.any());

        lowerings.put(EnsureClassInitializedNode.class, new EnsureClassInitializedNodeLowering());
    }

    class EnsureClassInitializedNodeLowering implements NodeLoweringProvider<EnsureClassInitializedNode> {
        @Override
        public void lower(EnsureClassInitializedNode node, LoweringTool tool) {
            Arguments args = new Arguments(ensureClassIsInitialized, node.graph().getGuardsStage(), tool.getLoweringStage());
            args.add("hub", node.getHub());
            template(tool, node, args).instantiate(tool.getMetaAccess(), node, SnippetTemplate.DEFAULT_REPLACER, args);
        }
    }
}
