package org.deju.plugin.settings;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogPanel;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.panel.ComponentPanelBuilder;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import org.deju.plugin.DejuSettings;
import org.deju.plugin.exclude.DejuExclusions;
import org.deju.plugin.exclude.ExcludedTypesDialog;
import org.deju.plugin.exclude.TypeExclusionMatcher;
import org.deju.plugin.privacy.ClearDataDialog;
import org.deju.plugin.privacy.DataDisclosureDialog;
import org.deju.plugin.privacy.DejuDataInventory;
import org.deju.plugin.privacy.DejuReset;
import org.deju.plugin.ui.UiStyle;
import org.deju.plugin.ui.WidthTrackingPanel;
import org.deju.plugin.ui.WrapLayout;

/**
 * Settings page under <b>Preferences → Tools → Deju Trace</b>. Canonical, single home
 * for every {@link DejuSettings} property: socket {@code host}/{@code port}/{@code token},
 * the local-run auto-attach toggle and its {@code includes}. A "Reset to defaults" button
 * restores the shipped defaults. The tool window keeps only quick operational controls.
 *
 * <p>It also hosts the per-project exclusion list ({@link DejuExclusions}), which is why
 * this is a <i>project</i> configurable: type patterns describe one codebase's conventions
 * and are meaningless in the next project. Per-class ticking stays in
 * {@link ExcludedTypesDialog}, since listing the classes means reading recorded payloads
 * off disk, work that must not happen while a settings page is being built.
 */
public final class DejuConfigurable implements Configurable {

    private final Project project;

    private JPanel root;
    private JBTextField hostField;
    private JBTextField portField;
    private JBTextField tokenField;
    private JBCheckBox containerOrRemoteBox;
    private JBCheckBox autoAttachBox;
    private JBTextField includesField;
    private JBTextField sourceRootsField;
    private JBTextField maxOpenFilesField;
    private Map<String, JBCheckBox> genericBoxes;
    private JBTextArea customPatternsArea;

