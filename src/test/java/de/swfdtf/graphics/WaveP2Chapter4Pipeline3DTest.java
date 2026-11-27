package de.swfdtf.graphics;

import de.swfdtf.core.EngineLogger;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless verification suite for Wave P2-4 Chapter 4 3D Pipeline, Hybrid 2D+3D, Camera,
 * SceneWorld, Materials, Lighting, Fog, Skydome, Raycasting, and Mesh Import.
 */
class WaveP2Chapter4Pipeline3DTest {

    @Test
    @DisplayName("RenderPipeline3D lifecycle init, beginFrame, draw submit, endFrame, dispose")
    void renderPipelineLifecycleAndMode() {
        RenderPipeline3D pipeline = new RenderPipeline3D(new EngineLogger());
        assertEquals(RenderMode.MODE_3D, pipeline.getMode());
        assertFalse(pipeline.isInitialized());

        pipeline.init(1920, 1080);
        assertTrue(pipeline.isInitialized());

        Mesh3D cube = Mesh3D.createCube(1.0f);
        Material3D mat = Material3D.defaultPbr("stone", "#aabbcc");
        pipeline.getWorld().putNode(new Scene3DWorld.Node3D("cube1", cube, mat).setPosition(0.0f, 0.0f, -5.0f));
        pipeline.getCamera().setPosition(0.0f, 1.0f, 4.0f);
        pipeline.getCamera().setLookAt(0.0f, 0.0f, -5.0f);

        pipeline.beginFrame(1280, 720);
        pipeline.renderSceneGraph(new SceneGraph());
        pipeline.endFrame();

        assertEquals(1, pipeline.getLastSubmittedDrawCalls());
        pipeline.dispose();
        assertFalse(pipeline.isInitialized());
    }

    @Test
    @DisplayName("RenderPipeline3D verifies std140 UBO layout (176 bytes), shaders, and pass order")
    void pipelineUboShadersAndCompositeOrder() {
        assertEquals(176, RenderPipeline3D.GLOBAL_CAMERA_UBO_STD140_BYTES);
        assertTrue(RenderPipeline3D.verifyStd140UboAlignment());

        List<CustomShaderRegistry.ReloadResult> shaderResults = RenderPipeline3D.validateCore3DShaders();
        assertEquals(2, shaderResults.size());
        for (CustomShaderRegistry.ReloadResult result : shaderResults) {
            assertTrue(result.valid(), "Shader validation failed: " + result.message());
        }
        assertEquals(List.of("Sky2D", "World3D", "Billboards", "Foreground2D", "UI"),
                RenderPipeline3D.COMPOSITE_PASS_ORDER);
    }

    @Test
    @DisplayName("RenderPipeline3D scales internal resolution between 0.5x and 1.5x")
    void pipelineInternalResolutionScaling() {
        RenderPipeline3D pipeline = new RenderPipeline3D(new EngineLogger());
        pipeline.setRenderResolutionScale(0.75f);
        int[] scaled = pipeline.computeInternalRenderSize(1920, 1080);
        assertEquals(1440, scaled[0]);
        assertEquals(810, scaled[1]);

        pipeline.setRenderResolutionScale(0.1f);
        assertEquals(0.5f, pipeline.getRenderResolutionScale(), 0.001f);
        pipeline.setRenderResolutionScale(3.0f);
        assertEquals(1.5f, pipeline.getRenderResolutionScale(), 0.001f);
    }

    @Test
    @DisplayName("RenderPipeline3D supports MSAA, shadow quality presets, debug views, and exposure/gamma")
    void pipelineQualityAndDebugModes() {
        RenderPipeline3D pipeline = new RenderPipeline3D(new EngineLogger());
        pipeline.setMsaaSamples(4);
        assertEquals(4, pipeline.getMsaaSamples());
        pipeline.setShadowQuality(RenderPipeline3D.ShadowQuality.HIGH);
        assertEquals(2048, pipeline.getShadowQuality().getResolution());
        pipeline.setDepthPrepassEnabled(false);
        assertFalse(pipeline.isDepthPrepassEnabled());
        pipeline.setDebugViewMode(RenderPipeline3D.DebugViewMode.NORMALS);
        assertEquals(RenderPipeline3D.DebugViewMode.NORMALS, pipeline.getDebugViewMode());
        pipeline.setExposureAndGamma(1.4f, 2.2f);
        assertEquals(1.4f, pipeline.getExposure(), 0.001f);
        assertEquals(2.2f, pipeline.getGamma(), 0.001f);
    }

