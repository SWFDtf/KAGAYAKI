package de.swfdtf.graphics;

import de.swfdtf.math.ScreenShake;
import de.swfdtf.math.Vec2f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Named, Z-ordered layer within a {@link SceneGraph}.
 *
 * <p>Holds an ordered list of {@link SceneNode}s, controls layer visibility and parallax weighting,
 * supports optional Y-position depth sorting ({@code ysort}) and per-layer {@link ScreenShake},
 * and exposes blueprint-standard Z-order constants ranging from {@link #Z_BACKGROUND} ({@code -100})
 * to {@link #Z_INSPECTOR} ({@code 1000}).</p>
 */
public final class SceneLayer {

    /** Z-order for background layer (still images, videos, panoramas). */
    public static final int Z_BACKGROUND = -100;

    /** Z-order for master stage layer (2D character sprites, layered images). */
    public static final int Z_MASTER = 0;

    /** Z-order for foreground layer (particles, weather, lighting vignettes). */
    public static final int Z_FOREGROUND = 100;

    /** Z-order for UI screens layer (ADV/NVL/bubble dialog boxes, quick menus, HUDs). */
    public static final int Z_SCREENS = 200;

    /** Z-order for transient overlays layer (choice menus, text prompts, popups). */
    public static final int Z_OVERLAY = 300;

    /** Z-order for modal menus layer (main menu, save/load, preferences, history, gallery). */
    public static final int Z_MODAL = 400;

    /** Z-order for system toast notifications layer (achievement popups, skip/auto HUD). */
    public static final int Z_TOAST = 500;

    /** Z-order for cursor and developer inspector overlay layer. */
    public static final int Z_INSPECTOR = 1000;

    private static final Comparator<SceneNode> NODE_Z_COMPARATOR =
            Comparator.comparingInt(SceneNode::getZOrder);

    private static final Comparator<SceneNode> NODE_YSORT_COMPARATOR =
            Comparator.comparingInt(SceneNode::getZOrder)
                    .thenComparingDouble(n -> n.getTransform().getYpos());

    private final String name;
    private int zOrder;
    private boolean visible = true;
    private boolean ysortEnabled = false;
    private float parallaxFactor = 0.0f;
    private ScreenShake layerShake;
    private final List<SceneNode> nodes = new ArrayList<>();

    /**
     * Creates a new visible {@code SceneLayer} with zero layer parallax.
     *
     * @param name   unique layer name (non-null, non-blank)
     * @param zOrder layer draw order (lower values render behind higher values)
     */
    public SceneLayer(String name, int zOrder) {
        this(name, zOrder, 0.0f);
    }

    /**
     * Creates a new visible {@code SceneLayer} with an explicit parallax factor.
     *
     * @param name           unique layer name (non-null, non-blank)
     * @param zOrder         layer draw order
     * @param parallaxFactor normalized mouse-parallax displacement factor for this layer
     */
    public SceneLayer(String name, int zOrder, float parallaxFactor) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Layer name must not be null or blank");
        }
        this.name = name;
        this.zOrder = zOrder;
        this.parallaxFactor = parallaxFactor;
    }

    /**
     * Returns the layer name.
     *
     * @return layer name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the layer Z-order.
     *
     * @return layer Z-order
     */
    public int getZOrder() {
        return zOrder;
    }

    /**
     * Updates the Z-order of this layer.
     *
     * @param zOrder new Z-order
     * @return {@code this} for fluent chaining
     */
    public SceneLayer setZOrder(int zOrder) {
        this.zOrder = zOrder;
        return this;
    }

    /**
     * Returns whether this layer is rendered.
     *
     * @return {@code true} if this layer is rendered
     */
    public boolean isVisible() {
        return visible;
    }

    /**
     * Sets whether this layer should be rendered.
     *
     * @param visible visibility toggle
     * @return {@code this} for fluent chaining
     */
    public SceneLayer setVisible(boolean visible) {
        this.visible = visible;
        return this;
    }

    /**
     * Returns the layer-wide normalized mouse parallax factor.
     *
     * @return layer-wide normalized mouse parallax factor
     */
    public float getParallaxFactor() {
        return parallaxFactor;
    }

    /**
     * Sets the layer-wide normalized mouse parallax factor.
     *
     * @param parallaxFactor parallax displacement factor
     * @return {@code this} for fluent chaining
     */
    public SceneLayer setParallaxFactor(float parallaxFactor) {
        this.parallaxFactor = parallaxFactor;
        return this;
    }

    /**
     * Appends a {@link SceneNode} to this layer if not already present.
     *
     * @param node node to add (must not be {@code null})
     * @return {@code this} for fluent chaining
     */
    public SceneLayer addNode(SceneNode node) {
        Objects.requireNonNull(node, "node must not be null");
        if (!nodes.contains(node)) {
            nodes.add(node);
        }
        return this;
    }

    /**
     * Removes a specific {@link SceneNode} from this layer.
     *
     * @param node node to remove
     * @return {@code true} if the node was present and removed
     */
    public boolean removeNode(SceneNode node) {
        return node != null && nodes.remove(node);
    }

    /**
     * Removes all top-level nodes in this layer whose {@link SceneNode#getTag()} equals {@code tag}.
     *
     * @param tag tag to match (returns {@code 0} if {@code null})
     * @return number of removed nodes
     */
    public int removeByTag(String tag) {
        if (tag == null) {
            return 0;
        }
        int removed = 0;
        Iterator<SceneNode> it = nodes.iterator();
        while (it.hasNext()) {
            if (Objects.equals(it.next().getTag(), tag)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /**
     * Finds all top-level nodes in this layer whose {@link SceneNode#getTag()} equals {@code tag},
     * preserving insertion order.
     *
     * @param tag tag to match
     * @return unmodifiable list of matching nodes
     */
    public List<SceneNode> findByTag(String tag) {
        if (tag == null) {
            return List.of();
        }
        List<SceneNode> matches = new ArrayList<>();
        for (SceneNode node : nodes) {
            if (Objects.equals(node.getTag(), tag)) {
                matches.add(node);
            }
        }
        return Collections.unmodifiableList(matches);
    }

    /**
     * Finds the first top-level node in this layer matching {@code id}.
     *
     * @param id node identifier
     * @return matching node, or {@code null} if not found
     */
    public SceneNode findById(String id) {
        if (id == null) {
            return null;
        }
        for (SceneNode node : nodes) {
            if (Objects.equals(node.getId(), id)) {
                return node;
            }
        }
        return null;
    }

    /**
     * Removes all nodes from this layer.
     */
    public void clear() {
        nodes.clear();
    }

    /**
     * Returns the number of top-level nodes in this layer.
     *
     * @return number of top-level nodes in this layer
     */
    public int size() {
        return nodes.size();
    }

    /**
     * Returns whether this layer contains no nodes.
     *
     * @return {@code true} if this layer contains no nodes
     */
    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /**
     * Returns an unmodifiable view of top-level nodes in insertion order.
     *
     * @return unmodifiable node list
     */
    public List<SceneNode> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    /**
     * Returns an unmodifiable snapshot of top-level nodes stably sorted by {@link SceneNode#getZOrder()}
     * (and by {@code ypos} when {@link #isYSortEnabled()} is active) in ascending order.
     *
     * @return sorted node list
     */
    public List<SceneNode> getSortedNodes() {
        if (nodes.isEmpty()) {
            return List.of();
        }
        List<SceneNode> sorted = new ArrayList<>(nodes);
        sorted.sort(ysortEnabled ? NODE_YSORT_COMPARATOR : NODE_Z_COMPARATOR);
        return Collections.unmodifiableList(sorted);
    }

    /**
     * Returns whether Y-position depth sorting is enabled on this layer.
     *
     * @return {@code true} if Y-sort is active
     */
    public boolean isYSortEnabled() {
        return ysortEnabled;
    }

    /**
     * Enables or disables Y-position depth sorting so sprites lower on screen render in front.
     *
     * @param ysortEnabled {@code true} to sort by ascending Y within the same Z-order
     * @return {@code this} for fluent chaining
     */
    public SceneLayer setYSortEnabled(boolean ysortEnabled) {
        this.ysortEnabled = ysortEnabled;
        return this;
    }

    /**
     * Starts or replaces a layer-local damped screen shake without mutating node transforms.
     *
     * @param amplitudePx     maximum shake amplitude in logical pixels
     * @param durationSeconds total shake duration in seconds
     */
    public void triggerShake(float amplitudePx, float durationSeconds) {
        if (!Float.isFinite(amplitudePx) || !Float.isFinite(durationSeconds) || durationSeconds <= 0.0f) {
            this.layerShake = null;
            return;
        }
        float amp = Math.max(0.0f, amplitudePx);
        this.layerShake = ScreenShake.custom(
                amp, amp * 0.7f, ScreenShake.DEFAULT_DECAY, ScreenShake.DEFAULT_OMEGA, durationSeconds);
    }

    /**
     * Advances any active layer-local shake by {@code dt} seconds and clears it on expiry.
     *
     * @param dt elapsed delta seconds ({@code >= 0})
     */
    public void updateShake(float dt) {
        if (layerShake != null && dt >= 0.0f && Float.isFinite(dt)) {
            layerShake.update(dt);
            if (layerShake.isFinished()) {
                layerShake = null;
            }
        }
    }

    /**
     * Returns the current render-time shake offset for this layer, or {@link Vec2f#ZERO} when inactive.
     *
     * @return render shake offset
     */
    public Vec2f getShakeOffset() {
        if (layerShake == null || layerShake.isFinished()) {
            return Vec2f.ZERO;
        }
        return layerShake.getOffset();
    }
}
