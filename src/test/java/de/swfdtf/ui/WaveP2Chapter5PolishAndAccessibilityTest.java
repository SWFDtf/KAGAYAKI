package de.swfdtf.ui;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.graphics.NineSliceDrawable;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SceneGraph;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.state.PreferencesState;
import de.swfdtf.state.RevertableStateStore;
import de.swfdtf.tools.AutomatedPlaythroughRunner;
import de.swfdtf.tools.ProjectTemplateGenerator;
import de.swfdtf.tools.VJavaLinter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless verification suite for Wave P2-5 {@link UiPolishAndServices}, built-in screen layout
 * overlap audits across resolutions, 200-item {@link VpGrid} virtualization, and widget gallery QA.
 */
class WaveP2Chapter5PolishAndAccessibilityTest {

    @Test
    @DisplayName("HudVisibilityController hides HUD with H/middle-click except during TextInput focus")
    void hudVisibilityIgnoresHWhenTyping() {
        UiPolishAndServices.HudVisibilityController hud = new UiPolishAndServices.HudVisibilityController();
        assertFalse(hud.toggleWithHKey(true));
        assertTrue(hud.toggleWithHKey(false));
        assertTrue(hud.isHudHidden());
        assertTrue(hud.restoreOnPrimaryClick());
        assertFalse(hud.isHudHidden());
        assertTrue(hud.toggleWithMiddleClick());
        assertFalse(hud.toggleWithMiddleClick());
    }

    @Test
    @DisplayName("SpeakerDimmingDirector dims silent sprites to 0.72 and restores active speaker in 0.15s")
    void speakerDimmingDirectorSmoothlyDimsAndBrightens() {
        SceneController sc = new SceneController(new SceneGraph());
        sc.registerImageDefinition("aria", "assets/aria.png");
        sc.registerImageDefinition("luna", "assets/luna.png");
        sc.showSprite("aria", List.of(), "left", null);
        sc.showSprite("luna", List.of(), "right", null);

        UiPolishAndServices.SpeakerDimmingDirector director = new UiPolishAndServices.SpeakerDimmingDirector();
        director.setActiveSpeakerTag("aria");
        director.update(sc, 0.20);
        assertEquals(1.0f, director.getSpriteBrightness("aria"), 0.01f);
        assertEquals(0.72f, director.getSpriteBrightness("luna"), 0.01f);

        director.setActiveSpeakerTag("luna");
        director.update(sc, 0.15);
        assertEquals(1.0f, director.getSpriteBrightness("luna"), 0.01f);
    }

    @Test
    @DisplayName("HotbarInventoryBinder sets $selected_item and fires item action callback")
    void hotbarInventoryBinderSetsSelectedItem() {
        UiPolishAndServices.HotbarInventoryBinder binder = new UiPolishAndServices.HotbarInventoryBinder();
        AtomicBoolean used = new AtomicBoolean(false);
        binder.registerItem("star_compass", () -> used.set(true));
        RevertableStateStore store = new RevertableStateStore();

        binder.selectItem("star_compass", store);
        assertEquals("star_compass", store.getString("selected_item", ""));
        assertTrue(used.get());
    }

    @Test
    @DisplayName("PromptGlyphMapper swaps glyphs dynamically for Keyboard, Xbox, PlayStation, Nintendo")
    void promptGlyphMapperAutoSwapsByDevice() {
        UiPolishAndServices.PromptGlyphMapper mapper = new UiPolishAndServices.PromptGlyphMapper();
        assertEquals("[Space]", mapper.getPromptGlyph("confirm"));
        mapper.setActiveFamily(UiPolishAndServices.InputDevicePromptFamily.XBOX);
        assertEquals("[A]", mapper.getPromptGlyph("confirm"));
        mapper.setActiveFamily(UiPolishAndServices.InputDevicePromptFamily.PLAYSTATION);
        assertEquals("[✕]", mapper.getPromptGlyph("confirm"));
        mapper.setActiveFamily(UiPolishAndServices.InputDevicePromptFamily.NINTENDO);
        assertEquals("[+]", mapper.getPromptGlyph("menu"));
    }

