package org.deju.plugin.history;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridLayout;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

/**
 * Asks what should go into the exported HTML, and how it should look when opened.
 *
 * <p><b>Content</b> is about <b>file size</b>. The report has always been able to fold
 * excluded types away on screen, but folding still ships their source in the file; on a
 * DTO-heavy trace that is most of the bytes, paid for on every download and every time the
 * file is mailed around. "Essential" leaves that source out of the document altogether, and
 * is the default, because an excluded type is by definition one the developer already said
 * they do not want to read.
 *
 * <p>Everything below Content is about <b>presentation</b>, and splits in two. Unchecking a
 * tab cuts its markup out of the file for good. Every other control only sets the position
 * the report opens in, which the reader can still change from the report's own Customize
 * panel. Dropping a tab is therefore worth doing to keep a report focused, not to shrink it:
 * all five tabs are drawn from the same embedded payload, so removing one removes its markup
 * and nothing else.
 *
 * <p>The defaults reproduce the report exactly as it behaved before these options existed, so
 * pressing Export without touching anything is not a change.
 */
public final class ExportOptionsDialog extends DialogWrapper {

    private final JRadioButton essential = new JRadioButton("Essential, leave out excluded types");
    private final JRadioButton full = new JRadioButton("Full, every recorded call, with its source");

    private final Map<String, JBCheckBox> tabBoxes = new LinkedHashMap<>();
    private final JBCheckBox showTime = new JBCheckBox("Time", true);
    private final JBCheckBox showPercent = new JBCheckBox("Percent", true);
    private final JBCheckBox showStep = new JBCheckBox("Step number", true);
    private final JBCheckBox showLine = new JBCheckBox("Line number", true);

    private final ComboBox<String> openTab = new ComboBox<>(new String[]{
            "Code Trace", "Graph", "Flow Graph", "Timeline", "Findings"});
    private final ComboBox<String> view = new ComboBox<>(new String[]{"Tree", "Files"});
    private final ComboBox<String> density = new ComboBox<>(new String[]{"Normal", "Compact"});
    private final ComboBox<String> theme = new ComboBox<>(new String[]{"Follow the reader's system", "Light", "Dark"});

    private final JBCheckBox sql = new JBCheckBox("Show SQL statements in the call tree", true);
    private final JBCheckBox groupRepeats = new JBCheckBox("Group repeated calls under the first one", false);
    private final JBCheckBox collapseTree = new JBCheckBox("Start the call tree folded to its top level", false);
    private final JBCheckBox collapseSections = new JBCheckBox("Start files and method sections folded", false);

    private final int excludedCount;
    private final long approxSaving;

    /** Tab ids in {@link ReportPrefs#ALL_TABS} order, paired with what the dialog calls them. */
    private static final String[][] TABS = {
            {"trace", "Code Trace"},
            {"graph", "Graph"},
            {"flow", "Flow Graph"},
            {"timeline", "Timeline"},
            {"findings", "Findings"},
    };

