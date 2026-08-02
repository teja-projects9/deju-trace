package org.deju.plugin.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings page is put inside a scroll pane by the IDE. These tests reproduce the
 * horizontal scrollbar it grew, using plain Swing so no IntelliJ fixture is needed.
 */
class WidthTrackingPanelTest {

    /** A settings dialog narrower than the content wants to be. */
    private static final Dimension VIEWPORT = new Dimension(420, 400);

    private static JScrollPane scrolled(JPanel content) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setSize(VIEWPORT);
        scroll.doLayout();
        scroll.getViewport().doLayout();
        content.doLayout();
        return scroll;
    }

    /** The bug: one long unwrappable label drags the whole page sideways. */
    @Test
    void aPlainPanelDemandsTheFullWidthOfItsWidestChild() {
        JPanel plain = new JPanel(new BorderLayout());
        plain.add(new JLabel("Loopback for a local app; a mapped host/port for a container"
                + " or remote box (no socat bridge needed)."), BorderLayout.CENTER);

        scrolled(plain);

        assertTrue(plain.getPreferredSize().width > VIEWPORT.width,
                "this is the reported symptom: the content is wider than the dialog");
    }

    @Test
    void aWidthTrackingPanelNeverExceedsTheViewport() {
        WidthTrackingPanel tracking = new WidthTrackingPanel(new BorderLayout());
        tracking.add(new JLabel("Loopback for a local app; a mapped host/port for a container"
                + " or remote box (no socat bridge needed)."), BorderLayout.CENTER);

        JScrollPane scroll = scrolled(tracking);

        assertTrue(tracking.getScrollableTracksViewportWidth(),
                "the viewport must impose its width rather than the content");
        assertTrue(tracking.getWidth() <= scroll.getViewport().getWidth(),
                "laid out width must fit the viewport, so no horizontal bar appears");
        assertFalse(tracking.getScrollableTracksViewportHeight(),
                "a long page still has to scroll vertically");
    }

    /**
     * The other half of the fix: rows of controls have to wrap. A {@link FlowLayout} wraps
     * when it is laid out but reports a preferred size as if everything sat on one line, so
     * the scroll pane is told the page is wider than it is.
     */
    @Test
    void wrapLayoutReportsTheHeightThatWrappingActuallyNeeds() {
        JPanel row = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 0));
        for (String caption : new String[]{"Reset to defaults", "What Deju records…",
                "Clear Deju data…"}) {
            JButton button = new JButton(caption);
            button.setPreferredSize(new Dimension(180, 24));
            row.add(button);
        }
        row.setSize(new Dimension(200, 10));   // narrow enough to force three rows

        Dimension wrapped = row.getPreferredSize();

        assertTrue(wrapped.height > 24,
                () -> "a wrapped row is taller than one button, was " + wrapped.height);
        assertTrue(wrapped.width <= 200 + 12,
                () -> "and must not claim the full single-line width, was " + wrapped.width);
    }
}
