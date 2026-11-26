package de.swfdtf.tools;

import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.VJavaAst;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Comprehensive JUnit 6 and Mockito tests for Wave 2 release tools in {@code de.swfdtf.tools}:
 * {@link AutomatedPlaythroughRunner}, {@link StudioCommandPalette},
 * {@link ExportPreFlightChecker}, {@link VpakManifestInspector}, and {@link StudioWorkspaceState}.
 */
@ExtendWith(MockitoExtension.class)
class Wave2ReleaseToolsTest {

    // ---- 1. AutomatedPlaythroughRunner ----

    @Test
    void automatedPlaythroughRunnerCompletesStarterProjectWithFirstAndLastChoice(@TempDir Path tempDir)
            throws Exception {
        Path projectDir = tempDir.resolve("starter");
        ProjectTemplateGenerator.generate(projectDir, "Runner Demo", "2D");

        AutomatedPlaythroughRunner runner = new AutomatedPlaythroughRunner();

        AutomatedPlaythroughRunner.PlaythroughReport firstReport =
                runner.run(projectDir, AutomatedPlaythroughRunner.ChoiceStrategy.FIRST_CHOICE, 100);
        assertTrue(firstReport.endedCleanly());
        assertTrue(firstReport.terminationReason().isEmpty());
        assertEquals(3, firstReport.dialogueCount());
        assertEquals(1, firstReport.choicesTaken());
        assertEquals(4, firstReport.stepsExecuted());
        assertEquals(Set.of("start", "dialog_zwei"), firstReport.visitedLabels());
        assertEquals((2 * 100.0) / 3.0, firstReport.labelCoveragePercent(), 0.01);
        assertThrows(UnsupportedOperationException.class, () -> firstReport.visitedLabels().add("x"));

        AutomatedPlaythroughRunner.PlaythroughReport lastReport =
                runner.run(projectDir, AutomatedPlaythroughRunner.ChoiceStrategy.LAST_CHOICE, 100);
        assertTrue(lastReport.endedCleanly());
        assertTrue(lastReport.terminationReason().isEmpty());
        assertEquals(4, lastReport.dialogueCount());
        assertEquals(1, lastReport.choicesTaken());
        assertEquals(5, lastReport.stepsExecuted());
        assertEquals(Set.of("start", "umgebung_pruefen", "dialog_zwei"), lastReport.visitedLabels());
        assertEquals(100.0, lastReport.labelCoveragePercent(), 0.001);
    }

    @Test
    void automatedPlaythroughRunnerHandlesInputPauseRoundRobinAndMaxSteps(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("multi_feature");
        Files.createDirectories(projectDir);
        ProjectDescriptor.save(projectDir, ProjectDescriptor.createDefault("Multi Feature"));
        String script = """
                label start:
                    input hero_name "Enter name:" default "Aria"
                    input nickname "Enter nickname:"
                    pause 0.2
                    pause
                    "Hello [hero_name] ([nickname])!"
                    menu:
                        "Branch A" -> jump branch_a
                        "Branch B" -> jump branch_b

                label branch_a:
                    "In A"
                    menu:
                        "End from A1" -> jump ending
                        "End from A2" -> jump ending

                label branch_b:
                    "In B"
                    jump ending

                label ending:
                    "Final line."
                    return
                """;
        Files.writeString(projectDir.resolve("main.vjava"), script, StandardCharsets.UTF_8);
        VJavaAst.ProjectAst ast = ProjectScanner.scan(projectDir);

        AutomatedPlaythroughRunner runner = new AutomatedPlaythroughRunner();
        AutomatedPlaythroughRunner.PlaythroughReport roundRobinReport =
                runner.run(ast, AutomatedPlaythroughRunner.ChoiceStrategy.ROUND_ROBIN, 50);

        assertTrue(roundRobinReport.endedCleanly());
        assertTrue(roundRobinReport.terminationReason().isEmpty());
        assertEquals(2, roundRobinReport.choicesTaken());
        assertEquals(3, roundRobinReport.dialogueCount());
        assertEquals(Set.of("start", "branch_a", "ending"), roundRobinReport.visitedLabels());

        AutomatedPlaythroughRunner.PlaythroughReport cappedReport =
                runner.run(ast, AutomatedPlaythroughRunner.ChoiceStrategy.FIRST_CHOICE, 2);
        assertFalse(cappedReport.endedCleanly());
        assertEquals(2, cappedReport.stepsExecuted());
        assertTrue(cappedReport.terminationReason().isPresent());
        assertTrue(cappedReport.terminationReason().get().contains("maxSteps"));

        assertThrows(NullPointerException.class,
                () -> runner.run((VJavaAst.ProjectAst) null,
                        AutomatedPlaythroughRunner.ChoiceStrategy.FIRST_CHOICE, 10));
        assertThrows(NullPointerException.class,
                () -> runner.run((Path) null, AutomatedPlaythroughRunner.ChoiceStrategy.FIRST_CHOICE, 10));
        assertThrows(NullPointerException.class, () -> runner.run(ast, null, 10));
        assertThrows(IllegalArgumentException.class,
                () -> runner.run(ast, AutomatedPlaythroughRunner.ChoiceStrategy.FIRST_CHOICE, 0));
    }

