package de.swfdtf.ui;

import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.Vec2f;
import org.lwjgl.glfw.GLFW;

/**
 * Interactive image-backed button supporting distinct idle, hover, selected, and disabled asset paths.
 */
public class ImageButton extends UiNode {

    private String idlePath;
    private String hoverPath;
    private String disabledPath;
    private String selectedPath;
    private Runnable onClick;
    private boolean selected = false;
    private boolean armed = false;

    /**
     * Creates an {@code ImageButton} with idle/hover image paths and click handler.
     *
     * @param idlePath  idle image asset path
     * @param hoverPath hover/focus image asset path (nullable; falls back to {@code idlePath})
     * @param onClick   click callback (nullable)
     */
    public ImageButton(String idlePath, String hoverPath, Runnable onClick) {
        this.idlePath = idlePath;
        this.hoverPath = hoverPath;
        this.onClick = onClick;
        setFocusable(true);
    }

    /**
     * Configures the texture asset path shown while disabled.
     *
     * @param disabledPath disabled image path (nullable)
     * @return {@code this}
     */
    public ImageButton setDisabledPath(String disabledPath) {
        this.disabledPath = disabledPath;
        return this;
    }

    /**
     * Returns disabled image path, or {@code null}.
     *
     * @return disabled image path, or {@code null}
     */
    public String getDisabledPath() {
        return disabledPath;
    }

    /**
     * Sets the idle image asset path.
     *
     * @param idlePath idle image path
     * @return {@code this}
     */
    public ImageButton setIdlePath(String idlePath) {
        this.idlePath = idlePath;
        return this;
    }

    /**
     * Returns idle image path.
     *
     * @return idle image path
     */
    public String getIdlePath() {
        return idlePath;
    }

    /**
     * Sets the hover/focus image asset path.
     *
     * @param hoverPath hover image path
     * @return {@code this}
     */
    public ImageButton setHoverPath(String hoverPath) {
        this.hoverPath = hoverPath;
        return this;
    }

    /**
     * Returns hover image path.
     *
     * @return hover image path
     */
    public String getHoverPath() {
        return hoverPath;
    }

    /**
     * Sets an optional selected/pressed image asset path.
     *
     * @param selectedPath selected image path (nullable)
     * @return {@code this}
     */
    public ImageButton setSelectedPath(String selectedPath) {
        this.selectedPath = selectedPath;
        return this;
    }

    /**
     * Returns selected image path.
     *
     * @return selected image path
     */
    public String getSelectedPath() {
        return selectedPath;
    }

    /**
     * Sets the selected state.
     *
     * @param selected {@code true} if selected
     * @return {@code this}
     */
    public ImageButton setSelected(boolean selected) {
        this.selected = selected;
        return this;
    }

    /**
     * Returns {@code true} if selected.
     *
     * @return {@code true} if selected
     */
    public boolean isSelected() {
        return selected;
    }

    /**
     * Sets the click callback.
     *
     * @param onClick click callback (nullable)
     * @return {@code this}
     */
    public ImageButton setOnClick(Runnable onClick) {
        this.onClick = onClick;
        return this;
    }

    /**
     * Returns click callback.
     *
     * @return click callback
     */
    public Runnable getOnClick() {
        return onClick;
    }

    /**
     * Resolves which image asset path should currently be displayed based on enabled, selected,
     * pressed, hovered, and focused state.
     *
     * @return active image asset path
     */
    public String getCurrentImagePath() {
        if (!isEnabled() && disabledPath != null && !disabledPath.isBlank()) {
            return disabledPath;
        }
        if ((selected || isPressed() || armed) && selectedPath != null && !selectedPath.isBlank()) {
            return selectedPath;
        }
        if ((isHovered() || isFocused() || isPressed() || armed) && hoverPath != null && !hoverPath.isBlank()) {
            return hoverPath;
        }
        return idlePath;
    }

    /**
     * Invokes the click callback if enabled.
     */
    public void click() {
        if (isEnabled() && onClick != null) {
            onClick.run();
        }
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        float w = getPreferredWidth() > 0.0f
                ? getPreferredWidth()
                : Math.min(200.0f, availableWidth > 0 ? availableWidth : 200.0f);
        float h = getPreferredHeight() > 0.0f
                ? getPreferredHeight()
                : Math.min(64.0f, availableHeight > 0 ? availableHeight : 64.0f);
        return new Vec2f(w, h);
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

    @Override
    protected void renderSelf(
            RenderPipeline2D pipeline,
            FontManager fontManager,
            TextureManager textureManager,
            double elapsedSeconds
    ) {
        float effectiveAlpha = isEnabled() ? getAlpha() : getAlpha() * 0.5f;
        UiRenderSupport.drawTexture(pipeline, textureManager, getCurrentImagePath(), getBounds(), null, effectiveAlpha);
    }
}
