package de.swfdtf.state;

import de.swfdtf.state.SaveRepository.FileSaveRepository;
import de.swfdtf.state.SaveRepository.InMemorySaveRepository;
import de.swfdtf.state.SaveSlotData.SaveMetadata;
import de.swfdtf.state.SaveSlotData.SavedScriptPosition;
import de.swfdtf.state.SaveSlotData.SavedVoiceBookmark;
import de.swfdtf.state.SaveWorkflowServices.CloudSyncAndProfileManager;
import de.swfdtf.state.SaveWorkflowServices.CloudSyncAndProfileManager.BetaDecisionTelemetry;
import de.swfdtf.state.SaveWorkflowServices.CloudSyncAndProfileManager.BonusSceneUnlockRegistry;
import de.swfdtf.state.SaveWorkflowServices.CloudSyncAndProfileManager.CloudMergeResult;
import de.swfdtf.state.SaveWorkflowServices.CloudSyncAndProfileManager.CodexUnlockTimeline;
import de.swfdtf.state.SaveWorkflowServices.CloudSyncAndProfileManager.PersistentMergeInput;
import de.swfdtf.state.SaveWorkflowServices.SaveIntegrityAndCodec;
import de.swfdtf.state.SaveWorkflowServices.SaveWorkflowController;
import de.swfdtf.state.SaveWorkflowServices.SaveWorkflowController.BatchCheckpointResult;
import de.swfdtf.state.SaveWorkflowServices.SaveWorkflowController.DefaultVariableDoc;
import de.swfdtf.state.SaveWorkflowServices.SaveWorkflowController.SaveOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit and stress tests for Chapter 7 save integrity, `.vsave` packaging, cloud-sync merges,
 * profile isolation, quick-save rings, trash/undo recovery, telemetry, and 1,000-cycle stress
 * (Wave P2-7).
 */
class WaveP2Chapter7SaveSyncAndStressTest {

    @Test
    void testHmacSigningAndTruncatedJsonRejection() throws IOException {
        String raw = "{\"hp\":42,\"name\":\"Schneewittchen\"}";
        String signed = SaveIntegrityAndCodec.wrapSignedJson(raw, "secret-key");
        assertTrue(SaveIntegrityAndCodec.verifyAndUnwrapSignedJson(signed, "secret-key").contains("42"));

        String truncated = signed.substring(0, signed.length() - 10);
        assertThrows(IOException.class,
                () -> SaveIntegrityAndCodec.verifyAndUnwrapSignedJson(truncated, "secret-key"));
        assertThrows(IOException.class,
                () -> SaveIntegrityAndCodec.verifyAndUnwrapSignedJson(signed, "wrong-key"));
    }

    @Test
    void testVsavePortableExportAndProjectIdVerification() throws IOException {
        byte[] fakePng = new byte[]{1, 2, 3, 4};
        SaveSlotData slot = createSlot("slot_1", "chapter_1", Map.of("name", "ÄÖÜ日本語🎭"), fakePng, 120.0);
        byte[] pkg = SaveIntegrityAndCodec.exportVsavePackage(slot, "kagayaki-demo");

        InMemorySaveRepository target = new InMemorySaveRepository();
        var imported = SaveIntegrityAndCodec.importVsavePackage(pkg, "kagayaki-demo", target, "imported_9");
        assertEquals("kagayaki-demo", imported.projectId());
        assertArrayEquals(fakePng, imported.slotData().metadata().thumbnailPng());
        assertEquals("ÄÖÜ日本語🎭", imported.slotData().variables().get("name"));
        assertTrue(target.exists("imported_9"));

        assertThrows(IOException.class,
                () -> SaveIntegrityAndCodec.importVsavePackage(pkg, "other-game", target, "bad"));
    }

