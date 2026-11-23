package de.swfdtf.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Rect2fTest {

    private static final float EPS = 1e-6f;

    @Test
    void edgesAndCenters() {
        Rect2f r = new Rect2f(10.0f, 20.0f, 100.0f, 50.0f);
        assertEquals(110.0f, r.right(), EPS);
        assertEquals(70.0f, r.bottom(), EPS);
        assertEquals(60.0f, r.centerX(), EPS);
        assertEquals(45.0f, r.centerY(), EPS);
    }

    @Test
    void containsChecksBoundsIncludingEdges() {
        Rect2f r = new Rect2f(10.0f, 20.0f, 100.0f, 50.0f);
        assertTrue(r.contains(10.0f, 20.0f));
        assertTrue(r.contains(110.0f, 70.0f));
        assertTrue(r.contains(60.0f, 45.0f));
        assertFalse(r.contains(9.99f, 45.0f));
        assertFalse(r.contains(110.01f, 45.0f));
        assertFalse(r.contains(60.0f, 19.99f));
        assertFalse(r.contains(60.0f, 70.01f));

        Rect2f invalid = new Rect2f(0.0f, 0.0f, -5.0f, 10.0f);
        assertFalse(invalid.contains(0.0f, 0.0f));
    }

    @Test
    void intersectsHandlesOverlapAndTouchingEdges() {
        Rect2f a = new Rect2f(0.0f, 0.0f, 100.0f, 100.0f);
        Rect2f overlapping = new Rect2f(50.0f, 50.0f, 100.0f, 100.0f);
        Rect2f touchingRight = new Rect2f(100.0f, 0.0f, 50.0f, 100.0f);
        Rect2f disjoint = new Rect2f(200.0f, 200.0f, 50.0f, 50.0f);
        Rect2f zeroArea = new Rect2f(10.0f, 10.0f, 0.0f, 50.0f);

        assertTrue(a.intersects(overlapping));
        assertTrue(overlapping.intersects(a));
        assertFalse(a.intersects(touchingRight));
        assertFalse(a.intersects(disjoint));
        assertFalse(a.intersects(zeroArea));
        assertThrows(NullPointerException.class, () -> a.intersects(null));
    }

    @Test
    void translateAndInset() {
        Rect2f r = new Rect2f(10.0f, 20.0f, 100.0f, 60.0f);
        assertEquals(new Rect2f(15.0f, 12.0f, 100.0f, 60.0f), r.translate(5.0f, -8.0f));
        assertEquals(new Rect2f(20.0f, 30.0f, 80.0f, 40.0f), r.inset(10.0f));
        assertEquals(new Rect2f(60.0f, 70.0f, 0.0f, 0.0f), r.inset(50.0f));
    }
}
