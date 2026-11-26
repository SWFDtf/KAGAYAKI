package de.swfdtf.e2e.support;

import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.tools.ProjectTemplateGenerator;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Shared workspace, fixture, and filesystem lifecycle helper for opaque-box E2E tests.
 */
public final class E2eWorkspaceSupport {

    /** Optional system property overriding the resolved KAGAYAKI repository root path. */
    public static final String REPO_ROOT_PROPERTY = "kagayaki.repo.root";

    private static final String ASSETS_DIR_NAME = "assets";
    private static final String SAVES_DIR_NAME = "saves";
    private static final String E2E_RESOURCES_REL = "src/e2eTest/resources";

    private E2eWorkspaceSupport() {
    }

    /**
     * Resolves the KAGAYAKI repository root directory by checking {@value #REPO_ROOT_PROPERTY}
     * and walking upward from the current working directory for {@code build.gradle} and
     * {@code settings.gradle}.
     *
     * @return normalized repository root path
     * @throws IllegalStateException if the repository root cannot be located
     */
    public static Path repoRoot() {
        String configured = System.getProperty(REPO_ROOT_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            Path explicit = Path.of(configured).toAbsolutePath().normalize();
            if (isRepoRoot(explicit)) {
                return explicit;
            }
            throw new IllegalStateException(
                    "Configured " + REPO_ROOT_PROPERTY + " is not a KAGAYAKI repo root: " + explicit
            );
        }

        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null) {
            if (isRepoRoot(cursor)) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException(
                "Could not locate KAGAYAKI repository root from " + Path.of("").toAbsolutePath()
        );
    }

    private static boolean isRepoRoot(Path candidate) {
        return Files.isRegularFile(candidate.resolve("build.gradle"))
                && Files.isRegularFile(candidate.resolve("settings.gradle"));
    }

    /**
     * Creates an empty isolated KAGAYAKI project directory with a normalized {@code project.vjava.json},
     * {@code assets/} directory, and {@code saves/} directory.
     *
     * @param rootDir    destination project root directory
     * @param title      project display title
     * @param renderMode render mode ({@code "2D"} or {@code "3D"})
     * @return normalized project root path
     * @throws IOException if directory creation or writing the descriptor fails
     */
    public static Path createEmptyProject(Path rootDir, String title, String renderMode) throws IOException {
        Objects.requireNonNull(rootDir, "rootDir must not be null");
        Path normalized = rootDir.toAbsolutePath().normalize();
        Files.createDirectories(normalized);
        Files.createDirectories(normalized.resolve(ASSETS_DIR_NAME));
        Files.createDirectories(normalized.resolve(SAVES_DIR_NAME));

        ProjectDescriptor descriptor = ProjectDescriptor.createDefault(title);
        if (renderMode != null && !renderMode.isBlank()) {
            descriptor.setRenderMode(renderMode);
        }
        descriptor.normalizeDefaults();
        ProjectDescriptor.save(normalized, descriptor);
        return normalized;
    }

    /**
     * Generates a KAGAYAKI project from {@link ProjectTemplateGenerator} into {@code targetDir}.
     *
     * @param targetDir  destination project root directory
     * @param title      project title
     * @param renderMode render mode ({@code "2D"} or {@code "3D"})
     * @param type       template preset to generate
     * @return normalized path to the generated project root
     * @throws IOException if template generation fails
     */
    public static Path createFromTemplate(
            Path targetDir,
            String title,
            String renderMode,
            ProjectTemplateGenerator.TemplateType type
    ) throws IOException {
        Objects.requireNonNull(targetDir, "targetDir must not be null");
        Objects.requireNonNull(type, "type must not be null");
        return ProjectTemplateGenerator.generate(targetDir, title, renderMode, type);
    }

    /**
     * Copies a fixture resource tree from the classpath or {@code src/e2eTest/resources/} fallback
     * into {@code targetDir}.
     *
     * @param resourcePrefix resource directory prefix (e.g., {@code "e2e/f01/valid_baseline"})
     * @param targetDir      isolated destination directory
     * @return normalized destination directory path
     * @throws IOException if the resource prefix cannot be found or copying fails
     */
    public static Path copyResourceTree(String resourcePrefix, Path targetDir) throws IOException {
        Objects.requireNonNull(resourcePrefix, "resourcePrefix must not be null");
        Objects.requireNonNull(targetDir, "targetDir must not be null");

        String cleanPrefix = sanitizeResourcePrefix(resourcePrefix);
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        Files.createDirectories(normalizedTarget);

        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = E2eWorkspaceSupport.class.getClassLoader();
        }
        URL resourceUrl = loader.getResource(cleanPrefix);
        if (resourceUrl != null) {
            String protocol = resourceUrl.getProtocol();
            if ("file".equals(protocol)) {
                try {
                    Path sourceDir = Path.of(resourceUrl.toURI());
                    if (Files.isDirectory(sourceDir)) {
                        copyDirectory(sourceDir, normalizedTarget);
                        return normalizedTarget;
                    }
                } catch (URISyntaxException ex) {
                    throw new IOException("Invalid file URI for resource: " + cleanPrefix, ex);
                }
            } else if ("jar".equals(protocol)) {
                copyFromJarResource(resourceUrl, cleanPrefix, normalizedTarget);
                return normalizedTarget;
            }
        }

