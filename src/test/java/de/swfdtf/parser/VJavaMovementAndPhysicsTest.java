package de.swfdtf.parser;

import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.graphics.ObjectMotionController;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.tools.VJavaLinter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for vJava sprite movement ({@code move}, {@code move_by}), WASD controls
 * ({@code wasd_init}, {@code wasd_stop}), 2D gravity/jump physics ({@code physics_init},
 * {@code physics_stop}), and AABB collision triggers ({@code on_collide ... jump ...}).
 */
class VJavaMovementAndPhysicsTest {

    @Test
    void parsesAllMotionPhysicsAndCollisionStatements() {
        String script = """
                label start:
                    show hero at left
                    show coin at right
                    move hero to 640 360 duration 1.5 ease ease_out
                    move_by coin -120 40 duration 0.5 ease ease_in_out
                    wasd_init hero speed 320 bounds 50 100 1800 950 diagonal_norm true
                    physics_init hero gravity 980 jump_force 500 ground_y 860
                    on_collide hero coin jump reached_goal
                    wasd_stop hero
                    physics_stop hero
                    return
                label reached_goal:
                    return
                """;
        VJavaAst.ScriptFileAst ast = VJavaParser.parse(Path.of("motion.vjava"), script);
        List<VJavaAst.Statement> stmts = ast.labels().get(0).statements();

        VJavaAst.MoveStatement absMove = (VJavaAst.MoveStatement) stmts.get(2);
        assertEquals("hero", absMove.tag());
        assertEquals(640.0, absMove.targetX());
        assertEquals(360.0, absMove.targetY());
        assertFalse(absMove.relative());
        assertEquals(1.5, absMove.durationSeconds());
        assertEquals("ease_out", absMove.easing());

        VJavaAst.MoveStatement relMove = (VJavaAst.MoveStatement) stmts.get(3);
        assertEquals("coin", relMove.tag());
        assertEquals(-120.0, relMove.targetX());
        assertEquals(40.0, relMove.targetY());
        assertTrue(relMove.relative());
        assertEquals("ease_in_out", relMove.easing());

        VJavaAst.WasdInitStatement wasdInit = (VJavaAst.WasdInitStatement) stmts.get(4);
        assertEquals("hero", wasdInit.tag());
        assertEquals(320.0, wasdInit.speedPxPerSec());
        assertEquals(50.0, wasdInit.minX());
        assertEquals(100.0, wasdInit.minY());
        assertEquals(1800.0, wasdInit.maxX());
        assertEquals(950.0, wasdInit.maxY());
        assertTrue(wasdInit.normalizeDiagonal());

        VJavaAst.PhysicsInitStatement physInit = (VJavaAst.PhysicsInitStatement) stmts.get(5);
        assertEquals("hero", physInit.tag());
        assertEquals(980.0, physInit.gravityPxPerSec2());
        assertEquals(500.0, physInit.jumpForcePxPerSec());
        assertEquals(860.0, physInit.groundY());

        VJavaAst.OnCollideStatement collide = (VJavaAst.OnCollideStatement) stmts.get(6);
        assertEquals("hero", collide.tagA());
        assertEquals("coin", collide.tagB());
        assertEquals("reached_goal", collide.targetLabel());

        assertEquals("hero", ((VJavaAst.WasdStopStatement) stmts.get(7)).tag());
        assertEquals("hero", ((VJavaAst.PhysicsStopStatement) stmts.get(8)).tag());

        assertThrows(VJavaParseException.class, () -> VJavaParser.parse(Path.of("bad.vjava"), """
                label bad:
                    on_collide a b wrong_kw target
                """));
    }

