package de.swfdtf.graphics;

import de.swfdtf.audio.AudioEngine;
import de.swfdtf.graphics.Scene3DAnimationAndPhysicsServices.Camera3DAudioListenerBridge;
import de.swfdtf.graphics.Scene3DAnimationAndPhysicsServices.ParticleSystem3D;
import de.swfdtf.graphics.Scene3DAnimationAndPhysicsServices.RigidBody3DPropSimulator;
import de.swfdtf.graphics.Scene3DAnimationAndPhysicsServices.Skeletal3DAnimator;
import de.swfdtf.graphics.Scene3DAnimationAndPhysicsServices.VertexWindSystem;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Wave P3-3 goals ({@code #0276}, {@code #0281}, {@code #0282},
 * {@code #0283}, {@code #0299}, {@code #0328}).
 */
class WaveP3AnimationParticlesInstancingPhysicsAudioTest {

    @Test
    void skeletal3DAnimatorEnforces64BoneLimitAndCrossfadesSmoothly() {
        List<Skeletal3DAnimator.Joint3D> tooMany = java.util.stream.IntStream.range(0, 65)
                .mapToObj(i -> new Skeletal3DAnimator.Joint3D(
                        i, "j" + i, i - 1, new Matrix4f(), new Vector3f(), new Quaternionf(), new Vector3f(1, 1, 1)))
                .toList();
        assertThrows(IllegalArgumentException.class, () -> new Skeletal3DAnimator(tooMany));

        Skeletal3DAnimator.Joint3D root = new Skeletal3DAnimator.Joint3D(
                0, "root", -1, new Matrix4f(), new Vector3f(), new Quaternionf(), new Vector3f(1, 1, 1));
        Skeletal3DAnimator.Joint3D door = new Skeletal3DAnimator.Joint3D(
                1, "door", 0, new Matrix4f(), new Vector3f(0, 0, 0), new Quaternionf(), new Vector3f(1, 1, 1));

        Skeletal3DAnimator animator = new Skeletal3DAnimator(List.of(root, door))
                .registerClip(new Skeletal3DAnimator.AnimationClip3D("closed", 1.0, true, Map.of(
                        1, List.of(new Skeletal3DAnimator.JointKeyframe(
                                0.0, new Vector3f(0, 0, 0), new Quaternionf(), new Vector3f(1, 1, 1)))
                )))
                .registerClip(new Skeletal3DAnimator.AnimationClip3D("open", 1.0, false, Map.of(
                        1, List.of(
                                new Skeletal3DAnimator.JointKeyframe(
                                        0.0, new Vector3f(0, 0, 0), new Quaternionf(), new Vector3f(1, 1, 1)),
                                new Skeletal3DAnimator.JointKeyframe(
                                        1.0, new Vector3f(2, 0, 0), new Quaternionf(), new Vector3f(1, 1, 1)))
                )));

        animator.crossfadeTo("open", 0.5);
        animator.update(0.25);
        float weight = animator.getCrossfadeWeight();
        assertEquals(0.5f, weight, 0.01f);

        List<Matrix4f> skin = animator.computeSkinningMatrices();
        assertEquals(2, skin.size());
        float doorTx = skin.get(1).m30();
        assertTrue(doorTx > 0.1f && doorTx < 1.0f, "Expected smooth crossfade translation, got " + doorTx);
    }

    @Test
    void particles3DScaleCloserSnowflakesLargerAndSortBackToFront() {
        Camera3D cam = new Camera3D();
        cam.setPosition(0.0f, 1.0f, 5.0f);
        cam.setLookAt(0.0f, 1.0f, -10.0f);

        ParticleSystem3D ps = new ParticleSystem3D()
                .addParticle(new ParticleSystem3D.Particle3D(
                        new Vector3f(0.0f, 1.0f, 3.0f), new Vector3f(), 0.2f, 1.0f, 5.0))
                .addParticle(new ParticleSystem3D.Particle3D(
                        new Vector3f(0.0f, 1.0f, -5.0f), new Vector3f(), 0.2f, 1.0f, 5.0));

        List<ParticleSystem3D.ProjectedBillboard3D> projected = ps.projectBillboards(cam, 1920, 1080);
        assertEquals(2, projected.size());
        // Back-to-front order: distant first, near second
        ParticleSystem3D.ProjectedBillboard3D distant = projected.get(0);
        ParticleSystem3D.ProjectedBillboard3D near = projected.get(1);
        assertTrue(distant.cameraDepth() > near.cameraDepth());
        assertTrue(near.projectedSizePx() > distant.projectedSizePx());
    }

    @Test
    void instancedMeshNodeBatches1000TreesInSingleDrawCallUnder4Ms() {
        InstancedMeshNode forest = new InstancedMeshNode(
                "winter_forest",
                Mesh3D.createCube(1.0f),
                Material3D.defaultPbr("pine", "#2e4d3a")
        );
        for (int i = 0; i < 1000; i++) {
            forest.addInstance((i % 40) * 3.0f, 0.0f, (i / 40) * 3.0f, i * 17.0f, 1.0f);
        }

        float[] matrixBuffer = forest.packInstanceMatrices();
        InstancedMeshNode.InstancedBatchStats stats = forest.submitInstancedDraw(false);

        assertEquals(1000, stats.instanceCount());
        assertEquals(1, stats.drawCallCount());
        assertEquals(16_000, matrixBuffer.length);
        assertTrue(stats.prepareTimeMs() < 4.0, "Expected 1000 instances < 4 ms, took " + stats.prepareTimeMs());
    }

    @Test
    void camera3DAudioListenerBridgeRotatesSoundStageCorrectly() {
        Camera3D cam = new Camera3D();
        cam.setPosition(0.0f, 0.0f, 0.0f);
        cam.setLookAt(0.0f, 0.0f, -5.0f);

        try (AudioEngine engine = new AudioEngine(null, true)) {
            engine.init();
            Camera3DAudioListenerBridge bridge = new Camera3DAudioListenerBridge(cam, engine);

            bridge.sync();
            assertEquals(0.0f, engine.computeSpatialPan(0.0f, 0.0f, -5.0f), 0.01f);

            // Turn camera to the left (-X): fireplace still at (0, 0, -5) is now to the player's right
            cam.setLookAt(-5.0f, 0.0f, 0.0f);
            bridge.sync();
            float panAfterTurnLeft = engine.computeSpatialPan(0.0f, 0.0f, -5.0f);
            assertTrue(panAfterTurnLeft > 0.8f, "Fireplace must pan right when turning left: " + panAfterTurnLeft);
        }
    }

    @Test
    void vertexWindKeepsZeroWeightRootsPinnedWhileCanopySways() {
        VertexWindSystem wind = new VertexWindSystem();
        wind.configure(new Vector3f(1.0f, 0.0f, 0.0f), 0.4f, 2.0f);

        Vector3f rootOffset = wind.evaluateDisplacement(new Vector3f(0.0f, 0.0f, 0.0f), 0.0f, 1.25);
        assertEquals(0.0f, rootOffset.length(), 0.0001f);

        Vector3f tipOffset = wind.evaluateDisplacement(new Vector3f(0.0f, 4.0f, 0.0f), 1.0f, 1.25);
        assertTrue(tipOffset.length() > 0.01f);
        assertTrue(VertexWindSystem.WIND_VERTEX_SHADER.contains("aWindWeight"));
    }

    @Test
    void rigidBodyDropPhysicsDeterministicallyStopsAfterBounces() {
        RigidBody3DPropSimulator sim = new RigidBody3DPropSimulator(0.0f);
        RigidBody3DPropSimulator.RigidBodyProp apple = new RigidBody3DPropSimulator.RigidBodyProp(
                "apple",
                new Vector3f(0.0f, 3.0f, 0.0f),
                new Vector3f(0.8f, 0.0f, 0.0f),
                0.2f,
                0.45f,
                0.6f
        );
        sim.addProp(apple);
        assertFalse(apple.isAtRest());

        for (int i = 0; i < 400; i++) {
            sim.step(0.02);
        }

        assertTrue(apple.isAtRest(), "Dropped prop should come deterministically to rest");
        assertTrue(apple.getBounceCount() >= 1);
        assertEquals(0.2f, apple.getPosition().y, 0.001f);
        assertEquals(0.0f, apple.getVelocity().length(), 0.0001f);
    }
}
