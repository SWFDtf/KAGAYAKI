package de.swfdtf.graphics;

import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Rect2f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * GPU-batchable and headless-simulatable 2D particle and weather system for KAGAYAKI.
 *
 * <p>Manages one or more {@link ParticleEmitter} instances across built-in presets
 * ({@link EmitterKind#SNOW_BLOSSOM}, {@link EmitterKind#RAIN}, {@link EmitterKind#CHERRY_BLOSSOM},
 * {@link EmitterKind#SPARKS}, {@link EmitterKind#DUST}). Implements {@link CustomDisplayable}
 * so the system can attach cleanly to the {@link SceneGraph#LAYER_FOREGROUND} ({@code Z = 100})
 * layer via {@link #attachToForeground(SceneGraph)} or render directly through {@link RenderPipeline2D}.</p>
 */
public final class ParticleSystem implements CustomDisplayable {

    /** Default logical simulation canvas width in pixels. */
    public static final float DEFAULT_WIDTH = RenderPipeline2D.DEFAULT_LOGICAL_WIDTH;

    /** Default logical simulation canvas height in pixels. */
    public static final float DEFAULT_HEIGHT = RenderPipeline2D.DEFAULT_LOGICAL_HEIGHT;

    private static final Rect2f FULL_UV = new Rect2f(0.0f, 0.0f, 1.0f, 1.0f);

    /**
     * Built-in atmospheric and FX particle presets.
     */
    public enum EmitterKind {
        SNOW_BLOSSOM,
        RAIN,
        CHERRY_BLOSSOM,
        SPARKS,
        DUST
    }

    /**
     * Mutable simulation state for a single particle within a {@link ParticleEmitter}.
     */
    public static final class Particle {
        public float x;
        public float y;
        public float vx;
        public float vy;
        public float rotation;
        public float angularVelocity;
        public float size;
        public float alpha;
        public float life;
        public float maxLife;
        public final float[] colorRgba;
        public float swayPhase;
        public float swayAmplitude;
        public float swayFrequency;

        /**
         * Creates a particle initialized with full kinematic and visual attributes.
         *
         * @param x               X coordinate in logical pixels
         * @param y               Y coordinate in logical pixels
         * @param vx              base horizontal velocity in px/s
         * @param vy              base vertical velocity in px/s
         * @param rotation        rotation angle in degrees
         * @param angularVelocity angular velocity in deg/s
         * @param size            base size in logical pixels
         * @param alpha           opacity multiplier in {@code [0, 1]}
         * @param life            remaining lifetime in seconds
         * @param maxLife         maximum lifetime in seconds ({@code <= 0} for immortal wrap-around)
         * @param colorRgba       4-element straight RGBA array {@code [r, g, b, a]}
         */
        public Particle(
                float x,
                float y,
                float vx,
                float vy,
                float rotation,
                float angularVelocity,
                float size,
                float alpha,
                float life,
                float maxLife,
                float[] colorRgba
        ) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.rotation = rotation;
            this.angularVelocity = angularVelocity;
            this.size = size;
            this.alpha = alpha;
            this.life = life;
            this.maxLife = maxLife;
            this.colorRgba = new float[4];
            if (colorRgba != null && colorRgba.length == 4) {
                System.arraycopy(colorRgba, 0, this.colorRgba, 0, 4);
            } else {
                this.colorRgba[0] = 1.0f;
                this.colorRgba[1] = 1.0f;
                this.colorRgba[2] = 1.0f;
                this.colorRgba[3] = 1.0f;
            }
        }

        public float getX() {
            return x;
        }

        public float getY() {
            return y;
        }

        public float getVx() {
            return vx;
        }

        public float getVy() {
            return vy;
        }

        public float getRotation() {
            return rotation;
        }

        public float getAngularVelocity() {
            return angularVelocity;
        }

        public float getSize() {
            return size;
        }

        public float getAlpha() {
            return alpha;
        }

        public float getLife() {
            return life;
        }

        public float getMaxLife() {
            return maxLife;
        }

        public float[] getColorRgba() {
            return colorRgba.clone();
        }
    }

    /**
     * Configurable emitter simulating and rendering a fixed pool of {@link Particle} instances
     * with wind, sinusoidal sway, lifetime respawn, and wrap-around within logical canvas bounds.
     */
    public static final class ParticleEmitter {
        private final EmitterKind kind;
        private final List<Particle> particles;
        private final Random random;
        private float boundsWidth;
        private float boundsHeight;
        private float windX;
        private float speed;
        private Texture texture;
        private boolean active = true;

        /**
         * Creates an emitter with explicit particle list and bounds.
         *
         * @param kind         emitter preset classification
         * @param particles    initial particle pool
         * @param boundsWidth  logical wrap width in pixels ({@code > 0})
         * @param boundsHeight logical wrap height in pixels ({@code > 0})
         * @param speed        nominal particle speed in px/s
         * @param windX        global horizontal wind velocity in px/s
         * @param random       random generator used on particle respawn
         */
        public ParticleEmitter(
                EmitterKind kind,
                List<Particle> particles,
                float boundsWidth,
                float boundsHeight,
                float speed,
                float windX,
                Random random
        ) {
            this.kind = Objects.requireNonNull(kind, "kind must not be null");
            this.particles = new ArrayList<>(Objects.requireNonNull(particles, "particles must not be null"));
            this.boundsWidth = boundsWidth > 0.0f ? boundsWidth : DEFAULT_WIDTH;
            this.boundsHeight = boundsHeight > 0.0f ? boundsHeight : DEFAULT_HEIGHT;
            this.speed = speed;
            this.windX = windX;
            this.random = random != null ? random : new Random();
        }

        /**
         * Advances all particles in this emitter by {@code dt} seconds, applying velocity, wind,
         * sinusoidal sway, angular rotation, lifetime respawn, and canvas wrap-around.
         *
         * @param dt elapsed frame delta in seconds
         */
        public void update(double dt) {
            if (!active || !Double.isFinite(dt) || dt <= 0.0 || particles.isEmpty()) {
                return;
            }
            float fdt = (float) dt;

            for (Particle p : particles) {
                p.swayPhase += p.swayFrequency * fdt;
                float swayVx = p.swayAmplitude * (float) Math.sin(p.swayPhase);

                p.x += (p.vx + windX + swayVx) * fdt;
                p.y += p.vy * fdt;
                p.rotation = (p.rotation + p.angularVelocity * fdt) % 360.0f;

                if (p.maxLife > 0.0f) {
                    p.life -= fdt;
                    if (p.life <= 0.0f) {
                        respawnFiniteParticle(p);
                    }
                }

                p.x = wrapCoord(p.x, boundsWidth);
                p.y = wrapCoord(p.y, boundsHeight);
            }
        }

        private void respawnFiniteParticle(Particle p) {
            p.life = p.maxLife;
            p.x = random.nextFloat() * boundsWidth;
            if (kind == EmitterKind.SPARKS) {
                p.y = boundsHeight - random.nextFloat() * (boundsHeight * 0.15f);
            } else {
                p.y = random.nextFloat() * boundsHeight;
            }
        }

        private static float wrapCoord(float value, float limit) {
            if (limit <= 0.0f || !Float.isFinite(value)) {
                return 0.0f;
            }
            float wrapped = value % limit;
            if (wrapped < 0.0f) {
                wrapped += limit;
            }
            if (wrapped >= limit) {
                wrapped = 0.0f;
            }
            return wrapped;
        }

        /**
         * Renders all particles belonging to this emitter using {@link RenderPipeline2D}.
         *
         * @param pipeline    active 2D render pipeline
         * @param bounds      destination bounds offset/scale (defaults to {@code (0,0,boundsWidth,boundsHeight)})
         * @param alpha       inherited opacity multiplier in {@code [0, 1]}
         * @param colorMatrix inherited 4x4 premultiplied color transformation matrix
         */
        public void render(RenderPipeline2D pipeline, Rect2f bounds, float alpha, ColorMatrix4f colorMatrix) {
            if (!active || pipeline == null || alpha <= 0.0f || particles.isEmpty()) {
                return;
            }
            float offsetX = bounds != null ? bounds.x() : 0.0f;
            float offsetY = bounds != null ? bounds.y() : 0.0f;
            float scaleX = (bounds != null && bounds.width() > 0.0f) ? bounds.width() / boundsWidth : 1.0f;
            float scaleY = (bounds != null && bounds.height() > 0.0f) ? bounds.height() / boundsHeight : 1.0f;
            ColorMatrix4f effectiveCm = colorMatrix != null ? colorMatrix : ColorMatrix4f.identity();
            boolean applyCpuMatrix = !effectiveCm.equals(ColorMatrix4f.identity());

            for (Particle p : particles) {
                float lifeFactor = p.maxLife > 0.0f
                        ? Math.clamp(p.life / p.maxLife, 0.0f, 1.0f)
                        : 1.0f;
                float effAlpha = Math.clamp(p.colorRgba[3] * p.alpha * alpha * lifeFactor, 0.0f, 1.0f);
                if (effAlpha <= 0.001f || p.size <= 0.0f) {
                    continue;
                }

                float drawX = offsetX + p.x * scaleX;
                float drawY = offsetY + p.y * scaleY;
                float drawSize = p.size * Math.min(scaleX, scaleY);

                if (texture != null && !texture.isDisposed() && texture.getTextureId() != 0) {
                    float[] tint = new float[]{p.colorRgba[0], p.colorRgba[1], p.colorRgba[2], effAlpha};
                    pipeline.drawTexturedQuad(
                            texture.getTextureId(),
                            drawX - drawSize * 0.5f,
                            drawY - drawSize * 0.5f,
                            drawSize,
                            drawSize,
                            FULL_UV,
                            tint,
                            effectiveCm,
                            p.rotation,
                            0.5f,
                            0.5f
                    );
                    continue;
                }

                float r = p.colorRgba[0];
                float g = p.colorRgba[1];
                float b = p.colorRgba[2];
                float a = effAlpha;
                if (applyCpuMatrix) {
                    float[] transformed = effectiveCm.applyPremultiplied(r * a, g * a, b * a, a);
                    a = Math.clamp(transformed[3], 0.0f, 1.0f);
                    if (a <= 0.001f) {
                        continue;
                    }
                    r = Math.clamp(transformed[0] / a, 0.0f, 1.0f);
                    g = Math.clamp(transformed[1] / a, 0.0f, 1.0f);
                    b = Math.clamp(transformed[2] / a, 0.0f, 1.0f);
                }

                switch (kind) {
                    case RAIN -> {
                        float streakW = Math.max(2.0f, drawSize * 0.25f);
                        float streakH = Math.max(12.0f, drawSize * 3.5f);
                        pipeline.drawRect(drawX, drawY, streakW, streakH, r, g, b, a);
                    }
                    case SPARKS -> pipeline.drawRoundedRect(
                            drawX - drawSize * 0.5f,
                            drawY - drawSize * 0.5f,
                            drawSize,
                            drawSize,
                            drawSize * 0.35f,
                            r, g, b, a
                    );
                    case SNOW_BLOSSOM, CHERRY_BLOSSOM, DUST -> pipeline.drawRoundedRect(
                            drawX - drawSize * 0.5f,
                            drawY - drawSize * 0.5f,
                            drawSize,
                            drawSize,
                            drawSize * 0.5f,
                            r, g, b, a
                    );
                }
            }
        }

        public EmitterKind getKind() {
            return kind;
        }

        public List<Particle> getParticles() {
            return Collections.unmodifiableList(particles);
        }

        public int getParticleCount() {
            return particles.size();
        }

        public float getBoundsWidth() {
            return boundsWidth;
        }

        public float getBoundsHeight() {
            return boundsHeight;
        }

        public ParticleEmitter setBounds(float width, float height) {
            if (width <= 0.0f || height <= 0.0f) {
                throw new IllegalArgumentException("Emitter bounds must be positive: " + width + "x" + height);
            }
            this.boundsWidth = width;
            this.boundsHeight = height;
            return this;
        }

        public float getWindX() {
            return windX;
        }

        public ParticleEmitter setWindX(float windX) {
            this.windX = windX;
            return this;
        }

        public float getSpeed() {
            return speed;
        }

        public ParticleEmitter setSpeed(float speed) {
            this.speed = speed;
            return this;
        }

        public Texture getTexture() {
            return texture;
        }

        public ParticleEmitter setTexture(Texture texture) {
            this.texture = texture;
            return this;
        }

        public boolean isActive() {
            return active;
        }

        public ParticleEmitter setActive(boolean active) {
            this.active = active;
            return this;
        }
    }

    private final List<ParticleEmitter> emitters = new ArrayList<>();
    private float width = DEFAULT_WIDTH;
    private float height = DEFAULT_HEIGHT;

    /**
     * Creates an empty {@code ParticleSystem} configured for {@code 1920x1080}.
     */
    public ParticleSystem() {
    }

    /**
     * Creates a {@code ParticleSystem} with explicit preferred bounds.
     *
     * @param width  logical width in pixels ({@code > 0})
     * @param height logical height in pixels ({@code > 0})
     */
    public ParticleSystem(float width, float height) {
        if (width <= 0.0f || height <= 0.0f) {
            throw new IllegalArgumentException("ParticleSystem bounds must be positive");
        }
        this.width = width;
        this.height = height;
    }

    // ---- Static Preset Factories ----

    /**
     * Creates a {@code SnowBlossom}-style gentle falling snow/blossom emitter across {@code 1920x1080}.
     *
     * @param count number of particles ({@code >= 0})
     * @param speed nominal downward fall speed in px/s
     * @param windX horizontal wind speed in px/s
     * @return configured {@link ParticleEmitter}
     */
    public static ParticleEmitter snowBlossom(int count, float speed, float windX) {
        return snowBlossom(count, speed, windX, new Random());
    }

    /**
     * Creates a {@code SnowBlossom}-style emitter using an explicit {@link Random} source.
     */
    public static ParticleEmitter snowBlossom(int count, float speed, float windX, Random random) {
        int safeCount = Math.max(0, count);
        Random rng = random != null ? random : new Random();
        List<Particle> list = new ArrayList<>(safeCount);
        float baseSpeed = Math.abs(speed);
        for (int i = 0; i < safeCount; i++) {
            float vy = baseSpeed * (0.70f + rng.nextFloat() * 0.60f);
            float vx = (rng.nextFloat() - 0.5f) * 20.0f;
            float size = 4.0f + rng.nextFloat() * 8.0f;
            float alpha = 0.55f + rng.nextFloat() * 0.40f;
            Particle p = new Particle(
                    rng.nextFloat() * DEFAULT_WIDTH,
                    rng.nextFloat() * DEFAULT_HEIGHT,
                    vx,
                    vy,
                    rng.nextFloat() * 360.0f,
                    (rng.nextFloat() - 0.5f) * 60.0f,
                    size,
                    alpha,
                    0.0f,
                    0.0f,
                    new float[]{0.94f, 0.97f, 1.0f, 1.0f}
            );
            p.swayPhase = rng.nextFloat() * (float) (Math.PI * 2.0);
            p.swayAmplitude = 15.0f + rng.nextFloat() * 25.0f;
            p.swayFrequency = 1.2f + rng.nextFloat() * 1.5f;
            list.add(p);
        }
        return new ParticleEmitter(EmitterKind.SNOW_BLOSSOM, list, DEFAULT_WIDTH, DEFAULT_HEIGHT, baseSpeed, windX, rng);
    }

    /**
     * Creates a fast directional {@code RAIN} streak emitter across {@code 1920x1080}.
     *
     * @param count number of raindrops ({@code >= 0})
     * @param speed nominal downward rain speed in px/s
     * @param windX horizontal wind velocity in px/s
     * @return configured {@link ParticleEmitter}
     */
    public static ParticleEmitter rain(int count, float speed, float windX) {
        return rain(count, speed, windX, new Random());
    }

    /**
     * Creates a {@code RAIN} emitter using an explicit {@link Random} source.
     */
    public static ParticleEmitter rain(int count, float speed, float windX, Random random) {
        int safeCount = Math.max(0, count);
        Random rng = random != null ? random : new Random();
        List<Particle> list = new ArrayList<>(safeCount);
        float baseSpeed = Math.abs(speed);
        float angleDeg = (float) Math.toDegrees(Math.atan2(windX, Math.max(baseSpeed, 1.0f)));
        for (int i = 0; i < safeCount; i++) {
            float vy = baseSpeed * (0.85f + rng.nextFloat() * 0.35f);
            float size = 6.0f + rng.nextFloat() * 4.0f;
            float alpha = 0.35f + rng.nextFloat() * 0.30f;
            Particle p = new Particle(
                    rng.nextFloat() * DEFAULT_WIDTH,
                    rng.nextFloat() * DEFAULT_HEIGHT,
                    0.0f,
                    vy,
                    angleDeg,
                    0.0f,
                    size,
                    alpha,
                    0.0f,
                    0.0f,
                    new float[]{0.66f, 0.80f, 1.0f, 1.0f}
            );
            list.add(p);
        }
        return new ParticleEmitter(EmitterKind.RAIN, list, DEFAULT_WIDTH, DEFAULT_HEIGHT, baseSpeed, windX, rng);
    }

    /**
     * Creates a {@code CHERRY_BLOSSOM} sakura petal emitter with tumbling rotation and gentle wind sway.
     *
     * @param count number of sakura petals ({@code >= 0})
     * @return configured {@link ParticleEmitter}
     */
    public static ParticleEmitter cherryBlossoms(int count) {
        return cherryBlossoms(count, new Random());
    }

    /**
     * Creates a {@code CHERRY_BLOSSOM} emitter using an explicit {@link Random} source.
     */
    public static ParticleEmitter cherryBlossoms(int count, Random random) {
        int safeCount = Math.max(0, count);
        Random rng = random != null ? random : new Random();
        List<Particle> list = new ArrayList<>(safeCount);
        for (int i = 0; i < safeCount; i++) {
            float vy = 60.0f + rng.nextFloat() * 70.0f;
            float vx = 10.0f + rng.nextFloat() * 25.0f;
            float size = 8.0f + rng.nextFloat() * 8.0f;
            float alpha = 0.70f + rng.nextFloat() * 0.25f;
            float greenTint = 0.62f + rng.nextFloat() * 0.20f;
            float blueTint = 0.74f + rng.nextFloat() * 0.16f;
            Particle p = new Particle(
                    rng.nextFloat() * DEFAULT_WIDTH,
                    rng.nextFloat() * DEFAULT_HEIGHT,
                    vx,
                    vy,
                    rng.nextFloat() * 360.0f,
                    (rng.nextFloat() - 0.5f) * 180.0f,
                    size,
                    alpha,
                    0.0f,
                    0.0f,
                    new float[]{1.0f, greenTint, blueTint, 1.0f}
            );
            p.swayPhase = rng.nextFloat() * (float) (Math.PI * 2.0);
            p.swayAmplitude = 35.0f + rng.nextFloat() * 40.0f;
            p.swayFrequency = 1.4f + rng.nextFloat() * 1.4f;
            list.add(p);
        }
        return new ParticleEmitter(EmitterKind.CHERRY_BLOSSOM, list, DEFAULT_WIDTH, DEFAULT_HEIGHT, 95.0f, 35.0f, rng);
    }

    /**
     * Creates an upward-rising glowing {@code SPARKS} ember emitter across {@code 1920x1080}.
     *
     * @param count number of sparks ({@code >= 0})
     * @return configured {@link ParticleEmitter}
     */
    public static ParticleEmitter sparks(int count) {
        return sparks(count, new Random());
    }

    /**
     * Creates a {@code SPARKS} emitter using an explicit {@link Random} source.
     */
    public static ParticleEmitter sparks(int count, Random random) {
        int safeCount = Math.max(0, count);
        Random rng = random != null ? random : new Random();
        List<Particle> list = new ArrayList<>(safeCount);
        for (int i = 0; i < safeCount; i++) {
            float vy = -(90.0f + rng.nextFloat() * 140.0f);
            float vx = (rng.nextFloat() - 0.5f) * 50.0f;
            float size = 3.0f + rng.nextFloat() * 5.0f;
            float maxLife = 1.5f + rng.nextFloat() * 2.5f;
            float life = rng.nextFloat() * maxLife;
            float green = 0.45f + rng.nextFloat() * 0.40f;
            Particle p = new Particle(
                    rng.nextFloat() * DEFAULT_WIDTH,
                    rng.nextFloat() * DEFAULT_HEIGHT,
                    vx,
                    vy,
                    0.0f,
                    0.0f,
                    size,
                    0.85f,
                    life,
                    maxLife,
                    new float[]{1.0f, green, 0.18f, 1.0f}
            );
            p.swayPhase = rng.nextFloat() * (float) (Math.PI * 2.0);
            p.swayAmplitude = 20.0f + rng.nextFloat() * 30.0f;
            p.swayFrequency = 3.0f + rng.nextFloat() * 3.0f;
            list.add(p);
        }
        return new ParticleEmitter(EmitterKind.SPARKS, list, DEFAULT_WIDTH, DEFAULT_HEIGHT, 160.0f, 10.0f, rng);
    }

    /**
     * Creates a slow ambient floating {@code DUST} mote emitter across {@code 1920x1080}.
     *
     * @param count number of dust motes ({@code >= 0})
     * @return configured {@link ParticleEmitter}
     */
    public static ParticleEmitter dust(int count) {
        return dust(count, new Random());
    }

    /**
     * Creates a {@code DUST} emitter using an explicit {@link Random} source.
     */
    public static ParticleEmitter dust(int count, Random random) {
        int safeCount = Math.max(0, count);
        Random rng = random != null ? random : new Random();
        List<Particle> list = new ArrayList<>(safeCount);
        for (int i = 0; i < safeCount; i++) {
            float vx = (rng.nextFloat() - 0.5f) * 30.0f;
            float vy = (rng.nextFloat() - 0.5f) * 24.0f;
            float size = 2.0f + rng.nextFloat() * 4.0f;
            float alpha = 0.18f + rng.nextFloat() * 0.28f;
            Particle p = new Particle(
                    rng.nextFloat() * DEFAULT_WIDTH,
                    rng.nextFloat() * DEFAULT_HEIGHT,
                    vx,
                    vy,
                    0.0f,
                    0.0f,
                    size,
                    alpha,
                    0.0f,
                    0.0f,
                    new float[]{0.94f, 0.96f, 0.99f, 1.0f}
            );
            p.swayPhase = rng.nextFloat() * (float) (Math.PI * 2.0);
            p.swayAmplitude = 8.0f + rng.nextFloat() * 12.0f;
            p.swayFrequency = 0.6f + rng.nextFloat() * 0.8f;
            list.add(p);
        }
        return new ParticleEmitter(EmitterKind.DUST, list, DEFAULT_WIDTH, DEFAULT_HEIGHT, 20.0f, 0.0f, rng);
    }

    // ---- Emitter Management & Scene Integration ----

    /**
     * Registers an emitter with this particle system.
     *
     * @param emitter emitter to add (non-null)
     * @return {@code this} for fluent chaining
     */
    public ParticleSystem addEmitter(ParticleEmitter emitter) {
        Objects.requireNonNull(emitter, "emitter must not be null");
        if (!emitters.contains(emitter)) {
            emitters.add(emitter);
        }
        return this;
    }

    /**
     * Removes an emitter from this particle system.
     *
     * @param emitter emitter to remove
     * @return {@code true} if present and removed
     */
    public boolean removeEmitter(ParticleEmitter emitter) {
        return emitter != null && emitters.remove(emitter);
    }

    /**
     * Removes all emitters from this particle system.
     */
    public void clearEmitters() {
        emitters.clear();
    }

    /** @return unmodifiable list of registered emitters */
    public List<ParticleEmitter> getEmitters() {
        return Collections.unmodifiableList(emitters);
    }

    /** @return total number of particles across all registered emitters */
    public int getTotalParticleCount() {
        int total = 0;
        for (ParticleEmitter emitter : emitters) {
            total += emitter.getParticleCount();
        }
        return total;
    }

    @Override
    public void update(double dt) {
        for (ParticleEmitter emitter : emitters) {
            emitter.update(dt);
        }
    }

    /**
     * Renders all active emitters directly into {@code pipeline} at full opacity over {@code 1920x1080}.
     *
     * @param pipeline active 2D rendering pipeline
     */
    public void render(RenderPipeline2D pipeline) {
        render(pipeline, new Rect2f(0.0f, 0.0f, width, height), 1.0f, ColorMatrix4f.identity());
    }

    @Override
    public void render(RenderPipeline2D pipeline, Rect2f bounds, float alpha, ColorMatrix4f colorMatrix) {
        if (pipeline == null || alpha <= 0.0f) {
            return;
        }
        for (ParticleEmitter emitter : emitters) {
            emitter.render(pipeline, bounds, alpha, colorMatrix);
        }
    }

    @Override
    public float getPreferredWidth() {
        return width;
    }

    @Override
    public float getPreferredHeight() {
        return height;
    }

    /**
     * Wraps this {@code ParticleSystem} inside a {@link SpriteNode} configured for foreground display.
     *
     * @param id optional node ID
     * @return configured {@link SpriteNode}
     */
    public SpriteNode toSceneNode(String id) {
        SpriteNode node = new SpriteNode(id != null ? id : "particles", "particles");
        node.setZOrder(SceneLayer.Z_FOREGROUND);
        node.setSize(width, height);
        node.setCustomDisplayable(this);
        return node;
    }

    /**
     * Attaches this {@code ParticleSystem} onto {@link SceneGraph#LAYER_FOREGROUND} ({@code Z = 100}).
     *
     * @param sceneGraph target scene graph (non-null)
     * @return the newly attached {@link SpriteNode}
     */
    public SpriteNode attachToForeground(SceneGraph sceneGraph) {
        Objects.requireNonNull(sceneGraph, "sceneGraph must not be null");
        SpriteNode node = toSceneNode("particles:foreground");
        sceneGraph.addNode(SceneGraph.LAYER_FOREGROUND, node);
        return node;
    }
}
