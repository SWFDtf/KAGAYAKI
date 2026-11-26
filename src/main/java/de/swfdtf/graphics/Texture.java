package de.swfdtf.graphics;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Immutable-metadata OpenGL 2D texture handle storing pixel data in premultiplied RGBA8 format.
 *
 * <p>All textures uploaded via {@link #createFromRgba(int, int, byte[], boolean, String)} use
 * {@code GL_LINEAR} min/mag filtering by default and {@code GL_CLAMP_TO_EDGE} wrapping to avoid dark halo
 * fringes and edge bleeding under {@code GL_ONE, GL_ONE_MINUS_SRC_ALPHA} blending.</p>
 */
public final class Texture {

    /**
     * Texture magnification/minification filtering mode.
     */
    public enum SamplingMode {
        /** Smooth bilinear interpolation suitable for illustrated art and UI. */
        LINEAR,
        /** Crisp nearest-neighbor sampling suitable for pixel art. */
        NEAREST
    }

    private int textureId;
    private final int width;
    private final int height;
    private final String name;
    private final boolean premultiplied;
    private final boolean fallback;
    private SamplingMode samplingMode = SamplingMode.LINEAR;
    private boolean mipmapsEnabled = false;
    private float anisotropyLevel = 1.0f;
    private boolean disposed = false;

    /**
     * Constructs a {@code Texture} descriptor wrapping an OpenGL texture handle.
     *
     * @param textureId     OpenGL texture ID ({@code >= 0})
     * @param width         texture width in pixels ({@code > 0})
     * @param height        texture height in pixels ({@code > 0})
     * @param name          diagnostic texture name or asset key (normalized to {@code ""} if {@code null})
     * @param premultiplied {@code true} if stored texels are premultiplied RGBA
     * @param fallback      {@code true} if this texture is a generated missing-asset placeholder
     * @throws IllegalArgumentException if {@code textureId < 0} or dimensions are non-positive
     */
    public Texture(int textureId, int width, int height, String name, boolean premultiplied, boolean fallback) {
        if (textureId < 0) {
            throw new IllegalArgumentException("textureId must be non-negative, got " + textureId);
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Texture dimensions must be positive, got " + width + "x" + height
            );
        }
        this.textureId = textureId;
        this.width = width;
        this.height = height;
        this.name = name != null ? name : "";
        this.premultiplied = premultiplied;
        this.fallback = fallback;
    }

    /**
     * Constructs a non-fallback {@code Texture} descriptor wrapping an OpenGL texture handle.
     *
     * @param textureId     OpenGL texture ID ({@code >= 0})
     * @param width         texture width in pixels ({@code > 0})
     * @param height        texture height in pixels ({@code > 0})
     * @param name          diagnostic texture name or asset key
     * @param premultiplied {@code true} if stored texels are premultiplied RGBA
     */
    public Texture(int textureId, int width, int height, String name, boolean premultiplied) {
        this(textureId, width, height, name, premultiplied, false);
    }

    /**
     * Converts straight (unassociated) RGBA8 bytes {@code [R, G, B, A, ...]} into premultiplied
     * RGBA8 bytes {@code [R*A, G*A, B*A, A, ...]} using rounded integer scaling {@code (c * a + 127) / 255}.
     *
     * <p>The input array is never mutated; a new array of equal length is returned.</p>
     *
     * @param straightRgba tightly packed straight RGBA8 byte array (length divisible by 4, non-null)
     * @return newly allocated premultiplied RGBA8 byte array
     * @throws NullPointerException     if {@code straightRgba} is {@code null}
     * @throws IllegalArgumentException if {@code straightRgba.length % 4 != 0}
     */
    public static byte[] premultiplyRgba(byte[] straightRgba) {
        Objects.requireNonNull(straightRgba, "straightRgba must not be null");
        if (straightRgba.length % 4 != 0) {
            throw new IllegalArgumentException(
                    "RGBA byte array length must be a multiple of 4, got " + straightRgba.length
            );
        }
        byte[] premul = new byte[straightRgba.length];
        for (int i = 0; i < straightRgba.length; i += 4) {
            int r = straightRgba[i] & 0xFF;
            int g = straightRgba[i + 1] & 0xFF;
            int b = straightRgba[i + 2] & 0xFF;
            int a = straightRgba[i + 3] & 0xFF;
            premul[i] = (byte) ((r * a + 127) / 255);
            premul[i + 1] = (byte) ((g * a + 127) / 255);
            premul[i + 2] = (byte) ((b * a + 127) / 255);
            premul[i + 3] = (byte) a;
        }
        return premul;
    }

    /**
     * Verifies whether a tightly packed RGBA8 buffer satisfies the premultiplied-alpha invariant
     * ({@code R <= A}, {@code G <= A}, {@code B <= A} for every texel) so bilinear downscaling does not
     * introduce dark fringe halos.
     *
     * @param rgbaBytes RGBA8 buffer to verify (length divisible by 4)
     * @return {@code true} if every texel has {@code max(R, G, B) <= A}
     */
    public static boolean verifyPremultipliedBuffer(byte[] rgbaBytes) {
        Objects.requireNonNull(rgbaBytes, "rgbaBytes must not be null");
        if (rgbaBytes.length % 4 != 0) {
            throw new IllegalArgumentException(
                    "RGBA byte array length must be a multiple of 4, got " + rgbaBytes.length
            );
        }
        for (int i = 0; i < rgbaBytes.length; i += 4) {
            int r = rgbaBytes[i] & 0xFF;
            int g = rgbaBytes[i + 1] & 0xFF;
            int b = rgbaBytes[i + 2] & 0xFF;
            int a = rgbaBytes[i + 3] & 0xFF;
            if (r > a || g > a || b > a) {
                return false;
            }
        }
        return true;
    }

    /**
     * Converts a premultiplied RGBA8 buffer back to straight RGBA8 bytes (zeroing RGB where {@code A == 0}).
     *
     * @param premulRgba premultiplied RGBA8 buffer (length divisible by 4)
     * @return newly allocated straight RGBA8 buffer
     */
    public static byte[] unpremultiplyRgba(byte[] premulRgba) {
        Objects.requireNonNull(premulRgba, "premulRgba must not be null");
        if (premulRgba.length % 4 != 0) {
            throw new IllegalArgumentException(
                    "RGBA byte array length must be a multiple of 4, got " + premulRgba.length
            );
        }
        byte[] straight = new byte[premulRgba.length];
        for (int i = 0; i < premulRgba.length; i += 4) {
            int r = premulRgba[i] & 0xFF;
            int g = premulRgba[i + 1] & 0xFF;
            int b = premulRgba[i + 2] & 0xFF;
            int a = premulRgba[i + 3] & 0xFF;
            if (a == 0) {
                straight[i] = 0;
                straight[i + 1] = 0;
                straight[i + 2] = 0;
                straight[i + 3] = 0;
            } else {
                straight[i] = (byte) Math.min(255, (r * 255 + (a / 2)) / a);
                straight[i + 1] = (byte) Math.min(255, (g * 255 + (a / 2)) / a);
                straight[i + 2] = (byte) Math.min(255, (b * 255 + (a / 2)) / a);
                straight[i + 3] = (byte) a;
            }
        }
        return straight;
    }

    /**
     * Uploads an RGBA8 pixel buffer to a new OpenGL {@code GL_TEXTURE_2D} with {@code GL_LINEAR}
     * filtering and {@code GL_CLAMP_TO_EDGE} wrapping, converting straight RGBA to premultiplied RGBA
     * when {@code alreadyPremultiplied} is {@code false}.
     *
     * @param width                texture width in pixels ({@code > 0})
     * @param height               texture height in pixels ({@code > 0})
     * @param rgbaBytes            tightly packed top-to-bottom RGBA8 pixel bytes ({@code 4 * width * height})
     * @param alreadyPremultiplied {@code true} if {@code rgbaBytes} is already premultiplied
     * @param name                 diagnostic texture name or asset key
     * @return uploaded {@link Texture}
     */
    public static Texture createFromRgba(
            int width,
            int height,
            byte[] rgbaBytes,
            boolean alreadyPremultiplied,
            String name
    ) {
        return createFromRgba(width, height, rgbaBytes, alreadyPremultiplied, name, false);
    }

    static Texture createFromRgba(
            int width,
            int height,
            byte[] rgbaBytes,
            boolean alreadyPremultiplied,
            String name,
            boolean fallback
    ) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Texture dimensions must be positive, got " + width + "x" + height
            );
        }
        Objects.requireNonNull(rgbaBytes, "rgbaBytes must not be null");
        int expectedBytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (rgbaBytes.length != expectedBytes) {
            throw new IllegalArgumentException(
                    "Expected " + expectedBytes + " RGBA bytes for " + width + "x" + height
                            + ", got " + rgbaBytes.length
            );
        }

        byte[] uploadBytes = alreadyPremultiplied ? rgbaBytes : premultiplyRgba(rgbaBytes);

        int prevTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevUnpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        ByteBuffer nativeBuffer = MemoryUtil.memAlloc(expectedBytes);
        int newTexture = 0;
        try {
            nativeBuffer.put(uploadBytes).flip();

            newTexture = GL11.glGenTextures();
            if (newTexture == 0) {
                throw new IllegalStateException("glGenTextures returned 0 for texture '" + name + "'");
            }

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, newTexture);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL11.GL_RGBA8,
                    width,
                    height,
                    0,
                    GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE,
                    nativeBuffer
            );

            Texture result = new Texture(newTexture, width, height, name, true, fallback);
            newTexture = 0;
            return result;
        } finally {
            if (newTexture != 0) {
                GL11.glDeleteTextures(newTexture);
            }
            MemoryUtil.memFree(nativeBuffer);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevUnpackAlignment);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTexture);
        }
    }

    /**
     * Activates texture unit {@code GL_TEXTURE0 + textureUnit} and binds this texture to {@code GL_TEXTURE_2D}.
     *
     * @param textureUnit zero-based texture unit index in {@code [0, 31]}
     * @throws IllegalArgumentException if {@code textureUnit} is out of range {@code [0, 31]}
     * @throws IllegalStateException    if this texture has been disposed or has {@code textureId == 0}
     */
    public void bind(int textureUnit) {
        if (textureUnit < 0 || textureUnit >= 32) {
            throw new IllegalArgumentException("textureUnit must be in [0, 31], got " + textureUnit);
        }
        if (disposed || textureId == 0) {
            throw new IllegalStateException("Texture '" + name + "' is disposed or invalid");
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + textureUnit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
    }

    /**
     * Unbinds any 2D texture from the currently active texture unit ({@code glBindTexture(GL_TEXTURE_2D, 0)}).
     */
    public void unbind() {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    /**
     * Releases the underlying OpenGL texture object. Safe to invoke multiple times or headlessly.
     */
    public void dispose() {
        if (textureId != 0) {
            if (hasActiveGlContext()) {
                GL11.glDeleteTextures(textureId);
            }
            textureId = 0;
        }
        disposed = true;
    }

    private static boolean hasActiveGlContext() {
        try {
            return GL.getCapabilities() != null;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    /**
     * Returns the native OpenGL texture ID.
     *
     * @return native OpenGL texture ID, or {@code 0} after {@link #dispose()}
     */
    public int getTextureId() {
        return textureId;
    }

    /**
     * Returns the texture width in pixels.
     *
     * @return texture width in pixels
     */
    public int getWidth() {
        return width;
    }

    /**
     * Returns the texture height in pixels.
     *
     * @return texture height in pixels
     */
    public int getHeight() {
        return height;
    }

    /**
     * Returns the diagnostic texture name or asset key.
     *
     * @return diagnostic texture name or asset key
     */
    public String getName() {
        return name;
    }

    /**
     * Returns whether the texture stores premultiplied RGBA texels.
     *
     * @return {@code true} if the texture stores premultiplied RGBA texels
     */
    public boolean isPremultiplied() {
        return premultiplied;
    }

    /**
     * Returns whether this texture is a generated missing-asset fallback.
     *
     * @return {@code true} if this texture is a generated missing-asset fallback
     */
    public boolean isFallback() {
        return fallback;
    }

    /**
     * Returns the estimated RGBA8 VRAM footprint of this texture in bytes.
     *
     * @return {@code (long) width * height * 4L} (plus ~33% when mipmaps are enabled)
     */
    public long getEstimatedMemoryBytes() {
        long base = (long) width * (long) height * 4L;
        return mipmapsEnabled ? (base * 4L) / 3L : base;
    }

    /**
     * Returns the configured {@link SamplingMode}.
     *
     * @return configured {@link SamplingMode}
     */
    public SamplingMode getSamplingMode() {
        return samplingMode;
    }

    /**
     * Sets the texture sampling mode ({@link SamplingMode#LINEAR} or {@link SamplingMode#NEAREST}) and updates
     * OpenGL texture parameters if a live texture handle is present and {@code applyGlParameters} is {@code true}.
     *
     * @param samplingMode      sampling mode (must not be {@code null})
     * @param applyGlParameters whether to issue {@code glTexParameteri} immediately on the current GL context
     */
    public void setSamplingMode(SamplingMode samplingMode, boolean applyGlParameters) {
        this.samplingMode = Objects.requireNonNull(samplingMode, "samplingMode must not be null");
        if (applyGlParameters && textureId != 0 && !disposed && hasActiveGlContext()) {
            int magFilter = samplingMode == SamplingMode.NEAREST ? GL11.GL_NEAREST : GL11.GL_LINEAR;
            int minFilter;
            if (mipmapsEnabled) {
                minFilter = samplingMode == SamplingMode.NEAREST
                        ? GL11.GL_NEAREST_MIPMAP_NEAREST
                        : GL11.GL_LINEAR_MIPMAP_LINEAR;
            } else {
                minFilter = magFilter;
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, minFilter);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, magFilter);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
    }

    /**
     * Returns whether mipmap generation is enabled for downscaled CG rendering.
     *
     * @return {@code true} if mipmaps are enabled
     */
    public boolean isMipmapsEnabled() {
        return mipmapsEnabled;
    }

    /**
     * Configures mipmap generation and anisotropic filtering level metadata, optionally generating OpenGL
     * mipmaps immediately when a GL context is current.
     *
     * @param enabled           whether mipmaps are enabled
     * @param anisotropyLevel   requested anisotropy factor ({@code >= 1.0f})
     * @param applyGlParameters whether to generate mipmaps and apply filters immediately in OpenGL
     */
    public void configureMipmaps(boolean enabled, float anisotropyLevel, boolean applyGlParameters) {
        this.mipmapsEnabled = enabled;
        this.anisotropyLevel = Float.isFinite(anisotropyLevel) ? Math.max(1.0f, anisotropyLevel) : 1.0f;
        if (applyGlParameters && textureId != 0 && !disposed && hasActiveGlContext()) {
            if (enabled) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
                GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            }
            setSamplingMode(this.samplingMode, true);
        }
    }

    /**
     * Returns the configured anisotropic filtering level.
     *
     * @return anisotropy level ({@code >= 1.0f})
     */
    public float getAnisotropyLevel() {
        return anisotropyLevel;
    }

    /**
     * Returns whether {@link #dispose()} has been called.
     *
     * @return {@code true} if {@link #dispose()} has been called
     */
    public boolean isDisposed() {
        return disposed;
    }
}
