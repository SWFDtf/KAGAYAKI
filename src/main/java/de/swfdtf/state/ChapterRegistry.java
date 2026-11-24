package de.swfdtf.state;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Manages Chapter Select definitions and persistent chapter unlock progression backed by
 * {@link PersistentAccess}.
 *
 * <p>Unlock flags are stored under {@code "chapter.unlocked.<chapterId>"} so they persist
 * automatically across save files via {@link PersistentState}.</p>
 *
 * <p>Thread-safe across script runtime and Chapter Select UI threads.</p>
 */
public class ChapterRegistry {

    /** Persistent property key prefix used for chapter unlock flags. */
    public static final String KEY_PREFIX = "chapter.unlocked.";

    /**
     * Immutable definition of a selectable story chapter.
     *
     * @param id            unique chapter identifier (non-null, non-blank)
     * @param title         display title shown in Chapter Select UI
     * @param startLabel    script label jumped to when starting this chapter (non-null, non-blank)
     * @param orderIndex    sort index determining chapter display order
     * @param thumbnailPath relative asset path to the chapter preview thumbnail
     * @param description   summary text shown in Chapter Select UI
     */
    public record ChapterDef(
            String id,
            String title,
            String startLabel,
            int orderIndex,
            String thumbnailPath,
            String description
    ) {
        /**
         * Validates and normalizes chapter metadata fields.
         */
        public ChapterDef {
            Objects.requireNonNull(id, "id must not be null");
            if (id.isBlank()) {
                throw new IllegalArgumentException("chapter id must not be blank");
            }
            Objects.requireNonNull(startLabel, "startLabel must not be null");
            if (startLabel.isBlank()) {
                throw new IllegalArgumentException("startLabel must not be blank");
            }
            title = title != null ? title : id;
            thumbnailPath = thumbnailPath != null ? thumbnailPath : "";
            description = description != null ? description : "";
        }
    }

    private final PersistentAccess persistentAccess;
    private final Map<String, ChapterDef> chapters = new LinkedHashMap<>();

    /**
     * Creates a {@link ChapterRegistry} backed by the specified persistent store.
     *
     * @param persistentAccess cross-save persistent storage implementation (must not be {@code null})
     */
    public ChapterRegistry(PersistentAccess persistentAccess) {
        this.persistentAccess = Objects.requireNonNull(persistentAccess, "persistentAccess must not be null");
    }

    /**
     * Registers or updates a chapter definition.
     *
     * @param chapter chapter definition to register (must not be {@code null})
     */
    public synchronized void register(ChapterDef chapter) {
        Objects.requireNonNull(chapter, "chapter must not be null");
        chapters.put(chapter.id(), chapter);
    }

    /**
     * Unlocks a chapter in {@link PersistentAccess}.
     *
     * @param chapterId chapter identifier
     * @return {@code true} if newly unlocked, {@code false} if already unlocked or invalid
     */
    public synchronized boolean unlock(String chapterId) {
        if (chapterId == null || chapterId.isBlank()) {
            return false;
        }
        if (isUnlocked(chapterId)) {
            return false;
        }
        persistentAccess.set(KEY_PREFIX + chapterId, Boolean.TRUE);
        return true;
    }

    /**
     * Returns whether the given chapter is unlocked in {@link PersistentAccess}.
     *
     * @param chapterId chapter identifier
     * @return {@code true} if unlocked
     */
    public synchronized boolean isUnlocked(String chapterId) {
        if (chapterId == null || chapterId.isBlank()) {
            return false;
        }
        Object value = persistentAccess.get(KEY_PREFIX + chapterId);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text.trim());
        }
        return false;
    }

    /**
     * Looks up a registered chapter definition by identifier.
     *
     * @param chapterId chapter identifier
     * @return matching {@link ChapterDef}, or {@link Optional#empty()} if not registered
     */
    public synchronized Optional<ChapterDef> findById(String chapterId) {
        if (chapterId == null || chapterId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(chapters.get(chapterId));
    }

    /**
     * Returns an unmodifiable list of all registered chapters sorted ascending by {@code orderIndex}.
     *
     * @return sorted list of all registered chapters
     */
    public synchronized List<ChapterDef> listAll() {
        List<ChapterDef> sorted = new ArrayList<>(chapters.values());
        sorted.sort(Comparator.comparingInt(ChapterDef::orderIndex));
        return List.copyOf(sorted);
    }

    /**
     * Returns an unmodifiable list of all unlocked registered chapters sorted ascending by {@code orderIndex}.
     *
     * @return sorted list of unlocked chapters
     */
    public synchronized List<ChapterDef> listUnlocked() {
        List<ChapterDef> unlocked = new ArrayList<>();
        for (ChapterDef chapter : chapters.values()) {
            if (isUnlocked(chapter.id())) {
                unlocked.add(chapter);
            }
        }
        unlocked.sort(Comparator.comparingInt(ChapterDef::orderIndex));
        return List.copyOf(unlocked);
    }

    /**
     * Computes chapter unlock completion percentage in the range {@code [0.0, 100.0]}.
     *
     * @return completion percentage, or {@code 0.0} if no chapters are registered
     */
    public synchronized double getCompletionPercent() {
        int total = chapters.size();
        if (total == 0) {
            return 0.0;
        }
        return (getUnlockedCount() * 100.0) / total;
    }

    /**
     * Counts how many registered chapters are currently unlocked.
     *
     * @return unlocked chapter count
     */
    public synchronized int getUnlockedCount() {
        int count = 0;
        for (String id : chapters.keySet()) {
            if (isUnlocked(id)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the total number of registered chapters.
     *
     * @return registered chapter count
     */
    public synchronized int getTotalCount() {
        return chapters.size();
    }
}
