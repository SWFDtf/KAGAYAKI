package de.swfdtf.parser;

import de.swfdtf.state.LocalizationManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VJavaLocalizationBridgeTest {

    @Test
    void indexesScannedProjectAndSwitchesLanguagesLive(@TempDir Path tempProject) throws IOException {
        Files.writeString(tempProject.resolve("project.vjava.json"), """
                {
                  "title": "Test VN",
                  "defaultLanguage": "deutsch"
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(tempProject.resolve("main.vjava"), """
                define lena = Character("Lena")

                label start:
                    lena "Guten Morgen!"
                    "Ein ruhiger Tag."
                    return
                """, StandardCharsets.UTF_8);

        Files.writeString(tempProject.resolve("tl_en.vjava"), """
                translate english line_intro_01:
                    # lena "Guten Morgen!"
                    lena "Good morning!"

                translate english line_narrator_02:
                    "A quiet day."

                translate english strings:
                    old "Neues Spiel"
                    new "New Game"
                    old "Einstellungen"
                    new "Settings"
                """, StandardCharsets.UTF_8);

        Files.writeString(tempProject.resolve("tl_jp.vjava"), """
                translate japanese strings:
                    old "Neues Spiel"
                    new "ニューゲーム"
                """, StandardCharsets.UTF_8);

        VJavaAst.ProjectAst project = ProjectScanner.scan(tempProject);
        LocalizationManager loc = new LocalizationManager();
        VJavaLocalizationBridge.indexProject(loc, project);

        assertEquals("deutsch", loc.getCurrentLanguage());
        assertEquals(Set.of("deutsch", "english", "japanese"), loc.getAvailableLanguages());

        assertEquals("Guten Morgen!", loc.translateDialogue("line_intro_01", "Guten Morgen!"));
        assertEquals("Neues Spiel", loc.translateUi("ui.new_game", "Neues Spiel"));

        List<String> languageEvents = new ArrayList<>();
        Consumer<String> listener = languageEvents::add;
        loc.addLanguageChangeListener(listener);

        loc.setLanguage("english");
        assertEquals("english", loc.getCurrentLanguage());
        assertEquals(List.of("english"), languageEvents);

        assertEquals("Good morning!", loc.translateDialogue("unknown_id", "Guten Morgen!"));
        assertEquals("A quiet day.", loc.translateDialogue("line_narrator_02", "Ein ruhiger Tag."));
        assertEquals("New Game", loc.translateUi("Neues Spiel", "Neues Spiel"));
        assertEquals("Settings", loc.translateUi("ui.settings", "Einstellungen"));
        assertEquals("Beenden", loc.translateUi("ui.quit", "Beenden"));

        loc.setLanguage("japanese");
        assertEquals("ニューゲーム", loc.translateUi("Neues Spiel", "Neues Spiel"));
        assertEquals("Guten Morgen!", loc.translateDialogue("line_intro_01", "Guten Morgen!"));
    }

    @Test
    void extractsValidTranslationTemplateThatReparsesCleanlyAndIntegratesWithRuntime(
            @TempDir Path projectRoot
    ) throws IOException {
        Files.writeString(projectRoot.resolve("script.vjava"), """
                define lena = Character("Lena", color="#58a6ff")
                default mut = 10

                screen title_menu():
                    frame:
                        vbox:
                            text "KAGAYAKI Demo" size 36
                            textbutton "Spiel starten" action Return() tooltip "Abenteuer beginnen"
                            label "Version 1.0"

                label start:
                    lena "Willkommen in \\"Kagayaki\\"!"
                    if mut >= 5:
                        "Eine sternenklare Nacht."
                    menu "Wohin gehen?":
                        "In die Bibliothek" -> jump library
                        "Auf das Dach":
                            lena "Hier oben weht ein frischer Wind."
                    return

                label library:
                    return
                """, StandardCharsets.UTF_8);

        VJavaAst.ProjectAst project = ProjectScanner.scan(projectRoot);
        String template = VJavaLocalizationBridge.extractTranslationTemplate(project, "english");

        assertNotNull(template);
        assertFalse(template.isBlank());
        assertTrue(template.contains("translate english "));
        assertTrue(template.contains("translate english strings:"));

        VJavaAst.ScriptFileAst parsedTemplate = VJavaParser.parse(Path.of("tl/english/generated.vjava"), template);
        assertEquals(4, parsedTemplate.translations().size());

        VJavaAst.TranslateBlock dlg1 = parsedTemplate.translations().get(0);
        assertEquals("english", dlg1.language());
        assertEquals(1, dlg1.entries().size());
        assertEquals("lena", dlg1.entries().get(0).speaker());
        assertEquals("Willkommen in \"Kagayaki\"!", dlg1.entries().get(0).originalText());

        VJavaAst.TranslateBlock dlg2 = parsedTemplate.translations().get(1);
        assertEquals(1, dlg2.entries().size());
        assertEquals("Eine sternenklare Nacht.", dlg2.entries().get(0).originalText());

        VJavaAst.TranslateBlock dlg3 = parsedTemplate.translations().get(2);
        assertEquals("lena", dlg3.entries().get(0).speaker());
        assertEquals("Hier oben weht ein frischer Wind.", dlg3.entries().get(0).originalText());

        VJavaAst.TranslateBlock stringsBlock = parsedTemplate.translations().get(3);
        assertEquals("strings", stringsBlock.id());
        List<String> extractedOriginals = stringsBlock.entries().stream()
                .map(VJavaAst.TranslateEntry::originalText)
                .toList();
        assertTrue(extractedOriginals.contains("Wohin gehen?"));
        assertTrue(extractedOriginals.contains("In die Bibliothek"));
        assertTrue(extractedOriginals.contains("Auf das Dach"));
        assertTrue(extractedOriginals.contains("KAGAYAKI Demo"));
        assertTrue(extractedOriginals.contains("Spiel starten"));
        assertTrue(extractedOriginals.contains("Abenteuer beginnen"));
        assertTrue(extractedOriginals.contains("Version 1.0"));

        String filledTemplate = template
                .replace(
                        "# lena \"Willkommen in \\\"Kagayaki\\\"!\"\n    lena \"\"",
                        "# lena \"Willkommen in \\\"Kagayaki\\\"!\"\n    lena \"Welcome to \\\"Kagayaki\\\"!\""
                )
                .replace("old \"Spiel starten\"\n    new \"\"", "old \"Spiel starten\"\n    new \"Start Game\"");

        Files.createDirectories(projectRoot.resolve("tl/english"));
        Files.writeString(projectRoot.resolve("tl/english/script_tl.vjava"), filledTemplate, StandardCharsets.UTF_8);

        VJavaAst.ProjectAst localizedProject = ProjectScanner.scan(projectRoot);
        LocalizationManager loc = new LocalizationManager();
        VJavaLocalizationBridge.indexProject(loc, localizedProject);
        loc.setLanguage("english");

        ScriptRuntime runtime = new ScriptRuntime(localizedProject);
        runtime.start();
        ScriptRuntime.DialoguePrompt prompt = runtime.getActiveDialogue();
        assertNotNull(prompt);
        assertEquals("Welcome to \"Kagayaki\"!", loc.translateDialogue(prompt.lineId(), prompt.rawText()));
        assertEquals("Welcome to \"Kagayaki\"!", loc.translateDialogue(prompt.lineId(), "UNRELATED_FALLBACK"));
        assertEquals("Start Game", loc.translateUi("btn.start", "Spiel starten"));
        assertEquals("Version 1.0", loc.translateUi("lbl.version", "Version 1.0"));
    }

    @Test
    void preservesDialogueLineIdParityWithSpacesInPathAndExtractsInputPrompts(
            @TempDir Path projectRoot
    ) throws IOException {
        Path nestedScript = projectRoot.resolve("ch 1/intro script.vjava");
        Files.createDirectories(nestedScript.getParent());
        Files.writeString(nestedScript, """
                define lena = Character("Lena")

                label start:
                    lena "Hallo aus Kapitel 1!"
                    input player_name "Wie heißt du?" default "Kaito"
                    return
                """, StandardCharsets.UTF_8);

        VJavaAst.ProjectAst project = ProjectScanner.scan(projectRoot);
        String template = VJavaLocalizationBridge.extractTranslationTemplate(project, "english");
        assertTrue(template.contains("ch_1/intro_script.vjava:"), "Expected sanitized path ID in:\n" + template);
        assertTrue(template.contains("old \"Wie heißt du?\""), "Expected InputStatement prompt in:\n" + template);

        VJavaAst.ScriptFileAst reparsed = VJavaParser.parse(Path.of("tl/english/generated.vjava"), template);
        assertEquals(2, reparsed.translations().size());
        String dlgBlockId = reparsed.translations().get(0).id();
        assertTrue(dlgBlockId.startsWith("ch_1/intro_script.vjava:"));

        String filledTemplate = template
                .replace(
                        "# lena \"Hallo aus Kapitel 1!\"\n    lena \"\"",
                        "# lena \"Hallo aus Kapitel 1!\"\n    lena \"Hello from Chapter 1!\""
                )
                .replace(
                        "old \"Wie heißt du?\"\n    new \"\"",
                        "old \"Wie heißt du?\"\n    new \"What is your name?\""
                );

        Files.createDirectories(projectRoot.resolve("tl/english"));
        Files.writeString(projectRoot.resolve("tl/english/tl.vjava"), filledTemplate, StandardCharsets.UTF_8);

        VJavaAst.ProjectAst localizedProject = ProjectScanner.scan(projectRoot);
        LocalizationManager loc = new LocalizationManager();
        VJavaLocalizationBridge.indexProject(loc, localizedProject);
        loc.setLanguage("english");

        ScriptRuntime runtime = new ScriptRuntime(localizedProject);
        runtime.setLocalizationManager(loc);
        runtime.start();

        ScriptRuntime.DialoguePrompt dialogue = runtime.getActiveDialogue();
        assertNotNull(dialogue);
        assertEquals(dlgBlockId, dialogue.lineId());
        assertEquals("Hello from Chapter 1!", dialogue.interpolatedText());
        assertEquals("Hello from Chapter 1!", loc.translateDialogue(dialogue.lineId(), "UNRELATED_FALLBACK"));

        runtime.advance();
        assertEquals(ScriptRuntime.Status.WAITING_INPUT, runtime.getStatus());
        assertEquals("What is your name?", runtime.getActiveInput().prompt());
    }
}
