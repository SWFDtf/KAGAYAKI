package de.swfdtf.graphics;

import de.swfdtf.math.Easing;
import de.swfdtf.math.Rect2f;

/**
 * Controller for smoothly animated top and bottom cinematic letterbox bars in logical
 * {@code 1920x1080} display coordinates.
 */
public final class CinematicLetterboxController {

    /** Default cinematic letterbox bar height in logical {@code 1920x1080} pixels. */
    public static final float DEFAULT_BAR_HEIGHT = 120.0f;

    /** Default transition duration in seconds used by {@link #engageDefault()}. */
    public static final double DEFAULT_TRANSITION_SECONDS = 0.45;

    private float currentBarHeight = 0.0f;
    private float startBarHeight = 0.0f;
    private float targetBarHeight = 0.0f;
    private double durationSeconds = 0.0;
    private double elapsedSeconds = 0.0;
    private boolean transitioning = false;

    /**
     * Creates a {@code CinematicLetterboxController} initialized in the inactive state
     * ({@code barHeight = 0.0f}).
     */
    public CinematicLetterboxController() {
    }

    /**
     * Animates the letterbox bars toward {@code barHeight} over {@code transitionSeconds}.
     *
     * @param barHeight         target height of each bar in logical pixels ({@code >= 0.0f})
     * @param transitionSeconds animation duration in seconds ({@code >= 0.0})
     * @throws IllegalArgumentException if {@code barHeight < 0.0f} or non-finite,
     *                                  or if {@code transitionSeconds < 0.0} or non-finite
     */
    public void engage(float barHeight, double transitionSeconds) {
        if (!Float.isFinite(barHeight) || barHeight < 0.0f) {
            throw new IllegalArgumentException("barHeight must be finite and >= 0.0, was: " + barHeight);
        }
        validateNonNegativeDouble(transitionSeconds, "transitionSeconds");

        if (transitionSeconds == 0.0) {
            this.currentBarHeight = barHeight;
            this.startBarHeight = barHeight;
            this.targetBarHeight = barHeight;
            this.durationSeconds = 0.0;
            this.elapsedSeconds = 0.0;
            this.transitioning = false;
            return;
        }

        this.startBarHeight = this.currentBarHeight;
        this.targetBarHeight = barHeight;
        this.durationSeconds = transitionSeconds;
        this.elapsedSeconds = 0.0;
        this.transitioning = true;
    }

    /**
     * Animates the letterbox bars to {@link #DEFAULT_BAR_HEIGHT} over {@link #DEFAULT_TRANSITION_SECONDS}.
     */
    public void engageDefault() {
        engage(DEFAULT_BAR_HEIGHT, DEFAULT_TRANSITION_SECONDS);
    }

    /**
     * Animates the letterbox bars back to {@code 0.0f} over {@code transitionSeconds}.
     *
     * @param transitionSeconds retraction duration in seconds ({@code >= 0.0})
     * @throws IllegalArgumentException if {@code transitionSeconds < 0.0} or non-finite
     */
    public void release(double transitionSeconds) {
        engage(0.0f, transitionSeconds);
    }

    /**
     * Advances the letterbox animation by {@code deltaSeconds}.
     *
     * @param deltaSeconds elapsed frame time in seconds ({@code >= 0.0})
     * @throws IllegalArgumentException if {@code deltaSeconds < 0.0} or non-finite
     */
    public void update(double deltaSeconds) {
        validateNonNegativeDouble(deltaSeconds, "deltaSeconds");
        if (!transitioning || deltaSeconds == 0.0) {
            return;
        }

        elapsedSeconds = Math.min(durationSeconds, elapsedSeconds + deltaSeconds);
        float linearT = (float) (elapsedSeconds / durationSeconds);
        float easedT = Easing.smoothstep(linearT);
        currentBarHeight = startBarHeight + (targetBarHeight - startBarHeight) * easedT;

        if (elapsedSeconds >= durationSeconds) {
            currentBarHeight = targetBarHeight;
            transitioning = false;
        }
    }

    /**
     * Returns the current interpolated height of each letterbox bar in logical pixels.
     *
     * @return current bar height ({@code >= 0.0f})
     */
    public float getCurrentBarHeight() {
        return currentBarHeight;
    }

    /**
     * Returns whether the letterbox bars are currently visible or animating toward a non-zero height.
     *
     * @return {@code true} if {@code currentBarHeight > 0.0f} or {@code targetBarHeight > 0.0f}
     */
    public boolean isActive() {
        return currentBarHeight > 0.0f || targetBarHeight > 0.0f;
    }

    /**
     * Computes the top letterbox bar rectangle spanning {@code [0, logicalWidth] x [0, currentBarHeight]}.
     *
     * @param logicalWidth logical viewport width in pixels ({@code > 0.0f})
     * @return top bar {@link Rect2f}
     * @throws IllegalArgumentException if {@code logicalWidth <= 0.0f} or non-finite
     */
    public Rect2f getTopBarRect(float logicalWidth) {
        validatePositiveFloat(logicalWidth, "logicalWidth");
        return new Rect2f(0.0f, 0.0f, logicalWidth, currentBarHeight);
    }

    /**
     * Computes the bottom letterbox bar rectangle anchored to the bottom of the logical viewport.
     *
     * @param logicalWidth  logical viewport width in pixels ({@code > 0.0f})
     * @param logicalHeight logical viewport height in pixels ({@code > 0.0f})
     * @return bottom bar {@link Rect2f}
     * @throws IllegalArgumentException if either dimension is {@code <= 0.0f} or non-finite
     */
    public Rect2f getBottomBarRect(float logicalWidth, float logicalHeight) {
        validatePositiveFloat(logicalWidth, "logicalWidth");
        validatePositiveFloat(logicalHeight, "logicalHeight");
        float clampedHeight = Math.min(currentBarHeight, logicalHeight);
        float topY = Math.max(0.0f, logicalHeight - clampedHeight);
        return new Rect2f(0.0f, topY, logicalWidth, clampedHeight);
    }

    private static void validatePositiveFloat(float value, String name) {
        if (!Float.isFinite(value) || value <= 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and > 0.0, was: " + value);
        }
    }

    private static void validateNonNegativeDouble(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and >= 0.0, was: " + value);
        }
    }
}
