package de.swfdtf.graphics;

import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Easing;

import java.util.Objects;
import java.util.Optional;

/**
 * Controller for atmospheric weather overlays, wind velocities, color-grading tint matrices,
 * and optional headless-safe synchronization with a {@link ParticleSystem}.
 *
 * <p>Supports smooth frame-rate-independent transitions between {@link WeatherPreset} states
 * and intensity levels in {@code [0.0f, 1.0f]}.</p>
 */
public final class WeatherOverlayController {

    private static final int DEFAULT_RAIN_PARTICLES = 140;
    private static final float DEFAULT_RAIN_SPEED = 920.0f;
    private static final int DEFAULT_SNOW_PARTICLES = 90;
    private static final float DEFAULT_SNOW_SPEED = 115.0f;
    private static final int DEFAULT_FOG_PARTICLES = 45;
    private static final int DEFAULT_EMBER_PARTICLES = 60;

    /**
     * Built-in atmospheric weather presets.
     */
    public enum WeatherPreset {
        /** Clear atmosphere with neutral tint and no active weather particles. */
        CLEAR,
        /** Cool overcast rain streaks with desaturated slate tint. */
        RAIN,
        /** Gentle drifting snow with crisp cool-white tint. */
        SNOW,
        /** Dense hazy mist with lifted brightness, reduced contrast, and floating motes. */
        FOG,
        /** Warm glowing rising embers with amber-orange color grading. */
        EMBERS
    }

    private WeatherPreset currentPreset = WeatherPreset.CLEAR;
    private float currentIntensity = 0.0f;
    private float startIntensity = 0.0f;
    private float targetIntensity = 0.0f;

    private ColorMatrix4f currentTintMatrix = ColorMatrix4f.identity();
    private ColorMatrix4f startTintMatrix = ColorMatrix4f.identity();
    private ColorMatrix4f targetTintMatrix = ColorMatrix4f.identity();

    private double transitionDurationSeconds = 0.0;
    private double transitionElapsedSeconds = 0.0;
    private boolean transitioning = false;

    private float windX = 0.0f;
    private float windY = 0.0f;

    private ParticleSystem attachedParticleSystem;

    /**
     * Creates a {@code WeatherOverlayController} initialized to {@link WeatherPreset#CLEAR}
     * with {@code 0.0f} intensity and zero wind.
     */
    public WeatherOverlayController() {
    }

    /**
     * Transitions the weather state to {@code preset} and {@code targetIntensity} over {@code transitionSeconds}.
     *
     * <p>When {@code transitionSeconds == 0.0}, the target state and tint matrix are applied immediately.
     * If a {@link ParticleSystem} is attached, its emitters are synchronized without requiring an OpenGL
     * context.</p>
     *
     * @param preset            target weather preset (must not be {@code null})
     * @param targetIntensity   target effect intensity in {@code [0.0f, 1.0f]}
     * @param transitionSeconds transition duration in seconds ({@code >= 0.0})
     * @throws NullPointerException     if {@code preset} is {@code null}
     * @throws IllegalArgumentException if {@code targetIntensity} is outside {@code [0.0f, 1.0f]} or non-finite,
     *                                  or if {@code transitionSeconds < 0.0} or non-finite
     */
    public void setWeather(WeatherPreset preset, float targetIntensity, double transitionSeconds) {
        Objects.requireNonNull(preset, "preset must not be null");
        validateUnitFloat(targetIntensity, "targetIntensity");
        validateNonNegativeDouble(transitionSeconds, "transitionSeconds");

        this.currentPreset = preset;
        this.targetIntensity = targetIntensity;

        ColorMatrix4f resolvedTargetTint = computePresetTint(preset, targetIntensity);
        if (transitionSeconds == 0.0) {
            this.currentIntensity = targetIntensity;
            this.startIntensity = targetIntensity;
            this.currentTintMatrix = resolvedTargetTint;
            this.startTintMatrix = resolvedTargetTint;
            this.targetTintMatrix = resolvedTargetTint;
            this.transitionDurationSeconds = 0.0;
            this.transitionElapsedSeconds = 0.0;
            this.transitioning = false;
        } else {
            this.startIntensity = this.currentIntensity;
            this.startTintMatrix = this.currentTintMatrix;
            this.targetTintMatrix = resolvedTargetTint;
            this.transitionDurationSeconds = transitionSeconds;
            this.transitionElapsedSeconds = 0.0;
            this.transitioning = true;
        }

        syncAttachedParticles();
    }

