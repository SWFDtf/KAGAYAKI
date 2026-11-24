package de.swfdtf.tools;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.ui.Fixed;
import de.swfdtf.ui.Screen;
import de.swfdtf.ui.ScreenManager;
import de.swfdtf.ui.TextButton;
import de.swfdtf.ui.ThemePalette;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit and integration tests for {@link LiveDirectorOverlay} and {@link DeveloperInspectors}.
 */
class DirectorAndInspectorsTest {

    @TempDir
    Path tempDir;

    @Test
    void updateShowLineInScriptPreservesIndentationAndWithClauseAtomically() throws Exception {
        Path script = tempDir.resolve("scene.vjava");
        String original = """
                label start:
                    scene bg_park
                    show lena neutral at center with easein(0.4) # intro cue
                    lena "Hallo!"
                """;
        Files.writeString(script, original, StandardCharsets.UTF_8);

        boolean updated = LiveDirectorOverlay.updateShowLineInScript(
                script,
                3,
                "lena",
                List.of("happy", "winter"),
                "left"
        );
        assertTrue(updated);

        String rewritten = Files.readString(script, StandardCharsets.UTF_8);
        String expected = """
                label start:
                    scene bg_park
                    show lena happy winter at left with easein(0.4) # intro cue
                    lena "Hallo!"
                """;
        assertEquals(expected, rewritten);

        VJavaAst.ProjectAst parsed = ProjectScanner.scan(tempDir);
        VJavaAst.ShowStatement show = (VJavaAst.ShowStatement) parsed.labels().get("start").statements().get(1);
        assertEquals("lena", show.tag());
        assertEquals(List.of("happy", "winter"), show.attributes());
        assertEquals("left", show.positionExpr());
        assertEquals("easein(0.4)", show.transitionExpr());

        try (var files = Files.list(tempDir)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }

        // Update with inline ATL properties, then replace back to named preset
        assertTrue(LiveDirectorOverlay.updateShowLineInScript(
                script,
                3,
                "lena",
                List.of("surprised"),
                "xpos 550.0 ypos 220.0 zoom 1.15"
        ));
        String withAtl = Files.readString(script, StandardCharsets.UTF_8);
        assertTrue(withAtl.contains("    show lena surprised with easein(0.4): # intro cue"));
        assertTrue(withAtl.contains("        xpos 550.0 ypos 220.0 zoom 1.15"));

        assertTrue(LiveDirectorOverlay.updateShowLineInScript(
                script,
                3,
                "lena",
                List.of("calm"),
                "right"
        ));
        String backToPreset = Files.readString(script, StandardCharsets.UTF_8);
        assertEquals("""
                label start:
                    scene bg_park
                    show lena calm at right with easein(0.4) # intro cue
                    lena "Hallo!"
                """, backToPreset);
    }

