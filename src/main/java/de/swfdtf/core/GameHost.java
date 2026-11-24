package de.swfdtf.core;

/**
 * Lifecycle bridge decoupling {@link EngineApplication} from higher-level gameplay, UI screens,
 * audio mixing, and script execution in {@code de.swfdtf.ui}.
 */
public interface GameHost {

    /**
     * Initializes script runtime, UI screens, and audio subsystems.
     *
     * @param app       owning engine application
     * @param glEnabled {@code true} when an OpenGL context is active on the game window
     */
    void init(EngineApplication app, boolean glEnabled);

    /**
     * Advances game simulation, script timers, animations, audio, and UI input.
     *
     * @param dt    fixed delta time in seconds
     * @param input active input manager
     */
    void update(double dt, InputManager input);

    /**
     * Renders active UI screens and overlays onto the logical 1920x1080 canvas.
     *
     * @param elapsedSeconds total elapsed runtime seconds
     */
    void renderUi(double elapsedSeconds);

    /**
     * Reloads {@code .vjava} scripts and assets while preserving active variables and position.
     */
    void onReload();

    /**
     * Releases audio, font, texture, and script resources cleanly.
     */
    void dispose();
}
