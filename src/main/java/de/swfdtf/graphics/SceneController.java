package de.swfdtf.graphics;

import de.swfdtf.core.EngineLogger;
import de.swfdtf.math.Transform2D;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.state.PersistentAccess;
import de.swfdtf.state.StateStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * High-level visual stage controller exposed as {@code scene} inside {@code @->{ ... }<<} Java blocks
 * and driven by {@code ScriptRuntime}.
 *
 * <p>Manages semantic tag-based backgrounds and character sprites on {@link SceneGraph} layers,
 * automatically replacing matching sprite tags while preserving position and transform when no
 * new {@code at <position>} is specified.</p>
 */
public final class SceneController {

    /**
     * Immutable serializable snapshot of a single visible sprite or background on stage.
     *
     * @param tag          semantic tag (e.g., {@code "lena"})
     * @param attributes   active sprite attributes (e.g., {@code ["happy", "winter"]})
     * @param positionName named preset or transform name (nullable)
     * @param assetPath    resolved asset path (nullable)
     * @param transform    copied transform state
     */
    public record StageEntry(
            String tag,
            List<String> attributes,
            String positionName,
            String assetPath,
            Transform2D transform
    ) {
        /** Normalizes null collections and copies the node transform defensively. */
        public StageEntry {
            attributes = attributes != null ? List.copyOf(attributes) : List.of();
            transform = transform != null ? transform.copy() : new Transform2D();
        }
    }

    /**
     * Snapshot of the full visual stage state for deterministic M4 rollback/save restoration.
     *
     * @param backgroundTag       active background tag (nullable)
     * @param backgroundAssetPath active background path (nullable)
     * @param backgroundTransform background transform copy
     * @param activeSprites       ordered map of active character/master sprite tags
     * @param motionSnapshot      optional captured {@link ObjectMotionController.MotionSnapshot} (nullable)
     */
    public record StageSnapshot(
            String backgroundTag,
            String backgroundAssetPath,
            Transform2D backgroundTransform,
            Map<String, StageEntry> activeSprites,
            ObjectMotionController.MotionSnapshot motionSnapshot
    ) {
        /**
         * Backwards-compatible 4-argument constructor without motion state.
         *
         * @param backgroundTag       active background tag (nullable)
         * @param backgroundAssetPath active background path (nullable)
         * @param backgroundTransform background transform copy
         * @param activeSprites       ordered map of active character/master sprite tags
         */
        public StageSnapshot(
                String backgroundTag,
                String backgroundAssetPath,
                Transform2D backgroundTransform,
                Map<String, StageEntry> activeSprites
        ) {
            this(backgroundTag, backgroundAssetPath, backgroundTransform, activeSprites, null);
        }

        /** Normalizes the background transform and defensively copies active sprite entries. */
        public StageSnapshot {
            backgroundTransform = backgroundTransform != null ? backgroundTransform.copy() : new Transform2D();
            Map<String, StageEntry> copy = new LinkedHashMap<>();
            if (activeSprites != null) {
                activeSprites.forEach((k, v) -> copy.put(k, new StageEntry(
                        v.tag(), v.attributes(), v.positionName(), v.assetPath(), v.transform()
                )));
            }
            activeSprites = Collections.unmodifiableMap(copy);
        }
    }

    private final SceneGraph sceneGraph;
    private final TextureManager textureManager;
    private final EngineLogger logger;
    private final boolean glTextureUploadEnabled;

    private final Map<String, SpriteNode> activeSprites = new LinkedHashMap<>();
    private final Map<String, String> spritePositionNames = new LinkedHashMap<>();
    private final Map<String, String> imageDefinitions = new LinkedHashMap<>();
    private final Map<String, VJavaAst.LayeredImageDef> layeredImageDefinitions = new LinkedHashMap<>();
    private final Map<String, Transform2D> namedTransforms = new LinkedHashMap<>();
    private final Map<String, List<VJavaAst.AtlStep>> namedTransformSteps = new LinkedHashMap<>();
    private final Map<String, ATLTransform> activeSpriteAtl = new LinkedHashMap<>();
    private final ObjectMotionController motionController = new ObjectMotionController(activeSprites);

    private Path projectRoot;
    private StateStore stateStore;
    private PersistentAccess persistentAccess;
    private SpriteNode backgroundNode;
    private String backgroundTag;
    private String lastTransitionExpr;
    private BiConsumer<String, String> transitionListener;

