package de.swfdtf.state;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Contract for the mutable Visual Novel variable store exposed as {@code state} inside
 * {@code @->{ ... }<<} Java blocks and script expressions.
 *
 * <p>In M3 this is backed by {@link SimpleStateStore}; in M4 it is backed by
 * {@code RevertableStateStore} with full deterministic rollback and roll-forward.</p>
 */
public interface StateStore {

    /**
     * Binds or overwrites a named variable. Passing {@code null} removes or clears the variable.
     *
     * @param key   variable name (must not be {@code null})
     * @param value variable value
     */
    void set(String key, Object value);

    /**
     * Returns the raw value associated with {@code key}, or {@code null} if unset.
     *
     * @param key variable name
     * @return stored value or {@code null}
     */
    Object get(String key);

    /**
     * Returns the stored value or {@code defaultValue} if {@code key} is unset or {@code null}.
     *
     * @param key          variable name
     * @param defaultValue fallback value
     * @return stored or fallback value
     */
    Object getOrDefault(String key, Object defaultValue);

    /**
     * Returns the string representation of {@code key}, or {@code defaultValue} if unset.
     *
     * @param key          variable name
     * @param defaultValue fallback string
     * @return string value
     */
    String getString(String key, String defaultValue);

    /**
     * Returns an integer value for {@code key}, coercing {@link Number} or numeric strings when possible.
     *
     * @param key          variable name
     * @param defaultValue fallback integer
     * @return integer value
     */
    int getInt(String key, int defaultValue);

    /**
     * Returns a double value for {@code key}, coercing {@link Number} or numeric strings when possible.
     *
     * @param key          variable name
     * @param defaultValue fallback double
     * @return double value
     */
    double getDouble(String key, double defaultValue);

    /**
     * Returns a boolean value for {@code key}, supporting truthy numbers/strings/collections.
     *
     * @param key          variable name
     * @param defaultValue fallback boolean
     * @return boolean value
     */
    boolean getBoolean(String key, boolean defaultValue);

    /**
     * Returns whether {@code key} exists in this store.
     *
     * @param key variable name
     * @return {@code true} if present
     */
    boolean has(String key);

    /**
     * Removes a variable from the store.
     *
     * @param key variable name
     * @return previous value or {@code null}
     */
    Object remove(String key);

    /**
     * Returns an immutable snapshot of all defined variable keys.
     *
     * @return key set
     */
    Set<String> keys();

    /**
     * Produces a shallow/deep-safe snapshot map of all entries for checkpointing.
     *
     * @return snapshot map
     */
    Map<String, Object> snapshotMap();

    /**
     * Replaces current variables with the contents of {@code snapshot}.
     *
     * @param snapshot previously captured map
     */
    void restoreMap(Map<String, Object> snapshot);

    /**
     * Marks an irreversible point-of-no-return barrier preventing rollback past this step.
     */
    void blockRollback();

    /**
     * Returns whether rollback has been blocked at the current checkpoint boundary.
     *
     * @return {@code true} if rollback is blocked
     */
    boolean isRollbackBlocked();

    /**
     * Executes a multi-variable mutation block atomically against this store ({@code #0532}).
     * If {@code block} throws a {@link RuntimeException}, all intermediate changes made inside
     * the transaction are rolled back using a pre-transaction snapshot before rethrowing.
     *
     * @param block batch operation receiving this store (must not be {@code null})
     */
    default void transaction(Consumer<StateStore> block) {
        Objects.requireNonNull(block, "block must not be null");
        Map<String, Object> before = snapshotMap();
        try {
            block.accept(this);
        } catch (RuntimeException ex) {
            restoreMap(before);
            throw ex;
        }
    }

    /**
     * Clears all stored variables.
     */
    void clear();
}
