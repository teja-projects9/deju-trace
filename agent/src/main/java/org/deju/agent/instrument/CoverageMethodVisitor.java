package org.deju.agent.instrument;

import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import org.deju.agent.model.DecisionModel;
import org.deju.agent.model.LineProbe;
import org.deju.agent.model.MethodModel;
import org.deju.agent.model.Registry;

/**
 * Inserts coverage probes into one method. Every insertion is <b>stack-neutral</b>
 * and adds <b>no new jump targets</b>, so the class's existing stack-map frames stay
 * valid and only {@code COMPUTE_MAXS} is required, never {@code COMPUTE_FRAMES}
 * (which would load classes during transform and risk deadlock inside an agent).
 *
 * <ul>
 *   <li>Method entry/exit → {@code enter}/{@code exit} (exit before each normal return).</li>
 *   <li>Each source line → {@code line}.</li>
 *   <li>Each conditional jump → the operand(s) are duplicated and handed to a
 *       {@code probe*} method which re-evaluates the condition to learn the edge taken.</li>
 * </ul>
 *
 * <p>TODO(deju): exit is emitted on normal returns only. An exception propagating
 * <i>through</i> the target frame will not fire exit; the runtime relies on a
 * disarm/next-entry reset to recover. A production build would add a frame-correct
 * try/finally (which requires computing frames without classloading, JaCoCo-style).
 */
final class CoverageMethodVisitor extends MethodVisitor {

    private static final String RUNTIME = "org/deju/agent/runtime/CoverageRuntime";

    private final MethodModel model;
    private int currentLine = -1;

    CoverageMethodVisitor(int api, MethodVisitor delegate, MethodModel model) {
        super(api, delegate);
        this.model = model;
    }

    @Override
    public void visitCode() {
        super.visitCode();
        // enter(methodGid), records that this method was entered / opens the session.
        pushInt(model.getMethodGid());
        super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "enter", "(I)V", false);
    }

    @Override
    public void visitLineNumber(int line, Label start) {
        super.visitLineNumber(line, start);
        this.currentLine = line;
        int probeId = lineProbeIdFor(line);
        pushInt(probeId);
        super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "line", "(I)V", false);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        switch (opcode) {
            case Opcodes.IFEQ:
            case Opcodes.IFNE:
            case Opcodes.IFLT:
            case Opcodes.IFGE:
            case Opcodes.IFGT:
            case Opcodes.IFLE: {
                int decisionId = registerBooleanDecision();
                super.visitInsn(Opcodes.DUP);          // duplicate the int being tested
                pushInt(opcode);
                pushInt(decisionId);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "probe1", "(III)V", false);
                break;
            }
            case Opcodes.IF_ICMPEQ:
            case Opcodes.IF_ICMPNE:
            case Opcodes.IF_ICMPLT:
            case Opcodes.IF_ICMPGE:
            case Opcodes.IF_ICMPGT:
            case Opcodes.IF_ICMPLE: {
                int decisionId = registerBooleanDecision();
                super.visitInsn(Opcodes.DUP2);         // duplicate both ints
                pushInt(opcode);
                pushInt(decisionId);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "probe2", "(IIII)V", false);
                break;
            }
            case Opcodes.IF_ACMPEQ:
            case Opcodes.IF_ACMPNE: {
                int decisionId = registerBooleanDecision();
                super.visitInsn(Opcodes.DUP2);         // duplicate both refs
                pushInt(opcode);
                pushInt(decisionId);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "probeRef2",
                        "(Ljava/lang/Object;Ljava/lang/Object;II)V", false);
                break;
            }
            case Opcodes.IFNULL:
            case Opcodes.IFNONNULL: {
                int decisionId = registerBooleanDecision();
                super.visitInsn(Opcodes.DUP);          // duplicate the ref
                pushInt(opcode);
                pushInt(decisionId);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "probeRef1",
                        "(Ljava/lang/Object;II)V", false);
                break;
            }
            default:
                // GOTO / JSR, unconditional, nothing to record.
                break;
        }
        super.visitJumpInsn(opcode, label);
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
        int edgeCount = labels.length + 1; // one edge per case + default
        int[] keys = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            keys[i] = min + i;
        }
        emitSwitchProbe(keys, edgeCount);
        super.visitTableSwitchInsn(min, max, dflt, labels);
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        int edgeCount = keys.length + 1; // one edge per case + default
        emitSwitchProbe(keys.clone(), edgeCount);
        super.visitLookupSwitchInsn(dflt, keys, labels);
    }

    @Override
    public void visitInsn(int opcode) {
        if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
            // exit(methodGid) before a normal return.
            pushInt(model.getMethodGid());
            super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "exit", "(I)V", false);
        }
        super.visitInsn(opcode);
    }

    // ------------------------------------------------------------------ helpers ---

    private void emitSwitchProbe(int[] switchKeys, int edgeCount) {
        int decisionId = Registry.newDecisionId();
        model.addDecision(currentLine, decisionId, edgeCount);
        Registry.putDecision(new DecisionModel(decisionId, model.getMethodGid(), currentLine,
                DecisionModel.Kind.SWITCH, switchKeys));
        super.visitInsn(Opcodes.DUP);              // duplicate the switch key
        pushInt(decisionId);
        super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "probeSwitch", "(II)V", false);
    }

    private int registerBooleanDecision() {
        int decisionId = Registry.newDecisionId();
        model.addDecision(currentLine, decisionId, 2); // taken / not-taken
        Registry.putDecision(new DecisionModel(decisionId, model.getMethodGid(), currentLine,
                DecisionModel.Kind.BOOLEAN, null));
        return decisionId;
    }

    private int lineProbeIdFor(int line) {
        Integer existing = model.lineProbeId(line);
        if (existing != null) {
            return existing;
        }
        int pid = Registry.newLineProbeId();
        model.registerLine(line, pid);
        Registry.putLineProbe(pid, new LineProbe(model.getMethodGid(), line));
        return pid;
    }

    /** Emit the most compact push for a small non-negative int constant. */
    private void pushInt(int value) {
        if (value >= -1 && value <= 5) {
            super.visitInsn(Opcodes.ICONST_0 + value); // ICONST_M1 .. ICONST_5
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            super.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            super.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            super.visitLdcInsn(value);
        }
    }
}
