package de.swfdtf.e2e.tier1;

import com.google.gson.JsonObject;
import de.swfdtf.audio.AudioEngine;
import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineMode;
import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2eFixtureFactory;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.e2e.support.E2eWorkspaceSupport;
import de.swfdtf.graphics.ObjectMotionController;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.state.AutoSaveScheduler;
import de.swfdtf.state.HistoryLog;
import de.swfdtf.state.PersistentState;
import de.swfdtf.state.PreferencesState;
import de.swfdtf.state.SaveManager;
import de.swfdtf.state.SaveMigrationService;
import de.swfdtf.state.SaveMigrationStep;
import de.swfdtf.state.SaveSlotData;
import de.swfdtf.state.SaveSlotQueryService;
import de.swfdtf.state.SessionState;
import de.swfdtf.tools.VJavaLinter;
import de.swfdtf.ui.GameRuntimeCoordinator;
import de.swfdtf.ui.SaveLoadScreen;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 1 happy-path E2E tests for Feature Area F5: save/load persistence, custom
 * {@code @->{ ... }<<} class round-trips, {@link AutoSaveScheduler} integration,
 * {@link SaveMigrationService} live upgrades, fast metadata-only {@link SaveSlotQueryService},
 * atomic writes with {@code fsync} and {@code .bak} recovery, bounded rollback depth,
 * {@code block_rollback} UI toast feedback, stage/audio/motion rollback sync, and immediate
 * {@link PersistentState} flushing.
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER1)
public final class F05PersistenceRollbackE2eTest {

    private static final long FIVE_MEGABYTES = 5L * 1024L * 1024L;

    /** Creates a new {@code F05PersistenceRollbackE2eTest} instance. */
    public F05PersistenceRollbackE2eTest() {
    }

