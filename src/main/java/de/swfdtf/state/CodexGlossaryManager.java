package de.swfdtf.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Manages in-game Codex / Glossary entries and their persistent unlock status backed by
 * {@link PersistentAccess}.
 *
 * <p>Unlock flags are stored under {@code "codex.unlocked.<entryId>"} so they persist
 * automatically across save slots via {@link PersistentState}.</p>
 *
 * <p>Thread-safe across script runtime and Codex UI threads.</p>
 */
public class CodexGlossaryManager {

    /** Persistent property key prefix used for codex entry unlock flags. */
    public static final String KEY_PREFIX = "codex.unlocked.";

    /**
     * Immutable definition of an encyclopedia / glossary entry.
     *
     * @param id       unique codex entry identifier (non-null, non-blank)
     * @param title    display title shown in the codex browser
     * @param category grouping category (e.g., {@code "Characters"}, {@code "Lore"}, {@code "Locations"})
     * @param summary  short teaser or tooltip description
     * @param fullText complete lore/glossary article body
     */
    public record CodexEntry(
            String id,
            String title,
            String category,
            String summary,
            String fullText
    ) {
        /**
         * Validates and normalizes codex entry metadata fields.
         */
        public CodexEntry {
            Objects.requireNonNull(id, "id must not be null");
            if (id.isBlank()) {
                throw new IllegalArgumentException("codex entry id must not be blank");
            }
            title = title != null ? title : id;
            category = (category != null && !category.isBlank()) ? category : "General";
            summary = summary != null ? summary : "";
            fullText = fullText != null ? fullText : "";
        }
    }

    private final PersistentAccess persistentAccess;
    private final Map<String, CodexEntry> entries = new LinkedHashMap<>();

    /**
     * Creates a {@link CodexGlossaryManager} backed by the specified persistent store.
     *
     * @param persistentAccess cross-save persistent storage implementation (must not be {@code null})
     */
    public CodexGlossaryManager(PersistentAccess persistentAccess) {
        this.persistentAccess = Objects.requireNonNull(persistentAccess, "persistentAccess must not be null");
    }

    /**
     * Registers or updates a codex entry in declaration order.
     *
     * @param entry codex entry definition to register (must not be {@code null})
     */
    public synchronized void register(CodexEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        entries.put(entry.id(), entry);
    }

    /**
     * Unlocks a codex entry in {@link PersistentAccess}.
     *
     * @param entryId codex entry identifier
     * @return {@code true} if newly unlocked, {@code false} if already unlocked or invalid
     */
    public synchronized boolean unlock(String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return false;
        }
        if (isUnlocked(entryId)) {
            return false;
        }
        persistentAccess.set(KEY_PREFIX + entryId, Boolean.TRUE);
        return true;
    }

    /**
     * Returns whether the given codex entry is unlocked in {@link PersistentAccess}.
     *
     * @param entryId codex entry identifier
     * @return {@code true} if unlocked
     */
    public synchronized boolean isUnlocked(String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return false;
        }
        Object value = persistentAccess.get(KEY_PREFIX + entryId);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text.trim());
        }
        return false;
    }

    /**
     * Looks up a registered codex entry by identifier.
     *
     * @param entryId codex entry identifier
     * @return matching {@link CodexEntry}, or {@link Optional#empty()} if not registered
     */
    public synchronized Optional<CodexEntry> findById(String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(entryId));
    }

    /**
     * Lists registered codex entries belonging to {@code category} (case-insensitive match)
     * in registration order.
     *
     * @param category     category name to match (must not be {@code null})
     * @param onlyUnlocked when {@code true}, restricts results to unlocked entries
     * @return unmodifiable list of matching {@link CodexEntry} items
     */
    public synchronized List<CodexEntry> listByCategory(String category, boolean onlyUnlocked) {
        Objects.requireNonNull(category, "category must not be null");
        if (category.isBlank()) {
            return List.of();
        }
        String normalizedCategory = category.trim();
        List<CodexEntry> matches = new ArrayList<>();
        for (CodexEntry entry : entries.values()) {
            if (!entry.category().equalsIgnoreCase(normalizedCategory)) {
                continue;
            }
            if (onlyUnlocked && !isUnlocked(entry.id())) {
                continue;
            }
            matches.add(entry);
        }
        return List.copyOf(matches);
    }

    /**
     * Searches registered codex entries case-insensitively across ID, title, category, summary,
     * and full text.
     *
     * @param query        search query string (must not be {@code null}; blank matches all entries)
     * @param onlyUnlocked when {@code true}, restricts results to unlocked entries
     * @return unmodifiable list of matching {@link CodexEntry} items in registration order
     */
    public synchronized List<CodexEntry> search(String query, boolean onlyUnlocked) {
        Objects.requireNonNull(query, "query must not be null");
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<CodexEntry> matches = new ArrayList<>();
        for (CodexEntry entry : entries.values()) {
            if (onlyUnlocked && !isUnlocked(entry.id())) {
                continue;
            }
            if (needle.isEmpty() || matchesQuery(entry, needle)) {
                matches.add(entry);
            }
        }
        return List.copyOf(matches);
    }

    /**
     * Computes codex unlock progress percentage in the range {@code [0.0, 100.0]}.
     *
     * @return unlock percentage, or {@code 0.0} if no entries are registered
     */
    public synchronized double getUnlockProgressPercent() {
        int total = entries.size();
        if (total == 0) {
            return 0.0;
        }
        return (getUnlockedCount() * 100.0) / total;
    }

    /**
     * Returns an unmodifiable list of all registered codex entries in registration order.
     *
     * @return all registered entries
     */
    public synchronized List<CodexEntry> listAll() {
        return List.copyOf(entries.values());
    }

    /**
     * Returns an unmodifiable list of distinct categories in order of first registration.
     *
     * @return distinct category names
     */
    public synchronized List<String> listCategories() {
        Set<String> categories = new LinkedHashSet<>();
        for (CodexEntry entry : entries.values()) {
            categories.add(entry.category());
        }
        return List.copyOf(categories);
    }

    /**
     * Counts how many registered codex entries are currently unlocked.
     *
     * @return unlocked entry count
     */
    public synchronized int getUnlockedCount() {
        int count = 0;
        for (String id : entries.keySet()) {
            if (isUnlocked(id)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the total number of registered codex entries.
     *
     * @return registered entry count
     */
    public synchronized int getTotalCount() {
        return entries.size();
    }

    private static boolean matchesQuery(CodexEntry entry, String needle) {
        return entry.id().toLowerCase(Locale.ROOT).contains(needle)
                || entry.title().toLowerCase(Locale.ROOT).contains(needle)
                || entry.category().toLowerCase(Locale.ROOT).contains(needle)
                || entry.summary().toLowerCase(Locale.ROOT).contains(needle)
                || entry.fullText().toLowerCase(Locale.ROOT).contains(needle);
    }
}
