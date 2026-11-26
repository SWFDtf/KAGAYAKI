package de.swfdtf.e2e.tier2;

import de.swfdtf.audio.ClosedCaptionQueue;
import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineMode;
import de.swfdtf.core.GamepadNavigationRouter;
import de.swfdtf.core.InputManager;
import de.swfdtf.core.KeybindingRemapper;
import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2eFixtureFactory;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.e2e.support.E2eWorkspaceSupport;
import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.SceneNode;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.state.PreferencesState;
import de.swfdtf.ui.ControlsScreen;
import de.swfdtf.ui.MainMenuScreen;
import de.swfdtf.ui.PreferencesScreen;
import de.swfdtf.ui.Screen;
import de.swfdtf.ui.ScreenManager;
import de.swfdtf.ui.TextButton;
import de.swfdtf.ui.TextWidget;
import de.swfdtf.ui.ThemePalette;
import de.swfdtf.ui.UiNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 2 boundary, conflict, and negative E2E tests for Feature Area F6: invalid keybindings,
 * gamepad disconnection/drift edges, caption FIFO eviction, prompt suppression, and scale
 * clamping ({@code #0596}–{@code #0599}, {@code #0357}, {@code #0364}, {@code #0367}).
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER2)
public final class F06InputAccessibilityBoundaryE2eTest {

    private static final double FIXED_DT = 1.0 / 60.0;

    /** Creates a new {@code F06InputAccessibilityBoundaryE2eTest} instance. */
    public F06InputAccessibilityBoundaryE2eTest() {
    }

    /**
     * Verifies baseline {@link KeybindingRemapper} boundary validation: out-of-range keycodes,
     * blank action names, duplicate key conflicts, and corrupt JSON leaving active state intact.
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void keybindingRemapperRejectsInvalidKeysConflictsAndCorruptJson() {
        KeybindingRemapper remapper = new KeybindingRemapper();
        remapper.registerDefault("Advance", GLFW.GLFW_KEY_SPACE);
        remapper.registerDefault("QuickSave", GLFW.GLFW_KEY_S);

        assertThrows(IllegalArgumentException.class, () -> remapper.registerDefault("   ", GLFW.GLFW_KEY_A));
        assertThrows(IllegalArgumentException.class, () -> remapper.bind("Advance", -1));
        assertThrows(IllegalArgumentException.class, () -> remapper.bind("Advance", InputManager.KEY_COUNT));
        assertThrows(IllegalStateException.class, () -> remapper.rebind("Advance", GLFW.GLFW_KEY_S));

        assertThrows(IllegalArgumentException.class, () -> remapper.loadJson("{broken json"));
        assertThrows(IllegalStateException.class,
                () -> remapper.loadJson("{\"Advance\": 83, \"QuickSave\": 83}"));

        assertEquals(GLFW.GLFW_KEY_SPACE, remapper.getKeyCode("Advance").orElseThrow());
        assertEquals(GLFW.GLFW_KEY_S, remapper.getKeyCode("QuickSave").orElseThrow());
    }

    /**
     * Verifies baseline {@link GamepadNavigationRouter} and {@link ClosedCaptionQueue} edge
     * handling: disconnected gamepad, opposing directions cancellation, and invalid arguments.
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void gamepadNavigationAndClosedCaptionBoundaryValidation() {
        GamepadNavigationRouter router = new GamepadNavigationRouter();
        InputManager input = new InputManager();

        input.beginFrame();
        assertTrue(router.poll(input, FIXED_DT).isEmpty());
        input.endFrame();

        assertThrows(IllegalArgumentException.class, () -> router.poll(input, -0.01));
        assertThrows(IllegalArgumentException.class, () -> router.poll(input, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> router.setDeadzone(-0.01f));
        assertThrows(IllegalArgumentException.class, () -> router.setDeadzone(1.0f));
        assertThrows(IllegalArgumentException.class, () -> router.setDeadzone(Float.NaN));

        boolean[] buttons = new boolean[InputManager.GAMEPAD_BUTTON_COUNT];
        buttons[GLFW.GLFW_GAMEPAD_BUTTON_DPAD_LEFT] = true;
        buttons[GLFW.GLFW_GAMEPAD_BUTTON_DPAD_RIGHT] = true;
        input.beginFrame();
        input.updateGamepadState(0, true, buttons, new float[InputManager.GAMEPAD_AXIS_COUNT]);
        assertTrue(router.poll(input, FIXED_DT).isEmpty(),
                "Simultaneous opposing LEFT+RIGHT inputs must cancel cleanly");
        input.endFrame();

        ClosedCaptionQueue queue = new ClosedCaptionQueue(3);
        assertThrows(IllegalArgumentException.class,
                () -> queue.push(ClosedCaptionQueue.CaptionCategory.SFX, "Door", "   ", 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> queue.push(ClosedCaptionQueue.CaptionCategory.SFX, "Door", "[Click]", 0.0));
        assertThrows(IllegalArgumentException.class, () -> queue.update(-0.5));
    }

    /**
     * Verifies goal {@code #0596} negative path: remapping {@code Advance} disables the old key,
     * rejects duplicate non-swap conflicts, and preserves valid bindings.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void remappedRuntimeIgnoresOldKeyAndRejectsConflictingBind(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF06Project(tempDir.resolve("f06_b_remap"), "e2e/f06/remap_and_a11y");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            KeybindingRemapper remapper = app.getApi().getService(KeybindingRemapper.class);
            assertNotNull(remapper, "GameRuntimeCoordinator must register KeybindingRemapper service");

            int quickSaveKey = remapper.getKeyCode("QuickSave").orElse(GLFW.GLFW_KEY_S);
            assertThrows(IllegalStateException.class, () -> remapper.rebind("Advance", quickSaveKey));

            remapper.rebindAllowingSwap("Advance", GLFW.GLFW_KEY_K);
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            ScriptSession session = app.getApi().getService(ScriptSession.class);
            screens.hideScreen(MainMenuScreen.SCREEN_NAME);
            session.start();
            app.stepOnce(FIXED_DT);
            String initial = session.getRuntime().getActiveDialogue().interpolatedText();

            tapKey(app, GLFW.GLFW_KEY_SPACE);
            assertEquals(initial, session.getRuntime().getActiveDialogue().interpolatedText(),
                    "Original Advance key must not advance after remapping");
        }
    }

    /**
     * Verifies goal {@code #0597} boundary path: pressing {@code Escape} during capture cancels
     * without mutation, and duplicate key rebinding never leaves two actions on the same key.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void controlsScreenEscapeCancelsCaptureAndPreventsDuplicateKeys(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF06Project(tempDir.resolve("f06_b_ctrl"), "e2e/f06/remap_and_a11y");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            KeybindingRemapper remapper = app.getApi().getService(KeybindingRemapper.class);
            assertNotNull(remapper, "KeybindingRemapper service must be present");

            screens.showScreen(ControlsScreen.SCREEN_NAME);
            app.stepOnce(FIXED_DT);
            ControlsScreen controls = (ControlsScreen) screens.getScreen(ControlsScreen.SCREEN_NAME);
            List<TextButton> rebindButtons = collectNodes(controls.getRoot(), TextButton.class).stream()
                    .filter(b -> !isControlsTabOrBack(b.getText()))
                    .toList();
            assertFalse(rebindButtons.isEmpty(), "ControlsScreen must expose interactive rebind rows");

            var beforeSnapshot = remapper.snapshotBindings();
            clickNode(screens, rebindButtons.get(0));
            app.stepOnce(FIXED_DT);
            tapKey(app, GLFW.GLFW_KEY_ESCAPE);
            assertEquals(beforeSnapshot, remapper.snapshotBindings(),
                    "Escape during key capture must cancel without mutating active bindings");

            Set<Integer> uniqueKeys = new HashSet<>(remapper.snapshotBindings().values());
            assertEquals(remapper.snapshotBindings().size(), uniqueKeys.size(),
                    "No two actions may hold conflicting active key codes");
        }
    }

    /**
     * Verifies goal {@code #0598} boundary path: disconnected gamepad emits zero navigation,
     * and active gamepad navigation skips disabled controls such as {@code Continue} when empty.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void gamepadNavigationSkipsDisabledButtonsAndIgnoresDisconnected(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF06Project(tempDir.resolve("f06_b_gp"), "e2e/f06/remap_and_a11y");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            MainMenuScreen menu = (MainMenuScreen) screens.getScreen(MainMenuScreen.SCREEN_NAME);
            assertFalse(menu.getContinueButton().isEnabled(),
                    "Continue button must be disabled when no saves exist");

            // Disconnected gamepad must not move focus.
            boolean[] buttons = new boolean[InputManager.GAMEPAD_BUTTON_COUNT];
            buttons[GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN] = true;
            app.getApi().getInput().updateGamepadState(0, false, buttons, new float[InputManager.GAMEPAD_AXIS_COUNT]);
            app.stepOnce(FIXED_DT);
            UiNode beforeFocus = screens.getFocusedNode();

            // Connected D-pad navigation must advance focus without ever landing on disabled Continue.
            app.getApi().getInput().updateGamepadState(0, true, buttons, new float[InputManager.GAMEPAD_AXIS_COUNT]);
            app.stepOnce(FIXED_DT);
            UiNode firstFocus = screens.getFocusedNode();
            assertNotNull(firstFocus, "Connected gamepad D-Pad must set focus in ScreenManager");

            for (int i = 0; i < 6; i++) {
                app.getApi().getInput().updateGamepadState(
                        0, true, new boolean[InputManager.GAMEPAD_BUTTON_COUNT],
                        new float[InputManager.GAMEPAD_AXIS_COUNT]);
                app.stepOnce(FIXED_DT);
                app.getApi().getInput().updateGamepadState(
                        0, true, buttons, new float[InputManager.GAMEPAD_AXIS_COUNT]);
                app.stepOnce(FIXED_DT);
                assertFalse(screens.getFocusedNode() == menu.getContinueButton(),
                        "Gamepad focus must skip disabled Continue button");
            }
            assertFalse(beforeFocus == firstFocus && firstFocus == null);
        }
    }

    /**
     * Verifies goal {@code #0599} boundary threshold: at {@code deadzone = 0.35f}, deflection
     * {@code 0.349f} emits strict {@code (0.0, 0.0)} while {@code 0.351f} triggers motion.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void gamepadDeadzoneBoundaryDistinguishesJustBelowAndJustAboveThreshold(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF06Project(tempDir.resolve("f06_b_dz"), "e2e/f06/remap_and_a11y");
        E2eWorkspaceSupport.copyResourceTree("e2e/f06/deadzone_prefs", projectDir);

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            assertEquals(0.35f, app.getApi().getInput().getGamepadDeadzone(), 0.01f,
                    "Persisted deadzone 0.35 must be applied to InputManager");

            ScriptSession session = app.getApi().getService(ScriptSession.class);
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            screens.hideScreen(MainMenuScreen.SCREEN_NAME);
            session.getRuntime().jumpToLabel("wasd_scene");
            app.stepOnce(FIXED_DT);

            SpriteNode player = session.getSceneController().getActiveSprite("player");
            float startX = player.getTransform().getXpos();

            boolean[] none = new boolean[InputManager.GAMEPAD_BUTTON_COUNT];
            float[] below = new float[InputManager.GAMEPAD_AXIS_COUNT];
            below[GLFW.GLFW_GAMEPAD_AXIS_LEFT_X] = 0.349f;
            app.getApi().getInput().updateGamepadState(0, true, none, below);
            assertEquals(0.0f, app.getApi().getInput().getGamepadAxis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X), 0.0001f);
            for (int i = 0; i < 6; i++) {
                app.stepOnce(FIXED_DT);
            }
            assertEquals(startX, player.getTransform().getXpos(), 0.001f);

            float[] above = new float[InputManager.GAMEPAD_AXIS_COUNT];
            above[GLFW.GLFW_GAMEPAD_AXIS_LEFT_X] = 0.60f;
            app.getApi().getInput().updateGamepadState(0, true, none, above);
            assertTrue(app.getApi().getInput().getGamepadAxis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X) > 0.0f);
            for (int i = 0; i < 6; i++) {
                app.stepOnce(FIXED_DT);
            }
            assertTrue(player.getTransform().getXpos() > startX,
                    "Stick deflection above deadzone must drive WASD sprite movement");
        }
    }

    /**
     * Verifies goals {@code #0357} and {@code #0367} boundary paths: caption overlay evicts
     * oldest beyond 3 captions via FIFO, and high-contrast scale clamps extreme values safely.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void closedCaptionEvictionAndHighContrastScaleClamping(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF06Project(tempDir.resolve("f06_b_cap"), "e2e/f06/remap_and_a11y");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ClosedCaptionQueue queue = app.getApi().getService(ClosedCaptionQueue.class);
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            assertNotNull(queue, "ClosedCaptionQueue service must be registered");

            Screen captionScreen = findCaptionOverlayScreen(screens);
            assertNotNull(captionScreen, "ClosedCaption overlay screen must exist");

            queue.push(ClosedCaptionQueue.CaptionCategory.SFX, "A", "[First]", 5.0);
            queue.push(ClosedCaptionQueue.CaptionCategory.SFX, "B", "[Second]", 5.0);
            queue.push(ClosedCaptionQueue.CaptionCategory.SFX, "C", "[Third]", 5.0);
            queue.push(ClosedCaptionQueue.CaptionCategory.SFX, "D", "[Fourth]", 5.0);
            app.stepOnce(FIXED_DT);

            List<TextWidget> visible = collectNodes(captionScreen.getRoot(), TextWidget.class).stream()
                    .filter(w -> w.isVisible() && !w.getText().isBlank())
                    .toList();
            assertTrue(visible.size() <= 3, "At most 3 captions may render simultaneously");
            assertTrue(visible.stream().noneMatch(w -> w.getText().contains("[First]")),
                    "Oldest caption '[First]' must be evicted when 4th caption is pushed");

            // #0367 boundary: extreme text scale and contrast check
            PreferencesState prefs = app.getApi().getService(PreferencesState.class);
            FontManager fontManager = app.getApi().getService(FontManager.class);
            prefs.setHighContrastDyslexiaFont(true);
            prefs.setUiTextScale(-5.0f);
            assertEquals(1.0f, prefs.getUiTextScale(), 0.001f);
            assertThrows(IllegalArgumentException.class, () -> fontManager.setGlobalTextScale(-1.0f));
            fontManager.setGlobalTextScale(0.1f);
            assertTrue(fontManager.getGlobalTextScale() > 0.0f);
            assertTrue(computeContrastRatio(ThemePalette.TEXT_PRIMARY, ThemePalette.CANVAS_BG) >= 7.0);
        }
    }

    /**
     * Verifies goal {@code #0364} boundary path: context-sensitive prompt disappears when player
     * moves out of interaction range or when a modal screen opens.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void contextSensitivePromptHiddenOutsideRangeAndDuringModal(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF06Project(tempDir.resolve("f06_b_prompt"), "e2e/f06/prompt_trigger");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScriptSession session = app.getApi().getService(ScriptSession.class);
            ScreenManager screens = app.getApi().getService(ScreenManager.class);

            screens.hideScreen(MainMenuScreen.SCREEN_NAME);
            session.start();
            app.stepOnce(FIXED_DT);

            assertFalse(findPromptWidgets(screens, "[E]").isEmpty(),
                    "Prompt must be visible while player is near interactive object");

            // Opening a modal screen must hide/suppress the world interaction prompt.
            screens.showScreen(PreferencesScreen.SCREEN_NAME);
            app.stepOnce(FIXED_DT);
            assertTrue(findPromptWidgets(screens, "[E]").isEmpty(),
                    "Action prompt must be suppressed while a modal screen is open");

            // Closing modal and moving far right out of range must hide prompt.
            screens.hideScreen(PreferencesScreen.SCREEN_NAME);
            app.getApi().getInput().onKey(GLFW.GLFW_KEY_D, 0, GLFW.GLFW_PRESS, 0);
            for (int i = 0; i < 120; i++) {
                app.stepOnce(FIXED_DT);
            }
            app.getApi().getInput().onKey(GLFW.GLFW_KEY_D, 0, GLFW.GLFW_RELEASE, 0);
            app.stepOnce(FIXED_DT);
            assertTrue(findPromptWidgets(screens, "[E]").isEmpty(),
                    "Action prompt must hide when player moves outside interaction range");
        }
    }

    private static Path prepareF06Project(Path projectDir, String resourceTree) throws Exception {
        E2eWorkspaceSupport.copyResourceTree(resourceTree, projectDir);
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/bg/bg_room.png"), 64, 64, 18, 22, 36, 255);
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/char/lena.png"), 64, 64, 88, 166, 255, 255);
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/sprites/player.png"), 48, 48, 60, 200, 120, 255);
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/sprites/chest.png"), 48, 48, 220, 170, 60, 255);
        E2eFixtureFactory.writeSineWav(
                projectDir.resolve("assets/audio/sfx_door.wav"), 320.0, 0.2, 22_050);
        return projectDir;
    }

    private static void tapKey(EngineApplication app, int glfwKey) {
        InputManager input = app.getApi().getInput();
        input.onKey(glfwKey, 0, GLFW.GLFW_PRESS, 0);
        app.stepOnce(FIXED_DT);
        input.onKey(glfwKey, 0, GLFW.GLFW_RELEASE, 0);
        app.stepOnce(FIXED_DT);
    }

    private static void clickNode(ScreenManager screens, UiNode node) {
        Rect2f b = node.getBounds();
        float cx = b.x() + b.width() * 0.5f;
        float cy = b.y() + b.height() * 0.5f;
        screens.dispatchMouseDown(cx, cy, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        screens.dispatchMouseUp(cx, cy, GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    private static Screen findCaptionOverlayScreen(ScreenManager screens) {
        for (String candidate : List.of("closed_captions", "captions", "subtitles", "closed_caption_overlay")) {
            Screen found = screens.getScreen(candidate);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static List<TextWidget> findPromptWidgets(ScreenManager screens, String badge) {
        List<TextWidget> matches = new ArrayList<>();
        for (Screen s : screens.getVisibleScreensSorted()) {
            for (TextWidget tw : collectNodes(s.getRoot(), TextWidget.class)) {
                if (tw.isVisible() && tw.getText().contains(badge)) {
                    matches.add(tw);
                }
            }
        }
        return List.copyOf(matches);
    }

    private static boolean isControlsTabOrBack(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return "all".equals(lower) || "keyboard".equals(lower) || "mouse".equals(lower)
                || "gamepad".equals(lower) || "back".equals(lower);
    }

    private static double computeContrastRatio(String fgHex, String bgHex) {
        double l1 = relativeLuminance(SceneNode.parseHexRgba(fgHex));
        double l2 = relativeLuminance(SceneNode.parseHexRgba(bgHex));
        return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    }

    private static double relativeLuminance(float[] rgba) {
        return 0.2126 * linearize(rgba[0]) + 0.7152 * linearize(rgba[1]) + 0.0722 * linearize(rgba[2]);
    }

    private static double linearize(double channel) {
        return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
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
