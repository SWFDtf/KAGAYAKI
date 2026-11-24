package de.swfdtf.demo.director;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import de.swfdtf.core.GamepadNavigationRouter;
import de.swfdtf.graphics.ColorBlindnessFilter;
import de.swfdtf.graphics.ColorBlindnessFilter.AccessibilityMode;
import de.swfdtf.state.AutoReadController;
import de.swfdtf.state.SkipModeController;
import de.swfdtf.state.SkipModeController.SkipMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Bündelt alle Barrierefreiheits- und Komfortfunktionen des Schneewittchen-Demos
 * ({@link ColorBlindnessFilter}, {@link AutoReadController}, {@link SkipModeController}
 * und {@link GamepadNavigationRouter}) unter benannten {@link ProfilePreset}-Profilen
 * inklusive JSON-Persistenz.
 */
public final class SnowWhiteAccessibilityProfile {

    private static final int FORMAT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Vordefinierte Barrierefreiheits- und Komfortprofile für das Schneewittchen-Demo.
     */
    public enum ProfilePreset {
        /** Ausgewogene Standardeinstellung ohne Farbfilter und ohne automatisches Weiterblättern. */
        STANDARD,
        /** Hoher Kontrast, entschleunigtes Auto-Read-Tempo und fehlertolerante Gamepad-Deadzone. */
        HIGH_CONTRAST,
        /** Aktiviertes Auto-Read-Kino-Profil mit zügigem Lesefluss und reaktionsschneller Gamepad-Navigation. */
        AUTO_CINEMA
    }

    private record SerializedProfile(
            int version,
            ProfilePreset activePreset,
            AccessibilityMode colorMode,
            float colorIntensity,
            boolean autoReadEnabled,
            double autoReadBaseDelaySeconds,
            double autoReadSecondsPerChar,
            boolean autoReadWaitForVoice,
            SkipMode skipMode,
            boolean skipStopOnChoice,
            float gamepadDeadzone,
            double gamepadInitialRepeatDelaySeconds,
            double gamepadRepeatIntervalSeconds
    ) {
    }

    private final ColorBlindnessFilter colorBlindnessFilter;
    private final AutoReadController autoReadController;
    private final SkipModeController skipModeController;
    private final GamepadNavigationRouter gamepadNavigationRouter;
    private ProfilePreset activePreset;

    /**
     * Erzeugt ein {@code SnowWhiteAccessibilityProfile} im {@link ProfilePreset#STANDARD}-Profil.
     */
    public SnowWhiteAccessibilityProfile() {
        this(ProfilePreset.STANDARD);
    }

    /**
     * Erzeugt ein {@code SnowWhiteAccessibilityProfile} und wendet unmittelbar {@code initialPreset} an.
     *
     * @param initialPreset initiales {@link ProfilePreset} (darf nicht {@code null} sein)
     * @throws NullPointerException falls {@code initialPreset} {@code null} ist
     */
    public SnowWhiteAccessibilityProfile(ProfilePreset initialPreset) {
        this(
                new ColorBlindnessFilter(),
                new AutoReadController(),
                new SkipModeController(),
                new GamepadNavigationRouter(),
                Objects.requireNonNull(initialPreset, "initialPreset darf nicht null sein")
        );
        applyPreset(initialPreset);
    }

    /**
     * Erzeugt ein {@code SnowWhiteAccessibilityProfile} aus explizit übergebenen Komponenten
     * ohne deren aktuelle Konfiguration zu überschreiben.
     *
     * @param colorBlindnessFilter    Farbfilter-Controller (darf nicht {@code null} sein)
     * @param autoReadController      Auto-Read-Controller (darf nicht {@code null} sein)
     * @param skipModeController      Skip-Mode-Controller (darf nicht {@code null} sein)
     * @param gamepadNavigationRouter Gamepad-Navigations-Router (darf nicht {@code null} sein)
     * @param activePreset            zugeordnetes {@link ProfilePreset} (darf nicht {@code null} sein)
     * @throws NullPointerException falls eines der Argumente {@code null} ist
     */
    public SnowWhiteAccessibilityProfile(
            ColorBlindnessFilter colorBlindnessFilter,
            AutoReadController autoReadController,
            SkipModeController skipModeController,
            GamepadNavigationRouter gamepadNavigationRouter,
            ProfilePreset activePreset
    ) {
        this.colorBlindnessFilter = Objects.requireNonNull(
                colorBlindnessFilter, "colorBlindnessFilter darf nicht null sein"
        );
        this.autoReadController = Objects.requireNonNull(
                autoReadController, "autoReadController darf nicht null sein"
        );
        this.skipModeController = Objects.requireNonNull(
                skipModeController, "skipModeController darf nicht null sein"
        );
        this.gamepadNavigationRouter = Objects.requireNonNull(
                gamepadNavigationRouter, "gamepadNavigationRouter darf nicht null sein"
        );
        this.activePreset = Objects.requireNonNull(activePreset, "activePreset darf nicht null sein");
    }

