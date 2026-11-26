package de.swfdtf.state;

import de.swfdtf.graphics.SceneController;
import de.swfdtf.state.SaveSlotData.SaveMetadata;
import de.swfdtf.state.SaveSlotData.SavedScriptPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Comprehensive unit and integration tests for {@link AutoSaveScheduler},
 * {@link SaveSlotQueryService}, {@link SaveMigrationStep}, and {@link SaveMigrationService}.
 */
@ExtendWith(MockitoExtension.class)
class Wave1StateFeaturesTest {

    @Test
    void autoSaveSchedulerRotatesSlotsAndTriggersByTimeAndSteps(@TempDir Path tempDir) throws Exception {
        SaveManager saveManager = new SaveManager(tempDir, 2);
        @SuppressWarnings("unchecked")
        Supplier<SaveSlotData> provider = mock(Supplier.class);

        SaveSlotData snap1 = sampleSlot(
                "auto_1", "Chap 1", "ch1", "Opening", 10.0, Instant.parse("2026-01-01T10:00:00Z"), Map.of("day", 1));
        SaveSlotData snap2 = sampleSlot(
                "auto_2", "Chap 2", "ch2", "Crossing", 20.0, Instant.parse("2026-01-01T10:05:00Z"), Map.of("day", 2));
        SaveSlotData snap3 = sampleSlot(
                "auto_3", "Chap 3", "ch3", "Finale", 30.0, Instant.parse("2026-01-01T10:10:00Z"), Map.of("day", 3));
        when(provider.get()).thenReturn(snap1, snap2, snap3);

        AutoSaveScheduler scheduler = new AutoSaveScheduler(saveManager, provider, 2, 10.0, 3);
        assertEquals(0, scheduler.getNextSlotIndex());
        assertTrue(scheduler.isEnabled());

        // Below thresholds: no save
        scheduler.recordDialogueStep();
        assertEquals(1, scheduler.getPendingStepCount());
        assertTrue(scheduler.update(4.5).isEmpty());
        assertEquals(4.5, scheduler.getElapsedSinceLastSaveSeconds(), 1e-6);

        // Exceed time threshold -> saves auto_1 and resets counters
        Optional<String> first = scheduler.update(6.0);
        assertEquals(Optional.of("auto_1"), first);
        assertEquals(1, scheduler.getNextSlotIndex());
        assertEquals(0, scheduler.getPendingStepCount());
        assertEquals(0.0, scheduler.getElapsedSinceLastSaveSeconds(), 1e-6);

        // Exceed step threshold -> saves auto_2 and wraps nextSlotIndex to 0
        scheduler.recordDialogueStep();
        scheduler.recordDialogueStep();
        scheduler.recordDialogueStep();
        Optional<String> second = scheduler.update(0.5);
        assertEquals(Optional.of("auto_2"), second);
        assertEquals(0, scheduler.getNextSlotIndex());

        // Wrap around to auto_1 via forceSave()
        Optional<String> third = scheduler.forceSave();
        assertEquals(Optional.of("auto_1"), third);
        assertEquals(1, scheduler.getNextSlotIndex());

        verify(provider, times(3)).get();
        SaveSlotData loaded = saveManager.loadSlot("auto_1");
        assertEquals(3, loaded.variables().get("day"));
    }

    @Test
    void autoSaveSchedulerRespectsEnabledFlagNullSnapshotsAndGuards(@TempDir Path tempDir) {
        SaveManager saveManager = new SaveManager(tempDir);
        @SuppressWarnings("unchecked")
        Supplier<SaveSlotData> provider = mock(Supplier.class);
        when(provider.get()).thenReturn(null);

        assertThrows(NullPointerException.class, () -> new AutoSaveScheduler(null, provider));
        assertThrows(NullPointerException.class, () -> new AutoSaveScheduler(saveManager, null));
        assertThrows(IllegalArgumentException.class, () -> new AutoSaveScheduler(saveManager, provider, 0, 10.0, 1));
        assertThrows(IllegalArgumentException.class, () -> new AutoSaveScheduler(saveManager, provider, 2, 0.0, 1));
        assertThrows(IllegalArgumentException.class, () -> new AutoSaveScheduler(saveManager, provider, 2, 10.0, 0));

        AutoSaveScheduler scheduler = new AutoSaveScheduler(saveManager, provider, 3, 5.0, 2);
        assertThrows(IllegalArgumentException.class, () -> scheduler.update(-1.0));
        assertThrows(IllegalArgumentException.class, () -> scheduler.update(Double.NaN));

        scheduler.setEnabled(false);
        assertFalse(scheduler.isEnabled());
        scheduler.recordDialogueStep();
        assertEquals(0, scheduler.getPendingStepCount());
        assertTrue(scheduler.update(20.0).isEmpty());

        // Null snapshot from provider produces Optional.empty() without advancing slot index
        assertTrue(scheduler.forceSave().isEmpty());
        assertEquals(0, scheduler.getNextSlotIndex());
    }

