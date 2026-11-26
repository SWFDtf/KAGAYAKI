package de.swfdtf.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.parser.JavaBlockCompiler;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptProfiler;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.parser.VJavaCompileException;
import de.swfdtf.state.SaveManager;
import de.swfdtf.state.SaveSlotData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Studio analysis, matrix editing, save inspection/fixture generation, Java-block diagnostic
 * mapping, ending verification, release-readiness checklists, and self-contained HTML dashboard
 * reporting ({@code #0105}, {@code #0106}, {@code #0139}, {@code #0140}, {@code #0146},
 * {@code #0159}, {@code #0161}, {@code #0165}).
 */
public final class StudioReportAndQaService {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeSpecialFloatingPointValues()
            .registerTypeAdapter(Instant.class, new InstantTypeAdapter())
            .create();

    private static final class InstantTypeAdapter extends TypeAdapter<Instant> {

        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return Instant.parse(in.nextString());
        }
    }

    /**
     * Single editable row in the Studio Translation Matrix ({@code #0105}).
     *
     * @param sourceText      source dialogue line
     * @param speakerId       optional speaker ID
     * @param targetLanguage  target language code
     * @param translatedText  existing translated string, or empty if missing
     * @param missing         {@code true} when target translation is absent/blank
     */
    public record TranslationMatrixRow(
            String sourceText,
            String speakerId,
            String targetLanguage,
            String translatedText,
            boolean missing
    ) {
    }

    /**
     * Ranked execution profiler hotspot item ({@code #0106}).
     *
     * @param label       label identifier
     * @param hitCount    total execution count
     * @param totalMillis cumulative time in milliseconds
     * @param maxMillis   single-step peak time in milliseconds
     */
    public record ProfilerHotspot(String label, long hitCount, double totalMillis, double maxMillis) {
    }

    /**
     * Save-slot inspection entry including graceful migration guidance for broken slots ({@code #0139}).
     *
     * @param slotId          slot file base name
     * @param valid           {@code true} if deserialization succeeded
     * @param currentLabel    active script label if valid
     * @param playtimeSeconds recorded playtime in seconds
     * @param variableCount   number of stored state variables
     * @param migrationHint   diagnostic or migration guidance if invalid or version-mismatched
     * @param slotData        loaded {@link SaveSlotData} when valid, else {@code null}
     */
    public record SaveSlotInspectionEntry(
            String slotId,
            boolean valid,
            String currentLabel,
            double playtimeSeconds,
            int variableCount,
            String migrationHint,
            SaveSlotData slotData
    ) {
    }

    /**
     * Compiler diagnostic mapped directly to a {@code .vjava} file and 1-based line ({@code #0146}).
     *
     * @param sourceFile project-relative {@code .vjava} path
     * @param lineNumber 1-based line inside the {@code .vjava} file
     * @param message    compiler diagnostic message
     */
    public record JavaBlockDiagnostic(String sourceFile, int lineNumber, String message) {
    }

    /**
     * Multi-strategy endings checklist report ({@code #0161}).
     *
     * @param reachedEndings immutable set of reached ending labels
     * @param missingEndings immutable list of expected endings never reached
     * @param reports        per-strategy playthrough reports
     */
    public record EndingCoverageSummary(
            Set<String> reachedEndings,
            List<String> missingEndings,
            List<AutomatedPlaythroughRunner.PlaythroughReport> reports
    ) {
    }

    /**
     * Release-readiness evaluation report ({@code #0165}).
     *
     * @param ready               {@code true} when icon, license, {@code >90%} i18n, and 0 lint errors pass
     * @param hasIcon             whether a valid icon file exists
     * @param hasLicense          whether {@code LICENSE} / {@code LICENSE.txt} / {@code LICENSE.md} exists
     * @param minTranslationPct   minimum language coverage percentage (100.0 if no target translations)
     * @param lintErrors          error count from {@link VJavaLinter}
     * @param summary             formatted checklist summary
     */
    public record ReleaseReadinessReport(
            boolean ready,
            boolean hasIcon,
            boolean hasLicense,
            double minTranslationPct,
            int lintErrors,
            String summary
    ) {
    }

    /** Creates a new {@code StudioReportAndQaService}. */
    public StudioReportAndQaService() {
    }

    /**
     * Builds the translation matrix rows for {@code targetLanguage} flagging missing entries in red
     * ({@code missing=true}) ({@code #0105}).
     *
     * @param project        parsed project AST
     * @param targetLanguage target language identifier (e.g., {@code "english"})
     * @return immutable list of {@link TranslationMatrixRow} rows
     */
    public List<TranslationMatrixRow> buildTranslationMatrix(VJavaAst.ProjectAst project, String targetLanguage) {
        Objects.requireNonNull(project, "project must not be null");
        String lang = Objects.requireNonNull(targetLanguage, "targetLanguage must not be null").trim();
        Map<String, String> existingTranslations = new LinkedHashMap<>();
        for (VJavaAst.TranslateBlock block : project.translations()) {
            if (block.language().equalsIgnoreCase(lang)) {
                for (VJavaAst.TranslateEntry entry : block.entries()) {
                    if (entry.originalText() != null && entry.translatedText() != null
                            && !entry.translatedText().isBlank()) {
                        existingTranslations.put(entry.originalText(), entry.translatedText());
                    }
                }
            }
        }
        List<TranslationMatrixRow> rows = new ArrayList<>();
        Set<String> seenLines = new LinkedHashSet<>();
        for (VJavaAst.LabelNode label : project.labels().values()) {
            for (VJavaAst.Statement stmt : label.statements()) {
                if (stmt instanceof VJavaAst.SayStatement say && seenLines.add(say.text())) {
                    String tr = existingTranslations.getOrDefault(say.text(), "");
                    rows.add(new TranslationMatrixRow(
                            say.text(), say.speakerId(), lang, tr, tr.isBlank()));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Saves or updates a single translation entry inside {@code tl/<lang>/translations.vjava} ({@code #0105}).
     *
     * @param projectDir     project root directory
     * @param targetLanguage target language code
     * @param originalText   source dialogue line
     * @param translatedText updated translation string
     * @return path to updated translation script file
     * @throws IOException if file I/O fails
     */
    public Path upsertTranslationMatrixEntry(
            Path projectDir,
            String targetLanguage,
            String originalText,
            String translatedText
    ) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        String lang = Objects.requireNonNull(targetLanguage, "targetLanguage must not be null").trim();
        String orig = Objects.requireNonNull(originalText, "originalText must not be null");
        String trans = Objects.requireNonNull(translatedText, "translatedText must not be null");
        Path tlDir = projectDir.toAbsolutePath().normalize().resolve("tl").resolve(lang);
        Files.createDirectories(tlDir);
        Path tlFile = tlDir.resolve("translations.vjava");
        String header = Files.isRegularFile(tlFile)
                ? Files.readString(tlFile, StandardCharsets.UTF_8)
                : "translate " + lang + " matrix:\n";
        String escapedOrig = orig.replace("\"", "\\\"");
        String escapedTrans = trans.replace("\"", "\\\"");
        String addition = "\n    old \"" + escapedOrig + "\"\n    new \"" + escapedTrans + "\"\n";
        String combined = header.stripTrailing() + addition;
        Files.writeString(tlFile, combined, StandardCharsets.UTF_8);
        return tlFile;
    }

    /**
     * Ranks labels from {@link ScriptProfiler} descending by total execution duration ({@code #0106}).
     *
     * @param profiler populated script profiler
     * @param limit    max entries to return
     * @return ordered list of {@link ProfilerHotspot} records
     */
    public List<ProfilerHotspot> rankHotspots(ScriptProfiler profiler, int limit) {
        Objects.requireNonNull(profiler, "profiler must not be null");
        List<ScriptProfiler.LabelProfile> top = profiler.topLabels(Math.max(1, limit));
        List<ProfilerHotspot> out = new ArrayList<>(top.size());
        for (ScriptProfiler.LabelProfile lp : top) {
            out.add(new ProfilerHotspot(lp.label(), lp.hitCount(), lp.totalMillis(),
                    lp.maxNanos() / 1_000_000.0));
        }
        return List.copyOf(out);
    }

    /**
     * Inspects all {@code .ksav} slots in {@code savesDir}, catching broken/incompatible files with
     * clear migration messages ({@code #0139}).
     *
     * @param savesDir saves directory path
     * @return list of {@link SaveSlotInspectionEntry} records
     * @throws IOException if directory listing fails
     */
    public List<SaveSlotInspectionEntry> inspectSaveDirectory(Path savesDir) throws IOException {
        Objects.requireNonNull(savesDir, "savesDir must not be null");
        Path dir = savesDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        SaveManager manager = new SaveManager(dir);
        List<SaveSlotInspectionEntry> entries = new ArrayList<>();
        List<Path> slotFiles;
        try (var stream = Files.list(dir)) {
            slotFiles = stream
                    .filter(p -> p.getFileName().toString().endsWith(SaveManager.SAVE_EXTENSION))
                    .sorted()
                    .toList();
        }
        for (Path file : slotFiles) {
            String fname = file.getFileName().toString();
            String slotId = fname.substring(0, fname.length() - SaveManager.SAVE_EXTENSION.length());
            try {
                SaveSlotData data = manager.loadSlot(slotId);
                String label = data.scriptPosition() != null ? data.scriptPosition().currentLabel() : "";
                double playtime = data.metadata() != null ? data.metadata().playtimeSeconds() : 0.0;
                entries.add(new SaveSlotInspectionEntry(
                        slotId, true, label, playtime, data.variables().size(), "", data));
            } catch (Exception ex) {
                entries.add(new SaveSlotInspectionEntry(
                        slotId, false, "", 0.0, 0,
                        "Defekter oder inkompatibler Speicherstand (" + ex.getMessage()
                                + ") — Migration über SaveMigrationStep empfohlen.",
                        null));
            }
        }
        return List.copyOf(entries);
    }

    /**
     * Exports a {@link SaveSlotData} snapshot as a reproducible JSON regression test fixture ({@code #0140}).
     *
     * @param slotData    source save slot data
     * @param fixtureFile destination JSON fixture file path
     * @return normalized path to written fixture file
     * @throws IOException if serialization fails
     */
    public Path exportSaveAsTestFixture(SaveSlotData slotData, Path fixtureFile) throws IOException {
        Objects.requireNonNull(slotData, "slotData must not be null");
        Objects.requireNonNull(fixtureFile, "fixtureFile must not be null");
        Path normalized = fixtureFile.toAbsolutePath().normalize();
        if (normalized.getParent() != null) {
            Files.createDirectories(normalized.getParent());
        }
        Files.writeString(normalized, GSON.toJson(slotData), StandardCharsets.UTF_8);
        return normalized;
    }

    /**
     * Loads a previously exported JSON regression test fixture back into {@link SaveSlotData} ({@code #0140}).
     *
     * @param fixtureFile JSON fixture file path
     * @return deserialized {@link SaveSlotData}
     * @throws IOException if reading or JSON parsing fails
     */
    public SaveSlotData loadTestFixture(Path fixtureFile) throws IOException {
        Objects.requireNonNull(fixtureFile, "fixtureFile must not be null");
        String json = Files.readString(fixtureFile, StandardCharsets.UTF_8);
        try {
            SaveSlotData loaded = GSON.fromJson(json, SaveSlotData.class);
            if (loaded == null) {
                throw new IOException("Empty save fixture: " + fixtureFile);
            }
            return loaded;
        } catch (JsonParseException ex) {
            throw new IOException("Invalid save fixture JSON: " + ex.getMessage(), ex);
        }
    }

    /**
     * Compiles all {@code @->{ ... }<<} blocks and returns 1:1 mapped {@code .vjava} source line
     * diagnostics ({@code #0146}).
     *
     * @param project scanned project AST
     * @return empty list if compilation succeeds, or mapped {@link JavaBlockDiagnostic} errors
     */
    public List<JavaBlockDiagnostic> diagnoseJavaBlocks(VJavaAst.ProjectAst project) {
        Objects.requireNonNull(project, "project must not be null");
        try {
            new JavaBlockCompiler().compile(project);
            return List.of();
        } catch (VJavaCompileException ex) {
            List<JavaBlockDiagnostic> out = new ArrayList<>();
            for (VJavaCompileException.CompileDiagnostic diag : ex.getDiagnostics()) {
                String src = diag.sourceFile().isBlank() ? ex.getSourceFile() : diag.sourceFile();
                int line = diag.lineNumber() > 0 ? diag.lineNumber() : ex.getLineNumber();
                out.add(new JavaBlockDiagnostic(src, Math.max(1, line), diag.message()));
            }
            if (out.isEmpty()) {
                out.add(new JavaBlockDiagnostic(ex.getSourceFile(), Math.max(1, ex.getLineNumber()),
                        ex.getMessage()));
            }
            return List.copyOf(out);
        }
    }

    /**
     * Generates a self-contained HTML project production dashboard with zero external CDN links
     * ({@code #0159}).
     *
     * @param projectDir project root directory
     * @param outputHtml target HTML file path
     * @return normalized path to written HTML file
     * @throws IOException if scanning or writing fails
     */
    public Path exportHtmlDashboard(Path projectDir, Path outputHtml) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Objects.requireNonNull(outputHtml, "outputHtml must not be null");
        Path root = projectDir.toAbsolutePath().normalize();
        Path htmlTarget = outputHtml.toAbsolutePath().normalize();
        if (htmlTarget.getParent() != null) {
            Files.createDirectories(htmlTarget.getParent());
        }

        VJavaAst.ProjectAst ast = ProjectScanner.scan(root);
        VJavaLinter.LintReport lint = VJavaLinter.lint(ast);
        ScriptWordCountAnalyzer.ScriptStatsReport stats = new ScriptWordCountAnalyzer().analyze(ast);
        String title = ast.descriptor() != null ? ast.descriptor().getTitle() : "KAGAYAKI Projekt";
        String version = ast.descriptor() != null ? ast.descriptor().getVersion() : "1.0.0";

        String html = """
                <!DOCTYPE html>
                <html lang="de">
                <head>
                  <meta charset="UTF-8">
                  <title>KAGAYAKI Dashboard — %s</title>
                  <style>
                    body { font-family: system-ui, sans-serif; background: #0d0e15; color: #f0f6fc; margin: 24px; }
                    .card { background: #161822; border: 1px solid #2e334a; border-radius: 8px; padding: 16px;
                            margin-bottom: 16px; }
                    h1, h2 { color: #58a6ff; }
                    table { width: 100%%; border-collapse: collapse; }
                    th, td { text-align: left; padding: 8px; border-bottom: 1px solid #2e334a; }
                  </style>
                </head>
                <body>
                  <h1>%s (v%s)</h1>
                  <div class="card">
                    <h2>Skript-Statistik</h2>
                    <p>Skripte: %d | Labels: %d | Dialogzeilen: %d | Wörter: %d | Geschätzte Lesezeit: %.1f min</p>
                  </div>
                  <div class="card">
                    <h2>Linter-Diagnose</h2>
                    <p>Fehler: %d | Warnungen: %d</p>
                  </div>
                </body>
                </html>
                """.formatted(
                escapeHtml(title), escapeHtml(title), escapeHtml(version),
                ast.scriptFiles().size(), ast.labels().size(), stats.totalLines(),
                stats.totalWords(), stats.estimatedReadingMinutes(),
                lint.errorCount(), lint.warningCount());

        Files.writeString(htmlTarget, html, StandardCharsets.UTF_8);
        return htmlTarget;
    }

    /**
     * Runs {@link AutomatedPlaythroughRunner} with all three strategies ({@code FIRST_CHOICE},
     * {@code LAST_CHOICE}, {@code ROUND_ROBIN}) and summarizes reached vs. missing endings ({@code #0161}).
     *
     * @param ast            parsed project AST
     * @param expectedLabels list of expected ending label names to check
     * @return {@link EndingCoverageSummary}
     */
    public EndingCoverageSummary testAllEndings(VJavaAst.ProjectAst ast, List<String> expectedLabels) {
        Objects.requireNonNull(ast, "ast must not be null");
        AutomatedPlaythroughRunner runner = new AutomatedPlaythroughRunner();
        List<AutomatedPlaythroughRunner.PlaythroughReport> reports = new ArrayList<>();
        Set<String> reached = new LinkedHashSet<>();
        for (AutomatedPlaythroughRunner.ChoiceStrategy strategy
                : AutomatedPlaythroughRunner.ChoiceStrategy.values()) {
            AutomatedPlaythroughRunner.PlaythroughReport report = runner.run(ast, strategy, 2000);
            reports.add(report);
            reached.addAll(report.visitedLabels());
        }
        List<String> missing = new ArrayList<>();
        if (expectedLabels != null) {
            for (String expected : expectedLabels) {
                if (expected != null && !reached.contains(expected)) {
                    missing.add(expected);
                }
            }
        }
        return new EndingCoverageSummary(
                Set.copyOf(reached), List.copyOf(missing), List.copyOf(reports));
    }

    /**
     * Evaluates full Release-Readiness checklist: icon present, license file present, minimum
     * translation coverage {@code > 90%}, and {@code 0} lint errors ({@code #0165}).
     *
     * @param projectDir project root directory
     * @return {@link ReleaseReadinessReport}
     * @throws IOException if project inspection fails
     */
    public ReleaseReadinessReport evaluateReleaseReadiness(Path projectDir) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Path root = projectDir.toAbsolutePath().normalize();
        ProjectDescriptor desc = Files.isRegularFile(root.resolve(ProjectDescriptor.FILE_NAME))
                ? ProjectDescriptor.load(root)
                : ProjectDescriptor.createDefault("Project");

        boolean hasIcon = desc.getIconPath() != null && !desc.getIconPath().isBlank()
                && Files.isRegularFile(root.resolve(desc.getIconPath()));
        boolean hasLicense = Files.isRegularFile(root.resolve("LICENSE"))
                || Files.isRegularFile(root.resolve("LICENSE.txt"))
                || Files.isRegularFile(root.resolve("LICENSE.md"));

        VJavaAst.ProjectAst ast = ProjectScanner.scan(root);
        VJavaLinter.LintReport lint = VJavaLinter.lint(ast);
        TranslationCoverageAuditor.CoverageReport cov = new TranslationCoverageAuditor().audit(ast);

        double minPct = 100.0;
        for (TranslationCoverageAuditor.LanguageCoverage lc : cov.languages().values()) {
            if (lc.coveragePercent() < minPct) {
                minPct = lc.coveragePercent();
            }
        }

        boolean ready = hasIcon && hasLicense && minPct > 90.0 && lint.errorCount() == 0;
        String summary = String.format(
                Locale.ROOT,
                "Release Checklist: ready=%s | icon=%s | license=%s | minI18n=%.1f%% | lintErrors=%d",
                ready, hasIcon, hasLicense, minPct, lint.errorCount());
        return new ReleaseReadinessReport(ready, hasIcon, hasLicense, minPct, lint.errorCount(), summary);
    }

    private static String escapeHtml(String text) {
        return text == null ? "" : text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
