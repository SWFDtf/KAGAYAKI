package de.swfdtf.audio;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded chronological history of recently played character voice lines for voice replay,
 * dialogue backlog integration, and debugging.
 */
public final class VoicePlaybackBookmark {

    /** Default maximum number of voice clip records retained in history. */
    public static final int DEFAULT_MAX_HISTORY = 50;

    /**
     * Immutable record of a played voice clip.
     *
     * @param lineId    dialogue line identifier (non-blank)
     * @param speakerId speaker identifier (non-blank)
     * @param assetPath audio asset path of the voice clip (non-blank)
     * @param playedAt  UTC timestamp when playback was recorded (defaults to {@link Instant#now()} if {@code null})
     */
    public record VoiceClipRecord(
            String lineId,
            String speakerId,
            String assetPath,
            Instant playedAt
    ) {
        /**
         * Validates and normalizes voice clip record fields.
         */
        public VoiceClipRecord {
            lineId = requireNonBlank(lineId, "lineId");
            speakerId = requireNonBlank(speakerId, "speakerId");
            assetPath = requireNonBlank(assetPath, "assetPath");
            playedAt = playedAt != null ? playedAt : Instant.now();
        }
    }

    private final Deque<VoiceClipRecord> history = new ArrayDeque<>();
    private final int maxHistory;
    private String activeAssetPath;
    private double activeOffsetSeconds = 0.0;

    /**
     * Creates a {@code VoicePlaybackBookmark} with {@link #DEFAULT_MAX_HISTORY}.
     */
    public VoicePlaybackBookmark() {
        this(DEFAULT_MAX_HISTORY);
    }

    /**
     * Creates a {@code VoicePlaybackBookmark} with a custom maximum history capacity.
     *
     * @param maxHistory maximum number of retained voice records ({@code > 0})
     * @throws IllegalArgumentException if {@code maxHistory <= 0}
     */
    public VoicePlaybackBookmark(int maxHistory) {
        if (maxHistory <= 0) {
            throw new IllegalArgumentException("maxHistory must be > 0, got: " + maxHistory);
        }
        this.maxHistory = maxHistory;
    }

    /**
     * Records a played voice line, evicting the oldest record if the history has reached
     * {@link #getMaxHistory()}.
     *
     * @param lineId    dialogue line identifier (must not be {@code null} or blank)
     * @param speakerId speaker identifier (must not be {@code null} or blank)
     * @param assetPath voice audio asset path (must not be {@code null} or blank)
     * @return the newly recorded {@link VoiceClipRecord}
     * @throws IllegalArgumentException if any string argument is blank
     */
    public synchronized VoiceClipRecord record(String lineId, String speakerId, String assetPath) {
        VoiceClipRecord entry = new VoiceClipRecord(lineId, speakerId, assetPath, Instant.now());
        while (history.size() >= maxHistory) {
            history.removeFirst();
        }
        history.addLast(entry);
        return entry;
    }

    /**
     * Returns the most recently recorded voice clip, if any.
     *
     * @return {@link Optional} containing the latest {@link VoiceClipRecord}, or {@link Optional#empty()}
     */
    public synchronized Optional<VoiceClipRecord> findLatest() {
        return Optional.ofNullable(history.peekLast());
    }

    /**
     * Finds the most recent voice clip record matching {@code lineId}.
     *
     * @param lineId dialogue line identifier (must not be {@code null} or blank)
     * @return {@link Optional} containing the matching {@link VoiceClipRecord}, or {@link Optional#empty()}
     * @throws IllegalArgumentException if {@code lineId} is blank
     */
    public synchronized Optional<VoiceClipRecord> findByLineId(String lineId) {
        String validLineId = requireNonBlank(lineId, "lineId");
        Iterator<VoiceClipRecord> reverseIt = history.descendingIterator();
        while (reverseIt.hasNext()) {
            VoiceClipRecord entry = reverseIt.next();
            if (entry.lineId().equals(validLineId)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns all recorded voice clips spoken by {@code speakerId} in chronological order.
     *
     * @param speakerId speaker identifier (must not be {@code null} or blank)
     * @return unmodifiable list of matching {@link VoiceClipRecord} entries
     * @throws IllegalArgumentException if {@code speakerId} is blank
     */
    public synchronized List<VoiceClipRecord> listBySpeaker(String speakerId) {
        String validSpeakerId = requireNonBlank(speakerId, "speakerId");
        List<VoiceClipRecord> matches = new ArrayList<>();
        for (VoiceClipRecord entry : history) {
            if (entry.speakerId().equals(validSpeakerId)) {
                matches.add(entry);
            }
        }
        return List.copyOf(matches);
    }

    /**
     * Returns an unmodifiable snapshot list of all recorded voice clips in chronological order.
     *
     * @return unmodifiable list of {@link VoiceClipRecord} entries
     */
    public synchronized List<VoiceClipRecord> listAll() {
        return List.copyOf(history);
    }

    /**
     * Clears all recorded voice clip history and resets active playback tracking.
     */
    public synchronized void clear() {
        history.clear();
        clearActivePlayback();
    }

    /**
     * Updates the currently playing voice asset path and position offset for save-slot bookmarking.
     *
     * @param assetPath     voice audio asset path (nullable clears active tracking)
     * @param offsetSeconds active playback cursor position in seconds ({@code >= 0})
     */
    public synchronized void updateActivePlayback(String assetPath, double offsetSeconds) {
        if (assetPath == null || assetPath.isBlank()) {
            clearActivePlayback();
            return;
        }
        this.activeAssetPath = assetPath.trim();
        this.activeOffsetSeconds = (Double.isFinite(offsetSeconds) && offsetSeconds > 0.0) ? offsetSeconds : 0.0;
    }

    /**
     * Clears the active voice playback bookmark offset when voice playback finishes or stops.
     */
    public synchronized void clearActivePlayback() {
        this.activeAssetPath = null;
        this.activeOffsetSeconds = 0.0;
    }

    /**
     * Returns the currently active voice asset path tracked for bookmarking, if any.
     *
     * @return optional active voice asset path
     */
    public synchronized Optional<String> getActiveAssetPath() {
        return Optional.ofNullable(activeAssetPath);
    }

    /**
     * Returns the latest tracked active voice playback offset in seconds.
     *
     * @return active voice playback offset in seconds ({@code >= 0})
     */
    public synchronized double getActiveOffsetSeconds() {
        return activeOffsetSeconds;
    }

    /**
     * Returns the maximum number of records retained by this bookmark history.
     *
     * @return maximum history capacity
     */
    public int getMaxHistory() {
        return maxHistory;
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
