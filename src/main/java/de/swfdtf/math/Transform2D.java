package de.swfdtf.math;

import java.util.Objects;

/**
 * Mutable 2D transform state for Visual Novel sprites and scene-graph nodes.
 *
 * <p>Supports Ren'Py-style positioning ({@code xpos}, {@code ypos}, {@code xanchor}, {@code yanchor},
 * optional {@code xalign}/{@code yalign}), scaling ({@code zoom}, {@code xzoom}, {@code yzoom}),
 * rotation in degrees, opacity, blur radius, premultiplied-alpha {@link ColorMatrix4f}, crop rectangle,
 * and mouse-parallax weights.</p>
 */
public final class Transform2D {

    private float xpos = 0.0f;
    private float ypos = 0.0f;
    private float xanchor = 0.0f;
    private float yanchor = 0.0f;
    private float xalign = Float.NaN;
    private float yalign = Float.NaN;
    private float zoom = 1.0f;
    private float xzoom = 1.0f;
    private float yzoom = 1.0f;
    private float rotate = 0.0f;
    private float alpha = 1.0f;
    private float blur = 0.0f;
    private ColorMatrix4f colorMatrix = ColorMatrix4f.identity();
    private Rect2f crop = null;
    private float parallaxX = 0.0f;
    private float parallaxY = 0.0f;

    /**
     * Creates a {@code Transform2D} initialized with neutral defaults
     * ({@code zoom=1}, {@code alpha=1}, {@code xalign=NaN}, {@code yalign=NaN}, identity color matrix).
     */
    public Transform2D() {
    }

    private Transform2D(Transform2D src) {
        this.xpos = src.xpos;
        this.ypos = src.ypos;
        this.xanchor = src.xanchor;
        this.yanchor = src.yanchor;
        this.xalign = src.xalign;
        this.yalign = src.yalign;
        this.zoom = src.zoom;
        this.xzoom = src.xzoom;
        this.yzoom = src.yzoom;
        this.rotate = src.rotate;
        this.alpha = src.alpha;
        this.blur = src.blur;
        this.colorMatrix = src.colorMatrix;
        this.crop = src.crop;
        this.parallaxX = src.parallaxX;
        this.parallaxY = src.parallaxY;
    }

    /**
     * Returns the X position in logical pixels.
     *
     * @return X position in logical pixels (used when {@code xalign} is {@code NaN})
     */
    public float getXpos() {
        return xpos;
    }

    /**
     * Sets the X position in logical pixels.
     *
     * @param xpos X position
     * @return {@code this} for fluent chaining
     */
    public Transform2D setXpos(float xpos) {
        this.xpos = xpos;
        return this;
    }

    /**
     * Returns the Y position in logical pixels.
     *
     * @return Y position in logical pixels (used when {@code yalign} is {@code NaN})
     */
    public float getYpos() {
        return ypos;
    }

    /**
     * Sets the Y position in logical pixels.
     *
     * @param ypos Y position
     * @return {@code this} for fluent chaining
     */
    public Transform2D setYpos(float ypos) {
        this.ypos = ypos;
        return this;
    }

    /**
     * Returns the normalized horizontal anchor.
     *
     * @return normalized horizontal anchor ({@code 0}=left, {@code 0.5}=center, {@code 1}=right)
     */
    public float getXanchor() {
        return xanchor;
    }

    /**
     * Sets the normalized horizontal anchor.
     *
     * @param xanchor horizontal anchor
     * @return {@code this} for fluent chaining
     */
    public Transform2D setXanchor(float xanchor) {
        this.xanchor = xanchor;
        return this;
    }

    /**
     * Returns the normalized vertical anchor.
     *
     * @return normalized vertical anchor ({@code 0}=top, {@code 0.5}=center, {@code 1}=bottom)
     */
    public float getYanchor() {
        return yanchor;
    }

    /**
     * Sets the normalized vertical anchor.
     *
     * @param yanchor vertical anchor
     * @return {@code this} for fluent chaining
     */
    public Transform2D setYanchor(float yanchor) {
        this.yanchor = yanchor;
        return this;
    }

