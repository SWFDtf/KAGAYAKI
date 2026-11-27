package de.swfdtf.graphics;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 3D world graph holding {@link Node3D}s (meshes, 2.5D character billboards, LOD tiers with hysteresis,
 * colliders, decals, 2D-to-3D render screens, hover highlights), {@link Light3D}s (directional + top-8
 * nearest point/spot lights), exponential height/distance {@link Fog3DConfig}, 0..24h {@link Skydome3D},
 * planar mirror reflection math, {@link InspectItemOverlay}, {@link Minimap3DProjector}, and
 * value-type save/load {@link Scene3DSnapshot}.
 */
public final class Scene3DWorld {

    /**
     * 3D scene light source (directional sun/moon or attenuated local point/spot light).
     *
     * @param id        unique light ID
     * @param directional {@code true} for infinite sun/moon light, {@code false} for local point/spot
     * @param posOrDir  world position (point) or direction (directional)
     * @param colorRgb  straight RGB color
     * @param radius    influence radius (ignored when directional)
     * @param intensity brightness multiplier
     */
    public record Light3D(
            String id,
            boolean directional,
            Vector3f posOrDir,
            float[] colorRgb,
            float radius,
            float intensity
    ) {
    }

    /**
     * Exponential height and distance fog parameters ({@code fog3d}).
     *
     * @param enabled       whether fog is active
     * @param colorRgb      fog RGB tint
     * @param density       exponential distance density
     * @param heightFalloff vertical height falloff factor
     */
    public record Fog3DConfig(boolean enabled, float[] colorRgb, float density, float heightFalloff) {
        /** Disabled neutral fog config. */
        public static final Fog3DConfig NONE = new Fog3DConfig(false, new float[]{0.5f, 0.55f, 0.65f}, 0.0f, 0.0f);

        /**
         * Linearly interpolates fog color and density during {@code scene} transitions.
         *
         * @param other target fog configuration
         * @param t     blend factor in {@code [0, 1]}
         * @return interpolated {@link Fog3DConfig}
         */
        public Fog3DConfig lerp(Fog3DConfig other, float t) {
            float ct = Math.clamp(t, 0.0f, 1.0f);
            float[] col = new float[3];
            for (int i = 0; i < 3; i++) {
                col[i] = this.colorRgb[i] + (other.colorRgb[i] - this.colorRgb[i]) * ct;
            }
            return new Fog3DConfig(
                    this.enabled || other.enabled,
                    col,
                    this.density + (other.density - this.density) * ct,
                    this.heightFalloff + (other.heightFalloff - this.heightFalloff) * ct
            );
        }
    }

    /**
     * Animated 0..24h time-of-day skydome sun direction &amp; starfield intensity calculator.
     */
    public static final class Skydome3D {
        private float hourOfDay = 12.0f;

        /** Creates a skydome initialized to noon ({@code 12.0f}). */
        public Skydome3D() {
        }

        /**
         * Sets continuous time of day wrapped cleanly in {@code [0, 24)}.
         *
         * @param hour hour of day
         */
        public void setHourOfDay(float hour) {
            float h = Float.isFinite(hour) ? (hour % 24.0f) : 12.0f;
            this.hourOfDay = h < 0.0f ? h + 24.0f : h;
        }

        /**
         * Returns current hour in {@code [0, 24)}.
         *
         * @return hour of day
         */
        public float getHourOfDay() {
            return hourOfDay;
        }

        /**
         * Computes sun direction vector cleanly oscillating with {@code hourOfDay}
         * (highest +Y at noon {@code 12.0}, below horizon -Y at midnight {@code 0.0}).
         *
         * @return normalized sun direction vector
         */
        public Vector3f computeSunDirection() {
            double angle = ((hourOfDay - 6.0) / 24.0) * Math.PI * 2.0;
            return new Vector3f((float) Math.cos(angle), (float) Math.sin(angle), -0.35f).normalize();
        }

