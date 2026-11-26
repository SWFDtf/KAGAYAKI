package de.swfdtf.graphics;

import de.swfdtf.core.EngineLogger;
import de.swfdtf.core.ResourceProvider;
import org.lwjgl.stb.STBImage;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure LWJGL / STB texture loader, CPU image codec helper, procedural fallback generator,
 * and canonical-path GPU texture cache.
 *
 * <p>Decodes images via {@link STBImage}, converts straight RGBA8 pixels to premultiplied RGBA8,
 * and uploads them to OpenGL 2D textures. Missing or corrupt assets never crash the engine: a
 * structured warning is logged through {@link EngineLogger} and a cached magenta/dark-purple
 * diagonal-hatched fallback {@link Texture} ({@link Texture#isFallback()}{@code == true}) is
 * returned instead.</p>
 */
public final class TextureManager {

    /** Default width in pixels for the generated missing-asset fallback texture. */
    public static final int DEFAULT_FALLBACK_WIDTH = 128;

    /** Default height in pixels for the generated missing-asset fallback texture. */
    public static final int DEFAULT_FALLBACK_HEIGHT = 128;

    private static final String LOG_CATEGORY = "TextureManager";
    private static final String FALLBACK_TEXTURE_NAME = "<missing-asset-fallback>";
    private static final int FALLBACK_BORDER_PX = 2;
    private static final int FALLBACK_STRIPE_PX = 8;

    /**
     * CPU-decoded image payload produced asynchronously during asset preloading.
     *
     * @param width        image width in pixels ({@code > 0})
     * @param height       image height in pixels ({@code > 0})
     * @param straightRgba tightly packed top-to-bottom straight RGBA8 pixel bytes
     */
    public record DecodedImage(int width, int height, byte[] straightRgba) {
        /** Validates dimensions and copies pixel bytes defensively. */
        public DecodedImage {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("DecodedImage dimensions must be positive: " + width + "x" + height);
            }
            Objects.requireNonNull(straightRgba, "straightRgba must not be null");
            straightRgba = straightRgba.clone();
        }

        /**
         * Returns a defensive copy of the straight RGBA8 pixel bytes.
         *
         * @return cloned RGBA8 byte array
         */
        @Override
        public byte[] straightRgba() {
            return straightRgba.clone();
        }
    }

    private final EngineLogger logger;
    private volatile Path assetRoot;
    private volatile ResourceProvider resourceProvider;
    private final Map<String, Texture> cache = new LinkedHashMap<>();
    private final Map<String, DecodedImage> preloadedImages = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Boolean>> inFlightPreloads = new ConcurrentHashMap<>();
    private final ExecutorService preloadExecutor;
    private Texture fallbackTexture;

    /**
     * Creates a {@code TextureManager} with an internal default {@link EngineLogger} and no asset root.
     */
    public TextureManager() {
        this(new EngineLogger(), null);
    }

    /**
     * Creates a {@code TextureManager} logging warnings to {@code logger} with no asset root.
     *
     * @param logger structured engine logger (must not be {@code null})
     */
    public TextureManager(EngineLogger logger) {
        this(logger, null);
    }

    /**
     * Creates a {@code TextureManager} with an internal default {@link EngineLogger} and base asset root.
     *
     * @param assetRoot optional root directory used to resolve relative asset paths (may be {@code null})
     */
    public TextureManager(Path assetRoot) {
        this(new EngineLogger(), assetRoot);
    }

    /**
     * Creates a {@code TextureManager} with an explicit {@link EngineLogger} and optional base asset root.
     *
     * @param logger    structured engine logger (must not be {@code null})
     * @param assetRoot optional root directory for resolving relative paths (may be {@code null})
     */
    public TextureManager(EngineLogger logger, Path assetRoot) {
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
        this.assetRoot = assetRoot != null ? assetRoot.toAbsolutePath().normalize() : null;
        int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
        AtomicInteger threadIndex = new AtomicInteger(1);
        ThreadFactory factory = runnable -> {
            Thread t = new Thread(runnable, "kagayaki-tex-preload-" + threadIndex.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        this.preloadExecutor = Executors.newFixedThreadPool(threads, factory);
    }

    /**
     * Generates a crisp magenta ({@code #FF00FF}) and dark-purple ({@code #1A0A1E}) diagonal-hatched
     * warning frame with a solid 2-pixel magenta border in tightly packed RGBA8 format.
     *
     * <p>All pixels are fully opaque ({@code A = 255}), making the generated buffer valid as both
     * straight and premultiplied RGBA8.</p>
     *
     * @param width  image width in pixels ({@code > 0})
     * @param height image height in pixels ({@code > 0})
     * @return tightly packed top-to-bottom RGBA8 pixel array of length {@code 4 * width * height}
     * @throws IllegalArgumentException if {@code width <= 0} or {@code height <= 0}
     */
    public static byte[] generateMissingAssetRgba(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Fallback image dimensions must be positive, got " + width + "x" + height
            );
        }
        int totalBytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        byte[] rgba = new byte[totalBytes];

        for (int y = 0; y < height; y++) {
            boolean borderY = y < FALLBACK_BORDER_PX || y >= height - FALLBACK_BORDER_PX;
            for (int x = 0; x < width; x++) {
                boolean borderX = x < FALLBACK_BORDER_PX || x >= width - FALLBACK_BORDER_PX;
                boolean magenta = borderX || borderY || (((x + y) / FALLBACK_STRIPE_PX) % 2 != 0);

                int offset = (y * width + x) * 4;
                if (magenta) {
                    rgba[offset] = (byte) 0xFF;
                    rgba[offset + 1] = (byte) 0x00;
                    rgba[offset + 2] = (byte) 0xFF;
                    rgba[offset + 3] = (byte) 0xFF;
                } else {
                    rgba[offset] = (byte) 0x1A;
                    rgba[offset + 1] = (byte) 0x0A;
                    rgba[offset + 2] = (byte) 0x1E;
                    rgba[offset + 3] = (byte) 0xFF;
                }
            }
        }
        return rgba;
    }

    /**
     * Writes straight RGBA8 pixel bytes to a PNG file via {@link STBImageWrite#stbi_write_png},
     * automatically creating missing parent directories.
     *
     * @param destination  target PNG file path (must not be {@code null})
     * @param width        image width in pixels ({@code > 0})
     * @param height       image height in pixels ({@code > 0})
     * @param straightRgba tightly packed top-to-bottom straight RGBA8 bytes ({@code 4 * width * height})
     * @throws IllegalArgumentException if dimensions are non-positive or byte array length mismatches
     * @throws UncheckedIOException     if parent directory creation fails
     * @throws IllegalStateException    if {@code stbi_write_png} fails to encode or write the file
     */
    public static void writePng(Path destination, int width, int height, byte[] straightRgba) {
        Objects.requireNonNull(destination, "destination must not be null");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "PNG dimensions must be positive, got " + width + "x" + height
            );
        }
        Objects.requireNonNull(straightRgba, "straightRgba must not be null");
        int expectedBytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (straightRgba.length != expectedBytes) {
            throw new IllegalArgumentException(
                    "Expected " + expectedBytes + " RGBA bytes for " + width + "x" + height
                            + ", got " + straightRgba.length
            );
        }

        Path absDestination = destination.toAbsolutePath().normalize();
        Path parent = absDestination.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException ex) {
                throw new UncheckedIOException("Failed to create parent directories for " + absDestination, ex);
            }
        }

        ByteBuffer nativePixels = MemoryUtil.memAlloc(expectedBytes);
        try {
            nativePixels.put(straightRgba).flip();
            boolean written = STBImageWrite.stbi_write_png(
                    absDestination.toString(),
                    width,
                    height,
                    4,
                    nativePixels,
                    width * 4
            );
            if (!written) {
                throw new IllegalStateException("STBImageWrite failed to write PNG: " + absDestination);
            }
        } finally {
            MemoryUtil.memFree(nativePixels);
        }
    }

    /**
     * Decodes an image file (PNG, JPG, BMP, TGA, etc.) on the CPU into straight RGBA8 bytes
     * using {@link STBImage#stbi_load_from_memory}.
     *
     * @param path    source image path (must not be {@code null})
     * @param outSize optional array of length {@code >= 2} receiving {@code [width, height]} (may be {@code null})
     * @return tightly packed top-to-bottom straight RGBA8 byte array
     * @throws IllegalArgumentException if {@code outSize != null && outSize.length < 2}
     * @throws UncheckedIOException     if the file cannot be read
     * @throws IllegalStateException    if the file is empty or cannot be decoded by {@link STBImage}
     */
    public static byte[] readImageFileRgba(Path path, int[] outSize) {
        Objects.requireNonNull(path, "path must not be null");
        if (outSize != null && outSize.length < 2) {
            throw new IllegalArgumentException(
                    "outSize array must have length >= 2, got " + outSize.length
            );
        }

        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(path);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read image file: " + path, ex);
        }

        if (fileBytes.length == 0) {
            throw new IllegalStateException("Image file is empty: " + path);
        }
        return decodeImageBytesRgba(fileBytes, outSize, path.toString());
    }

    /**
     * Decodes in-memory image bytes (PNG, JPEG, BMP, TGA, or lossless WebP) into straight RGBA8.
     *
     * @param fileBytes  encoded image bytes (must not be {@code null} or empty)
     * @param outSize    optional array of length {@code >= 2} receiving {@code [width, height]}
     * @param sourceName logical source name for error diagnostics
     * @return straight RGBA8 pixel bytes
     */
    public static byte[] decodeImageBytesRgba(byte[] fileBytes, int[] outSize, String sourceName) {
        Objects.requireNonNull(fileBytes, "fileBytes must not be null");
        if (outSize != null && outSize.length < 2) {
            throw new IllegalArgumentException("outSize array must have length >= 2, got " + outSize.length);
        }
        if (fileBytes.length == 0) {
            throw new IllegalStateException("Image bytes are empty: " + sourceName);
        }

        if (WebpVp8lDecoder.isWebp(fileBytes)) {
            WebpVp8lDecoder.DecodedRgba decodedWebp = WebpVp8lDecoder.decode(fileBytes);
            if (outSize != null) {
                outSize[0] = decodedWebp.width();
                outSize[1] = decodedWebp.height();
            }
            return decodedWebp.straightRgba();
        }

        ByteBuffer rawFileBuffer = MemoryUtil.memAlloc(fileBytes.length);
        ByteBuffer decoded = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            rawFileBuffer.put(fileBytes).flip();
            IntBuffer wBuf = stack.mallocInt(1);
            IntBuffer hBuf = stack.mallocInt(1);
            IntBuffer channelsBuf = stack.mallocInt(1);

            decoded = STBImage.stbi_load_from_memory(
                    rawFileBuffer,
                    wBuf,
                    hBuf,
                    channelsBuf,
                    STBImage.STBI_rgb_alpha
            );
            if (decoded == null) {
                String reason = STBImage.stbi_failure_reason();
                throw new IllegalStateException(
                        "Failed to decode image '" + sourceName + "': "
                                + (reason != null ? reason : "unknown STB error")
                );
            }

            int width = wBuf.get(0);
            int height = hBuf.get(0);
            if (width <= 0 || height <= 0) {
                throw new IllegalStateException(
                        "Decoded invalid image dimensions " + width + "x" + height + " for " + sourceName
                );
            }

            int totalBytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
            byte[] straightRgba = new byte[totalBytes];
            decoded.get(0, straightRgba);

            if (outSize != null) {
                outSize[0] = width;
                outSize[1] = height;
            }
            return straightRgba;
        } finally {
            if (decoded != null) {
                decoded.clear();
                STBImage.stbi_image_free(decoded);
            }
            MemoryUtil.memFree(rawFileBuffer);
        }
    }

    /**
     * Attaches an optional {@link ResourceProvider} fallback for loading textures directly
     * from packed {@code .vpak} archives when loose files are absent on disk.
     *
     * @param resourceProvider resource provider instance (may be {@code null})
     */
    public void setResourceProvider(ResourceProvider resourceProvider) {
        this.resourceProvider = resourceProvider;
    }

    /**
     * Loads (or retrieves from cache) a premultiplied OpenGL {@link Texture} from an asset path string.
     *
     * <p>Relative paths are resolved against {@link #getAssetRoot()} when set. Missing, unreadable,
     * or corrupt assets log a structured warning via {@link EngineLogger} and return the cached
     * fallback texture ({@link Texture#isFallback()}{@code == true}).</p>
     *
     * @param assetPath path or asset key relative to {@link #getAssetRoot()} or absolute
     * @return cached or newly uploaded {@link Texture}, or the cached fallback texture on failure
     */
    public Texture loadTexture(String assetPath) {
        if (assetPath == null || assetPath.isBlank()) {
            logger.warn(LOG_CATEGORY, "Requested texture with null or blank asset path; returning fallback texture.");
            return getOrCreateFallbackTexture();
        }
        try {
            return loadTexture(Path.of(assetPath));
        } catch (InvalidPathException ex) {
            logger.warn(
                    LOG_CATEGORY,
                    "Invalid texture path '" + assetPath + "' (" + ex.getMessage() + "); returning fallback texture."
            );
            return getOrCreateFallbackTexture();
        }
    }

    /**
     * Loads (or retrieves from cache) a premultiplied OpenGL {@link Texture} from a filesystem {@link Path}.
     *
     * <p>Missing, unreadable, or corrupt files log a structured warning via {@link EngineLogger}
     * and return the cached fallback texture ({@link Texture#isFallback()}{@code == true}).</p>
     *
     * @param path filesystem path to the image asset
     * @return cached or newly uploaded {@link Texture}, or the cached fallback texture on failure
     */
    public Texture loadTexture(Path path) {
        if (path == null) {
            logger.warn(LOG_CATEGORY, "Requested texture with null Path; returning fallback texture.");
            return getOrCreateFallbackTexture();
        }

        Path resolved = resolvePath(path);
        String cacheKey = toCanonicalKey(resolved);
        Texture cached = cache.get(cacheKey);
        if (cached != null && !cached.isDisposed()) {
            return cached;
        }

        DecodedImage preloaded = preloadedImages.remove(cacheKey);
        if (preloaded != null) {
            Texture uploadedPreloaded = Texture.createFromRgba(
                    preloaded.width(), preloaded.height(), preloaded.straightRgba(), false, cacheKey
            );
            cache.put(cacheKey, uploadedPreloaded);
            return uploadedPreloaded;
        }

        int[] size = new int[2];
        byte[] straightRgba;
        try {
            if (!Files.isRegularFile(resolved) && resourceProvider != null) {
                String relKey = extractRelativeKey(path, resolved);
                if (relKey != null && resourceProvider.contains(relKey)) {
                    byte[] rawBytes = resourceProvider.readBytes(relKey);
                    straightRgba = decodeImageBytesRgba(rawBytes, size, relKey);
                } else {
                    straightRgba = readImageFileRgba(resolved, size);
                }
            } else {
                straightRgba = readImageFileRgba(resolved, size);
            }
        } catch (IOException | RuntimeException ex) {
            logger.warn(
                    LOG_CATEGORY,
                    "Missing or corrupt texture asset '" + resolved + "' (" + ex.getMessage()
                            + "); using fallback texture."
            );
            return getOrCreateFallbackTexture();
        }

        Texture uploaded = Texture.createFromRgba(size[0], size[1], straightRgba, false, cacheKey);
        cache.put(cacheKey, uploaded);
        return uploaded;
    }

    private String extractRelativeKey(Path originalPath, Path resolvedPath) {
        Path root = assetRoot;
        if (root != null) {
            Path normRoot = root.toAbsolutePath().normalize();
            if (resolvedPath.startsWith(normRoot)) {
                return normRoot.relativize(resolvedPath).toString().replace('\\', '/');
            }
        }
        String raw = originalPath.toString().replace('\\', '/');
        while (raw.startsWith("./")) {
            raw = raw.substring(2);
        }
        while (raw.startsWith("/")) {
            raw = raw.substring(1);
        }
        return raw;
    }

    /**
     * Returns the cached fallback {@link Texture}, creating and uploading it on first demand.
     *
     * @return cached fallback {@link Texture} with {@link Texture#isFallback()}{@code == true}
     */
    public Texture getOrCreateFallbackTexture() {
        if (fallbackTexture == null || fallbackTexture.isDisposed()) {
            byte[] pixels = generateMissingAssetRgba(DEFAULT_FALLBACK_WIDTH, DEFAULT_FALLBACK_HEIGHT);
            fallbackTexture = Texture.createFromRgba(
                    DEFAULT_FALLBACK_WIDTH,
                    DEFAULT_FALLBACK_HEIGHT,
                    pixels,
                    true,
                    FALLBACK_TEXTURE_NAME,
                    true
            );
        }
        return fallbackTexture;
    }

    /**
     * Looks up a previously loaded texture in the cache without loading from disk.
     *
     * @param assetPath asset path string
     * @return cached {@link Texture}, or {@code null} if not cached or invalid path
     */
    public Texture getCached(String assetPath) {
        if (assetPath == null || assetPath.isBlank()) {
            return null;
        }
        try {
            return getCached(Path.of(assetPath));
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    /**
     * Looks up a previously loaded texture in the cache without loading from disk.
     *
     * @param path filesystem path
     * @return cached {@link Texture}, or {@code null} if not cached
     */
    public Texture getCached(Path path) {
        if (path == null) {
            return null;
        }
        String key = toCanonicalKey(resolvePath(path));
        Texture tex = cache.get(key);
        return (tex != null && !tex.isDisposed()) ? tex : null;
    }

    /**
     * Returns the number of non-fallback textures currently stored in the cache.
     *
     * @return number of non-fallback textures currently stored in the cache
     */
    public int getCacheSize() {
        return cache.size();
    }

    /**
     * Returns the configured base asset directory for relative path resolution.
     *
     * @return configured base asset directory for relative path resolution, or {@code null}
     */
    public Path getAssetRoot() {
        return assetRoot;
    }

    /**
     * Updates the base asset directory used when resolving relative paths.
     *
     * @param assetRoot root directory, or {@code null} to resolve relative to working directory
     */
    public void setAssetRoot(Path assetRoot) {
        this.assetRoot = assetRoot != null ? assetRoot.toAbsolutePath().normalize() : null;
    }

    /**
     * Returns the logger used for missing or corrupt texture warnings.
     *
     * @return logger used for missing/corrupt texture warnings
     */
    public EngineLogger getLogger() {
        return logger;
    }

    /**
     * Disposes all cached textures (including the cached fallback texture) and clears the cache.
     */
    public void clearCache() {
        for (Texture texture : cache.values()) {
            if (texture != null) {
                texture.dispose();
            }
        }
        cache.clear();
        if (fallbackTexture != null) {
            fallbackTexture.dispose();
            fallbackTexture = null;
        }
    }

    /**
     * Disposes all OpenGL textures managed by this {@code TextureManager} and stops preload worker threads.
     * Safe to call multiple times.
     */
    public void dispose() {
        inFlightPreloads.clear();
        preloadedImages.clear();
        preloadExecutor.shutdownNow();
        clearCache();
    }

    /**
     * Queues an asset path for asynchronous CPU image decoding on a background worker thread.
     *
     * <p>Missing, invalid, or corrupt assets never throw uncaught exceptions; failures log a
     * structured warning and resolve the returned future to {@code false}.</p>
     *
     * @param assetPath relative or absolute asset path string
     * @return future completing with {@code true} if decoded or already cached, {@code false} on error
     */
    public CompletableFuture<Boolean> preloadAsync(String assetPath) {
        if (assetPath == null || assetPath.isBlank()) {
            logger.warn(LOG_CATEGORY, "Ignoring blank preload asset path.");
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        try {
            return preloadAsync(Path.of(assetPath));
        } catch (InvalidPathException ex) {
            logger.warn(LOG_CATEGORY, "Invalid preload asset path '" + assetPath + "': " + ex.getMessage());
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
    }

    /**
     * Queues a filesystem image path for asynchronous CPU image decoding on a background worker thread.
     *
     * @param path image path to decode into the CPU preload cache
     * @return future completing with {@code true} if decoded or already cached, {@code false} on error
     */
    public CompletableFuture<Boolean> preloadAsync(Path path) {
        if (path == null) {
            logger.warn(LOG_CATEGORY, "Ignoring null preload Path.");
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        Path resolved = resolvePath(path);
        String cacheKey = toCanonicalKey(resolved);
        Texture cached = cache.get(cacheKey);
        if ((cached != null && !cached.isDisposed()) || preloadedImages.containsKey(cacheKey)) {
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
        if (preloadExecutor.isShutdown()) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        return inFlightPreloads.computeIfAbsent(cacheKey, key -> CompletableFuture.supplyAsync(() -> {
            try {
                int[] dims = new int[2];
                byte[] rgba = readImageFileRgba(resolved, dims);
                preloadedImages.put(key, new DecodedImage(dims[0], dims[1], rgba));
                return Boolean.TRUE;
            } catch (RuntimeException ex) {
                logger.warn(LOG_CATEGORY, "Async preload failed for '" + resolved + "': " + ex.getMessage());
                return Boolean.FALSE;
            } finally {
                inFlightPreloads.remove(key);
            }
        }, preloadExecutor));
    }

    /**
     * Queues multiple image paths concurrently on worker threads and returns a future resolving to
     * the number of successfully decoded images.
     *
     * @param paths collection of image paths to preload
     * @return future completing with count of successfully preloaded images
     */
    public CompletableFuture<Integer> preloadAllAsync(Collection<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        List<CompletableFuture<Boolean>> futures = new ArrayList<>(paths.size());
        for (Path p : paths) {
            futures.add(preloadAsync(p));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> {
                    int okCount = 0;
                    for (CompletableFuture<Boolean> f : futures) {
                        if (Boolean.TRUE.equals(f.join())) {
                            okCount++;
                        }
                    }
                    return okCount;
                });
    }

    /**
     * Returns whether an asset path currently has a CPU-decoded image ready in the preload cache.
     *
     * @param assetPath asset path string
     * @return {@code true} if decoded in the CPU preload cache
     */
    public boolean isPreloaded(String assetPath) {
        if (assetPath == null || assetPath.isBlank()) {
            return false;
        }
        try {
            return isPreloaded(Path.of(assetPath));
        } catch (InvalidPathException ignored) {
            return false;
        }
    }

    /**
     * Returns whether {@code path} currently has a CPU-decoded image ready in the preload cache.
     *
     * @param path image path
     * @return {@code true} if decoded in the CPU preload cache
     */
    public boolean isPreloaded(Path path) {
        if (path == null) {
            return false;
        }
        String key = toCanonicalKey(resolvePath(path));
        return preloadedImages.containsKey(key);
    }

    /**
     * Returns the number of CPU-decoded images waiting in the preload cache.
     *
     * @return number of preloaded CPU images
     */
    public int getPreloadedCount() {
        return preloadedImages.size();
    }

    /**
     * Uploads all CPU-preloaded images to OpenGL on the active render thread and caches the
     * resulting GPU {@link Texture} handles.
     *
     * @return number of newly uploaded OpenGL textures
     */
    public int uploadPreloadedTextures() {
        if (preloadedImages.isEmpty()) {
            return 0;
        }
        int uploaded = 0;
        List<String> keys = new ArrayList<>(preloadedImages.keySet());
        for (String key : keys) {
            DecodedImage img = preloadedImages.remove(key);
            if (img == null) {
                continue;
            }
            Texture existing = cache.get(key);
            if (existing != null && !existing.isDisposed()) {
                continue;
            }
            Texture tex = Texture.createFromRgba(img.width(), img.height(), img.straightRgba(), false, key);
            cache.put(key, tex);
            uploaded++;
        }
        return uploaded;
    }

    private Path resolvePath(Path path) {
        if (!path.isAbsolute() && assetRoot != null) {
            return assetRoot.resolve(path).toAbsolutePath().normalize();
        }
        return path.toAbsolutePath().normalize();
    }

    private static String toCanonicalKey(Path normalizedAbsolutePath) {
        try {
            return normalizedAbsolutePath.toRealPath().toString();
        } catch (IOException ignored) {
            return normalizedAbsolutePath.toString();
        }
    }
}
