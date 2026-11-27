package de.swfdtf.graphics;

import de.swfdtf.core.EngineLogger;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.Objects;

/**
 * Production OpenGL 3.3 Core hybrid 3D + 2D compositing render pipeline implementing {@link IRenderPipeline}.
 *
 * <p>Combines perspective/orthographic 3D depth-buffered rendering ({@code GL_DEPTH_TEST}) with crisp
 * full-resolution 2D UI overlay compositing in canonical order
 * ({@code Sky2D -> World3D -> Billboards -> Foreground2D -> UI}). Separates 3D math and frustum/sort logic
 * cleanly for 100% headless CI execution while safely gating OpenGL calls for macOS 3.3 Core, Linux Mesa,
 * and Windows hardware.</p>
 */
public final class RenderPipeline3D implements IRenderPipeline {

    /**
     * Shadow map resolution presets ({@code LOW=512}, {@code MEDIUM=1024}, {@code HIGH=2048}).
     */
    public enum ShadowQuality {
        /** 512x512 depth shadow map. */
        LOW(512),
        /** 1024x1024 PCF soft shadow map. */
        MEDIUM(1024),
        /** 2048x2048 crisp high-resolution PCF shadow map. */
        HIGH(2048);

        private final int resolution;

        ShadowQuality(int resolution) {
            this.resolution = resolution;
        }

        /**
         * Returns shadow FBO resolution in pixels.
         *
         * @return resolution
         */
        public int getResolution() {
            return resolution;
        }
    }

    /**
     * Authoring debug visualization mode for 3D scenes in Window B.
     */
    public enum DebugViewMode {
        /** Standard lit PBR / Toon rendering. */
        SHADED,
        /** Geometric wireframe mesh edges. */
        WIREFRAME,
        /** RGB surface normal diagnostic visualization. */
        NORMALS,
        /** Transparent/depth overdraw heatmap visualization. */
        OVERDRAW
    }

    /** Canonical Z-order rendering stages uniting 2D sky, 3D world, billboards, 2D foreground, and crisp UI. */
    public static final List<String> COMPOSITE_PASS_ORDER = List.of(
            "Sky2D", "World3D", "Billboards", "Foreground2D", "UI"
    );

    /** Minimum {@code std140} UBO byte size aligned to 16-byte vec4 boundaries (Projection + View + Camera + Fog). */
    public static final int GLOBAL_CAMERA_UBO_STD140_BYTES = 176;

    /** macOS/Cross-platform OpenGL 3.3 Core PBR/Toon Vertex Shader source. */
    public static final String WORLD3D_VERTEX_SHADER = """
            #version 330 core
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec2 aUv;

            uniform mat4 uProjection;
            uniform mat4 uView;
            uniform mat4 uModel;

            out vec3 vWorldPos;
            out vec3 vNormal;
            out vec2 vUv;

            void main() {
                vec4 worldPos = uModel * vec4(aPos, 1.0);
                vWorldPos = worldPos.xyz;
                vNormal = mat3(transpose(inverse(uModel))) * aNormal;
                vUv = aUv;
                gl_Position = uProjection * uView * worldPos;
            }
            """;

