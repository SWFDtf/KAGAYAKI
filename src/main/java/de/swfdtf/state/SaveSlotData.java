package de.swfdtf.state;

import de.swfdtf.graphics.SceneController;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable save-slot model persisted as GZIP-compressed JSON ({@code .ksav}) by {@link SaveManager}.
 *
 * <p>Stores slot metadata (including PNG thumbnail bytes), deep variable state, decoupled script
 * execution coordinates/stage snapshot, and dialogue history entries without importing
 * {@code de.swfdtf.parser}.</p>
 *
 * @param metadata       save-slot header metadata and optional PNG thumbnail
 * @param variables      saved {@link StateStore} variable map
 * @param scriptPosition saved script execution coordinates and {@link SceneController.StageSnapshot}
 * @param historyEntries saved dialogue backlog entries
 */
public record SaveSlotData(
        SaveMetadata metadata,
        Map<String, Object> variables,
        SavedScriptPosition scriptPosition,
        List<HistoryLog.HistoryEntry> historyEntries
) {

    /** Defensively normalizes variable and history collections. */
    public SaveSlotData {
        variables = variables != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(variables))
                : Map.of();
        historyEntries = historyEntries != null ? List.copyOf(historyEntries) : List.of();
    }

    /**
     * Returns a copy of this save slot with replaced {@link SaveMetadata}.
     *
     * @param updatedMetadata replacement metadata
     * @return updated {@link SaveSlotData}
     */
    public SaveSlotData withMetadata(SaveMetadata updatedMetadata) {
        return new SaveSlotData(updatedMetadata, variables, scriptPosition, historyEntries);
    }

    /**
     * Lightweight save-slot header metadata stored alongside the full state so save/load grids
     * can render previews and PNG thumbnails without deserializing full game state or custom classes.
     *
     * @param slotId          canonical slot identifier (e.g., {@code "slot_1"}, {@code "quick"}, {@code "auto_1"})
     * @param customName      player-defined or auto-generated slot title (nullable)
     * @param savedAt         UTC timestamp when saved
     * @param currentLabel    active {@code .vjava} label at save time (nullable)
     * @param dialoguePreview snippet of current dialogue or scene description (nullable)
     * @param playtimeSeconds accumulated session playtime in seconds
     * @param gameVersion     game version string from project metadata
     * @param thumbnailWidth  PNG thumbnail width in pixels ({@code 0} if absent)
     * @param thumbnailHeight PNG thumbnail height in pixels ({@code 0} if absent)
     * @param thumbnailPng    encoded PNG bytes (defensively copied; empty array if absent)
     */
    public record SaveMetadata(
            String slotId,
            String customName,
            Instant savedAt,
            String currentLabel,
            String dialoguePreview,
            double playtimeSeconds,
            String gameVersion,
            int thumbnailWidth,
            int thumbnailHeight,
            byte[] thumbnailPng
    ) {
        /** Defensively copies thumbnail PNG bytes. */
        public SaveMetadata {
            thumbnailPng = thumbnailPng != null ? thumbnailPng.clone() : new byte[0];
        }

        /**
         * Returns a defensive copy of the encoded PNG thumbnail bytes.
         *
         * @return copy of PNG thumbnail bytes
         */
        @Override
        public byte[] thumbnailPng() {
            return thumbnailPng.clone();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof SaveMetadata other)) {
                return false;
            }
            return Double.compare(playtimeSeconds, other.playtimeSeconds) == 0
                    && thumbnailWidth == other.thumbnailWidth
                    && thumbnailHeight == other.thumbnailHeight
                    && Objects.equals(slotId, other.slotId)
                    && Objects.equals(customName, other.customName)
                    && Objects.equals(savedAt, other.savedAt)
                    && Objects.equals(currentLabel, other.currentLabel)
                    && Objects.equals(dialoguePreview, other.dialoguePreview)
                    && Objects.equals(gameVersion, other.gameVersion)
                    && Arrays.equals(thumbnailPng, other.thumbnailPng);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(
                    slotId, customName, savedAt, currentLabel, dialoguePreview,
                    playtimeSeconds, gameVersion, thumbnailWidth, thumbnailHeight
            );
            result = 31 * result + Arrays.hashCode(thumbnailPng);
            return result;
        }
    }

    /**
     * Serializable call-stack or nested-block frame descriptor decoupled from AST statement objects.
     *
     * @param labelName      enclosing label name
     * @param statementIndex resume statement index within that label/block
     * @param isCallBoundary {@code true} for subroutine {@code call} frames, {@code false} for nested blocks
     */
    public record SavedStackFrame(
            String labelName,
            int statementIndex,
            boolean isCallBoundary
    ) {
    }

    /**
     * Serializable script execution state descriptor decoupled from {@code ScriptRuntime}.
     *
     * @param currentLabel       active script label name
     * @param statementIndex     active statement index within the current block/label
     * @param callStack          immutable list of call/block frames
     * @param statusName         runtime status name (e.g., {@code "WAITING_DIALOGUE"})
     * @param activeSpeakerId    active speaker ID (nullable)
     * @param activeSpeakerName  resolved active speaker name (nullable)
     * @param activeDialogueText active dialogue line text (nullable)
     * @param nvlMode            whether NVL cumulative mode is active
     * @param nvlLines           accumulated NVL lines
     * @param activeVoicePath    pending or active voice clip path (nullable)
     * @param stageSnapshot      visual stage snapshot (background and active sprites)
     */
    public record SavedScriptPosition(
            String currentLabel,
            int statementIndex,
            List<SavedStackFrame> callStack,
            String statusName,
            String activeSpeakerId,
            String activeSpeakerName,
            String activeDialogueText,
            boolean nvlMode,
            List<String> nvlLines,
            String activeVoicePath,
            SceneController.StageSnapshot stageSnapshot
    ) {
        /** Defensively copies call stack and NVL line lists. */
        public SavedScriptPosition {
            callStack = callStack != null ? List.copyOf(callStack) : List.of();
            nvlLines = nvlLines != null ? List.copyOf(nvlLines) : List.of();
        }
    }
}
