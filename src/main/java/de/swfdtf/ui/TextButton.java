package de.swfdtf.ui;

import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.StyledTextLayout;
import de.swfdtf.graphics.TextTagParser;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;
import org.lwjgl.glfw.GLFW;

/**
 * Interactive Dark-Slate button widget displaying rich text and reacting to mouse clicks as well
 * as keyboard {@code Enter}/{@code Space} activation when focused.
 */
public class TextButton extends UiNode {

    /** Default label font size in logical pixels. */
    public static final float DEFAULT_FONT_SIZE = 22.0f;

    /** Default corner radius in logical pixels. */
    public static final float DEFAULT_CORNER_RADIUS = 6.0f;

    private String text = "";
    private Runnable onClick;
    private boolean selected = false;
    private boolean armed = false;
    private float fontSize = DEFAULT_FONT_SIZE;

    private StyledTextLayout cachedLabelLayout;
    private boolean labelDirty = true;

    /**
     * Creates a {@code TextButton} with the given label and click handler.
     *
     * @param label   button label text
     * @param onClick click callback (nullable)
     */
    public TextButton(String label, Runnable onClick) {
        this.onClick = onClick;
        setText(label);
        setFocusable(true);
        setCornerRadius(DEFAULT_CORNER_RADIUS);
        setBorderThickness(1.0f);
        setPadding(16.0f, 10.0f);
    }

    /**
     * Updates the button label text.
     *
     * @param text new label text (null treated as empty string)
     * @return {@code this}
     */
    public TextButton setText(String text) {
        this.text = text != null ? text : "";
        this.labelDirty = true;
        return this;
    }

    /** @return button label text */
    public String getText() {
        return text;
    }

    /**
     * Updates the click callback invoked on mouse click or focused Enter/Space key press.
     *
     * @param onClick callback (nullable)
     * @return {@code this}
     */
    public TextButton setOnClick(Runnable onClick) {
        this.onClick = onClick;
        return this;
    }

    /** @return configured click callback */
    public Runnable getOnClick() {
        return onClick;
    }

    /**
     * Sets the active/selected toggle state (styled with {@link ThemePalette#ACTIVE_SELECTED}).
     *
     * @param selected {@code true} when selected
     * @return {@code this}
     */
    public TextButton setSelected(boolean selected) {
        this.selected = selected;
        return this;
    }

    /** @return {@code true} if selected */
    public boolean isSelected() {
        return selected;
    }

    /**
     * Sets the label font size in logical pixels.
     *
     * @param fontSize font size ({@code > 0})
     * @return {@code this}
     */
    public TextButton setFontSize(float fontSize) {
        if (Float.isFinite(fontSize) && fontSize > 0.0f) {
            this.fontSize = fontSize;
            this.labelDirty = true;
        }
        return this;
    }

    /** @return label font size */
    public float getFontSize() {
        return fontSize;
    }

    /**
     * Returns the resolved Dark-Slate {@link ThemePalette} surface hex color for the current state:
     * {@link ThemePalette#DISABLED}, {@link ThemePalette#ACTIVE_SELECTED},
     * {@link ThemePalette#ACCENT_HOVER}, or {@link ThemePalette#ELEVATED}.
     *
     * @return active surface hex color
     */
    public String getCurrentSurfaceColorHex() {
        if (!isEnabled()) {
            return ThemePalette.DISABLED;
        }
        if (selected || isPressed() || armed) {
            return ThemePalette.ACTIVE_SELECTED;
        }
        if (isHovered() || isFocused()) {
            return ThemePalette.ACCENT_HOVER;
        }
        return ThemePalette.ELEVATED;
    }

    /**
     * Returns the resolved {@link ThemePalette} border hex color for the current interaction state.
     *
     * @return active border hex color
     */
    public String getCurrentBorderColorHex() {
        if (!isEnabled()) {
            return ThemePalette.DISABLED;
        }
        if (selected || isPressed() || armed) {
            return ThemePalette.ACTIVE_SELECTED;
        }
        if (isHovered() || isFocused()) {
            return ThemePalette.ACCENT_HOVER;
        }
        return ThemePalette.BORDER;
    }

