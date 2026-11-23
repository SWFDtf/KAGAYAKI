package de.swfdtf.parser;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SceneGraph;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.parser.VJavaAst.IfBranch;
import de.swfdtf.parser.VJavaAst.IfStatement;
import de.swfdtf.parser.VJavaAst.InputStatement;
import de.swfdtf.parser.VJavaAst.JumpStatement;
import de.swfdtf.parser.VJavaAst.LabelNode;
import de.swfdtf.parser.VJavaAst.MenuChoice;
import de.swfdtf.parser.VJavaAst.MenuStatement;
import de.swfdtf.parser.VJavaAst.NvlStatement;
import de.swfdtf.parser.VJavaAst.PauseStatement;
import de.swfdtf.parser.VJavaAst.ProjectAst;
import de.swfdtf.parser.VJavaAst.ReturnStatement;
import de.swfdtf.parser.VJavaAst.SayStatement;
import de.swfdtf.parser.VJavaAst.SetVarStatement;
import de.swfdtf.parser.VJavaAst.CallStatement;
import de.swfdtf.parser.VJavaAst.BlockRollbackStatement;
import de.swfdtf.state.SimplePersistentAccess;
import de.swfdtf.state.SimpleStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration tests for {@link ScriptRuntime}, including full execution of the
 * Section 8 reference {@code .vjava} script via {@link ProjectScanner} and {@link JavaBlockCompiler}.
 */
class ScriptRuntimeIntegrationTest {

    private static final String SECTION_8_REFERENCE_SCRIPT = """
            // Datei: main.vjava
            define lena = Character("Lena", color="#58a6ff", image="lena", voice_tag="lena")

            :: Kode für VN: Definiere eigene Java-Klassen & Unterklassen für das Spielsystem @->{
                public static class InventarItem {
                    public final String name;
                    public final int wert;

                    public InventarItem(String name, int wert) {
                        this.name = name;
                        this.wert = wert;
                    }
                }

                public static class SpezialGegenstand extends InventarItem {
                    public final String effekt;

                    public SpezialGegenstand(String name, int wert, String effekt) {
                        super(name, wert);
                        this.effekt = effekt;
                    }
                }

                public static class DialogHelfer {
                    public static int berechneBonus(int basis, boolean hatArtefakt) {
                        return hatArtefakt ? basis + 25 : basis;
                    }
                }
            }<<

            layeredimage lena:
                always:
                    "assets/images/char/lena_base.png"
                group outfit:
                    attribute uniform default:
                        "assets/images/char/lena_uniform.png"
                    attribute casual:
                        "assets/images/char/lena_casual.png"
                group face:
                    attribute neutral default:
                        "assets/images/char/lena_face_neutral.png"
                    attribute happy:
                        "assets/images/char/lena_face_happy.png"
                    attribute entschlossen:
                        "assets/images/char/lena_face_determined.png"

            label start:
                scene bg_akademie_nacht with dissolve(0.8)
                play music "assets/audio/nachtwind.ogg" fadein 2.0

                :: Kode für VN: Initialisiere Spielerdaten mit den eigenen Java-Klassen @->{
                    SpezialGegenstand amulett = new SpezialGegenstand("Sternenkompass", 100, "Nachtsicht");
                    state.set("spieler_name", "Kaito");
                    state.set("aktives_item", amulett);
                    state.set("mut_punkte", DialogHelfer.berechneBonus(10, true));
                    engine.logInfo("Startzustand mit SpezialGegenstand '" + amulett.name + "' geladen.");
                }<<

                show lena neutral at center with easein(0.4)
                voice "assets/audio/voice/lena_01.ogg"
                lena "Willkommen in der ersten Beispielszene, [spieler_name].{w=0.4} Bereit für den Übergang in Dialog 2?"

                menu:
                    "Ja, direkt weiter zu Dialog 2!" if mut_punkte >= 20 -> jump dialog_zwei
                    "Erst noch die Umgebung untersuchen." -> jump umgebung_pruefen

            label umgebung_pruefen:
                show lena happy with dissolve(0.2)
                lena "Gute Idee! Dein Sternenkompass leuchtet im Dunkeln." with vpunch
                jump dialog_zwei

            label dialog_zwei:
                scene bg_bibliothek with fade(0.5)
                show lena entschlossen at left with easein(0.3)
                lena "Das hier ist der zweite Beispieldialog der Vorlage. Du kannst jetzt speichern, laden oder zurückspulen!"
                stop music fadeout 1.5
                return
            """;

