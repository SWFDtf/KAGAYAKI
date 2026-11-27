package de.swfdtf.state;

import com.google.gson.JsonObject;
import de.swfdtf.state.RelationshipAffinityTracker.AffinityChangeEvent;
import de.swfdtf.state.RelationshipAffinityTracker.RelationshipTier;
import de.swfdtf.state.StateCoreEnhancements.ChapterEntryCheckpointService;
import de.swfdtf.state.StateCoreEnhancements.DeltaRollbackBuffer;
import de.swfdtf.state.StateCoreEnhancements.ObservableStateBus;
import de.swfdtf.state.StateCoreEnhancements.RollbackOutcome;
import de.swfdtf.state.StateCoreEnhancements.StateChangeEvent;
import de.swfdtf.state.StateCoreEnhancements.TypeSafeStateView;
import de.swfdtf.state.StateCoreEnhancements.TypeSafeStateView.StateTypeMismatchException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests covering Chapter 7 core state views, reactive listeners, transactions, delta rollback,
 * preferences clamping/reset, BigDecimal precision, and affinity clamping (Wave P2-7).
 */
class WaveP2Chapter7StateAndRollbackTest {

    enum QuestRank { BRONZE, SILVER, GOLD }

    @Test
    void testTypeSafeStateViewAndMismatchDiagnostics() {
        SimpleStateStore store = new SimpleStateStore();
        store.set("player", Map.of(
                "name", "Snow",
                "stats", Map.of("hp", 42, "tags", List.of("hero", "forest"))
        ));

        TypeSafeStateView view = new TypeSafeStateView(store);
        assertEquals(Optional.of("Snow"), view.getOptional("player.name", String.class));
        assertEquals(Optional.of(42), view.getOptional("player.stats.hp", Integer.class));
        assertEquals(Optional.empty(), view.getOptional("player.missing.key", String.class));
        assertEquals(Optional.of(List.of("hero", "forest")), view.getNestedList("player.stats.tags", String.class));
        assertTrue(view.getNestedMap("player.stats").isPresent());

        StateTypeMismatchException ex = assertThrows(
                StateTypeMismatchException.class,
                () -> view.getOptional("player.stats.hp", String.class)
        );
        assertTrue(ex.getMessage().contains("player.stats.hp"));
    }

    @Test
    void testBigDecimalExactCurrencyArithmeticAndValidation() {
        SimpleStateStore store = new SimpleStateStore();
        TypeSafeStateView.addCurrency(store, "gold", "0.10");
        TypeSafeStateView.addCurrency(store, "gold", "0.20");

        TypeSafeStateView view = new TypeSafeStateView(store);
        assertEquals(0, new BigDecimal("0.30").compareTo(view.getBigDecimal("gold").orElseThrow()));

        assertThrows(IllegalArgumentException.class, () -> StateCoreEnhancements.validateStateKey(""));
        assertThrows(IllegalArgumentException.class, () -> StateCoreEnhancements.validateStateKey("_internal_secret"));
    }

    @Test
    void testObservableStateBusIgnoresDuplicateValuesAndLogsAuditHistory() {
        RevertableStateStore store = new RevertableStateStore();
        Clock fixed = Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC);
        ObservableStateBus bus = new ObservableStateBus(store, fixed, 10);

        List<StateChangeEvent> events = new ArrayList<>();
        bus.addKeyListener("mut_punkte", events::add);

        assertTrue(bus.set("mut_punkte", 10, "chapter_1"));
        assertFalse(bus.set("mut_punkte", 10, "chapter_1"));
        assertTrue(bus.set("mut_punkte", 25, "chapter_2"));

