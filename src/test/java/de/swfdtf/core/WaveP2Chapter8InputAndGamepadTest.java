package de.swfdtf.core;

import de.swfdtf.core.InputDeviceAndAccessibilityServices.AccessibilityHoldController;
import de.swfdtf.core.InputDeviceAndAccessibilityServices.ActionInputBuffer;
import de.swfdtf.core.InputDeviceAndAccessibilityServices.ClipboardBridge;
import de.swfdtf.core.InputDeviceAndAccessibilityServices.ControllerGlyphStyle;
import de.swfdtf.core.InputDeviceAndAccessibilityServices.CursorAndFocusPolicy;
import de.swfdtf.core.InputDeviceAndAccessibilityServices.CursorAndFocusPolicy.WindowBounds;
import de.swfdtf.core.InputDeviceAndAccessibilityServices.GamepadDeviceProfiles;
import de.swfdtf.core.InputDeviceAndAccessibilityServices.InMemoryClipboardBridge;
import de.swfdtf.core.InputDeviceAndAccessibilityServices.KeyRepeatController;
import de.swfdtf.core.InputDeviceAndAccessibilityServices.KeyboardLayoutScancodeMapper;
import de.swfdtf.core.InputDeviceAndAccessibilityServices.KeyboardLayoutScancodeMapper.Layout;
import de.swfdtf.core.InputDeviceAndAccessibilityServices.OneHandControlPreset;
import de.swfdtf.core.InputDeviceAndAccessibilityServices.PointerEvent;
import de.swfdtf.core.InputDeviceAndAccessibilityServices.RumbleEffect;
import de.swfdtf.core.InputDeviceAndAccessibilityServices.StickVector;
import de.swfdtf.core.InputDeviceAndAccessibilityServices.TouchGesture;
import de.swfdtf.core.InputDeviceAndAccessibilityServices.TouchGestureRecognizer;
import de.swfdtf.state.PreferencesState;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless unit tests covering Chapter 8 InputManager, KeybindingRemapper, Gamepad profiles,
 * Touch gestures, Accessibility Hold/Repeat/Presets, Keyboard layouts, and Cursor/Focus policies
 * (Wave P2-8).
 */
class WaveP2Chapter8InputAndGamepadTest {

