package org.deju.plugin.ui;

import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.Rectangle;

import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

import com.intellij.util.ui.JBUI;

/**
 * A panel that never asks for more width than the scroll pane holding it has.
 *
 * <p>The IDE puts a settings page inside a scroll pane, and a plain {@link JPanel} reports
 * whatever width its widest child wants. One long non-wrapping label is then enough to push
 * the whole page sideways, so the user gets a horizontal scrollbar and has to drag the page
 * back and forth to read a form that would otherwise fit.
 *
 * <p>Reporting {@code true} from {@link #getScrollableTracksViewportWidth()} makes the
 * viewport impose its own width instead. Children are laid out in the space that actually
 * exists, so anything able to wrap does, and the page grows downwards rather than sideways.
 * Vertical tracking stays off: a long page still has to scroll somewhere.
 */
public final class WidthTrackingPanel extends JPanel implements Scrollable {

    public WidthTrackingPanel(LayoutManager layout) {
        super(layout);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return JBUI.scale(16);
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
