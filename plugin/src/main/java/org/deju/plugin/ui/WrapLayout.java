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

    /**
     * Width the last measurement was taken against, so {@link #layoutContainer} can tell when
     * the height it already handed to its parent was computed for a different width.
     */
    private int measuredWidth = -1;

    public WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        measuredWidth = availableWidth(target);
        return layoutSize(target, true);
    }

    /**
     * The narrowest this row can ever be: its widest single child, because everything else
     * can wrap onto a line of its own.
     *
     * <p>Measuring against the container's <em>current</em> width instead, as this used to,
     * makes the answer depend on the width it is meant to help decide, and a scroll pane
     * asking "can this fit?" gets back whatever it happens to be showing right now.
     */
    @Override
    public Dimension minimumLayoutSize(Container target) {
        synchronized (target.getTreeLock()) {
            Insets insets = target.getInsets();
            int widest = 0;
            int stacked = 0;
            for (int i = 0; i < target.getComponentCount(); i++) {
                java.awt.Component c = target.getComponent(i);
                if (!c.isVisible()) {
                    continue;
                }
                Dimension d = c.getMinimumSize();
                widest = Math.max(widest, d.width);
                stacked += d.height + getVgap();
            }
            return new Dimension(widest + insets.left + insets.right + getHgap() * 2,
                    stacked + insets.top + insets.bottom + getVgap());
        }
    }

    /**
     * Re-measures once the container is given a width different from the one its reported
     * height was computed for.
     *
     * <p>{@link Container} caches its preferred size while it is valid and {@code BoxLayout}
     * caches its size requirements, so a height measured before the real width arrived
     * survives until something invalidates it. Until this existed, only dragging the tool
     * window's edge did, which is why the panel opened with rows stretched far taller than
     * their contents and snapped straight on the first resize.
     */
    @Override
    public void layoutContainer(Container target) {
        super.layoutContainer(target);
        int width = target.getWidth();
        if (width > 0 && width != measuredWidth) {
            measuredWidth = width;
            // Posted rather than run here: invalidating a container in the middle of laying
            // it out re-enters the very pass we are inside. The width-changed guard is what
            // stops this from cycling.
            SwingUtilities.invokeLater(() -> {
                target.invalidate();
                target.revalidate();
            });
        }
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

    /** Width to wrap against: the container's own, or the nearest ancestor's once realised. */
    private static int availableWidth(Container target) {
        int width = target.getWidth();
        // Zero means "not laid out yet". Negative means an ancestor has already subtracted
        // its insets from a width it does not have either, which is what a BorderLayout with
        // a border does on the very first pass. Neither is a width to wrap against, and
        // letting a negative one through wraps every child onto a line of its own.
        for (Container p = target.getParent(); width <= 0 && p != null; p = p.getParent()) {
            width = p.getWidth();
        }
        // Before the first layout pass nothing has a width yet; Integer.MAX_VALUE makes the
        // first measurement behave exactly like plain FlowLayout, and the next pass, once
        // real widths exist, wraps properly.
        return width <= 0 ? Integer.MAX_VALUE : width;
    }

    private void addRow(Dimension size, int rowWidth, int rowHeight) {
        size.width = Math.max(size.width, rowWidth);
        if (size.height > 0) {
            size.height += getVgap();   // separator between wrapped rows
        }
        size.height += rowHeight;
    }
}
