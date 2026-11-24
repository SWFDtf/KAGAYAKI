package de.swfdtf.audio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-safe registry of logical {@link AudioCue} definitions supporting lookup, channel filtering,
 * immutable snapshots, and Gson JSON serialization/deserialization.
 */
public final class AudioCueRegistry {

    private static final int FORMAT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Immutable metadata definition for a named audio cue.
     *
     * @param id            unique cue identifier (non-blank)
     * @param assetPath     logical or relative audio asset path (non-blank)
     * @param channel       target mixer channel name (non-blank)
     * @param defaultVolume default playback volume in {@code [0, 1]}
     * @param loop          whether playback loops continuously
     * @param pitchJitter   maximum random pitch deviation factor ({@code >= 0})
     */
    public record AudioCue(
            String id,
            String assetPath,
            String channel,
            float defaultVolume,
            boolean loop,
            float pitchJitter
    ) {
        /**
         * Validates and normalizes cue fields.
         */
        public AudioCue {
            id = requireNonBlank(id, "id");
            assetPath = requireNonBlank(assetPath, "assetPath");
            channel = requireNonBlank(channel, "channel");
            if (!Float.isFinite(defaultVolume) || defaultVolume < 0.0f || defaultVolume > 1.0f) {
                throw new IllegalArgumentException(
                        "defaultVolume must be finite and in [0, 1], got: " + defaultVolume
                );
            }
            if (!Float.isFinite(pitchJitter) || pitchJitter < 0.0f) {
                throw new IllegalArgumentException(
                        "pitchJitter must be finite and >= 0, got: " + pitchJitter
                );
            }
        }
    }

    private record CueRegistryPayload(int version, List<AudioCue> cues) {
    }

    private final Map<String, AudioCue> cues = new LinkedHashMap<>();

    /**
     * Creates an empty {@code AudioCueRegistry}.
     */
    public AudioCueRegistry() {
    }

    /**
     * Registers or replaces an {@link AudioCue} under its {@link AudioCue#id()}.
     *
     * @param cue cue definition to register (must not be {@code null})
     */
    public synchronized void register(AudioCue cue) {
        Objects.requireNonNull(cue, "cue must not be null");
        cues.put(cue.id(), cue);
    }

    /**
     * Unregisters the cue identified by {@code id}, if present.
     *
     * @param id cue identifier (must not be {@code null} or blank)
     * @return {@code true} if a cue was removed, {@code false} if no cue matched {@code id}
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public synchronized boolean unregister(String id) {
        String validId = requireNonBlank(id, "id");
        return cues.remove(validId) != null;
    }

    /**
     * Looks up a registered cue by {@code id}.
     *
     * @param id cue identifier (must not be {@code null} or blank)
     * @return {@link Optional} containing the cue if found, or {@link Optional#empty()}
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public synchronized Optional<AudioCue> findCue(String id) {
        String validId = requireNonBlank(id, "id");
        return Optional.ofNullable(cues.get(validId));
    }

    /**
     * Lists all registered cues targeting {@code channel} (case-insensitive) in registration order.
     *
     * @param channel target channel name (must not be {@code null} or blank)
     * @return unmodifiable list of matching {@link AudioCue} entries
     * @throws IllegalArgumentException if {@code channel} is blank
     */
    public synchronized List<AudioCue> listByChannel(String channel) {
        String validChannel = requireNonBlank(channel, "channel");
        List<AudioCue> matches = new ArrayList<>();
        for (AudioCue cue : cues.values()) {
            if (cue.channel().equalsIgnoreCase(validChannel)) {
                matches.add(cue);
            }
        }
        return List.copyOf(matches);
    }

    /**
     * Returns an unmodifiable snapshot map of all registered cues keyed by cue ID.
     *
     * @return unmodifiable map of cue ID to {@link AudioCue}
     */
    public synchronized Map<String, AudioCue> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(cues));
    }

    /**
     * Serializes all registered cues to formatted JSON using Gson.
     *
     * @return JSON representation of this registry
     */
    public synchronized String toJson() {
        CueRegistryPayload payload = new CueRegistryPayload(FORMAT_VERSION, new ArrayList<>(cues.values()));
        return GSON.toJson(payload);
    }

    /**
     * Replaces the contents of this registry by parsing cues from a JSON string produced by {@link #toJson()}.
     *
     * <p>Validation is atomic: if parsing or cue validation fails, existing registered cues remain unchanged.</p>
     *
     * @param json JSON payload string (must not be {@code null} or blank)
     * @throws IllegalArgumentException if {@code json} is blank, malformed, or contains invalid cues
     */
    public synchronized void loadJson(String json) {
        String validJson = requireNonBlank(json, "json");

        CueRegistryPayload payload;
        try {
            payload = GSON.fromJson(validJson, CueRegistryPayload.class);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Malformed AudioCueRegistry JSON: " + e.getMessage(), e);
        }

        if (payload == null || payload.cues() == null) {
            throw new IllegalArgumentException("Invalid AudioCueRegistry JSON: missing 'cues' array");
        }

        Map<String, AudioCue> candidate = new LinkedHashMap<>();
        for (AudioCue raw : payload.cues()) {
            if (raw == null) {
                throw new IllegalArgumentException("Invalid null AudioCue entry in JSON");
            }
            AudioCue validated = new AudioCue(
                    raw.id(),
                    raw.assetPath(),
                    raw.channel(),
                    raw.defaultVolume(),
                    raw.loop(),
                    raw.pitchJitter()
            );
            candidate.put(validated.id(), validated);
        }

        cues.clear();
        cues.putAll(candidate);
    }

    /**
     * Removes all registered cues from this registry.
     */
    public synchronized void clear() {
        cues.clear();
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }
}
