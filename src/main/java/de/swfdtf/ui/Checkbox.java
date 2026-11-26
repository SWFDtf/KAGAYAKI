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
 * Focusable boolean checkbox widget supporting mouse clicks and keyboard {@code Space}/{@code Enter}.
 */
public class Checkbox extends UiNode {

    private String label = "";
    private boolean checked = false;
    private Consumer<Boolean> onChanged;

    /**
     * Creates an unchecked {@code Checkbox} with an empty label.
     */
    public Checkbox() {
        setFocusable(true);
        setPreferredSize(240.0f, 38.0f);
    }

    /**
     * Creates an unchecked {@code Checkbox} with the specified label.
     *
     * @param label display text
     */
    public Checkbox(String label) {
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
    public Checkbox setLabel(String label) {
        this.label = label != null ? label : "";
        return this;
    }

    /**
     * Returns whether the checkbox is checked.
     *
     * @return {@code true} if checked
     */
    public boolean isChecked() {
        return checked;
    }

    /**
     * Sets checked state without invoking {@code onChanged}.
     *
     * @param checked target state
     * @return {@code this}
     */
    public Checkbox setChecked(boolean checked) {
        return setChecked(checked, false);
    }

    /**
     * Sets checked state and optionally notifies {@code onChanged}.
     *
     * @param checked target state
     * @param notify  whether to fire change listener
     * @return {@code this}
     */
    public Checkbox setChecked(boolean checked, boolean notify) {
        boolean changed = this.checked != checked;
        this.checked = checked;
        if (notify && changed && onChanged != null) {
            onChanged.accept(this.checked);
        }
        return this;
    }

    /**
     * Toggles checked state if enabled and notifies {@code onChanged}.
     *
     * @return updated checked state
     */
    public boolean toggle() {
        if (!isEnabled()) {
            return checked;
        }
        setChecked(!checked, true);
        return checked;
    }

    /**
     * Registers a callback invoked when checked state changes via interaction.
     *
     * @param onChanged change listener (nullable)
     * @return {@code this}
     */
    public Checkbox setOnChanged(Consumer<Boolean> onChanged) {
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
        float boxSize = Math.min(24.0f, Math.max(14.0f, b.height() - 10.0f));
        float bx = b.x() + 4.0f;
        float by = b.y() + (b.height() - boxSize) * 0.5f;
        String border = isFocused() || isHovered() ? ThemePalette.ACCENT_HOVER : ThemePalette.BORDER;
        String fill = checked ? ThemePalette.ACCENT_IDLE : ThemePalette.ELEVATED;
        pipeline.drawRoundedRect(
                bx, by, boxSize, boxSize, 4.0f,
                ThemePalette.premultipliedRgba(fill, getAlpha()),
                1.5f,
                ThemePalette.premultipliedRgba(border, getAlpha())
        );

        if (!label.isEmpty()) {
            FontManager fm = UiRenderSupport.resolveFontManager(fontManager);
            StyledTextLayout layout = StyledTextLayout.layout(
                    TextTagParser.parse(label),
                    fm,
                    Math.max(10.0f, b.width() - boxSize - 18.0f),
                    20.0f,
                    1.1f,
                    ThemePalette.TEXT_PRIMARY,
                    true
            );
            layout.render(pipeline, textureManager, bx + boxSize + 10.0f, b.y() + 8.0f, elapsedSeconds, -1, getAlpha());
        }
    }
}
