package de.swfdtf.ui;

import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.StyledTextLayout;
import de.swfdtf.graphics.TextTagParser;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * Pill-style boolean toggle switch widget supporting mouse clicks and keyboard {@code Space}/{@code Enter}.
 */
public class ToggleSwitch extends UiNode {

    private String label = "";
    private boolean toggled = false;
    private Consumer<Boolean> onChanged;

    /**
     * Creates an untoggled {@code ToggleSwitch} with an empty label.
     */
    public ToggleSwitch() {
        setFocusable(true);
        setPreferredSize(240.0f, 38.0f);
    }

    /**
     * Creates an untoggled {@code ToggleSwitch} with the specified label.
     *
     * @param label display text
     */
    public ToggleSwitch(String label) {
        this();
        setLabel(label);
    }

    /**
     * Returns the display label.
     *
     * @return display label
     */
    public String getLabel() {
        return label;
    }

    /**
     * Sets the display label.
     *
     * @param label label text (null treated as empty string)
     * @return {@code this}
     */
    public ToggleSwitch setLabel(String label) {
        this.label = label != null ? label : "";
        return this;
    }

    /**
     * Returns whether the switch is currently on.
     *
     * @return {@code true} if on
     */
    public boolean isToggled() {
        return toggled;
    }

    /**
     * Sets switch state without invoking {@code onChanged}.
     *
     * @param toggled target state
     * @return {@code this}
     */
    public ToggleSwitch setToggled(boolean toggled) {
        return setToggled(toggled, false);
    }

    /**
     * Sets switch state and optionally notifies {@code onChanged}.
     *
     * @param toggled target state
     * @param notify  whether to fire change listener
     * @return {@code this}
     */
    public ToggleSwitch setToggled(boolean toggled, boolean notify) {
        boolean changed = this.toggled != toggled;
        this.toggled = toggled;
        if (notify && changed && onChanged != null) {
            onChanged.accept(this.toggled);
        }
        return this;
    }

    /**
     * Flips switch state if enabled and notifies {@code onChanged}.
     *
     * @return updated toggle state
     */
    public boolean toggle() {
        if (!isEnabled()) {
            return toggled;
        }
        setToggled(!toggled, true);
        return toggled;
    }

    /**
     * Registers a callback invoked when toggle state changes via interaction.
     *
     * @param onChanged change listener (nullable)
     * @return {@code this}
     */
    public ToggleSwitch setOnChanged(Consumer<Boolean> onChanged) {
        this.onChanged = onChanged;
        return this;
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        float w = getPreferredWidth() > 0.0f ? getPreferredWidth() : 240.0f;
        float h = getPreferredHeight() > 0.0f ? getPreferredHeight() : 38.0f;
        return new Vec2f(w, h);
    }

    @Override
    protected boolean onMouseDown(float lx, float ly, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && isEnabled()) {
            setFocused(true);
            toggle();
            return true;
        }
        return false;
    }

    @Override
    protected boolean onKeyPressed(int key, int mods) {
        if (!isEnabled() || !isFocused()) {
            return false;
        }
        if (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            toggle();
            return true;
        }
        return false;
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
        float trackW = 44.0f;
        float trackH = 22.0f;
        float tx = b.x() + 4.0f;
        float ty = b.y() + (b.height() - trackH) * 0.5f;
        String trackFill = toggled ? ThemePalette.SUCCESS : ThemePalette.ELEVATED;
        String border = isFocused() || isHovered() ? ThemePalette.ACCENT_HOVER : ThemePalette.BORDER;
        pipeline.drawRoundedRect(
                tx, ty, trackW, trackH, trackH * 0.5f,
                ThemePalette.premultipliedRgba(trackFill, getAlpha()),
                1.5f,
                ThemePalette.premultipliedRgba(border, getAlpha())
        );

        float knobSize = trackH - 6.0f;
        float kx = toggled ? (tx + trackW - knobSize - 3.0f) : (tx + 3.0f);
        pipeline.drawRoundedRect(
                kx, ty + 3.0f, knobSize, knobSize, knobSize * 0.5f,
                ThemePalette.premultipliedRgba(ThemePalette.TEXT_PRIMARY, getAlpha()),
                0.0f,
                new float[]{0.0f, 0.0f, 0.0f, 0.0f}
        );

        if (!label.isEmpty()) {
            FontManager fm = UiRenderSupport.resolveFontManager(fontManager);
            StyledTextLayout layout = StyledTextLayout.layout(
                    TextTagParser.parse(label),
                    fm,
                    Math.max(10.0f, b.width() - trackW - 18.0f),
                    20.0f,
                    1.1f,
                    ThemePalette.TEXT_PRIMARY,
                    true
            );
            layout.render(pipeline, textureManager, tx + trackW + 10.0f, b.y() + 8.0f, elapsedSeconds, -1, getAlpha());
        }
    }
}