    @Test
    @DisplayName("RenderPipeline3D supports seamless 2D backdrop fallback mode")
    void pipelineBackdropFallbackSkips3dDraws() {
        RenderPipeline3D pipeline = new RenderPipeline3D(new EngineLogger());
        pipeline.init(1920, 1080);
        pipeline.setFallbackTo2dBackdrop(true);
        assertTrue(pipeline.isFallbackTo2dBackdrop());

        pipeline.getWorld().putNode(new Scene3DWorld.Node3D("crate", Mesh3D.createCube(1.0f),
                Material3D.defaultPbr("crate", "#ffffff")).setPosition(0, 0, -4));
        pipeline.beginFrame(1920, 1080);
        pipeline.renderSceneGraph(new SceneGraph());
        pipeline.endFrame();
        assertEquals(0, pipeline.getLastSubmittedDrawCalls());
        pipeline.dispose();
    }

    @Test
    @DisplayName("Mesh3D procedural primitives create valid vertex counts, indices, and AABBs")
    void meshPrimitivesAndBounds() {
        Mesh3D plane = Mesh3D.createPlane(4.0f, 6.0f);
        Mesh3D cube = Mesh3D.createCube(2.0f);
        Mesh3D sphere = Mesh3D.createSphere(1.5f, 8, 12);
        Mesh3D cyl = Mesh3D.createCylinder(1.0f, 3.0f, 12);

        assertEquals(2, plane.getTriangleCount());
        assertEquals(12, cube.getTriangleCount());
        assertTrue(sphere.getVertexCount() > 30);
        assertTrue(cyl.getTriangleCount() >= 24);
        assertTrue(cube.getBoundingBox().minX() < cube.getBoundingBox().maxX());
        assertTrue(cube.getBoundingSphere().radius() >= 1.5f);
    }

    @Test
    @DisplayName("Mesh3D parses Wavefront OBJ + MTL files and loads from disk")
    void objAndMtlParser(@TempDir Path tempDir) throws IOException {
        String mtl = """
                newmtl marble
                Kd 0.8 0.75 0.7
                Ks 0.4 0.4 0.4
                Ns 64.0
                d 1.0
                """;
        String obj = """
                mtllib hall.mtl
                usemtl marble
                v 0 0 0
                v 1 0 0
                v 0 1 0
                vt 0 0
                vt 1 0
                vt 0 1
                vn 0 0 1
                f 1/1/1 2/2/1 3/3/1
                """;
        Path objFile = tempDir.resolve("hall.obj");
        Files.writeString(tempDir.resolve("hall.mtl"), mtl, StandardCharsets.UTF_8);
        Files.writeString(objFile, obj, StandardCharsets.UTF_8);

        Mesh3D loaded = Mesh3D.loadObj(objFile);
        assertEquals(3, loaded.getVertexCount());
        assertEquals(1, loaded.getTriangleCount());
        assertEquals(1, loaded.getSubMeshes().size());
        assertTrue(loaded.getParsedMaterials().containsKey("marble"));
        assertEquals(0.8f, loaded.getParsedMaterials().get("marble").getAlbedoRgba()[0], 0.01f);
    }

