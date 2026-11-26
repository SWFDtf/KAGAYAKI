package de.swfdtf.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Tracks character affinity / trust scores, resolves qualitative {@link RelationshipTier}
 * levels from point thresholds, and notifies listeners upon score changes.
 *
 * <p>Can operate standalone with an internal in-memory map or synchronized directly against
 * a {@link StateStore} under keys {@code "affinity.<characterId>"} so affinity values participate
 * seamlessly in checkpoints, rollback, and save slots.</p>
 *
 * <p>Thread-safe across script execution, rollback, and UI HUD threads.</p>
 */
public class RelationshipAffinityTracker {

    /** Key prefix used when storing affinity scores inside a {@link StateStore}. */
    public static final String KEY_PREFIX = "affinity.";

    /**
     * Qualitative relationship tiers derived from numeric affinity points.
     *
     * <ul>
     *   <li>{@link #HOSTILE}: {@code points < 0}</li>
     *   <li>{@link #STRANGER}: {@code 0 <= points < 10}</li>
     *   <li>{@link #ACQUAINTANCE}: {@code 10 <= points < 25}</li>
     *   <li>{@link #FRIEND}: {@code 25 <= points < 50}</li>
     *   <li>{@link #TRUSTED}: {@code 50 <= points < 80}</li>
     *   <li>{@link #DEVOTED}: {@code points >= 80}</li>
     * </ul>
     */
    public enum RelationshipTier {
        /** Negative affinity ({@code points < 0}). */
        HOSTILE(Integer.MIN_VALUE),
        /** Initial neutral tier ({@code 0 <= points < 10}). */
        STRANGER(0),
        /** Casual acquaintance tier ({@code 10 <= points < 25}). */
        ACQUAINTANCE(10),
        /** Friendly tier ({@code 25 <= points < 50}). */
        FRIEND(25),
        /** Trusted ally tier ({@code 50 <= points < 80}). */
        TRUSTED(50),
        /** Highest devotion tier ({@code points >= 80}). */
        DEVOTED(80);

        private final int minPoints;

        RelationshipTier(int minPoints) {
            this.minPoints = minPoints;
        }

        /**
         * Returns the inclusive minimum point threshold for this tier.
         *
         * @return inclusive minimum affinity points
         */
        public int getMinPoints() {
            return minPoints;
        }

        /**
         * Resolves the {@link RelationshipTier} corresponding to the given point total.
         *
         * @param points numeric affinity score
         * @return matching {@link RelationshipTier}
         */
        public static RelationshipTier fromPoints(int points) {
            if (points < STRANGER.minPoints) {
                return HOSTILE;
            }
            if (points < ACQUAINTANCE.minPoints) {
                return STRANGER;
            }
            if (points < FRIEND.minPoints) {
                return ACQUAINTANCE;
            }
            if (points < TRUSTED.minPoints) {
                return FRIEND;
            }
            if (points < DEVOTED.minPoints) {
                return TRUSTED;
            }
            return DEVOTED;
        }
    }

    /**
     * Immutable event emitted when a character's affinity score changes.
     *
     * @param characterId    character identifier (non-null, non-blank)
     * @param previousPoints affinity score before the update
     * @param newPoints      affinity score after the update
     * @param previousTier   tier before the update
     * @param newTier        tier after the update
     */
    public record AffinityChangeEvent(
            String characterId,
            int previousPoints,
            int newPoints,
            RelationshipTier previousTier,
            RelationshipTier newTier
    ) {
        /**
         * Validates event payload fields.
         */
        public AffinityChangeEvent {
            Objects.requireNonNull(characterId, "characterId must not be null");
            if (characterId.isBlank()) {
                throw new IllegalArgumentException("characterId must not be blank");
            }
            Objects.requireNonNull(previousTier, "previousTier must not be null");
            Objects.requireNonNull(newTier, "newTier must not be null");
        }
    }

    private final StateStore backingStore;
    private final Map<String, Integer> inMemoryAffinities;
    private final List<Consumer<AffinityChangeEvent>> listeners = new ArrayList<>();

    /**
     * Creates a standalone in-memory {@link RelationshipAffinityTracker}.
     */
    public RelationshipAffinityTracker() {
        this.backingStore = null;
        this.inMemoryAffinities = new LinkedHashMap<>();
    }

    /**
     * Creates a {@link RelationshipAffinityTracker} synchronized with {@code backingStore}
     * using keys {@code "affinity.<characterId>"}.
     *
     * @param backingStore state store used for persistence and rollback (must not be {@code null})
     */
    public RelationshipAffinityTracker(StateStore backingStore) {
        this.backingStore = Objects.requireNonNull(backingStore, "backingStore must not be null");
        this.inMemoryAffinities = null;
    }