    @Test
    void saveSlotQueryServiceSupportsTagsSearchSortingAndSafeCorruptionSkipping(@TempDir Path tempDir)
            throws Exception {
        SaveManager saveManager = new SaveManager(tempDir);
        SaveSlotQueryService queryService = new SaveSlotQueryService(saveManager);

        assertTrue(queryService.findLatestSlot().isEmpty());
        assertTrue(queryService.listSortedByNewest().isEmpty());

        saveManager.saveSlot("slot_1", sampleSlot(
                "slot_1", "Library Dawn", "chapter_1", "Quiet reading room",
                42.5, Instant.parse("2026-02-01T08:00:00Z"), Map.of("route", "a")
        ));
        saveManager.saveSlot("slot_2", sampleSlot(
                "slot_2", "Rooftop Confrontation", "chapter_2", "Storm clouds gather",
                120.0, Instant.parse("2026-02-01T09:00:00Z"), Map.of("route", "b")
        ));
        saveManager.saveSlot("quick", sampleSlot(
                "quick", "Quick Save", "chapter_2", "Mid-choice pause",
                75.0, Instant.parse("2026-02-01T10:30:00Z"), Map.of("route", "b")
        ));

        // Write a corrupt .ksav file to verify safe skipping
        Files.writeString(tempDir.resolve("corrupt.ksav"), "not-valid-gzip-json");

        queryService.addTag("slot_1", "Cozy");
        queryService.addTag("slot_2", "BossFight");
        queryService.addTag("slot_2", "Dramatic");
        queryService.removeTag("slot_2", "Dramatic");

        Set<String> slot2Tags = queryService.getTags("slot_2");
        assertEquals(Set.of("BossFight"), slot2Tags);
        assertThrows(UnsupportedOperationException.class, () -> slot2Tags.add("Hacked"));

        Optional<SaveSlotData> latest = queryService.findLatestSlot();
        assertTrue(latest.isPresent());
        assertEquals("quick", latest.get().metadata().slotId());

        List<SaveSlotData> newest = queryService.listSortedByNewest();
        assertEquals(List.of("quick", "slot_2", "slot_1"),
                newest.stream().map(s -> s.metadata().slotId()).toList());
        assertThrows(UnsupportedOperationException.class, () -> newest.clear());

        List<SaveSlotData> byPlaytime = queryService.listSortedByPlaytime();
        assertEquals(List.of("slot_2", "quick", "slot_1"),
                byPlaytime.stream().map(s -> s.metadata().slotId()).toList());

        List<SaveSlotData> byTag = queryService.filterByTag("bossfight");
        assertEquals(1, byTag.size());
        assertEquals("slot_2", byTag.get(0).metadata().slotId());

        List<SaveSlotData> byLabel = queryService.filterByLabel("chapter_2");
        assertEquals(List.of("quick", "slot_2"),
                byLabel.stream().map(s -> s.metadata().slotId()).toList());

        // Case-insensitive search across caption, label, slotId, and tag
        assertEquals(1, queryService.search("STORM CLOUDS").size());
        assertEquals("slot_1", queryService.search("cozy").get(0).metadata().slotId());
        assertEquals(3, queryService.search("").size());
    }

    @Test
    void saveMigrationServiceExecutesValidatedChainAndPreservesOriginal(@TempDir Path tempDir) throws Exception {
        SaveMigrationStep step2To3 = mock(SaveMigrationStep.class);
        when(step2To3.fromVersion()).thenReturn(2);
        when(step2To3.toVersion()).thenReturn(3);
        when(step2To3.migrate(any())).thenAnswer(inv -> {
            SaveSlotData in = inv.getArgument(0);
            Map<String, Object> nextVars = new java.util.LinkedHashMap<>(in.variables());
            nextVars.put("affectionTier", "HIGH");
            return new SaveSlotData(in.metadata(), nextVars, in.scriptPosition(), in.historyEntries());
        });

        SaveMigrationStep step1To2 = new SaveMigrationStep() {
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
                Map<String, Object> nextVars = new java.util.LinkedHashMap<>(input.variables());
                Object legacyPoints = nextVars.remove("points");
                nextVars.put("score", legacyPoints != null ? legacyPoints : 0);
                return new SaveSlotData(input.metadata(), nextVars, input.scriptPosition(), input.historyEntries());
            }
        };

