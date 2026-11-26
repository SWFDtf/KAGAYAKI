package de.swfdtf.tools;

import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.VJavaAst;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automated safe fixer for selected {@link VJavaLinter} diagnostics.
 *
 * <p>Resolves {@code UNDEFINED_SPEAKER} warnings by appending {@code Character(...)} definitions and
 * {@code MISSING_JUMP_TARGET}/{@code MISSING_CALL_TARGET} errors by appending deterministic stub
 * labels to the project's entry/main {@code .vjava} script (or first discovered script file).
 * All fixes are deduplicated and idempotent across repeated invocations.</p>
 */
public final class VJavaLinterAutoFixer {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern UNDEFINED_SPEAKER_PATTERN =
            Pattern.compile("^Undefined speaker ID '([^']+)'");
    private static final Pattern MISSING_TARGET_PATTERN =
            Pattern.compile("^(?:Jump|Call) target label '([^']+)'");

    /**
     * Immutable summary of automated fixes applied by {@link #applySafeFixes(Path)}.
     *
     * @param appliedCount total number of distinct fixes applied ({@code >= 0})
     * @param descriptions unmodifiable list of human-readable descriptions for each applied fix
     */
    public record AutoFixResult(int appliedCount, List<String> descriptions) {

        /**
         * Validates fix count and wraps {@code descriptions} in an unmodifiable list.
         */
        public AutoFixResult {
            if (appliedCount < 0) {
                throw new IllegalArgumentException("appliedCount must be >= 0, got " + appliedCount);
            }
            descriptions = descriptions != null ? List.copyOf(descriptions) : List.of();
        }
    }

    /**
     * Creates a new {@code VJavaLinterAutoFixer}.
     */
    public VJavaLinterAutoFixer() {
    }

    /**
     * Runs {@link VJavaLinter#lint(Path)} on {@code projectRoot} and appends deduplicated,
     * deterministic safe fixes for {@code UNDEFINED_SPEAKER}, {@code MISSING_JUMP_TARGET},
     * and {@code MISSING_CALL_TARGET} issues.
     *
     * @param projectRoot root directory of the KAGAYAKI project (must not be {@code null})
     * @return {@link AutoFixResult} detailing the number and descriptions of applied fixes
     * @throws IOException if {@code projectRoot} is not a directory or reading/writing script files fails
     */
    public AutoFixResult applySafeFixes(Path projectRoot) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot must not be null");
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        if (!Files.exists(normalizedRoot) || !Files.isDirectory(normalizedRoot)) {
            throw new IOException("Project directory does not exist or is not a directory: " + normalizedRoot);
        }

        VJavaLinter.LintReport report = VJavaLinter.lint(normalizedRoot);
        Set<String> rawSpeakers = new LinkedHashSet<>();
        Set<String> rawLabels = new LinkedHashSet<>();

        for (VJavaLinter.LintIssue issue : report.issues()) {
            switch (issue.code()) {
                case "UNDEFINED_SPEAKER" -> {
                    Matcher m = UNDEFINED_SPEAKER_PATTERN.matcher(issue.message());
                    if (m.find()) {
                        String speaker = m.group(1).trim();
                        if (IDENTIFIER_PATTERN.matcher(speaker).matches()) {
                            rawSpeakers.add(speaker);
                        }
                    }
                }
                case "MISSING_JUMP_TARGET", "MISSING_CALL_TARGET" -> {
                    Matcher m = MISSING_TARGET_PATTERN.matcher(issue.message());
                    if (m.find()) {
                        String target = m.group(1).trim();
                        if (IDENTIFIER_PATTERN.matcher(target).matches()) {
                            rawLabels.add(target);
                        }
                    }
                }
                default -> {
                    // Other diagnostics are not auto-fixed here.
                }
            }
        }

        if (rawSpeakers.isEmpty() && rawLabels.isEmpty()) {
            return new AutoFixResult(0, List.of());
        }

        VJavaAst.ProjectAst project = ProjectScanner.scan(normalizedRoot);
        Set<String> speakersToAdd = new LinkedHashSet<>();
        for (String speaker : rawSpeakers) {
            if (!project.characters().containsKey(speaker)) {
                speakersToAdd.add(speaker);
            }
        }

        Set<String> labelsToAdd = new LinkedHashSet<>();
        for (String target : rawLabels) {
            if (!project.labels().containsKey(target)) {
                labelsToAdd.add(target);
            }
        }

        if (speakersToAdd.isEmpty() && labelsToAdd.isEmpty()) {
            return new AutoFixResult(0, List.of());
        }

        Path targetFile = resolveTargetScriptFile(normalizedRoot, project);
        if (targetFile == null) {
            return new AutoFixResult(0, List.of());
        }
        String relativeTarget = normalizedRoot.relativize(targetFile).toString().replace('\\', '/');

        StringBuilder additions = new StringBuilder();
        List<String> descriptions = new ArrayList<>();

