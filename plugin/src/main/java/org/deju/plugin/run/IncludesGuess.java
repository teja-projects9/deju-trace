package org.deju.plugin.run;

/**
 * Guesses a starting {@code includes=} package prefix from a run configuration's main class,
 * for {@link DejuProgramPatcher}'s auto-attach path when the user has not set one in Settings.
 *
 * <p>Deliberately free of IntelliJ Platform imports so it can be unit-tested as plain Java,
 * the same reasoning as {@link AgentVmOption}.
 */
public final class IncludesGuess {

    private IncludesGuess() {
    }

    /**
     * The main class's own package, e.g. {@code "com.example.Application"} &rarr;
     * {@code "com.example"}.
     *
     * <p>This is not a shaky heuristic for the apps this plugin targets: a Spring Boot
     * {@code @SpringBootApplication} class sits at the root of the package it component-scans,
     * so its own package already <i>is</i> the app's own code, by the framework's own
     * convention, not a guess about it.
     *
     * <p>Empty when there is no main class, or it sits in the default package &mdash;
     * instrumenting an empty prefix would match every class on the classpath, JDK included,
     * which is worse than instrumenting nothing and asking the user to set Includes by hand.
     */
    public static String fromMainClass(String mainClassFqName) {
        if (mainClassFqName == null) {
            return "";
        }
        String trimmed = mainClassFqName.trim();
        int dot = trimmed.lastIndexOf('.');
        return dot <= 0 ? "" : trimmed.substring(0, dot);
    }
}
