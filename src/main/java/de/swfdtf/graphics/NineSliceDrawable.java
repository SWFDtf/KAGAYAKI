package de.swfdtf.graphics;

import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Rect2f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 9-slice scalable panel/dialogue frame drawable splitting a source texture into 9 non-overlapping
 * regions (4 fixed corners, 4 single-axis stretched edges, and 1 center fill).
 *
 * <p>When the target dimensions are smaller than the sum of opposing border insets
 * ({@code width < left + right} or {@code height < top + bottom}), corner sizes are clamped
 * proportionally so slices never overlap or invert.</p>
 */
public final class NineSliceDrawable {

    /**
     * Immutable slice pair mapping a destination rectangle in logical canvas pixels to its
     * normalized texture UV sub-rectangle.
     *
     * @param destRect destination rectangle in logical pixels
     * @param uvRect   normalized texture UV rectangle in {@code [0, 1]}
     */
    public record SliceQuad(Rect2f destRect, Rect2f uvRect) {

        /**
         * Validates that both rectangles are non-null.
         */
        public SliceQuad {
            Objects.requireNonNull(destRect, "destRect must not be null");
            Objects.requireNonNull(uvRect, "uvRect must not be null");
        }
    }

    private final Texture texture;
    private final int textureId;
    private final float texWidth;
    private final float texHeight;
    private final float left;
    private final float top;
    private final float right;
    private final float bottom;

    /**
     * Creates a {@code NineSliceDrawable} with uniform border inset on all four sides.
     *
     * @param texture source texture (must not be {@code null})
     * @param inset   border inset in source texels ({@code >= 0})
     */
    public NineSliceDrawable(Texture texture, float inset) {
        this(texture, inset, inset, inset, inset);
    }

