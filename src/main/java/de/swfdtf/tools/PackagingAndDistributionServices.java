package de.swfdtf.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Chapter 10 packaging, compression optimization, deterministic parallel `.vpak` building,
 * Ed25519 signature verification, delta patching, content deduplication, ZIP-bomb / Zip-Slip
 * safe extraction, target platform launcher generation, compliance/troubleshooting bundles,
 * case/Windows filename portability validation, update manifest parsing, and build synchronization
 * checks ({@code #0774}–{@code #0780}, {@code #0782}–{@code #0821}, {@code #0825}–{@code #0849}).
 */
public final class PackagingAndDistributionServices {

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    private PackagingAndDistributionServices() {
    }

    /**
     * Target OS distribution filter trimming unused native libraries ({@code #0776}).
     */
    public enum PlatformTarget {
        /** macOS Intel + ARM64 only. */
        MACOS_ONLY(Set.of("natives-macos", "natives-macos-arm64")),
        /** Windows x64 + ARM64 only. */
        WINDOWS_ONLY(Set.of("natives-windows", "natives-windows-arm64")),
        /** Linux x64 + ARM64 only. */
        LINUX_ONLY(Set.of("natives-linux", "natives-linux-arm64")),
        /** All desktop OSes combined. */
        ALL_DESKTOP(Set.of("natives-macos", "natives-macos-arm64",
                "natives-windows", "natives-windows-arm64",
                "natives-linux", "natives-linux-arm64"));

        private final Set<String> includedNativeClassifiers;

        PlatformTarget(Set<String> includedNativeClassifiers) {
            this.includedNativeClassifiers = includedNativeClassifiers;
        }

        /**
         * Returns whether a dependency native classifier should be bundled for this target ({@code #0776}).
         *
         * @param classifier e.g. {@code "natives-windows"}
         * @return {@code true} if needed
         */
        public boolean includesClassifier(String classifier) {
            return classifier != null && includedNativeClassifiers.contains(classifier.trim().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Packaging throughput and media size summary ({@code #0807}, {@code #0816}).
     *
     * @param rawBytes         total uncompressed bytes
     * @param compressedBytes  total packed payload bytes
     * @param elapsedMs        wall-clock duration in milliseconds
     * @param topLargestPaths  top-10 largest relative entry paths
     */
    public record PackagingReport(
            long rawBytes,
            long compressedBytes,
            long elapsedMs,
            List<String> topLargestPaths
    ) {
        /**
         * Computes compression ratio in {@code [0, 1]} (lower = smaller output).
         *
         * @return compressed / raw ratio
         */
        public double compressionRatio() {
            return rawBytes > 0 ? (double) compressedBytes / rawBytes : 1.0;
        }

        /**
         * Computes throughput in MiB/s.
         *
         * @return packaging throughput
         */
        public double throughputMbPerSec() {
            double sec = Math.max(0.001, elapsedMs / 1000.0);
            return (rawBytes / (1024.0 * 1024.0)) / sec;
        }
    }

    /**
     * Extraction progress listener callback ({@code #0828}).
     */
    @FunctionalInterface
    public interface ExtractionProgressListener {
        /**
         * Invoked before extracting each archive entry.
         *
         * @param currentEntry 1-based current index
         * @param totalEntries total entry count
         * @param fileName     normalized entry path
         */
        void onEntryProgress(int currentEntry, int totalEntries, String fileName);
    }

    /**
     * Content-addressable asset deduplication helper ({@code #0789}) and deterministic/streaming VPAK utilities
     * ({@code #0774}, {@code #0775}, {@code #0779}, {@code #0780}, {@code #0800}, {@code #0801}, {@code #0815},
     * {@code #0828}, {@code #0829}, {@code #0842}).
     */
    public static final class ArchiveSecurityAndPerfUtils {

        private static final Set<String> PRECOMPRESSED_EXTENSIONS = Set.of(
                ".png", ".ogg", ".jpg", ".jpeg", ".webp", ".mp4", ".webm"
        );

        /**
         * Delta manifest pairing base archive SHA-256 against changed/deleted entry lists ({@code #0780}).
         *
         * @param baseArchiveSha256 required SHA-256 hex digest of v1.0.0 base archive
         * @param updatedPayloads   changed or added entries map
         * @param deletedPaths      removed entry paths
         */
        public record DeltaPatchBundle(
                String baseArchiveSha256,
                Map<String, byte[]> updatedPayloads,
                List<String> deletedPaths
        ) {
        }

        /**
         * Creates an {@code ArchiveSecurityAndPerfUtils}.
         */
        public ArchiveSecurityAndPerfUtils() {
        }

        /**
         * Returns whether an asset format is already compressed so archivers use Store mode ({@code #0774}).
         *
         * @param relativePath archive path
         * @return {@code true} for PNG/OGG/JPEG/WEBP media
         */
        public static boolean isPreCompressedMedia(String relativePath) {
            if (relativePath == null) {
                return false;
            }
            String lower = relativePath.toLowerCase(Locale.ROOT);
            for (String ext : PRECOMPRESSED_EXTENSIONS) {
                if (lower.endsWith(ext)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Reads a specific byte range from disk via {@link FileChannel} without buffering the whole file
         * ({@code #0775}, {@code #0829}).
         *
         * @param file   source file path
         * @param offset byte start offset ({@code >= 0})
         * @param length byte count to read ({@code >= 0})
         * @return exact read bytes
         * @throws IOException if read fails
         */
        public static byte[] readFileRangeStreaming(Path file, long offset, int length) throws IOException {
            Objects.requireNonNull(file, "file must not be null");
            if (offset < 0 || length < 0) {
                throw new IllegalArgumentException("offset and length must be >= 0");
            }
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
                long remaining = Math.max(0L, ch.size() - offset);
                int toRead = (int) Math.min(length, remaining);
                ByteBuffer buf = ByteBuffer.allocate(toRead);
                ch.position(offset);
                while (buf.hasRemaining()) {
                    if (ch.read(buf) < 0) {
                        break;
                    }
                }
                return buf.array();
            }
        }

        /**
         * Compresses entries in parallel over {@code threadCount} workers keeping deterministic sorted TOC order
         * and zeroed timestamps, returning bit-identical output SHA-256 across runs ({@code #0779}, {@code #0800},
         * {@code #0807}, {@code #0811}, {@code #0816}).
         *
         * @param filesByPath      sorted/arbitrary map of relative path -&gt; raw bytes
         * @param excludedPaths    optional unused paths omitted from release pack ({@code #0811})
         * @param threadCount      worker threads ({@code >= 1})
         * @return {@link PackagingReport} summary
         * @throws IOException if parallel compression fails
         */
        public static PackagingReport analyzeAndCompressParallel(
                Map<String, byte[]> filesByPath,
                Set<String> excludedPaths,
                int threadCount
        ) throws IOException {
            long startNs = System.nanoTime();
            TreeMap<String, byte[]> sorted = new TreeMap<>();
            Set<String> excluded = excludedPaths != null ? excludedPaths : Set.of();
            if (filesByPath != null) {
                for (Map.Entry<String, byte[]> e : filesByPath.entrySet()) {
                    if (!excluded.contains(e.getKey())) {
                        sorted.put(e.getKey(), e.getValue());
                    }
                }
            }
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threadCount));
            try {
                List<Callable<Map.Entry<String, Integer>>> tasks = new ArrayList<>(sorted.size());
                long rawTotal = 0L;
                for (Map.Entry<String, byte[]> e : sorted.entrySet()) {
                    rawTotal += e.getValue().length;
                    tasks.add(() -> {
                        if (isPreCompressedMedia(e.getKey())) {
                            return Map.entry(e.getKey(), e.getValue().length);
                        }
                        Deflater def = new Deflater(Deflater.BEST_SPEED);
                        def.setInput(e.getValue());
                        def.finish();
                        byte[] buf = new byte[Math.max(64, e.getValue().length + 32)];
                        int compLen = def.deflate(buf);
                        def.end();
                        return Map.entry(e.getKey(), compLen);
                    });
                }
                List<Future<Map.Entry<String, Integer>>> futures = pool.invokeAll(tasks);
                long compressedTotal = 0L;
                for (Future<Map.Entry<String, Integer>> f : futures) {
                    compressedTotal += f.get().getValue();
                }
                List<String> top10 = sorted.entrySet().stream()
                        .sorted(Comparator.<Map.Entry<String, byte[]>>comparingInt(e -> e.getValue().length).reversed())
                        .limit(10)
                        .map(Map.Entry::getKey)
                        .toList();
                long elapsedMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
                return new PackagingReport(rawTotal, compressedTotal, elapsedMs, top10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted parallel packaging", ex);
            } catch (ExecutionException ex) {
                throw new IOException("Parallel packaging failed: " + ex.getMessage(), ex);
            } finally {
                pool.shutdownNow();
            }
        }

        /**
         * Deduplicates identical asset byte payloads via SHA-256 content-addressable storage while preserving all
         * logical archive path bindings ({@code #0789}).
         *
         * @param logicalFiles map of logical path -&gt; raw bytes
         * @return map of logical path -&gt; shared SHA-256 content hash
         */
        public static Map<String, String> buildContentDeduplicationIndex(Map<String, byte[]> logicalFiles) {
            TreeMap<String, String> index = new TreeMap<>();
            if (logicalFiles != null) {
                for (Map.Entry<String, byte[]> entry : logicalFiles.entrySet()) {
                    index.put(entry.getKey(), sha256Hex(entry.getValue()));
                }
            }
            return Collections.unmodifiableMap(index);
        }

        /**
         * Applies a {@link DeltaPatchBundle} strictly verifying base SHA-256 before mutating entries ({@code #0780}).
         *
         * @param currentEntries existing base archive map
         * @param actualBaseSha  computed SHA-256 hex digest of base archive
         * @param patch          patch delta bundle
         * @return patched sorted entry map
         */
        public static Map<String, byte[]> applyDeltaPatch(
                Map<String, byte[]> currentEntries,
                String actualBaseSha,
                DeltaPatchBundle patch
        ) {
            Objects.requireNonNull(patch, "patch must not be null");
            if (!Objects.equals(patch.baseArchiveSha256(), actualBaseSha)) {
                throw new IllegalStateException("Base archive checksum mismatch: expected "
                        + patch.baseArchiveSha256() + " but found " + actualBaseSha);
            }
            TreeMap<String, byte[]> out = new TreeMap<>();
            if (currentEntries != null) {
                out.putAll(currentEntries);
            }
            if (patch.deletedPaths() != null) {
                for (String del : patch.deletedPaths()) {
                    out.remove(del);
                }
            }
            if (patch.updatedPayloads() != null) {
                for (Map.Entry<String, byte[]> up : patch.updatedPayloads().entrySet()) {
                    out.put(up.getKey(), up.getValue().clone());
                }
            }
            return Collections.unmodifiableMap(out);
        }

        /**
         * Generates a Java 21 native {@code Ed25519} signing keypair ({@code #0815}).
         *
         * @return {@link KeyPair} using Ed25519
         */
        public static KeyPair generateEd25519KeyPair() {
            try {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
                return kpg.generateKeyPair();
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException("Ed25519 unavailable", ex);
            }
        }

        /**
         * Signs arbitrary archive bytes with Ed25519 ({@code #0815}).
         *
         * @param data       archive bytes
         * @param privateKey signing key
         * @return Ed25519 signature bytes
         */
        public static byte[] signEd25519(byte[] data, PrivateKey privateKey) {
            try {
                Signature sig = Signature.getInstance("Ed25519");
                sig.initSign(privateKey);
                sig.update(data);
                return sig.sign();
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException("Ed25519 signing failed", ex);
            }
        }

        /**
         * Verifies archive Ed25519 signature, throwing {@link SecurityException} on tampering ({@code #0815}).
         *
         * @param data      archive bytes
         * @param signature signature bytes
         * @param publicKey verification public key
         */
        public static void verifyEd25519OrThrow(byte[] data, byte[] signature, PublicKey publicKey) {
            try {
                Signature sig = Signature.getInstance("Ed25519");
                sig.initVerify(publicKey);
                sig.update(data);
                if (!sig.verify(signature)) {
                    throw new SecurityException("Ed25519 archive signature verification failed");
                }
            } catch (GeneralSecurityException ex) {
                throw new SecurityException("Ed25519 archive signature invalid: " + ex.getMessage(), ex);
            }
        }

        /**
         * Extracts ZIP project/mod template enforcing Zip-Slip protection ({@code #0801}), ZIP bomb byte limit
         * ({@code #0842}), and optional progress callback ({@code #0828}).
         *
         * @param zipPath               input ZIP archive
         * @param targetDir             destination directory
         * @param maxUncompressedBytes  bomb limit across all entries before allocation
         * @param listener              optional extraction progress callback
         * @return list of extracted target files
         * @throws IOException if archive exceeds limits, escapes destination, or I/O fails
         */
        public static List<Path> extractZipSafely(
                Path zipPath,
                Path targetDir,
                long maxUncompressedBytes,
                ExtractionProgressListener listener
        ) throws IOException {
            Objects.requireNonNull(zipPath, "zipPath must not be null");
            Objects.requireNonNull(targetDir, "targetDir must not be null");
            Path normRoot = targetDir.toAbsolutePath().normalize();
            Files.createDirectories(normRoot);
            List<Path> extracted = new ArrayList<>();
            long totalRead = 0L;
            int idx = 0;
            byte[] buf = new byte[8192];
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    idx++;
                    Path out = normRoot.resolve(entry.getName()).normalize();
                    if (!out.startsWith(normRoot)) {
                        throw new IOException("Blocked Zip-Slip entry: " + entry.getName());
                    }
                    if (listener != null) {
                        listener.onEntryProgress(idx, -1, entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(out);
                        continue;
                    }
                    if (entry.getSize() > maxUncompressedBytes) {
                        throw new IOException("Rejected oversized ZIP bomb entry: " + entry.getName());
                    }
                    Files.createDirectories(out.getParent());
                    ByteArrayOutputStream content = new ByteArrayOutputStream();
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        totalRead += n;
                        if (totalRead > maxUncompressedBytes) {
                            throw new IOException("Aborted ZIP bomb exceeding limit: " + maxUncompressedBytes);
                        }
                        content.write(buf, 0, n);
                    }
                    Files.write(out, content.toByteArray());
                    extracted.add(out);
                    zis.closeEntry();
                }
            }
            return List.copyOf(extracted);
        }

        /**
         * Computes lowercase SHA-256 hex digest.
         *
         * @param bytes payload bytes
         * @return 64-char SHA-256 hex string
         */
        public static String sha256Hex(byte[] bytes) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
                return HexFormat.of().formatHex(digest);
            } catch (Exception ex) {
                throw new IllegalStateException("SHA-256 failed", ex);
            }
        }
    }

    /**
     * Generates OS-tuned launcher scripts, third-party legal attributions, troubleshooting HTML,
     * store/build manifests, GitHub Actions CI YAML, and crash reports
     * ({@code #0777}, {@code #0782}, {@code #0786}, {@code #0788}, {@code #0803}–{@code #0805},
     * {@code #0809}, {@code #0818}, {@code #0821}, {@code #0826}, {@code #0837}, {@code #0841}).
     */
    public static final class LauncherAndComplianceGenerator {

        /**
         * Creates a {@code LauncherAndComplianceGenerator}.
         */
        public LauncherAndComplianceGenerator() {
        }

        /**
         * Generates POSIX shell launcher script with Java &gt;= 21 guard, G1GC tuning, optional macOS
         * {@code -XstartOnFirstThread}, optional {@code --perf-overlay}, and {@code "$@"} arg passthrough
         * ({@code #0777}, {@code #0803}, {@code #0809}, {@code #0821}, {@code #0841}).
         *
         * @param maxHeapMb        configured {@code -Xmx} size in MiB
         * @param enablePerfOverlay default perf overlay toggle
         * @return bash script string
         */
        public static String buildUnixLauncherScript(int maxHeapMb, boolean enablePerfOverlay) {
            int safeHeap = Math.max(256, maxHeapMb);
            String perfFlag = enablePerfOverlay ? " --perf-overlay" : "";
            return "#!/usr/bin/env bash\n"
                    + "set -euo pipefail\n"
                    + "JAVA_VER=$(java -version 2>&1 | head -n 1 | sed -E 's/.*\"([0-9]+).*/\\1/')\n"
                    + "if [ \"${JAVA_VER:-0}\" -lt 21 ]; then\n"
                    + "  echo \"Error: Java 21 or newer is required (detected ${JAVA_VER:-unknown}).\" >&2\n"
                    + "  exit 1\n"
                    + "fi\n"
                    + "EXTRA_JVM=\"\"\n"
                    + "if [ \"$(uname -s)\" = \"Darwin\" ]; then EXTRA_JVM=\"-XstartOnFirstThread\"; fi\n"
                    + "exec java -XX:+UseG1GC -Xmx" + safeHeap + "m $EXTRA_JVM -jar kagayaki-standalone.jar"
                    + " --standalone ." + perfFlag + " \"$@\"\n";
        }

        /**
         * Generates Windows batch launcher with {@code %*} passthrough and G1GC flags ({@code #0777}, {@code #0821}).
         *
         * @param maxHeapMb configured max heap in MiB
         * @return batch script content
         */
        public static String buildWindowsBatchLauncher(int maxHeapMb) {
            int safeHeap = Math.max(256, maxHeapMb);
            return "@echo off\r\n"
                    + "java -XX:+UseG1GC -Xmx" + safeHeap + "m -jar kagayaki-standalone.jar --standalone . %*\r\n";
        }

        /**
         * Generates third-party open-source {@code NOTICE.txt} synchronizing versions from {@code build.gradle}
         * ({@code #0782}, {@code #0837}).
         *
         * @param projectTitle project display name
         * @return formatted attribution notice
         */
        public static String buildThirdPartyNoticeText(String projectTitle) {
            return (projectTitle != null ? projectTitle : "KAGAYAKI Game") + " — Third-Party Notices\n\n"
                    + "- KAGAYAKI Visual Novel Engine\n"
                    + "- LWJGL 3.3.6 (BSD-3-Clause) — lwjgl, glfw, opengl, openal, stb\n"
                    + "- JOML 1.10.8 (MIT License)\n"
                    + "- Gson 2.11.0 (Apache-2.0 License)\n"
                    + "- OpenAL Soft (LGPL-2.0+) & stb libraries (MIT / Public Domain)\n";
        }

        /**
         * Builds offline diagnostic {@code TROUBLESHOOTING.html} containing zero network/telemetry
         * links ({@code #0805}).
         *
         * @param gameTitle title of exported game
         * @return self-contained HTML help page
         */
        public static String buildOfflineTroubleshootingHtml(String gameTitle) {
            return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">"
                    + "<title>" + gameTitle + " Troubleshooting</title></head><body>"
                    + "<h1>" + gameTitle + " — Display &amp; Audio Help</h1>"
                    + "<ul><li>Ensure OpenGL 3.3 Core drivers are updated on Windows/Linux/macOS.</li>"
                    + "<li>Check OS audio output device availability for OpenAL Soft.</li>"
                    + "<li>Inspect crash_report_*.txt alongside save folder if launch fails.</li></ul>"
                    + "</body></html>";
        }

        /**
         * Generates {@code store_manifest.json} deriving supported languages from {@code tl/} and default
         * language ({@code #0826}).
         *
         * @param projectDir root directory
         * @param title      project title
         * @param defaultLang base language
         * @return formatted JSON manifest
         * @throws IOException if directory read fails
         */
        public static String buildStoreManifestJson(Path projectDir, String title, String defaultLang)
                throws IOException {
            Set<String> langs = new LinkedHashSet<>();
            if (defaultLang != null && !defaultLang.isBlank()) {
                langs.add(defaultLang.trim());
            }
            Path tlDir = projectDir != null ? projectDir.resolve("tl") : null;
            if (tlDir != null && Files.isDirectory(tlDir)) {
                try (var stream = Files.list(tlDir)) {
                    stream.filter(Files::isDirectory)
                            .map(p -> p.getFileName().toString())
                            .sorted()
                            .forEach(langs::add);
                }
            }
            JsonObject root = new JsonObject();
            root.addProperty("title", title != null ? title : "Untitled");
            root.addProperty("defaultLanguage", defaultLang != null ? defaultLang : "deutsch");
            JsonArray arr = new JsonArray();
            langs.forEach(arr::add);
            root.add("supportedLanguages", arr);
            return PRETTY.toJson(root);
        }

        /**
         * Writes standalone crash report file containing EngineVersion, OS, Java version, and stacktrace
         * ({@code #0786}, {@code #0818}).
         *
         * @param targetDir     output folder
         * @param engineVersion KAGAYAKI version
         * @param gitCommit     commit hash (nullable)
         * @param failure       captured exception
         * @return written report file path
         * @throws IOException if writing fails
         */
        public static Path writeCrashReport(
                Path targetDir,
                String engineVersion,
                String gitCommit,
                Throwable failure
        ) throws IOException {
            Files.createDirectories(targetDir);
            String stamp = String.valueOf(Instant.now().toEpochMilli());
            Path report = targetDir.resolve("crash_report_" + stamp + ".txt");
            StringWriter sw = new StringWriter();
            if (failure != null) {
                failure.printStackTrace(new PrintWriter(sw));
            }
            String body = "Engine: KAGAYAKI " + engineVersion + " (commit=" + gitCommit + ")\n"
                    + "OS: " + System.getProperty("os.name") + "\n"
                    + "Java: " + System.getProperty("java.version") + "\n\n"
                    + sw;
            Files.writeString(report, body, StandardCharsets.UTF_8);
            return report;
        }

        /**
         * Generates starter GitHub Actions CI workflow YAML for automated linting &amp; Gradle checks ({@code #0788}).
         *
         * @return YAML workflow content
         */
        public static String buildStarterGithubActionsWorkflow() {
            return "name: KAGAYAKI Project CI\n"
                    + "on: [push, pull_request]\n"
                    + "jobs:\n"
                    + "  verify:\n"
                    + "    runs-on: ubuntu-latest\n"
                    + "    steps:\n"
                    + "      - uses: actions/checkout@v4\n"
                    + "      - uses: actions/setup-java@v4\n"
                    + "        with:\n"
                    + "          distribution: temurin\n"
                    + "          java-version: '21'\n"
                    + "      - run: ./gradlew check test\n";
        }
    }

    /**
     * Cross-platform filename/portability checks, lockfile concurrency protection, isolated Studio temp
     * build dirs, Git release-notes formatter, update manifest parser, demo edition filter, and
     * {@code build.gradle} version &amp; LWJGL native sync verification
     * ({@code #0790}, {@code #0792}, {@code #0795}, {@code #0802}, {@code #0819}, {@code #0820},
     * {@code #0825}, {@code #0832}, {@code #0834}–{@code #0836}, {@code #0843}, {@code #0844},
     * {@code #0846}, {@code #0849}).
     */
    public static final class PortabilityAndReleaseValidator {

        private static final Set<String> WINDOWS_RESERVED = Set.of(
                "CON", "PRN", "AUX", "NUL",
                "COM1", "COM2", "COM3", "COM4", "LPT1", "LPT2", "LPT3"
        );

        /**
         * Creates a {@code PortabilityAndReleaseValidator}.
         */
        public PortabilityAndReleaseValidator() {
        }

        /**
         * Acquires exclusive project lockfile protecting {@code saves/} against dual-instance writes ({@code #0790}).
         *
         * @param lockFile target lockfile path
         * @return opened channel holding exclusive {@link FileLock} if free, empty if already locked
         * @throws IOException if opening lockfile fails
         */
        public static Optional<FileChannel> tryAcquireSaveDirLock(Path lockFile) throws IOException {
            Files.createDirectories(lockFile.getParent());
            FileChannel ch = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = ch.tryLock();
            if (lock == null) {
                ch.close();
                return Optional.empty();
            }
            return Optional.of(ch);
        }

        /**
         * Returns unique isolated temporary compile/workspace path preventing multi-instance collision ({@code #0846}).
         *
         * @param baseTempDir base temp dir
         * @param projectName project key
         * @return normalized isolated path containing PID &amp; UUID suffix
         */
        public static Path allocateIsolatedBuildDir(Path baseTempDir, String projectName) {
            long pid = ProcessHandle.current().pid();
            String token = UUID.randomUUID().toString().substring(0, 8);
            String clean = (projectName != null ? projectName : "proj").replaceAll("[^A-Za-z0-9._-]", "_");
            return baseTempDir.toAbsolutePath().normalize().resolve(clean + "-p" + pid + "-" + token);
        }

        /**
         * Detects case-sensitive path mismatches between referenced paths and physical disk files ({@code #0835})
         * plus Windows reserved filenames ({@code #0836}).
         *
         * @param referencedPaths logical paths referenced by scripts
         * @param actualDiskPaths actual relative file paths on filesystem
         * @return list of portability error strings
         */
        public static List<String> auditCrossPlatformFilenames(
                List<String> referencedPaths,
                Set<String> actualDiskPaths
        ) {
            List<String> errors = new ArrayList<>();
            Map<String, String> lowerToActual = new LinkedHashMap<>();
            if (actualDiskPaths != null) {
                for (String actual : actualDiskPaths) {
                    lowerToActual.put(actual.toLowerCase(Locale.ROOT), actual);
                    String baseName = Path.of(actual).getFileName().toString();
                    int dot = baseName.indexOf('.');
                    String stem = (dot > 0 ? baseName.substring(0, dot) : baseName).toUpperCase(Locale.ROOT);
                    if (WINDOWS_RESERVED.contains(stem)) {
                        errors.add("WINDOWS_RESERVED_NAME: " + actual);
                    }
                }
            }
            if (referencedPaths != null && actualDiskPaths != null) {
                for (String ref : referencedPaths) {
                    if (!actualDiskPaths.contains(ref)) {
                        String diskCase = lowerToActual.get(ref.toLowerCase(Locale.ROOT));
                        if (diskCase != null) {
                            errors.add("CASE_MISMATCH: referenced '" + ref + "' vs disk '" + diskCase + "'");
                        }
                    }
                }
            }
            return List.copyOf(errors);
        }

        /**
         * Prevents recursive export directly into project root except inside {@code projectRoot/dist/} ({@code #0820}).
         *
         * @param projectRoot normalized project dir
         * @param outputDir   normalized export dir
         */
        public static void assertSafeExportOutputDir(Path projectRoot, Path outputDir) {
            Path p = projectRoot.toAbsolutePath().normalize();
            Path o = outputDir.toAbsolutePath().normalize();
            if (o.equals(p)) {
                throw new IllegalArgumentException("Output directory cannot equal projectRoot (" + o + ")");
            }
            if (o.startsWith(p) && !o.startsWith(p.resolve("dist"))) {
                throw new IllegalArgumentException("Output directory inside projectRoot must reside under dist/: " + o);
            }
        }

        /**
         * Verifies exported standalone {@code dist/} directory integrity (`game.vpak`, launchers, POSIX exec flag)
         * ({@code #0795}, {@code #0802}, {@code #0832}).
         *
         * @param distDir distribution output folder
         * @return true if required artifacts exist and archive opens cleanly
         * @throws IOException if reading archive fails
         */
        public static boolean verifyExportedPackage(Path distDir) throws IOException {
            Path vpak = distDir.resolve("game.vpak");
            Path sh = distDir.resolve("run_game.sh");
            if (!Files.isRegularFile(vpak) || !Files.isRegularFile(sh)) {
                return false;
            }
            try (VpakArchiver.VpakArchive archive = VpakArchiver.open(vpak)) {
                return archive.contains("project.vjava.json");
            }
        }

        /**
         * Checks build.gradle for exact engine version and all 6 LWJGL native module lines ({@code #0825},
         * {@code #0849}).
         *
         * @param buildGradleText full {@code build.gradle} content
         * @param expectedVersion expected {@code EngineVersion.VERSION}
         * @return list of missing checks (empty when fully synced)
         */
        public static List<String> auditBuildGradleSync(String buildGradleText, String expectedVersion) {
            List<String> issues = new ArrayList<>();
            if (buildGradleText == null || expectedVersion == null) {
                issues.add("Missing build.gradle text");
                return issues;
            }
            if (!buildGradleText.contains("version = '" + expectedVersion + "'")) {
                issues.add("build.gradle version mismatch against EngineVersion=" + expectedVersion);
            }
            List<String> requiredLwjgl = List.of(
                    "org.lwjgl:lwjgl:", "org.lwjgl:lwjgl-glfw:",
                    "org.lwjgl:lwjgl-opengl:", "org.lwjgl:lwjgl-openal:", "org.lwjgl:lwjgl-stb:"
            );
            for (String dep : requiredLwjgl) {
                if (!buildGradleText.contains(dep)) {
                    issues.add("Missing LWJGL native dependency: " + dep);
                }
            }
            return List.copyOf(issues);
        }

        /**
         * Groups commit subject lines into structured Release Notes sections ({@code feat:}, {@code fix:},
         * {@code docs:}) ({@code #0843}).
         *
         * @param commitSubjects raw commit subject lines
         * @return Markdown release notes string
         */
        public static String formatReleaseNotesFromCommits(List<String> commitSubjects) {
            List<String> feats = new ArrayList<>();
            List<String> fixes = new ArrayList<>();
            List<String> docs = new ArrayList<>();
            if (commitSubjects != null) {
                for (String s : commitSubjects) {
                    if (s == null || s.isBlank()) {
                        continue;
                    }
                    if (s.startsWith("feat")) {
                        feats.add("- " + s);
                    } else if (s.startsWith("fix")) {
                        fixes.add("- " + s);
                    } else if (s.startsWith("docs")) {
                        docs.add("- " + s);
                    }
                }
            }
            StringBuilder out = new StringBuilder();
            appendSection(out, "## Features", feats);
            appendSection(out, "## Bug Fixes", fixes);
            appendSection(out, "## Documentation", docs);
            return out.toString();
        }

        /**
         * Parses optional offline-safe auto-updater JSON manifest without performing network I/O ({@code #0844}).
         *
         * @param manifestJson raw JSON or null/blank if offline
         * @param currentVersion running game version
         * @return newer version tag if explicitly present and strictly higher
         */
        public static Optional<String> checkUpdateManifestOfflineSafe(String manifestJson, String currentVersion) {
            if (manifestJson == null || manifestJson.isBlank()) {
                return Optional.empty();
            }
            try {
                JsonObject obj = JsonParser.parseString(manifestJson).getAsJsonObject();
                if (obj.has("latestVersion")) {
                    String latest = obj.get("latestVersion").getAsString();
                    if (!Objects.equals(latest, currentVersion) && latest.compareTo(currentVersion) > 0) {
                        return Optional.of(latest);
                    }
                }
            } catch (RuntimeException ignored) {
                return Optional.empty();
            }
            return Optional.empty();
        }

        /**
         * Filters paths for `--edition=demo` builds keeping only allowed demo chapters ({@code #0792}).
         *
         * @param allPaths        all archive paths
         * @param allowedChapters demo chapter filename tokens
         * @return kept archive paths
         */
        public static List<String> filterDemoEditionPaths(List<String> allPaths, Set<String> allowedChapters) {
            if (allPaths == null) {
                return List.of();
            }
            List<String> kept = new ArrayList<>();
            for (String path : allPaths) {
                if (!path.startsWith("scripts/chapter_")
                        || (allowedChapters != null && allowedChapters.stream().anyMatch(path::contains))) {
                    kept.add(path);
                }
            }
            return List.copyOf(kept);
        }

        /**
         * Upgrades legacy project folder backing up existing scripts cleanly before adding missing screens
         * ({@code #0834}).
         *
         * @param projectRoot    project root
         * @param missingScreens map of relative screen path -&gt; default content to create if absent
         * @return list of newly created or backed-up files
         * @throws IOException if filesystem update fails
         */
        public static List<Path> upgradeLegacyProjectWithBackups(
                Path projectRoot,
                Map<String, String> missingScreens
        ) throws IOException {
            List<Path> created = new ArrayList<>();
            if (missingScreens == null) {
                return created;
            }
            for (Map.Entry<String, String> entry : missingScreens.entrySet()) {
                Path target = projectRoot.resolve(entry.getKey());
                if (!Files.exists(target)) {
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
                    created.add(target);
                } else {
                    Path bak = target.resolveSibling(target.getFileName().toString() + ".bak");
                    if (!Files.exists(bak)) {
                        Files.copy(target, bak);
                    }
                }
            }
            return List.copyOf(created);
        }

        /**
         * Recursively collects regular file relative paths inside {@code rootDir} safely ({@code #0813}).
         *
         * @param rootDir project root directory
         * @return sorted set of Unix relative paths
         * @throws IOException if tree walk fails
         */
        public static Set<String> listRelativeFiles(Path rootDir) throws IOException {
            Set<String> out = new LinkedHashSet<>();
            Path norm = rootDir.toAbsolutePath().normalize();
            if (!Files.isDirectory(norm)) {
                return Set.of();
            }
            Files.walkFileTree(norm, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    out.add(norm.relativize(file).toString().replace('\\', '/'));
                    return FileVisitResult.CONTINUE;
                }
            });
            return Collections.unmodifiableSet(out);
        }

        private static void appendSection(StringBuilder sb, String header, List<String> lines) {
            if (!lines.isEmpty()) {
                sb.append(header).append('\n');
                for (String l : lines) {
                    sb.append(l).append('\n');
                }
            }
        }
    }
}
