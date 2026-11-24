package de.swfdtf.parser;

import de.swfdtf.state.StateStore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Observes variable changes on a {@link StateStore} and dispatches {@link WatchpointEvent}
 * notifications to registered key or conditional watchpoints.
 *
 * <p>Designed for non-blocking polling at script step boundaries or frame updates without
 * wrapping or mutating {@link StateStore} implementations.</p>
 */
public final class VariableWatchpointManager {

    /**
     * Default maximum number of recent {@link WatchpointEvent} records retained in history.
     */
    public static final int DEFAULT_MAX_HISTORY_SIZE = 256;

    private static final class WatchEntry {
        private final String id;
        private final String key;
        private final Predicate<Object> condition;
        private final Consumer<WatchpointEvent> listener;
        private Object lastObservedValue;

        private WatchEntry(
                String id,
                String key,
                Predicate<Object> condition,
                Consumer<WatchpointEvent> listener
        ) {
            this.id = id;
            this.key = key;
            this.condition = condition;
            this.listener = listener;
            this.lastObservedValue = null;
        }
    }

    private final AtomicLong idSequence = new AtomicLong(0L);
    private final Map<String, WatchEntry> watches = new LinkedHashMap<>();
    private final Deque<WatchpointEvent> history = new ArrayDeque<>();
    private final int maxHistorySize;

    /**
     * Creates a watchpoint manager retaining up to {@value #DEFAULT_MAX_HISTORY_SIZE} recent events.
     */
    public VariableWatchpointManager() {
        this(DEFAULT_MAX_HISTORY_SIZE);
    }

    /**
     * Creates a watchpoint manager with a configurable bounded event history size.
     *
     * @param maxHistorySize maximum number of retained events (must be {@code >= 0})
     */
    public VariableWatchpointManager(int maxHistorySize) {
        if (maxHistorySize < 0) {
            throw new IllegalArgumentException("maxHistorySize must not be negative");
        }
        this.maxHistorySize = maxHistorySize;
    }

    /**
     * Registers a watchpoint that fires whenever {@code key} changes value.
     *
     * @param key      variable key in {@link StateStore} (must not be {@code null} or blank)
     * @param listener callback invoked on value change
     * @return unique watchpoint identifier for {@link #unwatch(String)}
     */
    public synchronized String watchKey(String key, Consumer<WatchpointEvent> listener) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        Objects.requireNonNull(listener, "listener must not be null");
        String id = "wp-" + idSequence.incrementAndGet();
        watches.put(id, new WatchEntry(id, key, null, listener));
        return id;
    }

    /**
     * Registers a conditional watchpoint that fires when {@code key} changes value and
     * {@code condition.test(newValue)} evaluates to {@code true}.
     *
     * @param key       variable key in {@link StateStore} (must not be {@code null} or blank)
     * @param condition predicate evaluated against the new variable value
     * @param listener  callback invoked when the change satisfies {@code condition}
     * @return unique watchpoint identifier for {@link #unwatch(String)}
     */
    public synchronized String watchWhen(
            String key,
            Predicate<Object> condition,
            Consumer<WatchpointEvent> listener
    ) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        Objects.requireNonNull(condition, "condition must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        String id = "wp-" + idSequence.incrementAndGet();
        watches.put(id, new WatchEntry(id, key, condition, listener));
        return id;
    }

    /**
     * Removes a previously registered watchpoint.
     *
     * @param watchId identifier returned by {@link #watchKey} or {@link #watchWhen}
     * @return {@code true} if the watchpoint was found and removed
     */
    public synchronized boolean unwatch(String watchId) {
        Objects.requireNonNull(watchId, "watchId must not be null");
        return watches.remove(watchId) != null;
    }

    /**
     * Polls all registered watchpoints against {@code stateStore} and dispatches events for
     * changed values (matching conditions when present).
     *
     * @param stateStore    state store to inspect
     * @param sourceContext diagnostic context label attached to emitted events
     * @return number of events fired during this check
     */
    public int checkAll(StateStore stateStore, String sourceContext) {
        Objects.requireNonNull(stateStore, "stateStore must not be null");
        Objects.requireNonNull(sourceContext, "sourceContext must not be null");

        List<WatchpointEvent> firedEvents = new ArrayList<>();
        List<Consumer<WatchpointEvent>> listenersToNotify = new ArrayList<>();

        synchronized (this) {
            List<WatchEntry> snapshot = new ArrayList<>(watches.values());
            for (WatchEntry entry : snapshot) {
                Object currentValue = stateStore.get(entry.key);
                Object previousValue = entry.lastObservedValue;
                if (Objects.equals(previousValue, currentValue)) {
                    continue;
                }
                entry.lastObservedValue = currentValue;
                if (entry.condition == null || entry.condition.test(currentValue)) {
                    WatchpointEvent event = new WatchpointEvent(
                            entry.key,
                            previousValue,
                            currentValue,
                            sourceContext
                    );
                    recordHistory(event);
                    firedEvents.add(event);
                    listenersToNotify.add(entry.listener);
                }
            }
        }

        for (int i = 0; i < firedEvents.size(); i++) {
            listenersToNotify.get(i).accept(firedEvents.get(i));
        }
        return firedEvents.size();
    }

    /**
     * Returns an unmodifiable snapshot of recently fired watchpoint events in chronological order.
     *
     * @return unmodifiable list of recent {@link WatchpointEvent} instances
     */
    public synchronized List<WatchpointEvent> getRecentHistory() {
        return List.copyOf(history);
    }

    /**
     * Removes all registered watchpoints and clears recorded event history.
     */
    public synchronized void clear() {
        watches.clear();
        history.clear();
    }

    private void recordHistory(WatchpointEvent event) {
        if (maxHistorySize == 0) {
            return;
        }
        while (history.size() >= maxHistorySize) {
            history.removeFirst();
        }
        history.addLast(event);
    }
}