    public DejuConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Deju Trace";
    }

    @Override
    public @Nullable JComponent createComponent() {
        hostField = new JBTextField();
        portField = new JBTextField();
        tokenField = new JBTextField();
        containerOrRemoteBox = new JBCheckBox("Traced JVM runs in a container or on another machine");
        autoAttachBox = new JBCheckBox("Auto-attach agent to Java run configurations the IDE launches");
        includesField = new JBTextField();
        sourceRootsField = new JBTextField();
        maxOpenFilesField = new JBTextField();
        maxOpenFilesField.setColumns(5);

        JComponent hostHint = hint("Loopback for a local app; a mapped host/port for a"
                + " container or remote box (no socat bridge needed).");

        JComponent containerOrRemoteHint = indented(hint(
                "<html>Makes <b>Copy agent VM option</b> add <code>bind=0.0.0.0</code>, so the agent"
                        + " listens on every interface instead of loopback only, otherwise a published"
                        + " container port cannot reach it. Leave off for a JVM on this machine."
                        + "<br>The plugin cannot detect this: Docker publishes the port to the host, so"
                        + " the IDE connects to <code>localhost</code> either way.</html>"));

        JComponent autoAttachHint = indented(hint(
                "<html>Injects the bundled agent via <code>-javaagent</code> into local IDE runs only."
                        + " Containers started outside the IDE (e.g. docker-compose) are not affected"
                        + " and still need the agent flag in their own compose/env.</html>"));

        JComponent includesHint = hint("Package prefixes of the application you are tracing,"
                + " e.g. com.example (comma- or colon-separated).");

        JButton browseButton = UiStyle.compact(new JButton("Add source root…", AllIcons.Nodes.Folder));
        browseButton.setToolTipText("Choose a source root (e.g. …/module/src/main/java) and append it");
        browseButton.addActionListener(e -> chooseSourceRoot());
        JComponent sourceRootsHint = hint(
                "<html>Read source for the report/painter from these roots instead of the IDE index"
                        + " (semicolon-separated). Point at source matching the running build, or line"
                        + " numbers won't align. Empty = use the IDE index.</html>");
        JPanel sourceRootsRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 0));
        sourceRootsRow.add(sourceRootsField);
        sourceRootsField.setColumns(22);
        sourceRootsRow.add(browseButton);

        JComponent maxOpenFilesHint = hint(
                "<html>How many files <b>Show</b> opens for one run, in execution order, the traced"
                        + " method's own file first. Anything beyond the limit is named in a notification"
                        + " and stays in the exported report. 0 = open every file.</html>");

        JButton resetButton = UiStyle.compact(new JButton("Reset to defaults", AllIcons.General.Reset));
        resetButton.setToolTipText("Restore every field on this page, including the exclusion patterns");
        resetButton.addActionListener(e -> applyDefaultsToFields());
        JButton disclosureButton = UiStyle.compact(
                new JButton("What Deju records…", AllIcons.General.ShowInfos));
        disclosureButton.setToolTipText("Exactly what is stored, where, and what is never captured");
        disclosureButton.addActionListener(e -> DataDisclosureDialog.show(project));

        JButton clearDataButton = UiStyle.compact(
                new JButton("Clear Deju data…", AllIcons.Actions.GC));
        clearDataButton.setToolTipText("Delete recorded runs, rules, settings and the extracted agent");
        clearDataButton.addActionListener(e -> clearData());

        JPanel resetRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 0));
        resetRow.add(resetButton);
        resetRow.add(disclosureButton);
        resetRow.add(clearDataButton);

        DialogPanel form = new DialogPanel();
        FormBuilder builder = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("Host:"), hostField)
                .addComponentToRightColumn(hostHint)
                .addLabeledComponent(new JBLabel("Socket port:"), portField)
                .addLabeledComponent(new JBLabel("Token:"), tokenField)
                .addComponent(containerOrRemoteBox)
                .addComponent(containerOrRemoteHint)
                .addSeparator()
                .addComponent(autoAttachBox)
                .addComponent(autoAttachHint)
                .addLabeledComponent(new JBLabel("Includes:"), includesField)
                .addComponentToRightColumn(includesHint)
                .addLabeledComponent(new JBLabel("Source roots:"), sourceRootsRow)
                .addComponentToRightColumn(sourceRootsHint)
                .addSeparator()
                .addLabeledComponent(new JBLabel("Max files to open:"), maxOpenFilesField)
                .addComponentToRightColumn(maxOpenFilesHint);

        addExclusionSection(builder);

        builder.addSeparator()
                .addComponent(resetRow)
                .addComponentFillVertically(new JPanel(), 0);

        root = new WidthTrackingPanel(new BorderLayout());
        root.add(builder.getPanel(), BorderLayout.CENTER);
        root.setBorder(JBUI.Borders.empty(8));
        reset();
        return root;
    }

    /**
     * A wrapping help line under a field.
     *
     * <p>A plain {@link JBLabel} never wraps, so its preferred width is the whole sentence on
     * one line, and a settings page full of them is far wider than the dialog. The platform's
     * own comment component wraps at a sane measure and reports the height that wrapping
     * actually needs, which is the part a hand-rolled {@code <html>} label gets wrong.
     */
    private static JComponent hint(String text) {
        return ComponentPanelBuilder.createCommentComponent(
                text, true, ComponentPanelBuilder.MAX_COMMENT_WIDTH, true);
    }

    /** Aligns a hint under the checkbox it belongs to rather than the form's left edge. */
    private static JComponent indented(JComponent component) {
        component.setBorder(JBUI.Borders.emptyLeft(24));
        return component;
    }

    /**
     * The exclusion controls: the shipped patterns as ticks, plus a free-text box for this
     * project's own conventions.
     *
     * <p>Both halves of {@link TypeExclusionMatcher#GENERIC_PATTERNS} are shown under their
     * own captions, a glob that ends a class name and one that selects a package read
     * identically as a bare {@code *dto} / {@code *.dto.*} pair, and users pick the wrong
     * one when they are mixed into a single undifferentiated grid.
     */
    private void addExclusionSection(FormBuilder builder) {
        genericBoxes = new LinkedHashMap<>();

        JComponent intro = hint(
                "<html>Folded out of the report's <b>Essential</b> view and skipped by <b>Show</b>."
                        + " Nothing here changes what the agent records, the report's <b>Full</b> view"
                        + " still shows every frame. Matching is case-insensitive, and nested classes"
                        + " follow their outer class.</html>");

        customPatternsArea = new JBTextArea(4, 28);
        customPatternsArea.setLineWrap(false);
        JBScrollPane customScroll = new JBScrollPane(customPatternsArea);
        JComponent customHint = hint(
                "<html>One glob per line, e.g. <code>*summary</code> or <code>com.example.common.*</code>."
                        + " <code>*</code> matches any run of characters, dots included.</html>");

        JButton perClassButton = UiStyle.compact(
                new JButton("Per-class exclusions…", AllIcons.Actions.ListFiles));
        perClassButton.setToolTipText("Pick individual classes seen in recorded runs, overriding the patterns above");
        perClassButton.addActionListener(e -> ExcludedTypesDialog.show(project));
        JPanel perClassRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 0, 0));
        perClassRow.add(perClassButton);

        builder.addSeparator()
                .addComponent(new JBLabel("<html><b>Excluded types</b></html>"))
                .addComponent(intro)
                .addLabeledComponent(new JBLabel("By class name:"), patternGrid(false))
                .addLabeledComponent(new JBLabel("By package:"), patternGrid(true))
                .addLabeledComponent(new JBLabel("Custom patterns:"), customScroll)
                .addComponentToRightColumn(customHint)
                .addComponent(perClassRow);
    }

    /**
     * One tick per shipped glob. Package globs are the ones written {@code *.name.*}.
     *
     * <p>Wrapped rather than a fixed grid: a settings dialog narrowed to half the screen
     * would otherwise keep every tick on one line and push the page sideways.
     */
    private JPanel patternGrid(boolean packages) {
        JPanel grid = new JPanel(new WrapLayout(FlowLayout.LEFT, 12, 2));
        for (String glob : TypeExclusionMatcher.GENERIC_PATTERNS) {
            if (isPackageGlob(glob) != packages) {
                continue;
            }
            JBCheckBox box = new JBCheckBox(glob);
            genericBoxes.put(glob, box);
            grid.add(box);
        }
        return grid;
    }

    private static boolean isPackageGlob(String glob) {
        return glob.startsWith("*.") && glob.endsWith(".*");
    }

    @Override
    public boolean isModified() {
        DejuSettings s = DejuSettings.getInstance();
        return !hostField.getText().trim().equals(nullToEmpty(s.host))
                || !portField.getText().trim().equals(String.valueOf(s.port))
                || !tokenField.getText().equals(s.token)
                || containerOrRemoteBox.isSelected() != s.containerOrRemoteJvm
                || autoAttachBox.isSelected() != s.autoAttach
                || !includesField.getText().trim().equals(nullToEmpty(s.includes))
                || !sourceRootsField.getText().trim().equals(nullToEmpty(s.sourceRoots))
                || !maxOpenFilesField.getText().trim().equals(String.valueOf(s.maxOpenFiles))
                || exclusionsModified();
    }

    private boolean exclusionsModified() {
        DejuExclusions x = DejuExclusions.getInstance(project);
        // Ticks are compared as a set: the stored order came from whatever the dialog last
        // wrote, and re-ordering it is not a change the user made.
        return !new LinkedHashSet<>(selectedGenericPatterns()).equals(new LinkedHashSet<>(x.genericPatterns()))
                || !customPatternsArea.getText().trim().equals(x.customPatterns().trim());
    }

    private List<String> selectedGenericPatterns() {
        List<String> selected = new ArrayList<>();
        for (Map.Entry<String, JBCheckBox> e : genericBoxes.entrySet()) {
            if (e.getValue().isSelected()) {
                selected.add(e.getKey());
            }
        }
        return selected;
    }

    @Override
    public void apply() throws ConfigurationException {
        DejuSettings s = DejuSettings.getInstance();
        String host = hostField.getText().trim();
        if (host.isEmpty()) {
            throw new ConfigurationException("Host must not be empty (use 127.0.0.1 for a local app).");
        }
        String portText = portField.getText().trim();
        int parsedPort;
        try {
            parsedPort = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            throw new ConfigurationException("Port must be a number.");
        }
        if (parsedPort < 1 || parsedPort > 65535) {
            throw new ConfigurationException("Port must be between 1 and 65535.");
        }
        int parsedMax;
        try {
            parsedMax = Integer.parseInt(maxOpenFilesField.getText().trim());
        } catch (NumberFormatException e) {
            throw new ConfigurationException("Max files to open must be a number (0 for no limit).");
        }
        if (parsedMax < 0) {
            throw new ConfigurationException("Max files to open cannot be negative (0 means no limit).");
        }
        s.host = host;
        s.port = parsedPort;
        s.token = tokenField.getText();
        s.containerOrRemoteJvm = containerOrRemoteBox.isSelected();
        s.autoAttach = autoAttachBox.isSelected();
        s.includes = includesField.getText().trim();
        s.sourceRoots = sourceRootsField.getText().trim();
        s.maxOpenFiles = parsedMax;

        // The per-class lists belong to the dialog; carry them through untouched so applying
        // this page never discards decisions made there.
        DejuExclusions x = DejuExclusions.getInstance(project);
        x.update(selectedGenericPatterns(), customPatternsArea.getText(),
                x.excludedClasses(), x.keptClasses());
    }

    @Override
    public void reset() {
        DejuSettings s = DejuSettings.getInstance();
        hostField.setText(nullToEmpty(s.host));
        portField.setText(String.valueOf(s.port));
        tokenField.setText(s.token);
        containerOrRemoteBox.setSelected(s.containerOrRemoteJvm);
        autoAttachBox.setSelected(s.autoAttach);
        includesField.setText(nullToEmpty(s.includes));
        sourceRootsField.setText(nullToEmpty(s.sourceRoots));
        maxOpenFilesField.setText(String.valueOf(s.maxOpenFiles));

        DejuExclusions x = DejuExclusions.getInstance(project);
        setTicks(x.genericPatterns());
        customPatternsArea.setText(x.customPatterns());
    }

    private void setTicks(List<String> on) {
        Set<String> selected = new LinkedHashSet<>(on);
        for (Map.Entry<String, JBCheckBox> e : genericBoxes.entrySet()) {
            e.getValue().setSelected(selected.contains(e.getKey()));
        }
    }

    /**
     * Clears stored data, then re-reads the page.
     *
     * <p>Unlike "Reset to defaults", this one has already happened by the time it returns,
     * so the fields are refreshed from the settings rather than left showing values the user
     * would still have to apply. Anything that could not be deleted is named.
     */
    private void clearData() {
        DejuReset.Report report = ClearDataDialog.showAndClear(project);
        if (report == null) {
            return;
        }
        reset();
        if (report.clean()) {
            Messages.showInfoMessage(root,
                    report.filesDeleted == 0
                            ? "There was nothing stored to remove."
                            : "Removed " + report.filesDeleted + " file"
                                    + (report.filesDeleted == 1 ? "" : "s") + ", freeing "
                                    + DejuDataInventory.humanSize(report.bytesFreed) + ".",
                    "Deju Data Cleared");
        } else {
            Messages.showWarningDialog(root,
                    "Some files could not be removed:\n\n" + String.join("\n", report.failures)
                            + "\n\nA jar held open by a running traced JVM is the usual cause."
                            + " Stop it and clear again.",
                    "Deju Data Partly Cleared");
        }
    }

    /** Populates the fields with the shipped defaults; persisted only when the user applies. */
    private void applyDefaultsToFields() {
        hostField.setText(DejuSettings.DEFAULT_HOST);
        portField.setText(String.valueOf(DejuSettings.DEFAULT_PORT));
        tokenField.setText(DejuSettings.newToken());
        containerOrRemoteBox.setSelected(DejuSettings.DEFAULT_CONTAINER_OR_REMOTE_JVM);
        autoAttachBox.setSelected(DejuSettings.DEFAULT_AUTO_ATTACH);
        includesField.setText(DejuSettings.DEFAULT_INCLUDES);
        sourceRootsField.setText(DejuSettings.DEFAULT_SOURCE_ROOTS);
        maxOpenFilesField.setText(String.valueOf(DejuSettings.DEFAULT_MAX_OPEN_FILES));
        setTicks(TypeExclusionMatcher.DEFAULT_GENERIC_PATTERNS);
        customPatternsArea.setText("");
    }

    /** Opens a folder chooser and appends the picked directory to the source-roots field. */
    private void chooseSourceRoot() {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Select Source Root")
                .withDescription("Choose a source root, e.g. …/module/src/main/java");
        VirtualFile chosen = FileChooser.chooseFile(descriptor, project, null);
        if (chosen == null) {
            return;
        }
        String existing = sourceRootsField.getText().trim();
        String path = chosen.getPath();
        sourceRootsField.setText(existing.isEmpty() ? path : existing + ";" + path);
    }

    @Override
    public void disposeUIResources() {
        root = null;
        hostField = null;
        portField = null;
        tokenField = null;
        containerOrRemoteBox = null;
        autoAttachBox = null;
        includesField = null;
        sourceRootsField = null;
        maxOpenFilesField = null;
        genericBoxes = null;
        customPatternsArea = null;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
