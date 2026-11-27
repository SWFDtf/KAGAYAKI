package de.swfdtf.graphics;

import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Transform2D;
import de.swfdtf.state.PreferencesState;

import java.util.Objects;

/**
 * Graphics sanitization, premultiplied alpha color blending math, multi-monitor dual-window
 * layout planning, transition refresh-rate convergence verification, rule-of-thirds dialog camera
 * framing, gamma calibration assistant, and smooth HUD dissolve fading ({@code #0955},
 * {@code #0976}, {@code #0985}, {@code #0993}, {@code #0998}, {@code #1001}, {@code #1007},
 * {@code #1015}).
 */
public final class GraphicsQualityAndFramingServices {

    private GraphicsQualityAndFramingServices() {
    }

    /**
     * Sanitizes {@link Transform2D} fields against {@code NaN}, {@code Infinity}, negative zoom, and
     * out-of-range opacity so corrupted calculations never poison a render frame ({@code #0955}).
     */
    public static final class TransformSanitizer {

        /** Minimum non-degenerate zoom floor applied during sanitization. */
        public static final float MIN_SAFE_ZOOM = 0.01f;

        /**
         * Creates a {@code TransformSanitizer}.
         */
        public TransformSanitizer() {
        }

        /**
         * Repairs any non-finite or unsafe negative transform properties in place ({@code #0955}).
         *
         * @param transform mutable {@link Transform2D} (must not be {@code null})
         * @return the sanitized {@code transform} instance
         */
        public static Transform2D sanitize(Transform2D transform) {
            Objects.requireNonNull(transform, "transform must not be null");
            transform.setXpos(finiteOrDefault(transform.getXpos(), 0.0f));
            transform.setYpos(finiteOrDefault(transform.getYpos(), 0.0f));
            transform.setXanchor(finiteOrDefault(transform.getXanchor(), 0.0f));
            transform.setYanchor(finiteOrDefault(transform.getYanchor(), 0.0f));
            transform.setZoom(Math.max(MIN_SAFE_ZOOM, finiteOrDefault(transform.getZoom(), 1.0f)));
            transform.setXzoom(clampSignedScale(transform.getXzoom()));
            transform.setYzoom(clampSignedScale(transform.getYzoom()));
            transform.setRotate(finiteOrDefault(transform.getRotate(), 0.0f));
            transform.setAlpha(Math.clamp(finiteOrDefault(transform.getAlpha(), 1.0f), 0.0f, 1.0f));
            transform.setBlur(Math.max(0.0f, finiteOrDefault(transform.getBlur(), 0.0f)));
            transform.setParallaxX(finiteOrDefault(transform.getParallaxX(), 0.0f));
            transform.setParallaxY(finiteOrDefault(transform.getParallaxY(), 0.0f));
            return transform;
        }

        private static float finiteOrDefault(float value, float fallback) {
            return Float.isFinite(value) ? value : fallback;
        }

        private static float clampSignedScale(float scale) {
            if (!Float.isFinite(scale)) {
                return 1.0f;
            }
            if (Math.abs(scale) < MIN_SAFE_ZOOM) {
                return scale < 0.0f ? -MIN_SAFE_ZOOM : MIN_SAFE_ZOOM;
            }
            return scale;
        }
    }

    /**
     * Guards scissor/viewport rectangles against negative dimensions and non-finite coordinates to
     * prevent OpenGL {@code GL_INVALID_VALUE} errors ({@code #0985}).
     */
    public static final class ViewportSanitizer {

        /**
         * Creates a {@code ViewportSanitizer}.
         */
        public ViewportSanitizer() {
        }

        /**
         * Clamps negative dimensions to {@code 0.0f} and normalizes non-finite values ({@code #0985}).
         *
         * @param x      viewport X
         * @param y      viewport Y
         * @param width  candidate width
         * @param height candidate height
         * @return safe non-negative {@link Rect2f}
         */
        public static Rect2f safeRect(float x, float y, float width, float height) {
            float sx = Float.isFinite(x) ? x : 0.0f;
            float sy = Float.isFinite(y) ? y : 0.0f;
            float sw = Float.isFinite(width) ? Math.max(0.0f, width) : 0.0f;
            float sh = Float.isFinite(height) ? Math.max(0.0f, height) : 0.0f;
            return new Rect2f(sx, sy, sw, sh);
        }