    @Test
    void testInputManagerDoubleClickDebounceBootGhostZeroViewportAndTrace() {
        InputManager input = new InputManager();
        input.beginFrame();
        input.onCursorPos(200.0, 200.0);
        input.onMouseButtonWithTimestampNanos(0, GLFW.GLFW_PRESS, 0, 100_000_000L);
        input.onMouseButtonWithTimestampNanos(0, GLFW.GLFW_RELEASE, 0, 110_000_000L);
        assertFalse(input.isMouseDoubleClicked(0));
        input.endFrame();

        input.beginFrame();
        input.onCursorPos(202.0, 202.0);
        input.onMouseButtonWithTimestampNanos(0, GLFW.GLFW_PRESS, 0, 300_000_000L);
        assertTrue(input.isMouseDoubleClicked(0));
        input.onMouseButtonWithTimestampNanos(0, GLFW.GLFW_RELEASE, 0, 310_000_000L);
        input.endFrame();

        // Mechanical switch debounce (#0628)
        input.setMinClickIntervalMs(40);
        input.beginFrame();
        input.onMouseButtonWithTimestampNanos(1, GLFW.GLFW_PRESS, 0, 1_000_000_000L);
        input.onMouseButtonWithTimestampNanos(1, GLFW.GLFW_RELEASE, 0, 1_005_000_000L);
        input.endFrame();
        input.beginFrame();
        input.onMouseButtonWithTimestampNanos(1, GLFW.GLFW_PRESS, 0, 1_015_000_000L);
        assertFalse(input.wasMouseButtonJustPressed(1));
        input.endFrame();

        // Boot ghost suppression (#0656) & minimize/zero viewport (#0678) & OOB keycodes (#0642)
        input.armBootGhostSuppression(GLFW.GLFW_KEY_ENTER);
        input.beginFrame();
        input.onKey(GLFW.GLFW_KEY_ENTER, 0, GLFW.GLFW_PRESS, 0);
        assertFalse(input.isKeyDown(GLFW.GLFW_KEY_ENTER));
        input.onKey(GLFW.GLFW_KEY_ENTER, 0, GLFW.GLFW_RELEASE, 0);
        input.onKey(GLFW.GLFW_KEY_ENTER, 0, GLFW.GLFW_PRESS, 0);
        assertTrue(input.isKeyDown(GLFW.GLFW_KEY_ENTER));

        assertDoesNotThrow(() -> input.onKey(-1, 0, GLFW.GLFW_PRESS, 0));
        assertDoesNotThrow(() -> input.onKey(99999, 0, GLFW.GLFW_PRESS, 0));
        input.updateLetterboxViewport(0, 0);
        assertTrue(Double.isFinite(input.getLogicalMouseX()) && Double.isFinite(input.getLogicalMouseY()));

        // Hotplug & trace (#0600, #0639, #0675)
        List<String> traces = new ArrayList<>();
        AtomicBoolean toastTriggered = new AtomicBoolean();
        input.setTraceEnabled(true, traces::add);
        input.setGamepadConnectionListener((jid, connected, name) -> {
            if (connected) {
                toastTriggered.set(true);
            }
        });
        input.beginFrame();
        input.onGamepadHotPlug(0, true, "DualSense Wireless Controller");
        assertTrue(toastTriggered.get());
        assertFalse(input.getFrameInputEvents().isEmpty());
        assertFalse(traces.isEmpty());
        input.endFrame();
    }

    @Test
    void testKeybindingRemapperMultiBindContextsLocalizationAndProjectDefaults() {
        KeybindingRemapper remapper = new KeybindingRemapper();
        remapper.registerDefault("advance", GLFW.GLFW_KEY_SPACE);
        remapper.registerDefault("jump", GLFW.GLFW_KEY_J);
        remapper.setActionContext("advance", "UI");
        remapper.setActionContext("jump", "ARENA");

        remapper.addAlternateKeyBinding("advance", GLFW.GLFW_KEY_ENTER);
        remapper.addMouseBinding("advance", GLFW.GLFW_MOUSE_BUTTON_4);

        InputManager input = new InputManager();
        input.beginFrame();
        input.onMouseButton(GLFW.GLFW_MOUSE_BUTTON_4, GLFW.GLFW_PRESS, 0);
        assertTrue(remapper.isActionDown("advance", input));
        assertTrue(remapper.isActionJustPressed("advance", input));
        input.endFrame();

        // Cross-context binding allowed (#0670)
        assertTrue(remapper.findContextConflict("jump", GLFW.GLFW_KEY_SPACE).isEmpty());
        remapper.rebind("jump", GLFW.GLFW_KEY_K);
        remapper.applyProjectDefaults(Map.of("jump", GLFW.GLFW_KEY_U, "dash", GLFW.GLFW_KEY_LEFT_SHIFT));
        // Player override on jump must be preserved (#0637)
        assertEquals(GLFW.GLFW_KEY_K, remapper.getKeyCode("jump").orElseThrow());
        assertEquals(GLFW.GLFW_KEY_LEFT_SHIFT, remapper.getKeyCode("dash").orElseThrow());

        // Single-action reset (#0643) & localization (#0653)
        remapper.resetAction("jump");
        assertEquals(GLFW.GLFW_KEY_U, remapper.getKeyCode("jump").orElseThrow());
        assertEquals("Leertaste", KeybindingRemapper.localizeKeyName(GLFW.GLFW_KEY_SPACE, "deutsch"));
        assertEquals("Space", KeybindingRemapper.localizeKeyName(GLFW.GLFW_KEY_SPACE, "english"));
    }

