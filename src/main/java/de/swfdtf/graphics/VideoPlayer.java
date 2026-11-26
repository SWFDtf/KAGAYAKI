package de.swfdtf.graphics;

import de.swfdtf.core.EngineLogger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Video playback controller supporting pluggable {@link FrameSource} streams, deterministic
 * procedural test frames ({@link ProceduralTestFrameSource}), live FFmpeg raw RGBA process streaming
 * on Java 21 Virtual Threads ({@link FfmpegProcessFrameSource}), and optional OpenGL 2D texture
 * uploads.
 */
public final class VideoPlayer implements AutoCloseable {

    /**
     * Playback lifecycle state of a {@link VideoPlayer}.
     */
    public enum State {
        /** Stopped option. */
        STOPPED,
        /** Playing option. */
        PLAYING,
        /** Paused option. */
        PAUSED,
        /** Ended option. */
        ENDED
    }

    /**
     * Pluggable decoded RGBA frame producer for video playback.
     */
    public interface FrameSource extends AutoCloseable {
        /**
         * Returns frame width in pixels ({@code > 0}).
         *
         * @return frame width in pixels ({@code > 0})
         */
        int getWidth();

        /**
         * Returns frame height in pixels ({@code > 0}).
         *
         * @return frame height in pixels ({@code > 0})
         */
        int getHeight();

        /**
         * Returns nominal playback frame rate in frames per second ({@code > 0}).
         *
         * @return nominal playback frame rate in frames per second ({@code > 0})
         */
        double getFps();

        /**
         * Returns the next tightly packed top-to-bottom RGBA8 frame ({@code 4 * width * height} bytes),
         * or {@code null} when end-of-stream is reached.
         *
         * @return RGBA8 frame bytes, or {@code null} on EOF
         */
        byte[] nextRgbaFrame();

        /**
         * Resets the stream back to frame 0 so playback can loop or restart.
         */
        void reset();

        @Override
        void close();
    }

    /**
     * Deterministic, headless-safe procedural RGBA frame generator suitable for unit tests and
     * animated fallback backgrounds.
     */
    public static final class ProceduralTestFrameSource implements FrameSource {

        private final int width;
        private final int height;
        private final double fps;
        private final int totalFrames;
        private int frameIndex = 0;
        private boolean closed = false;

        /**
         * Creates an infinite procedural frame source.
         *
         * @param width  frame width in pixels ({@code > 0})
         * @param height frame height in pixels ({@code > 0})
         * @param fps    frames per second ({@code > 0})
         */
        public ProceduralTestFrameSource(int width, int height, double fps) {
            this(width, height, fps, 0);
        }

