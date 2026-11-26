package de.swfdtf.graphics;

import de.swfdtf.math.Rect2f;

import java.util.Objects;

/**
 * Sprite-sheet grid-animated {@link SpriteNode} advancing UV sub-rectangles across frames over time.
 *
 * <p>Supports {@link PlayMode#LOOP}, {@link PlayMode#PING_PONG}, and {@link PlayMode#ONCE} playback
 * in O(1) per frame step without drift or index overflow even for huge delta times.</p>
 */
public class AnimatedSpriteNode extends SpriteNode {

    /**
     * Playback progression mode across sprite-sheet grid frames.
     */
    public enum PlayMode {
        /** Loops continuously from the last frame back to frame 0. */
        LOOP,
        /** Reverses playback direction cleanly at the first and last frames. */
        PING_PONG,
        /** Plays once from frame 0 to the final frame and stops there. */
        ONCE
    }

    private final int columns;
    private final int rows;
    private int frameCount;
    private float fps;
    private PlayMode playMode;
    private double elapsedSeconds = 0.0;
    private int currentFrame = 0;
    private boolean paused = false;
    private boolean finished = false;

    /**
     * Creates a looping {@code AnimatedSpriteNode} with {@link PlayMode#LOOP} and {@code frameCount = columns * rows}.
     *
     * @param id      optional node identifier
     * @param columns number of grid columns in the sprite sheet ({@code > 0})
     * @param rows    number of grid rows in the sprite sheet ({@code > 0})
     * @param fps     playback speed in frames per second ({@code >= 0}, finite)
     */
    public AnimatedSpriteNode(String id, int columns, int rows, float fps) {
        this(id, columns, rows, fps, PlayMode.LOOP);
    }

    /**
     * Creates an {@code AnimatedSpriteNode} with explicit {@link PlayMode}.
     *
     * @param id       optional node identifier
     * @param columns  number of grid columns in the sprite sheet ({@code > 0})
     * @param rows     number of grid rows in the sprite sheet ({@code > 0})
     * @param fps      playback speed in frames per second ({@code >= 0}, finite)
     * @param playMode non-null playback mode
     */
    public AnimatedSpriteNode(String id, int columns, int rows, float fps, PlayMode playMode) {
        this(id, null, columns, rows, fps, playMode);
    }

    /**
     * Creates an {@code AnimatedSpriteNode} with explicit lookup tag and {@link PlayMode}.
     *
     * @param id       optional node identifier
     * @param tag      optional scene lookup tag
     * @param columns  number of grid columns in the sprite sheet ({@code > 0})
     * @param rows     number of grid rows in the sprite sheet ({@code > 0})
     * @param fps      playback speed in frames per second ({@code >= 0}, finite)
     * @param playMode non-null playback mode
     */
    public AnimatedSpriteNode(
            String id,
            String tag,
            int columns,
            int rows,
            float fps,
            PlayMode playMode
    ) {
        super(id, tag);
        if (columns <= 0 || rows <= 0) {
            throw new IllegalArgumentException(
                    "Sprite sheet grid dimensions must be positive, got " + columns + "x" + rows
            );
        }
        if (!Float.isFinite(fps) || fps < 0.0f) {
            throw new IllegalArgumentException("FPS must be finite and >= 0, got " + fps);
        }
        this.playMode = Objects.requireNonNull(playMode, "playMode must not be null");
        this.columns = columns;
        this.rows = rows;
        this.frameCount = Math.multiplyExact(columns, rows);
        this.fps = fps;
        applyFrameUv(0);
    }

    @Override
    public void update(double dt) {
        if (!paused && Double.isFinite(dt) && dt > 0.0 && fps > 0.0f && frameCount > 1) {
            elapsedSeconds += dt;
            long step = (long) Math.floor(elapsedSeconds * fps + 1.0e-9);
            if (step < 0L) {
                step = 0L;
            }
            int nextFrame = computeFrameForStep(step);
            if (nextFrame != currentFrame) {
                applyFrameUv(nextFrame);
            }
        }
        super.update(dt);
    }

    private int computeFrameForStep(long step) {
        return switch (playMode) {
            case LOOP -> (int) (step % frameCount);
            case PING_PONG -> {
                long period = 2L * (frameCount - 1L);
                long pos = step % period;
                yield pos < frameCount ? (int) pos : (int) (period - pos);
            }
            case ONCE -> {
                if (step >= frameCount - 1L) {
                    finished = true;
                    yield frameCount - 1;
                }
                yield (int) step;
            }
        };
    }

