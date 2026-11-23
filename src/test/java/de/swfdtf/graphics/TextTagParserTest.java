package de.swfdtf.graphics;

import de.swfdtf.graphics.TextTagParser.CueKind;
import de.swfdtf.graphics.TextTagParser.InlineImageSpan;
import de.swfdtf.graphics.TextTagParser.ParsedRichText;
import de.swfdtf.graphics.TextTagParser.RubyPair;
import de.swfdtf.graphics.TextTagParser.TextCue;
import de.swfdtf.graphics.TextTagParser.TextSpan;
import de.swfdtf.graphics.TextTagParser.TextStyle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextTagParserTest {

    private static final float EPS = 1e-4f;

    @Test
    void variableInterpolationAndEscapingWorkCleanly() {
        Map<String, Object> vars = Map.of(
                "player", "Kaito",
                "score", 42,
                "styled", "{b}Lena{/b}"
        );

        String interpolated = TextTagParser.interpolateVariables(
                "Hi [player], score=[score], escaped=[[player], unknown=[missing], {{b}",
                vars::get
        );
        assertEquals("Hi Kaito, score=42, escaped=[player], unknown=[missing], {{b}", interpolated);

        ParsedRichText parsed = TextTagParser.parse(
                "Hello [styled] [[raw] {{b}literal{{/b}",
                vars::get
        );
        assertEquals("Hello Lena [raw] {b}literal{/b}", parsed.plainText());
        assertEquals(3, parsed.spans().size());
        assertFalse(parsed.spans().get(0).style().bold());
        assertTrue(parsed.spans().get(1).style().bold());
        assertEquals("Lena", parsed.spans().get(1).text());
        assertFalse(parsed.spans().get(2).style().bold());
        assertEquals(" [raw] {b}literal{/b}", parsed.spans().get(2).text());
    }

    @Test
    void formattingTagsAndPlainResetWork() {
        ParsedRichText parsed = TextTagParser.parse(
                "{b}B{/b}{i}I{/i}{u}U{/u}{s}S{/s}{sc}Small{/sc}"
                        + "{b}{i}{u}{s}{sc}{color=#ff0000}{alpha=0.4}Mix{plain}Plain"
        );

        assertEquals("BIUSSmallMixPlain", parsed.plainText());
        List<TextSpan> spans = parsed.spans();
        assertEquals(7, spans.size());

        assertTrue(spans.get(0).style().bold());
        assertEquals("B", spans.get(0).text());

        assertTrue(spans.get(1).style().italic());
        assertEquals("I", spans.get(1).text());

        assertTrue(spans.get(2).style().underline());
        assertEquals("U", spans.get(2).text());

        assertTrue(spans.get(3).style().strikethrough());
        assertEquals("S", spans.get(3).text());

        assertTrue(spans.get(4).style().smallCaps());
        assertEquals("Small", spans.get(4).text());

        TextStyle mix = spans.get(5).style();
        assertTrue(mix.bold());
        assertTrue(mix.italic());
        assertTrue(mix.underline());
        assertTrue(mix.strikethrough());
        assertTrue(mix.smallCaps());
        assertEquals("#ff0000", mix.colorHex());
        assertEquals(0.4f, mix.alpha(), EPS);

        assertEquals(TextStyle.DEFAULT, spans.get(6).style());
        assertEquals("Plain", spans.get(6).text());
    }

    @Test
    void metricsAndColorStacksWorkProperly() {
        ParsedRichText parsed = TextTagParser.parse(
                "{color=#388bfd}A{color=#f0883eff}B{/color}C{/color}"
                        + "{alpha=0.5}D{/alpha}"
                        + "{size=28}E{size=+6}F{size=-4}G{/size}{/size}{/size}"
                        + "{font=assets/fonts/serif.ttf}H{/font}"
                        + "{k=2.5}I{/k}J"
        );

        assertEquals("ABCDEFGHIJ", parsed.plainText());
        List<TextSpan> spans = parsed.spans();
        assertEquals(10, spans.size());

        assertEquals("#388bfd", spans.get(0).style().colorHex());
        assertEquals("#f0883eff", spans.get(1).style().colorHex());
        assertEquals("#388bfd", spans.get(2).style().colorHex());

        assertNull(spans.get(3).style().colorHex());
        assertEquals(0.5f, spans.get(3).style().alpha(), EPS);

        assertEquals(28.0f, spans.get(4).style().resolveFontSize(20.0f), EPS);
        assertEquals(34.0f, spans.get(5).style().resolveFontSize(20.0f), EPS);
        assertEquals(30.0f, spans.get(6).style().resolveFontSize(20.0f), EPS);

        assertEquals("assets/fonts/serif.ttf", spans.get(7).style().fontPath());
        assertEquals(20.0f, spans.get(7).style().resolveFontSize(20.0f), EPS);

        assertEquals(2.5f, spans.get(8).style().kerning(), EPS);
        assertEquals(0.0f, spans.get(9).style().kerning(), EPS);
    }

    @Test
    void flowControlCuesAreCapturedWithExactIndices() {
        ParsedRichText parsed = TextTagParser.parse(
                "Fast{fast} {cps=60}Typed{/cps}{w} More{w=1.5}{p}Para{p=0.5}End{nw}"
        );

        assertEquals("Fast Typed More\nPara\nEnd", parsed.plainText());
        assertEquals(4, parsed.fastPrefixEndIndex());
        assertTrue(parsed.hasNoWait());

        List<TextCue> cues = parsed.cues();
        assertEquals(6, cues.size());

        assertEquals(new TextCue(4, CueKind.FAST, 0.0), cues.get(0));
        assertEquals(new TextCue(10, CueKind.WAIT_CLICK, 0.0), cues.get(1));
        assertEquals(new TextCue(15, CueKind.WAIT_TIMED, 1.5), cues.get(2));
        assertEquals(new TextCue(15, CueKind.PARAGRAPH, 0.0), cues.get(3));
        assertEquals(new TextCue(20, CueKind.PARAGRAPH, 0.5), cues.get(4));
        assertEquals(new TextCue(24, CueKind.NO_WAIT, 0.0), cues.get(5));

        TextSpan cpsSpan = parsed.spans().stream()
                .filter(s -> s.text().equals("Typed"))
                .findFirst()
                .orElseThrow();
        assertEquals(60.0f, cpsSpan.style().cps(), EPS);
    }

    @Test
    void rubyInlineImagesHyperlinksAndShaderAnimationsParseCleanly() {
        ParsedRichText parsed = TextTagParser.parse(
                "See {rb}東京{/rb}{rt}とうきょう{/rt} {image=assets/images/star.png} "
                        + "{a=jump:glossary_tokyo}{wave}Link{/wave}{/a} "
                        + "{jitter}Vibe{/jitter}{shake}Shake{/shake}"
                        + "{gradient=#58a6ff,#f0883e}Grad{/gradient}"
        );

        String expectedPlain = "See 東京 " + TextTagParser.OBJECT_REPLACEMENT_CHAR + " Link VibeShakeGrad";
        assertEquals(expectedPlain, parsed.plainText());
        assertEquals("See 東京  Link VibeShakeGrad", parsed.spokenText());

        List<RubyPair> rubies = parsed.rubyPairs();
        assertEquals(1, rubies.size());
        assertEquals(new RubyPair(4, 6, "東京", "とうきょう"), rubies.get(0));

        List<InlineImageSpan> images = parsed.inlineImages();
        assertEquals(1, images.size());
        assertEquals(7, images.get(0).charIndex());
        assertEquals("assets/images/star.png", images.get(0).imagePath());

        TextSpan linkSpan = parsed.spans().stream()
                .filter(s -> s.text().equals("Link"))
                .findFirst()
                .orElseThrow();
        assertEquals("jump:glossary_tokyo", linkSpan.style().hyperlinkTarget());
        assertTrue(linkSpan.style().wave());

        TextSpan vibeSpan = parsed.spans().stream()
                .filter(s -> s.text().equals("Vibe"))
                .findFirst()
                .orElseThrow();
        assertTrue(vibeSpan.style().jitter());

        TextSpan shakeSpan = parsed.spans().stream()
                .filter(s -> s.text().equals("Shake"))
                .findFirst()
                .orElseThrow();
        assertTrue(shakeSpan.style().jitter());

        TextSpan gradSpan = parsed.spans().stream()
                .filter(s -> s.text().equals("Grad"))
                .findFirst()
                .orElseThrow();
        assertEquals("#58a6ff", gradSpan.style().gradientStartHex());
        assertEquals("#f0883e", gradSpan.style().gradientEndHex());
        assertNotNull(gradSpan.style());
    }
}
