package org.deju.agent.instrument;

import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import org.deju.agent.model.ClassModel;
import org.deju.agent.model.MethodModel;
import org.deju.agent.model.Registry;

/**
 * Builds the static {@link ClassModel}/{@link MethodModel} metadata for a class and
 * wraps each concrete method with a {@link CoverageMethodVisitor}. Abstract and
 * native methods (no bytecode) are passed through untouched.
 */
final class CoverageClassVisitor extends ClassVisitor {

    private String className;      // dotted
    private String sourceFileName;
    private ClassModel classModel; // lazily built (visitSource arrives after visit)

    CoverageClassVisitor(int api, ClassVisitor next) {
        super(api, next);
    }

    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
        this.className = name.replace('/', '.');
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public void visitSource(String source, String debug) {
        this.sourceFileName = source;
        super.visitSource(source, debug);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (mv == null) {
            return null;
        }
        if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return mv; // no code to instrument
        }
        int methodGid = Registry.newMethodId();
        MethodModel model = new MethodModel(methodGid, classModel(), name, descriptor);
        Registry.putMethod(model);
        return new CoverageMethodVisitor(api, mv, model);
    }

    private ClassModel classModel() {
        if (classModel == null) {
            classModel = new ClassModel(className, sourceFileName);
        }
        return classModel;
    }
}
