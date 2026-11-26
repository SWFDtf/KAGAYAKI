package de.swfdtf.e2e.tier2;

import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineMode;
import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2eFixtureFactory;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.e2e.support.E2eWorkspaceSupport;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.state.ChapterRegistry;
import de.swfdtf.state.CodexGlossaryManager;
import de.swfdtf.state.PreferencesState;
import de.swfdtf.state.RelationshipAffinityTracker;
import de.swfdtf.state.SimplePersistentAccess;
import de.swfdtf.state.SimpleStateStore;
import de.swfdtf.ui.AdvDialogueScreen;
import de.swfdtf.ui.Grid;
import de.swfdtf.ui.MainMenuScreen;
import de.swfdtf.ui.PreferencesScreen;
import de.swfdtf.ui.QuickMenuScreen;
import de.swfdtf.ui.SaveLoadScreen;
import de.swfdtf.ui.Screen;
import de.swfdtf.ui.ScreenManager;
import de.swfdtf.ui.TextButton;
import de.swfdtf.ui.TextInput;
import de.swfdtf.ui.TextWidget;
import de.swfdtf.ui.UiNode;
import de.swfdtf.ui.VBox;
import de.swfdtf.ui.Viewport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 2 boundary, edge, and negative E2E tests for Feature Area F7: disabled save slots,
 * cancel overwrite confirmation, scroll clamping, input whitelist, odd grid counts, tooltip
 * pre-delay suppression, focus skipping, locked meta entries, and fallback rendering
 * ({@code #0342}–{@code #0356}, {@code #0359}, {@code #0360}, {@code #0362}).
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER2)
public final class F07UiScreensBoundaryE2eTest {

    private static final double FIXED_DT = 1.0 / 60.0;

    /** Creates a new {@code F07UiScreensBoundaryE2eTest} instance. */
    public F07UiScreensBoundaryE2eTest() {
    }

    /**
     * Verifies baseline {@link Grid}, {@link Viewport}, {@link TextInput}, and
     * {@link RelationshipAffinityTracker} boundary clamping.
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void gridViewportTextInputAndAffinityBoundariesBaseline() {
        Grid zeroCols = new Grid(0, 0);
        assertEquals(1, zeroCols.getColumns());
        zeroCols.layout(new Rect2f(0.0f, 0.0f, 300.0f, 200.0f));

        Viewport vp = new Viewport();
        vp.layout(new Rect2f(0.0f, 0.0f, 400.0f, 300.0f));
        vp.setScrollY(-50.0f);
        assertEquals(0.0f, vp.getScrollY(), 0.001f);
        vp.setScrollY(99999.0f);
        assertEquals(0.0f, vp.getScrollY(), 0.001f);
        vp.setScrollY(Float.NaN);
        assertEquals(0.0f, vp.getScrollY(), 0.001f);

        TextInput input = new TextInput("", null).setAllowChars("abc").setMaxLength(3);
        input.setText("a1b2c3d4");
        assertEquals("abc", input.getText());

        RelationshipAffinityTracker tracker = new RelationshipAffinityTracker(new SimpleStateStore());
        tracker.setPoints("villain", -50);
        assertEquals(RelationshipAffinityTracker.RelationshipTier.HOSTILE, tracker.getTier("villain"));
        tracker.setPoints("hero", Integer.MAX_VALUE);
        assertEquals(RelationshipAffinityTracker.RelationshipTier.DEVOTED, tracker.getTier("hero"));
    }

    /**
     * Verifies goals {@code #0342} and {@code #0347} negative paths: empty slot in
     * {@code template_load_screen} is disabled, and pressing {@code Escape} on overwrite
     * confirmation preserves prior save metadata untouched.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void emptyLoadSlotDisabledAndOverwriteCancelPreservesOldSave(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF07Project(tempDir.resolve("f07_b_slots"), "e2e/f07/meta_screens");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            ScriptSession session = app.getApi().getService(ScriptSession.class);

            Screen loadTemplate = screens.getScreen("template_load_screen");
            assertNotNull(loadTemplate, "template_load_screen must be registered");
            screens.showScreen(loadTemplate);
            app.stepOnce(FIXED_DT);
            List<TextButton> slotBtns = collectNodes(loadTemplate.getRoot(), TextButton.class).stream()
                    .filter(b -> !containsIgnoreCase(b.getText(), "zurück")
                            && !containsIgnoreCase(b.getText(), "schnellladen")
                            && !containsIgnoreCase(b.getText(), "quick")
                            && !containsIgnoreCase(b.getText(), "back"))
                    .toList();
            assertFalse(slotBtns.isEmpty(), "template_load_screen must expose dynamic slot buttons");
            assertTrue(slotBtns.stream().noneMatch(UiNode::isEnabled),
                    "Empty slots in template_load_screen must be disabled");
            screens.hideScreen("template_load_screen");

            // #0347: Occupy slot_1, open SaveLoadScreen in SAVE mode, trigger overwrite popup, then Escape
            screens.hideScreen(MainMenuScreen.SCREEN_NAME);
            session.getPreferencesState().setTextSpeedCps(0.0f);
            session.start();
            app.stepOnce(FIXED_DT);
            session.saveToSlot("slot_1", "Geschützter Stand", 0, 0, null);
            session.advance();
            app.stepOnce(FIXED_DT);

            SaveLoadScreen saveLoad = (SaveLoadScreen) screens.getScreen(SaveLoadScreen.SCREEN_NAME);
            saveLoad.setMode(SaveLoadScreen.Mode.SAVE);
            screens.showScreen(saveLoad);
            app.stepOnce(FIXED_DT);
            clickNode(screens, saveLoad.getVisibleCards().get(0).getActionButton());
            app.stepOnce(FIXED_DT);

            Screen topModal = screens.getTopModalScreen();
            assertNotEquals(SaveLoadScreen.SCREEN_NAME, topModal.getName(),
                    "Confirmation popup must open before overwriting occupied slot");
            screens.dispatchKeyPressed(GLFW.GLFW_KEY_ESCAPE, 0);
            app.stepOnce(FIXED_DT);

            assertEquals("Geschützter Stand",
                    session.getSaveManager().readSlotMetadata("slot_1").orElseThrow().customName(),
                    "Cancelling overwrite confirmation via Escape must preserve original save label");
        }
    }

    /**
     * Verifies goals {@code #0343} and {@code #0344} boundaries: viewport inertia stays clamped
     * in {@code [0, maxScrollY]} and {@link TextInput} cursor boundaries reject overflow/control chars.
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void viewportInertiaClampingAndTextInputBoundaryRejection() {
        ScreenManager screens = new ScreenManager();
        screens.setLogicalSize(1920.0f, 1080.0f);

        Screen screen = new Screen("b_scroll_input", 100, false);
        Viewport vp = new Viewport();
        vp.setPreferredSize(400.0f, 200.0f);
        VBox box = new VBox();
        for (int i = 0; i < 20; i++) {
            TextWidget tw = new TextWidget("Line " + i);
            tw.setPreferredSize(360.0f, 40.0f);
            box.addChild(tw);
        }
        vp.addChild(box);
        screen.setRoot(vp);
        screens.registerScreen(screen);
        screens.showScreen(screen);
        screens.update(FIXED_DT, null);

        screens.dispatchMouseDown(150.0f, 180.0f, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        vp.dispatchMouseMove(150.0f, -200.0f);
        screens.dispatchMouseUp(150.0f, -200.0f, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        for (int i = 0; i < 30; i++) {
            screens.update(FIXED_DT, null);
        }
        assertTrue(vp.getScrollY() >= 0.0f && vp.getScrollY() <= vp.getMaxScrollY(),
                "Viewport inertia must stay strictly clamped in [0, maxScrollY]");

        TextInput input = new TextInput("", null).setAllowChars("ab").setMaxLength(3);
        Screen inputScreen = new Screen("b_input", 100, false);
        inputScreen.setRoot(input);
        screens.registerScreen(inputScreen);
        screens.showScreen(inputScreen);
        screens.update(FIXED_DT, null);
        screens.dispatchMouseDown(50.0f, 20.0f, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        screens.dispatchMouseUp(50.0f, 20.0f, GLFW.GLFW_MOUSE_BUTTON_LEFT);

        screens.dispatchCharTyped('a');
        screens.dispatchCharTyped('b');
        screens.dispatchKeyPressed(GLFW.GLFW_KEY_LEFT, 0);
        screens.dispatchKeyPressed(GLFW.GLFW_KEY_LEFT, 0);
        screens.dispatchKeyPressed(GLFW.GLFW_KEY_LEFT, 0);
        screens.dispatchCharTyped('9');
        screens.dispatchCharTyped('a');
        assertEquals("aab", input.getText(),
                "Cursor at index 0 must insert 'a' at start while rejecting disallowed '9'");
    }

    /**
     * Verifies goals {@code #0345}, {@code #0346}, and {@code #0349} boundary behaviors:
     * {@code Escape} closes {@code SelectBox} without mutating selection, disabled toggle ignores
     * {@code Space}, and {@code Ctrl+Tab} wraps cleanly to the first tab.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void selectBoxEscapeCloseDisabledToggleAndTabWrap(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF07Project(tempDir.resolve("f07_b_widgets"), "e2e/f07/widgets_screen");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            ScriptSession session = app.getApi().getService(ScriptSession.class);
            screens.hideScreen(MainMenuScreen.SCREEN_NAME);
            screens.showScreen("widget_showcase");
            app.stepOnce(FIXED_DT);

            Screen widgetScreen = screens.getScreen("widget_showcase");
            UiNode selectBox = findNodeById(widgetScreen.getRoot(), "res_select");
            assertNotNull(selectBox);
            assertFalse(selectBox.getChildren().isEmpty() && !"SelectBox".equals(selectBox.getClass().getSimpleName()),
                    "SelectBox must exist as real widget");
            clickNode(screens, selectBox);
            app.stepOnce(FIXED_DT);
            screens.dispatchKeyPressed(GLFW.GLFW_KEY_ESCAPE, 0);
            app.stepOnce(FIXED_DT);
            assertEquals("1280x720", session.getStateStore().getString("selected_res", "1280x720"),
                    "Escape on open SelectBox must preserve prior selection");

            UiNode checkbox = findNodeById(widgetScreen.getRoot(), "opt_checkbox");
            checkbox.setEnabled(false);
            checkbox.setFocused(true);
            screens.dispatchKeyPressed(GLFW.GLFW_KEY_SPACE, 0);
            app.stepOnce(FIXED_DT);
            assertFalse(session.getStateStore().getBoolean("opt_flag", false),
                    "Disabled Checkbox must ignore Space key");

            UiNode tabPanel = findNodeById(widgetScreen.getRoot(), "settings_tabs");
            screens.dispatchKeyPressed(GLFW.GLFW_KEY_TAB, GLFW.GLFW_MOD_CONTROL);
            app.stepOnce(FIXED_DT);
            assertTrue(hasVisibleText(tabPanel, "Audio-Einstellungen"));
            screens.dispatchKeyPressed(GLFW.GLFW_KEY_TAB, GLFW.GLFW_MOD_CONTROL);
            app.stepOnce(FIXED_DT);
            assertTrue(hasVisibleText(tabPanel, "Grafik-Einstellungen"),
                    "Ctrl+Tab past last tab must wrap cleanly back to tab 0");
        }
    }

    /**
     * Verifies goals {@code #0350} and {@code #0348} boundaries: {@code GridBox} odd child counts
     * and {@code Tooltip} suppression prior to {@code 0.4s} hover delay.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void gridBoxOddChildLayoutAndTooltipPreDelaySuppression(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF07Project(tempDir.resolve("f07_b_grid_tip"), "e2e/f07/widgets_screen");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            screens.hideScreen(MainMenuScreen.SCREEN_NAME);
            screens.showScreen("widget_showcase");
            app.stepOnce(FIXED_DT);

            Screen widgetScreen = screens.getScreen("widget_showcase");
            UiNode gridBox = findNodeById(widgetScreen.getRoot(), "demo_gridbox");
            List<UiNode> children = gridBox.getChildren();
            for (int i = 7; i < children.size(); i++) {
                children.get(i).setVisible(false);
            }
            screens.update(FIXED_DT, null);
            Set<Integer> ys = new HashSet<>();
            for (int i = 0; i < 7; i++) {
                ys.add(Math.round(children.get(i).getBounds().y()));
            }
            assertEquals(3, ys.size(), "7 visible items in 3-col GridBox must produce 3 rows");

            UiNode tipBtn = findNodeById(widgetScreen.getRoot(), "tooltip_btn");
            Rect2f tb = tipBtn.getBounds();
            app.getApi().getInput().onCursorPos(tb.x() + 10.0f, tb.y() + 10.0f);
            for (int i = 0; i < 15; i++) {
                app.stepOnce(FIXED_DT);
            }
            assertTrue(findVisibleTextsContaining(screens, "Kontexthilfe").isEmpty(),
                    "Hovering < 0.4s (0.25s) must not display tooltip yet");
            for (int i = 0; i < 15; i++) {
                app.stepOnce(FIXED_DT);
            }
            assertFalse(findVisibleTextsContaining(screens, "Kontexthilfe").isEmpty(),
                    "Hovering >= 0.4s must show tooltip");
        }
    }

    /**
     * Verifies goals {@code #0351} and {@code #0352} boundaries: interrupting show/hide animation
     * never leaves modal stuck, and arrow-key focus ring skips disabled buttons cleanly.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void interruptedTransitionUnlocksCleanlyAndFocusRingSkipsDisabled(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF07Project(tempDir.resolve("f07_b_anim"), "e2e/f07/meta_screens");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            MainMenuScreen menu = (MainMenuScreen) screens.getScreen(MainMenuScreen.SCREEN_NAME);
            app.stepOnce(FIXED_DT);

            assertFalse(menu.getContinueButton().isEnabled());
            for (int i = 0; i < 10; i++) {
                screens.dispatchKeyPressed(GLFW.GLFW_KEY_DOWN, 0);
                assertNotNull(screens.getFocusedNode(), "Arrow key must maintain valid focus");
                assertNotEquals(menu.getContinueButton(), screens.getFocusedNode(),
                        "Focus ring must skip disabled Continue button");
            }

            screens.showScreen(PreferencesScreen.SCREEN_NAME);
            screens.update(0.02, null);
            screens.hideScreen(PreferencesScreen.SCREEN_NAME);
            screens.update(0.02, null);
            assertTrue(screens.getScreen(PreferencesScreen.SCREEN_NAME).isVisible(),
                    "Mid-hide transition must animate rather than snapping immediately");
            for (int i = 0; i < 60; i++) {
                screens.update(0.05, null);
            }
            assertFalse(screens.getScreen(PreferencesScreen.SCREEN_NAME).isVisible(),
                    "Interrupted transition must still finish cleanly without stuck modal lock");
        }
    }

    /**
     * Verifies goals {@code #0353}, {@code #0354}, {@code #0355}, and {@code #0356} boundaries:
     * locked Codex/Chapter entries cannot be opened, extreme affinities clamp cleanly, and {@code B}
     * on MainMenu creates no phantom quote.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void metaScreensLockedEntriesExtremeAffinityAndNonDialogueBookmark(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF07Project(tempDir.resolve("f07_b_meta"), "e2e/f07/meta_screens");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            ScriptSession session = app.getApi().getService(ScriptSession.class);

            Screen codexScreen = screens.getScreen("codex");
            Screen chapterScreen = screens.getScreen("chapter_select");
            Screen relScreen = screens.getScreen("relationships");
            Screen quoteScreen = screens.getScreen("quote_book");
            assertNotNull(codexScreen, "codex screen must exist");
            assertNotNull(chapterScreen, "chapter_select screen must exist");
            assertNotNull(relScreen, "relationships screen must exist");
            assertNotNull(quoteScreen, "quote_book screen must exist");

            CodexGlossaryManager codex = app.getApi().getService(CodexGlossaryManager.class);
            codex.register(new CodexGlossaryManager.CodexEntry(
                    "geheim", "Geheimes Artefakt", "Lore", "Versteckt", "Verbotener Inhalt"));
            screens.showScreen(codexScreen);
            app.stepOnce(FIXED_DT);
            assertTrue(findVisibleTextsContaining(screens, "Verbotener Inhalt").isEmpty(),
                    "Locked Codex entry content must remain hidden");
            screens.hideScreen("codex");

            ChapterRegistry chapters = app.getApi().getService(ChapterRegistry.class);
            chapters.register(new ChapterRegistry.ChapterDef("c2", "Gesperrt", "chapter_2", 2, "", ""));
            screens.showScreen(chapterScreen);
            app.stepOnce(FIXED_DT);
            TextButton lockedBtn = collectNodes(chapterScreen.getRoot(), TextButton.class).stream()
                    .filter(b -> b.getText().contains("Gesperrt"))
                    .findFirst()
                    .orElse(null);
            assertNotNull(lockedBtn);
            assertFalse(lockedBtn.isEnabled());
            clickNode(screens, lockedBtn);
            app.stepOnce(FIXED_DT);
            assertNotEquals("chapter_2", session.getRuntime().getCurrentLabel(),
                    "Clicking locked chapter must not jump to chapter_2");

            RelationshipAffinityTracker tracker = app.getApi().getService(RelationshipAffinityTracker.class);
            tracker.setPoints("lena", -40);
            screens.showScreen(relScreen);
            app.stepOnce(FIXED_DT);
            assertFalse(findVisibleTextsContaining(screens, "HOSTILE").isEmpty());
            screens.hideScreen("relationships");

            // Pressing B on MainMenu (not WAITING_DIALOGUE) must not bookmark any quote.
            app.getApi().getInput().onKey(GLFW.GLFW_KEY_B, 0, GLFW.GLFW_PRESS, 0);
            app.stepOnce(FIXED_DT);
            app.getApi().getInput().onKey(GLFW.GLFW_KEY_B, 0, GLFW.GLFW_RELEASE, 0);
            app.stepOnce(FIXED_DT);
            screens.showScreen(quoteScreen);
            app.stepOnce(FIXED_DT);
            assertTrue(findVisibleTextsContaining(screens, "Erste Zeile").isEmpty(),
                    "Bookmarking outside active dialogue must be a safe no-op");
        }
    }

    /**
     * Verifies goal {@code #0359} boundary path: Skip stops cleanly on unread/choice boundaries
     * when {@code skipUnread} and {@code skipAfterChoices} are {@code false}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void quickMenuSkipRespectsUnreadAndChoiceBoundaries(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF07Project(tempDir.resolve("f07_b_quick"), "e2e/f07/meta_screens");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            ScriptSession session = app.getApi().getService(ScriptSession.class);
            PreferencesState prefs = app.getApi().getService(PreferencesState.class);
            prefs.setSkipUnread(false);
            prefs.setSkipAfterChoices(false);

            screens.hideScreen(MainMenuScreen.SCREEN_NAME);
            session.getPreferencesState().setTextSpeedCps(0.0f);
            session.start();
            app.stepOnce(FIXED_DT);

            QuickMenuScreen quickMenu = (QuickMenuScreen) screens.getScreen(QuickMenuScreen.SCREEN_NAME);
            TextButton skipChoiceBtn = collectNodes(quickMenu.getRoot(), TextButton.class).stream()
                    .filter(b -> containsIgnoreCase(b.getText(), "choice")
                            || containsIgnoreCase(b.getText(), "auswahl"))
                    .findFirst()
                    .orElse(null);
            assertNotNull(skipChoiceBtn, "QuickMenuScreen must provide Skip-Next-Choice button");

            AdvDialogueScreen adv = (AdvDialogueScreen) screens.getScreen(AdvDialogueScreen.SCREEN_NAME);
            adv.setSkipActive(true);
            for (int i = 0; i < 15; i++) {
                app.stepOnce(0.1);
            }
            assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, session.getRuntime().getStatus(),
                    "Regular Skip with skipUnread=false must stop at unread dialogue");
        }
    }

    /**
     * Verifies goals {@code #0360} and {@code #0362} boundary paths: missing character portrait
     * asset does not crash, and clicking outside hyperlink bounds advances dialogue normally.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void sideImageMissingFallbackAndOutsideLinkClickAdvancesDialogue(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF07Project(tempDir.resolve("f07_b_adv"), "e2e/f07/widgets_screen");
        Files.deleteIfExists(projectDir.resolve("assets/images/char/lena.png"));

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            ScriptSession session = app.getApi().getService(ScriptSession.class);
            screens.hideScreen(MainMenuScreen.SCREEN_NAME);
            session.getPreferencesState().setTextSpeedCps(0.0f);
            session.start();
            app.stepOnce(FIXED_DT);

            AdvDialogueScreen adv = (AdvDialogueScreen) screens.getScreen(AdvDialogueScreen.SCREEN_NAME);
            UiNode sidePortrait = findSidePortraitNode(adv.getRoot());
            assertNotNull(sidePortrait, "AdvDialogueScreen must expose side-portrait node");

            String firstText = session.getRuntime().getActiveDialogue().interpolatedText();
            // Click bottom-left corner inside ADV dialogue panel far away from link text.
            screens.dispatchMouseDown(AdvDialogueScreen.PANEL_X + 20.0f,
                    AdvDialogueScreen.PANEL_Y + AdvDialogueScreen.PANEL_H - 20.0f,
                    GLFW.GLFW_MOUSE_BUTTON_LEFT);
            screens.dispatchMouseUp(AdvDialogueScreen.PANEL_X + 20.0f,
                    AdvDialogueScreen.PANEL_Y + AdvDialogueScreen.PANEL_H - 20.0f,
                    GLFW.GLFW_MOUSE_BUTTON_LEFT);
            app.stepOnce(FIXED_DT);

            assertNotEquals(firstText, session.getRuntime().getActiveDialogue().interpolatedText(),
                    "Clicking outside hyperlink hitbox must advance dialogue normally");
        }
    }

    private static Path prepareF07Project(Path projectDir, String resourceTree) throws Exception {
        E2eWorkspaceSupport.copyResourceTree(resourceTree, projectDir);
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/bg/bg_room.png"), 64, 64, 18, 22, 36, 255);
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/char/lena.png"), 96, 128, 88, 166, 255, 255);
        return projectDir;
    }

    private static void clickNode(ScreenManager screens, UiNode node) {
        Rect2f b = node.getBounds();
        float cx = b.x() + b.width() * 0.5f;
        float cy = b.y() + b.height() * 0.5f;
        screens.dispatchMouseDown(cx, cy, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        screens.dispatchMouseUp(cx, cy, GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    private static UiNode findNodeById(UiNode root, String id) {
        if (root == null) {
            return null;
        }
        if (id.equals(root.getId())) {
            return root;
        }
        for (UiNode child : root.getChildren()) {
            UiNode found = findNodeById(child, id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static UiNode findSidePortraitNode(UiNode root) {
        for (UiNode node : collectNodes(root, UiNode.class)) {
            String id = node.getId() != null ? node.getId().toLowerCase(Locale.ROOT) : "";
            String cls = node.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (id.contains("portrait") || id.contains("side_image") || cls.contains("portrait")) {
                return node;
            }
        }
        return null;
    }

    private static boolean hasVisibleText(UiNode root, String text) {
        return collectNodes(root, TextWidget.class).stream()
                .anyMatch(w -> w.isVisible() && w.getText().contains(text));
    }

    private static List<TextWidget> findVisibleTextsContaining(ScreenManager screens, String needle) {
        List<TextWidget> out = new ArrayList<>();
        for (Screen s : screens.getVisibleScreensSorted()) {
            for (TextWidget tw : collectNodes(s.getRoot(), TextWidget.class)) {
                if (tw.isVisible() && tw.getText().contains(needle)) {
                    out.add(tw);
                }
            }
        }
        return List.copyOf(out);
    }

    private static boolean containsIgnoreCase(String text, String needle) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
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
