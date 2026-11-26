package de.swfdtf.graphics;

import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Transform2D;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Coordinates interactive 2D stage motion for {@link SceneController}-managed sprites:
 * timed position tweens ({@code move} / {@code move_by}), cubic Bezier path tweens
 * ({@code move_path}), shortest-arc rotation tweens ({@code rotate}), scaling tweens ({@code scale}),
 * continuous WASD movement with optional acceleration/friction inertia ({@code wasd_init} /
 * {@code wasd_stop}), lightweight 2D gravity, jump buffering, coyote-time, and solid/one-way
 * platforms ({@code physics_init} / {@code physics_stop} / {@code solid_platform}), collision masks
 * and circular/rect hitboxes, overlap phase &amp; trigger-zone callbacks, smooth camera follow,
 * and serializable snapshot state.
 */
public final class ObjectMotionController {

    /** Supported easing curves for {@code move}, {@code move_path}, {@code rotate}, and {@code scale} tweens. */
    public static final Set<String> SUPPORTED_EASINGS = Set.of(
            "linear", "ease_in", "ease_out", "ease_in_out"
    );

    /** Coyote-time grace window after walking off a platform edge in seconds. */
    public static final float COYOTE_TIME_SECONDS = 0.10f;

    /** Pre-landing jump-input buffer window in seconds. */
    public static final float JUMP_BUFFER_SECONDS = 0.10f;

    private static final float LOGICAL_WIDTH = RenderPipeline2D.DEFAULT_LOGICAL_WIDTH;
    private static final float LOGICAL_HEIGHT = RenderPipeline2D.DEFAULT_LOGICAL_HEIGHT;
    private static final float FALLBACK_HITBOX_SIZE = 64.0f;

    /**
     * Frame-level directional and jump input passed to {@link #update(double, MotionInput)}.
     *
     * @param up          whether upward input (W / ArrowUp) is held
     * @param down        whether downward input (S / ArrowDown) is held
     * @param left        whether leftward input (A / ArrowLeft) is held
     * @param right       whether rightward input (D / ArrowRight) is held
     * @param jumpPressed whether jump (Space / W) transitioned down this frame
     */
    public record MotionInput(
            boolean up,
            boolean down,
            boolean left,
            boolean right,
            boolean jumpPressed
    ) {
        /** Neutral zero-input state used when no hardware input is sampled. */
        public static final MotionInput NONE = new MotionInput(false, false, false, false, false);
    }

    /**
     * Serializable snapshot of a WASD binding for save/load persistence.
     *
     * @param tag               sprite tag
     * @param speedPxPerSec     speed in pixels/sec
     * @param minX              left clamp
     * @param minY              top clamp
     * @param maxX              right clamp
     * @param maxY              bottom clamp
     * @param hasBounds         whether clamping bounds are active
     * @param normalizeDiagonal whether diagonals are normalized
     * @param friction          release damping rate
     * @param acceleration      acceleration ramp rate
     * @param velX              current horizontal velocity
     * @param velY              current vertical velocity
     */
    public record SavedWasdBinding(
            String tag,
            float speedPxPerSec,
            float minX,
            float minY,
            float maxX,
            float maxY,
            boolean hasBounds,
            boolean normalizeDiagonal,
            float friction,
            float acceleration,
            float velX,
            float velY
    ) {
    }

    /**
     * Serializable snapshot of a 2D physics body for save/load persistence.
     *
     * @param tag               sprite tag
     * @param gravityPxPerSec2  gravity acceleration
     * @param jumpForcePxPerSec jump velocity
     * @param groundY           ground plane Y
     * @param velocityY         current vertical velocity
     * @param grounded          whether currently grounded
     * @param coyoteTimer       remaining coyote-time window
     * @param jumpBufferTimer   remaining jump-buffer window
     */
    public record SavedPhysicsBody(
            String tag,
            float gravityPxPerSec2,
            float jumpForcePxPerSec,
            float groundY,
            float velocityY,
            boolean grounded,
            float coyoteTimer,
            float jumpBufferTimer
    ) {
    }

    /**
     * Serializable snapshot of an {@code on_collide} trigger.
     *
     * @param tagA          first tag
     * @param tagB          second tag
     * @param targetLabel   jump label
     * @param wasOverlapping previous overlap state
     */
    public record SavedCollisionTrigger(
            String tagA,
            String tagB,
            String targetLabel,
            boolean wasOverlapping
    ) {
    }

    /**
     * Serializable snapshot of an {@code on_overlap} trigger.
     *
     * @param tagA           first tag
     * @param tagB           second tag
     * @param phase          overlap phase ({@code enter}, {@code stay}, {@code exit})
     * @param targetLabel    subroutine label
     * @param wasOverlapping previous overlap state
     */
    public record SavedOverlapTrigger(
            String tagA,
            String tagB,
            String phase,
            String targetLabel,
            boolean wasOverlapping
    ) {
    }

    /**
     * Serializable snapshot of a {@code solid_platform} registration.
     *
     * @param tag    platform sprite tag
     * @param oneWay whether platform is one-way from below
     */
    public record SavedSolidPlatform(String tag, boolean oneWay) {
    }

    /**
     * Serializable snapshot of a {@code trigger_zone} registration.
     *
     * @param zoneId      zone ID
     * @param x           zone X
     * @param y           zone Y
     * @param width       zone width
     * @param height      zone height
     * @param callAction  {@code true} for {@code call}, {@code false} for {@code jump}
     * @param targetLabel target label
     * @param wasInside   previous entry state
     */
    public record SavedTriggerZone(
            String zoneId,
            float x,
            float y,
            float width,
            float height,
            boolean callAction,
            String targetLabel,
            boolean wasInside
    ) {
    }

    /**
     * Serializable snapshot of a collision mask configuration.
     *
     * @param tag          sprite tag
     * @param layer        own collision layer
     * @param collidesWith accepted target layers
     */
    public record SavedCollisionMask(String tag, String layer, List<String> collidesWith) {
        /** Copies mask targets defensively. */
        public SavedCollisionMask {
            collidesWith = collidesWith != null ? List.copyOf(collidesWith) : List.of();
        }
    }

    /**
     * Serializable snapshot of a custom hitbox shape.
     *
     * @param tag    sprite tag
     * @param shape  {@code "circle"} or {@code "rect"}
     * @param param1 radius or width
     * @param param2 zero or height
     */
    public record SavedHitbox(String tag, String shape, float param1, float param2) {
    }

    /**
     * Immutable serializable snapshot of all active motion, physics, collision, and camera state.
     *
     * @param wasdBindings      active WASD bindings
     * @param physicsBodies     active physics bodies
     * @param collisionTriggers registered collision triggers
     * @param overlapTriggers   registered overlap phase triggers
     * @param solidPlatforms    registered solid platforms
     * @param triggerZones      registered trigger zones
     * @param collisionMasks    configured collision masks
     * @param hitboxes          configured custom hitboxes
     * @param cameraFollowTag   camera follow target tag (nullable)
     * @param cameraSmooth      camera smooth rate
     * @param cameraOffsetX     current camera offset X
     * @param cameraOffsetY     current camera offset Y
     */
    public record MotionSnapshot(
            List<SavedWasdBinding> wasdBindings,
            List<SavedPhysicsBody> physicsBodies,
            List<SavedCollisionTrigger> collisionTriggers,
            List<SavedOverlapTrigger> overlapTriggers,
            List<SavedSolidPlatform> solidPlatforms,
            List<SavedTriggerZone> triggerZones,
            List<SavedCollisionMask> collisionMasks,
            List<SavedHitbox> hitboxes,
            String cameraFollowTag,
            float cameraSmooth,
            float cameraOffsetX,
            float cameraOffsetY
    ) {
        /** Normalizes null lists to empty immutable lists. */
        public MotionSnapshot {
            wasdBindings = wasdBindings != null ? List.copyOf(wasdBindings) : List.of();
            physicsBodies = physicsBodies != null ? List.copyOf(physicsBodies) : List.of();
            collisionTriggers = collisionTriggers != null ? List.copyOf(collisionTriggers) : List.of();
            overlapTriggers = overlapTriggers != null ? List.copyOf(overlapTriggers) : List.of();
            solidPlatforms = solidPlatforms != null ? List.copyOf(solidPlatforms) : List.of();
            triggerZones = triggerZones != null ? List.copyOf(triggerZones) : List.of();
            collisionMasks = collisionMasks != null ? List.copyOf(collisionMasks) : List.of();
            hitboxes = hitboxes != null ? List.copyOf(hitboxes) : List.of();
        }
    }

