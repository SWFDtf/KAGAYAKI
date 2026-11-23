package de.swfdtf.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameClockTest {

    @Test
    void stepsDeterministicallyAtSixtyHzAndComputesAlpha() {
        GameClock clock = new GameClock();

        int halfStep = clock.advanceFrame(GameClock.FIXED_TIMESTEP * 0.5);
        assertEquals(0, halfStep);
        assertEquals(0.5, clock.getInterpolationAlpha(), 1e-6);
        assertEquals(0L, clock.getTickCount());

        int fullStep = clock.advanceFrame(GameClock.FIXED_TIMESTEP * 0.5);
        assertEquals(1, fullStep);
        assertEquals(1L, clock.getTickCount());
        assertEquals(GameClock.FIXED_TIMESTEP, clock.getTotalTimeSeconds(), 1e-9);
        assertEquals(0.0, clock.getInterpolationAlpha(), 1e-6);
    }

    @Test
    void capsSpiralOfDeathAndTracksFps() {
        GameClock clock = new GameClock();
        int steps = clock.advanceFrame(5.0);
        assertEquals(GameClock.MAX_STEPS_PER_FRAME, steps);
        assertEquals(0.0, clock.getInterpolationAlpha(), 1e-9);

        clock.reset();
        for (int i = 0; i < 60; i++) {
            clock.advanceFrame(1.0 / 60.0);
        }
        assertEquals(60L, clock.getTickCount());
        assertEquals(60, clock.getCurrentFps());
    }

    @Test
    void respectsPauseAndTimeScale() {
        GameClock clock = new GameClock();
        clock.setPaused(true);
        assertEquals(0, clock.advanceFrame(0.1));
        assertEquals(0L, clock.getTickCount());

        clock.setPaused(false);
        clock.setTimeScale(2.0);
        int steps = clock.advanceFrame(GameClock.FIXED_TIMESTEP);
        assertEquals(2, steps);
        assertEquals(2L, clock.getTickCount());
        assertTrue(clock.getTotalTimeSeconds() > 0.0);

        assertThrows(IllegalArgumentException.class, () -> clock.setTimeScale(-0.5));
    }
}