        for (String speaker : speakersToAdd) {
            String displayName = capitalizeSpeaker(speaker);
            String definitionLine = "define " + speaker + " = Character(\"" + escapeQuotes(displayName) + "\")";
            additions.append(definitionLine).append('\n');
            descriptions.add("Added speaker definition for '" + speaker + "' in " + relativeTarget);
        }

        for (String targetLabel : labelsToAdd) {
            if (additions.length() > 0) {
                additions.append('\n');
            }
            additions.append("label ").append(targetLabel).append(":\n");
            additions.append("    # Stub label generated by VJavaLinterAutoFixer\n");
            additions.append("    return\n");
            descriptions.add("Added stub label '" + targetLabel + "' in " + relativeTarget);
        }

        String existing = Files.readString(targetFile, StandardCharsets.UTF_8);
        String trimmedBase = stripTrailingLineBreaks(existing);
        String updated = trimmedBase.isEmpty()
                ? additions.toString()
                : trimmedBase + "\n\n" + additions;

        writeAtomically(targetFile, updated);
        return new AutoFixResult(descriptions.size(), descriptions);
    }

    /**
     * Generates a readable 64x64 checkerboard RGBA placeholder PNG inside {@code projectRoot} for
     * a missing {@code assets/...} image reference ({@code #0125}), eliminating {@code MISSING_ASSET}.
     *
     * @param projectRoot       project root directory
     * @param assetRelativePath relative target asset path (must resolve inside {@code projectRoot})
     * @return normalized path of the written placeholder PNG file
     * @throws IOException              if writing fails
     * @throws IllegalArgumentException if {@code assetRelativePath} escapes {@code projectRoot} or is blank
     */
    public Path createPlaceholderPngForMissingAsset(Path projectRoot, String assetRelativePath) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot must not be null");
        Objects.requireNonNull(assetRelativePath, "assetRelativePath must not be null");
        String trimmed = assetRelativePath.trim().replace('\\', '/');
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("assetRelativePath must not be blank");
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        Path target = root.resolve(trimmed).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Asset path escapes project root: " + assetRelativePath);
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        final int size = 64;
        byte[] rgba = new byte[size * size * 4];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean magenta = ((x / 8) + (y / 8)) % 2 == 0;
                int idx = (y * size + x) * 4;
                rgba[idx] = (byte) (magenta ? 0xD9 : 0x2E);
                rgba[idx + 1] = (byte) (magenta ? 0x46 : 0x33);
                rgba[idx + 2] = (byte) (magenta ? 0xEF : 0x4A);
                rgba[idx + 3] = (byte) 0xFF;
            }
        }
        TextureManager.writePng(target, size, size, rgba);
        return target;
    }

    private static Path resolveTargetScriptFile(Path normalizedRoot, VJavaAst.ProjectAst project) {
        String entryLabel = resolveEntryLabel(project.descriptor());
        VJavaAst.LabelNode entryNode = project.labels().get(entryLabel);
        if (entryNode != null && entryNode.sourceFile() != null && !entryNode.sourceFile().isBlank()) {
            Path entryFile = normalizedRoot.resolve(entryNode.sourceFile()).normalize();
            if (entryFile.startsWith(normalizedRoot) && Files.isRegularFile(entryFile)) {
                return entryFile;
            }
        }

        Path mainFile = normalizedRoot.resolve("main.vjava").normalize();
        if (Files.isRegularFile(mainFile)) {
            return mainFile;
        }

        for (Path scriptFile : project.scriptFiles()) {
            if (Files.isRegularFile(scriptFile)) {
                return scriptFile.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static String resolveEntryLabel(ProjectDescriptor descriptor) {
        if (descriptor == null || descriptor.getEntryLabel() == null || descriptor.getEntryLabel().isBlank()) {
            return "start";
        }
        return descriptor.getEntryLabel().trim();
    }

    private static String capitalizeSpeaker(String speakerId) {
        if (speakerId.length() == 1) {
            return speakerId.toUpperCase(Locale.ROOT);
        }
        return speakerId.substring(0, 1).toUpperCase(Locale.ROOT) + speakerId.substring(1);
    }

    private static String escapeQuotes(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String stripTrailingLineBreaks(String text) {
        int end = text.length();
        while (end > 0) {
            char c = text.charAt(end - 1);
            if (c == '\n' || c == '\r') {
                end--;
            } else {
                break;
            }
        }
        return end == text.length() ? text : text.substring(0, end);
    }

    private static void writeAtomically(Path targetFile, String content) throws IOException {
        Path parent = targetFile.getParent();
        Path tempFile = parent != null
                ? Files.createTempFile(parent, ".autofix-", ".tmp")
                : Files.createTempFile(".autofix-", ".tmp");
        boolean moved = false;
        try {
            Files.writeString(tempFile, content, StandardCharsets.UTF_8);
            try {
                Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tempFile);
            }
        }
    }
}
