package org.deju.plugin.paint;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;

import org.deju.plugin.DejuSettings;
import org.deju.plugin.contract.DejuPayload;
import org.deju.plugin.contract.FileCoverage;
import org.deju.plugin.contract.LineCoverage;
import org.deju.plugin.contract.LineStatus;
import org.deju.plugin.exclude.DejuExclusions;
import org.deju.plugin.exclude.TypeExclusionMatcher;
import org.deju.plugin.source.SourceResolver;

/**
 * Paints a payload's line coverage into the editor: FULL green, PARTIAL yellow,
 * NONE red, like JUnit coverage but for one live request. Highlighters are added to
 * each covered file's editor markup and tracked so they can be cleared.
 *
 * <p>Must be used on the EDT.
 *
 * <p>TODO(deju): class resolution is by fully-qualified name via PSI. Multiple modules
 * defining the same FQN, or generated/decompiled sources, are edge cases the MVP maps
 * to the first match {@code JavaPsiFacade} returns.
 */
public final class EditorPainter {

    private static final int LAYER = HighlighterLayer.SELECTION - 1;

    // Soft backgrounds tuned for both light and dark themes.
    private static final JBColor GREEN = new JBColor(new Color(0xE3, 0xF4, 0xE1), new Color(0x27, 0x3A, 0x27));
    private static final JBColor YELLOW = new JBColor(new Color(0xFB, 0xF2, 0xCC), new Color(0x4A, 0x43, 0x1E));
    private static final JBColor RED = new JBColor(new Color(0xF8, 0xDD, 0xDD), new Color(0x48, 0x2A, 0x2B));

    private final Project project;
    private final List<RangeHighlighter> active = new ArrayList<>();
    private final List<GutterHandle> gutters = new ArrayList<>();

    /**
     * Coverage for every painted file, by virtual file, so a file opened long after "Show"
     * can still be given its timing column. Cleared with the highlighting.
     */
    private final Map<VirtualFile, FileCoverage> painted = new HashMap<>();

    /** Live only while something is painted; see {@link #listenForLaterOpens()}. */
    private MessageBusConnection connection;

    /** A registered timing column, kept so it can be removed on clear without touching others. */
    private record GutterHandle(Editor editor, TimingGutterProvider provider) {
    }

    public EditorPainter(Project project) {
        this.project = project;
    }

    /**
     * Clears any previous highlighting and paints the given payload.
     *
     * <p><b>Every</b> recorded file is painted, whether or not it is opened: coverage
     * highlighters live on the document, so a file the user opens by hand tomorrow already
     * carries its colours. Only the tabs are rationed, {@link PaintPlan} skips excluded
     * types and caps the rest at {@link DejuSettings#maxOpenFiles}, in execution order.
     */
    public void paint(DejuPayload payload) {
        clear();
        TypeExclusionMatcher matcher = DejuExclusions.getInstance(project).matcher();
        PaintPlan plan = PaintPlan.of(payload, matcher::isExcluded, DejuSettings.getInstance().maxOpenFiles);

        // Paint first, open second. Colours are then already in place for the tabs below,
        // and equally in place for anything opened later.
        List<String> unresolved = new ArrayList<>();
        for (FileCoverage fc : plan.all) {
            VirtualFile vf = SourceResolver.resolve(project, fc.getFqClassName(), fc.getSourceFileName());
            if (vf == null) {
                unresolved.add(fc.getFqClassName());
                continue;
            }
            Document document = FileDocumentManager.getInstance().getDocument(vf);
            if (document == null) {
                continue;   // binary or unreadable; nothing to highlight
            }
            paintFile(document, fc);
            painted.put(vf, fc);
            // The file may already be open from earlier work; it needs its column now,
            // because no fileOpened event is coming for it.
            annotateOpenEditors(vf, fc);
        }
        listenForLaterOpens();
        openTabs(plan);
        warnUnresolved(unresolved);
        reportTrimmed(plan);
    }

