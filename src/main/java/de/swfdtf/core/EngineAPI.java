package de.swfdtf.core;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Core public engine facade exposed to {@code @->{ ... }<<} Java script blocks (as {@code engine}),
 * declarative UI screens, and Studio authoring tools.
 *
 * <p>Provides unified access to logging, configuration, project metadata, deterministic clock,
 * input state, lifecycle flags, dynamic engine properties, and a typed service registry so higher-level
 * subsystems ({@code state}, {@code audio}, {@code scene}, {@code scriptRuntime}) can register
 * themselves without introducing circular package dependencies.</p>
 */
public final class EngineAPI {

    /**
     * Human-readable KAGAYAKI engine version string.
     */
    public static final String ENGINE_VERSION = "KAGAYAKI 1.0.0";

    /**
     * Graphics backend identification string.
     */
    public static final String RENDER_BACKEND_NAME = "LWJGL 3 / OpenGL 3.3 Core";

    private static final String SCRIPT_LOG_CATEGORY = "Script";

    private final EngineConfig config;
    private final EngineLogger logger;
    private final GameClock clock;
    private final InputManager input;

    private volatile ProjectDescriptor projectDescriptor;
    private final AtomicBoolean quitRequested = new AtomicBoolean(false);
    private final AtomicBoolean liveReloadRequested = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Object> properties = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<?>, Object> services = new ConcurrentHashMap<>();

    /**
     * Creates an {@link EngineAPI} instance with default {@link EngineLogger}, {@link GameClock},
     * {@link InputManager}, and a default {@link ProjectDescriptor}.
     *
     * @param config immutable engine runtime configuration
     */
    public EngineAPI(EngineConfig config) {
        this(
                config,
                ProjectDescriptor.createDefault("KAGAYAKI"),
                new EngineLogger(),
                new GameClock(),
                new InputManager()
        );
    }

