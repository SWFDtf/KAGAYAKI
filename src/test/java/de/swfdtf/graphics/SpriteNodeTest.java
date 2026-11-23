package de.swfdtf.graphics;

import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Rect2f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpriteNodeTest {

    @Test
    void synchronizesTextureAndNineSliceMetadataAndDimensions() {
        List<String> inputAttrs = new ArrayList<>(List.of("happy", "uniform"));
        SpriteNode sprite = new SpriteNode("eileen_1", "eileen", inputAttrs);
        inputAttrs.add("mutated");

        assertEquals("eileen", sprite.getImageTag());
        assertEquals("eileen", sprite.getTag());
        assertEquals(List.of("happy", "uniform"), sprite.getAttributes());
        assertThrows(UnsupportedOperationException.class, () -> sprite.getAttributes().add("x"));

        Texture tex = new Texture(17, 480, 960, "characters/eileen_happy.png", true, false);
        sprite.setAssetPath("characters/eileen_happy.png");
        sprite.setTexture(tex);

        assertSame(tex, sprite.getTexture());
        assertEquals(17, sprite.getTextureId());
        assertEquals(480.0f, sprite.getWidth());
        assertEquals(960.0f, sprite.getHeight());
        assertTrue(sprite.shouldDrawStandardQuad());

        NineSliceDrawable frame = new NineSliceDrawable(new Texture(23, 128, 64, "ui/frame.png", true, false), 16.0f);
        sprite.setNineSlice(frame);

        assertSame(frame, sprite.getNineSlice());
        assertEquals(23, sprite.getTextureId());
        assertEquals(128.0f, sprite.getWidth());
        assertEquals(64.0f, sprite.getHeight());
        assertFalse(sprite.shouldDrawStandardQuad());

        sprite.setSize(900.0f, 220.0f);
        assertEquals(900.0f, sprite.getWidth());
        assertEquals(220.0f, sprite.getHeight());

        sprite.setNineSlice(null);
        assertEquals(17, sprite.getTextureId());
        assertEquals(480.0f, sprite.getWidth());
        assertEquals(960.0f, sprite.getHeight());
        assertTrue(sprite.shouldDrawStandardQuad());
    }

    @Test
    void customDisplayablePreferredSizeAndUpdateForwarding() {
        final double[] accumulatedDt = {0.0};
        CustomDisplayable custom = new CustomDisplayable() {
            @Override
            public void update(double dt) {
                accumulatedDt[0] += dt;
            }

            @Override
            public void render(RenderPipeline2D pipeline, Rect2f bounds, float alpha, ColorMatrix4f colorMatrix) {
            }

            @Override
            public float getPreferredWidth() {
                return 320.0f;
            }

            @Override
            public float getPreferredHeight() {
                return 180.0f;
            }
        };

        SpriteNode node = new SpriteNode("fx_node");
        node.setCustomDisplayable(custom);

        assertSame(custom, node.getCustomDisplayable());
        assertFalse(node.shouldDrawStandardQuad());
        assertEquals(320.0f, node.getWidth());
        assertEquals(180.0f, node.getHeight());

        node.update(0.25);
        node.update(0.50);
        assertEquals(0.75, accumulatedDt[0], 1e-9);
    }
}