        // Register out of order to verify sorting & chain validation
        SaveMigrationService service = new SaveMigrationService()
                .registerStep(step2To3)
                .registerStep(step1To2);

        service.validateChain();
        assertTrue(service.isChainValid());
        assertEquals(3, service.getTargetVersion());
        assertEquals(2, service.getSteps().size());
        assertThrows(UnsupportedOperationException.class, () -> service.getSteps().clear());

        SaveSlotData original = sampleSlot(
                "slot_1", "Old Save", "intro", "Hello",
                15.0, Instant.parse("2026-01-15T12:00:00Z"), Map.of("points", 50)
        );

        SaveSlotData migrated = service.migrateToLatest(original);
        assertEquals(3, migrated.variables().get(SaveMigrationService.VERSION_VARIABLE_KEY));
        assertEquals(50, migrated.variables().get("score"));
        assertFalse(migrated.variables().containsKey("points"));
        assertEquals("HIGH", migrated.variables().get("affectionTier"));
        assertEquals(List.of("1->2", "2->3"), migrated.variables().get(SaveMigrationService.MIGRATION_TRAIL_KEY));

        // Original input remains untouched
        assertEquals(50, original.variables().get("points"));
        assertFalse(original.variables().containsKey("score"));

        InOrder order = inOrder(step2To3);
        order.verify(step2To3).migrate(any());

        // Idempotent when already at target version
        assertEquals(migrated, service.migrateToLatest(migrated));

        // Round-trip through SaveManager
        SaveManager saveManager = new SaveManager(tempDir);
        saveManager.saveSlot("slot_1", migrated);
        SaveSlotData reloaded = saveManager.loadSlot("slot_1");
        assertEquals(3, service.detectVersion(reloaded));
        assertEquals(List.of("1->2", "2->3"), reloaded.variables().get(SaveMigrationService.MIGRATION_TRAIL_KEY));
    }

    @Test
    void saveMigrationServiceRejectsBrokenChainsDuplicatesAndDowngrades() {
        SaveMigrationService broken = new SaveMigrationService();
        broken.registerStep(new SimpleStep(1, 2));
        broken.registerStep(new SimpleStep(3, 4));

        assertFalse(broken.isChainValid());
        assertThrows(IllegalStateException.class, broken::validateChain);
        assertThrows(IllegalStateException.class, () -> broken.migrateToLatest(
                sampleSlot("slot_1", "Broken", "start", "Text", 1.0, Instant.now(), Map.of())
        ));

        SaveMigrationService dup = new SaveMigrationService();
        dup.registerStep(new SimpleStep(1, 2));
        assertThrows(IllegalArgumentException.class, () -> dup.registerStep(new SimpleStep(1, 3)));
        assertThrows(IllegalArgumentException.class, () -> dup.registerStep(new SimpleStep(2, 2)));

        SaveSlotData futureSave = SaveMigrationService.withVersion(
                sampleSlot("slot_1", "Future", "start", "Text", 1.0, Instant.now(), Map.of()),
                99
        );
        assertThrows(IllegalStateException.class, () -> dup.migrateToLatest(futureSave));
    }

    private static SaveSlotData sampleSlot(
            String slotId,
            String customName,
            String label,
            String preview,
            double playtimeSeconds,
            Instant savedAt,
            Map<String, Object> variables
    ) {
        SaveMetadata meta = new SaveMetadata(
                slotId,
                customName,
                savedAt,
                label,
                preview,
                playtimeSeconds,
                "1.0.0",
                0,
                0,
                new byte[0]
        );
        SavedScriptPosition pos = new SavedScriptPosition(
                label,
                1,
                List.of(),
                "WAITING_DIALOGUE",
                "alice",
                "Alice",
                preview,
                false,
                List.of(),
                null,
                new SceneController.StageSnapshot(null, null, null, Map.of())
        );
        return new SaveSlotData(meta, variables, pos, List.of());
    }

    private record SimpleStep(int fromVersion, int toVersion) implements SaveMigrationStep {
        @Override
        public SaveSlotData migrate(SaveSlotData input) {
            return input;
        }
    }
}
