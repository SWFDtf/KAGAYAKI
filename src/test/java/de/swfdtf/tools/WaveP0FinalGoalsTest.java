package de.swfdtf.tools;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineMode;
import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.ui.GameRuntimeCoordinator;
import de.swfdtf.ui.MainMenuScreen;
import de.swfdtf.ui.Screen;
import de.swfdtf.ui.ScreenManager;
import de.swfdtf.ui.TextButton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance tests for final remaining P0 roadmap goals: {@code #0086}, {@code #0087}, and {@code #0341}.
 */
class WaveP0FinalGoalsTest {

    @Test
    void studioWorkspaceStatePersistsOnDisposeAndReloadsOnRestart(@TempDir Path tempDir) throws Exception {
        Path proj1 = tempDir.resolve("proj_one");
        Path proj2 = tempDir.resolve("proj_two");
        ProjectTemplateGenerator.generate(
                proj1, "Proj One", "2D", ProjectTemplateGenerator.TemplateType.MINIMAL);
        ProjectTemplateGenerator.generate(
                proj2, "Proj Two", "2D", ProjectTemplateGenerator.TemplateType.STARTER);

        Path studioJson = tempDir.resolve(".kagayaki/studio.json");
        assertTrue(StudioWorkspaceState.defaultPersistencePath()
                .toString()
                .replace('\\', '/')
                .endsWith(".kagayaki/studio.json"));

        StudioWindowController firstRun = new StudioWindowController();
        firstRun.setWorkspaceStateFile(studioJson);
        EngineAPI firstApi = new EngineAPI(EngineConfig.builder().headless(true).projectDir(proj1).build());
        firstRun.init(firstApi, 0L, 0L);
        firstRun.openProject(proj2);
        firstRun.setSelectedTemplateType(ProjectTemplateGenerator.TemplateType.HYBRID_3D_MYSTERY);
        firstRun.dispose();

        StudioWindowController restartRun = new StudioWindowController();
        restartRun.setWorkspaceStateFile(studioJson);
        EngineAPI secondApi = new EngineAPI(EngineConfig.builder().headless(true).projectDir(proj2).build());
        restartRun.init(secondApi, 0L, 0L);

        assertEquals(
                firstRun.getWorkspaceState().getRecentProjectPaths(),
                restartRun.getWorkspaceState().getRecentProjectPaths()
        );
        assertEquals(
                ProjectTemplateGenerator.TemplateType.HYBRID_3D_MYSTERY,
                restartRun.getSelectedTemplateType()
        );
        assertEquals(
                java.util.Optional.of(proj2.toAbsolutePath().normalize().toString()),
                restartRun.getWorkspaceState().getLastProjectPath()
        );
        restartRun.dispose();
    }

    @Test
    void studioRecentProjectsButtonsAreClickableAndReloadPreview(@TempDir Path tempDir) throws Exception {
        Path proj1 = tempDir.resolve("alpha_proj");
        Path proj2 = tempDir.resolve("beta_proj");
        ProjectTemplateGenerator.generate(
                proj1, "Alpha", "2D", ProjectTemplateGenerator.TemplateType.MINIMAL);
        ProjectTemplateGenerator.generate(
                proj2, "Beta", "2D", ProjectTemplateGenerator.TemplateType.FULL_DEMO);

        StudioWindowController controller = new StudioWindowController();
        controller.setWorkspaceStateFile(tempDir.resolve("studio.json"));
        EngineAPI api = new EngineAPI(EngineConfig.builder().headless(true).projectDir(proj1).build());
        controller.init(api, 0L, 0L);

        // Open proj2 so recent list holds [proj2, proj1] -> index 1 is proj1 (2nd recent entry)
        assertTrue(controller.openProject(proj2));
        List<TextButton> buttons = controller.getRecentProjectButtons();
        assertEquals(2, buttons.size());
        int reloadsBeforeClick = controller.getLivePreviewReloadCount();

        // Click 2nd entry in Recent Projects list -> switches activeProjectRoot back to proj1 & reloads Window B
        assertTrue(controller.openRecentProject(1));
        assertEquals(proj1.toAbsolutePath().normalize(), controller.getActiveProjectRoot());
        assertEquals(reloadsBeforeClick + 1, controller.getLivePreviewReloadCount());
        assertTrue(api.consumeLiveReloadRequested());
        controller.dispose();
    }

    @Test
    void gameRuntimeTogglesBetweenBuiltinMainMenuAndCustomTemplateMainMenu(@TempDir Path tempDir)
            throws Exception {
        Path projectDir = tempDir.resolve("full_demo_proj");
        ProjectTemplateGenerator.generate(
                projectDir, "Full Demo", "2D", ProjectTemplateGenerator.TemplateType.FULL_DEMO);

        ProjectDescriptor descriptor = ProjectDescriptor.load(projectDir);
        descriptor.setUseCustomMainMenuScreen(true);
        ProjectDescriptor.save(projectDir, descriptor);

        EngineConfig cfg = EngineConfig.builder()
                .engineMode(EngineMode.STANDALONE_GAME)
                .projectDir(projectDir)
                .headless(true)
                .build();
        try (EngineApplication app = new EngineApplication(cfg, null, new GameRuntimeCoordinator(true))) {
            app.initialize();
            GameRuntimeCoordinator coordinator = (GameRuntimeCoordinator) app.getGameHost();

            ScreenManager sm = coordinator.getScreenManager();
            Screen builtin = sm.getScreen(MainMenuScreen.SCREEN_NAME);
            Screen custom = sm.getScreen("template_main_menu");

            // Mode 1: custom .vjava template_main_menu leads without built-in overlap
            assertEquals("template_main_menu", coordinator.resolvePrimaryMainMenuScreenName());
            assertTrue(custom.isVisible());
            assertFalse(builtin.isVisible());

            // Mode 2: switch cleanly back to built-in Dark-Slate Main Menu without overlap
            coordinator.setUseCustomMainMenuScreen(false);
            assertEquals(MainMenuScreen.SCREEN_NAME, coordinator.resolvePrimaryMainMenuScreenName());
            assertTrue(builtin.isVisible());
            assertFalse(custom.isVisible());
        }
    }
}
