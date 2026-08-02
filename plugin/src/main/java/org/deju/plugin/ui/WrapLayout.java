package org.deju.plugin.ui;

import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * A {@link FlowLayout} that reports a preferred height for the width it is actually given.
 *
 * <p>{@code FlowLayout} already wraps its children onto new rows when it runs out of width,
 * but it reports a preferred size as if they were all on one line. In a tool window that
 * width is never available, so the enclosing scroll pane believes the panel is far wider
 * than the dock and shows a horizontal scrollbar over controls that had in fact wrapped
 * neatly. Deju's tool window carries eleven buttons, which is well past the point where a
 * right-hand dock can lay them out in a row.
 *
 * <p>The fix is to compute the preferred size the way the layout will really place things:
 * take the available width, wrap, and report the resulting height.
 */
public final class WrapLayout extends FlowLayout {

    public WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension minimum = layoutSize(target, false);
        // Signals to the scroll pane that this panel can be made narrower, which is what
        // stops it from forcing a horizontal scrollbar.
        minimum.width -= (getHgap() + 1);
        return minimum;
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int targetWidth = availableWidth(target);
            Insets insets = target.getInsets();
            int horizontalInsets = insets.left + insets.right + getHgap() * 2;
            int maxWidth = targetWidth - horizontalInsets;

            Dimension size = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;

            for (int i = 0; i < target.getComponentCount(); i++) {
                java.awt.Component c = target.getComponent(i);
                if (!c.isVisible()) {
                    continue;
                }
                Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();
                if (rowWidth + d.width > maxWidth && rowWidth > 0) {
                    addRow(size, rowWidth, rowHeight);
                    rowWidth = 0;
                    rowHeight = 0;
                }
                if (rowWidth != 0) {
                    rowWidth += getHgap();
                }
                rowWidth += d.width;
                rowHeight = Math.max(rowHeight, d.height);
            }
            addRow(size, rowWidth, rowHeight);

            size.width += horizontalInsets;
            size.height += insets.top + insets.bottom + getVgap() * 2;

            // A viewport asks for the preferred size while it is itself being laid out; if
            // we hand back the wrapped height at that moment the scrollbar flickers in and
            // out. Reporting one extra gap avoids the oscillation.
            java.awt.Container scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
            if (scrollPane != null && target.isValid()) {
                size.height += getVgap() * 2;
            }
            return size;
        }
    }

    /** Width to wrap against: the container's own, or its scroll viewport's once realised. */
    private static int availableWidth(Container target) {
        Container parent = target.getParent();
        int width = target.getWidth();
        if (width == 0 && parent != null) {
            width = parent.getWidth();
        }
        // Before the first layout pass nothing has a width yet; Integer.MAX_VALUE makes the
        // first measurement behave exactly like plain FlowLayout, and the next pass, once
        // real widths exist, wraps properly.
        return width == 0 ? Integer.MAX_VALUE : width;
    }

    private void addRow(Dimension size, int rowWidth, int rowHeight) {
        size.width = Math.max(size.width, rowWidth);
        if (size.height > 0) {
            size.height += getVgap();   // separator between wrapped rows
        }
        size.height += rowHeight;
    }
}
