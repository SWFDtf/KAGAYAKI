package de.swfdtf.state;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Thread-safe scheduler that triggers rotating auto-saves across {@code auto_1}..{@code auto_<maxSlots>}
 * via {@link SaveManager} based on elapsed time and/or dialogue progression steps.
 */
public final class AutoSaveScheduler {

    /** Default auto-save time threshold in seconds (5 minutes). */
    public static final double DEFAULT_INTERVAL_SECONDS = 300.0;

    /** Default number of dialogue steps before triggering an auto-save. */
    public static final int DEFAULT_DIALOGUE_STEP_INTERVAL = 25;

    private final SaveManager saveManager;
    private final Supplier<SaveSlotData> snapshotProvider;
    private final int maxSlots;
    private final double intervalSeconds;
    private final int dialogueStepInterval;

    private int nextSlotIndex;
    private int pendingStepCount;
    private double elapsedSinceLastSaveSeconds;
    private boolean enabled = true;

    /**
     * Creates an {@code AutoSaveScheduler} using {@link SaveManager#getAutoSaveSlotCount()} slots
     * and default time/dialogue-step intervals.
     *
     * @param saveManager      save manager used to persist rotating auto-save slots
     * @param snapshotProvider supplier producing the current {@link SaveSlotData} snapshot
     */
    public AutoSaveScheduler(SaveManager saveManager, Supplier<SaveSlotData> snapshotProvider) {
        this(
                saveManager,
                snapshotProvider,
                Objects.requireNonNull(saveManager, "saveManager must not be null").getAutoSaveSlotCount(),
                DEFAULT_INTERVAL_SECONDS,
                DEFAULT_DIALOGUE_STEP_INTERVAL
        );
    }

    /**
     * Creates an {@code AutoSaveScheduler} with explicit slot rotation count and trigger thresholds.
     *
     * @param saveManager          save manager used to persist rotating auto-save slots
     * @param snapshotProvider     supplier producing the current {@link SaveSlotData} snapshot
     * @param maxSlots             number of rotating slots ({@code >= 1})
     * @param intervalSeconds      elapsed seconds threshold before triggering an auto-save ({@code > 0})
     * @param dialogueStepInterval dialogue step threshold before triggering an auto-save ({@code >= 1})
     */
    public AutoSaveScheduler(
            SaveManager saveManager,
            Supplier<SaveSlotData> snapshotProvider,
            int maxSlots,
            double intervalSeconds,
            int dialogueStepInterval
    ) {
        this.saveManager = Objects.requireNonNull(saveManager, "saveManager must not be null");
        this.snapshotProvider = Objects.requireNonNull(snapshotProvider, "snapshotProvider must not be null");
        if (maxSlots < 1) {
            throw new IllegalArgumentException("maxSlots must be >= 1, got " + maxSlots);
        }
        if (!Double.isFinite(intervalSeconds) || intervalSeconds <= 0.0) {
            throw new IllegalArgumentException("intervalSeconds must be finite and > 0, got " + intervalSeconds);
        }
        if (dialogueStepInterval < 1) {
            throw new IllegalArgumentException("dialogueStepInterval must be >= 1, got " + dialogueStepInterval);
        }
        this.maxSlots = maxSlots;
        this.intervalSeconds = intervalSeconds;
        this.dialogueStepInterval = dialogueStepInterval;
    }

    /**
     * Records a single dialogue advancement step when the scheduler is enabled.
     */
    public synchronized void recordDialogueStep() {
        if (!enabled) {
            return;
        }
        pendingStepCount++;
    }

    /**
     * Advances the internal timer by {@code deltaSeconds} and triggers a rotating auto-save
     * if either the elapsed time threshold or dialogue step threshold has been reached.
     *
     * @param deltaSeconds elapsed frame delta in seconds ({@code >= 0})
     * @return saved slot identifier wrapped in {@link Optional}, or {@link Optional#empty()} if no save occurred
     */
    public synchronized Optional<String> update(double deltaSeconds) {
        if (!Double.isFinite(deltaSeconds) || deltaSeconds < 0.0) {
            throw new IllegalArgumentException("deltaSeconds must be finite and >= 0, got " + deltaSeconds);
        }
        if (!enabled) {
            return Optional.empty();
        }
        elapsedSinceLastSaveSeconds += deltaSeconds;
        if (elapsedSinceLastSaveSeconds >= intervalSeconds || pendingStepCount >= dialogueStepInterval) {
            return performSave();
        }
        return Optional.empty();
    }

    /**
     * Explicitly triggers an immediate rotating auto-save regardless of elapsed time,
     * pending step count, or enabled state.
     *
     * @return saved slot identifier wrapped in {@link Optional}, or {@link Optional#empty()} if the snapshot provider returned {@code null}
     */
    public synchronized Optional<String> forceSave() {
        return performSave();
    }

    /**
     * Returns the 0-based index of the next auto-save slot in {@code [0, maxSlots - 1]},
     * corresponding to {@code auto_<index + 1>}.
     *
     * @return next 0-based rotating slot index
     */
    public synchronized int getNextSlotIndex() {
        return nextSlotIndex;
    }

    /**
     * Returns the number of dialogue steps accumulated since the last successful auto-save.
     *
     * @return pending dialogue step count
     */
    public synchronized int getPendingStepCount() {
        return pendingStepCount;
    }

    /**
     * Returns the elapsed time in seconds since the last successful auto-save.
     *
     * @return elapsed seconds
     */
    public synchronized double getElapsedSinceLastSaveSeconds() {
        return elapsedSinceLastSaveSeconds;
    }

    /**
     * Enables or disables automatic time/step-triggered saves.
     *
     * @param enabled {@code true} to enable automatic saves
     */
    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns whether automatic time/step-triggered saves are currently enabled.
     *
     * @return {@code true} if enabled
     */
    public synchronized boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the configured number of rotating auto-save slots.
     *
     * @return maximum rotating slot count
     */
    public int getMaxSlots() {
        return maxSlots;
    }

    /**
     * Returns the configured elapsed-seconds threshold between automatic saves.
     *
     * @return time interval threshold in seconds
     */
    public double getIntervalSeconds() {
        return intervalSeconds;
    }

    /**
     * Returns the configured dialogue step threshold between automatic saves.
     *
     * @return dialogue step threshold
     */
    public int getDialogueStepInterval() {
        return dialogueStepInterval;
    }

    private Optional<String> performSave() {
        SaveSlotData snapshot = snapshotProvider.get();
        if (snapshot == null) {
            return Optional.empty();
        }
        int slotNumber = nextSlotIndex + 1;
        String slotId = SaveManager.AUTO_SAVE_PREFIX + slotNumber;
        String customName = (snapshot.metadata() != null
                && snapshot.metadata().customName() != null
                && !snapshot.metadata().customName().isBlank())
                ? snapshot.metadata().customName()
                : "Auto Save " + slotNumber;
        try {
            saveManager.saveSlot(slotId, customName, snapshot);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write auto-save slot '" + slotId + "'", e);
        }
        nextSlotIndex = (nextSlotIndex + 1) % maxSlots;
        pendingStepCount = 0;
        elapsedSinceLastSaveSeconds = 0.0;
        return Optional.of(slotId);
    }
}
