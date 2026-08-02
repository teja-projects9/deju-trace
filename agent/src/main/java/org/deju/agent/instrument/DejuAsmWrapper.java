package org.deju.agent.instrument;

import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.asm.AsmVisitorWrapper;

/**
 * Plugs the raw-ASM coverage instrumentation into ByteBuddy's {@code AgentBuilder}.
 * Requests {@code COMPUTE_FRAMES}; ByteBuddy computes frames from its {@code TypePool}
 * (no risky class loading during transform). Our probes are stack-neutral and add no
 * new reference-type merges, so recomputed frames match the originals.
 */
public final class DejuAsmWrapper implements AsmVisitorWrapper {

    @Override
    public int mergeWriter(int flags) {
        return flags | ClassWriter.COMPUTE_FRAMES;
    }

    @Override
    public int mergeReader(int flags) {
        return flags | net.bytebuddy.jar.asm.ClassReader.EXPAND_FRAMES;
    }

    @Override
    public ClassVisitor wrap(TypeDescription instrumentedType,
                             ClassVisitor classVisitor,
                             Implementation.Context implementationContext,
                             TypePool typePool,
                             FieldList<FieldDescription.InDefinedShape> fields,
                             MethodList<?> methods,
                             int writerFlags,
                             int readerFlags) {
        return new CoverageClassVisitor(Opcodes.ASM9, classVisitor);
    }
}
