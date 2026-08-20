package org.deju.plugin.history;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.deju.plugin.contract.CallNode;
import org.deju.plugin.contract.DejuPayload;
import org.deju.plugin.contract.FileCoverage;
import org.deju.plugin.contract.LineCoverage;
import org.deju.plugin.contract.LineStatus;

/**
 * A plain-Markdown summary of one recorded run, for pasting into a PR description, a
 * Slack thread, or an issue comment — anywhere the full HTML report doesn't render but
 * Markdown does.
 *
 * <p>Deliberately narrower than the report's own Findings tab: that engine reasons over
 * report.js's filtered, grouped, in-memory view of the call tree, none of which the
 * stored payload alone reconstructs. This sticks to what a plain pass over the payload
 * can say honestly — duration, a CPU split, a duplicate-query check, and a coverage
 * count — rather than re-deriving (and risking disagreeing with) the browser-side logic.
 */
public final class MarkdownSummary {

    private MarkdownSummary() {
    }

    /** Fewer identical queries than this from the same caller isn't worth flagging. */
    private static final int MIN_DUP_QUERIES = 3;

    public static String of(ExecutionEntry entry, DejuPayload payload) {
        String title = entry.label != null && !entry.label.isEmpty()
                ? entry.label : nullToQuestion(payload.getTarget());

        StringBuilder md = new StringBuilder();
        md.append("## Deju Trace: ").append(title).append('\n');
        if (entry.label != null && !entry.label.isEmpty() && payload.getTarget() != null) {
            md.append('(').append(payload.getTarget()).append(")\n");
        }
        md.append('\n');
        md.append(statsLine(payload)).append('\n');
        if (payload.isCallsTruncated()) {
            md.append("> Recording was capped: later invocations were not recorded, so the"
                    + " counts above are a lower bound.\n");
        }
        md.append('\n');

        List<String> findings = new ArrayList<>();
        findings.addAll(duplicateQueryFindings(payload.getCalls()));
        int[] branchCounts = branchCounts(payload.getFiles());
        if (branchCounts[1] > 0) {
            findings.add(branchCounts[1] + " line" + (branchCounts[1] == 1 ? "" : "s")
                    + " never executed");
        }
        if (branchCounts[0] > 0) {
            findings.add(branchCounts[0] + " branch" + (branchCounts[0] == 1 ? "" : "es")
                    + " only went one way");
        }
        if (!findings.isEmpty()) {
            md.append("**Findings**\n");
            for (String f : findings) {
                md.append("- ").append(f).append('\n');
            }
            md.append('\n');
        }

        String when = new SimpleDateFormat("dd/MM/yy HH:mm:ss", Locale.ROOT)
                .format(new Date(entry.savedAtMillis));
        md.append("_Recorded ").append(when).append("_\n");
        return md.toString();
    }

    private static String statsLine(DejuPayload payload) {
        long durationMs = payload.getDurationMs();
        long cpuMicros = payload.getCpuMicros();
        List<CallNode> calls = payload.getCalls();
        int queryCount = 0;
        for (CallNode c : calls) {
            if (c.getSql() != null) {
                queryCount++;
            }
        }
        StringBuilder s = new StringBuilder();
        s.append(fmtMs(durationMs)).append(" wall");
        if (cpuMicros >= 0) {
            long cpuMs = cpuMicros / 1000;
            s.append(" · ").append(fmtMs(cpuMs)).append(" CPU");
            if (durationMs > 0) {
                long pct = Math.round(Math.min(100.0, cpuMs * 100.0 / durationMs));
                s.append(" (").append(pct).append("%)");
            }
        }
        s.append(" · ").append(calls.size()).append(calls.size() == 1 ? " call" : " calls");
        s.append(" · ").append(queryCount).append(queryCount == 1 ? " query" : " queries");
        s.append(" · ").append(payload.getFiles().size()).append(" files");
        return s.toString();
    }

    /**
     * Same statement text from the same immediate caller, three times or more: the
     * signature of an N+1 (a loop issuing one query per row instead of one for the set).
     * Only the single worst offender is reported, the way the report's own per-file Graph
     * view leads with its worst repeat rather than listing every one found.
     */
    private static List<String> duplicateQueryFindings(List<CallNode> calls) {
        Map<Integer, CallNode> bySeq = new HashMap<>();
        for (CallNode c : calls) {
            bySeq.put(c.getSeq(), c);
        }

        Map<String, Integer> counts = new HashMap<>();
        Map<String, Long> totalMicros = new HashMap<>();
        Map<String, String> callerLabel = new HashMap<>();
        for (CallNode c : calls) {
            if (c.getSql() == null) {
                continue;
            }
            CallNode parent = bySeq.get(c.getParentSeq());
            String caller = parent != null && parent.getClassName() != null
                    ? parent.getClassName() + "." + parent.getMethodName() + "()" : "an unknown caller";
            String norm = c.getSql().replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            String key = caller + "|" + norm;
            counts.merge(key, 1, Integer::sum);
            totalMicros.merge(key, c.getTotalMicros() == null ? 0L : c.getTotalMicros(), Long::sum);
            callerLabel.putIfAbsent(key, caller);
        }

        String worstKey = null;
        int worstN = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > worstN) {
                worstN = e.getValue();
                worstKey = e.getKey();
            }
        }
        List<String> out = new ArrayList<>();
        if (worstKey != null && worstN >= MIN_DUP_QUERIES) {
            out.add("Possible N+1: " + worstN + " identical queries from " + callerLabel.get(worstKey)
                    + " costing " + fmtMs(totalMicros.get(worstKey) / 1000) + " in total");
        }
        return out;
    }

    /** {@code [partialBranchLines, neverExecutedLines]} across every file in the payload. */
    private static int[] branchCounts(List<FileCoverage> files) {
        int partial = 0;
        int none = 0;
        for (FileCoverage f : files) {
            for (LineCoverage l : f.getLines()) {
                if (l.getStatus() == LineStatus.PARTIAL) {
                    partial++;
                } else if (l.getStatus() == LineStatus.NONE) {
                    none++;
                }
            }
        }
        return new int[] { partial, none };
    }

    private static String nullToQuestion(String s) {
        return s == null ? "?" : s;
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
