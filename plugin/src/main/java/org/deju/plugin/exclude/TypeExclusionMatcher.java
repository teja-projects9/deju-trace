package org.deju.plugin.exclude;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether a traced class is "data" the report should fold away by default.
 *
 * <p>Deliberately free of IntelliJ Platform imports so it can be unit-tested as plain
 * Java. {@link DejuExclusions} owns the persisted state and delegates every decision here.
 *
 * <p><b>Patterns are globs over the fully-qualified class name, matched
 * case-insensitively</b>, {@code *dto} is meant to catch {@code ProductDto},
 * {@code OrderDTO} and {@code orderdto} alike, because no codebase is consistent about
 * the acronym. {@code *} stands for any run of characters including dots, so
 * {@code *.dto.*} selects a whole package while {@code *dto} selects a name suffix.
 *
 * <p><b>An explicit decision about one class always beats a pattern.</b> Patterns are
 * broad by design and will sometimes catch something real, {@code *data} matches
 * {@code DataMigrationService}. Without a per-class override the only remedy would be
 * deleting the pattern that is otherwise doing its job, so {@code keptClasses} exists to
 * win over any pattern.
 *
 * <p><b>Nested classes inherit their outer class's verdict</b> unless a rule names them
 * directly, so excluding {@code ApiResponse} also excludes the builder Lombok generated
 * inside it. The innermost rule that has an opinion wins.
 */
public final class TypeExclusionMatcher {

    /**
     * Generic patterns offered in the UI, in display order.
     *
     * <p>Both a name half and a package half, because neither alone is enough. Name
     * suffixes miss the commonest JPA convention outright, an entity is usually
     * {@code Product}, not {@code ProductEntity}, while package globs miss a
     * {@code ProductDto} that someone parked next to the service that returns it.
     */
    public static final List<String> GENERIC_PATTERNS = List.of(
            // by class name
            "*dto", "*data", "*entity", "*vo",
            "*pojo", "*request", "*response",
            "*model", "*bean", "*record", "*form",
            // by package
            "*.dto.*", "*.entity.*", "*.vo.*", "*.pojo.*",
            "*.model.*", "*.domain.*");

    /**
     * The subset enabled on a fresh project.
     *
     * <p>Everything left off shares one trait: it lands on classes that carry real logic
     * often enough that hiding them by default would cost more trust than the noise
     * reduction is worth. A pricing {@code *Model}, a Spring {@code *Bean} configuration,
     * and, in a domain-driven codebase, most of {@code *.domain.*}, which is where the
     * business rules live rather than the data.
     */
    public static final List<String> DEFAULT_GENERIC_PATTERNS = List.of(
            "*dto", "*data", "*entity", "*vo",
            "*pojo", "*request", "*response",
            "*.dto.*", "*.entity.*", "*.vo.*", "*.pojo.*");

    /** One configured glob and the regex it compiles to, kept together. */
    private static final class Rule {
        final String glob;
        final Pattern regex;

        Rule(String glob, Pattern regex) {
            this.glob = glob;
            this.regex = regex;
        }
    }

    private final List<Rule> rules;
    private final Set<String> excludedClasses;
    private final Set<String> keptClasses;

    public TypeExclusionMatcher(Collection<String> globs,
                                Collection<String> excludedClasses,
                                Collection<String> keptClasses) {
        List<Rule> compiled = new ArrayList<>();
        for (String glob : globs) {
            Pattern p = compile(glob);
            if (p != null) {
                // The original glob is retained, not just the regex: the UI reports which
                // rule hid a class, and "*entity" is what the user typed and recognises, while
                // ".*\Qentity\E" would be gibberish to them.
                compiled.add(new Rule(glob.trim(), p));
            }
        }
        this.rules = List.copyOf(compiled);
        this.excludedClasses = lowered(excludedClasses);
        this.keptClasses = lowered(keptClasses);
    }

    /** True when {@code fqcn} should be folded away in the report's default view. */
    public boolean isExcluded(String fqcn) {
        for (String name = fqcn; name != null; name = outerOf(name)) {
            Boolean decision = decide(name);
            if (decision != null) {
                return decision;
            }
        }
        return false;
    }

    /**
     * The verdict for one class name alone, or {@code null} when no rule has an opinion.
     *
     * <p>Nesting is handled by the caller, which walks outwards, so this stays a decision
     * about exactly the name it is given.
     */
    private Boolean decide(String fqcn) {
        if (fqcn == null || fqcn.isEmpty()) {
            return null;
        }
        String key = fqcn.toLowerCase(Locale.ROOT);
        if (keptClasses.contains(key)) {
            return Boolean.FALSE;   // an explicit keep outranks every pattern
        }
        if (excludedClasses.contains(key)) {
            return Boolean.TRUE;
        }
        for (Rule r : rules) {
            if (r.regex.matcher(fqcn).matches()) {
                return Boolean.TRUE;
            }
        }
        return null;
    }

