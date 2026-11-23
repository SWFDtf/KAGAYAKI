package de.swfdtf.graphics;

import de.swfdtf.math.Rect2f;
import de.swfdtf.math.ScreenShake;
import de.swfdtf.math.Vec2f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneGraphTest {

    private static final float EPS = 1e-4f;

    @Test
    void standardLayersArePrepopulatedAndSortedByZOrder() {
        SceneGraph graph = new SceneGraph();
        List<SceneLayer> sorted = graph.getLayersSorted();

        assertEquals(8, sorted.size());
        assertEquals(List.of(
                SceneGraph.LAYER_BACKGROUND,
                SceneGraph.LAYER_MASTER,
                SceneGraph.LAYER_FOREGROUND,
                SceneGraph.LAYER_SCREENS,
                SceneGraph.LAYER_OVERLAY,
                SceneGraph.LAYER_MODAL,
                SceneGraph.LAYER_TOAST,
                SceneGraph.LAYER_INSPECTOR
        ), sorted.stream().map(SceneLayer::getName).toList());

        assertEquals(List.of(
                SceneLayer.Z_BACKGROUND,
                SceneLayer.Z_MASTER,
                SceneLayer.Z_FOREGROUND,
                SceneLayer.Z_SCREENS,
                SceneLayer.Z_OVERLAY,
                SceneLayer.Z_MODAL,
                SceneLayer.Z_TOAST,
                SceneLayer.Z_INSPECTOR
        ), sorted.stream().map(SceneLayer::getZOrder).toList());

        assertEquals(-100, SceneLayer.Z_BACKGROUND);
        assertEquals(0, SceneLayer.Z_MASTER);
        assertEquals(100, SceneLayer.Z_FOREGROUND);
        assertEquals(200, SceneLayer.Z_SCREENS);
        assertEquals(300, SceneLayer.Z_OVERLAY);
        assertEquals(400, SceneLayer.Z_MODAL);
        assertEquals(500, SceneLayer.Z_TOAST);
        assertEquals(1000, SceneLayer.Z_INSPECTOR);
    }

    @Test
    void getOrCreateLayerInsertsInCorrectZOrder() {
        SceneGraph graph = new SceneGraph();
        SceneLayer sub = graph.getOrCreateLayer("sub_master", -50);
        SceneLayer same = graph.getOrCreateLayer("sub_master", 999);
        assertSame(sub, same);
        assertEquals(-50, sub.getZOrder());

        List<SceneLayer> sorted = graph.getLayersSorted();
        assertEquals(9, sorted.size());
        assertEquals("background", sorted.get(0).getName());
        assertEquals("sub_master", sorted.get(1).getName());
        assertEquals("master", sorted.get(2).getName());
    }

    @Test
    void nodeSortingAndTagLookupRemovalWorkCleanly() {
        SceneGraph graph = new SceneGraph();

        SceneNode nBack = new SceneNode("back", "character").setZOrder(10);
        SceneNode nMid1 = new SceneNode("mid1", "character").setZOrder(0);
        SceneNode nMid2 = new SceneNode("mid2", "prop").setZOrder(0);
        SceneNode nFront = new SceneNode("front", "fx").setZOrder(-5);

        graph.addNode(SceneGraph.LAYER_MASTER, nBack);
        graph.addNode(SceneGraph.LAYER_MASTER, nMid1);
        graph.addNode(SceneGraph.LAYER_MASTER, nMid2);
        graph.addNode(SceneGraph.LAYER_MASTER, nFront);

        SceneLayer master = graph.getLayer(SceneGraph.LAYER_MASTER);
        assertNotNull(master);

        List<SceneNode> sorted = master.getSortedNodes();
        assertEquals(List.of(nFront, nMid1, nMid2, nBack), sorted);

        List<SceneNode> chars = master.findByTag("character");
        assertEquals(List.of(nBack, nMid1), chars);
        assertSame(nMid2, master.findById("mid2"));

        assertEquals(2, graph.removeByTag(SceneGraph.LAYER_MASTER, "character"));
        assertEquals(List.of(nFront, nMid2), master.getSortedNodes());
        assertTrue(master.findByTag("character").isEmpty());
        assertEquals(0, graph.removeByTag("missing_layer", "character"));

        assertThrows(IllegalArgumentException.class, () -> graph.addNode("nonexistent", new SceneNode()));

        graph.clearAll();
        assertTrue(master.isEmpty());
        assertEquals(8, graph.getLayersSorted().size());
    }

    @Test
    void sceneNodeDefaultsHexColorsChildrenAndGraphState() {
        SceneNode parent = new SceneNode("panel", "ui")
                .setSize(400.0f, 200.0f)
                .setColorHex("#8b949e7f")
                .setBorderColorHex("#2e334a")
                .setBorderThickness(1.0f)
                .setCornerRadius(12.0f);

        assertTrue(parent.isVisible());
        assertEquals(new Rect2f(0.0f, 0.0f, 1.0f, 1.0f), parent.getUvRect());
        assertEquals(0, parent.getTextureId());

        float[] expectedStraight = new float[]{
                0x8b / 255.0f,
                0x94 / 255.0f,
                0x9e / 255.0f,
                0x7f / 255.0f
        };
        assertArrayEquals(expectedStraight, parent.getFillColorRgba(), EPS);

        float[] premul = parent.getPremultipliedFillColor(0.5f);
        float effA = expectedStraight[3] * 0.5f;
        assertArrayEquals(new float[]{
                expectedStraight[0] * effA,
                expectedStraight[1] * effA,
                expectedStraight[2] * effA,
                effA
        }, premul, EPS);

        SceneNode child = new SceneNode("label");
        parent.addChild(child);
        assertSame(parent, child.getParent());
        assertEquals(1, parent.getChildren().size());

        assertThrows(IllegalArgumentException.class, () -> parent.addChild(parent));
        assertThrows(IllegalArgumentException.class, () -> child.addChild(parent));
        assertTrue(parent.removeChild(child));
        assertNull(child.getParent());
        assertFalse(parent.removeChild(child));

        SceneGraph graph = new SceneGraph();
        graph.setNormalizedMouse(2.5f, -3.0f);
        assertEquals(new Vec2f(1.0f, -1.0f), graph.getNormalizedMouse());

        ScreenShake shake = ScreenShake.vpunch();
        graph.setScreenShake(shake);
        graph.update(0.05f);
        assertNotNull(graph.getScreenShake());
        graph.update(2.0f);
        assertNull(graph.getScreenShake());
    }
}
