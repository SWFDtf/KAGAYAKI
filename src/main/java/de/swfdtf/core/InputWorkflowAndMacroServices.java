package de.swfdtf.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Input priority routing stack, `.vinput` deterministic macro recorder/player, UI &amp; dialogue input
 * guards (anti-typo-skip, escape/right-click flow, UTF-32/IME sanitization, hotspot/grid/slider/radial/text
 * interactions, Konami sequence detector, hover sound debounce, and tutorial hint fading)
 * ({@code #0616}–{@code #0618}, {@code #0621}, {@code #0624}, {@code #0625}, {@code #0629},
 * {@code #0633}, {@code #0634}, {@code #0636}, {@code #0641}, {@code #0648}, {@code #0649},
 * {@code #0651}, {@code #0652}, {@code #0654}, {@code #0655}, {@code #0657}–{@code #0659},
 * {@code #0661}, {@code #0663}–{@code #0667}, {@code #0673}, {@code #0677}, {@code #0679}).
 */
public final class InputWorkflowAndMacroServices {

    private InputWorkflowAndMacroServices() {
    }

    /**
     * Layered input routing tiers ensuring modals block arena &amp; dialogue cleanly ({@code #0618}).
     */
    public enum InputLayer {
        /** Top modal overlay (Input dialog, Confirm modal, Settings overlay). */
        SCREEN_MODAL(4),
        /** Studio debug / flow-jump overlay. */
        DEBUG_OVERLAY(3),
        /** Interactive WASD / 3D arena gameplay. */
        ARENA(2),
        /** Visual novel dialogue and choices. */
        DIALOGUE(1);

        private final int rank;

        InputLayer(int rank) {
            this.rank = rank;
        }

        /**
         * Returns numeric priority rank where larger numbers win dispatch priority.
         *
         * @return priority rank
         */
        public int rank() {
            return rank;
        }
    }

    /**
     * Escape / right-click resolution action ({@code #0625}, {@code #0638}).
     */
    public enum EscapeAction {
        /** Re-show hidden HUD before opening menu. */
        RESTORE_HIDDEN_HUD,
        /** Close topmost open modal/subscreen. */
        CLOSE_MODAL,
        /** Trigger dialogue rollback (when right-click behavior is ROLLBACK). */
        TRIGGER_ROLLBACK,
        /** Open game pause / main menu. */
        OPEN_MENU
    }

    /**
     * Manages layered input blocking ({@code SCREEN_MODAL > DEBUG_OVERLAY > ARENA > DIALOGUE}),
     * Escape/Right-Click priority, widget click absorption, dual-window isolation verification,
     * and {@code input.isActionDown(name)} facade for Java blocks
     * ({@code #0618}, {@code #0625}, {@code #0636}, {@code #0651}, {@code #0657}).
     */
    public static final class InputPriorityStack {

        private boolean modalActive = false;
        private boolean debugOverlayActive = false;
        private boolean arenaActive = false;

        /**
         * Creates a new {@code InputPriorityStack}.
         */
        public InputPriorityStack() {
        }

        /**
         * Sets whether a modal screen (e.g. {@code InputOverlayScreen}) is active ({@code #0618}).
         *
         * @param modalActive {@code true} if modal blocks lower layers
         */
        public void setModalActive(boolean modalActive) {
            this.modalActive = modalActive;
        }

        /**
         * Sets whether debug overlay is active.
         *
         * @param debugOverlayActive debug overlay toggle
         */
        public void setDebugOverlayActive(boolean debugOverlayActive) {
            this.debugOverlayActive = debugOverlayActive;
        }

        /**
         * Sets whether an interactive WASD arena is active.
         *
         * @param arenaActive arena toggle
         */
        public void setArenaActive(boolean arenaActive) {
            this.arenaActive = arenaActive;
        }

        /**
         * Returns top-priority active {@link InputLayer}.
         *
         * @return active {@link InputLayer}
         */
        public InputLayer getTopLayer() {
            if (modalActive) {
                return InputLayer.SCREEN_MODAL;
            }
            if (debugOverlayActive) {
                return InputLayer.DEBUG_OVERLAY;
            }
            if (arenaActive) {
                return InputLayer.ARENA;
            }
            return InputLayer.DIALOGUE;
        }

        /**
         * Returns whether {@code targetLayer} can receive input right now.
         *
         * @param targetLayer layer requesting input
         * @return {@code true} when no higher layer blocks it
         */
        public boolean canReceiveInput(InputLayer targetLayer) {
            return targetLayer.rank() >= getTopLayer().rank();
        }

        /**
         * Resolves Escape/right-click consistently across phases: restores hidden HUD first ({@code #0625}).
         *
         * @param isRightClick       {@code true} if right-click instead of Escape
         * @param hudHidden          whether HUD textbox is hidden
         * @param hasOpenModal       whether modal/subscreen is open
         * @param rightClickBehavior user preference ({@code "MENU"} or {@code "ROLLBACK"})
         * @return resolved {@link EscapeAction}
         */
        public static EscapeAction resolveEscapeOrRightClick(
                boolean isRightClick,
                boolean hudHidden,
                boolean hasOpenModal,
                String rightClickBehavior
        ) {
            if (hudHidden) {
                return EscapeAction.RESTORE_HIDDEN_HUD;
            }
            if (hasOpenModal) {
                return EscapeAction.CLOSE_MODAL;
            }
            if (isRightClick && "ROLLBACK".equalsIgnoreCase(rightClickBehavior)) {
                return EscapeAction.TRIGGER_ROLLBACK;
            }
            return EscapeAction.OPEN_MENU;
        }

        /**
         * Returns whether direct canvas click should advance dialogue, suppressing click-through when an
         * overlay button/widget consumed the click ({@code #0651}).
         *
         * @param widgetConsumedClick {@code true} if cursor hit/clicked an interactive widget
         * @param clickJustPressed    whether left mouse button pressed this frame
         * @return {@code true} when dialogue should advance
         */
        public static boolean shouldCanvasClickAdvance(boolean widgetConsumedClick, boolean clickJustPressed) {
            return clickJustPressed && !widgetConsumedClick;
        }

        /**
         * Queries abstract named action inside {@code @->{ ... }<<} blocks respecting {@code Remapper} and
         * blocking when {@code ARENA} / {@code DIALOGUE} is covered by modal ({@code #0636}).
         *
         * @param actionName logical action name
         * @param remapper   active remapper
         * @param input      active input manager
         * @param layer      requesting layer
         * @return {@code true} if permitted and held down
         */
        public boolean isActionDown(
                String actionName,
                KeybindingRemapper remapper,
                InputManager input,
                InputLayer layer
        ) {
            if (!canReceiveInput(layer)) {
                return false;
            }
            return remapper.isActionDown(actionName, input);
        }

        /**
         * Checks strict isolation between Studio Window A and Game Window B {@link InputManager} instances
         * ({@code #0657}).
         *
         * @param studioInput Studio Window A input manager
         * @param gameInput   Game Window B input manager
         * @param keyCode     key to verify doesn't leak from A to B
         * @return {@code true} if isolated
         */
        public static boolean verifyDualWindowIsolation(
                InputManager studioInput,
                InputManager gameInput,
                int keyCode
        ) {
            Objects.requireNonNull(studioInput, "studioInput must not be null");
            Objects.requireNonNull(gameInput, "gameInput must not be null");
            return studioInput != gameInput && (!studioInput.isKeyDown(keyCode) || !gameInput.isKeyDown(keyCode));
        }
    }

    /**
     * Deterministic `.vinput` frame-by-frame input recording &amp; headless arena replay player
     * ({@code #0616}, {@code #0673}).
     */
    public static final class InputMacroRecorderAndPlayer {

        /**
         * Single recorded input frame.
         *
         * @param frameIndex zero-based frame counter
         * @param dtSeconds  fixed timestep delta in seconds
         * @param keysDown   sorted list of pressed GLFW key codes
         * @param mouseX     logical mouse X
         * @param mouseY     logical mouse Y
         */
        public record MacroFrame(
                int frameIndex,
                double dtSeconds,
                List<Integer> keysDown,
                double mouseX,
                double mouseY
        ) {
            /** Defensively copies keysDown. */
            public MacroFrame {
                keysDown = keysDown != null ? List.copyOf(keysDown) : List.of();
            }
        }

        /**
         * Complete `.vinput` macro sequence.
         *
         * @param formatVersion schema version (1)
         * @param scenarioName  test/scenario name
         * @param frames        ordered list of {@link MacroFrame}s
         */
        public record MacroRecording(int formatVersion, String scenarioName, List<MacroFrame> frames) {
            /** Defensively copies frames. */
            public MacroRecording {
                frames = frames != null ? List.copyOf(frames) : List.of();
            }
        }

        /**
         * Minimal deterministic 2D arena state for fast headless macro regression checks ({@code #0673}).
         *
         * @param x horizontal position in pixels
         * @param y vertical position in pixels
         */
        public record ArenaPosition(double x, double y) {
        }

        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private final List<MacroFrame> recordedFrames = new ArrayList<>();

        /**
         * Creates a new {@code InputMacroRecorderAndPlayer}.
         */
        public InputMacroRecorderAndPlayer() {
        }

        /**
         * Captures one frame of active input into the recording buffer.
         *
         * @param dtSeconds frame delta seconds
         * @param keysDown  keys held during frame
         * @param mouseX    logical cursor X
         * @param mouseY    logical cursor Y
         */
        public synchronized void captureFrame(
                double dtSeconds,
                List<Integer> keysDown,
                double mouseX,
                double mouseY
        ) {
            int[] sorted = (keysDown != null ? keysDown : List.<Integer>of())
                    .stream().mapToInt(Integer::intValue).sorted().distinct().toArray();
            List<Integer> list = Arrays.stream(sorted).boxed().toList();
            recordedFrames.add(new MacroFrame(recordedFrames.size(), dtSeconds, list, mouseX, mouseY));
        }

        /**
         * Serializes captured frames into `.vinput` UTF-8 JSON ({@code #0616}).
         *
         * @param scenarioName scenario identifier
         * @return `.vinput` JSON string
         */
        public synchronized String exportVinputJson(String scenarioName) {
            MacroRecording rec = new MacroRecording(1, scenarioName != null ? scenarioName : "default",
                    recordedFrames);
            return GSON.toJson(rec);
        }

        /**
         * Parses a `.vinput` JSON recording ({@code #0616}).
         *
         * @param vinputJson `.vinput` JSON payload
         * @return parsed {@link MacroRecording}
         */
        public static MacroRecording parseVinputJson(String vinputJson) {
            Objects.requireNonNull(vinputJson, "vinputJson must not be null");
            MacroRecording rec = GSON.fromJson(vinputJson, MacroRecording.class);
            if (rec == null || rec.frames() == null) {
                throw new IllegalArgumentException("Invalid .vinput recording JSON");
            }
            return rec;
        }

        /**
         * Replays a {@link MacroRecording} against an arena simulation deterministically
         * ({@code #0616}, {@code #0673}).
         *
         * @param recording   parsed macro recording
         * @param speedPxSec  player WASD movement speed in px/sec
         * @param startX      starting X
         * @param startY      starting Y
         * @return deterministic final {@link ArenaPosition}
         */
        public static ArenaPosition replayArena(
                MacroRecording recording,
                double speedPxSec,
                double startX,
                double startY
        ) {
            Objects.requireNonNull(recording, "recording must not be null");
            double x = startX;
            double y = startY;
            for (MacroFrame frame : recording.frames()) {
                double safeDt = DialogueAndUiInputGuards.clampSleepDelta(frame.dtSeconds());
                double dx = 0.0;
                double dy = 0.0;
                if (frame.keysDown().contains(GLFW.GLFW_KEY_A)) {
                    dx -= 1.0;
                }
                if (frame.keysDown().contains(GLFW.GLFW_KEY_D)) {
                    dx += 1.0;
                }
                if (frame.keysDown().contains(GLFW.GLFW_KEY_W)) {
                    dy -= 1.0;
                }
                if (frame.keysDown().contains(GLFW.GLFW_KEY_S)) {
                    dy += 1.0;
                }
                double len = Math.hypot(dx, dy);
                if (len > 0.0) {
                    x += (dx / len) * speedPxSec * safeDt;
                    y += (dy / len) * speedPxSec * safeDt;
                }
            }
            return new ArenaPosition(x, y);
        }
    }

    /**
     * Collection of UI/Dialogue input guards and ergonomic interactions covering
     * {@code #0617}, {@code #0621}, {@code #0624}, {@code #0629}, {@code #0633}, {@code #0634},
     * {@code #0641}, {@code #0648}, {@code #0649}, {@code #0652}, {@code #0654}, {@code #0655},
     * {@code #0658}, {@code #0659}, {@code #0661}, {@code #0663}–{@code #0667}, {@code #0677}, {@code #0679}.
     */
    public static final class DialogueAndUiInputGuards {

        /**
         * Creates a {@code DialogueAndUiInputGuards} helper.
         */
        public DialogueAndUiInputGuards() {
        }

        /**
         * Clamps large delta-time spikes caused by laptop sleep/screensaver to {@code 0.05 s} ({@code #0661}).
         *
         * @param dtSeconds raw elapsed seconds
         * @return safe physics/animation delta in {@code [0.0, 0.05]}
         */
        public static double clampSleepDelta(double dtSeconds) {
            if (!Double.isFinite(dtSeconds) || dtSeconds <= 0.0) {
                return 0.0;
            }
            return Math.min(dtSeconds, 0.05);
        }

        /**
         * Filters out ISO/ASCII control characters ({@code \u0000..\u001F}) while preserving valid
         * UTF-32 codepoints including CJK/Emoji surrogate pairs ({@code #0624}, {@code #0652}).
         *
         * @param rawInput candidate player text input
         * @param maxChars maximum permitted UTF-16 length
         * @return sanitized UTF-8/UTF-32 safe string
         */
        public static String sanitizePlayerTextInput(String rawInput, int maxChars) {
            if (rawInput == null || maxChars <= 0) {
                return "";
            }
            StringBuilder out = new StringBuilder();
            int idx = 0;
            while (idx < rawInput.length() && out.length() < maxChars) {
                int cp = rawInput.codePointAt(idx);
                int charCount = Character.charCount(cp);
                if (cp > 0x1F && cp != 0x7F && Character.isValidCodePoint(cp) && !Character.isISOControl(cp)) {
                    if (out.length() + charCount <= maxChars) {
                        out.appendCodePoint(cp);
                    }
                }
                idx += charCount;
            }
            return out.toString();
        }

        /**
         * Enforces a 250 ms post-typewriter-completion lock preventing accidental double-advance clicks
         * ({@code #0617}), plus a 200 ms guard after opening a {@code menu:} choice screen ({@code #0629}).
         *
         * @param msSinceTransition elapsed ms since typewriter completion or menu open
         * @param guardMs           required lock duration (e.g. {@code 250} or {@code 200})
         * @return {@code true} if advance/confirm is permitted
         */
        public static boolean isAdvanceAllowedAfterGuard(long msSinceTransition, long guardMs) {
            return msSinceTransition >= guardMs;
        }

        /**
         * Computes initial keyboard/gamepad choice focus index when opening {@code menu:} ({@code #0629}).
         *
         * @param autoFocusFirstChoice preference flag
         * @param choiceCount          number of visible choices
         * @return {@code 0} when auto-focus enabled and choices exist, otherwise {@code -1}
         */
        public static int resolveInitialChoiceFocus(boolean autoFocusFirstChoice, int choiceCount) {
            return (autoFocusFirstChoice && choiceCount > 0) ? 0 : -1;
        }

        /**
         * Normalizes scroll deltas across macOS high-res trackpads (tiny +-0.1 deltas) and discrete
         * mouse wheels (+-1.0) ({@code #0634}).
         *
         * @param rawScrollDelta raw GLFW scroll delta
         * @param multiplier     user sensitivity multiplier
         * @return normalized pixel/row scroll offset
         */
        public static double normalizeScrollDelta(double rawScrollDelta, double multiplier) {
            double mult = Double.isFinite(multiplier) ? Math.clamp(multiplier, 0.25, 4.0) : 1.0;
            return rawScrollDelta * mult;
        }

        /**
         * Computes 2D grid navigation step index supporting optional row wrap-around ({@code #0641}).
         *
         * @param currentIndex current 0-based slot index
         * @param columns      number of grid columns ({@code >= 1})
         * @param totalItems   total visible items ({@code >= 1})
         * @param dCol         column delta (-1, 0, +1)
         * @param dRow         row delta (-1, 0, +1)
         * @param wrapAround   whether moving past end of row wraps sequentially
         * @return next 0-based slot index
         */
        public static int stepGridFocus(
                int currentIndex,
                int columns,
                int totalItems,
                int dCol,
                int dRow,
                boolean wrapAround
        ) {
            if (columns < 1 || totalItems < 1) {
                return 0;
            }
            int idx = Math.clamp(currentIndex, 0, totalItems - 1);
            if (dCol != 0) {
                int candidate = idx + dCol;
                if (wrapAround) {
                    return Math.floorMod(candidate, totalItems);
                }
                return Math.clamp(candidate, 0, totalItems - 1);
            }
            if (dRow != 0) {
                int candidate = idx + (dRow * columns);
                if (wrapAround) {
                    return Math.floorMod(candidate, totalItems);
                }
                if (candidate >= 0 && candidate < totalItems) {
                    return candidate;
                }
            }
            return idx;
        }

        /**
         * Computes radial wheel sector index from vector {@code (dx, dy)} ({@code #0658}).
         *
         * @param dx          horizontal vector from wheel center
         * @param dy          vertical vector from wheel center
         * @param sectorCount number of sectors ({@code >= 2})
         * @return selected sector index {@code [0, sectorCount)}, or {@code -1} inside dead center
         */
        public static int resolveRadialSector(double dx, double dy, int sectorCount) {
            if (sectorCount < 2 || Math.hypot(dx, dy) < 12.0) {
                return -1;
            }
            double angle = Math.atan2(dy, dx);
            if (angle < 0.0) {
                angle += Math.PI * 2.0;
            }
            double sectorAngle = (Math.PI * 2.0) / sectorCount;
            return Math.min(sectorCount - 1, (int) Math.floor(((angle + sectorAngle * 0.5)
                    % (Math.PI * 2.0)) / sectorAngle));
        }

        /**
         * Applies 40% slowdown hotspot magnetism to virtual gamepad cursor speed near hotspots ({@code #0667}).
         *
         * @param baseSpeedPxSec nominal pointer speed
         * @param nearHotspot    {@code true} when within magnetism radius
         * @return adjusted pointer speed in px/s
         */
        public static double computeMagneticPointerSpeed(double baseSpeedPxSec, boolean nearHotspot) {
            return nearHotspot ? baseSpeedPxSec * 0.60 : baseSpeedPxSec;
        }

        /**
         * Stateful slider drag tracker continuing drag capture smoothly even when cursor leaves bar ({@code #0655}).
         */
        public static final class SliderDragCapture {

            private boolean dragging = false;
            private float value = 0.5f;

            /**
             * Creates a {@code SliderDragCapture}.
             */
            public SliderDragCapture() {
            }

            /**
             * Updates slider drag capture state.
             *
             * @param mouseDown   current mouse down state
             * @param pointerOver whether cursor hit slider on mouse press
             * @param cursorX     logical cursor X
             * @param barX        slider left X
             * @param barWidth    slider width
             * @return normalized value in {@code [0, 1]}
             */
            public float update(boolean mouseDown, boolean pointerOver, double cursorX, double barX, double barWidth) {
                if (mouseDown && !dragging && pointerOver) {
                    dragging = true;
                } else if (!mouseDown) {
                    dragging = false;
                }
                if (dragging && barWidth > 0.0) {
                    value = Math.clamp((float) ((cursorX - barX) / barWidth), 0.0f, 1.0f);
                }
                return value;
            }

            /**
             * Returns whether slider drag is currently captured.
             *
             * @return {@code true} while dragging
             */
            public boolean isDragging() {
                return dragging;
            }
        }

        /**
         * Matches Konami / secret code key sequences cleanly and notifies listener once completed ({@code #0665}).
         */
        public static final class SecretSequenceDetector {

            private final int[] targetSequence;
            private int matchedCount = 0;

            /**
             * Creates a {@code SecretSequenceDetector} for {@code targetSequence}.
             *
             * @param targetSequence expected ordered GLFW key code sequence
             */
            public SecretSequenceDetector(int... targetSequence) {
                if (targetSequence == null || targetSequence.length == 0) {
                    throw new IllegalArgumentException("targetSequence must not be empty");
                }
                this.targetSequence = targetSequence.clone();
            }

            /**
             * Feeds a newly pressed key code, returning {@code true} at exact sequence completion ({@code #0665}).
             *
             * @param keyCode GLFW key code pressed
             * @return {@code true} if complete sequence matched
             */
            public boolean onKeyPressed(int keyCode) {
                if (keyCode == targetSequence[matchedCount]) {
                    matchedCount++;
                    if (matchedCount == targetSequence.length) {
                        matchedCount = 0;
                        return true;
                    }
                    return false;
                }
                matchedCount = (keyCode == targetSequence[0]) ? 1 : 0;
                return false;
            }
        }

        /**
         * Preserves preferred cursor column across vertical line moves in multiline notes ({@code #0666}).
         */
        public static final class MultilineCursorTracker {

            private int line = 0;
            private int column = 0;
            private int preferredColumn = 0;

            /**
             * Creates a {@code MultilineCursorTracker}.
             */
            public MultilineCursorTracker() {
            }

            /**
             * Moves cursor horizontally, updating preferred column.
             *
             * @param targetCol target horizontal column
             * @param lines     text lines
             */
            public void setHorizontal(int targetCol, List<String> lines) {
                int len = lineLength(lines, line);
                column = Math.clamp(targetCol, 0, len);
                preferredColumn = column;
            }

            /**
             * Moves cursor vertically while preserving {@code preferredColumn} across shorter lines ({@code #0666}).
             *
             * @param deltaLines vertical line step (-1 up, +1 down)
             * @param lines      text lines
             */
            public void moveVertical(int deltaLines, List<String> lines) {
                if (lines == null || lines.isEmpty()) {
                    line = 0;
                    column = 0;
                    return;
                }
                line = Math.clamp(line + deltaLines, 0, lines.size() - 1);
                column = Math.min(preferredColumn, lines.get(line).length());
            }

            /**
             * Returns current 0-based line index.
             *
             * @return line index
             */
            public int getLine() {
                return line;
            }

            /**
             * Returns current 0-based column index.
             *
             * @return column index
             */
            public int getColumn() {
                return column;
            }

            private static int lineLength(List<String> lines, int lineIdx) {
                if (lines == null || lineIdx < 0 || lineIdx >= lines.size()) {
                    return 0;
                }
                return lines.get(lineIdx).length();
            }
        }

        /**
         * Hover sound cue debouncer enforcing &gt;= 50 ms spacing ({@code #0664}),
         * debug ADV/NVL toggle ({@code #0621}),
         * studio middle-click inspector ({@code #0663}), Shift+G quick-jump filter ({@code #0654}), Tab studio
         * column cycling ({@code #0679}), tutorial pill dimming ({@code #0677}), and ImageMap hotspot Tab cycle
         * ({@code #0633}).
         */
        public static final class StudioAndHudHelpers {

            private long lastHoverCueMs = -1000L;
            private boolean arenaTutorialVisible = true;
            private int studioPanelIndex = 0;
            private int hotspotIndex = 0;

            /**
             * Creates a {@code StudioAndHudHelpers} instance.
             */
            public StudioAndHudHelpers() {
            }

            /**
             * Checks 50 ms hover cue cooldown ({@code #0664}).
             *
             * @param nowMs current monotonic time ms
             * @return {@code true} if hover sound may play
             */
            public boolean tryPlayHoverCue(long nowMs) {
                if ((nowMs - lastHoverCueMs) >= 50L) {
                    lastHoverCueMs = nowMs;
                    return true;
                }
                return false;
            }

            /**
             * Dims/hides WASD first-contact tutorial pill as soon as player moves ({@code dx != 0}) ({@code #0677}).
             *
             * @param dx horizontal movement delta
             * @param dy vertical movement delta
             * @return {@code true} if tutorial hint remains highlighted
             */
            public boolean updateArenaTutorialHint(double dx, double dy) {
                if (Math.abs(dx) > 1e-6 || Math.abs(dy) > 1e-6) {
                    arenaTutorialVisible = false;
                }
                return arenaTutorialVisible;
            }

            /**
             * Evaluates debug-only ADV/NVL toggle returning target mode when debug mode is active ({@code #0621}).
             *
             * @param isDebugMode {@code true} only in studio/debug builds
             * @param currentMode current mode ({@code "ADV"} or {@code "NVL"})
             * @return toggled mode, or unchanged when in release mode
             */
            public static String toggleDialogueModeIfDebug(boolean isDebugMode, String currentMode) {
                if (!isDebugMode) {
                    return currentMode;
                }
                return "NVL".equalsIgnoreCase(currentMode) ? "ADV" : "NVL";
            }

            /**
             * Filters story labels case-insensitively for studio {@code Shift+G} quick-jump ({@code #0654}).
             *
             * @param allLabels available labels
             * @param query     search filter string
             * @return matching labels in order
             */
            public static List<String> filterLabelsForQuickJump(List<String> allLabels, String query) {
                if (allLabels == null || allLabels.isEmpty()) {
                    return List.of();
                }
                String q = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";
                List<String> out = new ArrayList<>();
                for (String lbl : allLabels) {
                    if (lbl != null && (q.isEmpty() || lbl.toLowerCase(Locale.ROOT).contains(q))) {
                        out.add(lbl);
                    }
                }
                return List.copyOf(out);
            }

            /**
             * Cycles focus across Studio panels via {@code Tab} / {@code Shift+Tab} ({@code #0679})
             * or ImageMap hotspots ({@code #0633}).
             *
             * @param reverse    {@code true} for backward Shift+Tab
             * @param panelCount total focusable panels
             * @return active 0-based panel index
             */
            public int cycleStudioPanelFocus(boolean reverse, int panelCount) {
                if (panelCount <= 0) {
                    return 0;
                }
                studioPanelIndex = Math.floorMod(studioPanelIndex + (reverse ? -1 : 1), panelCount);
                return studioPanelIndex;
            }

            /**
             * Cycles keyboard Tab focus through defined {@code ImageMap} hotspots ({@code #0633}).
             *
             * @param reverse      backward direction flag
             * @param hotspotNames ordered hotspot labels
             * @return active hotspot screen-reader label
             */
            public Optional<String> cycleHotspotFocus(boolean reverse, List<String> hotspotNames) {
                if (hotspotNames == null || hotspotNames.isEmpty()) {
                    return Optional.empty();
                }
                hotspotIndex = Math.floorMod(hotspotIndex + (reverse ? -1 : 1), hotspotNames.size());
                return Optional.ofNullable(hotspotNames.get(hotspotIndex));
            }

            /**
             * Logs middle-click widget inspect details strictly inside studio debug mode ({@code #0663}).
             *
             * @param isStudioMode whether running inside studio preview
             * @param widgetName   hit widget identifier
             * @param screenName   active screen name
             * @param logicalX     logical X
             * @param logicalY     logical Y
             * @param logSink      studio console sink
             * @return {@code true} if logged
             */
            public static boolean inspectWidgetOnMiddleClick(
                    boolean isStudioMode,
                    String widgetName,
                    String screenName,
                    double logicalX,
                    double logicalY,
                    Consumer<String> logSink
            ) {
                if (!isStudioMode || logSink == null) {
                    return false;
                }
                logSink.accept("[Inspect] screen=" + screenName + " widget=" + widgetName
                        + " pos=(" + Math.round(logicalX) + "," + Math.round(logicalY) + ")");
                return true;
            }

            /**
             * Checks screenshot hotkey ({@code F10} or {@code PrintScreen}) independently of QuickMenu ({@code #0648}).
             *
             * @param input input manager
             * @return {@code true} when screenshot should trigger this frame
             */
            public static boolean isScreenshotHotkeyPressed(InputManager input) {
                Objects.requireNonNull(input, "input must not be null");
                return input.wasKeyJustPressed(GLFW.GLFW_KEY_F10)
                        || input.wasKeyJustPressed(GLFW.GLFW_KEY_PRINT_SCREEN);
            }

            /**
             * Aligns carried drag item relative to cursor hotspot in letterboxed logical space ({@code #0659}).
             *
             * @param logicalCursorX logical pointer X
             * @param logicalCursorY logical pointer Y
             * @param hotspotOffsetX item hotspot X offset
             * @param hotspotOffsetY item hotspot Y offset
             * @return top-left aligned position as {@link InputMacroRecorderAndPlayer.ArenaPosition}
             */
            public static InputMacroRecorderAndPlayer.ArenaPosition alignDragHotspot(
                    double logicalCursorX,
                    double logicalCursorY,
                    double hotspotOffsetX,
                    double hotspotOffsetY
            ) {
                return new InputMacroRecorderAndPlayer.ArenaPosition(
                        logicalCursorX - hotspotOffsetX,
                        logicalCursorY - hotspotOffsetY
                );
            }
        }

        /**
         * Controller-friendly on-screen virtual keyboard state for {@code input} prompts ({@code #0649}).
         */
        public static final class VirtualKeyboardState {

            private final StringBuilder buffer = new StringBuilder();
            private boolean confirmed = false;

            /**
             * Creates an empty {@code VirtualKeyboardState}.
             */
            public VirtualKeyboardState() {
            }

            /**
             * Inserts character filtered through {@link #sanitizePlayerTextInput}.
             *
             * @param glyph    selected glyph string
             * @param maxChars maximum buffer length
             */
            public void typeGlyph(String glyph, int maxChars) {
                String clean = sanitizePlayerTextInput(glyph, maxChars);
                if (!clean.isEmpty() && (buffer.length() + clean.length()) <= maxChars) {
                    buffer.append(clean);
                }
            }

            /**
             * Deletes last Unicode codepoint if non-empty.
             */
            public void backspace() {
                if (buffer.isEmpty()) {
                    return;
                }
                int lastStart = buffer.offsetByCodePoints(buffer.length(), -1);
                buffer.delete(lastStart, buffer.length());
            }

            /**
             * Confirms current text buffer.
             *
             * @return finalized sanitized text
             */
            public String confirm() {
                confirmed = true;
                return buffer.toString();
            }

            /**
             * Returns current buffer contents.
             *
             * @return buffer string
             */
            public String getText() {
                return buffer.toString();
            }

            /**
             * Returns whether user confirmed virtual keyboard input.
             *
             * @return {@code true} if confirmed
             */
            public boolean isConfirmed() {
                return confirmed;
            }
        }
    }
}
