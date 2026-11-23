package de.swfdtf.graphics;

import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Transform2D;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Base hierarchical node in the KAGAYAKI 2D scene graph.
 *
 * <p>A {@code SceneNode} represents either a textured sprite/quad ({@code textureId != 0}),
 * a solid or rounded-rectangle UI primitive ({@code textureId == 0}), or a container grouping
 * child nodes with inherited bounds, opacity, and color matrix transforms. Custom drawing can
 * be supplied either by subclassing {@link #onCustomRender(RenderPipeline2D)} or by setting a
 * {@link #setCustomRenderer(BiConsumer) customRenderer} callback.</p>
 */
public class SceneNode {

    private String id;
    private String tag;
    private int zOrder = 0;
    private boolean visible = true;
    private Transform2D transform = new Transform2D();
    private float width = 0.0f;
    private float height = 0.0f;
    private final float[] fillColorRgba = new float[]{1.0f, 1.0f, 1.0f, 1.0f};
    private boolean fillColorPremultiplied = false;
    private float cornerRadius = 0.0f;
    private float borderThickness = 0.0f;
    private final float[] borderColorRgba = new float[]{1.0f, 1.0f, 1.0f, 1.0f};
    private boolean borderColorPremultiplied = false;
    private int textureId = 0;
    private Rect2f uvRect = new Rect2f(0.0f, 0.0f, 1.0f, 1.0f);
    private Object userData;
    private SceneNode parent;
    private final List<SceneNode> children = new ArrayList<>();
    private BiConsumer<SceneNode, RenderPipeline2D> customRenderer;

    /**
     * Creates an empty {@code SceneNode} with default neutral transform, opaque white color,
     * full {@code (0, 0, 1, 1)} UV rect, and {@code visible = true}.
     */
    public SceneNode() {
    }

    /**
     * Creates a {@code SceneNode} initialized with an identifier.
     *
     * @param id optional node identifier
     */
    public SceneNode(String id) {
        this.id = id;
    }

    /**
     * Creates a {@code SceneNode} initialized with an identifier and lookup tag.
     *
     * @param id  optional node identifier
     * @param tag optional lookup tag
     */
    public SceneNode(String id, String tag) {
        this.id = id;
        this.tag = tag;
    }

    /**
     * Parses a CSS-style hex color string into 4 straight RGBA float components in {@code [0, 1]}.
     *
     * <p>Supported formats (with or without leading {@code '#'}):
     * {@code RGB}, {@code RGBA}, {@code RRGGBB}, and {@code RRGGBBAA}.</p>
     *
     * @param hex hex color string
     * @return 4-element straight RGBA float array {@code [r, g, b, a]}
     * @throws IllegalArgumentException if {@code hex} is null or malformed
     */
    public static float[] parseHexRgba(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("Hex color must not be null");
        }
        String raw = hex.trim();
        if (raw.startsWith("#")) {
            raw = raw.substring(1);
        }
        return switch (raw.length()) {
            case 3 -> new float[]{
                    parseHexNibble(raw.charAt(0)) / 15.0f,
                    parseHexNibble(raw.charAt(1)) / 15.0f,
                    parseHexNibble(raw.charAt(2)) / 15.0f,
                    1.0f
            };
            case 4 -> new float[]{
                    parseHexNibble(raw.charAt(0)) / 15.0f,
                    parseHexNibble(raw.charAt(1)) / 15.0f,
                    parseHexNibble(raw.charAt(2)) / 15.0f,
                    parseHexNibble(raw.charAt(3)) / 15.0f
            };
            case 6 -> new float[]{
                    parseHexByte(raw, 0) / 255.0f,
                    parseHexByte(raw, 2) / 255.0f,
                    parseHexByte(raw, 4) / 255.0f,
                    1.0f
            };
            case 8 -> new float[]{
                    parseHexByte(raw, 0) / 255.0f,
                    parseHexByte(raw, 2) / 255.0f,
                    parseHexByte(raw, 4) / 255.0f,
                    parseHexByte(raw, 6) / 255.0f
            };
            default -> throw new IllegalArgumentException("Invalid hex color: " + hex);
        };
    }

    private static int parseHexNibble(char c) {
        int digit = Character.digit(c, 16);
        if (digit < 0) {
            throw new IllegalArgumentException("Invalid hex digit: " + c);
        }
        return digit;
    }

    private static int parseHexByte(String hex, int index) {
        return (parseHexNibble(hex.charAt(index)) << 4) | parseHexNibble(hex.charAt(index + 1));
    }

    /** @return optional node identifier, or {@code null} */
    public String getId() {
        return id;
    }

    /**
     * Sets the node identifier.
     *
     * @param id identifier string (may be {@code null})
     * @return {@code this} for fluent chaining
     */
    public SceneNode setId(String id) {
        this.id = id;
        return this;
    }

    /** @return optional lookup tag, or {@code null} */
    public String getTag() {
        return tag;
    }

    /**
     * Sets the node tag used for lookup and bulk removal within a {@link SceneLayer}.
     *
     * @param tag tag string (may be {@code null})
     * @return {@code this} for fluent chaining
     */
    public SceneNode setTag(String tag) {
        this.tag = tag;
        return this;
    }

    /** @return local Z-order within the owning layer or parent */
    public int getZOrder() {
        return zOrder;
    }

    /**
     * Sets the local Z-order used for stable ascending draw sorting.
     *
     * @param zOrder Z-order priority
     * @return {@code this} for fluent chaining
     */
    public SceneNode setZOrder(int zOrder) {
        this.zOrder = zOrder;
        return this;
    }

    /** @return {@code true} if this node and its subtree should be rendered */
    public boolean isVisible() {
        return visible;
    }

    /**
     * Controls whether this node and its children are rendered.
     *
     * @param visible visibility flag
     * @return {@code this} for fluent chaining
     */
    public SceneNode setVisible(boolean visible) {
        this.visible = visible;
        return this;
    }

    /** @return non-null mutable {@link Transform2D} for this node */
    public Transform2D getTransform() {
        return transform;
    }

    /**
     * Replaces the {@link Transform2D} attached to this node.
     *
     * @param transform non-null transform state
     * @return {@code this} for fluent chaining
     */
    public SceneNode setTransform(Transform2D transform) {
        this.transform = Objects.requireNonNull(transform, "transform must not be null");
        return this;
    }

    /** @return unscaled source width in logical pixels */
    public float getWidth() {
        return width;
    }

    /**
     * Sets the unscaled source width in logical pixels.
     *
     * @param width width in logical pixels
     * @return {@code this} for fluent chaining
     */
    public SceneNode setWidth(float width) {
        this.width = width;
        return this;
    }

    /** @return unscaled source height in logical pixels */
    public float getHeight() {
        return height;
    }

    /**
     * Sets the unscaled source height in logical pixels.
     *
     * @param height height in logical pixels
     * @return {@code this} for fluent chaining
     */
    public SceneNode setHeight(float height) {
        this.height = height;
        return this;
    }

    /**
     * Sets both unscaled source width and height in logical pixels.
     *
     * @param width  width in logical pixels
     * @param height height in logical pixels
     * @return {@code this} for fluent chaining
     */
    public SceneNode setSize(float width, float height) {
        this.width = width;
        this.height = height;
        return this;
    }

    /**
     * Returns a defensive copy of the 4-element fill/tint RGBA color array.
     *
     * @return 4-float RGBA array {@code [r, g, b, a]}
     */
    public float[] getFillColorRgba() {
        return fillColorRgba.clone();
    }

    /**
     * Sets the straight RGBA fill/tint color from a 4-float array.
     *
     * @param rgba 4-element straight RGBA array {@code [r, g, b, a]}
     * @return {@code this} for fluent chaining
     */
    public SceneNode setFillColorRgba(float[] rgba) {
        Objects.requireNonNull(rgba, "rgba must not be null");
        if (rgba.length != 4) {
            throw new IllegalArgumentException("Expected 4 RGBA components, got " + rgba.length);
        }
        System.arraycopy(rgba, 0, this.fillColorRgba, 0, 4);
        this.fillColorPremultiplied = false;
        return this;
    }

    /**
     * Sets the straight RGBA fill/tint color from individual channel values in {@code [0, 1]}.
     *
     * @param r red channel
     * @param g green channel
     * @param b blue channel
     * @param a alpha channel
     * @return {@code this} for fluent chaining
     */
    public SceneNode setFillColor(float r, float g, float b, float a) {
        this.fillColorRgba[0] = r;
        this.fillColorRgba[1] = g;
        this.fillColorRgba[2] = b;
        this.fillColorRgba[3] = a;
        this.fillColorPremultiplied = false;
        return this;
    }

    /**
     * Sets an already premultiplied RGBA fill/tint color {@code (R*A, G*A, B*A, A)}.
     *
     * @param rPre premultiplied red
     * @param gPre premultiplied green
     * @param bPre premultiplied blue
     * @param a    alpha
     * @return {@code this} for fluent chaining
     */
    public SceneNode setFillColorPremultiplied(float rPre, float gPre, float bPre, float a) {
        this.fillColorRgba[0] = rPre;
        this.fillColorRgba[1] = gPre;
        this.fillColorRgba[2] = bPre;
        this.fillColorRgba[3] = a;
        this.fillColorPremultiplied = true;
        return this;
    }

    /**
     * Sets the straight RGBA fill/tint color from a hex string ({@code #RRGGBB}, {@code #RRGGBBAA}, etc.).
     *
     * @param hex hex color string
     * @return {@code this} for fluent chaining
     */
    public SceneNode setColorHex(String hex) {
        float[] parsed = parseHexRgba(hex);
        System.arraycopy(parsed, 0, this.fillColorRgba, 0, 4);
        this.fillColorPremultiplied = false;
        return this;
    }

    /** @return {@code true} if {@link #fillColorRgba} is stored in premultiplied alpha form */
    public boolean isFillColorPremultiplied() {
        return fillColorPremultiplied;
    }

    /**
     * Computes the premultiplied RGBA fill/tint color scaled by {@code additionalAlpha}.
     *
     * @param additionalAlpha external opacity multiplier (e.g. from {@link Transform2D#getAlpha()})
     * @return 4-element premultiplied RGBA array {@code [rPre, gPre, bPre, aOut]}
     */
    public float[] getPremultipliedFillColor(float additionalAlpha) {
        return toPremultiplied(fillColorRgba, fillColorPremultiplied, additionalAlpha);
    }

    /** @return rounded-rectangle corner radius in logical pixels */
    public float getCornerRadius() {
        return cornerRadius;
    }

    /**
     * Sets the rounded-rectangle corner radius in logical pixels.
     *
     * @param cornerRadius radius in pixels ({@code >= 0})
     * @return {@code this} for fluent chaining
     */
    public SceneNode setCornerRadius(float cornerRadius) {
        this.cornerRadius = Math.max(0.0f, cornerRadius);
        return this;
    }

    /** @return border thickness in logical pixels ({@code 0} disables border) */
    public float getBorderThickness() {
        return borderThickness;
    }

    /**
     * Sets the rectangle border thickness in logical pixels.
     *
     * @param borderThickness border thickness ({@code >= 0})
     * @return {@code this} for fluent chaining
     */
    public SceneNode setBorderThickness(float borderThickness) {
        this.borderThickness = Math.max(0.0f, borderThickness);
        return this;
    }

    /**
     * Returns a defensive copy of the 4-element border RGBA color array.
     *
     * @return 4-float RGBA array {@code [r, g, b, a]}
     */
    public float[] getBorderColorRgba() {
        return borderColorRgba.clone();
    }

    /**
     * Sets the straight RGBA border color from a 4-float array.
     *
     * @param rgba 4-element straight RGBA array {@code [r, g, b, a]}
     * @return {@code this} for fluent chaining
     */
    public SceneNode setBorderColorRgba(float[] rgba) {
        Objects.requireNonNull(rgba, "rgba must not be null");
        if (rgba.length != 4) {
            throw new IllegalArgumentException("Expected 4 RGBA components, got " + rgba.length);
        }
        System.arraycopy(rgba, 0, this.borderColorRgba, 0, 4);
        this.borderColorPremultiplied = false;
        return this;
    }

    /**
     * Sets the straight RGBA border color from individual components in {@code [0, 1]}.
     *
     * @param r red channel
     * @param g green channel
     * @param b blue channel
     * @param a alpha channel
     * @return {@code this} for fluent chaining
     */
    public SceneNode setBorderColor(float r, float g, float b, float a) {
        this.borderColorRgba[0] = r;
        this.borderColorRgba[1] = g;
        this.borderColorRgba[2] = b;
        this.borderColorRgba[3] = a;
        this.borderColorPremultiplied = false;
        return this;
    }

    /**
     * Sets the straight RGBA border color from a hex string ({@code #RRGGBB}, {@code #RRGGBBAA}, etc.).
     *
     * @param hex hex color string
     * @return {@code this} for fluent chaining
     */
    public SceneNode setBorderColorHex(String hex) {
        float[] parsed = parseHexRgba(hex);
        System.arraycopy(parsed, 0, this.borderColorRgba, 0, 4);
        this.borderColorPremultiplied = false;
        return this;
    }

    /**
     * Computes the premultiplied RGBA border color scaled by {@code additionalAlpha}.
     *
     * @param additionalAlpha external opacity multiplier
     * @return 4-element premultiplied RGBA array
     */
    public float[] getPremultipliedBorderColor(float additionalAlpha) {
        return toPremultiplied(borderColorRgba, borderColorPremultiplied, additionalAlpha);
    }

    /** @return OpenGL texture ID, or {@code 0} for solid/rounded rectangles */
    public int getTextureId() {
        return textureId;
    }

    /**
     * Sets the OpenGL texture handle ({@code 0} renders a solid/rounded rectangle).
     *
     * @param textureId OpenGL texture ID
     * @return {@code this} for fluent chaining
     */
    public SceneNode setTextureId(int textureId) {
        this.textureId = textureId;
        return this;
    }

    /** @return non-null normalized texture UV sub-rectangle {@code (u, v, uWidth, vHeight)} */
    public Rect2f getUvRect() {
        return uvRect;
    }

    /**
     * Sets the normalized UV sub-rectangle within the bound texture.
     *
     * @param uvRect UV sub-rectangle (must not be {@code null})
     * @return {@code this} for fluent chaining
     */
    public SceneNode setUvRect(Rect2f uvRect) {
        this.uvRect = Objects.requireNonNull(uvRect, "uvRect must not be null");
        return this;
    }

    /** @return arbitrary user/controller metadata attached to this node, or {@code null} */
    public Object getUserData() {
        return userData;
    }

    /**
     * Attaches arbitrary user data to this node.
     *
     * @param userData custom payload (may be {@code null})
     * @return {@code this} for fluent chaining
     */
    public SceneNode setUserData(Object userData) {
        this.userData = userData;
        return this;
    }

    /** @return parent node if nested inside another {@link SceneNode}, otherwise {@code null} */
    public SceneNode getParent() {
        return parent;
    }

    /**
     * Adds a child node to this node's child list, detaching it from any previous parent first.
     *
     * @param child child node (non-null, must not be {@code this} or an ancestor)
     * @return {@code this} for fluent chaining
     */
    public SceneNode addChild(SceneNode child) {
        Objects.requireNonNull(child, "child must not be null");
        if (child == this) {
            throw new IllegalArgumentException("A SceneNode cannot be added as a child of itself");
        }
        for (SceneNode ancestor = this.parent; ancestor != null; ancestor = ancestor.parent) {
            if (ancestor == child) {
                throw new IllegalArgumentException("Cycle detected while adding child SceneNode");
            }
        }
        if (child.parent != null && child.parent != this) {
            child.parent.removeChild(child);
        }
        if (!children.contains(child)) {
            children.add(child);
        }
        child.parent = this;
        return this;
    }

    /**
     * Removes a direct child node from this node.
     *
     * @param child child node to remove
     * @return {@code true} if the child was present and removed
     */
    public boolean removeChild(SceneNode child) {
        if (child == null) {
            return false;
        }
        boolean removed = children.remove(child);
        if (removed && child.parent == this) {
            child.parent = null;
        }
        return removed;
    }

    /**
     * Removes all direct child nodes from this node.
     */
    public void clearChildren() {
        for (SceneNode child : children) {
            if (child.parent == this) {
                child.parent = null;
            }
        }
        children.clear();
    }

    /**
     * Returns an unmodifiable view of this node's direct children in insertion order.
     *
     * @return unmodifiable child list
     */
    public List<SceneNode> getChildren() {
        return Collections.unmodifiableList(children);
    }

    /** @return optional custom render hook callback, or {@code null} */
    public BiConsumer<SceneNode, RenderPipeline2D> getCustomRenderer() {
        return customRenderer;
    }

    /**
     * Registers an optional custom render hook invoked by {@link RenderPipeline2D} after standard node setup.
     *
     * @param customRenderer callback receiving {@code (thisNode, pipeline)}, or {@code null} to clear
     * @return {@code this} for fluent chaining
     */
    public SceneNode setCustomRenderer(BiConsumer<SceneNode, RenderPipeline2D> customRenderer) {
        this.customRenderer = customRenderer;
        return this;
    }

    /**
     * Hook method invoked by {@link RenderPipeline2D} when rendering this node.
     * Subclasses may override this method for custom OpenGL / primitive drawing; the default
     * implementation delegates to {@link #customRenderer} if one is registered.
     *
     * @param pipeline active 2D render pipeline
     */
    protected void onCustomRender(RenderPipeline2D pipeline) {
        if (customRenderer != null) {
            customRenderer.accept(this, pipeline);
        }
    }

    private static float[] toPremultiplied(float[] rgba, boolean alreadyPremultiplied, float additionalAlpha) {
        float alphaScale = Math.clamp(additionalAlpha, 0.0f, 1.0f);
        if (alreadyPremultiplied) {
            return new float[]{
                    rgba[0] * alphaScale,
                    rgba[1] * alphaScale,
                    rgba[2] * alphaScale,
                    rgba[3] * alphaScale
            };
        }
        float effectiveAlpha = Math.clamp(rgba[3], 0.0f, 1.0f) * alphaScale;
        return new float[]{
                rgba[0] * effectiveAlpha,
                rgba[1] * effectiveAlpha,
                rgba[2] * effectiveAlpha,
                effectiveAlpha
        };
    }
}
