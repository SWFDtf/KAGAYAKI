package de.swfdtf.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenShakeTest {

    private static final float EPS = 1e-4f;

    @Test
    void vpunchUsesBlueprintDefaultsAndVerticalAxis() {
        ScreenShake shake = ScreenShake.vpunch();
        assertEquals(0.0f, shake.getAmplitudeX(), EPS);
        assertEquals(30.0f, shake.getAmplitudeY(), EPS);
        assertEquals(5.0f, shake.getDecay(), EPS);
        assertEquals(40.0f, shake.getOmega(), EPS);
        assertFalse(shake.isFinished());
        assertEquals(Vec2f.ZERO, shake.getOffset());

        float dt = 0.04f;
        shake.update(dt);
        Vec2f offset = shake.getOffset();
        float expectedY = (float) (30.0 * Math.exp(-5.0 * dt) * Math.sin(40.0 * dt));
        assertEquals(0.0f, offset.x(), EPS);
        assertEquals(expectedY, offset.y(), EPS);
        assertTrue(Math.abs(offset.y()) > 10.0f);
    }

    @Test
    void hpunchUsesBlueprintDefaultsAndHorizontalAxis() {
        ScreenShake shake = ScreenShake.hpunch();
        assertEquals(30.0f, shake.getAmplitudeX(), EPS);
        assertEquals(0.0f, shake.getAmplitudeY(), EPS);

        float dt = 0.04f;
        shake.update(dt);
        Vec2f offset = shake.getOffset();
        float expectedX = (float) (30.0 * Math.exp(-5.0 * dt) * Math.sin(40.0 * dt));
        assertEquals(expectedX, offset.x(), EPS);
        assertEquals(0.0f, offset.y(), EPS);
    }

    @Test
    void customShakeDecaysFinishesAndResets() {
        ScreenShake shake = ScreenShake.custom(20.0f, -10.0f, 3.0f, 25.0f, 0.5f);
        shake.update(0.1f);
        Vec2f mid = shake.getOffset();
        double factor = Math.exp(-0.3) * Math.sin(2.5);
        assertEquals((float) (20.0 * factor), mid.x(), EPS);
        assertEquals((float) (-10.0 * factor), mid.y(), EPS);
        assertFalse(shake.isFinished());

        shake.update(1.0f);
        assertTrue(shake.isFinished());
        assertEquals(0.5f, shake.getElapsed(), EPS);
        assertEquals(Vec2f.ZERO, shake.getOffset());

        shake.reset();
        assertFalse(shake.isFinished());
        assertEquals(0.0f, shake.getElapsed(), EPS);
        assertEquals(Vec2f.ZERO, shake.getOffset());
    }

    @Test
    void validatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> ScreenShake.custom(10.0f, 10.0f, -1.0f, 20.0f, 1.0f));
        assertThrows(IllegalArgumentException.class, () -> ScreenShake.custom(10.0f, 10.0f, 1.0f, 20.0f, -0.5f));
        assertThrows(IllegalArgumentException.class, () -> ScreenShake.custom(Float.NaN, 0.0f, 1.0f, 20.0f, 1.0f));

        ScreenShake shake = ScreenShake.vpunch();
        assertThrows(IllegalArgumentException.class, () -> shake.update(-0.01f));
        assertThrows(IllegalArgumentException.class, () -> shake.update(Float.POSITIVE_INFINITY));
    }
}
