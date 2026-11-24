package de.swfdtf.audio;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Manages named ambient audio layers and smoothly crossfades their normalized mix weights
 * toward target weights or scene presets over a configurable fade duration.
 */
public final class AmbientCrossfadeMixer {

    /** Default crossfade duration in seconds. */
    public static final double DEFAULT_FADE_DURATION_SECONDS = 1.0;

    private static final class LayerState {
        float currentWeight;
        float startWeight;
        float targetWeight;
        double fadeDurationSeconds;
        double fadeElapsedSeconds;
        boolean removeWhenZero;
    }

    private final Map<String, LayerState> layers = new LinkedHashMap<>();
    private double fadeDurationSeconds;

    /**
     * Creates an {@code AmbientCrossfadeMixer} with {@link #DEFAULT_FADE_DURATION_SECONDS}.
     */
    public AmbientCrossfadeMixer() {
        this(DEFAULT_FADE_DURATION_SECONDS);
    }

    /**
     * Creates an {@code AmbientCrossfadeMixer} with a custom fade duration.
     *
     * @param fadeDurationSeconds crossfade duration in seconds ({@code >= 0})
     * @throws IllegalArgumentException if {@code fadeDurationSeconds} is non-finite or negative
     */
    public AmbientCrossfadeMixer(double fadeDurationSeconds) {
        validateDuration(fadeDurationSeconds, "fadeDurationSeconds");
        this.fadeDurationSeconds = fadeDurationSeconds;
    }

    /**
     * Adds a new ambient layer (starting at weight {@code 0.0f}) or updates an existing layer's
     * target weight, initiating a smooth transition over {@link #getFadeDurationSeconds()}.
     *
     * @param layerId      non-blank layer identifier (must not be {@code null})
     * @param targetWeight target mix weight in {@code [0, 1]}
     * @throws IllegalArgumentException if {@code layerId} is blank or {@code targetWeight} is out of {@code [0, 1]}
     */
    public synchronized void addOrSetTarget(String layerId, float targetWeight) {
        String validId = requireNonBlank(layerId, "layerId");
        validateWeight(targetWeight, "targetWeight");
        upsertLayerTarget(validId, targetWeight, false);
    }

    /**
     * Immediately removes an ambient layer from this mixer if present.
     *
     * @param layerId non-blank layer identifier (must not be {@code null})
     * @throws IllegalArgumentException if {@code layerId} is blank
     */
    public synchronized void removeLayer(String layerId) {
        String validId = requireNonBlank(layerId, "layerId");
        layers.remove(validId);
    }

    /**
     * Crossfades all active ambient layers to match {@code layerWeights}.
     *
     * <p>Layers present in {@code layerWeights} fade toward their specified weights, while existing
     * layers omitted from the preset fade down to {@code 0.0f} and are pruned once silent.</p>
     *
     * @param layerWeights map of layer identifier to target weight in {@code [0, 1]} (must not be {@code null})
     * @throws IllegalArgumentException if any key is blank or any weight is null, non-finite, or outside {@code [0, 1]}
     */
    public synchronized void applyPreset(Map<String, Float> layerWeights) {
        Objects.requireNonNull(layerWeights, "layerWeights must not be null");

        Map<String, Float> validated = new LinkedHashMap<>();
        for (Map.Entry<String, Float> entry : layerWeights.entrySet()) {
            String validId = requireNonBlank(entry.getKey(), "layerId");
            Float weight = entry.getValue();
            if (weight == null) {
                throw new IllegalArgumentException("Weight for layer '" + validId + "' must not be null");
            }
            validateWeight(weight, "weight for layer '" + validId + "'");
            validated.put(validId, weight);
        }

        Iterator<Map.Entry<String, LayerState>> it = layers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, LayerState> entry = it.next();
            if (!validated.containsKey(entry.getKey())) {
                LayerState state = entry.getValue();
                if (state.currentWeight <= 0.0f) {
                    it.remove();
                } else {
                    configureFade(state, 0.0f, true);
                }
            }
        }

