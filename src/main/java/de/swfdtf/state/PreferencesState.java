package de.swfdtf.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;

/**
 * Tier 4 cross-save user preferences persisted as human-readable JSON in {@code saves/preferences.json}.
 *
 * <p>Stores audio channel volumes, typewriter and auto-forward timing, skip behavior,
 * display/render mode, language, dialogue presentation mode, and accessibility options.</p>
 */
public final class PreferencesState {

    /** Standard preferences file name inside the saves directory. */
    public static final String FILE_NAME = "preferences.json";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private float masterVolume = 1.0f;
    private float musicVolume = 0.8f;
    private float soundVolume = 0.9f;
    private float voiceVolume = 1.0f;
    private float ambientVolume = 0.7f;
    private float textSpeedCps = 40.0f;
    private float autoForwardDelaySeconds = 2.5f;
    private boolean skipUnread = false;
    private boolean skipAfterChoices = false;
    private boolean fullscreen = false;
    private String renderMode = "2D";
    private String language = "deutsch";
    private String dialogueMode = "ADV";
    private boolean selfVoicing = false;
    private boolean highContrastDyslexiaFont = false;
    private float uiTextScale = 1.0f;

    /**
     * Creates a {@code PreferencesState} initialized with engine defaults.
     */
    public PreferencesState() {
    }