    /**
     * Sets the global atmospheric wind velocity in logical pixels per second.
     *
     * <p>If a {@link ParticleSystem} is attached, all emitters are updated with {@code windX}.</p>
     *
     * @param windX horizontal wind velocity in px/s (must be finite)
     * @param windY vertical wind velocity in px/s (must be finite)
     * @throws IllegalArgumentException if {@code windX} or {@code windY} is non-finite
     */
    public void setWind(float windX, float windY) {
        validateFiniteFloat(windX, "windX");
        validateFiniteFloat(windY, "windY");
        this.windX = windX;
        this.windY = windY;
        applyWindToParticles();
    }

    /**
     * Attaches a {@link ParticleSystem} to keep its emitters synchronized with the active weather preset
     * and wind velocity.
     *
     * @param particles particle system to attach (must not be {@code null})
     * @throws NullPointerException if {@code particles} is {@code null}
     */
    public void attachParticleSystem(ParticleSystem particles) {
        this.attachedParticleSystem = Objects.requireNonNull(particles, "particles must not be null");
        syncAttachedParticles();
    }

    /**
     * Detaches any currently attached {@link ParticleSystem}.
     */
    public void detachParticleSystem() {
        this.attachedParticleSystem = null;
    }

    /**
     * Advances active weather transitions and updates any attached {@link ParticleSystem}.
     *
     * @param deltaSeconds elapsed frame time in seconds ({@code >= 0.0})
     * @throws IllegalArgumentException if {@code deltaSeconds < 0.0} or non-finite
     */
    public void update(double deltaSeconds) {
        validateNonNegativeDouble(deltaSeconds, "deltaSeconds");

        if (transitioning && deltaSeconds > 0.0) {
            transitionElapsedSeconds = Math.min(transitionDurationSeconds, transitionElapsedSeconds + deltaSeconds);
            float linearT = (float) (transitionElapsedSeconds / transitionDurationSeconds);
            float easedT = Easing.smoothstep(linearT);

            currentIntensity = startIntensity + (targetIntensity - startIntensity) * easedT;
            currentTintMatrix = startTintMatrix.lerp(targetTintMatrix, easedT);

            if (transitionElapsedSeconds >= transitionDurationSeconds) {
                currentIntensity = targetIntensity;
                currentTintMatrix = targetTintMatrix;
                transitioning = false;
                if (currentPreset == WeatherPreset.CLEAR || currentIntensity <= 0.0f) {
                    syncAttachedParticles();
                }
            }
        }

        if (attachedParticleSystem != null && deltaSeconds > 0.0) {
            attachedParticleSystem.update(deltaSeconds);
        }
    }

    /**
     * Returns the currently active {@link WeatherPreset}.
     *
     * @return current weather preset
     */
    public WeatherPreset getCurrentPreset() {
        return currentPreset;
    }

    /**
     * Returns the current interpolated weather intensity in {@code [0.0f, 1.0f]}.
     *
     * @return current intensity
     */
    public float getCurrentIntensity() {
        return currentIntensity;
    }

    /**
     * Returns the target weather intensity in {@code [0.0f, 1.0f]}.
     *
     * @return target intensity
     */
    public float getTargetIntensity() {
        return targetIntensity;
    }

    /**
     * Returns the configured horizontal wind velocity in logical pixels per second.
     *
     * @return horizontal wind velocity
     */
    public float getWindX() {
        return windX;
    }

    /**
     * Returns the configured vertical wind velocity in logical pixels per second.
     *
     * @return vertical wind velocity
     */
    public float getWindY() {
        return windY;
    }

    /**
     * Returns whether a weather transition is currently in progress.
     *
     * @return {@code true} if transitioning toward a target intensity/tint
     */
    public boolean isTransitioning() {
        return transitioning;
    }

    /**
     * Computes the current 4x4 premultiplied-alpha color grading and fog tint matrix.
     *
     * @return current {@link ColorMatrix4f} tint matrix
     */
    public ColorMatrix4f computeTintMatrix() {
        return currentTintMatrix;
    }

    /**
     * Returns the attached {@link ParticleSystem}, if present.
     *
     * @return {@link Optional} containing the attached particle system, or empty if none is attached
     */
    public Optional<ParticleSystem> getAttachedParticleSystem() {
        return Optional.ofNullable(attachedParticleSystem);
    }

