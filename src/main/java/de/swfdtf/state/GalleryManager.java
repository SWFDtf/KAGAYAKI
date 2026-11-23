package de.swfdtf.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Manages CG gallery entries (including multi-variation CG sets) and queries their
 * persistent unlock status via {@link PersistentAccess}.
 *
 * <p>Thread-safe across script execution and gallery UI rendering threads.</p>
 */
public class GalleryManager {

    /**
     * Immutable definition of an unlockable CG gallery entry.
     *
     * @param id             unique gallery entry identifier (non-null, non-blank)
     * @param title          display title for the gallery browser
     * @param category       gallery tab or character category name
     * @param thumbnailPath  relative path to the gallery thumbnail image
     * @param variationPaths ordered list of full-resolution image paths (variations of this CG)
     * @param description    optional flavor text or scene caption
     */
    public record GalleryEntry(
            String id,
            String title,
            String category,
            String thumbnailPath,
            List<String> variationPaths,
            String description
    ) {
        /**
         * Validates and defensively copies gallery entry fields.
         */
        public GalleryEntry {
            Objects.requireNonNull(id, "id must not be null");
            if (id.isBlank()) {
                throw new IllegalArgumentException("gallery entry id must not be blank");
            }
            title = title != null ? title : id;
            category = category != null ? category : "General";
            thumbnailPath = thumbnailPath != null ? thumbnailPath : "";
            variationPaths = variationPaths != null ? List.copyOf(variationPaths) : List.of();
            description = description != null ? description : "";
        }
    }

    private final PersistentAccess persistentAccess;
    private final Map<String, GalleryEntry> entries = new LinkedHashMap<>();

    /**
     * Creates a {@link GalleryManager} backed by the specified persistent store.
     *
     * @param persistentAccess cross-save persistent storage implementation
     */
    public GalleryManager(PersistentAccess persistentAccess) {
        this.persistentAccess = Objects.requireNonNull(persistentAccess, "persistentAccess must not be null");
    }

    /**
     * Registers or updates a CG gallery entry in declaration order.
     *
     * @param entry gallery entry definition to register
     */
    public synchronized void register(GalleryEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        entries.put(entry.id(), entry);
    }

    /**
     * Unlocks a CG gallery entry in {@link PersistentAccess}.
     *
     * @param id gallery entry identifier
     * @return {@code true} if newly unlocked, {@code false} if already unlocked or invalid
     */
    public synchronized boolean unlock(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        if (persistentAccess.isCgUnlocked(id)) {
            return false;
        }
        persistentAccess.unlockCg(id);
        return true;
    }

    /**
     * Returns whether the given CG gallery entry is unlocked in {@link PersistentAccess}.
     *
     * @param id gallery entry identifier
     * @return {@code true} if unlocked
     */
    public synchronized boolean isUnlocked(String id) {
        return id != null && !id.isBlank() && persistentAccess.isCgUnlocked(id);
    }

    /**
     * Returns an immutable list of all registered gallery entries in registration order.
     *
     * @return all registered gallery entries
     */
    public synchronized List<GalleryEntry> getAllEntries() {
        return List.copyOf(entries.values());
    }

    /**
     * Returns an immutable list of all registered gallery entries that are currently unlocked.
     *
     * @return unlocked gallery entries in registration order
     */
    public synchronized List<GalleryEntry> getUnlockedEntries() {
        List<GalleryEntry> unlocked = new ArrayList<>();
        for (GalleryEntry entry : entries.values()) {
            if (persistentAccess.isCgUnlocked(entry.id())) {
                unlocked.add(entry);
            }
        }
        return List.copyOf(unlocked);
    }

    /**
     * Looks up a registered gallery entry by identifier.
     *
     * @param id gallery entry identifier
     * @return matching {@link GalleryEntry}, or {@link Optional#empty()} if not registered
     */
    public synchronized Optional<GalleryEntry> getEntry(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(id));
    }

    /**
     * Counts how many registered gallery entries are currently unlocked.
     *
     * @return unlocked registered CG count
     */
    public synchronized int getUnlockedCount() {
        int count = 0;
        for (String id : entries.keySet()) {
            if (persistentAccess.isCgUnlocked(id)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the total number of registered CG gallery entries.
     *
     * @return total registered CG count
     */
    public synchronized int getTotalCount() {
        return entries.size();
    }
}
