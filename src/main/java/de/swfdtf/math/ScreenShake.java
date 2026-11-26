package de.swfdtf.math;

/**
 * Screen-shake generator implementing damped harmonic oscillation:
 * {@code Delta(t) = A * exp(-lambda * t) * sin(omega * t)}.
 *
 * <p>Blueprint defaults: {@code A = 30.0f}, {@code lambda = 5.0f}, {@code omega = 40.0f}.</p>
 */
public final class ScreenShake {

    /** Blueprint default shake amplitude in logical pixels. */
    public static final float DEFAULT_AMPLITUDE = 30.0f;

    /** Blueprint default exponential damping factor {@code lambda}. */
    public static final float DEFAULT_DECAY = 5.0f;

    /** Blueprint default angular frequency {@code omega} in rad/s. */
    public static final float DEFAULT_OMEGA = 40.0f;

    /** Default punch duration in seconds (at {@code t=1s}, {@code exp(-5) < 0.007}). */
    public static final float DEFAULT_DURATION_SECONDS = 1.0f;

    private final float ampX;
    private final float ampY;
    private final float decay;
    private final float omega;
    private final float durationSeconds;

    private float elapsed = 0.0f;

    private ScreenShake(float ampX, float ampY, float decay, float omega, float durationSeconds) {
        if (!Float.isFinite(ampX) || !Float.isFinite(ampY)) {
            throw new IllegalArgumentException("Amplitudes must be finite");
        }
        if (!Float.isFinite(decay) || decay < 0.0f) {
            throw new IllegalArgumentException("decay must be finite and >= 0, was: " + decay);
        }
        if (!Float.isFinite(omega)) {
            throw new IllegalArgumentException("omega must be finite, was: " + omega);
        }
        if (!Float.isFinite(durationSeconds) || durationSeconds < 0.0f) {
            throw new IllegalArgumentException("durationSeconds must be finite and >= 0, was: " + durationSeconds);
        }
        this.ampX = ampX;
        this.ampY = ampY;
        this.decay = decay;
        this.omega = omega;
        this.durationSeconds = durationSeconds;
    }

    /**
     * Creates a vertical punch ({@code vpunch}) screen shake with blueprint defaults
     * ({@code ampY = 30.0f, decay = 5.0f, omega = 40.0f}).
     *
     * @return vertical punch {@code ScreenShake}
     */
    public static ScreenShake vpunch() {
        return new ScreenShake(0.0f, DEFAULT_AMPLITUDE, DEFAULT_DECAY, DEFAULT_OMEGA, DEFAULT_DURATION_SECONDS);
    }

    /**
     * Creates a horizontal punch ({@code hpunch}) screen shake with blueprint defaults
     * ({@code ampX = 30.0f, decay = 5.0f, omega = 40.0f}).
     *
     * @return horizontal punch {@code ScreenShake}
     */
    public static ScreenShake hpunch() {
        return new ScreenShake(DEFAULT_AMPLITUDE, 0.0f, DEFAULT_DECAY, DEFAULT_OMEGA, DEFAULT_DURATION_SECONDS);
    }

    /**
     * Creates a custom screen shake with independent X and Y amplitudes.
     *
     * @param ampX            horizontal amplitude in pixels
     * @param ampY            vertical amplitude in pixels
     * @param decay           exponential decay factor {@code lambda >= 0}
     * @param omega           angular frequency {@code omega} in rad/s
     * @param durationSeconds total duration in seconds ({@code >= 0})
     * @return custom {@code ScreenShake}
     */
    public static ScreenShake custom(float ampX, float ampY, float decay, float omega, float durationSeconds) {
        return new ScreenShake(ampX, ampY, decay, omega, durationSeconds);
    }

    /**
     * Advances the shake simulation by {@code dt} seconds.
     *
     * @param dt delta time in seconds ({@code >= 0})
     * @throws IllegalArgumentException if {@code dt < 0} or non-finite
     */
    public void update(float dt) {
        if (!Float.isFinite(dt) || dt < 0.0f) {
            throw new IllegalArgumentException("dt must be finite and >= 0, was: " + dt);
        }
        if (dt == 0.0f || isFinished()) {
            return;
        }
        elapsed = Math.min(durationSeconds, elapsed + dt);
    }

    /**
     * Computes the current damped oscillation offset {@code (dx, dy)}.
     * Returns {@link Vec2f#ZERO} once {@link #isFinished()} is {@code true}.
     *
     * @return current screen offset in logical pixels
     */
    public Vec2f getOffset() {
        if (elapsed <= 0.0f || isFinished()) {
            return Vec2f.ZERO;
        }
        double factor = Math.exp(-decay * (double) elapsed) * Math.sin(omega * (double) elapsed);
        float dx = (float) (ampX * factor);
        float dy = (float) (ampY * factor);
        return new Vec2f(dx == 0.0f ? 0.0f : dx, dy == 0.0f ? 0.0f : dy);
    }

    /**
     * Returns whether the shake duration has elapsed.
     *
     * @return {@code true} if {@code elapsed >= durationSeconds}
     */
    public boolean isFinished() {
        return elapsed >= durationSeconds;
    }

    /**
     * Resets elapsed time to {@code 0.0f} so the shake can be replayed.
     */
    public void reset() {
        elapsed = 0.0f;
    }

    /**
     * Returns the horizontal amplitude.
     *
     * @return horizontal amplitude
     */
    public float getAmplitudeX() {
        return ampX;
    }

    /**
     * Returns the vertical amplitude.
     *
     * @return vertical amplitude
     */
    public float getAmplitudeY() {
        return ampY;
    }

    /**
     * Returns the exponential decay factor {@code lambda}.
     *
     * @return exponential decay factor {@code lambda}
     */
    public float getDecay() {
        return decay;
    }

    /**
     * Returns the angular frequency {@code omega}.
     *
     * @return angular frequency {@code omega}
     */
    public float getOmega() {
        return omega;
    }

    /**
     * Returns the configured duration in seconds.
     *
     * @return configured duration in seconds
     */
    public float getDurationSeconds() {
        return durationSeconds;
    }

    /**
     * Returns the current elapsed time in seconds.
     *
     * @return current elapsed time in seconds
     */
    public float getElapsed() {
        return elapsed;
    }
}
