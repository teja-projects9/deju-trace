package org.deju.agent.instrument;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassWriter;

/**
 * A {@link ClassWriter} whose {@code getCommonSuperClass} never triggers a class
 * load that could fail or deadlock inside an agent. When it cannot resolve a common
 * super type from the target's own {@link ClassLoader}, it falls back to
 * {@code java/lang/Object}.
 *
 * <p>Our probes add no new reference-type control-flow merges, so this fallback is
 * only ever exercised for merges already present in the original method, where a
 * conservative {@code Object} answer keeps {@code COMPUTE_FRAMES} correct.
 */
final class SafeClassWriter extends ClassWriter {

    private final ClassLoader classLoader;

    SafeClassWriter(ClassReader reader, int flags, ClassLoader classLoader) {
        super(reader, flags);
        this.classLoader = classLoader != null ? classLoader : getClass().getClassLoader();
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        try {
            Class<?> c1 = Class.forName(type1.replace('/', '.'), false, classLoader);
            Class<?> c2 = Class.forName(type2.replace('/', '.'), false, classLoader);
            if (c1.isAssignableFrom(c2)) {
                return type1;
            }
            if (c2.isAssignableFrom(c1)) {
                return type2;
            }
            if (c1.isInterface() || c2.isInterface()) {
                return "java/lang/Object";
            }
            do {
                c1 = c1.getSuperclass();
            } while (c1 != null && !c1.isAssignableFrom(c2));
            return c1 == null ? "java/lang/Object" : c1.getName().replace('.', '/');
        } catch (Throwable t) {
            // Any resolution failure -> conservative, always-valid answer.
            return "java/lang/Object";
        }
    }
}