    /**
     * Creates a {@code NineSliceDrawable} backed by a {@link Texture}.
     *
     * @param texture source texture (must not be {@code null})
     * @param left    left border width in source texels ({@code >= 0})
     * @param top     top border height in source texels ({@code >= 0})
     * @param right   right border width in source texels ({@code >= 0})
     * @param bottom  bottom border height in source texels ({@code >= 0})
     */
    public NineSliceDrawable(Texture texture, float left, float top, float right, float bottom) {
        Objects.requireNonNull(texture, "texture must not be null");
        validateParams(texture.getTextureId(), texture.getWidth(), texture.getHeight(), left, top, right, bottom);
        this.texture = texture;
        this.textureId = texture.getTextureId();
        this.texWidth = texture.getWidth();
        this.texHeight = texture.getHeight();
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    /**
     * Creates a {@code NineSliceDrawable} from a raw OpenGL texture handle and texel dimensions.
     *
     * @param textureId OpenGL texture ID ({@code >= 0})
     * @param texWidth  source texture width in texels ({@code > 0})
     * @param texHeight source texture height in texels ({@code > 0})
     * @param left      left border width in source texels ({@code >= 0})
     * @param top       top border height in source texels ({@code >= 0})
     * @param right     right border width in source texels ({@code >= 0})
     * @param bottom    bottom border height in source texels ({@code >= 0})
     */
    public NineSliceDrawable(
            int textureId,
            float texWidth,
            float texHeight,
            float left,
            float top,
            float right,
            float bottom
    ) {
        validateParams(textureId, texWidth, texHeight, left, top, right, bottom);
        this.texture = null;
        this.textureId = textureId;
        this.texWidth = texWidth;
        this.texHeight = texHeight;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    private static void validateParams(
            int textureId,
            float texWidth,
            float texHeight,
            float left,
            float top,
            float right,
            float bottom
    ) {
        if (textureId < 0) {
            throw new IllegalArgumentException("textureId must be non-negative, got " + textureId);
        }
        if (!Float.isFinite(texWidth) || texWidth <= 0.0f
                || !Float.isFinite(texHeight) || texHeight <= 0.0f) {
            throw new IllegalArgumentException(
                    "Texture dimensions must be positive and finite, got " + texWidth + "x" + texHeight
            );
        }
        if (!isNonNegativeFinite(left) || !isNonNegativeFinite(top)
                || !isNonNegativeFinite(right) || !isNonNegativeFinite(bottom)) {
            throw new IllegalArgumentException(
                    "Border insets must be finite and non-negative, got ("
                            + left + ", " + top + ", " + right + ", " + bottom + ")"
            );
        }
        if (left + right > texWidth) {
            throw new IllegalArgumentException(
                    "Horizontal insets (left + right = " + (left + right)
                            + ") exceed texture width (" + texWidth + ")"
            );
        }
        if (top + bottom > texHeight) {
            throw new IllegalArgumentException(
                    "Vertical insets (top + bottom = " + (top + bottom)
                            + ") exceed texture height (" + texHeight + ")"
            );
        }
    }

    private static boolean isNonNegativeFinite(float value) {
        return Float.isFinite(value) && value >= 0.0f;
    }

    /**
     * Computes the 9 row-major {@link SliceQuad}s covering {@code [x, x + width] x [y, y + height]}.
     *
     * <p>Returns an empty list if {@code width <= 0} or {@code height <= 0}. When
     * {@code width < left + right} or {@code height < top + bottom}, corner sizes are scaled
     * proportionally so all 9 quads remain non-overlapping and sum exactly to {@code width} and
     * {@code height}.</p>
     *
     * @param x      destination left X coordinate
     * @param y      destination top Y coordinate
     * @param width  destination width
     * @param height destination height
     * @return immutable list of 9 row-major {@link SliceQuad}s (or empty list for non-positive dimensions)
     */
    public List<SliceQuad> computeSlices(float x, float y, float width, float height) {
        if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0.0f || height <= 0.0f) {
            return List.of();
        }

        float w0;
        float w1;
        float w2;
        if (left + right <= width) {
            w0 = left;
            w2 = right;
            w1 = Math.max(0.0f, width - w0 - w2);
        } else {
            float scaleX = width / (left + right);
            w0 = left * scaleX;
            w1 = 0.0f;
            w2 = Math.max(0.0f, width - w0);
        }

        float h0;
        float h1;
        float h2;
        if (top + bottom <= height) {
            h0 = top;
            h2 = bottom;
            h1 = Math.max(0.0f, height - h0 - h2);
        } else {
            float scaleY = height / (top + bottom);
            h0 = top * scaleY;
            h1 = 0.0f;
            h2 = Math.max(0.0f, height - h0);
        }

        float u1 = left / texWidth;
        float u2 = (texWidth - right) / texWidth;
        float uw0 = u1;
        float uw1 = Math.max(0.0f, u2 - u1);
        float uw2 = 1.0f - u2;

        float v1 = top / texHeight;
        float v2 = (texHeight - bottom) / texHeight;
        float vh0 = v1;
        float vh1 = Math.max(0.0f, v2 - v1);
        float vh2 = 1.0f - v2;

        float[] xs = {x, x + w0, x + w0 + w1};
        float[] ws = {w0, w1, w2};
        float[] ys = {y, y + h0, y + h0 + h1};
        float[] hs = {h0, h1, h2};

        float[] us = {0.0f, u1, u2};
        float[] uws = {uw0, uw1, uw2};
        float[] vs = {0.0f, v1, v2};
        float[] vhs = {vh0, vh1, vh2};

        List<SliceQuad> slices = new ArrayList<>(9);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                slices.add(new SliceQuad(
                        new Rect2f(xs[col], ys[row], ws[col], hs[row]),
                        new Rect2f(us[col], vs[row], uws[col], vhs[row])
                ));
            }
        }
        return Collections.unmodifiableList(slices);
    }

    /**
     * Renders this 9-slice panel without tint or color transformation.
     *
     * @param pipeline active 2D render pipeline (must not be {@code null})
     * @param x        destination left X coordinate
     * @param y        destination top Y coordinate
     * @param width    destination width
     * @param height   destination height
     */
    public void render(RenderPipeline2D pipeline, float x, float y, float width, float height) {
        render(pipeline, x, y, width, height, null, null);
    }

    /**
     * Renders this 9-slice panel into {@code pipeline} by issuing {@code drawTexturedQuad} for each
     * non-empty slice.
     *
     * @param pipeline    active 2D render pipeline (must not be {@code null})
     * @param x           destination left X coordinate
     * @param y           destination top Y coordinate
     * @param width       destination width
     * @param height      destination height
     * @param tintRgba    4-element straight RGBA tint color (defaults to opaque white if {@code null})
     * @param colorMatrix 4x4 color matrix (defaults to identity if {@code null})
     */
    public void render(
            RenderPipeline2D pipeline,
            float x,
            float y,
            float width,
            float height,
            float[] tintRgba,
            ColorMatrix4f colorMatrix
    ) {
        Objects.requireNonNull(pipeline, "pipeline must not be null");
        int activeTexId = getTextureId();
        if (activeTexId == 0 || width <= 0.0f || height <= 0.0f) {
            return;
        }
        if (tintRgba != null) {
            if (tintRgba.length != 4) {
                throw new IllegalArgumentException("Expected 4 RGBA components for tintRgba");
            }
            if (tintRgba[3] <= 0.0f) {
                return;
            }
        }
        float[] effectiveTint = tintRgba != null ? tintRgba : new float[]{1.0f, 1.0f, 1.0f, 1.0f};
        ColorMatrix4f effectiveMatrix = colorMatrix != null ? colorMatrix : ColorMatrix4f.identity();

        for (SliceQuad slice : computeSlices(x, y, width, height)) {
            Rect2f dest = slice.destRect();
            if (dest.width() > 0.0f && dest.height() > 0.0f) {
                pipeline.drawTexturedQuad(
                        activeTexId,
                        dest.x(),
                        dest.y(),
                        dest.width(),
                        dest.height(),
                        slice.uvRect(),
                        effectiveTint,
                        effectiveMatrix
                );
            }
        }
    }

    /**
     * Renders this 9-slice panel rotated around a normalized panel anchor {@code (anchorX, anchorY)}.
     *
     * @param pipeline      active 2D render pipeline (must not be {@code null})
     * @param x             destination left X coordinate
     * @param y             destination top Y coordinate
     * @param width         destination width
     * @param height        destination height
     * @param tintRgba      4-element straight RGBA tint color (defaults to opaque white if {@code null})
     * @param colorMatrix   4x4 color matrix (defaults to identity if {@code null})
     * @param rotateDegrees clockwise rotation angle in degrees
     * @param anchorX       normalized horizontal rotation anchor ({@code 0}=left, {@code 0.5}=center, {@code 1}=right)
     * @param anchorY       normalized vertical rotation anchor ({@code 0}=top, {@code 0.5}=center, {@code 1}=bottom)
     */
    public void render(
            RenderPipeline2D pipeline,
            float x,
            float y,
            float width,
            float height,
            float[] tintRgba,
            ColorMatrix4f colorMatrix,
            float rotateDegrees,
            float anchorX,
            float anchorY
    ) {
        if (rotateDegrees == 0.0f) {
            render(pipeline, x, y, width, height, tintRgba, colorMatrix);
            return;
        }
        Objects.requireNonNull(pipeline, "pipeline must not be null");
        int activeTexId = getTextureId();
        if (activeTexId == 0 || width <= 0.0f || height <= 0.0f) {
            return;
        }
        if (tintRgba != null) {
            if (tintRgba.length != 4) {
                throw new IllegalArgumentException("Expected 4 RGBA components for tintRgba");
            }
            if (tintRgba[3] <= 0.0f) {
                return;
            }
        }
        float[] effectiveTint = tintRgba != null ? tintRgba : new float[]{1.0f, 1.0f, 1.0f, 1.0f};
        ColorMatrix4f effectiveMatrix = colorMatrix != null ? colorMatrix : ColorMatrix4f.identity();
        float pivotX = x + anchorX * width;
        float pivotY = y + anchorY * height;

        for (SliceQuad slice : computeSlices(x, y, width, height)) {
            Rect2f dest = slice.destRect();
            if (dest.width() > 0.0f && dest.height() > 0.0f) {
                pipeline.drawTexturedQuadPivot(
                        activeTexId,
                        dest.x(),
                        dest.y(),
                        dest.width(),
                        dest.height(),
                        slice.uvRect(),
                        effectiveTint,
                        effectiveMatrix,
                        rotateDegrees,
                        pivotX,
                        pivotY
                );
            }
        }
    }

    /**
     * Returns the backing {@link Texture}.
     *
     * @return backing {@link Texture}, or {@code null} if constructed from a raw OpenGL texture ID
     */
    public Texture getTexture() {
        return texture;
    }

    /**
     * Returns the active OpenGL texture ID.
     *
     * @return active OpenGL texture ID
     */
    public int getTextureId() {
        return texture != null ? texture.getTextureId() : textureId;
    }

    /**
     * Returns the source texture width in texels.
     *
     * @return source texture width in texels
     */
    public float getTexWidth() {
        return texWidth;
    }

    /**
     * Returns the source texture height in texels.
     *
     * @return source texture height in texels
     */
    public float getTexHeight() {
        return texHeight;
    }

    /**
     * Returns the left border inset in texels.
     *
     * @return left border inset in texels
     */
    public float getLeft() {
        return left;
    }

    /**
     * Returns the top border inset in texels.
     *
     * @return top border inset in texels
     */
    public float getTop() {
        return top;
    }

    /**
     * Returns the right border inset in texels.
     *
     * @return right border inset in texels
     */
    public float getRight() {
        return right;
    }

    /**
     * Returns the bottom border inset in texels.
     *
     * @return bottom border inset in texels
     */
    public float getBottom() {
        return bottom;
    }
}
