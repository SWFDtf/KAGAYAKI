package de.swfdtf.graphics;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.KHRDebug;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bundled Wave P3-2 advanced 3D rendering, post-processing, lighting, panorama baking,
 * and RenderDoc diagnostic services ({@code #0268}, {@code #0269}, {@code #0270}, {@code #0287},
 * {@code #0292}, {@code #0308}, {@code #0314}, {@code #0339}).
 */
public final class Advanced3DRenderingServices {

    private Advanced3DRenderingServices() {
    }

    /**
     * Image-Based Lighting (IBL) HDR skybox and 6-face irradiance cubemap environment ({@code #0268}).
     */
    public static final class IblEnvironment3D {

        /**
         * Built-in atmospheric cubemap presets tailored to KAGAYAKI story themes.
         */
        public enum SkyboxPreset {
            /** Crisp winter snow valley sky and snow-bounce ground. */
            SNOW_VALLEY,
            /** Warm amber sunset castle courtyard horizon. */
            CASTLE_TWILIGHT,
            /** Deep indigo starry night sky with faint moonlight rim. */
            STARRY_NIGHT
        }

        /** OpenGL 3.3 Core vertex shader forcing skybox depth to exact {@code 1.0} via {@code pos.xyww}. */
        public static final String SKYBOX_VERTEX_SHADER = """
                #version 330 core
                layout(location = 0) in vec3 aPos;
                uniform mat4 uProjection;
                uniform mat4 uViewRotationOnly;
                out vec3 vDir;
                void main() {
                    vDir = aPos;
                    vec4 pos = uProjection * uViewRotationOnly * vec4(aPos, 1.0);
                    gl_Position = pos.xyww;
                }
                """;

        /** OpenGL 3.3 Core fragment shader sampling HDR cubemap with exposure scaling. */
        public static final String SKYBOX_FRAGMENT_SHADER = """
                #version 330 core
                in vec3 vDir;
                uniform vec3 uSkyZenith;
                uniform vec3 uSkyHorizon;
                uniform vec3 uSkyGround;
                uniform float uHdrExposure;
                out vec4 fragColor;
                void main() {
                    vec3 d = normalize(vDir);
                    float up = d.y;
                    vec3 sky = up >= 0.0
                            ? mix(uSkyHorizon, uSkyZenith, clamp(up, 0.0, 1.0))
                            : mix(uSkyHorizon, uSkyGround, clamp(-up, 0.0, 1.0));
                    vec3 mapped = vec3(1.0) - exp(-sky * max(uHdrExposure, 0.1));
                    fragColor = vec4(mapped, 1.0);
                }
                """;

        private final SkyboxPreset preset;
        private final float[][] cubemapIrradianceFaces;
        private final float hdrExposure;

        /**
         * Creates an IBL environment using the requested preset and neutral HDR exposure.
         *
         * @param preset skybox environment preset (must not be {@code null})
         */
        public IblEnvironment3D(SkyboxPreset preset) {
            this(preset, 1.15f);
        }

        /**
         * Creates an IBL environment using the requested preset and explicit HDR exposure.
         *
         * @param preset      skybox environment preset (must not be {@code null})
         * @param hdrExposure HDR exposure multiplier ({@code > 0})
         */
        public IblEnvironment3D(SkyboxPreset preset, float hdrExposure) {
            this.preset = Objects.requireNonNull(preset, "preset must not be null");
            this.hdrExposure = Float.isFinite(hdrExposure) ? Math.clamp(hdrExposure, 0.1f, 8.0f) : 1.0f;
            this.cubemapIrradianceFaces = buildPresetFaces(preset);
        }

        /**
         * Returns the NDC depth value produced by {@code gl_Position = pos.xyww} after perspective divide.
         *
         * @return exact {@code 1.0f} deepest Z depth
         */
        public float resolveSkyboxDepthNdc() {
            return 1.0f;
        }

        /**
         * Samples diffuse irradiance RGB for surface {@code normal} across the 6 cubemap faces.
         *
         * @param normal surface world normal (must not be {@code null})
         * @return 3-element HDR irradiance RGB array
         */
        public float[] sampleIrradiance(Vector3f normal) {
            Objects.requireNonNull(normal, "normal must not be null");
            Vector3f n = new Vector3f(normal);
            if (n.lengthSquared() < 1e-8f) {
                n.set(0.0f, 1.0f, 0.0f);
            } else {
                n.normalize();
            }
            float wx = n.x * n.x;
            float wy = n.y * n.y;
            float wz = n.z * n.z;
            int fx = n.x >= 0.0f ? 0 : 1;
            int fy = n.y >= 0.0f ? 2 : 3;
            int fz = n.z >= 0.0f ? 4 : 5;
            float[] out = new float[3];
            for (int c = 0; c < 3; c++) {
                float irr = wx * cubemapIrradianceFaces[fx][c]
                        + wy * cubemapIrradianceFaces[fy][c]
                        + wz * cubemapIrradianceFaces[fz][c];
                out[c] = irr * hdrExposure;
            }
            return out;
        }

        /**
         * Returns the active skybox preset.
         *
         * @return {@link SkyboxPreset}
         */
        public SkyboxPreset getPreset() {
            return preset;
        }

        /**
         * Returns the configured HDR exposure multiplier.
         *
         * @return HDR exposure
         */
        public float getHdrExposure() {
            return hdrExposure;
        }

        private static float[][] buildPresetFaces(SkyboxPreset preset) {
            return switch (preset) {
                case SNOW_VALLEY -> new float[][] {
                    {0.72f, 0.80f, 0.94f}, {0.68f, 0.76f, 0.90f},
                    {0.85f, 0.95f, 1.25f}, {0.78f, 0.82f, 0.88f},
                    {0.75f, 0.84f, 0.96f}, {0.70f, 0.78f, 0.92f}
                };
                case CASTLE_TWILIGHT -> new float[][] {
                    {0.95f, 0.56f, 0.34f}, {0.42f, 0.32f, 0.55f},
                    {0.35f, 0.28f, 0.58f}, {0.26f, 0.20f, 0.18f},
                    {0.68f, 0.44f, 0.46f}, {0.38f, 0.30f, 0.52f}
                };
                case STARRY_NIGHT -> new float[][] {
                    {0.12f, 0.16f, 0.30f}, {0.10f, 0.14f, 0.26f},
                    {0.08f, 0.12f, 0.28f}, {0.06f, 0.08f, 0.12f},
                    {0.14f, 0.18f, 0.34f}, {0.10f, 0.13f, 0.25f}
                };
            };
        }
    }

    /**
     * Screen-Space Ambient Occlusion (SSAO) quality configuration &amp; hemisphere kernel pass ({@code #0269}).
     */
    public static final class SsaoPass {

        /** OpenGL 3.3 Core SSAO hemisphere occlusion fragment shader. */
        public static final String SSAO_FRAGMENT_SHADER = """
                #version 330 core
                in vec2 vUv;
                uniform sampler2D uDepthTex;
                uniform float uRadius;
                uniform float uIntensity;
                uniform int uEnabled;
                out vec4 fragColor;
                void main() {
                    if (uEnabled == 0) {
                        fragColor = vec4(1.0);
                        return;
                    }
                    float baseD = texture(uDepthTex, vUv).r;
                    float occ = 0.0;
                    vec2 offset = vec2(max(uRadius, 0.05) * 0.0025);
                    occ += step(texture(uDepthTex, vUv + vec2(offset.x, 0.0)).r + 0.001, baseD);
                    occ += step(texture(uDepthTex, vUv - vec2(offset.x, 0.0)).r + 0.001, baseD);
                    occ += step(texture(uDepthTex, vUv + vec2(0.0, offset.y)).r + 0.001, baseD);
                    occ += step(texture(uDepthTex, vUv - vec2(0.0, offset.y)).r + 0.001, baseD);
                    float factor = clamp(1.0 - (occ * 0.25) * uIntensity, 0.0, 1.0);
                    fragColor = vec4(vec3(factor), 1.0);
                }
                """;

        private boolean enabled;
        private float radius;
        private float intensity;
        private int sampleCount;

        /** Creates an enabled default SSAO configuration suitable for quality menus. */
        public SsaoPass() {
            this(true, 0.5f, 1.0f, 16);
        }

        /**
         * Creates an explicit SSAO configuration.
         *
         * @param enabled     whether SSAO is active
         * @param radius      world-space hemisphere radius ({@code [0.05, 5.0]})
         * @param intensity   occlusion darkening strength ({@code [0.0, 3.0]})
         * @param sampleCount hemisphere tap count ({@code [8, 64]})
         */
        public SsaoPass(boolean enabled, float radius, float intensity, int sampleCount) {
            this.enabled = enabled;
            setRadius(radius);
            setIntensity(intensity);
            setSampleCount(sampleCount);
        }

        /**
         * Generates deterministic tangent-space hemisphere sample offsets weighted toward origin.
         *
         * @param count number of kernel vectors
         * @return immutable list of tangent-space {@link Vector3f} offsets
         */
        public static List<Vector3f> generateHemisphereKernel(int count) {
            int n = Math.clamp(count, 4, 64);
            List<Vector3f> kernel = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                double golden = 2.399963229728653 * (i + 1);
                float z = Math.max(0.08f, ((i + 0.5f) / n));
                float r = (float) Math.sqrt(Math.max(0.0, 1.0 - z * z));
                Vector3f sample = new Vector3f(
                        (float) Math.cos(golden) * r,
                        (float) Math.sin(golden) * r,
                        z
                ).normalize();
                float scale = (float) i / n;
                scale = 0.1f + 0.9f * scale * scale;
                kernel.add(sample.mul(scale));
            }
            return List.copyOf(kernel);
        }

        /**
         * Evaluates CPU-equivalent ambient occlusion factor in {@code [0, 1]} for depth comparisons.
         *
         * @param centerDepth    depth of target pixel
         * @param neighborDepths sampled surrounding depths
         * @return visibility factor ({@code 1.0f} when unoccluded or disabled, lower in creases)
         */
        public float evaluateOcclusion(float centerDepth, float[] neighborDepths) {
            if (!enabled || neighborDepths == null || neighborDepths.length == 0) {
                return 1.0f;
            }
            float occluded = 0.0f;
            for (float neighbor : neighborDepths) {
                float delta = centerDepth - neighbor;
                if (delta > 0.001f && delta <= radius) {
                    occluded += 1.0f - (delta / radius) * 0.5f;
                }
            }
            float ratio = occluded / neighborDepths.length;
            return Math.clamp(1.0f - ratio * intensity, 0.0f, 1.0f);
        }

        /**
         * Toggles SSAO activation for Quality menu controls.
         *
         * @param enabled {@code true} to enable
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * Returns whether SSAO is enabled.
         *
         * @return enabled state
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Sets SSAO hemisphere radius clamped to {@code [0.05f, 5.0f]}.
         *
         * @param radius radius in world units
         */
        public void setRadius(float radius) {
            this.radius = Float.isFinite(radius) ? Math.clamp(radius, 0.05f, 5.0f) : 0.5f;
        }

        /**
         * Returns SSAO hemisphere radius.
         *
         * @return radius
         */
        public float getRadius() {
            return radius;
        }

        /**
         * Sets SSAO occlusion intensity clamped to {@code [0.0f, 3.0f]}.
         *
         * @param intensity occlusion strength
         */
        public void setIntensity(float intensity) {
            this.intensity = Float.isFinite(intensity) ? Math.clamp(intensity, 0.0f, 3.0f) : 1.0f;
        }

        /**
         * Returns SSAO occlusion intensity.
         *
         * @return intensity
         */
        public float getIntensity() {
            return intensity;
        }

        /**
         * Sets sample tap count clamped to {@code [8, 64]}.
         *
         * @param sampleCount tap count
         */
        public void setSampleCount(int sampleCount) {
            this.sampleCount = Math.clamp(sampleCount, 8, 64);
        }

        /**
         * Returns configured sample tap count.
         *
         * @return sample count
         */
        public int getSampleCount() {
            return sampleCount;
        }
    }

    /**
     * Camera Depth-of-Field (Bokeh) focus controller with coordinate &amp; 3D node-tag binding ({@code #0270}).
     */
    public static final class DepthOfField3D {

        /** OpenGL 3.3 Core Bokeh Depth-of-Field post-processing fragment shader. */
        public static final String DOF_BOKEH_FRAGMENT_SHADER = """
                #version 330 core
                in vec2 vUv;
                uniform sampler2D uColorTex;
                uniform sampler2D uDepthTex;
                uniform float uFocusDistance;
                uniform float uAperture;
                uniform float uMaxBlurRadius;
                out vec4 fragColor;
                void main() {
                    float d = texture(uDepthTex, vUv).r * 100.0;
                    float coc = clamp(abs(d - uFocusDistance) * uAperture * 0.05, 0.0, uMaxBlurRadius);
                    vec2 stepUv = vec2(coc * 0.002);
                    vec3 acc = texture(uColorTex, vUv).rgb * 0.4;
                    acc += texture(uColorTex, vUv + vec2(stepUv.x, 0.0)).rgb * 0.15;
                    acc += texture(uColorTex, vUv - vec2(stepUv.x, 0.0)).rgb * 0.15;
                    acc += texture(uColorTex, vUv + vec2(0.0, stepUv.y)).rgb * 0.15;
                    acc += texture(uColorTex, vUv - vec2(0.0, stepUv.y)).rgb * 0.15;
                    fragColor = vec4(acc, 1.0);
                }
                """;

        private boolean enabled = true;
        private float focusDistance = 5.0f;
        private float aperture = 2.8f;
        private float maxBlurRadius = 1.0f;
        private Vector3f boundPosition;
        private String boundNodeTag;

        /** Creates a {@code DepthOfField3D} controller with default cinema bokeh parameters. */
        public DepthOfField3D() {
        }

        /**
         * Binds focal plane distance dynamically to an explicit 3D world coordinate.
         *
         * @param worldTarget 3D world focus coordinate (must not be {@code null})
         */
        public void bindFocusToPosition(Vector3f worldTarget) {
            this.boundPosition = new Vector3f(Objects.requireNonNull(worldTarget, "worldTarget must not be null"));
            this.boundNodeTag = null;
        }

        /**
         * Binds focal plane distance dynamically to a {@link Scene3DWorld.Node3D} identifier/tag.
         *
         * @param nodeTag 3D node identifier in {@link Scene3DWorld} (must not be {@code null} or blank)
         */
        public void bindFocusToNodeTag(String nodeTag) {
            Objects.requireNonNull(nodeTag, "nodeTag must not be null");
            if (nodeTag.isBlank()) {
                throw new IllegalArgumentException("nodeTag must not be blank");
            }
            this.boundNodeTag = nodeTag.trim();
            this.boundPosition = null;
        }

        /**
         * Sets an explicit manual focus distance and clears any coordinate/tag binding.
         *
         * @param focusDistance focal distance in world units ({@code >= 0.1})
         */
        public void setManualFocusDistance(float focusDistance) {
            this.boundPosition = null;
            this.boundNodeTag = null;
            this.focusDistance = Float.isFinite(focusDistance) ? Math.max(0.1f, focusDistance) : 5.0f;
        }

        /**
         * Resolves active focal distance from bound target position or {@link Scene3DWorld} node tag
         * relative to {@code camera}.
         *
         * @param camera active 3D camera (must not be {@code null})
         * @param world  optional 3D world graph for tag lookup (nullable when coordinate-bound)
         * @return updated focus distance in world units
         */
        public float updateFocusFromCamera(Camera3D camera, Scene3DWorld world) {
            Objects.requireNonNull(camera, "camera must not be null");
            Vector3f targetPos = null;
            if (boundNodeTag != null && world != null) {
                Optional<Scene3DWorld.Node3D> node = world.getNode(boundNodeTag);
                if (node.isPresent()) {
                    targetPos = node.get().getPosition();
                }
            } else if (boundPosition != null) {
                targetPos = boundPosition;
            }
            if (targetPos != null) {
                this.focusDistance = Math.max(0.1f, camera.getPosition().distance(targetPos));
            }
            return this.focusDistance;
        }

        /**
         * Computes normalized Circle-of-Confusion blur radius in {@code [0, maxBlurRadius]}.
         *
         * @param sampleDepth view-space distance of the shaded fragment
         * @return CoC blur radius ({@code 0.0f} when disabled or at exact focus plane)
         */
        public float computeCircleOfConfusion(float sampleDepth) {
            if (!enabled) {
                return 0.0f;
            }
            float raw = Math.abs(sampleDepth - focusDistance) * (1.0f / Math.max(0.8f, aperture)) * 0.25f;
            return Math.clamp(raw, 0.0f, maxBlurRadius);
        }

        /**
         * Enables or disables Depth-of-Field blur.
         *
         * @param enabled {@code true} to enable
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * Returns whether Depth-of-Field is enabled.
         *
         * @return enabled state
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Returns currently resolved focal distance in world units.
         *
         * @return focus distance
         */
        public float getFocusDistance() {
            return focusDistance;
        }

        /**
         * Returns the bound 3D node tag if tag-bound.
         *
         * @return optional bound node tag
         */
        public Optional<String> getBoundNodeTag() {
            return Optional.ofNullable(boundNodeTag);
        }

        /**
         * Configures f-stop aperture and maximum normalized bokeh radius.
         *
         * @param aperture      f-stop number ({@code [0.8, 22.0]})
         * @param maxBlurRadius maximum normalized blur radius ({@code [0.1, 4.0]})
         */
        public void setLensParameters(float aperture, float maxBlurRadius) {
            this.aperture = Float.isFinite(aperture) ? Math.clamp(aperture, 0.8f, 22.0f) : 2.8f;
            this.maxBlurRadius = Float.isFinite(maxBlurRadius) ? Math.clamp(maxBlurRadius, 0.1f, 4.0f) : 1.0f;
        }
    }

    /**
     * Volumetric crepuscular light shafts (God Rays) post-processing pass ({@code #0292}).
     */
    public static final class VolumetricGodRaysPass {

        /** OpenGL 3.3 Core radial light-shaft occlusion scatter fragment shader. */
        public static final String GOD_RAYS_FRAGMENT_SHADER = """
                #version 330 core
                in vec2 vUv;
                uniform sampler2D uOcclusionTex;
                uniform vec2 uLightScreenUv;
                uniform float uDensity;
                uniform float uWeight;
                uniform float uDecay;
                uniform float uExposure;
                uniform float uFacingFade;
                out vec4 fragColor;
                void main() {
                    if (uFacingFade <= 0.001) {
                        fragColor = vec4(0.0);
                        return;
                    }
                    vec2 uv = vUv;
                    vec2 deltaUv = (uv - uLightScreenUv) * (uDensity / 16.0);
                    float illumDecay = 1.0;
                    vec3 acc = vec3(0.0);
                    for (int i = 0; i < 16; i++) {
                        uv -= deltaUv;
                        vec3 sampleCol = texture(uOcclusionTex, clamp(uv, 0.0, 1.0)).rgb;
                        acc += sampleCol * illumDecay * uWeight;
                        illumDecay *= uDecay;
                    }
                    fragColor = vec4(acc * uExposure * uFacingFade, 1.0);
                }
                """;

        /**
         * Evaluated radial light shaft parameters for the current frame.
         *
         * @param lightVisibleInFront {@code true} when light source is in front of camera
         * @param facingDot           cosine angle between camera forward and light direction
         * @param lightScreenU        normalized horizontal screen coordinate
         * @param lightScreenV        normalized vertical screen coordinate
         * @param effectiveIntensity  final attenuated light shaft intensity ({@code 0.0f} behind camera)
         */
        public record GodRayEvaluation(
                boolean lightVisibleInFront,
                float facingDot,
                float lightScreenU,
                float lightScreenV,
                float effectiveIntensity
        ) {
        }

        private float baseExposure = 0.45f;
        private float density = 0.85f;
        private float weight = 0.08f;
        private float decay = 0.96f;

        /** Creates a {@code VolumetricGodRaysPass} with atmospheric castle-window defaults. */
        public VolumetricGodRaysPass() {
        }

        /**
         * Evaluates god ray screen-space origin and camera-facing fade-out so shafts fade cleanly
         * to zero as soon as the light moves behind the camera.
         *
         * @param camera        active 3D camera
         * @param lightWorldPos light source world position (or distant sun point)
         * @param viewportW     logical viewport width
         * @param viewportH     logical viewport height
         * @return immutable {@link GodRayEvaluation}
         */
        public GodRayEvaluation evaluate(
                Camera3D camera,
                Vector3f lightWorldPos,
                int viewportW,
                int viewportH
        ) {
            Objects.requireNonNull(camera, "camera must not be null");
            Objects.requireNonNull(lightWorldPos, "lightWorldPos must not be null");

            Vector3f forward = new Vector3f(camera.getTarget()).sub(camera.getPosition());
            if (forward.lengthSquared() < 1e-8f) {
                forward.set(0.0f, 0.0f, -1.0f);
            } else {
                forward.normalize();
            }
            Vector3f toLight = new Vector3f(lightWorldPos).sub(camera.getPosition());
            if (toLight.lengthSquared() < 1e-8f) {
                toLight.set(forward);
            } else {
                toLight.normalize();
            }

            float dot = forward.dot(toLight);
            if (dot <= 0.0f) {
                return new GodRayEvaluation(false, dot, 0.5f, 0.5f, 0.0f);
            }

            float[] projected = camera.projectWorldPoint(
                    lightWorldPos, Math.max(1, viewportW), Math.max(1, viewportH));
            float u = projected[0] / Math.max(1, viewportW);
            float v = projected[1] / Math.max(1, viewportH);
            float facingFade = Math.clamp((dot - 0.0f) / 0.35f, 0.0f, 1.0f);
            return new GodRayEvaluation(true, dot, u, v, baseExposure * facingFade);
        }

        /**
         * Configures God Ray scatter constants.
         *
         * @param baseExposure base additive exposure
         * @param density      ray march step density
         * @param weight       per-sample contribution weight
         * @param decay        exponential attenuation per march step
         */
        public void configure(float baseExposure, float density, float weight, float decay) {
            this.baseExposure = Math.clamp(baseExposure, 0.0f, 2.0f);
            this.density = Math.clamp(density, 0.1f, 2.0f);
            this.weight = Math.clamp(weight, 0.01f, 0.5f);
            this.decay = Math.clamp(decay, 0.8f, 0.999f);
        }

        /**
         * Returns configured base exposure.
         *
         * @return base exposure
         */
        public float getBaseExposure() {
            return baseExposure;
        }

        /**
         * Returns configured march density.
         *
         * @return density
         */
        public float getDensity() {
            return density;
        }

        /**
         * Returns configured sample weight.
         *
         * @return weight
         */
        public float getWeight() {
            return weight;
        }

        /**
         * Returns configured decay factor.
         *
         * @return decay
         */
        public float getDecay() {
            return decay;
        }
    }

    /**
     * Baked 6-face Color Box / SH-style Ambient Light Probe volume interpolating indirect room
     * illumination smoothly across portals without hard transition jumps ({@code #0308}).
     */
    public static final class AmbientLightProbeGrid {

        /**
         * Single baked 6-face ambient irradiance probe in world space.
         *
         * @param id        probe identifier
         * @param position  world-space probe coordinates
         * @param sixFaceRgb 6-row by 3-column RGB irradiance values ({@code +X,-X,+Y,-Y,+Z,-Z})
         */
        public record LightProbe(String id, Vector3f position, float[][] sixFaceRgb) {
            /** Validates 6x3 cubemap face matrix. */
            public LightProbe {
                Objects.requireNonNull(id, "id must not be null");
                position = new Vector3f(Objects.requireNonNull(position, "position must not be null"));
                Objects.requireNonNull(sixFaceRgb, "sixFaceRgb must not be null");
                if (sixFaceRgb.length != 6) {
                    throw new IllegalArgumentException("Expected 6 faces in LightProbe, got " + sixFaceRgb.length);
                }
                float[][] copy = new float[6][];
                for (int i = 0; i < 6; i++) {
                    if (sixFaceRgb[i] == null || sixFaceRgb[i].length != 3) {
                        throw new IllegalArgumentException("Each face in LightProbe must have 3 RGB floats");
                    }
                    copy[i] = sixFaceRgb[i].clone();
                }
                sixFaceRgb = copy;
            }
        }

        private final List<LightProbe> probes = new ArrayList<>();

        /** Creates an empty {@code AmbientLightProbeGrid}. */
        public AmbientLightProbeGrid() {
        }

        /**
         * Registers a baked light probe in the grid.
         *
         * @param probe light probe (must not be {@code null})
         * @return {@code this} for fluent chaining
         */
        public AmbientLightProbeGrid addProbe(LightProbe probe) {
            probes.add(Objects.requireNonNull(probe, "probe must not be null"));
            return this;
        }

        /**
         * Returns an immutable view of registered probes.
         *
         * @return immutable list of {@link LightProbe}s
         */
        public List<LightProbe> getProbes() {
            return List.copyOf(probes);
        }

        /**
         * Samples smooth inverse-distance-weighted ambient RGB at {@code worldPos} along {@code normal}.
         *
         * @param worldPos world evaluation point
         * @param normal   surface normal
         * @return 3-element ambient RGB array
         */
        public float[] sampleAmbient(Vector3f worldPos, Vector3f normal) {
            Objects.requireNonNull(worldPos, "worldPos must not be null");
            Objects.requireNonNull(normal, "normal must not be null");
            if (probes.isEmpty()) {
                return new float[] {0.2f, 0.2f, 0.22f};
            }
            Vector3f n = new Vector3f(normal);
            if (n.lengthSquared() < 1e-8f) {
                n.set(0.0f, 1.0f, 0.0f);
            } else {
                n.normalize();
            }

            double totalWeight = 0.0;
            double[] sumRgb = new double[3];
            for (LightProbe probe : probes) {
                float d2 = probe.position().distanceSquared(worldPos);
                double w = 1.0 / (d2 + 0.5);
                float[] probeIrr = evaluateProbe(probe, n);
                sumRgb[0] += probeIrr[0] * w;
                sumRgb[1] += probeIrr[1] * w;
                sumRgb[2] += probeIrr[2] * w;
                totalWeight += w;
            }
            return new float[] {
                (float) (sumRgb[0] / totalWeight),
                (float) (sumRgb[1] / totalWeight),
                (float) (sumRgb[2] / totalWeight)
            };
        }

        private static float[] evaluateProbe(LightProbe probe, Vector3f n) {
            float wx = n.x * n.x;
            float wy = n.y * n.y;
            float wz = n.z * n.z;
            int fx = n.x >= 0.0f ? 0 : 1;
            int fy = n.y >= 0.0f ? 2 : 3;
            int fz = n.z >= 0.0f ? 4 : 5;
            float[][] f = probe.sixFaceRgb();
            return new float[] {
                wx * f[fx][0] + wy * f[fy][0] + wz * f[fz][0],
                wx * f[fx][1] + wy * f[fy][1] + wz * f[fz][1],
                wx * f[fx][2] + wy * f[fy][2] + wz * f[fz][2]
            };
        }
    }

    /**
     * Subsurface Scattering (SSS) approximation for candle wax, foliage, and anime skin ({@code #0314}).
     */
    public static final class SubsurfaceScatteringModel {

        /**
         * Material subsurface transmission presets.
         */
        public enum Preset {
            /** Warm translucent candle wax. */
            WAX(new float[] {1.0f, 0.55f, 0.20f}, 0.25f, 3.0f),
            /** Warm sunlit green/gold translucent tree foliage. */
            FOLIAGE(new float[] {0.55f, 0.92f, 0.25f}, 0.30f, 2.5f),
            /** Subtle warm crimson-peach skin rim translucency. */
            SKIN(new float[] {0.98f, 0.42f, 0.32f}, 0.18f, 4.0f);

            private final float[] scatterTint;
            private final float distortion;
            private final float power;

            Preset(float[] scatterTint, float distortion, float power) {
                this.scatterTint = scatterTint;
                this.distortion = distortion;
                this.power = power;
            }

            /**
             * Returns the warm subsurface RGB tint.
             *
             * @return 3-element RGB tint copy
             */
            public float[] getScatterTint() {
                return scatterTint.clone();
            }

            /**
             * Returns the normal distortion factor.
             *
             * @return distortion weight
             */
            public float getDistortion() {
                return distortion;
            }

            /**
             * Returns the backlit lobe exponent.
             *
             * @return power exponent
             */
            public float getPower() {
                return power;
            }
        }

        /** OpenGL 3.3 Core GLSL helper snippet for Toon/PBR subsurface rim glow. */
        public static final String SSS_FUNCTION_GLSL = """
                vec3 evalSubsurface(vec3 n, vec3 viewDir, vec3 lightDir,
                        vec3 scatterTint, float distortion, float power, float strength, float thickness) {
                    vec3 h = normalize(lightDir + n * distortion);
                    float vdh = pow(clamp(dot(viewDir, -h), 0.0, 1.0), power);
                    return scatterTint * vdh * max(strength, 0.0) * (1.0 - clamp(thickness, 0.0, 0.95));
                }
                """;

        private final Preset preset;
        private float strength;

        /**
         * Creates an SSS model with default strength {@code 1.0f}.
         *
         * @param preset subsurface material preset
         */
        public SubsurfaceScatteringModel(Preset preset) {
            this(preset, 1.0f);
        }

        /**
         * Creates an SSS model with explicit rim glow strength.
         *
         * @param preset   subsurface material preset
         * @param strength parameterized translucency intensity ({@code >= 0})
         */
        public SubsurfaceScatteringModel(Preset preset, float strength) {
            this.preset = Objects.requireNonNull(preset, "preset must not be null");
            setStrength(strength);
        }

        /**
         * Evaluates warm backlit subsurface transmission RGB.
         *
         * @param viewDirFromSurface normalized vector from surface toward camera
         * @param lightDirToLight    normalized vector from surface toward light
         * @param surfaceNormal      normalized surface normal
         * @param thickness          normalized thickness in {@code [0, 1]}
         * @return 3-element warm RGB scatter contribution
         */
        public float[] evaluateBacklitScattering(
                Vector3f viewDirFromSurface,
                Vector3f lightDirToLight,
                Vector3f surfaceNormal,
                float thickness
        ) {
            Objects.requireNonNull(viewDirFromSurface, "viewDirFromSurface must not be null");
            Objects.requireNonNull(lightDirToLight, "lightDirToLight must not be null");
            Objects.requireNonNull(surfaceNormal, "surfaceNormal must not be null");
            if (strength <= 0.0f) {
                return new float[] {0.0f, 0.0f, 0.0f};
            }
            Vector3f v = new Vector3f(viewDirFromSurface).normalize();
            Vector3f l = new Vector3f(lightDirToLight).normalize();
            Vector3f n = new Vector3f(surfaceNormal).normalize();

            Vector3f halfVec = new Vector3f(l).add(new Vector3f(n).mul(preset.getDistortion())).normalize();
            float backlit = Math.clamp(v.dot(new Vector3f(halfVec).negate()), 0.0f, 1.0f);
            float lobe = (float) Math.pow(backlit, preset.getPower());
            float attenuation = (1.0f - Math.clamp(thickness, 0.0f, 0.95f)) * strength;
            float[] tint = preset.getScatterTint();
            return new float[] {
                tint[0] * lobe * attenuation,
                tint[1] * lobe * attenuation,
                tint[2] * lobe * attenuation
            };
        }

        /**
         * Sets parameterized subsurface glow strength.
         *
         * @param strength strength multiplier ({@code [0, 4.0]})
         */
        public void setStrength(float strength) {
            this.strength = Float.isFinite(strength) ? Math.clamp(strength, 0.0f, 4.0f) : 1.0f;
        }

        /**
         * Returns configured glow strength.
         *
         * @return strength
         */
        public float getStrength() {
            return strength;
        }

        /**
         * Returns active SSS preset.
         *
         * @return {@link Preset}
         */
        public Preset getPreset() {
            return preset;
        }
    }

    /**
     * Bakes complex 3D rooms into crisp layered 2D PNG plates directly compatible with
     * {@link RenderPipeline2D} for low-spec fallback devices ({@code #0287}).
     */
    public static final class Scene3DPanoramaBaker {

        /**
         * Single exported 2D panorama layer backed by a real PNG file.
         *
         * @param layerName logical 2D scene layer name (e.g. {@code "sky"}, {@code "midground_3d"})
         * @param width     exported width matching target {@link RenderPipeline2D} canvas
         * @param height    exported height matching target {@link RenderPipeline2D} canvas
         * @param pngPath   written PNG file path
         * @param zOrder    target 2D layer Z-order
         */
        public record BakedPanoramaLayer(
                String layerName,
                int width,
                int height,
                Path pngPath,
                int zOrder
        ) {
        }

        /** Creates a {@code Scene3DPanoramaBaker}. */
        public Scene3DPanoramaBaker() {
        }

        /**
         * Projects 3D world skybox &amp; meshes through {@code camera} and writes aligned 2D RGBA8 PNG
         * plates into {@code outputDir} via {@link TextureManager#writePng}.
         *
         * @param world     3D world graph to bake
         * @param camera    reference 3D camera viewpoint
         * @param width     target logical 2D width (e.g. {@code 1920} or scaled)
         * @param height    target logical 2D height (e.g. {@code 1080} or scaled)
         * @param outputDir destination directory for generated PNG layers
         * @return immutable list of {@link BakedPanoramaLayer} descriptors
         */
        public List<BakedPanoramaLayer> bakeWorldTo2DLayers(
                Scene3DWorld world,
                Camera3D camera,
                int width,
                int height,
                Path outputDir
        ) {
            Objects.requireNonNull(world, "world must not be null");
            Objects.requireNonNull(camera, "camera must not be null");
            Objects.requireNonNull(outputDir, "outputDir must not be null");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Panorama dimensions must be positive");
            }

            try {
                Files.createDirectories(outputDir);
            } catch (Exception ex) {
                throw new IllegalStateException("Failed creating panorama output directory: " + outputDir, ex);
            }

            byte[] skyPixels = rasterizeSkyPlate(world, width, height);
            Path skyPath = outputDir.resolve("panorama_sky.png");
            TextureManager.writePng(skyPath, width, height, skyPixels);

            byte[] worldPixels = rasterizeNodesPlate(world, camera, width, height);
            Path worldPath = outputDir.resolve("panorama_world3d.png");
            TextureManager.writePng(worldPath, width, height, worldPixels);

            return List.of(
                    new BakedPanoramaLayer(
                            SceneGraph.LAYER_BACKGROUND + "_sky", width, height, skyPath,
                            SceneLayer.Z_BACKGROUND - 10),
                    new BakedPanoramaLayer(
                            SceneGraph.LAYER_BACKGROUND, width, height, worldPath,
                            SceneLayer.Z_BACKGROUND)
            );
        }

        private static byte[] rasterizeSkyPlate(Scene3DWorld world, int w, int h) {
            byte[] rgba = new byte[Math.multiplyExact(Math.multiplyExact(w, h), 4)];
            float hour = world.getSkydome().getHourOfDay();
            boolean night = hour < 5.5f || hour > 19.5f;
            int topR = night ? 14 : 74;
            int topG = night ? 22 : 126;
            int topB = night ? 48 : 204;
            int botR = night ? 32 : 185;
            int botG = night ? 44 : 210;
            int botB = night ? 78 : 240;

            for (int y = 0; y < h; y++) {
                float t = h <= 1 ? 0.0f : (float) y / (h - 1);
                byte r = (byte) Math.round(topR + (botR - topR) * t);
                byte g = (byte) Math.round(topG + (botG - topG) * t);
                byte b = (byte) Math.round(topB + (botB - topB) * t);
                for (int x = 0; x < w; x++) {
                    int idx = (y * w + x) * 4;
                    rgba[idx] = r;
                    rgba[idx + 1] = g;
                    rgba[idx + 2] = b;
                    rgba[idx + 3] = (byte) 255;
                }
            }
            return rgba;
        }

        private static byte[] rasterizeNodesPlate(Scene3DWorld world, Camera3D camera, int w, int h) {
            byte[] rgba = new byte[Math.multiplyExact(Math.multiplyExact(w, h), 4)];
            List<Scene3DWorld.Node3D> nodes = world.getSortedDrawNodes(camera.getPosition());
            float aspect = w / (float) Math.max(1, h);
            Matrix4f vp = new Matrix4f(camera.getProjectionMatrix(aspect)).mul(camera.getViewMatrix());

            for (Scene3DWorld.Node3D node : nodes) {
                Mesh3D.BoundingBox3D aabb = node.computeWorldAabb();
                Vector3f center = new Vector3f(
                        (aabb.minX() + aabb.maxX()) * 0.5f,
                        (aabb.minY() + aabb.maxY()) * 0.5f,
                        (aabb.minZ() + aabb.maxZ()) * 0.5f
                );
                float[] minPt = camera.projectWorldPoint(
                        new Vector3f(aabb.minX(), aabb.maxY(), center.z), w, h);
                float[] maxPt = camera.projectWorldPoint(
                        new Vector3f(aabb.maxX(), aabb.minY(), center.z), w, h);
                if (vp.m33() == 0.0f && center.lengthSquared() < 0.0f) {
                    continue;
                }
                int x0 = Math.clamp(Math.round(Math.min(minPt[0], maxPt[0])), 0, w - 1);
                int x1 = Math.clamp(Math.round(Math.max(minPt[0], maxPt[0])), 0, w - 1);
                int y0 = Math.clamp(Math.round(Math.min(minPt[1], maxPt[1])), 0, h - 1);
                int y1 = Math.clamp(Math.round(Math.max(minPt[1], maxPt[1])), 0, h - 1);

                float[] alb = node.getMaterial().getAlbedoRgba();
                byte r = (byte) Math.clamp(Math.round(alb[0] * 255.0f), 0, 255);
                byte g = (byte) Math.clamp(Math.round(alb[1] * 255.0f), 0, 255);
                byte b = (byte) Math.clamp(Math.round(alb[2] * 255.0f), 0, 255);
                byte a = (byte) Math.clamp(Math.round(alb[3] * 255.0f), 0, 255);

                for (int y = y0; y <= y1; y++) {
                    for (int x = x0; x <= x1; x++) {
                        int idx = (y * w + x) * 4;
                        rgba[idx] = r;
                        rgba[idx + 1] = g;
                        rgba[idx + 2] = b;
                        rgba[idx + 3] = a;
                    }
                }
            }
            return rgba;
        }
    }

    /**
     * Scoped RenderDoc-friendly {@code GL_KHR_debug} group marker guard ({@code #0339}) structured
     * into {@code World3D}, {@code Billboards}, {@code PostFX}, and {@code UI}. Safe no-op when
     * debug markers or {@code GL_KHR_debug} are disabled.
     */
    public static final class RenderDocMarkerScope implements AutoCloseable {

        /** Standard RenderDoc capture hierarchy groups emitted per frame. */
        public static final List<String> STANDARD_GROUPS = List.of("World3D", "Billboards", "PostFX", "UI");

        private final boolean pushedNative;
        private boolean closed = false;

        /**
         * Opens a scoped RenderDoc debug group marker.
         *
         * @param markerId   numeric application marker ID
         * @param groupName  human-readable RenderDoc group label
         * @param emitGlCall {@code true} when GL_KHR_debug is enabled and an active GL context exists
         */
        public RenderDocMarkerScope(int markerId, String groupName, boolean emitGlCall) {
            Objects.requireNonNull(groupName, "groupName must not be null");
            boolean pushed = false;
            if (emitGlCall && hasKhrDebugSupport()) {
                try {
                    KHRDebug.glPushDebugGroup(KHRDebug.GL_DEBUG_SOURCE_APPLICATION, markerId, groupName);
                    pushed = true;
                } catch (RuntimeException ignored) {
                    pushed = false;
                }
            }
            this.pushedNative = pushed;
        }

        @Override
        public void close() {
            if (!closed && pushedNative) {
                try {
                    KHRDebug.glPopDebugGroup();
                } catch (RuntimeException ignored) {
                    // Ignore context shutdown races cleanly
                }
            }
            closed = true;
        }

        private static boolean hasKhrDebugSupport() {
            try {
                var caps = GL.getCapabilities();
                return caps != null && caps.GL_KHR_debug;
            } catch (IllegalStateException ignored) {
                return false;
            }
        }
    }

    /**
     * Validates all Wave P3-2 GLSL 3.30 Core shader sources using {@link CustomShaderRegistry}.
     *
     * @return immutable map of shader name to {@link CustomShaderRegistry.ReloadResult}
     */
    public static Map<String, CustomShaderRegistry.ReloadResult> validateAllAdvancedShaders() {
        Map<String, CustomShaderRegistry.ReloadResult> results = new LinkedHashMap<>();
        results.put("ibl_skybox_vertex",
                CustomShaderRegistry.validateFragmentSource("ibl_skybox_vertex",
                        IblEnvironment3D.SKYBOX_VERTEX_SHADER));
        results.put("ibl_skybox_fragment",
                CustomShaderRegistry.validateFragmentSource("ibl_skybox_fragment",
                        IblEnvironment3D.SKYBOX_FRAGMENT_SHADER));
        results.put("ssao_fragment",
                CustomShaderRegistry.validateFragmentSource("ssao_fragment",
                        SsaoPass.SSAO_FRAGMENT_SHADER));
        results.put("dof_bokeh_fragment",
                CustomShaderRegistry.validateFragmentSource("dof_bokeh_fragment",
                        DepthOfField3D.DOF_BOKEH_FRAGMENT_SHADER));
        results.put("god_rays_fragment",
                CustomShaderRegistry.validateFragmentSource("god_rays_fragment",
                        VolumetricGodRaysPass.GOD_RAYS_FRAGMENT_SHADER));
        return Map.copyOf(results);
    }
}
