package de.swfdtf.audio;

/**
 * Envelope-following lip-sync analyzer converting real-time voice RMS amplitude into smoothed
 * mouth openness in {@code [0, 1]} and discrete {@link MouthShape} states for character sprite
 * animation.
 */
public final class LipSyncAnalyzer {

    /** Default attack smoothing time constant in seconds (fast mouth opening). */
    public static final double DEFAULT_ATTACK_SECONDS = 0.025;

    /** Default release smoothing time constant in seconds (smooth mouth closing). */
    public static final double DEFAULT_RELEASE_SECONDS = 0.12;

    /** Default RMS noise gate below which target openness is {@code 0}. */
    public static final float DEFAULT_NOISE_FLOOR_RMS = 0.01f;

    /** Default RMS saturation level mapped to full {@code 1.0f} target openness. */
    public static final float DEFAULT_MAX_RMS = 0.50f;

    /** Default openness threshold separating {@link MouthShape#CLOSED} and {@link MouthShape#HALF_OPEN}. */
    public static final float DEFAULT_HALF_OPEN_THRESHOLD = 0.08f;

    /** Default openness threshold separating {@link MouthShape#HALF_OPEN} and {@link MouthShape#OPEN}. */
    public static final float DEFAULT_OPEN_THRESHOLD = 0.45f;

    /**
     * Discrete mouth animation state driven by smoothed voice openness.
     */
    public enum MouthShape {
        CLOSED,
        HALF_OPEN,
        OPEN
    }

    private double attackSeconds;
    private double releaseSeconds;
    private float noiseFloorRms;
    private float maxRms;
    private float halfOpenThreshold;
    private float openThreshold;

    private float openness = 0.0f;
    private MouthShape mouthShape = MouthShape.CLOSED;

    /**
     * Creates a {@code LipSyncAnalyzer} with blueprint-tuned attack/release smoothing and thresholds.
     */
    public LipSyncAnalyzer() {
        this(
                DEFAULT_ATTACK_SECONDS,
                DEFAULT_RELEASE_SECONDS,
                DEFAULT_NOISE_FLOOR_RMS,
                DEFAULT_MAX_RMS,
                DEFAULT_HALF_OPEN_THRESHOLD,
                DEFAULT_OPEN_THRESHOLD
        );
    }

    /**
     * Creates a {@code LipSyncAnalyzer} with custom attack/release time constants and shape thresholds.
     *
     * @param attackSeconds     attack time constant in seconds ({@code >= 0})
     * @param releaseSeconds    release time constant in seconds ({@code >= 0})
     * @param halfOpenThreshold openness threshold in {@code [0, 1]} for {@link MouthShape#HALF_OPEN}
     * @param openThreshold     openness threshold in {@code [halfOpenThreshold, 1]} for {@link MouthShape#OPEN}
     */
    public LipSyncAnalyzer(
            double attackSeconds,
            double releaseSeconds,
            float halfOpenThreshold,
            float openThreshold
    ) {
        this(
                attackSeconds,
                releaseSeconds,
                DEFAULT_NOISE_FLOOR_RMS,
                DEFAULT_MAX_RMS,
                halfOpenThreshold,
                openThreshold
        );
    }

    /**
     * Creates a fully customized {@code LipSyncAnalyzer}.
     *
     * @param attackSeconds     attack time constant in seconds ({@code >= 0})
     * @param releaseSeconds    release time constant in seconds ({@code >= 0})
     * @param noiseFloorRms     RMS noise floor ({@code >= 0})
     * @param maxRms            RMS value mapped to full {@code 1.0f} openness ({@code > noiseFloorRms})
     * @param halfOpenThreshold openness threshold in {@code [0, 1]} for {@link MouthShape#HALF_OPEN}
     * @param openThreshold     openness threshold in {@code [halfOpenThreshold, 1]} for {@link MouthShape#OPEN}
     */
    public LipSyncAnalyzer(
            double attackSeconds,
            double releaseSeconds,
            float noiseFloorRms,
            float maxRms,
            float halfOpenThreshold,
            float openThreshold
    ) {
        validateTimeConstant(attackSeconds, "attackSeconds");
        validateTimeConstant(releaseSeconds, "releaseSeconds");
        validateRmsBounds(noiseFloorRms, maxRms);
        validateThresholds(halfOpenThreshold, openThreshold);

        this.attackSeconds = attackSeconds;
        this.releaseSeconds = releaseSeconds;
        this.noiseFloorRms = noiseFloorRms;
        this.maxRms = maxRms;
        this.halfOpenThreshold = halfOpenThreshold;
        this.openThreshold = openThreshold;
    }

    /**
     * Samples the active voice RMS from {@code audioEngine} (or {@code 0} if {@code null})
     * and advances lip-sync smoothing by {@code dt} seconds.
     *
     * @param audioEngine audio engine providing {@link AudioEngine#getActiveVoiceRms()} (nullable)
     * @param dt          delta time in seconds ({@code >= 0})
     * @return updated {@link MouthShape}
     */
    public MouthShape update(AudioEngine audioEngine, double dt) {
        float rms = audioEngine != null ? audioEngine.getActiveVoiceRms() : 0.0f;
        return updateFromRms(rms, dt);
    }

