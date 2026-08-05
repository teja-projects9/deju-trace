package org.deju.plugin.exclude;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
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

import org.deju.plugin.ui.WrapLayout;

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
        JPanel genericGrid = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 2));
        for (String pattern : TypeExclusionMatcher.GENERIC_PATTERNS) {
            JBCheckBox box = new JBCheckBox(pattern, savedGeneric.contains(pattern));
            box.addActionListener(e -> captureThenRefresh());
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
                captureThenRefresh();
            }
        });
        classList.setEmptyText("No recordings yet, run a trace, then come back");

        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(labelled("Types seen in your recordings", search), BorderLayout.NORTH);
        JBScrollPane listScroll = new JBScrollPane(classList);
        listScroll.setPreferredSize(JBUI.size(560, 260));
        top.add(listScroll, BorderLayout.CENTER);

        JPanel listActions = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
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
        customScroll.setPreferredSize(JBUI.size(560, 80));
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
     * Re-renders after something that did not itself change the ticks.
     *
     * <p>A search keystroke or a generic-pattern box has no opinion about individual rows, so
     * whatever the user last clicked on the rows themselves has to be read off the list before
     * it is rebuilt, or it would be dropped.
     */
    private void captureThenRefresh() {
        captureTicks();
        refreshList();
    }

    /**
     * Renders the visible rows from {@link #ticked} for the current search text.
     *
     * <p>Tick state lives in {@link #ticked} rather than in the list, so filtering the view
     * never loses a choice made about a row that is momentarily scrolled out of the query.
     *
     * <p><b>Strictly one-way, {@link #ticked} to the list.</b> This used to begin by capturing
     * the list back into the map, which quietly undid every caller that had just finished
     * writing to it: "All", "None" and "Suggest" each set the map, called this, and had their
     * new values overwritten by the stale ticks still sitting in the widget. Callers that need
     * the capture ask for it by name, via {@link #captureThenRefresh()}.
     */
    private void refreshList() {
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
            labels.put(t.fqName, order(t) + t.fqName
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

    /**
     * The row's position in the most recent run, right-padded so the class names line up.
     *
     * <p>The same number the file has as an editor tab and in the report, which is the point
     * of showing it: the run reads top-to-bottom, so once the interesting frames are behind
     * you everything below is a candidate to fold away. A class the latest run never touched
     * gets a dash rather than a number it did not earn.
     */
    private String order(ObservedTypes.Type t) {
        // Widened to the longest number in this run, so 100+ classes still line up.
        int digits = Math.max(2, String.valueOf(observed.size()).length());
        if (t.executionOrder == ObservedTypes.NO_ORDER) {
            // FIGURE SPACE is a digit wide, unlike a normal space, so the dashes hold the
            // same column as the numbers above them.
            return "—" + "\u2007".repeat(digits) + "   ";
        }
        // Zero-padded rather than space-padded: the list font is proportional and its spaces
        // are narrower than its digits, so trailing spaces would leave the names ragged.
        return "#" + String.format("%0" + digits + "d", t.executionOrder) + "   ";
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
