package de.swfdtf.tools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure Java asset inspector for KAGAYAKI projects without {@code java.awt.*} or
 * {@code javax.imageio.*}.
 *
 * <p>Classifies files under {@code assets/} into {@link MediaType} categories and parses binary
 * headers directly for PNG ({@code IHDR} dimensions) and WAV ({@code RIFF}/{@code fmt }/{@code data}
 * sample rate, channel count, and duration).</p>
 */
public final class AssetMetadataInspector {

    private static final String ASSETS_DIR_NAME = "assets";
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final int PNG_HEADER_BYTES = 24;

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".webp", ".bmp", ".tga", ".gif"
    );
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            ".wav", ".wave", ".ogg", ".oga", ".mp3", ".flac"
    );
    private static final Set<String> FONT_EXTENSIONS = Set.of(
            ".ttf", ".otf", ".woff", ".woff2"
    );
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            ".mp4", ".webm", ".ogv", ".mov", ".mkv"
    );

    /**
     * Coarse media classification for project assets.
     */
    public enum MediaType {
        /** Raster image asset (e.g., {@code .png}, {@code .jpg}, {@code .webp}). */
        IMAGE,
        /** Audio asset (e.g., {@code .wav}, {@code .ogg}). */
        AUDIO,
        /** Font asset (e.g., {@code .ttf}, {@code .otf}). */
        FONT,
        /** Video asset (e.g., {@code .mp4}, {@code .webm}, {@code .ogv}). */
        VIDEO,
        /** Any unrecognized asset file type. */
        OTHER
    }

    /**
     * Immutable metadata descriptor extracted from a project asset file.
     *
     * @param relativePath    forward-slash path relative to {@code projectRoot}
     * @param mediaType       classified {@link MediaType}
     * @param byteSize        file size in bytes ({@code >= 0})
     * @param widthPx         parsed image width in pixels, if available
     * @param heightPx        parsed image height in pixels, if available
     * @param sampleRateHz    parsed audio sample rate in Hz, if available
     * @param channels        parsed audio channel count, if available
     * @param durationSeconds parsed audio duration in seconds, if available
     */
    public record AssetMetadata(
            String relativePath,
            MediaType mediaType,
            long byteSize,
            Optional<Integer> widthPx,
            Optional<Integer> heightPx,
            Optional<Integer> sampleRateHz,
            Optional<Integer> channels,
            Optional<Double> durationSeconds
    ) {

        /**
         * Validates required fields and normalizes optional containers.
         */
        public AssetMetadata {
            Objects.requireNonNull(relativePath, "relativePath must not be null");
            if (relativePath.isBlank()) {
                throw new IllegalArgumentException("relativePath must not be blank");
            }
            Objects.requireNonNull(mediaType, "mediaType must not be null");
            if (byteSize < 0) {
                throw new IllegalArgumentException("byteSize must be >= 0, got " + byteSize);
            }
            widthPx = widthPx != null ? widthPx : Optional.empty();
            heightPx = heightPx != null ? heightPx : Optional.empty();
            sampleRateHz = sampleRateHz != null ? sampleRateHz : Optional.empty();
            channels = channels != null ? channels : Optional.empty();
            durationSeconds = durationSeconds != null ? durationSeconds : Optional.empty();
        }
    }

    private record PngDimensions(Optional<Integer> widthPx, Optional<Integer> heightPx) {
        private static final PngDimensions EMPTY = new PngDimensions(Optional.empty(), Optional.empty());
    }

    private record WavMetadata(
            Optional<Integer> sampleRateHz,
            Optional<Integer> channels,
            Optional<Double> durationSeconds
    ) {
        private static final WavMetadata EMPTY =
                new WavMetadata(Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * Creates a new {@code AssetMetadataInspector}.
     */
    public AssetMetadataInspector() {
    }

    /**
     * Inspects a single asset file located at {@code relativePath} inside {@code projectRoot}.
     *
     * @param projectRoot  root directory of the KAGAYAKI project (must not be {@code null})
     * @param relativePath project-relative asset path (must not be {@code null} or blank)
     * @return {@link Optional} containing extracted {@link AssetMetadata}, or empty if the file does not exist
     * @throws IOException              if {@code projectRoot} is not a directory or reading fails
     * @throws IllegalArgumentException if {@code relativePath} is blank or escapes {@code projectRoot}
     */
    public Optional<AssetMetadata> inspectAsset(Path projectRoot, String relativePath) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot must not be null");
        Objects.requireNonNull(relativePath, "relativePath must not be null");

        String cleaned = cleanRelativePath(relativePath);
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }

        Path normalizedRoot = requireProjectDirectory(projectRoot);
        Path target = normalizedRoot.resolve(cleaned).normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Asset path escapes projectRoot: " + relativePath);
        }
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }

        String unixRelPath = normalizedRoot.relativize(target).toString().replace('\\', '/');
        return Optional.of(inspectResolvedFile(target, unixRelPath));
    }

    /**
     * Recursively inspects all regular files under {@code <projectRoot>/assets} and returns them
     * in a sorted, unmodifiable list ordered by {@link AssetMetadata#relativePath()}.
     *
     * @param projectRoot root directory of the KAGAYAKI project (must not be {@code null})
     * @return sorted unmodifiable list of {@link AssetMetadata} (empty if {@code assets/} is absent)
     * @throws IOException if {@code projectRoot} is not a directory or filesystem traversal/reading fails
     */
    public List<AssetMetadata> inspectAll(Path projectRoot) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot must not be null");
        Path normalizedRoot = requireProjectDirectory(projectRoot);

        Path assetsDir = normalizedRoot.resolve(ASSETS_DIR_NAME);
        if (!Files.exists(assetsDir) || !Files.isDirectory(assetsDir)) {
            return List.of();
        }

        List<AssetMetadata> results = new ArrayList<>();
        Files.walkFileTree(assetsDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    Path normalizedFile = file.toAbsolutePath().normalize();
                    String relPath = normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/');
                    results.add(inspectResolvedFile(normalizedFile, relPath));
                }
                return FileVisitResult.CONTINUE;
            }
        });

        results.sort(Comparator.comparing(AssetMetadata::relativePath));
        return Collections.unmodifiableList(results);
    }

    private static Path requireProjectDirectory(Path projectRoot) throws IOException {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        if (!Files.exists(normalizedRoot) || !Files.isDirectory(normalizedRoot)) {
            throw new IOException("Project directory does not exist or is not a directory: " + normalizedRoot);
        }
        return normalizedRoot;
    }

    private static AssetMetadata inspectResolvedFile(Path file, String relativePath) throws IOException {
        long byteSize = Files.size(file);
        String ext = extractExtension(relativePath);
        MediaType mediaType = classifyExtension(ext);

        Optional<Integer> widthPx = Optional.empty();
        Optional<Integer> heightPx = Optional.empty();
        Optional<Integer> sampleRateHz = Optional.empty();
        Optional<Integer> channels = Optional.empty();
        Optional<Double> durationSeconds = Optional.empty();

        if (mediaType == MediaType.IMAGE && ".png".equals(ext)) {
            PngDimensions dims = parsePngDimensions(file);
            widthPx = dims.widthPx();
            heightPx = dims.heightPx();
        } else if (mediaType == MediaType.AUDIO && (".wav".equals(ext) || ".wave".equals(ext))) {
            WavMetadata wav = parseWavMetadata(file);
            sampleRateHz = wav.sampleRateHz();
            channels = wav.channels();
            durationSeconds = wav.durationSeconds();
        }

        return new AssetMetadata(
                relativePath,
                mediaType,
                byteSize,
                widthPx,
                heightPx,
                sampleRateHz,
                channels,
                durationSeconds
        );
    }

    private static PngDimensions parsePngDimensions(Path file) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(PNG_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            if (!readFully(channel, header)) {
                return PngDimensions.EMPTY;
            }
        }
        header.flip();
        byte[] bytes = header.array();
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (bytes[i] != PNG_SIGNATURE[i]) {
                return PngDimensions.EMPTY;
            }
        }

        int ihdrLength = header.getInt(8);
        if (ihdrLength < 13) {
            return PngDimensions.EMPTY;
        }
        if (bytes[12] != 'I' || bytes[13] != 'H' || bytes[14] != 'D' || bytes[15] != 'R') {
            return PngDimensions.EMPTY;
        }

        int width = header.getInt(16);
        int height = header.getInt(20);
        if (width <= 0 || height <= 0) {
            return PngDimensions.EMPTY;
        }
        return new PngDimensions(Optional.of(width), Optional.of(height));
    }

    private static WavMetadata parseWavMetadata(Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < 12) {
                return WavMetadata.EMPTY;
            }

            ByteBuffer riffHeader = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            if (!readFully(channel, riffHeader)) {
                return WavMetadata.EMPTY;
            }
            byte[] riffBytes = riffHeader.array();
            if (!matchesAscii(riffBytes, 0, "RIFF") || !matchesAscii(riffBytes, 8, "WAVE")) {
                return WavMetadata.EMPTY;
            }

            boolean foundFmt = false;
            int channels = 0;
            int sampleRate = 0;
            long byteRate = 0;
            int blockAlign = 0;
            int bitsPerSample = 0;
            long dataChunkSize = -1;

            ByteBuffer chunkHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            long offset = 12;

            while (offset + 8 <= fileSize) {
                channel.position(offset);
                chunkHeader.clear();
                if (!readFully(channel, chunkHeader)) {
                    break;
                }
                chunkHeader.flip();
                byte[] headerBytes = chunkHeader.array();
                String chunkId = new String(headerBytes, 0, 4, StandardCharsets.US_ASCII);
                long chunkSize = Integer.toUnsignedLong(chunkHeader.getInt(4));
                long dataStart = offset + 8;

                if (chunkSize > fileSize - dataStart) {
                    break;
                }

                if ("fmt ".equals(chunkId) && chunkSize >= 16) {
                    ByteBuffer fmtBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
                    if (!readFully(channel, fmtBuf)) {
                        break;
                    }
                    fmtBuf.flip();
                    channels = Short.toUnsignedInt(fmtBuf.getShort(2));
                    sampleRate = fmtBuf.getInt(4);
                    byteRate = Integer.toUnsignedLong(fmtBuf.getInt(8));
                    blockAlign = Short.toUnsignedInt(fmtBuf.getShort(12));
                    bitsPerSample = Short.toUnsignedInt(fmtBuf.getShort(14));
                    foundFmt = true;
                } else if ("data".equals(chunkId)) {
                    dataChunkSize = chunkSize;
                }

                offset = dataStart + chunkSize;
                if ((chunkSize & 1L) != 0L && offset < fileSize) {
                    offset++;
                }
            }

            if (!foundFmt || channels <= 0 || sampleRate <= 0) {
                return WavMetadata.EMPTY;
            }

            Optional<Double> duration = Optional.empty();
            if (dataChunkSize >= 0) {
                long frameSizeBytes = blockAlign > 0
                        ? blockAlign
                        : (bitsPerSample > 0 ? (long) channels * (bitsPerSample / 8L) : 0L);
                double computed = -1.0;
                if (frameSizeBytes > 0) {
                    computed = ((double) dataChunkSize / frameSizeBytes) / sampleRate;
                } else if (byteRate > 0) {
                    computed = (double) dataChunkSize / byteRate;
                }
                if (Double.isFinite(computed) && computed >= 0.0) {
                    duration = Optional.of(computed);
                }
            }

            return new WavMetadata(Optional.of(sampleRate), Optional.of(channels), duration);
        }
    }

    private static boolean readFully(SeekableByteChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesAscii(byte[] bytes, int offset, String expected) {
        if (offset + expected.length() > bytes.length) {
            return false;
        }
        for (int i = 0; i < expected.length(); i++) {
            if (bytes[offset + i] != (byte) expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static String cleanRelativePath(String rawPath) {
        String s = rawPath.trim().replace('\\', '/');
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }

    private static String extractExtension(String path) {
        int slashIdx = path.lastIndexOf('/');
        String fileName = slashIdx >= 0 ? path.substring(slashIdx + 1) : path;
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIdx).toLowerCase(Locale.ROOT);
    }

    private static MediaType classifyExtension(String ext) {
        if (IMAGE_EXTENSIONS.contains(ext)) {
            return MediaType.IMAGE;
        }
        if (AUDIO_EXTENSIONS.contains(ext)) {
            return MediaType.AUDIO;
        }
        if (FONT_EXTENSIONS.contains(ext)) {
            return MediaType.FONT;
        }
        if (VIDEO_EXTENSIONS.contains(ext)) {
            return MediaType.VIDEO;
        }
        return MediaType.OTHER;
    }
}
