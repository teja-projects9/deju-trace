package org.deju.agent.runtime;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.deju.agent.AgentVersion;
import org.deju.agent.contract.CallNode;
import org.deju.agent.contract.DejuPayload;
import org.deju.agent.contract.FileCoverage;
import org.deju.agent.contract.LineCoverage;
import org.deju.agent.contract.LineStatus;
import org.deju.agent.model.ClassModel;
import org.deju.agent.model.DecisionModel;
import org.deju.agent.model.LineProbe;
import org.deju.agent.model.MethodModel;
import org.deju.agent.model.Registry;

/**
 * Turns the raw hit-sets recorded during a {@link Session} into the wire
 * {@link DejuPayload}, computing FULL / PARTIAL / NONE per line from line hits and
 * branch-edge hits. Only methods that were actually entered contribute lines, so a
 * class appears only if some of its code ran.
 */
final class PayloadBuilder {

    private PayloadBuilder() {
    }

    static DejuPayload build(Session s) {
        DejuPayload payload = new DejuPayload();
        payload.setSessionId(s.sessionId);
        payload.setTarget(s.target);
        payload.setStartedAtIso(s.startedAtIso);
        // Lets the plugin detect that a traced JVM is still running an older agent, a
        // -javaagent jar is loaded once at process start and nothing refreshes it in place.
        payload.setAgentVersion(AgentVersion.get());
        payload.setDurationMs(s.durationMs());

        // (methodGid,line) -> number of distinct branch edges taken.
        Map<Long, Integer> coveredByLine = new HashMap<>();
        for (Long edgeKey : s.edgesHit) {
            int decisionId = (int) (edgeKey >> 16);
            DecisionModel d = Registry.decision(decisionId);
            if (d != null) {
                long lineKey = lineKey(d.getMethodGid(), d.getLine());
                coveredByLine.merge(lineKey, 1, Integer::sum);
            }
        }

        // One FileCoverage per class, merging the lines of every entered method.
        Map<String, FileCoverage> filesByClass = new LinkedHashMap<>();
        Map<String, Map<Integer, LineCoverage>> linesByClass = new LinkedHashMap<>();

        for (Integer methodGid : s.methodsEntered) {
            MethodModel m = Registry.method(methodGid);
            if (m == null) {
                continue;
            }
            ClassModel owner = m.getOwner();
            String className = owner.getClassName();
            filesByClass.computeIfAbsent(className,
                    k -> new FileCoverage(className, owner.getSourceFileName()));
            Map<Integer, LineCoverage> lineMap =
                    linesByClass.computeIfAbsent(className, k -> new java.util.TreeMap<>());

            for (Integer line : m.getLines()) {
                int total = m.getBranchTotals().getOrDefault(line, 0);
                Integer probeId = m.lineProbeId(line);
                boolean hit = probeId != null && s.linesHit.contains(probeId);
                int covered = coveredByLine.getOrDefault(lineKey(methodGid, line), 0);
                if (covered > total) {
                    covered = total;
                }

                LineCoverage lc = toLineCoverage(line, total, covered, hit);
                applyMethodIdentity(lc, m, line);
                applyTiming(lc, s, m, line, probeId);
                mergeLine(lineMap, line, lc);
            }
        }

        for (Map.Entry<String, FileCoverage> e : filesByClass.entrySet()) {
            FileCoverage fc = e.getValue();
            Map<Integer, LineCoverage> lineMap = linesByClass.get(e.getKey());
            if (lineMap != null && !lineMap.isEmpty()) {
                fc.getLines().addAll(lineMap.values());
                payload.getFiles().add(fc);
            }
        }

        addCallTree(payload, s);
        return payload;
    }