        /**
         * Creates a procedural frame source with an optional finite frame limit.
         *
         * @param width       frame width in pixels ({@code > 0})
         * @param height      frame height in pixels ({@code > 0})
         * @param fps         frames per second ({@code > 0})
         * @param totalFrames maximum frames before EOF ({@code <= 0} means infinite)
         */
        public ProceduralTestFrameSource(int width, int height, double fps, int totalFrames) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Frame dimensions must be positive, got " + width + "x" + height);
            }
            if (!Double.isFinite(fps) || fps <= 0.0) {
                throw new IllegalArgumentException("fps must be positive and finite, got " + fps);
            }
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.totalFrames = totalFrames;
        }

        @Override
        public int getWidth() {
            return width;
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public double getFps() {
            return fps;
        }

        @Override
        public byte[] nextRgbaFrame() {
            if (closed || (totalFrames > 0 && frameIndex >= totalFrames)) {
                return null;
            }
            int current = frameIndex++;
            int byteCount = Math.multiplyExact(Math.multiplyExact(width, height), 4);
            byte[] rgba = new byte[byteCount];
            int barX = (current * 7) % width;
            byte frameMarker = (byte) ((current * 37) & 0xFF);

            for (int y = 0; y < height; y++) {
                int gy = (y * 255) / Math.max(1, height - 1);
                for (int x = 0; x < width; x++) {
                    int idx = (y * width + x) * 4;
                    boolean onBar = (x == barX);
                    rgba[idx] = onBar
                            ? (byte) 0x58
                            : (byte) (((x * 255) / Math.max(1, width - 1) + frameMarker) & 0xFF);
                    rgba[idx + 1] = onBar ? (byte) 0xA6 : (byte) ((gy + current * 13) & 0xFF);
                    rgba[idx + 2] = onBar ? (byte) 0xFF : (byte) ((current * 29) & 0xFF);
                    rgba[idx + 3] = (byte) 0xFF;
                }
            }
            return rgba;
        }

        @Override
        public void reset() {
            if (!closed) {
                frameIndex = 0;
            }
        }

        @Override
        public void close() {
            closed = true;
        }

        /**
         * Returns zero-based index of the next frame to be emitted.
         *
         * @return zero-based index of the next frame to be emitted
         */
        public int getFrameIndex() {
            return frameIndex;
        }

        /**
         * Returns total finite frames, or {@code <= 0} if infinite.
         *
         * @return total finite frames, or {@code <= 0} if infinite
         */
        public int getTotalFrames() {
            return totalFrames;
        }
    }

    /**
     * Streaming {@link FrameSource} backed by an external {@code ffmpeg} process outputting raw
     * {@code rgba} frames on stdout and decoded asynchronously on a Java 21 Virtual Thread into
     * a bounded frame queue.
     *
     * <p>If {@code ffmpeg} is unavailable on {@code PATH} or the video file cannot be opened,
     * errors are logged via {@link EngineLogger} and {@link #nextRgbaFrame()} gracefully returns
     * {@code null} without throwing or crashing the engine.</p>
     */
    public static final class FfmpegProcessFrameSource implements FrameSource {

        private static final int DEFAULT_QUEUE_CAPACITY = 8;
        private static final int DEFAULT_WIDTH = 1280;
        private static final int DEFAULT_HEIGHT = 720;
        private static final double DEFAULT_FPS = 30.0;
        private static final byte[] EOF_SENTINEL = new byte[0];
        private static final String LOG_CATEGORY = "VideoPlayer";

        private final Path videoPath;
        private final EngineLogger logger;
        private final int width;
        private final int height;
        private final double fps;
        private final boolean explicitDimensions;
        private final ArrayBlockingQueue<byte[]> frameQueue = new ArrayBlockingQueue<>(DEFAULT_QUEUE_CAPACITY);

        private volatile Process process;
        private volatile Thread readerThread;
        private volatile boolean closed = false;
        private volatile boolean eofReached = false;
        private volatile boolean available = false;
        private volatile String errorMessage;

        /**
         * Opens a video stream probing dimensions and frame rate via {@code ffprobe} when available.
         *
         * @param videoPath path to the video asset
         */
        public FfmpegProcessFrameSource(Path videoPath) {
            this(videoPath, 0, 0, 0.0, new EngineLogger());
        }

        /**
         * Opens a video stream with explicit target dimensions and FPS.
         *
         * @param videoPath path to the video asset
         * @param width     target output width in pixels
         * @param height    target output height in pixels
         * @param fps       nominal playback FPS
         */
        public FfmpegProcessFrameSource(Path videoPath, int width, int height, double fps) {
            this(videoPath, width, height, fps, new EngineLogger());
        }

        /**
         * Opens a video stream with explicit dimensions, FPS, and logger.
         *
         * @param videoPath path to the video asset
         * @param width     target width ({@code <= 0} to auto-probe via {@code ffprobe})
         * @param height    target height ({@code <= 0} to auto-probe via {@code ffprobe})
         * @param fps       nominal FPS ({@code <= 0} to auto-probe via {@code ffprobe})
         * @param logger    structured logger (nullable)
         */
        public FfmpegProcessFrameSource(
                Path videoPath,
                int width,
                int height,
                double fps,
                EngineLogger logger
        ) {
            this.videoPath = Objects.requireNonNull(videoPath, "videoPath must not be null");
            this.logger = logger != null ? logger : new EngineLogger();
            this.explicitDimensions = width > 0 && height > 0;

            int resolvedW = width > 0 ? width : DEFAULT_WIDTH;
            int resolvedH = height > 0 ? height : DEFAULT_HEIGHT;
            double resolvedFps = (Double.isFinite(fps) && fps > 0.0) ? fps : DEFAULT_FPS;

            if ((!explicitDimensions || fps <= 0.0) && Files.isRegularFile(videoPath)) {
                ProbeResult probed = probeStream(videoPath);
                if (probed != null) {
                    if (!explicitDimensions) {
                        resolvedW = probed.width;
                        resolvedH = probed.height;
                    }
                    if (!(Double.isFinite(fps) && fps > 0.0) && probed.fps > 0.0) {
                        resolvedFps = probed.fps;
                    }
                }
            }

            this.width = resolvedW;
            this.height = resolvedH;
            this.fps = resolvedFps;

            startProcess();
        }

        /**
         * Checks whether {@code ffmpeg} is executable on the system {@code PATH}.
         *
         * @return {@code true} if {@code ffmpeg -version} exits cleanly
         */
        public static boolean isFfmpegAvailable() {
            try {
                Process proc = new ProcessBuilder("ffmpeg", "-version")
                        .redirectErrorStream(true)
                        .start();
                boolean exited = proc.waitFor(2, TimeUnit.SECONDS);
                if (!exited) {
                    proc.destroyForcibly();
                    return false;
                }
                return proc.exitValue() == 0;
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }
        }

        private record ProbeResult(int width, int height, double fps) {
        }

        private static ProbeResult probeStream(Path path) {
            try {
                Process proc = new ProcessBuilder(
                        "ffprobe",
                        "-v", "error",
                        "-select_streams", "v:0",
                        "-show_entries", "stream=width,height,r_frame_rate",
                        "-of", "default=noprint_wrappers=1:nokey=1",
                        path.toAbsolutePath().toString()
                ).redirectErrorStream(true).start();

                List<String> lines = new java.util.ArrayList<>();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (!line.isBlank()) {
                            lines.add(line.trim());
                        }
                    }
                }
                if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                    return null;
                }
                if (proc.exitValue() != 0 || lines.size() < 2) {
                    return null;
                }
                int w = Integer.parseInt(lines.get(0));
                int h = Integer.parseInt(lines.get(1));
                double probedFps = lines.size() >= 3 ? parseFpsFraction(lines.get(2)) : DEFAULT_FPS;
                if (w > 0 && h > 0) {
                    return new ProbeResult(w, h, probedFps > 0.0 ? probedFps : DEFAULT_FPS);
                }
            } catch (IOException | RuntimeException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
            return null;
        }

        private static double parseFpsFraction(String expr) {
            int slash = expr.indexOf('/');
            if (slash > 0) {
                double num = Double.parseDouble(expr.substring(0, slash).trim());
                double den = Double.parseDouble(expr.substring(slash + 1).trim());
                return den > 0.0 ? num / den : DEFAULT_FPS;
            }
            return Double.parseDouble(expr.trim());
        }

        private synchronized void startProcess() {
            if (closed) {
                return;
            }
            stopProcessInternal();
            frameQueue.clear();
            eofReached = false;

            if (!Files.isRegularFile(videoPath)) {
                available = false;
                eofReached = true;
                errorMessage = "Video file not found: " + videoPath;
                logger.warn(LOG_CATEGORY, errorMessage);
                return;
            }

            List<String> cmd = new java.util.ArrayList<>();
            cmd.add("ffmpeg");
            cmd.add("-loglevel");
            cmd.add("error");
            cmd.add("-i");
            cmd.add(videoPath.toAbsolutePath().toString());
            if (explicitDimensions) {
                cmd.add("-vf");
                cmd.add("scale=" + width + ":" + height);
            }
            cmd.add("-f");
            cmd.add("rawvideo");
            cmd.add("-pix_fmt");
            cmd.add("rgba");
            cmd.add("-");

            try {
                Process started = new ProcessBuilder(cmd)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                this.process = started;
                this.available = true;
                this.errorMessage = null;
                this.readerThread = Thread.ofVirtual()
                        .name("kagayaki-ffmpeg-reader")
                        .start(() -> readFramesLoop(started));
            } catch (IOException ex) {
                this.available = false;
                this.eofReached = true;
                this.errorMessage = "FFmpeg unavailable or failed to launch: " + ex.getMessage();
                logger.warn(LOG_CATEGORY, errorMessage);
            }
        }

        private void readFramesLoop(Process proc) {
            int frameBytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
            try (InputStream in = proc.getInputStream()) {
                while (!closed && proc == this.process) {
                    byte[] frame = new byte[frameBytes];
                    int offset = 0;
                    while (offset < frameBytes) {
                        int read = in.read(frame, offset, frameBytes - offset);
                        if (read < 0) {
                            break;
                        }
                        offset += read;
                    }
                    if (offset < frameBytes) {
                        break;
                    }
                    frameQueue.put(frame);
                }
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                if (!closed && proc == this.process) {
                    frameQueue.offer(EOF_SENTINEL);
                }
            }
        }

        private void stopProcessInternal() {
            Thread prevThread = this.readerThread;
            Process prevProc = this.process;
            this.readerThread = null;
            this.process = null;
            if (prevProc != null) {
                prevProc.destroyForcibly();
            }
            if (prevThread != null) {
                prevThread.interrupt();
            }
        }

        @Override
        public int getWidth() {
            return width;
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public double getFps() {
            return fps;
        }

        @Override
        public byte[] nextRgbaFrame() {
            if (closed || !available || eofReached) {
                return null;
            }
            try {
                byte[] frame = frameQueue.poll(500, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    Process proc = this.process;
                    if (proc == null || !proc.isAlive()) {
                        eofReached = true;
                    }
                    return null;
                }
                if (frame == EOF_SENTINEL || frame.length == 0) {
                    eofReached = true;
                    return null;
                }
                return frame;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                eofReached = true;
                return null;
            }
        }

        @Override
        public void reset() {
            if (!closed) {
                startProcess();
            }
        }

        @Override
        public synchronized void close() {
            closed = true;
            eofReached = true;
            available = false;
            stopProcessInternal();
            frameQueue.clear();
        }

        /**
         * Returns {@code true} if the FFmpeg process launched successfully.
         *
         * @return {@code true} if the FFmpeg process launched successfully
         */
        public boolean isAvailable() {
            return available;
        }

        /**
         * Returns diagnostic error message if launch failed, or {@code null}.
         *
         * @return diagnostic error message if launch failed, or {@code null}
         */
        public String getErrorMessage() {
            return errorMessage;
        }
    }

    private final EngineLogger logger;
    private boolean glEnabled;
    private FrameSource frameSource;
    private boolean ownsFrameSource = false;
    private State state = State.STOPPED;
    private boolean loop = false;
    private double frameAccumulator = 0.0;
    private long currentFrameIndex = -1;
    private byte[] latestFrameRgba;
    private Texture currentTexture;
    private String sourceLabel = "video";

    /**
     * Creates a headless-safe {@code VideoPlayer} with OpenGL texture uploads disabled.
     */
    public VideoPlayer() {
        this(new EngineLogger(), false);
    }

    /**
     * Creates a {@code VideoPlayer} with explicit OpenGL upload control.
     *
     * @param glEnabled {@code true} to upload decoded frames to an OpenGL {@link Texture}
     */
    public VideoPlayer(boolean glEnabled) {
        this(new EngineLogger(), glEnabled);
    }

    /**
     * Creates a {@code VideoPlayer} with explicit logger and OpenGL upload control.
     *
     * @param logger    structured engine logger (non-null)
     * @param glEnabled {@code true} to upload decoded frames to an OpenGL {@link Texture}
     */
    public VideoPlayer(EngineLogger logger, boolean glEnabled) {
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
        this.glEnabled = glEnabled;
    }

    /**
     * Begins playback from a filesystem video path using {@link FfmpegProcessFrameSource}.
     * Fails gracefully without crashing if {@code ffmpeg} or the file is absent.
     *
     * @param videoPath path to the video file (non-null)
     */
    public void play(Path videoPath) {
        Objects.requireNonNull(videoPath, "videoPath must not be null");
        FfmpegProcessFrameSource source = new FfmpegProcessFrameSource(videoPath, 0, 0, 0.0, logger);
        playInternal(source, true, videoPath.getFileName().toString());
        if (!source.isAvailable()) {
            this.state = State.STOPPED;
        }
    }

    /**
     * Begins playback using a caller-supplied {@link FrameSource}.
     *
     * @param source frame producer (non-null)
     */
    public void play(FrameSource source) {
        Objects.requireNonNull(source, "source must not be null");
        playInternal(source, false, source.getClass().getSimpleName());
    }

    private void playInternal(FrameSource source, boolean ownsSource, String label) {
        closeActiveSource();
        this.frameSource = source;
        this.ownsFrameSource = ownsSource;
        this.sourceLabel = label != null ? label : "video";
        this.frameAccumulator = 0.0;
        this.currentFrameIndex = -1;
        this.latestFrameRgba = null;
        this.state = State.PLAYING;

        advanceSingleFrame();
    }

    /**
     * Pauses playback if currently playing.
     */
    public void pause() {
        if (state == State.PLAYING) {
            state = State.PAUSED;
        }
    }

    /**
     * Resumes playback if currently paused.
     */
    public void resume() {
        if (state == State.PAUSED && frameSource != null) {
            state = State.PLAYING;
        }
    }

    /**
     * Stops playback and resets the active {@link FrameSource} back to frame 0.
     */
    public void stop() {
        state = State.STOPPED;
        frameAccumulator = 0.0;
        currentFrameIndex = -1;
        latestFrameRgba = null;
        if (frameSource != null) {
            frameSource.reset();
        }
    }

    /**
     * Enables or disables automatic looping at end-of-stream.
     *
     * @param loop {@code true} to restart from frame 0 at EOF
     */
    public void setLoop(boolean loop) {
        this.loop = loop;
    }

    /**
     * Returns {@code true} if looping is enabled.
     *
     * @return {@code true} if looping is enabled
     */
    public boolean isLoop() {
        return loop;
    }

    /**
     * Advances video frame timing by {@code dt} seconds, pulling new frames at the source's FPS
     * and uploading to OpenGL if enabled.
     *
     * @param dt elapsed frame delta in seconds
     */
    public void update(double dt) {
        if (state != State.PLAYING || frameSource == null) {
            return;
        }
        double safeDt = Double.isFinite(dt) && dt > 0.0 ? dt : 0.0;
        double fps = frameSource.getFps() > 0.0 ? frameSource.getFps() : 30.0;
        double frameDuration = 1.0 / fps;
        frameAccumulator += safeDt;

        while (frameAccumulator + 1.0e-9 >= frameDuration && state == State.PLAYING) {
            frameAccumulator -= frameDuration;
            if (!advanceSingleFrame()) {
                break;
            }
        }
    }

    private boolean advanceSingleFrame() {
        if (frameSource == null) {
            state = State.STOPPED;
            return false;
        }
        byte[] frame = frameSource.nextRgbaFrame();
        if (frame == null) {
            if (loop) {
                frameSource.reset();
                frame = frameSource.nextRgbaFrame();
                if (frame == null) {
                    state = State.ENDED;
                    return false;
                }
                currentFrameIndex = 0;
            } else {
                state = State.ENDED;
                return false;
            }
        } else {
            currentFrameIndex++;
        }

        this.latestFrameRgba = frame;
        if (glEnabled) {
            uploadFrameToGl(frame, frameSource.getWidth(), frameSource.getHeight());
        }
        return true;
    }

    private void uploadFrameToGl(byte[] straightRgba, int width, int height) {
        int expectedBytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (straightRgba.length != expectedBytes) {
            return;
        }
        byte[] premul = Texture.premultiplyRgba(straightRgba);
        if (currentTexture == null
                || currentTexture.isDisposed()
                || currentTexture.getWidth() != width
                || currentTexture.getHeight() != height) {
            if (currentTexture != null) {
                currentTexture.dispose();
            }
            currentTexture = Texture.createFromRgba(width, height, premul, true, "video:" + sourceLabel);
            return;
        }

        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevUnpack = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        ByteBuffer nativeBuf = MemoryUtil.memAlloc(expectedBytes);
        try {
            nativeBuf.put(premul).flip();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, currentTexture.getTextureId());
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexSubImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    0,
                    0,
                    width,
                    height,
                    GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE,
                    nativeBuf
            );
        } finally {
            MemoryUtil.memFree(nativeBuf);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevUnpack);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        }
    }

    private void closeActiveSource() {
        if (frameSource != null) {
            if (ownsFrameSource) {
                frameSource.close();
            }
            frameSource = null;
            ownsFrameSource = false;
        }
    }

    @Override
    public void close() {
        state = State.STOPPED;
        closeActiveSource();
        if (currentTexture != null) {
            currentTexture.dispose();
            currentTexture = null;
        }
        latestFrameRgba = null;
        currentFrameIndex = -1;
    }

    /**
     * Returns latest uploaded OpenGL {@link Texture}, or {@code null} when GL is disabled or no frame uploaded.
     *
     * @return latest uploaded OpenGL {@link Texture}, or {@code null} when GL is disabled or no frame uploaded
     */
    public Texture getCurrentTexture() {
        return currentTexture;
    }

    /**
     * Returns defensive copy of the most recently decoded straight RGBA8 frame, or {@code null}.
     *
     * @return defensive copy of the most recently decoded straight RGBA8 frame, or {@code null}
     */
    public byte[] getLatestFrameRgba() {
        return latestFrameRgba != null ? latestFrameRgba.clone() : null;
    }

    /**
     * Returns active {@link FrameSource}, or {@code null}.
     *
     * @return active {@link FrameSource}, or {@code null}
     */
    public FrameSource getFrameSource() {
        return frameSource;
    }

    /**
     * Immediately skips/cancels active video playback, transitions to {@link State#ENDED},
     * releases any buffered pixel array and GPU texture handle, and resets the stream.
     */
    public void skip() {
        this.state = State.ENDED;
        this.frameAccumulator = 0.0;
        this.latestFrameRgba = null;
        if (currentTexture != null) {
            currentTexture.dispose();
            currentTexture = null;
        }
        if (frameSource != null) {
            frameSource.reset();
        }
    }

    /**
     * Returns current playback {@link State}.
     *
     * @return current playback {@link State}
     */
    public State getState() {
        return state;
    }

    /**
     * Returns whether playing is active.
     *
     * @return the resulting value
     */
    public boolean isPlaying() {
        return state == State.PLAYING;
    }

    /**
     * Returns whether paused is active.
     *
     * @return the resulting value
     */
    public boolean isPaused() {
        return state == State.PAUSED;
    }

    /**
     * Returns whether stopped is active.
     *
     * @return the resulting value
     */
    public boolean isStopped() {
        return state == State.STOPPED;
    }

    /**
     * Returns whether ended is active.
     *
     * @return the resulting value
     */
    public boolean isEnded() {
        return state == State.ENDED;
    }

    /**
     * Returns zero-based index of the currently displayed frame, or {@code -1} if none.
     *
     * @return zero-based index of the currently displayed frame, or {@code -1} if none
     */
    public long getCurrentFrameIndex() {
        return currentFrameIndex;
    }

    /**
     * Returns current video width in pixels, or {@code 0} if no source is attached.
     *
     * @return current video width in pixels, or {@code 0} if no source is attached
     */
    public int getWidth() {
        return frameSource != null ? frameSource.getWidth() : 0;
    }

    /**
     * Returns current video height in pixels, or {@code 0} if no source is attached.
     *
     * @return current video height in pixels, or {@code 0} if no source is attached
     */
    public int getHeight() {
        return frameSource != null ? frameSource.getHeight() : 0;
    }

    /**
     * Returns nominal video frame rate, or {@code 0.0} if no source is attached.
     *
     * @return nominal video frame rate, or {@code 0.0} if no source is attached
     */
    public double getFps() {
        return frameSource != null ? frameSource.getFps() : 0.0;
    }

    /**
     * Returns {@code true} if OpenGL texture uploading is enabled.
     *
     * @return {@code true} if OpenGL texture uploading is enabled
     */
    public boolean isGlEnabled() {
        return glEnabled;
    }

    /**
     * Enables or disables OpenGL texture upload on frame updates.
     *
     * @param glEnabled {@code true} to upload decoded frames to OpenGL
     */
    public void setGlEnabled(boolean glEnabled) {
        this.glEnabled = glEnabled;
    }
}
