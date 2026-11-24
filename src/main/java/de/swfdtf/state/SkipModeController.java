package de.swfdtf.state;

import java.util.Objects;

/**
 * Controls fast dialogue skipping behavior in Visual Novel gameplay.
 *
 * <p>Supports three modes ({@link SkipMode#OFF}, {@link SkipMode#READ_ONLY}, {@link SkipMode#ALL})
 * and an optional choice-boundary safety stop ({@code stopOnChoice}) that disables skipping when
 * a player choice menu is encountered.</p>
 *
 * <p>Thread-safe across input handling, script stepping, and preferences synchronization.</p>
 */
public class SkipModeController {

    /**
     * Dialogue skip strategy modes.
     */
    public enum SkipMode {
        /** Skipping is inactive. */
        OFF,
        /** Only previously read dialogue lines are skipped. */
        READ_ONLY,
        /** Both read and unread dialogue lines are skipped. */
        ALL
    }

    private SkipMode mode;
    private boolean stopOnChoice;

    /**
     * Creates a {@link SkipModeController} initialized to {@link SkipMode#OFF} with
     * {@code stopOnChoice} enabled.
     */
    public SkipModeController() {
        this(SkipMode.OFF, true);
    }

    /**
     * Creates a {@link SkipModeController} with explicit initial mode and choice-stop setting.
     *
     * @param initialMode  initial skip mode (must not be {@code null})
     * @param stopOnChoice whether encountering a choice disables active skipping
     */
    public SkipModeController(SkipMode initialMode, boolean stopOnChoice) {
        this.mode = Objects.requireNonNull(initialMode, "initialMode must not be null");
        this.stopOnChoice = stopOnChoice;
    }

    /**
     * Sets the active dialogue skip mode.
     *
     * @param mode target skip mode (must not be {@code null})
     */
    public synchronized void setMode(SkipMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
    }

    /**
     * Returns the current dialogue skip mode.
     *
     * @return current {@link SkipMode}
     */
    public synchronized SkipMode getMode() {
        return mode;
    }

    /**
     * Cycles through skip modes in order: {@link SkipMode#OFF} {@code ->} {@link SkipMode#READ_ONLY}
     * {@code ->} {@link SkipMode#ALL} {@code ->} {@link SkipMode#OFF}.
     *
     * @return the newly active {@link SkipMode}
     */
    public synchronized SkipMode cycleMode() {
        this.mode = switch (this.mode) {
            case OFF -> SkipMode.READ_ONLY;
            case READ_ONLY -> SkipMode.ALL;
            case ALL -> SkipMode.OFF;
        };
        return this.mode;
    }

    /**
     * Configures whether encountering a narrative choice disables active skipping.
     *
     * @param stopOnChoice {@code true} to stop skipping at choices, {@code false} to continue skipping after choices
     */
    public synchronized void setStopOnChoice(boolean stopOnChoice) {
        this.stopOnChoice = stopOnChoice;
    }

    /**
     * Returns whether encountering a narrative choice disables active skipping.
     *
     * @return {@code true} if skipping stops on choices
     */
    public synchronized boolean isStopOnChoice() {
        return stopOnChoice;
    }

    /**
     * Determines whether a dialogue line should be skipped under the current skip mode.
     *
     * @param alreadyRead {@code true} if the line was previously marked as read
     * @return {@code true} if the line should be skipped immediately
     */
    public synchronized boolean shouldSkipLine(boolean alreadyRead) {
        return switch (mode) {
            case OFF -> false;
            case READ_ONLY -> alreadyRead;
            case ALL -> true;
        };
    }

    /**
     * Notifies the controller that execution reached a player choice menu.
     *
     * <p>When {@code stopOnChoice} is enabled, skipping is disabled ({@link SkipMode#OFF}).</p>
     */
    public synchronized void onChoiceEncountered() {
        if (stopOnChoice) {
            this.mode = SkipMode.OFF;
        }
    }

    /**
     * Returns whether dialogue skipping is currently active (i.e., mode is not {@link SkipMode#OFF}).
     *
     * @return {@code true} if skipping is active
     */
    public synchronized boolean isSkippingActive() {
        return mode != SkipMode.OFF;
    }
}
