package de.swfdtf.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Thread-safe typed publish/subscribe event bus supporting priority-ordered dispatch
 * and per-listener exception isolation.
 *
 * <p>Listeners with higher numeric {@code priority} values are invoked first; listeners
 * with equal priority preserve their registration order. Exceptions thrown by one listener
 * are caught (and logged if an {@link EngineLogger} was supplied) so remaining listeners
 * still receive the event.</p>
 */
public final class EngineEventBus {

    private static final String LOG_CATEGORY = "EventBus";

    private static final Comparator<ListenerEntry> PRIORITY_ORDER =
            Comparator.comparingInt((ListenerEntry e) -> e.priority)
                    .reversed()
                    .thenComparingLong(e -> e.sequence);

    private final EngineLogger logger;
    private final Object mutationLock = new Object();
    private final AtomicLong sequenceGenerator = new AtomicLong(0L);
    private final ConcurrentHashMap<Class<? extends EngineEvent>, List<ListenerEntry>> listenersByType =
            new ConcurrentHashMap<>();

    /**
     * Creates an {@link EngineEventBus} without an attached {@link EngineLogger}.
     */
    public EngineEventBus() {
        this.logger = null;
    }

    /**
     * Creates an {@link EngineEventBus} that logs listener exceptions to the supplied logger.
     *
     * @param logger engine logger for reporting listener failures (must not be null)
     */
    public EngineEventBus(EngineLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
    }

    /**
     * Returns the optional logger used to record listener exceptions during dispatch.
     *
     * @return optional {@link EngineLogger}
     */
    public Optional<EngineLogger> getLogger() {
        return Optional.ofNullable(logger);
    }

    /**
     * Subscribes a listener for events assignable to {@code eventType} with default priority {@code 0}.
     *
     * @param eventType event class or interface to observe
     * @param listener  callback invoked when matching events are published
     * @param <T>       event type
     * @return subscription handle for cancelling registration
     */
    public <T extends EngineEvent> EventSubscription subscribe(Class<T> eventType, Consumer<T> listener) {
        return subscribe(eventType, listener, 0);
    }

    /**
     * Subscribes a listener for events assignable to {@code eventType} with an explicit dispatch priority.
     *
     * @param eventType event class or interface to observe
     * @param listener  callback invoked when matching events are published
     * @param priority  dispatch priority (higher values execute first)
     * @param <T>       event type
     * @return subscription handle for cancelling registration
     */
    public <T extends EngineEvent> EventSubscription subscribe(
            Class<T> eventType,
            Consumer<T> listener,
            int priority
    ) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(listener, "listener must not be null");

        long seq = sequenceGenerator.incrementAndGet();
        Consumer<EngineEvent> typedAdapter = event -> listener.accept(eventType.cast(event));
        ListenerEntry entry = new ListenerEntry(eventType, typedAdapter, priority, seq);

        synchronized (mutationLock) {
            List<ListenerEntry> existing = listenersByType.get(eventType);
            List<ListenerEntry> updated = existing == null ? new ArrayList<>(1) : new ArrayList<>(existing.size() + 1);
            if (existing != null) {
                updated.addAll(existing);
            }
            updated.add(entry);
            updated.sort(PRIORITY_ORDER);
            listenersByType.put(eventType, List.copyOf(updated));
        }

        return new EventSubscription() {
            @Override
            public boolean isActive() {
                return entry.active.get();
            }

            @Override
            public void unsubscribe() {
                if (entry.active.compareAndSet(true, false)) {
                    removeEntry(entry);
                }
            }
        };
    }

    /**
     * Publishes {@code event} synchronously to all matching active listeners in priority order.
     *
     * @param event event instance to dispatch (must not be null)
     */
    public void publish(EngineEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        Class<? extends EngineEvent> eventClass = event.getClass();
        List<ListenerEntry> matched = collectMatchingListeners(eventClass);
        if (matched.isEmpty()) {
            return;
        }

        for (ListenerEntry entry : matched) {
            if (!entry.active.get()) {
                continue;
            }
            try {
                entry.listener.accept(event);
            } catch (RuntimeException ex) {
                if (logger != null) {
                    logger.error(
                            LOG_CATEGORY,
                            "Exception in listener for event " + eventClass.getSimpleName(),
                            ex
                    );
                }
            }
        }
    }

    /**
     * Returns the number of currently active listeners registered directly for {@code eventType}.
     *
     * @param eventType exact event class or interface key
     * @return active listener count
     */
    public int listenerCount(Class<? extends EngineEvent> eventType) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        List<ListenerEntry> entries = listenersByType.get(eventType);
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (ListenerEntry entry : entries) {
            if (entry.active.get()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Removes all registered listeners and marks all outstanding {@link EventSubscription}s inactive.
     */
    public void clear() {
        synchronized (mutationLock) {
            for (List<ListenerEntry> entries : listenersByType.values()) {
                for (ListenerEntry entry : entries) {
                    entry.active.set(false);
                }
            }
            listenersByType.clear();
        }
    }

    private List<ListenerEntry> collectMatchingListeners(Class<? extends EngineEvent> eventClass) {
        List<ListenerEntry> exactOnly = listenersByType.get(eventClass);
        boolean hasHierarchyMatches = false;
        for (Class<? extends EngineEvent> registeredType : listenersByType.keySet()) {
            if (!registeredType.equals(eventClass) && registeredType.isAssignableFrom(eventClass)) {
                hasHierarchyMatches = true;
                break;
            }
        }

        if (!hasHierarchyMatches) {
            return exactOnly != null ? exactOnly : List.of();
        }

        List<ListenerEntry> combined = new ArrayList<>();
        for (Map.Entry<Class<? extends EngineEvent>, List<ListenerEntry>> mapEntry : listenersByType.entrySet()) {
            if (mapEntry.getKey().isAssignableFrom(eventClass)) {
                combined.addAll(mapEntry.getValue());
            }
        }
        combined.sort(PRIORITY_ORDER);
        return combined;
    }

    private void removeEntry(ListenerEntry target) {
        synchronized (mutationLock) {
            List<ListenerEntry> existing = listenersByType.get(target.eventType);
            if (existing == null || existing.isEmpty()) {
                return;
            }
            List<ListenerEntry> remaining = new ArrayList<>(existing.size());
            for (ListenerEntry entry : existing) {
                if (entry != target && entry.active.get()) {
                    remaining.add(entry);
                }
            }
            if (remaining.isEmpty()) {
                listenersByType.remove(target.eventType);
            } else {
                listenersByType.put(target.eventType, List.copyOf(remaining));
            }
        }
    }

    private static final class ListenerEntry {
        private final Class<? extends EngineEvent> eventType;
        private final Consumer<EngineEvent> listener;
        private final int priority;
        private final long sequence;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private ListenerEntry(
                Class<? extends EngineEvent> eventType,
                Consumer<EngineEvent> listener,
                int priority,
                long sequence
        ) {
            this.eventType = eventType;
            this.listener = listener;
            this.priority = priority;
            this.sequence = sequence;
        }
    }
}
