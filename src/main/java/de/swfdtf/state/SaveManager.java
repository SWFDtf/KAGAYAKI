package de.swfdtf.state;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import de.swfdtf.state.SaveSlotData.SaveMetadata;
import de.swfdtf.state.SaveSlotData.SavedScriptPosition;
import org.lwjgl.stb.STBIWriteCallback;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Tier 2 save/load manager persisting {@link SaveSlotData} as atomic GZIP-compressed JSON files
 * ({@code saves/<slotId>.ksav}) with embedded LWJGL-STB PNG thumbnails and reflective custom
 * {@code @->{ ... }<<} class serialization via {@link SaveValueCodec}.
 */
public final class SaveManager {

    /** File extension for KAGAYAKI save slot files. */
    public static final String SAVE_EXTENSION = ".ksav";

    /** Reserved slot ID for Quick-Save / Quick-Load. */
    public static final String QUICK_SAVE_SLOT = "quick";

    /** Prefix for rotating Auto-Save slots ({@code auto_1}..{@code auto_3}). */
    public static final String AUTO_SAVE_PREFIX = "auto_";

    /** Number of rotating Auto-Save slots maintained by {@link #autoSave(SaveSlotData)}. */
    public static final int DEFAULT_AUTO_SAVE_SLOTS = 3;

    private static final int FORMAT_VERSION = 1;
    private static final Pattern VALID_SLOT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Type HISTORY_LIST_TYPE = new TypeToken<List<HistoryLog.HistoryEntry>>() { }.getType();
    private static final Object STB_WRITE_LOCK = new Object();
    private static final Gson GSON = SaveValueCodec.createGson(false);

    private final Path savesDir;
    private final int autoSaveSlotCount;
    private int nextAutoSaveIndex = -1;

    /**
     * Creates a {@code SaveManager} targeting {@code savesDir} with {@value #DEFAULT_AUTO_SAVE_SLOTS}
     * rotating auto-save slots.
     *
     * @param savesDir directory storing {@code .ksav} files (must not be {@code null})
     */
    public SaveManager(Path savesDir) {
        this(savesDir, DEFAULT_AUTO_SAVE_SLOTS);
    }

    /**
     * Creates a {@code SaveManager} targeting {@code savesDir} with a configurable number of
     * rotating auto-save slots.
     *
     * @param savesDir          directory storing {@code .ksav} files (must not be {@code null})
     * @param autoSaveSlotCount number of rotating auto-save slots ({@code >= 1})
     */
    public SaveManager(Path savesDir, int autoSaveSlotCount) {
        this.savesDir = Objects.requireNonNull(savesDir, "savesDir must not be null")
                .toAbsolutePath()
                .normalize();
        if (autoSaveSlotCount < 1) {
            throw new IllegalArgumentException("autoSaveSlotCount must be >= 1, got " + autoSaveSlotCount);
        }
        this.autoSaveSlotCount = autoSaveSlotCount;
    }

    /**
     * Encodes tightly packed top-to-bottom straight RGBA8 pixel bytes into an in-memory PNG byte array
     * using LWJGL {@link STBImageWrite#stbi_write_png_to_func} (without temporary files or {@code java.awt}).
     *
     * @param width     image width in pixels ({@code > 0})
     * @param height    image height in pixels ({@code > 0})
     * @param rgbaBytes tightly packed RGBA8 pixels ({@code 4 * width * height} bytes)
     * @return encoded PNG bytes
     */
    public static byte[] encodeRgbaToPng(int width, int height, byte[] rgbaBytes) {
        return encodeRgbaToPng(width, height, rgbaBytes, false);
    }