        /**
         * Returns star visibility intensity in {@code [0, 1]} peaking at midnight and 0 during daylight.
         *
         * @return starfield intensity
         */
        public float computeStarIntensity() {
            float sunY = computeSunDirection().y;
            return Math.clamp(-sunY * 1.4f, 0.0f, 1.0f);
        }
    }

    /**
     * Clickable/interactive 3D stage node wrapping a {@link Mesh3D}, transform, material, LOD tier,
     * optional 2.5D character billboard mode, optional collision AABB, and polygon-offset decal metadata.
     */
    public static final class Node3D {
        private final String id;
        private Mesh3D mesh;
        private Mesh3D lodLowMesh;
        private float lodSwitchDistance = 25.0f;
        private float lodHysteresis = 3.0f;
        private int activeLod = 0;
        private Material3D material;
        private final Vector3f position = new Vector3f();
        private final Vector3f rotationDeg = new Vector3f();
        private final Vector3f scale = new Vector3f(1, 1, 1);
        private boolean billboard2d = false;
        private boolean hoveredHighlight = false;
        private String clickLabelTarget;
        private float polygonOffsetFactor = 0.0f;
        private boolean collisionSolid = false;
        private String attached2dSceneTag;

        /**
         * Creates a named {@code Node3D}.
         *
         * @param id       unique identifier
         * @param mesh     mesh geometry
         * @param material surface material (defaults to white PBR if null)
         */
        public Node3D(String id, Mesh3D mesh, Material3D material) {
            this.id = Objects.requireNonNull(id, "id must not be null");
            this.mesh = Objects.requireNonNull(mesh, "mesh must not be null");
            this.material = material != null ? material : Material3D.defaultPbr(id + "_mat", "#ffffff");
        }

        /**
         * Sets world position.
         *
         * @param x world X position
         * @param y world Y position
         * @param z world Z position
         * @return this node for chaining
         */
        public Node3D setPosition(float x, float y, float z) {
            position.set(x, y, z);
            return this;
        }

        /**
         * Sets Euler rotation in degrees.
         *
         * @param rx X rotation in degrees
         * @param ry Y rotation in degrees
         * @param rz Z rotation in degrees
         * @return this node for chaining
         */
        public Node3D setRotationDeg(float rx, float ry, float rz) {
            rotationDeg.set(rx, ry, rz);
            return this;
        }

        /**
         * Sets scale multiplier.
         *
         * @param sx X scale factor
         * @param sy Y scale factor
         * @param sz Z scale factor
         * @return this node for chaining
         */
        public Node3D setScale(float sx, float sy, float sz) {
            scale.set(sx, sy, sz);
            return this;
        }

        /**
         * Computes world transform matrix.
         *
         * @return JOML model matrix
         */
        public Matrix4f computeModelMatrix() {
            return new Matrix4f()
                    .translate(position)
                    .rotateZ((float) Math.toRadians(rotationDeg.z))
                    .rotateY((float) Math.toRadians(rotationDeg.y))
                    .rotateX((float) Math.toRadians(rotationDeg.x))
                    .scale(scale);
        }

        /**
         * Computes billboard model matrix orienting the sprite quad normal cleanly toward {@code cameraPos}.
         *
         * @param cameraPos active camera position
         * @return world-space billboard normal direction
         */
        public Vector3f computeBillboardNormalTowardCamera(Vector3f cameraPos) {
            Vector3f dir = new Vector3f(cameraPos).sub(position);
            return dir.lengthSquared() > 1e-8f ? dir.normalize() : new Vector3f(0, 0, 1);
        }

        /**
         * Evaluates world-space AABB scaled and translated from local mesh bounds.
         *
         * @return world AABB
         */
        public Mesh3D.BoundingBox3D computeWorldAabb() {
            Mesh3D.BoundingBox3D b = mesh.getBoundingBox();
            return new Mesh3D.BoundingBox3D(
                    position.x + b.minX() * scale.x,
                    position.y + b.minY() * scale.y,
                    position.z + b.minZ() * scale.z,
                    position.x + b.maxX() * scale.x,
                    position.y + b.maxY() * scale.y,
                    position.z + b.maxZ() * scale.z
            );
        }