    @Test
    void executesTweensWasdDiagonalNormalizationBoundsPhysicsAndCollisionJump(@TempDir Path tempDir) throws Exception {
        String script = """
                image bg_room = "assets/images/bg.png"
                image hero = "assets/images/hero.png"
                image coin = "assets/images/coin.png"

                label start:
                    scene bg_room
                    show hero
                    show coin
                    move hero to 100 700
                    move coin to 800 770
                    wasd_init hero speed 200 bounds 0 0 1200 1000 diagonal_norm true
                    physics_init hero gravity 1000 jump_force 500 ground_y 800
                    on_collide hero coin jump goal_hit
                    "Steuere den Helden mit WASD zum Ziel!"
                    return

                label goal_hit:
                    wasd_stop hero
                    physics_stop hero
                    "Ziel berührt!"
                    return
                """;
        createDummyPng(tempDir.resolve("assets/images/bg.png"));
        createDummyPng(tempDir.resolve("assets/images/hero.png"));
        createDummyPng(tempDir.resolve("assets/images/coin.png"));
        ProjectDescriptor.save(tempDir, ProjectDescriptor.createDefault("Motion Test"));
        writeUtf8(tempDir.resolve("main.vjava"), script);

        VJavaLinter.LintReport report = VJavaLinter.lint(tempDir);
        assertTrue(report.isClean(), () -> "Expected clean lint, got: " + report.issues());

        VJavaAst.ProjectAst ast = ProjectScanner.scan(tempDir);
        ScriptRuntime runtime = new ScriptRuntime(ast);
        runtime.start();

        SceneController sc = runtime.getSceneController();
        ObjectMotionController mc = sc.getMotionController();
        assertTrue(mc.hasActiveWasd());
        assertTrue(mc.hasActivePhysics());
        assertEquals(1, mc.getCollisionTriggerCount());

        SpriteNode hero = sc.getActiveSprite("hero");
        SpriteNode coin = sc.getActiveSprite("coin");
        assertNotNull(hero);
        assertNotNull(coin);
        hero.setSize(64.0f, 64.0f);
        coin.setSize(64.0f, 64.0f);

        assertEquals(100.0f, hero.getTransform().getXpos(), 0.01f);

        // Physics drops hero onto groundY=800
        sc.updateMotion(0.5, ObjectMotionController.MotionInput.NONE);
        assertEquals(800.0f, hero.getTransform().getYpos(), 0.01f);
        assertTrue(mc.isGrounded("hero"));

        // Timed move_by tween with ease_out interpolates coin horizontally
        mc.startMove("coin", -200.0f, 0.0f, true, 1.0, "ease_out");
        sc.updateMotion(0.5, ObjectMotionController.MotionInput.NONE);
        assertEquals(650.0f, coin.getTransform().getXpos(), 0.5f); // ease_out(0.5) = 0.75 -> 800 - 150 = 650

        // Move hero towards coin with WASD + jump; physics constrains vertical WASD, jump lifts hero above ground
        sc.updateMotion(0.1, new ObjectMotionController.MotionInput(false, false, false, true, true));
        assertTrue(hero.getTransform().getXpos() > 100.0f);
        assertTrue(hero.getTransform().getYpos() < 800.0f);
        assertFalse(mc.isGrounded("hero"));

        // Walk all the way into coin to trigger on_collide -> jump goal_hit
        for (int i = 0; i < 60 && !"goal_hit".equals(runtime.getCurrentLabel()); i++) {
            sc.updateMotion(0.05, new ObjectMotionController.MotionInput(false, false, false, true, false));
        }

        assertEquals("goal_hit", runtime.getCurrentLabel());
        assertFalse(mc.hasActiveWasd());
        assertFalse(mc.hasActivePhysics());
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        assertEquals("Ziel berührt!", runtime.getActiveDialogue().interpolatedText());
    }

    @Test
    void linterFlagsMissingCollisionLabelAsError(@TempDir Path tempDir) throws Exception {
        createDummyPng(tempDir.resolve("assets/images/bg.png"));
        createDummyPng(tempDir.resolve("assets/images/a.png"));
        createDummyPng(tempDir.resolve("assets/images/b.png"));
        ProjectDescriptor.save(tempDir, ProjectDescriptor.createDefault("Broken Collide"));
        writeUtf8(tempDir.resolve("main.vjava"), """
                image bg = "assets/images/bg.png"
                image a = "assets/images/a.png"
                image b = "assets/images/b.png"

                label start:
                    scene bg
                    show a
                    show b
                    on_collide a b jump does_not_exist
                    return
                """);

        VJavaLinter.LintReport report = VJavaLinter.lint(tempDir);
        assertTrue(report.hasErrors());
        assertTrue(report.issues().stream().anyMatch(i -> "MISSING_COLLIDE_TARGET".equals(i.code())));
    }

    private static void createDummyPng(Path file) {
        try {
            Files.createDirectories(file.getParent());
            byte[] px = new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255};
            TextureManager.writePng(file, 1, 1, px);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static void writeUtf8(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
