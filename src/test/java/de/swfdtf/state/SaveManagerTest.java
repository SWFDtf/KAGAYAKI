package de.swfdtf.state;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SceneGraph;
import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.JavaBlockCompiler;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.state.SaveSlotData.SaveMetadata;
import de.swfdtf.state.SaveSlotData.SavedScriptPosition;
import de.swfdtf.state.SaveSlotData.SavedStackFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit and integration tests for {@link SaveManager}, {@link SaveSlotData}, and {@link SaveValueCodec},
 * including LWJGL {@code STBImageWrite} PNG thumbnail round-trips and compiled {@code VJavaUserTypes}.
 */
class SaveManagerTest {

    public enum RouteAffinity {
        ACADEMY, ROOFTOP, LIBRARY
    }

    public static final class SamplePojo {
        public String title;
        public int level;
        public RouteAffinity affinity;

        public SamplePojo() {
        }

        public SamplePojo(String title, int level, RouteAffinity affinity) {
            this.title = title;
            this.level = level;
            this.affinity = affinity;
        }
    }

    @Test
    void encodeRgbaToPngRoundTripsThroughStbImageIncludingVerticalFlip() {
        int width = 4;
        int height = 2;
        byte[] topToBottomRgba = new byte[]{
            // Row 0 (top): red, green, blue, white
            (byte) 255, 0, 0, (byte) 255,
            0, (byte) 255, 0, (byte) 255,
            0, 0, (byte) 255, (byte) 255,
            (byte) 255, (byte) 255, (byte) 255, (byte) 200,
            // Row 1 (bottom): yellow, cyan, magenta, translucent gray
            (byte) 255, (byte) 255, 0, (byte) 255,
            0, (byte) 255, (byte) 255, (byte) 255,
            (byte) 255, 0, (byte) 255, (byte) 255,
            64, 64, 64, (byte) 128
        };

        byte[] png = SaveManager.encodeRgbaToPng(width, height, topToBottomRgba);
        assertTrue(png.length > 8);
        assertArrayEquals(topToBottomRgba, decodePngWithStb(png, width, height));

        // Simulate bottom-up OpenGL FBO pixel order and verify flipVertically=true restores top-to-bottom
        byte[] bottomUpRgba = new byte[topToBottomRgba.length];
        int rowBytes = width * 4;
        System.arraycopy(topToBottomRgba, rowBytes, bottomUpRgba, 0, rowBytes);
        System.arraycopy(topToBottomRgba, 0, bottomUpRgba, rowBytes, rowBytes);

        byte[] flippedPng = SaveManager.encodeRgbaToPng(width, height, bottomUpRgba, true);
        assertArrayEquals(topToBottomRgba, decodePngWithStb(flippedPng, width, height));
    }

