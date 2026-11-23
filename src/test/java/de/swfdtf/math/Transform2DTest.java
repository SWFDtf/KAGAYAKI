package de.swfdtf.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Transform2DTest {

    private static final float EPS = 1e-5f;

    @Test
    void defaultsAndFluentSetters() {
        Transform2D t = new Transform2D();
        assertEquals(0.0f, t.getXpos(), EPS);
        assertEquals(0.0f, t.getYpos(), EPS);
        assertEquals(0.0f, t.getXanchor(), EPS);
        assertEquals(0.0f, t.getYanchor(), EPS);
        assertTrue(Float.isNaN(t.getXalign()));
        assertTrue(Float.isNaN(t.getYalign()));
        assertFalse(t.hasXalign());
        assertFalse(t.hasYalign());
        assertEquals(1.0f, t.getZoom(), EPS);
        assertEquals(1.0f, t.getXzoom(), EPS);
        assertEquals(1.0f, t.getYzoom(), EPS);
        assertEquals(0.0f, t.getRotate(), EPS);
        assertEquals(1.0f, t.getAlpha(), EPS);
        assertEquals(0.0f, t.getBlur(), EPS);
        assertEquals(ColorMatrix4f.identity(), t.getColorMatrix());
        assertNull(t.getCrop());
        assertEquals(0.0f, t.getParallaxX(), EPS);
        assertEquals(0.0f, t.getParallaxY(), EPS);

        assertThrows(NullPointerException.class, () -> t.setColorMatrix(null));
    }

    @Test
    void copyCreatesIndependentInstance() {
        Transform2D original = new Transform2D()
                .setXpos(120.0f)
                .setYpos(240.0f)
                .setXanchor(0.5f)
                .setYanchor(1.0f)
                .setZoom(1.5f)
                .setRotate(15.0f)
                .setAlpha(0.8f)
                .setBlur(2.0f)
                .setColorMatrix(ColorMatrix4f.brightness(0.1f))
                .setCrop(new Rect2f(0.0f, 0.0f, 300.0f, 400.0f))
                .setParallaxX(0.05f)
                .setParallaxY(-0.02f);

        Transform2D copy = original.copy();
        assertNotSame(original, copy);
        assertEquals(original, copy);

        copy.setXpos(999.0f).setAlpha(0.1f);
        assertEquals(120.0f, original.getXpos(), EPS);
        assertEquals(0.8f, original.getAlpha(), EPS);
    }

    @Test
    void resolveBoundsWithPosAnchorZoomAndCrop() {
        Transform2D t = new Transform2D()
                .setXpos(960.0f)
                .setYpos(1080.0f)
                .setXanchor(0.5f)
                .setYanchor(1.0f)
                .setZoom(2.0f)
                .setXzoom(-1.0f)
                .setYzoom(0.5f);

        // width=200 * |2 * -1| = 400; height=400 * |2 * 0.5| = 400
        // x = 960 - 0.5 * 400 = 760; y = 1080 - 1.0 * 400 = 680
        Rect2f bounds = t.resolveBounds(200.0f, 400.0f, 1920.0f, 1080.0f);
        assertEquals(760.0f, bounds.x(), EPS);
        assertEquals(680.0f, bounds.y(), EPS);
        assertEquals(400.0f, bounds.width(), EPS);
        assertEquals(400.0f, bounds.height(), EPS);

        // Crop replaces source dimensions before zoom
        t.setCrop(new Rect2f(10.0f, 20.0f, 100.0f, 200.0f));
        Rect2f croppedBounds = t.resolveBounds(200.0f, 400.0f, 1920.0f, 1080.0f);
        assertEquals(860.0f, croppedBounds.x(), EPS);
        assertEquals(880.0f, croppedBounds.y(), EPS);
        assertEquals(200.0f, croppedBounds.width(), EPS);
        assertEquals(200.0f, croppedBounds.height(), EPS);
    }

    @Test
    void resolveBoundsWithAlignOverridesPosAndAnchor() {
        Transform2D centerBottom = new Transform2D()
                .setXpos(123.0f)
                .setXanchor(0.9f)
                .setXalign(0.5f)
                .setYalign(1.0f);

        Rect2f bounds = centerBottom.resolveBounds(400.0f, 600.0f, 1920.0f, 1080.0f);
        assertEquals(760.0f, bounds.x(), EPS);
        assertEquals(480.0f, bounds.y(), EPS);
        assertEquals(400.0f, bounds.width(), EPS);
        assertEquals(600.0f, bounds.height(), EPS);
    }

    @Test
    void lerpToInterpolatesAllPropertiesSafely() {
        Transform2D a = new Transform2D()
                .setXpos(0.0f)
                .setYpos(100.0f)
                .setXalign(0.0f)
                .setZoom(1.0f)
                .setRotate(0.0f)
                .setAlpha(0.0f)
                .setBlur(0.0f)
                .setColorMatrix(ColorMatrix4f.identity())
                .setCrop(new Rect2f(0.0f, 0.0f, 100.0f, 100.0f))
                .setParallaxX(0.0f);

        Transform2D b = new Transform2D()
                .setXpos(200.0f)
                .setYpos(300.0f)
                .setXalign(1.0f)
                .setZoom(2.0f)
                .setRotate(90.0f)
                .setAlpha(1.0f)
                .setBlur(4.0f)
                .setColorMatrix(ColorMatrix4f.brightness(0.4f))
                .setCrop(new Rect2f(20.0f, 40.0f, 200.0f, 300.0f))
                .setParallaxX(0.2f);

        Transform2D mid = a.lerpTo(b, 0.5f);
        assertEquals(100.0f, mid.getXpos(), EPS);
        assertEquals(200.0f, mid.getYpos(), EPS);
        assertEquals(0.5f, mid.getXalign(), EPS);
        assertTrue(Float.isNaN(mid.getYalign()));
        assertEquals(1.5f, mid.getZoom(), EPS);
        assertEquals(45.0f, mid.getRotate(), EPS);
        assertEquals(0.5f, mid.getAlpha(), EPS);
        assertEquals(2.0f, mid.getBlur(), EPS);
        assertEquals(ColorMatrix4f.brightness(0.2f), mid.getColorMatrix());
        assertEquals(new Rect2f(10.0f, 20.0f, 150.0f, 200.0f), mid.getCrop());
        assertEquals(0.1f, mid.getParallaxX(), EPS);

        // One-sided NaN xalign does not poison interpolation
        Transform2D unset = new Transform2D().setXpos(50.0f);
        assertTrue(Float.isNaN(unset.lerpTo(b, 0.0f).getXalign()));
        assertEquals(1.0f, unset.lerpTo(b, 1.0f).getXalign(), EPS);
    }
}
