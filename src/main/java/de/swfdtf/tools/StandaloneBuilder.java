package de.swfdtf.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.swfdtf.core.ProjectDescriptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Exports a validated KAGAYAKI project into a self-contained standalone distribution directory
 * containing {@code game.vpak}, launcher scripts ({@code run_game.sh} / {@code run_game.bat})
 * targeting {@code de.swfdtf.StandaloneMain --standalone .} (Window B only, without Studio tools),
 * optional bundled standalone JAR, and {@code standalone-packaging.json} for {@code jpackage}/{@code jlink}.
 */
public final class StandaloneBuilder {

    private static final String VPAK_FILE_NAME = "game.vpak";
    private static final String STANDALONE_JAR_NAME = "kagayaki-standalone.jar";
    private static final String RUNTIME_REFERENCE_NAME = "STANDALONE_RUNTIME_README.txt";
    private static final String LAUNCHER_SH_NAME = "run_game.sh";
    private static final String LAUNCHER_BAT_NAME = "run_game.bat";
    private static final String PACKAGING_DESCRIPTOR_NAME = "standalone-packaging.json";
    private static final String STANDALONE_MAIN_CLASS = "de.swfdtf.StandaloneMain";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    /**
     * Summary of a completed standalone export build.
     *
     * @param outputDir           normalized export output directory
     * @param vpakFile            path to generated {@code game.vpak}
     * @param standaloneJar       path to bundled standalone JAR inside {@code outputDir}, or {@code null} if omitted
     * @param launcherScript      path to generated POSIX launcher {@code run_game.sh}
     * @param packagingDescriptor path to generated {@code standalone-packaging.json}
     * @param packagedEntries     number of entries packed into {@code game.vpak}
     */
    public record BuildResult(
            Path outputDir,
            Path vpakFile,
            Path standaloneJar,
            Path launcherScript,
            Path packagingDescriptor,
            int packagedEntries
    ) {
    }

    private StandaloneBuilder() {
    }

    /**
     * Lints, packs, and generates standalone distribution artifacts for {@code projectDir} into {@code outputDir}.
     *
     * @param projectDir            source KAGAYAKI project root directory
     * @param outputDir             target standalone distribution directory
     * @param optionalStandaloneJar optional prebuilt {@code kagayaki-standalone.jar} to bundle (nullable)
     * @return immutable {@link BuildResult} describing generated artifacts
     * @throws IllegalStateException if {@link VJavaLinter#lint(Path)} reports any errors
     * @throws IOException           if reading project metadata or writing distribution files fails
     */
    public static BuildResult build(
            Path projectDir,
            Path outputDir,
            Path optionalStandaloneJar
    ) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");

        Path normalizedProject = projectDir.toAbsolutePath().normalize();
        Path normalizedOutput = outputDir.toAbsolutePath().normalize();

        VJavaLinter.LintReport lintReport = VJavaLinter.lint(normalizedProject);
        if (lintReport.hasErrors()) {
            throw new IllegalStateException(formatLintFailure(lintReport));
        }

        Files.createDirectories(normalizedOutput);

        ProjectDescriptor descriptor = loadDescriptorOrDefault(normalizedProject);
        String encryptionKey = descriptor.getVpakEncryptionKey();
        Path vpakFile = normalizedOutput.resolve(VPAK_FILE_NAME);
        List<VpakArchiver.VpakEntryInfo> packedEntries =
                VpakArchiver.pack(normalizedProject, vpakFile, encryptionKey);

        Path bundledJar = bundleOrWriteRuntimeReference(normalizedOutput, optionalStandaloneJar);
        Path shLauncher = writeUnixLauncher(normalizedOutput);
        writeWindowsLauncher(normalizedOutput);
        Path packagingDescriptor = writePackagingDescriptor(
                normalizedOutput,
                descriptor,
                packedEntries.size(),
                bundledJar != null
        );