    /** Opens the plan's tabs and gives each one its timing column. */
    private void openTabs(PaintPlan plan) {
        VirtualFile focusTarget = null;
        List<VirtualFile> resolved = new ArrayList<>(plan.open.size());
        for (FileCoverage fc : plan.open) {
            VirtualFile vf = SourceResolver.resolve(project, fc.getFqClassName(), fc.getSourceFileName());
            resolved.add(vf);
            // The first file is the traced method's own; fall back to any later one that
            // resolved, so the caret still lands somewhere meaningful.
            if (vf != null && focusTarget == null) {
                focusTarget = vf;
            }
        }
        for (int i = 0; i < resolved.size(); i++) {
            VirtualFile vf = resolved.get(i);
            if (vf == null) {
                continue;
            }
            Editor editor = FileEditorManager.getInstance(project)
                    .openTextEditor(new OpenFileDescriptor(project, vf, 0, 0), vf.equals(focusTarget));
            if (editor != null) {
                registerTiming(editor, plan.open.get(i));
            }
        }
    }

    /**
     * Adds the timing column to painted files the user opens after "Show" has run.
     *
     * <p>Highlighters could be attached up front because they belong to the document, but a
     * gutter annotation belongs to an editor, and beyond the tab limit there is no editor
     * yet. Without this, the 11th file would open coloured but with no timings, a
     * difference the user would have no way to explain.
     */
    private void listenForLaterOpens() {
        connection = project.getMessageBus().connect();
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override
            public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
                FileCoverage fc = painted.get(file);
                if (fc != null) {
                    annotateOpenEditors(file, fc);
                }
            }
        });
    }

    /** Gives every open text editor for {@code vf} the run's timing column. */
    private void annotateOpenEditors(VirtualFile vf, FileCoverage fc) {
        for (FileEditor fileEditor : FileEditorManager.getInstance(project).getEditors(vf)) {
            if (fileEditor instanceof TextEditor) {
                registerTiming(((TextEditor) fileEditor).getEditor(), fc);
            }
        }
    }

    /**
     * Tells the user which files were opened, and that the others are painted anyway.
     *
     * <p>Silently opening 10 of 34 would read as a bug. Saying so, and saying the rest are
     * already coloured, turns the limit into something the user can work with rather than
     * something they have to work around.
     */
    private void reportTrimmed(PaintPlan plan) {
        if (!plan.isTrimmed()) {
            return;
        }
        StringBuilder why = new StringBuilder();
        if (!plan.excluded.isEmpty()) {
            why.append(plan.excluded.size()).append(" excluded by type");
        }
        if (!plan.overLimit.isEmpty()) {
            if (why.length() > 0) {
                why.append(", ");
            }
            why.append(plan.overLimit.size()).append(" over the ")
                    .append(DejuSettings.getInstance().maxOpenFiles).append("-file limit");
        }
        NotificationGroupManager.getInstance()
                .getNotificationGroup("Deju Trace")
                .createNotification(
                        "Deju: opened " + plan.open.size() + " of " + plan.recorded() + " files",
                        why + ". <b>The rest are painted too</b>, open any of them and the "
                                + "coverage and timings are already there. Adjust the list or the "
                                + "limit in Settings → Tools → Deju Trace."
                                + preview(plan),
                        NotificationType.INFORMATION)
                .notify(project);
    }

    /** Names a few of the unopened classes: a count alone does not say whether it matters. */
    private static String preview(PaintPlan plan) {
        List<String> skipped = new ArrayList<>(plan.excluded);
        skipped.addAll(plan.overLimit);
        if (skipped.isEmpty()) {
            return "";
        }
        String names = String.join(", ", skipped.subList(0, Math.min(skipped.size(), 5)));
        return "<br>Not opened: " + names + (skipped.size() > 5 ? ", …" : "");
    }

    /**
     * Adds the left-gutter timing column for one file's covered lines.
     *
     * <p>Idempotent per editor. Opening a tab fires {@code fileOpened} <i>and</i> returns
     * the editor to the caller, so without this guard every opened file would get its
     * timing column twice over.
     */
    private void registerTiming(Editor editor, FileCoverage fc) {
        for (GutterHandle g : gutters) {
            if (g.editor().equals(editor)) {
                return;
            }
        }
        Map<Integer, String> text = new HashMap<>();
        Map<Integer, String> tips = new HashMap<>();
        for (LineCoverage lc : fc.getLines()) {
            int line0 = lc.getLine() - 1;
            if (line0 < 0) {
                continue;
            }
            Long methodTotal = lc.getMethodTotalMicros();
            Long self = lc.getTimeMicros();
            if (methodTotal != null) {
                // Method's first line: headline the inclusive total; self time in the tooltip.
                text.put(line0, "▸ " + TimingGutterProvider.format(methodTotal));
                Long methodSelf = lc.getMethodSelfMicros();
                tips.put(line0, "Method total " + TimingGutterProvider.format(methodTotal)
                        + (methodSelf != null ? "  ·  self " + TimingGutterProvider.format(methodSelf) : ""));
            } else if (self != null) {
                text.put(line0, TimingGutterProvider.format(self));
                tips.put(line0, "Line self time " + TimingGutterProvider.format(self));
            }
        }
        if (text.isEmpty()) {
            return;
        }
        TimingGutterProvider provider = new TimingGutterProvider(text, tips);
        editor.getGutter().registerTextAnnotation(provider);
        gutters.add(new GutterHandle(editor, provider));
    }

    /**
     * A silent failure here is exactly the "colors but no code" symptom, so make it visible:
     * tell the user which classes could not be mapped to source in the open project.
     */
    private void warnUnresolved(List<String> unresolved) {
        if (unresolved.isEmpty()) {
            return;
        }
        String preview = String.join(", ", unresolved.subList(0, Math.min(unresolved.size(), 5)));
        if (unresolved.size() > 5) {
            preview += ", …";
        }
        NotificationGroupManager.getInstance()
                .getNotificationGroup("Deju Trace")
                .createNotification(
                        "Deju: source not found for " + unresolved.size() + " class(es)",
                        "These classes were traced but are not in the open project's source index, "
                                + "so their code cannot be shown: " + preview,
                        NotificationType.WARNING)
                .notify(project);
    }

    private void paintFile(Document document, FileCoverage fc) {
        int lineCount = document.getLineCount();
        for (LineCoverage lc : fc.getLines()) {
            int line0 = lc.getLine() - 1; // payload lines are 1-based
            if (line0 < 0 || line0 >= lineCount) {
                continue;
            }
            TextAttributes attributes = attributesFor(lc.getStatus());
            RangeHighlighter highlighter = com.intellij.openapi.editor.impl.DocumentMarkupModel
                    .forDocument(document, project, true)
                    .addLineHighlighter(line0, LAYER, attributes);
            highlighter.setErrorStripeMarkColor(stripeFor(lc.getStatus()));
            highlighter.setErrorStripeTooltip(tooltip(lc));
            active.add(highlighter);
        }
    }

    /** Removes all coverage highlighting and timing columns this painter added. */
    public void clear() {
        if (connection != null) {
            // Stop annotating newly opened files: the run they belonged to is gone.
            connection.disconnect();
            connection = null;
        }
        painted.clear();
        for (RangeHighlighter h : active) {
            try {
                h.dispose();
            } catch (Exception ignored) {
                // highlighter's document/editor may already be gone
            }
        }
        active.clear();
        for (GutterHandle g : gutters) {
            try {
                g.editor().getGutter().closeTextAnnotations(Collections.singletonList(g.provider()));
            } catch (Exception ignored) {
                // editor may already be closed
            }
        }
        gutters.clear();
    }

    private static TextAttributes attributesFor(LineStatus status) {
        TextAttributes ta = new TextAttributes();
        ta.setBackgroundColor(colorFor(status));
        return ta;
    }

    private static Color colorFor(LineStatus status) {
        switch (status) {
            case FULL: return GREEN;
            case PARTIAL: return YELLOW;
            default: return RED;
        }
    }

    private static Color stripeFor(LineStatus status) {
        return colorFor(status);
    }

    private static String tooltip(LineCoverage lc) {
        if (lc.getBranchesTotal() != null && lc.getBranchesTotal() > 0) {
            return "Deju: " + lc.getStatus() + " (" + lc.getBranchesCovered()
                    + "/" + lc.getBranchesTotal() + " branches)";
        }
        return "Deju: " + lc.getStatus();
    }
}
