package org.deju.plugin.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.file.Path;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;

import com.intellij.icons.AllIcons;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileChooser.FileSaverDialog;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import org.deju.plugin.DejuController;
import org.deju.plugin.DejuSettings;
import org.deju.plugin.exclude.ExcludedTypesDialog;
import org.deju.plugin.history.DejuHistoryStore;
import org.deju.plugin.history.ExecutionEntry;
import org.deju.plugin.history.ExportScope;
import org.deju.plugin.history.ExportOptionsDialog;
import org.deju.plugin.run.AgentVmOption;
import org.deju.plugin.run.DejuAgentBundle;
import org.deju.plugin.run.RunConfigAgentUpdater;
import org.deju.plugin.settings.DejuConfigurable;

/**
 * The "Deju Trace" tool window: a compact server/connection row, the trace point with a
 * single Track / Stop toggle, and the recent-executions list with Show / Export / Clear.
 * A gear button opens the full settings page.
 *
 * <p>Plain-language controls (no arm/disarm jargon), semantic icons and colours, and live
 * validation: the port outline turns red on a bad value, and Track enables only when it can
 * actually do something.
 */
public final class DejuToolWindowPanel extends JBPanel<DejuToolWindowPanel> implements DejuController.UiListener {

    private static final JBColor OK_GREEN = new JBColor(new java.awt.Color(0x2E, 0x7D, 0x32), new java.awt.Color(0x6A, 0xBF, 0x6A));
    private static final JBColor ERROR_RED = new JBColor(new java.awt.Color(0xC5, 0x2F, 0x2F), new java.awt.Color(0xE0, 0x6C, 0x6C));
    private static final JBColor WARN_AMBER = new JBColor(new java.awt.Color(0xB0, 0x71, 0x00), new java.awt.Color(0xD6, 0xB6, 0x56));

    private final Project project;
    private final DejuController controller;

    private final JBTextField hostField = new JBTextField(9);
    private final JBTextField portField = new JBTextField(5);
    private final JButton connectButton = UiStyle.compact(new JButton("Connect", AllIcons.Nodes.Plugin));
    private final JButton settingsButton = UiStyle.compact(new JButton(AllIcons.General.Settings));
    private final JLabel statusLabel = new JLabel("Disconnected");
    private final JButton copyVmButton = UiStyle.compact(new JButton("Copy agent VM option", AllIcons.Actions.Copy));

    private final JBTextField traceField = new JBTextField(26);
    /** Single toggle: "Track" when idle, "Stop" while recording. */
    private final JButton trackButton = UiStyle.compact(new JButton("Track", AllIcons.Actions.Execute));
    private final JButton excludeButton = UiStyle.compact(new JButton("Excluded types…", AllIcons.General.Filter));
    private final JButton refreshAgentButton = UiStyle.compact(new JButton("Refresh agent", AllIcons.Actions.Refresh));
    private final JButton fixRunConfigsButton = UiStyle.compact(new JButton("Fix run configs", AllIcons.Actions.Edit));
    private boolean tracking = false;

    private final DefaultListModel<ExecutionEntry> historyModel = new DefaultListModel<>();
    private final JBList<ExecutionEntry> historyList = new JBList<>(historyModel);
    private final JButton showButton = UiStyle.compact(new JButton("Show", AllIcons.Actions.Preview));
    private final JButton exportButton = UiStyle.compact(new JButton("Export…", AllIcons.Actions.Download));
    private final JButton deleteButton = UiStyle.compact(new JButton("Delete", AllIcons.General.Remove));
    private final JButton deleteAllButton = UiStyle.compact(new JButton("Delete all", AllIcons.Actions.GC));
    private final JButton clearButton = UiStyle.compact(new JButton("Clear highlights", AllIcons.Actions.Cancel));

    /**
     * Footer showing the agent this plugin ships next to the agent actually loaded in the
     * traced JVM. Those two drift apart routinely, a -javaagent is loaded once at process
     * start, and for anything the IDE does not launch nothing refreshes it, and until this
     * label existed the only symptom was report features silently going missing.
     */
    private final JLabel agentLabel = new JLabel();

