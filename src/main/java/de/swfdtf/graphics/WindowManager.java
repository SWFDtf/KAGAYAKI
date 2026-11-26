package de.swfdtf.graphics;

import de.swfdtf.core.EngineLogger;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.system.MemoryUtil;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Manages the GLFW subsystem lifecycle and creates either dual Studio/Game windows
 * (Window A + Window B sharing texture/buffer namespaces) or a single standalone game window.
 */
public final class WindowManager {

    private static final int DEFAULT_STUDIO_WIDTH = 1280;
    private static final int DEFAULT_STUDIO_HEIGHT = 800;

    private final EngineLogger logger;
    private GLFWErrorCallback errorCallback;
    private boolean glfwInitialized = false;

    private GlfwWindow gameWindow;
    private GlfwWindow studioWindow;

    /**
     * Creates a {@code WindowManager} backed by an internal default {@link EngineLogger}.
     */
    public WindowManager() {
        this(new EngineLogger());
    }

    /**
     * Creates a {@code WindowManager} that forwards GLFW error callbacks to {@code logger}.
     *
     * @param logger structured logger (must not be {@code null})
     */
    public WindowManager(EngineLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
    }

    /**
     * Initializes GLFW (if not yet initialized) and creates both Window B (Live Game) and
     * Window A (Studio) using default Studio dimensions ({@code 1280x800}).
     *
     * <p>Window A shares OpenGL object namespaces (textures, buffers, programs) with Window B
     * while retaining its own OpenGL 3.3 Core context, capabilities, VAOs, and FBOs.</p>
     *
     * @param studioTitle title for Window A (Studio)
     * @param gameTitle   title for Window B (Live Game)
     * @param gameWidth   initial Window B width in screen coordinates ({@code > 0})
     * @param gameHeight  initial Window B height in screen coordinates ({@code > 0})
     * @param vsync       whether VSync should be enabled on both windows
     */
    public void initDualWindows(
            String studioTitle,
            String gameTitle,
            int gameWidth,
            int gameHeight,
            boolean vsync
    ) {
        initDualWindows(
                studioTitle,
                DEFAULT_STUDIO_WIDTH,
                DEFAULT_STUDIO_HEIGHT,
                gameTitle,
                gameWidth,
                gameHeight,
                vsync
        );
    }

    /**
     * Initializes GLFW and creates both Window B (Live Game) and Window A (Studio) with explicit sizes.
     *
     * @param studioTitle  title for Window A (Studio)
     * @param studioWidth  initial Window A width ({@code > 0})
     * @param studioHeight initial Window A height ({@code > 0})
     * @param gameTitle    title for Window B (Live Game)
     * @param gameWidth    initial Window B width ({@code > 0})
     * @param gameHeight   initial Window B height ({@code > 0})
     * @param vsync        whether VSync should be enabled on both windows
     */
    public void initDualWindows(
            String studioTitle,
            int studioWidth,
            int studioHeight,
            String gameTitle,
            int gameWidth,
            int gameHeight,
            boolean vsync
    ) {
        Objects.requireNonNull(studioTitle, "studioTitle must not be null");
        Objects.requireNonNull(gameTitle, "gameTitle must not be null");
        validateSize(studioWidth, studioHeight, "Studio window");
        validateSize(gameWidth, gameHeight, "Game window");
        ensureNoActiveWindows();

        initGlfwIfNeeded();
        try {
            gameWindow = createGlfwWindow(gameTitle, gameWidth, gameHeight, MemoryUtil.NULL);
            gameWindow.makeCurrent();
            gameWindow.setVsync(vsync);

            studioWindow = createGlfwWindow(studioTitle, studioWidth, studioHeight, gameWindow.getHandle());
            studioWindow.makeCurrent();
            studioWindow.setVsync(vsync);

            studioWindow.show();
            gameWindow.show();
            gameWindow.makeCurrent();
        } catch (RuntimeException ex) {
            destroyWindowsOnly();
            throw ex;
        }
    }

    /**
     * Initializes GLFW and creates only Window B (Live Game) for standalone runtime execution.
     *
     * @param gameTitle title for Window B
     * @param width     initial window width ({@code > 0})
     * @param height    initial window height ({@code > 0})
     * @param vsync     whether VSync should be enabled
     */
    public void initStandaloneWindow(String gameTitle, int width, int height, boolean vsync) {
        Objects.requireNonNull(gameTitle, "gameTitle must not be null");
        validateSize(width, height, "Game window");
        ensureNoActiveWindows();

        initGlfwIfNeeded();
        try {
            studioWindow = null;
            gameWindow = createGlfwWindow(gameTitle, width, height, MemoryUtil.NULL);
            gameWindow.makeCurrent();
            gameWindow.setVsync(vsync);
            gameWindow.show();
        } catch (RuntimeException ex) {
            destroyWindowsOnly();
            throw ex;
        }
    }

    /**
     * Returns the live game window (Window B).
     *
     * @return live game window (Window B), or {@code null} if not initialized
     */
    public GlfwWindow getGameWindow() {
        return gameWindow;
    }

    /**
     * Returns the studio authoring window (Window A).
     *
     * @return studio authoring window (Window A), or {@code null} in standalone mode
     */
    public GlfwWindow getStudioWindow() {
        return studioWindow;
    }

