package de.swfdtf.graphics;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 2D point-lighting and optional normal-map ({@code *_n.png}) shading system for atmospheric night,
 * lantern, and fireplace interior scenes.
 */
public final class Lighting2DSystem {

    /**
     * Immutable 2D point light descriptor with optional sinusoidal flicker modulation.
     *
     * @param id               unique light identifier
     * @param x                light X coordinate in logical canvas space
     * @param y                light Y coordinate in logical canvas space
     * @param radius           attenuation radius in pixels ({@code > 0})
     * @param intensity        nominal brightness multiplier ({@code >= 0})
     * @param colorRgb         straight RGB tint {@code [r, g, b]}
     * @param flickerAmplitude normalized flicker amplitude in {@code [0, 1]}
     * @param flickerHz        flicker frequency in Hz ({@code >= 0})
     */
    public record PointLight2D(
            String id,
            float x,
            float y,
            float radius,
            float intensity,
            float[] colorRgb,
            float flickerAmplitude,
            float flickerHz
    ) {
        /** Validates parameters and clones color array. */
        public PointLight2D {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(colorRgb, "colorRgb must not be null");
            if (colorRgb.length != 3) {
                throw new IllegalArgumentException("Expected 3 RGB components");
            }
            colorRgb = colorRgb.clone();
            radius = Math.max(1.0f, radius);
            intensity = Math.max(0.0f, intensity);
            flickerAmplitude = Math.clamp(flickerAmplitude, 0.0f, 1.0f);
            flickerHz = Math.max(0.0f, flickerHz);
        }

        /**
         * Returns a defensive copy of the RGB tint.
         *
         * @return 3-element RGB array
         */
        @Override
        public float[] colorRgb() {
            return colorRgb.clone();
        }
    }

    /** GLSL 3.30 Core fragment shader supporting up to 8 dynamic 2D point lights + normal maps. */
    public static final String LIGHTING_2D_FRAGMENT_SHADER = """
            #version 330 core
            in vec2 vTexCoord;

            uniform sampler2D uAlbedoTex;
            uniform sampler2D uNormalTex;
            uniform int uHasNormalMap;
            uniform vec3 uAmbientColor;
            uniform int uLightCount;
            uniform vec4 uLightPosRadius[8];
            uniform vec4 uLightColorIntensity[8];
            uniform vec2 uCanvasSize;

            out vec4 fragColor;

            void main() {
                vec4 albedo = texture(uAlbedoTex, vTexCoord);
                vec3 normal = uHasNormalMap != 0
                    ? normalize(texture(uNormalTex, vTexCoord).xyz * 2.0 - 1.0)
                    : vec3(0.0, 0.0, 1.0);
                vec2 fragPx = vTexCoord * uCanvasSize;
                vec3 accum = uAmbientColor;
                for (int i = 0; i < min(uLightCount, 8); i++) {
                    vec2 lightPos = uLightPosRadius[i].xy;
                    float radius = max(uLightPosRadius[i].z, 1.0);
                    vec3 lightDir = vec3((lightPos - fragPx) / radius, 0.45);
                    float distRatio = clamp(length(lightPos - fragPx) / radius, 0.0, 1.0);
                    float att = (1.0 - distRatio) * (1.0 - distRatio);
                    float ndl = max(dot(normal, normalize(lightDir)), 0.0);
                    accum += uLightColorIntensity[i].rgb * (uLightColorIntensity[i].w * att * ndl);
                }
                fragColor = vec4(albedo.rgb * accum, albedo.a);
            }
            """;

    private final List<PointLight2D> lights = new ArrayList<>();
    private final float[] ambientRgb = new float[]{0.28f, 0.30f, 0.42f};
    private double timeSeconds = 0.0;

    /**
     * Creates a {@code Lighting2DSystem} with soft moonlit interior ambient light.
     */
    public Lighting2DSystem() {
    }

    /**
     * Resolves the conventional normal-map path ({@code foo.png -> foo_n.png}) alongside a base sprite.
     *
     * @param baseAssetPath base image path
     * @return normal-map sibling path
     */
    public static Path resolveNormalMapPath(Path baseAssetPath) {
        Objects.requireNonNull(baseAssetPath, "baseAssetPath must not be null");
        String fileName = baseAssetPath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String normalName = dot >= 0
                ? fileName.substring(0, dot) + "_n" + fileName.substring(dot)
                : fileName + "_n.png";
        return baseAssetPath.resolveSibling(normalName);
    }

