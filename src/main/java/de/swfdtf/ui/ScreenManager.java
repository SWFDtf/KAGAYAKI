package de.swfdtf.ui;

import de.swfdtf.core.GamepadNavigationRouter;
import de.swfdtf.core.InputManager;
import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.TextureManager;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Manages the active stack of UI {@link Screen}s sorted by Z-order, handling modal input
 * blocking, mouse/keyboard/gamepad focus traversal, and rendering.
 */
public final class ScreenManager {

    /** Internal non-modal overlay screen name used for delayed hover tooltips. */
    public static final String TOOLTIP_OVERLAY_NAME = "_tooltip_overlay";
    private static final double TOOLTIP_HOVER_DELAY_SECONDS = 0.4;

    private final Map<String, Screen> screens = new LinkedHashMap<>();
    private final GamepadNavigationRouter gamepadRouter = new GamepadNavigationRouter();
    private final Deque<String> breadcrumbStack = new ArrayDeque<>();
    private final Deque<UiNode> focusRestoreStack = new ArrayDeque<>();
    private float logicalWidth = RenderPipeline2D.DEFAULT_LOGICAL_WIDTH;
    private float logicalHeight = RenderPipeline2D.DEFAULT_LOGICAL_HEIGHT;
    private final boolean[] previousButtons = new boolean[InputManager.MOUSE_BUTTON_COUNT];
    private final boolean[] previousTrackedKeys = new boolean[512];
    private UiNode focusedNode;
    private Consumer<String> focusNarrator;
    private Consumer<UiPolishAndServices.UiCue> soundCueListener;

    private final Screen tooltipOverlayScreen;
    private final Fixed tooltipOverlayRoot;
    private final Frame tooltipBox;
    private final TextWidget tooltipLabel;
    private UiNode hoveredTooltipNode;
    private double hoverTooltipElapsed = 0.0;

    /**
     * Creates an empty {@code ScreenManager}.
     */
    public ScreenManager() {
        this.tooltipOverlayScreen = new Screen(TOOLTIP_OVERLAY_NAME, 9999, false);
        this.tooltipOverlayScreen.setVisible(false);
        this.tooltipOverlayRoot = new Fixed();
        this.tooltipBox = new Frame();
        this.tooltipBox.setBackgroundColorHex(ThemePalette.PANEL_SURFACE)
                .setBorderColorHex(ThemePalette.ACCENT_IDLE)
                .setBorderThickness(1.5f)
                .setCornerRadius(6.0f)
                .setPadding(10.0f, 8.0f);
        this.tooltipLabel = new TextWidget("");
        this.tooltipLabel.setFontSize(18.0f).setColorHex(ThemePalette.TEXT_PRIMARY);
        this.tooltipBox.addChild(this.tooltipLabel);
        this.tooltipOverlayRoot.addChildAt(this.tooltipBox, 0.0f, 0.0f, 220.0f, 44.0f);
        this.tooltipOverlayScreen.setRoot(this.tooltipOverlayRoot);
        this.screens.put(TOOLTIP_OVERLAY_NAME, this.tooltipOverlayScreen);
    }

    /**
     * Executes {@code setLogicalSize}.
     *
     * @param width the width argument
     * @param height the height argument
     */
    public void setLogicalSize(float width, float height) {
        this.logicalWidth = Math.max(1.0f, width);
        this.logicalHeight = Math.max(1.0f, height);
    }

    /**
     * Executes {@code registerScreen}.
     *
     * @param screen the screen argument
     */
    public void registerScreen(Screen screen) {
        Objects.requireNonNull(screen, "screen must not be null");
        screens.put(screen.getName(), screen);
    }

    /**
     * Executes {@code showScreen}.
     *
     * @param screen the screen argument
     */
    public void showScreen(Screen screen) {
        Objects.requireNonNull(screen, "screen must not be null");
        screens.put(screen.getName(), screen);
        if (screen.getTransitionDurationSeconds() > 0.0) {
            screen.beginShowTransition();
        } else {
            screen.clearTransition();
            screen.setVisible(true);
            if (screen.getRoot() != null) {
                screen.getRoot().setAlpha(1.0f);
            }
        }
        screen.onShow();
        screen.layout(logicalWidth, logicalHeight);
        if (focusedNode != null && !screen.isVisible()) {
            focusedNode.setFocused(false);
            focusedNode = null;
        }
        if (screen.isModal() && focusedNode != null) {
            focusRestoreStack.push(focusedNode);
            focusedNode.setFocused(false);
            focusedNode = null;
        }
    }

