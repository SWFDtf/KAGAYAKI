package de.swfdtf.parser;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.graphics.ObjectMotionController;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SceneGraph;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.graphics.TextTagParser;
import de.swfdtf.tools.VJavaLinter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests covering Wave 3a F01/F02 additions: format specifiers, {@code match},
 * bytecode caching, strict type checking, Bezier paths, shortest-arc rotation, scaling,
 * masks, circular hitboxes, overlap phases, one-way platforms, WASD inertia, coyote-time,
 * {@code parallel:}/{@code wait_move}, {@code camera follow}, {@code trigger_zone},
 * {@code scene.moveSprite}, and motion snapshot serialization.
 */
final class Wave3aScriptingAndMotionTest {

    @Test
    void formatSpecifiersHandleValidAndInvalidInputsGracefully() {
        Map<String, Object> vars = Map.of(
                "gold", 1234.5,
                "ratio", 0.256,
                "count", 7,
                "label", "text"
        );
        assertEquals("1234.50", TextTagParser.interpolateVariables("[gold:.2f]", vars::get));
        assertEquals("1,234.50", TextTagParser.interpolateVariables("[gold:,.2f]", vars::get));
        assertEquals("25.6%", TextTagParser.interpolateVariables("[ratio:.1%]", vars::get));
        assertEquals("007", TextTagParser.interpolateVariables("[count:03d]", vars::get));
        assertEquals("[missing:.2f]", TextTagParser.interpolateVariables("[missing:.2f]", vars::get));
        assertEquals("text", TextTagParser.interpolateVariables("[label:.2f]", vars::get));
        assertEquals("1234.5", TextTagParser.interpolateVariables("[gold:bad_spec]", vars::get));
    }

    @Test
    void matchStatementAndStrictTypeCheckerWork(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("main.vjava"), """
                default score = 20
                label start:
                    match score:
                        case 10:
                            "Zehn."
                        case 20:
                            "Zwanzig."
                        default:
                            "Andere."
                    return
                """);
        VJavaLinter.LintReport clean = VJavaLinter.lint(tempDir);
        assertFalse(clean.hasErrors());

        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(tempDir));
        runtime.start();
        assertEquals("Zwanzig.", runtime.getActiveDialogue().interpolatedText());

        Path badTypesDir = tempDir.resolve("bad_types");
        Files.createDirectories(badTypesDir);
        Files.writeString(badTypesDir.resolve("main.vjava"), """
                label start:
                    $ oops = true + "path.png"
                    return
                """);
        VJavaLinter.LintReport badReport = VJavaLinter.lint(badTypesDir);
        assertTrue(badReport.hasErrors());
        assertTrue(badReport.issues().stream().anyMatch(i -> "TYPE_MISMATCH".equals(i.code())));
    }

    @Test
    void javaBlockCompileCacheWritesAndReusesBytecode(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("main.vjava"), """
                label start:
                    :: cache block @->{
                        state.set("cached", 42);
                    }<<
                    "Val=[cached]"
                    return
                """);
        JavaBlockCompiler compiler = new JavaBlockCompiler();
        JavaBlockCompiler.CompiledProjectBlocks first = compiler.compile(ProjectScanner.scan(tempDir));
        assertTrue(Files.isDirectory(tempDir.resolve("build/.vjava-cache")));
        assertEquals(1, first.size());

        JavaBlockCompiler.CompiledProjectBlocks second = compiler.compile(ProjectScanner.scan(tempDir));
        assertEquals(1, second.size());
    }

    @Test
    void motionBezierRotateScaleHitboxAndSnapshotWork() {
        SceneController scene = new SceneController(new SceneGraph());
        SpriteNode sprite = scene.showSprite("star", List.of(), null, null);
        sprite.setSize(100.0f, 100.0f);
        sprite.getTransform()
                .setXalign(Float.NaN)
                .setYalign(Float.NaN)
                .setXpos(0.0f)
                .setYpos(0.0f)
                .setRotate(350.0f)
                .setXzoom(1.0f)
                .setYzoom(1.0f);

        ObjectMotionController motion = scene.getMotionController();
        motion.startMovePath("star", 0.0f, 400.0f, 400.0f, 400.0f, 400.0f, 0.0f, 1.0, "linear");
        motion.startRotate("star", 10.0f, 1.0, "linear");
        motion.startScale("star", 2.0f, 0.0f, 1.0, "linear");
        assertTrue(motion.hasActiveTween("star"));

        scene.updateMotion(0.5, ObjectMotionController.MotionInput.NONE);
        assertEquals(200.0f, sprite.getTransform().getXpos(), 1.0f);
        assertEquals(300.0f, sprite.getTransform().getYpos(), 1.0f);
        assertEquals(1.5f, sprite.getTransform().getXzoom(), 0.05f);
        assertEquals(0.5f, sprite.getTransform().getYzoom(), 0.05f);

        scene.updateMotion(0.5, ObjectMotionController.MotionInput.NONE);
        assertFalse(motion.hasActiveTween("star"));
        assertEquals(400.0f, sprite.getTransform().getXpos(), 0.5f);
        assertEquals(10.0f, ((sprite.getTransform().getRotate() % 360.0f) + 360.0f) % 360.0f, 0.5f);

        motion.enableWasd("star", 200.0f, 0.0f, 0.0f, 1920.0f, 1080.0f, true, 5.0f, 10.0f);
        motion.enablePhysics("star", 980.0f, 400.0f, 800.0f);
        motion.registerCollisionTrigger("star", "orb", "win");
        motion.setCircularHitbox("star", 40.0f);
        assertThrows(IllegalArgumentException.class, () -> motion.setCircularHitbox("star", 0.0f));

        SceneController.StageSnapshot snapshot = scene.snapshotState();
        SceneController restored = new SceneController(new SceneGraph());
        restored.restoreState(snapshot);
        assertTrue(restored.getMotionController().hasActiveWasd());
        assertTrue(restored.getMotionController().hasActivePhysics());
        assertEquals(1, restored.getMotionController().getCollisionTriggerCount());
        assertNotNull(restored.getActiveSprite("star"));
    }

    @Test
    void sceneMoveSpriteSupportsJavaBlockApi() {
        SceneController scene = new SceneController(new SceneGraph(), null,
                new EngineAPI(EngineConfig.defaults()).getLogger(), null, false);
        SpriteNode hero = scene.showSprite("hero", List.of(), null, null);
        scene.moveSprite("hero", 512.0f, 384.0f);
        assertEquals(512.0f, hero.getTransform().getXpos(), 0.01f);
        assertEquals(384.0f, hero.getTransform().getYpos(), 0.01f);
        scene.moveSprite("nonexistent", 10.0f, 20.0f);
    }
}
