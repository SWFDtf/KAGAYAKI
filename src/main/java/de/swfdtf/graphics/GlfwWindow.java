package de.swfdtf.graphics;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWWindowSizeCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.util.Objects;

/**
 * Object-oriented wrapper around a native GLFW window handle and its OpenGL 3.3 Core context.
 *
 * <p>Tracks both logical window coordinates and physical framebuffer pixel dimensions (important
 * on Retina/HiDPI displays) and preserves per-window {@link GLCapabilities} across dual-window
 * context switches.</p>
 */
public final class GlfwWindow {

    private long handle;
    private String title;
    private int windowWidth;
    private int windowHeight;
    private int framebufferWidth;
    private int framebufferHeight;
    private GLCapabilities glCapabilities;
    private boolean vsync;

    private GLFWWindowSizeCallback windowSizeCallback;
    private GLFWFramebufferSizeCallback framebufferSizeCallback;

    /**
     * Wraps an already created native GLFW window handle.
     *
     * @param handle       native GLFW window pointer (must not be {@code 0L})
     * @param title        initial window title (non-null)
     * @param windowWidth  requested initial window width in screen coordinates
     * @param windowHeight requested initial window height in screen coordinates
     */
    public GlfwWindow(long handle, String title, int windowWidth, int windowHeight) {
        if (handle == MemoryUtil.NULL) {
            throw new IllegalArgumentException("GLFW window handle must not be NULL");
        }
        this.handle = handle;
        this.title = Objects.requireNonNull(title, "title must not be null");
        this.windowWidth = Math.max(0, windowWidth);
        this.windowHeight = Math.max(0, windowHeight);

        this.windowSizeCallback = GLFWWindowSizeCallback.create((win, width, height) -> {
            this.windowWidth = Math.max(0, width);
            this.windowHeight = Math.max(0, height);
        });
        this.framebufferSizeCallback = GLFWFramebufferSizeCallback.create((win, width, height) -> {
            this.framebufferWidth = Math.max(0, width);
            this.framebufferHeight = Math.max(0, height);
        });
        GLFW.glfwSetWindowSizeCallback(handle, windowSizeCallback);
        GLFW.glfwSetFramebufferSizeCallback(handle, framebufferSizeCallback);

        pollFramebufferSize();
    }

    /**
     * Makes this window's OpenGL context current on the calling thread and binds or initializes
     * its {@link GLCapabilities}.
     */
    public void makeCurrent() {
        ensureAlive();
        GLFW.glfwMakeContextCurrent(handle);
        if (glCapabilities == null) {
            glCapabilities = GL.createCapabilities();
        } else {
            GL.setCapabilities(glCapabilities);
        }
    }

    /**
     * Swaps the front and back buffers for this window.
     */
    public void swapBuffers() {
        ensureAlive();
        GLFW.glfwSwapBuffers(handle);
    }

    /**
     * Returns whether the user or OS has requested closing this window.
     *
     * @return {@code true} if the window close flag is set
     */
    public boolean shouldClose() {
        return handle == MemoryUtil.NULL || GLFW.glfwWindowShouldClose(handle);
    }

    /**
     * Sets or clears the GLFW window close flag.
     *
     * @param close {@code true} to request close
     */
    public void setShouldClose(boolean close) {
        ensureAlive();
        GLFW.glfwSetWindowShouldClose(handle, close);
    }

    /**
     * Updates the native window title bar text.
     *
     * @param title new title string (must not be {@code null})
     */
    public void setTitle(String title) {
        ensureAlive();
        this.title = Objects.requireNonNull(title, "title must not be null");
        GLFW.glfwSetWindowTitle(handle, this.title);
    }

    /**
     * Enables or disables vertical synchronization (swap interval {@code 1} or {@code 0}) on this
     * window's OpenGL context, making the context current first if necessary.
     *
     * @param vsync {@code true} to synchronize buffer swaps with display refresh
     */
    public void setVsync(boolean vsync) {
        ensureAlive();
        this.vsync = vsync;
        if (GLFW.glfwGetCurrentContext() != handle) {
            makeCurrent();
        }
        GLFW.glfwSwapInterval(vsync ? 1 : 0);
    }

    /**
     * Queries GLFW directly for the current window size (screen coordinates) and framebuffer
     * size (physical drawable pixels).
     */
    public void pollFramebufferSize() {
        ensureAlive();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer winW = stack.mallocInt(1);
            IntBuffer winH = stack.mallocInt(1);
            IntBuffer fbW = stack.mallocInt(1);
            IntBuffer fbH = stack.mallocInt(1);
            GLFW.glfwGetWindowSize(handle, winW, winH);
            GLFW.glfwGetFramebufferSize(handle, fbW, fbH);
            this.windowWidth = Math.max(0, winW.get(0));
            this.windowHeight = Math.max(0, winH.get(0));
            this.framebufferWidth = Math.max(0, fbW.get(0));
            this.framebufferHeight = Math.max(0, fbH.get(0));
        }
    }

    /**
     * Shows the window if currently hidden.
     */
    public void show() {
        ensureAlive();
        GLFW.glfwShowWindow(handle);
    }

    /**
     * Hides the window without destroying its OpenGL context.
     */
    public void hide() {
        ensureAlive();
        GLFW.glfwHideWindow(handle);
    }

    /**
     * Requests OS input focus for this window.
     */
    public void focus() {
        ensureAlive();
        GLFW.glfwFocusWindow(handle);
    }

    /**
     * Releases GLFW size callbacks and destroys the native window handle.
     * Safe to invoke multiple times.
     */
    public void destroy() {
        if (handle != MemoryUtil.NULL) {
            GLFW.glfwSetWindowSizeCallback(handle, null);
            GLFW.glfwSetFramebufferSizeCallback(handle, null);
            if (GLFW.glfwGetCurrentContext() == handle) {
                GLFW.glfwMakeContextCurrent(MemoryUtil.NULL);
                GL.setCapabilities(null);
            }
            GLFW.glfwDestroyWindow(handle);
            handle = MemoryUtil.NULL;
        }
        if (windowSizeCallback != null) {
            windowSizeCallback.free();
            windowSizeCallback = null;
        }
        if (framebufferSizeCallback != null) {
            framebufferSizeCallback.free();
            framebufferSizeCallback = null;
        }
        glCapabilities = null;
    }

    /** @return native GLFW window handle, or {@code 0L} after {@link #destroy()} */
    public long getHandle() {
        return handle;
    }

    /** @return current window title */
    public String getTitle() {
        return title;
    }

    /** @return window width in screen coordinates */
    public int getWindowWidth() {
        return windowWidth;
    }

    /** @return window height in screen coordinates */
    public int getWindowHeight() {
        return windowHeight;
    }

    /** @return physical drawable framebuffer width in pixels */
    public int getFramebufferWidth() {
        return framebufferWidth;
    }

    /** @return physical drawable framebuffer height in pixels */
    public int getFramebufferHeight() {
        return framebufferHeight;
    }

    /** @return cached LWJGL {@link GLCapabilities} for this window's context, or {@code null} */
    public GLCapabilities getGlCapabilities() {
        return glCapabilities;
    }

    /** @return {@code true} if VSync was enabled via {@link #setVsync(boolean)} */
    public boolean isVsync() {
        return vsync;
    }

    /** @return {@code true} if this window has already been destroyed */
    public boolean isDestroyed() {
        return handle == MemoryUtil.NULL;
    }

    private void ensureAlive() {
        if (handle == MemoryUtil.NULL) {
            throw new IllegalStateException("GlfwWindow has already been destroyed");
        }
    }
}
