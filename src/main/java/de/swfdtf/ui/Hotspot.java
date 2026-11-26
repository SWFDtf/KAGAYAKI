package de.swfdtf.ui;

import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;
import org.lwjgl.glfw.GLFW;

import java.util.Objects;

/**
 * Clickable rectangular region typically placed inside an {@link Imagemap} or {@link Fixed} container.
 */
public class Hotspot extends UiNode {

    private Rect2f area;
    private Runnable onClick;
    private String hoveredColorHex;
    private boolean armed = false;

    /**
     * Creates a {@code Hotspot} covering {@code area} with an optional click handler.
     *
     * @param area    hotspot rectangle relative to parent {@link Imagemap} or logical canvas
     * @param onClick click callback (nullable)
     */
    public Hotspot(Rect2f area, Runnable onClick) {
        this.area = Objects.requireNonNull(area, "area must not be null");
        this.onClick = onClick;
        setBounds(area);
        setPreferredSize(area.width(), area.height());
        setFocusable(true);
    }

    /**
     * Returns configured hotspot area rectangle.
     *
     * @return configured hotspot area rectangle
     */
    public Rect2f getArea() {
        return area;
    }

    /**
     * Updates the hotspot area rectangle.
     *
     * @param area hotspot rectangle
     * @return {@code this}
     */
    public Hotspot setArea(Rect2f area) {
        this.area = Objects.requireNonNull(area, "area must not be null");
        setPreferredSize(area.width(), area.height());
        return this;
    }

    /**
     * Sets the click callback.
     *
     * @param onClick click callback (nullable)
     * @return {@code this}
     */
    public Hotspot setOnClick(Runnable onClick) {
        this.onClick = onClick;
        return this;
    }

    /**
     * Returns configured click callback.
     *
     * @return configured click callback
     */
    public Runnable getOnClick() {
        return onClick;
    }

    /**
     * Sets an optional overlay tint color drawn when this hotspot is hovered.
     *
     * @param hoveredColorHex hex color string (nullable)
     * @return {@code this}
     */
    public Hotspot setHoveredColorHex(String hoveredColorHex) {
        this.hoveredColorHex = hoveredColorHex;
        return this;
    }

    /**
     * Returns hover highlight hex color, or {@code null}.
     *
     * @return hover highlight hex color, or {@code null}
     */
    public String getHoveredColorHex() {
        return hoveredColorHex;
    }

    /**
     * Tests whether logical coordinate {@code (lx, ly)} falls inside this hotspot's resolved bounds.
     *
     * @param lx logical X coordinate
     * @param ly logical Y coordinate
     * @return {@code true} if inside
     */
    public boolean isInside(float lx, float ly) {
        return getBounds().contains(lx, ly);
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        return new Vec2f(area.width(), area.height());
    }

    @Override
    protected boolean isInteractive() {
        return true;
    }

    @Override
    protected boolean onMouseDown(float lx, float ly, int button) {
        if (button == 0 && isEnabled() && isInside(lx, ly)) {
            armed = true;
            return true;
        }
        return false;
    }

    @Override
    protected boolean onMouseUp(float lx, float ly, int button) {
        if (button == 0 && armed) {
            armed = false;
            if (isEnabled() && isInside(lx, ly)) {
                if (onClick != null) {
                    onClick.run();
                }
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
            if (onClick != null) {
                onClick.run();
            }
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
        if (isHovered() && hoveredColorHex != null && !hoveredColorHex.isBlank()) {
            Rect2f b = getBounds();
            UiRenderSupport.drawRect(pipeline, b.x(), b.y(), b.width(), b.height(), hoveredColorHex, getAlpha());
        }
    }
}