    /**
     * The enclosing class of a nested type, or {@code null} for a top-level one.
     *
     * <p>The agent reports nested classes with the JVM's binary name, so Lombok's builder
     * for {@code ApiResponse} arrives as {@code com.example.ApiResponse$ApiResponseBuilder}.
     * That name ends in "Builder", so {@code *response} folds the outer class and leaves
     * its builder, and every {@code .field(…)} step on it, sitting in the report. Nested
     * types are part of the file the user excluded, so they follow it unless they have a
     * rule of their own.
     */
    private static String outerOf(String fqcn) {
        if (fqcn == null) {
            return null;
        }
        int dollar = fqcn.lastIndexOf('$');
        // dollar == 0 would mean the whole name is nested inside nothing.
        return dollar <= 0 ? null : fqcn.substring(0, dollar);
    }

    /**
     * The glob that caused {@code fqcn} to be excluded, or {@code null} when no pattern did.
     *
     * <p>Drives the "ⓘ *entity" hint in the popup: a class the user never ticked but that
     * vanished anyway is confusing unless the report can name the rule responsible. It
     * walks outwards like {@link #isExcluded} does, so a nested class folded because of its
     * outer class names the outer class's pattern rather than reporting nothing.
     */
    public String matchingPattern(String fqcn) {
        for (String name = fqcn; name != null; name = outerOf(name)) {
            if (name.isEmpty() || keptClasses.contains(name.toLowerCase(Locale.ROOT))) {
                return null;
            }
            for (Rule r : rules) {
                if (r.regex.matcher(name).matches()) {
                    return r.glob;
                }
            }
        }
        return null;
    }

    /** Subset of {@code candidates} that is excluded, preserving the input order. */
    public List<String> filterExcluded(Collection<String> candidates) {
        List<String> hit = new ArrayList<>();
        for (String c : candidates) {
            if (isExcluded(c)) {
                hit.add(c);
            }
        }
        return hit;
    }

    /**
     * Whether a method name has the shape of generated boilerplate.
     *
     * <p>Requires an upper-case letter or underscore after the prefix, so {@code getaway()}
     * and {@code sets()} are not mistaken for accessors.
     */
    public static boolean isAccessorShaped(String method) {
        if (method == null || method.isEmpty()) {
            return false;
        }
        switch (method) {
            case "toString":
            case "hashCode":
            case "equals":
            case "builder":
            case "<init>":
                return true;
            default:
                break;
        }
        int prefix;
        if (method.startsWith("get") || method.startsWith("set") || method.startsWith("has")) {
            prefix = 3;
        } else if (method.startsWith("is")) {
            prefix = 2;
        } else {
            return false;
        }
        if (method.length() <= prefix) {
            return false;
        }
        char next = method.charAt(prefix);
        return Character.isUpperCase(next) || next == '_';
    }

    /** The two explicit lists derived from what the user ticked. */
    public static final class Decisions {
        public final List<String> excluded;
        public final List<String> kept;

        Decisions(List<String> excluded, List<String> kept) {
            this.excluded = excluded;
            this.kept = kept;
        }
    }

    /**
     * Reduces a full tick map to only the decisions that disagree with the patterns.
     *
     * <p>Storing agreements too would freeze today's pattern results into the settings: add
     * {@code *entity} later and classes already recorded as "not excluded" would fight it.
     * So a ticked class no pattern covers becomes an explicit exclusion, an unticked class a
     * pattern does cover becomes an explicit keep, and everything else is left to the rules.
     */
    public static Decisions decisions(java.util.Map<String, Boolean> ticked,
                                      TypeExclusionMatcher patternsOnly) {
        List<String> excluded = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (java.util.Map.Entry<String, Boolean> e : ticked.entrySet()) {
            boolean on = Boolean.TRUE.equals(e.getValue());
            boolean byPattern = patternsOnly.isExcluded(e.getKey());
            if (on && !byPattern) {
                excluded.add(e.getKey());
            } else if (!on && byPattern) {
                kept.add(e.getKey());
            }
        }
        return new Decisions(excluded, kept);
    }

    /**
     * Splits the free-text pattern box into individual globs.
     *
     * <p>Newline-separated is what the UI advertises, but commas and semicolons are
     * accepted too, a pasted list is far likelier than a class name containing one.
     */
    public static List<String> parsePatternText(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String raw : text.split("[\\n,;]")) {
            String glob = raw.trim();
            if (!glob.isEmpty()) {
                out.add(glob);
            }
        }
        return out;
    }

    /**
     * Compiles one glob to a case-insensitive anchored regex, or {@code null} if blank.
     *
     * <p>Every character outside {@code *} and {@code ?} is quoted, so a pattern is never
     * able to smuggle regex syntax in, {@code com.example.*} must match a literal dot, not
     * any character, or it would also select {@code comXexample}.
     */
    private static Pattern compile(String glob) {
        if (glob == null || glob.trim().isEmpty()) {
            return null;
        }
        String g = glob.trim();
        StringBuilder re = new StringBuilder(g.length() + 8);
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*' || c == '?') {
                if (literal.length() > 0) {
                    re.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                re.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            re.append(Pattern.quote(literal.toString()));
        }
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static Set<String> lowered(Collection<String> in) {
        Set<String> out = new LinkedHashSet<>();
        if (in != null) {
            for (String s : in) {
                if (s != null && !s.trim().isEmpty()) {
                    out.add(s.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }
}