    /**
     * Creates a headless-safe {@code SceneController} without OpenGL texture uploads.
     *
     * @param sceneGraph target scene graph
     */
    public SceneController(SceneGraph sceneGraph) {
        this(sceneGraph, null, new EngineLogger(), null, false);
    }

    /**
     * Creates a {@code SceneController} bound to a {@link SceneGraph} and optional {@link TextureManager}.
     *
     * @param sceneGraph             target scene graph
     * @param textureManager         optional texture manager (nullable in headless runs)
     * @param logger                 structured engine logger
     * @param projectRoot            optional project root directory for asset path resolution
     * @param glTextureUploadEnabled whether to decode/upload GPU textures immediately
     */
    public SceneController(
            SceneGraph sceneGraph,
            TextureManager textureManager,
            EngineLogger logger,
            Path projectRoot,
            boolean glTextureUploadEnabled
    ) {
        this.sceneGraph = Objects.requireNonNull(sceneGraph, "sceneGraph must not be null");
        this.textureManager = textureManager;
        this.logger = logger != null ? logger : new EngineLogger();
        this.projectRoot = projectRoot;
        this.glTextureUploadEnabled = glTextureUploadEnabled;
        this.motionController.setAnimStateListener(this::applyAnimStateAttribute);
        registerBuiltinPositions();
    }

    private void applyAnimStateAttribute(String tag, String attrName) {
        SpriteNode existing = activeSprites.get(tag);
        if (existing == null || attrName == null || attrName.isBlank()) {
            return;
        }
        String trimmedAttr = attrName.trim();
        if (existing.getCustomDisplayable() instanceof LayeredImage layered) {
            layered.setAttributes(List.of(trimmedAttr), stateStore, persistentAccess);
            if (!layered.getResolvedLayerAssets().isEmpty()) {
                List<String> resolved = layered.getResolvedLayerAssets();
                existing.setAssetPath(resolved.get(resolved.size() - 1));
            }
            return;
        }
        String resolvedAsset = resolveImageAssetPath(tag, List.of(trimmedAttr));
        existing.setAssetPath(resolvedAsset);
        if (glTextureUploadEnabled && textureManager != null) {
            existing.setTexture(textureManager.loadTexture(resolvedAsset));
        }
    }

    private void registerBuiltinPositions() {
        namedTransforms.put("left", new Transform2D().setXalign(0.15f).setYalign(1.0f));
        namedTransforms.put("center", new Transform2D().setXalign(0.5f).setYalign(1.0f));
        namedTransforms.put("truecenter", new Transform2D().setXalign(0.5f).setYalign(0.5f));
        namedTransforms.put("right", new Transform2D().setXalign(0.85f).setYalign(1.0f));
        namedTransforms.put("offscreenleft", new Transform2D().setXpos(-600.0f).setYalign(1.0f));
        namedTransforms.put("offscreenright", new Transform2D().setXpos(2520.0f).setYalign(1.0f));
        namedTransforms.put("reset", new Transform2D());
    }

    /**
     * Switches the background scene, clears active master-layer sprites, and optionally triggers a transition.
     *
     * @param sceneName      background tag or relative asset path
     * @param transitionExpr transition expression (nullable)
     */
    public void setScene(String sceneName, String transitionExpr) {
        Objects.requireNonNull(sceneName, "sceneName must not be null");
        motionController.clearAll();
        clearMasterSprites();
        sceneGraph.getLayer(SceneGraph.LAYER_BACKGROUND).clear();

        String resolvedAsset = resolveImageAssetPath(sceneName, List.of());
        SpriteNode bg = new SpriteNode("bg:" + sceneName, sceneName, List.of());
        bg.setAssetPath(resolvedAsset);
        bg.setSize(RenderPipeline2D.DEFAULT_LOGICAL_WIDTH, RenderPipeline2D.DEFAULT_LOGICAL_HEIGHT);
        if (glTextureUploadEnabled && textureManager != null) {
            Texture tex = textureManager.loadTexture(resolvedAsset);
            bg.setTexture(tex);
        }

        sceneGraph.addNode(SceneGraph.LAYER_BACKGROUND, bg);
        this.backgroundNode = bg;
        this.backgroundTag = sceneName;
        notifyTransition("scene", transitionExpr);
    }