    /**
     * Returns the horizontal alignment.
     *
     * @return horizontal alignment in {@code [0, 1]} or {@link Float#NaN} if unset
     */
    public float getXalign() {
        return xalign;
    }

    /**
     * Sets horizontal alignment within the container ({@link Float#NaN} to unset).
     * When set, overrides {@code xpos} and {@code xanchor} during bounds resolution.
     *
     * @param xalign horizontal alignment or {@code Float.NaN}
     * @return {@code this} for fluent chaining
     */
    public Transform2D setXalign(float xalign) {
        this.xalign = xalign;
        return this;
    }

    /**
     * Returns whether horizontal alignment is set.
     *
     * @return {@code true} if {@code xalign} is set (not {@code NaN})
     */
    public boolean hasXalign() {
        return !Float.isNaN(xalign);
    }

    /**
     * Returns the vertical alignment.
     *
     * @return vertical alignment in {@code [0, 1]} or {@link Float#NaN} if unset
     */
    public float getYalign() {
        return yalign;
    }

    /**
     * Sets vertical alignment within the container ({@link Float#NaN} to unset).
     * When set, overrides {@code ypos} and {@code yanchor} during bounds resolution.
     *
     * @param yalign vertical alignment or {@code Float.NaN}
     * @return {@code this} for fluent chaining
     */
    public Transform2D setYalign(float yalign) {
        this.yalign = yalign;
        return this;
    }

    /**
     * Returns whether vertical alignment is set.
     *
     * @return {@code true} if {@code yalign} is set (not {@code NaN})
     */
    public boolean hasYalign() {
        return !Float.isNaN(yalign);
    }

    /**
     * Returns the uniform zoom factor.
     *
     * @return uniform zoom factor
     */
    public float getZoom() {
        return zoom;
    }

    /**
     * Sets the uniform zoom factor.
     *
     * @param zoom uniform scale multiplier
     * @return {@code this} for fluent chaining
     */
    public Transform2D setZoom(float zoom) {
        this.zoom = zoom;
        return this;
    }

    /**
     * Returns the horizontal zoom multiplier.
     *
     * @return horizontal zoom multiplier (negative values mirror horizontally)
     */
    public float getXzoom() {
        return xzoom;
    }

    /**
     * Sets the horizontal zoom multiplier.
     *
     * @param xzoom horizontal scale multiplier
     * @return {@code this} for fluent chaining
     */
    public Transform2D setXzoom(float xzoom) {
        this.xzoom = xzoom;
        return this;
    }

    /**
     * Returns the vertical zoom multiplier.
     *
     * @return vertical zoom multiplier (negative values mirror vertically)
     */
    public float getYzoom() {
        return yzoom;
    }

    /**
     * Sets the vertical zoom multiplier.
     *
     * @param yzoom vertical scale multiplier
     * @return {@code this} for fluent chaining
     */
    public Transform2D setYzoom(float yzoom) {
        this.yzoom = yzoom;
        return this;
    }

    /**
     * Returns the rotation angle in degrees.
     *
     * @return rotation angle in degrees
     */
    public float getRotate() {
        return rotate;
    }

    /**
     * Sets rotation in degrees.
     *
     * @param rotate clockwise angle in degrees
     * @return {@code this} for fluent chaining
     */
    public Transform2D setRotate(float rotate) {
        this.rotate = rotate;
        return this;
    }

    /**
     * Returns the opacity alpha multiplier.
     *
     * @return opacity alpha multiplier
     */
    public float getAlpha() {
        return alpha;
    }

    /**
     * Sets the opacity alpha multiplier.
     *
     * @param alpha opacity factor
     * @return {@code this} for fluent chaining
     */
    public Transform2D setAlpha(float alpha) {
        this.alpha = alpha;
        return this;
    }

    /**
     * Returns the blur radius in pixels.
     *
     * @return blur radius in pixels
     */
    public float getBlur() {
        return blur;
    }

    /**
     * Sets the blur radius (negative or non-finite values are clamped to {@code 0.0f}).
     *
     * @param blur blur radius in pixels
     * @return {@code this} for fluent chaining
     */
    public Transform2D setBlur(float blur) {
        this.blur = (Float.isFinite(blur) && blur > 0.0f) ? blur : 0.0f;
        return this;
    }