    @Test
    void executesFullSection8ReferenceScriptBothPaths(@TempDir Path tempProjectDir) throws Exception {
        Files.writeString(tempProjectDir.resolve("main.vjava"), SECTION_8_REFERENCE_SCRIPT);
        ProjectAst project = ProjectScanner.scan(tempProjectDir);

        EngineAPI engine = new EngineAPI(EngineConfig.defaults());
        engine.getLogger().setConsoleOutputEnabled(false);
        SimpleStateStore state = new SimpleStateStore();
        SimplePersistentAccess persistent = new SimplePersistentAccess();
        SceneController scene = new SceneController(new SceneGraph());

        List<String> bridgeLog = new ArrayList<>();
        ScriptRuntime runtime = new ScriptRuntime(project, engine, state, persistent, scene);
        runtime.setAudioBridge(new ScriptRuntime.AudioBridge() {
            @Override
            public void play(String ch, String path, boolean loop, double fadeIn, double vol) {
                bridgeLog.add("play:" + ch + ":" + path + ":" + loop + ":" + fadeIn);
            }

            @Override
            public void queue(String ch, List<String> paths) {
                bridgeLog.add("queue:" + ch + ":" + String.join(",", paths));
            }

            @Override
            public void stop(String ch, double fadeOut) {
                bridgeLog.add("stop:" + ch + ":" + fadeOut);
            }

            @Override
            public void voice(String path) {
                bridgeLog.add("voice:" + path);
            }
        });

        runtime.compile();
        runtime.start();

        // First pause: Lena's welcome dialogue
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        ScriptRuntime.DialoguePrompt d1 = runtime.getActiveDialogue();
        assertNotNull(d1);
        assertEquals("lena", d1.speakerId());
        assertEquals("Lena", d1.speakerName());
        assertEquals("#58a6ff", d1.speakerColorHex());
        assertEquals(
                "Willkommen in der ersten Beispielszene, Kaito.{w=0.4} Bereit für den Übergang in Dialog 2?",
                d1.interpolatedText()
        );
        assertEquals("assets/audio/voice/lena_01.ogg", d1.voicePath());
        assertFalse(d1.alreadyRead());
        assertTrue(persistent.isLineRead(d1.lineId()));
        assertTrue(persistent.isTrackUnlocked("assets/audio/nachtwind.ogg"));
        assertEquals(35, state.getInt("mut_punkte", 0));
        assertEquals("bg_akademie_nacht", scene.getBackgroundTag());

        SpriteNode lenaCenter = scene.getActiveSprite("lena");
        assertNotNull(lenaCenter);
        assertEquals(List.of("neutral"), lenaCenter.getAttributes());
        assertEquals(0.5f, lenaCenter.getTransform().getXalign(), 1e-5f);
        assertEquals("assets/images/char/lena_face_neutral.png", lenaCenter.getAssetPath());

        // Advance to menu
        runtime.advance();
        assertEquals(ScriptRuntime.Status.WAITING_MENU, runtime.getStatus());
        ScriptRuntime.MenuPrompt menu = runtime.getActiveMenu();
        assertNotNull(menu);
        assertEquals(2, menu.options().size());
        assertTrue(menu.options().get(0).enabled());
        assertTrue(menu.options().get(0).visible());
        assertTrue(menu.options().get(1).enabled());

        // Branch 1: inspect environment -> preserves center position, triggers vpunch, then jumps to dialog_zwei
        runtime.choose(1);
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        assertEquals("umgebung_pruefen", runtime.getCurrentLabel());
        ScriptRuntime.DialoguePrompt dInspect = runtime.getActiveDialogue();
        assertEquals("Gute Idee! Dein Sternenkompass leuchtet im Dunkeln.", dInspect.interpolatedText());
        assertEquals("vpunch", dInspect.transitionExpr());
        assertNotNull(scene.getSceneGraph().getScreenShake());

        SpriteNode lenaHappy = scene.getActiveSprite("lena");
        assertNotNull(lenaHappy);
        assertEquals(List.of("happy"), lenaHappy.getAttributes());
        assertEquals(0.5f, lenaHappy.getTransform().getXalign(), 1e-5f);
        assertEquals("assets/images/char/lena_face_happy.png", lenaHappy.getAssetPath());

        // Advance past inspect line -> jumps to dialog_zwei
        runtime.advance();
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        assertEquals("dialog_zwei", runtime.getCurrentLabel());
        assertEquals("bg_bibliothek", scene.getBackgroundTag());

        SpriteNode lenaLeft = scene.getActiveSprite("lena");
        assertNotNull(lenaLeft);
        assertEquals(List.of("entschlossen"), lenaLeft.getAttributes());
        assertEquals(0.15f, lenaLeft.getTransform().getXalign(), 1e-5f);
        assertEquals("assets/images/char/lena_face_determined.png", lenaLeft.getAssetPath());

        // Advance past final dialogue -> stops music and returns -> ENDED
        runtime.advance();
        assertEquals(ScriptRuntime.Status.ENDED, runtime.getStatus());
        assertTrue(bridgeLog.contains("play:music:assets/audio/nachtwind.ogg:true:2.0"));
        assertTrue(bridgeLog.contains("voice:assets/audio/voice/lena_01.ogg"));
        assertTrue(bridgeLog.contains("stop:music:1.5"));

        // Second run: verify line is now marked alreadyRead and take Choice 0 directly
        state.clear();
        runtime.start();
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        assertTrue(runtime.getActiveDialogue().alreadyRead());
        runtime.advance();
        assertEquals(ScriptRuntime.Status.WAITING_MENU, runtime.getStatus());
        runtime.choose(0);
        assertEquals("dialog_zwei", runtime.getCurrentLabel());
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        runtime.advance();
        assertEquals(ScriptRuntime.Status.ENDED, runtime.getStatus());
    }

