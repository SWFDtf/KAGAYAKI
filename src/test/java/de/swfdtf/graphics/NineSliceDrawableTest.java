package de.swfdtf.graphics;

import de.swfdtf.math.Rect2f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NineSliceDrawableTest {

    private static final float EPS = 1e-4f;

    @Test
    void computesNineNonOverlappingSlicesAndExactUvsForNormalSize() {
        // 64x64 texture with asymmetric insets: left=8, top=16, right=12, bottom=8
        Texture texture = new Texture(11, 64, 64, "panel.png", true, false);
        NineSliceDrawable nineSlice = new NineSliceDrawable(texture, 8.0f, 16.0f, 12.0f, 8.0f);

        List<NineSliceDrawable.SliceQuad> slices = nineSlice.computeSlices(100.0f, 200.0f, 300.0f, 120.0f);
        assertEquals(9, slices.size());

        // Row 0 (top): y=200, h=16 | x=[100, 108, 388], w=[8, 280, 12]
        assertRectEquals(new Rect2f(100.0f, 200.0f, 8.0f, 16.0f), slices.get(0).destRect());
        assertRectEquals(new Rect2f(108.0f, 200.0f, 280.0f, 16.0f), slices.get(1).destRect());
        assertRectEquals(new Rect2f(388.0f, 200.0f, 12.0f, 16.0f), slices.get(2).destRect());

        // Row 1 (middle): y=216, h=96
        assertRectEquals(new Rect2f(100.0f, 216.0f, 8.0f, 96.0f), slices.get(3).destRect());
        assertRectEquals(new Rect2f(108.0f, 216.0f, 280.0f, 96.0f), slices.get(4).destRect());
        assertRectEquals(new Rect2f(388.0f, 216.0f, 12.0f, 96.0f), slices.get(5).destRect());

        // Row 2 (bottom): y=312, h=8
        assertRectEquals(new Rect2f(100.0f, 312.0f, 8.0f, 8.0f), slices.get(6).destRect());
        assertRectEquals(new Rect2f(108.0f, 312.0f, 280.0f, 8.0f), slices.get(7).destRect());
        assertRectEquals(new Rect2f(388.0f, 312.0f, 12.0f, 8.0f), slices.get(8).destRect());

        // UVs: u=[0, 8/64=0.125, 52/64=0.8125], uw=[0.125, 0.6875, 0.1875]
        //      v=[0, 16/64=0.25, 56/64=0.875],  vh=[0.25, 0.625, 0.125]
        assertRectEquals(new Rect2f(0.0f, 0.0f, 0.125f, 0.25f), slices.get(0).uvRect());
        assertRectEquals(new Rect2f(0.125f, 0.0f, 0.6875f, 0.25f), slices.get(1).uvRect());
        assertRectEquals(new Rect2f(0.8125f, 0.0f, 0.1875f, 0.25f), slices.get(2).uvRect());
        assertRectEquals(new Rect2f(0.125f, 0.25f, 0.6875f, 0.625f), slices.get(4).uvRect());
        assertRectEquals(new Rect2f(0.8125f, 0.875f, 0.1875f, 0.125f), slices.get(8).uvRect());

        assertNonOverlappingAndExactCoverage(slices, 100.0f, 200.0f, 300.0f, 120.0f);
    }

    @Test
    void clampsCornersProportionallyWithoutOverlapForCrampedAndExactSizes() {
        // Horizontal insets sum to 40 (left=30, right=10); vertical sum to 40 (top=20, bottom=20)
        NineSliceDrawable nineSlice = new NineSliceDrawable(5, 100.0f, 100.0f, 30.0f, 20.0f, 10.0f, 20.0f);

        // Cramped size: 20x10 -> horizontal scale=0.5 (w0=15, w1=0, w2=5), vertical scale=0.25 (h0=5, h1=0, h2=5)
        List<NineSliceDrawable.SliceQuad> cramped = nineSlice.computeSlices(10.0f, 20.0f, 20.0f, 10.0f);
        assertEquals(9, cramped.size());

        assertRectEquals(new Rect2f(10.0f, 20.0f, 15.0f, 5.0f), cramped.get(0).destRect());
        assertRectEquals(new Rect2f(25.0f, 20.0f, 0.0f, 5.0f), cramped.get(1).destRect());
        assertRectEquals(new Rect2f(25.0f, 20.0f, 5.0f, 5.0f), cramped.get(2).destRect());
        assertRectEquals(new Rect2f(25.0f, 25.0f, 0.0f, 0.0f), cramped.get(4).destRect());
        assertRectEquals(new Rect2f(10.0f, 25.0f, 15.0f, 5.0f), cramped.get(6).destRect());
        assertRectEquals(new Rect2f(25.0f, 25.0f, 5.0f, 5.0f), cramped.get(8).destRect());

        // Corner UVs remain anchored to texture border regions
        assertRectEquals(new Rect2f(0.0f, 0.0f, 0.30f, 0.20f), cramped.get(0).uvRect());
        assertRectEquals(new Rect2f(0.90f, 0.80f, 0.10f, 0.20f), cramped.get(8).uvRect());

        assertNonOverlappingAndExactCoverage(cramped, 10.0f, 20.0f, 20.0f, 10.0f);

        // Exact boundary size: 40x40 -> center slice has 0x0 dest size
        List<NineSliceDrawable.SliceQuad> exactBoundary = nineSlice.computeSlices(0.0f, 0.0f, 40.0f, 40.0f);
        assertEquals(9, exactBoundary.size());
        assertRectEquals(new Rect2f(30.0f, 20.0f, 0.0f, 0.0f), exactBoundary.get(4).destRect());
        assertNonOverlappingAndExactCoverage(exactBoundary, 0.0f, 0.0f, 40.0f, 40.0f);

        // Non-positive dimensions return empty slice list
        assertTrue(nineSlice.computeSlices(0.0f, 0.0f, 0.0f, 100.0f).isEmpty());
        assertTrue(nineSlice.computeSlices(0.0f, 0.0f, 100.0f, -5.0f).isEmpty());
    }

    @Test
    void validatesConstructorParameters() {
        Texture tex = new Texture(1, 32, 32, "ui.png", true, false);
        assertThrows(NullPointerException.class, () -> new NineSliceDrawable(null, 4.0f));
        assertThrows(IllegalArgumentException.class, () -> new NineSliceDrawable(tex, -1.0f, 4.0f, 4.0f, 4.0f));
        assertThrows(IllegalArgumentException.class, () -> new NineSliceDrawable(tex, 20.0f, 4.0f, 13.0f, 4.0f));
        assertThrows(IllegalArgumentException.class, () -> new NineSliceDrawable(tex, 4.0f, 20.0f, 4.0f, 13.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new NineSliceDrawable(1, 0.0f, 32.0f, 4.0f, 4.0f, 4.0f, 4.0f));
    }

    private static void assertNonOverlappingAndExactCoverage(
            List<NineSliceDrawable.SliceQuad> slices,
            float expectedX,
            float expectedY,
            float expectedW,
            float expectedH
    ) {
        float totalArea = 0.0f;
        float totalUvArea = 0.0f;
        for (int i = 0; i < slices.size(); i++) {
            Rect2f a = slices.get(i).destRect();
            Rect2f uva = slices.get(i).uvRect();
            assertTrue(a.width() >= 0.0f && a.height() >= 0.0f);
            assertTrue(a.x() >= expectedX - EPS && a.right() <= expectedX + expectedW + EPS);
            assertTrue(a.y() >= expectedY - EPS && a.bottom() <= expectedY + expectedH + EPS);
            assertTrue(uva.x() >= -EPS && uva.right() <= 1.0f + EPS);
            assertTrue(uva.y() >= -EPS && uva.bottom() <= 1.0f + EPS);

            totalArea += a.width() * a.height();
            totalUvArea += uva.width() * uva.height();

            for (int j = i + 1; j < slices.size(); j++) {
                Rect2f b = slices.get(j).destRect();
                assertFalse(a.intersects(b), "Slices " + i + " and " + j + " overlap: " + a + " vs " + b);
            }
        }
        assertEquals(expectedW * expectedH, totalArea, EPS);
        assertEquals(1.0f, totalUvArea, EPS);
    }

    private static void assertRectEquals(Rect2f expected, Rect2f actual) {
        assertEquals(expected.x(), actual.x(), EPS);
        assertEquals(expected.y(), actual.y(), EPS);
        assertEquals(expected.width(), actual.width(), EPS);
        assertEquals(expected.height(), actual.height(), EPS);
    }
}
