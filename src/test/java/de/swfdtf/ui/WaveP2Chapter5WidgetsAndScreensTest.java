package de.swfdtf.ui;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineVersion;
import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.state.HistoryLog;
import de.swfdtf.state.PreferencesState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless verification suite for Wave P2-5 new UI widgets, screens, navigation stack,
 * choice enhancements, toasts, and {@link PreferencesState} extensions.
 */
class WaveP2Chapter5WidgetsAndScreensTest {

    @Test
    @DisplayName("WrapBox breaks sibling children onto new rows cleanly when width is exceeded")
    void wrapBoxBreaksLinesWithoutClipping() {
        WrapBox wrap = new WrapBox(10.0f, 12.0f);
        TextButton b1 = new TextButton("Alpha Button", null);
        b1.setPreferredSize(140.0f, 40.0f);
        TextButton b2 = new TextButton("Beta Button", null);
        b2.setPreferredSize(140.0f, 40.0f);
        TextButton b3 = new TextButton("Gamma Button", null);
        b3.setPreferredSize(140.0f, 40.0f);
        wrap.addChild(b1).addChild(b2).addChild(b3);

        wrap.layout(new Rect2f(0.0f, 0.0f, 300.0f, 200.0f));
        assertEquals(0.0f, b1.getBounds().y(), 0.1f);
        assertEquals(0.0f, b2.getBounds().y(), 0.1f);
        assertTrue(b3.getBounds().y() > 45.0f, "Third button should wrap to row 2");
        assertTrue(b3.getBounds().right() <= 300.0f);
    }

    @Test
    @DisplayName("SegmentedControl switches active index via click/keyboard and animates or snaps highlight")
    void segmentedControlSelectionAndReduceMotion() {
        AtomicInteger picked = new AtomicInteger(-1);
        SegmentedControl control = new SegmentedControl(
                List.of("Fenster", "Vollbild", "Rahmenlos"),
                (idx, label) -> picked.set(idx)
        );
        control.layout(new Rect2f(0.0f, 0.0f, 450.0f, 44.0f));
        assertTrue(control.dispatchMouseDown(400.0f, 22.0f, 0));
        assertEquals(2, control.getSelectedIndex());
        assertEquals(2, picked.get());
        assertEquals("Rahmenlos", control.getSelectedOption());

        control.update(0.02);
        assertTrue(control.getAnimatedIndex() > 0.0f && control.getAnimatedIndex() < 2.0f);

        control.setReduceMotion(true);
        control.setFocused(true);
        assertTrue(control.dispatchKeyPressed(GLFW.GLFW_KEY_LEFT, 0));
        assertEquals(1, control.getSelectedIndex());
        assertEquals(1.0f, control.getAnimatedIndex(), 0.001f);
    }

    @Test
    @DisplayName("ContextMenuOverlay opens inside logical 1920x1080 bounds and closes cleanly on outside click")
    void contextMenuClampingAndOutsideDismiss() {
        AtomicBoolean loaded = new AtomicBoolean(false);
        ContextMenuOverlay menu = new ContextMenuOverlay();
        menu.openAt(1880.0f, 1050.0f, List.of(
                new ContextMenuOverlay.MenuEntry("Laden", true, () -> loaded.set(true)),
                new ContextMenuOverlay.MenuEntry("Löschen", false, null)
        ));
        assertTrue(menu.isVisible());
        assertTrue(menu.getMenuBounds().right() <= 1920.0f);
        assertTrue(menu.getMenuBounds().bottom() <= 1080.0f);

        assertTrue(menu.handlePointerClick(10.0f, 10.0f, 0));
        assertFalse(menu.isVisible());
        assertFalse(loaded.get());

        menu.openAt(200.0f, 200.0f, List.of(
                new ContextMenuOverlay.MenuEntry("Laden", true, () -> loaded.set(true))));
        menu.getEntryButtons().get(0).click();
        assertTrue(loaded.get());
        assertFalse(menu.isVisible());
    }

    @Test
    @DisplayName("CreditsScreen scrolls vertically, accelerates on fast-forward, and triggers completion")
    void creditsScreenScrollsAndFinishes() {
        AtomicBoolean done = new AtomicBoolean(false);
        CreditsScreen credits = new CreditsScreen()
                .setSections(List.of(new CreditsScreen.CreditSection("Core", List.of("Alice", "Bob"))))
                .setScrollSpeed(100.0)
                .setOnFinished(() -> done.set(true));
        credits.setVisible(true);
        credits.onShow();
        float y0 = credits.getCurrentOffsetY();

        credits.update(1.0);
        assertEquals(y0 - 100.0f, credits.getCurrentOffsetY(), 0.1f);

        credits.setFastForward(true);
        for (int i = 0; i < 15 && !credits.isFinished(); i++) {
            credits.update(0.5);
        }
        assertTrue(credits.isFinished());
        assertTrue(done.get());
    }