    @Test
    void liveDirectorToggleSlidersAndCommitWriteBackToVJava() throws Exception {
        Path script = tempDir.resolve("main.vjava");
        Files.writeString(script, """
                define l = Character("Lena", color="#58a6ff")

                layeredimage lena:
                    always "assets/images/lena_base.png"
                    group mood:
                        attribute neutral default "assets/images/lena_neutral.png"
                        attribute happy "assets/images/lena_happy.png"

                label start:
                    show lena neutral at center with easein(0.4)
                    l "Bereit für die Probe!"
                """, StandardCharsets.UTF_8);

        VJavaAst.ProjectAst ast = ProjectScanner.scan(tempDir);
        EngineAPI engine = new EngineAPI(EngineConfig.defaults());
        ScriptSession session = new ScriptSession(ast, engine);
        session.start();

        ScreenManager screenManager = new ScreenManager();
        LiveDirectorOverlay director = new LiveDirectorOverlay(session, screenManager);
        screenManager.registerScreen(director);

        assertEquals(SceneLayer.Z_INSPECTOR, director.getZOrder());
        assertFalse(director.isVisible());

        assertTrue(director.toggle());
        assertTrue(director.isVisible());
        assertEquals("lena", director.getSelectedSpriteTag());

        // Adjust sliders and attributes interactively
        director.getXposSlider().dispatchMouseDown(director.getXposSlider().getBounds().x() + 200.0f,
                director.getXposSlider().getBounds().y() + 10.0f, 0);
        director.getXposSlider().dispatchMouseUp(director.getXposSlider().getBounds().x() + 200.0f,
                director.getXposSlider().getBounds().y() + 10.0f, 0);
        director.getZoomSlider().setValue(1.30f);
        director.getAlphaSlider().setValue(0.85f);

        SpriteNode lena = session.getSceneController().getActiveSprite("lena");
        assertNotNull(lena);
        lena.setAttributes(List.of("happy"));
        lena.getTransform().setXalign(Float.NaN).setXanchor(0.0f).setXpos(420.0f)
                .setYalign(Float.NaN).setYanchor(0.0f).setYpos(110.0f)
                .setZoom(1.30f)
                .setAlpha(0.85f);
        director.refreshFromStage();

        assertEquals(420.0f, director.getXposSlider().getValue(), 0.5f);
        assertEquals(1.30f, director.getZoomSlider().getValue(), 0.01f);
        assertEquals(0.85f, director.getAlphaSlider().getValue(), 0.01f);

        assertTrue(director.commitCurrentSpriteEdit("lena"));
        String updatedSource = Files.readString(script, StandardCharsets.UTF_8);
        assertTrue(updatedSource.contains("show lena happy with easein(0.4):"));
        assertTrue(updatedSource.contains("xpos 420.0 ypos 110.0 zoom 1.30 alpha 0.85"));

        // Verify rewritten script re-scans and runs cleanly with updated transform
        VJavaAst.ProjectAst reparsed = ProjectScanner.scan(tempDir);
        ScriptSession replayed = new ScriptSession(reparsed, new EngineAPI(EngineConfig.defaults()));
        replayed.start();
        SpriteNode reloadedLena = replayed.getSceneController().getActiveSprite("lena");
        assertNotNull(reloadedLena);
        assertEquals(List.of("happy"), reloadedLena.getAttributes());
        assertEquals(420.0f, reloadedLena.getTransform().getXpos(), 0.1f);
        assertEquals(110.0f, reloadedLena.getTransform().getYpos(), 0.1f);
        assertEquals(1.30f, reloadedLena.getTransform().getZoom(), 0.01f);
        assertEquals(0.85f, reloadedLena.getTransform().getAlpha(), 0.01f);

        // Toggle closed via D key
        assertTrue(screenManager.dispatchKeyPressed(GLFW.GLFW_KEY_D, 0));
        assertFalse(director.isVisible());
    }

    @Test
    void developerInspectorsPickerStyleConsoleWarpAndLiveReload() throws Exception {
        Path script = tempDir.resolve("game.vjava");
        Files.writeString(script, """
                @->{
                    public static class QuestBadge {
                        public final String title;
                        public QuestBadge(String title) {
                            this.title = title;
                        }
                    }

                    public static int combineScore(int base, int bonus) {
                        return base + bonus;
                    }
                }<<

                define l = Character("Lena", color="#58a6ff")

                label start:
                    $ score = 10
                    l "Erste Zeile [score]"
                    l "Zweite Zeile"

                label chapter_two:
                    l "Willkommen in Kapitel 2!"
                """, StandardCharsets.UTF_8);

        VJavaAst.ProjectAst initialAst = ProjectScanner.scan(tempDir);
        EngineAPI engine = new EngineAPI(EngineConfig.defaults());
        ScriptSession session = new ScriptSession(initialAst, engine);
        session.start();

        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, session.getRuntime().getStatus());
        assertEquals("Erste Zeile 10", session.getRuntime().getActiveDialogue().interpolatedText());

        // Set up ScreenManager with a sample target screen for Style Inspector
        ScreenManager screenManager = new ScreenManager();
        screenManager.setLogicalSize(1920.0f, 1080.0f);

        Screen targetScreen = new Screen("hud_test", SceneLayer.Z_SCREENS, false);
        Fixed hudRoot = new Fixed();
        TextButton inspectableButton = new TextButton("Inspect Me", () -> {});
        inspectableButton.setId("btn_inspect");
        inspectableButton.setBackgroundColorHex(ThemePalette.ELEVATED);
        inspectableButton.setBorderColorHex(ThemePalette.BORDER);
        inspectableButton.setCornerRadius(6.0f);
        hudRoot.addChildAt(inspectableButton, 200.0f, 300.0f, 240.0f, 60.0f);
        targetScreen.setRoot(hudRoot);
        screenManager.registerScreen(targetScreen);
        screenManager.showScreen(targetScreen);

        DeveloperInspectors inspectors = new DeveloperInspectors(session, screenManager);
        screenManager.registerScreen(inspectors);
        screenManager.showScreen(inspectors);

        // 1. Coordinate Picker (Shift + C)
        assertFalse(inspectors.isCoordinatePickerActive());
        assertTrue(screenManager.dispatchKeyPressed(GLFW.GLFW_KEY_C, GLFW.GLFW_MOD_SHIFT));
        assertTrue(inspectors.isCoordinatePickerActive());

        DeveloperInspectors.PickedCoordinate picked = inspectors.pickCoordinate(960.0f, 540.0f);
        assertEquals("xpos 960 ypos 540", picked.xposYposSnippet());
        assertEquals("xalign 0.500 yalign 0.500", picked.alignSnippet());
        assertEquals(1, inspectors.getPickedHistory().size());