    /**
     * Returns the 4x4 color transformation matrix.
     *
     * @return non-null 4x4 color transformation matrix
     */
    public ColorMatrix4f getColorMatrix() {
        return colorMatrix;
    }

    /**
     * Sets the 4x4 color transformation matrix (must be non-null).
     *
     * @param colorMatrix color matrix (non-null)
     * @return {@code this} for fluent chaining
     */
    public Transform2D setColorMatrix(ColorMatrix4f colorMatrix) {
        this.colorMatrix = Objects.requireNonNull(colorMatrix, "colorMatrix must not be null");
        return this;
    }

    /**
     * Returns the optional crop rectangle.
     *
     * @return optional crop rectangle in source pixel coordinates, or {@code null} if uncropped
     */
    public Rect2f getCrop() {
        return crop;
    }

    /**
     * Sets the optional crop rectangle (may be {@code null}).
     *
     * @param crop crop rectangle or {@code null}
     * @return {@code this} for fluent chaining
     */
    public Transform2D setCrop(Rect2f crop) {
        this.crop = crop;
        return this;
    }

    /**
     * Returns the horizontal mouse-parallax factor.
     *
     * @return horizontal mouse-parallax factor
     */
    public float getParallaxX() {
        return parallaxX;
    }

    /**
     * Sets the horizontal mouse-parallax factor.
     *
     * @param parallaxX horizontal parallax factor
     * @return {@code this} for fluent chaining
     */
    public Transform2D setParallaxX(float parallaxX) {
        this.parallaxX = parallaxX;
        return this;
    }

    /**
     * Returns the vertical mouse-parallax factor.
     *
     * @return vertical mouse-parallax factor
     */
    public float getParallaxY() {
        return parallaxY;
    }

    /**
     * Sets the vertical mouse-parallax factor.
     *
     * @param parallaxY vertical parallax factor
     * @return {@code this} for fluent chaining
     */
    public Transform2D setParallaxY(float parallaxY) {
        this.parallaxY = parallaxY;
        return this;
    }

    /**
     * Returns an independent deep copy of this {@code Transform2D}.
     *
     * @return cloned transform state
     */
    public Transform2D copy() {
        return new Transform2D(this);
    }

    /**
     * Returns a new {@code Transform2D} linearly interpolated between {@code this} (at {@code t = 0})
     * and {@code target} (at {@code t = 1}).
     *
     * @param target target transform state (non-null)
     * @param t      interpolation progress
     * @return interpolated {@code Transform2D}
     */
    public Transform2D lerpTo(Transform2D target, float t) {
        Objects.requireNonNull(target, "target must not be null");
        Transform2D out = new Transform2D();
        out.xpos = lerpFloat(this.xpos, target.xpos, t);
        out.ypos = lerpFloat(this.ypos, target.ypos, t);
        out.xanchor = lerpFloat(this.xanchor, target.xanchor, t);
        out.yanchor = lerpFloat(this.yanchor, target.yanchor, t);
        out.xalign = lerpOptionalAlign(this.xalign, target.xalign, t);
        out.yalign = lerpOptionalAlign(this.yalign, target.yalign, t);
        out.zoom = lerpFloat(this.zoom, target.zoom, t);
        out.xzoom = lerpFloat(this.xzoom, target.xzoom, t);
        out.yzoom = lerpFloat(this.yzoom, target.yzoom, t);
        out.rotate = lerpFloat(this.rotate, target.rotate, t);
        out.alpha = lerpFloat(this.alpha, target.alpha, t);
        out.blur = lerpFloat(this.blur, target.blur, t);
        out.colorMatrix = this.colorMatrix.lerp(target.colorMatrix, t);
        out.crop = lerpCrop(this.crop, target.crop, t);
        out.parallaxX = lerpFloat(this.parallaxX, target.parallaxX, t);
        out.parallaxY = lerpFloat(this.parallaxY, target.parallaxY, t);
        return out;
    }

