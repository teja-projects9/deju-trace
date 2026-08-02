package org.deju.plugin.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rewrites a {@code -javaagent:} flag in a run configuration's VM parameters so it points at
 * the current bundled agent.
 *
 * <p>Deliberately free of IntelliJ Platform imports so it can be unit-tested as plain Java;
 * {@link RunConfigAgentUpdater} supplies the paths and applies the result.
 *
 * <p><b>Only Deju's own agent is ever touched.</b> A {@code -javaagent} the user added for
 * something else, a profiler, a coverage tool, an APM, must survive untouched, so a flag
 * only qualifies when its jar sits inside the directory this plugin manages.
 */
public final class AgentVmOption {

    private static final String FLAG = "-javaagent:";

    private AgentVmOption() {
    }

    /**
     * Builds the {@code -javaagent} flag offered by "Copy agent VM option".
     *
     * <p>{@code includes} is comma-separated in the settings UI but colon-separated on the
     * agent side, because commas already delimit the top-level pairs.
     *
     * @param bindAllInterfaces when true, adds {@code bind=0.0.0.0}. Needed for a JVM in a
     *                          container: the agent's default loopback socket is reachable
     *                          only from inside it, so a published port lands on nothing.
     *                          Wrong for a local JVM, where it would open the port to the
     *                          whole network for no benefit.
     */
    public static String build(String agentPath, String port, String token,
                               String includes, boolean bindAllInterfaces) {
        StringBuilder sb = new StringBuilder(FLAG).append(agentPath)
                .append("=port=").append(port)
                .append(",token=").append(token == null ? "" : token);
        if (bindAllInterfaces) {
            sb.append(",bind=").append(BIND_ALL_INTERFACES);
        }
        return sb.append(",includes=").append(toAgentIncludes(includes)).toString();
    }

    /** The address that makes the agent reachable from outside its container. */
    public static final String BIND_ALL_INTERFACES = "0.0.0.0";

    /** Placeholder shown when no packages are configured, so the copied flag is still valid. */
    public static final String INCLUDES_PLACEHOLDER = "your.base.package";

    /**
     * Converts the settings field's comma-separated list to the agent's colon-separated one.
     *
     * <p>Each entry is trimmed, because a plain {@code replace(',', ':')} turns the natural
     * {@code "com.a, com.b"} into {@code "com.a: com.b"}, and a space inside a VM option
     * splits it into two arguments, so the JVM sees a truncated {@code -javaagent} and
     * refuses to start. Whitespace-only entries are dropped rather than emitted as empty
     * prefixes, which would match every class.
     */
    private static String toAgentIncludes(String includes) {
        if (includes == null) {
            return INCLUDES_PLACEHOLDER;
        }
        StringBuilder out = new StringBuilder();
        for (String part : includes.split("[,:]")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(':');
            }
            out.append(p);
        }
        return out.length() == 0 ? INCLUDES_PLACEHOLDER : out.toString();
    }

    /**
     * Returns {@code vmParameters} with any Deju agent path replaced by {@code newAgentPath},
     * or {@code null} when nothing needed changing.
     *
     * <p>The {@code =port=…,token=…,includes=…} arguments after the jar path are preserved
     * exactly: they are the user's configuration, not ours to regenerate.
     */
    public static String rewrite(String vmParameters, String managedDir, String newAgentPath) {
        if (vmParameters == null || vmParameters.isEmpty() || managedDir == null) {
            return null;
        }
        List<String> tokens = tokenize(vmParameters);
        boolean changed = false;
        for (int i = 0; i < tokens.size(); i++) {
            String rewritten = rewriteToken(tokens.get(i), managedDir, newAgentPath);
            if (rewritten != null) {
                tokens.set(i, rewritten);
                changed = true;
            }
        }
        return changed ? String.join(" ", tokens) : null;
    }

    /** True when this VM parameter string carries a Deju agent flag we would rewrite. */
    public static boolean containsManagedAgent(String vmParameters, String managedDir) {
        if (vmParameters == null || managedDir == null) {
            return false;
        }
        for (String token : tokenize(vmParameters)) {
            String unquoted = unquote(token);
            if (unquoted.startsWith(FLAG) && isManaged(jarPathOf(unquoted), managedDir)) {
                return true;
            }
        }
        return false;
    }

    private static String rewriteToken(String token, String managedDir, String newAgentPath) {
        String unquoted = unquote(token);
        if (!unquoted.startsWith(FLAG)) {
            return null;
        }
        String jar = jarPathOf(unquoted);
        if (!isManaged(jar, managedDir)) {
            return null;   // somebody else's agent
        }
        if (jar.equals(newAgentPath)) {
            return null;   // already current
        }
        String rest = unquoted.substring(FLAG.length() + jar.length());   // "" or "=args"
        String replacement = FLAG + newAgentPath + rest;
        // A path with spaces has to stay one argument, or the JVM sees a truncated flag.
        return replacement.indexOf(' ') >= 0 ? '"' + replacement + '"' : replacement;
    }

    /**
     * The jar path inside a {@code -javaagent:} flag.
     *
     * <p>Split at the FIRST {@code =}, which is what the JVM itself does: everything after it
     * is the agent's own argument string, and Deju's arguments contain further {@code =}
     * characters ({@code port=7391,token=…}).
     */
    private static String jarPathOf(String unquotedFlag) {
        String body = unquotedFlag.substring(FLAG.length());
        int eq = body.indexOf('=');
        return eq < 0 ? body : body.substring(0, eq);
    }

    private static boolean isManaged(String jarPath, String managedDir) {
        if (jarPath == null || jarPath.isEmpty()) {
            return false;
        }
        String path = normalise(jarPath);
        String dir = normalise(managedDir);
        if (!dir.endsWith("/")) {
            dir = dir + "/";
        }
        return path.startsWith(dir);
    }

    /**
     * Windows paths compare case-insensitively and may mix separators, and the same run
     * configuration is often edited on both platforms.
     */
    private static String normalise(String path) {
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static String unquote(String token) {
        if (token.length() >= 2 && token.charAt(0) == '"' && token.charAt(token.length() - 1) == '"') {
            return token.substring(1, token.length() - 1);
        }
        return token;
    }

    /** Splits on whitespace but keeps double-quoted runs together, as the IDE does. */
    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                cur.append(c);
            } else if (!quoted && Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }
}
