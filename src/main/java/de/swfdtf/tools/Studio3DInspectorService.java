package de.swfdtf.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.swfdtf.graphics.Camera3D;
import de.swfdtf.graphics.Material3D;
import de.swfdtf.graphics.Scene3DWorld;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Studio-only 3D authoring and inspection service ({@code #0323}, {@code #0324}, {@code #0325},
 * {@code #0327}, {@code #0331}).
 *
 * <p>Provides a live {@link Camera3D} parameter inspector, X/Y/Z axis gizmo prop dragging with
 * {@code .vjava} snippet formatting, live {@link Material3D} uniform editing and JSON export, and
 * Studio-gated 3D nav-path debug line generation.</p>
 */
public final class Studio3DInspectorService {

    /**
     * Gizmo translation axis for 3D prop dragging in Studio.
     */
    public enum GizmoAxis {
        /** World X axis. */
        X,
        /** World Y axis. */
        Y,
        /** World Z axis. */
        Z
    }

    /**
     * Immutable snapshot of {@link Camera3D} parameters for live Studio UI binding ({@code #0323}).
     *
     * @param x              camera world X position
     * @param y              camera world Y position
     * @param z              camera world Z position
     * @param yawDegrees     camera yaw angle in degrees
     * @param pitchDegrees   camera pitch angle in degrees
     * @param fovDegrees     vertical FOV in degrees
     * @param nearClip       near plane distance
     * @param farClip        far plane distance
     * @param projectionMode active projection mode
     */
    public record CameraSnapshot(
            float x,
            float y,
            float z,
            float yawDegrees,
            float pitchDegrees,
            float fovDegrees,
            float nearClip,
            float farClip,
            Camera3D.ProjectionMode projectionMode
    ) {
        /**
         * Formats a human-readable Studio inspector summary string.
         *
         * @return formatted camera inspector text
         */
        public String formatSummary() {
            return String.format(
                    Locale.ROOT,
                    "pos=(%.2f, %.2f, %.2f) yaw=%.1f pitch=%.1f fov=%.1f mode=%s",
                    x, y, z, yawDegrees, pitchDegrees, fovDegrees, projectionMode
            );
        }
    }

    /**
     * Result of dragging a 3D scene node along a gizmo axis ({@code #0324}).
     *
     * @param nodeId       moved node identifier
     * @param x            updated world X position
     * @param y            updated world Y position
     * @param z            updated world Z position
     * @param vjavaSnippet copy-ready {@code spawn3d} line for scripts
     */
    public record PropDragResult(String nodeId, float x, float y, float z, String vjavaSnippet) {
    }

    /**
     * Debug line segment rendered exclusively in Studio for nav-path and collider inspection ({@code #0331}).
     *
     * @param ax       start X
     * @param ay       start Y
     * @param az       start Z
     * @param bx       end X
     * @param by       end Y
     * @param bz       end Z
     * @param colorHex hex color tag
     */
    public record DebugLine3D(
            float ax,
            float ay,
            float az,
            float bx,
            float by,
            float bz,
            String colorHex
    ) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private boolean studioDebugEnabled = true;

    /**
     * Creates a new Studio 3D inspector service with debug visualization enabled.
     */
    public Studio3DInspectorService() {
    }

    /**
     * Enables or disables Studio-only 3D debug visualizations ({@code #0331}).
     *
     * @param enabled {@code true} when running inside Studio debug view
     */
    public void setStudioDebugEnabled(boolean enabled) {
        this.studioDebugEnabled = enabled;
    }

    /**
     * Returns whether Studio 3D debug overlays are currently enabled.
     *
     * @return {@code true} if Studio debug lines may be emitted
     */
    public boolean isStudioDebugEnabled() {
        return studioDebugEnabled;
    }

    /**
     * Captures live parameters from {@code camera} ({@code #0323}).
     *
     * @param camera active 3D camera
     * @return immutable {@link CameraSnapshot}
     */
    public CameraSnapshot inspectCamera(Camera3D camera) {
        Objects.requireNonNull(camera, "camera must not be null");
        Vector3f pos = camera.getPosition();
        return new CameraSnapshot(
                pos.x,
                pos.y,
                pos.z,
                camera.getYawDegrees(),
                camera.getPitchDegrees(),
                camera.getFovDegrees(),
                camera.getNearClip(),
                camera.getFarClip(),
                camera.getProjectionMode()
        );
    }

    /**
     * Applies edited position and FOV values directly to {@code camera} in real time ({@code #0323}).
     *
     * @param camera     target 3D camera
     * @param x          new world X
     * @param y          new world Y
     * @param z          new world Z
     * @param fovDegrees new vertical FOV (clamped cleanly by {@link Camera3D})
     * @return refreshed {@link CameraSnapshot}
     */
    public CameraSnapshot applyCameraEdit(Camera3D camera, float x, float y, float z, float fovDegrees) {
        Objects.requireNonNull(camera, "camera must not be null");
        camera.setPosition(x, y, z);
        camera.setFovDegrees(fovDegrees);
        return inspectCamera(camera);
    }

    /**
     * Moves an existing prop node in {@code world} along a single gizmo axis with optional grid
     * snapping and returns an updated {@code spawn3d} DSL snippet ({@code #0324}).
     *
     * @param world    active 3D scene world
     * @param nodeId   identifier of the prop node to move
     * @param axis     selected gizmo axis
     * @param delta    signed distance delta in world units
     * @param snapStep grid snap increment in world units ({@code <= 0} disables snapping)
     * @return {@link PropDragResult} with updated coordinates and DSL snippet
     */
    public PropDragResult dragNodeAlongAxis(
            Scene3DWorld world,
            String nodeId,
            GizmoAxis axis,
            float delta,
            float snapStep
    ) {
        Objects.requireNonNull(world, "world must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(axis, "axis must not be null");
        Scene3DWorld.Node3D node = world.getNode(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown 3D node: " + nodeId));

        Vector3f pos = new Vector3f(node.getPosition());
        switch (axis) {
            case X -> pos.x = snapValue(pos.x + delta, snapStep);
            case Y -> pos.y = snapValue(pos.y + delta, snapStep);
            case Z -> pos.z = snapValue(pos.z + delta, snapStep);
        }
        node.setPosition(pos.x, pos.y, pos.z);
        String meshRef = node.getMesh() != null ? node.getMesh().getName() : "cube";
        String snippet = String.format(
                Locale.ROOT,
                "spawn3d %s \"%s\" at %.2f %.2f %.2f",
                node.getId(),
                meshRef,
                pos.x,
                pos.y,
                pos.z
        );
        return new PropDragResult(node.getId(), pos.x, pos.y, pos.z, snippet);
    }

    /**
     * Edits material shader style and uniform parameters on a 3D node at runtime ({@code #0325}).
     *
     * @param world          active 3D scene world
     * @param nodeId         node identifier
     * @param style          optional new shader style (null preserves existing)
     * @param albedoHex      optional hex tint override (null preserves existing)
     * @param metallic       optional metallic override in {@code [0..1]} (null preserves existing)
     * @param roughness      optional roughness override in {@code [0..1]} (null preserves existing)
     * @param emissiveFactor optional emissive multiplier ({@code >= 0}, null preserves existing)
     * @return updated {@link Material3D} instance attached to the node
     */
    public Material3D updateNodeMaterial(
            Scene3DWorld world,
            String nodeId,
            Material3D.ShaderStyle style,
            String albedoHex,
            Float metallic,
            Float roughness,
            Float emissiveFactor
    ) {
        Objects.requireNonNull(world, "world must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Scene3DWorld.Node3D node = world.getNode(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown 3D node: " + nodeId));
        Material3D base = node.getMaterial() != null
                ? node.getMaterial()
                : Material3D.defaultPbr(nodeId + "_mat", "#d0d7e2");
        float effRoughness = roughness != null ? roughness : base.getRoughness();
        float effMetallic = metallic != null ? metallic : base.getMetallic();
        Material3D overridden = base.withOverrides(albedoHex, effRoughness, effMetallic);
        Material3D updated = new Material3D(
                overridden.getName(),
                style != null ? style : overridden.getStyle(),
                overridden.getAlbedoRgba(),
                overridden.getMetallic(),
                overridden.getRoughness(),
                emissiveFactor != null ? Math.max(0.0f, emissiveFactor) : overridden.getEmissiveFactor(),
                overridden.getToonBands(),
                overridden.getAlphaCutoff(),
                overridden.isTransparent(),
                overridden.getAlbedoTexturePath(),
                overridden.getNormalTexturePath()
        );
        node.setMaterial(updated);
        return updated;
    }

    /**
     * Serializes all node material configurations in {@code world} to formatted JSON ({@code #0325}).
     *
     * @param world source 3D scene world
     * @return pretty-printed JSON map keyed by node ID
     */
    public String exportMaterialsJson(Scene3DWorld world) {
        Objects.requireNonNull(world, "world must not be null");
        Map<String, Object> root = new LinkedHashMap<>();
        for (Scene3DWorld.Node3D node : world.getNodes()) {
            Material3D mat = node.getMaterial();
            if (mat == null) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("materialName", mat.getName());
            entry.put("style", mat.getStyle().name());
            entry.put("albedo", mat.getAlbedoRgba());
            entry.put("metallic", mat.getMetallic());
            entry.put("roughness", mat.getRoughness());
            entry.put("emissiveFactor", mat.getEmissiveFactor());
            entry.put("toonBands", mat.getToonBands());
            entry.put("transparent", mat.isTransparent());
            root.put(node.getId(), entry);
        }
        return GSON.toJson(root);
    }

    /**
     * Generates Studio-only debug segments for a 3D navigation path and waypoint markers ({@code #0331}).
     * Returns an empty list when Studio debug visualization is disabled (e.g., standalone release builds).
     *
     * @param waypoints ordered 3D path points
     * @param halfWidth corridor half-width indicator around waypoints
     * @return list of {@link DebugLine3D} segments, empty outside Studio debug mode
     */
    public List<DebugLine3D> buildNavPathLines(List<Vector3f> waypoints, float halfWidth) {
        if (!studioDebugEnabled || waypoints == null || waypoints.size() < 2) {
            return List.of();
        }
        List<DebugLine3D> lines = new ArrayList<>();
        float r = Math.max(0.05f, halfWidth);
        for (int i = 0; i < waypoints.size() - 1; i++) {
            Vector3f a = waypoints.get(i);
            Vector3f b = waypoints.get(i + 1);
            lines.add(new DebugLine3D(a.x, a.y + 0.05f, a.z, b.x, b.y + 0.05f, b.z, "#3fb950"));
        }
        for (Vector3f p : waypoints) {
            lines.add(new DebugLine3D(p.x - r, p.y + 0.05f, p.z, p.x + r, p.y + 0.05f, p.z, "#58a6ff"));
            lines.add(new DebugLine3D(p.x, p.y + 0.05f, p.z - r, p.x, p.y + 0.05f, p.z + r, "#58a6ff"));
        }
        return List.copyOf(lines);
    }

    private static float snapValue(float value, float snapStep) {
        if (snapStep <= 0.0f) {
            return value;
        }
        return Math.round(value / snapStep) * snapStep;
    }
}