    /**
     * Returns the resolved {@link ThemePalette} label text hex color for the current state.
     *
     * @return active text hex color
     */
    public String getCurrentTextColorHex() {
        return isEnabled() ? ThemePalette.TEXT_PRIMARY : ThemePalette.DISABLED;
    }

    /**
     * Triggers the configured {@code onClick} action if the button is enabled.
     */
    public void click() {
        if (isEnabled() && onClick != null) {
            onClick.run();
        }
    }

    @Override
    protected boolean isInteractive() {
        return true;
    }

    @Override
    protected boolean onMouseDown(float lx, float ly, int button) {
        if (button == 0 && isEnabled() && getBounds().contains(lx, ly)) {
            armed = true;
            return true;
        }
        return false;
    }

    @Override
    protected boolean onMouseUp(float lx, float ly, int button) {
        if (button == 0 && armed) {
            armed = false;
            if (isEnabled() && getBounds().contains(lx, ly)) {
                click();
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean onKeyPressed(int key, int mods) {
        if (!isEnabled() || !isFocused()) {
            return false;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE) {
            click();
            return true;
        }
        return false;
    }

    private StyledTextLayout ensureLabelLayout(FontManager fm, boolean uploadGl) {
        if (cachedLabelLayout == null || labelDirty) {
            cachedLabelLayout = StyledTextLayout.layout(
                    TextTagParser.parse(text),
                    UiRenderSupport.resolveFontManager(fm),
                    Float.POSITIVE_INFINITY,
                    fontSize,
                    1.1f,
                    getCurrentTextColorHex(),
                    uploadGl
            );
            labelDirty = false;
        }
        return cachedLabelLayout;
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        StyledTextLayout layout = ensureLabelLayout(null, false);
        float minW = layout.getTotalWidth() + getPaddingLeft() + getPaddingRight();
        float minH = Math.max(40.0f, layout.getTotalHeight() + getPaddingTop() + getPaddingBottom());
        float w = getPreferredWidth() > 0.0f ? getPreferredWidth() : minW;
        float h = getPreferredHeight() > 0.0f ? getPreferredHeight() : minH;
        return new Vec2f(w, h);
    }

    @Override
    protected void drawBackgroundBox(RenderPipeline2D pipeline, float effectiveAlpha) {
        Rect2f b = getBounds();
        String fillHex = getBackgroundColorHex() != null ? getBackgroundColorHex() : getCurrentSurfaceColorHex();
        String borderHex = getBorderColorHex() != null ? getBorderColorHex() : getCurrentBorderColorHex();
        UiRenderSupport.drawRoundedRect(
                pipeline,
                b.x(), b.y(), b.width(), b.height(),
                getCornerRadius(),
                fillHex,
                getBorderThickness(),
                borderHex,
                effectiveAlpha
        );
    }

    @Override
    protected void renderSelf(
            RenderPipeline2D pipeline,
            FontManager fontManager,
            TextureManager textureManager,
            double elapsedSeconds
    ) {
        if (!UiRenderSupport.isPipelineReady(pipeline) || text.isEmpty()) {
            return;
        }
        StyledTextLayout layout = StyledTextLayout.layout(
                TextTagParser.parse(text),
                UiRenderSupport.resolveFontManager(fontManager),
                Math.max(1.0f, getContentBounds().width()),
                fontSize,
                1.1f,
                getCurrentTextColorHex(),
                true
        );
        Rect2f inner = getContentBounds();
        float tx = inner.x() + Math.max(0.0f, (inner.width() - layout.getTotalWidth()) * 0.5f);
        float ty = inner.y() + Math.max(0.0f, (inner.height() - layout.getTotalHeight()) * 0.5f);
        layout.render(pipeline, textureManager, tx, ty, elapsedSeconds, -1, getAlpha());
    }
}
