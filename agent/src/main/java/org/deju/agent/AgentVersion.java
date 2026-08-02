package org.deju.agent;

import java.io.InputStream;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * The agent's own build version, stamped into the jar manifest as
 * {@code Implementation-Version} by {@code agent/build.gradle.kts}.
 *
 * <p>This travels in every payload so the plugin can tell when a traced JVM is still
 * running an older agent than the installed plugin, which happens routinely and used to
 * be invisible: a {@code -javaagent} jar is loaded once at JVM start, and for anything the
 * IDE does not launch (docker-compose, WSL, a hand-written flag) nothing refreshes it at
 * all. The symptom was features silently missing from the report with no explanation.
 *
 * <p>Java 11 compatible, no dependencies, this runs inside the traced application.
 */
public final class AgentVersion {

    private static final String UNKNOWN = "unknown";
    private static final String VALUE = discover();

    private AgentVersion() {
    }

    /** The agent version, or {@code "unknown"} if the manifest could not be read. */
    public static String get() {
        return VALUE;
    }

    private static String discover() {
        // Normal case: the class was loaded from the agent jar, so the sealing information
        // from that jar's manifest is already attached to the package.
        Package pkg = AgentVersion.class.getPackage();
        if (pkg != null) {
            String v = pkg.getImplementationVersion();
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        // Fallback for class loaders that do not populate package attributes (and for the
        // unit tests, which load these classes from a plain directory).
        return fromManifest();
    }

    private static String fromManifest() {
        try (InputStream in = AgentVersion.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (in == null) {
                return UNKNOWN;
            }
            Attributes attrs = new Manifest(in).getMainAttributes();
            // Only trust a manifest that is actually ours; on a shared classpath the first
            // MANIFEST.MF found may belong to some other jar entirely.
            String title = attrs.getValue("Implementation-Title");
            if (title == null || !title.contains("Deju")) {
                return UNKNOWN;
            }
            String v = attrs.getValue("Implementation-Version");
            return v == null || v.isEmpty() ? UNKNOWN : v;
        } catch (Exception e) {
            // Version reporting must never break a recording.
            return UNKNOWN;
        }
    }
}