    /**
     * Pushes a sub-menu screen onto the LIFO navigation breadcrumb stack and shows it ({@code #0378}).
     *
     * @param subScreen sub-menu screen to show
     */
    public void pushSubScreen(Screen subScreen) {
        Objects.requireNonNull(subScreen, "subScreen must not be null");
        breadcrumbStack.push(subScreen.getName());
        showScreen(subScreen);
    }

    /**
     * Pops and hides the top sub-menu screen from the LIFO breadcrumb stack ({@code #0378}).
     *
     * @return {@code true} if a sub-screen was popped and hidden
     */
    public boolean popSubScreen() {
        if (breadcrumbStack.isEmpty()) {
            return false;
        }
        String name = breadcrumbStack.pop();
        return hideScreenImmediate(name);
    }

    /**
     * Returns current sub-screen breadcrumb stack depth.
     *
     * @return number of open sub-screens on stack
     */
    public int getBreadcrumbDepth() {
        return breadcrumbStack.size();
    }

    /**
     * Replaces an existing live-reloaded screen cleanly without duplicate entries ({@code #0395}).
     *
     * @param updatedScreen replacement screen instance
     */
    public void replaceScreen(Screen updatedScreen) {
        Objects.requireNonNull(updatedScreen, "updatedScreen must not be null");
        Screen prev = screens.get(updatedScreen.getName());
        boolean wasVisible = prev != null && prev.isVisible();
        resetFocusForReload();
        screens.put(updatedScreen.getName(), updatedScreen);
        if (wasVisible) {
            showScreen(updatedScreen);
        }
    }

    /**
     * Clears any stale widget focus/hover pointers cleanly across live script reloads ({@code #0420}).
     */
    public void resetFocusForReload() {
        if (focusedNode != null) {
            focusedNode.setFocused(false);
            focusedNode = null;
        }
        hoveredTooltipNode = null;
        focusRestoreStack.clear();
    }

    /**
     * Executes {@code showScreen}.
     *
     * @param name the name argument
     * @return the resulting value
     */
    public boolean showScreen(String name) {
        Screen s = screens.get(name);
        if (s == null) {
            return false;
        }
        showScreen(s);
        return true;
    }

    /**
     * Executes {@code hideScreen}.
     *
     * @param name the name argument
     * @return the resulting value
     */
    public boolean hideScreen(String name) {
        Screen s = screens.get(name);
        if (s == null) {
            return false;
        }
        if (s.isVisible()) {
            clearFocusIfOwnedBy(s);
            if (s.getTransitionDurationSeconds() > 0.0) {
                s.beginHideTransition();
            } else {
                s.clearTransition();
                s.setVisible(false);
                s.onHide();
            }
            if (s.isModal()) {
                restoreSavedFocus();
            }
            if (soundCueListener != null) {
                soundCueListener.accept(UiPolishAndServices.UiCue.BACK);
            }
        }
        return true;
    }

    /**
     * Hides a screen immediately without playing any fade-out transition.
     *
     * @param name canonical screen name
     * @return {@code true} if the screen is registered
     */
    public boolean hideScreenImmediate(String name) {
        Screen s = screens.get(name);
        if (s == null) {
            return false;
        }
        if (s.isVisible()) {
            clearFocusIfOwnedBy(s);
            s.clearTransition();
            s.setVisible(false);
            s.onHide();
            if (s.isModal()) {
                restoreSavedFocus();
            }
            if (soundCueListener != null) {
                soundCueListener.accept(UiPolishAndServices.UiCue.BACK);
            }
        }
        return true;
    }

