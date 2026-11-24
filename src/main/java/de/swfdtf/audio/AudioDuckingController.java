package de.swfdtf.audio;

/**
 * Smoothly attenuates background music and ambient gain while character voice playback is active.
 *
 * <p>Uses configurable attack and release durations to transition linearly between unity gain
 * ({@code 1.0f}) and {@link #getDuckedGain()} without abrupt volume jumps or clicks.</p>
 */
public final class AudioDuckingController {

    /** Default multiplier applied to ducked channels while voice is active. */
    public static final float DEFAULT_DUCKED_GAIN = 0.35f;

    /** Default attack duration in seconds for attenuating down to {@link #DEFAULT_DUCKED_GAIN}. */
    public static final double DEFAULT_ATTACK_SECONDS = 0.12;

    /** Default release duration in seconds for recovering back to {@code 1.0f}. */
    public static final double DEFAULT_RELEASE_SECONDS = 0.45;

    private final float duckedGain;
    private final double attackSeconds;
    private final double releaseSeconds;

    private boolean voiceActive;
    private float duckMultiplier = 1.0f;

    /**
     * Creates an {@code AudioDuckingController} with default ducked gain and attack/release timings.
     */
    public AudioDuckingController() {
        this(DEFAULT_DUCKED_GAIN, DEFAULT_ATTACK_SECONDS, DEFAULT_RELEASE_SECONDS);
    }

    /**
     * Creates an {@code AudioDuckingController} with custom ducked gain and transition durations.
     *
     * @param duckedGain     target gain multiplier in {@code [0, 1]} while voice is active
     * @param attackSeconds  duration in seconds to transition from {@code 1.0f} down to {@code duckedGain} ({@code >= 0})
     * @param releaseSeconds duration in seconds to transition from {@code duckedGain} up to {@code 1.0f} ({@code >= 0})
     * @throws IllegalArgumentException if any parameter is non-finite or out of valid range
     */
    public AudioDuckingController(float duckedGain, double attackSeconds, double releaseSeconds) {
        if (!Float.isFinite(duckedGain) || duckedGain < 0.0f || duckedGain > 1.0f) {
            throw new IllegalArgumentException("duckedGain must be finite and in [0, 1], got: " + duckedGain);
        }
        validateDuration(attackSeconds, "attackSeconds");
        validateDuration(releaseSeconds, "releaseSeconds");

        this.duckedGain = duckedGain;
        this.attackSeconds = attackSeconds;
        this.releaseSeconds = releaseSeconds;
    }

    /**
     * Sets whether voice playback is currently active.
     *
     * @param active {@code true} to duck toward {@link #getDuckedGain()}, {@code false} to release toward {@code 1.0f}
     */
    public synchronized void setVoiceActive(boolean active) {
        this.voiceActive = active;
    }

    /**
     * Returns whether voice playback is currently marked active.
     *
     * @return {@code true} if voice is active
     */
    public synchronized boolean isVoiceActive() {
        return voiceActive;
    }

    /**
     * Advances the ducking envelope by {@code deltaSeconds}.
     *
     * @param deltaSeconds elapsed frame time in seconds ({@code >= 0})
     * @throws IllegalArgumentException if {@code deltaSeconds} is non-finite or negative
     */
    public synchronized void update(double deltaSeconds) {
        validateDuration(deltaSeconds, "deltaSeconds");

        float target = voiceActive ? duckedGain : 1.0f;
        if (Float.compare(duckMultiplier, target) == 0) {
            return;
        }

        double transitionSeconds = (target < duckMultiplier) ? attackSeconds : releaseSeconds;
        if (transitionSeconds <= 0.0) {
            duckMultiplier = target;
            return;
        }

        if (deltaSeconds <= 0.0) {
            return;
        }

        double span = 1.0 - duckedGain;
        if (span <= 0.0) {
            duckMultiplier = target;
            return;
        }

        float maxStep = (float) ((deltaSeconds / transitionSeconds) * span);
        if (target < duckMultiplier) {
            duckMultiplier = Math.max(target, duckMultiplier - maxStep);
        } else {
            duckMultiplier = Math.min(target, duckMultiplier + maxStep);
        }
        duckMultiplier = Math.clamp(duckMultiplier, duckedGain, 1.0f);
    }

    /**
     * Returns the current smoothed ducking multiplier in {@code [duckedGain, 1.0f]}.
     *
     * @return current ducking gain multiplier
     */
    public synchronized float getDuckMultiplier() {
        return duckMultiplier;
    }

    /**
     * Applies the current ducking multiplier to {@code baseGain}.
     *
     * @param baseGain non-negative finite channel or track gain
     * @return attenuated gain {@code baseGain * getDuckMultiplier()}
     * @throws IllegalArgumentException if {@code baseGain} is non-finite or negative
     */
    public synchronized float applyToGain(float baseGain) {
        if (!Float.isFinite(baseGain) || baseGain < 0.0f) {
            throw new IllegalArgumentException("baseGain must be finite and >= 0, got: " + baseGain);
        }
        return baseGain * duckMultiplier;
    }

    /**
     * Returns the configured target gain multiplier while voice is active.
     *
     * @return ducked gain in {@code [0, 1]}
     */
    public float getDuckedGain() {
        return duckedGain;
    }

    /**
     * Returns the configured attack transition duration in seconds.
     *
     * @return attack duration in seconds
     */
    public double getAttackSeconds() {
        return attackSeconds;
    }

    /**
     * Returns the configured release transition duration in seconds.
     *
     * @return release duration in seconds
     */
    public double getReleaseSeconds() {
        return releaseSeconds;
    }

    private static void validateDuration(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and >= 0, got: " + value);
        }
    }
}
