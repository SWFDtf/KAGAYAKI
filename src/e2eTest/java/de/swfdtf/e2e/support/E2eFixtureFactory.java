package de.swfdtf.e2e.support;

import de.swfdtf.audio.AudioDecoder;
import de.swfdtf.graphics.TextureManager;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Procedural binary asset and minimal-project factory for E2E tests.
 *
 * <p>Uses KAGAYAKI's pure LWJGL STB / Java audio utilities without {@code java.awt.*} or
 * {@code javax.imageio.*}.</p>
 */
public final class E2eFixtureFactory {

    private static final int VP8L_SIGNATURE = 0x2F;
    private static final int MAX_VP8L_DIMENSION = 16_384;
    private static final int JPG_QUALITY = 90;

    private E2eFixtureFactory() {
    }

    /**
     * Writes a solid-color RGBA8 PNG file via {@link TextureManager#writePng}.
     *
     * @param destination target PNG file path
     * @param width       image width in pixels ({@code > 0})
     * @param height      image height in pixels ({@code > 0})
     * @param r           red channel {@code [0, 255]}
     * @param g           green channel {@code [0, 255]}
     * @param b           blue channel {@code [0, 255]}
     * @param a           alpha channel {@code [0, 255]}
     * @return normalized path to the written PNG file
     */
    public static Path writeSolidPng(
            Path destination,
            int width,
            int height,
            int r,
            int g,
            int b,
            int a
    ) {
        Objects.requireNonNull(destination, "destination must not be null");
        validatePositiveDimensions(width, height);

        int pixelCount = Math.multiplyExact(width, height);
        byte[] rgba = new byte[Math.multiplyExact(pixelCount, 4)];
        byte rb = (byte) clampByte(r);
        byte gb = (byte) clampByte(g);
        byte bb = (byte) clampByte(b);
        byte ab = (byte) clampByte(a);
        for (int i = 0; i < rgba.length; i += 4) {
            rgba[i] = rb;
            rgba[i + 1] = gb;
            rgba[i + 2] = bb;
            rgba[i + 3] = ab;
        }
        Path normalized = destination.toAbsolutePath().normalize();
        TextureManager.writePng(normalized, width, height, rgba);
        return normalized;
    }

    /**
     * Writes a solid-color RGB JPEG file via {@link STBImageWrite#stbi_write_jpg}.
     *
     * @param destination target JPEG file path
     * @param width       image width in pixels ({@code > 0})
     * @param height      image height in pixels ({@code > 0})
     * @param r           red channel {@code [0, 255]}
     * @param g           green channel {@code [0, 255]}
     * @param b           blue channel {@code [0, 255]}
     * @return normalized path to the written JPEG file
     * @throws IOException if creating parent directories or encoding the JPEG fails
     */
    public static Path writeSolidJpg(
            Path destination,
            int width,
            int height,
            int r,
            int g,
            int b
    ) throws IOException {
        Objects.requireNonNull(destination, "destination must not be null");
        validatePositiveDimensions(width, height);

        Path normalized = destination.toAbsolutePath().normalize();
        createParentDirs(normalized);

        int byteCount = Math.multiplyExact(Math.multiplyExact(width, height), 3);
        byte rb = (byte) clampByte(r);
        byte gb = (byte) clampByte(g);
        byte bb = (byte) clampByte(b);

        ByteBuffer nativeRgb = MemoryUtil.memAlloc(byteCount);
        try {
            for (int i = 0; i < byteCount; i += 3) {
                nativeRgb.put(rb);
                nativeRgb.put(gb);
                nativeRgb.put(bb);
            }
            nativeRgb.flip();
            boolean written = STBImageWrite.stbi_write_jpg(
                    normalized.toString(),
                    width,
                    height,
                    3,
                    nativeRgb,
                    JPG_QUALITY
            );
            if (!written) {
                throw new IOException("STBImageWrite failed to write JPEG: " + normalized);
            }
        } finally {
            MemoryUtil.memFree(nativeRgb);
        }
        return normalized;
    }

