package de.swfdtf.ui;

import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;

import java.util.ArrayList;
import java.util.List;

/**
 * Virtualized scrollable grid container computing the visible row range
 * {@code [firstVisibleRow..lastVisibleRow]} for large save-slot pages and CG galleries.
 *
 * <p>Only children falling within the visible row range are laid out, rendered, and hit-tested,
 * enabling smooth performance even with thousands of entries.</p>
 */
public class VpGrid extends UiNode {

    /** Default scroll wheel step in logical pixels. */
    public static final float DEFAULT_SCROLL_STEP = 64.0f;

    private final int columns;
    private final float cellWidth;
    private final float cellHeight;
    private float horizontalSpacing = 0.0f;
    private float verticalSpacing = 0.0f;

    private float scrollY = 0.0f;
    private float maxScrollY = 0.0f;
    private float totalContentHeight = 0.0f;
    private float scrollStep = DEFAULT_SCROLL_STEP;
    private boolean scrollbarVisible = true;
    private boolean draggingScrollbar = false;

    private int totalRows = 0;
    private int firstVisibleRow = 0;
    private int lastVisibleRow = -1;

    /**
     * Creates a virtualized scrollable grid.
     *
     * @param columns    number of columns per row ({@code >= 1})
     * @param cellWidth  fixed cell width in logical pixels ({@code > 0})
     * @param cellHeight fixed cell height in logical pixels ({@code > 0})
     */
    public VpGrid(int columns, float cellWidth, float cellHeight) {
        this.columns = Math.max(1, columns);
        this.cellWidth = Math.max(1.0f, cellWidth);
        this.cellHeight = Math.max(1.0f, cellHeight);
    }

    /**
     * Configures horizontal and vertical spacing between grid cells.
     *
     * @param h horizontal spacing in pixels ({@code >= 0})
     * @param v vertical spacing in pixels ({@code >= 0})
     * @return {@code this}
     */
    public VpGrid setSpacing(float h, float v) {
        this.horizontalSpacing = Math.max(0.0f, h);
        this.verticalSpacing = Math.max(0.0f, v);
        return this;
    }

    /**
     * Returns column count.
     *
     * @return column count
     */
    public int getColumns() {
        return columns;
    }

    /**
     * Returns fixed cell width.
     *
     * @return fixed cell width
     */
    public float getCellWidth() {
        return cellWidth;
    }

    /**
     * Returns fixed cell height.
     *
     * @return fixed cell height
     */
    public float getCellHeight() {
        return cellHeight;
    }

    /**
     * Returns horizontal spacing between cells.
     *
     * @return horizontal spacing between cells
     */
    public float getHorizontalSpacing() {
        return horizontalSpacing;
    }

    /**
     * Returns vertical spacing between rows.
     *
     * @return vertical spacing between rows
     */
    public float getVerticalSpacing() {
        return verticalSpacing;
    }

    /**
     * Sets the vertical scroll offset, clamped to {@code [0, getMaxScrollY()]}.
     *
     * @param scrollY scroll offset in logical pixels
     * @return {@code this}
     */
    public VpGrid setScrollY(float scrollY) {
        float clamped = Float.isFinite(scrollY) ? Math.max(0.0f, scrollY) : 0.0f;
        this.scrollY = Math.min(clamped, maxScrollY);
        recomputeVisibleRowsAndLayout();
        return this;
    }

    /**
     * Returns current vertical scroll offset.
     *
     * @return current vertical scroll offset
     */
    public float getScrollY() {
        return scrollY;
    }

    /**
     * Returns maximum vertical scroll offset.
     *
     * @return maximum vertical scroll offset
     */
    public float getMaxScrollY() {
        return maxScrollY;
    }

    /**
     * Returns total logical height of all rows.
     *
     * @return total logical height of all rows
     */
    public float getTotalContentHeight() {
        return totalContentHeight;
    }

    /**
     * Returns total number of rows across all visible children.
     *
     * @return total number of rows across all visible children
     */
    public int getTotalRows() {
        return totalRows;
    }

    /**
     * Returns inclusive index of the first visible row.
     *
     * @return inclusive index of the first visible row
     */
    public int getFirstVisibleRow() {
        return firstVisibleRow;
    }

    /**
     * Returns inclusive index of the last visible row ({@code -1} when empty).
     *
     * @return inclusive index of the last visible row ({@code -1} when empty)
     */
    public int getLastVisibleRow() {
        return lastVisibleRow;
    }

    /**
     * Sets whether the vertical scrollbar is rendered when content overflows.
     *
     * @param scrollbarVisible {@code true} to show scrollbar
     * @return {@code this}
     */
    public VpGrid setScrollbarVisible(boolean scrollbarVisible) {
        this.scrollbarVisible = scrollbarVisible;
        return this;
    }

