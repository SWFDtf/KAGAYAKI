package de.swfdtf.state;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Slim SPI interface (without {@code I}-prefix) decoupling persistent achievements from external
 * platform backends such as Steamworks, Epic Online Services, or local offline storage ({@code #0554}).
 */
public interface AchievementBackend {

    /**
     * Returns the human-readable backend identifier (e.g. {@code "offline"}, {@code "steamworks"}).
     *
     * @return backend name
     */
    String backendName();

    /**
     * Returns whether this backend is currently initialized and able to accept unlock sync calls.
     *
     * @return {@code true} when backend is ready
     */
    boolean isAvailable();

    /**
     * Synchronizes an achievement unlock event with the backend.
     *
     * @param achievementId unique achievement identifier
     * @param unlockedAt    UTC timestamp when unlocked
     * @return {@code true} if the sync succeeded or was already recorded
     */
    boolean syncUnlock(String achievementId, Instant unlockedAt);

    /**
     * Resets achievement state on the backend when supported (primarily for testing).
     *
     * @return {@code true} if reset succeeded
     */
    boolean resetAll();

    /**
     * Pure-Java offline {@link AchievementBackend} requiring zero native libraries.
     */
    final class OfflineAchievementBackend implements AchievementBackend {

        private final Map<String, Instant> synced = new LinkedHashMap<>();

        /**
         * Creates a new {@code OfflineAchievementBackend}.
         */
        public OfflineAchievementBackend() {
        }

        @Override
        public String backendName() {
            return "offline";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public synchronized boolean syncUnlock(String achievementId, Instant unlockedAt) {
            Objects.requireNonNull(achievementId, "achievementId must not be null");
            if (achievementId.isBlank()) {
                throw new IllegalArgumentException("achievementId must not be blank");
            }
            Instant timestamp = unlockedAt != null ? unlockedAt : Instant.now();
            synced.putIfAbsent(achievementId, timestamp);
            return true;
        }

        @Override
        public synchronized boolean resetAll() {
            synced.clear();
            return true;
        }

        /**
         * Returns an immutable view of achievement IDs synced through this backend.
         *
         * @return map of achievement ID to unlock timestamp
         */
        public synchronized Map<String, Instant> getSyncedUnlocks() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(synced));
        }
    }
}
