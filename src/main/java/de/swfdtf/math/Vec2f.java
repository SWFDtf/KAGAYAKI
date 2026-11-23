package de.swfdtf.math;

import java.util.Objects;

/**
 * Immutable 2D vector with single-precision floating-point components.
 *
 * @param x horizontal component
 * @param y vertical component
 */
public record Vec2f(float x, float y) {

    /** Zero vector {@code (0, 0)}. */
    public static final Vec2f ZERO = new Vec2f(0.0f, 0.0f);

    /** Unit vector {@code (1, 1)}. */
    public static final Vec2f ONE = new Vec2f(1.0f, 1.0f);

    /** Half vector {@code (0.5, 0.5)}, commonly used for centered anchors. */
    public static final Vec2f HALF = new Vec2f(0.5f, 0.5f);

    /**
     * Returns the component-wise sum {@code this + other}.
     *
     * @param other vector to add (non-null)
     * @return resulting vector
     */
    public Vec2f add(Vec2f other) {
        Objects.requireNonNull(other, "other must not be null");
        return new Vec2f(x + other.x, y + other.y);
    }

    /**
     * Returns the component-wise difference {@code this - other}.
     *
     * @param other vector to subtract (non-null)
     * @return resulting vector
     */
    public Vec2f sub(Vec2f other) {
        Objects.requireNonNull(other, "other must not be null");
        return new Vec2f(x - other.x, y - other.y);
    }

    /**
     * Returns this vector scaled uniformly by {@code factor}.
     *
     * @param factor scalar multiplier
     * @return scaled vector
     */
    public Vec2f scale(float factor) {
        return new Vec2f(x * factor, y * factor);
    }

    /**
     * Linearly interpolates between {@code this} (at {@code t = 0}) and {@code target} (at {@code t = 1}).
     *
     * @param target target vector (non-null)
     * @param t      interpolation factor
     * @return interpolated vector
     */
    public Vec2f lerp(Vec2f target, float t) {
        Objects.requireNonNull(target, "target must not be null");
        return new Vec2f(
                x + (target.x - x) * t,
                y + (target.y - y) * t
        );
    }

    /**
     * Returns the Euclidean length (magnitude) of this vector.
     *
     * @return {@code sqrt(x^2 + y^2)}
     */
    public float length() {
        return (float) Math.hypot(x, y);
    }

    /**
     * Returns the Euclidean distance from this vector to {@code other}.
     *
     * @param other target point (non-null)
     * @return distance between {@code this} and {@code other}
     */
    public float distance(Vec2f other) {
        Objects.requireNonNull(other, "other must not be null");
        return (float) Math.hypot(other.x - x, other.y - y);
    }
}
