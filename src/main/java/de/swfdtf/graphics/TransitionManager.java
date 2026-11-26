package de.swfdtf.graphics;

import de.swfdtf.math.Easing;
import de.swfdtf.math.Rect2f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.List;
import java.util.Objects;

/**
 * Runtime state machine and OpenGL 3.3 Core Dual-FBO shader compositor for {@link Transition} effects.
 *
 * <p>Can be used purely headlessly to track active transition timelines, global progress in {@code [0, 1]},
 * and sequential sub-step indices/progress for {@link Transition.Kind#MULTIPLE}. When an OpenGL 3.3 Core
 * context is active, {@link #initGl(int, int)}, {@link #captureFromFbo(Framebuffer)},
 * {@link #renderComposite(Framebuffer, TextureManager, int, int)}, and {@link #disposeGl()} perform
 * hardware-accelerated compositing between the captured "from" framebuffer and the live "to" framebuffer.</p>
 */
public final class TransitionManager {

    private static final int MODE_NONE = 0;
    private static final int MODE_DISSOLVE = 1;
    private static final int MODE_FADE = 2;
    private static final int MODE_PIXELLATE = 3;
    private static final int MODE_WIPE_LEFT = 4;
    private static final int MODE_WIPE_RIGHT = 5;
    private static final int MODE_WIPE_UP = 6;
    private static final int MODE_WIPE_DOWN = 7;
    private static final int MODE_SLIDE_LEFT = 8;
    private static final int MODE_SLIDE_RIGHT = 9;
    private static final int MODE_PUSH_LEFT = 10;
    private static final int MODE_PUSH_RIGHT = 11;
    private static final int MODE_IRIS_IN = 12;
    private static final int MODE_IRIS_OUT = 13;
    private static final int MODE_BLINDS = 14;
    private static final int MODE_SQUARES = 15;
    private static final int MODE_IMAGE_DISSOLVE = 16;
    private static final int MODE_FLASH_WHITE = 17;
    private static final int MODE_PUSH_UP = 18;
    private static final int MODE_SHATTER = 19;
    private static final int MODE_DISSOLVE_CURVE = 20;

    private static final String COMPOSITE_VERTEX_SHADER = """
            #version 330 core
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aUv;

            uniform mat4 uProjection;
            uniform mat4 uModel;

            out vec2 vScreenUv;

            void main() {
                gl_Position = uProjection * uModel * vec4(aPos, 0.0, 1.0);
                vScreenUv = aUv;
            }
            """;

