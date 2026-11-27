package de.swfdtf.graphics;

import de.swfdtf.graphics.TypographyAndLayoutServices.BidiTextReorderer;
import de.swfdtf.graphics.TypographyAndLayoutServices.BidiTextReorderer.VisualRun;
import de.swfdtf.graphics.TypographyAndLayoutServices.LanguageFontProfileRegistry;
import de.swfdtf.graphics.TypographyAndLayoutServices.RichTextTypographyHelpers;
import de.swfdtf.graphics.TypographyAndLayoutServices.RichTextTypographyHelpers.ChapterScrollAnchor;
import de.swfdtf.graphics.TypographyAndLayoutServices.RichTextTypographyHelpers.OnDemandGlyphAtlasTracker;
import de.swfdtf.graphics.TypographyAndLayoutServices.RubyShorthandParser;
import de.swfdtf.graphics.TypographyAndLayoutServices.TextFitAndBoundsService;
import de.swfdtf.graphics.TypographyAndLayoutServices.TextFitAndBoundsService.TextBounds;
import de.swfdtf.state.PreferencesState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests covering Chapter 9 BiDi/RTL reordering, Furigana shorthand, per-language fonts,
 * auto-shrinking, soft hyphens, CJK character wrapping, WCAG contrast, typewriter tag/fade/speed,
 * glyph atlas DPI regeneration, and zero-width wrapping fuzz checks (Wave P2-9).
 */
class WaveP2Chapter9TypographyAndBidiTest {

    @Test
    void testBidiRtlMixedRunsAndDirectionalMirroring() {
        assertTrue(BidiTextReorderer.containsRtl("مرحبا 123 عالم"));
        assertFalse(BidiTextReorderer.containsRtl("Hello 123"));

        List<VisualRun> runs = BidiTextReorderer.reorderToVisualRuns("שלום 2026 עולם", true);
        assertFalse(runs.isEmpty());
        assertTrue(runs.stream().anyMatch(r -> !r.rightToLeft() && "2026".equals(r.text())));
        assertEquals("←", BidiTextReorderer.mirrorDirectionalGlyph("→", true));
        assertEquals("→", BidiTextReorderer.mirrorDirectionalGlyph("→", false));
    }

    @Test
    void testRubyShorthandLanguageFontsAutoShrinkSoftHyphensAndFuzzBounds() {
        var spans = RubyShorthandParser.parseRubySpans("{ruby=きょう}今日{/ruby}の天気", 24.0f);
        assertEquals(1, spans.size());
        assertEquals("きょう", spans.get(0).rubyText());
        assertTrue(RubyShorthandParser.computeAdjustedLineHeight("{ruby=きょう}今日{/ruby}", 24.0f, 1.25f)
                > 24.0f * 1.25f);

        LanguageFontProfileRegistry fonts = new LanguageFontProfileRegistry();
        assertEquals("NotoSansJP.ttf", fonts.resolveFontForLanguage("japanese", false));
        assertEquals(LanguageFontProfileRegistry.DYSLEXIA_FONT,
                fonts.resolveFontForLanguage("japanese", true));

        float shrunk = TextFitAndBoundsService.fitFontSizeToRect(
                "VeryLongTranslatedButtonCaption", 24.0f, 200.0f, 0.20f);
        assertEquals(24.0f * 0.80f, shrunk, 1e-4f);

        assertEquals("LongTit...", TextFitAndBoundsService.truncateWithEllipsis("LongTitleTextHere", 100.0f, 10.0f));

        // Soft hyphen invisible when fitting, hyphenated when split (#0692)
        assertEquals(List.of("Donau"), TextFitAndBoundsService.wrapWithSoftHyphens("Do\u00ADnau", 200.0f, 10.0f));
        List<String> split = TextFitAndBoundsService.wrapWithSoftHyphens("Donau\u00ADdampf", 60.0f, 10.0f);
        assertTrue(split.get(0).endsWith("-"));

        // Stable typewriter pre-wrap prevents word pop-in (#0738)
        List<String> pre = TextFitAndBoundsService.sliceStableTypewriterLines("Hello world", 7, 55.0f, 10.0f);
        assertEquals(2, pre.size());
        assertEquals("Hello", pre.get(0));
        assertEquals("w", pre.get(1));

        // #0749 fuzz box widths 0..2000 must never throw or return NaN
        for (int w = 0; w <= 2000; w += 25) {
            TextBounds bounds = TextFitAndBoundsService.measureWrappedText("日本語テスト", w, 20.0f, 1.4f, 1.0f);
            assertFalse(Float.isNaN(bounds.widthPx()));
            assertFalse(Float.isNaN(bounds.heightPx()));
        }
    }

