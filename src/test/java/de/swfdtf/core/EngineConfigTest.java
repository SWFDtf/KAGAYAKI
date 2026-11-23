package de.swfdtf.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineConfigTest {

    @Test
    void providesSensibleDefaults() {
        EngineConfig config = EngineConfig.defaults();
        assertEquals(EngineMode.STANDALONE_GAME, config.engineMode());
        assertEquals("2D", config.renderModeName());
        assertEquals(1920, config.logicalWidth());
        assertEquals(1080, config.logicalHeight());
        assertTrue(config.vsync());
        assertEquals(120, config.targetFps());
        assertFalse(config.smokeTest());
        assertFalse(config.headless());
        assertTrue(config.isStandaloneMode());
        assertFalse(config.isStudioMode());
    }

    @Test
    void parsesStudioAndStandaloneCliArguments() {
        EngineConfig studio = EngineConfig.fromArgs(new String[]{
                "--studio",
                "--project", "projects/demo",
                "--mode", "3d",
                "--width", "1280",
                "--height", "720",
                "--no-vsync",
                "--fps", "144",
                "--smoke-test",
                "--headless"
        });

        assertEquals(EngineMode.STUDIO_DUAL_WINDOW, studio.engineMode());
        assertTrue(studio.isStudioMode());
        assertEquals(Path.of("projects/demo"), studio.projectDir());
        assertEquals("3D", studio.renderModeName());
        assertTrue(studio.is3DMode());
        assertEquals(1280, studio.logicalWidth());
        assertEquals(720, studio.logicalHeight());
        assertFalse(studio.vsync());
        assertEquals(144, studio.targetFps());
        assertTrue(studio.smokeTest());
        assertTrue(studio.headless());

        EngineConfig gradleStandalone = EngineConfig.fromArgs(new String[]{
                "--standalone", "samples/my_vn"
        });
        assertEquals(EngineMode.STANDALONE_GAME, gradleStandalone.engineMode());
        assertEquals(Path.of("samples/my_vn"), gradleStandalone.projectDir());
    }

    @Test
    void validatesInvalidRenderModesAndDimensions() {
        assertThrows(IllegalArgumentException.class, () -> EngineConfig.builder().renderModeName("VULKAN").build());
        assertThrows(IllegalArgumentException.class, () -> EngineConfig.builder().logicalWidth(0).build());
        assertThrows(IllegalArgumentException.class, () -> EngineConfig.builder().targetFps(-1).build());
        assertThrows(IllegalArgumentException.class, () -> EngineConfig.fromArgs(new String[]{"--unknown-flag"}));
    }
}
