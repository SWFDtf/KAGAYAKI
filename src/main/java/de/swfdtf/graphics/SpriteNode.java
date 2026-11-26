package de.swfdtf.graphics;

import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Transform2D;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Visual Novel sprite node in the {@link SceneGraph} extending {@link SceneNode} with VN image
 * metadata ({@code imageTag}, {@code attributes}, {@code assetPath}) and support for plain
 * {@link Texture}s, {@link NineSliceDrawable} UI frames, and {@link CustomDisplayable} renderers.
 *
 * <p>Attaching a {@link Texture} or {@link NineSliceDrawable} automatically synchronizes
 * {@link #getTextureId()} and natural {@link #getWidth()}/{@link #getHeight()}. When a
 * {@link NineSliceDrawable} or {@link CustomDisplayable} is attached, standard single-quad drawing
 * is suppressed in favor of {@link #onCustomRender(RenderPipeline2D)}; when only a plain
 * {@link Texture} is set, standard {@link RenderPipeline2D} textured quad rendering (with full
 * crop, mirror, SDF, rotation, and color-matrix support) is used.</p>
 */
public class SpriteNode extends SceneNode {

    private String imageTag;
    private List<String> attributes = List.of();
    private String assetPath;
    private Texture texture;
    private NineSliceDrawable nineSlice;
    private CustomDisplayable customDisplayable;

    /**
     * Creates an empty {@code SpriteNode}.
     */
    public SpriteNode() {
        super();
    }

    /**
     * Creates a {@code SpriteNode} with the given identifier.
     *
     * @param id optional node identifier
     */
    public SpriteNode(String id) {
        super(id);
    }

    /**
     * Creates a {@code SpriteNode} with the given identifier and scene lookup tag.
     *
     * @param id  optional node identifier
     * @param tag optional scene lookup tag
     */
    public SpriteNode(String id, String tag) {
        super(id, tag);
    }

    /**
     * Creates a {@code SpriteNode} initialized with VN image tag and attribute tokens.
     * The scene lookup {@link #getTag()} is initialized to {@code imageTag}.
     *
     * @param id         optional node identifier
     * @param imageTag   VN image tag (e.g. {@code "eileen"} or {@code "bg_school"})
     * @param attributes optional attribute list (e.g. {@code ["happy", "school"]})
     */
    public SpriteNode(String id, String imageTag, List<String> attributes) {
        super(id, imageTag);
        this.imageTag = imageTag;
        setAttributes(attributes);
    }

    /**
     * Returns the VN image tag associated with this sprite.
     *
     * @return VN image tag associated with this sprite, or {@code null}
     */
    public String getImageTag() {
        return imageTag;
    }

    /**
     * Sets the VN image tag for this sprite.
     *
     * @param imageTag VN image tag (may be {@code null})
     * @return {@code this} for fluent chaining
     */
    public SpriteNode setImageTag(String imageTag) {
        this.imageTag = imageTag;
        return this;
    }

    /**
     * Returns an unmodifiable list of VN sprite attribute tokens.
     *
     * @return unmodifiable list of VN sprite attribute tokens
     */
    public List<String> getAttributes() {
        return attributes;
    }

    /**
     * Replaces the VN sprite attribute token list with a defensive unmodifiable copy.
     *
     * @param attributes attribute tokens (null treated as empty; null elements are ignored)
     * @return {@code this} for fluent chaining
     */
    public SpriteNode setAttributes(List<String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            this.attributes = List.of();
            return this;
        }
        List<String> copy = new ArrayList<>(attributes.size());
        for (String attr : attributes) {
            if (attr != null) {
                copy.add(attr);
            }
        }
        this.attributes = copy.isEmpty() ? List.of() : Collections.unmodifiableList(copy);
        return this;
    }

    /**
     * Returns the resolved or declared asset path string.
     *
     * @return resolved or declared asset path string, or {@code null}
     */
    public String getAssetPath() {
        return assetPath;
    }

    /**
     * Sets the diagnostic/logical asset path associated with this sprite.
     *
     * @param assetPath asset path string (may be {@code null})
     * @return {@code this} for fluent chaining
     */
    public SpriteNode setAssetPath(String assetPath) {
        this.assetPath = assetPath;
        return this;
    }

    /**
     * Returns the attached {@link Texture}, if any.
     *
     * @return attached {@link Texture}, or {@code null}
     */
    public Texture getTexture() {
        return texture;
    }

    /**
     * Attaches a {@link Texture} and synchronizes {@link #setTextureId(int)} and natural
     * {@link #setSize(float, float)} from the texture dimensions.
     *
     * <p>Passing {@code null} detaches the texture; if no {@link NineSliceDrawable} is active,
     * {@code textureId} is reset to {@code 0}.</p>
     *
     * @param texture texture to attach (may be {@code null})
     * @return {@code this} for fluent chaining
     */
    public SpriteNode setTexture(Texture texture) {
        this.texture = texture;
        if (texture != null) {
            setTextureId(texture.getTextureId());
            setSize(texture.getWidth(), texture.getHeight());
        } else if (nineSlice != null) {
            setTextureId(nineSlice.getTextureId());
            setSize(nineSlice.getTexWidth(), nineSlice.getTexHeight());
        } else {
            setTextureId(0);
            if (customDisplayable == null) {
                setSize(0.0f, 0.0f);
            }
        }
        return this;
    }

    /**
     * Returns the attached {@link NineSliceDrawable}, if any.
     *
     * @return attached {@link NineSliceDrawable}, or {@code null}
     */
    public NineSliceDrawable getNineSlice() {
        return nineSlice;
    }

    /**
     * Attaches a {@link NineSliceDrawable} and synchronizes {@link #setTextureId(int)} and natural
     * {@link #setSize(float, float)} from the 9-slice source texture dimensions.
     *
     * <p>Call {@link #setSize(float, float)} after setting the nine-slice if a different target
     * panel size is desired.</p>
     *
     * @param nineSlice nine-slice drawable (may be {@code null})
     * @return {@code this} for fluent chaining
     */
    public SpriteNode setNineSlice(NineSliceDrawable nineSlice) {
        this.nineSlice = nineSlice;
        if (nineSlice != null) {
            setTextureId(nineSlice.getTextureId());
            setSize(nineSlice.getTexWidth(), nineSlice.getTexHeight());
        } else if (texture != null) {
            setTextureId(texture.getTextureId());
            setSize(texture.getWidth(), texture.getHeight());
        } else {
            setTextureId(0);
            if (customDisplayable == null) {
                setSize(0.0f, 0.0f);
            }
        }
        return this;
    }

    /**
     * Returns the attached {@link CustomDisplayable}, if any.
     *
     * @return attached {@link CustomDisplayable}, or {@code null}
     */
    public CustomDisplayable getCustomDisplayable() {
        return customDisplayable;
    }

    /**
     * Attaches a {@link CustomDisplayable}. If this node currently has zero dimensions and the
     * displayable reports positive preferred dimensions, they are applied via {@link #setSize(float, float)}.
     *
     * @param customDisplayable custom displayable renderer (may be {@code null})
     * @return {@code this} for fluent chaining
     */
    public SpriteNode setCustomDisplayable(CustomDisplayable customDisplayable) {
        this.customDisplayable = customDisplayable;
        if (customDisplayable != null && getWidth() <= 0.0f && getHeight() <= 0.0f) {
            float prefW = Math.max(0.0f, customDisplayable.getPreferredWidth());
            float prefH = Math.max(0.0f, customDisplayable.getPreferredHeight());
            if (prefW > 0.0f || prefH > 0.0f) {
                setSize(prefW, prefH);
            }
        }
        return this;
    }

    /**
     * Advances the attached {@link CustomDisplayable}, if any, by {@code dt} seconds.
     *
     * @param dt elapsed time in seconds
     */
    public void update(double dt) {
        if (customDisplayable != null) {
            customDisplayable.update(dt);
        }
    }

    @Override
    protected boolean shouldDrawStandardQuad() {
        return nineSlice == null && customDisplayable == null;
    }

    @Override
    protected void onCustomRender(RenderPipeline2D pipeline) {
        if (pipeline == null) {
            return;
        }
        boolean hasPipelineContext = pipeline.getCurrentRenderBounds() != null;
        Transform2D transform = getTransform();
        Rect2f bounds = hasPipelineContext
                ? pipeline.getCurrentRenderBounds()
                : transform.resolveBounds(
                        getWidth(),
                        getHeight(),
                        pipeline.getLogicalWidth(),
                        pipeline.getLogicalHeight()
                );
        float effectiveAlpha = hasPipelineContext
                ? pipeline.getCurrentEffectiveAlpha()
                : Math.clamp(transform.getAlpha(), 0.0f, 1.0f);
        ColorMatrix4f effectiveColorMatrix = hasPipelineContext
                ? pipeline.getCurrentEffectiveColorMatrix()
                : transform.getColorMatrix();
        float rotateDegrees = hasPipelineContext
                ? pipeline.getCurrentRotationDegrees()
                : transform.getRotate();
        float anchorX = hasPipelineContext
                ? pipeline.getCurrentAnchorX()
                : transform.getXanchor();
        float anchorY = hasPipelineContext
                ? pipeline.getCurrentAnchorY()
                : transform.getYanchor();

        if (nineSlice != null && effectiveAlpha > 0.0f && bounds.width() > 0.0f && bounds.height() > 0.0f) {
            float[] straightTint = computeCombinedStraightTint(effectiveAlpha);
            nineSlice.render(
                    pipeline,
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    straightTint,
                    effectiveColorMatrix,
                    rotateDegrees,
                    anchorX,
                    anchorY
            );
        }

        if (customDisplayable != null && effectiveAlpha > 0.0f) {
            customDisplayable.render(pipeline, bounds, effectiveAlpha, effectiveColorMatrix);
        }

        super.onCustomRender(pipeline);
    }

    private float[] computeCombinedStraightTint(float effectiveAlpha) {
        float[] fill = getFillColorRgba();
        float alphaScale = Math.clamp(effectiveAlpha, 0.0f, 1.0f);
        if (isFillColorPremultiplied()) {
            float srcA = Math.clamp(fill[3], 0.0f, 1.0f);
            if (srcA <= 0.0f || alphaScale <= 0.0f) {
                return new float[]{0.0f, 0.0f, 0.0f, 0.0f};
            }
            return new float[]{
                fill[0] / srcA,
                fill[1] / srcA,
                fill[2] / srcA,
                srcA * alphaScale
            };
        }
        return new float[]{
            fill[0],
            fill[1],
            fill[2],
            Math.clamp(fill[3], 0.0f, 1.0f) * alphaScale
        };
    }
}
