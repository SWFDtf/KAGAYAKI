package de.swfdtf.demo;

import de.swfdtf.demo.model.CharacterStatus;
import de.swfdtf.demo.model.StoryCheckpointSummary;
import de.swfdtf.demo.service.SnowWhiteConsoleNarrator;
import de.swfdtf.demo.service.SnowWhiteEngineService;
import de.swfdtf.demo.story.SnowWhiteStoryRunner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.tools.StandaloneBuilder;
import de.swfdtf.tools.VJavaLinter;
import de.swfdtf.tools.VpakArchiver;
import de.swfdtf.ui.Screen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-End-Verifikation des Schneewittchen-vJava-Demo-Projekts.
 */
class SnowWhiteDemoTest {

    private static Path resolveProjectDir() {
        Path direct = Path.of("project");
        return Files.isDirectory(direct) ? direct : Path.of("demo", "project");
    }

    @Test
    void projectPassesVJavaLinterCleanly(@TempDir Path tempDir) throws Exception {
        SnowWhiteEngineService service = new SnowWhiteEngineService(resolveProjectDir());
        service.ensureAssets();

        VJavaLinter.LintReport report = service.runLint();
        assertTrue(report.isClean(), "VJavaLinter fand Probleme: " + report.issues());

        StandaloneBuilder.BuildResult export = service.exportStandalone(tempDir.resolve("dist"));
        assertTrue(Files.isRegularFile(export.vpakFile()));
        try (VpakArchiver.VpakArchive archive = VpakArchiver.open(export.vpakFile())) {
            assertTrue(archive.contains("project.vjava.json"));
            assertTrue(archive.contains("main.vjava"));
            assertTrue(archive.contains("kapitel/01_vorgeschichte_und_spiegel.vjava"));
            assertTrue(archive.contains("kapitel/02_flucht_und_zwerge.vjava"));
            assertTrue(archive.contains("kapitel/03_drei_mordversuche.vjava"));
            assertTrue(archive.contains("kapitel/04_glassarg_und_hochzeit.vjava"));
            assertTrue(archive.contains("ui/screens.vjava"));
            assertTrue(archive.contains("tl/english/translations.vjava"));
        }
    }

    @Test
    void fullSnowWhiteStoryExecutesAllSevenChaptersAndStates(@TempDir Path tempDir) throws Exception {
        SnowWhiteEngineService service = new SnowWhiteEngineService(resolveProjectDir());
        SnowWhiteStoryRunner runner = new SnowWhiteStoryRunner(service, new SnowWhiteConsoleNarrator());

        List<StoryCheckpointSummary> timeline = runner.runAutoDemo(tempDir.resolve("saves"));
        assertFalse(timeline.isEmpty());

        Set<Integer> chapters = timeline.stream()
                .map(StoryCheckpointSummary::kapitelNummer)
                .collect(Collectors.toSet());
        assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7), chapters, "Alle 7 Kapitel müssen durchlaufen werden");

        Set<CharacterStatus> states = timeline.stream()
                .map(StoryCheckpointSummary::schneewittchenState)
                .collect(Collectors.toSet());
        assertTrue(states.contains(CharacterStatus.LEBENDIG));
        assertTrue(states.contains(CharacterStatus.SCHLAFEND));
        assertTrue(states.contains(CharacterStatus.OHNMAECHTIG));
        assertTrue(states.contains(CharacterStatus.VERGIFTET));
        assertTrue(states.contains(CharacterStatus.ERLOEST));

        StoryCheckpointSummary last = timeline.get(timeline.size() - 1);
        assertEquals(7, last.kapitelNummer());
        assertEquals(CharacterStatus.ERLOEST, last.schneewittchenState());
    }

    @Test
    void verifiesHudScreenLocalizationAndRollbackBarrier(@TempDir Path tempDir) throws Exception {
        SnowWhiteEngineService service = new SnowWhiteEngineService(resolveProjectDir());
        try (SnowWhiteEngineService.SessionContext ctx = service.createHeadlessSession(tempDir.resolve("saves"))) {
            ScriptSession session = ctx.session();
            session.start();

            Screen hud = service.buildHudScreen(session);
            assertNotNull(hud);
            assertEquals("maerchen_hud", hud.getName());

            // Englische Übersetzung für den Prolog prüfen
            session.getLocalizationManager().setLanguage("english");
            String translated = session.getLocalizationManager().translateDialogue(
                    "prolog_01",
                    "Ach, hätte ich doch ein Kind, so weiß wie Schnee, so rot wie Blut und so schwarz wie Ebenholz!"
            );
            assertTrue(translated.contains("as white as snow"));
            session.getLocalizationManager().setLanguage("deutsch");

            long firstCheckpointId = session.getStateStore().getCheckpoints().get(0).id();
            boolean sawRollbackBarrierInChapter7 = false;

            // Schnell bis ans Ende vorrücken und block_rollback verifizieren
            int guard = 0;
            while (session.getRuntime().getStatus() != ScriptRuntime.Status.ENDED && guard++ < 400) {
                if ("kapitel_7_hochzeit_und_ende".equals(session.getRuntime().getCurrentLabel())
                        && session.getStateStore().isRollbackBlocked()) {
                    assertFalse(session.rollback(), "Rollback direkt an der block_rollback-Barriere muss scheitern");
                    sawRollbackBarrierInChapter7 = true;
                }
                switch (session.getRuntime().getStatus()) {
                    case WAITING_DIALOGUE, PAUSING -> session.advance();
                    case WAITING_MENU -> session.choose(1);
                    case WAITING_INPUT -> session.submitInput("");
                    default -> session.update(1.0 / 60.0);
                }
            }

            assertEquals(ScriptRuntime.Status.ENDED, session.getRuntime().getStatus());
            assertTrue(sawRollbackBarrierInChapter7, "block_rollback-Barriere in Kapitel 7 muss gegriffen haben");
            assertFalse(session.getStateStore().jumpToCheckpoint(firstCheckpointId),
                    "Sprung über die block_rollback-Barriere zurück nach Kapitel 1 muss blockiert sein");
            assertEquals("BESTRAFT", session.getStateStore().getString("stiefmutter_status", ""));
            assertTrue(session.getEndingsManager().isUnlocked("ending_hochzeit"));
            assertEquals(5, session.getAchievementManager().getUnlockedCount());
            assertEquals(5, session.getGalleryManager().getUnlockedCount());
        }
    }
}
