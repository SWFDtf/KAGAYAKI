package de.swfdtf.state;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Core state views, reactive change bus, variable key guards, exact {@link BigDecimal} currency
 * helpers, compact delta-diff rollback buffers, and chapter-entry checkpoints
 * ({@code #0524}, {@code #0525}, {@code #0532}, {@code #0533}, {@code #0543}, {@code #0544},
 * {@code #0549}, {@code #0559}, {@code #0577}, {@code #0578}, {@code #0582}).
 */
public final class StateCoreEnhancements {

    /** Reserved prefix blocked from arbitrary user state mutation ({@code #0578}). */
    public static final String RESERVED_INTERNAL_PREFIX = "_internal_";

    private StateCoreEnhancements() {
    }

    /**
     * Validates that a variable key is non-null, non-blank, and does not overwrite reserved engine
     * keys beginning with {@value #RESERVED_INTERNAL_PREFIX} ({@code #0578}).
     *
     * @param key candidate variable key
     * @return validated key
     */
    public static String validateStateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("State key must not be null or blank");
        }
        if (key.startsWith(RESERVED_INTERNAL_PREFIX)) {
            throw new IllegalArgumentException("State key uses reserved engine prefix '"
                    + RESERVED_INTERNAL_PREFIX + "': " + key);
        }
        return key;
    }

    /**
     * Read-only strongly typed wrapper for nested {@link Map} / {@link List} structures inside
     * {@link StateStore} ({@code #0524}, {@code #0543}).
     */
    public static final class TypeSafeStateView {

        private final Map<String, Object> root;

        /**
         * Creates a read-only view backed by a defensive snapshot of {@code store}.
         *
         * @param store source state store
         */
        public TypeSafeStateView(StateStore store) {
            this(Objects.requireNonNull(store, "store must not be null").snapshotMap());
        }

        /**
         * Creates a read-only view from an arbitrary map snapshot.
         *
         * @param root root map (defensively copied cleanly)
         */
        public TypeSafeStateView(Map<String, Object> root) {
            Objects.requireNonNull(root, "root must not be null");
            Object cloned = RevertableMap.cloneValue(root);
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = cloned instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : Map.of();
            this.root = Collections.unmodifiableMap(new LinkedHashMap<>(cast));
        }

        /**
         * Exception raised when a stored variable exists with an incompatible runtime type.
         */
        public static final class StateTypeMismatchException extends RuntimeException {

            /**
             * Creates a mismatch exception describing the failing key path and expected/actual types.
             *
             * @param message diagnostic detail message
             */
            public StateTypeMismatchException(String message) {
                super(message);
            }
        }

        /**
         * Traverses nested maps along {@code dotPath} (e.g. {@code "player.stats.hp"}) and returns
         * the target value cast safely to {@code expectedType}, or {@link Optional#empty()} if missing.
         *
         * @param dotPath      dot-separated key path
         * @param expectedType expected value class
         * @param <T>          value type parameter
         * @return optional typed value
         * @throws StateTypeMismatchException if an intermediate segment is not a map or leaf type differs
         */
        public <T> Optional<T> getOptional(String dotPath, Class<T> expectedType) {
            Objects.requireNonNull(expectedType, "expectedType must not be null");
            Optional<Object> raw = resolveRaw(dotPath);
            if (raw.isEmpty()) {
                return Optional.empty();
            }
            Object value = raw.get();
            if (!expectedType.isInstance(value)) {
                throw new StateTypeMismatchException("Key '" + dotPath + "' expected "
                        + expectedType.getSimpleName() + " but found " + value.getClass().getSimpleName());
            }
            return Optional.of(expectedType.cast(value));
        }

        /**
         * Reads a typed nested {@link List}, validating all element types safely.
         *
         * @param dotPath     dot-separated key path
         * @param elementType expected list element class
         * @param <T>         element type parameter
         * @return immutable list or {@link Optional#empty()} if missing
         */
        public <T> Optional<List<T>> getNestedList(String dotPath, Class<T> elementType) {
            Objects.requireNonNull(elementType, "elementType must not be null");
            Optional<Object> raw = resolveRaw(dotPath);
            if (raw.isEmpty()) {
                return Optional.empty();
            }
            Object val = raw.get();
            if (!(val instanceof List<?> list)) {
                throw new StateTypeMismatchException("Key '" + dotPath + "' expected List but found "
                        + val.getClass().getSimpleName());
            }
            List<T> result = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                Object elem = list.get(i);
                if (!elementType.isInstance(elem)) {
                    String found = elem != null ? elem.getClass().getSimpleName() : "null";
                    throw new StateTypeMismatchException("Element " + i + " at '" + dotPath + "' expected "
                            + elementType.getSimpleName() + " but found " + found);
                }
                result.add(elementType.cast(elem));
            }
            return Optional.of(List.copyOf(result));
        }

        /**
         * Reads a read-only nested {@link Map}.
         *
         * @param dotPath dot-separated key path
         * @return immutable string-keyed map or {@link Optional#empty()} if missing
         */
        public Optional<Map<String, Object>> getNestedMap(String dotPath) {
            Optional<Object> raw = resolveRaw(dotPath);
            if (raw.isEmpty()) {
                return Optional.empty();
            }
            Object val = raw.get();
            if (!(val instanceof Map<?, ?> map)) {
                throw new StateTypeMismatchException("Key '" + dotPath + "' expected Map but found "
                        + val.getClass().getSimpleName());
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return Optional.of(Collections.unmodifiableMap(copy));
        }

        /**
         * Reads an exact {@link BigDecimal} currency/precision value without binary float drift ({@code #0543}).
         *
         * @param dotPath dot-separated key path
         * @return optional exact {@link BigDecimal}
         */
        public Optional<BigDecimal> getBigDecimal(String dotPath) {
            Optional<Object> raw = resolveRaw(dotPath);
            if (raw.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(toExactBigDecimal(dotPath, raw.get()));
        }

        /**
         * Adds {@code deltaDecimal} to a currency key stored as {@link BigDecimal} safely ({@code #0543}).
         *
         * @param store        mutable state store
         * @param key          variable key
         * @param deltaDecimal exact decimal string (e.g. {@code "0.20"} or {@code "-1.50"})
         * @return resulting {@link BigDecimal} stored in {@code store}
         */
        public static BigDecimal addCurrency(StateStore store, String key, String deltaDecimal) {
            Objects.requireNonNull(store, "store must not be null");
            validateStateKey(key);
            Objects.requireNonNull(deltaDecimal, "deltaDecimal must not be null");
            Object currentRaw = store.get(key);
            BigDecimal current = currentRaw == null
                    ? BigDecimal.ZERO
                    : toExactBigDecimal(key, currentRaw);
            BigDecimal next = current.add(new BigDecimal(deltaDecimal));
            store.set(key, next);
            return next;
        }

        private static BigDecimal toExactBigDecimal(String path, Object val) {
            if (val instanceof BigDecimal bd) {
                return bd;
            }
            if (val instanceof Integer || val instanceof Long || val instanceof String) {
                try {
                    return new BigDecimal(String.valueOf(val).trim());
                } catch (NumberFormatException ex) {
                    throw new StateTypeMismatchException("Key '" + path + "' is not a valid decimal: " + val);
                }
            }
            if (val instanceof Number n) {
                return new BigDecimal(n.toString());
            }
            throw new StateTypeMismatchException("Key '" + path + "' expected BigDecimal/Number but found "
                    + val.getClass().getSimpleName());
        }

        private Optional<Object> resolveRaw(String dotPath) {
            if (dotPath == null || dotPath.isBlank()) {
                throw new IllegalArgumentException("dotPath must not be null or blank");
            }
            String[] parts = dotPath.split("\\.");
            Object cursor = root;
            for (int i = 0; i < parts.length; i++) {
                String seg = parts[i];
                if (seg.isEmpty()) {
                    throw new IllegalArgumentException("Invalid empty path segment in '" + dotPath + "'");
                }
                if (!(cursor instanceof Map<?, ?> map)) {
                    throw new StateTypeMismatchException("Intermediate path segment before '" + seg
                            + "' in '" + dotPath + "' is not a Map");
                }
                if (!map.containsKey(seg)) {
                    return Optional.empty();
                }
                cursor = map.get(seg);
                if (cursor == null) {
                    return Optional.empty();
                }
            }
            return Optional.of(cursor);
        }
    }

    /**
     * Event published when a watched state variable actually changes value ({@code #0525}).
     *
     * @param key          variable key
     * @param oldValue     previous value (nullable)
     * @param newValue     updated value (nullable)
     * @param labelContext active script label context for debugging (nullable)
     * @param timestamp    event UTC timestamp
     */
    public record StateChangeEvent(
            String key,
            Object oldValue,
            Object newValue,
            String labelContext,
            Instant timestamp
    ) {
        /** Normalizes timestamp cleanly. */
        public StateChangeEvent {
            Objects.requireNonNull(key, "key must not be null");
            timestamp = timestamp != null ? timestamp : Instant.now();
        }
    }

    /**
     * Reactive property-change dispatcher and bounded Studio variable audit log ({@code #0525}, {@code #0533}).
     */
    public static final class ObservableStateBus {

        private final StateStore delegate;
        private final Clock clock;
        private final int maxAuditEntriesPerKey;
        private final List<Consumer<StateChangeEvent>> globalListeners = new CopyOnWriteArrayList<>();
        private final Map<String, List<Consumer<StateChangeEvent>>> keyListeners = new LinkedHashMap<>();
        private final Map<String, Deque<StateChangeEvent>> auditByKey = new LinkedHashMap<>();

        /**
         * Creates an {@code ObservableStateBus} wrapping {@code delegate} using UTC system clock.
         *
         * @param delegate underlying {@link StateStore}
         */
        public ObservableStateBus(StateStore delegate) {
            this(delegate, Clock.systemUTC(), 50);
        }

        /**
         * Creates an {@code ObservableStateBus} with custom clock and bounded audit history per key.
         *
         * @param delegate              underlying {@link StateStore}
         * @param clock                 clock source
         * @param maxAuditEntriesPerKey max audit events kept per variable key ({@code >= 1})
         */
        public ObservableStateBus(StateStore delegate, Clock clock, int maxAuditEntriesPerKey) {
            this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
            this.clock = Objects.requireNonNull(clock, "clock must not be null");
            if (maxAuditEntriesPerKey < 1) {
                throw new IllegalArgumentException("maxAuditEntriesPerKey must be >= 1");
            }
            this.maxAuditEntriesPerKey = maxAuditEntriesPerKey;
        }

        /**
         * Registers a global property-change listener.
         *
         * @param listener consumer receiving change events
         */
        public void addListener(Consumer<StateChangeEvent> listener) {
            globalListeners.add(Objects.requireNonNull(listener, "listener must not be null"));
        }

        /**
         * Registers a listener filtered to a single variable key.
         *
         * @param key      variable key
         * @param listener consumer receiving matching events
         */
        public synchronized void addKeyListener(String key, Consumer<StateChangeEvent> listener) {
            validateStateKey(key);
            Objects.requireNonNull(listener, "listener must not be null");
            keyListeners.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(listener);
        }

        /**
         * Updates {@code key} and fires change listeners and audit logs ONLY when {@code newValue}
         * differs structurally from the existing value ({@code #0525}, {@code #0533}).
         *
         * @param key          validated variable key
         * @param newValue     new variable value
         * @param labelContext originating label/scene name (nullable)
         * @return {@code true} if value changed and listeners were notified
         */
        public boolean set(String key, Object newValue, String labelContext) {
            validateStateKey(key);
            StateChangeEvent event;
            List<Consumer<StateChangeEvent>> specific;
            synchronized (this) {
                Object oldValue = delegate.get(key);
                if (RevertableMap.deepEquals(oldValue, newValue)) {
                    return false;
                }
                delegate.set(key, newValue);
                event = new StateChangeEvent(key, RevertableMap.cloneValue(oldValue),
                        RevertableMap.cloneValue(newValue), labelContext, clock.instant());
                Deque<StateChangeEvent> history = auditByKey.computeIfAbsent(key, k -> new ArrayDeque<>());
                while (history.size() >= maxAuditEntriesPerKey) {
                    history.removeFirst();
                }
                history.addLast(event);
                specific = keyListeners.containsKey(key) ? List.copyOf(keyListeners.get(key)) : List.of();
            }
            for (Consumer<StateChangeEvent> listener : specific) {
                listener.accept(event);
            }
            for (Consumer<StateChangeEvent> listener : globalListeners) {
                listener.accept(event);
            }
            return true;
        }

        /**
         * Returns the chronological change audit history recorded for {@code key} ({@code #0533}).
         *
         * @param key variable key
         * @return immutable chronological list of changes
         */
        public synchronized List<StateChangeEvent> getAuditHistory(String key) {
            Deque<StateChangeEvent> deque = auditByKey.get(key);
            return deque != null ? List.copyOf(deque) : List.of();
        }
    }

    /**
     * Outcome returned by {@link DeltaRollbackBuffer#rollback()}.
     *
     * @param success    {@code true} when rollback stepped back
     * @param state      reconstructed state map if successful
     * @param statusText user-facing status/feedback text ({@code #0559})
     */
    public record RollbackOutcome(boolean success, Optional<Map<String, Object>> state, String statusText) {
    }

    /**
     * Delta-compressed rollback buffer storing per-step changed keys and periodic keyframes for
     * low-heap replay reconstruction ({@code #0544}, {@code #0559}, {@code #0577}, {@code #0582}).
     */
    public static final class DeltaRollbackBuffer {

        private record DeltaFrame(
                Map<String, Object> keyframe,
                Map<String, Object> changedKeys,
                Set<String> removedKeys,
                boolean blockedBefore
        ) {
        }

        private final int maxDepth;
        private final int keyframeInterval;
        private final List<DeltaFrame> frames = new ArrayList<>();
        private Map<String, Object> latestFullState = new LinkedHashMap<>();

        /**
         * Creates a {@code DeltaRollbackBuffer} with capacity {@code maxDepth} and {@code keyframeInterval}.
         *
         * @param maxDepth         maximum retained steps ({@code >= 1})
         * @param keyframeInterval periodic full keyframe interval ({@code >= 1})
         */
        public DeltaRollbackBuffer(int maxDepth, int keyframeInterval) {
            if (maxDepth < 1 || keyframeInterval < 1) {
                throw new IllegalArgumentException("maxDepth and keyframeInterval must be >= 1");
            }
            this.maxDepth = maxDepth;
            this.keyframeInterval = keyframeInterval;
        }

        /**
         * Records a state snapshot as a minimal diff relative to the previous frame ({@code #0544}).
         *
         * @param stateSnapshot current full variable state map
         * @param blockedBefore whether {@code block_rollback} prevents rewinding past this frame
         */
        public synchronized void recordStep(Map<String, Object> stateSnapshot, boolean blockedBefore) {
            Objects.requireNonNull(stateSnapshot, "stateSnapshot must not be null");
            Map<String, Object> clonedFull = cloneMap(stateSnapshot);
            if (frames.isEmpty() || frames.size() % keyframeInterval == 0) {
                frames.add(new DeltaFrame(clonedFull, Map.of(), Set.of(), blockedBefore));
            } else {
                Map<String, Object> changed = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : clonedFull.entrySet()) {
                    if (!latestFullState.containsKey(entry.getKey())
                            || !RevertableMap.deepEquals(latestFullState.get(entry.getKey()), entry.getValue())) {
                        changed.put(entry.getKey(), entry.getValue());
                    }
                }
                Set<String> removed = new LinkedHashSet<>();
                for (String prevKey : latestFullState.keySet()) {
                    if (!clonedFull.containsKey(prevKey)) {
                        removed.add(prevKey);
                    }
                }
                frames.add(new DeltaFrame(null, Collections.unmodifiableMap(changed),
                        Collections.unmodifiableSet(removed), blockedBefore));
            }
            latestFullState = clonedFull;
            trimOldestIfNeeded();
        }

        /**
         * Rewinds one step back, reconstructing the exact state from the nearest keyframe ({@code #0577}).
         *
         * @return {@link RollbackOutcome} containing reconstructed state or friendly boundary message ({@code #0559})
         */
        public synchronized RollbackOutcome rollback() {
            if (frames.size() <= 1) {
                return new RollbackOutcome(false, Optional.empty(), "Bereits am Anfang der Szene");
            }
            if (frames.get(frames.size() - 1).blockedBefore()) {
                return new RollbackOutcome(false, Optional.empty(), "Zurückspulen an diesem Punkt gesperrt");
            }
            frames.remove(frames.size() - 1);
            latestFullState = reconstructAtIndex(frames.size() - 1);
            return new RollbackOutcome(true, Optional.of(cloneMap(latestFullState)), "");
        }

        /**
         * Executes {@code history_clear} optionally combined with a {@code block_rollback} barrier ({@code #0582}).
         *
         * @param installBarrier {@code true} to keep current frame locked as non-rewindable barrier
         */
        public synchronized void clearHistory(boolean installBarrier) {
            if (frames.isEmpty()) {
                return;
            }
            Map<String, Object> keep = cloneMap(latestFullState);
            frames.clear();
            frames.add(new DeltaFrame(keep, Map.of(), Set.of(), installBarrier));
        }

        /**
         * Returns the number of retained frames.
         *
         * @return frame count
         */
        public synchronized int size() {
            return frames.size();
        }

        /**
         * Counts total per-key entries stored inside delta/keyframe frames for memory inspection ({@code #0577}).
         *
         * @return total stored map/key entries across all frames
         */
        public synchronized int totalStoredEntries() {
            int sum = 0;
            for (DeltaFrame frame : frames) {
                if (frame.keyframe() != null) {
                    sum += frame.keyframe().size();
                } else {
                    sum += frame.changedKeys().size() + frame.removedKeys().size();
                }
            }
            return sum;
        }

        private Map<String, Object> reconstructAtIndex(int targetIdx) {
            int kfIdx = targetIdx;
            while (kfIdx > 0 && frames.get(kfIdx).keyframe() == null) {
                kfIdx--;
            }
            DeltaFrame kf = frames.get(kfIdx);
            Map<String, Object> state = kf.keyframe() != null
                    ? cloneMap(kf.keyframe())
                    : new LinkedHashMap<>();
            for (int i = kfIdx + 1; i <= targetIdx; i++) {
                DeltaFrame f = frames.get(i);
                for (String rem : f.removedKeys()) {
                    state.remove(rem);
                }
                for (Map.Entry<String, Object> entry : f.changedKeys().entrySet()) {
                    state.put(entry.getKey(), RevertableMap.cloneValue(entry.getValue()));
                }
            }
            return state;
        }

        private void trimOldestIfNeeded() {
            while (frames.size() > maxDepth && frames.size() > 1) {
                if (frames.get(1).keyframe() == null) {
                    Map<String, Object> promoted = reconstructAtIndex(1);
                    frames.set(1, new DeltaFrame(promoted, Map.of(), Set.of(), frames.get(1).blockedBefore()));
                }
                frames.remove(0);
            }
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> cloneMap(Map<String, Object> source) {
            Object cloned = RevertableMap.cloneValue(source);
            return cloned instanceof Map<?, ?> m
                    ? new LinkedHashMap<>((Map<String, Object>) m)
                    : new LinkedHashMap<>(source);
        }
    }

    /**
     * Maintains invisible chapter-entry checkpoints allowing "Restart Chapter" recovery ({@code #0549}).
     */
    public static final class ChapterEntryCheckpointService {

        private final Map<String, SaveSlotData> entrySnapshots = new LinkedHashMap<>();

        /**
         * Creates a new {@code ChapterEntryCheckpointService}.
         */
        public ChapterEntryCheckpointService() {
        }

        /**
         * Stores or updates the chapter-start snapshot for {@code chapterId}.
         *
         * @param chapterId chapter label or ID
         * @param snapshot  captured chapter entry save snapshot
         */
        public synchronized void recordChapterEntry(String chapterId, SaveSlotData snapshot) {
            validateStateKey(chapterId);
            Objects.requireNonNull(snapshot, "snapshot must not be null");
            entrySnapshots.put(chapterId, snapshot);
        }

        /**
         * Retrieves the chapter-entry snapshot to restart from the beginning of {@code chapterId}.
         *
         * @param chapterId chapter label or ID
         * @return chapter-entry save snapshot if recorded
         */
        public synchronized Optional<SaveSlotData> getChapterEntry(String chapterId) {
            return Optional.ofNullable(entrySnapshots.get(chapterId));
        }

        /**
         * Clears all recorded chapter entry points.
         */
        public synchronized void clear() {
            entrySnapshots.clear();
        }
    }
}
