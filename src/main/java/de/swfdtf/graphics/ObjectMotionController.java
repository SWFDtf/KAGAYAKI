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
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
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
            "linear", "ease_in", "ease_out", "ease_in_out", "spring"
    );

    /** Fixed deterministic physics substep duration (1/120 s) to prevent high-speed tunneling. */
    public static final double FIXED_PHYSICS_STEP = 1.0 / 120.0;

    /** Coyote-time grace window after walking off a platform edge in seconds. */
    public static final float COYOTE_TIME_SECONDS = 0.10f;

    /** Pre-landing jump-input buffer window in seconds. */
    public static final float JUMP_BUFFER_SECONDS = 0.10f;

    private static final float LOGICAL_WIDTH = RenderPipeline2D.DEFAULT_LOGICAL_WIDTH;
    private static final float LOGICAL_HEIGHT = RenderPipeline2D.DEFAULT_LOGICAL_HEIGHT;
    private static final float FALLBACK_HITBOX_SIZE = 64.0f;
    private static final int MAX_PHYSICS_SUBSTEPS = 64;
    private static final float DEFAULT_DASH_MULTIPLIER = 2.5f;
    private static final float DEFAULT_DASH_DURATION = 0.18f;
    private static final float DEFAULT_DASH_COOLDOWN = 0.80f;

    /**
     * Frame-level directional, analog, jump, dash, and interact input passed to
     * {@link #update(double, MotionInput)}.
     *
     * @param up              whether upward input (W / ArrowUp) is held
     * @param down            whether downward input (S / ArrowDown) is held
     * @param left            whether leftward input (A / ArrowLeft) is held
     * @param right           whether rightward input (D / ArrowRight) is held
     * @param jumpPressed     whether jump (Space / W) transitioned down this frame
     * @param analogX         normalized analog stick horizontal deflection in {@code [-1, 1]}
     * @param analogY         normalized analog stick vertical deflection in {@code [-1, 1]}
     * @param dashPressed     whether dash transitioned down this frame
     * @param interactPressed whether interact (E / Enter) transitioned down this frame
     */
    public record MotionInput(
            boolean up,
            boolean down,
            boolean left,
            boolean right,
            boolean jumpPressed,
            float analogX,
            float analogY,
            boolean dashPressed,
            boolean interactPressed
    ) {
        /** Neutral zero-input state used when no hardware input is sampled. */
        public static final MotionInput NONE =
                new MotionInput(false, false, false, false, false, 0.0f, 0.0f, false, false);

        /**
         * Backwards-compatible 5-argument digital-only constructor.
         *
         * @param up          whether upward input is held
         * @param down        whether downward input is held
         * @param left        whether leftward input is held
         * @param right       whether rightward input is held
         * @param jumpPressed whether jump transitioned down this frame
         */
        public MotionInput(boolean up, boolean down, boolean left, boolean right, boolean jumpPressed) {
            this(up, down, left, right, jumpPressed, 0.0f, 0.0f, false, false);
        }

        private MotionInput heldOnly() {
            return new MotionInput(up, down, left, right, false, analogX, analogY, false, false);
        }
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
        private boolean autoFlip;
        private String dashKey;
        private float dashSpeedMultiplier = DEFAULT_DASH_MULTIPLIER;
        private float dashDurationSeconds = DEFAULT_DASH_DURATION;
        private float dashCooldownSeconds = DEFAULT_DASH_COOLDOWN;
        private float dashTimer;
        private float dashCooldownTimer;
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

    private static final class PatrolState {
        private final String tag;
        private final float x1;
        private final float y1;
        private final float x2;
        private final float y2;
        private final float speedPxPerSec;
        private final float distance;
        private final boolean pingPong;
        private float travelled = 0.0f;
        private float direction = 1.0f;

        private PatrolState(
                String tag,
                float x1,
                float y1,
                float x2,
                float y2,
                float speedPxPerSec,
                boolean pingPong
        ) {
            this.tag = tag;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.speedPxPerSec = Math.max(0.0f, speedPxPerSec);
            this.distance = (float) Math.hypot(x2 - x1, y2 - y1);
            this.pingPong = pingPong;
        }
    }

    private static final class FollowState {
        private final String tag;
        private final String targetTag;
        private final float speedPxPerSec;
        private final float stopRadius;
        private final float resumeRadius;
        private boolean moving = true;

        private FollowState(
                String tag,
                String targetTag,
                float speedPxPerSec,
                float stopRadius,
                float resumeRadius
        ) {
            this.tag = tag;
            this.targetTag = targetTag;
            this.speedPxPerSec = Math.max(0.0f, speedPxPerSec);
            this.stopRadius = Math.max(0.0f, stopRadius);
            this.resumeRadius = Math.max(this.stopRadius, resumeRadius);
        }
    }

    private static final class MovingPlatformState {
        private final String tag;
        private final float x1;
        private final float y1;
        private final float x2;
        private final float y2;
        private final float speedPxPerSec;
        private final float distance;
        private float travelled = 0.0f;
        private float direction = 1.0f;

        private MovingPlatformState(String tag, float x1, float y1, float x2, float y2, float speedPxPerSec) {
            this.tag = tag;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.speedPxPerSec = Math.max(0.0f, speedPxPerSec);
            this.distance = (float) Math.hypot(x2 - x1, y2 - y1);
        }
    }

    private record AttractState(String tag, String targetTag, float radiusPx, float speedPxPerSec) {
    }

    private record AttachState(String childTag, String parentTag, float offsetX, float offsetY) {
    }

    private static final class AnimStateConfig {
        private final String idleAttr;
        private final String walkAttr;
        private final String jumpAttr;
        private String currentState;

        private AnimStateConfig(String idleAttr, String walkAttr, String jumpAttr) {
            this.idleAttr = idleAttr;
            this.walkAttr = walkAttr;
            this.jumpAttr = jumpAttr;
        }
    }

    private record FrictionZone(Rect2f rect, float factor) {
    }

    private static final class KnockbackState {
        private float vx;
        private float vy;
        private float invulnTimer;
        private final float frictionDecel;

        private KnockbackState(float vx, float vy, float invulnTimer, float frictionDecel) {
            this.vx = vx;
            this.vy = vy;
            this.invulnTimer = Math.max(0.0f, invulnTimer);
            this.frictionDecel = Math.max(0.1f, frictionDecel);
        }
    }

    private record PointClickConfig(
            String tag,
            float speedPxPerSec,
            Float minX,
            Float minY,
            Float maxX,
            Float maxY,
            boolean hasBounds
    ) {
    }

    private record InteractTrigger(String targetTag, float maxDistancePx, String targetLabel, boolean callAction) {
    }

    private record TweenEndTrigger(String tag, String targetLabel, boolean callAction) {
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

    private final Map<String, PatrolState> patrolStates = new LinkedHashMap<>();
    private final Map<String, FollowState> followStates = new LinkedHashMap<>();
    private final Map<String, MovingPlatformState> movingPlatforms = new LinkedHashMap<>();
    private final Map<String, AttractState> attractStates = new LinkedHashMap<>();
    private final Map<String, AttachState> attachStates = new LinkedHashMap<>();
    private final Map<String, AnimStateConfig> animStates = new LinkedHashMap<>();
    private final Map<String, List<Rect2f>> blockingColliders = new LinkedHashMap<>();
    private final Map<String, FrictionZone> frictionZones = new LinkedHashMap<>();
    private final Map<String, Rect2f> climbZones = new LinkedHashMap<>();
    private final Map<String, KnockbackState> knockbacks = new LinkedHashMap<>();
    private final Map<String, PointClickConfig> pointClickConfigs = new LinkedHashMap<>();
    private final List<InteractTrigger> interactTriggers = new ArrayList<>();
    private final List<TweenEndTrigger> tweenEndTriggers = new ArrayList<>();

    private float timeScale = 1.0f;
    private float logicalViewportWidth = LOGICAL_WIDTH;
    private float logicalViewportHeight = LOGICAL_HEIGHT;

    private String cameraFollowTag;
    private float cameraSmoothFactor = 0.0f;
    private float cameraOffsetX = 0.0f;
    private float cameraOffsetY = 0.0f;
    private boolean cameraClampActive = false;
    private float cameraClampMinX = 0.0f;
    private float cameraClampMinY = 0.0f;
    private float cameraClampMaxX = 0.0f;
    private float cameraClampMaxY = 0.0f;

    private Consumer<String> collisionJumpListener;
    private Consumer<String> subroutineCallListener;
    private Consumer<String> tweenCompleteListener;
    private BiConsumer<String, String> animStateListener;

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
     * Enables WASD / arrow-key movement for {@code tag} with optional inertia, auto-flip, and dash.
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
        enableWasd(tag, speedPxPerSec, minX, minY, maxX, maxY, normalizeDiagonal,
                friction, acceleration, false, null);
    }

    /**
     * Enables WASD / arrow-key movement for {@code tag} with optional inertia, auto-flip, and dash key.
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
     * @param autoFlip          whether to mirror {@code xzoom} automatically along movement direction
     * @param dashKey           optional dash key identifier (nullable)
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
            float acceleration,
            boolean autoFlip,
            String dashKey
    ) {
        Objects.requireNonNull(tag, "tag must not be null");
        WasdBinding binding = new WasdBinding(
                speedPxPerSec, minX, minY, maxX, maxY, normalizeDiagonal, friction, acceleration);
        binding.autoFlip = autoFlip;
        binding.dashKey = (dashKey != null && !dashKey.isBlank()) ? dashKey.trim() : null;
        wasdBindings.put(tag, binding);
        SpriteNode sprite = activeSprites.get(tag);
        if (sprite != null) {
            normalizeToAbsoluteCoords(sprite);
        }
    }

    /**
     * Configures horizontal sprite flip behavior for an existing WASD binding.
     *
     * @param tag      sprite tag
     * @param autoFlip {@code true} to mirror {@code xzoom} when walking left
     */
    public void setWasdAutoFlip(String tag, boolean autoFlip) {
        WasdBinding binding = wasdBindings.get(tag);
        if (binding != null) {
            binding.autoFlip = autoFlip;
        }
    }

    /**
     * Configures dash speed boost, burst duration, and cooldown for an existing WASD binding.
     *
     * @param tag                 sprite tag
     * @param speedMultiplier     speed multiplier during active dash ({@code >= 1})
     * @param durationSeconds     burst duration in seconds
     * @param cooldownSeconds     cooldown duration before next dash
     */
    public void configureDash(String tag, float speedMultiplier, float durationSeconds, float cooldownSeconds) {
        WasdBinding binding = wasdBindings.get(tag);
        if (binding != null) {
            binding.dashSpeedMultiplier = Math.max(1.0f, speedMultiplier);
            binding.dashDurationSeconds = Math.max(0.01f, durationSeconds);
            binding.dashCooldownSeconds = Math.max(0.0f, cooldownSeconds);
        }
    }

    /**
     * Returns whether {@code tag} is currently inside an active dash burst.
     *
     * @param tag sprite tag
     * @return {@code true} while dashing
     */
    public boolean isDashing(String tag) {
        WasdBinding binding = wasdBindings.get(tag);
        return binding != null && binding.dashTimer > 0.0f;
    }

    /**
     * Returns remaining dash cooldown time in seconds for {@code tag}.
     *
     * @param tag sprite tag
     * @return remaining cooldown seconds ({@code 0} when ready)
     */
    public float getDashCooldownRemaining(String tag) {
        WasdBinding binding = wasdBindings.get(tag);
        return binding != null ? Math.max(0.0f, binding.dashCooldownTimer) : 0.0f;
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
     * Clamps tracked camera viewport offsets within {@code [minX, maxX]} and {@code [minY, maxY]}.
     *
     * @param minX minimum camera X offset
     * @param minY minimum camera Y offset
     * @param maxX maximum camera X offset
     * @param maxY maximum camera Y offset
     */
    public void setCameraClamp(float minX, float minY, float maxX, float maxY) {
        this.cameraClampActive = true;
        this.cameraClampMinX = Math.min(minX, maxX);
        this.cameraClampMinY = Math.min(minY, maxY);
        this.cameraClampMaxX = Math.max(minX, maxX);
        this.cameraClampMaxY = Math.max(minY, maxY);
        clampCameraOffsets();
    }

    /** Clears active camera viewport clamp bounds. */
    public void clearCameraClamp() {
        this.cameraClampActive = false;
    }

    /**
     * Sets time dilation scale applied to tweens, WASD, physics, patrols, and timers ({@code 1.0f} = real-time).
     *
     * @param timeScale time multiplier ({@code >= 0.0f})
     */
    public void setTimeScale(float timeScale) {
        this.timeScale = Float.isFinite(timeScale) && timeScale >= 0.0f ? timeScale : 1.0f;
    }

    /**
     * Returns the current motion/physics time scale multiplier.
     *
     * @return current time scale multiplier
     */
    public float getTimeScale() {
        return timeScale;
    }

    /**
     * Sets logical viewport dimensions used by {@link #startRelativeViewportMove}.
     *
     * @param width  logical viewport width
     * @param height logical viewport height
     */
    public void setLogicalViewport(float width, float height) {
        if (Float.isFinite(width) && width > 0.0f) {
            this.logicalViewportWidth = width;
        }
        if (Float.isFinite(height) && height > 0.0f) {
            this.logicalViewportHeight = height;
        }
    }

    /**
     * Starts a move tween targeting normalized viewport coordinates {@code (relX, relY)} in {@code [0, 1]}.
     *
     * @param tag             sprite tag
     * @param relX            normalized horizontal viewport fraction
     * @param relY            normalized vertical viewport fraction
     * @param durationSeconds duration in seconds
     * @param easing          easing curve name
     */
    public void startRelativeViewportMove(
            String tag,
            float relX,
            float relY,
            double durationSeconds,
            String easing
    ) {
        startMove(tag, relX * logicalViewportWidth, relY * logicalViewportHeight,
                false, durationSeconds, easing);
    }

    /**
     * Starts automated waypoint patrol between {@code (x1, y1)} and {@code (x2, y2)}.
     *
     * @param tag           sprite tag
     * @param x1            start X
     * @param y1            start Y
     * @param x2            end X
     * @param y2            end Y
     * @param speedPxPerSec travel speed in pixels/sec
     * @param pingPong      {@code true} to bounce back and forth, {@code false} to loop
     */
    public void startPatrol(
            String tag,
            float x1,
            float y1,
            float x2,
            float y2,
            float speedPxPerSec,
            boolean pingPong
    ) {
        Objects.requireNonNull(tag, "tag must not be null");
        PatrolState patrol = new PatrolState(tag, x1, y1, x2, y2, speedPxPerSec, pingPong);
        patrolStates.put(tag, patrol);
        SpriteNode sprite = activeSprites.get(tag);
        if (sprite != null) {
            applyAbsolutePos(sprite, x1, y1);
        }
    }

    /**
     * Stops active patrol behavior for {@code tag}.
     *
     * @param tag sprite tag
     */
    public void stopPatrol(String tag) {
        if (tag != null) {
            patrolStates.remove(tag);
        }
    }

    /**
     * Starts pursuit/follow AI moving {@code tag} toward {@code targetTag} with distance hysteresis.
     *
     * @param tag           follower sprite tag
     * @param targetTag     target sprite tag
     * @param speedPxPerSec movement speed in pixels/sec
     * @param stopRadius    radius at which pursuit halts
     * @param resumeRadius  radius at which pursuit resumes ({@code >= stopRadius})
     */
    public void startFollow(
            String tag,
            String targetTag,
            float speedPxPerSec,
            float stopRadius,
            float resumeRadius
    ) {
        Objects.requireNonNull(tag, "tag must not be null");
        Objects.requireNonNull(targetTag, "targetTag must not be null");
        followStates.put(tag, new FollowState(tag, targetTag, speedPxPerSec, stopRadius, resumeRadius));
    }

    /**
     * Stops follow AI for {@code tag}.
     *
     * @param tag sprite tag
     */
    public void stopFollow(String tag) {
        if (tag != null) {
            followStates.remove(tag);
        }
    }

    /**
     * Registers a proximity interaction trigger activated by {@code E}/{@code Enter} within {@code maxDistancePx}.
     *
     * @param targetTag     target sprite tag
     * @param maxDistancePx maximum Euclidean distance from active player sprite
     * @param targetLabel   label to jump or call when interacted
     * @param callAction    {@code true} for {@code call}, {@code false} for {@code jump}
     */
    public void registerInteractTrigger(
            String targetTag,
            float maxDistancePx,
            String targetLabel,
            boolean callAction
    ) {
        Objects.requireNonNull(targetTag, "targetTag must not be null");
        Objects.requireNonNull(targetLabel, "targetLabel must not be null");
        interactTriggers.removeIf(t -> t.targetTag().equals(targetTag) && t.targetLabel().equals(targetLabel));
        interactTriggers.add(new InteractTrigger(
                targetTag, Math.max(0.0f, maxDistancePx), targetLabel, callAction));
    }

    /**
     * Registers automatic {@code idle}/{@code walk}/{@code jump} sprite state mapping for {@code tag}.
     *
     * @param tag      sprite tag
     * @param idleAttr attribute name while stationary
     * @param walkAttr attribute name while moving horizontally
     * @param jumpAttr attribute name while airborne
     */
    public void registerAnimState(String tag, String idleAttr, String walkAttr, String jumpAttr) {
        Objects.requireNonNull(tag, "tag must not be null");
        animStates.put(tag, new AnimStateConfig(
                idleAttr != null ? idleAttr : "idle",
                walkAttr != null ? walkAttr : "walk",
                jumpAttr != null ? jumpAttr : "jump"));
    }

    /**
     * Clears automatic animation state mapping for {@code tag}.
     *
     * @param tag sprite tag
     */
    public void clearAnimState(String tag) {
        if (tag != null) {
            animStates.remove(tag);
        }
    }

    /**
     * Returns the current resolved animation state attribute name for {@code tag}, if tracked.
     *
     * @param tag sprite tag
     * @return optional current animation state name
     */
    public Optional<String> getCurrentAnimState(String tag) {
        AnimStateConfig cfg = tag != null ? animStates.get(tag) : null;
        return cfg != null ? Optional.ofNullable(cfg.currentState) : Optional.empty();
    }

    /**
     * Sets a listener invoked whenever a tracked sprite's animation state changes.
     *
     * @param listener consumer receiving {@code (spriteTag, attrName)}
     */
    public void setAnimStateListener(BiConsumer<String, String> listener) {
        this.animStateListener = listener;
    }

    /**
     * Adds a solid rectangular wall collider that blocks WASD movement with axis sliding.
     *
     * @param id     collider identifier
     * @param x      left X coordinate
     * @param y      top Y coordinate
     * @param width  collider width
     * @param height collider height
     */
    public void addWallCollider(String id, float x, float y, float width, float height) {
        addCompoundCollider(id, List.of(new Rect2f(x, y, Math.max(1.0f, width), Math.max(1.0f, height))));
    }

    /**
     * Adds a multi-box compound collider blocking WASD movement across all component rectangles.
     *
     * @param id    collider identifier
     * @param boxes non-empty list of collider rectangles
     */
    public void addCompoundCollider(String id, List<Rect2f> boxes) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(boxes, "boxes must not be null");
        blockingColliders.put(id, List.copyOf(boxes));
    }

    /**
     * Removes a wall or compound collider by {@code id}.
     *
     * @param id collider identifier
     */
    public void removeCollider(String id) {
        if (id != null) {
            blockingColliders.remove(id);
        }
    }

    /**
     * Applies a decaying knockback impulse and temporary invulnerability window to {@code tag}.
     *
     * @param tag                   sprite tag
     * @param vx                    initial horizontal velocity
     * @param vy                    initial vertical velocity
     * @param invulnerabilitySec    invulnerability window duration in seconds
     * @param frictionDecel         exponential decay rate
     */
    public void applyKnockback(
            String tag,
            float vx,
            float vy,
            float invulnerabilitySec,
            float frictionDecel
    ) {
        Objects.requireNonNull(tag, "tag must not be null");
        knockbacks.put(tag, new KnockbackState(vx, vy, invulnerabilitySec, frictionDecel));
    }

    /**
     * Returns whether {@code tag} is currently inside a knockback invulnerability window.
     *
     * @param tag sprite tag
     * @return {@code true} if invulnerable
     */
    public boolean isInvulnerable(String tag) {
        KnockbackState state = tag != null ? knockbacks.get(tag) : null;
        return state != null && state.invulnTimer > 0.0f;
    }

    /**
     * Applies an instantaneous velocity impulse to {@code tag} (e.g. jump pad or catapult).
     *
     * @param tag sprite tag
     * @param vx  horizontal velocity delta in pixels/sec
     * @param vy  vertical velocity delta in pixels/sec (negative launches upward)
     */
    public void applyImpulse(String tag, float vx, float vy) {
        Objects.requireNonNull(tag, "tag must not be null");
        PhysicsBody body = physicsBodies.get(tag);
        if (body != null && Float.isFinite(vy)) {
            body.velocityY += vy;
            if (vy < 0.0f) {
                body.grounded = false;
            }
        }
        WasdBinding wasd = wasdBindings.get(tag);
        if (wasd != null && Float.isFinite(vx)) {
            wasd.velX += vx;
        } else if (Float.isFinite(vx) && vx != 0.0f) {
            knockbacks.put(tag, new KnockbackState(vx, body == null ? vy : 0.0f, 0.0f, 6.0f));
        }
    }

    /**
     * Registers a rectangular climbing zone suspending gravity and enabling W/S vertical climb.
     *
     * @param id     climb zone identifier
     * @param x      left X
     * @param y      top Y
     * @param width  width
     * @param height height
     */
    public void addClimbZone(String id, float x, float y, float width, float height) {
        Objects.requireNonNull(id, "id must not be null");
        climbZones.put(id, new Rect2f(x, y, Math.max(1.0f, width), Math.max(1.0f, height)));
    }

    /**
     * Registers a moving platform oscillating along {@code (x1, y1) <-> (x2, y2)} and carrying riders.
     *
     * @param tag           platform sprite tag
     * @param x1            start X
     * @param y1            start Y
     * @param x2            end X
     * @param y2            end Y
     * @param speedPxPerSec speed in pixels/sec
     * @param oneWay        whether platform allows upward passage from below
     */
    public void registerMovingPlatform(
            String tag,
            float x1,
            float y1,
            float x2,
            float y2,
            float speedPxPerSec,
            boolean oneWay
    ) {
        Objects.requireNonNull(tag, "tag must not be null");
        registerSolidPlatform(tag, oneWay);
        movingPlatforms.put(tag, new MovingPlatformState(tag, x1, y1, x2, y2, speedPxPerSec));
        SpriteNode sprite = activeSprites.get(tag);
        if (sprite != null) {
            applyAbsolutePos(sprite, x1, y1);
        }
    }

    /**
     * Registers magnetic pickup attraction pulling {@code tag} toward {@code targetTag} inside {@code radiusPx}.
     *
     * @param tag           pickup sprite tag
     * @param targetTag     magnet target tag
     * @param radiusPx      activation radius in pixels
     * @param speedPxPerSec pull speed in pixels/sec
     */
    public void registerAttract(String tag, String targetTag, float radiusPx, float speedPxPerSec) {
        Objects.requireNonNull(tag, "tag must not be null");
        Objects.requireNonNull(targetTag, "targetTag must not be null");
        attractStates.put(tag, new AttractState(
                tag, targetTag, Math.max(0.0f, radiusPx), Math.max(1.0f, speedPxPerSec)));
    }

    /**
     * Registers an automatic jump or subroutine call executed when {@code tag}'s tween finishes.
     *
     * @param tag         sprite tag
     * @param targetLabel label to jump or call
     * @param callAction  {@code true} for subroutine {@code call}, {@code false} for {@code jump}
     */
    public void registerTweenEndTrigger(String tag, String targetLabel, boolean callAction) {
        Objects.requireNonNull(tag, "tag must not be null");
        Objects.requireNonNull(targetLabel, "targetLabel must not be null");
        tweenEndTriggers.add(new TweenEndTrigger(tag, targetLabel, callAction));
    }

    /**
     * Attaches {@code childTag} to {@code parentTag} so its position and rotation follow automatically.
     *
     * @param childTag  child sprite tag
     * @param parentTag parent sprite tag
     * @param offsetX   local X offset relative to parent
     * @param offsetY   local Y offset relative to parent
     */
    public void attachChild(String childTag, String parentTag, float offsetX, float offsetY) {
        Objects.requireNonNull(childTag, "childTag must not be null");
        Objects.requireNonNull(parentTag, "parentTag must not be null");
        attachStates.put(childTag, new AttachState(childTag, parentTag, offsetX, offsetY));
        updateAttachedChildren();
    }

    /**
     * Detaches a previously attached child sprite.
     *
     * @param childTag child sprite tag
     */
    public void detachChild(String childTag) {
        if (childTag != null) {
            attachStates.remove(childTag);
        }
    }

    /**
     * Enables point-and-click stage movement for {@code tag}.
     *
     * @param tag           sprite tag
     * @param speedPxPerSec walking speed in pixels/sec
     * @param minX          optional left bound
     * @param minY          optional top bound
     * @param maxX          optional right bound
     * @param maxY          optional bottom bound
     */
    public void enablePointClick(
            String tag,
            float speedPxPerSec,
            Float minX,
            Float minY,
            Float maxX,
            Float maxY
    ) {
        Objects.requireNonNull(tag, "tag must not be null");
        boolean hasBounds = minX != null && minY != null && maxX != null && maxY != null;
        pointClickConfigs.put(tag, new PointClickConfig(
                tag, Math.max(1.0f, speedPxPerSec), minX, minY, maxX, maxY, hasBounds));
    }

    /**
     * Handles a stage click at {@code (stageX, stageY)} by starting {@link MoveTween}s for all
     * enabled {@code point_click_init} bindings.
     *
     * @param stageX target logical X coordinate
     * @param stageY target logical Y coordinate
     * @return {@code true} if at least one sprite started moving
     */
    public boolean handleStageClick(float stageX, float stageY) {
        if (pointClickConfigs.isEmpty()) {
            return false;
        }
        boolean started = false;
        for (PointClickConfig cfg : pointClickConfigs.values()) {
            SpriteNode sprite = activeSprites.get(cfg.tag());
            if (sprite == null) {
                continue;
            }
            normalizeToAbsoluteCoords(sprite);
            float tx = stageX;
            float ty = stageY;
            if (cfg.hasBounds()) {
                tx = Math.clamp(tx, cfg.minX(), cfg.maxX());
                ty = Math.clamp(ty, cfg.minY(), cfg.maxY());
            }
            float dx = tx - sprite.getTransform().getXpos();
            float dy = ty - sprite.getTransform().getYpos();
            double dist = Math.hypot(dx, dy);
            double dur = dist / cfg.speedPxPerSec();
            startMove(cfg.tag(), tx, ty, false, dur, "linear");
            started = true;
        }
        return started;
    }

    /**
     * Registers a surface friction zone multiplying WASD acceleration and friction inside the rectangle.
     *
     * @param id             zone ID
     * @param x              left X
     * @param y              top Y
     * @param width          width
     * @param height         height
     * @param frictionFactor multiplier ({@code < 1.0} = slippery ice, {@code > 1.0} = sticky mud)
     */
    public void addSurfaceFrictionZone(String id, float x, float y, float width, float height, float frictionFactor) {
        Objects.requireNonNull(id, "id must not be null");
        float factor = Float.isFinite(frictionFactor) && frictionFactor > 0.0f ? frictionFactor : 1.0f;
        frictionZones.put(id, new FrictionZone(
                new Rect2f(x, y, Math.max(1.0f, width), Math.max(1.0f, height)), factor));
    }

    /**
     * Removes all motion, physics, AI, trigger, and attachment bindings referencing {@code tag}.
     *
     * @param tag sprite tag to purge
     */
    public void removeAllBindingsForTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return;
        }
        activeTweens.remove(tag);
        activeRotateTweens.remove(tag);
        activeScaleTweens.remove(tag);
        wasdBindings.remove(tag);
        physicsBodies.remove(tag);
        patrolStates.remove(tag);
        followStates.remove(tag);
        movingPlatforms.remove(tag);
        attractStates.remove(tag);
        attachStates.remove(tag);
        attachStates.values().removeIf(a -> a.parentTag().equals(tag));
        followStates.values().removeIf(f -> f.targetTag.equals(tag));
        attractStates.values().removeIf(a -> a.targetTag().equals(tag));
        animStates.remove(tag);
        knockbacks.remove(tag);
        pointClickConfigs.remove(tag);
        solidPlatforms.remove(tag);
        collisionMasks.remove(tag);
        customHitboxes.remove(tag);
        collisionTriggers.removeIf(t -> t.tagA.equals(tag) || t.tagB.equals(tag));
        overlapTriggers.removeIf(t -> t.tagA.equals(tag) || t.tagB.equals(tag));
        interactTriggers.removeIf(t -> t.targetTag().equals(tag));
        tweenEndTriggers.removeIf(t -> t.tag().equals(tag));
        if (tag.equals(cameraFollowTag)) {
            disableCameraFollow();
        }
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
        patrolStates.clear();
        followStates.clear();
        movingPlatforms.clear();
        attractStates.clear();
        attachStates.clear();
        animStates.clear();
        blockingColliders.clear();
        frictionZones.clear();
        climbZones.clear();
        knockbacks.clear();
        pointClickConfigs.clear();
        interactTriggers.clear();
        tweenEndTriggers.clear();
        timeScale = 1.0f;
        clearCameraClamp();
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
     * Advances tweens, WASD input movement, 2D physics (with fixed-timestep substepping),
     * AI behaviors, camera follow/clamp, and collision/zone triggers by {@code dt} seconds.
     *
     * @param dt    frame delta in seconds
     * @param input frame motion input (defaults to {@link MotionInput#NONE} if null)
     * @return immutable list of target label names triggered by new collisions this step
     */
    public List<String> update(double dt, MotionInput input) {
        if (dt <= 0.0 || !Double.isFinite(dt)) {
            return List.of();
        }
        double scaledDt = dt * timeScale;
        if (scaledDt <= 0.0 || !Double.isFinite(scaledDt)) {
            return List.of();
        }
        MotionInput safeInput = input != null ? input : MotionInput.NONE;
        int steps = Math.max(1, Math.min(MAX_PHYSICS_SUBSTEPS, (int) Math.ceil(scaledDt / FIXED_PHYSICS_STEP)));
        double subDt = scaledDt / steps;
        List<String> allJumps = null;
        for (int i = 0; i < steps; i++) {
            MotionInput stepInput = i == 0 ? safeInput : safeInput.heldOnly();
            updateTweens(subDt);
            updateMovingPlatforms(subDt);
            updatePatrols(subDt);
            updateFollows(subDt);
            updateAttracts(subDt);
            updateWasd(subDt, stepInput);
            updateKnockbacks(subDt);
            updatePhysics(subDt, stepInput);
            updateAttachedChildren();
            updateAnimStates();
            updateCameraFollow(subDt);
            checkInteractTriggers(stepInput);
            List<String> jumps = checkCollisions();
            if (!jumps.isEmpty()) {
                if (allJumps == null) {
                    allJumps = new ArrayList<>(jumps);
                } else {
                    allJumps.addAll(jumps);
                }
            }
        }
        return allJumps == null ? List.of() : Collections.unmodifiableList(allJumps);
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
            double rawT = tween.elapsed >= tween.durationSeconds - 1e-9
                    ? 1.0
                    : Math.min(1.0, tween.elapsed / tween.durationSeconds);
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
            double rawT = tween.elapsed >= tween.durationSeconds - 1e-9
                    ? 1.0
                    : Math.min(1.0, tween.elapsed / tween.durationSeconds);
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
            double rawT = tween.elapsed >= tween.durationSeconds - 1e-9
                    ? 1.0
                    : Math.min(1.0, tween.elapsed / tween.durationSeconds);
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
        if (!tweenEndTriggers.isEmpty() && !hasActiveTween(tag)) {
            Iterator<TweenEndTrigger> it = tweenEndTriggers.iterator();
            List<TweenEndTrigger> fired = null;
            while (it.hasNext()) {
                TweenEndTrigger trigger = it.next();
                if (trigger.tag().equals(tag)) {
                    it.remove();
                    if (fired == null) {
                        fired = new ArrayList<>(2);
                    }
                    fired.add(trigger);
                }
            }
            if (fired != null) {
                for (TweenEndTrigger trigger : fired) {
                    if (trigger.callAction() && subroutineCallListener != null) {
                        subroutineCallListener.accept(trigger.targetLabel());
                    } else if (!trigger.callAction() && collisionJumpListener != null) {
                        collisionJumpListener.accept(trigger.targetLabel());
                    }
                }
            }
        }
    }

    private void updateMovingPlatforms(double dt) {
        if (movingPlatforms.isEmpty()) {
            return;
        }
        float dtF = (float) dt;
        for (MovingPlatformState mp : movingPlatforms.values()) {
            SpriteNode platSprite = activeSprites.get(mp.tag);
            if (platSprite == null || mp.distance <= 1e-4f || mp.speedPxPerSec <= 0.0f) {
                continue;
            }
            normalizeToAbsoluteCoords(platSprite);
            float prevX = platSprite.getTransform().getXpos();
            float prevY = platSprite.getTransform().getYpos();
            Rect2f oldPlatBounds = resolveSpriteBounds(platSprite);
            float oldTop = oldPlatBounds.y();

            mp.travelled += mp.direction * mp.speedPxPerSec * dtF;
            if (mp.travelled >= mp.distance) {
                mp.travelled = mp.distance - (mp.travelled - mp.distance);
                mp.direction = -1.0f;
            } else if (mp.travelled <= 0.0f) {
                mp.travelled = -mp.travelled;
                mp.direction = 1.0f;
            }
            float t = Math.clamp(mp.travelled / mp.distance, 0.0f, 1.0f);
            float nextX = mp.x1 + (mp.x2 - mp.x1) * t;
            float nextY = mp.y1 + (mp.y2 - mp.y1) * t;
            platSprite.getTransform().setXpos(nextX).setYpos(nextY);
            float platDx = nextX - prevX;
            float platDy = nextY - prevY;
            if (platDx == 0.0f && platDy == 0.0f) {
                continue;
            }

            for (Map.Entry<String, PhysicsBody> entry : physicsBodies.entrySet()) {
                String riderTag = entry.getKey();
                if (riderTag.equals(mp.tag)) {
                    continue;
                }
                SpriteNode rider = activeSprites.get(riderTag);
                if (rider == null) {
                    continue;
                }
                PhysicsBody body = entry.getValue();
                if (!body.grounded || body.velocityY < 0.0f) {
                    continue;
                }
                Rect2f rBounds = resolveSpriteBounds(rider);
                boolean horiz = rBounds.x() < oldPlatBounds.x() + oldPlatBounds.width()
                        && rBounds.x() + rBounds.width() > oldPlatBounds.x();
                float riderFoot = rBounds.y() + rBounds.height();
                if (horiz && Math.abs(riderFoot - oldTop) <= 3.0f) {
                    Transform2D rtf = rider.getTransform();
                    rtf.setXpos(rtf.getXpos() + platDx).setYpos(rtf.getYpos() + platDy);
                }
            }
        }
    }

    private void updatePatrols(double dt) {
        if (patrolStates.isEmpty()) {
            return;
        }
        float dtF = (float) dt;
        for (PatrolState patrol : patrolStates.values()) {
            SpriteNode sprite = activeSprites.get(patrol.tag);
            if (sprite == null || patrol.distance <= 1e-4f || patrol.speedPxPerSec <= 0.0f) {
                continue;
            }
            normalizeToAbsoluteCoords(sprite);
            patrol.travelled += patrol.direction * patrol.speedPxPerSec * dtF;
            if (patrol.pingPong) {
                while (patrol.travelled > patrol.distance || patrol.travelled < 0.0f) {
                    if (patrol.travelled > patrol.distance) {
                        patrol.travelled = patrol.distance - (patrol.travelled - patrol.distance);
                        patrol.direction = -1.0f;
                    } else if (patrol.travelled < 0.0f) {
                        patrol.travelled = -patrol.travelled;
                        patrol.direction = 1.0f;
                    }
                }
            } else {
                patrol.travelled = patrol.travelled % patrol.distance;
                if (patrol.travelled < 0.0f) {
                    patrol.travelled += patrol.distance;
                }
            }
            float t = Math.clamp(patrol.travelled / patrol.distance, 0.0f, 1.0f);
            float nx = patrol.x1 + (patrol.x2 - patrol.x1) * t;
            float ny = patrol.y1 + (patrol.y2 - patrol.y1) * t;
            sprite.getTransform().setXpos(nx).setYpos(ny);
        }
    }

    private void updateFollows(double dt) {
        if (followStates.isEmpty()) {
            return;
        }
        float dtF = (float) dt;
        for (FollowState follow : followStates.values()) {
            SpriteNode follower = activeSprites.get(follow.tag);
            SpriteNode target = activeSprites.get(follow.targetTag);
            if (follower == null || target == null || follow.speedPxPerSec <= 0.0f) {
                continue;
            }
            normalizeToAbsoluteCoords(follower);
            normalizeToAbsoluteCoords(target);
            Transform2D ftf = follower.getTransform();
            Transform2D ttf = target.getTransform();
            float dx = ttf.getXpos() - ftf.getXpos();
            float dy = ttf.getYpos() - ftf.getYpos();
            float dist = (float) Math.hypot(dx, dy);
            if (follow.moving && dist <= follow.stopRadius) {
                follow.moving = false;
                continue;
            }
            if (!follow.moving && dist > follow.resumeRadius) {
                follow.moving = true;
            }
            if (!follow.moving || dist <= 1e-4f) {
                continue;
            }
            float step = Math.min(dist - follow.stopRadius * 0.5f, follow.speedPxPerSec * dtF);
            step = Math.max(0.0f, step);
            ftf.setXpos(ftf.getXpos() + (dx / dist) * step)
                    .setYpos(ftf.getYpos() + (dy / dist) * step);
        }
    }

    private void updateAttracts(double dt) {
        if (attractStates.isEmpty()) {
            return;
        }
        float dtF = (float) dt;
        for (AttractState attract : attractStates.values()) {
            SpriteNode pickup = activeSprites.get(attract.tag());
            SpriteNode target = activeSprites.get(attract.targetTag());
            if (pickup == null || target == null) {
                continue;
            }
            normalizeToAbsoluteCoords(pickup);
            normalizeToAbsoluteCoords(target);
            Transform2D ptf = pickup.getTransform();
            Transform2D ttf = target.getTransform();
            float dx = ttf.getXpos() - ptf.getXpos();
            float dy = ttf.getYpos() - ptf.getYpos();
            float dist = (float) Math.hypot(dx, dy);
            if (dist <= 1e-4f || dist > attract.radiusPx()) {
                continue;
            }
            float step = Math.min(dist, attract.speedPxPerSec() * dtF);
            ptf.setXpos(ptf.getXpos() + (dx / dist) * step)
                    .setYpos(ptf.getYpos() + (dy / dist) * step);
        }
    }

    private void updateWasd(double dt, MotionInput input) {
        float rawDx = (input.right() ? 1.0f : 0.0f) - (input.left() ? 1.0f : 0.0f);
        float rawDy = (input.down() ? 1.0f : 0.0f) - (input.up() ? 1.0f : 0.0f);
        if (Float.isFinite(input.analogX()) && input.analogX() != 0.0f) {
            rawDx = Math.clamp(rawDx + input.analogX(), -1.0f, 1.0f);
        }
        if (Float.isFinite(input.analogY()) && input.analogY() != 0.0f) {
            rawDy = Math.clamp(rawDy + input.analogY(), -1.0f, 1.0f);
        }
        float dtF = (float) dt;

        for (Map.Entry<String, WasdBinding> entry : wasdBindings.entrySet()) {
            String tag = entry.getKey();
            SpriteNode sprite = activeSprites.get(tag);
            if (sprite == null) {
                continue;
            }
            WasdBinding binding = entry.getValue();
            normalizeToAbsoluteCoords(sprite);

            if (binding.dashTimer > 0.0f) {
                binding.dashTimer = Math.max(0.0f, binding.dashTimer - dtF);
            }
            if (binding.dashCooldownTimer > 0.0f) {
                binding.dashCooldownTimer = Math.max(0.0f, binding.dashCooldownTimer - dtF);
            }
            if (input.dashPressed() && binding.dashCooldownTimer <= 0.0f) {
                binding.dashTimer = binding.dashDurationSeconds;
                binding.dashCooldownTimer = binding.dashCooldownSeconds;
            }
            float effectiveSpeed = binding.speedPxPerSec
                    * (binding.dashTimer > 0.0f ? binding.dashSpeedMultiplier : 1.0f);

            boolean hasPhysics = physicsBodies.containsKey(tag);
            boolean inClimb = isSpriteInClimbZone(tag, sprite);
            float stepDx = rawDx;
            float stepDy = (hasPhysics && !inClimb) ? 0.0f : rawDy;
            if (binding.normalizeDiagonal && stepDx != 0.0f && stepDy != 0.0f) {
                float mag = (float) Math.hypot(stepDx, stepDy);
                if (mag > 1.0f) {
                    stepDx /= mag;
                    stepDy /= mag;
                }
            }

            float zoneFrictionFactor = resolveFrictionMultiplier(tag, sprite);
            float effectiveFriction = binding.friction * zoneFrictionFactor;
            float effectiveAccel = binding.acceleration * zoneFrictionFactor;

            boolean inertiaMode = effectiveFriction > 0.0f || effectiveAccel > 0.0f;
            if (!inertiaMode) {
                if (stepDx == 0.0f && stepDy == 0.0f) {
                    binding.velX = 0.0f;
                    binding.velY = 0.0f;
                    continue;
                }
                binding.velX = stepDx * effectiveSpeed;
                binding.velY = stepDy * effectiveSpeed;
            } else {
                float targetVx = stepDx * effectiveSpeed;
                float targetVy = stepDy * effectiveSpeed;
                binding.velX = stepAxisVelocity(binding.velX, targetVx, stepDx != 0.0f,
                        effectiveAccel, effectiveFriction, dtF);
                binding.velY = (hasPhysics && !inClimb) ? 0.0f : stepAxisVelocity(
                        binding.velY, targetVy, stepDy != 0.0f, effectiveAccel, effectiveFriction, dtF);
                if (binding.velX == 0.0f && binding.velY == 0.0f) {
                    continue;
                }
            }

            if (binding.autoFlip && Math.abs(binding.velX) > 1e-3f) {
                Transform2D tf = sprite.getTransform();
                float magZoom = Math.max(1e-3f, Math.abs(tf.getXzoom()));
                tf.setXzoom(binding.velX < 0.0f ? -magZoom : magZoom);
            }

            moveSpriteWithCollidersAndBounds(tag, sprite, binding, binding.velX * dtF, binding.velY * dtF);
        }
    }

    private void moveSpriteWithCollidersAndBounds(
            String tag,
            SpriteNode sprite,
            WasdBinding binding,
            float deltaX,
            float deltaY
    ) {
        Transform2D tf = sprite.getTransform();
        float oldX = tf.getXpos();
        float targetX = oldX + deltaX;
        if (binding.hasBounds) {
            if (targetX < binding.minX) {
                targetX = binding.minX;
                binding.velX = 0.0f;
            } else if (targetX > binding.maxX) {
                targetX = binding.maxX;
                binding.velX = 0.0f;
            }
        }
        tf.setXpos(targetX);
        if (!blockingColliders.isEmpty() && overlapsAnyBlockingCollider(tag, sprite)) {
            tf.setXpos(oldX);
            binding.velX = 0.0f;
        }

        float oldY = tf.getYpos();
        float targetY = oldY + deltaY;
        if (binding.hasBounds) {
            if (targetY < binding.minY) {
                targetY = binding.minY;
                binding.velY = 0.0f;
            } else if (targetY > binding.maxY) {
                targetY = binding.maxY;
                binding.velY = 0.0f;
            }
        }
        tf.setYpos(targetY);
        if (!blockingColliders.isEmpty() && overlapsAnyBlockingCollider(tag, sprite)) {
            tf.setYpos(oldY);
            binding.velY = 0.0f;
        }
    }

    private boolean overlapsAnyBlockingCollider(String tag, SpriteNode sprite) {
        Rect2f hitbox = toHitboxRect(resolveSpriteBounds(sprite), customHitboxes.get(tag));
        for (List<Rect2f> boxes : blockingColliders.values()) {
            for (Rect2f box : boxes) {
                if (rectsOverlap(hitbox, box)) {
                    return true;
                }
            }
        }
        return false;
    }

    private float resolveFrictionMultiplier(String tag, SpriteNode sprite) {
        if (frictionZones.isEmpty()) {
            return 1.0f;
        }
        Rect2f hitbox = toHitboxRect(resolveSpriteBounds(sprite), customHitboxes.get(tag));
        for (FrictionZone zone : frictionZones.values()) {
            if (rectsOverlap(hitbox, zone.rect())) {
                return zone.factor();
            }
        }
        return 1.0f;
    }

    private boolean isSpriteInClimbZone(String tag, SpriteNode sprite) {
        if (climbZones.isEmpty()) {
            return false;
        }
        Rect2f hitbox = toHitboxRect(resolveSpriteBounds(sprite), customHitboxes.get(tag));
        for (Rect2f zone : climbZones.values()) {
            if (rectsOverlap(hitbox, zone)) {
                return true;
            }
        }
        return false;
    }

    private void updateKnockbacks(double dt) {
        if (knockbacks.isEmpty()) {
            return;
        }
        float dtF = (float) dt;
        Iterator<Map.Entry<String, KnockbackState>> it = knockbacks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, KnockbackState> entry = it.next();
            String tag = entry.getKey();
            KnockbackState kb = entry.getValue();
            kb.invulnTimer = Math.max(0.0f, kb.invulnTimer - dtF);

            SpriteNode sprite = activeSprites.get(tag);
            if (sprite != null && (Math.abs(kb.vx) > 0.01f || Math.abs(kb.vy) > 0.01f)) {
                normalizeToAbsoluteCoords(sprite);
                Transform2D tf = sprite.getTransform();
                float nx = tf.getXpos() + kb.vx * dtF;
                float ny = tf.getYpos() + kb.vy * dtF;
                WasdBinding bounds = wasdBindings.get(tag);
                if (bounds != null && bounds.hasBounds) {
                    nx = Math.clamp(nx, bounds.minX, bounds.maxX);
                    ny = Math.clamp(ny, bounds.minY, bounds.maxY);
                }
                tf.setXpos(nx).setYpos(ny);
            }
            float decay = (float) Math.exp(-kb.frictionDecel * dtF);
            kb.vx = Math.abs(kb.vx * decay) < 0.5f ? 0.0f : kb.vx * decay;
            kb.vy = Math.abs(kb.vy * decay) < 0.5f ? 0.0f : kb.vy * decay;
            if (kb.vx == 0.0f && kb.vy == 0.0f && kb.invulnTimer <= 0.0f) {
                it.remove();
            }
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
            if (isSpriteInClimbZone(tag, sprite)) {
                body.velocityY = 0.0f;
                body.grounded = true;
                continue;
            }
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

    private void updateAttachedChildren() {
        if (attachStates.isEmpty()) {
            return;
        }
        for (AttachState attach : attachStates.values()) {
            SpriteNode child = activeSprites.get(attach.childTag());
            SpriteNode parent = activeSprites.get(attach.parentTag());
            if (child == null || parent == null) {
                continue;
            }
            normalizeToAbsoluteCoords(parent);
            Transform2D ptf = parent.getTransform();
            double rad = Math.toRadians(ptf.getRotate());
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            float rx = attach.offsetX() * cos - attach.offsetY() * sin;
            float ry = attach.offsetX() * sin + attach.offsetY() * cos;
            applyAbsolutePos(child, ptf.getXpos() + rx, ptf.getYpos() + ry);
            child.getTransform().setRotate(ptf.getRotate());
        }
    }

    private void updateAnimStates() {
        if (animStates.isEmpty()) {
            return;
        }
        for (Map.Entry<String, AnimStateConfig> entry : animStates.entrySet()) {
            String tag = entry.getKey();
            AnimStateConfig cfg = entry.getValue();
            PhysicsBody body = physicsBodies.get(tag);
            WasdBinding wasd = wasdBindings.get(tag);
            String resolved;
            if (body != null && !body.grounded) {
                resolved = cfg.jumpAttr;
            } else if ((wasd != null && Math.abs(wasd.velX) > 1e-2f) || activeTweens.containsKey(tag)) {
                resolved = cfg.walkAttr;
            } else {
                resolved = cfg.idleAttr;
            }
            if (!Objects.equals(cfg.currentState, resolved)) {
                cfg.currentState = resolved;
                if (animStateListener != null) {
                    animStateListener.accept(tag, resolved);
                }
            }
        }
    }

    private void checkInteractTriggers(MotionInput input) {
        if (!input.interactPressed() || interactTriggers.isEmpty()) {
            return;
        }
        String actorTag = getPrimaryWasdTag();
        if (actorTag == null && !physicsBodies.isEmpty()) {
            actorTag = physicsBodies.keySet().iterator().next();
        }
        SpriteNode actor = actorTag != null ? activeSprites.get(actorTag) : null;
        if (actor == null) {
            return;
        }
        normalizeToAbsoluteCoords(actor);
        float ax = actor.getTransform().getXpos();
        float ay = actor.getTransform().getYpos();
        for (InteractTrigger trigger : List.copyOf(interactTriggers)) {
            SpriteNode target = activeSprites.get(trigger.targetTag());
            if (target == null) {
                continue;
            }
            normalizeToAbsoluteCoords(target);
            float dx = target.getTransform().getXpos() - ax;
            float dy = target.getTransform().getYpos() - ay;
            if (Math.hypot(dx, dy) <= trigger.maxDistancePx()) {
                if (trigger.callAction() && subroutineCallListener != null) {
                    subroutineCallListener.accept(trigger.targetLabel());
                } else if (!trigger.callAction() && collisionJumpListener != null) {
                    collisionJumpListener.accept(trigger.targetLabel());
                }
                break;
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
        clampCameraOffsets();
    }

    private void clampCameraOffsets() {
        if (!cameraClampActive) {
            return;
        }
        cameraOffsetX = Math.clamp(cameraOffsetX, cameraClampMinX, cameraClampMaxX);
        cameraOffsetY = Math.clamp(cameraOffsetY, cameraClampMinY, cameraClampMaxY);
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
            case "spring" -> clamped <= 0.0 ? 0.0
                    : (clamped >= 1.0 ? 1.0
                            : 1.0 - Math.exp(-3.2 * clamped) * Math.cos(clamped * Math.PI * 1.5));
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