    /**
     * Writes a valid minimal lossless {@code RIFF/WEBP/VP8L} image file with pure Java bytes.
     *
     * @param destination target WebP file path
     * @param width       image width in pixels ({@code 1..16384})
     * @param height      image height in pixels ({@code 1..16384})
     * @return normalized path to the written WebP file
     * @throws IOException if writing the file fails
     */
    public static Path writeMinimalWebP(Path destination, int width, int height) throws IOException {
        Objects.requireNonNull(destination, "destination must not be null");
        validatePositiveDimensions(width, height);
        if (width > MAX_VP8L_DIMENSION || height > MAX_VP8L_DIMENSION) {
            throw new IllegalArgumentException("VP8L dimensions must be <= 16384, got " + width + "x" + height);
        }

        byte[] vp8lPayload = buildSolidVp8lPayload(width, height);
        int riffSize = 4 + 8 + vp8lPayload.length;
        ByteBuffer out = ByteBuffer.allocate(8 + riffSize).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        out.putInt(riffSize);
        out.put((byte) 'W').put((byte) 'E').put((byte) 'B').put((byte) 'P');
        out.put((byte) 'V').put((byte) 'P').put((byte) '8').put((byte) 'L');
        out.putInt(vp8lPayload.length);
        out.put(vp8lPayload);

        Path normalized = destination.toAbsolutePath().normalize();
        createParentDirs(normalized);
        Files.write(normalized, out.array());
        return normalized;
    }

    private static byte[] buildSolidVp8lPayload(int width, int height) {
        int headerBits = ((width - 1) & 0x3FFF)
                | (((height - 1) & 0x3FFF) << 14)
                | (1 << 28);

        BitWriter bits = new BitWriter();
        bits.writeBits(0, 1); // transform_present = 0
        bits.writeBits(0, 1); // color_cache_used = 0
        bits.writeBits(0, 1); // meta_huffman_used = 0

        // 5 simple 1-symbol Huffman trees (green/length, red, blue, alpha, distance)
        writeSimpleSingleSymbolCode(bits, 0x80, true);
        writeSimpleSingleSymbolCode(bits, 0x40, true);
        writeSimpleSingleSymbolCode(bits, 0xC0, true);
        writeSimpleSingleSymbolCode(bits, 0xFF, true);
        writeSimpleSingleSymbolCode(bits, 0x00, false);

        byte[] bitstream = bits.toByteArray();
        ByteBuffer payload = ByteBuffer.allocate(5 + bitstream.length).order(ByteOrder.LITTLE_ENDIAN);
        payload.put((byte) VP8L_SIGNATURE);
        payload.putInt(headerBits);
        payload.put(bitstream);
        return payload.array();
    }

    private static void writeSimpleSingleSymbolCode(BitWriter bits, int symbol, boolean eightBit) {
        bits.writeBits(1, 1); // simple_code = 1
        bits.writeBits(0, 1); // num_symbols - 1 = 0
        if (eightBit) {
            bits.writeBits(1, 1); // is_first_8bits = 1
            bits.writeBits(symbol, 8);
        } else {
            bits.writeBits(0, 1); // is_first_8bits = 0
            bits.writeBits(symbol, 1);
        }
    }

    /**
     * Synthesizes a clean mono sine tone and writes it as a 16-bit PCM RIFF WAVE file via
     * {@link AudioDecoder#synthesizeTone} and {@link AudioDecoder#encodeWav}.
     *
     * @param destination     target WAV file path
     * @param freqHz          tone frequency in Hz ({@code > 0})
     * @param durationSeconds duration in seconds ({@code >= 0})
     * @param sampleRate      sample rate in Hz ({@code > 0})
     * @return normalized path to the written WAV file
     * @throws IOException if writing the WAV file fails
     */
    public static Path writeSineWav(
            Path destination,
            double freqHz,
            double durationSeconds,
            int sampleRate
    ) throws IOException {
        Objects.requireNonNull(destination, "destination must not be null");
        AudioDecoder.DecodedAudio audio = AudioDecoder.synthesizeTone(freqHz, durationSeconds, sampleRate, 0.5f);
        byte[] wavBytes = AudioDecoder.encodeWav(audio.pcm16(), audio.channels(), audio.sampleRate());

        Path normalized = destination.toAbsolutePath().normalize();
        createParentDirs(normalized);
        Files.write(normalized, wavBytes);
        return normalized;
    }