    /**
     * Creates an {@link EngineAPI} instance with explicitly provided core subsystems.
     *
     * @param config            immutable engine runtime configuration
     * @param projectDescriptor active project descriptor (defaults to {@code "KAGAYAKI"} if null)
     * @param logger            structured engine logger
     * @param clock             deterministic fixed-timestep clock
     * @param input             input manager
     */
    public EngineAPI(
            EngineConfig config,
            ProjectDescriptor projectDescriptor,
            EngineLogger logger,
            GameClock clock,
            InputManager input
    ) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.projectDescriptor = projectDescriptor != null
                ? projectDescriptor
                : ProjectDescriptor.createDefault("KAGAYAKI");
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.input = Objects.requireNonNull(input, "input must not be null");
    }

    /**
     * Logs an informational message via the engine logger.
     *
     * @param message message text
     */
    public void logInfo(String message) {
        logger.info(SCRIPT_LOG_CATEGORY, message);
    }

    /**
     * Logs a warning message via the engine logger.
     *
     * @param message message text
     */
    public void logWarn(String message) {
        logger.warn(SCRIPT_LOG_CATEGORY, message);
    }

    /**
     * Logs an error message via the engine logger.
     *
     * @param message message text
     */
    public void logError(String message) {
        logger.error(SCRIPT_LOG_CATEGORY, message);
    }

    /**
     * Logs an error message and throwable stack trace via the engine logger.
     *
     * @param message   message text
     * @param throwable cause exception
     */
    public void logError(String message, Throwable throwable) {
        logger.error(SCRIPT_LOG_CATEGORY, message, throwable);
    }

    /**
     * Logs a debug message via the engine logger.
     *
     * @param message message text
     */
    public void logDebug(String message) {
        logger.debug(SCRIPT_LOG_CATEGORY, message);
    }

    /**
     * Returns the active immutable engine configuration.
     *
     * @return engine configuration
     */
    public EngineConfig getConfig() {
        return config;
    }

    /**
     * Returns the currently loaded project metadata descriptor.
     *
     * @return active project descriptor
     */
    public ProjectDescriptor getProjectDescriptor() {
        return projectDescriptor;
    }

    /**
     * Replaces the active project metadata descriptor.
     *
     * @param descriptor new project descriptor (must not be null)
     */
    public void setProjectDescriptor(ProjectDescriptor descriptor) {
        this.projectDescriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
    }

    /**
     * Returns the deterministic fixed-timestep simulation clock.
     *
     * @return game clock
     */
    public GameClock getClock() {
        return clock;
    }

    /**
     * Returns the input manager tracking keyboard, mouse, and gamepad state.
     *
     * @return input manager
     */
    public InputManager getInput() {
        return input;
    }

    /**
     * Returns the structured thread-safe engine logger.
     *
     * @return engine logger
     */
    public EngineLogger getLogger() {
        return logger;
    }

    /**
     * Signals the main application loop to terminate gracefully at the end of the current frame.
     */
    public void requestQuit() {
        quitRequested.set(true);
    }

    /**
     * Returns whether a graceful shutdown has been requested.
     *
     * @return {@code true} if quit was requested
     */
    public boolean isQuitRequested() {
        return quitRequested.get();
    }

    /**
     * Requests a hot live-reload of {@code .vjava} scripts and project assets.
     */
    public void requestLiveReload() {
        liveReloadRequested.set(true);
    }

    /**
     * Atomically reads and clears any pending live-reload request.
     *
     * @return {@code true} if a live-reload was pending
     */
    public boolean consumeLiveReloadRequested() {
        return liveReloadRequested.getAndSet(false);
    }

    /**
     * Returns the KAGAYAKI engine version identifier.
     *
     * @return {@code "KAGAYAKI 1.0.0"}
     */
    public String getEngineVersion() {
        return ENGINE_VERSION;
    }

    /**
     * Returns the active render backend identifier string.
     *
     * @return {@code "LWJGL 3 / OpenGL 3.3 Core"}
     */
    public String getRenderBackendName() {
        return RENDER_BACKEND_NAME;
    }

    /**
     * Retrieves a dynamic runtime property by key.
     *
     * @param key property key (must not be null)
     * @return stored value or {@code null} if absent
     */
    public Object getProperty(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return properties.get(key);
    }

    /**
     * Stores or removes a dynamic runtime property. Passing {@code null} for {@code value}
     * removes the property entry.
     *
     * @param key   property key (must not be null)
     * @param value property value, or {@code null} to remove
     */
    public void setProperty(String key, Object value) {
        Objects.requireNonNull(key, "key must not be null");
        if (value == null) {
            properties.remove(key);
        } else {
            properties.put(key, value);
        }
    }

    /**
     * Removes a dynamic runtime property.
     *
     * @param key property key (must not be null)
     * @return previous value or {@code null} if none existed
     */
    public Object removeProperty(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return properties.remove(key);
    }

    /**
     * Returns an unmodifiable snapshot of all currently set dynamic properties.
     *
     * @return immutable property map copy
     */
    public Map<String, Object> getPropertiesSnapshot() {
        return Collections.unmodifiableMap(Map.copyOf(properties));
    }

    /**
     * Registers a typed subsystem service instance in the engine service locator.
     *
     * @param serviceType interface or base class key
     * @param instance    service implementation instance
     * @param <T>         service type
     */
    public <T> void registerService(Class<T> serviceType, T instance) {
        Objects.requireNonNull(serviceType, "serviceType must not be null");
        Objects.requireNonNull(instance, "instance must not be null");
        if (!serviceType.isInstance(instance)) {
            throw new IllegalArgumentException(
                    "Service instance of type " + instance.getClass().getName()
                            + " does not implement " + serviceType.getName()
            );
        }
        services.put(serviceType, instance);
    }

    /**
     * Retrieves a registered service instance, or {@code null} if no service is registered for {@code serviceType}.
     *
     * @param serviceType interface or base class key
     * @param <T>         service type
     * @return registered service instance or {@code null}
     */
    public <T> T getService(Class<T> serviceType) {
        Objects.requireNonNull(serviceType, "serviceType must not be null");
        Object service = services.get(serviceType);
        return service != null ? serviceType.cast(service) : null;
    }

    /**
     * Retrieves a registered service instance, throwing {@link IllegalStateException} if absent.
     *
     * @param serviceType interface or base class key
     * @param <T>         service type
     * @return registered service instance
     */
    public <T> T requireService(Class<T> serviceType) {
        T service = getService(serviceType);
        if (service == null) {
            throw new IllegalStateException("Required engine service not registered: " + serviceType.getName());
        }
        return service;
    }

    /**
     * Returns whether a service is currently registered for {@code serviceType}.
     *
     * @param serviceType interface or base class key
     * @return {@code true} if registered
     */
    public boolean hasService(Class<?> serviceType) {
        Objects.requireNonNull(serviceType, "serviceType must not be null");
        return services.containsKey(serviceType);
    }

    /**
     * Unregisters a previously registered service type.
     *
     * @param serviceType interface or base class key
     */
    public void unregisterService(Class<?> serviceType) {
        Objects.requireNonNull(serviceType, "serviceType must not be null");
        services.remove(serviceType);
    }
}
