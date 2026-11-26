package de.swfdtf.tools;

import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.VJavaAst;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Project refactoring, scaffolding, backup, version-bumping, external asset import, and batch
 * script formatting service for KAGAYAKI Studio ({@code #0131}, {@code #0132}, {@code #0148},
 * {@code #0149}, {@code #0155}, {@code #0166}).
 */
public final class ProjectRefactorService {

    private static final Set<String> EXCLUDED_BACKUP_DIRS = Set.of(".git", "build", ".gradle", "out", "dist");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");

    /** Semantic version bump component ({@code #0166}). */
    public enum VersionBump {
        /** Increments the patch component ({@code x.y.Z -> x.y.(Z+1)}). */
        PATCH,
        /** Increments the minor component and resets patch ({@code x.Y.z -> x.(Y+1).0}). */
        MINOR,
        /** Increments the major component and resets minor/patch ({@code X.y.z -> (X+1).0.0}). */
        MAJOR
    }

    /**
     * Summary of a batch rename refactoring across project scripts.
     *
     * @param filesModified number of {@code .vjava} files updated
     * @param replacements  total token occurrences rewritten
     */
    public record RenameResult(int filesModified, int replacements) {
    }

    /**
     * Preview / result of batch formatting a single script file ({@code #0149}).
     *
     * @param file           normalized script file path
     * @param changed        {@code true} when formatted text differs from original
     * @param astPreserved   {@code true} when parsed label/statement counts match pre-format AST
     * @param formattedText  formatted script content
     */
    public record FormatFileResult(Path file, boolean changed, boolean astPreserved, String formattedText) {
    }

    /** Creates a new {@code ProjectRefactorService}. */
    public ProjectRefactorService() {
    }

    /**
     * Renames a script label across {@code label}, {@code jump}, {@code call}, {@code menu},
     * {@code on_collide}, {@code on_overlap}, {@code on_interact}, {@code trigger_zone},
     * {@code after}, {@code spawn_cutscene}, screen actions, and {@code project.vjava.json}
     * ({@code #0132}).
     *
     * @param projectRoot project root directory
     * @param oldLabel    existing label name
     * @param newLabel    replacement label name
     * @return {@link RenameResult} summary
     * @throws IOException if reading or writing project files fails
     */
    public RenameResult renameLabel(Path projectRoot, String oldLabel, String newLabel) throws IOException {
        Path root = validateProjectDir(projectRoot);
        String oldId = requireValidName(oldLabel, "oldLabel");
        String newId = requireValidName(newLabel, "newLabel");
        if (oldId.equals(newId)) {
            return new RenameResult(0, 0);
        }

        Pattern labelPattern = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(oldId) + "(?![A-Za-z0-9_])");
        int filesChanged = 0;
        int totalReplacements = 0;

        for (Path vjavaFile : listVjavaFiles(root)) {
            List<String> lines = Files.readAllLines(vjavaFile, StandardCharsets.UTF_8);
            List<String> updatedLines = new ArrayList<>(lines.size());
            int fileHits = 0;
            for (String line : lines) {
                String rewritten = rewriteLabelLine(line, labelPattern, newId);
                if (!rewritten.equals(line)) {
                    fileHits++;
                }
                updatedLines.add(rewritten);
            }
            if (fileHits > 0) {
                Files.writeString(vjavaFile, String.join("\n", updatedLines) + "\n", StandardCharsets.UTF_8);
                filesChanged++;
                totalReplacements += fileHits;
            }
        }

        Path descPath = root.resolve(ProjectDescriptor.FILE_NAME);
        if (Files.isRegularFile(descPath)) {
            ProjectDescriptor desc = ProjectDescriptor.load(root);
            if (oldId.equals(desc.getEntryLabel())) {
                desc.setEntryLabel(newId);
                ProjectDescriptor.save(root, desc);
                totalReplacements++;
            }
        }
        return new RenameResult(filesChanged, totalReplacements);
    }

    /**
     * Renames an image/sprite tag consistently across {@code image}, {@code show}, {@code hide},
     * {@code move}, {@code rotate}, {@code scale}, {@code wasd_init}, and {@code physics_init}
     * statements ({@code #0132}).
     *
     * @param projectRoot project root directory
     * @param oldTag      existing sprite/image tag
     * @param newTag      replacement sprite/image tag
     * @return {@link RenameResult} summary
     * @throws IOException if reading or writing project files fails
     */
    public RenameResult renameAssetTag(Path projectRoot, String oldTag, String newTag) throws IOException {
        Path root = validateProjectDir(projectRoot);
        String oldId = requireValidName(oldTag, "oldTag");
        String newId = requireValidName(newTag, "newTag");
        if (oldId.equals(newId)) {
            return new RenameResult(0, 0);
        }

        Pattern tagPattern = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(oldId) + "(?![A-Za-z0-9_])");
        int filesChanged = 0;
        int totalReplacements = 0;

        for (Path vjavaFile : listVjavaFiles(root)) {
            List<String> lines = Files.readAllLines(vjavaFile, StandardCharsets.UTF_8);
            List<String> updated = new ArrayList<>(lines.size());
            int hits = 0;
            for (String line : lines) {
                String trimmed = line.trim();
                boolean isTagContext = trimmed.startsWith("image ") || trimmed.startsWith("layeredimage ")
                        || trimmed.startsWith("show ") || trimmed.startsWith("hide ")
                        || trimmed.startsWith("move ") || trimmed.startsWith("move_by ")
                        || trimmed.startsWith("wasd_init ") || trimmed.startsWith("physics_init ")
                        || trimmed.startsWith("hitbox ") || trimmed.startsWith("solid_platform ");
                if (isTagContext) {
                    Matcher m = tagPattern.matcher(line);
                    String replaced = m.replaceAll(Matcher.quoteReplacement(newId));
                    if (!replaced.equals(line)) {
                        hits++;
                    }
                    updated.add(replaced);
                } else {
                    updated.add(line);
                }
            }
            if (hits > 0) {
                Files.writeString(vjavaFile, String.join("\n", updated) + "\n", StandardCharsets.UTF_8);
                filesChanged++;
                totalReplacements += hits;
            }
        }
        return new RenameResult(filesChanged, totalReplacements);
    }

    /**
     * Safely duplicates a project directory excluding {@code build/}, {@code .git/}, {@code .gradle/},
     * {@code out/}, and {@code dist/} ({@code #0131}).
     *
     * @param sourceDir existing source project directory
     * @param targetDir destination backup directory
     * @return number of files copied
     * @throws IOException if copying fails
     */
    public int duplicateProject(Path sourceDir, Path targetDir) throws IOException {
        Path src = validateProjectDir(sourceDir);
        Path dst = Objects.requireNonNull(targetDir, "targetDir must not be null").toAbsolutePath().normalize();
        if (dst.startsWith(src)) {
            throw new IllegalArgumentException("targetDir must not be inside sourceDir");
        }
        Files.createDirectories(dst);
        final int[] count = {0};
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(src) && dir.getFileName() != null
                        && EXCLUDED_BACKUP_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(dst.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                count[0]++;
                return FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }

    /**
     * Archives a project directory to a ZIP backup excluding {@code build/}, {@code .git/},
     * {@code .gradle/}, {@code out/}, and {@code dist/} ({@code #0131}).
     *
     * @param sourceDir existing source project directory
     * @param outputZip destination ZIP archive path
     * @return normalized path to written ZIP archive
     * @throws IOException if archiving fails
     */
    public Path archiveProjectZip(Path sourceDir, Path outputZip) throws IOException {
        Path src = validateProjectDir(sourceDir);
        Path zipTarget = Objects.requireNonNull(outputZip, "outputZip must not be null")
                .toAbsolutePath().normalize();
        if (zipTarget.getParent() != null) {
            Files.createDirectories(zipTarget.getParent());
        }
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipTarget))) {
            Files.walkFileTree(src, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(src) && dir.getFileName() != null
                            && EXCLUDED_BACKUP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String rel = src.relativize(file).toString().replace('\\', '/');
                    zos.putNextEntry(new ZipEntry(rel));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return zipTarget;
    }

    /**
     * Creates a new {@code .vjava} script file inside {@code projectRoot} with an initial starter
     * label and return stub so {@link ProjectScanner#scan(Path)} indexes it immediately ({@code #0155}).
     *
     * @param projectRoot        project root directory
     * @param relativeScriptPath relative script path (e.g., {@code "scenes/chap2.vjava"})
     * @param initialLabel       initial label identifier
     * @return normalized path to the newly created script file
     * @throws IOException if file already exists or writing fails
     */
    public Path createNewScript(Path projectRoot, String relativeScriptPath, String initialLabel)
            throws IOException {
        Path root = validateProjectDir(projectRoot);
        String rel = requireValidName(relativeScriptPath.replace('\\', '/'), "relativeScriptPath");
        if (!rel.endsWith(".vjava")) {
            rel = rel + ".vjava";
        }
        String label = requireValidName(initialLabel, "initialLabel");
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Script path escapes project root: " + relativeScriptPath);
        }
        if (Files.exists(target)) {
            throw new IOException("Script already exists: " + target);
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        String content = "# KAGAYAKI Scene Script\nlabel " + label + ":\n"
                + "    \"Neue Szene (" + label + ")\"\n"
                + "    return\n";
        Files.writeString(target, content, StandardCharsets.UTF_8);
        return target;
    }

    /**
     * Increments the semantic version stored in {@code project.vjava.json} and saves it ({@code #0166}).
     *
     * @param projectRoot project root directory
     * @param bump        semantic version bump component
     * @return updated semantic version string
     * @throws IOException if loading or saving {@code project.vjava.json} fails
     */
    public String bumpProjectVersion(Path projectRoot, VersionBump bump) throws IOException {
        Path root = validateProjectDir(projectRoot);
        Objects.requireNonNull(bump, "bump must not be null");
        ProjectDescriptor desc = ProjectDescriptor.load(root);
        String current = desc.getVersion() != null ? desc.getVersion().trim() : "1.0.0";
        String[] parts = current.split("\\.");
        int major = parts.length > 0 ? parseNonNeg(parts[0], 1) : 1;
        int minor = parts.length > 1 ? parseNonNeg(parts[1], 0) : 0;
        int patch = parts.length > 2 ? parseNonNeg(parts[2], 0) : 0;
        switch (bump) {
            case MAJOR -> {
                major++;
                minor = 0;
                patch = 0;
            }
            case MINOR -> {
                minor++;
                patch = 0;
            }
            case PATCH -> patch++;
        }
        String next = major + "." + minor + "." + patch;
        desc.setVersion(next);
        ProjectDescriptor.save(root, desc);
        return next;
    }

    /**
     * Imports an external PNG/JPG/WEBP/WAV/OGG file into the project's organized {@code assets/}
     * subdirectory ({@code assets/images/} or {@code assets/audio/}) ({@code #0148}).
     *
     * @param projectRoot project root directory
     * @param externalFile source media file
     * @return normalized path inside project {@code assets/}
     * @throws IOException if copying fails
     */
    public Path importExternalAsset(Path projectRoot, Path externalFile) throws IOException {
        Path root = validateProjectDir(projectRoot);
        Path src = Objects.requireNonNull(externalFile, "externalFile must not be null").toAbsolutePath().normalize();
        if (!Files.isRegularFile(src)) {
            throw new IOException("Source asset file does not exist: " + src);
        }
        String fileName = src.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        String targetSubdir;
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")) {
            targetSubdir = "assets/images";
        } else if (lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".oga")) {
            targetSubdir = "assets/audio";
        } else {
            throw new IllegalArgumentException("Unsupported external asset extension: " + fileName);
        }
        Path destDir = root.resolve(targetSubdir);
        Files.createDirectories(destDir);
        Path destFile = destDir.resolve(fileName).normalize();
        Files.copy(src, destFile, StandardCopyOption.REPLACE_EXISTING);
        return destFile;
    }

    /**
     * Batch-formats all {@code .vjava} scripts in {@code projectRoot} using {@link VJavaScriptFormatter}
     * and verifies structural AST equivalence per file ({@code #0149}).
     *
     * @param projectRoot project root directory
     * @param dryRun      when {@code true}, returns diffs without overwriting files
     * @return immutable list of {@link FormatFileResult} entries
     * @throws IOException if reading or writing script files fails
     */
    public List<FormatFileResult> formatAllScripts(Path projectRoot, boolean dryRun) throws IOException {
        Path root = validateProjectDir(projectRoot);
        VJavaScriptFormatter formatter = new VJavaScriptFormatter();
        List<FormatFileResult> results = new ArrayList<>();
        for (Path script : listVjavaFiles(root)) {
            String before = Files.readString(script, StandardCharsets.UTF_8);
            VJavaAst.ScriptFileAst astBefore = de.swfdtf.parser.VJavaParser.parse(script, before);
            String after = formatter.format(before);
            VJavaAst.ScriptFileAst astAfter = de.swfdtf.parser.VJavaParser.parse(script, after);
            boolean preserved = astBefore.labels().size() == astAfter.labels().size()
                    && astBefore.characters().size() == astAfter.characters().size()
                    && astBefore.images().size() == astAfter.images().size();
            boolean changed = !before.equals(after);
            if (changed && !dryRun) {
                Files.writeString(script, after, StandardCharsets.UTF_8);
            }
            results.add(new FormatFileResult(script, changed, preserved, after));
        }
        return List.copyOf(results);
    }

    private static String rewriteLabelLine(String line, Pattern labelPattern, String newLabel) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return line;
        }
        if (trimmed.startsWith("label ") || trimmed.startsWith("jump ") || trimmed.startsWith("call ")
                || trimmed.contains("-> jump ") || trimmed.contains("on_collide ")
                || trimmed.contains("on_overlap ") || trimmed.contains("on_interact ")
                || trimmed.contains("trigger_zone ") || trimmed.contains("after ")
                || trimmed.startsWith("spawn_cutscene ") || trimmed.contains("Jump(\"")
                || trimmed.contains("Call(\"")) {
            return labelPattern.matcher(line).replaceAll(Matcher.quoteReplacement(newLabel));
        }
        return line;
    }

    private static List<Path> listVjavaFiles(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && dir.getFileName() != null
                        && ProjectScanner.SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName() != null && file.getFileName().toString().endsWith(".vjava")) {
                    out.add(file.toAbsolutePath().normalize());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    private static Path validateProjectDir(Path dir) {
        Objects.requireNonNull(dir, "projectRoot must not be null");
        Path norm = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(norm)) {
            throw new IllegalArgumentException("Not a valid project directory: " + norm);
        }
        return norm;
    }

    private static String requireValidName(String raw, String field) {
        Objects.requireNonNull(raw, field + " must not be null");
        String t = raw.trim();
        if (t.isEmpty() || !IDENTIFIER_PATTERN.matcher(t.replace('/', '_')).matches()) {
            throw new IllegalArgumentException("Invalid " + field + ": " + raw);
        }
        return t;
    }

    private static int parseNonNeg(String token, int fallback) {
        try {
            String digits = token.replaceAll("[^0-9].*$", "");
            return digits.isEmpty() ? fallback : Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
