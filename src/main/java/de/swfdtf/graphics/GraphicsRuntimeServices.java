package de.swfdtf.graphics;

import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Easing;
import de.swfdtf.math.Rect2f;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Cohesive suite of headless-capable 2D graphics utilities and post-processing/UI runtime helpers
 * for KAGAYAKI:
 *
 * <ul>
 *   <li>{@link FramePacer} — configurable FPS cap ({@code 30/60/120/unlimited}) and {@code 15 FPS} idle throttle</li>
 *   <li>{@link SoftwareRasterizer} — CPU RGBA8 rasterizer for headless screenshot &amp; UI overlap tests</li>
 *   <li>{@link AnimatedImageDecoder} — multi-frame GIF decoder via {@code stbi_load_gif_from_memory}</li>
 *   <li>{@link VectorDebugRenderer} &amp; {@link SpeechBubblePointer} — anti-aliased line/Bezier/bubble strips</li>
 *   <li>{@link ColorMatrixKeyframeTimeline} — keyframed color grading transitions returning to identity</li>
 *   <li>{@link ColorLut}, {@link HdrToneMapper}, {@link SplitViewConfig}, {@link SafeAreaGuides},
 *       {@link AdaptiveContrastAdvisor}, {@link FocusVignetteController}, {@link ReflectionZone},
 *       {@link MotionTrailTracker}, {@link GradientSpec}, {@link SoftwareCursorRenderer}, and {@link MaskConfig}</li>
 * </ul>
 */
public final class GraphicsRuntimeServices {

    private GraphicsRuntimeServices() {
    }

    /**
     * Frame pacing controller supporting configurable FPS caps ({@code 30}, {@code 60}, {@code 120}, or
     * {@code 0} = unlimited) and automatic {@code 15 FPS} idle throttling that wakes within 1 frame on input.
     */
    public static final class FramePacer {

        /** Target FPS when idle throttling activates. */
        public static final int IDLE_THROTTLE_FPS = 15;

        private int targetFps = 60;
        private boolean idleThrottleEnabled = true;
        private double idleThresholdSeconds = 5.0;
        private double idleElapsedSeconds = 0.0;
        private boolean currentlyThrottled = false;

        /**
         * Creates a {@code FramePacer} defaulting to {@code 60 FPS} with idle throttling enabled.
         */
        public FramePacer() {
        }

        /**
         * Configures target FPS cap ({@code 0} = unlimited, otherwise {@code >= 15}).
         *
         * @param targetFps target FPS cap
         */
        public void setTargetFps(int targetFps) {
            this.targetFps = Math.max(0, targetFps);
        }

        /**
         * Returns configured active target FPS ({@code 0} = unlimited).
         *
         * @return active target FPS
         */
        public int getTargetFps() {
            return targetFps;
        }

        /**
         * Enables or disables automatic {@code 15 FPS} idle power-saving throttle.
         *
         * @param enabled {@code true} to enable idle throttling
         */
        public void setIdleThrottleEnabled(boolean enabled) {
            this.idleThrottleEnabled = enabled;
            if (!enabled) {
                currentlyThrottled = false;
            }
        }

        /**
         * Sets inactivity threshold before idle throttling engages.
         *
         * @param idleThresholdSeconds threshold in seconds ({@code >= 0.1})
         */
        public void setIdleThresholdSeconds(double idleThresholdSeconds) {
            this.idleThresholdSeconds = Double.isFinite(idleThresholdSeconds)
                    ? Math.max(0.1, idleThresholdSeconds)
                    : 5.0;
        }

        /**
         * Notifies the pacer of user input or active animation, waking immediately back to full FPS within 1 frame.
         */
        public void notifyActivity() {
            this.idleElapsedSeconds = 0.0;
            this.currentlyThrottled = false;
        }

        /**
         * Advances frame clock by {@code dt} seconds and updates idle throttle status.
         *
         * @param dt              elapsed time in seconds
         * @param hasActiveMotion {@code true} if input or animation occurred this frame
         */
        public void update(double dt, boolean hasActiveMotion) {
            if (hasActiveMotion) {
                notifyActivity();
                return;
            }
            if (Double.isFinite(dt) && dt > 0.0) {
                idleElapsedSeconds += dt;
            }
            currentlyThrottled = idleThrottleEnabled && idleElapsedSeconds >= idleThresholdSeconds;
        }

        /**
         * Returns effective FPS limit considering active idle throttling.
         *
         * @return effective FPS limit ({@code 0} = unlimited)
         */
        public int getEffectiveFpsLimit() {
            if (currentlyThrottled) {
                return IDLE_THROTTLE_FPS;
            }
            return targetFps;
        }

        /**
         * Returns target frame interval in nanoseconds ({@code 0L} when uncapped).
         *
         * @return frame interval in nanoseconds
         */
        public long getTargetFrameTimeNanos() {
            int eff = getEffectiveFpsLimit();
            return eff <= 0 ? 0L : Math.round(1_000_000_000.0 / eff);
        }

        /**
         * Returns whether idle power-saving throttling is currently active.
         *
         * @return {@code true} when throttled to 15 FPS
         */
        public boolean isCurrentlyThrottled() {
            return currentlyThrottled;
        }
    }

