package de.swfdtf.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Builds and atomically exports structured JSON diagnostic bundles containing engine version,
 * {@link EngineConfig} summary, recent {@link EngineLogger.LogEntry} lines, optional
 * {@link VJavaLinter.LintReport} issues, and custom key-value metrics.
 */
public final class DiagnosticBundleExporter {

    /** Default maximum number of recent log lines included in exported diagnostic bundles. */
    public static final int DEFAULT_MAX_LOG_ENTRIES = 200;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    private final int maxLogEntries;

    /**
     * Immutable summary of {@link EngineConfig} fields included in a diagnostic report.
     *
     * @param engineMode     execution mode name
     * @param renderModeName active render backend mode ({@code "2D"} or {@code "3D"})
     * @param projectDir     configured project directory string
     * @param logicalWidth   logical canvas width
     * @param logicalHeight  logical canvas height
     * @param vsync          whether VSync is enabled
     * @param targetFps      target FPS cap
     * @param smokeTest      whether smoke-test mode is enabled
     * @param headless       whether headless mode is enabled
     */
    public record ConfigSummary(
            String engineMode,
            String renderModeName,
            String projectDir,
            int logicalWidth,
            int logicalHeight,
            boolean vsync,
            int targetFps,
            boolean smokeTest,
            boolean headless
    ) {
    }

    /**
     * Immutable summary of a single {@link VJavaLinter.LintIssue}.
     *
     * @param severity   severity name ({@code "ERROR"} or {@code "WARNING"})
     * @param code       stable diagnostic code
     * @param message    human-readable message
     * @param sourceFile project-relative source file path
     * @param lineNumber 1-based line number
     */
    public record LintIssueSummary(
            String severity,
            String code,
            String message,
            String sourceFile,
            int lineNumber
    ) {
    }

    /**
     * Immutable summary of an optional {@link VJavaLinter.LintReport}.
     *
     * @param clean        {@code true} when zero errors and zero warnings were reported
     * @param hasErrors    {@code true} when at least one error was reported
     * @param errorCount   total number of errors
     * @param warningCount total number of warnings
     * @param issues       immutable list of summarized lint issues
     */
    public record LintSummary(
            boolean clean,
            boolean hasErrors,
            int errorCount,
            int warningCount,
            List<LintIssueSummary> issues
    ) {
        /** Normalizes null issue lists to an empty immutable list. */
        public LintSummary {
            issues = issues != null ? List.copyOf(issues) : List.of();
        }
    }

    /**
     * Immutable structured diagnostic bundle payload serialized to JSON.
     *
     * @param exportedAt     UTC ISO-8601 timestamp when the bundle was generated
     * @param engineVersion  KAGAYAKI engine version string
     * @param renderBackend  active render backend identifier
     * @param config         optional {@link EngineConfig} summary
     * @param recentLogLines immutable list of formatted recent log lines
     * @param lintReport     optional lint issue summary ({@code null} if not supplied)
     * @param metrics        immutable sorted map of custom diagnostic metrics
     */
    public record DiagnosticReport(
            String exportedAt,
            String engineVersion,
            String renderBackend,
            ConfigSummary config,
            List<String> recentLogLines,
            LintSummary lintReport,
            Map<String, Object> metrics
    ) {
        /** Validates required fields and defensively copies log and metric collections. */
        public DiagnosticReport {
            Objects.requireNonNull(exportedAt, "exportedAt must not be null");
            Objects.requireNonNull(engineVersion, "engineVersion must not be null");
            Objects.requireNonNull(renderBackend, "renderBackend must not be null");
            recentLogLines = recentLogLines != null ? List.copyOf(recentLogLines) : List.of();
            metrics = copySortedMetrics(metrics);
        }
    }

    /**
     * Creates a {@code DiagnosticBundleExporter} retaining up to {@link #DEFAULT_MAX_LOG_ENTRIES} log entries.
     */
    public DiagnosticBundleExporter() {
        this(DEFAULT_MAX_LOG_ENTRIES);
    }

    /**
     * Creates a {@code DiagnosticBundleExporter} with a configurable log entry cap.
     *
     * @param maxLogEntries maximum number of recent {@link EngineLogger.LogEntry} lines to include ({@code >= 0})
     */
    public DiagnosticBundleExporter(int maxLogEntries) {
        if (maxLogEntries < 0) {
            throw new IllegalArgumentException("maxLogEntries must be >= 0, got " + maxLogEntries);
        }
        this.maxLogEntries = maxLogEntries;
    }

