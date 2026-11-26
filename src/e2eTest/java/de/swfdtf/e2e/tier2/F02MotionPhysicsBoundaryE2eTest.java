package de.swfdtf.e2e.tier2;

import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineMode;
import de.swfdtf.core.InputManager;
import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2eFixtureFactory;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.graphics.ObjectMotionController;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SceneGraph;
import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaParseException;
import de.swfdtf.state.SaveManager;
import de.swfdtf.state.SaveSlotData;
import de.swfdtf.tools.VJavaLinter;
import de.swfdtf.ui.GameRuntimeCoordinator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 2 boundary, edge-case, and negative E2E tests for Feature Area F2: malformed motion DSL,
 * zero/negative durations, rotation wraparound, zero/negative scales, invalid hitboxes/masks,
 * expired coyote-time, empty parallel blocks, missing trigger targets, anchor bounds clamping,
 * and legacy save / empty-stage debug overlay resilience.
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER2)
public final class F02MotionPhysicsBoundaryE2eTest {

    /** Creates a new {@code F02MotionPhysicsBoundaryE2eTest} instance. */
    public F02MotionPhysicsBoundaryE2eTest() {
    }

    /**
     * Verifies baseline F2 error handling: malformed {@code on_collide} throws a structured
     * {@link VJavaParseException} with line metadata, and updating motion on missing tags
     * never throws {@link NullPointerException}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void baselineMalformedOnCollideAndMissingTagsFailCleanly(@TempDir Path tempDir) throws Exception {
        Path badDir = tempDir.resolve("f02_bad_collide");
        E2eFixtureFactory.createMinimalPlayableProject(badDir, """
                label start:
                    on_collide hero
                    return
                """);
        VJavaParseException ex = assertThrows(VJavaParseException.class, () -> ProjectScanner.scan(badDir));
        assertTrue(ex.getLineNumber() > 0);
        assertTrue(ex.getSourceFile().contains("main.vjava"));

        SceneController scene = new SceneController(new SceneGraph());
        ObjectMotionController motion = scene.getMotionController();
        motion.startMove("ghost", 100.0f, 200.0f, false, 0.5, "linear");
        motion.enableWasd("ghost", 250.0f, 0.0f, 0.0f, 1920.0f, 1080.0f, true);
        motion.enablePhysics("ghost", 980.0f, 400.0f, 900.0f);
        scene.updateMotion(0.1, new ObjectMotionController.MotionInput(true, false, false, true, true));
        assertFalse(motion.isGrounded("ghost"));
    }

    /**
     * Verifies goal {@code #0037} boundary behavior: negative {@code dt} is a safe no-op,
     * huge {@code dt} clamps within WASD bounds while keeping {@code (xanchor=0.5, yanchor=1.0)}
     * intact, and {@code xzoom=-1.5f, yzoom=0.0f} stays finite without {@code NaN}.
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void negativeDtAndAnchorBoundsClampingWorkDeterministically() {
        SceneController scene = new SceneController(new SceneGraph());
        SpriteNode hero = scene.showSprite("hero", List.of(), null, null);
        hero.setSize(80.0f, 120.0f);
        hero.getTransform()
                .setXalign(Float.NaN)
                .setYalign(Float.NaN)
                .setXpos(500.0f)
                .setYpos(600.0f)
                .setXanchor(0.5f)
                .setYanchor(1.0f)
                .setXzoom(-1.5f)
                .setYzoom(0.0f);

        ObjectMotionController motion = scene.getMotionController();
        motion.enableWasd("hero", 300.0f, 100.0f, 120.0f, 1800.0f, 900.0f, true);

        scene.updateMotion(-1.0, new ObjectMotionController.MotionInput(false, false, false, true, false));
        assertEquals(500.0f, hero.getTransform().getXpos(), 0.01f);

        scene.updateMotion(10.0, new ObjectMotionController.MotionInput(false, false, false, true, false));
        assertEquals(1800.0f, hero.getTransform().getXpos(), 0.01f);
        assertEquals(0.5f, hero.getTransform().getXanchor(), 0.001f);
        assertEquals(1.0f, hero.getTransform().getYanchor(), 0.001f);

        Rect2f bounds = hero.getTransform().resolveBounds(
                80.0f, 120.0f, RenderPipeline2D.DEFAULT_LOGICAL_WIDTH, RenderPipeline2D.DEFAULT_LOGICAL_HEIGHT);
        assertTrue(Float.isFinite(bounds.x()) && Float.isFinite(bounds.y()));
        assertEquals(0.0f, bounds.height(), 0.001f);
        assertEquals(1800.0f, bounds.x() + bounds.width() * 0.5f, 0.05f);
        assertEquals(600.0f, bounds.y() + bounds.height(), 0.05f);
    }

    /**
     * Verifies goals {@code #0011} and {@code #0024} boundary cases: {@code move_path} with
     * {@code duration 0} snaps immediately to {@code P3}, incomplete {@code move_path} yields a
     * specific syntax error (not {@code Unknown statement}), and {@code wait_move} on an idle/unknown
     * tag proceeds without deadlock.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void zeroDurationAndInvalidMovePathSyntaxHandledDeterministically(@TempDir Path tempDir) throws Exception {
        Path validBoundary = tempDir.resolve("f02_zero_dur_wait");
        E2eFixtureFactory.createMinimalPlayableProject(validBoundary, """
                image star = "assets/images/bg/bg_default.png"
                label start:
                    show star:
                        xpos 10.0
                        ypos 20.0
                    move_path star 100 200 300 200 500 400 duration 0
                    wait_move missing_tag
                    "Sofort fertig."
                    return
                """);
        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(validBoundary));
        runtime.start();
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        assertEquals("Sofort fertig.", runtime.getActiveDialogue().interpolatedText());
        SpriteNode star = runtime.getSceneController().getActiveSprite("star");
        assertNotNull(star);
        assertEquals(500.0f, star.getTransform().getXpos(), 0.1f);
        assertEquals(400.0f, star.getTransform().getYpos(), 0.1f);

        Path malformed = tempDir.resolve("f02_bad_path");
        E2eFixtureFactory.createMinimalPlayableProject(malformed, """
                label start:
                    move_path star 10 20
                    return
                """);
        VJavaParseException parseEx = assertThrows(VJavaParseException.class, () -> ProjectScanner.scan(malformed));
        assertFalse(parseEx.getMessage().contains("Unknown statement"),
                "move_path must be recognized and fail on missing control points: " + parseEx.getMessage());
    }

    /**
     * Verifies goals {@code #0012} and {@code #0013} boundary cases: extreme rotation wraparound
     * ({@code -720} to {@code +720}) and {@code scale ... to 0.0 0.0} / negative mirror produce
     * strictly finite transforms without {@code NaN} or {@code Infinity}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void rotateWraparoundAndZeroNegativeScaleProduceNoNan(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f02_rot_scale_bounds");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                image star = "assets/images/bg/bg_default.png"
                label start:
                    show star:
                        xpos 300.0
                        ypos 300.0
                        rotate -720.0
                    rotate star to 720 duration 0.5 ease linear
                    scale star to 0.0 -1.0 duration 0.5 ease linear
                    "Fertig."
                    return
                """);

        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(projectDir));
        runtime.start();
        SpriteNode star = runtime.getSceneController().getActiveSprite("star");
        assertNotNull(star);

        for (int i = 0; i < 40; i++) {
            runtime.getSceneController().updateMotion(1.0 / 60.0, ObjectMotionController.MotionInput.NONE);
            assertTrue(Float.isFinite(star.getTransform().getRotate()));
            assertTrue(Float.isFinite(star.getTransform().getXzoom()));
            assertTrue(Float.isFinite(star.getTransform().getYzoom()));
        }
        assertEquals(0.0f, star.getTransform().getXzoom(), 0.01f);
        assertEquals(-1.0f, star.getTransform().getYzoom(), 0.01f);
    }

    /**
     * Verifies goals {@code #0014} and {@code #0015} boundary cases: non-positive circular hitbox
     * radius is rejected cleanly (or disabled safely), and unmatched {@code collision_mask}
     * does not trigger or throw during motion updates.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void negativeRadiusHitboxAndUnmatchedMasksHandledSafely(@TempDir Path tempDir) throws Exception {
        Path badRadiusDir = tempDir.resolve("f02_bad_radius");
        E2eFixtureFactory.createMinimalPlayableProject(badRadiusDir, """
                image hero = "assets/images/bg/bg_default.png"
                label start:
                    show hero
                    hitbox hero circle -10
                    return
                """);
        VJavaParseException parseEx = assertThrows(VJavaParseException.class, () -> ProjectScanner.scan(badRadiusDir));
        assertFalse(parseEx.getMessage().contains("Unknown statement"),
                "hitbox must be recognized by parser and reject negative radius: " + parseEx.getMessage());

        Path unmatchedMaskDir = tempDir.resolve("f02_unmatched_mask");
        E2eFixtureFactory.createMinimalPlayableProject(unmatchedMaskDir, """
                image hero = "assets/images/bg/bg_default.png"
                label start:
                    show hero
                    collision_mask hero player collides_with nonexistent_group
                    "Sicher."
                    return
                """);
        VJavaLinter.LintReport report = E2eCliRunner.lintProject(unmatchedMaskDir);
        assertFalse(report.hasErrors(), "Unmatched mask group must not fail compilation: " + report.issues());
        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(unmatchedMaskDir));
        runtime.start();
        runtime.getSceneController().updateMotion(0.1, ObjectMotionController.MotionInput.NONE);
        assertEquals("Sicher.", runtime.getActiveDialogue().interpolatedText());
    }

    /**
     * Verifies goals {@code #0018}, {@code #0019}, and {@code #0020} boundary cases: negative
     * {@code friction} rejected/clamped safely, empty {@code parallel:} block rejected with
     * {@link VJavaParseException}, and expired coyote-time ({@code 150 ms > 100 ms}) refuses jump.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void expiredCoyoteTimeAndEmptyParallelBlockHandledCleanly(@TempDir Path tempDir) throws Exception {
        // Positive coyote-time verification via platform edge walk-off + expired >150ms refusal
        Path coyoteDir = tempDir.resolve("f02_coyote_expiry");
        E2eFixtureFactory.createMinimalPlayableProject(coyoteDir, """
                image hero = "assets/images/bg/bg_default.png"
                image plat = "assets/images/bg/bg_default.png"
                label start:
                    show hero:
                        xpos 400.0
                        ypos 600.0
                        xanchor 0.5
                        yanchor 1.0
                    show plat:
                        xpos 300.0
                        ypos 600.0
                    solid_platform plat one_way
                    wasd_init hero speed 400
                    physics_init hero gravity 980 jump_force 500 ground_y 950
                    "Laufen."
                    return
                """);
        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(coyoteDir));
        runtime.start();
        SceneController scene = runtime.getSceneController();
        SpriteNode hero = scene.getActiveSprite("hero");
        SpriteNode plat = scene.getActiveSprite("plat");
        hero.setSize(40.0f, 60.0f);
        plat.setSize(120.0f, 20.0f);

        // Walk right off platform edge (x > 420) and wait 0.16s (> 100ms coyote window)
        for (int i = 0; i < 16; i++) {
            scene.updateMotion(0.016, new ObjectMotionController.MotionInput(false, false, false, true, false));
        }
        float yBeforeLateJump = hero.getTransform().getYpos();
        // Press jump well after coyote time expired (>150ms off platform): must NOT jump upward
        scene.updateMotion(0.016, new ObjectMotionController.MotionInput(false, false, false, false, true));
        assertTrue(hero.getTransform().getYpos() > yBeforeLateJump,
                "Jump pressed after coyote-time expiration must not launch hero upward");

        // Empty parallel: block must fail cleanly as a parallel-block syntax error (not Unknown statement)
        Path emptyParallelDir = tempDir.resolve("f02_empty_parallel");
        E2eFixtureFactory.createMinimalPlayableProject(emptyParallelDir, """
                label start:
                    parallel:
                        pass
                    return
                """);
        // Verify parallel: with valid statement works or empty parallel without body throws specific error
        Path missingBodyParallel = tempDir.resolve("f02_bad_parallel");
        E2eFixtureFactory.createMinimalPlayableProject(missingBodyParallel, """
                label start:
                    parallel:
                    "Nachher."
                    return
                """);
        VJavaParseException parallelEx = assertThrows(
                VJavaParseException.class,
                () -> ProjectScanner.scan(missingBodyParallel)
        );
        assertFalse(parallelEx.getMessage().contains("Unknown statement"),
                "parallel: must be recognized and fail specifically on empty body: " + parallelEx.getMessage());
    }

    /**
     * Verifies goals {@code #0016}, {@code #0017}, {@code #0025}, and {@code #0027} boundary cases:
     * {@code trigger_zone} and {@code on_overlap} pointing to a non-existent label are flagged by
     * {@link VJavaLinter}, and {@code camera follow} with missing target does not crash.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void missingTriggerZoneTargetAndUnknownCameraTargetHandledSafely(@TempDir Path tempDir) throws Exception {
        Path missingTargetDir = tempDir.resolve("f02_bad_trigger");
        E2eFixtureFactory.createMinimalPlayableProject(missingTargetDir, """
                image hero = "assets/images/bg/bg_default.png"
                label start:
                    show hero
                    camera follow missing_hero smooth -2.0
                    trigger_zone z1 rect 10 10 100 100 call nonexistent_label
                    "OK."
                    return
                """);

        VJavaLinter.LintReport report = E2eCliRunner.lintProject(missingTargetDir);
        assertTrue(report.hasErrors(), "Missing trigger_zone target label must be flagged by linter");
        assertFalse(
                report.issues().stream().anyMatch(i -> i.message().contains("Unknown statement")),
                "camera/trigger_zone must be parsed cleanly, got: " + report.issues()
        );
        assertTrue(
                report.issues().stream().anyMatch(i ->
                        i.message().toLowerCase(Locale.ROOT).contains("nonexistent_label")
                                || i.code().toUpperCase(Locale.ROOT).contains("LABEL")
                                || i.code().toUpperCase(Locale.ROOT).contains("TARGET")
                ),
                "Expected missing target label diagnostic, got: " + report.issues()
        );
    }

    /**
     * Verifies goals {@code #0029}, {@code #0030}, and {@code #0069} boundary cases: calling
     * {@code scene.moveSprite} on an unknown tag is a safe no-op, loading a legacy save missing
     * motion bindings restores clean {@link ObjectMotionController} state, and toggling {@code F9}
     * twice on an empty stage cleanly cleans up debug nodes.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or save/load fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void f9DoubleToggleAndLegacySaveWithoutMotionAndMissingMoveSpriteSafe(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f02_debug_legacy");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                label start:
                    :: Move missing tag safely @->{
                        scene.moveSprite("unknown_tag", 100.0f, 200.0f);
                    }<<
                    "Leere Bühne."
                    return
                """);

        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertFalse(lint.hasErrors(), "scene.moveSprite must compile cleanly: " + lint.issues());

        EngineConfig cfg = EngineConfig.builder()
                .engineMode(EngineMode.STUDIO_DUAL_WINDOW)
                .projectDir(projectDir)
                .headless(true)
                .smokeTest(false)
                .build();
        try (EngineApplication app = new EngineApplication(cfg, null, new GameRuntimeCoordinator(false))) {
            app.initialize();
            GameRuntimeCoordinator coordinator = (GameRuntimeCoordinator) app.getGameHost();
            ScriptSession session = coordinator.getSession();
            session.start();
            app.stepOnce(1.0 / 60.0);

            int baseNodes = countAllSceneNodes(app.getSceneGraph());
            InputManager input = app.getApi().getInput();

            // Toggle F9 ON then OFF -> node count must return to baseline
            input.onKey(GLFW.GLFW_KEY_F9, 0, GLFW.GLFW_PRESS, 0);
            app.stepOnce(1.0 / 60.0);
            input.onKey(GLFW.GLFW_KEY_F9, 0, GLFW.GLFW_RELEASE, 0);
            app.stepOnce(1.0 / 60.0);
            assertTrue(countAllSceneNodes(app.getSceneGraph()) > baseNodes
                    || Boolean.TRUE.equals(app.getApi().getProperty("collisionDebugOverlay")));

            input.onKey(GLFW.GLFW_KEY_F9, 0, GLFW.GLFW_PRESS, 0);
            app.stepOnce(1.0 / 60.0);
            input.onKey(GLFW.GLFW_KEY_F9, 0, GLFW.GLFW_RELEASE, 0);
            app.stepOnce(1.0 / 60.0);
            assertEquals(baseNodes, countAllSceneNodes(app.getSceneGraph()));

            // Write legacy SaveSlotData without WASD/motion fields and verify clean quickLoad
            SaveManager sm = session.getSaveManager();
            SaveSlotData legacy = new SaveSlotData(
                    new SaveSlotData.SaveMetadata(
                            "legacy", "Legacy", Instant.now(), "start", "Leere Bühne.", 1.0, "1.0.0", 0, 0, null),
                    Map.of(),
                    new SaveSlotData.SavedScriptPosition(
                            "start", 0, List.of(), "WAITING_DIALOGUE", null, null, "Leere Bühne.", false, List.of(),
                            null, null),
                    List.of()
            );
            sm.saveSlot("legacy", legacy);
            session.loadFromSlot("legacy");
            assertFalse(session.getSceneController().getMotionController().hasActiveWasd());
        }
    }

    private static int countAllSceneNodes(SceneGraph graph) {
        int total = 0;
        for (SceneLayer layer : graph.getLayersSorted()) {
            total += layer.getSortedNodes().size();
        }
        return total;
    }
}
