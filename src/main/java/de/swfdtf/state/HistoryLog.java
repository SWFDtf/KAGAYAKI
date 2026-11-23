package de.swfdtf.state;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Capacity-bounded chronological dialogue history log supporting checkpoint correlation,
 * rollback/jump-back truncation, and save/load snapshot restoration.
 */
public final class HistoryLog {

    /** Default maximum number of retained dialogue history entries. */
    public static final int DEFAULT_MAX_ENTRIES = 500;

    /**
     * Immutable dialogue backlog entry linked to a deterministic {@link RevertableStateStore}
     * checkpoint for jump-back navigation.
     *
     * @param entryId         unique monotonically increasing history entry ID
     * @param checkpointId    associated {@link RevertableStateStore.Checkpoint#id()}
     * @param lineId          canonical line ID for read-tracking
     * @param speakerName     resolved display speaker name (nullable for narrator)
     * @param speakerColorHex speaker hex color string (nullable)
     * @param text            interpolated dialogue text
     * @param voicePath       optional voice clip path (nullable)
     * @param timestamp       wall-clock timestamp when the line was presented
     */
    public record HistoryEntry(
            long entryId,
            long checkpointId,
            String lineId,
            String speakerName,
            String speakerColorHex,
            String text,
            String voicePath,
            Instant timestamp
    ) {
        public HistoryEntry {
            timestamp = timestamp != null ? timestamp : Instant.now();
        }
    }

    private final int maxEntries;
    private final Deque<HistoryEntry> entries = new ArrayDeque<>();
    private long nextEntryId = 1L;

    /**
     * Creates a {@code HistoryLog} with {@link #DEFAULT_MAX_ENTRIES} capacity.
     */
    public HistoryLog() {
        this(DEFAULT_MAX_ENTRIES);
    }

    /**
     * Creates a {@code HistoryLog} with the specified maximum capacity.
     *
     * @param maxEntries maximum retained entries ({@code > 0})
     */
    public HistoryLog(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive, got " + maxEntries);
        }
        this.maxEntries = maxEntries;
    }

    /**
     * Appends a new dialogue line to the history log at {@link Instant#now()}, evicting the oldest
     * entry if capacity is reached.
     *
     * @param checkpointId    checkpoint ID associated with this line
     * @param lineId          canonical dialogue line ID
     * @param speakerName     resolved speaker display name (nullable)
     * @param speakerColorHex speaker hex color (nullable)
     * @param text            interpolated dialogue text
     * @param voicePath       associated voice clip path (nullable)
     * @return created {@link HistoryEntry}
     */
    public synchronized HistoryEntry append(
            long checkpointId,
            String lineId,
            String speakerName,
            String speakerColorHex,
            String text,
            String voicePath
    ) {
        return append(checkpointId, lineId, speakerName, speakerColorHex, text, voicePath, Instant.now());
    }

    /**
     * Appends a new dialogue line with an explicit timestamp, evicting the oldest entry if full.
     *
     * @param checkpointId    checkpoint ID associated with this line
     * @param lineId          canonical dialogue line ID
     * @param speakerName     resolved speaker display name (nullable)
     * @param speakerColorHex speaker hex color (nullable)
     * @param text            interpolated dialogue text
     * @param voicePath       associated voice clip path (nullable)
     * @param timestamp       entry timestamp (defaults to {@link Instant#now()} if {@code null})
     * @return created {@link HistoryEntry}
     */
    public synchronized HistoryEntry append(
            long checkpointId,
            String lineId,
            String speakerName,
            String speakerColorHex,
            String text,
            String voicePath,
            Instant timestamp
    ) {
        HistoryEntry entry = new HistoryEntry(
                nextEntryId++,
                checkpointId,
                lineId,
                speakerName,
                speakerColorHex,
                text,
                voicePath,
                timestamp != null ? timestamp : Instant.now()
        );
        addBounded(entry);
        return entry;
    }

    /**
     * Appends a pre-constructed {@link HistoryEntry}, evicting the oldest entry if full.
     *
     * @param entry entry to append (must not be {@code null})
     */
    public synchronized void append(HistoryEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        if (entry.entryId() >= nextEntryId) {
            nextEntryId = entry.entryId() + 1L;
        }
        addBounded(entry);
    }

    /**
     * Returns an immutable chronological copy (oldest-to-newest) of all retained entries.
     *
     * @return all history entries
     */
    public synchronized List<HistoryEntry> getEntries() {
        return List.copyOf(entries);
    }

    /**
     * Returns up to {@code max} most recent entries in chronological order (oldest-to-newest).
     *
     * @param max maximum number of entries to return
     * @return immutable list of recent entries
     */
    public synchronized List<HistoryEntry> getRecentEntries(int max) {
        if (max <= 0 || entries.isEmpty()) {
            return List.of();
        }
        int count = Math.min(max, entries.size());
        int skip = entries.size() - count;
        List<HistoryEntry> result = new ArrayList<>(count);
        int index = 0;
        for (HistoryEntry entry : entries) {
            if (index >= skip) {
                result.add(entry);
            }
            index++;
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Removes all history entries recorded after {@code checkpointId} ({@code entry.checkpointId() > checkpointId})
     * so jump-back and diverging branches never leave stale future dialogue lines in the backlog.
     *
     * @param checkpointId checkpoint boundary ID to retain up to
     */
    public synchronized void truncateAfterCheckpoint(long checkpointId) {
        entries.removeIf(entry -> entry.checkpointId() > checkpointId);
    }

    /**
     * Clears all retained history entries.
     */
    public synchronized void clear() {
        entries.clear();
    }

    /**
     * Captures an immutable snapshot of all history entries for save slots or checkpoints.
     *
     * @return immutable list of entries
     */
    public synchronized List<HistoryEntry> snapshot() {
        return List.copyOf(entries);
    }

    /**
     * Replaces current log contents with {@code entries}, trimming oldest items if exceeding capacity.
     *
     * @param entries entries to restore (must not be {@code null})
     */
    public synchronized void restore(List<HistoryEntry> entries) {
        Objects.requireNonNull(entries, "entries must not be null");
        this.entries.clear();
        for (HistoryEntry entry : entries) {
            if (entry != null) {
                if (entry.entryId() >= nextEntryId) {
                    nextEntryId = entry.entryId() + 1L;
                }
                addBounded(entry);
            }
        }
    }

    /** @return current number of retained history entries */
    public synchronized int size() {
        return entries.size();
    }

    /** @return {@code true} if the log contains no entries */
    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    /** @return configured maximum entry capacity */
    public int getMaxEntries() {
        return maxEntries;
    }

    private void addBounded(HistoryEntry entry) {
        if (entries.size() >= maxEntries) {
            entries.pollFirst();
        }
        entries.addLast(entry);
    }
}
