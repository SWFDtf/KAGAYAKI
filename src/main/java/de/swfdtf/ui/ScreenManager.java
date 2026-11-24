package de.swfdtf.ui;

import de.swfdtf.core.InputManager;
import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.TextureManager;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Manages the active stack of UI {@link Screen}s sorted by Z-order, handling modal input
 * blocking, mouse/keyboard/gamepad focus traversal, and rendering.
 */
public final class ScreenManager {

    private final Map<String, Screen> screens = new LinkedHashMap<>();
    private float logicalWidth = RenderPipeline2D.DEFAULT_LOGICAL_WIDTH;
    private float logicalHeight = RenderPipeline2D.DEFAULT_LOGICAL_HEIGHT;
    private final boolean[] previousButtons = new boolean[InputManager.MOUSE_BUTTON_COUNT];
    private final boolean[] previousTrackedKeys = new boolean[512];
    private UiNode focusedNode;

    public void setLogicalSize(float width, float height) {
        this.logicalWidth = Math.max(1.0f, width);
        this.logicalHeight = Math.max(1.0f, height);
    }

    public void registerScreen(Screen screen) {
        Objects.requireNonNull(screen, "screen must not be null");
        screens.put(screen.getName(), screen);
    }

    public void showScreen(Screen screen) {
        Objects.requireNonNull(screen, "screen must not be null");
        screens.put(screen.getName(), screen);
        screen.setVisible(true);
        screen.onShow();
        screen.layout(logicalWidth, logicalHeight);
    }

    public boolean showScreen(String name) {
        Screen s = screens.get(name);
        if (s == null) {
            return false;
        }
        showScreen(s);
        return true;
    }

    public boolean hideScreen(String name) {
        Screen s = screens.get(name);
        if (s == null) {
            return false;
        }
        if (s.isVisible()) {
            s.setVisible(false);
            s.onHide();
        }
        return true;
    }

    public boolean toggleScreen(String name) {
        Screen s = screens.get(name);
        if (s == null) {
            return false;
        }
        if (s.isVisible()) {
            hideScreen(name);
        } else {
            showScreen(s);
        }
        return true;
    }

    public Screen getScreen(String name) {
        return screens.get(name);
    }

    public List<Screen> getVisibleScreensSorted() {
        List<Screen> visible = new ArrayList<>();
        for (Screen s : screens.values()) {
            if (s.isVisible()) {
                visible.add(s);
            }
        }
        visible.sort(Comparator.comparingInt(Screen::getZOrder));
        return visible;
    }

    public Screen getTopModalScreen() {
        List<Screen> sorted = getVisibleScreensSorted();
        for (int i = sorted.size() - 1; i >= 0; i--) {
            if (sorted.get(i).isModal()) {
                return sorted.get(i);
            }
        }
        return null;
    }

    public boolean isModalActive() {
        return getTopModalScreen() != null;
    }

    /**
     * Returns visible screens eligible for input in top-to-bottom order (stopping at the topmost modal screen).
     */
    public List<Screen> getInputEligibleScreensTopDown() {
        List<Screen> sorted = getVisibleScreensSorted();
        List<Screen> eligible = new ArrayList<>();
        for (int i = sorted.size() - 1; i >= 0; i--) {
            Screen s = sorted.get(i);
            eligible.add(s);
            if (s.isModal()) {
                break;
            }
        }
        return eligible;
    }

    public void update(double dt, InputManager input) {
        List<Screen> sorted = getVisibleScreensSorted();
        for (Screen s : sorted) {
            s.layout(logicalWidth, logicalHeight);
            s.update(dt);
        }
        if (input != null) {
            routeInput(input);
        }
    }

    public void routeInput(InputManager input) {
        List<Screen> eligible = getInputEligibleScreensTopDown();
        if (eligible.isEmpty()) {
            return;
        }

        float lx = (float) input.getLogicalMouseX();
        float ly = (float) input.getLogicalMouseY();

        // Clear hover on blocked screens below modal
        Screen topModal = getTopModalScreen();
        if (topModal != null) {
            for (Screen s : getVisibleScreensSorted()) {
                if (!eligible.contains(s) && s.getRoot() != null) {
                    s.getRoot().setHoveredDeep(false);
                }
            }
        }

        for (Screen s : eligible) {
            if (s.getRoot() != null) {
                s.getRoot().dispatchMouseMove(lx, ly);
            }
        }

        for (int btn = 0; btn < InputManager.MOUSE_BUTTON_COUNT; btn++) {
            boolean down = input.isMouseButtonDown(btn);
            boolean justPressed = down && !previousButtons[btn];
            boolean justReleased = !down && previousButtons[btn];
            previousButtons[btn] = down;

            if (justPressed) {
                dispatchMouseDown(lx, ly, btn);
            } else if (justReleased) {
                dispatchMouseUp(lx, ly, btn);
            }
        }

        double scrollY = input.consumeScrollY();
        if (Math.abs(scrollY) > 1e-6) {
            dispatchMouseScroll(lx, ly, scrollY);
        }

        String typed = input.drainTypedChars();
        if (!typed.isEmpty()) {
            for (int i = 0; i < typed.length(); i++) {
                dispatchCharTyped(typed.charAt(i));
            }
        }

        int mods = buildModsMask(input);
        for (int key = 32; key < previousTrackedKeys.length; key++) {
            boolean down = input.isKeyDown(key);
            boolean justPressed = down && !previousTrackedKeys[key];
            previousTrackedKeys[key] = down;
            if (justPressed) {
                dispatchKeyPressed(key, mods);
            }
        }
    }

