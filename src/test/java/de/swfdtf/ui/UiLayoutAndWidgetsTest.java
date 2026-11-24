package de.swfdtf.ui;

import de.swfdtf.core.InputManager;
import de.swfdtf.graphics.CustomDisplayable;
import de.swfdtf.graphics.NineSliceDrawable;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.StyledTextLayout;
import de.swfdtf.graphics.Texture;
import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.parser.VJavaParser;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiLayoutAndWidgetsTest {

    private static UiNode fixedSizeBox(float w, float h) {
        return new UiNode() {
        }.setPreferredSize(w, h);
    }

    @Test
    void vboxHboxGridFixedFrameAndWindowBoxLayoutBounds() {
        VBox vbox = new VBox().setSpacing(10.0f).setAlignCenterX(true);
        UiNode c1 = fixedSizeBox(100.0f, 30.0f);
        UiNode c2 = fixedSizeBox(200.0f, 50.0f);
        vbox.addChild(c1).addChild(c2);
        vbox.layout(new Rect2f(0.0f, 0.0f, 300.0f, 200.0f));

        assertEquals(new Rect2f(100.0f, 0.0f, 100.0f, 30.0f), c1.getBounds());
        assertEquals(new Rect2f(50.0f, 40.0f, 200.0f, 50.0f), c2.getBounds());

        vbox.setFillWidth(true);
        vbox.layout(new Rect2f(10.0f, 20.0f, 300.0f, 200.0f));
        assertEquals(new Rect2f(10.0f, 20.0f, 300.0f, 30.0f), c1.getBounds());
        assertEquals(new Rect2f(10.0f, 60.0f, 300.0f, 50.0f), c2.getBounds());

        HBox hbox = new HBox().setSpacing(15.0f).setAlignCenterY(true);
        UiNode h1 = fixedSizeBox(60.0f, 20.0f);
        UiNode h2 = fixedSizeBox(80.0f, 40.0f);
        hbox.addChild(h1).addChild(h2);
        hbox.layout(new Rect2f(0.0f, 0.0f, 300.0f, 100.0f));
        assertEquals(new Rect2f(0.0f, 40.0f, 60.0f, 20.0f), h1.getBounds());
        assertEquals(new Rect2f(75.0f, 30.0f, 80.0f, 40.0f), h2.getBounds());

        Grid grid = new Grid(2, 2).setSpacing(10.0f, 20.0f);
        List<UiNode> cells = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UiNode cell = fixedSizeBox(10.0f, 10.0f);
            cells.add(cell);
            grid.addChild(cell);
        }
        grid.layout(new Rect2f(0.0f, 0.0f, 210.0f, 120.0f));
        assertEquals(new Rect2f(0.0f, 0.0f, 100.0f, 50.0f), cells.get(0).getBounds());
        assertEquals(new Rect2f(110.0f, 0.0f, 100.0f, 50.0f), cells.get(1).getBounds());
        assertEquals(new Rect2f(0.0f, 70.0f, 100.0f, 50.0f), cells.get(2).getBounds());
        assertEquals(new Rect2f(110.0f, 70.0f, 100.0f, 50.0f), cells.get(3).getBounds());

        Fixed fixed = new Fixed();
        UiNode fxChild = fixedSizeBox(50.0f, 25.0f);
        fixed.addChildAt(fxChild, 35.0f, 45.0f, 120.0f, 60.0f);
        fixed.layout(new Rect2f(10.0f, 10.0f, 400.0f, 300.0f));
        assertEquals(new Rect2f(45.0f, 55.0f, 120.0f, 60.0f), fxChild.getBounds());

        Frame frame = new Frame();
        assertEquals(ThemePalette.PANEL_SURFACE, frame.getBackgroundColorHex());
        assertEquals(ThemePalette.BORDER, frame.getBorderColorHex());
        NineSliceDrawable nine = new NineSliceDrawable(new Texture(1, 64, 64, "ui/frame.png", true), 8.0f);
        frame.setNineSlice(nine);
        assertSame(nine, frame.getNineSlice());

        WindowBox window = new WindowBox("Settings").setHeaderHeight(40.0f);
        UiNode body = fixedSizeBox(100.0f, 100.0f);
        window.addChild(body);
        window.layout(new Rect2f(100.0f, 100.0f, 500.0f, 340.0f));
        assertEquals(new Rect2f(100.0f, 140.0f, 500.0f, 300.0f), body.getBounds());
    }

    @Test
    void viewportAndVpGridScrollAndVirtualization() {
        Viewport viewport = new Viewport().setScrollStep(50.0f);
        UiNode tallChild = fixedSizeBox(300.0f, 600.0f);
        viewport.addChild(tallChild);
        viewport.layout(new Rect2f(0.0f, 0.0f, 300.0f, 200.0f));

        assertEquals(400.0f, viewport.getMaxScrollY(), 1e-4f);
        assertEquals(0.0f, viewport.getScrollY(), 1e-4f);
        assertEquals(0.0f, tallChild.getBounds().y(), 1e-4f);

        assertTrue(viewport.dispatchMouseScroll(50.0f, 50.0f, -2.0));
        assertEquals(100.0f, viewport.getScrollY(), 1e-4f);
        assertEquals(-100.0f, tallChild.getBounds().y(), 1e-4f);

        viewport.setScrollY(999.0f);
        assertEquals(400.0f, viewport.getScrollY(), 1e-4f);

        // VpGrid with 20 items in 2 columns (10 rows of 100px + 10px vertical spacing)
        VpGrid vpGrid = new VpGrid(2, 140.0f, 100.0f).setSpacing(10.0f, 10.0f);
        for (int i = 0; i < 20; i++) {
            vpGrid.addChild(new TextButton("Item " + i, null));
        }
        vpGrid.layout(new Rect2f(0.0f, 0.0f, 300.0f, 230.0f));
        assertEquals(10, vpGrid.getTotalRows());
        assertEquals(0, vpGrid.getFirstVisibleRow());
        assertEquals(2, vpGrid.getLastVisibleRow());

        vpGrid.setScrollY(330.0f);
        assertEquals(3, vpGrid.getFirstVisibleRow());
        assertEquals(5, vpGrid.getLastVisibleRow());
    }

    @Test
    void screenManagerModalBlockingAndZSorting() {
        ScreenManager manager = new ScreenManager();
        manager.setLogicalSize(1920.0f, 1080.0f);

        AtomicInteger bgClicks = new AtomicInteger(0);
        AtomicInteger modalClicks = new AtomicInteger(0);

        Screen bgScreen = new Screen("hud", 100, false);
        TextButton bgBtn = new TextButton("BG", bgClicks::incrementAndGet);
        bgScreen.setRoot(new Fixed().addChildAt(bgBtn, 100.0f, 100.0f, 200.0f, 50.0f));

        Screen modalScreen = new Screen("pause", 400, true);
        TextButton modalBtn = new TextButton("Modal", modalClicks::incrementAndGet);
        modalScreen.setRoot(new Fixed().addChildAt(modalBtn, 500.0f, 500.0f, 200.0f, 50.0f));

        manager.registerScreen(modalScreen);
        manager.registerScreen(bgScreen);
        manager.showScreen("hud");
        manager.showScreen("pause");

        List<Screen> sorted = manager.getVisibleScreensSorted();
        assertEquals("hud", sorted.get(0).getName());
        assertEquals("pause", sorted.get(1).getName());
        assertTrue(manager.isModalActive());

        InputManager input = new InputManager();
        input.setLogicalSize(1920, 1080);
        input.setViewport(0, 0, 1920, 1080);

        // Try clicking bgBtn coordinates while modal is open: blocked!
        input.beginFrame();
        input.onCursorPos(150.0, 125.0);
        input.onMouseButton(0, GLFW.GLFW_PRESS, 0);
        manager.update(0.016, input);
        input.endFrame();

        input.beginFrame();
        input.onMouseButton(0, GLFW.GLFW_RELEASE, 0);
        manager.update(0.016, input);
        input.endFrame();
        assertEquals(0, bgClicks.get());
        assertFalse(bgBtn.isHovered());

        // Click modalBtn: succeeds!
        input.beginFrame();
        input.onCursorPos(550.0, 525.0);
        input.onMouseButton(0, GLFW.GLFW_PRESS, 0);
        manager.update(0.016, input);
        input.endFrame();

        input.beginFrame();
        input.onMouseButton(0, GLFW.GLFW_RELEASE, 0);
        manager.update(0.016, input);
        input.endFrame();
        assertEquals(1, modalClicks.get());

        // Hide modal -> bgBtn receives clicks
        manager.hideScreen("pause");
        assertFalse(manager.isModalActive());

        input.beginFrame();
        input.onCursorPos(150.0, 125.0);
        input.onMouseButton(0, GLFW.GLFW_PRESS, 0);
        manager.update(0.016, input);
        input.endFrame();

        input.beginFrame();
        input.onMouseButton(0, GLFW.GLFW_RELEASE, 0);
        manager.update(0.016, input);
        input.endFrame();
        assertEquals(1, bgClicks.get());
    }

    @Test
    void widgetsBehaveDeterministicallyHeadless() {
        // TextWidget with headless FontManager fallback & typewriter
        TextWidget tw = new TextWidget("{b}Hello{/b} World").setFontSize(24.0f).setTypewriterCps(10.0f);
        StyledTextLayout textLayout = tw.getLayout();
        assertNotNull(textLayout);
        assertTrue(textLayout.getTotalWidth() > 0.0f);
        assertNotNull(tw.getTypewriter());
        tw.update(0.5);
        assertEquals(5, tw.getTypewriter().getVisibleCharCount());

        // TextButton states & activation
        AtomicInteger btnClicks = new AtomicInteger(0);
        TextButton btn = new TextButton("Play", btnClicks::incrementAndGet);
        btn.layout(new Rect2f(0.0f, 0.0f, 180.0f, 48.0f));
        assertEquals(ThemePalette.ELEVATED, btn.getCurrentSurfaceColorHex());
        btn.dispatchMouseMove(50.0f, 20.0f);
        assertEquals(ThemePalette.ACCENT_HOVER, btn.getCurrentSurfaceColorHex());
        btn.setSelected(true);
        assertEquals(ThemePalette.ACTIVE_SELECTED, btn.getCurrentSurfaceColorHex());
        btn.setSelected(false);
        btn.setFocused(true);
        assertTrue(btn.dispatchKeyPressed(GLFW.GLFW_KEY_ENTER, 0));
        assertEquals(1, btnClicks.get());
        btn.setEnabled(false);
        assertEquals(ThemePalette.DISABLED, btn.getCurrentSurfaceColorHex());
        assertFalse(btn.dispatchMouseDown(50.0f, 20.0f, 0));

        // ImageButton paths
        ImageButton imgBtn = new ImageButton("idle.png", "hover.png", btnClicks::incrementAndGet)
                .setDisabledPath("disabled.png");
        imgBtn.layout(new Rect2f(0.0f, 0.0f, 100.0f, 40.0f));
        assertEquals("idle.png", imgBtn.getCurrentImagePath());
        imgBtn.dispatchMouseMove(10.0f, 10.0f);
        assertEquals("hover.png", imgBtn.getCurrentImagePath());
        imgBtn.setEnabled(false);
        assertEquals("disabled.png", imgBtn.getCurrentImagePath());

        // Bar & VBar
        Bar bar = new Bar(75.0f, 100.0f).setFillColorHex(ThemePalette.SUCCESS);
        assertEquals(0.75f, bar.getNormalizedValue(), 1e-5f);
        bar.setValue(150.0f);
        assertEquals(100.0f, bar.getValue(), 1e-5f);

        VBar vbar = new VBar(25.0f, 50.0f);
        assertEquals(0.5f, vbar.getNormalizedValue(), 1e-5f);

        // Slider drag, click-jump, step, keyboard
        AtomicReference<Float> sliderVal = new AtomicReference<>(0.0f);
        Slider slider = new Slider(0.0f, 0.0f, 100.0f, sliderVal::set).setStep(10.0f);
        slider.layout(new Rect2f(0.0f, 0.0f, 200.0f, 30.0f));
        assertTrue(slider.dispatchMouseDown(100.0f, 15.0f, 0));
        assertTrue(slider.isDragging());
        assertEquals(50.0f, slider.getValue(), 1e-4f);
        slider.dispatchMouseMove(162.0f, 15.0f);
        assertEquals(80.0f, slider.getValue(), 1e-4f);
        assertTrue(slider.dispatchMouseUp(162.0f, 15.0f, 0));
        assertFalse(slider.isDragging());
        assertTrue(slider.dispatchKeyPressed(GLFW.GLFW_KEY_LEFT, 0));
        assertEquals(70.0f, sliderVal.get(), 1e-4f);

        // TextInput typing, whitelist, maxLength, backspace, submit
        AtomicReference<String> submitted = new AtomicReference<>();
        TextInput input = new TextInput("", submitted::set)
                .setPlaceholder("Name...")
                .setAllowChars("abc123")
                .setMaxLength(4);
        input.layout(new Rect2f(0.0f, 0.0f, 240.0f, 40.0f));
        assertTrue(input.dispatchMouseDown(20.0f, 20.0f, 0));
        assertTrue(input.isFocused());
        input.dispatchCharTyped('a');
        input.dispatchCharTyped('z'); // filtered out
        input.dispatchCharTyped('b');
        input.dispatchCharTyped('1');
        input.dispatchCharTyped('2');
        input.dispatchCharTyped('3'); // rejected by maxLength=4
        assertEquals("ab12", input.getText());
        assertTrue(input.dispatchKeyPressed(GLFW.GLFW_KEY_BACKSPACE, 0));
        assertEquals("ab1", input.getText());
        assertTrue(input.dispatchKeyPressed(GLFW.GLFW_KEY_ENTER, 0));
        assertEquals("ab1", submitted.get());

        // Imagemap + Hotspot + Hotbar
        Imagemap map = new Imagemap("map_ground.png", "map_hover.png");
        AtomicBoolean hotspotClicked = new AtomicBoolean(false);
        Hotspot hs = map.addHotspot(new Rect2f(20.0f, 30.0f, 80.0f, 40.0f), () -> hotspotClicked.set(true));
        Hotbar hb = map.addHotbar(new Rect2f(20.0f, 90.0f, 100.0f, 20.0f), () -> 60.0f, 100.0f);
        map.layout(new Rect2f(100.0f, 100.0f, 400.0f, 300.0f));
        assertTrue(hs.isInside(150.0f, 150.0f));
        assertFalse(hs.isInside(110.0f, 110.0f));
        assertTrue(map.dispatchMouseDown(150.0f, 150.0f, 0));
        assertTrue(map.dispatchMouseUp(150.0f, 150.0f, 0));
        assertTrue(hotspotClicked.get());
        assertEquals(0.6f, hb.getNormalizedRatio(), 1e-5f);

        // DragGroup + DragItem drag & drop snapping
        DragGroup dragGroup = new DragGroup();
        dragGroup.layout(new Rect2f(0.0f, 0.0f, 800.0f, 600.0f));
        DragItem token = new DragItem("token", "star").setDraggable(true).setSnappable(true);
        token.setBounds(new Rect2f(20.0f, 20.0f, 60.0f, 60.0f));
        DragItem slot = new DragItem("slot", null).setDraggable(false).setDropTarget(true);
        slot.setBounds(new Rect2f(300.0f, 200.0f, 100.0f, 100.0f));

        AtomicReference<String> dropRecord = new AtomicReference<>();
        slot.setOnDrop((dragged, target) -> dropRecord.set(dragged.getId() + "->" + target.getId()));
        dragGroup.addDragItem(slot).addDragItem(token);

        assertTrue(dragGroup.dispatchMouseDown(50.0f, 50.0f, 0));
        assertTrue(token.isDragging());
        dragGroup.dispatchMouseMove(350.0f, 250.0f);
        assertTrue(dragGroup.dispatchMouseUp(350.0f, 250.0f, 0));
        assertFalse(token.isDragging());
        assertSame(slot, token.getSnappedTo());
        assertEquals("token->slot", dropRecord.get());
        // Centered snap inside 100x100 slot at (300,200) for 60x60 token -> (320, 220)
        assertEquals(new Rect2f(320.0f, 220.0f, 60.0f, 60.0f), token.getBounds());

        // CustomDisplayableWidget forwarding
        final double[] customDt = {0.0};
        CustomDisplayableWidget customWidget = new CustomDisplayableWidget(new CustomDisplayable() {
            @Override
            public void update(double dt) {
                customDt[0] += dt;
            }

            @Override
            public void render(RenderPipeline2D pipeline, Rect2f bounds, float alpha, ColorMatrix4f colorMatrix) {
            }

            @Override
            public float getPreferredWidth() {
                return 128.0f;
            }

            @Override
            public float getPreferredHeight() {
                return 64.0f;
            }
        });
        assertEquals(128.0f, customWidget.measure(500.0f, 500.0f).x(), 1e-4f);
        customWidget.update(0.25);
        assertEquals(0.25, customDt[0], 1e-9);
    }

    @Test
    void screenAstBuilderMaterializesFullScreenAndBindsRuntime() {
        String script = """
                default title_text = "Pause"
                default bgm_vol = 0.5
                default player_name = "Kaito"
                default show_secret = true

                label start:
                    return

                screen pause_overlay:
                    modal true
                    zorder 400
                    frame:
                        xalign 0.5
                        yalign 0.5
                        vbox:
                            spacing 12
                            text "[title_text]" size 32
                            textbutton "Resume" action Return()
                            slider value VariableValue("bgm_vol", 1.0) xsize 300
                            input value VariableInputValue("player_name") length 20
                            if show_secret:
                                text "Secret Unlocked"
                            imagemap:
                                ground "assets/ui/map.png"
                                hotspot (10, 20, 100, 40) action Jump("town")
                            draggroup:
                                drag:
                                    drag_name "token"
                                    draggable true
                    key "K_ESCAPE" action Return()
                    timer 5.0 action Jump("idle") repeat false
                """;
        VJavaAst.ScriptFileAst fileAst = VJavaParser.parse(Path.of("pause.vjava"), script);
        VJavaAst.ProjectAst projectAst = new VJavaAst.ProjectAst(
                null, null, List.of(),
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                java.util.Map.of("start", fileAst.labels().get(0)),
                java.util.Map.of("pause_overlay", fileAst.screens().get(0)),
                List.of(), List.of(), fileAst.defaultVariables(), List.of(), List.of()
        );
        ScriptRuntime runtime = new ScriptRuntime(projectAst);
        runtime.start();

        List<String> actions = new ArrayList<>();
        Screen screen = ScreenAstBuilder.build(fileAst.screens().get(0), runtime, actions::add);
        assertEquals("pause_overlay", screen.getName());
        assertTrue(screen.isModal());
        assertEquals(400, screen.getZOrder());
        screen.layout(1920.0f, 1080.0f);

        // Key binding & timer work
        assertTrue(screen.triggerKeyBindings(GLFW.GLFW_KEY_ESCAPE, 0));
        assertEquals(List.of("Return()"), actions);
        screen.update(5.0);
        assertEquals(List.of("Return()", "Jump(\"idle\")"), actions);

        // Inspect built widget tree
        Fixed root = (Fixed) screen.getRoot();
        Frame frame = (Frame) root.getChildren().get(0);
        VBox vbox = (VBox) frame.getChildren().get(0);
        TextWidget title = (TextWidget) vbox.getChildren().get(0);
        assertEquals("Pause", title.getText());

        TextButton resume = (TextButton) vbox.getChildren().get(1);
        resume.click();
        assertTrue(actions.contains("Return()"));

        Slider volSlider = (Slider) vbox.getChildren().get(2);
        assertEquals(0.5f, volSlider.getValue(), 1e-4f);
        volSlider.setFocused(true);
        volSlider.dispatchKeyPressed(GLFW.GLFW_KEY_RIGHT, 0);
        assertTrue(((Number) runtime.getStateStore().get("bgm_vol")).floatValue() > 0.5f);

        TextInput nameInput = (TextInput) vbox.getChildren().get(3);
        assertEquals("Kaito", nameInput.getText());
        nameInput.setFocused(true);
        nameInput.dispatchCharTyped('!');
        assertEquals("Kaito!", runtime.getStateStore().get("player_name"));

        TextWidget secret = (TextWidget) vbox.getChildren().get(4);
        assertEquals("Secret Unlocked", secret.getText());

        Imagemap map = (Imagemap) vbox.getChildren().get(5);
        assertEquals(1, map.getHotspots().size());

        DragGroup dragGroup = (DragGroup) vbox.getChildren().get(6);
        assertEquals(1, dragGroup.getItems().size());
        assertEquals("token", dragGroup.getItems().get(0).getId());
    }
}
