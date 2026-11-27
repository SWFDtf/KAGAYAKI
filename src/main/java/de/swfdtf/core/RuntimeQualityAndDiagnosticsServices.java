package de.swfdtf.core;

import java.util.Locale;
import java.util.Objects;

/**
 * Runtime diagnostics, smoke-test watchdog, idle loop throttling, GameClock modal-pause handling,
 * and semantic API stability helpers ({@code #0962}, {@code #0980}, {@code #0990}, {@code #0996},
 * {@code #1013}).
 */
public final class RuntimeQualityAndDiagnosticsServices {

    private RuntimeQualityAndDiagnosticsServices() {
    }

    /**
     * Generates user-friendly fallback error reports when native GLFW/OpenGL/OpenAL driver
     * initialization fails ({@code #0962}).
     */
    public static final class NativeDriverDiagnostics {

        /**
         * Creates a {@code NativeDriverDiagnostics} helper.
         */
        public NativeDriverDiagnostics() {
        }

        /**
         * Formats an actionable OpenGL 3.3 Core diagnostic report from a raw native init failure
         * ({@code #0962}).
         *
         * @param cause low-level native error reason (nullable)
         * @return clear German/English user-facing diagnostic guidance
         */
        public static String formatGlfwFailure(String cause) {
            String detail = (cause == null || cause.isBlank()) ? "Unknown native initialization error" : cause.trim();
            return "[KAGAYAKI Native Graphics/Audio Check]\n"
                    + "Anforderung: OpenGL 3.3 Core + aktueller GPU-/Audiotreiber.\n"
                    + "Ursache: " + detail + "\n"
                    + "Tipp: Treiber aktualisieren oder im Headless-/Smoke-Modus (--smoke-test) starten.";
        }
    }

    /**
     * Deterministic frame &amp; wall-clock budget watchdog preventing CI {@code --smoke-test} runs
     * from ever hanging indefinitely ({@code #0980}).
     */
    public static final class SmokeTestWatchdog {

        private final int maxFrames;
        private final long maxDurationNanos;
        private final long startNanos;
        private int framesRecorded = 0;

        /**
         * Creates a watchdog with explicit frame and wall-clock limits ({@code #0980}).
         *
         * @param maxFrames  maximum allowed ticks ({@code > 0})
         * @param maxSeconds maximum wall-clock duration in seconds ({@code > 0.0})
         */
        public SmokeTestWatchdog(int maxFrames, double maxSeconds) {
            if (maxFrames <= 0) {
                throw new IllegalArgumentException("maxFrames must be > 0, got " + maxFrames);
            }
            if (!Double.isFinite(maxSeconds) || maxSeconds <= 0.0) {
                throw new IllegalArgumentException("maxSeconds must be positive and finite, got " + maxSeconds);
            }
            this.maxFrames = maxFrames;
            this.maxDurationNanos = Math.round(maxSeconds * 1_000_000_000.0);
            this.startNanos = System.nanoTime();
        }

        /**
         * Records one completed loop iteration and returns whether smoke execution should stop.
         *
         * @return {@code true} once frame budget or timeout budget has been reached
         */
        public boolean tickAndShouldTerminate() {
            framesRecorded++;
            return framesRecorded >= maxFrames || (System.nanoTime() - startNanos) >= maxDurationNanos;
        }

        /**
         * Returns the count of completed iterations recorded so far.
         *
         * @return recorded frame count
         */
        public int getFramesRecorded() {
            return framesRecorded;
        }
    }

    /**
     * Computes GLFW wait timeout policy keeping CPU usage low during static pause/menu reading states
     * ({@code #0990}).
     */
    public static final class IdleWaitPolicy {

        /** Recommended wait timeout in seconds for static Visual Novel menus (~60 Hz wakeup ceiling). */
        public static final double STATIC_MENU_WAIT_TIMEOUT_SECONDS = 0.016;

        /**
         * Creates an {@code IdleWaitPolicy}.
         */
        public IdleWaitPolicy() {
        }

        /**
         * Selects wait timeout passed to {@code glfwWaitEventsTimeout} ({@code #0990}).
         *
         * @param inStaticMenu {@code true} when foreground UI sits in a pause/main menu
         * @param animating    {@code true} when active transitions/tweens/physics are advancing
         * @return {@code 0.016} seconds when safely idle, otherwise {@code 0.0}
         */
        public static double computeWaitTimeoutSeconds(boolean inStaticMenu, boolean animating) {
            return (inStaticMenu && !animating) ? STATIC_MENU_WAIT_TIMEOUT_SECONDS : 0.0;
        }
    }

    /**
     * Safe OS-modal / window-minimize pause wrapper ensuring accumulated real deltas do not distort
     * animations upon resume ({@code #1013}).
     */
    public static final class GameClockPauseHelper {

        /**
         * Creates a {@code GameClockPauseHelper}.
         */
        public GameClockPauseHelper() {
        }

        /**
         * Pauses {@code clock} during {@code modalElapsedSeconds}, advances with discarded delta, and
         * unpauses cleanly without accumulating drift ({@code #1013}).
         *
         * @param clock               target simulation clock (must not be {@code null})
         * @param modalElapsedSeconds simulated wall time while modal/minimized ({@code >= 0.0})
         * @return number of steps executed during pause (always {@code 0})
         */
        public static int pauseAndDiscardDelta(GameClock clock, double modalElapsedSeconds) {
            Objects.requireNonNull(clock, "clock must not be null");
            if (!Double.isFinite(modalElapsedSeconds) || modalElapsedSeconds < 0.0) {
                throw new IllegalArgumentException("modalElapsedSeconds must be >= 0.0, got " + modalElapsedSeconds);
            }
            clock.setPaused(true);
            int steps = clock.advanceFrame(modalElapsedSeconds);
            clock.setPaused(false);
            return steps;
        }
    }

    /**
     * Enforces semantic versioning deprecation grace windows (&gt;= 1 minor version before removal)
     * ({@code #0996}).
     */
    public static final class ApiStabilityPolicy {

        /**
         * Creates an {@code ApiStabilityPolicy}.
         */
        public ApiStabilityPolicy() {
        }

        /**
         * Returns {@code true} only if {@code currentVersion} is strictly at least one minor or major
         * release ahead of {@code deprecatedSince} ({@code #0996}).
         *
         * @param deprecatedSince version string where API was first {@code @Deprecated} (e.g. {@code "1.0.0"})
         * @param currentVersion  target removal version string (e.g. {@code "1.1.0"})
         * @return {@code true} when removal respects the minimum 1-minor-version deprecation window
         */
        public static boolean isRemovalAllowed(String deprecatedSince, String currentVersion) {
            int[] dep = parseSemVer(Objects.requireNonNull(deprecatedSince, "deprecatedSince must not be null"));
            int[] cur = parseSemVer(Objects.requireNonNull(currentVersion, "currentVersion must not be null"));
            if (cur[0] > dep[0]) {
                return true;
            }
            return cur[0] == dep[0] && cur[1] >= dep[1] + 1;
        }

        private static int[] parseSemVer(String version) {
            String clean = version.trim().toLowerCase(Locale.ROOT);
            if (clean.startsWith("v")) {
                clean = clean.substring(1);
            }
            int dash = clean.indexOf('-');
            if (dash >= 0) {
                clean = clean.substring(0, dash);
            }
            String[] parts = clean.split("\\.");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Invalid semver format: " + version);
            }
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
            return new int[]{major, minor, patch};
        }
    }
}
