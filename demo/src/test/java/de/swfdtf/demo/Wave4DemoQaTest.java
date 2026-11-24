package de.swfdtf.demo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.swfdtf.core.EngineAPI;
import de.swfdtf.demo.qa.SnowWhiteDiagnosticExporter;
import de.swfdtf.demo.qa.SnowWhiteFlowAndTranslationReport;
import de.swfdtf.demo.qa.SnowWhiteFlowAndTranslationReport.AuditBundle;
import de.swfdtf.demo.qa.SnowWhiteIntegrityAuditor;
import de.swfdtf.demo.qa.SnowWhiteIntegrityAuditor.IntegrityAuditReport;
import de.swfdtf.demo.qa.SnowWhitePlaythroughSuite;
import de.swfdtf.demo.qa.SnowWhitePlaythroughSuite.CombinedPlaythroughReport;
import de.swfdtf.demo.qa.SnowWhiteShowcaseCli;
import de.swfdtf.demo.qa.SnowWhiteShowcaseCli.ShowcaseOutput;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.tools.AssetChecksumVerifier;
import de.swfdtf.tools.AssetChecksumVerifier.AssetManifest;
import de.swfdtf.tools.AssetChecksumVerifier.VerificationReport;
import de.swfdtf.tools.AssetMetadataInspector;
import de.swfdtf.tools.AssetMetadataInspector.AssetMetadata;
import de.swfdtf.tools.AssetMetadataInspector.MediaType;
import de.swfdtf.tools.AutomatedPlaythroughRunner;
import de.swfdtf.tools.AutomatedPlaythroughRunner.ChoiceStrategy;
import de.swfdtf.tools.AutomatedPlaythroughRunner.PlaythroughReport;
import de.swfdtf.tools.DiagnosticBundleExporter;
import de.swfdtf.tools.ExportPreFlightChecker;
import de.swfdtf.tools.ExportPreFlightChecker.CheckItem;
import de.swfdtf.tools.ExportPreFlightChecker.CheckStatus;
import de.swfdtf.tools.ExportPreFlightChecker.PreFlightReport;
import de.swfdtf.tools.ScriptFlowGraphExporter;
import de.swfdtf.tools.ScriptFlowGraphExporter.EdgeKind;
import de.swfdtf.tools.ScriptFlowGraphExporter.FlowEdge;
import de.swfdtf.tools.ScriptFlowGraphExporter.FlowGraph;
import de.swfdtf.tools.StateSnapshotDiffService;
import de.swfdtf.tools.StateSnapshotDiffService.StateDiff;
import de.swfdtf.tools.TranslationCoverageAuditor;
import de.swfdtf.tools.TranslationCoverageAuditor.CoverageReport;
import de.swfdtf.tools.TranslationCoverageAuditor.LanguageCoverage;
import de.swfdtf.tools.UnusedAssetDetector;
import de.swfdtf.tools.UnusedAssetDetector.AssetUsageReport;
import de.swfdtf.tools.VpakManifestInspector;
import de.swfdtf.tools.VpakManifestInspector.VpakInspectionReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Umfassende JUnit-6- und Mockito-Testsuite für die Schneewittchen-QA-, Integritäts-,
 * Diagnose- und Showcase-Komponenten in {@code de.swfdtf.demo.qa}.
 */
@ExtendWith(MockitoExtension.class)
class Wave4DemoQaTest {

    @Mock
    private AutomatedPlaythroughRunner mockPlaythroughRunner;

    @Mock
    private ScriptFlowGraphExporter mockFlowExporter;

    @Mock
    private TranslationCoverageAuditor mockTranslationAuditor;

    @Mock
    private UnusedAssetDetector mockAssetDetector;

    @Mock
    private AssetChecksumVerifier mockChecksumVerifier;

    @Mock
    private AssetMetadataInspector mockMetadataInspector;

    @Mock
    private ExportPreFlightChecker mockPreFlightChecker;

    @Mock
    private VpakManifestInspector mockVpakInspector;

    private static Path resolveProjectDir() {
        Path direct = Path.of("project");
        return Files.isDirectory(direct) ? direct : Path.of("demo", "project");
    }

