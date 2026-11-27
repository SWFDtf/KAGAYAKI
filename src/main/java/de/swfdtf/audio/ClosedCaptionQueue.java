package de.swfdtf.audio;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Bounded accessibility closed-caption and subtitle queue tracking active dialogue, SFX, and
 * music captions with automatic time-based expiration.
 */
public final class ClosedCaptionQueue {

    /** Default maximum number of concurrently visible caption entries. */
    public static final int DEFAULT_MAX_VISIBLE_ENTRIES = 4;

    /**
     * Accessibility caption category distinguishing spoken dialogue, sound effects, and music cues.
     */
    public enum CaptionCategory {
        /** Spoken dialogue line. */
        DIALOGUE,
        /** Sound effect cue. */
        SFX,
        /** Music cue. */
        MUSIC
    }

    /**
     * Immutable closed-caption entry currently active in the display queue.
     *
     * @param id                   unique generated caption identifier (non-blank)
     * @param category             caption category (non-null)
     * @param source               speaker or sound source label (non-null)
     * @param text                 displayed caption text (non-blank)
     * @param remainingSeconds     remaining display duration in seconds ({@code >= 0})
     * @param totalDurationSeconds original total display duration in seconds ({@code > 0})
     */
    public record CaptionEntry(
            String id,
            CaptionCategory category,
            String source,
            String text,
            double remainingSeconds,
            double totalDurationSeconds
    ) {
        /**
         * Validates caption entry invariants.
         */
        public CaptionEntry {
            id = requireNonBlank(id, "id");
            Objects.requireNonNull(category, "category must not be null");
            Objects.requireNonNull(source, "source must not be null");
            text = requireNonBlank(text, "text");
            if (!Double.isFinite(totalDurationSeconds) || totalDurationSeconds <= 0.0) {
                throw new IllegalArgumentException(
                        "totalDurationSeconds must be finite and > 0, got: " + totalDurationSeconds
                );
            }
            if (!Double.isFinite(remainingSeconds) || remainingSeconds < 0.0) {
                throw new IllegalArgumentException(
                        "remainingSeconds must be finite and >= 0, got: " + remainingSeconds
                );
            }
        }
    }

    /**
     * Accessibility caption priority tier preventing ambient descriptions from hiding story alerts ({@code #0467}).
     */
    public enum Priority {
        /** Subtle background ambience cue. */
        AMBIENT(0),
        /** Standard story dialogue or SFX caption. */
        STORY(1),
        /** High-priority warning or critical alert kept at front of queue. */
        CRITICAL(2);

        private final int weight;

        Priority(int weight) {
            this.weight = weight;
        }

        /**
         * Returns numeric sorting weight.
         *
         * @return priority weight
         */
        public int getWeight() {
            return weight;
        }
    }

    private final Deque<CaptionEntry> entries = new ArrayDeque<>();
    private final java.util.Map<String, Priority> prioritiesById = new java.util.HashMap<>();
    private int maxVisibleEntries;
    private long nextSequence = 1L;

    /**
     * Creates a {@code ClosedCaptionQueue} with {@link #DEFAULT_MAX_VISIBLE_ENTRIES}.
     */
    public ClosedCaptionQueue() {
        this(DEFAULT_MAX_VISIBLE_ENTRIES);
    }

    /**
     * Creates a {@code ClosedCaptionQueue} with a custom maximum visible entry limit.
     *
     * @param maxVisibleEntries maximum number of visible captions retained at once ({@code > 0})
     * @throws IllegalArgumentException if {@code maxVisibleEntries <= 0}
     */
    public ClosedCaptionQueue(int maxVisibleEntries) {
        validateMaxVisibleEntries(maxVisibleEntries);
        this.maxVisibleEntries = maxVisibleEntries;
    }

    /**
     * Appends a new caption entry to the queue, evicting the oldest entry if the queue has
     * reached {@link #getMaxVisibleEntries()}.
     *
     * @param category        caption category (must not be {@code null})
     * @param source          source or speaker label (must not be {@code null})
     * @param text            caption text (must not be {@code null} or blank)
     * @param durationSeconds display duration in seconds ({@code > 0})
     * @return the newly created {@link CaptionEntry}
     * @throws IllegalArgumentException if {@code text} is blank or {@code durationSeconds <= 0}
     */
    public synchronized CaptionEntry push(
            CaptionCategory category,
            String source,
            String text,
            double durationSeconds
    ) {
        return push(category, source, text, durationSeconds, Priority.STORY);
    }