    @Test
    @DisplayName("Mesh3D parses valid binary GLB container with positions and indices")
    void binaryGlbParser() throws IOException {
        byte[] binChunk = new byte[36 + 6];
        ByteBuffer binBuf = ByteBuffer.wrap(binChunk).order(ByteOrder.LITTLE_ENDIAN);
        binBuf.putFloat(0f).putFloat(0f).putFloat(0f);
        binBuf.putFloat(1f).putFloat(0f).putFloat(0f);
        binBuf.putFloat(0f).putFloat(1f).putFloat(0f);
        binBuf.putShort((short) 0).putShort((short) 1).putShort((short) 2);
        int binPaddedLen = (binChunk.length + 3) & ~3;

        String json = """
                {
                  "asset":{"version":"2.0"},
                  "buffers":[{"byteLength":%d}],
                  "bufferViews":[
                    {"buffer":0,"byteOffset":0,"byteLength":36},
                    {"buffer":0,"byteOffset":36,"byteLength":6}
                  ],
                  "accessors":[
                    {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
                    {"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}
                  ],
                  "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}]
                }
                """.formatted(binChunk.length);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int jsonPaddedLen = (jsonBytes.length + 3) & ~3;

        int totalBytes = 12 + 8 + jsonPaddedLen + 8 + binPaddedLen;
        ByteArrayOutputStream out = new ByteArrayOutputStream(totalBytes);
        ByteBuffer hdr = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        hdr.putInt(0x46546C67).putInt(2).putInt(totalBytes);
        out.write(hdr.array());

        ByteBuffer jHdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        jHdr.putInt(jsonPaddedLen).putInt(0x4E4F534A);
        out.write(jHdr.array());
        out.write(jsonBytes);
        for (int i = jsonBytes.length; i < jsonPaddedLen; i++) {
            out.write(' ');
        }

        ByteBuffer bHdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        bHdr.putInt(binPaddedLen).putInt(0x004E4942);
        out.write(bHdr.array());
        out.write(binChunk);
        for (int i = binChunk.length; i < binPaddedLen; i++) {
            out.write(0);
        }

        Mesh3D mesh = Mesh3D.parseGlb(out.toByteArray(), "tri_glb");
        assertEquals(3, mesh.getVertexCount());
        assertEquals(1, mesh.getTriangleCount());
    }

    @Test
    @DisplayName("Mesh3D computes tangent vectors, smooth normals, and honors upload budget")
    void meshTangentsSmoothNormalsAndUploadBudget() {
        Mesh3D cube = Mesh3D.createCube(1.0f);
        assertEquals(cube.getVertexCount() * 4, cube.getTangents().length);
        assertEquals(cube.getVertexCount() * 3, cube.getSmoothNormals().length);

        Mesh3D.MeshUploadQueue queue = new Mesh3D.MeshUploadQueue(0.5);
        for (int i = 0; i < 5; i++) {
            queue.enqueue(cube);
        }
        assertTrue(queue.processFrameBudget() >= 1);
        assertTrue(queue.getUploadedCount() >= 1);
    }

    @Test
    @DisplayName("Material3D evaluates Cook-Torrance GGX NDF, Fresnel-Schlick, Toon bands, and overrides")
    void materialPbrToonAndOverrides() {
        assertTrue(Material3D.evaluateCookTorranceGgxNdf(1.0f, 0.1f)
                > Material3D.evaluateCookTorranceGgxNdf(1.0f, 0.9f));
        assertEquals(1.0f, Material3D.evaluateFresnelSchlick(0.0f, 0.04f), 0.01f);
        float toon = Material3D.evaluateToonQuantization(0.55f, 3);
        assertTrue(toon >= 0.3f && toon <= 1.0f);

        Material3D custom = Material3D.defaultPbr("bronze", "#aa6633").withOverrides("#224488", 0.25f, 0.85f);
        assertEquals(0.25f, custom.getRoughness(), 0.001f);
        assertEquals(0.85f, custom.getMetallic(), 0.001f);
        assertEquals(Material3D.ShaderStyle.WATER, new Material3D(
                "pond", Material3D.ShaderStyle.WATER, new float[]{0.2f, 0.5f, 0.8f, 0.7f},
                0.0f, 0.1f, 0.0f, 3, 0.5f, true, null, null).getStyle());
    }

