package de.swfdtf.ui;

import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base layout and interaction node for all KAGAYAKI declarative UI containers and widgets.
 */
public abstract class UiNode {

    private String id = "";
    private Rect2f bounds = new Rect2f(0.0f, 0.0f, 0.0f, 0.0f);
    private float preferredWidth = 0.0f;
    private float preferredHeight = 0.0f;
    private float paddingLeft = 0.0f;
    private float paddingTop = 0.0f;
    private float paddingRight = 0.0f;
    private float paddingBottom = 0.0f;

    private boolean visible = true;
    private boolean enabled = true;
    private boolean focusable = false;
    private boolean hovered = false;
    private boolean focused = false;
    private boolean pressed = false;
    private float alpha = 1.0f;

    private String backgroundColorHex;
    private String borderColorHex;
    private float borderThickness = 0.0f;
    private float cornerRadius = 0.0f;

    private UiNode parent;
    protected final List<UiNode> children = new ArrayList<>();

    /**
     * Computes preferred dimensions given available container space.
     *
     * @param availableWidth  available width in logical pixels
     * @param availableHeight available height in logical pixels
     * @return desired {@link Vec2f} dimensions
     */
    public Vec2f measure(float availableWidth, float availableHeight) {
        float w = preferredWidth > 0.0f ? preferredWidth : availableWidth;
        float h = preferredHeight > 0.0f ? preferredHeight : availableHeight;
        return new Vec2f(w, h);
    }

    /**
     * Assigns resolved layout bounds and recursively lays out children.
     *
     * @param resolvedBounds resolved bounds in logical canvas coordinates
     */
    public void layout(Rect2f resolvedBounds) {
        this.bounds = resolvedBounds != null ? resolvedBounds : new Rect2f(0.0f, 0.0f, 0.0f, 0.0f);
        Rect2f inner = getContentBounds();
        for (UiNode child : children) {
            if (child.isVisible()) {
                child.layout(inner);
            }
        }
    }

    /**
     * Advances animations/timers for this node and visible children.
     *
     * @param dt frame delta in seconds
     */
    public void update(double dt) {
        for (UiNode child : children) {
            if (child.isVisible()) {
                child.update(dt);
            }
        }
    }

    /**
     * Renders this node and its visible children via {@link RenderPipeline2D}.
     *
     * @param pipeline       active 2D render pipeline
     * @param fontManager    font manager (nullable in headless tests)
     * @param textureManager texture manager (nullable in headless tests)
     * @param elapsedSeconds total elapsed clock seconds for animated text/effects
     */
    public void render(
            RenderPipeline2D pipeline,
            FontManager fontManager,
            TextureManager textureManager,
            double elapsedSeconds
    ) {
        if (!visible || alpha <= 0.0f) {
            return;
        }
        drawBackgroundBox(pipeline, alpha);
        renderSelf(pipeline, fontManager, textureManager, elapsedSeconds);
        for (UiNode child : children) {
            if (child.isVisible()) {
                child.render(pipeline, fontManager, textureManager, elapsedSeconds);
            }
        }
    }

    /**
     * Renders node-specific content after the optional background box.
     */
    protected void renderSelf(
            RenderPipeline2D pipeline,
            FontManager fontManager,
            TextureManager textureManager,
            double elapsedSeconds
    ) {
    }

    /**
     * Draws this node's optional rounded/bordered background rectangle if configured.
     */
    protected void drawBackgroundBox(RenderPipeline2D pipeline, float effectiveAlpha) {
        if (pipeline == null || !pipeline.isInitialized() || bounds.width() <= 0.0f || bounds.height() <= 0.0f) {
            return;
        }
        if (backgroundColorHex != null || (borderColorHex != null && borderThickness > 0.0f)) {
            float[] fill = backgroundColorHex != null
                    ? ThemePalette.premultipliedRgba(backgroundColorHex, effectiveAlpha)
                    : new float[]{0.0f, 0.0f, 0.0f, 0.0f};
            float[] border = borderColorHex != null
                    ? ThemePalette.premultipliedRgba(borderColorHex, effectiveAlpha)
                    : new float[]{0.0f, 0.0f, 0.0f, 0.0f};
            pipeline.drawRoundedRect(
                    bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                    cornerRadius, fill, borderThickness, border
            );
        }
    }

