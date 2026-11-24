package de.swfdtf.state;

import de.swfdtf.state.ChapterRegistry.ChapterDef;
import de.swfdtf.state.CodexGlossaryManager.CodexEntry;
import de.swfdtf.state.RelationshipAffinityTracker.AffinityChangeEvent;
import de.swfdtf.state.RelationshipAffinityTracker.RelationshipTier;
import de.swfdtf.state.SkipModeController.SkipMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Comprehensive unit and integration tests for {@link AutoReadController},
 * {@link SkipModeController}, {@link ChapterRegistry}, {@link CodexGlossaryManager},
 * and {@link RelationshipAffinityTracker}.
 */
@ExtendWith(MockitoExtension.class)
class Wave3GameplayFeaturesTest {

    @Test
    void autoReadControllerHandlesDynamicCpsTimingVoiceWaitAndReset() {
        AutoReadController controller = new AutoReadController();
        assertFalse(controller.isEnabled());
        assertEquals(AutoReadController.DEFAULT_BASE_DELAY_SECONDS, controller.getBaseDelaySeconds(), 1e-9);
        assertEquals(AutoReadController.DEFAULT_SECONDS_PER_CHAR, controller.getSecondsPerChar(), 1e-9);
        assertTrue(controller.isWaitForVoice());

        assertTrue(controller.toggle());
        assertTrue(controller.isEnabled());

        controller.setBaseDelaySeconds(1.0);
        controller.setSecondsPerChar(0.05);
        controller.setWaitForVoice(true);

        // 20 chars -> 1.0 + 20 * 0.05 = 2.0 seconds
        controller.startLine(20);
        assertEquals(2.0, controller.getRemainingWaitSeconds(), 1e-9);

        // Typewriter not finished -> must not count down or advance
        assertFalse(controller.update(1.0, false, false));
        assertEquals(2.0, controller.getRemainingWaitSeconds(), 1e-9);

        // Typewriter finished, voice playing -> counts down but waits for voice
        assertFalse(controller.update(1.25, true, true));
        assertEquals(0.75, controller.getRemainingWaitSeconds(), 1e-9);

        assertFalse(controller.update(1.0, true, true));
        assertEquals(0.0, controller.getRemainingWaitSeconds(), 1e-9);

        // Voice finishes -> advances once, then returns false until next startLine()
        assertTrue(controller.update(0.01, true, false));
        assertFalse(controller.update(1.0, true, false));

        // Disabling waitForVoice allows advancing even while voicePlaying is true
        controller.setWaitForVoice(false);
        controller.startLine(10); // 1.0 + 10 * 0.05 = 1.5s
        assertFalse(controller.update(1.0, true, true));
        assertEquals(0.5, controller.getRemainingWaitSeconds(), 1e-9);
        assertTrue(controller.update(0.6, true, true));
        assertFalse(controller.update(0.5, true, true));

        // Disabled controller holds timer without advancing
        controller.startLine(10);
        assertFalse(controller.toggle());
        assertFalse(controller.update(5.0, true, false));
        assertEquals(1.5, controller.getRemainingWaitSeconds(), 1e-9);

        // Reset clears pending state
        controller.reset();
        assertEquals(0.0, controller.getRemainingWaitSeconds(), 1e-9);
        controller.setEnabled(true);
        assertFalse(controller.update(5.0, true, false));
    }