    /**
     * Adds {@code delta} (positive or negative) to the character's current affinity score,
     * clamping on 32-bit overflow, and notifies listeners if the score changed.
     *
     * @param characterId character identifier (must not be {@code null} or blank)
     * @param delta       points to add or subtract
     * @return updated affinity score
     */
    public int addPoints(String characterId, int delta) {
        String id = requireNonBlank(characterId, "characterId");
        AffinityChangeEvent event = null;
        List<Consumer<AffinityChangeEvent>> listenersSnapshot = null;
        int updated;
        synchronized (this) {
            int previous = readPoints(id);
            long sum = (long) previous + delta;
            updated = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, sum));
            writePoints(id, updated);
            if (updated != previous && !listeners.isEmpty()) {
                event = new AffinityChangeEvent(
                        id,
                        previous,
                        updated,
                        RelationshipTier.fromPoints(previous),
                        RelationshipTier.fromPoints(updated)
                );
                listenersSnapshot = List.copyOf(listeners);
            }
        }
        notifyListeners(event, listenersSnapshot);
        return updated;
    }

    /**
     * Sets a character's affinity score to {@code points} and notifies listeners if the score changed.
     *
     * @param characterId character identifier (must not be {@code null} or blank)
     * @param points      target affinity score
     */
    public void setPoints(String characterId, int points) {
        String id = requireNonBlank(characterId, "characterId");
        AffinityChangeEvent event = null;
        List<Consumer<AffinityChangeEvent>> listenersSnapshot = null;
        synchronized (this) {
            int previous = readPoints(id);
            writePoints(id, points);
            if (points != previous && !listeners.isEmpty()) {
                event = new AffinityChangeEvent(
                        id,
                        previous,
                        points,
                        RelationshipTier.fromPoints(previous),
                        RelationshipTier.fromPoints(points)
                );
                listenersSnapshot = List.copyOf(listeners);
            }
        }
        notifyListeners(event, listenersSnapshot);
    }

    /**
     * Returns the current affinity points for {@code characterId}, defaulting to {@code 0}.
     *
     * @param characterId character identifier (must not be {@code null} or blank)
     * @return current affinity points
     */
    public synchronized int getPoints(String characterId) {
        String id = requireNonBlank(characterId, "characterId");
        return readPoints(id);
    }

    /**
     * Returns the current {@link RelationshipTier} for {@code characterId}.
     *
     * @param characterId character identifier (must not be {@code null} or blank)
     * @return current {@link RelationshipTier}
     */
    public synchronized RelationshipTier getTier(String characterId) {
        return RelationshipTier.fromPoints(getPoints(characterId));
    }

    /**
     * Returns an unmodifiable snapshot map of all tracked character IDs to their affinity points.
     *
     * @return unmodifiable map of character IDs to affinity points
     */
    public synchronized Map<String, Integer> getAllAffinities() {
        if (backingStore == null) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(inMemoryAffinities));
        }
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        for (String key : backingStore.keys()) {
            if (key != null && key.startsWith(KEY_PREFIX) && key.length() > KEY_PREFIX.length()) {
                String charId = key.substring(KEY_PREFIX.length());
                snapshot.put(charId, backingStore.getInt(key, 0));
            }
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Registers a listener notified whenever a character's affinity points change.
     *
     * @param listener change event consumer (must not be {@code null})
     */
    public synchronized void addListener(Consumer<AffinityChangeEvent> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Unregisters a previously added affinity change listener.
     *
     * @param listener listener to remove (must not be {@code null})
     */
    public synchronized void removeListener(Consumer<AffinityChangeEvent> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.remove(listener);
    }

    private int readPoints(String characterId) {
        if (backingStore != null) {
            return backingStore.getInt(KEY_PREFIX + characterId, 0);
        }
        return inMemoryAffinities.getOrDefault(characterId, 0);
    }

    private void writePoints(String characterId, int points) {
        if (backingStore != null) {
            backingStore.set(KEY_PREFIX + characterId, points);
        } else {
            inMemoryAffinities.put(characterId, points);
        }
    }

    private static void notifyListeners(
            AffinityChangeEvent event,
            List<Consumer<AffinityChangeEvent>> listenersSnapshot
    ) {
        if (event == null || listenersSnapshot == null) {
            return;
        }
        for (Consumer<AffinityChangeEvent> listener : listenersSnapshot) {
            listener.accept(event);
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return trimmed;
    }
}
