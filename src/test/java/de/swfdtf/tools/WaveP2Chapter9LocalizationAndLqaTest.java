package de.swfdtf.tools;

import de.swfdtf.state.LocalizationAdvancedServices.DropInLanguagePackManager;
import de.swfdtf.state.LocalizationAdvancedServices.RegionalLocaleResolver;
import de.swfdtf.state.LocalizationAdvancedServices.TranslationKeyAndMacroServices;
import de.swfdtf.state.LocalizationAdvancedServices.TranslationKeyAndMacroServices.ExtractedSourceEntry;
import de.swfdtf.state.LocalizationAdvancedServices.TranslationKeyAndMacroServices.LocalizedMetadataRegistry;
import de.swfdtf.state.LocalizationManager;
import de.swfdtf.state.TranslationEntry;
import de.swfdtf.tools.LocalizationToolingServices.PseudoLocalizationGenerator;
import de.swfdtf.tools.LocalizationToolingServices.TranslationStaticValidator;
import de.swfdtf.tools.LocalizationToolingServices.TranslatorWorklistAndStatsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests covering Chapter 9 regional locale resolution, drop-in language packs,
 * localized asset swaps, idempotent extraction, encoding recovery, glossary auditing,
 * pseudo-localization, LQA notes, tag/escape validators, and DE/EN completeness verification
 * (Wave P2-9).
 */
class WaveP2Chapter9LocalizationAndLqaTest {

    @Test
    void testRegionalLocaleFallbackFormattingAndSuggestion() {
        assertEquals("deutsch", RegionalLocaleResolver.resolveBestLanguageMatch(
                "de_AT", Set.of("deutsch", "english"), "deutsch"));
        assertEquals("english", RegionalLocaleResolver.resolveBestLanguageMatch(
                "en_GB", Set.of("deutsch", "english"), "deutsch"));
        assertEquals("deutsch", RegionalLocaleResolver.suggestFirstLaunchLanguage(
                Locale.GERMAN, Set.of("deutsch", "english"), "deutsch"));

        Instant ts = Instant.parse("2026-11-24T18:00:00Z");
        String deFmt = RegionalLocaleResolver.formatTimestamp(ts, "deutsch", ZoneOffset.UTC);
        String enFmt = RegionalLocaleResolver.formatTimestamp(ts, "english", ZoneOffset.UTC);
        assertEquals("24.11.2026 18:00", deFmt);
        assertTrue(enFmt.contains("11/24/26"));
    }

    @Test
    void testDropInLanguagePacksAndAssetSwaps(@TempDir Path tempDir) throws IOException {
        Path esPack = tempDir.resolve("tl").resolve("spanish");
        Files.createDirectories(esPack);
        Files.writeString(esPack.resolve("tl_version.txt"), "0.8.0", StandardCharsets.UTF_8);

        var packs = DropInLanguagePackManager.scanDropInLanguagePacks(tempDir, "1.0");
        assertEquals(1, packs.size());
        assertEquals("spanish", packs.get(0).language());
        assertTrue(packs.get(0).versionWarning().contains("0.8.0"));

        Path customImg = tempDir.resolve("assets").resolve("images")
                .resolve("tl").resolve("english").resolve("title.png");
        Files.createDirectories(customImg.getParent());
        Files.writeString(customImg, "png", StandardCharsets.UTF_8);

        assertEquals("assets/images/tl/english/title.png",
                DropInLanguagePackManager.resolveLocalizedImageAsset(tempDir, "english", "title.png"));
        assertEquals("assets/images/title.png",
                DropInLanguagePackManager.resolveLocalizedImageAsset(tempDir, "french", "title.png"));
    }

    @Test
    void testIdempotentExtractionDeterministicIdsEncodingMacrosAndGlossaryAudit() {
        String id1 = TranslationKeyAndMacroServices.generateDeterministicLineId("ch1", 1, "Wirklich?");
        String id2 = TranslationKeyAndMacroServices.generateDeterministicLineId("ch2", 1, "Wirklich?");
        assertFalse(id1.equals(id2));

        List<ExtractedSourceEntry> sources = List.of(
                new ExtractedSourceEntry("l1", "Hallo Welt", "snow", "intro"),
                new ExtractedSourceEntry("l1", "Hallo Welt", "snow", "intro"),
                new ExtractedSourceEntry("l2", "Tschüss", "queen", "intro")
        );
        List<TranslationEntry> merged = TranslationKeyAndMacroServices.mergeIdempotentEntries(
                Map.of("l1", "Hello World"), sources);
        assertEquals(2, merged.size());
        assertEquals("Hello World", merged.get(0).translatedText());
        assertTrue(merged.get(0).context().contains("speaker=snow"));

        assertEquals(List.of("old_key"),
                TranslationKeyAndMacroServices.findObsoleteTranslationKeys(Set.of("l1", "old_key"), Set.of("l1")));

        // #0745 UTF-8 BOM & Windows-1252 recovery
        byte[] bomUtf8 = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'H', 'i'};
        assertEquals("Hi", TranslationKeyAndMacroServices.decodeTranslationBytesSafely(bomUtf8));
        byte[] win1252 = "Brücke".getBytes(Charset.forName("windows-1252"));
        assertEquals("Brücke", TranslationKeyAndMacroServices.decodeTranslationBytesSafely(win1252));

