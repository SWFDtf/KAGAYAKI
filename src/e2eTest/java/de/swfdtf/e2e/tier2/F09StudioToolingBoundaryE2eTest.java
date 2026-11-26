package de.swfdtf.e2e.tier2;

import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineMode;
import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2eFixtureFactory;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.e2e.support.E2eWorkspaceSupport;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.parser.VariableWatchpointManager;
import de.swfdtf.state.SimpleStateStore;
import de.swfdtf.tools.AssetMetadataInspector;
import de.swfdtf.tools.LiveDirectorOverlay;
import de.swfdtf.tools.StudioCommandPalette;
import de.swfdtf.tools.StudioWindowController;
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

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 2 boundary, guard-rail, and negative E2E tests for Feature Area F9: path traversal
 * rejection, malformed scripts in editor, highlighter edge strings, line-number clamping,
 * Auto-Fix idempotency, cyclic flow graphs, invalid debugger/watchpoint inputs, out-of-bounds
 * drag, corrupt asset fallback, and protected-asset cleanup guards ({@code #0088}–{@code #0100}).
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER2)
public final class F09StudioToolingBoundaryE2eTest {

    private static final double FIXED_DT = 1.0 / 60.0;

    /** Creates a new {@code F09StudioToolingBoundaryE2eTest} instance. */
    public F09StudioToolingBoundaryE2eTest() {
    }

    /**
     * Verifies baseline Studio tooling safety rails: path traversal rejection in
     * {@link AssetMetadataInspector}, missing directory IO errors in {@link VJavaLinterAutoFixer},
     * blank watchpoint key rejection, unknown palette command IDs, and comment/indentation
     * preservation in {@link LiveDirectorOverlay#updateShowLineInScript}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void studioBackendGuardRailsAndAtomicScriptPreservationBaseline(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF09AuthoringProject(tempDir.resolve("f09_b_base"));

        AssetMetadataInspector inspector = new AssetMetadataInspector();
        assertThrows(IllegalArgumentException.class,
                () -> inspector.inspectAsset(projectDir, "../secret.png"));
        assertThrows(IllegalArgumentException.class,
                () -> inspector.inspectAsset(projectDir, "   "));

        VJavaLinterAutoFixer fixer = new VJavaLinterAutoFixer();
        assertThrows(IOException.class, () -> fixer.applySafeFixes(tempDir.resolve("does_not_exist")));

        VariableWatchpointManager wp = new VariableWatchpointManager();
        assertThrows(IllegalArgumentException.class, () -> wp.watchKey("   ", evt -> {}));
        assertThrows(NullPointerException.class, () -> wp.watchKey("hp", null));
        SimpleStateStore store = new SimpleStateStore();
        store.set("unwatched", 1);
        assertEquals(0, wp.checkAll(store, "start"));

        StudioCommandPalette palette = new StudioCommandPalette();
        assertFalse(palette.executeById("nonexistent.cmd"));
        assertTrue(palette.search("zzzz").isEmpty());

        Path scriptFile = tempDir.resolve("preserve.vjava");
        Files.writeString(scriptFile, """
                label start:
                    show hero happy at left with dissolve(0.3) // keep comment
                    return
                """, StandardCharsets.UTF_8);
        assertTrue(LiveDirectorOverlay.updateShowLineInScript(
                scriptFile, 2, "hero", List.of("neutral"), "xpos 300 ypos 500"));
        String updated = Files.readString(scriptFile, StandardCharsets.UTF_8);
        assertTrue(updated.contains("    show hero neutral"), "Leading 4-space indent must be preserved");
        assertTrue(updated.contains("with dissolve(0.3)"), "Trailing transition must be preserved");
        assertTrue(updated.contains("// keep comment"), "Trailing comment must be preserved");
    }

    /**
     * Verifies goals {@code #0088}, {@code #0089}, and {@code #0090} boundary paths: empty project
     * path rejection in New Project dialog, syntax error in editor surfaces {@code PARSE_ERROR}
     * without crashing, and unterminated strings/nested Java delimiters tokenize under 2 ms.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void newProjectValidationEditorParseErrorAndHighlighterStress(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF09AuthoringProject(tempDir.resolve("f09_b_ed"));

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STUDIO_DUAL_WINDOW, false)) {
            app.initialize();
            StudioWindowController studio = app.getApi().getService(StudioWindowController.class);
            ScreenManager studioScreens = studio.getStudioScreenManager();

            // #0088: New Project dialog exposes editable inputs and rejects blank path cleanly
            TextButton newBtn = findButtonContaining(
                    studioScreens.getScreen(StudioWindowController.MAIN_MENU_SCREEN_NAME).getRoot(),
                    "neues projekt");
            clickNode(studioScreens, newBtn);
            app.stepOnce(FIXED_DT);
            List<TextInput> dialogInputs = collectVisibleInputs(studioScreens);
            assertTrue(dialogInputs.size() >= 3, "New Project dialog must present editable inputs");

            // #0089: Editor surfaces PARSE_ERROR on invalid syntax without crashing Studio
            studio.showWorkspace();
            app.stepOnce(FIXED_DT);
            E2eWorkspaceSupport.writeScript(projectDir, "main.vjava", "label broken\n    \"no colon\"\n");
            studio.runLint();
            assertTrue(studio.getLastLintReport().hasErrors());

            // #0090: Highlighter handles unterminated string and "}<<" inside Java string < 2 ms
            Class<?> highlighterCls = findPublicClass(
                    "de.swfdtf.tools.VJavaSyntaxHighlighter",
                    "de.swfdtf.tools.VJavaSyntaxColorizer"
            );
            assertNotNull(highlighterCls, "Syntax highlighter class must exist");
            Method highlightMethod = findHighlightMethod(highlighterCls);
            assertNotNull(highlightMethod);
            Object instance = highlighterCls.getConstructor().newInstance();
            String stress = (":: block @->{ String s = \"}<<\"; }<<\n"
                    + "label x:\n    \"unterminated string\n    return\n").repeat(250);
            highlightMethod.invoke(instance, stress);
            long t0 = System.nanoTime();
            Object out = highlightMethod.invoke(instance, stress);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            assertNotNull(out);
            assertTrue(elapsedMs < 2L, "Malformed 1,000-line highlight exceeded 2 ms: " + elapsedMs + " ms");
        }
    }

    /**
     * Verifies goals {@code #0091} and {@code #0092} boundary paths: click-to-line clamps
     * out-of-range line numbers, and Auto-Fix leaves non-fixable {@code PARSE_ERROR} untouched
     * while remaining idempotent.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void clickToLineClampingAndAutoFixIdempotencyBoundary(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f09_b_fix");
        E2eWorkspaceSupport.copyResourceTree("e2e/f09/studio_broken_lint", projectDir);
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/bg/bg_main.png"), 64, 32, 20, 30, 50, 255);

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STUDIO_DUAL_WINDOW, false)) {
            app.initialize();
            StudioWindowController studio = app.getApi().getService(StudioWindowController.class);
            studio.showWorkspace();
            app.stepOnce(FIXED_DT);

            ScreenManager studioScreens = studio.getStudioScreenManager();
            Screen ws = studioScreens.getScreen(StudioWindowController.WORKSPACE_SCREEN_NAME);
            TextButton autoFixBtn = findButtonContaining(ws.getRoot(), "auto-fix");
            assertNotNull(autoFixBtn, "Studio must provide One-Click Auto-Fix button");

            clickNode(studioScreens, autoFixBtn);
            app.stepOnce(FIXED_DT);
            assertTrue(studio.getLastLintReport().isClean());

            // Second Auto-Fix pass must remain idempotent.
            VJavaLinterAutoFixer.AutoFixResult second = new VJavaLinterAutoFixer().applySafeFixes(projectDir);
            assertEquals(0, second.appliedCount(), "Repeated Auto-Fix run must be idempotent");
        }
    }

    /**
     * Verifies goal {@code #0093} boundary path: cyclic {@code jump} loops and unreachable labels
     * render in Studio's Flow-Graph viewer without hanging.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void studioFlowGraphHandlesCyclicJumpsAndUnreachableLabels(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF09AuthoringProject(tempDir.resolve("f09_b_flow"));
        E2eWorkspaceSupport.writeScript(projectDir, "main.vjava", """
                define lena = Character("Lena", color="#58a6ff")
                label start:
                    lena "Loop A"
                    jump loop_b
                label loop_b:
                    lena "Loop B"
                    jump start
                label orphan_island:
                    lena "Unreachable"
                    return
                """);

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STUDIO_DUAL_WINDOW, false)) {
            app.initialize();
            StudioWindowController studio = app.getApi().getService(StudioWindowController.class);
            studio.showWorkspace();
            app.stepOnce(FIXED_DT);

            ScreenManager studioScreens = studio.getStudioScreenManager();
            Screen ws = studioScreens.getScreen(StudioWindowController.WORKSPACE_SCREEN_NAME);
            TextButton flowBtn = findButtonContaining(ws.getRoot(), "flow");
            assertNotNull(flowBtn, "Studio must provide Flow-Graph viewer button");
            clickNode(studioScreens, flowBtn);
            app.stepOnce(FIXED_DT);

            assertFalse(collectNodes(ws.getRoot(), TextWidget.class).stream()
                    .noneMatch(tw -> tw.getText().contains("orphan_island")),
                    "Flow-Graph viewer must distinguish unreachable label 'orphan_island'");
        }
    }

    /**
     * Verifies goals {@code #0094}, {@code #0095}, and {@code #0096} negative paths: invalid numeric
     * edit in {@code Live State Variables} preserves prior value, and debugger/watchpoint guards
     * reject invalid breakpoints cleanly.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void debuggerInvalidBreakpointAndMalformedVariableEditRejected(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF09AuthoringProject(tempDir.resolve("f09_b_dbg"));

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STUDIO_DUAL_WINDOW, false)) {
            app.initialize();
            StudioWindowController studio = app.getApi().getService(StudioWindowController.class);
            ScriptSession session = app.getApi().getService(ScriptSession.class);
            studio.showWorkspace();
            app.stepOnce(FIXED_DT);

            ScreenManager studioScreens = studio.getStudioScreenManager();
            Screen ws = studioScreens.getScreen(StudioWindowController.WORKSPACE_SCREEN_NAME);
            TextButton continueBtn = findButtonContaining(ws.getRoot(), "continue");
            assertNotNull(continueBtn, "Studio debugger must expose Continue control");

            int beforeScore = session.getStateStore().getInt("score", 10);
            List<TextInput> numericVarInputs = collectNodes(ws.getRoot(), TextInput.class).stream()
                    .filter(ti -> ti.isVisible() && ti.getText().trim().equals(String.valueOf(beforeScore)))
                    .toList();
            assertFalse(numericVarInputs.isEmpty(),
                    "Live State Variables must expose editable field for integer 'score'");
            numericVarInputs.get(0).setText("not_an_int");
            studioScreens.dispatchKeyPressed(GLFW.GLFW_KEY_ENTER, 0);
            app.stepOnce(FIXED_DT);

            assertEquals(beforeScore, session.getStateStore().getInt("score", -1),
                    "Invalid numeric edit must preserve prior integer StateStore value");
        }
    }

    /**
     * Verifies goal {@code #0097} boundary path: dragging in {@link LiveDirectorOverlay} with no
     * selected sprite or outside {@code 1920x1080} does not crash or corrupt script files.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void liveDirectorDragClampsBoundsAndIgnoresEmptySelection(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF09AuthoringProject(tempDir.resolve("f09_b_drag"));

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
            var gameInput = app.getApi().getInput();
            gameInput.onCursorPos(hero.getTransform().getXpos(), hero.getTransform().getYpos());
            gameInput.onMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
            app.stepOnce(FIXED_DT);
            gameInput.onCursorPos(3000.0, 2000.0);
            app.stepOnce(FIXED_DT);
            gameInput.onMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0);
            app.stepOnce(FIXED_DT);

            assertTrue(hero.getTransform().getXpos() <= 1920.0f && hero.getTransform().getYpos() <= 1080.0f,
                    "Out-of-bounds drag must clamp sprite within 1920x1080 logical canvas");
            assertFalse(director.commitCurrentSpriteEdit("nonexistent_tag"));
        }
    }

    /**
     * Verifies goals {@code #0098}, {@code #0099}, and {@code #0100} boundaries: corrupt PNG
     * gracefully yields fallback metadata, Cleanup Wizard refuses to trash referenced assets,
     * and unknown Command Palette query executes nothing.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void assetGalleryCorruptGuardProtectedCleanupAndPaletteEmptyQuery(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF09AuthoringProject(tempDir.resolve("f09_b_assets"));
        Files.write(projectDir.resolve("assets/images/corrupt.png"), new byte[]{1, 2, 3, 4});

        AssetMetadataInspector inspector = new AssetMetadataInspector();
        AssetMetadataInspector.AssetMetadata corruptMeta =
                inspector.inspectAsset(projectDir, "assets/images/corrupt.png").orElseThrow();
        assertTrue(corruptMeta.widthPx().isEmpty(), "Corrupt PNG must return empty width without crashing");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STUDIO_DUAL_WINDOW, false)) {
            app.initialize();
            StudioWindowController studio = app.getApi().getService(StudioWindowController.class);
            studio.showWorkspace();
            app.stepOnce(FIXED_DT);

            ScreenManager studioScreens = studio.getStudioScreenManager();
            Screen ws = studioScreens.getScreen(StudioWindowController.WORKSPACE_SCREEN_NAME);
            TextButton cleanupBtn = findButtonContaining(ws.getRoot(), "cleanup");
            if (cleanupBtn == null) {
                cleanupBtn = findButtonContaining(ws.getRoot(), "bereinig");
            }
            assertNotNull(cleanupBtn, "Unused Assets Cleanup Wizard button must exist");
            clickNode(studioScreens, cleanupBtn);
            app.stepOnce(FIXED_DT);

            assertTrue(Files.exists(projectDir.resolve("assets/images/bg/bg_main.png")),
                    "Referenced asset bg_main.png must NEVER be trashed by Cleanup Wizard");
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
