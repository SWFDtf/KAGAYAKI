package de.swfdtf.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Manages branching story ending definitions (including parent-child tree links for
 * flowchart / endings-tree UIs) and tracks persistent completion statistics via
 * {@link PersistentAccess}.
 *
 * <p>Thread-safe across script execution and extras UI rendering threads.</p>
 */
public class EndingsManager {

    /**
     * Immutable definition of a story ending node in the narrative branch tree.
     *
     * @param id             unique ending identifier (non-null, non-blank)
     * @param title          human-readable ending title
     * @param description    summary or hint text for the ending
     * @param branchLabel    route or decision branch label leading to this ending
     * @param parentEndingId optional parent ending/node identifier in the route tree (nullable for roots)
     */
    public record EndingDef(
            String id,
            String title,
            String description,
            String branchLabel,
            String parentEndingId
    ) {
        /**
         * Validates and normalizes ending metadata fields.
         */
        public EndingDef {
            Objects.requireNonNull(id, "id must not be null");
            if (id.isBlank()) {
                throw new IllegalArgumentException("ending id must not be blank");
            }
            title = title != null ? title : id;
            description = description != null ? description : "";
            branchLabel = branchLabel != null ? branchLabel : "";
        }
    }

    private final PersistentAccess persistentAccess;
    private final Map<String, EndingDef> endings = new LinkedHashMap<>();

    /**
     * Creates an {@link EndingsManager} backed by the specified persistent store.
     *
     * @param persistentAccess cross-save persistent storage implementation
     */
    public EndingsManager(PersistentAccess persistentAccess) {
        this.persistentAccess = Objects.requireNonNull(persistentAccess, "persistentAccess must not be null");
    }

    /**
     * Registers or updates a story ending definition in declaration order.
     *
     * @param ending ending definition to register
     */
    public synchronized void register(EndingDef ending) {
        Objects.requireNonNull(ending, "ending must not be null");
        endings.put(ending.id(), ending);
    }

    /**
     * Marks a story ending as reached in {@link PersistentAccess}.
     *
     * @param id ending identifier
     * @return {@code true} if newly unlocked, {@code false} if already unlocked or invalid
     */
    public synchronized boolean unlock(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        if (persistentAccess.isEndingUnlocked(id)) {
            return false;
        }
        persistentAccess.unlockEnding(id);
        return true;
    }

    /**
     * Returns whether the given story ending has been reached.
     *
     * @param id ending identifier
     * @return {@code true} if unlocked
     */
    public synchronized boolean isUnlocked(String id) {
        return id != null && !id.isBlank() && persistentAccess.isEndingUnlocked(id);
    }

    /**
     * Returns an immutable list of all registered ending definitions in registration order.
     *
     * @return all registered endings
     */
    public synchronized List<EndingDef> getAllEndings() {
        return List.copyOf(endings.values());
    }

    /**
     * Looks up a registered ending definition by identifier.
     *
     * @param id ending identifier
     * @return matching {@link EndingDef}, or {@link Optional#empty()} if not registered
     */
    public synchronized Optional<EndingDef> getEnding(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(endings.get(id));
    }

    /**
     * Returns all registered endings whose {@code parentEndingId} equals the specified identifier.
     * Passing {@code null} returns root endings in the tree.
     *
     * @param parentEndingId parent ending identifier or {@code null} for root nodes
     * @return immutable list of child endings in registration order
     */
    public synchronized List<EndingDef> getChildEndings(String parentEndingId) {
        List<EndingDef> children = new ArrayList<>();
        for (EndingDef ending : endings.values()) {
            if (Objects.equals(ending.parentEndingId(), parentEndingId)) {
                children.add(ending);
            }
        }
        return List.copyOf(children);
    }

    /**
     * Computes the overall ending completion percentage in the range {@code [0.0, 100.0]}.
     *
     * @return completion percentage, or {@code 0.0} if no endings are registered
     */
    public synchronized double getCompletionPercentage() {
        int total = endings.size();
        if (total == 0) {
            return 0.0;
        }
        return (getUnlockedCount() * 100.0) / total;
    }

    /**
     * Counts how many registered endings have been unlocked.
     *
     * @return unlocked registered ending count
     */
    public synchronized int getUnlockedCount() {
        int count = 0;
        for (String id : endings.keySet()) {
            if (persistentAccess.isEndingUnlocked(id)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the total number of registered endings.
     *
     * @return total registered ending count
     */
    public synchronized int getTotalCount() {
        return endings.size();
    }
}
