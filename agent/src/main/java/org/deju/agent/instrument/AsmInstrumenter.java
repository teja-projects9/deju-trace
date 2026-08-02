package org.deju.agent.instrument;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Opcodes;

/**
 * Applies the coverage instrumentation to a raw classfile. This is the same
 * {@link CoverageClassVisitor} the ByteBuddy {@code AgentBuilder} uses, exposed as a
 * plain {@code byte[] -> byte[]} transform so it can be unit-tested without attaching
 * an agent.
 *
 * <p>Uses {@code COMPUTE_FRAMES} with a classloading-safe {@link SafeClassWriter}: the
 * inserted probes are stack-neutral, so recomputed frames match the originals, and the
 * safe writer avoids any risky class loading during transformation.
 */
public final class AsmInstrumenter {

    private AsmInstrumenter() {
    }

    public static byte[] instrument(byte[] original, ClassLoader classLoader) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES, classLoader);
        ClassVisitor visitor = new CoverageClassVisitor(Opcodes.ASM9, writer);
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }
}