    private static final String COMPOSITE_FRAGMENT_SHADER = """
            #version 330 core
            in vec2 vScreenUv;

            uniform sampler2D uFromTex;
            uniform sampler2D uToTex;
            uniform sampler2D uMaskTex;
            uniform int uMode;
            uniform float uProgress;
            uniform vec4 uFadeColor;
            uniform vec2 uResolution;
            uniform float uRampLength;
            uniform int uReverseMask;
            uniform int uSliceCount;
            uniform int uHasMask;
            uniform vec2 uIrisCenter;
            uniform float uCurveBias;

            out vec4 fragColor;

            vec4 sampleFbo(sampler2D tex, vec2 screenUv) {
                vec2 clamped = clamp(screenUv, vec2(0.0), vec2(1.0));
                return texture(tex, vec2(clamped.x, 1.0 - clamped.y));
            }

            void main() {
                vec2 uv = clamp(vScreenUv, vec2(0.0), vec2(1.0));
                float p = clamp(uProgress, 0.0, 1.0);

                if (uMode == 0) {
                    fragColor = sampleFbo(uToTex, uv);
                    return;
                }

                if (uMode == 1) { // DISSOLVE / MOVE / EASE
                    fragColor = mix(sampleFbo(uFromTex, uv), sampleFbo(uToTex, uv), p);
                    return;
                }

                if (uMode == 2) { // FADE
                    vec4 fadePremul = vec4(uFadeColor.rgb * uFadeColor.a, uFadeColor.a);
                    if (p < 0.5) {
                        fragColor = mix(sampleFbo(uFromTex, uv), fadePremul, p * 2.0);
                    } else {
                        fragColor = mix(fadePremul, sampleFbo(uToTex, uv), (p - 0.5) * 2.0);
                    }
                    return;
                }

                if (uMode == 3) { // PIXELLATE
                    float tri = 1.0 - abs(2.0 * p - 1.0);
                    float blockPx = mix(1.0, 64.0, tri * tri);
                    vec2 res = max(uResolution, vec2(1.0));
                    vec2 grid = max(floor(res / blockPx), vec2(1.0));
                    vec2 qUv = (floor(uv * grid) + vec2(0.5)) / grid;
                    float blend = smoothstep(0.45, 0.55, p);
                    fragColor = mix(sampleFbo(uFromTex, qUv), sampleFbo(uToTex, qUv), blend);
                    return;
                }

                if (uMode == 4) { // WIPE_LEFT (right to left)
                    float edge = 1.0 - p;
                    float mask = smoothstep(edge - 0.01, edge + 0.01, uv.x);
                    fragColor = mix(sampleFbo(uFromTex, uv), sampleFbo(uToTex, uv), mask);
                    return;
                }

                if (uMode == 5) { // WIPE_RIGHT (left to right)
                    float mask = 1.0 - smoothstep(p - 0.01, p + 0.01, uv.x);
                    fragColor = mix(sampleFbo(uFromTex, uv), sampleFbo(uToTex, uv), mask);
                    return;
                }

                if (uMode == 6) { // WIPE_UP (bottom to top)
                    float edge = 1.0 - p;
                    float mask = smoothstep(edge - 0.01, edge + 0.01, uv.y);
                    fragColor = mix(sampleFbo(uFromTex, uv), sampleFbo(uToTex, uv), mask);
                    return;
                }

                if (uMode == 7) { // WIPE_DOWN (top to bottom)
                    float mask = 1.0 - smoothstep(p - 0.01, p + 0.01, uv.y);
                    fragColor = mix(sampleFbo(uFromTex, uv), sampleFbo(uToTex, uv), mask);
                    return;
                }

                if (uMode == 8) { // SLIDE_LEFT
                    float split = 1.0 - p;
                    if (uv.x >= split) {
                        fragColor = sampleFbo(uToTex, vec2(uv.x - split, uv.y));
                    } else {
                        fragColor = sampleFbo(uFromTex, uv);
                    }
                    return;
                }

                if (uMode == 9) { // SLIDE_RIGHT
                    float split = p;
                    if (uv.x <= split) {
                        fragColor = sampleFbo(uToTex, vec2(uv.x + (1.0 - p), uv.y));
                    } else {
                        fragColor = sampleFbo(uFromTex, uv);
                    }
                    return;
                }

                if (uMode == 10) { // PUSH_LEFT
                    float split = 1.0 - p;
                    if (uv.x >= split) {
                        fragColor = sampleFbo(uToTex, vec2(uv.x - split, uv.y));
                    } else {
                        fragColor = sampleFbo(uFromTex, vec2(uv.x + p, uv.y));
                    }
                    return;
                }

                if (uMode == 11) { // PUSH_RIGHT
                    float split = p;
                    if (uv.x <= split) {
                        fragColor = sampleFbo(uToTex, vec2(uv.x + (1.0 - p), uv.y));
                    } else {
                        fragColor = sampleFbo(uFromTex, vec2(uv.x - p, uv.y));
                    }
                    return;
                }

                if (uMode == 12 || uMode == 13) { // IRIS_IN / IRIS_OUT
                    vec2 res = max(uResolution, vec2(1.0));
                    vec2 aspect = vec2(res.x / res.y, 1.0);
                    vec2 center = clamp(uIrisCenter, vec2(0.0), vec2(1.0));
                    float maxDist = max(
                        max(length((vec2(0.0, 0.0) - center) * aspect), length((vec2(1.0, 0.0) - center) * aspect)),
                        max(length((vec2(0.0, 1.0) - center) * aspect), length((vec2(1.0, 1.0) - center) * aspect))
                    );
                    float dist = length((uv - center) * aspect);
                    if (uMode == 12) { // IRIS_IN: shrinking 'from' circle revealing 'to'
                        float radius = (1.0 - p) * maxDist;
                        float mask = smoothstep(radius - 0.01, radius + 0.01, dist);
                        fragColor = mix(sampleFbo(uFromTex, uv), sampleFbo(uToTex, uv), mask);
                    } else { // IRIS_OUT: expanding 'to' circle over 'from'
                        float radius = p * maxDist;
                        float mask = 1.0 - smoothstep(radius - 0.01, radius + 0.01, dist);
                        fragColor = mix(sampleFbo(uFromTex, uv), sampleFbo(uToTex, uv), mask);
                    }
                    return;
                }

                if (uMode == 14) { // BLINDS
                    float slices = float(max(uSliceCount, 1));
                    float cell = fract(uv.x * slices);
                    float mask = 1.0 - smoothstep(p - 0.02, p + 0.02, cell);
                    fragColor = mix(sampleFbo(uFromTex, uv), sampleFbo(uToTex, uv), mask);
                    return;
                }

                if (uMode == 15) { // SQUARES
                    vec2 res = max(uResolution, vec2(1.0));
                    float cols = float(max(uSliceCount, 1));
                    float rows = max(1.0, floor(cols * (res.y / res.x) + 0.5));
                    vec2 cellUv = fract(uv * vec2(cols, rows));
                    float chebyshev = max(abs(cellUv.x - 0.5), abs(cellUv.y - 0.5)) * 2.0;
                    float mask = 1.0 - smoothstep(p - 0.02, p + 0.02, chebyshev);
                    fragColor = mix(sampleFbo(uFromTex, uv), sampleFbo(uToTex, uv), mask);
                    return;
                }

                if (uMode == 16) { // IMAGE_DISSOLVE
                    float lum;
                    if (uHasMask != 0) {
                        vec4 m = texture(uMaskTex, uv);
                        vec3 straightRgb = m.a > 0.0001 ? (m.rgb / m.a) : vec3(0.0);
                        lum = dot(straightRgb, vec3(0.2126, 0.7152, 0.0722));
                    } else {
                        lum = uv.x;
                    }
                    if (uReverseMask != 0) {
                        lum = 1.0 - lum;
                    }
                    float ramp = clamp(uRampLength / 255.0, 0.001, 1.0);
                    float threshold = p * (1.0 + ramp);
                    float mask = clamp((threshold - lum) / ramp, 0.0, 1.0);
                    fragColor = mix(sampleFbo(uFromTex, uv), sampleFbo(uToTex, uv), mask);
                    return;
                }

                if (uMode == 17) { // FLASH_WHITE (peak white at t=0.15)
                    vec4 white = vec4(1.0, 1.0, 1.0, 1.0);
                    if (p <= 0.15) {
                        fragColor = mix(sampleFbo(uFromTex, uv), white, p / 0.15);
                    } else {
                        float decay = (p - 0.15) / 0.85;
                        float flashAmt = max(0.0, 1.0 - decay * 1.4);
                        vec4 toC = sampleFbo(uToTex, uv);
                        fragColor = mix(toC, white, flashAmt);
                    }
                    return;
                }

                if (uMode == 18) { // PUSH_UP
                    float split = 1.0 - p;
                    if (uv.y >= split) {
                        fragColor = sampleFbo(uToTex, vec2(uv.x, uv.y - split));
                    } else {
                        fragColor = sampleFbo(uFromTex, vec2(uv.x, uv.y + p));
                    }
                    return;
                }

                if (uMode == 19) { // SHATTER (broken mirror shard tiles)
                    float cols = float(max(uSliceCount, 2));
                    vec2 grid = vec2(cols, max(2.0, floor(cols * 0.5625)));
                    vec2 cell = floor(uv * grid);
                    vec2 local = fract(uv * grid) - 0.5;
                    float seed = fract(sin(dot(cell, vec2(12.9898, 78.233))) * 43758.5453);
                    float angle = p * (seed >= 0.5 ? 1.0 : -1.0) * (1.2 + seed * 1.4);
                    float c = cos(angle);
                    float s = sin(angle);
                    vec2 rotLocal = mat2(c, -s, s, c) * local;
                    vec2 dropUv = clamp(
                        uv - vec2(0.0, p * p * (0.4 + seed * 0.4)) + (rotLocal - local) / grid,
                        0.0, 1.0
                    );
                    float shardAlpha = clamp(1.0 - p, 0.0, 1.0);
                    vec4 fromShard = sampleFbo(uFromTex, dropUv) * shardAlpha;
                    vec4 toScene = sampleFbo(uToTex, uv);
                    fragColor = mix(toScene, fromShard + toScene * (1.0 - fromShard.a), shardAlpha);
                    return;
                }

                if (uMode == 20) { // DISSOLVE_CURVE
                    float s = p * p * (3.0 - 2.0 * p);
                    float boost = uCurveBias * 4.0 * s * (1.0 - s);
                    vec4 f = sampleFbo(uFromTex, uv);
                    vec4 t = sampleFbo(uToTex, uv);
                    fragColor = clamp(f * (1.0 - s + boost * 0.5) + t * (s + boost * 0.5), 0.0, 1.0);
                    return;
                }

                fragColor = mix(sampleFbo(uFromTex, uv), sampleFbo(uToTex, uv), p);
            }
            """;

