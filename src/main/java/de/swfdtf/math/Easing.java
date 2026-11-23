package de.swfdtf.math;

import java.util.Objects;

/**
 * Standard easing curves and cubic Bezier evaluation for ATL animations and UI transitions.
 */
public final class Easing {

    private static final float BEZIER_EPSILON = 1.0e-6f;
    private static final int MAX_BEZIER_ITERATIONS = 16;

    /**
     * Built-in easing curve presets.
     */
    public enum EaseType {
        /** Constant speed: {@code f(t) = t}. */
        LINEAR,
        /** Standard CSS ease curve: {@code cubic-bezier(0.25, 0.1, 0.25, 1.0)}. */
        EASE,
        /** Slow start, accelerating end: {@code cubic-bezier(0.42, 0.0, 1.0, 1.0)}. */
        EASE_IN,
        /** Fast start, decelerating end: {@code cubic-bezier(0.0, 0.0, 0.58, 1.0)}. */
        EASE_OUT,
        /** Smooth acceleration and deceleration: {@code cubic-bezier(0.42, 0.0, 0.58, 1.0)}. */
        EASE_IN_OUT,
        /** Hermite smoothstep polynomial: {@code t^2 * (3 - 2t)}. */
        SMOOTHSTEP
    }

    private Easing() {
    }

    /**
     * Evaluates the linear curve on {@code t} clamped to {@code [0, 1]}.
     *
     * @param t normalized progress
     * @return clamped {@code t} in {@code [0, 1]}
     */
    public static float linear(float t) {
        return clamp01(t);
    }

    /**
     * Evaluates the standard CSS {@code ease} curve {@code cubic-bezier(0.25, 0.1, 0.25, 1.0)}.
     *
     * @param t normalized progress (clamped to {@code [0, 1]})
     * @return eased progress
     */
    public static float ease(float t) {
        return cubicBezier(t, 0.25f, 0.1f, 0.25f, 1.0f);
    }

    /**
     * Evaluates the CSS {@code ease-in} curve {@code cubic-bezier(0.42, 0.0, 1.0, 1.0)}.
     *
     * @param t normalized progress (clamped to {@code [0, 1]})
     * @return eased progress
     */
    public static float easeIn(float t) {
        return cubicBezier(t, 0.42f, 0.0f, 1.0f, 1.0f);
    }

    /**
     * Evaluates the CSS {@code ease-out} curve {@code cubic-bezier(0.0, 0.0, 0.58, 1.0)}.
     *
     * @param t normalized progress (clamped to {@code [0, 1]})
     * @return eased progress
     */
    public static float easeOut(float t) {
        return cubicBezier(t, 0.0f, 0.0f, 0.58f, 1.0f);
    }

    /**
     * Evaluates the CSS {@code ease-in-out} curve {@code cubic-bezier(0.42, 0.0, 0.58, 1.0)}.
     *
     * @param t normalized progress (clamped to {@code [0, 1]})
     * @return eased progress
     */
    public static float easeInOut(float t) {
        return cubicBezier(t, 0.42f, 0.0f, 0.58f, 1.0f);
    }

    /**
     * Evaluates the Hermite smoothstep curve {@code t * t * (3 - 2 * t)} with {@code t} clamped to {@code [0, 1]}.
     *
     * @param t normalized progress
     * @return smoothed progress in {@code [0, 1]}
     */
    public static float smoothstep(float t) {
        float x = clamp01(t);
        return x * x * (3.0f - 2.0f * x);
    }

    /**
     * Evaluates a CSS-style cubic Bezier easing curve with endpoints {@code P0=(0, 0)} and {@code P3=(1, 1)}
     * and intermediate control points {@code P1=(p1x, p1y)} and {@code P2=(p2x, p2y)}.
     * Input {@code t} is clamped to {@code [0, 1]}. Control point X coordinates must lie in {@code [0, 1]}.
     * Y coordinates may lie outside {@code [0, 1]} to produce overshoot/bounce curves.
     *
     * @param t   normalized time progress (clamped to {@code [0, 1]})
     * @param p1x first control point X in {@code [0, 1]}
     * @param p1y first control point Y
     * @param p2x second control point X in {@code [0, 1]}
     * @param p2y second control point Y
     * @return evaluated Y value along the cubic Bezier curve
     * @throws IllegalArgumentException if {@code p1x} or {@code p2x} is outside {@code [0, 1]} or non-finite
     */
    public static float cubicBezier(float t, float p1x, float p1y, float p2x, float p2y) {
        validateControlX(p1x, "p1x");
        validateControlX(p2x, "p2x");

        float xTarget = clamp01(t);
        if (xTarget <= 0.0f) {
            return 0.0f;
        }
        if (xTarget >= 1.0f) {
            return 1.0f;
        }
        if (p1x == p1y && p2x == p2y) {
            return xTarget;
        }

        // Polynomial coefficients for B(u) = ((a * u + b) * u + c) * u
        float cx = 3.0f * p1x;
        float bx = 3.0f * (p2x - p1x) - cx;
        float ax = 1.0f - cx - bx;

        float cy = 3.0f * p1y;
        float by = 3.0f * (p2y - p1y) - cy;
        float ay = 1.0f - cy - by;

        float u = solveBezierX(ax, bx, cx, xTarget);
        return sampleCurve(ay, by, cy, u);
    }

    /**
     * Evaluates the specified {@link EaseType} for {@code t} clamped to {@code [0, 1]}.
     *
     * @param type easing preset (non-null)
     * @param t    normalized progress
     * @return eased progress
     */
    public static float evaluate(EaseType type, float t) {
        Objects.requireNonNull(type, "type must not be null");
        float clamped = clamp01(t);
        return switch (type) {
            case LINEAR -> clamped;
            case EASE -> ease(clamped);
            case EASE_IN -> easeIn(clamped);
            case EASE_OUT -> easeOut(clamped);
            case EASE_IN_OUT -> easeInOut(clamped);
            case SMOOTHSTEP -> smoothstep(clamped);
        };
    }

    private static float solveBezierX(float ax, float bx, float cx, float targetX) {
        float lo = 0.0f;
        float hi = 1.0f;
        float u = targetX;

        for (int i = 0; i < MAX_BEZIER_ITERATIONS; i++) {
            float err = sampleCurve(ax, bx, cx, u) - targetX;
            if (Math.abs(err) < BEZIER_EPSILON) {
                return u;
            }
            if (err < 0.0f) {
                lo = u;
            } else {
                hi = u;
            }
            float deriv = sampleDerivative(ax, bx, cx, u);
            if (Math.abs(deriv) > BEZIER_EPSILON) {
                float next = u - err / deriv;
                if (next > lo && next < hi) {
                    u = next;
                    continue;
                }
            }
            u = 0.5f * (lo + hi);
        }
        return u;
    }

    private static float sampleCurve(float a, float b, float c, float u) {
        return ((a * u + b) * u + c) * u;
    }

    private static float sampleDerivative(float a, float b, float c, float u) {
        return (3.0f * a * u + 2.0f * b) * u + c;
    }

    private static float clamp01(float t) {
        if (Float.isNaN(t)) {
            return 0.0f;
        }
        return Math.max(0.0f, Math.min(1.0f, t));
    }

    private static void validateControlX(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be in [0, 1], was: " + value);
        }
    }
}