    @Test
    @DisplayName("UiSkinRegistry returns registered NineSliceDrawable skin or null for vector fallback")
    void uiSkinRegistryFallbackAndLookup() {
        UiPolishAndServices.UiSkinRegistry registry = new UiPolishAndServices.UiSkinRegistry();
        assertNull(registry.resolveSkinOrFallback("dialogue_box"));
        NineSliceDrawable custom = new NineSliceDrawable(7, 16, 16, 4, 4, 4, 4);
        registry.registerSkin("dialogue_box", custom);
        assertNotNull(registry.resolveSkinOrFallback("dialogue_box"));
    }

    @Test
    @DisplayName("UiSoundCueRouter emits hover/click/back/save cues and mutes cleanly at 0% SFX")
    void uiSoundCueRouterHonorsMute() {
        UiPolishAndServices.UiSoundCueRouter router = new UiPolishAndServices.UiSoundCueRouter();
        assertTrue(router.trigger(UiPolishAndServices.UiCue.HOVER));
        assertTrue(router.trigger(UiPolishAndServices.UiCue.CLICK));
        router.setSfxVolume(0.0f);
        assertFalse(router.trigger(UiPolishAndServices.UiCue.SAVE));
        assertEquals(2, router.getEmittedCues().size());
    }

    @Test
    @DisplayName("DialoguePunctuationPacer pauses 180ms on punctuation unless instant mode is active")
    void dialoguePunctuationPacerDelayVsInstant() {
        assertEquals(0.18, UiPolishAndServices.DialoguePunctuationPacer.computePauseSeconds('.', false, 40.0, 200.0),
                0.001);
        assertEquals(0.18, UiPolishAndServices.DialoguePunctuationPacer.computePauseSeconds('a', true, 40.0, 200.0),
                0.001);
        assertEquals(0.0, UiPolishAndServices.DialoguePunctuationPacer.computePauseSeconds('!', false, 220.0, 200.0),
                0.001);
    }

    @Test
    @DisplayName("WasdArenaHudGuide toggles visibility strictly in sync with wasd_init / wasd_stop")
    void wasdArenaHudGuideLifecycle() {
        UiPolishAndServices.WasdArenaHudGuide guide = new UiPolishAndServices.WasdArenaHudGuide();
        assertFalse(guide.isVisible());
        guide.syncFromWasdState(true);
        assertTrue(guide.isVisible());
        guide.syncFromWasdState(false);
        assertFalse(guide.isVisible());
    }

    @Test
    @DisplayName("UiWidgetTreeDumper outputs hierarchy classes and Rect2f bounds for Shift+U")
    void uiWidgetTreeDumperListsScreensAndBounds() {
        ScreenManager sm = new ScreenManager();
        Screen s = new Screen("test_ui", 200, false);
        TextButton btn = new TextButton("Save", null);
        btn.setId("save_btn");
        s.setRoot(new Fixed().addChildAt(btn, 40, 50, 180, 44));
        sm.showScreen(s);

        String dump = UiPolishAndServices.UiWidgetTreeDumper.dump(sm);
        assertTrue(dump.contains("Screen[test_ui"));
        assertTrue(dump.contains("TextButton#save_btn"));
        assertTrue(dump.contains("x=40.0"));
    }

    @Test
    @DisplayName("UiLayoutOverlapAuditor detects 0 sibling overlaps on built-in screens across 3 resolutions")
    void builtInScreensHaveZeroSiblingOverlapsAcrossResolutions(@TempDir Path tempDir) throws IOException {
        Path root = ProjectTemplateGenerator.generate(
                tempDir.resolve("starter_audit"),
                "Audit Demo",
                "2D",
                ProjectTemplateGenerator.TemplateType.STARTER
        );
        ScriptSession session = new ScriptSession(ProjectScanner.scan(root),
                new EngineAPI(EngineConfig.builder().projectDir(root).headless(true).build()));
        ScreenManager sm = new ScreenManager();
        List<Screen> screens = List.of(
                new MainMenuScreen(session, sm),
                new PreferencesScreen(session, sm),
                new ControlsScreen(sm),
                new AboutScreen(session, sm),
                new HistoryLogScreen(session, sm),
                new GalleryScreen(session, sm),
                new MusicRoomScreen(session, sm),
                new EndingsTreeScreen(session, sm),
                new AchievementsScreen(session, sm),
                new ChoiceMenuScreen(session),
                new InputOverlayScreen(session),
                new AdvDialogueScreen(session, sm),
                new NvlDialogueScreen(session),
                new BubbleDialogueScreen(session),
                new QuickMenuScreen(session, sm),
                new ConfirmationDialogScreen(sm)
        );
        assertEquals(List.of(), UiPolishAndServices.UiLayoutOverlapAuditor.findSiblingButtonOverlaps(
                screens, 1920.0f, 1080.0f));
        assertEquals(List.of(), UiPolishAndServices.UiLayoutOverlapAuditor.findSiblingButtonOverlaps(
                screens, 1280.0f, 720.0f));
        assertEquals(List.of(), UiPolishAndServices.UiLayoutOverlapAuditor.findSiblingButtonOverlaps(
                screens, 800.0f, 600.0f));
    }

