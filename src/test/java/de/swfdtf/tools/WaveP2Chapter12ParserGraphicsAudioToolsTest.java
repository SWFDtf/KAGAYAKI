package de.swfdtf.tools;

import de.swfdtf.Main;
import de.swfdtf.audio.AudioDecoder;
import de.swfdtf.audio.AudioDecoder.DecodedAudio;
import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineMode;
import de.swfdtf.core.KeybindingRemapper;
import de.swfdtf.graphics.GraphicsQualityAndFramingServices.ColorBlendMath;
import de.swfdtf.graphics.GraphicsQualityAndFramingServices.DialogCameraFramingPresets;
import de.swfdtf.graphics.GraphicsQualityAndFramingServices.DisplayCalibrationAssistant;
import de.swfdtf.graphics.GraphicsQualityAndFramingServices.DualWindowLayoutPlanner;
import de.swfdtf.graphics.GraphicsQualityAndFramingServices.HudFadeController;
import de.swfdtf.graphics.GraphicsQualityAndFramingServices.TransformSanitizer;
import de.swfdtf.graphics.GraphicsQualityAndFramingServices.TransitionConvergenceVerifier;
import de.swfdtf.graphics.GraphicsQualityAndFramingServices.ViewportSanitizer;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SceneGraph;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.graphics.Transition;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Transform2D;
import de.swfdtf.parser.ConditionEvaluator;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.parser.VJavaParser;
import de.swfdtf.state.PreferencesState;
import de.swfdtf.state.RevertableStateStore;
import de.swfdtf.state.SaveMigrationService;
import de.swfdtf.state.SaveMigrationStep;
import de.swfdtf.state.SaveSlotData;
import de.swfdtf.state.SaveWorkflowServices;
import de.swfdtf.testutil.KagayakiTestBuilders;
import de.swfdtf.tools.ProjectTemplateGenerator.TemplateType;
import de.swfdtf.tools.QualityAssuranceAndReleaseServices.ArenaScenarioCollisionWarper;
import de.swfdtf.tools.QualityAssuranceAndReleaseServices.ChangelogReleaseValidator;
import de.swfdtf.tools.QualityAssuranceAndReleaseServices.LiveSpriteInspectorModel;
import de.swfdtf.tools.QualityAssuranceAndReleaseServices.PlaytestSuiteAggregator;
import de.swfdtf.tools.QualityAssuranceAndReleaseServices.ProjectVersionCompatibilityBadge;
import de.swfdtf.tools.QualityAssuranceAndReleaseServices.RepositoryLinkChecker;
import de.swfdtf.tools.QualityAssuranceAndReleaseServices.RoadmapProgressReporter;
import de.swfdtf.tools.QualityAssuranceAndReleaseServices.ScreenshotFormatExporter;
import de.swfdtf.tools.QualityAssuranceAndReleaseServices.StudioNewProjectAutoLayoutState;
import de.swfdtf.ui.GameRuntimeCoordinator;
import de.swfdtf.ui.Screen;
import de.swfdtf.ui.TextButton;
import de.swfdtf.ui.UiPolishAndServices.ControlLegendFormatter;
import de.swfdtf.ui.UiPolishAndServices.TabOrderAuditor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chapter 12 parser, linter, graphics, audio, UI, documentation link/roadmap, studio inspector,
 * release dry-run helper, and reference playtest tests ({@code #0955}–{@code #1017}).
 */
class WaveP2Chapter12ParserGraphicsAudioToolsTest {

    @Test
    void parserRecursionGuardRangesUnicodeEscapesAndLinterRulesWorkCleanly(@TempDir Path tempDir)
            throws Exception {
        // #1002: Range syntax 'in low..high' & 'between low and high'
        RevertableStateStore state = KagayakiTestBuilders.stateStore(Map.of("mut_punkte", 35));
        assertTrue(ConditionEvaluator.evaluateBoolean("mut_punkte in 20..50", state, null));
        assertFalse(ConditionEvaluator.evaluateBoolean("mut_punkte in 40..50", state, null));
        assertTrue(ConditionEvaluator.evaluateBoolean("mut_punkte between 20 and 50", state, null));
        assertFalse(ConditionEvaluator.evaluateBoolean("mut_punkte between 10 and 30", state, null));

        // #0960, #0961, #0978, #1009, #1014: Recursion depth guard, Thread.sleep warning,
        // duplicate on_collide, @deprecated label, and Unicode escape roundtrip
        Path proj = ProjectTemplateGenerator.generate(
                tempDir.resolve("lint_adv"), "Lint Test", "QA", "2D", TemplateType.MINIMAL);
        Files.writeString(proj.resolve("main.vjava"), """
                define a = Character("A", color="#ffffff")
                image p = "assets/images/bg/bg_akademie_nacht.png"
                image g = "assets/images/ui/dialog_frame.png"
                label start:
                    @->{
                        Thread.sleep(5);
                    }<<
                    a "Zeile 1\\nZitat: \\"Hallo\\" \\\\ Pfad \\u00E4"
                    on_collide p g jump legacy_scene
                    on_collide p g jump legacy_scene
                    jump legacy_scene

                # @deprecated -> use start
                label legacy_scene:
                    call endless_loop

                label endless_loop:
                    call endless_loop
                    return
                """, StandardCharsets.UTF_8);

        VJavaAst.ProjectAst ast = ProjectScanner.scan(proj);
        VJavaLinter.LintReport lint = VJavaLinter.lint(ast, false);
        assertTrue(lint.issues().stream().anyMatch(i -> "BLOCKING_CALL_IN_JAVA_BLOCK".equals(i.code())));
        assertTrue(lint.issues().stream().anyMatch(i -> "DUPLICATE_COLLISION_TRIGGER".equals(i.code())));
        assertTrue(lint.issues().stream().anyMatch(i -> "DEPRECATED_LABEL_TARGET".equals(i.code())));

        // #0960: Infinite call recursion throws IllegalStateException instead of StackOverflowError
        ScriptRuntime rt = new ScriptRuntime(
                ast,
                new EngineAPI(EngineConfig.builder().headless(true).build()));
        rt.start();
        assertThrows(IllegalStateException.class, () -> {
            while (rt.getStatus() != ScriptRuntime.Status.ENDED) {
                rt.advance();
            }
        });

        // #0978: Unicode escapes remain parseable
        VJavaAst.ScriptFileAst parsed = VJavaParser.parse(
                Path.of("escaped.vjava"), "label start:\n    \"Hi \\\"there\\\"\\\\n\\u00FC\"\n    return\n");
        assertEquals(1, parsed.labels().size());
    }

    @Test
    void massiveProjectWith100FilesAnd50kLinesScansAndLintsQuickly(@TempDir Path tempDir) throws Exception {
        // #0959: 100 .vjava files / 50,000 dialogue lines
        Path massive = ProjectTemplateGenerator.generate(
                tempDir.resolve("massive"), "Massive Novel", "QA", "2D", TemplateType.MINIMAL);
        StringBuilder body = new StringBuilder();
        for (int line = 0; line < 500; line++) {
            body.append("    narrator \"Dialogzeile Nummer ").append(line).append("\"\n");
        }
        for (int f = 1; f < 100; f++) {
            Files.writeString(massive.resolve("ch_" + f + ".vjava"),
                    "label ch_" + f + ":\n" + body + "    return\n", StandardCharsets.UTF_8);
        }
        long t0 = System.nanoTime();
        VJavaAst.ProjectAst ast = ProjectScanner.scan(massive);
        VJavaLinter.LintReport report = VJavaLinter.lint(ast, false);
        double sec = (System.nanoTime() - t0) / 1_000_000_000.0;
        assertEquals(0, report.errorCount());
        assertEquals(100, ast.labels().size());
        assertTrue(sec < 5.0, "Massive 100-file / 50k-line scan+lint should stay fast, took " + sec + "s");
    }

    @Test
    void graphicsSanitizersBlendFramingLayoutTransitionsCalibrationAndHudFadeWork() {
        // #0955, #0976, #0985, #0993, #0998, #1001, #1007, #1015
        Transform2D corrupted = new Transform2D()
                .setXpos(Float.NaN).setZoom(-2.0f).setAlpha(4.0f).setRotate(Float.POSITIVE_INFINITY);
        TransformSanitizer.sanitize(corrupted);
        assertEquals(0.0f, corrupted.getXpos());
        assertEquals(0.0f, corrupted.getRotate());
        assertTrue(corrupted.getZoom() > 0.0f);
        assertEquals(1.0f, corrupted.getAlpha());

        Rect2f safe = ViewportSanitizer.safeRect(-10.0f, 5.0f, -20.0f, 100.0f);
        assertEquals(0.0f, safe.width());
        assertThrows(IllegalArgumentException.class,
                () -> ViewportSanitizer.requireNonNegativeRect(0, 0, -1, 10));

        float[] blackPremul = ColorBlendMath.premultiply(0.0f, 0.0f, 0.0f, 2.0f);
        assertArrayEquals(new float[]{0.0f, 0.0f, 0.0f, 1.0f}, blackPremul, 1e-6f);
        float[] whiteOverBlack = ColorBlendMath.blendOver(
                ColorBlendMath.premultiply(1.0f, 1.0f, 1.0f, 0.5f), blackPremul);
        assertEquals(1.0f, whiteOverBlack[3], 1e-6f);

        Rect2f workarea = new Rect2f(0.0f, 25.0f, 1920.0f, 1055.0f);
        Rect2f[] windows = DualWindowLayoutPlanner.computeSideBySideLayout(workarea);
        assertTrue(windows[0].x() >= workarea.x());
        assertTrue(windows[0].x() + windows[0].width() < windows[1].x());
        assertTrue(windows[1].x() + windows[1].width() <= workarea.x() + workarea.width());

        double t60 = TransitionConvergenceVerifier.simulateAtRate(Transition.dissolve(0.4f), 60);
        double t144 = TransitionConvergenceVerifier.simulateAtRate(Transition.dissolve(0.4f), 144);
        assertEquals(t60, t144, 0.05);

        SpriteNode left = KagayakiTestBuilders.sprite("left", 0, 0);
        SpriteNode right = KagayakiTestBuilders.sprite("right", 0, 0);
        DialogCameraFramingPresets.frameTwoShot(left, right, 1920.0f, 1080.0f);
        assertTrue(left.getTransform().getXpos() < right.getTransform().getXpos());
        DialogCameraFramingPresets.frameEstablishingShot(left, 1920.0f, 1080.0f);
        assertEquals(960.0f, left.getTransform().getXpos(), 1e-3f);

        PreferencesState prefs = new PreferencesState();
        float applied = DisplayCalibrationAssistant.applyGamma(prefs, 1.4f);
        assertEquals(1.4f, applied, 1e-5f);
        assertTrue(DisplayCalibrationAssistant.computeCalibrationStarLuminance(1.4f) > 0.0f);

        HudFadeController hudFade = new HudFadeController();
        hudFade.hideWithDissolve(0.2);
        hudFade.update(0.1);
        assertEquals(0.5f, hudFade.getAlpha(), 0.05f);
        hudFade.update(0.1);
        assertEquals(0.0f, hudFade.getAlpha(), 0.01f);
    }

    @Test
    void audioExtraChunksRoundtripAndNoPopFadeInWork() throws Exception {
        // #0956, #0977, #1008
        DecodedAudio tone = AudioDecoder.synthesizeTone(440.0, 0.25, 22_050, 0.5f);
        byte[] wav = AudioDecoder.encodeWav(tone.pcm16(), tone.channels(), tone.sampleRate());
        DecodedAudio decoded = AudioDecoder.decodeWav(wav);
        assertArrayEquals(tone.pcm16(), decoded.pcm16());

        // Inject JUNK + LIST chunks right before 'data'
        ByteArrayOutputStream withJunk = new ByteArrayOutputStream();
        withJunk.write(wav, 0, 36);
        byte[] junkHeader = new byte[]{'J', 'U', 'N', 'K', 4, 0, 0, 0, 1, 2, 3, 4};
        withJunk.write(junkHeader);
        byte[] listHeader = new byte[]{'L', 'I', 'S', 'T', 4, 0, 0, 0, 'I', 'N', 'F', 'O'};
        withJunk.write(listHeader);
        withJunk.write(wav, 36, wav.length - 36);
        byte[] patched = withJunk.toByteArray();
        ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN).putInt(4, patched.length - 8);
        DecodedAudio fromJunk = AudioDecoder.decodeWav(patched);
        assertArrayEquals(tone.pcm16(), fromJunk.pcm16());

        float[] ramp = new float[22_050];
        java.util.Arrays.fill(ramp, 0.8f);
        AudioDecoder.applyNoPopFadeIn(ramp, 22_050, 1, 0.15);
        assertEquals(0.0f, ramp[0], 1e-6f);
        assertEquals(0.8f, ramp[ramp.length - 1], 1e-6f);
    }

    @Test
    void docsLinksRoadmapUiAuditMigrationInspectorExportAndPlaytestsSucceed(@TempDir Path tempDir)
            throws Exception {
        // #0979, #0981, #0982, #0984, #0986, #0989, #0992, #0997, #1003-#1006, #1010, #1011, #1016, #1017
        Path repoRoot = Path.of("").toAbsolutePath().normalize();
        var roadmap = RoadmapProgressReporter.summarizeZieleMarkdown(repoRoot.resolve("ZIELE.md"));
        assertTrue(roadmap.formatMarkdownTable().contains("| P2 |"));

        var brokenLinks = RepositoryLinkChecker.checkRelativeLinks(repoRoot, List.of(
                repoRoot.resolve("README.md"),
                repoRoot.resolve("CONTRIBUTING.md"),
                repoRoot.resolve("SECURITY.md"),
                repoRoot.resolve("docs/HANDBUCH_DE.md"),
                repoRoot.resolve("docs/GUIDE_EN.md"),
                repoRoot.resolve("docs/GUI_SMOKE_CHECKLIST.md"),
                repoRoot.resolve("docs/TUTORIAL_ERSTES_PROJEKT.md")
        ));
        assertTrue(brokenLinks.isEmpty(), "Broken links found: " + brokenLinks);
        assertTrue(ChangelogReleaseValidator.validate(repoRoot.resolve("docs/CHANGELOG_DEV.md")));

        // #0984: Multi-step v1 -> v2 -> v3 -> v4 save migration
        SaveMigrationService migrator = new SaveMigrationService(1);
        migrator.registerStep(addVarStep(1, 2, "step2", true));
        migrator.registerStep(addVarStep(2, 3, "step3", 42));
        migrator.registerStep(addVarStep(3, 4, "step4", "migrated"));
        migrator.validateChain();
        SaveSlotData v4 = migrator.migrateToLatest(KagayakiTestBuilders.saveSlot("m_slot").build());
        assertEquals("migrated", v4.variables().get("step4"));

        // #1003, #1004, #0981, #0989
        assertEquals("WASD-Arena (x=420, y=780)",
                SaveWorkflowServices.formatWasdArenaBadge("WASD-Arena", 420.2f, 779.6f));
        assertTrue(ProjectVersionCompatibilityBadge.formatNotice("1.0.0", "1.1.0").contains("Studio v1.1.0"));

        KeybindingRemapper remapper = new KeybindingRemapper();
        remapper.registerDefault("Confirm", org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
        assertFalse(ControlLegendFormatter.formatBindings(remapper, "de").isEmpty());

        Screen uiScreen = new Screen("audit_screen", 10, false);
        TextButton btnPlay = new TextButton("Play", () -> {
        });
        btnPlay.setId("btn_play");
        uiScreen.setRoot(btnPlay);
        assertEquals(List.of("btn_play"), TabOrderAuditor.collectFocusableIds(uiScreen));

        // #1005, #1006, #1010, #1011
        SceneController stage = new SceneController(new SceneGraph());
        stage.showSprite("player", List.of(), "center", null);
        stage.showSprite("goal", List.of(), "right", null);
        stage.getMotionController().enableWasd("player", 200.0f, 0f, 0f, 1920f, 1080f, true);
        assertEquals(2, LiveSpriteInspectorModel.captureRows(stage).size());
        assertFalse(ArenaScenarioCollisionWarper.warpPlayerToTarget(stage, "player", "goal", false));
        assertTrue(ArenaScenarioCollisionWarper.warpPlayerToTarget(stage, "player", "goal", true));

        byte[] rgba = new byte[16 * 16 * 4];
        java.util.Arrays.fill(rgba, (byte) 127);
        assertTrue(ScreenshotFormatExporter.exportLosslessPng(16, 16, rgba).length > 8);
        assertTrue(ScreenshotFormatExporter.exportCompactJpegPreview(16, 16, rgba, 75).length > 10);

        // #0992, #0997, #1017
        Path fullDemo = tempDir.resolve("full_smoke");
        ProjectTemplateGenerator.generate(fullDemo, "Smoke Demo", "QA", "2D", TemplateType.FULL_DEMO);
        var layoutReady = StudioNewProjectAutoLayoutState.prepareNewProjectLayout(fullDemo);
        assertTrue(layoutReady.lintClean());
        assertTrue(new UnusedAssetDetector().analyze(ProjectScanner.scan(fullDemo)).unusedAssets().isEmpty());

        Main.main(new String[]{"--project", fullDemo.toString(), "--smoke-test", "--headless"});

        EngineConfig cfg = EngineConfig.builder().engineMode(EngineMode.STANDALONE_GAME)
                .projectDir(fullDemo).headless(true).build();
        try (EngineApplication app = new EngineApplication(cfg, null, new GameRuntimeCoordinator(false))) {
            app.initialize();
            GameRuntimeCoordinator coord = (GameRuntimeCoordinator) app.getGameHost();
            coord.dispatchScreenAction("ShowMenu(\"load_screen\")");
            coord.dispatchScreenAction("Return()");
            coord.dispatchScreenAction("Start()");
            coord.dispatchScreenAction("QuickSave()");
            coord.dispatchScreenAction("QuickLoad()");
            coord.dispatchScreenAction("Jump(\"wasd_arena\")");
            coord.dispatchScreenAction("Quit()");
        }

        Path demoDir = Files.isDirectory(Path.of("demo/project")) ? Path.of("demo/project") : null;
        var playtests = PlaytestSuiteAggregator.runReferencePlaytests(tempDir.resolve("agg"), demoDir);
        assertTrue(playtests.stream().allMatch(PlaytestSuiteAggregator.ProjectPlaytestResult::passed),
                "Playtest failures: " + playtests);
    }

    private static SaveMigrationStep addVarStep(int from, int to, String key, Object val) {
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
                Map<String, Object> vars = new LinkedHashMap<>(input.variables());
                vars.put(key, val);
                return new SaveSlotData(input.metadata(), vars, input.scriptPosition(), input.historyEntries());
            }
        };
    }
}
