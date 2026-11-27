package de.swfdtf.core;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWWindowFocusCallback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Centralized GLFW input tracker supporting keyboard states, modifiers, Unicode text input,
 * letterbox-mapped logical mouse coordinates, scroll deltas, and gamepad polling.
 *
 * <p>Event handlers may also be invoked directly in headless environments and unit tests
 * without an active native GLFW window.</p>
 */
public final class InputManager {

    /**
     * Number of addressable GLFW keyboard slots ({@code 0..GLFW_KEY_LAST}).
     */
    public static final int KEY_COUNT = GLFW.GLFW_KEY_LAST + 1;

    /**
     * Number of supported mouse buttons ({@code 0..7}).
     */
    public static final int MOUSE_BUTTON_COUNT = GLFW.GLFW_MOUSE_BUTTON_LAST + 1;

    /**
     * Number of GLFW joystick slots ({@code 0..GLFW_JOYSTICK_LAST}).
     */
    public static final int MAX_JOYSTICKS = GLFW.GLFW_JOYSTICK_LAST + 1;

    /**
     * Number of standardized GLFW gamepad buttons ({@code 0..GLFW_GAMEPAD_BUTTON_LAST}).
     */
    public static final int GAMEPAD_BUTTON_COUNT = GLFW.GLFW_GAMEPAD_BUTTON_LAST + 1;

    /**
     * Number of standardized GLFW gamepad axes ({@code 0..GLFW_GAMEPAD_AXIS_LAST}).
     */
    public static final int GAMEPAD_AXIS_COUNT = GLFW.GLFW_GAMEPAD_AXIS_LAST + 1;

    private static final int MAX_TYPED_BUFFER_CHARS = 1024;
    private static final float DEFAULT_GAMEPAD_DEADZONE = 0.15f;
    private static final long DOUBLE_CLICK_WINDOW_NANOS = 350_000_000L;
    private static final double DOUBLE_CLICK_RADIUS_PX = 6.0;

    /**
     * Sub-frame input event stamped for deterministic ordering and telemetry ({@code #0675}).
     *
     * @param kind           event category ({@code "KEY"}, {@code "MOUSE"}, {@code "GAMEPAD"})
     * @param code           key/button/slot code
     * @param action         GLFW action code
     * @param timestampNanos monotonic timestamp in nanoseconds
     */
    public record TimestampedInputEvent(String kind, int code, int action, long timestampNanos) {
    }

    /**
     * Callback receiving gamepad hot-plug connect/disconnect notifications ({@code #0600}).
     */
    @FunctionalInterface
    public interface GamepadConnectionListener {
        /**
         * Invoked when a gamepad is connected or disconnected.
         *
         * @param joystickId slot index
         * @param connected  {@code true} when connected
         * @param deviceName resolved controller name
         */
        void onConnectionChanged(int joystickId, boolean connected, String deviceName);
    }

    private final boolean[] keysDown = new boolean[KEY_COUNT];
    private final boolean[] keysJustPressed = new boolean[KEY_COUNT];
    private final boolean[] keysJustReleased = new boolean[KEY_COUNT];

    private final boolean[] mouseButtonsDown = new boolean[MOUSE_BUTTON_COUNT];
    private final boolean[] mouseButtonsJustPressed = new boolean[MOUSE_BUTTON_COUNT];
    private final boolean[] mouseButtonsJustReleased = new boolean[MOUSE_BUTTON_COUNT];

    private final boolean[] gamepadConnected = new boolean[MAX_JOYSTICKS];
    private final boolean[][] gamepadButtonsDown = new boolean[MAX_JOYSTICKS][GAMEPAD_BUTTON_COUNT];
    private final boolean[][] gamepadButtonsJustPressed = new boolean[MAX_JOYSTICKS][GAMEPAD_BUTTON_COUNT];
    private final boolean[][] gamepadButtonsJustReleased = new boolean[MAX_JOYSTICKS][GAMEPAD_BUTTON_COUNT];
    private final float[][] gamepadAxes = new float[MAX_JOYSTICKS][GAMEPAD_AXIS_COUNT];

    private final StringBuilder typedCharsBuffer = new StringBuilder();

    private int logicalWidth = EngineConfig.DEFAULT_LOGICAL_WIDTH;
    private int logicalHeight = EngineConfig.DEFAULT_LOGICAL_HEIGHT;
    private double viewportX = 0.0;
    private double viewportY = 0.0;
    private double viewportWidth = EngineConfig.DEFAULT_LOGICAL_WIDTH;
    private double viewportHeight = EngineConfig.DEFAULT_LOGICAL_HEIGHT;

    private double windowMouseX = 0.0;
    private double windowMouseY = 0.0;
    private double logicalMouseX = 0.0;
    private double logicalMouseY = 0.0;
    private double prevLogicalMouseX = 0.0;
    private double prevLogicalMouseY = 0.0;
    private boolean hasCursorSample = false;

    private double scrollX = 0.0;
    private double scrollY = 0.0;

    private float gamepadDeadzone = DEFAULT_GAMEPAD_DEADZONE;
    private int activeGamepadId = -1;
    private boolean frameEnded = true;

