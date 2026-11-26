package de.swfdtf.core;

import de.swfdtf.graphics.GlfwWindow;
import de.swfdtf.graphics.IRenderPipeline;
import de.swfdtf.graphics.RenderMode;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.RenderPipeline3DStub;
import de.swfdtf.graphics.SceneGraph;
import de.swfdtf.graphics.WindowManager;
import de.swfdtf.math.Rect2f;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
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
    private static final String GAME_COORDINATOR_CLASS = "de.swfdtf.ui.GameRuntimeCoordinator";
    private static final String VPAK_PROVIDER_CLASS = "de.swfdtf.tools.VpakResourceProvider";
    private static final int SMOKE_TEST_FRAMES = 5;

    private final EngineConfig config;
    private final EngineAPI api;
    private final SceneGraph sceneGraph;
    private final WindowManager windowManager;
    private final PluginRegistry pluginRegistry;
    private final List<URLClassLoader> pluginClassLoaders = new ArrayList<>();
    private final ResourceProvider resourceProvider;

    private final List<Updatable> updatables = new ArrayList<>();
    private IRenderPipeline activePipeline;
    private StudioBridge studioBridge;
    private GameHost gameHost;
    private boolean studioBridgeInitialized = false;
    private boolean gameHostInitialized = false;
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
        this(config, studioBridge, null);
    }

    /**
     * Creates an {@link EngineApplication} with optional explicit {@link StudioBridge} and {@link GameHost}.
     *
     * @param config       immutable engine configuration
     * @param studioBridge optional studio bridge
     * @param gameHost     optional game host
     */
    public EngineApplication(EngineConfig config, StudioBridge studioBridge, GameHost gameHost) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        ResourceProvider vpakProvider = openVpakIfStandaloneWithoutLooseDescriptor(
                config.projectDir(), config.vpakKey());
        ProjectDescriptor descriptor = loadProjectDescriptorQuietly(config.projectDir(), vpakProvider);
        EngineLogger logger = new EngineLogger();
        GameClock clock = new GameClock();
        InputManager input = new InputManager();
        input.setLogicalSize(config.logicalWidth(), config.logicalHeight());
        this.api = new EngineAPI(config, descriptor, logger, clock, input);
        this.sceneGraph = new SceneGraph();
        this.windowManager = new WindowManager(logger);
        this.pluginRegistry = new PluginRegistry(this.api);
        this.resourceProvider = vpakProvider;
        this.studioBridge = studioBridge;
        this.gameHost = gameHost;

        this.api.registerService(SceneGraph.class, sceneGraph);
        this.api.registerService(EngineApplication.class, this);
        this.api.registerService(PluginRegistry.class, pluginRegistry);
        if (vpakProvider != null) {
            this.api.registerService(ResourceProvider.class, vpakProvider);
        }
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
        if (resourceProvider != null) {
            api.getLogger().info("Engine", "Loaded project '" + api.getProjectDescriptor().getTitle()
                    + "' from " + resourceProvider.getSourceDescription()
                    + " (" + resourceProvider.listPaths().size() + " entries)");
        }

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

        initializeGameHostIfApplicable();

        if (config.isStudioMode()) {
            initializeStudioBridgeIfAvailable();
        }

        if (config.projectDir() != null) {
            pluginClassLoaders.addAll(PluginLoader.loadFromDirectory(
                    config.projectDir().resolve("plugins"), pluginRegistry, api.getLogger()));
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

        if (api.consumeLiveReloadRequested()) {
            if (gameHostInitialized && gameHost != null) {
                gameHost.onReload();
            }
            if (studioBridge != null) {
                studioBridge.onReload();
            }
        }

        GameClock clock = api.getClock();
        int steps = clock.advanceFrame(realDeltaSeconds);
        double fixedDt = clock.getFixedTimestep();
        for (int i = 0; i < steps; i++) {
            sceneGraph.update((float) fixedDt);
            if (gameHostInitialized && gameHost != null) {
                gameHost.update(fixedDt, input);
            }
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
                if (gameHostInitialized && gameHost != null) {
                    gameHost.renderUi(api.getClock().getTotalTimeSeconds());
                }
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
                org.lwjgl.opengl.GL11.glViewport(
                        0, 0, studioWindow.getFramebufferWidth(), studioWindow.getFramebufferHeight());
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
        if (!config.headless()) {
            GlfwWindow gameWindow = windowManager.getGameWindow();
            if (gameWindow != null && !gameWindow.isDestroyed()) {
                gameWindow.makeCurrent();
            }
        }
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

    /**
     * Returns the core engine API facade.
     *
     * @return core engine API facade
     */
    public EngineAPI getApi() {
        return api;
    }

    /**
     * Returns the root scene graph.
     *
     * @return root scene graph
     */
    public SceneGraph getSceneGraph() {
        return sceneGraph;
    }

    /**
     * Returns the GLFW window manager.
     *
     * @return GLFW window manager
     */
    public WindowManager getWindowManager() {
        return windowManager;
    }

    /**
     * Returns the currently active render pipeline.
     *
     * @return currently active render pipeline
     */
    public IRenderPipeline getActivePipeline() {
        return activePipeline;
    }

    /**
     * Returns whether {@link #initialize()} has completed.
     *
     * @return {@code true} if {@link #initialize()} has completed
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Returns the active {@link GameHost}.
     *
     * @return active {@link GameHost}, or {@code null} if no project host was loaded
     */
    public GameHost getGameHost() {
        return gameHost;
    }

    @Override
    public void close() {
        if (disposed) {
            return;
        }
        disposed = true;

        if (gameHostInitialized && gameHost != null) {
            if (!config.headless()) {
                GlfwWindow gameWindow = windowManager.getGameWindow();
                if (gameWindow != null && !gameWindow.isDestroyed()) {
                    gameWindow.makeCurrent();
                }
            }
            try {
                gameHost.dispose();
            } catch (RuntimeException ex) {
                api.getLogger().error("Engine", "Error disposing GameHost", ex);
            }
            gameHostInitialized = false;
        }

        if (studioBridgeInitialized && studioBridge != null) {
            if (!config.headless() && windowManager.hasStudioWindow()) {
                windowManager.getStudioWindow().makeCurrent();
            }
            try {
                studioBridge.dispose();
            } catch (RuntimeException ex) {
                api.getLogger().error("Studio", "Error disposing StudioBridge", ex);
            } finally {
                if (!config.headless()) {
                    GlfwWindow gameWindow = windowManager.getGameWindow();
                    if (gameWindow != null && !gameWindow.isDestroyed()) {
                        gameWindow.makeCurrent();
                    }
                }
            }
            studioBridgeInitialized = false;
        }

        if (activePipeline != null) {
            if (!config.headless()) {
                GlfwWindow gameWindow = windowManager.getGameWindow();
                if (gameWindow != null && !gameWindow.isDestroyed()) {
                    gameWindow.makeCurrent();
                }
            }
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

        for (EnginePlugin plugin : pluginRegistry.listAll()) {
            try {
                pluginRegistry.unregister(plugin.id());
            } catch (RuntimeException ex) {
                api.getLogger().warn("PluginLoader", "Error unregistering plugin '" + plugin.id()
                        + "': " + ex.getMessage());
            }
        }
        for (URLClassLoader loader : pluginClassLoaders) {
            try {
                loader.close();
            } catch (IOException ex) {
                api.getLogger().warn("PluginLoader", "Error closing plugin classloader: " + ex.getMessage());
            }
        }
        pluginClassLoaders.clear();
        if (resourceProvider != null) {
            try {
                resourceProvider.close();
            } catch (IOException ex) {
                api.getLogger().warn("Engine", "Error closing ResourceProvider: " + ex.getMessage());
            }
        }
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

    private void initializeGameHostIfApplicable() {
        Path projectDir = config.projectDir();
        boolean hasProject = gameHost != null
                || resourceProvider != null
                || (projectDir != null && Files.isRegularFile(projectDir.resolve(ProjectDescriptor.FILE_NAME)));
        if (!hasProject) {
            return;
        }
        if (gameHost == null) {
            try {
                Class<?> clazz = Class.forName(GAME_COORDINATOR_CLASS);
                Object instance = clazz.getDeclaredConstructor().newInstance();
                if (instance instanceof GameHost host) {
                    gameHost = host;
                }
            } catch (ReflectiveOperationException ex) {
                api.getLogger().warn("Engine", "Failed to instantiate GameRuntimeCoordinator: " + ex.getMessage());
                return;
            }
        }
        gameHost.init(this, !config.headless());
        gameHostInitialized = true;
        api.registerService(GameHost.class, gameHost);
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
                api.getLogger().info(
                        "Studio", "StudioWindowController not yet present; Studio window runs base canvas.");
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
        boolean switchedToStudioContext = false;
        if (!config.headless() && windowManager.hasStudioWindow()) {
            windowManager.getStudioWindow().makeCurrent();
            switchedToStudioContext = true;
        }
        try {
            studioBridge.init(api, studioHandle, gameHandle);
            studioBridgeInitialized = true;
        } finally {
            if (switchedToStudioContext) {
                GlfwWindow gameWindow = windowManager.getGameWindow();
                if (gameWindow != null && !gameWindow.isDestroyed()) {
                    gameWindow.makeCurrent();
                }
            }
        }
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

    private static ResourceProvider openVpakIfStandaloneWithoutLooseDescriptor(Path projectDir, String vpakKey) {
        if (projectDir == null) {
            return null;
        }
        Path looseDescriptor = projectDir.resolve(ProjectDescriptor.FILE_NAME);
        if (Files.isRegularFile(looseDescriptor)) {
            return null;
        }
        Path vpakCandidate = null;
        Path dirVpak = projectDir.resolve("game.vpak");
        if (Files.isRegularFile(dirVpak)) {
            vpakCandidate = dirVpak;
        } else if (projectDir.toString().endsWith(".vpak") && Files.isRegularFile(projectDir)) {
            vpakCandidate = projectDir;
        }
        if (vpakCandidate == null) {
            return null;
        }
        ResourceProvider provider = null;
        try {
            Class<?> providerClass = Class.forName(VPAK_PROVIDER_CLASS);
            Method openMethod = providerClass.getMethod("open", Path.class, String.class);
            provider = (ResourceProvider) openMethod.invoke(null, vpakCandidate, vpakKey);
            provider.verifyIntegrity();
            return provider;
        } catch (InvocationTargetException ex) {
            closeProviderQuietly(provider);
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            throw new IllegalStateException("Failed to open/verify VPAK archive '"
                    + vpakCandidate + "': " + cause.getMessage(), cause);
        } catch (Exception ex) {
            closeProviderQuietly(provider);
            throw new IllegalStateException("Failed to open/verify VPAK archive '"
                    + vpakCandidate + "': " + ex.getMessage(), ex);
        }
    }

    private static void closeProviderQuietly(ResourceProvider provider) {
        if (provider != null) {
            try {
                provider.close();
            } catch (IOException ignored) {
                // Best-effort cleanup during startup failure
            }
        }
    }

    private static ProjectDescriptor loadProjectDescriptorQuietly(Path projectDir, ResourceProvider provider) {
        if (provider != null && provider.contains(ProjectDescriptor.FILE_NAME)) {
            try {
                String json = new String(
                        provider.readBytes(ProjectDescriptor.FILE_NAME), StandardCharsets.UTF_8);
                return ProjectDescriptor.parseJson(json);
            } catch (Exception ignored) {
                // Fall back to default descriptor when packed file cannot be parsed
            }
        }
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
