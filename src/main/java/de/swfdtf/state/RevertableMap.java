package de.swfdtf.state;

import de.swfdtf.math.Transform2D;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * {@link LinkedHashMap}-backed {@link Map} implementing {@link Revertable} with deep-safe
 * snapshot cloning and restoration.
 *
 * <p>Provides {@link #cloneValue(Object)} to safely isolate {@link Revertable} containers,
 * {@link List}, {@link Map}, {@link Set}, primitive and object arrays, {@link Transform2D},
 * and {@link Serializable} values while safely preserving references to custom non-{@link Serializable}
 * {@code @->{ ... }<<} POJOs without throwing.</p>
 *
 * @param <K> key type
 * @param <V> value type
 */
public class RevertableMap<K, V> extends AbstractMap<K, V> implements Revertable<Map<K, V>> {

    private final Map<K, V> backing = new LinkedHashMap<>();

    /**
     * Creates an empty {@code RevertableMap}.
     */
    public RevertableMap() {
    }

    /**
     * Creates a {@code RevertableMap} initialized with all entries from {@code initial}.
     *
     * @param initial initial map entries (must not be {@code null})
     */
    public RevertableMap(Map<? extends K, ? extends V> initial) {
        Objects.requireNonNull(initial, "initial must not be null");
        backing.putAll(initial);
    }

    @Override
    public int size() {
        return backing.size();
    }

    @Override
    public boolean isEmpty() {
        return backing.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return backing.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return backing.containsValue(value);
    }

    @Override
    public V get(Object key) {
        return backing.get(key);
    }

    @Override
    public V put(K key, V value) {
        return backing.put(key, value);
    }

    @Override
    public V remove(Object key) {
        return backing.remove(key);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        Objects.requireNonNull(m, "map must not be null");
        backing.putAll(m);
    }

    @Override
    public void clear() {
        backing.clear();
    }

    @Override
    public Set<K> keySet() {
        return backing.keySet();
    }

    @Override
    public Collection<V> values() {
        return backing.values();
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return backing.entrySet();
    }

    /**
     * Captures an unmodifiable deep-safe snapshot of all keys and values currently in this map.
     *
     * @return unmodifiable snapshot map preserving insertion order
     */
    @Override
    @SuppressWarnings("unchecked")
    public Map<K, V> captureState() {
        IdentityHashMap<Object, Object> visited = new IdentityHashMap<>();
        Map<K, V> cloned = new LinkedHashMap<>(Math.max(16, backing.size() * 2));
        for (Entry<K, V> entry : backing.entrySet()) {
            K clonedKey = (K) cloneValueInternal(entry.getKey(), visited);
            V clonedVal = (V) cloneValueInternal(entry.getValue(), visited);
            cloned.put(clonedKey, clonedVal);
        }
        return Collections.unmodifiableMap(cloned);
    }

    /**
     * Replaces all entries in this map with deep-cloned copies from {@code snapshot}.
     *
     * @param snapshot previously captured snapshot map (must not be {@code null})
     */
    @Override
    @SuppressWarnings("unchecked")
    public void restoreState(Map<K, V> snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        IdentityHashMap<Object, Object> visited = new IdentityHashMap<>();
        Map<K, V> restored = new LinkedHashMap<>(Math.max(16, snapshot.size() * 2));
        for (Entry<? extends K, ? extends V> entry : snapshot.entrySet()) {
            K clonedKey = (K) cloneValueInternal(entry.getKey(), visited);
            V clonedVal = (V) cloneValueInternal(entry.getValue(), visited);
            restored.put(clonedKey, clonedVal);
        }
        backing.clear();
        backing.putAll(restored);
    }

    /**
     * Deep-clones an arbitrary state value safely.
     *
     * <p>Handles {@code null}, immutable primitives/JDK value objects, {@link Revertable} containers
     * and custom {@link Revertable} objects, {@link List}, {@link Map}, {@link Set}, primitive and
     * object arrays, {@link Transform2D}, and {@link Serializable} objects. Non-{@link Serializable}
     * custom POJOs (including {@code @->{ ... }<<} classes) and objects whose serialization fails
     * are preserved by reference without throwing.</p>
     *
     * @param value source value (may be {@code null})
     * @return deep-cloned value, or original reference for immutable / non-serializable POJOs
     */
    public static Object cloneValue(Object value) {
        return cloneValueInternal(value, new IdentityHashMap<>());
    }

    /**
     * Deep-compares two arbitrary state values, including array contents and nested collections.
     *
     * @param a first value (may be {@code null})
     * @param b second value (may be {@code null})
     * @return {@code true} if both values are structurally equal
     */
    public static boolean deepEquals(Object a, Object b) {
        return deepEqualsInternal(a, b, new IdentityHashMap<>());
    }

    @SuppressWarnings("unchecked")
    static Object cloneValueInternal(Object value, IdentityHashMap<Object, Object> visited) {
        if (value == null || isKnownImmutable(value)) {
            return value;
        }
        if (visited.containsKey(value)) {
            return visited.get(value);
        }

        if (value instanceof RevertableMap<?, ?> revertableMap) {
            RevertableMap<Object, Object> copy = new RevertableMap<>();
            visited.put(value, copy);
            for (Entry<?, ?> entry : revertableMap.entrySet()) {
                copy.put(
                        cloneValueInternal(entry.getKey(), visited),
                        cloneValueInternal(entry.getValue(), visited)
                );
            }
            return copy;
        }

        if (value instanceof RevertableList<?> revertableList) {
            RevertableList<Object> copy = new RevertableList<>(revertableList.size());
            visited.put(value, copy);
            for (Object element : revertableList) {
                copy.add(cloneValueInternal(element, visited));
            }
            return copy;
        }

        if (value instanceof Revertable<?> revertable) {
            return cloneGenericRevertable((Revertable<Object>) revertable, visited);
        }

        if (value instanceof Transform2D transform) {
            Transform2D copy = transform.copy();
            visited.put(value, copy);
            return copy;
        }

        Class<?> clazz = value.getClass();
        if (clazz.isArray()) {
            return cloneArray(value, clazz, visited);
        }

        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            visited.put(value, copy);
            for (Object element : list) {
                copy.add(cloneValueInternal(element, visited));
            }
            return copy;
        }

        if (value instanceof Set<?> set) {
            Set<Object> copy = new LinkedHashSet<>(Math.max(16, set.size() * 2));
            visited.put(value, copy);
            for (Object element : set) {
                copy.add(cloneValueInternal(element, visited));
            }
            return copy;
        }

        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>(Math.max(16, map.size() * 2));
            visited.put(value, copy);
            for (Entry<?, ?> entry : map.entrySet()) {
                copy.put(
                        cloneValueInternal(entry.getKey(), visited),
                        cloneValueInternal(entry.getValue(), visited)
                );
            }
            return copy;
        }

        if (value instanceof Serializable serializable) {
            Object serializedCopy = tryCloneSerializable(serializable);
            if (serializedCopy != null) {
                visited.put(value, serializedCopy);
                return serializedCopy;
            }
        }

        // Custom non-Serializable POJO (e.g. @->{ ... }<< user class): keep reference safely.
        return value;
    }

    private static Object cloneGenericRevertable(
            Revertable<Object> revertable,
            IdentityHashMap<Object, Object> visited
    ) {
        Object captured = revertable.captureState();
        Object clonedSnapshot = cloneValueInternal(captured, visited);
        try {
            Constructor<?> ctor = revertable.getClass().getDeclaredConstructor();
            ctor.trySetAccessible();
            @SuppressWarnings("unchecked")
            Revertable<Object> copy = (Revertable<Object>) ctor.newInstance();
            visited.put(revertable, copy);
            copy.restoreState(clonedSnapshot);
            return copy;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Fall through to Serializable cloning or safe reference fallback
        }
        if (revertable instanceof Serializable serializable) {
            Object serializedCopy = tryCloneSerializable(serializable);
            if (serializedCopy != null) {
                visited.put(revertable, serializedCopy);
                return serializedCopy;
            }
        }
        return revertable;
    }

    private static Object cloneArray(
            Object array,
            Class<?> arrayClass,
            IdentityHashMap<Object, Object> visited
    ) {
        if (array instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (array instanceof short[] shorts) {
            return shorts.clone();
        }
        if (array instanceof int[] ints) {
            return ints.clone();
        }
        if (array instanceof long[] longs) {
            return longs.clone();
        }
        if (array instanceof float[] floats) {
            return floats.clone();
        }
        if (array instanceof double[] doubles) {
            return doubles.clone();
        }
        if (array instanceof boolean[] booleans) {
            return booleans.clone();
        }
        if (array instanceof char[] chars) {
            return chars.clone();
        }
        int len = Array.getLength(array);
        Object copy = Array.newInstance(arrayClass.getComponentType(), len);
        visited.put(array, copy);
        for (int i = 0; i < len; i++) {
            Array.set(copy, i, cloneValueInternal(Array.get(array, i), visited));
        }
        return copy;
    }

    private static Object tryCloneSerializable(Serializable serializable) {
        ClassLoader sourceLoader = serializable.getClass().getClassLoader();
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
                oos.writeObject(serializable);
            }
            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray())) {
                @Override
                protected Class<?> resolveClass(ObjectStreamClass desc)
                        throws IOException, ClassNotFoundException {
                    if (sourceLoader != null) {
                        try {
                            return Class.forName(desc.getName(), false, sourceLoader);
                        } catch (ClassNotFoundException ignored) {
                            // Fall back to default ObjectInputStream resolution
                        }
                    }
                    return super.resolveClass(desc);
                }
            }) {
                return ois.readObject();
            }
        } catch (IOException | ClassNotFoundException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static boolean isKnownImmutable(Object value) {
        return value instanceof String
                || value instanceof Number && (
                        value instanceof Integer
                                || value instanceof Long
                                || value instanceof Double
                                || value instanceof Float
                                || value instanceof Short
                                || value instanceof Byte
                                || value instanceof BigInteger
                                || value instanceof BigDecimal
                )
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum<?>
                || value instanceof UUID
                || value instanceof Instant
                || value instanceof LocalDate
                || value instanceof LocalTime
                || value instanceof LocalDateTime
                || value instanceof OffsetDateTime
                || value instanceof ZonedDateTime
                || value instanceof Duration
                || value instanceof Period
                || value instanceof Locale
                || value instanceof Path
                || value instanceof Class<?>;
    }

    private static boolean deepEqualsInternal(
            Object a,
            Object b,
            IdentityHashMap<Object, Set<Object>> seenPairs
    ) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.getClass().isArray() && b.getClass().isArray()) {
            if (a.getClass() != b.getClass()) {
                return false;
            }
            if (a instanceof byte[] ba && b instanceof byte[] bb) {
                return Arrays.equals(ba, bb);
            }
            if (a instanceof short[] sa && b instanceof short[] sb) {
                return Arrays.equals(sa, sb);
            }
            if (a instanceof int[] ia && b instanceof int[] ib) {
                return Arrays.equals(ia, ib);
            }
            if (a instanceof long[] la && b instanceof long[] lb) {
                return Arrays.equals(la, lb);
            }
            if (a instanceof float[] fa && b instanceof float[] fb) {
                return Arrays.equals(fa, fb);
            }
            if (a instanceof double[] da && b instanceof double[] db) {
                return Arrays.equals(da, db);
            }
            if (a instanceof boolean[] ba && b instanceof boolean[] bb) {
                return Arrays.equals(ba, bb);
            }
            if (a instanceof char[] ca && b instanceof char[] cb) {
                return Arrays.equals(ca, cb);
            }
            int len = Array.getLength(a);
            if (len != Array.getLength(b)) {
                return false;
            }
            if (!markPairVisited(a, b, seenPairs)) {
                return true;
            }
            for (int i = 0; i < len; i++) {
                if (!deepEqualsInternal(Array.get(a, i), Array.get(b, i), seenPairs)) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (ma.size() != mb.size()) {
                return false;
            }
            if (!markPairVisited(a, b, seenPairs)) {
                return true;
            }
            for (Entry<?, ?> entry : ma.entrySet()) {
                Object key = entry.getKey();
                if (!mb.containsKey(key)) {
                    return false;
                }
                if (!deepEqualsInternal(entry.getValue(), mb.get(key), seenPairs)) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) {
                return false;
            }
            if (!markPairVisited(a, b, seenPairs)) {
                return true;
            }
            Iterator<?> itA = la.iterator();
            Iterator<?> itB = lb.iterator();
            while (itA.hasNext()) {
                if (!deepEqualsInternal(itA.next(), itB.next(), seenPairs)) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Set<?> sa && b instanceof Set<?> sb) {
            if (sa.size() != sb.size()) {
                return false;
            }
            return sa.equals(sb);
        }
        return Objects.equals(a, b);
    }

    private static boolean markPairVisited(
            Object a,
            Object b,
            IdentityHashMap<Object, Set<Object>> seenPairs
    ) {
        Set<Object> mates = seenPairs.computeIfAbsent(a,
                k -> Collections.newSetFromMap(new IdentityHashMap<>()));
        return mates.add(b);
    }
}
