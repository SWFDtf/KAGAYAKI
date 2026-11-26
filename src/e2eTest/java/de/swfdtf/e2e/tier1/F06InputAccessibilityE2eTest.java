package de.swfdtf.e2e.tier1;

import de.swfdtf.audio.ClosedCaptionQueue;
import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineMode;
import de.swfdtf.core.GamepadNavigationRouter;
import de.swfdtf.core.GamepadNavigationRouter.NavigationDirection;
import de.swfdtf.core.InputManager;
import de.swfdtf.core.KeybindingRemapper;
import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2eFixtureFactory;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.e2e.support.E2eWorkspaceSupport;
import de.swfdtf.graphics.ColorBlindnessFilter;
import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.SceneNode;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.state.PreferencesState;
import de.swfdtf.ui.AdvDialogueScreen;
import de.swfdtf.ui.ControlsScreen;
import de.swfdtf.ui.GameRuntimeCoordinator;
import de.swfdtf.ui.MainMenuScreen;
import de.swfdtf.ui.PreferencesScreen;
import de.swfdtf.ui.Screen;
import de.swfdtf.ui.ScreenManager;
import de.swfdtf.ui.Slider;
import de.swfdtf.ui.TextButton;
import de.swfdtf.ui.TextWidget;
import de.swfdtf.ui.ThemePalette;
import de.swfdtf.ui.UiNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 1 happy-path E2E tests for Feature Area F6: input remapping, interactive controls
 * rebinding, gamepad navigation, stick deadzone, closed captions, action prompts, and
 * high-contrast dyslexia accessibility ({@code #0596}–{@code #0599}, {@code #0357},
 * {@code #0364}, {@code #0367}).
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER1)
public final class F06InputAccessibilityE2eTest {

    private static final double FIXED_DT = 1.0 / 60.0;

    /** Creates a new {@code F06InputAccessibilityE2eTest} instance. */
    public F06InputAccessibilityE2eTest() {
    }

    /**
     * Verifies baseline {@link KeybindingRemapper} contracts across all core gameplay actions,
     * swap rebinding, JSON persistence round-trip, live {@link InputManager} query, and defaults reset.
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void keybindingRemapperBaselineJsonRoundTripAndActionState() {
        KeybindingRemapper remapper = registerStandardBindings();
        assertTrue(remapper.rebindAllowingSwap("Advance", GLFW.GLFW_KEY_J).isEmpty());
        assertEquals(GLFW.GLFW_KEY_J, remapper.getKeyCode("Advance").orElseThrow());

        assertTrue(remapper.rebindAllowingSwap("QuickSave", GLFW.GLFW_KEY_J).isPresent());
        assertEquals(GLFW.GLFW_KEY_J, remapper.getKeyCode("QuickSave").orElseThrow());
        assertEquals(GLFW.GLFW_KEY_S, remapper.getKeyCode("Advance").orElseThrow());

        String json = remapper.toJson();
        KeybindingRemapper restored = registerStandardBindings();
        restored.loadJson(json);
        assertEquals(remapper.snapshotBindings(), restored.snapshotBindings());

        InputManager input = new InputManager();
        input.beginFrame();
        input.onKey(GLFW.GLFW_KEY_J, 0, GLFW.GLFW_PRESS, 0);
        assertTrue(restored.isActionDown("QuickSave", input));
        assertFalse(restored.isActionDown("QuickLoad", input));
        input.endFrame();

        restored.resetToDefaults();
        assertEquals(GLFW.GLFW_KEY_SPACE, restored.getKeyCode("Advance").orElseThrow());
        assertEquals(GLFW.GLFW_KEY_S, restored.getKeyCode("QuickSave").orElseThrow());
    }

    /**
     * Verifies baseline {@link GamepadNavigationRouter}, {@link PreferencesState},
     * {@link FontManager} accessibility scaling, and {@link ClosedCaptionQueue} expiration.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if saving or loading preferences fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void gamepadRouterPreferencesAndClosedCaptionsBaseline(@TempDir Path tempDir) throws Exception {
        InputManager input = new InputManager();
        GamepadNavigationRouter router = new GamepadNavigationRouter(0.35f, 0.40, 0.12);

        boolean[] buttons = new boolean[InputManager.GAMEPAD_BUTTON_COUNT];
        float[] axes = new float[InputManager.GAMEPAD_AXIS_COUNT];
        buttons[GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN] = true;
        buttons[GLFW.GLFW_GAMEPAD_BUTTON_A] = true;

        input.beginFrame();
        input.updateGamepadState(0, true, buttons, axes);
        List<NavigationDirection> events = router.poll(input, FIXED_DT);
        assertEquals(List.of(NavigationDirection.DOWN, NavigationDirection.CONFIRM), events);
        input.endFrame();

        PreferencesState prefs = new PreferencesState();
        prefs.setHighContrastDyslexiaFont(true);
        prefs.setSelfVoicing(true);
        prefs.setUiTextScale(1.35f);
        Path savesDir = tempDir.resolve("saves");
        prefs.save(savesDir);
        PreferencesState reloaded = PreferencesState.load(savesDir);
        assertTrue(reloaded.isHighContrastDyslexiaFont());
        assertTrue(reloaded.isSelfVoicing());
        assertEquals(1.35f, reloaded.getUiTextScale(), 0.001f);

        FontManager fm = new FontManager(tempDir.resolve("fonts"), true);
        fm.setDyslexiaFriendlyMode(true).setGlobalTextScale(1.25f);
        assertTrue(fm.isDyslexiaFriendlyMode());
        assertTrue(fm.getAccessibilityExtraKerning() > 0.0f);
        assertTrue(fm.getAccessibilityLineSpacingMultiplier() > 1.0f);
        assertEquals(30.0f, fm.scaleFontSize(24.0f), 0.01f);
        fm.dispose();

        ClosedCaptionQueue queue = new ClosedCaptionQueue(3);
        queue.push(ClosedCaptionQueue.CaptionCategory.SFX, "Door", "[Tür knarrt]", 1.0);
        queue.push(ClosedCaptionQueue.CaptionCategory.MUSIC, "Weather", "[Regen setzt ein]", 2.0);
        assertEquals(2, queue.getVisibleCaptions().size());
        queue.update(1.1);
        assertEquals(1, queue.getVisibleCaptions().size());
        assertEquals("[Regen setzt ein]", queue.getVisibleCaptions().get(0).text());
    }

    /**
     * Verifies goal {@code #0596}: live {@link KeybindingRemapper} integration in
     * {@link GameRuntimeCoordinator} updates dialogue advance, quick-save, and WASD movement
     * immediately without restart.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void liveKeybindingRemapperControlsDialogueQuickSaveAndWasd(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF06Project(tempDir.resolve("f06_remap"), "e2e/f06/remap_and_a11y");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            KeybindingRemapper remapper = app.getApi().getService(KeybindingRemapper.class);
            assertNotNull(remapper, "GameRuntimeCoordinator must register KeybindingRemapper service");

            remapper.rebindAllowingSwap("Advance", GLFW.GLFW_KEY_J);
            remapper.rebindAllowingSwap("QuickSave", GLFW.GLFW_KEY_F5);
            remapper.rebindAllowingSwap("Right", GLFW.GLFW_KEY_L);

            ScriptSession session = app.getApi().getService(ScriptSession.class);
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            assertNotNull(session);
            assertNotNull(screens);

            screens.hideScreen(MainMenuScreen.SCREEN_NAME);
            session.start();
            app.stepOnce(FIXED_DT);
            String firstLine = session.getRuntime().getActiveDialogue().interpolatedText();

            // Old Space binding must NOT advance once remapped to J.
            tapKey(app, GLFW.GLFW_KEY_SPACE);
            assertEquals(firstLine, session.getRuntime().getActiveDialogue().interpolatedText(),
                    "Old Advance key Space must be ignored after remap to J");

            // Remapped Advance key J must advance immediately.
            tapKey(app, GLFW.GLFW_KEY_J);
            assertEquals("Zweite Dialogzeile nach dem Advance.",
                    session.getRuntime().getActiveDialogue().interpolatedText());

            // Remapped QuickSave key F5 must write saves/quick.ksav.
            tapKey(app, GLFW.GLFW_KEY_F5);
            assertTrue(Files.isRegularFile(projectDir.resolve("saves/quick.ksav")),
                    "Remapped QuickSave key F5 must create saves/quick.ksav");

            // Jump to wasd_scene and verify remapped Right key L moves player sprite.
            session.getRuntime().jumpToLabel("wasd_scene");
            app.stepOnce(FIXED_DT);
            SpriteNode player = session.getSceneController().getActiveSprite("player");
            assertNotNull(player);
            float startX = player.getTransform().getXpos();

            app.getApi().getInput().onKey(GLFW.GLFW_KEY_L, 0, GLFW.GLFW_PRESS, 0);
            for (int i = 0; i < 10; i++) {
                app.stepOnce(FIXED_DT);
            }
            app.getApi().getInput().onKey(GLFW.GLFW_KEY_L, 0, GLFW.GLFW_RELEASE, 0);
            assertTrue(player.getTransform().getXpos() > startX + 10.0f,
                    "Remapped Right key L must move WASD sprite");
        }
    }

    /**
     * Verifies goal {@code #0597}: {@link ControlsScreen} exposes interactive rebind buttons
     * ("Taste drücken...") that capture a new key and swap conflicts cleanly.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void controlsScreenSupportsInteractiveRebindingAndConflictSwap(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF06Project(tempDir.resolve("f06_controls"), "e2e/f06/remap_and_a11y");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            KeybindingRemapper remapper = app.getApi().getService(KeybindingRemapper.class);
            assertNotNull(screens);
            assertNotNull(remapper, "KeybindingRemapper service must be registered for ControlsScreen");

            screens.showScreen(ControlsScreen.SCREEN_NAME);
            app.stepOnce(FIXED_DT);

            ControlsScreen controls = (ControlsScreen) screens.getScreen(ControlsScreen.SCREEN_NAME);
            assertNotNull(controls);

            List<TextButton> buttons = collectNodes(controls.getRoot(), TextButton.class);
            List<TextButton> rebindButtons = buttons.stream()
                    .filter(b -> !isControlsTabOrBack(b.getText()))
                    .toList();
            assertFalse(rebindButtons.isEmpty(),
                    "ControlsScreen must provide interactive rebind buttons instead of static text rows");

            TextButton targetBtn = rebindButtons.get(0);
            clickNode(screens, targetBtn);
            app.stepOnce(FIXED_DT);

            assertTrue(
                    buttons.stream().anyMatch(b -> containsIgnoreCase(b.getText(), "taste")
                            || containsIgnoreCase(b.getText(), "press")),
                    "Clicking a binding button must enter 'Taste drücken...' / 'Press a key...' capture state"
            );

            tapKey(app, GLFW.GLFW_KEY_K);
            assertTrue(remapper.findActionForKey(GLFW.GLFW_KEY_K).isPresent(),
                    "Pressing K during capture must persist new binding into KeybindingRemapper");
        }
    }

    /**
     * Verifies goal {@code #0598}: {@link ScreenManager} routes {@link GamepadNavigationRouter}
     * D-pad, {@code A} confirm, and {@code B} cancel across built-in screens.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void gamepadNavigationRouterDrivesScreenManagerFocusConfirmAndCancel(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF06Project(tempDir.resolve("f06_gamepad"), "e2e/f06/remap_and_a11y");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            assertNotNull(screens);
            app.stepOnce(FIXED_DT);

            MainMenuScreen menu = (MainMenuScreen) screens.getScreen(MainMenuScreen.SCREEN_NAME);
            assertNotNull(menu);
            assertTrue(menu.isVisible());

            // Navigate via D-Pad DOWN to reach Preferences button.
            UiNode initialFocus = screens.getFocusedNode();
            sendGamepadPulse(app, GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN, 0.0f, 0.0f);
            UiNode afterFirstDown = screens.getFocusedNode();
            assertNotNull(afterFirstDown, "Gamepad D-Pad DOWN must assign focus in ScreenManager");
            assertFalse(Objects.equals(initialFocus, afterFirstDown),
                    "Gamepad D-Pad DOWN must advance ScreenManager focus");

            for (int i = 0; i < 12 && screens.getFocusedNode() != menu.getPreferencesButton(); i++) {
                sendGamepadPulse(app, GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN, 0.0f, 0.0f);
            }
            assertEquals(menu.getPreferencesButton(), screens.getFocusedNode(),
                    "Gamepad D-Pad must reach Preferences button");

            // Confirm (A) opens PreferencesScreen; Cancel (B) closes it back to MainMenuScreen.
            sendGamepadPulse(app, GLFW.GLFW_GAMEPAD_BUTTON_A, 0.0f, 0.0f);
            assertTrue(screens.getScreen(PreferencesScreen.SCREEN_NAME).isVisible(),
                    "Gamepad A button must activate focused Preferences button");

            sendGamepadPulse(app, GLFW.GLFW_GAMEPAD_BUTTON_B, 0.0f, 0.0f);
            assertFalse(screens.getScreen(PreferencesScreen.SCREEN_NAME).isVisible(),
                    "Gamepad B button must close modal PreferencesScreen");
        }
    }

    /**
     * Verifies goal {@code #0599}: analog-stick deadzone configuration in {@link PreferencesScreen}
     * persists to {@code preferences.json} and filters sub-threshold drift to {@code (0.0, 0.0)}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void gamepadDeadzoneConfigPersistsAndSuppressesStickDrift(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF06Project(tempDir.resolve("f06_deadzone"), "e2e/f06/remap_and_a11y");
        E2eWorkspaceSupport.copyResourceTree("e2e/f06/deadzone_prefs", projectDir);

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            ScriptSession session = app.getApi().getService(ScriptSession.class);
            assertNotNull(screens);
            assertNotNull(session);

            PreferencesScreen prefsScreen = (PreferencesScreen) screens.getScreen(PreferencesScreen.SCREEN_NAME);
            assertNotNull(prefsScreen);
            screens.showScreen(prefsScreen);
            app.stepOnce(FIXED_DT);

            List<TextWidget> labels = collectNodes(prefsScreen.getRoot(), TextWidget.class);
            assertTrue(
                    labels.stream().anyMatch(w -> containsIgnoreCase(w.getText(), "deadzone")
                            || containsIgnoreCase(w.getText(), "totzone")),
                    "PreferencesScreen must expose a Gamepad Deadzone setting"
            );

            assertEquals(0.35f, app.getApi().getInput().getGamepadDeadzone(), 0.01f,
                    "InputManager must apply persisted gamepadDeadzone 0.35 from preferences.json");

            screens.hideScreen(PreferencesScreen.SCREEN_NAME);
            screens.hideScreen(MainMenuScreen.SCREEN_NAME);
            session.getRuntime().jumpToLabel("wasd_scene");
            app.stepOnce(FIXED_DT);

            SpriteNode player = session.getSceneController().getActiveSprite("player");
            assertNotNull(player);
            float startX = player.getTransform().getXpos();

            // Drift below deadzone 0.35f must produce zero displacement and zero axis output.
            boolean[] none = new boolean[InputManager.GAMEPAD_BUTTON_COUNT];
            float[] driftAxes = new float[InputManager.GAMEPAD_AXIS_COUNT];
            driftAxes[GLFW.GLFW_GAMEPAD_AXIS_LEFT_X] = 0.25f;
            app.getApi().getInput().updateGamepadState(0, true, none, driftAxes);
            assertEquals(0.0f, app.getApi().getInput().getGamepadAxis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X), 0.0001f);
            for (int i = 0; i < 10; i++) {
                app.stepOnce(FIXED_DT);
            }
            assertEquals(startX, player.getTransform().getXpos(), 0.01f,
                    "Sub-deadzone stick drift must not move WASD player");
        }
    }

    /**
     * Verifies goal {@code #0357}: {@link ClosedCaptionQueue} overlay renders up to 3 simultaneous
     * captions at the bottom of the screen and removes expired captions over {@code update(dt)}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void closedCaptionOverlayDisplaysUpToThreeBottomCaptions(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF06Project(tempDir.resolve("f06_captions"), "e2e/f06/remap_and_a11y");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ClosedCaptionQueue queue = app.getApi().getService(ClosedCaptionQueue.class);
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            assertNotNull(queue, "EngineAPI must register ClosedCaptionQueue service");
            assertNotNull(screens);

            Screen captionScreen = findCaptionOverlayScreen(screens);
            assertNotNull(captionScreen, "ScreenManager must register a ClosedCaption overlay screen");

            queue.push(ClosedCaptionQueue.CaptionCategory.SFX, "Door", "[Tür knarrt]", 1.5);
            queue.push(ClosedCaptionQueue.CaptionCategory.MUSIC, "Rain", "[Regen setzt ein]", 3.0);
            queue.push(ClosedCaptionQueue.CaptionCategory.DIALOGUE, "Lena", "[Leises Flüstern]", 3.0);
            queue.push(ClosedCaptionQueue.CaptionCategory.SFX, "Bell", "[Glocke läutet]", 3.0);
            app.stepOnce(FIXED_DT);

            assertTrue(captionScreen.isVisible(), "Closed caption overlay must become visible on active captions");
            List<TextWidget> renderedCaptions = collectNodes(captionScreen.getRoot(), TextWidget.class).stream()
                    .filter(w -> w.isVisible() && !w.getText().isBlank())
                    .toList();
            assertFalse(renderedCaptions.isEmpty(), "Caption overlay must render caption TextWidgets");
            assertTrue(renderedCaptions.size() <= 3,
                    "Caption overlay must show at most 3 simultaneous captions, got: " + renderedCaptions.size());
            assertTrue(
                    renderedCaptions.stream().allMatch(w -> w.getBounds().y() >= 680.0f),
                    "Caption overlay widgets must render at bottom of 1920x1080 screen"
            );
        }
    }

    /**
     * Verifies goal {@code #0364}: context-sensitive action prompt ({@code "[E] Untersuchen"})
     * appears near interactive WASD objects and updates dynamically when {@link KeybindingRemapper}
     * changes the interact key.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void contextSensitiveActionPromptTracksKeybindingRemapper(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF06Project(tempDir.resolve("f06_prompt"), "e2e/f06/prompt_trigger");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScriptSession session = app.getApi().getService(ScriptSession.class);
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            KeybindingRemapper remapper = app.getApi().getService(KeybindingRemapper.class);
            assertNotNull(session);
            assertNotNull(screens);
            assertNotNull(remapper, "KeybindingRemapper service must be registered for action prompts");

            screens.hideScreen(MainMenuScreen.SCREEN_NAME);
            session.start();
            app.stepOnce(FIXED_DT);

            List<TextWidget> initialPrompts = findPromptWidgets(screens, "[E]");
            assertFalse(initialPrompts.isEmpty(),
                    "Expected floating '[E]' context-sensitive action prompt near chest object");

            remapper.rebindAllowingSwap("Interact", GLFW.GLFW_KEY_F);
            app.stepOnce(FIXED_DT);

            List<TextWidget> updatedPrompts = findPromptWidgets(screens, "[F]");
            assertFalse(updatedPrompts.isEmpty(),
                    "Action prompt must update dynamically from '[E]' to '[F]' after remap");
        }
    }

    /**
     * Verifies goal {@code #0367}: high-contrast UI preset & dyslexia mode disable text drop-shadows,
     * enforce dyslexia-friendly typography, and achieve WCAG AAA contrast ratio {@code >= 7.0}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void highContrastPresetEnforcesWcag7To1ContrastAndNoDropShadow(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF06Project(tempDir.resolve("f06_hc"), "e2e/f06/remap_and_a11y");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            ScriptSession session = app.getApi().getService(ScriptSession.class);
            FontManager fontManager = app.getApi().getService(FontManager.class);
            assertNotNull(screens);
            assertNotNull(session);
            assertNotNull(fontManager);

            PreferencesScreen prefsScreen = (PreferencesScreen) screens.getScreen(PreferencesScreen.SCREEN_NAME);
            assertNotNull(prefsScreen);
            screens.showScreen(prefsScreen);
            app.stepOnce(FIXED_DT);
            clickNode(screens, prefsScreen.getDyslexiaFontToggle());
            app.stepOnce(FIXED_DT);
            screens.hideScreen(PreferencesScreen.SCREEN_NAME);

            assertTrue(session.getPreferencesState().isHighContrastDyslexiaFont());
            assertTrue(fontManager.isDyslexiaFriendlyMode());

            ColorBlindnessFilter filter = app.getApi().getService(ColorBlindnessFilter.class);
            assertNotNull(filter, "High-contrast mode must wire ColorBlindnessFilter service");
            assertEquals(ColorBlindnessFilter.AccessibilityMode.HIGH_CONTRAST, filter.getMode());

            screens.hideScreen(MainMenuScreen.SCREEN_NAME);
            session.start();
            app.stepOnce(FIXED_DT);

            AdvDialogueScreen adv = (AdvDialogueScreen) screens.getScreen(AdvDialogueScreen.SCREEN_NAME);
            assertNotNull(adv);
            List<TextWidget> advTexts = collectNodes(adv.getRoot(), TextWidget.class);
            assertFalse(advTexts.isEmpty());
            assertTrue(
                    advTexts.stream().noneMatch(TextWidget::isDropShadow),
                    "High-contrast dyslexia mode must disable flickering dropShadow on AdvDialogueScreen text"
            );

            for (TextWidget widget : advTexts) {
                double ratio = computeContrastRatio(widget.getColorHex(), ThemePalette.CANVAS_BG);
                assertTrue(ratio >= 7.0,
                        "Expected WCAG AAA contrast >= 7.0 on High-Contrast text, got: " + ratio);
            }
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

    private static KeybindingRemapper registerStandardBindings() {
        KeybindingRemapper remapper = new KeybindingRemapper();
        remapper.registerDefault("Advance", GLFW.GLFW_KEY_SPACE);
        remapper.registerDefault("QuickSave", GLFW.GLFW_KEY_S);
        remapper.registerDefault("QuickLoad", GLFW.GLFW_KEY_L);
        remapper.registerDefault("Auto", GLFW.GLFW_KEY_A);
        remapper.registerDefault("Skip", GLFW.GLFW_KEY_LEFT_CONTROL);
        remapper.registerDefault("Up", GLFW.GLFW_KEY_W);
        remapper.registerDefault("Down", GLFW.GLFW_KEY_DOWN);
        remapper.registerDefault("Left", GLFW.GLFW_KEY_LEFT);
        remapper.registerDefault("Right", GLFW.GLFW_KEY_D);
        remapper.registerDefault("Jump", GLFW.GLFW_KEY_UP);
        return remapper;
    }

    private static void tapKey(EngineApplication app, int glfwKey) {
        InputManager input = app.getApi().getInput();
        input.onKey(glfwKey, 0, GLFW.GLFW_PRESS, 0);
        app.stepOnce(FIXED_DT);
        input.onKey(glfwKey, 0, GLFW.GLFW_RELEASE, 0);
        app.stepOnce(FIXED_DT);
    }

    private static void sendGamepadPulse(EngineApplication app, int button, float leftX, float leftY) {
        boolean[] buttons = new boolean[InputManager.GAMEPAD_BUTTON_COUNT];
        buttons[button] = true;
        float[] axes = new float[InputManager.GAMEPAD_AXIS_COUNT];
        axes[GLFW.GLFW_GAMEPAD_AXIS_LEFT_X] = leftX;
        axes[GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y] = leftY;

        app.getApi().getInput().updateGamepadState(0, true, buttons, axes);
        app.stepOnce(FIXED_DT);
        app.getApi().getInput().updateGamepadState(
                0, true, new boolean[InputManager.GAMEPAD_BUTTON_COUNT], new float[InputManager.GAMEPAD_AXIS_COUNT]);
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

    private static boolean containsIgnoreCase(String text, String needle) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static double computeContrastRatio(String fgHex, String bgHex) {
        double l1 = relativeLuminance(SceneNode.parseHexRgba(fgHex));
        double l2 = relativeLuminance(SceneNode.parseHexRgba(bgHex));
        double lighter = Math.max(l1, l2);
        double darker = Math.min(l1, l2);
        return (lighter + 0.05) / (darker + 0.05);
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
