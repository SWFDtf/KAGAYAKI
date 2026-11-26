package de.swfdtf.demo.director;

import de.swfdtf.graphics.WeatherOverlayController;
import de.swfdtf.graphics.WeatherOverlayController.WeatherPreset;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Steuert die atmosphärischen Wetter- und Wind-Effekte des Schneewittchen-Demos über
 * {@link WeatherOverlayController} passend zu den sieben Märchenkapiteln.
 */
public final class SnowWhiteWeatherDirector {

    /** Niedrigste gültige Kapitelnummer im Schneewittchen-Demo. */
    public static final int MIN_CHAPTER = 1;

    /** Höchste gültige Kapitelnummer im Schneewittchen-Demo. */
    public static final int MAX_CHAPTER = 7;

    /**
     * Unveränderliche Wetter- und Windkonfiguration für ein einzelnes Kapitel.
     *
     * @param preset      zuzuordnendes {@link WeatherPreset}
     * @param intensity   Zielintensität in {@code [0.0f, 1.0f]}
     * @param windX       horizontale Windgeschwindigkeit in logischen Pixeln pro Sekunde
     * @param windY       vertikale Windgeschwindigkeit in logischen Pixeln pro Sekunde
     * @param description kurze deutsche Beschreibung der Kapitel-Atmosphäre
     */
    public record WeatherCue(
            WeatherPreset preset,
            float intensity,
            float windX,
            float windY,
            String description
    ) {
        /**
         * Validiert alle Felder einer Wetter-Vorgabe.
         */
        public WeatherCue {
            Objects.requireNonNull(preset, "preset darf nicht null sein");
            if (!Float.isFinite(intensity) || intensity < 0.0f || intensity > 1.0f) {
                throw new IllegalArgumentException("intensity muss endlich und in [0.0, 1.0] liegen: " + intensity);
            }
            if (!Float.isFinite(windX)) {
                throw new IllegalArgumentException("windX muss endlich sein: " + windX);
            }
            if (!Float.isFinite(windY)) {
                throw new IllegalArgumentException("windY muss endlich sein: " + windY);
            }
            Objects.requireNonNull(description, "description darf nicht null sein");
            description = description.trim();
            if (description.isEmpty()) {
                throw new IllegalArgumentException("description darf nicht leer sein");
            }
        }
    }

    private static final Map<Integer, WeatherCue> CHAPTER_CUES = createChapterCues();

    private final WeatherOverlayController controller;
    private Integer activeChapter;

    /**
     * Erzeugt einen {@code SnowWhiteWeatherDirector} mit einem neuen {@link WeatherOverlayController}.
     */
    public SnowWhiteWeatherDirector() {
        this(new WeatherOverlayController());
    }

    /**
     * Erzeugt einen {@code SnowWhiteWeatherDirector} mit dem übergebenen {@link WeatherOverlayController}.
     *
     * @param controller zugrundeliegender Wetter-Controller (darf nicht {@code null} sein)
     * @throws NullPointerException falls {@code controller} {@code null} ist
     */
    public SnowWhiteWeatherDirector(WeatherOverlayController controller) {
        this.controller = Objects.requireNonNull(controller, "controller darf nicht null sein");
    }

    /**
     * Gibt eine unveränderliche Zuordnung aller sieben Kapitelnummern ({@code 1..7}) auf ihre
     * jeweilige {@link WeatherCue} zurück.
     *
     * @return unveränderliche Map von Kapitelnummer auf {@link WeatherCue}
     */
    public static Map<Integer, WeatherCue> getChapterCues() {
        return CHAPTER_CUES;
    }

    /**
     * Sucht die definierte {@link WeatherCue} zu einer Kapitelnummer, sofern vorhanden.
     *
     * @param chapterNumber Kapitelnummer
     * @return {@link Optional} mit der passenden {@link WeatherCue}, oder leer außerhalb von {@code 1..7}
     */
    public static Optional<WeatherCue> findCueForChapter(int chapterNumber) {
        return Optional.ofNullable(CHAPTER_CUES.get(chapterNumber));
    }