        /** Configures lower-poly LOD mesh with anti-popping hysteresis band.
         *
         * @param lowMesh        simplified mesh for distant rendering
         * @param switchDist     transition distance
         * @param hysteresisBand hysteresis width avoiding boundary flickering
         */
        public void configureLod(Mesh3D lowMesh, float switchDist, float hysteresisBand) {
            this.lodLowMesh = lowMesh;
            this.lodSwitchDistance = Math.max(1.0f, switchDist);
            this.lodHysteresis = Math.max(0.5f, hysteresisBand);
        }

        /**
         * Updates active LOD tier with hysteresis buffer.
         *
         * @param distanceToCamera camera distance
         * @return active LOD tier index ({@code 0} = high, {@code 1} = low)
         */
        public int selectLod(float distanceToCamera) {
            if (lodLowMesh == null) {
                activeLod = 0;
                return 0;
            }
            if (activeLod == 0 && distanceToCamera > lodSwitchDistance + lodHysteresis) {
                activeLod = 1;
            } else if (activeLod == 1 && distanceToCamera < lodSwitchDistance - lodHysteresis) {
                activeLod = 0;
            }
            return activeLod;
        }

        /**
         * Returns active mesh (high or low LOD).
         *
         * @return selected {@link Mesh3D}
         */
        public Mesh3D getActiveMesh() {
            return (activeLod == 1 && lodLowMesh != null) ? lodLowMesh : mesh;
        }

        /**
         * Returns node ID.
         *
         * @return id
         */
        public String getId() {
            return id;
        }

        /**
         * Returns base mesh.
         *
         * @return mesh
         */
        public Mesh3D getMesh() {
            return mesh;
        }

        /**
         * Returns material.
         *
         * @return material
         */
        public Material3D getMaterial() {
            return material;
        }

        /**
         * Replaces material override.
         *
         * @param material new material
         */
        public void setMaterial(Material3D material) {
            this.material = Objects.requireNonNull(material, "material must not be null");
        }

        /**
         * Returns world position copy.
         *
         * @return position
         */
        public Vector3f getPosition() {
            return new Vector3f(position);
        }

        /**
         * Marks 2.5D character billboard behavior.
         *
         * @param billboard2d {@code true} for camera-facing billboard
         * @return this node for chaining
         */
        public Node3D setBillboard2d(boolean billboard2d) {
            this.billboard2d = billboard2d;
            return this;
        }

        /**
         * Returns whether 2.5D billboard orientation is active.
         *
         * @return billboard flag
         */
        public boolean isBillboard2d() {
            return billboard2d;
        }

        /**
         * Marks whether pick3d ray hit is highlighting this node.
         *
         * @param hovered true if aimed at
         */
        public void setHoveredHighlight(boolean hovered) {
            this.hoveredHighlight = hovered;
        }

        /**
         * Returns hover highlight state.
         *
         * @return true if highlighted
         */
        public boolean isHoveredHighlight() {
            return hoveredHighlight;
        }

        /**
         * Sets script label jumped to on {@code pick3d} click.
         *
         * @param labelTarget target label name
         * @return this node for chaining
         */
        public Node3D setClickLabelTarget(String labelTarget) {
            this.clickLabelTarget = labelTarget;
            return this;
        }

        /**
         * Returns target click label.
         *
         * @return target label or null
         */
        public String getClickLabelTarget() {
            return clickLabelTarget;
        }

        /**
         * Sets collision solidity for 3D exploration sliding.
         *
         * @param solid {@code true} if solid collider
         * @return this node for chaining
         */
        public Node3D setCollisionSolid(boolean solid) {
            this.collisionSolid = solid;
            return this;
        }