    /**
     * Encodes tightly packed RGBA8 pixel bytes into an in-memory PNG byte array using LWJGL
     * {@link STBImageWrite#stbi_write_png_to_func}, optionally flipping rows vertically
     * (useful for bottom-up OpenGL pixel buffers).
     *
     * @param width          image width in pixels ({@code > 0})
     * @param height         image height in pixels ({@code > 0})
     * @param rgbaBytes      tightly packed RGBA8 pixels ({@code 4 * width * height} bytes)
     * @param flipVertically {@code true} to flip image vertically during encoding
     * @return encoded PNG bytes
     */
    public static byte[] encodeRgbaToPng(int width, int height, byte[] rgbaBytes, boolean flipVertically) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Thumbnail dimensions must be positive, got " + width + "x" + height
            );
        }
        Objects.requireNonNull(rgbaBytes, "rgbaBytes must not be null");
        int expectedBytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (rgbaBytes.length != expectedBytes) {
            throw new IllegalArgumentException(
                    "Expected " + expectedBytes + " RGBA bytes for " + width + "x" + height
                            + ", got " + rgbaBytes.length
            );
        }

        ByteArrayOutputStream pngStream = new ByteArrayOutputStream(Math.max(1024, expectedBytes / 4));
        ByteBuffer nativePixels = MemoryUtil.memAlloc(expectedBytes);
        STBIWriteCallback callback = STBIWriteCallback.create((context, data, size) -> {
            ByteBuffer chunk = STBIWriteCallback.getData(data, size);
            byte[] bytes = new byte[size];
            chunk.get(bytes);
            pngStream.write(bytes, 0, size);
        });
        try {
            nativePixels.put(rgbaBytes).flip();
            boolean ok;
            synchronized (STB_WRITE_LOCK) {
                STBImageWrite.stbi_flip_vertically_on_write(flipVertically);
                try {
                    ok = STBImageWrite.stbi_write_png_to_func(
                            callback,
                            0L,
                            width,
                            height,
                            4,
                            nativePixels,
                            width * 4
                    );
                } finally {
                    STBImageWrite.stbi_flip_vertically_on_write(false);
                }
            }
            if (!ok) {
                throw new IllegalStateException("STBImageWrite.stbi_write_png_to_func failed to encode PNG");
            }
            return pngStream.toByteArray();
        } finally {
            callback.free();
            MemoryUtil.memFree(nativePixels);
        }
    }

    /**
     * Computes the canonical 1-based slot identifier for a paginated slot grid
     * (e.g., page {@code 0}, index {@code 0} with {@code 6} slots/page returns {@code "slot_1"}).
     *
     * @param pageIndex       0-based page index ({@code >= 0})
     * @param slotIndexInPage 0-based index within the page ({@code 0 <= idx < slotsPerPage})
     * @param slotsPerPage    number of slots per page ({@code > 0})
     * @return canonical slot ID ({@code "slot_<n>"})
     */
    public static String slotIdForPage(int pageIndex, int slotIndexInPage, int slotsPerPage) {
        if (pageIndex < 0) {
            throw new IllegalArgumentException("pageIndex must be >= 0, got " + pageIndex);
        }
        if (slotsPerPage <= 0) {
            throw new IllegalArgumentException("slotsPerPage must be > 0, got " + slotsPerPage);
        }
        if (slotIndexInPage < 0 || slotIndexInPage >= slotsPerPage) {
            throw new IllegalArgumentException("slotIndexInPage out of bounds: " + slotIndexInPage);
        }
        int oneBasedSlot = pageIndex * slotsPerPage + slotIndexInPage + 1;
        return "slot_" + oneBasedSlot;
    }

    /**
     * Saves {@code data} to {@code savesDir/<slotId>.ksav} atomically, updating slot ID and custom name
     * in the persisted {@link SaveMetadata}.
     *
     * @param slotId     target slot identifier (must not be {@code null} or contain path separators)
     * @param customName optional custom save name overriding metadata name (may be {@code null})
     * @param data       save slot payload (must not be {@code null})
     * @throws IOException if serialization or atomic file writing fails
     */
    public synchronized void saveSlot(String slotId, String customName, SaveSlotData data) throws IOException {
        Objects.requireNonNull(data, "data must not be null");
        Path target = resolveSlotPath(slotId);
        Files.createDirectories(savesDir);

        SaveMetadata normalizedMeta = normalizeMetadata(slotId, customName, data);
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", FORMAT_VERSION);
        root.add("metadata", GSON.toJsonTree(normalizedMeta));
        root.add("variables", SaveValueCodec.encodeMap(data.variables()));
        root.add("scriptPosition", data.scriptPosition() != null
                ? GSON.toJsonTree(data.scriptPosition())
                : null);
        root.add("historyEntries", GSON.toJsonTree(data.historyEntries(), HISTORY_LIST_TYPE));
        if (data.voiceBookmark() != null) {
            root.add("voiceBookmark", GSON.toJsonTree(data.voiceBookmark()));
        }

        Path tempFile = Files.createTempFile(savesDir, "save-", ".tmp");
        boolean moved = false;
        try {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new GZIPOutputStream(Files.newOutputStream(tempFile)), StandardCharsets.UTF_8))) {
                GSON.toJson(root, writer);
            }
            moveAtomically(tempFile, target);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    /**
     * Saves {@code data} to {@code savesDir/<slotId>.ksav}, preserving any existing custom name in
     * {@code data.metadata()}.
     *
     * @param slotId target slot identifier
     * @param data   save slot payload
     * @throws IOException if writing fails
     */
    public synchronized void saveSlot(String slotId, SaveSlotData data) throws IOException {
        saveSlot(slotId, null, data);
    }

    /**
     * Loads a full {@link SaveSlotData} snapshot from {@code savesDir/<slotId>.ksav} using the
     * default context/application classloader.
     *
     * @param slotId slot identifier
     * @return loaded {@link SaveSlotData}
     * @throws IOException if the slot does not exist, is corrupt, or custom class reconstruction fails
     */
    public synchronized SaveSlotData loadSlot(String slotId) throws IOException {
        return loadSlot(slotId, null);
    }

    /**
     * Loads a full {@link SaveSlotData} snapshot from {@code savesDir/<slotId>.ksav}, resolving
     * custom {@code @->{ ... }<<} {@code VJavaUserTypes} classes via {@code optionalUserClassLoader}.
     *
     * @param slotId                  slot identifier
     * @param optionalUserClassLoader optional classloader containing compiled user types (nullable)
     * @return loaded {@link SaveSlotData}
     * @throws IOException if the slot does not exist, is corrupt, or custom class reconstruction fails
     */
    public synchronized SaveSlotData loadSlot(String slotId, ClassLoader optionalUserClassLoader) throws IOException {
        Path file = resolveSlotPath(slotId);
        if (!Files.exists(file)) {
            throw new NoSuchFileException(file.toString());
        }
        JsonObject root = readSlotJson(file);
        try {
            SaveMetadata metadata = root.has("metadata") && !root.get("metadata").isJsonNull()
                    ? GSON.fromJson(root.get("metadata"), SaveMetadata.class)
                    : null;
            Map<String, Object> variables = root.has("variables") && root.get("variables").isJsonObject()
                    ? SaveValueCodec.decodeMap(root.getAsJsonObject("variables"), optionalUserClassLoader)
                    : Map.of();
            SavedScriptPosition scriptPos = root.has("scriptPosition") && !root.get("scriptPosition").isJsonNull()
                    ? GSON.fromJson(root.get("scriptPosition"), SavedScriptPosition.class)
                    : null;
            List<HistoryLog.HistoryEntry> history =
                    root.has("historyEntries") && root.get("historyEntries").isJsonArray()
                            ? GSON.fromJson(root.get("historyEntries"), HISTORY_LIST_TYPE)
                            : List.of();
            SaveSlotData.SavedVoiceBookmark voiceBookmark =
                    root.has("voiceBookmark") && !root.get("voiceBookmark").isJsonNull()
                            ? GSON.fromJson(root.get("voiceBookmark"), SaveSlotData.SavedVoiceBookmark.class)
                            : null;
            return new SaveSlotData(metadata, variables, scriptPos, history, voiceBookmark);
        } catch (RuntimeException e) {
            throw new IOException("Failed to deserialize save slot '" + slotId + "': " + e.getMessage(), e);
        }
    }

    /**
     * Reads only the {@link SaveMetadata} header (including PNG thumbnail bytes) for {@code slotId},
     * avoiding deserialization of custom variable classes.
     *
     * @param slotId slot identifier
     * @return metadata if the slot exists, or {@link Optional#empty()} if missing
     * @throws IOException if the slot file exists but is corrupt or unreadable
     */
    public synchronized Optional<SaveMetadata> readSlotMetadata(String slotId) throws IOException {
        Path file = resolveSlotPath(slotId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        JsonObject root = readSlotJson(file);
        try {
            if (!root.has("metadata") || root.get("metadata").isJsonNull()) {
                return Optional.empty();
            }
            return Optional.ofNullable(GSON.fromJson(root.get("metadata"), SaveMetadata.class));
        } catch (RuntimeException e) {
            throw new IOException("Failed to parse metadata for save slot '" + slotId + "': " + e.getMessage(), e);
        }
    }

    /**
     * Lists metadata for existing 1-based slots on {@code pageIndex} (e.g., page {@code 0} with
     * {@code 6} slots checks {@code slot_1}..{@code slot_6} in ascending order).
     *
     * @param pageIndex    0-based page index ({@code >= 0})
     * @param slotsPerPage number of slots per page ({@code > 0})
     * @return immutable list of metadata for existing slots on that page
     * @throws IOException if an existing slot file on that page is corrupt or unreadable
     */
    public synchronized List<SaveMetadata> listPageSlots(int pageIndex, int slotsPerPage) throws IOException {
        if (pageIndex < 0) {
            throw new IllegalArgumentException("pageIndex must be >= 0, got " + pageIndex);
        }
        if (slotsPerPage <= 0) {
            throw new IllegalArgumentException("slotsPerPage must be > 0, got " + slotsPerPage);
        }
        List<SaveMetadata> result = new ArrayList<>();
        for (int i = 0; i < slotsPerPage; i++) {
            String slotId = slotIdForPage(pageIndex, i, slotsPerPage);
            readSlotMetadata(slotId).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    /**
     * Saves to the dedicated Quick-Save slot ({@code "quick"}).
     *
     * @param data save slot payload
     * @throws IOException if saving fails
     */
    public synchronized void quickSave(SaveSlotData data) throws IOException {
        Objects.requireNonNull(data, "data must not be null");
        String name = (data.metadata() != null && data.metadata().customName() != null
                && !data.metadata().customName().isBlank())
                ? data.metadata().customName()
                : "Quick Save";
        saveSlot(QUICK_SAVE_SLOT, name, data);
    }

    /**
     * Loads the Quick-Save slot ({@code "quick"}) using {@code optionalUserClassLoader}.
     *
     * @param optionalUserClassLoader optional classloader for custom user types (nullable)
     * @return loaded {@link SaveSlotData}
     * @throws IOException if the quick-save does not exist or cannot be loaded
     */
    public synchronized SaveSlotData quickLoad(ClassLoader optionalUserClassLoader) throws IOException {
        return loadSlot(QUICK_SAVE_SLOT, optionalUserClassLoader);
    }

    /**
     * Loads the Quick-Save slot ({@code "quick"}) using the default classloader.
     *
     * @return loaded {@link SaveSlotData}
     * @throws IOException if the quick-save does not exist or cannot be loaded
     */
    public synchronized SaveSlotData quickLoad() throws IOException {
        return quickLoad(null);
    }

    /**
     * Saves to rotating Auto-Save slots ({@code "auto_1"}..{@code "auto_<count>"}) and returns
     * the chosen slot identifier.
     *
     * @param data save slot payload
     * @return slot identifier written (e.g., {@code "auto_1"})
     * @throws IOException if saving fails
     */
    public synchronized String autoSave(SaveSlotData data) throws IOException {
        Objects.requireNonNull(data, "data must not be null");
        int slotIndex = selectNextAutoSaveIndex();
        String slotId = AUTO_SAVE_PREFIX + (slotIndex + 1);
        String name = (data.metadata() != null && data.metadata().customName() != null
                && !data.metadata().customName().isBlank())
                ? data.metadata().customName()
                : "Auto Save " + (slotIndex + 1);
        saveSlot(slotId, name, data);
        nextAutoSaveIndex = (slotIndex + 1) % autoSaveSlotCount;
        return slotId;
    }

    /**
     * Deletes a save slot file if it exists.
     *
     * @param slotId slot identifier
     * @return {@code true} if the slot existed and was deleted, {@code false} if it did not exist
     * @throws IOException if deletion fails
     */
    public synchronized boolean deleteSlot(String slotId) throws IOException {
        Path file = resolveSlotPath(slotId);
        return Files.deleteIfExists(file);
    }

    /**
     * Checks whether a save slot file currently exists on disk.
     *
     * @param slotId slot identifier
     * @return {@code true} if {@code savesDir/<slotId>.ksav} exists as a regular file
     */
    public synchronized boolean slotExists(String slotId) {
        Path file = resolveSlotPath(slotId);
        return Files.isRegularFile(file);
    }

    /**
     * Returns the normalized saves directory path.
     *
     * @return normalized saves directory path
     */
    public Path getSavesDir() {
        return savesDir;
    }

    /**
     * Returns the configured rotating auto-save slot count.
     *
     * @return configured rotating auto-save slot count
     */
    public int getAutoSaveSlotCount() {
        return autoSaveSlotCount;
    }

    private int selectNextAutoSaveIndex() throws IOException {
        if (nextAutoSaveIndex >= 0 && nextAutoSaveIndex < autoSaveSlotCount) {
            return nextAutoSaveIndex;
        }
        int oldestIdx = 0;
        Instant oldestTime = null;
        for (int i = 0; i < autoSaveSlotCount; i++) {
            String candidateId = AUTO_SAVE_PREFIX + (i + 1);
            if (!slotExists(candidateId)) {
                return i;
            }
            Instant savedAt = readSlotMetadata(candidateId)
                    .map(SaveMetadata::savedAt)
                    .orElse(Instant.EPOCH);
            if (oldestTime == null || savedAt.isBefore(oldestTime)) {
                oldestTime = savedAt;
                oldestIdx = i;
            }
        }
        return oldestIdx;
    }

    private SaveMetadata normalizeMetadata(String slotId, String customName, SaveSlotData data) {
        SaveMetadata existing = data.metadata();
        SavedScriptPosition pos = data.scriptPosition();

        String resolvedName = customName != null
                ? customName
                : (existing != null ? existing.customName() : null);
        Instant resolvedSavedAt = (existing != null && existing.savedAt() != null)
                ? existing.savedAt()
                : Instant.now();
        String resolvedLabel = (existing != null && existing.currentLabel() != null)
                ? existing.currentLabel()
                : (pos != null ? pos.currentLabel() : null);
        String resolvedPreview = (existing != null && existing.dialoguePreview() != null)
                ? existing.dialoguePreview()
                : (pos != null ? pos.activeDialogueText() : null);
        double resolvedPlaytime = existing != null ? existing.playtimeSeconds() : 0.0;
        String resolvedVersion = existing != null ? existing.gameVersion() : null;
        int resolvedThumbW = existing != null ? existing.thumbnailWidth() : 0;
        int resolvedThumbH = existing != null ? existing.thumbnailHeight() : 0;
        byte[] resolvedThumbPng = existing != null ? existing.thumbnailPng() : new byte[0];

        return new SaveMetadata(
                slotId,
                resolvedName,
                resolvedSavedAt,
                resolvedLabel,
                resolvedPreview,
                resolvedPlaytime,
                resolvedVersion,
                resolvedThumbW,
                resolvedThumbH,
                resolvedThumbPng
        );
    }

    private Path resolveSlotPath(String slotId) {
        if (slotId == null || slotId.isBlank()) {
            throw new IllegalArgumentException("slotId must not be null or blank");
        }
        String trimmed = slotId.trim();
        if (!VALID_SLOT_ID.matcher(trimmed).matches() || trimmed.contains("..")) {
            throw new IllegalArgumentException("Invalid save slotId: '" + slotId + "'");
        }
        Path resolved = savesDir.resolve(trimmed + SAVE_EXTENSION).normalize();
        if (!resolved.getParent().equals(savesDir)) {
            throw new IllegalArgumentException("slotId escapes saves directory: '" + slotId + "'");
        }
        return resolved;
    }

    private static JsonObject readSlotJson(Path file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
            JsonElement elem = JsonParser.parseReader(reader);
            if (elem == null || !elem.isJsonObject()) {
                throw new IOException("Invalid save slot root JSON in " + file);
            }
            return elem.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Failed to read save file " + file + ": " + e.getMessage(), e);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
