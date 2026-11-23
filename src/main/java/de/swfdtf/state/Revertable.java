package de.swfdtf.state;

/**
 * Contract for mutable state containers and domain objects that support deterministic
 * checkpoint capture and rollback restoration.
 *
 * @param <S> immutable or isolated snapshot type representing the captured state
 */
public interface Revertable<S> {

    /**
     * Captures an isolated snapshot of this object's current state so subsequent mutations
     * do not affect the returned snapshot.
     *
     * @return captured state snapshot
     */
    S captureState();

    /**
     * Restores this object's internal state from a previously captured {@code snapshot}
     * without mutating the snapshot itself.
     *
     * @param snapshot previously captured state snapshot (must not be {@code null})
     */
    void restoreState(S snapshot);
}