    @Test
    @DisplayName("Camera3D clamps FOV [50..110], switches perspective/orthographic, and interpolates moveTo")
    void cameraProjectionFovAndMoveTo() {
        Camera3D cam = new Camera3D();
        cam.setFovDegrees(20.0f);
        assertEquals(50.0f, cam.getFovDegrees(), 0.01f);
        cam.setFovDegrees(140.0f);
        assertEquals(110.0f, cam.getFovDegrees(), 0.01f);

        Matrix4f persp = cam.getProjectionMatrix(16f / 9f);
        cam.setProjectionMode(Camera3D.ProjectionMode.ORTHOGRAPHIC);
        Matrix4f ortho = cam.getProjectionMatrix(16f / 9f);
        assertFalse(persp.equals(ortho));

        cam.setPosition(0f, 1f, 5f);
        cam.moveTo(new Vector3f(4f, 3f, -2f), new Vector3f(0f, 1f, -2f), 1.0);
        cam.update(0.5);
        assertEquals(2.0f, cam.getPosition().x, 0.2f);
        cam.update(0.6);
        assertEquals(4.0f, cam.getPosition().x, 0.05f);
    }

    @Test
    @DisplayName("Camera3D WASD input clamps pitch to ±89° and toggles strafe tilt")
    void cameraWasdPitchClampAndStrafeTilt() {
        Camera3D cam = new Camera3D();
        cam.applyWasdInput(1.0f, 1.0f, 0.0f, 10000.0f, 5.0f, 0.16);
        assertTrue(Math.abs(cam.getPitchDegrees()) <= 89.01f);
        assertTrue(Math.abs(cam.getCurrentStrafeTiltDegrees()) > 0.0f);

        cam.setStrafeTiltEnabled(false);
        assertEquals(0.0f, cam.getCurrentStrafeTiltDegrees(), 0.001f);
    }

    @Test
    @DisplayName("Camera3D trauma shake decays over time and SpringArm pulls inside wall AABB")
    void cameraTraumaAndSpringArmWallCollision() {
        Camera3D cam = new Camera3D();
        cam.addTrauma(0.8f);
        float initial = cam.getTrauma();
        cam.update(0.5);
        assertTrue(cam.getTrauma() < initial);

        Mesh3D.BoundingBox3D wall = new Mesh3D.BoundingBox3D(-2, -2, -1, 2, 2, 1);
        Vector3f resolved = Camera3D.resolveSpringArmPosition(
                new Vector3f(0, 0, -5), new Vector3f(0, 0, 10), List.of(wall));
        assertTrue(resolved.z < 5.0f);
    }

    @Test
    @DisplayName("Camera3D supports CameraRail spline, ShotPreset hold time, dialog focus & stereo eyes")
    void cameraRailShotPresetDialogAndStereo() {
        Camera3D.CameraRail rail = new Camera3D.CameraRail(List.of(
                new Vector3f(0, 1, 0), new Vector3f(0, 1, -10), new Vector3f(10, 2, -10)));
        assertEquals(-10.0f, rail.samplePosition(0.5f).z, 0.1f);
        assertNotNull(rail.sampleTangent(0.25f));

        Camera3D cam = new Camera3D();
        Camera3D.ShotPreset shotA = new Camera3D.ShotPreset("a", new Vector3f(-1, 1.5f, 3),
                new Vector3f(0, 1.5f, 0), 2.0);
        Camera3D.ShotPreset shotB = new Camera3D.ShotPreset("b", new Vector3f(1, 1.5f, 3),
                new Vector3f(0, 1.5f, 0), 2.0);
        assertTrue(cam.triggerShotPreset(shotA, 10.0));
        assertFalse(cam.triggerShotPreset(shotB, 10.5));
        assertTrue(cam.triggerShotPreset(shotB, 12.5));

        cam.enterDialogFocus(new Vector3f(0, 1.6f, -3), new Vector3f(0, 0.2f, 2), 0.2);
        cam.update(0.25);
        cam.exitDialogFocus(0.2);
        cam.update(0.25);
        assertEquals(2, cam.computeStereoViewMatrices(0.064f).length);
    }

