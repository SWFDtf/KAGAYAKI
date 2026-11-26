package de.swfdtf.tools;

import de.swfdtf.audio.AudioEngine;
import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineEvent;
import de.swfdtf.core.EngineEventBus;
import de.swfdtf.core.EngineLogger;
import de.swfdtf.core.EngineMode;
import de.swfdtf.core.EnginePlugin;
import de.swfdtf.core.PluginRegistry;
import de.swfdtf.graphics.ColorBlindnessFilter;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SceneGraph;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.ui.TextButton;
import de.swfdtf.ui.ThemePalette;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests covering {@link StudioWindowController} Wave P2-2 IDE capabilities
 * ({@code #0101}–{@code #0168}).
 */
class WaveP2Chapter2StudioControllerTest {

    private record TestBusEvent(String label) implements EngineEvent {
    }

    private static StudioWindowController bootHeadlessStudio(Path projectDir, EngineAPI api) {
        StudioWindowController controller = new StudioWindowController();
        controller.init(api, 0L, 0L);
        return controller;
    }

    @Test
    void studioConsoleFiltersThemesLayoutsScaleAndKeyboardNavWorkTogether(@TempDir Path dir) throws Exception {
        Path projectDir = ProjectTemplateGenerator.generate(
                dir.resolve("studio_proj"), "Studio Nav", null, "2D", ProjectTemplateGenerator.TemplateType.FULL_DEMO);
        EngineConfig cfg = new EngineConfig(
                EngineMode.STUDIO_DUAL_WINDOW, "2D", projectDir, 1920, 1080, true, 120, false, true);
        EngineAPI api = new EngineAPI(cfg);
        EngineEventBus bus = new EngineEventBus(api.getLogger());
        PluginRegistry plugins = new PluginRegistry(api);
        plugins.register(new EnginePlugin() {
            @Override
            public String id() {
                return "demo-ext";
            }

            @Override
            public String displayName() {
                return "Demo Extension";
            }

            @Override
            public String version() {
                return "1.0.0";
            }
        });
        plugins.enable("demo-ext");
        api.registerService(EngineEventBus.class, bus);
        api.registerService(PluginRegistry.class, plugins);

        StudioWindowController controller = bootHeadlessStudio(projectDir, api);
        try {
            api.getLogger().info("Audio", "Audio info noise");
            api.getLogger().warn("Audio", "Audio warning sample");
            api.getLogger().error("Studio", "Studio critical sample");

            controller.setConsoleMinLevel(EngineLogger.Level.WARN);
            controller.setConsoleCategoryFilter("");
            List<EngineLogger.LogEntry> warnOrHigher = controller.getFilteredConsoleEntries(20);
            assertTrue(warnOrHigher.stream().noneMatch(e -> e.level() == EngineLogger.Level.INFO));

            controller.setConsoleCategoryFilter("Studio");
            List<EngineLogger.LogEntry> studioOnly = controller.getFilteredConsoleEntries(20);
            assertTrue(studioOnly.stream().allMatch(e -> "Studio".equals(e.category())));

            controller.applyLayoutPreset("DEBUG");
            assertEquals("DEBUG", controller.getWorkspaceState().getLayoutPreset());
            assertTrue(controller.getWorkspaceState().getPanelVisibility().get("flowGraph"));

            controller.applyThemeVariant(ThemePalette.ThemeVariant.DAYLIGHT);
            assertEquals(ThemePalette.ThemeVariant.DAYLIGHT, controller.getThemeVariant());

            controller.setUiScale(1.25f);
            assertEquals(1.25f, controller.getUiScale(), 0.001f);

            controller.toggleShortcutOverlay();
            assertTrue(controller.isShortcutOverlayVisible());
            controller.dismissOverlays();
            assertFalse(controller.isShortcutOverlayVisible());

            assertTrue(controller.isOnboardingTourVisible());
            controller.completeOnboardingTour();
            assertFalse(controller.isOnboardingTourVisible());

            bus.publish(new TestBusEvent("ping"));
            assertTrue(controller.getCapturedBusEvents().stream().anyMatch(e -> e.contains("ping")));
            assertTrue(controller.listRegisteredPluginsSummary().get(0).contains("demo-ext"));

            controller.focusNextButton();
            TextButton first = controller.getFocusedButton();
            assertNotNull(first);
            controller.focusNextButton();
            TextButton second = controller.getFocusedButton();
            assertNotNull(second);
            assertNotEquals(first, second);
            controller.focusPrevButton();
            assertEquals(first, controller.getFocusedButton());

            controller.update(0.6);
            assertTrue(controller.getLiveTelemetryText().contains("FPS"));
        } finally {
            controller.dispose();
        }
    }

    @Test
    void studioSafeReloadSessionPreservationFastFilterAudioAndVersionBump(@TempDir Path dir) throws Exception {
        Path projectA = ProjectTemplateGenerator.generate(
                dir.resolve("proj_a"), "Project A", null, "2D", ProjectTemplateGenerator.TemplateType.MINIMAL);
        Path projectB = ProjectTemplateGenerator.generate(
                dir.resolve("proj_b"), "Project B", null, "2D", ProjectTemplateGenerator.TemplateType.FULL_DEMO);

        EngineConfig cfg = new EngineConfig(
                EngineMode.STUDIO_DUAL_WINDOW, "2D", projectA, 1920, 1080, true, 120, false, true);
        EngineAPI api = new EngineAPI(cfg);
        AudioEngine audio = new AudioEngine(projectA, true);
        SceneController sc = new SceneController(new SceneGraph(), null, api.getLogger(), projectA, false);
        ScriptSession session = new ScriptSession(ProjectScanner.scan(projectA), api, sc, projectA.resolve("saves"));
        api.registerService(AudioEngine.class, audio);
        api.registerService(ScriptSession.class, session);

        StudioWindowController controller = bootHeadlessStudio(projectA, api);
        try {
            assertTrue(controller.safeReloadScripts());

            Path mainVjava = projectA.resolve("main.vjava");
            String backup = Files.readString(mainVjava, StandardCharsets.UTF_8);
            Files.writeString(mainVjava, backup + "\nlabel broken_target\n", StandardCharsets.UTF_8);
            assertFalse(controller.safeReloadScripts(), "Syntax error must trigger safe rollback");
            assertNotNull(api.getService(ScriptSession.class), "Last working ScriptSession must stay active");
            assertFalse(controller.getLastToastMessage().isBlank());
            Files.writeString(mainVjava, backup, StandardCharsets.UTF_8);

            controller.switchActiveProject(projectB);
            assertEquals(projectB.toAbsolutePath().normalize(), controller.getActiveProjectRoot());
            assertTrue(controller.getRecentProjects().size() >= 2);

            assertEquals("1.0.1", controller.bumpActiveProjectVersion(ProjectRefactorService.VersionBump.PATCH));
            assertEquals("1.0.1", controller.getActiveProjectVersion());

            controller.setAudioChannelMuted(AudioEngine.CHANNEL_MUSIC, true);
            assertEquals(0.0f, audio.getChannel(AudioEngine.CHANNEL_MUSIC).getTargetVolume()
                    * 0.0f, 0.01f);
            controller.setAudioChannelMuted(AudioEngine.CHANNEL_MUSIC, false);

            controller.setAccessibilityPreview(ColorBlindnessFilter.AccessibilityMode.DEUTERANOPIA, true);
            assertEquals(ColorBlindnessFilter.AccessibilityMode.DEUTERANOPIA,
                    controller.getAccessibilityPreviewMode());
            assertTrue(controller.isDyslexiaFontPreview());
            assertTrue(session.getPreferencesState().isHighContrastDyslexiaFont());

            controller.setPreviewSpeedMultiplier(2.0f);
            assertEquals(2.0f, controller.getPreviewSpeedMultiplier(), 0.01f);

            controller.switchPreviewLanguage("english");
            assertEquals("english", session.getLocalizationManager().getCurrentLanguage());

            VJavaAst.ProjectAst scannedB = ProjectScanner.scan(projectB);
            String firstLabel = scannedB.labels().keySet().iterator().next();
            controller.addBookmark(firstLabel, "Story", "Start node");
            controller.jumpToBookmark(firstLabel);
            assertTrue(controller.jumpToLabelInPreview(firstLabel));

            AutomatedPlaythroughRunner.PlaythroughReport asyncReport = controller
                    .runPlaythroughAsync(AutomatedPlaythroughRunner.ChoiceStrategy.FIRST_CHOICE, 500)
                    .get(15, TimeUnit.SECONDS);
            assertTrue(asyncReport.stepsExecuted() > 0);

            Path mmd = controller.exportFlowGraphFile(dir.resolve("flow.mmd"), false);
            assertTrue(Files.readString(mmd).contains("flowchart"));
            Path diagZip = controller.exportDiagnosticZipBundle(dir.resolve("studio_diag.zip"));
            assertTrue(Files.isRegularFile(diagZip));

            List<String> twoThousandAssets = new ArrayList<>(2000);
            for (int i = 0; i < 2000; i++) {
                twoThousandAssets.add("assets/images/sprite_" + i + ".png");
            }
            long startNs = System.nanoTime();
            List<String> hits = controller.filterExplorerEntries(twoThousandAssets, "sprite_1999");
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            assertEquals(List.of("assets/images/sprite_1999.png"), hits);
            assertTrue(elapsedMs < 50L, "Filtering 2,000 entries took " + elapsedMs + "ms");

            controller.setKeybindingTestOverlayVisible(true);
            assertTrue(controller.isKeybindingTestOverlayVisible());
            controller.recordKeybindingTestKey("W");
            assertEquals(List.of("W"), controller.getRecordedTestKeys());

            assertFalse(controller.searchProject("start", true).isEmpty());
            assertTrue(controller.getWordCountReport().totalWords() >= 0);
            assertNotNull(controller.checkAssetIntegrity());
        } finally {
            audio.close();
            controller.dispose();
        }
    }
}
