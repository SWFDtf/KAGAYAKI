package de.swfdtf.state;

import java.util.Set;

/**
 * Contract for global cross-save persistent storage exposed as {@code persistent} inside
 * {@code @->{ ... }<<} Java script blocks.
 *
 * <p>Stores custom persistent variables, seen dialogue line hashes (for unread/read Skip),
 * unlocked CG gallery entries, music tracks, reached story endings, and unlocked achievements.</p>
 */
public interface PersistentAccess {

    /**
     * Stores a global persistent variable.
     *
     * @param key   property key
     * @param value property value
     */
    void set(String key, Object value);

    /**
     * Retrieves a global persistent variable or {@code null}.
     *
     * @param key property key
     * @return stored value or {@code null}
     */
    Object get(String key);

    /**
     * Retrieves a global persistent variable or {@code defaultValue}.
     *
     * @param key          property key
     * @param defaultValue fallback value
     * @return stored or fallback value
     */
    Object getOrDefault(String key, Object defaultValue);

    /**
     * Returns whether {@code key} is defined in persistent storage.
     *
     * @param key property key
     * @return {@code true} if present
     */
    boolean has(String key);

    /**
     * Marks a dialogue line identifier as read across all save files.
     *
     * @param lineId canonical line identifier
     */
    void markLineRead(String lineId);

    /**
     * Returns whether a dialogue line identifier was previously read.
     *
     * @param lineId canonical line identifier
     * @return {@code true} if read
     */
    boolean isLineRead(String lineId);

    /**
     * Unlocks a CG gallery entry by ID.
     *
     * @param cgId CG identifier
     */
    void unlockCg(String cgId);

    /**
     * Returns whether a CG gallery entry is unlocked.
     *
     * @param cgId CG identifier
     * @return {@code true} if unlocked
     */
    boolean isCgUnlocked(String cgId);

    /**
     * Unlocks a music room track by ID/path.
     *
     * @param trackId track identifier
     */
    void unlockTrack(String trackId);

    /**
     * Returns whether a music room track is unlocked.
     *
     * @param trackId track identifier
     * @return {@code true} if unlocked
     */
    boolean isTrackUnlocked(String trackId);

    /**
     * Records a reached story ending by ID.
     *
     * @param endingId ending identifier
     */
    void unlockEnding(String endingId);

    /**
     * Returns whether a story ending has been reached.
     *
     * @param endingId ending identifier
     * @return {@code true} if reached
     */
    boolean isEndingUnlocked(String endingId);

    /**
     * Unlocks an achievement by ID.
     *
     * @param achievementId achievement identifier
     */
    void unlockAchievement(String achievementId);

    /**
     * Returns whether an achievement is unlocked.
     *
     * @param achievementId achievement identifier
     * @return {@code true} if unlocked
     */
    boolean isAchievementUnlocked(String achievementId);

    /**
     * Returns an immutable view of unlocked CG IDs.
     *
     * @return immutable view of unlocked CG IDs
     */
    Set<String> getUnlockedCgs();

    /**
     * Returns an immutable view of unlocked track IDs.
     *
     * @return immutable view of unlocked track IDs
     */
    Set<String> getUnlockedTracks();

    /**
     * Returns an immutable view of unlocked ending IDs.
     *
     * @return immutable view of unlocked ending IDs
     */
    Set<String> getUnlockedEndings();

    /**
     * Returns an immutable view of unlocked achievement IDs.
     *
     * @return immutable view of unlocked achievement IDs
     */
    Set<String> getUnlockedAchievements();
}
