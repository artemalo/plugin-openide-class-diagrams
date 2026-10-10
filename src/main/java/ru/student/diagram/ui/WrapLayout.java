package ru.student.diagram.ui;

import java.awt.*;

/** FlowLayout that reserves height for wrapped rows in a narrow tool window. */
public final class WrapLayout extends FlowLayout {
    public WrapLayout(int horizontalGap, int verticalGap) { super(LEFT, horizontalGap, verticalGap); }
    @Override public Dimension preferredLayoutSize(Container parent) {
        synchronized (parent.getTreeLock()) {
            int width = parent.getWidth();
            if (width <= 0 && parent.getParent() != null) width = parent.getParent().getWidth();
            if (width <= 0) return super.preferredLayoutSize(parent);
            Insets insets = parent.getInsets();
            int available = Math.max(1, width - insets.left - insets.right - 2 * getHgap());
            int rowWidth = 0, rowHeight = 0, height = getVgap();
            for (Component child : parent.getComponents()) {
                if (!child.isVisible()) continue;
                Dimension size = child.getPreferredSize();
                if (rowWidth > 0 && rowWidth + getHgap() + size.width > available) {
                    height += rowHeight + getVgap(); rowWidth = 0; rowHeight = 0;
                }
                rowWidth += (rowWidth == 0 ? 0 : getHgap()) + size.width;
                rowHeight = Math.max(rowHeight, size.height);
            }
            return new Dimension(width, height + rowHeight + getVgap() + insets.top + insets.bottom);
        }
    }
    @Override public Dimension minimumLayoutSize(Container parent) { return preferredLayoutSize(parent); }
}
