package de.swfdtf.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Inspects KAGAYAKI {@code .vpak} archives via {@link VpakArchiver#open(Path)}, verifying
 * every entry's decompression and CRC32 integrity while aggregating byte statistics by
 * file extension.
 */
public final class VpakManifestInspector {

    private static final String NO_EXTENSION_KEY = "(none)";

    /**
     * Aggregated inspection and integrity verification report for a {@code .vpak} archive.
     *
     * @param vpakPath               normalized path to the inspected archive
     * @param entryCount             total number of entries listed in the archive table of contents
     * @param totalUncompressedBytes sum of uncompressed byte sizes across all entries
     * @param totalCompressedBytes   sum of compressed payload byte sizes across all entries
     * @param compressionRatio       ratio of compressed to uncompressed bytes ({@code 0.0} when empty)
     * @param bytesByExtension       unmodifiable map of lowercase file extension to total uncompressed bytes
     * @param corruptedEntries       unmodifiable list of entry paths whose decompression or CRC32 check failed
     * @param integrityVerified      {@code true} when all entries passed decompression and CRC32 verification
     */
    public record VpakInspectionReport(
            Path vpakPath,
            int entryCount,
            long totalUncompressedBytes,
            long totalCompressedBytes,
            double compressionRatio,
            Map<String, Long> bytesByExtension,
            List<String> corruptedEntries,
            boolean integrityVerified
    ) {
        public VpakInspectionReport {
            Objects.requireNonNull(vpakPath, "vpakPath must not be null");
            if (entryCount < 0) {
                throw new IllegalArgumentException("entryCount must be >= 0, got " + entryCount);
            }
            if (totalUncompressedBytes < 0) {
                throw new IllegalArgumentException("totalUncompressedBytes must be >= 0");
            }
            if (totalCompressedBytes < 0) {
                throw new IllegalArgumentException("totalCompressedBytes must be >= 0");
            }
            if (!Double.isFinite(compressionRatio) || compressionRatio < 0.0) {
                throw new IllegalArgumentException("compressionRatio must be finite and >= 0.0");
            }
            bytesByExtension = bytesByExtension != null
                    ? Collections.unmodifiableMap(new TreeMap<>(bytesByExtension))
                    : Map.of();
            corruptedEntries = corruptedEntries != null ? List.copyOf(corruptedEntries) : List.of();
        }
    }

    /**
     * Creates a new {@code VpakManifestInspector}.
     */
    public VpakManifestInspector() {
    }

    /**
     * Opens {@code vpakFile}, reads and verifies every entry using {@link VpakArchiver.VpakArchive#readEntry(String)},
     * and returns an immutable {@link VpakInspectionReport}.
     *
     * @param vpakFile path to the {@code .vpak} archive file (must not be {@code null})
     * @return immutable {@link VpakInspectionReport} with size totals, extension breakdown, and corruption list
     * @throws IOException if the archive file cannot be opened or has an invalid header/table of contents
     */
    public VpakInspectionReport inspect(Path vpakFile) throws IOException {
        Objects.requireNonNull(vpakFile, "vpakFile must not be null");
        Path normalizedPath = vpakFile.toAbsolutePath().normalize();

        try (VpakArchiver.VpakArchive archive = VpakArchiver.open(normalizedPath)) {
            List<VpakArchiver.VpakEntryInfo> entries = archive.listEntries();
            long totalUncompressed = 0L;
            long totalCompressed = 0L;
            Map<String, Long> bytesByExt = new TreeMap<>();
            List<String> corrupted = new ArrayList<>();

            for (VpakArchiver.VpakEntryInfo entry : entries) {
                totalUncompressed += entry.uncompressedSize();
                totalCompressed += entry.compressedSize();

                String ext = extractExtension(entry.path());
                bytesByExt.merge(ext, entry.uncompressedSize(), Long::sum);

                try {
                    archive.readEntry(entry.path());
                } catch (IOException | RuntimeException ex) {
                    corrupted.add(entry.path());
                }
            }

            double ratio = totalUncompressed > 0L
                    ? (double) totalCompressed / (double) totalUncompressed
                    : 0.0;
            boolean verified = corrupted.isEmpty();

            return new VpakInspectionReport(
                    normalizedPath,
                    entries.size(),
                    totalUncompressed,
                    totalCompressed,
                    ratio,
                    bytesByExt,
                    corrupted,
                    verified
            );
        }
    }

    private static String extractExtension(String entryPath) {
        if (entryPath == null || entryPath.isBlank()) {
            return NO_EXTENSION_KEY;
        }
        int slashIdx = entryPath.lastIndexOf('/');
        String fileName = slashIdx >= 0 ? entryPath.substring(slashIdx + 1) : entryPath;
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx <= 0 || dotIdx >= fileName.length() - 1) {
            return NO_EXTENSION_KEY;
        }
        return fileName.substring(dotIdx + 1).toLowerCase(Locale.ROOT);
    }
}
