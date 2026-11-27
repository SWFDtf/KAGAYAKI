package de.swfdtf.graphics;

import de.swfdtf.graphics.Advanced3DRenderingServices.AmbientLightProbeGrid;
import de.swfdtf.graphics.Advanced3DRenderingServices.DepthOfField3D;
import de.swfdtf.graphics.Advanced3DRenderingServices.IblEnvironment3D;
import de.swfdtf.graphics.Advanced3DRenderingServices.Scene3DPanoramaBaker;
import de.swfdtf.graphics.Advanced3DRenderingServices.SsaoPass;
import de.swfdtf.graphics.Advanced3DRenderingServices.SubsurfaceScatteringModel;
import de.swfdtf.graphics.Advanced3DRenderingServices.VolumetricGodRaysPass;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Wave P3-2 advanced 3D rendering goals ({@code #0268}, {@code #0269}, {@code #0270},
 * {@code #0287}, {@code #0292}, {@code #0308}, {@code #0314}, {@code #0339}).
 */
class WaveP3Advanced3DRenderingTest {

    @Test
    void iblEnvironmentProvidesDepth1SkyboxAndPresetIrradiance() {
        IblEnvironment3D snow = new IblEnvironment3D(IblEnvironment3D.SkyboxPreset.SNOW_VALLEY);
        IblEnvironment3D night = new IblEnvironment3D(IblEnvironment3D.SkyboxPreset.STARRY_NIGHT);

        assertEquals(1.0f, snow.resolveSkyboxDepthNdc(), 0.0001f);
        assertTrue(IblEnvironment3D.SKYBOX_VERTEX_SHADER.contains("pos.xyww"));
        float[] snowUp = snow.sampleIrradiance(new Vector3f(0.0f, 1.0f, 0.0f));
        float[] nightUp = night.sampleIrradiance(new Vector3f(0.0f, 1.0f, 0.0f));
        assertTrue(snowUp[2] > nightUp[2]);
    }

    @Test
    void ssaoPassTogglesAndScalesWithRadiusAndIntensity() {
        SsaoPass ssao = new SsaoPass(true, 0.8f, 1.2f, 16);
        assertEquals(16, SsaoPass.generateHemisphereKernel(ssao.getSampleCount()).size());

        float occluded = ssao.evaluateOcclusion(10.0f, new float[] {9.7f, 9.8f, 9.75f, 9.9f});
        assertTrue(occluded < 1.0f);

        ssao.setEnabled(false);
        assertEquals(1.0f, ssao.evaluateOcclusion(10.0f, new float[] {9.7f, 9.8f}), 0.0001f);
    }

    @Test
    void depthOfFieldBindsToCoordinateOrNodeTag() {
        DepthOfField3D dof = new DepthOfField3D();
        Camera3D camera = new Camera3D();
        camera.setPosition(0.0f, 2.0f, 10.0f);

        Scene3DWorld world = new Scene3DWorld();
        Scene3DWorld.Node3D npc = new Scene3DWorld.Node3D(
                "speaker_snow",
                Mesh3D.createCube(1.0f),
                Material3D.defaultPbr("npc_mat", "#ffffff")
        ).setPosition(0.0f, 2.0f, 2.0f);
        world.putNode(npc);

        dof.bindFocusToNodeTag("speaker_snow");
        float focus = dof.updateFocusFromCamera(camera, world);
        assertEquals(8.0f, focus, 0.01f);
        assertEquals(0.0f, dof.computeCircleOfConfusion(8.0f), 0.0001f);
        assertTrue(dof.computeCircleOfConfusion(14.0f) > 0.0f);

        dof.bindFocusToPosition(new Vector3f(0.0f, 2.0f, 6.0f));
        assertEquals(4.0f, dof.updateFocusFromCamera(camera, world), 0.01f);
    }

    @Test
    void panoramaBakerExportsAligned2DPngLayers(@TempDir Path tempDir) throws Exception {
        Scene3DWorld world = new Scene3DWorld();
        world.getSkydome().setHourOfDay(11.0f);
        world.putNode(new Scene3DWorld.Node3D(
                "castle_tower",
                Mesh3D.createCube(2.0f),
                Material3D.defaultPbr("stone", "#c8b8a6")
        ).setPosition(0.0f, 1.0f, -4.0f));

        Camera3D cam = new Camera3D();
        cam.setPosition(0.0f, 1.5f, 4.0f);
        cam.setLookAt(0.0f, 1.0f, -4.0f);

        Scene3DPanoramaBaker baker = new Scene3DPanoramaBaker();
        List<Scene3DPanoramaBaker.BakedPanoramaLayer> layers = baker.bakeWorldTo2DLayers(
                world, cam, 320, 180, tempDir);

        assertEquals(2, layers.size());
        for (Scene3DPanoramaBaker.BakedPanoramaLayer layer : layers) {
            assertEquals(320, layer.width());
            assertEquals(180, layer.height());
            assertTrue(Files.isRegularFile(layer.pngPath()));
            byte[] bytes = Files.readAllBytes(layer.pngPath());
            assertTrue(bytes.length > 16);
            assertEquals((byte) 0x89, bytes[0]);
            assertEquals((byte) 'P', bytes[1]);
            assertEquals((byte) 'N', bytes[2]);
            assertEquals((byte) 'G', bytes[3]);
        }
    }

    @Test
    void godRaysFadeOutCleanlyBehindCamera() {
        VolumetricGodRaysPass godRays = new VolumetricGodRaysPass();
        Camera3D camera = new Camera3D();
        camera.setPosition(0.0f, 2.0f, 5.0f);
        camera.setLookAt(0.0f, 2.0f, -10.0f);

        VolumetricGodRaysPass.GodRayEvaluation inFront = godRays.evaluate(
                camera, new Vector3f(0.0f, 4.0f, -20.0f), 1920, 1080);
        assertTrue(inFront.lightVisibleInFront());
        assertTrue(inFront.effectiveIntensity() > 0.0f);

        VolumetricGodRaysPass.GodRayEvaluation behind = godRays.evaluate(
                camera, new Vector3f(0.0f, 2.0f, 25.0f), 1920, 1080);
        assertFalse(behind.lightVisibleInFront());
        assertEquals(0.0f, behind.effectiveIntensity(), 0.0001f);
    }

    @Test
    void lightProbesInterpolateSmoothlyAcrossRoomBoundary() {
        AmbientLightProbeGrid grid = new AmbientLightProbeGrid();
        float[][] warmRoom = new float[6][];
        float[][] coolCorridor = new float[6][];
        for (int f = 0; f < 6; f++) {
            warmRoom[f] = new float[] {1.0f, 0.6f, 0.25f};
            coolCorridor[f] = new float[] {0.2f, 0.4f, 0.9f};
        }
        grid.addProbe(new AmbientLightProbeGrid.LightProbe("hearth", new Vector3f(0, 1, 0), warmRoom));
        grid.addProbe(new AmbientLightProbeGrid.LightProbe("hall", new Vector3f(10, 1, 0), coolCorridor));

        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        float[] prev = grid.sampleAmbient(new Vector3f(0.0f, 1.0f, 0.0f), up);
        float maxStepDelta = 0.0f;
        for (int step = 1; step <= 20; step++) {
            float x = step * 0.5f;
            float[] curr = grid.sampleAmbient(new Vector3f(x, 1.0f, 0.0f), up);
            for (int c = 0; c < 3; c++) {
                maxStepDelta = Math.max(maxStepDelta, Math.abs(curr[c] - prev[c]));
            }
            prev = curr;
        }
        assertTrue(maxStepDelta < 0.15f, "Expected smooth transition across doorway, max step=" + maxStepDelta);
    }

    @Test
    void subsurfaceScatteringProducesWarmBacklitRimGlow() {
        SubsurfaceScatteringModel wax = new SubsurfaceScatteringModel(
                SubsurfaceScatteringModel.Preset.WAX, 1.5f);
        Vector3f normal = new Vector3f(0.0f, 0.0f, 1.0f);
        Vector3f view = new Vector3f(0.0f, 0.0f, 1.0f);
        Vector3f backLight = new Vector3f(0.0f, 0.2f, -1.0f).normalize();
        Vector3f frontLight = new Vector3f(0.0f, 0.0f, 1.0f);

        float[] backlitRgb = wax.evaluateBacklitScattering(view, backLight, normal, 0.2f);
        float[] frontlitRgb = wax.evaluateBacklitScattering(view, frontLight, normal, 0.2f);

        assertTrue(backlitRgb[0] > backlitRgb[2], "Wax glow should be warm red/amber shifted");
        assertTrue(backlitRgb[0] > frontlitRgb[0]);

        wax.setStrength(0.0f);
        float[] disabled = wax.evaluateBacklitScattering(view, backLight, normal, 0.2f);
        assertEquals(0.0f, disabled[0], 0.0001f);
    }

    @Test
    void renderDocMarkersStructureFrameWithoutErrorWhenDisabled() {
        RenderPipeline3D pipeline = new RenderPipeline3D();
        pipeline.init(1920, 1080);
        pipeline.setDebugMarkersEnabled(false);
        pipeline.beginFrame(1920, 1080);
        pipeline.renderSceneGraph(new SceneGraph());
        pipeline.endFrame();

        assertEquals(List.of("World3D", "Billboards", "PostFX", "UI"), pipeline.getLastRecordedDebugGroups());

        // Also safe when requested in headless environment without active KHR_debug
        pipeline.setDebugMarkersEnabled(true);
        pipeline.beginFrame(1920, 1080);
        pipeline.renderSceneGraph(new SceneGraph());
        pipeline.endFrame();
        assertEquals(List.of("World3D", "Billboards", "PostFX", "UI"), pipeline.getLastRecordedDebugGroups());

        Map<String, CustomShaderRegistry.ReloadResult> shaderChecks =
                Advanced3DRenderingServices.validateAllAdvancedShaders();
        assertTrue(shaderChecks.values().stream().allMatch(CustomShaderRegistry.ReloadResult::valid));
    }
}
