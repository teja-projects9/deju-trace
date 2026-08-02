package org.deju.plugin;

import java.security.SecureRandom;
import java.util.Base64;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Application-level connection settings for the agent socket.
 *
 * <p>TODO(deju): the token is a shared secret; for MVP it is stored in plain plugin
 * settings. A production build should keep it in the IDE {@code PasswordSafe} instead
 * of {@code DejuTrace.xml}.
 */
@State(name = "DejuTraceSettings", storages = @Storage("DejuTrace.xml"))
public final class DejuSettings implements PersistentStateComponent<DejuSettings> {

    // Defaults, also used by the "Reset to defaults" action in the settings page.
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 7391;
    public static final boolean DEFAULT_AUTO_ATTACH = false;
    public static final String DEFAULT_INCLUDES = "";
    public static final String DEFAULT_SOURCE_ROOTS = "";
    public static final int DEFAULT_MAX_OPEN_FILES = 5;
    public static final boolean DEFAULT_CONTAINER_OR_REMOTE_JVM = false;

    /**
     * Host the agent socket is reachable on. Loopback for a local app; for a container or a
     * remote box, point this at the mapped host/port so no socat bridge is needed.
     */
    public String host = DEFAULT_HOST;
    /** Must match the agent's {@code port=} argument. */
    public int port = DEFAULT_PORT;
    /**
     * Must match the agent's {@code token=} argument.
     *
     * <p>Generated per installation rather than shipped as a fixed string. This token is the
     * only thing standing between the control socket and anything else that can reach it,
     * and with {@code bind=0.0.0.0} that can be the whole network. A default published in
     * the source of a plugin anyone can download is not a secret, so there is none.
     */
    public String token = newToken();

    /**
     * When {@code true}, the bundled agent is auto-attached (via {@code -javaagent}) to
     * Java run configurations that IntelliJ launches locally, using {@link #port},
     * {@link #token} and {@link #includes}. This is the only path to a truly config-free
     * setup, and it applies only to JVMs the IDE starts, a container launched by
     * docker-compose is out of the IDE's control and still needs the agent flag in its
     * own compose/env. Opt-in: injecting an agent into every local run is invasive, so it
     * stays off until the user turns it on.
     */
    public boolean autoAttach = DEFAULT_AUTO_ATTACH;
    /**
     * Package prefixes the auto-attached agent instruments, comma- or colon-separated,
     * e.g. {@code org.deju}. Empty means "instrument nothing", so auto-attach
     * is a no-op until this is set.
     */
    public String includes = DEFAULT_INCLUDES;

    /**
     * Optional source-root directories (semicolon- or newline-separated) Deju reads source
     * from, by fully-qualified name, instead of the IDE's project index. Use it when the IDE
     * can't resolve the traced classes, or to force a specific tree. It must hold source
     * matching the running build, or line numbers won't line up. Empty = use the IDE index.
     */
    public String sourceRoots = DEFAULT_SOURCE_ROOTS;

    /**
     * How many editor tabs "Show" is allowed to open for one recorded run.
     *
     * <p>A single request routinely touches dozens of classes once builders and mappers are
     * counted, and opening a tab for each buries the file the user was actually looking at.
     * Files are opened in execution order, so the cap keeps the start of the call tree; the
     * rest are named in a notification rather than dropped silently. Zero or less means no
     * limit, for anyone who preferred the old behaviour.
     *
     * <p>Every recorded file is painted either way, so a file past the cap still shows its
     * coverage the moment it is opened by hand. The limit is only about how many tabs appear
     * unasked, which is why it is deliberately low.
     */
    public int maxOpenFiles = DEFAULT_MAX_OPEN_FILES;

    /**
     * Whether the JVM being traced runs somewhere the agent's default loopback socket
     * cannot be reached from, a container, or another machine.
     *
     * <p>When set, "Copy agent VM option" adds {@code bind=0.0.0.0} so the agent listens on
     * every interface and a published container port actually reaches it. The plugin cannot
     * work this out for itself: with Docker the port is published to the host, so the IDE
     * connects to {@code localhost} exactly as it would for a local JVM, and the two cases
     * are indistinguishable from this side.
     *
     * <p>Off by default. Binding beyond loopback is a real widening of what can reach the
     * agent, so it stays something the developer opts into rather than something the plugin
     * guesses. It has no effect on runs the IDE launches itself, those are local, and
     * {@code DejuProgramPatcher} keeps them on loopback.
     */
    public boolean containerOrRemoteJvm = DEFAULT_CONTAINER_OR_REMOTE_JVM;

    public static DejuSettings getInstance() {
        return ApplicationManager.getApplication().getService(DejuSettings.class);
    }

    /**
     * A fresh random token: 128 bits, from {@link SecureRandom}, in the URL-safe alphabet so
     * it survives being pasted into a VM option, a compose file or an env var unquoted.
     */
    public static String newToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Restores every field, with a newly generated token.
     *
     * <p>The token is regenerated rather than preserved because this is also what "clear
     * everything" calls, and leaving the previous secret in place would make a reset
     * incomplete in exactly the field where that matters most.
     */
    public synchronized void resetToDefaults() {
        host = DEFAULT_HOST;
        port = DEFAULT_PORT;
        token = newToken();
        autoAttach = DEFAULT_AUTO_ATTACH;
        includes = DEFAULT_INCLUDES;
        sourceRoots = DEFAULT_SOURCE_ROOTS;
        maxOpenFiles = DEFAULT_MAX_OPEN_FILES;
        containerOrRemoteJvm = DEFAULT_CONTAINER_OR_REMOTE_JVM;
    }

    @Override
    public @Nullable DejuSettings getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull DejuSettings state) {
        XmlSerializerUtil.copyBean(state, this);
    }
}
