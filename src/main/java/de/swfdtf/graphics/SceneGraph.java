package de.swfdtf.graphics;

import de.swfdtf.math.ScreenShake;
import de.swfdtf.math.Vec2f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Root scene graph managing Z-ordered {@link SceneLayer}s, active {@link ScreenShake},
 * and normalized mouse coordinates for 2.5D parallax rendering.
 *
 * <p>Pre-populates the 8 blueprint-standard layers in ascending Z-order:
 * {@code background (-100)}, {@code master (0)}, {@code foreground (100)}, {@code screens (200)},
 * {@code overlay (300)}, {@code modal (400)}, {@code toast (500)}, and {@code inspector (1000)}.</p>
 */
public final class SceneGraph {

    /** Standard layer name for backgrounds ({@link SceneLayer#Z_BACKGROUND}). */
    public static final String LAYER_BACKGROUND = "background";

    /** Standard layer name for the main character/sprite stage ({@link SceneLayer#Z_MASTER}). */
    public static final String LAYER_MASTER = "master";

    /** Standard layer name for foreground particles and vignettes ({@link SceneLayer#Z_FOREGROUND}). */
    public static final String LAYER_FOREGROUND = "foreground";

    /** Standard layer name for dialog boxes and HUD screens ({@link SceneLayer#Z_SCREENS}). */
    public static final String LAYER_SCREENS = "screens";

    /** Standard layer name for transient choice/prompt overlays ({@link SceneLayer#Z_OVERLAY}). */
    public static final String LAYER_OVERLAY = "overlay";

    /** Standard layer name for modal menus ({@link SceneLayer#Z_MODAL}). */
    public static final String LAYER_MODAL = "modal";

    /** Standard layer name for toast notifications ({@link SceneLayer#Z_TOAST}). */
    public static final String LAYER_TOAST = "toast";

    /** Standard layer name for cursors and developer inspectors ({@link SceneLayer#Z_INSPECTOR}). */
    public static final String LAYER_INSPECTOR = "inspector";

    private static final Comparator<SceneLayer> LAYER_Z_COMPARATOR =
            Comparator.comparingInt(SceneLayer::getZOrder);

    private final Map<String, SceneLayer> layers = new LinkedHashMap<>();
    private ScreenShake screenShake;
    private float normalizedMouseX = 0.0f;
    private float normalizedMouseY = 0.0f;

    /**
     * Constructs a {@code SceneGraph} pre-populated with all 8 standard blueprint layers.
     */
    public SceneGraph() {
        registerStandardLayers();
    }

    private void registerStandardLayers() {
        layers.put(LAYER_BACKGROUND, new SceneLayer(LAYER_BACKGROUND, SceneLayer.Z_BACKGROUND));
        layers.put(LAYER_MASTER, new SceneLayer(LAYER_MASTER, SceneLayer.Z_MASTER));
        layers.put(LAYER_FOREGROUND, new SceneLayer(LAYER_FOREGROUND, SceneLayer.Z_FOREGROUND));
        layers.put(LAYER_SCREENS, new SceneLayer(LAYER_SCREENS, SceneLayer.Z_SCREENS));
        layers.put(LAYER_OVERLAY, new SceneLayer(LAYER_OVERLAY, SceneLayer.Z_OVERLAY));
        layers.put(LAYER_MODAL, new SceneLayer(LAYER_MODAL, SceneLayer.Z_MODAL));
        layers.put(LAYER_TOAST, new SceneLayer(LAYER_TOAST, SceneLayer.Z_TOAST));
        layers.put(LAYER_INSPECTOR, new SceneLayer(LAYER_INSPECTOR, SceneLayer.Z_INSPECTOR));
    }

    /**
     * Looks up a layer by exact name.
     *
     * @param name layer name
     * @return matching {@link SceneLayer}, or {@code null} if not registered
     */
    public SceneLayer getLayer(String name) {
        if (name == null) {
            return null;
        }
        return layers.get(name);
    }

