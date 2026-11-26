package de.swfdtf.graphics;

import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Transform2D;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless contract and math tests for {@link RenderPipeline2D}, scissor stack nesting,
 * Gaussian/bloom post-processing math, {@link SceneNode} soft shadows, and {@link Transform2D}
 * blur sanitization ({@code #0174}, {@code #0175}, {@code #0176}, {@code #0181}, {@code #0182}, {@code #0940}).
 */
final class RenderPipeline2DContractTest {

    @Test
    void scissorStackIntersectsNestedRectsAndGuardsUnderflow() {
        RenderPipeline2D pipeline = new RenderPipeline2D();
        assertEquals(0, pipeline.getScissorDepth());
        assertTrue(pipeline.getActiveScissor().isEmpty());
        assertThrows(IllegalStateException.class, pipeline::popScissor);

        Rect2f outer = pipeline.pushScissor(new Rect2f(100.0f, 100.0f, 500.0f, 400.0f));
        assertEquals(1, pipeline.getScissorDepth());
        assertEquals(new Rect2f(100.0f, 100.0f, 500.0f, 400.0f), outer);

        Rect2f inner = pipeline.pushScissor(new Rect2f(450.0f, 350.0f, 300.0f, 300.0f));
        assertEquals(2, pipeline.getScissorDepth());
        assertEquals(new Rect2f(450.0f, 350.0f, 150.0f, 150.0f), inner);

        Rect2f disjoint = pipeline.pushScissor(new Rect2f(0.0f, 0.0f, 50.0f, 50.0f));
        assertEquals(0.0f, disjoint.width(), 1e-4f);
        assertEquals(0.0f, disjoint.height(), 1e-4f);

        int[] glBox = RenderPipeline2D.toGlScissorBox(new Rect2f(10.0f, 20.0f, 100.0f, 50.0f), 1080);
        assertEquals(10, glBox[0]);
        assertEquals(1010, glBox[1]);
        assertEquals(100, glBox[2]);
        assertEquals(50, glBox[3]);

        pipeline.popScissor();
        pipeline.popScissor();
        pipeline.popScissor();
        assertEquals(0, pipeline.getScissorDepth());
        assertThrows(IllegalStateException.class, pipeline::popScissor);
    }

    @Test
    void postProcessConfigGaussianWeightsAndBloomBrightnessAreNormalizedAndSafe() {
        RenderPipeline2D pipeline = new RenderPipeline2D();
        assertEquals(RenderPipeline2D.PostProcessConfig.NONE, pipeline.getPostProcessConfig());
        assertFalse(pipeline.isBloomEnabled());

        pipeline.setSceneBlurRadius(-4.0f);
        assertEquals(0.0f, pipeline.getSceneBlurRadius(), 1e-5f);
        pipeline.setSceneBlurRadius(6.5f);
        assertEquals(6.5f, pipeline.getSceneBlurRadius(), 1e-5f);

        pipeline.setBloomConfig(0.8f, 1.4f);
        assertTrue(pipeline.isBloomEnabled());

        RenderPipeline2D.PostProcessConfig sanitized =
                new RenderPipeline2D.PostProcessConfig(Float.NaN, 2.0f, -1.0f);
        assertEquals(0.0f, sanitized.gaussianBlurRadius(), 1e-5f);
        assertEquals(1.0f, sanitized.bloomThreshold(), 1e-5f);
        assertEquals(0.0f, sanitized.bloomIntensity(), 1e-5f);

        float[] weights = RenderPipeline2D.computeGaussianWeights(2.0f, 4);
        assertEquals(9, weights.length);
        float sum = 0.0f;
        for (float w : weights) {
            assertTrue(w > 0.0f);
            sum += w;
        }
        assertEquals(1.0f, sum, 1e-4f);
        assertTrue(weights[4] > weights[0]);

        assertEquals(0.0f, RenderPipeline2D.computeBloomBrightness(0.1f, 0.1f, 0.1f, 0.7f), 1e-4f);
        assertTrue(RenderPipeline2D.computeBloomBrightness(1.0f, 1.0f, 1.0f, 0.7f) > 0.9f);
    }

    @Test
    void sceneNodeSoftShadowAndTransformBlurSanitization() {
        SceneNode node = new SceneNode("panel");
        assertFalse(node.hasShadow());

        node.setShadow(4.0f, 8.0f, 12.0f, "#00000080");
        assertTrue(node.hasShadow());
        assertEquals(4.0f, node.getShadowOffsetX(), 1e-4f);
        assertEquals(8.0f, node.getShadowOffsetY(), 1e-4f);
        assertEquals(12.0f, node.getShadowRadius(), 1e-4f);

        float[] premulShadow = node.getPremultipliedShadowColor(0.5f);
        assertTrue(premulShadow[3] > 0.2f && premulShadow[3] < 0.3f);

        node.setShadowRadius(Float.NaN);
        assertFalse(node.hasShadow());
        assertEquals(0.0f, node.getShadowRadius(), 1e-4f);

        float innerAlpha = RenderPipeline2D.computeSoftShadowAlpha(-5.0f, 10.0f);
        float edgeAlpha = RenderPipeline2D.computeSoftShadowAlpha(0.0f, 10.0f);
        float outerAlpha = RenderPipeline2D.computeSoftShadowAlpha(12.0f, 10.0f);
        assertTrue(innerAlpha > edgeAlpha);
        assertTrue(edgeAlpha > outerAlpha);
        assertEquals(0.0f, outerAlpha, 1e-4f);

        Transform2D tf = new Transform2D().setBlur(-10.0f);
        assertEquals(0.0f, tf.getBlur(), 1e-5f);
        tf.setBlur(Float.POSITIVE_INFINITY);
        assertEquals(0.0f, tf.getBlur(), 1e-5f);
        tf.setBlur(8.0f);
        assertEquals(8.0f, tf.getBlur(), 1e-5f);
    }
}
