package de.swfdtf.e2e.tier1;

import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2eFixtureFactory;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.e2e.support.E2eWorkspaceSupport;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.parser.ConditionEvaluator;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.state.SimplePersistentAccess;
import de.swfdtf.state.SimpleStateStore;
import de.swfdtf.tools.ProjectTemplateGenerator;
import de.swfdtf.tools.VJavaLinter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 1 happy-path E2E tests for Feature Area F1: {@code .vjava} scripting language,
 * control flow, ATL keyframes, compile cache, format interpolation, and determinism.
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER1)
public final class F01ScriptingLanguageE2eTest {

    /** Creates a new {@code F01ScriptingLanguageE2eTest} instance. */
    public F01ScriptingLanguageE2eTest() {
    }

    /**
     * Verifies baseline {@code .vjava} lifecycle on {@code f01/valid_baseline}: lint cleanliness,
     * embedded Java class instantiation, interpolation, branching menu choice, and clean ending.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or script execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void starterProjectExecutesBranchingJavaBlocksAndInterpolation(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f01_valid_baseline");
        E2eWorkspaceSupport.copyResourceTree("e2e/f01/valid_baseline", projectDir);
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/bg/bg_test.png"),
                64,
                64,
                24,
                40,
                72,
                255
        );

        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertTrue(lint.isClean(), "Expected clean lint on valid_baseline, got: " + lint.issues());

        VJavaAst.ProjectAst ast = ProjectScanner.scan(projectDir);
        ScriptRuntime runtime = new ScriptRuntime(ast);
        runtime.start();

        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        assertNotNull(runtime.getActiveDialogue());
        assertEquals("Aria", runtime.getActiveDialogue().speakerName());
        assertEquals("Hallo Welt Aria hat 30 Punkte.", runtime.getActiveDialogue().interpolatedText());

        runtime.advance();
        assertEquals(ScriptRuntime.Status.WAITING_MENU, runtime.getStatus());
        assertNotNull(runtime.getActiveMenu());
        assertEquals(2, runtime.getActiveMenu().options().size());

        runtime.choose(0);
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        assertEquals("ending_good", runtime.getCurrentLabel());
        assertEquals("Gutes Ende erreicht.", runtime.getActiveDialogue().interpolatedText());

        runtime.advance();
        assertEquals(ScriptRuntime.Status.ENDED, runtime.getStatus());
    }

    /**
     * Verifies goal {@code #0032} positive path: consistent numeric, boolean, and string
     * {@code $} assignments pass {@link VJavaLinter#lint(Path)} with zero errors.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if template generation or linting fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void strictTypeCheckingAcceptsConsistentAssignments(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f01_starter");
        E2eWorkspaceSupport.createFromTemplate(
                projectDir,
                "F01 Type Check Starter",
                "2D",
                ProjectTemplateGenerator.TemplateType.STARTER
        );
        E2eWorkspaceSupport.writeScript(projectDir, "extra_types.vjava", """
                label type_ok_sample:
                    $ count = 10 + 5 * 2
                    $ ratio = 1.5 + 2.5
                    $ ready = true && !false
                    $ label_text = "Level " + count
                    return
                """);

        VJavaLinter.LintReport report = E2eCliRunner.lintProject(projectDir);
        assertFalse(report.hasErrors(), "Consistent $ assignments must not fail lint: " + report.issues());
    }

    /**
     * Verifies goal {@code #0939}: {@link ConditionEvaluator} associativity, arithmetic precedence,
     * numeric coercion, and deterministic repeated execution of {@link ScriptSession}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void conditionEvaluatorAndStateStoreDeterminism(@TempDir Path tempDir) throws Exception {
        SimpleStateStore state = new SimpleStateStore();
        SimplePersistentAccess persistent = new SimplePersistentAccess();
        state.set("a", 7);
        state.set("b", 3);
        state.set("c", 2);

        assertEquals(13, ConditionEvaluator.evaluate("a + b * c", state, persistent));
        assertEquals(20, ConditionEvaluator.evaluate("(a + b) * c", state, persistent));
        assertEquals(Boolean.TRUE, ConditionEvaluator.evaluate("10 == 10.0", state, persistent));
        assertEquals(Boolean.TRUE, ConditionEvaluator.evaluate("((a > b) && (b > c)) == true", state, persistent));

        Path run1Dir = tempDir.resolve("run1");
        Path run2Dir = tempDir.resolve("run2");
        E2eWorkspaceSupport.copyResourceTree("e2e/f01/valid_baseline", run1Dir);
        E2eWorkspaceSupport.copyResourceTree("e2e/f01/valid_baseline", run2Dir);
        E2eFixtureFactory.writeSolidPng(
                run1Dir.resolve("assets/images/bg/bg_test.png"), 32, 32, 10, 20, 30, 255);
        E2eFixtureFactory.writeSolidPng(
                run2Dir.resolve("assets/images/bg/bg_test.png"), 32, 32, 10, 20, 30, 255);

        List<String> linesRun1 = executeValidBaselineTranscript(run1Dir);
        List<String> linesRun2 = executeValidBaselineTranscript(run2Dir);
        assertEquals(linesRun1, linesRun2);
        assertEquals(List.of("Hallo Welt Aria hat 30 Punkte.", "Gutes Ende erreicht."), linesRun1);
    }

    private static List<String> executeValidBaselineTranscript(Path projectDir) {
        VJavaAst.ProjectAst ast = ProjectScanner.scan(projectDir);
        ScriptRuntime runtime = new ScriptRuntime(ast);
        runtime.start();
        List<String> dialogueLines = new ArrayList<>();
        while (runtime.getStatus() != ScriptRuntime.Status.ENDED) {
            if (runtime.getStatus() == ScriptRuntime.Status.WAITING_DIALOGUE) {
                dialogueLines.add(runtime.getActiveDialogue().interpolatedText());
                runtime.advance();
            } else if (runtime.getStatus() == ScriptRuntime.Status.WAITING_MENU) {
                runtime.choose(0);
            } else {
                runtime.advance();
            }
        }
        return List.copyOf(dialogueLines);
    }

    /**
     * Verifies goal {@code #0033}: {@code match <expr>:} evaluates matching {@code case} arms
     * and {@code default:} deterministically.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if parsing or execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void matchStatementEvaluatesMatchingBranchAndDefault(@TempDir Path tempDir) throws Exception {
        assertEquals("Stimmung: fröhlich.", runMatchFixtureWithMood(tempDir.resolve("happy"), "happy"));
        assertEquals("Stimmung: traurig.", runMatchFixtureWithMood(tempDir.resolve("sad"), "sad"));
        assertEquals("Stimmung: neutral.", runMatchFixtureWithMood(tempDir.resolve("unknown"), "unknown"));
    }

    private static String runMatchFixtureWithMood(Path projectDir, String moodValue) throws Exception {
        E2eWorkspaceSupport.copyResourceTree("e2e/f01/match_case", projectDir);
        String script = """
                define lena = Character("Lena", color="#58a6ff")
                default mood = "%s"

                label start:
                    match mood:
                        case "happy":
                            lena "Stimmung: fröhlich."
                        case "sad":
                            lena "Stimmung: traurig."
                        default:
                            lena "Stimmung: neutral."
                    return
                """.formatted(moodValue);
        E2eWorkspaceSupport.writeScript(projectDir, "main.vjava", script);

        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertFalse(lint.hasErrors(), "Valid match script must lint cleanly: " + lint.issues());

        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(projectDir));
        runtime.start();
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        assertNotNull(runtime.getActiveDialogue());
        return runtime.getActiveDialogue().interpolatedText();
    }

    /**
     * Verifies goal {@code #0036}: {@code at transform:} ATL keyframes advance sprite transform
     * properties over frames and loop cleanly on {@code repeat}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void atlTransformKeyframesAdvanceOverFrames(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f01_atl");
        E2eWorkspaceSupport.copyResourceTree("e2e/f01/atl_keyframes", projectDir);
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/bg/bg_sky.png"), 64, 64, 40, 80, 160, 255);
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/sprites/cloud.png"), 32, 32, 240, 240, 240, 255);

        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertFalse(lint.hasErrors(), "ATL fixture must lint without errors: " + lint.issues());

        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(projectDir));
        runtime.start();
        SceneController controller = runtime.getSceneController();
        SpriteNode cloud = controller.getActiveSprite("cloud");
        assertNotNull(cloud, "cloud sprite should be active after show cloud at cloud_drift");

        for (int i = 0; i < 30; i++) {
            runtime.update(1.0 / 60.0);
        }
        float midX = cloud.getTransform().getXpos();
        assertTrue(midX > 100.0f && midX < 300.0f, "Expected mid-tween xpos near 200, got: " + midX);

        for (int i = 0; i < 60; i++) {
            runtime.update(1.0 / 60.0);
        }
        float loopedX = cloud.getTransform().getXpos();
        assertTrue(loopedX >= 0.0f && loopedX < 250.0f,
                "Expected looped xpos after repeat cycle, got: " + loopedX);
    }

    /**
     * Verifies goal {@code #0048}: string interpolation format specifiers ({@code :.2f},
     * {@code :,.2f}, {@code :.1%}) format values deterministically in dialogue text.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void formatSpecifierInterpolatesNumericAndPercentValues(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f01_format");
        E2eWorkspaceSupport.copyResourceTree("e2e/f01/format_spec", projectDir);

        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertFalse(lint.hasErrors(), "Format fixture must lint cleanly: " + lint.issues());

        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(projectDir));
        runtime.start();

        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        assertEquals("Plain: 1234.50", runtime.getActiveDialogue().interpolatedText());

        runtime.advance();
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        assertEquals("Grouped: 1,234.50", runtime.getActiveDialogue().interpolatedText());

        runtime.advance();
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        assertEquals("Percent: 25.6%", runtime.getActiveDialogue().interpolatedText());
    }

    /**
     * Verifies goal {@code #0044}: {@code build/.vjava-cache} is populated on initial compile
     * and accelerates unchanged re-scans below 20 ms after warmup.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if compilation or file inspection fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void javaBlockCompileCacheAcceleratesRescan(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f01_cache");
        E2eWorkspaceSupport.copyResourceTree("e2e/f01/valid_baseline", projectDir);
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/bg/bg_test.png"), 32, 32, 20, 30, 40, 255);

        VJavaLinter.LintReport first = E2eCliRunner.lintProject(projectDir);
        assertTrue(first.isClean());

        Path cacheDir = projectDir.resolve("build").resolve(".vjava-cache");
        assertTrue(Files.isDirectory(cacheDir), "Expected compile cache directory at " + cacheDir);
        try (Stream<Path> entries = Files.list(cacheDir)) {
            assertTrue(entries.findAny().isPresent(), "Expected cached bytecode artifacts under " + cacheDir);
        }

        // Warmup second pass before timing third cached pass.
        assertTrue(E2eCliRunner.lintProject(projectDir).isClean());
        long startNanos = System.nanoTime();
        VJavaLinter.LintReport cached = E2eCliRunner.lintProject(projectDir);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        assertTrue(cached.isClean());
        assertTrue(elapsedMs < 20L, "Expected cached re-scan < 20 ms, actual: " + elapsedMs + " ms");
    }
}
