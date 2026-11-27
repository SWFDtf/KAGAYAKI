package de.swfdtf.tools;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineVersion;
import de.swfdtf.tools.GameVfsAndModServices.GameVfs;
import de.swfdtf.tools.GameVfsAndModServices.ModManager;
import de.swfdtf.tools.GameVfsAndModServices.ModManager.ModDescriptor;
import de.swfdtf.tools.GameVfsAndModServices.PluginHotReloader;
import de.swfdtf.tools.PackagingAndDistributionServices.ArchiveSecurityAndPerfUtils;
import de.swfdtf.tools.PackagingAndDistributionServices.ArchiveSecurityAndPerfUtils.DeltaPatchBundle;
import de.swfdtf.tools.PackagingAndDistributionServices.LauncherAndComplianceGenerator;
import de.swfdtf.tools.PackagingAndDistributionServices.PlatformTarget;
import de.swfdtf.tools.PackagingAndDistributionServices.PortabilityAndReleaseValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests covering Chapter 10 GameVfs layering &amp; traversal security, ModManager
 * conflicts/cycles/quarantine, URLClassLoader hot-reload, deterministic VPAK packing, Ed25519
 * verification, ZIP-bomb guards, compliance files, portability audits, `build.gradle` sync,
 * and full template-&gt;export-&gt;delete-source-&gt;boot E2E (Wave P2-10).
 */
class WaveP2Chapter10PackagingVfsAndModsTest {

    @Test
    void testGameVfsLayeringDlcMountAndSecurityGuards(@TempDir Path tempDir) throws IOException {
        Path baseDir = tempDir.resolve("base");
        Path dlcSrc = tempDir.resolve("dlc_src");
        Path mod1 = tempDir.resolve("mods").resolve("m1");
        Path mod2 = tempDir.resolve("mods").resolve("m2");
        Files.createDirectories(baseDir.resolve("assets"));
        Files.createDirectories(dlcSrc.resolve("assets"));
        Files.createDirectories(mod1.resolve("assets"));
        Files.createDirectories(mod2.resolve("assets"));

        Files.writeString(baseDir.resolve("assets/bg.txt"), "base", StandardCharsets.UTF_8);
        Files.writeString(baseDir.resolve("assets/only_base.txt"), "orig", StandardCharsets.UTF_8);
        Files.writeString(dlcSrc.resolve("project.vjava.json"), "{\"title\":\"DLC\"}", StandardCharsets.UTF_8);
        Files.writeString(dlcSrc.resolve("assets/dlc.txt"), "dlc_data", StandardCharsets.UTF_8);
        Path dlcVpak = tempDir.resolve("dlc_01.vpak");
        VpakArchiver.pack(dlcSrc, dlcVpak);

        Files.writeString(mod1.resolve("assets/bg.txt"), "mod1", StandardCharsets.UTF_8);
        Files.writeString(mod2.resolve("assets/bg.txt"), "mod2", StandardCharsets.UTF_8);

        GameVfs vfs = new GameVfs();
        vfs.registerClasspathDefault("defaults/fallback.txt", "cp".getBytes(StandardCharsets.UTF_8));
        vfs.mountBaseDirectory(baseDir);
        vfs.mountModDirectory(mod1);
        vfs.mountModDirectory(mod2);
        try (VpakArchiver.VpakArchive dlc = VpakArchiver.open(dlcVpak)) {
            vfs.mountAdditionalVpak(dlc);
            assertEquals("mod2", new String(vfs.readBytes("assets/bg.txt").orElseThrow(), StandardCharsets.UTF_8));
            assertEquals("dlc_data",
                    new String(vfs.readBytes("assets/dlc.txt").orElseThrow(), StandardCharsets.UTF_8));
            assertEquals("orig",
                    new String(vfs.readBytes("assets/only_base.txt").orElseThrow(), StandardCharsets.UTF_8));
            assertEquals("cp",
                    new String(vfs.readBytes("defaults/fallback.txt").orElseThrow(), StandardCharsets.UTF_8));
            assertThrows(SecurityException.class, () -> vfs.readBytes("../../etc/passwd"));
        }
    }