    /**
     * Löst einen Preset-Namen (ohne Berücksichtigung von Groß-/Kleinschreibung) optional in ein
     * {@link ProfilePreset} auf.
     *
     * @param rawName Preset-Name (darf nicht {@code null} sein)
     * @return {@link Optional} mit passendem {@link ProfilePreset}, oder leer bei unbekanntem Namen
     * @throws NullPointerException falls {@code rawName} {@code null} ist
     */
    public static Optional<ProfilePreset> findPresetByName(String rawName) {
        Objects.requireNonNull(rawName, "rawName darf nicht null sein");
        String normalized = rawName.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        for (ProfilePreset preset : ProfilePreset.values()) {
            if (preset.name().equals(normalized)) {
                return Optional.of(preset);
            }
        }
        return Optional.empty();
    }

    /**
     * Wendet sämtliche Einstellungen des angegebenen {@link ProfilePreset} auf alle vier
     * Barrierefreiheits-Komponenten an.
     *
     * @param preset anzuwendendes Profil-Preset (darf nicht {@code null} sein)
     * @throws NullPointerException falls {@code preset} {@code null} ist
     */
    public void applyPreset(ProfilePreset preset) {
        Objects.requireNonNull(preset, "preset darf nicht null sein");
        switch (preset) {
            case STANDARD -> {
                colorBlindnessFilter.setMode(AccessibilityMode.NONE);
                colorBlindnessFilter.setIntensity(1.0f);

                autoReadController.setEnabled(false);
                autoReadController.setBaseDelaySeconds(AutoReadController.DEFAULT_BASE_DELAY_SECONDS);
                autoReadController.setSecondsPerChar(AutoReadController.DEFAULT_SECONDS_PER_CHAR);
                autoReadController.setWaitForVoice(true);

                skipModeController.setMode(SkipMode.OFF);
                skipModeController.setStopOnChoice(true);

                gamepadNavigationRouter.setDeadzone(GamepadNavigationRouter.DEFAULT_DEADZONE);
                gamepadNavigationRouter.setInitialRepeatDelaySeconds(
                        GamepadNavigationRouter.DEFAULT_INITIAL_REPEAT_DELAY_SECONDS
                );
                gamepadNavigationRouter.setRepeatIntervalSeconds(
                        GamepadNavigationRouter.DEFAULT_REPEAT_INTERVAL_SECONDS
                );
            }
            case HIGH_CONTRAST -> {
                colorBlindnessFilter.setMode(AccessibilityMode.HIGH_CONTRAST);
                colorBlindnessFilter.setIntensity(1.0f);

                autoReadController.setEnabled(false);
                autoReadController.setBaseDelaySeconds(2.0);
                autoReadController.setSecondsPerChar(0.05);
                autoReadController.setWaitForVoice(true);

                skipModeController.setMode(SkipMode.READ_ONLY);
                skipModeController.setStopOnChoice(true);

                gamepadNavigationRouter.setDeadzone(0.40f);
                gamepadNavigationRouter.setInitialRepeatDelaySeconds(0.45);
                gamepadNavigationRouter.setRepeatIntervalSeconds(0.16);
            }
            case AUTO_CINEMA -> {
                colorBlindnessFilter.setMode(AccessibilityMode.NONE);
                colorBlindnessFilter.setIntensity(1.0f);

                autoReadController.setEnabled(true);
                autoReadController.setBaseDelaySeconds(1.2);
                autoReadController.setSecondsPerChar(0.035);
                autoReadController.setWaitForVoice(true);

                skipModeController.setMode(SkipMode.OFF);
                skipModeController.setStopOnChoice(true);

                gamepadNavigationRouter.setDeadzone(0.30f);
                gamepadNavigationRouter.setInitialRepeatDelaySeconds(0.35);
                gamepadNavigationRouter.setRepeatIntervalSeconds(0.10);
            }
        }
        this.activePreset = preset;
    }