    @Test
    void testActionBufferRepeatRateTouchGesturesAndOneHandPresets() {
        // #0604 120 ms jump buffer
        ActionInputBuffer buf = new ActionInputBuffer();
        buf.enqueue("jump");
        buf.update(0.080);
        assertTrue(buf.consume("jump"));
        buf.enqueue("jump");
        buf.update(0.150);
        assertFalse(buf.consume("jump"));

        // #0605 400 ms delay + 80 ms repeat
        KeyRepeatController repeat = new KeyRepeatController();
        assertEquals(1, repeat.update(true, 0.0));
        assertEquals(0, repeat.update(true, 0.350));
        assertEquals(1, repeat.update(true, 0.060));
        assertEquals(1, repeat.update(true, 0.080));
        assertEquals(0, repeat.update(false, 0.016));

        // #0606 two-finger tap & #0671 horizontal swipe >= 80 px within 400 ms
        TouchGestureRecognizer recognizer = new TouchGestureRecognizer();
        recognizer.onPointerEvent(new PointerEvent(1, "TOUCH", "DOWN", 300, 300, 100));
        recognizer.onPointerEvent(new PointerEvent(2, "TOUCH", "DOWN", 340, 300, 120));
        recognizer.onPointerEvent(new PointerEvent(1, "TOUCH", "UP", 300, 300, 180));
        assertEquals(TouchGesture.TWO_FINGER_MENU,
                recognizer.onPointerEvent(new PointerEvent(2, "TOUCH", "UP", 340, 300, 200)));

        recognizer.onPointerEvent(new PointerEvent(3, "TOUCH", "DOWN", 500, 300, 500));
        assertEquals(TouchGesture.SWIPE_LEFT,
                recognizer.onPointerEvent(new PointerEvent(3, "TOUCH", "UP", 380, 305, 650)));

        assertTrue(TouchGestureRecognizer.isTouchOverlayVisible(true, true));
        assertFalse(TouchGestureRecognizer.isTouchOverlayVisible(true, false));

        // #0608 One-hand preset
        assertTrue(OneHandControlPreset.LEFT_HAND_KEYBOARD.buildActionKeyMap().containsKey("quicksave"));
    }