    @Test
    @DisplayName("Camera3D frustum AABB culling, mouse ray unprojection, and world projection")
    void cameraFrustumRayAndProjection() {
        Camera3D cam = new Camera3D();
        cam.setPosition(0f, 0f, 5f);
        cam.setLookAt(0f, 0f, 0f);
        assertTrue(cam.isAabbInFrustum(new Mesh3D.BoundingBox3D(-1, -1, -1, 1, 1, 1), 16f / 9f));
        assertFalse(cam.isAabbInFrustum(new Mesh3D.BoundingBox3D(-1, -1, 50, 1, 1, 52), 16f / 9f));

        Camera3D.Ray3D centerRay = cam.screenPointToRay(960, 540, 1920, 1080);
        assertTrue(Camera3D.rayIntersectsAabbDistance(
                centerRay, new Mesh3D.BoundingBox3D(-1, -1, -1, 1, 1, 1)) > 0.0f);
        assertEquals(960.0f, cam.projectWorldPoint(new Vector3f(0, 0, 0), 1920, 1080)[0], 5.0f);
    }

    @Test
    @DisplayName("Scene3DWorld LOD hysteresis avoids threshold flickering and swaps meshes cleanly")
    void worldLodHysteresisAndBillboards() {
        Mesh3D high = Mesh3D.createSphere(1.0f, 12, 16);
        Mesh3D low = Mesh3D.createCube(1.0f);
        Scene3DWorld.Node3D statue = new Scene3DWorld.Node3D("statue", high, Material3D.defaultPbr("s", "#ccc"))
                .setBillboard2d(true);
        statue.configureLod(low, 20.0f, 2.0f);

        assertEquals(0, statue.selectLod(10.0f));
        assertEquals(1, statue.selectLod(25.0f));
        assertEquals(1, statue.selectLod(19.0f));
        assertEquals(0, statue.selectLod(15.0f));
        assertSame(high, statue.getActiveMesh());
        assertNotNull(statue.computeBillboardNormalTowardCamera(new Vector3f(0, 1, 5)));
    }

    @Test
    @DisplayName("Scene3DWorld caps local lights to nearest 8 and sorts transparent nodes back-to-front")
    void worldLightsAndTransparentSortOrder() {
        Scene3DWorld world = new Scene3DWorld();
        Mesh3D cube = Mesh3D.createCube(1.0f);
        Material3D opaque = Material3D.defaultPbr("opaque", "#aaaaaa");
        Material3D glass = new Material3D("glass", Material3D.ShaderStyle.WATER,
                new float[]{0.4f, 0.7f, 1.0f, 0.5f}, 0f, 0.1f, 0f, 3, 0.5f, true, null, null);

        world.putNode(new Scene3DWorld.Node3D("far_glass", cube, glass).setPosition(0, 0, -10));
        world.putNode(new Scene3DWorld.Node3D("near_glass", cube, glass).setPosition(0, 0, -2));
        world.putNode(new Scene3DWorld.Node3D("opaque", cube, opaque).setPosition(0, 0, -6));

        for (int i = 0; i < 12; i++) {
            world.putLight(new Scene3DWorld.Light3D("l" + i, false,
                    new Vector3f(i * 2f, 1f, 0f), new float[]{1, 1, 1}, 10f, 1f));
        }
        assertEquals(8, world.selectTopNearestLights(new Vector3f(0, 0, 0), 8).size());

        List<Scene3DWorld.Node3D> sorted = world.getSortedDrawNodes(new Vector3f(0, 0, 5));
        assertEquals("opaque", sorted.get(0).getId());
        assertEquals("far_glass", sorted.get(1).getId());
        assertEquals("near_glass", sorted.get(2).getId());
    }

