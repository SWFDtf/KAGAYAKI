package de.swfdtf.graphics;

import de.swfdtf.math.Rect2f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RenderPipeline2DMathTest {

    private static final float EPS = 1e-4f;

    @Test
    void letterboxViewportFor16x9ExactAndScaledMatchesWindow() {
        Rect2f exact = RenderPipeline2D.computeLetterboxViewport(1920, 1080, 1920, 1080);
        assertEquals(0.0f, exact.x(), EPS);
        assertEquals(0.0f, exact.y(), EPS);
        assertEquals(1920.0f, exact.width(), EPS);
        assertEquals(1080.0f, exact.height(), EPS);

        Rect2f scaled4k = RenderPipeline2D.computeLetterboxViewport(3840, 2160, 1920, 1080);
        assertEquals(0.0f, scaled4k.x(), EPS);
        assertEquals(0.0f, scaled4k.y(), EPS);
        assertEquals(3840.0f, scaled4k.width(), EPS);
        assertEquals(2160.0f, scaled4k.height(), EPS);
    }

    @Test
    void letterboxViewportFor21x9ProducesCenteredPillarbox() {
        // 2560x1080 ultrawide (21:9-ish) for 1920x1080 logical
        Rect2f vp = RenderPipeline2D.computeLetterboxViewport(2560, 1080, 1920, 1080);
        assertEquals(320.0f, vp.x(), EPS);
        assertEquals(0.0f, vp.y(), EPS);
        assertEquals(1920.0f, vp.width(), EPS);
        assertEquals(1080.0f, vp.height(), EPS);
    }

    @Test
    void letterboxViewportFor4x3ProducesCenteredLetterbox() {
        // 1024x768 (4:3): scale = 1024 / 1920 -> destW = 1024, destH = 576, y = (768 - 576) / 2 = 96
        Rect2f vp = RenderPipeline2D.computeLetterboxViewport(1024, 768, 1920, 1080);
        assertEquals(0.0f, vp.x(), EPS);
        assertEquals(96.0f, vp.y(), EPS);
        assertEquals(1024.0f, vp.width(), EPS);
        assertEquals(576.0f, vp.height(), EPS);
    }

    @Test
    void letterboxViewportFor1x1SquareProducesCenteredLetterbox() {
        // 1000x1000 (1:1): scale = 1000 / 1920 -> destW = 1000, destH = 562.5, y = 218.75
        Rect2f vp = RenderPipeline2D.computeLetterboxViewport(1000, 1000, 1920, 1080);
        assertEquals(0.0f, vp.x(), EPS);
        assertEquals(218.75f, vp.y(), EPS);
        assertEquals(1000.0f, vp.width(), EPS);
        assertEquals(562.5f, vp.height(), EPS);
    }

    @Test
    void letterboxHandlesZeroWindowAndRejectsInvalidLogicalSize() {
        Rect2f minimized = RenderPipeline2D.computeLetterboxViewport(0, 0, 1920, 1080);
        assertEquals(new Rect2f(0.0f, 0.0f, 0.0f, 0.0f), minimized);

        assertThrows(IllegalArgumentException.class,
                () -> RenderPipeline2D.computeLetterboxViewport(1920, 1080, 0, 1080));
        assertThrows(IllegalArgumentException.class,
                () -> RenderPipeline2D.computeLetterboxViewport(1920, 1080, 1920, -1));
    }

    @Test
    void renderModeFromStringParsesAliasesAndRejectsInvalidValues() {
        assertEquals(RenderMode.MODE_2D, RenderMode.fromString("2D"));
        assertEquals(RenderMode.MODE_2D, RenderMode.fromString(" 2d "));
        assertEquals(RenderMode.MODE_2D, RenderMode.fromString("MODE_2D"));
        assertEquals(RenderMode.MODE_3D, RenderMode.fromString("3D"));
        assertEquals(RenderMode.MODE_3D, RenderMode.fromString("mode_3d"));
        assertEquals(RenderMode.MODE_3D, RenderMode.fromString("MODE_3D"));

        assertThrows(IllegalArgumentException.class, () -> RenderMode.fromString(null));
        assertThrows(IllegalArgumentException.class, () -> RenderMode.fromString("  "));
        assertThrows(IllegalArgumentException.class, () -> RenderMode.fromString("VR"));
    }
}
