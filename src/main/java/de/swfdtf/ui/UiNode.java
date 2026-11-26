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
    /** Ordered child UI nodes. */
    protected final List<UiNode> children = new ArrayList<>();

    /**
     * Creates a base UI node.
     */
    protected UiNode() {
    }

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
     *
     * @param pipeline       2D render pipeline
     * @param fontManager    font manager
     * @param textureManager texture manager
     * @param elapsedSeconds total elapsed clock seconds
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
     *
     * @param pipeline       2D render pipeline
     * @param effectiveAlpha effective node opacity
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
     * @param lx logical X coordinate
     * @param ly logical Y coordinate
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
     * @param lx     logical X coordinate
     * @param ly     logical Y coordinate
     * @param button GLFW mouse button code
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
     * @param lx     logical X coordinate
     * @param ly     logical Y coordinate
     * @param button GLFW mouse button code
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
     * @param lx      logical X coordinate
     * @param ly      logical Y coordinate
     * @param scrollY scroll wheel delta
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
     * @param key  GLFW key code
     * @param mods active GLFW modifier flags
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
     * @param ch typed Unicode character
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

    /**
     * Returns whether interactive is active.
     *
     * @return the resulting value
     */
    protected boolean isInteractive() {
        return focusable;
    }

    /**
     * Handles the {@code onMouseMoved} callback.
     *
     * @param lx the lx argument
     * @param ly the ly argument
     * @param inside the inside argument
     */
    protected void onMouseMoved(float lx, float ly, boolean inside) {
    }

    /**
     * Handles the {@code onMouseDown} callback.
     *
     * @param lx the lx argument
     * @param ly the ly argument
     * @param button the button argument
     * @return the resulting value
     */
    protected boolean onMouseDown(float lx, float ly, int button) {
        return false;
    }

    /**
     * Handles the {@code onMouseUp} callback.
     *
     * @param lx the lx argument
     * @param ly the ly argument
     * @param button the button argument
     * @return the resulting value
     */
    protected boolean onMouseUp(float lx, float ly, int button) {
        return false;
    }

    /**
     * Handles the {@code onMouseScroll} callback.
     *
     * @param lx the lx argument
     * @param ly the ly argument
     * @param scrollY the scrollY argument
     * @return the resulting value
     */
    protected boolean onMouseScroll(float lx, float ly, double scrollY) {
        return false;
    }

    /**
     * Handles the {@code onKeyPressed} callback.
     *
     * @param key the key argument
     * @param mods the mods argument
     * @return the resulting value
     */
    protected boolean onKeyPressed(int key, int mods) {
        return false;
    }

    /**
     * Handles the {@code onCharTyped} callback.
     *
     * @param ch the ch argument
     * @return the resulting value
     */
    protected boolean onCharTyped(char ch) {
        return false;
    }

    /**
     * Sets the hoveredDeep value.
     *
     * @param hovered the hovered argument
     */
    public void setHoveredDeep(boolean hovered) {
        this.hovered = hovered;
        for (UiNode child : children) {
            child.setHoveredDeep(hovered);
        }
    }

    /**
     * Executes {@code addChild}.
     *
     * @param child the child argument
     * @return the resulting value
     */
    public UiNode addChild(UiNode child) {
        if (child != null) {
            child.parent = this;
            children.add(child);
        }
        return this;
    }

    /**
     * Executes {@code removeChild}.
     *
     * @param child the child argument
     * @return the resulting value
     */
    public boolean removeChild(UiNode child) {
        if (children.remove(child)) {
            child.parent = null;
            return true;
        }
        return false;
    }

    /**
     * Executes {@code clearChildren}.
     */
    public void clearChildren() {
        for (UiNode child : children) {
            child.parent = null;
        }
        children.clear();
    }

    /**
     * Returns the children value.
     *
     * @return the resulting value
     */
    public List<UiNode> getChildren() {
        return Collections.unmodifiableList(children);
    }

    /**
     * Returns the parent value.
     *
     * @return the resulting value
     */
    public UiNode getParent() {
        return parent;
    }

    /**
     * Returns the contentBounds value.
     *
     * @return the resulting value
     */
    public Rect2f getContentBounds() {
        float x = bounds.x() + paddingLeft;
        float y = bounds.y() + paddingTop;
        float w = Math.max(0.0f, bounds.width() - paddingLeft - paddingRight);
        float h = Math.max(0.0f, bounds.height() - paddingTop - paddingBottom);
        return new Rect2f(x, y, w, h);
    }

    /**
     * Sets the padding value.
     *
     * @param all the all argument
     * @return the resulting value
     */
    public UiNode setPadding(float all) {
        this.paddingLeft = all;
        this.paddingTop = all;
        this.paddingRight = all;
        this.paddingBottom = all;
        return this;
    }

    /**
     * Executes {@code setPadding}.
     *
     * @param horizontal the horizontal argument
     * @param vertical the vertical argument
     * @return the resulting value
     */
    public UiNode setPadding(float horizontal, float vertical) {
        this.paddingLeft = horizontal;
        this.paddingRight = horizontal;
        this.paddingTop = vertical;
        this.paddingBottom = vertical;
        return this;
    }

    /**
     * Executes {@code setPadding}.
     *
     * @param left the left argument
     * @param top the top argument
     * @param right the right argument
     * @param bottom the bottom argument
     * @return the resulting value
     */
    public UiNode setPadding(float left, float top, float right, float bottom) {
        this.paddingLeft = left;
        this.paddingTop = top;
        this.paddingRight = right;
        this.paddingBottom = bottom;
        return this;
    }

    /**
     * Returns the id value.
     *
     * @return the resulting value
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the id value.
     *
     * @param id the id argument
     * @return the resulting value
     */
    public UiNode setId(String id) {
        this.id = id != null ? id : "";
        return this;
    }

    /**
     * Returns the bounds value.
     *
     * @return the resulting value
     */
    public Rect2f getBounds() {
        return bounds;
    }

    /**
     * Sets the bounds value.
     *
     * @param bounds the bounds argument
     * @return the resulting value
     */
    public UiNode setBounds(Rect2f bounds) {
        this.bounds = bounds != null ? bounds : new Rect2f(0, 0, 0, 0);
        return this;
    }

    /**
     * Returns the preferredWidth value.
     *
     * @return the resulting value
     */
    public float getPreferredWidth() {
        return preferredWidth;
    }

    /**
     * Sets the preferredWidth value.
     *
     * @param w the w argument
     * @return the resulting value
     */
    public UiNode setPreferredWidth(float w) {
        this.preferredWidth = w;
        return this;
    }

    /**
     * Returns the preferredHeight value.
     *
     * @return the resulting value
     */
    public float getPreferredHeight() {
        return preferredHeight;
    }

    /**
     * Sets the preferredHeight value.
     *
     * @param h the h argument
     * @return the resulting value
     */
    public UiNode setPreferredHeight(float h) {
        this.preferredHeight = h;
        return this;
    }

    /**
     * Executes {@code setPreferredSize}.
     *
     * @param w the w argument
     * @param h the h argument
     * @return the resulting value
     */
    public UiNode setPreferredSize(float w, float h) {
        this.preferredWidth = w;
        this.preferredHeight = h;
        return this;
    }

    /**
     * Returns the paddingLeft value.
     *
     * @return the resulting value
     */
    public float getPaddingLeft() {
        return paddingLeft;
    }

    /**
     * Returns the paddingTop value.
     *
     * @return the resulting value
     */
    public float getPaddingTop() {
        return paddingTop;
    }

    /**
     * Returns the paddingRight value.
     *
     * @return the resulting value
     */
    public float getPaddingRight() {
        return paddingRight;
    }

    /**
     * Returns the paddingBottom value.
     *
     * @return the resulting value
     */
    public float getPaddingBottom() {
        return paddingBottom;
    }

    /**
     * Returns whether visible is active.
     *
     * @return the resulting value
     */
    public boolean isVisible() {
        return visible;
    }

    /**
     * Sets the visible value.
     *
     * @param visible the visible argument
     * @return the resulting value
     */
    public UiNode setVisible(boolean visible) {
        this.visible = visible;
        return this;
    }

    /**
     * Returns whether enabled is active.
     *
     * @return the resulting value
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets the enabled value.
     *
     * @param enabled the enabled argument
     * @return the resulting value
     */
    public UiNode setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /**
     * Returns whether focusable is active.
     *
     * @return the resulting value
     */
    public boolean isFocusable() {
        return focusable;
    }

    /**
     * Sets the focusable value.
     *
     * @param focusable the focusable argument
     * @return the resulting value
     */
    public UiNode setFocusable(boolean focusable) {
        this.focusable = focusable;
        return this;
    }

    /**
     * Returns whether hovered is active.
     *
     * @return the resulting value
     */
    public boolean isHovered() {
        return hovered;
    }

    /**
     * Returns whether focused is active.
     *
     * @return the resulting value
     */
    public boolean isFocused() {
        return focused;
    }

    /**
     * Sets the focused value.
     *
     * @param focused the focused argument
     * @return the resulting value
     */
    public UiNode setFocused(boolean focused) {
        this.focused = focused;
        return this;
    }

    /**
     * Returns whether pressed is active.
     *
     * @return the resulting value
     */
    public boolean isPressed() {
        return pressed;
    }

    /**
     * Returns the alpha value.
     *
     * @return the resulting value
     */
    public float getAlpha() {
        return alpha;
    }

    /**
     * Sets the alpha value.
     *
     * @param alpha the alpha argument
     * @return the resulting value
     */
    public UiNode setAlpha(float alpha) {
        this.alpha = Math.max(0.0f, Math.min(1.0f, alpha));
        return this;
    }

    /**
     * Returns the backgroundColorHex value.
     *
     * @return the resulting value
     */
    public String getBackgroundColorHex() {
        return backgroundColorHex;
    }

    /**
     * Sets the backgroundColorHex value.
     *
     * @param hex the hex argument
     * @return the resulting value
     */
    public UiNode setBackgroundColorHex(String hex) {
        this.backgroundColorHex = hex;
        return this;
    }

    /**
     * Returns the borderColorHex value.
     *
     * @return the resulting value
     */
    public String getBorderColorHex() {
        return borderColorHex;
    }

    /**
     * Sets the borderColorHex value.
     *
     * @param hex the hex argument
     * @return the resulting value
     */
    public UiNode setBorderColorHex(String hex) {
        this.borderColorHex = hex;
        return this;
    }

    /**
     * Returns the borderThickness value.
     *
     * @return the resulting value
     */
    public float getBorderThickness() {
        return borderThickness;
    }

    /**
     * Sets the borderThickness value.
     *
     * @param t the t argument
     * @return the resulting value
     */
    public UiNode setBorderThickness(float t) {
        this.borderThickness = Math.max(0.0f, t);
        return this;
    }

    /**
     * Returns the cornerRadius value.
     *
     * @return the resulting value
     */
    public float getCornerRadius() {
        return cornerRadius;
    }

    /**
     * Sets the cornerRadius value.
     *
     * @param r the r argument
     * @return the resulting value
     */
    public UiNode setCornerRadius(float r) {
        this.cornerRadius = Math.max(0.0f, r);
        return this;
    }
}