    private Transition activeTransition;
    private boolean active = false;
    private double elapsedSeconds = 0.0;
    private float progress = 0.0f;
    private int activeStepIndex = 0;
    private float activeStepProgress = 0.0f;

    /**
     * Creates a new idle {@code TransitionManager} instance.
     */
    public TransitionManager() {
    }

    // OpenGL resources (lazily initialized via initGl)
    private boolean glInitialized = false;
    private Framebuffer fromFbo;
    private ShaderProgram compositeShader;
    private int quadVao = 0;
    private int quadVbo = 0;
    private final Matrix4f windowProjection = new Matrix4f();
    private final Matrix4f modelMatrix = new Matrix4f();

    /**
     * Starts a new transition timeline. If {@code transition} is {@code null}, {@link Transition.Kind#NONE},
     * or has non-positive total duration, the transition completes immediately ({@code isActive() == false},
     * {@code getProgress() == 1.0f}).
     *
     * @param transition transition specification (may be {@code null})
     */
    public void startTransition(Transition transition) {
        if (transition == null
                || transition.getKind() == Transition.Kind.NONE
                || transition.getDurationSeconds() <= 0.0f) {
            this.activeTransition = null;
            this.active = false;
            this.elapsedSeconds = 0.0;
            this.progress = 1.0f;
            this.activeStepIndex = 0;
            this.activeStepProgress = 1.0f;
            return;
        }

        if (transition.getKind() == Transition.Kind.MULTIPLE && transition.getSubTransitions().isEmpty()) {
            this.activeTransition = null;
            this.active = false;
            this.elapsedSeconds = 0.0;
            this.progress = 1.0f;
            this.activeStepIndex = 0;
            this.activeStepProgress = 1.0f;
            return;
        }

        this.activeTransition = transition;
        this.active = true;
        this.elapsedSeconds = 0.0;
        recomputeProgressState();
    }