        /**
         * Returns whether solid collision is active.
         *
         * @return true if solid
         */
        public boolean isCollisionSolid() {
            return collisionSolid;
        }

        /**
         * Configures decal polygon offset to prevent Z-fighting on floors.
         *
         * @param factor polygon offset factor
         * @return this node for chaining
         */
        public Node3D setPolygonOffsetFactor(float factor) {
            this.polygonOffsetFactor = factor;
            return this;
        }

        /**
         * Returns polygon offset factor.
         *
         * @return offset factor
         */
        public float getPolygonOffsetFactor() {
            return polygonOffsetFactor;
        }

        /** Binds a live 2D stage texture tag to project onto this 3D mesh surface.
         *
         * @param sceneTag 2D surface tag
         * @return this
         */
        public Node3D setAttached2dSceneTag(String sceneTag) {
            this.attached2dSceneTag = sceneTag;
            return this;
        }

        /**
         * Returns attached 2D render-to-texture surface tag.
         *
         * @return tag or null
         */
        public String getAttached2dSceneTag() {
            return attached2dSceneTag;
        }
    }

    /**
     * Modal 360-degree item inspection state isolating object rotation &amp; zoom from the main exploration camera.
     */
    public static final class InspectItemOverlay {
        private boolean open = false;
        private String itemId;
        private float yawDegrees = 0.0f;
        private float pitchDegrees = 0.0f;
        private float zoom = 1.0f;

        /** Creates an idle {@code InspectItemOverlay}. */
        public InspectItemOverlay() {
        }

        /**
         * Opens modal inspection for {@code itemId}.
         *
         * @param itemId clue/prop ID
         */
        public void open(String itemId) {
            this.itemId = Objects.requireNonNull(itemId, "itemId must not be null");
            this.yawDegrees = 0.0f;
            this.pitchDegrees = 0.0f;
            this.zoom = 1.0f;
            this.open = true;
        }

        /** Rotates inspected prop freely across 360° and adjusts zoom.
         *
         * @param dYaw   yaw delta degrees
         * @param dPitch pitch delta degrees
         * @param dZoom  zoom delta
         */
        public void orbit(float dYaw, float dPitch, float dZoom) {
            if (!open) {
                return;
            }
            yawDegrees = (yawDegrees + dYaw) % 360.0f;
            pitchDegrees = Math.clamp(pitchDegrees + dPitch, -85.0f, 85.0f);
            zoom = Math.clamp(zoom + dZoom, 0.5f, 3.0f);
        }

        /** Closes modal cleanly. */
        public void close() {
            this.open = false;
        }

        /**
         * Returns whether modal is open.
         *
         * @return true if open
         */
        public boolean isOpen() {
            return open;
        }

        /**
         * Returns inspected item ID.
         *
         * @return item ID
         */
        public String getItemId() {
            return itemId;
        }

        /**
         * Returns current rotation yaw.
         *
         * @return yaw degrees
         */
        public float getYawDegrees() {
            return yawDegrees;
        }

        /**
         * Returns current inspection zoom.
         *
         * @return zoom multiplier
         */
        public float getZoom() {
            return zoom;
        }
    }

    /**
     * Projects world XZ coordinates onto a 2D HUD minimap rectangle accurate to within 1 pixel.
     *
     * @param worldMinX world X lower bound
     * @param worldMaxX world X upper bound
     * @param worldMinZ world Z lower bound
     * @param worldMaxZ world Z upper bound
     * @param mapX      HUD minimap left X
     * @param mapY      HUD minimap top Y
     * @param mapWidth  HUD minimap width in px
     * @param mapHeight HUD minimap height in px
     */
    public record Minimap3DProjector(
            float worldMinX,
            float worldMaxX,
            float worldMinZ,
            float worldMaxZ,
            float mapX,
            float mapY,
            float mapWidth,
            float mapHeight
    ) {
        /**
         * Maps world {@code (x, z)} to 2D minimap {@code [px, py]} coordinates.
         *
         * @param worldX world X
         * @param worldZ world Z
         * @return 2-element {@code [mapPx, mapPy]}
         */
        public float[] projectWorldXzToMap(float worldX, float worldZ) {
            float u = (worldX - worldMinX) / Math.max(1e-4f, worldMaxX - worldMinX);
            float v = (worldZ - worldMinZ) / Math.max(1e-4f, worldMaxZ - worldMinZ);
            return new float[]{
                mapX + Math.clamp(u, 0.0f, 1.0f) * mapWidth,
                mapY + Math.clamp(v, 0.0f, 1.0f) * mapHeight
            };
        }
    }

