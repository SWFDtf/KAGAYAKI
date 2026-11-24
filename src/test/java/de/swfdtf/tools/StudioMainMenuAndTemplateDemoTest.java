package de.swfdtf.tools;

import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineMode;
import de.swfdtf.core.EngineVersion;
import de.swfdtf.graphics.ObjectMotionController;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.VJavaAst;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link EngineVersion}, upgraded {@link ProjectTemplateGenerator}
 * ({@code FULL_DEMO} &amp; {@code MINIMAL}), and {@link StudioWindowController}'s Main Menu Hub
 * with version display and "Neues Projekt" workflow.
 */
class StudioMainMenuAndTemplateDemoTest {

    @Test
    void engineVersionProvidesNonEmptyMetadataAndBanner() {
        assertEquals("KAGAYAKI", EngineVersion.ENGINE_NAME);
        assertFalse(EngineVersion.VERSION.isBlank());
        assertFalse(EngineVersion.VJAVA_SPEC_VERSION.isBlank());
        assertTrue(EngineVersion.formatBanner().contains(EngineVersion.VERSION));
        assertTrue(EngineVersion.formatProjectHeader("Sternenkompass", "2.0.0").contains("Sternenkompass v2.0.0"));
    }

    @Test
    void fullDemoTemplateIncludesMainMenuSaveLoadSettingsAndPlayableWasdArena(@TempDir Path tempDir) throws Exception {
        Path demoDir = tempDir.resolve("demo_template");
        ProjectTemplateGenerator.generate(
                demoDir,
                "Demo Vorlage",
                "2D",
                ProjectTemplateGenerator.TemplateType.FULL_DEMO
        );

        VJavaLinter.LintReport lint = VJavaLinter.lint(demoDir);
        assertTrue(lint.isClean(), () -> "FULL_DEMO lint issues: " + lint.issues());

        VJavaAst.ProjectAst ast = ProjectScanner.scan(demoDir);
        assertTrue(ast.screens().containsKey("template_main_menu"));
        assertTrue(ast.screens().containsKey("template_save_screen"));
        assertTrue(ast.screens().containsKey("template_load_screen"));
        assertTrue(ast.screens().containsKey("template_preferences_screen"));
        assertTrue(ast.labels().containsKey("wasd_arena"));
        assertTrue(ast.labels().containsKey("arena_victory"));

        // Verify WASD Arena inside FULL_DEMO runs cleanly to victory via WASD + collision
        ScriptRuntime runtime = new ScriptRuntime(ast);
        runtime.start();
        runtime.advance(); // Skip opening line
        runtime.advance(); // Reach menu
        assertEquals(ScriptRuntime.Status.WAITING_MENU, runtime.getStatus());
        runtime.choose(2); // Enter interactive WASD Arena

        assertEquals("wasd_arena", runtime.getCurrentLabel());
        SceneController sc = runtime.getSceneController();
        assertTrue(sc.getMotionController().hasActiveWasd());
        assertTrue(sc.getMotionController().hasActivePhysics());

        for (int i = 0; i < 120 && !"arena_victory".equals(runtime.getCurrentLabel()); i++) {
            sc.updateMotion(0.05, new ObjectMotionController.MotionInput(false, false, false, true, false));
        }
        assertEquals("arena_victory", runtime.getCurrentLabel());
        assertFalse(sc.getMotionController().hasActiveWasd());
    }

    @Test
    void minimalTemplateGeneratesLintCleanSkeleton(@TempDir Path tempDir) throws Exception {
        Path minDir = tempDir.resolve("minimal_template");
        ProjectTemplateGenerator.generate(
                minDir,
                "Minimal Projekt",
                "2D",
                ProjectTemplateGenerator.TemplateType.MINIMAL
        );

        assertTrue(Files.isRegularFile(minDir.resolve("main.vjava")));
        assertTrue(Files.isRegularFile(minDir.resolve("ui/screens.vjava")));
        VJavaLinter.LintReport lint = VJavaLinter.lint(minDir);
        assertTrue(lint.isClean(), () -> "MINIMAL lint issues: " + lint.issues());

        AutomatedPlaythroughRunner.PlaythroughReport run = new AutomatedPlaythroughRunner().run(
                ProjectScanner.scan(minDir),
                AutomatedPlaythroughRunner.ChoiceStrategy.FIRST_CHOICE,
                50
        );
        assertTrue(run.endedCleanly());
    }

    @Test
    void studioStartsAtMainMenuShowsVersionAndCreatesNewProjectsFromTemplates(@TempDir Path tempDir) throws Exception {
        Path initialDir = tempDir.resolve("initial_project");
        ProjectTemplateGenerator.generate(initialDir, "Initial VN", "2D");

        EngineConfig config = EngineConfig.builder()
                .engineMode(EngineMode.STUDIO_DUAL_WINDOW)
                .projectDir(initialDir)
                .headless(true)
                .smokeTest(true)
                .build();

        try (EngineApplication app = new EngineApplication(config)) {
            app.initialize();
            StudioWindowController studio = app.getApi().getService(StudioWindowController.class);
            assertNotNull(studio);

            // Starts in Main Menu / Project Hub with engine & project version
            assertEquals(StudioWindowController.StudioView.MAIN_MENU, studio.getActiveView());
            assertTrue(studio.getStudioScreenManager()
                    .getScreen(StudioWindowController.MAIN_MENU_SCREEN_NAME)
                    .isVisible());
            assertTrue(studio.getEngineVersionBanner().contains(EngineVersion.VERSION));
            assertEquals(EngineVersion.VERSION, studio.getActiveProjectVersion());
            assertEquals(ProjectTemplateGenerator.TemplateType.FULL_DEMO, studio.getSelectedTemplateType());

            // Switch to Minimal & create a new project via "Neues Projekt" flow
            studio.setSelectedTemplateType(ProjectTemplateGenerator.TemplateType.MINIMAL);
            Path newMinimalDir = tempDir.resolve("created_minimal");
            studio.createNewProject(newMinimalDir, "Mein Minimal Spiel", ProjectTemplateGenerator.TemplateType.MINIMAL);

            assertEquals(newMinimalDir.toAbsolutePath().normalize(), studio.getActiveProjectRoot());
            assertEquals(StudioWindowController.StudioView.WORKSPACE, studio.getActiveView());
            assertTrue(studio.getLastLintReport().isClean());
            assertTrue(studio.getRecentProjects().contains(newMinimalDir.toAbsolutePath().normalize().toString()));

            // Switch back to Studio Main Menu and create a FULL_DEMO project
            studio.showMainMenu();
            assertEquals(StudioWindowController.StudioView.MAIN_MENU, studio.getActiveView());

            Path newDemoDir = tempDir.resolve("created_full_demo");
            studio.createNewProject(newDemoDir, "Mein Demo Spiel", ProjectTemplateGenerator.TemplateType.FULL_DEMO);
            assertTrue(Files.isRegularFile(newDemoDir.resolve("assets/images/sprites/player.png")));
            assertTrue(studio.getLastLintReport().isClean());
            assertEquals("Mein Demo Spiel", app.getApi().getProjectDescriptor().getTitle());
        }
    }
}