    private final boolean[] bootSuppressedKeys = new boolean[KEY_COUNT];
    private final boolean[] mouseDoubleClicked = new boolean[MOUSE_BUTTON_COUNT];
    private final long[] lastClickNanos = new long[MOUSE_BUTTON_COUNT];
    private final double[] lastClickX = new double[MOUSE_BUTTON_COUNT];
    private final double[] lastClickY = new double[MOUSE_BUTTON_COUNT];
    private final long[] lastDebouncePressNanos = new long[MOUSE_BUTTON_COUNT];
    private int minClickIntervalMs = 0;
    private final List<TimestampedInputEvent> frameInputEvents = new ArrayList<>();
    private GamepadConnectionListener gamepadConnectionListener;
    private boolean traceEnabled = false;
    private Consumer<String> traceSink;

    private long attachedWindowHandle = MemoryUtil.NULL;
    private GLFWKeyCallback keyCallback;
    private GLFWCharCallback charCallback;
    private GLFWMouseButtonCallback mouseButtonCallback;
    private GLFWCursorPosCallback cursorPosCallback;
    private GLFWScrollCallback scrollCallback;
    private GLFWWindowFocusCallback windowFocusCallback;

    /** Creates a new {@code InputManager} with default logical viewport bounds. */
    public InputManager() {
    }

