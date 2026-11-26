package de.swfdtf.graphics;

import de.swfdtf.math.Easing;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransitionManagerTest {

    private static final float EPS = 1e-4f;

    @Test
    void parsesAllStandardTransitionExpressions() {
        assertEquals(Transition.Kind.NONE, Transition.parse(null).getKind());
        assertEquals(Transition.Kind.NONE, Transition.parse("").getKind());
        assertEquals(Transition.Kind.NONE, Transition.parse("none").getKind());

        Transition dissolve = Transition.parse("dissolve(0.8)");
        assertEquals(Transition.Kind.DISSOLVE, dissolve.getKind());
        assertEquals(0.8f, dissolve.getDurationSeconds(), EPS);

        Transition fadeDefault = Transition.parse("fade(0.5)");
        assertEquals(Transition.Kind.FADE, fadeDefault.getKind());
        assertEquals(0.5f, fadeDefault.getDurationSeconds(), EPS);
        assertEquals("#000000", fadeDefault.getFadeColorHex());

        Transition fadeWhite = Transition.parse("fade(0.5, #ffffff)");
        assertEquals(Transition.Kind.FADE, fadeWhite.getKind());
        assertEquals("#ffffff", fadeWhite.getFadeColorHex());
        assertEquals(1.0f, fadeWhite.getFadeColorRgba()[0], EPS);

        Transition easeIn = Transition.parse("easein(0.4)");
        assertEquals(Transition.Kind.EASE, easeIn.getKind());
        assertEquals(0.4f, easeIn.getDurationSeconds(), EPS);
        assertEquals(Easing.EaseType.EASE_IN, easeIn.getEaseType());

        assertEquals(Transition.Kind.EASE, Transition.parse("ease(0.3)").getKind());
        assertEquals(Transition.Kind.EASE, Transition.parse("easeout(0.3)").getKind());
        assertEquals(Transition.Kind.MOVE, Transition.parse("move(0.5)").getKind());
        assertEquals(Transition.Kind.PIXELLATE, Transition.parse("pixellate(0.6)").getKind());
        assertEquals(Transition.Kind.WIPE_LEFT, Transition.parse("wipeleft(0.5)").getKind());
        assertEquals(Transition.Kind.WIPE_RIGHT, Transition.parse("wiperight(0.5)").getKind());
        assertEquals(Transition.Kind.WIPE_UP, Transition.parse("wipeup(0.5)").getKind());
        assertEquals(Transition.Kind.WIPE_DOWN, Transition.parse("wipedown(0.5)").getKind());
        assertEquals(Transition.Kind.SLIDE_LEFT, Transition.parse("slideleft(0.5)").getKind());
        assertEquals(Transition.Kind.SLIDE_RIGHT, Transition.parse("slideright(0.5)").getKind());
        assertEquals(Transition.Kind.PUSH_LEFT, Transition.parse("pushleft(0.5)").getKind());
        assertEquals(Transition.Kind.PUSH_RIGHT, Transition.parse("pushright(0.5)").getKind());
        assertEquals(Transition.Kind.IRIS_IN, Transition.parse("irisin(0.5)").getKind());
        assertEquals(Transition.Kind.IRIS_OUT, Transition.parse("irisout(0.5)").getKind());

        Transition blinds = Transition.parse("blinds(0.6, 12)");
        assertEquals(Transition.Kind.BLINDS, blinds.getKind());
        assertEquals(0.6f, blinds.getDurationSeconds(), EPS);
        assertEquals(12, blinds.getSliceCount());

        Transition squares = Transition.parse("squares(0.6, 16)");
        assertEquals(Transition.Kind.SQUARES, squares.getKind());
        assertEquals(0.6f, squares.getDurationSeconds(), EPS);
        assertEquals(16, squares.getSliceCount());

        assertThrows(IllegalArgumentException.class, () -> Transition.parse("nonexistent_fx(1.0)"));
    }

    @Test
    void parsesImageDissolveAndMultipleTransitions() {
        Transition imgDissolve = Transition.parse("imagedissolve('mask.png', 1.0, ramplen=16)");
        assertEquals(Transition.Kind.IMAGE_DISSOLVE, imgDissolve.getKind());
        assertEquals("mask.png", imgDissolve.getMaskImagePath());
        assertEquals(1.0f, imgDissolve.getDurationSeconds(), EPS);
        assertEquals(16.0f, imgDissolve.getRampLength(), EPS);
        assertFalse(imgDissolve.isReverseMask());

        Transition reversed = Transition.parse("ImageDissolve(\"assets/masks/wipe.png\", 1.2, 24, true)");
        assertEquals(Transition.Kind.IMAGE_DISSOLVE, reversed.getKind());
        assertEquals("assets/masks/wipe.png", reversed.getMaskImagePath());
        assertEquals(1.2f, reversed.getDurationSeconds(), EPS);
        assertEquals(24.0f, reversed.getRampLength(), EPS);
        assertTrue(reversed.isReverseMask());

        Transition multi = Transition.parse("multiple(fade(0.4, #ffffff), dissolve(0.6))");
        assertEquals(Transition.Kind.MULTIPLE, multi.getKind());
        assertEquals(1.0f, multi.getDurationSeconds(), EPS);
        assertEquals(2, multi.getSubTransitions().size());
        assertEquals(Transition.Kind.FADE, multi.getSubTransitions().get(0).getKind());
        assertEquals("#ffffff", multi.getSubTransitions().get(0).getFadeColorHex());
        assertEquals(Transition.Kind.DISSOLVE, multi.getSubTransitions().get(1).getKind());

        Transition renpyMulti = Transition.parse(
                "MultipleTransition([False, Fade(0.25, 0.0, 0.25), True, Dissolve(0.5)])");
        assertEquals(Transition.Kind.MULTIPLE, renpyMulti.getKind());
        assertEquals(1.0f, renpyMulti.getDurationSeconds(), EPS);
        assertEquals(2, renpyMulti.getSubTransitions().size());
    }

    @Test
    void transitionManagerTracksSingleAndMultipleProgressHeadlessly() {
        TransitionManager manager = new TransitionManager();
        assertFalse(manager.isActive());
        assertEquals(0.0f, manager.getProgress(), EPS);
        assertNull(manager.getActiveTransition());

        // None / zero-duration completes immediately
        manager.startTransition(Transition.none());
        assertFalse(manager.isActive());
        assertEquals(1.0f, manager.getProgress(), EPS);

        // Single dissolve
        manager.startTransition(Transition.dissolve(0.8f));
        assertTrue(manager.isActive());
        assertNotNull(manager.getActiveTransition());
        assertEquals(0.0f, manager.getProgress(), EPS);

        manager.update(0.4);
        assertTrue(manager.isActive());
        assertEquals(0.5f, manager.getProgress(), EPS);
        assertEquals(0, manager.getActiveStepIndex());
        assertEquals(0.5f, manager.getActiveStepProgress(), EPS);

        manager.update(0.4);
        assertFalse(manager.isActive());
        assertNull(manager.getActiveTransition());
        assertEquals(1.0f, manager.getProgress(), EPS);

        // Multiple sequential progress
        Transition multi = Transition.multiple(List.of(
                Transition.fade(0.4f, "#000000"),
                Transition.wipeLeft(0.6f),
                Transition.dissolve(1.0f)
        ));
        manager.startTransition(multi);
        assertTrue(manager.isActive());
        assertEquals(0, manager.getActiveStepIndex());

        manager.update(0.2);
        assertEquals(0.1f, manager.getProgress(), EPS);
        assertEquals(0, manager.getActiveStepIndex());
        assertEquals(0.5f, manager.getActiveStepProgress(), EPS);
        assertEquals(Transition.Kind.FADE, manager.getCurrentEffectiveTransition().getKind());

        manager.update(0.5); // total = 0.7s -> inside step 1 (0.4..1.0s), 0.3/0.6 = 0.5
        assertEquals(0.35f, manager.getProgress(), EPS);
        assertEquals(1, manager.getActiveStepIndex());
        assertEquals(0.5f, manager.getActiveStepProgress(), EPS);
        assertEquals(Transition.Kind.WIPE_LEFT, manager.getCurrentEffectiveTransition().getKind());

        manager.update(0.8); // total = 1.5s -> inside step 2 (1.0..2.0s), 0.5/1.0 = 0.5
        assertEquals(0.75f, manager.getProgress(), EPS);
        assertEquals(2, manager.getActiveStepIndex());
        assertEquals(0.5f, manager.getActiveStepProgress(), EPS);
        assertEquals(Transition.Kind.DISSOLVE, manager.getCurrentEffectiveTransition().getKind());

        manager.cancel();
        assertFalse(manager.isActive());
        assertEquals(0.0f, manager.getProgress(), EPS);
        assertNull(manager.getActiveTransition());

        // Headless GL safety checks
        assertFalse(manager.isGlInitialized());
        manager.disposeGl();
        assertThrows(IllegalStateException.class, () -> manager.captureFromFbo(new Framebuffer()));
    }
}