    /**
     * Returns an existing layer named {@code name}, or creates and registers a new one with {@code zOrder}.
     *
     * @param name   layer name (non-null, non-blank)
     * @param zOrder Z-order used if a new layer is created
     * @return existing or newly created {@link SceneLayer}
     */
    public SceneLayer getOrCreateLayer(String name, int zOrder) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Layer name must not be null or blank");
        }
        SceneLayer existing = layers.get(name);
        if (existing != null) {
            return existing;
        }
        SceneLayer created = new SceneLayer(name, zOrder);
        layers.put(name, created);
        return created;
    }

    /**
     * Returns an unmodifiable list of all registered layers stably sorted by {@link SceneLayer#getZOrder()}
     * in ascending order.
     *
     * @return Z-sorted layers
     */
    public List<SceneLayer> getLayersSorted() {
        List<SceneLayer> sorted = new ArrayList<>(layers.values());
        sorted.sort(LAYER_Z_COMPARATOR);
        return Collections.unmodifiableList(sorted);
    }

    /**
     * Adds a {@link SceneNode} to the named layer.
     *
     * @param layerName target layer name
     * @param node      node to add (must not be {@code null})
     * @throws IllegalArgumentException if no layer with {@code layerName} exists
     */
    public void addNode(String layerName, SceneNode node) {
        Objects.requireNonNull(node, "node must not be null");
        SceneLayer layer = getLayer(layerName);
        if (layer == null) {
            throw new IllegalArgumentException("Unknown scene layer: " + layerName);
        }
        layer.addNode(node);
    }

    /**
     * Removes all top-level nodes with matching {@code tag} from the specified layer.
     *
     * @param layerName target layer name
     * @param tag       tag to remove
     * @return number of removed nodes, or {@code 0} if the layer does not exist
     */
    public int removeByTag(String layerName, String tag) {
        SceneLayer layer = getLayer(layerName);
        if (layer == null) {
            return 0;
        }
        return layer.removeByTag(tag);
    }

    /**
     * Removes all nodes from every layer while preserving the layer definitions themselves.
     */
    public void clearAll() {
        for (SceneLayer layer : layers.values()) {
            layer.clear();
        }
    }

    /** @return currently active {@link ScreenShake}, or {@code null} if none */
    public ScreenShake getScreenShake() {
        return screenShake;
    }

    /**
     * Sets or clears the active {@link ScreenShake} effect applied to world layers.
     *
     * @param screenShake active screen shake or {@code null}
     */
    public void setScreenShake(ScreenShake screenShake) {
        this.screenShake = screenShake;
    }

    /**
     * Advances active time-dependent scene effects (such as {@link ScreenShake}) by {@code dt} seconds.
     *
     * @param dt elapsed time in seconds ({@code >= 0})
     */
    public void update(float dt) {
        if (screenShake != null) {
            screenShake.update(dt);
            if (screenShake.isFinished()) {
                screenShake = null;
            }
        }
    }

    /**
     * Sets the normalized mouse position relative to the center of the logical canvas,
     * clamped to {@code [-1, 1]} along each axis, for 2.5D parallax calculations.
     *
     * @param nx normalized horizontal cursor offset in {@code [-1, 1]} ({@code 0} = center)
     * @param ny normalized vertical cursor offset in {@code [-1, 1]} ({@code 0} = center)
     */
    public void setNormalizedMouse(float nx, float ny) {
        this.normalizedMouseX = Float.isFinite(nx) ? Math.clamp(nx, -1.0f, 1.0f) : 0.0f;
        this.normalizedMouseY = Float.isFinite(ny) ? Math.clamp(ny, -1.0f, 1.0f) : 0.0f;
    }

    /** @return clamped normalized mouse X offset in {@code [-1, 1]} */
    public float getNormalizedMouseX() {
        return normalizedMouseX;
    }

    /** @return clamped normalized mouse Y offset in {@code [-1, 1]} */
    public float getNormalizedMouseY() {
        return normalizedMouseY;
    }

    /** @return normalized mouse offset vector {@code (nx, ny)} */
    public Vec2f getNormalizedMouse() {
        return new Vec2f(normalizedMouseX, normalizedMouseY);
    }
}