    private void restoreSavedFocus() {
        while (!focusRestoreStack.isEmpty()) {
            UiNode candidate = focusRestoreStack.pop();
            if (candidate != null && candidate.isVisible() && candidate.isEnabled() && candidate.isFocusable()) {
                if (focusedNode != null) {
                    focusedNode.setFocused(false);
                }
                focusedNode = candidate;
                focusedNode.setFocused(true);
                announceFocusedNode(focusedNode);
                return;
            }
        }
    }

    private void clearFocusIfOwnedBy(Screen screen) {
        if (focusedNode == null) {
            return;
        }
        if (screen.getRoot() == null || containsNode(screen.getRoot(), focusedNode)) {
            focusedNode.setFocused(false);
            focusedNode = null;
        }
    }

    private static boolean containsNode(UiNode root, UiNode target) {
        if (root == target) {
            return true;
        }
        for (UiNode child : root.getChildren()) {
            if (containsNode(child, target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Executes {@code toggleScreen}.
     *
     * @param name the name argument
     * @return the resulting value
     */
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

    /**
     * Executes {@code getScreen}.
     *
     * @param name the name argument
     * @return the resulting value
     */
    public Screen getScreen(String name) {
        return screens.get(name);
    }

    /**
     * Returns the visibleScreensSorted value.
     *
     * @return the resulting value
     */
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

    /**
     * Returns the topModalScreen value.
     *
     * @return the resulting value
     */
    public Screen getTopModalScreen() {
        List<Screen> sorted = getVisibleScreensSorted();
        for (int i = sorted.size() - 1; i >= 0; i--) {
            if (sorted.get(i).isModal()) {
                return sorted.get(i);
            }
        }
        return null;
    }

    /**
     * Returns whether modalActive is active.
     *
     * @return the resulting value
     */
    public boolean isModalActive() {
        return getTopModalScreen() != null;
    }

    /**
     * Returns visible screens eligible for input in top-to-bottom order (stopping at the topmost modal screen).
     *
     * @return immutable-order list of visible input-eligible screens
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

    /**
     * Executes {@code update}.
     *
     * @param dt the dt argument
     * @param input the input argument
     */
    public void update(double dt, InputManager input) {
        List<Screen> sorted = getVisibleScreensSorted();
        for (Screen s : sorted) {
            if (TOOLTIP_OVERLAY_NAME.equals(s.getName())) {
                continue;
            }
            s.layout(logicalWidth, logicalHeight);
            s.update(dt);
            if (s.advanceTransition(dt)) {
                clearFocusIfOwnedBy(s);
                s.setVisible(false);
                s.onHide();
            }
        }
        if (input != null) {
            updateHoverTooltip(dt, input);
            routeInput(input);
            routeGamepadNavigation(dt, input);
        }
    }

    private void updateHoverTooltip(double dt, InputManager input) {
        float lx = (float) input.getLogicalMouseX();
        float ly = (float) input.getLogicalMouseY();
        UiNode hit = null;
        for (Screen s : getInputEligibleScreensTopDown()) {
            if (TOOLTIP_OVERLAY_NAME.equals(s.getName()) || s.getRoot() == null) {
                continue;
            }
            hit = findTooltipNodeAt(s.getRoot(), lx, ly);
            if (hit != null) {
                break;
            }
        }
        if (hit == null || hit != hoveredTooltipNode) {
            hoveredTooltipNode = hit;
            hoverTooltipElapsed = 0.0;
            tooltipOverlayScreen.setVisible(false);
            return;
        }
        hoverTooltipElapsed += Math.max(0.0, dt);
        if (hoverTooltipElapsed < TOOLTIP_HOVER_DELAY_SECONDS) {
            tooltipOverlayScreen.setVisible(false);
            return;
        }
        String text = hit.getTooltipText();
        if (text == null || text.isBlank()) {
            tooltipOverlayScreen.setVisible(false);
            return;
        }
        tooltipLabel.setText(text);
        float boxW = Math.min(460.0f, Math.max(160.0f, text.length() * 11.0f + 28.0f));
        float boxH = 44.0f;
        float boxX = Math.max(0.0f, Math.min(logicalWidth - boxW, lx + 14.0f));
        float boxY = Math.max(0.0f, Math.min(logicalHeight - boxH, ly + 18.0f));
        tooltipOverlayRoot.clearChildren();
        tooltipOverlayRoot.addChildAt(tooltipBox, boxX, boxY, boxW, boxH);
        tooltipOverlayScreen.setVisible(true);
        tooltipOverlayScreen.layout(logicalWidth, logicalHeight);
    }

    private static UiNode findTooltipNodeAt(UiNode node, float lx, float ly) {
        if (node == null || !node.isVisible()) {
            return null;
        }
        List<UiNode> children = node.getChildren();
        for (int i = children.size() - 1; i >= 0; i--) {
            UiNode nested = findTooltipNodeAt(children.get(i), lx, ly);
            if (nested != null) {
                return nested;
            }
        }
        if (node.getBounds().contains(lx, ly)
                && node.getTooltipText() != null
                && !node.getTooltipText().isBlank()) {
            return node;
        }
        return null;
    }

    private void routeGamepadNavigation(double dt, InputManager input) {
        List<GamepadNavigationRouter.NavigationDirection> navEvents = gamepadRouter.poll(input, Math.max(0.0, dt));
        if (navEvents.isEmpty()) {
            return;
        }
        List<Screen> eligible = getInputEligibleScreensTopDown();
        Screen focusTarget = findTopFocusableScreen(eligible);
        for (GamepadNavigationRouter.NavigationDirection dir : navEvents) {
            switch (dir) {
                case DOWN, RIGHT -> {
                    if (focusTarget != null) {
                        cycleFocus(focusTarget, false);
                    }
                }
                case UP, LEFT -> {
                    if (focusTarget != null) {
                        cycleFocus(focusTarget, true);
                    }
                }
                case CONFIRM -> {
                    if (focusedNode != null && focusedNode.isVisible() && focusedNode.isEnabled()) {
                        focusedNode.dispatchKeyPressed(GLFW.GLFW_KEY_ENTER, 0);
                        eligible = getInputEligibleScreensTopDown();
                        focusTarget = findTopFocusableScreen(eligible);
                    }
                }
                case CANCEL -> {
                    Screen topModal = getTopModalScreen();
                    if (topModal != null && !MainMenuScreen.SCREEN_NAME.equals(topModal.getName())) {
                        hideScreenImmediate(topModal.getName());
                        eligible = getInputEligibleScreensTopDown();
                        focusTarget = findTopFocusableScreen(eligible);
                    }
                }
            }
        }
    }

    /**
     * Executes {@code routeInput}.
     *
     * @param input the input argument
     */
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

    /**
     * Executes {@code dispatchMouseDown}.
     *
     * @param lx the lx argument
     * @param ly the ly argument
     * @param button the button argument
     * @return the resulting value
     */
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

    /**
     * Executes {@code dispatchMouseUp}.
     *
     * @param lx the lx argument
     * @param ly the ly argument
     * @param button the button argument
     * @return the resulting value
     */
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

    /**
     * Executes {@code dispatchMouseScroll}.
     *
     * @param lx the lx argument
     * @param ly the ly argument
     * @param scrollY the scrollY argument
     * @return the resulting value
     */
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

    /**
     * Executes {@code dispatchKeyPressed}.
     *
     * @param key the key argument
     * @param mods the mods argument
     * @return the resulting value
     */
    public boolean dispatchKeyPressed(int key, int mods) {
        List<Screen> eligible = getInputEligibleScreensTopDown();
        if (key == GLFW.GLFW_KEY_TAB && (mods & GLFW.GLFW_MOD_CONTROL) != 0) {
            if (cycleActiveTabPanel(eligible, (mods & GLFW.GLFW_MOD_SHIFT) != 0)) {
                return true;
            }
        }
        for (Screen s : eligible) {
            if (TOOLTIP_OVERLAY_NAME.equals(s.getName())) {
                continue;
            }
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
        boolean isReverseFocus = key == GLFW.GLFW_KEY_UP
                || key == GLFW.GLFW_KEY_LEFT
                || (key == GLFW.GLFW_KEY_TAB && (mods & GLFW.GLFW_MOD_SHIFT) != 0);
        boolean isForwardFocus = key == GLFW.GLFW_KEY_DOWN
                || key == GLFW.GLFW_KEY_RIGHT
                || (key == GLFW.GLFW_KEY_TAB && (mods & GLFW.GLFW_MOD_SHIFT) == 0);
        if ((isReverseFocus || isForwardFocus) && !eligible.isEmpty()) {
            Screen target = findTopFocusableScreen(eligible);
            if (target != null) {
                cycleFocus(target, isReverseFocus);
            }
            return true;
        }
        return isModalActive();
    }

    /**
     * Executes {@code dispatchCharTyped}.
     *
     * @param ch the ch argument
     * @return the resulting value
     */
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

    /**
     * Executes {@code cycleFocus}.
     *
     * @param screen the screen argument
     * @param reverse the reverse argument
     */
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
        announceFocusedNode(focusedNode);
        if (soundCueListener != null) {
            soundCueListener.accept(UiPolishAndServices.UiCue.HOVER);
        }
    }

    private void announceFocusedNode(UiNode node) {
        if (focusNarrator == null || node == null) {
            return;
        }
        String label = switch (node) {
            case TextButton btn -> btn.getText();
            case SegmentedControl seg -> seg.getSelectedOption();
            case Slider sl -> String.format(java.util.Locale.ROOT, "Slider %.2f", sl.getValue());
            default -> node.getId().isBlank() ? node.getClass().getSimpleName() : node.getId();
        };
        if (label != null && !label.isBlank()) {
            focusNarrator.accept(label);
        }
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

    private static boolean cycleActiveTabPanel(List<Screen> eligible, boolean reverse) {
        for (Screen s : eligible) {
            if (TOOLTIP_OVERLAY_NAME.equals(s.getName()) || s.getRoot() == null) {
                continue;
            }
            TabPanel tabPanel = findFirstTabPanel(s.getRoot());
            if (tabPanel != null && tabPanel.getTabCount() > 0) {
                tabPanel.cycleTab(reverse);
                return true;
            }
            if (s.isModal()) {
                break;
            }
        }
        return false;
    }

    private static TabPanel findFirstTabPanel(UiNode node) {
        if (node == null || !node.isVisible()) {
            return null;
        }
        if (node instanceof TabPanel tp) {
            return tp;
        }
        for (UiNode child : node.getChildren()) {
            TabPanel found = findFirstTabPanel(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private Screen findTopFocusableScreen(List<Screen> eligible) {
        List<UiNode> probe = new ArrayList<>();
        for (Screen s : eligible) {
            probe.clear();
            collectFocusables(s.getRoot(), probe);
            if (!probe.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    /**
     * Executes {@code render}.
     *
     * @param pipeline the pipeline argument
     * @param fontManager the fontManager argument
     * @param textureManager the textureManager argument
     * @param elapsedSeconds the elapsedSeconds argument
     */
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

    /**
     * Returns the focusedNode value.
     *
     * @return the resulting value
     */
    public UiNode getFocusedNode() {
        return focusedNode;
    }

    /**
     * Returns the integrated {@link GamepadNavigationRouter} used for UI focus navigation.
     *
     * @return gamepad navigation router
     */
    public GamepadNavigationRouter getGamepadRouter() {
        return gamepadRouter;
    }

    /**
     * Returns the logicalWidth value.
     *
     * @return the resulting value
     */
    public float getLogicalWidth() {
        return logicalWidth;
    }

    /**
     * Configures optional accessibility focus narration listener (e.g., wired to TTS interrupt) ({@code #0388}).
     *
     * @param focusNarrator consumer receiving newly focused widget descriptions
     */
    public void setFocusNarrator(Consumer<String> focusNarrator) {
        this.focusNarrator = focusNarrator;
    }

    /**
     * Configures optional UI sound cue listener for hover/back cues ({@code #0381}).
     *
     * @param soundCueListener listener notified on focus/back transitions
     */
    public void setSoundCueListener(Consumer<UiPolishAndServices.UiCue> soundCueListener) {
        this.soundCueListener = soundCueListener;
    }

    /**
     * Returns the logicalHeight value.
     *
     * @return the resulting value
     */
    public float getLogicalHeight() {
        return logicalHeight;
    }
}