    /**
     * Returns whether a Studio window (Window A) is currently open.
     *
     * @return {@code true} if a Studio window (Window A) is currently open
     */
    public boolean hasStudioWindow() {
        return studioWindow != null && !studioWindow.isDestroyed();
    }

    /**
     * Polls pending OS/GLFW window and input events.
     */
    public void pollEvents() {
        if (glfwInitialized) {
            GLFW.glfwPollEvents();
        }
    }

    /**
     * Returns whether any managed window has its close flag set.
     *
     * @return {@code true} if the game window or (when present) studio window should close
     */
    public boolean anyWindowShouldClose() {
        if (gameWindow != null && gameWindow.shouldClose()) {
            return true;
        }
        return studioWindow != null && studioWindow.shouldClose();
    }

    /**
     * Descriptor for an available OS display monitor connected to GLFW.
     *
     * @param index       zero-based monitor index
     * @param name        human-readable monitor name
     * @param width       native mode width in pixels
     * @param height      native mode height in pixels
     * @param refreshRate native vertical refresh rate in Hz
     * @param primary     whether this monitor is the primary desktop display
     */
    public record MonitorInfo(
            int index,
            String name,
            int width,
            int height,
            int refreshRate,
            boolean primary
    ) {
    }

    /**
     * Returns whether GLFW has been initialized by this manager.
     *
     * @return {@code true} if GLFW has been initialized by this manager
     */
    public boolean isGlfwInitialized() {
        return glfwInitialized;
    }

    /**
     * Enumerates available monitors when GLFW is initialized (returning empty list in headless environments
     * without GLFW active).
     *
     * @return immutable list of {@link MonitorInfo} entries
     */
    public List<MonitorInfo> enumerateMonitors() {
        if (!glfwInitialized) {
            return List.of();
        }
        var monitors = GLFW.glfwGetMonitors();
        if (monitors == null || monitors.limit() == 0) {
            return List.of();
        }
        long primaryHandle = GLFW.glfwGetPrimaryMonitor();
        java.util.ArrayList<MonitorInfo> result = new java.util.ArrayList<>(monitors.limit());
        for (int i = 0; i < monitors.limit(); i++) {
            long m = monitors.get(i);
            var mode = GLFW.glfwGetVideoMode(m);
            String name = GLFW.glfwGetMonitorName(m);
            int w = mode != null ? mode.width() : 1920;
            int h = mode != null ? mode.height() : 1080;
            int hz = mode != null ? mode.refreshRate() : 60;
            result.add(new MonitorInfo(
                    i,
                    name != null ? name : ("Monitor " + i),
                    w,
                    h,
                    hz,
                    m == primaryHandle
            ));
        }
        return List.copyOf(result);
    }

    /**
     * Resolves a GLFW monitor handle by clamped zero-based index, falling back to the primary monitor.
     *
     * @param monitorIndex zero-based monitor index
     * @return native monitor handle or {@link MemoryUtil#NULL} if unavailable
     */
    public static long resolveMonitorHandle(int monitorIndex) {
        var monitors = GLFW.glfwGetMonitors();
        if (monitors == null || monitors.limit() == 0) {
            return GLFW.glfwGetPrimaryMonitor();
        }
        int clamped = Math.clamp(monitorIndex, 0, monitors.limit() - 1);
        return monitors.get(clamped);
    }

    /**
     * Destroys all managed windows, frees the GLFW error callback, and terminates GLFW.
     * Safe to invoke multiple times.
     */
    public void dispose() {
        destroyWindowsOnly();
        if (glfwInitialized) {
            GLFW.glfwTerminate();
            glfwInitialized = false;
        }
        if (errorCallback != null) {
            GLFW.glfwSetErrorCallback(null);
            errorCallback.free();
            errorCallback = null;
        }
    }

    private void initGlfwIfNeeded() {
        if (glfwInitialized) {
            return;
        }
        errorCallback = GLFWErrorCallback.create((error, description) -> {
            String message = GLFWErrorCallback.getDescription(description);
            logger.error("GLFW", "Error 0x" + Integer.toHexString(error) + ": " + message);
        });
        GLFW.glfwSetErrorCallback(errorCallback);

        if (!GLFW.glfwInit()) {
            errorCallback.free();
            errorCallback = null;
            GLFW.glfwSetErrorCallback(null);
            throw new IllegalStateException("Failed to initialize GLFW");
        }
        glfwInitialized = true;
    }

    private static GlfwWindow createGlfwWindow(String title, int width, int height, long shareHandle) {
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        if (isMacOs()) {
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        }

        long handle = GLFW.glfwCreateWindow(width, height, title, MemoryUtil.NULL, shareHandle);
        if (handle == MemoryUtil.NULL) {
            throw new IllegalStateException(
                    "Failed to create GLFW window '" + title + "' (" + width + "x" + height + ")");
        }
        return new GlfwWindow(handle, title, width, height);
    }

    private void destroyWindowsOnly() {
        if (studioWindow != null) {
            studioWindow.destroy();
            studioWindow = null;
        }
        if (gameWindow != null) {
            gameWindow.destroy();
            gameWindow = null;
        }
    }

    private void ensureNoActiveWindows() {
        if (gameWindow != null || studioWindow != null) {
            throw new IllegalStateException("Windows are already initialized; call dispose() first");
        }
    }

    private static void validateSize(int width, int height, String label) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(label + " size must be positive, got " + width + "x" + height);
        }
    }

    private static boolean isMacOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }
}
