package de.swfdtf.parser;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable notification payload emitted by {@link VariableWatchpointManager} when a watched
 * {@link de.swfdtf.state.StateStore} variable changes value.
 *
 * @param key           watched variable key (never {@code null})
 * @param previousValue previously observed value (may be {@code null})
 * @param newValue      newly observed value (may be {@code null})
 * @param sourceContext diagnostic label/location context supplied at check time (never {@code null})
 */
public record WatchpointEvent(
        String key,
        Object previousValue,
        Object newValue,
        String sourceContext
) {

    /**
     * Validates mandatory non-null metadata fields.
     */
    public WatchpointEvent {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(sourceContext, "sourceContext must not be null");
    }

    /**
     * Returns {@link #previousValue()} wrapped in an {@link Optional}.
     *
     * @return optional previous variable value
     */
    public Optional<Object> previousOptional() {
        return Optional.ofNullable(previousValue);
    }

    /**
     * Returns {@link #newValue()} wrapped in an {@link Optional}.
     *
     * @return optional new variable value
     */
    public Optional<Object> newOptional() {
        return Optional.ofNullable(newValue);
    }
}
