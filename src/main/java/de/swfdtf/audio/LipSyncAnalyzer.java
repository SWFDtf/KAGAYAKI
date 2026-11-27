package de.swfdtf.audio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        /** Mouth closed (below half-open threshold). */
        CLOSED,
        /** Mouth partially open. */
        HALF_OPEN,
        /** Mouth fully open. */
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

    /**
     * Returns the current discrete mouth shape.
     *
     * @return current discrete {@link MouthShape}
     */
    public MouthShape getMouthShape() {
        return mouthShape;
    }

    /**
     * Returns the smoothed mouth openness.
     *
     * @return smoothed mouth openness in {@code [0, 1]}
     */
    public float getOpenness() {
        return openness;
    }

    /**
     * Returns the attack smoothing time constant.
     *
     * @return attack smoothing time constant in seconds
     */
    public double getAttackSeconds() {
        return attackSeconds;
    }

    /**
     * Sets the attack smoothing time constant.
     *
     * @param attackSeconds attack smoothing time constant in seconds ({@code >= 0})
     */
    public void setAttackSeconds(double attackSeconds) {
        validateTimeConstant(attackSeconds, "attackSeconds");
        this.attackSeconds = attackSeconds;
    }

    /**
     * Returns the release smoothing time constant.
     *
     * @return release smoothing time constant in seconds
     */
    public double getReleaseSeconds() {
        return releaseSeconds;
    }

    /**
     * Sets the release smoothing time constant.
     *
     * @param releaseSeconds release smoothing time constant in seconds ({@code >= 0})
     */
    public void setReleaseSeconds(double releaseSeconds) {
        validateTimeConstant(releaseSeconds, "releaseSeconds");
        this.releaseSeconds = releaseSeconds;
    }

    /**
     * Returns the noise floor RMS gate.
     *
     * @return noise floor RMS gate
     */
    public float getNoiseFloorRms() {
        return noiseFloorRms;
    }

    /**
     * Sets the noise floor RMS gate.
     *
     * @param noiseFloorRms noise floor RMS gate ({@code >= 0} and {@code < maxRms})
     */
    public void setNoiseFloorRms(float noiseFloorRms) {
        validateRmsBounds(noiseFloorRms, this.maxRms);
        this.noiseFloorRms = noiseFloorRms;
    }

    /**
     * Returns the saturation RMS value.
     *
     * @return saturation RMS value mapped to {@code 1.0f} openness
     */
    public float getMaxRms() {
        return maxRms;
    }

    /**
     * Sets the saturation RMS value.
     *
     * @param maxRms saturation RMS value ({@code > noiseFloorRms})
     */
    public void setMaxRms(float maxRms) {
        validateRmsBounds(this.noiseFloorRms, maxRms);
        this.maxRms = maxRms;
    }

    /**
     * Returns the openness threshold for {@link MouthShape#HALF_OPEN}.
     *
     * @return openness threshold for {@link MouthShape#HALF_OPEN}
     */
    public float getHalfOpenThreshold() {
        return halfOpenThreshold;
    }

    /**
     * Sets the openness threshold for {@link MouthShape#HALF_OPEN}.
     *
     * @param halfOpenThreshold threshold in {@code [0, openThreshold]}
     */
    public void setHalfOpenThreshold(float halfOpenThreshold) {
        validateThresholds(halfOpenThreshold, this.openThreshold);
        this.halfOpenThreshold = halfOpenThreshold;
        this.mouthShape = classifyShape(this.openness);
    }

    /**
     * Returns the openness threshold for {@link MouthShape#OPEN}.
     *
     * @return openness threshold for {@link MouthShape#OPEN}
     */
    public float getOpenThreshold() {
        return openThreshold;
    }

    /**
     * Sets the openness threshold for {@link MouthShape#OPEN}.
     *
     * @param openThreshold threshold in {@code [halfOpenThreshold, 1]}
     */
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

    /**
     * Offline pre-baked {@code *.lipsync.json} envelope sequence ({@code #0474}).
     *
     * @param fps    sample rate of mouth frames per second
     * @param frames ordered mouth shape sequence
     */
    public record BakedLipSync(int fps, List<MouthShape> frames) {
        /** Validates positive frame rate and immutable frame list. */
        public BakedLipSync {
            if (fps <= 0) {
                throw new IllegalArgumentException("fps must be > 0");
            }
            frames = frames != null ? List.copyOf(frames) : List.of();
        }

        /**
         * Resolves mouth shape at playback timestamp {@code seconds}.
         *
         * @param seconds voice playback position in seconds
         * @return baked {@link MouthShape} or {@link MouthShape#CLOSED} when out of range
         */
        public MouthShape sampleAt(double seconds) {
            if (frames.isEmpty() || seconds < 0.0) {
                return MouthShape.CLOSED;
            }
            int idx = (int) Math.floor(seconds * fps);
            return idx < frames.size() ? frames.get(idx) : MouthShape.CLOSED;
        }
    }

    /**
     * Computes normalized Zero-Crossing Rate in {@code [0, 1]} to help distinguish consonants from
     * vowels alongside RMS envelope smoothing ({@code #0439}).
     *
     * @param samples mono float sample window
     * @return zero-crossing rate in {@code [0, 1]}
     */
    public static float computeZeroCrossingRate(float[] samples) {
        Objects.requireNonNull(samples, "samples must not be null");
        if (samples.length < 2) {
            return 0.0f;
        }
        int crossings = 0;
        for (int i = 1; i < samples.length; i++) {
            if ((samples[i - 1] < 0.0f && samples[i] >= 0.0f)
                    || (samples[i - 1] >= 0.0f && samples[i] < 0.0f)) {
                crossings++;
            }
        }
        return (float) crossings / (samples.length - 1);
    }

    /**
     * Pre-bakes discrete {@link MouthShape} frames for an entire decoded voice track ({@code #0474}).
     *
     * @param audio decoded voice audio
     * @param fps   target envelope frame rate (e.g., {@code 30})
     * @return baked lip-sync timeline
     */
    public static BakedLipSync bakeLipSync(AudioDecoder.DecodedAudio audio, int fps) {
        Objects.requireNonNull(audio, "audio must not be null");
        if (fps <= 0) {
            throw new IllegalArgumentException("fps must be > 0");
        }
        float[] mono = audio.toMonoFloat();
        int stepSamples = Math.max(1, audio.sampleRate() / fps);
        double dt = 1.0 / fps;
        LipSyncAnalyzer analyzer = new LipSyncAnalyzer();
        List<MouthShape> frames = new ArrayList<>();
        for (int i = 0; i < mono.length; i += stepSamples) {
            int len = Math.min(stepSamples, mono.length - i);
            float[] window = new float[len];
            System.arraycopy(mono, i, window, 0, len);
            float rms = AudioDsp.computeRms(window);
            float zcr = computeZeroCrossingRate(window);
            // Boost consonant transitions slightly via mild ZCR weighting when audible
            float weightedRms = rms > analyzer.getNoiseFloorRms() ? rms * (1.0f + zcr * 0.25f) : rms;
            frames.add(analyzer.updateFromRms(weightedRms, dt));
        }
        return new BakedLipSync(fps, frames);
    }

    /**
     * Serializes a {@link BakedLipSync} timeline to JSON ({@code #0474}).
     *
     * @param baked baked lip-sync sequence
     * @return formatted JSON string
     */
    public static String toJson(BakedLipSync baked) {
        Objects.requireNonNull(baked, "baked must not be null");
        StringBuilder sb = new StringBuilder("{\"fps\":").append(baked.fps()).append(",\"frames\":[");
        for (int i = 0; i < baked.frames().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(baked.frames().get(i).name()).append('"');
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * Parses a serialized {@link BakedLipSync} JSON string ({@code #0474}).
     *
     * @param json JSON string
     * @return parsed {@link BakedLipSync}
     */
    public static BakedLipSync fromJson(String json) {
        Objects.requireNonNull(json, "json must not be null");
        Matcher fpsM = Pattern.compile("\"fps\"\\s*:\\s*(\\d+)").matcher(json);
        int fps = fpsM.find() ? Integer.parseInt(fpsM.group(1)) : 30;
        List<MouthShape> shapes = new ArrayList<>();
        Matcher shapeM = Pattern.compile("\"(CLOSED|HALF_OPEN|OPEN)\"").matcher(json);
        while (shapeM.find()) {
            shapes.add(MouthShape.valueOf(shapeM.group(1)));
        }
        return new BakedLipSync(fps, shapes);
    }

    /**
     * Loads a pre-baked {@code *.lipsync.json} file if present, falling back cleanly when absent ({@code #0474}).
     *
     * @param jsonPath optional baked file path
     * @param fallbackAudio decoded audio used for real-time baking fallback if file missing
     * @return loaded or freshly computed {@link BakedLipSync}
     * @throws IOException if file exists but is unreadable
     */
    public static BakedLipSync loadOrFallback(Path jsonPath, AudioDecoder.DecodedAudio fallbackAudio)
            throws IOException {
        if (jsonPath != null && Files.isRegularFile(jsonPath)) {
            return fromJson(Files.readString(jsonPath, StandardCharsets.UTF_8));
        }
        return bakeLipSync(fallbackAudio, 30);
    }
}