    @Test
    void testGzipMagicVsPlainJsonDetectionAndPrettyToggle() throws IOException {
        SaveSlotData slot = createSlot("s1", "start", Map.of("x", 1), new byte[0], 10.0);
        byte[] gzipped = SaveIntegrityAndCodec.encodeJsonPayload(slot, true, false);
        byte[] plainPretty = SaveIntegrityAndCodec.encodeJsonPayload(slot, false, true);

        assertEquals(0x1F, gzipped[0] & 0xFF);
        assertEquals(0x8B, gzipped[1] & 0xFF);
        assertTrue(new String(plainPretty, StandardCharsets.UTF_8).contains("\n"));

        String fromGz = SaveIntegrityAndCodec.decodeAutoDetectGzipOrJson(gzipped);
        String fromPlain = SaveIntegrityAndCodec.decodeAutoDetectGzipOrJson(plainPretty);
        assertEquals(
                SaveValueCodec.createGson(false).fromJson(fromGz, SaveSlotData.class).metadata().slotId(),
                SaveValueCodec.createGson(false).fromJson(fromPlain, SaveSlotData.class).metadata().slotId()
        );
    }

    @Test
    void testPathTraversalAndOrphanedTempFileCleanup(@TempDir Path tempDir) throws IOException {
        assertThrows(IllegalArgumentException.class, () -> SaveRepository.validateSlotId("../evil"));
        assertThrows(IllegalArgumentException.class, () -> SaveRepository.validateSlotId("a/b"));

        Path validJson = tempDir.resolve("slot_1.ksav");
        Path validBak = tempDir.resolve("slot_1.ksav.bak");
        Path orphanTmp = tempDir.resolve("slot_1-aborted.tmp");
        Files.writeString(validJson, "ok");
        Files.writeString(validBak, "bak");
        Files.writeString(orphanTmp, "partial");

        int removed = FileSaveRepository.cleanupOrphanedTempFiles(tempDir);
        assertEquals(1, removed);
        assertTrue(Files.exists(validJson));
        assertTrue(Files.exists(validBak));
        assertFalse(Files.exists(orphanTmp));
    }

    @Test
    void testProfileAndPlatformDirectoryResolution(@TempDir Path tempDir) {
        Path p1 = CloudSyncAndProfileManager.resolveProfileDirectory(tempDir, "player_one");
        Path p2 = CloudSyncAndProfileManager.resolveProfileDirectory(tempDir, "player_two");
        assertNotEquals(p1, p2);
        assertEquals(tempDir.resolve("player_one").normalize(), p1);

        Path installDir = Path.of("/opt/kagayaki");
        Path home = Path.of("/home/user");
        assertEquals(installDir.resolve("saves"),
                CloudSyncAndProfileManager.resolvePlatformSaveDirectory(
                        "Kagayaki", true, installDir, true, home, "Linux"));
        assertEquals(home.resolve("Library").resolve("Application Support").resolve("Kagayaki").resolve("saves"),
                CloudSyncAndProfileManager.resolvePlatformSaveDirectory(
                        "Kagayaki", true, installDir, false, home, "Mac OS X"));
        assertEquals(home.resolve("AppData").resolve("Roaming").resolve("Kagayaki").resolve("saves"),
                CloudSyncAndProfileManager.resolvePlatformSaveDirectory(
                        "Kagayaki", false, installDir, true, home, "Windows 11"));
    }

    @Test
    void testCloudSyncUnionMergeAndNewGamePlusReset() {
        Instant t1 = Instant.parse("2026-06-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-02T11:00:00Z");
        SaveSlotData localSlot = createSlot("quick", "ch1", Map.of(), new byte[0], 100.0);
        SaveSlotData remoteSlot = new SaveSlotData(
                new SaveMetadata("quick", "Remote", t2, "ch2", "Hi", 200.0, "1.0", 0, 0, new byte[0]),
                Map.of(), null, List.of()
        );
        PersistentMergeInput local = new PersistentMergeInput(
                t1, Set.of("l1"), Set.of("ending_a"), Set.of("cg1"), Set.of("bgm1"), Set.of("ach1"),
                Optional.of(localSlot));
        PersistentMergeInput remote = new PersistentMergeInput(
                t2, Set.of("l2"), Set.of("ending_b"), Set.of("cg2"), Set.of("bgm2"), Set.of("ach2"),
                Optional.of(remoteSlot));

        CloudMergeResult merged = CloudSyncAndProfileManager.mergePersistentSnapshots(local, remote);
        assertEquals(Set.of("l1", "l2"), merged.mergedReadLines());
        assertEquals(Set.of("ending_a", "ending_b"), merged.mergedEndings());
        assertEquals("ch2", merged.winningLatestSlot().orElseThrow().metadata().currentLabel());

        SimpleStateStore runStore = new SimpleStateStore();
        runStore.set("story_flag", true);
        runStore.set("relic_count", 7);
        SimplePersistentAccess persistent = new SimplePersistentAccess();
        persistent.unlockEnding("ending_a");

        CloudSyncAndProfileManager.resetRunKeepPersistent(runStore, persistent, Set.of("relic_count"));
        assertFalse(runStore.has("story_flag"));
        assertEquals(7, runStore.getInt("relic_count", 0));
        assertTrue(runStore.getBoolean("ng_plus_active", false));
        assertTrue(persistent.isEndingUnlocked("ending_a"));

        // #0564 skip-seen stops at first unread content hash
        PreferencesState prefs = new PreferencesState();
        prefs.setSkipUnread(false);
        persistent.markLineRead("seen_1");
        assertFalse(CloudSyncAndProfileManager.shouldStopSkipAtLine(prefs, persistent, "seen_1"));
        assertTrue(CloudSyncAndProfileManager.shouldStopSkipAtLine(prefs, persistent, "unread_2"));
    }

