package org.deju.plugin.privacy;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.swing.Action;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import org.deju.plugin.ui.UiStyle;

/**
 * A plain answer to "what is this plugin recording about me, and where does it go?"
 *
 * <p>Read-only, and built from the same {@link DejuDataInventory} the clear button uses, so
 * a new kind of stored data cannot appear in one place and not the other. The paths are
 * real and openable: a claim the user can check beats a claim they have to take on faith.
 */
public final class DataDisclosureDialog extends DialogWrapper {

    private static final Logger LOG = Logger.getInstance(DataDisclosureDialog.class);

    private final List<DejuDataInventory.Item> items;

    private DataDisclosureDialog(@Nullable Project project, List<DejuDataInventory.Item> items) {
        super(project, true);
        this.items = items;
        setTitle("What Deju Records");
        init();
    }

    public static void show(@Nullable Project project) {
        new DataDisclosureDialog(project, DejuDataInventory.of(project)).show();
    }

    @Override
    protected Action[] createActions() {
        return new Action[]{getOKAction()};
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setBorder(JBUI.Borders.empty(4, 2, 2, 2));

        column.add(left(bold("Deju records one traced call at a time, on this machine only.")));
        column.add(Box.createVerticalStrut(JBUI.scale(2)));
        column.add(left(help("<html>Nothing is sent anywhere. The plugin's only network"
                + " activity is the socket it opens to the agent you attached, and exported"
                + " reports contain no remote resources and make no requests.</html>")));
        column.add(Box.createVerticalStrut(JBUI.scale(12)));

        column.add(left(bold("Never captured")));
        column.add(left(help("<html>Variable values &middot; method arguments and return values"
                + " &middot; request or response bodies &middot; SQL parameter values, which stay"
                + " as <code>?</code> &middot; your source code, which the report reads live"
                + " from the IDE when you export and is not kept in the recording.</html>")));
        column.add(Box.createVerticalStrut(JBUI.scale(12)));

        column.add(left(bold("Stored on disk")));
        column.add(Box.createVerticalStrut(JBUI.scale(4)));

        for (DejuDataInventory.Item item : items) {
            column.add(left(bold(item.label + sizeSuffix(item))));
            column.add(left(help("<html>" + item.contents + "</html>")));
            if (item.path != null) {
                JPanel row = new JPanel();
                row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
                row.setBorder(JBUI.Borders.emptyTop(2));
                JBLabel where = help(item.path.toString());
                row.add(where);
                row.add(Box.createHorizontalStrut(JBUI.scale(6)));
                Path target = item.exclusive ? item.path : item.path.getParent();
                JButton open = UiStyle.compact(new JButton("Open", AllIcons.Nodes.Folder));
                open.addActionListener(e -> reveal(target));
                open.setEnabled(target != null && Files.exists(target));
                row.add(open);
                row.add(Box.createHorizontalGlue());
                column.add(left(row));
            }
            column.add(Box.createVerticalStrut(JBUI.scale(10)));
        }

        column.add(left(help("<html>Use <b>Clear Deju data…</b> to remove all of the"
                + " above. Uninstalling from the Plugins page clears it too, when the"
                + " uninstall happens in a running IDE.</html>")));

        JBScrollPane scroll = new JBScrollPane(column);
        scroll.setBorder(JBUI.Borders.empty());
        scroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(16));
        JPanel root = new JPanel(new BorderLayout());
        root.add(scroll, BorderLayout.CENTER);
        root.setPreferredSize(JBUI.size(620, 520));
        return root;
    }

    private static String sizeSuffix(DejuDataInventory.Item item) {
        if (!item.exclusive) {
            return "";
        }
        return item.files == 0
                ? ": nothing stored yet"
                : ": " + DejuDataInventory.humanSize(item.bytes);
    }

    private void reveal(@Nullable Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(path.toFile());
            }
        } catch (IOException | UnsupportedOperationException e) {
            LOG.warn("Could not open " + path, e);
        }
    }

    private static JBLabel bold(String text) {
        JBLabel label = new JBLabel(text);
        label.setFont(label.getFont().deriveFont(java.awt.Font.BOLD));
        return label;
    }

    private static JBLabel help(String text) {
        JBLabel label = new JBLabel(text);
        label.setForeground(UIUtil.getContextHelpForeground());
        label.setFont(JBUI.Fonts.smallFont());
        return label;
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return c;
    }
}
