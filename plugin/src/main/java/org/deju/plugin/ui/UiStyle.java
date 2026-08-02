package org.deju.plugin.ui;

import javax.swing.JButton;

import com.intellij.util.ui.JBUI;

/** Small shared helpers for a compact, tight-button look across Deju's UI. */
public final class UiStyle {

    private UiStyle() {
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
