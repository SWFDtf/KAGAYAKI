package de.swfdtf.demo;

import de.swfdtf.core.EngineVersion;
import de.swfdtf.demo.director.SnowWhiteAudioShowcase;
import de.swfdtf.demo.director.SnowWhiteCameraChoreographer;
import de.swfdtf.demo.director.SnowWhiteWeatherDirector;
import de.swfdtf.demo.features.SnowWhiteWaveP2ShowcaseServices;
import de.swfdtf.demo.features.SnowWhiteWaveP2ShowcaseServices.DemoEnding;
import de.swfdtf.demo.features.SnowWhiteWaveP2ShowcaseServices.DemoGameplayAndExtras;
import de.swfdtf.demo.features.SnowWhiteWaveP2ShowcaseServices.DemoGameplayAndExtras.CottageTimeOfDay;
import de.swfdtf.demo.features.SnowWhiteWaveP2ShowcaseServices.DemoQaAndBenchmarkSuite;
import de.swfdtf.demo.features.SnowWhiteWaveP2ShowcaseServices.DemoQaAndBenchmarkSuite.Benchmark10kResult;
import de.swfdtf.demo.features.SnowWhiteWaveP2ShowcaseServices.DwarfVoiceSpec;
import de.swfdtf.demo.features.SnowWhiteWaveP2ShowcaseServices.HunterPatrolStealthQuest;
import de.swfdtf.demo.features.SnowWhiteWaveP2ShowcaseServices.MagicMirrorRiddleEvaluator;
import de.swfdtf.demo.features.SnowWhiteWaveP2ShowcaseServices.MagicMirrorRiddleEvaluator.RiddleOutcome;
import de.swfdtf.demo.features.SnowWhiteWaveP2ShowcaseServices.SnowWhiteRosterAndTheme;
import de.swfdtf.demo.service.SnowWhiteEngineService;
import de.swfdtf.graphics.ObjectMotionController.MotionInput;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SceneGraph;
import de.swfdtf.math.Rect2f;
import de.swfdtf.state.PersistentAccess;
import de.swfdtf.state.PersistentState;
import de.swfdtf.state.RevertableStateStore;
import de.swfdtf.state.SaveManager;
import de.swfdtf.state.SaveSlotData;
import de.swfdtf.state.StateStore;
import de.swfdtf.tools.StandaloneBuilder;
import de.swfdtf.tools.VJavaLinter;
import de.swfdtf.tools.VpakArchiver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies Chapter 11 Snow White demo showcase stories, endings, minigames, accessibility
 * fallbacks, multi-resolution layout bounds, 600-frame combined subsystem simulation, 10k-frame
 * benchmark, historical v1.0.0 save compatibility, and standalone export packaging ({@code #0867}–{@code #0935}).
 */
class WaveP2Chapter11SnowWhiteShowcaseTest {

    private static Path resolveProjectDir() {
        Path direct = Path.of("project");
        return Files.isDirectory(direct) ? direct : Path.of("demo", "project");
    }

    @Test
    void endingsChoicesStealthAndRiddleEvaluateDeterministically() {
        assertEquals(DemoEnding.TRUE_FAIRYTALE, SnowWhiteWaveP2ShowcaseServices.evaluateEnding(10, 10, false));
        assertEquals(DemoEnding.HUNTERS_PACT, SnowWhiteWaveP2ShowcaseServices.evaluateEnding(25, 10, false));
        assertEquals(DemoEnding.SECRET_CRYSTAL_TRUTH, SnowWhiteWaveP2ShowcaseServices.evaluateEnding(22, 35, true));

        assertEquals("cottage_evade", SnowWhiteWaveP2ShowcaseServices.resolvePoisonAppleChoice(8.0, true));
        assertEquals("cottage_evade", SnowWhiteWaveP2ShowcaseServices.resolvePoisonAppleChoice(9.4, false));
        assertEquals("cottage_poison", SnowWhiteWaveP2ShowcaseServices.resolvePoisonAppleChoice(3.0, true));
        assertEquals("cottage_safe_refuse", SnowWhiteWaveP2ShowcaseServices.resolvePoisonAppleChoice(3.0, false));

        HunterPatrolStealthQuest stealth = new HunterPatrolStealthQuest();
        double[] safe = stealth.stepStealth(500.0, 500.0, 100.0, 100.0, 80.0);
        assertEquals(500.0, safe[0], 0.001);
        assertEquals(0, stealth.getSpotCount());

        double[] caught = stealth.stepStealth(120.0, 130.0, 100.0, 100.0, 80.0);
        assertEquals(120.0, caught[0], 0.001);
        assertEquals(540.0, caught[1], 0.001);
        assertEquals(1, stealth.getSpotCount());

        RiddleOutcome solved = MagicMirrorRiddleEvaluator.evaluate("Der Zauberspiegel!");
        assertTrue(solved.solved());
        assertTrue(solved.mirrorResponse().contains("Lore-Bonus"));

        RiddleOutcome wrong = MagicMirrorRiddleEvaluator.evaluate("Apfel");
        assertFalse(wrong.solved());
    }

    @Test
    void sevenDwarfsThemeExtrasIsolationAndReduceMotionBehaveProperly() {
        List<DwarfVoiceSpec> dwarfs = SnowWhiteRosterAndTheme.getSevenDwarfs();
        assertEquals(7, dwarfs.size());
        Set<String> names = dwarfs.stream().map(DwarfVoiceSpec::displayName).collect(Collectors.toSet());
        assertEquals(
                Set.of("Chef", "Brummbär", "Happy", "Schlafmütz", "Hatschi", "Pimpel", "Seppl"),
                names
        );
        assertTrue(SnowWhiteRosterAndTheme.isFairytaleGoldWcagCompliant());

        String status = SnowWhiteRosterAndTheme.formatMainMenuStatus(8, 3, 7, 12);
        assertTrue(status.contains(EngineVersion.VERSION));
        assertTrue(status.contains("Endings 3/3"));

        StateStore campaign = new RevertableStateStore();
        campaign.set("courage", 15);
        DemoGameplayAndExtras.replaySceneIsolated(campaign, isolated -> isolated.put("courage", 99));
        assertEquals(15, campaign.getInt("courage", 0));

        DemoGameplayAndExtras extras = new DemoGameplayAndExtras();
        assertTrue(extras.collectItem("mirror_compass"));
        assertFalse(extras.collectItem("mirror_compass"));
        assertEquals(Set.of("mirror_compass"), extras.getInventory());

        PersistentAccess persistent = new PersistentState();
        assertFalse(extras.toggleCommentaryIfUnlocked(persistent, true));
        persistent.unlockEnding("ending_true");
        assertTrue(extras.toggleCommentaryIfUnlocked(persistent, true));

        assertEquals("fade", DemoGameplayAndExtras.resolveQueenAngerTransition(true));
        assertEquals("shatter", DemoGameplayAndExtras.resolveQueenAngerTransition(false));
        assertEquals(0, DemoGameplayAndExtras.resolveCrystalParticleCount(true));
        assertTrue(DemoGameplayAndExtras.resolveCrystalParticleCount(false) > 0);

        assertTrue(DemoGameplayAndExtras.shouldShowArenaTutorialFirstTime(persistent));
        assertFalse(DemoGameplayAndExtras.shouldShowArenaTutorialFirstTime(persistent));

        DemoGameplayAndExtras.unlockEndingTrack(persistent, "music/ending_fairytale.wav");
        assertTrue(persistent.getUnlockedTracks().contains("music/ending_fairytale.wav"));

        float[] morning = DemoGameplayAndExtras.resolveTimeOfDayTint(CottageTimeOfDay.MORNING_LIGHT);
        float[] blizzard = DemoGameplayAndExtras.resolveTimeOfDayTint(CottageTimeOfDay.BLIZZARD_NIGHT);
        assertTrue(morning[0] > blizzard[0]);
        assertTrue(blizzard[2] > morning[2]);

        extras.setWinterSnowSpecial(true);
        assertTrue(extras.isWinterSnowSpecial());
        assertEquals(4, DemoGameplayAndExtras.getPrologQuickJumpLabels().size());
    }

    @Test
    void qaSuiteRuns10kFramesHudAuditAudioBalanceAnd600FrameCombinedSimulation() {
        Benchmark10kResult bench = DemoQaAndBenchmarkSuite.run10kFrameSimulation(10_000);
        assertEquals(10_000, bench.frames());
        assertTrue(bench.avgMs() < 2.0, "Average frame tick must stay well under 2ms, got " + bench.avgMs());
        assertTrue(bench.p99Ms() < 5.0, "P99 frame tick must stay well under 5ms, got " + bench.p99Ms());

        List<Rect2f> hudRects = List.of(
                new Rect2f(0.0f, 820.0f, 1920.0f, 260.0f),
                new Rect2f(40.0f, 24.0f, 360.0f, 64.0f),
                new Rect2f(1520.0f, 24.0f, 360.0f, 64.0f)
        );
        assertTrue(DemoQaAndBenchmarkSuite.verifyHudBoundsWithinCanvas(hudRects));
        assertFalse(DemoQaAndBenchmarkSuite.verifyHudBoundsWithinCanvas(List.of(new Rect2f(1800.0f, 0.0f, 200.0f, 50.0f))));

        assertTrue(DemoQaAndBenchmarkSuite.verifyVoiceOverBgmBalance(0.65f, 0.20f));
        assertFalse(DemoQaAndBenchmarkSuite.verifyVoiceOverBgmBalance(0.15f, 0.65f));

        SceneController stage = new SceneController(new SceneGraph());
        var snowWhite = stage.showSprite("snow_white", List.of("happy"), "center", null);
        float startX = snowWhite.getTransform().getXpos();
        stage.getMotionController().enableWasd("snow_white", 240.0f, 60.0f, 60.0f, 1860.0f, 1020.0f, true);

        SnowWhiteWeatherDirector weather = new SnowWhiteWeatherDirector();
        SnowWhiteCameraChoreographer camera = new SnowWhiteCameraChoreographer();
        SnowWhiteAudioShowcase audio = new SnowWhiteAudioShowcase();

        weather.applyForChapter(2, 0.5);
        camera.applyChapterCue(2, 0.5);
        audio.triggerChapterAudio(2);
        audio.recordVoiceLine("forest_01", "snow_white", "Im Zauberwald leuchten die Glühwürmchen.", "voice/forest_01.wav");

        MotionInput holdRight = new MotionInput(false, false, false, true, false);
        double dt = 1.0 / 60.0;
        for (int frame = 0; frame < 600; frame++) {
            stage.updateMotion(dt, holdRight);
            stage.update(dt);
            weather.update(dt);
            camera.update(dt);
            audio.update(dt);
        }

        assertTrue(snowWhite.getTransform().getXpos() > startX,
                "WASD right input must advance sprite over 600 frames");
        assertTrue(Double.isFinite(snowWhite.getTransform().getXpos()));
        assertFalse(weather.getController().isTransitioning());
        assertFalse(camera.getCameraPresets().isTransitioning());
    }

    @Test
    void loadsHistoricalV1SaveFixtureAndVerifiesZeroLintIssuesAndStandalonePackage(@TempDir Path tempDir)
            throws Exception {
        Path fixtureSource = Path.of("src", "test", "resources", "snow_white_v1_0_0_save.json");
        if (!Files.exists(fixtureSource)) {
            fixtureSource = Path.of("demo", "src", "test", "resources", "snow_white_v1_0_0_save.json");
        }
        assertTrue(Files.isRegularFile(fixtureSource), "Missing historical fixture: " + fixtureSource);

        Path savesDir = tempDir.resolve("saves");
        Files.createDirectories(savesDir);
        byte[] jsonBytes = Files.readAllBytes(fixtureSource);
        ByteArrayOutputStream gzipBuf = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(gzipBuf)) {
            gzipOut.write(jsonBytes);
        }
        Files.write(savesDir.resolve("snow_white_v1.ksav"), gzipBuf.toByteArray());

        SaveManager saveManager = new SaveManager(savesDir);
        SaveSlotData loaded = saveManager.loadSlot("snow_white_v1");
        assertNotNull(loaded);
        assertEquals("chapter_4_cottage", loaded.metadata().currentLabel());
        assertEquals(22, ((Number) loaded.variables().get("hunter_trust")).intValue());

        SnowWhiteEngineService service = new SnowWhiteEngineService(resolveProjectDir());
        service.ensureAssets();
        VJavaLinter.LintReport lint = service.runLint();
        assertTrue(lint.isClean(), "Demo project must have 0 errors and 0 warnings, got: " + lint.issues());

        StandaloneBuilder.BuildResult export = service.exportStandalone(tempDir.resolve("dist"));
        assertTrue(Files.isRegularFile(export.vpakFile()));
        try (VpakArchiver.VpakArchive archive = VpakArchiver.open(export.vpakFile())) {
            assertTrue(archive.contains("project.vjava.json"));
            assertTrue(archive.contains("main.vjava"));
        }
    }
}
