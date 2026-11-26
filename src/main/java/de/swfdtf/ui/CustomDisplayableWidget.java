package de.swfdtf.ui;

import de.swfdtf.graphics.CustomDisplayable;
import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Vec2f;

/**
 * UI widget wrapping any {@link CustomDisplayable} (such as {@link de.swfdtf.graphics.LayeredImage}
 * or {@link de.swfdtf.graphics.ParticleSystem}) inside declarative UI layouts.
 */
public class CustomDisplayableWidget extends UiNode {

    private CustomDisplayable displayable;

    /**
     * Creates a widget wrapping the given {@link CustomDisplayable}.
     *
     * @param displayable custom displayable instance (nullable)
     */
    public CustomDisplayableWidget(CustomDisplayable displayable) {
        this.displayable = displayable;
    }

    /**
     * Returns wrapped {@link CustomDisplayable}, or {@code null}.
     *
     * @return wrapped {@link CustomDisplayable}, or {@code null}
     */
    public CustomDisplayable getDisplayable() {
        return displayable;
    }

    /**
     * Replaces the wrapped {@link CustomDisplayable}.
     *
     * @param displayable custom displayable instance (nullable)
     * @return {@code this}
     */
    public CustomDisplayableWidget setDisplayable(CustomDisplayable displayable) {
        this.displayable = displayable;
        return this;
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        float dispW = displayable != null ? displayable.getPreferredWidth() : 0.0f;
        float dispH = displayable != null ? displayable.getPreferredHeight() : 0.0f;
        float w = getPreferredWidth() > 0.0f
                ? getPreferredWidth()
                : (dispW > 0.0f ? dispW : availableWidth);
        float h = getPreferredHeight() > 0.0f
                ? getPreferredHeight()
                : (dispH > 0.0f ? dispH : availableHeight);
        return new Vec2f(w, h);
    }

    @Override
    public void update(double dt) {
        if (displayable != null) {
            displayable.update(dt);
        }
        super.update(dt);
    }

    @Override
    protected void renderSelf(
            RenderPipeline2D pipeline,
            FontManager fontManager,
            TextureManager textureManager,
            double elapsedSeconds
    ) {
        if (displayable != null) {
            displayable.render(pipeline, getContentBounds(), getAlpha(), ColorMatrix4f.identity());
        }
    }
}