    /**
     * Verifies baseline F5 lifecycle and goal {@code #0514} positive path: rollback, roll-forward,
     * {@link HistoryLog} jump-back, {@code block_rollback} barrier, and persistence of custom
     * {@code InventarItem}/{@code SpezialGegenstand} instances across fresh {@link ScriptSession} restart.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or save/load fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void baselineRollbackRollForwardHistoryAndCustomClassSaveLoadWork(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f05_baseline");
        E2eWorkspaceSupport.copyResourceTree("e2e/f05/save_rollback_baseline", projectDir);
        E2eFixtureFactory.writeSolidPng(projectDir.resolve("assets/images/bg/bg_room.png"), 64, 64, 30, 40, 60, 255);

        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertTrue(lint.isClean(), "save_rollback_baseline must lint cleanly: " + lint.issues());

        try (EngineApplication app1 = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            ScriptSession session = new ScriptSession(ProjectScanner.scan(projectDir), app1.getApi());
            session.start();
            assertEquals(1, session.getStateStore().getInt("kapitel", 0));

            session.advance();
            assertEquals(2, session.getStateStore().getInt("kapitel", 0));
            assertTrue(session.rollback());
            assertEquals(1, session.getStateStore().getInt("kapitel", 0));
            assertTrue(session.rollForward());
            assertEquals(2, session.getStateStore().getInt("kapitel", 0));

            HistoryLog.HistoryEntry firstEntry = session.getHistoryLog().getEntries().get(0);
            assertTrue(session.jumpToHistoryEntry(firstEntry));
            assertEquals(1, session.getStateStore().getInt("kapitel", 0));
            assertTrue(session.rollForward());

            session.advance();
            assertEquals(3, session.getStateStore().getInt("kapitel", 0));
            assertFalse(session.rollback(), "block_rollback barrier must prevent rollback");

            session.saveToSlot("slot_1", "Relikt Save", 0, 0, null);
        }

        // Brand-new ScriptSession simulates app restart and verifies custom @->{}<< class survival
        try (EngineApplication app2 = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            ScriptSession reloaded = new ScriptSession(ProjectScanner.scan(projectDir), app2.getApi());
            reloaded.loadFromSlot("slot_1");

            assertEquals(3, reloaded.getStateStore().getInt("kapitel", 0));
            Object item = reloaded.getStateStore().get("aktives_item");
            assertNotNull(item, "Custom SpezialGegenstand must survive Save -> Restart -> Load");
            assertTrue(item.getClass().getName().endsWith("SpezialGegenstand"));

            Field nameField = item.getClass().getField("name");
            Field rarityField = item.getClass().getField("seltenheit");
            assertEquals("Sternenkompass", nameField.get(item));
            assertEquals("Relikt", rarityField.get(item));
        }
    }

    /**
     * Verifies baseline {@link PreferencesState} and {@link SessionState} persistence across restarts.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if save/load fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void preferencesAndSessionPlaytimePersistAcrossRestarts(@TempDir Path tempDir) throws Exception {
        Path savesDir = tempDir.resolve("saves");
        PreferencesState prefs = new PreferencesState();
        prefs.setMusicVolume(0.45f);
        prefs.setLanguage("english");
        prefs.setSelfVoicing(true);
        prefs.save(savesDir);

        PreferencesState loaded = PreferencesState.load(savesDir);
        assertEquals(0.45f, loaded.getMusicVolume(), 0.001f);
        assertEquals("english", loaded.getLanguage());
        assertTrue(loaded.isSelfVoicing());

        SessionState sessionState = new SessionState();
        sessionState.updatePlaytime(12.5);
        assertEquals(12.5, sessionState.getPlaytimeSeconds(), 0.001);
    }

    /**
     * Verifies goal {@code #0511}: {@link AutoSaveScheduler} is integrated into
     * {@link GameRuntimeCoordinator}, automatically creating rotating {@code auto_1..auto_3}
     * saves on menu choices / intervals without touching manual {@code slot_1.ksav}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void autoSaveSchedulerIntegratedInGameRuntimeCoordinator(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f05_autosave");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                label start:
                    $ manual_tag = "safe"
                    "Vor Entscheidung."
                    menu "Wahl":
                        "Weiter" -> jump next
                    return
                label next:
                    "Danach."
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
            assertNotNull(scheduler, "AutoSaveScheduler must be wired and registered in EngineAPI (#0511)");

            GameRuntimeCoordinator coordinator = (GameRuntimeCoordinator) app.getGameHost();
            ScriptSession session = coordinator.getSession();
            session.start();
            session.saveToSlot("slot_1", "Manual Save", 0, 0, null);
            byte[] manualBefore = Files.readAllBytes(projectDir.resolve("saves/slot_1.ksav"));

            // Advance into menu: choice -> coordinator auto-save should trigger on choice boundary
            session.advance();
            app.stepOnce(1.0 / 60.0);
            assertEquals(ScriptRuntime.Status.WAITING_MENU, session.getRuntime().getStatus());
            assertTrue(session.getSaveManager().slotExists("auto_1"),
                    "GameRuntimeCoordinator must auto-create auto_1.ksav before menu choice");
            assertEquals(
                    Arrays.hashCode(manualBefore),
                    Arrays.hashCode(Files.readAllBytes(projectDir.resolve("saves/slot_1.ksav"))),
                    "Auto-save must never overwrite manual slot_1.ksav"
            );
        }
    }

    /**
     * Verifies goal {@code #0512}: {@link SaveMigrationService} is integrated into live save
     * loading so a v1 save ({@code old_gold = 250}) automatically upgrades to v3 with intact
     * {@code __migrationTrail}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or load fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void liveSaveMigrationUpgradesV1ToV3Losslessly(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f05_migration");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                label start:
                    "Migrated."
                    return
                """);

        SaveManager rawManager = new SaveManager(projectDir.resolve("saves"));
        SaveSlotData v1Save = SaveMigrationService.withVersion(new SaveSlotData(
                new SaveSlotData.SaveMetadata(
                        "slot_1", "Old Save", Instant.now(), "start", "Legacy", 5.0, "1", 0, 0, null),
                Map.of("old_gold", 250),
                new SaveSlotData.SavedScriptPosition(
                        "start", 0, List.of(), "WAITING_DIALOGUE", null, null, "Migrated.", false, List.of(),
                        null, null),
                List.of()
        ), 1);
        rawManager.saveSlot("slot_1", v1Save);

        EngineConfig cfg = EngineConfig.builder()
                .engineMode(EngineMode.STANDALONE_GAME)
                .projectDir(projectDir)
                .headless(true)
                .smokeTest(false)
                .build();
        try (EngineApplication app = new EngineApplication(cfg, null, new GameRuntimeCoordinator(false))) {
            app.initialize();
            SaveMigrationService migrationService = app.getApi().getService(SaveMigrationService.class);
            assertNotNull(migrationService, "SaveMigrationService must be wired as EngineAPI service (#0512)");
            migrationService.registerStep(new SaveMigrationStep() {
                @Override
                public int fromVersion() {
                    return 1;
                }

                @Override
                public int toVersion() {
                    return 2;
                }

                @Override
                public SaveSlotData migrate(SaveSlotData input) {
                    var vars = new java.util.LinkedHashMap<>(input.variables());
                    Object oldGold = vars.remove("old_gold");
                    vars.put("gold", oldGold);
                    return new SaveSlotData(input.metadata(), vars, input.scriptPosition(), input.historyEntries());
                }
            });
            migrationService.registerStep(new SaveMigrationStep() {
                @Override
                public int fromVersion() {
                    return 2;
                }

                @Override
                public int toVersion() {
                    return 3;
                }

                @Override
                public SaveSlotData migrate(SaveSlotData input) {
                    var vars = new java.util.LinkedHashMap<>(input.variables());
                    vars.put("migrated_v3", true);
                    return new SaveSlotData(input.metadata(), vars, input.scriptPosition(), input.historyEntries());
                }
            });

            GameRuntimeCoordinator coordinator = (GameRuntimeCoordinator) app.getGameHost();
            coordinator.getSession().loadFromSlot("slot_1");
            assertEquals(250, coordinator.getSession().getStateStore().getInt("gold", 0));
            assertEquals(3, coordinator.getSession().getStateStore().getInt(
                    SaveMigrationService.VERSION_VARIABLE_KEY, 0));
            assertEquals(List.of("1->2", "2->3"),
                    coordinator.getSession().getStateStore().get(SaveMigrationService.MIGRATION_TRAIL_KEY));
        }
    }

    /**
     * Verifies goal {@code #0513}: {@link SaveSlotQueryService} and {@link SaveLoadScreen}
     * query metadata across 100 save slots without deserializing custom {@code __vjavaClass}
     * payloads and open pages {@code 0..4} in under 10 ms after warmup.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or I/O fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void saveSlotQueryServiceAndSaveLoadScreenHandle100SlotsUnder10Ms(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f05_query_100");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                label start:
                    "Slots."
                    return
                """);

        Path savesDir = projectDir.resolve("saves");
        Files.createDirectories(savesDir);
        for (int i = 1; i <= 100; i++) {
            writeLightweightSlotWithExternalUserClass(savesDir, "slot_" + i, "Kapitel " + (i % 2 + 1), i);
        }

        EngineConfig cfg = EngineConfig.builder()
                .engineMode(EngineMode.STANDALONE_GAME)
                .projectDir(projectDir)
                .headless(true)
                .smokeTest(false)
                .build();
        try (EngineApplication app = new EngineApplication(cfg, null, new GameRuntimeCoordinator(false))) {
            app.initialize();
            SaveSlotQueryService queryService = new SaveSlotQueryService(
                    ((GameRuntimeCoordinator) app.getGameHost()).getSession().getSaveManager());

            // Must return all 100 slots using readSlotMetadata() without failing on unknown __vjavaClass
            List<SaveSlotData> all = queryService.search("");
            assertEquals(100, all.size(),
                    "SaveSlotQueryService must read metadata headers without decoding custom classes (#0513)");

            SaveLoadScreen saveLoadScreen = (SaveLoadScreen) ((GameRuntimeCoordinator) app.getGameHost())
                    .getScreenManager()
                    .getScreen(SaveLoadScreen.SCREEN_NAME);
            assertNotNull(saveLoadScreen);

            // Warmup then measure opening pages 0..4 and querying across 100 slots
            for (int p = 0; p < SaveLoadScreen.TOTAL_PAGES; p++) {
                saveLoadScreen.setPage(p);
            }
            long startNs = System.nanoTime();
            for (int p = 0; p < SaveLoadScreen.TOTAL_PAGES; p++) {
                saveLoadScreen.setPage(p);
            }
            List<SaveSlotData> filtered = queryService.search("Kapitel 1");
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

            assertEquals(50, filtered.size());
            assertTrue(elapsedMs < 10L,
                    "Pages 1..5 + query across 100 slots exceeded 10 ms budget: " + elapsedMs + " ms");
        }
    }

    /**
     * Verifies goals {@code #0515} and {@code #0516}: atomic save writes include {@code fsync}
     * ({@code FileChannel.force(true)}) durability, create a {@code .bak} backup when overwriting
     * an existing slot, and automatically recover from the validated {@code .bak} when the primary
     * {@code .ksav} is corrupted.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if file I/O fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void atomicWriteAndBakBackupRecoverFromCorruptedPrimarySlot(@TempDir Path tempDir) throws Exception {
        Path stateDir = E2eWorkspaceSupport.repoRoot().resolve("src/main/java/de/swfdtf/state");
        String saveMgrSrc = Files.readString(stateDir.resolve("SaveManager.java"), StandardCharsets.UTF_8);
        String persistentSrc = Files.readString(stateDir.resolve("PersistentState.java"), StandardCharsets.UTF_8);
        String prefsSrc = Files.readString(stateDir.resolve("PreferencesState.java"), StandardCharsets.UTF_8);
        assertTrue(saveMgrSrc.contains("force(true)") && persistentSrc.contains("force(true)")
                        && prefsSrc.contains("force(true)"),
                "SaveManager, PersistentState, and PreferencesState must fsync via FileChannel.force(true) (#0515)");

        Path savesDir = tempDir.resolve("saves");
        SaveManager saveManager = new SaveManager(savesDir);

        SaveSlotData firstSave = new SaveSlotData(
                new SaveSlotData.SaveMetadata("slot_1", "First", Instant.now(), "start", "L1", 1.0, "1.0", 0, 0, null),
                Map.of("score", 100),
                null,
                List.of()
        );
        SaveSlotData secondSave = new SaveSlotData(
                new SaveSlotData.SaveMetadata("slot_1", "Second", Instant.now(), "start", "L2", 2.0, "1.0", 0, 0, null),
                Map.of("score", 200),
                null,
                List.of()
        );

        saveManager.saveSlot("slot_1", firstSave);
        saveManager.saveSlot("slot_1", secondSave);

        Path primary = savesDir.resolve("slot_1.ksav");
        Path backup = savesDir.resolve("slot_1.ksav.bak");
        assertTrue(Files.isRegularFile(backup), "Overwriting slot_1 must produce slot_1.ksav.bak (#0516)");

        // Corrupt primary save file -> loadSlot must fall back automatically to validated .bak (score == 100)
        Files.write(primary, new byte[]{0x1F, (byte) 0x8B, 0x00, 0x01, 0x02});
        SaveSlotData recovered = saveManager.loadSlot("slot_1");
        assertEquals(100, ((Number) recovered.variables().get("score")).intValue());
    }

    /**
     * Verifies goal {@code #0517}: {@link de.swfdtf.state.RevertableStateStore} bounds rollback
     * checkpoint depth (e.g. {@code maxRollbackDepth = 200}) and keeps 500 dialogue steps within
     * a {@code < 5 MB} rollback buffer heap budget.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void rollbackDepthBoundedTo200StepsUnder5MbHeapBudget(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f05_long_500");
        StringBuilder script = new StringBuilder("label start:\n");
        for (int i = 1; i <= 350; i++) {
            script.append("    $ step_counter = ").append(i).append("\n");
            script.append("    \"Schritt ").append(i).append("\"\n");
        }
        script.append("    return\n");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, script.toString());

        System.gc();
        long heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            ScriptSession session = new ScriptSession(ProjectScanner.scan(projectDir), app.getApi());
            session.start();
            for (int i = 1; i < 350; i++) {
                session.advance();
            }

            int retained = session.getStateStore().getCheckpoints().size();
            assertTrue(retained <= 200,
                    "RevertableStateStore must cap checkpoints at maxRollbackDepth <= 200, got: " + retained);

            System.gc();
            long heapDelta = Math.max(
                    0L,
                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) - heapBefore
            );
            assertTrue(heapDelta < FIVE_MEGABYTES,
                    "Rollback buffer heap delta exceeded 5 MB: " + (heapDelta / (1024L * 1024L)) + " MB");
            assertTrue(session.rollback());
        }
    }

    /**
     * Verifies goal {@code #0518}: attempting rollback across {@code block_rollback} via
     * {@code PageUp} keeps script state untouched and displays user feedback via
     * {@link de.swfdtf.ui.NotificationToastOverlay}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void blockRollbackDisplaysUserFeedbackToastWithoutMutatingState(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f05_block_toast");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                label start:
                    $ phase = 1
                    "Vor Sperre."
                    block_rollback
                    $ phase = 2
                    "Nach Sperre."
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
            session.start();
            session.advance();
            app.stepOnce(1.0 / 60.0);
            assertEquals("Nach Sperre.", session.getRuntime().getActiveDialogue().interpolatedText());

            app.getApi().getInput().onKey(GLFW.GLFW_KEY_PAGE_UP, 0, GLFW.GLFW_PRESS, 0);
            app.stepOnce(1.0 / 60.0);
            app.getApi().getInput().onKey(GLFW.GLFW_KEY_PAGE_UP, 0, GLFW.GLFW_RELEASE, 0);
            app.stepOnce(1.0 / 60.0);

            assertEquals(2, session.getStateStore().getInt("phase", 0));
            assertEquals("Nach Sperre.", session.getRuntime().getActiveDialogue().interpolatedText());
            assertTrue(coordinator.getToastOverlay().isToastVisible(),
                    "Blocked rollback must trigger NotificationToastOverlay user feedback (#0518)");
        }
    }

    /**
     * Verifies goal {@code #0519}: {@link ScriptSession#rollback()} synchronizes stage sprites,
     * active audio tracks ({@link AudioEngine}), and {@link ObjectMotionController} WASD bindings.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void rollbackRestoresStageSpritesAudioAndMotionCoordinates(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f05_rollback_sync");
        E2eWorkspaceSupport.createEmptyProject(projectDir, "F05 Rollback Sync", "2D");
        E2eFixtureFactory.writeSolidPng(projectDir.resolve("assets/images/bg/day.png"), 32, 32, 80, 140, 220, 255);
        E2eFixtureFactory.writeSolidPng(projectDir.resolve("assets/images/bg/night.png"), 32, 32, 10, 15, 40, 255);
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/sprites/hero.png"), 32, 32, 240, 200, 60, 255);
        E2eFixtureFactory.writeSineWav(projectDir.resolve("assets/audio/day.wav"), 220.0, 2.0, 22_050);
        E2eFixtureFactory.writeSineWav(projectDir.resolve("assets/audio/night.wav"), 440.0, 2.0, 22_050);

        E2eWorkspaceSupport.writeScript(projectDir, "main.vjava", """
                image bg_day = "assets/images/bg/day.png"
                image bg_night = "assets/images/bg/night.png"
                image hero = "assets/images/sprites/hero.png"
                transform pos_a:
                    xpos 100.0
                    ypos 200.0
                transform pos_b:
                    xpos 900.0
                    ypos 700.0
                label start:
                    scene bg_day
                    show hero at pos_a
                    wasd_init hero speed 300
                    play music "assets/audio/day.wav"
                    "Tag."
                    scene bg_night
                    show hero at pos_b
                    wasd_stop hero
                    play music "assets/audio/night.wav"
                    "Nacht."
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

            assertEquals("assets/audio/day.wav", audio.getChannel(AudioEngine.CHANNEL_MUSIC).getAssetPath());
            assertTrue(session.getSceneController().getMotionController().hasActiveWasd());

            session.advance();
            app.stepOnce(0.05);
            assertEquals("assets/audio/night.wav", audio.getChannel(AudioEngine.CHANNEL_MUSIC).getAssetPath());
            assertFalse(session.getSceneController().getMotionController().hasActiveWasd());

            assertTrue(session.rollback());
            SpriteNode hero = session.getSceneController().getActiveSprite("hero");
            assertNotNull(hero);
            assertEquals(100.0f, hero.getTransform().getXpos(), 0.5f);
            assertEquals(200.0f, hero.getTransform().getYpos(), 0.5f);
            assertEquals("assets/audio/day.wav", audio.getChannel(AudioEngine.CHANNEL_MUSIC).getAssetPath(),
                    "Rollback must revert AudioEngine music channel back to day.wav (#0519)");
            assertTrue(session.getSceneController().getMotionController().hasActiveWasd(),
                    "Rollback must restore active WASD bindings (#0519)");
        }
    }

    /**
     * Verifies goal {@code #0523}: critical unlocks in {@link PersistentState} (endings, CGs,
     * music tracks, and flags) flush immediately to {@code saves/persistent.dat} without needing
     * an explicit {@code quickSave()} or {@code saveMetaState()}, surviving abrupt termination.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or loading fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void persistentUnlocksSurviveImmediateTerminationWithoutExplicitSave(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f05_persistent_sync");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                label start:
                    :: Unlock global progression @->{
                        persistent.unlockEnding("true_end");
                        persistent.unlockCg("cg_1");
                        persistent.set("meta_done", true);
                    }<<
                    play music "assets/audio/tone.wav"
                    "Freigeschaltet."
                    return
                """);

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            ScriptSession session = new ScriptSession(ProjectScanner.scan(projectDir), app.getApi());
            session.start();
            // Intentionally DO NOT call quickSave(), saveToSlot(), or saveMetaState()!
        }

        PersistentState reloaded = PersistentState.load(projectDir.resolve("saves"));
        assertTrue(reloaded.isEndingUnlocked("true_end"),
                "Ending unlock must flush immediately to persistent.dat (#0523)");
        assertTrue(reloaded.isCgUnlocked("cg_1"),
                "CG unlock must flush immediately to persistent.dat (#0523)");
        assertTrue(reloaded.isTrackUnlocked("assets/audio/tone.wav"),
                "Music track unlock must flush immediately to persistent.dat (#0523)");
        assertEquals(Boolean.TRUE, reloaded.get("meta_done"));
    }

    private static void writeLightweightSlotWithExternalUserClass(
            Path savesDir,
            String slotId,
            String label,
            int index
    ) throws Exception {
        JsonObject meta = new JsonObject();
        meta.addProperty("slotId", slotId);
        meta.addProperty("customName", "Slot " + index + " " + label);
        meta.addProperty("savedAt", Instant.EPOCH.plusSeconds(index).toString());
        meta.addProperty("currentLabel", label);
        meta.addProperty("dialoguePreview", "Preview " + index);
        meta.addProperty("playtimeSeconds", index * 1.5);
        meta.addProperty("gameVersion", "1.0.0");
        meta.addProperty("thumbnailWidth", 0);
        meta.addProperty("thumbnailHeight", 0);

        JsonObject itemObj = new JsonObject();
        itemObj.addProperty("__vjavaClass", "VJavaUserTypes$ExternalQuestItem");
        itemObj.addProperty("name", "Item " + index);

        JsonObject vars = new JsonObject();
        vars.add("quest_item", itemObj);

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
}
