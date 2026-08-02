package org.deju.plugin.run;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Ships the {@code deju-agent.jar} inside the plugin and unpacks it to a real file on
 * disk, because {@code -javaagent:} needs a filesystem path, it cannot point at a
 * resource nested inside the plugin jar.
 *
 * <p>The jar is bundled at build time (see {@code plugin/build.gradle.kts}, which copies
 * {@code :agent:shadowJar} into resources at {@code /agent/deju-agent.jar}) so the plugin
 * and the agent it attaches are always the same build.
 *
 * <p><b>The extracted file name carries a content hash</b>
 * ({@code deju-agent-<version>-<hash>.jar}). That is what makes a plugin update actually
 * take effect, and it fixes three problems that a single fixed name had:
 *
 * <ul>
 *   <li><b>Staleness.</b> The previous check re-extracted only when the byte length
 *   differed, so two builds of equal length would keep serving the old agent forever.
 *   Different content now means a different path, so there is nothing to compare.</li>
 *   <li><b>Windows file locking.</b> A running JVM holds its {@code -javaagent} jar open,
 *   and Windows refuses to overwrite an open file, the write threw {@link IOException},
 *   which was swallowed into "no agent attached". A new build now writes a new name and
 *   never has to overwrite anything in use.</li>
 *   <li><b>Silent version skew.</b> The path itself now names the build, so a stale
 *   {@code -javaagent} flag is visible rather than something you have to hash to spot.</li>
 * </ul>
 *
 * <p>A fixed-name {@code deju-agent.jar} alias is also refreshed on a best-effort basis so
 * that hand-written {@code -javaagent} flags (docker-compose, WSL, anything the IDE does
 * not launch) pick up a new agent after a plugin update. Failure to refresh the alias,
 * exactly what happens on Windows while the traced app is running, is not fatal: the
 * hashed path is still correct for anything the IDE launches.
 */
public final class DejuAgentBundle {

    private static final Logger LOG = Logger.getInstance(DejuAgentBundle.class);
    private static final String RESOURCE = "/agent/deju-agent.jar";
    /** Written by the build; see the generateVersionResource task in plugin/build.gradle.kts. */
    private static final String VERSION_RESOURCE = "/deju/version.properties";
    /** Kept for hand-written -javaagent flags that predate the hashed name. */
    private static final String LEGACY_NAME = "deju-agent.jar";
    private static final String PREFIX = "deju-agent-";

    private static Path resolved;
    private static String pluginVersion;

    private DejuAgentBundle() {
    }

    /**
     * Returns the on-disk path of the bundled agent jar, extracting it on first use.
     *
     * @return the jar path, or {@code null} if the plugin was built without a bundled agent.
     */
    @Nullable
    public static synchronized Path ensureExtracted() {
        return extract(false);
    }

    /**
     * The path to hand a human: fixed-name, and therefore still correct after an update.
     *
     * <p>{@link #ensureExtracted()} returns the hashed name, which is right for a flag the
     * IDE writes and rewrites itself. It is wrong for one the user pastes into a compose
     * file, a Gradle build or an env var, because the next plugin update renames the jar and
     * deletes the old one, and the flag then fails with the JVM's unhelpful "Error opening
     * zip file or JAR manifest missing". The alias is refreshed in place on every extraction
     * precisely so a pasted flag survives.
     *
     * <p>Falls back to the hashed path when the alias could not be refreshed, which happens
     * on Windows while a traced JVM holds it open. A correct path that may go stale later
     * beats no path at all.
     */
    @Nullable
    public static synchronized Path stableAgentPath() {
        Path extracted = extract(false);
        if (extracted == null) {
            return null;
        }
        Path alias = managedDir().resolve(LEGACY_NAME);
        return Files.isRegularFile(alias) ? alias : extracted;
    }

    /**
     * Re-extracts the bundled agent unconditionally, overwriting whatever is on disk.
     *
     * <p>Exists because extraction otherwise only happens as a side effect of launching a
     * run configuration. A user whose {@code -javaagent} flag is hand-written, a container,
     * WSL, or a VM option pasted into a run configuration, never triggers it, so after a
     * plugin update the fixed-name alias can sit at the previous version indefinitely with
     * nothing in the UI able to correct it.
     *
     * @return the extracted jar, or {@code null} if the plugin ships no agent.
     */
    @Nullable
    public static synchronized Path refresh() {
        resolved = null;
        return extract(true);
    }