    @Test
    @DisplayName("SplashScreen displays EngineVersion, tracks progress, and skips on click/key")
    void splashScreenVersionAndSkip() {
        AtomicBoolean completed = new AtomicBoolean(false);
        SplashScreen splash = new SplashScreen().setOnComplete(() -> completed.set(true));
        assertTrue(splash.getVersionBannerText().contains(EngineVersion.VERSION));
        splash.setProgress(0.45f, "Loading UI...");
        assertEquals(0.45f, splash.getProgress(), 0.001f);
        assertFalse(splash.isDismissed());

        assertTrue(splash.trySkip());
        assertTrue(splash.isDismissed());
        assertTrue(completed.get());
    }

    @Test
    @DisplayName("ChapterBannerOverlay fades smoothly over duration and cancels on skip")
    void chapterBannerFadeAndSkip() {
        ChapterBannerOverlay banner = new ChapterBannerOverlay();
        banner.showBanner("Kapitel 2", "Der dunkle Wald", 2.0);
        assertTrue(banner.isBannerActive());

        banner.update(0.25);
        assertEquals(1.0f, banner.getBannerAlpha(), 0.05f);
        banner.update(1.5);
        assertTrue(banner.getBannerAlpha() < 0.9f);

        banner.skip();
        assertFalse(banner.isBannerActive());
        assertFalse(banner.isVisible());
    }

    @Test
    @DisplayName("ProgressSpinnerModal updates spinner angle and progress without blocking render loop")
    void progressSpinnerNonBlockingUpdates() {
        ProgressSpinnerModal modal = new ProgressSpinnerModal();
        modal.startJob("Packing .vpak...", false);
        assertTrue(modal.isJobActive());

        modal.update(0.25);
        assertTrue(modal.getSpinnerAngleDegrees() > 0.0f);
        modal.updateProgress(0.6f, "Compressing textures...");
        assertEquals(0.6f, modal.getProgress(), 0.001f);

        modal.completeJob();
        assertFalse(modal.isJobActive());
    }

    @Test
    @DisplayName("ScreenManager pops 3-level sub-screen navigation stack in exact LIFO order")
    void screenManagerLifoBreadcrumbStack() {
        ScreenManager sm = new ScreenManager();
        Screen main = new Screen("main", 100, true);
        Screen prefs = new Screen("prefs", 200, true);
        Screen controls = new Screen("controls", 300, true);

        sm.pushSubScreen(main);
        sm.pushSubScreen(prefs);
        sm.pushSubScreen(controls);
        assertEquals(3, sm.getBreadcrumbDepth());

        assertTrue(sm.popSubScreen());
        assertFalse(controls.isVisible());
        assertTrue(prefs.isVisible());

        assertTrue(sm.popSubScreen());
        assertFalse(prefs.isVisible());
        assertTrue(main.isVisible());

        assertTrue(sm.popSubScreen());
        assertFalse(main.isVisible());
        assertFalse(sm.popSubScreen());
    }

    @Test
    @DisplayName("ScreenManager saves and restores button focus cleanly when modals open and close")
    void screenManagerModalFocusRestore() {
        ScreenManager sm = new ScreenManager();
        Screen base = new Screen("base", 100, false);
        VBox box = new VBox();
        TextButton btnA = new TextButton("Start", null);
        TextButton btnB = new TextButton("Prefs", null);
        box.addChild(btnA).addChild(btnB);
        base.setRoot(box);
        sm.showScreen(base);
        sm.cycleFocus(base, false);
        assertSame(btnA, sm.getFocusedNode());

        Screen modal = new Screen("modal", 400, true);
        sm.showScreen(modal);
        assertNull(sm.getFocusedNode());

        sm.hideScreenImmediate("modal");
        assertSame(btnA, sm.getFocusedNode());
        assertTrue(btnA.isFocused());
    }

