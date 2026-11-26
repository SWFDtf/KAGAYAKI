package de.swfdtf.graphics;

import de.swfdtf.audio.LipSyncAnalyzer;
import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.ConditionEvaluator;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.state.PersistentAccess;
import de.swfdtf.state.StateStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Multi-layered composite sprite displayable built from {@link VJavaAst.LayeredImageDef} or via
 * {@link Builder}.
 *
 * <p>Supports unconditional {@code always} base layers, mutually exclusive {@code group}s with
 * {@code default} fallbacks and optional conditions, standalone conditional layers ({@code if ...}),
 * smooth {@code 0.20s} crossfading when a group attribute changes, and real-time lip-sync
 * mouth shape binding via {@link LipSyncAnalyzer}.</p>
 */
public class LayeredImage implements CustomDisplayable {

    /** Default duration in seconds for group attribute crossfades. */
    public static final float DEFAULT_CROSSFADE_DURATION_SECONDS = 0.20f;

    /** Default group name targeted by lip-sync mouth shape updates. */
    public static final String DEFAULT_MOUTH_GROUP_NAME = "mouth";

    private static final Rect2f FULL_UV_RECT = new Rect2f(0.0f, 0.0f, 1.0f, 1.0f);

    /**
     * Immutable render-layer snapshot describing an active layer path and effective opacity.
     *
     * @param assetPath resolved asset path
     * @param alpha     layer opacity multiplier in {@code [0, 1]}
     */
    public record LayerRenderInfo(String assetPath, float alpha) {
    }

    private static final class GroupCrossfadeState {
        String currentAttribute;
        String currentAsset;
        String previousAttribute;
        String previousAsset;
        float progress = 1.0f;

        void transitionTo(String newAttr, String newAsset, float durationSeconds) {
            if (Objects.equals(currentAttribute, newAttr) && Objects.equals(currentAsset, newAsset)) {
                return;
            }
            if (currentAttribute != null && currentAsset != null && !currentAsset.isBlank() && durationSeconds > 0.0f) {
                this.previousAttribute = currentAttribute;
                this.previousAsset = currentAsset;
                this.progress = 0.0f;
            } else {
                this.previousAttribute = null;
                this.previousAsset = null;
                this.progress = 1.0f;
            }
            this.currentAttribute = newAttr;
            this.currentAsset = newAsset;
        }

        void update(double dt, float durationSeconds) {
            if (progress >= 1.0f) {
                return;
            }
            if (durationSeconds <= 0.0f) {
                finish();
                return;
            }
            if (dt > 0.0) {
                progress = Math.min(1.0f, progress + (float) (dt / durationSeconds));
                if (progress >= 1.0f) {
                    finish();
                }
            }
        }

        void finish() {
            progress = 1.0f;
            previousAttribute = null;
            previousAsset = null;
        }

        float previousAlpha() {
            return (previousAsset != null && progress < 1.0f) ? (1.0f - progress) : 0.0f;
        }

        float currentAlpha() {
            return currentAsset != null ? progress : 0.0f;
        }

        boolean isCrossfading() {
            return previousAsset != null && progress < 1.0f;
        }
    }

    /**
     * Fluent programmatic builder for constructing a {@link LayeredImage} without parsing a script file.
     */
    public static final class Builder {
        private final String name;
        private final List<VJavaAst.LayeredEntry> entries = new ArrayList<>();
        private final Map<String, List<VJavaAst.AttributeEntry>> pendingGroups = new LinkedHashMap<>();
        private TextureManager textureManager;
        private float crossfadeDurationSeconds = DEFAULT_CROSSFADE_DURATION_SECONDS;
        private String mouthGroupName = DEFAULT_MOUTH_GROUP_NAME;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name must not be null");
        }

        /**
         * Adds an unconditional {@code always} layer.
         *
         * @param assetPath asset path
         * @return {@code this}
         */
        public Builder always(String assetPath) {
            entries.add(new VJavaAst.AlwaysLayerEntry(Objects.requireNonNull(assetPath, "assetPath must not be null")));
            return this;
        }

