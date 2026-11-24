package de.swfdtf.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Generates and verifies deterministic SHA-256 asset checksum manifests for all files under
 * {@code <projectRoot>/assets}.
 */
public final class AssetChecksumVerifier {

    /** Default hash algorithm used for asset manifests. */
    public static final String DEFAULT_ALGORITHM = "SHA-256";

    private static final String ASSETS_DIR_NAME = "assets";
    private static final int IO_BUFFER_SIZE = 8192;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HexFormat HEX_FORMAT = HexFormat.of();

    /**
     * Immutable manifest mapping sorted {@code projectRoot}-relative forward-slash asset paths
     * to hexadecimal digest strings.
     *
     * @param algorithm message digest algorithm name (e.g., {@code "SHA-256"})
     * @param checksums immutable sorted map of relative asset path to hex digest
     */
    public record AssetManifest(String algorithm, Map<String, String> checksums) {

        /**
         * Validates manifest fields and enforces deterministic unmodifiable map ordering.
         */
        public AssetManifest {
            Objects.requireNonNull(algorithm, "algorithm must not be null");
            if (algorithm.isBlank()) {
                throw new IllegalArgumentException("algorithm must not be blank");
            }
            TreeMap<String, String> sorted = new TreeMap<>();
            if (checksums != null) {
                for (Map.Entry<String, String> entry : checksums.entrySet()) {
                    String path = Objects.requireNonNull(entry.getKey(), "manifest path key must not be null");
                    String hash = Objects.requireNonNull(entry.getValue(), "manifest checksum value must not be null");
                    sorted.put(path, hash);
                }
            }
            checksums = Collections.unmodifiableMap(sorted);
        }

        /**
         * Serializes this manifest to formatted JSON.
         *
         * @return JSON representation of this manifest
         */
        public String toJson() {
            return GSON.toJson(this);
        }

        /**
         * Parses an {@link AssetManifest} from a JSON string.
         *
         * @param json JSON manifest payload (must not be {@code null})
         * @return parsed immutable {@link AssetManifest}
         * @throws IllegalArgumentException if {@code json} is malformed or missing required fields
         */
        public static AssetManifest fromJson(String json) {
            Objects.requireNonNull(json, "json must not be null");
            try {
                AssetManifest parsed = GSON.fromJson(json, AssetManifest.class);
                if (parsed == null || parsed.algorithm() == null) {
                    throw new IllegalArgumentException("Invalid asset manifest JSON: missing algorithm");
                }
                return new AssetManifest(parsed.algorithm(), parsed.checksums());
            } catch (JsonSyntaxException e) {
                throw new IllegalArgumentException("Invalid asset manifest JSON: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Immutable integrity verification report comparing current files under {@code <projectRoot>/assets}
     * against an expected {@link AssetManifest}.
     *
     * @param validCount      number of manifest entries matching on-disk digests
     * @param missingFiles    immutable sorted list of expected asset paths missing on disk
     * @param modifiedFiles   immutable sorted list of asset paths whose digest differs from the manifest
     * @param unexpectedFiles immutable sorted list of extra asset paths present on disk but absent from the manifest
     */
    public record VerificationReport(
            int validCount,
            List<String> missingFiles,
            List<String> modifiedFiles,
            List<String> unexpectedFiles
    ) {

        /**
         * Validates report counts and wraps file lists in unmodifiable copies.
         */
        public VerificationReport {
            if (validCount < 0) {
                throw new IllegalArgumentException("validCount must be >= 0, got " + validCount);
            }
            missingFiles = missingFiles != null ? List.copyOf(missingFiles) : List.of();
            modifiedFiles = modifiedFiles != null ? List.copyOf(modifiedFiles) : List.of();
            unexpectedFiles = unexpectedFiles != null ? List.copyOf(unexpectedFiles) : List.of();
        }

        /**
         * Returns {@code true} when all manifest entries match and no missing, modified, or
         * unexpected asset files were found.
         *
         * @return {@code true} if the assets directory matches the manifest completely
         */
        public boolean isIntact() {
            return missingFiles.isEmpty() && modifiedFiles.isEmpty() && unexpectedFiles.isEmpty();
        }
    }

    /**
     * Creates a new {@code AssetChecksumVerifier}.
     */
    public AssetChecksumVerifier() {
    }

    /**
     * Scans {@code <projectRoot>/assets} and computes a deterministic SHA-256 {@link AssetManifest}.
     *
     * @param projectRoot root directory of the KAGAYAKI project (must not be {@code null})
     * @return generated {@link AssetManifest}
     * @throws IOException if {@code projectRoot} is not a directory or reading asset files fails
     */
    public AssetManifest generateManifest(Path projectRoot) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot must not be null");
        Map<String, String> checksums = computeDirectoryChecksums(projectRoot, DEFAULT_ALGORITHM);
        return new AssetManifest(DEFAULT_ALGORITHM, checksums);
    }

    /**
     * Verifies all files currently under {@code <projectRoot>/assets} against {@code manifest}.
     *
     * @param projectRoot root directory of the KAGAYAKI project (must not be {@code null})
     * @param manifest    expected asset checksum manifest (must not be {@code null})
     * @return {@link VerificationReport} detailing valid, missing, modified, and unexpected files
     * @throws IOException if {@code projectRoot} is not a directory or reading asset files fails
     */
    public VerificationReport verify(Path projectRoot, AssetManifest manifest) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot must not be null");
        Objects.requireNonNull(manifest, "manifest must not be null");

        Map<String, String> actual = computeDirectoryChecksums(projectRoot, manifest.algorithm());
        Map<String, String> expected = manifest.checksums();

        int validCount = 0;
        List<String> missing = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        List<String> unexpected = new ArrayList<>();

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String path = entry.getKey();
            String expectedHash = entry.getValue();
            String actualHash = actual.get(path);
            if (actualHash == null) {
                missing.add(path);
            } else if (!expectedHash.equalsIgnoreCase(actualHash)) {
                modified.add(path);
            } else {
                validCount++;
            }
        }

        for (String actualPath : actual.keySet()) {
            if (!expected.containsKey(actualPath)) {
                unexpected.add(actualPath);
            }
        }

        return new VerificationReport(validCount, missing, modified, unexpected);
    }

    private static Map<String, String> computeDirectoryChecksums(Path projectRoot, String algorithm)
            throws IOException {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        if (!Files.exists(normalizedRoot) || !Files.isDirectory(normalizedRoot)) {
            throw new IOException("Project directory does not exist or is not a directory: " + normalizedRoot);
        }

        // Validate algorithm availability early even when assets/ is empty or absent.
        createDigest(algorithm);

        Path assetsDir = normalizedRoot.resolve(ASSETS_DIR_NAME);
        if (!Files.exists(assetsDir) || !Files.isDirectory(assetsDir)) {
            return Collections.emptyMap();
        }

        Map<String, String> checksums = new TreeMap<>();
        Files.walkFileTree(assetsDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    String relativePath = normalizedRoot.relativize(file.toAbsolutePath().normalize())
                            .toString()
                            .replace('\\', '/');
                    checksums.put(relativePath, computeFileDigestHex(file, algorithm));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return checksums;
    }

    private static String computeFileDigestHex(Path file, String algorithm) throws IOException {
        MessageDigest digest = createDigest(algorithm);
        byte[] buffer = new byte[IO_BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HEX_FORMAT.formatHex(digest.digest());
    }

    private static MessageDigest createDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("Unsupported digest algorithm: " + algorithm, e);
        }
    }
}