    private void syncAttachedParticles() {
        if (attachedParticleSystem == null) {
            return;
        }
        if (currentPreset == WeatherPreset.CLEAR || (targetIntensity <= 0.0f && !transitioning)) {
            attachedParticleSystem.clearEmitters();
            return;
        }
        if (targetIntensity <= 0.0f) {
            return;
        }

        ParticleSystem.EmitterKind desiredKind = mapPresetToEmitterKind(currentPreset);
        if (desiredKind == null) {
            attachedParticleSystem.clearEmitters();
            return;
        }

        boolean alreadyMatches = attachedParticleSystem.getEmitters().size() == 1
                && attachedParticleSystem.getEmitters().get(0).getKind() == desiredKind;
        if (alreadyMatches) {
            applyWindToParticles();
            return;
        }

        attachedParticleSystem.clearEmitters();
        ParticleSystem.ParticleEmitter emitter = createEmitterForPreset(currentPreset, targetIntensity);
        if (emitter != null) {
            emitter.setWindX(windX);
            attachedParticleSystem.addEmitter(emitter);
        }
    }

    private void applyWindToParticles() {
        if (attachedParticleSystem == null) {
            return;
        }
        for (ParticleSystem.ParticleEmitter emitter : attachedParticleSystem.getEmitters()) {
            emitter.setWindX(windX);
        }
    }

    private ParticleSystem.EmitterKind mapPresetToEmitterKind(WeatherPreset preset) {
        return switch (preset) {
            case CLEAR -> null;
            case RAIN -> ParticleSystem.EmitterKind.RAIN;
            case SNOW -> ParticleSystem.EmitterKind.SNOW_BLOSSOM;
            case FOG -> ParticleSystem.EmitterKind.DUST;
            case EMBERS -> ParticleSystem.EmitterKind.SPARKS;
        };
    }

    private ParticleSystem.ParticleEmitter createEmitterForPreset(WeatherPreset preset, float intensity) {
        float scale = Math.max(0.25f, intensity);
        return switch (preset) {
            case CLEAR -> null;
            case RAIN -> ParticleSystem.rain(Math.round(DEFAULT_RAIN_PARTICLES * scale), DEFAULT_RAIN_SPEED, windX);
            case SNOW -> ParticleSystem.snowBlossom(Math.round(DEFAULT_SNOW_PARTICLES * scale), DEFAULT_SNOW_SPEED, windX);
            case FOG -> ParticleSystem.dust(Math.round(DEFAULT_FOG_PARTICLES * scale));
            case EMBERS -> ParticleSystem.sparks(Math.round(DEFAULT_EMBER_PARTICLES * scale));
        };
    }

    private static ColorMatrix4f computePresetTint(WeatherPreset preset, float intensity) {
        if (preset == WeatherPreset.CLEAR || intensity <= 0.0f) {
            return ColorMatrix4f.identity();
        }
        ColorMatrix4f fullTint = switch (preset) {
            case CLEAR -> ColorMatrix4f.identity();
            case RAIN -> ColorMatrix4f.tint(0.78f, 0.85f, 0.98f)
                    .multiply(ColorMatrix4f.saturation(0.75f));
            case SNOW -> ColorMatrix4f.tint(0.92f, 0.96f, 1.0f)
                    .multiply(ColorMatrix4f.brightness(0.06f));
            case FOG -> ColorMatrix4f.brightness(0.14f)
                    .multiply(ColorMatrix4f.contrast(0.72f))
                    .multiply(ColorMatrix4f.saturation(0.45f))
                    .multiply(ColorMatrix4f.tint(0.90f, 0.93f, 0.97f));
            case EMBERS -> ColorMatrix4f.tint(1.08f, 0.86f, 0.72f)
                    .multiply(ColorMatrix4f.saturation(1.15f));
        };
        if (intensity >= 1.0f) {
            return fullTint;
        }
        return ColorMatrix4f.identity().lerp(fullTint, intensity);
    }

    private static void validateUnitFloat(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be finite and in [0.0, 1.0], was: " + value);
        }
    }

    private static void validateFiniteFloat(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite, was: " + value);
        }
    }

    private static void validateNonNegativeDouble(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and >= 0.0, was: " + value);
        }
    }
}