    /**
     * Resolves the axis-aligned screen/container bounding box for a displayable of size {@code (width, height)}
     * placed inside a container of size {@code (containerWidth, containerHeight)}.
     *
     * <p>If {@code crop} is non-null, its dimensions replace {@code (width, height)} before scaling by
     * {@code zoom * |xzoom|} and {@code zoom * |yzoom|}. If {@code xalign} (or {@code yalign}) is set,
     * it positions the scaled bounds at {@code align * (containerDim - scaledDim)}; otherwise
     * {@code pos - anchor * scaledDim} is used.</p>
     *
     * @param width           unscaled source width
     * @param height          unscaled source height
     * @param containerWidth  container width in logical pixels
     * @param containerHeight container height in logical pixels
     * @return resolved bounding {@link Rect2f}
     */
    public Rect2f resolveBounds(float width, float height, float containerWidth, float containerHeight) {
        float baseWidth = crop != null ? crop.width() : width;
        float baseHeight = crop != null ? crop.height() : height;

        float scaledWidth = baseWidth * Math.abs(zoom * xzoom);
        float scaledHeight = baseHeight * Math.abs(zoom * yzoom);

        float resolvedX = !Float.isNaN(xalign)
                ? xalign * (containerWidth - scaledWidth)
                : xpos - xanchor * scaledWidth;
        float resolvedY = !Float.isNaN(yalign)
                ? yalign * (containerHeight - scaledHeight)
                : ypos - yanchor * scaledHeight;

        return new Rect2f(resolvedX, resolvedY, scaledWidth, scaledHeight);
    }

    private static float lerpFloat(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float lerpOptionalAlign(float a, float b, float t) {
        if (Float.isNaN(a) && Float.isNaN(b)) {
            return Float.NaN;
        }
        if (!Float.isNaN(a) && !Float.isNaN(b)) {
            return lerpFloat(a, b, t);
        }
        return t >= 1.0f ? b : (t <= 0.0f ? a : (t < 0.5f ? a : b));
    }

    private static Rect2f lerpCrop(Rect2f a, Rect2f b, float t) {
        if (a == null && b == null) {
            return null;
        }
        if (a != null && b != null) {
            return new Rect2f(
                    lerpFloat(a.x(), b.x(), t),
                    lerpFloat(a.y(), b.y(), t),
                    lerpFloat(a.width(), b.width(), t),
                    lerpFloat(a.height(), b.height(), t)
            );
        }
        return t < 0.5f ? a : b;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Transform2D that)) {
            return false;
        }
        return Float.compare(xpos, that.xpos) == 0
                && Float.compare(ypos, that.ypos) == 0
                && Float.compare(xanchor, that.xanchor) == 0
                && Float.compare(yanchor, that.yanchor) == 0
                && Float.compare(xalign, that.xalign) == 0
                && Float.compare(yalign, that.yalign) == 0
                && Float.compare(zoom, that.zoom) == 0
                && Float.compare(xzoom, that.xzoom) == 0
                && Float.compare(yzoom, that.yzoom) == 0
                && Float.compare(rotate, that.rotate) == 0
                && Float.compare(alpha, that.alpha) == 0
                && Float.compare(blur, that.blur) == 0
                && Float.compare(parallaxX, that.parallaxX) == 0
                && Float.compare(parallaxY, that.parallaxY) == 0
                && colorMatrix.equals(that.colorMatrix)
                && Objects.equals(crop, that.crop);
    }

    @Override
    public int hashCode() {
        return Objects.hash(xpos, ypos, xanchor, yanchor, xalign, yalign,
                zoom, xzoom, yzoom, rotate, alpha, blur, colorMatrix, crop, parallaxX, parallaxY);
    }

    @Override
    public String toString() {
        return "Transform2D{" +
                "xpos=" + xpos +
                ", ypos=" + ypos +
                ", xanchor=" + xanchor +
                ", yanchor=" + yanchor +
                ", xalign=" + xalign +
                ", yalign=" + yalign +
                ", zoom=" + zoom +
                ", xzoom=" + xzoom +
                ", yzoom=" + yzoom +
                ", rotate=" + rotate +
                ", alpha=" + alpha +
                ", blur=" + blur +
                ", colorMatrix=" + colorMatrix +
                ", crop=" + crop +
                ", parallaxX=" + parallaxX +
                ", parallaxY=" + parallaxY +
                '}';
    }
}
