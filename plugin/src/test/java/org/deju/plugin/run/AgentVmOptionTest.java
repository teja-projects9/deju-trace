package org.deju.plugin.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain-Java tests for the -javaagent rewrite; no IntelliJ fixture needed. */
class AgentVmOptionTest {

    private static final String DIR = "/Users/t/Library/Caches/JetBrains/IdeaIC2025.1/deju-trace";
    private static final String NEW = DIR + "/deju-agent-1.2.0-eap.1-ab12cd34ef56.jar";

    @Test
    void rewritesAStaleDejuAgentAndKeepsItsArguments() {
        String before = "-javaagent:" + DIR + "/deju-agent-1.1.0-eap.1-000000000000.jar"
                + "=port=7391,token=devtoken,includes=com.example";
        String after = AgentVmOption.rewrite(before, DIR, NEW);
        assertEquals("-javaagent:" + NEW + "=port=7391,token=devtoken,includes=com.example", after,
                "the port/token/includes arguments are the user's and must survive verbatim");
    }

    @Test
    void rewritesTheFixedNameAliasToo() {
        String before = "-javaagent:" + DIR + "/deju-agent.jar=port=7391,token=devtoken";
        assertEquals("-javaagent:" + NEW + "=port=7391,token=devtoken",
                AgentVmOption.rewrite(before, DIR, NEW));
    }

    @Test
    void leavesSomebodyElsesAgentCompletelyAlone() {
        String before = "-javaagent:/opt/newrelic/newrelic.jar -Xmx2g";
        assertNull(AgentVmOption.rewrite(before, DIR, NEW),
                "a profiler or APM agent is not ours to touch");
    }

    @Test
    void rewritesOursWhileLeavingAForeignAgentUntouched() {
        String before = "-javaagent:/opt/newrelic/newrelic.jar"
                + " -javaagent:" + DIR + "/deju-agent.jar=port=7391"
                + " -Xmx2g";
        assertEquals("-javaagent:/opt/newrelic/newrelic.jar"
                        + " -javaagent:" + NEW + "=port=7391"
                        + " -Xmx2g",
                AgentVmOption.rewrite(before, DIR, NEW));
    }

    @Test
    void otherVmOptionsArePreserved() {
        String before = "-Xmx2g -Dspring.profiles.active=dev -javaagent:" + DIR + "/deju-agent.jar=port=7391";
        String after = AgentVmOption.rewrite(before, DIR, NEW);
        assertTrue(after.startsWith("-Xmx2g -Dspring.profiles.active=dev "), after);
        assertTrue(after.endsWith("=port=7391"), after);
    }

    @Test
    void returnsNullWhenAlreadyCurrent() {
        String already = "-javaagent:" + NEW + "=port=7391";
        assertNull(AgentVmOption.rewrite(already, DIR, NEW),
                "nothing to do means no configuration is marked modified");
    }

    @Test
    void handlesAFlagWithNoArguments() {
        assertEquals("-javaagent:" + NEW,
                AgentVmOption.rewrite("-javaagent:" + DIR + "/deju-agent.jar", DIR, NEW));
    }

    @Test
    void splitsThePathAtTheFirstEqualsJustAsTheJvmDoes() {
        // The agent's own arguments contain '=' characters; the path must not absorb them.
        String before = "-javaagent:" + DIR + "/deju-agent.jar=port=7391,token=a=b,includes=com.example";
        assertEquals("-javaagent:" + NEW + "=port=7391,token=a=b,includes=com.example",
                AgentVmOption.rewrite(before, DIR, NEW));
    }

    @Test
    void keepsAQuotedPathAsASingleArgument() {
        String dir = "/Users/my user/Caches/deju-trace";
        String target = dir + "/deju-agent-1.2.0.jar";
        String before = "\"-javaagent:" + dir + "/deju-agent.jar=port=7391\"";
        String after = AgentVmOption.rewrite(before, dir, target);
        assertTrue(after.startsWith("\"") && after.endsWith("\""),
                "a path containing spaces must stay quoted or the JVM sees a truncated flag: " + after);
        assertTrue(after.contains(target), after);
    }

    @Test
    void aPathOutsideTheManagedDirectoryIsNeverRewritten() {
        // Same file name, different directory, a copy the user keeps elsewhere on purpose.
        String before = "-javaagent:/home/me/agents/deju-agent.jar=port=7391";
        assertNull(AgentVmOption.rewrite(before, DIR, NEW));
    }

    @Test
    void prefixCollisionIsNotTreatedAsInsideTheDirectory() {
        String before = "-javaagent:" + DIR + "-backup/deju-agent.jar=port=7391";
        assertNull(AgentVmOption.rewrite(before, DIR, NEW),
                "'deju-trace-backup' merely starts with the managed directory name");
    }