    /**
     * Loads user preferences from {@code savesDir/preferences.json}, returning a fresh instance
     * with default values if the file does not exist yet.
     *
     * @param savesDir saves directory path (must not be {@code null})
     * @return loaded and normalized {@link PreferencesState}
     * @throws IOException if the file exists but cannot be read or parsed
     */
    public static PreferencesState load(Path savesDir) throws IOException {
        Objects.requireNonNull(savesDir, "savesDir must not be null");
        Path file = savesDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return new PreferencesState();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            PreferencesState loaded = GSON.fromJson(reader, PreferencesState.class);
            if (loaded == null) {
                throw new IOException("Empty preferences file: " + file);
            }
            loaded.normalizeDefaults();
            return loaded;
        } catch (JsonParseException e) {
            throw new IOException("Failed to parse preferences file " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Saves user preferences atomically (temp write + move) to {@code savesDir/preferences.json}.
     *
     * @param savesDir saves directory path (must not be {@code null})
     * @throws IOException if creating directories or writing the file fails
     */
    public synchronized void save(Path savesDir) throws IOException {
        Objects.requireNonNull(savesDir, "savesDir must not be null");
        normalizeDefaults();
        Files.createDirectories(savesDir);
        Path target = savesDir.resolve(FILE_NAME);
        Path tempFile = Files.createTempFile(savesDir, "preferences-", ".tmp");
        boolean moved = false;
        try {
            try (Writer writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
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
     * Normalizes out-of-range or missing configuration fields to safe defaults.
     */
    public synchronized void normalizeDefaults() {
        masterVolume = clampVolume(masterVolume, 1.0f);
        musicVolume = clampVolume(musicVolume, 0.8f);
        soundVolume = clampVolume(soundVolume, 0.9f);
        voiceVolume = clampVolume(voiceVolume, 1.0f);
        ambientVolume = clampVolume(ambientVolume, 0.7f);
        if (!Float.isFinite(textSpeedCps) || textSpeedCps < 0.0f) {
            textSpeedCps = 40.0f;
        }
        if (!Float.isFinite(autoForwardDelaySeconds) || autoForwardDelaySeconds < 0.0f) {
            autoForwardDelaySeconds = 2.5f;
        }
        if (renderMode == null || renderMode.isBlank()) {
            renderMode = "2D";
        } else {
            renderMode = "3D".equalsIgnoreCase(renderMode.trim()) ? "3D" : "2D";
        }
        if (language == null || language.isBlank()) {
            language = "deutsch";
        } else {
            language = language.trim();
        }
        if (dialogueMode == null || dialogueMode.isBlank()) {
            dialogueMode = "ADV";
        } else {
            dialogueMode = dialogueMode.trim().toUpperCase(Locale.ROOT);
        }
        if (!Float.isFinite(uiTextScale) || uiTextScale <= 0.0f) {
            uiTextScale = 1.0f;
        }
    }

    private static float clampVolume(float value, float fallback) {
        if (!Float.isFinite(value)) {
            return fallback;
        }
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** @return master volume in {@code [0, 1]} */
    public synchronized float getMasterVolume() {
        return masterVolume;
    }

    /** @param masterVolume master volume in {@code [0, 1]} */
    public synchronized void setMasterVolume(float masterVolume) {
        this.masterVolume = clampVolume(masterVolume, 1.0f);
    }

    /** @return music volume in {@code [0, 1]} */
    public synchronized float getMusicVolume() {
        return musicVolume;
    }

    /** @param musicVolume music volume in {@code [0, 1]} */
    public synchronized void setMusicVolume(float musicVolume) {
        this.musicVolume = clampVolume(musicVolume, 0.8f);
    }

    /** @return sound effect volume in {@code [0, 1]} */
    public synchronized float getSoundVolume() {
        return soundVolume;
    }

    /** @param soundVolume sound effect volume in {@code [0, 1]} */
    public synchronized void setSoundVolume(float soundVolume) {
        this.soundVolume = clampVolume(soundVolume, 0.9f);
    }

    /** @return voice volume in {@code [0, 1]} */
    public synchronized float getVoiceVolume() {
        return voiceVolume;
    }

    /** @param voiceVolume voice volume in {@code [0, 1]} */
    public synchronized void setVoiceVolume(float voiceVolume) {
        this.voiceVolume = clampVolume(voiceVolume, 1.0f);
    }

    /** @return ambient audio volume in {@code [0, 1]} */
    public synchronized float getAmbientVolume() {
        return ambientVolume;
    }

    /** @param ambientVolume ambient audio volume in {@code [0, 1]} */
    public synchronized void setAmbientVolume(float ambientVolume) {
        this.ambientVolume = clampVolume(ambientVolume, 0.7f);
    }

    /** @return typewriter speed in characters per second ({@code 0} for instant) */
    public synchronized float getTextSpeedCps() {
        return textSpeedCps;
    }

    /** @param textSpeedCps typewriter speed in characters per second */
    public synchronized void setTextSpeedCps(float textSpeedCps) {
        this.textSpeedCps = (!Float.isFinite(textSpeedCps) || textSpeedCps < 0.0f) ? 40.0f : textSpeedCps;
    }

    /** @return auto-forward delay in seconds */
    public synchronized float getAutoForwardDelaySeconds() {
        return autoForwardDelaySeconds;
    }

    /** @param autoForwardDelaySeconds auto-forward delay in seconds */
    public synchronized void setAutoForwardDelaySeconds(float autoForwardDelaySeconds) {
        this.autoForwardDelaySeconds = (!Float.isFinite(autoForwardDelaySeconds) || autoForwardDelaySeconds < 0.0f)
                ? 2.5f
                : autoForwardDelaySeconds;
    }

    /** @return whether unread lines may be skipped */
    public synchronized boolean isSkipUnread() {
        return skipUnread;
    }

    /** @param skipUnread whether unread lines may be skipped */
    public synchronized void setSkipUnread(boolean skipUnread) {
        this.skipUnread = skipUnread;
    }

    /** @return whether skip continues automatically after player menu choices */
    public synchronized boolean isSkipAfterChoices() {
        return skipAfterChoices;
    }

    /** @param skipAfterChoices whether skip continues after choices */
    public synchronized void setSkipAfterChoices(boolean skipAfterChoices) {
        this.skipAfterChoices = skipAfterChoices;
    }

    /** @return whether fullscreen display is enabled */
    public synchronized boolean isFullscreen() {
        return fullscreen;
    }

    /** @param fullscreen fullscreen display flag */
    public synchronized void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
    }

    /** @return render backend mode ({@code "2D"} or {@code "3D"}) */
    public synchronized String getRenderMode() {
        return renderMode;
    }

    /** @param renderMode render backend mode ({@code "2D"} or {@code "3D"}) */
    public synchronized void setRenderMode(String renderMode) {
        this.renderMode = (renderMode != null && "3D".equalsIgnoreCase(renderMode.trim())) ? "3D" : "2D";
    }

    /** @return active translation language identifier */
    public synchronized String getLanguage() {
        return language;
    }

    /** @param language translation language identifier */
    public synchronized void setLanguage(String language) {
        this.language = (language == null || language.isBlank()) ? "deutsch" : language.trim();
    }

    /** @return preferred dialogue presentation mode ({@code "ADV"}, {@code "NVL"}, {@code "BUBBLE"}) */
    public synchronized String getDialogueMode() {
        return dialogueMode;
    }

    /** @param dialogueMode preferred dialogue presentation mode */
    public synchronized void setDialogueMode(String dialogueMode) {
        this.dialogueMode = (dialogueMode == null || dialogueMode.isBlank())
                ? "ADV"
                : dialogueMode.trim().toUpperCase(Locale.ROOT);
    }

    /** @return whether TTS / self-voicing accessibility mode is enabled */
    public synchronized boolean isSelfVoicing() {
        return selfVoicing;
    }

    /** @param selfVoicing self-voicing accessibility flag */
    public synchronized void setSelfVoicing(boolean selfVoicing) {
        this.selfVoicing = selfVoicing;
    }

    /** @return whether high-contrast & dyslexia-friendly font mode is enabled */
    public synchronized boolean isHighContrastDyslexiaFont() {
        return highContrastDyslexiaFont;
    }

    /** @param highContrastDyslexiaFont dyslexia/high-contrast accessibility flag */
    public synchronized void setHighContrastDyslexiaFont(boolean highContrastDyslexiaFont) {
        this.highContrastDyslexiaFont = highContrastDyslexiaFont;
    }

    /** @return UI accessibility text scale multiplier */
    public synchronized float getUiTextScale() {
        return uiTextScale;
    }

    /** @param uiTextScale UI accessibility text scale multiplier ({@code > 0}) */
    public synchronized void setUiTextScale(float uiTextScale) {
        this.uiTextScale = (!Float.isFinite(uiTextScale) || uiTextScale <= 0.0f) ? 1.0f : uiTextScale;
    }

    @Override
    public synchronized boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PreferencesState other)) {
            return false;
        }
        return Float.compare(masterVolume, other.masterVolume) == 0
                && Float.compare(musicVolume, other.musicVolume) == 0
                && Float.compare(soundVolume, other.soundVolume) == 0
                && Float.compare(voiceVolume, other.voiceVolume) == 0
                && Float.compare(ambientVolume, other.ambientVolume) == 0
                && Float.compare(textSpeedCps, other.textSpeedCps) == 0
                && Float.compare(autoForwardDelaySeconds, other.autoForwardDelaySeconds) == 0
                && skipUnread == other.skipUnread
                && skipAfterChoices == other.skipAfterChoices
                && fullscreen == other.fullscreen
                && selfVoicing == other.selfVoicing
                && highContrastDyslexiaFont == other.highContrastDyslexiaFont
                && Float.compare(uiTextScale, other.uiTextScale) == 0
                && Objects.equals(renderMode, other.renderMode)
                && Objects.equals(language, other.language)
                && Objects.equals(dialogueMode, other.dialogueMode);
    }

    @Override
    public synchronized int hashCode() {
        return Objects.hash(
                masterVolume, musicVolume, soundVolume, voiceVolume, ambientVolume,
                textSpeedCps, autoForwardDelaySeconds, skipUnread, skipAfterChoices,
                fullscreen, renderMode, language, dialogueMode,
                selfVoicing, highContrastDyslexiaFont, uiTextScale
        );
    }
}
