package de.swfdtf.graphics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Modular plugin adapter contract for 2D skeletal and mesh-deformed portrait/character runtimes
 * compatible with Spine, DragonBones, and custom procedural rig backends ({@code #0213}).
 */
public interface Skeletal2DAdapter {

    /**
     * Resolved world-space 2D bone transform produced by the skeletal backend.
     *
     * @param name        bone identifier
     * @param parentName  optional parent bone name (nullable for root)
     * @param x           world/local canvas X offset relative to displayable origin
     * @param y           world/local canvas Y offset relative to displayable origin
     * @param rotationDeg bone rotation in degrees
     * @param scaleX      horizontal scale multiplier
     * @param scaleY      vertical scale multiplier
     */
    record BonePose(
            String name,
            String parentName,
            float x,
            float y,
            float rotationDeg,
            float scaleX,
            float scaleY
    ) {
    }

    /**
     * Drawable slot attachment bound to a bone.
     *
     * @param slotName       slot identifier
     * @param boneName       controlling bone identifier
     * @param attachmentName active attachment or skin region name
     * @param textureId      OpenGL texture handle ({@code 0} in headless mode)
     * @param alpha          slot opacity in {@code [0, 1]}
     * @param width          attachment width in logical pixels
     * @param height         attachment height in logical pixels
     * @param drawOrder      ascending draw order index
     */
    record SlotAttachment(
            String slotName,
            String boneName,
            String attachmentName,
            int textureId,
            float alpha,
            float width,
            float height,
            int drawOrder
    ) {
    }

    /**
     * Deformed 2D mesh vertex for free-form mesh (FFM) bone-weighted portrait attachments.
     *
     * @param x deformed local X coordinate
     * @param y deformed local Y coordinate
     * @param u normalized texture U coordinate
     * @param v normalized texture V coordinate
     */
    record MeshDeformVertex(float x, float y, float u, float v) {
    }

    /**
     * Returns the runtime backend format identifier (e.g. {@code "Spine"}, {@code "DragonBones"}).
     *
     * @return skeletal backend format name
     */
    String getFormatName();

    /**
     * Switches or crossfades to {@code animationName}.
     *
     * @param animationName      target animation clip name
     * @param loop               whether playback loops
     * @param mixDurationSeconds crossfade mix time in seconds
     */
    void setAnimation(String animationName, boolean loop, double mixDurationSeconds);

    /**
     * Returns the currently active animation clip name.
     *
     * @return active animation name, or empty string if none
     */
    String getCurrentAnimation();

    /**
     * Advances skeletal timeline playback by {@code dt} seconds.
     *
     * @param dt elapsed frame delta in seconds
     */
    void update(double dt);

    /**
     * Returns an immutable list of resolved {@link BonePose} transforms for the current frame.
     *
     * @return immutable bone pose list
     */
    List<BonePose> getWorldBonePoses();

    /**
     * Returns an immutable list of resolved {@link SlotAttachment}s sorted in ascending draw order.
     *
     * @return immutable slot list
     */
    List<SlotAttachment> getResolvedSlots();

    /**
     * Returns optional mesh-deformed vertices for {@code slotName}, or an empty list for rigid quads.
     *
     * @param slotName slot identifier
     * @return immutable deformed vertex list
     */
    default List<MeshDeformVertex> getSlotMeshVertices(String slotName) {
        return List.of();
    }

    /**
     * Returns preferred logical width for layout when embedded inside a {@link SpriteNode}.
     *
     * @return preferred width in logical pixels
     */
    default float getPreferredWidth() {
        return 0.0f;
    }

    /**
     * Returns preferred logical height for layout when embedded inside a {@link SpriteNode}.
     *
     * @return preferred height in logical pixels
     */
    default float getPreferredHeight() {
        return 0.0f;
    }

    /**
     * Built-in lightweight Spine/DragonBones-compatible keyframe rig adapter ready for tests and
     * procedural plugin prototypes.
     */
    final class SimpleRigAdapter implements Skeletal2DAdapter {

        private final String formatName;
        private final float preferredWidth;
        private final float preferredHeight;
        private final Map<String, BonePose> bones = new LinkedHashMap<>();
        private final Map<String, SlotAttachment> slots = new LinkedHashMap<>();
        private final Map<String, Double> clipDurations = new LinkedHashMap<>();
        private String currentAnimation = "idle";
        private boolean looping = true;
        private double playbackTime = 0.0;
        private double mixRemaining = 0.0;

        /**
         * Creates a built-in skeletal 2D rig adapter.
         *
         * @param formatName      format tag (e.g. {@code "Spine-JSON"} or {@code "DragonBones"})
         * @param preferredWidth  natural portrait width in logical pixels
         * @param preferredHeight natural portrait height in logical pixels
         */
        public SimpleRigAdapter(String formatName, float preferredWidth, float preferredHeight) {
            this.formatName = Objects.requireNonNull(formatName, "formatName must not be null");
            this.preferredWidth = Math.max(0.0f, preferredWidth);
            this.preferredHeight = Math.max(0.0f, preferredHeight);
            bones.put("root", new BonePose("root", null, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f));
        }

        /**
         * Registers or updates a bone definition.
         *
         * @param bone bone pose
         * @return {@code this} for fluent chaining
         */
        public SimpleRigAdapter putBone(BonePose bone) {
            Objects.requireNonNull(bone, "bone must not be null");
            bones.put(bone.name(), bone);
            return this;
        }

        /**
         * Registers or updates a slot attachment.
         *
         * @param slot slot attachment
         * @return {@code this} for fluent chaining
         */
        public SimpleRigAdapter putSlot(SlotAttachment slot) {
            Objects.requireNonNull(slot, "slot must not be null");
            slots.put(slot.slotName(), slot);
            return this;
        }

        /**
         * Registers an animation clip duration in seconds.
         *
         * @param animationName clip identifier
         * @param durationSec   duration in seconds ({@code > 0})
         * @return {@code this} for fluent chaining
         */
        public SimpleRigAdapter registerClip(String animationName, double durationSec) {
            Objects.requireNonNull(animationName, "animationName must not be null");
            clipDurations.put(animationName, Math.max(0.05, durationSec));
            return this;
        }

        @Override
        public String getFormatName() {
            return formatName;
        }

        @Override
        public void setAnimation(String animationName, boolean loop, double mixDurationSeconds) {
            this.currentAnimation = animationName != null ? animationName : "";
            this.looping = loop;
            this.playbackTime = 0.0;
            this.mixRemaining = Math.max(0.0, mixDurationSeconds);
        }

        @Override
        public String getCurrentAnimation() {
            return currentAnimation;
        }

        @Override
        public void update(double dt) {
            if (!Double.isFinite(dt) || dt <= 0.0) {
                return;
            }
            playbackTime += dt;
            if (mixRemaining > 0.0) {
                mixRemaining = Math.max(0.0, mixRemaining - dt);
            }
            double duration = clipDurations.getOrDefault(currentAnimation, 1.0);
            if (looping && duration > 0.0) {
                playbackTime %= duration;
            } else {
                playbackTime = Math.min(playbackTime, duration);
            }
        }

        /**
         * Returns current normalized clip progress in {@code [0, 1]}.
         *
         * @return normalized clip progress
         */
        public double getNormalizedTime() {
            double duration = clipDurations.getOrDefault(currentAnimation, 1.0);
            return duration > 0.0 ? Math.clamp(playbackTime / duration, 0.0, 1.0) : 0.0;
        }

        /**
         * Returns remaining mix transition time in seconds.
         *
         * @return remaining mix duration
         */
        public double getMixRemaining() {
            return mixRemaining;
        }

        @Override
        public List<BonePose> getWorldBonePoses() {
            float sway = (float) Math.sin(playbackTime * Math.PI * 2.0) * 3.0f;
            return bones.values().stream()
                    .map(b -> "root".equals(b.name())
                            ? b
                            : new BonePose(
                                    b.name(),
                                    b.parentName(),
                                    b.x(),
                                    b.y(),
                                    b.rotationDeg() + sway,
                                    b.scaleX(),
                                    b.scaleY()))
                    .toList();
        }

        @Override
        public List<SlotAttachment> getResolvedSlots() {
            return Collections.unmodifiableList(
                    slots.values().stream()
                            .sorted((a, b) -> Integer.compare(a.drawOrder(), b.drawOrder()))
                            .toList()
            );
        }

        @Override
        public float getPreferredWidth() {
            return preferredWidth;
        }

        @Override
        public float getPreferredHeight() {
            return preferredHeight;
        }
    }
}