        /**
         * Ensures a mutually exclusive attribute group exists in declaration order.
         *
         * @param groupName group identifier
         * @return {@code this}
         */
        public Builder group(String groupName) {
            Objects.requireNonNull(groupName, "groupName must not be null");
            pendingGroups.computeIfAbsent(groupName, k -> {
                List<VJavaAst.AttributeEntry> list = new ArrayList<>();
                entries.add(new VJavaAst.GroupLayerEntry(k, list));
                return list;
            });
            return this;
        }

        /**
         * Adds an attribute option to a named group (creating the group if needed).
         *
         * @param groupName group identifier
         * @param attrName  attribute name
         * @param isDefault whether this attribute is the group's default
         * @param assetPath asset path
         * @return {@code this}
         */
        public Builder attribute(String groupName, String attrName, boolean isDefault, String assetPath) {
            return attribute(groupName, attrName, isDefault, assetPath, null);
        }

        /**
         * Adds a conditionally guarded attribute option to a named group.
         *
         * @param groupName     group identifier
         * @param attrName      attribute name
         * @param isDefault     whether this attribute is the group's default
         * @param assetPath     asset path
         * @param conditionExpr optional condition expression (nullable)
         * @return {@code this}
         */
        public Builder attribute(
                String groupName,
                String attrName,
                boolean isDefault,
                String assetPath,
                String conditionExpr
        ) {
            Objects.requireNonNull(groupName, "groupName must not be null");
            Objects.requireNonNull(attrName, "attrName must not be null");
            group(groupName);
            pendingGroups.get(groupName).add(new VJavaAst.AttributeEntry(
                    attrName,
                    isDefault,
                    assetPath != null ? assetPath : "",
                    conditionExpr
            ));
            return this;
        }

        /**
         * Adds a conditional overlay layer ({@code if <conditionExpr>:}).
         *
         * @param conditionExpr boolean condition expression
         * @param assetPath     asset path
         * @return {@code this}
         */
        public Builder conditional(String conditionExpr, String assetPath) {
            Objects.requireNonNull(conditionExpr, "conditionExpr must not be null");
            Objects.requireNonNull(assetPath, "assetPath must not be null");
            entries.add(new VJavaAst.ConditionalLayerEntry(conditionExpr, assetPath));
            return this;
        }

        /**
         * Sets an optional {@link TextureManager} for layer rendering.
         *
         * @param textureManager texture manager (nullable)
         * @return {@code this}
         */
        public Builder textureManager(TextureManager textureManager) {
            this.textureManager = textureManager;
            return this;
        }

        /**
         * Configures the crossfade duration in seconds when group attributes change.
         *
         * @param seconds crossfade duration in seconds ({@code >= 0})
         * @return {@code this}
         */
        public Builder crossfadeDuration(float seconds) {
            this.crossfadeDurationSeconds = Math.max(0.0f, seconds);
            return this;
        }

        /**
         * Sets the group name driven by lip-sync mouth shapes (default {@code "mouth"}).
         *
         * @param mouthGroupName group name
         * @return {@code this}
         */
        public Builder mouthGroupName(String mouthGroupName) {
            this.mouthGroupName = mouthGroupName;
            return this;
        }

