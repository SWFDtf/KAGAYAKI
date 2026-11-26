package de.swfdtf.state;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * In-memory implementation of {@link PersistentAccess} used by M3 runtime and unit tests
 * before disk-backed M4 {@code PersistentState} is wired in.
 */
public class SimplePersistentAccess implements PersistentAccess {

    private final Map<String, Object> data = new LinkedHashMap<>();
    private final Set<String> readLines = new LinkedHashSet<>();
    private final Set<String> unlockedCgs = new LinkedHashSet<>();
    private final Set<String> unlockedTracks = new LinkedHashSet<>();
    private final Set<String> unlockedEndings = new LinkedHashSet<>();
    private final Set<String> unlockedAchievements = new LinkedHashSet<>();

    /** Creates a new empty in-memory {@code SimplePersistentAccess} store. */
    public SimplePersistentAccess() {
    }

    @Override
    public synchronized void set(String key, Object value) {
        Objects.requireNonNull(key, "key must not be null");
        if (value == null) {
            data.remove(key);
        } else {
            data.put(key, value);
        }
    }

    @Override
    public synchronized Object get(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return data.get(key);
    }

    @Override
    public synchronized Object getOrDefault(String key, Object defaultValue) {
        Objects.requireNonNull(key, "key must not be null");
        Object val = data.get(key);
        return val != null ? val : defaultValue;
    }

    @Override
    public synchronized boolean has(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return data.containsKey(key);
    }

    @Override
    public synchronized void markLineRead(String lineId) {
        if (lineId != null && !lineId.isEmpty()) {
            readLines.add(lineId);
        }
    }

    @Override
    public synchronized boolean isLineRead(String lineId) {
        return lineId != null && readLines.contains(lineId);
    }

    @Override
    public synchronized void unlockCg(String cgId) {
        if (cgId != null && !cgId.isEmpty()) {
            unlockedCgs.add(cgId);
        }
    }

    @Override
    public synchronized boolean isCgUnlocked(String cgId) {
        return cgId != null && unlockedCgs.contains(cgId);
    }

    @Override
    public synchronized void unlockTrack(String trackId) {
        if (trackId != null && !trackId.isEmpty()) {
            unlockedTracks.add(trackId);
        }
    }

    @Override
    public synchronized boolean isTrackUnlocked(String trackId) {
        return trackId != null && unlockedTracks.contains(trackId);
    }

    @Override
    public synchronized void unlockEnding(String endingId) {
        if (endingId != null && !endingId.isEmpty()) {
            unlockedEndings.add(endingId);
        }
    }

    @Override
    public synchronized boolean isEndingUnlocked(String endingId) {
        return endingId != null && unlockedEndings.contains(endingId);
    }

    @Override
    public synchronized void unlockAchievement(String achievementId) {
        if (achievementId != null && !achievementId.isEmpty()) {
            unlockedAchievements.add(achievementId);
        }
    }

    @Override
    public synchronized boolean isAchievementUnlocked(String achievementId) {
        return achievementId != null && unlockedAchievements.contains(achievementId);
    }

    @Override
    public synchronized Set<String> getUnlockedCgs() {
        return Set.copyOf(unlockedCgs);
    }

    @Override
    public synchronized Set<String> getUnlockedTracks() {
        return Set.copyOf(unlockedTracks);
    }

    @Override
    public synchronized Set<String> getUnlockedEndings() {
        return Set.copyOf(unlockedEndings);
    }

    @Override
    public synchronized Set<String> getUnlockedAchievements() {
        return Set.copyOf(unlockedAchievements);
    }
}