    @Test
    void supportsCallReturnIfElseInputPauseMenuTimeoutNvlAndSnapshotRestore() {
        LabelNode subLabel = new LabelNode(
                "sub_routine",
                List.of(
                        new SayStatement(null, "In subroutine [hero]", null, "test.vjava", 10),
                        new ReturnStatement("40 + 2", "test.vjava", 11)
                ),
                "test.vjava",
                9
        );

        LabelNode timeoutLabel = new LabelNode(
                "timed_out",
                List.of(
                        new SayStatement(null, "Timed out!", null, "test.vjava", 30),
                        new ReturnStatement(null, "test.vjava", 31)
                ),
                "test.vjava",
                29
        );

        LabelNode startLabel = new LabelNode(
                "start",
                List.of(
                        new NvlStatement("show", "test.vjava", 1),
                        new InputStatement("hero", "Name?", "Kaito", "KaitoLena", 4, "test.vjava", 2),
                        new CallStatement("sub_routine", List.of(), "test.vjava", 3),
                        new IfStatement(
                                List.of(
                                        new IfBranch("_return == 42", List.of(
                                                new SayStatement(null, "Returned [ _return ] for [hero]", null, "test.vjava", 5),
                                                new PauseStatement(0.5, "test.vjava", 6)
                                        )),
                                        new IfBranch(null, List.of(
                                                new SayStatement(null, "Unexpected", null, "test.vjava", 7)
                                        ))
                                ),
                                "test.vjava",
                                4
                        ),
                        new BlockRollbackStatement("test.vjava", 8),
                        new MenuStatement(
                                "Fast choice",
                                1.0,
                                "timed_out",
                                List.of(
                                        new MenuChoice("Disabled choice", "false", true, List.of(), "timed_out", "test.vjava", 10),
                                        new MenuChoice("Hidden choice", "false", false, List.of(), "timed_out", "test.vjava", 11),
                                        new MenuChoice("Active choice", null, false, List.of(new JumpStatement("timed_out", false, "test.vjava", 12)), null, "test.vjava", 12)
                                ),
                                "test.vjava",
                                9
                        )
                ),
                "test.vjava",
                1
        );

        ProjectAst ast = new ProjectAst(
                Path.of("."),
                null,
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("start", startLabel, "sub_routine", subLabel, "timed_out", timeoutLabel),
                Map.of(),
                List.of(),
                List.of(),
                List.of(new SetVarStatement("score", "10", true, "test.vjava", 0)),
                List.of(),
                List.of()
        );

        ScriptRuntime runtime = new ScriptRuntime(ast);
        AtomicInteger checkpoints = new AtomicInteger(0);
        runtime.setCheckpointListener(checkpoints::incrementAndGet);

        runtime.start("start");
        assertEquals(10, runtime.getStateStore().getInt("score", 0));
        assertEquals(ScriptRuntime.Status.WAITING_INPUT, runtime.getStatus());
        assertEquals(1, checkpoints.get());

        // Filter non-allowed chars ("X", "Z") and enforce maxLength 4 -> "Kait"
        runtime.submitInput("KXZaito");
        assertEquals("Kait", runtime.getStateStore().getString("hero", ""));
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        assertEquals("sub_routine", runtime.getCurrentLabel());
        assertEquals("In subroutine Kait", runtime.getActiveDialogue().interpolatedText());
        assertTrue(runtime.isNvlMode());
        assertEquals(List.of("In subroutine Kait"), runtime.getNvlLines());

        // Snapshot inside subroutine
        ScriptRuntime.ExecutionSnapshot subSnapshot = runtime.snapshotExecution();

        // Advance out of subroutine -> returns 42 -> enters if branch -> pauses on dialogue
        runtime.advance();
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        assertEquals(42, runtime.getStateStore().getInt("_return", 0));
        assertEquals("Returned 42 for Kait", runtime.getActiveDialogue().interpolatedText());

        // Snapshot inside nested IfStatement branch
        ScriptRuntime.ExecutionSnapshot ifSnapshot = runtime.snapshotExecution();

        // Advance to timed PauseStatement
        runtime.advance();
        assertEquals(ScriptRuntime.Status.PAUSING, runtime.getStatus());
        runtime.update(0.2);
        assertEquals(ScriptRuntime.Status.PAUSING, runtime.getStatus());
        assertEquals(0.3, runtime.getPauseRemainingSeconds(), 1e-6);

        // Finish pause -> executes block_rollback and reaches timed MenuStatement
        runtime.update(0.35);
        assertTrue(runtime.getStateStore().isRollbackBlocked());
        assertEquals(ScriptRuntime.Status.WAITING_MENU, runtime.getStatus());

        ScriptRuntime.MenuPrompt menu = runtime.getActiveMenu();
        assertEquals(3, menu.options().size());
        assertFalse(menu.options().get(0).enabled());
        assertTrue(menu.options().get(0).visible());
        assertFalse(menu.options().get(1).enabled());
        assertFalse(menu.options().get(1).visible());
        assertTrue(menu.options().get(2).enabled());

        assertThrows(IllegalStateException.class, () -> runtime.choose(0));
        assertThrows(IllegalStateException.class, () -> runtime.choose(1));

        // Elapse menu timeout -> auto-jumps to timed_out
        runtime.update(1.1);
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        assertEquals("timed_out", runtime.getCurrentLabel());
        assertEquals("Timed out!", runtime.getActiveDialogue().interpolatedText());

        runtime.advance();
        assertEquals(ScriptRuntime.Status.ENDED, runtime.getStatus());

        // Restore snapshot inside nested if-branch and confirm deterministic continuation
        runtime.restoreExecution(ifSnapshot);
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        assertEquals("Returned 42 for Kait", runtime.getActiveDialogue().interpolatedText());
        runtime.advance();
        assertEquals(ScriptRuntime.Status.PAUSING, runtime.getStatus());

        // Restore snapshot inside subroutine and confirm callStack unwinds properly
        runtime.restoreExecution(subSnapshot);
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        assertEquals("sub_routine", runtime.getCurrentLabel());

        // Developer warp
        runtime.jumpToLabel("timed_out");
        assertEquals("timed_out", runtime.getCurrentLabel());
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
    }
}
