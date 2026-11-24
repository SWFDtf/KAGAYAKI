package de.swfdtf.demo.features;

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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-sicheres Zitat- und Lesezeichenbuch für prägnante Dialogzeilen aus dem Schneewittchen-Demo.
 *
 * <p>Einträge werden anhand ihrer eindeutigen {@code lineId} dedupliziert gespeichert, unterstützen
 * Volltextsuche und lassen sich atomar per Gson als JSON-Datei sichern und wieder laden.</p>
 */
public final class SnowWhiteQuoteBook {

    private static final int FORMAT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(Instant.class, new InstantTypeAdapter())
            .create();

    /**
     * Unveränderlicher Zitat-Eintrag im Zitatbuch.
     *
     * @param lineId        eindeutige Dialogzeilen-ID (darf nicht {@code null} oder leer sein)
     * @param speaker       Sprechername oder {@code "Erzähler"} (darf nicht {@code null} sein)
     * @param text          gespeicherter Zitattext (darf nicht {@code null} oder leer sein)
     * @param chapterNumber zugehörige Kapitelnummer ({@code >= 1})
     * @param savedAt       UTC-Zeitstempel der Speicherung (bei {@code null} wird {@link Instant#now()} verwendet)
     */
    public record QuoteEntry(
            String lineId,
            String speaker,
            String text,
            int chapterNumber,
            Instant savedAt
    ) {
        /**
         * Validiert und normalisiert die Felder eines Zitat-Eintrags.
         */
        public QuoteEntry {
            lineId = requireNonBlank(lineId, "lineId");
            Objects.requireNonNull(speaker, "speaker darf nicht null sein");
            speaker = speaker.isBlank() ? "Erzähler" : speaker.trim();
            text = requireNonBlank(text, "text");
            if (chapterNumber < 1) {
                throw new IllegalArgumentException("chapterNumber muss >= 1 sein, war: " + chapterNumber);
            }
            savedAt = savedAt != null ? savedAt : Instant.now();
        }
    }

    private record QuoteBookPayload(int version, List<QuoteEntry> quotes) {
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

    private final Map<String, QuoteEntry> quotes = new LinkedHashMap<>();

    /**
     * Erzeugt ein leeres {@link SnowWhiteQuoteBook}.
     */
    public SnowWhiteQuoteBook() {
    }

    /**
     * Fügt ein Zitat hinzu oder aktualisiert einen bereits unter derselben {@code lineId}
     * gespeicherten Eintrag (Deduplizierung über {@code lineId}).
     *
     * @param lineId        eindeutige Zeilen-ID (darf nicht {@code null} oder leer sein)
     * @param speaker       Sprechername (darf nicht {@code null} sein)
     * @param text          Zitattext (darf nicht {@code null} oder leer sein)
     * @param chapterNumber Kapitelnummer ({@code >= 1})
     * @return gespeicherter unveränderlicher {@link QuoteEntry}
     */
    public synchronized QuoteEntry addQuote(String lineId, String speaker, String text, int chapterNumber) {
        QuoteEntry entry = new QuoteEntry(lineId, speaker, text, chapterNumber, Instant.now());
        quotes.put(entry.lineId(), entry);
        return entry;
    }

    /**
     * Entfernt ein Zitat anhand seiner {@code lineId}.
     *
     * @param lineId Zeilen-ID des zu entfernenden Zitats (darf nicht {@code null} sein)
     * @return {@code true}, wenn ein Eintrag vorhanden war und entfernt wurde, sonst {@code false}
     */
    public synchronized boolean removeQuote(String lineId) {
        Objects.requireNonNull(lineId, "lineId darf nicht null sein");
        String trimmed = lineId.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        return quotes.remove(trimmed) != null;
    }

    /**
     * Sucht ein Zitat anhand seiner {@code lineId}.
     *
     * @param lineId gesuchte Zeilen-ID (darf nicht {@code null} sein)
     * @return {@link Optional} mit dem gefundenen {@link QuoteEntry} oder {@link Optional#empty()}
     */
    public synchronized Optional<QuoteEntry> findQuote(String lineId) {
        Objects.requireNonNull(lineId, "lineId darf nicht null sein");
        String trimmed = lineId.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(quotes.get(trimmed));
    }

