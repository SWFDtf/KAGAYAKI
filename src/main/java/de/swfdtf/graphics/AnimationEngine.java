package de.swfdtf.graphics;

import de.swfdtf.math.Transform2D;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Central manager updating active {@link ATLTransform} timelines bound to {@link SceneNode}s.
 *
 * <p>Each animation is tracked by a unique string identifier. Calling {@link #play(String, SceneNode, ATLTransform)}
 * immediately starts the animation against the node's {@link Transform2D}; subsequent calls to
 * {@link #update(double)} advance all active animations and automatically remove finished ones.</p>
 */
public final class AnimationEngine {

    private static final class ActiveAnimation {
        private final SceneNode node;
        private final ATLTransform animation;
        private final Runnable onComplete;

        private ActiveAnimation(SceneNode node, ATLTransform animation, Runnable onComplete) {
            this.node = node;
            this.animation = animation;
            this.onComplete = onComplete;
        }
    }

    private final Map<String, ActiveAnimation> activeAnimations = new LinkedHashMap<>();

    /**
     * Starts and registers an {@link ATLTransform} bound to {@code node} under identifier {@code id}.
     * If an animation with the same {@code id} is already active, it is replaced.
     *
     * @param id   unique animation identifier (non-null, non-blank)
     * @param node target scene node whose {@link Transform2D} will be animated (non-null)
     * @param anim ATL animation timeline (non-null)
     */
    public void play(String id, SceneNode node, ATLTransform anim) {
        play(id, node, anim, null);
    }

    /**
     * Starts and registers an {@link ATLTransform} bound to {@code node} with an optional completion callback.
     *
     * @param id         unique animation identifier (non-null, non-blank)
     * @param node       target scene node (non-null)
     * @param anim       ATL animation timeline (non-null)
     * @param onComplete optional callback invoked when the animation completes naturally (may be {@code null})
     */
    public void play(String id, SceneNode node, ATLTransform anim, Runnable onComplete) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Animation id must not be null or blank");
        }
        Objects.requireNonNull(node, "node must not be null");
        Objects.requireNonNull(anim, "anim must not be null");

        Transform2D transform = node.getTransform();
        anim.start(transform);
        if (anim.isFinished()) {
            activeAnimations.remove(id);
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        activeAnimations.put(id, new ActiveAnimation(node, anim, onComplete));
    }

    /**
     * Stops and removes the active animation associated with {@code id}.
     *
     * @param id animation identifier
     * @return {@code true} if an active animation was stopped and removed
     */
    public boolean stop(String id) {
        if (id == null) {
            return false;
        }
        return activeAnimations.remove(id) != null;
    }

    /**
     * Stops and clears all active animations.
     */
    public void stopAll() {
        activeAnimations.clear();
    }

    /**
     * Advances all active animations by {@code dt} seconds and removes any animations that finish.
     *
     * @param dt elapsed frame delta in seconds
     */
    public void update(double dt) {
        if (activeAnimations.isEmpty()) {
            return;
        }
        List<Map.Entry<String, ActiveAnimation>> snapshot = new ArrayList<>(activeAnimations.entrySet());
        for (Map.Entry<String, ActiveAnimation> entry : snapshot) {
            String id = entry.getKey();
            ActiveAnimation active = entry.getValue();
            if (activeAnimations.get(id) != active) {
                continue;
            }
            active.animation.update(dt, active.node.getTransform());
            if (active.animation.isFinished()) {
                activeAnimations.remove(id, active);
                if (active.onComplete != null) {
                    active.onComplete.run();
                }
            }
        }
    }

    /**
     * Returns whether an animation with {@code id} is currently active and unfinished.
     *
     * @param id animation identifier
     * @return {@code true} if currently playing
     */
    public boolean isPlaying(String id) {
        if (id == null) {
            return false;
        }
        ActiveAnimation active = activeAnimations.get(id);
        return active != null && !active.animation.isFinished();
    }

    /**
     * Returns the number of currently active animations.
     *
     * @return active animation count
     */
    public int getActiveCount() {
        return activeAnimations.size();
    }

    /**
     * Returns the active {@link ATLTransform} registered under {@code id}, or {@code null}.
     *
     * @param id animation identifier
     * @return active {@link ATLTransform}, or {@code null}
     */
    public ATLTransform getAnimation(String id) {
        ActiveAnimation active = id != null ? activeAnimations.get(id) : null;
        return active != null ? active.animation : null;
    }

    /**
     * Returns an unmodifiable snapshot of currently active animation IDs in registration order.
     *
     * @return active animation identifiers
     */
    public List<String> getActiveIds() {
        return Collections.unmodifiableList(new ArrayList<>(activeAnimations.keySet()));
    }
}
