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

    public DecisionModel(int decisionId, int methodGid, int line, Kind kind, int[] switchKeys) {
        this.decisionId = decisionId;
        this.methodGid = methodGid;
        this.line = line;
        this.kind = kind;
        this.switchKeys = switchKeys;
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
