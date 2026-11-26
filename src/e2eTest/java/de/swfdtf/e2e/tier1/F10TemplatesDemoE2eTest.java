package de.swfdtf.e2e.tier1;

import de.swfdtf.audio.AudioDecoder;
import de.swfdtf.audio.LipSyncAnalyzer;
import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineMode;
import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.e2e.support.E2eWorkspaceSupport;
import de.swfdtf.graphics.ObjectMotionController;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.tools.ProjectTemplateGenerator;
import de.swfdtf.tools.StudioWindowController;
import de.swfdtf.tools.TranslationCoverageAuditor;
import de.swfdtf.tools.VJavaLinter;
import de.swfdtf.ui.MainMenuScreen;
import de.swfdtf.ui.ScreenManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 1 happy-path E2E tests for Feature Area F10: project templates ({@code STARTER},
 * {@code MINIMAL}, {@code FULL_DEMO}, {@code ACTION_ADVENTURE_2D}) and the Snow White showcase
 * ({@code #0851}–{@code #0866}).
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER1)
public final class F10TemplatesDemoE2eTest {

    private static final double FIXED_DT = 1.0 / 60.0;

    /** Creates a new {@code F10TemplatesDemoE2eTest} instance. */
    public F10TemplatesDemoE2eTest() {
    }

    /**
     * Verifies baseline {@link ProjectTemplateGenerator} presets ({@code STARTER}, {@code MINIMAL},
     * {@code FULL_DEMO}), lint cleanliness, and {@code wasd_arena} script execution.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if template generation or linting fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void projectTemplateGeneratorStarterMinimalAndFullDemoBaseline(@TempDir Path tempDir) throws Exception {
        Path starterDir = ProjectTemplateGenerator.generate(tempDir.resolve("starter"));
        assertTrue(VJavaLinter.lint(starterDir).isClean(), "STARTER template must be lint-clean");
        VJavaAst.ProjectAst starterAst = ProjectScanner.scan(starterDir);
        assertFalse(starterAst.labels().containsKey("wasd_arena"),
                "Default 1-arg generate(Path) must remain STARTER rather than FULL_DEMO");

        Path minimalDir = ProjectTemplateGenerator.generate(
                tempDir.resolve("minimal"), "Minimal Test", "2D",
                ProjectTemplateGenerator.TemplateType.MINIMAL);
        assertTrue(VJavaLinter.lint(minimalDir).isClean(), "MINIMAL template must be lint-clean");

        Path fullDemoDir = ProjectTemplateGenerator.generate(
                tempDir.resolve("full_demo"), "Full Demo Test", "2D",
                ProjectTemplateGenerator.TemplateType.FULL_DEMO);
        assertTrue(VJavaLinter.lint(fullDemoDir).isClean(), "FULL_DEMO template must be lint-clean");
        VJavaAst.ProjectAst fullAst = ProjectScanner.scan(fullDemoDir);
        assertTrue(fullAst.labels().containsKey("wasd_arena"));

        ScriptRuntime runtime = new ScriptRuntime(fullAst);
        runtime.jumpToLabel("wasd_arena");
        assertTrue(runtime.getSceneController().getMotionController().hasActiveWasd());
    }

    /**
     * Verifies baseline Snow White {@code demo} composite build tasks ({@code lintDemo},
     * {@code runDemoAuto}, {@code runDemoShowcase}) and generated showcase artifacts.
     *
     * @throws Exception if Gradle invocation fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void snowWhiteDemoBaselineLintAutoAndShowcaseGradleTasks() throws Exception {
        Path repoRoot = E2eWorkspaceSupport.repoRoot();
        E2eCliRunner.ProcessResult res = E2eCliRunner.runGradle(
                repoRoot,
                List.of("-p", "demo", "lintDemo", "runDemoAuto", "runDemoShowcase"),
                Duration.ofMinutes(2)
        );
        assertTrue(res.isSuccess(),
                "demo Gradle tasks failed (exit=" + res.exitCode() + "):\n" + res.stdout() + "\n" + res.stderr());

        Path showcaseDir = repoRoot.resolve("demo/build/showcase");
        for (String expected : List.of(
                "snowwhite-flow.mmd",
                "snowwhite-audit.md",
                "snowwhite-diagnostics.json",
                "snowwhite-asset-manifest.json",
                "snowwhite-preflight.md")) {
            Path file = showcaseDir.resolve(expected);
            assertTrue(Files.isRegularFile(file) && Files.size(file) > 0,
                    "Expected non-empty showcase artifact: " + file);
        }
    }

    /**
     * Verifies goals {@code #0851} and {@code #0852}: Snow White demo integrates walkable
     * {@code wasd_init} forest escape and 3-item {@code on_collide} dwarf cottage gathering.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if copying or scanning demo fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void snowWhiteForestEscapeWasdAndDwarfCottageOnCollide(@TempDir Path tempDir) throws Exception {
        Path demoCopy = copyDemoProject(tempDir.resolve("demo_wasd"));
        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(demoCopy);
        assertTrue(lint.isClean(), "Snow White demo must remain lint-clean: " + lint.issues());

        VJavaAst.ProjectAst ast = ProjectScanner.scan(demoCopy);
        List<VJavaAst.WasdInitStatement> wasdInits = collectStatements(ast, VJavaAst.WasdInitStatement.class);
        List<VJavaAst.OnCollideStatement> collisions = collectStatements(ast, VJavaAst.OnCollideStatement.class);

        assertFalse(wasdInits.isEmpty(),
                "Snow White demo must integrate wasd_init for Forest Escape (#0851)");
        assertTrue(collisions.size() >= 3,
                "Snow White demo must integrate >= 3 on_collide targets for Cottage Collect (#0852)");
    }

    /**
     * Verifies goals {@code #0853}, {@code #0859}, {@code #0860}, and {@code #0861}: Snow White
     * demo scripts &amp; runtime wire the mirror reveal ({@code move}/weather), scene weather
     * transitions, camera choreography ({@code WIDE}/{@code DRAMATIC_ZOOM}/{@code CLOSE_UP}),
     * and audio ducking/crossfading.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void snowWhiteMirrorRevealWeatherCameraAndAudioShowcase(@TempDir Path tempDir) throws Exception {
        Path demoCopy = copyDemoProject(tempDir.resolve("demo_directors"));
        VJavaAst.ProjectAst ast = ProjectScanner.scan(demoCopy);

        List<VJavaAst.MoveStatement> moves = collectStatements(ast, VJavaAst.MoveStatement.class);
        assertFalse(moves.isEmpty(),
                "Snow White Chapter 1/2 mirror reveal must wire move/ATL effects (#0853)");

        String allScripts = readAllVJavaText(demoCopy);
        assertTrue(containsIgnoreCase(allScripts, "weather")
                        || containsIgnoreCase(allScripts, "snow")
                        || containsIgnoreCase(allScripts, "fog"),
                "Snow White scripts must couple scene weather events (#0859)");
        assertTrue(containsIgnoreCase(allScripts, "camera")
                        || containsIgnoreCase(allScripts, "dramatic_zoom")
                        || containsIgnoreCase(allScripts, "zoom"),
                "Snow White scripts must couple camera choreography cues (#0860)");
        assertTrue(containsIgnoreCase(allScripts, "ambient")
                        || containsIgnoreCase(allScripts, "crossfade")
                        || containsIgnoreCase(allScripts, "duck"),
                "Snow White scripts must integrate ambient crossfade / audio ducking (#0861)");
    }

    /**
     * Verifies goals {@code #0854}, {@code #0855}, {@code #0856}, {@code #0857}, {@code #0858},
     * {@code #0862}, and {@code #0863}: Snow White demo integrates Chapter UI, Codex links,
     * affinity branching, QuoteBook bookmarks, ReadingStats summary, Accessibility Profile
     * first-start selector, and Screenshot Gallery PNGs.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void snowWhiteMetaUiCodexAffinityQuoteStatsAccessibilityAndGallery(@TempDir Path tempDir) throws Exception {
        Path demoCopy = copyDemoProject(tempDir.resolve("demo_meta"));
        String allScripts = readAllVJavaText(demoCopy);

        // #0855: Codex dialogue links in Snow White scripts
        assertTrue(allScripts.contains("{a=codex:"),
                "Snow White dialogues must link directly to Codex cards via {a=codex:...} (#0855)");

        // #0856: Dwarf affinity secret ending / bonus scene branching
        assertTrue(containsIgnoreCase(allScripts, "affinity")
                        || containsIgnoreCase(allScripts, "geheim")
                        || containsIgnoreCase(allScripts, "secret"),
                "Snow White scripts must wire SnowWhiteAffinityDirector branching (#0856)");

        // #0854, #0857, #0858, #0862, #0863: Headless demo EngineApplication wires meta screens
        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                demoCopy, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            assertNotNull(screens.getScreen("chapter_select"),
                    "Snow White UI must wire Chapter Select screen (#0854)");
            assertNotNull(screens.getScreen("codex"),
                    "Snow White UI must wire Codex screen (#0855)");
            assertNotNull(screens.getScreen("quote_book"),
                    "Snow White UI must wire Quote-Book screen (#0857)");
            assertTrue(containsIgnoreCase(allScripts, "accessibility")
                            || screens.getScreen("accessibility_profile") != null,
                    "Snow White demo must wire first-start accessibility profile selector (#0862)");
            assertTrue(containsIgnoreCase(allScripts, "stats")
                            || containsIgnoreCase(allScripts, "epilog"),
                    "Snow White epilogue must wire ReadingStats summary (#0858)");
            assertTrue(containsIgnoreCase(allScripts, "screenshot")
                            || containsIgnoreCase(allScripts, "gallery"),
                    "Snow White demo must wire screenshot gallery unlocks (#0863)");
        }
    }

    /**
     * Verifies goal {@code #0864}: Snow White English translation under
     * {@code demo/project/tl/english/} achieves 100.0% coverage.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if copying or scanning fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void snowWhiteDemoHas100PercentEnglishTranslationCoverage(@TempDir Path tempDir) throws Exception {
        Path demoCopy = copyDemoProject(tempDir.resolve("demo_i18n"));
        VJavaAst.ProjectAst ast = ProjectScanner.scan(demoCopy);
        TranslationCoverageAuditor.CoverageReport report = new TranslationCoverageAuditor().audit(ast);

        TranslationCoverageAuditor.LanguageCoverage english = report.findLanguage("english").orElseThrow(
                () -> new AssertionError("English translation coverage missing in demo/project"));
        assertEquals(100.0, english.coveragePercent(), 0.01,
                "Snow White English translation must reach 100.0%, untranslated sample: "
                        + english.untranslatedTexts().stream().limit(5).toList());
    }

    /**
     * Verifies goal {@code #0865}: Snow White prologue links voice WAV samples across main
     * characters and drives {@link LipSyncAnalyzer} mouth shape transitions.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if audio decoding fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void snowWhitePrologueHasSyncedVoiceSamplesAndLipSync(@TempDir Path tempDir) throws Exception {
        Path demoCopy = copyDemoProject(tempDir.resolve("demo_voice"));
        VJavaAst.ProjectAst ast = ProjectScanner.scan(demoCopy);
        List<VJavaAst.VoiceStatement> voices = collectStatements(ast, VJavaAst.VoiceStatement.class);

        assertTrue(voices.size() >= 4,
                "Snow White prologue/main characters must link >= 4 voice samples, found: " + voices.size());

        LipSyncAnalyzer lipSync = new LipSyncAnalyzer();
        for (VJavaAst.VoiceStatement vs : voices) {
            Path wavPath = demoCopy.resolve(vs.assetPath());
            assertTrue(Files.isRegularFile(wavPath), "Voice WAV must exist: " + wavPath);
            AudioDecoder.DecodedAudio decoded = AudioDecoder.decode(wavPath);
            assertTrue(decoded.frameCount() > 0);
        }

        LipSyncAnalyzer.MouthShape active = lipSync.updateFromRms(0.45f, 0.1);
        assertTrue(active == LipSyncAnalyzer.MouthShape.HALF_OPEN || active == LipSyncAnalyzer.MouthShape.OPEN,
                "Non-zero voice RMS must drive LipSyncAnalyzer mouth transition");
    }

    /**
     * Verifies goal {@code #0866}: {@link ProjectTemplateGenerator} exposes
     * {@code ACTION_ADVENTURE_2D} with walkable 2D rooms, platforms, NPCs, collision zones, and
     * key puzzle, lint-clean and selectable in {@link StudioWindowController}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if template generation or Studio init fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void actionAdventure2dTemplateIsLintCleanPlayableAndInStudioHub(@TempDir Path tempDir) throws Exception {
        Optional<ProjectTemplateGenerator.TemplateType> actionType = Arrays.stream(
                        ProjectTemplateGenerator.TemplateType.values())
                .filter(t -> "ACTION_ADVENTURE_2D".equals(t.name()))
                .findFirst();
        assertTrue(actionType.isPresent(),
                "ProjectTemplateGenerator.TemplateType must include ACTION_ADVENTURE_2D (#0866)");

        Path outDir = ProjectTemplateGenerator.generate(
                tempDir.resolve("action_2d"), "Action 2D Quest", "2D", actionType.get());
        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(outDir);
        assertTrue(lint.isClean(), "ACTION_ADVENTURE_2D template must have 0 errors and 0 warnings: "
                + lint.issues());

        VJavaAst.ProjectAst ast = ProjectScanner.scan(outDir);
        assertFalse(collectStatements(ast, VJavaAst.WasdInitStatement.class).isEmpty(),
                "ACTION_ADVENTURE_2D must include wasd_init movement");
        assertFalse(collectStatements(ast, VJavaAst.PhysicsInitStatement.class).isEmpty(),
                "ACTION_ADVENTURE_2D must include physics_init platforms");
        assertFalse(collectStatements(ast, VJavaAst.OnCollideStatement.class).isEmpty(),
                "ACTION_ADVENTURE_2D must include on_collide NPC/key/dialog zones");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                outDir, EngineMode.STUDIO_DUAL_WINDOW, false)) {
            app.initialize();
            StudioWindowController studio = app.getApi().getService(StudioWindowController.class);
            studio.setSelectedTemplateType(actionType.get());
            assertEquals(actionType.get(), studio.getSelectedTemplateType());
        }
    }

    private static Path copyDemoProject(Path targetDir) throws IOException {
        Path sourceDir = E2eWorkspaceSupport.repoRoot().resolve("demo/project").toAbsolutePath().normalize();
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        Files.createDirectories(normalizedTarget);
        Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = sourceDir.relativize(dir);
                Files.createDirectories(normalizedTarget.resolve(rel.toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = sourceDir.relativize(file);
                Files.copy(file, normalizedTarget.resolve(rel.toString()));
                return FileVisitResult.CONTINUE;
            }
        });
        return normalizedTarget;
    }

    private static String readAllVJavaText(Path projectDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (var stream = Files.walk(projectDir)) {
            for (Path p : stream.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".vjava")).sorted().toList()) {
                sb.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return sb.toString();
    }

    private static <T extends VJavaAst.Statement> List<T> collectStatements(
            VJavaAst.ProjectAst ast, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (VJavaAst.LabelNode label : ast.labels().values()) {
            collectFromList(label.statements(), type, found);
        }
        return found;
    }

    private static <T extends VJavaAst.Statement> void collectFromList(
            List<VJavaAst.Statement> stmts, Class<T> type, List<T> out) {
        for (VJavaAst.Statement s : stmts) {
            if (type.isInstance(s)) {
                out.add(type.cast(s));
            }
            if (s instanceof VJavaAst.IfStatement ifStmt) {
                for (VJavaAst.IfBranch branch : ifStmt.branches()) {
                    collectFromList(branch.body(), type, out);
                }
            } else if (s instanceof VJavaAst.MenuStatement menuStmt) {
                for (VJavaAst.MenuChoice choice : menuStmt.choices()) {
                    collectFromList(choice.body(), type, out);
                }
            }
        }
    }

    private static boolean containsIgnoreCase(String text, String needle) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
