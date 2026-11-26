package de.swfdtf.core;

/**
 * Deterministic fixed-timestep simulation clock running at 60 Hz ({@link #FIXED_TIMESTEP}).
 *
 * <p>Incoming real frame deltas are clamped to {@link #MAX_FRAME_DELTA} and stepped through a
 * capped accumulator to avoid the spiral of death during frame hitches.</p>
 */
public final class GameClock {

    /**
     * Fixed simulation timestep in seconds (60 Hz).
     */
    public static final double FIXED_TIMESTEP = 1.0 / 60.0;

    /**
     * Maximum accepted real frame delta in seconds before clamping.
     */
    public static final double MAX_FRAME_DELTA = 0.25;

    /**
     * Maximum fixed update steps allowed within a single render frame.
     */
    public static final int MAX_STEPS_PER_FRAME = 15;

    /** Creates a new {@code GameClock} initialized to zero elapsed time. */
    public GameClock() {
    }

    private static final double FPS_WINDOW_SECONDS = 1.0 - 1e-9;

    private double accumulator = 0.0;
    private long tickCount = 0L;
    private double totalTimeSeconds = 0.0;

    private boolean paused = false;
    private double timeScale = 1.0;

    private double fpsAccumulator = 0.0;
    private int fpsFrameCount = 0;
    private int currentFps = 0;

    /**
     * Advances the clock by {@code realDeltaSeconds} and computes how many fixed simulation
     * steps of duration {@link #FIXED_TIMESTEP} should execute this frame.
     *
     * @param realDeltaSeconds elapsed wall-clock time since the previous frame in seconds
     * @return number of fixed simulation steps to execute (0..{@link #MAX_STEPS_PER_FRAME})
     */
    public int advanceFrame(double realDeltaSeconds) {
        if (!Double.isFinite(realDeltaSeconds) || realDeltaSeconds <= 0.0) {
            return 0;
        }

        updateFpsCounter(realDeltaSeconds);

        if (paused || timeScale == 0.0) {
            return 0;
        }

        double clampedDelta = Math.min(realDeltaSeconds, MAX_FRAME_DELTA);
        accumulator += clampedDelta * timeScale;

        int steps = 0;
        while (accumulator >= FIXED_TIMESTEP && steps < MAX_STEPS_PER_FRAME) {
            accumulator -= FIXED_TIMESTEP;
            tickCount++;
            steps++;
        }

        if (steps == MAX_STEPS_PER_FRAME && accumulator >= FIXED_TIMESTEP) {
            accumulator = 0.0;
        }

        totalTimeSeconds = tickCount * FIXED_TIMESTEP;
        return steps;
    }

    private void updateFpsCounter(double realDeltaSeconds) {
        fpsAccumulator += realDeltaSeconds;
        fpsFrameCount++;
        if (fpsAccumulator >= FPS_WINDOW_SECONDS) {
            currentFps = (int) Math.round(fpsFrameCount / fpsAccumulator);
            fpsAccumulator = 0.0;
            fpsFrameCount = 0;
        } else if (currentFps == 0 && fpsAccumulator > 0.0) {
            currentFps = (int) Math.round(fpsFrameCount / fpsAccumulator);
        }
    }

    /**
     * Returns the normalized remainder {@code accumulator / FIXED_TIMESTEP} in {@code [0.0, 1.0]}
     * for render-state interpolation between fixed physics/animation ticks.
     *
     * @return interpolation alpha in {@code [0.0, 1.0]}
     */
    public double getInterpolationAlpha() {
        return Math.clamp(accumulator / FIXED_TIMESTEP, 0.0, 1.0);
    }

    /**
     * Returns total accumulated simulation time in seconds across all executed fixed ticks.
     *
     * @return total simulation seconds
     */
    public double getTotalTimeSeconds() {
        return totalTimeSeconds;
    }

    /**
     * Returns total number of fixed simulation ticks executed since start or last {@link #reset()}.
     *
     * @return fixed tick count
     */
    public long getTickCount() {
        return tickCount;
    }

    /**
     * Returns the measured frames-per-second rate based on real frame deltas.
     *
     * @return measured FPS
     */
    public int getCurrentFps() {
        return currentFps;
    }

    /**
     * Returns whether the simulation clock is currently paused.
     *
     * @return {@code true} if paused
     */
    public boolean isPaused() {
        return paused;
    }

    /**
     * Pauses or resumes simulation tick progression.
     *
     * @param paused {@code true} to pause fixed-timestep progression
     */
    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    /**
     * Returns the simulation time scale multiplier.
     *
     * @return time scale ({@code 1.0} is real-time)
     */
    public double getTimeScale() {
        return timeScale;
    }

    /**
     * Sets the simulation time scale multiplier (must be finite and {@code >= 0.0}).
     *
     * @param timeScale time multiplier
     */
    public void setTimeScale(double timeScale) {
        if (!Double.isFinite(timeScale) || timeScale < 0.0) {
            throw new IllegalArgumentException("timeScale must be finite and non-negative, got: " + timeScale);
        }
        this.timeScale = timeScale;
    }

    /**
     * Returns the fixed timestep constant ({@link #FIXED_TIMESTEP}).
     *
     * @return fixed timestep in seconds
     */
    public double getFixedTimestep() {
        return FIXED_TIMESTEP;
    }

    /**
     * Resets accumulated time, tick count, and FPS counters while preserving pause and time scale settings.
     */
    public void reset() {
        accumulator = 0.0;
        tickCount = 0L;
        totalTimeSeconds = 0.0;
        fpsAccumulator = 0.0;
        fpsFrameCount = 0;
        currentFps = 0;
    }
}
