/*
 * Copyright (c) 2022, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.graal;

import static jdk.compiler.graal.graph.Node.ConstantNodeParameter;

import java.util.Arrays;
import java.util.EnumSet;

import jdk.compiler.graal.api.replacements.SnippetReflectionProvider;
import jdk.compiler.graal.core.common.type.StampFactory;
import jdk.compiler.graal.debug.GraalError;
import jdk.compiler.graal.graph.NodeClass;
import jdk.compiler.graal.lir.gen.LIRGenerator;
import jdk.compiler.graal.nodeinfo.NodeCycles;
import jdk.compiler.graal.nodeinfo.NodeInfo;
import jdk.compiler.graal.nodeinfo.NodeSize;
import jdk.compiler.graal.nodes.ConstantNode;
import jdk.compiler.graal.nodes.FixedWithNextNode;
import jdk.compiler.graal.nodes.ValueNode;
import jdk.compiler.graal.nodes.graphbuilderconf.GraphBuilderConfiguration;
import jdk.compiler.graal.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.compiler.graal.nodes.graphbuilderconf.InvocationPlugin;
import jdk.compiler.graal.nodes.graphbuilderconf.InvocationPlugins;
import jdk.compiler.graal.nodes.spi.LIRLowerable;
import jdk.compiler.graal.nodes.spi.NodeLIRBuilderTool;
import jdk.compiler.graal.phases.util.Providers;

import com.oracle.svm.core.ParsingReason;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.cpufeature.RuntimeCPUFeatureCheck;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.graal.aarch64.AArch64CPUFeatureRegionOp;
import com.oracle.svm.graal.amd64.AMD64CPUFeatureRegionOp;

import jdk.vm.ci.aarch64.AArch64;
import jdk.vm.ci.amd64.AMD64;
import jdk.vm.ci.code.Architecture;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;

@AutomaticallyRegisteredFeature
class RuntimeCPUFeatureRegionFeature implements InternalFeature {

    @Override
    public void registerInvocationPlugins(Providers providers, SnippetReflectionProvider snippetReflection, GraphBuilderConfiguration.Plugins plugins, ParsingReason reason) {
        InvocationPlugins.Registration r = new InvocationPlugins.Registration(plugins.getInvocationPlugins(), RuntimeCPUFeatureRegion.class, providers.getReplacements());
        r.register(new InvocationPlugin.RequiredInlineOnlyInvocationPlugin("enter", Enum.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode arg0) {
                return createRegionEnterNode(providers, snippetReflection, b, arg0);
            }
        });
        r.register(new InvocationPlugin.RequiredInlineOnlyInvocationPlugin("enter", Enum.class, Enum.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode arg0, ValueNode arg1) {
                return createRegionEnterNode(providers, snippetReflection, b, arg0, arg1);
            }
        });
        r.register(new InvocationPlugin.RequiredInlineOnlyInvocationPlugin("enter", Enum.class, Enum.class, Enum.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode arg0, ValueNode arg1, ValueNode arg2) {
                return createRegionEnterNode(providers, snippetReflection, b, arg0, arg1, arg2);
            }
        });
        r.register(new InvocationPlugin.RequiredInlineOnlyInvocationPlugin("enterSet", EnumSet.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode arg0) {
                return createRegionEnterSetNode(providers, snippetReflection, b, arg0);
            }
        });
        r.register(new InvocationPlugin.RequiredInlineOnlyInvocationPlugin("leave", InvocationPlugin.Receiver.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                receiver.requireNonNull();
                b.add(new CPUFeatureRegionLeaveNode());
                return true;
            }
        });
    }

    private static boolean createRegionEnterNode(Providers providers, SnippetReflectionProvider snippetReflection, GraphBuilderContext b, ValueNode first, ValueNode... rest) {
        Enum<?> firstEnum = constValueToEnum(snippetReflection, first);
        Enum<?>[] restEnum = Arrays.stream(rest).map(n -> constValueToEnum(snippetReflection, n)).toArray(Enum<?>[]::new);
        EnumSet<?> features = toEnumSet(firstEnum, restEnum);
        b.add(new CPUFeatureRegionEnterNode(features));
        b.addPush(JavaKind.Object, ConstantNode.forConstant(snippetReflection.forObject(RuntimeCPUFeatureRegion.INSTANCE), providers.getMetaAccess()));
        return true;
    }

    private static boolean createRegionEnterSetNode(Providers providers, SnippetReflectionProvider snippetReflection, GraphBuilderContext b, ValueNode set) {
        GraalError.guarantee(set.isConstant(), "Must be a constant: %s", set);
        EnumSet<?> features = snippetReflection.asObject(EnumSet.class, set.asJavaConstant());
        b.add(new CPUFeatureRegionEnterNode(features));
        b.addPush(JavaKind.Object, ConstantNode.forConstant(snippetReflection.forObject(RuntimeCPUFeatureRegion.INSTANCE), providers.getMetaAccess()));
        return true;
    }

    private static Enum<?> constValueToEnum(SnippetReflectionProvider snippetReflection, ValueNode node) {
        GraalError.guarantee(node.isConstant(), "Must be a constant: %s", node);
        return snippetReflection.asObject(Enum.class, node.asJavaConstant());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EnumSet<?> toEnumSet(Enum first, Enum... rest) {
        return EnumSet.of(first, rest);
    }

    // @formatter:off
    @NodeInfo(nameTemplate = "CPUFeatureRegionEnter {p#features}",
            cycles = NodeCycles.CYCLES_0,
            cyclesRationale = "no code is generated for this node",
            size = NodeSize.SIZE_0,
            sizeRationale = "no code is generated for this node")
    // @formatter:on
    public static class CPUFeatureRegionEnterNode extends FixedWithNextNode implements LIRLowerable {
        public static final NodeClass<CPUFeatureRegionEnterNode> TYPE = NodeClass.create(CPUFeatureRegionEnterNode.class);

        private final EnumSet<?> features;

        protected CPUFeatureRegionEnterNode(EnumSet<?> features) {
            super(TYPE, StampFactory.objectNonNull());
            this.features = features;
        }

        @SuppressWarnings("unchecked")
        private static <T extends Enum<T>> EnumSet<T> checkedCast(EnumSet<?> features, Class<T> enumClass) {
            if (!features.isEmpty()) {
                GraalError.guarantee(enumClass.isInstance(features.iterator().next()), "Wrong enum set: %s vs. %s", enumClass, features);
            }
            return (EnumSet<T>) features;
        }

        @Override
        public void generate(NodeLIRBuilderTool tool) {
            Architecture arch = ConfigurationValues.getTarget().arch;
            if (arch instanceof AMD64) {
                LIRGenerator generator = (LIRGenerator) tool.getLIRGeneratorTool();
                generator.append(new AMD64CPUFeatureRegionOp.AMD64CPUFeatureRegionEnterOp(checkedCast(features, AMD64.CPUFeature.class)));
            } else if (arch instanceof AArch64) {
                LIRGenerator generator = (LIRGenerator) tool.getLIRGeneratorTool();
                generator.append(new AArch64CPUFeatureRegionOp.AArch64CPUFeatureRegionEnterOp(checkedCast(features, AArch64.CPUFeature.class)));
            } else {
                throw GraalError.shouldNotReachHere("unsupported architecture " + arch); // ExcludeFromJacocoGeneratedReport
            }
        }
    }

    // @formatter:off
    @NodeInfo(nameTemplate = "CPUFeatureRegionLeave",
            cycles = NodeCycles.CYCLES_0,
            cyclesRationale = "no code is generated for this node",
            size = NodeSize.SIZE_0,
            sizeRationale = "no code is generated for this node")
    // @formatter:on
    public static class CPUFeatureRegionLeaveNode extends FixedWithNextNode implements LIRLowerable {
        public static final NodeClass<CPUFeatureRegionLeaveNode> TYPE = NodeClass.create(CPUFeatureRegionLeaveNode.class);

        protected CPUFeatureRegionLeaveNode() {
            super(TYPE, StampFactory.forVoid());
        }

        @Override
        public void generate(NodeLIRBuilderTool tool) {
            Architecture arch = ConfigurationValues.getTarget().arch;
            if (arch instanceof AMD64) {
                LIRGenerator generator = (LIRGenerator) tool.getLIRGeneratorTool();
                generator.append(new AMD64CPUFeatureRegionOp.AMD64CPUFeatureRegionLeaveOp());
            } else if (arch instanceof AArch64) {
                LIRGenerator generator = (LIRGenerator) tool.getLIRGeneratorTool();
                generator.append(new AArch64CPUFeatureRegionOp.AArch64CPUFeatureRegionLeaveOp());
            } else {
                throw GraalError.shouldNotReachHere("unsupported architecture " + arch); // ExcludeFromJacocoGeneratedReport
            }
        }
    }
}

/**
 * Marker node for instructing the assembler to switch into a mode with or without support for a
 * given CPU feature. Regions with special CPU features must be guarded by appropriate checks
 * ({@link RuntimeCPUFeatureCheck#isSupported}).
 * <p>
 * Because we cannot predict the final code layout on the graph level, regions using special CPU
 * features must not stretch over graph block boundaries. In other words, every block using
 * operations requiring a special CPU feature must have an {@linkplain #enter
 * enter}/{@linkplain #leave exit} region pair. The recommended way to achieve this is via a
 * try-finally.
 *
 * <h2>Example</h2>
 *
 * <pre>
 * RuntimeCPUFeatureRegion region = RuntimeCPUFeatureRegion.enter(AVX);
 * try {
 *     // code that requires AVX
 * } finally {
 *     region.leave();
 * }
 * </pre>
 *
 * @see RuntimeCPUFeatureCheck#isSupported
 */
public final class RuntimeCPUFeatureRegion {

    static final RuntimeCPUFeatureRegion INSTANCE = new RuntimeCPUFeatureRegion();

    private RuntimeCPUFeatureRegion() {
    }

    /**
     * Enter a code region where the assembler supports {@code features}.
     * <p>
     * All arguments must be compile-time constant.
     */
    public static native RuntimeCPUFeatureRegion enter(@ConstantNodeParameter Enum<?> arg0);

    public static native RuntimeCPUFeatureRegion enterSet(@ConstantNodeParameter EnumSet<?> arg0);

    /**
     * @see #enter(Enum)
     */
    public static native <T extends Enum<T>> RuntimeCPUFeatureRegion enter(@ConstantNodeParameter Enum<T> arg0, @ConstantNodeParameter Enum<T> arg1);

    /**
     * @see #enter(Enum)
     */
    public static native <T extends Enum<T>> RuntimeCPUFeatureRegion enter(@ConstantNodeParameter Enum<T> arg0, @ConstantNodeParameter Enum<T> arg1, @ConstantNodeParameter Enum<T> arg2);

    public native void leave();
}
