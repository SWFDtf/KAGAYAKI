package de.swfdtf.state;

/**
 * Dynamic characters-per-second (CPS) based auto-read pacing controller for Visual Novel dialogue.
 *
 * <p>Computes a per-line reading delay from a configurable base delay plus a per-character duration:
 * {@code targetWaitSeconds = baseDelaySeconds + (textLength * secondsPerChar)}. When enabled, the
 * remaining wait timer counts down once the dialogue typewriter has finished revealing the line,
 * and signals advancement once both the timer has elapsed and (if {@code waitForVoice} is active)
 * voice playback has completed.</p>
 *
 * <p>Thread-safe across game-loop update and settings UI threads.</p>
 */
public class AutoReadController {

    /** Default base pause in seconds after typewriter completion. */
    public static final double DEFAULT_BASE_DELAY_SECONDS = 1.5;

    /** Default additional reading time in seconds per dialogue character (25 CPS). */
    public static final double DEFAULT_SECONDS_PER_CHAR = 0.04;

    private boolean enabled;
    private double baseDelaySeconds;
    private double secondsPerChar;
    private boolean waitForVoice;

    private double remainingWaitSeconds;
    private boolean linePending;

    /**
     * Creates an {@link AutoReadController} disabled by default with standard pacing defaults
     * ({@value #DEFAULT_BASE_DELAY_SECONDS}s base delay, {@value #DEFAULT_SECONDS_PER_CHAR}s per character,
     * and waiting for voice playback enabled).
     */
    public AutoReadController() {
        this(DEFAULT_BASE_DELAY_SECONDS, DEFAULT_SECONDS_PER_CHAR, true);
    }

    /**
     * Creates an {@link AutoReadController} with explicit pacing settings (initially disabled).
     *
     * @param baseDelaySeconds minimum post-reveal pause in seconds (must be finite and {@code >= 0})
     * @param secondsPerChar   additional wait seconds per character (must be finite and {@code >= 0})
     * @param waitForVoice     whether auto-advance must wait until voice playback finishes
     */
    public AutoReadController(double baseDelaySeconds, double secondsPerChar, boolean waitForVoice) {
        this.baseDelaySeconds = validateNonNegativeFinite(baseDelaySeconds, "baseDelaySeconds");
        this.secondsPerChar = validateNonNegativeFinite(secondsPerChar, "secondsPerChar");
        this.waitForVoice = waitForVoice;
    }

    /**
     * Enables or disables auto-read mode.
     *
     * @param enabled {@code true} to enable auto-read, {@code false} to pause/disable it
     */
    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns whether auto-read mode is currently enabled.
     *
     * @return {@code true} if enabled
     */
    public synchronized boolean isEnabled() {
        return enabled;
    }

    /**
     * Toggles auto-read mode between enabled and disabled.
     *
     * @return the new enabled state after toggling
     */
    public synchronized boolean toggle() {
        this.enabled = !this.enabled;
        return this.enabled;
    }

    /**
     * Sets the minimum pause duration in seconds added to every dialogue line.
     *
     * @param seconds base delay in seconds (must be finite and {@code >= 0})
     */
    public synchronized void setBaseDelaySeconds(double seconds) {
        this.baseDelaySeconds = validateNonNegativeFinite(seconds, "baseDelaySeconds");
    }

    /**
     * Returns the configured base delay in seconds.
     *
     * @return base delay in seconds
     */
    public synchronized double getBaseDelaySeconds() {
        return baseDelaySeconds;
    }

    /**
     * Sets the additional reading duration in seconds per dialogue character.
     *
     * @param seconds seconds per character (must be finite and {@code >= 0})
     */
    public synchronized void setSecondsPerChar(double seconds) {
        this.secondsPerChar = validateNonNegativeFinite(seconds, "secondsPerChar");
    }

    /**
     * Returns the configured seconds-per-character pacing factor.
     *
     * @return seconds per character
     */
    public synchronized double getSecondsPerChar() {
        return secondsPerChar;
    }

    /**
     * Configures whether auto-read waits for active voice playback to finish before advancing.
     *
     * @param waitForVoice {@code true} to wait for voice completion
     */
    public synchronized void setWaitForVoice(boolean waitForVoice) {
        this.waitForVoice = waitForVoice;
    }

    /**
     * Returns whether auto-read waits for voice playback to finish before advancing.
     *
     * @return {@code true} if waiting for voice completion
     */
    public synchronized boolean isWaitForVoice() {
        return waitForVoice;
    }

    /**
     * Initializes the auto-read wait timer for a newly started dialogue line.
     *
     * @param textLength visible character count of the dialogue line (must be {@code >= 0})
     */
    public synchronized void startLine(int textLength) {
        if (textLength < 0) {
            throw new IllegalArgumentException("textLength must not be negative: " + textLength);
        }
        this.remainingWaitSeconds = baseDelaySeconds + (textLength * secondsPerChar);
        this.linePending = true;
    }

    /**
     * Advances the auto-read timer and determines whether the current dialogue line should auto-advance.
     *
     * <p>The wait timer only counts down while auto-read is enabled, a line is pending, and
     * {@code typewriterFinished} is {@code true}. Once the timer reaches zero and voice playback
     * is no longer blocking advancement, this method returns {@code true} once and resets the
     * pending state until the next {@link #startLine(int)} call.</p>
     *
     * @param deltaSeconds       elapsed frame time in seconds (must be finite and {@code >= 0})
     * @param typewriterFinished whether the dialogue typewriter effect has completed revealing text
     * @param voicePlaying       whether a voice clip for the current line is still playing
     * @return {@code true} once when ready to advance to the next line, {@code false} otherwise
     */
    public synchronized boolean update(double deltaSeconds, boolean typewriterFinished, boolean voicePlaying) {
        validateNonNegativeFinite(deltaSeconds, "deltaSeconds");
        if (!enabled || !linePending || !typewriterFinished) {
            return false;
        }
        remainingWaitSeconds = Math.max(0.0, remainingWaitSeconds - deltaSeconds);
        if (remainingWaitSeconds > 0.0) {
            return false;
        }
        if (waitForVoice && voicePlaying) {
            return false;
        }
        linePending = false;
        remainingWaitSeconds = 0.0;
        return true;
    }

    /**
     * Returns the remaining wait duration in seconds for the active dialogue line.
     *
     * @return remaining wait seconds ({@code >= 0.0})
     */
    public synchronized double getRemainingWaitSeconds() {
        return remainingWaitSeconds;
    }

    /**
     * Clears any pending line timer without altering user pacing preferences or enabled state.
     */
    public synchronized void reset() {
        this.remainingWaitSeconds = 0.0;
        this.linePending = false;
    }

    private static double validateNonNegativeFinite(double value, String name) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and >= 0: " + value);
        }
        return value;
    }
}