    /**
     * Advances the active transition timeline by {@code dt} seconds.
     *
     * @param dt elapsed frame delta in seconds (negative values are clamped to {@code 0})
     */
    public void update(double dt) {
        if (!active || activeTransition == null) {
            return;
        }
        double safeDt = Double.isFinite(dt) && dt > 0.0 ? dt : 0.0;
        elapsedSeconds += safeDt;

        float totalDuration = activeTransition.getDurationSeconds();
        double tol = floatBoundaryEpsilon(totalDuration);
        if (totalDuration <= 0.0f || elapsedSeconds + tol >= totalDuration) {
            List<Transition> subs = activeTransition.getSubTransitions();
            this.active = false;
            this.activeTransition = null;
            this.elapsedSeconds = totalDuration;
            this.progress = 1.0f;
            this.activeStepIndex = subs.isEmpty() ? 0 : subs.size() - 1;
            this.activeStepProgress = 1.0f;
            return;
        }

        recomputeProgressState();
    }

    private static double floatBoundaryEpsilon(float duration) {
        return Math.max(1.0e-7, Math.abs(duration) * 1.0e-5);
    }

    private void recomputeProgressState() {
        float totalDuration = activeTransition.getDurationSeconds();
        this.progress = totalDuration > 0.0f
                ? (float) Math.clamp(elapsedSeconds / totalDuration, 0.0, 1.0)
                : 1.0f;

        if (activeTransition.getKind() != Transition.Kind.MULTIPLE) {
            this.activeStepIndex = 0;
            this.activeStepProgress = this.progress;
            return;
        }

        List<Transition> subs = activeTransition.getSubTransitions();
        double cursorTime = elapsedSeconds;
        for (int i = 0; i < subs.size(); i++) {
            Transition sub = subs.get(i);
            float subDur = sub.getDurationSeconds();
            if (subDur <= 0.0f) {
                if (i == subs.size() - 1) {
                    this.activeStepIndex = i;
                    this.activeStepProgress = 1.0f;
                    return;
                }
                continue;
            }
            double subTol = floatBoundaryEpsilon(subDur);
            if (cursorTime + subTol < subDur || i == subs.size() - 1) {
                this.activeStepIndex = i;
                this.activeStepProgress = (float) Math.clamp(cursorTime / subDur, 0.0, 1.0);
                return;
            }
            cursorTime = Math.max(0.0, cursorTime - subDur);
        }
        this.activeStepIndex = Math.max(0, subs.size() - 1);
        this.activeStepProgress = 1.0f;
    }