    @Nullable
    private static Path extract(boolean force) {
        if (!force && resolved != null && Files.isRegularFile(resolved)) {
            return resolved;
        }
        try (InputStream in = DejuAgentBundle.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOG.warn("Bundled agent " + RESOURCE + " is missing from the plugin; auto-attach disabled.");
                return null;
            }
            byte[] bytes = in.readAllBytes();
            Path dir = managedDir();
            Files.createDirectories(dir);

            Path target = dir.resolve(PREFIX + versionTag() + "-" + shortHash(bytes) + ".jar");
            // The name encodes the content, so an existing file of the right size IS this
            // build, no comparison beyond that is meaningful. A forced refresh rewrites it
            // anyway, because "force" exists precisely for when the file on disk is not
            // trusted to be what its name claims.
            if (force || !Files.exists(target) || Files.size(target) != bytes.length) {
                writeAtomically(target, bytes);
            }
            refreshLegacyAlias(dir, bytes, force);
            pruneSupersededJars(dir, target);

            resolved = target;
            return target;
        } catch (IOException e) {
            LOG.warn("Could not extract bundled agent jar", e);
            return null;
        }
    }

    /** The directory this plugin owns; anything under it is ours to refresh or delete. */
    public static Path managedDir() {
        return Paths.get(PathManager.getSystemPath(), "deju-trace");
    }

    /** True when {@code vmOption} points at an agent jar inside our managed directory. */
    public static boolean isManagedPath(String vmOption) {
        if (vmOption == null) {
            return false;
        }
        String dir = managedDir().toString().replace('\\', '/');
        return vmOption.replace('\\', '/').contains(dir);
    }

    /**
     * This plugin's version, or {@code "unknown"}.
     *
     * <p>Read from a resource written by the build rather than asked of the platform. The
     * obvious API for this, {@code PluginManagerCore.getPlugin(PluginId)}, is annotated
     * {@code @ApiStatus.Internal}, and the Marketplace verifier reports it as a problem on
     * 2026.x builds even though older IDEs accept it silently. A generated resource cannot
     * be deprecated, made internal, or removed from under us.
     */
    public static String pluginVersion() {
        String known = pluginVersion;
        if (known != null) {
            return known;
        }
        String v = "unknown";
        try (InputStream in = DejuAgentBundle.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String read = props.getProperty("version");
                if (read != null && !read.trim().isEmpty()) {
                    v = read.trim();
                }
            } else {
                LOG.warn("Version resource " + VERSION_RESOURCE + " is missing from the plugin jar.");
            }
        } catch (IOException e) {
            LOG.warn("Could not read " + VERSION_RESOURCE, e);
        }
        pluginVersion = v;
        return v;
    }

    /** Filesystem-safe form of the plugin version, for use inside a file name. */
    private static String versionTag() {
        return pluginVersion().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Writes via a temporary file in the same directory and then moves it into place, so a
     * crash or a concurrent read never observes a half-written agent jar.
     */
    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), PREFIX, ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Best-effort refresh of the fixed-name alias, which is the only thing a hand-written
     * {@code -javaagent} flag can point at. Overwriting it fails on Windows while a traced
     * JVM holds it open; that is expected, logged, and not propagated.
     */
    private static void refreshLegacyAlias(Path dir, byte[] bytes, boolean force) {
        Path alias = dir.resolve(LEGACY_NAME);
        try {
            // Size equality is a weak test, two builds can be the same length, so a forced
            // refresh skips it. It is kept for the normal path only to avoid rewriting 11 MB
            // on every single run-configuration launch.
            if (!force && Files.exists(alias) && Files.size(alias) == bytes.length) {
                return;
            }
            writeAtomically(alias, bytes);
        } catch (IOException e) {
            LOG.info("Could not refresh " + LEGACY_NAME + " (a traced JVM may hold it open). "
                    + "Hand-written -javaagent flags will keep using the previous agent until "
                    + "that process exits.", e);
        }
    }

    /** Removes agent jars from earlier builds. Best-effort: a jar in use simply stays. */
    private static void pruneSupersededJars(Path dir, Path keep) {
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(dir, PREFIX + "*.jar")) {
            for (Path p : jars) {
                if (p.equals(keep)) {
                    continue;
                }
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.debug("Superseded agent jar still in use: " + p);
                }
            }
        } catch (IOException e) {
            LOG.debug("Could not list the agent directory for cleanup", e);
        }
    }

    /** First 12 hex characters of the SHA-256 of the bundled jar, enough to distinguish builds. */
    private static String shortHash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform; fall back to length so we still work.
            return "len" + bytes.length;
        }
    }
}