    @Test
    void testModManagerConflictsCyclesQuarantineStarterModAndHotReload(@TempDir Path tempDir)
            throws IOException {
        ModManager manager = new ModManager();
        ModDescriptor coreMod = new ModDescriptor("core_mod", true, List.of(), List.of("assets/ui.png"));
        ModDescriptor themeMod = new ModDescriptor("theme_mod", true, List.of("core_mod"), List.of("assets/ui.png"));
        List<ModDescriptor> order = manager.resolveLoadOrder(List.of(themeMod, coreMod));
        assertEquals("core_mod", order.get(0).id());
        assertEquals("theme_mod", order.get(1).id());

        var conflicts = ModManager.detectFileConflicts(order);
        assertEquals(1, conflicts.size());
        assertEquals("assets/ui.png", conflicts.get(0).filePath());

        manager.markModCrashQuarantined("theme_mod");
        assertTrue(manager.isQuarantined("theme_mod"));
        assertEquals(1, manager.resolveLoadOrder(List.of(themeMod, coreMod)).size());

        // Cyclic dependencies rejected cleanly
        ModDescriptor cycA = new ModDescriptor("a", true, List.of("b"), List.of());
        ModDescriptor cycB = new ModDescriptor("b", true, List.of("a"), List.of());
        assertThrows(IllegalStateException.class, () -> manager.resolveLoadOrder(List.of(cycA, cycB)));

        Path starterMod = ModManager.createStarterModTemplate(tempDir, "cool_ui");
        assertTrue(Files.exists(starterMod.resolve("plugin.json")));
        assertTrue(Files.exists(starterMod.resolve("scripts/override.vjava")));

        // #0838 isolated URLClassLoader closes safely on reload
        try (PluginHotReloader reloader = new PluginHotReloader()) {
            URLClassLoader l1 = reloader.reload(List.of(starterMod));
            assertTrue(reloader.hasActiveLoader());
            URLClassLoader l2 = reloader.reload(List.of(starterMod));
            assertFalse(l1 == l2);
            assertEquals(2, reloader.getReloadCount());
        }
    }

    @Test
    void testArchivePerfDeduplicationDeltaPatchEd25519AndZipBombGuards(@TempDir Path tempDir)
            throws IOException {
        assertTrue(ArchiveSecurityAndPerfUtils.isPreCompressedMedia("assets/cg_1.png"));
        assertFalse(ArchiveSecurityAndPerfUtils.isPreCompressedMedia("scripts/main.vjava"));

        Path rangeFile = tempDir.resolve("video.bin");
        byte[] bytes = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);
        Files.write(rangeFile, bytes);
        assertArrayEquals("4567".getBytes(StandardCharsets.US_ASCII),
                ArchiveSecurityAndPerfUtils.readFileRangeStreaming(rangeFile, 4, 4));

        Map<String, byte[]> files = Map.of(
                "scripts/main.vjava", "label start:\n  \"Hi\"\n  return\n".repeat(10).getBytes(StandardCharsets.UTF_8),
                "assets/unused_sketch.png", new byte[]{1, 2, 3},
                "assets/a.png", bytes,
                "assets/b_dup.png", bytes
        );
        var rep = ArchiveSecurityAndPerfUtils.analyzeAndCompressParallel(files, Set.of("assets/unused_sketch.png"), 2);
        assertFalse(rep.topLargestPaths().contains("assets/unused_sketch.png"));
        assertTrue(rep.throughputMbPerSec() > 0.0);

        var dedup = ArchiveSecurityAndPerfUtils.buildContentDeduplicationIndex(files);
        assertEquals(dedup.get("assets/a.png"), dedup.get("assets/b_dup.png"));

        String baseSha = ArchiveSecurityAndPerfUtils.sha256Hex(bytes);
        DeltaPatchBundle patch = new DeltaPatchBundle(baseSha,
                Map.of("scripts/new.vjava", "ok".getBytes(StandardCharsets.UTF_8)),
                List.of("assets/unused_sketch.png"));
        Map<String, byte[]> patched = ArchiveSecurityAndPerfUtils.applyDeltaPatch(files, baseSha, patch);
        assertTrue(patched.containsKey("scripts/new.vjava"));
        assertFalse(patched.containsKey("assets/unused_sketch.png"));
        assertThrows(IllegalStateException.class,
                () -> ArchiveSecurityAndPerfUtils.applyDeltaPatch(files, "wrong-sha", patch));

        // #0815 Ed25519 signature verification
        KeyPair kp = ArchiveSecurityAndPerfUtils.generateEd25519KeyPair();
        byte[] sig = ArchiveSecurityAndPerfUtils.signEd25519(bytes, kp.getPrivate());
        ArchiveSecurityAndPerfUtils.verifyEd25519OrThrow(bytes, sig, kp.getPublic());
        byte[] tampered = bytes.clone();
        tampered[0] ^= 0xFF;
        assertThrows(SecurityException.class,
                () -> ArchiveSecurityAndPerfUtils.verifyEd25519OrThrow(tampered, sig, kp.getPublic()));