    private static final class MoveTween {
        private final String tag;
        private float startX;
        private float startY;
        private float endX;
        private float endY;
        private final boolean relative;
        private final boolean bezier;
        private final float rawX;
        private final float rawY;
        private final float ctrl1X;
        private final float ctrl1Y;
        private final float ctrl2X;
        private final float ctrl2Y;
        private final double durationSeconds;
        private final String easing;
        private boolean initializedStart;
        private double elapsed;

        private MoveTween(
                String tag,
                float rawX,
                float rawY,
                boolean relative,
                double durationSeconds,
                String easing
        ) {
            this.tag = tag;
            this.rawX = rawX;
            this.rawY = rawY;
            this.relative = relative;
            this.bezier = false;
            this.ctrl1X = 0.0f;
            this.ctrl1Y = 0.0f;
            this.ctrl2X = 0.0f;
            this.ctrl2Y = 0.0f;
            this.durationSeconds = Math.max(0.0, durationSeconds);
            this.easing = normalizeEasing(easing);
        }

        private MoveTween(
                String tag,
                float c1x,
                float c1y,
                float c2x,
                float c2y,
                float endX,
                float endY,
                double durationSeconds,
                String easing
        ) {
            this.tag = tag;
            this.rawX = endX;
            this.rawY = endY;
            this.relative = false;
            this.bezier = true;
            this.ctrl1X = c1x;
            this.ctrl1Y = c1y;
            this.ctrl2X = c2x;
            this.ctrl2Y = c2y;
            this.durationSeconds = Math.max(0.0, durationSeconds);
            this.easing = normalizeEasing(easing);
        }
    }

    private static final class RotateTween {
        private final String tag;
        private float startDegrees;
        private float shortestDeltaDegrees;
        private final float targetDegrees;
        private final double durationSeconds;
        private final String easing;
        private boolean initializedStart;
        private double elapsed;

        private RotateTween(String tag, float targetDegrees, double durationSeconds, String easing) {
            this.tag = tag;
            this.targetDegrees = targetDegrees;
            this.durationSeconds = Math.max(0.0, durationSeconds);
            this.easing = normalizeEasing(easing);
        }
    }

    private static final class ScaleTween {
        private final String tag;
        private float startXZoom;
        private float startYZoom;
        private final float targetXZoom;
        private final float targetYZoom;
        private final double durationSeconds;
        private final String easing;
        private boolean initializedStart;
        private double elapsed;

        private ScaleTween(String tag, float targetXZoom, float targetYZoom, double durationSeconds, String easing) {
            this.tag = tag;
            this.targetXZoom = targetXZoom;
            this.targetYZoom = targetYZoom;
            this.durationSeconds = Math.max(0.0, durationSeconds);
            this.easing = normalizeEasing(easing);
        }
    }

    private static final class WasdBinding {
        private final float speedPxPerSec;
        private final float minX;
        private final float minY;
        private final float maxX;
        private final float maxY;
        private final boolean hasBounds;
        private final boolean normalizeDiagonal;
        private final float friction;
        private final float acceleration;
        private float velX;
        private float velY;

        private WasdBinding(
                float speedPxPerSec,
                Float minX,
                Float minY,
                Float maxX,
                Float maxY,
                boolean normalizeDiagonal,
                float friction,
                float acceleration
        ) {
            this.speedPxPerSec = Math.max(1.0f, speedPxPerSec);
            this.hasBounds = minX != null && minY != null && maxX != null && maxY != null;
            this.minX = minX != null ? minX : 0.0f;
            this.minY = minY != null ? minY : 0.0f;
            this.maxX = maxX != null ? maxX : LOGICAL_WIDTH;
            this.maxY = maxY != null ? maxY : LOGICAL_HEIGHT;
            this.normalizeDiagonal = normalizeDiagonal;
            this.friction = Float.isFinite(friction) && friction > 0.0f ? friction : 0.0f;
            this.acceleration = Float.isFinite(acceleration) && acceleration > 0.0f ? acceleration : 0.0f;
        }
    }

    private static final class PhysicsBody {
        private final float gravityPxPerSec2;
        private final float jumpForcePxPerSec;
        private final float groundY;
        private float velocityY = 0.0f;
        private boolean grounded = false;
        private float coyoteTimer = 0.0f;
        private float jumpBufferTimer = 0.0f;

        private PhysicsBody(float gravityPxPerSec2, float jumpForcePxPerSec, float groundY) {
            this.gravityPxPerSec2 = Math.max(0.0f, gravityPxPerSec2);
            this.jumpForcePxPerSec = Math.max(0.0f, jumpForcePxPerSec);
            this.groundY = groundY;
        }
    }

    private static final class CollisionTrigger {
        private final String tagA;
        private final String tagB;
        private final String targetLabel;
        private boolean wasOverlapping = false;

        private CollisionTrigger(String tagA, String tagB, String targetLabel) {
            this.tagA = tagA;
            this.tagB = tagB;
            this.targetLabel = targetLabel;
        }
    }

    private static final class OverlapTrigger {
        private final String tagA;
        private final String tagB;
        private final String phase;
        private final String targetLabel;
        private boolean wasOverlapping = false;

        private OverlapTrigger(String tagA, String tagB, String phase, String targetLabel) {
            this.tagA = tagA;
            this.tagB = tagB;
            this.phase = phase;
            this.targetLabel = targetLabel;
        }
    }

    private record CollisionMask(String layer, Set<String> collidesWith) {
    }

    private enum HitboxShape {
        CIRCLE,
        RECT
    }

    private record HitboxSpec(HitboxShape shape, float param1, float param2) {
    }

    private record SolidPlatform(String tag, boolean oneWay) {
    }

    private static final class TriggerZone {
        private final String zoneId;
        private final Rect2f rect;
        private final boolean callAction;
        private final String targetLabel;
        private boolean wasInside = false;

        private TriggerZone(String zoneId, Rect2f rect, boolean callAction, String targetLabel) {
            this.zoneId = zoneId;
            this.rect = rect;
            this.callAction = callAction;
            this.targetLabel = targetLabel;
        }
    }

    private final Map<String, SpriteNode> activeSprites;
    private final Map<String, MoveTween> activeTweens = new LinkedHashMap<>();
    private final Map<String, RotateTween> activeRotateTweens = new LinkedHashMap<>();
    private final Map<String, ScaleTween> activeScaleTweens = new LinkedHashMap<>();
    private final Map<String, WasdBinding> wasdBindings = new LinkedHashMap<>();
    private final Map<String, PhysicsBody> physicsBodies = new LinkedHashMap<>();
    private final List<CollisionTrigger> collisionTriggers = new ArrayList<>();
    private final List<OverlapTrigger> overlapTriggers = new ArrayList<>();
    private final Map<String, SolidPlatform> solidPlatforms = new LinkedHashMap<>();
    private final Map<String, TriggerZone> triggerZones = new LinkedHashMap<>();
    private final Map<String, CollisionMask> collisionMasks = new LinkedHashMap<>();
    private final Map<String, HitboxSpec> customHitboxes = new LinkedHashMap<>();

    private String cameraFollowTag;
    private float cameraSmoothFactor = 0.0f;
    private float cameraOffsetX = 0.0f;
    private float cameraOffsetY = 0.0f;

    private Consumer<String> collisionJumpListener;
    private Consumer<String> subroutineCallListener;
    private Consumer<String> tweenCompleteListener;

    /**
     * Creates an {@code ObjectMotionController} backed by {@code activeSprites}.
     *
     * @param activeSprites live stage sprite lookup map owned by {@link SceneController}
     */
    public ObjectMotionController(Map<String, SpriteNode> activeSprites) {
        this.activeSprites = Objects.requireNonNull(activeSprites, "activeSprites must not be null");
    }

    /**
     * Starts an absolute or relative linear/eased position tween for {@code tag}.
     *
     * @param tag             semantic sprite tag
     * @param x               target X position or relative delta X
     * @param y               target Y position or relative delta Y
     * @param relative        {@code true} for {@code move_by}, {@code false} for {@code move ... to}
     * @param durationSeconds duration in seconds ({@code >= 0})
     * @param easing          easing curve name
     */
    public void startMove(
            String tag,
            float x,
            float y,
            boolean relative,
            double durationSeconds,
            String easing
    ) {
        Objects.requireNonNull(tag, "tag must not be null");
        MoveTween tween = new MoveTween(tag, x, y, relative, durationSeconds, easing);
        SpriteNode sprite = activeSprites.get(tag);
        if (sprite != null) {
            initMoveEndpoints(tween, sprite);
            if (tween.durationSeconds <= 0.0) {
                applyAbsolutePos(sprite, tween.endX, tween.endY);
                notifyTweenComplete(tag);
                return;
            }
        }
        activeTweens.put(tag, tween);
    }

