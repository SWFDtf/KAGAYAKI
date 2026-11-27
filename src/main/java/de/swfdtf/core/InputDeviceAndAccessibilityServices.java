package de.swfdtf.core;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Input device buffering, UI key-repeat timing, touch/pen gestures, one-hand accessibility profiles,
 * physical keyboard scancode layout mapping, OS shortcut translation, gamepad hardware profiles,
 * cursor/focus lifecycle management, and clipboard abstraction
 * ({@code #0601}, {@code #0604}–{@code #0615}, {@code #0619}, {@code #0620}, {@code #0623},
 * {@code #0627}, {@code #0630}–{@code #0632}, {@code #0635}, {@code #0640}, {@code #0644},
 * {@code #0646}, {@code #0647}, {@code #0660}, {@code #0662}, {@code #0668}, {@code #0669},
 * {@code #0671}, {@code #0672}, {@code #0674}).
 */
public final class InputDeviceAndAccessibilityServices {

    private InputDeviceAndAccessibilityServices() {
    }

    /**
     * Action buffering queue retaining recent jump/menu button presses for 120 ms so inputs pressed
     * right before landing or screen activation trigger cleanly ({@code #0604}).
     */
    public static final class ActionInputBuffer {

        private record BufferedAction(String action, double remainingSeconds) {
        }

        /** Default action buffer lifetime (120 ms). */
        public static final double DEFAULT_BUFFER_WINDOW_SECONDS = 0.120;

        private final double bufferWindowSeconds;
        private final Deque<BufferedAction> buffered = new ArrayDeque<>();

        /**
         * Creates an {@code ActionInputBuffer} with the default 120 ms window.
         */
        public ActionInputBuffer() {
            this(DEFAULT_BUFFER_WINDOW_SECONDS);
        }

        /**
         * Creates an {@code ActionInputBuffer} with custom retention duration.
         *
         * @param bufferWindowSeconds positive buffer window in seconds
         */
        public ActionInputBuffer(double bufferWindowSeconds) {
            if (!Double.isFinite(bufferWindowSeconds) || bufferWindowSeconds <= 0.0) {
                throw new IllegalArgumentException("bufferWindowSeconds must be positive");
            }
            this.bufferWindowSeconds = bufferWindowSeconds;
        }

        /**
         * Enqueues {@code action} with full buffer lifetime.
         *
         * @param action action identifier (e.g. {@code "jump"}, {@code "confirm"})
         */
        public synchronized void enqueue(String action) {
            Objects.requireNonNull(action, "action must not be null");
            buffered.addLast(new BufferedAction(action, bufferWindowSeconds));
        }

        /**
         * Advances timers by {@code dtSeconds} and expires elapsed actions.
         *
         * @param dtSeconds delta time in seconds
         */
        public synchronized void update(double dtSeconds) {
            if (dtSeconds <= 0.0 || buffered.isEmpty()) {
                return;
            }
            int count = buffered.size();
            for (int i = 0; i < count; i++) {
                BufferedAction item = buffered.removeFirst();
                double left = item.remainingSeconds() - dtSeconds;
                if (left > 0.0) {
                    buffered.addLast(new BufferedAction(item.action(), left));
                }
            }
        }

        /**
         * Consumes {@code action} immediately when present in the active buffer window.
         *
         * @param action action identifier
         * @return {@code true} if buffered action was consumed
         */
        public synchronized boolean consume(String action) {
            for (BufferedAction item : buffered) {
                if (item.action().equals(action)) {
                    buffered.remove(item);
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Keyboard/D-Pad hold-to-repeat timer generating an initial press at t=0, a 400 ms initial delay,
     * followed by steady 80 ms repeat pulses ({@code #0605}).
     */
    public static final class KeyRepeatController {

        /** Default delay before repeat starts (400 ms). */
        public static final double DEFAULT_INITIAL_DELAY_SEC = 0.400;

        /** Default interval between repeat pulses (80 ms). */
        public static final double DEFAULT_REPEAT_INTERVAL_SEC = 0.080;

        private final double initialDelaySeconds;
        private final double repeatIntervalSeconds;
        private boolean held = false;
        private boolean initialFired = false;
        private double elapsedSinceInitial = 0.0;
        private double repeatAccumulator = 0.0;

        /**
         * Creates a {@code KeyRepeatController} with 400 ms initial delay and 80 ms interval.
         */
        public KeyRepeatController() {
            this(DEFAULT_INITIAL_DELAY_SEC, DEFAULT_REPEAT_INTERVAL_SEC);
        }

        /**
         * Creates a {@code KeyRepeatController} with custom timings.
         *
         * @param initialDelaySeconds   delay before first repeat in seconds
         * @param repeatIntervalSeconds repeat period in seconds
         */
        public KeyRepeatController(double initialDelaySeconds, double repeatIntervalSeconds) {
            if (initialDelaySeconds <= 0.0 || repeatIntervalSeconds <= 0.0) {
                throw new IllegalArgumentException("delays must be positive");
            }
            this.initialDelaySeconds = initialDelaySeconds;
            this.repeatIntervalSeconds = repeatIntervalSeconds;
        }

        /**
         * Advances key-repeat state by {@code dtSeconds} and returns number of triggers fired this frame.
         *
         * @param isDown    whether the directional key/button is held
         * @param dtSeconds delta time in seconds
         * @return number of navigation trigger pulses emitted this frame
         */
        public int update(boolean isDown, double dtSeconds) {
            if (!isDown) {
                held = false;
                initialFired = false;
                elapsedSinceInitial = 0.0;
                repeatAccumulator = 0.0;
                return 0;
            }
            int triggers = 0;
            if (!held) {
                held = true;
                initialFired = false;
                elapsedSinceInitial = 0.0;
                repeatAccumulator = 0.0;
                return 1;
            }
            if (dtSeconds <= 0.0) {
                return 0;
            }
            elapsedSinceInitial += dtSeconds;
            if (!initialFired) {
                if (elapsedSinceInitial >= initialDelaySeconds) {
                    initialFired = true;
                    repeatAccumulator = elapsedSinceInitial - initialDelaySeconds;
                    triggers++;
                    while (repeatAccumulator >= repeatIntervalSeconds) {
                        repeatAccumulator -= repeatIntervalSeconds;
                        triggers++;
                    }
                }
            } else {
                repeatAccumulator += dtSeconds;
                while (repeatAccumulator >= repeatIntervalSeconds) {
                    repeatAccumulator -= repeatIntervalSeconds;
                    triggers++;
                }
            }
            return triggers;
        }
    }

    /**
     * Unified pointer event supporting mouse, touch screen, and pen styli ({@code #0606}, {@code #0671}).
     *
     * @param pointerId      unique finger/stylus/mouse ID
     * @param kind           pointer kind ({@code "MOUSE"}, {@code "TOUCH"}, {@code "PEN"})
     * @param phase          lifecycle phase ({@code "DOWN"}, {@code "MOVE"}, {@code "UP"})
     * @param logicalX       logical X coordinate in {@code [0, 1920]}
     * @param logicalY       logical Y coordinate in {@code [0, 1080]}
     * @param timestampMs    monotonic timestamp in milliseconds
     */
    public record PointerEvent(
            int pointerId,
            String kind,
            String phase,
            double logicalX,
            double logicalY,
            long timestampMs
    ) {
    }

    /**
     * Recognized high-level touch gesture output ({@code #0606}, {@code #0671}).
     */
    public enum TouchGesture {
        /** No gesture completed. */
        NONE,
        /** Primary single-finger tap / dialogue advance. */
        TAP_ADVANCE,
        /** Two-finger tap mapped cleanly to right-click / menu open. */
        TWO_FINGER_MENU,
        /** Horizontal swipe left (previous page). */
        SWIPE_LEFT,
        /** Horizontal swipe right (next page). */
        SWIPE_RIGHT
    }

    /**
     * Recognizes 2-finger menu taps, horizontal page swipes (&gt;= 80 px within 400 ms), and evaluates
     * optional on-screen touch WASD overlay visibility ({@code #0606}, {@code #0607}, {@code #0671}).
     */
    public static final class TouchGestureRecognizer {

        private record ActiveTouch(double startX, double startY, long startMs, double lastX, double lastY) {
        }

        private final Map<Integer, ActiveTouch> active = new LinkedHashMap<>();
        private boolean twoFingerWindow = false;
        private long twoFingerStartMs = 0L;

        /**
         * Creates a new {@code TouchGestureRecognizer}.
         */
        public TouchGestureRecognizer() {
        }

        /**
         * Feeds a {@link PointerEvent} into the recognizer and returns any completed gesture.
         *
         * @param event pointer event
         * @return recognized {@link TouchGesture}
         */
        public synchronized TouchGesture onPointerEvent(PointerEvent event) {
            Objects.requireNonNull(event, "event must not be null");
            if ("DOWN".equals(event.phase())) {
                active.put(event.pointerId(), new ActiveTouch(
                        event.logicalX(), event.logicalY(), event.timestampMs(),
                        event.logicalX(), event.logicalY()));
                if (active.size() >= 2 && !twoFingerWindow) {
                    twoFingerWindow = true;
                    twoFingerStartMs = event.timestampMs();
                }
                return TouchGesture.NONE;
            }
            if ("MOVE".equals(event.phase())) {
                ActiveTouch prev = active.get(event.pointerId());
                if (prev != null) {
                    active.put(event.pointerId(), new ActiveTouch(
                            prev.startX(), prev.startY(), prev.startMs(),
                            event.logicalX(), event.logicalY()));
                }
                return TouchGesture.NONE;
            }
            if ("UP".equals(event.phase())) {
                ActiveTouch prev = active.remove(event.pointerId());
                if (twoFingerWindow && active.isEmpty()) {
                    boolean quickTwoFingerTap = (event.timestampMs() - twoFingerStartMs) <= 400L;
                    twoFingerWindow = false;
                    if (quickTwoFingerTap) {
                        return TouchGesture.TWO_FINGER_MENU;
                    }
                }
                if (prev != null) {
                    double dx = event.logicalX() - prev.startX();
                    double dy = event.logicalY() - prev.startY();
                    long dt = event.timestampMs() - prev.startMs();
                    if (dt <= 400L && Math.abs(dx) >= 80.0 && Math.abs(dx) > Math.abs(dy) * 1.5) {
                        return dx > 0.0 ? TouchGesture.SWIPE_RIGHT : TouchGesture.SWIPE_LEFT;
                    }
                    if (Math.hypot(dx, dy) < 20.0 && !twoFingerWindow) {
                        return TouchGesture.TAP_ADVANCE;
                    }
                }
            }
            return TouchGesture.NONE;
        }

        /**
         * Determines whether the virtual touch D-Pad + Jump overlay should be displayed ({@code #0607}).
         *
         * @param touchControlsEnabled preference flag
         * @param hasActiveWasd        whether {@code wasd_init} arena control is active
         * @return {@code true} only when both touch controls are enabled and WASD is active
         */
        public static boolean isTouchOverlayVisible(boolean touchControlsEnabled, boolean hasActiveWasd) {
            return touchControlsEnabled && hasActiveWasd;
        }
    }

    /**
     * One-handed accessibility control profiles ({@code #0608}).
     */
    public enum OneHandControlPreset {
        /** Standard two-handed keyboard + mouse layout. */
        STANDARD,
        /** Mouse-only layout exposing full navigation via mouse buttons and QuickMenu. */
        MOUSE_ONLY,
        /** Left-hand-only layout mapping advance, confirm, skip, and WASD around left keyboard cluster. */
        LEFT_HAND_KEYBOARD;

        /**
         * Returns default key mapping for this one-hand preset.
         *
         * @return immutable map of action name to GLFW key code
         */
        public Map<String, Integer> buildActionKeyMap() {
            if (this == LEFT_HAND_KEYBOARD) {
                Map<String, Integer> left = new LinkedHashMap<>();
                left.put("move_up", GLFW.GLFW_KEY_W);
                left.put("move_left", GLFW.GLFW_KEY_A);
                left.put("move_down", GLFW.GLFW_KEY_S);
                left.put("move_right", GLFW.GLFW_KEY_D);
                left.put("advance", GLFW.GLFW_KEY_SPACE);
                left.put("skip", GLFW.GLFW_KEY_LEFT_CONTROL);
                left.put("menu", GLFW.GLFW_KEY_TAB);
                left.put("quicksave", GLFW.GLFW_KEY_Q);
                left.put("quickload", GLFW.GLFW_KEY_E);
                return Map.copyOf(left);
            }
            return Map.of(
                    "move_up", GLFW.GLFW_KEY_W,
                    "move_left", GLFW.GLFW_KEY_A,
                    "move_down", GLFW.GLFW_KEY_S,
                    "move_right", GLFW.GLFW_KEY_D,
                    "advance", GLFW.GLFW_KEY_SPACE,
                    "skip", GLFW.GLFW_KEY_RIGHT_CONTROL
            );
        }
    }

    /**
     * Handles accessibility hold-vs-mash, toggle-vs-hold skip/sprint, 0.7 s hold-to-confirm bars,
     * 300 ms gamepad skip delay, and hold-key paced auto-advance ({@code #0609}, {@code #0627},
     * {@code #0630}, {@code #0660}, {@code #0668}).
     */
    public static final class AccessibilityHoldController {

        private boolean toggleSkipActive = false;
        private double holdConfirmProgress = 0.0;
        private double mashFillProgress = 0.0;
        private double gamepadSkipHoldSec = 0.0;
        private double holdAdvanceTimerSec = 0.0;

        /**
         * Creates an {@code AccessibilityHoldController}.
         */
        public AccessibilityHoldController() {
        }

        /**
         * Updates QTE/mash progress under either normal rapid tapping or accessibility hold ({@code #0609}).
         *
         * @param simplifyHoldInputs preference flag replacing mashing with hold
         * @param isHeld             whether action button is currently held
         * @param justPressed        whether action button transitioned to pressed this frame
         * @param dtSeconds          frame delta seconds
         * @return normalized completion progress in {@code [0.0, 1.0]}
         */
        public synchronized double updateMashOrHold(
                boolean simplifyHoldInputs,
                boolean isHeld,
                boolean justPressed,
                double dtSeconds
        ) {
            if (simplifyHoldInputs) {
                if (isHeld && dtSeconds > 0.0) {
                    mashFillProgress = Math.min(1.0, mashFillProgress + dtSeconds * 0.9);
                } else if (!isHeld && dtSeconds > 0.0) {
                    mashFillProgress = Math.max(0.0, mashFillProgress - dtSeconds * 0.4);
                }
            } else {
                if (justPressed) {
                    mashFillProgress = Math.min(1.0, mashFillProgress + 0.25);
                } else if (dtSeconds > 0.0) {
                    mashFillProgress = Math.max(0.0, mashFillProgress - dtSeconds * 0.15);
                }
            }
            return mashFillProgress;
        }

        /**
         * Evaluates hold-vs-toggle skip state ({@code #0603}, {@code #0627}).
         *
         * @param toggleMode  whether toggle mode is active in preferences
         * @param held        whether skip key/bumper is held
         * @param justPressed whether skip key/bumper was pressed this frame
         * @return {@code true} while skip mode is active
         */
        public synchronized boolean updateSkipState(boolean toggleMode, boolean held, boolean justPressed) {
            if (!toggleMode) {
                toggleSkipActive = held;
                return held;
            }
            if (justPressed) {
                toggleSkipActive = !toggleSkipActive;
            }
            return toggleSkipActive;
        }

        /**
         * Evaluates gamepad skip activation with a mandatory 300 ms hold threshold ({@code #0660}).
         *
         * @param triggerHeld whether gamepad skip trigger/bumper is held
         * @param dtSeconds   frame delta seconds
         * @return {@code true} only after held for {@code >= 0.300 s}
         */
        public synchronized boolean updateGamepadSkipWithDelay(boolean triggerHeld, double dtSeconds) {
            if (!triggerHeld) {
                gamepadSkipHoldSec = 0.0;
                return false;
            }
            if (dtSeconds > 0.0) {
                gamepadSkipHoldSec += dtSeconds;
            }
            return gamepadSkipHoldSec >= 0.300;
        }

        /**
         * Updates destructive action confirmation hold bar (0.7 s required; cancels cleanly on early release)
         * ({@code #0630}).
         *
         * @param held      whether confirm button is held
         * @param dtSeconds frame delta seconds
         * @return {@code true} only at the frame hold reaches 100%
         */
        public synchronized boolean updateHoldToConfirm(boolean held, double dtSeconds) {
            if (!held) {
                holdConfirmProgress = 0.0;
                return false;
            }
            if (dtSeconds > 0.0) {
                holdConfirmProgress = Math.min(1.0, holdConfirmProgress + (dtSeconds / 0.70));
            }
            if (holdConfirmProgress >= 1.0) {
                holdConfirmProgress = 0.0;
                return true;
            }
            return false;
        }

        /**
         * Returns current normalized hold-to-confirm bar progress in {@code [0, 1]}.
         *
         * @return progress fraction
         */
        public synchronized double getHoldConfirmProgress() {
            return holdConfirmProgress;
        }

        /**
         * Advances accessible paced auto-advance while Advance key is held, paced by word count ({@code #0668}).
         *
         * @param held          whether Space/mouse hold auto-advance is active
         * @param wordCount     word count of visible line
         * @param wordsPerSec   configured reading pace (e.g. {@code 4.0})
         * @param dtSeconds     frame delta seconds
         * @return {@code true} when reading interval elapses and dialogue should advance once
         */
        public synchronized boolean updateHoldKeyAutoAdvance(
                boolean held,
                int wordCount,
                double wordsPerSec,
                double dtSeconds
        ) {
            if (!held) {
                holdAdvanceTimerSec = 0.0;
                return false;
            }
            double safeWords = Math.max(1, wordCount);
            double rate = Math.max(0.5, wordsPerSec);
            double threshold = safeWords / rate;
            if (dtSeconds > 0.0) {
                holdAdvanceTimerSec += dtSeconds;
            }
            if (holdAdvanceTimerSec >= threshold) {
                holdAdvanceTimerSec -= threshold;
                return true;
            }
            return false;
        }
    }

    /**
     * Scancode-aware logical keyboard layout profiles mapping physical WASD positions across QWERTY,
     * QWERTZ, AZERTY ({@code ZQSD}), and DVORAK ({@code ,AOE}) ({@code #0611}) plus platform shortcuts
     * ({@code #0619}, {@code #0644}).
     */
    public static final class KeyboardLayoutScancodeMapper {

        /** Supported physical layout presets. */
        public enum Layout {
            /** Standard US QWERTY layout. */
            QWERTY,
            /** German QWERTZ layout. */
            QWERTZ,
            /** French AZERTY layout (ZQSD). */
            AZERTY,
            /** Dvorak layout (,AOE). */
            DVORAK
        }

        /**
         * Creates a {@code KeyboardLayoutScancodeMapper}.
         */
        public KeyboardLayoutScancodeMapper() {
        }

        /**
         * Returns default movement keys (up, left, down, right) for the given keyboard layout ({@code #0611}).
         *
         * @param layout target keyboard layout
         * @return immutable map of {@code "up"}, {@code "left"}, {@code "down"}, {@code "right"} to GLFW key codes
         */
        public static Map<String, Integer> resolveDirectionalKeys(Layout layout) {
            Objects.requireNonNull(layout, "layout must not be null");
            if (layout == Layout.AZERTY) {
                return Map.of(
                        "up", GLFW.GLFW_KEY_Z,
                        "left", GLFW.GLFW_KEY_Q,
                        "down", GLFW.GLFW_KEY_S,
                        "right", GLFW.GLFW_KEY_D
                );
            }
            if (layout == Layout.DVORAK) {
                return Map.of(
                        "up", GLFW.GLFW_KEY_COMMA,
                        "left", GLFW.GLFW_KEY_A,
                        "down", GLFW.GLFW_KEY_O,
                        "right", GLFW.GLFW_KEY_E
                );
            }
            return Map.of(
                    "up", GLFW.GLFW_KEY_W,
                    "left", GLFW.GLFW_KEY_A,
                    "down", GLFW.GLFW_KEY_S,
                    "right", GLFW.GLFW_KEY_D
            );
        }

        /**
         * Checks whether the platform Studio command modifier ({@code Ctrl} or macOS {@code Super/Cmd}) is active
         * ({@code #0619}).
         *
         * @param input  input manager
         * @param osName operating system name ({@code System.getProperty("os.name")})
         * @return {@code true} if platform command modifier is pressed
         */
        public static boolean isPlatformCommandDown(InputManager input, String osName) {
            Objects.requireNonNull(input, "input must not be null");
            boolean isMac = osName != null && osName.toLowerCase(Locale.ROOT).contains("mac");
            return isMac ? (input.isSuperDown() || input.isCtrlDown()) : input.isCtrlDown();
        }

        /**
         * Verifies sticky-key friendliness: gameplay action lists stay single-key
         * without forced chords ({@code #0644}).
         *
         * @param binding action key code
         * @param requiresSimultaneousChord whether caller wants a simultaneous multi-key chord
         * @return {@code true} when compliant with single-key sticky-key accessibility
         */
        public static boolean isStickyKeyFriendly(int binding, boolean requiresSimultaneousChord) {
            return binding >= 0 && binding < InputManager.KEY_COUNT && !requiresSimultaneousChord;
        }
    }

    /**
     * Normalized 2D stick vector or trigger value.
     *
     * @param x horizontal component in {@code [-1, 1]}
     * @param y vertical component in {@code [-1, 1]}
     */
    public record StickVector(float x, float y) {
    }

    /**
     * Immutable haptic vibration effect clamped to safe motor intensities and non-blocking duration ({@code #0620}).
     *
     * @param lowFreqMotor   left heavy motor intensity in {@code [0, 1]}
     * @param highFreqMotor  right precision motor intensity in {@code [0, 1]}
     * @param durationSec    clamped effect duration in seconds in {@code [0, 5.0]}
     */
    public record RumbleEffect(float lowFreqMotor, float highFreqMotor, double durationSec) {
        /** Clamps fields safely. */
        public RumbleEffect {
            lowFreqMotor = Float.isFinite(lowFreqMotor) ? Math.clamp(lowFreqMotor, 0.0f, 1.0f) : 0.0f;
            highFreqMotor = Float.isFinite(highFreqMotor) ? Math.clamp(highFreqMotor, 0.0f, 1.0f) : 0.0f;
            durationSec = Double.isFinite(durationSec) ? Math.clamp(durationSec, 0.0, 5.0) : 0.0;
        }
    }

    /**
     * Controller family glyph sets auto-detected from USB VID/PID or device names ({@code #0672}).
     */
    public enum ControllerGlyphStyle {
        /** Microsoft Xbox buttons (A/B/X/Y). */
        XBOX,
        /** Sony DualSense/DualShock PlayStation buttons (Cross/Circle/Square/Triangle). */
        PLAYSTATION,
        /** Nintendo Switch Pro buttons (B/A/Y/X). */
        NINTENDO,
        /** Generic ABXY fallback. */
        GENERIC_ABXY
    }

    /**
     * SDL_GameControllerDB mapping registry, proportional left-stick WASD vector scaling, trigger
     * normalization {@code [-1..1] -> [0..1]}, D-Pad/Stick same-frame edge debouncing, controller
     * glyph detection, disconnect auto-pause, and clamped haptics ({@code #0601}, {@code #0610},
     * {@code #0620}, {@code #0640}, {@code #0646}, {@code #0647}, {@code #0672}).
     */
    public static final class GamepadDeviceProfiles {

        /**
         * Parsed SDL GameControllerDB entry ({@code #0610}).
         *
         * @param guid    controller GUID string
         * @param name    device display name
         * @param rawLine normalized SDL mapping line
         */
        public record SdlControllerMapping(String guid, String name, String rawLine) {
        }

        private final Map<String, SdlControllerMapping> mappingsByGuid = new LinkedHashMap<>();
        private boolean prevNavLeft = false;
        private boolean prevNavRight = false;
        private boolean prevNavUp = false;
        private boolean prevNavDown = false;

        /**
         * Creates a {@code GamepadDeviceProfiles} manager.
         */
        public GamepadDeviceProfiles() {
        }

        /**
         * Parses and registers SDL_GameControllerDB lines ({@code #0610}).
         *
         * @param dbText text containing comma-separated SDL controller mapping lines
         * @return count of parsed controller mappings
         */
        public synchronized int loadSdlMappings(String dbText) {
            if (dbText == null || dbText.isBlank()) {
                return 0;
            }
            int loaded = 0;
            for (String line : dbText.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split(",", 3);
                if (parts.length >= 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                    mappingsByGuid.put(parts[0].trim(), new SdlControllerMapping(
                            parts[0].trim(), parts[1].trim(), trimmed));
                    loaded++;
                }
            }
            return loaded;
        }

        /**
         * Looks up a loaded SDL controller mapping by GUID.
         *
         * @param guid controller GUID
         * @return matching {@link SdlControllerMapping} if present
         */
        public synchronized Optional<SdlControllerMapping> findByGuid(String guid) {
            return Optional.ofNullable(mappingsByGuid.get(guid));
        }

        /**
         * Computes proportional WASD velocity vector from GLFW left analog stick respecting radial
         * deadzone ({@code #0601}).
         *
         * @param input input manager
         * @return {@link StickVector} scaled linearly in {@code [0, 1]} magnitude (0.5 stick -&gt; 0.5 speed)
         */
        public static StickVector readProportionalLeftStick(InputManager input) {
            Objects.requireNonNull(input, "input must not be null");
            if (!input.isGamepadConnected()) {
                return new StickVector(0.0f, 0.0f);
            }
            float rawX = input.getGamepadAxis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X);
            float rawY = input.getGamepadAxis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y);
            float mag = (float) Math.hypot(rawX, rawY);
            if (mag <= 1e-5f) {
                return new StickVector(0.0f, 0.0f);
            }
            if (mag > 1.0f) {
                return new StickVector(rawX / mag, rawY / mag);
            }
            return new StickVector(rawX, rawY);
        }

        /**
         * Normalizes GLFW analog trigger axis from {@code [-1.0, 1.0]} into {@code [0.0, 1.0]} ({@code #0640}).
         *
         * @param rawTriggerAxis GLFW axis reading ({@code -1} unpressed, {@code +1} fully pressed)
         * @return normalized trigger travel in {@code [0.0, 1.0]}
         */
        public static float normalizeTrigger(float rawTriggerAxis) {
            if (!Float.isFinite(rawTriggerAxis)) {
                return 0.0f;
            }
            return Math.clamp((rawTriggerAxis + 1.0f) * 0.5f, 0.0f, 1.0f);
        }

        /**
         * Detects controller glyph style using standard USB Vendor/Product IDs or device name ({@code #0672}).
         *
         * @param vendorId   USB vendor ID (e.g. {@code 0x054C} Sony, {@code 0x045E} Microsoft, {@code 0x057E} Nintendo)
         * @param productId  USB product ID
         * @param deviceName optional device name fallback
         * @return detected {@link ControllerGlyphStyle}
         */
        public static ControllerGlyphStyle detectGlyphStyle(int vendorId, int productId, String deviceName) {
            if (vendorId == 0x054C) {
                return ControllerGlyphStyle.PLAYSTATION;
            }
            if (vendorId == 0x057E) {
                return ControllerGlyphStyle.NINTENDO;
            }
            if (vendorId == 0x045E) {
                return ControllerGlyphStyle.XBOX;
            }
            if (deviceName != null) {
                String lower = deviceName.toLowerCase(Locale.ROOT);
                if (lower.contains("dualsense") || lower.contains("dualshock") || lower.contains("playstation")) {
                    return ControllerGlyphStyle.PLAYSTATION;
                }
                if (lower.contains("switch") || lower.contains("nintendo") || lower.contains("pro controller")) {
                    return ControllerGlyphStyle.NINTENDO;
                }
                if (lower.contains("xbox")) {
                    return ControllerGlyphStyle.XBOX;
                }
            }
            return ControllerGlyphStyle.GENERIC_ABXY;
        }

        /**
         * Evaluates whether active gameplay/WASD should auto-pause upon controller disconnect ({@code #0646}).
         *
         * @param wasGamepadActiveBefore previous frame gamepad active status
         * @param isGamepadConnectedNow  current frame gamepad active status
         * @param inActionOrWasdScene    whether an interactive arena or timed scene is running
         * @return {@code true} if pause should activate immediately
         */
        public static boolean shouldAutoPauseOnDisconnect(
                boolean wasGamepadActiveBefore,
                boolean isGamepadConnectedNow,
                boolean inActionOrWasdScene
        ) {
            return inActionOrWasdScene && wasGamepadActiveBefore && !isGamepadConnectedNow;
        }

        /**
         * Combines simultaneous D-Pad and left-stick navigation inputs with edge debouncing so pressing
         * both in the same frame triggers at most ONE navigation step ({@code #0647}).
         *
         * @param dpadDown    D-pad button state
         * @param stickActive analog stick past threshold
         * @param dirIndex    0=UP, 1=DOWN, 2=LEFT, 3=RIGHT
         * @return {@code true} on rising edge only
         */
        public synchronized boolean pollCombinedDirectionEdge(boolean dpadDown, boolean stickActive, int dirIndex) {
            boolean combined = dpadDown || stickActive;
            boolean prev = switch (dirIndex) {
                case 0 -> prevNavUp;
                case 1 -> prevNavDown;
                case 2 -> prevNavLeft;
                default -> prevNavRight;
            };
            switch (dirIndex) {
                case 0 -> prevNavUp = combined;
                case 1 -> prevNavDown = combined;
                case 2 -> prevNavLeft = combined;
                default -> prevNavRight = combined;
            }
            return combined && !prev;
        }
    }

    /**
     * Manages HiDPI retina letterbox coordinates, idle cursor auto-hide on gamepad, 3D cursor lock &amp;
     * guaranteed shutdown unlock, mouse hover takeover suppression, window focus cleanup, fullscreen
     * geometry restore, and dual-window focus requests ({@code #0613}–{@code #0615}, {@code #0631},
     * {@code #0632}, {@code #0635}, {@code #0662}, {@code #0674}, {@code #0678}).
     */
    public static final class CursorAndFocusPolicy {

        /**
         * Saved windowed rectangle for clean F11/Alt+Enter fullscreen restoration ({@code #0662}).
         *
         * @param x      window X position
         * @param y      window Y position
         * @param width  window width in pixels
         * @param height window height in pixels
         */
        public record WindowBounds(int x, int y, int width, int height) {
        }

        private double secondsSinceMouseMove = 0.0;
        private boolean cursorHidden = false;
        private boolean relativeMouseLocked = false;
        private boolean fullscreen = false;
        private WindowBounds savedWindowedBounds = new WindowBounds(100, 100, 1280, 720);

        /**
         * Creates a {@code CursorAndFocusPolicy}.
         */
        public CursorAndFocusPolicy() {
        }

        /**
         * Updates cursor visibility: hides cursor after 2 s without mouse motion when using gamepad, and
         * reveals immediately once cursor delta exceeds 4 px ({@code #0614}).
         *
         * @param hideCursorOnGamepad preference toggle
         * @param mouseDeltaPx        cursor movement distance in logical px this frame
         * @param gamepadUsed         whether gamepad input occurred recently
         * @param dtSeconds           frame delta time
         * @return {@code true} if OS cursor should be hidden
         */
        public synchronized boolean updateCursorAutoHide(
                boolean hideCursorOnGamepad,
                double mouseDeltaPx,
                boolean gamepadUsed,
                double dtSeconds
        ) {
            if (!hideCursorOnGamepad) {
                cursorHidden = false;
                secondsSinceMouseMove = 0.0;
                return false;
            }
            if (mouseDeltaPx > 4.0) {
                cursorHidden = false;
                secondsSinceMouseMove = 0.0;
                return false;
            }
            if (gamepadUsed && dtSeconds > 0.0) {
                secondsSinceMouseMove += dtSeconds;
                if (secondsSinceMouseMove >= 2.0) {
                    cursorHidden = true;
                }
            }
            return cursorHidden;
        }

        /**
         * Evaluates whether mouse hover may hijack keyboard focus (only when mouse intentionally moves
         * over a widget, never when idle) ({@code #0635}).
         *
         * @param mouseDeltaPx mouse movement in pixels this frame
         * @return {@code true} if mouse cursor should take widget focus
         */
        public static boolean shouldMouseHoverTakeFocus(double mouseDeltaPx) {
            return mouseDeltaPx > 0.5;
        }

        /**
         * Configures relative 3D pointer lock, releasing immediately when menus/pause are open ({@code #0615}).
         *
         * @param inWasd3dMode   whether 3D exploration mode is active
         * @param pauseOrMenuOpen whether pause/escape modal is open
         * @return {@code true} if cursor should be disabled/locked ({@code GLFW_CURSOR_DISABLED})
         */
        public synchronized boolean updateRelativeMouseLock(boolean inWasd3dMode, boolean pauseOrMenuOpen) {
            relativeMouseLocked = inWasd3dMode && !pauseOrMenuOpen;
            return relativeMouseLocked;
        }

        /**
         * Guaranteed {@code finally}-safe release restoring normal visible OS cursor mode ({@code #0674}).
         */
        public synchronized void forceReleaseCursor() {
            relativeMouseLocked = false;
            cursorHidden = false;
        }

        /**
         * Returns whether relative 3D cursor lock is currently active.
         *
         * @return {@code true} if locked
         */
        public synchronized boolean isRelativeMouseLocked() {
            return relativeMouseLocked;
        }

        /**
         * Toggles fullscreen state while preserving windowed position and size for restore ({@code #0662}).
         *
         * @param currentBounds current window bounds when transitioning out of windowed mode
         * @return target {@link WindowBounds} when returning to windowed mode, or empty when entering fullscreen
         */
        public synchronized Optional<WindowBounds> toggleFullscreen(WindowBounds currentBounds) {
            if (!fullscreen) {
                if (currentBounds != null && currentBounds.width() > 0 && currentBounds.height() > 0) {
                    savedWindowedBounds = currentBounds;
                }
                fullscreen = true;
                return Optional.empty();
            }
            fullscreen = false;
            return Optional.of(savedWindowedBounds);
        }

        /**
         * Returns whether fullscreen mode is currently active.
         *
         * @return {@code true} if fullscreen
         */
        public synchronized boolean isFullscreen() {
            return fullscreen;
        }
    }

    /**
     * Platform clipboard abstraction without {@code I}-prefix supporting native GLFW or headless RAM mocks
     * ({@code #0669}).
     */
    public interface ClipboardBridge {
        /**
         * Reads current clipboard UTF-8 string.
         *
         * @return clipboard text (never null; empty if blank)
         */
        String getClipboardString();

        /**
         * Writes UTF-8 string into clipboard.
         *
         * @param text clipboard content
         */
        void setClipboardString(String text);
    }

    /**
     * Pure-RAM {@link ClipboardBridge} for deterministic headless tests ({@code #0669}).
     */
    public static final class InMemoryClipboardBridge implements ClipboardBridge {

        private String text = "";

        /**
         * Creates an empty {@code InMemoryClipboardBridge}.
         */
        public InMemoryClipboardBridge() {
        }

        @Override
        public synchronized String getClipboardString() {
            return text;
        }

        @Override
        public synchronized void setClipboardString(String text) {
            this.text = text != null ? text : "";
        }
    }

    /**
     * Scales pointer/look deltas in a frame-rate independent manner with Y-invert preference
     * ({@code #0622}, {@code #0623}).
     *
     * @param deltaX      horizontal movement or stick rate
     * @param deltaY      vertical movement or stick rate
     * @param sensitivity user sensitivity multiplier clamped to {@code [0.2, 3.0]}
     * @param invertY     invert Y flag
     * @param dtSeconds   frame delta time in seconds
     * @return scaled {@link StickVector}
     */
    public static StickVector scaleCameraLook(
            double deltaX,
            double deltaY,
            float sensitivity,
            boolean invertY,
            double dtSeconds
    ) {
        float sens = Float.isFinite(sensitivity) ? Math.clamp(sensitivity, 0.2f, 3.0f) : 1.0f;
        double safeDt = Math.clamp(dtSeconds, 0.0, 0.05);
        double scaledX = deltaX * sens * (safeDt > 0.0 ? safeDt * 60.0 : 1.0);
        double scaledY = deltaY * sens * (invertY ? -1.0 : 1.0) * (safeDt > 0.0 ? safeDt * 60.0 : 1.0);
        return new StickVector((float) scaledX, (float) scaledY);
    }

    /**
     * Returns immutable view list of recent sub-frame events from {@code input} safely for tooling tests.
     *
     * @param input source {@link InputManager}
     * @return list of {@link InputManager.TimestampedInputEvent}
     */
    public static List<InputManager.TimestampedInputEvent> snapshotEvents(InputManager input) {
        return new ArrayList<>(Objects.requireNonNull(input, "input must not be null").getFrameInputEvents());
    }
}
