package de.swfdtf.tools;

import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineMode;
import de.swfdtf.graphics.RenderMode;
import de.swfdtf.graphics.RenderPipeline3DStub;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.ui.GameRuntimeCoordinator;
import de.swfdtf.ui.MainMenuScreen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndToEndEngineTest {

    @Test
    void fullDualWindowStudioStarterProjectLifecycleStandaloneExportAndDocsCheck(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("my_vn_project");
        Path distDir = tempDir.resolve("dist_out");

        // 1. Generate starter template & verify clean lint
        ProjectTemplateGenerator.generate(projectDir, "KAGAYAKI Demo", "2D");
        VJavaLinter.LintReport lint = VJavaLinter.lint(projectDir);
        assertTrue(lint.isClean(), "Starter template lint issues: " + lint.issues());

        // 2. Boot EngineApplication in STUDIO_DUAL_WINDOW headless mode (automatically loads GameRuntimeCoordinator & StudioWindowController)
        EngineConfig studioConfig = EngineConfig.builder()
                .engineMode(EngineMode.STUDIO_DUAL_WINDOW)
                .projectDir(projectDir)
                .headless(true)
                .smokeTest(true)
                .build();

        try (EngineApplication app = new EngineApplication(studioConfig)) {
            app.initialize();

            StudioWindowController studio = app.getApi().getService(StudioWindowController.class);
            assertNotNull(studio, "StudioWindowController should be registered in STUDIO_DUAL_WINDOW mode");
            assertNotNull(studio.getLastScannedAst());
            assertTrue(studio.getLastLintReport().isClean());

            GameRuntimeCoordinator game = assertInstanceOf(GameRuntimeCoordinator.class, app.getGameHost());
            ScriptSession session = game.getSession();
            assertNotNull(session);

            // Main menu starts visible in GameRuntimeCoordinator
            assertTrue(game.getMainMenuScreen().isVisible());

            // 3. Click "Start" from Main Menu -> enters Dialogue 1
            game.getScreenManager().hideScreen(MainMenuScreen.SCREEN_NAME);
            session.start();
            app.stepOnce(1.0 / 60.0);

            assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, session.getRuntime().getStatus());
            assertTrue(game.getAdvScreen().isVisible());
            assertEquals("Willkommen in der ersten Beispielszene, Kaito.{w=0.4} Bereit für den Übergang in Dialog 2?",
                    session.getRuntime().getActiveDialogue().interpolatedText());
            assertEquals(35, session.getStateStore().getInt("mut_punkte", 0));

            // 4. Advance past Kaito's line -> Menu -> Choose Branch 2 -> Dialogue 2 -> QuickSave & Rollback
            session.advance();
            assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, session.getRuntime().getStatus());
            assertEquals("Der Sternenkompass zeigt direkt hinüber zur Bibliothek.",
                    session.getRuntime().getActiveDialogue().interpolatedText());
            session.advance();
            assertEquals(ScriptRuntime.Status.WAITING_MENU, session.getRuntime().getStatus());
            session.choose(1); // umgebung_pruefen
            assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, session.getRuntime().getStatus());
            session.quickSave();

            session.advance(); // jump dialog_zwei
            assertEquals("dialog_zwei", session.getRuntime().getCurrentLabel());

            // Rollback back from Dialogue 2 to umgebung_pruefen
            assertTrue(session.rollback());
            assertEquals("umgebung_pruefen", session.getRuntime().getCurrentLabel());

            // Roll-forward back to Dialogue 2
            assertTrue(session.rollForward());
            assertEquals("dialog_zwei", session.getRuntime().getCurrentLabel());

            // Quick-Load back to umgebung_pruefen
            session.quickLoad();
            assertEquals("umgebung_pruefen", session.getRuntime().getCurrentLabel());

            // 5. Studio mode switching to 3D Stub works without crash
            RenderMode switched = studio.switchRenderMode(RenderMode.MODE_3D);
            assertEquals(RenderMode.MODE_3D, switched);
            assertInstanceOf(RenderPipeline3DStub.class, app.getActivePipeline());
            app.stepOnce(1.0 / 60.0);
            assertTrue(((RenderPipeline3DStub) app.getActivePipeline()).isWarningLogged());
            studio.switchRenderMode(RenderMode.MODE_2D);

            // 6. Export standalone game bundle via StudioWindowController
            StandaloneBuilder.BuildResult export = studio.exportStandalone(distDir, null);
            assertTrue(Files.isRegularFile(export.vpakFile()));
            assertTrue(Files.isRegularFile(export.launcherScript()));
            assertTrue(Files.isRegularFile(export.packagingDescriptor()));

            try (VpakArchiver.VpakArchive archive = VpakArchiver.open(export.vpakFile())) {
                assertTrue(archive.contains("project.vjava.json"));
                assertTrue(archive.contains("main.vjava"));
                assertTrue(archive.contains("assets/audio/nachtwind.wav"));
            }

            // 7. Trigger Live Reload & verify Studio refresh
            app.getApi().requestLiveReload();
            app.stepOnce(1.0 / 60.0);
            studio.refreshInspectors();
        }

        // 8. Verify Standalone Mode does NOT load StudioWindowController
        EngineConfig standaloneConfig = EngineConfig.builder()
                .engineMode(EngineMode.STANDALONE_GAME)
                .projectDir(projectDir)
                .headless(true)
                .smokeTest(true)
                .build();

        try (EngineApplication standaloneApp = new EngineApplication(standaloneConfig)) {
            standaloneApp.initialize();
            assertEquals(null, standaloneApp.getApi().getService(StudioWindowController.class));
            assertFalse(standaloneApp.getWindowManager().hasStudioWindow());
            assertNotNull(standaloneApp.getGameHost());
            standaloneApp.run();
        }
    }

    @Test
    void everyMainJavaPackageHasAgentContextDocumentation() {
        List<String> packages = List.of(
                "src/main/java/de/swfdtf",
                "src/main/java/de/swfdtf/core",
                "src/main/java/de/swfdtf/math",
                "src/main/java/de/swfdtf/graphics",
                "src/main/java/de/swfdtf/parser",
                "src/main/java/de/swfdtf/state",
                "src/main/java/de/swfdtf/audio",
                "src/main/java/de/swfdtf/ui",
                "src/main/java/de/swfdtf/tools",
                "src/main/java/de/swfdtf/git"
        );
        for (String pkg : packages) {
            assertTrue(Files.isRegularFile(Path.of(pkg, "AGENT_CONTEXT.md")),
                    "Missing AGENT_CONTEXT.md in " + pkg);
        }
    }
}
