package de.swfdtf;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.WebAssemblyFeasibilityAnalyzer;
import de.swfdtf.graphics.GridPathfinder2D;
import de.swfdtf.graphics.GridPathfinder2D.GridCell;
import de.swfdtf.graphics.GridPathfinder2D.GridMap;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.Skeletal2DAdapter;
import de.swfdtf.graphics.Skeletal2DDisplayable;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.CompiledScriptBundle;
import de.swfdtf.parser.JavaBlockCompiler;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.state.CloudSyncManifest;
import de.swfdtf.state.SaveManager;
import de.swfdtf.state.SaveSlotData;
import de.swfdtf.tools.VpakArchiver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Wave P3-1 goals ({@code #0056}, {@code #0079}, {@code #0080},
 * {@code #0213}, {@code #0530}, {@code #1018}).
 */
class WaveP3CoreParserStateAnd2DTest {

    @Test
    void gridPathfinderFindsShortestWalkablePathAndDetectsBlockade() {
        GridMap map = new GridMap(5, 5);
        for (int y = 0; y < 4; y++) {
            map.setBlocked(2, y, true);
        }

        Optional<List<GridCell>> detour = GridPathfinder2D.findPath(
                map, new GridCell(0, 0), new GridCell(4, 0));
        assertTrue(detour.isPresent());
        List<GridCell> path = detour.orElseThrow();
        assertEquals(new GridCell(0, 0), path.get(0));
        assertEquals(new GridCell(4, 0), path.get(path.size() - 1));
        assertTrue(path.stream().allMatch(cell -> map.isWalkable(cell.x(), cell.y())));
        assertEquals(13, path.size());

        // Block the last opening at (2, 4) -> unreachable
        map.setBlocked(2, 4, true);
        assertEquals(Optional.empty(), GridPathfinder2D.findPath(
                map, new GridCell(0, 0), new GridCell(4, 0)));

        // Start == goal
        assertEquals(Optional.of(List.of(new GridCell(0, 0))), GridPathfinder2D.findPath(
                map, new GridCell(0, 0), new GridCell(0, 0)));
    }

    @Test
    void skeletal2DAdapterPlugsIntoSpriteNodeAsCustomDisplayable() {
        Skeletal2DAdapter.SimpleRigAdapter rig = new Skeletal2DAdapter.SimpleRigAdapter("Spine-JSON", 420.0f, 760.0f)
                .putBone(new Skeletal2DAdapter.BonePose("torso", "root", 0.0f, -120.0f, 0.0f, 1.0f, 1.0f))
                .putSlot(new Skeletal2DAdapter.SlotAttachment(
                        "coat", "torso", "coat_winter", 0, 1.0f, 220.0f, 320.0f, 1))
                .putSlot(new Skeletal2DAdapter.SlotAttachment(
                        "scarf", "torso", "scarf_red", 0, 0.9f, 140.0f, 90.0f, 2))
                .registerClip("wave", 0.8);

        rig.setAnimation("wave", true, 0.2);
        Skeletal2DDisplayable displayable = new Skeletal2DDisplayable(rig);
        SpriteNode node = new SpriteNode("eileen_rig").setCustomDisplayable(displayable);

        assertEquals(420.0f, node.getWidth(), 0.01f);
        assertEquals(760.0f, node.getHeight(), 0.01f);
        node.update(0.25);
        assertEquals("Spine-JSON", rig.getFormatName());
        assertEquals("wave", rig.getCurrentAnimation());
        assertTrue(rig.getNormalizedTime() > 0.2);

        RenderPipeline2D pipeline = new RenderPipeline2D();
        displayable.render(pipeline, new Rect2f(100.0f, 100.0f, 420.0f, 760.0f), 1.0f, ColorMatrix4f.identity());
        assertEquals(2, displayable.getLastRenderedSlotCount());
    }

    @Test
    void hotSwapUpdatesLiveDialogueWhilePreservingStateStore(@TempDir Path projectDir) throws Exception {
        writeDescriptor(projectDir);
        Path mainVjava = projectDir.resolve("main.vjava");
        Files.writeString(mainVjava, """
                define lena = Character("Lena")
                default score = 10

                label start:
                    @->{
                        state.set("score", 42);
                        state.set("flagKeep", "preserved");
                    }<<
                    lena "Ursprungstext [score]"
                    lena "Zweite Zeile"
                    return
                """, StandardCharsets.UTF_8);

        VJavaAst.ProjectAst initialAst = ProjectScanner.scan(projectDir);
        ScriptSession session = new ScriptSession(
                initialAst, new EngineAPI(EngineConfig.builder().headless(true).build()));
        session.start();

        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, session.getRuntime().getStatus());
        assertEquals("Ursprungstext 42", session.getRuntime().getActiveDialogue().interpolatedText());

        // Edit dialogue text in label start + add a new bonus label, leaving variables untouched
        Files.writeString(mainVjava, """
                define lena = Character("Lena")
                default score = 10

                label start:
                    @->{
                        state.set("score", 42);
                        state.set("flagKeep", "preserved");
                    }<<
                    lena "Live geaendert [score]"
                    lena "Zweite Zeile"
                    return

                label bonus:
                    lena "Neues Bonus-Label"
                    return
                """, StandardCharsets.UTF_8);

        VJavaAst.ProjectAst updatedAst = ProjectScanner.scan(projectDir);
        ScriptRuntime.HotSwapResult swap = session.hotSwapProject(updatedAst);

        assertTrue(swap.updatedLabels().contains("start"));
        assertTrue(swap.addedLabels().contains("bonus"));
        assertTrue(swap.activeDialogueRefreshed());
        assertEquals("Live geaendert 42", session.getRuntime().getActiveDialogue().interpolatedText());
        assertEquals(42, session.getStateStore().getInt("score", -1));
        assertEquals("preserved", session.getStateStore().getString("flagKeep", ""));
    }

    @Test
    void precompiledScriptBundleBootsAndExecutesFromVpakUnder50Ms(@TempDir Path projectDir) throws Exception {
        writeDescriptor(projectDir);
        Files.writeString(projectDir.resolve("main.vjava"), """
                label start:
                    @->{
                        state.set("booted", true);
                        state.set("magicNumber", 777);
                    }<<
                    "Schnellstart aktiv: [magicNumber]"
                    return
                """, StandardCharsets.UTF_8);

        Path vpak = projectDir.resolve("dist/game.vpak");
        List<VpakArchiver.VpakEntryInfo> packed = VpakArchiver.packWithPrecompiledScripts(projectDir, vpak, null);
        assertTrue(packed.stream().anyMatch(e -> VpakArchiver.PRECOMPILED_SCRIPTS_ENTRY.equals(e.path())));

        byte[] bundleBytes;
        try (VpakArchiver.VpakArchive archive = VpakArchiver.open(vpak)) {
            bundleBytes = archive.readEntry(VpakArchiver.PRECOMPILED_SCRIPTS_ENTRY);
        }

        long t0 = System.nanoTime();
        CompiledScriptBundle.DeserializedBundle bundle = CompiledScriptBundle.deserialize(bundleBytes);
        JavaBlockCompiler.CompiledProjectBlocks blocks = bundle.loadCompiledBlocks();
        VJavaAst.ProjectAst ast = ProjectScanner.scan(projectDir);
        ScriptRuntime runtime = new ScriptRuntime(
                ast, new EngineAPI(EngineConfig.builder().headless(true).build()));
        runtime.setPrecompiledBlocks(blocks);
        runtime.start();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertEquals("start", bundle.entryLabel());
        assertTrue(bundle.resolvedLabels().contains("start"));
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        assertEquals("Schnellstart aktiv: 777", runtime.getActiveDialogue().interpolatedText());
        assertTrue(elapsedMs < 50L, "Expected precompiled release boot < 50 ms, took " + elapsedMs + " ms");
    }

    @Test
    void cloudSyncManifestNormalizesPathsAndVerifiesCrossPlatformReload(@TempDir Path tempDir) throws Exception {
        Path macSaves = tempDir.resolve("mac/saves");
        Path winSaves = tempDir.resolve("win/saves");
        SaveManager macManager = new SaveManager(macSaves);

        SaveSlotData original = new SaveSlotData(
                new SaveSlotData.SaveMetadata(
                        "slot_1",
                        "Schlosskapitel",
                        Instant.parse("2026-11-27T09:00:00Z"),
                        "chapter_castle",
                        "Am Tor",
                        123.5,
                        "1.1.0",
                        0,
                        0,
                        null
                ),
                new LinkedHashMap<>(Map.of("chapter", 3, "hero", "Snow")),
                new SaveSlotData.SavedScriptPosition(
                        "chapter_castle", 2, List.of(), "WAITING_DIALOGUE",
                        "narrator", null, "Am Tor", false, List.of(), null, null),
                List.of()
        );
        macManager.saveSlot("slot_1", "Schlosskapitel", original);

        CloudSyncManifest.ManifestDocument doc = CloudSyncManifest.buildFromDirectory(macSaves, "1.1.0");
        assertEquals(1, doc.entries().size());
        CloudSyncManifest.SaveFileEntry entry = doc.entries().get(0);
        assertEquals("slot_1.ksav", entry.relativePath());
        assertTrue(entry.modifiedUtcIso8601().endsWith("Z"));

        String json = CloudSyncManifest.toCanonicalJson(doc);
        assertFalse(json.contains("\r\n"));
        CloudSyncManifest.ManifestDocument parsed = CloudSyncManifest.fromJson(json);

        // Normalize Windows backslash paths and reject unsafe traversal
        assertEquals("sub/slot_1.ksav", CloudSyncManifest.normalizeRelativePath(".\\sub\\slot_1.ksav"));
        assertThrows(IllegalArgumentException.class, () -> CloudSyncManifest.normalizeRelativePath("../secret.ksav"));
        assertThrows(IllegalArgumentException.class, () -> CloudSyncManifest.normalizeRelativePath("C:\\bad.ksav"));

        for (CloudSyncManifest.SaveFileEntry e : parsed.entries()) {
            Path src = CloudSyncManifest.resolvePortablePath(macSaves, e.relativePath());
            Path dst = CloudSyncManifest.resolvePortablePath(winSaves, e.relativePath());
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst);
        }

        CloudSyncManifest.VerificationResult verify = CloudSyncManifest.verifyDirectory(winSaves, parsed);
        assertTrue(verify.valid());
        SaveSlotData reloaded = new SaveManager(winSaves).loadSlot("slot_1");
        assertEquals("chapter_castle", reloaded.metadata().currentLabel());
        assertEquals(3, ((Number) reloaded.variables().get("chapter")).intValue());
        assertEquals("Snow", reloaded.variables().get("hero"));
    }

    @Test
    void webAssemblyFeasibilityStudyIdentifiesNonLwjglBlockersAndDocExists() throws Exception {
        Path repoRoot = Path.of(".").toAbsolutePath().normalize();
        WebAssemblyFeasibilityAnalyzer.FeasibilityReport report =
                WebAssemblyFeasibilityAnalyzer.analyzeRepository(repoRoot);

        assertTrue(report.totalFilesScanned() > 50);
        assertTrue(report.nonLwjglBlockers().stream().anyMatch(
                f -> f.apiIdentifier().contains("javax.tools") || f.apiIdentifier().contains("ToolProvider")));
        assertTrue(report.nonLwjglBlockers().stream().anyMatch(
                f -> f.apiIdentifier().contains("ProcessBuilder")));
        assertNotNull(report.byPackage().get("de.swfdtf.parser"));

        Path studyDoc = repoRoot.resolve("docs/WEBASSEMBLY_FEASIBILITY.md");
        assertTrue(Files.isRegularFile(studyDoc));
        String docText = Files.readString(studyDoc, StandardCharsets.UTF_8);
        assertTrue(docText.contains("javax.tools"));
        assertTrue(docText.contains("CompiledScriptBundle"));
    }

    private static void writeDescriptor(Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("project.vjava.json"), """
                {
                  "name": "WaveP3Test",
                  "version": "1.0.0",
                  "entryLabel": "start",
                  "defaultLanguage": "deutsch"
                }
                """, StandardCharsets.UTF_8);
    }
}