    @Test
    @DisplayName("SaveSlotMetaManager renames slots, write-protects pinned slots, and jumps pages 1..9")
    void saveSlotMetaManagerRenamePinAndPageJump() {
        UiPolishAndServices.SaveSlotMetaManager meta = new UiPolishAndServices.SaveSlotMetaManager();
        meta.renameSlot("slot_1", "Vor dem Bosskampf");
        assertEquals("Vor dem Bosskampf", meta.getSlotLabel("slot_1"));

        meta.setPinned("slot_1", true);
        assertTrue(meta.isPinned("slot_1"));
        assertFalse(meta.canModifySlot("slot_1", false));
        assertTrue(meta.canModifySlot("slot_1", true));
        assertEquals(5, meta.jumpToPage(5, 6));
    }

    @Test
    @DisplayName("GameplayStatsPresenter pauses play timer in menus and formats completion badge")
    void gameplayStatsPausesDuringMenus() {
        UiPolishAndServices.GameplayStatsPresenter stats = new UiPolishAndServices.GameplayStatsPresenter();
        stats.tickPlayTime(12.5, false);
        stats.tickPlayTime(30.0, true); // Pause menu open -> ignored
        assertEquals(12.5, stats.getActivePlaySeconds(), 0.001);

        stats.updateStats(42, 3, 3);
        assertEquals(100, stats.getCompletionPercent());
        assertTrue(stats.formatMainMenuBadge().contains("100 % Abgeschlossen"));
    }

    @Test
    @DisplayName("AdaptiveDialogueBoxCalculator expands height above 4 lines and aligns speaker plaque")
    void adaptiveDialogueHeightAndNameAlignment() {
        assertEquals(250.0f, UiPolishAndServices.AdaptiveDialogueBoxCalculator.computeBoxHeight(250, 4, 28), 0.1f);
        assertEquals(306.0f, UiPolishAndServices.AdaptiveDialogueBoxCalculator.computeBoxHeight(250, 6, 28), 0.1f);
        assertEquals(
                UiPolishAndServices.AdaptiveDialogueBoxCalculator.NameBoxAlignment.LEFT,
                UiPolishAndServices.AdaptiveDialogueBoxCalculator.computeNameBoxAlignment(320.0f, 1920.0f)
        );
        assertEquals(
                UiPolishAndServices.AdaptiveDialogueBoxCalculator.NameBoxAlignment.RIGHT,
                UiPolishAndServices.AdaptiveDialogueBoxCalculator.computeNameBoxAlignment(1600.0f, 1920.0f)
        );
    }

    @Test
    @DisplayName("BubbleCollisionResolver separates overlapping bubbles and avoids character eye zones")
    void bubbleCollisionResolverSeparatesAndAvoidsEyes() {
        Rect2f eyeZone = new Rect2f(420, 300, 120, 60);
        List<Rect2f> resolved = UiPolishAndServices.BubbleCollisionResolver.resolveOverlaps(
                List.of(new Rect2f(400, 290, 220, 80), new Rect2f(410, 280, 220, 80)),
                List.of(eyeZone),
                8.0f
        );
        assertEquals(2, resolved.size());
        assertFalse(resolved.get(0).intersects(eyeZone));
        assertFalse(resolved.get(1).intersects(eyeZone));
        assertFalse(resolved.get(0).intersects(resolved.get(1)));
    }

    @Test
    @DisplayName("HoverGlossaryPreview auto-dismisses when advancing dialogue")
    void hoverGlossaryAutoClearsOnAdvance() {
        UiPolishAndServices.HoverGlossaryPreview preview = new UiPolishAndServices.HoverGlossaryPreview();
        preview.showTerm("spiegel", "Magisches Artefakt");
        assertTrue(preview.isVisible());
        preview.onDialogueAdvance();
        assertFalse(preview.isVisible());
    }

