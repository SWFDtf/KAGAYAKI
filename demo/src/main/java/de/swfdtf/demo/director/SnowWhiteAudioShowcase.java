package de.swfdtf.demo.director;

import de.swfdtf.audio.AmbientCrossfadeMixer;
import de.swfdtf.audio.AudioCueRegistry;
import de.swfdtf.audio.AudioCueRegistry.AudioCue;
import de.swfdtf.audio.AudioDuckingController;
import de.swfdtf.audio.ClosedCaptionQueue;
import de.swfdtf.audio.ClosedCaptionQueue.CaptionCategory;
import de.swfdtf.audio.VoicePlaybackBookmark;
import de.swfdtf.audio.VoicePlaybackBookmark.VoiceClipRecord;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Integriert {@link AudioCueRegistry}, {@link AudioDuckingController}, {@link AmbientCrossfadeMixer},
 * {@link ClosedCaptionQueue} und {@link VoicePlaybackBookmark} zu einer abgestimmten Audio-Regie
 * für alle sieben Kapitel des Schneewittchen-Demos.
 */
public final class SnowWhiteAudioShowcase {

    /** Niedrigste gültige Kapitelnummer im Schneewittchen-Demo. */
    public static final int MIN_CHAPTER = 1;

    /** Höchste gültige Kapitelnummer im Schneewittchen-Demo. */
    public static final int MAX_CHAPTER = 7;

    /**
     * Unveränderliche Audio-Szene für ein einzelnes Märchenkapitel.
     *
     * @param primaryCueId    ID des primären {@link AudioCue}
     * @param ambientLayers   unveränderliche Map der Zielgewichte je Ambient-/Musik-Layer
     * @param captionCategory Untertitelkategorie des Kapitel-Hinweises
     * @param captionSource   Quellenbezeichnung für {@link ClosedCaptionQueue}
     * @param captionText     deutscher Untertiteltext für den Audio-Cue
     * @param captionSeconds  Anzeigedauer des Untertitels in Sekunden ({@code > 0.0})
     */
    public record ChapterAudioScene(
            String primaryCueId,
            Map<String, Float> ambientLayers,
            CaptionCategory captionCategory,
            String captionSource,
            String captionText,
            double captionSeconds
    ) {
        /**
         * Validiert und normalisiert alle Felder einer Kapitel-Audioszene.
         */
        public ChapterAudioScene {
            primaryCueId = requireNonBlank(primaryCueId, "primaryCueId");
            Objects.requireNonNull(ambientLayers, "ambientLayers darf nicht null sein");
            ambientLayers = Map.copyOf(ambientLayers);
            Objects.requireNonNull(captionCategory, "captionCategory darf nicht null sein");
            captionSource = requireNonBlank(captionSource, "captionSource");
            captionText = requireNonBlank(captionText, "captionText");
            if (!Double.isFinite(captionSeconds) || captionSeconds <= 0.0) {
                throw new IllegalArgumentException("captionSeconds muss endlich und > 0.0 sein, war: " + captionSeconds);
            }
        }
    }

    private static final Map<Integer, ChapterAudioScene> CHAPTER_SCENES = createChapterScenes();

    private final AudioCueRegistry cueRegistry;
    private final AudioDuckingController duckingController;
    private final AmbientCrossfadeMixer ambientMixer;
    private final ClosedCaptionQueue captionQueue;
    private final VoicePlaybackBookmark voiceBookmark;

    private Integer activeChapter;
    private String activeCueId;
    private double voiceDuckingRemainingSeconds;

    /**
     * Erzeugt einen {@code SnowWhiteAudioShowcase} mit Standard-Komponenten und registriert alle
     * Schneewittchen-Audio-Cues.
     */
    public SnowWhiteAudioShowcase() {
        this(
                new AudioCueRegistry(),
                new AudioDuckingController(),
                new AmbientCrossfadeMixer(),
                new ClosedCaptionQueue(),
                new VoicePlaybackBookmark()
        );
    }

