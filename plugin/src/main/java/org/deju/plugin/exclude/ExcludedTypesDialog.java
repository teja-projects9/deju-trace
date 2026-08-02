package org.deju.plugin.exclude;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.CheckBoxList;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;

/**
 * Lets the user choose which types the report folds away by default.
 *
 * <p>Three inputs, deliberately in this order: the classes actually seen in recordings,
 * a set of ready-made suffix patterns, and a free-text box for anything project-specific.
 * The list is the primary control because it shows real consequences, you can see the
 * invocation count you are about to remove from the report.
 *
 * <p>Nothing here changes what the agent records. The report always carries the full call
 * tree and its "Full" detail level shows every frame regardless of what is chosen here.
 */
public final class ExcludedTypesDialog extends DialogWrapper {

    private final Project project;
    private final List<ObservedTypes.Type> observed;

    /** Tick state by fully-qualified name, kept here because the list is rebuilt on search. */
    private final Map<String, Boolean> ticked = new LinkedHashMap<>();

    private final CheckBoxList<String> classList = new CheckBoxList<>();
    private final SearchTextField search = new SearchTextField(false);
    private final Map<String, JBCheckBox> genericBoxes = new LinkedHashMap<>();
    private final JTextArea customArea = new JTextArea(4, 40);
    private final JBLabel summary = new JBLabel();

    private ExcludedTypesDialog(Project project, List<ObservedTypes.Type> observed) {
        super(project, true);
        this.project = project;
        this.observed = observed;
        setTitle("Deju: Excluded Types");
        init();
    }

    /**
     * Loads the observed classes, then shows the dialog.
     *
     * <p>The scan parses stored payloads from disk, which must not happen on the EDT, so it
     * runs behind a modal progress indicator rather than freezing the tool window.
     */
    public static void show(Project project) {
        List<ObservedTypes.Type> observed = ProgressManager.getInstance()
                .runProcessWithProgressSynchronously(
                        () -> ObservedTypes.scan(project),
                        "Reading Deju Recordings…", true, project);
        if (observed == null) {
            return;   // cancelled
        }
        new ExcludedTypesDialog(project, observed).show();
    }

    // ------------------------------------------------------------------- ui ---

