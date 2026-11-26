package de.swfdtf.math;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable 4x4 color transformation matrix stored in column-major order for OpenGL
 * {@code glUniformMatrix4fv(..., false, ...)} and designed for premultiplied-alpha RGBA vectors
 * {@code (R_pre, G_pre, B_pre, A) = (R * A, G * A, B * A, A)}.
 *
 * <p>Because the 4th component of a premultiplied color vector is {@code A}, entries in column 3
 * ({@code m[12], m[13], m[14]}) naturally scale offsets by {@code A}, preserving premultiplied alpha
 * invariants across arbitrary chained color operations.</p>
 */
public final class ColorMatrix4f {

    /** Rec. 709 red luminance weight. */
    public static final float LUMA_R = 0.2126f;

    /** Rec. 709 green luminance weight. */
    public static final float LUMA_G = 0.7152f;

    /** Rec. 709 blue luminance weight. */
    public static final float LUMA_B = 0.0722f;

    private static final float INV_SQRT_3 = (float) (1.0 / Math.sqrt(3.0));

    private static final ColorMatrix4f IDENTITY = new ColorMatrix4f(new float[]{
        1.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 1.0f
    });

    private final float[] m;

    private ColorMatrix4f(float[] columnMajor) {
        this.m = columnMajor;
    }

    /**
     * Returns the 4x4 identity color matrix.
     *
     * @return identity matrix
     */
    public static ColorMatrix4f identity() {
        return IDENTITY;
    }

    /**
     * Creates a matrix from a 16-element column-major array.
     *
     * @param m 16-element column-major float array (non-null)
     * @return new {@code ColorMatrix4f}
     * @throws IllegalArgumentException if {@code m.length != 16}
     */
    public static ColorMatrix4f ofColumnMajor(float[] m) {
        Objects.requireNonNull(m, "m must not be null");
        if (m.length != 16) {
            throw new IllegalArgumentException("Expected 16 elements, got " + m.length);
        }
        return new ColorMatrix4f(m.clone());
    }