        // #0801 + #0842 safe ZIP extraction against Zip-Slip & oversized bomb
        Path safeZip = tempDir.resolve("safe.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(safeZip))) {
            zos.putNextEntry(new ZipEntry("project.vjava.json"));
            zos.write("{\"title\":\"ZipMod\"}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        assertThrows(IOException.class, () -> ArchiveSecurityAndPerfUtils.extractZipSafely(
                safeZip, tempDir.resolve("out_small"), 4L, null));
        List<Path> extracted = ArchiveSecurityAndPerfUtils.extractZipSafely(
                safeZip, tempDir.resolve("out_ok"), 1024L * 1024L, null);
        assertEquals(1, extracted.size());
    }

    @Test
    void testLaunchersCompliancePortabilityBuildSyncAndEndToEndTemplateExportBoot(@TempDir Path tempDir)
            throws Exception {
        assertTrue(PlatformTarget.MACOS_ONLY.includesClassifier("natives-macos-arm64"));
        assertFalse(PlatformTarget.MACOS_ONLY.includesClassifier("natives-windows"));

        String sh = LauncherAndComplianceGenerator.buildUnixLauncherScript(2048, true);
        assertTrue(sh.contains("-XstartOnFirstThread"));
        assertTrue(sh.contains("--perf-overlay"));
        assertTrue(sh.contains("JAVA_VER"));
        assertTrue(sh.contains("\"$@\""));
        assertTrue(LauncherAndComplianceGenerator.buildWindowsBatchLauncher(1024).contains("%*"));
        assertTrue(LauncherAndComplianceGenerator.buildThirdPartyNoticeText("MyGame").contains("LWJGL 3.3.6"));
        assertTrue(LauncherAndComplianceGenerator.buildOfflineTroubleshootingHtml("MyGame").contains("OpenGL 3.3"));
        assertTrue(LauncherAndComplianceGenerator.buildStarterGithubActionsWorkflow().contains("java-version: '21'"));

        // #0835 & #0836 filename portability audits
        var portIssues = PortabilityAndReleaseValidator.auditCrossPlatformFilenames(
                List.of("assets/bg_Wald.png"), Set.of("assets/bg_wald.png", "assets/CON.png"));
        assertEquals(2, portIssues.size());

        // #0820 prevent recursive export inside projectRoot (except dist/)
        assertThrows(IllegalArgumentException.class,
                () -> PortabilityAndReleaseValidator.assertSafeExportOutputDir(tempDir, tempDir));
        PortabilityAndReleaseValidator.assertSafeExportOutputDir(tempDir, tempDir.resolve("dist"));

        // #0825 & #0849 build.gradle version and native checks against EngineVersion & EngineAPI
        String gradleText = Files.readString(Path.of("build.gradle"), StandardCharsets.UTF_8);
        assertTrue(EngineAPI.ENGINE_VERSION.endsWith(EngineVersion.VERSION));
        assertTrue(PortabilityAndReleaseValidator.auditBuildGradleSync(gradleText, EngineVersion.VERSION).isEmpty());

        // #0843, #0844, #0846, #0790
        String notes = PortabilityAndReleaseValidator.formatReleaseNotesFromCommits(
                List.of("feat: new camera", "fix: save crash", "docs: update guide"));
        assertTrue(notes.contains("## Features") && notes.contains("## Bug Fixes"));
        assertEquals(Optional.of("1.2.0"), PortabilityAndReleaseValidator.checkUpdateManifestOfflineSafe(
                "{\"latestVersion\":\"1.2.0\"}", "1.0.0"));
        assertTrue(PortabilityAndReleaseValidator.allocateIsolatedBuildDir(tempDir, "demo")
                .getFileName().toString().contains("demo-p"));

        Path lockFile = tempDir.resolve("saves/.lock");
        var lock1 = PortabilityAndReleaseValidator.tryAcquireSaveDirLock(lockFile);
        assertTrue(lock1.isPresent());
        lock1.get().close();

        // #0779 + #0813 + #0848 Full workflow: generate template -> double deterministic pack -> delete src -> boot
        Path projectDir = ProjectTemplateGenerator.generate(tempDir.resolve("hub_proj"), "HubGame", "2D");
        Path distDir = tempDir.resolve("exported_dist");
        Files.createDirectories(distDir);
        Path vpak1 = distDir.resolve("game.vpak");
        Path vpak2 = tempDir.resolve("game2.vpak");
        VpakArchiver.pack(projectDir, vpak1);
        VpakArchiver.pack(projectDir, vpak2);
        assertEquals(
                ArchiveSecurityAndPerfUtils.sha256Hex(Files.readAllBytes(vpak1)),
                ArchiveSecurityAndPerfUtils.sha256Hex(Files.readAllBytes(vpak2))
        );
        Files.writeString(distDir.resolve("run_game.sh"), sh, StandardCharsets.UTF_8);

        // Delete original source project directory and verify dist/.vpak remains bootable
        try (var walk = Files.walk(projectDir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
        assertTrue(PortabilityAndReleaseValidator.verifyExportedPackage(distDir));
        VpakResourceProvider provider = VpakResourceProvider.open(vpak1, null);
        assertTrue(provider.contains("project.vjava.json"));
    }
}
