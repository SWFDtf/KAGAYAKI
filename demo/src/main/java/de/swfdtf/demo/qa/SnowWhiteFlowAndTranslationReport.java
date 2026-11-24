package de.swfdtf.demo.qa;

import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.tools.ScriptFlowGraphExporter;
import de.swfdtf.tools.ScriptFlowGraphExporter.FlowGraph;
import de.swfdtf.tools.TranslationCoverageAuditor;
import de.swfdtf.tools.TranslationCoverageAuditor.CoverageReport;
import de.swfdtf.tools.TranslationCoverageAuditor.LanguageCoverage;
import de.swfdtf.tools.UnusedAssetDetector;
import de.swfdtf.tools.UnusedAssetDetector.AssetUsageReport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Kombiniert Kontrollfluss-Analyse ({@link ScriptFlowGraphExporter}),
 * Übersetzungsabdeckung ({@link TranslationCoverageAuditor}) und Asset-Nutzungsprüfung
 * ({@link UnusedAssetDetector}) zu einem einheitlichen {@link AuditBundle} inklusive
 * lesbarer deutscher Markdown-Zusammenfassung.
 */
public final class SnowWhiteFlowAndTranslationReport {

    private final ScriptFlowGraphExporter flowExporter;
    private final TranslationCoverageAuditor translationAuditor;
    private final UnusedAssetDetector assetDetector;

    /**
     * Unveränderliches Gesamtergebnis aus Flow-Graph, Übersetzungs-Audit, Asset-Prüfung
     * und formatiertem deutschen Markdown-Bericht.
     *
     * @param flowGraph         extrahierter Kontrollflussgraph
     * @param mermaidDiagram    Mermaid-Darstellung ({@code flowchart TD})
     * @param dotDiagram        Graphviz-DOT-Darstellung
     * @param translationReport Bericht über die Lokalisierungsabdeckung
     * @param assetUsageReport  Bericht über verwendete, ungenutzte und fehlende Assets
     * @param markdownReport    zusammenfassender deutscher Markdown-Bericht
     */
    public record AuditBundle(
            FlowGraph flowGraph,
            String mermaidDiagram,
            String dotDiagram,
            CoverageReport translationReport,
            AssetUsageReport assetUsageReport,
            String markdownReport
    ) {

        /**
         * Stellt nicht-null und nicht-leere Berichtsbestandteile sicher.
         */
        public AuditBundle {
            Objects.requireNonNull(flowGraph, "flowGraph darf nicht null sein");
            Objects.requireNonNull(mermaidDiagram, "mermaidDiagram darf nicht null sein");
            Objects.requireNonNull(dotDiagram, "dotDiagram darf nicht null sein");
            Objects.requireNonNull(translationReport, "translationReport darf nicht null sein");
            Objects.requireNonNull(assetUsageReport, "assetUsageReport darf nicht null sein");
            Objects.requireNonNull(markdownReport, "markdownReport darf nicht null sein");
            if (mermaidDiagram.isBlank()) {
                throw new IllegalArgumentException("mermaidDiagram darf nicht leer sein");
            }
            if (dotDiagram.isBlank()) {
                throw new IllegalArgumentException("dotDiagram darf nicht leer sein");
            }
            if (markdownReport.isBlank()) {
                throw new IllegalArgumentException("markdownReport darf nicht leer sein");
            }
        }

        /**
         * Sucht die Übersetzungsabdeckung für die angegebene Sprache.
         *
         * @param language Sprachbezeichner (darf nicht {@code null} sein)
         * @return optionale {@link LanguageCoverage}
         */
        public Optional<LanguageCoverage> findLanguageCoverage(String language) {
            Objects.requireNonNull(language, "language darf nicht null sein");
            return translationReport.findLanguage(language);
        }
    }

    /**
     * Erzeugt einen {@code SnowWhiteFlowAndTranslationReport} mit Standard-Analysewerkzeugen.
     */
    public SnowWhiteFlowAndTranslationReport() {
        this(new ScriptFlowGraphExporter(), new TranslationCoverageAuditor(), new UnusedAssetDetector());
    }

    /**
     * Erzeugt einen {@code SnowWhiteFlowAndTranslationReport} mit explizit injizierten Analysekomponenten.
     *
     * @param flowExporter       Exporter für Kontrollflussgraphen (darf nicht {@code null} sein)
     * @param translationAuditor Auditor für Übersetzungsabdeckung (darf nicht {@code null} sein)
     * @param assetDetector      Detektor für ungenutzte/fehlende Assets (darf nicht {@code null} sein)
     */
    public SnowWhiteFlowAndTranslationReport(
            ScriptFlowGraphExporter flowExporter,
            TranslationCoverageAuditor translationAuditor,
            UnusedAssetDetector assetDetector
    ) {
        this.flowExporter = Objects.requireNonNull(flowExporter, "flowExporter darf nicht null sein");
        this.translationAuditor = Objects.requireNonNull(translationAuditor, "translationAuditor darf nicht null sein");
        this.assetDetector = Objects.requireNonNull(assetDetector, "assetDetector darf nicht null sein");
    }

