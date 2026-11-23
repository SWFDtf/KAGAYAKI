package de.swfdtf.state;

import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.parser.VJavaParser;
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

class LocalizationManagerTest {

    @Test
    void switchesLanguagesLiveWithoutRestartAndResolvesDialogueAndUi(@TempDir Path tempProject) throws IOException {
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
        loc.indexProject(project);

        assertEquals("deutsch", loc.getCurrentLanguage());
        assertEquals(Set.of("deutsch", "english", "japanese"), loc.getAvailableLanguages());

        // Fast fallback for default language
        assertEquals("Guten Morgen!", loc.translateDialogue("line_intro_01", "Guten Morgen!"));
        assertEquals("Neues Spiel", loc.translateUi("ui.new_game", "Neues Spiel"));

        List<String> languageEvents = new ArrayList<>();
        Consumer<String> listener = languageEvents::add;
        loc.addLanguageChangeListener(listener);

        // Live switch to English without restart
        loc.setLanguage("english");
        assertEquals("english", loc.getCurrentLanguage());
        assertEquals(List.of("english"), languageEvents);

        // Lookup by originalText and by TranslateBlock id
        assertEquals("Good morning!", loc.translateDialogue("unknown_id", "Guten Morgen!"));
        assertEquals("A quiet day.", loc.translateDialogue("line_narrator_02", "Ein ruhiger Tag."));
        assertEquals("New Game", loc.translateUi("Neues Spiel", "Neues Spiel"));
        assertEquals("Settings", loc.translateUi("ui.settings", "Einstellungen"));
        // Untranslated fallback
        assertEquals("Beenden", loc.translateUi("ui.quit", "Beenden"));

        // Manual override takes precedence
        loc.registerTranslation("english", "ui.new_game", "Start Story");
        assertEquals("Start Story", loc.translateUi("ui.new_game", "Neues Spiel"));

        // Live switch to Japanese
        loc.setLanguage("japanese");
        assertEquals("ニューゲーム", loc.translateUi("Neues Spiel", "Neues Spiel"));
        assertEquals("Guten Morgen!", loc.translateDialogue("line_intro_01", "Guten Morgen!"));

        // Same-language call does not emit redundant event; removed listener stops receiving
        loc.setLanguage("japanese");
        assertEquals(2, languageEvents.size());
        loc.removeLanguageChangeListener(listener);
        loc.setLanguage("deutsch");
        assertEquals(2, languageEvents.size());
        assertEquals("Guten Morgen!", loc.translateDialogue("line_intro_01", "Guten Morgen!"));
    }

    @Test
    void resolvesLocalizedAssetsUnderTlLangWithFallback(@TempDir Path projectRoot) throws IOException {
        Path assetsImages = projectRoot.resolve("assets/images");
        Files.createDirectories(assetsImages);
        Files.writeString(assetsImages.resolve("title.png"), "orig-title", StandardCharsets.UTF_8);
        Files.writeString(assetsImages.resolve("logo.png"), "orig-logo", StandardCharsets.UTF_8);
        Files.writeString(assetsImages.resolve("bg.png"), "orig-bg", StandardCharsets.UTF_8);

        // Candidate 1: tl/en/assets/images/title.png (and verify preference over tl/en/images/title.png)
        Path tlFull = projectRoot.resolve("tl/en/assets/images/title.png");
        Files.createDirectories(tlFull.getParent());
        Files.writeString(tlFull, "en-full-title", StandardCharsets.UTF_8);

        Path tlShortShadow = projectRoot.resolve("tl/en/images/title.png");
        Files.createDirectories(tlShortShadow.getParent());
        Files.writeString(tlShortShadow, "en-short-title", StandardCharsets.UTF_8);

        // Candidate 2: tl/en/images/logo.png
        Files.writeString(projectRoot.resolve("tl/en/images/logo.png"), "en-short-logo", StandardCharsets.UTF_8);

        LocalizationManager loc = new LocalizationManager("deutsch");
        assertEquals("assets/images/title.png", loc.resolveLocalizedAsset(projectRoot, "assets/images/title.png"));

        loc.setLanguage("en");
        assertEquals("tl/en/assets/images/title.png", loc.resolveLocalizedAsset(projectRoot, "assets/images/title.png"));
        assertEquals("tl/en/images/logo.png", loc.resolveLocalizedAsset(projectRoot, "assets/images/logo.png"));
        assertEquals("assets/images/bg.png", loc.resolveLocalizedAsset(projectRoot, "assets/images/bg.png"));

        loc.setLanguage("fr");
        assertEquals("assets/images/title.png", loc.resolveLocalizedAsset(projectRoot, "assets/images/title.png"));
    }

    @Test
    void extractsValidTranslationTemplateThatReparsesCleanlyAndIntegratesWithRuntime(@TempDir Path projectRoot) throws IOException {
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
        String template = LocalizationManager.extractTranslationTemplate(project, "english");

        assertNotNull(template);
        assertFalse(template.isBlank());
        assertTrue(template.contains("translate english "));
        assertTrue(template.contains("translate english strings:"));

        // Re-parse generated .vjava template cleanly using VJavaParser
        VJavaAst.ScriptFileAst parsedTemplate = VJavaParser.parse(Path.of("tl/english/generated.vjava"), template);

        // 3 dialogue blocks (lena line 1, narrator in if, lena inside menu choice) + 1 strings block
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

        // Verify filled-in generated template matches ScriptRuntime DialoguePrompt.lineId() end-to-end
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
        loc.indexProject(localizedProject);
        loc.setLanguage("english");

        ScriptRuntime runtime = new ScriptRuntime(localizedProject);
        runtime.start();
        ScriptRuntime.DialoguePrompt prompt = runtime.getActiveDialogue();
        assertNotNull(prompt);
        assertEquals("Welcome to \"Kagayaki\"!", loc.translateDialogue(prompt.lineId(), prompt.rawText()));
        assertEquals("Welcome to \"Kagayaki\"!", loc.translateDialogue(prompt.lineId(), "UNRELATED_FALLBACK"));
        assertEquals("Start Game", loc.translateUi("btn.start", "Spiel starten"));
        // Untranslated template entry gracefully falls back to original text
        assertEquals("Version 1.0", loc.translateUi("lbl.version", "Version 1.0"));
    }
}
