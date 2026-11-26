package de.swfdtf.e2e.tier2;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.swfdtf.audio.AudioEngine;
import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineMode;
import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2eFixtureFactory;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.state.AutoSaveScheduler;
import de.swfdtf.state.HistoryLog;
import de.swfdtf.state.PersistentState;
import de.swfdtf.state.SaveManager;
import de.swfdtf.state.SaveMigrationService;
import de.swfdtf.state.SaveMigrationStep;
import de.swfdtf.state.SaveSlotData;
import de.swfdtf.state.SaveSlotQueryService;
import de.swfdtf.ui.GameRuntimeCoordinator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 2 boundary, security, and negative E2E tests for Feature Area F5: path-traversal slot
 * ID rejection, {@code SaveValueCodec} gadget-class allowlist enforcement, broken migration chains
 * and future save rejection, corrupt primary + corrupt {@code .bak} failure handling, rollback
 * depth and {@code block_rollback} jump boundaries, auto-save manual slot isolation, and empty
 * stage/audio rollback edge cases.
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER2)
public final class F05PersistenceRollbackBoundaryE2eTest {

    /** Creates a new {@code F05PersistenceRollbackBoundaryE2eTest} instance. */
    public F05PersistenceRollbackBoundaryE2eTest() {
    }

    /**
     * Verifies baseline {@link SaveManager} boundary validation: path traversal slot IDs
     * ({@code ../escape}, {@code a/b}, blank) throw {@link IllegalArgumentException}, and corrupt
     * {@code .ksav} without a valid {@code .bak} throws {@link IOException}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if file setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void pathTraversalSlotIdsAndCorruptGzipSavesRejectedCleanly(@TempDir Path tempDir) throws Exception {
        Path savesDir = tempDir.resolve("saves");
        SaveManager saveManager = new SaveManager(savesDir);

        assertThrows(IllegalArgumentException.class, () -> saveManager.slotExists("../escape"));
        assertThrows(IllegalArgumentException.class, () -> saveManager.slotExists("sub/slot"));
        assertThrows(IllegalArgumentException.class, () -> saveManager.slotExists("   "));

        Files.createDirectories(savesDir);
        Files.write(savesDir.resolve("corrupt.ksav"), new byte[]{1, 2, 3, 4, 5});
        assertThrows(IOException.class, () -> saveManager.loadSlot("corrupt"));
    }

    /**
     * Verifies baseline {@link SaveSlotQueryService} resilience on empty directories and corrupt
     * {@code .ksav} files alongside valid slots.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void queryServiceSkipsCorruptSlotsAndHandlesEmptyDir(@TempDir Path tempDir) throws Exception {
        Path savesDir = tempDir.resolve("saves");
        SaveManager saveManager = new SaveManager(savesDir);
        SaveSlotQueryService queryService = new SaveSlotQueryService(saveManager);

        assertTrue(queryService.search("").isEmpty());

        SaveSlotData valid = new SaveSlotData(
                new SaveSlotData.SaveMetadata("slot_1", "Hero", Instant.now(), "ch1", "Hallo", 3.0, "1.0", 0, 0, null),
                Map.of("gold", 50),
                null,
                List.of()
        );
        saveManager.saveSlot("slot_1", valid);
        Files.write(savesDir.resolve("broken.ksav"), new byte[]{0x00, 0x11, 0x22});

        List<SaveSlotData> found = queryService.search("");
        assertEquals(1, found.size());
        assertEquals("slot_1", found.get(0).metadata().slotId());
    }

    /**
     * Verifies goal {@code #0514} security boundary: {@code SaveValueCodec} strictly enforces a
     * class allowlist and rejects tampered {@code __vjavaClass} gadget payloads
     * ({@code java.lang.StringBuilder}, {@code java.lang.ProcessBuilder},
     * {@code javax.script.ScriptEngineManager}) rather than instantiating arbitrary JDK classes.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if file setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void saveValueCodecBlocksUnsafeNonAllowlistedClassGadgets(@TempDir Path tempDir) throws Exception {
        Path savesDir = tempDir.resolve("saves");
        Files.createDirectories(savesDir);
        SaveManager saveManager = new SaveManager(savesDir);

        List<String> disallowedClasses = List.of(
                "java.lang.StringBuilder",
                "javax.script.ScriptEngineManager",
                "java.lang.ProcessBuilder"
        );
        for (int i = 0; i < disallowedClasses.size(); i++) {
            String className = disallowedClasses.get(i);
            String slotId = "tampered_" + i;
            writeTamperedClassSlot(savesDir, slotId, className);

            Exception ex = assertThrows(
                    Exception.class,
                    () -> saveManager.loadSlot(slotId),
                    "Disallowed class '" + className + "' must be blocked by SaveValueCodec allowlist"
            );
            String fullMessage = collectExceptionMessages(ex).toLowerCase(Locale.ROOT);
            assertTrue(
                    ex instanceof SecurityException
                            || fullMessage.contains("allow")
                            || fullMessage.contains("forbidden")
                            || fullMessage.contains("disallowed")
                            || fullMessage.contains("unsafe")
                            || fullMessage.contains("whitelist")
                            || fullMessage.contains("not permitted")
                            || fullMessage.contains("blocked"),
                    "Expected allowlist/security rejection for '" + className + "', got: " + fullMessage
            );
        }
    }

    /**
     * Verifies goal {@code #0512} boundary behavior: {@link SaveMigrationService} wired in
     * {@link EngineApplication} rejects broken chains and future save versions ({@code v99 > target})
     * cleanly without corrupting live session state.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void saveMigrationRejectsBrokenChainDowngradeAndFutureSave(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f05_migration_boundary");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                label start:
                    $ intact = 777
                    "Start."
                    return
                """);

        EngineConfig cfg = EngineConfig.builder()
                .engineMode(EngineMode.STANDALONE_GAME)
                .projectDir(projectDir)
                .headless(true)
                .smokeTest(false)
                .build();
        try (EngineApplication app = new EngineApplication(cfg, null, new GameRuntimeCoordinator(false))) {
            app.initialize();
            SaveMigrationService wired = app.getApi().getService(SaveMigrationService.class);
            assertNotNull(wired, "SaveMigrationService must be wired in EngineAPI (#0512)");

            // Broken chain check on isolated pipeline
            SaveMigrationService broken = new SaveMigrationService(1);
            broken.registerStep(noopStep(1, 2));
            broken.registerStep(noopStep(3, 4));
            assertThrows(IllegalStateException.class, broken::validateChain);

            // Future save version must fail cleanly without overwriting intact=777
            GameRuntimeCoordinator coordinator = (GameRuntimeCoordinator) app.getGameHost();
            ScriptSession session = coordinator.getSession();
            session.start();

            SaveSlotData futureSave = SaveMigrationService.withVersion(new SaveSlotData(
                    new SaveSlotData.SaveMetadata(
                            "future", "Future", Instant.now(), "start", "X", 1.0, "99", 0, 0, null),
                    Map.of("intact", 999),
                    null,
                    List.of()
            ), 99);
            session.getSaveManager().saveSlot("future", futureSave);

            assertThrows(Exception.class, () -> session.loadFromSlot("future"));
            assertEquals(777, session.getStateStore().getInt("intact", 0));
        }
    }

    /**
     * Verifies goals {@code #0515} and {@code #0516} boundary behavior: overwriting creates
     * {@code .bak}, corrupting BOTH primary and {@code .bak} throws {@link IOException} (never
     * silently returns empty state), and zero leftover {@code .tmp} files remain after failed saves.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void corruptPrimaryAndCorruptBakFailSafelyWithoutPartialState(@TempDir Path tempDir) throws Exception {
        Path savesDir = tempDir.resolve("saves");
        SaveManager saveManager = new SaveManager(savesDir);

        SaveSlotData v1 = new SaveSlotData(
                new SaveSlotData.SaveMetadata("slot_1", "V1", Instant.now(), "start", "L1", 1.0, "1.0", 0, 0, null),
                Map.of("val", 1),
                null,
                List.of()
        );
        SaveSlotData v2 = new SaveSlotData(
                new SaveSlotData.SaveMetadata("slot_1", "V2", Instant.now(), "start", "L2", 2.0, "1.0", 0, 0, null),
                Map.of("val", 2),
                null,
                List.of()
        );
        saveManager.saveSlot("slot_1", v1);
        saveManager.saveSlot("slot_1", v2);

        Path primary = savesDir.resolve("slot_1.ksav");
        Path backup = savesDir.resolve("slot_1.ksav.bak");
        assertTrue(Files.isRegularFile(backup), "Expected slot_1.ksav.bak after overwrite (#0516)");

        // Failed save (null data or unserializable target) must keep primary intact and leave zero .tmp files
        byte[] validBefore = Files.readAllBytes(primary);
        assertThrows(NullPointerException.class, () -> saveManager.saveSlot("slot_1", null));
        assertArrayEquals(validBefore, Files.readAllBytes(primary));
        try (Stream<Path> files = Files.list(savesDir)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }

        Files.write(primary, new byte[]{0x01, 0x02, 0x03});
        Files.write(backup, new byte[]{0x04, 0x05, 0x06});
        assertThrows(IOException.class, () -> saveManager.loadSlot("slot_1"));
    }

    /**
     * Verifies goals {@code #0517} and {@code #0518} boundary behavior: rolling back beyond the
     * bounded 200-step depth stops cleanly at the oldest retained checkpoint, and
     * {@link ScriptSession#jumpToHistoryEntry} across a {@code block_rollback} barrier returns
     * {@code false} leaving state unchanged.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void rollbackBeyondMaxDepthAndHistoryJumpPastBarrierBlocked(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f05_cap_barrier");
        StringBuilder script = new StringBuilder("label start:\n");
        for (int i = 1; i <= 250; i++) {
            if (i == 230) {
                script.append("    block_rollback\n");
            }
            script.append("    $ idx = ").append(i).append("\n");
            script.append("    \"Line ").append(i).append("\"\n");
        }
        script.append("    return\n");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, script.toString());

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            ScriptSession session = new ScriptSession(ProjectScanner.scan(projectDir), app.getApi());
            session.start();
            for (int i = 1; i < 229; i++) {
                session.advance();
            }

            assertTrue(session.getStateStore().getCheckpoints().size() <= 200,
                    "Checkpoint depth must be bounded <= 200 (#0517)");

            int rollbackSteps = 0;
            while (session.rollback()) {
                rollbackSteps++;
                assertTrue(rollbackSteps <= 205);
            }
            assertTrue(rollbackSteps >= 1 && rollbackSteps < 200);
            while (session.rollForward()) {
                // Return to line 229
            }

            // Cross block_rollback at line 230
            HistoryLog.HistoryEntry beforeBarrier = session.getHistoryLog().getEntries().get(
                    session.getHistoryLog().getEntries().size() - 1);
            session.advance();
            assertEquals(230, session.getStateStore().getInt("idx", 0));
            assertFalse(session.jumpToHistoryEntry(beforeBarrier),
                    "History jump across block_rollback must return false");
            assertEquals(230, session.getStateStore().getInt("idx", 0));
        }
    }

    /**
     * Verifies goal {@code #0511} boundary behavior: {@link AutoSaveScheduler} wired in
     * {@link EngineApplication} rotates {@code auto_1..auto_3}, never touches manual
     * {@code slot_1..slot_3}, and respects {@code setEnabled(false)}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void autoSaveNeverOverwritesManualSlotsAndHandlesDisabledState(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f05_autosave_bounds");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                label start:
                    "Line 1"
                    "Line 2"
                    return
                """);

        EngineConfig cfg = EngineConfig.builder()
                .engineMode(EngineMode.STANDALONE_GAME)
                .projectDir(projectDir)
                .headless(true)
                .smokeTest(false)
                .build();
        try (EngineApplication app = new EngineApplication(cfg, null, new GameRuntimeCoordinator(false))) {
            app.initialize();
            AutoSaveScheduler scheduler = app.getApi().getService(AutoSaveScheduler.class);
            assertNotNull(scheduler, "AutoSaveScheduler must be wired in EngineAPI (#0511)");

            GameRuntimeCoordinator coordinator = (GameRuntimeCoordinator) app.getGameHost();
            ScriptSession session = coordinator.getSession();
            session.start();

            session.saveToSlot("slot_1", "Manual 1", 0, 0, null);
            byte[] manual1 = Files.readAllBytes(projectDir.resolve("saves/slot_1.ksav"));

            for (int i = 0; i < 4; i++) {
                scheduler.forceSave();
            }
            assertTrue(session.getSaveManager().slotExists("auto_1"));
            assertTrue(session.getSaveManager().slotExists("auto_2"));
            assertTrue(session.getSaveManager().slotExists("auto_3"));
            assertArrayEquals(manual1, Files.readAllBytes(projectDir.resolve("saves/slot_1.ksav")));

            scheduler.setEnabled(false);
            assertTrue(scheduler.update(999.0).isEmpty());
        }
    }

    /**
     * Verifies goals {@code #0519} and {@code #0523} boundary behavior: rolling back from an
     * active music/sprite state to the initial empty state stops music and clears sprites cleanly
     * without NPE, and corrupt {@code persistent.dat} throws {@link IOException} cleanly.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void rollbackSyncHandlesStoppedAudioAndClearedSpritesGracefully(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f05_rollback_empty");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                image hero = "assets/images/bg/bg_default.png"
                label start:
                    "Noch still und leer."
                    show hero:
                        xpos 400.0
                        ypos 400.0
                    play music "assets/audio/tone.wav"
                    "Musik und Figur aktiv."
                    return
                """);

        EngineConfig cfg = EngineConfig.builder()
                .engineMode(EngineMode.STANDALONE_GAME)
                .projectDir(projectDir)
                .headless(true)
                .smokeTest(false)
                .build();
        try (EngineApplication app = new EngineApplication(cfg, null, new GameRuntimeCoordinator(false))) {
            app.initialize();
            GameRuntimeCoordinator coordinator = (GameRuntimeCoordinator) app.getGameHost();
            ScriptSession session = coordinator.getSession();
            AudioEngine audio = coordinator.getAudioEngine();
            session.start();
            app.stepOnce(0.05);
            assertFalse(audio.getChannel(AudioEngine.CHANNEL_MUSIC).isPlaying());

            session.advance();
            app.stepOnce(0.05);
            assertTrue(audio.getChannel(AudioEngine.CHANNEL_MUSIC).isPlaying());
            assertNotNull(session.getSceneController().getActiveSprite("hero"));

            assertTrue(session.rollback());
            assertFalse(audio.getChannel(AudioEngine.CHANNEL_MUSIC).isPlaying(),
                    "Rollback to pre-play checkpoint must stop active music (#0519)");
            assertTrue(session.getSceneController().getActiveSprites().isEmpty());
        }

        Path badSaves = tempDir.resolve("bad_saves");
        Files.createDirectories(badSaves);
        Files.write(badSaves.resolve(PersistentState.FILE_NAME), new byte[]{1, 2, 3, 4});
        assertThrows(IOException.class, () -> PersistentState.load(badSaves));
    }

    private static void writeTamperedClassSlot(Path savesDir, String slotId, String className) throws Exception {
        JsonObject meta = new JsonObject();
        meta.addProperty("slotId", slotId);
        meta.addProperty("customName", "Tampered");
        meta.addProperty("savedAt", Instant.now().toString());
        meta.addProperty("currentLabel", "start");
        meta.addProperty("dialoguePreview", "Tampered");
        meta.addProperty("playtimeSeconds", 0.0);
        meta.addProperty("gameVersion", "1.0.0");
        meta.addProperty("thumbnailWidth", 0);
        meta.addProperty("thumbnailHeight", 0);

        JsonObject gadget = new JsonObject();
        gadget.addProperty("__vjavaClass", className);
        if ("java.lang.ProcessBuilder".equals(className)) {
            JsonArray cmd = new JsonArray();
            cmd.add("echo");
            gadget.add("command", cmd);
        }

        JsonObject vars = new JsonObject();
        vars.add("exploit", gadget);

        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", 1);
        root.add("metadata", meta);
        root.add("variables", vars);

        Path target = savesDir.resolve(slotId + SaveManager.SAVE_EXTENSION);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(target)), StandardCharsets.UTF_8))) {
            writer.write(root.toString());
        }
    }

    private static String collectExceptionMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable curr = t;
        while (curr != null) {
            if (curr.getMessage() != null) {
                sb.append(curr.getMessage()).append(' ');
            }
            curr = curr.getCause();
        }
        return sb.toString();
    }

    private static SaveMigrationStep noopStep(int from, int to) {
        return new SaveMigrationStep() {
            @Override
            public int fromVersion() {
                return from;
            }

            @Override
            public int toVersion() {
                return to;
            }

            @Override
            public SaveSlotData migrate(SaveSlotData input) {
                return input;
            }
        };
    }
}
