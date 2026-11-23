package de.swfdtf.core;

/**
 * Decoupling bridge interface connecting {@code EngineApplication} in the core runtime
 * with {@code StudioWindowController} in the {@code tools} package.
 *
 * <p>Keeping this interface inside {@code de.swfdtf.core} allows Gradle's {@code packageStandalone}
 * task to exclude {@code StudioWindowController*} classes cleanly without leaving unresolved runtime
 * dependencies in standalone distributions.</p>
 */
public interface StudioBridge {

    /**
     * Initializes the Studio authoring window and attaches hooks to the live game window.
     *
     * @param api                shared engine facade
     * @param studioWindowHandle native GLFW handle of the Studio window (Window A)
     * @param gameWindowHandle   native GLFW handle of the live game window (Window B)
     */
    void init(EngineAPI api, long studioWindowHandle, long gameWindowHandle);

    /**
     * Advances Studio background tasks, asset watchers, and UI state.
     *
     * @param dt frame delta time in seconds
     */
    void update(double dt);

    /**
     * Renders the Studio window contents for the given dimensions.
     *
     * @param windowWidth  Studio window width in pixels
     * @param windowHeight Studio window height in pixels
     */
    void render(int windowWidth, int windowHeight);

    /**
     * Notifies the Studio controller that project scripts or assets were live-reloaded.
     */
    void onReload();

    /**
     * Releases all resources owned by the Studio window controller.
     */
    void dispose();
}
