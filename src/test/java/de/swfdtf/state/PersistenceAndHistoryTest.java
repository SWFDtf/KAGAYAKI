package de.swfdtf.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PersistentState}, {@link PreferencesState}, {@link SessionState},
 * and {@link HistoryLog}.
 */
class PersistenceAndHistoryTest {

    @Test
    void persistentStateSavesAtomicallyAndPreservesTypesAcrossReload(@TempDir Path tempDir) throws Exception {
        Path savesDir = tempDir.resolve("saves");

        // Missing persistent.dat yields fresh empty instance
        PersistentState fresh = PersistentState.load(savesDir);
        assertTrue(fresh.getUnlockedCgs().isEmpty());
        assertFalse(fresh.isLineRead("line_1"));

        fresh.set("clear_count", 3);
        fresh.set("total_score", 9876543210123L);
        fresh.set("completion_ratio", 0.875);
        fresh.set("hero_name", "Kaito");
        fresh.set("flags", Map.of("true_end", true, "secrets", List.of("a", "b")));
        fresh.markLineRead("main.vjava:12:abc");
        fresh.unlockCg("cg_academy_night");
        fresh.unlockTrack("assets/audio/nachtwind.ogg");
        fresh.unlockEnding("ending_stars");
        fresh.unlockAchievement("kompass_erhalten");

        fresh.save(savesDir);
        assertTrue(Files.isRegularFile(savesDir.resolve(PersistentState.FILE_NAME)));

        PersistentState reloaded = PersistentState.load(savesDir);
        assertEquals(3, reloaded.get("clear_count"));
        assertTrue(reloaded.get("clear_count") instanceof Integer);
        assertEquals(9876543210123L, reloaded.get("total_score"));
        assertTrue(reloaded.get("total_score") instanceof Long);
        assertEquals(0.875, (Double) reloaded.get("completion_ratio"), 1e-9);
        assertEquals("Kaito", reloaded.get("hero_name"));
        assertEquals(Map.of("true_end", true, "secrets", List.of("a", "b")), reloaded.get("flags"));

        assertTrue(reloaded.isLineRead("main.vjava:12:abc"));
        assertTrue(reloaded.isCgUnlocked("cg_academy_night"));
        assertTrue(reloaded.isTrackUnlocked("assets/audio/nachtwind.ogg"));
        assertTrue(reloaded.isEndingUnlocked("ending_stars"));
        assertTrue(reloaded.isAchievementUnlocked("kompass_erhalten"));
        assertEquals(Set.of("cg_academy_night"), reloaded.getUnlockedCgs());
        assertEquals(Set.of("assets/audio/nachtwind.ogg"), reloaded.getUnlockedTracks());
        assertEquals(Set.of("ending_stars"), reloaded.getUnlockedEndings());
        assertEquals(Set.of("kompass_erhalten"), reloaded.getUnlockedAchievements());
    }

    @Test
    void preferencesStateRoundTripsAndNormalizesDefaults(@TempDir Path tempDir) throws Exception {
        Path savesDir = tempDir.resolve("saves");

        PreferencesState defaults = PreferencesState.load(savesDir);
        assertEquals(1.0f, defaults.getMasterVolume(), 1e-6f);
        assertEquals(0.8f, defaults.getMusicVolume(), 1e-6f);
        assertEquals(0.9f, defaults.getSoundVolume(), 1e-6f);
        assertEquals(1.0f, defaults.getVoiceVolume(), 1e-6f);
        assertEquals(0.7f, defaults.getAmbientVolume(), 1e-6f);
        assertEquals(40.0f, defaults.getTextSpeedCps(), 1e-6f);
        assertEquals(2.5f, defaults.getAutoForwardDelaySeconds(), 1e-6f);
        assertFalse(defaults.isSkipUnread());
        assertFalse(defaults.isSkipAfterChoices());
        assertFalse(defaults.isFullscreen());
        assertEquals("2D", defaults.getRenderMode());
        assertEquals("deutsch", defaults.getLanguage());
        assertEquals("ADV", defaults.getDialogueMode());
        assertFalse(defaults.isSelfVoicing());
        assertFalse(defaults.isHighContrastDyslexiaFont());
        assertEquals(1.0f, defaults.getUiTextScale(), 1e-6f);

        defaults.setMasterVolume(0.65f);
        defaults.setMusicVolume(0.4f);
        defaults.setSoundVolume(0.55f);
        defaults.setVoiceVolume(0.95f);
        defaults.setAmbientVolume(0.3f);
        defaults.setTextSpeedCps(75.0f);
        defaults.setAutoForwardDelaySeconds(1.25f);
        defaults.setSkipUnread(true);
        defaults.setSkipAfterChoices(true);
        defaults.setFullscreen(true);
        defaults.setRenderMode("3D");
        defaults.setLanguage("english");
        defaults.setDialogueMode("nvl");
        defaults.setSelfVoicing(true);
        defaults.setHighContrastDyslexiaFont(true);
        defaults.setUiTextScale(1.35f);

        defaults.save(savesDir);
        Path prefsFile = savesDir.resolve(PreferencesState.FILE_NAME);
        assertTrue(Files.isRegularFile(prefsFile));

        PreferencesState loaded = PreferencesState.load(savesDir);
        assertEquals(defaults, loaded);
        assertEquals("NVL", loaded.getDialogueMode());
        assertEquals("3D", loaded.getRenderMode());

        // Clamping validation
        loaded.setMasterVolume(2.5f);
        loaded.setMusicVolume(-1.0f);
        assertEquals(1.0f, loaded.getMasterVolume(), 1e-6f);
        assertEquals(0.0f, loaded.getMusicVolume(), 1e-6f);
    }

