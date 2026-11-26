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
 * timed position tweens ({@code move} / {@code move_by}), continuous WASD / arrow-key movement
 * ({@code wasd_init} / {@code wasd_stop}), lightweight 2D gravity &amp; jump physics
 * ({@code physics_init} / {@code physics_stop}), and AABB collision triggers
 * ({@code on_collide ... jump ...}).
 *
 * <p>All simulation math operates purely on {@link SpriteNode} transforms in logical
 * {@code 1920x1080} stage coordinates and has no GLFW or OpenGL dependency so it runs
 * identically in headless tests and live GPU sessions.</p>
 */
public final class ObjectMotionController {

    /** Supported easing curves for {@code move} and {@code move_by} tweens. */
    public static final Set<String> SUPPORTED_EASINGS = Set.of(
            "linear", "ease_in", "ease_out", "ease_in_out"
    );

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

    private static final class MoveTween {
        private final String tag;
        private float startX;
        private float startY;
        private float endX;
        private float endY;
        private final boolean relative;
        private final float rawX;
        private final float rawY;
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

        private WasdBinding(
                float speedPxPerSec,
                Float minX,
                Float minY,
                Float maxX,
                Float maxY,
                boolean normalizeDiagonal
        ) {
            this.speedPxPerSec = Math.max(1.0f, speedPxPerSec);
            this.hasBounds = minX != null && minY != null && maxX != null && maxY != null;
            this.minX = minX != null ? minX : 0.0f;
            this.minY = minY != null ? minY : 0.0f;
            this.maxX = maxX != null ? maxX : LOGICAL_WIDTH;
            this.maxY = maxY != null ? maxY : LOGICAL_HEIGHT;
            this.normalizeDiagonal = normalizeDiagonal;
        }
    }

    private static final class PhysicsBody {
        private final float gravityPxPerSec2;
        private final float jumpForcePxPerSec;
        private final float groundY;
        private float velocityY = 0.0f;
        private boolean grounded = false;

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

    private final Map<String, SpriteNode> activeSprites;
    private final Map<String, MoveTween> activeTweens = new LinkedHashMap<>();
    private final Map<String, WasdBinding> wasdBindings = new LinkedHashMap<>();
    private final Map<String, PhysicsBody> physicsBodies = new LinkedHashMap<>();
    private final List<CollisionTrigger> collisionTriggers = new ArrayList<>();
    private Consumer<String> collisionJumpListener;

    /**
     * Creates an {@code ObjectMotionController} backed by {@code activeSprites}.
     *
     * @param activeSprites live stage sprite lookup map owned by {@link SceneController}
     */
    public ObjectMotionController(Map<String, SpriteNode> activeSprites) {
        this.activeSprites = Objects.requireNonNull(activeSprites, "activeSprites must not be null");
    }

    /**
     * Starts an absolute or relative movement tween for the sprite identified by {@code tag}.
     * If {@code durationSeconds <= 0}, the sprite snaps to the target position immediately when present.
     *
     * @param tag             semantic sprite tag
     * @param x               target X position or relative delta X
     * @param y               target Y position or relative delta Y
     * @param relative        {@code true} for {@code move_by}, {@code false} for {@code move ... to}
     * @param durationSeconds duration in seconds ({@code >= 0})
     * @param easing          easing name ({@code linear}, {@code ease_in}, {@code ease_out}, {@code ease_in_out})
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
            initTweenEndpoints(tween, sprite);
            if (tween.durationSeconds <= 0.0) {
                applyAbsolutePos(sprite, tween.endX, tween.endY);
                return;
            }
        }
        activeTweens.put(tag, tween);
    }

    /**
     * Enables WASD / arrow-key movement for {@code tag}.
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
        Objects.requireNonNull(tag, "tag must not be null");
        WasdBinding binding = new WasdBinding(speedPxPerSec, minX, minY, maxX, maxY, normalizeDiagonal);
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
     * Registers an AABB collision trigger that fires {@code targetLabel} on initial overlap
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
     * Sets the listener notified with the destination label name whenever a collision trigger fires.
     *
     * @param listener callback receiving the target label name (nullable)
     */
    public void setCollisionJumpListener(Consumer<String> listener) {
        this.collisionJumpListener = listener;
    }

    /** Clears all active tweens, WASD bindings, physics bodies, and collision triggers. */
    public void clearAll() {
        activeTweens.clear();
        wasdBindings.clear();
        physicsBodies.clear();
        collisionTriggers.clear();
    }

    /**
     * Advances tweens, WASD input movement, 2D physics, and collision triggers by {@code dt} seconds.
     *
     * @param dt    frame delta in seconds
     * @param input frame motion input (defaults to {@link MotionInput#NONE} if null)
     * @return immutable list of target label names triggered by new AABB collisions this step
     */
    public List<String> update(double dt, MotionInput input) {
        if (dt <= 0.0) {
            return List.of();
        }
        MotionInput safeInput = input != null ? input : MotionInput.NONE;
        updateTweens(dt);
        updateWasd(dt, safeInput);
        updatePhysics(dt, safeInput);
        return checkCollisions();
    }

