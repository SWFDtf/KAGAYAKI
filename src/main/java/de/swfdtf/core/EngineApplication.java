package de.swfdtf.core;

import de.swfdtf.graphics.GlfwWindow;
import de.swfdtf.graphics.IRenderPipeline;
import de.swfdtf.graphics.RenderMode;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.RenderPipeline3DStub;
import de.swfdtf.graphics.SceneGraph;
import de.swfdtf.graphics.WindowManager;
import de.swfdtf.math.Rect2f;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Top-level KAGAYAKI runtime coordinator managing initialization, the deterministic
 * fixed-timestep update loop, 2D/3D render pipeline switching, input routing,
 * Dual-Window Studio coordination, and Standalone Game execution.
 */
public final class EngineApplication implements AutoCloseable {

    private static final String STUDIO_CONTROLLER_CLASS = "de.swfdtf.tools.StudioWindowController";
    private static final int SMOKE_TEST_FRAMES = 5;

    private final EngineConfig config;
    private final EngineAPI api;
    private final SceneGraph sceneGraph;
    private final WindowManager windowManager;

    private final List<Updatable> updatables = new ArrayList<>();
    private IRenderPipeline activePipeline;
    private StudioBridge studioBridge;
    private boolean studioBridgeInitialized = false;
    private boolean initialized = false;
    private boolean disposed = false;

    /**
     * Functional hook for fixed-timestep engine subsystems.
     */
    @FunctionalInterface
    public interface Updatable {
        /**
         * Advances subsystem state by a deterministic fixed timestep.
         *
         * @param fixedDeltaSeconds fixed delta in seconds ({@code 1.0 / 60.0})
         */
        void update(double fixedDeltaSeconds);
    }

    /**
     * Creates an {@link EngineApplication} from the given runtime configuration.
     *
     * @param config immutable engine configuration
     */
    public EngineApplication(EngineConfig config) {
        this(config, null);
    }

    /**
     * Creates an {@link EngineApplication} with an optional explicitly supplied {@link StudioBridge}.
     *
     * @param config       immutable engine configuration
     * @param studioBridge optional studio bridge (if {@code null} in studio mode, loaded dynamically if present)
     */
    public EngineApplication(EngineConfig config, StudioBridge studioBridge) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        ProjectDescriptor descriptor = loadProjectDescriptorQuietly(config.projectDir());
        EngineLogger logger = new EngineLogger();
        GameClock clock = new GameClock();
        InputManager input = new InputManager();
        input.setLogicalSize(config.logicalWidth(), config.logicalHeight());
        this.api = new EngineAPI(config, descriptor, logger, clock, input);
        this.sceneGraph = new SceneGraph();
        this.windowManager = new WindowManager(logger);
        this.studioBridge = studioBridge;

