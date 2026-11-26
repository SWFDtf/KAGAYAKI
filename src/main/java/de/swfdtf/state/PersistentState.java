package de.swfdtf.state;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Tier 3 cross-save persistent storage implementing {@link PersistentAccess} and backed by
 * GZIP-compressed JSON at {@code saves/persistent.dat}.
 *
 * <p>Persists global custom properties, read dialogue line IDs, unlocked CGs, music room tracks,
 * reached endings, and unlocked achievements atomically across save slots and game sessions.</p>
 */
public final class PersistentState implements PersistentAccess {

    /** Standard persistent storage file name inside the saves directory. */
    public static final String FILE_NAME = "persistent.dat";

    private static final Gson GSON = SaveValueCodec.createGson(false);

    private final Map<String, Object> data = new LinkedHashMap<>();
    private final Set<String> readLines = new LinkedHashSet<>();
    private final Set<String> unlockedCgs = new LinkedHashSet<>();
    private final Set<String> unlockedTracks = new LinkedHashSet<>();
    private final Set<String> unlockedEndings = new LinkedHashSet<>();
    private final Set<String> unlockedAchievements = new LinkedHashSet<>();
    private Path boundSavesDir;

    /**
     * Creates an empty {@code PersistentState}.
     */
    public PersistentState() {
    }

    /**
     * Loads persistent data from {@code savesDir/persistent.dat}, returning a fresh instance
     * if the file does not exist yet.
     *
     * @param savesDir saves directory path (must not be {@code null})
     * @return loaded or fresh {@link PersistentState}
     * @throws IOException if the file exists but cannot be read, decompressed, or parsed
     */
    public static PersistentState load(Path savesDir) throws IOException {
        return load(savesDir, null);
    }

