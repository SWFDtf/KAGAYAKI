package de.swfdtf.graphics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureMathTest {

    @Test
    void premultiplyRgbaComputesExactRoundedScaledChannelsWithoutMutatingInput() {
        byte[] straight = new byte[]{
            (byte) 255, (byte) 128, (byte) 64, (byte) 255,   // opaque: unchanged
            (byte) 255, (byte) 128, (byte) 64, (byte) 0,     // transparent: zero RGB, zero A
            (byte) 255, (byte) 200, (byte) 50, (byte) 128,   // (c * 128 + 127) / 255 -> 128, 100, 25, 128
            (byte) 255, (byte) 100, (byte) 1,  (byte) 1      // (255*1+127)/255=1, (100*1+127)/255=0
        };
        byte[] snapshot = straight.clone();

        byte[] actual = Texture.premultiplyRgba(straight);
        byte[] expected = new byte[]{
            (byte) 255, (byte) 128, (byte) 64, (byte) 255,
            (byte) 0,   (byte) 0,   (byte) 0,  (byte) 0,
            (byte) 128, (byte) 100, (byte) 25, (byte) 128,
            (byte) 1,   (byte) 0,   (byte) 0,  (byte) 1
        };

        assertArrayEquals(expected, actual);
        assertArrayEquals(snapshot, straight);
        assertArrayEquals(new byte[0], Texture.premultiplyRgba(new byte[0]));
        assertThrows(NullPointerException.class, () -> Texture.premultiplyRgba(null));
        assertThrows(IllegalArgumentException.class, () -> Texture.premultiplyRgba(new byte[]{1, 2, 3}));
    }

    @Test
    void generateMissingAssetRgbaProducesMagentaAndDarkPurpleHatchedFrameWith2PxBorder() {
        int width = 32;
        int height = 24;
        byte[] pixels = TextureManager.generateMissingAssetRgba(width, height);
        assertEquals(width * height * 4, pixels.length);

        boolean sawMagentaInterior = false;
        boolean sawDarkPurpleInterior = false;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = (y * width + x) * 4;
                int r = pixels[idx] & 0xFF;
                int g = pixels[idx + 1] & 0xFF;
                int b = pixels[idx + 2] & 0xFF;
                int a = pixels[idx + 3] & 0xFF;
                assertEquals(0xFF, a);

                boolean isBorder = x < 2 || x >= width - 2 || y < 2 || y >= height - 2;
                boolean isMagenta = (r == 0xFF && g == 0x00 && b == 0xFF);
                boolean isDarkPurple = (r == 0x1A && g == 0x0A && b == 0x1E);

                if (isBorder) {
                    assertTrue(isMagenta, "Border pixel at (" + x + "," + y + ") must be #FF00FF");
                } else {
                    assertTrue(isMagenta || isDarkPurple,
                            "Interior pixel at (" + x + "," + y + ") must be #FF00FF or #1A0A1E");
                    if (isMagenta) {
                        sawMagentaInterior = true;
                    }
                    if (isDarkPurple) {
                        sawDarkPurpleInterior = true;
                    }
                }
            }
        }

        assertTrue(sawMagentaInterior);
        assertTrue(sawDarkPurpleInterior);

        assertThrows(IllegalArgumentException.class, () -> TextureManager.generateMissingAssetRgba(0, 16));
        assertThrows(IllegalArgumentException.class, () -> TextureManager.generateMissingAssetRgba(16, -4));
    }

    @Test
    void writePngCreatesParentDirsAndRoundTripsThroughStbImage(@TempDir Path tempDir) throws Exception {
        int width = 5;
        int height = 3;
        byte[] original = new byte[]{
            // Row 0 (top)
            (byte) 255, (byte) 0,   (byte) 0,   (byte) 255,
            (byte) 0,   (byte) 255, (byte) 0,   (byte) 255,
            (byte) 0,   (byte) 0,   (byte) 255, (byte) 255,
            (byte) 255, (byte) 255, (byte) 0,   (byte) 180,
            (byte) 12,  (byte) 34,  (byte) 56,  (byte) 0,
            // Row 1 (middle)
            (byte) 200, (byte) 100, (byte) 50,  (byte) 255,
            (byte) 10,  (byte) 20,  (byte) 30,  (byte) 90,
            (byte) 128, (byte) 128, (byte) 128, (byte) 255,
            (byte) 255, (byte) 0,   (byte) 255, (byte) 255,
            (byte) 26,  (byte) 10,  (byte) 30,  (byte) 255,
            // Row 2 (bottom)
            (byte) 0,   (byte) 255, (byte) 255, (byte) 255,
            (byte) 250, (byte) 240, (byte) 230, (byte) 255,
            (byte) 1,   (byte) 2,   (byte) 3,   (byte) 4,
            (byte) 64,  (byte) 128, (byte) 192, (byte) 220,
            (byte) 255, (byte) 255, (byte) 255, (byte) 255
        };

        Path pngPath = tempDir.resolve("nested/assets/test_frame.png");
        TextureManager.writePng(pngPath, width, height, original);
        assertTrue(Files.isRegularFile(pngPath));

        int[] outSize = new int[2];
        byte[] decodedViaHelper = TextureManager.readImageFileRgba(pngPath, outSize);
        assertEquals(width, outSize[0]);
        assertEquals(height, outSize[1]);
        assertArrayEquals(original, decodedViaHelper);

        // Also read back directly via STBImage to verify raw PNG compliance
        byte[] pngBytes = Files.readAllBytes(pngPath);
        ByteBuffer rawBuffer = MemoryUtil.memAlloc(pngBytes.length);
        ByteBuffer stbDecoded = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            rawBuffer.put(pngBytes).flip();
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer c = stack.mallocInt(1);
            stbDecoded = STBImage.stbi_load_from_memory(rawBuffer, w, h, c, STBImage.STBI_rgb_alpha);
            assertNotNull(stbDecoded);
            assertEquals(width, w.get(0));
            assertEquals(height, h.get(0));
            assertEquals(4, c.get(0));

            byte[] stbRgba = new byte[width * height * 4];
            stbDecoded.get(0, stbRgba);
            assertArrayEquals(original, stbRgba);
        } finally {
            if (stbDecoded != null) {
                stbDecoded.clear();
                STBImage.stbi_image_free(stbDecoded);
            }
            MemoryUtil.memFree(rawBuffer);
        }

        // Verify error handling for missing and corrupt files
        assertThrows(RuntimeException.class,
                () -> TextureManager.readImageFileRgba(tempDir.resolve("does_not_exist.png"), new int[2]));

        Path corruptPath = tempDir.resolve("corrupt.png");
        Files.writeString(corruptPath, "not a valid png file");
        assertThrows(IllegalStateException.class,
                () -> TextureManager.readImageFileRgba(corruptPath, new int[2]));
    }
}
