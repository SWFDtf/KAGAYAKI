package de.swfdtf.tools;

import de.swfdtf.audio.AudioDecoder;
import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptProfiler;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.parser.VJavaParser;
import de.swfdtf.state.SaveManager;
import de.swfdtf.state.SaveSlotData;
import de.swfdtf.ui.ThemePalette;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehensive tests for Wave P2-2 Studio authoring, refactoring, QA, packaging,
 * recovery, template repository, and CLI automation tools ({@code #0101}–{@code #0170}).
 */
class WaveP2Chapter2StudioToolsTest {

    @Test
    void themeVariantsWcagContrastAndWorkspaceStatePersistCleanly(@TempDir Path dir) throws Exception {
        assertTrue(ThemePalette.meetsWcagAA(
                ThemePalette.ThemeVariant.MIDNIGHT_HIGH_CONTRAST.textPrimary(),
                ThemePalette.ThemeVariant.MIDNIGHT_HIGH_CONTRAST.panelSurface()));
        assertTrue(ThemePalette.meetsWcagAA(
                ThemePalette.ThemeVariant.DAYLIGHT.textPrimary(),
                ThemePalette.ThemeVariant.DAYLIGHT.panelSurface()));
        assertFalse(ThemePalette.meetsWcagAA("#1a1c28", ThemePalette.PANEL_SURFACE));

        StudioWorkspaceState state = StudioWorkspaceState.builder()
                .layoutPreset("DEBUG")
                .themeVariant(ThemePalette.ThemeVariant.DAYLIGHT.name())
                .uiScale(1.75f)
                .onboardingTourCompleted(true)
                .userTemplatePaths(List.of(dir.toString()))
                .build();
        assertEquals(1.50f, state.getUiScale(), 0.001f);
        assertEquals("DEBUG", state.getLayoutPreset());
        assertTrue(state.getPanelVisibility().get("flowGraph"));

        Path jsonFile = dir.resolve("studio.json");
        state.save(jsonFile);
        StudioWorkspaceState loaded = StudioWorkspaceState.load(jsonFile);
        assertEquals(state, loaded);
    }

    @Test
    void searchReplaceDiagnosticZipAndBuilderProfilesVerifyChecksumsAndBudget(@TempDir Path dir) throws Exception {
        Path projectDir = ProjectTemplateGenerator.generate(
                dir.resolve("sample"), "Build Sample", null, "2D", ProjectTemplateGenerator.TemplateType.MINIMAL);

        ProjectSearchService search = new ProjectSearchService();
        ProjectSearchService.ReplaceResult dry = search.replacePlainText(
                projectDir, "start", "intro_start", true, true);
        assertTrue(dry.totalReplacements() >= 1);
        assertTrue(Files.readString(projectDir.resolve("main.vjava")).contains("label start:"));

        ProjectSearchService.ReplaceResult regexDry = search.replaceRegex(
                projectDir, Pattern.compile("Minimal\\w*"), "CustomMinimal", true);
        assertTrue(regexDry.totalReplacements() >= 1);
        assertTrue(Files.readString(projectDir.resolve("main.vjava")).contains("Minimal"));

        DiagnosticBundleExporter exporter = new DiagnosticBundleExporter();
        VJavaAst.ProjectAst ast = ProjectScanner.scan(projectDir);
        VJavaLinter.LintReport lint = VJavaLinter.lint(ast);
        Path zipPath = exporter.exportZipBundle(
                dir.resolve("diag.zip"), new EngineAPI(EngineConfig.defaults()), lint, ast, Map.of("fps", 60));
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            assertTrue(zip.getEntry("diagnostic-report.json") != null);
            assertTrue(zip.getEntry("logs.txt") != null);
            assertTrue(zip.getEntry("lint-report.json") != null);
            assertTrue(zip.getEntry("ast-summary.json") != null);
            assertTrue(zip.getEntry("system-info.txt") != null);
        }

        Path outDir = dir.resolve("dist_macos");
        StandaloneBuilder.BuildResult buildRes = StandaloneBuilder.build(
                projectDir, outDir, null, StandaloneBuilder.ExportProfile.MACOS, 1L);
        assertTrue(Files.isRegularFile(buildRes.vpakFile()));
        Path checksumsFile = outDir.resolve("checksums.sha256");
        assertTrue(Files.isRegularFile(checksumsFile));
        String checksumsContent = Files.readString(checksumsFile, StandardCharsets.UTF_8);
        assertTrue(checksumsContent.contains("game.vpak"));
        assertTrue(Files.readString(buildRes.packagingDescriptor()).contains("-XstartOnFirstThread"));
        assertFalse(StandaloneBuilder.inspectAssetSizeBudget(projectDir, 1L).isEmpty());
    }

    @Test
    void linterRulesConfigPlaceholderPngAndAutocompleteWorkTogether(@TempDir Path dir) throws Exception {
        Path projectDir = ProjectTemplateGenerator.generate(
                dir.resolve("lint_cfg"), "Lint Config", null, "2D", ProjectTemplateGenerator.TemplateType.MINIMAL);
        Path mainScript = projectDir.resolve("main.vjava");
        String withMissing = Files.readString(mainScript)
                + "\nimage hero_idle = \"assets/images/missing_hero.png\"\n"
                + "label orphan_scene:\n    \"Orphan\"\n    return\n";
        Files.writeString(mainScript, withMissing, StandardCharsets.UTF_8);

        Files.writeString(projectDir.resolve(VJavaLinter.LINT_CONFIG_FILE),
                "{\"rules\":{\"UNREACHABLE_LABEL\":\"ERROR\"}}", StandardCharsets.UTF_8);
        VJavaLinter.LintReport errReport = VJavaLinter.lint(projectDir);
        assertTrue(errReport.issues().stream().anyMatch(
                i -> "UNREACHABLE_LABEL".equals(i.code()) && i.severity() == VJavaLinter.Severity.ERROR));
        assertTrue(errReport.issues().stream().anyMatch(i -> "MISSING_ASSET".equals(i.code())));

        Path createdPng = new VJavaLinterAutoFixer().createPlaceholderPngForMissingAsset(
                projectDir, "assets/images/missing_hero.png");
        assertTrue(Files.isRegularFile(createdPng));
        Files.writeString(projectDir.resolve(VJavaLinter.LINT_CONFIG_FILE),
                "{\"rules\":{\"UNREACHABLE_LABEL\":\"OFF\"}}", StandardCharsets.UTF_8);
        VJavaLinter.LintReport cleanReport = VJavaLinter.lint(projectDir);
        assertFalse(cleanReport.issues().stream().anyMatch(i -> "MISSING_ASSET".equals(i.code())));
        assertFalse(cleanReport.issues().stream().anyMatch(i -> "UNREACHABLE_LABEL".equals(i.code())));

        VJavaAst.ProjectAst ast = ProjectScanner.scan(projectDir);
        VJavaAutocompleteEngine ac = new VJavaAutocompleteEngine();
        assertTrue(ac.suggest(ast, "show hero").stream().anyMatch(s -> s.text().contains("hero_idle")));
        assertTrue(ac.suggest(ast, "jump orph").stream().anyMatch(s -> "orphan_scene".equals(s.text())));
        assertTrue(ac.suggest(ast, "show bg at cen").stream().anyMatch(s -> "center".equals(s.text())));
    }

    @Test
    void refactorServiceAndAuthoringToolkitCoverAllStudioAuthoringFlows(@TempDir Path dir) throws Exception {
        Path projectDir = ProjectTemplateGenerator.generate(
                dir.resolve("ref"), "Refactor", null, "2D", ProjectTemplateGenerator.TemplateType.MINIMAL);
        Files.createDirectories(projectDir.resolve("build"));
        Files.writeString(projectDir.resolve("build/ignored.tmp"), "ignore", StandardCharsets.UTF_8);

        ProjectRefactorService refactor = new ProjectRefactorService();
        Path newScript = refactor.createNewScript(projectDir, "scenes/chapter2", "chap2_entry");
        assertTrue(Files.isRegularFile(newScript));
        assertTrue(ProjectScanner.scan(projectDir).labels().containsKey("chap2_entry"));

        ProjectRefactorService.RenameResult ren = refactor.renameLabel(projectDir, "chap2_entry", "chap2_renamed");
        assertTrue(ren.filesModified() >= 1);
        assertTrue(ProjectScanner.scan(projectDir).labels().containsKey("chap2_renamed"));

        assertEquals("1.0.1", refactor.bumpProjectVersion(projectDir, ProjectRefactorService.VersionBump.PATCH));
        assertEquals("1.1.0", refactor.bumpProjectVersion(projectDir, ProjectRefactorService.VersionBump.MINOR));

        Path extImg = dir.resolve("ext_hero.png");
        TextureManager.writePng(extImg, 2, 2, new byte[16]);
        Path imported = refactor.importExternalAsset(projectDir, extImg);
        assertTrue(imported.startsWith(projectDir.resolve("assets/images")));

        Path backupZip = refactor.archiveProjectZip(projectDir, dir.resolve("backup.zip"));
        try (ZipFile zip = new ZipFile(backupZip.toFile())) {
            assertEquals(null, zip.getEntry("build/ignored.tmp"));
        }

        List<ProjectRefactorService.FormatFileResult> formatted = refactor.formatAllScripts(projectDir, true);
        assertTrue(formatted.stream().allMatch(ProjectRefactorService.FormatFileResult::astPreserved));

        StudioAuthoringToolkit toolkit = new StudioAuthoringToolkit();
        assertEquals(4, toolkit.simulateScreenAspects().size());
        assertFalse(toolkit.checkCharacterContrast("dark", "#1c1f2e", ThemePalette.PANEL_SURFACE).wcagAaPassed());
        ProjectDescriptor updatedDesc = new StudioAuthoringToolkit.ProjectDescriptorFormModel(
                ProjectDescriptor.load(projectDir))
                .setAuthor("Studio Author")
                .setResolution(1920, 1080)
                .applyAndSave(projectDir);
        assertEquals("Studio Author", updatedDesc.getAuthor());

        String tBlock = toolkit.buildTransformPresetBlock("hero_close", 0.5f, 1.0f, 1.25f);
        VJavaAst.ScriptFileAst tAst = VJavaParser.parse(Path.of("t.vjava"), tBlock);
        assertEquals(1, tAst.transforms().size());

        Path wavDir = projectDir.resolve("assets/audio/voice");
        Files.createDirectories(wavDir);
        AudioDecoder.DecodedAudio clippedTone = AudioDecoder.synthesizeTone(440.0, 0.25, 22050, 1.0f);
        short[] clippedPcm = clippedTone.pcm16().clone();
        clippedPcm[100] = Short.MAX_VALUE;
        clippedPcm[101] = Short.MAX_VALUE;
        Path wavPath = wavDir.resolve("line_01.wav");
        Files.write(wavPath, AudioDecoder.encodeWav(clippedPcm, 1, 22050));

        StudioAuthoringToolkit.LipSyncCalibration cal = toolkit.calibrateLipSync(wavPath);
        assertTrue(cal.peakRms() > 0.0f && cal.recommendedOpen() > cal.recommendedHalfOpen());
        List<StudioAuthoringToolkit.AudioPeakDiagnostic> peaks = toolkit.checkAudioClipping(projectDir);
        assertTrue(peaks.stream().anyMatch(p -> "AUDIO_CLIPPING".equals(p.code())));

        byte[] imgA = new byte[]{10, 20, 30, (byte) 255, 10, 20, 30, (byte) 255};
        byte[] imgB = new byte[]{(byte) 250, 20, 30, (byte) 255, 10, 20, 30, (byte) 255};
        StudioAuthoringToolkit.ScreenshotDiffResult diff = toolkit.diffRgbaImages(2, 1, imgA, imgB, 5);
        assertEquals(1, diff.differedPixels());
        assertEquals(50.0, diff.diffPercentage(), 0.01);

        VJavaAst.ScriptFileAst tlAst = VJavaParser.parse(Path.of("tl.vjava"), """
                label s:
                    move hero to 600 400 duration 2.0
                    "Hello"
                """);
        List<StudioAuthoringToolkit.TimelineCue> cues = toolkit.buildTimelineCues(tlAst.labels().get(0));
        float[] mid = toolkit.sampleStageAt(cues, "hero", 1.0, 200.0f, 400.0f);
        assertEquals(400.0f, mid[0], 0.1f);

        String md = toolkit.exportCharacterAndCodexMarkdown(
                ProjectScanner.scan(projectDir), Map.of("mirror", "Magic Mirror"));
        assertTrue(md.contains("mirror") && md.contains("Magic Mirror"));
    }

    @Test
    void qaServiceAutoRecoveryTemplatesAndCliRunnerOperateCleanly(@TempDir Path dir) throws Exception {
        Path projectDir = ProjectTemplateGenerator.generate(
                dir.resolve("qa_proj"), "QA Project", null, "2D", ProjectTemplateGenerator.TemplateType.FULL_DEMO);

        StudioReportAndQaService qa = new StudioReportAndQaService();
        VJavaAst.ProjectAst ast = ProjectScanner.scan(projectDir);
        assertFalse(qa.buildTranslationMatrix(ast, "french").isEmpty());

        ScriptProfiler profiler = new ScriptProfiler();
        profiler.recordExecution("start", 0, 5_000_000L);
        assertEquals("start", qa.rankHotspots(profiler, 3).get(0).label());

        Path savesDir = projectDir.resolve("saves");
        SaveManager sm = new SaveManager(savesDir);
        SaveSlotData sampleSlot = new SaveSlotData(
                new SaveSlotData.SaveMetadata("slot_1", "Chapter 1", Instant.now(),
                        "start", "Hi", 12.5, "1.0.0", 0, 0, null),
                Map.of("score", 42),
                new SaveSlotData.SavedScriptPosition(
                        "start", 1, List.of(), "WAITING_DIALOGUE", null, null, "Hi",
                        false, List.of(), null, new SceneController.StageSnapshot(null, null, null, Map.of())),
                List.of(), null);
        sm.saveSlot("slot_1", sampleSlot);
        Files.writeString(savesDir.resolve("broken.ksav"), "{bad-gzip", StandardCharsets.UTF_8);
        List<StudioReportAndQaService.SaveSlotInspectionEntry> inspected = qa.inspectSaveDirectory(savesDir);
        assertTrue(inspected.stream().anyMatch(e -> e.valid() && "slot_1".equals(e.slotId())));
        assertTrue(inspected.stream().anyMatch(e -> !e.valid() && !e.migrationHint().isBlank()));

        Path fixture = qa.exportSaveAsTestFixture(sampleSlot, dir.resolve("fixture.json"));
        assertEquals(42, ((Number) qa.loadTestFixture(fixture).variables().get("score")).intValue());

        Path htmlPath = qa.exportHtmlDashboard(projectDir, dir.resolve("report.html"));
        String html = Files.readString(htmlPath, StandardCharsets.UTF_8);
        assertFalse(html.contains("http://") || html.contains("https://"));

        assertTrue(qa.diagnoseJavaBlocks(ast).isEmpty());
        assertFalse(qa.testAllEndings(ast, List.of()).reachedEndings().isEmpty());
        StudioReportAndQaService.ReleaseReadinessReport readiness = qa.evaluateReleaseReadiness(projectDir);
        assertFalse(readiness.summary().isBlank());

        StudioWorkspaceAutoRecovery recovery = new StudioWorkspaceAutoRecovery(projectDir);
        Path targetScript = projectDir.resolve("main.vjava");
        assertEquals(1, recovery.tick(30.0, Map.of(targetScript, "# unsaved edit")));
        assertTrue(recovery.hasBackup(targetScript));
        assertEquals("# unsaved edit", recovery.restoreBackup(targetScript).orElseThrow());
        recovery.clearBackup(targetScript);
        assertFalse(recovery.hasBackup(targetScript));

        UserTemplateRepository templates = new UserTemplateRepository(dir.resolve("user_templates"));
        Path customTpl = templates.saveAsTemplate(projectDir, "MyVN");
        assertTrue(templates.listAllPreviewCards().stream()
                .anyMatch(c -> c.userDefined() && "MyVN".equals(c.displayName())));
        assertTrue(templates.createProjectFromTemplate(customTpl, dir.resolve("clone_from_tpl")) > 0);
        assertNotEquals(
                UserTemplateRepository.builtInPreview(ProjectTemplateGenerator.TemplateType.FULL_DEMO).summary(),
                UserTemplateRepository.builtInPreview(ProjectTemplateGenerator.TemplateType.MINIMAL).summary());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(0, StudioCliRunner.run(
                new String[]{"lint", projectDir.toString()}, new PrintStream(out), new PrintStream(err)));
        assertEquals(0, StudioCliRunner.run(
                new String[]{"preflight", projectDir.toString()}, new PrintStream(out), new PrintStream(err)));
        assertEquals(0, StudioCliRunner.run(
                new String[]{"html-report", projectDir.toString(), dir.resolve("cli.html").toString()},
                new PrintStream(out), new PrintStream(err)));
    }
}
