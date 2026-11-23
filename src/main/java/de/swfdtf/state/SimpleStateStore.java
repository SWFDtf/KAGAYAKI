package de.swfdtf.state;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lightweight thread-safe in-memory {@link StateStore} implementation used during M3 script
 * execution and standalone unit tests prior to attaching full M4 {@code RevertableStateStore}.
 */
public class SimpleStateStore implements StateStore {

    private final Map<String, Object> variables = new LinkedHashMap<>();
    private boolean rollbackBlocked = false;

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
            if ("true".equalsIgnoreCase(s.trim())) {
                return true;
            }
            if ("false".equalsIgnoreCase(s.trim())) {
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
        return Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    @Override
    public synchronized void restoreMap(Map<String, Object> snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        variables.clear();
        variables.putAll(snapshot);
    }

    @Override
    public synchronized void blockRollback() {
        this.rollbackBlocked = true;
    }

    @Override
    public synchronized boolean isRollbackBlocked() {
        return rollbackBlocked;
    }

    /**
     * Resets the rollback-blocked flag (used when starting a new session or test).
     */
    public synchronized void resetRollbackBlock() {
        this.rollbackBlocked = false;
    }

    @Override
    public synchronized void clear() {
        variables.clear();
        rollbackBlocked = false;
    }
}
