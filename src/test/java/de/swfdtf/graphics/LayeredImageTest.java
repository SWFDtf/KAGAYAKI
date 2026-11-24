package de.swfdtf.graphics;

import de.swfdtf.audio.LipSyncAnalyzer;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.parser.VJavaParser;
import de.swfdtf.state.SimplePersistentAccess;
import de.swfdtf.state.SimpleStateStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayeredImageTest {

    private static VJavaAst.LayeredImageDef parseLenaDef() {
        String src = """
                layeredimage lena:
                    always:
                        "assets/char/lena_base.png"
                    group outfit:
                        attribute uniform default:
                            "assets/char/lena_uniform.png"
                        attribute casual if unlocked_casual:
                            "assets/char/lena_casual.png"
                    group face:
                        attribute neutral default:
                            "assets/char/lena_neutral.png"
                        attribute happy:
                            "assets/char/lena_happy.png"
                    group mouth:
                        attribute closed default:
                            "assets/char/lena_mouth_closed.png"
                        attribute half:
                            "assets/char/lena_mouth_half.png"
                        attribute open:
                            "assets/char/lena_mouth_open.png"
                    if has_glasses:
                        "assets/char/lena_glasses.png"
                """;
        VJavaAst.ScriptFileAst ast = VJavaParser.parse(Path.of("lena.vjava"), src);
        assertEquals(1, ast.layeredImages().size());
        return ast.layeredImages().get(0);
    }

    @Test
    void resolvesAlwaysGroupDefaultsMutuallyExclusiveAndConditionals() {
        VJavaAst.LayeredImageDef def = parseLenaDef();
        LayeredImage layered = new LayeredImage(def);
        SimpleStateStore state = new SimpleStateStore();
        SimplePersistentAccess persistent = new SimplePersistentAccess();

        // Initial defaults: uniform + neutral + closed, without glasses
        layered.setAttributes(List.of(), state, persistent);
        assertEquals(List.of(
                "assets/char/lena_base.png",
                "assets/char/lena_uniform.png",
                "assets/char/lena_neutral.png",
                "assets/char/lena_mouth_closed.png"
        ), layered.getResolvedLayerAssets());
        assertEquals("uniform", layered.getActiveAttribute("outfit"));
        assertEquals("neutral", layered.getActiveAttribute("face"));
        assertEquals("closed", layered.getActiveAttribute("mouth"));

        // Selecting "casual" when unlocked_casual is false falls back to default "uniform";
        // "happy" overrides "neutral" in mutually exclusive face group; glasses off
        layered.setAttributes(List.of("casual", "happy"), state, persistent);
        assertEquals("uniform", layered.getActiveAttribute("outfit"));
        assertEquals("happy", layered.getActiveAttribute("face"));
        assertEquals(List.of(
                "assets/char/lena_base.png",
                "assets/char/lena_uniform.png",
                "assets/char/lena_happy.png",
                "assets/char/lena_mouth_closed.png"
        ), layered.getResolvedLayerAssets());

        // Enable conditional variables
        state.set("unlocked_casual", true);
        state.set("has_glasses", true);
        layered.setAttributes(List.of("casual", "happy"), state, persistent);
        assertEquals("casual", layered.getActiveAttribute("outfit"));
        assertEquals(List.of(
                "assets/char/lena_base.png",
                "assets/char/lena_casual.png",
                "assets/char/lena_happy.png",
                "assets/char/lena_mouth_closed.png",
                "assets/char/lena_glasses.png"
        ), layered.getResolvedLayerAssets());
    }

    @Test
    void crossfadesSmoothlyWhenGroupAttributeChanges() {
        VJavaAst.LayeredImageDef def = parseLenaDef();
        LayeredImage layered = new LayeredImage(def);
        assertEquals(0.20f, layered.getCrossfadeDurationSeconds(), 1e-6f);

        layered.setAttributes(List.of("neutral"), null, null);
        assertFalse(layered.isCrossfading());
        assertEquals(1.0f, layered.getGroupCurrentAlpha("face"), 1e-5f);
        assertEquals(0.0f, layered.getGroupPreviousAlpha("face"), 1e-5f);

        // Switch face from neutral -> happy: triggers 0.20s crossfade
        layered.setAttributes(List.of("happy"), null, null);
        assertTrue(layered.isCrossfading());
        assertEquals(1.0f, layered.getGroupPreviousAlpha("face"), 1e-5f);
        assertEquals(0.0f, layered.getGroupCurrentAlpha("face"), 1e-5f);

        // Halfway at 0.10s: previous 0.5, current 0.5
        layered.update(0.10);
        assertTrue(layered.isCrossfading());
        assertEquals(0.5f, layered.getGroupPreviousAlpha("face"), 1e-4f);
        assertEquals(0.5f, layered.getGroupCurrentAlpha("face"), 1e-4f);

        List<LayeredImage.LayerRenderInfo> midLayers = layered.getActiveRenderLayers();
        assertTrue(midLayers.stream().anyMatch(l -> l.assetPath().equals("assets/char/lena_neutral.png") && Math.abs(l.alpha() - 0.5f) < 1e-3f));
        assertTrue(midLayers.stream().anyMatch(l -> l.assetPath().equals("assets/char/lena_happy.png") && Math.abs(l.alpha() - 0.5f) < 1e-3f));

        // Finish at +0.10s (total 0.20s)
        layered.update(0.10);
        assertFalse(layered.isCrossfading());
        assertEquals(0.0f, layered.getGroupPreviousAlpha("face"), 1e-5f);
        assertEquals(1.0f, layered.getGroupCurrentAlpha("face"), 1e-5f);
    }

    @Test
    void lipSyncBindingDrivesMouthGroupAttributes() {
        LayeredImage layered = LayeredImage.builder("speaker")
                .always("assets/char/body.png")
                .attribute("mouth", "closed", true, "assets/char/m_closed.png")
                .attribute("mouth", "half", false, "assets/char/m_half.png")
                .attribute("mouth", "open", false, "assets/char/m_open.png")
                .crossfadeDuration(0.0f)
                .build();

        assertTrue(layered.applyMouthShape(LipSyncAnalyzer.MouthShape.HALF_OPEN));
        assertEquals("half", layered.getActiveAttribute("mouth"));
        assertTrue(layered.getResolvedLayerAssets().contains("assets/char/m_half.png"));

        LipSyncAnalyzer analyzer = new LipSyncAnalyzer(0.0, 0.0, 0.01f, 0.50f, 0.08f, 0.45f);
        layered.bindLipSync(analyzer);
        assertEquals("closed", layered.getActiveAttribute("mouth"));

        analyzer.updateFromRms(0.48f, 0.05);
        assertEquals(LipSyncAnalyzer.MouthShape.OPEN, analyzer.getMouthShape());
        layered.update(0.016);
        assertEquals("open", layered.getActiveAttribute("mouth"));
        assertTrue(layered.getResolvedLayerAssets().contains("assets/char/m_open.png"));

        layered.setMouthGroupName("custom_mouth");
        assertFalse(layered.applyMouthShape(LipSyncAnalyzer.MouthShape.CLOSED));
    }

    @Test
    void sceneControllerIntegratesLayeredImageAndPreservesCrossfadeAcrossShowSprite() {
        SceneGraph graph = new SceneGraph();
        SceneController controller = new SceneController(graph);
        VJavaAst.LayeredImageDef def = parseLenaDef();
        controller.registerLayeredImage(def);

        SpriteNode first = controller.showSprite("lena", List.of("neutral"), "center", "dissolve(0.2)");
        assertNotNull(first);
        LayeredImage layered = assertInstanceOf(LayeredImage.class, first.getCustomDisplayable());
        assertEquals("neutral", layered.getActiveAttribute("face"));
        assertFalse(layered.isCrossfading());

        // Replace sprite on same tag with new attribute: preserves same LayeredImage and initiates crossfade
        SpriteNode second = controller.showSprite("lena", List.of("happy"), null, null);
        assertSame(layered, second.getCustomDisplayable());
        assertEquals("happy", layered.getActiveAttribute("face"));
        assertTrue(layered.isCrossfading());
        assertEquals(0.15f, second.getTransform().getXalign(), 1e-5f > 0 ? 1.0f : 0.0f);
        assertEquals(0.5f, second.getTransform().getXalign(), 1e-5f);

        controller.update(0.25);
        assertFalse(layered.isCrossfading());
    }
}
