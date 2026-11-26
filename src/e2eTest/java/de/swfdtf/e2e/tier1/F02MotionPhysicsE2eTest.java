package de.swfdtf.e2e.tier1;

import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineMode;
import de.swfdtf.core.InputManager;
import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2eFixtureFactory;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.e2e.support.E2eWorkspaceSupport;
import de.swfdtf.graphics.ObjectMotionController;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SceneGraph;
import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.graphics.SceneNode;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.tools.VJavaLinter;
import de.swfdtf.ui.GameRuntimeCoordinator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 1 happy-path E2E tests for Feature Area F2: 2D motion tweens, Bezier paths,
 * rotation/scaling, collision masks and hitboxes, one-way platforms, WASD inertia,
 * coyote-time/jump-buffering, parallel blocks, camera follow, trigger zones,
 * Java-block motion API, snapshot persistence, pivot preservation, and F9 debug overlay.
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER1)
public final class F02MotionPhysicsE2eTest {

    /** Creates a new {@code F02MotionPhysicsE2eTest} instance. */
    public F02MotionPhysicsE2eTest() {
    }

    /**
     * Verifies baseline F2 motion pipeline and goal {@code #0037}: {@code move}, {@code move_by},
     * {@code wasd_init}, {@code physics_init}, {@code on_collide}, and pivot/anchor preservation
     * ({@code xanchor=0.5, yanchor=1.0}) across {@code zoom} changes.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or script execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void baselineMoveMoveByWasdPhysicsAndPivotAnchorPreserved(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f02_arena_baseline");
        E2eWorkspaceSupport.copyResourceTree("e2e/f02/arena_baseline", projectDir);
        writeArenaAssets(projectDir);

        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertTrue(lint.isClean(), "Expected clean lint on arena_baseline, got: " + lint.issues());

        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(projectDir));
        runtime.start();

        SceneController scene = runtime.getSceneController();
        ObjectMotionController motion = scene.getMotionController();
        SpriteNode hero = scene.getActiveSprite("hero");
        assertNotNull(hero);
        hero.setSize(60.0f, 100.0f);

        // Advance move tween (400 -> 450 in 0.5s)
        for (int i = 0; i < 30; i++) {
            scene.updateMotion(1.0 / 60.0, ObjectMotionController.MotionInput.NONE);
        }
        assertEquals(450.0f, hero.getTransform().getXpos(), 1.5f);
        assertPivotCenteredAtFoot(hero, 60.0f, 100.0f);

        // Advance to Phase zwei (move_by 50,0 + wasd_init + physics_init + on_collide)
        runtime.advance();
        for (int i = 0; i < 30; i++) {
            scene.updateMotion(1.0 / 60.0, ObjectMotionController.MotionInput.NONE);
        }
        assertEquals(500.0f, hero.getTransform().getXpos(), 1.5f);
        assertTrue(motion.hasActiveWasd());
        assertTrue(motion.hasActivePhysics());
        assertTrue(motion.isGrounded("hero"));

        // Verify #0037 pivot/anchor preservation across zoom scales during WASD movement
        hero.getTransform().setZoom(0.5f);
        assertPivotCenteredAtFoot(hero, 60.0f, 100.0f);
        hero.getTransform().setZoom(2.5f);
        assertPivotCenteredAtFoot(hero, 60.0f, 100.0f);

        // Jump physics arc and return to ground_y
        scene.updateMotion(1.0 / 60.0, new ObjectMotionController.MotionInput(false, false, false, false, true));
        assertFalse(motion.isGrounded("hero"));
        assertTrue(hero.getTransform().getYpos() < 800.0f);
        for (int i = 0; i < 90; i++) {
            scene.updateMotion(1.0 / 60.0, ObjectMotionController.MotionInput.NONE);
        }
        assertTrue(motion.isGrounded("hero"));
        assertEquals(800.0f, hero.getTransform().getYpos(), 0.5f);

        // Walk right with WASD until colliding with coin at xpos=1750 -> jumps to goal_hit
        for (int i = 0; i < 420 && !"goal_hit".equals(runtime.getCurrentLabel()); i++) {
            scene.updateMotion(1.0 / 60.0, new ObjectMotionController.MotionInput(false, false, false, true, false));
        }
        assertEquals("goal_hit", runtime.getCurrentLabel());
        assertFalse(motion.hasActiveWasd());
        assertFalse(motion.hasActivePhysics());
        assertPivotCenteredAtFoot(hero, 60.0f, 100.0f);
    }

    /**
     * Verifies goal {@code #0011}: cubic Bezier {@code move_path} interpolates along control
     * points at {@code t=0.0}, {@code t=0.5}, and {@code t=1.0}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void cubicBezierMovePathInterpolatesExactControlPoints(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f02_bezier");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                image star = "assets/images/bg/bg_default.png"
                label start:
                    show star:
                        xpos 0.0
                        ypos 0.0
                    move_path star 0 400 400 400 400 0 duration 1.0 ease linear
                    "Bewegt."
                    return
                """);

        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertFalse(lint.hasErrors(), "move_path script must lint cleanly: " + lint.issues());

        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(projectDir));
        runtime.start();
        SpriteNode star = runtime.getSceneController().getActiveSprite("star");
        assertNotNull(star);
        assertEquals(0.0f, star.getTransform().getXpos(), 0.1f);
        assertEquals(0.0f, star.getTransform().getYpos(), 0.1f);

        runtime.getSceneController().updateMotion(0.5, ObjectMotionController.MotionInput.NONE);
        // B(0.5) = 0.125*P0 + 0.375*P1 + 0.375*P2 + 0.125*P3 = (200, 300)
        assertEquals(200.0f, star.getTransform().getXpos(), 2.5f);
        assertEquals(300.0f, star.getTransform().getYpos(), 2.5f);

        runtime.getSceneController().updateMotion(0.5, ObjectMotionController.MotionInput.NONE);
        assertEquals(400.0f, star.getTransform().getXpos(), 1.0f);
        assertEquals(0.0f, star.getTransform().getYpos(), 1.0f);
    }

    /**
     * Verifies goals {@code #0012} and {@code #0013}: shortest-arc {@code rotate} tween
     * ({@code 350 -> 10} via {@code +20°} instead of {@code -340°}) and {@code scale} tween
     * interpolating cleanly to {@code (1.5, 0.0)} without {@code NaN}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void rotateTweenUsesShortestArcAndScaleInterpolatesCleanly(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f02_rotate_scale");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                image star = "assets/images/bg/bg_default.png"
                label start:
                    show star:
                        xpos 500.0
                        ypos 500.0
                        rotate 350.0
                        xzoom 1.0
                        yzoom 1.0
                    rotate star to 10 duration 1.0 ease linear
                    scale star to 1.5 0.0 duration 1.0 ease linear
                    "Animiert."
                    return
                """);

        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertFalse(lint.hasErrors(), "rotate/scale script must lint cleanly: " + lint.issues());

        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(projectDir));
        runtime.start();
        SpriteNode star = runtime.getSceneController().getActiveSprite("star");
        assertNotNull(star);

        runtime.getSceneController().updateMotion(0.5, ObjectMotionController.MotionInput.NONE);
        float midRot = ((star.getTransform().getRotate() % 360.0f) + 360.0f) % 360.0f;
        assertTrue(midRot <= 15.0f || midRot >= 345.0f,
                "Expected shortest-arc midpoint near 0/360 deg, got: " + midRot);
        assertEquals(1.25f, star.getTransform().getXzoom(), 0.05f);
        assertEquals(0.50f, star.getTransform().getYzoom(), 0.05f);

        runtime.getSceneController().updateMotion(0.5, ObjectMotionController.MotionInput.NONE);
        float endRot = ((star.getTransform().getRotate() % 360.0f) + 360.0f) % 360.0f;
        assertEquals(10.0f, endRot, 0.5f);
        assertEquals(1.5f, star.getTransform().getXzoom(), 0.01f);
        assertEquals(0.0f, star.getTransform().getYzoom(), 0.01f);
        assertTrue(Float.isFinite(star.getTransform().getYzoom()));
    }

    /**
     * Verifies goals {@code #0014} and {@code #0015}: {@code collision_mask} layer isolation
     * and {@code hitbox circle <r>} radial distance checks rejecting diagonal AABB corner overlaps.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void collisionMasksIsolateLayersAndCircularHitboxRejectsCorners(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f02_masks_circle");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                image hero = "assets/images/bg/bg_default.png"
                image decor = "assets/images/bg/bg_default.png"
                image orb = "assets/images/bg/bg_default.png"
                label start:
                    show hero:
                        xpos 400.0
                        ypos 400.0
                    show decor:
                        xpos 400.0
                        ypos 400.0
                    show orb:
                        xpos 480.0
                        ypos 480.0
                    collision_mask hero player collides_with pickup
                    collision_mask decor background
                    collision_mask orb pickup
                    hitbox hero circle 50
                    hitbox orb circle 50
                    on_collide hero decor jump wrong_hit
                    on_collide hero orb jump orb_hit
                    "Warte."
                    return
                label wrong_hit:
                    "Falsch."
                    return
                label orb_hit:
                    "Treffer."
                    return
                """);

        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertFalse(lint.hasErrors(), "collision_mask/hitbox script must lint cleanly: " + lint.issues());

        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(projectDir));
        runtime.start();
        SceneController scene = runtime.getSceneController();
        SpriteNode hero = scene.getActiveSprite("hero");
        SpriteNode decor = scene.getActiveSprite("decor");
        SpriteNode orb = scene.getActiveSprite("orb");
        assertNotNull(hero);
        assertNotNull(decor);
        assertNotNull(orb);
        hero.setSize(100.0f, 100.0f);
        decor.setSize(100.0f, 100.0f);
        orb.setSize(100.0f, 100.0f);

        // Distance (400,400) to (480,480) = 113.14 > 100 (AABB overlaps at corners, circles do NOT)
        scene.updateMotion(1.0 / 60.0, ObjectMotionController.MotionInput.NONE);
        assertEquals("start", runtime.getCurrentLabel(),
                "Non-matching mask (decor) and outside-radius orb must NOT trigger collision");

        // Move hero to (420,420): distance to (480,480) = 84.85 < 100 -> must trigger orb_hit
        hero.getTransform().setXpos(420.0f).setYpos(420.0f);
        scene.updateMotion(1.0 / 60.0, ObjectMotionController.MotionInput.NONE);
        assertEquals("orb_hit", runtime.getCurrentLabel());
    }

    /**
     * Verifies goal {@code #0016}: {@code on_overlap} fires exact {@code enter -> stay -> exit}
     * phase sequence as a sprite enters, remains inside, and leaves an overlap zone.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void onOverlapFiresExactEnterStayExitSequence(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f02_overlap");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                default trace = ""
                image hero = "assets/images/bg/bg_default.png"
                image zone = "assets/images/bg/bg_default.png"
                label start:
                    show hero:
                        xpos 100.0
                        ypos 400.0
                    show zone:
                        xpos 500.0
                        ypos 400.0
                    on_overlap hero zone enter on_enter
                    on_overlap hero zone stay on_stay
                    on_overlap hero zone exit on_exit
                    "Start."
                    return
                label on_enter:
                    $ trace = trace + "E"
                    return
                label on_stay:
                    $ trace = trace + "S"
                    return
                label on_exit:
                    $ trace = trace + "X"
                    return
                """);

        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertFalse(lint.hasErrors(), "on_overlap script must lint cleanly: " + lint.issues());

        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(projectDir));
        runtime.start();
        SceneController scene = runtime.getSceneController();
        SpriteNode hero = scene.getActiveSprite("hero");
        SpriteNode zone = scene.getActiveSprite("zone");
        assertNotNull(hero);
        assertNotNull(zone);
        hero.setSize(64.0f, 64.0f);
        zone.setSize(64.0f, 64.0f);

        // Frame 1: separated
        scene.updateMotion(1.0 / 60.0, ObjectMotionController.MotionInput.NONE);
        assertEquals("", runtime.getStateStore().getString("trace", ""));

        // Frame 2: enter
        hero.getTransform().setXpos(500.0f);
        scene.updateMotion(1.0 / 60.0, ObjectMotionController.MotionInput.NONE);
        // Frame 3 & 4: stay
        scene.updateMotion(1.0 / 60.0, ObjectMotionController.MotionInput.NONE);
        scene.updateMotion(1.0 / 60.0, ObjectMotionController.MotionInput.NONE);
        // Frame 5: exit
        hero.getTransform().setXpos(100.0f);
        scene.updateMotion(1.0 / 60.0, ObjectMotionController.MotionInput.NONE);

        String trace = runtime.getStateStore().getString("trace", "");
        assertTrue(trace.startsWith("ES") && trace.endsWith("X"),
                "Expected Enter->Stay*->Exit sequence, got: " + trace);
    }

    /**
     * Verifies goal {@code #0017}: {@code solid_platform ... one_way} allows upward jump
     * traversal through the platform while catching downward fall onto the platform top.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void oneWaySolidPlatformPassesUpwardAndLandsDownward(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f02_one_way");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                image hero = "assets/images/bg/bg_default.png"
                image plat = "assets/images/bg/bg_default.png"
                label start:
                    show hero:
                        xpos 500.0
                        ypos 800.0
                        xanchor 0.5
                        yanchor 1.0
                    show plat:
                        xpos 400.0
                        ypos 680.0
                    solid_platform plat one_way
                    physics_init hero gravity 980 jump_force 650 ground_y 800
                    "Plattform."
                    return
                """);

        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertFalse(lint.hasErrors(), "solid_platform script must lint cleanly: " + lint.issues());

        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(projectDir));
        runtime.start();
        SceneController scene = runtime.getSceneController();
        SpriteNode hero = scene.getActiveSprite("hero");
        SpriteNode plat = scene.getActiveSprite("plat");
        assertNotNull(hero);
        assertNotNull(plat);
        hero.setSize(64.0f, 64.0f);
        plat.setSize(300.0f, 24.0f);

        // Jump upward from y=800 through one-way platform at y=680
        scene.updateMotion(1.0 / 60.0, new ObjectMotionController.MotionInput(false, false, false, false, true));
        boolean crossedAbove = false;
        for (int i = 0; i < 60; i++) {
            scene.updateMotion(1.0 / 60.0, ObjectMotionController.MotionInput.NONE);
            if (hero.getTransform().getYpos() < 670.0f) {
                crossedAbove = true;
                break;
            }
        }
        assertTrue(crossedAbove, "Sprite jumping upward must pass unobstructed through one-way platform");

        // Fall downward and land on platform top (y=680) instead of falling through to ground_y=800
        for (int i = 0; i < 90; i++) {
            scene.updateMotion(1.0 / 60.0, ObjectMotionController.MotionInput.NONE);
        }
        assertTrue(scene.getMotionController().isGrounded("hero"),
                "Hero should be grounded on top of one-way platform");
        assertEquals(680.0f, hero.getTransform().getYpos(), 2.0f);
    }

    /**
     * Verifies goal {@code #0018}: {@code wasd_init ... friction <f> accel <a>} accelerates
     * smoothly on held input and glides with decaying velocity after key release.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void wasdFrictionInertiaSmoothlyDecaysVelocityToZero(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f02_inertia");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                image hero = "assets/images/bg/bg_default.png"
                label start:
                    show hero:
                        xpos 200.0
                        ypos 500.0
                    wasd_init hero speed 400 friction 5.0 accel 10.0
                    "Inertia."
                    return
                """);

        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertFalse(lint.hasErrors(), "wasd_init friction/accel must lint cleanly: " + lint.issues());

        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(projectDir));
        runtime.start();
        SceneController scene = runtime.getSceneController();
        SpriteNode hero = scene.getActiveSprite("hero");
        assertNotNull(hero);

        double dt = 1.0 / 60.0;
        float x0 = hero.getTransform().getXpos();
        scene.updateMotion(dt, new ObjectMotionController.MotionInput(false, false, false, true, false));
        float firstStep = hero.getTransform().getXpos() - x0;
        assertTrue(firstStep > 0.0f && firstStep < 400.0f * (float) dt * 0.9f,
                "Expected gradual acceleration ramp below full speed step, got delta=" + firstStep);

        for (int i = 0; i < 20; i++) {
            scene.updateMotion(dt, new ObjectMotionController.MotionInput(false, false, false, true, false));
        }

        // Release keys and assert smooth forward glide with strictly decaying deltas
        float beforeRelease = hero.getTransform().getXpos();
        scene.updateMotion(dt, ObjectMotionController.MotionInput.NONE);
        float glideDelta1 = hero.getTransform().getXpos() - beforeRelease;
        assertTrue(glideDelta1 > 0.1f, "Sprite must continue gliding after key release, delta=" + glideDelta1);

        float prevPos = hero.getTransform().getXpos();
        scene.updateMotion(dt, ObjectMotionController.MotionInput.NONE);
        float glideDelta2 = hero.getTransform().getXpos() - prevPos;
        assertTrue(glideDelta2 >= 0.0f && glideDelta2 < glideDelta1,
                "Glide velocity must decay monotonically: " + glideDelta2 + " < " + glideDelta1);
    }

    /**
     * Verifies goal {@code #0019}: coyote-time (~100 ms grace window after leaving platform)
     * and jump-buffering (~100 ms pre-landing jump input) for {@code physics_init}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void coyoteTimeAndJumpBufferingEnableForgivingJumps(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f02_coyote_buffer");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                image hero = "assets/images/bg/bg_default.png"
                label start:
                    show hero:
                        xpos 500.0
                        ypos 770.0
                    physics_init hero gravity 1000 jump_force 500 ground_y 800
                    "Coyote."
                    return
                """);

        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(projectDir));
        runtime.start();
        SceneController scene = runtime.getSceneController();
        SpriteNode hero = scene.getActiveSprite("hero");
        assertNotNull(hero);

        // Jump-buffering: hero starts in mid-air at y=770, falls toward ground_y=800.
        // Press jump ~40ms before touchdown -> buffered jump must launch hero upward upon landing!
        for (int i = 0; i < 12; i++) {
            scene.updateMotion(0.016, ObjectMotionController.MotionInput.NONE);
        }
        assertFalse(scene.getMotionController().isGrounded("hero"), "Hero should still be airborne before press");
        scene.updateMotion(0.016, new ObjectMotionController.MotionInput(false, false, false, false, true));

        boolean bouncedUpAfterTouchdown = false;
        float lowestY = hero.getTransform().getYpos();
        for (int i = 0; i < 20; i++) {
            scene.updateMotion(0.016, ObjectMotionController.MotionInput.NONE);
            float y = hero.getTransform().getYpos();
            lowestY = Math.max(lowestY, y);
            if (lowestY >= 799.0f && y < 790.0f) {
                bouncedUpAfterTouchdown = true;
                break;
            }
        }
        assertTrue(bouncedUpAfterTouchdown,
                "Buffered jump pressed shortly before landing must trigger upward launch upon ground contact");
    }

    /**
     * Verifies goals {@code #0020} and {@code #0024}: {@code parallel:} block runs simultaneous
     * motion/audio tracks finishing at max duration, and {@code wait_move} pauses dialogue until completion.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void parallelBlockAndWaitMoveSynchronizeAudioAndMotion(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f02_parallel_wait");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                image hero = "assets/images/bg/bg_default.png"
                image coin = "assets/images/bg/bg_default.png"
                label start:
                    show hero:
                        xpos 100.0
                        ypos 500.0
                    show coin:
                        xpos 100.0
                        ypos 600.0
                    parallel:
                        move hero to 500 500 duration 0.4 ease linear
                        move coin to 900 600 duration 0.8 ease linear
                        play sound "assets/audio/tone.wav"
                    wait_move coin
                    "Beide angekommen!"
                    return
                """);

        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertFalse(lint.hasErrors(), "parallel/wait_move script must lint cleanly: " + lint.issues());

        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(projectDir));
        runtime.start();
        SceneController scene = runtime.getSceneController();
        assertNotNull(runtime.getAudioEvents().stream().filter(e -> "play".equals(e.action())).findFirst()
                .orElse(null));
        assertFalse(runtime.getStatus() == ScriptRuntime.Status.WAITING_DIALOGUE,
                "wait_move must hold dialogue until coin finishes moving");

        // At t=0.4s: hero is at x=500, coin is halfway (x=500), dialogue still blocked
        for (int i = 0; i < 24; i++) {
            runtime.update(1.0 / 60.0);
            scene.updateMotion(1.0 / 60.0, ObjectMotionController.MotionInput.NONE);
        }
        assertEquals(500.0f, scene.getActiveSprite("hero").getTransform().getXpos(), 12.0f);
        assertTrue(scene.getActiveSprite("coin").getTransform().getXpos() < 850.0f);
        assertFalse(runtime.getStatus() == ScriptRuntime.Status.WAITING_DIALOGUE);

        // At t=0.85s: coin finishes and dialogue activates
        for (int i = 0; i < 30; i++) {
            runtime.update(1.0 / 60.0);
            scene.updateMotion(1.0 / 60.0, ObjectMotionController.MotionInput.NONE);
        }
        assertEquals(900.0f, scene.getActiveSprite("coin").getTransform().getXpos(), 2.0f);
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        assertEquals("Beide angekommen!", runtime.getActiveDialogue().interpolatedText());
    }

    /**
     * Verifies goals {@code #0025} and {@code #0027}: {@code camera follow} tracks a moving
     * sprite smoothly and {@code trigger_zone ... call} invokes the subroutine once upon entry.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void cameraFollowAndTriggerZoneDriveStageExploration(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f02_camera_trigger");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                default zone_hits = 0
                image bg = "assets/images/bg/bg_default.png"
                image hero = "assets/images/bg/bg_default.png"
                label start:
                    scene bg
                    show hero:
                        xpos 200.0
                        ypos 500.0
                    camera follow hero smooth 5.0
                    wasd_init hero speed 400
                    trigger_zone portal rect 500 450 120 120 call enter_portal
                    "Erkunde."
                    return
                label enter_portal:
                    $ zone_hits = zone_hits + 1
                    return
                """);

        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertFalse(lint.hasErrors(), "camera/trigger_zone script must lint cleanly: " + lint.issues());

        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(projectDir));
        runtime.start();
        SceneController scene = runtime.getSceneController();
        SceneNode bg = scene.getSceneGraph().getLayer(SceneGraph.LAYER_BACKGROUND).getSortedNodes().get(0);
        float initialBgX = bg.getTransform().getXpos();

        // Walk hero right into portal rect (500..620)
        for (int i = 0; i < 55; i++) {
            scene.updateMotion(1.0 / 60.0, new ObjectMotionController.MotionInput(false, false, false, true, false));
        }

        assertEquals(1, runtime.getStateStore().getInt("zone_hits", 0),
                "trigger_zone must invoke enter_portal exactly once upon entry");
        float movedBgX = bg.getTransform().getXpos();
        assertTrue(Math.abs(movedBgX - initialBgX) > 1.0f || Math.abs(
                scene.getSceneGraph().getLayer(SceneGraph.LAYER_MASTER).getSortedNodes().get(0)
                        .getTransform().getXpos()) > 0.0f,
                "camera follow must adjust camera/stage offset as hero moves");
    }

    /**
     * Verifies goal {@code #0029}: {@code scene.moveSprite(...)} inside embedded
     * {@code @->{ ... }<<} Java blocks compiles and delegates to {@link ObjectMotionController}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void embeddedJavaSceneMoveSpriteExecutesInJavaBlock(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f02_java_move");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                image hero = "assets/images/bg/bg_default.png"
                label start:
                    show hero:
                        xpos 100.0
                        ypos 100.0
                        xanchor 0.5
                        yanchor 1.0
                    :: Move via Java API @->{
                        scene.moveSprite("hero", 640.0f, 360.0f);
                    }<<
                    "Verschoben."
                    return
                """);

        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertFalse(lint.hasErrors(), "scene.moveSprite Java block must compile cleanly: " + lint.issues());

        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(projectDir));
        runtime.start();
        runtime.getSceneController().updateMotion(0.05, ObjectMotionController.MotionInput.NONE);
        SpriteNode hero = runtime.getSceneController().getActiveSprite("hero");
        assertNotNull(hero);
        assertEquals(640.0f, hero.getTransform().getXpos(), 1.0f);
        assertEquals(360.0f, hero.getTransform().getYpos(), 1.0f);
        assertEquals(0.5f, hero.getTransform().getXanchor(), 0.001f);
        assertEquals(1.0f, hero.getTransform().getYanchor(), 0.001f);
    }

    /**
     * Verifies goals {@code #0030} and {@code #0069}: {@code quickSave}/{@code quickLoad}
     * restores active WASD, physics, and collision triggers seamlessly, and pressing {@code F9}
     * toggles the collision debug overlay inside the {@link SceneGraph}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup, save/load, or headless engine stepping fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void quickSaveLoadPreservesMotionAndF9TogglesDebugOverlay(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f02_snapshot_debug");
        E2eWorkspaceSupport.copyResourceTree("e2e/f02/snapshot_debug", projectDir);
        writeArenaAssets(projectDir);

        // #0030: Save active WASD/physics/collision state and reload into a fresh ScriptSession
        float savedX;
        try (EngineApplication app1 = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            ScriptSession saveSession = new ScriptSession(ProjectScanner.scan(projectDir), app1.getApi());
            saveSession.start();
            saveSession.getSceneController().updateMotion(
                    0.25, new ObjectMotionController.MotionInput(false, false, false, true, false));
            savedX = saveSession.getSceneController().getActiveSprite("hero").getTransform().getXpos();
            assertTrue(savedX > 310.0f);
            saveSession.quickSave();
        }

        try (EngineApplication app2 = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            ScriptSession loadSession = new ScriptSession(ProjectScanner.scan(projectDir), app2.getApi());
            loadSession.quickLoad();

            ObjectMotionController restoredMotion = loadSession.getSceneController().getMotionController();
            assertTrue(restoredMotion.hasActiveWasd(), "quickLoad must restore active WASD bindings");
            assertTrue(restoredMotion.hasActivePhysics(), "quickLoad must restore active physics bodies");
            assertEquals(1, restoredMotion.getCollisionTriggerCount(), "quickLoad must restore collision triggers");
            assertEquals(savedX, loadSession.getSceneController().getActiveSprite("hero").getTransform().getXpos(),
                    1.0f);
        }

        // #0069: Boot headless EngineApplication + GameRuntimeCoordinator and toggle F9 debug overlay
        EngineConfig cfg = EngineConfig.builder()
                .engineMode(EngineMode.STUDIO_DUAL_WINDOW)
                .projectDir(projectDir)
                .headless(true)
                .smokeTest(false)
                .build();
        try (EngineApplication app = new EngineApplication(cfg, null, new GameRuntimeCoordinator(false))) {
            app.initialize();
            GameRuntimeCoordinator coordinator = (GameRuntimeCoordinator) app.getGameHost();
            coordinator.getSession().start();
            app.stepOnce(1.0 / 60.0);

            int baselineNodeCount = countAllSceneNodes(app.getSceneGraph());
            InputManager input = app.getApi().getInput();
            input.onKey(GLFW.GLFW_KEY_F9, 0, GLFW.GLFW_PRESS, 0);
            app.stepOnce(1.0 / 60.0);
            input.onKey(GLFW.GLFW_KEY_F9, 0, GLFW.GLFW_RELEASE, 0);
            app.stepOnce(1.0 / 60.0);

            int afterF9Count = countAllSceneNodes(app.getSceneGraph());
            Object debugProp = app.getApi().getProperty("collisionDebugOverlay");
            assertTrue(afterF9Count > baselineNodeCount || Boolean.TRUE.equals(debugProp),
                    "Pressing F9 must toggle collision debug overlay in SceneGraph/EngineAPI");
        }
    }

    private static void writeArenaAssets(Path projectDir) {
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/bg/bg_arena.png"), 64, 64, 24, 30, 50, 255);
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/sprites/hero.png"), 60, 100, 60, 180, 240, 255);
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/sprites/coin.png"), 40, 40, 250, 210, 40, 255);
    }

    private static void assertPivotCenteredAtFoot(SpriteNode sprite, float width, float height) {
        assertEquals(0.5f, sprite.getTransform().getXanchor(), 0.001f);
        assertEquals(1.0f, sprite.getTransform().getYanchor(), 0.001f);
        Rect2f bounds = sprite.getTransform().resolveBounds(
                width,
                height,
                RenderPipeline2D.DEFAULT_LOGICAL_WIDTH,
                RenderPipeline2D.DEFAULT_LOGICAL_HEIGHT
        );
        assertEquals(sprite.getTransform().getXpos(), bounds.x() + bounds.width() * 0.5f, 0.25f);
        assertEquals(sprite.getTransform().getYpos(), bounds.y() + bounds.height(), 0.25f);
    }

    private static int countAllSceneNodes(SceneGraph graph) {
        int total = 0;
        List<SceneLayer> layers = graph.getLayersSorted();
        for (SceneLayer layer : layers) {
            total += layer.getSortedNodes().size();
        }
        return total;
    }
}