    /**
     * Shows or updates a tagged sprite on the master stage layer.
     *
     * <p>If a sprite with the same semantic {@code tag} is already visible on stage and
     * {@code positionOrTransform} is {@code null} or blank, its existing position/transform
     * is preserved automatically while its attributes/texture update.</p>
     *
     * @param tag                 semantic sprite tag (e.g., {@code "lena"})
     * @param attributes          attribute qualifiers (e.g., {@code ["happy", "winter"]})
     * @param positionOrTransform optional {@code at <pos>} name (nullable)
     * @param transitionExpr      optional {@code with <transition>} expression (nullable)
     * @return the active {@link SpriteNode} on the master layer
     */
    public SpriteNode showSprite(
            String tag,
            List<String> attributes,
            String positionOrTransform,
            String transitionExpr
    ) {
        Objects.requireNonNull(tag, "tag must not be null");
        List<String> attrList = attributes != null ? List.copyOf(attributes) : List.of();
        SpriteNode existing = activeSprites.get(tag);

        Transform2D transform;
        String resolvedPositionName;
        if (positionOrTransform != null && !positionOrTransform.isBlank()) {
            transform = resolvePositionTransform(positionOrTransform);
            resolvedPositionName = positionOrTransform.trim();
        } else if (existing != null) {
            transform = existing.getTransform().copy();
            resolvedPositionName = spritePositionNames.get(tag);
        } else {
            transform = resolvePositionTransform("center");
            resolvedPositionName = "center";
        }

        if (existing != null) {
            sceneGraph.getLayer(SceneGraph.LAYER_MASTER).removeNode(existing);
        }

        String resolvedAsset = resolveImageAssetPath(tag, attrList);
        SpriteNode sprite = new SpriteNode("sprite:" + tag, tag, attrList);
        sprite.setTransform(transform);
        sprite.setSize(600.0f, 960.0f);

        VJavaAst.LayeredImageDef layeredDef = layeredImageDefinitions.get(tag);
        if (layeredDef != null) {
            LayeredImage layered;
            if (existing != null
                    && existing.getCustomDisplayable() instanceof LayeredImage existingLayered
                    && layeredDef.name().equals(existingLayered.getName())) {
                layered = existingLayered;
                if (glTextureUploadEnabled && textureManager != null && layered.getTextureManager() == null) {
                    layered.setTextureManager(textureManager);
                }
            } else {
                layered = new LayeredImage(layeredDef, glTextureUploadEnabled ? textureManager : null);
            }
            layered.setAttributes(attrList, stateStore, persistentAccess);
            sprite.setCustomDisplayable(layered);
            if (!hasExplicitImageDefinition(tag, attrList) && !layered.getResolvedLayerAssets().isEmpty()) {
                List<String> resolvedLayers = layered.getResolvedLayerAssets();
                resolvedAsset = resolvedLayers.get(resolvedLayers.size() - 1);
            }
            sprite.setAssetPath(resolvedAsset);
        } else {
            sprite.setAssetPath(resolvedAsset);
            if (glTextureUploadEnabled && textureManager != null) {
                Texture tex = textureManager.loadTexture(resolvedAsset);
                sprite.setTexture(tex);
            }
        }

        activeSprites.put(tag, sprite);
        activeSpriteAtl.remove(tag);
        if (resolvedPositionName != null) {
            spritePositionNames.put(tag, resolvedPositionName);
            List<VJavaAst.AtlStep> namedSteps = findNamedTransformSteps(resolvedPositionName);
            if (namedSteps != null && !namedSteps.isEmpty()) {
                sprite.setTransform(new Transform2D());
                attachAtlTransform(tag, namedSteps);
            }
        } else {
            spritePositionNames.remove(tag);
        }
        sceneGraph.addNode(SceneGraph.LAYER_MASTER, sprite);
        notifyTransition("show:" + tag, transitionExpr);
        return sprite;
    }

    /**
     * Hides (removes) a tagged sprite from the master stage layer.
     *
     * @param tag            semantic sprite tag
     * @param transitionExpr optional transition expression (nullable)
     * @return {@code true} if a matching sprite was present and removed
     */
    public boolean hideSprite(String tag, String transitionExpr) {
        if (tag == null) {
            return false;
        }
        SpriteNode removed = activeSprites.remove(tag);
        spritePositionNames.remove(tag);
        activeSpriteAtl.remove(tag);
        if (removed != null) {
            sceneGraph.getLayer(SceneGraph.LAYER_MASTER).removeNode(removed);
            notifyTransition("hide:" + tag, transitionExpr);
            return true;
        }
        return false;
    }

