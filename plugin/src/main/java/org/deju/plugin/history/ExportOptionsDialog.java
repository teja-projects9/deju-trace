package org.deju.plugin.history;

import java.awt.BorderLayout;

import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

/**
 * Asks what should go into the exported HTML: everything, or everything worth reading.
 *
 * <p>The choice is about <b>file size</b>, not about presentation. The report has always
 * been able to fold excluded types away on screen, but folding still ships their source in
 * the file, on a DTO-heavy trace that is most of the bytes, paid for on every download and
 * every time the file is mailed around. "Essential" leaves that source out of the document
 * altogether.
 *
 * <p>Essential is the default because an excluded type is, by definition, one the developer
 * already said they do not want to read.
 */
public final class ExportOptionsDialog extends DialogWrapper {

    private final JRadioButton essential = new JRadioButton("Essential, leave out excluded types");
    private final JRadioButton full = new JRadioButton("Full, every recorded call, with its source");
    private final int excludedCount;
    private final long approxSaving;

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
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        panel.setBorder(JBUI.Borders.empty(4, 8, 8, 8));

        panel.add(essential);
        panel.add(hint(excludedCount == 0
                ? "Nothing is excluded in this project, so this produces the same file as Full."
                : excludedCount + " excluded type(s) in this run, about "
                        + humanSize(approxSaving) + " of source left out. Their calls still"
                        + " appear in the tree with their timings; only the code is dropped."));

        panel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)));
        panel.add(full);
        panel.add(hint("Larger file. Needed only if you want the report's Full view to show"
                + " the source of entities, DTOs and generated builders."));

        JPanel root = new JPanel(new BorderLayout());
        root.add(panel, BorderLayout.CENTER);
        return root;
    }

    /** True when the export should leave excluded types' source out of the file. */
    public boolean isOmitExcluded() {
        return essential.isSelected();
    }

    private static JComponent hint(String text) {
        JBLabel label = new JBLabel("<html>" + text + "</html>");
        label.setForeground(UIUtil.getContextHelpForeground());
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
