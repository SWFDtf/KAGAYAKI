package de.swfdtf.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalizationManagerTest {

    @Test
    void translationEntryAndBlockValidateInputsAndEnforceImmutability() {
        assertThrows(IllegalArgumentException.class,
                () -> new TranslationEntry("line_1", "Hallo", "Hello", "lena", -1));

        TranslationEntry entry = new TranslationEntry("line_1", "Hallo", "Hello", "lena", 0);
        assertEquals("line_1", entry.id());
        assertEquals(0, entry.lineNumber());

        assertThrows(NullPointerException.class,
                () -> new LocalizationManager.TranslationBlock(null, "line_1", List.of(entry)));

        List<TranslationEntry> mutable = new ArrayList<>();
        mutable.add(entry);
        LocalizationManager.TranslationBlock block =
                new LocalizationManager.TranslationBlock("english", "line_1", mutable);
        mutable.clear();
        assertEquals(1, block.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> block.entries().add(entry));

        LocalizationManager.TranslationBlock emptyBlock =
                new LocalizationManager.TranslationBlock("english", null, null);
        assertEquals(List.of(), emptyBlock.entries());
    }

    @Test
    void switchesLanguagesLiveWithoutRestartAndResolvesDialogueAndUi() {
        List<LocalizationManager.TranslationBlock> blocks = List.of(
                new LocalizationManager.TranslationBlock("english", "line_intro_01", List.of(
                        new TranslationEntry("line_intro_01", "Guten Morgen!", "Good morning!", "lena", 3)
                )),
                new LocalizationManager.TranslationBlock("english", "line_narrator_02", List.of(
                        new TranslationEntry("line_narrator_02", "Ein ruhiger Tag.", "A quiet day.", null, 6)
                )),
                new LocalizationManager.TranslationBlock("english", "strings", List.of(
                        new TranslationEntry("strings", "Neues Spiel", "New Game", null, 10),
                        new TranslationEntry("strings", "Einstellungen", "Settings", null, 12),
                        new TranslationEntry("strings", "Beenden", "", null, 14)
                )),
                new LocalizationManager.TranslationBlock("japanese", "strings", List.of(
                        new TranslationEntry("strings", "Neues Spiel", "ニューゲーム", null, 2)
                ))
        );

        LocalizationManager loc = new LocalizationManager();
        loc.indexTranslationBlocks("deutsch", blocks);

        assertEquals("deutsch", loc.getCurrentLanguage());
        assertEquals("deutsch", loc.getDefaultLanguage());
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

        // Lookup by originalText and by TranslationBlock id ("strings" must NOT enter projectById)
        assertEquals("Good morning!", loc.translateDialogue("unknown_id", "Guten Morgen!"));
        assertEquals("A quiet day.", loc.translateDialogue("line_narrator_02", "Ein ruhiger Tag."));
        assertEquals("New Game", loc.translateUi("Neues Spiel", "Neues Spiel"));
        assertEquals("Settings", loc.translateUi("ui.settings", "Einstellungen"));
        assertEquals("FALLBACK", loc.translateUi("strings", "FALLBACK"));
        // Untranslated empty entry falls back cleanly
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
    void indexTranslationsPreservesMultiEntryBlockSemanticsAndExplicitLanguageSelection() {
        LocalizationManager loc = new LocalizationManager("deutsch");
        loc.setLanguage("french");

        Map<String, List<TranslationEntry>> byLang = new LinkedHashMap<>();
        byLang.put("english", List.of(
                new TranslationEntry("shared_id", "Erste Zeile", "First line", "lena", 10),
                new TranslationEntry("shared_id", "Zweite Zeile", "Second line", "lena", 11),
                new TranslationEntry("single_id", "Einzelzeile", "Single line", "lena", 15),
                new TranslationEntry("strings", "Start", "Play", null, 20)
        ));
        byLang.put("french", List.of());

        loc.indexTranslations("spanish", byLang);

        // Explicit language remains active even when projectDefaultLanguage changes
        assertEquals("spanish", loc.getDefaultLanguage());
        assertEquals("french", loc.getCurrentLanguage());
        assertEquals(Set.of("spanish", "english", "french"), loc.getAvailableLanguages());

        loc.setLanguage("english");
        // Multi-entry block uses putIfAbsent for blockId -> keeps first entry for shared_id
        assertEquals("First line", loc.translateDialogue("shared_id", "UNRELATED"));
        assertEquals("Second line", loc.translateDialogue("other_id", "Zweite Zeile"));
        // Single-entry block resolves by id and sourceText
        assertEquals("Single line", loc.translateDialogue("single_id", "Einzelzeile"));
        // "strings" entries resolve by sourceText, never by id "strings"
        assertEquals("Play", loc.translateUi("Start", "Start"));
        assertEquals("UNCHANGED", loc.translateUi("strings", "UNCHANGED"));
    }

    @Test
    void registerTranslationWithEntryRegistersAndRemovesOverridesCorrectly() {
        LocalizationManager loc = new LocalizationManager("deutsch");
        loc.setLanguage("english");

        loc.registerTranslation("english",
                new TranslationEntry("dlg_42", "Alter Text", "New Text", "lena", 42));
        assertEquals("New Text", loc.translateDialogue("dlg_42", "UNRELATED"));
        assertEquals("New Text", loc.translateDialogue("other", "Alter Text"));

        // "strings" id must not pollute ID lookup, only sourceText
        loc.registerTranslation("english",
                new TranslationEntry("strings", "Optionen", "Options", null, 50));
        assertEquals("Options", loc.translateUi("Optionen", "Optionen"));
        assertEquals("FALLBACK", loc.translateUi("strings", "FALLBACK"));

        // Null translatedText removes both ID and sourceText overrides
        loc.registerTranslation("english",
                new TranslationEntry("dlg_42", "Alter Text", null, "lena", 42));
        assertEquals("UNRELATED", loc.translateDialogue("dlg_42", "UNRELATED"));
        assertEquals("Alter Text", loc.translateDialogue("other", "Alter Text"));
    }

    @Test
    void avoidsCaseDuplicatesAndPhantomLanguagesAndTrimsDialogueLineIds() {
        LocalizationManager loc = new LocalizationManager("EN");
        loc.indexTranslationBlocks("en", List.of(
                new LocalizationManager.TranslationBlock("en", "strings", List.of(
                        new TranslationEntry("strings", "Hallo", "Hello", null, 1)
                ))
        ));
        assertEquals(1, loc.getAvailableLanguages().size());
        assertEquals(Set.of("EN"), loc.getAvailableLanguages());

        // Removing or passing empty stub on unseen languages must not pollute available languages
        loc.registerTranslation("fr", new TranslationEntry("id1", "Bonjour", null, null, 1));
        loc.registerTranslation("es", "id2", null);
        loc.registerTranslation("it", new TranslationEntry("id3", "Ciao", "", null, 1));
        assertEquals(Set.of("EN"), loc.getAvailableLanguages());

        // Registering a real override adds the language; removing the last override prunes it
        loc.registerTranslation("fr", new TranslationEntry("  dlg_fr  ", "Hallo", "Bonjour", "lena", 5));
        assertEquals(Set.of("EN", "fr"), loc.getAvailableLanguages());

        loc.setLanguage("fr");
        assertEquals("Bonjour", loc.translateDialogue("  dlg_fr  ", "UNRELATED"));

        loc.registerTranslation("fr", new TranslationEntry("dlg_fr", "Hallo", null, "lena", 5));
        assertEquals(Set.of("EN"), loc.getAvailableLanguages());
        assertEquals("Hallo", loc.translateDialogue("dlg_fr", "Hallo"));

        // Empty translatedText on an existing manual override removes it and falls back cleanly
        loc.setLanguage("en");
        loc.registerTranslation("en", new TranslationEntry("dlg_en", "Welt", "World", null, 7));
        assertEquals("World", loc.translateDialogue("dlg_en", "Welt"));
        loc.registerTranslation("en", new TranslationEntry("dlg_en", "Welt", "", null, 7));
        assertEquals("Welt", loc.translateDialogue("dlg_en", "Welt"));

        // Indexed language survives pruning of a removed manual override
        loc.indexTranslationBlocks("deutsch", List.of(
                new LocalizationManager.TranslationBlock("japanese", "dlg_jp", List.of(
                        new TranslationEntry("dlg_jp", "Hallo", "こんにちは", null, 3)
                ))
        ));
        loc.registerTranslation("japanese", "extra_key", "追加");
        loc.registerTranslation("japanese", "extra_key", "");
        assertEquals(Set.of("deutsch", "japanese"), loc.getAvailableLanguages());
        loc.setLanguage("japanese");
        assertEquals("こんにちは", loc.translateDialogue("  dlg_jp  ", "UNRELATED"));
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
        assertEquals("tl/en/assets/images/title.png",
                loc.resolveLocalizedAsset(projectRoot, "assets/images/title.png"));
        assertEquals("tl/en/images/logo.png", loc.resolveLocalizedAsset(projectRoot, "assets/images/logo.png"));
        assertEquals("assets/images/bg.png", loc.resolveLocalizedAsset(projectRoot, "assets/images/bg.png"));

        loc.setLanguage("fr");
        assertEquals("assets/images/title.png", loc.resolveLocalizedAsset(projectRoot, "assets/images/title.png"));
    }
}
