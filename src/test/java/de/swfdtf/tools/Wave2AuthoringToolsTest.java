package de.swfdtf.tools;

import de.swfdtf.audio.AudioDecoder;
import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.parser.VJavaLexer;
import de.swfdtf.parser.VJavaParser;
import de.swfdtf.state.SaveSlotData;
import de.swfdtf.state.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Comprehensive unit and integration tests for Wave 2 authoring tools:
 * {@link VJavaScriptFormatter}, {@link StateSnapshotDiffService}, {@link AssetMetadataInspector},
 * {@link LabelBookmarkManager}, and {@link VJavaLinterAutoFixer}.
 */
@ExtendWith(MockitoExtension.class)
class Wave2AuthoringToolsTest {

    @Test
    void formatterNormalizesVnLinesWhilePreservingJavaBlocksVerbatim() {
        VJavaScriptFormatter formatter = new VJavaScriptFormatter();

        String raw = "\uFEFF"
                + "define lena = Character(\"Lena\")   \r\n"
                + "\r\n\r\n\r\n\r\n"
                + "label start:\t  \r\n"
                + "\tlena \"Hello!\"   \r\n"
                + "\t:: Kode für VN: Complex Java @->{\r\n"
                + "\t\tint keepTabs = 1;   \r\n"
                + "\r\n\r\n\r\n"
                + "\t\tString marker = \"}<<\";\r\n"
                + "\t}<<   \t\r\n"
                + "\r\n\r\n\r\n"
                + "\tif true:\t\r\n"
                + " \t\treturn   \r\n\r\n\r\n";

        String formatted = formatter.format(raw);

        String expected = ""
                + "define lena = Character(\"Lena\")\n"
                + "\n\n"
                + "label start:\n"
                + "    lena \"Hello!\"\n"
                + "    :: Kode für VN: Complex Java @->{\n"
                + "\t\tint keepTabs = 1;   \n"
                + "\n\n\n"
                + "\t\tString marker = \"}<<\";\n"
                + "\t}<<\n"
                + "\n\n"
                + "    if true:\n"
                + "         return\n";

        assertEquals(expected, formatted);
        assertEquals(formatted, formatter.format(formatted));
        assertFalse(formatted.contains("\r"));
        assertTrue(formatted.endsWith("\n"));
        assertFalse(formatted.endsWith("\n\n"));

        // Verify formatted script remains cleanly lexable and parseable.
        List<VJavaLexer.Token> tokens = VJavaLexer.tokenize(formatted, "formatted.vjava");
        assertNotNull(VJavaParser.parse(tokens));
    }

    @Test
    void formatterHandlesEmptyBlankSingleTrailingNewlineAndFileRewrites(@TempDir Path tempDir) throws Exception {
        VJavaScriptFormatter formatter = new VJavaScriptFormatter();

        assertEquals("", formatter.format(""));
        assertEquals("", formatter.format("   \t \r\n\r\n  "));
        assertEquals("label start:\n    return\n", formatter.format("label start:\n\treturn"));
        assertThrows(NullPointerException.class, () -> formatter.format(null));
        assertThrows(NullPointerException.class, () -> formatter.formatFile(null));

        Path missingFile = tempDir.resolve("missing.vjava");
        assertThrows(IOException.class, () -> formatter.formatFile(missingFile));

        Path scriptFile = tempDir.resolve("scene.vjava");
        Files.writeString(scriptFile, "label start:\r\n\t\"Hi\"   \r\n\treturn", StandardCharsets.UTF_8);

        assertTrue(formatter.formatFile(scriptFile));
        assertEquals("label start:\n    \"Hi\"\n    return\n", Files.readString(scriptFile, StandardCharsets.UTF_8));
        assertFalse(formatter.formatFile(scriptFile));
    }