    /**
     * Erzeugt einen {@code SnowWhiteAudioShowcase} mit explizit injizierten Audio-Komponenten
     * und registriert die Schneewittchen-Standard-Cues in {@code cueRegistry}.
     *
     * @param cueRegistry       Audio-Cue-Register (darf nicht {@code null} sein)
     * @param duckingController Ducking-Controller für Sprach-Priorisierung (darf nicht {@code null} sein)
     * @param ambientMixer      Ambient-Crossfade-Mixer (darf nicht {@code null} sein)
     * @param captionQueue      Untertitel-Warteschlange (darf nicht {@code null} sein)
     * @param voiceBookmark     Verlauf der abgespielten Sprachzeilen (darf nicht {@code null} sein)
     * @throws NullPointerException falls eine der Komponenten {@code null} ist
     */
    public SnowWhiteAudioShowcase(
            AudioCueRegistry cueRegistry,
            AudioDuckingController duckingController,
            AmbientCrossfadeMixer ambientMixer,
            ClosedCaptionQueue captionQueue,
            VoicePlaybackBookmark voiceBookmark
    ) {
        this.cueRegistry = Objects.requireNonNull(cueRegistry, "cueRegistry darf nicht null sein");
        this.duckingController = Objects.requireNonNull(duckingController, "duckingController darf nicht null sein");
        this.ambientMixer = Objects.requireNonNull(ambientMixer, "ambientMixer darf nicht null sein");
        this.captionQueue = Objects.requireNonNull(captionQueue, "captionQueue darf nicht null sein");
        this.voiceBookmark = Objects.requireNonNull(voiceBookmark, "voiceBookmark darf nicht null sein");
        registerDefaultCues();
    }

    /**
     * Gibt eine unveränderliche Zuordnung aller sieben Kapitelnummern ({@code 1..7}) auf ihre
     * zugehörige {@link ChapterAudioScene} zurück.
     *
     * @return unveränderliche Map von Kapitelnummer auf {@link ChapterAudioScene}
     */
    public static Map<Integer, ChapterAudioScene> getChapterScenes() {
        return CHAPTER_SCENES;
    }

    /**
     * Aktiviert die Audio-Szene für das angegebene Kapitel {@code 1..7}: startet das
     * Ambient-Crossfade und fügt einen passenden Untertitel in {@link ClosedCaptionQueue} ein.
     *
     * @param chapterNumber Kapitelnummer in {@code 1..7}
     * @throws IllegalArgumentException falls {@code chapterNumber} außerhalb {@code 1..7} liegt
     */
    public void triggerChapterAudio(int chapterNumber) {
        validateChapterNumber(chapterNumber);
        ChapterAudioScene scene = CHAPTER_SCENES.get(chapterNumber);

        ambientMixer.applyPreset(scene.ambientLayers());
        captionQueue.push(
                scene.captionCategory(),
                scene.captionSource(),
                scene.captionText(),
                scene.captionSeconds()
        );
        this.activeChapter = chapterNumber;
        this.activeCueId = scene.primaryCueId();
    }

    /**
     * Zeichnet eine gesprochene Dialogzeile auf, aktiviert das Sprach-Ducking auf Musik/Ambient
     * und fügt den Dialog als {@link CaptionCategory#DIALOGUE}-Untertitel hinzu.
     *
     * @param lineId    eindeutige Zeilen-ID (darf nicht {@code null} oder leer sein)
     * @param speaker   Sprechername bzw. Sprecher-ID (darf nicht {@code null} oder leer sein)
     * @param text      gesprochener Text für Untertitel (darf nicht {@code null} oder leer sein)
     * @param assetPath Pfad zur Sprachdatei (darf nicht {@code null} oder leer sein)
     * @throws NullPointerException     falls ein Argument {@code null} ist
     * @throws IllegalArgumentException falls ein String leer/blank ist
     */
    public void recordVoiceLine(String lineId, String speaker, String text, String assetPath) {
        String validLineId = requireNonBlank(lineId, "lineId");
        String validSpeaker = requireNonBlank(speaker, "speaker");
        String validText = requireNonBlank(text, "text");
        String validAssetPath = requireNonBlank(assetPath, "assetPath");

        voiceBookmark.record(validLineId, validSpeaker, validAssetPath);
        double durationSeconds = Math.max(2.0, 1.2 + validText.length() * 0.05);
        captionQueue.push(CaptionCategory.DIALOGUE, validSpeaker, validText, durationSeconds);

        this.voiceDuckingRemainingSeconds = durationSeconds;
        duckingController.setVoiceActive(true);
    }

