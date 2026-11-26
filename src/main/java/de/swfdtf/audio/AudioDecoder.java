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
}
