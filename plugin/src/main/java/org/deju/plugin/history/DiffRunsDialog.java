package org.deju.plugin.history;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;

import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import org.deju.plugin.contract.DejuPayload;
import org.deju.plugin.ui.UiStyle;
import org.deju.plugin.ui.WrapLayout;

/**
 * Compares any two stored runs — normally "before" and "after" a change — using
 * {@link RunDiff}. Picking two runs from the history list rather than requiring
 * multi-select there keeps every other history action (Show, Pin, Rename, Delete) on
 * its existing single-selection assumption.
 */
public final class DiffRunsDialog extends DialogWrapper {

    private final Project project;
    private final ComboBox<ExecutionEntry> runA;
    private final ComboBox<ExecutionEntry> runB;
    private final JBTextArea output = new JBTextArea();

    public static void show(Project project, List<ExecutionEntry> entries, int preferA, int preferB) {
        if (entries.size() < 2) {
            return;
        }
        new DiffRunsDialog(project, entries, preferA, preferB).show();
    }

    private DiffRunsDialog(Project project, List<ExecutionEntry> entries, int preferA, int preferB) {
        super(project, true);
        this.project = project;
        this.runA = new ComboBox<>(entries.toArray(new ExecutionEntry[0]));
        this.runB = new ComboBox<>(entries.toArray(new ExecutionEntry[0]));
        setTitle("Diff Two Runs");
        setOKButtonText("Close");
        init();

        SimpleListCellRenderer<ExecutionEntry> renderer = SimpleListCellRenderer.create(
                (label, value, index) -> label.setText(value == null ? "" : shortDescribe(value)));
        runA.setRenderer(renderer);
        runB.setRenderer(renderer);
        selectBySlot(runA, preferA);
        // The second picker defaults to something other than the first when there is a
        // choice, comparing a run against itself is never what opening this dialog was for.
        selectBySlot(runB, preferB != preferA ? preferB
                : entries.stream().mapToInt(e -> e.slot).filter(s -> s != preferA).findFirst().orElse(preferA));

        runA.addActionListener(e -> recompute());
        runB.addActionListener(e -> recompute());
        recompute();
    }

    private static void selectBySlot(ComboBox<ExecutionEntry> box, int slot) {
        for (int i = 0; i < box.getItemCount(); i++) {
            if (box.getItemAt(i).slot == slot) {
                box.setSelectedIndex(i);
                return;
            }
        }
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setPreferredSize(JBUI.size(640, 480));

        JPanel pickers = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 4));
        pickers.add(new JBLabel("Run A:"));
        pickers.add(runA);
        pickers.add(new JBLabel("Run B:"));
        pickers.add(runB);
        JButton copyButton = UiStyle.compact(new JButton("Copy diff as Markdown"));
        copyButton.addActionListener(e ->
                CopyPasteManager.getInstance().setContents(new StringSelection(output.getText())));
        pickers.add(copyButton);
        root.add(pickers, BorderLayout.NORTH);

        output.setEditable(false);
        output.setLineWrap(true);
        output.setWrapStyleWord(true);
        output.setFont(output.getFont().deriveFont(java.awt.Font.PLAIN, 12f));
        root.add(new JBScrollPane(output), BorderLayout.CENTER);
        return root;
    }

    private void recompute() {
        ExecutionEntry entryA = runA.getItem();
        ExecutionEntry entryB = runB.getItem();
        if (entryA == null || entryB == null) {
            return;
        }
        DejuHistoryStore store = DejuHistoryStore.getInstance(project);
        DejuPayload payloadA = store.load(entryA.slot);
        DejuPayload payloadB = store.load(entryB.slot);
        if (payloadA == null || payloadB == null) {
            output.setText("One of the selected runs is no longer on disk.");
            return;
        }
        output.setText(RunDiff.of(entryA, payloadA, entryB, payloadB));
        output.setCaretPosition(0);
    }

    private static String shortDescribe(ExecutionEntry entry) {
        String when = new SimpleDateFormat("dd/MM/yy HH:mm:ss", Locale.ROOT)
                .format(new Date(entry.savedAtMillis));
        String what = entry.label != null && !entry.label.isEmpty() ? entry.label : entry.target;
        return when + "  ·  " + what;
    }
}
