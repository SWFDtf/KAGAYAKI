package de.swfdtf.graphics;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Immutable-metadata OpenGL 2D texture handle storing pixel data in premultiplied RGBA8 format.
 *
 * <p>All textures uploaded via {@link #createFromRgba(int, int, byte[], boolean, String)} use
 * {@code GL_LINEAR} min/mag filtering and {@code GL_CLAMP_TO_EDGE} wrapping to avoid dark halo
 * fringes and edge bleeding under {@code GL_ONE, GL_ONE_MINUS_SRC_ALPHA} blending.</p>
 */
public final class Texture {

    private int textureId;
    private final int width;
    private final int height;
    private final String name;
    private final boolean premultiplied;
    private final boolean fallback;
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
     * Releases the underlying OpenGL texture object. Safe to invoke multiple times.
     */
    public void dispose() {
        if (textureId != 0) {
            GL11.glDeleteTextures(textureId);
            textureId = 0;
        }
        disposed = true;
    }

    /** @return native OpenGL texture ID, or {@code 0} after {@link #dispose()} */
    public int getTextureId() {
        return textureId;
    }

    /** @return texture width in pixels */
    public int getWidth() {
        return width;
    }

    /** @return texture height in pixels */
    public int getHeight() {
        return height;
    }

    /** @return diagnostic texture name or asset key */
    public String getName() {
        return name;
    }

    /** @return {@code true} if the texture stores premultiplied RGBA texels */
    public boolean isPremultiplied() {
        return premultiplied;
    }

    /** @return {@code true} if this texture is a generated missing-asset fallback */
    public boolean isFallback() {
        return fallback;
    }

    /** @return {@code true} if {@link #dispose()} has been called */
    public boolean isDisposed() {
        return disposed;
    }
}
