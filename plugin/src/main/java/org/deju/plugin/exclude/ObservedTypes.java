package org.deju.plugin.exclude;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.intellij.openapi.project.Project;

import org.deju.plugin.contract.CallNode;
import org.deju.plugin.contract.DejuPayload;
import org.deju.plugin.contract.FileCoverage;
import org.deju.plugin.contract.LineCoverage;
import org.deju.plugin.history.DejuHistoryStore;
import org.deju.plugin.history.ExecutionEntry;
import org.deju.plugin.paint.PaintPlan;

/**
 * The classes seen in this project's stored recordings, with enough shape information for
 * the exclusion dialog to make a sensible suggestion and to open any of them in the editor.
 *
 * <p>Candidates come from recordings rather than from a project-wide index scan: the point
 * is to offer the handful of types that are actually cluttering your reports, not every
 * class on the classpath.
 *
 * <p><b>Never call {@link #scan} on the EDT</b>, it parses up to
 * {@link DejuHistoryStore#CAPACITY} payload files from disk.
 */
public final class ObservedTypes {

    /** A class that did not appear in the most recent run, so it has no position in it. */
    public static final int NO_ORDER = 0;

    /** A class whose recording carries no line we can open it at. */
    public static final int NO_LINE = 0;

    /** One class, as observed across the stored runs. */
    public static final class Type {
        public final String fqName;
        public final int invocations;
        /** True when some invocation of this class went on to call something else. */
        public final boolean everCalls;
        /** True when every recorded method on this class looks like an accessor. */
        public final boolean onlyAccessors;
        /**
         * 1-based position in the most recent run's execution order, or {@link #NO_ORDER}.
         *
         * <p>Scoped to one run because execution order is a property of a run, not of a
         * class: the same DTO can be 3rd in one request and 7th in another. The most recent
         * is the one whose editor tabs the user was last looking at, which is what makes the
         * number worth cross-referencing.
         */
        public final int executionOrder;
        /**
         * The payload's own file name, e.g. {@code Order.java}, or {@code null}.
         *
         * <p>Only needed so a user-configured source root can be searched by path; the IDE
         * index does not need it. See {@code SourceResolver}.
         */
        public final String sourceFileName;
        /**
         * The method the recording first entered this class through, or {@code null}.
         *
         * <p>Taken from the call tree rather than from the file's lowest recorded line: the
         * two disagree whenever a helper is declared above the entry point, and the entry
         * point is the one the user is looking for when they open the class from a trace.
         */
        public final String entryMethod;
        /** 1-based line {@link #entryMethod} is declared on, or {@link #NO_LINE}. */
        public final int entryLine;

        Type(String fqName, int invocations, boolean everCalls, boolean onlyAccessors,
             int executionOrder, String sourceFileName, String entryMethod, int entryLine) {
            this.fqName = fqName;
            this.invocations = invocations;
            this.everCalls = everCalls;
            this.onlyAccessors = onlyAccessors;
            this.executionOrder = executionOrder;
            this.sourceFileName = sourceFileName;
            this.entryMethod = entryMethod;
            this.entryLine = entryLine;
        }

        /**
         * Whether "Suggest" should tick this class.
         *
         * <p>Both conditions are required, and the first is the one that matters. A class
         * that drives work (a service, a controller, a mapper) invokes something
         * somewhere in the recording; a DTO or entity is a leaf in the call graph. That
         * distinction needs no naming convention, so a one-line {@code getDiscount()} on a
         * service is never mistaken for boilerplate.
         */
        public boolean suggested() {
            return !everCalls && onlyAccessors;
        }

        /** The name without its package, which is what a list of one package should show. */
        public String simpleName() {
            int lastDot = fqName.lastIndexOf('.');
            return lastDot < 0 ? fqName : fqName.substring(lastDot + 1);
        }
    }

    private ObservedTypes() {
    }

    /** Everything gathered about one class while the stored runs are read. */
    private static final class Accumulator {
        int invocations;
        boolean everCalls;
        boolean sawMethod;
        boolean allAccessors = true;
        String sourceFileName;
        String entryMethod;
        /** Method name to the line it is declared on, in first-seen order. */
        final Map<String, Integer> methodLine = new LinkedHashMap<>();

        void noteLine(String method, int line, boolean isDeclaration) {
            if (line <= 0) {
                return;
            }
            Integer known = methodLine.get(method);
            // A declaration line always wins; otherwise keep the earliest line seen, which
            // is the closest we can get to the declaration without parsing the file.
            if (known == null || isDeclaration || line < known) {
                methodLine.put(method, line);
            }
        }

        int entryLine() {
            Integer line = entryMethod == null ? null : methodLine.get(entryMethod);
            return line == null ? NO_LINE : line;
        }

        /**
         * The entry method, falling back to the first method with a recorded line.
         *
         * <p>A run recorded by an agent too old to send a call tree has coverage and no
         * frames, so there is nothing to call an entry point; opening the class at its first
         * recorded method still beats opening it at line 1.
         */
        String resolvedEntryMethod() {
            if (entryMethod != null) {
                return entryMethod;
            }
            return methodLine.isEmpty() ? null : methodLine.keySet().iterator().next();
        }
    }

