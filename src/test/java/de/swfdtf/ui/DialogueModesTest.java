package de.swfdtf.ui;

import de.swfdtf.audio.AudioEngine;
import de.swfdtf.audio.TextToSpeechService;
import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.InputManager;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaAst;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueModesTest {

    private static final String DIALOGUE_SCRIPT = """
            define lena = Character("Lena", color="#58a6ff", image="lena")
            define kaito = Character("Kaito", color="#f0883e", image="kaito")

            label start:
                show lena at left
                lena "Hello world from KAGAYAKI."
                lena "Second ADV line for auto and skip."
                lena "Third unread line."
                return

            label nvl_test:
                nvl show
                lena "First paragraph in NVL mode."
                kaito "Second paragraph in NVL mode."
                lena "Third paragraph in NVL mode."
                nvl clear
                nvl hide
                lena "Back to standard line."
                return

            label bubble_test:
                show lena at left
                lena "Left side speech bubble."
                show kaito at right
                kaito "Right side speech bubble."
                lena "Third retained bubble."
                return
            """;

    private ScriptSession createSession(Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("main.vjava"), DIALOGUE_SCRIPT);
        VJavaAst.ProjectAst project = ProjectScanner.scan(projectDir);
        EngineAPI api = new EngineAPI(EngineConfig.builder().projectDir(projectDir).headless(true).build());
        return new ScriptSession(project, api);
    }

    @Test
    void advDialogueTypewriterVoiceBleepAutoAdvanceAndSkip(@TempDir Path projectDir) throws Exception {
        ScriptSession session = createSession(projectDir);
        AudioEngine audio = new AudioEngine(projectDir, true);
        audio.init();
        TextToSpeechService tts = new TextToSpeechService(session.getEngineApi().getLogger(), false);
        session.getEngineApi().registerService(AudioEngine.class, audio);
        session.getEngineApi().registerService(TextToSpeechService.class, tts);

        session.getPreferencesState().setTextSpeedCps(10.0f);
        session.getPreferencesState().setAutoForwardDelaySeconds(0.5f);
        session.getPreferencesState().setSelfVoicing(true);

        ScreenManager sm = new ScreenManager();
        AdvDialogueScreen adv = new AdvDialogueScreen(session, sm);
        sm.showScreen(adv);

        assertEquals(new Rect2f(140.0f, 790.0f, 1640.0f, 250.0f), adv.getDialogueBoxBounds());

        session.start("start");
        String firstLineId = session.getRuntime().getActiveDialogue().lineId();
        sm.update(0.0, null);

        assertFalse(adv.isTypewriterFinished());
        assertEquals(0, adv.getVisibleCharCount());
        assertTrue(tts.awaitIdle(1000));
        assertEquals(1, tts.getSpokenHistory().size());

        // Advance 0.6s at 10 CPS -> ~6 chars revealed and voice bleep triggered
        adv.update(0.6);
        assertTrue(adv.getVisibleCharCount() >= 5 && adv.getVisibleCharCount() < 15);
        assertTrue(adv.getBleepCount() >= 1);

        // First click/Space finishes typewriter without advancing script
        adv.handlePrimaryAdvance();
        assertTrue(adv.isTypewriterFinished());
        assertEquals("Hello world from KAGAYAKI.", session.getRuntime().getActiveDialogue().interpolatedText());

        // Second click/Space advances to line 2
        adv.handlePrimaryAdvance();
        assertEquals("Second ADV line for auto and skip.", session.getRuntime().getActiveDialogue().interpolatedText());

        // Enable Auto mode: finish typewriter, wait 0.6s -> auto-advances to line 3
        adv.setAutoActive(true);
        adv.handlePrimaryAdvance(); // finish line 2 typewriter
        adv.update(0.3);
        assertEquals("Second ADV line for auto and skip.", session.getRuntime().getActiveDialogue().interpolatedText());
        adv.update(0.3);
        assertEquals("Third unread line.", session.getRuntime().getActiveDialogue().interpolatedText());

        // Test Skip mode respecting skipUnread == false vs alreadyRead == true
        session.getPreferencesState().setSkipUnread(false);
        session.start("start");
        // Mark first line already read, ensure second line is NOT read by clearing or checking fresh
        assertTrue(session.getPersistentState().isLineRead(firstLineId));
        adv.syncFromRuntime(true);
        adv.setSkipActive(true);
        // Since firstLineId is alreadyRead, skip should advance when skipTimer hits
        adv.update(0.1);
        assertEquals("Second ADV line for auto and skip.", session.getRuntime().getActiveDialogue().interpolatedText());

        // On an unread line (not yet marked read? Note ScriptRuntime marks on show, so simulate unread prompt)
        session.getPreferencesState().setSkipUnread(false);
        session.getPersistentState().clear();
        session.start("start");
        // Verify skipUnread=false stops skip if prompt.alreadyRead() is false:
        // (start() showed line 1 with alreadyRead=false because persistentState was cleared!)
        assertFalse(session.getRuntime().getActiveDialogue().alreadyRead());
        adv.syncFromRuntime(true);
        adv.setSkipActive(true);
        adv.update(0.1);
        assertFalse(adv.isSkipActive(), "Skip must disengage on unread line when skipUnread is false");
        assertEquals("Hello world from KAGAYAKI.", session.getRuntime().getActiveDialogue().interpolatedText());

        // With skipUnread=true, unread lines are skipped
        session.getPreferencesState().setSkipUnread(true);
        adv.setSkipActive(true);
        adv.update(0.1);
        assertEquals("Second ADV line for auto and skip.", session.getRuntime().getActiveDialogue().interpolatedText());

        tts.close();
        audio.close();
    }

    @Test
    void nvlDialogueAccumulatesUntilClear(@TempDir Path projectDir) throws Exception {
        ScriptSession session = createSession(projectDir);
        ScreenManager sm = new ScreenManager();
        NvlDialogueScreen nvl = new NvlDialogueScreen(session);
        sm.showScreen(nvl);

        session.start("nvl_test");
        nvl.update(0.0);
        assertEquals(1, nvl.getEntryCount());
        assertEquals(List.of("Lena: First paragraph in NVL mode."), nvl.getEntries());

        // Finish typewriter and advance to 2nd and 3rd paragraphs
        nvl.handlePrimaryAdvance();
        nvl.handlePrimaryAdvance();
        nvl.update(0.0);
        assertEquals(2, nvl.getEntryCount());

        nvl.handlePrimaryAdvance();
        nvl.handlePrimaryAdvance();
        nvl.update(0.0);
        assertEquals(3, nvl.getEntryCount());
        assertEquals("Kaito: Second paragraph in NVL mode.", nvl.getEntries().get(1));
        assertEquals("Lena: Third paragraph in NVL mode.", nvl.getEntries().get(2));

        // Advancing past line 3 runs `nvl clear` + `nvl hide`, clearing the accumulated NVL lines
        nvl.handlePrimaryAdvance();
        nvl.handlePrimaryAdvance();
        nvl.update(0.0);
        assertTrue(session.getRuntime().getNvlLines().isEmpty());
        assertEquals("Back to standard line.", session.getRuntime().getActiveDialogue().interpolatedText());
    }

    @Test
    void bubbleDialogueTailTargetRetainModeAndShiftBEditor(@TempDir Path projectDir) throws Exception {
        ScriptSession session = createSession(projectDir);
        ScreenManager sm = new ScreenManager();
        BubbleDialogueScreen bubble = new BubbleDialogueScreen(session);
        bubble.setRetainPreviousBubbles(true);
        sm.showScreen(bubble);

        session.start("bubble_test");
        sm.update(0.0, null);
        assertNotNull(bubble.getActiveBubble());
        assertEquals(1, bubble.getVisibleBubbleCount());
        float leftTailX = bubble.getActiveBubble().layout().tailX();

        // Advance to Kaito on right side -> tail target shifts right!
        bubble.handlePrimaryAdvance();
        bubble.handlePrimaryAdvance();
        sm.update(0.0, null);
        assertEquals(2, bubble.getVisibleBubbleCount());
        assertEquals(1, bubble.getRetainedBubbles().size());
        float rightTailX = bubble.getActiveBubble().layout().tailX();
        assertTrue(rightTailX > leftTailX + 400.0f,
                "Expected right-side speaker tailX (" + rightTailX + ") > left-side tailX (" + leftTailX + ")");

        // Toggle Shift+B editor mode via InputManager / ScreenManager
        InputManager input = new InputManager();
        input.onKey(GLFW.GLFW_KEY_LEFT_SHIFT, 0, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SHIFT);
        input.onKey(GLFW.GLFW_KEY_B, 0, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SHIFT);
        sm.update(0.016, input);
        assertTrue(bubble.isEditorActive(), "Shift+B should activate bubble editor");

        // Drag bubble box using mouse events on root node
        BubbleDialogueScreen.BubbleLayoutConfig before = bubble.getActiveBubble().layout();
        float grabBoxX = before.boxX() + 40.0f;
        float grabBoxY = before.boxY() + 40.0f;
        assertTrue(bubble.getRoot().dispatchMouseDown(grabBoxX, grabBoxY, GLFW.GLFW_MOUSE_BUTTON_LEFT));
        bubble.getRoot().dispatchMouseMove(grabBoxX + 120.0f, grabBoxY - 50.0f);
        assertTrue(bubble.getRoot().dispatchMouseUp(grabBoxX + 120.0f, grabBoxY - 50.0f, GLFW.GLFW_MOUSE_BUTTON_LEFT));

        BubbleDialogueScreen.BubbleLayoutConfig afterBoxDrag = bubble.getActiveBubble().layout();
        assertEquals(before.boxX() + 120.0f, afterBoxDrag.boxX(), 1.0f);
        assertEquals(before.boxY() - 50.0f, afterBoxDrag.boxY(), 1.0f);

        // Drag tail tip handle
        float tailStartX = afterBoxDrag.tailX();
        float tailStartY = afterBoxDrag.tailY();
        assertTrue(bubble.getRoot().dispatchMouseDown(tailStartX, tailStartY, GLFW.GLFW_MOUSE_BUTTON_LEFT));
        bubble.getRoot().dispatchMouseMove(tailStartX - 80.0f, tailStartY + 35.0f);
        assertTrue(bubble.getRoot().dispatchMouseUp(
                tailStartX - 80.0f, tailStartY + 35.0f, GLFW.GLFW_MOUSE_BUTTON_LEFT));

        BubbleDialogueScreen.BubbleLayoutConfig afterTailDrag = bubble.getActiveBubble().layout();
        assertEquals(tailStartX - 80.0f, afterTailDrag.tailX(), 1.0f);
        assertEquals(tailStartY + 35.0f, afterTailDrag.tailY(), 1.0f);

        // Exit editor mode -> presets persist to bubble_layouts.json and reload in new BubbleDialogueScreen
        bubble.toggleEditorMode();
        assertFalse(bubble.isEditorActive());
        assertTrue(Files.exists(
                session.getSaveManager().getSavesDir().resolve(BubbleDialogueScreen.PRESETS_FILE_NAME)));

        BubbleDialogueScreen reloaded = new BubbleDialogueScreen(session);
        BubbleDialogueScreen.BubbleLayoutConfig loadedCfg = reloaded.getLayoutPreset(bubble.getActiveBubble().lineId());
        assertNotNull(loadedCfg);
        assertEquals(afterTailDrag.boxX(), loadedCfg.boxX(), 0.5f);
        assertEquals(afterTailDrag.tailX(), loadedCfg.tailX(), 0.5f);
    }
}