    @Test
    void stateDiffServiceDetectsAddedRemovedModifiedWithDeepEqualityAndSummary() {
        StateSnapshotDiffService service = new StateSnapshotDiffService();

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("unchanged_int", 42);
        before.put("unchanged_arr", new int[]{1, 2, 3});
        before.put("unchanged_map", Map.of("nested", List.of("a", "b")));
        before.put("score", 10);
        before.put("nullable", "present");
        before.put("removed_flag", true);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("unchanged_int", 42);
        after.put("unchanged_arr", new int[]{1, 2, 3});
        after.put("unchanged_map", Map.of("nested", List.of("a", "b")));
        after.put("score", 25);
        after.put("nullable", null);
        after.put("added_item", "Key");

        StateSnapshotDiffService.StateDiff diff = service.diff(before, after);

        assertFalse(diff.isEmpty());
        assertEquals(4, diff.totalChanges());
        assertEquals(Map.of("added_item", "Key"), diff.added());
        assertEquals(Map.of("removed_flag", true), diff.removed());
        assertEquals(2, diff.modified().size());
        assertEquals(new StateSnapshotDiffService.ValueChange("score", 10, 25), diff.modified().get("score"));
        assertEquals(
                new StateSnapshotDiffService.ValueChange("nullable", "present", null),
                diff.modified().get("nullable"));

        assertThrows(UnsupportedOperationException.class, () -> diff.added().put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> diff.removed().put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> diff.modified().clear());

        String summary = diff.formatSummary();
        assertTrue(summary.contains("4 total"));
        assertTrue(summary.contains("+ added_item = Key"));
        assertTrue(summary.contains("- removed_flag = true"));
        assertTrue(summary.contains("~ score: 10 -> 25"));
        assertTrue(summary.contains("~ nullable: present -> null"));

        StateSnapshotDiffService.StateDiff emptyDiff = service.diff(before, new LinkedHashMap<>(before));
        assertTrue(emptyDiff.isEmpty());
        assertEquals(0, emptyDiff.totalChanges());
        assertTrue(emptyDiff.formatSummary().contains("0 total"));
    }

    @Test
    void stateDiffServiceComparesSaveSlotDataAndMockedStateStores() {
        StateSnapshotDiffService service = new StateSnapshotDiffService();

        SaveSlotData.SaveMetadata metaA = new SaveSlotData.SaveMetadata(
                "slot_1", "Slot 1", Instant.parse("2026-01-01T00:00:00Z"),
                "start", "Hello", 12.0, "1.0.0", 0, 0, null
        );
        SaveSlotData.SavedScriptPosition pos = new SaveSlotData.SavedScriptPosition(
                "start", 1, List.of(), "WAITING_DIALOGUE", "lena", "Lena", "Hi",
                false, List.of(), null, new SceneController.StageSnapshot(null, null, null, Map.of())
        );

        SaveSlotData slotBefore = new SaveSlotData(metaA, Map.of("affinity", 1, "chapter", 1), pos, List.of());
        SaveSlotData slotAfter = new SaveSlotData(
                metaA, Map.of("affinity", 3, "chapter", 1, "unlocked", true), pos, List.of());

        StateSnapshotDiffService.StateDiff slotDiff = service.diff(slotBefore, slotAfter);
        assertEquals(2, slotDiff.totalChanges());
        assertEquals(Map.of("unlocked", true), slotDiff.added());
        assertEquals(3, slotDiff.modified().get("affinity").afterValue());

        StateStore mockBefore = mock(StateStore.class);
        StateStore mockAfter = mock(StateStore.class);
        when(mockBefore.snapshotMap()).thenReturn(Map.of("hp", 100));
        when(mockAfter.snapshotMap()).thenReturn(Map.of("hp", 80));

        StateSnapshotDiffService.StateDiff mockDiff = service.diff(mockBefore.snapshotMap(), mockAfter.snapshotMap());
        assertEquals(1, mockDiff.totalChanges());
        assertEquals(80, mockDiff.modified().get("hp").afterValue());
        verify(mockBefore).snapshotMap();
        verify(mockAfter).snapshotMap();

        assertThrows(NullPointerException.class, () -> service.diff((Map<String, Object>) null, Map.of()));
        assertThrows(NullPointerException.class, () -> service.diff(Map.of(), (Map<String, Object>) null));
        assertThrows(NullPointerException.class, () -> service.diff((SaveSlotData) null, slotAfter));
        assertThrows(NullPointerException.class, () -> service.diff(slotBefore, (SaveSlotData) null));
    }

