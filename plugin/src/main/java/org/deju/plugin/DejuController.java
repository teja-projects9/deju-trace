package org.deju.plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;
import java.util.Locale;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import org.deju.plugin.client.AgentClient;
import org.deju.plugin.contract.DejuPayload;
import org.deju.plugin.history.DejuHistoryStore;
import org.deju.plugin.history.ExecutionEntry;
import org.deju.plugin.history.ExportScope;
import org.deju.plugin.history.HtmlReportGenerator;
import org.deju.plugin.history.ReportPrefs;
import org.deju.plugin.run.DejuAgentBundle;
import org.deju.plugin.run.RunConfigAgentUpdater;
import org.deju.plugin.paint.EditorPainter;

/**
 * Per-project hub coordinating the socket client, the execution history, and the
 * editor painter, and relaying state to the tool window. Actions (e.g. "Add Deju
 * Point") also talk to this service.
 */
public final class DejuController implements AgentClient.Listener, Disposable {

    /** Implemented by the tool window so the controller can refresh it (calls arrive on the EDT). */
    public interface UiListener {
        void onConnectionChanged(boolean connected, String info);

        void onHistoryChanged();

        void onTargetChanged(String target);
    }

    private static final Logger LOG = Logger.getInstance(DejuController.class);

    /** Shown for a recording made before the agent started reporting its version. */
    public static final String PRE_VERSIONED_AGENT = "(pre-1.1.0)";

    /** Agent versions already warned about, so the mismatch notice appears once each. */
    private final java.util.Set<String> warnedAgentVersions = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Version of the agent that produced the most recent recording; null until one arrives. */
    private volatile String lastAgentVersion;

    private final Project project;
    private final EditorPainter painter;
    private volatile AgentClient client;
    private volatile String currentTarget = "";
    private volatile UiListener ui;

    public DejuController(Project project) {
        this.project = project;
        this.painter = new EditorPainter(project);
    }

    public static DejuController getInstance(Project project) {
        return project.getService(DejuController.class);
    }

    public void setUiListener(@Nullable UiListener listener) {
        this.ui = listener;
    }

    // ------------------------------------------------------------- connection ---

    public boolean isConnected() {
        AgentClient c = client;
        return c != null && c.isConnected();
    }

    public void connect() {
        if (isConnected()) {
            return;
        }
        DejuSettings settings = DejuSettings.getInstance();
        AgentClient c = new AgentClient(settings.host, settings.port, settings.token, this);
        this.client = c;
        c.connect();
    }

    public void disconnect() {
        AgentClient c = client;
        if (c != null) {
            c.disconnect();
        }
        client = null;
        onEdt(() -> notifyConnection(false, "disconnected"));
    }

    // ------------------------------------------------------------ target / arm ---

    public String getTarget() {
        return currentTarget;
    }

    public void setTarget(String target) {
        this.currentTarget = target == null ? "" : target.trim();
        onEdt(() -> {
            UiListener l = ui;
            if (l != null) {
                l.onTargetChanged(currentTarget);
            }
            refreshGutter();
        });
    }

    /** Starts tracking the API through the trace point, with a balloon reflecting the outcome. */
    public void arm() {
        AgentClient c = client;
        if (c == null || !c.isConnected()) {
            onEdt(() -> notify("Not connected, connect to the agent before tracking.", NotificationType.WARNING));
            return;
        }
        if (currentTarget.isEmpty()) {
            onEdt(() -> notify("Set a trace point (pkg.Class#method) before tracking.", NotificationType.WARNING));
            return;
        }
        c.arm(currentTarget);
        onEdt(() -> notify("Tracking started for " + currentTarget + ".", NotificationType.INFORMATION));
    }

    /** Stops tracking the API, with a confirming balloon. */
    public void disarm() {
        AgentClient c = client;
        if (c != null) {
            c.disarm();
        }
        onEdt(() -> notify("Tracking stopped.", NotificationType.INFORMATION));
    }

    // ------------------------------------------------- history / paint / export ---

    /** Loads a stored execution and paints it into the editor. */
    public void showExecution(int slot) {
        DejuPayload payload = DejuHistoryStore.getInstance(project).load(slot);
        if (payload == null) {
            return;
        }
        onEdt(() -> painter.paint(payload));
    }

    public void clearHighlighting() {
        onEdt(painter::clear);
    }