    /**
     * Beendet eine aktive Sprach-Ducking-Phase vorzeitig und gibt Musik-/Ambient-Kanäle frei.
     */
    public void stopVoicePlayback() {
        this.voiceDuckingRemainingSeconds = 0.0;
        duckingController.setVoiceActive(false);
    }

    /**
     * Aktualisiert Sprach-Ducking, Ambient-Crossfades und Untertitel-Restlaufzeiten um {@code dt} Sekunden.
     *
     * @param dt verstrichene Frame-Zeit in Sekunden ({@code >= 0.0})
     * @throws IllegalArgumentException falls {@code dt} negativ oder nicht endlich ist
     */
    public void update(double dt) {
        if (!Double.isFinite(dt) || dt < 0.0) {
            throw new IllegalArgumentException("dt muss endlich und >= 0.0 sein, war: " + dt);
        }

        if (voiceDuckingRemainingSeconds > 0.0 && dt > 0.0) {
            voiceDuckingRemainingSeconds = Math.max(0.0, voiceDuckingRemainingSeconds - dt);
            if (voiceDuckingRemainingSeconds <= 0.0) {
                duckingController.setVoiceActive(false);
            }
        }

        duckingController.update(dt);
        ambientMixer.update(dt);
        captionQueue.update(dt);
    }

    /**
     * Berechnet die effektive Musik-Verstärkung unter Berücksichtigung des aktuellen Ducking-Faktors.
     *
     * @param baseGain Ausgangslautstärke ({@code >= 0.0f})
     * @return geduckte Lautstärke {@code baseGain * duckMultiplier}
     */
    public float computeDuckedMusicGain(float baseGain) {
        return duckingController.applyToGain(baseGain);
    }

    /**
     * Gibt das {@link AudioCueRegistry} zurück.
     *
     * @return gekapseltes {@link AudioCueRegistry}
     */
    public AudioCueRegistry getCueRegistry() {
        return cueRegistry;
    }

    /**
     * Gibt den {@link AudioDuckingController} zurück.
     *
     * @return gekapselter {@link AudioDuckingController}
     */
    public AudioDuckingController getDuckingController() {
        return duckingController;
    }

    /**
     * Gibt den {@link AmbientCrossfadeMixer} zurück.
     *
     * @return gekapselter {@link AmbientCrossfadeMixer}
     */
    public AmbientCrossfadeMixer getAmbientMixer() {
        return ambientMixer;
    }

    /**
     * Gibt die {@link ClosedCaptionQueue} zurück.
     *
     * @return gekapselte {@link ClosedCaptionQueue}
     */
    public ClosedCaptionQueue getCaptionQueue() {
        return captionQueue;
    }

    /**
     * Gibt das {@link VoicePlaybackBookmark} zurück.
     *
     * @return gekapseltes {@link VoicePlaybackBookmark}
     */
    public VoicePlaybackBookmark getVoiceBookmark() {
        return voiceBookmark;
    }

    /**
     * Gibt die zuletzt ausgelöste Kapitelnummer zurück, falls vorhanden.
     *
     * @return {@link Optional} mit aktiver Kapitelnummer oder leer
     */
    public Optional<Integer> getActiveChapter() {
        return Optional.ofNullable(activeChapter);
    }

    /**
     * Gibt die ID des zuletzt ausgelösten Kapitel-Audio-Cues zurück, falls vorhanden.
     *
     * @return {@link Optional} mit aktiver Cue-ID oder leer
     */
    public Optional<String> getActiveCueId() {
        return Optional.ofNullable(activeCueId);
    }

    /**
     * Liefert die zuletzt aufgezeichnete Sprachzeile aus {@link VoicePlaybackBookmark}.
     *
     * @return {@link Optional} mit dem neuesten {@link VoiceClipRecord}
     */
    public Optional<VoiceClipRecord> findLatestVoiceLine() {
        return voiceBookmark.findLatest();
    }

