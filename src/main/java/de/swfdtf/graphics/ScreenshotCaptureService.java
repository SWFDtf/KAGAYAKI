package de.swfdtf.graphics;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Pure LWJGL/STB screenshot capture and directory listing service.
 *
 * <p>Writes tightly packed straight RGBA8 pixel buffers directly to PNG files via
 * {@link TextureManager#writePng(Path, int, int, byte[])} without any {@code java.awt.*}
 * or {@code javax.imageio.*} dependencies.</p>
 */
public final class ScreenshotCaptureService {

    private static final int MAX_BASENAME_LENGTH = 120;
    private static final String DEFAULT_BASENAME = "screenshot";
    private static final String PNG_EXTENSION = ".png";

    private final Object writeLock = new Object();

    /**
     * Creates a {@code ScreenshotCaptureService}.
     */
    public ScreenshotCaptureService() {
    }

    /**
     * Sanitizes a raw file base name so it contains only safe filename characters and no
     * directory traversal sequences or path separators.
     *
     * @param raw input file name or stem (must not be {@code null})
     * @return non-empty sanitized filename stem
     * @throws NullPointerException if {@code raw} is {@code null}
     */
    public static String sanitizeFileName(String raw) {
        Objects.requireNonNull(raw, "raw must not be null");

        String cleaned = raw.trim()
                .replaceAll("[^A-Za-z0-9._-]", "_")
                .replaceAll("\\.{2,}", "_")
                .replaceAll("_{2,}", "_");

        while (!cleaned.isEmpty() && (cleaned.charAt(0) == '.' || cleaned.charAt(0) == '_')) {
            cleaned = cleaned.substring(1);
        }
        while (!cleaned.isEmpty() && (cleaned.charAt(cleaned.length() - 1) == '.'
                || cleaned.charAt(cleaned.length() - 1) == '_')) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.length() > MAX_BASENAME_LENGTH) {
            cleaned = cleaned.substring(0, MAX_BASENAME_LENGTH);
        }
        if (cleaned.isEmpty() || cleaned.replace("_", "").isEmpty()) {
            return DEFAULT_BASENAME;
        }
        return cleaned;
    }

    /**
     * Writes an RGBA8 pixel buffer to a uniquely named PNG file inside {@code outputDir}.
     *
     * <p>Creates {@code outputDir} if it does not yet exist, sanitizes {@code baseName},
     * validates that {@code rgbaBytes.length == width * height * 4}, and encodes the image
     * through {@link TextureManager#writePng(Path, int, int, byte[])}.</p>
     *
     * @param outputDir target directory for the PNG screenshot (must not be {@code null})
     * @param baseName  desired base file name before sanitization (must not be {@code null})
     * @param width     image width in pixels ({@code > 0})
     * @param height    image height in pixels ({@code > 0})
     * @param rgbaBytes tightly packed top-to-bottom straight RGBA8 bytes (must not be {@code null})
     * @return normalized path to the newly written PNG file
     * @throws NullPointerException     if {@code outputDir}, {@code baseName}, or {@code rgbaBytes} is {@code null}
     * @throws IllegalArgumentException if dimensions are non-positive or {@code rgbaBytes.length != width * height * 4}
     * @throws IOException              if creating {@code outputDir} or writing the PNG file fails
     */
    public Path captureRgba(Path outputDir, String baseName, int width, int height, byte[] rgbaBytes)
            throws IOException {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(baseName, "baseName must not be null");
        Objects.requireNonNull(rgbaBytes, "rgbaBytes must not be null");

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Screenshot dimensions must be positive, got " + width + "x" + height
            );
        }

        long expectedBytesLong = (long) width * (long) height * 4L;
        if (expectedBytesLong > Integer.MAX_VALUE || rgbaBytes.length != (int) expectedBytesLong) {
            throw new IllegalArgumentException(
                    "Expected " + expectedBytesLong + " RGBA bytes for " + width + "x" + height
                            + ", got " + rgbaBytes.length
            );
        }

        String stem = stripPngExtension(sanitizeFileName(baseName));
        Path normalizedDir = outputDir.toAbsolutePath().normalize();

        synchronized (writeLock) {
            Files.createDirectories(normalizedDir);
            Path destination = resolveUniqueDestination(normalizedDir, stem);
            try {
                TextureManager.writePng(destination, width, height, rgbaBytes);
            } catch (UncheckedIOException ex) {
                throw ex.getCause();
            } catch (IllegalStateException ex) {
                throw new IOException("Failed to write screenshot PNG: " + destination, ex);
            }
            return destination;
        }
    }

    /**
     * Lists all regular {@code .png} files directly inside {@code outputDir} sorted lexicographically
     * by file name.
     *
     * <p>Returns an empty unmodifiable list if {@code outputDir} does not exist.</p>
     *
     * @param outputDir directory to inspect (must not be {@code null})
     * @return unmodifiable sorted list of PNG {@link Path} entries
     * @throws NullPointerException if {@code outputDir} is {@code null}
     * @throws IOException          if {@code outputDir} exists and is not a directory, or listing fails
     */
    public List<Path> listScreenshots(Path outputDir) throws IOException {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Path normalizedDir = outputDir.toAbsolutePath().normalize();
        if (!Files.exists(normalizedDir)) {
            return List.of();
        }
        if (!Files.isDirectory(normalizedDir)) {
            throw new IOException("outputDir is not a directory: " + normalizedDir);
        }

        try (Stream<Path> entries = Files.list(normalizedDir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(PNG_EXTENSION))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    /**
     * Vertically flips tightly packed RGBA8 pixels in-place/copy so bottom-left OpenGL origin buffers
     * convert cleanly into top-to-bottom image scanline order.
     *
     * @param rgba   source RGBA8 buffer ({@code width * height * 4} bytes)
     * @param width  image width in pixels ({@code > 0})
     * @param height image height in pixels ({@code > 0})
     * @return vertically flipped top-to-bottom RGBA8 buffer
     */
    public static byte[] flipVerticalRgba(byte[] rgba, int width, int height) {
        Objects.requireNonNull(rgba, "rgba must not be null");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive");
        }
        int stride = Math.multiplyExact(width, 4);
        if (rgba.length != Math.multiplyExact(stride, height)) {
            throw new IllegalArgumentException("Unexpected buffer size for " + width + "x" + height);
        }
        byte[] out = new byte[rgba.length];
        for (int row = 0; row < height; row++) {
            int srcOff = (height - 1 - row) * stride;
            int dstOff = row * stride;
            System.arraycopy(rgba, srcOff, out, dstOff, stride);
        }
        return out;
    }

    /**
     * Bilinearly downscales (or resizes) a top-to-bottom RGBA8 buffer to target dimensions, e.g.
     * {@code 320x180} save-slot thumbnails.
     *
     * @param srcRgba source top-to-bottom RGBA8 buffer
     * @param srcW    source width ({@code > 0})
     * @param srcH    source height ({@code > 0})
     * @param dstW    target width ({@code > 0})
     * @param dstH    target height ({@code > 0})
     * @return resized RGBA8 buffer
     */
    public static byte[] resizeBilinearRgba(byte[] srcRgba, int srcW, int srcH, int dstW, int dstH) {
        Objects.requireNonNull(srcRgba, "srcRgba must not be null");
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive");
        }
        if (srcRgba.length != Math.multiplyExact(Math.multiplyExact(srcW, srcH), 4)) {
            throw new IllegalArgumentException("Source buffer length mismatch");
        }
        byte[] dst = new byte[Math.multiplyExact(Math.multiplyExact(dstW, dstH), 4)];
        for (int dy = 0; dy < dstH; dy++) {
            float sy = ((dy + 0.5f) * srcH / (float) dstH) - 0.5f;
            int y0 = Math.clamp((int) Math.floor(sy), 0, srcH - 1);
            int y1 = Math.clamp(y0 + 1, 0, srcH - 1);
            float fy = Math.clamp(sy - y0, 0.0f, 1.0f);
            for (int dx = 0; dx < dstW; dx++) {
                float sx = ((dx + 0.5f) * srcW / (float) dstW) - 0.5f;
                int x0 = Math.clamp((int) Math.floor(sx), 0, srcW - 1);
                int x1 = Math.clamp(x0 + 1, 0, srcW - 1);
                float fx = Math.clamp(sx - x0, 0.0f, 1.0f);

                int idx00 = (y0 * srcW + x0) * 4;
                int idx10 = (y0 * srcW + x1) * 4;
                int idx01 = (y1 * srcW + x0) * 4;
                int idx11 = (y1 * srcW + x1) * 4;
                int outIdx = (dy * dstW + dx) * 4;
                for (int c = 0; c < 4; c++) {
                    float c00 = srcRgba[idx00 + c] & 0xFF;
                    float c10 = srcRgba[idx10 + c] & 0xFF;
                    float c01 = srcRgba[idx01 + c] & 0xFF;
                    float c11 = srcRgba[idx11 + c] & 0xFF;
                    float top = c00 + (c10 - c00) * fx;
                    float bot = c01 + (c11 - c01) * fx;
                    dst[outIdx + c] = (byte) Math.clamp(Math.round(top + (bot - top) * fy), 0, 255);
                }
            }
        }
        return dst;
    }

    /**
     * Reads pixels from an offscreen OpenGL {@link Framebuffer} and writes a PNG screenshot without
     * {@code java.awt}.
     *
     * @param fbo       initialized framebuffer (must not be {@code null})
     * @param outputDir target output directory
     * @param baseName  desired screenshot base name
     * @return path to the written PNG file
     * @throws IOException if writing fails
     */
    public Path captureFramebufferPng(Framebuffer fbo, Path outputDir, String baseName) throws IOException {
        Objects.requireNonNull(fbo, "fbo must not be null");
        byte[] topDown = fbo.readPixelsRgba();
        return captureRgba(outputDir, baseName, fbo.getWidth(), fbo.getHeight(), topDown);
    }

    /**
     * Reads pixels from the currently bound OpenGL backbuffer using {@code glReadPixels}, flips vertically
     * into top-to-bottom scanline order, and saves as PNG via {@link TextureManager#writePng}.
     *
     * @param outputDir target directory
     * @param baseName  desired base filename
     * @param width     framebuffer width in pixels ({@code > 0})
     * @param height    framebuffer height in pixels ({@code > 0})
     * @return path to the written PNG file
     * @throws IOException if writing fails
     */
    public Path captureActiveBackbufferPng(Path outputDir, String baseName, int width, int height)
            throws IOException {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive");
        }
        int total = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        byte[] bottomUp = new byte[total];
        ByteBuffer nativeBuf = MemoryUtil.memAlloc(total);
        try {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, nativeBuf);
            nativeBuf.get(bottomUp);
        } finally {
            MemoryUtil.memFree(nativeBuf);
        }
        byte[] topDown = flipVerticalRgba(bottomUp, width, height);
        return captureRgba(outputDir, baseName, width, height, topDown);
    }

    private static String stripPngExtension(String sanitized) {
        if (sanitized.toLowerCase(Locale.ROOT).endsWith(PNG_EXTENSION)) {
            String stripped = sanitized.substring(0, sanitized.length() - PNG_EXTENSION.length());
            while (!stripped.isEmpty() && (stripped.charAt(stripped.length() - 1) == '.'
                    || stripped.charAt(stripped.length() - 1) == '_')) {
                stripped = stripped.substring(0, stripped.length() - 1);
            }
            return stripped.isEmpty() ? DEFAULT_BASENAME : stripped;
        }
        return sanitized;
    }

    private static Path resolveUniqueDestination(Path dir, String stem) {
        Path candidate = dir.resolve(stem + PNG_EXTENSION);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        int suffix = 1;
        while (true) {
            Path numbered = dir.resolve(stem + "_" + suffix + PNG_EXTENSION);
            if (!Files.exists(numbered)) {
                return numbered;
            }
            suffix++;
        }
    }
}
