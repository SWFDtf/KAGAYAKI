package de.swfdtf.state;

import de.swfdtf.state.SaveSlotData.SaveMetadata;
import de.swfdtf.state.SaveSlotData.SavedScriptPosition;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Query, search, sorting, and tagging service built on top of {@link SaveManager}.
 *
 * <p>Safely scans {@code .ksav} files in {@link SaveManager#getSavesDir()} and loads them via
 * {@link SaveManager#loadSlot(String)}, skipping corrupt or concurrently removed slot files.</p>
 */
public final class SaveSlotQueryService {

    private static final Comparator<SaveSlotData> NEWEST_FIRST = Comparator
            .comparing((SaveSlotData data) -> extractSavedAt(data), Comparator.reverseOrder())
            .thenComparing(SaveSlotQueryService::extractSlotId);

    private static final Comparator<SaveSlotData> PLAYTIME_DESC = Comparator
            .comparingDouble((SaveSlotData data) -> data.metadata() != null ? data.metadata().playtimeSeconds() : 0.0)
            .reversed()
            .thenComparing(NEWEST_FIRST);

    private final SaveManager saveManager;
    private final Map<String, Set<String>> tagsBySlot = new LinkedHashMap<>();

    /**
     * Creates a query service backed by {@code saveManager}.
     *
     * @param saveManager save manager instance (must not be {@code null})
     */
    public SaveSlotQueryService(SaveManager saveManager) {
        this.saveManager = Objects.requireNonNull(saveManager, "saveManager must not be null");
    }

    /**
     * Associates a tag with {@code slotId}.
     *
     * @param slotId save slot identifier (must not be {@code null} or blank)
     * @param tag    tag label (must not be {@code null} or blank)
     */
    public synchronized void addTag(String slotId, String tag) {
        String normalizedSlotId = requireNonBlank(slotId, "slotId");
        String normalizedTag = requireNonBlank(tag, "tag");
        tagsBySlot.computeIfAbsent(normalizedSlotId, ignored -> new LinkedHashSet<>()).add(normalizedTag);
    }

    /**
     * Removes a tag from {@code slotId} if present.
     *
     * @param slotId save slot identifier (must not be {@code null} or blank)
     * @param tag    tag label to remove (must not be {@code null} or blank)
     */
    public synchronized void removeTag(String slotId, String tag) {
        String normalizedSlotId = requireNonBlank(slotId, "slotId");
        String normalizedTag = requireNonBlank(tag, "tag");
        Set<String> tags = tagsBySlot.get(normalizedSlotId);
        if (tags != null) {
            tags.remove(normalizedTag);
            if (tags.isEmpty()) {
                tagsBySlot.remove(normalizedSlotId);
            }
        }
    }

    /**
     * Returns an unmodifiable set of tags associated with {@code slotId}.
     *
     * @param slotId save slot identifier (must not be {@code null} or blank)
     * @return immutable set of tags (empty if none exist)
     */
    public synchronized Set<String> getTags(String slotId) {
        String normalizedSlotId = requireNonBlank(slotId, "slotId");
        Set<String> tags = tagsBySlot.get(normalizedSlotId);
        return tags != null ? Set.copyOf(tags) : Set.of();
    }

    /**
     * Searches all available save slots case-insensitively across slot ID, script label,
     * caption/title/dialogue preview, and assigned tags.
     *
     * @param query search query string (must not be {@code null}; blank returns all slots newest-first)
     * @return unmodifiable list of matching {@link SaveSlotData} sorted newest-first
     */
    public synchronized List<SaveSlotData> search(String query) {
        Objects.requireNonNull(query, "query must not be null");
        List<SaveSlotData> allSlots = loadAllSlotsSafely();
        allSlots.sort(NEWEST_FIRST);
        if (query.isBlank()) {
            return List.copyOf(allSlots);
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<SaveSlotData> matches = new ArrayList<>();
        for (SaveSlotData slot : allSlots) {
            if (matchesQuery(slot, needle)) {
                matches.add(slot);
            }
        }
        return List.copyOf(matches);
    }

    /**
     * Returns all available save slots carrying {@code tag} (case-insensitive match), sorted newest-first.
     *
     * @param tag tag name to filter by (must not be {@code null})
     * @return unmodifiable list of matching {@link SaveSlotData}
     */
    public synchronized List<SaveSlotData> filterByTag(String tag) {
        Objects.requireNonNull(tag, "tag must not be null");
        if (tag.isBlank()) {
            return List.of();
        }
        String normalizedTag = tag.trim();
        List<SaveSlotData> matches = new ArrayList<>();
        for (SaveSlotData slot : loadAllSlotsSafely()) {
            String slotId = extractSlotId(slot);
            Set<String> tags = tagsBySlot.getOrDefault(slotId, Set.of());
            boolean hasTag = tags.stream().anyMatch(t -> t.equalsIgnoreCase(normalizedTag));
            if (hasTag) {
                matches.add(slot);
            }
        }
        matches.sort(NEWEST_FIRST);
        return List.copyOf(matches);
    }

    /**
     * Returns all available save slots whose active script label equals {@code currentLabel}, sorted newest-first.
     *
     * @param currentLabel script label name (must not be {@code null})
     * @return unmodifiable list of matching {@link SaveSlotData}
     */
    public synchronized List<SaveSlotData> filterByLabel(String currentLabel) {
        Objects.requireNonNull(currentLabel, "currentLabel must not be null");
        if (currentLabel.isBlank()) {
            return List.of();
        }
        String targetLabel = currentLabel.trim();
        List<SaveSlotData> matches = new ArrayList<>();
        for (SaveSlotData slot : loadAllSlotsSafely()) {
            String resolvedLabel = extractLabel(slot);
            if (targetLabel.equals(resolvedLabel)) {
                matches.add(slot);
            }
        }
        matches.sort(NEWEST_FIRST);
        return List.copyOf(matches);
    }

    /**
     * Lists all safely loadable save slots ordered from newest to oldest {@code savedAt} timestamp.
     *
     * @return unmodifiable list of {@link SaveSlotData} sorted newest-first
     */
    public synchronized List<SaveSlotData> listSortedByNewest() {
        List<SaveSlotData> allSlots = loadAllSlotsSafely();
        allSlots.sort(NEWEST_FIRST);
        return List.copyOf(allSlots);
    }

    /**
     * Lists all safely loadable save slots ordered by accumulated playtime descending.
     *
     * @return unmodifiable list of {@link SaveSlotData} sorted by playtime descending
     */
    public synchronized List<SaveSlotData> listSortedByPlaytime() {
        List<SaveSlotData> allSlots = loadAllSlotsSafely();
        allSlots.sort(PLAYTIME_DESC);
        return List.copyOf(allSlots);
    }

    /**
     * Returns the most recently saved slot if at least one valid slot exists.
     *
     * @return newest {@link SaveSlotData} wrapped in {@link Optional}, or {@link Optional#empty()} if none exist
     */
    public synchronized Optional<SaveSlotData> findLatestSlot() {
        List<SaveSlotData> sorted = listSortedByNewest();
        return sorted.isEmpty() ? Optional.empty() : Optional.of(sorted.get(0));
    }

    private List<SaveSlotData> loadAllSlotsSafely() {
        Path savesDir = saveManager.getSavesDir();
        if (savesDir == null || !Files.isDirectory(savesDir)) {
            return new ArrayList<>();
        }
        List<String> slotIds = new ArrayList<>();
        try (Stream<Path> stream = Files.list(savesDir)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                Path fileNamePath = path.getFileName();
                if (fileNamePath != null) {
                    String fileName = fileNamePath.toString();
                    if (fileName.endsWith(SaveManager.SAVE_EXTENSION)) {
                        String slotId = fileName.substring(0, fileName.length() - SaveManager.SAVE_EXTENSION.length());
                        if (!slotId.isBlank()) {
                            slotIds.add(slotId);
                        }
                    }
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list save directory " + savesDir, e);
        }

        slotIds.sort(String::compareTo);
        List<SaveSlotData> loaded = new ArrayList<>(slotIds.size());
        for (String slotId : slotIds) {
            try {
                Optional<SaveMetadata> metaOpt = saveManager.readSlotMetadata(slotId);
                if (metaOpt.isPresent()) {
                    loaded.add(new SaveSlotData(metaOpt.get(), Map.of(), null, List.of(), null));
                }
            } catch (IOException | RuntimeException ignored) {
                // Skip corrupt, incompatible, or concurrently deleted save slot files safely.
            }
        }
        return loaded;
    }

    private boolean matchesQuery(SaveSlotData slot, String lowerNeedle) {
        SaveMetadata meta = slot.metadata();
        SavedScriptPosition pos = slot.scriptPosition();

        String slotId = extractSlotId(slot);
        if (containsIgnoreCase(slotId, lowerNeedle)) {
            return true;
        }
        if (meta != null && (containsIgnoreCase(meta.customName(), lowerNeedle)
                || containsIgnoreCase(meta.currentLabel(), lowerNeedle)
                || containsIgnoreCase(meta.dialoguePreview(), lowerNeedle))) {
            return true;
        }
        if (pos != null && (containsIgnoreCase(pos.currentLabel(), lowerNeedle)
                || containsIgnoreCase(pos.activeDialogueText(), lowerNeedle)
                || containsIgnoreCase(pos.activeSpeakerName(), lowerNeedle))) {
            return true;
        }
        Set<String> tags = tagsBySlot.getOrDefault(slotId, Set.of());
        for (String tag : tags) {
            if (containsIgnoreCase(tag, lowerNeedle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIgnoreCase(String candidate, String lowerNeedle) {
        return candidate != null && candidate.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    private static Instant extractSavedAt(SaveSlotData data) {
        if (data.metadata() != null && data.metadata().savedAt() != null) {
            return data.metadata().savedAt();
        }
        return Instant.EPOCH;
    }

    private static String extractSlotId(SaveSlotData data) {
        if (data.metadata() != null && data.metadata().slotId() != null) {
            return data.metadata().slotId();
        }
        return "";
    }

    private static String extractLabel(SaveSlotData data) {
        if (data.metadata() != null && data.metadata().currentLabel() != null) {
            return data.metadata().currentLabel();
        }
        if (data.scriptPosition() != null && data.scriptPosition().currentLabel() != null) {
            return data.scriptPosition().currentLabel();
        }
        return "";
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }
}
