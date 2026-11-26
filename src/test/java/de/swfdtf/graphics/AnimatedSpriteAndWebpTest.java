package de.swfdtf.graphics;

import de.swfdtf.math.Rect2f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless contract tests for {@link AnimatedSpriteNode} ({@code #0172}), {@link WebpVp8lDecoder}
 * ({@code #0189}), {@link FontAtlas} SDF/MSDF helpers ({@code #0173}), and Kinsoku Shori
 * punctuation wrapping in {@link StyledTextLayout} ({@code #0685}, {@code #0940}).
 */
final class AnimatedSpriteAndWebpTest {

    @Test
    void animatedSpriteSupportsLoopPingPongOnceAndValidatesArgs() {
        assertThrows(IllegalArgumentException.class, () -> new AnimatedSpriteNode("bad", 0, 2, 4.0f));
        assertThrows(IllegalArgumentException.class, () -> new AnimatedSpriteNode("bad", 2, 0, 4.0f));
        assertThrows(IllegalArgumentException.class, () -> new AnimatedSpriteNode("bad", 2, 2, -1.0f));

        AnimatedSpriteNode loop = new AnimatedSpriteNode("hero", 4, 1, 4.0f, AnimatedSpriteNode.PlayMode.LOOP);
        Rect2f uv0 = loop.getUvRect();
        assertEquals(0, loop.getCurrentFrame());
        loop.update(0.25);
        assertEquals(1, loop.getCurrentFrame());
        assertNotEquals(uv0, loop.getUvRect());
        loop.update(1000.75);
        assertEquals(0, loop.getCurrentFrame());

        AnimatedSpriteNode pingPong = new AnimatedSpriteNode(
                "pp", 3, 1, 3.0f, AnimatedSpriteNode.PlayMode.PING_PONG
        );
        pingPong.update(1.0); // step = 3 on [0,1,2] -> reverses to 1
        assertEquals(1, pingPong.getCurrentFrame());

        AnimatedSpriteNode once = new AnimatedSpriteNode("once", 2, 2, 2.0f, AnimatedSpriteNode.PlayMode.ONCE);
        once.update(10.0);
        assertEquals(3, once.getCurrentFrame());
        assertTrue(once.isFinished());
        once.reset();
        assertEquals(0, once.getCurrentFrame());
        assertFalse(once.isFinished());
    }

    @Test
    void webpVp8lDecodesSolidAndRejectsCorruptStreams(@TempDir Path tempDir) throws Exception {
        byte[] valid = buildMinimalVp8l(4, 3);
        assertTrue(WebpVp8lDecoder.isWebp(valid));
        WebpVp8lDecoder.DecodedRgba decoded = WebpVp8lDecoder.decode(valid);
        assertEquals(4, decoded.width());
        assertEquals(3, decoded.height());
        byte[] px = decoded.straightRgba();
        assertEquals(4 * 3 * 4, px.length);
        assertEquals(0x40, px[0] & 0xFF);
        assertEquals(0x80, px[1] & 0xFF);
        assertEquals(0xC0, px[2] & 0xFF);
        assertEquals(0xFF, px[3] & 0xFF);

        Path webpPath = tempDir.resolve("sample.webp");
        java.nio.file.Files.write(webpPath, valid);
        int[] dims = new int[2];
        byte[] tmBytes = TextureManager.readImageFileRgba(webpPath, dims);
        assertEquals(4, dims[0]);
        assertEquals(3, dims[1]);
        assertEquals(px.length, tmBytes.length);

        assertThrows(IllegalStateException.class,
                () -> WebpVp8lDecoder.decode(new byte[]{'R', 'I', 'F', 'F', 8, 0, 0, 0, 'W', 'E', 'B', 'P'}));
    }

    @Test
    void fontAtlasSdfAndMsdfHelpersProduceValidDistanceFields() {
        byte[] coverage = new byte[16 * 16];
        for (int y = 4; y < 12; y++) {
            for (int x = 4; x < 12; x++) {
                coverage[y * 16 + x] = (byte) 255;
            }
        }
        byte[] sdf = FontAtlas.computeSdfFromCoverage(coverage, 16, 16, 4.0f);
        assertTrue((sdf[8 * 16 + 8] & 0xFF) > 128);
        assertTrue((sdf[0] & 0xFF) < 128);
        assertEquals(0.5f, FontAtlas.evaluateMsdfMedian(0.2f, 0.5f, 0.9f), 1e-5f);
        assertTrue(FontAtlas.evaluateSdfCoverage(0.75f, 4.0f, 1.0f) > 0.95f);

        try (FontAtlas sdfAtlas = FontAtlas.createSdfBuiltinFallback(24.0f, false)) {
            assertEquals(FontAtlas.GlyphFieldMode.SDF, sdfAtlas.getFieldMode());
            assertTrue(sdfAtlas.hasGlyph('A'));
        }
    }

    @Test
    void kinsokuShoriPreventsLineStartAndLineEndViolations() {
        String text = "あいうえお。かきくけこ。「さしすせそ」たちつてと。";
        TextTagParser.ParsedRichText parsed = TextTagParser.parse(text, k -> null);
        try (FontManager fm = new FontManager()) {
            StyledTextLayout single = new StyledTextLayout(
                    parsed, fm, Float.POSITIVE_INFINITY, 24.0f, 1.2f, "#ffffff", false
            );
            float limit = single.getGlyphQuads().get(5).x() + 1.0f;
            StyledTextLayout wrapped = new StyledTextLayout(parsed, fm, limit, 24.0f, 1.2f, "#ffffff", false);
            for (StyledTextLayout.TextLine line : wrapped.getLines()) {
                if (line.endCharIndex() <= line.startCharIndex()) {
                    continue;
                }
                String slice = parsed.plainText().substring(line.startCharIndex(), line.endCharIndex());
                if (line.lineIndex() > 0) {
                    assertFalse("。、」』）！？・ー".indexOf(slice.charAt(0)) >= 0, "Orphaned start in: " + slice);
                }
                if (line.lineIndex() < wrapped.getLineCount() - 1) {
                    assertFalse("「『（".indexOf(slice.charAt(slice.length() - 1)) >= 0,
                            "Trailing bracket in: " + slice);
                }
            }
        }
    }

    private static byte[] buildMinimalVp8l(int w, int h) {
        int hdr = ((w - 1) & 0x3FFF) | (((h - 1) & 0x3FFF) << 14) | (1 << 28);
        // 3 header zero bits (transform=0, cache=0, meta=0) + five 1-symbol simple codes
        int[] vals = {0x80, 0x40, 0xC0, 0xFF, 0x00};
        boolean[] eightBit = {true, true, true, true, false};
        long bitBuf = 0L;
        int bitCount = 3; // initial 3 zero bits
        byte[] raw = new byte[16];
        int outPos = 0;
        for (int i = 0; i < vals.length; i++) {
            int code = 1 | ((eightBit[i] ? 1 : 0) << 2) | (vals[i] << 3);
            int width = eightBit[i] ? 11 : 4;
            bitBuf |= (long) code << bitCount;
            bitCount += width;
            while (bitCount >= 8) {
                raw[outPos++] = (byte) (bitBuf & 0xFF);
                bitBuf >>>= 8;
                bitCount -= 8;
            }
        }
        if (bitCount > 0) {
            raw[outPos++] = (byte) (bitBuf & 0xFF);
        }
        int vp8lLen = 5 + outPos;
        int riffLen = 4 + 8 + vp8lLen;
        ByteBuffer buf = ByteBuffer.allocate(8 + riffLen).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F').putInt(riffLen);
        buf.put((byte) 'W').put((byte) 'E').put((byte) 'B').put((byte) 'P');
        buf.put((byte) 'V').put((byte) 'P').put((byte) '8').put((byte) 'L').putInt(vp8lLen);
        buf.put((byte) 0x2F).putInt(hdr);
        buf.put(raw, 0, outPos);
        return buf.array();
    }
}