    /**
     * Loads persistent data from {@code savesDir/persistent.dat} with an optional user classloader
     * for custom serialized classes, returning a fresh instance if the file does not exist yet.
     *
     * @param savesDir                saves directory path (must not be {@code null})
     * @param optionalUserClassLoader optional classloader for {@code __vjavaClass} objects (nullable)
     * @return loaded or fresh {@link PersistentState}
     * @throws IOException if the file exists but cannot be read, decompressed, or parsed
     */
    public static PersistentState load(Path savesDir, ClassLoader optionalUserClassLoader) throws IOException {
        Objects.requireNonNull(savesDir, "savesDir must not be null");
        Path normalizedDir = savesDir.toAbsolutePath().normalize();
        Path file = normalizedDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            PersistentState fresh = new PersistentState();
            fresh.bindSavesDir(normalizedDir);
            return fresh;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                throw new IOException("Invalid persistent data format in " + file);
            }
            JsonObject root = parsed.getAsJsonObject();
            PersistentState state = new PersistentState();
            if (root.has("data") && root.get("data").isJsonObject()) {
                state.data.putAll(SaveValueCodec.decodeMap(root.getAsJsonObject("data"), optionalUserClassLoader));
            }
            readStringSet(root.getAsJsonArray("readLines"), state.readLines);
            readStringSet(root.getAsJsonArray("unlockedCgs"), state.unlockedCgs);
            readStringSet(root.getAsJsonArray("unlockedTracks"), state.unlockedTracks);
            readStringSet(root.getAsJsonArray("unlockedEndings"), state.unlockedEndings);
            readStringSet(root.getAsJsonArray("unlockedAchievements"), state.unlockedAchievements);
            state.bindSavesDir(normalizedDir);
            return state;
        } catch (RuntimeException e) {
            throw new IOException("Failed to parse persistent state file " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Binds this {@code PersistentState} to {@code savesDir} so critical unlocks and flags flush
     * immediately to disk without requiring an explicit save call.
     *
     * @param savesDir saves directory path (nullable to disable auto-flush)
     */
    public synchronized void bindSavesDir(Path savesDir) {
        this.boundSavesDir = savesDir != null ? savesDir.toAbsolutePath().normalize() : null;
    }

    /**
     * Returns the bound saves directory used for immediate unlock flushing, if any.
     *
     * @return bound saves directory path, or {@code null}
     */
    public synchronized Path getBoundSavesDir() {
        return boundSavesDir;
    }

    /**
     * Atomically writes all persistent data as GZIP-compressed JSON to {@code savesDir/persistent.dat}.
     *
     * @param savesDir saves directory path (must not be {@code null})
     * @throws IOException if creating directories or writing/moving the file fails
     */
    public synchronized void save(Path savesDir) throws IOException {
        Objects.requireNonNull(savesDir, "savesDir must not be null");
        Path normalizedDir = savesDir.toAbsolutePath().normalize();
        Files.createDirectories(normalizedDir);
        Path target = normalizedDir.resolve(FILE_NAME);
        Path tempFile = Files.createTempFile(normalizedDir, "persistent-", ".tmp");
        boolean moved = false;
        try {
            JsonObject root = new JsonObject();
            root.addProperty("formatVersion", 1);
            root.add("data", SaveValueCodec.encodeMap(data));
            root.add("readLines", toJsonArray(readLines));
            root.add("unlockedCgs", toJsonArray(unlockedCgs));
            root.add("unlockedTracks", toJsonArray(unlockedTracks));
            root.add("unlockedEndings", toJsonArray(unlockedEndings));
            root.add("unlockedAchievements", toJsonArray(unlockedAchievements));

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new GZIPOutputStream(Files.newOutputStream(tempFile)), StandardCharsets.UTF_8))) {
                GSON.toJson(root, writer);
            }
            fsyncFile(tempFile);
            moveAtomically(tempFile, target);
            moved = true;
            this.boundSavesDir = normalizedDir;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    @Override
    public synchronized void set(String key, Object value) {
        Objects.requireNonNull(key, "key must not be null");
        if (value == null) {
            data.remove(key);
        } else {
            data.put(key, value);
        }
        flushIfBound();
    }

    @Override
    public synchronized Object get(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return data.get(key);
    }

    @Override
    public synchronized Object getOrDefault(String key, Object defaultValue) {
        Objects.requireNonNull(key, "key must not be null");
        Object val = data.get(key);
        return val != null ? val : defaultValue;
    }

    @Override
    public synchronized boolean has(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return data.containsKey(key);
    }

    @Override
    public synchronized void markLineRead(String lineId) {
        if (lineId != null && !lineId.isEmpty()) {
            readLines.add(lineId);
        }
    }

    @Override
    public synchronized boolean isLineRead(String lineId) {
        return lineId != null && readLines.contains(lineId);
    }

    @Override
    public synchronized void unlockCg(String cgId) {
        if (cgId != null && !cgId.isEmpty() && unlockedCgs.add(cgId)) {
            flushIfBound();
        }
    }

    @Override
    public synchronized boolean isCgUnlocked(String cgId) {
        return cgId != null && unlockedCgs.contains(cgId);
    }

    @Override
    public synchronized void unlockTrack(String trackId) {
        if (trackId != null && !trackId.isEmpty() && unlockedTracks.add(trackId)) {
            flushIfBound();
        }
    }

    @Override
    public synchronized boolean isTrackUnlocked(String trackId) {
        return trackId != null && unlockedTracks.contains(trackId);
    }

    @Override
    public synchronized void unlockEnding(String endingId) {
        if (endingId != null && !endingId.isEmpty() && unlockedEndings.add(endingId)) {
            flushIfBound();
        }
    }

    @Override
    public synchronized boolean isEndingUnlocked(String endingId) {
        return endingId != null && unlockedEndings.contains(endingId);
    }

    @Override
    public synchronized void unlockAchievement(String achievementId) {
        if (achievementId != null && !achievementId.isEmpty() && unlockedAchievements.add(achievementId)) {
            flushIfBound();
        }
    }

    @Override
    public synchronized boolean isAchievementUnlocked(String achievementId) {
        return achievementId != null && unlockedAchievements.contains(achievementId);
    }

    @Override
    public synchronized Set<String> getUnlockedCgs() {
        return Set.copyOf(unlockedCgs);
    }

    @Override
    public synchronized Set<String> getUnlockedTracks() {
        return Set.copyOf(unlockedTracks);
    }

    @Override
    public synchronized Set<String> getUnlockedEndings() {
        return Set.copyOf(unlockedEndings);
    }

    @Override
    public synchronized Set<String> getUnlockedAchievements() {
        return Set.copyOf(unlockedAchievements);
    }

    /**
     * Returns an immutable view of all read dialogue line identifiers.
     *
     * @return immutable view of all read dialogue line identifiers
     */
    public synchronized Set<String> getReadLines() {
        return Set.copyOf(readLines);
    }

    /**
     * Returns an immutable copy of all custom persistent key-value pairs.
     *
     * @return immutable copy of all custom persistent key-value pairs
     */
    public synchronized Map<String, Object> getDataSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    /**
     * Clears all stored persistent variables, read line records, and unlocks in memory.
     */
    public synchronized void clear() {
        data.clear();
        readLines.clear();
        unlockedCgs.clear();
        unlockedTracks.clear();
        unlockedEndings.clear();
        unlockedAchievements.clear();
    }

    private static JsonArray toJsonArray(Set<String> values) {
        JsonArray arr = new JsonArray(values.size());
        for (String v : values) {
            arr.add(v);
        }
        return arr;
    }

    private static void readStringSet(JsonArray arr, Set<String> target) {
        if (arr == null) {
            return;
        }
        for (JsonElement elem : arr) {
            if (elem != null && !elem.isJsonNull()) {
                String value = elem.getAsString();
                if (!value.isEmpty()) {
                    target.add(value);
                }
            }
        }
    }

    private void flushIfBound() {
        if (boundSavesDir == null) {
            return;
        }
        try {
            save(boundSavesDir);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to flush persistent state to " + boundSavesDir, ex);
        }
    }

    private static void fsyncFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
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