    /**
     * @param excludedCount how many recorded classes the project's exclusion list covers
     * @param approxSaving  bytes of source text those classes account for
     */
    public ExportOptionsDialog(Project project, int excludedCount, long approxSaving) {
        super(project, true);
        this.excludedCount = excludedCount;
        this.approxSaving = approxSaving;
        setTitle("Export Report");
        setOKButtonText("Export…");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        ButtonGroup group = new ButtonGroup();
        group.add(essential);
        group.add(full);
        essential.setSelected(true);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(JBUI.Borders.empty(4, 8, 8, 8));

        panel.add(heading("Content"));
        panel.add(essential);
        panel.add(hint(excludedCount == 0
                ? "Nothing is excluded in this project, so this produces the same file as Full."
                : excludedCount + " excluded type(s) in this run, about "
                        + humanSize(approxSaving) + " of source left out. Their calls still"
                        + " appear in the tree with their timings; only the code is dropped."));
        panel.add(full);
        panel.add(hint("Larger file. Needed only if you want the report's Full view to show"
                + " the source of entities, DTOs and generated builders."));

        panel.add(gap());
        panel.add(heading("Tabs to include"));
        JPanel tabs = new JPanel(new GridLayout(0, 3, JBUI.scale(8), 0));
        tabs.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (String[] tab : TABS) {
            JBCheckBox box = new JBCheckBox(tab[1], true);
            tabBoxes.put(tab[0], box);
            tabs.add(box);
        }
        panel.add(tabs);
        panel.add(hint("An unchecked tab is left out of the file and the reader cannot bring it"
                + " back. Every tab reads the same embedded data, so this keeps a report focused"
                + " rather than making it much smaller."));

        panel.add(gap());
        panel.add(heading("Columns to show"));
        JPanel cols = new JPanel(new GridLayout(0, 4, JBUI.scale(8), 0));
        cols.setAlignmentX(Component.LEFT_ALIGNMENT);
        cols.add(showTime);
        cols.add(showPercent);
        cols.add(showStep);
        cols.add(showLine);
        panel.add(cols);

        panel.add(gap());
        panel.add(heading("Opens on"));
        JPanel layout = new JPanel(new GridLayout(0, 4, JBUI.scale(6), JBUI.scale(4)));
        layout.setAlignmentX(Component.LEFT_ALIGNMENT);
        layout.add(new JBLabel("Tab"));
        layout.add(openTab);
        layout.add(new JBLabel("Code Trace view"));
        layout.add(view);
        layout.add(new JBLabel("Density"));
        layout.add(density);
        layout.add(new JBLabel("Theme"));
        layout.add(theme);
        panel.add(layout);

        panel.add(gap());
        panel.add(heading("Starting state"));
        panel.add(sql);
        panel.add(groupRepeats);
        panel.add(collapseTree);
        panel.add(collapseSections);
        panel.add(hint("These are starting positions only. The report's Customize panel can"
                + " change any of them, and remembers the reader's choice."));

        JPanel root = new JPanel(new BorderLayout());
        root.add(panel, BorderLayout.CENTER);
        return root;
    }

    /**
     * Refuses an export with no tabs at all: the file would open on a header and nothing else,
     * which is indistinguishable from a report that failed to generate.
     */
    @Override
    protected @Nullable ValidationInfo doValidate() {
        for (JBCheckBox box : tabBoxes.values()) {
            if (box.isSelected()) {
                return null;
            }
        }
        return new ValidationInfo("Keep at least one tab, or the report opens empty.",
                tabBoxes.values().iterator().next());
    }

    /** True when the export should leave excluded types' source out of the file. */
    public boolean isOmitExcluded() {
        return essential.isSelected();
    }

    /** What the reader gets, and what the report opens in. */
    public ReportPrefs prefs() {
        ReportPrefs p = new ReportPrefs();
        p.tabs = new LinkedHashSet<>();
        for (Map.Entry<String, JBCheckBox> e : tabBoxes.entrySet()) {
            if (e.getValue().isSelected()) {
                p.tabs.add(e.getKey());
            }
        }
        p.openTab = ReportPrefs.ALL_TABS.get(openTab.getSelectedIndex());
        p.view = view.getSelectedIndex() == 1 ? "files" : "tree";
        p.density = density.getSelectedIndex() == 1 ? "compact" : "normal";
        p.theme = switch (theme.getSelectedIndex()) {
            case 1 -> "light";
            case 2 -> "dark";
            default -> "auto";
        };
        p.showTime = showTime.isSelected();
        p.showPercent = showPercent.isSelected();
        p.showStep = showStep.isSelected();
        p.showLine = showLine.isSelected();
        p.sql = sql.isSelected();
        p.groupRepeats = groupRepeats.isSelected();
        p.collapseTree = collapseTree.isSelected();
        p.collapseSections = collapseSections.isSelected();
        return p;
    }

    private static JComponent heading(String text) {
        JBLabel label = new JBLabel("<html><b>" + text + "</b></html>");
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(JBUI.Borders.emptyBottom(2));
        return label;
    }

    private static Component gap() {
        return Box.createVerticalStrut(JBUI.scale(10));
    }

    /**
     * Explanatory small print. The width is pinned because Swing's HTML view lays a paragraph
     * out on one line given the chance, and one long hint would then set the dialog's width.
     */
    private static JComponent hint(String text) {
        JBLabel label = new JBLabel("<html><body style='width:" + JBUI.scale(420) + "px'>"
                + text + "</body></html>");
        label.setForeground(UIUtil.getContextHelpForeground());
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(JBUI.Borders.empty(0, 24, 0, 0));
        return label;
    }

    /** Deliberately approximate, and labelled as such, an exact figure would mean generating both. */
    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