    @Test
    void windowsPathsMatchCaseInsensitivelyAndAcrossSeparators() {
        String dir = "C:\\Users\\T\\AppData\\Local\\JetBrains\\IdeaIC2025.1\\deju-trace";
        String target = dir + "\\deju-agent-1.2.0.jar";
        String before = "-javaagent:c:/users/t/appdata/local/jetbrains/ideaic2025.1/deju-trace/deju-agent.jar=port=7391";
        String after = AgentVmOption.rewrite(before, dir, target);
        assertTrue(after != null && after.contains(target),
                "the same run configuration is often edited on both platforms: " + after);
    }

    @Test
    void detectionAgreesWithRewriting() {
        String ours = "-javaagent:" + DIR + "/deju-agent.jar=port=7391";
        String theirs = "-javaagent:/opt/newrelic/newrelic.jar";
        assertTrue(AgentVmOption.containsManagedAgent(ours, DIR));
        assertFalse(AgentVmOption.containsManagedAgent(theirs, DIR));
        assertFalse(AgentVmOption.containsManagedAgent(null, DIR));
        assertFalse(AgentVmOption.containsManagedAgent("", DIR));
    }

    // ------------------------------------------- building the flag to copy ---

    @Test
    void buildsALoopbackFlagByDefault() {
        assertEquals("-javaagent:" + NEW + "=port=7391,token=devtoken,includes=com.example",
                AgentVmOption.build(NEW, "7391", "devtoken", "com.example", false),
                "a JVM on this machine must not be told to listen on every interface");
    }

    @Test
    void addsBindWhenTheJvmIsInAContainer() {
        // Without this the agent listens inside the container only, and a published port
        // reaches nothing, the IDE just reports "Connection refused".
        assertEquals("-javaagent:" + NEW + "=port=7391,token=devtoken,bind=0.0.0.0,includes=com.example",
                AgentVmOption.build(NEW, "7391", "devtoken", "com.example", true));
    }

    @Test
    void includesAreColonSeparatedForTheAgent() {
        // Commas already delimit the top-level pairs, so a comma-separated list would be
        // read as unknown keys and silently instrument nothing.
        String built = AgentVmOption.build(NEW, "7391", "t", "com.a, com.b", false);
        assertTrue(built.endsWith(",includes=com.a:com.b"), built);
        assertFalse(built.contains(" "),
                "a space inside a VM option splits it in two, and the JVM then refuses to start");
    }

    @Test
    void anIncludesListAlreadyUsingColonsIsAccepted() {
        // The agent's own syntax; a user copying from the docs must not end up with a
        // doubled separator.
        String built = AgentVmOption.build(NEW, "7391", "t", "com.a:com.b", false);
        assertTrue(built.endsWith(",includes=com.a:com.b"), built);
    }

    @Test
    void strayCommasDoNotProduceAnEmptyPrefix() {
        // An empty prefix matches every class, which would instrument the whole JVM.
        String built = AgentVmOption.build(NEW, "7391", "t", "com.a,com.b,", false);
        assertTrue(built.endsWith(",includes=com.a:com.b"), built);
    }

    @Test
    void aPlaceholderKeepsTheFlagValidWhenNoPackagesAreSet() {
        String built = AgentVmOption.build(NEW, "7391", "t", "  ", false);
        assertTrue(built.endsWith(",includes=" + AgentVmOption.INCLUDES_PLACEHOLDER), built);
        assertTrue(AgentVmOption.build(NEW, "7391", "t", null, false).contains("includes="));
    }

    @Test
    void aBuiltFlagIsRecognisedAndRewrittenByThisSameClass() {
        // The copied flag ends up in a run configuration, where the rewriter has to be able
        // to find it again after a plugin update.
        String old = AgentVmOption.build(DIR + "/deju-agent.jar", "7391", "devtoken", "com.example", true);
        assertTrue(AgentVmOption.containsManagedAgent(old, DIR));
        String rewritten = AgentVmOption.rewrite(old, DIR, NEW);
        assertTrue(rewritten.contains("bind=0.0.0.0"), "the bind argument must survive a rewrite: " + rewritten);
        assertTrue(rewritten.startsWith("-javaagent:" + NEW + "="), rewritten);
    }

    @Test
    void emptyAndNullInputsAreSafe() {
        assertNull(AgentVmOption.rewrite(null, DIR, NEW));
        assertNull(AgentVmOption.rewrite("", DIR, NEW));
        assertNull(AgentVmOption.rewrite("-Xmx2g", DIR, NEW));
        assertNull(AgentVmOption.rewrite("-javaagent:" + DIR + "/deju-agent.jar", null, NEW));
    }
}
