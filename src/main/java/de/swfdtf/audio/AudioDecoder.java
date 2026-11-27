package de.swfdtf.audio;

import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCStdlib;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure LWJGL / Java audio decoder, WAV encoder, and procedural synthesizer for KAGAYAKI.
 *
 * <p>Decodes {@code .ogg} (Ogg Vorbis) via {@link STBVorbis} and {@code .wav} (RIFF PCM / IEEE float)
 * streams into normalized float and signed 16-bit PCM buffers, encodes clean RIFF WAVE files,
 * and synthesizes enveloped sine tones, chords, and character voice bleeps.</p>
 */
public final class AudioDecoder {

    private static final int WAV_HEADER_SIZE = 44;
    private static final int PCM_FORMAT_INT = 1;
    private static final int PCM_FORMAT_IEEE_FLOAT = 3;

    private AudioDecoder() {
    }

    /**
     * Immutable decoded audio buffer containing both normalized floating-point samples
     * in {@code [-1, 1]} and signed 16-bit PCM samples ready for OpenAL upload.
     *
     * @param monoOrInterleavedFloat normalized sample data in {@code [-1, 1]} (interleaved by channel)
     * @param pcm16                  signed 16-bit PCM sample data (interleaved by channel)
     * @param channels               channel count ({@code >= 1})
     * @param sampleRate             sample rate in Hz ({@code > 0})
     * @param durationSeconds        playback duration in seconds ({@code >= 0})
     */
    public record DecodedAudio(
            float[] monoOrInterleavedFloat,
            short[] pcm16,
            int channels,
            int sampleRate,
            double durationSeconds
    ) {
        /** Validates buffer lengths and audio stream parameters. */
        public DecodedAudio {
            Objects.requireNonNull(monoOrInterleavedFloat, "monoOrInterleavedFloat must not be null");
            Objects.requireNonNull(pcm16, "pcm16 must not be null");
            if (channels < 1) {
                throw new IllegalArgumentException("channels must be >= 1, got: " + channels);
            }
            if (sampleRate <= 0) {
                throw new IllegalArgumentException("sampleRate must be > 0, got: " + sampleRate);
            }
            if (!Double.isFinite(durationSeconds) || durationSeconds < 0.0) {
                throw new IllegalArgumentException("durationSeconds must be finite and >= 0, got: " + durationSeconds);
            }
            if (monoOrInterleavedFloat.length != pcm16.length) {
                throw new IllegalArgumentException(
                        "Float and PCM16 buffer lengths must match ("
                                + monoOrInterleavedFloat.length + " vs " + pcm16.length + ")"
                );
            }
            if (pcm16.length % channels != 0) {
                throw new IllegalArgumentException(
                        "Sample count " + pcm16.length + " is not divisible by channels=" + channels
                );
            }
        }

        /**
         * Returns the number of audio frames ({@code pcm16.length / channels}).
         *
         * @return frame count
         */
        public int frameCount() {
            return pcm16.length / channels;
        }

        /**
         * Returns a mono float array by averaging channels per frame when {@code channels > 1},
         * or a copy of {@link #monoOrInterleavedFloat()} when already mono.
         *
         * @return mono float sample array of length {@link #frameCount()}
         */
        public float[] toMonoFloat() {
            if (channels == 1) {
                return monoOrInterleavedFloat.clone();
            }
            int frames = frameCount();
            float[] mono = new float[frames];
            float invChannels = 1.0f / channels;
            for (int f = 0; f < frames; f++) {
                double sum = 0.0;
                int base = f * channels;
                for (int c = 0; c < channels; c++) {
                    sum += monoOrInterleavedFloat[base + c];
                }
                mono[f] = (float) (sum * invChannels);
            }
            return mono;
        }
    }

    /**
     * Reads and decodes an audio file ({@code .ogg} or {@code .wav}), auto-detecting format
     * via header magic bytes and falling back to file extension.
     *
     * @param file path to audio file (must not be {@code null})
     * @return decoded audio buffer
     * @throws IOException if the file cannot be read, is corrupt, or has an unsupported format
     */
    public static DecodedAudio decode(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        if (!Files.isRegularFile(file)) {
            throw new IOException("Audio file does not exist or is not a regular file: " + file);
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0) {
            throw new IOException("Audio file is empty (0 bytes): " + file);
        }
        if (isOggHeader(bytes)) {
            return decodeOgg(bytes);
        }
        if (isWavHeader(bytes)) {
            return decodeWav(bytes);
        }
        String name = file.getFileName() != null ? file.getFileName().toString().toLowerCase(Locale.ROOT) : "";
        if (name.endsWith(".ogg") || name.endsWith(".oga")) {
            return decodeOgg(bytes);
        }
        if (name.endsWith(".wav") || name.endsWith(".wave")) {
            return decodeWav(bytes);
        }
        throw new IOException("Unsupported audio format (expected .ogg or .wav): " + file);
    }

