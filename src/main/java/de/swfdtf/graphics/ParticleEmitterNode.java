package de.swfdtf.graphics;

import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Rect2f;

import java.util.Objects;

/**
 * Scene-graph emitter node wrapping a {@link ParticleSystem} for built-in atmospheric presets
 * (snow, rain, fireflies, sakura blossoms, sparks) with smooth fade intensity and dialogue-box
 * collision support.
 */
public class ParticleEmitterNode extends SpriteNode {

    /**
     * Built-in atmospheric emitter effect presets.
     */
    public enum EffectKind {
        /** Drifting snow crystals. */
        SNOW,
        /** Slanted rain streaks. */
        RAIN,
        /** Glowing ambient fireflies / motes. */
        FIREFLIES,
        /** Drifting cherry blossom petals. */
        SAKURA,
        /** Rising warm embers / sparks. */
        SPARKS
    }

    private final ParticleSystem particleSystem;
    private EffectKind effectKind;
    private float intensity = 1.0f;

    /**
     * Creates a {@code ParticleEmitterNode} over the default {@code 1920x1080} stage.
     *
     * @param id         node identifier (nullable)
     * @param effectKind atmospheric effect preset (non-null)
     * @param count      target particle count ({@code >= 1})
     */
    public ParticleEmitterNode(String id, EffectKind effectKind, int count) {
        this(id, effectKind, count, 1920.0f, 1080.0f);
    }

    /**
     * Creates a {@code ParticleEmitterNode} with explicit emitter canvas bounds.
     *
     * @param id         node identifier (nullable)
     * @param effectKind atmospheric effect preset (non-null)
     * @param count      target particle count ({@code >= 1})
     * @param width      emitter width in logical pixels ({@code > 0})
     * @param height     emitter height in logical pixels ({@code > 0})
     */
    public ParticleEmitterNode(String id, EffectKind effectKind, int count, float width, float height) {
        super(id, "particles");
        this.effectKind = Objects.requireNonNull(effectKind, "effectKind must not be null");
        float safeW = Float.isFinite(width) && width > 0.0f ? width : 1920.0f;
        float safeH = Float.isFinite(height) && height > 0.0f ? height : 1080.0f;
        this.particleSystem = new ParticleSystem(safeW, safeH);
        setSize(safeW, safeH);
        setZOrder(SceneLayer.Z_FOREGROUND);
        configureEffect(effectKind, Math.max(1, count));
    }

    /**
     * Reconfigures this node's active particle preset and particle pool size.
     *
     * @param kind  effect preset (non-null)
     * @param count particle count ({@code >= 1})
     */
    public void configureEffect(EffectKind kind, int count) {
        this.effectKind = Objects.requireNonNull(kind, "kind must not be null");
        int n = Math.max(1, count);
        particleSystem.clearEmitters();
        ParticleSystem.ParticleEmitter emitter = switch (kind) {
            case SNOW -> ParticleSystem.snowBlossom(n, 115.0f, 18.0f);
            case RAIN -> ParticleSystem.rain(n, 920.0f, -65.0f);
            case FIREFLIES -> ParticleSystem.dust(n);
            case SAKURA -> {
                ParticleSystem.ParticleEmitter sakura = ParticleSystem.cherryBlossoms(n);
                sakura.setWindX(35.0f);
                yield sakura;
            }
            case SPARKS -> ParticleSystem.sparks(n);
        };
        particleSystem.addEmitter(emitter);
    }

    /**
     * Sets collision floor Y coordinate (for example, dialogue textbox top edge) across all emitters.
     *
     * @param floorY surface Y coordinate in logical pixels, or {@code null} to disable
     * @param bounce {@code true} to bounce slightly, {@code false} to melt cleanly
     */
    public void setCollisionFloorY(Float floorY, boolean bounce) {
        for (ParticleSystem.ParticleEmitter emitter : particleSystem.getEmitters()) {
            emitter.setCollisionFloorY(floorY, bounce, 0.35f);
        }
    }

    /**
     * Advances particle physics simulation by {@code dt} seconds.
     *
     * @param dt frame delta in seconds
     */
    public void update(double dt) {
        if (intensity > 0.0f) {
            particleSystem.update(dt);
        }
    }

    @Override
    protected boolean shouldDrawStandardQuad() {
        return false;
    }

    @Override
    protected void onCustomRender(RenderPipeline2D pipeline) {
        if (pipeline == null || intensity <= 0.0f) {
            return;
        }
        Rect2f bounds = pipeline.getCurrentRenderBounds();
        if (bounds == null) {
            bounds = new Rect2f(0.0f, 0.0f, getWidth(), getHeight());
        }
        float alpha = Math.clamp(pipeline.getCurrentEffectiveAlpha() * intensity, 0.0f, 1.0f);
        ColorMatrix4f matrix = pipeline.getCurrentEffectiveColorMatrix();
        particleSystem.render(pipeline, bounds, alpha, matrix);
        super.onCustomRender(pipeline);
    }

    /**
     * Returns the backing {@link ParticleSystem}.
     *
     * @return backing {@link ParticleSystem}
     */
    public ParticleSystem getParticleSystem() {
        return particleSystem;
    }

    /**
     * Returns total active particles currently managed by this node.
     *
     * @return active particle count
     */
    public int getActiveParticleCount() {
        return particleSystem.getTotalParticleCount();
    }

    /**
     * Returns the configured {@link EffectKind}.
     *
     * @return configured {@link EffectKind}
     */
    public EffectKind getEffectKind() {
        return effectKind;
    }

    /**
     * Sets emitter blend intensity in {@code [0.0f, 1.0f]}.
     *
     * @param intensity intensity in {@code [0, 1]}
     */
    public void setIntensity(float intensity) {
        this.intensity = Float.isFinite(intensity) ? Math.clamp(intensity, 0.0f, 1.0f) : 1.0f;
    }

    /**
     * Returns current emitter intensity in {@code [0, 1]}.
     *
     * @return intensity in {@code [0, 1]}
     */
    public float getIntensity() {
        return intensity;
    }
}
