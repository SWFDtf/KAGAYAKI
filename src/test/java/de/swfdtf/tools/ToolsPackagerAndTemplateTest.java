package de.swfdtf.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.swfdtf.audio.AudioDecoder;
import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.VJavaAst;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit and integration tests for Milestone M7 Track A tools:
 * {@link ProjectTemplateGenerator}, {@link VJavaLinter}, {@link VpakArchiver}, and {@link StandaloneBuilder}.
 */
class ToolsPackagerAndTemplateTest {

    private static final List<String> EXPECTED_PNGS = List.of(
            "assets/images/bg/bg_akademie_nacht.png",
            "assets/images/bg/bg_bibliothek.png",
            "assets/images/char/lena_base.png",
            "assets/images/char/lena_uniform.png",
            "assets/images/char/lena_casual.png",
            "assets/images/char/lena_face_neutral.png",
            "assets/images/char/lena_face_happy.png",
            "assets/images/char/lena_face_determined.png",
            "assets/images/char/lena_mouth_closed.png",
            "assets/images/char/lena_mouth_half.png",
            "assets/images/char/lena_mouth_open.png",
            "assets/images/ui/dialog_frame.png"
    );

    private static final List<String> EXPECTED_WAVS = List.of(
            "assets/audio/nachtwind.wav",
            "assets/audio/voice/lena_01.wav"
    );

    @Test
    void generatesCompleteStarterTemplateAndPassesLinterCleanly(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("starter_vn");
        Path returned = ProjectTemplateGenerator.generate(projectDir, "Sternenkompass Chronik", "2D");
        assertEquals(projectDir.toAbsolutePath().normalize(), returned);

        assertTrue(Files.isRegularFile(projectDir.resolve(ProjectDescriptor.FILE_NAME)));
        assertTrue(Files.isRegularFile(projectDir.resolve("main.vjava")));
        assertTrue(Files.isRegularFile(projectDir.resolve("ui/screens.vjava")));
        assertTrue(Files.isRegularFile(projectDir.resolve("tl/english/translations.vjava")));

        ProjectDescriptor descriptor = ProjectDescriptor.load(projectDir);
        assertEquals("Sternenkompass Chronik", descriptor.getTitle());
        assertEquals("2D", descriptor.getRenderMode());
        assertEquals("start", descriptor.getEntryLabel());

        for (String pngRel : EXPECTED_PNGS) {
            Path pngPath = projectDir.resolve(pngRel);
            assertTrue(Files.isRegularFile(pngPath), "Missing generated PNG: " + pngRel);
            int[] size = new int[2];
            byte[] rgba = TextureManager.readImageFileRgba(pngPath, size);
            assertTrue(size[0] > 0 && size[1] > 0);
            assertEquals(size[0] * size[1] * 4, rgba.length);
        }

        for (String wavRel : EXPECTED_WAVS) {
            Path wavPath = projectDir.resolve(wavRel);
            assertTrue(Files.isRegularFile(wavPath), "Missing generated WAV: " + wavRel);
            AudioDecoder.DecodedAudio decoded = AudioDecoder.decode(wavPath);
            assertEquals(1, decoded.channels());
            assertTrue(decoded.sampleRate() > 0);
            assertTrue(decoded.frameCount() > 0);
        }

        VJavaLinter.LintReport report = VJavaLinter.lint(projectDir);
        assertTrue(report.isClean(), () -> "Expected clean lint report, got: " + report.issues());
        assertEquals(0, report.errorCount());
        assertEquals(0, report.warningCount());
        assertFalse(report.hasErrors());

        VJavaAst.ProjectAst ast = ProjectScanner.scan(projectDir);
        ScriptRuntime runtime = new ScriptRuntime(ast);
        runtime.start();
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        runtime.advance();
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        runtime.advance();
        assertEquals(ScriptRuntime.Status.WAITING_MENU, runtime.getStatus());
        runtime.choose(1);
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        runtime.advance();
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        runtime.advance();
        assertEquals(ScriptRuntime.Status.ENDED, runtime.getStatus());
    }

