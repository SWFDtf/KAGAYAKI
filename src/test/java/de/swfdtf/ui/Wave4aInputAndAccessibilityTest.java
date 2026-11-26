package de.swfdtf.ui;

import de.swfdtf.audio.ClosedCaptionQueue;
import de.swfdtf.core.InputManager;
import de.swfdtf.core.KeybindingRemapper;
import de.swfdtf.state.PreferencesState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused unit tests for Wave 4a F06 input remapping, gamepad navigation, deadzone preferences,
 * closed captions overlay, and action prompts.
 */
class Wave4aInputAndAccessibilityTest {

    @Test
    void preferencesGamepadDeadzoneClampsAndPersists(@TempDir Path tempDir) throws Exception {
        PreferencesState prefs = new PreferencesState();
        assertEquals(0.15f, prefs.getGamepadDeadzone(), 0.001f);
        prefs.setGamepadDeadzone(-1.0f);
        assertEquals(0.15f, prefs.getGamepadDeadzone(), 0.001f);
        prefs.setGamepadDeadzone(1.5f);
        assertEquals(0.95f, prefs.getGamepadDeadzone(), 0.001f);
        prefs.setGamepadDeadzone(0.35f);

        Path savesDir = tempDir.resolve("saves");
        prefs.save(savesDir);
        PreferencesState loaded = PreferencesState.load(savesDir);
        assertEquals(0.35f, loaded.getGamepadDeadzone(), 0.001f);
    }

    @Test
    void closedCaptionAndActionPromptOverlaysWorkCleanly() {
        ClosedCaptionQueue queue = new ClosedCaptionQueue(3);
        ClosedCaptionOverlayScreen overlay = new ClosedCaptionOverlayScreen(queue);
        overlay.layout(1920.0f, 1080.0f);
        assertFalse(overlay.isVisible());

        queue.push(ClosedCaptionQueue.CaptionCategory.SFX, "A", "[First]", 2.0);
        queue.push(ClosedCaptionQueue.CaptionCategory.SFX, "B", "[Second]", 2.0);
        queue.push(ClosedCaptionQueue.CaptionCategory.SFX, "C", "[Third]", 2.0);
        queue.push(ClosedCaptionQueue.CaptionCategory.SFX, "D", "[Fourth]", 2.0);
        overlay.update(0.016);
        overlay.layout(1920.0f, 1080.0f);
        assertTrue(overlay.isVisible());
        List<TextWidget> visible = overlay.getCaptionWidgets().stream().filter(UiNode::isVisible).toList();
        assertEquals(3, visible.size());
        assertTrue(visible.stream().allMatch(w -> w.getBounds().y() >= 680.0f));
        assertTrue(visible.stream().noneMatch(w -> w.getText().contains("[First]")));

        queue.update(3.0);
        overlay.update(0.016);
        assertFalse(overlay.isVisible());

        ActionPromptOverlayScreen prompt = new ActionPromptOverlayScreen();
        prompt.updatePrompt(true, 400.0f, 500.0f, "F", "Untersuchen");
        assertTrue(prompt.isVisible());
        assertEquals("[F] Untersuchen", prompt.getPromptText().getText());
        prompt.updatePrompt(false, 0.0f, 0.0f, "", "");
        assertFalse(prompt.isVisible());
    }

    @Test
    void controlsScreenInteractiveCaptureAndGamepadRouterWork() {
        ScreenManager sm = new ScreenManager();
        sm.setLogicalSize(1920.0f, 1080.0f);

        KeybindingRemapper remapper = new KeybindingRemapper();
        remapper.registerDefault("Advance", GLFW.GLFW_KEY_SPACE);
        remapper.registerDefault("QuickSave", GLFW.GLFW_KEY_S);

        ControlsScreen controls = new ControlsScreen(sm, remapper);
        sm.registerScreen(controls);
        sm.showScreen(controls);
        sm.update(0.016, null);

        List<TextButton> rowButtons = controls.getRoot().getChildren().stream()
                .flatMap(n -> collectButtons(n).stream())
                .filter(b -> "SPACE".equals(b.getText()))
                .toList();
        assertEquals(1, rowButtons.size());
        TextButton advanceBtn = rowButtons.get(0);
        advanceBtn.click();
        assertEquals("Taste drücken...", advanceBtn.getText());
        assertTrue(controls.isCapturingKey());

        assertTrue(controls.triggerKeyBindings(GLFW.GLFW_KEY_ESCAPE, 0));
        assertFalse(controls.isCapturingKey());
        assertEquals(GLFW.GLFW_KEY_SPACE, remapper.getKeyCode("Advance").orElseThrow());

        advanceBtn.click();
        assertTrue(controls.triggerKeyBindings(GLFW.GLFW_KEY_S, 0));
        assertEquals(GLFW.GLFW_KEY_S, remapper.getKeyCode("Advance").orElseThrow());
        assertEquals(GLFW.GLFW_KEY_SPACE, remapper.getKeyCode("QuickSave").orElseThrow());
        sm.hideScreen(ControlsScreen.SCREEN_NAME);

        Screen menu = new Screen("test_menu", 400, true);
        VBox box = new VBox();
        TextButton b1 = new TextButton("First", () -> { });
        TextButton disabled = new TextButton("Disabled", () -> { });
        disabled.setEnabled(false);
        TextButton b2 = new TextButton("Second", () -> { });
        box.addChild(b1);
        box.addChild(disabled);
        box.addChild(b2);
        menu.setRoot(box);
        sm.showScreen(menu);

        InputManager input = new InputManager();
        boolean[] dpadDown = new boolean[InputManager.GAMEPAD_BUTTON_COUNT];
        dpadDown[GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN] = true;
        input.beginFrame();
        input.updateGamepadState(0, true, dpadDown, new float[InputManager.GAMEPAD_AXIS_COUNT]);
        sm.update(0.016, input);
        input.endFrame();
        assertEquals(b1, sm.getFocusedNode());

        input.beginFrame();
        input.updateGamepadState(0, true, new boolean[InputManager.GAMEPAD_BUTTON_COUNT],
                new float[InputManager.GAMEPAD_AXIS_COUNT]);
        sm.update(0.016, input);
        input.endFrame();

        input.beginFrame();
        input.updateGamepadState(0, true, dpadDown, new float[InputManager.GAMEPAD_AXIS_COUNT]);
        sm.update(0.016, input);
        input.endFrame();
        assertNotEquals(disabled, sm.getFocusedNode());
        assertEquals(b2, sm.getFocusedNode());
    }

    private static List<TextButton> collectButtons(UiNode node) {
        java.util.ArrayList<TextButton> out = new java.util.ArrayList<>();
        if (node instanceof TextButton tb) {
            out.add(tb);
        }
        for (UiNode child : node.getChildren()) {
            out.addAll(collectButtons(child));
        }
        return out;
    }
}
