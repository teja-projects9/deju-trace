package org.deju.agent.model;

/** Resolves a line-probe id back to the owning method and its source line. */
public final class LineProbe {

    private final int methodGid;
    private final int line;

    public LineProbe(int methodGid, int line) {
        this.methodGid = methodGid;
        this.line = line;
    }

    public int getMethodGid() {
        return methodGid;
    }

    public int getLine() {
        return line;
    }
}
