package org.deju.plugin;

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
 * <p><b>The token is a fixed, published value</b>, {@link #DEFAULT_TOKEN}. It is therefore
 * not a secret, and the only thing keeping the control socket private is that the agent
 * binds to loopback. Anyone who can reach the port on a JVM started with
 * {@code bind=0.0.0.0} can arm a trace on it, so that combination is for a machine you
 * control, on a network you trust. See {@link #token}.
 */
@State(name = "DejuTraceSettings", storages = @Storage("DejuTrace.xml"))
public final class DejuSettings implements PersistentStateComponent<DejuSettings> {

    // Defaults, also used by the "Reset to defaults" action in the settings page.
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 7391;
    /**
     * The one token this plugin ever uses, so {@code token=dejutoken} in a hand-written
     * {@code -javaagent} flag is always right and never has to be copied from a dialog.
     *
     * <p>Chosen for that convenience with the trade-off understood: it is printed in the
     * source of a plugin anyone can download, so it authenticates nothing. Loopback is the
     * boundary now.
     */
    public static final String DEFAULT_TOKEN = "dejutoken";
    public static final boolean DEFAULT_AUTO_ATTACH = false;
    public static final String DEFAULT_INCLUDES = "";
    public static final String DEFAULT_SOURCE_ROOTS = "";
    /**
     * One tab: the traced method's own file.
     *
     * <p>Deliberately the minimum. Every recorded file is painted whether or not it is
     * opened, and the exclusion dialog can jump straight to any class in the run, so extra
     * tabs buy nothing that is not one click away, while five of them reliably bury whatever
     * the user had open before they pressed Show.
     */
    public static final int DEFAULT_MAX_OPEN_FILES = 1;
    public static final boolean DEFAULT_CONTAINER_OR_REMOTE_JVM = false;
    /** See {@link #historyCapacity} and {@code DejuHistoryStore.MAX_CAPACITY}. */
    public static final int DEFAULT_HISTORY_CAPACITY = 10;

    /**
     * Host the agent socket is reachable on. Loopback for a local app; for a container or a
     * remote box, point this at the mapped host/port so no socat bridge is needed.
     */
    public String host = DEFAULT_HOST;
    /** Must match the agent's {@code port=} argument. */
    public int port = DEFAULT_PORT;
    /**
     * Must match the agent's {@code token=} argument. Always {@link #DEFAULT_TOKEN}.
     *
     * <p>Kept as a field rather than read straight off the constant so the settings page can
     * display it and {@code DejuTrace.xml} still round-trips; {@link #loadState} normalises
     * whatever an older installation wrote there, including the random token earlier builds
     * generated, so upgrading does not leave the plugin and a hand-written
     * {@code -javaagent} flag disagreeing.
     */
    public String token = DEFAULT_TOKEN;

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
     * e.g. {@code org.deju}. Empty is not necessarily "instrument nothing" any more: the
     * auto-attach path ({@code DejuProgramPatcher}) first tries to guess a prefix from the
     * run configuration's own main class ({@code IncludesGuess}) before giving up, so a
     * typical Spring Boot run needs no visit here at all. Set explicitly to override that
     * guess, or when there is no main class to guess from (a multi-module app, a test run).
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
     * rest are named in a notification rather than dropped silently. Zero opens none at all —
     * paint only, no tabs — for anyone who wants the coverage colour without any tab churn.
     *
     * <p>Every recorded file is painted either way, so a file past the cap (zero included)
     * still shows its coverage the moment it is opened by hand. The limit is only about how
     * many tabs appear unasked, which is why it is deliberately low.
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

    /**
     * How many recorded runs the history ring buffer keeps, per project.
     *
     * <p>Clamped to {@code DejuHistoryStore.MIN_CAPACITY}..{@code MAX_CAPACITY} wherever it
     * is read rather than here, so a value written by a future version with a wider range
     * still round-trips through an older one instead of being silently rewritten.
     */
    public int historyCapacity = DEFAULT_HISTORY_CAPACITY;

    public static DejuSettings getInstance() {
        return ApplicationManager.getApplication().getService(DejuSettings.class);
    }

    /** Restores every field to the shipped defaults. */
    public synchronized void resetToDefaults() {
        host = DEFAULT_HOST;
        port = DEFAULT_PORT;
        token = DEFAULT_TOKEN;
        autoAttach = DEFAULT_AUTO_ATTACH;
        includes = DEFAULT_INCLUDES;
        sourceRoots = DEFAULT_SOURCE_ROOTS;
        maxOpenFiles = DEFAULT_MAX_OPEN_FILES;
        containerOrRemoteJvm = DEFAULT_CONTAINER_OR_REMOTE_JVM;
        historyCapacity = DEFAULT_HISTORY_CAPACITY;
    }

    @Override
    public @Nullable DejuSettings getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull DejuSettings state) {
        XmlSerializerUtil.copyBean(state, this);
        // Whatever is on disk, the token is the constant. Earlier builds generated a random
        // one per installation and stored it here; leaving that in place after an upgrade
        // would silently break every hand-written token=dejutoken flag.
        token = DEFAULT_TOKEN;
    }
}