    @Test
    @DisplayName("ScreenManager survives 10 script reloads with replaceScreen and resetFocusForReload")
    void screenManagerTenReloadsZeroStaleFocus() {
        ScreenManager sm = new ScreenManager();
        for (int i = 0; i < 10; i++) {
            Screen s = new Screen("choice_screen", 300, true);
            TextButton btn = new TextButton("Option " + i, null);
            VBox root = new VBox();
            root.addChild(btn);
            s.setRoot(root);
            sm.replaceScreen(s);
            sm.showScreen(s);
            sm.cycleFocus(s, false);
            assertSame(btn, sm.getFocusedNode());
        }
        sm.resetFocusForReload();
        assertNull(sm.getFocusedNode());
    }

    @Test
    @DisplayName("ScreenManager blocks click leak-through under modals and emits focus narration & cues")
    void screenManagerModalClickEatAndNarrator() {
        ScreenManager sm = new ScreenManager();
        AtomicBoolean underClicked = new AtomicBoolean(false);
        Screen under = new Screen("under", 100, false);
        TextButton underBtn = new TextButton("Under", () -> underClicked.set(true));
        Fixed fixed = new Fixed();
        fixed.addChildAt(underBtn, 100, 100, 200, 60);
        under.setRoot(fixed);
        sm.showScreen(under);

        Screen modal = new Screen("modal", 400, true);
        TextButton modalBtn = new TextButton("ModalBtn", null);
        modal.setRoot(new Fixed().addChildAt(modalBtn, 600, 600, 200, 60));
        sm.showScreen(modal);

        List<String> spoken = new ArrayList<>();
        List<UiPolishAndServices.UiCue> cues = new ArrayList<>();
        sm.setFocusNarrator(spoken::add);
        sm.setSoundCueListener(cues::add);

        assertTrue(sm.dispatchMouseDown(150, 130, 0));
        assertFalse(underClicked.get());
        sm.cycleFocus(modal, false);
        assertEquals(List.of("ModalBtn"), spoken);
        sm.hideScreenImmediate("modal");
        assertTrue(cues.contains(UiPolishAndServices.UiCue.HOVER));
        assertTrue(cues.contains(UiPolishAndServices.UiCue.BACK));
    }

    @Test
    @DisplayName("DragGroup cancels drag cleanly on right-click or out-of-bounds release")
    void dragGroupRightClickAndOutsideCancel() {
        DragGroup group = new DragGroup();
        group.layout(new Rect2f(100, 100, 500, 400));
        DragItem item = new DragItem("apple", "apple").setDraggable(true);
        item.layout(new Rect2f(150, 150, 60, 60));
        group.addDragItem(item);

        assertTrue(group.dispatchMouseDown(170, 170, 0));
        group.dispatchMouseMove(300, 300);
        assertEquals(280.0f, item.getBounds().x(), 1.0f);

        assertTrue(group.dispatchMouseDown(300, 300, 1)); // Right-click cancel
        assertNull(group.getActiveDraggedItem());
        assertEquals(150.0f, item.getBounds().x(), 0.1f);

        assertTrue(group.dispatchMouseDown(170, 170, 0));
        group.dispatchMouseMove(700, 700);
        assertTrue(group.dispatchMouseUp(700, 700, 0)); // Outside bounds release
        assertEquals(150.0f, item.getBounds().x(), 0.1f);
    }

    @Test
    @DisplayName("TextButton interpolates hover scale and disables animation under reduceMotion")
    void textButtonHoverScaleAndReduceMotion() {
        TextButton btn = new TextButton("Hover Me", null);
        btn.setFocused(true);
        btn.update(0.1);
        assertTrue(btn.getHoverScale() > 1.0f);

        btn.setReduceMotion(true);
        assertEquals(1.0f, btn.getHoverScale(), 0.001f);
        btn.update(0.1);
        assertEquals(1.0f, btn.getHoverScale(), 0.001f);
    }

    @Test
    @DisplayName("ChoiceMenuScreen supports 1..9 hotkeys, <25% red warning bar, disabled tooltips & badges")
    void choiceMenuHotkeysRedBarDisabledTooltipAndHistory(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("main.vjava"), """
                default courage = 5
                label start:
                    menu "Choose wisely":
                        timeout 10.0 -> jump timed_out_lbl
                        "Bold Path" if courage >= 1 disabled:
                            "Chose bold."
                            return
                        "Quiet Path":
                            "Chose quiet."
                            return
                label timed_out_lbl:
                    return
                """, StandardCharsets.UTF_8);
        VJavaAst.ProjectAst ast = ProjectScanner.scan(tempDir);
        ScriptSession session = new ScriptSession(ast, new EngineAPI(EngineConfig.defaults()));
        session.start("start");