    // ---- 2. StudioCommandPalette ----

    @Test
    void studioCommandPaletteRegistersSearchesExecutesWithMockitoAndBoundsHistory() {
        Runnable lintAction = mock(Runnable.class);
        Runnable exportAction = mock(Runnable.class);
        Runnable reloadAction = mock(Runnable.class);
        Runnable unusedAction = mock(Runnable.class);

        StudioCommandPalette palette = new StudioCommandPalette(2);
        StudioCommandPalette.PaletteCommand cmdLint = new StudioCommandPalette.PaletteCommand(
                "tools.lint", "Run Project Linter", "Validation", "Ctrl+L", lintAction);
        StudioCommandPalette.PaletteCommand cmdExport = new StudioCommandPalette.PaletteCommand(
                "build.export", "Export Standalone Build", "Build", "Ctrl+E", exportAction);
        StudioCommandPalette.PaletteCommand cmdReload = new StudioCommandPalette.PaletteCommand(
                "script.reload", "Live Reload Scripts", "Runtime", "Shift+R", reloadAction);
        StudioCommandPalette.PaletteCommand cmdUnused = new StudioCommandPalette.PaletteCommand(
                "view.zoom", "Reset Stage Zoom", null, null, unusedAction);

        palette.register(cmdLint);
        palette.register(cmdExport);
        palette.register(cmdReload);
        palette.register(cmdUnused);

        assertEquals(4, palette.listAll().size());
        assertEquals("General", palette.findById("view.zoom").orElseThrow().category());
        assertEquals("", palette.findById("view.zoom").orElseThrow().shortcut());
        assertTrue(palette.findById("non.existent").isEmpty());

        List<StudioCommandPalette.PaletteCommand> buildMatches = palette.search("build");
        assertEquals(1, buildMatches.size());
        assertEquals("build.export", buildMatches.get(0).id());

        List<StudioCommandPalette.PaletteCommand> reloadMatches = palette.search("RELOAD");
        assertEquals(1, reloadMatches.size());
        assertEquals("script.reload", reloadMatches.get(0).id());

        List<StudioCommandPalette.PaletteCommand> allSorted = palette.search("");
        assertEquals(List.of("build.export", "view.zoom", "script.reload", "tools.lint"),
                allSorted.stream().map(StudioCommandPalette.PaletteCommand::id).toList());
        assertThrows(UnsupportedOperationException.class, () -> allSorted.clear());

        assertTrue(palette.executeById("tools.lint"));
        assertTrue(palette.executeById("build.export"));
        assertTrue(palette.executeById("script.reload"));
        assertTrue(palette.executeById("build.export"));
        assertFalse(palette.executeById("missing.cmd"));

        verify(lintAction, times(1)).run();
        verify(exportAction, times(2)).run();
        verify(reloadAction, times(1)).run();
        verifyNoInteractions(unusedAction);

        assertEquals(List.of("build.export", "script.reload"), palette.getRecentCommandIds());
        assertThrows(UnsupportedOperationException.class, () -> palette.getRecentCommandIds().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> palette.listAll().clear());

        assertTrue(palette.unregister("script.reload"));
        assertFalse(palette.unregister("script.reload"));
        assertTrue(palette.findById("script.reload").isEmpty());
        assertEquals(List.of("build.export"), palette.getRecentCommandIds());

        assertThrows(NullPointerException.class, () -> palette.register(null));
        assertThrows(NullPointerException.class, () -> palette.search(null));
        assertThrows(IllegalArgumentException.class,
                () -> new StudioCommandPalette.PaletteCommand(" ", "Title", "Cat", "", lintAction));
        assertThrows(IllegalArgumentException.class, () -> new StudioCommandPalette(-1));
    }

    // ---- 3. ExportPreFlightChecker ----

