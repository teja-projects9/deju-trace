package org.deju.plugin.history;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.deju.plugin.contract.CallNode;
import org.deju.plugin.contract.DejuPayload;
import org.deju.plugin.contract.FileCoverage;
import org.deju.plugin.contract.LineCoverage;
import org.deju.plugin.contract.LineStatus;

/**
 * Compares two recorded runs, typically "before" and "after" a change: the headline
 * numbers, the methods whose self time moved the most, and the lines whose coverage
 * status flipped. Output is plain Markdown, so it doubles as something to paste
 * somewhere, the same reasoning as {@link MarkdownSummary}.
 *
 * <p>Matches methods and lines by {@code class#method}/{@code class:line} identity, not
 * by call sequence, so the two runs being different sizes (a call added or removed) does
 * not misalign anything — a method present in only one run just shows as absent in the
 * other rather than lining up against something unrelated.
 */
public final class RunDiff {

    private RunDiff() {
    }

    private static final int MAX_TIMING_ROWS = 15;
    private static final int MAX_COVERAGE_ROWS = 30;
    /** Self-time swings smaller than this are noise between two runs of the same code. */
    private static final long MIN_TIMING_DELTA_MICROS = 200;

    public static String of(ExecutionEntry entryA, DejuPayload payloadA,
                             ExecutionEntry entryB, DejuPayload payloadB) {
        String labelA = label(entryA, payloadA);
        String labelB = label(entryB, payloadB);

        StringBuilder md = new StringBuilder();
        md.append("## Diff: ").append(labelA).append(" → ").append(labelB).append("\n\n");
        md.append(headline(payloadA, payloadB)).append('\n');

        List<String> timingRows = timingDiff(payloadA, payloadB);
        if (!timingRows.isEmpty()) {
            md.append("\n**Biggest self-time changes**\n\n");
            md.append("| Method | A | B | Δ |\n|---|---|---|---|\n");
            for (String row : timingRows) {
                md.append(row).append('\n');
            }
        }

        List<String> coverageRows = coverageDiff(payloadA, payloadB);
        if (!coverageRows.isEmpty()) {
            md.append("\n**Coverage changes**\n\n");
            for (String row : coverageRows) {
                md.append("- ").append(row).append('\n');
            }
        }

        if (timingRows.isEmpty() && coverageRows.isEmpty()) {
            md.append("\nNo method timing or coverage differences worth reporting between"
                    + " these two runs.\n");
        }
        return md.toString();
    }

    private static String label(ExecutionEntry entry, DejuPayload payload) {
        if (entry.label != null && !entry.label.isEmpty()) {
            return entry.label;
        }
        return payload.getTarget() != null ? payload.getTarget() : "run " + entry.slot;
    }

    private static String headline(DejuPayload a, DejuPayload b) {
        long ma = a.getDurationMs();
        long mb = b.getDurationMs();
        StringBuilder s = new StringBuilder();
        s.append("Wall: ").append(fmtMs(ma)).append(" → ").append(fmtMs(mb));
        s.append(deltaSuffix(mb - ma, ma));
        s.append("  \nCalls: ").append(a.getCalls().size()).append(" → ").append(b.getCalls().size());
        s.append("  \nQueries: ").append(queryCount(a)).append(" → ").append(queryCount(b));
        long cpuA = a.getCpuMicros();
        long cpuB = b.getCpuMicros();
        if (cpuA >= 0 && cpuB >= 0) {
            s.append("  \nCPU: ").append(fmtMs(cpuA / 1000)).append(" → ").append(fmtMs(cpuB / 1000));
        }
        return s.toString();
    }

    private static String deltaSuffix(long deltaMs, long baseMs) {
        if (deltaMs == 0) {
            return " (no change)";
        }
        String sign = deltaMs > 0 ? "+" : "-";
        String pct = baseMs > 0
                ? " (" + sign + Math.round(Math.abs(deltaMs) * 100.0 / baseMs) + "%)" : "";
        return " (" + sign + fmtMs(Math.abs(deltaMs)) + pct + ")";
    }

    private static int queryCount(DejuPayload p) {
        int n = 0;
        for (CallNode c : p.getCalls()) {
            if (c.getSql() != null) {
                n++;
            }
        }
        return n;
    }

