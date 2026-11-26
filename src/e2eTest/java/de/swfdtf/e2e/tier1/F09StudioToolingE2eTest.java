package de.swfdtf.e2e.tier1;

import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineMode;
import de.swfdtf.core.InputManager;
import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2eFixtureFactory;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.e2e.support.E2eWorkspaceSupport;
import de.swfdtf.graphics.RenderMode;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.parser.VariableWatchpointManager;
import de.swfdtf.parser.WatchpointEvent;
import de.swfdtf.state.SimpleStateStore;
import de.swfdtf.tools.AssetMetadataInspector;
import de.swfdtf.tools.DeveloperInspectors;
import de.swfdtf.tools.LiveDirectorOverlay;
import de.swfdtf.tools.ProjectTemplateGenerator;
import de.swfdtf.tools.ScriptFlowGraphExporter;
import de.swfdtf.tools.StudioCommandPalette;
import de.swfdtf.tools.StudioWindowController;
import de.swfdtf.tools.UnusedAssetDetector;
import de.swfdtf.tools.VJavaLinter;
import de.swfdtf.tools.VJavaLinterAutoFixer;
import de.swfdtf.ui.MainMenuScreen;
import de.swfdtf.ui.Screen;
import de.swfdtf.ui.ScreenManager;
import de.swfdtf.ui.TextButton;
import de.swfdtf.ui.TextInput;
import de.swfdtf.ui.TextWidget;
import de.swfdtf.ui.UiNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 1 happy-path E2E tests for Feature Area F9: Studio project dialog, embedded code editor,
 * syntax coloring, click-to-line &amp; Auto-Fix, flow-graph viewer, watchpoints, step debugger,
 * live variable editing, sprite drag-and-drop, asset gallery, cleanup wizard, and command palette
 * ({@code #0088}–{@code #0100}).
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER1)
public final class F09StudioToolingE2eTest {

    private static final double FIXED_DT = 1.0 / 60.0;

    /** Creates a new {@code F09StudioToolingE2eTest} instance. */
    public F09StudioToolingE2eTest() {
    }

    /**
     * Verifies baseline Studio controller headless initialization, view switching, backend
     * analyzers ({@link ScriptFlowGraphExporter}, {@link UnusedAssetDetector},
     * {@link AssetMetadataInspector}, {@link VariableWatchpointManager}, {@link StudioCommandPalette},
     * {@link VJavaLinterAutoFixer}), and {@link LiveDirectorOverlay#updateShowLineInScript}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void studioHeadlessControllerAndBackendToolsBaseline(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF09AuthoringProject(tempDir.resolve("f09_base"));

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STUDIO_DUAL_WINDOW, false)) {
            app.initialize();
            app.stepOnce(FIXED_DT);

            StudioWindowController studio = app.getApi().getService(StudioWindowController.class);
            assertNotNull(studio);
            assertEquals(StudioWindowController.StudioView.MAIN_MENU, studio.getActiveView());

            studio.showWorkspace();
            assertEquals(StudioWindowController.StudioView.WORKSPACE, studio.getActiveView());
            VJavaLinter.LintReport baselineLint = studio.runLint();
            assertFalse(baselineLint.hasErrors(),
                    "Baseline authoring project must have 0 lint errors: " + baselineLint.issues());
            assertTrue(
                    baselineLint.issues().stream().allMatch(i -> "UNREACHABLE_LABEL".equals(i.code())),
                    "Only intentional UNREACHABLE_LABEL warning is expected: " + baselineLint.issues()
            );

            assertEquals(RenderMode.MODE_3D, studio.switchRenderMode(RenderMode.MODE_3D));
            assertEquals(RenderMode.MODE_2D, studio.switchRenderMode(RenderMode.MODE_2D));

            VJavaAst.ProjectAst ast = ProjectScanner.scan(projectDir);
            ScriptFlowGraphExporter exporter = new ScriptFlowGraphExporter();
            ScriptFlowGraphExporter.FlowGraph graph = exporter.buildGraph(ast);
            assertTrue(graph.labels().contains("chapter_two"));
            assertTrue(graph.unreachableLabels().contains("unreachable_secret"));
            assertTrue(exporter.toMermaid(graph).contains("chapter_two"));
            assertTrue(exporter.toDot(graph).contains("chapter_two"));

            UnusedAssetDetector.AssetUsageReport usage = new UnusedAssetDetector().analyze(ast);
            assertTrue(usage.unusedAssets().contains("assets/images/unused_prop.png"));
            assertTrue(usage.isClean());

            AssetMetadataInspector inspector = new AssetMetadataInspector();
            AssetMetadataInspector.AssetMetadata pngMeta =
                    inspector.inspectAsset(projectDir, "assets/images/bg/bg_main.png").orElseThrow();
            assertEquals(64, pngMeta.widthPx().orElseThrow());
            assertEquals(32, pngMeta.heightPx().orElseThrow());

            AssetMetadataInspector.AssetMetadata wavMeta =
                    inspector.inspectAsset(projectDir, "assets/audio/ping.wav").orElseThrow();
            assertEquals(22_050, wavMeta.sampleRateHz().orElseThrow());
            assertTrue(wavMeta.durationSeconds().orElseThrow() > 0.05);

            SimpleStateStore store = new SimpleStateStore();
            VariableWatchpointManager wp = new VariableWatchpointManager();
            List<WatchpointEvent> events = new ArrayList<>();
            wp.watchKey("score", events::add);
            store.set("score", 10);
            wp.checkAll(store, "start");
            store.set("score", 25);
            wp.checkAll(store, "helper");
            assertEquals(2, events.size());
            assertNull(events.get(0).previousValue());
            assertEquals(10, events.get(0).newValue());
            assertEquals(10, events.get(1).previousValue());
            assertEquals(25, events.get(1).newValue());

            StudioCommandPalette palette = new StudioCommandPalette();
            AtomicBoolean ran = new AtomicBoolean(false);
            palette.register(new StudioCommandPalette.PaletteCommand(
                    "lint.run", "Run Lint", "Analysis", "Ctrl+L", () -> ran.set(true)));
            assertEquals("lint.run", palette.search("lint").get(0).id());
            assertTrue(palette.executeById("lint.run"));
            assertTrue(ran.get());
            assertEquals(List.of("lint.run"), palette.getRecentCommandIds());
        }
    }

    /**
     * Verifies goal {@code #0088}: clicking "Neues Projekt" opens a Studio project creation
     * dialog accepting custom title, target path, author, and {@code RenderMode} ({@code 2D}/{@code 3D}).
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void studioNewProjectDialogPersistsCustomTitlePathAuthorAndRenderMode(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF09AuthoringProject(tempDir.resolve("f09_hub"));
        Path customOut = tempDir.resolve("custom_studio_game");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STUDIO_DUAL_WINDOW, false)) {
            app.initialize();
            app.stepOnce(FIXED_DT);

            StudioWindowController studio = app.getApi().getService(StudioWindowController.class);
            ScreenManager studioScreens = studio.getStudioScreenManager();
            Screen mainMenu = studioScreens.getScreen(StudioWindowController.MAIN_MENU_SCREEN_NAME);
            TextButton newProjectBtn = findButtonContaining(mainMenu.getRoot(), "neues projekt");
            assertNotNull(newProjectBtn);

            clickNode(studioScreens, newProjectBtn);
            app.stepOnce(FIXED_DT);

            List<TextInput> dialogInputs = collectVisibleInputs(studioScreens);
            assertTrue(dialogInputs.size() >= 3,
                    "New Project dialog must present editable TextInputs for title, path, and author");

            dialogInputs.get(0).setText("Custom Studio Quest");
            dialogInputs.get(1).setText(customOut.toString());
            dialogInputs.get(2).setText("Jane Studio Author");

            TextButton createConfirmBtn = findVisibleButtonContaining(studioScreens, "erstellen");
            if (createConfirmBtn == null) {
                createConfirmBtn = findVisibleButtonContaining(studioScreens, "create");
            }
            assertNotNull(createConfirmBtn, "New Project dialog must provide a Create/Erstellen confirm button");
            clickNode(studioScreens, createConfirmBtn);
            app.stepOnce(FIXED_DT);

            Path descriptorPath = customOut.resolve("project.vjava.json");
            assertTrue(Files.isRegularFile(descriptorPath), "Dialog must create project.vjava.json at custom path");
            String json = Files.readString(descriptorPath, StandardCharsets.UTF_8);
            assertTrue(json.contains("Custom Studio Quest"));
            assertTrue(json.contains("Jane Studio Author"));
        }
    }

    /**
     * Verifies goals {@code #0089} and {@code #0090}: Studio embeds an editable {@code .vjava}
     * code editor with {@code Ctrl+S} save/lint/live-reload and fast ({@code < 2 ms} / 1,000 lines)
     * syntax coloring.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void studioEmbeddedCodeEditorSaveAndFastSyntaxHighlighting(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF09AuthoringProject(tempDir.resolve("f09_editor"));

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STUDIO_DUAL_WINDOW, false)) {
            app.initialize();
            StudioWindowController studio = app.getApi().getService(StudioWindowController.class);
            studio.showWorkspace();
            app.stepOnce(FIXED_DT);

            ScreenManager studioScreens = studio.getStudioScreenManager();
            List<TextButton> fileButtons = collectNodes(
                    studioScreens.getScreen(StudioWindowController.WORKSPACE_SCREEN_NAME).getRoot(),
                    TextButton.class
            ).stream().filter(b -> b.getText().contains("main.vjava")).toList();
            assertFalse(fileButtons.isEmpty(),
                    "Studio Project Explorer must provide clickable .vjava file entries");

            clickNode(studioScreens, fileButtons.get(0));
            app.stepOnce(FIXED_DT);

            List<TextInput> editors = collectVisibleInputs(studioScreens);
            assertFalse(editors.isEmpty(), "Clicking main.vjava must open an editable code editor field");

            String original = Files.readString(projectDir.resolve("main.vjava"), StandardCharsets.UTF_8);
            String edited = original.replace("Startszene geladen.", "Startszene im Editor editiert.");
            editors.get(0).setText(edited);

            InputManager studioInput = studio.getStudioInput();
            studioInput.onKey(GLFW.GLFW_KEY_LEFT_CONTROL, 0, GLFW.GLFW_PRESS, 0);
            studioInput.onKey(GLFW.GLFW_KEY_S, 0, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_CONTROL);
            studio.update(FIXED_DT);
            studioInput.onKey(GLFW.GLFW_KEY_S, 0, GLFW.GLFW_RELEASE, 0);
            studioInput.onKey(GLFW.GLFW_KEY_LEFT_CONTROL, 0, GLFW.GLFW_RELEASE, 0);
            app.stepOnce(FIXED_DT);

            assertTrue(Files.readString(projectDir.resolve("main.vjava"), StandardCharsets.UTF_8)
                            .contains("Startszene im Editor editiert."),
                    "Ctrl+S in Studio editor must write updated script to disk");

            // #0090: Syntax coloring benchmark (< 2 ms per 1,000 lines)
            Class<?> highlighterCls = findPublicClass(
                    "de.swfdtf.tools.VJavaSyntaxHighlighter",
                    "de.swfdtf.tools.VJavaSyntaxColorizer"
            );
            assertNotNull(highlighterCls, "Studio .vjava syntax highlighter class must exist");
            StringBuilder script1000 = new StringBuilder();
            for (int i = 0; i < 250; i++) {
                script1000.append("label l_").append(i).append(":\n")
                        .append("    $ x_").append(i).append(" = \"text\" // comment\n")
                        .append("    :: block @->{ int a = ").append(i).append("; }<<\n")
                        .append("    return\n");
            }
            String bulk = script1000.toString();
            Method highlightMethod = findHighlightMethod(highlighterCls);
            assertNotNull(highlightMethod, "Highlighter must expose public tokenize/highlight method");
            Object instance = highlighterCls.getConstructor().newInstance();

            // Warmup + timed run
            for (int w = 0; w < 5; w++) {
                highlightMethod.invoke(instance, bulk);
            }
            long startNanos = System.nanoTime();
            Object runs = highlightMethod.invoke(instance, bulk);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            assertNotNull(runs);
            assertTrue(elapsedMs < 2L,
                    "1,000-line syntax highlighting must complete in < 2 ms, took " + elapsedMs + " ms");
        }
    }

    /**
     * Verifies goals {@code #0091} and {@code #0092}: clicking a {@link VJavaLinter.LintIssue}
     * jumps to its line in the editor, and the one-click Auto-Fix button repairs fixable issues.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void studioLinterClickToLineAndOneClickAutoFix(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f09_broken");
        E2eWorkspaceSupport.copyResourceTree("e2e/f09/studio_broken_lint", projectDir);
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/bg/bg_main.png"), 64, 32, 20, 30, 50, 255);

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STUDIO_DUAL_WINDOW, false)) {
            app.initialize();
            StudioWindowController studio = app.getApi().getService(StudioWindowController.class);
            studio.showWorkspace();
            app.stepOnce(FIXED_DT);

            assertFalse(studio.getLastLintReport().isClean());
            ScreenManager studioScreens = studio.getStudioScreenManager();
            Screen ws = studioScreens.getScreen(StudioWindowController.WORKSPACE_SCREEN_NAME);

            List<TextButton> issueButtons = collectNodes(ws.getRoot(), TextButton.class).stream()
                    .filter(b -> b.getText().contains("UNDEFINED_SPEAKER")
                            || b.getText().contains("MISSING_JUMP_TARGET"))
                    .toList();
            assertFalse(issueButtons.isEmpty(),
                    "Studio Static Analysis panel must render clickable LintIssue buttons");

            clickNode(studioScreens, issueButtons.get(0));
            app.stepOnce(FIXED_DT);
            assertFalse(collectVisibleInputs(studioScreens).isEmpty(),
                    "Clicking a LintIssue must open target file in the Studio editor");

            TextButton autoFixBtn = findButtonContaining(ws.getRoot(), "auto-fix");
            assertNotNull(autoFixBtn, "Studio must expose a One-Click Auto-Fix button");
            clickNode(studioScreens, autoFixBtn);
            app.stepOnce(FIXED_DT);

            assertTrue(studio.getLastLintReport().isClean(),
                    "One-Click Auto-Fix must repair UNDEFINED_SPEAKER and MISSING_JUMP_TARGET cleanly");
        }
    }

    /**
     * Verifies goal {@code #0093}: Studio renders a visual Story-Flow Graph viewer highlighting
     * the currently active runtime label.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void studioVisualFlowGraphViewerHighlightsActiveRuntimeLabel(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF09AuthoringProject(tempDir.resolve("f09_flow"));

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STUDIO_DUAL_WINDOW, false)) {
            app.initialize();
            StudioWindowController studio = app.getApi().getService(StudioWindowController.class);
            ScriptSession session = app.getApi().getService(ScriptSession.class);
            studio.showWorkspace();
            app.stepOnce(FIXED_DT);

            ScreenManager studioScreens = studio.getStudioScreenManager();
            Screen ws = studioScreens.getScreen(StudioWindowController.WORKSPACE_SCREEN_NAME);
            TextButton flowBtn = findButtonContaining(ws.getRoot(), "flow");
            assertNotNull(flowBtn, "Studio workspace must expose a Flow-Graph viewer button/panel");
            clickNode(studioScreens, flowBtn);
            app.stepOnce(FIXED_DT);

            session.getRuntime().jumpToLabel("chapter_two");
            app.stepOnce(FIXED_DT);

            List<TextButton> highlighted = collectNodes(ws.getRoot(), TextButton.class).stream()
                    .filter(b -> b.isSelected() && b.getText().contains("chapter_two"))
                    .toList();
            assertFalse(highlighted.isEmpty(),
                    "Flow-Graph viewer must highlight currently active runtime label 'chapter_two'");
        }
    }

    /**
     * Verifies goals {@code #0094}, {@code #0095}, and {@code #0096}: Studio watchpoint panel,
     * step-by-step script debugger (breakpoints, {@code Step Into}, {@code Step Over},
     * {@code Continue}), and live variable editing in {@code Live State Variables}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void studioWatchpointsStepDebuggerAndLiveVariableEditing(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF09AuthoringProject(tempDir.resolve("f09_debug"));

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STUDIO_DUAL_WINDOW, false)) {
            app.initialize();
            StudioWindowController studio = app.getApi().getService(StudioWindowController.class);
            ScriptSession session = app.getApi().getService(ScriptSession.class);
            studio.showWorkspace();
            app.stepOnce(FIXED_DT);

            ScreenManager studioScreens = studio.getStudioScreenManager();
            Screen ws = studioScreens.getScreen(StudioWindowController.WORKSPACE_SCREEN_NAME);

            // #0094: Watchpoint service/UI registered in Studio
            VariableWatchpointManager watchpoints = app.getApi().getService(VariableWatchpointManager.class);
            assertNotNull(watchpoints, "Studio must register VariableWatchpointManager service");

            // #0095: Step debugger buttons in Studio workspace
            TextButton stepIntoBtn = findButtonContaining(ws.getRoot(), "step into");
            TextButton stepOverBtn = findButtonContaining(ws.getRoot(), "step over");
            TextButton continueBtn = findButtonContaining(ws.getRoot(), "continue");
            assertNotNull(stepIntoBtn, "Studio debugger must provide Step Into button");
            assertNotNull(stepOverBtn, "Studio debugger must provide Step Over button");
            assertNotNull(continueBtn, "Studio debugger must provide Continue button");

            // #0096: Editable variable input in Live State Variables panel
            List<TextInput> varInputs = collectNodes(ws.getRoot(), TextInput.class).stream()
                    .filter(ti -> ti.isVisible() && (ti.getId().contains("var") || ti.getText().contains("neutral")))
                    .toList();
            assertFalse(varInputs.isEmpty(),
                    "Live State Variables panel must provide editable variable TextInput");
            varInputs.get(0).setText("happy");
            studioScreens.dispatchKeyPressed(GLFW.GLFW_KEY_ENTER, 0);
            app.stepOnce(FIXED_DT);

            session.getRuntime().jumpToLabel("chapter_two");
            app.stepOnce(FIXED_DT);
            assertEquals("Stimmung ist fröhlich.", session.getRuntime().getActiveDialogue().interpolatedText(),
                    "Live variable edit must immediately govern next 'if' branch");
        }
    }

    /**
     * Verifies goal {@code #0097}: dragging a sprite in {@link LiveDirectorOverlay} / Window B
     * updates {@code Transform2D} and syncs a copyable {@code move} / {@code Transform2D} snippet
     * containing exact coordinates into Studio.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void liveDirectorSpriteDragGeneratesTransformSnippetInStudio(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF09AuthoringProject(tempDir.resolve("f09_drag"));

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STUDIO_DUAL_WINDOW, false)) {
            app.initialize();
            StudioWindowController studio = app.getApi().getService(StudioWindowController.class);
            ScriptSession session = app.getApi().getService(ScriptSession.class);
            ScreenManager gameScreens = app.getApi().getService(ScreenManager.class);
            studio.showWorkspace();
            gameScreens.hideScreen(MainMenuScreen.SCREEN_NAME);
            session.start();
            app.stepOnce(FIXED_DT);

            LiveDirectorOverlay director = (LiveDirectorOverlay) gameScreens.getScreen(
                    LiveDirectorOverlay.SCREEN_NAME);
            assertNotNull(director);
            gameScreens.showScreen(director);
            director.selectSprite("hero");
            app.stepOnce(FIXED_DT);

            SpriteNode hero = session.getSceneController().getActiveSprite("hero");
            assertNotNull(hero);
            float startX = hero.getTransform().getXpos();
            float startY = hero.getTransform().getYpos();

            // Simulate pointer drag on stage to (420, 680).
            InputManager gameInput = app.getApi().getInput();
            gameInput.onCursorPos(startX, startY);
            gameInput.onMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
            app.stepOnce(FIXED_DT);
            gameInput.onCursorPos(420.0, 680.0);
            app.stepOnce(FIXED_DT);
            gameInput.onMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0);
            app.stepOnce(FIXED_DT);

            assertEquals(420.0f, hero.getTransform().getXpos(), 2.0f,
                    "Dragging sprite in Live Director must update Transform2D xpos");
            assertEquals(680.0f, hero.getTransform().getYpos(), 2.0f,
                    "Dragging sprite in Live Director must update Transform2D ypos");

            List<TextWidget> studioTexts = collectNodes(
                    studio.getStudioScreenManager().getScreen(StudioWindowController.WORKSPACE_SCREEN_NAME).getRoot(),
                    TextWidget.class
            );
            assertTrue(
                    studioTexts.stream().anyMatch(tw -> tw.getText().contains("420")
                            && tw.getText().contains("680")),
                    "Studio must display generated move/Transform2D snippet with dragged coordinates"
            );
        }
    }

    /**
     * Verifies goals {@code #0098} and {@code #0099}: Asset Preview Gallery displays dimensions
     * and plays audio previews, while Unused Assets Cleanup Wizard moves unused files into
     * {@code .trash/} cleanly and supports Undo restoration.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void studioAssetPreviewGalleryAndUnusedCleanupWizardWithUndo(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF09AuthoringProject(tempDir.resolve("f09_assets"));

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STUDIO_DUAL_WINDOW, false)) {
            app.initialize();
            StudioWindowController studio = app.getApi().getService(StudioWindowController.class);
            studio.showWorkspace();
            app.stepOnce(FIXED_DT);

            ScreenManager studioScreens = studio.getStudioScreenManager();
            Screen ws = studioScreens.getScreen(StudioWindowController.WORKSPACE_SCREEN_NAME);

            // #0098: Clickable PNG asset item in preview gallery shows "64x32"
            TextButton pngAssetBtn = findButtonContaining(ws.getRoot(), "bg_main.png");
            assertNotNull(pngAssetBtn, "Asset gallery must list clickable PNG asset items");
            clickNode(studioScreens, pngAssetBtn);
            app.stepOnce(FIXED_DT);
            assertTrue(
                    collectNodes(ws.getRoot(), TextWidget.class).stream()
                            .anyMatch(tw -> tw.getText().contains("64x32")),
                    "Clicking PNG asset must display exact dimensions '64x32'"
            );

            // #0099: Unused Asset Cleanup Wizard moves unused_prop.png to .trash/ & Undo restores
            TextButton cleanupBtn = findButtonContaining(ws.getRoot(), "cleanup");
            if (cleanupBtn == null) {
                cleanupBtn = findButtonContaining(ws.getRoot(), "bereinig");
            }
            assertNotNull(cleanupBtn, "Studio must provide Unused Assets Cleanup Wizard button");
            clickNode(studioScreens, cleanupBtn);
            app.stepOnce(FIXED_DT);

            Path unusedPath = projectDir.resolve("assets/images/unused_prop.png");
            Path trashedPath = projectDir.resolve(".trash/assets/images/unused_prop.png");
            assertFalse(Files.exists(unusedPath), "Cleanup wizard must remove unused asset from assets/");
            assertTrue(Files.exists(trashedPath), "Cleanup wizard must move unused asset into .trash/");
            VJavaLinter.LintReport afterCleanupLint = studio.runLint();
            assertFalse(afterCleanupLint.hasErrors(),
                    "Remaining project must lint with 0 errors: " + afterCleanupLint.issues());
            assertTrue(
                    afterCleanupLint.issues().stream().noneMatch(i -> "MISSING_ASSET".equals(i.code())),
                    "Remaining project must lint with 0 MISSING_ASSET errors: " + afterCleanupLint.issues()
            );

            TextButton undoBtn = findButtonContaining(ws.getRoot(), "undo");
            if (undoBtn == null) {
                undoBtn = findButtonContaining(ws.getRoot(), "rückgängig");
            }
            assertNotNull(undoBtn, "Cleanup wizard must provide Undo restore button");
            clickNode(studioScreens, undoBtn);
            app.stepOnce(FIXED_DT);
            assertTrue(Files.exists(unusedPath), "Undo must restore trashed asset back to original path");
        }
    }

    /**
     * Verifies goal {@code #0100}: pressing {@code Ctrl+Shift+P} in Studio opens the interactive
     * Command Palette, filtering {@code "lint"} and executing {@code "Run Lint"} on {@code Enter}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void studioCommandPaletteCtrlShiftPOpensAndExecutesLint(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF09AuthoringProject(tempDir.resolve("f09_palette"));

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STUDIO_DUAL_WINDOW, false)) {
            app.initialize();
            StudioWindowController studio = app.getApi().getService(StudioWindowController.class);
            studio.showWorkspace();
            app.stepOnce(FIXED_DT);

            InputManager studioInput = studio.getStudioInput();
            int mods = GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT;
            studioInput.onKey(GLFW.GLFW_KEY_LEFT_CONTROL, 0, GLFW.GLFW_PRESS, mods);
            studioInput.onKey(GLFW.GLFW_KEY_LEFT_SHIFT, 0, GLFW.GLFW_PRESS, mods);
            studioInput.onKey(GLFW.GLFW_KEY_P, 0, GLFW.GLFW_PRESS, mods);
            studio.update(FIXED_DT);
            studioInput.onKey(GLFW.GLFW_KEY_P, 0, GLFW.GLFW_RELEASE, 0);
            studioInput.onKey(GLFW.GLFW_KEY_LEFT_SHIFT, 0, GLFW.GLFW_RELEASE, 0);
            studioInput.onKey(GLFW.GLFW_KEY_LEFT_CONTROL, 0, GLFW.GLFW_RELEASE, 0);
            studio.update(FIXED_DT);

            ScreenManager studioScreens = studio.getStudioScreenManager();
            Screen paletteModal = studioScreens.getTopModalScreen();
            assertNotNull(paletteModal, "Ctrl+Shift+P must open a modal Command Palette screen in Studio");

            List<TextInput> inputs = collectNodes(paletteModal.getRoot(), TextInput.class);
            assertFalse(inputs.isEmpty(), "Command Palette must expose search TextInput");
            inputs.get(0).setText("lint");
            studioScreens.dispatchKeyPressed(GLFW.GLFW_KEY_ENTER, 0);
            studio.update(FIXED_DT);

            StudioCommandPalette palette = app.getApi().getService(StudioCommandPalette.class);
            assertNotNull(palette, "Studio must register StudioCommandPalette service");
            assertFalse(palette.getRecentCommandIds().isEmpty(),
                    "Pressing Enter on 'lint' in Command Palette must execute Run Lint");
        }
    }

    private static Path prepareF09AuthoringProject(Path projectDir) throws Exception {
        E2eWorkspaceSupport.copyResourceTree("e2e/f09/studio_authoring", projectDir);
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/bg/bg_main.png"), 64, 32, 24, 32, 56, 255);
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/char/hero.png"), 48, 64, 88, 166, 255, 255);
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/unused_prop.png"), 32, 32, 180, 90, 90, 255);
        E2eFixtureFactory.writeSineWav(
                projectDir.resolve("assets/audio/ping.wav"), 520.0, 0.25, 22_050);
        return projectDir;
    }

    private static Class<?> findPublicClass(String... candidates) {
        for (String name : candidates) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {
            }
        }
        return null;
    }

    private static Method findHighlightMethod(Class<?> cls) {
        for (Method m : cls.getMethods()) {
            if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) {
                String n = m.getName().toLowerCase(Locale.ROOT);
                if (n.contains("highlight") || n.contains("tokenize") || n.contains("color")) {
                    return m;
                }
            }
        }
        return null;
    }

    private static void clickNode(ScreenManager screens, UiNode node) {
        Rect2f b = node.getBounds();
        float cx = b.x() + b.width() * 0.5f;
        float cy = b.y() + b.height() * 0.5f;
        screens.dispatchMouseDown(cx, cy, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        screens.dispatchMouseUp(cx, cy, GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    private static TextButton findButtonContaining(UiNode root, String needle) {
        return collectNodes(root, TextButton.class).stream()
                .filter(b -> b.getText().toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElse(null);
    }

    private static TextButton findVisibleButtonContaining(ScreenManager screens, String needle) {
        for (Screen s : screens.getVisibleScreensSorted()) {
            TextButton found = findButtonContaining(s.getRoot(), needle);
            if (found != null && found.isVisible()) {
                return found;
            }
        }
        return null;
    }

    private static List<TextInput> collectVisibleInputs(ScreenManager screens) {
        List<TextInput> inputs = new ArrayList<>();
        for (Screen s : screens.getVisibleScreensSorted()) {
            for (TextInput ti : collectNodes(s.getRoot(), TextInput.class)) {
                if (ti.isVisible()) {
                    inputs.add(ti);
                }
            }
        }
        return List.copyOf(inputs);
    }

    private static <T extends UiNode> List<T> collectNodes(UiNode root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collectInto(root, type, out);
        return out;
    }

    private static <T extends UiNode> void collectInto(UiNode node, Class<T> type, List<T> out) {
        if (node == null) {
            return;
        }
        if (type.isInstance(node)) {
            out.add(type.cast(node));
        }
        for (UiNode child : node.getChildren()) {
            collectInto(child, type, out);
        }
    }
}