    /**
     * Registers a named image alias (e.g., from {@code image bg_room = "assets/images/bg/room.png"}).
     *
     * @param key       image tag or combined key
     * @param assetPath asset path relative to project root
     */
    public void registerImageDefinition(String key, String assetPath) {
        if (key != null && assetPath != null) {
            imageDefinitions.put(key.trim(), assetPath.trim());
        }
    }

    /**
     * Registers a named {@link Transform2D} preset (e.g., from {@code transform} declarations).
     *
     * @param name      transform name
     * @param transform transform prototype
     */
    public void registerTransform(String name, Transform2D transform) {
        if (name != null && transform != null) {
            namedTransforms.put(name.trim(), transform.copy());
        }
    }

    /**
     * Registers executable ATL keyframes for a named {@code transform} declaration so subsequent
     * {@code show <tag> at <name>} animations play and loop automatically over frames.
     *
     * @param name  transform name
     * @param steps ATL timeline steps
     */
    public void registerTransformSteps(String name, List<VJavaAst.AtlStep> steps) {
        if (name != null && steps != null && !steps.isEmpty()) {
            namedTransformSteps.put(name.trim(), List.copyOf(steps));
        }
    }

    /**
     * Attaches and starts an executable {@link ATLTransform} on an active sprite tag.
     * Clears {@code xalign}/{@code yalign} automatically when {@code xpos}/{@code ypos} are explicitly set.
     *
     * @param tag   target sprite tag
     * @param steps ATL steps to execute
     */
    public void attachAtlTransform(String tag, List<VJavaAst.AtlStep> steps) {
        if (tag == null || steps == null || steps.isEmpty()) {
            return;
        }
        SpriteNode sprite = activeSprites.get(tag);
        if (sprite == null) {
            return;
        }
        Transform2D tf = sprite.getTransform();
        clearAlignIfAbsolutePosInAtl(steps, tf);
        ATLTransform atl = ATLTransform.fromAst(steps);
        atl.start(tf);
        if (!atl.isFinished()) {
            activeSpriteAtl.put(tag, atl);
        } else {
            activeSpriteAtl.remove(tag);
        }
    }

    /**
     * Moves an active sprite immediately to {@code (x, y)} in logical stage coordinates (for Java blocks).
     * Safe no-op if {@code tag} is not currently visible on stage.
     *
     * @param tag sprite tag
     * @param x   target X coordinate
     * @param y   target Y coordinate
     */
    public void moveSprite(String tag, float x, float y) {
        moveSprite(tag, x, y, 0.0, "linear");
    }

    /**
     * Moves an active sprite to {@code (x, y)} immediately or animated over {@code durationSeconds}.
     * Safe no-op if {@code tag} is not currently visible on stage.
     *
     * @param tag             sprite tag
     * @param x               target X coordinate
     * @param y               target Y coordinate
     * @param durationSeconds tween duration in seconds ({@code <= 0} places immediately)
     * @param easing          easing curve name
     */
    public void moveSprite(String tag, float x, float y, double durationSeconds, String easing) {
        if (tag == null || !activeSprites.containsKey(tag)) {
            return;
        }
        motionController.startMove(tag, x, y, false, durationSeconds, easing);
    }

    /**
     * Registers a composite {@link VJavaAst.LayeredImageDef} definition by its tag name so
     * subsequent {@link #showSprite(String, List, String, String)} calls create/update a
     * {@link LayeredImage}-backed {@link SpriteNode}.
     *
     * @param def layered image AST definition
     */
    public void registerLayeredImage(VJavaAst.LayeredImageDef def) {
        if (def != null && def.name() != null && !def.name().isBlank()) {
            layeredImageDefinitions.put(def.name().trim(), def);
        }
    }

    /**
     * Returns a registered {@link VJavaAst.LayeredImageDef} for {@code name}, or {@code null}.
     *
     * @param name layered image tag name
     * @return registered definition or {@code null}
     */
    public VJavaAst.LayeredImageDef getLayeredImageDefinition(String name) {
        return name != null ? layeredImageDefinitions.get(name.trim()) : null;
    }

