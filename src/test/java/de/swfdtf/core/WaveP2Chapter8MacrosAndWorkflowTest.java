package de.swfdtf.core;

import de.swfdtf.core.InputWorkflowAndMacroServices.DialogueAndUiInputGuards;
import de.swfdtf.core.InputWorkflowAndMacroServices.DialogueAndUiInputGuards.MultilineCursorTracker;
import de.swfdtf.core.InputWorkflowAndMacroServices.DialogueAndUiInputGuards.SecretSequenceDetector;
import de.swfdtf.core.InputWorkflowAndMacroServices.DialogueAndUiInputGuards.SliderDragCapture;
import de.swfdtf.core.InputWorkflowAndMacroServices.DialogueAndUiInputGuards.StudioAndHudHelpers;
import de.swfdtf.core.InputWorkflowAndMacroServices.DialogueAndUiInputGuards.VirtualKeyboardState;
import de.swfdtf.core.InputWorkflowAndMacroServices.EscapeAction;
import de.swfdtf.core.InputWorkflowAndMacroServices.InputLayer;
import de.swfdtf.core.InputWorkflowAndMacroServices.InputMacroRecorderAndPlayer;
import de.swfdtf.core.InputWorkflowAndMacroServices.InputMacroRecorderAndPlayer.ArenaPosition;
import de.swfdtf.core.InputWorkflowAndMacroServices.InputMacroRecorderAndPlayer.MacroRecording;
import de.swfdtf.core.InputWorkflowAndMacroServices.InputPriorityStack;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests covering Chapter 8 input priority stacks, `.vinput` deterministic macro
 * recording/replay, dual-window isolation, escape/HUD flow, UTF-32/IME sanitization, slider capture,
 * grid navigation, radial wheel, Konami codes, multiline cursors, and virtual keyboards (Wave P2-8).
 */
class WaveP2Chapter8MacrosAndWorkflowTest {

    @Test
    void testInputPriorityStackEscapeFlowAndDualWindowIsolation() {
        InputPriorityStack stack = new InputPriorityStack();
        KeybindingRemapper remapper = new KeybindingRemapper();
        remapper.registerDefault("dash", GLFW.GLFW_KEY_LEFT_SHIFT);
        InputManager studioInput = new InputManager();
        InputManager gameInput = new InputManager();

        gameInput.beginFrame();
        gameInput.onKey(GLFW.GLFW_KEY_LEFT_SHIFT, 0, GLFW.GLFW_PRESS, 0);
        stack.setArenaActive(true);
        assertTrue(stack.isActionDown("dash", remapper, gameInput, InputLayer.ARENA));

        stack.setModalActive(true);
        assertEquals(InputLayer.SCREEN_MODAL, stack.getTopLayer());
        assertFalse(stack.canReceiveInput(InputLayer.ARENA));
        assertFalse(stack.isActionDown("dash", remapper, gameInput, InputLayer.ARENA));

        // #0625 hidden HUD restores before menu; #0651 widget blocks canvas advance
        assertEquals(EscapeAction.RESTORE_HIDDEN_HUD,
                InputPriorityStack.resolveEscapeOrRightClick(true, true, false, "MENU"));
        assertEquals(EscapeAction.TRIGGER_ROLLBACK,
                InputPriorityStack.resolveEscapeOrRightClick(true, false, false, "ROLLBACK"));
        assertFalse(InputPriorityStack.shouldCanvasClickAdvance(true, true));
        assertTrue(InputPriorityStack.shouldCanvasClickAdvance(false, true));

        // #0657 dual window isolation
        assertTrue(InputPriorityStack.verifyDualWindowIsolation(studioInput, gameInput, GLFW.GLFW_KEY_LEFT_SHIFT));
    }

    @Test
    void testVinputMacroRecorderAndDeterministicReplay() {
        InputMacroRecorderAndPlayer recorder = new InputMacroRecorderAndPlayer();
        for (int i = 0; i < 60; i++) {
            recorder.captureFrame(1.0 / 60.0, List.of(GLFW.GLFW_KEY_D), 0.0, 0.0);
        }
        String vinput = recorder.exportVinputJson("snowwhite_forest_walk");
        MacroRecording parsed = InputMacroRecorderAndPlayer.parseVinputJson(vinput);

        ArenaPosition end1 = InputMacroRecorderAndPlayer.replayArena(parsed, 200.0, 100.0, 100.0);
        ArenaPosition end2 = InputMacroRecorderAndPlayer.replayArena(parsed, 200.0, 100.0, 100.0);
        assertEquals(300.0, end1.x(), 1e-4);
        assertEquals(100.0, end1.y(), 1e-4);
        assertEquals(end1, end2);
    }

