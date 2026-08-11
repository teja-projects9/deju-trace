package org.deju.plugin.exclude;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.CheckBoxList;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import org.deju.plugin.source.TypeNavigator;
import org.deju.plugin.ui.WrapLayout;

/**
 * Lets the user choose which types the report folds away by default.
 *
 * <p>Two ways in, because the two are good at different things and neither replaces the
 * other:
 *
 * <ul>
 *   <li><b>By class</b>, the precise one. You see the invocation count you are about to
 *   remove from the report, and a decision here outranks every pattern, so one class can be
 *   rescued from a rule that is otherwise doing its job.</li>
 *   <li><b>By package</b>, the durable one. Ticking {@code com.acme.dto} states an
 *   intention that stays true as the codebase grows, where forty ticked class names are out
 *   of date the moment someone writes the forty-first.</li>
 * </ul>
 *
 * <p>Both tabs feed one rule set, so the class tab shows package ticks taking effect
 * immediately, and unticking one class inside a ticked package keeps that class visible.
 *
 * <p>Underneath sit the ready-made suffix patterns and a free-text box, which apply
 * whatever tab is in front.
 *
 * <p>Nothing here changes what the agent records. The report always carries the full call
 * tree and its "Full" detail level shows every frame regardless of what is chosen here.
 */
public final class ExcludedTypesDialog extends DialogWrapper {

    private final Project project;
    private final List<ObservedTypes.Type> observed;
    private final Map<String, ObservedTypes.Type> byName = new LinkedHashMap<>();
    private final List<PackageGroups.Group> packages;
    private final Map<String, PackageGroups.Group> packagesByName = new LinkedHashMap<>();

    /**
     * Whether the dialog blocks its parent, which decides what "open in the editor" can do.
     *
     * <p>Opened from the tool window it is modeless, so a class can be opened with the
     * dialog left standing, which is the whole point of being able to jump from the list.
     * Opened from the Settings dialog it must be modal, that dialog already is, and a
     * modeless child of a modal parent is a window the user cannot reach; there, jumping
     * applies the choices and closes, so the editor is not left behind two dialogs.
     */
    private final boolean modal;

    /** Tick state by fully-qualified name, kept here because the list is rebuilt on search. */
    private final Map<String, Boolean> ticked = new LinkedHashMap<>();

    /**
     * Classes the user has an opinion about, as opposed to ones simply following the rules.
     *
     * <p>Without this the dialog quietly sabotages its own patterns: tick {@code *dto} and
     * every DTO already in the list is still rendered unticked, so applying writes each one
     * into {@code keptClasses} and the brand-new pattern is overridden for exactly the
     * classes it was meant for. Rows nobody has touched follow the rules and are recomputed
     * whenever the rules change; rows the user clicked stay put.
     */
    private final Set<String> userDecided = new LinkedHashSet<>();

    /** Ticked packages, as dotted names; each contributes a {@code pkg.*} glob. */
    private final Set<String> tickedPackages = new LinkedHashSet<>();

    private final CheckBoxList<String> classList = new CheckBoxList<>();
    private final CheckBoxList<String> packageList = new CheckBoxList<>();
    private final SearchTextField classSearch = new SearchTextField(false);
    private final SearchTextField packageSearch = new SearchTextField(false);
    private final Map<String, JBCheckBox> genericBoxes = new LinkedHashMap<>();
    private final JTextArea customArea = new JTextArea(4, 40);
    private final JBLabel classSummary = new JBLabel();
    private final JBLabel packageSummary = new JBLabel();
    private final JButton openClassButton =
            new JButton("Open in Editor", AllIcons.Actions.EditSource);
    private final JButton openPackageButton =
            new JButton("Open First Class", AllIcons.Actions.EditSource);

    /** What each visible row was last rendered as; see {@link #captureTicks()}. */
    private final Map<String, Boolean> lastRendered = new LinkedHashMap<>();

    /** True while a list is being rebuilt, so programmatic ticks are not read as clicks. */
    private boolean rendering;

    private ExcludedTypesDialog(Project project, List<ObservedTypes.Type> observed, boolean modal) {
        super(project, true);
        this.project = project;
        this.observed = observed;
        this.modal = modal;
        this.packages = PackageGroups.of(observed);
        for (ObservedTypes.Type t : observed) {
            byName.put(t.fqName, t);
        }
        for (PackageGroups.Group g : packages) {
            packagesByName.put(g.name, g);
        }
        setTitle("Deju: Excluded Types");
        setModal(modal);
        init();
    }