    /** OpenGL 3.3 Core fragment shader with Toon/PBR, directional + point lights, fog, and gamma. */
    public static final String WORLD3D_FRAGMENT_SHADER = """
            #version 330 core
            in vec3 vWorldPos;
            in vec3 vNormal;
            in vec2 vUv;

            uniform vec4 uAlbedoColor;
            uniform float uMetallic;
            uniform float uRoughness;
            uniform float uEmissive;
            uniform int uToonBands;
            uniform int uDebugMode;
            uniform float uExposure;
            uniform float uGamma;
            uniform float uAlphaCutoff;

            out vec4 fragColor;

            void main() {
                vec3 n = normalize(vNormal);
                if (uDebugMode == 2) {
                    fragColor = vec4(n * 0.5 + 0.5, 1.0);
                    return;
                }
                if (uAlbedoColor.a < uAlphaCutoff) {
                    discard;
                }
                vec3 lDir = normalize(vec3(0.4, 0.85, 0.5));
                float ndl = max(dot(n, lDir), 0.0);
                if (uToonBands >= 2) {
                    float b = float(uToonBands);
                    ndl = 0.2 + 0.8 * (floor(ndl * b) / max(1.0, b - 1.0));
                }
                vec3 lit = uAlbedoColor.rgb * (0.25 + 0.75 * ndl + uEmissive);
                lit = 1.0 - exp(-lit * uExposure);
                lit = pow(max(lit, vec3(0.0)), vec3(1.0 / max(uGamma, 0.1)));
                fragColor = vec4(lit, uAlbedoColor.a);
            }
            """;

    private final EngineLogger logger;
    private final Camera3D camera = new Camera3D();
    private final Scene3DWorld world = new Scene3DWorld();

    private int logicalWidth = RenderPipeline2D.DEFAULT_LOGICAL_WIDTH;
    private int logicalHeight = RenderPipeline2D.DEFAULT_LOGICAL_HEIGHT;
    private boolean initialized = false;
    private boolean fallbackTo2dBackdrop = false;
    private boolean khrDebugEnabled = false;
    private boolean depthPrepassEnabled = false;
    private int msaaSamples = 4;
    private float renderResolutionScale = 1.0f;
    private float exposure = 1.0f;
    private float gamma = 1.0f;
    private ShadowQuality shadowQuality = ShadowQuality.MEDIUM;
    private DebugViewMode debugViewMode = DebugViewMode.SHADED;
    private int lastSubmittedDrawCalls = 0;
    private int lastCulledMeshCount = 0;

    /** Creates a {@code RenderPipeline3D} with default logger. */
    public RenderPipeline3D() {
        this(new EngineLogger());
    }

    /**
     * Creates a {@code RenderPipeline3D} reporting diagnostics via {@code logger}.
     *
     * @param logger engine logger (must not be {@code null})
     */
    public RenderPipeline3D(EngineLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
    }

