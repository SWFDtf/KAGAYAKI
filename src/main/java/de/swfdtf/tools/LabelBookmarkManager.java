package de.swfdtf.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-safe manager for authoring bookmarks on {@code .vjava} script labels, supporting
 * categorization, notes, lookup, category filtering, and atomic JSON persistence via Gson.
 */
public final class LabelBookmarkManager {

    private static final int FORMAT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(Instant.class, new InstantTypeAdapter())
            .create();

    /**
     * Immutable bookmark attached to a {@code .vjava} script label.
     *
     * @param label     target {@code .vjava} label name (must not be {@code null} or blank)
     * @param category  bookmark category tag (must not be {@code null} or blank)
     * @param note      authoring note text (must not be {@code null}; may be empty)
     * @param createdAt UTC creation/update timestamp (defaults to {@link Instant#now()} if {@code null})
     */
    public record LabelBookmark(
            String label,
            String category,
            String note,
            Instant createdAt
    ) {

        /**
         * Validates and normalizes bookmark fields.
         */
        public LabelBookmark {
            label = requireNonBlank(label, "label");
            category = requireNonBlank(category, "category");
            note = Objects.requireNonNull(note, "note must not be null");
            createdAt = createdAt != null ? createdAt : Instant.now();
        }
    }

    private record BookmarkStorePayload(int version, List<LabelBookmark> bookmarks) {
    }

    private static final class InstantTypeAdapter extends TypeAdapter<Instant> {

        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return Instant.parse(in.nextString());
        }
    }

    private final Map<String, LabelBookmark> bookmarks = new LinkedHashMap<>();

    /**
     * Creates an empty {@code LabelBookmarkManager}.
     */
    public LabelBookmarkManager() {
    }

    /**
     * Adds a new bookmark or updates an existing bookmark for {@code label}.
     *
     * @param label    target {@code .vjava} label name (must not be {@code null} or blank)
     * @param category bookmark category (must not be {@code null} or blank)
     * @param note     authoring note (must not be {@code null}; may be empty)
     * @return the stored immutable {@link LabelBookmark}
     */
    public synchronized LabelBookmark addOrUpdate(String label, String category, String note) {
        String normalizedLabel = requireNonBlank(label, "label");
        String normalizedCategory = requireNonBlank(category, "category");
        Objects.requireNonNull(note, "note must not be null");

        LabelBookmark entry = new LabelBookmark(normalizedLabel, normalizedCategory, note, Instant.now());
        bookmarks.put(normalizedLabel, entry);
        return entry;
    }

    /**
     * Removes the bookmark associated with {@code label}, if present.
     *
     * @param label label name to remove (must not be {@code null})
     * @return {@code true} if a bookmark existed and was removed, {@code false} otherwise
     */
    public synchronized boolean remove(String label) {
        Objects.requireNonNull(label, "label must not be null");
        String normalized = label.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        return bookmarks.remove(normalized) != null;
    }

    /**
     * Looks up a bookmark by label name.
     *
     * @param label label name to look up (must not be {@code null})
     * @return {@link Optional} containing the bookmark if present
     */
    public synchronized Optional<LabelBookmark> find(String label) {
        Objects.requireNonNull(label, "label must not be null");
        String normalized = label.trim();
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(bookmarks.get(normalized));
    }

    /**
     * Returns an unmodifiable snapshot list of all stored bookmarks in insertion order.
     *
     * @return unmodifiable list of all {@link LabelBookmark} entries
     */
    public synchronized List<LabelBookmark> listAll() {
        return List.copyOf(bookmarks.values());
    }

    /**
     * Returns an unmodifiable list of bookmarks matching {@code category} (case-insensitive).
     *
     * @param category category filter string (must not be {@code null} or blank)
     * @return unmodifiable list of matching {@link LabelBookmark} entries
     */
    public synchronized List<LabelBookmark> filterByCategory(String category) {
        String normalizedCategory = requireNonBlank(category, "category");
        List<LabelBookmark> filtered = new ArrayList<>();
        for (LabelBookmark bookmark : bookmarks.values()) {
            if (bookmark.category().equalsIgnoreCase(normalizedCategory)) {
                filtered.add(bookmark);
            }
        }
        return List.copyOf(filtered);
    }

    /**
     * Atomically serializes all bookmarks to {@code targetFile} in JSON format, creating parent
     * directories if needed.
     *
     * @param targetFile destination JSON file path (must not be {@code null})
     * @throws IOException if directory creation, serialization, or atomic file move fails
     */
    public synchronized void save(Path targetFile) throws IOException {
        Objects.requireNonNull(targetFile, "targetFile must not be null");
        Path normalizedTarget = targetFile.toAbsolutePath().normalize();
        Path parent = normalizedTarget.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        BookmarkStorePayload payload = new BookmarkStorePayload(FORMAT_VERSION, listAll());
        String json = GSON.toJson(payload);

        Path tempFile = parent != null
                ? Files.createTempFile(parent, ".bookmarks-", ".tmp")
                : Files.createTempFile(".bookmarks-", ".tmp");
        boolean moved = false;
        try {
            Files.writeString(tempFile, json, StandardCharsets.UTF_8);
            try {
                Files.move(tempFile, normalizedTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempFile, normalizedTarget, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    /**
     * Loads bookmarks from a JSON file previously written by {@link #save(Path)}.
     *
     * @param sourceFile source JSON file path (must not be {@code null})
     * @return populated {@link LabelBookmarkManager}
     * @throws IOException if the file does not exist, cannot be read, or contains invalid JSON
     */
    public static LabelBookmarkManager load(Path sourceFile) throws IOException {
        Objects.requireNonNull(sourceFile, "sourceFile must not be null");
        Path normalizedSource = sourceFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedSource)) {
            throw new NoSuchFileException(normalizedSource.toString());
        }

        try (Reader reader = Files.newBufferedReader(normalizedSource, StandardCharsets.UTF_8)) {
            BookmarkStorePayload payload = GSON.fromJson(reader, BookmarkStorePayload.class);
            if (payload == null || payload.bookmarks() == null) {
                throw new IOException("Invalid bookmark store JSON: missing 'bookmarks' array in " + normalizedSource);
            }
            LabelBookmarkManager manager = new LabelBookmarkManager();
            for (LabelBookmark raw : payload.bookmarks()) {
                if (raw == null) {
                    throw new IOException("Invalid null bookmark entry in " + normalizedSource);
                }
                LabelBookmark validated = new LabelBookmark(
                        raw.label(),
                        raw.category(),
                        raw.note(),
                        raw.createdAt()
                );
                manager.bookmarks.put(validated.label(), validated);
            }
            return manager;
        } catch (JsonParseException | IllegalArgumentException | NullPointerException e) {
            throw new IOException("Failed to parse bookmark store JSON from " + normalizedSource + ": " + e.getMessage(), e);
        }
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
