package de.swfdtf.graphics;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Dynamic RGBA8 premultiplied glyph texture atlas backed by LWJGL {@link STBTruetype} for
 * TrueType/OpenType fonts, with a self-contained CPU procedural bitmap fallback for headless
 * environments and missing font assets.
 *
 * <p>All atlas texels are stored as premultiplied white coverage {@code (cov, cov, cov, cov)} so
 * {@link RenderPipeline2D} can tint text directly with straight RGBA colors under
 * {@code GL_ONE, GL_ONE_MINUS_SRC_ALPHA} blending.</p>
 */
public final class FontAtlas implements AutoCloseable {

    private static final int DEFAULT_INITIAL_ATLAS_SIZE = 512;
    private static final int MAX_ATLAS_SIZE = 4096;
    private static final int GLYPH_PADDING_PX = 2;

    /**
     * Immutable metrics and normalized UV coordinates for a single baked glyph.
     *
     * @param codepoint       Unicode codepoint
     * @param advanceWidth    horizontal cursor advance in pixels at {@link #getBaseSize()}
     * @param leftSideBearing horizontal left bearing in pixels at {@link #getBaseSize()}
     * @param x0              quad left offset relative to pen X at {@link #getBaseSize()}
     * @param y0              quad top offset relative to baseline Y (negative = above baseline)
     * @param x1              quad right offset relative to pen X
     * @param y1              quad bottom offset relative to baseline Y (positive = below baseline)
     * @param u0              left UV coordinate in {@code [0, 1]}
     * @param v0              top UV coordinate in {@code [0, 1]}
     * @param u1              right UV coordinate in {@code [0, 1]}
     * @param v1              bottom UV coordinate in {@code [0, 1]}
     */
    public record GlyphMetrics(
            int codepoint,
            float advanceWidth,
            float leftSideBearing,
            float x0,
            float y0,
            float x1,
            float y1,
            float u0,
            float v0,
            float u1,
            float v1
    ) {
        /**
         * Returns the unscaled glyph bitmap width in pixels.
         *
         * @return unscaled glyph bitmap width in pixels
         */
        public float width() {
            return x1 - x0;
        }

        /**
         * Returns the unscaled glyph bitmap height in pixels.
         *
         * @return unscaled glyph bitmap height in pixels
         */
        public float height() {
            return y1 - y0;
        }
    }

    private final float baseSize;
    private final boolean builtinFallback;
    private final float ascent;
    private final float descent;
    private final float lineGap;
    private final float stbScale;

    private ByteBuffer fontBuffer;
    private STBTTFontinfo fontInfo;

    private int atlasWidth;
    private int atlasHeight;
    private byte[] atlasRgba;
    private int shelfX = GLYPH_PADDING_PX;
    private int shelfY = GLYPH_PADDING_PX;
    private int shelfHeight = 0;

    private final Map<Integer, GlyphMetrics> glyphs = new HashMap<>();
    private final Map<Long, Float> kerningCache = new HashMap<>();

    private int glTextureId = 0;
    private boolean dirty = true;
    private boolean disposed = false;

    private FontAtlas(
            float baseSize,
            boolean builtinFallback,
            float ascent,
            float descent,
            float lineGap,
            float stbScale,
            ByteBuffer fontBuffer,
            STBTTFontinfo fontInfo,
            int initialAtlasSize
    ) {
        this.baseSize = baseSize;
        this.builtinFallback = builtinFallback;
        this.ascent = ascent;
        this.descent = descent;
        this.lineGap = lineGap;
        this.stbScale = stbScale;
        this.fontBuffer = fontBuffer;
        this.fontInfo = fontInfo;
        this.atlasWidth = initialAtlasSize;
        this.atlasHeight = initialAtlasSize;
        this.atlasRgba = new byte[initialAtlasSize * initialAtlasSize * 4];
    }

    /**
     * Creates a CPU-procedural bitmap fallback {@code FontAtlas} covering printable ASCII,
     * German umlauts ({@code äöüÄÖÜß}), common punctuation, and deterministic replacement boxes
     * without requiring a {@code .ttf} file or {@code java.awt}.
     *
     * @param baseSize target pixel height ({@code > 0})
     * @param uploadGl whether to upload the atlas immediately to the active OpenGL context
     * @return initialized fallback {@link FontAtlas}
     */
    public static FontAtlas createBuiltinFallback(float baseSize, boolean uploadGl) {
        float validSize = validateBaseSize(baseSize);
        float ascentPx = validSize * 0.80f;
        float descentPx = -validSize * 0.20f;
        float lineGapPx = validSize * 0.12f;
        int initialSize = validSize > 36.0f ? 1024 : DEFAULT_INITIAL_ATLAS_SIZE;

        FontAtlas atlas = new FontAtlas(
                validSize,
                true,
                ascentPx,
                descentPx,
                lineGapPx,
                1.0f,
                null,
                null,
                initialSize
        );
        atlas.prebakeStandardCodepoints();
        if (uploadGl) {
            atlas.uploadToOpenGl();
        }
        return atlas;
    }

