package de.swfdtf.state;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Storage SPI interface (without {@code I}-prefix) encapsulating raw save-slot byte persistence for
 * filesystem, in-memory test, and sandboxed web/portable deployments ({@code #0571}, {@code #0587}).
 */
public interface SaveRepository {

    /** Validation regex preventing path traversal outside the save root. */
    Pattern VALID_SLOT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    /**
     * Validates a slot identifier, rejecting {@code null}, blanks, path separators, and {@code ..}
     * traversal sequences ({@code #0571}).
     *
     * @param slotId candidate slot identifier
     * @return validated slot identifier
     */
    static String validateSlotId(String slotId) {
        if (slotId == null || slotId.isBlank() || slotId.contains("..")
                || slotId.contains("/") || slotId.contains("\\")
                || !VALID_SLOT_ID.matcher(slotId).matches()) {
            throw new IllegalArgumentException("Invalid or unsafe save slotId: " + slotId);
        }
        return slotId;
    }

    /**
     * Persists encoded save bytes atomically for {@code slotId}, rotating previous bytes into backup
     * storage when supported.
     *
     * @param slotId validated slot identifier
     * @param bytes  encoded payload bytes
     * @throws IOException if persistence fails
     */
    void write(String slotId, byte[] bytes) throws IOException;

    /**
     * Reads primary payload bytes for {@code slotId}.
     *
     * @param slotId validated slot identifier
     * @return payload bytes if present
     * @throws IOException if reading fails
     */
    Optional<byte[]> read(String slotId) throws IOException;

    /**
     * Reads backup ({@code .bak}) bytes for {@code slotId} when primary recovery is required.
     *
     * @param slotId validated slot identifier
     * @return backup payload bytes if present
     * @throws IOException if reading fails
     */
    Optional<byte[]> readBackup(String slotId) throws IOException;

    /**
     * Returns whether a primary save payload exists for {@code slotId}.
     *
     * @param slotId validated slot identifier
     * @return {@code true} if the slot exists
     * @throws IOException if probing storage fails
     */
    boolean exists(String slotId) throws IOException;

    /**
     * Deletes primary (and optionally backup) payload for {@code slotId}.
     *
     * @param slotId validated slot identifier
     * @return {@code true} if an entry was removed
     * @throws IOException if deletion fails
     */
    boolean delete(String slotId) throws IOException;

    /**
     * Lists all valid stored slot identifiers.
     *
     * @return list of slot identifiers
     * @throws IOException if listing storage fails
     */
    List<String> listSlotIds() throws IOException;

    /**
     * Pure RAM {@link SaveRepository} implementation for fast zero-I/O unit tests and sandboxes ({@code #0587}).
     */
    final class InMemorySaveRepository implements SaveRepository {

        private final Map<String, byte[]> slots = new LinkedHashMap<>();
        private final Map<String, byte[]> backups = new LinkedHashMap<>();

        /**
         * Creates an empty {@code InMemorySaveRepository}.
         */
        public InMemorySaveRepository() {
        }

        @Override
        public synchronized void write(String slotId, byte[] bytes) {
            String clean = validateSlotId(slotId);
            Objects.requireNonNull(bytes, "bytes must not be null");
            byte[] previous = slots.get(clean);
            if (previous != null) {
                backups.put(clean, previous.clone());
            }
            slots.put(clean, bytes.clone());
        }

        @Override
        public synchronized Optional<byte[]> read(String slotId) {
            String clean = validateSlotId(slotId);
            byte[] data = slots.get(clean);
            return data != null ? Optional.of(data.clone()) : Optional.empty();
        }

        @Override
        public synchronized Optional<byte[]> readBackup(String slotId) {
            String clean = validateSlotId(slotId);
            byte[] data = backups.get(clean);
            return data != null ? Optional.of(data.clone()) : Optional.empty();
        }

        @Override
        public synchronized boolean exists(String slotId) {
            String clean = validateSlotId(slotId);
            return slots.containsKey(clean);
        }

        @Override
        public synchronized boolean delete(String slotId) {
            String clean = validateSlotId(slotId);
            boolean removed = slots.remove(clean) != null;
            backups.remove(clean);
            return removed;
        }

        @Override
        public synchronized List<String> listSlotIds() {
            return List.copyOf(slots.keySet());
        }
    }

    /**
     * Atomic local-disk {@link SaveRepository} using temp+fsync+atomic move, per-slot write locks
     * ({@code #0588}), {@code .bak} rotation, and orphaned {@code .tmp} cleanup ({@code #0535}).
     */
    final class FileSaveRepository implements SaveRepository {

        private final Path rootDir;
        private final ConcurrentHashMap<String, Object> slotLocks = new ConcurrentHashMap<>();

        /**
         * Creates a {@code FileSaveRepository} rooted at {@code rootDir}.
         *
         * @param rootDir directory storing {@code .ksav} and {@code .bak} files
         */
        public FileSaveRepository(Path rootDir) {
            this.rootDir = Objects.requireNonNull(rootDir, "rootDir must not be null")
                    .toAbsolutePath()
                    .normalize();
        }

        /**
         * Deletes orphaned {@code .tmp} fragments in {@code savesDir} while keeping valid save,
         * JSON, and {@code .bak} files untouched ({@code #0535}).
         *
         * @param savesDir saves directory to sweep
         * @return count of removed orphaned {@code .tmp} files
         * @throws IOException if directory iteration fails
         */
        public static int cleanupOrphanedTempFiles(Path savesDir) throws IOException {
            Objects.requireNonNull(savesDir, "savesDir must not be null");
            if (!Files.isDirectory(savesDir)) {
                return 0;
            }
            int removed = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(savesDir, "*.tmp")) {
                for (Path tmp : stream) {
                    if (Files.isRegularFile(tmp) && Files.deleteIfExists(tmp)) {
                        removed++;
                    }
                }
            }
            return removed;
        }

        @Override
        public void write(String slotId, byte[] bytes) throws IOException {
            String clean = validateSlotId(slotId);
            Objects.requireNonNull(bytes, "bytes must not be null");
            Object lock = slotLocks.computeIfAbsent(clean, k -> new Object());
            synchronized (lock) {
                Files.createDirectories(rootDir);
                Path target = resolveSafe(clean + SaveManager.SAVE_EXTENSION);
                if (Files.exists(target)) {
                    Path backup = resolveSafe(clean + SaveManager.SAVE_EXTENSION + SaveManager.BACKUP_EXTENSION);
                    Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                }
                Path tempFile = Files.createTempFile(rootDir, clean + "-", ".tmp");
                boolean moved = false;
                try {
                    try (FileChannel ch = FileChannel.open(tempFile,
                            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        ByteBuffer buf = ByteBuffer.wrap(bytes);
                        while (buf.hasRemaining()) {
                            ch.write(buf);
                        }
                        ch.force(true);
                    }
                    try {
                        Files.move(tempFile, target,
                                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException ignored) {
                        Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    moved = true;
                } finally {
                    if (!moved) {
                        Files.deleteIfExists(tempFile);
                    }
                }
            }
        }

        @Override
        public Optional<byte[]> read(String slotId) throws IOException {
            String clean = validateSlotId(slotId);
            Path target = resolveSafe(clean + SaveManager.SAVE_EXTENSION);
            if (!Files.isRegularFile(target)) {
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(target));
        }

        @Override
        public Optional<byte[]> readBackup(String slotId) throws IOException {
            String clean = validateSlotId(slotId);
            Path backup = resolveSafe(clean + SaveManager.SAVE_EXTENSION + SaveManager.BACKUP_EXTENSION);
            if (!Files.isRegularFile(backup)) {
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(backup));
        }

        @Override
        public boolean exists(String slotId) {
            String clean = validateSlotId(slotId);
            return Files.isRegularFile(resolveSafe(clean + SaveManager.SAVE_EXTENSION));
        }

        @Override
        public boolean delete(String slotId) throws IOException {
            String clean = validateSlotId(slotId);
            Object lock = slotLocks.computeIfAbsent(clean, k -> new Object());
            synchronized (lock) {
                Path target = resolveSafe(clean + SaveManager.SAVE_EXTENSION);
                Path backup = resolveSafe(clean + SaveManager.SAVE_EXTENSION + SaveManager.BACKUP_EXTENSION);
                boolean removed = Files.deleteIfExists(target);
                Files.deleteIfExists(backup);
                return removed;
            }
        }

        @Override
        public List<String> listSlotIds() throws IOException {
            if (!Files.isDirectory(rootDir)) {
                return List.of();
            }
            List<String> ids = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(rootDir, "*" + SaveManager.SAVE_EXTENSION)) {
                for (Path p : stream) {
                    if (!Files.isRegularFile(p)) {
                        continue;
                    }
                    String name = p.getFileName().toString();
                    String id = name.substring(0, name.length() - SaveManager.SAVE_EXTENSION.length());
                    ids.add(id);
                }
            }
            return List.copyOf(ids);
        }

        private Path resolveSafe(String fileName) {
            Path resolved = rootDir.resolve(fileName).normalize();
            if (!resolved.startsWith(rootDir)) {
                throw new IllegalArgumentException("Resolved path escapes savesDir: " + fileName);
            }
            return resolved;
        }
    }
}