        for (Map.Entry<String, Float> entry : validated.entrySet()) {
            upsertLayerTarget(entry.getKey(), entry.getValue(), false);
        }
    }

    /**
     * Advances all active layer crossfades by {@code deltaSeconds}.
     *
     * @param deltaSeconds elapsed frame time in seconds ({@code >= 0})
     * @throws IllegalArgumentException if {@code deltaSeconds} is non-finite or negative
     */
    public synchronized void update(double deltaSeconds) {
        validateDuration(deltaSeconds, "deltaSeconds");

        Iterator<Map.Entry<String, LayerState>> it = layers.entrySet().iterator();
        while (it.hasNext()) {
            LayerState state = it.next().getValue();
            if (state.fadeDurationSeconds <= 0.0) {
                state.currentWeight = state.targetWeight;
            } else if (deltaSeconds > 0.0) {
                state.fadeElapsedSeconds = Math.min(
                        state.fadeDurationSeconds,
                        state.fadeElapsedSeconds + deltaSeconds
                );
                float t = (float) (state.fadeElapsedSeconds / state.fadeDurationSeconds);
                state.currentWeight = Math.clamp(
                        state.startWeight + (state.targetWeight - state.startWeight) * t,
                        0.0f,
                        1.0f
                );
                if (state.fadeElapsedSeconds >= state.fadeDurationSeconds) {
                    state.currentWeight = state.targetWeight;
                    state.fadeDurationSeconds = 0.0;
                }
            }

            if (state.removeWhenZero && state.currentWeight <= 0.0f && state.targetWeight <= 0.0f) {
                it.remove();
            }
        }
    }

    /**
     * Returns an unmodifiable snapshot map of all managed layers and their current mix weights.
     *
     * @return unmodifiable map of layer identifier to current weight in {@code [0, 1]}
     */
    public synchronized Map<String, Float> getActiveWeights() {
        Map<String, Float> weights = new LinkedHashMap<>(layers.size());
        for (Map.Entry<String, LayerState> entry : layers.entrySet()) {
            weights.put(entry.getKey(), entry.getValue().currentWeight);
        }
        return Collections.unmodifiableMap(weights);
    }

    /**
     * Returns the current mix weight for {@code layerId}, if managed by this mixer.
     *
     * @param layerId non-blank layer identifier (must not be {@code null})
     * @return {@link Optional} containing the current weight, or {@link Optional#empty()} if absent
     * @throws IllegalArgumentException if {@code layerId} is blank
     */
    public synchronized Optional<Float> getWeight(String layerId) {
        String validId = requireNonBlank(layerId, "layerId");
        LayerState state = layers.get(validId);
        return state != null ? Optional.of(state.currentWeight) : Optional.empty();
    }

    /**
     * Removes all layers immediately.
     */
    public synchronized void clear() {
        layers.clear();
    }

    /**
     * Returns the configured fade duration in seconds for newly triggered transitions.
     *
     * @return fade duration in seconds
     */
    public synchronized double getFadeDurationSeconds() {
        return fadeDurationSeconds;
    }

    /**
     * Updates the fade duration in seconds applied to subsequent transitions.
     *
     * @param fadeDurationSeconds fade duration in seconds ({@code >= 0})
     * @throws IllegalArgumentException if {@code fadeDurationSeconds} is non-finite or negative
     */
    public synchronized void setFadeDurationSeconds(double fadeDurationSeconds) {
        validateDuration(fadeDurationSeconds, "fadeDurationSeconds");
        this.fadeDurationSeconds = fadeDurationSeconds;
    }

    private void upsertLayerTarget(String layerId, float targetWeight, boolean removeWhenZero) {
        LayerState state = layers.get(layerId);
        if (state == null) {
            state = new LayerState();
            state.currentWeight = 0.0f;
            layers.put(layerId, state);
        }
        configureFade(state, targetWeight, removeWhenZero);
    }

    private void configureFade(LayerState state, float targetWeight, boolean removeWhenZero) {
        state.startWeight = state.currentWeight;
        state.targetWeight = targetWeight;
        state.fadeElapsedSeconds = 0.0;
        state.removeWhenZero = removeWhenZero;

        if (Float.compare(state.currentWeight, targetWeight) == 0 || fadeDurationSeconds <= 0.0) {
            state.fadeDurationSeconds = 0.0;
            if (fadeDurationSeconds <= 0.0) {
                state.currentWeight = targetWeight;
            }
        } else {
            state.fadeDurationSeconds = fadeDurationSeconds;
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }

    private static void validateWeight(float weight, String name) {
        if (!Float.isFinite(weight) || weight < 0.0f || weight > 1.0f) {
            throw new IllegalArgumentException(name + " must be finite and in [0, 1], got: " + weight);
        }
    }

    private static void validateDuration(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and >= 0, got: " + value);
        }
    }
}
