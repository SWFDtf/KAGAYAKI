package de.swfdtf.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ColorMatrix4fTest {

    private static final float EPS = 1e-5f;

    @Test
    void identityLeavesPremultipliedColorUnchanged() {
        float[] out = ColorMatrix4f.identity().applyPremultiplied(0.3f, 0.4f, 0.2f, 0.5f);
        assertArrayEquals(new float[]{0.3f, 0.4f, 0.2f, 0.5f}, out, EPS);
    }

    @Test
    void columnMajorAndRowMajorConstructorsAndDefensiveCopies() {
        float[] rowMajor = {
                1, 2, 3, 4,
                5, 6, 7, 8,
                9, 10, 11, 12,
                13, 14, 15, 16
        };
        float[] expectedColMajor = {
                1, 5, 9, 13,
                2, 6, 10, 14,
                3, 7, 11, 15,
                4, 8, 12, 16
        };
        ColorMatrix4f fromRow = ColorMatrix4f.ofRowMajor(rowMajor);
        ColorMatrix4f fromCol = ColorMatrix4f.ofColumnMajor(expectedColMajor);
        assertEquals(fromCol, fromRow);

        float[] exported = fromCol.toFloatArray();
        assertArrayEquals(expectedColMajor, exported, EPS);
        exported[0] = 999.0f;
        assertArrayEquals(expectedColMajor, fromCol.toFloatArray(), EPS);
        assertNotSame(exported, fromCol.toFloatArray());

        assertThrows(IllegalArgumentException.class, () -> ColorMatrix4f.ofColumnMajor(new float[15]));
        assertThrows(IllegalArgumentException.class, () -> ColorMatrix4f.ofRowMajor(new float[17]));
    }

    @Test
    void brightnessAddsOffsetScaledByAlpha() {
        float a = 0.4f;
        float rPre = 0.2f * a;
        float gPre = 0.5f * a;
        float bPre = 0.7f * a;
        float[] out = ColorMatrix4f.brightness(0.25f).applyPremultiplied(rPre, gPre, bPre, a);
        assertArrayEquals(new float[]{0.45f * a, 0.75f * a, 0.95f * a, a}, out, EPS);

        // Zero alpha remains zero
        assertArrayEquals(new float[]{0f, 0f, 0f, 0f},
                ColorMatrix4f.brightness(0.5f).applyPremultiplied(0f, 0f, 0f, 0f), EPS);
    }

    @Test
    void contrastScalesAroundHalfAlphaMidpoint() {
        float a = 0.8f;
        float[] midGrayZeroContrast = ColorMatrix4f.contrast(0.0f).applyPremultiplied(0.2f * a, 0.9f * a, 0.1f * a, a);
        assertArrayEquals(new float[]{0.5f * a, 0.5f * a, 0.5f * a, a}, midGrayZeroContrast, EPS);

        float[] doubled = ColorMatrix4f.contrast(2.0f).applyPremultiplied(0.75f * a, 0.5f * a, 0.25f * a, a);
        assertArrayEquals(new float[]{1.0f * a, 0.5f * a, 0.0f * a, a}, doubled, EPS);
    }

    @Test
    void saturationUsesRec709LuminanceAndPreservesNeutralGray() {
        float a = 0.6f;
        float rPre = 0.8f * a;
        float gPre = 0.4f * a;
        float bPre = 0.2f * a;
        float expectedLuma = (0.2126f * 0.8f + 0.7152f * 0.4f + 0.0722f * 0.2f) * a;

        float[] gray = ColorMatrix4f.saturation(0.0f).applyPremultiplied(rPre, gPre, bPre, a);
        assertArrayEquals(new float[]{expectedLuma, expectedLuma, expectedLuma, a}, gray, EPS);

        // Neutral gray invariant under arbitrary saturation
        float[] unchangedGray = ColorMatrix4f.saturation(2.5f).applyPremultiplied(0.5f * a, 0.5f * a, 0.5f * a, a);
        assertArrayEquals(new float[]{0.5f * a, 0.5f * a, 0.5f * a, a}, unchangedGray, EPS);
    }

    @Test
    void hueRotateRotatesPrimariesPreservesGrayAndComposes() {
        float a = 0.5f;
        // 120 degrees maps Red -> Green, Green -> Blue, Blue -> Red
        ColorMatrix4f rot120 = ColorMatrix4f.hueRotate(120.0f);
        assertArrayEquals(new float[]{0.0f, a, 0.0f, a}, rot120.applyPremultiplied(a, 0.0f, 0.0f, a), EPS);
        assertArrayEquals(new float[]{0.0f, 0.0f, a, a}, rot120.applyPremultiplied(0.0f, a, 0.0f, a), EPS);
        assertArrayEquals(new float[]{a, 0.0f, 0.0f, a}, rot120.applyPremultiplied(0.0f, 0.0f, a, a), EPS);

        // Neutral gray is invariant
        assertArrayEquals(new float[]{0.3f, 0.3f, 0.3f, a},
                ColorMatrix4f.hueRotate(73.0f).applyPremultiplied(0.3f, 0.3f, 0.3f, a), EPS);

        // 360 degrees == identity
        assertArrayEquals(ColorMatrix4f.identity().toFloatArray(),
                ColorMatrix4f.hueRotate(360.0f).toFloatArray(), EPS);

        // Group composition: R(40) * R(80) == R(120)
        ColorMatrix4f composed = ColorMatrix4f.hueRotate(40.0f).multiply(ColorMatrix4f.hueRotate(80.0f));
        assertArrayEquals(rot120.toFloatArray(), composed.toFloatArray(), EPS);
    }

    @Test
    void tintOpacityInvertMultiplyAndLerp() {
        float a = 0.5f;
        float[] tinted = ColorMatrix4f.tint(1.0f, 0.5f, 0.25f).applyPremultiplied(0.4f, 0.4f, 0.4f, a);
        assertArrayEquals(new float[]{0.4f, 0.2f, 0.1f, 0.5f}, tinted, EPS);

        float[] faded = ColorMatrix4f.opacity(0.5f).applyPremultiplied(0.4f, 0.2f, 0.1f, 0.8f);
        assertArrayEquals(new float[]{0.2f, 0.1f, 0.05f, 0.4f}, faded, EPS);

        // Invert is self-inverse in premultiplied space
        ColorMatrix4f inv = ColorMatrix4f.invert();
        float[] inverted = inv.applyPremultiplied(0.2f * a, 0.7f * a, 0.9f * a, a);
        assertArrayEquals(new float[]{0.8f * a, 0.3f * a, 0.1f * a, a}, inverted, EPS);
        assertArrayEquals(ColorMatrix4f.identity().toFloatArray(), inv.multiply(inv).toFloatArray(), EPS);

        // Brightness + opacity preserves premultiplied semantics regardless of order
        ColorMatrix4f brightThenFade = ColorMatrix4f.opacity(0.5f).multiply(ColorMatrix4f.brightness(0.2f));
        float[] out = brightThenFade.applyPremultiplied(0.4f * 0.8f, 0.4f * 0.8f, 0.4f * 0.8f, 0.8f);
        assertArrayEquals(new float[]{0.6f * 0.4f, 0.6f * 0.4f, 0.6f * 0.4f, 0.4f}, out, EPS);

        // Lerp midpoint between identity and invert
        ColorMatrix4f mid = ColorMatrix4f.identity().lerp(inv, 0.5f);
        float[] midOut = mid.applyPremultiplied(0.2f * a, 0.8f * a, 0.4f * a, a);
        assertArrayEquals(new float[]{0.5f * a, 0.5f * a, 0.5f * a, a}, midOut, EPS);
    }

    @Test
    void equalsAndHashCodeWorkOnValues() {
        ColorMatrix4f a = ColorMatrix4f.brightness(0.3f);
        ColorMatrix4f b = ColorMatrix4f.brightness(0.3f);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
