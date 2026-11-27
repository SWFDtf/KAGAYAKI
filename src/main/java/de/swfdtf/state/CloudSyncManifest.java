package de.swfdtf.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Deterministic cross-platform cloud save manifest generator and verifier for Steam Cloud /
 * multi-device synchronization ({@code #0530}).
 *
 * <p>Stores strictly normalized relative Unix paths ({@code /}), UTF-8 JSON with LF newlines,
 * SHA-256 digests, and UTC ISO-8601 timestamps so saves authored on macOS, Windows, and Linux
 * exchange bit-cleanly.</p>
 */
public final class CloudSyncManifest {

    /** Schema version written to generated manifest documents. */
    public static final int SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * Deterministic cloud-sync entry describing a single save or metadata artifact.
     *
     * @param relativePath        normalized Unix relative path without traversal segments
     * @param byteSize            file byte length ({@code >= 0})
     * @param sha256Hex           lowercase 64-character SHA-256 hex digest
     * @param modifiedUtcIso8601  UTC ISO-8601 timestamp string
     */
    public record SaveFileEntry(
            String relativePath,
            long byteSize,
            String sha256Hex,
            String modifiedUtcIso8601
    ) {
        /** Validates normalized relative path and non-negative metadata. */
        public SaveFileEntry {
            relativePath = normalizeRelativePath(relativePath);
            if (byteSize < 0) {
                throw new IllegalArgumentException("byteSize must be >= 0");
            }
            Objects.requireNonNull(sha256Hex, "sha256Hex must not be null");
            Objects.requireNonNull(modifiedUtcIso8601, "modifiedUtcIso8601 must not be null");
        }
    }

    /**
     * Root UTF-8 JSON manifest describing a complete save directory snapshot.
     *
     * @param schemaVersion      manifest schema version ({@link #SCHEMA_VERSION})
     * @param generatedUtcIso8601 UTC ISO-8601 generation timestamp
     * @param engineVersion      engine version string recorded at export time
     * @param entries            immutable path-sorted list of {@link SaveFileEntry} records
     */
    public record ManifestDocument(
            int schemaVersion,
            String generatedUtcIso8601,
            String engineVersion,
            List<SaveFileEntry> entries
    ) {
        /** Enforces non-null fields and deterministic ordering. */
        public ManifestDocument {
            Objects.requireNonNull(generatedUtcIso8601, "generatedUtcIso8601 must not be null");
            engineVersion = engineVersion != null ? engineVersion : "unknown";
            entries = entries != null ? List.copyOf(entries) : List.of();
        }
    }

    /**
     * Verification summary comparing an on-disk saves directory against a {@link ManifestDocument}.
     *
     * @param valid          {@code true} when all manifest entries exist and match SHA-256 digests
     * @param missingPaths   relative paths listed in manifest but absent on disk
     * @param corruptedPaths relative paths present on disk with mismatched byte size or SHA-256 digest
     */
    public record VerificationResult(
            boolean valid,
            List<String> missingPaths,
            List<String> corruptedPaths
    ) {
        /** Normalizes null lists to immutable empty lists. */
        public VerificationResult {
            missingPaths = missingPaths != null ? List.copyOf(missingPaths) : List.of();
            corruptedPaths = corruptedPaths != null ? List.copyOf(corruptedPaths) : List.of();
        }
    }

    private CloudSyncManifest() {
    }

    /**
     * Normalizes an OS-specific path fragment into a safe canonical Unix relative path without
     * leading slashes, Windows drive letters, or {@code ..} traversal segments.
     *
     * @param rawPath raw path string (must not be {@code null} or blank)
     * @return canonical relative Unix path (e.g. {@code "slot_1.ksav"} or {@code "auto/auto_1.ksav"})
     */
    public static String normalizeRelativePath(String rawPath) {
        Objects.requireNonNull(rawPath, "rawPath must not be null");
        String normalized = rawPath.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.contains(":")) {
            throw new IllegalArgumentException("Cloud manifest path must be relative: " + rawPath);
        }
        String[] parts = normalized.split("/");
        List<String> clean = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new IllegalArgumentException("Path traversal not permitted in cloud manifest: " + rawPath);
            }
            clean.add(part);
        }
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Cloud manifest path must not be empty: " + rawPath);
        }
        return String.join("/", clean);
    }

    /**
     * Scans {@code savesDir} deterministically and constructs a cross-device {@link ManifestDocument}.
     *
     * @param savesDir      saves root directory (must not be {@code null})
     * @param engineVersion engine version string recorded inside the manifest
     * @return deterministic {@link ManifestDocument}
     * @throws IOException if reading save files fails
     */
    public static ManifestDocument buildFromDirectory(Path savesDir, String engineVersion) throws IOException {
        Objects.requireNonNull(savesDir, "savesDir must not be null");
        Path root = savesDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return new ManifestDocument(SCHEMA_VERSION, Instant.now().toString(), engineVersion, List.of());
        }

        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> isSyncRelevantFile(p.getFileName().toString()))
                    .sorted(Comparator.comparing(p -> toManifestRelativePath(root, p)))
                    .toList();
        }

        List<SaveFileEntry> entries = new ArrayList<>(files.size());
        for (Path file : files) {
            byte[] bytes = Files.readAllBytes(file);
            String rel = toManifestRelativePath(root, file);
            String hash = sha256Hex(bytes);
            String utc = Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis()).toString();
            entries.add(new SaveFileEntry(rel, bytes.length, hash, utc));
        }

        return new ManifestDocument(SCHEMA_VERSION, Instant.now().toString(), engineVersion, entries);
    }

    /**
     * Serializes {@code manifest} as canonical UTF-8 JSON using LF newlines only.
     *
     * @param manifest manifest document (must not be {@code null})
     * @return canonical UTF-8 JSON string
     */
    public static String toCanonicalJson(ManifestDocument manifest) {
        Objects.requireNonNull(manifest, "manifest must not be null");
        return GSON.toJson(manifest).replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * Parses a UTF-8 JSON manifest document.
     *
     * @param json JSON string (must not be {@code null})
     * @return parsed {@link ManifestDocument}
     */
    public static ManifestDocument fromJson(String json) {
        Objects.requireNonNull(json, "json must not be null");
        try {
            ManifestDocument doc = GSON.fromJson(json, ManifestDocument.class);
            if (doc == null) {
                throw new IllegalArgumentException("Empty CloudSyncManifest JSON");
            }
            return new ManifestDocument(
                    doc.schemaVersion(),
                    doc.generatedUtcIso8601(),
                    doc.engineVersion(),
                    doc.entries()
            );
        } catch (JsonParseException ex) {
            throw new IllegalArgumentException("Invalid CloudSyncManifest JSON: " + ex.getMessage(), ex);
        }
    }

    /**
     * Resolves a manifest relative path safely against a host OS saves root directory.
     *
     * @param targetSavesRoot      target host OS directory
     * @param manifestRelativePath normalized relative manifest path
     * @return normalized child {@link Path} inside {@code targetSavesRoot}
     */
    public static Path resolvePortablePath(Path targetSavesRoot, String manifestRelativePath) {
        Objects.requireNonNull(targetSavesRoot, "targetSavesRoot must not be null");
        String rel = normalizeRelativePath(manifestRelativePath);
        Path base = targetSavesRoot.toAbsolutePath().normalize();
        Path resolved = base;
        for (String segment : rel.split("/")) {
            resolved = resolved.resolve(segment);
        }
        resolved = resolved.normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Resolved path escapes target saves root: " + manifestRelativePath);
        }
        return resolved;
    }

    /**
     * Verifies all files listed in {@code manifest} exist under {@code savesDir} with matching
     * byte counts and SHA-256 digests.
     *
     * @param savesDir target saves directory
     * @param manifest manifest to verify against
     * @return {@link VerificationResult} detailing missing or corrupted files
     * @throws IOException if file reading fails
     */
    public static VerificationResult verifyDirectory(Path savesDir, ManifestDocument manifest) throws IOException {
        Objects.requireNonNull(savesDir, "savesDir must not be null");
        Objects.requireNonNull(manifest, "manifest must not be null");
        List<String> missing = new ArrayList<>();
        List<String> corrupted = new ArrayList<>();

        for (SaveFileEntry entry : manifest.entries()) {
            Path target = resolvePortablePath(savesDir, entry.relativePath());
            if (!Files.isRegularFile(target)) {
                missing.add(entry.relativePath());
                continue;
            }
            byte[] bytes = Files.readAllBytes(target);
            if (bytes.length != entry.byteSize() || !sha256Hex(bytes).equals(entry.sha256Hex())) {
                corrupted.add(entry.relativePath());
            }
        }
        return new VerificationResult(missing.isEmpty() && corrupted.isEmpty(), missing, corrupted);
    }

    private static boolean isSyncRelevantFile(String fileName) {
        return fileName.endsWith(SaveManager.SAVE_EXTENSION)
                || "persistent.dat".equals(fileName)
                || "preferences.json".equals(fileName);
    }

    private static String toManifestRelativePath(Path root, Path target) {
        return normalizeRelativePath(root.relativize(target.toAbsolutePath().normalize()).toString());
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
