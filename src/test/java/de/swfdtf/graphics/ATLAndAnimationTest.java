package de.swfdtf.graphics;

import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Easing;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Transform2D;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.parser.VJavaParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ATLAndAnimationTest {

    private static final float EPS = 1e-4f;

    @Test
    void linearEaseInEaseOutAndPauseInterpolateAccurately() {
        ATLTransform anim = ATLTransform.builder()
                .set().xpos(0.0f).alpha(0.0f)
                .linear(1.0).xpos(100.0f)
                .pause(0.5)
                .easeIn(1.0).alpha(1.0f)
                .easeOut(1.0).zoom(2.0f)
                .build();

        Transform2D target = new Transform2D();
        anim.start(target);
        assertFalse(anim.isFinished());
        assertEquals(0.0f, target.getXpos(), EPS);
        assertEquals(0.0f, target.getAlpha(), EPS);

        // Midpoint of linear 1.0s
        anim.update(0.5, target);
        assertEquals(50.0f, target.getXpos(), EPS);
        assertEquals(0.0f, target.getAlpha(), EPS);

        // Finish linear + halfway through pause
        anim.update(0.75, target);
        assertEquals(100.0f, target.getXpos(), EPS);
        assertEquals(0.0f, target.getAlpha(), EPS);

        // Finish pause + halfway through easeIn (t=0.5)
        anim.update(0.75, target);
        assertEquals(Easing.easeIn(0.5f), target.getAlpha(), EPS);
        assertEquals(1.0f, target.getZoom(), EPS);

        // Finish easeIn + halfway through easeOut (t=0.5)
        anim.update(1.0, target);
        assertEquals(1.0f, target.getAlpha(), EPS);
        assertEquals(1.0f + Easing.easeOut(0.5f), target.getZoom(), EPS);
        assertFalse(anim.isFinished());

        // Finish easeOut
        anim.update(0.5, target);
        assertEquals(2.0f, target.getZoom(), EPS);
        assertTrue(anim.isFinished());

        // Reset restores initial timeline cleanly
        anim.reset();
        assertFalse(anim.isStarted());
        assertFalse(anim.isFinished());
        Transform2D fresh = new Transform2D();
        anim.start(fresh);
        assertEquals(0.0f, fresh.getXpos(), EPS);
        assertEquals(0.0f, fresh.getAlpha(), EPS);
    }

    @Test
    void finiteAndInfiniteRepeatBehaveDeterministically() {
        ATLTransform finiteRepeat = ATLTransform.builder()
                .set().xpos(0.0f)
                .linear(1.0).xpos(100.0f)
                .repeat(3)
                .build();

        Transform2D target = new Transform2D();
        finiteRepeat.start(target);
        finiteRepeat.update(1.5, target); // 1 full cycle + 0.5 of cycle 2
        assertFalse(finiteRepeat.isFinished());
        assertEquals(50.0f, target.getXpos(), EPS);

        finiteRepeat.update(1.0, target); // finish cycle 2 + 0.5 of cycle 3
        assertFalse(finiteRepeat.isFinished());
        assertEquals(50.0f, target.getXpos(), EPS);

        finiteRepeat.update(0.5, target); // finish cycle 3
        assertTrue(finiteRepeat.isFinished());
        assertEquals(100.0f, target.getXpos(), EPS);

        // Infinite repeat (count <= 0) never finishes and zero-duration loop never hangs
        ATLTransform infinite = ATLTransform.builder()
                .set().alpha(0.2f)
                .linear(0.5).alpha(0.8f)
                .repeat(0)
                .build();
        Transform2D loopTarget = new Transform2D();
        infinite.start(loopTarget);
        for (int i = 0; i < 12; i++) {
            infinite.update(0.25, loopTarget);
            assertFalse(infinite.isFinished());
        }

        ATLTransform zeroDurationLoop = ATLTransform.builder()
                .set().alpha(0.5f)
                .repeat(-1)
                .build();
        Transform2D zeroTarget = new Transform2D();
        zeroDurationLoop.start(zeroTarget);
        zeroDurationLoop.update(0.1, zeroTarget);
        assertEquals(0.5f, zeroTarget.getAlpha(), EPS);
        assertFalse(zeroDurationLoop.isFinished());
    }

    @Test
    void parallelBlockAndChoiceExecuteCorrectly() {
        ATLTransform parallel = ATLTransform.builder()
                .parallel(
                        ATLTransform.builder().linear(1.0).alpha(0.2f).build(),
                        ATLTransform.builder().easeOut(2.0).rotate(180.0f).build()
                )
                .block(List.of(
                        ATLTransform.builder().pause(0.25).build(),
                        ATLTransform.builder().set().zoom(1.5f).build()
                ))
                .build();

        Transform2D target = new Transform2D();
        parallel.start(target);

        parallel.update(1.0, target);
        assertEquals(0.2f, target.getAlpha(), EPS);
        assertEquals(180.0f * Easing.easeOut(0.5f), target.getRotate(), EPS);
        assertEquals(1.0f, target.getZoom(), EPS);
        assertFalse(parallel.isFinished());

        parallel.update(1.0, target);
        assertEquals(180.0f, target.getRotate(), EPS);
        assertEquals(1.0f, target.getZoom(), EPS); // paused 0.25s still pending
        assertFalse(parallel.isFinished());

        parallel.update(0.25, target);
        assertEquals(1.5f, target.getZoom(), EPS);
        assertTrue(parallel.isFinished());

        // Choice with seeded Random selects multiple branches over repeated runs
        Random seeded = new Random(42L);
        ATLTransform choiceAnim = ATLTransform.builder()
                .choice(List.of(
                        ATLTransform.builder().set().alpha(0.1f).build(),
                        ATLTransform.builder().set().alpha(0.5f).build(),
                        ATLTransform.builder().set().alpha(0.9f).build()
                ), seeded)
                .build();

        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            Transform2D t = new Transform2D();
            choiceAnim.start(t);
            assertTrue(choiceAnim.isFinished());
            seen.add(Math.round(t.getAlpha() * 10));
        }
        assertEquals(Set.of(1, 5, 9), seen);
    }

    @Test
    void matrixColorCropAndVJavaAstConversionWorkTogether() {
        // Parse all matrixcolor functions directly
        assertArrayEquals(ColorMatrix4f.brightness(0.2f).toFloatArray(),
                ATLTransform.parseColorMatrix("brightness(0.2)").toFloatArray(), EPS);
        assertArrayEquals(ColorMatrix4f.contrast(1.4f).toFloatArray(),
                ATLTransform.parseColorMatrix("contrast(1.4)").toFloatArray(), EPS);
        assertArrayEquals(ColorMatrix4f.saturation(0.5f).toFloatArray(),
                ATLTransform.parseColorMatrix("saturation(0.5)").toFloatArray(), EPS);
        assertArrayEquals(ColorMatrix4f.hueRotate(90.0f).toFloatArray(),
                ATLTransform.parseColorMatrix("hue(90)").toFloatArray(), EPS);
        assertArrayEquals(ColorMatrix4f.tint(0.2f, 0.4f, 0.8f).toFloatArray(),
                ATLTransform.parseColorMatrix("tint(0.2, 0.4, 0.8)").toFloatArray(), EPS);
        assertArrayEquals(ColorMatrix4f.invert().toFloatArray(),
                ATLTransform.parseColorMatrix("invert()").toFloatArray(), EPS);

        ColorMatrix4f expectedChained = ColorMatrix4f.tint(1.0f, 0.5f, 0.0f)
                .multiply(ColorMatrix4f.brightness(0.15f));
        assertArrayEquals(expectedChained.toFloatArray(),
                ATLTransform.parseColorMatrix("TintMatrix('#ff7f00') * BrightnessMatrix(0.15)").toFloatArray(), 0.01f);

        assertThrows(IllegalArgumentException.class, () -> ATLTransform.parseColorMatrix("unknown_op(1)"));

        // Crop interpolation + matrixcolor interpolation via parsed .vjava transform AST
        String script = """
                transform stylized_reveal:
                    crop (0, 0, 200, 100)
                    parallax_x 0.1
                    parallax_y 0.2
                    linear 1.0 crop (40, 20, 100, 50) matrixcolor invert() xalign 1.0 yalign 0.5
                """;
        VJavaAst.ScriptFileAst ast = VJavaParser.parse(Path.of("atl_test.vjava"), script);
        ATLTransform anim = ATLTransform.fromAst(ast.transforms().get(0));

        Transform2D target = new Transform2D().setXalign(0.0f).setYalign(0.0f);
        anim.start(target);
        assertEquals(new Rect2f(0, 0, 200, 100), target.getCrop());
        assertEquals(0.1f, target.getParallaxX(), EPS);
        assertEquals(0.2f, target.getParallaxY(), EPS);

        anim.update(0.5, target);
        assertEquals(new Rect2f(20, 10, 150, 75), target.getCrop());
        assertEquals(0.5f, target.getXalign(), EPS);
        assertEquals(0.25f, target.getYalign(), EPS);
        assertArrayEquals(ColorMatrix4f.identity().lerp(ColorMatrix4f.invert(), 0.5f).toFloatArray(),
                target.getColorMatrix().toFloatArray(), EPS);

        anim.update(0.5, target);
        assertTrue(anim.isFinished());
        assertEquals(new Rect2f(40, 20, 100, 50), target.getCrop());
        assertArrayEquals(ColorMatrix4f.invert().toFloatArray(), target.getColorMatrix().toFloatArray(), EPS);
    }

    @Test
    void animationEngineManagesPlayStopReplaceAndCompletion() {
        AnimationEngine engine = new AnimationEngine();
        SceneNode nodeA = new SceneNode("nodeA");
        SceneNode nodeB = new SceneNode("nodeB");
        AtomicInteger completions = new AtomicInteger();

        ATLTransform fadeA = ATLTransform.builder()
                .set().alpha(0.0f)
                .linear(1.0).alpha(1.0f)
                .build();
        ATLTransform spinB = ATLTransform.builder()
                .linear(2.0).rotate(360.0f)
                .build();

        engine.play("fadeA", nodeA, fadeA, completions::incrementAndGet);
        engine.play("spinB", nodeB, spinB);

        assertEquals(2, engine.getActiveCount());
        assertTrue(engine.isPlaying("fadeA"));
        assertTrue(engine.isPlaying("spinB"));

        engine.update(0.5);
        assertEquals(0.5f, nodeA.getTransform().getAlpha(), EPS);
        assertEquals(90.0f, nodeB.getTransform().getRotate(), EPS);

        engine.update(0.5);
        assertEquals(1.0f, nodeA.getTransform().getAlpha(), EPS);
        assertEquals(1, completions.get());
        assertFalse(engine.isPlaying("fadeA"));
        assertTrue(engine.isPlaying("spinB"));
        assertEquals(1, engine.getActiveCount());

        // Instant set finishes immediately on play()
        ATLTransform instant = ATLTransform.builder().set().xpos(777.0f).build();
        engine.play("instant", nodeA, instant, completions::incrementAndGet);
        assertEquals(777.0f, nodeA.getTransform().getXpos(), EPS);
        assertEquals(2, completions.get());
        assertFalse(engine.isPlaying("instant"));

        assertTrue(engine.stop("spinB"));
        assertFalse(engine.stop("spinB"));
        assertEquals(0, engine.getActiveCount());

        engine.play("spinB2", nodeB, ATLTransform.builder().linear(1.0).zoom(2.0f).build());
        assertEquals(1, engine.getActiveCount());
        engine.stopAll();
        assertEquals(0, engine.getActiveCount());
    }
}