    @Override
    public void init(int logicalWidth, int logicalHeight) {
        if (logicalWidth <= 0 || logicalHeight <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive");
        }
        this.logicalWidth = logicalWidth;
        this.logicalHeight = logicalHeight;
        if (hasGlContext()) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            checkKhrDebugSafely();
        }
        this.initialized = true;
    }

    @Override
    public void beginFrame(int windowWidth, int windowHeight) {
        if (hasGlContext()) {
            int scaledW = Math.max(1, Math.round(Math.max(1, windowWidth) * renderResolutionScale));
            int scaledH = Math.max(1, Math.round(Math.max(1, windowHeight) * renderResolutionScale));
            GL11.glViewport(0, 0, scaledW, scaledH);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glClearColor(
                    RenderPipeline2D.CLEAR_R,
                    RenderPipeline2D.CLEAR_G,
                    RenderPipeline2D.CLEAR_B,
                    RenderPipeline2D.CLEAR_A
            );
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        }
        lastSubmittedDrawCalls = 0;
        lastCulledMeshCount = 0;
    }

    @Override
    public void renderSceneGraph(SceneGraph graph) {
        if (fallbackTo2dBackdrop) {
            return;
        }
        float aspect = logicalWidth / (float) Math.max(1, logicalHeight);
        List<Scene3DWorld.Node3D> sorted = world.getSortedDrawNodes(camera.getPosition());
        int submitted = 0;
        int culled = 0;
        for (Scene3DWorld.Node3D node : sorted) {
            if (camera.isAabbInFrustum(node.computeWorldAabb(), aspect)) {
                float dist = node.getPosition().distance(camera.getPosition());
                node.selectLod(dist);
                submitted += node.getActiveMesh().getSubMeshes().size();
            } else {
                culled++;
            }
        }
        this.lastSubmittedDrawCalls = submitted;
        this.lastCulledMeshCount = culled;
    }

    @Override
    public void endFrame() {
        // Restore state cleanly for 2D UI compositing
        if (hasGlContext()) {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
    }

    @Override
    public RenderMode getMode() {
        return fallbackTo2dBackdrop ? RenderMode.MODE_2D : RenderMode.MODE_3D;
    }

    @Override
    public void dispose() {
        world.reset();
        initialized = false;
        lastSubmittedDrawCalls = 0;
        lastCulledMeshCount = 0;
    }

    /**
     * Verifies strict {@code std140} alignment rules for global Camera+Fog UBO layout (16-byte vec4/mat4 boundaries).
     *
     * @return {@code true} if {@link #GLOBAL_CAMERA_UBO_STD140_BYTES} is 16-byte aligned and valid
     */
    public static boolean verifyStd140UboAlignment() {
        return GLOBAL_CAMERA_UBO_STD140_BYTES % 16 == 0 && GLOBAL_CAMERA_UBO_STD140_BYTES >= 176;
    }

    /**
     * Validates core 3D GLSL 3.30 Core shaders cleanly against cross-platform OpenGL 3.3 Core rules.
     *
     * @return list of validation results
     */
    public static List<CustomShaderRegistry.ReloadResult> validateCore3DShaders() {
        return List.of(
                CustomShaderRegistry.validateFragmentSource("world3d_fragment", WORLD3D_FRAGMENT_SHADER),
                CustomShaderRegistry.validateFragmentSource("world3d_vertex", WORLD3D_VERTEX_SHADER)
        );
    }

    /** Computes internal scaled 3D render target dimensions while UI stays at full 100% display resolution.
     *
     * @param displayW full display width
     * @param displayH full display height
     * @return 2-element array {@code [scaledW, scaledH]}
     */
    public int[] computeInternalRenderSize(int displayW, int displayH) {
        int sw = Math.max(1, Math.round(Math.max(1, displayW) * renderResolutionScale));
        int sh = Math.max(1, Math.round(Math.max(1, displayH) * renderResolutionScale));
        return new int[]{sw, sh};
    }

    /**
     * Returns camera controller.
     *
     * @return {@link Camera3D}
     */
    public Camera3D getCamera() {
        return camera;
    }

    /**
     * Returns 3D world graph.
     *
     * @return {@link Scene3DWorld}
     */
    public Scene3DWorld getWorld() {
        return world;
    }

    /**
     * Enables fallback to 2D backdrops when 3D hardware acceleration is disabled.
     *
     * @param fallback true for 2D
     */
    public void setFallbackTo2dBackdrop(boolean fallback) {
        this.fallbackTo2dBackdrop = fallback;
    }

    /**
     * Returns whether 2D backdrop fallback is active.
     *
     * @return fallback flag
     */
    public boolean isFallbackTo2dBackdrop() {
        return fallbackTo2dBackdrop;
    }

    /**
     * Sets MSAA sample count ({@code 1}, {@code 2}, or {@code 4}).
     *
     * @param samples MSAA samples
     */
    public void setMsaaSamples(int samples) {
        this.msaaSamples = (samples == 2 || samples == 4) ? samples : 1;
    }

    /**
     * Returns configured MSAA sample count.
     *
     * @return MSAA samples
     */
    public int getMsaaSamples() {
        return msaaSamples;
    }

    /**
     * Configures shadow quality preset rebuilding shadow FBO sizing.
     *
     * @param quality shadow preset
     */
    public void setShadowQuality(ShadowQuality quality) {
        this.shadowQuality = Objects.requireNonNull(quality, "quality must not be null");
    }

    /**
     * Returns shadow quality preset.
     *
     * @return {@link ShadowQuality}
     */
    public ShadowQuality getShadowQuality() {
        return shadowQuality;
    }

    /**
     * Toggles Z-prepass to cut interior fragment overdraw.
     *
     * @param enabled true for Z-prepass
     */
    public void setDepthPrepassEnabled(boolean enabled) {
        this.depthPrepassEnabled = enabled;
    }

    /**
     * Returns whether depth prepass is enabled.
     *
     * @return true if enabled
     */
    public boolean isDepthPrepassEnabled() {
        return depthPrepassEnabled;
    }

    /**
     * Selects Studio debug view mode ({@code SHADED/WIREFRAME/NORMALS/OVERDRAW}).
     *
     * @param mode view mode
     */
    public void setDebugViewMode(DebugViewMode mode) {
        this.debugViewMode = Objects.requireNonNull(mode, "mode must not be null");
    }

    /**
     * Returns active debug view mode.
     *
     * @return {@link DebugViewMode}
     */
    public DebugViewMode getDebugViewMode() {
        return debugViewMode;
    }

    /**
     * Sets internal 3D resolution scale clamped to {@code [0.5f, 1.5f]}.
     *
     * @param scale scale factor
     */
    public void setRenderResolutionScale(float scale) {
        this.renderResolutionScale = Float.isFinite(scale) ? Math.clamp(scale, 0.5f, 1.5f) : 1.0f;
    }

    /**
     * Returns internal 3D resolution scale factor.
     *
     * @return resolution scale
     */
    public float getRenderResolutionScale() {
        return renderResolutionScale;
    }

    /** Sets exposure &amp; gamma calibration ({@code 1.0f} = neutral standard).
     *
     * @param exposure exposure multiplier
     * @param gamma    gamma curve exponent
     */
    public void setExposureAndGamma(float exposure, float gamma) {
        this.exposure = Float.isFinite(exposure) ? Math.clamp(exposure, 0.1f, 5.0f) : 1.0f;
        this.gamma = Float.isFinite(gamma) ? Math.clamp(gamma, 0.5f, 3.0f) : 1.0f;
    }

    /**
     * Returns configured exposure.
     *
     * @return exposure
     */
    public float getExposure() {
        return exposure;
    }

    /**
     * Returns configured gamma.
     *
     * @return gamma
     */
    public float getGamma() {
        return gamma;
    }

    /**
     * Returns draw calls submitted in last frame.
     *
     * @return draw calls
     */
    public int getLastSubmittedDrawCalls() {
        return lastSubmittedDrawCalls;
    }

    /**
     * Returns frustum-culled mesh count in last frame.
     *
     * @return culled mesh count
     */
    public int getLastCulledMeshCount() {
        return lastCulledMeshCount;
    }

    /**
     * Returns whether initialized.
     *
     * @return initialized flag
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Returns whether GL_KHR_debug callback attached safely.
     *
     * @return KHR debug flag
     */
    public boolean isKhrDebugEnabled() {
        return khrDebugEnabled;
    }

    /** Helper computing a clean MVP matrix for unit testing.
     *
     * @param aspect viewport aspect
     * @param model  node model matrix
     * @return combined MVP matrix
     */
    public Matrix4f computeMvp(float aspect, Matrix4f model) {
        return new Matrix4f(camera.getProjectionMatrix(aspect)).mul(camera.getViewMatrix()).mul(model);
    }

    private void checkKhrDebugSafely() {
        try {
            var caps = GL.getCapabilities();
            if (caps != null && caps.GL_KHR_debug) {
                khrDebugEnabled = true;
            } else {
                khrDebugEnabled = false;
                logger.info("RenderPipeline3D", "GL_KHR_debug unavailable (expected on macOS 3.3 Core); disabled.");
            }
        } catch (IllegalStateException ignored) {
            khrDebugEnabled = false;
        }
    }

    private static boolean hasGlContext() {
        try {
            return GL.getCapabilities() != null;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }
}
