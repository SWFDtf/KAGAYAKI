package de.swfdtf.ui;

import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;

/**
 * Horizontal progress/gauge bar widget styled with the Dark-Slate {@link ThemePalette}.
 */
public class Bar extends UiNode {

    /** Default preferred width in logical pixels. */
    public static final float DEFAULT_WIDTH = 300.0f;

    /** Default preferred height in logical pixels. */
    public static final float DEFAULT_HEIGHT = 24.0f;

    private float max;
    private float value;
    private String fillColorHex = ThemePalette.ACCENT_IDLE;
    private String trackColorHex = ThemePalette.ELEVATED;

    /**
     * Creates a horizontal {@code Bar} with initial {@code value} and upper bound {@code max}.
     *
     * @param value initial value
     * @param max   maximum value ({@code > 0})
     */
    public Bar(float value, float max) {
        this.max = Float.isFinite(max) && max > 0.0f ? max : 1.0f;
        setValue(value);
        setCornerRadius(6.0f);
    }

    /**
     * Sets the current bar value, clamped to {@code [0, max]}.
     *
     * @param value new value
     * @return {@code this}
     */
    public Bar setValue(float value) {
        float safe = Float.isFinite(value) ? value : 0.0f;
        this.value = Math.clamp(safe, 0.0f, max);
        return this;
    }

    /**
     * Returns the current bar value.
     *
     * @return current bar value
     */
    public float getValue() {
        return value;
    }

    /**
     * Updates the upper bound {@code max} and re-clamps the current value.
     *
     * @param max maximum value ({@code > 0})
     * @return {@code this}
     */
    public Bar setRange(float max) {
        if (Float.isFinite(max) && max > 0.0f) {
            this.max = max;
            this.value = Math.clamp(this.value, 0.0f, this.max);
        }
        return this;
    }

    /**
     * Returns the maximum range value.
     *
     * @return maximum range value
     */
    public float getMax() {
        return max;
    }

    /**
     * Returns the normalized fill fraction in {@code [0, 1]}.
     *
     * @return normalized fill fraction
     */
    public float getNormalizedValue() {
        return max > 0.0f ? Math.clamp(value / max, 0.0f, 1.0f) : 0.0f;
    }

    /**
     * Sets the fill color hex string.
     *
     * @param fillColorHex hex color
     * @return {@code this}
     */
    public Bar setFillColorHex(String fillColorHex) {
        if (fillColorHex != null && !fillColorHex.isBlank()) {
            this.fillColorHex = fillColorHex;
        }
        return this;
    }

    /**
     * Returns the fill color hex string.
     *
     * @return fill color hex string
     */
    public String getFillColorHex() {
        return fillColorHex;
    }

    /**
     * Sets the background track color hex string.
     *
     * @param trackColorHex hex color
     * @return {@code this}
     */
    public Bar setTrackColorHex(String trackColorHex) {
        if (trackColorHex != null && !trackColorHex.isBlank()) {
            this.trackColorHex = trackColorHex;
        }
        return this;
    }

    /**
     * Returns the track color hex string.
     *
     * @return track color hex string
     */
    public String getTrackColorHex() {
        return trackColorHex;
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        float w = getPreferredWidth() > 0.0f
                ? getPreferredWidth()
                : (availableWidth > 0.0f ? Math.min(DEFAULT_WIDTH, availableWidth) : DEFAULT_WIDTH);
        float h = getPreferredHeight() > 0.0f ? getPreferredHeight() : DEFAULT_HEIGHT;
        return new Vec2f(w, h);
    }

    @Override
    protected void renderSelf(
            RenderPipeline2D pipeline,
            FontManager fontManager,
            TextureManager textureManager,
            double elapsedSeconds
    ) {
        if (!UiRenderSupport.isPipelineReady(pipeline)) {
            return;
        }
        Rect2f b = getBounds();
        if (b.width() <= 0.0f || b.height() <= 0.0f) {
            return;
        }
        float radius = Math.min(getCornerRadius(), b.height() * 0.5f);
        UiRenderSupport.drawRoundedRect(
                pipeline, b.x(), b.y(), b.width(), b.height(),
                radius, trackColorHex, 1.0f, ThemePalette.BORDER, getAlpha()
        );
        float fillW = b.width() * getNormalizedValue();
        if (fillW > 0.0f) {
            UiRenderSupport.drawRoundedRect(
                    pipeline, b.x(), b.y(), fillW, b.height(),
                    radius, fillColorHex, 0.0f, null, getAlpha()
            );
        }
    }
}