    /**
     * Scannt das Projekt unter {@code projectDir} und erstellt ein vollständiges {@link AuditBundle}.
     *
     * @param projectDir Wurzelverzeichnis des KAGAYAKI-{@code .vjava}-Projekts (darf nicht {@code null} sein)
     * @return unveränderliches {@link AuditBundle}
     * @throws IllegalArgumentException falls {@code projectDir} kein gültiges Verzeichnis ist
     */
    public AuditBundle analyze(Path projectDir) {
        Objects.requireNonNull(projectDir, "projectDir darf nicht null sein");
        Path normalizedDir = projectDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedDir)) {
            throw new IllegalArgumentException("Projektverzeichnis existiert nicht oder ist kein Verzeichnis: " + normalizedDir);
        }
        VJavaAst.ProjectAst project = ProjectScanner.scan(normalizedDir);
        return analyze(project);
    }

    /**
     * Analysiert einen bereits eingelesenen {@link VJavaAst.ProjectAst} und erstellt das {@link AuditBundle}.
     *
     * @param project geparster Projekt-AST (darf nicht {@code null} sein)
     * @return unveränderliches {@link AuditBundle}
     */
    public AuditBundle analyze(VJavaAst.ProjectAst project) {
        Objects.requireNonNull(project, "project darf nicht null sein");

        FlowGraph flowGraph = flowExporter.buildGraph(project);
        String mermaidDiagram = flowExporter.toMermaid(flowGraph);
        String dotDiagram = flowExporter.toDot(flowGraph);
        CoverageReport translationReport = translationAuditor.audit(project);
        AssetUsageReport assetUsageReport = assetDetector.analyze(project);

        String markdownReport = buildMarkdownReport(
                project.descriptor(),
                flowGraph,
                translationReport,
                assetUsageReport
        );

        return new AuditBundle(
                flowGraph,
                mermaidDiagram,
                dotDiagram,
                translationReport,
                assetUsageReport,
                markdownReport
        );
    }

    private static String buildMarkdownReport(
            ProjectDescriptor descriptor,
            FlowGraph flowGraph,
            CoverageReport translationReport,
            AssetUsageReport assetUsageReport
    ) {
        StringBuilder sb = new StringBuilder();
        String title = descriptor != null && descriptor.getTitle() != null && !descriptor.getTitle().isBlank()
                ? descriptor.getTitle()
                : "Schneewittchen (vJava-Demo)";
        String version = descriptor != null && descriptor.getVersion() != null && !descriptor.getVersion().isBlank()
                ? descriptor.getVersion()
                : "unbekannt";

        sb.append("# Qualitäts-, Flow- & Lokalisierungsbericht: ").append(title).append("\n\n");
        sb.append("- **Version:** ").append(version).append('\n');
        sb.append("- **Einstiegs-Label:** `").append(flowGraph.entryLabel()).append("`\n\n");

        sb.append("## 1. Kontrollfluss-Analyse\n\n");
        sb.append("- **Definierte Labels:** ").append(flowGraph.labels().size()).append('\n');
        sb.append("- **Gerichtete Übergänge (Kanten):** ").append(flowGraph.edges().size()).append('\n');
        sb.append("- **Unerreichbare Labels:** ").append(flowGraph.unreachableLabels().size()).append('\n');
        if (!flowGraph.unreachableLabels().isEmpty()) {
            sb.append("  - Betroffen: ");
            sb.append(String.join(", ", flowGraph.unreachableLabels()));
            sb.append('\n');
        } else {
            sb.append("  - Alle definierten Skript-Labels sind erreichbar.\n");
        }
        sb.append('\n');

        sb.append("## 2. Übersetzungsabdeckung\n\n");
        sb.append("- **Erfasste Dialogzeilen gesamt:** ").append(translationReport.totalDialogueLines()).append('\n');
        if (translationReport.languages().isEmpty()) {
            sb.append("- Keine `translate`-Blöcke im Projekt gefunden.\n\n");
        } else {
            sb.append("\n| Sprache | Übersetzt | Erwartet | Abdeckung | Fehlend |\n");
            sb.append("| :--- | ---: | ---: | ---: | ---: |\n");
            for (Map.Entry<String, LanguageCoverage> entry : translationReport.languages().entrySet()) {
                LanguageCoverage cov = entry.getValue();
                sb.append(String.format(
                        Locale.ROOT,
                        "| %s | %d | %d | %.1f %% | %d |%n",
                        cov.language(),
                        cov.translatedEntries(),
                        cov.expectedEntries(),
                        cov.coveragePercent(),
                        cov.untranslatedTexts().size()
                ));
            }
            sb.append('\n');
        }

        sb.append("## 3. Asset-Nutzungsprüfung\n\n");
        sb.append("- **Entdeckte Assets (`assets/`):** ").append(assetUsageReport.discoveredAssets().size()).append('\n');
        sb.append("- **Referenzierte Assets:** ").append(assetUsageReport.referencedAssets().size()).append('\n');
        sb.append("- **Ungenutzte Assets:** ").append(assetUsageReport.unusedAssets().size()).append('\n');
        sb.append("- **Fehlende Assets:** ").append(assetUsageReport.missingAssets().size()).append('\n');
        sb.append("- **Status:** ")
                .append(assetUsageReport.isClean() ? "SAUBER (keine fehlenden Assets)" : "FEHLER (fehlende Assets gefunden)")
                .append('\n');

        if (!assetUsageReport.unusedAssets().isEmpty()) {
            sb.append("\n### Ungenutzte Asset-Pfade\n");
            for (String unused : assetUsageReport.unusedAssets()) {
                sb.append("- `").append(unused).append("`\n");
            }
        }
        if (!assetUsageReport.missingAssets().isEmpty()) {
            sb.append("\n### Fehlende Asset-Pfade\n");
            for (String missing : assetUsageReport.missingAssets()) {
                sb.append("- `").append(missing).append("`\n");
            }
        }

        return sb.toString();
    }
}
