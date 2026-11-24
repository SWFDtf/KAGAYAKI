package de.swfdtf.graphics;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Animated {@link SpriteNode} wrapping a {@link VideoPlayer} for cutscenes and looping
 * video backgrounds in the scene graph.
 *
 * <p>Calling {@link #update(double)} advances the underlying {@link VideoPlayer}, automatically
 * synchronizes sprite dimensions from the active {@link VideoPlayer.FrameSource}, and binds
 * the latest OpenGL {@link Texture} when GL uploads are enabled.</p>
 */
public class MovieSprite extends SpriteNode implements AutoCloseable {

    private final VideoPlayer videoPlayer;

    /**
     * Creates a headless-safe {@code MovieSprite} backed by an internal {@link VideoPlayer}.
     */
    public MovieSprite() {
        this(null, false);
    }

    /**
     * Creates a headless-safe {@code MovieSprite} with an optional node identifier.
     *
     * @param id optional scene node identifier
     */
    public MovieSprite(String id) {
        this(id, false);
    }

    /**
     * Creates a {@code MovieSprite} with explicit OpenGL upload control.
     *
     * @param glEnabled {@code true} to upload decoded video frames to OpenGL textures
     */
    public MovieSprite(boolean glEnabled) {
        this(null, glEnabled);
    }

    /**
     * Creates a {@code MovieSprite} with an identifier and explicit OpenGL upload control.
     *
     * @param id        optional scene node identifier
     * @param glEnabled {@code true} to upload decoded video frames to OpenGL textures
     */
    public MovieSprite(String id, boolean glEnabled) {
        this(id, null, new VideoPlayer(glEnabled));
    }

    /**
     * Creates a {@code MovieSprite} wrapping an existing {@link VideoPlayer}.
     *
     * @param id          optional scene node identifier
     * @param videoPlayer backing video player (non-null)
     */
    public MovieSprite(String id, VideoPlayer videoPlayer) {
        this(id, null, videoPlayer);
    }

    /**
     * Creates a {@code MovieSprite} wrapping an existing {@link VideoPlayer} with identifier and tag.
     *
     * @param id          optional scene node identifier
     * @param tag         optional scene lookup tag
     * @param videoPlayer backing video player (non-null)
     */
    public MovieSprite(String id, String tag, VideoPlayer videoPlayer) {
        super(id, tag);
        this.videoPlayer = Objects.requireNonNull(videoPlayer, "videoPlayer must not be null");
        syncFromPlayer(true);
    }

    /**
     * Starts playing a video file from disk via {@link VideoPlayer#play(Path)} and syncs metadata.
     *
     * @param videoPath path to the video file
     * @return {@code this} for fluent chaining
     */
    public MovieSprite play(Path videoPath) {
        Objects.requireNonNull(videoPath, "videoPath must not be null");
        setAssetPath(videoPath.toString());
        videoPlayer.play(videoPath);
        syncFromPlayer(true);
        return this;
    }

    /**
     * Starts playing a custom {@link VideoPlayer.FrameSource} and syncs sprite state.
     *
     * @param source frame source (non-null)
     * @return {@code this} for fluent chaining
     */
    public MovieSprite play(VideoPlayer.FrameSource source) {
        Objects.requireNonNull(source, "source must not be null");
        videoPlayer.play(source);
        syncFromPlayer(true);
        return this;
    }

    public void pause() {
        videoPlayer.pause();
    }

    public void resume() {
        videoPlayer.resume();
    }

    public void stop() {
        videoPlayer.stop();
    }

    public MovieSprite setLoop(boolean loop) {
        videoPlayer.setLoop(loop);
        return this;
    }

    public boolean isLoop() {
        return videoPlayer.isLoop();
    }

    @Override
    public void update(double dt) {
        videoPlayer.update(dt);
        syncFromPlayer(false);
        super.update(dt);
    }

    private void syncFromPlayer(boolean forceSizeSync) {
        Texture tex = videoPlayer.getCurrentTexture();
        if (tex != null && !tex.isDisposed()) {
            if (getTexture() != tex) {
                setTexture(tex);
            }
            return;
        }
        int vw = videoPlayer.getWidth();
        int vh = videoPlayer.getHeight();
        if (vw > 0 && vh > 0 && (forceSizeSync || getWidth() <= 0.0f || getHeight() <= 0.0f)) {
            setSize(vw, vh);
        }
    }

    /** @return backing {@link VideoPlayer} instance */
    public VideoPlayer getVideoPlayer() {
        return videoPlayer;
    }

    @Override
    public void close() {
        videoPlayer.close();
        setTexture(null);
    }
}
