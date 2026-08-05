package org.deju.plugin.ui;

import javax.swing.JButton;
import javax.swing.JLabel;

import com.intellij.util.ui.JBUI;

/** Small shared helpers for a compact, tight-button look across Deju's UI. */
public final class UiStyle {

    private UiStyle() {
    }

    /**
     * Wraps {@code text} in a {@code <div>} of an explicit pixel width.
     *
     * <p>A plain {@code <html>} label reports the width of the whole sentence on one line and
     * the height of a single line, whatever width it is later given. Put one in a dialog and
     * it drags a horizontal scrollbar across the page; force it narrower and the text wraps
     * visually but the label still claims one line's height, so the rest is simply clipped.
     * An explicit {@code width} is what makes the label report the height wrapping really
     * takes, which is the part a naive {@code <html>} label gets wrong.
     */
    public static String wrappedAtWidth(String text, int widthPx) {
        return "<html><body><div width=\"" + widthPx + "\">" + stripHtml(text) + "</div></body></html>";
    }

    /**
     * The same, for a width measured as {@code chars} characters of {@code text} in the
     * label's own font. Short text is left on one line: a width narrower than the text itself
     * would wrap it for no reason.
     *
     * <p>The measurement runs on the visible text, not the markup. Measuring the raw string
     * counts {@code <b>} and {@code <code>} as if they were words, so a hint carrying markup
     * wraps at a different column than a plain one of the same length.
     */
    public static String wrappedAtChars(JLabel label, String text, int chars) {
        String visible = stripHtml(text);
        if (visible.length() <= chars) {
            return "<html><body><div>" + visible + "</div></body></html>";
        }
        int width = label.getFontMetrics(label.getFont()).stringWidth(visible.substring(0, chars));
        return wrappedAtWidth(text, width);
    }

    /**
     * Drops an outer {@code <html>} wrapper, keeping any inline markup inside it. Callers pass
     * both bare sentences and ready-made HTML, and nesting a second {@code <html>} inside the
     * {@code <div>} is not something Swing's parser is obliged to make sense of.
     */
    private static String stripHtml(String text) {
        String s = text.trim();
        if (s.regionMatches(true, 0, "<html>", 0, 6)) {
            s = s.substring(6);
        }
        if (s.regionMatches(true, s.length() - 7, "</html>", 0, 7)) {
            s = s.substring(0, s.length() - 7);
        }
        return s;
    }

    /**
     * Shortens a filesystem path from the middle, keeping both ends readable.
     *
     * <p>The interesting parts of a path are at its ends, the root that says which machine
     * and the leaf that says which file. A label left to clip itself loses the leaf, and one
     * left at full length dictates the width of the dialog holding it.
     */
    public static String ellipsisMiddle(String text, int maxChars) {
        if (text.length() <= maxChars || maxChars < 8) {
            return text;
        }
        int keep = maxChars - 1;
        int head = (keep + 1) / 2;
        return text.substring(0, head) + "…" + text.substring(text.length() - (keep - head));
    }

    /**
     * Makes a button compact, a smaller font and tight insets, closer to a native macOS
     * control than the default chunky platform button. Returns the same button so it can wrap
     * a field initializer: {@code UiStyle.compact(new JButton("Go"))}.
     */
    public static JButton compact(JButton button) {
        button.setFont(JBUI.Fonts.smallFont());
        button.setMargin(JBUI.insets(1, 8));
        return button;
    }
}
