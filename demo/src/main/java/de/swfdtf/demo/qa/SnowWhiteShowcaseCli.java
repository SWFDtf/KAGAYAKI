package de.swfdtf.demo.qa;

import de.swfdtf.demo.qa.SnowWhiteFlowAndTranslationReport.AuditBundle;
import de.swfdtf.demo.qa.SnowWhiteIntegrityAuditor.IntegrityAuditReport;
import de.swfdtf.demo.qa.SnowWhitePlaythroughSuite.CombinedPlaythroughReport;
import de.swfdtf.tools.AutomatedPlaythroughRunner.ChoiceStrategy;
import de.swfdtf.tools.AutomatedPlaythroughRunner.PlaythroughReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * CLI- und Automatisierungs-Fassade, die alle Schneewittchen-QA-, Audit-, Integritäts- und
 * Diagnoseberichte erzeugt und sauber in fünf Ausgabedateien in ein Zielverzeichnis schreibt.
 */
public final class SnowWhiteShowcaseCli {

    /** Dateiname des exportierten Mermaid-Kontrollflussdiagramms. */
    public static final String FLOW_MERMAID_FILE = "snowwhite-flow.mmd";
    /** Dateiname des kombinierten deutschen Audit-Markdown-Berichts. */
    public static final String AUDIT_MARKDOWN_FILE = "snowwhite-audit.md";
    /** Dateiname des JSON-Diagnosebundles. */
    public static final String DIAGNOSTICS_JSON_FILE = "snowwhite-diagnostics.json";
    /** Dateiname des SHA-256-Asset-Manifests. */
    public static final String MANIFEST_JSON_FILE = "snowwhite-asset-manifest.json";
    /** Dateiname der Release-Pre-Flight-Checkliste. */
    public static final String PREFLIGHT_MARKDOWN_FILE = "snowwhite-preflight.md";

    private final SnowWhitePlaythroughSuite playthroughSuite;
    private final SnowWhiteFlowAndTranslationReport flowAndTranslationReport;
    private final SnowWhiteIntegrityAuditor integrityAuditor;
    private final SnowWhiteDiagnosticExporter diagnosticExporter;

    /**
     * Unveränderliche Pfad-Zusammenfassung aller im Showcase-Verzeichnis erzeugten Dateien.
     *
     * @param outputDir             normalisiertes Zielverzeichnis
     * @param flowMermaidFile       Pfad zur Mermaid-Flow-Datei
     * @param auditMarkdownFile     Pfad zum deutschen Audit-Markdown-Bericht
     * @param diagnosticsJsonFile   Pfad zum JSON-Diagnosebundle
     * @param manifestJsonFile      Pfad zum SHA-256-Asset-Manifest
     * @param preFlightMarkdownFile Pfad zur Pre-Flight-Checkliste im Markdown-Format
     */
    public record ShowcaseOutput(
            Path outputDir,
            Path flowMermaidFile,
            Path auditMarkdownFile,
            Path diagnosticsJsonFile,
            Path manifestJsonFile,
            Path preFlightMarkdownFile
    ) {

        /**
         * Validiert alle sechs Ausgabepfade.
         */
        public ShowcaseOutput {
            Objects.requireNonNull(outputDir, "outputDir darf nicht null sein");
            Objects.requireNonNull(flowMermaidFile, "flowMermaidFile darf nicht null sein");
            Objects.requireNonNull(auditMarkdownFile, "auditMarkdownFile darf nicht null sein");
            Objects.requireNonNull(diagnosticsJsonFile, "diagnosticsJsonFile darf nicht null sein");
            Objects.requireNonNull(manifestJsonFile, "manifestJsonFile darf nicht null sein");
            Objects.requireNonNull(preFlightMarkdownFile, "preFlightMarkdownFile darf nicht null sein");
        }
    }

    /**
     * Erzeugt eine {@code SnowWhiteShowcaseCli} mit Standard-QA- und Audit-Komponenten.
     */
    public SnowWhiteShowcaseCli() {
        this(
                new SnowWhitePlaythroughSuite(),
                new SnowWhiteFlowAndTranslationReport(),
                new SnowWhiteIntegrityAuditor(),
                new SnowWhiteDiagnosticExporter()
        );
    }