        Path fallbackDir = repoRoot().resolve(E2E_RESOURCES_REL).resolve(cleanPrefix).normalize();
        if (Files.isDirectory(fallbackDir)) {
            copyDirectory(fallbackDir, normalizedTarget);
            return normalizedTarget;
        }

        throw new IOException("E2E resource directory not found on classpath or disk: " + cleanPrefix);
    }

    private static String sanitizeResourcePrefix(String raw) {
        String normalized = raw.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty() || normalized.contains("..")) {
            throw new IllegalArgumentException("Invalid resourcePrefix: " + raw);
        }
        return normalized;
    }

    private static void copyFromJarResource(URL resourceUrl, String cleanPrefix, Path targetDir) throws IOException {
        JarURLConnection conn = (JarURLConnection) resourceUrl.openConnection();
        String entryPrefix = cleanPrefix + "/";
        try (JarFile jar = conn.getJarFile()) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(entryPrefix) || entry.isDirectory()) {
                    continue;
                }
                String relative = name.substring(entryPrefix.length());
                Path dest = resolveInside(targetDir, relative);
                Path parent = dest.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (InputStream in = jar.getInputStream(entry)) {
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void copyDirectory(Path sourceDir, Path targetDir) throws IOException {
        Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = sourceDir.relativize(dir);
                Path dest = resolveInside(targetDir, rel.toString().replace('\\', '/'));
                Files.createDirectories(dest);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = sourceDir.relativize(file);
                Path dest = resolveInside(targetDir, rel.toString().replace('\\', '/'));
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Writes UTF-8 text content to a relative file inside {@code projectDir}, rejecting
     * absolute paths and {@code ..} traversal escapes.
     *
     * @param projectDir   root project directory
     * @param relativePath project-relative path (e.g., {@code "main.vjava"})
     * @param content      UTF-8 text content to write
     * @return normalized path to the written file
     * @throws IOException              if creating parent directories or writing the file fails
     * @throws IllegalArgumentException if {@code relativePath} is absolute or escapes {@code projectDir}
     */
    public static Path writeScript(Path projectDir, String relativePath, String content) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Objects.requireNonNull(relativePath, "relativePath must not be null");
        Objects.requireNonNull(content, "content must not be null");

        Path normalizedRoot = projectDir.toAbsolutePath().normalize();
        Path target = resolveInside(normalizedRoot, relativePath);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(target, content, StandardCharsets.UTF_8);
        return target;
    }

    private static Path resolveInside(Path root, String relativePath) {
        String trimmed = relativePath.trim();
        if (trimmed.isEmpty()) {
            return root;
        }
        Path rel = Path.of(trimmed);
        if (rel.isAbsolute()) {
            throw new IllegalArgumentException("Relative path must not be absolute: " + relativePath);
        }
        Path resolved = root.resolve(rel).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path traversal rejected outside projectDir: " + relativePath);
        }
        return resolved;
    }

    /**
     * Recursively deletes {@code root} and all descendants if present.
     *
     * @param root directory or file path to remove (no-op if non-existent)
     * @throws IOException if deleting any file or directory fails
     */
    public static void deleteRecursively(Path root) throws IOException {
        Objects.requireNonNull(root, "root must not be null");
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * Searches {@code <repoRoot>/build/dist} for a built standalone JAR matching
     * {@code kagayaki-standalone-*.jar}.
     *
     * @return {@link Optional} containing the standalone JAR path if present
     * @throws IOException if listing {@code build/dist} fails
     */
    public static Optional<Path> findStandaloneJar() throws IOException {
        Path distDir = repoRoot().resolve("build").resolve("dist");
        if (!Files.isDirectory(distDir)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.list(distDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("kagayaki-standalone-") && name.endsWith(".jar");
                    })
                    .sorted()
                    .findFirst();
        }
    }
}