    /** Deletes one stored execution and refreshes the history list. */
    public void deleteExecution(int slot) {
        DejuHistoryStore.getInstance(project).delete(slot);
        onEdt(this::notifyHistoryChanged);
    }

    /** Deletes all stored executions, clears any painting, and refreshes the list. */
    public void deleteAllExecutions() {
        DejuHistoryStore.getInstance(project).deleteAll();
        onEdt(() -> {
            painter.clear();
            notifyHistoryChanged();
        });
    }

    private void notifyHistoryChanged() {
        UiListener l = ui;
        if (l != null) {
            l.onHistoryChanged();
        }
    }

    /** The agent build this plugin ships and would attach, always known. */
    public String bundledAgentVersion() {
        return DejuAgentBundle.pluginVersion();
    }

    /**
     * The agent version actually loaded in the traced JVM, as reported by the last
     * recording, or {@code null} if nothing has been recorded on this machine yet.
     *
     * <p>Falls back to the newest stored run so the tool window still shows something
     * useful after an IDE restart, rather than going blank until the next recording. A
     * {@code -javaagent} is loaded once at process start, so this is the only way to know
     * what is really running.
     *
     * <p>The fallback reads the in-memory history index rather than a stored payload: this
     * is called while the tool window is being built on the EDT, and parsing a payload off
     * disk there would block the UI.
     */
    @Nullable
    public String loadedAgentVersion() {
        String known = lastAgentVersion;
        if (known != null) {
            return known;
        }
        List<ExecutionEntry> entries = DejuHistoryStore.getInstance(project).entries();
        if (entries.isEmpty()) {
            return null;
        }
        String indexed = entries.get(0).agentVersion; // most-recent-first
        // Empty means the run was indexed before this field existed, so it came from an
        // agent that did not report a version at all.
        String v = indexed == null || indexed.isEmpty() ? PRE_VERSIONED_AGENT : indexed;
        lastAgentVersion = v;
        return v;
    }