    /**
     * Attaches GLFW keyboard, character, mouse, scroll, and focus callbacks to the given window.
     * Any previously attached callbacks managed by this instance are detached first.
     *
     * @param windowHandle native GLFW window handle (must not be {@code 0L})
     */
    public void attachToWindow(long windowHandle) {
        if (windowHandle == MemoryUtil.NULL) {
            throw new IllegalArgumentException("windowHandle must not be NULL");
        }
        detachCallbacks();
        this.attachedWindowHandle = windowHandle;

        keyCallback = GLFWKeyCallback.create((window, key, scancode, action, mods) ->
                onKey(key, scancode, action, mods));
        charCallback = GLFWCharCallback.create((window, codepoint) ->
                onChar(codepoint));
        mouseButtonCallback = GLFWMouseButtonCallback.create((window, button, action, mods) ->
                onMouseButton(button, action, mods));
        cursorPosCallback = GLFWCursorPosCallback.create((window, xpos, ypos) ->
                onCursorPos(xpos, ypos));
        scrollCallback = GLFWScrollCallback.create((window, xoffset, yoffset) ->
                onScroll(xoffset, yoffset));
        windowFocusCallback = GLFWWindowFocusCallback.create((window, focused) ->
                onWindowFocus(focused));

        GLFW.glfwSetKeyCallback(windowHandle, keyCallback);
        GLFW.glfwSetCharCallback(windowHandle, charCallback);
        GLFW.glfwSetMouseButtonCallback(windowHandle, mouseButtonCallback);
        GLFW.glfwSetCursorPosCallback(windowHandle, cursorPosCallback);
        GLFW.glfwSetScrollCallback(windowHandle, scrollCallback);
        GLFW.glfwSetWindowFocusCallback(windowHandle, windowFocusCallback);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer winW = stack.mallocInt(1);
            IntBuffer winH = stack.mallocInt(1);
            GLFW.glfwGetWindowSize(windowHandle, winW, winH);
            if (winW.get(0) > 0 && winH.get(0) > 0) {
                updateLetterboxViewport(winW.get(0), winH.get(0));
            }
            DoubleBuffer cursorX = stack.mallocDouble(1);
            DoubleBuffer cursorY = stack.mallocDouble(1);
            GLFW.glfwGetCursorPos(windowHandle, cursorX, cursorY);
            onCursorPos(cursorX.get(0), cursorY.get(0));
        }
    }

    /**
     * Detaches and frees any GLFW callbacks currently owned by this {@link InputManager}.
     */
    public void detachCallbacks() {
        if (attachedWindowHandle != MemoryUtil.NULL) {
            GLFW.glfwSetKeyCallback(attachedWindowHandle, null);
            GLFW.glfwSetCharCallback(attachedWindowHandle, null);
            GLFW.glfwSetMouseButtonCallback(attachedWindowHandle, null);
            GLFW.glfwSetCursorPosCallback(attachedWindowHandle, null);
            GLFW.glfwSetScrollCallback(attachedWindowHandle, null);
            GLFW.glfwSetWindowFocusCallback(attachedWindowHandle, null);
            attachedWindowHandle = MemoryUtil.NULL;
        }
        keyCallback = freeCallback(keyCallback);
        charCallback = freeCallback(charCallback);
        mouseButtonCallback = freeCallback(mouseButtonCallback);
        cursorPosCallback = freeCallback(cursorPosCallback);
        scrollCallback = freeCallback(scrollCallback);
        windowFocusCallback = freeCallback(windowFocusCallback);
    }

    /**
     * Releases all native GLFW callback resources.
     */
    public void dispose() {
        detachCallbacks();
        reset();
    }

    /**
     * Begins a new input frame, clearing stale edge transitions if {@link #endFrame()} was not
     * called on the previous iteration and polling connected GLFW gamepads when attached.
     */
    public void beginFrame() {
        if (!frameEnded) {
            clearTransientState();
        }
        frameEnded = false;
        if (attachedWindowHandle != MemoryUtil.NULL) {
            pollGamepads();
        }
    }

    /**
     * Completes the current input frame by clearing edge-triggered press/release flags,
     * resetting unconsumed scroll deltas, and latching the mouse delta baseline.
     */
    public void endFrame() {
        clearTransientState();
        prevLogicalMouseX = logicalMouseX;
        prevLogicalMouseY = logicalMouseY;
        frameEnded = true;
    }

    /**
     * Polls GLFW gamepad states for all joystick slots ({@code 0..GLFW_JOYSTICK_LAST}).
     */
    public void pollGamepads() {
        int foundActive = -1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GLFWGamepadState state = GLFWGamepadState.calloc(stack);
            for (int jid = 0; jid < MAX_JOYSTICKS; jid++) {
                boolean present = GLFW.glfwJoystickPresent(jid) && GLFW.glfwJoystickIsGamepad(jid);
                if (present && GLFW.glfwGetGamepadState(jid, state)) {
                    if (foundActive == -1) {
                        foundActive = jid;
                    }
                    gamepadConnected[jid] = true;
                    for (int b = 0; b < GAMEPAD_BUTTON_COUNT; b++) {
                        boolean pressed = state.buttons(b) == GLFW.GLFW_PRESS;
                        updateGamepadButtonState(jid, b, pressed);
                    }
                    for (int a = 0; a < GAMEPAD_AXIS_COUNT; a++) {
                        gamepadAxes[jid][a] = state.axes(a);
                    }
                } else {
                    disconnectGamepadSlot(jid);
                }
            }
        }
        activeGamepadId = foundActive;
    }

    /**
     * Updates a gamepad slot directly (useful for automated headless tests).
     *
     * @param joystickId joystick index in {@code 0..MAX_JOYSTICKS-1}
     * @param connected  whether the gamepad is connected
     * @param buttons    button press states (up to {@link #GAMEPAD_BUTTON_COUNT})
     * @param axes       axis values (up to {@link #GAMEPAD_AXIS_COUNT})
     */
    public void updateGamepadState(int joystickId, boolean connected, boolean[] buttons, float[] axes) {
        if (joystickId < 0 || joystickId >= MAX_JOYSTICKS) {
            throw new IllegalArgumentException("Invalid joystickId: " + joystickId);
        }
        if (!connected) {
            disconnectGamepadSlot(joystickId);
            recomputeActiveGamepad();
            return;
        }
        gamepadConnected[joystickId] = true;
        if (buttons != null) {
            int count = Math.min(buttons.length, GAMEPAD_BUTTON_COUNT);
            for (int b = 0; b < count; b++) {
                updateGamepadButtonState(joystickId, b, buttons[b]);
            }
        }
        if (axes != null) {
            int count = Math.min(axes.length, GAMEPAD_AXIS_COUNT);
            for (int a = 0; a < count; a++) {
                gamepadAxes[joystickId][a] = axes[a];
            }
        }
        recomputeActiveGamepad();
    }

    /**
     * Processes a GLFW keyboard event.
     *
     * @param key      GLFW key code
     * @param scancode platform scancode
     * @param action   {@link GLFW#GLFW_PRESS}, {@link GLFW#GLFW_RELEASE}, or {@link GLFW#GLFW_REPEAT}
     * @param mods     modifier bitmask
     */
    public void onKey(int key, int scancode, int action, int mods) {
        if (key < 0 || key >= KEY_COUNT) {
            return;
        }
        long nowNanos = System.nanoTime();
        if (bootSuppressedKeys[key]) {
            if (action == GLFW.GLFW_RELEASE) {
                bootSuppressedKeys[key] = false;
            }
            return;
        }
        if (action == GLFW.GLFW_PRESS) {
            if (!keysDown[key]) {
                keysJustPressed[key] = true;
            }
            keysDown[key] = true;
        } else if (action == GLFW.GLFW_RELEASE) {
            if (keysDown[key]) {
                keysJustReleased[key] = true;
            }
            keysDown[key] = false;
        }
        recordEvent("KEY", key, action, nowNanos);
    }

    /**
     * Processes a Unicode character event from GLFW.
     *
     * @param codepoint Unicode code point
     */
    public void onChar(int codepoint) {
        if (!Character.isValidCodePoint(codepoint) || Character.isISOControl(codepoint)) {
            return;
        }
        if (typedCharsBuffer.length() < MAX_TYPED_BUFFER_CHARS) {
            typedCharsBuffer.appendCodePoint(codepoint);
        }
    }

    /**
     * Processes a GLFW mouse button event.
     *
     * @param button button index ({@code 0..7})
     * @param action {@link GLFW#GLFW_PRESS} or {@link GLFW#GLFW_RELEASE}
     * @param mods   modifier bitmask
     */
    public void onMouseButton(int button, int action, int mods) {
        onMouseButtonWithTimestampNanos(button, action, mods, System.nanoTime());
    }

    /**
     * Processes a GLFW mouse button event with an explicit monotonic timestamp for deterministic
     * double-click ({@code <= 350 ms}, {@code <= 6 px}) and mechanical debounce filtering
     * ({@code #0612}, {@code #0628}, {@code #0675}).
     *
     * @param button         button index ({@code 0..7})
     * @param action         {@link GLFW#GLFW_PRESS} or {@link GLFW#GLFW_RELEASE}
     * @param mods           modifier bitmask
     * @param timestampNanos monotonic event timestamp in nanoseconds
     */
    public void onMouseButtonWithTimestampNanos(int button, int action, int mods, long timestampNanos) {
        if (button < 0 || button >= MOUSE_BUTTON_COUNT) {
            return;
        }
        if (action == GLFW.GLFW_PRESS) {
            if (minClickIntervalMs > 0 && lastDebouncePressNanos[button] != 0L
                    && (timestampNanos - lastDebouncePressNanos[button]) < (minClickIntervalMs * 1_000_000L)) {
                return;
            }
            lastDebouncePressNanos[button] = timestampNanos;
            if (!mouseButtonsDown[button]) {
                mouseButtonsJustPressed[button] = true;
                long prevNanos = lastClickNanos[button];
                double dx = logicalMouseX - lastClickX[button];
                double dy = logicalMouseY - lastClickY[button];
                if (prevNanos != 0L
                        && (timestampNanos - prevNanos) <= DOUBLE_CLICK_WINDOW_NANOS
                        && ((dx * dx) + (dy * dy)) <= (DOUBLE_CLICK_RADIUS_PX * DOUBLE_CLICK_RADIUS_PX)) {
                    mouseDoubleClicked[button] = true;
                }
                lastClickNanos[button] = timestampNanos;
                lastClickX[button] = logicalMouseX;
                lastClickY[button] = logicalMouseY;
            }
            mouseButtonsDown[button] = true;
        } else if (action == GLFW.GLFW_RELEASE) {
            if (mouseButtonsDown[button]) {
                mouseButtonsJustReleased[button] = true;
            }
            mouseButtonsDown[button] = false;
        }
        recordEvent("MOUSE", button, action, timestampNanos);
    }

    /**
     * Processes a cursor position update in window coordinates and maps it into logical canvas space.
     *
     * @param windowX cursor X coordinate in window space
     * @param windowY cursor Y coordinate in window space
     */
    public void onCursorPos(double windowX, double windowY) {
        this.windowMouseX = windowX;
        this.windowMouseY = windowY;
        recomputeLogicalMouse();
        if (!hasCursorSample) {
            prevLogicalMouseX = logicalMouseX;
            prevLogicalMouseY = logicalMouseY;
            hasCursorSample = true;
        }
    }

    /**
     * Processes a scroll wheel offset event.
     *
     * @param xOffset horizontal scroll delta
     * @param yOffset vertical scroll delta
     */
    public void onScroll(double xOffset, double yOffset) {
        this.scrollX += xOffset;
        this.scrollY += yOffset;
    }

    /**
     * Handles window focus changes, releasing stuck held keys/buttons when focus is lost.
     *
     * @param focused {@code true} if the window gained focus
     */
    public void onWindowFocus(boolean focused) {
        if (!focused) {
            Arrays.fill(keysDown, false);
            Arrays.fill(mouseButtonsDown, false);
            for (int jid = 0; jid < MAX_JOYSTICKS; jid++) {
                Arrays.fill(gamepadButtonsDown[jid], false);
            }
        }
    }

    /**
     * Returns whether the specified GLFW key is currently held down.
     *
     * @param key GLFW key constant
     * @return {@code true} if held down
     */
    public boolean isKeyDown(int key) {
        return key >= 0 && key < KEY_COUNT && keysDown[key];
    }

    /**
     * Returns whether the specified GLFW key transitioned from up to down during the current frame.
     *
     * @param key GLFW key constant
     * @return {@code true} if pressed this frame
     */
    public boolean wasKeyJustPressed(int key) {
        return key >= 0 && key < KEY_COUNT && keysJustPressed[key];
    }

    /**
     * Returns whether the specified GLFW key transitioned from down to up during the current frame.
     *
     * @param key GLFW key constant
     * @return {@code true} if released this frame
     */
    public boolean wasKeyJustReleased(int key) {
        return key >= 0 && key < KEY_COUNT && keysJustReleased[key];
    }

    /**
     * Returns whether either Left or Right Shift is currently held.
     *
     * @return {@code true} if Shift is pressed
     */
    public boolean isShiftDown() {
        return isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT) || isKeyDown(GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    /**
     * Returns whether either Left or Right Control is currently held.
     *
     * @return {@code true} if Control is pressed
     */
    public boolean isCtrlDown() {
        return isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL) || isKeyDown(GLFW.GLFW_KEY_RIGHT_CONTROL);
    }

    /**
     * Returns whether either Left or Right Alt is currently held.
     *
     * @return {@code true} if Alt is pressed
     */
    public boolean isAltDown() {
        return isKeyDown(GLFW.GLFW_KEY_LEFT_ALT) || isKeyDown(GLFW.GLFW_KEY_RIGHT_ALT);
    }

    /**
     * Returns whether either Left or Right Super (Command/Windows) is currently held.
     *
     * @return {@code true} if Super is pressed
     */
    public boolean isSuperDown() {
        return isKeyDown(GLFW.GLFW_KEY_LEFT_SUPER) || isKeyDown(GLFW.GLFW_KEY_RIGHT_SUPER);
    }

    /**
     * Drains and clears all queued Unicode characters typed since the previous drain.
     *
     * @return typed Unicode characters in arrival order (empty string if none)
     */
    public String drainTypedChars() {
        if (typedCharsBuffer.isEmpty()) {
            return "";
        }
        String result = typedCharsBuffer.toString();
        typedCharsBuffer.setLength(0);
        return result;
    }

    /**
     * Sets the logical coordinate dimensions used for mouse viewport projection (default {@code 1920x1080}).
     *
     * @param logicalWidth  logical canvas width (must be {@code > 0})
     * @param logicalHeight logical canvas height (must be {@code > 0})
     */
    public void setLogicalSize(int logicalWidth, int logicalHeight) {
        if (logicalWidth <= 0 || logicalHeight <= 0) {
            throw new IllegalArgumentException(
                    "logicalWidth and logicalHeight must be positive, got: " + logicalWidth + "x" + logicalHeight
            );
        }
        this.logicalWidth = logicalWidth;
        this.logicalHeight = logicalHeight;
        recomputeLogicalMouse();
    }

    /**
     * Sets the active viewport rectangle in window coordinates for letterbox/pillarbox mouse mapping.
     *
     * @param vpX viewport left edge in window coordinates
     * @param vpY viewport top edge in window coordinates
     * @param vpW viewport width in window coordinates
     * @param vpH viewport height in window coordinates
     */
    public void setViewport(double vpX, double vpY, double vpW, double vpH) {
        if (vpW <= 0.0 || vpH <= 0.0) {
            return;
        }
        this.viewportX = vpX;
        this.viewportY = vpY;
        this.viewportWidth = vpW;
        this.viewportHeight = vpH;
        recomputeLogicalMouse();
    }

    /**
     * Computes and applies an aspect-preserving letterbox/pillarbox viewport for the given window size.
     *
     * @param windowWidth  window width in window coordinates
     * @param windowHeight window height in window coordinates
     */
    public void updateLetterboxViewport(int windowWidth, int windowHeight) {
        if (windowWidth <= 0 || windowHeight <= 0) {
            return;
        }
        double targetAspect = (double) logicalWidth / logicalHeight;
        double windowAspect = (double) windowWidth / windowHeight;
        if (windowAspect > targetAspect) {
            double vpH = windowHeight;
            double vpW = vpH * targetAspect;
            double vpX = (windowWidth - vpW) * 0.5;
            setViewport(vpX, 0.0, vpW, vpH);
        } else {
            double vpW = windowWidth;
            double vpH = vpW / targetAspect;
            double vpY = (windowHeight - vpH) * 0.5;
            setViewport(0.0, vpY, vpW, vpH);
        }
    }

    /**
     * Maps a window-space X coordinate into logical canvas coordinates using the active viewport.
     *
     * @param windowX X coordinate in window space
     * @return corresponding logical X coordinate
     */
    public double windowToLogicalX(double windowX) {
        if (viewportWidth <= 0.0) {
            return 0.0;
        }
        return ((windowX - viewportX) / viewportWidth) * logicalWidth;
    }

    /**
     * Maps a window-space Y coordinate into logical canvas coordinates using the active viewport.
     *
     * @param windowY Y coordinate in window space
     * @return corresponding logical Y coordinate
     */
    public double windowToLogicalY(double windowY) {
        if (viewportHeight <= 0.0) {
            return 0.0;
        }
        return ((windowY - viewportY) / viewportHeight) * logicalHeight;
    }

    /**
     * Returns the raw window-space cursor X coordinate.
     *
     * @return window mouse X
     */
    public double getWindowMouseX() {
        return windowMouseX;
    }

    /**
     * Returns the raw window-space cursor Y coordinate.
     *
     * @return window mouse Y
     */
    public double getWindowMouseY() {
        return windowMouseY;
    }

    /**
     * Returns the cursor X coordinate mapped into logical canvas coordinates ({@code 0..logicalWidth} inside viewport).
     *
     * @return logical mouse X
     */
    public double getLogicalMouseX() {
        return logicalMouseX;
    }

    /**
     * Returns the cursor Y coordinate mapped into logical canvas coordinates
     * ({@code 0..logicalHeight} inside viewport).
     *
     * @return logical mouse Y
     */
    public double getLogicalMouseY() {
        return logicalMouseY;
    }

    /**
     * Returns the logical mouse X coordinate clamped to {@code [0, logicalWidth]}.
     *
     * @return clamped logical mouse X
     */
    public double getClampedLogicalMouseX() {
        return Math.clamp(logicalMouseX, 0.0, logicalWidth);
    }

    /**
     * Returns the logical mouse Y coordinate clamped to {@code [0, logicalHeight]}.
     *
     * @return clamped logical mouse Y
     */
    public double getClampedLogicalMouseY() {
        return Math.clamp(logicalMouseY, 0.0, logicalHeight);
    }

    /**
     * Returns logical cursor movement along X since the previous frame.
     *
     * @return logical mouse delta X
     */
    public double getMouseDeltaX() {
        return logicalMouseX - prevLogicalMouseX;
    }

    /**
     * Returns logical cursor movement along Y since the previous frame.
     *
     * @return logical mouse delta Y
     */
    public double getMouseDeltaY() {
        return logicalMouseY - prevLogicalMouseY;
    }

    /**
     * Returns whether the cursor is currently inside the active letterboxed canvas viewport.
     *
     * @return {@code true} if inside {@code [0, logicalWidth] x [0, logicalHeight]}
     */
    public boolean isMouseInsideViewport() {
        return logicalMouseX >= 0.0 && logicalMouseX <= logicalWidth
                && logicalMouseY >= 0.0 && logicalMouseY <= logicalHeight;
    }

    /**
     * Returns whether the specified mouse button ({@code 0..7}) is currently held down.
     *
     * @param button mouse button index ({@code 0..7})
     * @return {@code true} if held down
     */
    public boolean isMouseButtonDown(int button) {
        return button >= 0 && button < MOUSE_BUTTON_COUNT && mouseButtonsDown[button];
    }

    /**
     * Returns whether the specified mouse button ({@code 0..7}) was pressed during this frame.
     *
     * @param button mouse button index ({@code 0..7})
     * @return {@code true} if pressed this frame
     */
    public boolean wasMouseButtonJustPressed(int button) {
        return button >= 0 && button < MOUSE_BUTTON_COUNT && mouseButtonsJustPressed[button];
    }

    /**
     * Returns whether the specified mouse button ({@code 0..7}) was released during this frame.
     *
     * @param button mouse button index ({@code 0..7})
     * @return {@code true} if released this frame
     */
    public boolean wasMouseButtonJustReleased(int button) {
        return button >= 0 && button < MOUSE_BUTTON_COUNT && mouseButtonsJustReleased[button];
    }

    /**
     * Returns the accumulated horizontal scroll delta without consuming it.
     *
     * @return horizontal scroll delta
     */
    public double getScrollX() {
        return scrollX;
    }

    /**
     * Returns the accumulated vertical scroll delta without consuming it.
     *
     * @return vertical scroll delta
     */
    public double getScrollY() {
        return scrollY;
    }

    /**
     * Returns and resets the accumulated horizontal scroll delta.
     *
     * @return consumed horizontal scroll delta
     */
    public double consumeScrollX() {
        double value = scrollX;
        scrollX = 0.0;
        return value;
    }

    /**
     * Returns and resets the accumulated vertical scroll delta.
     *
     * @return consumed vertical scroll delta
     */
    public double consumeScrollY() {
        double value = scrollY;
        scrollY = 0.0;
        return value;
    }

    /**
     * Returns whether at least one GLFW-mapped gamepad is currently connected.
     *
     * @return {@code true} if a gamepad is active
     */
    public boolean isGamepadConnected() {
        return activeGamepadId >= 0;
    }

    /**
     * Returns whether the specified joystick slot is connected as a recognized gamepad.
     *
     * @param joystickId joystick slot index ({@code 0..15})
     * @return {@code true} if connected
     */
    public boolean isGamepadConnected(int joystickId) {
        return joystickId >= 0 && joystickId < MAX_JOYSTICKS && gamepadConnected[joystickId];
    }

    /**
     * Returns the lowest connected gamepad joystick index, or {@code -1} if none is connected.
     *
     * @return active gamepad index or {@code -1}
     */
    public int getActiveGamepadId() {
        return activeGamepadId;
    }

    /**
     * Returns whether a gamepad button is held down on the active gamepad.
     *
     * @param button GLFW gamepad button constant ({@code 0..14})
     * @return {@code true} if held down
     */
    public boolean isGamepadButtonDown(int button) {
        return activeGamepadId >= 0 && isGamepadButtonDown(activeGamepadId, button);
    }

    /**
     * Returns whether a gamepad button is held down on the specified joystick slot.
     *
     * @param joystickId joystick slot index ({@code 0..15})
     * @param button     GLFW gamepad button constant ({@code 0..14})
     * @return {@code true} if held down
     */
    public boolean isGamepadButtonDown(int joystickId, int button) {
        return isValidGamepadSlotAndButton(joystickId, button) && gamepadButtonsDown[joystickId][button];
    }

    /**
     * Returns whether a gamepad button transitioned to pressed on the active gamepad this frame.
     *
     * @param button GLFW gamepad button constant ({@code 0..14})
     * @return {@code true} if pressed this frame
     */
    public boolean wasGamepadButtonJustPressed(int button) {
        return activeGamepadId >= 0
                && isValidGamepadSlotAndButton(activeGamepadId, button)
                && gamepadButtonsJustPressed[activeGamepadId][button];
    }

    /**
     * Returns whether a gamepad button transitioned to released on the active gamepad this frame.
     *
     * @param button GLFW gamepad button constant ({@code 0..14})
     * @return {@code true} if released this frame
     */
    public boolean wasGamepadButtonJustReleased(int button) {
        return activeGamepadId >= 0
                && isValidGamepadSlotAndButton(activeGamepadId, button)
                && gamepadButtonsJustReleased[activeGamepadId][button];
    }

    /**
     * Returns the deadzone-filtered axis value on the active gamepad.
     *
     * @param axis GLFW gamepad axis constant ({@code 0..5})
     * @return axis value in {@code [-1.0, 1.0]}, or {@code 0.0} inside deadzone or if disconnected
     */
    public float getGamepadAxis(int axis) {
        return activeGamepadId >= 0 ? getGamepadAxis(activeGamepadId, axis) : 0.0f;
    }

    /**
     * Returns the deadzone-filtered axis value on the specified gamepad slot.
     *
     * @param joystickId joystick slot index ({@code 0..15})
     * @param axis       GLFW gamepad axis constant ({@code 0..5})
     * @return axis value in {@code [-1.0, 1.0]} with deadzone applied
     */
    public float getGamepadAxis(int joystickId, int axis) {
        float raw = getRawGamepadAxis(joystickId, axis);
        return Math.abs(raw) < gamepadDeadzone ? 0.0f : raw;
    }

    /**
     * Returns the raw unfiltered axis value on the specified gamepad slot.
     *
     * @param joystickId joystick slot index ({@code 0..15})
     * @param axis       GLFW gamepad axis constant ({@code 0..5})
     * @return raw axis value or {@code 0.0f} if invalid/disconnected
     */
    public float getRawGamepadAxis(int joystickId, int axis) {
        if (joystickId < 0 || joystickId >= MAX_JOYSTICKS || !gamepadConnected[joystickId]) {
            return 0.0f;
        }
        if (axis < 0 || axis >= GAMEPAD_AXIS_COUNT) {
            return 0.0f;
        }
        return gamepadAxes[joystickId][axis];
    }

    /**
     * Sets the analog stick deadzone threshold used by {@link #getGamepadAxis(int)}.
     *
     * @param gamepadDeadzone deadzone threshold in {@code [0.0, 1.0)}
     */
    public void setGamepadDeadzone(float gamepadDeadzone) {
        if (!Float.isFinite(gamepadDeadzone) || gamepadDeadzone < 0.0f || gamepadDeadzone >= 1.0f) {
            throw new IllegalArgumentException("gamepadDeadzone must be in [0.0, 1.0), got: " + gamepadDeadzone);
        }
        this.gamepadDeadzone = gamepadDeadzone;
    }

    /**
     * Returns the configured analog stick deadzone threshold.
     *
     * @return deadzone threshold
     */
    public float getGamepadDeadzone() {
        return gamepadDeadzone;
    }

    /**
     * Resets all held keys, buttons, typed character buffer, scroll deltas, and gamepad states.
     */
    public void reset() {
        Arrays.fill(keysDown, false);
        Arrays.fill(mouseButtonsDown, false);
        for (int jid = 0; jid < MAX_JOYSTICKS; jid++) {
            disconnectGamepadSlot(jid);
        }
        activeGamepadId = -1;
        clearTransientState();
        typedCharsBuffer.setLength(0);
        hasCursorSample = false;
        prevLogicalMouseX = logicalMouseX;
        prevLogicalMouseY = logicalMouseY;
        frameEnded = true;
    }

    private void clearTransientState() {
        Arrays.fill(keysJustPressed, false);
        Arrays.fill(keysJustReleased, false);
        Arrays.fill(mouseButtonsJustPressed, false);
        Arrays.fill(mouseButtonsJustReleased, false);
        Arrays.fill(mouseDoubleClicked, false);
        for (int jid = 0; jid < MAX_JOYSTICKS; jid++) {
            Arrays.fill(gamepadButtonsJustPressed[jid], false);
            Arrays.fill(gamepadButtonsJustReleased[jid], false);
        }
        frameInputEvents.clear();
        scrollX = 0.0;
        scrollY = 0.0;
    }

    /**
     * Returns whether a double-click occurred on {@code button} in the current frame
     * (within 350 ms and 6 px radius) ({@code #0612}).
     *
     * @param button mouse button index ({@code 0..7})
     * @return {@code true} if double-clicked this frame
     */
    public boolean isMouseDoubleClicked(int button) {
        return button >= 0 && button < MOUSE_BUTTON_COUNT && mouseDoubleClicked[button];
    }

    /**
     * Sets minimum click interval for mechanical switch debounce in {@code [0, 80]} ms ({@code #0628}).
     *
     * @param minClickIntervalMs debounce interval in milliseconds
     */
    public void setMinClickIntervalMs(int minClickIntervalMs) {
        this.minClickIntervalMs = Math.clamp(minClickIntervalMs, 0, 80);
    }

    /**
     * Returns active mouse debounce threshold in milliseconds ({@code #0628}).
     *
     * @return debounce interval ms
     */
    public int getMinClickIntervalMs() {
        return minClickIntervalMs;
    }

    /**
     * Suppresses keys currently held at engine boot (such as launch-command {@code Enter})
     * until released ({@code #0656}).
     *
     * @param heldKeys optional extra keys considered held at launch
     */
    public void armBootGhostSuppression(int... heldKeys) {
        for (int k = 0; k < KEY_COUNT; k++) {
            if (keysDown[k]) {
                bootSuppressedKeys[k] = true;
                keysDown[k] = false;
                keysJustPressed[k] = false;
            }
        }
        if (heldKeys != null) {
            for (int key : heldKeys) {
                if (key >= 0 && key < KEY_COUNT) {
                    bootSuppressedKeys[key] = true;
                    keysDown[key] = false;
                    keysJustPressed[key] = false;
                }
            }
        }
    }

    /**
     * Processes a GLFW joystick hot-plug connect/disconnect notification ({@code #0600}).
     *
     * @param joystickId slot index ({@code 0..MAX_JOYSTICKS-1})
     * @param connected  {@code true} when connected, {@code false} when disconnected
     * @param deviceName optional device display name
     */
    public void onGamepadHotPlug(int joystickId, boolean connected, String deviceName) {
        if (joystickId < 0 || joystickId >= MAX_JOYSTICKS) {
            return;
        }
        if (connected) {
            gamepadConnected[joystickId] = true;
        } else {
            disconnectGamepadSlot(joystickId);
        }
        recomputeActiveGamepad();
        recordEvent("GAMEPAD", joystickId, connected ? GLFW.GLFW_CONNECTED : GLFW.GLFW_DISCONNECTED,
                System.nanoTime());
        if (gamepadConnectionListener != null) {
            String safeName = deviceName != null && !deviceName.isBlank() ? deviceName : "Controller " + joystickId;
            gamepadConnectionListener.onConnectionChanged(joystickId, connected, safeName);
        }
    }

    /**
     * Registers a listener notified on gamepad hot-plug connect/disconnect ({@code #0600}).
     *
     * @param listener connection callback (nullable to clear)
     */
    public void setGamepadConnectionListener(GamepadConnectionListener listener) {
        this.gamepadConnectionListener = listener;
    }

    /**
     * Enables or disables zero-overhead input trace logging for {@code --trace-input} ({@code #0639}).
     *
     * @param enabled {@code true} to emit timestamped traces
     * @param sink    output consumer receiving formatted lines (nullable)
     */
    public void setTraceEnabled(boolean enabled, Consumer<String> sink) {
        this.traceEnabled = enabled;
        this.traceSink = sink;
    }

    /**
     * Returns whether input tracing is currently enabled.
     *
     * @return {@code true} if tracing is enabled
     */
    public boolean isTraceEnabled() {
        return traceEnabled;
    }

    /**
     * Returns immutable list of sub-frame timestamped events recorded during the active frame ({@code #0675}).
     *
     * @return timestamped frame input events
     */
    public List<TimestampedInputEvent> getFrameInputEvents() {
        return List.copyOf(frameInputEvents);
    }

    private void recordEvent(String kind, int code, int action, long timestampNanos) {
        frameInputEvents.add(new TimestampedInputEvent(kind, code, action, timestampNanos));
        if (traceEnabled && traceSink != null) {
            traceSink.accept("[" + timestampNanos + "] " + kind + " code=" + code + " action=" + action);
        }
    }

    private void recomputeLogicalMouse() {
        this.logicalMouseX = windowToLogicalX(windowMouseX);
        this.logicalMouseY = windowToLogicalY(windowMouseY);
    }

    private void updateGamepadButtonState(int jid, int button, boolean pressed) {
        boolean wasDown = gamepadButtonsDown[jid][button];
        if (pressed && !wasDown) {
            gamepadButtonsJustPressed[jid][button] = true;
        } else if (!pressed && wasDown) {
            gamepadButtonsJustReleased[jid][button] = true;
        }
        gamepadButtonsDown[jid][button] = pressed;
    }

    private void disconnectGamepadSlot(int jid) {
        gamepadConnected[jid] = false;
        Arrays.fill(gamepadButtonsDown[jid], false);
        Arrays.fill(gamepadButtonsJustPressed[jid], false);
        Arrays.fill(gamepadButtonsJustReleased[jid], false);
        Arrays.fill(gamepadAxes[jid], 0.0f);
    }

    private void recomputeActiveGamepad() {
        for (int jid = 0; jid < MAX_JOYSTICKS; jid++) {
            if (gamepadConnected[jid]) {
                activeGamepadId = jid;
                return;
            }
        }
        activeGamepadId = -1;
    }

    private boolean isValidGamepadSlotAndButton(int joystickId, int button) {
        return joystickId >= 0 && joystickId < MAX_JOYSTICKS
                && gamepadConnected[joystickId]
                && button >= 0 && button < GAMEPAD_BUTTON_COUNT;
    }

    private static <T extends org.lwjgl.system.Callback> T freeCallback(T callback) {
        if (callback != null) {
            callback.free();
        }
        return null;
    }
}