    /**
     * Appends a caption with an explicit {@link Priority}, ordering higher-priority alerts at the
     * front so ambient cues never displace critical warnings ({@code #0467}).
     *
     * @param category        caption category (must not be {@code null})
     * @param source          source or speaker label (must not be {@code null})
     * @param text            caption text (must not be {@code null} or blank)
     * @param durationSeconds display duration in seconds ({@code > 0})
     * @param priority        caption priority tier
     * @return newly created {@link CaptionEntry}
     */
    public synchronized CaptionEntry push(
            CaptionCategory category,
            String source,
            String text,
            double durationSeconds,
            Priority priority
    ) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(priority, "priority must not be null");
        String validText = requireNonBlank(text, "text");
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0.0) {
            throw new IllegalArgumentException(
                    "durationSeconds must be finite and > 0, got: " + durationSeconds
            );
        }

        String id = "caption-" + nextSequence++;
        CaptionEntry entry = new CaptionEntry(
                id,
                category,
                source.trim(),
                validText,
                durationSeconds,
                durationSeconds
        );
        prioritiesById.put(id, priority);

        List<CaptionEntry> sorted = new ArrayList<>(entries);
        sorted.add(entry);
        sorted.sort((a, b) -> Integer.compare(
                prioritiesById.getOrDefault(b.id(), Priority.STORY).getWeight(),
                prioritiesById.getOrDefault(a.id(), Priority.STORY).getWeight()
        ));
        while (sorted.size() > maxVisibleEntries) {
            int lowestIdx = 0;
            int lowestWeight = prioritiesById.getOrDefault(sorted.get(0).id(), Priority.STORY).getWeight();
            for (int i = 1; i < sorted.size(); i++) {
                int w = prioritiesById.getOrDefault(sorted.get(i).id(), Priority.STORY).getWeight();
                if (w < lowestWeight) {
                    lowestWeight = w;
                    lowestIdx = i;
                }
            }
            CaptionEntry removed = sorted.remove(lowestIdx);
            prioritiesById.remove(removed.id());
        }
        entries.clear();
        entries.addAll(sorted);
        return entry;
    }

    /**
     * Decrements remaining durations on all active captions by {@code deltaSeconds} and removes
     * any captions whose remaining time has expired ({@code <= 0}).
     *
     * @param deltaSeconds elapsed time step in seconds ({@code >= 0})
     * @throws IllegalArgumentException if {@code deltaSeconds} is non-finite or negative
     */
    public synchronized void update(double deltaSeconds) {
        if (!Double.isFinite(deltaSeconds) || deltaSeconds < 0.0) {
            throw new IllegalArgumentException("deltaSeconds must be finite and >= 0, got: " + deltaSeconds);
        }
        if (deltaSeconds == 0.0 || entries.isEmpty()) {
            return;
        }

        List<CaptionEntry> updated = new ArrayList<>(entries.size());
        Iterator<CaptionEntry> it = entries.iterator();
        while (it.hasNext()) {
            CaptionEntry current = it.next();
            double remaining = current.remainingSeconds() - deltaSeconds;
            if (remaining > 0.0) {
                updated.add(new CaptionEntry(
                        current.id(),
                        current.category(),
                        current.source(),
                        current.text(),
                        remaining,
                        current.totalDurationSeconds()
                ));
            }
        }
        entries.clear();
        entries.addAll(updated);
    }

    /**
     * Returns an unmodifiable snapshot list of currently visible captions in FIFO order.
     *
     * @return unmodifiable list of active {@link CaptionEntry} items
     */
    public synchronized List<CaptionEntry> getVisibleCaptions() {
        return List.copyOf(entries);
    }

    /**
     * Returns an unmodifiable list of currently visible captions matching {@code category}.
     *
     * @param category caption category filter (must not be {@code null})
     * @return unmodifiable list of matching {@link CaptionEntry} items
     */
    public synchronized List<CaptionEntry> filterByCategory(CaptionCategory category) {
        Objects.requireNonNull(category, "category must not be null");
        List<CaptionEntry> filtered = new ArrayList<>();
        for (CaptionEntry entry : entries) {
            if (entry.category() == category) {
                filtered.add(entry);
            }
        }
        return List.copyOf(filtered);
    }

    /**
     * Removes all captions from the queue.
     */
    public synchronized void clear() {
        entries.clear();
    }

    /**
     * Returns the configured maximum number of concurrently visible captions.
     *
     * @return maximum visible caption count ({@code > 0})
     */
    public synchronized int getMaxVisibleEntries() {
        return maxVisibleEntries;
    }

    /**
     * Updates the maximum number of concurrently visible captions, evicting oldest entries
     * immediately if necessary.
     *
     * @param maxVisibleEntries new maximum visible caption count ({@code > 0})
     * @throws IllegalArgumentException if {@code maxVisibleEntries <= 0}
     */
    public synchronized void setMaxVisibleEntries(int maxVisibleEntries) {
        validateMaxVisibleEntries(maxVisibleEntries);
        this.maxVisibleEntries = maxVisibleEntries;
        while (entries.size() > maxVisibleEntries) {
            entries.removeFirst();
        }
    }

    private static void validateMaxVisibleEntries(int maxVisibleEntries) {
        if (maxVisibleEntries <= 0) {
            throw new IllegalArgumentException(
                    "maxVisibleEntries must be > 0, got: " + maxVisibleEntries
            );
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }
}