    @Test
    @DisplayName("Scene3DWorld ray picks clickable 3D props and slides movement along solid walls")
    void worldRayPickAndWallCollision() {
        Scene3DWorld world = new Scene3DWorld();
        Mesh3D cube = Mesh3D.createCube(1.0f);
        Material3D mat = Material3D.defaultPbr("stone", "#cccccc");
        world.putNode(new Scene3DWorld.Node3D("clue", cube, mat).setPosition(0, 0, -5)
                .setClickLabelTarget("inspect_clue"));
        world.putNode(new Scene3DWorld.Node3D("wall", cube, mat).setPosition(3, 0, 0).setCollisionSolid(true));

        Camera3D cam = new Camera3D();
        cam.setPosition(0, 0, 5);
        cam.setLookAt(0, 0, -5);
        Scene3DWorld.Node3D hit = world.pickNode(cam.screenPointToRay(960, 540, 1920, 1080));
        assertNotNull(hit);
        assertEquals("inspect_clue", hit.getClickLabelTarget());
        assertNull(world.pickNode(cam.screenPointToRay(10, 10, 1920, 1080)));

        Vector3f slid = world.moveWithWallCollision(new Vector3f(1.5f, 0, 0), new Vector3f(2.0f, 0, 1.0f), 0.4f);
        assertTrue(slid.z > 0.0f);
    }

    @Test
    @DisplayName("Scene3DWorld supports Skydome3D cycle, Fog3DConfig lerp, and planar reflection matrix")
    void worldSkydomeFogAndReflection() {
        Scene3DWorld world = new Scene3DWorld();
        world.getSkydome().setHourOfDay(23.0f);
        assertTrue(world.getSkydome().computeStarIntensity() > 0.5f);
        assertNotNull(world.getSkydome().computeSunDirection());

        Scene3DWorld.Fog3DConfig fog = new Scene3DWorld.Fog3DConfig(true, new float[]{0.2f, 0.3f, 0.5f}, 0.05f, 0.1f);
        Scene3DWorld.Fog3DConfig lerped = Scene3DWorld.Fog3DConfig.NONE.lerp(fog, 0.5f);
        world.setFogConfig(lerped);
        assertEquals(0.025f, world.getFogConfig().density(), 0.001f);

        Matrix4f refl = Scene3DWorld.computePlanarReflectionMatrix(new Vector3f(0, 0, 0), new Vector3f(0, 1, 0));
        assertEquals(-3.0f, refl.transformPosition(new Vector3f(1, 3, 2)).y, 0.01f);
    }

    @Test
    @DisplayName("Scene3DWorld snapshot save/restore, inspect item overlay, minimap, preloader & reset")
    void worldSnapshotInspectMinimapAndReset() {
        Scene3DWorld world = new Scene3DWorld();
        Camera3D cam = new Camera3D();
        cam.setPosition(2f, 3f, 7f);
        world.putNode(new Scene3DWorld.Node3D("vase", Mesh3D.createCube(1.0f),
                Material3D.defaultPbr("vase", "#ffffff")).setPosition(0, 0, -5));

        Scene3DWorld.Scene3DSnapshot snap = world.captureSnapshot(cam);
        world.getNode("vase").orElseThrow().setPosition(99, 99, 99);
        cam.setPosition(0, 0, 0);
        world.restoreSnapshot(snap, cam);
        assertEquals(-5.0f, world.getNode("vase").orElseThrow().getPosition().z, 0.01f);
        assertEquals(7.0f, cam.getPosition().z, 0.01f);

        world.getInspectOverlay().open("golden_key");
        world.getInspectOverlay().orbit(45f, 10f, 0.5f);
        assertTrue(world.getInspectOverlay().isOpen());
        world.getInspectOverlay().close();

        Scene3DWorld.Minimap3DProjector minimap = new Scene3DWorld.Minimap3DProjector(-10, 10, -10, 10, 0, 0, 200, 200);
        assertEquals(100.0f, minimap.projectWorldXzToMap(0f, 0f)[0], 0.1f);

        Scene3DWorld.Scene3DPreloader preloader = new Scene3DWorld.Scene3DPreloader(2);
        preloader.markAssetLoaded();
        assertEquals(0.5f, preloader.getProgress(), 0.01f);

        world.reset();
        assertEquals(0, world.getActiveMeshCount());
    }
}