    /**
     * Aktiviert das Wetter-Preset inklusive Windvektor für das angegebene Kapitel {@code 1..7}.
     *
     * @param chapterNumber     Kapitelnummer in {@code 1..7}
     * @param transitionSeconds Übergangsdauer in Sekunden ({@code >= 0.0})
     * @throws IllegalArgumentException falls {@code chapterNumber} außerhalb {@code 1..7} liegt
     *                                  oder {@code transitionSeconds} negativ bzw. nicht endlich ist
     */
    public void applyForChapter(int chapterNumber, double transitionSeconds) {
        validateChapterNumber(chapterNumber);
        validateNonNegativeFinite(transitionSeconds, "transitionSeconds");

        WeatherCue cue = CHAPTER_CUES.get(chapterNumber);
        controller.setWind(cue.windX(), cue.windY());
        controller.setWeather(cue.preset(), cue.intensity(), transitionSeconds);
        this.activeChapter = chapterNumber;
    }

    /**
     * Aktualisiert laufende Wetter-Übergänge und verknüpfte Partikelsysteme um {@code dt} Sekunden.
     *
     * @param dt verstrichene Frame-Zeit in Sekunden ({@code >= 0.0})
     * @throws IllegalArgumentException falls {@code dt} negativ oder nicht endlich ist
     */
    public void update(double dt) {
        validateNonNegativeFinite(dt, "dt");
        controller.update(dt);
    }

    /**
     * Gibt den gekapselten {@link WeatherOverlayController} zurück.
     *
     * @return aktiver {@link WeatherOverlayController}
     */
    public WeatherOverlayController getController() {
        return controller;
    }

    /**
     * Gibt die zuletzt angewendete Kapitelnummer zurück, sofern bereits ein Kapitel aktiviert wurde.
     *
     * @return {@link Optional} mit aktiver Kapitelnummer oder leer vor dem ersten Kapitelaufruf
     */
    public Optional<Integer> getActiveChapter() {
        return Optional.ofNullable(activeChapter);
    }

    /**
     * Erzeugt eine kompakte deutsche Zustandsbeschreibung des aktuellen Wetters inklusive Intensität,
     * Windvektor und optionalem Kapitelkontext.
     *
     * @return formatierte Wetterbeschreibung (niemals {@code null} oder leer)
     */
    public String describeCurrentWeather() {
        WeatherPreset preset = controller.getCurrentPreset();
        int intensityPercent = Math.round(controller.getCurrentIntensity() * 100.0f);
        String presetLabel = switch (preset) {
            case CLEAR -> "Klar (CLEAR)";
            case RAIN -> "Regen (RAIN)";
            case SNOW -> "Schneefall (SNOW)";
            case SAKURA -> "Kirschblüten (SAKURA)";
            case FOG -> "Nebel (FOG)";
            case EMBERS -> "Funkenflug (EMBERS)";
        };

        String base = String.format(
                Locale.ROOT,
                "Wetter: %s | Intensität: %d%% | Wind: (%.1f, %.1f) px/s",
                presetLabel,
                intensityPercent,
                controller.getWindX(),
                controller.getWindY()
        );
        if (activeChapter == null) {
            return base;
        }
        WeatherCue cue = CHAPTER_CUES.get(activeChapter);
        return String.format(Locale.ROOT, "Kapitel %d (%s) – %s", activeChapter, cue.description(), base);
    }

    private static Map<Integer, WeatherCue> createChapterCues() {
        Map<Integer, WeatherCue> cues = new LinkedHashMap<>();
        cues.put(1, new WeatherCue(WeatherPreset.SNOW, 0.85f, 18.0f, 12.0f,
                "Schneefall am winterlichen Schlossfenster"));
        cues.put(2, new WeatherCue(WeatherPreset.FOG, 0.55f, 6.0f, 0.0f,
                "Kühler Nebelschleier im Spiegelsaal"));
        cues.put(3, new WeatherCue(WeatherPreset.FOG, 0.80f, -22.0f, 4.0f,
                "Dichter Waldnebel auf der Flucht"));
        cues.put(4, new WeatherCue(WeatherPreset.CLEAR, 0.0f, 0.0f, 0.0f,
                "Klare Waldlichtung am Zwergenhaus"));
        cues.put(5, new WeatherCue(WeatherPreset.RAIN, 0.90f, -45.0f, 28.0f,
                "Stürmischer Regen während der drei Mordversuche"));
        cues.put(6, new WeatherCue(WeatherPreset.SNOW, 0.65f, 10.0f, 6.0f,
                "Stiller Bergschnee rund um den Glassarg"));
        cues.put(7, new WeatherCue(WeatherPreset.EMBERS, 0.75f, 8.0f, -18.0f,
                "Festlicher Funkenflug im Hochzeitssaal"));
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