        // #0715, #0741, #0763, #0760
        assertEquals("You have 50 gold",
                TranslationKeyAndMacroServices.interpolateTemplate("You have [gold] gold", Map.of("gold", 50)));
        assertEquals(List.of("missing_var"),
                TranslationKeyAndMacroServices.findUndefinedPlaceholders("Hi [name] [missing_var]", Set.of("name")));
        assertEquals("she lost her mirror",
                TranslationKeyAndMacroServices.applyPronounMacros("[subj] lost [poss] mirror", "she"));
        assertEquals("english::line1",
                TranslationKeyAndMacroServices.resolveReadTrackingKey("line1", "english", false));

        // #0727 & #0758 Glossary & Brand stopword audit
        List<TranslationEntry> badGlossary = List.of(
                new TranslationEntry("g1", "Der Zauberspiegel von KAGAYAKI", "The Enchanted Mirror of Demo", "", 1)
        );
        var violations = TranslationKeyAndMacroServices.auditGlossaryAndBrandNames(
                badGlossary, Map.of("Zauberspiegel", "Magic Mirror"), Set.of("KAGAYAKI"));
        assertEquals(2, violations.size());

        // #0704, #0708, #0710, #0725, #0748, #0750 LocalizedMetadataRegistry
        LocalizedMetadataRegistry meta = new LocalizedMetadataRegistry();
        meta.register("english", "char.snow", "Snow White");
        meta.register("english", "codex.mirror", "Ancient glass artifact.");
        meta.registerCodexTags("english", "mirror", List.of("glass", "oracle"));
        meta.register("english", "ui.about.header", "KAGAYAKI Engine [version]");
        assertEquals("Snow White", meta.resolve("english", "char.snow", "Schneewittchen"));
        assertEquals(List.of("mirror"), meta.searchCodexIds("english", "glass"));
        assertEquals("KAGAYAKI Engine 1.0.0", meta.formatAboutScreenText("english", "1.0.0"));
    }

    @Test
    void testPseudoLocalizationWorklistsValidatorsAndBilingualDemoAudit() {
        String pseudo = PseudoLocalizationGenerator.pseudoLocalize("Welcome [player] {b}now{/b}");
        assertTrue(pseudo.startsWith("[!! "));
        assertTrue(pseudo.contains("[player]"));
        assertTrue(pseudo.contains("{b}"));
        assertTrue(pseudo.length() > "Welcome [player] {b}now{/b}".length() + 8);

        var worklist = TranslatorWorklistAndStatsService.buildUntranslatedWorklistByChapter(
                List.of(
                        new ExtractedSourceEntry("k1", "Hi", "a", "ch1"),
                        new ExtractedSourceEntry("k1", "Hi", "a", "ch1"),
                        new ExtractedSourceEntry("k2", "Bye", "a", "ch1")
                ),
                Set.of("k2"));
        assertEquals(1, worklist.get("ch1").size());

        var wordStats = TranslatorWorklistAndStatsService.computeMissingWordStats(
                List.of(
                        new TranslationEntry("d1", "Hello friend", "", "dialogue ch1", 1),
                        new TranslationEntry("u1", "Save Game Now", "", "ui button", 2)
                ),
                Map.of());
        assertEquals(2, wordStats.missingDialogueWords());
        assertEquals(3, wordStats.missingUiWords());

        assertEquals("WARN_OVER_130_PERCENT",
                TranslatorWorklistAndStatsService.evaluateLengthTrafficLight("Save", "Spielstand sichern"));
        assertEquals("OK",
                TranslatorWorklistAndStatsService.evaluateLengthTrafficLight("Start", "Start"));

        List<String> notes = TranslatorWorklistAndStatsService.extractLqaNotes(
                "// internal comment\n## LQA Note: Keep sarcastic tone\nsay \"Hi\"\n");
        assertEquals(List.of("Keep sarcastic tone"), notes);

        var syntaxIssues = TranslationStaticValidator.validateTranslationLines(
                List.of("\"{color=red}Hello\"", "\"unclosed quote"));
        assertEquals(2, syntaxIssues.size());

        var uiWarnings = TranslationStaticValidator.warnHardcodedUiStrings(
                List.of("new TextButton(\"Hardcoded Title\")", "new TextButton(loc.translateUi(\"k\", \"Start\"))"));
        assertEquals(1, uiWarnings.size());

        var hits = TranslationStaticValidator.searchTranslatedEntries(
                "english", Map.of("d1", "forest_intro"),
                List.of(new TranslationEntry("d1", "Der Zauberwald", "The Enchanted Forest", "", 1)),
                "enchanted");
        assertEquals(1, hits.size());
        assertEquals("forest_intro", hits.get(0).labelName());

        assertEquals(Path.of("dist/screenshots/english/title.png"),
                TranslationStaticValidator.resolveLocalizedScreenshotPath(Path.of("dist"), "english", "title"));

        // #0746 & #0765 DE/EN complete audit
        LocalizationManager lm = new LocalizationManager("deutsch");
        lm.registerTranslation("deutsch", "ui.start", "Spiel starten");
        lm.registerTranslation("english", "ui.start", "Start Game");
        assertTrue(TranslationStaticValidator.verifyMultiLanguageCompleteness(
                lm, List.of("ui.start"), List.of("deutsch", "english")).isEmpty());
    }
}
