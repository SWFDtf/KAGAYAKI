package de.swfdtf.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EasingTest {

    private static final float EPS = 1e-4f;

    @Test
    void allPresetsSatisfyEndpointAndClampingInvariants() {
        for (Easing.EaseType type : Easing.EaseType.values()) {
            assertEquals(0.0f, Easing.evaluate(type, -0.5f), EPS, type.name());
            assertEquals(0.0f, Easing.evaluate(type, 0.0f), EPS, type.name());
            assertEquals(1.0f, Easing.evaluate(type, 1.0f), EPS, type.name());
            assertEquals(1.0f, Easing.evaluate(type, 1.5f), EPS, type.name());
            assertEquals(0.0f, Easing.evaluate(type, Float.NaN), EPS, type.name());

            float prev = 0.0f;
            for (int i = 1; i <= 20; i++) {
                float cur = Easing.evaluate(type, i / 20.0f);
                assertTrue(cur >= prev - 1e-5f, "Monotonicity failed for " + type + " at step " + i);
                prev = cur;
            }
        }
    }

    @Test
    void curveShapesMatchExpectedBehavior() {
        assertEquals(0.25f, Easing.linear(0.25f), EPS);
        assertEquals(0.5f, Easing.smoothstep(0.5f), EPS);
        assertEquals(0.15625f, Easing.smoothstep(0.25f), EPS);

        assertTrue(Easing.easeIn(0.5f) < 0.5f);
        assertTrue(Easing.easeOut(0.5f) > 0.5f);
        assertEquals(0.5f, Easing.easeInOut(0.5f), EPS);
        assertEquals(1.0f - Easing.easeIn(0.25f), Easing.easeOut(0.75f), EPS);
    }

    @Test
    void cubicBezierHandlesLinearExtremeAndOvershootCurves() {
        // Linear diagonal
        assertEquals(0.37f, Easing.cubicBezier(0.37f, 0.0f, 0.0f, 1.0f, 1.0f), EPS);

        // Flat inflection point (p1x=1, p2x=0): derivative = 0 at u=0.5, x(0.5) = 0.5, y(0.5) = 0.5
        assertEquals(0.5f, Easing.cubicBezier(0.5f, 1.0f, 0.0f, 0.0f, 1.0f), 1e-3f);

        // Overshoot curve (p2y > 1)
        float overshoot = Easing.cubicBezier(0.75f, 0.25f, 0.1f, 0.25f, 1.5f);
        assertTrue(overshoot > 1.0f, "Expected overshoot > 1.0, was " + overshoot);
    }

    @Test
    void validatesNullAndOutOfRangeControlPoints() {
        assertThrows(NullPointerException.class, () -> Easing.evaluate(null, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> Easing.cubicBezier(0.5f, -0.1f, 0.0f, 1.0f, 1.0f));
        assertThrows(IllegalArgumentException.class, () -> Easing.cubicBezier(0.5f, 0.0f, 0.0f, 1.1f, 1.0f));
        assertThrows(IllegalArgumentException.class, () -> Easing.cubicBezier(0.5f, Float.NaN, 0.0f, 1.0f, 1.0f));
    }
}
