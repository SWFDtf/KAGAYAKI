package de.swfdtf.graphics;

import de.swfdtf.math.ColorMatrix4f;

import java.util.Objects;

/**
 * Color-vision deficiency simulation and visual accessibility filter generating
 * premultiplied-alpha {@link ColorMatrix4f} transforms.
 *
 * <p>Blends smoothly between {@link ColorMatrix4f#identity()} (at {@code intensity = 0.0f})
 * and the full mode-specific matrix (at {@code intensity = 1.0f}).</p>
 */
public final class ColorBlindnessFilter {

    private static final ColorMatrix4f PROTANOPIA_MATRIX = ColorMatrix4f.ofRowMajor(new float[]{
            0.567f, 0.433f, 0.000f, 0.0f,
            0.558f, 0.442f, 0.000f, 0.0f,
            0.000f, 0.242f, 0.758f, 0.0f,
            0.000f, 0.000f, 0.000f, 1.0f
    });

    private static final ColorMatrix4f DEUTERANOPIA_MATRIX = ColorMatrix4f.ofRowMajor(new float[]{
            0.625f, 0.375f, 0.000f, 0.0f,
            0.700f, 0.300f, 0.000f, 0.0f,
            0.000f, 0.300f, 0.700f, 0.0f,
            0.000f, 0.000f, 0.000f, 1.0f
    });

    private static final ColorMatrix4f TRITANOPIA_MATRIX = ColorMatrix4f.ofRowMajor(new float[]{
            0.950f, 0.050f, 0.000f, 0.0f,
            0.000f, 0.433f, 0.567f, 0.0f,
            0.000f, 0.475f, 0.525f, 0.0f,
            0.000f, 0.000f, 0.000f, 1.0f
    });

    private static final ColorMatrix4f HIGH_CONTRAST_MATRIX = ColorMatrix4f.contrast(1.4f)
            .multiply(ColorMatrix4f.saturation(1.15f));

    private static final ColorMatrix4f GRAYSCALE_MATRIX = ColorMatrix4f.saturation(0.0f);

    /**
     * Supported color-vision and visual accessibility filter modes.
     */
    public enum AccessibilityMode {
        /** No color transformation (identity matrix). */
        NONE,
        /** Red-weak/red-blind simulation (protanopia). */
        PROTANOPIA,
        /** Green-weak/green-blind simulation (deuteranopia). */
        DEUTERANOPIA,
        /** Blue-yellow color blindness simulation (tritanopia). */
        TRITANOPIA,
        /** High-contrast boost for improved figure-ground separation. */
        HIGH_CONTRAST,
        /** Rec. 709 luminance grayscale transform. */
        GRAYSCALE
    }

    private AccessibilityMode mode = AccessibilityMode.NONE;
    private float intensity = 1.0f;

    /**
     * Creates a {@code ColorBlindnessFilter} initialized to {@link AccessibilityMode#NONE}
     * with {@code intensity = 1.0f}.
     */
    public ColorBlindnessFilter() {
    }

    /**
     * Creates a {@code ColorBlindnessFilter} initialized with an explicit mode and intensity.
     *
     * @param mode      accessibility filter mode (must not be {@code null})
     * @param intensity blend intensity in {@code [0.0f, 1.0f]}
     * @throws NullPointerException     if {@code mode} is {@code null}
     * @throws IllegalArgumentException if {@code intensity} is outside {@code [0.0f, 1.0f]} or non-finite
     */
    public ColorBlindnessFilter(AccessibilityMode mode, float intensity) {
        setMode(mode);
        setIntensity(intensity);
    }

    /**
     * Returns the currently selected {@link AccessibilityMode}.
     *
     * @return active accessibility mode
     */
    public AccessibilityMode getMode() {
        return mode;
    }

    /**
     * Updates the active {@link AccessibilityMode}.
     *
     * @param mode accessibility mode (must not be {@code null})
     * @throws NullPointerException if {@code mode} is {@code null}
     */
    public void setMode(AccessibilityMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
    }

    /**
     * Returns the current blend intensity in {@code [0.0f, 1.0f]}.
     *
     * @return filter blend intensity
     */
    public float getIntensity() {
        return intensity;
    }

    /**
     * Sets the filter blend intensity in {@code [0.0f, 1.0f]}.
     *
     * @param intensity blend factor ({@code 0.0f} = identity, {@code 1.0f} = full filter)
     * @throws IllegalArgumentException if {@code intensity} is outside {@code [0.0f, 1.0f]} or non-finite
     */
    public void setIntensity(float intensity) {
        validateIntensity(intensity);
        this.intensity = intensity;
    }

    /**
     * Builds the {@link ColorMatrix4f} corresponding to the current {@link #getMode()} and
     * {@link #getIntensity()}.
     *
     * @return blended 4x4 color transformation matrix
     */
    public ColorMatrix4f buildMatrix() {
        return forMode(mode, intensity);
    }

    /**
     * Computes a {@link ColorMatrix4f} blended between {@link ColorMatrix4f#identity()} and the
     * full matrix for {@code mode} using {@code intensity} in {@code [0.0f, 1.0f]}.
     *
     * @param mode      accessibility filter mode (must not be {@code null})
     * @param intensity blend factor in {@code [0.0f, 1.0f]}
     * @return blended 4x4 color transformation matrix
     * @throws NullPointerException     if {@code mode} is {@code null}
     * @throws IllegalArgumentException if {@code intensity} is outside {@code [0.0f, 1.0f]} or non-finite
     */
    public static ColorMatrix4f forMode(AccessibilityMode mode, float intensity) {
        Objects.requireNonNull(mode, "mode must not be null");
        validateIntensity(intensity);

        if (mode == AccessibilityMode.NONE || intensity == 0.0f) {
            return ColorMatrix4f.identity();
        }

        ColorMatrix4f target = switch (mode) {
            case NONE -> ColorMatrix4f.identity();
            case PROTANOPIA -> PROTANOPIA_MATRIX;
            case DEUTERANOPIA -> DEUTERANOPIA_MATRIX;
            case TRITANOPIA -> TRITANOPIA_MATRIX;
            case HIGH_CONTRAST -> HIGH_CONTRAST_MATRIX;
            case GRAYSCALE -> GRAYSCALE_MATRIX;
        };

        if (intensity == 1.0f) {
            return target;
        }
        return ColorMatrix4f.identity().lerp(target, intensity);
    }

    private static void validateIntensity(float intensity) {
        if (!Float.isFinite(intensity) || intensity < 0.0f || intensity > 1.0f) {
            throw new IllegalArgumentException(
                    "intensity must be finite and in [0.0, 1.0], was: " + intensity
            );
        }
    }
}
