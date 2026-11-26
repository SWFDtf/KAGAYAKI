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

    /**
     * Display presentation mode for a native GLFW window.
     */
    public enum WindowMode {
        /** Standard decorated resizable desktop window. */
        WINDOWED,
        /** Borderless full-screen window matching the monitor's video mode resolution for fast task switching. */
        BORDERLESS_FULLSCREEN,
        /** Exclusive full-screen mode bound to a specific GLFW monitor handle. */
        EXCLUSIVE_FULLSCREEN
    }

    private long handle;
    private String title;
    private int windowWidth;
    private int windowHeight;
    private int framebufferWidth;
    private int framebufferHeight;
    private WindowMode windowMode = WindowMode.WINDOWED;
    private int monitorIndex = 0;
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

    /**
     * Returns the native GLFW window handle.
     *
     * @return native GLFW window handle, or {@code 0L} after {@link #destroy()}
     */
    public long getHandle() {
        return handle;
    }

    /**
     * Returns the current window title.
     *
     * @return current window title
     */
    public String getTitle() {
        return title;
    }

    /**
     * Returns the window width in screen coordinates.
     *
     * @return window width in screen coordinates
     */
    public int getWindowWidth() {
        return windowWidth;
    }

    /**
     * Returns the window height in screen coordinates.
     *
     * @return window height in screen coordinates
     */
    public int getWindowHeight() {
        return windowHeight;
    }

    /**
     * Returns the physical drawable framebuffer width in pixels.
     *
     * @return physical drawable framebuffer width in pixels
     */
    public int getFramebufferWidth() {
        return framebufferWidth;
    }

    /**
     * Returns the physical drawable framebuffer height in pixels.
     *
     * @return physical drawable framebuffer height in pixels
     */
    public int getFramebufferHeight() {
        return framebufferHeight;
    }

    /**
     * Returns the cached LWJGL {@link GLCapabilities} for this window.
     *
     * @return cached LWJGL {@link GLCapabilities} for this window's context, or {@code null}
     */
    public GLCapabilities getGlCapabilities() {
        return glCapabilities;
    }

    /**
     * Returns whether VSync is enabled.
     *
     * @return {@code true} if VSync was enabled via {@link #setVsync(boolean)}
     */
    public boolean isVsync() {
        return vsync;
    }

    /**
     * Switches this window between {@link WindowMode#WINDOWED}, {@link WindowMode#BORDERLESS_FULLSCREEN},
     * and {@link WindowMode#EXCLUSIVE_FULLSCREEN} on the selected monitor index.
     *
     * @param mode         target {@link WindowMode} (must not be {@code null})
     * @param monitorIndex zero-based monitor index (clamped safely to connected monitors)
     * @param windowedW    fallback width when restoring {@link WindowMode#WINDOWED}
     * @param windowedH    fallback height when restoring {@link WindowMode#WINDOWED}
     */
    public void setWindowMode(WindowMode mode, int monitorIndex, int windowedW, int windowedH) {
        ensureAlive();
        Objects.requireNonNull(mode, "mode must not be null");
        long monitor = WindowManager.resolveMonitorHandle(monitorIndex);
        this.monitorIndex = Math.max(0, monitorIndex);
        this.windowMode = mode;

        if (monitor == MemoryUtil.NULL) {
            if (mode == WindowMode.WINDOWED && windowedW > 0 && windowedH > 0) {
                GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
                GLFW.glfwSetWindowMonitor(handle, MemoryUtil.NULL, 100, 100, windowedW, windowedH, 0);
            }
            pollFramebufferSize();
            return;
        }

        var vidMode = GLFW.glfwGetVideoMode(monitor);
        if (vidMode == null) {
            pollFramebufferSize();
            return;
        }
        int monW = vidMode.width();
        int monH = vidMode.height();
        int monHz = vidMode.refreshRate();

        switch (mode) {
            case WINDOWED -> {
                GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
                int w = windowedW > 0 ? windowedW : Math.max(640, monW * 2 / 3);
                int h = windowedH > 0 ? windowedH : Math.max(360, monH * 2 / 3);
                GLFW.glfwSetWindowMonitor(handle, MemoryUtil.NULL, 80, 80, w, h, 0);
            }
            case BORDERLESS_FULLSCREEN -> {
                GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer mx = stack.mallocInt(1);
                    IntBuffer my = stack.mallocInt(1);
                    GLFW.glfwGetMonitorPos(monitor, mx, my);
                    GLFW.glfwSetWindowMonitor(handle, MemoryUtil.NULL, mx.get(0), my.get(0), monW, monH, 0);
                }
            }
            case EXCLUSIVE_FULLSCREEN -> {
                GLFW.glfwSetWindowMonitor(handle, monitor, 0, 0, monW, monH, monHz);
            }
        }
        pollFramebufferSize();
    }

    /**
     * Returns the current {@link WindowMode}.
     *
     * @return current {@link WindowMode}
     */
    public WindowMode getWindowMode() {
        return windowMode;
    }

    /**
     * Returns the target monitor index configured for fullscreen modes.
     *
     * @return zero-based monitor index
     */
    public int getMonitorIndex() {
        return monitorIndex;
    }

    /**
     * Returns whether this window has already been destroyed.
     *
     * @return {@code true} if this window has already been destroyed
     */
    public boolean isDestroyed() {
        return handle == MemoryUtil.NULL;
    }

    private void ensureAlive() {
        if (handle == MemoryUtil.NULL) {
            throw new IllegalStateException("GlfwWindow has already been destroyed");
        }
    }
}