    @Test
    void sessionStateTracksPlaytimeFlagsAndResetsCleanly() {
        SessionState session = new SessionState();
        assertNotNull(session.getSessionStartTime());
        assertEquals(0.0, session.getPlaytimeSeconds(), 1e-9);
        assertNull(session.getActiveSaveSlotId());
        assertNull(session.getLastAutoSaveTime());

        session.updatePlaytime(1.5);
        session.updatePlaytime(2.25);
        session.updatePlaytime(-10.0); // ignored
        session.updatePlaytime(Double.NaN); // ignored
        assertEquals(3.75, session.getPlaytimeSeconds(), 1e-9);

        session.setActiveSaveSlotId("slot_1");
        session.markAutoSaved();
        session.setFlag("skipping", true);
        session.setFlag("auto_forward", false);

        assertEquals("slot_1", session.getActiveSaveSlotId());
        assertNotNull(session.getLastAutoSaveTime());
        assertTrue(session.getFlag("skipping"));
        assertFalse(session.getFlag("auto_forward"));
        assertFalse(session.getFlag("unset"));
        assertTrue(session.getFlag("unset", true));
        assertEquals(Map.of("skipping", true, "auto_forward", false), session.getSessionFlags());

        assertThrows(IllegalArgumentException.class, () -> session.setPlaytimeSeconds(-0.1));

        session.reset();
        assertEquals(0.0, session.getPlaytimeSeconds(), 1e-9);
        assertNull(session.getActiveSaveSlotId());
        assertNull(session.getLastAutoSaveTime());
        assertTrue(session.getSessionFlags().isEmpty());
    }

    @Test
    void historyLogEnforcesCapacityTruncatesOnJumpBackAndRestoresSnapshots() {
        HistoryLog defaultLog = new HistoryLog();
        assertEquals(500, defaultLog.getMaxEntries());

        HistoryLog log = new HistoryLog(3);
        Instant t1 = Instant.parse("2026-11-23T18:00:00Z");
        Instant t2 = Instant.parse("2026-11-23T18:00:05Z");
        Instant t3 = Instant.parse("2026-11-23T18:00:10Z");
        Instant t4 = Instant.parse("2026-11-23T18:00:15Z");

        log.append(1L, "line_1", "Lena", "#58a6ff", "Line 1", null, t1);
        log.append(2L, "line_2", "Lena", "#58a6ff", "Line 2", "v2.ogg", t2);
        log.append(3L, "line_3", null, null, "Line 3", null, t3);
        log.append(4L, "line_4", "Kaito", "#ffffff", "Line 4", null, t4);

        // Capacity=3 evicted line_1
        assertEquals(3, log.size());
        assertEquals(List.of("Line 2", "Line 3", "Line 4"),
                log.getEntries().stream().map(HistoryLog.HistoryEntry::text).toList());
        assertEquals(List.of("Line 3", "Line 4"),
                log.getRecentEntries(2).stream().map(HistoryLog.HistoryEntry::text).toList());

        List<HistoryLog.HistoryEntry> savedSnapshot = log.snapshot();

        // Jump-back to checkpoint 2 truncates entries after checkpoint 2
        log.truncateAfterCheckpoint(2L);
        assertEquals(1, log.size());
        assertEquals("Line 2", log.getEntries().get(0).text());

        // Restore full snapshot
        log.restore(savedSnapshot);
        assertEquals(3, log.size());
        assertEquals(List.of("Line 2", "Line 3", "Line 4"),
                log.getEntries().stream().map(HistoryLog.HistoryEntry::text).toList());

        log.clear();
        assertTrue(log.isEmpty());
    }
}
