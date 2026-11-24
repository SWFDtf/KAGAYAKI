package de.swfdtf;

import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineMode;
import de.swfdtf.tools.ProjectTemplateGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies default project resolution and headless smoke execution for {@link Main} and {@link StandaloneMain}.
 */
class MainEntryPointsTest {

    @Test
    void mainAndStandaloneFallbackToDemoProjectWhenNoExplicitProjectArgProvided() {
        EngineConfig studioCfg = Main.buildConfig(new String[]{"--smoke-test", "--headless"});
        assertEquals(EngineMode.STUDIO_DUAL_WINDOW, studioCfg.engineMode());
        assertEquals(Path.of("demo/project"), studioCfg.projectDir());

        EngineConfig standaloneCfg = StandaloneMain.buildConfig(new String[]{"--smoke-test", "--headless"});
        assertEquals(EngineMode.STANDALONE_GAME, standaloneCfg.engineMode());
        assertEquals(Path.of("demo/project"), standaloneCfg.projectDir());
    }

    @Test
    void resolveDefaultProjectDirPrefersRootDescriptorThenDemoSubdir(@TempDir Path tempDir) throws Exception {
        assertTrue(Main.resolveDefaultProjectDir(tempDir).isEmpty());

        Path demoDir = tempDir.resolve("demo").resolve("project");
        Files.createDirectories(demoDir);
        Files.writeString(demoDir.resolve("project.vjava.json"), "{\"title\":\"Demo\"}");
        Optional<Path> demoResolved = Main.resolveDefaultProjectDir(tempDir);
        assertEquals(Optional.of(demoDir.normalize()), demoResolved);

        Files.writeString(tempDir.resolve("project.vjava.json"), "{\"title\":\"Root\"}");
        Optional<Path> rootResolved = Main.resolveDefaultProjectDir(tempDir);
        assertEquals(Optional.of(tempDir.normalize()), rootResolved);
    }

    @Test
    void explicitProjectFlagOverridesDemoFallback(@TempDir Path tempDir) throws Exception {
        Path custom = tempDir.resolve("custom_proj");
        ProjectTemplateGenerator.generate(custom, "Custom", "2D");

        EngineConfig cfg = Main.buildConfig(new String[]{"--project", custom.toString(), "--smoke-test", "--headless"});
        assertEquals(custom.normalize(), cfg.projectDir());
        assertFalse(Main.hasExplicitProjectArg(new String[]{"--studio", "--headless"}));
        assertTrue(Main.hasExplicitProjectArg(new String[]{"--project", custom.toString()}));
    }

    @Test
    void mainAndStandaloneSmokeTestRunCleanlyOnDefaultDemoProject() {
        Main.main(new String[]{"--smoke-test", "--headless"});
        StandaloneMain.main(new String[]{"--smoke-test", "--headless"});
    }
}
