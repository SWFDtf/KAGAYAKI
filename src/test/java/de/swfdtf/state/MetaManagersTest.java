package de.swfdtf.state;

import de.swfdtf.state.AchievementManager.AchievementDef;
import de.swfdtf.state.AchievementManager.AchievementToast;
import de.swfdtf.state.EndingsManager.EndingDef;
import de.swfdtf.state.GalleryManager.GalleryEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetaManagersTest {

    @Test
    void achievementManagerHandlesRegistrationToastDeduplicationHiddenFlagAndListener() {
        SimplePersistentAccess persistent = new SimplePersistentAccess();
        AchievementManager manager = new AchievementManager(persistent);

        AchievementDef firstStep = new AchievementDef(
                "ach_first_step",
                "Erster Schritt",
                "Beginne die Geschichte.",
                "assets/images/ui/ach_first.png",
                false
        );
        AchievementDef secretEnding = new AchievementDef(
                "ach_secret",
                "Verborgener Pfad",
                "Finde das geheime Ende.",
                "assets/images/ui/ach_secret.png",
                true
        );

        manager.register(firstStep);
        manager.register(secretEnding);

        assertEquals(2, manager.getTotalCount());
        assertEquals(0, manager.getUnlockedCount());
        assertEquals(List.of(firstStep, secretEnding), manager.getAllAchievements());
        assertFalse(manager.getAllAchievements().get(0).hidden());
        assertTrue(manager.getAllAchievements().get(1).hidden());
        assertFalse(manager.isUnlocked("ach_first_step"));
        assertTrue(manager.pollToast().isEmpty());

        List<AchievementToast> receivedToasts = new ArrayList<>();
        Consumer<AchievementToast> listener = receivedToasts::add;
        manager.setOnUnlockListener(listener);

        assertTrue(manager.unlock("ach_first_step"));
        assertTrue(manager.isUnlocked("ach_first_step"));
        assertTrue(persistent.isAchievementUnlocked("ach_first_step"));
        assertEquals(1, manager.getUnlockedCount());
        assertEquals(1, receivedToasts.size());
        assertEquals(firstStep, receivedToasts.get(0).achievement());
        assertNotNull(receivedToasts.get(0).unlockedAt());

        // Duplicate unlock must NOT queue another toast or re-fire listener
        assertFalse(manager.unlock("ach_first_step"));
        assertEquals(1, receivedToasts.size());

        Optional<AchievementToast> polled1 = manager.pollToast();
        assertTrue(polled1.isPresent());
        assertEquals("ach_first_step", polled1.get().achievement().id());
        assertTrue(manager.pollToast().isEmpty());

        // Clearing listener stops callbacks while queue still works
        manager.setOnUnlockListener(null);
        assertTrue(manager.unlock("ach_secret"));
        assertEquals(1, receivedToasts.size());
        assertEquals(2, manager.getUnlockedCount());
        Optional<AchievementToast> polled2 = manager.pollToast();
        assertTrue(polled2.isPresent());
        assertTrue(polled2.get().achievement().hidden());

        // Cross-session deduplication when backed by the same PersistentAccess
        AchievementManager reloadedManager = new AchievementManager(persistent);
        reloadedManager.register(firstStep);
        reloadedManager.register(secretEnding);
        assertTrue(reloadedManager.isUnlocked("ach_first_step"));
        assertTrue(reloadedManager.isUnlocked("ach_secret"));
        assertEquals(2, reloadedManager.getUnlockedCount());
        assertFalse(reloadedManager.unlock("ach_first_step"));
        assertTrue(reloadedManager.pollToast().isEmpty());

        assertFalse(manager.unlock(""));
        assertFalse(manager.unlock(null));
        assertThrows(IllegalArgumentException.class, () -> new AchievementDef("  ", "X", "", "", false));
    }

    @Test
    void galleryManagerSupportsMultiVariationCgsAndCounts() {
        SimplePersistentAccess persistent = new SimplePersistentAccess();
        GalleryManager gallery = new GalleryManager(persistent);

        List<String> rooftopVariations = new ArrayList<>(List.of(
                "assets/images/cg/rooftop_day.png",
                "assets/images/cg/rooftop_sunset.png",
                "assets/images/cg/rooftop_night.png"
        ));
        GalleryEntry cgRooftop = new GalleryEntry(
                "cg_rooftop",
                "Auf dem Schuldach",
                "Lena",
                "assets/images/cg/rooftop_thumb.png",
                rooftopVariations,
                "Gespräch nach dem Unterricht."
        );
        // Mutating original list must not affect stored GalleryEntry
        rooftopVariations.add("assets/images/cg/mutated.png");

        GalleryEntry cgLibrary = new GalleryEntry(
                "cg_library",
                "Alte Bibliothek",
                "Akademie",
                "assets/images/cg/library_thumb.png",
                List.of("assets/images/cg/library_01.png", "assets/images/cg/library_02.png"),
                "Verbotene Archive."
        );

        gallery.register(cgRooftop);
        gallery.register(cgLibrary);

        assertEquals(2, gallery.getTotalCount());
        assertEquals(0, gallery.getUnlockedCount());
        assertEquals(List.of(cgRooftop, cgLibrary), gallery.getAllEntries());
        assertTrue(gallery.getUnlockedEntries().isEmpty());
        assertEquals(3, gallery.getEntry("cg_rooftop").orElseThrow().variationPaths().size());
        assertTrue(gallery.getEntry("missing").isEmpty());

        assertTrue(gallery.unlock("cg_rooftop"));
        assertFalse(gallery.unlock("cg_rooftop"));
        assertTrue(gallery.isUnlocked("cg_rooftop"));
        assertFalse(gallery.isUnlocked("cg_library"));
        assertTrue(persistent.isCgUnlocked("cg_rooftop"));

        assertEquals(1, gallery.getUnlockedCount());
        assertEquals(2, gallery.getTotalCount());
        List<GalleryEntry> unlocked = gallery.getUnlockedEntries();
        assertEquals(1, unlocked.size());
        assertEquals("cg_rooftop", unlocked.get(0).id());
        assertEquals(
                List.of(
                        "assets/images/cg/rooftop_day.png",
                        "assets/images/cg/rooftop_sunset.png",
                        "assets/images/cg/rooftop_night.png"
                ),
                unlocked.get(0).variationPaths()
        );
    }

    @Test
    void endingsManagerTracksTreeParentLinksAndCompletionPercentage() {
        SimplePersistentAccess persistent = new SimplePersistentAccess();
        EndingsManager endings = new EndingsManager(persistent);

        assertEquals(0.0, endings.getCompletionPercentage(), 1e-6);
        assertEquals(0, endings.getTotalCount());

        EndingDef rootNormal = new EndingDef(
                "end_normal",
                "Gewöhnlicher Abschluss",
                "Du verlässt die Akademie ohne das Geheimnis zu lüften.",
                "Hauptroute",
                null
        );
        EndingDef branchTruth = new EndingDef(
                "end_truth",
                "Die Wahrheit der Sterne",
                "Lena und Kaito entschlüsseln das Archiv.",
                "Wahrheitsroute",
                "end_normal"
        );
        EndingDef branchSacrifice = new EndingDef(
                "end_sacrifice",
                "Stilles Opfer",
                "Der Sternenkompass wird zerstört.",
                "Konfliktroute",
                "end_normal"
        );
        EndingDef secretTrue = new EndingDef(
                "end_true_secret",
                "Kagayaki-Finale",
                "Alle Rätsel gelöst und gerettet.",
                "Epilog",
                "end_truth"
        );

        endings.register(rootNormal);
        endings.register(branchTruth);
        endings.register(branchSacrifice);
        endings.register(secretTrue);

        assertEquals(4, endings.getTotalCount());
        assertEquals(0, endings.getUnlockedCount());
        assertEquals(0.0, endings.getCompletionPercentage(), 1e-6);

        // Verify tree parent links
        assertEquals(List.of(rootNormal), endings.getChildEndings(null));
        assertEquals(List.of(branchTruth, branchSacrifice), endings.getChildEndings("end_normal"));
        assertEquals(List.of(secretTrue), endings.getChildEndings("end_truth"));
        assertEquals("end_truth", endings.getEnding("end_true_secret").orElseThrow().parentEndingId());

        assertTrue(endings.unlock("end_normal"));
        assertFalse(endings.unlock("end_normal"));
        assertTrue(endings.isUnlocked("end_normal"));
        assertEquals(1, endings.getUnlockedCount());
        assertEquals(25.0, endings.getCompletionPercentage(), 1e-6);

        assertTrue(endings.unlock("end_truth"));
        assertEquals(2, endings.getUnlockedCount());
        assertEquals(50.0, endings.getCompletionPercentage(), 1e-6);

        assertTrue(endings.unlock("end_sacrifice"));
        assertTrue(endings.unlock("end_true_secret"));
        assertEquals(4, endings.getUnlockedCount());
        assertEquals(100.0, endings.getCompletionPercentage(), 1e-6);
    }
}
