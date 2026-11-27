package de.swfdtf.tools;

import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.parser.CompiledScriptBundle;
import de.swfdtf.parser.JavaBlockCompiler;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.VJavaAst;

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
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Binary {@code .vpak} asset and script archive packer/reader for KAGAYAKI standalone builds.
 *
 * <p>Uses a compact binary container ({@code MAGIC = "VPAK"}, {@code FORMAT_VERSION = 1}) with
 * per-entry Deflate compression and CRC32 integrity verification.</p>
 */
public final class VpakArchiver {

    /** 4-byte ASCII magic header identifying a KAGAYAKI {@code .vpak} archive. */
    public static final String MAGIC = "VPAK";

    /** Current unencrypted binary container format version. */
    public static final int FORMAT_VERSION = 1;

    /** AES-CTR encrypted binary container format version. */
    public static final int FORMAT_VERSION_ENCRYPTED = 2;

    /** Virtual archive path holding precompiled {@code .vjava} label indices and bytecode ({@code #0080}). */
    public static final String PRECOMPILED_SCRIPTS_ENTRY = "META-INF/compiled-scripts.vjc";

    private static final byte[] MAGIC_BYTES = MAGIC.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ENCRYPTED_TOC_MAGIC = "VPAKTOC2".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_SIZE = 20;
    private static final int SALT_SIZE = 16;
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
        return pack(projectDir, outputVpak, null);
    }

    /**
     * Packs a KAGAYAKI project directory into a compressed {@code .vpak} archive at {@code outputVpak},
     * optionally encrypting compressed payloads and the table of contents with AES-256 CTR when
     * {@code encryptionKey} is non-blank.
     *
     * @param projectDir    project root directory
     * @param outputVpak    destination {@code .vpak} file path
     * @param encryptionKey optional passphrase to enable {@link #FORMAT_VERSION_ENCRYPTED} (nullable/blank = v1)
     * @return immutable list of packed entry descriptors in deterministic archive order
     * @throws IOException if scanning, compressing, encrypting, or writing the archive fails
     */
    public static List<VpakEntryInfo> pack(
            Path projectDir,
            Path outputVpak,
            String encryptionKey
    ) throws IOException {
        return pack(projectDir, outputVpak, encryptionKey, Map.of());
    }

    /**
     * Scans and precompiles all {@code .vjava} scripts in {@code projectDir} into JVM bytecode and
     * packs the serialized {@link CompiledScriptBundle} at {@link #PRECOMPILED_SCRIPTS_ENTRY} inside
     * the resulting {@code .vpak} archive for instant release boot without runtime {@code javac}
     * ({@code #0080}).
     *
     * @param projectDir    project root directory
     * @param outputVpak    destination {@code .vpak} file path
     * @param encryptionKey optional passphrase to enable {@link #FORMAT_VERSION_ENCRYPTED} (nullable/blank = v1)
     * @return immutable list of packed entry descriptors including {@link #PRECOMPILED_SCRIPTS_ENTRY}
     * @throws IOException if scanning, compiling, or writing the archive fails
     */
    public static List<VpakEntryInfo> packWithPrecompiledScripts(
            Path projectDir,
            Path outputVpak,
            String encryptionKey
    ) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Path normalizedProject = projectDir.toAbsolutePath().normalize();
        VJavaAst.ProjectAst ast = ProjectScanner.scan(normalizedProject);
        JavaBlockCompiler compiler = new JavaBlockCompiler();
        JavaBlockCompiler.CompiledBytecodePackage bytecode = compiler.compileToBytecode(ast);
        byte[] bundleBytes = CompiledScriptBundle.serialize(ast, bytecode);
        return pack(projectDir, outputVpak, encryptionKey, Map.of(PRECOMPILED_SCRIPTS_ENTRY, bundleBytes));
    }

    private static List<VpakEntryInfo> pack(
            Path projectDir,
            Path outputVpak,
            String encryptionKey,
            Map<String, byte[]> syntheticEntries
    ) throws IOException {
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

        boolean encrypted = encryptionKey != null && !encryptionKey.isBlank();
        byte[] aesKey = encrypted ? deriveAesKey(encryptionKey) : null;
        List<Path> filesToPack = collectPackableFiles(normalizedProject, normalizedOutput);
        int totalEntries = filesToPack.size() + syntheticEntries.size();
        byte[] salt = encrypted ? deriveSalt(encryptionKey, totalEntries) : null;
        int version = encrypted ? FORMAT_VERSION_ENCRYPTED : FORMAT_VERSION;
        long preambleSize = encrypted ? HEADER_SIZE + SALT_SIZE : HEADER_SIZE;

        List<StoredEntry> toc = new ArrayList<>(totalEntries);

        try (FileChannel channel = FileChannel.open(
                normalizedOutput,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            writeFully(channel, ByteBuffer.allocate((int) preambleSize));
            long currentOffset = preambleSize;

            for (Path file : filesToPack) {
                String relPath = toUnixRelativePath(normalizedProject, file);
                currentOffset = writeArchiveEntry(
                        channel, toc, currentOffset, relPath, Files.readAllBytes(file), encrypted, aesKey, salt);
            }
            for (Map.Entry<String, byte[]> synthetic : syntheticEntries.entrySet()) {
                String relPath = normalizeArchivePath(synthetic.getKey());
                currentOffset = writeArchiveEntry(
                        channel, toc, currentOffset, relPath, synthetic.getValue(), encrypted, aesKey, salt);
            }

            long tocOffset = currentOffset;
            ByteBuffer tocBuffer = serializeToc(toc);
            if (encrypted) {
                byte[] rawToc = new byte[tocBuffer.remaining()];
                tocBuffer.get(rawToc);
                byte[] withMagic = new byte[ENCRYPTED_TOC_MAGIC.length + rawToc.length];
                System.arraycopy(ENCRYPTED_TOC_MAGIC, 0, withMagic, 0, ENCRYPTED_TOC_MAGIC.length);
                System.arraycopy(rawToc, 0, withMagic, ENCRYPTED_TOC_MAGIC.length, rawToc.length);
                byte[] encToc = aesCtrTransform(withMagic, aesKey, deriveTocIv(salt));
                writeFully(channel, ByteBuffer.wrap(encToc));
            } else {
                writeFully(channel, tocBuffer);
            }

            channel.position(0);
            ByteBuffer preamble = ByteBuffer.allocate((int) preambleSize).order(ByteOrder.BIG_ENDIAN);
            preamble.put(MAGIC_BYTES);
            preamble.putInt(version);
            preamble.putInt(toc.size());
            preamble.putLong(tocOffset);
            if (encrypted) {
                preamble.put(salt);
            }
            preamble.flip();
            writeFully(channel, preamble);
        }

        return toc.stream().map(StoredEntry::info).toList();
    }

    private static long writeArchiveEntry(
            FileChannel channel,
            List<StoredEntry> toc,
            long currentOffset,
            String relPath,
            byte[] rawBytes,
            boolean encrypted,
            byte[] aesKey,
            byte[] salt
    ) throws IOException {
        long crc = computeCrc32(rawBytes);
        byte[] compressed = deflate(rawBytes);
        byte[] storedPayload = encrypted
                ? aesCtrTransform(compressed, aesKey, deriveEntryIv(salt, relPath))
                : compressed;
        writeFully(channel, ByteBuffer.wrap(storedPayload));
        VpakEntryInfo info = new VpakEntryInfo(relPath, rawBytes.length, storedPayload.length, crc);
        toc.add(new StoredEntry(info, currentOffset));
        return currentOffset + storedPayload.length;
    }

    /**
     * Opens an existing {@code .vpak} archive for indexed reading and CRC32-verified extraction.
     *
     * @param vpakFile path to the {@code .vpak} archive
     * @return opened {@link VpakArchive} handle
     * @throws IOException if the file cannot be opened or has an invalid header/TOC
     */
    public static VpakArchive open(Path vpakFile) throws IOException {
        return open(vpakFile, null);
    }

    /**
     * Opens an existing {@code .vpak} archive (v1 plain or v2 AES-CTR encrypted) for indexed
     * reading and CRC32-verified extraction.
     *
     * @param vpakFile      path to the {@code .vpak} archive
     * @param decryptionKey optional passphrase required when the archive uses {@link #FORMAT_VERSION_ENCRYPTED}
     * @return opened {@link VpakArchive} handle
     * @throws IOException if the file cannot be opened, has an invalid key, or has a corrupt header/TOC
     */
    public static VpakArchive open(Path vpakFile, String decryptionKey) throws IOException {
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
            if (version != FORMAT_VERSION && version != FORMAT_VERSION_ENCRYPTED) {
                throw new IOException("Unsupported VPAK format version: " + version);
            }
            boolean encrypted = version == FORMAT_VERSION_ENCRYPTED;
            long minDataOffset = encrypted ? HEADER_SIZE + SALT_SIZE : HEADER_SIZE;

            int entryCount = header.getInt();
            long tocOffset = header.getLong();
            if (entryCount < 0 || tocOffset < minDataOffset || tocOffset > fileSize) {
                throw new IOException("Corrupt VPAK header metadata in " + normalized);
            }

            byte[] aesKey = null;
            byte[] salt = null;
            if (encrypted) {
                if (decryptionKey == null || decryptionKey.isBlank()) {
                    throw new IOException("Encrypted VPAK archive requires a decryption key (--vpak-key).");
                }
                aesKey = deriveAesKey(decryptionKey);
                salt = new byte[SALT_SIZE];
                ByteBuffer saltBuf = ByteBuffer.wrap(salt);
                readFully(channel, HEADER_SIZE, saltBuf);
            }

            long tocLengthLong = fileSize - tocOffset;
            if (tocLengthLong > Integer.MAX_VALUE) {
                throw new IOException("VPAK table of contents exceeds maximum buffer size");
            }
            long minTocBytes = (long) entryCount * 37L + (encrypted ? ENCRYPTED_TOC_MAGIC.length : 0L);
            if ((encrypted && tocLengthLong < ENCRYPTED_TOC_MAGIC.length)
                    || (entryCount > 0 && minTocBytes > tocLengthLong)) {
                throw new IOException(
                        "VPAK integrity/decryption error: invalid key or corrupt TOC in " + normalized);
            }

            ByteBuffer rawTocBuf = ByteBuffer.allocate((int) tocLengthLong).order(ByteOrder.BIG_ENDIAN);
            readFully(channel, tocOffset, rawTocBuf);
            rawTocBuf.flip();

            ByteBuffer tocBuf;
            if (encrypted) {
                byte[] decrypted = aesCtrTransform(rawTocBuf.array(), aesKey, deriveTocIv(salt));
                byte[] prefix = Arrays.copyOfRange(decrypted, 0, ENCRYPTED_TOC_MAGIC.length);
                if (!Arrays.equals(prefix, ENCRYPTED_TOC_MAGIC)) {
                    throw new IOException(
                            "VPAK decryption error: wrong decryption key or corrupted archive TOC in "
                                    + normalized);
                }
                tocBuf = ByteBuffer.wrap(
                                decrypted,
                                ENCRYPTED_TOC_MAGIC.length,
                                decrypted.length - ENCRYPTED_TOC_MAGIC.length)
                        .order(ByteOrder.BIG_ENDIAN);
            } else {
                tocBuf = rawTocBuf;
            }

            Map<String, StoredEntry> entries = new LinkedHashMap<>(Math.min(entryCount, 1024));
            for (int i = 0; i < entryCount; i++) {
                if (tocBuf.remaining() < 4) {
                    throw new IOException("Truncated VPAK table of contents at entry " + i);
                }
                int pathLen = tocBuf.getInt();
                if (pathLen <= 0 || pathLen > 4096 || pathLen > tocBuf.remaining()) {
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

                if (dataOffset < minDataOffset
                        || dataOffset > tocOffset
                        || uncompressedSize < 0
                        || compressedSize < 0
                        || compressedSize > tocOffset - dataOffset) {
                    throw new IOException("Corrupt VPAK offsets/sizes for entry '" + entryPath + "'");
                }

                VpakEntryInfo info = new VpakEntryInfo(entryPath, uncompressedSize, compressedSize, crc32);
                entries.put(entryPath, new StoredEntry(info, dataOffset));
            }
            if (tocBuf.hasRemaining()) {
                throw new IOException("Corrupt trailing bytes in VPAK table of contents: " + normalized);
            }
            return new VpakArchive(channel, entries, aesKey, salt);
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
        private final byte[] aesKey;
        private final byte[] salt;
        private boolean closed = false;

        private VpakArchive(FileChannel channel, Map<String, StoredEntry> entries, byte[] aesKey, byte[] salt) {
            this.channel = channel;
            this.entries = entries;
            this.aesKey = aesKey;
            this.salt = salt;
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
            if (aesKey != null) {
                compressed = aesCtrTransform(compressed, aesKey, deriveEntryIv(salt, normalized));
            }

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
                if (relPath.indexOf('\0') >= 0 || (relPath.length() >= 2 && relPath.charAt(1) == ':')) {
                    throw new IOException("Blocked unsafe VPAK entry path outside targetDir: " + relPath);
                }
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
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(expectedSize, IO_BUFFER_SIZE));
            byte[] buffer = new byte[IO_BUFFER_SIZE];
            int totalWritten = 0;
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count > 0) {
                    if (totalWritten + count > expectedSize) {
                        throw new IOException("Decompressed size mismatch for VPAK entry '" + entryPath + "'");
                    }
                    out.write(buffer, 0, count);
                    totalWritten += count;
                } else {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
            }
            if (!inflater.finished()) {
                byte[] extra = new byte[1];
                int extraCount = inflater.inflate(extra);
                if (extraCount > 0 || !inflater.finished()) {
                    throw new IOException("Decompressed size mismatch for VPAK entry '" + entryPath + "'");
                }
            }
            if (totalWritten != expectedSize) {
                throw new IOException(
                        "Truncated decompressed data for VPAK entry '" + entryPath
                                + "' (expected " + expectedSize + " bytes, got " + totalWritten + ")"
                );
            }
            return out.toByteArray();
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

    private static byte[] deriveAesKey(String encryptionKey) throws IOException {
        return sha256(encryptionKey.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] deriveSalt(String encryptionKey, int entryCount) throws IOException {
        byte[] hash = sha256(("KAGAYAKI-VPAK2-SALT:" + encryptionKey + ":" + entryCount)
                .getBytes(StandardCharsets.UTF_8));
        return Arrays.copyOf(hash, SALT_SIZE);
    }

    private static byte[] deriveTocIv(byte[] salt) throws IOException {
        return deriveIvWithLabel(salt, "TOC");
    }

    private static byte[] deriveEntryIv(byte[] salt, String relPath) throws IOException {
        return deriveIvWithLabel(salt, "ENTRY:" + relPath);
    }

    private static byte[] deriveIvWithLabel(byte[] salt, String label) throws IOException {
        byte[] labelBytes = label.getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[salt.length + labelBytes.length];
        System.arraycopy(salt, 0, combined, 0, salt.length);
        System.arraycopy(labelBytes, 0, combined, salt.length, labelBytes.length);
        return Arrays.copyOf(sha256(combined), 16);
    }

    private static byte[] sha256(byte[] input) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("SHA-256 unavailable for VPAK encryption", ex);
        }
    }

    private static byte[] aesCtrTransform(byte[] payload, byte[] keyBytes, byte[] ivBytes) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(ivBytes));
            return cipher.doFinal(payload);
        } catch (GeneralSecurityException ex) {
            throw new IOException("VPAK AES-CTR cipher failure: " + ex.getMessage(), ex);
        }
    }
}
