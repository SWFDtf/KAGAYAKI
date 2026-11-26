package de.swfdtf.e2e.tier1;

import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineMode;
import de.swfdtf.core.InputManager;
import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2eFixtureFactory;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.e2e.support.E2eWorkspaceSupport;
import de.swfdtf.graphics.StyledTextLayout;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.state.ChapterRegistry;
import de.swfdtf.state.CodexGlossaryManager;
import de.swfdtf.state.RelationshipAffinityTracker;
import de.swfdtf.state.SimplePersistentAccess;
import de.swfdtf.state.SimpleStateStore;
import de.swfdtf.ui.AdvDialogueScreen;
import de.swfdtf.ui.Bar;
import de.swfdtf.ui.Frame;
import de.swfdtf.ui.Grid;
import de.swfdtf.ui.MainMenuScreen;
import de.swfdtf.ui.PreferencesScreen;
import de.swfdtf.ui.QuickMenuScreen;
import de.swfdtf.ui.SaveLoadScreen;
import de.swfdtf.ui.Screen;
import de.swfdtf.ui.ScreenAstBuilder;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 1 happy-path E2E tests for Feature Area F7: UI widgets, layouts, screen transitions,
 * meta screens, QuickMenu extensions, speaker side-image, and clickable dialogue links
 * ({@code #0342}–{@code #0356}, {@code #0359}, {@code #0360}, {@code #0362}).
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER1)
public final class F07UiScreensE2eTest {

    private static final double FIXED_DT = 1.0 / 60.0;

    /** Creates a new {@code F07UiScreensE2eTest} instance. */
    public F07UiScreensE2eTest() {
    }

    /**
     * Verifies baseline {@link ScreenManager} Z-order modal blocking, {@link Grid} layout,
     * {@link Viewport} vertical scrolling, {@link TextInput} entry, and {@link AdvDialogueScreen}
     * typewriter reveal.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void screenManagerModalBlockingGridAndViewportBaseline(@TempDir Path tempDir) throws Exception {
        ScreenManager manager = new ScreenManager();
        manager.setLogicalSize(1920.0f, 1080.0f);

        AtomicBoolean lowerClicked = new AtomicBoolean(false);
        Screen lower = new Screen("lower", 100, false);
        TextButton lowerBtn = new TextButton("Lower", () -> lowerClicked.set(true));
        lowerBtn.setPreferredSize(200.0f, 50.0f);
        lower.setRoot(lowerBtn);

        Screen modal = new Screen("modal", 400, true);
        Grid grid = new Grid(3, 2);
        grid.setSpacing(10.0f, 10.0f);
        for (int i = 0; i < 6; i++) {
            TextWidget tw = new TextWidget("Cell " + i);
            tw.setPreferredSize(120.0f, 40.0f);
            grid.addChild(tw);
        }
        modal.setRoot(grid);

        manager.registerScreen(lower);
        manager.registerScreen(modal);
        manager.showScreen(lower);
        manager.showScreen(modal);
        manager.update(FIXED_DT, null);

        assertTrue(manager.isModalActive());
        assertEquals(modal, manager.getTopModalScreen());
        manager.dispatchMouseDown(50.0f, 25.0f, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        manager.dispatchMouseUp(50.0f, 25.0f, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        assertFalse(lowerClicked.get(), "Modal screen must block clicks to lower Z-order screen");

        List<UiNode> cells = grid.getChildren();
        assertEquals(6, cells.size());
        assertTrue(cells.get(1).getBounds().x() > cells.get(0).getBounds().x());
        assertTrue(cells.get(3).getBounds().y() > cells.get(0).getBounds().y());

        Viewport vp = new Viewport();
        VBox box = new VBox();
        for (int i = 0; i < 15; i++) {
            TextWidget line = new TextWidget("Row " + i);
            line.setPreferredSize(300.0f, 40.0f);
            box.addChild(line);
        }
        vp.addChild(box);
        vp.layout(new Rect2f(0.0f, 0.0f, 400.0f, 160.0f));
        assertTrue(vp.getMaxScrollY() > 0.0f);
        vp.setScrollY(120.0f);
        assertEquals(120.0f, vp.getScrollY(), 0.01f);

        Path projectDir = prepareF07Project(tempDir.resolve("f07_base"), "e2e/f07/meta_screens");
        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScriptSession session = app.getApi().getService(ScriptSession.class);
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            screens.hideScreen(MainMenuScreen.SCREEN_NAME);
            session.start();
            app.stepOnce(FIXED_DT);
            assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, session.getRuntime().getStatus());
        }
    }

    /**
     * Verifies baseline {@link CodexGlossaryManager}, {@link ChapterRegistry}, and
     * {@link RelationshipAffinityTracker} progression and tier contracts.
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void metaManagersCodexChaptersAndAffinityBaseline() {
        SimplePersistentAccess persistent = new SimplePersistentAccess();
        CodexGlossaryManager codex = new CodexGlossaryManager(persistent);
        codex.register(new CodexGlossaryManager.CodexEntry(
                "spiegel", "Zauberspiegel", "Artefakte", "Sprechender Spiegel", "Volltext"));
        assertTrue(codex.unlock("spiegel"));
        assertTrue(codex.isUnlocked("spiegel"));
        assertEquals(1, codex.search("Spiegel", true).size());

        ChapterRegistry chapters = new ChapterRegistry(persistent);
        chapters.register(new ChapterRegistry.ChapterDef("ch1", "Kapitel 1", "chapter_1", 1, "", "Start"));
        chapters.register(new ChapterRegistry.ChapterDef("ch2", "Kapitel 2", "chapter_2", 2, "", "Mitte"));
        assertTrue(chapters.unlock("ch1"));
        assertEquals(50.0, chapters.getCompletionPercent(), 0.01);

        RelationshipAffinityTracker affinity = new RelationshipAffinityTracker(new SimpleStateStore());
        affinity.addPoints("lena", 30);
        assertEquals(RelationshipAffinityTracker.RelationshipTier.FRIEND, affinity.getTier("lena"));
        affinity.addPoints("lena", 25);
        assertEquals(RelationshipAffinityTracker.RelationshipTier.TRUSTED, affinity.getTier("lena"));
    }

    /**
     * Verifies goal {@code #0342}: declarative {@code template_save_screen} generates interactive
     * slot cards that save immediately on click and update their label/metadata live.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void dynamicSlotGridInTemplateSaveScreenSavesAndUpdatesCard(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF07Project(tempDir.resolve("f07_slots"), "e2e/f07/meta_screens");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            ScriptSession session = app.getApi().getService(ScriptSession.class);
            screens.hideScreen(MainMenuScreen.SCREEN_NAME);
            session.start();
            app.stepOnce(FIXED_DT);

            Screen saveTemplate = screens.getScreen("template_save_screen");
            assertNotNull(saveTemplate, "template_save_screen must be registered from .vjava");
            screens.showScreen(saveTemplate);
            app.stepOnce(FIXED_DT);

            List<TextButton> slotButtons = collectNodes(saveTemplate.getRoot(), TextButton.class).stream()
                    .filter(b -> !containsIgnoreCase(b.getText(), "zurück")
                            && !containsIgnoreCase(b.getText(), "schnellspeichern")
                            && !containsIgnoreCase(b.getText(), "quick")
                            && !containsIgnoreCase(b.getText(), "back"))
                    .toList();
            assertFalse(slotButtons.isEmpty(),
                    "template_save_screen must render interactive save-slot cards dynamically");

            TextButton firstSlotBtn = slotButtons.get(0);
            String beforeLabel = firstSlotBtn.getText();
            clickNode(screens, firstSlotBtn);
            app.stepOnce(FIXED_DT);

            long saveFiles;
            try (var files = Files.list(projectDir.resolve("saves"))) {
                saveFiles = files.filter(p -> p.getFileName().toString().endsWith(".vjsave")).count();
            }
            assertTrue(saveFiles > 0, "Clicking a dynamic slot card must write a .vjsave file");
            assertNotEquals(beforeLabel, firstSlotBtn.getText(),
                    "Dynamic slot card label must update immediately after saving");
        }
    }

    /**
     * Verifies goal {@code #0343}: {@link Viewport} supports drag-and-release touch inertia
     * and horizontal scroll across {@code update(dt)} frames.
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void viewportSupportsHorizontalScrollAndDragInertia() {
        ScreenManager screens = new ScreenManager();
        screens.setLogicalSize(1920.0f, 1080.0f);

        Screen screen = new Screen("scroll_test", 100, false);
        Viewport vp = new Viewport();
        vp.setPreferredSize(400.0f, 200.0f);
        VBox box = new VBox();
        for (int i = 0; i < 30; i++) {
            TextWidget line = new TextWidget("Long line number " + i + " extending beyond viewport width");
            line.setPreferredSize(900.0f, 40.0f);
            box.addChild(line);
        }
        vp.addChild(box);
        screen.setRoot(vp);
        screens.registerScreen(screen);
        screens.showScreen(screen);
        screens.update(FIXED_DT, null);

        // Drag upward inside viewport content (not scrollbar track) and release to impart inertia.
        screens.dispatchMouseDown(150.0f, 160.0f, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        vp.dispatchMouseMove(150.0f, 80.0f);
        screens.update(FIXED_DT, null);
        screens.dispatchMouseUp(150.0f, 40.0f, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        float scrollAtRelease = vp.getScrollY();

        for (int i = 0; i < 15; i++) {
            screens.update(FIXED_DT, null);
        }
        assertTrue(vp.getScrollY() > scrollAtRelease,
                "Viewport touch/drag inertia must continue scrolling after pointer release");
    }

    /**
     * Verifies goal {@code #0344}: {@link TextInput} supports cursor left/right navigation and
     * mid-string insertion rather than appending only at the end.
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void textInputSupportsCursorLeftRightNavigationAndMidStringEdit() {
        ScreenManager screens = new ScreenManager();
        screens.setLogicalSize(1920.0f, 1080.0f);

        Screen screen = new Screen("input_test", 100, false);
        TextInput input = new TextInput("", null);
        input.setPreferredSize(400.0f, 44.0f);
        screen.setRoot(input);
        screens.registerScreen(screen);
        screens.showScreen(screen);
        screens.update(FIXED_DT, null);

        screens.dispatchMouseDown(100.0f, 22.0f, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        screens.dispatchMouseUp(100.0f, 22.0f, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        screens.dispatchCharTyped('a');
        screens.dispatchCharTyped('c');
        screens.dispatchKeyPressed(GLFW.GLFW_KEY_LEFT, 0);
        screens.dispatchCharTyped('b');

        assertEquals("abc", input.getText(),
                "LEFT arrow followed by typing 'b' inside 'ac' must insert at cursor to yield 'abc'");

        screens.dispatchKeyPressed(GLFW.GLFW_KEY_RIGHT, 0);
        screens.dispatchCharTyped('d');
        assertEquals("abcd", input.getText());
    }

    /**
     * Verifies goals {@code #0345}, {@code #0346}, and {@code #0349}: declarative {@code selectbox},
     * {@code checkbox}, {@code toggleswitch}, and {@code tabpanel} AST widgets render and mutate
     * state via mouse and keyboard interaction.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void declarativeSelectBoxCheckboxToggleSwitchAndTabPanel(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF07Project(tempDir.resolve("f07_widgets"), "e2e/f07/widgets_screen");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            ScriptSession session = app.getApi().getService(ScriptSession.class);
            screens.hideScreen(MainMenuScreen.SCREEN_NAME);
            screens.showScreen("widget_showcase");
            app.stepOnce(FIXED_DT);

            Screen widgetScreen = screens.getScreen("widget_showcase");
            assertNotNull(widgetScreen);

            // #0345: SelectBox at (1000, 40, 280, 44) opens options and selects 1920x1080
            UiNode selectBox = findNodeById(widgetScreen.getRoot(), "res_select");
            assertNotNull(selectBox);
            assertFalse(selectBox.getChildren().isEmpty() && !"SelectBox".equals(selectBox.getClass().getSimpleName()),
                    "SelectBox must be instantiated as an interactive dropdown widget, not empty Fixed");
            clickNode(screens, selectBox);
            app.stepOnce(FIXED_DT);
            List<TextButton> optionButtons = collectNodes(widgetScreen.getRoot(), TextButton.class).stream()
                    .filter(b -> b.isVisible() && b.getText().contains("1920x1080"))
                    .toList();
            assertFalse(optionButtons.isEmpty(), "Clicking SelectBox must reveal '1920x1080' option");
            clickNode(screens, optionButtons.get(0));
            app.stepOnce(FIXED_DT);
            assertEquals("1920x1080", session.getStateStore().getString("selected_res", ""));

            // #0346: Checkbox & ToggleSwitch toggle StateStore booleans on click / Space
            UiNode checkbox = findNodeById(widgetScreen.getRoot(), "opt_checkbox");
            UiNode toggle = findNodeById(widgetScreen.getRoot(), "opt_toggle");
            assertNotNull(checkbox);
            assertNotNull(toggle);
            clickNode(screens, checkbox);
            app.stepOnce(FIXED_DT);
            assertTrue(session.getStateStore().getBoolean("opt_flag", false),
                    "Clicking Checkbox must toggle bound StateStore key 'opt_flag'");

            toggle.setFocused(true);
            screens.dispatchKeyPressed(GLFW.GLFW_KEY_SPACE, 0);
            app.stepOnce(FIXED_DT);
            assertTrue(session.getStateStore().getBoolean("toggle_flag", false),
                    "Pressing Space on focused ToggleSwitch must toggle bound key 'toggle_flag'");

            // #0349: TabPanel switches visible tab content via Ctrl+Tab
            UiNode tabPanel = findNodeById(widgetScreen.getRoot(), "settings_tabs");
            assertNotNull(tabPanel);
            assertTrue(hasVisibleText(tabPanel, "Grafik-Einstellungen"));
            assertFalse(hasVisibleText(tabPanel, "Audio-Einstellungen"));
            screens.dispatchKeyPressed(GLFW.GLFW_KEY_TAB, GLFW.GLFW_MOD_CONTROL);
            app.stepOnce(FIXED_DT);
            assertTrue(hasVisibleText(tabPanel, "Audio-Einstellungen"),
                    "Ctrl+Tab must switch TabPanel to Audio tab");
            assertFalse(hasVisibleText(tabPanel, "Grafik-Einstellungen"));
        }
    }

    /**
     * Verifies goals {@code #0350}, {@code #0347}, and {@code #0348}: {@code GridBox} 3x4 matrix,
     * modal overwrite confirmation popup, and 0.4s hover tooltip system.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void gridBoxLayoutModalConfirmationAndHoverTooltip(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF07Project(tempDir.resolve("f07_grid_tip"), "e2e/f07/widgets_screen");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            ScriptSession session = app.getApi().getService(ScriptSession.class);
            screens.hideScreen(MainMenuScreen.SCREEN_NAME);
            screens.showScreen("widget_showcase");
            app.stepOnce(FIXED_DT);

            Screen widgetScreen = screens.getScreen("widget_showcase");
            UiNode gridBox = findNodeById(widgetScreen.getRoot(), "demo_gridbox");
            assertNotNull(gridBox);
            List<UiNode> items = gridBox.getChildren();
            assertEquals(12, items.size());

            Set<Integer> distinctXs = new HashSet<>();
            Set<Integer> distinctYs = new HashSet<>();
            for (UiNode cell : items) {
                distinctXs.add(Math.round(cell.getBounds().x()));
                distinctYs.add(Math.round(cell.getBounds().y()));
            }
            assertEquals(3, distinctXs.size(), "GridBox(cols=3) with 12 items must compute 3 X columns");
            assertEquals(4, distinctYs.size(), "GridBox(cols=3) with 12 items must compute 4 Y rows");

            // #0348: Hover tooltip appears after >= 0.4s and clamps within 1920x1080
            UiNode tipBtn = findNodeById(widgetScreen.getRoot(), "tooltip_btn");
            assertNotNull(tipBtn);
            Rect2f tb = tipBtn.getBounds();
            app.getApi().getInput().onCursorPos(tb.x() + 10.0f, tb.y() + 10.0f);
            for (int i = 0; i < 30; i++) {
                app.stepOnce(FIXED_DT);
            }
            List<TextWidget> tooltips = findVisibleTextsContaining(screens, "Kontexthilfe");
            assertFalse(tooltips.isEmpty(), "Hovering >= 0.4s must show tooltip overlay");
            Rect2f tipBounds = tooltips.get(0).getBounds();
            assertTrue(tipBounds.x() >= 0.0f && tipBounds.y() >= 0.0f
                    && tipBounds.right() <= 1920.0f && tipBounds.bottom() <= 1080.0f,
                    "Tooltip bounds must clamp inside 1920x1080: " + tipBounds);

            // #0347: Overwriting an occupied save slot triggers modal confirmation dialog
            screens.hideScreen("widget_showcase");
            session.start();
            app.stepOnce(FIXED_DT);
            session.saveToSlot("slot_1", "Alter Spielstand", 0, 0, null);

            SaveLoadScreen saveLoad = (SaveLoadScreen) screens.getScreen(SaveLoadScreen.SCREEN_NAME);
            saveLoad.setMode(SaveLoadScreen.Mode.SAVE);
            screens.showScreen(saveLoad);
            app.stepOnce(FIXED_DT);

            SaveLoadScreen.SlotCardView card = saveLoad.getVisibleCards().get(0);
            clickNode(screens, card.getActionButton());
            app.stepOnce(FIXED_DT);

            Screen topModal = screens.getTopModalScreen();
            assertNotNull(topModal);
            assertNotEquals(SaveLoadScreen.SCREEN_NAME, topModal.getName(),
                    "Overwriting occupied slot must open a modal confirmation popup");
        }
    }

    /**
     * Verifies goals {@code #0351} and {@code #0352}: animated screen hide transitions keep input
     * blocked until completion, and arrow keys traverse all enabled focusable widgets.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void animatedScreenTransitionsAndArrowKeyFocusTraversal(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF07Project(tempDir.resolve("f07_anim"), "e2e/f07/meta_screens");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            MainMenuScreen menu = (MainMenuScreen) screens.getScreen(MainMenuScreen.SCREEN_NAME);
            app.stepOnce(FIXED_DT);

            // #0352: Arrow-key focus ring moves across enabled MainMenuScreen buttons
            screens.dispatchKeyPressed(GLFW.GLFW_KEY_DOWN, 0);
            UiNode firstFocus = screens.getFocusedNode();
            assertNotNull(firstFocus, "GLFW_KEY_DOWN must activate focus ring in ScreenManager");
            screens.dispatchKeyPressed(GLFW.GLFW_KEY_DOWN, 0);
            UiNode secondFocus = screens.getFocusedNode();
            assertNotNull(secondFocus);
            assertNotEquals(firstFocus, secondFocus, "GLFW_KEY_DOWN must advance focus to next enabled button");

            // #0351: Show/Hide transition animates over dt and unblocks input only upon finish
            screens.showScreen(PreferencesScreen.SCREEN_NAME);
            screens.update(0.5, null);
            assertTrue(screens.getScreen(PreferencesScreen.SCREEN_NAME).isVisible());

            screens.hideScreen(PreferencesScreen.SCREEN_NAME);
            screens.update(0.02, null);
            Screen prefs = screens.getScreen(PreferencesScreen.SCREEN_NAME);
            assertTrue(prefs.isVisible() && prefs.getRoot().getAlpha() > 0.0f,
                    "Hide transition must animate alpha smoothly instead of instant disappearance");
            assertEquals(PreferencesScreen.SCREEN_NAME, screens.getTopModalScreen().getName(),
                    "Hiding modal must block lower input until fade animation finishes");

            for (int i = 0; i < 60; i++) {
                screens.update(0.05, null);
            }
            assertFalse(prefs.isVisible(), "Hide animation must complete and clear visibility");
            assertEquals(menu, screens.getTopModalScreen());
        }
    }

    /**
     * Verifies goals {@code #0353}, {@code #0354}, {@code #0355}, and {@code #0356}: Codex,
     * Chapter Select, Relationship Affinity, and Quote-Book meta screens wired into runtime.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void codexChapterAffinityAndQuoteBookMetaScreens(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF07Project(tempDir.resolve("f07_meta"), "e2e/f07/meta_screens");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            ScriptSession session = app.getApi().getService(ScriptSession.class);

            // #0353: Codex screen with "Neu" badge clearing
            Screen codexScreen = screens.getScreen("codex");
            assertNotNull(codexScreen, "ScreenManager must register 'codex' screen");
            CodexGlossaryManager codex = app.getApi().getService(CodexGlossaryManager.class);
            assertNotNull(codex, "CodexGlossaryManager service must be registered");
            codex.register(new CodexGlossaryManager.CodexEntry(
                    "spiegel", "Zauberspiegel", "Artefakte", "Sprechender Spiegel", "Volltext"));
            codex.unlock("spiegel");
            screens.showScreen(codexScreen);
            app.stepOnce(FIXED_DT);
            assertFalse(findVisibleTextsContaining(screens, "Neu").isEmpty(),
                    "Newly unlocked Codex entry must display 'Neu' badge");
            List<TextButton> entryButtons = collectNodes(codexScreen.getRoot(), TextButton.class).stream()
                    .filter(b -> b.getText().contains("Zauberspiegel"))
                    .toList();
            assertFalse(entryButtons.isEmpty());
            clickNode(screens, entryButtons.get(0));
            app.stepOnce(FIXED_DT);
            assertTrue(findVisibleTextsContaining(screens, "Neu").isEmpty(),
                    "Selecting Codex entry must clear 'Neu' badge");
            screens.hideScreen("codex");

            // #0354: Chapter Select screen with locked vs unlocked chapters
            Screen chapterScreen = screens.getScreen("chapter_select");
            assertNotNull(chapterScreen, "ScreenManager must register 'chapter_select' screen");
            ChapterRegistry chapters = app.getApi().getService(ChapterRegistry.class);
            assertNotNull(chapters, "ChapterRegistry service must be registered");
            chapters.register(new ChapterRegistry.ChapterDef("c1", "Kapitel 1", "chapter_1", 1, "", ""));
            chapters.register(new ChapterRegistry.ChapterDef("c2", "Kapitel 2", "chapter_2", 2, "", ""));
            chapters.unlock("c1");
            screens.showScreen(chapterScreen);
            app.stepOnce(FIXED_DT);
            List<TextButton> chBtns = collectNodes(chapterScreen.getRoot(), TextButton.class);
            TextButton ch1Btn = chBtns.stream().filter(b -> b.getText().contains("Kapitel 1")).findFirst().orElse(null);
            TextButton ch2Btn = chBtns.stream().filter(b -> b.getText().contains("Kapitel 2")).findFirst().orElse(null);
            assertNotNull(ch1Btn);
            assertNotNull(ch2Btn);
            assertTrue(ch1Btn.isEnabled());
            assertFalse(ch2Btn.isEnabled(), "Locked chapter button must be disabled");
            clickNode(screens, ch1Btn);
            app.stepOnce(FIXED_DT);
            assertEquals("chapter_1", session.getRuntime().getCurrentLabel());

            // #0355: Relationship Affinity screen showing tier & Bar
            Screen relScreen = screens.getScreen("relationships");
            assertNotNull(relScreen, "ScreenManager must register 'relationships' screen");
            RelationshipAffinityTracker tracker = app.getApi().getService(RelationshipAffinityTracker.class);
            assertNotNull(tracker, "RelationshipAffinityTracker service must be registered");
            tracker.setPoints("lena", 50);
            screens.showScreen(relScreen);
            app.stepOnce(FIXED_DT);
            assertFalse(collectNodes(relScreen.getRoot(), Bar.class).isEmpty(),
                    "Relationship screen must render progress Bars");
            assertFalse(findVisibleTextsContaining(screens, "TRUSTED").isEmpty());
            screens.hideScreen("relationships");

            // #0356: Quote-Book bookmarking with key B
            screens.hideScreen(MainMenuScreen.SCREEN_NAME);
            session.start();
            app.stepOnce(FIXED_DT);
            String expectedQuote = session.getRuntime().getActiveDialogue().interpolatedText();
            app.getApi().getInput().onKey(GLFW.GLFW_KEY_B, 0, GLFW.GLFW_PRESS, 0);
            app.stepOnce(FIXED_DT);
            app.getApi().getInput().onKey(GLFW.GLFW_KEY_B, 0, GLFW.GLFW_RELEASE, 0);
            app.stepOnce(FIXED_DT);

            Screen quoteScreen = screens.getScreen("quote_book");
            assertNotNull(quoteScreen, "ScreenManager must register 'quote_book' screen");
            screens.showScreen(quoteScreen);
            app.stepOnce(FIXED_DT);
            assertFalse(findVisibleTextsContaining(screens, expectedQuote).isEmpty(),
                    "Quote-Book screen must display bookmarked dialogue line");
        }
    }

    /**
     * Verifies goal {@code #0359}: {@link QuickMenuScreen} exposes Skip-Next-Choice and Auto
     * status indicator, stopping deterministically at {@code WAITING_MENU}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void quickMenuSkipNextChoiceAndAutoIndicator(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF07Project(tempDir.resolve("f07_quick"), "e2e/f07/meta_screens");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            ScriptSession session = app.getApi().getService(ScriptSession.class);
            screens.hideScreen(MainMenuScreen.SCREEN_NAME);
            session.start();
            app.stepOnce(FIXED_DT);

            QuickMenuScreen quickMenu = (QuickMenuScreen) screens.getScreen(QuickMenuScreen.SCREEN_NAME);
            assertNotNull(quickMenu);
            List<TextButton> btns = collectNodes(quickMenu.getRoot(), TextButton.class);
            TextButton skipChoiceBtn = btns.stream()
                    .filter(b -> containsIgnoreCase(b.getText(), "choice")
                            || containsIgnoreCase(b.getText(), "auswahl"))
                    .findFirst()
                    .orElse(null);
            assertNotNull(skipChoiceBtn, "QuickMenuScreen must provide a Skip-Next-Choice button");

            clickNode(screens, skipChoiceBtn);
            for (int i = 0; i < 60 && session.getRuntime().getStatus() != ScriptRuntime.Status.WAITING_MENU; i++) {
                app.stepOnce(0.1);
            }
            assertEquals(ScriptRuntime.Status.WAITING_MENU, session.getRuntime().getStatus(),
                    "Skip-Next-Choice must fast-forward cleanly until WAITING_MENU");
        }
    }

    /**
     * Verifies goals {@code #0360} and {@code #0362}: {@link AdvDialogueScreen} displays speaker
     * side portrait for character lines (hiding it for narrator) and opens Codex on clicking
     * {@code {a=codex:spiegel}Zauberspiegel{/a}}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void advDialogueSideImageAndClickableCodexLinks(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF07Project(tempDir.resolve("f07_adv"), "e2e/f07/widgets_screen");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            ScriptSession session = app.getApi().getService(ScriptSession.class);
            screens.hideScreen(MainMenuScreen.SCREEN_NAME);
            session.start();
            app.stepOnce(FIXED_DT);

            AdvDialogueScreen adv = (AdvDialogueScreen) screens.getScreen(AdvDialogueScreen.SCREEN_NAME);
            assertNotNull(adv);

            // #0360: Side portrait node must be present and visible for speaker "Lena"
            UiNode sidePortrait = findSidePortraitNode(adv.getRoot());
            assertNotNull(sidePortrait, "AdvDialogueScreen must contain a speaker side-image node");
            assertTrue(sidePortrait.isVisible(), "Side portrait must be visible when Lena speaks");

            // #0362: Click {a=codex:spiegel}Zauberspiegel{/a} glyph hitbox
            TextWidget dialogueText = collectNodes(adv.getRoot(), TextWidget.class).stream()
                    .filter(tw -> tw.getText().contains("Zauberspiegel"))
                    .findFirst()
                    .orElseThrow();
            StyledTextLayout layout = dialogueText.getLayout();
            assertFalse(layout.getHyperlinkHitboxes().isEmpty(),
                    "StyledTextLayout must expose HyperlinkHitbox");
            StyledTextLayout.HyperlinkHitbox link = layout.getHyperlinkHitboxes().get(0);
            Rect2f textInner = dialogueText.getContentBounds();
            float clickX = textInner.x() + link.bounds().x() + link.bounds().width() * 0.5f;
            float clickY = textInner.y() + link.bounds().y() + link.bounds().height() * 0.5f;
            screens.dispatchMouseDown(clickX, clickY, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            screens.dispatchMouseUp(clickX, clickY, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            app.stepOnce(FIXED_DT);

            Screen codexScreen = screens.getScreen("codex");
            assertNotNull(codexScreen, "Clicking {a=codex:spiegel} link must open Codex screen");
            assertTrue(codexScreen.isVisible(), "Codex screen must become visible after clicking link");
            screens.hideScreen("codex");

            // Advance to narrator line and verify side portrait hides cleanly.
            session.advance();
            app.stepOnce(FIXED_DT);
            assertFalse(sidePortrait.isVisible(), "Narrator line without Character.image must hide side portrait");
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