    @Test
    void exportPreFlightCheckerValidatesStarterProjectAndReportsWarningsAndFailures(@TempDir Path tempDir)
            throws Exception {
        ExportPreFlightChecker checker = new ExportPreFlightChecker();

        Path cleanProject = tempDir.resolve("clean_project");
        ProjectTemplateGenerator.generate(cleanProject, "Release Ready VN", "2D");

        ExportPreFlightChecker.PreFlightReport cleanReport = checker.check(cleanProject);
        assertTrue(cleanReport.readyForRelease());
        assertTrue(cleanReport.checks().stream().allMatch(c -> c.status() == ExportPreFlightChecker.CheckStatus.PASS));
        assertTrue(cleanReport.summaryMarkdown().contains("**Release Ready:** YES"));
        assertTrue(cleanReport.summaryMarkdown().contains("- [x]"));
        assertThrows(UnsupportedOperationException.class, () -> cleanReport.checks().clear());

        // Absent tl/ yields WARN, but project remains ready for release
        deleteRecursively(cleanProject.resolve("tl"));
        ExportPreFlightChecker.PreFlightReport warnReport = checker.check(cleanProject);
        assertTrue(warnReport.readyForRelease());
        assertTrue(warnReport.checks().stream().anyMatch(
                c -> c.name().equals("Translations Directory")
                        && c.status() == ExportPreFlightChecker.CheckStatus.WARN));
        assertTrue(warnReport.summaryMarkdown().contains("- [!]"));

        // Blank title in project.vjava.json triggers FAIL
        String invalidDescriptorJson = """
                {
                  "title": "   ",
                  "version": "1.0.0",
                  "entryLabel": "start"
                }
                """;
        Files.writeString(
                cleanProject.resolve(ProjectDescriptor.FILE_NAME), invalidDescriptorJson, StandardCharsets.UTF_8);
        ExportPreFlightChecker.PreFlightReport badMetaReport = checker.check(cleanProject);
        assertFalse(badMetaReport.readyForRelease());
        assertTrue(badMetaReport.checks().stream().anyMatch(
                c -> c.name().equals("Descriptor Metadata") && c.status() == ExportPreFlightChecker.CheckStatus.FAIL));

        // Missing assets/ and broken jump target fail pre-flight
        Path brokenProject = tempDir.resolve("broken_project");
        Files.createDirectories(brokenProject);
        ProjectDescriptor.save(brokenProject, ProjectDescriptor.createDefault("Broken VN"));
        Files.writeString(brokenProject.resolve("main.vjava"), """
                label start:
                    jump nonexistent_label
                """, StandardCharsets.UTF_8);

        ExportPreFlightChecker.PreFlightReport brokenReport = checker.check(brokenProject);
        assertFalse(brokenReport.readyForRelease());
        assertTrue(brokenReport.checks().stream().anyMatch(
                c -> c.name().equals("VJava Linter") && c.status() == ExportPreFlightChecker.CheckStatus.FAIL));
        assertTrue(brokenReport.checks().stream().anyMatch(
                c -> c.name().equals("Assets Directory") && c.status() == ExportPreFlightChecker.CheckStatus.FAIL));
        assertTrue(brokenReport.summaryMarkdown().contains("**Release Ready:** NO"));
        assertTrue(brokenReport.summaryMarkdown().contains("- [ ]"));

        ExportPreFlightChecker.PreFlightReport missingDirReport = checker.check(tempDir.resolve("does_not_exist"));
        assertFalse(missingDirReport.readyForRelease());
        assertEquals(ExportPreFlightChecker.CheckStatus.FAIL, missingDirReport.checks().get(0).status());

        assertThrows(NullPointerException.class, () -> checker.check(null));
    }

    // ---- 4. VpakManifestInspector ----

    @Test
    void vpakManifestInspectorVerifiesIntactArchiveAndDetectsCorruptedPayload(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("vpak_project");
        ProjectTemplateGenerator.generate(projectDir, "VPAK Inspector Test", "2D");
        Path vpakFile = tempDir.resolve("dist/game.vpak");

        List<VpakArchiver.VpakEntryInfo> packed = VpakArchiver.pack(projectDir, vpakFile);
        assertFalse(packed.isEmpty());

        VpakManifestInspector inspector = new VpakManifestInspector();
        VpakManifestInspector.VpakInspectionReport intact = inspector.inspect(vpakFile);

        assertEquals(vpakFile.toAbsolutePath().normalize(), intact.vpakPath());
        assertEquals(packed.size(), intact.entryCount());
        assertTrue(intact.totalUncompressedBytes() > 0L);
        assertTrue(intact.totalCompressedBytes() > 0L);
        assertTrue(intact.compressionRatio() > 0.0 && intact.compressionRatio() < 1.5);
        assertTrue(intact.integrityVerified());
        assertTrue(intact.corruptedEntries().isEmpty());
        assertTrue(intact.bytesByExtension().getOrDefault("png", 0L) > 0L);
        assertTrue(intact.bytesByExtension().getOrDefault("wav", 0L) > 0L);
        assertTrue(intact.bytesByExtension().getOrDefault("vjava", 0L) > 0L);
        assertTrue(intact.bytesByExtension().getOrDefault("json", 0L) > 0L);

        assertThrows(UnsupportedOperationException.class, () -> intact.bytesByExtension().put("tmp", 1L));
        assertThrows(UnsupportedOperationException.class, () -> intact.corruptedEntries().add("bad"));

        // Corrupt the first entry's compressed payload (right after the 20-byte VPAK header)
        corruptFirstPayloadByte(vpakFile);
        VpakManifestInspector.VpakInspectionReport corrupted = inspector.inspect(vpakFile);
        assertFalse(corrupted.integrityVerified());
        assertFalse(corrupted.corruptedEntries().isEmpty());

        assertThrows(NullPointerException.class, () -> inspector.inspect(null));
        assertThrows(IOException.class, () -> inspector.inspect(tempDir.resolve("missing.vpak")));
    }