    /**
     * Starts a cubic Bezier path tween from the sprite's current position through {@code (x1, y1)}
     * and {@code (x2, y2)} to {@code (x3, y3)}.
     *
     * @param tag             semantic sprite tag
     * @param x1              control point 1 X
     * @param y1              control point 1 Y
     * @param x2              control point 2 X
     * @param y2              control point 2 Y
     * @param x3              end point X
     * @param y3              end point Y
     * @param durationSeconds duration in seconds ({@code >= 0})
     * @param easing          easing curve name
     */
    public void startMovePath(
            String tag,
            float x1,
            float y1,
            float x2,
            float y2,
            float x3,
            float y3,
            double durationSeconds,
            String easing
    ) {
        Objects.requireNonNull(tag, "tag must not be null");
        MoveTween tween = new MoveTween(tag, x1, y1, x2, y2, x3, y3, durationSeconds, easing);
        SpriteNode sprite = activeSprites.get(tag);
        if (sprite != null) {
            initMoveEndpoints(tween, sprite);
            if (tween.durationSeconds <= 0.0) {
                applyAbsolutePos(sprite, tween.endX, tween.endY);
                notifyTweenComplete(tag);
                return;
            }
        }
        activeTweens.put(tag, tween);
    }

    /**
     * Starts a shortest-arc rotation tween toward {@code targetDegrees}.
     *
     * @param tag             semantic sprite tag
     * @param targetDegrees   target rotation in degrees
     * @param durationSeconds duration in seconds ({@code >= 0})
     * @param easing          easing curve name
     */
    public void startRotate(String tag, float targetDegrees, double durationSeconds, String easing) {
        Objects.requireNonNull(tag, "tag must not be null");
        if (!Float.isFinite(targetDegrees)) {
            return;
        }
        RotateTween tween = new RotateTween(tag, targetDegrees, durationSeconds, easing);
        SpriteNode sprite = activeSprites.get(tag);
        if (sprite != null) {
            initRotateEndpoints(tween, sprite);
            if (tween.durationSeconds <= 0.0) {
                sprite.getTransform().setRotate(tween.startDegrees + tween.shortestDeltaDegrees);
                notifyTweenComplete(tag);
                return;
            }
        }
        activeRotateTweens.put(tag, tween);
    }

    /**
     * Starts a non-uniform scale tween toward {@code (targetXZoom, targetYZoom)}.
     *
     * @param tag             semantic sprite tag
     * @param targetXZoom     target horizontal zoom factor
     * @param targetYZoom     target vertical zoom factor
     * @param durationSeconds duration in seconds ({@code >= 0})
     * @param easing          easing curve name
     */
    public void startScale(
            String tag,
            float targetXZoom,
            float targetYZoom,
            double durationSeconds,
            String easing
    ) {
        Objects.requireNonNull(tag, "tag must not be null");
        if (!Float.isFinite(targetXZoom) || !Float.isFinite(targetYZoom)) {
            return;
        }
        ScaleTween tween = new ScaleTween(tag, targetXZoom, targetYZoom, durationSeconds, easing);
        SpriteNode sprite = activeSprites.get(tag);
        if (sprite != null) {
            initScaleEndpoints(tween, sprite);
            if (tween.durationSeconds <= 0.0) {
                sprite.getTransform().setXzoom(targetXZoom).setYzoom(targetYZoom);
                notifyTweenComplete(tag);
                return;
            }
        }
        activeScaleTweens.put(tag, tween);
    }

    /**
     * Returns whether any move, path, rotate, or scale tween is currently running for {@code tag}.
     *
     * @param tag sprite tag
     * @return {@code true} if an active tween exists for {@code tag}
     */
    public boolean hasActiveTween(String tag) {
        if (tag == null) {
            return false;
        }
        return activeTweens.containsKey(tag)
                || activeRotateTweens.containsKey(tag)
                || activeScaleTweens.containsKey(tag);
    }

    /**
     * Enables WASD / arrow-key movement for {@code tag} without inertia.
     *
     * @param tag               semantic sprite tag
     * @param speedPxPerSec     movement speed in logical pixels per second
     * @param minX              optional left bound (nullable)
     * @param minY              optional top bound (nullable)
     * @param maxX              optional right bound (nullable)
     * @param maxY              optional bottom bound (nullable)
     * @param normalizeDiagonal whether diagonal movement is scaled by {@code 1 / sqrt(2)}
     */
    public void enableWasd(
            String tag,
            float speedPxPerSec,
            Float minX,
            Float minY,
            Float maxX,
            Float maxY,
            boolean normalizeDiagonal
    ) {
        enableWasd(tag, speedPxPerSec, minX, minY, maxX, maxY, normalizeDiagonal, 0.0f, 0.0f);
    }

    /**
     * Enables WASD / arrow-key movement for {@code tag} with optional acceleration and release friction.
     *
     * @param tag               semantic sprite tag
     * @param speedPxPerSec     maximum movement speed in logical pixels per second
     * @param minX              optional left bound (nullable)
     * @param minY              optional top bound (nullable)
     * @param maxX              optional right bound (nullable)
     * @param maxY              optional bottom bound (nullable)
     * @param normalizeDiagonal whether diagonal movement is scaled by {@code 1 / sqrt(2)}
     * @param friction          exponential decay rate on release ({@code 0} = instant stop)
     * @param acceleration      exponential acceleration rate when held ({@code 0} = instant full speed)
     */
    public void enableWasd(
            String tag,
            float speedPxPerSec,
            Float minX,
            Float minY,
            Float maxX,
            Float maxY,
            boolean normalizeDiagonal,
            float friction,
            float acceleration
    ) {
        Objects.requireNonNull(tag, "tag must not be null");
        WasdBinding binding = new WasdBinding(
                speedPxPerSec, minX, minY, maxX, maxY, normalizeDiagonal, friction, acceleration);
        wasdBindings.put(tag, binding);
        SpriteNode sprite = activeSprites.get(tag);
        if (sprite != null) {
            normalizeToAbsoluteCoords(sprite);
        }
    }

    /**
     * Disables WASD control for {@code tag}, or all sprites if {@code tag} is null or blank.
     *
     * @param tag target sprite tag, or null/blank to clear all WASD bindings
     */
    public void disableWasd(String tag) {
        if (tag == null || tag.isBlank()) {
            wasdBindings.clear();
        } else {
            wasdBindings.remove(tag);
        }
    }

    /**
     * Enables 2D gravity and jump physics for {@code tag}.
     *
     * @param tag               semantic sprite tag
     * @param gravityPxPerSec2  downward acceleration in logical pixels/sec²
     * @param jumpForcePxPerSec upward initial velocity when jumping from ground
     * @param groundY           Y coordinate of the floor plane in logical coordinates
     */
    public void enablePhysics(String tag, float gravityPxPerSec2, float jumpForcePxPerSec, float groundY) {
        Objects.requireNonNull(tag, "tag must not be null");
        PhysicsBody body = new PhysicsBody(gravityPxPerSec2, jumpForcePxPerSec, groundY);
        SpriteNode sprite = activeSprites.get(tag);
        if (sprite != null) {
            normalizeToAbsoluteCoords(sprite);
            if (sprite.getTransform().getYpos() >= groundY) {
                sprite.getTransform().setYpos(groundY);
                body.grounded = true;
            }
        }
        physicsBodies.put(tag, body);
    }

    /**
     * Disables 2D physics for {@code tag}, or all sprites if {@code tag} is null or blank.
     *
     * @param tag target sprite tag, or null/blank to clear all physics bodies
     */
    public void disablePhysics(String tag) {
        if (tag == null || tag.isBlank()) {
            physicsBodies.clear();
        } else {
            physicsBodies.remove(tag);
        }
    }

    /**
     * Registers or updates a collision layer mask for {@code tag}.
     *
     * @param tag          sprite tag
     * @param layer        layer name this sprite belongs to
     * @param collidesWith layer names accepted for collisions (empty matches any layer)
     */
    public void setCollisionMask(String tag, String layer, List<String> collidesWith) {
        Objects.requireNonNull(tag, "tag must not be null");
        Objects.requireNonNull(layer, "layer must not be null");
        Set<String> targets = collidesWith != null && !collidesWith.isEmpty()
                ? Collections.unmodifiableSet(new LinkedHashSet<>(collidesWith))
                : Set.of();
        collisionMasks.put(tag, new CollisionMask(layer, targets));
    }

    /**
     * Configures a circular collision hitbox centered on {@code tag}'s resolved bounds.
     *
     * @param tag    sprite tag
     * @param radius positive circle radius in logical pixels
     */
    public void setCircularHitbox(String tag, float radius) {
        Objects.requireNonNull(tag, "tag must not be null");
        if (!Float.isFinite(radius) || radius <= 0.0f) {
            throw new IllegalArgumentException("Circular hitbox radius must be positive, got: " + radius);
        }
        customHitboxes.put(tag, new HitboxSpec(HitboxShape.CIRCLE, radius, 0.0f));
    }

