package de.swfdtf.tools;

import com.google.gson.Gson;
import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineLogger;
import de.swfdtf.core.EngineMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Comprehensive unit and integration tests for {@link AssetChecksumVerifier}
 * and {@link DiagnosticBundleExporter}.
 */
@ExtendWith(MockitoExtension.class)
class Wave1ToolsFeaturesTest {

    @Test
    void assetChecksumVerifierGeneratesSortedManifestAndRoundTripsJson(@TempDir Path projectDir) throws Exception {
        Path bgFile = projectDir.resolve("assets/images/bg/school.png");
        Path sfxFile = projectDir.resolve("assets/audio/click.wav");
        Path ignoredScript = projectDir.resolve("main.vjava");
        Files.createDirectories(bgFile.getParent());
        Files.createDirectories(sfxFile.getParent());
        Files.writeString(bgFile, "fake-png-payload", StandardCharsets.UTF_8);
        Files.writeString(sfxFile, "fake-wav-payload", StandardCharsets.UTF_8);
        Files.writeString(ignoredScript, "label start:\n", StandardCharsets.UTF_8);

        AssetChecksumVerifier verifier = new AssetChecksumVerifier();
        AssetChecksumVerifier.AssetManifest manifest = verifier.generateManifest(projectDir);

        assertEquals(AssetChecksumVerifier.DEFAULT_ALGORITHM, manifest.algorithm());
        assertEquals(
                List.of("assets/audio/click.wav", "assets/images/bg/school.png"),
                List.copyOf(manifest.checksums().keySet())
        );
        assertEquals(64, manifest.checksums().get("assets/audio/click.wav").length());
        assertThrows(UnsupportedOperationException.class, () -> manifest.checksums().put("x", "y"));

        String json = manifest.toJson();
        AssetChecksumVerifier.AssetManifest parsed = AssetChecksumVerifier.AssetManifest.fromJson(json);
        assertEquals(manifest, parsed);

        AssetChecksumVerifier.VerificationReport intactReport = verifier.verify(projectDir, parsed);
        assertTrue(intactReport.isIntact());
        assertEquals(2, intactReport.validCount());
        assertTrue(intactReport.missingFiles().isEmpty());
        assertTrue(intactReport.modifiedFiles().isEmpty());
        assertTrue(intactReport.unexpectedFiles().isEmpty());
    }

    @Test
    void assetChecksumVerifierDetectsMissingModifiedAndUnexpectedFiles(@TempDir Path projectDir) throws Exception {
        Path assetsDir = projectDir.resolve("assets/images");
        Files.createDirectories(assetsDir);
        Path keepFile = assetsDir.resolve("keep.png");
        Path modifyFile = assetsDir.resolve("sprite.png");
        Path deleteFile = assetsDir.resolve("missing.png");
        Files.writeString(keepFile, "stable", StandardCharsets.UTF_8);
        Files.writeString(modifyFile, "v1", StandardCharsets.UTF_8);
        Files.writeString(deleteFile, "gone-soon", StandardCharsets.UTF_8);

        AssetChecksumVerifier verifier = new AssetChecksumVerifier();
        AssetChecksumVerifier.AssetManifest baseline = verifier.generateManifest(projectDir);

        // Mutate assets directory
        Files.writeString(modifyFile, "v2-tampered", StandardCharsets.UTF_8);
        Files.delete(deleteFile);
        Files.writeString(assetsDir.resolve("extra.png"), "surprise", StandardCharsets.UTF_8);

        AssetChecksumVerifier.VerificationReport report = verifier.verify(projectDir, baseline);
        assertFalse(report.isIntact());
        assertEquals(1, report.validCount());
        assertEquals(List.of("assets/images/missing.png"), report.missingFiles());
        assertEquals(List.of("assets/images/sprite.png"), report.modifiedFiles());
        assertEquals(List.of("assets/images/extra.png"), report.unexpectedFiles());

        assertThrows(UnsupportedOperationException.class, () -> report.missingFiles().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.modifiedFiles().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.unexpectedFiles().clear());
    }

    @Test
    void assetChecksumVerifierHandlesMissingAssetsDirAndGuards(@TempDir Path projectDir) throws Exception {
        AssetChecksumVerifier verifier = new AssetChecksumVerifier();
        AssetChecksumVerifier.AssetManifest emptyManifest = verifier.generateManifest(projectDir);
        assertTrue(emptyManifest.checksums().isEmpty());
        assertTrue(verifier.verify(projectDir, emptyManifest).isIntact());

        assertThrows(NullPointerException.class, () -> verifier.generateManifest(null));
        assertThrows(NullPointerException.class, () -> verifier.verify(projectDir, null));
        assertThrows(IllegalArgumentException.class, () -> AssetChecksumVerifier.AssetManifest.fromJson("{not-json"));
        assertThrows(IllegalArgumentException.class,
                () -> verifier.verify(projectDir, new AssetChecksumVerifier.AssetManifest("UNKNOWN-ALG", Map.of())));
    }

