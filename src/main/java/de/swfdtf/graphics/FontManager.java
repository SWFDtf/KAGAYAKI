package de.swfdtf.graphics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Central font registry and {@link FontAtlas} cache for KAGAYAKI.
 *
 * <p>Manages default, asset, in-memory, and optional system-discovered TrueType/OpenType fonts
 * keyed by {@code (fontKey, pixelSize)}, always falling back cleanly to
 * {@link FontAtlas#createBuiltinFallback(float, boolean)} when no font file is present.
 * Exposes global accessibility controls for dyslexia-friendly typography and text scaling.</p>
 */
public final class FontManager implements AutoCloseable {

    private static final String BUILTIN_KEY = "__builtin_fallback__";
    private static final float MIN_TEXT_SCALE = 0.5f;
    private static final float MAX_TEXT_SCALE = 3.0f;

    private static final List<String> STANDARD_SYSTEM_FONT_CANDIDATES = List.of(
            "/System/Library/Fonts/Supplemental/Arial.ttf",
            "/System/Library/Fonts/Supplemental/Verdana.ttf",
            "/System/Library/Fonts/Helvetica.ttc",
            "/Library/Fonts/Arial.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
            "/usr/share/fonts/TTF/DejaVuSans.ttf",
            "C:/Windows/Fonts/segoeui.ttf",
            "C:/Windows/Fonts/arial.ttf"
    );

    private static final List<String> DYSLEXIA_SYSTEM_FONT_CANDIDATES = List.of(
            "/Library/Fonts/OpenDyslexic-Regular.otf",
            "/Library/Fonts/OpenDyslexic-Regular.ttf",
            "/usr/share/fonts/opentype/opendyslexic/OpenDyslexic-Regular.otf",
            "/usr/share/fonts/truetype/opendyslexic/OpenDyslexic-Regular.ttf",
            "/System/Library/Fonts/Supplemental/Verdana.ttf",
            "/System/Library/Fonts/Supplemental/Comic Sans MS.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "C:/Windows/Fonts/verdana.ttf",
            "C:/Windows/Fonts/comic.ttf"
    );

    private record AtlasKey(String fontKey, int pixelSize) {
    }

    private final Map<String, byte[]> registeredFontBytes = new HashMap<>();
    private final Map<AtlasKey, FontAtlas> atlasCache = new HashMap<>();

    private Path assetRoot;
    private String defaultFontPath;
    private String dyslexiaFontPath;
    private boolean systemFontFallbackEnabled;
    private boolean dyslexiaFriendlyMode = false;
    private float globalTextScale = 1.0f;
    private boolean disposed = false;

    /**
     * Creates a {@code FontManager} using the built-in CPU bitmap fallback unless custom fonts
     * are registered or a default font path is configured.
     */
    public FontManager() {
        this(null, false);
    }

    /**
     * Creates a {@code FontManager} rooted at an optional project/assets directory.
     *
     * @param assetRoot                 optional root path for resolving relative font paths (may be {@code null})
     * @param systemFontFallbackEnabled whether missing fonts may fall back to OS system font discovery
     */
    public FontManager(Path assetRoot, boolean systemFontFallbackEnabled) {
        this.assetRoot = assetRoot;
        this.systemFontFallbackEnabled = systemFontFallbackEnabled;
    }

    /**
     * Probes common OS font locations and returns the first readable TrueType/OpenType font file.
     *
     * @param dyslexiaFriendly when {@code true}, prioritizes OpenDyslexic / Verdana / high-legibility faces
     * @return first discovered font {@link Path}, or empty if none exist
     */
    public static Optional<Path> discoverSystemFontPath(boolean dyslexiaFriendly) {
        List<String> candidates = new ArrayList<>();
        if (dyslexiaFriendly) {
            candidates.addAll(DYSLEXIA_SYSTEM_FONT_CANDIDATES);
        }
        candidates.addAll(STANDARD_SYSTEM_FONT_CANDIDATES);
        for (String candidate : candidates) {
            Path p = Path.of(candidate);
            if (Files.isRegularFile(p) && Files.isReadable(p)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /**
     * Registers an in-memory {@code .ttf}/{@code .otf} byte array under {@code fontKey}.
     *
     * @param fontKey   logical font path or identifier
     * @param fontBytes raw TrueType/OpenType bytes
     * @return {@code this} for fluent chaining
     */
    public FontManager registerFont(String fontKey, byte[] fontBytes) {
        checkNotDisposed();
        Objects.requireNonNull(fontKey, "fontKey must not be null");
        Objects.requireNonNull(fontBytes, "fontBytes must not be null");
        if (fontKey.isBlank()) {
            throw new IllegalArgumentException("fontKey must not be blank");
        }
        if (fontBytes.length == 0) {
            throw new IllegalArgumentException("fontBytes must not be empty");
        }
        String normalized = normalizeFontKey(fontKey);
        registeredFontBytes.put(normalized, fontBytes.clone());
        evictAtlasesForKey(normalized);
        return this;
    }

    /**
     * Loads and registers a {@code .ttf}/{@code .otf} file from disk under {@code fontKey}.
     *
     * @param fontKey  logical font path or identifier
     * @param filePath disk path to font file
     * @return {@code this} for fluent chaining
     * @throws IOException if the file cannot be read
     */
    public FontManager registerFont(String fontKey, Path filePath) throws IOException {
        Objects.requireNonNull(filePath, "filePath must not be null");
        return registerFont(fontKey, Files.readAllBytes(filePath));
    }

    /**
     * Sets the base directory used to resolve relative font paths.
     *
     * @param assetRoot project or assets directory (may be {@code null})
     * @return {@code this} for fluent chaining
     */
    public FontManager setAssetRoot(Path assetRoot) {
        this.assetRoot = assetRoot;
        return this;
    }

    /** @return configured asset root directory, or {@code null} */
    public Path getAssetRoot() {
        return assetRoot;
    }

    /**
     * Sets the default font path used when no {@code {font=...}} tag is active.
     *
     * @param defaultFontPath font path or registered key (may be {@code null} for built-in fallback)
     * @return {@code this} for fluent chaining
     */
    public FontManager setDefaultFontPath(String defaultFontPath) {
        this.defaultFontPath = (defaultFontPath == null || defaultFontPath.isBlank())
                ? null
                : normalizeFontKey(defaultFontPath);
        return this;
    }

    /** @return configured default font path, or {@code null} */
    public String getDefaultFontPath() {
        return defaultFontPath;
    }

    /**
     * Sets a dedicated font path used when {@link #isDyslexiaFriendlyMode()} is enabled.
     *
     * @param dyslexiaFontPath font path or registered key (may be {@code null})
     * @return {@code this} for fluent chaining
     */
    public FontManager setDyslexiaFontPath(String dyslexiaFontPath) {
        this.dyslexiaFontPath = (dyslexiaFontPath == null || dyslexiaFontPath.isBlank())
                ? null
                : normalizeFontKey(dyslexiaFontPath);
        return this;
    }

    /** @return configured dyslexia-friendly font path, or {@code null} */
    public String getDyslexiaFontPath() {
        return dyslexiaFontPath;
    }

    /**
     * Enables or disables automatic OS system font discovery as a fallback when no explicit
     * font file is found.
     *
     * @param enabled {@code true} to allow system font fallback
     * @return {@code this} for fluent chaining
     */
    public FontManager setSystemFontFallbackEnabled(boolean enabled) {
        this.systemFontFallbackEnabled = enabled;
        return this;
    }

    /** @return {@code true} if OS system font fallback is enabled */
    public boolean isSystemFontFallbackEnabled() {
        return systemFontFallbackEnabled;
    }

    /**
     * Enables or disables accessibility dyslexia-friendly typography mode (prefers high-legibility
     * fonts when available and adds extra inter-character tracking / line spacing in layouts).
     *
     * @param dyslexiaFriendlyMode {@code true} to enable dyslexia-friendly mode
     * @return {@code this} for fluent chaining
     */
    public FontManager setDyslexiaFriendlyMode(boolean dyslexiaFriendlyMode) {
        this.dyslexiaFriendlyMode = dyslexiaFriendlyMode;
        return this;
    }

    /** @return {@code true} if dyslexia-friendly typography mode is active */
    public boolean isDyslexiaFriendlyMode() {
        return dyslexiaFriendlyMode;
    }

    /**
     * Sets the global accessibility text scale multiplier (clamped to {@code [0.5, 3.0]}).
     *
     * @param globalTextScale scale multiplier ({@code > 0}, finite)
     * @return {@code this} for fluent chaining
     * @throws IllegalArgumentException if {@code globalTextScale} is not finite or {@code <= 0}
     */
    public FontManager setGlobalTextScale(float globalTextScale) {
        if (!Float.isFinite(globalTextScale) || globalTextScale <= 0.0f) {
            throw new IllegalArgumentException("globalTextScale must be finite and > 0, got " + globalTextScale);
        }
        this.globalTextScale = Math.clamp(globalTextScale, MIN_TEXT_SCALE, MAX_TEXT_SCALE);
        return this;
    }

    /** @return active global text scale multiplier (default {@code 1.0f}) */
    public float getGlobalTextScale() {
        return globalTextScale;
    }

    /**
     * Computes an accessibility-scaled pixel font size using {@link #getGlobalTextScale()}.
     *
     * @param requestedSizePx requested pixel font size
     * @return scaled pixel size clamped to at least {@code 6.0f}
     */
    public float scaleFontSize(float requestedSizePx) {
        return Math.max(6.0f, requestedSizePx * globalTextScale);
    }

    /**
     * Returns extra per-character kerning added by accessibility settings.
     *
     * @return {@code 1.5f} when {@link #isDyslexiaFriendlyMode()} is active, otherwise {@code 0.0f}
     */
    public float getAccessibilityExtraKerning() {
        return dyslexiaFriendlyMode ? 1.5f : 0.0f;
    }

    /**
     * Returns the accessibility line spacing multiplier.
     *
     * @return {@code 1.25f} when {@link #isDyslexiaFriendlyMode()} is active, otherwise {@code 1.0f}
     */
    public float getAccessibilityLineSpacingMultiplier() {
        return dyslexiaFriendlyMode ? 1.25f : 1.0f;
    }

    /**
     * Retrieves or bakes a cached {@link FontAtlas} for the given font path and pixel size.
     *
     * <p>If {@code fontPath} is {@code null} or blank, the configured default (or dyslexia font when
     * active) is resolved, falling back cleanly to {@link FontAtlas#createBuiltinFallback(float, boolean)}
     * if no font asset is available.</p>
     *
     * @param fontPath optional font path/key ({@code null} selects default font)
     * @param sizePx   requested pixel height
     * @param uploadGl whether to ensure the returned atlas is uploaded to OpenGL
     * @return non-null cached {@link FontAtlas}
     */
    public FontAtlas getAtlas(String fontPath, float sizePx, boolean uploadGl) {
        checkNotDisposed();
        if (!Float.isFinite(sizePx) || sizePx <= 0.0f) {
            throw new IllegalArgumentException("sizePx must be finite and > 0, got " + sizePx);
        }
        int quantizedPx = Math.max(6, Math.round(sizePx));
        String effectiveKey = resolveEffectiveFontKey(fontPath);
        AtlasKey cacheKey = new AtlasKey(effectiveKey, quantizedPx);

        FontAtlas cached = atlasCache.get(cacheKey);
        if (cached != null && !cached.isDisposed()) {
            if (uploadGl && cached.getGlTextureId() == 0) {
                cached.uploadToOpenGl();
            }
            return cached;
        }

        FontAtlas created = createAtlasForKey(effectiveKey, quantizedPx, uploadGl);
        atlasCache.put(cacheKey, created);
        return created;
    }

    /**
     * Disposes all cached {@link FontAtlas} instances and clears font caches.
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        for (FontAtlas atlas : atlasCache.values()) {
            if (!atlas.isDisposed()) {
                atlas.dispose();
            }
        }
        atlasCache.clear();
        registeredFontBytes.clear();
    }

    /**
     * Alias for {@link #dispose()} supporting try-with-resources.
     */
    @Override
    public void close() {
        dispose();
    }

    /** @return {@code true} if {@link #dispose()} has been called */
    public boolean isDisposed() {
        return disposed;
    }

    /** @return number of cached {@link FontAtlas} entries currently held */
    public int getCachedAtlasCount() {
        return atlasCache.size();
    }

    private String resolveEffectiveFontKey(String requestedPath) {
        if (requestedPath != null && !requestedPath.isBlank()) {
            return normalizeFontKey(requestedPath);
        }
        if (dyslexiaFriendlyMode && dyslexiaFontPath != null) {
            return dyslexiaFontPath;
        }
        if (defaultFontPath != null) {
            return defaultFontPath;
        }
        if (systemFontFallbackEnabled) {
            Optional<Path> systemFont = discoverSystemFontPath(dyslexiaFriendlyMode);
            if (systemFont.isPresent()) {
                return normalizeFontKey(systemFont.get().toString());
            }
        }
        return BUILTIN_KEY;
    }

    private FontAtlas createAtlasForKey(String fontKey, int sizePx, boolean uploadGl) {
        if (!BUILTIN_KEY.equals(fontKey)) {
            byte[] bytes = loadFontBytes(fontKey);
            if (bytes != null && bytes.length > 0) {
                try {
                    return FontAtlas.fromTrueTypeBytes(bytes, sizePx, uploadGl);
                } catch (IllegalArgumentException ignored) {
                    // Corrupted or unsupported font file falls back safely below.
                }
            }
            if (systemFontFallbackEnabled) {
                Optional<Path> sysFont = discoverSystemFontPath(dyslexiaFriendlyMode);
                if (sysFont.isPresent()) {
                    try {
                        return FontAtlas.fromTrueTypeFile(sysFont.get(), sizePx, uploadGl);
                    } catch (IOException | IllegalArgumentException ignored) {
                        // Fall through to built-in CPU bitmap fallback.
                    }
                }
            }
        }
        return FontAtlas.createBuiltinFallback(sizePx, uploadGl);
    }

    private byte[] loadFontBytes(String fontKey) {
        byte[] registered = registeredFontBytes.get(fontKey);
        if (registered != null) {
            return registered;
        }

        Path direct = Path.of(fontKey);
        byte[] directBytes = tryReadRegularFile(direct);
        if (directBytes != null) {
            registeredFontBytes.put(fontKey, directBytes);
            return directBytes;
        }

        if (assetRoot != null) {
            List<Path> candidates = List.of(
                    assetRoot.resolve(fontKey),
                    assetRoot.resolve("assets").resolve(fontKey),
                    assetRoot.resolve("assets").resolve("fonts").resolve(fontKey),
                    assetRoot.resolve("fonts").resolve(fontKey)
            );
            for (Path candidate : candidates) {
                byte[] bytes = tryReadRegularFile(candidate);
                if (bytes != null) {
                    registeredFontBytes.put(fontKey, bytes);
                    return bytes;
                }
            }
        }

        String resourcePath = fontKey.startsWith("/") ? fontKey : "/" + fontKey;
        try (InputStream stream = FontManager.class.getResourceAsStream(resourcePath)) {
            if (stream != null) {
                byte[] bytes = stream.readAllBytes();
                registeredFontBytes.put(fontKey, bytes);
                return bytes;
            }
        } catch (IOException ignored) {
            // Fall through to fallback.
        }
        return null;
    }

    private static byte[] tryReadRegularFile(Path path) {
        try {
            if (Files.isRegularFile(path) && Files.isReadable(path)) {
                return Files.readAllBytes(path);
            }
        } catch (IOException ignored) {
            // Ignore unreadable paths.
        }
        return null;
    }

    private void evictAtlasesForKey(String fontKey) {
        atlasCache.entrySet().removeIf(entry -> {
            if (entry.getKey().fontKey().equals(fontKey)) {
                entry.getValue().dispose();
                return true;
            }
            return false;
        });
    }

    private static String normalizeFontKey(String raw) {
        return raw.trim().replace('\\', '/');
    }

    private void checkNotDisposed() {
        if (disposed) {
            throw new IllegalStateException("FontManager has already been disposed");
        }
    }
}
