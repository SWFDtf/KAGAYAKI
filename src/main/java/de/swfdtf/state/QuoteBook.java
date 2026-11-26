package de.swfdtf.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistent player quote-book store recording favorite dialogue lines across saves.
 */
public class QuoteBook {

    private static final String KEY_COUNT = "quote_book.count";
    private static final String KEY_PREFIX = "quote_book.item.";

    /**
     * Immutable quote-book entry record.
     *
     * @param id               unique entry identifier
     * @param speaker          speaker display name (may be empty for narrator)
     * @param text             bookmarked dialogue text
     * @param label            script label active when bookmarked
     * @param timestampEpochMs creation timestamp in epoch milliseconds
     */
    public record QuoteEntry(String id, String speaker, String text, String label, long timestampEpochMs) {

        /**
         * Normalizes nullable string fields on construction.
         */
        public QuoteEntry {
            id = id != null && !id.isBlank() ? id : UUID.randomUUID().toString();
            speaker = speaker != null ? speaker : "";
            text = text != null ? text : "";
            label = label != null ? label : "";
        }
    }

    private final PersistentAccess persistentAccess;
    private final List<QuoteEntry> entries = new ArrayList<>();

    /**
     * Creates a {@link QuoteBook} backed by {@code persistentAccess} and loads any stored entries.
     *
     * @param persistentAccess cross-save persistent storage (must not be {@code null})
     */
    public QuoteBook(PersistentAccess persistentAccess) {
        this.persistentAccess = Objects.requireNonNull(persistentAccess, "persistentAccess must not be null");
        loadFromPersistent();
    }

    /**
     * Bookmarks a dialogue line and persists it immediately.
     *
     * @param speaker speaker display name (nullable)
     * @param text    dialogue text (must not be {@code null} or blank)
     * @param label   script label (nullable)
     * @return newly created {@link QuoteEntry}, or {@link Optional#empty()} if {@code text} is blank
     */
    public synchronized Optional<QuoteEntry> addQuote(String speaker, String text, String label) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String normalizedText = text.trim();
        for (QuoteEntry existing : entries) {
            if (existing.text().equals(normalizedText)
                    && existing.speaker().equals(speaker != null ? speaker : "")) {
                return Optional.of(existing);
            }
        }
        QuoteEntry entry = new QuoteEntry(
                UUID.randomUUID().toString(),
                speaker != null ? speaker.trim() : "",
                normalizedText,
                label != null ? label.trim() : "",
                System.currentTimeMillis()
        );
        entries.add(entry);
        saveToPersistent();
        return Optional.of(entry);
    }

    /**
     * Returns an unmodifiable snapshot of bookmarked quotes in insertion order.
     *
     * @return immutable list of quotes
     */
    public synchronized List<QuoteEntry> listQuotes() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /**
     * Removes a quote entry matching {@code quoteId}.
     *
     * @param quoteId unique entry identifier
     * @return {@code true} if an entry was removed
     */
    public synchronized boolean removeQuote(String quoteId) {
        if (quoteId == null || quoteId.isBlank()) {
            return false;
        }
        boolean removed = entries.removeIf(e -> e.id().equals(quoteId));
        if (removed) {
            saveToPersistent();
        }
        return removed;
    }

    /**
     * Clears all bookmarked quotes and updates persistent storage.
     */
    public synchronized void clear() {
        entries.clear();
        saveToPersistent();
    }

    /**
     * Returns the number of stored quotes.
     *
     * @return quote count
     */
    public synchronized int size() {
        return entries.size();
    }

    private void loadFromPersistent() {
        Object rawCount = persistentAccess.get(KEY_COUNT);
        int count = 0;
        if (rawCount instanceof Number num) {
            count = Math.max(0, num.intValue());
        } else if (rawCount instanceof String str) {
            try {
                count = Math.max(0, Integer.parseInt(str.trim()));
            } catch (NumberFormatException ignored) {
                count = 0;
            }
        }
        entries.clear();
        for (int i = 0; i < count; i++) {
            String base = KEY_PREFIX + i + ".";
            String text = asString(persistentAccess.get(base + "text"));
            if (!text.isBlank()) {
                String id = asString(persistentAccess.get(base + "id"));
                String speaker = asString(persistentAccess.get(base + "speaker"));
                String label = asString(persistentAccess.get(base + "label"));
                long ts = asLong(persistentAccess.get(base + "ts"), 0L);
                entries.add(new QuoteEntry(id, speaker, text, label, ts));
            }
        }
    }

    private void saveToPersistent() {
        persistentAccess.set(KEY_COUNT, entries.size());
        for (int i = 0; i < entries.size(); i++) {
            QuoteEntry e = entries.get(i);
            String base = KEY_PREFIX + i + ".";
            persistentAccess.set(base + "id", e.id());
            persistentAccess.set(base + "speaker", e.speaker());
            persistentAccess.set(base + "text", e.text());
            persistentAccess.set(base + "label", e.label());
            persistentAccess.set(base + "ts", e.timestampEpochMs());
        }
    }

    private static String asString(Object raw) {
        return raw != null ? String.valueOf(raw) : "";
    }

    private static long asLong(Object raw, long fallback) {
        if (raw instanceof Number num) {
            return num.longValue();
        }
        if (raw instanceof String str) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