    /**
     * Loads the observed classes, then shows the dialog modelessly.
     *
     * <p>The scan parses stored payloads from disk, which must not happen on the EDT, so it
     * runs behind a modal progress indicator rather than freezing the tool window.
     */
    public static void show(Project project) {
        show(project, false);
    }

    /**
     * As {@link #show(Project)}, but {@code modal} must be {@code true} when the caller is
     * itself inside a modal dialog. See {@link #modal}.
     */
    public static void show(Project project, boolean modal) {
        List<ObservedTypes.Type> observed = ProgressManager.getInstance()
                .runProcessWithProgressSynchronously(
                        () -> ObservedTypes.scan(project),
                        "Reading Deju Recordings…", true, project);
        if (observed == null) {
            return;   // cancelled
        }
        new ExcludedTypesDialog(project, observed, modal).show();
    }

    // ------------------------------------------------------------------- ui ---

    @Override
    protected @Nullable JComponent createCenterPanel() {
        DejuExclusions state = DejuExclusions.getInstance(project);

        // Saved per-class decisions are decisions, so they are exempt from being recomputed
        // when the patterns change, exactly as a click in this session would be.
        for (String fq : state.excludedClasses()) {
            ticked.put(fq, Boolean.TRUE);
            userDecided.add(fq);
        }
        for (String fq : state.keptClasses()) {
            ticked.putIfAbsent(fq, Boolean.FALSE);
            userDecided.add(fq);
        }
        tickedPackages.addAll(state.excludedPackages());
        customArea.setText(state.customPatterns());
        customArea.setLineWrap(false);
        customArea.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@Nullable DocumentEvent e) {
                patternsChanged();
            }
        });

        List<String> savedGeneric = state.genericPatterns();
        JPanel genericGrid = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 2));
        for (String pattern : TypeExclusionMatcher.GENERIC_PATTERNS) {
            JBCheckBox box = new JBCheckBox(pattern, savedGeneric.contains(pattern));
            box.addActionListener(e -> patternsChanged());
            genericBoxes.put(pattern, box);
            genericGrid.add(box);
        }

        // Everything nobody has an opinion about starts wherever the saved rules put it.
        applyPatternsToUndecided();

        JBTabbedPane tabs = new JBTabbedPane();
        tabs.addTab("By class", classTab());
        tabs.addTab("By package", packageTab());

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
        root.add(tabs, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);

        refreshLists();
        return root;
    }

    private JComponent classTab() {
        classSearch.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@Nullable DocumentEvent e) {
                captureThenRefresh();
            }
        });
        classList.setEmptyText("No recordings yet, run a trace, then come back");
        classList.setCheckBoxListListener(this::classToggled);
        navigateOnDoubleClick(classList, this::openSelectedClass);

        JPanel tab = new JPanel(new BorderLayout(0, 4));
        tab.setBorder(JBUI.Borders.empty(8, 0, 0, 0));
        tab.add(labelled("Types seen in your recordings", classSearch), BorderLayout.NORTH);
        JBScrollPane listScroll = new JBScrollPane(classList);
        listScroll.setPreferredSize(JBUI.size(560, 260));
        tab.add(listScroll, BorderLayout.CENTER);

        JPanel actions = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        actions.add(button("Suggest", this::applySuggestion,
                "Tick classes that never call anything and expose only accessors"));
        actions.add(button("All", () -> setAllVisible(true), "Tick every listed class"));
        actions.add(button("None", () -> setAllVisible(false), "Untick every listed class"));
        actions.add(openButton(openClassButton, classList, this::openSelectedClass,
                "Open the selected class at the method the run entered it through"
                        + " (or double-click it)"));
        actions.add(classSummary);
        tab.add(actions, BorderLayout.SOUTH);
        return tab;
    }

    private JComponent packageTab() {
        packageSearch.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@Nullable DocumentEvent e) {
                refreshPackageList();
            }
        });
        packageList.setEmptyText("No recordings yet, run a trace, then come back");
        packageList.setCheckBoxListListener(this::packageToggled);
        navigateOnDoubleClick(packageList, this::openSelectedPackage);

        JPanel tab = new JPanel(new BorderLayout(0, 4));
        tab.setBorder(JBUI.Borders.empty(8, 0, 0, 0));
        tab.add(labelled("Packages seen in your recordings (a tick covers sub-packages too)",
                packageSearch), BorderLayout.NORTH);
        JBScrollPane listScroll = new JBScrollPane(packageList);
        listScroll.setPreferredSize(JBUI.size(560, 260));
        tab.add(listScroll, BorderLayout.CENTER);

        JPanel actions = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        actions.add(button("All", () -> setAllPackages(true), "Tick every listed package"));
        actions.add(button("None", () -> setAllPackages(false), "Untick every listed package"));
        actions.add(openButton(openPackageButton, packageList, this::openSelectedPackage,
                "Open the first class this package contributed to the run (or double-click it)"));
        actions.add(packageSummary);
        tab.add(actions, BorderLayout.SOUTH);
        return tab;
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

    /** An action button that is only meaningful while a row is selected. */
    private JButton openButton(JButton b, CheckBoxList<String> list, Runnable action,
                               String tooltip) {
        b.setToolTipText(tooltip);
        b.setEnabled(false);
        b.addActionListener(e -> action.run());
        list.addListSelectionListener(e -> b.setEnabled(list.getSelectedIndex() >= 0));
        return b;
    }

    /**
     * Makes a double-click on a row jump to its source.
     *
     * <p>A double-click on the checkbox itself toggles twice and so changes nothing, which
     * is why the gesture is safe to use on a list whose single click already means
     * something. There is no Enter binding on purpose: in a {@link DialogWrapper} that key
     * belongs to the default button, and quietly stealing it would be a worse surprise than
     * having one fewer shortcut.
     */
    private void navigateOnDoubleClick(CheckBoxList<String> list, Runnable action) {
        new DoubleClickListener() {
            @Override
            protected boolean onDoubleClick(MouseEvent event) {
                if (list.getSelectedIndex() < 0) {
                    return false;
                }
                action.run();
                return true;
            }
        }.installOn(list);
    }

    // ------------------------------------------------------------ rendering ---

    /**
     * Re-renders after something that did not itself change the ticks.
     *
     * <p>A search keystroke has no opinion about individual rows, so whatever the user last
     * clicked on the rows themselves has to be read off the list before it is rebuilt, or it
     * would be dropped.
     */
    private void captureThenRefresh() {
        captureTicks();
        refreshLists();
    }

    /** Re-derives every undecided row from the new rule set, then re-renders both tabs. */
    private void patternsChanged() {
        captureTicks();
        applyPatternsToUndecided();
        refreshLists();
    }

    private void applyPatternsToUndecided() {
        TypeExclusionMatcher matcher = currentMatcher();
        for (ObservedTypes.Type t : observed) {
            if (!userDecided.contains(t.fqName)) {
                ticked.put(t.fqName, matcher.isExcluded(t.fqName));
            }
        }
    }

    private void refreshLists() {
        refreshClassList();
        refreshPackageList();
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
    private void refreshClassList() {
        String query = classSearch.getText().trim().toLowerCase(Locale.ROOT);
        TypeExclusionMatcher matcher = currentMatcher();

        List<String> visible = new ArrayList<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (ObservedTypes.Type t : observed) {
            if (!query.isEmpty() && !t.fqName.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            visible.add(t.fqName);
            String via = matcher.matchingPattern(t.fqName);
            labels.put(t.fqName, orderPrefix(t.executionOrder, observed.size()) + t.fqName
                    + (t.invocations > 0 ? "   " + t.invocations + "×" : "")
                    + (via != null ? ", via " + via : ""));
        }

        String wasSelected = selectedValue(classList);
        rendering = true;
        try {
            classList.setItems(visible, labels::get);
            lastRendered.clear();
            for (String fq : visible) {
                boolean on = Boolean.TRUE.equals(ticked.get(fq));
                classList.setItemSelected(fq, on);
                lastRendered.put(fq, on);
            }
        } finally {
            rendering = false;
        }
        reselect(classList, visible, wasSelected);
        openClassButton.setEnabled(classList.getSelectedIndex() >= 0);
        refreshSummaries();
    }

    private void refreshPackageList() {
        String query = packageSearch.getText().trim().toLowerCase(Locale.ROOT);
        TypeExclusionMatcher matcher = currentMatcher();

        List<String> visible = new ArrayList<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (PackageGroups.Group g : packages) {
            if (!query.isEmpty() && !g.name.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            visible.add(g.name);
            labels.put(g.name, orderPrefix(g.firstOrder, packages.size()) + g.name
                    + "   " + g.classes.size() + (g.classes.size() == 1 ? " class" : " classes")
                    + (g.invocations > 0 ? ", " + g.invocations + "×" : "")
                    + coverageNote(g, matcher));
        }

        String wasSelected = selectedValue(packageList);
        rendering = true;
        try {
            packageList.setItems(visible, labels::get);
            for (String pkg : visible) {
                packageList.setItemSelected(pkg, tickedPackages.contains(pkg));
            }
        } finally {
            rendering = false;
        }
        reselect(packageList, visible, wasSelected);
        openPackageButton.setEnabled(packageList.getSelectedIndex() >= 0);
        refreshSummaries();
    }

    @Nullable
    private static String selectedValue(CheckBoxList<String> list) {
        int index = list.getSelectedIndex();
        return index < 0 ? null : list.getItemAt(index);
    }

    /**
     * Puts the highlight back on the row it was on before the list was rebuilt.
     *
     * <p>{@code setItems} replaces the model, so without this every re-render, which
     * includes every tick of a package, drops the selection, greys out the open button and
     * loses the user's place in a list they were reading.
     */
    private static void reselect(CheckBoxList<String> list, List<String> visible,
                                 @Nullable String value) {
        int index = value == null ? -1 : visible.indexOf(value);
        if (index >= 0) {
            list.setSelectedIndex(index);
        }
    }

    /**
     * How much of a package the other rules already hide, or {@code ""} when none do.
     *
     * <p>Ticking a package whose classes are all covered by {@code *dto} anyway changes
     * nothing, and a list that does not say so invites the user to do it. Suppressed for a
     * ticked package, whose own glob would otherwise report itself.
     */
    private String coverageNote(PackageGroups.Group group, TypeExclusionMatcher matcher) {
        if (tickedPackages.contains(group.name)) {
            return "";
        }
        int covered = 0;
        String glob = null;
        boolean oneGlob = true;
        for (String fq : group.classes) {
            String via = matcher.matchingPattern(fq);
            if (via == null) {
                continue;
            }
            covered++;
            if (glob == null) {
                glob = via;
            } else if (!glob.equals(via)) {
                oneGlob = false;
            }
        }
        if (covered == 0) {
            return "";
        }
        if (covered == group.classes.size()) {
            return oneGlob ? ", all via " + glob : ", all already hidden";
        }
        return ", " + covered + " already hidden";
    }

    /**
     * A row's position in the most recent run, right-padded so the names line up.
     *
     * <p>The same number the file has as an editor tab and in the report, which is the point
     * of showing it: the run reads top-to-bottom, so once the interesting frames are behind
     * you everything below is a candidate to fold away. A row the latest run never touched
     * gets a dash rather than a number it did not earn.
     */
    private static String orderPrefix(int order, int total) {
        // Widened to the longest number in this run, so 100+ rows still line up.
        int digits = Math.max(2, String.valueOf(total).length());
        if (order == ObservedTypes.NO_ORDER) {
            // FIGURE SPACE is a digit wide, unlike a normal space, so the dashes hold the
            // same column as the numbers above them.
            return "—" + " ".repeat(digits) + "   ";
        }
        // Zero-padded rather than space-padded: the list font is proportional and its spaces
        // are narrower than its digits, so trailing spaces would leave the names ragged.
        return "#" + String.format("%0" + digits + "d", order) + "   ";
    }

    private void refreshSummaries() {
        // Counted over what is on screen, not over the whole map: a class excluded in an
        // older session but absent from every stored run is still carried in `ticked` (and
        // still saved), and counting it would print "40 of 37".
        int on = 0;
        for (ObservedTypes.Type t : observed) {
            if (Boolean.TRUE.equals(ticked.get(t.fqName))) {
                on++;
            }
        }
        classSummary.setText("   " + on + " of " + observed.size() + " types excluded");
        packageSummary.setText("   " + tickedPackages.size() + " of " + packages.size()
                + " packages excluded");
    }

    // ------------------------------------------------------------- editing ---

    /** A row the user clicked; its verdict is now theirs, not the rules'. */
    private void classToggled(int index, boolean value) {
        if (rendering) {
            return;
        }
        String fq = classList.getItemAt(index);
        if (fq == null) {
            return;
        }
        ticked.put(fq, value);
        lastRendered.put(fq, value);
        userDecided.add(fq);
        refreshSummaries();
    }

    private void packageToggled(int index, boolean value) {
        if (rendering) {
            return;
        }
        String pkg = packageList.getItemAt(index);
        if (pkg == null) {
            return;
        }
        if (value) {
            tickedPackages.add(pkg);
        } else {
            tickedPackages.remove(pkg);
        }
        // A package tick is a rule, so it moves every class that has no opinion of its own.
        // Deferred because the re-render replaces this list's model, and we are still inside
        // the click that the platform is handling against the old one.
        ApplicationManager.getApplication()
                .invokeLater(this::patternsChanged, ModalityState.stateForComponent(packageList));
    }

    /**
     * Copies the list's own tick state back into {@link #ticked} before it is rebuilt.
     *
     * <p>Also the backstop for {@link #userDecided}: anything the widget now reports
     * differently from what {@link #refreshClassList()} last wrote there was changed by the
     * user, whether or not the platform's listener told us about it.
     */
    private void captureTicks() {
        for (int i = 0; i < classList.getItemsCount(); i++) {
            String fq = classList.getItemAt(i);
            if (fq == null) {
                continue;
            }
            boolean on = classList.isItemSelected(i);
            if (on != Boolean.TRUE.equals(lastRendered.get(fq))) {
                userDecided.add(fq);
            }
            ticked.put(fq, on);
        }
    }

    private void setAllVisible(boolean on) {
        captureTicks();
        for (int i = 0; i < classList.getItemsCount(); i++) {
            String fq = classList.getItemAt(i);
            if (fq != null) {
                ticked.put(fq, on);
                userDecided.add(fq);
            }
        }
        refreshLists();
    }

    private void setAllPackages(boolean on) {
        for (int i = 0; i < packageList.getItemsCount(); i++) {
            String pkg = packageList.getItemAt(i);
            if (pkg == null) {
                continue;
            }
            if (on) {
                tickedPackages.add(pkg);
            } else {
                tickedPackages.remove(pkg);
            }
        }
        patternsChanged();
    }

    private void applySuggestion() {
        captureTicks();
        for (ObservedTypes.Type t : observed) {
            if (t.suggested()) {
                ticked.put(t.fqName, Boolean.TRUE);
                userDecided.add(t.fqName);
            }
        }
        refreshLists();
    }

    // ---------------------------------------------------------- navigation ---

    private void openSelectedClass() {
        int index = classList.getSelectedIndex();
        String fq = index < 0 ? null : classList.getItemAt(index);
        if (fq != null) {
            navigateTo(byName.get(fq));
        }
    }

    /**
     * Opens the first class this package contributed to the run.
     *
     * <p>A package is not a thing you can put a caret in, so the useful answer is the class
     * that made the package show up in the first place, the earliest one in execution order,
     * which is where a reader following the trace would arrive anyway.
     */
    private void openSelectedPackage() {
        int index = packageList.getSelectedIndex();
        String pkg = index < 0 ? null : packageList.getItemAt(index);
        PackageGroups.Group group = pkg == null ? null : packagesByName.get(pkg);
        if (group == null || group.classes.isEmpty()) {
            return;
        }
        ObservedTypes.Type best = null;
        for (String fq : group.classes) {
            ObservedTypes.Type t = byName.get(fq);
            if (t == null) {
                continue;
            }
            boolean earlier = best == null
                    || (t.executionOrder != ObservedTypes.NO_ORDER
                        && (best.executionOrder == ObservedTypes.NO_ORDER
                            || t.executionOrder < best.executionOrder));
            if (earlier) {
                best = t;
            }
        }
        navigateTo(best);
    }

    /**
     * Opens {@code type} at its entry method, closing first when that is the only way the
     * user could see it (see {@link #modal}).
     */
    private void navigateTo(@Nullable ObservedTypes.Type type) {
        if (type == null) {
            return;
        }
        boolean opened = TypeNavigator.open(project, type.fqName, type.sourceFileName,
                type.entryMethod, type.entryLine);
        if (!opened) {
            reportNoSource(type.fqName);
            return;
        }
        if (modal) {
            // The tab is now open behind a dialog the user cannot see past, so a jump that
            // left this open would read as a button that did nothing. Get out of the way,
            // keeping their choices rather than discarding them on the way out.
            captureTicks();
            save();
            close(OK_EXIT_CODE);
        }
    }

    private void reportNoSource(String fqName) {
        Messages.showWarningDialog(project,
                "No source for " + fqName + " in this project.\n\n"
                        + "It was recorded from a dependency, or the module holding it is not"
                        + " open. Add its directory under Settings | Tools | Deju Trace |"
                        + " Source roots to open it anyway.",
                "Deju: Cannot Open Class");
    }

    // ---------------------------------------------------------------- apply ---

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
        for (String pkg : tickedPackages) {
            patterns.add(PackageGroups.globFor(pkg));
        }
        patterns.addAll(TypeExclusionMatcher.parsePatternText(customArea.getText()));
        return patterns;
    }

    @Override
    protected void doOKAction() {
        captureTicks();
        save();
        super.doOKAction();
    }

    private void save() {
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

        DejuExclusions.getInstance(project).update(generic, customArea.getText(),
                decided.excluded, decided.kept, new ArrayList<>(tickedPackages));
    }
}