    private void registerDefaultCues() {
        cueRegistry.register(new AudioCue("winter_prolog", "assets/audio/music/winter_prolog.wav",
                "music", 0.75f, true, 0.0f));
        cueRegistry.register(new AudioCue("spiegel_thema", "assets/audio/music/spiegel_thema.wav",
                "music", 0.75f, true, 0.0f));
        cueRegistry.register(new AudioCue("spiegel_klang", "assets/audio/sfx/spiegel_klang.wav",
                "sfx", 0.85f, false, 0.02f));
        cueRegistry.register(new AudioCue("wald_thema", "assets/audio/music/wald_thema.wav",
                "music", 0.72f, true, 0.0f));
        cueRegistry.register(new AudioCue("gefahr_thema", "assets/audio/music/gefahr_thema.wav",
                "music", 0.80f, true, 0.0f));
        cueRegistry.register(new AudioCue("finale_hochzeit", "assets/audio/music/finale_hochzeit.wav",
                "music", 0.85f, true, 0.0f));
        cueRegistry.register(new AudioCue("jubel", "assets/audio/sfx/jubel.wav",
                "sfx", 0.80f, false, 0.03f));
    }

    private static Map<Integer, ChapterAudioScene> createChapterScenes() {
        Map<Integer, ChapterAudioScene> scenes = new LinkedHashMap<>();
        scenes.put(1, new ChapterAudioScene(
                "winter_prolog",
                Map.of("winter_prolog", 0.85f, "winter_wind", 0.45f),
                CaptionCategory.MUSIC,
                "Musik",
                "[Winter-Prolog: Ruhige Streicher im Schneefall]",
                3.5
        ));
        scenes.put(2, new ChapterAudioScene(
                "spiegel_klang",
                Map.of("spiegel_klang", 0.75f, "spiegel_thema", 0.65f),
                CaptionCategory.SFX,
                "Zauberspiegel",
                "[Spiegelklang: Kristallines Erklingen des magischen Spiegels]",
                3.0
        ));
        scenes.put(3, new ChapterAudioScene(
                "wald_thema",
                Map.of("wald_thema", 0.80f, "wald_rauschen", 0.55f),
                CaptionCategory.MUSIC,
                "Musik",
                "[Wald-Thema: Geheimnisvolle Flucht durch den tiefen Forst]",
                3.5
        ));
        scenes.put(4, new ChapterAudioScene(
                "wald_thema",
                Map.of("wald_thema", 0.65f, "zwergenhaus_kamin", 0.50f),
                CaptionCategory.MUSIC,
                "Musik",
                "[Zwergenhaus: Warme Holzbläser am Kaminfeuer]",
                3.5
        ));
        scenes.put(5, new ChapterAudioScene(
                "gefahr_thema",
                Map.of("gefahr_thema", 0.90f, "gewitter_regen", 0.65f),
                CaptionCategory.MUSIC,
                "Musik",
                "[Gefahr-Thema: Dramatische Streicher bei den drei Mordversuchen]",
                3.5
        ));
        scenes.put(6, new ChapterAudioScene(
                "winter_prolog",
                Map.of("winter_prolog", 0.60f, "berg_wind", 0.40f),
                CaptionCategory.MUSIC,
                "Musik",
                "[Glassarg: Sanftes Winter-Motiv am Berghang]",
                3.5
        ));
        scenes.put(7, new ChapterAudioScene(
                "finale_hochzeit",
                Map.of("finale_hochzeit", 0.95f, "festsaal_jubel", 0.50f),
                CaptionCategory.MUSIC,
                "Musik",
                "[Finale Hochzeit: Festliche Fanfare im Königssaal]",
                4.0
        ));
        return Collections.unmodifiableMap(scenes);
    }

    private static void validateChapterNumber(int chapterNumber) {
        if (chapterNumber < MIN_CHAPTER || chapterNumber > MAX_CHAPTER) {
            throw new IllegalArgumentException(
                    "chapterNumber muss zwischen " + MIN_CHAPTER + " und " + MAX_CHAPTER + " liegen, war: " + chapterNumber
            );
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " darf nicht null sein");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " darf nicht leer sein");
        }
        return trimmed;
    }
}