    @Test
    void autoReadControllerValidatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> new AutoReadController(-0.1, 0.04, true));
        assertThrows(IllegalArgumentException.class, () -> new AutoReadController(1.0, Double.NaN, true));

        AutoReadController controller = new AutoReadController();
        assertThrows(IllegalArgumentException.class, () -> controller.setBaseDelaySeconds(-1.0));
        assertThrows(IllegalArgumentException.class, () -> controller.setBaseDelaySeconds(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> controller.setSecondsPerChar(-0.01));
        assertThrows(IllegalArgumentException.class, () -> controller.setSecondsPerChar(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> controller.startLine(-1));
        assertThrows(IllegalArgumentException.class, () -> controller.update(-0.5, true, false));
        assertThrows(IllegalArgumentException.class, () -> controller.update(Double.NaN, true, false));
    }

    @Test
    void skipModeControllerCyclesModesEvaluatesReadStateAndRespectsStopOnChoice() {
        SkipModeController controller = new SkipModeController();
        assertEquals(SkipMode.OFF, controller.getMode());
        assertTrue(controller.isStopOnChoice());
        assertFalse(controller.isSkippingActive());
        assertFalse(controller.shouldSkipLine(true));
        assertFalse(controller.shouldSkipLine(false));

        // Cycle OFF -> READ_ONLY -> ALL -> OFF
        assertEquals(SkipMode.READ_ONLY, controller.cycleMode());
        assertTrue(controller.isSkippingActive());
        assertTrue(controller.shouldSkipLine(true));
        assertFalse(controller.shouldSkipLine(false));

        assertEquals(SkipMode.ALL, controller.cycleMode());
        assertTrue(controller.isSkippingActive());
        assertTrue(controller.shouldSkipLine(true));
        assertTrue(controller.shouldSkipLine(false));

        assertEquals(SkipMode.OFF, controller.cycleMode());
        assertFalse(controller.isSkippingActive());

        // Choice encounter disables skipping when stopOnChoice == true
        controller.setMode(SkipMode.READ_ONLY);
        controller.onChoiceEncountered();
        assertEquals(SkipMode.OFF, controller.getMode());
        assertFalse(controller.isSkippingActive());

        // Choice encounter preserves mode when stopOnChoice == false
        controller.setStopOnChoice(false);
        assertFalse(controller.isStopOnChoice());
        controller.setMode(SkipMode.ALL);
        controller.onChoiceEncountered();
        assertEquals(SkipMode.ALL, controller.getMode());
        assertTrue(controller.isSkippingActive());

        assertThrows(NullPointerException.class, () -> controller.setMode(null));
        assertThrows(NullPointerException.class, () -> new SkipModeController(null, true));
    }

    @Test
    void chapterRegistryManagesSortingUnlocksCompletionAndPersistentAccess(@TempDir Path tempDir) throws Exception {
        PersistentState persistent = new PersistentState();
        ChapterRegistry registry = new ChapterRegistry(persistent);

        assertEquals(0.0, registry.getCompletionPercent(), 1e-9);
        assertEquals(0, registry.getTotalCount());

        ChapterDef ch3 = new ChapterDef("ch3", "Finale", "label_ch3", 30, "thumb3.png", "The final act.");
        ChapterDef ch1 = new ChapterDef("ch1", "Prologue", "label_ch1", 10, "thumb1.png", "The beginning.");
        ChapterDef ch2 = new ChapterDef("ch2", "Crossroads", "label_ch2", 20, null, null);

        // Register out of order to verify orderIndex sorting
        registry.register(ch3);
        registry.register(ch1);
        registry.register(ch2);

        assertEquals(3, registry.getTotalCount());
        assertEquals(List.of(ch1, ch2, ch3), registry.listAll());
        assertTrue(registry.listUnlocked().isEmpty());
        assertEquals(Optional.of(ch2), registry.findById("ch2"));
        assertTrue(registry.findById("missing").isEmpty());
        assertTrue(registry.findById(" ").isEmpty());
        assertEquals("", ch2.thumbnailPath());
        assertEquals("", ch2.description());

        assertTrue(registry.unlock("ch2"));
        assertFalse(registry.unlock("ch2"));
        assertFalse(registry.unlock(""));
        assertFalse(registry.unlock(null));
        assertTrue(registry.isUnlocked("ch2"));
        assertFalse(registry.isUnlocked("ch1"));
        assertEquals(List.of(ch2), registry.listUnlocked());
        assertEquals(100.0 / 3.0, registry.getCompletionPercent(), 1e-6);

        assertTrue(registry.unlock("ch1"));
        assertEquals(List.of(ch1, ch2), registry.listUnlocked());
        assertEquals(200.0 / 3.0, registry.getCompletionPercent(), 1e-6);

        // Unmodifiable collections check
        assertThrows(UnsupportedOperationException.class, () -> registry.listAll().add(ch1));
        assertThrows(UnsupportedOperationException.class, () -> registry.listUnlocked().add(ch3));

        // Verify disk roundtrip via PersistentState
        persistent.save(tempDir);
        PersistentState reloaded = PersistentState.load(tempDir);
        ChapterRegistry reloadedRegistry = new ChapterRegistry(reloaded);
        reloadedRegistry.register(ch1);
        reloadedRegistry.register(ch2);
        reloadedRegistry.register(ch3);
        assertTrue(reloadedRegistry.isUnlocked("ch1"));
        assertTrue(reloadedRegistry.isUnlocked("ch2"));
        assertFalse(reloadedRegistry.isUnlocked("ch3"));
        assertEquals(2, reloadedRegistry.getUnlockedCount());
    }

    @Test
    void chapterRegistryUsesExpectedPersistentKeysViaMockAndValidatesInputs() {
        PersistentAccess mockPersistent = mock(PersistentAccess.class);
        when(mockPersistent.get("chapter.unlocked.ch_intro")).thenReturn(null, Boolean.TRUE);

        ChapterRegistry registry = new ChapterRegistry(mockPersistent);
        assertTrue(registry.unlock("ch_intro"));
        verify(mockPersistent).set("chapter.unlocked.ch_intro", Boolean.TRUE);

        assertFalse(registry.unlock("ch_intro"));
        verify(mockPersistent, times(1)).set("chapter.unlocked.ch_intro", Boolean.TRUE);

        assertThrows(NullPointerException.class, () -> new ChapterRegistry(null));
        assertThrows(NullPointerException.class, () -> registry.register(null));
        assertThrows(IllegalArgumentException.class, () -> new ChapterDef(" ", "T", "lbl", 0, "", ""));
        assertThrows(IllegalArgumentException.class, () -> new ChapterDef("ch1", "T", " ", 0, "", ""));
    }

    @Test
    void codexGlossaryManagerSupportsCategoriesSearchProgressAndMockedPersistence() {
        SimplePersistentAccess persistent = new SimplePersistentAccess();
        CodexGlossaryManager codex = new CodexGlossaryManager(persistent);

        assertEquals(0.0, codex.getUnlockProgressPercent(), 1e-9);

        CodexEntry lena = new CodexEntry(
                "char_lena",
                "Lena Vance",
                "Characters",
                "Astrophysics student at the academy.",
                "Lena discovered the fractured star chart in the old observatory."
        );
        CodexEntry observatory = new CodexEntry(
                "loc_observatory",
                "Old Observatory",
                "Locations",
                "Abandoned dome on the north hill.",
                "Built a century ago to track comet KAGAYAKI."
        );
        CodexEntry kaito = new CodexEntry(
                "char_kaito",
                "Kaito Ren",
                "Characters",
                "Archivist and childhood friend.",
                "Guards the forbidden ledger in the library."
        );

        codex.register(lena);
        codex.register(observatory);
        codex.register(kaito);

        assertEquals(3, codex.getTotalCount());
        assertEquals(List.of("Characters", "Locations"), codex.listCategories());
        assertEquals(Optional.of(lena), codex.findById("char_lena"));
        assertTrue(codex.findById("unknown").isEmpty());

        assertTrue(codex.unlock("char_lena"));
        assertFalse(codex.unlock("char_lena"));
        assertTrue(codex.unlock("loc_observatory"));
        assertEquals(2, codex.getUnlockedCount());
        assertEquals(200.0 / 3.0, codex.getUnlockProgressPercent(), 1e-6);

        // Category filtering (case-insensitive, unlocked vs all)
        assertEquals(List.of(lena, kaito), codex.listByCategory("characters", false));
        assertEquals(List.of(lena), codex.listByCategory("CHARACTERS", true));
        assertTrue(codex.listByCategory(" ", false).isEmpty());

        // Search across title, summary, and fullText
        assertEquals(List.of(lena, observatory), codex.search("observatory", true));
        assertEquals(List.of(kaito), codex.search("archivist", false));
        assertTrue(codex.search("archivist", true).isEmpty());
        assertEquals(List.of(lena, observatory), codex.search(" ", true));
        assertEquals(List.of(lena, observatory, kaito), codex.search("", false));

        // Mockito verification for key prefix
        PersistentAccess mockPersistent = mock(PersistentAccess.class);
        when(mockPersistent.get("codex.unlocked.lore_comet")).thenReturn(null, Boolean.TRUE);
        CodexGlossaryManager mockedCodex = new CodexGlossaryManager(mockPersistent);
        assertTrue(mockedCodex.unlock("lore_comet"));
        verify(mockPersistent).set("codex.unlocked.lore_comet", Boolean.TRUE);
        assertFalse(mockedCodex.unlock("lore_comet"));

        assertThrows(NullPointerException.class, () -> new CodexGlossaryManager(null));
        assertThrows(NullPointerException.class, () -> codex.register(null));
        assertThrows(NullPointerException.class, () -> codex.listByCategory(null, false));
        assertThrows(NullPointerException.class, () -> codex.search(null, false));
        assertThrows(IllegalArgumentException.class, () -> new CodexEntry(" ", "Title", "Lore", "", ""));
    }

    @Test
    void relationshipAffinityTrackerInMemoryTiersListenersAndGuards() {
        RelationshipAffinityTracker tracker = new RelationshipAffinityTracker();

        @SuppressWarnings("unchecked")
        Consumer<AffinityChangeEvent> listener = mock(Consumer.class);
        tracker.addListener(listener);
        tracker.addListener(listener); // duplicate registration ignored

        assertEquals(0, tracker.getPoints("lena"));
        assertEquals(RelationshipTier.STRANGER, tracker.getTier("lena"));

        // Verify all tier boundaries cleanly
        assertEquals(RelationshipTier.HOSTILE, RelationshipTier.fromPoints(-1));
        assertEquals(RelationshipTier.STRANGER, RelationshipTier.fromPoints(0));
        assertEquals(RelationshipTier.STRANGER, RelationshipTier.fromPoints(9));
        assertEquals(RelationshipTier.ACQUAINTANCE, RelationshipTier.fromPoints(10));
        assertEquals(RelationshipTier.ACQUAINTANCE, RelationshipTier.fromPoints(24));
        assertEquals(RelationshipTier.FRIEND, RelationshipTier.fromPoints(25));
        assertEquals(RelationshipTier.FRIEND, RelationshipTier.fromPoints(49));
        assertEquals(RelationshipTier.TRUSTED, RelationshipTier.fromPoints(50));
        assertEquals(RelationshipTier.TRUSTED, RelationshipTier.fromPoints(79));
        assertEquals(RelationshipTier.DEVOTED, RelationshipTier.fromPoints(80));

        assertEquals(12, tracker.addPoints("lena", 12));
        assertEquals(RelationshipTier.ACQUAINTANCE, tracker.getTier("lena"));

        ArgumentCaptor<AffinityChangeEvent> captor = ArgumentCaptor.forClass(AffinityChangeEvent.class);
        verify(listener, times(1)).accept(captor.capture());
        AffinityChangeEvent firstEvent = captor.getValue();
        assertEquals("lena", firstEvent.characterId());
        assertEquals(0, firstEvent.previousPoints());
        assertEquals(12, firstEvent.newPoints());
        assertEquals(RelationshipTier.STRANGER, firstEvent.previousTier());
        assertEquals(RelationshipTier.ACQUAINTANCE, firstEvent.newTier());

        // No-op delta must not emit event
        assertEquals(12, tracker.addPoints("lena", 0));
        verify(listener, times(1)).accept(any());

        tracker.setPoints("lena", 85);
        assertEquals(RelationshipTier.DEVOTED, tracker.getTier("lena"));
        assertEquals(85, tracker.getAllAffinities().get("lena"));

        // Negative delta drops into HOSTILE
        tracker.setPoints("rival", -5);
        assertEquals(RelationshipTier.HOSTILE, tracker.getTier("rival"));
        assertEquals(Map.of("lena", 85, "rival", -5), tracker.getAllAffinities());
        assertThrows(UnsupportedOperationException.class, () -> tracker.getAllAffinities().put("x", 1));

        // Removing listener stops further notifications
        tracker.removeListener(listener);
        tracker.addPoints("lena", -10);
        verify(listener, times(3)).accept(any());

        assertThrows(NullPointerException.class, () -> tracker.addPoints(null, 5));
        assertThrows(IllegalArgumentException.class, () -> tracker.addPoints("  ", 5));
        assertThrows(NullPointerException.class, () -> tracker.addListener(null));
        assertThrows(NullPointerException.class, () -> tracker.removeListener(null));
    }

    @Test
    void relationshipAffinityTrackerSyncsWithStateStoreAndRollback() {
        // Mockito verification against StateStore
        StateStore mockStore = mock(StateStore.class);
        when(mockStore.getInt("affinity.kaito", 0)).thenReturn(0, 15);
        when(mockStore.keys()).thenReturn(Set.of("affinity.kaito", "chapter", "affinity."));

        RelationshipAffinityTracker mockedTracker = new RelationshipAffinityTracker(mockStore);
        assertEquals(15, mockedTracker.addPoints("kaito", 15));
        verify(mockStore).set("affinity.kaito", 15);
        assertEquals(Map.of("kaito", 15), mockedTracker.getAllAffinities());
        verify(mockStore, never()).set("chapter", 15);

        // Integration test with RevertableStateStore rollback
        RevertableStateStore revertable = new RevertableStateStore();
        RelationshipAffinityTracker tracker = new RelationshipAffinityTracker(revertable);
        tracker.setPoints("lena", 10);
        revertable.recordCheckpoint("Before rooftop choice");

        tracker.addPoints("lena", 20);
        revertable.recordCheckpoint("After rooftop choice");
        assertEquals(30, tracker.getPoints("lena"));
        assertEquals(RelationshipTier.FRIEND, tracker.getTier("lena"));
        assertEquals(30, revertable.getInt("affinity.lena", 0));

        assertTrue(revertable.rollback());
        assertEquals(10, tracker.getPoints("lena"));
        assertEquals(RelationshipTier.ACQUAINTANCE, tracker.getTier("lena"));

        assertTrue(revertable.rollForward());
        assertEquals(30, tracker.getPoints("lena"));
        assertEquals(RelationshipTier.FRIEND, tracker.getTier("lena"));

        assertThrows(NullPointerException.class, () -> new RelationshipAffinityTracker(null));
    }
}