    /**
     * Cancels any active transition immediately, resetting progress to {@code 0}.
     */
    public void cancel() {
        this.activeTransition = null;
        this.active = false;
        this.elapsedSeconds = 0.0;
        this.progress = 0.0f;
        this.activeStepIndex = 0;
        this.activeStepProgress = 0.0f;
    }

    /**
     * Returns whether a transition is currently in progress.
     *
     * @return {@code true} if a transition is currently in progress
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Returns the overall normalized transition progress in {@code [0, 1]}.
     *
     * @return overall normalized transition progress in {@code [0, 1]}
     */
    public float getProgress() {
        return progress;
    }

    /**
     * Returns the currently active {@link Transition}, if any.
     *
     * @return currently active {@link Transition}, or {@code null} when idle/finished
     */
    public Transition getActiveTransition() {
        return active ? activeTransition : null;
    }

    /**
     * Returns the zero-based sub-transition index currently executing for {@link Transition.Kind#MULTIPLE},
     * or {@code 0} for single transitions.
     *
     * @return active sub-step index
     */
    public int getActiveStepIndex() {
        return activeStepIndex;
    }

    /**
     * Returns the normalized progress in {@code [0, 1]} of the currently executing sub-transition
     * (equal to {@link #getProgress()} for non-{@code MULTIPLE} transitions).
     *
     * @return active sub-step progress in {@code [0, 1]}
     */
    public float getActiveStepProgress() {
        return activeStepProgress;
    }

    /**
     * Resolves the single leaf {@link Transition} currently driving visual compositing
     * (unwrapping {@link Transition.Kind#MULTIPLE}).
     *
     * @return current effective {@link Transition}, or {@code null} if no transition is active
     */
    public Transition getCurrentEffectiveTransition() {
        if (!active || activeTransition == null) {
            return null;
        }
        if (activeTransition.getKind() == Transition.Kind.MULTIPLE) {
            List<Transition> subs = activeTransition.getSubTransitions();
            if (subs.isEmpty()) {
                return null;
            }
            int idx = Math.clamp(activeStepIndex, 0, subs.size() - 1);
            Transition sub = subs.get(idx);
            while (sub.getKind() == Transition.Kind.MULTIPLE && !sub.getSubTransitions().isEmpty()) {
                sub = sub.getSubTransitions().get(0);
            }
            return sub;
        }
        return activeTransition;
    }

    // ---- OpenGL 3.3 Core Dual-FBO Compositor ----

    /**
     * Initializes the offscreen "from" {@link Framebuffer}, GLSL 330 core composite shader, and
     * fullscreen quad geometry on the current OpenGL context.
     *
     * @param logicalW logical canvas width in pixels ({@code > 0})
     * @param logicalH logical canvas height in pixels ({@code > 0})
     */
    public void initGl(int logicalW, int logicalH) {
        if (logicalW <= 0 || logicalH <= 0) {
            throw new IllegalArgumentException(
                    "Logical dimensions must be positive, got " + logicalW + "x" + logicalH
            );
        }
        if (glInitialized) {
            disposeGl();
        }
        try {
            this.fromFbo = new Framebuffer(logicalW, logicalH);
            this.fromFbo.init();
            this.compositeShader = new ShaderProgram(COMPOSITE_VERTEX_SHADER, COMPOSITE_FRAGMENT_SHADER);
            initQuadGeometry();
            this.glInitialized = true;
        } catch (RuntimeException ex) {
            disposeGl();
            throw ex;
        }
    }

