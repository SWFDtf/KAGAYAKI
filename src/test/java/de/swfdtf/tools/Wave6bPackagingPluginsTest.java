package de.swfdtf.tools;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineLogger;
import de.swfdtf.core.PluginLoader;
import de.swfdtf.core.PluginRegistry;
import de.swfdtf.core.ProjectDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for Wave 6b (F11): encrypted {@code .vpak} archives, {@link VpakResourceProvider},
 * {@code --vpak-key} CLI parsing, and {@link PluginLoader} error isolation.
 */
final class Wave6bPackagingPluginsTest {

    @Test
    void engineConfigParsesVpakKeyCliFlag() {
        EngineConfig spaceArg = EngineConfig.fromArgs(
                new String[]{"--standalone", "dist_dir", "--vpak-key", "secret-123", "--headless", "--smoke-test"}
        );
        assertEquals("secret-123", spaceArg.vpakKey());

        EngineConfig eqArg = EngineConfig.fromArgs(new String[]{"--vpak-key=inline-key"});
        assertEquals("inline-key", eqArg.vpakKey());
    }

    @Test
    void encryptedVpakRoundTripWrongKeyAndTamperingDetection(@TempDir Path tempDir) throws Exception {
        Path projectDir = ProjectTemplateGenerator.generate(
                tempDir.resolve("proj"), "Enc Test", "SWFD", "2D", ProjectTemplateGenerator.TemplateType.MINIMAL);
        Path plainVpak = tempDir.resolve("plain.vpak");
        Path encVpak = tempDir.resolve("enc.vpak");

        VpakArchiver.pack(projectDir, plainVpak);
        VpakArchiver.pack(projectDir, encVpak, "top-secret-key");

        byte[] plainBytes = Files.readAllBytes(plainVpak);
        byte[] encBytes = Files.readAllBytes(encVpak);
        assertFalse(Arrays.equals(plainBytes, encBytes));

        try (VpakResourceProvider provider = VpakResourceProvider.open(encVpak, "top-secret-key")) {
            provider.verifyIntegrity();
            assertTrue(provider.contains("main.vjava"));
            assertArrayEquals(
                    Files.readAllBytes(projectDir.resolve("main.vjava")),
                    provider.readBytes("main.vjava")
            );
            ProjectDescriptor desc = ProjectDescriptor.parseJson(
                    new String(provider.readBytes("project.vjava.json"), StandardCharsets.UTF_8));
            assertEquals("Enc Test", desc.getTitle());
        }

        assertThrows(IOException.class, () -> VpakArchiver.open(encVpak, null));
        assertThrows(IOException.class, () -> VpakArchiver.open(encVpak, "wrong-key"));

        // Tamper encrypted payload byte and verify integrity check fails
        byte[] tampered = encBytes.clone();
        tampered[24] ^= 0x55;
        Path badVpak = tempDir.resolve("tampered.vpak");
        Files.write(badVpak, tampered);
        assertThrows(IOException.class, () -> {
            try (VpakResourceProvider p = VpakResourceProvider.open(badVpak, "top-secret-key")) {
                p.verifyIntegrity();
            }
        });
    }

    @Test
    void pluginLoaderLoadsValidJarAndSkipsCorruptJarSafely(@TempDir Path tempDir) throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        Files.createDirectories(pluginsDir);

        // Corrupt / nonexistent service class JAR
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(pluginsDir.resolve("broken.jar")))) {
            jos.putNextEntry(new JarEntry("META-INF/services/de.swfdtf.core.EnginePlugin"));
            jos.write("de.swfdtf.nonexistent.BrokenPlugin\n".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        buildValidPluginJar(pluginsDir.resolve("valid.jar"));

        EngineAPI api = new EngineAPI(EngineConfig.defaults());
        PluginRegistry registry = new PluginRegistry(api);
        EngineLogger logger = new EngineLogger();

        List<URLClassLoader> loaders = PluginLoader.loadFromDirectory(pluginsDir, registry, logger);
        try {
            assertTrue(registry.isEnabled("unit.test.plugin"));
            assertEquals(Boolean.TRUE, api.getProperty("unit.plugin.enabled"));
            assertFalse(logger.getRecentEntries(20).isEmpty());
        } finally {
            for (URLClassLoader loader : loaders) {
                loader.close();
            }
        }
    }

    private static void buildValidPluginJar(Path jarDest) throws IOException {
        Path work = Files.createTempDirectory(jarDest.getParent(), "compile_");
        Path src = work.resolve("de/swfdtf/testplugin/UnitSamplePlugin.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package de.swfdtf.testplugin;

                import de.swfdtf.core.EngineAPI;
                import de.swfdtf.core.EnginePlugin;

                public final class UnitSamplePlugin implements EnginePlugin {
                    @Override
                    public String id() {
                        return "unit.test.plugin";
                    }

                    @Override
                    public String displayName() {
                        return "Unit Test Plugin";
                    }

                    @Override
                    public String version() {
                        return "1.0.0";
                    }

                    @Override
                    public void onEnable(EngineAPI api) {
                        api.setProperty("unit.plugin.enabled", Boolean.TRUE);
                    }
                }
                """, StandardCharsets.UTF_8);

        Path classes = work.resolve("classes");
        Files.createDirectories(classes);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        int rc = compiler.run(null, null, null, "-cp", System.getProperty("java.class.path", ""),
                "-d", classes.toString(), src.toString());
        assertEquals(0, rc);

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarDest))) {
            Path classFile = classes.resolve("de/swfdtf/testplugin/UnitSamplePlugin.class");
            jos.putNextEntry(new JarEntry("de/swfdtf/testplugin/UnitSamplePlugin.class"));
            jos.write(Files.readAllBytes(classFile));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("META-INF/services/de.swfdtf.core.EnginePlugin"));
            jos.write("de.swfdtf.testplugin.UnitSamplePlugin\n".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
    }
}