    @Test
    void savesAndLoadsFullSlotWithCustomCompiledVJavaUserTypesAndStageSnapshot(@TempDir Path tempDir)
            throws Exception {
        // Compile real @->{ ... }<< classes with public final fields and parameterized constructors
        VJavaAst.JavaBlockNode defsBlock = new VJavaAst.JavaBlockNode(
                "blk_defs",
                "User item types",
                """
                public static class InventarItem {
                    public final String name;
                    public final int wert;

                    public InventarItem(String name, int wert) {
                        this.name = name;
                        this.wert = wert;
                    }
                }

                public static class SpezialGegenstand extends InventarItem {
                    public final String effekt;
                    public int ladungen = 3;

                    public SpezialGegenstand(String name, int wert, String effekt) {
                        super(name, wert);
                        this.effekt = effekt;
                    }
                }
                """,
                "main.vjava",
                1
        );
        VJavaAst.JavaBlockNode execBlock = new VJavaAst.JavaBlockNode(
                "blk_exec",
                "Create item",
                """
                SpezialGegenstand amulett = new SpezialGegenstand("Sternenkompass", 100, "Nachtsicht");
                amulett.ladungen = 1;
                state.set("aktives_item", amulett);
                state.set("spieler_name", "Kaito");
                state.set("mut_punkte", 35);
                """,
                "main.vjava",
                30
        );

        JavaBlockCompiler compiler = new JavaBlockCompiler();
        JavaBlockCompiler.CompiledProjectBlocks compiled = compiler.compile(List.of(defsBlock, execBlock));
        RevertableStateStore state = new RevertableStateStore();
        PersistentState persistent = new PersistentState();
        EngineAPI engine = new EngineAPI(EngineConfig.defaults());
        SceneController scene = new SceneController(new SceneGraph());

        compiled.requireExecutable("blk_defs").execute(state, persistent, engine, scene);
        compiled.requireExecutable("blk_exec").execute(state, persistent, engine, scene);

        // Add additional complex types (enum, POJO, list, map, array)
        state.set("route", RouteAffinity.ROOFTOP);
        state.set("pojo", new SamplePojo("Kapitel 1", 7, RouteAffinity.LIBRARY));
        state.set("badges", List.of("Mut", "Freundschaft"));
        state.set("flags", Map.of("hasMap", true, "day", 2));
        state.set("scores", new int[]{10, 25, 40});

        // Build stage snapshot containing Transform2D (with default NaN alignments and ColorMatrix4f)
        scene.setScene("bg_akademie_nacht", null);
        scene.showSprite("lena", List.of("happy"), "center", null);
        scene.getActiveSprite("lena").getTransform()
                .setRotate(5.0f)
                .setColorMatrix(ColorMatrix4f.saturation(0.5f))
                .setCrop(new Rect2f(0, 0, 500, 900));
        SceneController.StageSnapshot stageSnapshot = scene.snapshotState();

        // Generate PNG thumbnail via LWJGL STBImageWrite
        byte[] thumbRgba = new byte[]{
            (byte) 20, (byte) 40, (byte) 80, (byte) 255,
            (byte) 200, (byte) 100, (byte) 50, (byte) 255,
            (byte) 10, (byte) 220, (byte) 120, (byte) 255,
            (byte) 255, (byte) 255, (byte) 255, (byte) 255
        };
        byte[] thumbPng = SaveManager.encodeRgbaToPng(2, 2, thumbRgba);

        Instant savedAt = Instant.parse("2026-11-23T19:15:00Z");
        SaveMetadata metadata = new SaveMetadata(
                "slot_1",
                "Akademie Nacht",
                savedAt,
                "start",
                "Willkommen in der ersten Beispielszene, Kaito.",
                142.5,
                "1.0.0",
                2,
                2,
                thumbPng
        );
        SavedScriptPosition scriptPos = new SavedScriptPosition(
                "start",
                4,
                List.of(new SavedStackFrame("prologue", 2, true)),
                "WAITING_DIALOGUE",
                "lena",
                "Lena",
                "Willkommen in der ersten Beispielszene, Kaito.",
                false,
                List.of(),
                "assets/audio/voice/lena_01.ogg",
                stageSnapshot
        );
        List<HistoryLog.HistoryEntry> history = List.of(
                new HistoryLog.HistoryEntry(
                        1L,
                        1L,
                        "main.vjava:45:abcd",
                        "Lena",
                        "#58a6ff",
                        "Willkommen in der ersten Beispielszene, Kaito.",
                        "assets/audio/voice/lena_01.ogg",
                        savedAt
                )
        );
        SaveSlotData saveData = new SaveSlotData(metadata, state.snapshotMap(), scriptPos, history);

        Path savesDir = tempDir.resolve("saves");
        SaveManager saveManager = new SaveManager(savesDir);
        saveManager.saveSlot("slot_1", "Akademie Nacht", saveData);

        assertTrue(saveManager.slotExists("slot_1"));
        assertTrue(Files.isRegularFile(savesDir.resolve("slot_1.ksav")));

        // Metadata read works WITHOUT user ClassLoader
        Optional<SaveMetadata> metaOpt = saveManager.readSlotMetadata("slot_1");
        assertTrue(metaOpt.isPresent());
        SaveMetadata loadedMeta = metaOpt.get();
        assertEquals("slot_1", loadedMeta.slotId());
        assertEquals("Akademie Nacht", loadedMeta.customName());
        assertEquals(savedAt, loadedMeta.savedAt());
        assertEquals(2, loadedMeta.thumbnailWidth());
        assertEquals(2, loadedMeta.thumbnailHeight());
        assertArrayEquals(thumbRgba, decodePngWithStb(loadedMeta.thumbnailPng(), 2, 2));

        // Full load with compiled user ClassLoader reconstructs VJavaUserTypes cleanly
        SaveSlotData loaded = saveManager.loadSlot("slot_1", compiled.getClassLoader());
        assertEquals("Kaito", loaded.variables().get("spieler_name"));
        assertEquals(35, loaded.variables().get("mut_punkte"));
        assertEquals(RouteAffinity.ROOFTOP, loaded.variables().get("route"));
        assertEquals(List.of("Mut", "Freundschaft"), loaded.variables().get("badges"));
        assertEquals(Map.of("hasMap", true, "day", 2), loaded.variables().get("flags"));
        assertArrayEquals(new int[]{10, 25, 40}, (int[]) loaded.variables().get("scores"));

        SamplePojo loadedPojo = (SamplePojo) loaded.variables().get("pojo");
        assertEquals("Kapitel 1", loadedPojo.title);
        assertEquals(7, loadedPojo.level);
        assertEquals(RouteAffinity.LIBRARY, loadedPojo.affinity);

        Class<?> spezialClass = compiled.loadUserType("SpezialGegenstand");
        Object loadedItem = loaded.variables().get("aktives_item");
        assertNotNull(loadedItem);
        assertTrue(spezialClass.isInstance(loadedItem));
        assertEquals("Sternenkompass", spezialClass.getField("name").get(loadedItem));
        assertEquals(100, spezialClass.getField("wert").get(loadedItem));
        assertEquals("Nachtsicht", spezialClass.getField("effekt").get(loadedItem));
        assertEquals(1, spezialClass.getField("ladungen").get(loadedItem));

        // StageSnapshot & Transform2D losslessly restored
        SceneController.StageSnapshot restoredStage = loaded.scriptPosition().stageSnapshot();
        assertEquals("bg_akademie_nacht", restoredStage.backgroundTag());
        assertTrue(Float.isNaN(restoredStage.backgroundTransform().getXalign()));
        SceneController.StageEntry lenaEntry = restoredStage.activeSprites().get("lena");
        assertNotNull(lenaEntry);
        assertEquals(5.0f, lenaEntry.transform().getRotate(), 1e-5f);
        assertEquals(ColorMatrix4f.saturation(0.5f), lenaEntry.transform().getColorMatrix());
        assertEquals(new Rect2f(0, 0, 500, 900), lenaEntry.transform().getCrop());

        assertEquals(1, loaded.historyEntries().size());
        assertEquals("Lena", loaded.historyEntries().get(0).speakerName());
    }