    /**
     * Computes the normalized UV sub-rectangle for zero-based grid frame index {@code frameIndex}.
     *
     * @param frameIndex zero-based frame index clamped into {@code [0, frameCount - 1]}
     * @return normalized UV sub-rectangle within the sprite sheet
     */
    public Rect2f computeFrameUv(int frameIndex) {
        int clamped = Math.clamp(frameIndex, 0, Math.max(0, frameCount - 1));
        int col = clamped % columns;
        int row = clamped / columns;
        float cellW = 1.0f / columns;
        float cellH = 1.0f / rows;
        return new Rect2f(col * cellW, row * cellH, cellW, cellH);
    }

    private void applyFrameUv(int frameIndex) {
        this.currentFrame = Math.clamp(frameIndex, 0, Math.max(0, frameCount - 1));
        setUvRect(computeFrameUv(this.currentFrame));
    }

    /**
     * Resets animation time and frame back to frame {@code 0}.
     */
    public void reset() {
        this.elapsedSeconds = 0.0;
        this.finished = false;
        applyFrameUv(0);
    }

    /**
     * Returns sprite-sheet grid column count.
     *
     * @return column count ({@code > 0})
     */
    public int getColumns() {
        return columns;
    }

    /**
     * Returns sprite-sheet grid row count.
     *
     * @return row count ({@code > 0})
     */
    public int getRows() {
        return rows;
    }

    /**
     * Returns active frame count played across the grid.
     *
     * @return frame count in {@code [1, columns * rows]}
     */
    public int getFrameCount() {
        return frameCount;
    }

    /**
     * Sets the active frame count when trailing cells of the grid are empty.
     *
     * @param frameCount number of valid frames in {@code [1, columns * rows]}
     * @return {@code this} for fluent chaining
     */
    public AnimatedSpriteNode setFrameCount(int frameCount) {
        int maxFrames = Math.multiplyExact(columns, rows);
        if (frameCount <= 0 || frameCount > maxFrames) {
            throw new IllegalArgumentException(
                    "frameCount must be in [1, " + maxFrames + "], got " + frameCount
            );
        }
        this.frameCount = frameCount;
        applyFrameUv(Math.min(this.currentFrame, frameCount - 1));
        return this;
    }

    /**
     * Returns playback speed in frames per second.
     *
     * @return frames per second
     */
    public float getFps() {
        return fps;
    }

    /**
     * Updates playback speed in frames per second.
     *
     * @param fps frames per second ({@code >= 0}, finite)
     * @return {@code this} for fluent chaining
     */
    public AnimatedSpriteNode setFps(float fps) {
        if (!Float.isFinite(fps) || fps < 0.0f) {
            throw new IllegalArgumentException("FPS must be finite and >= 0, got " + fps);
        }
        this.fps = fps;
        return this;
    }

    /**
     * Returns active {@link PlayMode}.
     *
     * @return active {@link PlayMode}
     */
    public PlayMode getPlayMode() {
        return playMode;
    }

    /**
     * Sets the active {@link PlayMode}.
     *
     * @param playMode playback progression mode (must not be {@code null})
     * @return {@code this} for fluent chaining
     */
    public AnimatedSpriteNode setPlayMode(PlayMode playMode) {
        this.playMode = Objects.requireNonNull(playMode, "playMode must not be null");
        this.finished = false;
        return this;
    }

    /**
     * Returns the current zero-based frame index.
     *
     * @return current zero-based frame index
     */
    public int getCurrentFrame() {
        return currentFrame;
    }

    /**
     * Jumps immediately to the specified zero-based frame index.
     *
     * @param frameIndex frame index in {@code [0, frameCount - 1]}
     * @return {@code this} for fluent chaining
     */
    public AnimatedSpriteNode setCurrentFrame(int frameIndex) {
        if (frameIndex < 0 || frameIndex >= frameCount) {
            throw new IllegalArgumentException(
                    "frameIndex out of range [0, " + (frameCount - 1) + "]: " + frameIndex
            );
        }
        this.elapsedSeconds = fps > 0.0f ? frameIndex / (double) fps : 0.0;
        applyFrameUv(frameIndex);
        return this;
    }

    /**
     * Returns whether playback is paused.
     *
     * @return {@code true} if paused
     */
    public boolean isPaused() {
        return paused;
    }

    /**
     * Pauses or resumes frame progression.
     *
     * @param paused {@code true} to pause playback
     * @return {@code this} for fluent chaining
     */
    public AnimatedSpriteNode setPaused(boolean paused) {
        this.paused = paused;
        return this;
    }

    /**
     * Returns {@code true} when {@link PlayMode#ONCE} has reached the final frame.
     *
     * @return {@code true} if finished
     */
    public boolean isFinished() {
        return finished;
    }
}
