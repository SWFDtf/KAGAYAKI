package de.swfdtf.core;

/**
 * Handle representing an active listener registration on {@link EngineEventBus}.
 *
 * <p>Implements {@link AutoCloseable} so subscriptions can be managed cleanly with
 * try-with-resources blocks or deterministic lifecycle teardown.</p>
 */
public interface EventSubscription extends AutoCloseable {

    /**
     * Returns whether this subscription is currently active and eligible to receive events.
     *
     * @return {@code true} if still subscribed
     */
    boolean isActive();

    /**
     * Cancels this subscription so no further events are delivered to its listener.
     * Calling this method multiple times is idempotent.
     */
    void unsubscribe();

    /**
     * Closes this subscription by delegating to {@link #unsubscribe()}.
     */
    @Override
    default void close() {
        unsubscribe();
    }
}
