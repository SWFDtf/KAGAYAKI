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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

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

            out vec4 fragColor;

            float roundedBoxSdf(vec2 p, vec2 halfSize, float radius) {
                vec2 q = abs(p) - halfSize + vec2(radius);
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
            }

            void main() {
                vec4 baseSample = uUseTexture != 0
                    ? texture(uTexture, vTexCoord) * uColor
                    : uColor;

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

            out vec4 fragColor;

            void main() {
                fragColor = texture(uTexture, vTexCoord);
            }
            """;

    private int logicalWidth = DEFAULT_LOGICAL_WIDTH;
    private int logicalHeight = DEFAULT_LOGICAL_HEIGHT;
    private int currentWindowWidth = 0;
    private int currentWindowHeight = 0;

    private Framebuffer offscreenFbo;
    private ShaderProgram quadShader;
    private ShaderProgram blitShader;
    private int quadVao = 0;
    private int quadVbo = 0;

    private final Matrix4f logicalProjection = new Matrix4f();
    private final Matrix4f windowProjection = new Matrix4f();
    private final Matrix4f modelMatrix = new Matrix4f();

    private Rect2f lastLetterboxViewport = new Rect2f(0.0f, 0.0f, 0.0f, 0.0f);
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

            this.quadShader = new ShaderProgram(QUAD_VERTEX_SHADER, QUAD_FRAGMENT_SHADER);
            this.blitShader = new ShaderProgram(BLIT_VERTEX_SHADER, BLIT_FRAGMENT_SHADER);

            initQuadGeometry();
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

        offscreenFbo.bind();
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);

        GL11.glClearColor(CLEAR_R, CLEAR_G, CLEAR_B, CLEAR_A);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
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
            float layerParallaxX = mouseX * layer.getParallaxFactor() * (logicalWidth * 0.5f);
            float layerParallaxY = mouseY * layer.getParallaxFactor() * (logicalHeight * 0.5f);

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

        if (drawW > 0.0f && drawH > 0.0f && effectiveAlpha > 0.0f) {
            Rect2f effectiveUv = resolveEffectiveUv(node, transform);
            float[] fillPremul = node.getPremultipliedFillColor(effectiveAlpha);
            float[] borderPremul = node.getBorderThickness() > 0.0f
                    ? node.getPremultipliedBorderColor(effectiveAlpha)
                    : NO_BORDER_COLOR;

            drawQuadInternal(
                    node.getTextureId(),
                    drawX,
                    drawY,
                    drawW,
                    drawH,
                    transform.getXanchor(),
                    transform.getYanchor(),
                    transform.getRotate(),
                    effectiveUv,
                    fillPremul,
                    effectiveColorMatrix,
                    node.getCornerRadius(),
                    node.getBorderThickness(),
                    borderPremul
            );
        }

        node.onCustomRender(this);

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
        offscreenFbo.unbind();

        if (currentWindowWidth <= 0 || currentWindowHeight <= 0) {
            lastLetterboxViewport = new Rect2f(0.0f, 0.0f, 0.0f, 0.0f);
            GL30.glBindVertexArray(0);
            GL20.glUseProgram(0);
            return;
        }

        GL11.glViewport(0, 0, currentWindowWidth, currentWindowHeight);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glClearColor(CLEAR_R, CLEAR_G, CLEAR_B, CLEAR_A);
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

        blitShader.bind();
        blitShader.setUniformMat4("uProjection", windowProjection);
        blitShader.setUniformMat4("uModel", modelMatrix);
        blitShader.setUniformInt("uTexture", 0);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, offscreenFbo.getTextureId());

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
        drawQuadInternal(
                textureId,
                x,
                y,
                width,
                height,
                0.0f,
                0.0f,
                0.0f,
                uvRect != null ? uvRect : FULL_UV_RECT,
                premulTint,
                colorMatrix != null ? colorMatrix : ColorMatrix4f.identity(),
                0.0f,
                0.0f,
                NO_BORDER_COLOR
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
        float anchorPxX = xanchor * width;
        float anchorPxY = yanchor * height;

        modelMatrix.identity()
                .translate(x + anchorPxX, y + anchorPxY, 0.0f);
        if (rotateDeg != 0.0f) {
            modelMatrix.rotateZ((float) Math.toRadians(rotateDeg));
        }
        modelMatrix.translate(-anchorPxX, -anchorPxY, 0.0f)
                .scale(width, height, 1.0f);

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

    private static Rect2f resolveEffectiveUv(SceneNode node, Transform2D transform) {
        Rect2f uv = node.getUvRect();
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
        if (quadShader != null) {
            quadShader.dispose();
            quadShader = null;
        }
        if (blitShader != null) {
            blitShader.dispose();
            blitShader = null;
        }
        if (quadVao != 0) {
            GL30.glDeleteVertexArrays(quadVao);
            quadVao = 0;
        }
        if (quadVbo != 0) {
            GL15.glDeleteBuffers(quadVbo);
            quadVbo = 0;
        }
        if (offscreenFbo != null) {
            offscreenFbo.dispose();
            offscreenFbo = null;
        }
        initialized = false;
    }

    /** @return most recently computed letterbox/pillarbox destination rectangle from {@link #endFrame()} */
    public Rect2f getLastLetterboxViewport() {
        return lastLetterboxViewport;
    }

    /** @return offscreen logical-resolution {@link Framebuffer}, or {@code null} if uninitialized */
    public Framebuffer getOffscreenFramebuffer() {
        return offscreenFbo;
    }

    /** @return configured logical canvas width in pixels */
    public int getLogicalWidth() {
        return logicalWidth;
    }

    /** @return configured logical canvas height in pixels */
    public int getLogicalHeight() {
        return logicalHeight;
    }

    /** @return {@code true} if GPU resources have been initialized */
    public boolean isInitialized() {
        return initialized;
    }

    private void requireInitialized() {
        if (!initialized) {
            throw new IllegalStateException("RenderPipeline2D has not been initialized or was disposed");
        }
    }
}
