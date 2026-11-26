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
     *
     * @param glfwKey          GLFW key code
     * @param requiredModsMask required modifier bitmask
     * @param action           callback to run when triggered
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

        /**
         * Creates a screen timer.
         *
         * @param intervalSeconds timer interval in seconds
         * @param repeat          whether the timer repeats after firing
         * @param action          callback invoked on trigger
         */
        public ScreenTimer(double intervalSeconds, boolean repeat, Runnable action) {
            this.intervalSeconds = Math.max(0.001, intervalSeconds);
            this.repeat = repeat;
            this.action = Objects.requireNonNull(action, "action must not be null");
        }

        /**
         * Advances elapsed timer time and triggers actions when the interval completes.
         *
         * @param dt delta seconds
         */
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

        /**
         * Returns whether this one-shot timer has completed.
         *
         * @return {@code true} if finished
         */
        public boolean isFinished() {
            return finished;
        }
    }

    private enum TransitionState {
        IDLE,
        SHOWING,
        HIDING
    }

    private final String name;
    private int zOrder = SceneLayer.Z_SCREENS;
    private boolean modal = false;
    private boolean visible = true;
    private UiNode root;
    private final List<KeyBinding> keyBindings = new ArrayList<>();
    private final List<ScreenTimer> timers = new ArrayList<>();
    private double transitionDurationSeconds = 0.0;
    private TransitionState transitionState = TransitionState.IDLE;
    private double transitionElapsed = 0.0;
    private float transitionStartAlpha = 1.0f;

    /**
     * Creates a non-modal screen with default Z-order.
     *
     * @param name canonical screen name
     */
    public Screen(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    /**
     * Creates a screen with explicit Z-order and modality.
     *
     * @param name   canonical screen name
     * @param zOrder render layer Z-order
     * @param modal  whether the screen blocks lower input
     */
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
     *
     * @param logicalWidth  logical viewport width
     * @param logicalHeight logical viewport height
     */
    public void layout(float logicalWidth, float logicalHeight) {
        if (root != null) {
            root.layout(new Rect2f(0.0f, 0.0f, logicalWidth, logicalHeight));
        }
    }

    /**
     * Advances timers and root UI animations.
     *
     * @param dt delta time in seconds
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
     *
     * @param pipeline       2D render pipeline
     * @param fontManager    font manager
     * @param textureManager texture manager
     * @param elapsedSeconds total elapsed clock seconds
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

    /**
     * Registers a key binding with no required modifier mask.
     *
     * @param glfwKey GLFW key code
     * @param action  action callback
     * @return {@code this}
     */
    public Screen addKeyBinding(int glfwKey, Runnable action) {
        return addKeyBinding(glfwKey, 0, action);
    }

    /**
     * Registers a key binding with an explicit modifier bitmask.
     *
     * @param glfwKey          GLFW key code
     * @param requiredModsMask required modifier bitmask
     * @param action           action callback
     * @return {@code this}
     */
    public Screen addKeyBinding(int glfwKey, int requiredModsMask, Runnable action) {
        keyBindings.add(new KeyBinding(glfwKey, requiredModsMask, Objects.requireNonNull(action)));
        return this;
    }

    /**
     * Registers a periodic or one-shot timer on this screen.
     *
     * @param intervalSeconds trigger interval in seconds
     * @param repeat          whether to repeat
     * @param action          callback action
     * @return {@code this}
     */
    public Screen addTimer(double intervalSeconds, boolean repeat, Runnable action) {
        timers.add(new ScreenTimer(intervalSeconds, repeat, action));
        return this;
    }

    /**
     * Dispatches matching key bindings for the given GLFW key event.
     *
     * @param key  GLFW key code
     * @param mods active GLFW modifier flags
     * @return {@code true} if a binding matched and ran
     */
    public boolean triggerKeyBindings(int key, int mods) {
        for (KeyBinding kb : List.copyOf(keyBindings)) {
            boolean modsOk = kb.requiredModsMask() == 0
                    || (mods & kb.requiredModsMask()) == kb.requiredModsMask();
            if (kb.glfwKey() == key && modsOk) {
                kb.action().run();
                return true;
            }
        }
        return false;
    }

    /**
     * Returns an unmodifiable view of registered key bindings.
     *
     * @return list of key bindings
     */
    public List<KeyBinding> getKeyBindings() {
        return Collections.unmodifiableList(keyBindings);
    }

    /**
     * Returns the canonical screen name.
     *
     * @return screen name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the screen Z-order.
     *
     * @return Z-order
     */
    public int getZOrder() {
        return zOrder;
    }

    /**
     * Sets the screen Z-order.
     *
     * @param zOrder Z-order layer value
     * @return {@code this}
     */
    public Screen setZOrder(int zOrder) {
        this.zOrder = zOrder;
        return this;
    }

    /**
     * Returns whether this screen is modal.
     *
     * @return {@code true} if modal
     */
    public boolean isModal() {
        return modal;
    }

    /**
     * Sets whether this screen is modal.
     *
     * @param modal modal flag
     * @return {@code this}
     */
    public Screen setModal(boolean modal) {
        this.modal = modal;
        return this;
    }

    /**
     * Returns whether this screen is visible.
     *
     * @return {@code true} if visible
     */
    public boolean isVisible() {
        return visible;
    }

    /**
     * Sets screen visibility.
     *
     * @param visible visibility flag
     * @return {@code this}
     */
    public Screen setVisible(boolean visible) {
        this.visible = visible;
        return this;
    }

    /**
     * Returns the root UI node.
     *
     * @return root node (nullable)
     */
    public UiNode getRoot() {
        return root;
    }

    /**
     * Sets the root UI node.
     *
     * @param root root node
     * @return {@code this}
     */
    public Screen setRoot(UiNode root) {
        this.root = root;
        return this;
    }

    /**
     * Returns the configured fade show/hide duration in seconds ({@code 0.0} means instant).
     *
     * @return transition duration in seconds
     */
    public double getTransitionDurationSeconds() {
        return transitionDurationSeconds;
    }

    /**
     * Sets the fade show/hide duration in seconds ({@code <= 0} disables animation).
     *
     * @param seconds transition duration in seconds
     * @return {@code this}
     */
    public Screen setTransitionDurationSeconds(double seconds) {
        this.transitionDurationSeconds = Double.isFinite(seconds) ? Math.max(0.0, seconds) : 0.0;
        return this;
    }

    /**
     * Returns whether this screen is currently animating a show or hide transition.
     *
     * @return {@code true} if transitioning
     */
    public boolean isTransitioning() {
        return transitionState != TransitionState.IDLE;
    }

    /**
     * Returns whether this screen is currently fading out before hiding.
     *
     * @return {@code true} when fading out
     */
    public boolean isHidingTransition() {
        return transitionState == TransitionState.HIDING;
    }

    void clearTransition() {
        this.transitionState = TransitionState.IDLE;
        this.transitionElapsed = 0.0;
        this.transitionStartAlpha = 1.0f;
    }

    void beginShowTransition() {
        float currentAlpha = (visible && root != null) ? root.getAlpha() : 0.0f;
        this.transitionStartAlpha = Math.max(0.0f, Math.min(1.0f, currentAlpha));
        this.transitionElapsed = 0.0;
        this.transitionState = TransitionState.SHOWING;
        this.visible = true;
        if (root != null) {
            root.setAlpha(this.transitionStartAlpha);
        }
    }

    void beginHideTransition() {
        float currentAlpha = (root != null) ? root.getAlpha() : 1.0f;
        this.transitionStartAlpha = Math.max(0.05f, Math.min(1.0f, currentAlpha));
        this.transitionElapsed = 0.0;
        this.transitionState = TransitionState.HIDING;
        this.visible = true;
        if (root != null) {
            root.setAlpha(this.transitionStartAlpha);
        }
    }

    boolean advanceTransition(double dt) {
        if (transitionState == TransitionState.IDLE) {
            return false;
        }
        if (transitionDurationSeconds <= 0.0) {
            boolean wasHiding = transitionState == TransitionState.HIDING;
            transitionState = TransitionState.IDLE;
            if (root != null) {
                root.setAlpha(wasHiding ? 0.0f : 1.0f);
            }
            return wasHiding;
        }
        transitionElapsed += Math.max(0.0, dt);
        float progress = (float) Math.min(1.0, transitionElapsed / transitionDurationSeconds);
        if (transitionState == TransitionState.SHOWING) {
            float alpha = transitionStartAlpha + (1.0f - transitionStartAlpha) * progress;
            if (root != null) {
                root.setAlpha(alpha);
            }
            if (progress >= 1.0f) {
                transitionState = TransitionState.IDLE;
            }
            return false;
        }
        float alpha = transitionStartAlpha * (1.0f - progress);
        if (root != null) {
            root.setAlpha(alpha);
        }
        if (progress >= 1.0f) {
            transitionState = TransitionState.IDLE;
            return true;
        }
        return false;
    }
}
