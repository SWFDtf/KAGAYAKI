package de.swfdtf.audio;

import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Chunked streaming Ogg Vorbis decoder backed by {@link STBVorbis}, keeping heap allocation
 * strictly bounded regardless of track duration ({@code < 64 KB} buffer even for long tracks).
 */
public final class OggVorbisStream implements AutoCloseable {

    /** Fixed number of PCM frames per rolling chunk window. */
    public static final int DEFAULT_CHUNK_FRAMES = 4096;

    private final Path sourcePath;
    private final long vorbisHandle;
    private final int channels;
    private final int sampleRate;
    private final int totalFrames;
    private final double durationSeconds;
    private final short[] chunkScratch;
    private final float[] rollingMonoWindow;
    private int rollingMonoCount = 0;
    private boolean closed = false;

    private OggVorbisStream(
            Path sourcePath,
            long vorbisHandle,
            int channels,
            int sampleRate,
            int totalFrames
    ) {
        this.sourcePath = sourcePath;
        this.vorbisHandle = vorbisHandle;
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.totalFrames = totalFrames;
        this.durationSeconds = totalFrames > 0 ? totalFrames / (double) sampleRate : 0.0;
        this.chunkScratch = new short[DEFAULT_CHUNK_FRAMES * channels];
        this.rollingMonoWindow = new float[DEFAULT_CHUNK_FRAMES];
    }