    @Test
    void linterDetectsBrokenTargetsMissingAssetsUndefinedSpeakersUnreachableLabelsAndBrokenJavaBlock(
            @TempDir Path tempDir
    ) throws Exception {
        ProjectDescriptor.save(tempDir, ProjectDescriptor.createDefault("Broken Project"));

        String brokenMain = """
                define lena = Character("Lena", color="#58a6ff")

                :: Kode für VN: Kaputter Java-Block @->{
                    int x = "not_an_int";
                }<<

                label start:
                    scene bg_nonexistent
                    play music "assets/audio/missing_bgm.wav"
                    voice "assets/audio/missing_voice.wav"
                    ghost "Wer hat mich definiert?"
                    lena "Hier geht es gleich falsch weiter."
                    menu:
                        "Fehlender Inline-Sprung" -> jump nonexistent_target
                        timeout 5.0 -> jump nonexistent_timeout
                    call missing_subroutine
                    jump still_missing

                label dead_end:
                    lena "Niemand springt hierher."
                    return
                """;
        Files.writeString(tempDir.resolve("main.vjava"), brokenMain, StandardCharsets.UTF_8);

        VJavaLinter.LintReport report = VJavaLinter.lint(tempDir);
        assertFalse(report.isClean());
        assertTrue(report.hasErrors());
        assertTrue(report.errorCount() >= 6);
        assertTrue(report.warningCount() >= 2);

        List<String> errorCodes = report.issues().stream()
                .filter(i -> i.severity() == VJavaLinter.Severity.ERROR)
                .map(VJavaLinter.LintIssue::code)
                .toList();
        List<String> warningCodes = report.issues().stream()
                .filter(i -> i.severity() == VJavaLinter.Severity.WARNING)
                .map(VJavaLinter.LintIssue::code)
                .toList();

        assertTrue(errorCodes.contains("MISSING_ASSET"));
        assertTrue(errorCodes.contains("MISSING_MENU_TARGET"));
        assertTrue(errorCodes.contains("MISSING_TIMEOUT_TARGET"));
        assertTrue(errorCodes.contains("MISSING_CALL_TARGET"));
        assertTrue(errorCodes.contains("MISSING_JUMP_TARGET"));
        assertTrue(errorCodes.contains("JAVA_COMPILE_ERROR"));

        assertTrue(warningCodes.contains("UNDEFINED_SPEAKER"));
        assertTrue(warningCodes.contains("UNREACHABLE_LABEL"));

        VJavaLinter.LintIssue javaErr = report.issues().stream()
                .filter(i -> "JAVA_COMPILE_ERROR".equals(i.code()))
                .findFirst()
                .orElseThrow();
        assertEquals("main.vjava", javaErr.sourceFile());
        assertTrue(javaErr.lineNumber() >= 3);
    }

    @Test
    void linterReportsMissingEntryLabel(@TempDir Path tempDir) throws Exception {
        ProjectDescriptor desc = ProjectDescriptor.createDefault("Missing Entry");
        desc.setEntryLabel("custom_intro");
        ProjectDescriptor.save(tempDir, desc);
        Files.writeString(tempDir.resolve("main.vjava"), """
                label other_label:
                    "Hallo"
                    return
                """, StandardCharsets.UTF_8);

        VJavaLinter.LintReport report = VJavaLinter.lint(tempDir);
        assertTrue(report.hasErrors());
        assertEquals(1, report.errorCount());
        assertTrue(report.issues().stream().anyMatch(i -> "MISSING_ENTRY_LABEL".equals(i.code())));
    }