    /**
     * Binds optional {@link StateStore} and {@link PersistentAccess} context used when evaluating
     * conditional layers inside {@link LayeredImage} sprites.
     *
     * @param stateStore       mutable variable store (nullable)
     * @param persistentAccess persistent storage (nullable)
     */
    public void setStateContext(StateStore stateStore, PersistentAccess persistentAccess) {
        this.stateStore = stateStore;
        this.persistentAccess = persistentAccess;
    }

    /**
     * Advances time-dependent displayables and active ATL keyframe timelines on stage sprites.
     *
     * @param dt elapsed frame delta in seconds
     */
    public void update(double dt) {
        updateAtl(dt);
        updateMotion(dt, ObjectMotionController.MotionInput.NONE);
    }

    /**
     * Advances active ATL keyframe timelines attached to stage sprites by {@code dt} seconds.
     *
     * @param dt elapsed frame delta in seconds
     */
    public void updateAtl(double dt) {
        if (dt <= 0.0 || !Double.isFinite(dt) || activeSpriteAtl.isEmpty()) {
            return;
        }
        var it = activeSpriteAtl.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            SpriteNode sprite = activeSprites.get(entry.getKey());
            if (sprite == null) {
                it.remove();
                continue;
            }
            ATLTransform atl = entry.getValue();
            atl.update(dt, sprite.getTransform());
            if (atl.isFinished()) {
                it.remove();
            }
        }
    }

    /**
     * Dynamically spawns a temporary or pooled sprite instance on the master stage without requiring
     * a prior {@code define}/{@code image} entry when an asset path is supplied.
     *
     * @param tag         unique dynamic sprite tag
     * @param imageOrPath registered image tag or relative asset path
     * @param x           optional initial X coordinate (nullable defaults to centered)
     * @param y           optional initial Y coordinate (nullable defaults to centered)
     * @return spawned {@link SpriteNode}
     */
    public SpriteNode spawnSprite(String tag, String imageOrPath, Float x, Float y) {
        Objects.requireNonNull(tag, "tag must not be null");
        String resolvedPath = (imageOrPath != null && !imageOrPath.isBlank())
                ? resolveImageAssetPath(imageOrPath.trim(), List.of())
                : resolveImageAssetPath(tag, List.of());
        despawnSprite(tag);
        SpriteNode sprite = new SpriteNode("spawn:" + tag, tag, List.of());
        sprite.setAssetPath(resolvedPath);
        sprite.setSize(600.0f, 960.0f);
        Transform2D tf = sprite.getTransform();
        if (x != null && y != null) {
            tf.setXalign(Float.NaN).setYalign(Float.NaN).setXpos(x).setYpos(y);
        } else {
            tf.setXalign(0.5f).setYalign(1.0f);
        }
        if (glTextureUploadEnabled && textureManager != null) {
            sprite.setTexture(textureManager.loadTexture(resolvedPath));
        }
        activeSprites.put(tag, sprite);
        sceneGraph.addNode(SceneGraph.LAYER_MASTER, sprite);
        return sprite;
    }

    /**
     * Despawns {@code tag} completely from the scene graph, sprite map, ATL registry, and motion controller.
     *
     * @param tag sprite tag to despawn
     * @return {@code true} if a sprite was active and removed
     */
    public boolean despawnSprite(String tag) {
        boolean removed = hideSprite(tag, null);
        motionController.removeAllBindingsForTag(tag);
        return removed;
    }

    /**
     * Enables or disables Y-position depth sorting on the master sprite layer.
     *
     * @param enabled {@code true} to render sprites with higher Y in front of sprites with lower Y
     */
    public void setYSortEnabled(boolean enabled) {
        SceneLayer master = sceneGraph.getLayer(SceneGraph.LAYER_MASTER);
        if (master != null) {
            master.setYSortEnabled(enabled);
        }
    }

    /**
     * Sets the global motion/ATL time dilation factor ({@code 1.0f} = normal speed).
     *
     * @param timeScale time dilation factor ({@code >= 0})
     */
    public void setTimeScale(float timeScale) {
        motionController.setTimeScale(timeScale);
    }

    /**
     * Advances active displayables plus sprite movement tweens, WASD controls, 2D physics,
     * camera follow offsets, layer shakes, and collision triggers using {@code motionInput}.
     *
     * @param dt          elapsed frame delta in seconds
     * @param motionInput frame movement and jump input (nullable)
     */
    public void updateMotion(double dt, ObjectMotionController.MotionInput motionInput) {
        double scaledDt = dt * motionController.getTimeScale();
        sceneGraph.update((float) Math.max(0.0, dt));
        if (backgroundNode != null) {
            backgroundNode.update(scaledDt);
        }
        for (SpriteNode sprite : activeSprites.values()) {
            sprite.update(scaledDt);
        }
        motionController.update(dt, motionInput);
        if (motionController.isCameraFollowActive() && backgroundNode != null) {
            backgroundNode.getTransform()
                    .setXalign(Float.NaN)
                    .setYalign(Float.NaN)
                    .setXpos(-motionController.getCameraOffsetX())
                    .setYpos(-motionController.getCameraOffsetY());
        }
    }

    /**
     * Returns the interactive motion, WASD, physics, and collision controller for stage sprites.
     *
     * @return stage {@link ObjectMotionController}
     */
    public ObjectMotionController getMotionController() {
        return motionController;
    }

    /**
     * Captures a deterministic snapshot of the current background and active master sprites.
     *
     * @return stage snapshot
     */
    public StageSnapshot snapshotState() {
        Map<String, StageEntry> entries = new LinkedHashMap<>();
        for (Map.Entry<String, SpriteNode> entry : activeSprites.entrySet()) {
            SpriteNode node = entry.getValue();
            entries.put(entry.getKey(), new StageEntry(
                    node.getImageTag(),
                    node.getAttributes(),
                    spritePositionNames.get(entry.getKey()),
                    node.getAssetPath(),
                    node.getTransform()
            ));
        }
        return new StageSnapshot(
                backgroundTag,
                backgroundNode != null ? backgroundNode.getAssetPath() : null,
                backgroundNode != null ? backgroundNode.getTransform() : new Transform2D(),
                entries,
                motionController.snapshotState()
        );
    }

    /**
     * Restores stage background, sprites, and motion/physics state cleanly from {@link StageSnapshot}.
     *
     * @param snapshot stage snapshot to restore
     */
    public void restoreState(StageSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        clearMasterSprites();
        sceneGraph.getLayer(SceneGraph.LAYER_BACKGROUND).clear();
        backgroundNode = null;
        backgroundTag = null;

        if (snapshot.backgroundTag() != null) {
            setScene(snapshot.backgroundTag(), null);
            if (backgroundNode != null) {
                backgroundNode.setTransform(snapshot.backgroundTransform().copy());
                if (snapshot.backgroundAssetPath() != null) {
                    backgroundNode.setAssetPath(snapshot.backgroundAssetPath());
                }
            }
        }

        for (StageEntry entry : snapshot.activeSprites().values()) {
            SpriteNode node = showSprite(entry.tag(), entry.attributes(), entry.positionName(), null);
            node.setTransform(entry.transform().copy());
            if (entry.assetPath() != null) {
                node.setAssetPath(entry.assetPath());
            }
        }
        motionController.restoreState(snapshot.motionSnapshot());
    }

    /**
     * Clears all sprites on the master layer without modifying the background.
     */
    public void clearMasterSprites() {
        activeSprites.clear();
        spritePositionNames.clear();
        activeSpriteAtl.clear();
        sceneGraph.getLayer(SceneGraph.LAYER_MASTER).clear();
    }

    /**
     * Returns the active sprite node for the given tag.
     *
     * @param tag sprite tag to look up
     * @return active sprite node for {@code tag}, or {@code null}
     */
    public SpriteNode getActiveSprite(String tag) {
        return activeSprites.get(tag);
    }

    /**
     * Returns an immutable view of currently active sprites keyed by tag.
     *
     * @return immutable view of currently active sprites by tag
     */
    public Map<String, SpriteNode> getActiveSprites() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(activeSprites));
    }

    /**
     * Returns the active background tag, if any.
     *
     * @return active background tag or {@code null}
     */
    public String getBackgroundTag() {
        return backgroundTag;
    }

    /**
     * Returns the active background node, if any.
     *
     * @return active background node or {@code null}
     */
    public SpriteNode getBackgroundNode() {
        return backgroundNode;
    }

    /**
     * Returns the most recent transition expression requested.
     *
     * @return most recent transition expression requested, or {@code null}
     */
    public String getLastTransitionExpr() {
        return lastTransitionExpr;
    }

    /**
     * Returns the underlying {@link SceneGraph}.
     *
     * @return underlying {@link SceneGraph}
     */
    public SceneGraph getSceneGraph() {
        return sceneGraph;
    }

    /**
     * Sets a listener invoked whenever {@code setScene}, {@code showSprite}, or {@code hideSprite}
     * requests a visual transition.
     *
     * @param listener consumer receiving {@code (contextKey, transitionExpr)}
     */
    public void setTransitionListener(BiConsumer<String, String> listener) {
        this.transitionListener = listener;
    }

    /**
     * Updates the project root used to resolve relative {@code assets/} files.
     *
     * @param projectRoot project directory path
     */
    public void setProjectRoot(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    private List<VJavaAst.AtlStep> findNamedTransformSteps(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        List<VJavaAst.AtlStep> exact = namedTransformSteps.get(trimmed);
        if (exact != null) {
            return exact;
        }
        return namedTransformSteps.get(trimmed.toLowerCase(Locale.ROOT));
    }

    private static void clearAlignIfAbsolutePosInAtl(List<VJavaAst.AtlStep> steps, Transform2D tf) {
        boolean setsXpos = false;
        boolean setsYpos = false;
        boolean setsXalign = false;
        boolean setsYalign = false;
        for (VJavaAst.AtlStep step : steps) {
            if (step == null) {
                continue;
            }
            for (String rawKey : step.properties().keySet()) {
                if (rawKey == null) {
                    continue;
                }
                String key = rawKey.trim().toLowerCase(Locale.ROOT);
                switch (key) {
                    case "xpos", "pos" -> {
                        setsXpos = true;
                        if ("pos".equals(key)) {
                            setsYpos = true;
                        }
                    }
                    case "ypos" -> setsYpos = true;
                    case "xalign", "align" -> {
                        setsXalign = true;
                        if ("align".equals(key)) {
                            setsYalign = true;
                        }
                    }
                    case "yalign" -> setsYalign = true;
                    default -> {
                    }
                }
            }
            if (!step.children().isEmpty()) {
                clearAlignIfAbsolutePosInAtl(step.children(), tf);
            }
        }
        if (setsXpos && !setsXalign) {
            tf.setXalign(Float.NaN);
        }
        if (setsYpos && !setsYalign) {
            tf.setYalign(Float.NaN);
        }
    }

    private Transform2D resolvePositionTransform(String name) {
        String key = name.trim().toLowerCase(Locale.ROOT);
        Transform2D exact = namedTransforms.get(name.trim());
        if (exact != null) {
            return exact.copy();
        }
        Transform2D lower = namedTransforms.get(key);
        if (lower != null) {
            return lower.copy();
        }
        logger.warn("Scene", "Unknown position/transform '" + name + "', using default Transform2D.");
        return new Transform2D();
    }

    private boolean hasExplicitImageDefinition(String tag, List<String> attributes) {
        if (!attributes.isEmpty() && imageDefinitions.containsKey(tag + " " + String.join(" ", attributes))) {
            return true;
        }
        return imageDefinitions.containsKey(tag);
    }

    private String resolveImageAssetPath(String tag, List<String> attributes) {
        List<String> keysToTry = new ArrayList<>();
        if (!attributes.isEmpty()) {
            keysToTry.add(tag + " " + String.join(" ", attributes));
        }
        keysToTry.add(tag);

        for (String key : keysToTry) {
            String defined = imageDefinitions.get(key);
            if (defined != null) {
                return defined;
            }
        }

        if (tag.contains("/") || tag.endsWith(".png") || tag.endsWith(".jpg") || tag.endsWith(".webp")) {
            return tag;
        }

        String candidateSuffix = attributes.isEmpty()
                ? tag + ".png"
                : tag + "_" + String.join("_", attributes) + ".png";
        List<String> candidates = List.of(
                "assets/images/" + candidateSuffix,
                "assets/images/bg/" + candidateSuffix,
                "assets/images/char/" + candidateSuffix,
                "assets/images/sprites/" + candidateSuffix
        );

        if (projectRoot != null) {
            for (String candidate : candidates) {
                if (Files.isRegularFile(projectRoot.resolve(candidate))) {
                    return candidate;
                }
            }
        }
        return candidates.get(0);
    }

    private void notifyTransition(String context, String transitionExpr) {
        if (transitionExpr != null && !transitionExpr.isBlank()) {
            this.lastTransitionExpr = transitionExpr.trim();
            if (transitionListener != null) {
                transitionListener.accept(context, this.lastTransitionExpr);
            }
        }
    }
}
