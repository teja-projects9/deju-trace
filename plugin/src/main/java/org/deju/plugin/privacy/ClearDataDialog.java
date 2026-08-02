package org.deju.plugin.privacy;

import java.awt.BorderLayout;
import java.util.List;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

/**
 * Confirms clearing Deju's data, by naming every item and its current size.
 *
 * <p>A "clear everything" button that opens a bare "are you sure?" asks the user to trust a
 * claim they cannot check. This lists what is actually on disk right now, read from
 * {@link DejuDataInventory}, so the list cannot drift away from what
 * {@link DejuReset} deletes.
 */
public final class ClearDataDialog extends DialogWrapper {

    private final List<DejuDataInventory.Item> items;
    private final JBCheckBox includeAppData =
            new JBCheckBox("Also reset connection settings and delete the extracted agent");

    private ClearDataDialog(@Nullable Project project, List<DejuDataInventory.Item> items) {
        super(project, true);
        this.items = items;
        setTitle("Clear Deju Data");
        setOKButtonText("Clear");
        includeAppData.setSelected(true);
        init();
    }

    /**
     * Shows the dialog and, if confirmed, performs the reset.
     *
     * @return the outcome, or {@code null} if the user cancelled
     */
    public static @Nullable DejuReset.Report showAndClear(@Nullable Project project) {
        List<DejuDataInventory.Item> items = DejuDataInventory.of(project);
        ClearDataDialog dialog = new ClearDataDialog(project, items);
        if (!dialog.showAndGet()) {
            return null;
        }
        return DejuReset.clearAll(project, dialog.includeAppData.isSelected());
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setBorder(JBUI.Borders.empty(4, 2, 2, 2));

        column.add(left(new JBLabel("This removes what Deju has stored and returns it to a"
                + " fresh install:")));
        column.add(Box.createVerticalStrut(JBUI.scale(8)));

        for (DejuDataInventory.Item item : items) {
            String size = item.exclusive
                    ? (item.files == 0 ? "nothing stored"
                            : item.files + (item.files == 1 ? " file, " : " files, ")
                                    + DejuDataInventory.humanSize(item.bytes))
                    : "entries removed from the IDE's workspace file";
            JBLabel line = new JBLabel("• " + item.label + ": " + size);
            column.add(left(line));

            if (item.path != null) {
                JBLabel where = new JBLabel(item.path.toString());
                where.setForeground(UIUtil.getContextHelpForeground());
                where.setFont(JBUI.Fonts.smallFont());
                where.setBorder(JBUI.Borders.emptyLeft(12));
                column.add(left(where));
            }
            column.add(Box.createVerticalStrut(JBUI.scale(4)));
        }

        column.add(Box.createVerticalStrut(JBUI.scale(4)));
        column.add(left(includeAppData));

        JBLabel caution = new JBLabel("<html>Resetting settings generates a new token, so any"
                + " hand-written <code>-javaagent</code> flag will need the VM option copied"
                + " again.</html>");
        caution.setForeground(UIUtil.getContextHelpForeground());
        caution.setFont(JBUI.Fonts.smallFont());
        caution.setBorder(JBUI.Borders.empty(2, 20, 0, 0));
        column.add(left(caution));

        column.add(Box.createVerticalStrut(JBUI.scale(10)));
        JBLabel keeps = new JBLabel("<html>Exported HTML reports are left alone: you chose"
                + " where those went.</html>");
        keeps.setForeground(UIUtil.getContextHelpForeground());
        keeps.setFont(JBUI.Fonts.smallFont());
        column.add(left(keeps));

        JPanel root = new JPanel(new BorderLayout());
        root.add(column, BorderLayout.CENTER);
        root.setPreferredSize(JBUI.size(560, 300));
        return root;
    }

    /** BoxLayout centres children by default, which reads as ragged text. */
    private static JComponent left(JComponent c) {
        c.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return c;
    }
}
