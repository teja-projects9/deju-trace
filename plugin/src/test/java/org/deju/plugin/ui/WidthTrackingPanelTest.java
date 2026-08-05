package org.deju.plugin.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void aWidthTrackingPanelImposesTheViewportWidthOnContentThatCanWrap() {
        WidthTrackingPanel tracking = new WidthTrackingPanel(new BorderLayout());
        tracking.add(buttonRow(), BorderLayout.CENTER);

        JScrollPane scroll = scrolled(tracking);

        assertTrue(tracking.getScrollableTracksViewportWidth(),
                "the viewport must impose its width rather than the content");
        assertTrue(tracking.getWidth() <= scroll.getViewport().getWidth(),
                "laid out width must fit the viewport, so no horizontal bar appears");
        assertFalse(tracking.getScrollableTracksViewportHeight(),
                "a long page still has to scroll vertically");
    }

    /**
     * The limit of the promise. Content that cannot wrap has a floor, and a panel that keeps
     * claiming to fit below it is worse than the scrollbar it removes: the page is squashed
     * and clipped, and nothing will scroll to what was cut off.
     */
    @Test
    void butHandsTheScrollbarBackOnceTheContentCannotShrinkThatFar() {
        WidthTrackingPanel tracking = new WidthTrackingPanel(new BorderLayout());
        tracking.add(new JLabel("Loopback for a local app; a mapped host/port for a container"
                + " or remote box (no socat bridge needed)."), BorderLayout.CENTER);

        scrolled(tracking);

        assertTrue(tracking.getMinimumSize().width > VIEWPORT.width,
                "premise: a single unwrappable label really is wider than the dialog");
        assertFalse(tracking.getScrollableTracksViewportWidth(),
                "so the scrollbar has to come back rather than the text be clipped away");
    }

    private static JPanel buttonRow() {
        JPanel row = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 0));
        for (String caption : new String[]{"Reset to defaults", "What Deju records…",
                "Clear Deju data…"}) {
            JButton button = new JButton(caption);
            button.setPreferredSize(new Dimension(180, 24));
            row.add(button);
        }
        return row;
    }

    /**
     * The other half of the fix: rows of controls have to wrap. A {@link FlowLayout} wraps
     * when it is laid out but reports a preferred size as if everything sat on one line, so
     * the scroll pane is told the page is wider than it is.
     */
    @Test
    void wrapLayoutReportsTheHeightThatWrappingActuallyNeeds() {
        JPanel row = buttonRow();
        row.setSize(new Dimension(200, 10));   // narrow enough to force three rows

        Dimension wrapped = row.getPreferredSize();

        assertTrue(wrapped.height > 24,
                () -> "a wrapped row is taller than one button, was " + wrapped.height);
        assertTrue(wrapped.width <= 200 + 12,
                () -> "and must not claim the full single-line width, was " + wrapped.width);
    }

    /**
     * The tool window opened with rows stretched far taller than their contents, and snapped
     * straight the moment its edge was dragged.
     *
     * <p>A {@code BorderLayout} with a border hands its child the container width minus the
     * insets, so before a real width exists the row is measured at a <em>negative</em> one.
     * Treating that as a width to wrap against puts every control on a line of its own, and
     * the tall result is what the parent then reserves space for.
     */
    @Test
    void wrapLayoutDoesNotStackEveryChildWhenNoWidthIsKnownYet() {
        JPanel row = buttonRow();
        row.setSize(-16, 0);   // what a bordered BorderLayout hands out on the first pass

        Dimension measured = row.getPreferredSize();

        assertTrue(measured.height < 3 * 24,
                () -> "three buttons must not each claim their own line, height was "
                        + measured.height);
    }

    /**
     * The minimum has to be answerable without knowing the width, because it is what a scroll
     * pane asks in order to decide the width. Measuring against the current one makes the
     * answer whatever the panel happens to be showing already.
     */
    @Test
    void wrapLayoutReportsItsWidestChildAsTheMinimumWidth() {
        JPanel row = buttonRow();

        row.setSize(1000, 24);
        int wide = row.getMinimumSize().width;
        row.setSize(200, 24);
        int narrow = row.getMinimumSize().width;

        assertEquals(wide, narrow, "the minimum must not depend on the current width");
        assertTrue(wide < 3 * 180,
                () -> "and must be one button wide, not three, was " + wide);
    }
}