    /**
     * Dispatches a mouse move event in logical canvas coordinates and updates {@code hovered}.
     *
     * @return deepest hovered interactive node, if any
     */
    public UiNode dispatchMouseMove(float lx, float ly) {
        if (!visible) {
            setHoveredDeep(false);
            return null;
        }
        UiNode hitChild = null;
        for (int i = children.size() - 1; i >= 0; i--) {
            UiNode child = children.get(i);
            UiNode candidate = child.dispatchMouseMove(lx, ly);
            if (candidate != null && hitChild == null) {
                hitChild = candidate;
            }
        }
        boolean inside = bounds.contains(lx, ly);
        this.hovered = inside && enabled && (hitChild == null || hitChild == this);
        onMouseMoved(lx, ly, inside);
        if (hitChild != null) {
            return hitChild;
        }
        return (inside && isInteractive()) ? this : null;
    }

    /**
     * Dispatches a mouse button press event in top-down z-order (last child first).
     *
     * @return {@code true} if consumed by this node or a descendant
     */
    public boolean dispatchMouseDown(float lx, float ly, int button) {
        if (!visible || !enabled || !bounds.contains(lx, ly)) {
            return false;
        }
        for (int i = children.size() - 1; i >= 0; i--) {
            if (children.get(i).dispatchMouseDown(lx, ly, button)) {
                return true;
            }
        }
        if (onMouseDown(lx, ly, button)) {
            this.pressed = true;
            return true;
        }
        return false;
    }

    /**
     * Dispatches a mouse button release event.
     *
     * @return {@code true} if handled
     */
    public boolean dispatchMouseUp(float lx, float ly, int button) {
        if (!visible) {
            return false;
        }
        boolean handled = false;
        for (int i = children.size() - 1; i >= 0; i--) {
            if (children.get(i).dispatchMouseUp(lx, ly, button)) {
                handled = true;
            }
        }
        boolean wasPressed = this.pressed;
        this.pressed = false;
        if (enabled && (wasPressed || bounds.contains(lx, ly))) {
            if (onMouseUp(lx, ly, button)) {
                handled = true;
            }
        }
        return handled;
    }

    /**
     * Dispatches mouse wheel scrolling inside bounds.
     *
     * @return {@code true} if consumed
     */
    public boolean dispatchMouseScroll(float lx, float ly, double scrollY) {
        if (!visible || !enabled || !bounds.contains(lx, ly)) {
            return false;
        }
        for (int i = children.size() - 1; i >= 0; i--) {
            if (children.get(i).dispatchMouseScroll(lx, ly, scrollY)) {
                return true;
            }
        }
        return onMouseScroll(lx, ly, scrollY);
    }

    /**
     * Dispatches a key press to this node or its children.
     *
     * @return {@code true} if consumed
     */
    public boolean dispatchKeyPressed(int key, int mods) {
        if (!visible || !enabled) {
            return false;
        }
        for (int i = children.size() - 1; i >= 0; i--) {
            if (children.get(i).dispatchKeyPressed(key, mods)) {
                return true;
            }
        }
        return onKeyPressed(key, mods);
    }

    /**
     * Dispatches a typed Unicode character to this node or its children.
     *
     * @return {@code true} if consumed
     */
    public boolean dispatchCharTyped(char ch) {
        if (!visible || !enabled) {
            return false;
        }
        for (int i = children.size() - 1; i >= 0; i--) {
            if (children.get(i).dispatchCharTyped(ch)) {
                return true;
            }
        }
        return onCharTyped(ch);
    }

    protected boolean isInteractive() {
        return focusable;
    }

    protected void onMouseMoved(float lx, float ly, boolean inside) {
    }

    protected boolean onMouseDown(float lx, float ly, int button) {
        return false;
    }

    protected boolean onMouseUp(float lx, float ly, int button) {
        return false;
    }

    protected boolean onMouseScroll(float lx, float ly, double scrollY) {
        return false;
    }

    protected boolean onKeyPressed(int key, int mods) {
        return false;
    }

