package de.swfdtf.tools;

import de.swfdtf.state.RevertableMap;
import de.swfdtf.state.SaveSlotData;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Computes structured, deterministic diffs between two VN state variable maps or
 * {@link SaveSlotData} snapshots.
 */
public final class StateSnapshotDiffService {

    /**
     * Represents a value change for a key present in both compared snapshots.
     *
     * @param key         variable key (must not be {@code null})
     * @param beforeValue value in the baseline snapshot (may be {@code null})
     * @param afterValue  value in the updated snapshot (may be {@code null})
     */
    public record ValueChange(String key, Object beforeValue, Object afterValue) {

        /**
         * Validates that {@code key} is non-null.
         */
        public ValueChange {
            Objects.requireNonNull(key, "key must not be null");
        }
    }

    /**
     * Immutable diff between two state snapshots partitioned into added, removed, and modified keys.
     *
     * @param added    unmodifiable sorted map of keys introduced in {@code after}
     * @param removed  unmodifiable sorted map of keys removed from {@code before}
     * @param modified unmodifiable sorted map of keys whose values changed
     */
    public record StateDiff(
            Map<String, Object> added,
            Map<String, Object> removed,
            Map<String, ValueChange> modified
    ) {

        /**
         * Defensively copies and enforces unmodifiable sorted maps.
         */
        public StateDiff {
            added = copySortedUnmodifiable(added, "added");
            removed = copySortedUnmodifiable(removed, "removed");
            modified = copySortedUnmodifiable(modified, "modified");
        }

        /**
         * Returns {@code true} if no keys were added, removed, or modified.
         *
         * @return {@code true} when there are zero differences
         */
        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && modified.isEmpty();
        }

        /**
         * Returns the total number of changed keys across added, removed, and modified maps.
         *
         * @return sum of added, removed, and modified key counts
         */
        public int totalChanges() {
            return added.size() + removed.size() + modified.size();
        }

        /**
         * Formats a deterministic, human-readable summary of all state differences.
         *
         * @return formatted diff report string
         */
        public String formatSummary() {
            if (isEmpty()) {
                return "No state changes (0 total).";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("State changes: ")
                    .append(totalChanges())
                    .append(" total (added=")
                    .append(added.size())
                    .append(", removed=")
                    .append(removed.size())
                    .append(", modified=")
                    .append(modified.size())
                    .append(')');

            for (Map.Entry<String, Object> entry : added.entrySet()) {
                sb.append("\n+ ")
                        .append(entry.getKey())
                        .append(" = ")
                        .append(formatValue(entry.getValue()));
            }
            for (Map.Entry<String, Object> entry : removed.entrySet()) {
                sb.append("\n- ")
                        .append(entry.getKey())
                        .append(" = ")
                        .append(formatValue(entry.getValue()));
            }
            for (Map.Entry<String, ValueChange> entry : modified.entrySet()) {
                ValueChange change = entry.getValue();
                sb.append("\n~ ")
                        .append(change.key())
                        .append(": ")
                        .append(formatValue(change.beforeValue()))
                        .append(" -> ")
                        .append(formatValue(change.afterValue()));
            }
            return sb.toString();
        }
    }

    /**
     * Creates a new {@code StateSnapshotDiffService}.
     */
    public StateSnapshotDiffService() {
    }

    /**
     * Computes a structural diff between two variable maps using deep equality semantics for
     * collections, maps, and arrays.
     *
     * @param before baseline variable map (must not be {@code null})
     * @param after  updated variable map (must not be {@code null})
     * @return immutable {@link StateDiff}
     */
    public StateDiff diff(Map<String, Object> before, Map<String, Object> after) {
        Objects.requireNonNull(before, "before must not be null");
        Objects.requireNonNull(after, "after must not be null");

        TreeSet<String> allKeys = new TreeSet<>();
        for (String key : before.keySet()) {
            allKeys.add(Objects.requireNonNull(key, "before map key must not be null"));
        }
        for (String key : after.keySet()) {
            allKeys.add(Objects.requireNonNull(key, "after map key must not be null"));
        }

        Map<String, Object> added = new TreeMap<>();
        Map<String, Object> removed = new TreeMap<>();
        Map<String, ValueChange> modified = new TreeMap<>();

        for (String key : allKeys) {
            boolean inBefore = before.containsKey(key);
            boolean inAfter = after.containsKey(key);
            if (!inBefore && inAfter) {
                added.put(key, after.get(key));
            } else if (inBefore && !inAfter) {
                removed.put(key, before.get(key));
            } else {
                Object beforeVal = before.get(key);
                Object afterVal = after.get(key);
                if (!RevertableMap.deepEquals(beforeVal, afterVal)) {
                    modified.put(key, new ValueChange(key, beforeVal, afterVal));
                }
            }
        }

        return new StateDiff(added, removed, modified);
    }

    /**
     * Computes the variable diff between two {@link SaveSlotData} snapshots.
     *
     * @param before baseline save slot snapshot (must not be {@code null})
     * @param after  updated save slot snapshot (must not be {@code null})
     * @return immutable {@link StateDiff} comparing {@code before.variables()} and {@code after.variables()}
     */
    public StateDiff diff(SaveSlotData before, SaveSlotData after) {
        Objects.requireNonNull(before, "before must not be null");
        Objects.requireNonNull(after, "after must not be null");
        Map<String, Object> beforeVars = before.variables() != null ? before.variables() : Map.of();
        Map<String, Object> afterVars = after.variables() != null ? after.variables() : Map.of();
        return diff(beforeVars, afterVars);
    }

    private static <V> Map<String, V> copySortedUnmodifiable(Map<String, V> source, String fieldName) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        TreeMap<String, V> sorted = new TreeMap<>();
        for (Map.Entry<String, V> entry : source.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), fieldName + " key must not be null");
            sorted.put(key, entry.getValue());
        }
        return Collections.unmodifiableMap(sorted);
    }

    private static String formatValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof byte[] arr) {
            return Arrays.toString(arr);
        }
        if (value instanceof short[] arr) {
            return Arrays.toString(arr);
        }
        if (value instanceof int[] arr) {
            return Arrays.toString(arr);
        }
        if (value instanceof long[] arr) {
            return Arrays.toString(arr);
        }
        if (value instanceof float[] arr) {
            return Arrays.toString(arr);
        }
        if (value instanceof double[] arr) {
            return Arrays.toString(arr);
        }
        if (value instanceof boolean[] arr) {
            return Arrays.toString(arr);
        }
        if (value instanceof char[] arr) {
            return Arrays.toString(arr);
        }
        if (value instanceof Object[] arr) {
            return Arrays.deepToString(arr);
        }
        return String.valueOf(value);
    }
}
