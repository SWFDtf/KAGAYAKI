package de.swfdtf.ui;

import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;

import java.util.ArrayList;
import java.util.List;

/**
 * Matrix grid layout container arranging visible children automatically across {@code cols} columns.
 */
public class GridBox extends UiNode {

    private int cols = 3;
    private float spacingX = 8.0f;
    private float spacingY = 8.0f;

    /**
     * Creates a 3-column {@code GridBox} with default {@code 8px} spacing.
     */
    public GridBox() {
    }

    /**
     * Creates a {@code GridBox} with the specified column count.
     *
     * @param cols number of columns ({@code >= 1})
     */
    public GridBox(int cols) {
        setCols(cols);
    }

    /**
     * Sets column count (clamped to {@code >= 1}).
     *
     * @param cols column count
     * @return {@code this}
     */
    public GridBox setCols(int cols) {
        this.cols = Math.max(1, cols);
        return this;
    }

    /**
     * Returns configured column count.
     *
     * @return column count
     */
    public int getCols() {
        return cols;
    }

    /**
     * Sets uniform horizontal and vertical cell spacing.
     *
     * @param spacing spacing in logical pixels ({@code >= 0})
     * @return {@code this}
     */
    public GridBox setSpacing(float spacing) {
        return setSpacing(spacing, spacing);
    }

    /**
     * Sets horizontal and vertical cell spacing.
     *
     * @param xspacing horizontal gap in logical pixels ({@code >= 0})
     * @param yspacing vertical gap in logical pixels ({@code >= 0})
     * @return {@code this}
     */
    public GridBox setSpacing(float xspacing, float yspacing) {
        this.spacingX = Math.max(0.0f, xspacing);
        this.spacingY = Math.max(0.0f, yspacing);
        return this;
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        float w = getPreferredWidth() > 0.0f ? getPreferredWidth() : availableWidth;
        float h = getPreferredHeight() > 0.0f ? getPreferredHeight() : availableHeight;
        return new Vec2f(w, h);
    }

    @Override
    public void layout(Rect2f resolvedBounds) {
        Rect2f target = resolvedBounds != null ? resolvedBounds : new Rect2f(0.0f, 0.0f, 0.0f, 0.0f);
        super.layout(target);
        Rect2f inner = getContentBounds();
        List<UiNode> visibleChildren = new ArrayList<>();
        for (UiNode child : children) {
            if (child.isVisible()) {
                visibleChildren.add(child);
            }
        }
        if (visibleChildren.isEmpty()) {
            return;
        }
        int c = Math.max(1, cols);
        int rows = (visibleChildren.size() + c - 1) / c;
        float cellW = Math.max(1.0f, (inner.width() - spacingX * (c - 1)) / c);
        float cellH = Math.max(1.0f, (inner.height() - spacingY * (rows - 1)) / rows);

        for (int i = 0; i < visibleChildren.size(); i++) {
            int col = i % c;
            int row = i / c;
            float cx = inner.x() + col * (cellW + spacingX);
            float cy = inner.y() + row * (cellH + spacingY);
            visibleChildren.get(i).layout(new Rect2f(cx, cy, cellW, cellH));
        }
    }
}