    /**
     * Value-type save/load snapshot capturing active 3D camera pose and all spawned node coordinates.
     *
     * @param camX      camera position X
     * @param camY      camera position Y
     * @param camZ      camera position Z
     * @param targetX   look-at target X
     * @param targetY   look-at target Y
     * @param targetZ   look-at target Z
     * @param hourOfDay skydome hour
     * @param nodePoses serialized node coordinate triples keyed by node ID
     */
    public record Scene3DSnapshot(
            float camX,
            float camY,
            float camZ,
            float targetX,
            float targetY,
            float targetZ,
            float hourOfDay,
            Map<String, float[]> nodePoses
    ) {
    }

    /**
     * Monotonic 3D scene preloading progress tracker ({@code 0.0f -> 1.0f}) driving loading screens.
     */
    public static final class Scene3DPreloader {
        private final int totalAssets;
        private int loadedAssets = 0;

        /**
         * Creates a preloader for {@code totalAssets} items.
         *
         * @param totalAssets item count
         */
        public Scene3DPreloader(int totalAssets) {
            this.totalAssets = Math.max(0, totalAssets);
        }

        /** Advances completed item count by 1. */
        public void markAssetLoaded() {
            if (loadedAssets < totalAssets) {
                loadedAssets++;
            }
        }

        /**
         * Returns monotonic progress in {@code [0.0f, 1.0f]}.
         *
         * @return normalized progress
         */
        public float getProgress() {
            return totalAssets == 0 ? 1.0f : (loadedAssets / (float) totalAssets);
        }
    }

    private final List<Node3D> nodes = new ArrayList<>();
    private final List<Light3D> lights = new ArrayList<>();
    private Fog3DConfig fogConfig = Fog3DConfig.NONE;
    private final Skydome3D skydome = new Skydome3D();
    private final InspectItemOverlay inspectOverlay = new InspectItemOverlay();

    /** Creates an empty {@code Scene3DWorld}. */
    public Scene3DWorld() {
    }

    /**
     * Adds or replaces a {@link Node3D} by ID.
     *
     * @param node 3D node
     */
    public void putNode(Node3D node) {
        Objects.requireNonNull(node, "node must not be null");
        nodes.removeIf(n -> n.getId().equals(node.getId()));
        nodes.add(node);
    }

