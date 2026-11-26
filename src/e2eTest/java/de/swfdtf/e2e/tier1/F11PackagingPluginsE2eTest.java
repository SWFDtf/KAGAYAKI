package de.swfdtf.e2e.tier1;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineMode;
import de.swfdtf.core.EnginePlugin;
import de.swfdtf.core.PluginRegistry;
import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2eFixtureFactory;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.e2e.support.E2eWorkspaceSupport;
import de.swfdtf.tools.AssetChecksumVerifier;
import de.swfdtf.tools.StandaloneBuilder;
import de.swfdtf.tools.VpakArchiver;
import de.swfdtf.tools.VpakManifestInspector;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 1 happy-path E2E tests for Feature Area F11: {@code .vpak} packaging, checksums,
 * encryption, standalone fat JAR, {@code jpackage} native bundles, and runtime plugins.
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER1)
public final class F11PackagingPluginsE2eTest {

    /** Creates a new {@code F11PackagingPluginsE2eTest} instance. */
    public F11PackagingPluginsE2eTest() {
    }

    /**
     * Verifies {@link VpakArchiver} pack/open/read/extract round-trip, exclusion of
     * {@code saves/}/{@code build/}/{@code dist/}/{@code .git/}, and {@link VpakManifestInspector}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if packing, reading, or extracting fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void vpakPackOpenReadAndExtractRoundTrip(@TempDir Path tempDir) throws Exception {
        Path projectDir = preparePackableProject(tempDir.resolve("proj"));
        E2eWorkspaceSupport.writeScript(projectDir, "saves/slot_01.json", "{\"ignored\":true}");
        E2eWorkspaceSupport.writeScript(projectDir, "build/temp.txt", "ignored");
        E2eWorkspaceSupport.writeScript(projectDir, "dist/old.vpak", "ignored");

        Path vpakFile = tempDir.resolve("out").resolve("game.vpak");
        List<VpakArchiver.VpakEntryInfo> packed = E2eCliRunner.packVpak(projectDir, vpakFile);
        assertFalse(packed.isEmpty(), "Expected packed entries in game.vpak");

        VpakManifestInspector.VpakInspectionReport inspection = new VpakManifestInspector().inspect(vpakFile);
        assertTrue(inspection.integrityVerified(), "Expected clean VPAK integrity");
        assertEquals(packed.size(), inspection.entryCount());

        Path extractDir = tempDir.resolve("extracted");
        try (VpakArchiver.VpakArchive archive = VpakArchiver.open(vpakFile)) {
            assertTrue(archive.contains("project.vjava.json"));
            assertTrue(archive.contains("main.vjava"));
            assertTrue(archive.contains("assets/images/bg/bg_pack.png"));
            assertTrue(archive.contains("assets/audio/tone.wav"));
            assertTrue(archive.contains("tl/english/translations.vjava"));

            assertFalse(archive.contains("saves/slot_01.json"));
            assertFalse(archive.contains("build/temp.txt"));
            assertFalse(archive.contains("dist/old.vpak"));

            byte[] originalPng = Files.readAllBytes(projectDir.resolve("assets/images/bg/bg_pack.png"));
            assertArrayEquals(originalPng, archive.readEntry("assets/images/bg/bg_pack.png"));
            archive.extractAll(extractDir);
        }

        assertArrayEquals(
                Files.readAllBytes(projectDir.resolve("main.vjava")),
                Files.readAllBytes(extractDir.resolve("main.vjava"))
        );
    }

    /**
     * Verifies goal {@code #0946}: {@link AssetChecksumVerifier} generates a deterministic SHA-256
     * manifest, round-trips JSON serialization, and verifies asset integrity.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if manifest generation or verification fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void assetChecksumManifestGenerationAndVerification(@TempDir Path tempDir) throws Exception {
        Path projectDir = preparePackableProject(tempDir.resolve("proj_checksum"));
        AssetChecksumVerifier verifier = new AssetChecksumVerifier();

        AssetChecksumVerifier.AssetManifest manifest = verifier.generateManifest(projectDir);
        assertEquals(AssetChecksumVerifier.DEFAULT_ALGORITHM, manifest.algorithm());
        assertTrue(manifest.checksums().containsKey("assets/images/bg/bg_pack.png"));
        assertTrue(manifest.checksums().containsKey("assets/audio/tone.wav"));

        AssetChecksumVerifier.AssetManifest reparsed =
                AssetChecksumVerifier.AssetManifest.fromJson(manifest.toJson());
        assertEquals(manifest, reparsed);

        AssetChecksumVerifier.VerificationReport report = verifier.verify(projectDir, reparsed);
        assertTrue(report.isIntact());
        assertEquals(2, report.validCount());
    }

    /**
     * Verifies {@link StandaloneBuilder} exports {@code game.vpak}, POSIX/Windows launcher scripts,
     * and {@code standalone-packaging.json} descriptor cleanly.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if export fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void standaloneBuilderExportsCompleteBundleAndDescriptor(@TempDir Path tempDir) throws Exception {
        Path projectDir = preparePackableProject(tempDir.resolve("proj_export"));
        Path distDir = tempDir.resolve("dist");

        StandaloneBuilder.BuildResult result = E2eCliRunner.exportStandalone(projectDir, distDir, null);
        assertTrue(Files.isRegularFile(result.vpakFile()));
        assertTrue(Files.isRegularFile(result.launcherScript()));
        assertTrue(Files.isRegularFile(distDir.resolve("run_game.bat")));
        assertTrue(Files.isRegularFile(result.packagingDescriptor()));
        assertTrue(result.packagedEntries() >= 5);

        String launcherSh = Files.readString(result.launcherScript(), StandardCharsets.UTF_8);
        assertTrue(launcherSh.contains("StandaloneMain --standalone"));

        String descriptorJson = Files.readString(result.packagingDescriptor(), StandardCharsets.UTF_8);
        assertTrue(descriptorJson.contains("jpackage"));
        assertTrue(descriptorJson.contains("jlink"));
    }

    /**
     * Verifies {@link PluginRegistry} lifecycle management: register, enable, disable, unregister.
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void pluginRegistryManagesLifecycleHooks() {
        EngineAPI api = new EngineAPI(EngineConfig.defaults());
        PluginRegistry registry = new PluginRegistry(api);
        AtomicInteger enabledCount = new AtomicInteger();
        AtomicInteger disabledCount = new AtomicInteger();

        EnginePlugin plugin = new EnginePlugin() {
            @Override
            public String id() {
                return "e2e.sample.plugin";
            }

            @Override
            public String displayName() {
                return "E2E Sample Plugin";
            }

            @Override
            public String version() {
                return "1.0.0";
            }

            @Override
            public void onEnable(EngineAPI activeApi) {
                enabledCount.incrementAndGet();
                activeApi.setProperty("e2e.plugin.active", Boolean.TRUE);
            }

            @Override
            public void onDisable(EngineAPI activeApi) {
                disabledCount.incrementAndGet();
                activeApi.setProperty("e2e.plugin.active", Boolean.FALSE);
            }
        };

        registry.register(plugin);
        assertEquals(1, registry.listAll().size());
        assertFalse(registry.isEnabled("e2e.sample.plugin"));

        registry.enable("e2e.sample.plugin");
        assertTrue(registry.isEnabled("e2e.sample.plugin"));
        assertEquals(1, enabledCount.get());
        assertEquals(Boolean.TRUE, api.getProperty("e2e.plugin.active"));

        assertTrue(registry.unregister("e2e.sample.plugin"));
        assertEquals(1, disabledCount.get());
        assertEquals(Boolean.FALSE, api.getProperty("e2e.plugin.active"));
        assertTrue(registry.listAll().isEmpty());
    }

    /**
     * Verifies procedural AWT-free fixture generation (PNG, JPEG, WebP, WAV, OGG) and
     * minimal playable project creation pass structural and lint checks.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if generating or reading fixtures fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void fixtureFactoryProducesValidProceduralAssetsAndPlayableProject(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("minimal_playable");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                image bg_default = "assets/images/bg/bg_default.png"
                label start:
                    scene bg_default
                    play music "assets/audio/tone.wav"
                    "Minimal playable E2E fixture."
                    stop music
                    return
                """);
        assertTrue(E2eCliRunner.lintProject(projectDir).isClean());

        Path jpg = E2eFixtureFactory.writeSolidJpg(tempDir.resolve("solid.jpg"), 16, 16, 120, 180, 220);
        byte[] jpgBytes = Files.readAllBytes(jpg);
        assertTrue(jpgBytes.length > 4 && (jpgBytes[0] & 0xFF) == 0xFF && (jpgBytes[1] & 0xFF) == 0xD8);

        Path webp = E2eFixtureFactory.writeMinimalWebP(tempDir.resolve("solid.webp"), 8, 8);
        byte[] webpBytes = Files.readAllBytes(webp);
        assertEquals("RIFF", new String(webpBytes, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WEBPVP8L", new String(webpBytes, 8, 8, StandardCharsets.US_ASCII));

        Path ogg = E2eFixtureFactory.writeMinimalOgg(tempDir.resolve("stub.ogg"));
        byte[] oggBytes = Files.readAllBytes(ogg);
        assertEquals("OggS", new String(oggBytes, 0, 4, StandardCharsets.US_ASCII));
    }

    /**
     * Verifies goal {@code #0766}: {@code StandaloneMain} boots directly from a directory
     * containing only {@code game.vpak} without loose {@code assets/} or {@code .vjava} files.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if export or subprocess launch fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void standaloneMainBootsDirectlyFromVpakWithoutLooseAssets(@TempDir Path tempDir) throws Exception {
        Path projectDir = preparePackableProject(tempDir.resolve("proj_vpak_boot"));
        Path distDir = tempDir.resolve("dist_vpak_only");
        E2eCliRunner.exportStandalone(projectDir, distDir, null);

        assertFalse(Files.exists(distDir.resolve("assets")));
        assertFalse(Files.exists(distDir.resolve("main.vjava")));
        assertTrue(Files.isRegularFile(distDir.resolve("game.vpak")));

        E2eCliRunner.ProcessResult result = E2eCliRunner.runJavaMainWithMacFirstThread(
                "de.swfdtf.StandaloneMain",
                List.of("--standalone", distDir.toString(), "--headless", "--smoke-test"),
                distDir,
                Duration.ofSeconds(30)
        );
        assertTrue(
                result.isSuccess(),
                "Expected StandaloneMain direct .vpak boot exit 0, got "
                        + result.exitCode() + " stderr=" + result.stderr()
        );
        String combined = result.stdout() + "\n" + result.stderr();
        assertTrue(
                combined.contains("F11 Packaging Fixture") || combined.contains("game.vpak"),
                "Expected output to reflect project/vpak boot, got: " + combined
        );
    }

    /**
     * Verifies goal {@code #0767} and {@code #0770}: encrypted {@code .vpak} obscures raw bytes
     * while preserving integrity and bootability, and {@code plugins/*.jar} plugins are loaded
     * dynamically via {@code ServiceLoader<EnginePlugin>}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if packaging, plugin compilation, or boot fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void encryptedVpakRoundTripAndServiceLoaderPlugin(@TempDir Path tempDir) throws Exception {
        Path projectDir = preparePackableProject(tempDir.resolve("proj_enc_plugin"));
        Path plainDist = tempDir.resolve("dist_plain");
        StandaloneBuilder.BuildResult plainBuilt = E2eCliRunner.exportStandalone(projectDir, plainDist, null);
        byte[] plainVpak = Files.readAllBytes(plainBuilt.vpakFile());

        Path descriptorFile = projectDir.resolve("project.vjava.json");
        String descriptorJson = Files.readString(descriptorFile, StandardCharsets.UTF_8);
        String withEncKey = descriptorJson.substring(0, descriptorJson.lastIndexOf('}'))
                + ",\n  \"vpakEncryptionKey\": \"kagayaki-e2e-secret\"\n}\n";
        Files.writeString(descriptorFile, withEncKey, StandardCharsets.UTF_8);

        Path distDir = tempDir.resolve("dist_enc");
        StandaloneBuilder.BuildResult built = E2eCliRunner.exportStandalone(projectDir, distDir, null);
        byte[] rawVpak = Files.readAllBytes(built.vpakFile());
        assertFalse(
                Arrays.equals(plainVpak, rawVpak),
                "Encrypted .vpak bytes must differ from unencrypted deterministic .vpak output"
        );
        String asciiProbe = new String(rawVpak, StandardCharsets.ISO_8859_1);
        assertFalse(asciiProbe.contains("Verpacktes Projekt"), "Encrypted .vpak must not expose plaintext script");
        assertFalse(asciiProbe.contains("IHDR"), "Encrypted .vpak must not expose raw PNG chunk headers");

        E2eCliRunner.ProcessResult goodKeyBoot = E2eCliRunner.runJavaMainWithMacFirstThread(
                "de.swfdtf.StandaloneMain",
                List.of(
                        "--standalone",
                        distDir.toString(),
                        "--vpak-key",
                        "kagayaki-e2e-secret",
                        "--headless",
                        "--smoke-test"
                ),
                distDir,
                Duration.ofSeconds(20)
        );
        assertTrue(
                goodKeyBoot.isSuccess(),
                "StandaloneMain must accept --vpak-key and boot encrypted game.vpak, stderr="
                        + goodKeyBoot.stderr()
        );

        Path pluginsDir = projectDir.resolve("plugins");
        Files.createDirectories(pluginsDir);
        buildSampleServiceLoaderPluginJar(pluginsDir.resolve("sample-e2e-plugin.jar"));

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir,
                EngineMode.STANDALONE_GAME,
                true
        )) {
            app.initialize();
            assertEquals(Boolean.TRUE, app.getApi().getProperty("e2e.serviceloader.enabled"));
            app.run();
        }
    }

    /**
     * Verifies goals {@code #0768} and {@code #0769}: {@code packageStandalone} builds a fat
     * standalone JAR executable with {@code java -jar}, and a documented Gradle {@code jpackage}
     * task generates a valid host OS bundle.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if Gradle tasks or JAR inspection fail
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void fatStandaloneJarAndJpackageDescriptorReady(@TempDir Path tempDir) throws Exception {
        Path repoRoot = E2eWorkspaceSupport.repoRoot();
        E2eCliRunner.ProcessResult pkgResult = E2eCliRunner.runGradle(
                repoRoot,
                List.of("packageStandalone"),
                Duration.ofMinutes(2)
        );
        assertTrue(pkgResult.isSuccess(), "packageStandalone failed: " + pkgResult.stderr());

        Path jarPath = E2eWorkspaceSupport.findStandaloneJar().orElse(null);
        assertNotNull(jarPath, "Expected standalone JAR in build/dist");

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            assertFalse(
                    jar.stream().anyMatch(e -> e.getName().contains("StudioWindowController")),
                    "Standalone JAR must exclude StudioWindowController"
            );
            assertFalse(
                    jar.stream().anyMatch(e -> e.getName().endsWith("AGENT_CONTEXT.md")),
                    "Standalone JAR must exclude AGENT_CONTEXT.md"
            );
        }

        Path projectDir = preparePackableProject(tempDir.resolve("jar_smoke_proj"));
        E2eCliRunner.ProcessResult jarRun = E2eCliRunner.runJar(
                jarPath,
                List.of("--project", projectDir.toString(), "--headless", "--smoke-test"),
                tempDir,
                Duration.ofSeconds(30)
        );
        assertTrue(jarRun.isSuccess(), "java -jar smoke run failed: " + jarRun.stderr());

        E2eCliRunner.ProcessResult tasksResult = E2eCliRunner.runGradle(
                repoRoot,
                List.of("tasks", "--all"),
                Duration.ofMinutes(1)
        );
        assertTrue(tasksResult.isSuccess());
        assertTrue(
                tasksResult.stdout().contains("jpackage"),
                "Expected jpackage task registered in Gradle build"
        );
    }

    private static Path preparePackableProject(Path projectDir) throws IOException {
        E2eWorkspaceSupport.copyResourceTree("e2e/f11/packable_project", projectDir);
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/bg/bg_pack.png"), 64, 64, 60, 90, 140, 255);
        E2eFixtureFactory.writeSineWav(
                projectDir.resolve("assets/audio/tone.wav"), 440.0, 0.1, 22_050);
        return projectDir;
    }

    private static void buildSampleServiceLoaderPluginJar(Path jarDest) throws IOException {
        Path work = Files.createTempDirectory(jarDest.getParent(), "plugin_build_");
        try {
            Path srcFile = work.resolve("de/swfdtf/e2e/sampleplugin/SampleServicePlugin.java");
            Files.createDirectories(srcFile.getParent());
            Files.writeString(srcFile, """
                    package de.swfdtf.e2e.sampleplugin;

                    import de.swfdtf.core.EngineAPI;
                    import de.swfdtf.core.EnginePlugin;

                    public final class SampleServicePlugin implements EnginePlugin {
                        @Override
                        public String id() {
                            return "e2e.serviceloader.sample";
                        }

                        @Override
                        public String displayName() {
                            return "E2E ServiceLoader Sample";
                        }

                        @Override
                        public String version() {
                            return "1.0.0";
                        }

                        @Override
                        public void onEnable(EngineAPI api) {
                            api.setProperty("e2e.serviceloader.enabled", Boolean.TRUE);
                        }
                    }
                    """, StandardCharsets.UTF_8);

            Path classesDir = work.resolve("classes");
            Files.createDirectories(classesDir);
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            assertNotNull(compiler, "JDK JavaCompiler required to compile test plugin JAR");
            int rc = compiler.run(
                    null,
                    null,
                    null,
                    "-cp",
                    System.getProperty("java.class.path", ""),
                    "-d",
                    classesDir.toString(),
                    srcFile.toString()
            );
            assertEquals(0, rc, "Test plugin compilation must succeed");

            try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarDest))) {
                Path classFile = classesDir.resolve("de/swfdtf/e2e/sampleplugin/SampleServicePlugin.class");
                jos.putNextEntry(new JarEntry("de/swfdtf/e2e/sampleplugin/SampleServicePlugin.class"));
                jos.write(Files.readAllBytes(classFile));
                jos.closeEntry();

                jos.putNextEntry(new JarEntry("META-INF/services/de.swfdtf.core.EnginePlugin"));
                jos.write("de.swfdtf.e2e.sampleplugin.SampleServicePlugin\n"
                        .getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        } finally {
            E2eWorkspaceSupport.deleteRecursively(work);
        }
    }
}