    @Test
    void testUiInputGuardsTextSanitizerSliderGridRadialAndKonami() {
        assertEquals(0.05, DialogueAndUiInputGuards.clampSleepDelta(30.0), 1e-9);
        assertTrue(DialogueAndUiInputGuards.isAdvanceAllowedAfterGuard(260, 250));
        assertFalse(DialogueAndUiInputGuards.isAdvanceAllowedAfterGuard(150, 200));
        assertEquals(0, DialogueAndUiInputGuards.resolveInitialChoiceFocus(true, 3));

        // #0624 + #0652 surrogate pairs & control char filtering
        String cleaned = DialogueAndUiInputGuards.sanitizePlayerTextInput("A\u0001さくら🎭B", 32);
        assertEquals("Aさくら🎭B", cleaned);

        // #0641 grid nav wrap, #0658 radial wheel, #0667 magnetic speed
        assertEquals(0, DialogueAndUiInputGuards.stepGridFocus(5, 3, 6, 1, 0, true));
        assertEquals(0, DialogueAndUiInputGuards.resolveRadialSector(50.0, 0.0, 4));
        assertEquals(-1, DialogueAndUiInputGuards.resolveRadialSector(2.0, 2.0, 4));
        assertEquals(60.0, DialogueAndUiInputGuards.computeMagneticPointerSpeed(100.0, true), 1e-6);

        // #0655 slider capture outside bar
        SliderDragCapture slider = new SliderDragCapture();
        slider.update(true, true, 150.0, 100.0, 100.0);
        assertTrue(slider.isDragging());
        assertEquals(1.0f, slider.update(true, false, 240.0, 100.0, 100.0), 1e-6f);

        // #0665 Konami / secret code
        SecretSequenceDetector konami = new SecretSequenceDetector(
                GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_DOWN);
        assertFalse(konami.onKeyPressed(GLFW.GLFW_KEY_UP));
        assertFalse(konami.onKeyPressed(GLFW.GLFW_KEY_UP));
        assertFalse(konami.onKeyPressed(GLFW.GLFW_KEY_DOWN));
        assertTrue(konami.onKeyPressed(GLFW.GLFW_KEY_DOWN));

        // #0666 multiline column tracking
        List<String> lines = List.of("0123456789", "hi", "abcdefghij");
        MultilineCursorTracker cursor = new MultilineCursorTracker();
        cursor.setHorizontal(7, lines);
        cursor.moveVertical(1, lines);
        assertEquals(2, cursor.getColumn());
        cursor.moveVertical(1, lines);
        assertEquals(7, cursor.getColumn());
    }

    @Test
    void testStudioHelpersVirtualKeyboardAndScreenshots() {
        StudioAndHudHelpers hud = new StudioAndHudHelpers();
        assertTrue(hud.tryPlayHoverCue(1000L));
        assertFalse(hud.tryPlayHoverCue(1020L));
        assertTrue(hud.tryPlayHoverCue(1060L));

        assertTrue(hud.updateArenaTutorialHint(0.0, 0.0));
        assertFalse(hud.updateArenaTutorialHint(1.0, 0.0));

        assertEquals("NVL", StudioAndHudHelpers.toggleDialogueModeIfDebug(true, "ADV"));
        assertEquals("ADV", StudioAndHudHelpers.toggleDialogueModeIfDebug(false, "ADV"));
        assertEquals(List.of("forest_clear"),
                StudioAndHudHelpers.filterLabelsForQuickJump(List.of("intro", "forest_clear"), "forest"));

        assertEquals(1, hud.cycleStudioPanelFocus(false, 3));
        assertEquals("door", hud.cycleHotspotFocus(false, List.of("window", "door")).orElseThrow());

        List<String> inspectLogs = new ArrayList<>();
        assertTrue(StudioAndHudHelpers.inspectWidgetOnMiddleClick(true, "btnSave", "Main", 10, 20, inspectLogs::add));
        assertEquals(1, inspectLogs.size());

        InputManager input = new InputManager();
        input.beginFrame();
        input.onKey(GLFW.GLFW_KEY_F10, 0, GLFW.GLFW_PRESS, 0);
        assertTrue(StudioAndHudHelpers.isScreenshotHotkeyPressed(input));

        VirtualKeyboardState vkb = new VirtualKeyboardState();
        vkb.typeGlyph("Snow\n", 16);
        vkb.backspace();
        vkb.typeGlyph("y", 16);
        assertEquals("Snoy", vkb.confirm());
        assertTrue(vkb.isConfirmed());
    }
}