        this.api.registerService(SceneGraph.class, sceneGraph);
        this.api.registerService(EngineApplication.class, this);
    }

    /**
     * Initializes windows (unless headless), input bindings, active render pipeline,
     * and optional Studio bridge.
     */
    public void initialize() {
        if (initialized) {
            return;
        }

        RenderMode requestedMode = resolveInitialRenderMode();
        api.getLogger().info("Engine", "Starting " + api.getEngineVersion()
                + " mode=" + config.engineMode()
                + " render=" + requestedMode
                + " headless=" + config.headless());

        if (!config.headless()) {
            String gameTitle = buildGameWindowTitle();
            if (config.isStudioMode()) {
                windowManager.initDualWindows(
                        "KAGAYAKI Studio — " + api.getProjectDescriptor().getTitle(),
                        gameTitle,
                        1280,
                        720,
                        config.vsync()
                );
            } else {
                windowManager.initStandaloneWindow(gameTitle, 1280, 720, config.vsync());
            }

            GlfwWindow gameWindow = windowManager.getGameWindow();
            gameWindow.makeCurrent();
            api.getInput().attachToWindow(gameWindow.getHandle());
        }

        switchRenderPipeline(requestedMode);

        if (config.isStudioMode()) {
            initializeStudioBridgeIfAvailable();
        }

        initialized = true;
    }

    /**
     * Runs the main fixed-timestep and render loop until quit or smoke-test completion.
     */
    public void run() {
        if (!initialized) {
            initialize();
        }

        long frameCount = 0L;
        double lastTime = System.nanoTime() / 1_000_000_000.0;
        double frameBudget = 1.0 / Math.max(1, config.targetFps());

        while (!shouldTerminate(frameCount)) {
            double now = System.nanoTime() / 1_000_000_000.0;
            double realDelta = Math.max(0.0, now - lastTime);
            lastTime = now;

            stepOnce(realDelta);
            frameCount++;

            if (!config.headless() && !config.vsync() && frameBudget > 0.0) {
                paceFrame(now + frameBudget);
            }
        }
    }

    /**
     * Executes a single frame iteration (event polling, fixed-timestep simulation, and rendering).
     * Exposed publicly for deterministic headless tests and external drivers.
     *
     * @param realDeltaSeconds elapsed wall-clock time since the previous frame
     */
    public void stepOnce(double realDeltaSeconds) {
        if (!initialized) {
            initialize();
        }

        InputManager input = api.getInput();
        input.beginFrame();

        if (!config.headless()) {
            windowManager.pollEvents();
            input.pollGamepads();
        }

        if (api.consumeLiveReloadRequested() && studioBridge != null) {
            studioBridge.onReload();
        }

        GameClock clock = api.getClock();
        int steps = clock.advanceFrame(realDeltaSeconds);
        double fixedDt = clock.getFixedTimestep();
        for (int i = 0; i < steps; i++) {
            sceneGraph.update((float) fixedDt);
            for (Updatable updatable : List.copyOf(updatables)) {
                updatable.update(fixedDt);
            }
            if (studioBridgeInitialized && studioBridge != null) {
                studioBridge.update(fixedDt);
            }
        }

        if (!config.headless()) {
            renderWindows();
        } else if (activePipeline instanceof RenderPipeline3DStub stub) {
            stub.beginFrame(config.logicalWidth(), config.logicalHeight());
            stub.renderSceneGraph(sceneGraph);
            stub.endFrame();
        }

        input.endFrame();
    }

    private void renderWindows() {
        GlfwWindow gameWindow = windowManager.getGameWindow();
        if (gameWindow != null && !gameWindow.isDestroyed()) {
            gameWindow.makeCurrent();
            gameWindow.pollFramebufferSize();
            int fbW = gameWindow.getFramebufferWidth();
            int fbH = gameWindow.getFramebufferHeight();
            if (fbW > 0 && fbH > 0) {
                updateNormalizedMouseAndViewport(gameWindow, fbW, fbH);
                activePipeline.beginFrame(fbW, fbH);
                activePipeline.renderSceneGraph(sceneGraph);
                activePipeline.endFrame();
                if (activePipeline instanceof RenderPipeline2D p2d) {
                    Rect2f vp = p2d.getLastLetterboxViewport();
                    if (vp.width() > 0 && vp.height() > 0) {
                        float scaleX = gameWindow.getWindowWidth() > 0
                                ? gameWindow.getWindowWidth() / (float) fbW
                                : 1.0f;
                        float scaleY = gameWindow.getWindowHeight() > 0
                                ? gameWindow.getWindowHeight() / (float) fbH
                                : 1.0f;
                        api.getInput().setViewport(
                                vp.x() * scaleX,
                                vp.y() * scaleY,
                                vp.width() * scaleX,
                                vp.height() * scaleY
                        );
                    }
                }
            }
            gameWindow.swapBuffers();
        }

        GlfwWindow studioWindow = windowManager.getStudioWindow();
        if (studioWindow != null && !studioWindow.isDestroyed()) {
            studioWindow.makeCurrent();
            studioWindow.pollFramebufferSize();
            if (studioBridgeInitialized && studioBridge != null) {
                studioBridge.render(studioWindow.getFramebufferWidth(), studioWindow.getFramebufferHeight());
            } else {
                org.lwjgl.opengl.GL11.glViewport(0, 0, studioWindow.getFramebufferWidth(), studioWindow.getFramebufferHeight());
                org.lwjgl.opengl.GL11.glClearColor(0x16 / 255.0f, 0x18 / 255.0f, 0x22 / 255.0f, 1.0f);
                org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT);
            }
            studioWindow.swapBuffers();
        }

        if (gameWindow != null && !gameWindow.isDestroyed()) {
            gameWindow.makeCurrent();
        }
    }

    private void updateNormalizedMouseAndViewport(GlfwWindow gameWindow, int fbW, int fbH) {
        if (activePipeline instanceof RenderPipeline2D) {
            Rect2f vp = RenderPipeline2D.computeLetterboxViewport(
                    gameWindow.getWindowWidth(),
                    gameWindow.getWindowHeight(),
                    config.logicalWidth(),
                    config.logicalHeight()
            );
            api.getInput().setViewport(vp.x(), vp.y(), vp.width(), vp.height());
        } else {
            api.getInput().updateLetterboxViewport(gameWindow.getWindowWidth(), gameWindow.getWindowHeight());
        }

        float nx = (float) (api.getInput().getClampedLogicalMouseX() / config.logicalWidth());
        float ny = (float) (api.getInput().getClampedLogicalMouseY() / config.logicalHeight());
        sceneGraph.setNormalizedMouse(nx, ny);
    }

    /**
     * Switches the active rendering backend between {@link RenderMode#MODE_2D} and {@link RenderMode#MODE_3D}.
     *
     * @param mode target render mode
     */
    public void switchRenderPipeline(RenderMode mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        if (activePipeline != null) {
            if (activePipeline.getMode() == mode) {
                return;
            }
            activePipeline.dispose();
        }

        if (mode == RenderMode.MODE_3D) {
            activePipeline = new RenderPipeline3DStub(api.getLogger(), !config.headless());
            activePipeline.init(config.logicalWidth(), config.logicalHeight());
        } else {
            RenderPipeline2D pipeline2D = new RenderPipeline2D();
            if (!config.headless()) {
                pipeline2D.init(config.logicalWidth(), config.logicalHeight());
            }
            activePipeline = pipeline2D;
        }

        api.registerService(IRenderPipeline.class, activePipeline);
        api.setProperty("renderMode", mode.name());
    }

    /**
     * Registers a fixed-timestep {@link Updatable} callback.
     *
     * @param updatable updatable component
     */
    public void addUpdatable(Updatable updatable) {
        Objects.requireNonNull(updatable, "updatable must not be null");
        updatables.add(updatable);
    }

    /**
     * Removes a previously registered {@link Updatable}.
     *
     * @param updatable updatable component
     */
    public void removeUpdatable(Updatable updatable) {
        updatables.remove(updatable);
    }

    /** @return core engine API facade */
    public EngineAPI getApi() {
        return api;
    }

    /** @return root scene graph */
    public SceneGraph getSceneGraph() {
        return sceneGraph;
    }

    /** @return GLFW window manager */
    public WindowManager getWindowManager() {
        return windowManager;
    }

    /** @return currently active render pipeline */
    public IRenderPipeline getActivePipeline() {
        return activePipeline;
    }

    /** @return {@code true} if {@link #initialize()} has completed */
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void close() {
        if (disposed) {
            return;
        }
        disposed = true;

        if (studioBridgeInitialized && studioBridge != null) {
            try {
                studioBridge.dispose();
            } catch (RuntimeException ex) {
                api.getLogger().error("Studio", "Error disposing StudioBridge", ex);
            }
            studioBridgeInitialized = false;
        }

        if (activePipeline != null) {
            try {
                if (!config.headless() || activePipeline instanceof RenderPipeline3DStub) {
                    activePipeline.dispose();
                }
            } catch (RuntimeException ex) {
                api.getLogger().error("Graphics", "Error disposing active render pipeline", ex);
            }
            activePipeline = null;
        }

        api.getInput().dispose();
        windowManager.dispose();
        api.getLogger().info("Engine", "KAGAYAKI shutdown complete.");
    }

    private RenderMode resolveInitialRenderMode() {
        if (config.is3DMode()) {
            return RenderMode.MODE_3D;
        }
        ProjectDescriptor desc = api.getProjectDescriptor();
        if (desc != null && "3D".equalsIgnoreCase(desc.getRenderMode())) {
            return RenderMode.MODE_3D;
        }
        return RenderMode.MODE_2D;
    }

    private String buildGameWindowTitle() {
        ProjectDescriptor desc = api.getProjectDescriptor();
        if (desc != null && desc.getWindowTitle() != null && !desc.getWindowTitle().isBlank()) {
            return desc.getWindowTitle();
        }
        String projectTitle = desc != null && desc.getTitle() != null ? desc.getTitle() : "KAGAYAKI";
        return config.isStudioMode()
                ? projectTitle + " — Live Preview"
                : projectTitle;
    }

    private void initializeStudioBridgeIfAvailable() {
        if (studioBridge == null) {
            try {
                Class<?> clazz = Class.forName(STUDIO_CONTROLLER_CLASS);
                Object instance = clazz.getDeclaredConstructor().newInstance();
                if (instance instanceof StudioBridge bridge) {
                    studioBridge = bridge;
                }
            } catch (ClassNotFoundException ignored) {
                api.getLogger().info("Studio", "StudioWindowController not yet present; Studio window runs base canvas.");
                return;
            } catch (ReflectiveOperationException ex) {
                api.getLogger().warn("Studio", "Failed to instantiate StudioWindowController: " + ex.getMessage());
                return;
            }
        }

        long studioHandle = windowManager.getStudioWindow() != null
                ? windowManager.getStudioWindow().getHandle()
                : 0L;
        long gameHandle = windowManager.getGameWindow() != null
                ? windowManager.getGameWindow().getHandle()
                : 0L;
        studioBridge.init(api, studioHandle, gameHandle);
        studioBridgeInitialized = true;
    }

    private boolean shouldTerminate(long frameCount) {
        if (api.isQuitRequested()) {
            return true;
        }
        if (!config.headless() && windowManager.anyWindowShouldClose()) {
            return true;
        }
        return config.smokeTest() && frameCount >= SMOKE_TEST_FRAMES;
    }

    private static void paceFrame(double targetEndTimeSeconds) {
        while ((System.nanoTime() / 1_000_000_000.0) < targetEndTimeSeconds) {
            Thread.onSpinWait();
        }
    }

    private static ProjectDescriptor loadProjectDescriptorQuietly(Path projectDir) {
        if (projectDir != null) {
            Path descriptorPath = projectDir.resolve(ProjectDescriptor.FILE_NAME);
            if (Files.isRegularFile(descriptorPath)) {
                try {
                    return ProjectDescriptor.load(projectDir);
                } catch (Exception ignored) {
                    // Fall back to default descriptor when project file is unreadable during early startup
                }
            }
        }
        return ProjectDescriptor.createDefault("KAGAYAKI");
    }
}
