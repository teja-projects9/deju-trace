package org.deju.agent.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Static metadata about one instrumented method. Records every instrumentable
 * source line and, per line, the total number of branch edges and which decisions
 * live on that line. This is what lets the agent mark entered-but-unhit lines RED
 * and compute PARTIAL vs FULL for decision lines.
 *
 * <p>Populated during instrumentation (single-threaded per method visit), then
 * only read afterwards.
 */
public final class MethodModel {

    private final int methodGid;
    private final ClassModel owner;
    private final String methodName;
    private final String descriptor;

    /** All instrumentable source lines in this method. */
    private final TreeSet<Integer> lines = new TreeSet<>();
    /** line -> the single line-probe id allocated for that line. */
    private final Map<Integer, Integer> lineProbeIds = new TreeMap<>();
    /** line -> total branch edges on that line (2 per boolean decision, cases+1 per switch). */
    private final Map<Integer, Integer> branchTotals = new TreeMap<>();
    /** line -> decision ids located on that line. */
    private final Map<Integer, List<Integer>> lineDecisions = new TreeMap<>();

    public MethodModel(int methodGid, ClassModel owner, String methodName, String descriptor) {
        this.methodGid = methodGid;
        this.owner = owner;
        this.methodName = methodName;
        this.descriptor = descriptor;
    }

    public int getMethodGid() {
        return methodGid;
    }

    public ClassModel getOwner() {
        return owner;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getDescriptor() {
        return descriptor;
    }

    /** Fully-qualified target identity used to match the armed deju point (class#method). */
    public String fqName() {
        return owner.getClassName() + "#" + methodName;
    }

    public TreeSet<Integer> getLines() {
        return lines;
    }

    /** Records (once) the line-probe id for {@code line}; returns the id in use. */
    public int registerLine(int line, int lineProbeId) {
        lines.add(line);
        Integer existing = lineProbeIds.get(line);
        if (existing != null) {
            return existing;
        }
        lineProbeIds.put(line, lineProbeId);
        return lineProbeId;
    }

    public Integer lineProbeId(int line) {
        return lineProbeIds.get(line);
    }

    public Map<Integer, Integer> getBranchTotals() {
        return branchTotals;
    }

    public Map<Integer, List<Integer>> getLineDecisions() {
        return lineDecisions;
    }

    /** Register a decision (with {@code edgeCount} edges) sitting on {@code line}. */
    public void addDecision(int line, int decisionId, int edgeCount) {
        lines.add(line);
        branchTotals.merge(line, edgeCount, Integer::sum);
        lineDecisions.computeIfAbsent(line, k -> new ArrayList<>()).add(decisionId);
    }
}
