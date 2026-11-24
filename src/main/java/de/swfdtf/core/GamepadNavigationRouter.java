package de.swfdtf.core;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

/**
 * Translates continuous gamepad D-pad, left analog stick, and A/B face button states from
 * {@link InputManager} into discrete UI {@link NavigationDirection} events with configurable
 * deadzone and hold-to-repeat timing.
 */
public final class GamepadNavigationRouter {

    /**
     * Discrete UI navigation events emitted by {@link GamepadNavigationRouter}.
     */
    public enum NavigationDirection {
        UP,
        DOWN,
        LEFT,
        RIGHT,
        CONFIRM,
        CANCEL
    }

    /**
     * Default left-stick deflection threshold required to trigger directional navigation.
     */
    public static final float DEFAULT_DEADZONE = 0.35f;

    /**
     * Default hold duration in seconds before the first auto-repeat directional event fires.
     */
    public static final double DEFAULT_INITIAL_REPEAT_DELAY_SECONDS = 0.40;

    /**
     * Default interval in seconds between subsequent auto-repeat directional events while held.
     */
    public static final double DEFAULT_REPEAT_INTERVAL_SECONDS = 0.12;

    private static final NavigationDirection[] DIRECTIONAL_INPUTS = {
            NavigationDirection.UP,
            NavigationDirection.DOWN,
            NavigationDirection.LEFT,
            NavigationDirection.RIGHT
    };

    private float deadzone;
    private double initialRepeatDelaySeconds;
    private double repeatIntervalSeconds;

    private final EnumMap<NavigationDirection, RepeatState> repeatStates = new EnumMap<>(NavigationDirection.class);
    private boolean confirmWasDown = false;
    private boolean cancelWasDown = false;

    /**
     * Creates a {@link GamepadNavigationRouter} with default deadzone and repeat timings.
     */
    public GamepadNavigationRouter() {
        this(
                DEFAULT_DEADZONE,
                DEFAULT_INITIAL_REPEAT_DELAY_SECONDS,
                DEFAULT_REPEAT_INTERVAL_SECONDS
        );
    }

    /**
     * Creates a {@link GamepadNavigationRouter} with custom deadzone and hold-repeat timing.
     *
     * @param deadzone                  analog stick deadzone in {@code [0.0, 1.0)}
     * @param initialRepeatDelaySeconds delay in seconds before first repeat ({@code > 0.0})
     * @param repeatIntervalSeconds     interval in seconds between subsequent repeats ({@code > 0.0})
     */
    public GamepadNavigationRouter(
            float deadzone,
            double initialRepeatDelaySeconds,
            double repeatIntervalSeconds
    ) {
        setDeadzone(deadzone);
        setInitialRepeatDelaySeconds(initialRepeatDelaySeconds);
        setRepeatIntervalSeconds(repeatIntervalSeconds);
        for (NavigationDirection direction : DIRECTIONAL_INPUTS) {
            repeatStates.put(direction, new RepeatState());
        }
    }