    /**
     * Updates smoothed mouth openness and {@link MouthShape} directly from an RMS amplitude value.
     *
     * @param rms instantaneous voice RMS amplitude
     * @param dt  delta time in seconds ({@code >= 0})
     * @return updated {@link MouthShape}
     */
    public MouthShape updateFromRms(float rms, double dt) {
        if (!Double.isFinite(dt) || dt < 0.0) {
            throw new IllegalArgumentException("dt must be finite and >= 0, got: " + dt);
        }

        float safeRms = (Float.isFinite(rms) && rms > 0.0f) ? rms : 0.0f;
        float targetOpenness;
        if (safeRms <= noiseFloorRms) {
            targetOpenness = 0.0f;
        } else if (safeRms >= maxRms) {
            targetOpenness = 1.0f;
        } else {
            targetOpenness = (safeRms - noiseFloorRms) / (maxRms - noiseFloorRms);
        }

        if (dt > 0.0) {
            double tau = (targetOpenness > openness) ? attackSeconds : releaseSeconds;
            double alpha = (tau <= 0.0) ? 1.0 : (1.0 - Math.exp(-dt / tau));
            openness = Math.clamp((float) (openness + (targetOpenness - openness) * alpha), 0.0f, 1.0f);
        } else if (attackSeconds == 0.0 && targetOpenness > openness) {
            openness = targetOpenness;
        } else if (releaseSeconds == 0.0 && targetOpenness < openness) {
            openness = targetOpenness;
        }

        this.mouthShape = classifyShape(this.openness);
        return this.mouthShape;
    }

    /**
     * Resets smoothed openness to {@code 0.0f} and mouth shape to {@link MouthShape#CLOSED}.
     */
    public void reset() {
        this.openness = 0.0f;
        this.mouthShape = MouthShape.CLOSED;
    }

    /** @return current discrete {@link MouthShape} */
    public MouthShape getMouthShape() {
        return mouthShape;
    }

    /** @return smoothed mouth openness in {@code [0, 1]} */
    public float getOpenness() {
        return openness;
    }

    /** @return attack smoothing time constant in seconds */
    public double getAttackSeconds() {
        return attackSeconds;
    }

    /** @param attackSeconds attack smoothing time constant in seconds ({@code >= 0}) */
    public void setAttackSeconds(double attackSeconds) {
        validateTimeConstant(attackSeconds, "attackSeconds");
        this.attackSeconds = attackSeconds;
    }

    /** @return release smoothing time constant in seconds */
    public double getReleaseSeconds() {
        return releaseSeconds;
    }

    /** @param releaseSeconds release smoothing time constant in seconds ({@code >= 0}) */
    public void setReleaseSeconds(double releaseSeconds) {
        validateTimeConstant(releaseSeconds, "releaseSeconds");
        this.releaseSeconds = releaseSeconds;
    }

    /** @return noise floor RMS gate */
    public float getNoiseFloorRms() {
        return noiseFloorRms;
    }

    /** @param noiseFloorRms noise floor RMS gate ({@code >= 0} and {@code < maxRms}) */
    public void setNoiseFloorRms(float noiseFloorRms) {
        validateRmsBounds(noiseFloorRms, this.maxRms);
        this.noiseFloorRms = noiseFloorRms;
    }

    /** @return saturation RMS value mapped to {@code 1.0f} openness */
    public float getMaxRms() {
        return maxRms;
    }

    /** @param maxRms saturation RMS value ({@code > noiseFloorRms}) */
    public void setMaxRms(float maxRms) {
        validateRmsBounds(this.noiseFloorRms, maxRms);
        this.maxRms = maxRms;
    }

    /** @return openness threshold for {@link MouthShape#HALF_OPEN} */
    public float getHalfOpenThreshold() {
        return halfOpenThreshold;
    }

    /** @param halfOpenThreshold threshold in {@code [0, openThreshold]} */
    public void setHalfOpenThreshold(float halfOpenThreshold) {
        validateThresholds(halfOpenThreshold, this.openThreshold);
        this.halfOpenThreshold = halfOpenThreshold;
        this.mouthShape = classifyShape(this.openness);
    }

    /** @return openness threshold for {@link MouthShape#OPEN} */
    public float getOpenThreshold() {
        return openThreshold;
    }

    /** @param openThreshold threshold in {@code [halfOpenThreshold, 1]} */
    public void setOpenThreshold(float openThreshold) {
        validateThresholds(this.halfOpenThreshold, openThreshold);
        this.openThreshold = openThreshold;
        this.mouthShape = classifyShape(this.openness);
    }

    private MouthShape classifyShape(float value) {
        if (value < halfOpenThreshold) {
            return MouthShape.CLOSED;
        }
        if (value < openThreshold) {
            return MouthShape.HALF_OPEN;
        }
        return MouthShape.OPEN;
    }

    private static void validateTimeConstant(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and >= 0, got: " + value);
        }
    }

    private static void validateRmsBounds(float noiseFloor, float max) {
        if (!Float.isFinite(noiseFloor) || noiseFloor < 0.0f) {
            throw new IllegalArgumentException("noiseFloorRms must be finite and >= 0, got: " + noiseFloor);
        }
        if (!Float.isFinite(max) || max <= noiseFloor) {
            throw new IllegalArgumentException(
                    "maxRms must be finite and > noiseFloorRms (" + noiseFloor + "), got: " + max
            );
        }
    }

    private static void validateThresholds(float halfOpen, float open) {
        if (!Float.isFinite(halfOpen) || halfOpen < 0.0f || halfOpen > 1.0f) {
            throw new IllegalArgumentException("halfOpenThreshold must be in [0, 1], got: " + halfOpen);
        }
        if (!Float.isFinite(open) || open < halfOpen || open > 1.0f) {
            throw new IllegalArgumentException(
                    "openThreshold must be in [halfOpenThreshold (" + halfOpen + "), 1], got: " + open
            );
        }
    }
}
