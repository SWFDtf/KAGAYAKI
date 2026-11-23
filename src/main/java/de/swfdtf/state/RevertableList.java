package de.swfdtf.state;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * {@link ArrayList}-backed {@link List} implementing {@link Revertable} with deep-safe
 * snapshot cloning and restoration.
 *
 * @param <E> element type
 */
public class RevertableList<E> extends AbstractList<E> implements Revertable<List<E>> {

    private final ArrayList<E> backing;

    /**
     * Creates an empty {@code RevertableList}.
     */
    public RevertableList() {
        this.backing = new ArrayList<>();
    }

    /**
     * Creates an empty {@code RevertableList} with the specified initial capacity.
     *
     * @param initialCapacity initial list capacity ({@code >= 0})
     */
    public RevertableList(int initialCapacity) {
        this.backing = new ArrayList<>(initialCapacity);
    }

    /**
     * Creates a {@code RevertableList} containing all elements from {@code initial} in iteration order.
     *
     * @param initial initial elements (must not be {@code null})
     */
    public RevertableList(Collection<? extends E> initial) {
        Objects.requireNonNull(initial, "initial must not be null");
        this.backing = new ArrayList<>(initial);
    }

    @Override
    public E get(int index) {
        return backing.get(index);
    }

    @Override
    public int size() {
        return backing.size();
    }

    @Override
    public E set(int index, E element) {
        return backing.set(index, element);
    }

    @Override
    public void add(int index, E element) {
        backing.add(index, element);
        modCount++;
    }

    @Override
    public E remove(int index) {
        E removed = backing.remove(index);
        modCount++;
        return removed;
    }

    @Override
    public void clear() {
        backing.clear();
        modCount++;
    }

    /**
     * Captures an unmodifiable deep-safe snapshot of all elements in this list.
     *
     * @return unmodifiable cloned snapshot list
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<E> captureState() {
        IdentityHashMap<Object, Object> visited = new IdentityHashMap<>();
        List<E> cloned = new ArrayList<>(backing.size());
        for (E element : backing) {
            cloned.add((E) RevertableMap.cloneValueInternal(element, visited));
        }
        return Collections.unmodifiableList(cloned);
    }

    /**
     * Replaces all elements in this list with deep-cloned copies from {@code snapshot}.
     *
     * @param snapshot previously captured snapshot list (must not be {@code null})
     */
    @Override
    @SuppressWarnings("unchecked")
    public void restoreState(List<E> snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        IdentityHashMap<Object, Object> visited = new IdentityHashMap<>();
        List<E> restored = new ArrayList<>(snapshot.size());
        for (E element : snapshot) {
            restored.add((E) RevertableMap.cloneValueInternal(element, visited));
        }
        backing.clear();
        backing.addAll(restored);
        modCount++;
    }
}
