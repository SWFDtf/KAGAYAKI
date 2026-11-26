package de.swfdtf.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Crash-safe auto-recovery service periodically checkpointing dirty Studio editor buffers at a
 * 30-second interval and restoring unsaved changes after abrupt termination ({@code #0150}).
 */
public final class StudioWorkspaceAutoRecovery {

    /** Default auto-save backup cadence in seconds. */
    public static final double DEFAULT_BACKUP_INTERVAL_SECONDS = 30.0;

    private static final String BACKUP_DIR_NAME = ".studio_autosave";

    private final Path recoveryDir;
    private final double backupIntervalSeconds;
    private double accumulatorSeconds = 0.0;

    /**
     * Creates an auto-recovery manager storing temporary backups under
     * {@code projectRoot/.studio_autosave} using {@link #DEFAULT_BACKUP_INTERVAL_SECONDS}.
     *
     * @param projectRoot project root directory
     */
    public StudioWorkspaceAutoRecovery(Path projectRoot) {
        this(projectRoot, DEFAULT_BACKUP_INTERVAL_SECONDS);
    }

    /**
     * Creates an auto-recovery manager with custom backup interval.
     *
     * @param projectRoot           project root directory
     * @param backupIntervalSeconds interval between periodic backups ({@code > 0})
     */
    public StudioWorkspaceAutoRecovery(Path projectRoot, double backupIntervalSeconds) {
        Objects.requireNonNull(projectRoot, "projectRoot must not be null");
        if (!Double.isFinite(backupIntervalSeconds) || backupIntervalSeconds <= 0.0) {
            throw new IllegalArgumentException("backupIntervalSeconds must be > 0");
        }
        this.recoveryDir = projectRoot.toAbsolutePath().normalize().resolve(BACKUP_DIR_NAME);
        this.backupIntervalSeconds = backupIntervalSeconds;
    }

    /**
     * Advances the backup timer and flushes all provided dirty buffers whenever the 30s interval elapses.
     *
     * @param dt           elapsed frame delta in seconds
     * @param dirtyBuffers map of target script path to unsaved editor text
     * @return number of backup files written on this tick ({@code 0} if interval not yet reached)
     * @throws IOException if writing backup snapshots fails
     */
    public int tick(double dt, Map<Path, String> dirtyBuffers) throws IOException {
        if (dt <= 0.0 || dirtyBuffers == null || dirtyBuffers.isEmpty()) {
            return 0;
        }
        accumulatorSeconds += dt;
        if (accumulatorSeconds < backupIntervalSeconds) {
            return 0;
        }
        accumulatorSeconds = 0.0;
        int written = 0;
        for (Map.Entry<Path, String> entry : dirtyBuffers.entrySet()) {
            writeBackup(entry.getKey(), entry.getValue());
            written++;
        }
        return written;
    }

    /**
     * Immediately writes an auto-recovery snapshot for {@code scriptFile}.
     *
     * @param scriptFile target script file
     * @param content    unsaved buffer contents
     * @return path to written recovery file
     * @throws IOException if writing fails
     */
    public Path writeBackup(Path scriptFile, String content) throws IOException {
        Objects.requireNonNull(scriptFile, "scriptFile must not be null");
        Files.createDirectories(recoveryDir);
        Path backupPath = backupFileFor(scriptFile);
        Files.writeString(backupPath, content != null ? content : "", StandardCharsets.UTF_8);
        return backupPath;
    }

    /**
     * Checks whether a recovery snapshot exists for {@code scriptFile}.
     *
     * @param scriptFile target script file
     * @return {@code true} if an unsaved backup snapshot exists
     */
    public boolean hasBackup(Path scriptFile) {
        Objects.requireNonNull(scriptFile, "scriptFile must not be null");
        return Files.isRegularFile(backupFileFor(scriptFile));
    }

    /**
     * Reads a pending recovery snapshot for {@code scriptFile}, if one exists.
     *
     * @param scriptFile target script file
     * @return optional recovered buffer text
     * @throws IOException if reading fails
     */
    public Optional<String> restoreBackup(Path scriptFile) throws IOException {
        Objects.requireNonNull(scriptFile, "scriptFile must not be null");
        Path backupPath = backupFileFor(scriptFile);
        if (!Files.isRegularFile(backupPath)) {
            return Optional.empty();
        }
        return Optional.of(Files.readString(backupPath, StandardCharsets.UTF_8));
    }

    /**
     * Deletes the recovery snapshot for {@code scriptFile} (typically after an explicit {@code Ctrl+S} save).
     *
     * @param scriptFile saved script file
     * @throws IOException if deletion fails
     */
    public void clearBackup(Path scriptFile) throws IOException {
        Objects.requireNonNull(scriptFile, "scriptFile must not be null");
        Files.deleteIfExists(backupFileFor(scriptFile));
    }

    /**
     * Discovers all existing recovery snapshot files mapped by filename.
     *
     * @return map of backup filename to snapshot contents
     * @throws IOException if scanning fails
     */
    public Map<String, String> listPendingBackups() throws IOException {
        if (!Files.isDirectory(recoveryDir)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        try (var stream = Files.list(recoveryDir)) {
            for (Path p : stream.filter(Files::isRegularFile).sorted().toList()) {
                out.put(p.getFileName().toString(), Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        return Map.copyOf(out);
    }

    private Path backupFileFor(Path scriptFile) {
        String key = scriptFile.toAbsolutePath().normalize().toString();
        String hash;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            hash = HexFormat.of().formatHex(md.digest(key.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            hash = Integer.toHexString(key.hashCode());
        }
        String baseName = scriptFile.getFileName() != null ? scriptFile.getFileName().toString() : "script";
        return recoveryDir.resolve(baseName + "." + hash + ".autosave");
    }
}
