package de.swfdtf.graphics;

import de.swfdtf.core.EngineLogger;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Transform2D;
import de.swfdtf.math.Vec2f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests verifying Chapter 1 motion, physics, collision, camera, and text-tag P2 goals
 * ({@code #0021}, {@code #0022}, {@code #0026}, {@code #0028}, {@code #0038}–{@code #0042},
 * {@code #0049}, {@code #0051}–{@code #0055}, {@code #0057}–{@code #0058}, {@code #0070}–{@code #0076},
 * {@code #0081}–{@code #0083}).
 */
class WaveP2Chapter1MotionPhysicsTest {

    private static SceneController newStageWithSprite(String tag, float x, float y, float w, float h) {
        SceneController sc = new SceneController(new SceneGraph(), null, new EngineLogger(), null, false);
        SpriteNode node = sc.spawnSprite(tag, "assets/images/" + tag + ".png", x, y);
        node.setSize(w, h);
        return sc;
    }

    @Test
    void patrolReturnsToStartAfterDoubleTravelTime() {
        SceneController sc = newStageWithSprite("npc", 100.0f, 200.0f, 40.0f, 40.0f);
        ObjectMotionController motion = sc.getMotionController();
        motion.startPatrol("npc", 100.0f, 200.0f, 300.0f, 200.0f, 100.0f, true);

        for (int i = 0; i < 200; i++) {
            motion.update(0.01, ObjectMotionController.MotionInput.NONE);
        }
        assertEquals(300.0f, sc.getActiveSprite("npc").getTransform().getXpos(), 1.0f);

        for (int i = 0; i < 200; i++) {
            motion.update(0.01, ObjectMotionController.MotionInput.NONE);
        }
        assertEquals(100.0f, sc.getActiveSprite("npc").getTransform().getXpos(), 1.0f);
        assertEquals(200.0f, sc.getActiveSprite("npc").getTransform().getYpos(), 1.0f);
    }

    @Test
    void followConvergesUnderStopRadius() {
        SceneController sc = newStageWithSprite("player", 500.0f, 200.0f, 40.0f, 40.0f);
        SpriteNode pet = sc.spawnSprite("pet", "assets/images/pet.png", 100.0f, 200.0f);
        pet.setSize(40.0f, 40.0f);

        ObjectMotionController motion = sc.getMotionController();
        motion.startFollow("pet", "player", 200.0f, 50.0f, 90.0f);

        for (int i = 0; i < 250; i++) {
            motion.update(0.01, ObjectMotionController.MotionInput.NONE);
        }
        double dist = Math.hypot(
                sc.getActiveSprite("player").getTransform().getXpos() - pet.getTransform().getXpos(),
                sc.getActiveSprite("player").getTransform().getYpos() - pet.getTransform().getYpos());
        assertTrue(dist <= 50.5, "Expected follower distance <= stopRadius (50), was " + dist);
    }

    @Test
    void cameraClampPreventsViewportExceedingBounds() {
        SceneController sc = newStageWithSprite("hero", 1900.0f, 1000.0f, 40.0f, 40.0f);
        ObjectMotionController motion = sc.getMotionController();
        motion.enableCameraFollow("hero", 0.0f);
        motion.setCameraClamp(0.0f, 0.0f, 500.0f, 300.0f);
        motion.update(0.016, ObjectMotionController.MotionInput.NONE);

        assertEquals(500.0f, motion.getCameraOffsetX(), 0.01f);
        assertEquals(300.0f, motion.getCameraOffsetY(), 0.01f);
    }

    @Test
    void onInteractTriggersOnlyInsideDistanceWhenPressed() {
        SceneController sc = newStageWithSprite("player", 100.0f, 200.0f, 40.0f, 40.0f);
        SpriteNode npc = sc.spawnSprite("npc", "assets/images/npc.png", 400.0f, 200.0f);
        npc.setSize(40.0f, 40.0f);

        ObjectMotionController motion = sc.getMotionController();
        motion.enableWasd("player", 200.0f, 0.0f, 0.0f, 1000.0f, 1000.0f, true);
        AtomicReference<String> jumped = new AtomicReference<>();
        motion.setCollisionJumpListener(jumped::set);
        motion.registerInteractTrigger("npc", 80.0f, "talk_npc", false);

        motion.update(0.016, new ObjectMotionController.MotionInput(
                false, false, false, false, false, 0f, 0f, false, true));
        assertNull(jumped.get(), "Interact outside range must stay inactive");

        sc.getActiveSprite("player").getTransform().setXpos(350.0f);
        motion.update(0.016, new ObjectMotionController.MotionInput(
                false, false, false, false, false, 0f, 0f, false, true));
        assertEquals("talk_npc", jumped.get());
    }

    @Test
    void autoFlipDashAndAnimStateOperateTogetherCleanly() {
        SceneController sc = newStageWithSprite("hero", 400.0f, 500.0f, 40.0f, 40.0f);
        ObjectMotionController motion = sc.getMotionController();
        motion.enableWasd("hero", 200.0f, 0.0f, 0.0f, 1800.0f, 1000.0f, true, 0.0f, 0.0f, true, "shift");
        motion.enablePhysics("hero", 980.0f, 460.0f, 500.0f);
        motion.registerAnimState("hero", "idle", "walk", "jump");

        Transform2D tf = sc.getActiveSprite("hero").getTransform();
        motion.update(0.02, new ObjectMotionController.MotionInput(false, false, true, false, false));
        assertTrue(tf.getXzoom() < 0.0f, "Pressing A must flip xzoom negative");
        assertEquals("walk", motion.getCurrentAnimState("hero").orElse(""));

        motion.update(0.02, new ObjectMotionController.MotionInput(false, false, false, true, false));
        assertTrue(tf.getXzoom() > 0.0f, "Pressing D must restore positive xzoom");

        motion.update(0.02, new ObjectMotionController.MotionInput(false, false, false, false, true));
        assertEquals("jump", motion.getCurrentAnimState("hero").orElse(""));

        for (int i = 0; i < 80; i++) {
            motion.update(0.02, ObjectMotionController.MotionInput.NONE);
        }
        assertEquals("idle", motion.getCurrentAnimState("hero").orElse(""));

        float xBeforeDash = tf.getXpos();
        motion.update(0.05, new ObjectMotionController.MotionInput(
                false, false, false, true, false, 0f, 0f, true, false));
        assertTrue(motion.isDashing("hero"));
        assertTrue(motion.getDashCooldownRemaining("hero") > 0.0f);
        float dashDelta = tf.getXpos() - xBeforeDash;
        assertTrue(dashDelta > 15.0f, "Dash should move faster than base speed, delta=" + dashDelta);
    }

    @Test
    void wallAndCompoundCollidersSlideDiagonallyAndPassThroughGap() {
        SceneController sc = newStageWithSprite("hero", 175.0f, 200.0f, 40.0f, 40.0f);
        ObjectMotionController motion = sc.getMotionController();
        motion.enableWasd("hero", 200.0f, 0.0f, 0.0f, 1200.0f, 1000.0f, false);
        motion.addWallCollider("wall1", 220.0f, 100.0f, 50.0f, 400.0f);

        Transform2D tf = sc.getActiveSprite("hero").getTransform();
        for (int i = 0; i < 20; i++) {
            motion.update(0.02, new ObjectMotionController.MotionInput(false, true, false, true, false));
        }
        assertTrue(tf.getXpos() < 220.0f, "X must stay blocked left of wall");
        assertTrue(tf.getYpos() > 250.0f, "Y must continue sliding downward along wall");

        motion.removeCollider("wall1");
        motion.addCompoundCollider("doorway", List.of(
                new Rect2f(300.0f, 0.0f, 40.0f, 250.0f),
                new Rect2f(300.0f, 400.0f, 40.0f, 300.0f)
        ));
        tf.setXpos(250.0f).setYpos(325.0f);
        for (int i = 0; i < 40; i++) {
            motion.update(0.02, new ObjectMotionController.MotionInput(false, false, false, true, false));
        }
        assertTrue(tf.getXpos() > 360.0f, "Player must pass through compound gap at y=325");
    }

    @Test
    void springEasingPeaksAboveOneAndConvergesToTarget() {
        SceneController sc = newStageWithSprite("box", 100.0f, 100.0f, 40.0f, 40.0f);
        ObjectMotionController motion = sc.getMotionController();
        motion.startMove("box", 200.0f, 100.0f, false, 1.0, "spring");

        for (int i = 0; i < 70; i++) {
            motion.update(0.01, ObjectMotionController.MotionInput.NONE);
        }
        float xAtPeak = sc.getActiveSprite("box").getTransform().getXpos();
        assertTrue(xAtPeak > 200.0f, "Expected spring overshoot > 200 at t=0.7, got " + xAtPeak);

        for (int i = 0; i < 35; i++) {
            motion.update(0.01, ObjectMotionController.MotionInput.NONE);
        }
        assertEquals(200.0f, sc.getActiveSprite("box").getTransform().getXpos(), 0.01f);
    }

    @Test
    void layerShakeReturnsToZeroWithoutShiftingSpriteLogicCoordinates() {
        SceneController sc = newStageWithSprite("actor", 300.0f, 400.0f, 40.0f, 40.0f);
        SceneGraph graph = sc.getSceneGraph();
        graph.shakeLayer(SceneGraph.LAYER_MASTER, 24.0f, 0.3f);

        sc.updateMotion(0.05, ObjectMotionController.MotionInput.NONE);
        Vec2f midShake = graph.getLayerShakeOffset(SceneGraph.LAYER_MASTER);
        assertTrue(Math.abs(midShake.x()) > 0.0f || Math.abs(midShake.y()) > 0.0f);
        assertEquals(300.0f, sc.getActiveSprite("actor").getTransform().getXpos(), 0.001f);

        sc.updateMotion(0.4, ObjectMotionController.MotionInput.NONE);
        Vec2f afterShake = graph.getLayerShakeOffset(SceneGraph.LAYER_MASTER);
        assertEquals(0.0f, afterShake.x(), 0.0001f);
        assertEquals(0.0f, afterShake.y(), 0.0001f);
    }

    @Test
    void knockbackClimbZoneMovingPlatformAttractAndImpulseBehaveDeterministically() {
        SceneController sc = newStageWithSprite("player", 200.0f, 400.0f, 40.0f, 40.0f);
        SpriteNode plat = sc.spawnSprite("lift", "assets/images/lift.png", 200.0f, 440.0f);
        plat.setSize(120.0f, 20.0f);
        SpriteNode coin = sc.spawnSprite("coin", "assets/images/coin.png", 420.0f, 400.0f);
        coin.setSize(20.0f, 20.0f);

        ObjectMotionController motion = sc.getMotionController();
        motion.enablePhysics("player", 980.0f, 460.0f, 900.0f);
        motion.registerMovingPlatform("lift", 200.0f, 440.0f, 400.0f, 440.0f, 100.0f, false);
        motion.registerAttract("coin", "player", 120.0f, 300.0f);

        Transform2D ptf = sc.getActiveSprite("player").getTransform();
        ptf.setXpos(200.0f).setYpos(400.0f);
        motion.update(0.02, ObjectMotionController.MotionInput.NONE);
        assertEquals(420.0f, coin.getTransform().getXpos(), 0.01f, "Coin outside radius stays stationary");

        for (int i = 0; i < 150; i++) {
            motion.update(0.01, ObjectMotionController.MotionInput.NONE);
        }
        assertTrue(ptf.getXpos() > 310.0f, "Moving platform must carry standing player horizontally");
        assertTrue(coin.getTransform().getXpos() < 410.0f, "Coin inside radius pulls toward player");

        motion.applyImpulse("player", 0.0f, -900.0f);
        motion.update(0.05, ObjectMotionController.MotionInput.NONE);
        assertTrue(ptf.getYpos() < 390.0f, "Impulse launches player upward");

        motion.addClimbZone("ladder", 150.0f, 100.0f, 300.0f, 400.0f);
        float climbStartY = ptf.getYpos();
        motion.update(0.2, ObjectMotionController.MotionInput.NONE);
        assertEquals(climbStartY, ptf.getYpos(), 0.5f, "Climb zone suspends gravity");

        motion.applyKnockback("player", -200.0f, 0.0f, 0.5f, 8.0f);
        assertTrue(motion.isInvulnerable("player"));
        float preKbX = ptf.getXpos();
        motion.update(0.1, ObjectMotionController.MotionInput.NONE);
        assertTrue(ptf.getXpos() < preKbX, "Knockback displaces player backward");
    }

    @Test
    void fixedSubstepperPreventsTunnelingAtFifteenFps() {
        SceneController sc = newStageWithSprite("bullet", 100.0f, 300.0f, 16.0f, 16.0f);
        SpriteNode thinWall = sc.spawnSprite("target", "assets/images/target.png", 200.0f, 300.0f);
        thinWall.setSize(16.0f, 64.0f);

        ObjectMotionController motion = sc.getMotionController();
        AtomicReference<String> hitLabel = new AtomicReference<>();
        motion.setCollisionJumpListener(hitLabel::set);
        motion.registerCollisionTrigger("bullet", "target", "bullet_hit");
        motion.startMove("bullet", 350.0f, 300.0f, false, 0.1, "linear");

        List<String> jumps = motion.update(0.1, ObjectMotionController.MotionInput.NONE);
        assertTrue(jumps.contains("bullet_hit") || "bullet_hit".equals(hitLabel.get()),
                "Fast projectile over coarse dt=0.1s must still collide without tunneling");
    }

    @Test
    void surfaceFrictionTimeScaleRelCoordsAttachYSortAnalogPointClickAndDespawn() {
        SceneController sc = newStageWithSprite("player", 100.0f, 500.0f, 40.0f, 40.0f);
        SpriteNode hat = sc.spawnSprite("hat", "assets/images/hat.png", 100.0f, 455.0f);
        SpriteNode bgChar = sc.spawnSprite("bg_npc", "assets/images/bg_npc.png", 300.0f, 200.0f);
        ObjectMotionController motion = sc.getMotionController();

        sc.setYSortEnabled(true);
        List<SceneNode> sorted = sc.getSceneGraph().getLayer(SceneGraph.LAYER_MASTER).getSortedNodes();
        assertEquals("bg_npc", sorted.get(0).getTag(), "Lower Y sprite renders behind");
        assertEquals("player", sorted.get(sorted.size() - 1).getTag(), "Higher Y sprite renders in front");

        motion.attachChild("hat", "player", 0.0f, -45.0f);
        motion.enableWasd("player", 200.0f, 0.0f, 0.0f, 1920.0f, 1080.0f, true);
        motion.update(0.5, new ObjectMotionController.MotionInput(
                false, false, false, false, false, 0.5f, 0.0f, false, false));
        assertEquals(150.0f, sc.getActiveSprite("player").getTransform().getXpos(), 1.0f);
        assertEquals(150.0f, hat.getTransform().getXpos(), 1.0f);
        assertEquals(455.0f, hat.getTransform().getYpos(), 1.0f);

        motion.setTimeScale(0.5f);
        motion.update(1.0, new ObjectMotionController.MotionInput(false, false, false, true, false));
        assertEquals(250.0f, sc.getActiveSprite("player").getTransform().getXpos(), 1.5f);
        motion.setTimeScale(1.0f);

        motion.disableWasd("player");
        SpriteNode skater = sc.spawnSprite("skater", "assets/images/skater.png", 100.0f, 700.0f);
        skater.setSize(40.0f, 40.0f);
        motion.enableWasd("skater", 200.0f, 0.0f, 0.0f, 1920.0f, 1080.0f, true, 10.0f, 10.0f);
        for (int i = 0; i < 40; i++) {
            motion.update(0.02, new ObjectMotionController.MotionInput(false, false, false, true, false));
        }
        float normalStopStart = skater.getTransform().getXpos();
        for (int i = 0; i < 40; i++) {
            motion.update(0.02, ObjectMotionController.MotionInput.NONE);
        }
        float normalGlide = skater.getTransform().getXpos() - normalStopStart;
        assertTrue(normalGlide > 5.0f, "Expected positive normal glide distance, got " + normalGlide);

        motion.addSurfaceFrictionZone("ice", 0.0f, 650.0f, 1920.0f, 150.0f, 0.3f);
        skater.getTransform().setXpos(100.0f);
        for (int i = 0; i < 120; i++) {
            motion.update(0.02, new ObjectMotionController.MotionInput(false, false, false, true, false));
        }
        float iceStopStart = skater.getTransform().getXpos();
        for (int i = 0; i < 80; i++) {
            motion.update(0.02, ObjectMotionController.MotionInput.NONE);
        }
        float iceGlide = skater.getTransform().getXpos() - iceStopStart;
        assertTrue(iceGlide > normalGlide * 1.5f, "Ice zone should lengthen glide significantly");

        motion.startRelativeViewportMove("bg_npc", 0.5f, 0.5f, 0.0, "linear");
        assertEquals(960.0f, bgChar.getTransform().getXpos(), 0.01f);
        assertEquals(540.0f, bgChar.getTransform().getYpos(), 0.01f);

        motion.enablePointClick("bg_npc", 500.0f, 0.0f, 0.0f, 1000.0f, 800.0f);
        assertTrue(motion.handleStageClick(1200.0f, 400.0f));
        motion.update(1.0, ObjectMotionController.MotionInput.NONE);
        assertEquals(1000.0f, bgChar.getTransform().getXpos(), 1.0f);

        assertTrue(sc.despawnSprite("bg_npc"));
        assertNull(sc.getActiveSprite("bg_npc"));
        assertFalse(motion.handleStageClick(500.0f, 500.0f), "Despawned tag must be purged from motion registries");
    }

    @Test
    void textTagParserSupportsShakeWaveAndNestedRubyShorthand() {
        TextTagParser.ParsedRichText parsed = TextTagParser.parse(
                "{wave}A{/wave}{shake}B{/shake} {color=#ffd700}{ruby=きょう}今日{/ruby}{/color}"
        );
        assertEquals("AB 今日", parsed.plainText());
        assertTrue(parsed.spans().get(0).style().wave());
        assertTrue(parsed.spans().get(1).style().jitter());
        List<TextTagParser.RubyPair> pairs = parsed.rubyPairs();
        assertEquals(1, pairs.size());
        assertEquals("今日", pairs.get(0).baseText());
        assertEquals("きょう", pairs.get(0).rubyText());
        assertEquals(3, pairs.get(0).startIndex());
        assertEquals(5, pairs.get(0).endIndex());
        assertNotNull(parsed.spans().get(3).style().colorHex());
    }
}