    public boolean dispatchMouseDown(float lx, float ly, int button) {
        for (Screen s : getInputEligibleScreensTopDown()) {
            if (s.getRoot() != null && s.getRoot().dispatchMouseDown(lx, ly, button)) {
                return true;
            }
            if (s.isModal()) {
                return true;
            }
        }
        return false;
    }

    public boolean dispatchMouseUp(float lx, float ly, int button) {
        for (Screen s : getInputEligibleScreensTopDown()) {
            if (s.getRoot() != null && s.getRoot().dispatchMouseUp(lx, ly, button)) {
                return true;
            }
            if (s.isModal()) {
                return true;
            }
        }
        return false;
    }

    public boolean dispatchMouseScroll(float lx, float ly, double scrollY) {
        for (Screen s : getInputEligibleScreensTopDown()) {
            if (s.getRoot() != null && s.getRoot().dispatchMouseScroll(lx, ly, scrollY)) {
                return true;
            }
            if (s.isModal()) {
                return true;
            }
        }
        return false;
    }

    public boolean dispatchKeyPressed(int key, int mods) {
        List<Screen> eligible = getInputEligibleScreensTopDown();
        for (Screen s : eligible) {
            if (s.triggerKeyBindings(key, mods)) {
                return true;
            }
            if (s.getRoot() != null && s.getRoot().dispatchKeyPressed(key, mods)) {
                return true;
            }
            if (s.isModal()) {
                break;
            }
        }
        if (key == GLFW.GLFW_KEY_TAB && !eligible.isEmpty()) {
            cycleFocus(eligible.get(0), (mods & GLFW.GLFW_MOD_SHIFT) != 0);
            return true;
        }
        return isModalActive();
    }

    public boolean dispatchCharTyped(char ch) {
        for (Screen s : getInputEligibleScreensTopDown()) {
            if (s.getRoot() != null && s.getRoot().dispatchCharTyped(ch)) {
                return true;
            }
            if (s.isModal()) {
                return true;
            }
        }
        return false;
    }

    public void cycleFocus(Screen screen, boolean reverse) {
        List<UiNode> focusables = new ArrayList<>();
        collectFocusables(screen.getRoot(), focusables);
        if (focusables.isEmpty()) {
            return;
        }
        int currentIndex = focusedNode != null ? focusables.indexOf(focusedNode) : -1;
        if (focusedNode != null) {
            focusedNode.setFocused(false);
        }
        int nextIndex = reverse
                ? (currentIndex <= 0 ? focusables.size() - 1 : currentIndex - 1)
                : (currentIndex + 1) % focusables.size();
        focusedNode = focusables.get(nextIndex);
        focusedNode.setFocused(true);
    }

    private static void collectFocusables(UiNode node, List<UiNode> out) {
        if (node == null || !node.isVisible() || !node.isEnabled()) {
            return;
        }
        if (node.isFocusable()) {
            out.add(node);
        }
        for (UiNode child : node.getChildren()) {
            collectFocusables(child, out);
        }
    }

    public void render(
            RenderPipeline2D pipeline,
            FontManager fontManager,
            TextureManager textureManager,
            double elapsedSeconds
    ) {
        for (Screen s : getVisibleScreensSorted()) {
            s.render(pipeline, fontManager, textureManager, elapsedSeconds);
        }
    }

    private static int buildModsMask(InputManager input) {
        int mods = 0;
        if (input.isShiftDown()) {
            mods |= GLFW.GLFW_MOD_SHIFT;
        }
        if (input.isCtrlDown()) {
            mods |= GLFW.GLFW_MOD_CONTROL;
        }
        if (input.isAltDown()) {
            mods |= GLFW.GLFW_MOD_ALT;
        }
        return mods;
    }

    public UiNode getFocusedNode() {
        return focusedNode;
    }

    public float getLogicalWidth() {
        return logicalWidth;
    }

    public float getLogicalHeight() {
        return logicalHeight;
    }
}