    /**
     * Configures an explicit centered rectangular collision hitbox for {@code tag}.
     *
     * @param tag    sprite tag
     * @param width  positive hitbox width
     * @param height positive hitbox height
     */
    public void setRectHitbox(String tag, float width, float height) {
        Objects.requireNonNull(tag, "tag must not be null");
        if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0.0f || height <= 0.0f) {
            throw new IllegalArgumentException("Rect hitbox dimensions must be positive: " + width + "x" + height);
        }
        customHitboxes.put(tag, new HitboxSpec(HitboxShape.RECT, width, height));
    }

    /**
     * Marks a sprite tag as a solid or one-way collision platform for {@code physics_init} bodies.
     *
     * @param tag    platform sprite tag
     * @param oneWay {@code true} to allow upward passage from below while catching downward landings
     */
    public void registerSolidPlatform(String tag, boolean oneWay) {
        Objects.requireNonNull(tag, "tag must not be null");
        solidPlatforms.put(tag, new SolidPlatform(tag, oneWay));
    }

    /**
     * Registers an AABB/hitbox collision trigger that jumps to {@code targetLabel} on initial overlap
     * between {@code tagA} and {@code tagB}.
     *
     * @param tagA        first sprite tag
     * @param tagB        second sprite tag
     * @param targetLabel label to jump to on collision
     */
    public void registerCollisionTrigger(String tagA, String tagB, String targetLabel) {
        Objects.requireNonNull(tagA, "tagA must not be null");
        Objects.requireNonNull(tagB, "tagB must not be null");
        Objects.requireNonNull(targetLabel, "targetLabel must not be null");
        collisionTriggers.removeIf(t ->
                ((t.tagA.equals(tagA) && t.tagB.equals(tagB))
                        || (t.tagA.equals(tagB) && t.tagB.equals(tagA)))
                        && t.targetLabel.equals(targetLabel));
        collisionTriggers.add(new CollisionTrigger(tagA, tagB, targetLabel));
    }

    /**
     * Registers an overlap phase callback ({@code enter}, {@code stay}, or {@code exit}) between two sprites.
     *
     * @param tagA        first sprite tag
     * @param tagB        second sprite tag
     * @param phase       overlap phase ({@code "enter"}, {@code "stay"}, or {@code "exit"})
     * @param targetLabel subroutine label to invoke
     */
    public void registerOverlapTrigger(String tagA, String tagB, String phase, String targetLabel) {
        Objects.requireNonNull(tagA, "tagA must not be null");
        Objects.requireNonNull(tagB, "tagB must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        Objects.requireNonNull(targetLabel, "targetLabel must not be null");
        String normPhase = phase.trim().toLowerCase(Locale.ROOT);
        overlapTriggers.removeIf(t ->
                t.tagA.equals(tagA) && t.tagB.equals(tagB)
                        && t.phase.equals(normPhase) && t.targetLabel.equals(targetLabel));
        overlapTriggers.add(new OverlapTrigger(tagA, tagB, normPhase, targetLabel));
    }

    /**
     * Registers a rectangular trigger zone invoking or jumping to {@code targetLabel} on rising-edge entry.
     *
     * @param zoneId      unique trigger zone ID
     * @param x           zone left X
     * @param y           zone top Y
     * @param width       zone width
     * @param height      zone height
     * @param callAction  {@code true} to invoke as subroutine {@code call}, {@code false} for {@code jump}
     * @param targetLabel target label name
     */
    public void registerTriggerZone(
            String zoneId,
            float x,
            float y,
            float width,
            float height,
            boolean callAction,
            String targetLabel
    ) {
        Objects.requireNonNull(zoneId, "zoneId must not be null");
        Objects.requireNonNull(targetLabel, "targetLabel must not be null");
        triggerZones.put(zoneId, new TriggerZone(zoneId, new Rect2f(x, y, width, height), callAction, targetLabel));
    }

    /**
     * Enables smooth camera tracking focused on {@code targetTag}.
     *
     * @param targetTag    sprite tag to track
     * @param smoothFactor smoothing rate ({@code >= 0}; negative values clamped cleanly to {@code 0})
     */
    public void enableCameraFollow(String targetTag, float smoothFactor) {
        Objects.requireNonNull(targetTag, "targetTag must not be null");
        this.cameraFollowTag = targetTag;
        this.cameraSmoothFactor = Float.isFinite(smoothFactor) && smoothFactor > 0.0f ? smoothFactor : 0.0f;
    }

    /** Disables active camera follow tracking and resets offsets to zero. */
    public void disableCameraFollow() {
        this.cameraFollowTag = null;
        this.cameraSmoothFactor = 0.0f;
        this.cameraOffsetX = 0.0f;
        this.cameraOffsetY = 0.0f;
    }

    /**
     * Sets the listener notified with the destination label name whenever an {@code on_collide}
     * or jump {@code trigger_zone} fires.
     *
     * @param listener callback receiving the target label name (nullable)
     */
    public void setCollisionJumpListener(Consumer<String> listener) {
        this.collisionJumpListener = listener;
    }

    /**
     * Sets the listener notified with the subroutine label name for {@code on_overlap} or {@code trigger_zone call}.
     *
     * @param listener callback receiving the subroutine label name (nullable)
     */
    public void setSubroutineCallListener(Consumer<String> listener) {
        this.subroutineCallListener = listener;
    }

    /**
     * Sets the listener notified with the sprite tag whenever a motion tween completes.
     *
     * @param listener callback receiving completed sprite tag (nullable)
     */
    public void setTweenCompleteListener(Consumer<String> listener) {
        this.tweenCompleteListener = listener;
    }

    /** Clears all active tweens, bindings, physics bodies, triggers, masks, hitboxes, and camera follow state. */
    public void clearAll() {
        activeTweens.clear();
        activeRotateTweens.clear();
        activeScaleTweens.clear();
        wasdBindings.clear();
        physicsBodies.clear();
        collisionTriggers.clear();
        overlapTriggers.clear();
        solidPlatforms.clear();
        triggerZones.clear();
        collisionMasks.clear();
        customHitboxes.clear();
        disableCameraFollow();
    }

    /**
     * Captures a serializable {@link MotionSnapshot} of active WASD/physics/trigger/platform/camera state.
     *
     * @return immutable motion snapshot
     */
    public MotionSnapshot snapshotState() {
        List<SavedWasdBinding> savedWasd = new ArrayList<>(wasdBindings.size());
        for (Map.Entry<String, WasdBinding> entry : wasdBindings.entrySet()) {
            WasdBinding b = entry.getValue();
            savedWasd.add(new SavedWasdBinding(
                    entry.getKey(), b.speedPxPerSec, b.minX, b.minY, b.maxX, b.maxY,
                    b.hasBounds, b.normalizeDiagonal, b.friction, b.acceleration, b.velX, b.velY));
        }
        List<SavedPhysicsBody> savedPhysics = new ArrayList<>(physicsBodies.size());
        for (Map.Entry<String, PhysicsBody> entry : physicsBodies.entrySet()) {
            PhysicsBody p = entry.getValue();
            savedPhysics.add(new SavedPhysicsBody(
                    entry.getKey(), p.gravityPxPerSec2, p.jumpForcePxPerSec, p.groundY,
                    p.velocityY, p.grounded, p.coyoteTimer, p.jumpBufferTimer));
        }
        List<SavedCollisionTrigger> savedCollisions = new ArrayList<>(collisionTriggers.size());
        for (CollisionTrigger t : collisionTriggers) {
            savedCollisions.add(new SavedCollisionTrigger(t.tagA, t.tagB, t.targetLabel, t.wasOverlapping));
        }
        List<SavedOverlapTrigger> savedOverlaps = new ArrayList<>(overlapTriggers.size());
        for (OverlapTrigger t : overlapTriggers) {
            savedOverlaps.add(new SavedOverlapTrigger(t.tagA, t.tagB, t.phase, t.targetLabel, t.wasOverlapping));
        }
        List<SavedSolidPlatform> savedPlatforms = new ArrayList<>(solidPlatforms.size());
        for (SolidPlatform p : solidPlatforms.values()) {
            savedPlatforms.add(new SavedSolidPlatform(p.tag(), p.oneWay()));
        }
        List<SavedTriggerZone> savedZones = new ArrayList<>(triggerZones.size());
        for (TriggerZone z : triggerZones.values()) {
            savedZones.add(new SavedTriggerZone(
                    z.zoneId, z.rect.x(), z.rect.y(), z.rect.width(), z.rect.height(),
                    z.callAction, z.targetLabel, z.wasInside));
        }
        List<SavedCollisionMask> savedMasks = new ArrayList<>(collisionMasks.size());
        for (Map.Entry<String, CollisionMask> entry : collisionMasks.entrySet()) {
            savedMasks.add(new SavedCollisionMask(
                    entry.getKey(), entry.getValue().layer(), List.copyOf(entry.getValue().collidesWith())));
        }
        List<SavedHitbox> savedHitboxes = new ArrayList<>(customHitboxes.size());
        for (Map.Entry<String, HitboxSpec> entry : customHitboxes.entrySet()) {
            savedHitboxes.add(new SavedHitbox(
                    entry.getKey(),
                    entry.getValue().shape() == HitboxShape.CIRCLE ? "circle" : "rect",
                    entry.getValue().param1(),
                    entry.getValue().param2()));
        }
        return new MotionSnapshot(
                savedWasd, savedPhysics, savedCollisions, savedOverlaps,
                savedPlatforms, savedZones, savedMasks, savedHitboxes,
                cameraFollowTag, cameraSmoothFactor, cameraOffsetX, cameraOffsetY);
    }

    /**
     * Restores all WASD, physics, collision, platform, hitbox, zone, and camera follow state from {@code snapshot}.
     * Passing {@code null} clears all motion state cleanly (e.g., for legacy saves).
     *
     * @param snapshot saved motion snapshot (nullable)
     */
    public void restoreState(MotionSnapshot snapshot) {
        clearAll();
        if (snapshot == null) {
            return;
        }
        for (SavedWasdBinding w : snapshot.wasdBindings()) {
            WasdBinding binding = new WasdBinding(
                    w.speedPxPerSec(),
                    w.hasBounds() ? w.minX() : null,
                    w.hasBounds() ? w.minY() : null,
                    w.hasBounds() ? w.maxX() : null,
                    w.hasBounds() ? w.maxY() : null,
                    w.normalizeDiagonal(),
                    w.friction(),
                    w.acceleration());
            binding.velX = w.velX();
            binding.velY = w.velY();
            wasdBindings.put(w.tag(), binding);
        }
        for (SavedPhysicsBody p : snapshot.physicsBodies()) {
            PhysicsBody body = new PhysicsBody(p.gravityPxPerSec2(), p.jumpForcePxPerSec(), p.groundY());
            body.velocityY = p.velocityY();
            body.grounded = p.grounded();
            body.coyoteTimer = p.coyoteTimer();
            body.jumpBufferTimer = p.jumpBufferTimer();
            physicsBodies.put(p.tag(), body);
        }
        for (SavedCollisionTrigger c : snapshot.collisionTriggers()) {
            CollisionTrigger trigger = new CollisionTrigger(c.tagA(), c.tagB(), c.targetLabel());
            trigger.wasOverlapping = c.wasOverlapping();
            collisionTriggers.add(trigger);
        }
        for (SavedOverlapTrigger o : snapshot.overlapTriggers()) {
            OverlapTrigger trigger = new OverlapTrigger(o.tagA(), o.tagB(), o.phase(), o.targetLabel());
            trigger.wasOverlapping = o.wasOverlapping();
            overlapTriggers.add(trigger);
        }
        for (SavedSolidPlatform sp : snapshot.solidPlatforms()) {
            solidPlatforms.put(sp.tag(), new SolidPlatform(sp.tag(), sp.oneWay()));
        }
        for (SavedTriggerZone tz : snapshot.triggerZones()) {
            TriggerZone zone = new TriggerZone(
                    tz.zoneId(), new Rect2f(tz.x(), tz.y(), tz.width(), tz.height()),
                    tz.callAction(), tz.targetLabel());
            zone.wasInside = tz.wasInside();
            triggerZones.put(tz.zoneId(), zone);
        }
        for (SavedCollisionMask m : snapshot.collisionMasks()) {
            setCollisionMask(m.tag(), m.layer(), m.collidesWith());
        }
        for (SavedHitbox h : snapshot.hitboxes()) {
            if ("circle".equalsIgnoreCase(h.shape()) && h.param1() > 0.0f) {
                setCircularHitbox(h.tag(), h.param1());
            } else if ("rect".equalsIgnoreCase(h.shape()) && h.param1() > 0.0f && h.param2() > 0.0f) {
                setRectHitbox(h.tag(), h.param1(), h.param2());
            }
        }
        this.cameraFollowTag = snapshot.cameraFollowTag();
        this.cameraSmoothFactor = snapshot.cameraSmooth();
        this.cameraOffsetX = snapshot.cameraOffsetX();
        this.cameraOffsetY = snapshot.cameraOffsetY();
    }

    /**
     * Advances tweens, WASD input movement, 2D physics, camera follow, and collision/zone triggers
     * by {@code dt} seconds.
     *
     * @param dt    frame delta in seconds
     * @param input frame motion input (defaults to {@link MotionInput#NONE} if null)
     * @return immutable list of target label names triggered by new collisions this step
     */
    public List<String> update(double dt, MotionInput input) {
        if (dt <= 0.0 || !Double.isFinite(dt)) {
            return List.of();
        }
        MotionInput safeInput = input != null ? input : MotionInput.NONE;
        updateTweens(dt);
        updateWasd(dt, safeInput);
        updatePhysics(dt, safeInput);
        updateCameraFollow(dt);
        return checkCollisions();
    }

    private void updateTweens(double dt) {
        List<String> completedTags = null;

        Iterator<Map.Entry<String, MoveTween>> moveIt = activeTweens.entrySet().iterator();
        while (moveIt.hasNext()) {
            MoveTween tween = moveIt.next().getValue();
            SpriteNode sprite = activeSprites.get(tween.tag);
            if (sprite == null) {
                continue;
            }
            if (!tween.initializedStart) {
                initMoveEndpoints(tween, sprite);
            }
            if (tween.durationSeconds <= 0.0) {
                applyAbsolutePos(sprite, tween.endX, tween.endY);
                moveIt.remove();
                completedTags = recordCompletedTag(completedTags, tween.tag);
                continue;
            }
            tween.elapsed += dt;
            double rawT = Math.min(1.0, tween.elapsed / tween.durationSeconds);
            float t = (float) applyEasing(tween.easing, rawT);
            float nx;
            float ny;
            if (tween.bezier) {
                float u = 1.0f - t;
                float u2 = u * u;
                float t2 = t * t;
                nx = u2 * u * tween.startX
                        + 3.0f * u2 * t * tween.ctrl1X
                        + 3.0f * u * t2 * tween.ctrl2X
                        + t2 * t * tween.endX;
                ny = u2 * u * tween.startY
                        + 3.0f * u2 * t * tween.ctrl1Y
                        + 3.0f * u * t2 * tween.ctrl2Y
                        + t2 * t * tween.endY;
            } else {
                nx = tween.startX + (tween.endX - tween.startX) * t;
                ny = tween.startY + (tween.endY - tween.startY) * t;
            }
            applyAbsolutePos(sprite, nx, ny);
            if (rawT >= 1.0) {
                moveIt.remove();
                completedTags = recordCompletedTag(completedTags, tween.tag);
            }
        }

        Iterator<Map.Entry<String, RotateTween>> rotIt = activeRotateTweens.entrySet().iterator();
        while (rotIt.hasNext()) {
            RotateTween tween = rotIt.next().getValue();
            SpriteNode sprite = activeSprites.get(tween.tag);
            if (sprite == null) {
                continue;
            }
            if (!tween.initializedStart) {
                initRotateEndpoints(tween, sprite);
            }
            if (tween.durationSeconds <= 0.0) {
                sprite.getTransform().setRotate(tween.startDegrees + tween.shortestDeltaDegrees);
                rotIt.remove();
                completedTags = recordCompletedTag(completedTags, tween.tag);
                continue;
            }
            tween.elapsed += dt;
            double rawT = Math.min(1.0, tween.elapsed / tween.durationSeconds);
            float t = (float) applyEasing(tween.easing, rawT);
            float angle = tween.startDegrees + tween.shortestDeltaDegrees * t;
            if (Float.isFinite(angle)) {
                sprite.getTransform().setRotate(angle);
            }
            if (rawT >= 1.0) {
                rotIt.remove();
                completedTags = recordCompletedTag(completedTags, tween.tag);
            }
        }

        Iterator<Map.Entry<String, ScaleTween>> scaleIt = activeScaleTweens.entrySet().iterator();
        while (scaleIt.hasNext()) {
            ScaleTween tween = scaleIt.next().getValue();
            SpriteNode sprite = activeSprites.get(tween.tag);
            if (sprite == null) {
                continue;
            }
            if (!tween.initializedStart) {
                initScaleEndpoints(tween, sprite);
            }
            if (tween.durationSeconds <= 0.0) {
                sprite.getTransform().setXzoom(tween.targetXZoom).setYzoom(tween.targetYZoom);
                scaleIt.remove();
                completedTags = recordCompletedTag(completedTags, tween.tag);
                continue;
            }
            tween.elapsed += dt;
            double rawT = Math.min(1.0, tween.elapsed / tween.durationSeconds);
            float t = (float) applyEasing(tween.easing, rawT);
            float zx = tween.startXZoom + (tween.targetXZoom - tween.startXZoom) * t;
            float zy = tween.startYZoom + (tween.targetYZoom - tween.startYZoom) * t;
            if (Float.isFinite(zx)) {
                sprite.getTransform().setXzoom(zx);
            }
            if (Float.isFinite(zy)) {
                sprite.getTransform().setYzoom(zy);
            }
            if (rawT >= 1.0) {
                scaleIt.remove();
                completedTags = recordCompletedTag(completedTags, tween.tag);
            }
        }

        if (completedTags != null) {
            for (String tag : completedTags) {
                notifyTweenComplete(tag);
            }
        }
    }

    private List<String> recordCompletedTag(List<String> list, String tag) {
        if (hasActiveTween(tag)) {
            return list;
        }
        List<String> out = list != null ? list : new ArrayList<>(2);
        out.add(tag);
        return out;
    }

    private void notifyTweenComplete(String tag) {
        if (tweenCompleteListener != null && !hasActiveTween(tag)) {
            tweenCompleteListener.accept(tag);
        }
    }

    private void updateWasd(double dt, MotionInput input) {
        float dx = (input.right() ? 1.0f : 0.0f) - (input.left() ? 1.0f : 0.0f);
        float dy = (input.down() ? 1.0f : 0.0f) - (input.up() ? 1.0f : 0.0f);
        float dtF = (float) dt;

        for (Map.Entry<String, WasdBinding> entry : wasdBindings.entrySet()) {
            SpriteNode sprite = activeSprites.get(entry.getKey());
            if (sprite == null) {
                continue;
            }
            WasdBinding binding = entry.getValue();
            normalizeToAbsoluteCoords(sprite);
            boolean hasPhysics = physicsBodies.containsKey(entry.getKey());
            float stepDx = dx;
            float stepDy = hasPhysics ? 0.0f : dy;
            if (binding.normalizeDiagonal && stepDx != 0.0f && stepDy != 0.0f) {
                float invSqrt2 = (float) (1.0 / Math.sqrt(2.0));
                stepDx *= invSqrt2;
                stepDy *= invSqrt2;
            }

            boolean inertiaMode = binding.friction > 0.0f || binding.acceleration > 0.0f;
            if (!inertiaMode) {
                if (stepDx == 0.0f && stepDy == 0.0f) {
                    continue;
                }
                binding.velX = stepDx * binding.speedPxPerSec;
                binding.velY = stepDy * binding.speedPxPerSec;
            } else {
                float targetVx = stepDx * binding.speedPxPerSec;
                float targetVy = stepDy * binding.speedPxPerSec;
                binding.velX = stepAxisVelocity(binding.velX, targetVx, stepDx != 0.0f,
                        binding.acceleration, binding.friction, dtF);
                binding.velY = hasPhysics ? 0.0f : stepAxisVelocity(
                        binding.velY, targetVy, stepDy != 0.0f, binding.acceleration, binding.friction, dtF);
                if (binding.velX == 0.0f && binding.velY == 0.0f) {
                    continue;
                }
            }

            Transform2D tf = sprite.getTransform();
            float nextX = tf.getXpos() + binding.velX * dtF;
            float nextY = tf.getYpos() + binding.velY * dtF;
            if (binding.hasBounds) {
                if (nextX < binding.minX) {
                    nextX = binding.minX;
                    binding.velX = 0.0f;
                } else if (nextX > binding.maxX) {
                    nextX = binding.maxX;
                    binding.velX = 0.0f;
                }
                if (nextY < binding.minY) {
                    nextY = binding.minY;
                    binding.velY = 0.0f;
                } else if (nextY > binding.maxY) {
                    nextY = binding.maxY;
                    binding.velY = 0.0f;
                }
            }
            tf.setXpos(nextX).setYpos(nextY);
        }
    }

    private static float stepAxisVelocity(
            float currentVel,
            float targetVel,
            boolean inputActive,
            float accelRate,
            float frictionRate,
            float dtF
    ) {
        if (inputActive) {
            if (accelRate <= 0.0f) {
                return targetVel;
            }
            float alpha = 1.0f - (float) Math.exp(-accelRate * dtF);
            return currentVel + (targetVel - currentVel) * alpha;
        }
        if (frictionRate <= 0.0f) {
            return 0.0f;
        }
        float decayed = currentVel * (float) Math.exp(-frictionRate * dtF);
        return Math.abs(decayed) < 0.01f ? 0.0f : decayed;
    }

    private void updatePhysics(double dt, MotionInput input) {
        float dtF = (float) dt;
        for (Map.Entry<String, PhysicsBody> entry : physicsBodies.entrySet()) {
            String tag = entry.getKey();
            SpriteNode sprite = activeSprites.get(tag);
            if (sprite == null) {
                continue;
            }
            normalizeToAbsoluteCoords(sprite);
            PhysicsBody body = entry.getValue();
            Transform2D tf = sprite.getTransform();
            float prevY = tf.getYpos();

            if (input.jumpPressed()) {
                body.jumpBufferTimer = JUMP_BUFFER_SECONDS;
            } else {
                body.jumpBufferTimer = Math.max(0.0f, body.jumpBufferTimer - dtF);
            }

            if (body.grounded) {
                body.coyoteTimer = COYOTE_TIME_SECONDS;
            } else {
                body.coyoteTimer = Math.max(0.0f, body.coyoteTimer - dtF);
            }

            if (body.jumpBufferTimer > 0.0f && (body.grounded || body.coyoteTimer > 0.0f)) {
                body.velocityY = -body.jumpForcePxPerSec;
                body.grounded = false;
                body.coyoteTimer = 0.0f;
                body.jumpBufferTimer = 0.0f;
            }

            body.velocityY += body.gravityPxPerSec2 * dtF;
            float nextY = prevY + body.velocityY * dtF;
            boolean landedOnPlatform = false;
            boolean landedOnGround = false;

            if (!solidPlatforms.isEmpty()) {
                Rect2f sBounds = resolveSpriteBounds(sprite);
                float scaledH = (sprite.getHeight() > 0.0f ? sprite.getHeight() : FALLBACK_HITBOX_SIZE)
                        * Math.abs(tf.getZoom() * tf.getYzoom());
                float footOffset = (1.0f - tf.getYanchor()) * scaledH;
                float headOffset = tf.getYanchor() * scaledH;
                float prevFootY = prevY + footOffset;
                float nextFootY = nextY + footOffset;

                for (SolidPlatform plat : solidPlatforms.values()) {
                    if (plat.tag().equals(tag)) {
                        continue;
                    }
                    SpriteNode platSprite = activeSprites.get(plat.tag());
                    if (platSprite == null) {
                        continue;
                    }
                    Rect2f pBounds = resolveSpriteBounds(platSprite);
                    boolean horizontalOverlap = sBounds.x() < pBounds.x() + pBounds.width()
                            && sBounds.x() + sBounds.width() > pBounds.x();
                    if (!horizontalOverlap) {
                        continue;
                    }
                    float platTop = pBounds.y();
                    float platBottom = pBounds.y() + pBounds.height();
                    if (body.velocityY >= 0.0f && prevFootY <= platTop + 2.0f && nextFootY >= platTop) {
                        nextY = platTop - footOffset;
                        nextFootY = platTop;
                        body.velocityY = 0.0f;
                        landedOnPlatform = true;
                    } else if (!plat.oneWay() && body.velocityY < 0.0f) {
                        float prevTopY = prevY - headOffset;
                        float nextTopY = nextY - headOffset;
                        if (prevTopY >= platBottom - 2.0f && nextTopY <= platBottom) {
                            nextY = platBottom + headOffset;
                            body.velocityY = 0.0f;
                        }
                    }
                }
            }

            if (nextY >= body.groundY) {
                nextY = body.groundY;
                body.velocityY = 0.0f;
                landedOnGround = true;
            }

            body.grounded = landedOnPlatform || landedOnGround;

            WasdBinding bounds = wasdBindings.get(tag);
            if (bounds != null && bounds.hasBounds) {
                nextY = Math.max(bounds.minY, Math.min(bounds.maxY, nextY));
            }
            tf.setYpos(nextY);

            if (body.grounded && body.jumpBufferTimer > 0.0f) {
                body.velocityY = -body.jumpForcePxPerSec;
                body.grounded = false;
                body.coyoteTimer = 0.0f;
                body.jumpBufferTimer = 0.0f;
            }
        }
    }

    private void updateCameraFollow(double dt) {
        if (cameraFollowTag == null) {
            return;
        }
        SpriteNode target = activeSprites.get(cameraFollowTag);
        if (target == null) {
            return;
        }
        Transform2D tf = target.getTransform();
        float desiredX = tf.getXpos() - LOGICAL_WIDTH * 0.5f;
        float desiredY = tf.getYpos() - LOGICAL_HEIGHT * 0.5f;
        float alpha = cameraSmoothFactor > 0.0f
                ? 1.0f - (float) Math.exp(-cameraSmoothFactor * dt)
                : 1.0f;
        cameraOffsetX += (desiredX - cameraOffsetX) * alpha;
        cameraOffsetY += (desiredY - cameraOffsetY) * alpha;
    }

    private List<String> checkCollisions() {
        List<String> triggeredJumps = new ArrayList<>();
        if (!collisionTriggers.isEmpty()) {
            List<CollisionTrigger> snapshot = List.copyOf(collisionTriggers);
            for (CollisionTrigger trigger : snapshot) {
                SpriteNode a = activeSprites.get(trigger.tagA);
                SpriteNode b = activeSprites.get(trigger.tagB);
                if (a == null || b == null) {
                    trigger.wasOverlapping = false;
                    continue;
                }
                boolean overlapping = spritesOverlap(trigger.tagA, a, trigger.tagB, b);
                if (overlapping && !trigger.wasOverlapping) {
                    trigger.wasOverlapping = true;
                    triggeredJumps.add(trigger.targetLabel);
                } else if (!overlapping) {
                    trigger.wasOverlapping = false;
                }
            }
        }

        List<String> overlapCalls = null;
        if (!overlapTriggers.isEmpty()) {
            List<OverlapTrigger> snapshot = List.copyOf(overlapTriggers);
            for (OverlapTrigger trigger : snapshot) {
                SpriteNode a = activeSprites.get(trigger.tagA);
                SpriteNode b = activeSprites.get(trigger.tagB);
                if (a == null || b == null) {
                    trigger.wasOverlapping = false;
                    continue;
                }
                boolean was = trigger.wasOverlapping;
                boolean now = spritesOverlap(trigger.tagA, a, trigger.tagB, b);
                trigger.wasOverlapping = now;
                boolean fire = switch (trigger.phase) {
                    case "enter" -> now && !was;
                    case "stay" -> now && was;
                    case "exit" -> !now && was;
                    default -> false;
                };
                if (fire) {
                    if (overlapCalls == null) {
                        overlapCalls = new ArrayList<>();
                    }
                    overlapCalls.add(trigger.targetLabel);
                }
            }
        }

        List<String> zoneCalls = null;
        if (!triggerZones.isEmpty()) {
            List<TriggerZone> zoneSnapshot = List.copyOf(triggerZones.values());
            for (TriggerZone zone : zoneSnapshot) {
                boolean inside = isAnyActorInsideZone(zone.rect);
                boolean rising = inside && !zone.wasInside;
                zone.wasInside = inside;
                if (rising) {
                    if (zone.callAction) {
                        if (zoneCalls == null) {
                            zoneCalls = new ArrayList<>();
                        }
                        zoneCalls.add(zone.targetLabel);
                    } else {
                        triggeredJumps.add(zone.targetLabel);
                    }
                }
            }
        }

        if (overlapCalls != null && subroutineCallListener != null) {
            for (String label : overlapCalls) {
                subroutineCallListener.accept(label);
            }
        }
        if (zoneCalls != null && subroutineCallListener != null) {
            for (String label : zoneCalls) {
                subroutineCallListener.accept(label);
            }
        }
        if (collisionJumpListener != null && !triggeredJumps.isEmpty()) {
            for (String label : triggeredJumps) {
                collisionJumpListener.accept(label);
            }
        }
        return triggeredJumps.isEmpty() ? List.of() : Collections.unmodifiableList(triggeredJumps);
    }

    private boolean isAnyActorInsideZone(Rect2f zoneRect) {
        if (!wasdBindings.isEmpty() || !physicsBodies.isEmpty()) {
            for (String tag : wasdBindings.keySet()) {
                SpriteNode s = activeSprites.get(tag);
                if (s != null && spriteTouchesRect(tag, s, zoneRect)) {
                    return true;
                }
            }
            for (String tag : physicsBodies.keySet()) {
                if (wasdBindings.containsKey(tag)) {
                    continue;
                }
                SpriteNode s = activeSprites.get(tag);
                if (s != null && spriteTouchesRect(tag, s, zoneRect)) {
                    return true;
                }
            }
            return false;
        }
        for (Map.Entry<String, SpriteNode> entry : activeSprites.entrySet()) {
            if (spriteTouchesRect(entry.getKey(), entry.getValue(), zoneRect)) {
                return true;
            }
        }
        return false;
    }

    private boolean spriteTouchesRect(String tag, SpriteNode sprite, Rect2f zoneRect) {
        HitboxSpec custom = customHitboxes.get(tag);
        if (custom != null && custom.shape() == HitboxShape.CIRCLE) {
            Rect2f b = resolveSpriteBounds(sprite);
            return circleIntersectsRect(
                    b.x() + b.width() * 0.5f, b.y() + b.height() * 0.5f, custom.param1(), zoneRect);
        }
        if (custom != null && custom.shape() == HitboxShape.RECT) {
            Rect2f b = resolveSpriteBounds(sprite);
            float cx = b.x() + b.width() * 0.5f;
            float cy = b.y() + b.height() * 0.5f;
            Rect2f r = new Rect2f(
                    cx - custom.param1() * 0.5f, cy - custom.param2() * 0.5f, custom.param1(), custom.param2());
            return rectsOverlap(r, zoneRect);
        }
        Transform2D tf = sprite.getTransform();
        float x = tf.getXpos();
        float y = tf.getYpos();
        return x >= zoneRect.x() && x <= zoneRect.x() + zoneRect.width()
                && y >= zoneRect.y() && y <= zoneRect.y() + zoneRect.height();
    }

    private boolean masksAllowCollision(String tagA, String tagB) {
        CollisionMask maskA = collisionMasks.get(tagA);
        CollisionMask maskB = collisionMasks.get(tagB);
        boolean aAcceptsB = (maskA == null || maskA.collidesWith().isEmpty())
                || (maskB != null && maskA.collidesWith().contains(maskB.layer()));
        boolean bAcceptsA = (maskB == null || maskB.collidesWith().isEmpty())
                || (maskA != null && maskB.collidesWith().contains(maskA.layer()));
        return aAcceptsB && bAcceptsA;
    }

    private boolean spritesOverlap(String tagA, SpriteNode a, String tagB, SpriteNode b) {
        if (!masksAllowCollision(tagA, tagB)) {
            return false;
        }
        HitboxSpec specA = customHitboxes.get(tagA);
        HitboxSpec specB = customHitboxes.get(tagB);
        Rect2f boundsA = resolveSpriteBounds(a);
        Rect2f boundsB = resolveSpriteBounds(b);

        if (specA != null && specA.shape() == HitboxShape.CIRCLE) {
            float ax = boundsA.x() + boundsA.width() * 0.5f;
            float ay = boundsA.y() + boundsA.height() * 0.5f;
            if (specB != null && specB.shape() == HitboxShape.CIRCLE) {
                float bx = boundsB.x() + boundsB.width() * 0.5f;
                float by = boundsB.y() + boundsB.height() * 0.5f;
                float dx = bx - ax;
                float dy = by - ay;
                float sumR = specA.param1() + specB.param1();
                return dx * dx + dy * dy < sumR * sumR;
            }
            return circleIntersectsRect(ax, ay, specA.param1(), toHitboxRect(boundsB, specB));
        }
        if (specB != null && specB.shape() == HitboxShape.CIRCLE) {
            float bx = boundsB.x() + boundsB.width() * 0.5f;
            float by = boundsB.y() + boundsB.height() * 0.5f;
            return circleIntersectsRect(bx, by, specB.param1(), toHitboxRect(boundsA, specA));
        }
        return rectsOverlap(toHitboxRect(boundsA, specA), toHitboxRect(boundsB, specB));
    }

    private static Rect2f toHitboxRect(Rect2f spriteBounds, HitboxSpec spec) {
        if (spec == null) {
            return spriteBounds;
        }
        float cx = spriteBounds.x() + spriteBounds.width() * 0.5f;
        float cy = spriteBounds.y() + spriteBounds.height() * 0.5f;
        if (spec.shape() == HitboxShape.CIRCLE) {
            float d = spec.param1() * 2.0f;
            return new Rect2f(cx - spec.param1(), cy - spec.param1(), d, d);
        }
        return new Rect2f(cx - spec.param1() * 0.5f, cy - spec.param2() * 0.5f, spec.param1(), spec.param2());
    }

    private static boolean circleIntersectsRect(float cx, float cy, float radius, Rect2f rect) {
        float closestX = Math.clamp(cx, rect.x(), rect.x() + rect.width());
        float closestY = Math.clamp(cy, rect.y(), rect.y() + rect.height());
        float dx = cx - closestX;
        float dy = cy - closestY;
        return dx * dx + dy * dy < radius * radius;
    }

    private static void initMoveEndpoints(MoveTween tween, SpriteNode sprite) {
        normalizeToAbsoluteCoords(sprite);
        Transform2D tf = sprite.getTransform();
        tween.startX = tf.getXpos();
        tween.startY = tf.getYpos();
        if (tween.bezier) {
            tween.endX = tween.rawX;
            tween.endY = tween.rawY;
        } else {
            tween.endX = tween.relative ? tween.startX + tween.rawX : tween.rawX;
            tween.endY = tween.relative ? tween.startY + tween.rawY : tween.rawY;
        }
        tween.initializedStart = true;
    }

    private static void initRotateEndpoints(RotateTween tween, SpriteNode sprite) {
        float start = sprite.getTransform().getRotate();
        tween.startDegrees = Float.isFinite(start) ? start : 0.0f;
        float rawDiff = tween.targetDegrees - tween.startDegrees;
        tween.shortestDeltaDegrees = ((rawDiff % 360.0f) + 540.0f) % 360.0f - 180.0f;
        tween.initializedStart = true;
    }

    private static void initScaleEndpoints(ScaleTween tween, SpriteNode sprite) {
        Transform2D tf = sprite.getTransform();
        tween.startXZoom = Float.isFinite(tf.getXzoom()) ? tf.getXzoom() : 1.0f;
        tween.startYZoom = Float.isFinite(tf.getYzoom()) ? tf.getYzoom() : 1.0f;
        tween.initializedStart = true;
    }

    private static void applyAbsolutePos(SpriteNode sprite, float x, float y) {
        sprite.getTransform()
                .setXalign(Float.NaN)
                .setYalign(Float.NaN)
                .setXpos(x)
                .setYpos(y);
    }

    /**
     * Converts align-based positioning ({@code xalign}/{@code yalign}) into explicit
     * {@code xpos}/{@code ypos} pixel coordinates so incremental motion works seamlessly.
     */
    private static void normalizeToAbsoluteCoords(SpriteNode sprite) {
        Transform2D tf = sprite.getTransform();
        boolean alignX = !Float.isNaN(tf.getXalign());
        boolean alignY = !Float.isNaN(tf.getYalign());
        if (!alignX && !alignY) {
            return;
        }
        Rect2f bounds = resolveSpriteBounds(sprite);
        if (alignX) {
            tf.setXpos(bounds.x() + tf.getXanchor() * bounds.width());
            tf.setXalign(Float.NaN);
        }
        if (alignY) {
            tf.setYpos(bounds.y() + tf.getYanchor() * bounds.height());
            tf.setYalign(Float.NaN);
        }
    }

    private static Rect2f resolveSpriteBounds(SpriteNode sprite) {
        float w = sprite.getWidth() > 0.0f ? sprite.getWidth() : FALLBACK_HITBOX_SIZE;
        float h = sprite.getHeight() > 0.0f ? sprite.getHeight() : FALLBACK_HITBOX_SIZE;
        return sprite.getTransform().resolveBounds(w, h, LOGICAL_WIDTH, LOGICAL_HEIGHT);
    }

    private static boolean rectsOverlap(Rect2f a, Rect2f b) {
        return a.x() < b.x() + b.width()
                && a.x() + a.width() > b.x()
                && a.y() < b.y() + b.height()
                && a.y() + a.height() > b.y();
    }

    private static String normalizeEasing(String easing) {
        if (easing == null || easing.isBlank()) {
            return "linear";
        }
        String norm = easing.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_EASINGS.contains(norm) ? norm : "linear";
    }

    private static double applyEasing(String easing, double t) {
        double clamped = Math.max(0.0, Math.min(1.0, t));
        return switch (easing) {
            case "ease_in" -> clamped * clamped;
            case "ease_out" -> 1.0 - (1.0 - clamped) * (1.0 - clamped);
            case "ease_in_out" -> clamped < 0.5
                    ? 2.0 * clamped * clamped
                    : 1.0 - Math.pow(-2.0 * clamped + 2.0, 2.0) / 2.0;
            default -> clamped;
        };
    }

    /**
     * Resolves the effective debug hitbox rectangle for {@code tag}/{@code sprite} (for debug overlays).
     *
     * @param tag    sprite tag
     * @param sprite active sprite node
     * @return axis-aligned bounding box representing its active hitbox
     */
    public Rect2f resolveDebugHitbox(String tag, SpriteNode sprite) {
        Objects.requireNonNull(sprite, "sprite must not be null");
        Rect2f spriteBounds = resolveSpriteBounds(sprite);
        return toHitboxRect(spriteBounds, tag != null ? customHitboxes.get(tag) : null);
    }

    /**
     * Returns an immutable map of registered trigger zone rectangles keyed by zone ID.
     *
     * @return map of trigger zone rectangles by ID
     */
    public Map<String, Rect2f> getTriggerZoneRects() {
        Map<String, Rect2f> out = new LinkedHashMap<>(triggerZones.size());
        for (TriggerZone z : triggerZones.values()) {
            out.put(z.zoneId, z.rect);
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * Returns whether camera follow tracking is currently enabled.
     *
     * @return {@code true} if camera follow is active
     */
    public boolean isCameraFollowActive() {
        return cameraFollowTag != null;
    }

    /**
     * Returns the current horizontal camera offset in logical pixels.
     *
     * @return horizontal camera offset
     */
    public float getCameraOffsetX() {
        return cameraOffsetX;
    }

    /**
     * Returns the current vertical camera offset in logical pixels.
     *
     * @return vertical camera offset
     */
    public float getCameraOffsetY() {
        return cameraOffsetY;
    }

    /**
     * Returns whether any WASD movement binding is active.
     *
     * @return {@code true} if at least one WASD movement binding is currently active
     */
    public boolean hasActiveWasd() {
        return !wasdBindings.isEmpty();
    }

    /**
     * Returns whether any 2D physics body is active.
     *
     * @return {@code true} if at least one 2D physics body is currently active
     */
    public boolean hasActivePhysics() {
        return !physicsBodies.isEmpty();
    }

    /**
     * Returns an immutable set of sprite tags bound to WASD control.
     *
     * @return immutable set of sprite tags currently bound to WASD control
     */
    public Set<String> getActiveWasdTags() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(wasdBindings.keySet()));
    }

    /**
     * Returns an immutable set of sprite tags driven by 2D physics.
     *
     * @return immutable set of sprite tags currently driven by 2D physics
     */
    public Set<String> getActivePhysicsTags() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(physicsBodies.keySet()));
    }

    /**
     * Returns the number of active movement, rotation, and scale tweens.
     *
     * @return number of currently running motion tweens
     */
    public int getActiveTweenCount() {
        return activeTweens.size() + activeRotateTweens.size() + activeScaleTweens.size();
    }

    /**
     * Returns the number of registered collision triggers.
     *
     * @return number of registered collision triggers
     */
    public int getCollisionTriggerCount() {
        return collisionTriggers.size();
    }

    /**
     * Returns whether the physics body attached to {@code tag} is currently resting on ground or a platform.
     *
     * @param tag semantic sprite tag
     * @return {@code true} if grounded, {@code false} otherwise or if no physics body is attached
     */
    public boolean isGrounded(String tag) {
        PhysicsBody body = physicsBodies.get(tag);
        return body != null && body.grounded;
    }

    /**
     * Returns the first active WASD-controlled sprite tag, or {@code null} if none is bound.
     *
     * @return active WASD sprite tag or {@code null}
     */
    public String getPrimaryWasdTag() {
        return wasdBindings.isEmpty() ? null : wasdBindings.keySet().iterator().next();
    }

    /**
     * Returns all distinct partner sprite tags linked to {@code spriteTag} via {@code on_collide}
     * or {@code on_overlap} triggers.
     *
     * @param spriteTag query sprite tag (nullable)
     * @return immutable list of interactive partner tags
     */
    public List<String> getInteractivePartnersFor(String spriteTag) {
        if (spriteTag == null || spriteTag.isBlank()) {
            return List.of();
        }
        Set<String> partners = new LinkedHashSet<>();
        for (CollisionTrigger t : collisionTriggers) {
            if (spriteTag.equals(t.tagA)) {
                partners.add(t.tagB);
            } else if (spriteTag.equals(t.tagB)) {
                partners.add(t.tagA);
            }
        }
        for (OverlapTrigger t : overlapTriggers) {
            if (spriteTag.equals(t.tagA)) {
                partners.add(t.tagB);
            } else if (spriteTag.equals(t.tagB)) {
                partners.add(t.tagA);
            }
        }
        return List.copyOf(partners);
    }
}