    @Test
    void playthroughSuiteRunsAllThreeStrategiesEndToEnd() {
        SnowWhitePlaythroughSuite suite = new SnowWhitePlaythroughSuite();
        CombinedPlaythroughReport report = suite.runAllStrategies(resolveProjectDir());

        assertTrue(report.allEndedCleanly(), "Alle Strategien müssen das Märchen sauber beenden: " + report.reports());
        assertEquals(
                Set.of(ChoiceStrategy.FIRST_CHOICE, ChoiceStrategy.LAST_CHOICE, ChoiceStrategy.ROUND_ROBIN),
                report.reports().keySet()
        );
        assertTrue(report.reportFor(ChoiceStrategy.FIRST_CHOICE).isPresent());
        assertTrue(report.combinedVisitedLabels().contains("kapitel_1_vorgeschichte"));
        assertTrue(report.combinedVisitedLabels().contains("zauberspiegel_befragen"));
        assertTrue(report.combinedVisitedLabels().contains("kapitel_4_haus_der_zwerge"));
        assertTrue(report.combinedVisitedLabels().contains("kapitel_5_mordversuche"));
        assertTrue(report.combinedVisitedLabels().contains("kapitel_6_glassarg_und_erloesung"));
        assertTrue(report.combinedVisitedLabels().contains("kapitel_7_hochzeit_und_ende"));

        assertThrows(UnsupportedOperationException.class,
                () -> report.reports().put(ChoiceStrategy.FIRST_CHOICE, null));
        assertThrows(UnsupportedOperationException.class,
                () -> report.combinedVisitedLabels().add("unerlaubt"));
    }

    @Test
    void playthroughSuiteAggregatesMockedStrategiesAndDetectsFailure(@TempDir Path tempDir) {
        PlaythroughReport first = new PlaythroughReport(
                true, 50, 30, 3, Set.of("start", "kapitel_1"), 50.0, Optional.empty()
        );
        PlaythroughReport last = new PlaythroughReport(
                true, 55, 32, 3, Set.of("start", "kapitel_2"), 50.0, Optional.empty()
        );
        PlaythroughReport roundRobin = new PlaythroughReport(
                false, 2000, 100, 10, Set.of("kapitel_3"), 25.0, Optional.of("Reached maxSteps limit")
        );

        when(mockPlaythroughRunner.run(any(Path.class), eq(ChoiceStrategy.FIRST_CHOICE), eq(120))).thenReturn(first);
        when(mockPlaythroughRunner.run(any(Path.class), eq(ChoiceStrategy.LAST_CHOICE), eq(120))).thenReturn(last);
        when(mockPlaythroughRunner.run(any(Path.class), eq(ChoiceStrategy.ROUND_ROBIN), eq(120))).thenReturn(roundRobin);

        SnowWhitePlaythroughSuite suite = new SnowWhitePlaythroughSuite(mockPlaythroughRunner, 120);
        CombinedPlaythroughReport combined = suite.runAllStrategies(tempDir);

        assertFalse(combined.allEndedCleanly());
        assertEquals(Set.of("start", "kapitel_1", "kapitel_2", "kapitel_3"), combined.combinedVisitedLabels());
        assertEquals(Optional.of(roundRobin), combined.reportFor(ChoiceStrategy.ROUND_ROBIN));

        verify(mockPlaythroughRunner).run(eq(tempDir.toAbsolutePath().normalize()), eq(ChoiceStrategy.FIRST_CHOICE), eq(120));
        verify(mockPlaythroughRunner).run(eq(tempDir.toAbsolutePath().normalize()), eq(ChoiceStrategy.LAST_CHOICE), eq(120));
        verify(mockPlaythroughRunner).run(eq(tempDir.toAbsolutePath().normalize()), eq(ChoiceStrategy.ROUND_ROBIN), eq(120));
    }

    @Test
    void flowAndTranslationReportAnalyzesSnowWhiteProject() {
        SnowWhiteFlowAndTranslationReport analyzer = new SnowWhiteFlowAndTranslationReport();
        AuditBundle bundle = analyzer.analyze(resolveProjectDir());

        assertEquals("start", bundle.flowGraph().entryLabel());
        assertFalse(bundle.flowGraph().labels().isEmpty());
        assertFalse(bundle.flowGraph().edges().isEmpty());
        assertTrue(bundle.mermaidDiagram().startsWith("flowchart TD"));
        assertTrue(bundle.dotDiagram().startsWith("digraph ScriptFlow"));

        assertTrue(bundle.translationReport().totalDialogueLines() > 0);
        assertTrue(bundle.findLanguageCoverage("english").isPresent());
        assertTrue(bundle.assetUsageReport().isClean(),
                "Fehlende Assets unerwartet: " + bundle.assetUsageReport().missingAssets());
        assertFalse(bundle.assetUsageReport().discoveredAssets().isEmpty());

        assertTrue(bundle.markdownReport().contains("## 1. Kontrollfluss-Analyse"));
        assertTrue(bundle.markdownReport().contains("## 2. Übersetzungsabdeckung"));
        assertTrue(bundle.markdownReport().contains("## 3. Asset-Nutzungsprüfung"));
    }

