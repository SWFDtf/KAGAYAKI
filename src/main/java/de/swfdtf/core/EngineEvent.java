package de.swfdtf.core;

/**
 * Marker and timing contract for all events dispatched through {@link EngineEventBus}.
 *
 * <p>Implementations should be immutable value types or records representing a single
 * engine, UI, or gameplay occurrence.</p>
 */
public interface EngineEvent {

    /**
     * Returns the monotonic timestamp in nanoseconds when this event was created.
     *
     * @return event timestamp in nanoseconds from {@link System#nanoTime()}
     */
    default long timestampNanos() {
        return System.nanoTime();
    }
}
