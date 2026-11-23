package de.swfdtf.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Vec2fTest {

    private static final float EPS = 1e-6f;

    @Test
    void constantsHaveExpectedValues() {
        assertEquals(new Vec2f(0.0f, 0.0f), Vec2f.ZERO);
        assertEquals(new Vec2f(1.0f, 1.0f), Vec2f.ONE);
        assertEquals(new Vec2f(0.5f, 0.5f), Vec2f.HALF);
    }

    @Test
    void arithmeticAndDistanceOperations() {
        Vec2f a = new Vec2f(3.0f, 4.0f);
        Vec2f b = new Vec2f(-1.0f, 2.0f);

        assertEquals(new Vec2f(2.0f, 6.0f), a.add(b));
        assertEquals(new Vec2f(4.0f, 2.0f), a.sub(b));
        assertEquals(new Vec2f(6.0f, 8.0f), a.scale(2.0f));
        assertEquals(5.0f, a.length(), EPS);
        assertEquals(0.0f, Vec2f.ZERO.length(), EPS);
        assertEquals((float) Math.hypot(4.0, 2.0), a.distance(b), EPS);
    }

    @Test
    void lerpInterpolatesLinearly() {
        Vec2f start = new Vec2f(10.0f, 20.0f);
        Vec2f end = new Vec2f(30.0f, -20.0f);

        assertEquals(start, start.lerp(end, 0.0f));
        assertEquals(end, start.lerp(end, 1.0f));
        assertEquals(new Vec2f(15.0f, 10.0f), start.lerp(end, 0.25f));
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> Vec2f.ONE.add(null));
        assertThrows(NullPointerException.class, () -> Vec2f.ONE.sub(null));
        assertThrows(NullPointerException.class, () -> Vec2f.ONE.lerp(null, 0.5f));
        assertThrows(NullPointerException.class, () -> Vec2f.ONE.distance(null));
    }
}
