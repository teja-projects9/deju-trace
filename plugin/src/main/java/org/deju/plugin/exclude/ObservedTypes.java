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

/**
 * The classes seen in this project's stored recordings, with enough shape information for
 * the exclusion dialog to make a sensible suggestion.
 *
 * <p>Candidates come from recordings rather than from a project-wide index scan: the point
 * is to offer the handful of types that are actually cluttering your reports, not every
 * class on the classpath.
 *
 * <p><b>Never call {@link #scan} on the EDT</b>, it parses up to
 * {@link DejuHistoryStore#CAPACITY} payload files from disk.
 */
public final class ObservedTypes {

    /** One class, as observed across the stored runs. */
    public static final class Type {
        public final String fqName;
        public final int invocations;
        /** True when some invocation of this class went on to call something else. */
        public final boolean everCalls;
        /** True when every recorded method on this class looks like an accessor. */
        public final boolean onlyAccessors;

        Type(String fqName, int invocations, boolean everCalls, boolean onlyAccessors) {
            this.fqName = fqName;
            this.invocations = invocations;
            this.everCalls = everCalls;
            this.onlyAccessors = onlyAccessors;
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
    }

    private ObservedTypes() {
    }

    /** Reads every stored run and summarises the classes in them, busiest first. */
    public static List<Type> scan(Project project) {
        Map<String, int[]> invocations = new LinkedHashMap<>();   // fq -> {count, everCalls}
        Map<String, boolean[]> accessorOnly = new LinkedHashMap<>(); // fq -> {seenMethod, allAccessors}

        DejuHistoryStore store = DejuHistoryStore.getInstance(project);
        for (int slot = 1; slot <= DejuHistoryStore.CAPACITY; slot++) {
            DejuPayload payload = store.load(slot);
            if (payload == null) {
                continue;
            }
            collectCalls(payload, invocations);
            collectMethods(payload, accessorOnly);
        }

        // Union of both sources: a run recorded by an agent too old to send a call tree has
        // covered files and no calls[], and those classes must still be offered.
        List<String> names = new ArrayList<>(invocations.keySet());
        for (String fq : accessorOnly.keySet()) {
            if (!invocations.containsKey(fq)) {
                names.add(fq);
            }
        }

        List<Type> out = new ArrayList<>();
        for (String fq : names) {
            int[] calls = invocations.get(fq);
            boolean[] acc = accessorOnly.get(fq);
            // No recorded lines at all means nothing contradicts "accessors only", but it is
            // also no evidence for it, treat it as unsuggestable rather than guess.
            boolean onlyAccessors = acc != null && acc[0] && acc[1];
            out.add(new Type(fq,
                    calls == null ? 0 : calls[0],
                    calls != null && calls[1] == 1,
                    onlyAccessors));
        }
        out.sort(Comparator.comparingInt((Type t) -> -t.invocations).thenComparing(t -> t.fqName));
        return out;
    }

    private static void collectCalls(DejuPayload payload, Map<String, int[]> invocations) {
        List<CallNode> calls = payload.getCalls();
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
            int[] cell = invocations.computeIfAbsent(fq, k -> new int[] {0, 0});
            cell[0]++;
            if (hasChild.containsKey(node.getSeq())) {
                cell[1] = 1;
            }
        }
    }

    private static void collectMethods(DejuPayload payload, Map<String, boolean[]> accessorOnly) {
        for (FileCoverage file : payload.getFiles()) {
            String fq = file.getFqClassName();
            if (fq == null || fq.isEmpty()) {
                continue;
            }
            boolean[] cell = accessorOnly.computeIfAbsent(fq, k -> new boolean[] {false, true});
            for (LineCoverage line : file.getLines()) {
                String method = line.getMethodName();
                if (method == null || method.isEmpty()) {
                    continue;
                }
                cell[0] = true;                       // we have seen at least one method
                if (!TypeExclusionMatcher.isAccessorShaped(method)) {
                    cell[1] = false;
                }
            }
        }
    }

}
