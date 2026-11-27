package de.swfdtf.tools;

import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.state.SaveManager;
import de.swfdtf.state.SaveSlotData;
import de.swfdtf.state.SaveSlotData.SaveMetadata;
import de.swfdtf.state.SaveSlotData.SavedScriptPosition;
import de.swfdtf.tools.ProjectTemplateGenerator.TemplateType;
import de.swfdtf.tools.TemplateEcosystemServices.CommunityTemplateValidator;
import de.swfdtf.tools.TemplateEcosystemServices.EngineShowcaseReportExporter;
import de.swfdtf.tools.TemplateEcosystemServices.ProceduralDemoSoundKit;
import de.swfdtf.tools.TemplateEcosystemServices.StudioHubExtras;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests verifying all 9 {@link TemplateType} presets (incl. {@code HYBRID_3D_MYSTERY},
 * {@code NVL_LITERARY}, {@code TUTORIAL_5_MIN}, {@code SANDBOX_2D_LAB}), compact template size/speed,
 * generated icon/.gitignore/README, DE/EN starter generation, 6-slot save roundtrips, and
 * template validation/showcase tools (Wave P2-11).
 */
class WaveP2Chapter11TemplatesAndEcosystemTest {

    @ParameterizedTest
    @EnumSource(TemplateType.class)
    void allTemplateTypesGenerateScanLintCleanlyAndPackUnderBudget(TemplateType type, @TempDir Path tempDir)
            throws Exception {
        long t0 = System.nanoTime();
        Path projectDir = ProjectTemplateGenerator.generate(
                tempDir.resolve(type.name().toLowerCase()),
                "Test " + type.name(),
                "QA Bot",
                "2D",
                type
        );
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(elapsedMs < 2_000L);

        assertTrue(Files.isRegularFile(projectDir.resolve("assets/icon.png")));
        assertTrue(Files.isRegularFile(projectDir.resolve(".gitignore")));
        assertTrue(Files.isRegularFile(projectDir.resolve("README.md")));

        ProjectDescriptor desc = ProjectDescriptor.load(projectDir);
        assertEquals("assets/icon.png", desc.getIconPath());
        assertTrue(desc.getAccentColorHex().startsWith("#"));

        var validation = CommunityTemplateValidator.validateTemplate(projectDir);
        assertTrue(validation.passed(),
                type + " lint failed: " + validation.toPlainText() + " -> " + validation.toJson());

        // Pack into .vpak and ensure compact starter size (< 2 MB) (#0921)
        Path vpak = tempDir.resolve(type.name() + ".vpak");
        VpakArchiver.pack(projectDir, vpak);
        assertTrue(Files.size(vpak) < 2L * 1024L * 1024L);
    }

    @Test
    void testUserTemplatesEnglishStarterSixSlotSaveRoundtripAndEcosystemTools(@TempDir Path tempDir)
            throws Exception {
        UserTemplateRepository repo = new UserTemplateRepository(tempDir.resolve("user_templates"));
        var cards = repo.listAllPreviewCards();
        assertEquals(TemplateType.values().length, cards.size());

        // #0909 English starter variant
        Path enProj = ProjectTemplateGenerator.generate(
                tempDir.resolve("en_starter"), "English Quest", "QA", "2D",
                TemplateType.MINIMAL, "english");
        assertEquals("english", ProjectDescriptor.load(enProj).getDefaultLanguage());
        assertTrue(CommunityTemplateValidator.validateTemplate(enProj).passed());

        // #0927 FULL_DEMO save/load roundtrip across slots 1..6
        SaveManager saveManager = new SaveManager(tempDir.resolve("saves"));
        for (int slot = 1; slot <= 6; slot++) {
            String slotId = "slot_" + slot;
            SaveSlotData data = new SaveSlotData(
                    new SaveMetadata(slotId, "Slot " + slot, Instant.now(),
                            "wasd_arena", "x=" + (100 + slot), 15.0 * slot, "1.0.0", 0, 0, new byte[0]),
                    Map.of("playerX", 100.0 + slot, "courage", slot * 5),
                    new SavedScriptPosition("wasd_arena", slot, List.of(), "WAITING_DIALOGUE",
                            "lena", "Lena", "Go", false, List.of(), null, null),
                    List.of()
            );
            saveManager.saveSlot(slotId, data);
            SaveSlotData loaded = saveManager.loadSlot(slotId);
            assertEquals(100.0 + slot, ((Number) loaded.variables().get("playerX")).doubleValue(), 1e-6);
            assertEquals(slot * 5, ((Number) loaded.variables().get("courage")).intValue());
        }

        // #0896 Sound kit WAV generation, #0907 HTML dashboard, #0916/#0931/#0933 StudioHubExtras
        List<Path> wavs = ProceduralDemoSoundKit.generateSoundKit(tempDir.resolve("sfx"));
        assertEquals(3, wavs.size());
        Path dashboard = EngineShowcaseReportExporter.exportHtmlDashboard(
                tempDir.resolve("build/reports/showcase.html"), "Demo", 0, 1200, 1.45);
        assertTrue(Files.exists(dashboard));
        assertTrue(StudioHubExtras.getOfflineChangelogHighlights().size() >= 3);
        assertTrue(StudioHubExtras.buildSafeOpenExplorerCommand(tempDir, "Mac OS X", true).isEmpty());
        assertEquals(List.of("open", tempDir.toAbsolutePath().normalize().toString()),
                StudioHubExtras.buildSafeOpenExplorerCommand(tempDir, "Mac OS X", false));
        assertNotNull(StudioHubExtras.resolveInitialOrFallbackProject(null, enProj));
    }
}