        /**
         * Builds the configured {@link LayeredImage}.
         *
         * @return constructed {@link LayeredImage}
         */
        public LayeredImage build() {
            List<VJavaAst.LayeredEntry> finalized = new ArrayList<>(entries.size());
            for (VJavaAst.LayeredEntry entry : entries) {
                if (entry instanceof VJavaAst.GroupLayerEntry g) {
                    finalized.add(new VJavaAst.GroupLayerEntry(g.groupName(), pendingGroups.get(g.groupName())));
                } else {
                    finalized.add(entry);
                }
            }
            VJavaAst.LayeredImageDef def = new VJavaAst.LayeredImageDef(name, finalized, "", 0);
            LayeredImage img = new LayeredImage(def, textureManager);
            img.setCrossfadeDurationSeconds(crossfadeDurationSeconds);
            img.setMouthGroupName(mouthGroupName);
            return img;
        }
    }

    private final String name;
    private final List<VJavaAst.LayeredEntry> entries;
    private final Map<String, VJavaAst.GroupLayerEntry> groupsByName = new LinkedHashMap<>();
    private final Map<String, GroupCrossfadeState> groupStates = new LinkedHashMap<>();
    private final Map<String, Texture> preloadedTextures = new LinkedHashMap<>();

    private TextureManager textureManager;
    private float crossfadeDurationSeconds = DEFAULT_CROSSFADE_DURATION_SECONDS;
    private String mouthGroupName = DEFAULT_MOUTH_GROUP_NAME;
    private LipSyncAnalyzer boundLipSync;
    private LipSyncAnalyzer.MouthShape currentMouthShape;

    private List<String> selectedAttributes = List.of();
    private StateStore lastState;
    private PersistentAccess lastPersistent;
    private List<String> resolvedLayerAssets = List.of();

    /**
     * Creates a {@link LayeredImage} from a parsed {@link VJavaAst.LayeredImageDef} without a {@link TextureManager}.
     *
     * @param def layered image AST definition
     */
    public LayeredImage(VJavaAst.LayeredImageDef def) {
        this(def, null);
    }

    /**
     * Creates a {@link LayeredImage} from a parsed {@link VJavaAst.LayeredImageDef} and optional
     * {@link TextureManager}.
     *
     * @param def            layered image AST definition
     * @param textureManager optional texture manager for GPU texture loading (nullable in headless tests)
     */
    public LayeredImage(VJavaAst.LayeredImageDef def, TextureManager textureManager) {
        Objects.requireNonNull(def, "def must not be null");
        this.name = def.name();
        this.entries = def.entries();
        this.textureManager = textureManager;
        for (VJavaAst.LayeredEntry entry : entries) {
            if (entry instanceof VJavaAst.GroupLayerEntry group) {
                groupsByName.put(group.groupName(), group);
                groupStates.put(group.groupName(), new GroupCrossfadeState());
            }
        }
        setAttributes(List.of(), null, null);
    }

    /**
     * Creates a fluent {@link Builder} for constructing a {@link LayeredImage}.
     *
     * @param name composite image tag name
     * @return builder instance
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * Updates active attributes without state context (uses previously provided state or {@code null}).
     *
     * @param attributes requested attribute tokens
     */
    public void setAttributes(Collection<String> attributes) {
        setAttributes(attributes, lastState, lastPersistent);
    }

    /**
     * Applies active attribute tokens and evaluates all {@code always}, {@code group}, and {@code if}
     * layers deterministically against {@code state} and {@code persistent}.
     *
     * <p>When the resolved attribute inside a {@code group} changes from its previous value,
     * a smooth crossfade over {@link #getCrossfadeDurationSeconds()} is initiated.</p>
     *
     * @param attributes requested attribute tokens (nullable)
     * @param state      mutable variable store for condition evaluation (nullable)
     * @param persistent persistent storage for condition evaluation (nullable)
     */
    public void setAttributes(Collection<String> attributes, StateStore state, PersistentAccess persistent) {
        this.lastState = state;
        this.lastPersistent = persistent;
        if (attributes == null || attributes.isEmpty()) {
            this.selectedAttributes = List.of();
        } else {
            List<String> clean = new ArrayList<>(attributes.size());
            for (String attr : attributes) {
                if (attr != null && !attr.isBlank()) {
                    clean.add(attr.trim());
                }
            }
            this.selectedAttributes = Collections.unmodifiableList(clean);
        }
        resolveLayers(state, persistent);
    }

    /**
     * Re-evaluates all conditional and group layers against the provided state context without
     * altering the currently selected attribute list.
     *
     * @param state      mutable variable store (nullable)
     * @param persistent persistent store (nullable)
     */
    public void resolve(StateStore state, PersistentAccess persistent) {
        this.lastState = state;
        this.lastPersistent = persistent;
        resolveLayers(state, persistent);
    }

    private void resolveLayers(StateStore state, PersistentAccess persistent) {
        Set<String> requested = new LinkedHashSet<>(selectedAttributes);
        List<String> resolved = new ArrayList<>();

        for (VJavaAst.LayeredEntry entry : entries) {
            if (entry instanceof VJavaAst.AlwaysLayerEntry always) {
                String path = resolveAssetExpression(always.assetOrExpr(), state, persistent);
                if (!path.isEmpty()) {
                    resolved.add(path);
                }
            } else if (entry instanceof VJavaAst.ConditionalLayerEntry cond) {
                if (evaluateConditionSafe(cond.conditionExpr(), state, persistent)) {
                    String path = resolveAssetExpression(cond.assetOrExpr(), state, persistent);
                    if (!path.isEmpty()) {
                        resolved.add(path);
                    }
                }
            } else if (entry instanceof VJavaAst.GroupLayerEntry group) {
                VJavaAst.AttributeEntry chosen = resolveGroupAttribute(group, requested, state, persistent);
                GroupCrossfadeState groupState = groupStates.get(group.groupName());
                if (chosen != null) {
                    String asset = resolveAssetExpression(chosen.assetOrExpr(), state, persistent);
                    groupState.transitionTo(chosen.name(), asset, crossfadeDurationSeconds);
                    if (!asset.isEmpty()) {
                        resolved.add(asset);
                    }
                } else {
                    groupState.transitionTo(null, null, crossfadeDurationSeconds);
                }
            }
        }
        this.resolvedLayerAssets = Collections.unmodifiableList(resolved);
    }

    private VJavaAst.AttributeEntry resolveGroupAttribute(
            VJavaAst.GroupLayerEntry group,
            Set<String> requested,
            StateStore state,
            PersistentAccess persistent
    ) {
        VJavaAst.AttributeEntry matchedExplicit = null;
        for (String token : requested) {
            for (VJavaAst.AttributeEntry attr : group.attributes()) {
                if (attr.name().equals(token) && isAttributeConditionMet(attr, state, persistent)) {
                    matchedExplicit = attr;
                }
            }
        }
        if (matchedExplicit != null) {
            return matchedExplicit;
        }

        if (group.groupName().equals(mouthGroupName) && currentMouthShape != null) {
            VJavaAst.AttributeEntry lipAttr = findMouthAttribute(group, currentMouthShape, state, persistent);
            if (lipAttr != null) {
                return lipAttr;
            }
        }

        for (VJavaAst.AttributeEntry attr : group.attributes()) {
            if (attr.isDefault() && isAttributeConditionMet(attr, state, persistent)) {
                return attr;
            }
        }
        return null;
    }

    private VJavaAst.AttributeEntry findMouthAttribute(
            VJavaAst.GroupLayerEntry group,
            LipSyncAnalyzer.MouthShape shape,
            StateStore state,
            PersistentAccess persistent
    ) {
        String primaryName = switch (shape) {
            case CLOSED -> "closed";
            case HALF_OPEN -> "half";
            case OPEN -> "open";
        };
        for (VJavaAst.AttributeEntry attr : group.attributes()) {
            if (attr.name().equalsIgnoreCase(primaryName) && isAttributeConditionMet(attr, state, persistent)) {
                return attr;
            }
        }
        if (shape == LipSyncAnalyzer.MouthShape.HALF_OPEN) {
            for (VJavaAst.AttributeEntry attr : group.attributes()) {
                if ("half_open".equalsIgnoreCase(attr.name()) && isAttributeConditionMet(attr, state, persistent)) {
                    return attr;
                }
            }
        }
        return null;
    }

    private boolean isAttributeConditionMet(
            VJavaAst.AttributeEntry attr,
            StateStore state,
            PersistentAccess persistent
    ) {
        if (attr.conditionExpr() == null || attr.conditionExpr().isBlank()) {
            return true;
        }
        return evaluateConditionSafe(attr.conditionExpr(), state, persistent);
    }

    private boolean evaluateConditionSafe(String expr, StateStore state, PersistentAccess persistent) {
        if (expr == null || expr.isBlank()) {
            return false;
        }
        if ("true".equalsIgnoreCase(expr.trim())) {
            return true;
        }
        try {
            return ConditionEvaluator.evaluateBoolean(expr, state, persistent);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private String resolveAssetExpression(String raw, StateStore state, PersistentAccess persistent) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.length() >= 2 && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        if ((trimmed.contains("+") || trimmed.contains("?")) && (state != null || persistent != null)) {
            try {
                Object evaluated = ConditionEvaluator.evaluate(trimmed, state, persistent);
                if (evaluated != null) {
                    return String.valueOf(evaluated);
                }
            } catch (RuntimeException ignored) {
                // Treat as literal path when not a valid runtime expression
            }
        }
        return trimmed;
    }

    /**
     * Binds a {@link LipSyncAnalyzer} whose {@link LipSyncAnalyzer#getMouthShape()} will
     * automatically drive the configured mouth group during {@link #update(double)}.
     *
     * @param analyzer lip-sync analyzer (nullable to unbind)
     */
    public void bindLipSync(LipSyncAnalyzer analyzer) {
        this.boundLipSync = analyzer;
        if (analyzer != null) {
            applyMouthShape(analyzer.getMouthShape());
        }
    }

    /**
     * Applies a discrete {@link LipSyncAnalyzer.MouthShape} to the configured mouth group
     * ({@code "closed"}, {@code "half"}, or {@code "open"}) if that group and attribute exist.
     *
     * @param shape mouth shape (nullable; null clears override)
     * @return {@code true} if a matching attribute was found in the mouth group
     */
    public boolean applyMouthShape(LipSyncAnalyzer.MouthShape shape) {
        this.currentMouthShape = shape;
        if (shape == null || mouthGroupName == null) {
            return false;
        }
        VJavaAst.GroupLayerEntry group = groupsByName.get(mouthGroupName);
        if (group == null) {
            return false;
        }
        VJavaAst.AttributeEntry matched = findMouthAttribute(group, shape, lastState, lastPersistent);
        if (matched == null) {
            return false;
        }
        GroupCrossfadeState groupState = groupStates.get(mouthGroupName);
        String asset = resolveAssetExpression(matched.assetOrExpr(), lastState, lastPersistent);
        groupState.transitionTo(matched.name(), asset, crossfadeDurationSeconds);
        rebuildResolvedAssetsFromCurrentState();
        return true;
    }

    private void rebuildResolvedAssetsFromCurrentState() {
        List<String> resolved = new ArrayList<>();
        for (VJavaAst.LayeredEntry entry : entries) {
            if (entry instanceof VJavaAst.AlwaysLayerEntry always) {
                String path = resolveAssetExpression(always.assetOrExpr(), lastState, lastPersistent);
                if (!path.isEmpty()) {
                    resolved.add(path);
                }
            } else if (entry instanceof VJavaAst.ConditionalLayerEntry cond) {
                if (evaluateConditionSafe(cond.conditionExpr(), lastState, lastPersistent)) {
                    String path = resolveAssetExpression(cond.assetOrExpr(), lastState, lastPersistent);
                    if (!path.isEmpty()) {
                        resolved.add(path);
                    }
                }
            } else if (entry instanceof VJavaAst.GroupLayerEntry group) {
                GroupCrossfadeState st = groupStates.get(group.groupName());
                if (st != null && st.currentAsset != null && !st.currentAsset.isEmpty()) {
                    resolved.add(st.currentAsset);
                }
            }
        }
        this.resolvedLayerAssets = Collections.unmodifiableList(resolved);
    }

    @Override
    public void update(double dt) {
        if (boundLipSync != null) {
            applyMouthShape(boundLipSync.getMouthShape());
        }
        for (GroupCrossfadeState st : groupStates.values()) {
            st.update(dt, crossfadeDurationSeconds);
        }
    }

    @Override
    public void render(RenderPipeline2D pipeline, Rect2f bounds, float alpha, ColorMatrix4f colorMatrix) {
        if (pipeline == null || !pipeline.isInitialized() || bounds == null
                || bounds.width() <= 0.0f || bounds.height() <= 0.0f || alpha <= 0.0f) {
            return;
        }
        float clampedAlpha = Math.clamp(alpha, 0.0f, 1.0f);
        ColorMatrix4f matrix = colorMatrix != null ? colorMatrix : ColorMatrix4f.identity();

        for (LayerRenderInfo layer : getActiveRenderLayers()) {
            float layerAlpha = Math.clamp(clampedAlpha * layer.alpha(), 0.0f, 1.0f);
            if (layerAlpha <= 0.0f) {
                continue;
            }
            Texture tex = resolveTexture(layer.assetPath());
            if (tex != null && tex.getTextureId() != 0) {
                pipeline.drawTexturedQuad(
                        tex.getTextureId(),
                        bounds.x(),
                        bounds.y(),
                        bounds.width(),
                        bounds.height(),
                        FULL_UV_RECT,
                        new float[]{1.0f, 1.0f, 1.0f, layerAlpha},
                        matrix
                );
            }
        }
    }

    @Override
    public float getPreferredWidth() {
        for (String path : resolvedLayerAssets) {
            Texture tex = peekTexture(path);
            if (tex != null && tex.getWidth() > 0) {
                return tex.getWidth();
            }
        }
        return 0.0f;
    }

    @Override
    public float getPreferredHeight() {
        for (String path : resolvedLayerAssets) {
            Texture tex = peekTexture(path);
            if (tex != null && tex.getHeight() > 0) {
                return tex.getHeight();
            }
        }
        return 0.0f;
    }

    private Texture resolveTexture(String assetPath) {
        Texture cached = preloadedTextures.get(assetPath);
        if (cached != null) {
            return cached;
        }
        if (textureManager != null && assetPath != null && !assetPath.isBlank()) {
            return textureManager.loadTexture(assetPath);
        }
        return null;
    }

    private Texture peekTexture(String assetPath) {
        Texture cached = preloadedTextures.get(assetPath);
        if (cached != null) {
            return cached;
        }
        return textureManager != null ? textureManager.getCached(assetPath) : null;
    }

    /**
     * Returns all currently active render layers in bottom-to-top drawing order, including
     * outgoing ({@code 1 -> 0}) and incoming ({@code 0 -> 1}) crossfade layers for transitioning groups.
     *
     * @return ordered list of {@link LayerRenderInfo}
     */
    public List<LayerRenderInfo> getActiveRenderLayers() {
        List<LayerRenderInfo> layers = new ArrayList<>();
        for (VJavaAst.LayeredEntry entry : entries) {
            if (entry instanceof VJavaAst.AlwaysLayerEntry always) {
                String path = resolveAssetExpression(always.assetOrExpr(), lastState, lastPersistent);
                if (!path.isEmpty()) {
                    layers.add(new LayerRenderInfo(path, 1.0f));
                }
            } else if (entry instanceof VJavaAst.ConditionalLayerEntry cond) {
                if (evaluateConditionSafe(cond.conditionExpr(), lastState, lastPersistent)) {
                    String path = resolveAssetExpression(cond.assetOrExpr(), lastState, lastPersistent);
                    if (!path.isEmpty()) {
                        layers.add(new LayerRenderInfo(path, 1.0f));
                    }
                }
            } else if (entry instanceof VJavaAst.GroupLayerEntry group) {
                GroupCrossfadeState st = groupStates.get(group.groupName());
                if (st != null) {
                    if (st.isCrossfading() && st.previousAsset != null && !st.previousAsset.isEmpty()) {
                        layers.add(new LayerRenderInfo(st.previousAsset, st.previousAlpha()));
                    }
                    if (st.currentAsset != null && !st.currentAsset.isEmpty()) {
                        layers.add(new LayerRenderInfo(st.currentAsset, st.currentAlpha()));
                    }
                }
            }
        }
        return Collections.unmodifiableList(layers);
    }

    /**
     * Registers a pre-loaded {@link Texture} for an explicit layer asset path.
     *
     * @param assetPath layer asset path
     * @param texture   texture instance
     */
    public void registerLayerTexture(String assetPath, Texture texture) {
        if (assetPath != null && texture != null) {
            preloadedTextures.put(assetPath, texture);
        }
    }

    /**
     * Returns the composite layered image name.
     *
     * @return composite layered image name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the ordered list of currently resolved active layer asset paths.
     *
     * @return ordered list of currently resolved active layer asset paths
     */
    public List<String> getResolvedLayerAssets() {
        return resolvedLayerAssets;
    }

    /**
     * Returns an unmodifiable list of requested attributes.
     *
     * @return unmodifiable list of requested attributes
     */
    public List<String> getSelectedAttributes() {
        return selectedAttributes;
    }

    /**
     * Returns the active attribute name inside {@code groupName}.
     *
     * @param groupName attribute group name
     * @return active attribute name inside {@code groupName}, or {@code null}
     */
    public String getActiveAttribute(String groupName) {
        GroupCrossfadeState st = groupStates.get(groupName);
        return st != null ? st.currentAttribute : null;
    }

    /**
     * Returns a map of group name to currently active attribute name.
     *
     * @return map of group name to currently active attribute name
     */
    public Map<String, String> getActiveGroupAttributes() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, GroupCrossfadeState> e : groupStates.entrySet()) {
            if (e.getValue().currentAttribute != null) {
                map.put(e.getKey(), e.getValue().currentAttribute);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * Returns the outgoing layer alpha for {@code groupName} during a crossfade.
     *
     * @param groupName attribute group name
     * @return outgoing layer alpha in {@code [0, 1]} for {@code groupName} during crossfade, else {@code 0}
     */
    public float getGroupPreviousAlpha(String groupName) {
        GroupCrossfadeState st = groupStates.get(groupName);
        return st != null ? st.previousAlpha() : 0.0f;
    }

    /**
     * Returns the incoming layer alpha for {@code groupName} during a crossfade.
     *
     * @param groupName attribute group name
     * @return incoming layer alpha in {@code [0, 1]} for {@code groupName} during crossfade, else {@code 1}
     */
    public float getGroupCurrentAlpha(String groupName) {
        GroupCrossfadeState st = groupStates.get(groupName);
        return st != null ? st.currentAlpha() : 0.0f;
    }

    /**
     * Returns the crossfade progress for {@code groupName}.
     *
     * @param groupName attribute group name
     * @return crossfade progress in {@code [0, 1]} for {@code groupName}
     */
    public float getGroupFadeProgress(String groupName) {
        GroupCrossfadeState st = groupStates.get(groupName);
        return st != null ? st.progress : 1.0f;
    }

    /**
     * Returns whether any group is currently crossfading.
     *
     * @return {@code true} if any group is currently crossfading
     */
    public boolean isCrossfading() {
        for (GroupCrossfadeState st : groupStates.values()) {
            if (st.isCrossfading()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the crossfade duration in seconds.
     *
     * @return crossfade duration in seconds
     */
    public float getCrossfadeDurationSeconds() {
        return crossfadeDurationSeconds;
    }

    /**
     * Sets the crossfade duration in seconds for group attribute transitions.
     *
     * @param crossfadeDurationSeconds duration in seconds ({@code >= 0})
     */
    public void setCrossfadeDurationSeconds(float crossfadeDurationSeconds) {
        this.crossfadeDurationSeconds = Float.isFinite(crossfadeDurationSeconds)
                ? Math.max(0.0f, crossfadeDurationSeconds)
                : DEFAULT_CROSSFADE_DURATION_SECONDS;
    }

    /**
     * Returns the group name targeted by lip-sync mouth shape changes.
     *
     * @return group name targeted by lip-sync mouth shape changes
     */
    public String getMouthGroupName() {
        return mouthGroupName;
    }

    /**
     * Sets the group name targeted by {@link #applyMouthShape(LipSyncAnalyzer.MouthShape)}.
     *
     * @param mouthGroupName group name (defaults to {@code "mouth"} if null or blank)
     */
    public void setMouthGroupName(String mouthGroupName) {
        this.mouthGroupName = (mouthGroupName == null || mouthGroupName.isBlank())
                ? DEFAULT_MOUTH_GROUP_NAME
                : mouthGroupName.trim();
    }

    /**
     * Returns the currently bound lip-sync analyzer.
     *
     * @return currently bound {@link LipSyncAnalyzer}, or {@code null}
     */
    public LipSyncAnalyzer getBoundLipSync() {
        return boundLipSync;
    }

    /**
     * Returns the optional texture manager.
     *
     * @return optional {@link TextureManager}
     */
    public TextureManager getTextureManager() {
        return textureManager;
    }

    /**
     * Sets or replaces the {@link TextureManager} used to load layer textures.
     *
     * @param textureManager texture manager (nullable)
     */
    public void setTextureManager(TextureManager textureManager) {
        this.textureManager = textureManager;
    }
}