    /**
     * Decodes in-memory Ogg Vorbis bytes using LWJGL's {@link STBVorbis#stb_vorbis_decode_memory}.
     *
     * @param oggBytes raw Ogg Vorbis byte array (must not be {@code null})
     * @return decoded audio buffer
     * @throws IOException if the Ogg Vorbis stream is empty or invalid
     */
    public static DecodedAudio decodeOgg(byte[] oggBytes) throws IOException {
        Objects.requireNonNull(oggBytes, "oggBytes must not be null");
        if (oggBytes.length == 0) {
            throw new IOException("Cannot decode empty OGG Vorbis byte array");
        }

        ByteBuffer encoded = MemoryUtil.memAlloc(oggBytes.length);
        ShortBuffer pcm = null;
        try {
            encoded.put(oggBytes).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer channelsBuf = stack.mallocInt(1);
                IntBuffer sampleRateBuf = stack.mallocInt(1);
                pcm = STBVorbis.stb_vorbis_decode_memory(encoded, channelsBuf, sampleRateBuf);
                if (pcm == null) {
                    IntBuffer errorBuf = stack.mallocInt(1);
                    long probe = STBVorbis.stb_vorbis_open_memory(encoded, errorBuf, null);
                    int errCode = errorBuf.get(0);
                    if (probe != MemoryUtil.NULL) {
                        STBVorbis.stb_vorbis_close(probe);
                    }
                    throw new IOException("STBVorbis failed to decode OGG Vorbis stream (error=" + errCode + ")");
                }
                int channels = channelsBuf.get(0);
                int sampleRate = sampleRateBuf.get(0);
                if (channels < 1 || sampleRate <= 0) {
                    throw new IOException(
                            "Invalid OGG stream parameters: channels=" + channels + ", sampleRate=" + sampleRate
                    );
                }

                int totalSamples = pcm.remaining();
                if (totalSamples % channels != 0) {
                    throw new IOException(
                            "Corrupt OGG PCM length " + totalSamples + " not divisible by channels=" + channels
                    );
                }
                short[] pcm16 = new short[totalSamples];
                pcm.get(pcm16);
                float[] floats = pcm16ToFloat(pcm16);
                double duration = (double) (totalSamples / channels) / sampleRate;
                return new DecodedAudio(floats, pcm16, channels, sampleRate, duration);
            }
        } finally {
            if (pcm != null) {
                LibCStdlib.nfree(MemoryUtil.memAddress0(pcm));
            }
            MemoryUtil.nmemFree(MemoryUtil.memAddress0(encoded));
        }
    }

    /**
     * Decodes an in-memory RIFF WAVE ({@code .wav}) byte array supporting 16-bit PCM
     * (as well as 8-bit PCM and 32-bit IEEE float PCM) with arbitrary RIFF subchunk ordering.
     *
     * @param wavBytes raw RIFF WAVE byte array (must not be {@code null})
     * @return decoded audio buffer
     * @throws IOException if the RIFF/WAVE header or chunks are invalid or unsupported
     */
    public static DecodedAudio decodeWav(byte[] wavBytes) throws IOException {
        Objects.requireNonNull(wavBytes, "wavBytes must not be null");
        if (wavBytes.length < WAV_HEADER_SIZE || !isWavHeader(wavBytes)) {
            throw new IOException("Invalid RIFF WAVE header");
        }

        ByteBuffer buf = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN);
        int offset = 12;

        boolean foundFmt = false;
        int audioFormat = 0;
        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;

        byte[] dataBytes = null;

        while (offset + 8 <= wavBytes.length) {
            String chunkId = readFourCc(wavBytes, offset);
            long chunkSizeUnsigned = Integer.toUnsignedLong(buf.getInt(offset + 4));
            offset += 8;

            if (chunkSizeUnsigned > wavBytes.length - offset) {
                throw new IOException(
                        "Truncated WAV chunk '" + chunkId + "' claiming " + chunkSizeUnsigned + " bytes"
                );
            }
            int chunkSize = (int) chunkSizeUnsigned;

            if ("fmt ".equals(chunkId)) {
                if (chunkSize < 16) {
                    throw new IOException("Invalid WAV fmt chunk size: " + chunkSize);
                }
                audioFormat = Short.toUnsignedInt(buf.getShort(offset));
                channels = Short.toUnsignedInt(buf.getShort(offset + 2));
                sampleRate = buf.getInt(offset + 4);
                bitsPerSample = Short.toUnsignedInt(buf.getShort(offset + 14));
                foundFmt = true;
            } else if ("data".equals(chunkId)) {
                dataBytes = new byte[chunkSize];
                System.arraycopy(wavBytes, offset, dataBytes, 0, chunkSize);
            }

            offset += chunkSize;
            if ((chunkSize & 1) != 0 && offset < wavBytes.length) {
                offset++;
            }
        }

        if (!foundFmt || dataBytes == null) {
            throw new IOException("WAV stream missing required 'fmt ' or 'data' chunk");
        }
        if (channels < 1 || sampleRate <= 0) {
            throw new IOException("Invalid WAV format parameters: channels=" + channels + ", sampleRate=" + sampleRate);
        }

        short[] pcm16;
        float[] floats;

        if (audioFormat == PCM_FORMAT_INT && bitsPerSample == 16) {
            if (dataBytes.length % (channels * 2) != 0) {
                throw new IOException("WAV 16-bit PCM data length is not frame-aligned: " + dataBytes.length);
            }
            int sampleCount = dataBytes.length / 2;
            pcm16 = new short[sampleCount];
            ByteBuffer dataBuf = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < sampleCount; i++) {
                pcm16[i] = dataBuf.getShort(i * 2);
            }
            floats = pcm16ToFloat(pcm16);
        } else if (audioFormat == PCM_FORMAT_INT && bitsPerSample == 8) {
            if (dataBytes.length % channels != 0) {
                throw new IOException("WAV 8-bit PCM data length is not frame-aligned: " + dataBytes.length);
            }
            pcm16 = new short[dataBytes.length];
            floats = new float[dataBytes.length];
            for (int i = 0; i < dataBytes.length; i++) {
                int unsigned = dataBytes[i] & 0xFF;
                float f = (unsigned - 128) / 128.0f;
                floats[i] = f;
                pcm16[i] = (short) Math.round(Math.clamp(f, -1.0f, 1.0f) * 32767.0f);
            }
        } else if (audioFormat == PCM_FORMAT_INT && bitsPerSample == 24) {
            if (dataBytes.length % (channels * 3) != 0) {
                throw new IOException("WAV 24-bit PCM data length is not frame-aligned: " + dataBytes.length);
            }
            int sampleCount = dataBytes.length / 3;
            floats = new float[sampleCount];
            pcm16 = new short[sampleCount];
            for (int i = 0; i < sampleCount; i++) {
                int base = i * 3;
                int b0 = dataBytes[base] & 0xFF;
                int b1 = dataBytes[base + 1] & 0xFF;
                int b2 = dataBytes[base + 2]; // signed MSB
                int sample24 = (b2 << 16) | (b1 << 8) | b0;
                float norm = Math.clamp(sample24 / 8388608.0f, -1.0f, 1.0f);
                floats[i] = norm;
                pcm16[i] = floatToPcm16(norm);
            }
        } else if (audioFormat == PCM_FORMAT_IEEE_FLOAT && bitsPerSample == 32) {
            if (dataBytes.length % (channels * 4) != 0) {
                throw new IOException("WAV 32-bit float data length is not frame-aligned: " + dataBytes.length);
            }
            int sampleCount = dataBytes.length / 4;
            floats = new float[sampleCount];
            pcm16 = new short[sampleCount];
            ByteBuffer dataBuf = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < sampleCount; i++) {
                float raw = dataBuf.getFloat(i * 4);
                float clamped = Float.isFinite(raw) ? Math.clamp(raw, -1.0f, 1.0f) : 0.0f;
                floats[i] = clamped;
                pcm16[i] = floatToPcm16(clamped);
            }
        } else {
            throw new IOException(
                    "Unsupported WAV format: audioFormat=" + audioFormat + ", bitsPerSample=" + bitsPerSample
            );
        }

        double duration = (double) (pcm16.length / channels) / sampleRate;
        return new DecodedAudio(floats, pcm16, channels, sampleRate, duration);
    }

    /**
     * Encodes 16-bit PCM samples into a standard 44-byte-header RIFF WAVE byte array.
     *
     * @param pcm16      interleaved signed 16-bit PCM sample array (must not be {@code null})
     * @param channels   channel count ({@code >= 1})
     * @param sampleRate sample rate in Hz ({@code > 0})
     * @return complete RIFF WAVE file bytes
     */
    public static byte[] encodeWav(short[] pcm16, int channels, int sampleRate) {
        Objects.requireNonNull(pcm16, "pcm16 must not be null");
        if (channels < 1) {
            throw new IllegalArgumentException("channels must be >= 1, got: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be > 0, got: " + sampleRate);
        }
        if (pcm16.length % channels != 0) {
            throw new IllegalArgumentException(
                    "pcm16 length " + pcm16.length + " is not divisible by channels=" + channels
            );
        }

        int dataByteLength = Math.multiplyExact(pcm16.length, 2);
        int riffChunkSize = Math.addExact(36, dataByteLength);
        int byteRate = Math.multiplyExact(Math.multiplyExact(sampleRate, channels), 2);
        short blockAlign = (short) (channels * 2);

        ByteBuffer out = ByteBuffer.allocate(WAV_HEADER_SIZE + dataByteLength).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        out.putInt(riffChunkSize);
        out.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');

        out.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
        out.putInt(16);
        out.putShort((short) PCM_FORMAT_INT);
        out.putShort((short) channels);
        out.putInt(sampleRate);
        out.putInt(byteRate);
        out.putShort(blockAlign);
        out.putShort((short) 16);

        out.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
        out.putInt(dataByteLength);

        for (short sample : pcm16) {
            out.putShort(sample);
        }
        return out.array();
    }

    /**
     * Synthesizes a clean enveloped mono sine tone.
     *
     * @param freqHz          tone frequency in Hz ({@code > 0})
     * @param durationSeconds duration in seconds ({@code >= 0})
     * @param sampleRate      sample rate in Hz ({@code > 0})
     * @param amplitude       peak linear amplitude clamped to {@code [0, 1]}
     * @return synthesized mono {@link DecodedAudio}
     */
    public static DecodedAudio synthesizeTone(
            double freqHz,
            double durationSeconds,
            int sampleRate,
            float amplitude
    ) {
        if (!Double.isFinite(freqHz) || freqHz <= 0.0) {
            throw new IllegalArgumentException("freqHz must be finite and > 0, got: " + freqHz);
        }
        return synthesizeChord(new double[]{freqHz}, durationSeconds, sampleRate, amplitude);
    }

    /**
     * Synthesizes a clean enveloped mono chord summing one or more sine frequencies without clipping.
     *
     * @param freqsHz         array of frequencies in Hz (must be non-empty, each {@code > 0})
     * @param durationSeconds duration in seconds ({@code >= 0})
     * @param sampleRate      sample rate in Hz ({@code > 0})
     * @param amplitude       peak linear amplitude clamped to {@code [0, 1]}
     * @return synthesized mono {@link DecodedAudio}
     */
    public static DecodedAudio synthesizeChord(
            double[] freqsHz,
            double durationSeconds,
            int sampleRate,
            float amplitude
    ) {
        Objects.requireNonNull(freqsHz, "freqsHz must not be null");
        if (freqsHz.length == 0) {
            throw new IllegalArgumentException("freqsHz must not be empty");
        }
        for (double f : freqsHz) {
            if (!Double.isFinite(f) || f <= 0.0) {
                throw new IllegalArgumentException("Each frequency must be finite and > 0, got: " + f);
            }
        }
        if (!Double.isFinite(durationSeconds) || durationSeconds < 0.0) {
            throw new IllegalArgumentException("durationSeconds must be finite and >= 0, got: " + durationSeconds);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be > 0, got: " + sampleRate);
        }
        if (!Float.isFinite(amplitude) || amplitude < 0.0f) {
            throw new IllegalArgumentException("amplitude must be finite and >= 0, got: " + amplitude);
        }

        float clampedAmp = Math.clamp(amplitude, 0.0f, 1.0f);
        int sampleCount = (int) Math.round(durationSeconds * sampleRate);
        float[] floats = new float[sampleCount];
        short[] pcm16 = new short[sampleCount];

        int fadeSamples = Math.min((int) Math.round(0.005 * sampleRate), sampleCount / 10);
        double invVoiceCount = 1.0 / freqsHz.length;

        for (int i = 0; i < sampleCount; i++) {
            double t = (double) i / sampleRate;
            double sum = 0.0;
            for (double freq : freqsHz) {
                sum += Math.sin(2.0 * Math.PI * freq * t);
            }
            double env = 1.0;
            if (fadeSamples > 0) {
                if (i < fadeSamples) {
                    env = (double) i / fadeSamples;
                } else if (i >= sampleCount - fadeSamples) {
                    env = (double) (sampleCount - 1 - i) / fadeSamples;
                }
            }
            float value = (float) (sum * invVoiceCount * clampedAmp * env);
            floats[i] = value;
            pcm16[i] = floatToPcm16(value);
        }

        double actualDuration = (double) sampleCount / sampleRate;
        return new DecodedAudio(floats, pcm16, 1, sampleRate, actualDuration);
    }

    /**
     * Synthesizes a short, click-free character voice bleep suitable for typewriter dialogue feedback.
     *
     * @param baseFreqHz      fundamental frequency in Hz ({@code > 0})
     * @param durationSeconds bleep duration in seconds ({@code >= 0})
     * @param sampleRate      sample rate in Hz ({@code > 0})
     * @return synthesized mono {@link DecodedAudio}
     */
    public static DecodedAudio synthesizeVoiceBleep(
            double baseFreqHz,
            double durationSeconds,
            int sampleRate
    ) {
        if (!Double.isFinite(baseFreqHz) || baseFreqHz <= 0.0) {
            throw new IllegalArgumentException("baseFreqHz must be finite and > 0, got: " + baseFreqHz);
        }
        if (!Double.isFinite(durationSeconds) || durationSeconds < 0.0) {
            throw new IllegalArgumentException("durationSeconds must be finite and >= 0, got: " + durationSeconds);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be > 0, got: " + sampleRate);
        }

        int sampleCount = (int) Math.round(durationSeconds * sampleRate);
        float[] floats = new float[sampleCount];
        short[] pcm16 = new short[sampleCount];

        int attackSamples = Math.max(1, Math.min((int) Math.round(0.004 * sampleRate), sampleCount / 5));
        int releaseSamples = Math.max(1, Math.min((int) Math.round(0.008 * sampleRate), sampleCount / 4));

        for (int i = 0; i < sampleCount; i++) {
            double t = (double) i / sampleRate;
            double progress = sampleCount > 1 ? (double) i / (sampleCount - 1) : 0.0;
            double carrier = 0.72 * Math.sin(2.0 * Math.PI * baseFreqHz * t)
                    + 0.20 * Math.sin(2.0 * Math.PI * (baseFreqHz * 2.0) * t)
                    + 0.08 * Math.sin(2.0 * Math.PI * (baseFreqHz * 3.0) * t);

            double decay = Math.exp(-3.2 * progress);
            double edgeEnv = 1.0;
            if (i < attackSamples) {
                edgeEnv = (double) i / attackSamples;
            } else if (i >= sampleCount - releaseSamples) {
                edgeEnv = (double) (sampleCount - 1 - i) / releaseSamples;
            }

            float sample = (float) Math.clamp(carrier * 0.45 * decay * edgeEnv, -1.0, 1.0);
            floats[i] = sample;
            pcm16[i] = floatToPcm16(sample);
        }

        double actualDuration = (double) sampleCount / sampleRate;
        return new DecodedAudio(floats, pcm16, 1, sampleRate, actualDuration);
    }

    /**
     * Converts normalized float samples in {@code [-1, 1]} to signed 16-bit PCM.
     *
     * @param floats normalized float samples (must not be {@code null})
     * @return signed 16-bit PCM array
     */
    public static short[] floatToPcm16(float[] floats) {
        Objects.requireNonNull(floats, "floats must not be null");
        short[] out = new short[floats.length];
        for (int i = 0; i < floats.length; i++) {
            out[i] = floatToPcm16(floats[i]);
        }
        return out;
    }

    /**
     * Converts signed 16-bit PCM samples to normalized float samples in {@code [-1, 1]}.
     *
     * @param pcm16 signed 16-bit PCM samples (must not be {@code null})
     * @return normalized float sample array
     */
    public static float[] pcm16ToFloat(short[] pcm16) {
        Objects.requireNonNull(pcm16, "pcm16 must not be null");
        float[] out = new float[pcm16.length];
        for (int i = 0; i < pcm16.length; i++) {
            short s = pcm16[i];
            out[i] = s < 0 ? s / 32768.0f : s / 32767.0f;
        }
        return out;
    }

    private static short floatToPcm16(float sample) {
        if (!Float.isFinite(sample)) {
            return 0;
        }
        float clamped = Math.clamp(sample, -1.0f, 1.0f);
        if (clamped >= 0.0f) {
            return (short) Math.round(clamped * 32767.0f);
        }
        return (short) Math.round(clamped * 32768.0f);
    }

    private static boolean isOggHeader(byte[] bytes) {
        return bytes.length >= 4
                && bytes[0] == 'O'
                && bytes[1] == 'g'
                && bytes[2] == 'g'
                && bytes[3] == 'S';
    }

    private static boolean isWavHeader(byte[] bytes) {
        return bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'A'
                && bytes[10] == 'V'
                && bytes[11] == 'E';
    }

    private static String readFourCc(byte[] bytes, int offset) {
        return new String(new char[]{
            (char) (bytes[offset] & 0xFF),
            (char) (bytes[offset + 1] & 0xFF),
            (char) (bytes[offset + 2] & 0xFF),
            (char) (bytes[offset + 3] & 0xFF)
        });
    }

    /**
     * Procedural synthesizer waveforms supporting click-free Java synthesis ({@code #0446}).
     */
    public enum Waveform {
        /** Pure sine wave. */
        SINE,
        /** Symmetric triangle wave. */
        TRIANGLE,
        /** One-pole lowpass filtered deterministic pseudo-noise. */
        FILTERED_NOISE
    }

    /**
     * Embedded WAV {@code smpl} chunk loop region frame range ({@code #0501}).
     *
     * @param startFrame inclusive loop start frame index
     * @param endFrame   inclusive loop end frame index
     */
    public record LoopRegion(int startFrame, int endFrame) {
    }

    /**
     * Measured active speech bounds inside a voice recording ({@code #0440}, {@code #0496}).
     *
     * @param startSeconds          first audible timestamp in seconds
     * @param endSeconds            last audible timestamp in seconds
     * @param activeDurationSeconds active speaking window duration
     * @param totalDurationSeconds  total buffer duration
     */
    public record SpeechBounds(
            double startSeconds,
            double endSeconds,
            double activeDurationSeconds,
            double totalDurationSeconds
    ) {
    }

    /**
     * Downmixes interleaved multi-channel samples into mono by averaging per frame ({@code #0449}).
     *
     * @param interleaved interleaved float samples
     * @param channels    channel count ({@code >= 1})
     * @return mono float sample array
     */
    public static float[] downmixToMono(float[] interleaved, int channels) {
        Objects.requireNonNull(interleaved, "interleaved must not be null");
        if (channels <= 1) {
            return interleaved.clone();
        }
        int frames = interleaved.length / channels;
        float[] mono = new float[frames];
        float inv = 1.0f / channels;
        for (int f = 0; f < frames; f++) {
            double sum = 0.0;
            int base = f * channels;
            for (int c = 0; c < channels; c++) {
                sum += interleaved[base + c];
            }
            mono[f] = (float) (sum * inv);
        }
        return mono;
    }

    /**
     * Reads only container headers to inspect WAV/OGG playback duration without full PCM decoding ({@code #0492}).
     *
     * @param file path to {@code .wav} or {@code .ogg} file
     * @return estimated/exact duration in seconds
     * @throws IOException if file is missing, empty, or unreadable
     */
    public static double probeDuration(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        if (!Files.isRegularFile(file)) {
            throw new IOException("Audio file does not exist: " + file);
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0) {
            throw new IOException("Audio file is empty (0 bytes): " + file);
        }
        return probeDurationBytes(bytes, file.toString());
    }

    /**
     * Inspects WAV or OGG headers in memory to compute playback duration without full sample decode ({@code #0492}).
     *
     * @param data encoded audio bytes
     * @param hint diagnostic label/path for error reporting
     * @return duration in seconds
     * @throws IOException if header is empty or invalid
     */
    public static double probeDurationBytes(byte[] data, String hint) throws IOException {
        Objects.requireNonNull(data, "data must not be null");
        if (data.length == 0) {
            throw new IOException("Audio stream is empty (0 bytes): " + hint);
        }
        if (isWavHeader(data)) {
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int offset = 12;
            int byteRate = 0;
            int dataBytes = -1;
            while (offset + 8 <= data.length) {
                String chunkId = readFourCc(data, offset);
                int chunkSize = buf.getInt(offset + 4);
                offset += 8;
                if (chunkSize < 0 || offset + chunkSize > data.length) {
                    break;
                }
                if ("fmt ".equals(chunkId) && chunkSize >= 16) {
                    byteRate = buf.getInt(offset + 8);
                } else if ("data".equals(chunkId)) {
                    dataBytes = chunkSize;
                }
                offset += chunkSize + (chunkSize & 1);
            }
            if (byteRate > 0 && dataBytes >= 0) {
                return (double) dataBytes / byteRate;
            }
            throw new IOException("Invalid WAV header for duration probe: " + hint);
        }
        if (isOggHeader(data)) {
            ByteBuffer buf = ByteBuffer.allocateDirect(data.length);
            buf.put(data).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer err = stack.mallocInt(1);
                long handle = STBVorbis.stb_vorbis_open_memory(buf, err, null);
                if (handle == MemoryUtil.NULL) {
                    throw new IOException("Failed to open OGG header (" + err.get(0) + "): " + hint);
                }
                try {
                    return STBVorbis.stb_vorbis_stream_length_in_seconds(handle);
                } finally {
                    STBVorbis.stb_vorbis_close(handle);
                }
            } finally {
                MemoryUtil.memFree(buf);
            }
        }
        throw new IOException("Unsupported audio format header in: " + hint);
    }

    /**
     * Probes an embedded RIFF WAV {@code smpl} chunk for loop start/end frames ({@code #0501}).
     *
     * @param wavBytes raw RIFF WAVE bytes
     * @return first loop region if present, otherwise {@link Optional#empty()}
     */
    public static Optional<LoopRegion> probeWavLoopRegion(byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length < WAV_HEADER_SIZE || !isWavHeader(wavBytes)) {
            return Optional.empty();
        }
        ByteBuffer buf = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN);
        int offset = 12;
        while (offset + 8 <= wavBytes.length) {
            String chunkId = readFourCc(wavBytes, offset);
            int chunkSize = buf.getInt(offset + 4);
            offset += 8;
            if (chunkSize < 0 || offset + chunkSize > wavBytes.length) {
                break;
            }
            if ("smpl".equals(chunkId) && chunkSize >= 60) {
                int numLoops = buf.getInt(offset + 28);
                if (numLoops > 0) {
                    int start = buf.getInt(offset + 44);
                    int end = buf.getInt(offset + 48);
                    if (end >= start && start >= 0) {
                        return Optional.of(new LoopRegion(start, end));
                    }
                }
            }
            offset += chunkSize + (chunkSize & 1);
        }
        return Optional.empty();
    }

    /**
     * Synthesizes a procedural waveform with full ADSR envelope without {@code javax.sound} ({@code #0446}).
     *
     * @param waveform   waveform shape
     * @param freqHz     base frequency in Hz
     * @param durationS  total duration in seconds
     * @param sampleRate sample rate in Hz
     * @param attackS    attack time in seconds
     * @param decayS     decay time in seconds
     * @param sustain    sustain level in {@code [0, 1]}
     * @param releaseS   release time in seconds
     * @param amplitude  peak output amplitude in {@code [0, 1]}
     * @return synthesized mono {@link DecodedAudio}
     */
    public static DecodedAudio synthesizeAdsr(
            Waveform waveform,
            double freqHz,
            double durationS,
            int sampleRate,
            double attackS,
            double decayS,
            float sustain,
            double releaseS,
            float amplitude
    ) {
        Objects.requireNonNull(waveform, "waveform must not be null");
        int count = Math.max(1, (int) Math.round(Math.max(0.01, durationS) * Math.max(8000, sampleRate)));
        float[] floats = new float[count];
        short[] pcm16 = new short[count];
        float amp = Math.clamp(amplitude, 0.0f, 1.0f);
        float sus = Math.clamp(sustain, 0.0f, 1.0f);
        int atkN = (int) Math.round(Math.max(0.0, attackS) * sampleRate);
        int decN = (int) Math.round(Math.max(0.0, decayS) * sampleRate);
        int relN = (int) Math.round(Math.max(0.0, releaseS) * sampleRate);
        double lpState = 0.0;
        long seed = 0x9E3779B97F4A7C15L;

        for (int i = 0; i < count; i++) {
            double t = (double) i / sampleRate;
            double osc = switch (waveform) {
                case SINE -> Math.sin(2.0 * Math.PI * freqHz * t);
                case TRIANGLE -> 2.0 * Math.abs(2.0 * ((freqHz * t) - Math.floor((freqHz * t) + 0.5))) - 1.0;
                case FILTERED_NOISE -> {
                    seed ^= (seed << 13);
                    seed ^= (seed >>> 7);
                    seed ^= (seed << 17);
                    double white = ((seed & 0xFFFF) / 32768.0) - 1.0;
                    lpState = lpState * 0.78 + white * 0.22;
                    yield lpState;
                }
            };
            double env = sus;
            if (atkN > 0 && i < atkN) {
                env = (double) i / atkN;
            } else if (decN > 0 && i < atkN + decN) {
                double p = (double) (i - atkN) / decN;
                env = 1.0 - (1.0 - sus) * p;
            }
            if (relN > 0 && i >= count - relN) {
                env *= (double) (count - 1 - i) / Math.max(1, relN);
            }
            float sample = (float) Math.clamp(osc * env * amp, -1.0, 1.0);
            floats[i] = sample;
            pcm16[i] = floatToPcm16(sample);
        }
        return new DecodedAudio(floats, pcm16, 1, sampleRate, (double) count / sampleRate);
    }

    /**
     * Downsamples audio streams with sample rates above {@code 48,000 Hz} down to {@code 48,000 Hz}
     * using linear interpolation while preserving playback duration ({@code #0494}).
     *
     * @param source decoded source buffer
     * @return original buffer if {@code sampleRate <= 48000}, otherwise resampled {@code 48000 Hz} buffer
     */
    public static DecodedAudio resampleTo48kHzIfAbove(DecodedAudio source) {
        Objects.requireNonNull(source, "source must not be null");
        if (source.sampleRate() <= 48_000) {
            return source;
        }
        int targetRate = 48_000;
        int ch = source.channels();
        int srcFrames = source.frameCount();
        int dstFrames = Math.max(1, (int) Math.round(source.durationSeconds() * targetRate));
        float[] src = source.monoOrInterleavedFloat();
        float[] dst = new float[dstFrames * ch];
        for (int df = 0; df < dstFrames; df++) {
            double srcPos = dstFrames > 1
                    ? (double) df * (srcFrames - 1) / (dstFrames - 1)
                    : 0.0;
            int f0 = Math.min(srcFrames - 1, (int) Math.floor(srcPos));
            int f1 = Math.min(srcFrames - 1, f0 + 1);
            double frac = srcPos - f0;
            for (int c = 0; c < ch; c++) {
                float s0 = src[f0 * ch + c];
                float s1 = src[f1 * ch + c];
                dst[df * ch + c] = (float) (s0 + (s1 - s0) * frac);
            }
        }
        return new DecodedAudio(dst, floatToPcm16(dst), ch, targetRate, (double) dstFrames / targetRate);
    }

    /**
     * Detects non-silent speech start/end timestamps using a decibel RMS threshold ({@code #0440}, {@code #0496}).
     *
     * @param audio         decoded audio buffer
     * @param thresholdDbfs silence threshold in dBFS (e.g., {@code -40.0f})
     * @return measured {@link SpeechBounds}
     */
    public static SpeechBounds detectActiveSpeechBounds(DecodedAudio audio, float thresholdDbfs) {
        Objects.requireNonNull(audio, "audio must not be null");
        float[] mono = audio.toMonoFloat();
        int windowFrames = Math.max(1, audio.sampleRate() / 100); // 10 ms windows
        float ampThresh = (float) Math.pow(10.0, thresholdDbfs / 20.0);
        int firstActive = -1;
        int lastActive = -1;
        for (int start = 0; start < mono.length; start += windowFrames) {
            int len = Math.min(windowFrames, mono.length - start);
            float rms = AudioDsp.computeRms(mono, start, len);
            if (rms >= ampThresh) {
                if (firstActive < 0) {
                    firstActive = start;
                }
                lastActive = start + len;
            }
        }
        if (firstActive < 0) {
            return new SpeechBounds(0.0, 0.0, 0.0, audio.durationSeconds());
        }
        double startSec = (double) firstActive / audio.sampleRate();
        double endSec = (double) lastActive / audio.sampleRate();
        return new SpeechBounds(startSec, endSec, Math.max(0.0, endSec - startSec), audio.durationSeconds());
    }
}