    // ---- 5. StudioWorkspaceState ----

    @Test
    void studioWorkspaceStateManagesRecentProjectsBoundsAndPersistsAtomically(@TempDir Path tempDir) throws Exception {
        StudioWorkspaceState defaults = StudioWorkspaceState.defaults();
        assertTrue(defaults.getLastProjectPath().isEmpty());
        assertTrue(defaults.getRecentProjectPaths().isEmpty());
        assertTrue(defaults.isAutoLintOnReload());
        assertEquals(StudioWorkspaceState.DEFAULT_SELECTED_PANEL, defaults.getSelectedPanel());
        assertEquals(StudioWorkspaceState.DEFAULT_WINDOW_WIDTH, defaults.getWindowWidth());
        assertEquals(StudioWorkspaceState.DEFAULT_WINDOW_HEIGHT, defaults.getWindowHeight());

        StudioWorkspaceState state = StudioWorkspaceState.builder()
                .autoLintOnReload(false)
                .selectedPanel("linter")
                .windowWidth(1920)
                .windowHeight(1080)
                .build();

        for (int i = 1; i <= 12; i++) {
            state.recordOpenedProject(tempDir.resolve("project_" + i));
        }
        // Re-open project_3 to verify move-to-front deduplication
        Path project3 = tempDir.resolve("project_3").toAbsolutePath().normalize();
        state.recordOpenedProject(project3);

        assertEquals(project3.toString(), state.getLastProjectPath().orElseThrow());
        List<String> recent = state.getRecentProjectPaths();
        assertEquals(StudioWorkspaceState.MAX_RECENT_PROJECTS, recent.size());
        assertEquals(project3.toString(), recent.get(0));
        assertEquals(recent.size(), Set.copyOf(recent).size());
        assertThrows(UnsupportedOperationException.class, () -> recent.add("invalid"));

        Path stateFile = tempDir.resolve("config/studio-workspace.json");
        state.save(stateFile);
        assertTrue(Files.isRegularFile(stateFile));

        StudioWorkspaceState loaded = StudioWorkspaceState.load(stateFile);
        assertEquals(state, loaded);
        assertEquals(project3.toString(), loaded.getLastProjectPath().orElseThrow());
        assertFalse(loaded.isAutoLintOnReload());
        assertEquals("linter", loaded.getSelectedPanel());
        assertEquals(1920, loaded.getWindowWidth());
        assertEquals(1080, loaded.getWindowHeight());

        StudioWorkspaceState rebuilt = loaded.toBuilder()
                .selectedPanel("console")
                .build();
        assertNotNull(rebuilt);
        assertEquals("console", rebuilt.getSelectedPanel());

        assertThrows(NullPointerException.class, () -> state.recordOpenedProject(null));
        assertThrows(NullPointerException.class, () -> state.save(null));
        assertThrows(NullPointerException.class, () -> StudioWorkspaceState.load(null));
        assertThrows(NoSuchFileException.class, () -> StudioWorkspaceState.load(tempDir.resolve("absent.json")));
        assertThrows(IllegalArgumentException.class, () -> StudioWorkspaceState.builder().windowWidth(0));
        assertThrows(IllegalArgumentException.class, () -> StudioWorkspaceState.builder().windowHeight(-5));
    }

    private static void corruptFirstPayloadByte(Path vpakFile) throws IOException {
        try (FileChannel channel = FileChannel.open(vpakFile, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer header = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
            channel.read(header, 0L);
            header.flip();
            header.position(12);
            long tocOffset = header.getLong();
            assertTrue(tocOffset > 22L, "Archive must contain payload bytes to corrupt");

            ByteBuffer singleByte = ByteBuffer.allocate(1);
            channel.read(singleByte, 22L);
            singleByte.flip();
            byte mutated = (byte) (singleByte.get(0) ^ 0xFF);
            singleByte.clear();
            singleByte.put(mutated);
            singleByte.flip();
            channel.write(singleByte, 22L);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            List<Path> paths = stream.sorted((a, b) -> b.compareTo(a)).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }
}
