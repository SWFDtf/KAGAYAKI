package de.swfdtf.tools;

import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.VJavaAst;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Comprehensive JUnit 6 and Mockito tests for Wave 2 script-analysis and search tools:
 * {@link ScriptFlowGraphExporter}, {@link ScriptWordCountAnalyzer}, {@link UnusedAssetDetector},
 * {@link TranslationCoverageAuditor}, and {@link ProjectSearchService}.
 */
@ExtendWith(MockitoExtension.class)
class Wave2AnalysisToolsTest {

    @Test
    void scriptFlowGraphExporterBuildsGraphHandlesNestedFlowsScreensAndExportsMermaidAndDot(
            @TempDir Path projectDir
    ) throws Exception {
        writeStarterDescriptor(projectDir, "prologue");
        Files.writeString(projectDir.resolve("main.vjava"), """
                label prologue:
                    if flag == true:
                        call helper_scene
                    else:
                        jump ending
                    menu:
                        timeout 5.0 -> timeout_scene
                        "Go secret" -> jump secret_route
                        "Inspect":
                            jump inspect_scene

                label helper_scene:
                    "Subroutine."
                    return

                label secret_route:
                    "Secret."
                    return

                label inspect_scene:
                    "Inspecting."
                    return

                label timeout_scene:
                    "Timed out."
                    return

                label ending:
                    "Fin."
                    return

                label screen_target:
                    "Reached from screen."
                    return

                label dead_end:
                    "Never reached."
                    return

                screen main_menu():
                    vbox:
                        textbutton "Extra" action Call("screen_target")
                """, StandardCharsets.UTF_8);

        VJavaAst.ProjectAst ast = ProjectScanner.scan(projectDir);
        ScriptFlowGraphExporter exporter = spy(new ScriptFlowGraphExporter());
        ScriptFlowGraphExporter.FlowGraph graph = exporter.buildGraph(ast);

        assertEquals("prologue", graph.entryLabel());
        assertEquals(
                Set.of("prologue", "helper_scene", "secret_route", "inspect_scene",
                        "timeout_scene", "ending", "screen_target", "dead_end"),
                graph.labels()
        );
        assertEquals(Set.of("dead_end"), graph.unreachableLabels());

        assertTrue(graph.edges().contains(new ScriptFlowGraphExporter.FlowEdge(
                "__entry__", "prologue", ScriptFlowGraphExporter.EdgeKind.ENTRY, "")));
        assertTrue(graph.edges().contains(new ScriptFlowGraphExporter.FlowEdge(
                "prologue", "helper_scene", ScriptFlowGraphExporter.EdgeKind.CALL, "flag == true")));
        assertTrue(graph.edges().contains(new ScriptFlowGraphExporter.FlowEdge(
                "prologue", "ending", ScriptFlowGraphExporter.EdgeKind.JUMP, "else")));
        assertTrue(graph.edges().contains(new ScriptFlowGraphExporter.FlowEdge(
                "prologue", "timeout_scene", ScriptFlowGraphExporter.EdgeKind.MENU_TIMEOUT, "timeout(5.0s)")));
        assertTrue(graph.edges().contains(new ScriptFlowGraphExporter.FlowEdge(
                "prologue", "secret_route", ScriptFlowGraphExporter.EdgeKind.MENU, "Go secret")));
        assertTrue(graph.edges().contains(new ScriptFlowGraphExporter.FlowEdge(
                "prologue", "inspect_scene", ScriptFlowGraphExporter.EdgeKind.JUMP, "Inspect")));
        assertTrue(graph.edges().contains(new ScriptFlowGraphExporter.FlowEdge(
                "screen:main_menu", "screen_target", ScriptFlowGraphExporter.EdgeKind.CALL, "screen:main_menu")));

        String mermaid = exporter.exportMermaid(ast);
        verify(exporter).toMermaid(org.mockito.ArgumentMatchers.any());
        assertTrue(mermaid.startsWith("flowchart TD\n"));
        assertTrue(mermaid.contains("prologue"));
        assertTrue(mermaid.contains("secret_route"));
        assertTrue(mermaid.contains("class dead_end unreachable"));

        String dot = exporter.toDot(graph);
        assertTrue(dot.startsWith("digraph ScriptFlow {"));
        assertTrue(dot.contains("\"prologue\" -> \"ending\""));
        assertTrue(dot.contains("\"dead_end\" [shape=box, style=dashed];"));

        assertThrows(UnsupportedOperationException.class, () -> graph.labels().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> graph.edges().clear());
        assertThrows(UnsupportedOperationException.class, () -> graph.unreachableLabels().remove("dead_end"));
        assertThrows(NullPointerException.class, () -> exporter.buildGraph(null));
        assertThrows(NullPointerException.class, () -> exporter.toMermaid(null));
        assertThrows(NullPointerException.class, () -> exporter.toDot(null));
    }

