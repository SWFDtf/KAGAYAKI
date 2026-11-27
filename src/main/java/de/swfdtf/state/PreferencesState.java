package de.swfdtf.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
    private float gamepadDeadzone = 0.15f;
    private float textboxOpacity = 0.88f;
    private boolean reduceMotion = false;
    private int safezoneMarginPx = 0;
    private String accessibilityCursorStyle = "DEFAULT";
    private String textBackdropMode = "BOX";
    private boolean safeLetterboxUltrawide = false;
    private boolean choiceNumberHotkeysEnabled = true;

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
            fsyncFile(tempFile);
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
        if (!Float.isFinite(gamepadDeadzone) || gamepadDeadzone < 0.0f) {
            gamepadDeadzone = 0.15f;
        } else if (gamepadDeadzone > 0.95f) {
            gamepadDeadzone = 0.95f;
        }
        textboxOpacity = clampVolume(textboxOpacity, 0.88f);
        safezoneMarginPx = Math.clamp(safezoneMarginPx, 0, 64);
        if (accessibilityCursorStyle == null || accessibilityCursorStyle.isBlank()) {
            accessibilityCursorStyle = "DEFAULT";
        } else {
            String upper = accessibilityCursorStyle.trim().toUpperCase(Locale.ROOT);
            accessibilityCursorStyle = ("LARGE_YELLOW".equals(upper) || "INVERTED".equals(upper))
                    ? upper : "DEFAULT";
        }
        if (textBackdropMode == null || textBackdropMode.isBlank()) {
            textBackdropMode = "BOX";
        } else {
            String upper = textBackdropMode.trim().toUpperCase(Locale.ROOT);
            textBackdropMode = ("DARK_STRIP".equals(upper) || "NONE".equals(upper))
                    ? upper : "BOX";
        }
    }

    private static float clampVolume(float value, float fallback) {
        if (!Float.isFinite(value)) {
            return fallback;
        }
        return Math.max(0.0f, Math.min(1.0f, value));
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

    /**
     * Returns the master volume.
     *
     * @return master volume in {@code [0, 1]}
     */
    public synchronized float getMasterVolume() {
        return masterVolume;
    }

    /**
     * Sets the master volume.
     *
     * @param masterVolume master volume in {@code [0, 1]}
     */
    public synchronized void setMasterVolume(float masterVolume) {
        this.masterVolume = clampVolume(masterVolume, 1.0f);
    }

    /**
     * Returns the music volume.
     *
     * @return music volume in {@code [0, 1]}
     */
    public synchronized float getMusicVolume() {
        return musicVolume;
    }

    /**
     * Sets the music volume.
     *
     * @param musicVolume music volume in {@code [0, 1]}
     */
    public synchronized void setMusicVolume(float musicVolume) {
        this.musicVolume = clampVolume(musicVolume, 0.8f);
    }

    /**
     * Returns the sound effect volume.
     *
     * @return sound effect volume in {@code [0, 1]}
     */
    public synchronized float getSoundVolume() {
        return soundVolume;
    }

    /**
     * Sets the sound effect volume.
     *
     * @param soundVolume sound effect volume in {@code [0, 1]}
     */
    public synchronized void setSoundVolume(float soundVolume) {
        this.soundVolume = clampVolume(soundVolume, 0.9f);
    }

    /**
     * Returns the voice volume.
     *
     * @return voice volume in {@code [0, 1]}
     */
    public synchronized float getVoiceVolume() {
        return voiceVolume;
    }

    /**
     * Sets the voice volume.
     *
     * @param voiceVolume voice volume in {@code [0, 1]}
     */
    public synchronized void setVoiceVolume(float voiceVolume) {
        this.voiceVolume = clampVolume(voiceVolume, 1.0f);
    }

    /**
     * Returns the ambient audio volume.
     *
     * @return ambient audio volume in {@code [0, 1]}
     */
    public synchronized float getAmbientVolume() {
        return ambientVolume;
    }

    /**
     * Sets the ambient audio volume.
     *
     * @param ambientVolume ambient audio volume in {@code [0, 1]}
     */
    public synchronized void setAmbientVolume(float ambientVolume) {
        this.ambientVolume = clampVolume(ambientVolume, 0.7f);
    }

    /**
     * Returns the typewriter text speed.
     *
     * @return typewriter speed in characters per second ({@code 0} for instant)
     */
    public synchronized float getTextSpeedCps() {
        return textSpeedCps;
    }

    /**
     * Sets the typewriter text speed.
     *
     * @param textSpeedCps typewriter speed in characters per second
     */
    public synchronized void setTextSpeedCps(float textSpeedCps) {
        this.textSpeedCps = (!Float.isFinite(textSpeedCps) || textSpeedCps < 0.0f) ? 40.0f : textSpeedCps;
    }

    /**
     * Returns the auto-forward delay in seconds.
     *
     * @return auto-forward delay in seconds
     */
    public synchronized float getAutoForwardDelaySeconds() {
        return autoForwardDelaySeconds;
    }

    /**
     * Sets the auto-forward delay in seconds.
     *
     * @param autoForwardDelaySeconds auto-forward delay in seconds
     */
    public synchronized void setAutoForwardDelaySeconds(float autoForwardDelaySeconds) {
        this.autoForwardDelaySeconds = (!Float.isFinite(autoForwardDelaySeconds) || autoForwardDelaySeconds < 0.0f)
                ? 2.5f
                : autoForwardDelaySeconds;
    }

    /**
     * Returns whether unread lines may be skipped.
     *
     * @return whether unread lines may be skipped
     */
    public synchronized boolean isSkipUnread() {
        return skipUnread;
    }

    /**
     * Sets whether unread lines may be skipped.
     *
     * @param skipUnread whether unread lines may be skipped
     */
    public synchronized void setSkipUnread(boolean skipUnread) {
        this.skipUnread = skipUnread;
    }

    /**
     * Returns whether skip continues after menu choices.
     *
     * @return whether skip continues automatically after player menu choices
     */
    public synchronized boolean isSkipAfterChoices() {
        return skipAfterChoices;
    }

    /**
     * Sets whether skip continues after menu choices.
     *
     * @param skipAfterChoices whether skip continues after choices
     */
    public synchronized void setSkipAfterChoices(boolean skipAfterChoices) {
        this.skipAfterChoices = skipAfterChoices;
    }

    /**
     * Returns whether fullscreen display is enabled.
     *
     * @return whether fullscreen display is enabled
     */
    public synchronized boolean isFullscreen() {
        return fullscreen;
    }

    /**
     * Sets whether fullscreen display is enabled.
     *
     * @param fullscreen fullscreen display flag
     */
    public synchronized void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
    }

    /**
     * Returns the render backend mode.
     *
     * @return render backend mode ({@code "2D"} or {@code "3D"})
     */
    public synchronized String getRenderMode() {
        return renderMode;
    }

    /**
     * Sets the render backend mode.
     *
     * @param renderMode render backend mode ({@code "2D"} or {@code "3D"})
     */
    public synchronized void setRenderMode(String renderMode) {
        this.renderMode = (renderMode != null && "3D".equalsIgnoreCase(renderMode.trim())) ? "3D" : "2D";
    }

    /**
     * Returns the active translation language identifier.
     *
     * @return active translation language identifier
     */
    public synchronized String getLanguage() {
        return language;
    }

    /**
     * Sets the active translation language identifier.
     *
     * @param language translation language identifier
     */
    public synchronized void setLanguage(String language) {
        this.language = (language == null || language.isBlank()) ? "deutsch" : language.trim();
    }

    /**
     * Returns the preferred dialogue presentation mode.
     *
     * @return preferred dialogue presentation mode ({@code "ADV"}, {@code "NVL"}, {@code "BUBBLE"})
     */
    public synchronized String getDialogueMode() {
        return dialogueMode;
    }

    /**
     * Sets the preferred dialogue presentation mode.
     *
     * @param dialogueMode preferred dialogue presentation mode
     */
    public synchronized void setDialogueMode(String dialogueMode) {
        this.dialogueMode = (dialogueMode == null || dialogueMode.isBlank())
                ? "ADV"
                : dialogueMode.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Returns whether self-voicing TTS mode is enabled.
     *
     * @return whether TTS / self-voicing accessibility mode is enabled
     */
    public synchronized boolean isSelfVoicing() {
        return selfVoicing;
    }

    /**
     * Sets whether self-voicing TTS mode is enabled.
     *
     * @param selfVoicing self-voicing accessibility flag
     */
    public synchronized void setSelfVoicing(boolean selfVoicing) {
        this.selfVoicing = selfVoicing;
    }

    /**
     * Returns whether high-contrast and dyslexia-friendly font mode is enabled.
     *
     * @return whether high-contrast &amp; dyslexia-friendly font mode is enabled
     */
    public synchronized boolean isHighContrastDyslexiaFont() {
        return highContrastDyslexiaFont;
    }

    /**
     * Sets whether high-contrast and dyslexia-friendly font mode is enabled.
     *
     * @param highContrastDyslexiaFont dyslexia/high-contrast accessibility flag
     */
    public synchronized void setHighContrastDyslexiaFont(boolean highContrastDyslexiaFont) {
        this.highContrastDyslexiaFont = highContrastDyslexiaFont;
    }

    /**
     * Returns the UI accessibility text scale multiplier.
     *
     * @return UI accessibility text scale multiplier
     */
    public synchronized float getUiTextScale() {
        return uiTextScale;
    }

    /**
     * Sets the UI accessibility text scale multiplier.
     *
     * @param uiTextScale UI accessibility text scale multiplier ({@code > 0})
     */
    public synchronized void setUiTextScale(float uiTextScale) {
        this.uiTextScale = (!Float.isFinite(uiTextScale) || uiTextScale <= 0.0f) ? 1.0f : uiTextScale;
    }

    /**
     * Returns the configured analog gamepad deadzone threshold.
     *
     * @return deadzone threshold in {@code [0.0, 0.95]}
     */
    public synchronized float getGamepadDeadzone() {
        return gamepadDeadzone;
    }

    /**
     * Sets the analog gamepad deadzone threshold.
     *
     * @param gamepadDeadzone deadzone threshold in {@code [0.0, 0.95]}
     */
    public synchronized void setGamepadDeadzone(float gamepadDeadzone) {
        if (!Float.isFinite(gamepadDeadzone) || gamepadDeadzone < 0.0f) {
            this.gamepadDeadzone = 0.15f;
        } else {
            this.gamepadDeadzone = Math.min(0.95f, gamepadDeadzone);
        }
    }

    /**
     * Returns dialogue textbox background opacity in {@code [0.0, 1.0]}.
     *
     * @return textbox opacity
     */
    public synchronized float getTextboxOpacity() {
        return textboxOpacity;
    }

    /**
     * Sets dialogue textbox background opacity in {@code [0.0, 1.0]}.
     *
     * @param textboxOpacity opacity in {@code [0.0, 1.0]}
     */
    public synchronized void setTextboxOpacity(float textboxOpacity) {
        this.textboxOpacity = clampVolume(textboxOpacity, 0.88f);
    }

    /**
     * Returns whether reduced-motion accessibility mode is enabled.
     *
     * @return {@code true} when reduced motion is active
     */
    public synchronized boolean isReduceMotion() {
        return reduceMotion;
    }

    /**
     * Sets reduced-motion accessibility mode.
     *
     * @param reduceMotion {@code true} to suppress flashes/shakes and snap UI hovers
     */
    public synchronized void setReduceMotion(boolean reduceMotion) {
        this.reduceMotion = reduceMotion;
    }

    /**
     * Returns edge HUD safezone margin in pixels {@code [0..64]}.
     *
     * @return safezone margin in pixels
     */
    public synchronized int getSafezoneMarginPx() {
        return safezoneMarginPx;
    }

    /**
     * Sets edge HUD safezone margin in pixels clamped to {@code [0..64]}.
     *
     * @param safezoneMarginPx safezone margin in pixels
     */
    public synchronized void setSafezoneMarginPx(int safezoneMarginPx) {
        this.safezoneMarginPx = Math.clamp(safezoneMarginPx, 0, 64);
    }

    /**
     * Returns accessibility cursor style ({@code DEFAULT}, {@code LARGE_YELLOW}, {@code INVERTED}).
     *
     * @return cursor style identifier
     */
    public synchronized String getAccessibilityCursorStyle() {
        return accessibilityCursorStyle;
    }

    /**
     * Sets accessibility cursor style ({@code DEFAULT}, {@code LARGE_YELLOW}, {@code INVERTED}).
     *
     * @param accessibilityCursorStyle cursor style identifier
     */
    public synchronized void setAccessibilityCursorStyle(String accessibilityCursorStyle) {
        String upper = accessibilityCursorStyle == null ? "" : accessibilityCursorStyle.trim().toUpperCase(Locale.ROOT);
        this.accessibilityCursorStyle = ("LARGE_YELLOW".equals(upper) || "INVERTED".equals(upper))
                ? upper : "DEFAULT";
    }

    /**
     * Returns dialogue text backdrop mode ({@code BOX}, {@code DARK_STRIP}, {@code NONE}).
     *
     * @return text backdrop mode
     */
    public synchronized String getTextBackdropMode() {
        return textBackdropMode;
    }

    /**
     * Sets dialogue text backdrop mode ({@code BOX}, {@code DARK_STRIP}, {@code NONE}).
     *
     * @param textBackdropMode backdrop mode name
     */
    public synchronized void setTextBackdropMode(String textBackdropMode) {
        String upper = textBackdropMode == null ? "" : textBackdropMode.trim().toUpperCase(Locale.ROOT);
        this.textBackdropMode = ("DARK_STRIP".equals(upper) || "NONE".equals(upper))
                ? upper : "BOX";
    }

    /**
     * Returns whether ultrawide safe-letterbox expansion is enabled.
     *
     * @return {@code true} when 21:9 expansion is permitted
     */
    public synchronized boolean isSafeLetterboxUltrawide() {
        return safeLetterboxUltrawide;
    }

    /**
     * Sets ultrawide safe-letterbox expansion toggle.
     *
     * @param safeLetterboxUltrawide expansion flag
     */
    public synchronized void setSafeLetterboxUltrawide(boolean safeLetterboxUltrawide) {
        this.safeLetterboxUltrawide = safeLetterboxUltrawide;
    }

    /**
     * Returns whether 1..9 numeric hotkeys are enabled in choice menus.
     *
     * @return {@code true} when numeric choice hotkeys are enabled
     */
    public synchronized boolean isChoiceNumberHotkeysEnabled() {
        return choiceNumberHotkeysEnabled;
    }

    /**
     * Sets whether 1..9 numeric hotkeys are enabled in choice menus.
     *
     * @param choiceNumberHotkeysEnabled numeric hotkeys toggle
     */
    public synchronized void setChoiceNumberHotkeysEnabled(boolean choiceNumberHotkeysEnabled) {
        this.choiceNumberHotkeysEnabled = choiceNumberHotkeysEnabled;
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
                && Float.compare(gamepadDeadzone, other.gamepadDeadzone) == 0
                && Float.compare(textboxOpacity, other.textboxOpacity) == 0
                && reduceMotion == other.reduceMotion
                && safezoneMarginPx == other.safezoneMarginPx
                && safeLetterboxUltrawide == other.safeLetterboxUltrawide
                && choiceNumberHotkeysEnabled == other.choiceNumberHotkeysEnabled
                && Objects.equals(renderMode, other.renderMode)
                && Objects.equals(language, other.language)
                && Objects.equals(dialogueMode, other.dialogueMode)
                && Objects.equals(accessibilityCursorStyle, other.accessibilityCursorStyle)
                && Objects.equals(textBackdropMode, other.textBackdropMode);
    }

    @Override
    public synchronized int hashCode() {
        return Objects.hash(
                masterVolume, musicVolume, soundVolume, voiceVolume, ambientVolume,
                textSpeedCps, autoForwardDelaySeconds, skipUnread, skipAfterChoices,
                fullscreen, renderMode, language, dialogueMode,
                selfVoicing, highContrastDyslexiaFont, uiTextScale, gamepadDeadzone,
                textboxOpacity, reduceMotion, safezoneMarginPx,
                accessibilityCursorStyle, textBackdropMode,
                safeLetterboxUltrawide, choiceNumberHotkeysEnabled
        );
    }
}