    /**
     * Headless CPU RGBA8 software rasterizer rendering {@link SceneGraph} rectangles, node colors, and
     * verifying UI layout overlaps without an OpenGL driver or GLFW window.
     */
    public static final class SoftwareRasterizer {

        private SoftwareRasterizer() {
        }

        /**
         * Renders visible nodes of {@code graph} sorted by layer and node Z-order onto a straight RGBA8 buffer.
         *
         * @param graph  scene graph to rasterize
         * @param width  output width in pixels ({@code > 0})
         * @param height output height in pixels ({@code > 0})
         * @return straight RGBA8 byte buffer ({@code width * height * 4})
         */
        public static byte[] rasterize(SceneGraph graph, int width, int height) {
            Objects.requireNonNull(graph, "graph must not be null");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Dimensions must be positive");
            }
            byte[] out = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
            fillBackground(out, width, height, 0x0D, 0x0E, 0x15, 255);
            float scaleX = width / 1920.0f;
            float scaleY = height / 1080.0f;
            for (SceneLayer layer : graph.getLayersSorted()) {
                if (!layer.isVisible()) {
                    continue;
                }
                for (SceneNode node : layer.getSortedNodes()) {
                    rasterNode(out, width, height, node, 0.0f, 0.0f, scaleX, scaleY, 1.0f);
                }
            }
            return out;
        }

        /**
         * Computes intersection area between two layout bounds rectangles to detect unwanted UI overlaps
         * in headless CI layout verification.
         *
         * @param a first rectangle
         * @param b second rectangle
         * @return intersection area in square logical pixels ({@code 0.0f} when disjoint)
         */
        public static float computeOverlapArea(Rect2f a, Rect2f b) {
            Objects.requireNonNull(a, "a must not be null");
            Objects.requireNonNull(b, "b must not be null");
            float x0 = Math.max(a.x(), b.x());
            float y0 = Math.max(a.y(), b.y());
            float x1 = Math.min(a.x() + a.width(), b.x() + b.width());
            float y1 = Math.min(a.y() + a.height(), b.y() + b.height());
            float w = x1 - x0;
            float h = y1 - y0;
            return (w > 0.0f && h > 0.0f) ? w * h : 0.0f;
        }

