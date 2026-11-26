package de.swfdtf.math;

import java.util.Objects;

/**
 * Immutable axis-aligned 2D rectangle defined by top-left origin {@code (x, y)} and dimensions {@code (width, height)}.
 *
 * @param x      left X coordinate
 * @param y      top Y coordinate
 * @param width  rectangle width
 * @param height rectangle height
 */
public record Rect2f(float x, float y, float width, float height) {

    /**
     * Returns the right edge coordinate {@code x + width}.
     *
     * @return right X coordinate
     */
    public float right() {
        return x + width;
    }

    /**
     * Returns the bottom edge coordinate {@code y + height}.
     *
     * @return bottom Y coordinate
     */
    public float bottom() {
        return y + height;
    }

    /**
     * Returns the horizontal center coordinate {@code x + width * 0.5f}.
     *
     * @return center X coordinate
     */
    public float centerX() {
        return x + width * 0.5f;
    }

    /**
     * Returns the vertical center coordinate {@code y + height * 0.5f}.
     *
     * @return center Y coordinate
     */
    public float centerY() {
        return y + height * 0.5f;
    }

    /**
     * Tests whether the point {@code (px, py)} lies inside or on the boundary of this rectangle.
     * Returns {@code false} if {@code width < 0} or {@code height < 0}.
     *
     * @param px point X coordinate
     * @param py point Y coordinate
     * @return {@code true} if the point is within {@code [x, x + width] x [y, y + height]}
     */
    public boolean contains(float px, float py) {
        if (width < 0.0f || height < 0.0f) {
            return false;
        }
        return px >= x && px <= x + width && py >= y && py <= y + height;
    }

    /**
     * Tests whether this rectangle strictly overlaps with {@code other}
     * (shared edges alone do not count as intersection).
     * Returns {@code false} if either rectangle has non-positive width or height.
     *
     * @param other other rectangle (non-null)
     * @return {@code true} if the interiors of the two rectangles intersect
     */
    public boolean intersects(Rect2f other) {
        Objects.requireNonNull(other, "other must not be null");
        if (width <= 0.0f || height <= 0.0f || other.width <= 0.0f || other.height <= 0.0f) {
            return false;
        }
        return x < other.right() && right() > other.x
                && y < other.bottom() && bottom() > other.y;
    }

    /**
     * Returns a new rectangle translated by {@code (dx, dy)}.
     *
     * @param dx horizontal offset
     * @param dy vertical offset
     * @return translated rectangle
     */
    public Rect2f translate(float dx, float dy) {
        return new Rect2f(x + dx, y + dy, width, height);
    }

    /**
     * Returns a new rectangle shrunk inward on all four sides by {@code amount} (or expanded if {@code amount < 0}).
     * Resulting width and height are clamped to {@code >= 0}.
     *
     * @param amount inset distance per edge
     * @return inset rectangle
     */
    public Rect2f inset(float amount) {
        float newWidth = Math.max(0.0f, width - 2.0f * amount);
        float newHeight = Math.max(0.0f, height - 2.0f * amount);
        return new Rect2f(x + amount, y + amount, newWidth, newHeight);
    }
}
