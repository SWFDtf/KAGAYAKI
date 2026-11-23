package de.swfdtf.core;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputManagerTest {

    @Test
    void tracksKeyTransitionsModifiersAndUnicodeStream() {
        InputManager input = new InputManager();
        input.beginFrame();
        input.onKey(GLFW.GLFW_KEY_LEFT_SHIFT, 0, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SHIFT);
        input.onKey(GLFW.GLFW_KEY_R, 0, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SHIFT);
        input.onChar('K');
        input.onChar('ä');

        assertTrue(input.isShiftDown());
        assertTrue(input.isKeyDown(GLFW.GLFW_KEY_R));
        assertTrue(input.wasKeyJustPressed(GLFW.GLFW_KEY_R));
        assertFalse(input.wasKeyJustReleased(GLFW.GLFW_KEY_R));
        assertEquals("Kä", input.drainTypedChars());
        assertEquals("", input.drainTypedChars());

        input.endFrame();
        input.beginFrame();
        assertTrue(input.isKeyDown(GLFW.GLFW_KEY_R));
        assertFalse(input.wasKeyJustPressed(GLFW.GLFW_KEY_R));

        input.onKey(GLFW.GLFW_KEY_R, 0, GLFW.GLFW_RELEASE, 0);
        assertFalse(input.isKeyDown(GLFW.GLFW_KEY_R));
        assertTrue(input.wasKeyJustReleased(GLFW.GLFW_KEY_R));
        input.endFrame();
    }

    @Test
    void mapsMouseThroughLetterboxViewportAndTracksButtonsAndScroll() {
        InputManager input = new InputManager();
        input.setLogicalSize(1920, 1080);
        // 1920x1200 window -> 16:9 viewport is 1920x1080 centered at (0, 60)
        input.updateLetterboxViewport(1920, 1200);

        input.beginFrame();
        input.onCursorPos(960.0, 600.0);
        assertEquals(960.0, input.getLogicalMouseX(), 1e-6);
        assertEquals(540.0, input.getLogicalMouseY(), 1e-6);
        assertTrue(input.isMouseInsideViewport());

        input.onCursorPos(960.0, 10.0);
        assertFalse(input.isMouseInsideViewport());
        assertTrue(input.getLogicalMouseY() < 0.0);
        assertEquals(0.0, input.getClampedLogicalMouseY(), 1e-6);

        input.onMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
        assertTrue(input.isMouseButtonDown(0));
        assertTrue(input.wasMouseButtonJustPressed(0));

        input.onScroll(0.0, 3.0);
        assertEquals(3.0, input.getScrollY(), 1e-6);
        assertEquals(3.0, input.consumeScrollY(), 1e-6);
        assertEquals(0.0, input.consumeScrollY(), 1e-6);

        input.endFrame();
        assertFalse(input.wasMouseButtonJustPressed(0));
    }

    @Test
    void tracksGamepadButtonsAndDeadzoneAxes() {
        InputManager input = new InputManager();
        boolean[] buttons = new boolean[InputManager.GAMEPAD_BUTTON_COUNT];
        buttons[GLFW.GLFW_GAMEPAD_BUTTON_A] = true;
        float[] axes = new float[InputManager.GAMEPAD_AXIS_COUNT];
        axes[GLFW.GLFW_GAMEPAD_AXIS_LEFT_X] = 0.08f;
        axes[GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y] = -0.85f;

        input.beginFrame();
        input.updateGamepadState(0, true, buttons, axes);
        assertTrue(input.isGamepadConnected());
        assertEquals(0, input.getActiveGamepadId());
        assertTrue(input.isGamepadButtonDown(GLFW.GLFW_GAMEPAD_BUTTON_A));
        assertTrue(input.wasGamepadButtonJustPressed(GLFW.GLFW_GAMEPAD_BUTTON_A));
        assertEquals(0.0f, input.getGamepadAxis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X), 1e-6f);
        assertEquals(-0.85f, input.getGamepadAxis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y), 1e-6f);
        input.endFrame();

        input.beginFrame();
        buttons[GLFW.GLFW_GAMEPAD_BUTTON_A] = false;
        input.updateGamepadState(0, true, buttons, axes);
        assertFalse(input.isGamepadButtonDown(GLFW.GLFW_GAMEPAD_BUTTON_A));
        assertTrue(input.wasGamepadButtonJustReleased(GLFW.GLFW_GAMEPAD_BUTTON_A));
        input.endFrame();
    }
}