        // 2. Style Inspector (Shift + I)
        assertFalse(inspectors.isStyleInspectorActive());
        assertTrue(screenManager.dispatchKeyPressed(GLFW.GLFW_KEY_I, GLFW.GLFW_MOD_SHIFT));
        assertTrue(inspectors.isStyleInspectorActive());

        engine.getInput().onCursorPos(300.0, 330.0);
        screenManager.update(0.016, engine.getInput());
        DeveloperInspectors.StyleInspectionResult style = inspectors.inspectAt(300.0f, 330.0f);
        assertNotNull(style);
        assertEquals("hud_test", style.screenName());
        assertEquals("TextButton", style.nodeClass());
        assertEquals("btn_inspect", style.nodeId());
        assertEquals(200.0f, style.bounds().x(), 0.01f);
        assertEquals(300.0f, style.bounds().y(), 0.01f);
        assertEquals(6.0f, style.cornerRadius(), 0.01f);
        assertTrue(style.visible());
        assertTrue(style.enabled());
        assertTrue(style.hovered());
        assertTrue(style.formatReport().contains("TextButton #btn_inspect"));

        // 3. Live Java Console (Shift + O) against StateStore + VJavaUserTypes
        assertFalse(inspectors.isConsoleActive());
        assertTrue(screenManager.dispatchKeyPressed(GLFW.GLFW_KEY_O, GLFW.GLFW_MOD_SHIFT));
        assertTrue(inspectors.isConsoleActive());

        DeveloperInspectors.ConsoleExecutionResult okResult = inspectors.executeConsoleSnippet("""
                QuestBadge badge = new QuestBadge("Sternenforscher");
                int total = combineScore(state.getInt("score", 0), 32);
                state.set("score", total);
                state.set("badge_title", badge.title);
                engine.logInfo("Console updated score=" + total);
                """);
        assertTrue(okResult.success());
        assertEquals(42, session.getStateStore().getInt("score", 0));
        assertEquals("Sternenforscher", session.getStateStore().getString("badge_title", ""));
        assertTrue(okResult.output().contains("Console updated score=42"));
        assertTrue(inspectors.getConsoleLog().stream().anyMatch(l -> l.contains("Console updated score=42")));

        DeveloperInspectors.ConsoleExecutionResult errResult = inspectors.executeConsoleSnippet("undefinedSymbol();");
        assertFalse(errResult.success());
        assertNotNull(errResult.errorMessage());
        assertTrue(inspectors.getConsoleLog().stream().anyMatch(l -> l.contains("[COMPILE ERROR]")));

        // 4. Live Reload (Shift + R) preserving current label, statementIndex & StateStore variables
        session.getStateStore().set("custom_flag", "kept_across_reload");
        String prevLabel = session.getRuntime().getCurrentLabel();
        int prevIndex = session.getRuntime().getStatementIndex();

        Files.writeString(script, """
                @->{
                    public static class QuestBadge {
                        public final String title;
                        public QuestBadge(String title) {
                            this.title = title;
                        }
                    }

                    public static int combineScore(int base, int bonus) {
                        return base + bonus;
                    }
                }<<

                define l = Character("Lena", color="#58a6ff")

                label start:
                    $ score = 10
                    l "Geänderte Zeile [score]"
                    l "Zweite Zeile"

                label chapter_two:
                    l "Willkommen in Kapitel 2!"

                label secret_bonus:
                    l "Geheimes Bonus-Label!"
                """, StandardCharsets.UTF_8);

        assertTrue(screenManager.dispatchKeyPressed(GLFW.GLFW_KEY_R, GLFW.GLFW_MOD_SHIFT));
        ScriptSession reloaded = inspectors.getSession();
        assertEquals(prevLabel, reloaded.getRuntime().getCurrentLabel());
        assertEquals(prevIndex, reloaded.getRuntime().getStatementIndex());
        assertEquals(42, reloaded.getStateStore().getInt("score", 0));
        assertEquals("kept_across_reload", reloaded.getStateStore().getString("custom_flag", ""));
        assertEquals("Geänderte Zeile 42", reloaded.getRuntime().getActiveDialogue().interpolatedText());

        // 5. Script Warping to newly added label
        assertTrue(inspectors.getAvailableLabels().contains("secret_bonus"));
        inspectors.warpToLabel(reloaded, "secret_bonus");
        assertEquals("secret_bonus", reloaded.getRuntime().getCurrentLabel());
        assertEquals("Geheimes Bonus-Label!", reloaded.getRuntime().getActiveDialogue().interpolatedText());
    }
}