    @Test
    void assetMetadataInspectorParsesRealTemplatePngAndWavAssetsAndOtherTypes(@TempDir Path projectDir)
            throws Exception {
        ProjectTemplateGenerator.generate(projectDir, "InspectDemo", "2D");

        // Add font, video, other, and a custom stereo WAV with an extra LIST chunk before data
        Path fontFile = projectDir.resolve("assets/fonts/ui.ttf");
        Path otfFile = projectDir.resolve("assets/fonts/title.otf");
        Path mp4File = projectDir.resolve("assets/video/intro.mp4");
        Path webmFile = projectDir.resolve("assets/video/credits.webm");
        Path ogvFile = projectDir.resolve("assets/video/cutscene.ogv");
        Path textFile = projectDir.resolve("assets/readme.txt");
        Path stereoWavFile = projectDir.resolve("assets/audio/stereo_custom.wav");
        Path corruptPng = projectDir.resolve("assets/images/broken.png");

        Files.createDirectories(fontFile.getParent());
        Files.createDirectories(mp4File.getParent());
        Files.write(fontFile, new byte[]{0x00, 0x01, 0x00, 0x00});
        Files.write(otfFile, "OTTO".getBytes(StandardCharsets.US_ASCII));
        Files.write(mp4File, new byte[]{1, 2, 3, 4, 5});
        Files.write(webmFile, new byte[]{1, 2, 3});
        Files.write(ogvFile, new byte[]{1, 2});
        Files.writeString(textFile, "notes", StandardCharsets.UTF_8);
        Files.write(stereoWavFile, buildStereoWavWithExtraChunk(44_100, 2, 44_100));
        Files.writeString(corruptPng, "not-really-a-png", StandardCharsets.UTF_8);

        AssetMetadataInspector inspector = new AssetMetadataInspector();

        Optional<AssetMetadataInspector.AssetMetadata> bgMeta =
                inspector.inspectAsset(projectDir, "assets/images/bg/bg_akademie_nacht.png");
        assertTrue(bgMeta.isPresent());
        assertEquals(AssetMetadataInspector.MediaType.IMAGE, bgMeta.get().mediaType());
        assertEquals(Optional.of(640), bgMeta.get().widthPx());
        assertEquals(Optional.of(360), bgMeta.get().heightPx());
        assertTrue(bgMeta.get().byteSize() > 0);
        assertTrue(bgMeta.get().sampleRateHz().isEmpty());

        Optional<AssetMetadataInspector.AssetMetadata> wavMeta =
                inspector.inspectAsset(projectDir, "assets/audio/nachtwind.wav");
        assertTrue(wavMeta.isPresent());
        assertEquals(AssetMetadataInspector.MediaType.AUDIO, wavMeta.get().mediaType());
        assertEquals(Optional.of(22_050), wavMeta.get().sampleRateHz());
        assertEquals(Optional.of(1), wavMeta.get().channels());
        assertTrue(wavMeta.get().durationSeconds().isPresent());
        assertEquals(1.2, wavMeta.get().durationSeconds().get(), 0.01);

        Optional<AssetMetadataInspector.AssetMetadata> stereoMeta =
                inspector.inspectAsset(projectDir, "assets/audio/stereo_custom.wav");
        assertTrue(stereoMeta.isPresent());
        assertEquals(Optional.of(44_100), stereoMeta.get().sampleRateHz());
        assertEquals(Optional.of(2), stereoMeta.get().channels());
        assertEquals(1.0, stereoMeta.get().durationSeconds().orElseThrow(), 1e-6);

        assertEquals(AssetMetadataInspector.MediaType.FONT,
                inspector.inspectAsset(projectDir, "assets/fonts/ui.ttf").orElseThrow().mediaType());
        assertEquals(AssetMetadataInspector.MediaType.FONT,
                inspector.inspectAsset(projectDir, "assets/fonts/title.otf").orElseThrow().mediaType());
        assertEquals(AssetMetadataInspector.MediaType.VIDEO,
                inspector.inspectAsset(projectDir, "assets/video/intro.mp4").orElseThrow().mediaType());
        assertEquals(AssetMetadataInspector.MediaType.VIDEO,
                inspector.inspectAsset(projectDir, "assets/video/credits.webm").orElseThrow().mediaType());
        assertEquals(AssetMetadataInspector.MediaType.VIDEO,
                inspector.inspectAsset(projectDir, "assets/video/cutscene.ogv").orElseThrow().mediaType());
        assertEquals(AssetMetadataInspector.MediaType.OTHER,
                inspector.inspectAsset(projectDir, "assets/readme.txt").orElseThrow().mediaType());

        AssetMetadataInspector.AssetMetadata brokenMeta =
                inspector.inspectAsset(projectDir, "assets/images/broken.png").orElseThrow();
        assertEquals(AssetMetadataInspector.MediaType.IMAGE, brokenMeta.mediaType());
        assertTrue(brokenMeta.widthPx().isEmpty());
        assertTrue(brokenMeta.heightPx().isEmpty());

        List<AssetMetadataInspector.AssetMetadata> all = inspector.inspectAll(projectDir);
        assertEquals(23, all.size());
        assertThrows(UnsupportedOperationException.class, all::clear);
        for (int i = 1; i < all.size(); i++) {
            assertTrue(all.get(i - 1).relativePath().compareTo(all.get(i).relativePath()) <= 0);
        }

        assertTrue(inspector.inspectAsset(projectDir, "assets/does_not_exist.png").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> inspector.inspectAsset(projectDir, "../outside.png"));
        assertThrows(IllegalArgumentException.class, () -> inspector.inspectAsset(projectDir, "   "));
    }