    @Override
    protected @Nullable JComponent createCenterPanel() {
        DejuExclusions state = DejuExclusions.getInstance(project);

        for (String fq : state.excludedClasses()) {
            ticked.put(fq, Boolean.TRUE);
        }
        customArea.setText(state.customPatterns());
        customArea.setLineWrap(false);

        List<String> savedGeneric = state.genericPatterns();
        JPanel genericGrid = new JPanel(new GridLayout(0, 4, 8, 2));
        for (String pattern : TypeExclusionMatcher.GENERIC_PATTERNS) {
            JBCheckBox box = new JBCheckBox(pattern, savedGeneric.contains(pattern));
            box.addActionListener(e -> refreshList());
            genericBoxes.put(pattern, box);
            genericGrid.add(box);
        }

        // A class the patterns already cover starts ticked, unless it was explicitly kept.
        List<String> kept = state.keptClasses();
        for (ObservedTypes.Type t : observed) {
            if (!ticked.containsKey(t.fqName)) {
                ticked.put(t.fqName, !kept.contains(t.fqName) && currentMatcher().isExcluded(t.fqName));
            }
        }

        search.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@Nullable DocumentEvent e) {
                refreshList();
            }
        });
        classList.setEmptyText("No recordings yet, run a trace, then come back");

        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(labelled("Types seen in your recordings", search), BorderLayout.NORTH);
        JBScrollPane listScroll = new JBScrollPane(classList);
        listScroll.setPreferredSize(new Dimension(560, 260));
        top.add(listScroll, BorderLayout.CENTER);

        JPanel listActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        listActions.add(button("Suggest", this::applySuggestion,
                "Tick classes that never call anything and expose only accessors"));
        listActions.add(button("All", () -> setAllVisible(true), "Tick every listed class"));
        listActions.add(button("None", () -> setAllVisible(false), "Untick every listed class"));
        listActions.add(summary);
        top.add(listActions, BorderLayout.SOUTH);

        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.add(labelled("Generic exclusions", genericGrid));
        bottom.add(Box.createVerticalStrut(8));
        JBScrollPane customScroll = new JBScrollPane(customArea);
        customScroll.setPreferredSize(new Dimension(560, 80));
        bottom.add(labelled("Custom patterns (one per line; * matches anything, case-insensitive)",
                customScroll));

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(JBUI.Borders.empty(8));
        root.add(top, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);

        refreshList();
        return root;
    }

    private static JPanel labelled(String text, JComponent component) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.add(new JBLabel(text), BorderLayout.NORTH);
        p.add(component, BorderLayout.CENTER);
        return p;
    }

    private JButton button(String text, Runnable action, String tooltip) {
        JButton b = new JButton(text);
        b.setToolTipText(tooltip);
        b.addActionListener(e -> action.run());
        return b;
    }

    /**
     * Rebuilds the visible rows for the current search text.
     *
     * <p>Tick state lives in {@link #ticked} rather than in the list, so filtering the view
     * never loses a choice made about a row that is momentarily scrolled out of the query.
     */
    private void refreshList() {
        captureTicks();
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        TypeExclusionMatcher matcher = currentMatcher();

        List<String> visible = new ArrayList<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (ObservedTypes.Type t : observed) {
            if (!query.isEmpty() && !t.fqName.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            visible.add(t.fqName);
            String via = matcher.matchingPattern(t.fqName);
            labels.put(t.fqName, t.fqName
                    + (t.invocations > 0 ? "   " + t.invocations + "×" : "")
                    + (via != null ? ", via " + via : ""));
        }

        classList.setItems(visible, labels::get);
        for (String fq : visible) {
            classList.setItemSelected(fq, Boolean.TRUE.equals(ticked.get(fq)));
        }

        int on = 0;
        for (Boolean v : ticked.values()) {
            if (Boolean.TRUE.equals(v)) {
                on++;
            }
        }
        summary.setText("   " + on + " of " + observed.size() + " types excluded");
    }

    /** Copies the list's own tick state back into {@link #ticked} before it is rebuilt. */
    private void captureTicks() {
        for (int i = 0; i < classList.getItemsCount(); i++) {
            String fq = classList.getItemAt(i);
            if (fq != null) {
                ticked.put(fq, classList.isItemSelected(i));
            }
        }
    }

    private void setAllVisible(boolean on) {
        captureTicks();
        for (int i = 0; i < classList.getItemsCount(); i++) {
            String fq = classList.getItemAt(i);
            if (fq != null) {
                ticked.put(fq, on);
            }
        }
        refreshList();
    }

    private void applySuggestion() {
        captureTicks();
        for (ObservedTypes.Type t : observed) {
            if (t.suggested()) {
                ticked.put(t.fqName, Boolean.TRUE);
            }
        }
        refreshList();
    }

    /** A matcher over what the dialog currently shows, not over what was last saved. */
    private TypeExclusionMatcher currentMatcher() {
        return new TypeExclusionMatcher(currentPatterns(), List.of(), List.of());
    }

    private List<String> currentPatterns() {
        List<String> patterns = new ArrayList<>();
        for (Map.Entry<String, JBCheckBox> e : genericBoxes.entrySet()) {
            if (e.getValue().isSelected()) {
                patterns.add(e.getKey());
            }
        }
        patterns.addAll(TypeExclusionMatcher.parsePatternText(customArea.getText()));
        return patterns;
    }

    // ---------------------------------------------------------------- apply ---

    @Override
    protected void doOKAction() {
        captureTicks();
        // Only the disagreements with the patterns are stored; see the method's javadoc for
        // why recording the agreements too would be actively harmful.
        TypeExclusionMatcher.Decisions decided =
                TypeExclusionMatcher.decisions(ticked, currentMatcher());

        List<String> generic = new ArrayList<>();
        for (Map.Entry<String, JBCheckBox> e : genericBoxes.entrySet()) {
            if (e.getValue().isSelected()) {
                generic.add(e.getKey());
            }
        }

        DejuExclusions.getInstance(project)
                .update(generic, customArea.getText(), decided.excluded, decided.kept);
        super.doOKAction();
    }
}
