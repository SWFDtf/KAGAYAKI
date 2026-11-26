package de.swfdtf.graphics;

import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.ScreenShake;
import de.swfdtf.math.Transform2D;
import de.swfdtf.math.Vec2f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Production LWJGL 3 / OpenGL 3.3 Core 2D rendering pipeline for KAGAYAKI.
 *
 * <p>Renders all Z-ordered {@link SceneGraph} layers into an offscreen {@code RGBA8}
 * {@link Framebuffer} sized to the logical resolution (default {@code 1920x1080}) using
 * orthographic projection {@code ortho(0, logicalW, logicalH, 0, -1000, 1000)},
 * premultiplied alpha blending ({@code GL_ONE, GL_ONE_MINUS_SRC_ALPHA}), 4x4 color matrices,
 * and SDF anti-aliased rounded rectangles with optional borders. On {@link #endFrame()}, the
 * offscreen texture is blitted onto the window framebuffer with bilinear ({@code GL_LINEAR})
 * filtering and aspect-preserving letterboxing/pillarboxing over the blueprint canvas color
 * {@code #0d0e15}.</p>
 */
public final class RenderPipeline2D implements IRenderPipeline {

    /** Default logical canvas width in pixels. */
    public static final int DEFAULT_LOGICAL_WIDTH = 1920;

    /** Default logical canvas height in pixels. */
    public static final int DEFAULT_LOGICAL_HEIGHT = 1080;

    /** Blueprint background red channel ({@code #0d0e15}). */
    public static final float CLEAR_R = 0x0D / 255.0f;

    /** Blueprint background green channel ({@code #0d0e15}). */
    public static final float CLEAR_G = 0x0E / 255.0f;

    /** Blueprint background blue channel ({@code #0d0e15}). */
    public static final float CLEAR_B = 0x15 / 255.0f;

    /** Blueprint background alpha channel. */
    public static final float CLEAR_A = 1.0f;

    private static final Rect2f FULL_UV_RECT = new Rect2f(0.0f, 0.0f, 1.0f, 1.0f);
    private static final float[] NO_BORDER_COLOR = new float[]{0.0f, 0.0f, 0.0f, 0.0f};
    private static final Comparator<SceneNode> CHILD_Z_COMPARATOR =
            Comparator.comparingInt(SceneNode::getZOrder);

    /**
     * Creates an uninitialized {@code RenderPipeline2D}; call {@link #init(int, int)} before rendering.
     */
    public RenderPipeline2D() {
    }

    /**
     * Configuration parameters for the offscreen FBO ping-pong post-processing pipeline.
     *
     * @param gaussianBlurRadius separable Gaussian blur radius in pixels ({@code 0} disables full-scene blur)
     * @param bloomThreshold     luminance threshold above which bright pixels contribute to bloom
     * @param bloomIntensity     additive bloom glow multiplier ({@code 0} disables bloom pass)
     */
    public record PostProcessConfig(float gaussianBlurRadius, float bloomThreshold, float bloomIntensity) {
        /** Disabled post-processing configuration. */
        public static final PostProcessConfig NONE = new PostProcessConfig(0.0f, 0.85f, 0.0f);

        /** Sanitizes non-finite or negative parameters cleanly. */
        public PostProcessConfig {
            gaussianBlurRadius = (Float.isFinite(gaussianBlurRadius) && gaussianBlurRadius > 0.0f)
                    ? gaussianBlurRadius
                    : 0.0f;
            bloomThreshold = Float.isFinite(bloomThreshold)
                    ? Math.clamp(bloomThreshold, 0.0f, 1.0f)
                    : 0.85f;
            bloomIntensity = (Float.isFinite(bloomIntensity) && bloomIntensity > 0.0f)
                    ? bloomIntensity
                    : 0.0f;
        }
    }

    private static final String QUAD_VERTEX_SHADER = """
            #version 330 core
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aUv;

            uniform mat4 uProjection;
            uniform mat4 uModel;
            uniform vec4 uUvRect;

            out vec2 vTexCoord;
            out vec2 vLocalPos;

            void main() {
                gl_Position = uProjection * uModel * vec4(aPos, 0.0, 1.0);
                vTexCoord = uUvRect.xy + aUv * uUvRect.zw;
                vLocalPos = aPos;
            }
            """;

    private static final String QUAD_FRAGMENT_SHADER = """
            #version 330 core
            in vec2 vTexCoord;
            in vec2 vLocalPos;

            uniform mat4 uColorMatrix;
            uniform vec4 uColor;
            uniform vec4 uBorderColor;
            uniform int uUseTexture;
            uniform sampler2D uTexture;
            uniform vec2 uRectSize;
            uniform float uCornerRadius;
            uniform float uBorderThickness;
            uniform int uSdfTextMode;
            uniform float uSdfPxRange;
            uniform int uShadowMode;
            uniform float uShadowRadius;
            uniform int uSilhouetteMode;
            uniform vec4 uSilhouetteColor;
            uniform int uGradientMode;
            uniform vec4 uGradientTopLeft;
            uniform vec4 uGradientTopRight;
            uniform vec4 uGradientBottomLeft;
            uniform vec4 uGradientBottomRight;

            out vec4 fragColor;

            float roundedBoxSdf(vec2 p, vec2 halfSize, float radius) {
                vec2 q = abs(p) - halfSize + vec2(radius);
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
            }

            float median3(vec3 v) {
                return max(min(v.r, v.g), min(max(v.r, v.g), v.b));
            }

            void main() {
                if (uShadowMode != 0) {
                    vec2 size = max(uRectSize, vec2(1.0));
                    vec2 halfSize = size * 0.5;
                    float pad = max(uShadowRadius, 0.0);
                    vec2 innerHalf = max(halfSize - vec2(pad), vec2(1.0));
                    float radius = clamp(uCornerRadius, 0.0, min(innerHalf.x, innerHalf.y));
                    vec2 p = vLocalPos * size - halfSize;
                    float dist = roundedBoxSdf(p, innerHalf, radius);
                    float softEdge = max(pad, 1.0);
                    float shadowAlpha = 1.0 - smoothstep(-softEdge * 0.35, softEdge, dist);
                    fragColor = uColorMatrix * (uColor * shadowAlpha);
                    return;
                }

                if (uSdfTextMode != 0 && uUseTexture != 0) {
                    vec4 texel = texture(uTexture, vTexCoord);
                    float sd = (uSdfTextMode == 2) ? median3(texel.rgb) : texel.a;
                    float smoothing = max(fwidth(sd), 1.0 / max(uSdfPxRange * 2.0, 2.0));
                    float glyphAlpha = smoothstep(0.5 - smoothing, 0.5 + smoothing, sd);
                    fragColor = uColorMatrix * (uColor * glyphAlpha);
                    return;
                }

                vec4 baseSample = uUseTexture != 0
                    ? texture(uTexture, vTexCoord) * uColor
                    : (uGradientMode != 0
                        ? mix(
                            mix(uGradientTopLeft, uGradientTopRight, clamp(vLocalPos.x, 0.0, 1.0)),
                            mix(uGradientBottomLeft, uGradientBottomRight, clamp(vLocalPos.x, 0.0, 1.0)),
                            clamp(vLocalPos.y, 0.0, 1.0))
                        : uColor);

                if (uSilhouetteMode != 0 && uUseTexture != 0) {
                    float a = texture(uTexture, vTexCoord).a * uColor.a;
                    baseSample = vec4(uSilhouetteColor.rgb * a, a * uSilhouetteColor.a);
                }

                vec4 finalColor = baseSample;

                if (uCornerRadius > 0.0 || uBorderThickness > 0.0) {
                    vec2 size = max(uRectSize, vec2(1.0));
                    vec2 halfSize = size * 0.5;
                    float radius = clamp(uCornerRadius, 0.0, min(halfSize.x, halfSize.y));
                    vec2 p = vLocalPos * size - halfSize;

                    float dist = roundedBoxSdf(p, halfSize, radius);
                    float aa = max(fwidth(dist), 0.75);
                    float outerAlpha = 1.0 - smoothstep(-aa, aa, dist);

                    if (uBorderThickness > 0.0) {
                        float innerMask = 1.0 - smoothstep(-aa, aa, dist + uBorderThickness);
                        float borderMix = clamp(outerAlpha - innerMask, 0.0, 1.0);
                        finalColor = baseSample * innerMask + uBorderColor * borderMix;
                    } else {
                        finalColor = baseSample * outerAlpha;
                    }
                }

                fragColor = uColorMatrix * finalColor;
            }
            """;

    private static final String POSTFX_VERTEX_SHADER = """
            #version 330 core
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aUv;

            uniform mat4 uProjection;
            uniform mat4 uModel;

            out vec2 vTexCoord;

            void main() {
                gl_Position = uProjection * uModel * vec4(aPos, 0.0, 1.0);
                vTexCoord = aUv;
            }
            """;

    private static final String GAUSSIAN_BLUR_FRAGMENT_SHADER = """
            #version 330 core
            in vec2 vTexCoord;

            uniform sampler2D uTexture;
            uniform vec2 uTexelStep;
            uniform float uBlurRadius;

            out vec4 fragColor;

            void main() {
                float r = max(uBlurRadius, 0.0);
                if (r <= 0.001) {
                    fragColor = texture(uTexture, vTexCoord);
                    return;
                }
                vec4 sum = texture(uTexture, vTexCoord) * 0.227027;
                float weights[4] = float[4](0.1945946, 0.1216216, 0.054054, 0.016216);
                for (int i = 0; i < 4; i++) {
                    vec2 offset = uTexelStep * (float(i + 1) * (r * 0.25));
                    sum += texture(uTexture, clamp(vTexCoord + offset, 0.0, 1.0)) * weights[i];
                    sum += texture(uTexture, clamp(vTexCoord - offset, 0.0, 1.0)) * weights[i];
                }
                fragColor = sum;
            }
            """;

    private static final String BLOOM_THRESHOLD_FRAGMENT_SHADER = """
            #version 330 core
            in vec2 vTexCoord;

            uniform sampler2D uTexture;
            uniform float uThreshold;

            out vec4 fragColor;

            void main() {
                vec4 color = texture(uTexture, vTexCoord);
                float lum = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
                float factor = smoothstep(uThreshold, uThreshold + 0.15, lum);
                fragColor = vec4(color.rgb * factor, color.a * factor);
            }
            """;

    private static final String BLOOM_COMPOSITE_FRAGMENT_SHADER = """
            #version 330 core
            in vec2 vTexCoord;

            uniform sampler2D uSceneTexture;
            uniform sampler2D uBloomTexture;
            uniform float uBloomIntensity;

            out vec4 fragColor;

            void main() {
                vec4 baseColor = texture(uSceneTexture, vTexCoord);
                vec4 glowColor = texture(uBloomTexture, vTexCoord);
                fragColor = vec4(baseColor.rgb + glowColor.rgb * uBloomIntensity, max(baseColor.a, glowColor.a));
            }
            """;

    private static final String BLIT_VERTEX_SHADER = """
            #version 330 core
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aUv;

            uniform mat4 uProjection;
            uniform mat4 uModel;

            out vec2 vTexCoord;

            void main() {
                gl_Position = uProjection * uModel * vec4(aPos, 0.0, 1.0);
                // Logical offscreen FBO is rendered with top-down ortho(0, W, H, 0),
                // so top of screen (y=0) maps to FBO texture v=1.0.
                vTexCoord = vec2(aUv.x, 1.0 - aUv.y);
            }
            """;

    private static final String BLIT_FRAGMENT_SHADER = """
            #version 330 core
            in vec2 vTexCoord;

            uniform sampler2D uTexture;
            uniform mat4 uAccessibilityMatrix;
            uniform float uChromaticAberration;
            uniform float uVignetteIntensity;
            uniform vec2 uVignetteFocus;
            uniform int uHalftoneEnabled;
            uniform float uHalftoneDotSize;
            uniform float uDistortionStrength;
            uniform float uDistortionTime;
            uniform float uDistortionTopY;
            uniform int uDitherEnabled;
            uniform int uSrgbOutput;
            uniform int uHdrToneMap;
            uniform vec2 uCanvasSize;

            out vec4 fragColor;

            vec3 acesToneMap(vec3 x) {
                return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
            }

            void main() {
                vec2 uv = vTexCoord;
                if (uDistortionStrength > 0.0 && (1.0 - uv.y) >= uDistortionTopY) {
                    float wave = sin(uv.y * 42.0 + uDistortionTime * 4.5) * 0.008 * uDistortionStrength;
                    uv.x = clamp(uv.x + wave, 0.0, 1.0);
                }

                vec4 col;
                if (uChromaticAberration > 0.0001) {
                    float shift = uChromaticAberration * 0.006;
                    float r = texture(uTexture, clamp(uv + vec2(shift, 0.0), 0.0, 1.0)).r;
                    vec2 gb = texture(uTexture, uv).ga;
                    float b = texture(uTexture, clamp(uv - vec2(shift, 0.0), 0.0, 1.0)).b;
                    col = vec4(r, gb.x, b, gb.y);
                } else {
                    col = texture(uTexture, uv);
                }

                if (uHalftoneEnabled != 0) {
                    float dotSz = max(uHalftoneDotSize, 2.0);
                    vec2 cell = fract((uv * max(uCanvasSize, vec2(1.0))) / dotSz) - 0.5;
                    float lum = dot(col.rgb, vec3(0.2126, 0.7152, 0.0722));
                    float mask = step(length(cell), sqrt(clamp(1.0 - lum, 0.0, 1.0)) * 0.65);
                    col.rgb = mix(col.rgb, col.rgb * 0.35, mask * 0.6);
                }

                if (uVignetteIntensity > 0.0001) {
                    float d = length(vec2(uv.x, 1.0 - uv.y) - uVignetteFocus);
                    float vig = clamp(1.0 - smoothstep(0.32, 0.85, d) * uVignetteIntensity, 0.0, 1.0);
                    col.rgb *= vig;
                }

                col = uAccessibilityMatrix * col;
                if (uHdrToneMap != 0) {
                    col.rgb = acesToneMap(max(col.rgb, vec3(0.0)));
                }
                if (uSrgbOutput != 0) {
                    col.rgb = pow(max(col.rgb, vec3(0.0)), vec3(1.0 / 2.2));
                }
                if (uDitherEnabled != 0) {
                    vec2 px = uv * max(uCanvasSize, vec2(1.0));
                    float n = fract(sin(dot(px, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
                    col.rgb = clamp(col.rgb + n / 255.0, 0.0, 1.0);
                }
                fragColor = col;
            }
            """;

    /**
     * Extended post-processing and color-output descriptor configuring chromatic aberration, focus vignette,
     * water/heat distortion, manga halftone screentone, ACES HDR tone mapping, sRGB gamma curve, and
     * 8-bit backbuffer triangular dithering.
     *
     * @param chromaticAberration intensity in {@code [0, 1]} ({@code 0.0f} = bit-identical output)
     * @param vignetteIntensity   vignette strength in {@code [0, 1]}
     * @param vignetteFocusX      normalized focal X in {@code [0, 1]}
     * @param vignetteFocusY      normalized focal Y in {@code [0, 1]}
     * @param halftoneEnabled     whether manga halftone raster is enabled
     * @param halftoneDotSizePx   halftone cell size in pixels
     * @param distortionStrength  lower-region water/heat distortion strength ({@code 0} = disabled)
     * @param distortionTopY      normalized screen Y threshold where distortion begins
     * @param ditheringEnabled    whether 8-bit backbuffer triangular dithering is enabled
     * @param srgbOutputEnabled   whether linear-to-sRGB gamma conversion runs on composite output
     * @param hdrToneMapEnabled   whether ACES Filmic tone mapping runs on HDR highlights
     */
    public record ExtendedPostProcessConfig(
            float chromaticAberration,
            float vignetteIntensity,
            float vignetteFocusX,
            float vignetteFocusY,
            boolean halftoneEnabled,
            float halftoneDotSizePx,
            float distortionStrength,
            float distortionTopY,
            boolean ditheringEnabled,
            boolean srgbOutputEnabled,
            boolean hdrToneMapEnabled
    ) {
        /** Disabled neutral configuration preserving exact bit-identical pass-through output. */
        public static final ExtendedPostProcessConfig NONE = new ExtendedPostProcessConfig(
                0.0f, 0.0f, 0.5f, 0.5f, false, 6.0f, 0.0f, 0.65f, false, false, false
        );
    }

    /**
     * Reusable scratch batch item populated during zero-allocation frame queue sorting (10,000+ quads).
     */
    public static final class ScratchQuadCommand {
        int zOrder;
        int textureId;
        float x;
        float y;
        float width;
        float height;

        /** Creates an empty reusable quad command slot. */
        public ScratchQuadCommand() {
        }
    }

    private int logicalWidth = DEFAULT_LOGICAL_WIDTH;
    private int logicalHeight = DEFAULT_LOGICAL_HEIGHT;
    private int currentWindowWidth = 0;
    private int currentWindowHeight = 0;

    private Framebuffer offscreenFbo;
    private Framebuffer postProcessPingFbo;
    private Framebuffer postProcessPongFbo;
    private ShaderProgram quadShader;
    private ShaderProgram blitShader;
    private ShaderProgram gaussianBlurShader;
    private ShaderProgram bloomThresholdShader;
    private ShaderProgram bloomCompositeShader;
    private int quadVao = 0;
    private int quadVbo = 0;

    private final Matrix4f logicalProjection = new Matrix4f();
    private final Matrix4f postProcessProjection = new Matrix4f().setOrtho(0.0f, 1.0f, 0.0f, 1.0f, -1.0f, 1.0f);
    private final Matrix4f windowProjection = new Matrix4f();
    private final Matrix4f modelMatrix = new Matrix4f();
    private final Deque<Rect2f> scissorStack = new ArrayDeque<>();

    private PostProcessConfig postProcessConfig = PostProcessConfig.NONE;
    private ExtendedPostProcessConfig extendedPostProcessConfig = ExtendedPostProcessConfig.NONE;
    private CinematicLetterboxController letterboxController;
    private ColorBlindnessFilter colorBlindnessFilter;
    private GlResourceAuditor resourceAuditor;
    private float maxParallaxOffsetPx = 180.0f;
    private final float[] clearColorRgba = new float[]{CLEAR_R, CLEAR_G, CLEAR_B, CLEAR_A};
    private boolean debugWireframeEnabled = false;
    private boolean safeAreaGuidesEnabled = false;
    private boolean hdrFboPreferred = false;
    private double postEffectTimeSeconds = 0.0;
    private final List<ScratchQuadCommand> scratchBatchQueue = new ArrayList<>(256);
    private int scratchBatchCount = 0;
    private float frameNodeBlurRadius = 0.0f;
    private Rect2f lastLetterboxViewport = new Rect2f(0.0f, 0.0f, 0.0f, 0.0f);
    private Rect2f currentRenderBounds = null;
    private float currentEffectiveAlpha = 1.0f;
    private ColorMatrix4f currentEffectiveColorMatrix = ColorMatrix4f.identity();
    private float currentRotationDegrees = 0.0f;
    private float currentAnchorX = 0.0f;
    private float currentAnchorY = 0.0f;
    private boolean initialized = false;

    /**
     * Computes the aspect-preserving letterbox/pillarbox destination rectangle inside a window
     * framebuffer of size {@code windowW x windowH} for a logical canvas of {@code logicalW x logicalH}.
     *
     * <p>Uses uniform scale {@code S = min(windowW / (float) logicalW, windowH / (float) logicalH)}
     * and centers the scaled canvas within the window.</p>
     *
     * @param windowW  window framebuffer width in pixels
     * @param windowH  window framebuffer height in pixels
     * @param logicalW logical canvas width in pixels ({@code > 0})
     * @param logicalH logical canvas height in pixels ({@code > 0})
     * @return centered destination {@link Rect2f} in top-down window coordinates
     * @throws IllegalArgumentException if {@code logicalW <= 0} or {@code logicalH <= 0}
     */
    public static Rect2f computeLetterboxViewport(int windowW, int windowH, int logicalW, int logicalH) {
        if (logicalW <= 0 || logicalH <= 0) {
            throw new IllegalArgumentException(
                    "Logical dimensions must be positive, got " + logicalW + "x" + logicalH
            );
        }
        if (windowW <= 0 || windowH <= 0) {
            return new Rect2f(0.0f, 0.0f, 0.0f, 0.0f);
        }

        float scale = Math.min(windowW / (float) logicalW, windowH / (float) logicalH);
        float destW = logicalW * scale;
        float destH = logicalH * scale;
        float destX = (windowW - destW) * 0.5f;
        float destY = (windowH - destH) * 0.5f;
        return new Rect2f(destX, destY, destW, destH);
    }

    @Override
    public void init(int logicalWidth, int logicalHeight) {
        if (logicalWidth <= 0 || logicalHeight <= 0) {
            throw new IllegalArgumentException(
                    "Logical dimensions must be positive, got " + logicalWidth + "x" + logicalHeight
            );
        }
        if (initialized) {
            dispose();
        }

        this.logicalWidth = logicalWidth;
        this.logicalHeight = logicalHeight;
        this.logicalProjection.setOrtho(0.0f, logicalWidth, logicalHeight, 0.0f, -1000.0f, 1000.0f);

        try {
            this.offscreenFbo = new Framebuffer(logicalWidth, logicalHeight);
            this.offscreenFbo.init();
            this.postProcessPingFbo = new Framebuffer(logicalWidth, logicalHeight);
            this.postProcessPingFbo.init();
            this.postProcessPongFbo = new Framebuffer(logicalWidth, logicalHeight);
            this.postProcessPongFbo.init();

            this.quadShader = new ShaderProgram(QUAD_VERTEX_SHADER, QUAD_FRAGMENT_SHADER);
            this.blitShader = new ShaderProgram(BLIT_VERTEX_SHADER, BLIT_FRAGMENT_SHADER);
            this.gaussianBlurShader = new ShaderProgram(POSTFX_VERTEX_SHADER, GAUSSIAN_BLUR_FRAGMENT_SHADER);
            this.bloomThresholdShader = new ShaderProgram(POSTFX_VERTEX_SHADER, BLOOM_THRESHOLD_FRAGMENT_SHADER);
            this.bloomCompositeShader = new ShaderProgram(POSTFX_VERTEX_SHADER, BLOOM_COMPOSITE_FRAGMENT_SHADER);

            initQuadGeometry();
            if (resourceAuditor != null) {
                resourceAuditor.recordCreate(GlResourceAuditor.ResourceKind.FBO);
                resourceAuditor.recordCreate(GlResourceAuditor.ResourceKind.FBO);
                resourceAuditor.recordCreate(GlResourceAuditor.ResourceKind.FBO);
                resourceAuditor.recordCreate(GlResourceAuditor.ResourceKind.SHADER);
                resourceAuditor.recordCreate(GlResourceAuditor.ResourceKind.SHADER);
                resourceAuditor.recordCreate(GlResourceAuditor.ResourceKind.SHADER);
                resourceAuditor.recordCreate(GlResourceAuditor.ResourceKind.SHADER);
                resourceAuditor.recordCreate(GlResourceAuditor.ResourceKind.SHADER);
                resourceAuditor.recordCreate(GlResourceAuditor.ResourceKind.VAO);
                resourceAuditor.recordCreate(GlResourceAuditor.ResourceKind.VBO);
            }
            this.scissorStack.clear();
            this.frameNodeBlurRadius = 0.0f;
            this.lastLetterboxViewport = new Rect2f(0.0f, 0.0f, 0.0f, 0.0f);
            this.initialized = true;
        } catch (RuntimeException ex) {
            dispose();
            throw ex;
        }
    }

    private void initQuadGeometry() {
        float[] vertices = {
            // x,    y,    u,    v
            0.0f, 0.0f, 0.0f, 0.0f,
            1.0f, 0.0f, 1.0f, 0.0f,
            1.0f, 1.0f, 1.0f, 1.0f,

            0.0f, 0.0f, 0.0f, 0.0f,
            1.0f, 1.0f, 1.0f, 1.0f,
            0.0f, 1.0f, 0.0f, 1.0f
        };

        quadVao = GL30.glGenVertexArrays();
        quadVbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(quadVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(vertices.length);
            buffer.put(vertices).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);
        }

        int stride = 4 * Float.BYTES;
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, stride, 0L);

        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 2L * Float.BYTES);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    @Override
    public void beginFrame(int windowWidth, int windowHeight) {
        requireInitialized();
        this.currentWindowWidth = Math.max(0, windowWidth);
        this.currentWindowHeight = Math.max(0, windowHeight);
        this.scissorStack.clear();
        this.frameNodeBlurRadius = 0.0f;

        offscreenFbo.bind();
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);

        GL11.glClearColor(clearColorRgba[0], clearColorRgba[1], clearColorRgba[2], clearColorRgba[3]);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        this.scratchBatchCount = 0;
    }

    @Override
    public void renderSceneGraph(SceneGraph graph) {
        requireInitialized();
        if (graph == null) {
            return;
        }

        ScreenShake shake = graph.getScreenShake();
        Vec2f shakeOffset = (shake != null && !shake.isFinished()) ? shake.getOffset() : Vec2f.ZERO;
        float mouseX = graph.getNormalizedMouseX();
        float mouseY = graph.getNormalizedMouseY();

        for (SceneLayer layer : graph.getLayersSorted()) {
            if (!layer.isVisible()) {
                continue;
            }

            boolean applyShake = layer.getZOrder() < SceneLayer.Z_SCREENS;
            float layerShakeX = applyShake ? shakeOffset.x() : 0.0f;
            float layerShakeY = applyShake ? shakeOffset.y() : 0.0f;
            float rawLayerPxX = mouseX * layer.getParallaxFactor() * (logicalWidth * 0.5f);
            float rawLayerPxY = mouseY * layer.getParallaxFactor() * (logicalHeight * 0.5f);
            float layerParallaxX = Math.clamp(rawLayerPxX, -maxParallaxOffsetPx, maxParallaxOffsetPx);
            float layerParallaxY = Math.clamp(rawLayerPxY, -maxParallaxOffsetPx, maxParallaxOffsetPx);

            if (letterboxController != null && letterboxController.isActive()
                    && layer.getZOrder() >= SceneLayer.Z_MODAL) {
                drawActiveLetterboxBars();
            }

            for (SceneNode node : layer.getSortedNodes()) {
                renderNodeRecursive(
                        node,
                        0.0f,
                        0.0f,
                        logicalWidth,
                        logicalHeight,
                        1.0f,
                        ColorMatrix4f.identity(),
                        layerShakeX,
                        layerShakeY,
                        layerParallaxX,
                        layerParallaxY,
                        mouseX,
                        mouseY
                );
            }
        }
    }

    private void renderNodeRecursive(
            SceneNode node,
            float containerX,
            float containerY,
            float containerW,
            float containerH,
            float inheritedAlpha,
            ColorMatrix4f inheritedColorMatrix,
            float shakeX,
            float shakeY,
            float layerParallaxX,
            float layerParallaxY,
            float normalizedMouseX,
            float normalizedMouseY
    ) {
        if (node == null || !node.isVisible()) {
            return;
        }

        Transform2D transform = node.getTransform();
        Rect2f localBounds = transform.resolveBounds(
                node.getWidth(),
                node.getHeight(),
                containerW,
                containerH
        );

        float nodeParallaxX = normalizedMouseX * transform.getParallaxX() * (logicalWidth * 0.5f);
        float nodeParallaxY = normalizedMouseY * transform.getParallaxY() * (logicalHeight * 0.5f);

        float drawX = containerX + localBounds.x() + shakeX + layerParallaxX + nodeParallaxX;
        float drawY = containerY + localBounds.y() + shakeY + layerParallaxY + nodeParallaxY;
        float drawW = localBounds.width();
        float drawH = localBounds.height();

        float effectiveAlpha = Math.clamp(inheritedAlpha * transform.getAlpha(), 0.0f, 1.0f);
        ColorMatrix4f effectiveColorMatrix = combineColorMatrices(inheritedColorMatrix, transform.getColorMatrix());
        float nodeBlur = transform.getBlur();
        if (Float.isFinite(nodeBlur) && nodeBlur > frameNodeBlurRadius) {
            frameNodeBlurRadius = nodeBlur;
        }

        if (node.shouldDrawStandardQuad() && drawW > 0.0f && drawH > 0.0f && effectiveAlpha > 0.0f) {
            applyNodeBlendMode(node.getBlendMode());
            try {
                if (node.hasShadow()) {
                    float radius = node.getShadowRadius();
                    float[] shadowPremul = node.getPremultipliedShadowColor(effectiveAlpha);
                    drawQuadWithPivotInternal(
                            0,
                            drawX + node.getShadowOffsetX() - radius,
                            drawY + node.getShadowOffsetY() - radius,
                            drawW + radius * 2.0f,
                            drawH + radius * 2.0f,
                            drawX + transform.getXanchor() * drawW + node.getShadowOffsetX(),
                            drawY + transform.getYanchor() * drawH + node.getShadowOffsetY(),
                            transform.getRotate(),
                            FULL_UV_RECT,
                            shadowPremul,
                            effectiveColorMatrix,
                            node.getCornerRadius(),
                            0.0f,
                            NO_BORDER_COLOR,
                            0,
                            4.0f,
                            1,
                            radius
                    );
                }
                if (node.isHovered() && node.getOutlineThickness() > 0.0f) {
                    float pad = node.getOutlineThickness();
                    float[] outCol = node.getOutlineColorRgba();
                    float[] outPremul = new float[]{
                        outCol[0] * outCol[3] * effectiveAlpha,
                        outCol[1] * outCol[3] * effectiveAlpha,
                        outCol[2] * outCol[3] * effectiveAlpha,
                        outCol[3] * effectiveAlpha
                    };
                    drawQuadWithPivotInternal(
                            0,
                            drawX - pad,
                            drawY - pad,
                            drawW + pad * 2.0f,
                            drawH + pad * 2.0f,
                            drawX + transform.getXanchor() * drawW,
                            drawY + transform.getYanchor() * drawH,
                            transform.getRotate(),
                            FULL_UV_RECT,
                            new float[]{0.0f, 0.0f, 0.0f, 0.0f},
                            effectiveColorMatrix,
                            node.getCornerRadius() + pad * 0.5f,
                            pad,
                            outPremul,
                            0,
                            4.0f,
                            0,
                            0.0f
                    );
                }
                Rect2f effectiveUv = resolveEffectiveUv(node, transform);
                float[] fillPremul = node.getPremultipliedFillColor(effectiveAlpha);
                float[] borderPremul = node.getBorderThickness() > 0.0f
                        ? node.getPremultipliedBorderColor(effectiveAlpha)
                        : NO_BORDER_COLOR;

                float[] sil = node.getSilhouetteColorRgba();
                drawQuadWithPivotInternal(
                        node.getTextureId(),
                        drawX,
                        drawY,
                        drawW,
                        drawH,
                        drawX + transform.getXanchor() * drawW,
                        drawY + transform.getYanchor() * drawH,
                        transform.getRotate(),
                        effectiveUv,
                        fillPremul,
                        effectiveColorMatrix,
                        node.getCornerRadius(),
                        node.getBorderThickness(),
                        borderPremul,
                        0,
                        4.0f,
                        0,
                        0.0f,
                        node.isSilhouetteEnabled() ? 1 : 0,
                        sil
                );
                if (debugWireframeEnabled) {
                    drawRectOutlineWireframe(drawX, drawY, drawW, drawH);
                }
            } finally {
                if (node.getBlendMode() != SceneNode.BlendMode.NORMAL) {
                    GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
                }
            }
        }

        Rect2f prevBounds = currentRenderBounds;
        float prevAlpha = currentEffectiveAlpha;
        ColorMatrix4f prevColorMatrix = currentEffectiveColorMatrix;
        float prevRotate = currentRotationDegrees;
        float prevAnchorX = currentAnchorX;
        float prevAnchorY = currentAnchorY;

        currentRenderBounds = new Rect2f(drawX, drawY, drawW, drawH);
        currentEffectiveAlpha = effectiveAlpha;
        currentEffectiveColorMatrix = effectiveColorMatrix;
        currentRotationDegrees = transform.getRotate();
        currentAnchorX = transform.getXanchor();
        currentAnchorY = transform.getYanchor();
        try {
            node.onCustomRender(this);
        } finally {
            currentRenderBounds = prevBounds;
            currentEffectiveAlpha = prevAlpha;
            currentEffectiveColorMatrix = prevColorMatrix;
            currentRotationDegrees = prevRotate;
            currentAnchorX = prevAnchorX;
            currentAnchorY = prevAnchorY;
        }

        List<SceneNode> children = node.getChildren();
        if (!children.isEmpty()) {
            List<SceneNode> sortedChildren = new ArrayList<>(children);
            sortedChildren.sort(CHILD_Z_COMPARATOR);
            for (SceneNode child : sortedChildren) {
                renderNodeRecursive(
                        child,
                        drawX,
                        drawY,
                        drawW,
                        drawH,
                        effectiveAlpha,
                        effectiveColorMatrix,
                        0.0f,
                        0.0f,
                        0.0f,
                        0.0f,
                        normalizedMouseX,
                        normalizedMouseY
                );
            }
        }
    }

    @Override
    public void endFrame() {
        requireInitialized();
        scissorStack.clear();
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        offscreenFbo.unbind();

        if (currentWindowWidth <= 0 || currentWindowHeight <= 0) {
            lastLetterboxViewport = new Rect2f(0.0f, 0.0f, 0.0f, 0.0f);
            GL30.glBindVertexArray(0);
            GL20.glUseProgram(0);
            return;
        }

        Framebuffer sourceFbo = applyPostProcessChain(offscreenFbo);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glViewport(0, 0, currentWindowWidth, currentWindowHeight);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glClearColor(clearColorRgba[0], clearColorRgba[1], clearColorRgba[2], clearColorRgba[3]);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        lastLetterboxViewport = computeLetterboxViewport(
                currentWindowWidth,
                currentWindowHeight,
                logicalWidth,
                logicalHeight
        );

        windowProjection.setOrtho(0.0f, currentWindowWidth, currentWindowHeight, 0.0f, -1.0f, 1.0f);
        modelMatrix.identity()
                .translate(lastLetterboxViewport.x(), lastLetterboxViewport.y(), 0.0f)
                .scale(lastLetterboxViewport.width(), lastLetterboxViewport.height(), 1.0f);

        ColorMatrix4f accMat = colorBlindnessFilter != null
                ? colorBlindnessFilter.buildMatrix()
                : ColorMatrix4f.identity();

        blitShader.bind();
        blitShader.setUniformMat4("uProjection", windowProjection);
        blitShader.setUniformMat4("uModel", modelMatrix);
        blitShader.setUniformInt("uTexture", 0);
        blitShader.setUniformMat4("uAccessibilityMatrix", accMat.toFloatArray());
        blitShader.setUniformFloat("uChromaticAberration", extendedPostProcessConfig.chromaticAberration());
        blitShader.setUniformFloat("uVignetteIntensity", extendedPostProcessConfig.vignetteIntensity());
        blitShader.setUniformVec2(
                "uVignetteFocus",
                extendedPostProcessConfig.vignetteFocusX(),
                extendedPostProcessConfig.vignetteFocusY()
        );
        blitShader.setUniformInt("uHalftoneEnabled", extendedPostProcessConfig.halftoneEnabled() ? 1 : 0);
        blitShader.setUniformFloat("uHalftoneDotSize", extendedPostProcessConfig.halftoneDotSizePx());
        blitShader.setUniformFloat("uDistortionStrength", extendedPostProcessConfig.distortionStrength());
        blitShader.setUniformFloat("uDistortionTime", (float) postEffectTimeSeconds);
        blitShader.setUniformFloat("uDistortionTopY", extendedPostProcessConfig.distortionTopY());
        blitShader.setUniformInt("uDitherEnabled", extendedPostProcessConfig.ditheringEnabled() ? 1 : 0);
        blitShader.setUniformInt("uSrgbOutput", extendedPostProcessConfig.srgbOutputEnabled() ? 1 : 0);
        blitShader.setUniformInt("uHdrToneMap", extendedPostProcessConfig.hdrToneMapEnabled() ? 1 : 0);
        blitShader.setUniformVec2("uCanvasSize", logicalWidth, logicalHeight);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sourceFbo.getTextureId());

        GL30.glBindVertexArray(quadVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);

        GL30.glBindVertexArray(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        blitShader.unbind();
        GL11.glEnable(GL11.GL_BLEND);
    }

    /**
     * Draws a solid axis-aligned rectangle in logical canvas coordinates using straight RGBA channels.
     *
     * @param x      left X coordinate
     * @param y      top Y coordinate
     * @param width  rectangle width
     * @param height rectangle height
     * @param r      red channel in {@code [0, 1]}
     * @param g      green channel in {@code [0, 1]}
     * @param b      blue channel in {@code [0, 1]}
     * @param a      alpha channel in {@code [0, 1]}
     */
    public void drawRect(float x, float y, float width, float height, float r, float g, float b, float a) {
        drawRoundedRect(x, y, width, height, 0.0f, r, g, b, a);
    }

    /**
     * Draws a solid axis-aligned rectangle in logical canvas coordinates using a 4-float straight RGBA array.
     *
     * @param x      left X coordinate
     * @param y      top Y coordinate
     * @param width  rectangle width
     * @param height rectangle height
     * @param rgba   4-element straight RGBA array
     */
    public void drawRect(float x, float y, float width, float height, float[] rgba) {
        Objects.requireNonNull(rgba, "rgba must not be null");
        if (rgba.length != 4) {
            throw new IllegalArgumentException("Expected 4 RGBA components, got " + rgba.length);
        }
        drawRoundedRect(x, y, width, height, 0.0f, rgba[0], rgba[1], rgba[2], rgba[3]);
    }

    /**
     * Draws a rounded rectangle with straight RGBA fill color and no border.
     *
     * @param x            left X coordinate
     * @param y            top Y coordinate
     * @param width        rectangle width
     * @param height       rectangle height
     * @param cornerRadius corner radius in logical pixels
     * @param r            red channel in {@code [0, 1]}
     * @param g            green channel in {@code [0, 1]}
     * @param b            blue channel in {@code [0, 1]}
     * @param a            alpha channel in {@code [0, 1]}
     */
    public void drawRoundedRect(
            float x,
            float y,
            float width,
            float height,
            float cornerRadius,
            float r,
            float g,
            float b,
            float a
    ) {
        requireInitialized();
        if (width <= 0.0f || height <= 0.0f || a <= 0.0f) {
            return;
        }
        float clampedA = Math.clamp(a, 0.0f, 1.0f);
        float[] fillPremul = new float[]{r * clampedA, g * clampedA, b * clampedA, clampedA};
        drawQuadInternal(
                0,
                x,
                y,
                width,
                height,
                0.0f,
                0.0f,
                0.0f,
                FULL_UV_RECT,
                fillPremul,
                ColorMatrix4f.identity(),
                Math.max(0.0f, cornerRadius),
                0.0f,
                NO_BORDER_COLOR
        );
    }

    /**
     * Draws a rounded rectangle with optional border using straight RGBA fill and border colors.
     *
     * @param x               left X coordinate
     * @param y               top Y coordinate
     * @param width           rectangle width
     * @param height          rectangle height
     * @param cornerRadius    corner radius in logical pixels
     * @param fillRgba        4-element straight RGBA fill color
     * @param borderThickness border width in logical pixels ({@code 0} for no border)
     * @param borderRgba      4-element straight RGBA border color (may be {@code null} when thickness is 0)
     */
    public void drawRoundedRect(
            float x,
            float y,
            float width,
            float height,
            float cornerRadius,
            float[] fillRgba,
            float borderThickness,
            float[] borderRgba
    ) {
        requireInitialized();
        Objects.requireNonNull(fillRgba, "fillRgba must not be null");
        if (fillRgba.length != 4) {
            throw new IllegalArgumentException("Expected 4 RGBA components for fillRgba");
        }
        if (width <= 0.0f || height <= 0.0f) {
            return;
        }

        float fillA = Math.clamp(fillRgba[3], 0.0f, 1.0f);
        float[] fillPremul = new float[]{
            fillRgba[0] * fillA,
            fillRgba[1] * fillA,
            fillRgba[2] * fillA,
            fillA
        };

        float[] borderPremul = NO_BORDER_COLOR;
        float effectiveBorder = Math.max(0.0f, borderThickness);
        if (effectiveBorder > 0.0f && borderRgba != null) {
            if (borderRgba.length != 4) {
                throw new IllegalArgumentException("Expected 4 RGBA components for borderRgba");
            }
            float borderA = Math.clamp(borderRgba[3], 0.0f, 1.0f);
            borderPremul = new float[]{
                borderRgba[0] * borderA,
                borderRgba[1] * borderA,
                borderRgba[2] * borderA,
                borderA
            };
        }

        drawQuadInternal(
                0,
                x,
                y,
                width,
                height,
                0.0f,
                0.0f,
                0.0f,
                FULL_UV_RECT,
                fillPremul,
                ColorMatrix4f.identity(),
                Math.max(0.0f, cornerRadius),
                effectiveBorder,
                borderPremul
        );
    }

    /**
     * Draws a full-UV textured quad at full opacity.
     *
     * @param textureId OpenGL texture ID
     * @param x         left X coordinate
     * @param y         top Y coordinate
     * @param width     quad width
     * @param height    quad height
     */
    public void drawTexturedQuad(int textureId, float x, float y, float width, float height) {
        drawTexturedQuad(textureId, x, y, width, height, FULL_UV_RECT, 1.0f);
    }

    /**
     * Draws a textured quad with a custom UV sub-rectangle and scalar opacity.
     *
     * @param textureId OpenGL texture ID
     * @param x         left X coordinate
     * @param y         top Y coordinate
     * @param width     quad width
     * @param height    quad height
     * @param uvRect    UV sub-rectangle (defaults to full {@code (0,0,1,1)} if {@code null})
     * @param alpha     opacity in {@code [0, 1]}
     */
    public void drawTexturedQuad(
            int textureId,
            float x,
            float y,
            float width,
            float height,
            Rect2f uvRect,
            float alpha
    ) {
        float clampedA = Math.clamp(alpha, 0.0f, 1.0f);
        drawTexturedQuad(
                textureId,
                x,
                y,
                width,
                height,
                uvRect,
                new float[]{1.0f, 1.0f, 1.0f, clampedA},
                ColorMatrix4f.identity()
        );
    }

    /**
     * Draws a textured quad with a custom UV sub-rectangle, straight RGBA tint, and 4x4 color matrix.
     *
     * @param textureId   OpenGL texture ID
     * @param x           left X coordinate
     * @param y           top Y coordinate
     * @param width       quad width
     * @param height      quad height
     * @param uvRect      UV sub-rectangle (defaults to {@code (0,0,1,1)} if {@code null})
     * @param tintRgba    4-element straight RGBA tint color
     * @param colorMatrix 4x4 color matrix (defaults to identity if {@code null})
     */
    public void drawTexturedQuad(
            int textureId,
            float x,
            float y,
            float width,
            float height,
            Rect2f uvRect,
            float[] tintRgba,
            ColorMatrix4f colorMatrix
    ) {
        drawTexturedQuad(
                textureId,
                x,
                y,
                width,
                height,
                uvRect,
                tintRgba,
                colorMatrix,
                0.0f,
                0.0f,
                0.0f
        );
    }

    /**
     * Draws a textured quad rotated around a normalized local origin {@code (originX, originY)}.
     *
     * @param textureId     OpenGL texture ID
     * @param x             left X coordinate in logical pixels
     * @param y             top Y coordinate in logical pixels
     * @param width         quad width in logical pixels
     * @param height        quad height in logical pixels
     * @param uvRect        UV sub-rectangle (defaults to {@code (0,0,1,1)} if {@code null})
     * @param tintRgba      4-element straight RGBA tint color (defaults to opaque white if {@code null})
     * @param colorMatrix   4x4 color matrix (defaults to identity if {@code null})
     * @param rotateDegrees clockwise rotation angle in degrees
     * @param originX       normalized horizontal rotation origin ({@code 0}=left, {@code 0.5}=center, {@code 1}=right)
     * @param originY       normalized vertical rotation origin ({@code 0}=top, {@code 0.5}=center, {@code 1}=bottom)
     */
    public void drawTexturedQuad(
            int textureId,
            float x,
            float y,
            float width,
            float height,
            Rect2f uvRect,
            float[] tintRgba,
            ColorMatrix4f colorMatrix,
            float rotateDegrees,
            float originX,
            float originY
    ) {
        drawTexturedQuadPivot(
                textureId,
                x,
                y,
                width,
                height,
                uvRect,
                tintRgba,
                colorMatrix,
                rotateDegrees,
                x + originX * width,
                y + originY * height
        );
    }

    /**
     * Draws a textured quad rotated around an explicit pivot point {@code (pivotX, pivotY)} in
     * logical canvas coordinates.
     *
     * @param textureId     OpenGL texture ID
     * @param x             left X coordinate in logical pixels
     * @param y             top Y coordinate in logical pixels
     * @param width         quad width in logical pixels
     * @param height        quad height in logical pixels
     * @param uvRect        UV sub-rectangle (defaults to {@code (0,0,1,1)} if {@code null})
     * @param tintRgba      4-element straight RGBA tint color (defaults to opaque white if {@code null})
     * @param colorMatrix   4x4 color matrix (defaults to identity if {@code null})
     * @param rotateDegrees clockwise rotation angle in degrees
     * @param pivotX        pivot X coordinate in logical pixels
     * @param pivotY        pivot Y coordinate in logical pixels
     */
    public void drawTexturedQuadPivot(
            int textureId,
            float x,
            float y,
            float width,
            float height,
            Rect2f uvRect,
            float[] tintRgba,
            ColorMatrix4f colorMatrix,
            float rotateDegrees,
            float pivotX,
            float pivotY
    ) {
        requireInitialized();
        if (textureId == 0 || width <= 0.0f || height <= 0.0f) {
            return;
        }
        float[] tint = tintRgba != null ? tintRgba : new float[]{1.0f, 1.0f, 1.0f, 1.0f};
        if (tint.length != 4) {
            throw new IllegalArgumentException("Expected 4 RGBA components for tintRgba");
        }
        float a = Math.clamp(tint[3], 0.0f, 1.0f);
        if (a <= 0.0f) {
            return;
        }
        float[] premulTint = new float[]{tint[0] * a, tint[1] * a, tint[2] * a, a};
        drawQuadWithPivotInternal(
                textureId,
                x,
                y,
                width,
                height,
                pivotX,
                pivotY,
                rotateDegrees,
                uvRect != null ? uvRect : FULL_UV_RECT,
                premulTint,
                colorMatrix != null ? colorMatrix : ColorMatrix4f.identity(),
                0.0f,
                0.0f,
                NO_BORDER_COLOR,
                0,
                4.0f,
                0,
                0.0f
        );
    }

    /**
     * Draws an SDF or multi-channel MSDF text glyph quad using {@link RenderPipeline2D}'s smooth
     * signed-distance-field anti-aliasing fragment path.
     *
     * @param textureId    OpenGL atlas texture ID
     * @param x            left X coordinate in logical pixels
     * @param y            top Y coordinate in logical pixels
     * @param width        quad width in logical pixels
     * @param height       quad height in logical pixels
     * @param uvRect       UV sub-rectangle in the glyph atlas
     * @param tintRgba     4-element straight RGBA text tint color
     * @param pxRange      distance field screen-pixel range ({@code > 0})
     * @param multiChannel {@code true} for 3-channel MSDF median decoding, {@code false} for single-channel SDF
     */
    public void drawMsdfTextQuad(
            int textureId,
            float x,
            float y,
            float width,
            float height,
            Rect2f uvRect,
            float[] tintRgba,
            float pxRange,
            boolean multiChannel
    ) {
        requireInitialized();
        if (textureId == 0 || width <= 0.0f || height <= 0.0f) {
            return;
        }
        float[] tint = tintRgba != null ? tintRgba : new float[]{1.0f, 1.0f, 1.0f, 1.0f};
        if (tint.length != 4) {
            throw new IllegalArgumentException("Expected 4 RGBA components for tintRgba");
        }
        float a = Math.clamp(tint[3], 0.0f, 1.0f);
        if (a <= 0.0f) {
            return;
        }
        float[] premulTint = new float[]{tint[0] * a, tint[1] * a, tint[2] * a, a};
        float validRange = (Float.isFinite(pxRange) && pxRange > 0.0f) ? pxRange : 4.0f;
        drawQuadWithPivotInternal(
                textureId,
                x,
                y,
                width,
                height,
                x,
                y,
                0.0f,
                uvRect != null ? uvRect : FULL_UV_RECT,
                premulTint,
                ColorMatrix4f.identity(),
                0.0f,
                0.0f,
                NO_BORDER_COLOR,
                multiChannel ? 2 : 1,
                validRange,
                0,
                0.0f
        );
    }

    private void drawQuadInternal(
            int textureId,
            float x,
            float y,
            float width,
            float height,
            float xanchor,
            float yanchor,
            float rotateDeg,
            Rect2f uvRect,
            float[] fillPremul,
            ColorMatrix4f colorMatrix,
            float cornerRadius,
            float borderThickness,
            float[] borderPremul
    ) {
        drawQuadWithPivotInternal(
                textureId,
                x,
                y,
                width,
                height,
                x + xanchor * width,
                y + yanchor * height,
                rotateDeg,
                uvRect,
                fillPremul,
                colorMatrix,
                cornerRadius,
                borderThickness,
                borderPremul,
                0,
                4.0f,
                0,
                0.0f
        );
    }

    private void drawQuadWithPivotInternal(
            int textureId,
            float x,
            float y,
            float width,
            float height,
            float pivotX,
            float pivotY,
            float rotateDeg,
            Rect2f uvRect,
            float[] fillPremul,
            ColorMatrix4f colorMatrix,
            float cornerRadius,
            float borderThickness,
            float[] borderPremul,
            int sdfTextMode,
            float sdfPxRange,
            int shadowMode,
            float shadowRadius
    ) {
        drawQuadWithPivotInternal(
                textureId,
                x,
                y,
                width,
                height,
                pivotX,
                pivotY,
                rotateDeg,
                uvRect,
                fillPremul,
                colorMatrix,
                cornerRadius,
                borderThickness,
                borderPremul,
                sdfTextMode,
                sdfPxRange,
                shadowMode,
                shadowRadius,
                0,
                NO_BORDER_COLOR,
                0,
                null
        );
    }

    private void drawQuadWithPivotInternal(
            int textureId,
            float x,
            float y,
            float width,
            float height,
            float pivotX,
            float pivotY,
            float rotateDeg,
            Rect2f uvRect,
            float[] fillPremul,
            ColorMatrix4f colorMatrix,
            float cornerRadius,
            float borderThickness,
            float[] borderPremul,
            int sdfTextMode,
            float sdfPxRange,
            int shadowMode,
            float shadowRadius,
            int silhouetteMode,
            float[] silhouetteRgba
    ) {
        drawQuadWithPivotInternal(
                textureId,
                x,
                y,
                width,
                height,
                pivotX,
                pivotY,
                rotateDeg,
                uvRect,
                fillPremul,
                colorMatrix,
                cornerRadius,
                borderThickness,
                borderPremul,
                sdfTextMode,
                sdfPxRange,
                shadowMode,
                shadowRadius,
                silhouetteMode,
                silhouetteRgba,
                0,
                null
        );
    }

    private void drawQuadWithPivotInternal(
            int textureId,
            float x,
            float y,
            float width,
            float height,
            float pivotX,
            float pivotY,
            float rotateDeg,
            Rect2f uvRect,
            float[] fillPremul,
            ColorMatrix4f colorMatrix,
            float cornerRadius,
            float borderThickness,
            float[] borderPremul,
            int sdfTextMode,
            float sdfPxRange,
            int shadowMode,
            float shadowRadius,
            int silhouetteMode,
            float[] silhouetteRgba,
            int gradientMode,
            float[][] cornerGradientColors
    ) {
        if (rotateDeg != 0.0f) {
            modelMatrix.identity()
                    .translate(pivotX, pivotY, 0.0f)
                    .rotateZ((float) Math.toRadians(rotateDeg))
                    .translate(x - pivotX, y - pivotY, 0.0f)
                    .scale(width, height, 1.0f);
        } else {
            modelMatrix.identity()
                    .translate(x, y, 0.0f)
                    .scale(width, height, 1.0f);
        }

        quadShader.bind();
        quadShader.setUniformMat4("uProjection", logicalProjection);
        quadShader.setUniformMat4("uModel", modelMatrix);
        quadShader.setUniformMat4("uColorMatrix", colorMatrix.toFloatArray());
        quadShader.setUniformVec4("uColor", fillPremul[0], fillPremul[1], fillPremul[2], fillPremul[3]);
        quadShader.setUniformVec4("uBorderColor", borderPremul[0], borderPremul[1], borderPremul[2], borderPremul[3]);
        quadShader.setUniformVec4("uUvRect", uvRect.x(), uvRect.y(), uvRect.width(), uvRect.height());
        quadShader.setUniformVec2("uRectSize", width, height);
        quadShader.setUniformFloat("uCornerRadius", cornerRadius);
        quadShader.setUniformFloat("uBorderThickness", borderThickness);
        quadShader.setUniformInt("uSdfTextMode", sdfTextMode);
        quadShader.setUniformFloat("uSdfPxRange", sdfPxRange);
        quadShader.setUniformInt("uShadowMode", shadowMode);
        quadShader.setUniformFloat("uShadowRadius", shadowRadius);
        quadShader.setUniformInt("uSilhouetteMode", silhouetteMode);
        float[] sil = silhouetteRgba != null ? silhouetteRgba : NO_BORDER_COLOR;
        quadShader.setUniformVec4("uSilhouetteColor", sil[0], sil[1], sil[2], sil[3]);
        quadShader.setUniformInt("uGradientMode", gradientMode);
        if (gradientMode != 0 && cornerGradientColors != null && cornerGradientColors.length == 4) {
            float[] c0 = cornerGradientColors[0];
            float[] c1 = cornerGradientColors[1];
            float[] c2 = cornerGradientColors[2];
            float[] c3 = cornerGradientColors[3];
            quadShader.setUniformVec4("uGradientTopLeft", c0[0], c0[1], c0[2], c0[3]);
            quadShader.setUniformVec4("uGradientTopRight", c1[0], c1[1], c1[2], c1[3]);
            quadShader.setUniformVec4("uGradientBottomLeft", c2[0], c2[1], c2[2], c2[3]);
            quadShader.setUniformVec4("uGradientBottomRight", c3[0], c3[1], c3[2], c3[3]);
        } else {
            quadShader.setUniformVec4("uGradientTopLeft", 0.0f, 0.0f, 0.0f, 0.0f);
            quadShader.setUniformVec4("uGradientTopRight", 0.0f, 0.0f, 0.0f, 0.0f);
            quadShader.setUniformVec4("uGradientBottomLeft", 0.0f, 0.0f, 0.0f, 0.0f);
            quadShader.setUniformVec4("uGradientBottomRight", 0.0f, 0.0f, 0.0f, 0.0f);
        }

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        if (textureId != 0) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            quadShader.setUniformInt("uUseTexture", 1);
            quadShader.setUniformInt("uTexture", 0);
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            quadShader.setUniformInt("uUseTexture", 0);
            quadShader.setUniformInt("uTexture", 0);
        }

        GL30.glBindVertexArray(quadVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
    }

    private static void applyNodeBlendMode(SceneNode.BlendMode mode) {
        switch (mode) {
            case NORMAL -> GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            case ADDITIVE -> GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
            case MULTIPLY -> GL11.glBlendFunc(GL11.GL_DST_COLOR, GL11.GL_ONE_MINUS_SRC_ALPHA);
            case SCREEN -> GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_COLOR);
        }
    }

    private void drawActiveLetterboxBars() {
        Rect2f top = letterboxController.getTopBarRect(logicalWidth);
        Rect2f bottom = letterboxController.getBottomBarRect(logicalWidth, logicalHeight);
        if (top.height() > 0.0f) {
            drawRect(top.x(), top.y(), top.width(), top.height(), 0.0f, 0.0f, 0.0f, 1.0f);
        }
        if (bottom.height() > 0.0f) {
            drawRect(bottom.x(), bottom.y(), bottom.width(), bottom.height(), 0.0f, 0.0f, 0.0f, 1.0f);
        }
    }

    private void drawRectOutlineWireframe(float x, float y, float w, float h) {
        drawRoundedRect(x, y, w, h, 0.0f, new float[]{0.0f, 0.0f, 0.0f, 0.0f}, 1.5f,
                new float[]{0.2f, 0.9f, 1.0f, 0.95f});
    }

    private static Rect2f resolveEffectiveUv(SceneNode node, Transform2D transform) {
        Rect2f uv = node.getUvRect();
        float scrollU = node.getUvScrollOffsetU();
        float scrollV = node.getUvScrollOffsetV();
        if (scrollU != 0.0f || scrollV != 0.0f) {
            uv = new Rect2f(uv.x() + scrollU, uv.y() + scrollV, uv.width(), uv.height());
        }
        Rect2f crop = transform.getCrop();
        if (crop != null && node.getWidth() > 0.0f && node.getHeight() > 0.0f) {
            float relX = crop.x() / node.getWidth();
            float relY = crop.y() / node.getHeight();
            float relW = crop.width() / node.getWidth();
            float relH = crop.height() / node.getHeight();
            uv = new Rect2f(
                    uv.x() + relX * uv.width(),
                    uv.y() + relY * uv.height(),
                    uv.width() * relW,
                    uv.height() * relH
            );
        }
        if (transform.getXzoom() < 0.0f) {
            uv = new Rect2f(uv.x() + uv.width(), uv.y(), -uv.width(), uv.height());
        }
        if (transform.getYzoom() < 0.0f) {
            uv = new Rect2f(uv.x(), uv.y() + uv.height(), uv.width(), -uv.height());
        }
        return uv;
    }

    private static ColorMatrix4f combineColorMatrices(ColorMatrix4f parent, ColorMatrix4f local) {
        if (parent.equals(ColorMatrix4f.identity())) {
            return local;
        }
        if (local.equals(ColorMatrix4f.identity())) {
            return parent;
        }
        return parent.multiply(local);
    }

    @Override
    public RenderMode getMode() {
        return RenderMode.MODE_2D;
    }

    @Override
    public void dispose() {
        boolean hadGl = initialized || quadShader != null;
        if (quadShader != null) {
            quadShader.dispose();
            quadShader = null;
        }
        if (blitShader != null) {
            blitShader.dispose();
            blitShader = null;
        }
        if (gaussianBlurShader != null) {
            gaussianBlurShader.dispose();
            gaussianBlurShader = null;
        }
        if (bloomThresholdShader != null) {
            bloomThresholdShader.dispose();
            bloomThresholdShader = null;
        }
        if (bloomCompositeShader != null) {
            bloomCompositeShader.dispose();
            bloomCompositeShader = null;
        }
        if (quadVao != 0) {
            GL30.glDeleteVertexArrays(quadVao);
            quadVao = 0;
        }
        if (quadVbo != 0) {
            GL15.glDeleteBuffers(quadVbo);
            quadVbo = 0;
        }
        if (postProcessPingFbo != null) {
            postProcessPingFbo.dispose();
            postProcessPingFbo = null;
        }
        if (postProcessPongFbo != null) {
            postProcessPongFbo.dispose();
            postProcessPongFbo = null;
        }
        if (offscreenFbo != null) {
            offscreenFbo.dispose();
            offscreenFbo = null;
        }
        if (hadGl && resourceAuditor != null) {
            for (int i = 0; i < 3; i++) {
                resourceAuditor.recordDispose(GlResourceAuditor.ResourceKind.FBO);
            }
            for (int i = 0; i < 5; i++) {
                resourceAuditor.recordDispose(GlResourceAuditor.ResourceKind.SHADER);
            }
            resourceAuditor.recordDispose(GlResourceAuditor.ResourceKind.VAO);
            resourceAuditor.recordDispose(GlResourceAuditor.ResourceKind.VBO);
        }
        scissorStack.clear();
        frameNodeBlurRadius = 0.0f;
        currentRenderBounds = null;
        currentEffectiveAlpha = 1.0f;
        currentEffectiveColorMatrix = ColorMatrix4f.identity();
        currentRotationDegrees = 0.0f;
        currentAnchorX = 0.0f;
        currentAnchorY = 0.0f;
        initialized = false;
    }

    /**
     * Draws a dynamic 4-corner gradient quad directly without uploading an external gradient texture.
     *
     * @param x           left X
     * @param y           top Y
     * @param width       width in logical pixels
     * @param height      height in logical pixels
     * @param topLeft     top-left straight RGBA color
     * @param topRight    top-right straight RGBA color
     * @param bottomLeft  bottom-left straight RGBA color
     * @param bottomRight bottom-right straight RGBA color
     */
    public void drawGradientQuad(
            float x,
            float y,
            float width,
            float height,
            float[] topLeft,
            float[] topRight,
            float[] bottomLeft,
            float[] bottomRight
    ) {
        requireInitialized();
        if (width <= 0.0f || height <= 0.0f) {
            return;
        }
        drawQuadWithPivotInternal(
                0,
                x,
                y,
                width,
                height,
                x,
                y,
                0.0f,
                FULL_UV_RECT,
                new float[]{1.0f, 1.0f, 1.0f, 1.0f},
                ColorMatrix4f.identity(),
                0.0f,
                0.0f,
                NO_BORDER_COLOR,
                0,
                4.0f,
                0,
                0.0f,
                0,
                NO_BORDER_COLOR,
                1,
                new float[][]{topLeft, topRight, bottomLeft, bottomRight}
        );
    }

    /**
     * Connects a {@link CinematicLetterboxController} rendered cleanly above foreground effects and below modals.
     *
     * @param controller letterbox controller (nullable)
     */
    public void setLetterboxController(CinematicLetterboxController controller) {
        this.letterboxController = controller;
    }

    /**
     * Returns attached {@link CinematicLetterboxController}, if any.
     *
     * @return attached controller or {@code null}
     */
    public CinematicLetterboxController getLetterboxController() {
        return letterboxController;
    }

    /**
     * Attaches a live {@link ColorBlindnessFilter} applied directly in the final composite shader pass.
     *
     * @param filter accessibility filter (nullable)
     */
    public void setColorBlindnessFilter(ColorBlindnessFilter filter) {
        this.colorBlindnessFilter = filter;
    }

    /**
     * Returns attached {@link ColorBlindnessFilter}, if any.
     *
     * @return attached filter or {@code null}
     */
    public ColorBlindnessFilter getColorBlindnessFilter() {
        return colorBlindnessFilter;
    }

    /**
     * Configures extended post-processing effects (chromatic aberration, vignette, halftone, distortion, sRGB, HDR).
     *
     * @param config extended configuration (null resets to {@link ExtendedPostProcessConfig#NONE})
     */
    public void setExtendedPostProcessConfig(ExtendedPostProcessConfig config) {
        this.extendedPostProcessConfig = config != null ? config : ExtendedPostProcessConfig.NONE;
    }

    /**
     * Returns current extended post-processing configuration.
     *
     * @return current {@link ExtendedPostProcessConfig}
     */
    public ExtendedPostProcessConfig getExtendedPostProcessConfig() {
        return extendedPostProcessConfig;
    }

    /**
     * Attaches a {@link GlResourceAuditor} to verify 0 leaked GPU resources upon {@link #dispose()}.
     *
     * @param auditor resource auditor (nullable)
     */
    public void setResourceAuditor(GlResourceAuditor auditor) {
        this.resourceAuditor = auditor;
    }

    /**
     * Returns the configured scene clear color outside 16:9 letterboxes.
     *
     * @return 4-element RGBA copy
     */
    public float[] getClearColorRgba() {
        return clearColorRgba.clone();
    }

    /**
     * Configures custom scene clear color outside the 16:9 canvas (preventing jarring black flashes).
     *
     * @param r red {@code [0, 1]}
     * @param g green {@code [0, 1]}
     * @param b blue {@code [0, 1]}
     * @param a alpha {@code [0, 1]}
     */
    public void setClearColor(float r, float g, float b, float a) {
        clearColorRgba[0] = Math.clamp(r, 0.0f, 1.0f);
        clearColorRgba[1] = Math.clamp(g, 0.0f, 1.0f);
        clearColorRgba[2] = Math.clamp(b, 0.0f, 1.0f);
        clearColorRgba[3] = Math.clamp(a, 0.0f, 1.0f);
    }

    /**
     * Sets maximum mouse/camera parallax offset clamp in logical pixels.
     *
     * @param maxParallaxOffsetPx clamp magnitude in pixels ({@code >= 0})
     */
    public void setMaxParallaxOffsetPx(float maxParallaxOffsetPx) {
        this.maxParallaxOffsetPx = Float.isFinite(maxParallaxOffsetPx) ? Math.max(0.0f, maxParallaxOffsetPx) : 180.0f;
    }

    /**
     * Enables or disables node bounding-box debug wireframe rendering in the live viewport.
     *
     * @param enabled {@code true} to draw outlines around scene nodes
     */
    public void setDebugWireframeEnabled(boolean enabled) {
        this.debugWireframeEnabled = enabled;
    }

    /**
     * Returns whether debug wireframe outlines are enabled.
     *
     * @return {@code true} if debug wireframe mode is enabled
     */
    public boolean isDebugWireframeEnabled() {
        return debugWireframeEnabled;
    }

    /**
     * Controls whether Studio-only Broadcast/SteamDeck Safe-Area guides are overlaid.
     *
     * @param enabled {@code true} to enable guides
     */
    public void setSafeAreaGuidesEnabled(boolean enabled) {
        this.safeAreaGuidesEnabled = enabled;
    }

    /**
     * Returns whether Safe-Area guides are enabled.
     *
     * @return {@code true} when active
     */
    public boolean isSafeAreaGuidesEnabled() {
        return safeAreaGuidesEnabled;
    }

    /**
     * Enables or disables HDR floating-point FBO preference for bloom pipelines.
     *
     * @param hdrFboPreferred {@code true} to prefer {@code GL_RGBA16F}
     */
    public void setHdrFboPreferred(boolean hdrFboPreferred) {
        this.hdrFboPreferred = hdrFboPreferred;
    }

    /**
     * Returns whether HDR FBO storage is preferred.
     *
     * @return {@code true} when HDR FBO is preferred
     */
    public boolean isHdrFboPreferred() {
        return hdrFboPreferred;
    }

    /**
     * Advances distortion/time-based shader uniform timer.
     *
     * @param dt frame delta in seconds
     */
    public void advancePostEffectTime(double dt) {
        if (Double.isFinite(dt) && dt > 0.0) {
            postEffectTimeSeconds += dt;
        }
    }

    /**
     * Populates preallocated scratch quad commands for up to {@code quadCount} quads without allocating
     * per-quad instances on repeat frames and sorts by Z/texture ID.
     *
     * @param quadCount number of quads in this frame
     * @return prepared batch count
     */
    public int prepareZeroAllocationBatch(int quadCount) {
        int n = Math.max(0, quadCount);
        while (scratchBatchQueue.size() < n) {
            scratchBatchQueue.add(new ScratchQuadCommand());
        }
        for (int i = 0; i < n; i++) {
            ScratchQuadCommand cmd = scratchBatchQueue.get(i);
            cmd.zOrder = i % 8;
            cmd.textureId = (i % 16) + 1;
            cmd.x = (i % 100) * 16.0f;
            cmd.y = (i / 100.0f) * 16.0f;
            cmd.width = 16.0f;
            cmd.height = 16.0f;
        }
        this.scratchBatchCount = n;
        scratchBatchQueue.subList(0, n).sort(Comparator.comparingInt((ScratchQuadCommand c) -> c.zOrder)
                .thenComparingInt(c -> c.textureId));
        return scratchBatchCount;
    }

    /**
     * Captures a compact stage-only thumbnail (excluding UI screens {@code Z >= Z_SCREENS}) at {@code thumbW x thumbH},
     * using live FBO readback + bilinear resize when OpenGL is active or headless CPU rasterization otherwise.
     *
     * @param graph  scene graph to snapshot
     * @param thumbW thumbnail width (e.g. {@code 320})
     * @param thumbH thumbnail height (e.g. {@code 180})
     * @return straight RGBA8 bytes ({@code thumbW * thumbH * 4})
     */
    public byte[] captureSceneThumbnailRgba(SceneGraph graph, int thumbW, int thumbH) {
        Objects.requireNonNull(graph, "graph must not be null");
        SceneGraph stageOnly = new SceneGraph();
        for (SceneLayer layer : graph.getLayersSorted()) {
            if (layer.getZOrder() >= SceneLayer.Z_SCREENS) {
                continue;
            }
            SceneLayer target = stageOnly.getOrCreateLayer(layer.getName(), layer.getZOrder());
            target.setParallaxFactor(layer.getParallaxFactor());
            for (SceneNode node : layer.getSortedNodes()) {
                target.addNode(node);
            }
        }
        if (initialized && offscreenFbo != null && offscreenFbo.isInitialized()) {
            beginFrame(thumbW, thumbH);
            renderSceneGraph(stageOnly);
            offscreenFbo.unbind();
            byte[] full = offscreenFbo.readPixelsRgba();
            return ScreenshotCaptureService.resizeBilinearRgba(
                    full, offscreenFbo.getWidth(), offscreenFbo.getHeight(), thumbW, thumbH
            );
        }
        return GraphicsRuntimeServices.SoftwareRasterizer.rasterize(stageOnly, thumbW, thumbH);
    }

    /**
     * Validates all built-in OpenGL 3.3 Core GLSL shader sources upfront so boot diagnostics can catch
     * cross-driver syntax issues without first-effect compile stutter.
     *
     * @return list of validation results across core shaders
     */
    public static List<CustomShaderRegistry.ReloadResult> precompileValidateCoreShaders() {
        Map<String, String> core = new LinkedHashMap<>();
        core.put("quad_fragment", QUAD_FRAGMENT_SHADER);
        core.put("gaussian_blur", GAUSSIAN_BLUR_FRAGMENT_SHADER);
        core.put("bloom_threshold", BLOOM_THRESHOLD_FRAGMENT_SHADER);
        core.put("bloom_composite", BLOOM_COMPOSITE_FRAGMENT_SHADER);
        core.put("blit_composite", BLIT_FRAGMENT_SHADER);
        return CustomShaderRegistry.precompileValidateManifest(core);
    }

    /**
     * Returns a defensive copy of the logical orthographic projection matrix.
     *
     * @return defensive copy of the logical orthographic projection matrix
     */
    public Matrix4f getLogicalProjection() {
        return new Matrix4f(logicalProjection);
    }

    /**
     * Returns the resolved logical bounds of the {@link SceneNode} currently executing
     * {@link SceneNode#onCustomRender(RenderPipeline2D)}, or {@code null} outside scene-graph traversal.
     *
     * @return active node render bounds, or {@code null}
     */
    public Rect2f getCurrentRenderBounds() {
        return currentRenderBounds;
    }

    /**
     * Returns the effective inherited alpha of the {@link SceneNode} currently executing
     * {@link SceneNode#onCustomRender(RenderPipeline2D)}, or {@code 1.0f} outside traversal.
     *
     * @return effective alpha in {@code [0, 1]}
     */
    public float getCurrentEffectiveAlpha() {
        return currentEffectiveAlpha;
    }

    /**
     * Returns the effective combined {@link ColorMatrix4f} of the {@link SceneNode} currently executing
     * {@link SceneNode#onCustomRender(RenderPipeline2D)}, or identity outside traversal.
     *
     * @return effective color matrix
     */
    public ColorMatrix4f getCurrentEffectiveColorMatrix() {
        return currentEffectiveColorMatrix;
    }

    /**
     * Returns the rotation angle in degrees of the {@link SceneNode} currently executing
     * {@link SceneNode#onCustomRender(RenderPipeline2D)}, or {@code 0.0f} outside traversal.
     *
     * @return rotation angle in degrees
     */
    public float getCurrentRotationDegrees() {
        return currentRotationDegrees;
    }

    /**
     * Returns the normalized X anchor of the {@link SceneNode} currently executing
     * {@link SceneNode#onCustomRender(RenderPipeline2D)}, or {@code 0.0f} outside traversal.
     *
     * @return normalized horizontal anchor
     */
    public float getCurrentAnchorX() {
        return currentAnchorX;
    }

    /**
     * Returns the normalized Y anchor of the {@link SceneNode} currently executing
     * {@link SceneNode#onCustomRender(RenderPipeline2D)}, or {@code 0.0f} outside traversal.
     *
     * @return normalized vertical anchor
     */
    public float getCurrentAnchorY() {
        return currentAnchorY;
    }

    /**
     * Returns the most recently computed letterbox or pillarbox viewport rectangle.
     *
     * @return most recently computed letterbox/pillarbox destination rectangle from {@link #endFrame()}
     */
    public Rect2f getLastLetterboxViewport() {
        return lastLetterboxViewport;
    }

    /**
     * Returns the offscreen logical-resolution framebuffer.
     *
     * @return offscreen logical-resolution {@link Framebuffer}, or {@code null} if uninitialized
     */
    public Framebuffer getOffscreenFramebuffer() {
        return offscreenFbo;
    }

    /**
     * Returns the configured logical canvas width in pixels.
     *
     * @return configured logical canvas width in pixels
     */
    public int getLogicalWidth() {
        return logicalWidth;
    }

    /**
     * Returns the configured logical canvas height in pixels.
     *
     * @return configured logical canvas height in pixels
     */
    public int getLogicalHeight() {
        return logicalHeight;
    }

    /**
     * Returns whether GPU resources have been initialized.
     *
     * @return {@code true} if GPU resources have been initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Pushes a clip rectangle onto the hierarchical scissor stack, intersecting it with any active
     * parent scissor bounds and logical canvas bounds.
     *
     * <p>Safe to call headlessly to compute and verify clipped regions; when OpenGL resources are
     * initialized, updates {@code GL_SCISSOR_TEST} and {@code glScissor} immediately.</p>
     *
     * @param clipRect clip rectangle in top-down logical canvas coordinates (must not be {@code null})
     * @return effective intersected clip rectangle pushed onto the stack
     */
    public Rect2f pushScissor(Rect2f clipRect) {
        Objects.requireNonNull(clipRect, "clipRect must not be null");
        Rect2f canvasBounds = new Rect2f(0.0f, 0.0f, logicalWidth, logicalHeight);
        Rect2f sanitized = new Rect2f(
                clipRect.x(),
                clipRect.y(),
                Math.max(0.0f, clipRect.width()),
                Math.max(0.0f, clipRect.height())
        );
        Rect2f effective = intersectScissorRects(canvasBounds, sanitized);
        if (!scissorStack.isEmpty()) {
            effective = intersectScissorRects(scissorStack.peek(), effective);
        }
        scissorStack.push(effective);
        if (initialized) {
            applyGlScissor(effective);
        }
        return effective;
    }

    /**
     * Pops the active scissor rectangle and restores the enclosing parent clip rectangle (or disables
     * {@code GL_SCISSOR_TEST} when the stack becomes empty).
     *
     * @return the popped {@link Rect2f} clip rectangle
     * @throws IllegalStateException if the scissor stack is currently empty
     */
    public Rect2f popScissor() {
        if (scissorStack.isEmpty()) {
            throw new IllegalStateException("Cannot popScissor() on an empty scissor stack");
        }
        Rect2f popped = scissorStack.pop();
        if (initialized) {
            if (scissorStack.isEmpty()) {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            } else {
                applyGlScissor(scissorStack.peek());
            }
        }
        return popped;
    }

    /**
     * Returns the currently active top scissor rectangle, if any.
     *
     * @return top intersected scissor rectangle, or empty when scissor clipping is inactive
     */
    public Optional<Rect2f> getActiveScissor() {
        return Optional.ofNullable(scissorStack.peek());
    }

    /**
     * Returns the current nesting depth of the scissor stack.
     *
     * @return scissor stack depth ({@code >= 0})
     */
    public int getScissorDepth() {
        return scissorStack.size();
    }

    /**
     * Computes the axis-aligned intersection of two scissor rectangles, collapsing disjoint regions
     * cleanly to a zero-area rectangle.
     *
     * @param a first rectangle (non-null)
     * @param b second rectangle (non-null)
     * @return intersected rectangle with non-negative dimensions
     */
    public static Rect2f intersectScissorRects(Rect2f a, Rect2f b) {
        Objects.requireNonNull(a, "a must not be null");
        Objects.requireNonNull(b, "b must not be null");
        float x1 = Math.max(a.x(), b.x());
        float y1 = Math.max(a.y(), b.y());
        float x2 = Math.min(a.right(), b.right());
        float y2 = Math.min(a.bottom(), b.bottom());
        float w = Math.max(0.0f, x2 - x1);
        float h = Math.max(0.0f, y2 - y1);
        return new Rect2f(x1, y1, w, h);
    }

    /**
     * Converts a top-down logical scissor rectangle into OpenGL bottom-left integer
     * {@code [x, y, width, height]} arguments for {@code glScissor}.
     *
     * @param rect         top-down scissor rectangle
     * @param targetHeight target framebuffer height in pixels
     * @return 4-element integer array {@code [x, y, width, height]}
     */
    public static int[] toGlScissorBox(Rect2f rect, int targetHeight) {
        Objects.requireNonNull(rect, "rect must not be null");
        int x = Math.max(0, Math.round(rect.x()));
        int w = Math.max(0, Math.round(rect.width()));
        int h = Math.max(0, Math.round(rect.height()));
        int y = Math.max(0, targetHeight - Math.round(rect.y() + h));
        return new int[]{x, y, w, h};
    }

    /**
     * Computes smooth soft-shadow SDF attenuation in {@code [0, 1]} for a rounded-box signed distance.
     *
     * @param signedDistance signed distance in logical pixels (negative inside the box)
     * @param radius         softness blur radius in pixels
     * @return normalized shadow opacity weight in {@code [0, 1]}
     */
    public static float computeSoftShadowAlpha(float signedDistance, float radius) {
        float safeRadius = (Float.isFinite(radius) && radius > 0.0f) ? radius : 1.0f;
        float edge0 = -safeRadius * 0.35f;
        float edge1 = safeRadius;
        float t = Math.clamp((signedDistance - edge0) / Math.max(1.0e-5f, edge1 - edge0), 0.0f, 1.0f);
        float smooth = t * t * (3.0f - 2.0f * t);
        return 1.0f - smooth;
    }

    /**
     * Computes normalized symmetric 1D Gaussian filter weights summing to {@code 1.0f} for
     * separable horizontal/vertical blur passes.
     *
     * @param sigma  standard deviation ({@code > 0})
     * @param radius one-sided tap radius ({@code >= 0})
     * @return array of {@code 2 * radius + 1} normalized weights
     */
    public static float[] computeGaussianWeights(float sigma, int radius) {
        int r = Math.max(0, radius);
        float s = (Float.isFinite(sigma) && sigma > 0.0f) ? sigma : 1.0f;
        float[] weights = new float[2 * r + 1];
        double twoSigmaSq = 2.0 * s * s;
        double sum = 0.0;
        for (int i = -r; i <= r; i++) {
            double w = Math.exp(-(i * i) / twoSigmaSq);
            weights[i + r] = (float) w;
            sum += w;
        }
        for (int i = 0; i < weights.length; i++) {
            weights[i] = (float) (weights[i] / sum);
        }
        return weights;
    }

    /**
     * Evaluates extracted Rec.709 bloom contribution weight above {@code threshold}.
     *
     * @param r         red component in {@code [0, 1]}
     * @param g         green component in {@code [0, 1]}
     * @param b         blue component in {@code [0, 1]}
     * @param threshold luminance extraction threshold in {@code [0, 1]}
     * @return extracted bloom brightness factor in {@code [0, 1]}
     */
    public static float computeBloomBrightness(float r, float g, float b, float threshold) {
        float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        float clampedThresh = Math.clamp(threshold, 0.0f, 1.0f);
        float t = Math.clamp((lum - clampedThresh) / 0.15f, 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    /**
     * Configures the offscreen FBO ping-pong post-processing passes.
     *
     * @param config post-processing settings ({@code null} resets to {@link PostProcessConfig#NONE})
     */
    public void setPostProcessConfig(PostProcessConfig config) {
        this.postProcessConfig = config != null ? config : PostProcessConfig.NONE;
    }

    /**
     * Returns the current offscreen FBO post-processing configuration.
     *
     * @return current {@link PostProcessConfig}
     */
    public PostProcessConfig getPostProcessConfig() {
        return postProcessConfig;
    }

    /**
     * Sets the global scene two-pass Gaussian blur radius in pixels ({@code 0} disables blur).
     *
     * @param blurRadius blur radius in pixels
     */
    public void setSceneBlurRadius(float blurRadius) {
        this.postProcessConfig = new PostProcessConfig(
                blurRadius,
                postProcessConfig.bloomThreshold(),
                postProcessConfig.bloomIntensity()
        );
    }

    /**
     * Returns the configured global scene Gaussian blur radius in pixels.
     *
     * @return global Gaussian blur radius in pixels
     */
    public float getSceneBlurRadius() {
        return postProcessConfig.gaussianBlurRadius();
    }

    /**
     * Configures the bloom/glow extraction threshold and additive intensity.
     *
     * @param threshold luminance threshold in {@code [0, 1]}
     * @param intensity additive bloom intensity ({@code 0} disables bloom)
     */
    public void setBloomConfig(float threshold, float intensity) {
        this.postProcessConfig = new PostProcessConfig(
                postProcessConfig.gaussianBlurRadius(),
                threshold,
                intensity
        );
    }

    /**
     * Returns whether bloom post-processing is currently enabled.
     *
     * @return {@code true} if bloom intensity &gt; 0
     */
    public boolean isBloomEnabled() {
        return postProcessConfig.bloomIntensity() > 0.0f;
    }

    /**
     * Returns the maximum per-node {@link Transform2D#getBlur()} observed during the most recent frame.
     *
     * @return maximum active node blur radius observed in pixels
     */
    public float getLastFrameNodeBlurRadius() {
        return frameNodeBlurRadius;
    }

    private void applyGlScissor(Rect2f clipRect) {
        int[] box = toGlScissorBox(clipRect, logicalHeight);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(box[0], box[1], box[2], box[3]);
    }

    private Framebuffer applyPostProcessChain(Framebuffer sceneSource) {
        float effectiveBlur = Math.max(postProcessConfig.gaussianBlurRadius(), frameNodeBlurRadius);
        boolean doBlur = effectiveBlur > 0.0f && gaussianBlurShader != null
                && postProcessPingFbo != null && postProcessPongFbo != null;
        boolean doBloom = postProcessConfig.bloomIntensity() > 0.0f
                && bloomThresholdShader != null && bloomCompositeShader != null
                && gaussianBlurShader != null && postProcessPingFbo != null && postProcessPongFbo != null;

        if (!doBlur && !doBloom) {
            return sceneSource;
        }

        GL11.glDisable(GL11.GL_BLEND);
        Framebuffer active = sceneSource;

        if (doBlur) {
            active = runGaussianBlurPasses(active, effectiveBlur);
        }

        if (doBloom) {
            postProcessPongFbo.bind();
            GL11.glViewport(0, 0, logicalWidth, logicalHeight);
            GL11.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            bloomThresholdShader.bind();
            bloomThresholdShader.setUniformMat4("uProjection", postProcessProjection);
            bloomThresholdShader.setUniformMat4("uModel", modelMatrix.identity());
            bloomThresholdShader.setUniformInt("uTexture", 0);
            bloomThresholdShader.setUniformFloat("uThreshold", postProcessConfig.bloomThreshold());
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, active.getTextureId());
            GL30.glBindVertexArray(quadVao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
            bloomThresholdShader.unbind();
            postProcessPongFbo.unbind();

            Framebuffer blurredBloom = runGaussianBlurIntoPong(postProcessPongFbo, Math.max(4.0f, effectiveBlur));

            Framebuffer compositeTarget = (active == postProcessPingFbo) ? sceneSource : postProcessPingFbo;
            compositeTarget.bind();
            GL11.glViewport(0, 0, logicalWidth, logicalHeight);
            bloomCompositeShader.bind();
            bloomCompositeShader.setUniformMat4("uProjection", postProcessProjection);
            bloomCompositeShader.setUniformMat4("uModel", modelMatrix.identity());
            bloomCompositeShader.setUniformInt("uSceneTexture", 0);
            bloomCompositeShader.setUniformInt("uBloomTexture", 1);
            bloomCompositeShader.setUniformFloat("uBloomIntensity", postProcessConfig.bloomIntensity());
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, active.getTextureId());
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, blurredBloom.getTextureId());
            GL30.glBindVertexArray(quadVao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            bloomCompositeShader.unbind();
            compositeTarget.unbind();
            active = compositeTarget;
        }

        GL11.glEnable(GL11.GL_BLEND);
        return active;
    }

    private Framebuffer runGaussianBlurPasses(Framebuffer inputFbo, float radius) {
        drawGaussianPass(inputFbo, postProcessPingFbo, 1.0f / logicalWidth, 0.0f, radius);
        drawGaussianPass(postProcessPingFbo, postProcessPongFbo, 0.0f, 1.0f / logicalHeight, radius);
        return postProcessPongFbo;
    }

    private Framebuffer runGaussianBlurIntoPong(Framebuffer pongInput, float radius) {
        drawGaussianPass(pongInput, postProcessPingFbo, 1.0f / logicalWidth, 0.0f, radius);
        drawGaussianPass(postProcessPingFbo, postProcessPongFbo, 0.0f, 1.0f / logicalHeight, radius);
        return postProcessPongFbo;
    }

    private void drawGaussianPass(
            Framebuffer srcFbo,
            Framebuffer dstFbo,
            float stepX,
            float stepY,
            float radius
    ) {
        dstFbo.bind();
        GL11.glViewport(0, 0, logicalWidth, logicalHeight);
        gaussianBlurShader.bind();
        gaussianBlurShader.setUniformMat4("uProjection", postProcessProjection);
        gaussianBlurShader.setUniformMat4("uModel", modelMatrix.identity());
        gaussianBlurShader.setUniformInt("uTexture", 0);
        gaussianBlurShader.setUniformVec2("uTexelStep", stepX, stepY);
        gaussianBlurShader.setUniformFloat("uBlurRadius", radius);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, srcFbo.getTextureId());
        GL30.glBindVertexArray(quadVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        gaussianBlurShader.unbind();
        dstFbo.unbind();
    }

    private void requireInitialized() {
        if (!initialized) {
            throw new IllegalStateException("RenderPipeline2D has not been initialized or was disposed");
        }
    }
}