    /**
     * Returns {@code true} if scrollbar is enabled.
     *
     * @return {@code true} if scrollbar is enabled
     */
    public boolean isScrollbarVisible() {
        return scrollbarVisible;
    }

    /**
     * Sets the scroll step per mouse wheel notch.
     *
     * @param scrollStep scroll step in logical pixels ({@code > 0})
     * @return {@code this}
     */
    public VpGrid setScrollStep(float scrollStep) {
        if (Float.isFinite(scrollStep) && scrollStep > 0.0f) {
            this.scrollStep = scrollStep;
        }
        return this;
    }

    /**
     * Returns scroll step per mouse wheel notch.
     *
     * @return scroll step per mouse wheel notch
     */
    public float getScrollStep() {
        return scrollStep;
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        float naturalW = columns * cellWidth + Math.max(0, columns - 1) * horizontalSpacing
                + getPaddingLeft() + getPaddingRight();
        float w = getPreferredWidth() > 0.0f
                ? getPreferredWidth()
                : Math.min(availableWidth > 0 ? availableWidth : naturalW, naturalW);
        float h = getPreferredHeight() > 0.0f ? getPreferredHeight() : availableHeight;
        return new Vec2f(w, h);
    }

    @Override
    public void layout(Rect2f resolvedBounds) {
        setBounds(resolvedBounds);
        recomputeVisibleRowsAndLayout();
    }

    private List<UiNode> collectVisibleChildren() {
        List<UiNode> visible = new ArrayList<>(children.size());
        for (UiNode child : children) {
            if (child.isVisible()) {
                visible.add(child);
            }
        }
        return visible;
    }

    private void recomputeVisibleRowsAndLayout() {
        List<UiNode> visible = collectVisibleChildren();
        Rect2f inner = getContentBounds();
        int count = visible.size();
        this.totalRows = count == 0 ? 0 : (count + columns - 1) / columns;
        this.totalContentHeight = totalRows == 0
                ? 0.0f
                : totalRows * cellHeight + Math.max(0, totalRows - 1) * verticalSpacing;
        this.maxScrollY = Math.max(0.0f, totalContentHeight - inner.height());
        this.scrollY = Math.clamp(this.scrollY, 0.0f, this.maxScrollY);

        if (totalRows == 0 || inner.height() <= 0.0f) {
            this.firstVisibleRow = 0;
            this.lastVisibleRow = -1;
            return;
        }

        float rowStride = cellHeight + verticalSpacing;
        this.firstVisibleRow = Math.clamp((int) Math.floor(scrollY / rowStride), 0, totalRows - 1);
        this.lastVisibleRow = Math.clamp(
                (int) Math.floor((scrollY + inner.height() - 0.001f) / rowStride),
                firstVisibleRow,
                totalRows - 1
        );

        int startIdx = firstVisibleRow * columns;
        int endIdx = Math.min(count - 1, (lastVisibleRow + 1) * columns - 1);
        for (int i = startIdx; i <= endIdx; i++) {
            int row = i / columns;
            int col = i % columns;
            float cx = inner.x() + col * (cellWidth + horizontalSpacing);
            float cy = inner.y() + row * rowStride - scrollY;
            visible.get(i).layout(new Rect2f(cx, cy, cellWidth, cellHeight));
        }
    }

    @Override
    public void render(
            RenderPipeline2D pipeline,
            FontManager fontManager,
            TextureManager textureManager,
            double elapsedSeconds
    ) {
        if (!isVisible() || getAlpha() <= 0.0f) {
            return;
        }
        drawBackgroundBox(pipeline, getAlpha());
        renderSelf(pipeline, fontManager, textureManager, elapsedSeconds);

        Rect2f inner = getContentBounds();
        if (lastVisibleRow >= firstVisibleRow) {
            List<UiNode> visible = collectVisibleChildren();
            int startIdx = firstVisibleRow * columns;
            int endIdx = Math.min(visible.size() - 1, (lastVisibleRow + 1) * columns - 1);
            UiRenderSupport.ScissorState scissor = UiRenderSupport.pushScissor(pipeline, inner);
            try {
                for (int i = startIdx; i <= endIdx; i++) {
                    visible.get(i).render(pipeline, fontManager, textureManager, elapsedSeconds);
                }
            } finally {
                UiRenderSupport.popScissor(scissor);
            }
        }

        if (scrollbarVisible && maxScrollY > 0.0f) {
            Rect2f track = computeScrollbarTrack(inner);
            UiRenderSupport.drawScrollbar(
                    pipeline, track, scrollY, maxScrollY, totalContentHeight,
                    inner.height(), getAlpha(), draggingScrollbar
            );
        }
    }