    @Test
    void supportsPaginatedSlotsQuickSaveLoadAutoSaveRotationAndDeletion(@TempDir Path tempDir) throws Exception {
        SaveManager manager = new SaveManager(tempDir.resolve("saves"));

        SaveSlotData base = new SaveSlotData(
                new SaveMetadata(null, "Checkpoint", Instant.parse("2026-11-23T19:00:00Z"),
                        "dialog_zwei", "Preview", 50.0, "1.0.0", 0, 0, null),
                new LinkedHashMap<>(Map.of("chapter", 2)),
                new SavedScriptPosition("dialog_zwei", 1, List.of(), "WAITING_DIALOGUE",
                        "lena", "Lena", "Preview", false, List.of(), null, null),
                List.of()
        );

        // Paginated slots (1-based: slot_1..slot_6 on page 0, slot_7..slot_12 on page 1)
        assertEquals("slot_1", SaveManager.slotIdForPage(0, 0, 6));
        assertEquals("slot_7", SaveManager.slotIdForPage(1, 0, 6));

        manager.saveSlot("slot_1", "Page 0 Slot 1", base);
        manager.saveSlot("slot_5", "Page 0 Slot 5", base);
        manager.saveSlot("slot_8", "Page 1 Slot 2", base);

        List<SaveMetadata> page0 = manager.listPageSlots(0, 6);
        assertEquals(List.of("slot_1", "slot_5"), page0.stream().map(SaveMetadata::slotId).toList());
        List<SaveMetadata> page1 = manager.listPageSlots(1, 6);
        assertEquals(List.of("slot_8"), page1.stream().map(SaveMetadata::slotId).toList());

        // Quick-Save / Quick-Load
        manager.quickSave(base);
        assertTrue(manager.slotExists(SaveManager.QUICK_SAVE_SLOT));
        SaveSlotData quickLoaded = manager.quickLoad(null);
        assertEquals("quick", quickLoaded.metadata().slotId());
        assertEquals(2, quickLoaded.variables().get("chapter"));

        // Rotating Auto-Save: auto_1 -> auto_2 -> auto_3 -> auto_1
        assertEquals("auto_1", manager.autoSave(base));
        assertEquals("auto_2", manager.autoSave(base));
        assertEquals("auto_3", manager.autoSave(base));
        assertEquals("auto_1", manager.autoSave(base));

        // Deletion & validation
        assertTrue(manager.deleteSlot("slot_5"));
        assertFalse(manager.slotExists("slot_5"));
        assertFalse(manager.deleteSlot("slot_5"));
        assertThrows(IllegalArgumentException.class, () -> manager.slotExists("../escape"));
        assertThrows(IllegalArgumentException.class, () -> manager.slotExists("dir/slot"));
    }

    private static byte[] decodePngWithStb(byte[] pngBytes, int expectedWidth, int expectedHeight) {
        ByteBuffer raw = MemoryUtil.memAlloc(pngBytes.length);
        ByteBuffer decoded = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            raw.put(pngBytes).flip();
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer c = stack.mallocInt(1);
            decoded = STBImage.stbi_load_from_memory(raw, w, h, c, STBImage.STBI_rgb_alpha);
            assertNotNull(decoded, "STBImage failed to decode PNG bytes");
            assertEquals(expectedWidth, w.get(0));
            assertEquals(expectedHeight, h.get(0));
            byte[] rgba = new byte[expectedWidth * expectedHeight * 4];
            decoded.get(0, rgba);
            return rgba;
        } finally {
            if (decoded != null) {
                decoded.clear();
                STBImage.stbi_image_free(decoded);
            }
            MemoryUtil.memFree(raw);
        }
    }
}
