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
    private float uiVolume = 0.9f;
    private boolean muteOnFocusLoss = false;
    private boolean stopVoiceOnAdvance = true;
    private boolean monoAudio = false;
    private boolean accessibilityAudioCues = false;
    private boolean autosaveOnQuit = true;
    private double autoReadVoiceWaitSeconds = 0.75;
    private String saveSlotSortMode = "LAST_SAVED";
    private boolean invertCameraY = false;
    private float cameraSensitivity = 1.0f;
    private boolean scrollWheelRollbackEnabled = true;
    private String rightClickBehavior = "MENU";
    private boolean touchControlsEnabled = false;
    private boolean simplifyHoldInputs = false;
    private boolean toggleSkipSprintMode = false;
    private boolean hideCursorOnGamepad = true;
    private int minClickIntervalMs = 0;
    private boolean disableBackspaceTrigger = false;
    private float lineSpacing = 1.25f;
    private float letterSpacing = 0.0f;
    private boolean textCharFadeInEnabled = true;
    private boolean sharedReadLinesAcrossLanguages = true;
    private float displayGamma = 1.0f;

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
        uiVolume = clampVolume(uiVolume, 0.9f);
        if (!Double.isFinite(autoReadVoiceWaitSeconds) || autoReadVoiceWaitSeconds < 0.0) {
            autoReadVoiceWaitSeconds = 0.75;
        } else if (autoReadVoiceWaitSeconds > 10.0) {
            autoReadVoiceWaitSeconds = 10.0;
        }
        if (saveSlotSortMode == null || saveSlotSortMode.isBlank()) {
            saveSlotSortMode = "LAST_SAVED";
        } else {
            String upper = saveSlotSortMode.trim().toUpperCase(Locale.ROOT);
            saveSlotSortMode = "SLOT_NUMBER".equals(upper) ? "SLOT_NUMBER" : "LAST_SAVED";
        }
        if (!Float.isFinite(cameraSensitivity) || cameraSensitivity < 0.2f) {
            cameraSensitivity = Float.isFinite(cameraSensitivity) ? 0.2f : 1.0f;
        } else if (cameraSensitivity > 3.0f) {
            cameraSensitivity = 3.0f;
        }
        if (rightClickBehavior == null || rightClickBehavior.isBlank()) {
            rightClickBehavior = "MENU";
        } else {
            String upper = rightClickBehavior.trim().toUpperCase(Locale.ROOT);
            rightClickBehavior = "ROLLBACK".equals(upper) ? "ROLLBACK" : "MENU";
        }
        minClickIntervalMs = Math.clamp(minClickIntervalMs, 0, 80);
        if (!Float.isFinite(lineSpacing) || lineSpacing < 1.0f) {
            lineSpacing = Float.isFinite(lineSpacing) ? 1.0f : 1.25f;
        } else if (lineSpacing > 2.2f) {
            lineSpacing = 2.2f;
        }
        if (!Float.isFinite(letterSpacing) || letterSpacing < 0.0f) {
            letterSpacing = 0.0f;
        } else if (letterSpacing > 8.0f) {
            letterSpacing = 8.0f;
        }
    }

    /**
     * Resets all user preferences back to clean engine factory defaults ({@code #0552}).
     */
    public synchronized void resetToDefaults() {
        PreferencesState defaults = new PreferencesState();
        masterVolume = defaults.masterVolume;
        musicVolume = defaults.musicVolume;
        soundVolume = defaults.soundVolume;
        voiceVolume = defaults.voiceVolume;
        ambientVolume = defaults.ambientVolume;
        textSpeedCps = defaults.textSpeedCps;
        autoForwardDelaySeconds = defaults.autoForwardDelaySeconds;
        skipUnread = defaults.skipUnread;
        skipAfterChoices = defaults.skipAfterChoices;
        fullscreen = defaults.fullscreen;
        renderMode = defaults.renderMode;
        language = defaults.language;
        dialogueMode = defaults.dialogueMode;
        selfVoicing = defaults.selfVoicing;
        highContrastDyslexiaFont = defaults.highContrastDyslexiaFont;
        uiTextScale = defaults.uiTextScale;
        gamepadDeadzone = defaults.gamepadDeadzone;
        textboxOpacity = defaults.textboxOpacity;
        reduceMotion = defaults.reduceMotion;
        safezoneMarginPx = defaults.safezoneMarginPx;
        accessibilityCursorStyle = defaults.accessibilityCursorStyle;
        textBackdropMode = defaults.textBackdropMode;
        safeLetterboxUltrawide = defaults.safeLetterboxUltrawide;
        choiceNumberHotkeysEnabled = defaults.choiceNumberHotkeysEnabled;
        uiVolume = defaults.uiVolume;
        muteOnFocusLoss = defaults.muteOnFocusLoss;
        stopVoiceOnAdvance = defaults.stopVoiceOnAdvance;
        monoAudio = defaults.monoAudio;
        accessibilityAudioCues = defaults.accessibilityAudioCues;
        autosaveOnQuit = defaults.autosaveOnQuit;
        autoReadVoiceWaitSeconds = defaults.autoReadVoiceWaitSeconds;
        saveSlotSortMode = defaults.saveSlotSortMode;
        invertCameraY = defaults.invertCameraY;
        cameraSensitivity = defaults.cameraSensitivity;
        scrollWheelRollbackEnabled = defaults.scrollWheelRollbackEnabled;
        rightClickBehavior = defaults.rightClickBehavior;
        touchControlsEnabled = defaults.touchControlsEnabled;
        simplifyHoldInputs = defaults.simplifyHoldInputs;
        toggleSkipSprintMode = defaults.toggleSkipSprintMode;
        hideCursorOnGamepad = defaults.hideCursorOnGamepad;
        minClickIntervalMs = defaults.minClickIntervalMs;
        disableBackspaceTrigger = defaults.disableBackspaceTrigger;
        lineSpacing = defaults.lineSpacing;
        letterSpacing = defaults.letterSpacing;
        textCharFadeInEnabled = defaults.textCharFadeInEnabled;
        sharedReadLinesAcrossLanguages = defaults.sharedReadLinesAcrossLanguages;
    }

    /**
     * Resets all user preferences to defaults and atomically persists them to {@code savesDir}.
     *
     * @param savesDir saves directory where {@code preferences.json} is written
     * @throws IOException if writing the reset file fails
     */
    public synchronized void resetToDefaults(Path savesDir) throws IOException {
        resetToDefaults();
        save(savesDir);
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

    /**
     * Converts a normalized linear volume slider value {@code [0, 1]} to a perceptual gain curve
     * ({@code v^2}) for smooth low-volume control ({@code #0442}).
     *
     * @param linear linear slider value in {@code [0, 1]}
     * @return perceptual gain multiplier in {@code [0, 1]}
     */
    public static float toPerceptualGain(float linear) {
        float c = clampVolume(linear, 0.0f);
        return c * c;
    }

    /**
     * Returns UI sound effect volume in {@code [0, 1]}.
     *
     * @return UI SFX volume
     */
    public synchronized float getUiVolume() {
        return uiVolume;
    }

    /**
     * Sets UI sound effect volume in {@code [0, 1]}.
     *
     * @param uiVolume UI SFX volume
     */
    public synchronized void setUiVolume(float uiVolume) {
        this.uiVolume = clampVolume(uiVolume, 0.9f);
    }

    /**
     * Returns whether audio mutes smoothly when the window loses focus.
     *
     * @return {@code true} if focus-loss mute is enabled
     */
    public synchronized boolean isMuteOnFocusLoss() {
        return muteOnFocusLoss;
    }

    /**
     * Enables or disables smooth muting when the window loses focus.
     *
     * @param muteOnFocusLoss focus-loss mute flag
     */
    public synchronized void setMuteOnFocusLoss(boolean muteOnFocusLoss) {
        this.muteOnFocusLoss = muteOnFocusLoss;
    }

    /**
     * Returns whether active voice clips stop when advancing dialogue.
     *
     * @return {@code true} to interrupt active voice on advance
     */
    public synchronized boolean isStopVoiceOnAdvance() {
        return stopVoiceOnAdvance;
    }

    /**
     * Configures whether active voice clips stop when advancing dialogue.
     *
     * @param stopVoiceOnAdvance interrupt flag
     */
    public synchronized void setStopVoiceOnAdvance(boolean stopVoiceOnAdvance) {
        this.stopVoiceOnAdvance = stopVoiceOnAdvance;
    }

    /**
     * Returns whether stereo audio is folded down to mono for accessibility.
     *
     * @return {@code true} when mono downmix is active
     */
    public synchronized boolean isMonoAudio() {
        return monoAudio;
    }

    /**
     * Enables or disables accessibility mono audio downmix.
     *
     * @param monoAudio mono downmix flag
     */
    public synchronized void setMonoAudio(boolean monoAudio) {
        this.monoAudio = monoAudio;
    }

    /**
     * Returns whether accessibility screen-reader navigation sound cues are enabled.
     *
     * @return {@code true} when accessibility audio cues are enabled
     */
    public synchronized boolean isAccessibilityAudioCues() {
        return accessibilityAudioCues;
    }

    /**
     * Enables or disables accessibility screen-reader navigation sound cues.
     *
     * @param accessibilityAudioCues accessibility audio cue flag
     */
    public synchronized void setAccessibilityAudioCues(boolean accessibilityAudioCues) {
        this.accessibilityAudioCues = accessibilityAudioCues;
    }

    /**
     * Returns whether automatic saving on quit / close is enabled ({@code #0526}).
     *
     * @return {@code true} if autosave-on-quit is enabled
     */
    public synchronized boolean isAutosaveOnQuit() {
        return autosaveOnQuit;
    }

    /**
     * Enables or disables automatic saving on normal game exit.
     *
     * @param autosaveOnQuit autosave-on-quit toggle
     */
    public synchronized void setAutosaveOnQuit(boolean autosaveOnQuit) {
        this.autosaveOnQuit = autosaveOnQuit;
    }

    /**
     * Returns extra auto-read wait time in seconds after a voice clip finishes ({@code #0553}).
     *
     * @return post-voice wait seconds in {@code [0, 10]}
     */
    public synchronized double getAutoReadVoiceWaitSeconds() {
        return autoReadVoiceWaitSeconds;
    }

    /**
     * Sets extra auto-read wait time in seconds after a voice clip finishes.
     *
     * @param autoReadVoiceWaitSeconds wait duration clamped to {@code [0, 10]}
     */
    public synchronized void setAutoReadVoiceWaitSeconds(double autoReadVoiceWaitSeconds) {
        if (!Double.isFinite(autoReadVoiceWaitSeconds) || autoReadVoiceWaitSeconds < 0.0) {
            this.autoReadVoiceWaitSeconds = 0.0;
        } else {
            this.autoReadVoiceWaitSeconds = Math.min(10.0, autoReadVoiceWaitSeconds);
        }
    }

    /**
     * Returns save-slot sorting mode ({@code "LAST_SAVED"} or {@code "SLOT_NUMBER"}) ({@code #0580}).
     *
     * @return save-slot sort mode
     */
    public synchronized String getSaveSlotSortMode() {
        return saveSlotSortMode;
    }

    /**
     * Sets save-slot sorting mode ({@code "LAST_SAVED"} or {@code "SLOT_NUMBER"}).
     *
     * @param saveSlotSortMode sort mode identifier
     */
    public synchronized void setSaveSlotSortMode(String saveSlotSortMode) {
        if (saveSlotSortMode != null && "SLOT_NUMBER".equalsIgnoreCase(saveSlotSortMode.trim())) {
            this.saveSlotSortMode = "SLOT_NUMBER";
        } else {
            this.saveSlotSortMode = "LAST_SAVED";
        }
    }

    /**
     * Returns whether camera Y axis is inverted for 3D / pan controls ({@code #0622}).
     *
     * @return {@code true} if Y axis is inverted
     */
    public synchronized boolean isInvertCameraY() {
        return invertCameraY;
    }

    /**
     * Sets whether camera Y axis is inverted for 3D / pan controls.
     *
     * @param invertCameraY invert Y flag
     */
    public synchronized void setInvertCameraY(boolean invertCameraY) {
        this.invertCameraY = invertCameraY;
    }

    /**
     * Returns camera mouse/stick sensitivity multiplier in {@code [0.2, 3.0]} ({@code #0623}).
     *
     * @return sensitivity multiplier
     */
    public synchronized float getCameraSensitivity() {
        return cameraSensitivity;
    }

    /**
     * Sets camera mouse/stick sensitivity multiplier clamped to {@code [0.2, 3.0]}.
     *
     * @param cameraSensitivity sensitivity multiplier
     */
    public synchronized void setCameraSensitivity(float cameraSensitivity) {
        if (!Float.isFinite(cameraSensitivity)) {
            this.cameraSensitivity = 1.0f;
        } else {
            this.cameraSensitivity = Math.max(0.2f, Math.min(3.0f, cameraSensitivity));
        }
    }

    /**
     * Returns whether scroll-wheel up triggers rollback ({@code #0602}).
     *
     * @return {@code true} when mouse wheel rollback is enabled
     */
    public synchronized boolean isScrollWheelRollbackEnabled() {
        return scrollWheelRollbackEnabled;
    }

    /**
     * Enables or disables scroll-wheel rollback/roll-forward.
     *
     * @param scrollWheelRollbackEnabled toggle flag
     */
    public synchronized void setScrollWheelRollbackEnabled(boolean scrollWheelRollbackEnabled) {
        this.scrollWheelRollbackEnabled = scrollWheelRollbackEnabled;
    }

    /**
     * Returns right-click behavior ({@code "MENU"} or {@code "ROLLBACK"}) ({@code #0638}).
     *
     * @return right-click mode
     */
    public synchronized String getRightClickBehavior() {
        return rightClickBehavior;
    }

    /**
     * Sets right-click behavior ({@code "MENU"} or {@code "ROLLBACK"}).
     *
     * @param rightClickBehavior mode string
     */
    public synchronized void setRightClickBehavior(String rightClickBehavior) {
        if (rightClickBehavior != null && "ROLLBACK".equalsIgnoreCase(rightClickBehavior.trim())) {
            this.rightClickBehavior = "ROLLBACK";
        } else {
            this.rightClickBehavior = "MENU";
        }
    }

    /**
     * Returns whether optional on-screen touch controls overlay is enabled ({@code #0607}).
     *
     * @return {@code true} if enabled
     */
    public synchronized boolean isTouchControlsEnabled() {
        return touchControlsEnabled;
    }

    /**
     * Enables or disables optional on-screen touch controls overlay.
     *
     * @param touchControlsEnabled touch controls flag
     */
    public synchronized void setTouchControlsEnabled(boolean touchControlsEnabled) {
        this.touchControlsEnabled = touchControlsEnabled;
    }

    /**
     * Returns whether mash inputs are replaced with accessibility hold actions ({@code #0609}).
     *
     * @return {@code true} if simplified hold mode is enabled
     */
    public synchronized boolean isSimplifyHoldInputs() {
        return simplifyHoldInputs;
    }

    /**
     * Enables or disables simplified accessibility hold mode for timed mash prompts.
     *
     * @param simplifyHoldInputs hold accessibility flag
     */
    public synchronized void setSimplifyHoldInputs(boolean simplifyHoldInputs) {
        this.simplifyHoldInputs = simplifyHoldInputs;
    }

    /**
     * Returns whether skip/sprint uses toggle mode instead of holding keys ({@code #0627}).
     *
     * @return {@code true} for toggle mode
     */
    public synchronized boolean isToggleSkipSprintMode() {
        return toggleSkipSprintMode;
    }

    /**
     * Sets toggle vs hold mode for skip and sprint inputs.
     *
     * @param toggleSkipSprintMode toggle flag
     */
    public synchronized void setToggleSkipSprintMode(boolean toggleSkipSprintMode) {
        this.toggleSkipSprintMode = toggleSkipSprintMode;
    }

    /**
     * Returns whether the cursor hides automatically when using gamepad navigation ({@code #0614}).
     *
     * @return {@code true} to hide cursor on gamepad inactivity
     */
    public synchronized boolean isHideCursorOnGamepad() {
        return hideCursorOnGamepad;
    }

    /**
     * Enables or disables cursor hiding after mouse inactivity on gamepad input.
     *
     * @param hideCursorOnGamepad hide flag
     */
    public synchronized void setHideCursorOnGamepad(boolean hideCursorOnGamepad) {
        this.hideCursorOnGamepad = hideCursorOnGamepad;
    }

    /**
     * Returns mouse switch debounce interval in milliseconds {@code [0, 80]} ({@code #0628}).
     *
     * @return minimum interval between clicks in milliseconds
     */
    public synchronized int getMinClickIntervalMs() {
        return minClickIntervalMs;
    }

    /**
     * Sets mouse switch debounce interval clamped to {@code [0, 80]} ms.
     *
     * @param minClickIntervalMs minimum click interval ms
     */
    public synchronized void setMinClickIntervalMs(int minClickIntervalMs) {
        this.minClickIntervalMs = Math.clamp(minClickIntervalMs, 0, 80);
    }

    /**
     * Returns whether Backspace rollback trigger is disabled to prevent accidental triggers ({@code #0676}).
     *
     * @return {@code true} if Backspace trigger is disabled
     */
    public synchronized boolean isDisableBackspaceTrigger() {
        return disableBackspaceTrigger;
    }

    /**
     * Enables or disables disabling Backspace rollback trigger.
     *
     * @param disableBackspaceTrigger disable flag
     */
    public synchronized void setDisableBackspaceTrigger(boolean disableBackspaceTrigger) {
        this.disableBackspaceTrigger = disableBackspaceTrigger;
    }

    /**
     * Returns typography line-height multiplier in {@code [1.0, 2.2]} ({@code #0693}).
     *
     * @return line spacing factor
     */
    public synchronized float getLineSpacing() {
        return lineSpacing;
    }

    /**
     * Sets typography line-height multiplier clamped to {@code [1.0, 2.2]}.
     *
     * @param lineSpacing line spacing factor
     */
    public synchronized void setLineSpacing(float lineSpacing) {
        if (!Float.isFinite(lineSpacing)) {
            this.lineSpacing = 1.25f;
        } else {
            this.lineSpacing = Math.max(1.0f, Math.min(2.2f, lineSpacing));
        }
    }

    /**
     * Returns extra per-character horizontal tracking in pixels {@code [0.0, 8.0]} ({@code #0693}).
     *
     * @return letter spacing in px
     */
    public synchronized float getLetterSpacing() {
        return letterSpacing;
    }

    /**
     * Sets extra per-character horizontal tracking clamped to {@code [0.0, 8.0]} pixels.
     *
     * @param letterSpacing letter spacing in px
     */
    public synchronized void setLetterSpacing(float letterSpacing) {
        if (!Float.isFinite(letterSpacing)) {
            this.letterSpacing = 0.0f;
        } else {
            this.letterSpacing = Math.max(0.0f, Math.min(8.0f, letterSpacing));
        }
    }

    /**
     * Returns whether smooth 80 ms per-character typewriter fade-in is enabled ({@code #0744}).
     *
     * @return {@code true} if character fade-in is enabled
     */
    public synchronized boolean isTextCharFadeInEnabled() {
        return textCharFadeInEnabled;
    }

    /**
     * Enables or disables smooth per-character typewriter fade-in.
     *
     * @param textCharFadeInEnabled fade-in toggle
     */
    public synchronized void setTextCharFadeInEnabled(boolean textCharFadeInEnabled) {
        this.textCharFadeInEnabled = textCharFadeInEnabled;
    }

    /**
     * Returns whether seen dialogue line history is shared across language switches ({@code #0760}).
     *
     * @return {@code true} when shared via stable {@code lineId}
     */
    public synchronized boolean isSharedReadLinesAcrossLanguages() {
        return sharedReadLinesAcrossLanguages;
    }

    /**
     * Sets whether seen dialogue history is shared across languages or separated per language.
     *
     * @param sharedReadLinesAcrossLanguages share toggle
     */
    public synchronized void setSharedReadLinesAcrossLanguages(boolean sharedReadLinesAcrossLanguages) {
        this.sharedReadLinesAcrossLanguages = sharedReadLinesAcrossLanguages;
    }

    /**
     * Returns calibrated display gamma in {@code [0.5, 2.2]} ({@code #1007}).
     *
     * @return calibrated display gamma
     */
    public synchronized float getDisplayGamma() {
        return displayGamma;
    }

    /**
     * Sets calibrated display gamma clamped to {@code [0.5, 2.2]} ({@code #1007}).
     *
     * @param displayGamma display gamma value
     */
    public synchronized void setDisplayGamma(float displayGamma) {
        if (!Float.isFinite(displayGamma)) {
            this.displayGamma = 1.0f;
        } else {
            this.displayGamma = Math.max(0.5f, Math.min(2.2f, displayGamma));
        }
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
                && Float.compare(uiVolume, other.uiVolume) == 0
                && muteOnFocusLoss == other.muteOnFocusLoss
                && stopVoiceOnAdvance == other.stopVoiceOnAdvance
                && monoAudio == other.monoAudio
                && accessibilityAudioCues == other.accessibilityAudioCues
                && autosaveOnQuit == other.autosaveOnQuit
                && Double.compare(autoReadVoiceWaitSeconds, other.autoReadVoiceWaitSeconds) == 0
                && invertCameraY == other.invertCameraY
                && Float.compare(cameraSensitivity, other.cameraSensitivity) == 0
                && scrollWheelRollbackEnabled == other.scrollWheelRollbackEnabled
                && touchControlsEnabled == other.touchControlsEnabled
                && simplifyHoldInputs == other.simplifyHoldInputs
                && toggleSkipSprintMode == other.toggleSkipSprintMode
                && hideCursorOnGamepad == other.hideCursorOnGamepad
                && minClickIntervalMs == other.minClickIntervalMs
                && disableBackspaceTrigger == other.disableBackspaceTrigger
                && Float.compare(lineSpacing, other.lineSpacing) == 0
                && Float.compare(letterSpacing, other.letterSpacing) == 0
                && textCharFadeInEnabled == other.textCharFadeInEnabled
                && sharedReadLinesAcrossLanguages == other.sharedReadLinesAcrossLanguages
                && Float.compare(displayGamma, other.displayGamma) == 0
                && Objects.equals(saveSlotSortMode, other.saveSlotSortMode)
                && Objects.equals(rightClickBehavior, other.rightClickBehavior)
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
                safeLetterboxUltrawide, choiceNumberHotkeysEnabled,
                uiVolume, muteOnFocusLoss, stopVoiceOnAdvance, monoAudio, accessibilityAudioCues,
                autosaveOnQuit, autoReadVoiceWaitSeconds, saveSlotSortMode,
                invertCameraY, cameraSensitivity, scrollWheelRollbackEnabled, rightClickBehavior,
                touchControlsEnabled, simplifyHoldInputs, toggleSkipSprintMode,
                hideCursorOnGamepad, minClickIntervalMs, disableBackspaceTrigger,
                lineSpacing, letterSpacing, textCharFadeInEnabled, sharedReadLinesAcrossLanguages,
                displayGamma
        );
    }
}
