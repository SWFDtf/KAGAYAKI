package de.swfdtf.graphics;

import de.swfdtf.core.EngineLogger;
import de.swfdtf.math.ColorMatrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaveP2Chapter3GraphicsPipelineTest {

    @Test
    void premultipliedRgbaInvariantPreventsDarkFringing() {
        byte[] straight = new byte[]{
            (byte) 200, (byte) 100, (byte) 50, (byte) 128,
            (byte) 255, (byte) 255, (byte) 255, (byte) 0
        };
        byte[] premul = Texture.premultiplyRgba(straight);
        assertTrue(Texture.verifyPremultipliedBuffer(premul));
        assertEquals(0, premul[4] & 0xFF);
        assertEquals(0, premul[5] & 0xFF);
        assertEquals(0, premul[6] & 0xFF);
        assertFalse(Texture.verifyPremultipliedBuffer(new byte[]{(byte) 200, 0, 0, 10}));

        byte[] roundTrip = Texture.unpremultiplyRgba(premul);
        assertTrue(Math.abs((roundTrip[0] & 0xFF) - 200) <= 2);
        assertEquals(0, roundTrip[4] & 0xFF);
    }

    @Test
    void textureManagerLruBudgetPersistentPinAndUploadCapWork(@TempDir Path dir) {
        TextureManager mgr = new TextureManager(new EngineLogger(), dir);
        Texture uiFrame = new Texture(11, 100, 100, "ui/frame.png", true);
        Texture bg1 = new Texture(12, 1000, 1000, "bg/room1.png", true);
        Texture bg2 = new Texture(13, 1000, 1000, "bg/room2.png", true);

        mgr.pinPersistent("ui/frame.png");
        mgr.putCachedTexture("ui/frame.png", uiFrame);
        mgr.putCachedTexture("bg/room1.png", bg1);
        mgr.setMaxMemoryBudgetBytes(4_200_000L);
        assertTrue(mgr.isCacheHit("ui/frame.png"));
        mgr.putCachedTexture("bg/room2.png", bg2);

        assertTrue(mgr.isPersistentPinned("ui/frame.png"));
        assertTrue(mgr.isCacheHit("ui/frame.png"));
        assertTrue(mgr.getCurrentMemoryBytes() <= 4_200_000L);

        assertTrue(mgr.clearTransientSceneTextures() >= 0);
        assertTrue(mgr.isCacheHit("ui/frame.png"));
        mgr.clearCache();
    }

    @Test
    void colorMatrixPresetsAndTimeOfDayGradingComposeAndReverse() {
        ColorMatrix4f sepia = ColorMatrix4f.sepia();
        ColorMatrix4f gray = ColorMatrix4f.grayscale();
        ColorMatrix4f inverted = ColorMatrix4f.invert();
        float[] px = inverted.applyPremultiplied(0.25f, 0.50f, 0.75f, 1.0f);
        assertArrayEquals(new float[]{0.75f, 0.50f, 0.25f, 1.0f}, px, 1e-4f);
        float[] restored = inverted.multiply(inverted).applyPremultiplied(0.25f, 0.50f, 0.75f, 1.0f);
        assertArrayEquals(new float[]{0.25f, 0.50f, 0.75f, 1.0f}, restored, 1e-4f);

        ColorMatrix4f warmCandle = ColorMatrix4f.colorTemperature(2400.0f);
        ColorMatrix4f day = ColorMatrix4f.timeOfDay(ColorMatrix4f.TimeOfDayPreset.NOON);
        ColorMatrix4f night = ColorMatrix4f.timeOfDay(ColorMatrix4f.TimeOfDayPreset.NIGHT);
        ColorMatrix4f dusk = day.lerp(night, 0.5f);
        assertNotEquals(day, night);
        assertNotEquals(day, dusk);
        assertNotEquals(ColorMatrix4f.identity(), sepia);
        assertNotEquals(ColorMatrix4f.identity(), gray);
        assertNotEquals(ColorMatrix4f.identity(), warmCandle);
    }

    @Test
    void nineSliceHalfTexelInsetAndUvScrollAndSilhouetteWork() {
        NineSliceDrawable nine = new NineSliceDrawable(99, 64.0f, 64.0f, 16.0f, 16.0f, 16.0f, 16.0f, true);
        List<NineSliceDrawable.SliceQuad> slices = nine.computeSlices(0.0f, 0.0f, 320.0f, 180.0f);
        assertEquals(9, slices.size());
        assertTrue(slices.get(0).uvRect().x() > 0.0f);
        assertTrue(slices.get(8).uvRect().right() < 1.0f);

        SpriteNode sprite = new SpriteNode("train_bg");
        sprite.setUvScrollSpeed(0.6f, 0.0f);
        sprite.updateUvScroll(1.0);
        assertEquals(0.6f, sprite.getUvScrollOffsetU(), 1e-4f);
        sprite.updateUvScroll(1.0);
        assertEquals(0.2f, sprite.getUvScrollOffsetU(), 1e-4f);

        sprite.setTintSolid("#0a0e1a");
        assertTrue(sprite.isSilhouetteEnabled());
        assertEquals(0x0A / 255.0f, sprite.getSilhouetteColorRgba()[0], 1e-3f);
        sprite.clearTintSolid();
        assertFalse(sprite.isSilhouetteEnabled());

        sprite.setBlendMode(SceneNode.BlendMode.ADDITIVE);
        assertEquals(SceneNode.BlendMode.ADDITIVE, sprite.getBlendMode());
        sprite.setHovered(true).setOutlineHighlight(3.0f, "#ffd859");
        assertTrue(sprite.isHovered());
        assertEquals(3.0f, sprite.getOutlineThickness(), 1e-4f);
        sprite.setPixelArtSampling(true);
        assertTrue(sprite.isPixelArtSampling());
    }

    @Test
    void dynamicAtlasAutoCropPreservesOffsetAndPixelPosition() {
        byte[] canvas = new byte[10 * 10 * 4];
        int targetIdx = (6 * 10 + 3) * 4;
        canvas[targetIdx] = (byte) 255;
        canvas[targetIdx + 1] = 100;
        canvas[targetIdx + 2] = 50;
        canvas[targetIdx + 3] = (byte) 255;

        DynamicTextureAtlas.TrimmedRgba trimmed = DynamicTextureAtlas.trimTransparentBorders(10, 10, canvas);
        assertEquals(3, trimmed.trimOffsetX());
        assertEquals(6, trimmed.trimOffsetY());
        assertEquals(1, trimmed.trimmedWidth());
        assertEquals(1, trimmed.trimmedHeight());
        assertTrue(trimmed.savedBytes() > 0);
    }

    @Test
    void renderPipelineConfigZeroAllocBatchAndHeadlessThumbnailWork() {
        RenderPipeline2D pipeline = new RenderPipeline2D();
        pipeline.setClearColor(0.95f, 0.90f, 0.80f, 1.0f);
        assertEquals(0.95f, pipeline.getClearColorRgba()[0], 1e-4f);

        RenderPipeline2D.ExtendedPostProcessConfig ext = RenderPipeline2D.ExtendedPostProcessConfig.NONE;
        assertEquals(0.0f, ext.chromaticAberration());
        assertEquals(0.0f, ext.vignetteIntensity());

        List<CustomShaderRegistry.ReloadResult> coreReport = RenderPipeline2D.precompileValidateCoreShaders();
        assertEquals(5, coreReport.size());
        assertTrue(coreReport.stream().allMatch(CustomShaderRegistry.ReloadResult::valid));

        long startNs = System.nanoTime();
        int count = pipeline.prepareZeroAllocationBatch(10_000);
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        assertEquals(10_000, count);
        assertTrue(elapsedMs < 250, "Expected fast 10k batch prep, took ms=" + elapsedMs);

        SceneGraph graph = new SceneGraph();
        SceneNode bg = new SceneNode("bg");
        bg.setSize(1920, 1080).setFillColor(0.2f, 0.6f, 0.9f, 1.0f);
        graph.addNode(SceneGraph.LAYER_BACKGROUND, bg);

        SceneNode uiBox = new SceneNode("ui_box");
        uiBox.setSize(800, 200).setFillColor(1.0f, 0.0f, 0.0f, 1.0f);
        uiBox.getTransform().setXpos(100).setYpos(800);
        graph.addNode(SceneGraph.LAYER_SCREENS, uiBox);

        byte[] thumb = pipeline.captureSceneThumbnailRgba(graph, 320, 180);
        assertEquals(320 * 180 * 4, thumb.length);
        int sampleIdx = (160 * 320 + 60) * 4;
        assertTrue((thumb[sampleIdx + 2] & 0xFF) > (thumb[sampleIdx] & 0xFF),
                "Thumbnail must exclude UI screens overlay");
    }

    @Test
    void layeredImageOfflineCompositingAndBlinkBreathingPauseWork(@TempDir Path dir) throws Exception {
        LayeredImage img = LayeredImage.builder("hero")
                .always("base.png")
                .attribute("eyes", "open", true, "eyes_open.png")
                .attribute("eyes", "closed", false, "eyes_closed.png")
                .build();
        img.enableEyeBlink("eyes", "open", "closed", 2.0, 0.25);
        img.enableBreathing(0.02f, 0.5f);

        assertFalse(img.isCurrentlyBlinking());
        img.update(2.1);
        assertTrue(img.isCurrentlyBlinking());
        assertNotEquals(1.0f, img.getCurrentBreathingYZoom(), 1e-4f);

        img.setAutomationPaused(true);
        float frozenZoom = img.getCurrentBreathingYZoom();
        img.update(1.0);
        assertEquals(frozenZoom, img.getCurrentBreathingYZoom(), 1e-6f);

        byte[] basePx = new byte[]{
            100, 100, 100, (byte) 255, 100, 100, 100, (byte) 255,
            100, 100, 100, (byte) 255, 100, 100, 100, (byte) 255
        };
        byte[] closedEyesPx = new byte[]{
            0, (byte) 200, 0, (byte) 255, 0, (byte) 200, 0, (byte) 255,
            0, (byte) 200, 0, (byte) 255, 0, (byte) 200, 0, (byte) 255
        };
        Path outPng = dir.resolve("portrait_export.png");
        img.exportCompositedPng(outPng, 2, 2, Map.of("base.png", basePx, "eyes_closed.png", closedEyesPx));
        assertTrue(Files.isRegularFile(outPng));
        assertTrue(Files.size(outPng) > 0);
        assertNull(img.getBoundLipSync());
    }

    @Test
    void cameraDirectorZoomToNodeFocusesCleanlyAndScreenshotFlips() {
        CameraDirectorPresets camera = new CameraDirectorPresets();
        SpriteNode lena = new SpriteNode("lena");
        lena.setSize(400, 800);
        lena.getTransform().setXpos(1400).setYpos(500);
        camera.zoomToNode(lena, 1.35f, 0.4, 1920.0f, 1080.0f);
        camera.update(0.4);
        assertEquals(1.35f, camera.getCurrentState().zoom(), 1e-4f);
        assertTrue(camera.getCurrentState().x() > 0.0f);

        byte[] flipped = ScreenshotCaptureService.flipVerticalRgba(new byte[]{
            1, 2, 3, 4,
            5, 6, 7, 8
        }, 1, 2);
        assertArrayEquals(new byte[]{5, 6, 7, 8, 1, 2, 3, 4}, flipped);
    }
}
