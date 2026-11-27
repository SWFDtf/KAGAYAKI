package de.swfdtf.graphics;

import java.util.Objects;

/**
 * 3D surface material descriptor supporting Cook-Torrance Metallic-Roughness PBR shading,
 * Anime/Toon Cel-shading with stepped light bands, Fresnel transparent water surfaces,
 * alpha-discard cutouts, HDR emissive glow ({@code > 1.0f}), and shared {@link TextureManager} lookup keys.
 */
public final class Material3D {

    /**
     * Rendering shading model for 3D materials.
     */
    public enum ShaderStyle {
        /** Cook-Torrance metallic-roughness PBR shader. */
        PBR,
        /** Quantized anime cel-shader paired with inverted-hull outline pass. */
        TOON,
        /** Fresnel-reflective animated translucent water surface. */
        WATER,
        /** Alpha-tested foliage / lattice cutout material ({@code discard} when {@code a < alphaCutoff}). */
        CUTOUT,
        /** Projected surface decal (footprints, runes) with polygon offset. */
        DECAL
    }

    private final String name;
    private final ShaderStyle style;
    private final float[] albedoRgba;
    private final float metallic;
    private final float roughness;
    private final float emissiveFactor;
    private final int toonBands;
    private final float alphaCutoff;
    private final boolean transparent;
    private final String albedoTexturePath;
    private final String normalTexturePath;

