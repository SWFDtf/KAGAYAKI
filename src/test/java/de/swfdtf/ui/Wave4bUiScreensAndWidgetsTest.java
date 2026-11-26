package de.swfdtf.ui;

import de.swfdtf.math.Rect2f;
import de.swfdtf.state.ChapterRegistry;
import de.swfdtf.state.CodexGlossaryManager;
import de.swfdtf.state.PersistentState;
import de.swfdtf.state.QuoteBook;
import de.swfdtf.state.RelationshipAffinityTracker;
import de.swfdtf.state.SimpleStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for Wave 4b UI widgets, containers, transitions, tooltips, and meta screens.
 */
class Wave4bUiScreensAndWidgetsTest {

    @Test
    void viewportHorizontalScrollAndDragInertiaClampCleanly() {
        Viewport vp = new Viewport();
        vp.setPreferredSize(300.0f, 150.0f);
        VBox box = new VBox();
        for (int i = 0; i < 20; i++) {
            TextWidget line = new TextWidget("Row " + i);
            line.setPreferredSize(750.0f, 30.0f);
            box.addChild(line);
        }
        vp.addChild(box);
        vp.layout(new Rect2f(0.0f, 0.0f, 300.0f, 150.0f));

        assertTrue(vp.getMaxScrollX() >= 400.0f);
        assertTrue(vp.getMaxScrollY() >= 400.0f);

        vp.dispatchMouseDown(100.0f, 120.0f, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        vp.dispatchMouseMove(60.0f, 70.0f);
        vp.dispatchMouseUp(40.0f, 40.0f, GLFW.GLFW_MOUSE_BUTTON_LEFT);

        float scrollAtReleaseX = vp.getScrollX();
        float scrollAtReleaseY = vp.getScrollY();
        for (int i = 0; i < 12; i++) {
            vp.update(0.016);
        }
        assertTrue(vp.getScrollX() > scrollAtReleaseX && vp.getScrollX() <= vp.getMaxScrollX());
        assertTrue(vp.getScrollY() > scrollAtReleaseY && vp.getScrollY() <= vp.getMaxScrollY());
    }

    @Test
    void textInputSupportsCursorLeftRightDeleteAndMidInsert() {
        TextInput input = new TextInput("", null).setAllowChars("abcd").setMaxLength(5);
        input.layout(new Rect2f(0.0f, 0.0f, 300.0f, 44.0f));
        input.setFocused(true);

        input.dispatchCharTyped('a');
        input.dispatchCharTyped('c');
        input.dispatchCharTyped('z');
        assertEquals("ac", input.getText());

        input.dispatchKeyPressed(GLFW.GLFW_KEY_LEFT, 0);
        input.dispatchCharTyped('b');
        assertEquals("abc", input.getText());

        input.dispatchKeyPressed(GLFW.GLFW_KEY_RIGHT, 0);
        input.dispatchCharTyped('d');
        assertEquals("abcd", input.getText());

        input.dispatchKeyPressed(GLFW.GLFW_KEY_HOME, 0);
        input.dispatchKeyPressed(GLFW.GLFW_KEY_DELETE, 0);
        assertEquals("bcd", input.getText());
    }

    @Test
    void selectBoxCheckboxToggleSwitchTabPanelAndGridBoxBehaviors() {
        SelectBox sb = new SelectBox(List.of("1280x720", "1920x1080"));
        sb.layout(new Rect2f(100.0f, 100.0f, 200.0f, 40.0f));
        assertEquals("1280x720", sb.getSelectedOption());
        sb.dispatchMouseDown(120.0f, 120.0f, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        assertTrue(sb.isExpanded());
        sb.dispatchKeyPressed(GLFW.GLFW_KEY_ESCAPE, 0);
        assertFalse(sb.isExpanded());
        assertEquals("1280x720", sb.getSelectedOption());

        Checkbox cb = new Checkbox("Option");
        cb.setFocused(true);
        cb.dispatchKeyPressed(GLFW.GLFW_KEY_SPACE, 0);
        assertTrue(cb.isChecked());
        cb.setEnabled(false);
        cb.dispatchKeyPressed(GLFW.GLFW_KEY_SPACE, 0);
        assertTrue(cb.isChecked());

        ToggleSwitch ts = new ToggleSwitch("Mode");
        ts.layout(new Rect2f(0.0f, 0.0f, 200.0f, 40.0f));
        ts.dispatchMouseDown(5.0f, 5.0f, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        assertTrue(ts.isToggled());

        TabPanel tp = new TabPanel();
        TextWidget tab1Text = new TextWidget("First");
        TextWidget tab2Text = new TextWidget("Second");
        tp.addTab("One", tab1Text);
        tp.addTab("Two", tab2Text);
        tp.layout(new Rect2f(0.0f, 0.0f, 500.0f, 300.0f));
        assertTrue(tab1Text.isVisible());
        assertFalse(tab2Text.isVisible());

        tp.dispatchKeyPressed(GLFW.GLFW_KEY_TAB, GLFW.GLFW_MOD_CONTROL);
        assertFalse(tab1Text.isVisible());
        assertTrue(tab2Text.isVisible());

        tp.dispatchKeyPressed(GLFW.GLFW_KEY_TAB, GLFW.GLFW_MOD_CONTROL);
        assertTrue(tab1Text.isVisible());

        GridBox grid = new GridBox(3).setSpacing(10.0f);
        for (int i = 0; i < 7; i++) {
            grid.addChild(new TextWidget("Item " + i));
        }
        grid.layout(new Rect2f(0.0f, 0.0f, 320.0f, 230.0f));
        assertEquals(grid.getChildren().get(0).getBounds().y(), grid.getChildren().get(2).getBounds().y(), 0.01f);
        assertTrue(grid.getChildren().get(6).getBounds().y() > grid.getChildren().get(3).getBounds().y());
    }

    @Test
    void screenTransitionAndMetaScreensBehavior(@TempDir Path tempDir) throws Exception {
        ScreenManager sm = new ScreenManager();
        sm.setLogicalSize(1920.0f, 1080.0f);

        Screen modal = new Screen("anim_modal", 400, true).setTransitionDurationSeconds(0.12);
        modal.setRoot(new Frame());
        sm.registerScreen(modal);
        sm.showScreen(modal);
        sm.update(0.02, null);
        sm.hideScreen("anim_modal");
        sm.update(0.02, null);
        assertTrue(modal.isVisible());
        assertTrue(modal.getRoot().getAlpha() > 0.0f);
        for (int i = 0; i < 10; i++) {
            sm.update(0.05, null);
        }
        assertFalse(modal.isVisible());

        PersistentState persistent = new PersistentState();
        persistent.bindSavesDir(tempDir);
        CodexGlossaryManager codex = new CodexGlossaryManager(persistent);
        codex.register(new CodexGlossaryManager.CodexEntry("e1", "Spiegel", "Lore", "Kurz", "Lang"));
        codex.unlock("e1");
        CodexScreen codexScreen = new CodexScreen(codex, sm);
        codexScreen.onShow();
        assertTrue(hasTextContaining(codexScreen.getRoot(), "Neu"));
        codexScreen.openEntry("e1");
        assertFalse(hasTextContaining(codexScreen.getRoot(), "Neu"));

        ChapterRegistry chapters = new ChapterRegistry(persistent);
        chapters.register(new ChapterRegistry.ChapterDef("c1", "Kapitel 1", "ch1", 1, "", ""));
        chapters.unlock("c1");

        RelationshipAffinityTracker rel = new RelationshipAffinityTracker(new SimpleStateStore());
        rel.setPoints("lena", 55);
        RelationshipOverviewScreen relScreen = new RelationshipOverviewScreen(rel, sm);
        relScreen.onShow();
        assertTrue(hasTextContaining(relScreen.getRoot(), "TRUSTED"));

        QuoteBook qb = new QuoteBook(persistent);
        qb.addQuote("Lena", "Ein Testzitat", "start");
        QuoteBook persistedAgain = new QuoteBook(persistent);
        assertEquals(1, persistedAgain.size());

        ConfirmationDialogScreen confirm = new ConfirmationDialogScreen(sm);
        AtomicBoolean confirmed = new AtomicBoolean(false);
        confirm.configure("Titel", "Frage", "Ja", "Nein", () -> confirmed.set(true), null);
        confirm.confirm();
        assertTrue(confirmed.get());
    }

    private static boolean hasTextContaining(UiNode node, String needle) {
        if (node == null || !node.isVisible()) {
            return false;
        }
        if (node instanceof TextWidget tw && tw.getText().contains(needle)) {
            return true;
        }
        for (UiNode child : node.getChildren()) {
            if (hasTextContaining(child, needle)) {
                return true;
            }
        }
        return false;
    }
}
