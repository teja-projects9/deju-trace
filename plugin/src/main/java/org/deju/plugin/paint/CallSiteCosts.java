package org.deju.plugin.paint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.deju.plugin.contract.CallNode;
import org.deju.plugin.contract.DejuPayload;

/**
 * What each line of source <b>handed off</b>, summed over the run: the inclusive cost of
 * every call and query made from that line.
 *
 * <p><b>Why this exists.</b> A line's own time stops the moment it calls something and does
 * not resume until the callee returns, which is the right measurement — it is what makes
 * "this line is slow" mean the line and not its callees. But it also means the line that
 * costs the request half a second reads as a rounding error in the gutter, and the half
 * second only becomes visible after opening the callee and reading a number there. This is
 * the same time, charged back to the line the developer would actually go and change.
 *
 * <p><b>Not added to self time.</b> They are different measurements and one of them is not
 * even disjoint from the other: a query is timed while its issuing line is still running, so
 * a query's cost is already inside that line's self time, while a method call's cost is
 * explicitly outside it. Summing the two would double-count every line that issues SQL, so
 * the gutter shows them as two figures and says which is which.
 *
 * <p>Deliberately free of IntelliJ Platform imports so it can be unit-tested as plain Java.
 */
public final class CallSiteCosts {

    /** How many distinct targets a tooltip names before it stops listing them. */
    private static final int MAX_NAMED_TARGETS = 3;

    /** What one line of one class handed off. */
    public static final class Site {
        /** Inclusive microseconds of everything called from this line, across the whole run. */
        public final long micros;
        /** How many invocations that was. */
        public final int calls;
        /** Target label -> how many times it was called, in first-seen order. */
        public final Map<String, Integer> targets;

        Site(long micros, int calls, Map<String, Integer> targets) {
            this.micros = micros;
            this.calls = calls;
            this.targets = Collections.unmodifiableMap(targets);
        }

        /**
         * The targets, most-called first, as {@code "CustomerRepo.findAll ×3"}, capped at
         * {@link #MAX_NAMED_TARGETS} with a count of the remainder.
         *
         * <p>Capped because a line inside a loop that dispatched through an interface can
         * reach a dozen implementations, and a tooltip that lists all of them is one the
         * reader closes without reading.
         */
        public String describeTargets() {
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(targets.entrySet());
            entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            StringBuilder sb = new StringBuilder();
            int shown = Math.min(MAX_NAMED_TARGETS, entries.size());
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(entries.get(i).getKey());
                int n = entries.get(i).getValue();
                if (n > 1) {
                    sb.append(" ×").append(n);
                }
            }
            int rest = entries.size() - shown;
            if (rest > 0) {
                sb.append(" and ").append(rest).append(" other").append(rest == 1 ? "" : "s");
            }
            return sb.toString();
        }
    }

    /** fully-qualified class name -> 1-based source line -> what that line handed off. */
    private final Map<String, Map<Integer, Site>> byClass;

    private CallSiteCosts(Map<String, Map<Integer, Site>> byClass) {
        this.byClass = byClass;
    }

    /** What {@code line} of {@code fqClassName} handed off, or {@code null} if nothing. */
    public Site at(String fqClassName, int line) {
        Map<Integer, Site> lines = byClass.get(fqClassName);
        return lines == null ? null : lines.get(line);
    }

    /** True when the payload carried no call tree to charge anything back from. */
    public boolean isEmpty() {
        return byClass.isEmpty();
    }

    /**
     * Builds the map for one payload.
     *
     * <p>A call counts against its <b>parent's</b> class and the parent's line that made it,
     * which is why this needs the tree and not just the coverage: {@code callSiteLine} is
     * recorded on the callee, not on the caller.
     *
     * <p>Repeated invocations from one line are added together rather than kept apart, to
     * match how every other per-line figure in the editor reads — a loop body's line shows
     * what the loop cost, not what one iteration cost.
     */
    public static CallSiteCosts of(DejuPayload payload) {
        List<CallNode> calls = payload == null ? null : payload.getCalls();
        if (calls == null || calls.isEmpty()) {
            return new CallSiteCosts(Map.of());
        }

        Map<Integer, CallNode> bySeq = new HashMap<>();
        for (CallNode node : calls) {
            bySeq.put(node.getSeq(), node);
        }

        // Accumulated in mutable holders and frozen at the end: a Site is immutable, and
        // rebuilding one per call would allocate once per invocation in the run.
        Map<String, Map<Integer, long[]>> totals = new HashMap<>();
        Map<String, Map<Integer, Map<String, Integer>>> targets = new HashMap<>();

        for (CallNode node : calls) {
            Integer line = node.getCallSiteLine();
            if (line == null || line <= 0) {
                continue;   // the trace root, or a frame entered with no line probe open
            }
            CallNode parent = bySeq.get(node.getParentSeq());
            if (parent == null || parent.getClassName() == null) {
                continue;   // caller outside the recording; nothing to charge it to
            }
            long micros = node.getTotalMicros() == null ? 0L : node.getTotalMicros();
            long[] cell = totals.computeIfAbsent(parent.getClassName(), k -> new HashMap<>())
                    .computeIfAbsent(line, k -> new long[2]);
            cell[0] += micros;
            cell[1]++;
            Map<String, Integer> names =
                    targets.computeIfAbsent(parent.getClassName(), k -> new HashMap<>())
                            .computeIfAbsent(line, k -> new LinkedHashMap<>());
            names.merge(label(node), 1, Integer::sum);
        }

        Map<String, Map<Integer, Site>> out = new HashMap<>();
        totals.forEach((className, lines) -> {
            Map<Integer, Site> sites = new HashMap<>();
            lines.forEach((line, cell) -> sites.put(line,
                    new Site(cell[0], (int) cell[1], targets.get(className).get(line))));
            out.put(className, sites);
        });
        return new CallSiteCosts(out);
    }

    /**
     * What to call a target in a tooltip: {@code Class.method} for a call, {@code query} for
     * SQL.
     *
     * <p>The simple class name, not the fully-qualified one. This ends up in a tooltip beside
     * a time and a percentage, where a package prefix repeated three times is the part that
     * pushes the useful half of the line out of view.
     */
    private static String label(CallNode node) {
        if (node.getClassName() == null) {
            return "query";
        }
        String simple = node.getClassName();
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) {
            simple = simple.substring(dot + 1);
        }
        return node.getMethodName() == null ? simple : simple + "." + node.getMethodName();
    }
}