    /**
     * Copies the session's ordered invocation record into the payload, resolving each
     * method id to its class/method name and each call-site probe to a source line.
     *
     * <p>Nodes are emitted for every recorded index even if the registry lookup fails, so
     * that {@code parentSeq} references stay valid, dropping a node would silently reparent
     * its children to the wrong frame.
     */
    private static void addCallTree(DejuPayload payload, Session s) {
        for (int seq = 0; seq < s.callCount; seq++) {
            CallNode node = new CallNode();
            node.setSeq(seq);
            node.setParentSeq(s.callParent[seq]);

            MethodModel m = Registry.method(s.callMethodGid[seq]);
            if (m != null) {
                node.setClassName(m.getOwner().getClassName());
                node.setMethodName(m.getMethodName());
            }

            int probe = s.callSiteProbe[seq];
            if (probe >= 0) {
                LineProbe site = Registry.lineProbe(probe);
                if (site != null) {
                    node.setCallSiteLine(site.getLine());
                }
            }

            // A query node has no MethodModel, so className/methodName stay null above and
            // the SQL is what identifies it.
            node.setSql(s.callSql[seq]);

            long micros = s.callNanos[seq] / 1000L;
            if (micros > 0) {
                node.setTotalMicros(micros);
            }
            payload.getCalls().add(node);
        }
        payload.setCallsTruncated(s.callsTruncated);
    }

    /**
     * Tags a line with the method that owns it, so the report can group a file's lines
     * into method sections. Deliberately unconditional: {@link #applyTiming} only emits
     * {@code methodTotalMicros} when the method cost at least a microsecond, so timing
     * alone would leave fast methods with no detectable boundary at all.
     *
     * <p>Where a line belongs to two methods of the same class, a lambda body written
     * inline on its enclosing method's line, {@link #mergeLine} keeps whichever record
     * has the stronger status, and the surviving record's own method name goes with it.
     */
    private static void applyMethodIdentity(LineCoverage lc, MethodModel m, int line) {
        lc.setMethodName(m.getMethodName());
        if (!m.getLines().isEmpty() && line == m.getLines().first()) {
            lc.setMethodStart(Boolean.TRUE);
        }
    }

    /**
     * Attaches wall-clock timing (µs) to a line: its own self time, and, on the method's
     * first line, the method's inclusive total and self totals, for the gutter/report.
     * Sub-microsecond values are dropped so only lines that actually cost something show.
     */
    private static void applyTiming(LineCoverage lc, Session s, MethodModel m, int line, Integer probeId) {
        long selfMicros = (probeId == null ? 0L : s.lineNanos.getOrDefault(probeId, 0L)) / 1000L;
        if (selfMicros > 0) {
            lc.setTimeMicros(selfMicros);
        }
        if (!m.getLines().isEmpty() && line == m.getLines().first()) {
            long totalMicros = s.methodNanos.getOrDefault(m.getMethodGid(), 0L) / 1000L;
            long methodSelfNanos = 0L;
            for (Integer ln : m.getLines()) {
                Integer pid = m.lineProbeId(ln);
                if (pid != null) {
                    methodSelfNanos += s.lineNanos.getOrDefault(pid, 0L);
                }
            }
            if (totalMicros > 0) {
                lc.setMethodTotalMicros(totalMicros);
            }
            if (methodSelfNanos / 1000L > 0) {
                lc.setMethodSelfMicros(methodSelfNanos / 1000L);
            }
        }
    }

    private static LineCoverage toLineCoverage(int line, int total, int covered, boolean hit) {
        if (total > 0) {
            LineStatus status;
            if (!hit) {
                status = LineStatus.NONE;
            } else if (covered >= total) {
                status = LineStatus.FULL;
            } else {
                status = LineStatus.PARTIAL;
            }
            return new LineCoverage(line, status, covered, total);
        }
        LineStatus status = hit ? LineStatus.FULL : LineStatus.NONE;
        return new LineCoverage(line, status, null, null);
    }

    /** Keeps the stronger of two records for the same line (FULL > PARTIAL > NONE). */
    private static void mergeLine(Map<Integer, LineCoverage> lineMap, int line, LineCoverage candidate) {
        LineCoverage existing = lineMap.get(line);
        if (existing == null || rank(candidate.getStatus()) > rank(existing.getStatus())) {
            lineMap.put(line, candidate);
        }
    }

    private static int rank(LineStatus status) {
        switch (status) {
            case FULL: return 2;
            case PARTIAL: return 1;
            default: return 0;
        }
    }

    private static long lineKey(int methodGid, int line) {
        return ((long) methodGid << 20) | (line & 0xFFFFFL);
    }
}
