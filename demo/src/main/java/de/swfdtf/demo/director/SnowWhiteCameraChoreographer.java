package de.swfdtf.demo.director;

import de.swfdtf.graphics.CameraDirectorPresets;
import de.swfdtf.graphics.CameraDirectorPresets.CameraPreset;
import de.swfdtf.graphics.CinematicLetterboxController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Koordiniert Kamera-Presets ({@link CameraDirectorPresets}) und Kino-Balken
 * ({@link CinematicLetterboxController}) für alle sieben Kapitel des Schneewittchen-Demos.
 */
public final class SnowWhiteCameraChoreographer {

    /** Niedrigste gültige Kapitelnummer im Schneewittchen-Demo. */
    public static final int MIN_CHAPTER = 1;

    /** Höchste gültige Kapitelnummer im Schneewittchen-Demo. */
    public static final int MAX_CHAPTER = 7;

    /**
     * Unveränderliche Regie-Vorgabe aus Kamera-Preset und Zielhöhe der Letterbox-Balken.
     *
     * @param cameraPreset       ausgewähltes {@link CameraPreset}
     * @param letterboxBarHeight Zielhöhe je Balken in logischen Pixeln ({@code 0.0f} deaktiviert Balken)
     * @param description        kurze deutsche Beschreibung der Kameraeinstellung
     */
    public record CameraCue(
            CameraPreset cameraPreset,
            float letterboxBarHeight,
            String description
    ) {
        /**
         * Validiert alle Felder der Kamera-Vorgabe.
         */
        public CameraCue {
            Objects.requireNonNull(cameraPreset, "cameraPreset darf nicht null sein");
            if (!Float.isFinite(letterboxBarHeight) || letterboxBarHeight < 0.0f) {
                throw new IllegalArgumentException(
                        "letterboxBarHeight muss endlich und >= 0.0 sein, war: " + letterboxBarHeight
                );
            }
            Objects.requireNonNull(description, "description darf nicht null sein");
            description = description.trim();
            if (description.isEmpty()) {
                throw new IllegalArgumentException("description darf nicht leer sein");
            }
        }
    }

    private static final Map<Integer, CameraCue> CHAPTER_CUES = createChapterCues();

    private final CameraDirectorPresets cameraPresets;
    private final CinematicLetterboxController letterboxController;
    private Integer activeChapter;

    /**
     * Erzeugt einen {@code SnowWhiteCameraChoreographer} mit neuen Controller-Instanzen.
     */
    public SnowWhiteCameraChoreographer() {
        this(new CameraDirectorPresets(), new CinematicLetterboxController());
    }

    /**
     * Erzeugt einen {@code SnowWhiteCameraChoreographer} mit explizit übergebenen Controllern.
     *
     * @param cameraPresets       Kamera-Preset-Controller (darf nicht {@code null} sein)
     * @param letterboxController Letterbox-Controller (darf nicht {@code null} sein)
     * @throws NullPointerException falls einer der Controller {@code null} ist
     */
    public SnowWhiteCameraChoreographer(
            CameraDirectorPresets cameraPresets,
            CinematicLetterboxController letterboxController
    ) {
        this.cameraPresets = Objects.requireNonNull(cameraPresets, "cameraPresets darf nicht null sein");
        this.letterboxController = Objects.requireNonNull(
                letterboxController,
                "letterboxController darf nicht null sein"
        );
    }

    /**
     * Gibt eine unveränderliche Zuordnung aller sieben Kapitelnummern ({@code 1..7}) auf ihre
     * jeweilige {@link CameraCue} zurück.
     *
     * @return unveränderliche Map von Kapitelnummer auf {@link CameraCue}
     */
    public static Map<Integer, CameraCue> getChapterCues() {
        return CHAPTER_CUES;
    }

    /**
     * Sucht die definierte {@link CameraCue} zu einer Kapitelnummer, sofern vorhanden.
     *
     * @param chapterNumber Kapitelnummer
     * @return {@link Optional} mit passender {@link CameraCue}, oder leer außerhalb von {@code 1..7}
     */
    public static Optional<CameraCue> findCueForChapter(int chapterNumber) {
        return Optional.ofNullable(CHAPTER_CUES.get(chapterNumber));
    }