    /**
     * Constructs an immutable {@code Material3D}.
     *
     * @param name              material name
     * @param style             shading model
     * @param albedoRgba        4-element RGBA color
     * @param metallic          metallic factor {@code [0, 1]}
     * @param roughness         roughness factor {@code [0, 1]} ({@code 0}=mirror smooth, {@code 1}=diffuse)
     * @param emissiveFactor    emissive glow strength ({@code > 1.0f} triggers bloom)
     * @param toonBands         quantized cel light step count ({@code 2..3})
     * @param alphaCutoff       cutout discard threshold {@code [0, 1]}
     * @param transparent       whether this material requires back-to-front alpha sorting
     * @param albedoTexturePath shared asset path resolved via {@link TextureManager}
     * @param normalTexturePath optional normal map asset path
     */
    public Material3D(
            String name,
            ShaderStyle style,
            float[] albedoRgba,
            float metallic,
            float roughness,
            float emissiveFactor,
            int toonBands,
            float alphaCutoff,
            boolean transparent,
            String albedoTexturePath,
            String normalTexturePath
    ) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.style = Objects.requireNonNull(style, "style must not be null");
        Objects.requireNonNull(albedoRgba, "albedoRgba must not be null");
        if (albedoRgba.length != 4) {
            throw new IllegalArgumentException("Expected 4 RGBA elements");
        }
        this.albedoRgba = albedoRgba.clone();
        this.metallic = Math.clamp(metallic, 0.0f, 1.0f);
        this.roughness = Math.clamp(roughness, 0.0f, 1.0f);
        this.emissiveFactor = Math.max(0.0f, emissiveFactor);
        this.toonBands = Math.clamp(toonBands, 2, 3);
        this.alphaCutoff = Math.clamp(alphaCutoff, 0.0f, 1.0f);
        this.transparent = transparent || this.albedoRgba[3] < 0.999f || style == ShaderStyle.WATER;
        this.albedoTexturePath = albedoTexturePath;
        this.normalTexturePath = normalTexturePath;
    }

    /**
     * Creates a default matte PBR material with a hex color tint.
     *
     * @param name    material name
     * @param hexColor hex albedo tint
     * @return default PBR material
     */
    public static Material3D defaultPbr(String name, String hexColor) {
        return new Material3D(
                name,
                ShaderStyle.PBR,
                SceneNode.parseHexRgba(hexColor != null ? hexColor : "#ffffff"),
                0.0f,
                0.65f,
                0.0f,
                2,
                0.5f,
                false,
                null,
                null
        );
    }

    /**
     * Produces a variant copy overriding albedo tint and/or roughness without duplicating mesh geometry.
     *
     * @param overrideHexColor optional hex tint override (nullable)
     * @param newRoughness     optional new roughness (negative retains existing)
     * @param newMetallic      optional new metallic (negative retains existing)
     * @return derived {@link Material3D}
     */
    public Material3D withOverrides(String overrideHexColor, float newRoughness, float newMetallic) {
        float[] rgba = overrideHexColor != null ? SceneNode.parseHexRgba(overrideHexColor) : this.albedoRgba;
        float r = newRoughness >= 0.0f ? newRoughness : this.roughness;
        float m = newMetallic >= 0.0f ? newMetallic : this.metallic;
        return new Material3D(
                this.name + "_override",
                this.style,
                rgba,
                m,
                r,
                this.emissiveFactor,
                this.toonBands,
                this.alphaCutoff,
                this.transparent,
                this.albedoTexturePath,
                this.normalTexturePath
        );
    }

    /**
     * Evaluates Cook-Torrance specular GGX lobe intensity at perfect half-vector alignment ({@code N.H}),
     * verifying sharp pinpoint specular highlights at roughness {@code 0.0} vs broad diffuse response at {@code 1.0}.
     *
     * @param nDotH     half-vector cosine in {@code [0, 1]}
     * @param roughness surface roughness in {@code [0, 1]}
     * @return specular NDF response ({@code >= 0})
     */
    public static float evaluateCookTorranceGgxNdf(float nDotH, float roughness) {
        float alpha = Math.max(0.02f, roughness * roughness);
        float a2 = alpha * alpha;
        float cos = Math.clamp(nDotH, 0.0f, 1.0f);
        float denom = (cos * cos) * (a2 - 1.0f) + 1.0f;
        return a2 / (float) (Math.PI * denom * denom);
    }

    /**
     * Evaluates quantized anime cel-shading intensity in discrete steps across {@code [2, 3]} bands.
     *
     * @param nDotL diffuse cosine in {@code [0, 1]}
     * @param bands step count ({@code 2} or {@code 3})
     * @return quantized cel intensity in {@code [0.15, 1.0]}
     */
    public static float evaluateToonQuantization(float nDotL, int bands) {
        int b = Math.clamp(bands, 2, 3);
        float d = Math.clamp(nDotL, 0.0f, 1.0f);
        float q = (float) Math.floor(d * b) / Math.max(1, b - 1);
        return Math.clamp(0.15f + 0.85f * q, 0.15f, 1.0f);
    }

    /**
     * Evaluates Schlick Fresnel reflectance factor at viewing angle cosine {@code vDotN},
     * driving shimmering water surface reflection weight.
     *
     * @param vDotN view cosine {@code [0, 1]}
     * @param f0    base normal-incidence reflectance (e.g. {@code 0.02f} for water)
     * @return Fresnel reflectance weight in {@code [f0, 1]}
     */
    public static float evaluateFresnelSchlick(float vDotN, float f0) {
        float cos = Math.clamp(vDotN, 0.0f, 1.0f);
        return f0 + (1.0f - f0) * (float) Math.pow(1.0f - cos, 5.0);
    }

    /**
     * Resolves any shared albedo texture via {@link TextureManager} so 2D sprites and 3D materials
     * deduplicate to the exact same OpenGL texture ID.
     *
     * @param textureManager texture cache manager
     * @return resolved {@link Texture}, or {@code null} when no texture path is configured
     */
    public Texture resolveSharedAlbedoTexture(TextureManager textureManager) {
        if (textureManager == null || albedoTexturePath == null || albedoTexturePath.isBlank()) {
            return null;
        }
        return textureManager.loadTexture(albedoTexturePath);
    }

    /**
     * Returns material name.
     *
     * @return name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns shading style.
     *
     * @return {@link ShaderStyle}
     */
    public ShaderStyle getStyle() {
        return style;
    }

    /**
     * Returns defensive RGBA copy.
     *
     * @return 4-element RGBA
     */
    public float[] getAlbedoRgba() {
        return albedoRgba.clone();
    }

    /**
     * Returns metallic factor.
     *
     * @return metallic
     */
    public float getMetallic() {
        return metallic;
    }

    /**
     * Returns roughness factor.
     *
     * @return roughness
     */
    public float getRoughness() {
        return roughness;
    }

    /**
     * Returns emissive multiplier ({@code > 1} triggers bloom).
     *
     * @return emissive factor
     */
    public float getEmissiveFactor() {
        return emissiveFactor;
    }

    /**
     * Returns cel-shader step band count.
     *
     * @return toon bands
     */
    public int getToonBands() {
        return toonBands;
    }

    /**
     * Returns alpha cutout discard threshold.
     *
     * @return alpha cutoff
     */
    public float getAlphaCutoff() {
        return alphaCutoff;
    }

    /**
     * Returns whether back-to-front alpha sorting is required.
     *
     * @return {@code true} if transparent
     */
    public boolean isTransparent() {
        return transparent;
    }

    /**
     * Returns albedo texture asset path.
     *
     * @return path or {@code null}
     */
    public String getAlbedoTexturePath() {
        return albedoTexturePath;
    }

    /**
     * Returns normal map asset path.
     *
     * @return path or {@code null}
     */
    public String getNormalTexturePath() {
        return normalTexturePath;
    }
}
