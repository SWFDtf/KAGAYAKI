package de.swfdtf.tools;

import de.swfdtf.graphics.WindowManager;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.parser.VJavaParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Wave P3-4 Studio IDE &amp; No-Code Node Graph goals ({@code #0128}, {@code #0163},
 * {@code #0169}, {@code #1019}).
 */
class WaveP3StudioToolingAndNodeGraphTest {

    @Test
    void dialogGitDiffListsOnlyChangedLabelsAndChoicesWithPaging(@TempDir Path repoDir) throws Exception {
        DialogScriptGitDiffService diffService = new DialogScriptGitDiffService();
        String headScript = """
                label intro:
                    "Unveraendertes Intro."
                    jump chapter_1

                label chapter_1:
                    "Alter Text eins."
                    menu:
                        "Alte Option":
                            jump end_a

                label end_a:
                    return
                """;
        String workingScript = """
                label intro:
                    "Unveraendertes Intro."
                    jump chapter_1

                label chapter_1:
                    "Neuer Text eins!"
                    menu:
                        "Alte Option":
                            jump end_a
                        "Neue Geheimoption":
                            jump end_b

                label end_a:
                    return

                label end_b:
                    "Neues Ende."
                    return
                """;

        DialogScriptGitDiffService.ScriptDiffReport memReport =
                diffService.diffSources("main.vjava", headScript, workingScript);
        assertEquals(2, memReport.changedLabels().size());
        assertFalse(memReport.changedLabels().stream().anyMatch(d -> "intro".equals(d.labelName())));
        assertFalse(memReport.changedLabels().stream().anyMatch(d -> "end_a".equals(d.labelName())));

        DialogScriptGitDiffService.LabelDiff ch1 = memReport.changedLabels().get(0);
        assertEquals("chapter_1", ch1.labelName());
        assertEquals(1, ch1.lineDiffs().size());
        assertEquals("Alter Text eins.", ch1.lineDiffs().get(0).beforeLine());
        assertEquals("Neuer Text eins!", ch1.lineDiffs().get(0).afterLine());
        assertEquals(List.of("Neue Geheimoption"), ch1.addedChoices());
        assertEquals(2, memReport.totalPages(1));
        assertEquals(1, memReport.page(1, 1).size());

        // Real temporary Git repository verification against HEAD
        Files.writeString(repoDir.resolve("main.vjava"), headScript, StandardCharsets.UTF_8);
        runGit(repoDir, "init");
        runGit(repoDir, "config", "user.email", "test@kagayaki.local");
        runGit(repoDir, "config", "user.name", "Test");
        runGit(repoDir, "add", "main.vjava");
        runGit(repoDir, "commit", "-m", "init");

        Files.writeString(repoDir.resolve("main.vjava"), workingScript, StandardCharsets.UTF_8);
        DialogScriptGitDiffService.ScriptDiffReport gitReport = diffService.diffAgainstGitHead(repoDir);
        assertEquals(2, gitReport.changedLabels().size());
        assertEquals("chapter_1", gitReport.changedLabels().get(0).labelName());
    }

    @Test
    void studioUndockedPanelsMaintainStrictOpenGlContextIsolation() {
        List<WindowManager.MonitorInfo> monitors = List.of(
                new WindowManager.MonitorInfo(0, "Primary 4K", 3840, 2160, 120, true),
                new WindowManager.MonitorInfo(1, "Secondary Studio", 1920, 1080, 60, false)
        );
        StudioWindowController controller = new StudioWindowController();
        StudioUndockedWindowManager.UndockedPanelWindow undocked = controller.undockPanel(
                StudioUndockedWindowManager.DockablePanel.FLOW_GRAPH, monitors, 1);

        assertTrue(undocked.open());
        assertEquals(1, undocked.monitorIndex());
        assertTrue(undocked.x() >= 3840);
        assertTrue(undocked.glContext().isolatedFromGameContext());
        assertTrue(undocked.glContext().requiresContextSwitchOnDraw());
        assertTrue(controller.getUndockedWindowManager().isUndocked(
                StudioUndockedWindowManager.DockablePanel.FLOW_GRAPH));

        assertTrue(controller.redockPanel(StudioUndockedWindowManager.DockablePanel.FLOW_GRAPH));
        assertFalse(controller.getUndockedWindowManager().isUndocked(
                StudioUndockedWindowManager.DockablePanel.FLOW_GRAPH));
    }

    @Test
    void dialogPacingLinterFlags15PlusSentencesWithInfoWhileRemainingClean(@TempDir Path projectDir)
            throws Exception {
        Files.writeString(projectDir.resolve("project.vjava.json"), """
                {"name":"Pacing","version":"1.0.0","entryLabel":"start","defaultLanguage":"deutsch"}
                """, StandardCharsets.UTF_8);

        StringBuilder lines14 = new StringBuilder("label start:\n");
        for (int i = 1; i <= 14; i++) {
            lines14.append("    \"Satz Nummer ").append(i).append(".\"\n");
        }
        lines14.append("    return\n");
        Files.writeString(projectDir.resolve("main.vjava"), lines14.toString(), StandardCharsets.UTF_8);

        VJavaLinter.LintReport clean14 = VJavaLinter.lint(projectDir);
        assertFalse(clean14.issues().stream().anyMatch(
                i -> DialogPacingLinter.ISSUE_CODE_LONG_MONOLOGUE.equals(i.code())));

        StringBuilder lines16 = new StringBuilder("label start:\n");
        for (int i = 1; i <= 16; i++) {
            lines16.append("    \"Monolog Satz ").append(i).append(".\"\n");
        }
        lines16.append("    return\n");
        Files.writeString(projectDir.resolve("main.vjava"), lines16.toString(), StandardCharsets.UTF_8);

        VJavaAst.ProjectAst ast16 = ProjectScanner.scan(projectDir);
        VJavaLinter.LintReport report16 = VJavaLinter.lint(ast16, false);
        assertTrue(report16.isClean(), "INFO hints must not fail isClean()");
        assertTrue(report16.issues().stream().anyMatch(i ->
                i.severity() == VJavaLinter.Severity.INFO
                        && DialogPacingLinter.ISSUE_CODE_LONG_MONOLOGUE.equals(i.code())));
    }

    @Test
    void visualNodeGraphEditorRoundTripsCleanlyAndPreservesManualJavaBlocks() {
        String manualJavaCode = "int gold = state.getInt(\"gold\", 0);\nstate.set(\"gold\", gold + 50);";
        String initialSource = """
                define lena = Character("Lena")

                label start:
                    @->{
                        %s
                    }<<
                    lena "Willkommen im Laden."
                    menu:
                        "Kaufen":
                            jump shop_buy
                label shop_buy:
                    "Vielen Dank!"
                    return
                """.formatted(manualJavaCode.replace("\n", "\n        "));

        VisualNodeGraphEditor editor = new VisualNodeGraphEditor();
        VisualNodeGraphEditor.StoryGraph graph = editor.importFromSource(Path.of("shop.vjava"), initialSource);

        assertTrue(graph.getNodes().containsKey("start"));
        assertEquals(1, graph.getNodes().get("start").preservedJavaBlocks().size());

        // Drag node + create new node + link new choice edge
        graph.moveNode("start", 120.0f, 80.0f);
        graph.addNode("shop_secret", 520.0f, 300.0f);
        graph.addDialogueLine("shop_secret", "lena", "Geheimraum geoeffnet!");
        graph.addChoiceEdge("start", "Geheimgang", "shop_secret");

        String exported = editor.exportToVJava(graph);
        VJavaAst.ScriptFileAst reparsed = VJavaParser.parse(Path.of("shop.vjava"), exported);

        assertEquals(3, reparsed.labels().size());
        assertTrue(reparsed.labels().stream().anyMatch(l -> "shop_secret".equals(l.name())));
        // Manual Java block executable logic must survive verbatim untouched
        assertTrue(exported.contains("int gold = state.getInt(\"gold\", 0);"));
        assertTrue(exported.contains("state.set(\"gold\", gold + 50);"));
    }

    private static void runGit(Path repoDir, String... cmd) throws Exception {
        List<String> args = new java.util.ArrayList<>();
        args.add("git");
        args.addAll(List.of(cmd));
        Process proc = new ProcessBuilder(args).directory(repoDir.toFile()).start();
        byte[] err = proc.getErrorStream().readAllBytes();
        int code = proc.waitFor();
        assertEquals(0, code, () -> "git failed: " + new String(err, StandardCharsets.UTF_8));
    }
}
