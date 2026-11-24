package de.swfdtf.ui;

import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;

import java.util.ArrayList;
import java.util.List;

/**
 * Uniform cell grid layout container arranging visible children in row-major order.
 */
public class Grid extends UiNode {

    private int columns;
    private int rows;
    private float horizontalSpacing = 0.0f;
    private float verticalSpacing = 0.0f;

    /**
     * Creates a {@code Grid} with the specified column and row counts.
     *
     * @param columns number of grid columns ({@code >= 1})
     * @param rows    number of grid rows ({@code <= 0} computes row count dynamically from children)
     */
    public Grid(int columns, int rows) {
        this.columns = Math.max(1, columns);
        this.rows = Math.max(0, rows);
    }

    /**
     * Configures horizontal and vertical gaps between grid cells.
     *
     * @param h horizontal spacing in pixels ({@code >= 0})
     * @param v vertical spacing in pixels ({@code >= 0})
     * @return {@code this}
     */
    public Grid setSpacing(float h, float v) {
        this.horizontalSpacing = Math.max(0.0f, h);
        this.verticalSpacing = Math.max(0.0f, v);
        return this;
    }

    /** @return configured column count */
    public int getColumns() {
        return columns;
    }

    /** @return configured row count ({@code 0} for automatic) */
    public int getRows() {
        return rows;
    }

    /** @return horizontal cell spacing in pixels */
    public float getHorizontalSpacing() {
        return horizontalSpacing;
    }

    /** @return vertical cell spacing in pixels */
    public float getVerticalSpacing() {
        return verticalSpacing;
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        List<UiNode> visible = visibleChildren();
        int cols = Math.max(1, columns);
        int effectiveRows = rows > 0 ? rows : Math.max(1, (visible.size() + cols - 1) / cols);

        float innerAvailW = Math.max(0.0f, (getPreferredWidth() > 0.0f ? getPreferredWidth() : availableWidth)
                - getPaddingLeft() - getPaddingRight());
        float innerAvailH = Math.max(0.0f, (getPreferredHeight() > 0.0f ? getPreferredHeight() : availableHeight)
                - getPaddingTop() - getPaddingBottom());
        float availCellW = Math.max(0.0f, (innerAvailW - (cols - 1) * horizontalSpacing) / cols);
        float availCellH = Math.max(0.0f, (innerAvailH - (effectiveRows - 1) * verticalSpacing) / effectiveRows);

        float maxCellW = 0.0f;
        float maxCellH = 0.0f;
        for (UiNode child : visible) {
            Vec2f m = child.measure(availCellW, availCellH);
            maxCellW = Math.max(maxCellW, m.x());
            maxCellH = Math.max(maxCellH, m.y());
        }

        float w = getPreferredWidth() > 0.0f
                ? getPreferredWidth()
                : cols * maxCellW + Math.max(0, cols - 1) * horizontalSpacing + getPaddingLeft() + getPaddingRight();
        float h = getPreferredHeight() > 0.0f
                ? getPreferredHeight()
                : effectiveRows * maxCellH + Math.max(0, effectiveRows - 1) * verticalSpacing + getPaddingTop() + getPaddingBottom();
        return new Vec2f(w, h);
    }

    @Override
    public void layout(Rect2f resolvedBounds) {
        setBounds(resolvedBounds);
        Rect2f inner = getContentBounds();
        List<UiNode> visible = visibleChildren();
        int cols = Math.max(1, columns);
        int effectiveRows = rows > 0 ? rows : Math.max(1, (visible.size() + cols - 1) / cols);

        float cellW = Math.max(0.0f, (inner.width() - Math.max(0, cols - 1) * horizontalSpacing) / cols);
        float cellH = Math.max(0.0f, (inner.height() - Math.max(0, effectiveRows - 1) * verticalSpacing) / effectiveRows);

        for (int i = 0; i < visible.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            float cx = inner.x() + col * (cellW + horizontalSpacing);
            float cy = inner.y() + row * (cellH + verticalSpacing);
            visible.get(i).layout(new Rect2f(cx, cy, cellW, cellH));
        }
    }

    private List<UiNode> visibleChildren() {
        List<UiNode> visible = new ArrayList<>();
        for (UiNode child : children) {
            if (child.isVisible()) {
                visible.add(child);
            }
        }
        return visible;
    }
}