    @Test
    void testLabelCompatibilityContentHashesEpisodeImportAndDiscoveryAnalytics() throws IOException {
        var aliasRes = CloudSyncAndProfileManager.resolveLabelCompatibility(
                "old_intro", "chapter_1", Set.of("chapter_1", "new_intro"), Map.of("old_intro", "new_intro"));
        assertEquals("new_intro", aliasRes.targetLabel());
        assertTrue(aliasRes.usedFallback());

        var missingRes = CloudSyncAndProfileManager.resolveLabelCompatibility(
                "deleted_scene", "chapter_1", Set.of("start", "chapter_1"), Map.of());
        assertEquals("chapter_1", missingRes.targetLabel());

        String idBeforeInsert = CloudSyncAndProfileManager.computeContentLineId("intro", "Hallo   Welt!");
        String idAfterShift = CloudSyncAndProfileManager.computeContentLineId("intro", "Hallo Welt!");
        assertEquals(idBeforeInsert, idAfterShift);

        InMemorySaveRepository ep1Repo = new InMemorySaveRepository();
        SaveSlotData ep1 = createSlot("ep1_end", "finale", Map.of("hero_name", "Nova", "secret_internal", 99),
                new byte[0], 500.0);
        ep1Repo.write("ep1_end", SaveIntegrityAndCodec.encodeJsonPayload(ep1, true, false));
        SimpleStateStore ep2Store = new SimpleStateStore();
        var imported = CloudSyncAndProfileManager.importPreviousEpisodeSave(
                ep1Repo, "ep1_end", Set.of("hero_name"), ep2Store);
        assertEquals(Map.of("hero_name", "Nova"), imported);
        assertFalse(ep2Store.has("secret_internal"));

        assertEquals(100.0, CloudSyncAndProfileManager.calculateDiscoveryPercent(
                Set.of("a", "b"), Set.of("a", "b")), 1e-6);
        String statsJson = CloudSyncAndProfileManager.exportChapterStatsJson(
                Map.of("ch1", 45.5), Set.of("ch1"), Set.of("ch1", "ch2"));
        assertTrue(statsJson.contains("50.0"));
    }

    @Test
    void testQuickSaveRingTrashUndoPreconditionsTimingPrngAndPagination() throws IOException {
        Clock clock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC);
        InMemorySaveRepository repo = new InMemorySaveRepository();
        SaveWorkflowController controller = new SaveWorkflowController(repo, clock);

        controller.setAllowSaveInTimedChoice(false);
        SaveOutcome deniedTimed = controller.saveSlot(
                "s1", createSlot("s1", "ch1", Map.of(), new byte[0], 10), true, false);
        assertFalse(deniedTimed.saved());

        controller.setSavePrecondition(() -> false, () -> "Minigame aktiv");
        SaveOutcome deniedHook = controller.saveSlot(
                "s1", createSlot("s1", "ch1", Map.of(), new byte[0], 10), false, false);
        assertFalse(deniedHook.saved());
        assertEquals("Minigame aktiv", deniedHook.message());