    /**
     * Polls {@code input} for the current frame and returns any discrete navigation events triggered.
     *
     * @param input        input manager supplying gamepad button and axis states (must not be null)
     * @param deltaSeconds elapsed frame time in seconds (must be finite and {@code >= 0.0})
     * @return unmodifiable list of triggered {@link NavigationDirection} events in deterministic order
     */
    public List<NavigationDirection> poll(InputManager input, double deltaSeconds) {
        Objects.requireNonNull(input, "input must not be null");
        if (!Double.isFinite(deltaSeconds) || deltaSeconds < 0.0) {
            throw new IllegalArgumentException("deltaSeconds must be finite and >= 0, got: " + deltaSeconds);
        }

        if (!input.isGamepadConnected()) {
            reset();
            return List.of();
        }

        float leftX = input.getGamepadAxis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X);
        float leftY = input.getGamepadAxis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y);

        boolean rawUp = input.isGamepadButtonDown(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_UP) || leftY < -deadzone;
        boolean rawDown = input.isGamepadButtonDown(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN) || leftY > deadzone;
        boolean rawLeft = input.isGamepadButtonDown(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_LEFT) || leftX < -deadzone;
        boolean rawRight = input.isGamepadButtonDown(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_RIGHT) || leftX > deadzone;

        boolean upActive = rawUp && !rawDown;
        boolean downActive = rawDown && !rawUp;
        boolean leftActive = rawLeft && !rawRight;
        boolean rightActive = rawRight && !rawLeft;

        List<NavigationDirection> events = new ArrayList<>(4);
        processDirection(NavigationDirection.UP, upActive, deltaSeconds, events);
        processDirection(NavigationDirection.DOWN, downActive, deltaSeconds, events);
        processDirection(NavigationDirection.LEFT, leftActive, deltaSeconds, events);
        processDirection(NavigationDirection.RIGHT, rightActive, deltaSeconds, events);

        boolean confirmDown = input.isGamepadButtonDown(GLFW.GLFW_GAMEPAD_BUTTON_A);
        if (input.wasGamepadButtonJustPressed(GLFW.GLFW_GAMEPAD_BUTTON_A) || (confirmDown && !confirmWasDown)) {
            events.add(NavigationDirection.CONFIRM);
        }
        confirmWasDown = confirmDown || input.wasGamepadButtonJustPressed(GLFW.GLFW_GAMEPAD_BUTTON_A);

        boolean cancelDown = input.isGamepadButtonDown(GLFW.GLFW_GAMEPAD_BUTTON_B);
        if (input.wasGamepadButtonJustPressed(GLFW.GLFW_GAMEPAD_BUTTON_B) || (cancelDown && !cancelWasDown)) {
            events.add(NavigationDirection.CANCEL);
        }
        cancelWasDown = cancelDown || input.wasGamepadButtonJustPressed(GLFW.GLFW_GAMEPAD_BUTTON_B);

        return events.isEmpty() ? List.of() : Collections.unmodifiableList(events);
    }

    /**
     * Resets all held-direction repeat timers and face-button edge tracking states.
     */
    public void reset() {
        for (RepeatState state : repeatStates.values()) {
            state.reset();
        }
        confirmWasDown = false;
        cancelWasDown = false;
    }

    /**
     * Returns the configured analog stick deadzone threshold.
     *
     * @return deadzone in {@code [0.0, 1.0)}
     */
    public float getDeadzone() {
        return deadzone;
    }

    /**
     * Updates the analog stick deadzone threshold.
     *
     * @param deadzone threshold in {@code [0.0, 1.0)}
     */
    public void setDeadzone(float deadzone) {
        if (!Float.isFinite(deadzone) || deadzone < 0.0f || deadzone >= 1.0f) {
            throw new IllegalArgumentException("deadzone must be in [0.0, 1.0), got: " + deadzone);
        }
        this.deadzone = deadzone;
    }

    /**
     * Returns the initial delay in seconds before a held direction begins repeating.
     *
     * @return initial repeat delay in seconds
     */
    public double getInitialRepeatDelaySeconds() {
        return initialRepeatDelaySeconds;
    }

    /**
     * Sets the initial delay in seconds before a held direction begins repeating.
     *
     * @param initialRepeatDelaySeconds delay in seconds (must be finite and {@code > 0.0})
     */
    public void setInitialRepeatDelaySeconds(double initialRepeatDelaySeconds) {
        if (!Double.isFinite(initialRepeatDelaySeconds) || initialRepeatDelaySeconds <= 0.0) {
            throw new IllegalArgumentException(
                    "initialRepeatDelaySeconds must be finite and > 0, got: " + initialRepeatDelaySeconds
            );
        }
        this.initialRepeatDelaySeconds = initialRepeatDelaySeconds;
    }

    /**
     * Returns the repeat interval in seconds after the initial hold delay expires.
     *
     * @return repeat interval in seconds
     */
    public double getRepeatIntervalSeconds() {
        return repeatIntervalSeconds;
    }

    /**
     * Sets the repeat interval in seconds after the initial hold delay expires.
     *
     * @param repeatIntervalSeconds interval in seconds (must be finite and {@code > 0.0})
     */
    public void setRepeatIntervalSeconds(double repeatIntervalSeconds) {
        if (!Double.isFinite(repeatIntervalSeconds) || repeatIntervalSeconds <= 0.0) {
            throw new IllegalArgumentException(
                    "repeatIntervalSeconds must be finite and > 0, got: " + repeatIntervalSeconds
            );
        }
        this.repeatIntervalSeconds = repeatIntervalSeconds;
    }

    private void processDirection(
            NavigationDirection direction,
            boolean active,
            double deltaSeconds,
            List<NavigationDirection> events
    ) {
        RepeatState state = repeatStates.get(direction);
        if (!active) {
            state.reset();
            return;
        }

        if (!state.held) {
            state.held = true;
            state.inInitialDelay = true;
            state.elapsedSeconds = 0.0;
            events.add(direction);
            return;
        }

        state.elapsedSeconds += deltaSeconds;
        if (state.inInitialDelay) {
            if (state.elapsedSeconds < initialRepeatDelaySeconds) {
                return;
            }
            state.elapsedSeconds -= initialRepeatDelaySeconds;
            state.inInitialDelay = false;
            events.add(direction);
        }

        while (state.elapsedSeconds >= repeatIntervalSeconds) {
            state.elapsedSeconds -= repeatIntervalSeconds;
            events.add(direction);
        }
    }

    private static final class RepeatState {
        private boolean held = false;
        private boolean inInitialDelay = true;
        private double elapsedSeconds = 0.0;

        private void reset() {
            held = false;
            inInitialDelay = true;
            elapsedSeconds = 0.0;
        }
    }
}