    /**
     * Durchsucht alle gespeicherten Zitate unabhängig von Groß-/Kleinschreibung in {@code lineId},
     * {@code speaker}, {@code text} und Kapitelnummer. Ein leerer Suchbegriff liefert alle Einträge.
     *
     * @param query Suchbegriff (darf nicht {@code null} sein)
     * @return unveränderliche Liste passender {@link QuoteEntry}-Einträge in Einfügereihenfolge
     */
    public synchronized List<QuoteEntry> search(String query) {
        Objects.requireNonNull(query, "query darf nicht null sein");
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return listAll();
        }
        List<QuoteEntry> matches = new ArrayList<>();
        for (QuoteEntry entry : quotes.values()) {
            if (matchesQuery(entry, needle)) {
                matches.add(entry);
            }
        }
        return List.copyOf(matches);
    }

    /**
     * Liefert eine unveränderliche Kopie aller gespeicherten Zitate in Einfügereihenfolge.
     *
     * @return unveränderliche Liste aller {@link QuoteEntry}-Einträge
     */
    public synchronized List<QuoteEntry> listAll() {
        return List.copyOf(quotes.values());
    }

    /**
     * Speichert den gesamten Zitatbestand atomar über eine temporäre Datei als JSON via Gson.
     *
     * @param file Zieldateipfad (darf nicht {@code null} sein)
     * @throws IOException falls Verzeichniserstellung, Serialisierung oder Verschieben fehlschlägt
     */
    public synchronized void save(Path file) throws IOException {
        Objects.requireNonNull(file, "file darf nicht null sein");
        Path normalizedTarget = file.toAbsolutePath().normalize();
        Path parent = normalizedTarget.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        QuoteBookPayload payload = new QuoteBookPayload(FORMAT_VERSION, listAll());
        String json = GSON.toJson(payload);

        Path tempFile = parent != null
                ? Files.createTempFile(parent, ".quotes-", ".tmp")
                : Files.createTempFile(".quotes-", ".tmp");
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
     * Lädt ein Zitatbuch aus einer zuvor mit {@link #save(Path)} geschriebenen JSON-Datei.
     *
     * @param file Quelldateipfad (darf nicht {@code null} sein)
     * @return befülltes {@link SnowWhiteQuoteBook}
     * @throws IOException falls die Datei fehlt, unlesbar ist oder ungültiges JSON enthält
     */
    public static SnowWhiteQuoteBook load(Path file) throws IOException {
        Objects.requireNonNull(file, "file darf nicht null sein");
        Path normalizedSource = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedSource)) {
            throw new NoSuchFileException(normalizedSource.toString());
        }

        try (Reader reader = Files.newBufferedReader(normalizedSource, StandardCharsets.UTF_8)) {
            QuoteBookPayload payload = GSON.fromJson(reader, QuoteBookPayload.class);
            if (payload == null || payload.quotes() == null) {
                throw new IOException("Ungültiges Zitatbuch-JSON: Array 'quotes' fehlt in " + normalizedSource);
            }
            SnowWhiteQuoteBook book = new SnowWhiteQuoteBook();
            for (QuoteEntry raw : payload.quotes()) {
                if (raw == null) {
                    throw new IOException("Ungültiger null-Eintrag im Zitatbuch: " + normalizedSource);
                }
                QuoteEntry validated = new QuoteEntry(
                        raw.lineId(),
                        raw.speaker(),
                        raw.text(),
                        raw.chapterNumber(),
                        raw.savedAt()
                );
                book.quotes.put(validated.lineId(), validated);
            }
            return book;
        } catch (JsonParseException | IllegalArgumentException | NullPointerException e) {
            throw new IOException("Fehler beim Laden des Zitatbuchs aus " + normalizedSource + ": " + e.getMessage(), e);
        }
    }

    private static boolean matchesQuery(QuoteEntry entry, String needle) {
        return entry.lineId().toLowerCase(Locale.ROOT).contains(needle)
                || entry.speaker().toLowerCase(Locale.ROOT).contains(needle)
                || entry.text().toLowerCase(Locale.ROOT).contains(needle)
                || Integer.toString(entry.chapterNumber()).equals(needle);
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " darf nicht null sein");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " darf nicht leer sein");
        }
        return trimmed;
    }
}