        controller.setSavePrecondition(() -> true, () -> "");
        controller.quickSaveRing(createSlot("q", "ch1", Map.of("v", 1), new byte[0], 10)).writeFuture().join();
        controller.quickSaveRing(createSlot("q", "ch1", Map.of("v", 2), new byte[0], 20)).writeFuture().join();
        controller.quickSaveRing(createSlot("q", "ch1", Map.of("v", 3), new byte[0], 30)).writeFuture().join();
        assertEquals(3, ((Number) controller.quickLoadLatest().orElseThrow().variables().get("v")).intValue());
        assertEquals(2, ((Number) controller.loadSlot("quick-2").orElseThrow().variables().get("v")).intValue());
        assertEquals(1, ((Number) controller.loadSlot("quick-3").orElseThrow().variables().get("v")).intValue());
        assertTrue(controller.isSaveIndicatorVisible());

        assertFalse(controller.deleteSlot("quick-1", false));
        assertTrue(controller.deleteSlot("quick-1", true));
        assertTrue(controller.loadSlot("quick-1").isEmpty());
        assertEquals(Optional.of("quick-1"), controller.undoLastDelete());
        assertTrue(controller.loadSlot("quick-1").isPresent());

        controller.updateSlotNote("quick-1", "Vor dem Ball-Entscheid");
        assertEquals("Vor dem Ball-Entscheid", controller.loadSlot("quick-1").orElseThrow().metadata().customName());

        SaveWorkflowController.PlaytimeAccumulator playtime = new SaveWorkflowController.PlaytimeAccumulator(clock);
        playtime.resumeFromSave(600.0);
        assertEquals(600.0, playtime.getTotalPlaytimeSeconds(), 1e-6);

        SimpleStateStore rngStore1 = new SimpleStateStore();
        double draw1 = SaveWorkflowController.nextDeterministicRandom(rngStore1);
        SimpleStateStore rngStore2 = new SimpleStateStore();
        rngStore2.set("randomSeed", rngStore1.get("randomSeed"));
        rngStore1.set("randomSeed", 0x5EEDBEEFL);
        assertEquals(draw1, SaveWorkflowController.nextDeterministicRandom(rngStore1), 1e-12);

        List<DefaultVariableDoc> docs = List.of(new DefaultVariableDoc("gold", "BigDecimal", "0.00", "Currency"));
        assertTrue(SaveWorkflowController.exportDefaultVariablesMarkdown(docs).contains("| `gold` |"));

        assertFalse(SaveWorkflowController.shouldConfirmBeforeLoad(false, true));
        assertTrue(SaveWorkflowController.shouldConfirmBeforeLoad(true, true));
        assertTrue(SaveWorkflowController.shouldConfirmBeforeNewGame(true));

        SaveSlotData taintedSlot = createSlot("cheat", "ch3", Map.of(), new byte[0], 99);
        controller.saveSlot("cheat", taintedSlot, false, true).writeFuture().join();
        assertEquals(Boolean.TRUE, controller.loadSlot("cheat").orElseThrow().variables().get("developerTainted"));