    @Test
    void scriptFlowGraphExporterRespectsMockedDescriptorEntryLabel(@TempDir Path tempDir) {
        ProjectDescriptor mockDescriptor = mock(ProjectDescriptor.class);
        when(mockDescriptor.getEntryLabel()).thenReturn("custom_entry");

        VJavaAst.LabelNode customEntry = new VJavaAst.LabelNode(
                "custom_entry", List.of(), "main.vjava", 1);
        VJavaAst.LabelNode orphan = new VJavaAst.LabelNode(
                "orphan", List.of(), "main.vjava", 5);

        VJavaAst.ProjectAst syntheticProject = new VJavaAst.ProjectAst(
                tempDir,
                mockDescriptor,
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("custom_entry", customEntry, "orphan", orphan),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        ScriptFlowGraphExporter exporter = new ScriptFlowGraphExporter();
        ScriptFlowGraphExporter.FlowGraph graph = exporter.buildGraph(syntheticProject);

        verify(mockDescriptor).getEntryLabel();
        assertEquals("custom_entry", graph.entryLabel());
        assertEquals(Set.of("orphan"), graph.unreachableLabels());
    }

    @Test
    void scriptFlowGraphExporterAnalyzesGeneratedStarterTemplate(@TempDir Path projectDir) throws Exception {
        Path root = ProjectTemplateGenerator.generate(projectDir);
        VJavaAst.ProjectAst ast = ProjectScanner.scan(root);

        ScriptFlowGraphExporter exporter = new ScriptFlowGraphExporter();
        ScriptFlowGraphExporter.FlowGraph graph = exporter.buildGraph(ast);

        assertEquals("start", graph.entryLabel());
        assertEquals(Set.of("start", "umgebung_pruefen", "dialog_zwei"), graph.labels());
        assertTrue(graph.unreachableLabels().isEmpty());
    }

    @Test
    void scriptWordCountAnalyzerCountsNestedSayStatementsAndStripsRichTextTags(@TempDir Path projectDir)
            throws Exception {
        writeStarterDescriptor(projectDir, "start");
        Files.writeString(projectDir.resolve("main.vjava"), """
                define lena = Character("Lena")
                label start:
                    lena "Hello {b}world{/b}!{w=0.4}"
                    "Narrator {{braces}} line."
                    if true:
                        lena "{color=#ff0000}Two words{/color}"
                    menu:
                        "Pick":
                            lena "One"
                """, StandardCharsets.UTF_8);

        VJavaAst.ProjectAst ast = ProjectScanner.scan(projectDir);
        ScriptWordCountAnalyzer analyzer = new ScriptWordCountAnalyzer();
        ScriptWordCountAnalyzer.ScriptStatsReport report = analyzer.analyze(ast);

        assertEquals(4, report.totalLines());
        assertEquals(8, report.totalWords());
        // "Hello world!" (12) + "Narrator {braces} line." (23) + "Two words" (9) + "One" (3) = 47
        assertEquals(47, report.totalCharacters());
        assertEquals(8 / 200.0, report.estimatedReadingMinutes(), 1e-9);

        ScriptWordCountAnalyzer.SpeakerStats lena = report.findSpeaker("lena").orElseThrow();
        assertEquals(3, lena.lineCount());
        assertEquals(5, lena.wordCount());
        assertEquals(24, lena.charCount());

        ScriptWordCountAnalyzer.SpeakerStats narrator = report.findSpeaker("narrator").orElseThrow();
        assertEquals(1, narrator.lineCount());
        assertEquals(3, narrator.wordCount());
        assertEquals(23, narrator.charCount());

        assertTrue(report.findSpeaker("unknown").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> report.perSpeaker().clear());
        assertThrows(NullPointerException.class, () -> analyzer.analyze(null));
    }

    @Test
    void scriptWordCountAnalyzerAnalyzesStarterTemplate(@TempDir Path projectDir) throws Exception {
        Path root = ProjectTemplateGenerator.generate(projectDir);
        VJavaAst.ProjectAst ast = ProjectScanner.scan(root);

        ScriptWordCountAnalyzer.ScriptStatsReport report = new ScriptWordCountAnalyzer().analyze(ast);
        assertEquals(4, report.totalLines());
        assertTrue(report.totalWords() > 30);
        assertEquals(report.totalWords() / 200.0, report.estimatedReadingMinutes(), 1e-9);
        assertTrue(report.findSpeaker("lena").isPresent());
        assertTrue(report.findSpeaker("kaito").isPresent());
    }

    @Test
    void unusedAssetDetectorDetectsCleanStarterTemplateAndUnusedOrMissingAssets(@TempDir Path projectDir)
            throws Exception {
        Path root = ProjectTemplateGenerator.generate(projectDir);
        VJavaAst.ProjectAst cleanAst = ProjectScanner.scan(root);

        UnusedAssetDetector detector = new UnusedAssetDetector();
        UnusedAssetDetector.AssetUsageReport cleanReport = detector.analyze(cleanAst);

        assertTrue(cleanReport.isClean());
        assertTrue(cleanReport.missingAssets().isEmpty());
        assertTrue(cleanReport.unusedAssets().isEmpty());
        assertFalse(cleanReport.discoveredAssets().isEmpty());
        assertEquals(cleanReport.discoveredAssets(), cleanReport.referencedAssets());

        // Add an unreferenced asset and a script referencing a missing asset + screen/layered/queue assets
        Path extraAsset = root.resolve("assets/images/unused_prop.png");
        Files.createDirectories(extraAsset.getParent());
        Files.writeString(extraAsset, "unused", StandardCharsets.UTF_8);

        Files.writeString(root.resolve("extra.vjava"), """
                image extra_img = "assets/images/missing_bg.png"

                layeredimage hero:
                    always:
                        "assets/images/char/hero_base.png"
                    group mood:
                        attribute calm default:
                            "assets/images/char/hero_calm.png"
                    if has_cape:
                        "assets/images/char/hero_cape.png"

                label bonus:
                    scene "assets/images/direct_scene.png"
                    show extra_img
                    queue music ["assets/audio/queued_track.wav"]
                    voice "assets/audio/voice/missing_voice.wav"
                    return

                screen extra_screen():
                    frame:
                        background "#161822d9"
                        imagebutton:
                            idle "assets/images/ui/btn_idle.png"
                            hover "assets/images/ui/btn_hover.png"
                """, StandardCharsets.UTF_8);

        VJavaAst.ProjectAst dirtyAst = ProjectScanner.scan(root);
        UnusedAssetDetector.AssetUsageReport dirtyReport = detector.analyze(dirtyAst);

        assertFalse(dirtyReport.isClean());
        assertEquals(Set.of("assets/images/unused_prop.png"), dirtyReport.unusedAssets());
        assertTrue(dirtyReport.missingAssets().contains("assets/images/missing_bg.png"));
        assertTrue(dirtyReport.missingAssets().contains("assets/images/char/hero_base.png"));
        assertTrue(dirtyReport.missingAssets().contains("assets/images/char/hero_calm.png"));
        assertTrue(dirtyReport.missingAssets().contains("assets/images/char/hero_cape.png"));
        assertTrue(dirtyReport.missingAssets().contains("assets/images/direct_scene.png"));
        assertTrue(dirtyReport.missingAssets().contains("assets/audio/queued_track.wav"));
        assertTrue(dirtyReport.missingAssets().contains("assets/audio/voice/missing_voice.wav"));
        assertTrue(dirtyReport.missingAssets().contains("assets/images/ui/btn_idle.png"));
        assertTrue(dirtyReport.missingAssets().contains("assets/images/ui/btn_hover.png"));
        assertFalse(dirtyReport.referencedAssets().contains("#161822d9"));

        assertThrows(UnsupportedOperationException.class, () -> dirtyReport.discoveredAssets().clear());
        assertThrows(UnsupportedOperationException.class, () -> dirtyReport.referencedAssets().clear());
        assertThrows(UnsupportedOperationException.class, () -> dirtyReport.unusedAssets().clear());
        assertThrows(UnsupportedOperationException.class, () -> dirtyReport.missingAssets().clear());
        assertThrows(NullPointerException.class, () -> detector.analyze(null));
    }

    @Test
    void translationCoverageAuditorComputesCoverageAndUntranslatedLines(@TempDir Path projectDir) throws Exception {
        Path root = ProjectTemplateGenerator.generate(projectDir);
        Path frenchFile = root.resolve("tl/francais/translations.vjava");
        Files.createDirectories(frenchFile.getParent());
        Files.writeString(frenchFile, """
                translate francais start_01:
                    # lena "Willkommen in der ersten Beispielszene, [spieler_name].{w=0.4} Bereit für den Übergang in Dialog 2?"
                    lena "Bienvenue dans la première scène, [spieler_name].{w=0.4} Prêt pour le dialogue 2 ?"

                translate francais start_02:
                    # kaito "Der Sternenkompass zeigt direkt hinüber zur Bibliothek."
                    kaito ""
                """, StandardCharsets.UTF_8);

        VJavaAst.ProjectAst ast = ProjectScanner.scan(root);
        TranslationCoverageAuditor auditor = new TranslationCoverageAuditor();
        TranslationCoverageAuditor.CoverageReport report = auditor.audit(ast);

        assertEquals(4, report.totalDialogueLines());
        assertEquals(2, report.languages().size());

        TranslationCoverageAuditor.LanguageCoverage english = report.findLanguage("ENGLISH").orElseThrow();
        assertEquals("english", english.language());
        assertEquals(4, english.expectedEntries());
        assertEquals(1, english.translatedEntries());
        assertEquals(25.0, english.coveragePercent(), 1e-9);
        assertEquals(3, english.untranslatedTexts().size());

        TranslationCoverageAuditor.LanguageCoverage french = report.findLanguage("francais").orElseThrow();
        assertEquals(1, french.translatedEntries());
        assertEquals(25.0, french.coveragePercent(), 1e-9);
        assertTrue(french.untranslatedTexts().contains("Der Sternenkompass zeigt direkt hinüber zur Bibliothek."));

        assertEquals(Optional.empty(), report.findLanguage("japanese"));
        assertThrows(UnsupportedOperationException.class, () -> report.languages().clear());
        assertThrows(UnsupportedOperationException.class, () -> english.untranslatedTexts().clear());
        assertThrows(NullPointerException.class, () -> auditor.audit(null));
    }

    @Test
    void translationCoverageAuditorReturns100PercentForEmptyDialogueProject(@TempDir Path projectDir) throws Exception {
        writeStarterDescriptor(projectDir, "start");
        Files.writeString(projectDir.resolve("main.vjava"), """
                label start:
                    return
                translate english empty_block:
                    "Nothing"
                """, StandardCharsets.UTF_8);

        VJavaAst.ProjectAst ast = ProjectScanner.scan(projectDir);
        TranslationCoverageAuditor.CoverageReport report = new TranslationCoverageAuditor().audit(ast);

        assertEquals(0, report.totalDialogueLines());
        assertEquals(100.0, report.findLanguage("english").orElseThrow().coveragePercent(), 1e-9);
    }

    @Test
    void projectSearchServiceSearchesPlainTextAndRegexDeterministicallyAndSkipsIgnoredDirs(
            @TempDir Path projectDir
    ) throws Exception {
        Path root = ProjectTemplateGenerator.generate(projectDir);

        // Create files inside skipped directories that should never appear in results
        for (String skipped : List.of(".git", "build", ".gradle", "saves", "out", "dist")) {
            Path skippedFile = root.resolve(skipped).resolve("ignored.vjava");
            Files.createDirectories(skippedFile.getParent());
            Files.writeString(skippedFile, "lena \"Sternenkompass hidden match\"\n", StandardCharsets.UTF_8);
        }

        ProjectSearchService service = new ProjectSearchService();

        List<ProjectSearchService.SearchMatch> caseSensitive =
                service.searchPlainText(root, "Sternenkompass", true);
        assertEquals(3, caseSensitive.size());
        assertEquals(root.resolve("main.vjava"), caseSensitive.get(0).file());
        assertTrue(caseSensitive.get(0).lineNumber() < caseSensitive.get(1).lineNumber());
        assertTrue(caseSensitive.get(1).lineNumber() < caseSensitive.get(2).lineNumber());
        assertTrue(caseSensitive.get(0).column() > 1);
        assertTrue(caseSensitive.get(0).lineText().contains("Sternenkompass"));

        List<ProjectSearchService.SearchMatch> caseInsensitive =
                service.searchPlainText(root, "sternenkompass", false);
        assertEquals(3, caseInsensitive.size());
        assertTrue(service.searchPlainText(root, "sternenkompass", true).isEmpty());

        // Verify multiple matches on a single line and deterministic file/line/column sorting
        Files.writeString(root.resolve("a_extra.vjava"), "alpha alpha\nbeta alpha\n", StandardCharsets.UTF_8);
        List<ProjectSearchService.SearchMatch> regexMatches =
                service.searchRegex(root, Pattern.compile("\\balpha\\b"));
        assertEquals(3, regexMatches.size());
        assertEquals(root.resolve("a_extra.vjava"), regexMatches.get(0).file());
        assertEquals(1, regexMatches.get(0).lineNumber());
        assertEquals(1, regexMatches.get(0).column());
        assertEquals(1, regexMatches.get(1).lineNumber());
        assertEquals(7, regexMatches.get(1).column());
        assertEquals(2, regexMatches.get(2).lineNumber());
        assertEquals(6, regexMatches.get(2).column());

        assertThrows(UnsupportedOperationException.class, () -> regexMatches.clear());
        assertThrows(IllegalArgumentException.class, () -> service.searchPlainText(root, "", true));
        assertThrows(IllegalArgumentException.class, () -> service.searchRegex(root, Pattern.compile("")));
        assertThrows(IllegalArgumentException.class,
                () -> service.searchPlainText(root.resolve("does_not_exist"), "x", true));
        assertThrows(NullPointerException.class, () -> service.searchPlainText(null, "x", true));
        assertThrows(NullPointerException.class, () -> service.searchRegex(root, null));
    }

    private static void writeStarterDescriptor(Path projectDir, String entryLabel) throws Exception {
        ProjectDescriptor descriptor = ProjectDescriptor.createDefault("Wave2Test");
        descriptor.setEntryLabel(entryLabel);
        ProjectDescriptor.save(projectDir, descriptor);
    }
}