    /**
     * Wendet das Kamera-Preset und die passende Letterbox-Konfiguration für {@code chapterNumber}
     * über {@code durationSeconds} an.
     *
     * @param chapterNumber   Kapitelnummer in {@code 1..7}
     * @param durationSeconds Übergangsdauer in Sekunden ({@code >= 0.0})
     * @throws IllegalArgumentException falls {@code chapterNumber} außerhalb {@code 1..7} liegt
     *                                  oder {@code durationSeconds} negativ bzw. nicht endlich ist
     */
    public void applyChapterCue(int chapterNumber, double durationSeconds) {
        validateChapterNumber(chapterNumber);
        validateNonNegativeFinite(durationSeconds, "durationSeconds");

        CameraCue cue = CHAPTER_CUES.get(chapterNumber);
        cameraPresets.applyPreset(cue.cameraPreset(), durationSeconds);
        if (cue.letterboxBarHeight() > 0.0f) {
            letterboxController.engage(cue.letterboxBarHeight(), durationSeconds);
        } else {
            letterboxController.release(durationSeconds);
        }
        this.activeChapter = chapterNumber;
    }

    /**
     * Aktualisiert laufende Kamera- und Letterbox-Animationen um {@code dt} Sekunden.
     *
     * @param dt verstrichene Frame-Zeit in Sekunden ({@code >= 0.0})
     * @throws IllegalArgumentException falls {@code dt} negativ oder nicht endlich ist
     */
    public void update(double dt) {
        validateNonNegativeFinite(dt, "dt");
        cameraPresets.update(dt);
        letterboxController.update(dt);
    }

    /**
     * Gibt den gekapselten {@link CameraDirectorPresets}-Controller zurück.
     *
     * @return aktiver {@link CameraDirectorPresets}
     */
    public CameraDirectorPresets getCameraPresets() {
        return cameraPresets;
    }

    /**
     * Gibt den gekapselten {@link CinematicLetterboxController} zurück.
     *
     * @return aktiver {@link CinematicLetterboxController}
     */
    public CinematicLetterboxController getLetterboxController() {
        return letterboxController;
    }

    /**
     * Gibt die zuletzt angewendete Kapitelnummer zurück, sofern bereits ein Cue gestartet wurde.
     *
     * @return {@link Optional} mit aktiver Kapitelnummer oder leer
     */
    public Optional<Integer> getActiveChapter() {
        return Optional.ofNullable(activeChapter);
    }

    private static Map<Integer, CameraCue> createChapterCues() {
        Map<Integer, CameraCue> cues = new LinkedHashMap<>();
        cues.put(1, new CameraCue(CameraPreset.ESTABLISHING, 90.0f,
                "Weite Eröffnung am verschneiten Schlossfenster"));
        cues.put(2, new CameraCue(CameraPreset.CLOSE_UP, CinematicLetterboxController.DEFAULT_BAR_HEIGHT,
                "Nahaufnahme vor dem Zauberspiegel"));
        cues.put(3, new CameraCue(CameraPreset.PAN_LEFT_TO_RIGHT, CinematicLetterboxController.DEFAULT_BAR_HEIGHT,
                "Horizontale Kamerafahrt durch den tiefen Wald"));
        cues.put(4, new CameraCue(CameraPreset.ESTABLISHING, 0.0f,
                "Offener Bühnenblick im Haus der sieben Zwerge"));
        cues.put(5, new CameraCue(CameraPreset.DRAMATIC_TILT, 140.0f,
                "Dramatischer Dutch Angle während der drei Mordversuche"));
        cues.put(6, new CameraCue(CameraPreset.ZOOM_PULSE, CinematicLetterboxController.DEFAULT_BAR_HEIGHT,
                "Ruhiger Fokus-Zoom auf den Glassarg"));
        cues.put(7, new CameraCue(CameraPreset.ESTABLISHING, 0.0f,
                "Freier Festsaal-Blick zur königlichen Hochzeit"));
        return Collections.unmodifiableMap(cues);
    }

    private static void validateChapterNumber(int chapterNumber) {
        if (chapterNumber < MIN_CHAPTER || chapterNumber > MAX_CHAPTER) {
            throw new IllegalArgumentException(
                    "chapterNumber muss zwischen " + MIN_CHAPTER + " und " + MAX_CHAPTER + " liegen, war: " + chapterNumber
            );
        }
    }

    private static void validateNonNegativeFinite(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " muss endlich und >= 0.0 sein, war: " + value);
        }
    }
}