    /**
     * Returns immutable list of active 3D nodes.
     *
     * @return list of nodes
     */
    public List<Node3D> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    /**
     * Finds an active 3D node by its unique identifier.
     *
     * @param id node identifier
     * @return matching {@link Node3D}, if present
     */
    public Optional<Node3D> getNode(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        for (Node3D node : nodes) {
            if (id.equals(node.getId())) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns active 3D mesh node count.
     *
     * @return node count
     */
    public int getActiveMeshCount() {
        return nodes.size();
    }

    /**
     * Adds or replaces a light source.
     *
     * @param light light descriptor
     */
    public void putLight(Light3D light) {
        Objects.requireNonNull(light, "light must not be null");
        lights.removeIf(l -> l.id().equals(light.id()));
        lights.add(light);
    }

    /**
     * Selects up to 8 nearest point/spot lights relative to {@code focusPoint} (Top-N selection),
     * preserving directional lights automatically.
     *
     * @param focusPoint world reference point
     * @param maxLocal   max local lights (up to 8)
     * @return prioritized list of active lights
     */
    public List<Light3D> selectTopNearestLights(Vector3f focusPoint, int maxLocal) {
        int cap = Math.clamp(maxLocal, 1, 8);
        List<Light3D> result = new ArrayList<>();
        List<Light3D> local = new ArrayList<>();
        for (Light3D l : lights) {
            if (l.directional()) {
                result.add(l);
            } else {
                local.add(l);
            }
        }
        local.sort(Comparator.comparingDouble(l -> l.posOrDir().distanceSquared(focusPoint)));
        for (int i = 0; i < Math.min(cap, local.size()); i++) {
            result.add(local.get(i));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns nodes sorted back-to-front by camera distance when {@link Material3D#isTransparent()} is true,
     * placing opaque nodes cleanly before transparent glass/water surfaces.
     *
     * @param cameraPos active camera world position
     * @return sorted node list for draw submission
     */
    public List<Node3D> getSortedDrawNodes(Vector3f cameraPos) {
        List<Node3D> opaque = new ArrayList<>();
        List<Node3D> transparent = new ArrayList<>();
        for (Node3D n : nodes) {
            if (n.getMaterial().isTransparent()) {
                transparent.add(n);
            } else {
                opaque.add(n);
            }
        }
        transparent.sort((a, b) -> Float.compare(
                b.getPosition().distanceSquared(cameraPos),
                a.getPosition().distanceSquared(cameraPos)
        ));
        List<Node3D> ordered = new ArrayList<>(opaque.size() + transparent.size());
        ordered.addAll(opaque);
        ordered.addAll(transparent);
        return ordered;
    }

    /**
     * Executes raycast hotspot picking ({@code pick3d}) returning the closest clicked node if hit.
     *
     * @param ray world-space ray from {@link Camera3D#screenPointToRay}
     * @return hit {@link Node3D}, or {@code null} if missed
     */
    public Node3D pickNode(Camera3D.Ray3D ray) {
        Objects.requireNonNull(ray, "ray must not be null");
        Node3D best = null;
        float bestDist = Float.POSITIVE_INFINITY;
        for (Node3D n : nodes) {
            n.setHoveredHighlight(false);
            float dist = Camera3D.rayIntersectsAabbDistance(ray, n.computeWorldAabb());
            if (!Float.isNaN(dist) && dist < bestDist) {
                bestDist = dist;
                best = n;
            }
        }
        if (best != null) {
            best.setHoveredHighlight(true);
        }
        return best;
    }

    /**
     * Resolves player 3D exploration movement with smooth AABB sliding against solid walls so players
     * slide along walls cleanly without getting stuck or clipping through geometry.
     *
     * @param currentPos   starting player position
     * @param deltaMove    desired displacement
     * @param playerRadius player capsule horizontal half-extent
     * @return collision-resolved world position
     */
    public Vector3f moveWithWallCollision(Vector3f currentPos, Vector3f deltaMove, float playerRadius) {
        float r = Math.max(0.1f, playerRadius);
        Vector3f resolved = new Vector3f(currentPos);
        // Resolve X axis independently for clean wall sliding
        Vector3f tryX = new Vector3f(resolved.x + deltaMove.x, resolved.y, resolved.z);
        if (!collidesAnySolid(tryX, r)) {
            resolved.x = tryX.x;
        }
        // Resolve Z axis independently
        Vector3f tryZ = new Vector3f(resolved.x, resolved.y, resolved.z + deltaMove.z);
        if (!collidesAnySolid(tryZ, r)) {
            resolved.z = tryZ.z;
        }
        resolved.y += deltaMove.y;
        return resolved;
    }

    private boolean collidesAnySolid(Vector3f center, float radius) {
        Mesh3D.BoundingBox3D playerBox = new Mesh3D.BoundingBox3D(
                center.x - radius, center.y - 0.1f, center.z - radius,
                center.x + radius, center.y + 1.8f, center.z + radius
        );
        for (Node3D n : nodes) {
            if (n.isCollisionSolid() && playerBox.intersects(n.computeWorldAabb())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Computes planar mirror reflection matrix across a plane defined by point {@code planePoint} and unit normal
     * {@code planeNormal} (for the Queen's magic mirror pass).
     *
     * @param planePoint  point on mirror plane
     * @param planeNormal unit normal of mirror plane
     * @return JOML reflection {@link Matrix4f}
     */
    public static Matrix4f computePlanarReflectionMatrix(Vector3f planePoint, Vector3f planeNormal) {
        Vector3f n = new Vector3f(planeNormal).normalize();
        float d = -n.dot(planePoint);
        return new Matrix4f(
                1.0f - 2.0f * n.x * n.x, -2.0f * n.x * n.y, -2.0f * n.x * n.z, 0.0f,
                -2.0f * n.y * n.x, 1.0f - 2.0f * n.y * n.y, -2.0f * n.y * n.z, 0.0f,
                -2.0f * n.z * n.x, -2.0f * n.z * n.y, 1.0f - 2.0f * n.z * n.z, 0.0f,
                -2.0f * d * n.x, -2.0f * d * n.y, -2.0f * d * n.z, 1.0f
        );
    }

    /** Captures serializable value snapshot of camera + world objects for save slots.
     *
     * @param camera active 3D camera
     * @return immutable {@link Scene3DSnapshot}
     */
    public Scene3DSnapshot captureSnapshot(Camera3D camera) {
        Vector3f cp = camera.getPosition();
        Vector3f ct = camera.getTarget();
        Map<String, float[]> poses = new LinkedHashMap<>();
        for (Node3D n : nodes) {
            Vector3f np = n.getPosition();
            poses.put(n.getId(), new float[]{np.x, np.y, np.z});
        }
        return new Scene3DSnapshot(
                cp.x, cp.y, cp.z,
                ct.x, ct.y, ct.z,
                skydome.getHourOfDay(),
                Collections.unmodifiableMap(poses)
        );
    }

    /** Restores camera pose, skydome hour, and node coordinates from a saved snapshot.
     *
     * @param snap   saved snapshot
     * @param camera camera to restore
     */
    public void restoreSnapshot(Scene3DSnapshot snap, Camera3D camera) {
        Objects.requireNonNull(snap, "snap must not be null");
        Objects.requireNonNull(camera, "camera must not be null");
        camera.setPosition(snap.camX(), snap.camY(), snap.camZ());
        camera.setLookAt(snap.targetX(), snap.targetY(), snap.targetZ());
        skydome.setHourOfDay(snap.hourOfDay());
        for (Node3D n : nodes) {
            float[] p = snap.nodePoses().get(n.getId());
            if (p != null && p.length >= 3) {
                n.setPosition(p[0], p[1], p[2]);
            }
        }
    }

    /** Clears all active 3D meshes and lights cleanly upon switching back to pure 2D scenes. */
    public void reset() {
        nodes.clear();
        lights.clear();
        fogConfig = Fog3DConfig.NONE;
        inspectOverlay.close();
    }

    /**
     * Configures exponential fog settings.
     *
     * @param fogConfig fog settings
     */
    public void setFogConfig(Fog3DConfig fogConfig) {
        this.fogConfig = fogConfig != null ? fogConfig : Fog3DConfig.NONE;
    }

    /**
     * Returns active fog configuration.
     *
     * @return {@link Fog3DConfig}
     */
    public Fog3DConfig getFogConfig() {
        return fogConfig;
    }

    /**
     * Returns skydome controller.
     *
     * @return {@link Skydome3D}
     */
    public Skydome3D getSkydome() {
        return skydome;
    }

    /**
     * Returns modal item inspection controller.
     *
     * @return {@link InspectItemOverlay}
     */
    public InspectItemOverlay getInspectOverlay() {
        return inspectOverlay;
    }
}
