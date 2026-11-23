package de.swfdtf.graphics;

import de.swfdtf.graphics.StyledTextLayout.DecorationKind;
import de.swfdtf.graphics.StyledTextLayout.GlyphQuad;
import de.swfdtf.graphics.StyledTextLayout.HyperlinkHitbox;
import de.swfdtf.graphics.StyledTextLayout.InlineImageLayout;
import de.swfdtf.graphics.StyledTextLayout.LineDecoration;
import de.swfdtf.graphics.StyledTextLayout.RubyLayout;
import de.swfdtf.graphics.StyledTextLayout.TypewriterController;
import de.swfdtf.graphics.TextTagParser.ParsedRichText;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StyledTextLayoutTest {

    private static final float EPS = 1e-3f;

    @Test
    void builtinFallbackFontAtlasCoversAsciiAndGermanUmlauts() {
        try (FontAtlas atlas = FontAtlas.createBuiltinFallback(28.0f, false)) {
            assertTrue(atlas.isBuiltinFallback());
            assertEquals(0, atlas.getGlTextureId());
            assertTrue(atlas.getLineHeight() > 28.0f);

            for (char ch : new char[]{'A', 'z', 'ä', 'ö', 'ü', 'Ä', 'Ö', 'Ü', 'ß', '—', '…'}) {
                FontAtlas.GlyphMetrics gm = atlas.getGlyph(ch);
                assertNotNull(gm);
                assertTrue(gm.advanceWidth() > 0.0f, "Expected positive advance for " + ch);
                assertTrue(gm.width() > 0.0f && gm.height() > 0.0f, "Expected bitmap box for " + ch);
                assertTrue(gm.u1() > gm.u0() && gm.v1() > gm.v0(), "Expected non-empty UVs for " + ch);
            }

            float helloW = atlas.measureTextWidth("Hello", 28.0f, 0.0f);
            float spacedW = atlas.measureTextWidth("Hello", 28.0f, 3.0f);
            assertTrue(spacedW > helloW + 10.0f);

            float avKern = atlas.getKerning('A', 'V', 28.0f);
            assertTrue(avKern < 0.0f);

            byte[] rgba = atlas.getAtlasRgbaCopy();
            boolean hasNonZeroAlpha = false;
            for (int i = 3; i < rgba.length; i += 4) {
                if ((rgba[i] & 0xFF) > 0) {
                    hasNonZeroAlpha = true;
                    break;
                }
            }
            assertTrue(hasNonZeroAlpha);
        }
    }

    @Test
    void stbTrueTypeLoadsSystemFontWhenPresentAndFontManagerManagesAccessibility() throws IOException {
        try (FontManager manager = new FontManager()) {
            FontAtlas a1 = manager.getAtlas(null, 24.0f, false);
            FontAtlas a2 = manager.getAtlas(null, 24.2f, false);
            assertSame(a1, a2);
            assertTrue(a1.isBuiltinFallback());

            manager.setGlobalTextScale(1.5f);
            assertEquals(36.0f, manager.scaleFontSize(24.0f), EPS);
            manager.setDyslexiaFriendlyMode(true);
            assertTrue(manager.getAccessibilityExtraKerning() > 0.0f);
            assertTrue(manager.getAccessibilityLineSpacingMultiplier() > 1.0f);
        }

        Optional<Path> systemFont = FontManager.discoverSystemFontPath(false);
        if (systemFont.isPresent()) {
            try (FontAtlas ttfAtlas = FontAtlas.fromTrueTypeFile(systemFont.get(), 32.0f, false)) {
                assertFalse(ttfAtlas.isBuiltinFallback());
                assertEquals(0, ttfAtlas.getGlTextureId());
                assertTrue(ttfAtlas.measureTextWidth("KAGAYAKI äöüß", 32.0f, 0.0f) > 50.0f);
                // Dynamically bake a codepoint outside the initial prebake set if available.
                FontAtlas.GlyphMetrics arrow = ttfAtlas.getGlyph(0x25C6);
                assertNotNull(arrow);
            }
        }
    }

    @Test
    void wordWrappingAndLineHeightsComputeAccurately() {
        try (FontManager fontManager = new FontManager()) {
            ParsedRichText rich = TextTagParser.parse(
                    "The quick brown fox jumps over the lazy dog in KAGAYAKI."
            );

            StyledTextLayout wide = new StyledTextLayout(rich, fontManager, 2000.0f, 24.0f, 1.2f, "#f0f6fc", false);
            assertEquals(1, wide.getLineCount());
            assertTrue(wide.getTotalWidth() > 200.0f);

            StyledTextLayout narrow = new StyledTextLayout(rich, fontManager, 180.0f, 24.0f, 1.2f, "#f0f6fc", false);
            assertTrue(narrow.getLineCount() >= 3, "Expected multi-line wrap, got " + narrow.getLineCount());
            assertTrue(narrow.getTotalWidth() <= 185.0f, "Expected wrapped width <= maxWidth, got " + narrow.getTotalWidth());
            assertTrue(narrow.getTotalHeight() > wide.getTotalHeight() * 2.5f);

            for (int i = 1; i < narrow.getLines().size(); i++) {
                assertTrue(narrow.getLines().get(i).y() > narrow.getLines().get(i - 1).y());
                assertTrue(narrow.getLines().get(i).baselineY() > narrow.getLines().get(i - 1).baselineY());
            }
        }
    }

    @Test
    void furiganaIsCenteredDirectlyAboveBaseTextAndReservesHeadroom() {
        try (FontManager fontManager = new FontManager()) {
            ParsedRichText rich = TextTagParser.parse("Welcome {rb}漢字{/rb}{rt}かんじ{/rt} world");
            StyledTextLayout layout = new StyledTextLayout(rich, fontManager, 1000.0f, 32.0f, 1.2f, "#f0f6fc", false);

            assertEquals(1, layout.getRubyLayouts().size());
            RubyLayout ruby = layout.getRubyLayouts().get(0);
            assertEquals("かんじ", ruby.rubyText());

            float rubyCenterX = ruby.x() + ruby.width() * 0.5f;
            assertEquals(ruby.baseCenterX(), rubyCenterX, EPS);

            List<GlyphQuad> baseQuads = layout.getGlyphQuads().stream()
                    .filter(q -> q.charIndex() >= ruby.startCharIndex() && q.charIndex() < ruby.endCharIndex())
                    .toList();
            assertEquals(2, baseQuads.size());

            float baseSpanCenter = (baseQuads.get(0).x() + (baseQuads.get(1).x() + baseQuads.get(1).width())) * 0.5f;
            assertEquals(baseSpanCenter, rubyCenterX, 4.0f);

            float baseBaselineY = layout.getLines().get(0).baselineY();
            assertTrue(ruby.baselineY() < baseQuads.get(0).y(), "Ruby baseline must be above base glyphs");
            assertTrue(ruby.baselineY() < baseBaselineY);
            assertTrue(ruby.rubyQuads().stream().allMatch(q -> q.y() >= 0.0f));
        }
    }

    @Test
    void hyperlinksSmallCapsDecorationsGradientAndInlineImagesLayoutProperly() {
        try (FontManager fontManager = new FontManager()) {
            ParsedRichText rich = TextTagParser.parse(
                    "{a=jump:entry}Click here{/a} {sc}Abc{/sc} "
                            + "{u}Under{/u} {s}Strike{/s} "
                            + "{gradient=#ff0000,#0000ff}RGB{/gradient} "
                            + "{image=assets/images/gem.png}"
            );
            StyledTextLayout layout = new StyledTextLayout(rich, fontManager, 1200.0f, 28.0f, 1.2f, "#f0f6fc", false);

            List<HyperlinkHitbox> hitboxes = layout.getHyperlinkHitboxes();
            assertEquals(1, hitboxes.size());
            HyperlinkHitbox hb = hitboxes.get(0);
            assertEquals("jump:entry", hb.actionTarget());
            assertTrue(hb.bounds().width() > 20.0f && hb.bounds().height() > 15.0f);
            assertEquals(
                    Optional.of("jump:entry"),
                    layout.hitTestHyperlink(hb.bounds().centerX(), hb.bounds().centerY())
            );
            assertEquals(Optional.empty(), layout.hitTestHyperlink(-50.0f, -50.0f));

            // Small caps converts lowercase 'b','c' -> 'B','C' at reduced height while keeping 'A' full height.
            int scStart = rich.plainText().indexOf("Abc");
            GlyphQuad qCapA = quadAt(layout, scStart);
            GlyphQuad qSmallB = quadAt(layout, scStart + 1);
            assertEquals('A', qCapA.codepoint());
            assertFalse(qCapA.smallCaps());
            assertEquals('B', qSmallB.codepoint());
            assertTrue(qSmallB.smallCaps());
            assertTrue(qSmallB.height() < qCapA.height());

            // Decorations include underline and strikethrough.
            List<LineDecoration> decs = layout.getDecorations();
            assertTrue(decs.stream().anyMatch(d -> d.kind() == DecorationKind.UNDERLINE));
            assertTrue(decs.stream().anyMatch(d -> d.kind() == DecorationKind.STRIKETHROUGH));

            // Gradient interpolates from red to blue across "RGB".
            int gradStart = rich.plainText().indexOf("RGB");
            GlyphQuad qR = quadAt(layout, gradStart);
            GlyphQuad qG = quadAt(layout, gradStart + 1);
            GlyphQuad qB = quadAt(layout, gradStart + 2);
            assertTrue(qR.hasGradient());
            assertEquals(1.0f, qR.colorRgba()[0], EPS);
            assertEquals(0.0f, qR.colorRgba()[2], EPS);
            assertEquals(0.5f, qG.colorRgba()[0], EPS);
            assertEquals(0.5f, qG.colorRgba()[2], EPS);
            assertEquals(0.0f, qB.colorRgba()[0], EPS);
            assertEquals(1.0f, qB.colorRgba()[2], EPS);

            // Inline image layout present.
            assertEquals(1, layout.getInlineImages().size());
            InlineImageLayout img = layout.getInlineImages().get(0);
            assertEquals("assets/images/gem.png", img.imagePath());
            assertTrue(img.width() > 0.0f && img.height() > 0.0f);
        }
    }

    @Test
    void typewriterControllerHandlesFastTimedWaitClickWaitParagraphAndNoWait() {
        ParsedRichText rich = TextTagParser.parse(
                "Fast{fast}AB{w=0.5}CD{w}EF{p}GH{nw}"
        );
        // plainText: "FastABCDEF\nGH" -> indices: Fast(0..3), A(4), B(5), C(6), D(7), E(8), F(9), \n(10), G(11), H(12)
        assertEquals("FastABCDEF\nGH", rich.plainText());

        TypewriterController tw = new TypewriterController(rich, 10.0f); // 0.1s per char

        // Characters prior to {fast} appear instantaneously.
        assertEquals(4, tw.getVisibleCharCount());
        assertFalse(tw.isWaiting());
        assertFalse(tw.isFinished());

        // Advance 0.25s -> reveals 'A' and 'B' (indices 4 and 5) and hits {w=0.5} at index 6.
        tw.advance(0.25);
        assertEquals(6, tw.getVisibleCharCount());
        assertTrue(tw.isWaiting());
        assertTrue(tw.isTimedWait());
        assertEquals(0.5, tw.getWaitRemainingSeconds(), 1e-4);

        // Advance 0.3s -> still waiting (0.2s left).
        tw.advance(0.3);
        assertEquals(6, tw.getVisibleCharCount());
        assertTrue(tw.isWaiting());
        assertEquals(0.2, tw.getWaitRemainingSeconds(), 1e-4);

        // Advance 0.35s -> 0.2s clears timed wait, remaining 0.15s reveals 'C' at 0.1s -> visibleCharCount = 7.
        tw.advance(0.35);
        assertFalse(tw.isWaiting());
        assertEquals(7, tw.getVisibleCharCount());

        // Click while typing -> jumps forward to {w} at index 8 ("FastABCD").
        assertTrue(tw.clickOrAdvance());
        assertEquals(8, tw.getVisibleCharCount());
        assertTrue(tw.isWaiting());
        assertFalse(tw.isTimedWait());

        // Time alone must NOT clear click-wait {w}.
        tw.advance(5.0);
        assertTrue(tw.isWaiting());
        assertEquals(8, tw.getVisibleCharCount());

        // Click consumes {w}; next click reveals up to {p} at index 10 ("FastABCDEF").
        assertTrue(tw.clickOrAdvance());
        assertFalse(tw.isWaiting());
        assertTrue(tw.clickOrAdvance());
        assertEquals(10, tw.getVisibleCharCount());
        assertTrue(tw.isWaiting());

        // Click consumes {p} (advancing past '\n' to index 11), then click finishes "GH".
        assertTrue(tw.clickOrAdvance());
        assertFalse(tw.isWaiting());
        assertEquals(11, tw.getVisibleCharCount());
        assertTrue(tw.clickOrAdvance());
        assertEquals(13, tw.getVisibleCharCount());
        assertTrue(tw.isFinished());
        assertTrue(tw.shouldAutoAdvance());
        assertFalse(tw.clickOrAdvance());
    }

    @Test
    void typewriterControllerSupportsLocalCpsAndTimedParagraph() {
        ParsedRichText rich = TextTagParser.parse("{cps=50}12345{/cps}{p=0.25}X");
        TypewriterController tw = new TypewriterController(rich, 10.0f);

        // At 50 CPS, 0.1s reveals all 5 chars and hits {p=0.25}.
        tw.advance(0.11);
        assertEquals(5, tw.getVisibleCharCount());
        assertTrue(tw.isWaiting());
        assertTrue(tw.isTimedWait());
        assertEquals(0.25, tw.getWaitRemainingSeconds(), 1e-4);

        // Wait 0.25s clears {p=0.25} and skips '\n'; remaining 0.1s at 10 CPS reveals 'X'.
        tw.advance(0.36);
        assertFalse(tw.isWaiting());
        assertEquals(7, tw.getVisibleCharCount());
        assertTrue(tw.isFinished());
    }

    @Test
    void multiLineHyperlinksAdjacentLinksWideRubyAndExtraKerningWork() {
        try (FontManager fontManager = new FontManager()) {
            ParsedRichText linkRich = TextTagParser.parse(
                    "{a=first}Long multi word hyperlink target{/a} {a=second}Next{/a}"
            );
            StyledTextLayout wrappedLinks = new StyledTextLayout(
                    linkRich, fontManager, 180.0f, 24.0f, 1.2f, "#f0f6fc", false
            );
            assertTrue(wrappedLinks.getLineCount() >= 2);
            List<HyperlinkHitbox> firstBoxes = wrappedLinks.getHyperlinkHitboxes().stream()
                    .filter(h -> h.actionTarget().equals("first"))
                    .toList();
            List<HyperlinkHitbox> secondBoxes = wrappedLinks.getHyperlinkHitboxes().stream()
                    .filter(h -> h.actionTarget().equals("second"))
                    .toList();
            assertTrue(firstBoxes.size() >= 2, "Wrapped link should create hitboxes per wrapped line");
            assertEquals(1, secondBoxes.size());

            // Wide Furigana expands slot cleanly without colliding with neighbors.
            ParsedRichText wideRuby = TextTagParser.parse("A{rb}光{/rb}{rt}ひかりのしるし{/rt}B");
            StyledTextLayout rubyLayout = new StyledTextLayout(
                    wideRuby, fontManager, 1000.0f, 28.0f, 1.2f, "#f0f6fc", false
            );
            RubyLayout rl = rubyLayout.getRubyLayouts().get(0);
            GlyphQuad qBase = quadAt(rubyLayout, 1);
            GlyphQuad qNextB = quadAt(rubyLayout, 2);
            assertTrue(qNextB.x() >= rl.x() + rl.width() - EPS);
            assertEquals(rl.baseCenterX(), rl.x() + rl.width() * 0.5f, EPS);
            assertTrue(qBase.x() > rl.x());

            // Extra kerning widens laid out line cleanly.
            StyledTextLayout normalK = new StyledTextLayout(
                    TextTagParser.parse("ABCDE"), fontManager, 1000.0f, 24.0f, 1.2f, "#f0f6fc", false
            );
            StyledTextLayout wideK = new StyledTextLayout(
                    TextTagParser.parse("{k=5.0}ABCDE{/k}"), fontManager, 1000.0f, 24.0f, 1.2f, "#f0f6fc", false
            );
            assertTrue(wideK.getTotalWidth() >= normalK.getTotalWidth() + 19.0f);

            // Render safely ignores null or uninitialized RenderPipeline2D in headless tests.
            RenderPipeline2D uninit = new RenderPipeline2D();
            wideK.render(null, null, 0.0f, 0.0f, 1.0, 5, 1.0f);
            wideK.render(uninit, null, 0.0f, 0.0f, 1.0, 5, 1.0f);
        }
    }

    private static GlyphQuad quadAt(StyledTextLayout layout, int charIndex) {
        return layout.getGlyphQuads().stream()
                .filter(q -> q.charIndex() == charIndex)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No quad at charIndex " + charIndex));
    }
}