    public DejuToolWindowPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.controller = DejuController.getInstance(project);

        setBorder(JBUI.Borders.empty(8));
        add(buildControls(), BorderLayout.NORTH);
        add(buildHistory(), BorderLayout.CENTER);
        add(buildAgentFooter(), BorderLayout.SOUTH);

        DejuSettings settings = DejuSettings.getInstance();
        hostField.setText(settings.host);
        portField.setText(String.valueOf(settings.port));
        traceField.setText(controller.getTarget());

        trackButton.setToolTipText("Track the API");
        connectButton.setToolTipText("Connect to the agent socket");
        settingsButton.setToolTipText("Open Deju Trace settings");
        hostField.setToolTipText("127.0.0.1 for a local app; a mapped host for a container / remote box");
        traceField.getEmptyText().setText("pkg.Class#method");

        wireActions();
        controller.setUiListener(this);
        refreshHistory();
        updateButtons(controller.isConnected());
    }

    private JComponent buildControls() {
        JBPanel<?> box = new JBPanel<>();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));

        JBPanel<?> conn = row();
        conn.add(new JLabel("Host:"));
        conn.add(hostField);
        conn.add(new JLabel("Port:"));
        conn.add(portField);
        conn.add(connectButton);
        conn.add(settingsButton);
        box.add(conn);

        // Status on its own line so the connection reason is always visible, even in a
        // narrow tool window (it used to be pushed off-screen next to the Connect button).
        JBPanel<?> statusRow = row();
        statusRow.add(new JLabel("Status:"));
        statusRow.add(statusLabel);
        statusRow.add(copyVmButton);
        copyVmButton.setToolTipText("Copy the -javaagent VM option (bundled agent + current port/token/includes)"
                + " to paste into your app's run configuration");
        box.add(statusRow);

        JBPanel<?> traceRow = row();
        traceRow.add(new JLabel("Trace point:"));
        traceRow.add(traceField);
        traceRow.add(trackButton);
        traceRow.add(excludeButton);
        excludeButton.setToolTipText("Choose which classes or packages the report folds away and"
                + " Show skips (entities, DTOs and other data classes), or double-click one to"
                + " open it at the method the run entered it through. Patterns also live in"
                + " Settings → Tools → Deju Trace. The report always keeps the full call tree,"
                + " its “Full” detail level shows everything.");
        excludeButton.addActionListener(e -> ExcludedTypesDialog.show(project));
        box.add(traceRow);

        return box;
    }

    /**
     * One row of controls that wraps instead of overflowing.
     *
     * <p>A plain {@link FlowLayout} keeps everything on a single line as far as its
     * preferred size is concerned, so the tool window, docked to the right and rarely more
     * than 300px wide, ends up with a horizontal scrollbar across the buttons. See
     * {@link WrapLayout}.
     */
    private static JBPanel<?> row() {
        JBPanel<?> p = new JBPanel<>(new WrapLayout(FlowLayout.LEFT, 6, 2));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private JComponent buildHistory() {
        JBPanel<?> panel = new JBPanel<>(new BorderLayout(0, 6));
        panel.setBorder(JBUI.Borders.emptyTop(8));
        panel.add(new JLabel("Recent runs (last " + DejuHistoryStore.CAPACITY + "):"), BorderLayout.NORTH);

        historyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        historyList.setCellRenderer((list, value, index, selected, focused) -> {
            JLabel label = new JLabel(describe(value));
            label.setIcon(AllIcons.Toolwindows.ToolWindowCoverage);
            label.setOpaque(true);
            if (selected) {
                label.setBackground(list.getSelectionBackground());
                label.setForeground(list.getSelectionForeground());
            }
            label.setBorder(JBUI.Borders.empty(2, 6));
            return label;
        });
        panel.add(new JBScrollPane(historyList), BorderLayout.CENTER);

        showButton.setToolTipText("Open the selected run's files and paint its coverage."
                + " Excluded types are skipped and the rest are capped (Settings → Tools →"
                + " Deju Trace); what was left out is listed in a notification.");
        exportButton.setToolTipText("Export the selected run as a self-contained HTML report");
        deleteButton.setToolTipText("Delete the selected run");
        deleteAllButton.setToolTipText("Delete all recorded runs");
        clearButton.setToolTipText("Remove coverage highlighting and the timing column from editors");

        JBPanel<?> actions = row();
        actions.add(showButton);
        actions.add(exportButton);
        actions.add(deleteButton);
        actions.add(deleteAllButton);
        actions.add(clearButton);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private void wireActions() {
        connectButton.addActionListener(e -> toggleConnection());
        settingsButton.addActionListener(e -> {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, DejuConfigurable.class);
            syncFieldsFromSettings();
        });
        trackButton.addActionListener(e -> toggleTracking());
        historyList.addListSelectionListener(e -> updateHistoryButtons());
        showButton.addActionListener(e -> {
            ExecutionEntry entry = historyList.getSelectedValue();
            if (entry != null) {
                controller.showExecution(entry.slot);
            }
        });
        exportButton.addActionListener(e -> exportSelected());
        deleteButton.addActionListener(e -> deleteSelected());
        deleteAllButton.addActionListener(e -> deleteAll());
        clearButton.addActionListener(e -> controller.clearHighlighting());
        copyVmButton.addActionListener(e -> copyAgentVmOption());

        portField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                validatePort();
            }
        });
        traceField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                updateTrackEnabled();
            }
        });
    }

    /** Track ⇄ Stop. Starting sets the trace point and arms; stopping disarms. */
    private void toggleTracking() {
        if (tracking) {
            controller.disarm();
            setTracking(false);
        } else if (controller.isConnected()) {
            controller.setTarget(traceField.getText());
            controller.arm();
            setTracking(true);
        }
    }

    private void setTracking(boolean on) {
        tracking = on;
        trackButton.setText(on ? "Stop" : "Track");
        trackButton.setIcon(on ? AllIcons.Actions.Suspend : AllIcons.Actions.Execute);
        trackButton.setToolTipText(on ? "Stop the API tracking" : "Track the API");
        updateTrackEnabled();
    }

    /** Re-reads host/port into the quick-access fields after the settings dialog closes. */
    private void syncFieldsFromSettings() {
        DejuSettings s = DejuSettings.getInstance();
        if (!controller.isConnected()) {
            hostField.setText(s.host);
            portField.setText(String.valueOf(s.port));
        }
    }

    private void toggleConnection() {
        if (controller.isConnected()) {
            controller.disconnect();
            return;
        }
        if (!validatePort()) {
            setStatus("Port must be 1–65535", ERROR_RED);
            return;
        }
        DejuSettings settings = DejuSettings.getInstance();
        String host = hostField.getText().trim();
        settings.host = host.isEmpty() ? DejuSettings.DEFAULT_HOST : host;
        settings.port = Integer.parseInt(portField.getText().trim());
        setStatus("Connecting…", null);
        controller.connect();
    }

    /** @return true if the port field holds a valid 1–65535 value; updates its error outline. */
    private boolean validatePort() {
        boolean valid;
        try {
            int p = Integer.parseInt(portField.getText().trim());
            valid = p >= 1 && p <= 65535;
        } catch (NumberFormatException ex) {
            valid = false;
        }
        portField.putClientProperty("JComponent.outline", valid ? null : "error");
        portField.repaint();
        connectButton.setEnabled(valid || controller.isConnected());
        return valid;
    }

    private void exportSelected() {
        ExecutionEntry entry = historyList.getSelectedValue();
        if (entry == null) {
            return;
        }
        // Measuring the exclusion coverage reads the stored payload off disk, so it runs
        // behind a progress indicator rather than freezing the EDT while the dialog opens.
        ExportScope scope = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                () -> controller.exportScope(entry.slot),
                "Preparing Export…", true, project);
        if (scope == null) {
            Messages.showErrorDialog(project, "That run is no longer available.", "Deju Trace");
            return;
        }
        ExportOptionsDialog options = new ExportOptionsDialog(project, scope.excludedClasses, scope.approxBytes);
        if (!options.showAndGet()) {
            return;
        }

        // TODO(deju): this 3-arg constructor is deprecated from 2025.1 onward, but its
        // replacement does not exist in 2024.1, the floor of our supported range. It is
        // still present and functional on every supported IDE (the Plugin Verifier reports
        // "Compatible" on 241 through 251). Migrate when the minimum is raised above 2024.x.
        FileSaverDescriptor descriptor =
                new FileSaverDescriptor("Export Deju HTML Report", "Choose where to save the report", "html");
        FileSaverDialog dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project);
        VirtualFileWrapper wrapper = dialog.save((Path) null, "deju-report.html");
        if (wrapper == null) {
            return;
        }
        try {
            controller.exportHtml(entry.slot, wrapper.getFile().toPath(), options.isOmitExcluded());
        } catch (IOException ex) {
            Messages.showErrorDialog(project, "Failed to export report: " + ex.getMessage(), "Deju Trace");
        }
    }

    private void deleteSelected() {
        ExecutionEntry entry = historyList.getSelectedValue();
        if (entry != null) {
            controller.deleteExecution(entry.slot);
        }
    }

    private void deleteAll() {
        if (historyModel.isEmpty()) {
            return;
        }
        int choice = Messages.showYesNoDialog(project,
                "Delete all " + historyModel.size() + " recorded run(s)? This cannot be undone.",
                "Delete All Runs", "Delete All", "Cancel", Messages.getWarningIcon());
        if (choice == Messages.YES) {
            controller.deleteAllExecutions();
        }
    }

    /**
     * Builds the ready-to-paste {@code -javaagent} option from the bundled agent and the
     * current port/token/includes, and copies it to the clipboard. This is the manual
     * counterpart to auto-attach, for JVMs the IDE doesn't launch (e.g. docker-compose).
     */
    private void copyAgentVmOption() {
        DejuSettings settings = DejuSettings.getInstance();
        String port = portField.getText().trim().isEmpty()
                ? String.valueOf(settings.port) : portField.getText().trim();
        String includes = settings.includes == null ? "" : settings.includes.trim();

        // The fixed-name alias, not the hashed jar: this string is going somewhere the IDE
        // cannot reach in to fix, so it has to keep working across plugin updates.
        Path agent = DejuAgentBundle.stableAgentPath();
        String agentPath = agent != null ? agent.toString() : "/path/to/deju-agent.jar";

        // A container publishes its port to the host, so the agent's default loopback socket
        // is reachable from inside the container only and the IDE gets "Connection refused".
        // The plugin cannot detect that case, the IDE still connects to localhost either
        // way, so it follows the setting the developer set for their own environment.
        boolean bindAll = settings.containerOrRemoteJvm;
        String vmOption = AgentVmOption.build(agentPath, port, settings.token, includes, bindAll);
        CopyPasteManager.getInstance().setContents(new StringSelection(vmOption));

        String note = includes.isEmpty()
                ? " Set Includes in Settings → Tools → Deju Trace, then copy again."
                : "";
        // Both states are worth stating: a missing bind= is the silent failure, and an
        // unexpected one means the port is open wider than the developer may realise.
        String bindNote = bindAll
                ? "<br>Includes <code>bind=" + AgentVmOption.BIND_ALL_INTERFACES + "</code>"
                        + ", the agent will listen on every interface, and refuses to start"
                        + " without a token. Turn off “Traced JVM runs in a container or on"
                        + " another machine” in Settings for a local JVM."
                : "<br>Loopback only. If this JVM runs in a container, tick “Traced JVM runs in"
                        + " a container or on another machine” in Settings and copy again,"
                        + " or the IDE will not be able to reach it.";
        // The jar path and plugin version are shown explicitly because for anything the IDE
        // does not launch (docker-compose, WSL, a remote box), the plugin cannot refresh
        // the agent. Those setups have to re-copy this jar after every plugin update, so
        // they need to know where it is and which build it is.
        String where = agent == null
                ? ""
                : "<br>Agent jar (plugin " + DejuAgentBundle.pluginVersion() + "): <code>"
                        + agentPath + "</code>"
                        + "<br>If the traced JVM is not launched by this IDE, that jar has to reach"
                        + " the container or remote host again after every plugin update, a"
                        + " -javaagent is loaded once at process start. For Docker, pinning the"
                        + " agent version in your compose setup does this for you.";
        NotificationGroupManager.getInstance()
                .getNotificationGroup("Deju Trace")
                .createNotification("Deju Trace",
                        "Copied the agent VM option to the clipboard." + note + bindNote + where,
                        NotificationType.INFORMATION)
                .notify(project);
    }

    // --------------------------------------------------------- UiListener ---

    @Override
    public void onConnectionChanged(boolean connected, String info) {
        setStatus(info, connected ? OK_GREEN : null);
        updateButtons(connected);
    }

    @Override
    public void onHistoryChanged() {
        refreshHistory();
        refreshAgentFooter();
    }

    @Override
    public void onTargetChanged(String target) {
        if (!traceField.getText().equals(target)) {
            traceField.setText(target);
        }
    }

    /** The bottom strip: which agent is shipped, and which one actually recorded. */
    private JComponent buildAgentFooter() {
        agentLabel.setFont(JBUI.Fonts.smallFont());
        // The version line plus two buttons is far wider than a docked tool window, so this
        // row wraps rather than pushing a horizontal scrollbar across the whole panel.
        JBPanel<?> row = new JBPanel<>(new WrapLayout(FlowLayout.LEFT, 6, 2));
        row.setBorder(JBUI.Borders.emptyTop(6));
        row.add(agentLabel);
        row.add(refreshAgentButton);
        row.add(fixRunConfigsButton);

        refreshAgentButton.setToolTipText("Re-extract the agent this plugin ships, overwriting the copy on disk."
                + " Use after updating the plugin, then restart your application so it loads the new agent.");
        refreshAgentButton.addActionListener(e -> refreshBundledAgent());

        fixRunConfigsButton.setToolTipText("Point this project's run configurations at the current agent jar."
                + " Only Deju's own -javaagent flag is changed; any other agent is left alone.");
        fixRunConfigsButton.addActionListener(e -> updateRunConfigurations());

        refreshAgentFooter();
        return row;
    }

    /**
     * Forces the bundled agent back onto disk.
     *
     * <p>Extraction otherwise only happens while patching a run configuration, so a
     * hand-written {@code -javaagent} flag (a container, WSL, a pasted VM option) never
     * triggers it and can keep loading the previous version after a plugin update.
     */
    private void refreshBundledAgent() {
        java.nio.file.Path agent = DejuAgentBundle.refresh();
        if (agent == null) {
            Messages.showErrorDialog(project,
                    "This plugin build does not contain a bundled agent, so there is nothing to refresh.",
                    "Deju: No Bundled Agent");
            return;
        }
        refreshAgentFooter();
        Messages.showInfoMessage(project,
                "Agent " + DejuAgentBundle.pluginVersion() + " written to:\n" + agent
                        + "\n\nA -javaagent is loaded once when the JVM starts, so restart your"
                        + " application for it to take effect.",
                "Deju: Agent Refreshed");
    }

    /** Rewrites stale Deju agent paths in this project's run configurations. */
    private void updateRunConfigurations() {
        RunConfigAgentUpdater.Result result = RunConfigAgentUpdater.updateAll(project);
        if (result == null) {
            Messages.showErrorDialog(project,
                    "This plugin build does not contain a bundled agent, so there is no path to point at.",
                    "Deju: No Bundled Agent");
            return;
        }
        if (result.isEmpty()) {
            Messages.showInfoMessage(project,
                    "No run configuration in this project references the Deju agent.\n\n"
                            + "Use “Copy agent VM option” and paste it into your run configuration's"
                            + " VM options to have Deju attach.",
                    "Deju: Nothing to Update");
            return;
        }
        if (result.updated.isEmpty()) {
            Messages.showInfoMessage(project,
                    "Already up to date: " + String.join(", ", result.alreadyCurrent),
                    "Deju: Run Configurations");
            return;
        }
        Messages.showInfoMessage(project,
                "Updated the agent path in: " + String.join(", ", result.updated)
                        + (result.alreadyCurrent.isEmpty() ? ""
                        : "\nAlready current: " + String.join(", ", result.alreadyCurrent))
                        + "\n\nRestart your application so it loads the new agent.",
                "Deju: Run Configurations Updated");
    }

    /**
     * Repaints the agent footer. Amber when the loaded agent differs from the bundled one,
     * because that is the case where recordings quietly lose features and the fix (restart
     * the traced application) is not otherwise discoverable.
     */
    private void refreshAgentFooter() {
        String bundled = controller.bundledAgentVersion();
        String loaded = controller.loadedAgentVersion();

        if (loaded == null) {
            agentLabel.setText("Agent: bundled " + bundled + "  ·  loaded, (no run recorded yet)");
            agentLabel.setForeground(UIUtil.getContextHelpForeground());
            agentLabel.setToolTipText("The loaded version is read from a recording, so it"
                    + " appears after the first tracked run.");
            return;
        }
        boolean stale = !loaded.equals(bundled);
        agentLabel.setText("Agent: bundled " + bundled + "  ·  loaded " + loaded
                + (stale ? "  ⚠ restart the traced app" : ""));
        agentLabel.setForeground(stale ? WARN_AMBER : UIUtil.getContextHelpForeground());
        agentLabel.setToolTipText(stale
                ? "The traced JVM is running agent " + loaded + " but this plugin ships "
                        + bundled + ". A -javaagent is loaded once at process start, so restart"
                        + " the traced application. If the IDE does not launch it (docker-compose,"
                        + " WSL, a remote host), re-copy the agent jar first, see"
                        + " “Copy agent VM option”."
                : "The traced JVM is running the agent this plugin ships.");
    }

    private void setStatus(String text, JBColor color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color != null ? color : UIUtil.getLabelForeground());
    }

    private void updateButtons(boolean connected) {
        connectButton.setText(connected ? "Disconnect" : "Connect");
        connectButton.setIcon(connected ? AllIcons.Actions.Suspend : AllIcons.Nodes.Plugin);
        if (!connected && tracking) {
            setTracking(false); // a dropped connection ends any in-flight tracking
        }
        hostField.setEnabled(!connected);
        portField.setEnabled(!connected);
        validatePort();
        updateTrackEnabled();
        updateHistoryButtons();
    }

    /** Track enables when it can start (connected + a trace point) or stop (already tracking). */
    private void updateTrackEnabled() {
        trackButton.setEnabled(tracking
                || (controller.isConnected() && !traceField.getText().trim().isEmpty()));
    }

    private void updateHistoryButtons() {
        boolean hasSelection = historyList.getSelectedValue() != null;
        showButton.setEnabled(hasSelection);
        exportButton.setEnabled(hasSelection);
        deleteButton.setEnabled(hasSelection);
        deleteAllButton.setEnabled(!historyModel.isEmpty());
    }

    private void refreshHistory() {
        ExecutionEntry previouslySelected = historyList.getSelectedValue();
        historyModel.clear();
        List<ExecutionEntry> entries = DejuHistoryStore.getInstance(project).entries();
        for (ExecutionEntry entry : entries) {
            historyModel.addElement(entry);
        }
        if (previouslySelected != null) {
            for (int i = 0; i < historyModel.size(); i++) {
                if (historyModel.get(i).slot == previouslySelected.slot) {
                    historyList.setSelectedIndex(i);
                    break;
                }
            }
        }
        updateHistoryButtons();
    }

    private static String describe(ExecutionEntry entry) {
        String time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(entry.savedAtMillis));
        return time + ", " + entry.target
                + "   (" + entry.fileCount + " files, " + entry.lineCount + " lines)";
    }
}