    @Test
    @DisplayName("GalleryViewerController and MusicRoomSeekController handle zoom, pan, double-click & seek")
    void galleryZoomAndMusicRoomSeek() {
        UiPolishAndServices.GalleryViewerController gallery = new UiPolishAndServices.GalleryViewerController();
        gallery.setZoom(2.5f);
        gallery.pan(120.0f, -60.0f);
        assertEquals(2.5f, gallery.getZoom(), 0.001f);
        assertEquals(1, gallery.switchVariation(1, 3));
        gallery.onDoubleClickReset();
        assertEquals(1.0f, gallery.getZoom(), 0.001f);
        assertEquals(0.0f, gallery.getPanX(), 0.001f);

        UiPolishAndServices.MusicRoomSeekController music = new UiPolishAndServices.MusicRoomSeekController();
        music.setTrackDurationSeconds(120.0);
        assertEquals(60.0, music.seekByProgressClick(0.5f), 0.01);
        assertTrue(music.toggleRepeatOne());
    }

    @Test
    @DisplayName("VpGrid virtualizes 200 items in O(visible) bounded window")
    void vpGridVirtualizesTwoHundredEntries() {
        VpGrid grid = new VpGrid(4, 200.0f, 80.0f);
        for (int i = 0; i < 200; i++) {
            grid.addChild(new TextButton("Entry " + i, null));
        }
        grid.layout(new Rect2f(0.0f, 0.0f, 860.0f, 320.0f));
        assertTrue(grid.getLastVisibleRow() - grid.getFirstVisibleRow() < 8);
    }

    @Test
    @DisplayName("Interactive UI Widget Gallery .vjava project passes VJavaLinter & AutomatedPlaythroughRunner")
    void uiWidgetGalleryProjectLintAndPlaythroughClean(@TempDir Path tempDir) throws IOException {
        Path projectDir = tempDir.resolve("widget_gallery");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("main.vjava"), """
                define guide = Character("UI-Guide", color="#58a6ff")
                default slider_val = 75

                label start:
                    guide "Willkommen in der interaktiven KAGAYAKI UI-Widget-Galerie!"
                    menu "Welches Widget testen?":
                        "Buttons & Slider":
                            $ slider_val = 90
                            guide "Slider & WrapBox aktiv."
                            return
                        "TabPanel & GridBox":
                            guide "TabPanel & GridBox aktiv."
                            return
                """, StandardCharsets.UTF_8);
        Files.writeString(projectDir.resolve("screens.vjava"), """
                screen widget_gallery_hud():
                    zorder 50
                    frame:
                        xalign 0.5
                        yalign 0.1
                        vbox:
                            spacing 8
                            text "UI Widget Gallery | Value: [slider_val]" size 20
                            textbutton "OK" action Hide("widget_gallery_hud")
                """, StandardCharsets.UTF_8);

        VJavaAst.ProjectAst ast = ProjectScanner.scan(projectDir);
        VJavaLinter.LintReport lint = VJavaLinter.lint(ast);
        assertEquals(0, lint.errorCount(), "Lint errors: " + lint.issues());
        assertEquals(0, lint.warningCount(), "Lint warnings: " + lint.issues());

        AutomatedPlaythroughRunner.PlaythroughReport report = new AutomatedPlaythroughRunner()
                .run(ast, AutomatedPlaythroughRunner.ChoiceStrategy.FIRST_CHOICE, 100);
        assertTrue(report.endedCleanly());

        PreferencesState p = new PreferencesState();
        Rect2f shifted = UiPolishAndServices.applySafezoneMargin(
                new Rect2f(0.0f, 0.0f, 200.0f, 40.0f),
                new PreferencesState(), 1920.0f, 1080.0f
        );
        assertEquals(0.0f, shifted.x(), 0.1f);
        p.setSafezoneMarginPx(24);
        Rect2f inset = UiPolishAndServices.applySafezoneMargin(
                new Rect2f(0.0f, 0.0f, 200.0f, 40.0f), p, 1920.0f, 1080.0f
        );
        assertEquals(24.0f, inset.x(), 0.1f);
    }
}