    @Test
    void assetMetadataInspectorHandlesMissingAssetsAndInvalidRoots(@TempDir Path tempDir) throws Exception {
        AssetMetadataInspector inspector = new AssetMetadataInspector();
        assertTrue(inspector.inspectAll(tempDir).isEmpty());

        Path missingDir = tempDir.resolve("nope");
        assertThrows(IOException.class, () -> inspector.inspectAll(missingDir));
        assertThrows(IOException.class, () -> inspector.inspectAsset(missingDir, "assets/a.png"));
        assertThrows(NullPointerException.class, () -> inspector.inspectAll(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void labelBookmarkManagerManagesBookmarksAndRoundTripsAtomicJson(@TempDir Path tempDir) throws Exception {
        LabelBookmarkManager manager = new LabelBookmarkManager();
        LabelBookmarkManager.LabelBookmark b1 = manager.addOrUpdate("start", "story", "Opening scene");
        LabelBookmarkManager.LabelBookmark b2 = manager.addOrUpdate("ending_true", "Ending", "Final route");
        LabelBookmarkManager.LabelBookmark b3 = manager.addOrUpdate("secret_room", "STORY", "Hidden branch");

        assertEquals("start", b1.label());
        assertEquals("Ending", b2.category());
        assertNotNull(b3.createdAt());

        // Update existing bookmark
        LabelBookmarkManager.LabelBookmark updated = manager.addOrUpdate("start", "story", "Updated opening");
        assertEquals("Updated opening", updated.note());
        assertEquals(3, manager.listAll().size());
        assertEquals("Updated opening", manager.find("start").orElseThrow().note());
        assertTrue(manager.find("nonexistent").isEmpty());

        List<LabelBookmarkManager.LabelBookmark> storyBookmarks = manager.filterByCategory("story");
        assertEquals(2, storyBookmarks.size());
        assertThrows(UnsupportedOperationException.class, storyBookmarks::clear);
        assertThrows(UnsupportedOperationException.class, () -> manager.listAll().clear());

        Path saveFile = tempDir.resolve("nested/authoring/bookmarks.json");
        manager.save(saveFile);
        assertTrue(Files.isRegularFile(saveFile));

        LabelBookmarkManager loaded = LabelBookmarkManager.load(saveFile);
        assertEquals(manager.listAll(), loaded.listAll());
        assertTrue(loaded.remove("secret_room"));
        assertFalse(loaded.remove("secret_room"));
        assertEquals(2, loaded.listAll().size());

        // Mockito callback verification example
        Consumer<LabelBookmarkManager.LabelBookmark> onSelect = mock(Consumer.class);
        loaded.find("ending_true").ifPresent(onSelect);
        verify(onSelect).accept(b2);

        assertThrows(NoSuchFileException.class, () -> LabelBookmarkManager.load(tempDir.resolve("missing.json")));
        Path badJson = tempDir.resolve("bad.json");
        Files.writeString(badJson, "{invalid", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> LabelBookmarkManager.load(badJson));
        assertThrows(IllegalArgumentException.class, () -> manager.addOrUpdate("  ", "story", "x"));
        assertThrows(IllegalArgumentException.class, () -> manager.addOrUpdate("lbl", "  ", "x"));
        assertThrows(NullPointerException.class, () -> manager.addOrUpdate("lbl", "story", null));
    }

    @Test
    void linterAutoFixerFixesSpeakersAndMissingJumpCallTargetsIdempotently(@TempDir Path projectDir) throws Exception {
        ProjectDescriptor descriptor = ProjectDescriptor.createDefault("AutoFixTest");
        ProjectDescriptor.save(projectDir, descriptor);

        String initialMain = """
                label start:
                    alice "Hello!"
                    alice "Nice to meet you again!"
                    bob "Let's go."
                    call helper_sub
                    jump missing_ending
                    jump missing_ending
                """;
        String secondaryScript = """
                translate english start_01:
                    charlie "Translated line"
                """;

        Files.writeString(projectDir.resolve("main.vjava"), initialMain, StandardCharsets.UTF_8);
        Files.createDirectories(projectDir.resolve("tl/english"));
        Files.writeString(projectDir.resolve("tl/english/lines.vjava"), secondaryScript, StandardCharsets.UTF_8);

        VJavaLinter.LintReport before = VJavaLinter.lint(projectDir);
        assertTrue(before.hasErrors());
        assertFalse(before.isClean());

        VJavaLinterAutoFixer fixer = new VJavaLinterAutoFixer();
        VJavaLinterAutoFixer.AutoFixResult firstRun = fixer.applySafeFixes(projectDir);

        // 3 unique speakers (alice, bob, charlie) + 2 unique labels (helper_sub, missing_ending)
        assertEquals(5, firstRun.appliedCount());
        assertEquals(5, firstRun.descriptions().size());
        assertThrows(UnsupportedOperationException.class, () -> firstRun.descriptions().clear());

        String updatedMain = Files.readString(projectDir.resolve("main.vjava"), StandardCharsets.UTF_8);
        assertTrue(updatedMain.contains("define alice = Character(\"Alice\")"));
        assertTrue(updatedMain.contains("define bob = Character(\"Bob\")"));
        assertTrue(updatedMain.contains("define charlie = Character(\"Charlie\")"));
        assertTrue(updatedMain.contains(
                "label helper_sub:\n    # Stub label generated by VJavaLinterAutoFixer\n    return"));
        assertTrue(updatedMain.contains(
                "label missing_ending:\n    # Stub label generated by VJavaLinterAutoFixer\n    return"));
        assertFalse(updatedMain.toUpperCase().contains("TODO"));

        VJavaLinter.LintReport after = VJavaLinter.lint(projectDir);
        assertTrue(after.isClean(), "Expected lint report to be clean after auto-fix, got: " + after.issues());

        // Second run must be completely idempotent.
        VJavaLinterAutoFixer.AutoFixResult secondRun = fixer.applySafeFixes(projectDir);
        assertEquals(0, secondRun.appliedCount());
        assertTrue(secondRun.descriptions().isEmpty());
        assertEquals(updatedMain, Files.readString(projectDir.resolve("main.vjava"), StandardCharsets.UTF_8));
    }

    @Test
    void linterAutoFixerHandlesCleanTemplateProjectAndNonMainEntryFile(@TempDir Path tempDir) throws Exception {
        Path cleanProject = tempDir.resolve("clean");
        ProjectTemplateGenerator.generate(cleanProject, "Clean", "2D");
        VJavaLinterAutoFixer fixer = new VJavaLinterAutoFixer();

        VJavaLinterAutoFixer.AutoFixResult cleanResult = fixer.applySafeFixes(cleanProject);
        assertEquals(0, cleanResult.appliedCount());
        assertTrue(cleanResult.descriptions().isEmpty());

        // Non-main entry file project
        Path customProject = tempDir.resolve("custom");
        Files.createDirectories(customProject);
        ProjectDescriptor desc = ProjectDescriptor.createDefault("CustomEntry");
        desc.setEntryLabel("intro");
        ProjectDescriptor.save(customProject, desc);
        Files.writeString(customProject.resolve("chapter1.vjava"), """
                label intro:
                    narrator "Welcome."
                    jump outro
                """, StandardCharsets.UTF_8);

        VJavaLinterAutoFixer.AutoFixResult customResult = fixer.applySafeFixes(customProject);
        assertEquals(2, customResult.appliedCount());
        String chapterText = Files.readString(customProject.resolve("chapter1.vjava"), StandardCharsets.UTF_8);
        assertTrue(chapterText.contains("define narrator = Character(\"Narrator\")"));
        assertTrue(chapterText.contains("label outro:"));
        assertTrue(VJavaLinter.lint(customProject).isClean());

        assertThrows(NullPointerException.class, () -> fixer.applySafeFixes(null));
        assertThrows(IOException.class, () -> fixer.applySafeFixes(tempDir.resolve("missing_dir")));
    }

    private static byte[] buildStereoWavWithExtraChunk(int sampleRate, int channels, int frames) {
        short[] pcm = new short[frames * channels];
        byte[] standardWav = AudioDecoder.encodeWav(pcm, channels, sampleRate);
        // Extract raw data bytes from standard 44-byte WAV
        int dataSize = standardWav.length - 44;
        byte[] listPayload = "INFO!".getBytes(StandardCharsets.US_ASCII); // odd size = 5 (requires 1 pad byte)
        int listChunkTotal = 8 + listPayload.length + 1;
        int totalRiffSize = 4 + (8 + 16) + listChunkTotal + (8 + dataSize);

        ByteBuffer buf = ByteBuffer.allocate(8 + totalRiffSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(totalRiffSize);
        buf.put("WAVE".getBytes(StandardCharsets.US_ASCII));

        // Copy fmt chunk (24 bytes starting at offset 12 in standardWav)
        buf.put(standardWav, 12, 24);

        // Insert odd-sized LIST chunk to exercise chunk skipping & word-alignment padding
        buf.put("LIST".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(listPayload.length);
        buf.put(listPayload);
        buf.put((byte) 0);

        // Copy data chunk header + payload
        buf.put("data".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(dataSize);
        buf.put(standardWav, 44, dataSize);
        return buf.array();
    }
}