    protected boolean onCharTyped(char ch) {
        return false;
    }

    public void setHoveredDeep(boolean hovered) {
        this.hovered = hovered;
        for (UiNode child : children) {
            child.setHoveredDeep(hovered);
        }
    }

    public UiNode addChild(UiNode child) {
        if (child != null) {
            child.parent = this;
            children.add(child);
        }
        return this;
    }

    public boolean removeChild(UiNode child) {
        if (children.remove(child)) {
            child.parent = null;
            return true;
        }
        return false;
    }

    public void clearChildren() {
        for (UiNode child : children) {
            child.parent = null;
        }
        children.clear();
    }

    public List<UiNode> getChildren() {
        return Collections.unmodifiableList(children);
    }

    public UiNode getParent() {
        return parent;
    }

    public Rect2f getContentBounds() {
        float x = bounds.x() + paddingLeft;
        float y = bounds.y() + paddingTop;
        float w = Math.max(0.0f, bounds.width() - paddingLeft - paddingRight);
        float h = Math.max(0.0f, bounds.height() - paddingTop - paddingBottom);
        return new Rect2f(x, y, w, h);
    }

    public UiNode setPadding(float all) {
        this.paddingLeft = all;
        this.paddingTop = all;
        this.paddingRight = all;
        this.paddingBottom = all;
        return this;
    }

    public UiNode setPadding(float horizontal, float vertical) {
        this.paddingLeft = horizontal;
        this.paddingRight = horizontal;
        this.paddingTop = vertical;
        this.paddingBottom = vertical;
        return this;
    }

    public UiNode setPadding(float left, float top, float right, float bottom) {
        this.paddingLeft = left;
        this.paddingTop = top;
        this.paddingRight = right;
        this.paddingBottom = bottom;
        return this;
    }

    public String getId() { return id; }
    public UiNode setId(String id) { this.id = id != null ? id : ""; return this; }
    public Rect2f getBounds() { return bounds; }
    public UiNode setBounds(Rect2f bounds) { this.bounds = bounds != null ? bounds : new Rect2f(0, 0, 0, 0); return this; }
    public float getPreferredWidth() { return preferredWidth; }
    public UiNode setPreferredWidth(float w) { this.preferredWidth = w; return this; }
    public float getPreferredHeight() { return preferredHeight; }
    public UiNode setPreferredHeight(float h) { this.preferredHeight = h; return this; }
    public UiNode setPreferredSize(float w, float h) { this.preferredWidth = w; this.preferredHeight = h; return this; }
    public float getPaddingLeft() { return paddingLeft; }
    public float getPaddingTop() { return paddingTop; }
    public float getPaddingRight() { return paddingRight; }
    public float getPaddingBottom() { return paddingBottom; }
    public boolean isVisible() { return visible; }
    public UiNode setVisible(boolean visible) { this.visible = visible; return this; }
    public boolean isEnabled() { return enabled; }
    public UiNode setEnabled(boolean enabled) { this.enabled = enabled; return this; }
    public boolean isFocusable() { return focusable; }
    public UiNode setFocusable(boolean focusable) { this.focusable = focusable; return this; }
    public boolean isHovered() { return hovered; }
    public boolean isFocused() { return focused; }
    public UiNode setFocused(boolean focused) { this.focused = focused; return this; }
    public boolean isPressed() { return pressed; }
    public float getAlpha() { return alpha; }
    public UiNode setAlpha(float alpha) { this.alpha = Math.max(0.0f, Math.min(1.0f, alpha)); return this; }
    public String getBackgroundColorHex() { return backgroundColorHex; }
    public UiNode setBackgroundColorHex(String hex) { this.backgroundColorHex = hex; return this; }
    public String getBorderColorHex() { return borderColorHex; }
    public UiNode setBorderColorHex(String hex) { this.borderColorHex = hex; return this; }
    public float getBorderThickness() { return borderThickness; }
    public UiNode setBorderThickness(float t) { this.borderThickness = Math.max(0.0f, t); return this; }
    public float getCornerRadius() { return cornerRadius; }
    public UiNode setCornerRadius(float r) { this.cornerRadius = Math.max(0.0f, r); return this; }
}
