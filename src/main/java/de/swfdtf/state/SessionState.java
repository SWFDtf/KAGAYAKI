package de.swfdtf.state;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Tier 1 transient runtime session state (never written directly to save files as-is).
 *
 * <p>Tracks the current play session start timestamp, accumulated active playtime in seconds,
 * active save slot identifier, most recent auto-save timestamp, and transient boolean flags.</p>
 */
public final class SessionState {

    private Instant sessionStartTime;
    private double playtimeSeconds;
    private String activeSaveSlotId;
    private Instant lastAutoSaveTime;
    private final Map<String, Boolean> sessionFlags = new LinkedHashMap<>();

    /**
     * Creates a new {@code SessionState} starting at {@link Instant#now()} with zero playtime.
     */
    public SessionState() {
        this.sessionStartTime = Instant.now();
        this.playtimeSeconds = 0.0;
    }

    /**
     * Advances accumulated playtime by {@code dt} seconds when {@code dt} is finite and positive.
     *
     * @param dt elapsed frame delta in seconds
     */
    public synchronized void updatePlaytime(double dt) {
        if (dt > 0.0 && Double.isFinite(dt)) {
            this.playtimeSeconds += dt;
        }
    }

    /**
     * Sets or clears a transient boolean session flag.
     *
     * @param key   flag identifier (must not be {@code null})
     * @param value boolean flag value
     */
    public synchronized void setFlag(String key, boolean value) {
        Objects.requireNonNull(key, "key must not be null");
        sessionFlags.put(key, value);
    }

    /**
     * Returns the boolean value of {@code key}, or {@code false} if unset.
     *
     * @param key flag identifier (must not be {@code null})
     * @return flag value or {@code false}
     */
    public synchronized boolean getFlag(String key) {
        return getFlag(key, false);
    }

    /**
     * Returns the boolean value of {@code key}, or {@code defaultValue} if unset.
     *
     * @param key          flag identifier (must not be {@code null})
     * @param defaultValue fallback value
     * @return stored flag value or {@code defaultValue}
     */
    public synchronized boolean getFlag(String key, boolean defaultValue) {
        Objects.requireNonNull(key, "key must not be null");
        Boolean val = sessionFlags.get(key);
        return val != null ? val : defaultValue;
    }

    /**
     * Returns whether {@code key} is present in session flags.
     *
     * @param key flag identifier (must not be {@code null})
     * @return {@code true} if set
     */
    public synchronized boolean hasFlag(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return sessionFlags.containsKey(key);
    }

    /**
     * Removes a single transient flag if present.
     *
     * @param key flag identifier (must not be {@code null})
     * @return previous flag value or {@code null}
     */
    public synchronized Boolean removeFlag(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return sessionFlags.remove(key);
    }

    /**
     * Returns an immutable snapshot of all transient session flags.
     *
     * @return unmodifiable copy of session flags
     */
    public synchronized Map<String, Boolean> getSessionFlags() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(sessionFlags));
    }

    /** @return timestamp when the current session started or was reset */
    public synchronized Instant getSessionStartTime() {
        return sessionStartTime;
    }

    /**
     * Sets the session start timestamp.
     *
     * @param sessionStartTime session start timestamp (must not be {@code null})
     */
    public synchronized void setSessionStartTime(Instant sessionStartTime) {
        this.sessionStartTime = Objects.requireNonNull(sessionStartTime, "sessionStartTime must not be null");
    }

    /** @return accumulated playtime in seconds */
    public synchronized double getPlaytimeSeconds() {
        return playtimeSeconds;
    }

    /**
     * Sets accumulated playtime in seconds (for example when loading a save slot).
     *
     * @param playtimeSeconds playtime in seconds ({@code >= 0} and finite)
     */
    public synchronized void setPlaytimeSeconds(double playtimeSeconds) {
        if (!Double.isFinite(playtimeSeconds) || playtimeSeconds < 0.0) {
            throw new IllegalArgumentException("playtimeSeconds must be finite and >= 0, got " + playtimeSeconds);
        }
        this.playtimeSeconds = playtimeSeconds;
    }

    /** @return identifier of the currently active save slot, or {@code null} if unsaved */
    public synchronized String getActiveSaveSlotId() {
        return activeSaveSlotId;
    }

    /**
     * Updates the currently active save slot identifier.
     *
     * @param activeSaveSlotId save slot identifier (nullable)
     */
    public synchronized void setActiveSaveSlotId(String activeSaveSlotId) {
        this.activeSaveSlotId = activeSaveSlotId;
    }

    /** @return timestamp of the most recent auto-save, or {@code null} if none in this session */
    public synchronized Instant getLastAutoSaveTime() {
        return lastAutoSaveTime;
    }

    /**
     * Updates the most recent auto-save timestamp.
     *
     * @param lastAutoSaveTime timestamp of last auto-save (nullable)
     */
    public synchronized void setLastAutoSaveTime(Instant lastAutoSaveTime) {
        this.lastAutoSaveTime = lastAutoSaveTime;
    }

    /**
     * Records {@link Instant#now()} as the most recent auto-save time.
     */
    public synchronized void markAutoSaved() {
        this.lastAutoSaveTime = Instant.now();
    }

    /**
     * Resets all session timers, active slot reference, and flags for a fresh game session.
     */
    public synchronized void reset() {
        this.sessionStartTime = Instant.now();
        this.playtimeSeconds = 0.0;
        this.activeSaveSlotId = null;
        this.lastAutoSaveTime = null;
        this.sessionFlags.clear();
    }
}
