package de.swfdtf.tools;

import de.swfdtf.core.ProjectDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Binary {@code .vpak} asset and script archive packer/reader for KAGAYAKI standalone builds.
 *
 * <p>Uses a compact binary container ({@code MAGIC = "VPAK"}, {@code FORMAT_VERSION = 1}) with
 * per-entry Deflate compression and CRC32 integrity verification.</p>
 */
public final class VpakArchiver {

    /** 4-byte ASCII magic header identifying a KAGAYAKI {@code .vpak} archive. */
    public static final String MAGIC = "VPAK";

    /** Current binary container format version. */
    public static final int FORMAT_VERSION = 1;

    private static final byte[] MAGIC_BYTES = MAGIC.getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_SIZE = 20;
    private static final Set<String> EXCLUDED_DIRS = Set.of("saves", ".git", "build", "dist");
    private static final int IO_BUFFER_SIZE = 8192;

    /**
     * Metadata describing a single packed entry inside a {@code .vpak} archive.
     *
     * @param path             normalized Unix-style relative archive path
     * @param uncompressedSize original uncompressed byte size
     * @param compressedSize   Deflate-compressed payload byte size
     * @param crc32            unsigned CRC32 checksum of the uncompressed payload
     */
    public record VpakEntryInfo(
            String path,
            long uncompressedSize,
            long compressedSize,
            long crc32
    ) {
    }

    private record StoredEntry(
            VpakEntryInfo info,
            long dataOffset
    ) {
    }

    private VpakArchiver() {
    }

    /**
     * Packs a KAGAYAKI project directory into a compressed {@code .vpak} archive at {@code outputVpak}.
     *
     * <p>Includes {@code project.vjava.json}, all {@code **}{@code /*.vjava} scripts, {@code assets/**},
     * and {@code tl/**}, excluding {@code saves/}, {@code .git/}, {@code build/}, and {@code dist/}.</p>
     *
     * @param projectDir project root directory
     * @param outputVpak destination {@code .vpak} file path
     * @return immutable list of packed entry descriptors in deterministic archive order
     * @throws IOException if scanning, compressing, or writing the archive fails
     */
    public static List<VpakEntryInfo> pack(Path projectDir, Path outputVpak) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Objects.requireNonNull(outputVpak, "outputVpak must not be null");

        Path normalizedProject = projectDir.toAbsolutePath().normalize();
        if (!Files.exists(normalizedProject) || !Files.isDirectory(normalizedProject)) {
            throw new IOException("Project directory does not exist or is not a directory: " + normalizedProject);
        }

        Path normalizedOutput = outputVpak.toAbsolutePath().normalize();
        Path outputParent = normalizedOutput.getParent();
        if (outputParent != null) {
            Files.createDirectories(outputParent);
        }

        List<Path> filesToPack = collectPackableFiles(normalizedProject, normalizedOutput);
        List<StoredEntry> toc = new ArrayList<>(filesToPack.size());

        try (FileChannel channel = FileChannel.open(
                normalizedOutput,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            writeFully(channel, ByteBuffer.allocate(HEADER_SIZE));
            long currentOffset = HEADER_SIZE;

            for (Path file : filesToPack) {
                String relPath = toUnixRelativePath(normalizedProject, file);
                byte[] rawBytes = Files.readAllBytes(file);
                long crc = computeCrc32(rawBytes);
                byte[] compressed = deflate(rawBytes);

                writeFully(channel, ByteBuffer.wrap(compressed));
                VpakEntryInfo info = new VpakEntryInfo(relPath, rawBytes.length, compressed.length, crc);
                toc.add(new StoredEntry(info, currentOffset));
                currentOffset += compressed.length;
            }

            long tocOffset = currentOffset;
            ByteBuffer tocBuffer = serializeToc(toc);
            writeFully(channel, tocBuffer);

            channel.position(0);
            ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
            header.put(MAGIC_BYTES);
            header.putInt(FORMAT_VERSION);
            header.putInt(toc.size());
            header.putLong(tocOffset);
            header.flip();
            writeFully(channel, header);
        }

        return toc.stream().map(StoredEntry::info).toList();
    }