    @Test
    void vpakArchiverPacksUnpacksAndDetectsCorruption(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("project");
        ProjectTemplateGenerator.generate(projectDir, "VPAK Demo", "2D");

        // Create files in excluded directories to ensure they are skipped
        Files.createDirectories(projectDir.resolve("saves"));
        Files.writeString(projectDir.resolve("saves/slot_01.json"), "{\"ignored\":true}", StandardCharsets.UTF_8);
        Files.createDirectories(projectDir.resolve(".git"));
        Files.writeString(projectDir.resolve(".git/HEAD"), "ref: refs/heads/main", StandardCharsets.UTF_8);
        Files.createDirectories(projectDir.resolve("build"));
        Files.writeString(projectDir.resolve("build/tmp.txt"), "ignored", StandardCharsets.UTF_8);
        Files.createDirectories(projectDir.resolve("dist"));
        Files.writeString(projectDir.resolve("dist/old.vpak"), "ignored", StandardCharsets.UTF_8);

        Path vpakFile = tempDir.resolve("out/game.vpak");
        List<VpakArchiver.VpakEntryInfo> packed = VpakArchiver.pack(projectDir, vpakFile);
        assertTrue(packed.size() >= 18);

        try (VpakArchiver.VpakArchive archive = VpakArchiver.open(vpakFile)) {
            assertTrue(archive.contains("project.vjava.json"));
            assertTrue(archive.contains("main.vjava"));
            assertTrue(archive.contains("ui/screens.vjava"));
            assertTrue(archive.contains("tl/english/translations.vjava"));
            assertTrue(archive.contains("assets/audio/nachtwind.wav"));
            assertFalse(archive.contains("saves/slot_01.json"));
            assertFalse(archive.contains(".git/HEAD"));
            assertFalse(archive.contains("build/tmp.txt"));
            assertFalse(archive.contains("dist/old.vpak"));

            byte[] mainFromVpak = archive.readEntry("main.vjava");
            byte[] mainOriginal = Files.readAllBytes(projectDir.resolve("main.vjava"));
            assertArrayEquals(mainOriginal, mainFromVpak);

            Path extractedDir = tempDir.resolve("extracted");
            archive.extractAll(extractedDir);
            assertArrayEquals(mainOriginal, Files.readAllBytes(extractedDir.resolve("main.vjava")));
            assertArrayEquals(
                    Files.readAllBytes(projectDir.resolve("assets/images/bg/bg_akademie_nacht.png")),
                    Files.readAllBytes(extractedDir.resolve("assets/images/bg/bg_akademie_nacht.png"))
            );
        }

        // Corrupt a compressed payload byte inside the archive and verify readEntry rejects it
        Path corruptedVpak = tempDir.resolve("out/corrupted_payload.vpak");
        Files.copy(vpakFile, corruptedVpak);
        try (FileChannel ch = FileChannel.open(corruptedVpak, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer one = ByteBuffer.allocate(1);
            ch.read(one, 25);
            one.flip();
            byte flipped = (byte) (one.get(0) ^ 0xFF);
            ch.write(ByteBuffer.wrap(new byte[]{flipped}), 25);
        }

        try (VpakArchiver.VpakArchive corruptArchive = VpakArchiver.open(corruptedVpak)) {
            String firstEntry = corruptArchive.listEntries().get(0).path();
            assertThrows(IOException.class, () -> corruptArchive.readEntry(firstEntry));
        }

        // Corrupt the stored CRC32 in the TOC of the first entry and verify explicit CRC32 check catches it
        Path corruptedCrcVpak = tempDir.resolve("out/corrupted_crc.vpak");
        Files.copy(vpakFile, corruptedCrcVpak);
        try (FileChannel ch = FileChannel.open(corruptedCrcVpak, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer headerBuf = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
            ch.read(headerBuf, 0);
            headerBuf.flip();
            long tocOffset = headerBuf.getLong(12);

            ByteBuffer pathLenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
            ch.read(pathLenBuf, tocOffset);
            pathLenBuf.flip();
            int pathLen = pathLenBuf.getInt(0);

            long crcOffset = tocOffset + 4 + pathLen + 24;
            ByteBuffer crcBuf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
            ch.read(crcBuf, crcOffset);
            crcBuf.flip();
            long originalCrc = crcBuf.getLong(0);

            ByteBuffer badCrcBuf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
            badCrcBuf.putLong(originalCrc ^ 0x12345678L);
            badCrcBuf.flip();
            ch.write(badCrcBuf, crcOffset);
        }

        try (VpakArchiver.VpakArchive crcArchive = VpakArchiver.open(corruptedCrcVpak)) {
            String firstEntry = crcArchive.listEntries().get(0).path();
            IOException ex = assertThrows(IOException.class, () -> crcArchive.readEntry(firstEntry));
            assertTrue(ex.getMessage().contains("CRC32"));
        }
    }

    @Test
    void standaloneBuilderExportsCleanBundleWithAndWithoutJar(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("vn_project");
        ProjectTemplateGenerator.generate(projectDir, "Standalone Test", "2D");

        Path outWithoutJar = tempDir.resolve("export_no_jar");
        StandaloneBuilder.BuildResult res1 = StandaloneBuilder.build(projectDir, outWithoutJar, null);

        assertEquals(outWithoutJar.toAbsolutePath().normalize(), res1.outputDir());
        assertTrue(Files.isRegularFile(res1.vpakFile()));
        assertNull(res1.standaloneJar());
        assertTrue(Files.isRegularFile(outWithoutJar.resolve("STANDALONE_RUNTIME_README.txt")));
        assertTrue(Files.isRegularFile(res1.launcherScript()));
        assertTrue(Files.isRegularFile(outWithoutJar.resolve("run_game.bat")));
        assertTrue(Files.isRegularFile(res1.packagingDescriptor()));
        assertTrue(res1.packagedEntries() >= 18);

        String shContent = Files.readString(res1.launcherScript(), StandardCharsets.UTF_8);
        assertTrue(shContent.contains("-XstartOnFirstThread"));
        assertTrue(shContent.contains("de.swfdtf.StandaloneMain --standalone ."));

        String batContent = Files.readString(outWithoutJar.resolve("run_game.bat"), StandardCharsets.UTF_8);
        assertTrue(batContent.contains("de.swfdtf.StandaloneMain --standalone ."));

        JsonObject pkgJson = JsonParser.parseString(
                Files.readString(res1.packagingDescriptor(), StandardCharsets.UTF_8)
        ).getAsJsonObject();
        assertEquals("de.swfdtf.StandaloneMain", pkgJson.get("mainClass").getAsString());
        assertEquals("packageStandalone", pkgJson.get("gradleTask").getAsString());
        assertFalse(pkgJson.get("standaloneJarBundled").getAsBoolean());
        assertTrue(pkgJson.has("jpackage"));
        assertTrue(pkgJson.has("jlink"));

        // Export with a prebuilt standalone JAR present
        Path dummyJar = tempDir.resolve("kagayaki-standalone-1.0.0.jar");
        byte[] dummyJarBytes = "PK\u0003\u0004dummy-jar".getBytes(StandardCharsets.UTF_8);
        Files.write(dummyJar, dummyJarBytes);

        Path outWithJar = tempDir.resolve("export_with_jar");
        StandaloneBuilder.BuildResult res2 = StandaloneBuilder.build(projectDir, outWithJar, dummyJar);
        assertNotNull(res2.standaloneJar());
        assertTrue(Files.isRegularFile(res2.standaloneJar()));
        assertArrayEquals(dummyJarBytes, Files.readAllBytes(res2.standaloneJar()));
    }

    @Test
    void standaloneBuilderRefusesToExportProjectWithLintErrors(@TempDir Path tempDir) throws Exception {
        Path brokenProject = tempDir.resolve("broken");
        ProjectDescriptor.save(brokenProject, ProjectDescriptor.createDefault("Broken"));
        Files.writeString(brokenProject.resolve("main.vjava"), """
                label start:
                    jump nowhere
                """, StandardCharsets.UTF_8);

        assertThrows(
                IllegalStateException.class,
                () -> StandaloneBuilder.build(brokenProject, tempDir.resolve("export_fail"), null)
        );
    }
}