    private void updateTweens(double dt) {
        Iterator<Map.Entry<String, MoveTween>> it = activeTweens.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, MoveTween> entry = it.next();
            MoveTween tween = entry.getValue();
            SpriteNode sprite = activeSprites.get(tween.tag);
            if (sprite == null) {
                continue;
            }
            if (!tween.initializedStart) {
                initTweenEndpoints(tween, sprite);
            }
            if (tween.durationSeconds <= 0.0) {
                applyAbsolutePos(sprite, tween.endX, tween.endY);
                it.remove();
                continue;
            }
            tween.elapsed += dt;
            double rawT = Math.min(1.0, tween.elapsed / tween.durationSeconds);
            float easedT = (float) applyEasing(tween.easing, rawT);
            float nx = tween.startX + (tween.endX - tween.startX) * easedT;
            float ny = tween.startY + (tween.endY - tween.startY) * easedT;
            applyAbsolutePos(sprite, nx, ny);
            if (rawT >= 1.0) {
                it.remove();
            }
        }
    }

    private void updateWasd(double dt, MotionInput input) {
        float dx = (input.right() ? 1.0f : 0.0f) - (input.left() ? 1.0f : 0.0f);
        float dy = (input.down() ? 1.0f : 0.0f) - (input.up() ? 1.0f : 0.0f);
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
            if (stepDx == 0.0f && stepDy == 0.0f) {
                continue;
            }
            Transform2D tf = sprite.getTransform();
            float nextX = tf.getXpos() + stepDx * binding.speedPxPerSec * (float) dt;
            float nextY = tf.getYpos() + stepDy * binding.speedPxPerSec * (float) dt;
            if (binding.hasBounds) {
                nextX = Math.max(binding.minX, Math.min(binding.maxX, nextX));
                nextY = Math.max(binding.minY, Math.min(binding.maxY, nextY));
            }
            tf.setXpos(nextX).setYpos(nextY);
        }
    }

    private void updatePhysics(double dt, MotionInput input) {
        float dtF = (float) dt;
        for (Map.Entry<String, PhysicsBody> entry : physicsBodies.entrySet()) {
            SpriteNode sprite = activeSprites.get(entry.getKey());
            if (sprite == null) {
                continue;
            }
            normalizeToAbsoluteCoords(sprite);
            PhysicsBody body = entry.getValue();
            Transform2D tf = sprite.getTransform();

            if (input.jumpPressed() && body.grounded) {
                body.velocityY = -body.jumpForcePxPerSec;
                body.grounded = false;
            }
            body.velocityY += body.gravityPxPerSec2 * dtF;
            float nextY = tf.getYpos() + body.velocityY * dtF;
            if (nextY >= body.groundY) {
                nextY = body.groundY;
                body.velocityY = 0.0f;
                body.grounded = true;
            } else {
                body.grounded = false;
            }

            WasdBinding bounds = wasdBindings.get(entry.getKey());
            if (bounds != null && bounds.hasBounds) {
                nextY = Math.max(bounds.minY, Math.min(bounds.maxY, nextY));
            }
            tf.setYpos(nextY);
        }
    }

    private List<String> checkCollisions() {
        if (collisionTriggers.isEmpty()) {
            return List.of();
        }
        List<String> triggered = new ArrayList<>();
        // Snapshot triggers before dispatching listeners that may jump and mutate triggers.
        List<CollisionTrigger> snapshot = List.copyOf(collisionTriggers);
        for (CollisionTrigger trigger : snapshot) {
            SpriteNode a = activeSprites.get(trigger.tagA);
            SpriteNode b = activeSprites.get(trigger.tagB);
            if (a == null || b == null) {
                trigger.wasOverlapping = false;
                continue;
            }
            boolean overlapping = rectsOverlap(resolveSpriteBounds(a), resolveSpriteBounds(b));
            if (overlapping && !trigger.wasOverlapping) {
                trigger.wasOverlapping = true;
                triggered.add(trigger.targetLabel);
            } else if (!overlapping) {
                trigger.wasOverlapping = false;
            }
        }
        if (collisionJumpListener != null) {
            for (String label : triggered) {
                collisionJumpListener.accept(label);
            }
        }
        return triggered.isEmpty() ? List.of() : Collections.unmodifiableList(triggered);
    }

    private static void initTweenEndpoints(MoveTween tween, SpriteNode sprite) {
        normalizeToAbsoluteCoords(sprite);
        Transform2D tf = sprite.getTransform();
        tween.startX = tf.getXpos();
        tween.startY = tf.getYpos();
        tween.endX = tween.relative ? tween.startX + tween.rawX : tween.rawX;
        tween.endY = tween.relative ? tween.startY + tween.rawY : tween.rawY;
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
     * Returns the number of active movement tweens.
     *
     * @return number of currently running movement tweens
     */
    public int getActiveTweenCount() {
        return activeTweens.size();
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
     * Returns whether the physics body attached to {@code tag} is currently resting on {@code groundY}.
     *
     * @param tag semantic sprite tag
     * @return {@code true} if grounded, {@code false} otherwise or if no physics body is attached
     */
    public boolean isGrounded(String tag) {
        PhysicsBody body = physicsBodies.get(tag);
        return body != null && body.grounded;
    }
}
