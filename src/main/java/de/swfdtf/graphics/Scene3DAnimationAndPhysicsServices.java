package de.swfdtf.graphics;

import de.swfdtf.audio.AudioEngine;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bundled Wave P3-3 3D skeletal skinning, 3D particle projection, camera-audio listener sync,
 * GPU vertex wind animation, and rigid-body prop drop physics ({@code #0276}, {@code #0281},
 * {@code #0283}, {@code #0299}, {@code #0328}).
 */
public final class Scene3DAnimationAndPhysicsServices {

    private Scene3DAnimationAndPhysicsServices() {
    }

    /**
     * glTF-compatible 3D skeletal skinning &amp; joint-matrix crossfade animator supporting up to
     * {@value #MAX_BONES} joints per mesh ({@code #0276}).
     */
    public static final class Skeletal3DAnimator {

        /** Maximum bones supported per skinned 3D mesh in UBO/matrix arrays. */
        public static final int MAX_BONES = 64;

        /**
         * Skeleton joint definition.
         *
         * @param index             zero-based joint index
         * @param name              joint name
         * @param parentIndex       parent joint index ({@code -1} for root)
         * @param inverseBindMatrix inverse bind pose matrix
         * @param restTranslation   rest local translation
         * @param restRotation      rest local quaternion rotation
         * @param restScale         rest local scale
         */
        public record Joint3D(
                int index,
                String name,
                int parentIndex,
                Matrix4f inverseBindMatrix,
                Vector3f restTranslation,
                Quaternionf restRotation,
                Vector3f restScale
        ) {
            /** Validates indices and clones vectors/matrices defensively. */
            public Joint3D {
                Objects.requireNonNull(name, "name must not be null");
                inverseBindMatrix = inverseBindMatrix != null ? new Matrix4f(inverseBindMatrix) : new Matrix4f();
                restTranslation = restTranslation != null ? new Vector3f(restTranslation) : new Vector3f();
                restRotation = restRotation != null ? new Quaternionf(restRotation) : new Quaternionf();
                restScale = restScale != null ? new Vector3f(restScale) : new Vector3f(1.0f, 1.0f, 1.0f);
            }
        }

        /**
         * Single joint TRS keyframe inside an animation track.
         *
         * @param timeSec     timestamp in seconds ({@code >= 0})
         * @param translation local translation
         * @param rotation    local quaternion rotation
         * @param scale       local scale
         */
        public record JointKeyframe(
                double timeSec,
                Vector3f translation,
                Quaternionf rotation,
                Vector3f scale
        ) {
        }

        /**
         * Named animation clip mapping joint indices to sorted keyframe tracks.
         *
         * @param name        clip name
         * @param durationSec clip duration in seconds
         * @param looping     whether clip loops at end
         * @param tracks      joint index to ordered keyframes map
         */
        public record AnimationClip3D(
                String name,
                double durationSec,
                boolean looping,
                Map<Integer, List<JointKeyframe>> tracks
        ) {
            /** Normalizes tracks to immutable sorted lists. */
            public AnimationClip3D {
                Objects.requireNonNull(name, "name must not be null");
                durationSec = Math.max(0.01, durationSec);
                Map<Integer, List<JointKeyframe>> copy = new LinkedHashMap<>();
                if (tracks != null) {
                    for (Map.Entry<Integer, List<JointKeyframe>> entry : tracks.entrySet()) {
                        List<JointKeyframe> sorted = new ArrayList<>(entry.getValue());
                        sorted.sort(Comparator.comparingDouble(JointKeyframe::timeSec));
                        copy.put(entry.getKey(), Collections.unmodifiableList(sorted));
                    }
                }
                tracks = Collections.unmodifiableMap(copy);
            }
        }

        private record TrsPose(Vector3f t, Quaternionf r, Vector3f s) {
        }

        private final List<Joint3D> joints;
        private final Map<String, AnimationClip3D> clips = new LinkedHashMap<>();
        private AnimationClip3D activeClip;
        private double activeTime = 0.0;
        private AnimationClip3D outgoingClip;
        private double outgoingTime = 0.0;
        private double crossfadeElapsed = 0.0;
        private double crossfadeDuration = 0.0;

        /**
         * Constructs an animator for the given joint hierarchy ({@code <= 64} bones).
         *
         * @param joints ordered list of skeleton joints
         */
        public Skeletal3DAnimator(List<Joint3D> joints) {
            Objects.requireNonNull(joints, "joints must not be null");
            if (joints.isEmpty() || joints.size() > MAX_BONES) {
                throw new IllegalArgumentException(
                        "Joint count must be in [1, " + MAX_BONES + "], got " + joints.size());
            }
            this.joints = List.copyOf(joints);
        }

        /**
         * Registers a named glTF-style animation clip.
         *
         * @param clip clip to register
         * @return {@code this} for fluent chaining
         */
        public Skeletal3DAnimator registerClip(AnimationClip3D clip) {
            Objects.requireNonNull(clip, "clip must not be null");
            clips.put(clip.name(), clip);
            if (activeClip == null) {
                activeClip = clip;
            }
            return this;
        }

        /**
         * Crossfades smoothly from the current animation to {@code clipName}.
         *
         * @param clipName        target clip identifier
         * @param fadeDurationSec crossfade duration in seconds ({@code >= 0})
         */
        public void crossfadeTo(String clipName, double fadeDurationSec) {
            AnimationClip3D next = clips.get(Objects.requireNonNull(clipName, "clipName must not be null"));
            if (next == null) {
                throw new IllegalArgumentException("Unknown 3D animation clip: " + clipName);
            }
            if (fadeDurationSec > 0.0 && activeClip != null && !activeClip.name().equals(clipName)) {
                this.outgoingClip = this.activeClip;
                this.outgoingTime = this.activeTime;
                this.crossfadeDuration = fadeDurationSec;
                this.crossfadeElapsed = 0.0;
            } else {
                this.outgoingClip = null;
                this.crossfadeDuration = 0.0;
                this.crossfadeElapsed = 0.0;
            }
            this.activeClip = next;
            this.activeTime = 0.0;
        }

        /**
         * Advances active and crossfading clips by {@code dt} seconds.
         *
         * @param dt elapsed frame time in seconds
         */
        public void update(double dt) {
            if (!Double.isFinite(dt) || dt <= 0.0) {
                return;
            }
            if (activeClip != null) {
                activeTime = advanceTime(activeClip, activeTime, dt);
            }
            if (outgoingClip != null) {
                outgoingTime = advanceTime(outgoingClip, outgoingTime, dt);
                crossfadeElapsed += dt;
                if (crossfadeElapsed >= crossfadeDuration) {
                    outgoingClip = null;
                }
            }
        }

        /**
         * Returns normalized crossfade blend weight toward the active clip in {@code [0, 1]}.
         *
         * @return {@code 1.0} when no crossfade is in progress
         */
        public float getCrossfadeWeight() {
            if (outgoingClip == null || crossfadeDuration <= 0.0) {
                return 1.0f;
            }
            return Math.clamp((float) (crossfadeElapsed / crossfadeDuration), 0.0f, 1.0f);
        }

        /**
         * Evaluates final hierarchical skinning matrices ({@code global[i] * inverseBind[i]}) for
         * upload to vertex shader joint uniforms.
         *
         * @return list of {@link Matrix4f} skinning matrices (length == joint count)
         */
        public List<Matrix4f> computeSkinningMatrices() {
            int n = joints.size();
            Matrix4f[] globals = new Matrix4f[n];
            float blendAlpha = getCrossfadeWeight();

            for (int i = 0; i < n; i++) {
                Joint3D joint = joints.get(i);
                TrsPose activePose = sampleJointPose(activeClip, activeTime, joint);
                TrsPose finalPose = activePose;
                if (outgoingClip != null && blendAlpha < 1.0f) {
                    TrsPose outPose = sampleJointPose(outgoingClip, outgoingTime, joint);
                    finalPose = new TrsPose(
                            new Vector3f(outPose.t()).lerp(activePose.t(), blendAlpha),
                            new Quaternionf(outPose.r()).slerp(activePose.r(), blendAlpha),
                            new Vector3f(outPose.s()).lerp(activePose.s(), blendAlpha)
                    );
                }

                Matrix4f local = new Matrix4f()
                        .translate(finalPose.t())
                        .rotate(finalPose.r())
                        .scale(finalPose.s());
                if (joint.parentIndex() >= 0 && joint.parentIndex() < i) {
                    globals[i] = new Matrix4f(globals[joint.parentIndex()]).mul(local);
                } else {
                    globals[i] = local;
                }
            }

            List<Matrix4f> skin = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                skin.add(new Matrix4f(globals[i]).mul(joints.get(i).inverseBindMatrix()));
            }
            return List.copyOf(skin);
        }

        private static double advanceTime(AnimationClip3D clip, double curr, double dt) {
            double next = curr + dt;
            if (clip.looping()) {
                return next % clip.durationSec();
            }
            return Math.min(next, clip.durationSec());
        }

        private static TrsPose sampleJointPose(AnimationClip3D clip, double time, Joint3D joint) {
            if (clip == null) {
                return new TrsPose(joint.restTranslation(), joint.restRotation(), joint.restScale());
            }
            List<JointKeyframe> keys = clip.tracks().get(joint.index());
            if (keys == null || keys.isEmpty()) {
                return new TrsPose(joint.restTranslation(), joint.restRotation(), joint.restScale());
            }
            if (keys.size() == 1 || time <= keys.get(0).timeSec()) {
                JointKeyframe k = keys.get(0);
                return new TrsPose(k.translation(), k.rotation(), k.scale());
            }
            JointKeyframe last = keys.get(keys.size() - 1);
            if (time >= last.timeSec()) {
                return new TrsPose(last.translation(), last.rotation(), last.scale());
            }
            for (int i = 0; i < keys.size() - 1; i++) {
                JointKeyframe a = keys.get(i);
                JointKeyframe b = keys.get(i + 1);
                if (time >= a.timeSec() && time <= b.timeSec()) {
                    double span = Math.max(1e-6, b.timeSec() - a.timeSec());
                    float f = Math.clamp((float) ((time - a.timeSec()) / span), 0.0f, 1.0f);
                    return new TrsPose(
                            new Vector3f(a.translation()).lerp(b.translation(), f),
                            new Quaternionf(a.rotation()).slerp(b.rotation(), f),
                            new Vector3f(a.scale()).lerp(b.scale(), f)
                    );
                }
            }
            return new TrsPose(last.translation(), last.rotation(), last.scale());
        }
    }

    /**
     * Depth-tested 3D billboard particle system (snowflakes, fireflies) with true perspective
     * camera-distance scaling and back-to-front sorting ({@code #0281}).
     */
    public static final class ParticleSystem3D {

        /** Built-in 3D atmospheric particle presets. */
        public enum Preset3D {
            /** Falling 3D winter snowflakes drifting in depth layers. */
            SNOWFLAKES,
            /** Warm glowing forest fireflies drifting slowly in 3D volume. */
            FIREFLIES
        }

        /** Mutable 3D particle state. */
        public static final class Particle3D {

            private final Vector3f position;
            private final Vector3f velocity;
            private final float worldRadius;
            private float alpha;
            private double ageSec;
            private final double lifetimeSec;

            /**
             * Creates a 3D particle instance.
             *
             * @param position    initial world position
             * @param velocity    velocity in world units/sec
             * @param worldRadius billboard radius in world units
             * @param alpha       opacity in {@code [0, 1]}
             * @param lifetimeSec lifetime in seconds
             */
            public Particle3D(
                    Vector3f position,
                    Vector3f velocity,
                    float worldRadius,
                    float alpha,
                    double lifetimeSec
            ) {
                this.position = new Vector3f(Objects.requireNonNull(position, "position must not be null"));
                this.velocity = new Vector3f(Objects.requireNonNull(velocity, "velocity must not be null"));
                this.worldRadius = Math.max(0.01f, worldRadius);
                this.alpha = Math.clamp(alpha, 0.0f, 1.0f);
                this.lifetimeSec = Math.max(0.1, lifetimeSec);
            }

            /**
             * Returns current world position copy.
             *
             * @return world position
             */
            public Vector3f getPosition() {
                return new Vector3f(position);
            }
        }

        /**
         * Projected camera-facing 3D billboard ordered back-to-front for depth-checked rendering.
         *
         * @param screenX         projected screen X in pixels
         * @param screenY         projected screen Y in pixels
         * @param cameraDepth     view distance along camera forward axis
         * @param projectedSizePx screen diameter scaled inversely by camera distance
         * @param alpha           billboard opacity
         */
        public record ProjectedBillboard3D(
                float screenX,
                float screenY,
                float cameraDepth,
                float projectedSizePx,
                float alpha
        ) {
        }

        private final List<Particle3D> particles = new ArrayList<>();

        /** Creates an empty {@code ParticleSystem3D}. */
        public ParticleSystem3D() {
        }

        /**
         * Spawns a deterministic cloud of {@code count} 3D particles centered around {@code origin}.
         *
         * @param preset 3D particle preset
         * @param origin volume center
         * @param count  number of particles to spawn
         */
        public void spawnPresetBurst(Preset3D preset, Vector3f origin, int count) {
            Objects.requireNonNull(preset, "preset must not be null");
            Objects.requireNonNull(origin, "origin must not be null");
            for (int i = 0; i < count; i++) {
                float rx = ((i % 7) - 3) * 1.4f;
                float ry = ((i % 5) - 2) * 0.9f;
                float rz = ((i % 9) - 4) * 1.6f;
                Vector3f pos = new Vector3f(origin.x + rx, origin.y + ry, origin.z + rz);
                Vector3f vel = preset == Preset3D.SNOWFLAKES
                        ? new Vector3f(0.1f, -0.8f, 0.05f)
                        : new Vector3f((float) Math.sin(i) * 0.3f, 0.25f, (float) Math.cos(i) * 0.3f);
                particles.add(new Particle3D(pos, vel, 0.15f, 0.9f, 10.0));
            }
        }

        /**
         * Adds a specific 3D particle manually.
         *
         * @param particle particle instance
         * @return {@code this} for fluent chaining
         */
        public ParticleSystem3D addParticle(Particle3D particle) {
            particles.add(Objects.requireNonNull(particle, "particle must not be null"));
            return this;
        }

        /**
         * Advances active particles by {@code dt} seconds and prunes expired particles.
         *
         * @param dt elapsed simulation step in seconds
         */
        public void update(double dt) {
            if (!Double.isFinite(dt) || dt <= 0.0) {
                return;
            }
            float fdt = (float) dt;
            particles.removeIf(p -> {
                p.position.fma(fdt, p.velocity);
                p.ageSec += dt;
                return p.ageSec >= p.lifetimeSec;
            });
        }

        /**
         * Projects active 3D particles into camera-scaled screen billboards sorted back-to-front.
         *
         * @param camera    active 3D camera
         * @param viewportW viewport width in pixels
         * @param viewportH viewport height in pixels
         * @return back-to-front sorted immutable list of {@link ProjectedBillboard3D}
         */
        public List<ProjectedBillboard3D> projectBillboards(Camera3D camera, int viewportW, int viewportH) {
            Objects.requireNonNull(camera, "camera must not be null");
            Vector3f forward = new Vector3f(camera.getTarget()).sub(camera.getPosition());
            if (forward.lengthSquared() < 1e-8f) {
                forward.set(0.0f, 0.0f, -1.0f);
            } else {
                forward.normalize();
            }
            double halfFovRad = Math.toRadians(camera.getFovDegrees() * 0.5);
            float focalPx = (float) ((Math.max(1, viewportH) * 0.5) / Math.tan(halfFovRad));

            List<ProjectedBillboard3D> out = new ArrayList<>(particles.size());
            for (Particle3D p : particles) {
                Vector3f rel = new Vector3f(p.position).sub(camera.getPosition());
                float depth = rel.dot(forward);
                if (depth <= camera.getNearClip() || depth >= camera.getFarClip()) {
                    continue;
                }
                float[] pt = camera.projectWorldPoint(p.position, Math.max(1, viewportW), Math.max(1, viewportH));
                float projectedSize = (p.worldRadius * 2.0f * focalPx) / depth;
                out.add(new ProjectedBillboard3D(pt[0], pt[1], depth, projectedSize, p.alpha));
            }
            out.sort(Comparator.comparingDouble(ProjectedBillboard3D::cameraDepth).reversed());
            return List.copyOf(out);
        }

        /**
         * Returns active particle count.
         *
         * @return active particle count
         */
        public int getParticleCount() {
            return particles.size();
        }
    }

    /**
     * Bridges an active {@link Camera3D} position and look vector automatically to the OpenAL
     * listener inside {@link AudioEngine} ({@code #0283}).
     */
    public static final class Camera3DAudioListenerBridge {

        private final Camera3D camera;
        private final AudioEngine audioEngine;

        /**
         * Binds {@code camera} orientation sync to {@code audioEngine}.
         *
         * @param camera      active 3D camera
         * @param audioEngine active audio engine
         */
        public Camera3DAudioListenerBridge(Camera3D camera, AudioEngine audioEngine) {
            this.camera = Objects.requireNonNull(camera, "camera must not be null");
            this.audioEngine = Objects.requireNonNull(audioEngine, "audioEngine must not be null");
        }

        /**
         * Pushes current {@link Camera3D} world position and forward vector into {@link AudioEngine}.
         */
        public void sync() {
            Vector3f pos = camera.getPosition();
            Vector3f fwd = new Vector3f(camera.getTarget()).sub(pos);
            if (fwd.lengthSquared() < 1e-8f) {
                fwd.set(0.0f, 0.0f, -1.0f);
            } else {
                fwd.normalize();
            }
            Vector3f worldUp = new Vector3f(0.0f, 1.0f, 0.0f);
            Vector3f right = new Vector3f(fwd).cross(worldUp);
            Vector3f up = right.lengthSquared() > 1e-8f
                    ? new Vector3f(right.normalize()).cross(fwd).normalize()
                    : worldUp;
            audioEngine.setListenerTransform(pos.x, pos.y, pos.z, fwd.x, fwd.y, fwd.z, up.x, up.y, up.z);
        }
    }

    /**
     * Sinusoidal GPU vertex-shader wind animation pinning grounded roots where vertex weight
     * is zero ({@code #0299}).
     */
    public static final class VertexWindSystem {

        /** OpenGL 3.3 Core foliage/curtain vertex shader displacing canopy vertices without CPU updates. */
        public static final String WIND_VERTEX_SHADER = """
                #version 330 core
                layout(location = 0) in vec3 aPos;
                layout(location = 1) in vec3 aNormal;
                layout(location = 2) in vec2 aUv;
                layout(location = 3) in float aWindWeight;
                uniform mat4 uProjection;
                uniform mat4 uView;
                uniform mat4 uModel;
                uniform float uWindTime;
                uniform float uWindFrequency;
                uniform float uWindStrength;
                uniform vec3 uWindDirection;
                out vec2 vUv;
                void main() {
                    float phase = uWindTime * uWindFrequency + dot(aPos, vec3(0.7, 0.4, 0.7));
                    float sway = sin(phase) * uWindStrength * clamp(aWindWeight, 0.0, 1.0);
                    vec3 displaced = aPos + normalize(uWindDirection) * sway;
                    vUv = aUv;
                    gl_Position = uProjection * uView * uModel * vec4(displaced, 1.0);
                }
                """;

        private final Vector3f windDirection = new Vector3f(1.0f, 0.0f, 0.35f).normalize();
        private float strength = 0.25f;
        private float frequency = 2.4f;

        /** Creates a {@code VertexWindSystem} with gentle breeze parameters. */
        public VertexWindSystem() {
        }

        /**
         * Configures wind vector, sway amplitude, and oscillation frequency.
         *
         * @param direction horizontal/vertical wind vector (normalized)
         * @param strength  displacement amplitude in world units ({@code >= 0})
         * @param frequency angular frequency in rad/sec ({@code >= 0})
         */
        public void configure(Vector3f direction, float strength, float frequency) {
            Objects.requireNonNull(direction, "direction must not be null");
            if (direction.lengthSquared() > 1e-8f) {
                this.windDirection.set(direction).normalize();
            }
            this.strength = Math.max(0.0f, strength);
            this.frequency = Math.max(0.0f, frequency);
        }

        /**
         * Evaluates deterministic wind vertex offset (exact zero when {@code vertexWeight <= 0}).
         *
         * @param restPos      vertex rest position
         * @param vertexWeight painted wind weight in {@code [0, 1]} ({@code 0} fixes roots to ground)
         * @param timeSec      elapsed shader time in seconds
         * @return world/local vertex displacement vector
         */
        public Vector3f evaluateDisplacement(Vector3f restPos, float vertexWeight, double timeSec) {
            Objects.requireNonNull(restPos, "restPos must not be null");
            float w = Math.clamp(vertexWeight, 0.0f, 1.0f);
            if (w <= 0.0f || strength <= 0.0f) {
                return new Vector3f(0.0f, 0.0f, 0.0f);
            }
            double phase = timeSec * frequency + (restPos.x * 0.7 + restPos.y * 0.4 + restPos.z * 0.7);
            float sway = (float) Math.sin(phase) * strength * w;
            return new Vector3f(windDirection).mul(sway);
        }
    }

    /**
     * Deterministic simple rigid-body gravity &amp; bounce-damping simulator for 3D props such as
     * falling apples, shards, or goblets ({@code #0328}).
     */
    public static final class RigidBody3DPropSimulator {

        /** Mutable rigid-body 3D prop state. */
        public static final class RigidBodyProp {

            private final String id;
            private final Vector3f position;
            private final Vector3f velocity;
            private final float radius;
            private final float restitution;
            private final float groundFriction;
            private int bounceCount = 0;
            private boolean atRest = false;

            /**
             * Creates a rigid-body 3D prop.
             *
             * @param id             prop identifier
             * @param position       initial world coordinates
             * @param velocity       initial linear velocity
             * @param radius         collision sphere radius ({@code > 0})
             * @param restitution    bounce coefficient in {@code [0, 0.95]}
             * @param groundFriction horizontal contact damping per bounce in {@code [0, 1]}
             */
            public RigidBodyProp(
                    String id,
                    Vector3f position,
                    Vector3f velocity,
                    float radius,
                    float restitution,
                    float groundFriction
            ) {
                this.id = Objects.requireNonNull(id, "id must not be null");
                this.position = new Vector3f(Objects.requireNonNull(position, "position must not be null"));
                this.velocity = new Vector3f(Objects.requireNonNull(velocity, "velocity must not be null"));
                this.radius = Math.max(0.02f, radius);
                this.restitution = Math.clamp(restitution, 0.0f, 0.95f);
                this.groundFriction = Math.clamp(groundFriction, 0.0f, 1.0f);
            }

            /**
             * Returns prop ID.
             *
             * @return identifier
             */
            public String getId() {
                return id;
            }

            /**
             * Returns current position copy.
             *
             * @return position copy
             */
            public Vector3f getPosition() {
                return new Vector3f(position);
            }

            /**
             * Returns current velocity copy.
             *
             * @return velocity copy
             */
            public Vector3f getVelocity() {
                return new Vector3f(velocity);
            }

            /**
             * Returns collision radius.
             *
             * @return radius
             */
            public float getRadius() {
                return radius;
            }

            /**
             * Returns total floor impacts recorded.
             *
             * @return bounce count
             */
            public int getBounceCount() {
                return bounceCount;
            }

            /**
             * Returns whether the prop has dissipated energy and settled deterministically at rest.
             *
             * @return {@code true} if at rest
             */
            public boolean isAtRest() {
                return atRest;
            }
        }

        private static final float GRAVITY_Y = -9.81f;
        private static final float REST_SPEED_THRESHOLD = 0.25f;

        private final float floorY;
        private final List<RigidBodyProp> props = new ArrayList<>();

        /** Creates a simulator with floor plane at {@code y = 0.0f}. */
        public RigidBody3DPropSimulator() {
            this(0.0f);
        }

        /**
         * Creates a simulator with an explicit horizontal floor plane height.
         *
         * @param floorY floor Y coordinate
         */
        public RigidBody3DPropSimulator(float floorY) {
            this.floorY = floorY;
        }

        /**
         * Adds a rigid-body prop to the simulation.
         *
         * @param prop rigid-body prop
         * @return {@code this} for fluent chaining
         */
        public RigidBody3DPropSimulator addProp(RigidBodyProp prop) {
            props.add(Objects.requireNonNull(prop, "prop must not be null"));
            return this;
        }

        /**
         * Advances all rigid-body props deterministically by {@code dt} seconds.
         *
         * @param dt frame delta in seconds
         */
        public void step(double dt) {
            if (!Double.isFinite(dt) || dt <= 0.0) {
                return;
            }
            float fdt = (float) Math.min(dt, 0.05);
            for (RigidBodyProp prop : props) {
                if (prop.atRest) {
                    continue;
                }
                prop.velocity.y += GRAVITY_Y * fdt;
                prop.position.fma(fdt, prop.velocity);

                float restFloorY = floorY + prop.radius;
                if (prop.position.y <= restFloorY) {
                    prop.position.y = restFloorY;
                    if (prop.velocity.y < 0.0f) {
                        prop.bounceCount++;
                        float nextVy = -prop.velocity.y * prop.restitution;
                        float horizDamp = Math.max(0.0f, 1.0f - prop.groundFriction * 0.5f);
                        prop.velocity.x *= horizDamp;
                        prop.velocity.z *= horizDamp;
                        if (nextVy < REST_SPEED_THRESHOLD
                                && Math.hypot(prop.velocity.x, prop.velocity.z) < REST_SPEED_THRESHOLD) {
                            prop.velocity.set(0.0f, 0.0f, 0.0f);
                            prop.atRest = true;
                        } else {
                            prop.velocity.y = nextVy;
                        }
                    }
                }
            }
        }
    }
}
