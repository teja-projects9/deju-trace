package org.deju.plugin.paint;

import java.awt.Color;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.TextAnnotationGutterProvider;
import com.intellij.openapi.editor.colors.ColorKey;
import com.intellij.openapi.editor.colors.EditorFontType;
import org.jetbrains.annotations.Nullable;

/**
 * Renders a per-line timing column in the editor's left gutter, like VCS blame, but the
 * text is the wall-clock time recorded for that line. A method's first line shows the
 * method's inclusive total; other lines show their own self time (which, for a line that
 * calls an uninstrumented dependency such as a JDBC/Postgres driver, is that call's cost).
 *
 * <p>Registered per file via {@code editor.getGutter().registerTextAnnotation(this)} and
 * removed with {@code closeTextAnnotations(...)} so it never disturbs other annotations.
 */
final class TimingGutterProvider implements TextAnnotationGutterProvider {

    private final Map<Integer, String> text;     // 0-based line -> gutter text
    private final Map<Integer, String> tooltip;  // 0-based line -> tooltip

    TimingGutterProvider(Map<Integer, String> text, Map<Integer, String> tooltip) {
        this.text = text;
        this.tooltip = tooltip;
    }

    @Override
    public @Nullable String getLineText(int line, Editor editor) {
        return text.get(line);
    }

    @Override
    public @Nullable String getToolTip(int line, Editor editor) {
        return tooltip.get(line);
    }

    @Override
    public EditorFontType getStyle(int line, Editor editor) {
        return EditorFontType.PLAIN;
    }

    @Override
    public @Nullable ColorKey getColor(int line, Editor editor) {
        return null;
    }

    @Override
    public @Nullable Color getBgColor(int line, Editor editor) {
        return null;
    }

    @Override
    public List<AnAction> getPopupActions(int line, Editor editor) {
        return Collections.emptyList();
    }

    @Override
    public void gutterClosed() {
        // nothing to release
    }

    /**
     * Human-friendly duration from microseconds, auto-scaling the unit:
     * "640 µs", "8.4 ms", "1.5 s", "1m 30s", "2h 5m".
     */
    static String format(long micros) {
        if (micros < 1_000L) {                 // sub-millisecond
            return micros + " µs";
        }
        if (micros < 1_000_000L) {             // < 1 s → ms
            return trim(micros / 1_000.0) + " ms";
        }
        if (micros < 60_000_000L) {            // < 1 min → s
            return trim(micros / 1_000_000.0) + " s";
        }
        if (micros < 3_600_000_000L) {         // < 1 h → m s
            long totalSec = micros / 1_000_000L;
            return (totalSec / 60) + "m " + (totalSec % 60) + "s";
        }
        long totalMin = micros / 60_000_000L;  // ≥ 1 h → h m
        return (totalMin / 60) + "h " + (totalMin % 60) + "m";
    }

    /**
     * That duration's share of the whole run, as {@code "  12%"}, ready to append to a
     * formatted time. Empty when there is no run length to divide by, because a column that
     * silently falls back to a percentage of something else is worse than no percentage.
     *
     * <p>Precision follows size, for the same reason the report's does: {@code 0.4%} and
     * {@code <0.1%} both say "not this line", while three decimals on every trivial line
     * would widen the gutter for noise. Anything at or above 10% loses its decimal, since by
     * then the figure is a headline, not a measurement.
     */
    static String suffixPercent(long micros, long runMicros) {
        if (runMicros <= 0) {
            return "";
        }
        double p = (micros * 100.0) / runMicros;
        if (p >= 10) {
            return "  " + Math.round(p) + "%";
        }
        if (p >= 0.1) {
            return "  " + String.format("%.1f", p) + "%";
        }
        return p > 0 ? "  <0.1%" : "  0%";
    }

    /** One decimal, but drop a trailing ".0", and no decimals once we're in the hundreds. */
    private static String trim(double value) {
        if (value >= 100) {
            return String.format("%.0f", value);
        }
        String s = String.format("%.1f", value);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