    @Test
    void testNfcQuotesWcagContrastTagsFadeBinaryCursorTategakiAndGlyphAtlas() {
        String decomposed = "a\u0308";
        assertEquals("ä", RichTextTypographyHelpers.normalizeNfc(decomposed));

        assertEquals("„Hallo [name]“",
                RichTextTypographyHelpers.applyTypographicQuotes("\"Hallo [name]\"", "deutsch"));
        assertEquals("“Hello {b}there{/b}”",
                RichTextTypographyHelpers.applyTypographicQuotes("\"Hello {b}there{/b}\"", "english"));

        assertTrue(RichTextTypographyHelpers.isWcagAaReadable("#FFFFFF", "#0D1117"));
        assertFalse(RichTextTypographyHelpers.isWcagAaReadable("#555555", "#444444"));

        assertEquals("{color=red}Text{/color}",
                RichTextTypographyHelpers.autoCloseUnclosedTags("{color=red}Text"));
        assertEquals("{color=red}Hel{/color}",
                RichTextTypographyHelpers.sliceVisiblePreservingActiveTags("{color=red}Hello{/color}", 3));

        assertEquals(15, RichTextTypographyHelpers.remapTypewriterOnLanguageSwitch(10, 20, 30));

        double[] times = RichTextTypographyHelpers.computeCharRevealTimes("ab{instant}cd", 10.0);
        assertEquals(4, times.length);
        assertEquals(times[2], times[3], 1e-6);

        assertEquals(0.5f, RichTextTypographyHelpers.computeCharFadeAlpha(40.0, true), 1e-4f);
        assertEquals(1.0f, RichTextTypographyHelpers.computeCharFadeAlpha(10.0, false), 1e-4f);

        assertEquals(2, RichTextTypographyHelpers.locateCursorBinarySearch(new float[]{10f, 20f, 30f, 40f}, 25f));

        List<String> cols = RichTextTypographyHelpers.layoutVerticalTategakiColumns("あいうえ", 2);
        assertEquals(List.of("うえ", "あい"), cols);

        assertEquals(List.of("https://kagayaki.dev"),
                RichTextTypographyHelpers.detectUrls("Docs at https://kagayaki.dev ready."));

        var missing = RichTextTypographyHelpers.findMissingGlyphs("A♥B", Set.of((int) 'A', (int) 'B'), "intro");
        assertEquals(1, missing.size());
        assertEquals("♥", missing.get(0).character());

        assertTrue(RichTextTypographyHelpers.estimateAutoReadDurationSeconds("あいうえおかきくけこ", "japanese", 120)
                >= 0.8);
        assertEquals("Chapter 2", RichTextTypographyHelpers.resolveStickyChapterHeader(250f,
                List.of(new ChapterScrollAnchor("Chapter 1", 0f), new ChapterScrollAnchor("Chapter 2", 200f))));

        assertTrue(RichTextTypographyHelpers.highlightInterpolatedPlaceholders("Hi [name]", "#58a6ff", true)
                .contains("{color=#58a6ff}[name]{/color}"));

        OnDemandGlyphAtlasTracker atlas = new OnDemandGlyphAtlasTracker();
        assertEquals(3, atlas.ensureCodepointsBaked("雪白姫"));
        assertEquals(0, atlas.ensureCodepointsBaked("雪"));
        assertTrue(atlas.onWindowContentScaleChanged(2.0f, 2.0f));
        assertEquals(1, atlas.getRebuildCount());
        assertEquals(0, atlas.getBakedGlyphCount());

        PreferencesState prefs = new PreferencesState();
        prefs.setLineSpacing(1.5f);
        prefs.setLetterSpacing(2.0f);
        assertEquals(1.5f, prefs.getLineSpacing(), 1e-4f);
        assertEquals(2.0f, prefs.getLetterSpacing(), 1e-4f);
    }
}
