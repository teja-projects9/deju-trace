package org.deju.agent.model;

/**
 * A single decision point (one conditional jump, or one switch) discovered during
 * instrumentation. Boolean decisions have two edges (taken / not-taken); switches
 * have one edge per case plus one for {@code default}.
 */
public final class DecisionModel {

    public enum Kind { BOOLEAN, SWITCH }

    private final int decisionId;
    private final int methodGid;
    private final int line;
    private final Kind kind;
    /** For SWITCH decisions: the case keys in ascending order (default is the last edge). Null for BOOLEAN. */
    private final int[] switchKeys;
    /**
     * For BOOLEAN decisions: the JVM opcode of the conditional jump (e.g. {@code IFEQ}).
     * 0 for SWITCH. Lets {@code PayloadBuilder} tell a plain boolean/null-check test
     * ({@code IFEQ}/{@code IFNE}/{@code IFNULL}/{@code IFNONNULL} — where "taken" maps
     * unambiguously back to the tested value's own truthiness) apart from a relational
     * comparison ({@code IF_ICMPxx}, etc. — where it doesn't, see the operand-coloring
     * design notes) without needing to re-derive it from anything else.
     */
    private final int opcode;

    public DecisionModel(int decisionId, int methodGid, int line, Kind kind, int[] switchKeys, int opcode) {
        this.decisionId = decisionId;
        this.methodGid = methodGid;
        this.line = line;
        this.kind = kind;
        this.switchKeys = switchKeys;
        this.opcode = opcode;
    }

    public int getDecisionId() {
        return decisionId;
    }

    public int getMethodGid() {
        return methodGid;
    }

    public int getLine() {
        return line;
    }

    public Kind getKind() {
        return kind;
    }

    public int[] getSwitchKeys() {
        return switchKeys;
    }

    public int getOpcode() {
        return opcode;
    }

    // JVM opcode values (ASM's Opcodes constants), duplicated here rather than depending
    // on the ASM package from the model class: IFEQ=153, IFNE=154, IFNULL=198, IFNONNULL=199.
    private static final int IFEQ = 153, IFNE = 154, IFNULL = 198, IFNONNULL = 199;

    /**
     * Whether "taken" (edge 1) means the tested value was itself truthy (non-zero /
     * non-null) — {@code TRUE}/{@code FALSE} for the four opcodes where that mapping is
     * unambiguous ({@code IFEQ}/{@code IFNE}/{@code IFNULL}/{@code IFNONNULL}), or
     * {@code null} for every other opcode (relational comparisons, where the same
     * source text could compile to either sense depending on {@code &&}/{@code ||}
     * context — see the operand-coloring design notes).
     */
    public Boolean rawTruthinessOnTaken() {
        switch (opcode) {
            case IFNE:
            case IFNONNULL:
                return Boolean.TRUE;
            case IFEQ:
            case IFNULL:
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    /**
     * Resolves a switch key to its edge index: the position of {@code key} among the
     * sorted keys, or {@code switchKeys.length} (the default edge) if not present.
     */
    public int switchEdgeIndex(int key) {
        if (switchKeys == null) {
            return 0;
        }
        for (int i = 0; i < switchKeys.length; i++) {
            if (switchKeys[i] == key) {
                return i;
            }
        }
        return switchKeys.length; // default edge
    }
}
