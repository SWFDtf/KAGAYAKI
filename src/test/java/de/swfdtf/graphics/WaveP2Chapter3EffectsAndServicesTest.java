package de.swfdtf.graphics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaveP2Chapter3EffectsAndServicesTest {

    @Test
    void particleEmitterNodeWeatherControllerAndTextboxCollisionWork() {
        ParticleEmitterNode emitterNode = new ParticleEmitterNode(
                "snow_fx", ParticleEmitterNode.EffectKind.SNOW, 120
        );
        emitterNode.setCollisionFloorY(800.0f, false);
        assertEquals(120, emitterNode.getActiveParticleCount());
        emitterNode.update(0.25);

        ParticleSystem.Particle p = new ParticleSystem.Particle(
                100, 799, 0, 50, 0, 0, 4, 1.0f, 0, -1, new float[]{1, 1, 1, 1}
        );
        ParticleSystem.ParticleEmitter custom = new ParticleSystem.ParticleEmitter(
                ParticleSystem.EmitterKind.SNOW_BLOSSOM,
                List.of(p),
                1920,
                1080,
                100,
                0,
                null
        );
        custom.setCollisionFloorY(800.0f, false, 0.35f);
        custom.update(0.1);
        assertTrue(custom.getParticles().get(0).y <= 800.0f);

        WeatherOverlayController weather = new WeatherOverlayController();
        weather.setWeather(WeatherOverlayController.WeatherPreset.SAKURA, 0.8f, 0.0);
        assertEquals(WeatherOverlayController.WeatherPreset.SAKURA, weather.getCurrentPreset());
    }

    @Test
    void transitionsFlashWhitePushUpIrisFocusShatterAndCurveWork() {
        Transition flash = Transition.flashWhite(1.0f);
        float[] peakWeights = flash.evaluateBlendWeights(0.15f);
        assertEquals(1.0f, peakWeights[2], 1e-4f);
        assertEquals(0.0f, flash.evaluateBlendWeights(1.0f)[2], 1e-4f);

        Transition iris = Transition.irisOut(0.5f, 0.25f, 0.75f);
        assertEquals(0.25f, iris.getFocusX(), 1e-4f);
        assertEquals(0.75f, iris.getFocusY(), 1e-4f);

        Transition pushUp = Transition.parse("push_up(0.6)");
        assertEquals(Transition.Kind.PUSH_UP, pushUp.getKind());

        Transition shatter = Transition.shatter(1.0f, 6);
        List<Transition.ShatterShard> shards = shatter.computeShatterShards(0.5f, 4);
        assertEquals(24, shards.size());
        assertTrue(shards.stream().anyMatch(s -> Math.abs(s.rotationDegrees()) > 10.0f));
        assertEquals(0.0f, shatter.computeShatterShards(1.0f, 4).get(0).alpha(), 1e-4f);

        Transition curve = Transition.dissolveCurve(0.8f, 0.25f);
        float[] mid = curve.evaluateBlendWeights(0.5f);
        assertTrue(mid[0] + mid[1] >= 1.0f);
    }

    @Test
    void lighting2dSystemNormalMapsAndFlickerModulateIrradiance() {
        Path norm = Lighting2DSystem.resolveNormalMapPath(Path.of("assets/bg/hall.png"));
        assertEquals("hall_n.png", norm.getFileName().toString());

        Lighting2DSystem lighting = new Lighting2DSystem();
        lighting.setAmbientColor(0.1f, 0.1f, 0.15f);
        lighting.putLight(new Lighting2DSystem.PointLight2D(
                "lantern", 500, 500, 300, 1.0f, new float[]{1.0f, 0.8f, 0.5f}, 0.25f, 2.0f
        ));
        float[] near = lighting.evaluateIrradianceAt(510, 500, 0, 0, 1);
        float[] far = lighting.evaluateIrradianceAt(1500, 500, 0, 0, 1);
        assertTrue(near[0] > far[0]);
        lighting.update(0.125);
        assertTrue(lighting.evaluateFlickerIntensity(lighting.getLights().get(0)) > 1.0f);
    }

    @Test
    void customShaderRegistryHotReloadPreservesLastValidShaderAndChecksLine(@TempDir Path dir) throws Exception {
        CustomShaderRegistry registry = new CustomShaderRegistry();
        assertEquals(
            "magic_glow",
            CustomShaderRegistry.parseDefineShaderName("define shader magic_glow").orElseThrow()
        );

        String validSrc = """
                #version 330 core
                out vec4 fragColor;
                void main() {
                    fragColor = vec4(1.0);
                }
                """;
        Path shaderFile = dir.resolve("magic_glow.frag");
        Files.writeString(shaderFile, validSrc);
        CustomShaderRegistry.ReloadResult r1 = registry.loadFromShaderDirectory(dir, "magic_glow.frag");
        assertTrue(r1.valid());

        String brokenSrc = """
                #version 330 core
                void main() {
                    bad line;;;
                }
                """;
        CustomShaderRegistry.ReloadResult r2 = registry.registerOrReload("magic_glow", brokenSrc, shaderFile);
        assertFalse(r2.valid());
        assertEquals(3, r2.lineNumber());
        assertTrue(r2.retainedPrev());
        assertEquals(validSrc, registry.getShader("magic_glow").orElseThrow().source());
    }

    @Test
    void renderCacheNodeAuditorAndRuntimeServicesCoverRemainingChapter3Specs() {
        RenderCacheNode cacheNode = new RenderCacheNode("card_cache");
        cacheNode.addChild(new SceneNode("label1"));
        cacheNode.addChild(new SceneNode("icon1"));
        assertTrue(cacheNode.isCacheDirty());
        assertEquals(3, cacheNode.getEffectiveDrawCallCount());
        cacheNode.markCacheBaked();
        assertFalse(cacheNode.isCacheDirty());
        assertEquals(1, cacheNode.getEffectiveDrawCallCount());
        cacheNode.addChild(new SceneNode("badge"));
        assertTrue(cacheNode.isCacheDirty());

        GlResourceAuditor auditor = new GlResourceAuditor();
        auditor.recordCreate(GlResourceAuditor.ResourceKind.VAO);
        auditor.recordCreate(GlResourceAuditor.ResourceKind.FBO);
        assertEquals(2, auditor.getTotalActiveResources());
        auditor.recordDispose(GlResourceAuditor.ResourceKind.VAO);
        auditor.recordDispose(GlResourceAuditor.ResourceKind.FBO);
        assertEquals(0, auditor.getTotalActiveResources());
        assertEquals(0.0, auditor.getPassTimingMs("unrecorded_pass"));

        GraphicsRuntimeServices.FramePacer pacer = new GraphicsRuntimeServices.FramePacer();
        pacer.setTargetFps(120);
        pacer.setIdleThresholdSeconds(2.0);
        pacer.update(2.1, false);
        assertEquals(15, pacer.getEffectiveFpsLimit());
        pacer.update(0.016, true);
        assertEquals(120, pacer.getEffectiveFpsLimit());

        GraphicsRuntimeServices.ColorLut lut = GraphicsRuntimeServices.ColorLut.createIdentity(16);
        int[] mapped = lut.mapRgb(128, 64, 255);
        assertTrue(Math.abs(mapped[0] - 128) <= 17);
        assertEquals(255, mapped[2]);

        float dither = GraphicsRuntimeServices.HdrToneMapper.triangularDitherLuvLsb(17, 42);
        assertTrue(Math.abs(dither) <= (1.0f / 255.0f) + 1e-6f);
        assertTrue(GraphicsRuntimeServices.HdrToneMapper.acesFilmic(5.0f) <= 1.0f);

        GraphicsRuntimeServices.MotionTrailTracker trail = new GraphicsRuntimeServices.MotionTrailTracker(4);
        trail.update(100, 100, 0.016);
        trail.update(160, 100, 0.016);
        assertFalse(trail.getSamples().isEmpty());
        trail.update(160, 100, 0.25);
        assertTrue(trail.getSamples().isEmpty());

        GraphicsRuntimeServices.VectorDebugRenderer.TessellatedMesh poly =
                GraphicsRuntimeServices.VectorDebugRenderer.tessellatePolyline(
                        new float[]{0, 0, 100, 0, 150, 50}, 4.0f
                );
        assertEquals(4, poly.triangleCount());

        var split = GraphicsRuntimeServices.SplitViewConfig.horizontalSplit(1920, 1080, 16);
        assertEquals(0.0f, GraphicsRuntimeServices.SoftwareRasterizer.computeOverlapArea(
                split.leftViewport(), split.rightViewport()
        ));
        assertTrue(GraphicsRuntimeServices.SafeAreaGuides.titleSafe90(1920, 1080).width() < 1920);
        assertTrue(GraphicsRuntimeServices.AdaptiveContrastAdvisor.recommendBackdropAlpha(0.9f, 0.7f, 0.95f) > 0.9f);
        assertEquals(0.0f, new GraphicsRuntimeServices.MaskConfig(true, 0.45f).sampleMaskAlpha(0.0f, 0.0f));
    }

    @Test
    void videoPlayerSkipAndFontFallbackChainWork() {
        VideoPlayer player = new VideoPlayer(false);
        player.play(new VideoPlayer.ProceduralTestFrameSource(64, 36, 30.0));
        player.update(0.1);
        assertTrue(player.isPlaying());
        player.skip();
        assertTrue(player.isEnded());
        player.close();

        FontManager fm = new FontManager();
        fm.addFallbackFont("assets/fonts/NotoSansCJK.ttf");
        assertEquals(1, fm.getFallbackFontChain().size());
        FontAtlas atlas = fm.resolveAtlasForCodepoint(null, 24.0f, 'A', false);
        assertTrue(atlas.hasGlyph('A'));
        fm.dispose();
    }
}