    /**
     * Produces minimal valid {@code OggS} BOS page header bytes for container/negative tests.
     *
     * @return minimal OggS container byte array
     */
    public static byte[] createMinimalValidOggPlaceholderBytes() {
        byte[] packet = new byte[]{
                0x01, 'v', 'o', 'r', 'b', 'i', 's',
                0x00, 0x00, 0x00, 0x00,
                0x01,
                0x44, (byte) 0xAC, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x01
        };
        ByteBuffer page = ByteBuffer.allocate(28 + packet.length).order(ByteOrder.LITTLE_ENDIAN);
        page.put((byte) 'O').put((byte) 'g').put((byte) 'g').put((byte) 'S');
        page.put((byte) 0); // stream_structure_version
        page.put((byte) 0x02); // BOS header_type_flag
        page.putLong(0L); // granule_position
        page.putInt(1); // bitstream_serial_number
        page.putInt(0); // page_sequence_number
        page.putInt(0); // CRC placeholder
        page.put((byte) 1); // page_segments
        page.put((byte) packet.length); // segment_table
        page.put(packet);

        byte[] raw = page.array();
        int crc = computeOggCrc32(raw);
        ByteBuffer crcView = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        crcView.putInt(22, crc);
        return raw;
    }

    /**
     * Writes minimal {@code OggS} container bytes produced by {@link #createMinimalValidOggPlaceholderBytes()}
     * to {@code destination}.
     *
     * @param destination target OGG file path
     * @return normalized path to the written OGG file
     * @throws IOException if writing the file fails
     */
    public static Path writeMinimalOgg(Path destination) throws IOException {
        Objects.requireNonNull(destination, "destination must not be null");
        Path normalized = destination.toAbsolutePath().normalize();
        createParentDirs(normalized);
        Files.write(normalized, createMinimalValidOggPlaceholderBytes());
        return normalized;
    }

    /**
     * Creates a minimal playable KAGAYAKI project in {@code projectDir} containing
     * {@code project.vjava.json}, {@code main.vjava}, a procedural background PNG, and a WAV audio file.
     *
     * @param projectDir        target project directory
     * @param mainScriptContent contents of {@code main.vjava}
     * @return normalized path to the created project directory
     * @throws IOException if writing project files or procedural assets fails
     */
    public static Path createMinimalPlayableProject(Path projectDir, String mainScriptContent) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Objects.requireNonNull(mainScriptContent, "mainScriptContent must not be null");

        Path root = E2eWorkspaceSupport.createEmptyProject(projectDir, "Minimal E2E Project", "2D");
        E2eWorkspaceSupport.writeScript(root, "main.vjava", mainScriptContent);
        writeSolidPng(root.resolve("assets/images/bg/bg_default.png"), 64, 64, 32, 48, 80, 255);
        writeSineWav(root.resolve("assets/audio/tone.wav"), 440.0, 0.1, 22_050);
        return root;
    }

    private static void validatePositiveDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive, got " + width + "x" + height);
        }
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static void createParentDirs(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static int computeOggCrc32(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc ^= (b & 0xFF) << 24;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000_0000) != 0) {
                    crc = (crc << 1) ^ 0x04C11DB7;
                } else {
                    crc <<= 1;
                }
            }
        }
        return crc;
    }

    private static final class BitWriter {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int currentByte = 0;
        private int bitCount = 0;

        private void writeBits(int value, int count) {
            for (int i = 0; i < count; i++) {
                int bit = (value >>> i) & 1;
                currentByte |= (bit << bitCount);
                bitCount++;
                if (bitCount == 8) {
                    out.write(currentByte);
                    currentByte = 0;
                    bitCount = 0;
                }
            }
        }

        private byte[] toByteArray() {
            if (bitCount > 0) {
                out.write(currentByte);
            }
            return out.toByteArray();
        }
    }
}
