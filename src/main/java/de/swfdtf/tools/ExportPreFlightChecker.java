package de.swfdtf.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.swfdtf.core.ProjectDescriptor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Self-contained release readiness validator for KAGAYAKI projects prior to standalone export.
 *
 * <p>Verifies project directory existence, {@code project.vjava.json} loading and metadata
 * completeness ({@code title}, {@code version}, {@code entryLabel}), {@link VJavaLinter#lint(Path)},
 * non-empty {@code assets/}, and {@code tl/} translation presence (reported as {@link CheckStatus#WARN}
 * when absent), producing a structured {@link PreFlightReport} with a Markdown checklist.</p>
 */
public final class ExportPreFlightChecker {

    /**
     * Outcome status of a single pre-flight validation check.
     */
    public enum CheckStatus {
        /** Check succeeded without issues. */
        PASS,
        /** Non-fatal warning that does not block release readiness. */
        WARN,
        /** Critical failure that blocks release export. */
        FAIL
    }

    /**
     * Individual check item in a {@link PreFlightReport}.
     *
     * @param name    concise check name
     * @param status  resulting {@link CheckStatus}
     * @param details human-readable explanation of the check result
     */
    public record CheckItem(
            String name,
            CheckStatus status,
            String details
    ) {
        /** Validates check name and status, normalizing null details to empty string. */
        public CheckItem {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(status, "status must not be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            details = details != null ? details : "";
        }
    }

    /**
     * Aggregated pre-flight validation report with release readiness flag and Markdown summary.
     *
     * @param readyForRelease {@code true} when no {@link CheckItem} has {@link CheckStatus#FAIL}
     * @param checks          unmodifiable ordered list of evaluated {@link CheckItem} entries
     * @param summaryMarkdown Markdown-formatted checklist summarizing all checks
     */
    public record PreFlightReport(
            boolean readyForRelease,
            List<CheckItem> checks,
            String summaryMarkdown
    ) {
        /** Normalizes null check list and summary Markdown values. */
        public PreFlightReport {
            checks = checks != null ? List.copyOf(checks) : List.of();
            summaryMarkdown = summaryMarkdown != null ? summaryMarkdown : "";
        }
    }

    /**
     * Creates a new {@code ExportPreFlightChecker}.
     */
    public ExportPreFlightChecker() {
    }

    /**
     * Runs all release pre-flight checks against {@code projectRoot}.
     *
     * @param projectRoot root directory of the KAGAYAKI project (must not be {@code null})
     * @return immutable {@link PreFlightReport}
     */
    public PreFlightReport check(Path projectRoot) {
        Objects.requireNonNull(projectRoot, "projectRoot must not be null");
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        List<CheckItem> checks = new ArrayList<>();

        if (!Files.exists(normalizedRoot) || !Files.isDirectory(normalizedRoot)) {
            checks.add(new CheckItem(
                    "Project Directory",
                    CheckStatus.FAIL,
                    "Project directory does not exist or is not a directory: " + normalizedRoot
            ));
            return buildReport(checks);
        }

        checks.add(new CheckItem(
                "Project Directory",
                CheckStatus.PASS,
                "Found project directory: " + normalizedRoot
        ));

        checkDescriptorAndMetadata(normalizedRoot, checks);
        checkLinter(normalizedRoot, checks);
        checkAssetsDirectory(normalizedRoot, checks);
        checkTranslationsDirectory(normalizedRoot, checks);

        return buildReport(checks);
    }

    private static void checkDescriptorAndMetadata(Path projectRoot, List<CheckItem> checks) {
        Path descriptorFile = projectRoot.resolve(ProjectDescriptor.FILE_NAME);
        if (!Files.isRegularFile(descriptorFile)) {
            checks.add(new CheckItem(
                    "Project Descriptor",
                    CheckStatus.FAIL,
                    "Missing " + ProjectDescriptor.FILE_NAME + " in " + projectRoot
            ));
            checks.add(new CheckItem(
                    "Descriptor Metadata",
                    CheckStatus.FAIL,
                    "Cannot validate title, version, and entryLabel without " + ProjectDescriptor.FILE_NAME
            ));
            return;
        }

        ProjectDescriptor descriptor;
        try {
            descriptor = ProjectDescriptor.load(projectRoot);
        } catch (IOException ex) {
            checks.add(new CheckItem(
                    "Project Descriptor",
                    CheckStatus.FAIL,
                    "Failed to load " + ProjectDescriptor.FILE_NAME + ": " + ex.getMessage()
            ));
            checks.add(new CheckItem(
                    "Descriptor Metadata",
                    CheckStatus.FAIL,
                    "Cannot validate metadata due to invalid " + ProjectDescriptor.FILE_NAME
            ));
            return;
        }

        checks.add(new CheckItem(
                "Project Descriptor",
                CheckStatus.PASS,
                "Loaded " + ProjectDescriptor.FILE_NAME + " successfully"
        ));

        List<String> missingOrBlankFields = validateRawMetadataFields(descriptorFile, descriptor);
        if (!missingOrBlankFields.isEmpty()) {
            checks.add(new CheckItem(
                    "Descriptor Metadata",
                    CheckStatus.FAIL,
                    "Blank or missing required metadata fields: " + String.join(", ", missingOrBlankFields)
            ));
        } else {
            checks.add(new CheckItem(
                    "Descriptor Metadata",
                    CheckStatus.PASS,
                    "title='" + descriptor.getTitle() + "', version='" + descriptor.getVersion()
                            + "', entryLabel='" + descriptor.getEntryLabel() + "'"
            ));
        }
    }

    private static List<String> validateRawMetadataFields(Path descriptorFile, ProjectDescriptor descriptor) {
        List<String> missing = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(descriptorFile, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject obj = parsed.getAsJsonObject();
                if (isJsonFieldBlank(obj, "title") || isBlank(descriptor.getTitle())) {
                    missing.add("title");
                }
                if (isJsonFieldBlank(obj, "version") || isBlank(descriptor.getVersion())) {
                    missing.add("version");
                }
                if (isJsonFieldBlank(obj, "entryLabel") || isBlank(descriptor.getEntryLabel())) {
                    missing.add("entryLabel");
                }
                return missing;
            }
        } catch (Exception ignored) {
            // Fall back to loaded descriptor checks below.
        }

        if (isBlank(descriptor.getTitle())) {
            missing.add("title");
        }
        if (isBlank(descriptor.getVersion())) {
            missing.add("version");
        }
        if (isBlank(descriptor.getEntryLabel())) {
            missing.add("entryLabel");
        }
        return missing;
    }

    private static boolean isJsonFieldBlank(JsonObject obj, String fieldName) {
        if (!obj.has(fieldName) || obj.get(fieldName).isJsonNull()) {
            return true;
        }
        JsonElement el = obj.get(fieldName);
        return !el.isJsonPrimitive() || el.getAsString().isBlank();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void checkLinter(Path projectRoot, List<CheckItem> checks) {
        VJavaLinter.LintReport report = VJavaLinter.lint(projectRoot);
        if (report.hasErrors()) {
            String firstError = report.issues().stream()
                    .filter(issue -> issue.severity() == VJavaLinter.Severity.ERROR)
                    .findFirst()
                    .map(issue -> issue.code() + ": " + issue.message())
                    .orElse("Unknown lint error");
            checks.add(new CheckItem(
                    "VJava Linter",
                    CheckStatus.FAIL,
                    report.errorCount() + " error(s), " + report.warningCount()
                            + " warning(s). First error: " + firstError
            ));
        } else if (report.warningCount() > 0) {
            checks.add(new CheckItem(
                    "VJava Linter",
                    CheckStatus.WARN,
                    "0 errors, " + report.warningCount() + " warning(s)"
            ));
        } else {
            checks.add(new CheckItem(
                    "VJava Linter",
                    CheckStatus.PASS,
                    "Clean lint report (0 errors, 0 warnings)"
            ));
        }
    }

    private static void checkAssetsDirectory(Path projectRoot, List<CheckItem> checks) {
        Path assetsDir = projectRoot.resolve("assets");
        if (!Files.exists(assetsDir) || !Files.isDirectory(assetsDir)) {
            checks.add(new CheckItem(
                    "Assets Directory",
                    CheckStatus.FAIL,
                    "Missing assets/ directory"
            ));
            return;
        }

        long fileCount = countRegularFiles(assetsDir);
        if (fileCount <= 0) {
            checks.add(new CheckItem(
                    "Assets Directory",
                    CheckStatus.FAIL,
                    "assets/ directory contains no files"
            ));
        } else {
            checks.add(new CheckItem(
                    "Assets Directory",
                    CheckStatus.PASS,
                    "Found " + fileCount + " asset file(s) under assets/"
            ));
        }
    }

    private static void checkTranslationsDirectory(Path projectRoot, List<CheckItem> checks) {
        Path tlDir = projectRoot.resolve("tl");
        if (!Files.exists(tlDir) || !Files.isDirectory(tlDir)) {
            checks.add(new CheckItem(
                    "Translations Directory",
                    CheckStatus.WARN,
                    "tl/ directory is absent; export will include default language only"
            ));
            return;
        }

        long fileCount = countRegularFiles(tlDir);
        if (fileCount <= 0) {
            checks.add(new CheckItem(
                    "Translations Directory",
                    CheckStatus.WARN,
                    "tl/ directory exists but contains no translation files"
            ));
        } else {
            checks.add(new CheckItem(
                    "Translations Directory",
                    CheckStatus.PASS,
                    "Found " + fileCount + " translation file(s) under tl/"
            ));
        }
    }

    private static long countRegularFiles(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile).count();
        } catch (IOException ex) {
            return 0L;
        }
    }

    private static PreFlightReport buildReport(List<CheckItem> checks) {
        boolean ready = checks.stream().noneMatch(item -> item.status() == CheckStatus.FAIL);
        String markdown = formatMarkdownChecklist(ready, checks);
        return new PreFlightReport(ready, checks, markdown);
    }

    private static String formatMarkdownChecklist(boolean readyForRelease, List<CheckItem> checks) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Export Pre-Flight Checklist\n\n");
        sb.append("**Release Ready:** ").append(readyForRelease ? "YES" : "NO").append("\n\n");
        for (CheckItem item : checks) {
            String marker = switch (item.status()) {
                case PASS -> "[x]";
                case WARN -> "[!]";
                case FAIL -> "[ ]";
            };
            sb.append("- ").append(marker)
                    .append(" **").append(item.name()).append("** (`")
                    .append(item.status().name()).append("`): ")
                    .append(item.details()).append('\n');
        }
        return sb.toString();
    }
}