    @Override
    public UiNode dispatchMouseMove(float lx, float ly) {
        if (!isVisible()) {
            setHoveredDeep(false);
            return null;
        }
        UiNode hitChild = null;
        if (lastVisibleRow >= firstVisibleRow) {
            List<UiNode> visible = collectVisibleChildren();
            int startIdx = firstVisibleRow * columns;
            int endIdx = Math.min(visible.size() - 1, (lastVisibleRow + 1) * columns - 1);
            for (int i = endIdx; i >= startIdx; i--) {
                UiNode candidate = visible.get(i).dispatchMouseMove(lx, ly);
                if (candidate != null && hitChild == null) {
                    hitChild = candidate;
                }
            }
        }
        onMouseMoved(lx, ly, getBounds().contains(lx, ly));
        return hitChild;
    }

    @Override
    public boolean dispatchMouseDown(float lx, float ly, int button) {
        if (!isVisible() || !isEnabled() || !getBounds().contains(lx, ly)) {
            return false;
        }
        if (lastVisibleRow >= firstVisibleRow) {
            List<UiNode> visible = collectVisibleChildren();
            int startIdx = firstVisibleRow * columns;
            int endIdx = Math.min(visible.size() - 1, (lastVisibleRow + 1) * columns - 1);
            for (int i = endIdx; i >= startIdx; i--) {
                if (visible.get(i).dispatchMouseDown(lx, ly, button)) {
                    return true;
                }
            }
        }
        return onMouseDown(lx, ly, button);
    }

    @Override
    public boolean dispatchMouseUp(float lx, float ly, int button) {
        if (!isVisible()) {
            return false;
        }
        boolean handled = false;
        if (lastVisibleRow >= firstVisibleRow) {
            List<UiNode> visible = collectVisibleChildren();
            int startIdx = firstVisibleRow * columns;
            int endIdx = Math.min(visible.size() - 1, (lastVisibleRow + 1) * columns - 1);
            for (int i = endIdx; i >= startIdx; i--) {
                if (visible.get(i).dispatchMouseUp(lx, ly, button)) {
                    handled = true;
                }
            }
        }
        if (isEnabled() && onMouseUp(lx, ly, button)) {
            handled = true;
        }
        return handled;
    }

    @Override
    protected boolean isInteractive() {
        return true;
    }

    @Override
    protected boolean onMouseScroll(float lx, float ly, double scrollYDelta) {
        if (maxScrollY <= 0.0f) {
            return false;
        }
        setScrollY(this.scrollY - (float) scrollYDelta * scrollStep);
        return true;
    }

    @Override
    protected boolean onMouseDown(float lx, float ly, int button) {
        if (button != 0 || !scrollbarVisible || maxScrollY <= 0.0f) {
            return false;
        }
        Rect2f inner = getContentBounds();
        Rect2f track = computeScrollbarTrack(inner);
        if (lx >= track.x() - 4.0f && lx <= getBounds().right()
                && ly >= track.y() && ly <= track.bottom()) {
            draggingScrollbar = true;
            updateScrollFromPointerY(ly, inner, track);
            return true;
        }
        return false;
    }

    @Override
    protected void onMouseMoved(float lx, float ly, boolean inside) {
        if (draggingScrollbar) {
            Rect2f inner = getContentBounds();
            updateScrollFromPointerY(ly, inner, computeScrollbarTrack(inner));
        }
    }

    @Override
    protected boolean onMouseUp(float lx, float ly, int button) {
        if (draggingScrollbar && button == 0) {
            draggingScrollbar = false;
            return true;
        }
        return false;
    }

    private Rect2f computeScrollbarTrack(Rect2f inner) {
        float x = Math.max(inner.x(), inner.right() - Viewport.SCROLLBAR_WIDTH - 2.0f);
        return new Rect2f(x, inner.y() + 2.0f, Viewport.SCROLLBAR_WIDTH, Math.max(0.0f, inner.height() - 4.0f));
    }

    private void updateScrollFromPointerY(float ly, Rect2f inner, Rect2f track) {
        if (track.height() <= 0.0f || maxScrollY <= 0.0f || totalContentHeight <= 0.0f) {
            return;
        }
        float thumbH = Math.min(
                track.height(), Math.max(24.0f, track.height() * (inner.height() / totalContentHeight)));
        float travel = Math.max(1.0f, track.height() - thumbH);
        float ratio = Math.clamp((ly - track.y() - thumbH * 0.5f) / travel, 0.0f, 1.0f);
        setScrollY(ratio * maxScrollY);
    }
}