    /**
     * Opens an existing {@code .vpak} archive for indexed reading and CRC32-verified extraction.
     *
     * @param vpakFile path to the {@code .vpak} archive
     * @return opened {@link VpakArchive} handle
     * @throws IOException if the file cannot be opened or has an invalid header/TOC
     */
    public static VpakArchive open(Path vpakFile) throws IOException {
        Objects.requireNonNull(vpakFile, "vpakFile must not be null");
        Path normalized = vpakFile.toAbsolutePath().normalize();
        FileChannel channel = FileChannel.open(normalized, StandardOpenOption.READ);
        try {
            long fileSize = channel.size();
            if (fileSize < HEADER_SIZE) {
                throw new IOException("Invalid VPAK archive (too small): " + normalized);
            }
            ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
            readFully(channel, 0L, header);
            header.flip();

            byte[] magic = new byte[4];
            header.get(magic);
            if (!Arrays.equals(magic, MAGIC_BYTES)) {
                throw new IOException("Invalid VPAK magic header in " + normalized);
            }
            int version = header.getInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported VPAK format version: " + version);
            }
            int entryCount = header.getInt();
            long tocOffset = header.getLong();
            if (entryCount < 0 || tocOffset < HEADER_SIZE || tocOffset > fileSize) {
                throw new IOException("Corrupt VPAK header metadata in " + normalized);
            }

            long tocLengthLong = fileSize - tocOffset;
            if (tocLengthLong > Integer.MAX_VALUE) {
                throw new IOException("VPAK table of contents exceeds maximum buffer size");
            }
            ByteBuffer tocBuf = ByteBuffer.allocate((int) tocLengthLong).order(ByteOrder.BIG_ENDIAN);
            readFully(channel, tocOffset, tocBuf);
            tocBuf.flip();

            Map<String, StoredEntry> entries = new LinkedHashMap<>(entryCount);
            for (int i = 0; i < entryCount; i++) {
                if (tocBuf.remaining() < 4) {
                    throw new IOException("Truncated VPAK table of contents at entry " + i);
                }
                int pathLen = tocBuf.getInt();
                if (pathLen <= 0 || pathLen > tocBuf.remaining()) {
                    throw new IOException("Invalid VPAK entry path length at entry " + i);
                }
                byte[] pathBytes = new byte[pathLen];
                tocBuf.get(pathBytes);
                String entryPath = normalizeArchivePath(new String(pathBytes, StandardCharsets.UTF_8));
                if (tocBuf.remaining() < 32) {
                    throw new IOException("Truncated VPAK entry metadata for '" + entryPath + "'");
                }
                long dataOffset = tocBuf.getLong();
                long uncompressedSize = tocBuf.getLong();
                long compressedSize = tocBuf.getLong();
                long crc32 = tocBuf.getLong();

                if (dataOffset < HEADER_SIZE
                        || uncompressedSize < 0
                        || compressedSize < 0
                        || dataOffset + compressedSize > tocOffset) {
                    throw new IOException("Corrupt VPAK offsets/sizes for entry '" + entryPath + "'");
                }

                VpakEntryInfo info = new VpakEntryInfo(entryPath, uncompressedSize, compressedSize, crc32);
                entries.put(entryPath, new StoredEntry(info, dataOffset));
            }
            return new VpakArchive(channel, entries);
        } catch (IOException | RuntimeException ex) {
            channel.close();
            throw ex;
        }
    }

    /**
     * Open handle for reading and verifying entries from a {@code .vpak} archive.
     */
    public static final class VpakArchive implements AutoCloseable {

        private final FileChannel channel;
        private final Map<String, StoredEntry> entries;
        private boolean closed = false;

        private VpakArchive(FileChannel channel, Map<String, StoredEntry> entries) {
            this.channel = channel;
            this.entries = entries;
        }

        /**
         * Returns an immutable list of all entry descriptors in archive order.
         *
         * @return list of {@link VpakEntryInfo}
         */
        public List<VpakEntryInfo> listEntries() {
            ensureOpen();
            return entries.values().stream().map(StoredEntry::info).toList();
        }

        /**
         * Returns whether an entry exists at {@code path} in this archive.
         *
         * @param path relative archive path
         * @return {@code true} if present
         */
        public boolean contains(String path) {
            ensureOpen();
            if (path == null || path.isBlank()) {
                return false;
            }
            return entries.containsKey(normalizeArchivePath(path));
        }

        /**
         * Reads, decompresses, and CRC32-verifies the payload for {@code path}.
         *
         * @param path relative archive path
         * @return uncompressed entry bytes
         * @throws IOException if the entry is missing, decompression fails, or CRC32 verification fails
         */
        public byte[] readEntry(String path) throws IOException {
            ensureOpen();
            Objects.requireNonNull(path, "path must not be null");
            String normalized = normalizeArchivePath(path);
            StoredEntry stored = entries.get(normalized);
            if (stored == null) {
                throw new NoSuchFileException("Entry not found in VPAK archive: " + normalized);
            }
            VpakEntryInfo info = stored.info();
            if (info.compressedSize() > Integer.MAX_VALUE || info.uncompressedSize() > Integer.MAX_VALUE) {
                throw new IOException("Entry exceeds single-array size limit: " + normalized);
            }

            ByteBuffer compressedBuf = ByteBuffer.allocate((int) info.compressedSize());
            readFully(channel, stored.dataOffset(), compressedBuf);
            byte[] compressed = compressedBuf.array();

            byte[] uncompressed = inflate(compressed, (int) info.uncompressedSize(), normalized);
            long actualCrc = computeCrc32(uncompressed);
            if (actualCrc != info.crc32()) {
                throw new IOException(
                        "CRC32 integrity check failed for VPAK entry '" + normalized
                                + "' (expected=" + Long.toHexString(info.crc32())
                                + ", actual=" + Long.toHexString(actualCrc) + ")"
                );
            }
            return uncompressed;
        }

        /**
         * Extracts and CRC32-verifies all archive entries into {@code targetDir}.
         *
         * @param targetDir destination root directory
         * @throws IOException if extraction fails, path traversal is attempted, or integrity checks fail
         */
        public void extractAll(Path targetDir) throws IOException {
            ensureOpen();
            Objects.requireNonNull(targetDir, "targetDir must not be null");
            Path normalizedTarget = targetDir.toAbsolutePath().normalize();
            Files.createDirectories(normalizedTarget);

            for (StoredEntry stored : entries.values()) {
                String relPath = stored.info().path();
                Path outPath = normalizedTarget.resolve(relPath).normalize();
                if (!outPath.startsWith(normalizedTarget)) {
                    throw new IOException("Blocked unsafe VPAK entry path outside targetDir: " + relPath);
                }
                Path parent = outPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                byte[] bytes = readEntry(relPath);
                Files.write(outPath, bytes);
            }
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                channel.close();
            }
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("VpakArchive is already closed");
            }
        }
    }

    private static List<Path> collectPackableFiles(Path projectRoot, Path outputVpak) throws IOException {
        List<Path> collected = new ArrayList<>();
        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(projectRoot)) {
                    Path name = dir.getFileName();
                    if (name != null && EXCLUDED_DIRS.contains(name.toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                Path normalizedFile = file.toAbsolutePath().normalize();
                if (normalizedFile.equals(outputVpak)) {
                    return FileVisitResult.CONTINUE;
                }
                String rel = toUnixRelativePath(projectRoot, normalizedFile);
                if (shouldPackRelativePath(rel)) {
                    collected.add(normalizedFile);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        collected.sort(Comparator.comparing(p -> toUnixRelativePath(projectRoot, p)));
        return collected;
    }

    private static boolean shouldPackRelativePath(String relPath) {
        return ProjectDescriptor.FILE_NAME.equals(relPath)
                || relPath.endsWith(".vjava")
                || relPath.startsWith("assets/")
                || relPath.startsWith("tl/");
    }

    private static ByteBuffer serializeToc(List<StoredEntry> entries) {
        int totalBytes = 0;
        byte[][] pathByteArrays = new byte[entries.size()][];
        for (int i = 0; i < entries.size(); i++) {
            byte[] pathBytes = entries.get(i).info().path().getBytes(StandardCharsets.UTF_8);
            pathByteArrays[i] = pathBytes;
            totalBytes += 4 + pathBytes.length + 32;
        }
        ByteBuffer buf = ByteBuffer.allocate(totalBytes).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < entries.size(); i++) {
            StoredEntry stored = entries.get(i);
            byte[] pathBytes = pathByteArrays[i];
            buf.putInt(pathBytes.length);
            buf.put(pathBytes);
            buf.putLong(stored.dataOffset());
            buf.putLong(stored.info().uncompressedSize());
            buf.putLong(stored.info().compressedSize());
            buf.putLong(stored.info().crc32());
        }
        buf.flip();
        return buf;
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(input);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, input.length / 2));
            byte[] buffer = new byte[IO_BUFFER_SIZE];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                if (count > 0) {
                    out.write(buffer, 0, count);
                } else if (deflater.needsInput()) {
                    break;
                }
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] compressed, int expectedSize, String entryPath) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] output = new byte[expectedSize];
            int offset = 0;
            while (!inflater.finished() && offset < expectedSize) {
                int count = inflater.inflate(output, offset, expectedSize - offset);
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                offset += count;
            }
            if (!inflater.finished()) {
                byte[] extra = new byte[1];
                int extraCount = inflater.inflate(extra);
                if (extraCount > 0 || !inflater.finished()) {
                    throw new IOException("Decompressed size mismatch for VPAK entry '" + entryPath + "'");
                }
            }
            if (offset != expectedSize) {
                throw new IOException(
                        "Truncated decompressed data for VPAK entry '" + entryPath
                                + "' (expected " + expectedSize + " bytes, got " + offset + ")"
                );
            }
            return output;
        } catch (DataFormatException ex) {
            throw new IOException("Corrupt compressed data for VPAK entry '" + entryPath + "': " + ex.getMessage(), ex);
        } finally {
            inflater.end();
        }
    }

    private static long computeCrc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        return crc.getValue();
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void readFully(FileChannel channel, long position, ByteBuffer buffer) throws IOException {
        long pos = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, pos);
            if (read < 0) {
                throw new EOFException("Unexpected end of VPAK file at offset " + pos);
            }
            pos += read;
        }
    }

    private static String toUnixRelativePath(Path root, Path target) {
        return root.relativize(target.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String normalizeArchivePath(String raw) {
        String s = raw.trim().replace('\\', '/');
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }
}