    @Test
    void flowAndTranslationReportUsesMockedAnalyzers() {
        VJavaAst.ProjectAst dummyAst = new VJavaAst.ProjectAst(
                Path.of(".").toAbsolutePath().normalize(),
                null,
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        FlowGraph graph = new FlowGraph(
                "start",
                Set.of("start", "tot_label"),
                List.of(new FlowEdge("__entry__", "start", EdgeKind.ENTRY, "")),
                Set.of("tot_label")
        );
        CoverageReport coverage = new CoverageReport(
                10,
                Map.of("english", new LanguageCoverage("english", 10, 4, List.of("Hallo")))
        );
        AssetUsageReport usage = new AssetUsageReport(
                Set.of("assets/images/unused.png"),
                Set.of("assets/images/missing.png"),
                Set.of("assets/images/unused.png"),
                Set.of("assets/images/missing.png")
        );

        when(mockFlowExporter.buildGraph(dummyAst)).thenReturn(graph);
        when(mockFlowExporter.toMermaid(graph)).thenReturn("flowchart TD\n    start[\"start\"]\n");
        when(mockFlowExporter.toDot(graph)).thenReturn("digraph ScriptFlow {\n}\n");
        when(mockTranslationAuditor.audit(dummyAst)).thenReturn(coverage);
        when(mockAssetDetector.analyze(dummyAst)).thenReturn(usage);

        SnowWhiteFlowAndTranslationReport analyzer = new SnowWhiteFlowAndTranslationReport(
                mockFlowExporter, mockTranslationAuditor, mockAssetDetector
        );
        AuditBundle bundle = analyzer.analyze(dummyAst);

        assertEquals(graph, bundle.flowGraph());
        assertTrue(bundle.markdownReport().contains("tot_label"));
        assertTrue(bundle.markdownReport().contains("40.0 %"));
        assertTrue(bundle.markdownReport().contains("assets/images/missing.png"));
        assertTrue(bundle.markdownReport().contains("assets/images/unused.png"));
        assertTrue(bundle.findLanguageCoverage("ENGLISH").isPresent());

        verify(mockFlowExporter).buildGraph(dummyAst);
        verify(mockFlowExporter).toMermaid(graph);
        verify(mockFlowExporter).toDot(graph);
        verify(mockTranslationAuditor).audit(dummyAst);
        verify(mockAssetDetector).analyze(dummyAst);
    }

    @Test
    void integrityAuditorVerifiesSnowWhiteProjectEndToEnd(@TempDir Path tempDir) throws Exception {
        Path workDir = tempDir.resolve("work");
        SnowWhiteIntegrityAuditor auditor = new SnowWhiteIntegrityAuditor();
        IntegrityAuditReport report = auditor.audit(resolveProjectDir(), workDir);

        assertTrue(report.allPassed(), "Integritätsprüfung für Schneewittchen muss bestehen");
        assertTrue(report.checksumReport().isIntact());
        assertEquals(report.manifest().checksums().size(), report.checksumReport().validCount());
        assertFalse(report.assetMetadata().isEmpty());
        assertTrue(report.findMetadata("assets/images/bg/bg_schloss_winter.png")
                .flatMap(AssetMetadata::widthPx)
                .isPresent());
        assertTrue(report.preFlightReport().readyForRelease());
        assertTrue(report.vpakReport().integrityVerified());
        assertTrue(report.vpakReport().entryCount() > 0);

        // Temporäre .vpak-Datei muss nach der Prüfung wieder entfernt worden sein
        try (var files = Files.list(workDir)) {
            assertEquals(0L, files.count());
        }
        assertThrows(UnsupportedOperationException.class,
                () -> report.assetMetadata().remove(0));
    }

    @Test
    void integrityAuditorDetectsCorruptedVpakWithMocks(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("proj");
        Path workDir = tempDir.resolve("work");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("main.vjava"), "label start:\n    \"Hi\"\n", StandardCharsets.UTF_8);

        AtomicBoolean preparerCalled = new AtomicBoolean(false);
        AssetManifest manifest = new AssetManifest("SHA-256", Map.of("assets/bg.png", "abc"));
        VerificationReport verification = new VerificationReport(1, List.of(), List.of(), List.of());
        List<AssetMetadata> metadata = List.of(new AssetMetadata(
                "assets/bg.png", MediaType.IMAGE, 128L,
                Optional.of(64), Optional.of(64),
                Optional.empty(), Optional.empty(), Optional.empty()
        ));
        PreFlightReport preFlight = new PreFlightReport(
                true,
                List.of(new CheckItem("Project Directory", CheckStatus.PASS, "ok")),
                "# Export Pre-Flight Checklist\n"
        );
        VpakInspectionReport corruptVpak = new VpakInspectionReport(
                workDir.resolve("temp.vpak"), 1, 100L, 50L, 0.5,
                Map.of("vjava", 100L), List.of("main.vjava"), false
        );

        when(mockChecksumVerifier.generateManifest(any(Path.class))).thenReturn(manifest);
        when(mockChecksumVerifier.verify(any(Path.class), eq(manifest))).thenReturn(verification);
        when(mockMetadataInspector.inspectAll(any(Path.class))).thenReturn(metadata);
        when(mockPreFlightChecker.check(any(Path.class))).thenReturn(preFlight);
        when(mockVpakInspector.inspect(any(Path.class))).thenReturn(corruptVpak);

        SnowWhiteIntegrityAuditor auditor = new SnowWhiteIntegrityAuditor(
                mockChecksumVerifier,
                mockMetadataInspector,
                mockPreFlightChecker,
                mockVpakInspector,
                dir -> preparerCalled.set(true)
        );
        IntegrityAuditReport report = auditor.audit(projectDir, workDir);

        assertTrue(preparerCalled.get());
        assertFalse(report.allPassed());
        assertTrue(report.findMetadata("assets/bg.png").isPresent());
        assertTrue(report.findMetadata("assets/unknown.png").isEmpty());
        verify(mockVpakInspector).inspect(any(Path.class));
    }

