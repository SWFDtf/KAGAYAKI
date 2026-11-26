package de.swfdtf.ui;

import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;

/**
 * Scrollable clipping container supporting vertical mouse-wheel scrolling and interactive
 * scrollbar dragging.
 */
public class Viewport extends UiNode {

    /** Default scroll wheel step in logical pixels per wheel notch. */
    public static final float DEFAULT_SCROLL_STEP = 48.0f;

    /** Scrollbar track width in logical pixels. */
    public static final float SCROLLBAR_WIDTH = 8.0f;

    private float scrollY = 0.0f;
    private float maxScrollY = 0.0f;
    private float contentHeight = 0.0f;
    private float scrollStep = DEFAULT_SCROLL_STEP;
    private boolean scrollbarVisible = true;
    private boolean draggingScrollbar = false;

    /**
     * Creates an empty {@code Viewport}.
     */
    public Viewport() {
    }

    /**
     * Sets the vertical scroll offset, clamped to {@code [0, getMaxScrollY()]}.
     *
     * @param scrollY desired scroll offset in logical pixels
     * @return {@code this}
     */
    public Viewport setScrollY(float scrollY) {
        float clamped = Float.isFinite(scrollY) ? Math.max(0.0f, scrollY) : 0.0f;
        this.scrollY = Math.min(clamped, maxScrollY);
        relayoutChildren();
        return this;
    }

    /**
     * Returns current vertical scroll offset in logical pixels.
     *
     * @return current vertical scroll offset in logical pixels
     */
    public float getScrollY() {
        return scrollY;
    }

    /**
     * Returns maximum scrollable vertical offset ({@code max(0, contentHeight - innerHeight)}).
     *
     * @return maximum scrollable vertical offset ({@code max(0, contentHeight - innerHeight)})
     */
    public float getMaxScrollY() {
        return maxScrollY;
    }

    /**
     * Returns total measured vertical height of child content.
     *
     * @return total measured vertical height of child content
     */
    public float getContentHeight() {
        return contentHeight;
    }

    /**
     * Sets whether the vertical scrollbar track and thumb are drawn when content overflows.
     *
     * @param scrollbarVisible {@code true} to show the scrollbar
     * @return {@code this}
     */
    public Viewport setScrollbarVisible(boolean scrollbarVisible) {
        this.scrollbarVisible = scrollbarVisible;
        return this;
    }

    /**
     * Returns {@code true} if the scrollbar is visible.
     *
     * @return {@code true} if the scrollbar is visible
     */
    public boolean isScrollbarVisible() {
        return scrollbarVisible;
    }

    /**
     * Configures the scroll step per mouse wheel notch.
     *
     * @param scrollStep scroll step in logical pixels ({@code > 0})
     * @return {@code this}
     */
    public Viewport setScrollStep(float scrollStep) {
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

    /**
     * Returns {@code true} while the user is dragging the scrollbar thumb.
     *
     * @return {@code true} while the user is dragging the scrollbar thumb
     */
    public boolean isDraggingScrollbar() {
        return draggingScrollbar;
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        float w = getPreferredWidth() > 0.0f ? getPreferredWidth() : availableWidth;
        float h = getPreferredHeight() > 0.0f ? getPreferredHeight() : availableHeight;
        return new Vec2f(w, h);
    }

    @Override
    public void layout(Rect2f resolvedBounds) {
        setBounds(resolvedBounds);
        relayoutChildren();
    }

    private void relayoutChildren() {
        Rect2f inner = getContentBounds();
        float totalH = 0.0f;
        for (UiNode child : children) {
            if (!child.isVisible()) {
                continue;
            }
            Vec2f measured = child.measure(inner.width(), inner.height());
            float childH = Math.max(measured.y(), child.getPreferredHeight());
            totalH += childH;
        }
        this.contentHeight = totalH;
        this.maxScrollY = Math.max(0.0f, contentHeight - inner.height());
        this.scrollY = Math.clamp(this.scrollY, 0.0f, this.maxScrollY);

        float cursorY = inner.y() - this.scrollY;
        for (UiNode child : children) {
            if (!child.isVisible()) {
                continue;
            }
            Vec2f measured = child.measure(inner.width(), inner.height());
            float childH = Math.max(measured.y(), child.getPreferredHeight());
            float childW = inner.width();
            child.layout(new Rect2f(inner.x(), cursorY, childW, childH));
            cursorY += childH;
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
        UiRenderSupport.ScissorState scissor = UiRenderSupport.pushScissor(pipeline, inner);
        try {
            for (UiNode child : children) {
                if (child.isVisible() && child.getBounds().intersects(inner)) {
                    child.render(pipeline, fontManager, textureManager, elapsedSeconds);
                }
            }
        } finally {
            UiRenderSupport.popScissor(pipeline, scissor);
        }

        if (scrollbarVisible && maxScrollY > 0.0f) {
            Rect2f track = computeScrollbarTrack(inner);
            UiRenderSupport.drawScrollbar(
                    pipeline, track, scrollY, maxScrollY, contentHeight,
                    inner.height(), getAlpha(), draggingScrollbar
            );
        }
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
        float clampedX = Math.max(track.x(), Math.min(track.right(), lx));
        if (lx >= track.x() - 4.0f && lx <= getBounds().right() && track.contains(clampedX, ly)) {
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
        float x = Math.max(inner.x(), inner.right() - SCROLLBAR_WIDTH - 2.0f);
        return new Rect2f(x, inner.y() + 2.0f, SCROLLBAR_WIDTH, Math.max(0.0f, inner.height() - 4.0f));
    }

    private void updateScrollFromPointerY(float ly, Rect2f inner, Rect2f track) {
        if (track.height() <= 0.0f || maxScrollY <= 0.0f || contentHeight <= 0.0f) {
            return;
        }
        float thumbH = Math.min(track.height(), Math.max(24.0f, track.height() * (inner.height() / contentHeight)));
        float travel = Math.max(1.0f, track.height() - thumbH);
        float ratio = Math.clamp((ly - track.y() - thumbH * 0.5f) / travel, 0.0f, 1.0f);
        setScrollY(ratio * maxScrollY);
    }
}
