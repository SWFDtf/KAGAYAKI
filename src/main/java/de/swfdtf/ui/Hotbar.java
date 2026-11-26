package de.swfdtf.ui;

import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Region-based progress bar inside an {@link Imagemap} or {@link Fixed} container driven by a
 * live value {@link Supplier}.
 */
public class Hotbar extends UiNode {

    private Rect2f area;
    private Supplier<Float> valueSupplier;
    private float maxValue;
    private String fillColorHex = ThemePalette.ACCENT_IDLE;
    private String trackColorHex = ThemePalette.ELEVATED;

    /**
     * Creates a {@code Hotbar} widget.
     *
     * @param area          bar region relative to parent {@link Imagemap}
     * @param valueSupplier dynamic value supplier (nullable; defaults to {@code 0})
     * @param maxValue      maximum range value ({@code > 0})
     */
    public Hotbar(Rect2f area, Supplier<Float> valueSupplier, float maxValue) {
        this.area = Objects.requireNonNull(area, "area must not be null");
        this.valueSupplier = valueSupplier;
        this.maxValue = Math.max(0.0001f, maxValue);
        setBounds(area);
        setPreferredSize(area.width(), area.height());
    }

    /**
     * Returns configured hotbar area.
     *
     * @return configured hotbar area
     */
    public Rect2f getArea() {
        return area;
    }

    /**
     * Updates the hotbar area rectangle.
     *
     * @param area new area
     * @return {@code this}
     */
    public Hotbar setArea(Rect2f area) {
        this.area = Objects.requireNonNull(area, "area must not be null");
        setPreferredSize(area.width(), area.height());
        return this;
    }

    /**
     * Returns maximum range value.
     *
     * @return maximum range value
     */
    public float getMaxValue() {
        return maxValue;
    }

    /**
     * Sets the maximum range value.
     *
     * @param maxValue maximum value ({@code > 0})
     * @return {@code this}
     */
    public Hotbar setMaxValue(float maxValue) {
        this.maxValue = Math.max(0.0001f, maxValue);
        return this;
    }

    /**
     * Returns current supplied value clamped to {@code [0, maxValue]}.
     *
     * @return current supplied value clamped to {@code [0, maxValue]}
     */
    public float getCurrentValue() {
        if (valueSupplier == null) {
            return 0.0f;
        }
        Float val = valueSupplier.get();
        if (val == null || !Float.isFinite(val)) {
            return 0.0f;
        }
        return Math.clamp(val, 0.0f, maxValue);
    }

    /**
     * Returns normalized fill fraction in {@code [0, 1]}.
     *
     * @return normalized fill fraction in {@code [0, 1]}
     */
    public float getNormalizedRatio() {
        return Math.clamp(getCurrentValue() / maxValue, 0.0f, 1.0f);
    }

    /**
     * Sets the fill color hex string.
     *
     * @param fillColorHex hex color
     * @return {@code this}
     */
    public Hotbar setFillColorHex(String fillColorHex) {
        if (fillColorHex != null && !fillColorHex.isBlank()) {
            this.fillColorHex = fillColorHex;
        }
        return this;
    }

    /**
     * Returns fill color hex string.
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
    public Hotbar setTrackColorHex(String trackColorHex) {
        if (trackColorHex != null && !trackColorHex.isBlank()) {
            this.trackColorHex = trackColorHex;
        }
        return this;
    }

    /**
     * Returns track color hex string.
     *
     * @return track color hex string
     */
    public String getTrackColorHex() {
        return trackColorHex;
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        return new Vec2f(area.width(), area.height());
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
        UiRenderSupport.drawRoundedRect(
                pipeline, b.x(), b.y(), b.width(), b.height(),
                Math.min(4.0f, b.height() * 0.5f),
                trackColorHex, 1.0f, ThemePalette.BORDER, getAlpha()
        );
        float fillW = b.width() * getNormalizedRatio();
        if (fillW > 0.0f) {
            UiRenderSupport.drawRoundedRect(
                    pipeline, b.x(), b.y(), fillW, b.height(),
                    Math.min(4.0f, b.height() * 0.5f),
                    fillColorHex, 0.0f, null, getAlpha()
            );
        }
    }
}
