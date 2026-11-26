package de.swfdtf.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import de.swfdtf.audio.TextToSpeechService;
import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.graphics.StyledTextLayout;
import de.swfdtf.graphics.TextTagParser;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Comic/manga speech bubble dialogue screen ({@code Z = 200}) with dynamic sprite-targeting
 * pointer tails, multi-bubble retain mode, and an integrated {@code Shift+B} interactive editor.
 */
public class BubbleDialogueScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "bubble_dialogue";

    /** Filename used to persist bubble layout overrides inside the saves directory. */
    public static final String PRESETS_FILE_NAME = "bubble_layouts.json";

    /** Default bubble width in logical pixels. */
    public static final float DEFAULT_BOX_W = 540.0f;

    /** Default bubble height in logical pixels. */
    public static final float DEFAULT_BOX_H = 160.0f;

    private static final float TAIL_HANDLE_RADIUS = 16.0f;
    private static final float TAIL_HIT_RADIUS = 24.0f;
    private static final int TAIL_SEGMENTS = 8;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type PRESET_MAP_TYPE = new TypeToken<Map<String, BubbleLayoutConfig>>() { }.getType();

    /**
     * Serializable speech bubble geometry and tail target coordinates in logical canvas pixels.
     *
     * @param boxX  bubble left X
     * @param boxY  bubble top Y
     * @param boxW  bubble width
     * @param boxH  bubble height
     * @param tailX tail tip X
     * @param tailY tail tip Y
     */
    public record BubbleLayoutConfig(
            float boxX,
            float boxY,
            float boxW,
            float boxH,
            float tailX,
            float tailY
    ) {
        /**
         * Checks whether {@code (lx, ly)} falls inside the bubble rectangle.
         *
         * @param lx logical X coordinate
         * @param ly logical Y coordinate
         * @return {@code true} if {@code (lx, ly)} falls inside the bubble rectangle
         */
        public boolean containsBox(float lx, float ly) {
            return lx >= boxX && lx <= boxX + boxW && ly >= boxY && ly <= boxY + boxH;
        }

        /**
         * Checks whether {@code (lx, ly)} is within grab radius of the tail tip.
         *
         * @param lx     logical X coordinate
         * @param ly     logical Y coordinate
         * @param radius hit grab radius
         * @return {@code true} if {@code (lx, ly)} is within grab radius of the tail tip
         */
        public boolean isNearTailTip(float lx, float ly, float radius) {
            float dx = lx - tailX;
            float dy = ly - tailY;
            return (dx * dx + dy * dy) <= radius * radius;
        }
    }

    /**
     * Rendered speech bubble entry (either currently active or retained from earlier lines).
     *
     * @param lineId          dialogue line identifier
     * @param speakerId       speaker tag (nullable)
     * @param speakerName     resolved display name (nullable)
     * @param speakerColorHex speaker accent hex color (nullable)
     * @param text            interpolated dialogue text
     * @param layout          bubble box and tail geometry
     */
    public record BubbleEntry(
            String lineId,
            String speakerId,
            String speakerName,
            String speakerColorHex,
            String text,
            BubbleLayoutConfig layout
    ) {
    }

    private enum DragTarget {
        NONE,
        BOX,
        TAIL
    }

    private final ScriptSession session;
    private final BubbleCanvasNode canvasNode;
    private final Map<String, BubbleLayoutConfig> layoutPresets = new LinkedHashMap<>();
    private final List<BubbleEntry> retainedBubbles = new ArrayList<>();

    private boolean retainPreviousBubbles = false;
    private boolean editorMode = false;
    private DragTarget dragTarget = DragTarget.NONE;
    private float dragOffsetX = 0.0f;
    private float dragOffsetY = 0.0f;

    private ScriptRuntime.DialoguePrompt lastPrompt;
    private BubbleEntry activeBubble;
    private StyledTextLayout.TypewriterController activeTypewriter;

    /**
     * Creates a {@code BubbleDialogueScreen} bound to {@code session} and loads saved presets if present.
     *
     * @param session active script session
     */
    public BubbleDialogueScreen(ScriptSession session) {
        super(SCREEN_NAME, SceneLayer.Z_SCREENS, false);
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.canvasNode = new BubbleCanvasNode();
        setRoot(this.canvasNode);

        loadPresetsFromDisk();
        addKeyBinding(GLFW.GLFW_KEY_B, GLFW.GLFW_MOD_SHIFT, this::toggleEditorMode);
        addKeyBinding(GLFW.GLFW_KEY_SPACE, this::handlePrimaryAdvance);
        addKeyBinding(GLFW.GLFW_KEY_ENTER, this::handlePrimaryAdvance);
        addKeyBinding(GLFW.GLFW_KEY_KP_ENTER, this::handlePrimaryAdvance);
    }

    @Override
    public void onShow() {
        syncFromRuntime(true);
    }

    @Override
    public void update(double dt) {
        if (!isVisible()) {
            return;
        }
        syncFromRuntime(false);
        super.update(dt);
        if (activeTypewriter != null && dt >= 0.0) {
            activeTypewriter.advance(dt);
        }
    }

    /**
     * Toggles the integrated {@code Shift+B} bubble layout editor.
     *
     * @return new editor active state
     */
    public boolean toggleEditorMode() {
        setEditorMode(!editorMode);
        return editorMode;
    }

    /**
     * Enables or disables the integrated bubble layout editor.
     *
     * @param active editor active flag
     */
    public void setEditorMode(boolean active) {
        this.editorMode = active;
        if (!active) {
            this.dragTarget = DragTarget.NONE;
            savePresetsToDisk();
        }
    }

    /**
     * Returns whether the {@code Shift+B} bubble editor is currently active.
     *
     * @return {@code true} if active
     */
    public boolean isEditorActive() {
        return editorMode;
    }

    /**
     * Enables or disables retain mode (keeping prior speech bubbles visible simultaneously).
     *
     * @param retainPreviousBubbles {@code true} to retain prior bubbles
     */
    public void setRetainPreviousBubbles(boolean retainPreviousBubbles) {
        this.retainPreviousBubbles = retainPreviousBubbles;
        if (!retainPreviousBubbles) {
            retainedBubbles.clear();
        }
    }

    /**
     * Returns whether retain mode is active.
     *
     * @return {@code true} if retain mode is active
     */
    public boolean isRetainPreviousBubbles() {
        return retainPreviousBubbles;
    }

    /**
     * Clears all retained historical speech bubbles.
     */
    public void clearRetainedBubbles() {
        retainedBubbles.clear();
    }

    /**
     * Stores a layout preset by key (line ID or speaker ID) and updates the active bubble if matching.
     *
     * @param key    line ID or speaker ID
     * @param config layout coordinates
     */
    public void setLayoutPreset(String key, BubbleLayoutConfig config) {
        if (key == null || key.isBlank() || config == null) {
            return;
        }
        layoutPresets.put(key.trim(), config);
        if (activeBubble != null && (key.equals(activeBubble.lineId()) || key.equals(activeBubble.speakerId()))) {
            activeBubble = new BubbleEntry(
                    activeBubble.lineId(),
                    activeBubble.speakerId(),
                    activeBubble.speakerName(),
                    activeBubble.speakerColorHex(),
                    activeBubble.text(),
                    config
            );
        }
    }

    /**
     * Retrieves a stored layout preset for {@code key}, or {@code null} if absent.
     *
     * @param key line ID or speaker ID
     * @return stored {@link BubbleLayoutConfig} or {@code null}
     */
    public BubbleLayoutConfig getLayoutPreset(String key) {
        return key != null ? layoutPresets.get(key) : null;
    }

    /**
     * Returns an unmodifiable view of all layout presets.
     *
     * @return layout presets map
     */
    public Map<String, BubbleLayoutConfig> getLayoutPresets() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(layoutPresets));
    }

    /**
     * Returns the currently active speech bubble, or {@code null}.
     *
     * @return active speech bubble
     */
    public BubbleEntry getActiveBubble() {
        return activeBubble;
    }

    /**
     * Returns an unmodifiable list of retained prior speech bubbles (excluding current).
     *
     * @return retained bubbles
     */
    public List<BubbleEntry> getRetainedBubbles() {
        return Collections.unmodifiableList(retainedBubbles);
    }

    /**
     * Returns total visible bubbles (retained + active).
     *
     * @return visible bubble count
     */
    public int getVisibleBubbleCount() {
        return retainedBubbles.size() + (activeBubble != null ? 1 : 0);
    }

    /**
     * Advances the active typewriter reveal or advances the script to the next line.
     */
    public void handlePrimaryAdvance() {
        if (editorMode) {
            return;
        }
        if (activeTypewriter != null && !activeTypewriter.isFinished()) {
            activeTypewriter.clickOrAdvance();
            return;
        }
        session.advance();
        syncFromRuntime(false);
    }

    /**
     * Synchronizes active and retained bubbles from {@link ScriptRuntime#getActiveDialogue()}.
     *
     * @param force {@code true} to rebuild even when prompt is unchanged
     */
    public void syncFromRuntime(boolean force) {
        ScriptRuntime runtime = session.getRuntime();
        ScriptRuntime.DialoguePrompt prompt = runtime.getActiveDialogue();
        if (!force && Objects.equals(prompt, lastPrompt)) {
            return;
        }

        if (retainPreviousBubbles && activeBubble != null && prompt != null && !Objects.equals(prompt, lastPrompt)) {
            if (!Objects.equals(activeBubble.lineId(), prompt.lineId())) {
                retainedBubbles.add(activeBubble);
            }
        }

        lastPrompt = prompt;
        if (prompt == null) {
            activeBubble = null;
            activeTypewriter = null;
            return;
        }

        BubbleLayoutConfig layout = resolveLayoutForPrompt(prompt);
        String text = prompt.interpolatedText() != null ? prompt.interpolatedText() : "";
        activeBubble = new BubbleEntry(
                prompt.lineId(),
                prompt.speakerId(),
                prompt.speakerName(),
                prompt.speakerColorHex(),
                text,
                layout
        );

        float cps = session.getPreferencesState().getTextSpeedCps();
        activeTypewriter = new StyledTextLayout.TypewriterController(TextTagParser.parse(text), cps);

        if (session.getPreferencesState().isSelfVoicing()) {
            TextToSpeechService tts = UiMediaSupport.findTtsService(session);
            if (tts != null) {
                tts.setEnabled(true);
                tts.speak(text);
            }
        }
    }

    /**
     * Computes the default or preset-overridden {@link BubbleLayoutConfig} for {@code prompt}.
     *
     * @param prompt dialogue prompt
     * @return resolved bubble layout configuration
     */
    public BubbleLayoutConfig resolveLayoutForPrompt(ScriptRuntime.DialoguePrompt prompt) {
        if (prompt.lineId() != null && layoutPresets.containsKey(prompt.lineId())) {
            return layoutPresets.get(prompt.lineId());
        }
        if (prompt.speakerId() != null && layoutPresets.containsKey(prompt.speakerId())) {
            return layoutPresets.get(prompt.speakerId());
        }
        Vec2f anchor = resolveSpeakerAnchor(prompt.speakerId());
        float boxW = DEFAULT_BOX_W;
        float boxH = DEFAULT_BOX_H;
        float boxX = clamp(anchor.x() - boxW * 0.5f, 40.0f, RenderPipeline2D.DEFAULT_LOGICAL_WIDTH - boxW - 40.0f);
        float boxY = clamp(anchor.y() - boxH - 95.0f, 40.0f, RenderPipeline2D.DEFAULT_LOGICAL_HEIGHT - boxH - 120.0f);
        return new BubbleLayoutConfig(boxX, boxY, boxW, boxH, anchor.x(), anchor.y());
    }

    /**
     * Resolves the logical screen anchor point for {@code speakerId} from {@link SceneController}.
     *
     * @param speakerId speaker character tag
     * @return screen-space tail target {@link Vec2f}
     */
    public Vec2f resolveSpeakerAnchor(String speakerId) {
        SceneController scene = session.getSceneController();
        if (scene != null && speakerId != null && !speakerId.isBlank()) {
            SpriteNode sprite = scene.getActiveSprite(speakerId);
            if (sprite != null) {
                Rect2f bounds = sprite.getTransform().resolveBounds(
                        sprite.getWidth(),
                        sprite.getHeight(),
                        RenderPipeline2D.DEFAULT_LOGICAL_WIDTH,
                        RenderPipeline2D.DEFAULT_LOGICAL_HEIGHT
                );
                float tx = clamp(bounds.x() + bounds.width() * 0.5f, 40.0f, 1880.0f);
                float ty = clamp(bounds.y() + bounds.height() * 0.22f, 40.0f, 1020.0f);
                return new Vec2f(tx, ty);
            }
        }
        return new Vec2f(RenderPipeline2D.DEFAULT_LOGICAL_WIDTH * 0.5f, 760.0f);
    }

    /**
     * Persists current bubble layout presets to {@code savesDir/bubble_layouts.json}.
     */
    public void savePresetsToDisk() {
        Path savesDir = session.getSaveManager() != null ? session.getSaveManager().getSavesDir() : null;
        if (savesDir == null) {
            return;
        }
        try {
            Files.createDirectories(savesDir);
            Path file = savesDir.resolve(PRESETS_FILE_NAME);
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(layoutPresets, writer);
            }
        } catch (IOException ex) {
            session.getEngineApi().logWarn("Failed to save bubble layout presets: " + ex.getMessage());
        }
    }

    private void loadPresetsFromDisk() {
        Path savesDir = session.getSaveManager() != null ? session.getSaveManager().getSavesDir() : null;
        if (savesDir == null) {
            return;
        }
        Path file = savesDir.resolve(PRESETS_FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, BubbleLayoutConfig> loaded = GSON.fromJson(reader, PRESET_MAP_TYPE);
            if (loaded != null) {
                layoutPresets.putAll(loaded);
            }
        } catch (Exception ex) {
            session.getEngineApi().logWarn("Failed to load bubble layout presets: " + ex.getMessage());
        }
    }

    private void persistActivePreset() {
        if (activeBubble == null) {
            return;
        }
        String key = (activeBubble.lineId() != null && !activeBubble.lineId().isBlank())
                ? activeBubble.lineId()
                : activeBubble.speakerId();
        if (key != null && !key.isBlank()) {
            layoutPresets.put(key, activeBubble.layout());
        }
        if (activeBubble.speakerId() != null && !activeBubble.speakerId().isBlank()) {
            layoutPresets.put(activeBubble.speakerId(), activeBubble.layout());
        }
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    private final class BubbleCanvasNode extends UiNode {

        private BubbleCanvasNode() {
            setFocusable(true);
        }

        @Override
        protected boolean isInteractive() {
            return true;
        }

        @Override
        protected boolean onMouseDown(float lx, float ly, int button) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || activeBubble == null) {
                return false;
            }
            if (!editorMode) {
                handlePrimaryAdvance();
                return true;
            }
            BubbleLayoutConfig cfg = activeBubble.layout();
            if (cfg.isNearTailTip(lx, ly, TAIL_HIT_RADIUS)) {
                dragTarget = DragTarget.TAIL;
                dragOffsetX = cfg.tailX() - lx;
                dragOffsetY = cfg.tailY() - ly;
                return true;
            }
            if (cfg.containsBox(lx, ly)) {
                dragTarget = DragTarget.BOX;
                dragOffsetX = lx - cfg.boxX();
                dragOffsetY = ly - cfg.boxY();
                return true;
            }
            return false;
        }

        @Override
        protected void onMouseMoved(float lx, float ly, boolean inside) {
            if (!editorMode || dragTarget == DragTarget.NONE || activeBubble == null) {
                return;
            }
            BubbleLayoutConfig cfg = activeBubble.layout();
            BubbleLayoutConfig updated;
            if (dragTarget == DragTarget.BOX) {
                float nx = clamp(lx - dragOffsetX, 10.0f, 1910.0f - cfg.boxW());
                float ny = clamp(ly - dragOffsetY, 10.0f, 1070.0f - cfg.boxH());
                updated = new BubbleLayoutConfig(nx, ny, cfg.boxW(), cfg.boxH(), cfg.tailX(), cfg.tailY());
            } else {
                float tx = clamp(lx + dragOffsetX, 10.0f, 1910.0f);
                float ty = clamp(ly + dragOffsetY, 10.0f, 1070.0f);
                updated = new BubbleLayoutConfig(cfg.boxX(), cfg.boxY(), cfg.boxW(), cfg.boxH(), tx, ty);
            }
            activeBubble = new BubbleEntry(
                    activeBubble.lineId(),
                    activeBubble.speakerId(),
                    activeBubble.speakerName(),
                    activeBubble.speakerColorHex(),
                    activeBubble.text(),
                    updated
            );
            persistActivePreset();
        }

        @Override
        protected boolean onMouseUp(float lx, float ly, int button) {
            if (dragTarget != DragTarget.NONE) {
                dragTarget = DragTarget.NONE;
                savePresetsToDisk();
                return true;
            }
            return false;
        }

        @Override
        protected void renderSelf(
                RenderPipeline2D pipeline,
                FontManager fontManager,
                TextureManager textureManager,
                double elapsedSeconds
        ) {
            if (pipeline == null || !pipeline.isInitialized()) {
                return;
            }
            for (BubbleEntry retained : retainedBubbles) {
                drawBubble(pipeline, fontManager, textureManager, retained, 0.78f, false, null, elapsedSeconds);
            }
            if (activeBubble != null) {
                drawBubble(pipeline, fontManager, textureManager, activeBubble,
                        1.0f, editorMode, activeTypewriter, elapsedSeconds);
            }
            if (editorMode) {
                drawEditorBanner(pipeline, fontManager, elapsedSeconds);
            }
        }

        private void drawBubble(
                RenderPipeline2D pipeline,
                FontManager fontManager,
                TextureManager textureManager,
                BubbleEntry entry,
                float alpha,
                boolean highlightEditor,
                StyledTextLayout.TypewriterController typewriter,
                double elapsedSeconds
        ) {
            BubbleLayoutConfig cfg = entry.layout();
            float[] fill = ThemePalette.premultipliedRgba(ThemePalette.PANEL_GLASS, alpha);
            String borderHex = highlightEditor ? ThemePalette.ACTIVE_SELECTED : ThemePalette.BORDER;
            float[] border = ThemePalette.premultipliedRgba(borderHex, alpha);

            drawTail(pipeline, cfg, fill, border);
            pipeline.drawRoundedRect(
                    cfg.boxX(), cfg.boxY(), cfg.boxW(), cfg.boxH(),
                    16.0f, fill, highlightEditor ? 2.5f : 1.5f, border);

            if (entry.speakerName() != null && !entry.speakerName().isBlank()) {
                float[] badgeFill = ThemePalette.premultipliedRgba(ThemePalette.ELEVATED, alpha);
                pipeline.drawRoundedRect(
                        cfg.boxX() + 18.0f, cfg.boxY() - 22.0f,
                        220.0f, 36.0f, 8.0f, badgeFill, 1.0f, border);
                if (fontManager != null) {
                    String colorHex = (entry.speakerColorHex() != null && !entry.speakerColorHex().isBlank())
                            ? entry.speakerColorHex()
                            : ThemePalette.ACCENT_HOVER;
                    StyledTextLayout badgeLayout = StyledTextLayout.layout(
                            TextTagParser.parse(entry.speakerName()),
                            fontManager,
                            200.0f,
                            22.0f,
                            1.15f,
                            colorHex,
                            true
                    );
                    badgeLayout.render(
                            pipeline, textureManager, cfg.boxX() + 28.0f, cfg.boxY() - 16.0f, elapsedSeconds);
                }
            }

            if (fontManager != null && entry.text() != null && !entry.text().isEmpty()) {
                StyledTextLayout bodyLayout = StyledTextLayout.layout(
                        TextTagParser.parse(entry.text()),
                        fontManager,
                        Math.max(80.0f, cfg.boxW() - 40.0f),
                        24.0f,
                        1.15f,
                        ThemePalette.TEXT_PRIMARY,
                        true
                );
                int visibleLimit = typewriter != null ? typewriter.getVisibleCharCount() : Integer.MAX_VALUE;
                bodyLayout.render(
                        pipeline,
                        textureManager,
                        cfg.boxX() + 20.0f,
                        cfg.boxY() + 24.0f,
                        elapsedSeconds,
                        visibleLimit,
                        alpha
                );
            }

            if (highlightEditor) {
                float[] handleFill = ThemePalette.premultipliedRgba(ThemePalette.ACTIVE_SELECTED, 1.0f);
                float[] handleBorder = ThemePalette.premultipliedRgba(ThemePalette.TEXT_PRIMARY, 1.0f);
                pipeline.drawRoundedRect(
                        cfg.tailX() - TAIL_HANDLE_RADIUS,
                        cfg.tailY() - TAIL_HANDLE_RADIUS,
                        TAIL_HANDLE_RADIUS * 2.0f,
                        TAIL_HANDLE_RADIUS * 2.0f,
                        TAIL_HANDLE_RADIUS,
                        handleFill,
                        2.0f,
                        handleBorder
                );
            }
        }

        private void drawTail(RenderPipeline2D pipeline, BubbleLayoutConfig cfg, float[] fill, float[] border) {
            float centerX = cfg.boxX() + cfg.boxW() * 0.5f;
            float centerY = cfg.boxY() + cfg.boxH() * 0.5f;
            float dx = cfg.tailX() - centerX;
            float dy = cfg.tailY() - centerY;
            float startX = clamp(centerX + dx * 0.35f, cfg.boxX() + 28.0f, cfg.boxX() + cfg.boxW() - 28.0f);
            float startY = clamp(centerY + dy * 0.35f, cfg.boxY() + 20.0f, cfg.boxY() + cfg.boxH() - 20.0f);

            for (int i = 0; i <= TAIL_SEGMENTS; i++) {
                float t = i / (float) TAIL_SEGMENTS;
                float px = startX + (cfg.tailX() - startX) * t;
                float py = startY + (cfg.tailY() - startY) * t;
                float size = 26.0f * (1.0f - t) + 6.0f * t;
                pipeline.drawRoundedRect(
                        px - size * 0.5f,
                        py - size * 0.5f,
                        size,
                        size,
                        size * 0.5f,
                        fill,
                        1.0f,
                        border
                );
            }
        }

        private void drawEditorBanner(RenderPipeline2D pipeline, FontManager fontManager, double elapsedSeconds) {
            float[] bg = ThemePalette.premultipliedRgba(ThemePalette.ELEVATED, 0.95f);
            float[] border = ThemePalette.premultipliedRgba(ThemePalette.ACTIVE_SELECTED, 1.0f);
            pipeline.drawRoundedRect(620.0f, 18.0f, 680.0f, 44.0f, 8.0f, bg, 1.5f, border);
            if (fontManager != null) {
                StyledTextLayout layout = StyledTextLayout.layout(
                        TextTagParser.parse("BUBBLE EDITOR [Shift+B] - Drag box or orange tail handle"),
                        fontManager,
                        660.0f,
                        20.0f,
                        1.15f,
                        ThemePalette.ACTIVE_SELECTED,
                        true
                );
                layout.render(pipeline, null, 640.0f, 28.0f, elapsedSeconds);
            }
        }
    }
}
