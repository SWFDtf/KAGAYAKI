package de.swfdtf.graphics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Debug OpenGL resource lifecycle tracker and GPU pass timer auditor.
 *
 * <p>Records allocated VAO, VBO, FBO, Texture, and Shader handles to guarantee zero leaks after shutdown,
 * and stores per-pass timing measurements with safe {@code 0.0 ms} headless fallback.</p>
 */
public final class GlResourceAuditor {

    /**
     * OpenGL resource categories tracked by {@link GlResourceAuditor}.
     */
    public enum ResourceKind {
        /** Vertex array object. */
        VAO,
        /** Vertex buffer object. */
        VBO,
        /** Offscreen framebuffer object. */
        FBO,
        /** 2D texture handle. */
        TEXTURE,
        /** Linked shader program. */
        SHADER
    }

    private final Map<ResourceKind, Integer> activeCounts = new LinkedHashMap<>();
    private final Map<String, Double> passDurationsMs = new LinkedHashMap<>();
    private boolean enabled = true;

    /**
     * Creates a new {@code GlResourceAuditor}.
     */
    public GlResourceAuditor() {
        for (ResourceKind k : ResourceKind.values()) {
            activeCounts.put(k, 0);
        }
    }

    /**
     * Records creation of a GPU resource.
     *
     * @param kind resource category
     */
    public synchronized void recordCreate(ResourceKind kind) {
        Objects.requireNonNull(kind, "kind must not be null");
        if (enabled) {
            activeCounts.put(kind, activeCounts.getOrDefault(kind, 0) + 1);
        }
    }

    /**
     * Records disposal of a GPU resource.
     *
     * @param kind resource category
     */
    public synchronized void recordDispose(ResourceKind kind) {
        Objects.requireNonNull(kind, "kind must not be null");
        if (enabled) {
            int next = Math.max(0, activeCounts.getOrDefault(kind, 0) - 1);
            activeCounts.put(kind, next);
        }
    }

    /**
     * Returns total un-disposed GPU handles across all categories.
     *
     * @return total live resource count
     */
    public synchronized int getTotalActiveResources() {
        int total = 0;
        for (int v : activeCounts.values()) {
            total += v;
        }
        return total;
    }

    /**
     * Returns active resource count for a specific category.
     *
     * @param kind resource category
     * @return active resource count
     */
    public synchronized int getActiveCount(ResourceKind kind) {
        return activeCounts.getOrDefault(Objects.requireNonNull(kind, "kind must not be null"), 0);
    }

    /**
     * Records GPU/CPU timing duration in milliseconds for a named render pass (defaults safely to 0.0 ms).
     *
     * @param passName render pass identifier
     * @param millis   duration in milliseconds
     */
    public synchronized void recordPassTimingMs(String passName, double millis) {
        Objects.requireNonNull(passName, "passName must not be null");
        double safeMs = (Double.isFinite(millis) && millis >= 0.0) ? millis : 0.0;
        passDurationsMs.put(passName, safeMs);
    }

    /**
     * Returns recorded render pass duration in milliseconds ({@code 0.0} fallback if unavailable).
     *
     * @param passName pass name
     * @return duration in milliseconds
     */
    public synchronized double getPassTimingMs(String passName) {
        return passDurationsMs.getOrDefault(passName, 0.0);
    }

    /**
     * Returns unmodifiable snapshot of all pass timings.
     *
     * @return pass timing map in milliseconds
     */
    public synchronized Map<String, Double> getAllPassTimingsMs() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(passDurationsMs));
    }

    /**
     * Resets all resource counters and pass timings.
     */
    public synchronized void reset() {
        for (ResourceKind k : ResourceKind.values()) {
            activeCounts.put(k, 0);
        }
        passDurationsMs.clear();
    }

    /**
     * Returns whether auditing is enabled.
     *
     * @return {@code true} if enabled
     */
    public synchronized boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables tracking.
     *
     * @param enabled {@code true} to enable tracking
     */
    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