    /**
     * Checks whether {@code file} looks like an Ogg Vorbis stream by reading the first 4 bytes
     * ({@code OggS}) or checking a {@code .ogg}/{@code .oga} filename suffix.
     *
     * @param file path to check (nullable)
     * @return {@code true} if {@code file} appears to be Ogg Vorbis
     */
    public static boolean isOggFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] header = in.readNBytes(4);
            if (header.length == 4
                    && header[0] == 'O' && header[1] == 'g' && header[2] == 'g' && header[3] == 'S') {
                return true;
            }
        } catch (IOException ignored) {
            return false;
        }
        String name = file.getFileName() != null ? file.getFileName().toString().toLowerCase(Locale.ROOT) : "";
        return name.endsWith(".ogg") || name.endsWith(".oga");
    }

    /**
     * Opens an Ogg Vorbis file for low-memory chunked streaming without decoding the whole track
     * into Java heap.
     *
     * @param file path to an {@code .ogg}/{@code .oga} audio file (must not be {@code null})
     * @return opened {@link OggVorbisStream}
     * @throws IOException if the file cannot be opened or contains an invalid/empty Vorbis stream
     */
    public static OggVorbisStream open(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        Path normalized = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("OGG file does not exist or is not a regular file: " + normalized);
        }

        long handle = MemoryUtil.NULL;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer errorBuf = stack.mallocInt(1);
            handle = STBVorbis.stb_vorbis_open_filename(normalized.toString(), errorBuf, null);
            if (handle == MemoryUtil.NULL) {
                throw new IOException(
                        "Failed to open OGG Vorbis stream '" + normalized + "' (error=" + errorBuf.get(0) + ")"
                );
            }

            STBVorbisInfo info = STBVorbisInfo.malloc(stack);
            STBVorbis.stb_vorbis_get_info(handle, info);
            int channels = info.channels();
            int sampleRate = info.sample_rate();
            int totalFrames = STBVorbis.stb_vorbis_stream_length_in_samples(handle);

            if (channels < 1 || sampleRate <= 0 || totalFrames <= 0) {
                STBVorbis.stb_vorbis_close(handle);
                handle = MemoryUtil.NULL;
                throw new IOException(
                        "Invalid or header-only OGG stream '" + normalized
                                + "' (channels=" + channels + ", sampleRate=" + sampleRate
                                + ", frames=" + totalFrames + ")"
                );
            }

            int probeSamples = STBVorbis.stb_vorbis_get_samples_short_interleaved(
                    handle, channels, new short[Math.min(512, totalFrames) * channels]
            );
            if (probeSamples <= 0) {
                STBVorbis.stb_vorbis_close(handle);
                handle = MemoryUtil.NULL;
                throw new IOException("OGG stream contains no decodable PCM frames: " + normalized);
            }
            STBVorbis.stb_vorbis_seek_start(handle);

            OggVorbisStream stream = new OggVorbisStream(normalized, handle, channels, sampleRate, totalFrames);
            handle = MemoryUtil.NULL;
            return stream;
        } finally {
            if (handle != MemoryUtil.NULL) {
                STBVorbis.stb_vorbis_close(handle);
            }
        }
    }

    /**
     * Decodes up to {@code maxFrames} interleaved signed 16-bit PCM frames into {@code dst}.
     *
     * @param dst       destination array of length at least {@code maxFrames * getChannels()}
     * @param maxFrames maximum frames to read ({@code >= 0})
     * @return number of frames decoded ({@code 0} at end of stream or when closed)
     */
    public synchronized int readInterleavedPcm16(short[] dst, int maxFrames) {
        Objects.requireNonNull(dst, "dst must not be null");
        if (maxFrames < 0) {
            throw new IllegalArgumentException("maxFrames must be >= 0, got " + maxFrames);
        }
        if (closed || maxFrames == 0) {
            return 0;
        }
        int limitFrames = Math.min(maxFrames, dst.length / channels);
        if (limitFrames <= 0) {
            return 0;
        }
        int targetSamples = limitFrames * channels;
        short[] targetBuf = (dst.length == targetSamples) ? dst : new short[targetSamples];
        int framesRead = STBVorbis.stb_vorbis_get_samples_short_interleaved(vorbisHandle, channels, targetBuf);
        if (targetBuf != dst && framesRead > 0) {
            System.arraycopy(targetBuf, 0, dst, 0, framesRead * channels);
        }
        if (framesRead > 0) {
            updateRollingMono(targetBuf, framesRead);
        }
        return Math.max(0, framesRead);
    }

    /**
     * Decodes a small bounded chunk and returns the latest mono float analysis window
     * ({@code <= DEFAULT_CHUNK_FRAMES} samples) for RMS and spectrum extraction.
     *
     * @param windowFrames requested window length in frames ({@code > 0})
     * @return mono float samples in {@code [-1, 1]}
     */
    public synchronized float[] readMonoAnalysisWindow(int windowFrames) {
        int clampedWindow = Math.clamp(windowFrames, 1, DEFAULT_CHUNK_FRAMES);
        if (closed) {
            return new float[clampedWindow];
        }
        int samplesNeeded = clampedWindow * channels;
        int frames = STBVorbis.stb_vorbis_get_samples_short_interleaved(vorbisHandle, channels, chunkScratch);
        if (frames <= 0) {
            STBVorbis.stb_vorbis_seek_start(vorbisHandle);
            frames = STBVorbis.stb_vorbis_get_samples_short_interleaved(vorbisHandle, channels, chunkScratch);
        }
        if (frames > 0) {
            updateRollingMono(chunkScratch, Math.min(frames, samplesNeeded / channels));
        }
        int copyCount = Math.min(clampedWindow, rollingMonoCount);
        float[] out = new float[clampedWindow];
        if (copyCount > 0) {
            System.arraycopy(rollingMonoWindow, rollingMonoCount - copyCount, out, 0, copyCount);
        }
        return out;
    }

    /**
     * Seeks the Ogg Vorbis decode cursor directly to {@code seconds} within stream duration.
     *
     * @param seconds target playback position in seconds
     */
    public synchronized void seekSeconds(double seconds) {
        if (closed) {
            return;
        }
        double clampedSec = Double.isFinite(seconds) ? Math.clamp(seconds, 0.0, durationSeconds) : 0.0;
        int sampleOffset = Math.clamp(Math.round((float) (clampedSec * sampleRate)), 0, Math.max(0, totalFrames - 1));
        STBVorbis.stb_vorbis_seek(vorbisHandle, sampleOffset);
    }

    private void updateRollingMono(short[] interleaved, int frames) {
        float invScale = 1.0f / (32768.0f * channels);
        int usableFrames = Math.min(frames, DEFAULT_CHUNK_FRAMES);
        for (int f = 0; f < usableFrames; f++) {
            float sum = 0.0f;
            int base = f * channels;
            for (int c = 0; c < channels; c++) {
                sum += interleaved[base + c];
            }
            rollingMonoWindow[f] = Math.clamp(sum * invScale, -1.0f, 1.0f);
        }
        rollingMonoCount = usableFrames;
    }

    /**
     * Returns the backing source file path.
     *
     * @return normalized source file path
     */
    public Path getSourcePath() {
        return sourcePath;
    }

    /**
     * Returns audio channel count ({@code >= 1}).
     *
     * @return channel count
     */
    public int getChannels() {
        return channels;
    }

    /**
     * Returns sample rate in Hz.
     *
     * @return sample rate in Hz
     */
    public int getSampleRate() {
        return sampleRate;
    }

    /**
     * Returns total decoded stream frame length.
     *
     * @return total frame count
     */
    public int getTotalFrames() {
        return totalFrames;
    }

    /**
     * Returns total track duration in seconds.
     *
     * @return track duration in seconds
     */
    public double getDurationSeconds() {
        return durationSeconds;
    }

    /**
     * Returns whether this stream has been closed.
     *
     * @return {@code true} if closed
     */
    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        STBVorbis.stb_vorbis_close(vorbisHandle);
    }
}