    /** Writes a self-contained HTML report for a stored execution to {@code target}. */
    public void exportHtml(int slot, Path target, boolean omitExcluded, ReportPrefs prefs) throws IOException {
        DejuPayload payload = DejuHistoryStore.getInstance(project).load(slot);
        if (payload == null) {
            throw new IOException("Execution " + slot + " is no longer available");
        }
        String html = new HtmlReportGenerator(project).generate(payload, omitExcluded, prefs);
        Files.write(target, html.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * How much of a run the exclusion list covers, for the export dialog.
     *
     * <p>Reads a stored payload, so it must not run on the EDT.
     *
     * @return {@code null} when the run is gone; otherwise the class count and the number of
     *         source characters those classes account for.
     */
    @Nullable
    public ExportScope exportScope(int slot) {
        DejuPayload payload = DejuHistoryStore.getInstance(project).load(slot);
        return payload == null ? null : ExportScope.of(project, payload);
    }

    // ---------------------------------------------------- AgentClient.Listener ---

    @Override
    public void onConnected() {
        DejuSettings s = DejuSettings.getInstance();
        String at = s.host + ":" + s.port;
        onEdt(() -> {
            notifyConnection(true, "Connected to " + at);
            notify("Connected to the Deju agent at " + at + ".", NotificationType.INFORMATION);
        });
    }

    @Override
    public void onDisconnected(String reason) {
        onEdt(() -> {
            notifyConnection(false, reason);
            notify("Deju is not connected: " + reason + hintFor(reason), NotificationType.WARNING);
        });
    }

    /** Shows a Deju Trace balloon so connection state is visible regardless of tool-window width. */
    private void notify(String content, NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("Deju Trace")
                .createNotification("Deju Trace", content, type)
                .notify(project);
    }

    /** Turns a raw socket reason into an actionable next step. */
    private static String hintFor(String reason) {
        if (reason == null) {
            return "";
        }
        String r = reason.toLowerCase(Locale.ROOT);
        if (r.contains("refused")) {
            return ", nothing is listening on that port. Start your app with the deju agent attached,"
                    + " and for Docker publish the agent port and pass bind=0.0.0.0 to the agent.";
        }
        if (r.contains("auth")) {
            return ", the token does not match the agent's token= value.";
        }
        if (r.contains("timed out") || r.contains("timeout")) {
            return ", the port is unreachable (wrong host, a firewall, or a missing Docker mapping).";
        }
        return "";
    }

    @Override
    public void onPayload(DejuPayload payload) {
        int files = payload.getFiles() == null ? 0 : payload.getFiles().size();
        int lines = payload.totalLines();
        String target = payload.getTarget() == null ? "?" : payload.getTarget();
        warnIfAgentIsStale(payload);
        try {
            DejuHistoryStore.getInstance(project).add(payload);
            // Visible confirmation that a run actually arrived and was stored, so an empty
            // history list can be told apart from "nothing was ever received".
            onEdt(() -> notify("Recorded " + files + " file(s), " + lines + " line(s) for "
                    + target + ".", NotificationType.INFORMATION));
        } catch (IOException e) {
            LOG.warn("Failed to persist execution", e);
            onEdt(() -> notify("Deju received a run for " + target
                    + " but couldn't save it: " + e.getMessage(), NotificationType.ERROR));
        }
        onEdt(() -> {
            UiListener l = ui;
            if (l != null) {
                l.onHistoryChanged();
            }
        });
    }

    /**
     * Warns once per mismatched agent version that the traced JVM is running an agent older
     * (or newer) than this plugin.
     *
     * <p>A {@code -javaagent} jar is loaded once at process start, and for any JVM the IDE
     * does not launch (docker-compose, WSL, a hand-written flag), nothing refreshes it at
     * all. Before this check the only symptom was report features quietly going missing,
     * which is indistinguishable from a bug in the report.
     */
    private void warnIfAgentIsStale(DejuPayload payload) {
        String agent = payload.getAgentVersion();
        String plugin = DejuAgentBundle.pluginVersion();
        String seen = agent == null ? PRE_VERSIONED_AGENT : agent;
        lastAgentVersion = seen;
        if (agent != null && agent.equals(plugin)) {
            return;
        }
        if (!warnedAgentVersions.add(seen)) {
            return; // Already said this; don't nag on every recording.
        }
        onEdt(() -> notifyStaleAgent(seen, plugin));
    }

    /**
     * The stale-agent warning, with the two fixes attached as actions.
     *
     * <p>The message alone used to leave the user to work out that the on-disk agent needed
     * re-extracting and that a pinned {@code -javaagent} path needed editing, both of which
     * the plugin can simply do.
     */
    private void notifyStaleAgent(String seen, String plugin) {
        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("Deju Trace")
                .createNotification("Deju Trace",
                        "This run was recorded by Deju agent " + seen
                                + ", but the installed plugin is " + plugin
                                + ". Until the traced application restarts with the current agent,"
                                + " newer report features (such as SQL and the call tree) are missing"
                                + " from recordings.",
                        NotificationType.WARNING);

        notification.addAction(NotificationAction.createSimple("Refresh agent on disk", () -> {
            java.nio.file.Path agent = DejuAgentBundle.refresh();
            notify(agent == null
                            ? "This plugin build ships no agent to refresh."
                            : "Agent " + DejuAgentBundle.pluginVersion() + " written to " + agent
                            + ". Restart your application to load it.",
                    agent == null ? NotificationType.ERROR : NotificationType.INFORMATION);
        }));

        notification.addAction(NotificationAction.createSimple("Fix run configurations", () -> {
            RunConfigAgentUpdater.Result result = RunConfigAgentUpdater.updateAll(project);
            if (result == null || result.updated.isEmpty()) {
                notify("No run configuration needed its agent path changed.",
                        NotificationType.INFORMATION);
            } else {
                notify("Updated the agent path in: " + String.join(", ", result.updated)
                        + ". Restart your application to load it.", NotificationType.INFORMATION);
            }
        }));

        notification.notify(project);
    }

    private void notifyConnection(boolean connected, String info) {
        UiListener l = ui;
        if (l != null) {
            l.onConnectionChanged(connected, info);
        }
        // Re-run line markers so the trace-point gutter mark flips to the live ✓ (or back).
        refreshGutter();
    }

    /** Re-triggers code analysis so {@code DejuLineMarkerProvider} recomputes the trace-point mark. */
    private void refreshGutter() {
        if (!project.isDisposed()) {
            DaemonCodeAnalyzer.getInstance(project).restart();
        }
    }

    private static void onEdt(Runnable r) {
        ApplicationManager.getApplication().invokeLater(r);
    }

    @Override
    public void dispose() {
        disconnect();
        painter.clear();
    }
}