    /**
     * Reads every stored run and summarises the classes in them.
     *
     * <p>Ordered the way the most recent run executed, so the dialog reads top-to-bottom like
     * the report and the editor tabs do. Classes that run never touched keep the old
     * busiest-first ranking and sort after it, rather than being scattered through an order
     * they took no part in.
     */
    public static List<Type> scan(Project project) {
        Map<String, Accumulator> byClass = new LinkedHashMap<>();

        DejuHistoryStore store = DejuHistoryStore.getInstance(project);
        for (int slot = 1; slot <= DejuHistoryStore.CAPACITY; slot++) {
            DejuPayload payload = store.load(slot);
            if (payload == null) {
                continue;
            }
            collectCalls(payload, byClass);
            collectFiles(payload, byClass);
        }
        Map<String, Integer> order = executionOrderOfLatestRun(store);

        List<Type> out = new ArrayList<>(byClass.size());
        for (Map.Entry<String, Accumulator> e : byClass.entrySet()) {
            Accumulator acc = e.getValue();
            // No recorded lines at all means nothing contradicts "accessors only", but it is
            // also no evidence for it, treat it as unsuggestable rather than guess.
            String entry = acc.resolvedEntryMethod();
            out.add(new Type(e.getKey(),
                    acc.invocations,
                    acc.everCalls,
                    acc.sawMethod && acc.allAccessors,
                    order.getOrDefault(e.getKey(), NO_ORDER),
                    acc.sourceFileName,
                    entry,
                    entry == null ? NO_LINE : acc.methodLine.getOrDefault(entry, NO_LINE)));
        }
        // Numbered rows first and in order; everything else keeps the busiest-first ranking
        // it had before, behind them.
        out.sort(Comparator.comparingInt((Type t) -> t.executionOrder == NO_ORDER ? 1 : 0)
                .thenComparingInt(t -> t.executionOrder == NO_ORDER ? 0 : t.executionOrder)
                .thenComparingInt(t -> -t.invocations)
                .thenComparing(t -> t.fqName));
        return out;
    }

    /**
     * Maps each class in the most recent run to its 1-based execution position.
     *
     * <p>Empty when there is no stored run, which leaves every row unnumbered and the list
     * ranked busiest-first exactly as it was before.
     */
    private static Map<String, Integer> executionOrderOfLatestRun(DejuHistoryStore store) {
        List<ExecutionEntry> entries = store.entries();
        if (entries.isEmpty()) {
            return Map.of();
        }
        // entries() is most-recent-first.
        DejuPayload latest = store.load(entries.get(0).slot);
        if (latest == null) {
            return Map.of();
        }
        Map<String, Integer> order = new LinkedHashMap<>();
        for (String fq : PaintPlan.classNamesInExecutionOrder(latest)) {
            order.putIfAbsent(fq, order.size() + 1);
        }
        return order;
    }

    private static void collectCalls(DejuPayload payload, Map<String, Accumulator> byClass) {
        List<CallNode> calls = payload.getCalls();
        if (calls == null) {
            return;
        }
        // Which sequence numbers are somebody's parent; that is what "ever calls" means.
        Map<Integer, Boolean> hasChild = new LinkedHashMap<>();
        for (CallNode node : calls) {
            if (node.getParentSeq() >= 0) {
                hasChild.put(node.getParentSeq(), Boolean.TRUE);
            }
        }
        for (CallNode node : calls) {
            String fq = node.getClassName();
            if (fq == null || fq.isEmpty()) {
                continue;
            }
            Accumulator acc = byClass.computeIfAbsent(fq, k -> new Accumulator());
            acc.invocations++;
            if (hasChild.containsKey(node.getSeq())) {
                acc.everCalls = true;
            }
            String method = node.getMethodName();
            if (acc.entryMethod == null && method != null && !method.isEmpty()) {
                acc.entryMethod = method;
            }
        }
    }

    private static void collectFiles(DejuPayload payload, Map<String, Accumulator> byClass) {
        for (FileCoverage file : payload.getFiles()) {
            String fq = file.getFqClassName();
            if (fq == null || fq.isEmpty()) {
                continue;
            }
            Accumulator acc = byClass.computeIfAbsent(fq, k -> new Accumulator());
            if (acc.sourceFileName == null) {
                acc.sourceFileName = file.getSourceFileName();
            }
            for (LineCoverage line : file.getLines()) {
                String method = line.getMethodName();
                if (method == null || method.isEmpty()) {
                    continue;
                }
                acc.sawMethod = true;                 // we have seen at least one method
                if (!TypeExclusionMatcher.isAccessorShaped(method)) {
                    acc.allAccessors = false;
                }
                acc.noteLine(method, line.getLine(), Boolean.TRUE.equals(line.getMethodStart()));
            }
        }
    }

}