    @Test
    void diagnosticExporterProfilesWatchesAndExportsValidJson(@TempDir Path tempDir) throws Exception {
        Path savesDir = tempDir.resolve("saves");
        Path targetJson = tempDir.resolve("out/diagnostics.json");

        SnowWhiteDiagnosticExporter exporter = new SnowWhiteDiagnosticExporter();
        Path written = exporter.exportProfiledDiagnostics(resolveProjectDir(), savesDir, targetJson);

        assertEquals(targetJson.toAbsolutePath().normalize(), written);
        assertTrue(Files.isRegularFile(written));

        JsonObject root = JsonParser.parseString(Files.readString(written, StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertNotNull(root.get("engineVersion").getAsString());
        assertTrue(root.getAsJsonObject("lintReport").get("clean").getAsBoolean());

        JsonObject metrics = root.getAsJsonObject("metrics");
        assertTrue(metrics.get("execution.endedCleanly").getAsBoolean());
        assertTrue(metrics.get("execution.stepsExecuted").getAsInt() > 0);
        assertFalse(metrics.get("profiler.hottestLabel").getAsString().isBlank());
        assertFalse(metrics.getAsJsonArray("profiler.topLabels").isEmpty());

        JsonArray watchedKeys = metrics.getAsJsonArray("watchpoints.watchedKeys");
        List<String> watchedList = watchedKeys.asList().stream().map(e -> e.getAsString()).toList();
        assertTrue(watchedList.contains("schneewittchen_zustand"));
        assertTrue(watchedList.contains("koenigin_zustand"));
        assertTrue(watchedList.contains("anschlag_zaehler"));
        assertTrue(watchedList.contains("schneewittchen_status"));

        assertTrue(metrics.get("watchpoints.eventCount").getAsInt() > 0);
        assertFalse(metrics.getAsJsonArray("watchpoints.events").isEmpty());

        JsonObject typedValues = metrics.getAsJsonObject("state.typedValues");
        assertEquals("ERLOEST", typedValues.get("schneewittchen_status").getAsString());
        assertEquals("BESTRAFT", typedValues.get("stiefmutter_status").getAsString());
        assertEquals(7, typedValues.get("kapitel_nr").getAsInt());

        assertTrue(metrics.get("state.diff.totalChanges").getAsInt() > 0);
    }

    @Test
    void diagnosticExporterDelegatesToMockedBundleExporterAndDiffService(@TempDir Path tempDir) throws Exception {
        DiagnosticBundleExporter mockExporter = mock(DiagnosticBundleExporter.class);
        StateSnapshotDiffService mockDiff = mock(StateSnapshotDiffService.class);

        Path targetJson = tempDir.resolve("diag.json").toAbsolutePath().normalize();
        when(mockDiff.diff(anyMap(), anyMap())).thenReturn(new StateDiff(Map.of("stiefmutter_status", "BESTRAFT"), Map.of(), Map.of()));
        when(mockExporter.export(eq(targetJson), any(EngineAPI.class), any(), anyMap())).thenReturn(targetJson);

        SnowWhiteDiagnosticExporter exporter = new SnowWhiteDiagnosticExporter(mockExporter, mockDiff, 50);
        Path result = exporter.exportProfiledDiagnostics(resolveProjectDir(), tempDir.resolve("saves"), targetJson);

        assertEquals(targetJson, result);
        verify(mockDiff, times(1)).diff(anyMap(), anyMap());
        verify(mockExporter, times(1)).export(eq(targetJson), any(EngineAPI.class), any(), anyMap());
    }

    @Test
    void showcaseCliGeneratesAllFiveFilesEndToEnd(@TempDir Path tempDir) throws Exception {
        Path outDir = tempDir.resolve("showcase");
        SnowWhiteShowcaseCli cli = new SnowWhiteShowcaseCli();

        ShowcaseOutput output = cli.generateShowcase(resolveProjectDir(), outDir);

        assertEquals(outDir.toAbsolutePath().normalize(), output.outputDir());
        assertTrue(Files.isRegularFile(output.flowMermaidFile()));
        assertTrue(Files.isRegularFile(output.auditMarkdownFile()));
        assertTrue(Files.isRegularFile(output.diagnosticsJsonFile()));
        assertTrue(Files.isRegularFile(output.manifestJsonFile()));
        assertTrue(Files.isRegularFile(output.preFlightMarkdownFile()));

        String mermaid = Files.readString(output.flowMermaidFile(), StandardCharsets.UTF_8);
        assertTrue(mermaid.startsWith("flowchart TD"));

        String auditMd = Files.readString(output.auditMarkdownFile(), StandardCharsets.UTF_8);
        assertTrue(auditMd.contains("Kontrollfluss-Analyse"));
        assertTrue(auditMd.contains("Automatisierte Playthrough-Strategien"));
        assertTrue(auditMd.contains("Integritäts- & Archivprüfung"));

        String diagJson = Files.readString(output.diagnosticsJsonFile(), StandardCharsets.UTF_8);
        assertTrue(JsonParser.parseString(diagJson).isJsonObject());

        AssetManifest manifest = AssetManifest.fromJson(Files.readString(output.manifestJsonFile(), StandardCharsets.UTF_8));
        assertFalse(manifest.checksums().isEmpty());

        String preFlightMd = Files.readString(output.preFlightMarkdownFile(), StandardCharsets.UTF_8);
        assertTrue(preFlightMd.contains("# Export Pre-Flight Checklist"));
    }

    @Test
    void showcaseCliOrchestratesMockedCollaborators(@TempDir Path tempDir) throws Exception {
        SnowWhitePlaythroughSuite mockSuite = mock(SnowWhitePlaythroughSuite.class);
        SnowWhiteFlowAndTranslationReport mockFlow = mock(SnowWhiteFlowAndTranslationReport.class);
        SnowWhiteIntegrityAuditor mockIntegrity = mock(SnowWhiteIntegrityAuditor.class);
        SnowWhiteDiagnosticExporter mockDiag = mock(SnowWhiteDiagnosticExporter.class);

        Path projectDir = tempDir.resolve("proj");
        Path outDir = tempDir.resolve("out");
        Files.createDirectories(projectDir);

        CombinedPlaythroughReport playthrough = new CombinedPlaythroughReport(
                Map.of(ChoiceStrategy.FIRST_CHOICE, new PlaythroughReport(true, 10, 5, 1, Set.of("start"), 100.0, Optional.empty())),
                Set.of("start"),
                true
        );
        AuditBundle auditBundle = new AuditBundle(
                new FlowGraph("start", Set.of("start"), List.of(), Set.of()),
                "flowchart TD\n    start[\"start\"]\n",
                "digraph ScriptFlow {\n}\n",
                new CoverageReport(1, Map.of()),
                new AssetUsageReport(Set.of(), Set.of(), Set.of(), Set.of()),
                "# Bericht\n"
        );
        IntegrityAuditReport integrity = new IntegrityAuditReport(
                new AssetManifest("SHA-256", Map.of()),
                new VerificationReport(0, List.of(), List.of(), List.of()),
                List.of(),
                new PreFlightReport(true, List.of(), "# Export Pre-Flight Checklist\n"),
                new VpakInspectionReport(tempDir.resolve("a.vpak"), 0, 0L, 0L, 0.0, Map.of(), List.of(), true),
                true
        );

        Path expectedDiag = outDir.toAbsolutePath().normalize().resolve(SnowWhiteShowcaseCli.DIAGNOSTICS_JSON_FILE);
        when(mockIntegrity.audit(eq(projectDir.toAbsolutePath().normalize()), any(Path.class))).thenReturn(integrity);
        when(mockSuite.runAllStrategies(projectDir.toAbsolutePath().normalize())).thenReturn(playthrough);
        when(mockFlow.analyze(projectDir.toAbsolutePath().normalize())).thenReturn(auditBundle);
        when(mockDiag.exportProfiledDiagnostics(eq(projectDir.toAbsolutePath().normalize()), any(Path.class), eq(expectedDiag)))
                .thenAnswer(inv -> {
                    Files.writeString(expectedDiag, "{}", StandardCharsets.UTF_8);
                    return expectedDiag;
                });

        SnowWhiteShowcaseCli cli = new SnowWhiteShowcaseCli(mockSuite, mockFlow, mockIntegrity, mockDiag);
        ShowcaseOutput output = cli.generateShowcase(projectDir, outDir);

        assertTrue(Files.isRegularFile(output.flowMermaidFile()));
        assertTrue(Files.isRegularFile(output.auditMarkdownFile()));
        assertTrue(Files.isRegularFile(output.diagnosticsJsonFile()));
        assertTrue(Files.isRegularFile(output.manifestJsonFile()));
        assertTrue(Files.isRegularFile(output.preFlightMarkdownFile()));

        verify(mockIntegrity).audit(eq(projectDir.toAbsolutePath().normalize()), any(Path.class));
        verify(mockSuite).runAllStrategies(projectDir.toAbsolutePath().normalize());
        verify(mockFlow).analyze(projectDir.toAbsolutePath().normalize());
        verify(mockDiag).exportProfiledDiagnostics(eq(projectDir.toAbsolutePath().normalize()), any(Path.class), eq(expectedDiag));
    }

    @Test
    void guardClausesRejectNullAndInvalidArguments(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("does-not-exist");

        SnowWhitePlaythroughSuite suite = new SnowWhitePlaythroughSuite();
        assertThrows(NullPointerException.class, () -> suite.runAllStrategies(null));
        assertThrows(IllegalArgumentException.class, () -> suite.runAllStrategies(missing));
        assertThrows(IllegalArgumentException.class, () -> new SnowWhitePlaythroughSuite(new AutomatedPlaythroughRunner(), 0));

        SnowWhiteFlowAndTranslationReport flow = new SnowWhiteFlowAndTranslationReport();
        assertThrows(NullPointerException.class, () -> flow.analyze((Path) null));
        assertThrows(IllegalArgumentException.class, () -> flow.analyze(missing));

        SnowWhiteIntegrityAuditor auditor = new SnowWhiteIntegrityAuditor();
        assertThrows(NullPointerException.class, () -> auditor.audit(null, tempDir));
        assertThrows(NullPointerException.class, () -> auditor.audit(tempDir, null));
        assertThrows(IllegalArgumentException.class, () -> auditor.audit(missing, tempDir));

        SnowWhiteDiagnosticExporter exporter = new SnowWhiteDiagnosticExporter();
        assertThrows(NullPointerException.class, () -> exporter.exportProfiledDiagnostics(null, tempDir, tempDir.resolve("a.json")));
        assertThrows(NullPointerException.class, () -> exporter.exportProfiledDiagnostics(tempDir, null, tempDir.resolve("a.json")));
        assertThrows(NullPointerException.class, () -> exporter.exportProfiledDiagnostics(tempDir, tempDir, null));
        assertThrows(IllegalArgumentException.class, () -> exporter.exportProfiledDiagnostics(missing, tempDir, tempDir.resolve("a.json")));

        SnowWhiteShowcaseCli cli = new SnowWhiteShowcaseCli();
        assertThrows(NullPointerException.class, () -> cli.generateShowcase(null, tempDir));
        assertThrows(NullPointerException.class, () -> cli.generateShowcase(tempDir, null));
        assertThrows(IllegalArgumentException.class, () -> cli.generateShowcase(missing, tempDir));
    }
}
