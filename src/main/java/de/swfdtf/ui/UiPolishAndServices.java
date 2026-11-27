package de.swfdtf.ui;

import de.swfdtf.core.EngineVersion;
import de.swfdtf.core.KeybindingRemapper;
import de.swfdtf.graphics.NineSliceDrawable;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.math.Rect2f;
import de.swfdtf.state.PersistentAccess;
import de.swfdtf.state.PreferencesState;
import de.swfdtf.state.StateStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Cohesive suite of runtime UI polish, accessibility, layout auditing, skinning, and interactive
 * HUD helpers completing Wave P2-5 Chapter 5 goals ({@code #0358}–{@code #0425}).
 */
public final class UiPolishAndServices {

    private UiPolishAndServices() {
    }

    /** UI audio cues routed by {@link UiSoundCueRouter} ({@code #0381}). */
    public enum UiCue {
        /** Pointer or keyboard focus entered a widget. */
        HOVER,
        /** Button or menu option activated. */
        CLICK,
        /** Screen closed or navigated back. */
        BACK,
        /** Game progress saved to a slot. */
        SAVE
    }

    /**
     * Routes UI sound cues while strictly honoring SFX mute ({@code volume <= 0}) ({@code #0381}).
     */
    public static final class UiSoundCueRouter {
        private final List<UiCue> emittedCues = new ArrayList<>();
        private float sfxVolume = 0.9f;

        /** Creates a new UI sound cue router. */
        public UiSoundCueRouter() {
        }

        /**
         * Updates active SFX volume multiplier {@code [0, 1]}.
         *
         * @param sfxVolume normalized SFX gain
         */
        public void setSfxVolume(float sfxVolume) {
            this.sfxVolume = Float.isFinite(sfxVolume) ? Math.clamp(sfxVolume, 0.0f, 1.0f) : 0.0f;
        }

        /**
         * Returns configured SFX volume.
         *
         * @return SFX gain in {@code [0, 1]}
         */
        public float getSfxVolume() {
            return sfxVolume;
        }

        /**
         * Emits a UI sound cue if SFX gain is above zero.
         *
         * @param cue UI sound cue
         * @return {@code true} if the cue was emitted (not muted)
         */
        public boolean trigger(UiCue cue) {
            Objects.requireNonNull(cue, "cue must not be null");
            if (sfxVolume <= 0.0f) {
                return false;
            }
            emittedCues.add(cue);
            return true;
        }

        /**
         * Returns unmodifiable list of recorded non-muted cues.
         *
         * @return emitted UI cues
         */
        public List<UiCue> getEmittedCues() {
            return List.copyOf(emittedCues);
        }
    }

    /**
     * Manages screenshot-friendly HUD hiding via {@code H} key or middle mouse click ({@code #0358}),
     * refusing {@code H} whenever a {@link TextInput} currently holds keyboard focus.
     */
    public static final class HudVisibilityController {
        private boolean hudHidden = false;

        /** Creates a new HUD visibility controller. */
        public HudVisibilityController() {
        }

        /**
         * Handles pressing the {@code H} key.
         *
         * @param textInputFocused {@code true} if the user is typing in a text field
         * @return current HUD hidden state after handling
         */
        public boolean toggleWithHKey(boolean textInputFocused) {
            if (!textInputFocused) {
                hudHidden = !hudHidden;
            }
            return hudHidden;
        }

        /**
         * Toggles HUD visibility on middle mouse button click.
         *
         * @return updated HUD hidden state
         */
        public boolean toggleWithMiddleClick() {
            hudHidden = !hudHidden;
            return hudHidden;
        }

        /**
         * Restores hidden HUD cleanly on the next standard mouse click.
         *
         * @return {@code true} if this click restored a hidden HUD and should be consumed
         */
        public boolean restoreOnPrimaryClick() {
            if (!hudHidden) {
                return false;
            }
            hudHidden = false;
            return true;
        }

        /**
         * Returns whether dialogue box, quick menu, and HUD are currently hidden.
         *
         * @return {@code true} when HUD is hidden
         */
        public boolean isHudHidden() {
            return hudHidden;
        }
    }

    /**
     * Smoothly dims non-speaking sprites to {@code 0.72} alpha/brightness while brightening the
     * active speaker to {@code 1.0} over {@code 0.15 s} ({@code #0361}).
     */
    public static final class SpeakerDimmingDirector {
        /** Canonical non-speaking brightness/alpha target. */
        public static final float DIM_ALPHA = 0.72f;
        /** Transition duration in seconds. */
        public static final double BLEND_DURATION_SECONDS = 0.15;

        private final Map<String, Float> spriteAlphas = new HashMap<>();
        private String activeSpeakerTag = "";

        /** Creates a new speaker dimming director. */
        public SpeakerDimmingDirector() {
        }

        /**
         * Sets the currently speaking character sprite tag.
         *
         * @param speakerTag speaking tag (null/blank means narrator, dimming none)
         */
        public void setActiveSpeakerTag(String speakerTag) {
            this.activeSpeakerTag = speakerTag != null ? speakerTag.trim() : "";
        }

        /**
         * Advances alpha interpolation across all active stage sprites on {@code controller}.
         *
         * @param controller active scene stage controller
         * @param dt         frame delta seconds
         */
        public void update(SceneController controller, double dt) {
            if (controller == null) {
                return;
            }
            float step = dt <= 0.0 ? 0.0f : (float) Math.min(1.0, dt / BLEND_DURATION_SECONDS);
            for (SpriteNode sprite : controller.getActiveSprites().values()) {
                String tag = sprite.getTag();
                float target = (activeSpeakerTag.isEmpty() || activeSpeakerTag.equals(tag))
                        ? 1.0f : DIM_ALPHA;
                float current = spriteAlphas.getOrDefault(tag, sprite.getTransform().getAlpha());
                float next = current + (target - current) * step;
                if (Math.abs(target - next) < 0.005f) {
                    next = target;
                }
                spriteAlphas.put(tag, next);
                sprite.getTransform().setAlpha(next);
            }
        }

        /**
         * Returns tracked alpha multiplier for {@code spriteTag}.
         *
         * @param spriteTag sprite tag
         * @return alpha in {@code [0.72, 1.0]}
         */
        public float getSpriteBrightness(String spriteTag) {
            return spriteAlphas.getOrDefault(spriteTag, 1.0f);
        }
    }

    /**
     * Binds clickable UI inventory hotbar items to {@code $selected_item} in {@link StateStore}
     * and optional label/use callbacks ({@code #0363}).
     */
    public static final class HotbarInventoryBinder {
        private final Map<String, Runnable> itemCallbacks = new LinkedHashMap<>();

        /** Creates a new hotbar inventory binder. */
        public HotbarInventoryBinder() {
        }

        /**
         * Registers a usable item slot with an action callback.
         *
         * @param itemId   item identifier
         * @param onSelect optional callback triggered when item is selected
         */
        public void registerItem(String itemId, Runnable onSelect) {
            if (itemId != null && !itemId.isBlank()) {
                itemCallbacks.put(itemId.trim(), onSelect);
            }
        }

        /**
         * Selects {@code itemId}, sets {@code selected_item} in {@code stateStore}, and runs its callback.
         *
         * @param itemId     selected item identifier
         * @param stateStore active state store
         */
        public void selectItem(String itemId, StateStore stateStore) {
            if (itemId == null || itemId.isBlank()) {
                return;
            }
            String clean = itemId.trim();
            if (stateStore != null) {
                stateStore.set("selected_item", clean);
            }
            Runnable cb = itemCallbacks.get(clean);
            if (cb != null) {
                cb.run();
            }
        }
    }

    /** Active input device family controlling prompt glyphs ({@code #0411}). */
    public enum InputDevicePromptFamily {
        /** Standard PC keyboard and mouse glyphs. */
        KEYBOARD,
        /** Xbox / XInput controller glyphs. */
        XBOX,
        /** PlayStation controller glyphs. */
        PLAYSTATION,
        /** Nintendo Switch controller glyphs. */
        NINTENDO
    }

    /**
     * Automatically swaps UI action glyphs ({@code [Space]} vs {@code [A]} vs {@code [✕]})
     * according to the most recently observed input device ({@code #0411}).
     */
    public static final class PromptGlyphMapper {
        private InputDevicePromptFamily activeFamily = InputDevicePromptFamily.KEYBOARD;

        /** Creates a new prompt glyph mapper defaulting to keyboard glyphs. */
        public PromptGlyphMapper() {
        }

        /**
         * Updates the active prompt glyph family from latest input event.
         *
         * @param family active device family
         */
        public void setActiveFamily(InputDevicePromptFamily family) {
            this.activeFamily = Objects.requireNonNull(family, "family must not be null");
        }

        /**
         * Returns the active prompt glyph family.
         *
         * @return device family
         */
        public InputDevicePromptFamily getActiveFamily() {
            return activeFamily;
        }

        /**
         * Resolves the localized prompt glyph token for an abstract UI action ({@code confirm},
         * {@code cancel}, {@code menu}).
         *
         * @param action abstract action identifier
         * @return display glyph badge (e.g., {@code "[Space]"}, {@code "[A]"}, {@code "[✕]"})
         */
        public String getPromptGlyph(String action) {
            String key = action == null ? "confirm" : action.trim().toLowerCase(Locale.ROOT);
            return switch (activeFamily) {
                case KEYBOARD -> resolveFamilyGlyph(key, "[Space]", "[Esc]", "[M]");
                case XBOX -> resolveFamilyGlyph(key, "[A]", "[B]", "[Start]");
                case PLAYSTATION -> resolveFamilyGlyph(key, "[✕]", "[○]", "[Options]");
                case NINTENDO -> resolveFamilyGlyph(key, "[A]", "[B]", "[+]");
            };
        }

        private static String resolveFamilyGlyph(String key, String confirm, String cancel, String menu) {
            if ("cancel".equals(key) || "back".equals(key)) {
                return cancel;
            }
            if ("menu".equals(key)) {
                return menu;
            }
            return confirm;
        }
    }

    /**
     * Registers project-level {@link NineSliceDrawable} UI skins with seamless fallback to the
     * default Dark-Slate vector style ({@code #0377}).
     */
    public static final class UiSkinRegistry {
        private final Map<String, NineSliceDrawable> skins = new HashMap<>();

        /** Creates an empty UI skin registry. */
        public UiSkinRegistry() {
        }

        /**
         * Registers a custom nine-slice skin under a widget slot key (e.g. {@code "dialogue_box"}).
         *
         * @param slotKey  UI slot key
         * @param drawable custom nine-slice border skin
         */
        public void registerSkin(String slotKey, NineSliceDrawable drawable) {
            if (slotKey != null && !slotKey.isBlank() && drawable != null) {
                skins.put(slotKey.trim(), drawable);
            }
        }

        /**
         * Resolves a registered custom skin or returns {@code null} to trigger clean Dark-Slate fallback.
         *
         * @param slotKey UI slot key
         * @return registered {@link NineSliceDrawable}, or {@code null} for vector fallback
         */
        public NineSliceDrawable resolveSkinOrFallback(String slotKey) {
            return slotKey != null ? skins.get(slotKey.trim()) : null;
        }
    }

    /**
     * Evaluates punctuation pauses ({@code 180 ms}) and explicit {@code {p}} cues during
     * typewriter text reveal, disabling all pauses when instant text mode is active ({@code #0384}).
     */
    public static final class DialoguePunctuationPacer {
        /** Standard punctuation pause duration in seconds. */
        public static final double DEFAULT_PUNCTUATION_PAUSE_SEC = 0.18;

        private DialoguePunctuationPacer() {
        }

        /**
         * Computes pause duration in seconds after character {@code ch} (or {@code {p}} tag),
         * returning {@code 0.0} when instant reveal ({@code textSpeedCps >= maxCps}) is active.
         *
         * @param ch           revealed character
         * @param explicitCue  {@code true} when an explicit {@code {p}} pause tag was reached
         * @param textSpeedCps active characters-per-second speed
         * @param maxCps       threshold treated as instant reveal
         * @return pause delay in seconds
         */
        public static double computePauseSeconds(
                char ch,
                boolean explicitCue,
                double textSpeedCps,
                double maxCps
        ) {
            if (textSpeedCps >= maxCps) {
                return 0.0;
            }
            if (explicitCue) {
                return DEFAULT_PUNCTUATION_PAUSE_SEC;
            }
            return (ch == '.' || ch == '?' || ch == '!' || ch == '—')
                    ? DEFAULT_PUNCTUATION_PAUSE_SEC : 0.0;
        }
    }

    /**
     * Tracks previously picked menu options globally inside {@link PersistentAccess} across
     * playthroughs and saves ({@code #0422}).
     */
    public static final class ChoiceHistoryTracker {
        private static final String PREFIX = "__choice_seen:";

        private ChoiceHistoryTracker() {
        }

        /**
         * Records that {@code optionText} under {@code labelName} was chosen previously.
         *
         * @param persistent persistent cross-save store
         * @param labelName  menu label identifier
         * @param optionText visible option text
         */
        public static void recordChoice(PersistentAccess persistent, String labelName, String optionText) {
            if (persistent != null && labelName != null && optionText != null) {
                persistent.set(PREFIX + labelName + ":" + optionText, Boolean.TRUE);
            }
        }

        /**
         * Returns whether {@code optionText} under {@code labelName} was already picked before.
         *
         * @param persistent persistent cross-save store
         * @param labelName  menu label identifier
         * @param optionText visible option text
         * @return {@code true} if chosen in any prior run
         */
        public static boolean wasPreviouslyChosen(
                PersistentAccess persistent,
                String labelName,
                String optionText
        ) {
            if (persistent == null || labelName == null || optionText == null) {
                return false;
            }
            return Boolean.TRUE.equals(persistent.get(PREFIX + labelName + ":" + optionText));
        }
    }

    /**
     * Walkable arena control hint banner tied to {@code wasd_init} / {@code wasd_stop} ({@code #0394}).
     */
    public static final class WasdArenaHudGuide {
        /** Canonical German/localized control banner text. */
        public static final String BANNER_TEXT = "[WASD] Bewegen · [Leertaste] Springen · [Esc] Menü";

        private boolean visible = false;

        /** Creates a new hidden WASD arena HUD guide. */
        public WasdArenaHudGuide() {
        }

        /**
         * Updates guide visibility from active exploration state.
         *
         * @param wasdActive {@code true} when {@code wasd_init} is active
         */
        public void syncFromWasdState(boolean wasdActive) {
            this.visible = wasdActive;
        }

        /**
         * Returns whether the WASD HUD guide is visible.
         *
         * @return {@code true} if active
         */
        public boolean isVisible() {
            return visible;
        }
    }

    /**
     * Debug inspector utility ({@code Shift+U}, {@code #0414}) formatting a readable widget tree
     * dump with computed {@link Rect2f} layout bounds across all visible screens.
     */
    public static final class UiWidgetTreeDumper {
        private UiWidgetTreeDumper() {
        }

        /**
         * Dumps all visible screens and nested {@link UiNode} bounds registered in {@code manager}.
         *
         * @param manager screen manager to inspect
         * @return structured multi-line hierarchy report
         */
        public static String dump(ScreenManager manager) {
            Objects.requireNonNull(manager, "manager must not be null");
            StringBuilder sb = new StringBuilder();
            for (Screen screen : manager.getVisibleScreensSorted()) {
                sb.append("Screen[").append(screen.getName())
                        .append(", z=").append(screen.getZOrder())
                        .append(", modal=").append(screen.isModal()).append("]\n");
                dumpNode(screen.getRoot(), 1, sb);
            }
            return sb.toString();
        }

        private static void dumpNode(UiNode node, int depth, StringBuilder sb) {
            if (node == null) {
                return;
            }
            sb.append("  ".repeat(depth))
                    .append(node.getClass().getSimpleName());
            if (!node.getId().isBlank()) {
                sb.append("#").append(node.getId());
            }
            Rect2f b = node.getBounds();
            sb.append(String.format(
                    Locale.ROOT,
                    " [x=%.1f, y=%.1f, w=%.1f, h=%.1f, visible=%s]%n",
                    b.x(), b.y(), b.width(), b.height(), node.isVisible()
            ));
            for (UiNode child : node.getChildren()) {
                dumpNode(child, depth + 1, sb);
            }
        }
    }

    /**
     * Audits built-in {@link Screen}s across standard and compact resolutions ({@code 1920x1080},
     * {@code 1280x720}, {@code 800x600}) to detect unintended sibling button overlaps ({@code #0419}).
     */
    public static final class UiLayoutOverlapAuditor {
        private UiLayoutOverlapAuditor() {
        }

        /**
         * Audits sibling interactive widgets within {@code screens} at {@code (width, height)}.
         *
         * @param screens screens to lay out and inspect
         * @param width   logical test width
         * @param height  logical test height
         * @return list of overlap descriptions (empty means 0 overlaps)
         */
        public static List<String> findSiblingButtonOverlaps(List<Screen> screens, float width, float height) {
            List<String> overlaps = new ArrayList<>();
            if (screens == null) {
                return overlaps;
            }
            for (Screen s : screens) {
                if (s == null || s.getRoot() == null) {
                    continue;
                }
                s.layout(width, height);
                collectSiblingOverlaps(s.getName(), s.getRoot(), overlaps);
            }
            return overlaps;
        }

        private static void collectSiblingOverlaps(String screenName, UiNode parent, List<String> overlaps) {
            List<UiNode> children = parent.getChildren();
            for (int i = 0; i < children.size(); i++) {
                UiNode a = children.get(i);
                if (!a.isVisible()) {
                    continue;
                }
                if (a instanceof TextButton) {
                    for (int j = i + 1; j < children.size(); j++) {
                        UiNode b = children.get(j);
                        if (b.isVisible() && b instanceof TextButton
                                && rectsOverlapStrictly(a.getBounds(), b.getBounds())) {
                            overlaps.add(screenName + ": '" + ((TextButton) a).getText()
                                    + "' overlaps '" + ((TextButton) b).getText() + "'");
                        }
                    }
                }
                collectSiblingOverlaps(screenName, a, overlaps);
            }
        }

        private static boolean rectsOverlapStrictly(Rect2f a, Rect2f b) {
            float ox = Math.min(a.right(), b.right()) - Math.max(a.x(), b.x());
            float oy = Math.min(a.bottom(), b.bottom()) - Math.max(a.y(), b.y());
            return ox > 1.0f && oy > 1.0f;
        }
    }

    /**
     * Manages custom save-slot labels, favorite write-protection pinning, and {@code 1..9}
     * keyboard page jumping ({@code #0392}, {@code #0401}).
     */
    public static final class SaveSlotMetaManager {
        private final Map<String, String> customLabels = new HashMap<>();
        private final Map<String, Boolean> pinnedSlots = new HashMap<>();
        private int currentPage = 1;

        /** Creates a new save-slot metadata manager. */
        public SaveSlotMetaManager() {
        }

        /**
         * Renames a save slot with a player-chosen label.
         *
         * @param slotId save slot identifier
         * @param label  custom label
         */
        public void renameSlot(String slotId, String label) {
            if (slotId != null && !slotId.isBlank()) {
                customLabels.put(slotId.trim(), label != null ? label.trim() : "");
            }
        }

        /**
         * Returns custom slot label if present.
         *
         * @param slotId save slot identifier
         * @return custom label or empty string
         */
        public String getSlotLabel(String slotId) {
            return slotId != null ? customLabels.getOrDefault(slotId.trim(), "") : "";
        }

        /**
         * Pins or unpins a save slot to protect against accidental overwrite/deletion.
         *
         * @param slotId save slot identifier
         * @param pinned {@code true} to enable write protection
         */
        public void setPinned(String slotId, boolean pinned) {
            if (slotId != null && !slotId.isBlank()) {
                pinnedSlots.put(slotId.trim(), pinned);
            }
        }

        /**
         * Returns whether {@code slotId} is favorite-pinned.
         *
         * @param slotId save slot identifier
         * @return {@code true} when write-protected
         */
        public boolean isPinned(String slotId) {
            return slotId != null && Boolean.TRUE.equals(pinnedSlots.get(slotId.trim()));
        }

        /**
         * Checks whether overwriting/deleting {@code slotId} is allowed unless explicitly unlocked.
         *
         * @param slotId           save slot identifier
         * @param explicitUnlock   {@code true} when player explicitly confirmed unlocking
         * @return {@code true} if modification is permitted
         */
        public boolean canModifySlot(String slotId, boolean explicitUnlock) {
            return !isPinned(slotId) || explicitUnlock;
        }

        /**
         * Jumps directly to save-slot page {@code [1..9]} via number hotkeys.
         *
         * @param pageNumber requested 1-based page number
         * @param totalPages maximum available pages
         * @return resulting active page number
         */
        public int jumpToPage(int pageNumber, int totalPages) {
            int maxP = Math.max(1, Math.min(9, totalPages));
            this.currentPage = Math.clamp(pageNumber, 1, maxP);
            return this.currentPage;
        }

        /**
         * Returns current 1-based page index.
         *
         * @return active page
         */
        public int getCurrentPage() {
            return currentPage;
        }
    }

    /**
     * Tracks live gameplay session statistics (pausing cleanly while menus are open) and formats
     * main-menu completion badges alongside {@link EngineVersion} ({@code #0386}, {@code #0400}).
     */
    public static final class GameplayStatsPresenter {
        private double activePlaySeconds = 0.0;
        private int readLineCount = 0;
        private int unlockedEndings = 0;
        private int totalEndings = 0;

        /** Creates a new gameplay statistics presenter. */
        public GameplayStatsPresenter() {
        }

        /**
         * Advances active play timer unless the pause/main menu is currently open.
         *
         * @param dt              delta seconds
         * @param pauseMenuOpen   {@code true} when pause overlay suppresses play time counting
         */
        public void tickPlayTime(double dt, boolean pauseMenuOpen) {
            if (!pauseMenuOpen && dt > 0.0) {
                activePlaySeconds += dt;
            }
        }

        /**
         * Updates progression counters shown in About/Status and MainMenu screens.
         *
         * @param readLines       number of dialogue lines read
         * @param unlockedEndings number of discovered endings
         * @param totalEndings    total defined endings
         */
        public void updateStats(int readLines, int unlockedEndings, int totalEndings) {
            this.readLineCount = Math.max(0, readLines);
            this.unlockedEndings = Math.max(0, unlockedEndings);
            this.totalEndings = Math.max(0, totalEndings);
        }

        /**
         * Returns accumulated active gameplay duration in seconds.
         *
         * @return active play time in seconds
         */
        public double getActivePlaySeconds() {
            return activePlaySeconds;
        }

        /**
         * Computes completion percentage {@code [0..100]} across discovered endings.
         *
         * @return completion percent
         */
        public int getCompletionPercent() {
            if (totalEndings <= 0) {
                return 0;
            }
            return Math.clamp(Math.round((unlockedEndings * 100.0f) / totalEndings), 0, 100);
        }

        /**
         * Formats the MainMenu status badge string showing completion percent and engine version.
         *
         * @return main-menu badge text
         */
        public String formatMainMenuBadge() {
            return String.format(
                    Locale.ROOT,
                    "%d %% Abgeschlossen · %d Zeilen · %s",
                    getCompletionPercent(), readLineCount, EngineVersion.VERSION
            );
        }
    }

    /**
     * Calculates adaptive dialogue container height for long translations and speaker plaque
     * alignment based on active character X coordinate ({@code #0416}, {@code #0417}).
     */
    public static final class AdaptiveDialogueBoxCalculator {
        private AdaptiveDialogueBoxCalculator() {
        }

        /** Plaque alignment relative to character stage position. */
        public enum NameBoxAlignment {
            /** Align speaker plaque left above left-stage characters. */
            LEFT,
            /** Align speaker plaque center above centered characters or narrator. */
            CENTER,
            /** Align speaker plaque right above right-stage characters. */
            RIGHT
        }

        /**
         * Computes adaptive dialogue box height preventing overflow when wrapped lines exceed 4.
         *
         * @param baseHeight    default box height (e.g. {@code 250 px})
         * @param lineCount     wrapped dialogue line count
         * @param lineHeightPx  height per wrapped line
         * @return adaptive height {@code >= baseHeight}
         */
        public static float computeBoxHeight(float baseHeight, int lineCount, float lineHeightPx) {
            if (lineCount <= 4) {
                return baseHeight;
            }
            return baseHeight + (lineCount - 4) * Math.max(16.0f, lineHeightPx);
        }

        /**
         * Determines horizontal speaker plaque alignment from speaker sprite stage X ({@code [0..1920]}).
         *
         * @param speakerStageX speaker center X position in logical pixels
         * @param logicalWidth  logical stage width
         * @return {@link NameBoxAlignment}
         */
        public static NameBoxAlignment computeNameBoxAlignment(float speakerStageX, float logicalWidth) {
            float ratio = speakerStageX / Math.max(1.0f, logicalWidth);
            if (ratio < 0.36f) {
                return NameBoxAlignment.LEFT;
            }
            if (ratio > 0.64f) {
                return NameBoxAlignment.RIGHT;
            }
            return NameBoxAlignment.CENTER;
        }
    }

    /**
     * Resolves vertical collisions among simultaneous speech bubbles while keeping character eye
     * exclusion rectangles unobstructed ({@code #0380}).
     */
    public static final class BubbleCollisionResolver {
        private BubbleCollisionResolver() {
        }

        /**
         * Adjusts bubble rectangles in place so no two bubbles overlap and none cover eye zones.
         *
         * @param bubbles       mutable list of bubble bounds
         * @param eyeZones      head/eye exclusion rectangles
         * @param paddingPx     minimum separation gap in pixels
         * @return resolved non-overlapping list of {@link Rect2f} bounds
         */
        public static List<Rect2f> resolveOverlaps(
                List<Rect2f> bubbles,
                List<Rect2f> eyeZones,
                float paddingPx
        ) {
            List<Rect2f> result = new ArrayList<>();
            if (bubbles == null) {
                return result;
            }
            float pad = Math.max(2.0f, paddingPx);
            for (Rect2f candidate : bubbles) {
                Rect2f current = candidate;
                for (int pass = 0; pass < 8; pass++) {
                    boolean shifted = false;
                    if (eyeZones != null) {
                        for (Rect2f eye : eyeZones) {
                            if (current.intersects(eye)) {
                                current = new Rect2f(
                                        current.x(), eye.y() - current.height() - pad,
                                        current.width(), current.height()
                                );
                                shifted = true;
                            }
                        }
                    }
                    for (Rect2f placed : result) {
                        if (current.intersects(placed)) {
                            current = new Rect2f(
                                    current.x(), placed.y() - current.height() - pad,
                                    current.width(), current.height()
                            );
                            shifted = true;
                        }
                    }
                    if (!shifted) {
                        break;
                    }
                }
                result.add(current);
            }
            return result;
        }
    }

    /**
     * Lightweight hover preview popup state for inline codex/glossary links that automatically
     * dismisses on dialogue advance ({@code #0393}).
     */
    public static final class HoverGlossaryPreview {
        private String activeTermId;
        private String activeSummary;

        /** Creates a new hover glossary preview controller. */
        public HoverGlossaryPreview() {
        }

        /**
         * Activates the glossary hover preview for a term ID and summary.
         *
         * @param termId  codex term identifier
         * @param summary short explanation text
         */
        public void showTerm(String termId, String summary) {
            this.activeTermId = termId;
            this.activeSummary = summary;
        }

        /**
         * Clears active popup preview when advancing to the next dialogue line.
         */
        public void onDialogueAdvance() {
            this.activeTermId = null;
            this.activeSummary = null;
        }

        /**
         * Returns whether a glossary preview popup is active.
         *
         * @return {@code true} if visible
         */
        public boolean isVisible() {
            return activeTermId != null && !activeTermId.isBlank();
        }

        /**
         * Returns summary text shown in the hover popup.
         *
         * @return summary text or null
         */
        public String getActiveSummary() {
            return activeSummary;
        }
    }

    /**
     * Fullscreen CG zoom, pan, double-click Fit-to-Screen reset, and variation switcher
     * backing {@link GalleryScreen} ({@code #0370}).
     */
    public static final class GalleryViewerController {
        private float zoom = 1.0f;
        private float panX = 0.0f;
        private float panY = 0.0f;
        private int variationIndex = 0;

        /** Creates a new CG gallery viewer controller. */
        public GalleryViewerController() {
        }

        /**
         * Adjusts zoom clamped within {@code [1.0, 4.0]}.
         *
         * @param zoom target zoom scale
         */
        public void setZoom(float zoom) {
            this.zoom = Float.isFinite(zoom) ? Math.clamp(zoom, 1.0f, 4.0f) : 1.0f;
        }

        /**
         * Pans current CG view offset.
         *
         * @param dx delta X
         * @param dy delta Y
         */
        public void pan(float dx, float dy) {
            this.panX += dx;
            this.panY += dy;
        }

        /**
         * Handles double-click resetting zoom cleanly to {@code 1.0x} Fit-to-Screen and centering pan.
         */
        public void onDoubleClickReset() {
            this.zoom = 1.0f;
            this.panX = 0.0f;
            this.panY = 0.0f;
        }

        /**
         * Advances variation index wrapping around {@code totalVariations}.
         *
         * @param delta           step ({@code +1} / {@code -1})
         * @param totalVariations total variation count
         * @return updated 0-based variation index
         */
        public int switchVariation(int delta, int totalVariations) {
            if (totalVariations <= 0) {
                variationIndex = 0;
                return 0;
            }
            variationIndex = Math.floorMod(variationIndex + delta, totalVariations);
            return variationIndex;
        }

        /**
         * Returns current zoom scale ({@code 1.0}..{@code 4.0}).
         *
         * @return zoom factor
         */
        public float getZoom() {
            return zoom;
        }

        /**
         * Returns current X pan offset.
         *
         * @return pan X
         */
        public float getPanX() {
            return panX;
        }

        /**
         * Returns current Y pan offset.
         *
         * @return pan Y
         */
        public float getPanY() {
            return panY;
        }

        /**
         * Returns active variation index.
         *
         * @return variation index
         */
        public int getVariationIndex() {
            return variationIndex;
        }
    }

    /**
     * Seek bar and Repeat-One controller for {@link MusicRoomScreen} ({@code #0371}).
     */
    public static final class MusicRoomSeekController {
        private double trackDurationSeconds = 0.0;
        private double positionSeconds = 0.0;
        private boolean repeatOne = false;

        /** Creates a new music room seek controller. */
        public MusicRoomSeekController() {
        }

        /**
         * Sets active track duration in seconds.
         *
         * @param trackDurationSeconds total duration in seconds
         */
        public void setTrackDurationSeconds(double trackDurationSeconds) {
            this.trackDurationSeconds = Double.isFinite(trackDurationSeconds)
                    ? Math.max(0.0, trackDurationSeconds) : 0.0;
            this.positionSeconds = Math.min(this.positionSeconds, this.trackDurationSeconds);
        }

        /**
         * Seeks to normalized progress bar click position {@code [0..1]}, returning target second.
         *
         * @param normalizedRatio clicked X ratio along seek bar
         * @return target playback position in seconds
         */
        public double seekByProgressClick(float normalizedRatio) {
            float r = Float.isFinite(normalizedRatio) ? Math.clamp(normalizedRatio, 0.0f, 1.0f) : 0.0f;
            this.positionSeconds = trackDurationSeconds * r;
            return this.positionSeconds;
        }

        /**
         * Toggles Repeat-One track looping mode.
         *
         * @return new Repeat-One state
         */
        public boolean toggleRepeatOne() {
            this.repeatOne = !this.repeatOne;
            return this.repeatOne;
        }

        /**
         * Returns current playback position in seconds.
         *
         * @return playback position
         */
        public double getPositionSeconds() {
            return positionSeconds;
        }

        /**
         * Returns whether Repeat-One is enabled.
         *
         * @return {@code true} if Repeat-One is active
         */
        public boolean isRepeatOne() {
            return repeatOne;
        }
    }

    /**
     * Computes horizontal safezone-inset bounds honoring {@link PreferencesState#getSafezoneMarginPx()}
     * symmetrically ({@code #0398}).
     *
     * @param baseBounds      unpadded edge HUD bounds
     * @param prefs           active preferences state
     * @param logicalWidth    logical canvas width
     * @param logicalHeight   logical canvas height
     * @return clamped bounds inside safezone margins
     */
    public static Rect2f applySafezoneMargin(
            Rect2f baseBounds,
            PreferencesState prefs,
            float logicalWidth,
            float logicalHeight
    ) {
        if (baseBounds == null || prefs == null) {
            return baseBounds;
        }
        int m = prefs.getSafezoneMarginPx();
        if (m <= 0) {
            return baseBounds;
        }
        float x = Math.max(m, Math.min(logicalWidth - m - baseBounds.width(), baseBounds.x()));
        float y = Math.max(m, Math.min(logicalHeight - m - baseBounds.height(), baseBounds.y()));
        return new Rect2f(x, y, baseBounds.width(), baseBounds.height());
    }

    /**
     * Generates live keyboard &amp; controller binding legend strings for About/Help overlays
     * reflecting custom remappings ({@code #1004}).
     */
    public static final class ControlLegendFormatter {

        /**
         * Creates a {@code ControlLegendFormatter}.
         */
        public ControlLegendFormatter() {
        }

        /**
         * Formats ordered legend rows from {@code remapper} ({@code #1004}).
         *
         * @param remapper keybinding remapper (must not be {@code null})
         * @param language locale language code ({@code "de"} or {@code "en"})
         * @return immutable list of {@code "action: keyName"} legend lines
         */
        public static List<String> formatBindings(KeybindingRemapper remapper, String language) {
            Objects.requireNonNull(remapper, "remapper must not be null");
            Map<String, Integer> bindings = remapper.snapshotBindings();
            List<String> lines = new ArrayList<>(bindings.size());
            for (Map.Entry<String, Integer> entry : bindings.entrySet()) {
                String localizedKey = KeybindingRemapper.localizeKeyName(entry.getValue(), language);
                lines.add(entry.getKey() + ": " + localizedKey);
            }
            return List.copyOf(lines);
        }
    }

    /**
     * Walks a {@link Screen} UI tree to verify keyboard focus/Tab reachability across visible
     * focusable widgets ({@code #0981}).
     */
    public static final class TabOrderAuditor {

        /**
         * Creates a {@code TabOrderAuditor}.
         */
        public TabOrderAuditor() {
        }

        /**
         * Collects IDs of all visible, enabled, focusable widgets in pre-order traversal ({@code #0981}).
         *
         * @param screen UI screen to audit (must not be {@code null})
         * @return immutable list of focusable node identifiers
         */
        public static List<String> collectFocusableIds(Screen screen) {
            Objects.requireNonNull(screen, "screen must not be null");
            List<String> ids = new ArrayList<>();
            collectRecursive(screen.getRoot(), ids);
            return List.copyOf(ids);
        }

        private static void collectRecursive(UiNode node, List<String> ids) {
            if (node == null || !node.isVisible()) {
                return;
            }
            if (node.isFocusable() && node.isEnabled()) {
                ids.add(node.getId() != null ? node.getId() : node.getClass().getSimpleName());
            }
            for (UiNode child : node.getChildren()) {
                collectRecursive(child, ids);
            }
        }
    }
}