    /**
     * Erzeugt eine {@code SnowWhiteShowcaseCli} mit explizit injizierten Komponenten.
     *
     * @param playthroughSuite         Suite für alle Playthrough-Strategien (darf nicht {@code null} sein)
     * @param flowAndTranslationReport Analyzer für Flow, Übersetzung und Assets (darf nicht {@code null} sein)
     * @param integrityAuditor         Integrator für Checksummen, Metadaten, Pre-Flight und {@code .vpak} (darf nicht {@code null} sein)
     * @param diagnosticExporter       Exporter für profilierte Diagnose-JSONs (darf nicht {@code null} sein)
     */
    public SnowWhiteShowcaseCli(
            SnowWhitePlaythroughSuite playthroughSuite,
            SnowWhiteFlowAndTranslationReport flowAndTranslationReport,
            SnowWhiteIntegrityAuditor integrityAuditor,
            SnowWhiteDiagnosticExporter diagnosticExporter
    ) {
        this.playthroughSuite = Objects.requireNonNull(playthroughSuite, "playthroughSuite darf nicht null sein");
        this.flowAndTranslationReport = Objects.requireNonNull(
                flowAndTranslationReport, "flowAndTranslationReport darf nicht null sein");
        this.integrityAuditor = Objects.requireNonNull(integrityAuditor, "integrityAuditor darf nicht null sein");
        this.diagnosticExporter = Objects.requireNonNull(diagnosticExporter, "diagnosticExporter darf nicht null sein");
    }

    /**
     * Führt alle QA-, Audit-, Integritäts- und Diagnose-Workflows für {@code projectDir} aus
     * und schreibt die fünf Showcase-Artefakte in {@code outputDir}.
     *
     * @param projectDir Wurzelverzeichnis des KAGAYAKI-{@code .vjava}-Projekts (darf nicht {@code null} sein)
     * @param outputDir  Zielverzeichnis für die erzeugten Berichtsdateien (darf nicht {@code null} sein)
     * @return {@link ShowcaseOutput} mit allen fünf Dateipfaden
     * @throws IOException              falls Dateisystem- oder Exportoperationen fehlschlagen
     * @throws IllegalArgumentException falls {@code projectDir} kein existierendes Verzeichnis ist
     */
    public ShowcaseOutput generateShowcase(Path projectDir, Path outputDir) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir darf nicht null sein");
        Objects.requireNonNull(outputDir, "outputDir darf nicht null sein");