    /**
     * Creates a matrix from a 16-element row-major array, transposing into internal column-major layout.
     *
     * @param m 16-element row-major float array (non-null)
     * @return new {@code ColorMatrix4f}
     * @throws IllegalArgumentException if {@code m.length != 16}
     */
    public static ColorMatrix4f ofRowMajor(float[] m) {
        Objects.requireNonNull(m, "m must not be null");
        if (m.length != 16) {
            throw new IllegalArgumentException("Expected 16 elements, got " + m.length);
        }
        float[] colMajor = new float[16];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                colMajor[col * 4 + row] = m[row * 4 + col];
            }
        }
        return new ColorMatrix4f(colMajor);
    }

    /**
     * Creates a brightness adjustment matrix adding {@code b * A} to R, G, and B via column 3.
     *
     * @param b brightness offset, typically in {@code [-1, 1]}
     * @return brightness matrix
     */
    public static ColorMatrix4f brightness(float b) {
        return new ColorMatrix4f(new float[]{
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            b,    b,    b,    1.0f
        });
    }

    /**
     * Creates a contrast adjustment matrix scaling RGB by {@code c} and shifting by {@code ((1 - c) / 2) * A}
     * via column 3.
     *
     * @param c contrast factor ({@code >= 0}, {@code 1.0f} is neutral)
     * @return contrast matrix
     */
    public static ColorMatrix4f contrast(float c) {
        float offset = (1.0f - c) * 0.5f;
        return new ColorMatrix4f(new float[]{
            c,      0.0f,   0.0f,   0.0f,
            0.0f,   c,      0.0f,   0.0f,
            0.0f,   0.0f,   c,      0.0f,
            offset, offset, offset, 1.0f
        });
    }

    /**
     * Creates a saturation adjustment matrix using Rec. 709 luminance weights
     * ({@code Lr = 0.2126, Lg = 0.7152, Lb = 0.0722}).
     *
     * @param s saturation factor ({@code >= 0}, {@code 0.0f} produces grayscale, {@code 1.0f} is identity)
     * @return saturation matrix
     */
    public static ColorMatrix4f saturation(float s) {
        float inv = 1.0f - s;
        float sr = inv * LUMA_R;
        float sg = inv * LUMA_G;
        float sb = inv * LUMA_B;
        return new ColorMatrix4f(new float[]{
            sr + s, sr,     sr,     0.0f,
            sg,     sg + s, sg,     0.0f,
            sb,     sb,     sb + s, 0.0f,
            0.0f,   0.0f,   0.0f,   1.0f
        });
    }

    /**
     * Creates a hue rotation matrix using Rodrigues' rotation formula around the neutral gray axis
     * {@code k = (1/sqrt(3), 1/sqrt(3), 1/sqrt(3))}.
     *
     * @param degrees rotation angle in degrees
     * @return hue rotation matrix
     */
    public static ColorMatrix4f hueRotate(float degrees) {
        double radians = Math.toRadians(degrees);
        float c = (float) Math.cos(radians);
        float s = (float) Math.sin(radians);
        float t = (1.0f - c) / 3.0f;
        float u = s * INV_SQRT_3;

        float diag = c + t;
        float r01 = t - u;
        float r02 = t + u;

        return new ColorMatrix4f(new float[]{
            diag, r02,  r01,  0.0f,
            r01,  diag, r02,  0.0f,
            r02,  r01,  diag, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
        });
    }

    /**
     * Creates a multiplicative RGB tint matrix leaving alpha unchanged.
     *
     * @param r red multiplier
     * @param g green multiplier
     * @param b blue multiplier
     * @return tint matrix
     */
    public static ColorMatrix4f tint(float r, float g, float b) {
        return new ColorMatrix4f(new float[]{
            r,    0.0f, 0.0f, 0.0f,
            0.0f, g,    0.0f, 0.0f,
            0.0f, 0.0f, b,    0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
        });
    }

    /**
     * Time-of-day atmospheric color-grading presets.
     */
    public enum TimeOfDayPreset {
        /** Crisp warm-golden sunrise tone. */
        MORNING,
        /** Clear neutral daylight tone. */
        NOON,
        /** Rich amber-peach sunset tone. */
        EVENING,
        /** Cool moonlit indigo night tone. */
        NIGHT
    }

    /**
     * Creates an opacity scaling matrix for premultiplied alpha, scaling {@code (R_pre, G_pre, B_pre, A)} by {@code a}.
     *
     * @param a alpha multiplier
     * @return opacity matrix
     */
    public static ColorMatrix4f opacity(float a) {
        return new ColorMatrix4f(new float[]{
            a,    0.0f, 0.0f, 0.0f,
            0.0f, a,    0.0f, 0.0f,
            0.0f, 0.0f, a,    0.0f,
            0.0f, 0.0f, 0.0f, a
        });
    }

    /**
     * Creates a classic warm sepia tone matrix matching standard W3C CSS sepia coefficients.
     *
     * @return sepia color matrix
     */
    public static ColorMatrix4f sepia() {
        return new ColorMatrix4f(new float[]{
            0.393f, 0.349f, 0.272f, 0.0f,
            0.769f, 0.686f, 0.534f, 0.0f,
            0.189f, 0.168f, 0.131f, 0.0f,
            0.0f,   0.0f,   0.0f,   1.0f
        });
    }

    /**
     * Creates a grayscale matrix using Rec. 709 luminance weights ({@code saturation(0.0f)}).
     *
     * @return grayscale color matrix
     */
    public static ColorMatrix4f grayscale() {
        return saturation(0.0f);
    }

    /**
     * Approximates white-balance color temperature tinting from warm candlelight ({@code 2000K}) to cool sky
     * ({@code 12000K}), normalized around daylight {@code 6500K}.
     *
     * @param kelvin temperature in Kelvin (clamped to {@code [1500, 15000]})
     * @return color temperature tint matrix
     */
    public static ColorMatrix4f colorTemperature(float kelvin) {
        float clampedK = Float.isFinite(kelvin) ? Math.clamp(kelvin, 1500.0f, 15000.0f) : 6500.0f;
        float temp = clampedK / 100.0f;
        float r;
        float g;
        float b;
        if (temp <= 66.0f) {
            r = 255.0f;
            g = Math.clamp((float) (99.4708025861 * Math.log(temp) - 161.1195681661), 0.0f, 255.0f);
            b = temp <= 19.0f
                    ? 0.0f
                    : Math.clamp((float) (138.5177312231 * Math.log(temp - 10.0) - 305.0447927307), 0.0f, 255.0f);
        } else {
            r = Math.clamp((float) (329.698727446 * Math.pow(temp - 60.0, -0.1332047592)), 0.0f, 255.0f);
            g = Math.clamp((float) (288.1221695283 * Math.pow(temp - 60.0, -0.0755148492)), 0.0f, 255.0f);
            b = 255.0f;
        }
        return tint(r / 255.0f, g / 255.0f, b / 255.0f);
    }

    /**
     * Returns a curated atmospheric {@link ColorMatrix4f} for a {@link TimeOfDayPreset}.
     *
     * @param preset time-of-day preset (must not be {@code null})
     * @return atmospheric grading matrix
     */
    public static ColorMatrix4f timeOfDay(TimeOfDayPreset preset) {
        Objects.requireNonNull(preset, "preset must not be null");
        return switch (preset) {
            case MORNING -> tint(1.04f, 0.98f, 0.90f).multiply(brightness(0.03f));
            case NOON -> identity();
            case EVENING -> tint(1.08f, 0.84f, 0.68f).multiply(saturation(1.08f));
            case NIGHT -> tint(0.58f, 0.68f, 0.96f)
                    .multiply(saturation(0.75f))
                    .multiply(brightness(-0.08f));
        };
    }

    /**
     * Creates a color inversion matrix for premultiplied alpha, transforming {@code C_pre} into {@code A - C_pre}.
     *
     * @return inversion matrix
     */
    public static ColorMatrix4f invert() {
        return new ColorMatrix4f(new float[]{
            -1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, -1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, -1.0f, 0.0f,
            1.0f, 1.0f, 1.0f, 1.0f
        });
    }

    /**
     * Returns the matrix product {@code this * other}.
     * When applied to a color vector {@code v}, {@code this.multiply(other)} applies {@code other} first and
     * {@code this} second.
     *
     * @param other right-hand operand (non-null)
     * @return product matrix {@code this * other}
     */
    public ColorMatrix4f multiply(ColorMatrix4f other) {
        Objects.requireNonNull(other, "other must not be null");
        float[] res = new float[16];
        float[] a = this.m;
        float[] b = other.m;

        for (int col = 0; col < 4; col++) {
            int colOffset = col * 4;
            float b0 = b[colOffset];
            float b1 = b[colOffset + 1];
            float b2 = b[colOffset + 2];
            float b3 = b[colOffset + 3];
            for (int row = 0; row < 4; row++) {
                res[colOffset + row] = a[row] * b0
                        + a[4 + row] * b1
                        + a[8 + row] * b2
                        + a[12 + row] * b3;
            }
        }
        return new ColorMatrix4f(res);
    }

    /**
     * Linearly interpolates element-wise between {@code this} (at {@code t = 0}) and {@code target} (at {@code t = 1}).
     *
     * @param target target matrix (non-null)
     * @param t      interpolation factor
     * @return interpolated matrix
     */
    public ColorMatrix4f lerp(ColorMatrix4f target, float t) {
        Objects.requireNonNull(target, "target must not be null");
        float[] res = new float[16];
        for (int i = 0; i < 16; i++) {
            res[i] = m[i] + (target.m[i] - m[i]) * t;
        }
        return new ColorMatrix4f(res);
    }

    /**
     * Returns a defensive 16-float copy in column-major order suitable for {@code glUniformMatrix4fv}.
     *
     * @return 16-element column-major array
     */
    public float[] toFloatArray() {
        return m.clone();
    }

    /**
     * Applies this color matrix to a premultiplied RGBA color vector {@code [rPre, gPre, bPre, a]^T}.
     *
     * @param rPre premultiplied red ({@code r * a})
     * @param gPre premultiplied green ({@code g * a})
     * @param bPre premultiplied blue ({@code b * a})
     * @param a    alpha
     * @return transformed 4-element array {@code [rOut, gOut, bOut, aOut]}
     */
    public float[] applyPremultiplied(float rPre, float gPre, float bPre, float a) {
        return new float[]{
                m[0] * rPre + m[4] * gPre + m[8] * bPre + m[12] * a,
                m[1] * rPre + m[5] * gPre + m[9] * bPre + m[13] * a,
                m[2] * rPre + m[6] * gPre + m[10] * bPre + m[14] * a,
                m[3] * rPre + m[7] * gPre + m[11] * bPre + m[15] * a
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ColorMatrix4f other)) {
            return false;
        }
        return Arrays.equals(m, other.m);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(m);
    }

    @Override
    public String toString() {
        return "ColorMatrix4f" + Arrays.toString(m);
    }
}