    /**
     * Adds or replaces a {@link PointLight2D} by its {@link PointLight2D#id()}.
     *
     * @param light point light descriptor
     */
    public void putLight(PointLight2D light) {
        Objects.requireNonNull(light, "light must not be null");
        lights.removeIf(existing -> existing.id().equals(light.id()));
        lights.add(light);
    }

    /**
     * Removes a light by ID.
     *
     * @param id light identifier
     * @return {@code true} if removed
     */
    public boolean removeLight(String id) {
        return id != null && lights.removeIf(l -> l.id().equals(id));
    }

    /**
     * Removes all registered lights.
     */
    public void clearLights() {
        lights.clear();
    }

    /**
     * Configures the ambient base illumination RGB multiplier.
     *
     * @param r ambient red ({@code >= 0})
     * @param g ambient green ({@code >= 0})
     * @param b ambient blue ({@code >= 0})
     */
    public void setAmbientColor(float r, float g, float b) {
        ambientRgb[0] = Math.max(0.0f, r);
        ambientRgb[1] = Math.max(0.0f, g);
        ambientRgb[2] = Math.max(0.0f, b);
    }

    /**
     * Returns an immutable snapshot of active lights.
     *
     * @return unmodifiable list of {@link PointLight2D}
     */
    public List<PointLight2D> getLights() {
        return Collections.unmodifiableList(lights);
    }

    /**
     * Advances internal flicker clock by {@code dt} seconds.
     *
     * @param dt frame delta in seconds
     */
    public void update(double dt) {
        if (Double.isFinite(dt) && dt > 0.0) {
            timeSeconds += dt;
        }
    }

    /**
     * Evaluates instantaneous effective intensity of a light at the current flicker clock.
     *
     * @param light point light
     * @return modulated intensity ({@code >= 0})
     */
    public float evaluateFlickerIntensity(PointLight2D light) {
        Objects.requireNonNull(light, "light must not be null");
        if (light.flickerAmplitude() <= 0.0f || light.flickerHz() <= 0.0f) {
            return light.intensity();
        }
        double wave = Math.sin(timeSeconds * Math.PI * 2.0 * light.flickerHz());
        float mod = 1.0f + (float) wave * light.flickerAmplitude();
        return Math.max(0.0f, light.intensity() * mod);
    }

    /**
     * Evaluates final lit RGB radiance {@code [r, g, b]} at a surface coordinate and unit normal.
     *
     * @param surfaceX surface X in logical pixels
     * @param surfaceY surface Y in logical pixels
     * @param normalX  normal X in {@code [-1, 1]}
     * @param normalY  normal Y in {@code [-1, 1]}
     * @param normalZ  normal Z in {@code [0, 1]}
     * @return accumulated RGB radiance {@code [r, g, b]}
     */
    public float[] evaluateIrradianceAt(
            float surfaceX,
            float surfaceY,
            float normalX,
            float normalY,
            float normalZ
    ) {
        float[] out = new float[]{ambientRgb[0], ambientRgb[1], ambientRgb[2]};
        float nLen = (float) Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
        float nx = nLen > 1e-5f ? normalX / nLen : 0.0f;
        float ny = nLen > 1e-5f ? normalY / nLen : 0.0f;
        float nz = nLen > 1e-5f ? normalZ / nLen : 1.0f;

        for (PointLight2D light : lights) {
            float dx = light.x() - surfaceX;
            float dy = light.y() - surfaceY;
            float dist = (float) Math.hypot(dx, dy);
            if (dist >= light.radius()) {
                continue;
            }
            float falloff = 1.0f - (dist / light.radius());
            float attenuation = falloff * falloff;
            float ldx = dx / light.radius();
            float ldy = dy / light.radius();
            float ldz = 0.45f;
            float lLen = (float) Math.sqrt(ldx * ldx + ldy * ldy + ldz * ldz);
            float ndl = Math.max(0.0f, (nx * ldx + ny * ldy + nz * ldz) / lLen);
            float power = evaluateFlickerIntensity(light) * attenuation * ndl;
            float[] col = light.colorRgb();
            out[0] += col[0] * power;
            out[1] += col[1] * power;
            out[2] += col[2] * power;
        }
        return out;
    }
}
