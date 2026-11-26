package de.swfdtf.ui;

import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * Interactive horizontal slider supporting click-jump, continuous mouse dragging, step snapping,
 * and keyboard Left/Right arrow adjustments.
 */
public class Slider extends UiNode {

    /** Default preferred width in logical pixels. */
    public static final float DEFAULT_WIDTH = 300.0f;

    /** Default preferred height in logical pixels. */
    public static final float DEFAULT_HEIGHT = 28.0f;

    private float min;
    private float max;
    private float value;
    private float step = 0.0f;
    private Consumer<Float> onChanged;
    private boolean dragging = false;

    /**
     * Creates a horizontal {@code Slider}.
     *
     * @param value     initial value
     * @param min       minimum range bound
     * @param max       maximum range bound ({@code >= min})
     * @param onChanged callback invoked whenever user interaction changes the value (nullable)
     */
    public Slider(float value, float min, float max, Consumer<Float> onChanged) {
        this.min = Float.isFinite(min) ? min : 0.0f;
        this.max = (Float.isFinite(max) && max >= this.min) ? max : this.min;
        this.onChanged = onChanged;
        setValue(value);
        setFocusable(true);
    }

    /**
     * Sets the slider value programmatically (clamped and snapped to {@link #getStep()} if positive)
     * without invoking {@code onChanged}.
     *
     * @param value new value
     * @return {@code this}
     */
    public Slider setValue(float value) {
        this.value = normalizeAndSnap(value);
        return this;
    }

    /**
     * Returns current slider value.
     *
     * @return current slider value
     */
    public float getValue() {
        return value;
    }

    /**
     * Returns minimum bound.
     *
     * @return minimum bound
     */
    public float getMin() {
        return min;
    }

    /**
     * Returns maximum bound.
     *
     * @return maximum bound
     */
    public float getMax() {
        return max;
    }

    /**
     * Configures discrete step snapping ({@code <= 0} disables snapping for continuous control).
     *
     * @param step step size in value units
     * @return {@code this}
     */
    public Slider setStep(float step) {
        this.step = (Float.isFinite(step) && step > 0.0f) ? step : 0.0f;
        this.value = normalizeAndSnap(this.value);
        return this;
    }

    /**
     * Returns configured step size ({@code 0} when continuous).
     *
     * @return configured step size ({@code 0} when continuous)
     */
    public float getStep() {
        return step;
    }

    /**
     * Returns {@code true} while actively dragged by the pointer.
     *
     * @return {@code true} while actively dragged by the pointer
     */
    public boolean isDragging() {
        return dragging;
    }

    /**
     * Updates the change callback.
     *
     * @param onChanged change listener (nullable)
     * @return {@code this}
     */
    public Slider setOnChanged(Consumer<Float> onChanged) {
        this.onChanged = onChanged;
        return this;
    }

    /**
     * Returns normalized slider position in {@code [0, 1]}.
     *
     * @return normalized slider position in {@code [0, 1]}
     */
    public float getNormalizedValue() {
        float span = max - min;
        if (span <= 0.0f) {
            return 0.0f;
        }
        return Math.clamp((value - min) / span, 0.0f, 1.0f);
    }

    private float normalizeAndSnap(float raw) {
        float safe = Float.isFinite(raw) ? raw : min;
        float clamped = Math.clamp(safe, min, max);
        if (step > 0.0f && max > min) {
            float steps = Math.round((clamped - min) / step);
            clamped = Math.clamp(min + steps * step, min, max);
        }
        return clamped;
    }

    private void setValueFromUser(float raw) {
        float next = normalizeAndSnap(raw);
        boolean changed = Math.abs(next - this.value) > 1e-6f;
        this.value = next;
        if (changed && onChanged != null) {
            onChanged.accept(this.value);
        }
    }

    private void updateFromPointerX(float lx) {
        Rect2f b = getBounds();
        float w = Math.max(1.0f, b.width());
        float ratio = Math.clamp((lx - b.x()) / w, 0.0f, 1.0f);
        setValueFromUser(min + ratio * (max - min));
    }

    @Override
    protected boolean isInteractive() {
        return true;
    }

    @Override
    protected boolean onMouseDown(float lx, float ly, int button) {
        if (button == 0 && isEnabled() && getBounds().contains(lx, ly)) {
            dragging = true;
            setFocused(true);
            updateFromPointerX(lx);
            return true;
        }
        return false;
    }

    @Override
    protected void onMouseMoved(float lx, float ly, boolean inside) {
        if (dragging && isEnabled()) {
            updateFromPointerX(lx);
        }
    }

    @Override
    protected boolean onMouseUp(float lx, float ly, int button) {
        if (button == 0 && dragging) {
            dragging = false;
            return true;
        }
        return false;
    }

    @Override
    protected boolean onKeyPressed(int key, int mods) {
        if (!isEnabled() || !isFocused()) {
            return false;
        }
        float delta = step > 0.0f ? step : (max - min) * 0.05f;
        if (delta <= 0.0f) {
            return false;
        }
        if (key == GLFW.GLFW_KEY_LEFT) {
            setValueFromUser(value - delta);
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT) {
            setValueFromUser(value + delta);
            return true;
        }
        return false;
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
        float trackH = Math.max(6.0f, Math.min(10.0f, b.height() * 0.35f));
        float trackY = b.y() + (b.height() - trackH) * 0.5f;
        float radius = trackH * 0.5f;

        UiRenderSupport.drawRoundedRect(
                pipeline, b.x(), trackY, b.width(), trackH,
                radius, ThemePalette.ELEVATED, 1.0f, ThemePalette.BORDER, getAlpha()
        );

        float norm = getNormalizedValue();
        float fillW = b.width() * norm;
        String accent = (isHovered() || isFocused()) ? ThemePalette.ACCENT_HOVER : ThemePalette.ACCENT_IDLE;
        if (fillW > 0.0f) {
            UiRenderSupport.drawRoundedRect(
                    pipeline, b.x(), trackY, fillW, trackH,
                    radius, accent, 0.0f, null, getAlpha()
            );
        }

        float thumbSize = Math.min(b.height(), Math.max(16.0f, trackH * 2.0f));
        float thumbX = Math.clamp(b.x() + fillW - thumbSize * 0.5f, b.x(), b.right() - thumbSize);
        float thumbY = b.y() + (b.height() - thumbSize) * 0.5f;
        String thumbColor = dragging
                ? ThemePalette.ACTIVE_SELECTED
                : ((isHovered() || isFocused()) ? ThemePalette.ACCENT_HOVER : ThemePalette.TEXT_PRIMARY);
        UiRenderSupport.drawRoundedRect(
                pipeline, thumbX, thumbY, thumbSize, thumbSize,
                thumbSize * 0.5f, thumbColor, 1.0f, ThemePalette.BORDER, getAlpha()
        );
    }
}
