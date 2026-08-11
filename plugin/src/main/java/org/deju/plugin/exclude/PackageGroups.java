package org.deju.plugin.exclude;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rolls the observed classes up into the packages they live in.
 *
 * <p>The other half of the exclusion dialog. Picking classes one at a time is precise but
 * does not scale: a run through a JPA-backed service touches forty entities and their
 * generated builders, and ticking forty rows to say "none of {@code com.acme.domain}"
 * is busywork the tool should be doing. A package is also the unit that stays true as the
 * codebase grows, tomorrow's new entity is excluded before it is written, where a list of
 * forty class names is out of date the moment someone adds the forty-first.
 *
 * <p>Deliberately free of IntelliJ Platform imports so it can be unit-tested as plain Java,
 * for the same reason {@link TypeExclusionMatcher} is.
 */
public final class PackageGroups {

    /** One package, with the classes from the recordings that live in it. */
    public static final class Group {
        /** Dotted package name, never empty; see {@link #of} for the default package. */
        public final String name;
        /** Fully-qualified class names in this package, in the order they were given. */
        public final List<String> classes;
        /** Invocations summed over {@link #classes}. */
        public final int invocations;
        /**
         * The earliest position any of its classes held in the latest run, or
         * {@link ObservedTypes#NO_ORDER} when the run touched none of them.
         *
         * <p>A package is "reached" when the first of its classes is, so the earliest
         * position is the one that places the package in the run, and sorting on it makes
         * the package list read top-to-bottom exactly as the class list does.
         */
        public final int firstOrder;

        Group(String name, List<String> classes, int invocations, int firstOrder) {
            this.name = name;
            this.classes = List.copyOf(classes);
            this.invocations = invocations;
            this.firstOrder = firstOrder;
        }
    }

    private PackageGroups() {
    }

    /**
     * Groups {@code types} by package, ordered the way the latest run reached them.
     *
     * <p>Classes in the default package are left out. There is no name to write a rule
     * against, and {@code *} as a package glob would exclude the entire recording, so the
     * class tab remains the only honest way to hide them.
     */
    public static List<Group> of(List<ObservedTypes.Type> types) {
        Map<String, List<ObservedTypes.Type>> byPackage = new LinkedHashMap<>();
        for (ObservedTypes.Type t : types) {
            String pkg = packageOf(t.fqName);
            if (pkg.isEmpty()) {
                continue;
            }
            byPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(t);
        }

        List<Group> out = new ArrayList<>(byPackage.size());
        for (Map.Entry<String, List<ObservedTypes.Type>> e : byPackage.entrySet()) {
            List<String> names = new ArrayList<>(e.getValue().size());
            int invocations = 0;
            int firstOrder = ObservedTypes.NO_ORDER;
            for (ObservedTypes.Type t : e.getValue()) {
                names.add(t.fqName);
                invocations += t.invocations;
                if (t.executionOrder != ObservedTypes.NO_ORDER
                        && (firstOrder == ObservedTypes.NO_ORDER || t.executionOrder < firstOrder)) {
                    firstOrder = t.executionOrder;
                }
            }
            out.add(new Group(e.getKey(), names, invocations, firstOrder));
        }

        // Same ranking as the class list: packages the latest run reached, in the order it
        // reached them, then everything else busiest-first.
        out.sort(Comparator.comparingInt((Group g) -> g.firstOrder == ObservedTypes.NO_ORDER ? 1 : 0)
                .thenComparingInt(g -> g.firstOrder == ObservedTypes.NO_ORDER ? 0 : g.firstOrder)
                .thenComparingInt(g -> -g.invocations)
                .thenComparing(g -> g.name));
        return out;
    }

    /**
     * The package a fully-qualified class name lives in, or {@code ""} for the default one.
     *
     * <p>The nested-class portion is cut off first: the agent sends binary names, so
     * {@code com.acme.Order$Builder} would otherwise be read as living in
     * {@code com.acme.Order$Builder}'s parent only by luck, and an inner class named with a
     * dot-free segment would move the boundary. Nested classes belong to their outer class's
     * package, always.
     */
    public static String packageOf(String fqName) {
        if (fqName == null) {
            return "";
        }
        int dollar = fqName.indexOf('$');
        String outer = dollar >= 0 ? fqName.substring(0, dollar) : fqName;
        int lastDot = outer.lastIndexOf('.');
        return lastDot <= 0 ? "" : outer.substring(0, lastDot);
    }

    /**
     * The glob that excludes a package.
     *
     * <p><b>Sub-packages are included.</b> {@code *} spans dots (see
     * {@link TypeExclusionMatcher}), so {@code com.acme.dto.*} covers
     * {@code com.acme.dto.internal.Foo} as well. That is the behaviour people expect from
     * "exclude this package" and it is what makes ticking one parent stand in for ticking
     * the eight leaves under it; the dialog says so next to the list.
     */
    public static String globFor(String packageName) {
        return packageName + ".*";
    }
}
