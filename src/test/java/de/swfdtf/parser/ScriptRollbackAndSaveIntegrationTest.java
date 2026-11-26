package de.swfdtf.parser;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.state.HistoryLog;
import de.swfdtf.state.SaveSlotData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptRollbackAndSaveIntegrationTest {

    private static final String ROLLBACK_AND_SAVE_SCRIPT = """
            define lena = Character("Lena", color="#58a6ff", image="lena", voice_tag="lena")

            :: Kode für VN: Definiere eigene Java-Klassen & Unterklassen @->{
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

            label start:
                scene bg_akademie_nacht with dissolve(0.8)
                :: Kode für VN: Initialisiere Spielerdaten @->{
                    SpezialGegenstand amulett = new SpezialGegenstand("Sternenkompass", 100, "Nachtsicht");
                    state.set("spieler_name", "Kaito");
                    state.set("aktives_item", amulett);
                    state.set("mut_punkte", DialogHelfer.berechneBonus(10, true));
                }<<
                show lena neutral at center with easein(0.4)
                lena "Willkommen in der ersten Beispielszene, [spieler_name]."

                menu:
                    "Direkt weiter" if mut_punkte >= 20 -> jump dialog_zwei
                    "Umgebung untersuchen" -> jump umgebung_pruefen

            label umgebung_pruefen:
                show lena happy with dissolve(0.2)
                :: Kode für VN: Erhöhe Mutpunkte @->{
                    state.set("mut_punkte", state.getInt("mut_punkte", 0) + 15);
                }<<
                lena "Dein Sternenkompass leuchtet." with vpunch
                jump dialog_zwei

            label dialog_zwei:
                scene bg_bibliothek with fade(0.5)
                block_rollback
                lena "Punkt ohne Wiederkehr erreicht."
                return

            translate english start_5:
                lena "Welcome to the first sample scene, [spieler_name]."
            """;

    @Test
    void rollbackRollForwardHistoryJumpBarrierAndGzipSaveLoadWorkEndToEnd(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("main.vjava"), ROLLBACK_AND_SAVE_SCRIPT);

        VJavaAst.ProjectAst project = ProjectScanner.scan(projectDir);
        EngineAPI api = new EngineAPI(EngineConfig.builder().projectDir(projectDir).headless(true).build());
        ScriptSession session = new ScriptSession(project, api);

        session.start();
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, session.getRuntime().getStatus());
        assertEquals(
                "Willkommen in der ersten Beispielszene, Kaito.",
                session.getRuntime().getActiveDialogue().interpolatedText());
        assertEquals(35, session.getStateStore().getInt("mut_punkte", 0));

        // Step 1 -> Menu
        session.advance();
        assertEquals(ScriptRuntime.Status.WAITING_MENU, session.getRuntime().getStatus());

        // Step 2 -> Branch B (umgebung_pruefen)
        session.choose(1);
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, session.getRuntime().getStatus());
        assertEquals("Dein Sternenkompass leuchtet.", session.getRuntime().getActiveDialogue().interpolatedText());
        assertEquals(50, session.getStateStore().getInt("mut_punkte", 0));

        // Rollback back to Menu! Mut points should revert from 50 -> 35
        assertTrue(session.rollback());
        assertEquals(ScriptRuntime.Status.WAITING_MENU, session.getRuntime().getStatus());
        assertEquals(35, session.getStateStore().getInt("mut_punkte", 0));

        // Roll-forward back to Branch B dialogue! Mut points restore 35 -> 50
        assertTrue(session.rollForward());
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, session.getRuntime().getStatus());
        assertEquals("Dein Sternenkompass leuchtet.", session.getRuntime().getActiveDialogue().interpolatedText());
        assertEquals(50, session.getStateStore().getInt("mut_punkte", 0));

        // Jump back to first dialogue via HistoryLog entry
        List<HistoryLog.HistoryEntry> entries = session.getHistoryLog().getEntries();
        assertTrue(entries.size() >= 2);
        HistoryLog.HistoryEntry firstLine = entries.get(0);
        assertTrue(session.jumpToHistoryEntry(firstLine));
        assertEquals(
                "Willkommen in der ersten Beispielszene, Kaito.",
                session.getRuntime().getActiveDialogue().interpolatedText());
        assertEquals(35, session.getStateStore().getInt("mut_punkte", 0));

        // Save slot with 2x2 RGBA thumbnail while holding custom SpezialGegenstand object
        byte[] thumbRgba = new byte[]{
            (byte) 255, 0, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255,
            0, 0, (byte) 255, (byte) 255, (byte) 255, (byte) 255, 0, (byte) 255
        };
        session.saveToSlot("slot_1", "Akademie Start", 2, 2, thumbRgba);
        SaveSlotData.SaveMetadata meta = session.getSaveManager().readSlotMetadata("slot_1").orElseThrow();
        assertEquals("Akademie Start", meta.customName());
        assertTrue(meta.thumbnailPng().length > 0);

        // Advance past block_rollback in dialog_zwei
        session.advance(); // menu
        session.choose(0); // jump dialog_zwei (triggers block_rollback before dialogue)
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, session.getRuntime().getStatus());
        assertEquals("Punkt ohne Wiederkehr erreicht.", session.getRuntime().getActiveDialogue().interpolatedText());

        // Rollback must now be BLOCKED by block_rollback!
        assertFalse(session.rollback());
        assertFalse(session.jumpToHistoryEntry(firstLine));

        // Load saved slot in a brand new ScriptSession and verify full restoration including SpezialGegenstand!
        EngineAPI api2 = new EngineAPI(EngineConfig.builder().projectDir(projectDir).headless(true).build());
        ScriptSession reloaded = new ScriptSession(project, api2);
        reloaded.loadFromSlot("slot_1");

        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, reloaded.getRuntime().getStatus());
        assertEquals(
                "Willkommen in der ersten Beispielszene, Kaito.",
                reloaded.getRuntime().getActiveDialogue().interpolatedText());
        assertEquals(35, reloaded.getStateStore().getInt("mut_punkte", 0));
        Object item = reloaded.getStateStore().get("aktives_item");
        assertNotNull(item);
        assertEquals("SpezialGegenstand", item.getClass().getSimpleName());
        assertEquals("Sternenkompass", item.getClass().getField("name").get(item));
        assertEquals("Nachtsicht", item.getClass().getField("effekt").get(item));

        // Live language switch in reloaded session
        reloaded.getLocalizationManager().setLanguage("english");
        assertEquals("english", reloaded.getLocalizationManager().getCurrentLanguage());
        reloaded.advance(); // menu
        reloaded.choose(0); // dialog_zwei
        assertEquals("Punkt ohne Wiederkehr erreicht.", reloaded.getRuntime().getActiveDialogue().interpolatedText());
        reloaded.advance();
        assertEquals(ScriptRuntime.Status.ENDED, reloaded.getRuntime().getStatus());
    }
}