    /**
     * Builds an in-memory {@link DiagnosticReport} from {@code api}, optional {@code optionalLintReport},
     * and optional {@code extraMetrics}.
     *
     * @param api                engine API facade (must not be {@code null})
     * @param optionalLintReport optional project lint report (may be {@code null})
     * @param extraMetrics       optional custom key-value metrics map (may be {@code null})
     * @return immutable {@link DiagnosticReport}
     */
    public DiagnosticReport buildReport(
            EngineAPI api,
            VJavaLinter.LintReport optionalLintReport,
            Map<String, Object> extraMetrics
    ) {
        Objects.requireNonNull(api, "api must not be null");

        String engineVersion = api.getEngineVersion() != null
                ? api.getEngineVersion()
                : EngineAPI.ENGINE_VERSION;
        String renderBackend = api.getRenderBackendName() != null
                ? api.getRenderBackendName()
                : EngineAPI.RENDER_BACKEND_NAME;

        ConfigSummary configSummary = summarizeConfig(api.getConfig());
        List<String> logLines = collectLogLines(api.getLogger(), maxLogEntries);
        LintSummary lintSummary = summarizeLintReport(optionalLintReport);
        Map<String, Object> metrics = copySortedMetrics(extraMetrics);

        return new DiagnosticReport(
                Instant.now().toString(),
                engineVersion,
                renderBackend,
                configSummary,
                logLines,
                lintSummary,
                metrics
        );
    }

    /**
     * Serializes a {@link DiagnosticReport} into formatted JSON.
     *
     * @param report diagnostic report to serialize (must not be {@code null})
     * @return pretty-printed JSON string
     */
    public String toJson(DiagnosticReport report) {
        Objects.requireNonNull(report, "report must not be null");
        return GSON.toJson(report);
    }

    /**
     * Builds and atomically writes a diagnostic JSON bundle to {@code targetFile}, creating parent
     * directories as needed.
     *
     * @param targetFile         destination JSON file path (must not be {@code null})
     * @param api                engine API facade (must not be {@code null})
     * @param optionalLintReport optional project lint report (may be {@code null})
     * @param extraMetrics       optional custom key-value metrics (may be {@code null})
     * @return normalized absolute path of the exported diagnostic file
     * @throws IOException if directory creation, serialization, or atomic file move fails
     */
    public Path export(
            Path targetFile,
            EngineAPI api,
            VJavaLinter.LintReport optionalLintReport,
            Map<String, Object> extraMetrics
    ) throws IOException {
        Objects.requireNonNull(targetFile, "targetFile must not be null");
        Objects.requireNonNull(api, "api must not be null");

        Path normalizedTarget = targetFile.toAbsolutePath().normalize();
        Path parent = normalizedTarget.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        DiagnosticReport report = buildReport(api, optionalLintReport, extraMetrics);
        String json = toJson(report);

        Path tempFile = parent != null
                ? Files.createTempFile(parent, "diagnostic-", ".tmp")
                : Files.createTempFile("diagnostic-", ".tmp");
        boolean moved = false;
        try {
            Files.writeString(tempFile, json, StandardCharsets.UTF_8);
            moveAtomically(tempFile, normalizedTarget);
            moved = true;
            return normalizedTarget;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    /**
     * Returns the configured maximum number of recent log lines included per bundle.
     *
     * @return max log entries
     */
    public int getMaxLogEntries() {
        return maxLogEntries;
    }

    private static ConfigSummary summarizeConfig(EngineConfig config) {
        if (config == null) {
            return null;
        }
        return new ConfigSummary(
                config.engineMode() != null ? config.engineMode().name() : "",
                config.renderModeName() != null ? config.renderModeName() : "",
                config.projectDir() != null ? config.projectDir().toString() : "",
                config.logicalWidth(),
                config.logicalHeight(),
                config.vsync(),
                config.targetFps(),
                config.smokeTest(),
                config.headless()
        );
    }

    private static List<String> collectLogLines(EngineLogger logger, int maxEntries) {
        if (logger == null || maxEntries <= 0) {
            return List.of();
        }
        List<EngineLogger.LogEntry> entries = logger.getRecentEntries(maxEntries);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(entries.size());
        for (EngineLogger.LogEntry entry : entries) {
            if (entry != null) {
                lines.add(entry.format());
            }
        }
        return List.copyOf(lines);
    }

    private static LintSummary summarizeLintReport(VJavaLinter.LintReport report) {
        if (report == null) {
            return null;
        }
        List<LintIssueSummary> issues = new ArrayList<>(report.issues().size());
        for (VJavaLinter.LintIssue issue : report.issues()) {
            issues.add(new LintIssueSummary(
                    issue.severity().name(),
                    issue.code(),
                    issue.message(),
                    issue.sourceFile(),
                    issue.lineNumber()
            ));
        }
        return new LintSummary(
                report.isClean(),
                report.hasErrors(),
                report.errorCount(),
                report.warningCount(),
                issues
        );
    }

    private static Map<String, Object> copySortedMetrics(Map<String, Object> extraMetrics) {
        if (extraMetrics == null || extraMetrics.isEmpty()) {
            return Map.of();
        }
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<String, Object> entry : extraMetrics.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "metric key must not be null");
            sorted.put(key, entry.getValue());
        }
        return Collections.unmodifiableMap(sorted);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