    /**
     * Speichert den vollständigen aktuellen Profilzustand inklusive aller Komponenteneinstellungen
     * atomar als UTF-8-JSON in {@code file}.
     *
     * @param file Zieldatei für das Profil (darf nicht {@code null} sein)
     * @throws NullPointerException falls {@code file} {@code null} ist
     * @throws IOException          falls das Verzeichnis nicht erstellt oder die Datei nicht geschrieben werden kann
     */
    public void save(Path file) throws IOException {
        Objects.requireNonNull(file, "file darf nicht null sein");
        Path normalized = file.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        SerializedProfile payload = new SerializedProfile(
                FORMAT_VERSION,
                activePreset,
                colorBlindnessFilter.getMode(),
                colorBlindnessFilter.getIntensity(),
                autoReadController.isEnabled(),
                autoReadController.getBaseDelaySeconds(),
                autoReadController.getSecondsPerChar(),
                autoReadController.isWaitForVoice(),
                skipModeController.getMode(),
                skipModeController.isStopOnChoice(),
                gamepadNavigationRouter.getDeadzone(),
                gamepadNavigationRouter.getInitialRepeatDelaySeconds(),
                gamepadNavigationRouter.getRepeatIntervalSeconds()
        );
        String json = GSON.toJson(payload);

        Path tempFile = Files.createTempFile(
                parent != null ? parent : Path.of(".").toAbsolutePath().normalize(),
                "accessibility-profile-",
                ".tmp"
        );
        try {
            Files.writeString(tempFile, json, StandardCharsets.UTF_8);
            try {
                Files.move(tempFile, normalized, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tempFile, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Lädt ein zuvor mit {@link #save(Path)} gespeichertes {@code SnowWhiteAccessibilityProfile}
     * aus einer JSON-Datei und validiert alle Parameterbereiche.
     *
     * @param file Quelldatei des gespeicherten Profils (darf nicht {@code null} sein)
     * @return vollständig rekonstruiertes {@code SnowWhiteAccessibilityProfile}
     * @throws NullPointerException falls {@code file} {@code null} ist
     * @throws IOException          falls die Datei fehlt, nicht lesbar ist oder ungültiges JSON enthält
     */
    public static SnowWhiteAccessibilityProfile load(Path file) throws IOException {
        Objects.requireNonNull(file, "file darf nicht null sein");
        Path normalized = file.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            throw new NoSuchFileException("Profildatei existiert nicht: " + normalized);
        }
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("Profildatei ist keine reguläre Datei: " + normalized);
        }

        String json = Files.readString(normalized, StandardCharsets.UTF_8);
        if (json.isBlank()) {
            throw new IOException("Profildatei ist leer: " + normalized);
        }

        SerializedProfile payload;
        try {
            payload = GSON.fromJson(json, SerializedProfile.class);
        } catch (JsonParseException ex) {
            throw new IOException("Ungültiges JSON in Profildatei: " + normalized, ex);
        }

        if (payload == null
                || payload.activePreset() == null
                || payload.colorMode() == null
                || payload.skipMode() == null) {
            throw new IOException("Unvollständige Profildaten in: " + normalized);
        }

        try {
            ColorBlindnessFilter filter = new ColorBlindnessFilter(payload.colorMode(), payload.colorIntensity());
            AutoReadController autoRead = new AutoReadController(
                    payload.autoReadBaseDelaySeconds(),
                    payload.autoReadSecondsPerChar(),
                    payload.autoReadWaitForVoice()
            );
            autoRead.setEnabled(payload.autoReadEnabled());

            SkipModeController skip = new SkipModeController(payload.skipMode(), payload.skipStopOnChoice());
            GamepadNavigationRouter gamepad = new GamepadNavigationRouter(
                    payload.gamepadDeadzone(),
                    payload.gamepadInitialRepeatDelaySeconds(),
                    payload.gamepadRepeatIntervalSeconds()
            );

            return new SnowWhiteAccessibilityProfile(filter, autoRead, skip, gamepad, payload.activePreset());
        } catch (IllegalArgumentException ex) {
            throw new IOException("Ungültige Werte in Profildatei: " + normalized, ex);
        }
    }

    /**
     * Gibt das aktuell ausgewählte {@link ProfilePreset} zurück.
     *
     * @return aktives {@link ProfilePreset}
     */
    public ProfilePreset getActivePreset() {
        return activePreset;
    }

    /**
     * Gibt den gekapselten {@link ColorBlindnessFilter} zurück.
     *
     * @return aktiver {@link ColorBlindnessFilter}
     */
    public ColorBlindnessFilter getColorBlindnessFilter() {
        return colorBlindnessFilter;
    }

    /**
     * Gibt den gekapselten {@link AutoReadController} zurück.
     *
     * @return aktiver {@link AutoReadController}
     */
    public AutoReadController getAutoReadController() {
        return autoReadController;
    }

    /**
     * Gibt den gekapselten {@link SkipModeController} zurück.
     *
     * @return aktiver {@link SkipModeController}
     */
    public SkipModeController getSkipModeController() {
        return skipModeController;
    }

    /**
     * Gibt den gekapselten {@link GamepadNavigationRouter} zurück.
     *
     * @return aktiver {@link GamepadNavigationRouter}
     */
    public GamepadNavigationRouter getGamepadNavigationRouter() {
        return gamepadNavigationRouter;
    }
}