        assertEquals(2, events.size());
        assertEquals("chapter_2", bus.getAuditHistory("mut_punkte").get(1).labelContext());
        assertEquals(10, bus.getAuditHistory("mut_punkte").get(1).oldValue());
    }

    @Test
    void testStateStoreTransactionCommitsOrRollsBackAtomically() {
        RevertableStateStore store = new RevertableStateStore();
        store.set("gold", 100);
        store.set("sword", false);

        assertThrows(IllegalStateException.class, () -> store.transaction(s -> {
            s.set("gold", 50);
            s.set("sword", true);
            throw new IllegalStateException("payment error");
        }));

        assertEquals(100, store.getInt("gold", -1));
        assertFalse(store.getBoolean("sword", true));

        store.transaction(s -> {
            s.set("gold", 50);
            s.set("sword", true);
        });
        assertEquals(50, store.getInt("gold", -1));
        assertTrue(store.getBoolean("sword", false));
    }

    @Test
    void testRevertableStateStoreSnapshotMapDeepCopiesNestedCollections() {
        RevertableStateStore store = new RevertableStateStore();
        List<String> bag = new ArrayList<>(List.of("key", "apple"));
        Map<String, Object> quest = new LinkedHashMap<>();
        quest.put("stage", 1);
        store.set("inventory", bag);
        store.set("quest", quest);

        Map<String, Object> snap = store.snapshotMap();
        bag.add("potion");
        quest.put("stage", 99);

        @SuppressWarnings("unchecked")
        List<String> snapBag = (List<String>) snap.get("inventory");
        @SuppressWarnings("unchecked")
        Map<?, ?> snapQuest = (Map<?, ?>) snap.get("quest");
        assertEquals(List.of("key", "apple"), snapBag);
        assertEquals(1, snapQuest.get("stage"));
    }

    @Test
    void testDeltaRollbackBuffer100StepsReplayBoundaryAndHistoryClear() {
        DeltaRollbackBuffer buffer = new DeltaRollbackBuffer(150, 20);
        Map<String, Object> current = new LinkedHashMap<>();
        for (int s = 0; s <= 100; s++) {
            current.put("step", s);
            current.put("constant", "unchanged_payload");
            buffer.recordStep(current, s == 50);
        }

        // Delta storage should be dramatically smaller than full 101*2=202 entries when keyframe interval is 20
        assertTrue(buffer.totalStoredEntries() < 150);

        for (int back = 100; back > 50; back--) {
            RollbackOutcome out = buffer.rollback();
            assertTrue(out.success());
            assertEquals(back - 1, out.state().orElseThrow().get("step"));
        }

        RollbackOutcome blocked = buffer.rollback();
        assertFalse(blocked.success());
        assertFalse(blocked.statusText().isBlank());

        DeltaRollbackBuffer emptyCheck = new DeltaRollbackBuffer(10, 5);
        emptyCheck.recordStep(Map.of("a", 1), false);
        RollbackOutcome atStart = emptyCheck.rollback();
        assertFalse(atStart.success());
        assertTrue(atStart.statusText().contains("Anfang"));

        buffer.clearHistory(true);
        assertEquals(1, buffer.size());
    }

    @Test
    void testChapterEntryCheckpointServiceStoresAndRetrievesEntry() {
        ChapterEntryCheckpointService service = new ChapterEntryCheckpointService();
        SaveSlotData entry = sampleSlot("entry_ch2", "chapter_2", Map.of("courage", 5));
        service.recordChapterEntry("chapter_2", entry);

        assertEquals(Optional.of(entry), service.getChapterEntry("chapter_2"));
        service.clear();
        assertTrue(service.getChapterEntry("chapter_2").isEmpty());
    }

    @Test
    void testPreferencesStateNewFieldsClampingAndResetToDefaults() throws Exception {
        Path tempDir = Files.createTempDirectory("kagayaki-prefs-p2-7-");
        PreferencesState prefs = new PreferencesState();
        prefs.setAutosaveOnQuit(false);
        prefs.setAutoReadVoiceWaitSeconds(25.0);
        prefs.setSaveSlotSortMode("slot_number");
        prefs.setMasterVolume(Float.NaN);
        prefs.save(tempDir);

        PreferencesState loaded = PreferencesState.load(tempDir);
        assertFalse(loaded.isAutosaveOnQuit());
        assertEquals(10.0, loaded.getAutoReadVoiceWaitSeconds(), 1e-6);
        assertEquals("SLOT_NUMBER", loaded.getSaveSlotSortMode());
        assertEquals(1.0f, loaded.getMasterVolume(), 1e-6f);

        loaded.resetToDefaults(tempDir);
        PreferencesState afterReset = PreferencesState.load(tempDir);
        assertTrue(afterReset.isAutosaveOnQuit());
        assertEquals(0.75, afterReset.getAutoReadVoiceWaitSeconds(), 1e-6);
        assertEquals("LAST_SAVED", afterReset.getSaveSlotSortMode());
    }

    @Test
    void testRelationshipAffinityTrackerClampingAndOncePerRankEvents() {
        RelationshipAffinityTracker tracker = new RelationshipAffinityTracker();
        tracker.setBounds(-100, 100);
        AtomicInteger tierEvents = new AtomicInteger();
        List<RelationshipTier> seenTiers = new ArrayList<>();
        tracker.addTierThresholdListener((AffinityChangeEvent e) -> {
            tierEvents.incrementAndGet();
            seenTiers.add(e.newTier());
        });

        tracker.addPoints("queen", 150);
        assertEquals(100, tracker.getPoints("queen"));
        assertEquals(RelationshipTier.DEVOTED, tracker.getTier("queen"));
        tracker.addPoints("queen", 10);
        assertEquals(100, tracker.getPoints("queen"));
        assertEquals(1, tierEvents.get());
        assertEquals(List.of(RelationshipTier.DEVOTED), seenTiers);

        tracker.setPoints("queen", -250);
        assertEquals(-100, tracker.getPoints("queen"));
        assertEquals(2, tierEvents.get());
    }

    @Test
    void testSaveValueCodecPreservesSetListEnumAndDeSwfdtfStateRemainsParserFree() {
        SimpleStateStore store = new SimpleStateStore();
        Set<String> clues = new LinkedHashSet<>(List.of("clue_b", "clue_a"));
        List<String> inventory = List.of("lantern", "sword");
        store.set("clues", clues);
        store.set("inventory", inventory);
        store.set("rank", QuestRank.GOLD);

        JsonObject encoded = SaveValueCodec.encodeMap(store.snapshotMap());
        Map<String, Object> restored = SaveValueCodec.decodeMap(encoded, null);
        assertTrue(restored.get("clues") instanceof Set<?>);
        assertEquals(clues, restored.get("clues"));
        assertTrue(restored.get("inventory") instanceof List<?>);
        assertEquals(inventory, restored.get("inventory"));
        assertEquals(QuestRank.GOLD, restored.get("rank"));

        // #0522 verify ArchitectureGuard / no parser imports in state source dir
        assertDoesNotThrow(() -> {
            Path stateDir = Path.of("src/main/java/de/swfdtf/state");
            try (var stream = Files.list(stateDir)) {
                for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String src = Files.readString(file);
                    assertFalse(src.contains("import de.swfdtf.parser"),
                            file + " must not import de.swfdtf.parser");
                }
            }
        });
    }

    @Test
    void testAchievementBackendOfflineSyncWithoutNativeLibs() {
        PersistentState persistent = new PersistentState();
        AchievementManager manager = new AchievementManager(persistent);
        AchievementBackend.OfflineAchievementBackend offline = new AchievementBackend.OfflineAchievementBackend();
        manager.setBackend(offline);

        assertEquals("offline", offline.backendName());
        assertTrue(offline.isAvailable());
        assertTrue(manager.unlock("first_ending"));
        assertTrue(offline.getSyncedUnlocks().containsKey("first_ending"));
        assertTrue(offline.resetAll());
        assertTrue(offline.getSyncedUnlocks().isEmpty());
    }

    private static SaveSlotData sampleSlot(String id, String label, Map<String, Object> vars) {
        SaveSlotData.SaveMetadata meta = new SaveSlotData.SaveMetadata(
                id, "Note", Instant.parse("2026-06-01T12:00:00Z"),
                label, "Hello", 120.0, "1.0.0", 0, 0, new byte[0]
        );
        SaveSlotData.SavedScriptPosition pos = new SaveSlotData.SavedScriptPosition(
                label, 1, List.of(), "WAITING_DIALOGUE", "snow", "Snow", "Hello",
                false, List.of(), null, null
        );
        return new SaveSlotData(meta, vars, pos, List.of());
    }
}