        // #0526 autosave_on_quit & #0590 pre-choice autosave
        PreferencesState quitPrefs = new PreferencesState();
        controller.savePreChoice(taintedSlot).writeFuture().join();
        assertTrue(controller.loadSlot("last_choice").isPresent());
        assertTrue(controller.autosaveOnQuit(quitPrefs, taintedSlot).saved());
        assertTrue(controller.loadSlot("auto_quit").isPresent());
        quitPrefs.setAutosaveOnQuit(false);
        assertFalse(controller.autosaveOnQuit(quitPrefs, taintedSlot).saved());
    }

    @Test
    void testPaginationSortingChapterFilterAndBatchMigrationCheckpoints() throws IOException {
        InMemorySaveRepository repo = new InMemorySaveRepository();
        SaveWorkflowController controller = new SaveWorkflowController(repo, Clock.systemUTC());
        for (int i = 1; i <= 12; i++) {
            String label = (i % 2 == 0) ? "chapter_2" : "chapter_1";
            SaveSlotData slot = new SaveSlotData(
                    new SaveMetadata(String.format("slot_%02d", i), "N" + i,
                            Instant.parse("2026-06-01T00:00:00Z").plusSeconds(i),
                            label, "Preview", i * 10.0, "1.0", 0, 0, new byte[0]),
                    Map.of("idx", i), null, List.of()
            );
            repo.write(slot.metadata().slotId(), SaveIntegrityAndCodec.encodeJsonPayload(slot, true, false));
        }

        List<SaveMetadata> ch2Page0 = controller.listMetadataPage("chapter_2", "SLOT_NUMBER", 0, 6);
        assertEquals(6, ch2Page0.size());
        assertEquals("slot_02", ch2Page0.get(0).slotId());

        SaveMigrationService migrationService = new SaveMigrationService()
                .registerStep(migrationStep(1, 2, "migrated", true));
        List<BatchCheckpointResult> report = controller.batchValidateCheckpoints(
                List.of("slot_01", "slot_02", "missing_slot"), migrationService, 2);
        assertEquals(3, report.size());
        assertTrue(report.get(0).passed());
        assertTrue(report.get(1).passed());
        assertFalse(report.get(2).passed());
    }

    @Test
    void testPersistentRepairSealedSecretsModsDiffTelemetryBonusCodexAndFixture(@TempDir Path tempDir)
            throws IOException {
        Path corruptFile = tempDir.resolve("persistent.json");
        Files.writeString(corruptFile, "{truncated-json", StandardCharsets.UTF_8);
        var repair = SaveIntegrityAndCodec.repairPersistentJsonIfCorrupt(corruptFile);
        assertTrue(repair.repaired());
        assertTrue(Files.exists(repair.backupPath().orElseThrow()));
        assertEquals("{}", Files.readString(corruptFile, StandardCharsets.UTF_8));

        String token = SaveIntegrityAndCodec.encodeSealedSecret("true_end_key", "mirror-77");
        var decoded = SaveIntegrityAndCodec.decodeSealedSecret(token).orElseThrow();
        assertEquals("mirror-77", decoded.getValue());
        assertTrue(SaveIntegrityAndCodec.decodeSealedSecret(token + "x").isEmpty());

        assertEquals("center", SaveIntegrityAndCodec.resolveSafeShowPosition("old_zoom", Set.of("left", "center")));
        assertEquals(List.of("mod_z"), SaveIntegrityAndCodec.previewMissingMods(
                List.of("mod_a", "mod_z"), Set.of("mod_a")));

        Map<String, Object> m1 = Map.of("pos", Map.of("x", 10, "y", 20));
        Map<String, Object> m2 = Map.of("pos", Map.of("x", 10, "y", 99));
        String diff = SaveIntegrityAndCodec.diffSnapshots(m1, m2).orElseThrow();
        assertTrue(diff.contains("root.pos.y"));

        BetaDecisionTelemetry telemetry = new BetaDecisionTelemetry();
        assertFalse(telemetry.isOptInEnabled());
        telemetry.recordDecision("menu_1", "stay");
        assertTrue(telemetry.exportLocalJson().contains("[]"));
        telemetry.setOptInEnabled(true);
        telemetry.recordDecision("menu_1", "leave");
        assertTrue(telemetry.exportLocalJson().contains("menu_1->leave"));

        SimplePersistentAccess persistent = new SimplePersistentAccess();
        BonusSceneUnlockRegistry bonus = new BonusSceneUnlockRegistry(persistent);
        bonus.unlockBonusScene("dev_commentary");
        assertTrue(bonus.isBonusSceneUnlocked("dev_commentary"));

        CodexUnlockTimeline timeline = new CodexUnlockTimeline();
        timeline.recordUnlock("zeta", "ch1", Instant.parse("2026-06-01T10:00:00Z"));
        timeline.recordUnlock("alpha", "ch2", Instant.parse("2026-06-01T11:00:00Z"));
        assertEquals("zeta", timeline.listChronological().get(0).entryId());
        assertEquals("alpha", timeline.listAlphabetical().get(0).entryId());

        // #0595 regression fixture save_v1_0_0_fixture.json
        try (InputStream is = WaveP2Chapter7SaveSyncAndStressTest.class.getResourceAsStream(
                "/saves/save_v1_0_0_fixture.json")) {
            assertTrue(is != null);
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            SaveSlotData fixture = SaveValueCodec.createGson(false).fromJson(json, SaveSlotData.class);
            SaveMigrationService mig = new SaveMigrationService()
                    .registerStep(migrationStep(1, 2, "migratedV2", true));
            SaveSlotData migrated = mig.migrateToLatest(fixture);
            assertEquals("chapter_1_ballroom", migrated.metadata().currentLabel());
            assertEquals(true, migrated.variables().get("migratedV2"));
        }
    }

    @Test
    void testAsyncQuickSaveConcurrencyAnd1000CycleSaveLoadMigrationStress() throws Exception {
        InMemorySaveRepository repo = new InMemorySaveRepository();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        ExecutorService ioExec = Executors.newFixedThreadPool(2);
        try {
            SaveWorkflowController controller = new SaveWorkflowController(repo, Clock.systemUTC(), ioExec);
            SaveMigrationService migration = new SaveMigrationService()
                    .registerStep(migrationStep(1, 2, "v2", true));

            // Concurrent rapid quick-saves (#0588)
            CountDownLatch start = new CountDownLatch(1);
            var f1 = callers.submit(() -> {
                start.await(2, TimeUnit.SECONDS);
                controller.saveSlot("concurrent", createSlot("concurrent", "ch1", Map.of("t", 1), new byte[0], 1.0),
                        false, false).writeFuture().join();
                return true;
            });
            var f2 = callers.submit(() -> {
                start.await(2, TimeUnit.SECONDS);
                controller.saveSlot("concurrent", createSlot("concurrent", "ch1", Map.of("t", 2), new byte[0], 2.0),
                        false, false).writeFuture().join();
                return true;
            });
            start.countDown();
            assertTrue(f1.get(5, TimeUnit.SECONDS));
            assertTrue(f2.get(5, TimeUnit.SECONDS));
            assertTrue(controller.loadSlot("concurrent").isPresent());

            // 1,000 save/load + migration stress cycles (#0551) & FULL_DEMO WASD coords (#0560)
            SaveWorkflowController syncController = new SaveWorkflowController(repo, Clock.systemUTC());
            for (int cycle = 0; cycle < 1000; cycle++) {
                Map<String, Object> vars = new LinkedHashMap<>();
                vars.put("playerX", 120.0 + cycle);
                vars.put("playerY", 340.0);
                vars.put("score", cycle);
                SaveSlotData slot = createSlot("stress", "demo_wasd_arena", vars, new byte[0], cycle);
                syncController.saveSlot("stress", slot, false, false).writeFuture().join();
                SaveSlotData loaded = syncController.loadSlot("stress").orElseThrow();
                SaveSlotData migrated = migration.migrateToLatest(loaded);
                assertEquals(cycle, ((Number) migrated.variables().get("score")).intValue());
                assertEquals(120.0 + cycle, ((Number) migrated.variables().get("playerX")).doubleValue(), 1e-6);
                assertEquals(Boolean.TRUE, migrated.variables().get("v2"));
            }

            // Voice bookmark sync & clear (#0586)
            SaveSlotData withVoice = SaveWorkflowController.syncVoiceBookmark(
                    createSlot("v", "ch1", Map.of(), new byte[0], 1.0),
                    new SavedVoiceBookmark("voice/line_1.ogg", 4.5), false);
            assertEquals("voice/line_1.ogg", withVoice.voiceBookmark().assetPath());
            SaveSlotData clearedVoice = SaveWorkflowController.syncVoiceBookmark(
                    withVoice, withVoice.voiceBookmark(), true);
            assertEquals(null, clearedVoice.voiceBookmark());
        } finally {
            callers.shutdownNow();
            ioExec.shutdownNow();
        }
    }

    private static SaveSlotData createSlot(
            String id,
            String label,
            Map<String, Object> vars,
            byte[] png,
            double playtimeSec
    ) {
        SaveMetadata meta = new SaveMetadata(
                id, "Slot " + id, Instant.parse("2026-06-01T12:00:00Z"),
                label, "Dialogue", playtimeSec, "1.0.0", png.length > 0 ? 1 : 0, png.length > 0 ? 1 : 0, png
        );
        SavedScriptPosition pos = new SavedScriptPosition(
                label, 2, List.of(), "WAITING_DIALOGUE", "snow", "Snow", "Dialogue",
                false, List.of(), null, null
        );
        return new SaveSlotData(meta, vars, pos, List.of());
    }

    private static SaveMigrationStep migrationStep(int from, int to, String flagKey, Object flagValue) {
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
                Map<String, Object> nextVars = new LinkedHashMap<>(input.variables());
                nextVars.put(flagKey, flagValue);
                return new SaveSlotData(
                        input.metadata(), nextVars, input.scriptPosition(),
                        input.historyEntries(), input.voiceBookmark()
                );
            }
        };
    }
}