        return new BuildResult(
                normalizedOutput,
                vpakFile,
                bundledJar,
                shLauncher,
                packagingDescriptor,
                packedEntries.size()
        );
    }

    private static ProjectDescriptor loadDescriptorOrDefault(Path projectDir) throws IOException {
        Path descriptorPath = projectDir.resolve(ProjectDescriptor.FILE_NAME);
        if (Files.isRegularFile(descriptorPath)) {
            return ProjectDescriptor.load(projectDir);
        }
        Path name = projectDir.getFileName();
        return ProjectDescriptor.createDefault(name != null ? name.toString() : "KAGAYAKI Standalone");
    }

    private static Path bundleOrWriteRuntimeReference(Path outputDir, Path optionalStandaloneJar) throws IOException {
        if (optionalStandaloneJar != null && Files.isRegularFile(optionalStandaloneJar)) {
            Path targetJar = outputDir.resolve(STANDALONE_JAR_NAME);
            Files.copy(
                    optionalStandaloneJar.toAbsolutePath().normalize(),
                    targetJar,
                    StandardCopyOption.REPLACE_EXISTING);
            return targetJar;
        }

        String reference = """
                KAGAYAKI Standalone Runtime Reference
                =====================================

                No prebuilt standalone JAR was supplied during export.
                To complete this standalone package:

                  1. Build the Studio-free standalone JAR in the KAGAYAKI workspace:
                       ./gradlew packageStandalone

                  2. Copy the generated JAR from:
                       build/dist/kagayaki-standalone-<version>.jar
                     to this directory as:
                       """ + STANDALONE_JAR_NAME + """

                  3. Launch Window B (Standalone Game only, without Studio tools):
                       ./""" + LAUNCHER_SH_NAME + "   (macOS/Linux)\n"
                + "                       " + LAUNCHER_BAT_NAME + "   (Windows)\n\n"
                + "Main class: " + STANDALONE_MAIN_CLASS + " --standalone .\n"
                + "Archive:    " + VPAK_FILE_NAME + "\n";

        Files.writeString(outputDir.resolve(RUNTIME_REFERENCE_NAME), reference, StandardCharsets.UTF_8);
        return null;
    }

    private static Path writeUnixLauncher(Path outputDir) throws IOException {
        String script = """
                #!/usr/bin/env sh
                set -eu

                SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
                cd "$SCRIPT_DIR"

                JVM_OPTS=""
                case "$(uname -s)" in
                  Darwin*) JVM_OPTS="-XstartOnFirstThread" ;;
                esac

                JAR_FILE="${KAGAYAKI_STANDALONE_JAR:-""" + STANDALONE_JAR_NAME + """
                }"
                if [ ! -f "$JAR_FILE" ]; then
                  echo "Standalone runtime JAR not found: $JAR_FILE" >&2
                  echo "Build it via './gradlew packageStandalone' and place it in $SCRIPT_DIR." >&2
                  exit 1
                fi

                exec java $JVM_OPTS -cp "$JAR_FILE" """ + STANDALONE_MAIN_CLASS + """
                 --standalone . "$@"
                """;

        Path shPath = outputDir.resolve(LAUNCHER_SH_NAME);
        Files.writeString(shPath, script, StandardCharsets.UTF_8);
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE
            );
            Files.setPosixFilePermissions(shPath, perms);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems (e.g., Windows) do not support POSIX file permissions
        }
        return shPath;
    }

    private static void writeWindowsLauncher(Path outputDir) throws IOException {
        String script = "@echo off\r\n"
                + "setlocal\r\n"
                + "cd /d \"%~dp0\"\r\n"
                + "set \"JAR_FILE=%KAGAYAKI_STANDALONE_JAR%\"\r\n"
                + "if \"%JAR_FILE%\"==\"\" set \"JAR_FILE=" + STANDALONE_JAR_NAME + "\"\r\n"
                + "if not exist \"%JAR_FILE%\" (\r\n"
                + "  echo Standalone runtime JAR not found: %JAR_FILE% 1>&2\r\n"
                + "  echo Build it via gradlew.bat packageStandalone and place it in \"%~dp0\". 1>&2\r\n"
                + "  exit /b 1\r\n"
                + ")\r\n"
                + "java -cp \"%JAR_FILE%\" " + STANDALONE_MAIN_CLASS + " --standalone . %*\r\n";

        Files.writeString(outputDir.resolve(LAUNCHER_BAT_NAME), script, StandardCharsets.UTF_8);
    }

    private static Path writePackagingDescriptor(
            Path outputDir,
            ProjectDescriptor descriptor,
            int packagedEntries,
            boolean jarBundled
    ) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("title", descriptor.getTitle());
        root.put("version", descriptor.getVersion());
        root.put("author", descriptor.getAuthor());
        root.put("renderMode", descriptor.getRenderMode());
        root.put("entryLabel", descriptor.getEntryLabel());
        root.put("mainClass", STANDALONE_MAIN_CLASS);
        root.put("mainArgs", List.of("--standalone", "."));
        root.put("vpakFile", VPAK_FILE_NAME);
        root.put("vpakEncrypted", descriptor.getVpakEncryptionKey() != null
                && !descriptor.getVpakEncryptionKey().isBlank());
        root.put("packagedEntries", packagedEntries);
        root.put("standaloneJar", STANDALONE_JAR_NAME);
        root.put("standaloneJarBundled", jarBundled);
        root.put("runtimeReferenceFile", jarBundled ? null : RUNTIME_REFERENCE_NAME);
        root.put("gradleTask", "packageStandalone");
        root.put("gradleOutputPattern", "build/dist/kagayaki-standalone-<version>.jar");

        Map<String, Object> launchers = new LinkedHashMap<>();
        launchers.put("unix", LAUNCHER_SH_NAME);
        launchers.put("windows", LAUNCHER_BAT_NAME);
        root.put("launchers", launchers);

        Map<String, Object> jvmOptions = new LinkedHashMap<>();
        jvmOptions.put("macOS", List.of("-XstartOnFirstThread"));
        jvmOptions.put("default", List.of());
        root.put("jvmOptions", jvmOptions);

        Map<String, Object> jlink = new LinkedHashMap<>();
        jlink.put("addModules", List.of("java.base", "java.logging", "java.compiler", "jdk.unsupported"));
        jlink.put("stripDebug", true);
        jlink.put("compress", "zip-6");
        jlink.put("noHeaderFiles", true);
        jlink.put("noManPages", true);
        root.put("jlink", jlink);

        Map<String, Object> jpackage = new LinkedHashMap<>();
        jpackage.put("name", sanitizeAppName(descriptor.getTitle()));
        jpackage.put("appVersion", descriptor.getVersion());
        jpackage.put("input", ".");
        jpackage.put("mainJar", STANDALONE_JAR_NAME);
        jpackage.put("mainClass", STANDALONE_MAIN_CLASS);
        jpackage.put("arguments", List.of("--standalone", "."));
        jpackage.put("macJavaOptions", List.of("-XstartOnFirstThread"));
        root.put("jpackage", jpackage);

        Path descriptorPath = outputDir.resolve(PACKAGING_DESCRIPTOR_NAME);
        Files.writeString(descriptorPath, GSON.toJson(root), StandardCharsets.UTF_8);
        return descriptorPath;
    }

    private static String sanitizeAppName(String title) {
        if (title == null || title.isBlank()) {
            return "KagayakiGame";
        }
        String sanitized = title.replaceAll("[^A-Za-z0-9._-]", "");
        return sanitized.isBlank() ? "KagayakiGame" : sanitized;
    }

    private static String formatLintFailure(VJavaLinter.LintReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("Standalone export aborted due to ")
                .append(report.errorCount())
                .append(" lint error(s):");
        for (VJavaLinter.LintIssue issue : report.issues()) {
            if (issue.severity() == VJavaLinter.Severity.ERROR) {
                sb.append("\n  - [").append(issue.code()).append("] ");
                if (!issue.sourceFile().isBlank()) {
                    sb.append(issue.sourceFile());
                    if (issue.lineNumber() > 0) {
                        sb.append(':').append(issue.lineNumber());
                    }
                    sb.append(": ");
                }
                sb.append(issue.message());
            }
        }
        return sb.toString();
    }
}
