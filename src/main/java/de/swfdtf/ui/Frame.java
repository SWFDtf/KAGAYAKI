package de.swfdtf.ui;

import de.swfdtf.graphics.NineSliceDrawable;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;

/**
 * Styled panel container defaulting to a Dark-Slate {@link ThemePalette#PANEL_SURFACE} rounded box
 * with a 1px {@link ThemePalette#BORDER} outline, or rendering a custom {@link NineSliceDrawable}.
 */
public class Frame extends UiNode {

    /** Default corner radius in logical pixels. */
    public static final float DEFAULT_CORNER_RADIUS = 8.0f;

    /** Default border thickness in logical pixels. */
    public static final float DEFAULT_BORDER_THICKNESS = 1.0f;

    private NineSliceDrawable nineSlice;

    /**
     * Creates a {@code Frame} with Dark-Slate surface and border styling.
     */
    public Frame() {
        setBackgroundColorHex(ThemePalette.PANEL_SURFACE);
        setBorderColorHex(ThemePalette.BORDER);
        setBorderThickness(DEFAULT_BORDER_THICKNESS);
        setCornerRadius(DEFAULT_CORNER_RADIUS);
    }

    /**
     * Attaches a {@link NineSliceDrawable} texture frame (or {@code null} to revert to Dark-Slate box).
     *
     * @param nineSlice nine-slice drawable (nullable)
     * @return {@code this}
     */
    public Frame setNineSlice(NineSliceDrawable nineSlice) {
        this.nineSlice = nineSlice;
        return this;
    }

    /**
     * Returns attached {@link NineSliceDrawable}, or {@code null}.
     *
     * @return attached {@link NineSliceDrawable}, or {@code null}
     */
    public NineSliceDrawable getNineSlice() {
        return nineSlice;
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        if (getPreferredWidth() > 0.0f && getPreferredHeight() > 0.0f) {
            return new Vec2f(getPreferredWidth(), getPreferredHeight());
        }
        float innerAvailW = Math.max(0.0f, (getPreferredWidth() > 0.0f ? getPreferredWidth() : availableWidth)
                - getPaddingLeft() - getPaddingRight());
        float innerAvailH = Math.max(0.0f, (getPreferredHeight() > 0.0f ? getPreferredHeight() : availableHeight)
                - getPaddingTop() - getPaddingBottom());

        float maxW = 0.0f;
        float maxH = 0.0f;
        boolean hasVisibleChild = false;
        for (UiNode child : children) {
            if (!child.isVisible()) {
                continue;
            }
            hasVisibleChild = true;
            Vec2f m = child.measure(innerAvailW, innerAvailH);
            maxW = Math.max(maxW, m.x());
            maxH = Math.max(maxH, m.y());
        }

        if (!hasVisibleChild) {
            return super.measure(availableWidth, availableHeight);
        }

        float w = getPreferredWidth() > 0.0f
                ? getPreferredWidth()
                : maxW + getPaddingLeft() + getPaddingRight();
        float h = getPreferredHeight() > 0.0f
                ? getPreferredHeight()
                : maxH + getPaddingTop() + getPaddingBottom();
        return new Vec2f(w, h);
    }

    @Override
    protected void drawBackgroundBox(RenderPipeline2D pipeline, float effectiveAlpha) {
        Rect2f b = getBounds();
        if (!UiRenderSupport.isPipelineReady(pipeline)
                || b.width() <= 0.0f || b.height() <= 0.0f || effectiveAlpha <= 0.0f) {
            return;
        }
        if (nineSlice != null) {
            nineSlice.render(
                    pipeline,
                    b.x(),
                    b.y(),
                    b.width(),
                    b.height(),
                    new float[]{1.0f, 1.0f, 1.0f, Math.clamp(effectiveAlpha, 0.0f, 1.0f)},
                    ColorMatrix4f.identity()
            );
            return;
        }
        UiRenderSupport.drawRoundedRect(
                pipeline,
                b.x(),
                b.y(),
                b.width(),
                b.height(),
                getCornerRadius(),
                getBackgroundColorHex(),
                getBorderThickness(),
                getBorderColorHex(),
                effectiveAlpha
        );
    }
}