    @Test
    void testAccessibilityHoldLayoutsGamepadProfilesRumbleAndCursorFocus() {
        AccessibilityHoldController hold = new AccessibilityHoldController();
        // #0627 toggle skip
        assertTrue(hold.updateSkipState(true, true, true));
        assertTrue(hold.updateSkipState(true, false, false));
        assertFalse(hold.updateSkipState(true, true, true));

        // #0630 0.7 s hold-to-confirm cancels on release
        assertFalse(hold.updateHoldToConfirm(true, 0.40));
        assertFalse(hold.updateHoldToConfirm(false, 0.01));
        assertEquals(0.0, hold.getHoldConfirmProgress(), 1e-6);
        assertFalse(hold.updateHoldToConfirm(true, 0.40));
        assertTrue(hold.updateHoldToConfirm(true, 0.35));

        // #0660 300 ms gamepad skip delay
        assertFalse(hold.updateGamepadSkipWithDelay(true, 0.250));
        assertTrue(hold.updateGamepadSkipWithDelay(true, 0.060));
        assertFalse(hold.updateGamepadSkipWithDelay(false, 0.016));

        // #0668 & #0609
        assertTrue(hold.updateMashOrHold(true, true, false, 1.2) >= 1.0 - 1e-6);
        assertTrue(hold.updateHoldKeyAutoAdvance(true, 4, 4.0, 1.05));

        // #0611 AZERTY ZQSD & #0619 macOS Cmd alias & #0644 sticky key check
        assertEquals(GLFW.GLFW_KEY_Z, KeyboardLayoutScancodeMapper.resolveDirectionalKeys(Layout.AZERTY).get("up"));
        assertEquals(GLFW.GLFW_KEY_Q, KeyboardLayoutScancodeMapper.resolveDirectionalKeys(Layout.AZERTY).get("left"));
        InputManager macInput = new InputManager();
        macInput.beginFrame();
        macInput.onKey(GLFW.GLFW_KEY_LEFT_SUPER, 0, GLFW.GLFW_PRESS, 0);
        assertTrue(KeyboardLayoutScancodeMapper.isPlatformCommandDown(macInput, "Mac OS X"));
        assertTrue(KeyboardLayoutScancodeMapper.isStickyKeyFriendly(GLFW.GLFW_KEY_SPACE, false));

        // #0601, #0610, #0620, #0640, #0646, #0647, #0672
        GamepadDeviceProfiles profiles = new GamepadDeviceProfiles();
        int count = profiles.loadSdlMappings("030000004c050000e60c000011810000,PS5 Controller,platform:Mac");
        assertEquals(1, count);
        assertTrue(profiles.findByGuid("030000004c050000e60c000011810000").isPresent());
        assertEquals(0.5f, GamepadDeviceProfiles.normalizeTrigger(0.0f), 1e-4f);
        assertEquals(ControllerGlyphStyle.PLAYSTATION, GamepadDeviceProfiles.detectGlyphStyle(0x054C, 0, ""));
        assertTrue(GamepadDeviceProfiles.shouldAutoPauseOnDisconnect(true, false, true));
        assertTrue(profiles.pollCombinedDirectionEdge(true, true, 0));
        assertFalse(profiles.pollCombinedDirectionEdge(true, true, 0));

        InputManager padInput = new InputManager();
        padInput.setGamepadDeadzone(0.15f);
        padInput.beginFrame();
        float[] axes = new float[InputManager.GAMEPAD_AXIS_COUNT];
        axes[GLFW.GLFW_GAMEPAD_AXIS_LEFT_X] = 0.5f;
        padInput.updateGamepadState(0, true, new boolean[InputManager.GAMEPAD_BUTTON_COUNT], axes);
        StickVector stick = GamepadDeviceProfiles.readProportionalLeftStick(padInput);
        assertEquals(0.5f, stick.x(), 1e-4f);

        RumbleEffect rumble = new RumbleEffect(2.0f, -1.0f, 10.0);
        assertEquals(1.0f, rumble.lowFreqMotor(), 1e-6f);
        assertEquals(0.0f, rumble.highFreqMotor(), 1e-6f);
        assertEquals(5.0, rumble.durationSec(), 1e-6);

        // #0613, #0614, #0615, #0622, #0623, #0635, #0662, #0669, #0674
        CursorAndFocusPolicy cursor = new CursorAndFocusPolicy();
        assertTrue(cursor.updateCursorAutoHide(true, 0.0, true, 2.1));
        assertFalse(cursor.updateCursorAutoHide(true, 6.0, true, 0.016));
        assertTrue(cursor.updateRelativeMouseLock(true, false));
        assertFalse(cursor.updateRelativeMouseLock(true, true));
        cursor.forceReleaseCursor();
        assertFalse(cursor.isRelativeMouseLocked());
        assertFalse(CursorAndFocusPolicy.shouldMouseHoverTakeFocus(0.0));
        assertTrue(cursor.toggleFullscreen(new WindowBounds(50, 60, 1280, 720)).isEmpty());
        WindowBounds restored = cursor.toggleFullscreen(null).orElseThrow();
        assertEquals(1280, restored.width());

        ClipboardBridge clipboard = new InMemoryClipboardBridge();
        clipboard.setClipboardString("hello kagayaki");
        assertEquals("hello kagayaki", clipboard.getClipboardString());

        PreferencesState prefs = new PreferencesState();
        prefs.setInvertCameraY(true);
        prefs.setCameraSensitivity(2.5f);
        prefs.setMinClickIntervalMs(120);
        assertTrue(prefs.isInvertCameraY());
        assertEquals(2.5f, prefs.getCameraSensitivity(), 1e-6f);
        assertEquals(80, prefs.getMinClickIntervalMs());
    }
}