    private void initQuadGeometry() {
        float[] vertices = {
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
            FloatBuffer buf = stack.mallocFloat(vertices.length);
            buf.put(vertices).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
        }
        int stride = 4 * Float.BYTES;
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, stride, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 2L * Float.BYTES);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    /**
     * Copies the rendered contents of {@code src} into the internal "from" FBO before switching scenes.
     *
     * @param src initialized source {@link Framebuffer} containing the outgoing scene
     */
    public void captureFromFbo(Framebuffer src) {
        requireGlInitialized();
        Objects.requireNonNull(src, "src must not be null");
        if (!src.isInitialized()) {
            throw new IllegalStateException("Source Framebuffer is not initialized");
        }
        if (fromFbo.getWidth() != src.getWidth() || fromFbo.getHeight() != src.getHeight()) {
            fromFbo.resize(src.getWidth(), src.getHeight());
        }

        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, src.getFboId());
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fromFbo.getFboId());
            GL30.glBlitFramebuffer(
                    0, 0, src.getWidth(), src.getHeight(),
                    0, 0, fromFbo.getWidth(), fromFbo.getHeight(),
                    GL11.GL_COLOR_BUFFER_BIT,
                    GL11.GL_LINEAR
            );
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
        }
    }

    /**
     * Composites the captured "from" FBO with {@code toFbo} onto the default window framebuffer
     * using aspect-preserving letterboxing over {@code targetWidth x targetHeight}.
     *
     * @param toFbo        initialized destination scene {@link Framebuffer}
     * @param texMgr       optional {@link TextureManager} used to load {@link Transition.Kind#IMAGE_DISSOLVE} masks
     * @param targetWidth  window framebuffer width in pixels
     * @param targetHeight window framebuffer height in pixels
     */
    public void renderComposite(Framebuffer toFbo, TextureManager texMgr, int targetWidth, int targetHeight) {
        requireGlInitialized();
        Objects.requireNonNull(toFbo, "toFbo must not be null");
        if (!toFbo.isInitialized()) {
            throw new IllegalStateException("Target toFbo is not initialized");
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        if (targetWidth <= 0 || targetHeight <= 0) {
            return;
        }

        GL11.glViewport(0, 0, targetWidth, targetHeight);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glClearColor(
                RenderPipeline2D.CLEAR_R,
                RenderPipeline2D.CLEAR_G,
                RenderPipeline2D.CLEAR_B,
                RenderPipeline2D.CLEAR_A
        );
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        Rect2f viewport = RenderPipeline2D.computeLetterboxViewport(
                targetWidth,
                targetHeight,
                fromFbo.getWidth(),
                fromFbo.getHeight()
        );
        windowProjection.setOrtho(0.0f, targetWidth, targetHeight, 0.0f, -1.0f, 1.0f);
        modelMatrix.identity()
                .translate(viewport.x(), viewport.y(), 0.0f)
                .scale(viewport.width(), viewport.height(), 1.0f);

        Transition effective = getCurrentEffectiveTransition();
        int shaderMode = MODE_NONE;
        float shaderProgress = 1.0f;
        float[] fadeRgba = new float[]{0.0f, 0.0f, 0.0f, 1.0f};
        float rampLen = Transition.DEFAULT_RAMP_LENGTH;
        int reverseMask = 0;
        int sliceCount = Transition.DEFAULT_SLICE_COUNT;
        int maskTexId = 0;

        float focusX = 0.5f;
        float focusY = 0.5f;
        float curveBias = 0.0f;
        if (effective != null && effective.getKind() != Transition.Kind.NONE) {
            shaderMode = mapKindToShaderMode(effective.getKind());
            float rawProgress = Math.clamp(activeStepProgress, 0.0f, 1.0f);
            shaderProgress = switch (effective.getKind()) {
                case EASE, MOVE -> Easing.evaluate(effective.getEaseType(), rawProgress);
                default -> rawProgress;
            };
            fadeRgba = effective.getFadeColorRgba();
            rampLen = effective.getRampLength();
            reverseMask = effective.isReverseMask() ? 1 : 0;
            sliceCount = effective.getSliceCount();
            focusX = effective.getFocusX();
            focusY = effective.getFocusY();
            curveBias = effective.getCurveBias();
            if (effective.getKind() == Transition.Kind.IMAGE_DISSOLVE
                    && texMgr != null
                    && effective.getMaskImagePath() != null) {
                Texture maskTex = texMgr.loadTexture(effective.getMaskImagePath());
                if (maskTex != null && !maskTex.isDisposed()) {
                    maskTexId = maskTex.getTextureId();
                }
            }
        }

        compositeShader.bind();
        compositeShader.setUniformMat4("uProjection", windowProjection);
        compositeShader.setUniformMat4("uModel", modelMatrix);
        compositeShader.setUniformInt("uFromTex", 0);
        compositeShader.setUniformInt("uToTex", 1);
        compositeShader.setUniformInt("uMaskTex", 2);
        compositeShader.setUniformInt("uMode", shaderMode);
        compositeShader.setUniformFloat("uProgress", shaderProgress);
        compositeShader.setUniformVec4("uFadeColor", fadeRgba[0], fadeRgba[1], fadeRgba[2], fadeRgba[3]);
        compositeShader.setUniformVec2("uResolution", fromFbo.getWidth(), fromFbo.getHeight());
        compositeShader.setUniformFloat("uRampLength", rampLen);
        compositeShader.setUniformInt("uReverseMask", reverseMask);
        compositeShader.setUniformInt("uSliceCount", sliceCount);
        compositeShader.setUniformInt("uHasMask", maskTexId != 0 ? 1 : 0);
        compositeShader.setUniformVec2("uIrisCenter", focusX, focusY);
        compositeShader.setUniformFloat("uCurveBias", curveBias);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fromFbo.getTextureId());
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, toFbo.getTextureId());
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, maskTexId);

        GL30.glBindVertexArray(quadVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);

        GL30.glBindVertexArray(0);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        compositeShader.unbind();
        GL11.glEnable(GL11.GL_BLEND);
    }

    /**
     * Disposes all OpenGL resources owned by this {@code TransitionManager}. Safe to call headlessly or repeatedly.
     */
    public void disposeGl() {
        if (compositeShader != null) {
            compositeShader.dispose();
            compositeShader = null;
        }
        if (quadVao != 0) {
            GL30.glDeleteVertexArrays(quadVao);
            quadVao = 0;
        }
        if (quadVbo != 0) {
            GL15.glDeleteBuffers(quadVbo);
            quadVbo = 0;
        }
        if (fromFbo != null) {
            fromFbo.dispose();
            fromFbo = null;
        }
        glInitialized = false;
    }

    /**
     * Returns whether OpenGL resources have been initialized via {@link #initGl(int, int)}.
     *
     * @return {@code true} if OpenGL resources have been initialized via {@link #initGl(int, int)}
     */
    public boolean isGlInitialized() {
        return glInitialized;
    }

    /**
     * Returns the internal captured "from" {@link Framebuffer}, if initialized.
     *
     * @return internal captured "from" {@link Framebuffer}, or {@code null} if GL is uninitialized
     */
    public Framebuffer getFromFramebuffer() {
        return fromFbo;
    }

    private void requireGlInitialized() {
        if (!glInitialized) {
            throw new IllegalStateException("TransitionManager GL resources have not been initialized");
        }
    }

    private static int mapKindToShaderMode(Transition.Kind kind) {
        return switch (kind) {
            case NONE -> MODE_NONE;
            case DISSOLVE, MOVE, EASE, MULTIPLE -> MODE_DISSOLVE;
            case FADE -> MODE_FADE;
            case PIXELLATE -> MODE_PIXELLATE;
            case WIPE_LEFT -> MODE_WIPE_LEFT;
            case WIPE_RIGHT -> MODE_WIPE_RIGHT;
            case WIPE_UP -> MODE_WIPE_UP;
            case WIPE_DOWN -> MODE_WIPE_DOWN;
            case SLIDE_LEFT -> MODE_SLIDE_LEFT;
            case SLIDE_RIGHT -> MODE_SLIDE_RIGHT;
            case PUSH_LEFT -> MODE_PUSH_LEFT;
            case PUSH_RIGHT -> MODE_PUSH_RIGHT;
            case PUSH_UP -> MODE_PUSH_UP;
            case IRIS_IN -> MODE_IRIS_IN;
            case IRIS_OUT -> MODE_IRIS_OUT;
            case BLINDS -> MODE_BLINDS;
            case SQUARES -> MODE_SQUARES;
            case IMAGE_DISSOLVE -> MODE_IMAGE_DISSOLVE;
            case FLASH_WHITE -> MODE_FLASH_WHITE;
            case SHATTER -> MODE_SHATTER;
            case DISSOLVE_CURVE -> MODE_DISSOLVE_CURVE;
        };
    }
}
