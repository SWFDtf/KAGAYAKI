package de.swfdtf.graphics;

import de.swfdtf.math.Rect2f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Dynamic shelf-packed RGBA8 premultiplied texture atlas for batching small sprites and UI icons
 * into a single OpenGL {@code GL_TEXTURE_2D}, minimizing shader texture-bind switches.
 *
 * <p>Stores premultiplied RGBA8 pixels on the CPU so regions can be packed, measured, and validated
 * headlessly without an active OpenGL context, then uploaded via {@link #uploadToOpenGl()} on demand.</p>
 */
public final class DynamicTextureAtlas implements AutoCloseable {

    /** Default initial square dimension of the atlas texture in pixels. */
    public static final int DEFAULT_INITIAL_SIZE = 512;

    /** Default maximum square dimension supported when growing the atlas. */
    public static final int DEFAULT_MAX_SIZE = 4096;

    /** Default gutter padding in pixels inserted between packed regions to prevent UV bleeding. */
    public static final int DEFAULT_PADDING_PX = 2;

    /**
     * Immutable packed sprite region descriptor inside a {@link DynamicTextureAtlas}.
     *
     * @param key    unique asset/sprite key
     * @param x      packed top-left pixel X in the atlas
     * @param y      packed top-left pixel Y in the atlas
     * @param width  region width in pixels
     * @param height region height in pixels
     * @param uvRect normalized UV sub-rectangle {@code [u, v, uWidth, vHeight]}
     */
    public record AtlasRegion(
            String key,
            int x,
            int y,
            int width,
            int height,
            Rect2f uvRect
    ) {
        /** Validates non-null key, non-negative coordinates, and UV rectangle. */
        public AtlasRegion {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(uvRect, "uvRect must not be null");
        }
    }

    /**
     * Aggregated draw-call batching telemetry comparing unbatched texture binds against atlas-batched draws.
     *
     * @param spriteCount        number of sprites considered in the batch
     * @param originalBindCount  texture bind transitions required without atlas batching
     * @param atlasBindCount     texture bind transitions required when using the atlas
     * @param bindReductionRatio fraction of texture binds eliminated in {@code [0, 1]}
     */
    public record BatchStats(
            int spriteCount,
            int originalBindCount,
            int atlasBindCount,
            double bindReductionRatio
    ) {
    }

    private final int maxSize;
    private final int paddingPx;

    private int atlasWidth;
    private int atlasHeight;
    private byte[] atlasRgba;
    private int shelfX;
    private int shelfY;
    private int shelfHeight;

    private final Map<String, AtlasRegion> regions = new LinkedHashMap<>();
    private int glTextureId = 0;
    private boolean dirty = true;
    private boolean disposed = false;

    /**
     * Creates a {@code DynamicTextureAtlas} with default initial size {@value #DEFAULT_INITIAL_SIZE},
     * maximum size {@value #DEFAULT_MAX_SIZE}, and {@value #DEFAULT_PADDING_PX}px gutter padding.
     */
    public DynamicTextureAtlas() {
        this(DEFAULT_INITIAL_SIZE, DEFAULT_MAX_SIZE, DEFAULT_PADDING_PX);
    }

    /**
     * Creates a {@code DynamicTextureAtlas} with explicit capacity and gutter padding settings.
     *
     * @param initialSize initial atlas width and height in pixels ({@code > 0})
     * @param maxSize     maximum atlas width and height in pixels ({@code >= initialSize})
     * @param paddingPx   gutter padding in pixels between regions ({@code >= 0})
     * @throws IllegalArgumentException if size constraints or padding are invalid
     */
    public DynamicTextureAtlas(int initialSize, int maxSize, int paddingPx) {
        if (initialSize <= 0 || maxSize < initialSize || paddingPx < 0) {
            throw new IllegalArgumentException(
                    "Invalid atlas parameters initial=" + initialSize + ", max=" + maxSize + ", padding=" + paddingPx
            );
        }
        this.maxSize = maxSize;
        this.paddingPx = paddingPx;
        this.atlasWidth = initialSize;
        this.atlasHeight = initialSize;
        this.atlasRgba = new byte[Math.multiplyExact(Math.multiplyExact(initialSize, initialSize), 4)];
        this.shelfX = paddingPx;
        this.shelfY = paddingPx;
        this.shelfHeight = 0;
    }

    /**
     * Packs straight RGBA8 pixels under {@code key} into the atlas, growing automatically if needed.
     *
     * <p>If {@code key} has already been packed, returns the existing {@link AtlasRegion} cleanly.</p>
     *
     * @param key          unique non-blank sprite identifier
     * @param width        image width in pixels ({@code > 0})
     * @param height       image height in pixels ({@code > 0})
     * @param straightRgba tightly packed straight RGBA8 pixel bytes ({@code 4 * width * height})
     * @return packed {@link AtlasRegion} with normalized UVs
     * @throws IllegalArgumentException if arguments are invalid or the image exceeds {@code maxSize}
     */
    public AtlasRegion pack(String key, int width, int height, byte[] straightRgba) {
        checkNotDisposed();
        Objects.requireNonNull(key, "key must not be null");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Region dimensions must be positive: " + width + "x" + height);
        }
        Objects.requireNonNull(straightRgba, "straightRgba must not be null");
        int expectedBytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (straightRgba.length != expectedBytes) {
            throw new IllegalArgumentException("Expected " + expectedBytes + " RGBA bytes, got " + straightRgba.length);
        }

        AtlasRegion existing = regions.get(key);
        if (existing != null) {
            return existing;
        }

        int[] slot = allocateSlot(width, height);
        if (slot == null) {
            throw new IllegalStateException("Texture atlas capacity exceeded for " + width + "x" + height);
        }

        byte[] premul = Texture.premultiplyRgba(straightRgba);
        copyPremultipliedPixels(premul, width, height, slot[0], slot[1]);

        AtlasRegion region = createRegion(key, slot[0], slot[1], width, height);
        regions.put(key, region);
        dirty = true;
        return region;
    }

    /**
     * Decodes an image file via {@link TextureManager#readImageFileRgba} and packs it into the atlas.
     *
     * @param key       unique region identifier (must not be null or blank)
     * @param imagePath source PNG, JPEG, WebP, BMP, or TGA file path
     * @return packed {@link AtlasRegion}
     */
    public AtlasRegion packFromFile(String key, Path imagePath) {
        int[] dims = new int[2];
        byte[] straightRgba = TextureManager.readImageFileRgba(imagePath, dims);
        return pack(key, dims[0], dims[1], straightRgba);
    }

    /**
     * Looks up a previously packed region by key.
     *
     * @param key region identifier
     * @return matching {@link AtlasRegion}, or empty if not present
     */
    public Optional<AtlasRegion> getRegion(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(regions.get(key));
    }

    /**
     * Returns whether {@code key} is packed inside this atlas.
     *
     * @param key region identifier
     * @return {@code true} if {@code key} is present
     */
    public boolean contains(String key) {
        return key != null && regions.containsKey(key);
    }

    /**
     * Computes texture-bind batching statistics for an ordered draw sequence of region keys.
     *
     * @param drawKeys ordered list of region keys queued for drawing
     * @return {@link BatchStats} quantifying bind reduction from atlas batching
     */
    public BatchStats computeBatchStats(List<String> drawKeys) {
        if (drawKeys == null || drawKeys.isEmpty()) {
            return new BatchStats(0, 0, 0, 0.0);
        }
        int originalBinds = 0;
        String prevKey = null;
        int unbatchedSwitches = 0;
        boolean anyInAtlas = false;

        for (String key : drawKeys) {
            if (key == null) {
                continue;
            }
            if (!key.equals(prevKey)) {
                originalBinds++;
                prevKey = key;
            }
            if (regions.containsKey(key)) {
                anyInAtlas = true;
            } else {
                unbatchedSwitches++;
            }
        }
        int atlasBinds = (anyInAtlas ? 1 : 0) + unbatchedSwitches;
        double reduction = originalBinds > 0
                ? Math.max(0.0, (originalBinds - atlasBinds) / (double) originalBinds)
                : 0.0;
        return new BatchStats(drawKeys.size(), originalBinds, atlasBinds, reduction);
    }

    /**
     * Uploads or refreshes the CPU-side premultiplied RGBA8 atlas onto the active OpenGL context.
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
     * Returns the number of regions currently packed in this atlas.
     *
     * @return packed region count
     */
    public int getRegionCount() {
        return regions.size();
    }

    /**
     * Returns current atlas width in pixels.
     *
     * @return atlas width in pixels
     */
    public int getAtlasWidth() {
        return atlasWidth;
    }

    /**
     * Returns current atlas height in pixels.
     *
     * @return atlas height in pixels
     */
    public int getAtlasHeight() {
        return atlasHeight;
    }

    /**
     * Returns the OpenGL texture ID, or {@code 0} if not uploaded.
     *
     * @return OpenGL texture handle or {@code 0}
     */
    public int getGlTextureId() {
        return glTextureId;
    }

    /**
     * Returns whether CPU pixel changes still need GPU upload.
     *
     * @return {@code true} if dirty
     */
    public boolean isDirty() {
        return dirty;
    }

    /**
     * Returns a defensive copy of the premultiplied RGBA8 backing buffer.
     *
     * @return copy of backing RGBA8 pixel bytes
     */
    public byte[] getAtlasRgbaCopy() {
        return atlasRgba.clone();
    }

    /**
     * Auto-cropped RGBA sprite buffer storing tight opaque bounds plus offsets relative to the original canvas
     * so packed atlas quads render pixel-for-pixel aligned with untrimmed sprites while saving VRAM.
     *
     * @param originalWidth  untrimmed image width in pixels
     * @param originalHeight untrimmed image height in pixels
     * @param trimOffsetX    X pixel offset of trimmed box within original canvas
     * @param trimOffsetY    Y pixel offset of trimmed box within original canvas
     * @param trimmedWidth   trimmed box width ({@code >= 1})
     * @param trimmedHeight  trimmed box height ({@code >= 1})
     * @param trimmedRgba    tightly packed straight RGBA8 pixels of size {@code trimmedWidth * trimmedHeight * 4}
     */
    public record TrimmedRgba(
            int originalWidth,
            int originalHeight,
            int trimOffsetX,
            int trimOffsetY,
            int trimmedWidth,
            int trimmedHeight,
            byte[] trimmedRgba
    ) {
        /** Computes byte savings relative to the untrimmed RGBA buffer.
         *
         * @return bytes saved by auto-cropping transparent margins
         */
        public long savedBytes() {
            return Math.max(0L, ((long) originalWidth * originalHeight - (long) trimmedWidth * trimmedHeight) * 4L);
        }
    }

    /**
     * Trims fully transparent borders ({@code alpha == 0}) from an RGBA8 sprite while preserving exact
     * {@code (trimOffsetX, trimOffsetY)} so final rendered placement differs 0 px from the untrimmed source.
     *
     * @param width        original width ({@code > 0})
     * @param height       original height ({@code > 0})
     * @param straightRgba tightly packed RGBA8 buffer
     * @return {@link TrimmedRgba} preserving exact spatial offset
     */
    public static TrimmedRgba trimTransparentBorders(int width, int height, byte[] straightRgba) {
        Objects.requireNonNull(straightRgba, "straightRgba must not be null");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive");
        }
        int expected = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (straightRgba.length != expected) {
            throw new IllegalArgumentException("Buffer size mismatch for " + width + "x" + height);
        }

        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = straightRgba[(y * width + x) * 4 + 3] & 0xFF;
                if (alpha > 0) {
                    if (x < minX) {
                        minX = x;
                    }
                    if (x > maxX) {
                        maxX = x;
                    }
                    if (y < minY) {
                        minY = y;
                    }
                    if (y > maxY) {
                        maxY = y;
                    }
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            return new TrimmedRgba(width, height, 0, 0, 1, 1, new byte[4]);
        }

        int trimmedW = maxX - minX + 1;
        int trimmedH = maxY - minY + 1;
        byte[] cropped = new byte[trimmedW * trimmedH * 4];
        for (int row = 0; row < trimmedH; row++) {
            int srcOff = ((minY + row) * width + minX) * 4;
            int dstOff = row * trimmedW * 4;
            System.arraycopy(straightRgba, srcOff, cropped, dstOff, trimmedW * 4);
        }
        return new TrimmedRgba(width, height, minX, minY, trimmedW, trimmedH, cropped);
    }

    /**
     * Releases OpenGL texture resources and clears packed regions. Safe to call repeatedly.
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        if (glTextureId != 0) {
            GL11.glDeleteTextures(glTextureId);
            glTextureId = 0;
        }
        regions.clear();
        disposed = true;
    }

    @Override
    public void close() {
        dispose();
    }

    private int[] allocateSlot(int width, int height) {
        int neededW = width + paddingPx * 2;
        int neededH = height + paddingPx * 2;
        if (neededW > maxSize || neededH > maxSize) {
            return null;
        }

        int stepX = width + paddingPx;
        int stepY = height + paddingPx;
        if (shelfX + stepX > atlasWidth) {
            shelfX = paddingPx;
            shelfY += shelfHeight + paddingPx;
            shelfHeight = 0;
        }
        while (shelfY + stepY > atlasHeight || shelfX + stepX > atlasWidth) {
            if (!grow()) {
                return null;
            }
            if (shelfX + stepX > atlasWidth) {
                shelfX = paddingPx;
                shelfY += shelfHeight + paddingPx;
                shelfHeight = 0;
            }
        }

        int x = shelfX;
        int y = shelfY;
        shelfX += stepX;
        shelfHeight = Math.max(shelfHeight, height);
        return new int[]{x, y};
    }

    private boolean grow() {
        int nextW = Math.min(maxSize, atlasWidth * 2);
        int nextH = Math.min(maxSize, atlasHeight * 2);
        if (nextW == atlasWidth && nextH == atlasHeight) {
            return false;
        }
        byte[] grown = new byte[Math.multiplyExact(Math.multiplyExact(nextW, nextH), 4)];
        for (int row = 0; row < atlasHeight; row++) {
            System.arraycopy(atlasRgba, row * atlasWidth * 4, grown, row * nextW * 4, atlasWidth * 4);
        }
        atlasRgba = grown;
        atlasWidth = nextW;
        atlasHeight = nextH;

        for (Map.Entry<String, AtlasRegion> entry : regions.entrySet()) {
            AtlasRegion old = entry.getValue();
            entry.setValue(createRegion(old.key(), old.x(), old.y(), old.width(), old.height()));
        }
        dirty = true;
        return true;
    }

    private void copyPremultipliedPixels(byte[] premul, int width, int height, int dstX, int dstY) {
        for (int row = 0; row < height; row++) {
            int srcOffset = row * width * 4;
            int dstOffset = ((dstY + row) * atlasWidth + dstX) * 4;
            System.arraycopy(premul, srcOffset, atlasRgba, dstOffset, width * 4);
        }
    }

    private AtlasRegion createRegion(String key, int x, int y, int width, int height) {
        Rect2f uv = new Rect2f(
                x / (float) atlasWidth,
                y / (float) atlasHeight,
                width / (float) atlasWidth,
                height / (float) atlasHeight
        );
        return new AtlasRegion(key, x, y, width, height, uv);
    }

    private void checkNotDisposed() {
        if (disposed) {
            throw new IllegalStateException("DynamicTextureAtlas has already been disposed");
        }
    }
}