    /** {@code class#method -> self micros}, from whichever line in a file carries it. */
    private static Map<String, Long> selfTimesByMethod(DejuPayload p) {
        Map<String, Long> out = new HashMap<>();
        for (FileCoverage f : p.getFiles()) {
            for (LineCoverage l : f.getLines()) {
                if (l.getMethodSelfMicros() != null && l.getMethodName() != null) {
                    out.put(f.getFqClassName() + "#" + l.getMethodName(), l.getMethodSelfMicros());
                }
            }
        }
        return out;
    }

    private static List<String> timingDiff(DejuPayload a, DejuPayload b) {
        Map<String, Long> selfA = selfTimesByMethod(a);
        Map<String, Long> selfB = selfTimesByMethod(b);
        Set<String> keys = new LinkedHashSet<>(selfA.keySet());
        keys.addAll(selfB.keySet());

        List<String[]> rows = new ArrayList<>(); // {key, formattedRow, absDeltaAsString}
        for (String key : keys) {
            long va = selfA.getOrDefault(key, 0L);
            long vb = selfB.getOrDefault(key, 0L);
            long delta = vb - va;
            if (Math.abs(delta) < MIN_TIMING_DELTA_MICROS) {
                continue;
            }
            String method = key.substring(key.lastIndexOf('#') + 1) + "()";
            String cls = simpleName(key.substring(0, key.lastIndexOf('#')));
            String sign = delta > 0 ? "+" : "";
            rows.add(new String[] {
                    String.valueOf(Math.abs(delta)),
                    "| " + cls + "." + method + " | " + fmtMs(va / 1000) + " | " + fmtMs(vb / 1000)
                            + " | " + sign + fmtMs(delta / 1000) + " |"
            });
        }
        rows.sort(Comparator.comparingLong((String[] r) -> Long.parseLong(r[0])).reversed());

        List<String> out = new ArrayList<>();
        for (int i = 0; i < rows.size() && i < MAX_TIMING_ROWS; i++) {
            out.add(rows.get(i)[1]);
        }
        if (rows.size() > MAX_TIMING_ROWS) {
            out.add("| … " + (rows.size() - MAX_TIMING_ROWS) + " more method(s) changed | | | |");
        }
        return out;
    }

    /** {@code class:line -> status}. */
    private static Map<String, LineStatus> statusByLine(DejuPayload p) {
        Map<String, LineStatus> out = new HashMap<>();
        for (FileCoverage f : p.getFiles()) {
            for (LineCoverage l : f.getLines()) {
                if (l.getStatus() != null) {
                    out.put(f.getFqClassName() + ":" + l.getLine(), l.getStatus());
                }
            }
        }
        return out;
    }

    private static List<String> coverageDiff(DejuPayload a, DejuPayload b) {
        Map<String, LineStatus> byA = statusByLine(a);
        Map<String, LineStatus> byB = statusByLine(b);
        Set<String> keys = new LinkedHashSet<>(byA.keySet());
        keys.addAll(byB.keySet());

        List<String> changed = new ArrayList<>();
        for (String key : keys) {
            LineStatus sa = byA.get(key);
            LineStatus sb = byB.get(key);
            if (sa == sb) {
                continue;
            }
            String cls = simpleName(key.substring(0, key.lastIndexOf(':')));
            String line = key.substring(key.lastIndexOf(':') + 1);
            changed.add(cls + ":" + line + "  " + (sa == null ? "absent" : sa)
                    + " → " + (sb == null ? "absent" : sb));
        }
        changed.sort(String::compareTo);

        List<String> out = new ArrayList<>();
        for (int i = 0; i < changed.size() && i < MAX_COVERAGE_ROWS; i++) {
            out.add(changed.get(i));
        }
        if (changed.size() > MAX_COVERAGE_ROWS) {
            out.add("… " + (changed.size() - MAX_COVERAGE_ROWS) + " more line(s) changed status");
        }
        return out;
    }

    private static String simpleName(String fqcn) {
        int i = fqcn.lastIndexOf('.');
        return i >= 0 ? fqcn.substring(i + 1) : fqcn;
    }

    private static String fmtMs(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        }
        double s = ms / 1000.0;
        String num = s == Math.floor(s)
                ? String.valueOf((long) s) : String.format(Locale.ROOT, "%.1f", s);
        return num + "s";
    }
}