    @Test
    void diagnosticBundleExporterExportsAtomicJsonWithLintAndMetrics(@TempDir Path tempDir) throws Exception {
        EngineConfig config = EngineConfig.builder()
                .engineMode(EngineMode.STUDIO_DUAL_WINDOW)
                .renderModeName("2D")
                .projectDir(tempDir)
                .logicalWidth(1920)
                .logicalHeight(1080)
                .vsync(false)
                .targetFps(144)
                .headless(true)
                .build();
        EngineLogger logger = new EngineLogger(50);
        logger.setConsoleOutputEnabled(false);
        logger.info("Boot", "Engine started");
        logger.warn("Assets", "Low texture cache");

        EngineAPI api = new EngineAPI(
                config, null, logger, new de.swfdtf.core.GameClock(), new de.swfdtf.core.InputManager());
        VJavaLinter.LintReport lintReport = new VJavaLinter.LintReport(
                List.of(new VJavaLinter.LintIssue(
                        VJavaLinter.Severity.WARNING,
                        "UNREACHABLE_LABEL",
                        "Label 'secret' is unreachable.",
                        "main.vjava",
                        42
                )),
                0,
                1
        );

        DiagnosticBundleExporter exporter = new DiagnosticBundleExporter(10);
        Path targetFile = tempDir.resolve("nested/diagnostics/bundle.json");
        Path exported = exporter.export(
                targetFile,
                api,
                lintReport,
                Map.of("fps", 60.0, "activeSprites", 3)
        );

        assertEquals(targetFile.toAbsolutePath().normalize(), exported);
        assertTrue(Files.isRegularFile(exported));

        String rawJson = Files.readString(exported, StandardCharsets.UTF_8);
        DiagnosticBundleExporter.DiagnosticReport parsed =
                new Gson().fromJson(rawJson, DiagnosticBundleExporter.DiagnosticReport.class);

        assertNotNull(parsed.exportedAt());
        assertEquals(EngineAPI.ENGINE_VERSION, parsed.engineVersion());
        assertEquals(EngineAPI.RENDER_BACKEND_NAME, parsed.renderBackend());
        assertEquals("STUDIO_DUAL_WINDOW", parsed.config().engineMode());
        assertEquals(144, parsed.config().targetFps());
        assertEquals(2, parsed.recentLogLines().size());
        assertTrue(parsed.recentLogLines().get(0).contains("Engine started"));
        assertFalse(parsed.lintReport().hasErrors());
        assertEquals(1, parsed.lintReport().warningCount());
        assertEquals("UNREACHABLE_LABEL", parsed.lintReport().issues().get(0).code());
        assertEquals(60.0, ((Number) parsed.metrics().get("fps")).doubleValue(), 1e-6);
    }

    @Test
    void diagnosticBundleExporterIntegratesWithMockitoEngineApiAndHandlesNullOptionals(@TempDir Path tempDir)
            throws Exception {
        EngineAPI mockApi = mock(EngineAPI.class);
        EngineLogger mockLogger = mock(EngineLogger.class);

        when(mockApi.getEngineVersion()).thenReturn("KAGAYAKI-TEST");
        when(mockApi.getRenderBackendName()).thenReturn(null);
        when(mockApi.getConfig()).thenReturn(null);
        when(mockApi.getLogger()).thenReturn(mockLogger);
        when(mockLogger.getRecentEntries(25)).thenReturn(List.of(
                new EngineLogger.LogEntry(
                        Instant.parse("2026-03-01T12:00:00Z"), EngineLogger.Level.ERROR, "Test", "Boom")
        ));

        DiagnosticBundleExporter exporter = new DiagnosticBundleExporter(25);
        Path output = exporter.export(tempDir.resolve("diag.json"), mockApi, null, null);

        verify(mockLogger).getRecentEntries(25);

        DiagnosticBundleExporter.DiagnosticReport report = new Gson().fromJson(
                Files.readString(output, StandardCharsets.UTF_8),
                DiagnosticBundleExporter.DiagnosticReport.class
        );
        assertEquals("KAGAYAKI-TEST", report.engineVersion());
        assertEquals(EngineAPI.RENDER_BACKEND_NAME, report.renderBackend());
        assertNull(report.config());
        assertNull(report.lintReport());
        assertEquals(1, report.recentLogLines().size());
        assertTrue(report.recentLogLines().get(0).contains("Boom"));
        assertTrue(report.metrics().isEmpty());

        assertThrows(IllegalArgumentException.class, () -> new DiagnosticBundleExporter(-1));
        assertThrows(NullPointerException.class, () -> exporter.export(null, mockApi, null, Map.of()));
        assertThrows(NullPointerException.class, () -> exporter.export(output, null, null, Map.of()));
    }
}