        private static void rasterNode(
                byte[] buf, int bufW, int bufH,
                SceneNode node, float parentX, float parentY, float sx, float sy, float parentAlpha
        ) {
            if (!node.isVisible()) {
                return;
            }
            var t = node.getTransform();
            float effA = Math.clamp(parentAlpha * t.getAlpha(), 0.0f, 1.0f);
            if (effA <= 0.0f) {
                return;
            }
            Rect2f r = t.resolveBounds(node.getWidth(), node.getHeight(), 1920.0f, 1080.0f);
            float lx = parentX + r.x();
            float ly = parentY + r.y();
            int x0 = Math.max(0, Math.round(lx * sx));
            int y0 = Math.max(0, Math.round(ly * sy));
            int x1 = Math.min(bufW, Math.round((lx + r.width()) * sx));
            int y1 = Math.min(bufH, Math.round((ly + r.height()) * sy));
            float[] fill = node.getFillColorRgba();
            float srcA = Math.clamp(fill[3] * effA, 0.0f, 1.0f);
            if (x1 > x0 && y1 > y0 && srcA > 0.0f) {
                int sr = Math.clamp(Math.round(fill[0] * 255.0f), 0, 255);
                int sg = Math.clamp(Math.round(fill[1] * 255.0f), 0, 255);
                int sb = Math.clamp(Math.round(fill[2] * 255.0f), 0, 255);
                float invA = 1.0f - srcA;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int idx = (y * bufW + x) * 4;
                        int dr = buf[idx] & 0xFF;
                        int dg = buf[idx + 1] & 0xFF;
                        int db = buf[idx + 2] & 0xFF;
                        buf[idx] = (byte) Math.round(sr * srcA + dr * invA);
                        buf[idx + 1] = (byte) Math.round(sg * srcA + dg * invA);
                        buf[idx + 2] = (byte) Math.round(sb * srcA + db * invA);
                        buf[idx + 3] = (byte) 255;
                    }
                }
            }
            for (SceneNode child : node.getChildren()) {
                rasterNode(buf, bufW, bufH, child, lx, ly, sx, sy, effA);
            }
        }

        private static void fillBackground(byte[] buf, int w, int h, int r, int g, int b, int a) {
            for (int i = 0; i < w * h; i++) {
                int off = i * 4;
                buf[off] = (byte) r;
                buf[off + 1] = (byte) g;
                buf[off + 2] = (byte) b;
                buf[off + 3] = (byte) a;
            }
        }
    }

    /**
     * Multi-frame animated sticker / GIF image sequence holding straight RGBA8 frames and per-frame
     * millisecond delays. Decodes GIF bytes natively via {@link STBImage#stbi_load_gif_from_memory}.
     */
    public static final class AnimatedImageDecoder {

        /**
         * Immutable single decoded animation frame with exact millisecond delay.
         *
         * @param width        frame width in pixels
         * @param height       frame height in pixels
         * @param delayMs      frame display duration in milliseconds
         * @param straightRgba tightly packed RGBA8 pixels
         */
        public record AnimatedFrame(int width, int height, int delayMs, byte[] straightRgba) {
        }

        private AnimatedImageDecoder() {
        }

        /**
         * Decodes an animated GIF byte buffer using {@code STBImage.stbi_load_gif_from_memory}.
         *
         * @param gifBytes raw animated GIF file bytes
         * @return list of {@link AnimatedFrame}s preserving per-frame millisecond delays
         */
        public static List<AnimatedFrame> decodeGif(byte[] gifBytes) {
            Objects.requireNonNull(gifBytes, "gifBytes must not be null");
            if (gifBytes.length == 0) {
                throw new IllegalArgumentException("gifBytes must not be empty");
            }
            ByteBuffer src = MemoryUtil.memAlloc(gifBytes.length);
            IntBuffer xBuf = MemoryUtil.memAllocInt(1);
            IntBuffer yBuf = MemoryUtil.memAllocInt(1);
            IntBuffer zBuf = MemoryUtil.memAllocInt(1);
            IntBuffer compBuf = MemoryUtil.memAllocInt(1);
            org.lwjgl.PointerBuffer delaysPtr = MemoryUtil.memAllocPointer(1);
            ByteBuffer decoded = null;
            try {
                src.put(gifBytes).flip();
                decoded = STBImage.stbi_load_gif_from_memory(src, delaysPtr, xBuf, yBuf, zBuf, compBuf, 4);
                if (decoded == null) {
                    throw new IllegalStateException("Failed to decode GIF: " + STBImage.stbi_failure_reason());
                }
                int w = xBuf.get(0);
                int h = yBuf.get(0);
                int frames = zBuf.get(0);
                int frameBytes = w * h * 4;
                IntBuffer delays = delaysPtr.getIntBuffer(frames);
                List<AnimatedFrame> list = new ArrayList<>(frames);
                for (int f = 0; f < frames; f++) {
                    byte[] px = new byte[frameBytes];
                    decoded.position(f * frameBytes);
                    decoded.get(px);
                    int delay = Math.max(10, delays.get(f));
                    list.add(new AnimatedFrame(w, h, delay, px));
                }
                return Collections.unmodifiableList(list);
            } finally {
                if (decoded != null) {
                    STBImage.stbi_image_free(decoded);
                }
                long delayAddr = delaysPtr.get(0);
                if (delayAddr != 0L) {
                    MemoryUtil.nmemFree(delayAddr);
                }
                MemoryUtil.memFree(delaysPtr);
                MemoryUtil.memFree(compBuf);
                MemoryUtil.memFree(zBuf);
                MemoryUtil.memFree(yBuf);
                MemoryUtil.memFree(xBuf);
                MemoryUtil.memFree(src);
            }
        }

        /**
         * Resolves which frame index should be displayed at elapsed playback time {@code elapsedMs}.
         *
         * @param frames    non-empty frame sequence
         * @param elapsedMs elapsed milliseconds ({@code >= 0})
         * @return zero-based active frame index
         */
        public static int resolveFrameIndexAtMs(List<AnimatedFrame> frames, long elapsedMs) {
            Objects.requireNonNull(frames, "frames must not be null");
            if (frames.isEmpty()) {
                throw new IllegalArgumentException("frames must not be empty");
            }
            long totalDuration = 0L;
            for (AnimatedFrame f : frames) {
                totalDuration += Math.max(1, f.delayMs());
            }
            long modTime = totalDuration > 0 ? Math.floorMod(Math.max(0L, elapsedMs), totalDuration) : 0L;
            long accum = 0L;
            for (int i = 0; i < frames.size(); i++) {
                accum += Math.max(1, frames.get(i).delayMs());
                if (modTime < accum) {
                    return i;
                }
            }
            return frames.size() - 1;
        }
    }

    /**
     * CPU tessellator for anti-aliased variable-thickness vector lines, polygons, and cubic Bezier curves
     * rendered cleanly with GPU triangles under macOS OpenGL Core Profile (bypassing 1px {@code glLineWidth} limits).
     */
    public static final class VectorDebugRenderer {

        /**
         * Tessellated triangle vertex list in screen-space {@code [x0, y0, x1, y1, ...]} pairs (6 floats per tri).
         *
         * @param triangleVertices float array of length divisible by 6
         */
        public record TessellatedMesh(float[] triangleVertices) {
            /**
             * Returns triangle count.
             *
             * @return number of triangles
             */
            public int triangleCount() {
                return triangleVertices.length / 6;
            }
        }

        private VectorDebugRenderer() {
        }

        /**
         * Evaluates a point along a cubic Bezier curve at parameter {@code t in [0, 1]}.
         *
         * @param x0 start X
         * @param y0 start Y
         * @param cx1 control 1 X
         * @param cy1 control 1 Y
         * @param cx2 control 2 X
         * @param cy2 control 2 Y
         * @param x1 end X
         * @param y1 end Y
         * @param t  curve parameter {@code [0, 1]}
         * @return 2-element array {@code [x, y]}
         */
        public static float[] evaluateCubicBezier(
                float x0, float y0, float cx1, float cy1, float cx2, float cy2, float x1, float y1, float t
        ) {
            float u = 1.0f - t;
            float tt = t * t;
            float uu = u * u;
            float uuu = uu * u;
            float ttt = tt * t;
            float x = uuu * x0 + 3 * uu * t * cx1 + 3 * u * tt * cx2 + ttt * x1;
            float y = uuu * y0 + 3 * uu * t * cy1 + 3 * u * tt * cy2 + ttt * y1;
            return new float[]{x, y};
        }

        /**
         * Tessellates a polyline segment list of thickness {@code thicknessPx > 1} into GPU-ready triangles.
         *
         * @param pointsXY    flattened XY polyline coordinates (even length {@code >= 4})
         * @param thicknessPx stroke thickness in logical pixels ({@code > 0})
         * @return tessellated {@link TessellatedMesh}
         */
        public static TessellatedMesh tessellatePolyline(float[] pointsXY, float thicknessPx) {
            Objects.requireNonNull(pointsXY, "pointsXY must not be null");
            if (pointsXY.length < 4 || pointsXY.length % 2 != 0) {
                throw new IllegalArgumentException("Expected even polyline length >= 4");
            }
            float halfW = Math.max(0.5f, thicknessPx * 0.5f);
            int segments = (pointsXY.length / 2) - 1;
            float[] tris = new float[segments * 12];
            int out = 0;
            for (int i = 0; i < segments; i++) {
                float x0 = pointsXY[i * 2];
                float y0 = pointsXY[i * 2 + 1];
                float x1 = pointsXY[(i + 1) * 2];
                float y1 = pointsXY[(i + 1) * 2 + 1];
                float dx = x1 - x0;
                float dy = y1 - y0;
                float len = (float) Math.hypot(dx, dy);
                float nx = len > 1e-4f ? (-dy / len) * halfW : 0.0f;
                float ny = len > 1e-4f ? (dx / len) * halfW : 0.0f;

                float ax = x0 + nx;
                float ay = y0 + ny;
                float bx = x0 - nx;
                float by = y0 - ny;
                float cx = x1 + nx;
                float cy = y1 + ny;
                float ddx = x1 - nx;
                float ddy = y1 - ny;

                tris[out++] = ax;
                tris[out++] = ay;
                tris[out++] = bx;
                tris[out++] = by;
                tris[out++] = cx;
                tris[out++] = cy;
                tris[out++] = cx;
                tris[out++] = cy;
                tris[out++] = bx;
                tris[out++] = by;
                tris[out++] = ddx;
                tris[out++] = ddy;
            }
            return new TessellatedMesh(tris);
        }

        /**
         * Draws a thick straight vector segment in {@link RenderPipeline2D} via rotated quad geometry.
         *
         * @param pipeline    active 2D pipeline
         * @param x0          start X
         * @param y0          start Y
         * @param x1          end X
         * @param y1          end Y
         * @param thicknessPx stroke width in pixels
         * @param rgba        straight RGBA color {@code [r, g, b, a]}
         */
        public static void drawLine(
                RenderPipeline2D pipeline,
                float x0, float y0, float x1, float y1, float thicknessPx, float[] rgba
        ) {
            if (pipeline == null || !pipeline.isInitialized()) {
                return;
            }
            float dx = x1 - x0;
            float dy = y1 - y0;
            float len = (float) Math.hypot(dx, dy);
            if (len <= 1e-4f) {
                return;
            }
            float angleDeg = (float) Math.toDegrees(Math.atan2(dy, dx));
            pipeline.drawTexturedQuadPivot(
                    0,
                    x0,
                    y0 - thicknessPx * 0.5f,
                    len,
                    thicknessPx,
                    new Rect2f(0.0f, 0.0f, 1.0f, 1.0f),
                    rgba,
                    ColorMatrix4f.identity(),
                    angleDeg,
                    x0,
                    y0
            );
        }
    }

    /**
     * Dynamic speech-bubble vector tail calculator targeting a speaking {@link SceneNode}.
     */
    public static final class SpeechBubblePointer {

        private SpeechBubblePointer() {
        }

        /**
         * Computes the triangle vertices {@code [baseLeftX, baseLeftY, tipX, tipY, baseRightX, baseRightY]}
         * pointing smoothly toward the speaking character's face center.
         *
         * @param bubbleBounds dialogue bubble rectangle
         * @param speakerNode  speaking {@link SceneNode}
         * @param logicalW     logical stage width
         * @param logicalH     logical stage height
         * @return 6-float triangle vertex array
         */
        public static float[] computePointerTriangle(
                Rect2f bubbleBounds,
                SceneNode speakerNode,
                float logicalW,
                float logicalH
        ) {
            Objects.requireNonNull(bubbleBounds, "bubbleBounds must not be null");
            Objects.requireNonNull(speakerNode, "speakerNode must not be null");
            Rect2f sp = speakerNode.getTransform().resolveBounds(
                    speakerNode.getWidth(), speakerNode.getHeight(), logicalW, logicalH
            );
            float tipX = sp.x() + sp.width() * 0.5f;
            float tipY = sp.y() + sp.height() * 0.35f;
            float baseX = Math.clamp(
                    tipX,
                    bubbleBounds.x() + 28.0f,
                    Math.max(bubbleBounds.x() + 28.0f, bubbleBounds.x() + bubbleBounds.width() - 28.0f)
            );
            float baseY = (tipY < bubbleBounds.y())
                    ? bubbleBounds.y()
                    : (bubbleBounds.y() + bubbleBounds.height());
            return new float[]{baseX - 16.0f, baseY, tipX, tipY, baseX + 16.0f, baseY};
        }
    }

    /**
     * Keyframed {@link ColorMatrix4f} timeline ({@code animate color}) returning cleanly to
     * {@link ColorMatrix4f#identity()} once the timed flash/desaturation effect ends.
     */
    public static final class ColorMatrixKeyframeTimeline {

        private ColorMatrix4f activeMatrix = ColorMatrix4f.identity();
        private ColorMatrix4f peakMatrix = ColorMatrix4f.identity();
        private double durationSeconds = 0.0;
        private double elapsedSeconds = 0.0;
        private boolean active = false;

        /** Creates an idle timeline initialized to {@link ColorMatrix4f#identity()}. */
        public ColorMatrixKeyframeTimeline() {
        }

        /**
         * Starts a flash/grade timeline surging immediately toward {@code targetMatrix} and returning cleanly
         * to {@link ColorMatrix4f#identity()} after {@code durationSeconds}.
         *
         * @param targetMatrix    target color matrix at effect onset/peak
         * @param durationSeconds total effect duration in seconds
         */
        public void startFlashToIdentity(ColorMatrix4f targetMatrix, double durationSeconds) {
            this.peakMatrix = Objects.requireNonNull(targetMatrix, "targetMatrix must not be null");
            if (durationSeconds <= 0.0) {
                this.activeMatrix = ColorMatrix4f.identity();
                this.active = false;
                return;
            }
            this.durationSeconds = durationSeconds;
            this.elapsedSeconds = 0.0;
            this.activeMatrix = peakMatrix;
            this.active = true;
        }

        /**
         * Advances the timeline by {@code dt} seconds.
         *
         * @param dt delta time in seconds
         */
        public void update(double dt) {
            if (!active || !Double.isFinite(dt) || dt <= 0.0) {
                return;
            }
            elapsedSeconds = Math.min(durationSeconds, elapsedSeconds + dt);
            float t = Easing.smoothstep((float) (elapsedSeconds / durationSeconds));
            activeMatrix = peakMatrix.lerp(ColorMatrix4f.identity(), t);
            if (elapsedSeconds >= durationSeconds) {
                activeMatrix = ColorMatrix4f.identity();
                active = false;
            }
        }

        /**
         * Returns current interpolated {@link ColorMatrix4f}.
         *
         * @return current matrix
         */
        public ColorMatrix4f getCurrentMatrix() {
            return activeMatrix;
        }

        /**
         * Returns whether timeline playback is still running.
         *
         * @return {@code true} if active
         */
        public boolean isActive() {
            return active;
        }
    }

    /**
     * Splitscreen dual-panel scissor configuration ({@code split_view}) with guaranteed non-overlapping
     * left/right viewports.
     *
     * @param enabled       whether split view is enabled
     * @param dividerGapPx  center divider gap in logical pixels
     * @param leftViewport  scissor/camera bounds for left panel
     * @param rightViewport scissor/camera bounds for right panel
     */
    public record SplitViewConfig(
            boolean enabled,
            float dividerGapPx,
            Rect2f leftViewport,
            Rect2f rightViewport
    ) {
        /**
         * Creates a non-overlapping left/right split viewport inside {@code logicalW x logicalH}.
         *
         * @param logicalW     canvas width
         * @param logicalH     canvas height
         * @param dividerGapPx gap between halves ({@code >= 0})
         * @return configured {@link SplitViewConfig}
         */
        public static SplitViewConfig horizontalSplit(float logicalW, float logicalH, float dividerGapPx) {
            float gap = Math.max(0.0f, dividerGapPx);
            float halfW = Math.max(1.0f, (logicalW - gap) * 0.5f);
            return new SplitViewConfig(
                    true,
                    gap,
                    new Rect2f(0.0f, 0.0f, halfW, logicalH),
                    new Rect2f(halfW + gap, 0.0f, halfW, logicalH)
            );
        }
    }

    /**
     * Broadcast / SteamDeck Safe-Area overlay guide calculator ({@code Title Safe 90%}, {@code Action Safe 95%}).
     */
    public static final class SafeAreaGuides {

        private SafeAreaGuides() {
        }

        /**
         * Returns broadcast Title Safe rectangle ({@code 90%}) centered inside {@code logicalW x logicalH}.
         *
         * @param logicalW canvas width
         * @param logicalH canvas height
         * @return Title Safe rectangle
         */
        public static Rect2f titleSafe90(float logicalW, float logicalH) {
            return insetNormalized(logicalW, logicalH, 0.90f);
        }

        /**
         * Returns broadcast Action Safe rectangle ({@code 95%}) centered inside {@code logicalW x logicalH}.
         *
         * @param logicalW canvas width
         * @param logicalH canvas height
         * @return Action Safe rectangle
         */
        public static Rect2f actionSafe95(float logicalW, float logicalH) {
            return insetNormalized(logicalW, logicalH, 0.95f);
        }

        private static Rect2f insetNormalized(float w, float h, float keepRatio) {
            float margin = (1.0f - keepRatio) * 0.5f;
            return new Rect2f(w * margin, h * margin, w * keepRatio, h * keepRatio);
        }
    }

    /**
     * Adaptive UI contrast advisor computing average scene luminance and suggesting higher textbox backdrop
     * opacity in bright daylight scenes so dialogue text always remains crisp and legible.
     */
    public static final class AdaptiveContrastAdvisor {

        private AdaptiveContrastAdvisor() {
        }

        /**
         * Computes average Rec. 709 relative luminance in {@code [0, 1]} from an RGBA8 framebuffer snapshot.
         *
         * @param rgbaBytes tightly packed RGBA8 pixel array
         * @return mean luminance in {@code [0, 1]}
         */
        public static float computeMeanLuminance(byte[] rgbaBytes) {
            Objects.requireNonNull(rgbaBytes, "rgbaBytes must not be null");
            if (rgbaBytes.length < 4) {
                return 0.0f;
            }
            double sum = 0.0;
            int pixels = rgbaBytes.length / 4;
            for (int i = 0; i < pixels; i++) {
                int off = i * 4;
                float r = (rgbaBytes[off] & 0xFF) / 255.0f;
                float g = (rgbaBytes[off + 1] & 0xFF) / 255.0f;
                float b = (rgbaBytes[off + 2] & 0xFF) / 255.0f;
                sum += 0.2126 * r + 0.7152 * g + 0.0722 * b;
            }
            return (float) Math.clamp(sum / pixels, 0.0, 1.0);
        }

        /**
         * Suggests elevated dark-panel opacity in {@code [baseAlpha, maxAlpha]} when scene luminance is bright.
         *
         * @param sceneLuminance mean scene luminance in {@code [0, 1]}
         * @param baseAlpha      default dark-mode backdrop opacity (e.g. {@code 0.72f})
         * @param maxAlpha       maximum boosted opacity (e.g. {@code 0.94f})
         * @return recommended textbox opacity
         */
        public static float recommendBackdropAlpha(float sceneLuminance, float baseAlpha, float maxAlpha) {
            float l = Float.isFinite(sceneLuminance) ? Math.clamp(sceneLuminance, 0.0f, 1.0f) : 0.0f;
            float base = Math.clamp(baseAlpha, 0.0f, 1.0f);
            float max = Math.clamp(maxAlpha, base, 1.0f);
            return base + (max - base) * l;
        }
    }

    /**
     * 3D strip PNG Color Lookup Table ({@code NxN x N} flattened horizontal strip) for filmic grading,
     * preserving identity mapping within {@code <= 1} 8-bit quantization step.
     */
    public static final class ColorLut {

        private final int size;
        private final byte[] lutRgba;

        private ColorLut(int size, byte[] lutRgba) {
            this.size = size;
            this.lutRgba = lutRgba;
        }

        /**
         * Builds an exact neutral identity strip LUT of dimension {@code size^3} (e.g. {@code 16}).
         *
         * @param size cube dimension ({@code >= 2 && <= 64})
         * @return identity {@link ColorLut}
         */
        public static ColorLut createIdentity(int size) {
            int n = Math.clamp(size, 2, 64);
            int w = n * n;
            int h = n;
            byte[] data = new byte[w * h * 4];
            for (int b = 0; b < n; b++) {
                for (int g = 0; g < n; g++) {
                    for (int r = 0; r < n; r++) {
                        int x = b * n + r;
                        int y = g;
                        int off = (y * w + x) * 4;
                        data[off] = (byte) Math.round(r * 255.0f / (n - 1));
                        data[off + 1] = (byte) Math.round(g * 255.0f / (n - 1));
                        data[off + 2] = (byte) Math.round(b * 255.0f / (n - 1));
                        data[off + 3] = (byte) 255;
                    }
                }
            }
            return new ColorLut(n, data);
        }

        /**
         * Looks up transformed straight RGB {@code [0..255]} for an input RGB triple via nearest LUT cell.
         *
         * @param r red {@code [0..255]}
         * @param g green {@code [0..255]}
         * @param b blue {@code [0..255]}
         * @return 3-element array {@code [rOut, gOut, bOut]} in {@code [0..255]}
         */
        public int[] mapRgb(int r, int g, int b) {
            int n = size;
            int w = n * n;
            int ri = Math.clamp(Math.round((r / 255.0f) * (n - 1)), 0, n - 1);
            int gi = Math.clamp(Math.round((g / 255.0f) * (n - 1)), 0, n - 1);
            int bi = Math.clamp(Math.round((b / 255.0f) * (n - 1)), 0, n - 1);
            int x = bi * n + ri;
            int off = (gi * w + x) * 4;
            return new int[]{lutRgba[off] & 0xFF, lutRgba[off + 1] & 0xFF, lutRgba[off + 2] & 0xFF};
        }

        /**
         * Returns cube dimension {@code N} ({@code width = N*N, height = N}).
         *
         * @return LUT cube dimension
         */
        public int getSize() {
            return size;
        }
    }

    /**
     * HDR ACES Filmic tone mapping and 8-bit triangular dithering helper eliminating dark-sky banding.
     */
    public static final class HdrToneMapper {

        private HdrToneMapper() {
        }

        /**
         * Applies ACES Filmic curve preserving highlight hue &amp; saturation for HDR bloom values {@code > 1.0}.
         *
         * @param hdrValue linear HDR channel value ({@code >= 0})
         * @return tone-mapped display value in {@code [0, 1]}
         */
        public static float acesFilmic(float hdrValue) {
            float x = Math.max(0.0f, hdrValue);
            float a = 2.51f;
            float b = 0.03f;
            float c = 2.43f;
            float d = 0.59f;
            float e = 0.14f;
            return Math.clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0f, 1.0f);
        }

        /**
         * Evaluates deterministic triangular PDF dither noise bounded cleanly within {@code +/- 1 / 255.0f} (1 LSB).
         *
         * @param x pixel X coordinate
         * @param y pixel Y coordinate
         * @return signed dither delta bounded in {@code [-1/255, +1/255]}
         */
        public static float triangularDitherLuvLsb(int x, int y) {
            int hash1 = Integer.hashCode((x * 73856093) ^ (y * 19349663));
            int hash2 = Integer.hashCode((x * 83492791) ^ (y * 19349669));
            float u1 = ((hash1 & 0xFF) / 255.0f);
            float u2 = ((hash2 & 0xFF) / 255.0f);
            return (u1 - u2) / 255.0f;
        }
    }

    /**
     * Tracks dynamic attention-focus vignette target coupled to a speaking {@link SceneNode}.
     */
    public static final class FocusVignetteController {
        private float centerX = 0.5f;
        private float centerY = 0.5f;
        private float intensity = 0.0f;

        /** Creates a centered idle {@code FocusVignetteController}. */
        public FocusVignetteController() {
        }

        /**
         * Updates normalized vignette focus center directly from a {@link SceneNode}'s stage position.
         *
         * @param speakerNode target sprite node
         * @param logicalW    logical stage width
         * @param logicalH    logical stage height
         * @param intensity   vignette strength in {@code [0, 1]}
         */
        public void focusOnNode(SceneNode speakerNode, float logicalW, float logicalH, float intensity) {
            Objects.requireNonNull(speakerNode, "speakerNode must not be null");
            Rect2f b = speakerNode.getTransform().resolveBounds(
                    speakerNode.getWidth(), speakerNode.getHeight(), logicalW, logicalH
            );
            this.centerX = Math.clamp((b.x() + b.width() * 0.5f) / Math.max(1.0f, logicalW), 0.0f, 1.0f);
            this.centerY = Math.clamp((b.y() + b.height() * 0.35f) / Math.max(1.0f, logicalH), 0.0f, 1.0f);
            this.intensity = Math.clamp(intensity, 0.0f, 1.0f);
        }

        /**
         * Returns normalized focus X in {@code [0, 1]}.
         *
         * @return focus X
         */
        public float getCenterX() {
            return centerX;
        }

        /**
         * Returns normalized focus Y in {@code [0, 1]}.
         *
         * @return focus Y
         */
        public float getCenterY() {
            return centerY;
        }

        /**
         * Returns active vignette intensity in {@code [0, 1]}.
         *
         * @return intensity
         */
        public float getIntensity() {
            return intensity;
        }
    }

    /**
     * Floor/water planar reflection zone clipping reflected geometry tightly inside {@code surfaceBounds}.
     *
     * @param surfaceBounds   clip rectangle of floor/water region
     * @param opacity         reflection opacity in {@code [0, 1]}
     * @param waveAmplitudePx sinusoidal horizontal wave amplitude in pixels
     */
    public record ReflectionZone(Rect2f surfaceBounds, float opacity, float waveAmplitudePx) {
        /**
         * Computes reflected Y coordinate mirrored vertically across {@code surfaceBounds.y()}, clamping
         * strictly within {@code surfaceBounds}.
         *
         * @param worldY source object Y coordinate
         * @return reflected Y coordinate or {@link Float#NaN} if clipped outside zone
         */
        public float computeReflectedY(float worldY) {
            float ry = surfaceBounds.y() + (surfaceBounds.y() - worldY);
            if (ry < surfaceBounds.y() || ry > surfaceBounds.y() + surfaceBounds.height()) {
                return Float.NaN;
            }
            return ry;
        }
    }

    /**
     * Stores 3–6 decaying ghost motion poses for fast sprite dashes ({@code motion_trail}) that clear cleanly
     * within {@code <= 0.2 s} once the sprite comes to rest.
     */
    public static final class MotionTrailTracker {

        /**
         * Single ghost trail pose.
         *
         * @param x     pose X
         * @param y     pose Y
         * @param alpha decaying alpha in {@code [0, 1]}
         */
        public record TrailSample(float x, float y, float alpha) {
        }

        private final int maxSamples;
        private final float decayDurationSeconds;
        private final Deque<TrailSample> samples = new ArrayDeque<>();
        private float lastX = Float.NaN;
        private float lastY = Float.NaN;

        /**
         * Creates a tracker storing up to {@code maxSamples} (clamped {@code 3..6}) decaying over {@code 0.2 s}.
         *
         * @param maxSamples trail length ({@code 3..6})
         */
        public MotionTrailTracker(int maxSamples) {
            this.maxSamples = Math.clamp(maxSamples, 3, 6);
            this.decayDurationSeconds = 0.20f;
        }

        /**
         * Records current sprite position and decays existing trail poses by {@code dt}.
         *
         * @param x  current sprite X
         * @param y  current sprite Y
         * @param dt frame delta in seconds
         */
        public void update(float x, float y, double dt) {
            float fdt = (Double.isFinite(dt) && dt > 0.0) ? (float) dt : 0.0f;
            float decay = fdt / decayDurationSeconds;
            List<TrailSample> kept = new ArrayList<>();
            for (TrailSample s : samples) {
                float nextA = s.alpha() - decay;
                if (nextA > 0.005f) {
                    kept.add(new TrailSample(s.x(), s.y(), nextA));
                }
            }
            samples.clear();
            samples.addAll(kept);
            boolean moved = Float.isFinite(lastX) && (Math.abs(x - lastX) > 1.0f || Math.abs(y - lastY) > 1.0f);
            if (moved) {
                samples.addFirst(new TrailSample(x, y, 0.65f));
                while (samples.size() > maxSamples) {
                    samples.removeLast();
                }
            }
            lastX = x;
            lastY = y;
        }

        /**
         * Returns current immutable snapshot of active trail ghosts.
         *
         * @return list of {@link TrailSample}s
         */
        public List<TrailSample> getSamples() {
            return List.copyOf(samples);
        }
    }

    /**
     * Multi-stop directional linear/radial gradient badge specification supporting arbitrary angles {@code 0..360°}.
     *
     * @param angleDegrees gradient angle in degrees
     * @param radial       {@code true} for radial falloff, {@code false} for directional linear
     * @param startRgba    start stop color
     * @param endRgba      end stop color
     */
    public record GradientSpec(float angleDegrees, boolean radial, float[] startRgba, float[] endRgba) {
        /**
         * Evaluates gradient color at normalized quad coordinate {@code (u, v) in [0, 1]^2}.
         *
         * @param u normalized X
         * @param v normalized Y
         * @return interpolated 4-element RGBA color
         */
        public float[] sampleColorAt(float u, float v) {
            float t;
            if (radial) {
                t = Math.clamp((float) Math.hypot(u - 0.5f, v - 0.5f) * 2.0f, 0.0f, 1.0f);
            } else {
                double rad = Math.toRadians(angleDegrees);
                float dx = (float) Math.cos(rad);
                float dy = (float) Math.sin(rad);
                t = Math.clamp((u - 0.5f) * dx + (v - 0.5f) * dy + 0.5f, 0.0f, 1.0f);
            }
            float[] out = new float[4];
            for (int i = 0; i < 4; i++) {
                out[i] = startRgba[i] + (endRgba[i] - startRgba[i]) * t;
            }
            return out;
        }
    }

    /**
     * Software mouse cursor / crosshair renderer mapping hotspot offsets precisely to logical cursor coordinates.
     *
     * @param hotspotX horizontal hotspot pixel offset inside sprite
     * @param hotspotY vertical hotspot pixel offset inside sprite
     * @param sizeW    cursor sprite width
     * @param sizeH    cursor sprite height
     */
    public record SoftwareCursorRenderer(float hotspotX, float hotspotY, float sizeW, float sizeH) {
        /**
         * Computes cursor draw quad placing exact hotspot at {@code (cursorX, cursorY)}.
         *
         * @param cursorX logical cursor X from {@code InputManager}
         * @param cursorY logical cursor Y from {@code InputManager}
         * @return quad rectangle aligning hotspot to {@code (cursorX, cursorY)}
         */
        public Rect2f computeDrawRect(float cursorX, float cursorY) {
            return new Rect2f(cursorX - hotspotX, cursorY - hotspotY, sizeW, sizeH);
        }
    }

    /**
     * Circular or custom alpha-mask node descriptor ensuring pixels outside radius remain completely transparent.
     *
     * @param circular        {@code true} for normalized circular portrait mask
     * @param radiusNormalized normalized radius inside {@code [0, 0.5]} (default {@code 0.5f})
     */
    public record MaskConfig(boolean circular, float radiusNormalized) {
        /**
         * Evaluates mask opacity ({@code 0.0f} outside mask, {@code 1.0f} inside) at normalized {@code (u, v)}.
         *
         * @param u normalized X in {@code [0, 1]}
         * @param v normalized Y in {@code [0, 1]}
         * @return alpha mask value ({@code 0.0f} or {@code 1.0f})
         */
        public float sampleMaskAlpha(float u, float v) {
            if (!circular) {
                return 1.0f;
            }
            float dx = u - 0.5f;
            float dy = v - 0.5f;
            return Math.hypot(dx, dy) <= radiusNormalized ? 1.0f : 0.0f;
        }
    }
}
