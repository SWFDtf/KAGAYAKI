package de.swfdtf.state;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Manages achievement definitions, persistent unlock status via {@link PersistentAccess},
 * toast notification queuing, and optional real-time unlock callbacks.
 *
 * <p>Thread-safe across gameplay, script runtime, and UI rendering threads. Duplicate
 * unlock requests for an already unlocked achievement are ignored so toast notifications
 * are never emitted more than once across sessions.</p>
 */
public class AchievementManager {

    /**
     * Immutable definition of a game achievement.
     *
     * @param id          unique achievement identifier (non-null, non-blank)
     * @param title       display title shown in UI and toast popups
     * @param description achievement description or unlock criteria
     * @param iconPath    relative asset path to the achievement icon
     * @param hidden      whether details should be concealed in UI until unlocked
     */
    public record AchievementDef(
            String id,
            String title,
            String description,
            String iconPath,
            boolean hidden
    ) {
        /**
         * Validates and normalizes achievement metadata fields.
         */
        public AchievementDef {
            Objects.requireNonNull(id, "id must not be null");
            if (id.isBlank()) {
                throw new IllegalArgumentException("achievement id must not be blank");
            }
            title = title != null ? title : id;
            description = description != null ? description : "";
            iconPath = iconPath != null ? iconPath : "";
        }
    }

    /**
     * Queued notification payload emitted when an achievement is newly unlocked.
     *
     * @param achievement metadata of the unlocked achievement
     * @param unlockedAt  UTC timestamp when the unlock occurred
     */
    public record AchievementToast(
            AchievementDef achievement,
            Instant unlockedAt
    ) {
        /**
         * Ensures toast payload integrity.
         */
        public AchievementToast {
            Objects.requireNonNull(achievement, "achievement must not be null");
            unlockedAt = unlockedAt != null ? unlockedAt : Instant.now();
        }
    }

    private final PersistentAccess persistentAccess;
    private final Map<String, AchievementDef> definitions = new LinkedHashMap<>();
    private final Deque<AchievementToast> toastQueue = new ArrayDeque<>();
    private Consumer<AchievementToast> onUnlockListener;
    private AchievementBackend backend = new AchievementBackend.OfflineAchievementBackend();

    /**
     * Creates an {@link AchievementManager} backed by the specified persistent store.
     *
     * @param persistentAccess cross-save persistent storage implementation
     */
    public AchievementManager(PersistentAccess persistentAccess) {
        this.persistentAccess = Objects.requireNonNull(persistentAccess, "persistentAccess must not be null");
    }

    /**
     * Attaches an optional external or offline {@link AchievementBackend} ({@code #0554}).
     *
     * @param backend backend implementation (null resets to offline default)
     */
    public synchronized void setBackend(AchievementBackend backend) {
        this.backend = backend != null ? backend : new AchievementBackend.OfflineAchievementBackend();
    }

    /**
     * Returns the currently configured {@link AchievementBackend}.
     *
     * @return active achievement backend
     */
    public synchronized AchievementBackend getBackend() {
        return backend;
    }

    /**
     * Registers or updates an achievement definition in declaration order.
     *
     * @param achievement achievement definition to register
     */
    public synchronized void register(AchievementDef achievement) {
        Objects.requireNonNull(achievement, "achievement must not be null");
        definitions.put(achievement.id(), achievement);
    }

    /**
     * Unlocks an achievement in {@link PersistentAccess}.
     *
     * <p>If the achievement was not previously unlocked, a new {@link AchievementToast}
     * is pushed onto the notification queue and the configured unlock listener (if any)
     * is invoked. Repeated calls for an already unlocked achievement are no-ops and
     * return {@code false}.</p>
     *
     * @param id achievement identifier
     * @return {@code true} if newly unlocked, {@code false} if already unlocked or invalid
     */
    public boolean unlock(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        AchievementToast toast;
        Consumer<AchievementToast> listenerSnapshot;
        AchievementBackend backendSnapshot;
        synchronized (this) {
            if (persistentAccess.isAchievementUnlocked(id)) {
                return false;
            }
            persistentAccess.unlockAchievement(id);
            AchievementDef def = definitions.get(id);
            if (def == null) {
                def = new AchievementDef(id, id, "", "", false);
            }
            toast = new AchievementToast(def, Instant.now());
            toastQueue.addLast(toast);
            listenerSnapshot = this.onUnlockListener;
            backendSnapshot = this.backend;
        }
        if (backendSnapshot != null && backendSnapshot.isAvailable()) {
            backendSnapshot.syncUnlock(id, toast.unlockedAt());
        }
        if (listenerSnapshot != null) {
            listenerSnapshot.accept(toast);
        }
        return true;
    }

    /**
     * Returns whether the given achievement is marked unlocked in {@link PersistentAccess}.
     *
     * @param id achievement identifier
     * @return {@code true} if unlocked
     */
    public synchronized boolean isUnlocked(String id) {
        return id != null && !id.isBlank() && persistentAccess.isAchievementUnlocked(id);
    }

    /**
     * Retrieves and removes the oldest pending {@link AchievementToast} from the queue.
     *
     * @return next toast notification, or {@link Optional#empty()} if the queue is empty
     */
    public synchronized Optional<AchievementToast> pollToast() {
        return Optional.ofNullable(toastQueue.pollFirst());
    }

    /**
     * Returns an immutable snapshot list of all registered achievements in registration order.
     *
     * @return list of registered achievement definitions
     */
    public synchronized List<AchievementDef> getAllAchievements() {
        return List.copyOf(definitions.values());
    }

    /**
     * Counts how many registered achievements are currently unlocked.
     *
     * @return unlocked registered achievement count
     */
    public synchronized int getUnlockedCount() {
        int count = 0;
        for (String id : definitions.keySet()) {
            if (persistentAccess.isAchievementUnlocked(id)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the total number of registered achievements.
     *
     * @return total registered achievement count
     */
    public synchronized int getTotalCount() {
        return definitions.size();
    }

    /**
     * Sets or clears a listener notified immediately whenever an achievement is newly unlocked.
     *
     * @param listener consumer receiving the generated {@link AchievementToast}, or {@code null} to clear
     */
    public synchronized void setOnUnlockListener(Consumer<AchievementToast> listener) {
        this.onUnlockListener = listener;
    }
}
