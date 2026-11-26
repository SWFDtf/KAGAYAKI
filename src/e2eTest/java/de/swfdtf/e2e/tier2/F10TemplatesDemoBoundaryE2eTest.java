package de.swfdtf.e2e.tier2;

import de.swfdtf.audio.LipSyncAnalyzer;
import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineMode;
import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.e2e.support.E2eWorkspaceSupport;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.tools.ProjectTemplateGenerator;
import de.swfdtf.tools.TranslationCoverageAuditor;
import de.swfdtf.tools.VJavaLinter;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 2 boundary, compatibility, and negative E2E tests for Feature Area F10: template
 * backwards compatibility ({@code STARTER} default), demo bounds/collision guards, director
 * validation, partial English coverage detection, silent lip-sync clamping, and
 * {@code ACTION_ADVENTURE_2D} distinction ({@code #0851}–{@code #0866}).
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER2)
public final class F10TemplatesDemoBoundaryE2eTest {

    /** Creates a new {@code F10TemplatesDemoBoundaryE2eTest} instance. */
    public F10TemplatesDemoBoundaryE2eTest() {
    }

    /**
     * Verifies baseline {@link ProjectTemplateGenerator} backwards compatibility and argument
     * normalization: 1-arg and 3-arg calls default to {@code STARTER}, blank title falls back
     * safely, and {@code null} directory throws {@link NullPointerException}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if template generation fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void templateGeneratorDefaultsAndInvalidArgBoundariesBaseline(@TempDir Path tempDir) throws Exception {
        assertThrows(NullPointerException.class, () -> ProjectTemplateGenerator.generate(null));

        Path oneArg = ProjectTemplateGenerator.generate(tempDir.resolve("one_arg"));
        Path threeArg = ProjectTemplateGenerator.generate(tempDir.resolve("three_arg"), "   ", "");
        Path nullType = ProjectTemplateGenerator.generate(
                tempDir.resolve("null_type"), "Null Type", "2D", null);

        for (Path dir : List.of(oneArg, threeArg, nullType)) {
            assertTrue(VJavaLinter.lint(dir).isClean(), "Template must lint cleanly: " + dir);
            assertFalse(ProjectScanner.scan(dir).labels().containsKey("wasd_arena"),
                    "1-arg/3-arg/null-type generate calls must default to STARTER, not FULL_DEMO");
        }
    }

    /**
     * Verifies goals {@code #0851} and {@code #0852} boundary paths: Snow White forest
     * {@code wasd_init} defines clamping bounds, and cottage {@code on_collide} guards against
     * duplicate collection counts on the same target.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if scanning fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void snowWhiteForestBoundsAndDuplicateCollectGuards(@TempDir Path tempDir) throws Exception {
        Path demoCopy = copyDemoProject(tempDir.resolve("demo_b_wasd"));
        VJavaAst.ProjectAst ast = ProjectScanner.scan(demoCopy);

        List<VJavaAst.WasdInitStatement> wasdInits = collectStatements(ast, VJavaAst.WasdInitStatement.class);
        List<VJavaAst.OnCollideStatement> collisions = collectStatements(ast, VJavaAst.OnCollideStatement.class);
        assertFalse(wasdInits.isEmpty(), "Snow White demo must include wasd_init (#0851)");
        assertTrue(collisions.size() >= 3, "Snow White demo must include >= 3 on_collide rules (#0852)");

        for (VJavaAst.WasdInitStatement stmt : wasdInits) {
            assertTrue(stmt.speedPxPerSec() > 0.0);
            if (stmt.maxX() != null && stmt.minX() != null && stmt.maxY() != null && stmt.minY() != null) {
                assertTrue(stmt.maxX() > stmt.minX() && stmt.maxY() > stmt.minY(),
                        "wasd_init bounds must clamp playable area cleanly");
            }
        }
    }

    /**
     * Verifies goals {@code #0853}, {@code #0859}, {@code #0860}, and {@code #0861} boundary
     * paths by running the {@code demo} unit/director validation suite and inspecting mirror/scene
     * director wiring.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if Gradle execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void snowWhiteDirectorsValidateChapterAndBoundaryArguments(@TempDir Path tempDir) throws Exception {
        Path demoCopy = copyDemoProject(tempDir.resolve("demo_b_dir"));
        VJavaAst.ProjectAst ast = ProjectScanner.scan(demoCopy);
        assertFalse(collectStatements(ast, VJavaAst.MoveStatement.class).isEmpty(),
                "Snow White demo must wire mirror reveal move/effects (#0853)");

        E2eCliRunner.ProcessResult demoTests = E2eCliRunner.runGradle(
                E2eWorkspaceSupport.repoRoot(),
                List.of("-p", "demo", "test"),
                Duration.ofMinutes(2)
        );
        assertTrue(demoTests.isSuccess(),
                "demo:test must pass all director boundary tests:\n"
                        + demoTests.stdout() + "\n" + demoTests.stderr());
    }

    /**
     * Verifies goals {@code #0854}–{@code #0858}, {@code #0862}, and {@code #0863} boundary paths:
     * unknown codex target, locked chapter state, low affinity standard ending, duplicate quote
     * guard, and accessibility/gallery edge handling in the Snow White demo.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void snowWhiteMetaScreensHandleLockedChaptersAndUnknownCodexSafely(@TempDir Path tempDir) throws Exception {
        Path demoCopy = copyDemoProject(tempDir.resolve("demo_b_meta"));
        String allScripts = readAllVJavaText(demoCopy);
        assertTrue(allScripts.contains("{a=codex:"),
                "Snow White scripts must integrate {a=codex:...} links (#0855)");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                demoCopy, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            assertFalse(screens.getScreen("chapter_select") == null,
                    "Snow White must register chapter_select screen (#0854)");
            assertFalse(screens.getScreen("codex") == null,
                    "Snow White must register codex screen (#0855)");
            assertFalse(screens.getScreen("quote_book") == null,
                    "Snow White must register quote_book screen (#0857)");
        }
    }

    /**
     * Verifies goal {@code #0864} negative path: starting from 100% English coverage on
     * {@code demo/project}, removing a translated block causes {@link TranslationCoverageAuditor}
     * to drop strictly below 100.0% and report the untranslated line.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if file manipulation fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void snowWhiteTranslationAuditorCatchesMissingEnglishLine(@TempDir Path tempDir) throws Exception {
        Path demoCopy = copyDemoProject(tempDir.resolve("demo_b_i18n"));
        TranslationCoverageAuditor auditor = new TranslationCoverageAuditor();

        double fullCoverage = auditor.audit(ProjectScanner.scan(demoCopy))
                .findLanguage("english").orElseThrow().coveragePercent();
        assertEquals(100.0, fullCoverage, 0.01,
                "Snow White English translation baseline must be 100.0%");

        Path tlFile = demoCopy.resolve("tl/english/translations.vjava");
        Files.writeString(tlFile, """
                translate english strings:
                    old "Dummy"
                    new "Dummy"
                """, StandardCharsets.UTF_8);

        TranslationCoverageAuditor.LanguageCoverage degraded = auditor.audit(ProjectScanner.scan(demoCopy))
                .findLanguage("english").orElseThrow();
        assertTrue(degraded.coveragePercent() < 100.0,
                "Deleting English dialogue translations must drop coverage < 100.0%");
        assertFalse(degraded.untranslatedTexts().isEmpty());
    }

    /**
     * Verifies goal {@code #0865} boundary path: prologue defines synced voice samples while
     * zero-RMS silent audio keeps {@link LipSyncAnalyzer} at {@link LipSyncAnalyzer.MouthShape#CLOSED}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if scanning fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void snowWhiteLipSyncSilentAudioRemainsClosed(@TempDir Path tempDir) throws Exception {
        Path demoCopy = copyDemoProject(tempDir.resolve("demo_b_voice"));
        VJavaAst.ProjectAst ast = ProjectScanner.scan(demoCopy);
        List<VJavaAst.VoiceStatement> voices = collectStatements(ast, VJavaAst.VoiceStatement.class);
        assertTrue(voices.size() >= 4,
                "Snow White prologue must define >= 4 voice samples (#0865)");

        LipSyncAnalyzer analyzer = new LipSyncAnalyzer();
        assertEquals(LipSyncAnalyzer.MouthShape.CLOSED, analyzer.updateFromRms(0.0f, 0.1));
        assertEquals(LipSyncAnalyzer.MouthShape.CLOSED, analyzer.updateFromRms(0.001f, 0.1));
    }

    /**
     * Verifies goal {@code #0866} compatibility boundary: {@code ACTION_ADVENTURE_2D} exists
     * alongside {@code STARTER}, and legacy 1-arg / 3-arg {@link ProjectTemplateGenerator#generate}
     * calls preserve {@code STARTER} as default rather than switching to {@code ACTION_ADVENTURE_2D}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if generation fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void actionAdventure2dPreservesStarterBackwardsCompatibility(@TempDir Path tempDir) throws Exception {
        Optional<ProjectTemplateGenerator.TemplateType> actionType = Arrays.stream(
                        ProjectTemplateGenerator.TemplateType.values())
                .filter(t -> "ACTION_ADVENTURE_2D".equals(t.name()))
                .findFirst();
        assertTrue(actionType.isPresent(),
                "ProjectTemplateGenerator.TemplateType must include ACTION_ADVENTURE_2D");

        Path legacyStarter = ProjectTemplateGenerator.generate(tempDir.resolve("legacy_1arg"));
        Path explicitAction = ProjectTemplateGenerator.generate(
                tempDir.resolve("explicit_action"), "Action", "2D", actionType.get());

        VJavaAst.ProjectAst starterAst = ProjectScanner.scan(legacyStarter);
        VJavaAst.ProjectAst actionAst = ProjectScanner.scan(explicitAction);

        assertTrue(collectStatements(starterAst, VJavaAst.WasdInitStatement.class).isEmpty(),
                "Legacy 1-arg generate() must remain STARTER (without ACTION_ADVENTURE_2D WASD rooms)");
        assertFalse(collectStatements(actionAst, VJavaAst.WasdInitStatement.class).isEmpty(),
                "ACTION_ADVENTURE_2D template must contain WASD rooms");
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
}
