package de.swfdtf.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Deterministic transactional {@link StateStore} backed by {@link RevertableMap} with full
 * rollback, roll-forward trail preservation, diverging-branch trimming, {@code HistoryLog}
 * checkpoint jump-back, and {@link #blockRollback()} point-of-no-return barriers.
 *
 * <p>Runtime execution snapshots are handled as opaque {@link Object} values via optional
 * {@link #setRuntimeHooks(Supplier, Consumer)} callbacks so {@code de.swfdtf.state} stays
 * decoupled from {@code de.swfdtf.parser}.</p>
 */
public class RevertableStateStore implements StateStore {

    /**
     * Immutable snapshot checkpoint capturing variable state and optional runtime execution state.
     *
     * @param id                    monotonically increasing unique checkpoint ID
     * @param variablesSnapshot     deep-isolated snapshot of all {@link StateStore} variables
     * @param runtimeSnapshot       optional deep-isolated runtime execution snapshot (nullable)
     * @param summary               human-readable checkpoint summary (e.g., dialogue preview or label)
     * @param rollbackBarrierBefore {@code true} if {@link #blockRollback()} was triggered prior to
     *                              recording this checkpoint, blocking rollback past it
     */
    public record Checkpoint(
            long id,
            Map<String, Object> variablesSnapshot,
            Object runtimeSnapshot,
            String summary,
            boolean rollbackBarrierBefore
    ) {
        public Checkpoint {
            variablesSnapshot = variablesSnapshot != null
                    ? Collections.unmodifiableMap(new LinkedHashMap<>(variablesSnapshot))
                    : Map.of();
        }
    }

    private final RevertableMap<String, Object> variables = new RevertableMap<>();
    private final List<Checkpoint> checkpoints = new ArrayList<>();

    private Supplier<Object> runtimeSnapshotter;
    private Consumer<Object> runtimeRestorer;
    private int cursorIndex = -1;
    private long nextCheckpointId = 1L;
    private boolean pendingRollbackBarrier = false;

    /**
     * Creates an empty {@code RevertableStateStore} with no recorded checkpoints.
     */
    public RevertableStateStore() {
    }

    /**
     * Attaches optional runtime snapshot capture and restore hooks (typically wired to
     * {@code ScriptRuntime::snapshotExecution} and {@code ScriptRuntime::restoreExecution}).
     *
     * @param snapshotter supplier producing a runtime execution snapshot (may be {@code null})
     * @param restorer    consumer restoring a previously captured runtime snapshot (may be {@code null})
     */
    public synchronized void setRuntimeHooks(Supplier<Object> snapshotter, Consumer<Object> restorer) {
        this.runtimeSnapshotter = snapshotter;
        this.runtimeRestorer = restorer;
    }

    /**
     * Captures a checkpoint using the registered runtime snapshot hook (if any) and the given summary.
     *
     * @param summary descriptive label for this checkpoint (nullable)
     * @return unique checkpoint ID
     */
    public synchronized long recordCheckpoint(String summary) {
        Object runtimeSnapshot = runtimeSnapshotter != null ? runtimeSnapshotter.get() : null;
        return recordCheckpoint(runtimeSnapshot, summary);
    }

    /**
     * Captures a checkpoint with an explicit runtime snapshot and summary.
     *
     * <p>If the store was previously rolled back and future checkpoints exist ahead of the cursor:
     * advancing cleanly with an identical variable state, runtime snapshot, summary, and barrier flag
     * preserves the existing roll-forward trail; any diverging state or branch trims invalidated
     * future checkpoints before appending the new checkpoint.</p>
     *
     * @param runtimeSnapshot explicit runtime execution snapshot (nullable)
     * @param summary         descriptive label for this checkpoint (nullable)
     * @return unique checkpoint ID
     */
    public synchronized long recordCheckpoint(Object runtimeSnapshot, String summary) {
        Map<String, Object> varsSnapshot = variables.captureState();
        Object isolatedRuntime = RevertableMap.cloneValue(runtimeSnapshot);
        boolean barrier = this.pendingRollbackBarrier;

        if (cursorIndex >= -1 && cursorIndex + 1 < checkpoints.size()) {
            Checkpoint next = checkpoints.get(cursorIndex + 1);
            if (next.rollbackBarrierBefore() == barrier
                    && Objects.equals(next.summary(), summary)
                    && RevertableMap.deepEquals(next.variablesSnapshot(), varsSnapshot)
                    && RevertableMap.deepEquals(next.runtimeSnapshot(), isolatedRuntime)) {
                this.cursorIndex = cursorIndex + 1;
                this.pendingRollbackBarrier = false;
                return next.id();
            }
            checkpoints.subList(cursorIndex + 1, checkpoints.size()).clear();
        }

        Checkpoint created = new Checkpoint(
                nextCheckpointId++,
                varsSnapshot,
                isolatedRuntime,
                summary,
                barrier
        );
        checkpoints.add(created);
        this.cursorIndex = checkpoints.size() - 1;
        this.pendingRollbackBarrier = false;
        return created.id();
    }

    /**
     * Returns whether stepping back one checkpoint is permitted without crossing a
     * {@link #blockRollback()} barrier.
     *
     * @return {@code true} if {@link #rollback()} will succeed
     */
    public synchronized boolean canRollback() {
        return cursorIndex > 0
                && !pendingRollbackBarrier
                && !checkpoints.get(cursorIndex).rollbackBarrierBefore();
    }

    /**
     * Rolls back variables and runtime state to the immediately preceding checkpoint, unless blocked
     * by a {@link #blockRollback()} barrier or already at the earliest checkpoint.
     *
     * @return {@code true} if rollback succeeded, {@code false} if blocked or unavailable
     */
    public synchronized boolean rollback() {
        if (!canRollback()) {
            return false;
        }
        cursorIndex--;
        restoreCheckpointInternal(checkpoints.get(cursorIndex));
        return true;
    }

    /**
     * Returns whether stepping forward along an intact roll-forward trail is available.
     *
     * @return {@code true} if {@link #rollForward()} will succeed
     */
    public synchronized boolean canRollForward() {
        return cursorIndex >= 0 && cursorIndex < checkpoints.size() - 1;
    }

    /**
     * Steps forward one checkpoint along the preserved roll-forward trail and restores its state.
     *
     * @return {@code true} if roll-forward succeeded, {@code false} if at the latest checkpoint
     */
    public synchronized boolean rollForward() {
        if (!canRollForward()) {
            return false;
        }
        cursorIndex++;
        restoreCheckpointInternal(checkpoints.get(cursorIndex));
        return true;
    }

    /**
     * Jumps directly to the checkpoint identified by {@code checkpointId} (for example, from a
     * {@link HistoryLog} jump-back action), refusing if the path backward would cross any
     * {@link #blockRollback()} barrier.
     *
     * @param checkpointId target checkpoint ID
     * @return {@code true} if the checkpoint was found and restored, {@code false} if not found or blocked
     */
    public synchronized boolean jumpToCheckpoint(long checkpointId) {
        int targetIndex = -1;
        for (int i = 0; i < checkpoints.size(); i++) {
            if (checkpoints.get(i).id() == checkpointId) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex < 0) {
            return false;
        }
        if (targetIndex <= cursorIndex && pendingRollbackBarrier) {
            return false;
        }
        if (targetIndex < cursorIndex) {
            for (int i = cursorIndex; i > targetIndex; i--) {
                if (checkpoints.get(i).rollbackBarrierBefore()) {
                    return false;
                }
            }
        }
        cursorIndex = targetIndex;
        restoreCheckpointInternal(checkpoints.get(targetIndex));
        return true;
    }

    /**
     * Returns the checkpoint at the current cursor position, or {@code null} if no checkpoints exist.
     *
     * @return active {@link Checkpoint} or {@code null}
     */
    public synchronized Checkpoint getCurrentCheckpoint() {
        return (cursorIndex >= 0 && cursorIndex < checkpoints.size())
                ? checkpoints.get(cursorIndex)
                : null;
    }

    /**
     * Returns an immutable copy of all checkpoints currently stored on the timeline.
     *
     * @return ordered list of checkpoints
     */
    public synchronized List<Checkpoint> getCheckpoints() {
        return List.copyOf(checkpoints);
    }

    /**
     * Returns the 0-based index of the current checkpoint, or {@code -1} when empty.
     *
     * @return cursor index
     */
    public synchronized int getCursorIndex() {
        return cursorIndex;
    }

    /**
     * Clears the recorded checkpoint history without clearing current variable values.
     */
    public synchronized void clearCheckpoints() {
        checkpoints.clear();
        cursorIndex = -1;
        pendingRollbackBarrier = false;
    }

    // ---- StateStore Implementation ----

    @Override
    public synchronized void set(String key, Object value) {
        Objects.requireNonNull(key, "key must not be null");
        if (value == null) {
            variables.remove(key);
        } else {
            variables.put(key, value);
        }
    }

    @Override
    public synchronized Object get(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return variables.get(key);
    }

    @Override
    public synchronized Object getOrDefault(String key, Object defaultValue) {
        Objects.requireNonNull(key, "key must not be null");
        Object value = variables.get(key);
        return value != null ? value : defaultValue;
    }

    @Override
    public synchronized String getString(String key, String defaultValue) {
        Object value = get(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }

    @Override
    public synchronized int getInt(String key, int defaultValue) {
        Object value = get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    @Override
    public synchronized double getDouble(String key, double defaultValue) {
        Object value = get(key);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    @Override
    public synchronized boolean getBoolean(String key, boolean defaultValue) {
        Object value = get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0.0;
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if ("true".equalsIgnoreCase(trimmed)) {
                return true;
            }
            if ("false".equalsIgnoreCase(trimmed)) {
                return false;
            }
            return !s.isEmpty();
        }
        return true;
    }

    @Override
    public synchronized boolean has(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return variables.containsKey(key);
    }

    @Override
    public synchronized Object remove(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return variables.remove(key);
    }

    @Override
    public synchronized Set<String> keys() {
        return Set.copyOf(variables.keySet());
    }

    @Override
    public synchronized Map<String, Object> snapshotMap() {
        return variables.captureState();
    }

    @Override
    public synchronized void restoreMap(Map<String, Object> snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        variables.restoreState(snapshot);
    }

    @Override
    public synchronized void blockRollback() {
        this.pendingRollbackBarrier = true;
    }

    @Override
    public synchronized boolean isRollbackBlocked() {
        if (pendingRollbackBarrier) {
            return true;
        }
        return cursorIndex >= 0
                && cursorIndex < checkpoints.size()
                && checkpoints.get(cursorIndex).rollbackBarrierBefore();
    }

    @Override
    public synchronized void clear() {
        variables.clear();
        clearCheckpoints();
    }

    private void restoreCheckpointInternal(Checkpoint checkpoint) {
        variables.restoreState(checkpoint.variablesSnapshot());
        pendingRollbackBarrier = false;
        if (runtimeRestorer != null && checkpoint.runtimeSnapshot() != null) {
            runtimeRestorer.accept(RevertableMap.cloneValue(checkpoint.runtimeSnapshot()));
        }
    }
}