        ChoiceMenuScreen menu = new ChoiceMenuScreen(session);
        menu.setChoiceBadge(1, "[★]");
        menu.setDisabledReason(0, "Requires courage >= 20");
        menu.bindChoiceHistory(session.getPersistentState(), "start");
        menu.syncFromRuntime(true);

        List<TextButton> btns = menu.getOptionButtons();
        assertEquals(2, btns.size());
        assertTrue(btns.get(1).getText().startsWith("[★]"));

        // Advance clock to <= 2.5s remaining (<= 25%) -> ERROR warning red
        session.update(8.0);
        menu.syncFromRuntime(false);
        assertEquals(ThemePalette.ERROR, menu.getTimeoutBar().getFillColorHex());

        assertFalse(menu.handleNumericHotkey(9));
        assertTrue(menu.handleNumericHotkey(2));
        assertTrue(UiPolishAndServices.ChoiceHistoryTracker.wasPreviouslyChosen(
                session.getPersistentState(), "start", "Quiet Path"));
    }

    @Test
    @DisplayName("NotificationToastOverlay stacks simultaneous toasts without overlapping slots")
    void notificationToastStacksWithoutOverlap(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("main.vjava"), "label start:\n    return\n", StandardCharsets.UTF_8);
        ScriptSession session = new ScriptSession(ProjectScanner.scan(tempDir), new EngineAPI(EngineConfig.defaults()));
        NotificationToastOverlay overlay = new NotificationToastOverlay(session, new ScreenManager());

        overlay.pushStackedToast("Erfolg 1", "Kapitel abgeschlossen", 1.0f, "★");
        overlay.pushStackedToast("Erfolg 2", "Schlüssel gefunden", 0.5f, "🔑");
        List<NotificationToastOverlay.StackedToast> stacked = overlay.getVisibleStackedToasts();
        assertEquals(2, stacked.size());
        assertTrue(stacked.get(1).slotY() - stacked.get(0).slotY() >= 90.0f);

        overlay.update(4.5);
        assertTrue(overlay.getVisibleStackedToasts().isEmpty());
    }

    @Test
    @DisplayName("HistoryLogScreen formats clean copyable text stripping Rich-Text tags")
    void historyLogFormatsCopyableEntry() {
        HistoryLog.HistoryEntry entry = new HistoryLog.HistoryEntry(
                1L, 1L, "l1", "Schneewittchen", "#58a6ff",
                "Wie {b}hell{/b} der {color=#gold}Spiegel{/color} leuchtet!", "", Instant.now()
        );
        assertEquals(
                "Schneewittchen: Wie hell der Spiegel leuchtet!",
                HistoryLogScreen.formatCopyableEntry(entry)
        );
    }

    @Test
    @DisplayName("ThemePalette inherits custom project accent hex or falls back to Dark Slate ACCENT_HOVER")
    void themePaletteProjectAccentInheritance() {
        ProjectDescriptor desc = ProjectDescriptor.createDefault("Accent Demo");
        assertEquals(ThemePalette.ACCENT_HOVER, ThemePalette.resolveProjectAccentHex(desc));
        desc.setAccentColorHex("#d4af37");
        assertEquals("#d4af37", ThemePalette.resolveProjectAccentHex(desc));
    }

    @Test
    @DisplayName("PreferencesState clamps and persists textboxOpacity, reduceMotion, safezone & cursor")
    void preferencesStateWave5RoundTrip(@TempDir Path tempDir) throws IOException {
        PreferencesState prefs = new PreferencesState();
        prefs.setTextboxOpacity(0.45f);
        prefs.setReduceMotion(true);
        prefs.setSafezoneMarginPx(120); // clamps to 64
        prefs.setAccessibilityCursorStyle("large_yellow");
        prefs.setTextBackdropMode("dark_strip");
        prefs.setSafeLetterboxUltrawide(true);
        prefs.setChoiceNumberHotkeysEnabled(false);
        prefs.save(tempDir);

        PreferencesState reloaded = PreferencesState.load(tempDir);
        assertEquals(0.45f, reloaded.getTextboxOpacity(), 0.001f);
        assertTrue(reloaded.isReduceMotion());
        assertEquals(64, reloaded.getSafezoneMarginPx());
        assertEquals("LARGE_YELLOW", reloaded.getAccessibilityCursorStyle());
        assertEquals("DARK_STRIP", reloaded.getTextBackdropMode());
        assertTrue(reloaded.isSafeLetterboxUltrawide());
        assertFalse(reloaded.isChoiceNumberHotkeysEnabled());
    }
}
