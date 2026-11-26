package de.swfdtf.graphics;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Offscreen OpenGL framebuffer object (FBO) backed by an {@code RGBA8} color texture attachment
 * configured with {@code GL_LINEAR} filtering for aspect-preserving logical canvas rendering.
 */
public final class Framebuffer {

    private int width;
    private int height;
    private int fboId = 0;
    private int textureId = 0;

    /**
     * Creates an uninitialized {@code Framebuffer} configured for {@code 1920x1080}.
     */
    public Framebuffer() {
        this(1920, 1080);
    }

    /**
     * Creates an uninitialized {@code Framebuffer} with the specified dimensions.
     * Call {@link #init()} on an active OpenGL context before binding or reading pixels.
     *
     * @param width  framebuffer width in pixels ({@code > 0})
     * @param height framebuffer height in pixels ({@code > 0})
     */
    public Framebuffer(int width, int height) {
        validateDimensions(width, height);
        this.width = width;
        this.height = height;
    }

    /**
     * Allocates the OpenGL {@code RGBA8} color texture and FBO attachment, verifying completeness.
     * If already initialized, disposes the previous FBO before re-allocating.
     *
     * @throws IllegalStateException if the FBO fails OpenGL completeness checks
     */
    public void init() {
        if (fboId != 0 || textureId != 0) {
            dispose();
        }

        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        int newTexture = 0;
        int newFbo = 0;
        try {
            newTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, newTexture);
            GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL11.GL_RGBA8,
                    width,
                    height,
                    0,
                    GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE,
                    (ByteBuffer) null
            );
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            newFbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, newFbo);
            GL30.glFramebufferTexture2D(
                    GL30.GL_FRAMEBUFFER,
                    GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D,
                    newTexture,
                    0
            );

            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException(
                        "Framebuffer incomplete for " + width + "x" + height + ": 0x" + Integer.toHexString(status)
                );
            }

            this.textureId = newTexture;
            this.fboId = newFbo;
            newTexture = 0;
            newFbo = 0;
        } finally {
            if (newFbo != 0) {
                GL30.glDeleteFramebuffers(newFbo);
            }
            if (newTexture != 0) {
                GL11.glDeleteTextures(newTexture);
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        }
    }

    /**
     * Resizes the color attachment texture to {@code width x height}, allocating GPU objects if
     * {@link #init()} has not yet been called.
     *
     * @param width  new width in pixels ({@code > 0})
     * @param height new height in pixels ({@code > 0})
     */
    public void resize(int width, int height) {
        validateDimensions(width, height);
        if (this.width == width && this.height == height && fboId != 0) {
            return;
        }
        this.width = width;
        this.height = height;
        if (fboId == 0) {
            init();
            return;
        }

        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL11.GL_RGBA8,
                    width,
                    height,
                    0,
                    GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE,
                    (ByteBuffer) null
            );
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        }
    }

    /**
     * Binds this framebuffer as the active render target and updates {@code glViewport} to its dimensions.
     *
     * @throws IllegalStateException if the framebuffer has not been initialized
     */
    public void bind() {
        requireInitialized();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        GL11.glViewport(0, 0, width, height);
    }

    /**
     * Binds the default window framebuffer ({@code 0}).
     */
    public void unbind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * Reads back the FBO color contents as tightly packed RGBA8 bytes in <strong>top-to-bottom</strong>
     * row order ({@code row 0} is the top edge of the logical scene, {@code 4 * width * height} bytes total).
     *
     * @return top-to-bottom RGBA8 pixel byte array
     * @throws IllegalStateException if the framebuffer has not been initialized
     */
    public byte[] readPixelsRgba() {
        requireInitialized();
        int rowBytes = Math.multiplyExact(width, 4);
        int totalBytes = Math.multiplyExact(rowBytes, height);

        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        ByteBuffer nativeBuffer = MemoryUtil.memAlloc(totalBytes);
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, nativeBuffer);

            byte[] topToBottom = new byte[totalBytes];
            for (int y = 0; y < height; y++) {
                int srcOffset = (height - 1 - y) * rowBytes;
                nativeBuffer.position(srcOffset);
                nativeBuffer.get(topToBottom, y * rowBytes, rowBytes);
            }
            return topToBottom;
        } finally {
            MemoryUtil.memFree(nativeBuffer);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, prevPackAlignment);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        }
    }

    /**
     * Returns the OpenGL color texture ID.
     *
     * @return OpenGL texture ID attached to color attachment 0, or {@code 0} if uninitialized
     */
    public int getTextureId() {
        return textureId;
    }

    /**
     * Returns the native OpenGL framebuffer object ID.
     *
     * @return native OpenGL framebuffer object ID, or {@code 0} if uninitialized
     */
    public int getFboId() {
        return fboId;
    }

    /**
     * Returns the framebuffer width in pixels.
     *
     * @return framebuffer width in pixels
     */
    public int getWidth() {
        return width;
    }

    /**
     * Returns the framebuffer height in pixels.
     *
     * @return framebuffer height in pixels
     */
    public int getHeight() {
        return height;
    }

    /**
     * Returns whether the FBO and texture attachment are initialized.
     *
     * @return {@code true} if the FBO and texture attachment are currently allocated
     */
    public boolean isInitialized() {
        return fboId != 0 && textureId != 0;
    }

    /**
     * Releases the OpenGL framebuffer object and color texture attachment.
     * Safe to invoke multiple times.
     */
    public void dispose() {
        if (fboId != 0) {
            GL30.glDeleteFramebuffers(fboId);
            fboId = 0;
        }
        if (textureId != 0) {
            GL11.glDeleteTextures(textureId);
            textureId = 0;
        }
    }

    private static void validateDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Framebuffer dimensions must be positive, got " + width + "x" + height
            );
        }
    }

    private void requireInitialized() {
        if (fboId == 0) {
            throw new IllegalStateException("Framebuffer has not been initialized or was disposed");
        }
    }
}
