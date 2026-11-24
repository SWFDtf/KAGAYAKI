package de.swfdtf.parser;

import de.swfdtf.state.StateStore;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only, type-safe {@link Optional}-based view over a {@link StateStore}.
 *
 * <p>Holds an immutable reference to the underlying {@link StateStore} and eliminates
 * {@code null} ambiguity or silent default-value masking by returning {@link Optional#empty()}
 * when keys are missing or cannot be converted cleanly to the requested type.</p>
 */
public final class TypeSafeStateView {

    private final StateStore stateStore;

    /**
     * Wraps {@code stateStore} in a read-only type-safe view.
     *
     * @param stateStore backing state store (must not be {@code null})
     */
    public TypeSafeStateView(StateStore stateStore) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
    }

    /**
     * Reads a variable as a {@link String} using {@link String#valueOf(Object)} when present.
     *
     * @param key variable key
     * @return optional string value, or {@link Optional#empty()} if unset
     */
    public Optional<String> getString(String key) {
        Objects.requireNonNull(key, "key must not be null");
        Object value = stateStore.get(key);
        return value != null ? Optional.of(String.valueOf(value)) : Optional.empty();
    }

    /**
     * Reads a variable as an {@link Integer}, converting {@link Number} or numeric {@link String} values.
     *
     * @param key variable key
     * @return optional integer value, or {@link Optional#empty()} if unset or unparseable
     */
    public Optional<Integer> getInt(String key) {
        Objects.requireNonNull(key, "key must not be null");
        Object value = stateStore.get(key);
        if (value instanceof Number n) {
            return Optional.of(n.intValue());
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                return Optional.empty();
            }
            try {
                return Optional.of(Integer.parseInt(trimmed));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Reads a variable as a {@link Long}, converting {@link Number} or numeric {@link String} values.
     *
     * @param key variable key
     * @return optional long value, or {@link Optional#empty()} if unset or unparseable
     */
    public Optional<Long> getLong(String key) {
        Objects.requireNonNull(key, "key must not be null");
        Object value = stateStore.get(key);
        if (value instanceof Number n) {
            return Optional.of(n.longValue());
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                return Optional.empty();
            }
            try {
                return Optional.of(Long.parseLong(trimmed));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Reads a variable as a {@link Double}, converting {@link Number} or numeric {@link String} values.
     *
     * @param key variable key
     * @return optional double value, or {@link Optional#empty()} if unset or unparseable
     */
    public Optional<Double> getDouble(String key) {
        Objects.requireNonNull(key, "key must not be null");
        Object value = stateStore.get(key);
        if (value instanceof Number n) {
            return Optional.of(n.doubleValue());
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                return Optional.empty();
            }
            try {
                return Optional.of(Double.parseDouble(trimmed));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Reads a variable as a {@link Boolean}, accepting {@link Boolean}, {@link Number}
     * (non-zero is {@code true}), or {@code "true"}/{@code "false"} strings.
     *
     * @param key variable key
     * @return optional boolean value, or {@link Optional#empty()} if unset or not boolean-convertible
     */
    public Optional<Boolean> getBoolean(String key) {
        Objects.requireNonNull(key, "key must not be null");
        Object value = stateStore.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Boolean b) {
            return Optional.of(b);
        }
        if (value instanceof Number n) {
            return Optional.of(n.doubleValue() != 0.0);
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if ("true".equalsIgnoreCase(trimmed)) {
                return Optional.of(true);
            }
            if ("false".equalsIgnoreCase(trimmed)) {
                return Optional.of(false);
            }
        }
        return Optional.empty();
    }

    /**
     * Reads a variable as a {@link BigDecimal}, converting {@link BigDecimal}, {@link Number},
     * or numeric {@link String} values cleanly.
     *
     * @param key variable key
     * @return optional {@link BigDecimal} value, or {@link Optional#empty()} if unset, non-finite,
     *         or unparseable
     */
    public Optional<BigDecimal> getDecimal(String key) {
        Objects.requireNonNull(key, "key must not be null");
        Object value = stateStore.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof BigDecimal bd) {
            return Optional.of(bd);
        }
        if (value instanceof BigInteger bi) {
            return Optional.of(new BigDecimal(bi));
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return Optional.of(BigDecimal.valueOf(((Number) value).longValue()));
        }
        if (value instanceof Float || value instanceof Double) {
            double d = ((Number) value).doubleValue();
            return Double.isFinite(d) ? Optional.of(BigDecimal.valueOf(d)) : Optional.empty();
        }
        if (value instanceof Number n) {
            try {
                return Optional.of(new BigDecimal(n.toString()));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                return Optional.empty();
            }
            try {
                return Optional.of(new BigDecimal(trimmed));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Reads a variable only if its runtime value is an instance of {@code type}.
     *
     * @param key  variable key
     * @param type expected runtime class
     * @param <T>  expected value type
     * @return optional cast value, or {@link Optional#empty()} if unset or incompatible
     */
    public <T> Optional<T> getAs(String key, Class<T> type) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Object value = stateStore.get(key);
        if (type.isInstance(value)) {
            return Optional.of(type.cast(value));
        }
        return Optional.empty();
    }

    /**
     * Checks whether {@code key} is present in the underlying {@link StateStore}.
     *
     * @param key variable key
     * @return {@code true} if the key exists
     */
    public boolean has(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return stateStore.has(key);
    }

    /**
     * Returns an unmodifiable snapshot of all keys currently defined in the underlying store.
     *
     * @return unmodifiable set of variable keys
     */
    public Set<String> keys() {
        Set<String> storeKeys = stateStore.keys();
        return storeKeys != null ? Set.copyOf(storeKeys) : Set.of();
    }
}