        /**
         * Strictly rejects negative dimensions or non-finite bounds with {@link IllegalArgumentException}.
         *
         * @param x      viewport X
         * @param y      viewport Y
         * @param width  required non-negative width
         * @param height required non-negative height
         * @return validated {@link Rect2f}
         */
        public static Rect2f requireNonNegativeRect(float x, float y, float width, float height) {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(width) || !Float.isFinite(height)) {
                throw new IllegalArgumentException("Viewport coordinates and sizes must be finite");
            }
            if (width < 0.0f || height < 0.0f) {
                throw new IllegalArgumentException(
                        "Viewport width and height must be >= 0, got " + width + "x" + height);
            }
            return new Rect2f(x, y, width, height);
        }
    }

    /**
     * Premultiplied-alpha Porter-Duff color math aligning CPU calculations with the fragment shader
     * ({@code #0976}).
     */
    public static final class ColorBlendMath {

        /**
         * Creates a {@code ColorBlendMath} utility.
         */
        public ColorBlendMath() {
        }

        /**
         * Converts straight RGBA in {@code [0, 1]} (clamping out-of-range alpha &gt; 1 or &lt; 0) to
         * premultiplied RGBA ({@code #0976}).
         *
         * @param r red channel
         * @param g green channel
         * @param b blue channel
         * @param a alpha channel
         * @return premultiplied {@code [r*a, g*a, b*a, a]}
         */
        public static float[] premultiply(float r, float g, float b, float a) {
            float ca = clamp01(a);
            return new float[]{clamp01(r) * ca, clamp01(g) * ca, clamp01(b) * ca, ca};
        }

        /**
         * Performs Porter-Duff {@code SRC_OVER} compositing on two premultiplied RGBA pixels.
         *
         * @param srcPremul foreground premultiplied RGBA
         * @param dstPremul background premultiplied RGBA
         * @return composited premultiplied RGBA
         */
        public static float[] blendOver(float[] srcPremul, float[] dstPremul) {
            Objects.requireNonNull(srcPremul, "srcPremul must not be null");
            Objects.requireNonNull(dstPremul, "dstPremul must not be null");
            float invSrcA = 1.0f - clamp01(srcPremul[3]);
            return new float[]{
                    clamp01(srcPremul[0] + dstPremul[0] * invSrcA),
                    clamp01(srcPremul[1] + dstPremul[1] * invSrcA),
                    clamp01(srcPremul[2] + dstPremul[2] * invSrcA),
                    clamp01(srcPremul[3] + dstPremul[3] * invSrcA)
            };
        }

        private static float clamp01(float v) {
            if (!Float.isFinite(v)) {
                return 0.0f;
            }
            return Math.clamp(v, 0.0f, 1.0f);
        }
    }

    /**
     * Computes clean side-by-side Dual-Window coordinates for Studio Window A (left) and Game
     * Window B (right) inside {@code glfwGetMonitorWorkarea} without edge overflow ({@code #0993}).
     */
    public static final class DualWindowLayoutPlanner {

        /** Standard gap between Window A and Window B in workarea pixels. */
        public static final float WINDOW_GAP_PX = 16.0f;

        /** Outer workarea padding keeping window decorations clear of taskbars/docks. */
        public static final float OUTER_MARGIN_PX = 24.0f;

        /**
         * Creates a {@code DualWindowLayoutPlanner}.
         */
        public DualWindowLayoutPlanner() {
        }

        /**
         * Places Window A on the left half and Window B on the right half inside {@code workarea}
         * ({@code #0993}).
         *
         * @param workarea usable monitor workarea rect
         * @return 2-element array {@code [windowARect, windowBRect]}
         */
        public static Rect2f[] computeSideBySideLayout(Rect2f workarea) {
            Objects.requireNonNull(workarea, "workarea must not be null");
            float margin = Math.min(OUTER_MARGIN_PX, Math.min(workarea.width(), workarea.height()) * 0.05f);
            float usableW = Math.max(200.0f, workarea.width() - 2.0f * margin - WINDOW_GAP_PX);
            float usableH = Math.max(120.0f, workarea.height() - 2.0f * margin);
            float leftW = (float) Math.floor(usableW * 0.44f);
            float rightW = (float) Math.floor(usableW - leftW);
            Rect2f winA = new Rect2f(workarea.x() + margin, workarea.y() + margin, leftW, usableH);
            Rect2f winB = new Rect2f(winA.x() + leftW + WINDOW_GAP_PX, workarea.y() + margin, rightW, usableH);
            return new Rect2f[]{winA, winB};
        }
    }

    /**
     * Simulates {@link Transition} duration convergence independently of display refresh rate
     * (e.g. 60 Hz vs 144 Hz) ({@code #0998}).
     */
    public static final class TransitionConvergenceVerifier {

        /**
         * Creates a {@code TransitionConvergenceVerifier}.
         */
        public TransitionConvergenceVerifier() {
        }

        /**
         * Steps {@code transition} using fixed steps of {@code 1.0 / hz} until completion
         * ({@code #0998}).
         *
         * @param transition descriptor under test (must not be {@code null})
         * @param hz         simulated refresh rate in Hz ({@code > 0})
         * @return measured elapsed seconds until {@code !isActive()}
         */
        public static double simulateAtRate(Transition transition, int hz) {
            Objects.requireNonNull(transition, "transition must not be null");
            if (hz <= 0) {
                throw new IllegalArgumentException("hz must be > 0, got " + hz);
            }
            TransitionManager manager = new TransitionManager();
            manager.startTransition(transition);
            double dt = 1.0 / hz;
            double elapsed = 0.0;
            int safetyMax = Math.max(10_000, hz * 30);
            for (int step = 0; step < safetyMax && manager.isActive(); step++) {
                manager.update(dt);
                elapsed += dt;
            }
            return elapsed;
        }
    }

    /**
     * Rule-of-thirds dialog composition presets automating {@code two_shot} and
     * {@code establishing_shot} sprite framing ({@code #1001}).
     */
    public static final class DialogCameraFramingPresets {

        /** Horizontal left third factor (1/3). */
        public static final float THIRD_LEFT = 1.0f / 3.0f;

        /** Horizontal right third factor (2/3). */
        public static final float THIRD_RIGHT = 2.0f / 3.0f;

        /** Standard baseline vertical anchor ratio for standing dialogue portraits. */
        public static final float PORTRAIT_VERTICAL_RATIO = 0.85f;

        /**
         * Creates a {@code DialogCameraFramingPresets} helper.
         */
        public DialogCameraFramingPresets() {
        }

        /**
         * Frames two dialog partners symmetrically along vertical thirds ({@code #1001}).
         *
         * @param left          left-side speaker sprite (must not be {@code null})
         * @param right         right-side speaker sprite (must not be {@code null})
         * @param logicalWidth  canvas logical width
         * @param logicalHeight canvas logical height
         */
        public static void frameTwoShot(
                SpriteNode left,
                SpriteNode right,
                float logicalWidth,
                float logicalHeight
        ) {
            Objects.requireNonNull(left, "left must not be null");
            Objects.requireNonNull(right, "right must not be null");
            left.getTransform()
                    .setXanchor(0.5f)
                    .setYanchor(1.0f)
                    .setXpos(logicalWidth * THIRD_LEFT)
                    .setYpos(logicalHeight * PORTRAIT_VERTICAL_RATIO)
                    .setZoom(1.0f);
            right.getTransform()
                    .setXanchor(0.5f)
                    .setYanchor(1.0f)
                    .setXpos(logicalWidth * THIRD_RIGHT)
                    .setYpos(logicalHeight * PORTRAIT_VERTICAL_RATIO)
                    .setZoom(1.0f);
        }

        /**
         * Frames a wide establishing shot centering the focal sprite cleanly ({@code #1001}).
         *
         * @param subject       focal sprite node (must not be {@code null})
         * @param logicalWidth  canvas logical width
         * @param logicalHeight canvas logical height
         */
        public static void frameEstablishingShot(SpriteNode subject, float logicalWidth, float logicalHeight) {
            Objects.requireNonNull(subject, "subject must not be null");
            subject.getTransform()
                    .setXanchor(0.5f)
                    .setYanchor(1.0f)
                    .setXpos(logicalWidth * 0.5f)
                    .setYpos(logicalHeight * 0.78f)
                    .setZoom(0.75f);
        }
    }

    /**
     * Brightness &amp; contrast calibration assistant storing user-selected gamma into
     * {@link PreferencesState} ({@code #1007}).
     */
    public static final class DisplayCalibrationAssistant {

        /** Minimum supported display gamma. */
        public static final float MIN_GAMMA = 0.5f;

        /** Maximum supported display gamma. */
        public static final float MAX_GAMMA = 2.2f;

        /**
         * Creates a {@code DisplayCalibrationAssistant}.
         */
        public DisplayCalibrationAssistant() {
        }

        /**
         * Applies clamped display gamma to {@code prefs} ({@code #1007}).
         *
         * @param prefs target preferences instance (must not be {@code null})
         * @param gamma requested display gamma
         * @return effective clamped gamma saved to preferences
         */
        public static float applyGamma(PreferencesState prefs, float gamma) {
            Objects.requireNonNull(prefs, "prefs must not be null");
            float safe = Float.isFinite(gamma) ? Math.clamp(gamma, MIN_GAMMA, MAX_GAMMA) : 1.0f;
            prefs.setDisplayGamma(safe);
            return prefs.getDisplayGamma();
        }

        /**
         * Computes perceived star pattern luminance on dark background for calibration feedback.
         *
         * @param gamma display gamma
         * @return perceived contrast score in {@code [0, 1]}
         */
        public static float computeCalibrationStarLuminance(float gamma) {
            float g = Float.isFinite(gamma) ? Math.clamp(gamma, MIN_GAMMA, MAX_GAMMA) : 1.0f;
            return (float) Math.pow(0.18, 1.0 / g);
        }
    }

    /**
     * Smooth HUD alpha fade controller for {@code window hide} / {@code window show} cutscene
     * transitions ({@code 0.2 s} default dissolve) ({@code #1015}).
     */
    public static final class HudFadeController {

        /** Default smooth dissolve fade duration in seconds for {@code window hide/show}. */
        public static final double DEFAULT_DISSOLVE_SECONDS = 0.2;

        private float currentAlpha = 1.0f;
        private float startAlpha = 1.0f;
        private float targetAlpha = 1.0f;
        private double elapsed = 0.0;
        private double duration = 0.0;

        /**
         * Creates a {@code HudFadeController} initially visible at full opacity {@code 1.0f}.
         */
        public HudFadeController() {
        }

        /**
         * Starts smoothly fading the HUD dialogue window out to {@code 0.0f} ({@code #1015}).
         *
         * @param fadeSeconds duration in seconds ({@code <= 0} uses {@link #DEFAULT_DISSOLVE_SECONDS})
         */
        public void hideWithDissolve(double fadeSeconds) {
            startFade(0.0f, fadeSeconds);
        }

        /**
         * Starts smoothly fading the HUD dialogue window back in to {@code 1.0f} ({@code #1015}).
         *
         * @param fadeSeconds duration in seconds ({@code <= 0} uses {@link #DEFAULT_DISSOLVE_SECONDS})
         */
        public void showWithDissolve(double fadeSeconds) {
            startFade(1.0f, fadeSeconds);
        }

        private void startFade(float target, double fadeSeconds) {
            this.startAlpha = this.currentAlpha;
            this.targetAlpha = target;
            this.duration = (Double.isFinite(fadeSeconds) && fadeSeconds > 0.0)
                    ? fadeSeconds
                    : DEFAULT_DISSOLVE_SECONDS;
            this.elapsed = 0.0;
        }

        /**
         * Advances active HUD opacity interpolation by {@code dt} seconds.
         *
         * @param dt frame delta time in seconds
         */
        public void update(double dt) {
            if (!Double.isFinite(dt) || dt <= 0.0 || currentAlpha == targetAlpha) {
                return;
            }
            elapsed += dt;
            float t = (float) Math.clamp(elapsed / duration, 0.0, 1.0);
            currentAlpha = startAlpha + (targetAlpha - startAlpha) * t;
            if (t >= 1.0f) {
                currentAlpha = targetAlpha;
            }
        }

        /**
         * Returns current HUD textbox opacity in {@code [0, 1]}.
         *
         * @return current HUD alpha
         */
        public float getAlpha() {
            return currentAlpha;
        }
    }
}
