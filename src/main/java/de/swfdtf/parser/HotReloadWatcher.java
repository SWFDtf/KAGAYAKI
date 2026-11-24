package de.swfdtf.parser;

import de.swfdtf.core.ProjectDescriptor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Non-blocking polling watcher detecting additions, modifications, and deletions of
 * {@code **&#47;*.vjava} scripts and {@code project.vjava.json} under a KAGAYAKI project root.
 *
 * <p>Excludes the same build and runtime directories as {@link ProjectScanner}
 * ({@code .git}, {@code build}, {@code .gradle}, {@code saves}, {@code out}, {@code dist}).</p>
 */
public final class HotReloadWatcher {

    private static final String SCRIPT_EXTENSION = ".vjava";

    /**
     * Immutable change delta produced by {@link #pollChanges()}.
     *
     * @param added    newly discovered watched files
     * @param modified watched files whose {@code lastModifiedTime} or byte size changed
     * @param deleted  previously tracked files that no longer exist
     */
    public record ReloadDelta(List<Path> added, List<Path> modified, List<Path> deleted) {

        /**
         * Ensures all delta lists are unmodifiable and non-null.
         */
        public ReloadDelta {
            added = added != null ? List.copyOf(added) : List.of();
            modified = modified != null ? List.copyOf(modified) : List.of();
            deleted = deleted != null ? List.copyOf(deleted) : List.of();
        }

        /**
         * Returns {@code true} if any file was added, modified, or deleted.
         *
         * @return {@code true} when changes are present
         */
        public boolean hasChanges() {
            return !added.isEmpty() || !modified.isEmpty() || !deleted.isEmpty();
        }
    }

    private record FileStamp(FileTime lastModifiedTime, long size) {
    }

    private final Path projectRoot;
    private final Path descriptorPath;
    private final Map<Path, FileStamp> snapshot = new LinkedHashMap<>();

    /**
     * Creates a polling watcher rooted at {@code projectRoot}.
     *
     * @param projectRoot existing KAGAYAKI project directory
     * @throws IllegalArgumentException if {@code projectRoot} does not exist or is not a directory
     */
    public HotReloadWatcher(Path projectRoot) {
        Objects.requireNonNull(projectRoot, "projectRoot must not be null");
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        if (!Files.exists(normalizedRoot) || !Files.isDirectory(normalizedRoot)) {
            throw new IllegalArgumentException(
                    "Project root does not exist or is not a directory: " + normalizedRoot
            );
        }
        this.projectRoot = normalizedRoot;
        this.descriptorPath = normalizedRoot.resolve(ProjectDescriptor.FILE_NAME);
    }

    /**
     * Captures the initial baseline of all watched files without emitting a delta.
     *
     * @throws UncheckedIOException if filesystem traversal fails
     */
    public synchronized void initializeSnapshot() {
        snapshot.clear();
        snapshot.putAll(scanCurrentFiles());
    }

    /**
     * Polls the filesystem for changes since the previous snapshot and updates the internal baseline.
     *
     * @return immutable {@link ReloadDelta} describing added, modified, and deleted files
     * @throws UncheckedIOException if filesystem traversal fails
     */
    public synchronized ReloadDelta pollChanges() {
        Map<Path, FileStamp> current = scanCurrentFiles();
        List<Path> added = new ArrayList<>();
        List<Path> modified = new ArrayList<>();
        List<Path> deleted = new ArrayList<>();

        for (Map.Entry<Path, FileStamp> entry : current.entrySet()) {
            Path path = entry.getKey();
            FileStamp newStamp = entry.getValue();
            FileStamp oldStamp = snapshot.get(path);
            if (oldStamp == null) {
                added.add(path);
            } else if (!oldStamp.equals(newStamp)) {
                modified.add(path);
            }
        }

        for (Path previousPath : snapshot.keySet()) {
            if (!current.containsKey(previousPath)) {
                deleted.add(previousPath);
            }
        }

        Comparator<Path> deterministicOrder = Comparator.comparing(p -> projectRoot.relativize(p).toString());
        added.sort(deterministicOrder);
        modified.sort(deterministicOrder);
        deleted.sort(deterministicOrder);

        snapshot.clear();
        snapshot.putAll(current);
        return new ReloadDelta(added, modified, deleted);
    }

    /**
     * Returns the normalized project root directory watched by this instance.
     *
     * @return normalized project root path
     */
    public Path getProjectRoot() {
        return projectRoot;
    }

    private Map<Path, FileStamp> scanCurrentFiles() {
        Map<Path, FileStamp> current = new LinkedHashMap<>();
        try {
            Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(projectRoot)) {
                        Path dirName = dir.getFileName();
                        if (dirName != null && ProjectScanner.SKIPPED_DIRECTORIES.contains(dirName.toString())) {
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
                    Path fileName = normalizedFile.getFileName();
                    boolean isVJava = fileName != null && fileName.toString().endsWith(SCRIPT_EXTENSION);
                    boolean isDescriptor = normalizedFile.equals(descriptorPath);
                    if (isVJava || isDescriptor) {
                        current.put(normalizedFile, new FileStamp(attrs.lastModifiedTime(), attrs.size()));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to poll project files in " + projectRoot, e);
        }
        return current;
    }
}