    /**
     * Bakes a {@code FontAtlas} from raw TrueType/OpenType bytes via LWJGL {@link STBTruetype}.
     *
     * @param fontBytes raw {@code .ttf} or {@code .otf} byte array
     * @param baseSize  target pixel height ({@code > 0})
     * @param uploadGl  whether to upload the baked atlas immediately to OpenGL
     * @return initialized {@link FontAtlas}
     * @throws IllegalArgumentException if {@code fontBytes} is null/empty or invalid TTF/OTF data
     */
    public static FontAtlas fromTrueTypeBytes(byte[] fontBytes, float baseSize, boolean uploadGl) {
        Objects.requireNonNull(fontBytes, "fontBytes must not be null");
        if (fontBytes.length == 0) {
            throw new IllegalArgumentException("fontBytes must not be empty");
        }
        float validSize = validateBaseSize(baseSize);

        ByteBuffer nativeBuf = MemoryUtil.memAlloc(fontBytes.length);
        STBTTFontinfo info = STBTTFontinfo.calloc();
        boolean ownershipTransferred = false;
        try {
            nativeBuf.put(fontBytes).flip();
            int fontOffset = STBTruetype.stbtt_GetFontOffsetForIndex(nativeBuf, 0);
            if (fontOffset < 0) {
                fontOffset = 0;
            }
            if (!STBTruetype.stbtt_InitFont(info, nativeBuf, fontOffset)) {
                throw new IllegalArgumentException("Invalid TrueType/OpenType font data");
            }

            float scale = STBTruetype.stbtt_ScaleForPixelHeight(info, validSize);
            float ascentPx;
            float descentPx;
            float lineGapPx;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer pAscent = stack.mallocInt(1);
                IntBuffer pDescent = stack.mallocInt(1);
                IntBuffer pLineGap = stack.mallocInt(1);
                STBTruetype.stbtt_GetFontVMetrics(info, pAscent, pDescent, pLineGap);
                ascentPx = pAscent.get(0) * scale;
                descentPx = pDescent.get(0) * scale;
                lineGapPx = pLineGap.get(0) * scale;
            }

            int initialSize = validSize > 28.0f ? 1024 : DEFAULT_INITIAL_ATLAS_SIZE;
            FontAtlas atlas = new FontAtlas(
                    validSize,
                    false,
                    ascentPx,
                    descentPx,
                    lineGapPx,
                    scale,
                    nativeBuf,
                    info,
                    initialSize
            );
            ownershipTransferred = true;
            atlas.prebakeStandardCodepoints();
            if (uploadGl) {
                atlas.uploadToOpenGl();
            }
            return atlas;
        } finally {
            if (!ownershipTransferred) {
                info.free();
                MemoryUtil.memFree(nativeBuf);
            }
        }
    }

    /**
     * Loads and bakes a {@code FontAtlas} from a {@code .ttf} or {@code .otf} file on disk.
     *
     * @param fontPath path to font file
     * @param baseSize target pixel height ({@code > 0})
     * @param uploadGl whether to upload immediately to OpenGL
     * @return initialized {@link FontAtlas}
     * @throws IOException if the file cannot be read
     */
    public static FontAtlas fromTrueTypeFile(Path fontPath, float baseSize, boolean uploadGl) throws IOException {
        Objects.requireNonNull(fontPath, "fontPath must not be null");
        byte[] bytes = Files.readAllBytes(fontPath);
        return fromTrueTypeBytes(bytes, baseSize, uploadGl);
    }

    private static float validateBaseSize(float baseSize) {
        if (!Float.isFinite(baseSize) || baseSize <= 0.0f) {
            throw new IllegalArgumentException("baseSize must be finite and > 0, got " + baseSize);
        }
        return baseSize;
    }

    private void prebakeStandardCodepoints() {
        for (int cp = 0x0020; cp <= 0x007E; cp++) {
            bakeCodepoint(cp);
        }
        for (int cp = 0x00A0; cp <= 0x024F; cp++) {
            bakeCodepoint(cp);
        }
        int[] extraPunctuation = {
            0x2013, 0x2014, 0x2018, 0x2019, 0x201A, 0x201C, 0x201D, 0x201E,
            0x2022, 0x2026, 0x20AC, 0x2190, 0x2191, 0x2192, 0x2193, 0x25A1, 0xFFFD
        };
        for (int cp : extraPunctuation) {
            bakeCodepoint(cp);
        }
    }

    /**
     * Uploads or refreshes the CPU-side premultiplied RGBA8 atlas into an OpenGL 2D texture.
     * Must only be called when a current OpenGL context exists on the calling thread.
     */
    public void uploadToOpenGl() {
        checkNotDisposed();
        if (glTextureId == 0) {
            glTextureId = GL11.glGenTextures();
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        ByteBuffer uploadBuf = MemoryUtil.memAlloc(atlasRgba.length);
        try {
            uploadBuf.put(atlasRgba).flip();
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL11.GL_RGBA8,
                    atlasWidth,
                    atlasHeight,
                    0,
                    GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE,
                    uploadBuf
            );
        } finally {
            MemoryUtil.memFree(uploadBuf);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
        dirty = false;
    }

    /**
     * Returns metrics for {@code codepoint}, dynamically baking it into the atlas if it has not
     * yet been rasterized.
     *
     * @param codepoint Unicode codepoint
     * @return non-null {@link GlyphMetrics}
     */
    public GlyphMetrics getGlyph(int codepoint) {
        checkNotDisposed();
        GlyphMetrics existing = glyphs.get(codepoint);
        if (existing != null) {
            return existing;
        }
        return bakeCodepoint(codepoint);
    }

    /**
     * Ensures every codepoint in {@code text} is baked into the atlas and refreshes the OpenGL
     * texture if it was already uploaded and modified.
     *
     * @param text text string to prepare (may be {@code null})
     */
    public void ensureCodepoints(String text) {
        checkNotDisposed();
        if (text == null || text.isEmpty()) {
            return;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            getGlyph(cp);
            i += Character.charCount(cp);
        }
        if (dirty && glTextureId != 0) {
            uploadToOpenGl();
        }
    }

    /**
     * Computes horizontal pixel kerning adjustment between {@code cp1} and {@code cp2} scaled to {@code fontSize}.
     *
     * @param cp1      left Unicode codepoint
     * @param cp2      right Unicode codepoint
     * @param fontSize target pixel size
     * @return horizontal kerning offset in pixels
     */
    public float getKerning(int cp1, int cp2, float fontSize) {
        checkNotDisposed();
        if (cp1 <= 0 || cp2 <= 0) {
            return 0.0f;
        }
        long key = (((long) cp1) << 32) | (cp2 & 0xFFFFFFFFL);
        Float cached = kerningCache.get(key);
        float baseKern;
        if (cached != null) {
            baseKern = cached;
        } else {
            if (!builtinFallback && fontInfo != null) {
                baseKern = STBTruetype.stbtt_GetCodepointKernAdvance(fontInfo, cp1, cp2) * stbScale;
            } else {
                baseKern = computeBuiltinKerning(cp1, cp2) * baseSize;
            }
            kerningCache.put(key, baseKern);
        }
        return baseKern * (fontSize / baseSize);
    }

    /**
     * Measures the rendered horizontal advance width of {@code text} at {@code fontSize} with optional
     * {@code extraKerning} added between adjacent codepoints.
     *
     * @param text         text string to measure
     * @param fontSize     target pixel font size
     * @param extraKerning additional inter-glyph horizontal spacing in pixels
     * @return measured width in pixels ({@code >= 0})
     */
    public float measureTextWidth(String text, float fontSize, float extraKerning) {
        checkNotDisposed();
        if (text == null || text.isEmpty()) {
            return 0.0f;
        }
        float scale = fontSize / baseSize;
        float width = 0.0f;
        int prevCp = -1;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n' || cp == '\r') {
                prevCp = -1;
                continue;
            }
            if (prevCp != -1) {
                width += getKerning(prevCp, cp, fontSize) + extraKerning;
            }
            GlyphMetrics gm = getGlyph(cp);
            width += gm.advanceWidth() * scale;
            prevCp = cp;
        }
        return Math.max(0.0f, width);
    }

    /**
     * Returns whether {@code codepoint} is already cached in this atlas.
     *
     * @param codepoint Unicode code point
     * @return {@code true} if {@code codepoint} is already cached in this atlas
     */
    public boolean hasGlyph(int codepoint) {
        return glyphs.containsKey(codepoint);
    }

    /**
     * Returns the OpenGL texture ID.
     *
     * @return OpenGL texture ID, or {@code 0} if not uploaded (e.g. headless mode)
     */
    public int getGlTextureId() {
        return glTextureId;
    }

    /**
     * Returns the atlas bake pixel size.
     *
     * @return atlas bake pixel size
     */
    public float getBaseSize() {
        return baseSize;
    }

    /**
     * Returns the distance above baseline at base size.
     *
     * @return distance above baseline in pixels at {@link #getBaseSize()}
     */
    public float getAscent() {
        return ascent;
    }

    /**
     * Returns the distance above baseline scaled to {@code fontSize}.
     *
     * @param fontSize target pixel size
     * @return distance above baseline scaled to {@code fontSize}
     */
    public float getAscent(float fontSize) {
        return ascent * (fontSize / baseSize);
    }

    /**
     * Returns the signed descent below baseline at base size.
     *
     * @return signed descent below baseline in pixels at {@link #getBaseSize()} (typically {@code <= 0})
     */
    public float getDescent() {
        return descent;
    }

    /**
     * Returns the recommended inter-line gap at base size.
     *
     * @return recommended inter-line gap in pixels at {@link #getBaseSize()}
     */
    public float getLineGap() {
        return lineGap;
    }

    /**
     * Returns the full line height at base size.
     *
     * @return full line height {@code ascent - descent + lineGap} at {@link #getBaseSize()}
     */
    public float getLineHeight() {
        return Math.max(1.0f, ascent - descent + lineGap);
    }

    /**
     * Returns the full line height scaled to {@code fontSize}.
     *
     * @param fontSize target pixel size
     * @return full line height scaled to {@code fontSize}
     */
    public float getLineHeight(float fontSize) {
        return getLineHeight() * (fontSize / baseSize);
    }

    /**
     * Returns the atlas texture width in pixels.
     *
     * @return atlas texture width in pixels
     */
    public int getAtlasWidth() {
        return atlasWidth;
    }

    /**
     * Returns the atlas texture height in pixels.
     *
     * @return atlas texture height in pixels
     */
    public int getAtlasHeight() {
        return atlasHeight;
    }

    /**
     * Returns whether this atlas uses CPU procedural fallback bitmaps.
     *
     * @return {@code true} if this atlas uses CPU procedural fallback bitmaps
     */
    public boolean isBuiltinFallback() {
        return builtinFallback;
    }

    /**
     * Returns whether this atlas has been disposed.
     *
     * @return {@code true} if {@link #dispose()} has been called
     */
    public boolean isDisposed() {
        return disposed;
    }

    /**
     * Returns a defensive copy of the CPU-side atlas pixel buffer.
     *
     * @return defensive copy of the CPU-side RGBA8 premultiplied atlas buffer
     */
    public byte[] getAtlasRgbaCopy() {
        return atlasRgba.clone();
    }

    /**
     * Releases native STB font memory and deletes the OpenGL texture if allocated.
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (glTextureId != 0) {
            GL11.glDeleteTextures(glTextureId);
            glTextureId = 0;
        }
        if (fontInfo != null) {
            fontInfo.free();
            fontInfo = null;
        }
        if (fontBuffer != null) {
            MemoryUtil.memFree(fontBuffer);
            fontBuffer = null;
        }
    }

    /**
     * Alias for {@link #dispose()} supporting try-with-resources.
     */
    @Override
    public void close() {
        dispose();
    }

    private GlyphMetrics bakeCodepoint(int codepoint) {
        if (!builtinFallback && fontInfo != null) {
            int glyphIdx = STBTruetype.stbtt_FindGlyphIndex(fontInfo, codepoint);
            if (glyphIdx != 0 || codepoint == 0xFFFD || codepoint == ' ') {
                return bakeStbGlyph(codepoint);
            }
            if (glyphs.containsKey(0xFFFD)) {
                GlyphMetrics rep = glyphs.get(0xFFFD);
                GlyphMetrics aliased = new GlyphMetrics(
                        codepoint, rep.advanceWidth(), rep.leftSideBearing(),
                        rep.x0(), rep.y0(), rep.x1(), rep.y1(),
                        rep.u0(), rep.v0(), rep.u1(), rep.v1()
                );
                glyphs.put(codepoint, aliased);
                return aliased;
            }
        }
        return bakeBuiltinGlyph(codepoint);
    }

    private GlyphMetrics bakeStbGlyph(int codepoint) {
        float advPx;
        float lsbPx;
        int ix0;
        int iy0;
        int ix1;
        int iy1;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pAdv = stack.mallocInt(1);
            IntBuffer pLsb = stack.mallocInt(1);
            STBTruetype.stbtt_GetCodepointHMetrics(fontInfo, codepoint, pAdv, pLsb);
            advPx = pAdv.get(0) * stbScale;
            lsbPx = pLsb.get(0) * stbScale;

            IntBuffer px0 = stack.mallocInt(1);
            IntBuffer py0 = stack.mallocInt(1);
            IntBuffer px1 = stack.mallocInt(1);
            IntBuffer py1 = stack.mallocInt(1);
            STBTruetype.stbtt_GetCodepointBitmapBox(fontInfo, codepoint, stbScale, stbScale, px0, py0, px1, py1);
            ix0 = px0.get(0);
            iy0 = py0.get(0);
            ix1 = px1.get(0);
            iy1 = py1.get(0);
        }

        int bw = Math.max(0, ix1 - ix0);
        int bh = Math.max(0, iy1 - iy0);

        if (bw == 0 || bh == 0) {
            GlyphMetrics empty = new GlyphMetrics(codepoint, advPx, lsbPx, 0, 0, 0, 0, 0, 0, 0, 0);
            glyphs.put(codepoint, empty);
            return empty;
        }

        int[] slot = allocateAtlasSlot(bw, bh);
        if (slot == null) {
            GlyphMetrics fallback = new GlyphMetrics(codepoint, advPx, lsbPx, 0, 0, 0, 0, 0, 0, 0, 0);
            glyphs.put(codepoint, fallback);
            return fallback;
        }

        ByteBuffer bitmap = MemoryUtil.memAlloc(bw * bh);
        try {
            STBTruetype.stbtt_MakeCodepointBitmap(fontInfo, bitmap, bw, bh, bw, stbScale, stbScale, codepoint);
            copyCoverageToAtlas(bitmap, bw, bh, slot[0], slot[1]);
        } finally {
            MemoryUtil.memFree(bitmap);
        }

        GlyphMetrics metrics = new GlyphMetrics(
                codepoint,
                advPx,
                lsbPx,
                ix0,
                iy0,
                ix1,
                iy1,
                slot[0] / (float) atlasWidth,
                slot[1] / (float) atlasHeight,
                (slot[0] + bw) / (float) atlasWidth,
                (slot[1] + bh) / (float) atlasHeight
        );
        glyphs.put(codepoint, metrics);
        dirty = true;
        return metrics;
    }

    private GlyphMetrics bakeBuiltinGlyph(int codepoint) {
        if (codepoint == ' ' || codepoint == '\u00A0' || codepoint == '\t') {
            float spaceAdv = codepoint == '\t' ? baseSize * 1.8f : baseSize * 0.36f;
            GlyphMetrics space = new GlyphMetrics(codepoint, spaceAdv, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            glyphs.put(codepoint, space);
            return space;
        }

        String[] rows = BuiltinGlyphPatterns.patternFor(codepoint);
        boolean wideCjk = isWideCodepoint(codepoint);
        int gridW = rows != null ? BuiltinGlyphPatterns.GRID_WIDTH : (wideCjk ? 7 : 5);
        int gridH = BuiltinGlyphPatterns.GRID_HEIGHT;

        int bw = Math.max(4, Math.round(baseSize * (wideCjk ? 0.82f : 0.50f)));
        int bh = Math.max(7, Math.round(baseSize * 0.90f));
        float advance = wideCjk
                ? baseSize * 0.92f
                : computeBuiltinAdvance(codepoint, bw);
        float lsb = Math.max(0.5f, baseSize * 0.04f);
        float yTop = -ascent;
        float yBottom = yTop + bh;

        int[] slot = allocateAtlasSlot(bw, bh);
        if (slot == null) {
            GlyphMetrics fallback = new GlyphMetrics(codepoint, advance, lsb, lsb, yTop, lsb + bw, yBottom, 0, 0, 0, 0);
            glyphs.put(codepoint, fallback);
            return fallback;
        }

        rasterizeBuiltinPattern(rows, wideCjk, gridW, gridH, bw, bh, slot[0], slot[1]);

        GlyphMetrics metrics = new GlyphMetrics(
                codepoint,
                advance,
                lsb,
                lsb,
                yTop,
                lsb + bw,
                yBottom,
                slot[0] / (float) atlasWidth,
                slot[1] / (float) atlasHeight,
                (slot[0] + bw) / (float) atlasWidth,
                (slot[1] + bh) / (float) atlasHeight
        );
        glyphs.put(codepoint, metrics);
        dirty = true;
        return metrics;
    }

    private void rasterizeBuiltinPattern(
            String[] rows,
            boolean wideCjk,
            int gridW,
            int gridH,
            int bw,
            int bh,
            int dstX,
            int dstY
    ) {
        for (int py = 0; py < bh; py++) {
            float gy = (py + 0.5f) / bh * gridH - 0.5f;
            for (int px = 0; px < bw; px++) {
                float gx = (px + 0.5f) / bw * gridW - 0.5f;
                float cov;
                if (rows != null) {
                    cov = samplePatternBilinear(rows, gx, gy);
                } else {
                    cov = sampleFallbackBox(px, py, bw, bh, wideCjk);
                }
                int c = Math.clamp(Math.round(cov * 255.0f), 0, 255);
                int idx = ((dstY + py) * atlasWidth + (dstX + px)) * 4;
                byte b = (byte) c;
                atlasRgba[idx] = b;
                atlasRgba[idx + 1] = b;
                atlasRgba[idx + 2] = b;
                atlasRgba[idx + 3] = b;
            }
        }
    }

    private static float samplePatternBilinear(String[] rows, float gx, float gy) {
        int x0 = (int) Math.floor(gx);
        int y0 = (int) Math.floor(gy);
        float tx = gx - x0;
        float ty = gy - y0;

        float s00 = samplePatternBit(rows, x0, y0);
        float s10 = samplePatternBit(rows, x0 + 1, y0);
        float s01 = samplePatternBit(rows, x0, y0 + 1);
        float s11 = samplePatternBit(rows, x0 + 1, y0 + 1);

        float top = s00 + (s10 - s00) * tx;
        float bot = s01 + (s11 - s01) * tx;
        float val = top + (bot - top) * ty;
        return Math.clamp(val * 1.15f, 0.0f, 1.0f);
    }

    private static float samplePatternBit(String[] rows, int x, int y) {
        if (y < 0 || y >= rows.length) {
            return 0.0f;
        }
        String row = rows[y];
        if (x < 0 || x >= row.length()) {
            return 0.0f;
        }
        return row.charAt(x) == '#' ? 1.0f : 0.0f;
    }

    private static float sampleFallbackBox(int px, int py, int bw, int bh, boolean wideCjk) {
        int marginX = Math.max(1, bw / 6);
        int marginTop = Math.max(1, bh / 7);
        int marginBottom = Math.max(1, bh / 6);
        if (px < marginX || px >= bw - marginX || py < marginTop || py >= bh - marginBottom) {
            return 0.0f;
        }
        boolean border = px == marginX || px == bw - marginX - 1
                || py == marginTop || py == bh - marginBottom - 1;
        if (border) {
            return 1.0f;
        }
        if (wideCjk && (px == bw / 2 || py == bh / 2)) {
            return 0.7f;
        }
        return 0.0f;
    }

    private float computeBuiltinAdvance(int cp, int bw) {
        float narrowScale = switch (cp) {
            case 'i', 'l', '!', '.', ',', ':', ';', '\'', '|' -> 0.58f;
            case 'I', 'j', 'f', 't', 'r', '(', ')', '[', ']', '{', '}', '-' -> 0.76f;
            case 'W', 'M', 'w', 'm', 'Ä', 'Ö', 'Ü' -> 1.12f;
            default -> 1.0f;
        };
        return bw * narrowScale + Math.max(1.5f, baseSize * 0.12f);
    }

    private static float computeBuiltinKerning(int cp1, int cp2) {
        if ((cp1 == 'A' && (cp2 == 'V' || cp2 == 'W' || cp2 == 'Y' || cp2 == 'T'))
                || (cp1 == 'V' && (cp2 == 'A' || cp2 == 'a' || cp2 == 'o'))
                || (cp1 == 'T' && (cp2 == 'o' || cp2 == 'a' || cp2 == 'e'))
                || (cp1 == 'Y' && (cp2 == 'o' || cp2 == 'a'))) {
            return -0.06f;
        }
        return 0.0f;
    }

    private static boolean isWideCodepoint(int cp) {
        return (cp >= 0x3000 && cp <= 0x9FFF)
                || (cp >= 0xAC00 && cp <= 0xD7AF)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFF01 && cp <= 0xFF60);
    }

    private int[] allocateAtlasSlot(int bw, int bh) {
        int paddedW = bw + GLYPH_PADDING_PX;
        int paddedH = bh + GLYPH_PADDING_PX;
        if (paddedW > MAX_ATLAS_SIZE || paddedH > MAX_ATLAS_SIZE) {
            return null;
        }

        if (shelfX + paddedW > atlasWidth) {
            shelfX = GLYPH_PADDING_PX;
            shelfY += shelfHeight + GLYPH_PADDING_PX;
            shelfHeight = 0;
        }
        while (shelfY + paddedH > atlasHeight || shelfX + paddedW > atlasWidth) {
            if (!growAtlas()) {
                return null;
            }
            if (shelfX + paddedW > atlasWidth) {
                shelfX = GLYPH_PADDING_PX;
                shelfY += shelfHeight + GLYPH_PADDING_PX;
                shelfHeight = 0;
            }
        }

        int x = shelfX;
        int y = shelfY;
        shelfX += paddedW;
        shelfHeight = Math.max(shelfHeight, bh);
        return new int[]{x, y};
    }

    private boolean growAtlas() {
        int nextW = Math.min(MAX_ATLAS_SIZE, atlasWidth * 2);
        int nextH = Math.min(MAX_ATLAS_SIZE, atlasHeight * 2);
        if (nextW == atlasWidth && nextH == atlasHeight) {
            return false;
        }
        byte[] grown = new byte[nextW * nextH * 4];
        for (int row = 0; row < atlasHeight; row++) {
            System.arraycopy(
                    atlasRgba,
                    row * atlasWidth * 4,
                    grown,
                    row * nextW * 4,
                    atlasWidth * 4
            );
        }

        float scaleU = atlasWidth / (float) nextW;
        float scaleV = atlasHeight / (float) nextH;
        for (Map.Entry<Integer, GlyphMetrics> entry : glyphs.entrySet()) {
            GlyphMetrics g = entry.getValue();
            entry.setValue(new GlyphMetrics(
                    g.codepoint(),
                    g.advanceWidth(),
                    g.leftSideBearing(),
                    g.x0(),
                    g.y0(),
                    g.x1(),
                    g.y1(),
                    g.u0() * scaleU,
                    g.v0() * scaleV,
                    g.u1() * scaleU,
                    g.v1() * scaleV
            ));
        }

        atlasRgba = grown;
        atlasWidth = nextW;
        atlasHeight = nextH;
        dirty = true;
        return true;
    }

    private void copyCoverageToAtlas(ByteBuffer bitmap, int bw, int bh, int dstX, int dstY) {
        for (int py = 0; py < bh; py++) {
            int srcRow = py * bw;
            int dstRow = (dstY + py) * atlasWidth;
            for (int px = 0; px < bw; px++) {
                byte cov = bitmap.get(srcRow + px);
                int idx = (dstRow + dstX + px) * 4;
                atlasRgba[idx] = cov;
                atlasRgba[idx + 1] = cov;
                atlasRgba[idx + 2] = cov;
                atlasRgba[idx + 3] = cov;
            }
        }
    }

    private void checkNotDisposed() {
        if (disposed) {
            throw new IllegalStateException("FontAtlas has already been disposed");
        }
    }

    /**
     * Built-in 5x9 procedural glyph bitmaps for ASCII, German umlauts, and common punctuation.
     */
    private static final class BuiltinGlyphPatterns {
        static final int GRID_WIDTH = 5;
        static final int GRID_HEIGHT = 9;
        private static final Map<Integer, String[]> PATTERNS = buildPatterns();

        static String[] patternFor(int codepoint) {
            return PATTERNS.get(codepoint);
        }

        private static void put(Map<Integer, String[]> map, int cp, String... rows) {
            if (rows.length != GRID_HEIGHT) {
                throw new IllegalArgumentException("Expected 9 rows for glyph " + cp);
            }
            map.put(cp, rows);
        }

        private static Map<Integer, String[]> buildPatterns() {
            Map<Integer, String[]> m = new HashMap<>();

            // Uppercase A-Z (rows 0..6 main height, 7..8 descender space)
            put(m, 'A', "..#..", ".#.#.", "#...#", "#####", "#...#", "#...#", "#...#", ".....", ".....");
            put(m, 'B', "####.", "#...#", "#...#", "####.", "#...#", "#...#", "####.", ".....", ".....");
            put(m, 'C', ".####", "#....", "#....", "#....", "#....", "#....", ".####", ".....", ".....");
            put(m, 'D', "####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####.", ".....", ".....");
            put(m, 'E', "#####", "#....", "#....", "####.", "#....", "#....", "#####", ".....", ".....");
            put(m, 'F', "#####", "#....", "#....", "####.", "#....", "#....", "#....", ".....", ".....");
            put(m, 'G', ".####", "#....", "#....", "#..##", "#...#", "#...#", ".####", ".....", ".....");
            put(m, 'H', "#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#", ".....", ".....");
            put(m, 'I', ".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###.", ".....", ".....");
            put(m, 'J', "..###", "...#.", "...#.", "...#.", "#..#.", "#..#.", ".##..", ".....", ".....");
            put(m, 'K', "#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#", ".....", ".....");
            put(m, 'L', "#....", "#....", "#....", "#....", "#....", "#....", "#####", ".....", ".....");
            put(m, 'M', "#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#", ".....", ".....");
            put(m, 'N', "#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#", ".....", ".....");
            put(m, 'O', ".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.", ".....", ".....");
            put(m, 'P', "####.", "#...#", "#...#", "####.", "#....", "#....", "#....", ".....", ".....");
            put(m, 'Q', ".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#", ".....", ".....");
            put(m, 'R', "####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#", ".....", ".....");
            put(m, 'S', ".####", "#....", "#....", ".###.", "....#", "....#", "####.", ".....", ".....");
            put(m, 'T', "#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#..", ".....", ".....");
            put(m, 'U', "#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.", ".....", ".....");
            put(m, 'V', "#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#..", ".....", ".....");
            put(m, 'W', "#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#", ".....", ".....");
            put(m, 'X', "#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#", ".....", ".....");
            put(m, 'Y', "#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#..", ".....", ".....");
            put(m, 'Z', "#####", "....#", "...#.", "..#..", ".#...", "#....", "#####", ".....", ".....");

            // Lowercase a-z (x-height rows 2..6, ascenders 0..6, descenders 7..8)
            put(m, 'a', ".....", ".....", ".###.", "....#", ".####", "#...#", ".####", ".....", ".....");
            put(m, 'b', "#....", "#....", "####.", "#...#", "#...#", "#...#", "####.", ".....", ".....");
            put(m, 'c', ".....", ".....", ".####", "#....", "#....", "#....", ".####", ".....", ".....");
            put(m, 'd', "....#", "....#", ".####", "#...#", "#...#", "#...#", ".####", ".....", ".....");
            put(m, 'e', ".....", ".....", ".###.", "#...#", "#####", "#....", ".####", ".....", ".....");
            put(m, 'f', "..###", ".#...", "####.", ".#...", ".#...", ".#...", ".#...", ".....", ".....");
            put(m, 'g', ".....", ".....", ".####", "#...#", "#...#", ".####", "....#", "#...#", ".###.");
            put(m, 'h', "#....", "#....", "####.", "#...#", "#...#", "#...#", "#...#", ".....", ".....");
            put(m, 'i', "..#..", ".....", ".##..", "..#..", "..#..", "..#..", ".###.", ".....", ".....");
            put(m, 'j', "...#.", ".....", "..##.", "...#.", "...#.", "...#.", "#..#.", ".##..", ".....");
            put(m, 'k', "#....", "#....", "#..#.", "#.#..", "##...", "#.#..", "#..#.", ".....", ".....");
            put(m, 'l', ".##..", "..#..", "..#..", "..#..", "..#..", "..#..", ".###.", ".....", ".....");
            put(m, 'm', ".....", ".....", "##.#.", "#.#.#", "#.#.#", "#...#", "#...#", ".....", ".....");
            put(m, 'n', ".....", ".....", "####.", "#...#", "#...#", "#...#", "#...#", ".....", ".....");
            put(m, 'o', ".....", ".....", ".###.", "#...#", "#...#", "#...#", ".###.", ".....", ".....");
            put(m, 'p', ".....", ".....", "####.", "#...#", "#...#", "####.", "#....", "#....", "#....");
            put(m, 'q', ".....", ".....", ".####", "#...#", "#...#", ".####", "....#", "....#", "....#");
            put(m, 'r', ".....", ".....", "#.##.", "##...", "#....", "#....", "#....", ".....", ".....");
            put(m, 's', ".....", ".....", ".####", "#....", ".###.", "....#", "####.", ".....", ".....");
            put(m, 't', ".#...", ".#...", "####.", ".#...", ".#...", ".#..#", "..##.", ".....", ".....");
            put(m, 'u', ".....", ".....", "#...#", "#...#", "#...#", "#...#", ".####", ".....", ".....");
            put(m, 'v', ".....", ".....", "#...#", "#...#", "#...#", ".#.#.", "..#..", ".....", ".....");
            put(m, 'w', ".....", ".....", "#...#", "#...#", "#.#.#", "#.#.#", ".#.#.", ".....", ".....");
            put(m, 'x', ".....", ".....", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", ".....", ".....");
            put(m, 'y', ".....", ".....", "#...#", "#...#", "#...#", ".####", "....#", "#...#", ".###.");
            put(m, 'z', ".....", ".....", "#####", "...#.", "..#..", ".#...", "#####", ".....", ".....");

            // Digits 0-9
            put(m, '0', ".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###.", ".....", ".....");
            put(m, '1', "..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###.", ".....", ".....");
            put(m, '2', ".###.", "#...#", "....#", "..##.", ".#...", "#....", "#####", ".....", ".....");
            put(m, '3', "####.", "....#", "....#", ".###.", "....#", "....#", "####.", ".....", ".....");
            put(m, '4', "#...#", "#...#", "#...#", "#####", "....#", "....#", "....#", ".....", ".....");
            put(m, '5', "#####", "#....", "####.", "....#", "....#", "#...#", ".###.", ".....", ".....");
            put(m, '6', ".###.", "#....", "#....", "####.", "#...#", "#...#", ".###.", ".....", ".....");
            put(m, '7', "#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#...", ".....", ".....");
            put(m, '8', ".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###.", ".....", ".....");
            put(m, '9', ".###.", "#...#", "#...#", ".####", "....#", "....#", ".###.", ".....", ".....");

            // Punctuation & symbols
            put(m, '!', "..#..", "..#..", "..#..", "..#..", "..#..", ".....", "..#..", ".....", ".....");
            put(m, '"', ".#.#.", ".#.#.", ".....", ".....", ".....", ".....", ".....", ".....", ".....");
            put(m, '#', ".#.#.", "#####", ".#.#.", ".#.#.", "#####", ".#.#.", ".....", ".....", ".....");
            put(m, '$', "..#..", ".####", "#.#..", ".###.", "..#.#", "####.", "..#..", ".....", ".....");
            put(m, '%', "##..#", "##.#.", "..#..", "..#..", ".#.##", "#..##", ".....", ".....", ".....");
            put(m, '&', ".##..", "#..#.", "#.#..", ".##..", "#..#.", "#..#.", ".##.#", ".....", ".....");
            put(m, '\'', "..#..", "..#..", ".#...", ".....", ".....", ".....", ".....", ".....", ".....");
            put(m, '(', "...#.", "..#..", ".#...", ".#...", ".#...", "..#..", "...#.", ".....", ".....");
            put(m, ')', ".#...", "..#..", "...#.", "...#.", "...#.", "..#..", ".#...", ".....", ".....");
            put(m, '*', ".....", "#.#.#", ".###.", "#####", ".###.", "#.#.#", ".....", ".....", ".....");
            put(m, '+', ".....", "..#..", "..#..", "#####", "..#..", "..#..", ".....", ".....", ".....");
            put(m, ',', ".....", ".....", ".....", ".....", ".....", "..#..", "..#..", ".#...", ".....");
            put(m, '-', ".....", ".....", ".....", "#####", ".....", ".....", ".....", ".....", ".....");
            put(m, '.', ".....", ".....", ".....", ".....", ".....", "..#..", "..#..", ".....", ".....");
            put(m, '/', "....#", "...#.", "...#.", "..#..", ".#...", ".#...", "#....", ".....", ".....");
            put(m, ':', ".....", ".....", "..#..", ".....", ".....", "..#..", ".....", ".....", ".....");
            put(m, ';', ".....", ".....", "..#..", ".....", ".....", "..#..", "..#..", ".#...", ".....");
            put(m, '<', "...#.", "..#..", ".#...", "#....", ".#...", "..#..", "...#.", ".....", ".....");
            put(m, '=', ".....", ".....", "#####", ".....", "#####", ".....", ".....", ".....", ".....");
            put(m, '>', ".#...", "..#..", "...#.", "....#", "...#.", "..#..", ".#...", ".....", ".....");
            put(m, '?', ".###.", "#...#", "....#", "..##.", "..#..", ".....", "..#..", ".....", ".....");
            put(m, '@', ".###.", "#...#", "#.###", "#.#.#", "#.###", "#....", ".###.", ".....", ".....");
            put(m, '[', ".###.", ".#...", ".#...", ".#...", ".#...", ".#...", ".###.", ".....", ".....");
            put(m, '\\', "#....", ".#...", ".#...", "..#..", "...#.", "...#.", "....#", ".....", ".....");
            put(m, ']', ".###.", "...#.", "...#.", "...#.", "...#.", "...#.", ".###.", ".....", ".....");
            put(m, '^', "..#..", ".#.#.", "#...#", ".....", ".....", ".....", ".....", ".....", ".....");
            put(m, '_', ".....", ".....", ".....", ".....", ".....", ".....", ".....", "#####", ".....");
            put(m, '`', ".#...", "..#..", ".....", ".....", ".....", ".....", ".....", ".....", ".....");
            put(m, '{', "..##.", ".#...", ".#...", "#....", ".#...", ".#...", "..##.", ".....", ".....");
            put(m, '|', "..#..", "..#..", "..#..", "..#..", "..#..", "..#..", "..#..", ".....", ".....");
            put(m, '}', ".##..", "...#.", "...#.", "....#", "...#.", "...#.", ".##..", ".....", ".....");
            put(m, '~', ".....", ".....", ".#.#.", "#.#.#", ".....", ".....", ".....", ".....", ".....");

            // German umlauts, Eszett & common European / typographic symbols
            put(m, 'ä', ".#.#.", ".....", ".###.", "....#", ".####", "#...#", ".####", ".....", ".....");
            put(m, 'ö', ".#.#.", ".....", ".###.", "#...#", "#...#", "#...#", ".###.", ".....", ".....");
            put(m, 'ü', ".#.#.", ".....", "#...#", "#...#", "#...#", "#...#", ".####", ".....", ".....");
            put(m, 'Ä', ".#.#.", "..#..", ".#.#.", "#####", "#...#", "#...#", "#...#", ".....", ".....");
            put(m, 'Ö', ".#.#.", ".###.", "#...#", "#...#", "#...#", "#...#", ".###.", ".....", ".....");
            put(m, 'Ü', ".#.#.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.", ".....", ".....");
            put(m, 'ß', ".###.", "#...#", "#...#", "####.", "#...#", "#...#", "#.##.", ".....", ".....");
            put(m, '–', ".....", ".....", ".....", "#####", ".....", ".....", ".....", ".....", ".....");
            put(m, '—', ".....", ".....", ".....", "#####", ".....", ".....", ".....", ".....", ".....");
            put(m, '…', ".....", ".....", ".....", ".....", ".....", ".....", "#.#.#", ".....", ".....");
            put(m, '„', ".....", ".....", ".....", ".....", ".....", ".....", ".#.#.", "#.#..", ".....");
            put(m, '“', ".#.#.", "#.#..", ".....", ".....", ".....", ".....", ".....", ".....", ".....");
            put(m, '”', ".#.#.", ".#.#.", ".....", ".....", ".....", ".....", ".....", ".....", ".....");
            put(m, '‘', "..#..", ".#...", ".....", ".....", ".....", ".....", ".....", ".....", ".....");
            put(m, '’', "..#..", "..#..", ".....", ".....", ".....", ".....", ".....", ".....", ".....");
            put(m, '•', ".....", ".....", "..#..", ".###.", "..#..", ".....", ".....", ".....", ".....");
            put(m, '°', ".###.", "#...#", ".###.", ".....", ".....", ".....", ".....", ".....", ".....");
            put(m, '€', "..###", ".#...", "####.", ".#...", "####.", ".#...", "..###", ".....", ".....");
            put(m, '§', ".###.", "#....", ".###.", "#...#", ".###.", "....#", ".###.", ".....", ".....");
            put(m, '←', ".....", "..#..", ".#...", "#####", ".#...", "..#..", ".....", ".....", ".....");
            put(m, '↑', ".....", "..#..", ".###.", "#.#.#", "..#..", "..#..", ".....", ".....", ".....");
            put(m, '→', ".....", "..#..", "...#.", "#####", "...#.", "..#..", ".....", ".....", ".....");
            put(m, '↓', ".....", "..#..", "..#..", "#.#.#", ".###.", "..#..", ".....", ".....", ".....");
            put(m, '□', "#####", "#...#", "#...#", "#...#", "#...#", "#...#", "#####", ".....", ".....");
            put(m, 0xFFFD, "#####", "#.#.#", "##.##", "#.#.#", "#####", "#...#", "#####", ".....", ".....");

            return Map.copyOf(m);
        }
    }
}