        Path normalizedProject = projectDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedProject)) {
            throw new IllegalArgumentException("Projektverzeichnis existiert nicht oder ist kein Verzeichnis: " + normalizedProject);
        }
        Path normalizedOutput = outputDir.toAbsolutePath().normalize();
        Files.createDirectories(normalizedOutput);

        Path tempRoot = Files.createTempDirectory("snowwhite-showcase-");
        try {
            Path workDir = tempRoot.resolve("work");
            Path savesDir = tempRoot.resolve("saves");
            Files.createDirectories(workDir);
            Files.createDirectories(savesDir);

            IntegrityAuditReport integrityReport = integrityAuditor.audit(normalizedProject, workDir);
            CombinedPlaythroughReport playthroughReport = playthroughSuite.runAllStrategies(normalizedProject);
            AuditBundle auditBundle = flowAndTranslationReport.analyze(normalizedProject);

            Path flowMermaidFile = normalizedOutput.resolve(FLOW_MERMAID_FILE);
            Path auditMarkdownFile = normalizedOutput.resolve(AUDIT_MARKDOWN_FILE);
            Path diagnosticsTarget = normalizedOutput.resolve(DIAGNOSTICS_JSON_FILE);
            Path manifestJsonFile = normalizedOutput.resolve(MANIFEST_JSON_FILE);
            Path preFlightMarkdownFile = normalizedOutput.resolve(PREFLIGHT_MARKDOWN_FILE);

            Files.writeString(flowMermaidFile, auditBundle.mermaidDiagram(), StandardCharsets.UTF_8);
            Files.writeString(
                    auditMarkdownFile,
                    appendPlaythroughSummary(auditBundle.markdownReport(), playthroughReport, integrityReport),
                    StandardCharsets.UTF_8
            );
            Path diagnosticsJsonFile = diagnosticExporter.exportProfiledDiagnostics(
                    normalizedProject,
                    savesDir,
                    diagnosticsTarget
            );
            Files.writeString(manifestJsonFile, integrityReport.manifest().toJson(), StandardCharsets.UTF_8);
            Files.writeString(
                    preFlightMarkdownFile,
                    integrityReport.preFlightReport().summaryMarkdown(),
                    StandardCharsets.UTF_8
            );

            return new ShowcaseOutput(
                    normalizedOutput,
                    flowMermaidFile,
                    auditMarkdownFile,
                    diagnosticsJsonFile,
                    manifestJsonFile,
                    preFlightMarkdownFile
            );
        } finally {
            deleteRecursivelyQuietly(tempRoot);
        }
    }

    /**
     * Kommandozeilen-Einstiegspunkt für den Schneewittchen-Showcase-Generator.
     *
     * <p>Unterstützt {@code --project <ordner>} und {@code --output <zielordner>}
     * (oder {@code --showcase <zielordner>}).</p>
     *
     * @param args Kommandozeilenargumente
     * @throws IOException falls Dateizugriffe fehlschlagen
     */
    public static void main(String[] args) throws IOException {
        Path projectDir = resolveDefaultProjectDir();
        Path outputDir = Path.of("build", "showcase");

        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--project" -> {
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException("Pfad nach --project fehlt.");
                        }
                        projectDir = Path.of(args[++i]);
                    }
                    case "--output", "--showcase" -> {
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException("Zielverzeichnis nach " + arg + " fehlt.");
                        }
                        outputDir = Path.of(args[++i]);
                    }
                    default -> throw new IllegalArgumentException("Unbekanntes Argument: " + arg);
                }
            }
        }

        ShowcaseOutput output = new SnowWhiteShowcaseCli().generateShowcase(projectDir, outputDir);
        System.out.println("[Showcase] 5 Berichtsdateien erzeugt unter: " + output.outputDir());
        System.out.println("  - Flow (Mermaid): " + output.flowMermaidFile());
        System.out.println("  - Audit (Markdown): " + output.auditMarkdownFile());
        System.out.println("  - Diagnostik (JSON): " + output.diagnosticsJsonFile());
        System.out.println("  - Asset-Manifest (JSON): " + output.manifestJsonFile());
        System.out.println("  - Pre-Flight (Markdown): " + output.preFlightMarkdownFile());
    }

    private static String appendPlaythroughSummary(
            String baseMarkdown,
            CombinedPlaythroughReport playthroughReport,
            IntegrityAuditReport integrityReport
    ) {
        StringBuilder sb = new StringBuilder(baseMarkdown);
        if (!baseMarkdown.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append("\n## 4. Automatisierte Playthrough-Strategien\n\n");
        sb.append("- **Alle Strategien sauber beendet:** ")
                .append(playthroughReport.allEndedCleanly() ? "JA" : "NEIN")
                .append('\n');
        sb.append("- **Kombinierte besuchte Labels:** ")
                .append(playthroughReport.combinedVisitedLabels().size())
                .append("\n\n");
        sb.append("| Strategie | Sauber beendet | Schritte | Dialoge | Entscheidungen | Labels |\n");
        sb.append("| :--- | :--- | ---: | ---: | ---: | ---: |\n");
        for (Map.Entry<ChoiceStrategy, PlaythroughReport> entry : playthroughReport.reports().entrySet()) {
            PlaythroughReport report = entry.getValue();
            sb.append("| ")
                    .append(entry.getKey().name())
                    .append(" | ").append(report.endedCleanly() ? "JA" : "NEIN")
                    .append(" | ").append(report.stepsExecuted())
                    .append(" | ").append(report.dialogueCount())
                    .append(" | ").append(report.choicesTaken())
                    .append(" | ").append(report.visitedLabels().size())
                    .append(" |\n");
        }

        sb.append("\n## 5. Integritäts- & Archivprüfung\n\n");
        sb.append("- **Gesamtstatus:** ")
                .append(integrityReport.allPassed() ? "BESTANDEN" : "FEHLGESCHLAGEN")
                .append('\n');
        sb.append("- **Geprüfte Asset-Checksummen:** ")
                .append(integrityReport.checksumReport().validCount())
                .append('\n');
        sb.append("- **`.vpak`-Einträge:** ")
                .append(integrityReport.vpakReport().entryCount())
                .append(" (Integrität verifiziert: ")
                .append(integrityReport.vpakReport().integrityVerified() ? "JA" : "NEIN")
                .append(")\n");
        return sb.toString();
    }

    private static Path resolveDefaultProjectDir() {
        Path direct = Path.of("project");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path fromRepoRoot = Path.of("demo", "project");
        if (Files.isDirectory(fromRepoRoot)) {
            return fromRepoRoot;
        }
        return direct;
    }

    private static void deleteRecursivelyQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temporäres Arbeitsverzeichnis wird nach Best-Effort bereinigt.
                }
            });
        } catch (IOException ignored) {
            // Temporäres Arbeitsverzeichnis wird nach Best-Effort bereinigt.
        }
    }
}
