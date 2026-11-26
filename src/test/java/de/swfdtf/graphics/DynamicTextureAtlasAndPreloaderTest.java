package de.swfdtf.graphics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless contract tests for {@link DynamicTextureAtlas} sprite batching ({@code #0171})
 * and {@link TextureManager} multithreaded CPU asset preloading ({@code #0179}, {@code #0940}).
 */
final class DynamicTextureAtlasAndPreloaderTest {

    @Test
    void dynamicAtlasPacksRegionsAutoGrowsAndReducesBindSwitches() {
        try (DynamicTextureAtlas atlas = new DynamicTextureAtlas(64, 512, 2)) {
            byte[] red = solidRgba(24, 24, 255, 40, 40, 255);
            byte[] green = solidRgba(24, 24, 40, 255, 40, 255);
            byte[] blue = solidRgba(24, 24, 40, 40, 255, 255);
            byte[] white = solidRgba(24, 24, 255, 255, 255, 128);

            atlas.pack("red", 24, 24, red);
            atlas.pack("green", 24, 24, green);
            atlas.pack("blue", 24, 24, blue);
            atlas.pack("white", 24, 24, white);
            // Force shelf grow beyond initial 64x64
            atlas.pack("extra1", 40, 40, solidRgba(40, 40, 120, 120, 200, 255));

            assertTrue(atlas.getAtlasWidth() >= 128);
            assertEquals(5, atlas.getRegionCount());
            assertTrue(atlas.contains("red"));
            DynamicTextureAtlas.AtlasRegion r = atlas.getRegion("red").orElseThrow();
            assertTrue(r.uvRect().width() > 0.0f && r.uvRect().height() > 0.0f);

            DynamicTextureAtlas.BatchStats stats = atlas.computeBatchStats(
                    List.of("red", "green", "blue", "white", "extra1")
            );
            assertEquals(5, stats.originalBindCount());
            assertEquals(1, stats.atlasBindCount());
            assertTrue(stats.bindReductionRatio() >= 0.60, "Expected >= 60% bind reduction");
        }
    }

    @Test
    void multithreadedPreloaderDecodesValidAssetsAndHandlesMissingSafely(@TempDir Path tempDir) throws Exception {
        Path pngA = tempDir.resolve("a.png");
        Path pngB = tempDir.resolve("b.png");
        Path missing = tempDir.resolve("missing.png");
        TextureManager.writePng(pngA, 8, 8, solidRgba(8, 8, 10, 20, 30, 255));
        TextureManager.writePng(pngB, 12, 6, solidRgba(12, 6, 40, 50, 60, 255));

        TextureManager tm = new TextureManager(tempDir);
        try {
            int okCount = tm.preloadAllAsync(List.of(pngA, pngB, missing)).get(5, TimeUnit.SECONDS);
            assertEquals(2, okCount);
            assertEquals(2, tm.getPreloadedCount());
            assertTrue(tm.isPreloaded(pngA));
            assertTrue(tm.isPreloaded("b.png"));
            assertFalse(tm.isPreloaded(missing));
            assertFalse(tm.preloadAsync("").get(2, TimeUnit.SECONDS));
        } finally {
            tm.dispose();
        }
    }

    private static byte[] solidRgba(int w, int h, int r, int g, int b, int a) {
        byte[] out = new byte[w * h * 4];
        for (int i = 0; i < out.length; i += 4) {
            out[i] = (byte) r;
            out[i + 1] = (byte) g;
            out[i + 2] = (byte) b;
            out[i + 3] = (byte) a;
        }
        return out;
    }
}
