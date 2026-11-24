package de.swfdtf.ui;

import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.Rect2f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Declarative UI screen container holding a root {@link UiNode} tree, Z-order, modal flag,
 * screen-scoped keyboard shortcuts, and periodic timers.
 */
public class Screen {

    /**
     * Screen-scoped keyboard shortcut binding.
     */
    public record KeyBinding(int glfwKey, int requiredModsMask, Runnable action) {
    }

    /**
     * Screen-scoped timer entry.
     */
    public static final class ScreenTimer {
        private final double intervalSeconds;
        private final boolean repeat;
        private final Runnable action;
        private double elapsed = 0.0;
        private boolean finished = false;

        public ScreenTimer(double intervalSeconds, boolean repeat, Runnable action) {
            this.intervalSeconds = Math.max(0.001, intervalSeconds);
            this.repeat = repeat;
            this.action = Objects.requireNonNull(action, "action must not be null");
        }

        public void update(double dt) {
            if (finished || dt <= 0.0) {
                return;
            }
            elapsed += dt;
            while (elapsed >= intervalSeconds && !finished) {
                elapsed -= intervalSeconds;
                action.run();
                if (!repeat) {
                    finished = true;
                }
            }
        }

        public boolean isFinished() {
            return finished;
        }
    }

    private final String name;
    private int zOrder = SceneLayer.Z_SCREENS;
    private boolean modal = false;
    private boolean visible = true;
    private UiNode root;
    private final List<KeyBinding> keyBindings = new ArrayList<>();
    private final List<ScreenTimer> timers = new ArrayList<>();

    public Screen(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    public Screen(String name, int zOrder, boolean modal) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.zOrder = zOrder;
        this.modal = modal;
    }

    /**
     * Refreshes screen contents from backing state before layout/display.
     */
    public void onShow() {
    }

    /**
     * Invoked when the screen is hidden or dismissed.
     */
    public void onHide() {
    }

    /**
     * Resolves layout of the root {@link UiNode} tree within the logical viewport.
     */
    public void layout(float logicalWidth, float logicalHeight) {
        if (root != null) {
            root.layout(new Rect2f(0.0f, 0.0f, logicalWidth, logicalHeight));
        }
    }

    /**
     * Advances timers and root UI animations.
     */
    public void update(double dt) {
        if (!visible) {
            return;
        }
        for (int i = 0; i < timers.size(); i++) {
            ScreenTimer timer = timers.get(i);
            timer.update(dt);
        }
        timers.removeIf(ScreenTimer::isFinished);
        if (root != null) {
            root.update(dt);
        }
    }

    /**
     * Renders this screen if visible.
     */
    public void render(
            RenderPipeline2D pipeline,
            FontManager fontManager,
            TextureManager textureManager,
            double elapsedSeconds
    ) {
        if (visible && root != null) {
            root.render(pipeline, fontManager, textureManager, elapsedSeconds);
        }
    }

    public Screen addKeyBinding(int glfwKey, Runnable action) {
        return addKeyBinding(glfwKey, 0, action);
    }

    public Screen addKeyBinding(int glfwKey, int requiredModsMask, Runnable action) {
        keyBindings.add(new KeyBinding(glfwKey, requiredModsMask, Objects.requireNonNull(action)));
        return this;
    }

    public Screen addTimer(double intervalSeconds, boolean repeat, Runnable action) {
        timers.add(new ScreenTimer(intervalSeconds, repeat, action));
        return this;
    }

    public boolean triggerKeyBindings(int key, int mods) {
        for (KeyBinding kb : List.copyOf(keyBindings)) {
            if (kb.glfwKey() == key && (kb.requiredModsMask() == 0 || (mods & kb.requiredModsMask()) == kb.requiredModsMask())) {
                kb.action().run();
                return true;
            }
        }
        return false;
    }

    public List<KeyBinding> getKeyBindings() {
        return Collections.unmodifiableList(keyBindings);
    }

    public String getName() { return name; }
    public int getZOrder() { return zOrder; }
    public Screen setZOrder(int zOrder) { this.zOrder = zOrder; return this; }
    public boolean isModal() { return modal; }
    public Screen setModal(boolean modal) { this.modal = modal; return this; }
    public boolean isVisible() { return visible; }
    public Screen setVisible(boolean visible) { this.visible = visible; return this; }
    public UiNode getRoot() { return root; }
    public Screen setRoot(UiNode root) { this.root = root; return this; }
}
