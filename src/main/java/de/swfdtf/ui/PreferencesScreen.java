package de.swfdtf.ui;

import de.swfdtf.audio.AudioEngine;
import de.swfdtf.audio.TextToSpeechService;
import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.state.LocalizationManager;
import de.swfdtf.state.PreferencesState;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Built-in Dark-Slate Preferences screen ({@code Z = 400}, modal) exposing live audio sliders,
 * text speed &amp; auto-advance sliders, display/skip toggles, Dialogue Mode &amp; Render Mode controls,
 * live {@link LocalizationManager} language switching, accessibility controls, and disk persistence.
 */
public class PreferencesScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "preferences";

    private static final List<String> DIALOGUE_MODES = List.of("ADV", "NVL", "BUBBLE");

    private final ScriptSession session;
    private final ScreenManager screenManager;
    private FontManager fontManager;

    private final TextWidget headerTitle;
    private final TextButton saveAndCloseButton;
    private final Slider masterSlider;
    private final Slider musicSlider;
    private final Slider sfxSlider;
    private final Slider voiceSlider;
    private final Slider ambientSlider;
    private final Slider textSpeedSlider;
    private final Slider autoDelaySlider;
    private final Slider uiScaleSlider;

    private final TextButton fullscreenToggle;
    private final TextButton skipUnreadToggle;
    private final TextButton skipAfterChoicesToggle;
    private final TextButton dialogueModeButton;
    private final TextButton languageButton;
    private final TextButton renderModeButton;
    private final TextButton selfVoicingToggle;
    private final TextButton dyslexiaFontToggle;

    /**
     * Creates a {@code PreferencesScreen} bound to {@code session} and {@code screenManager}.
     *
     * @param session       active script session
     * @param screenManager UI screen manager
     */
    public PreferencesScreen(ScriptSession session, ScreenManager screenManager) {
        super(SCREEN_NAME, SceneLayer.Z_MODAL, true);
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.screenManager = screenManager;
        this.fontManager = session.getEngineApi().getService(FontManager.class);

        PreferencesState prefs = session.getPreferencesState();

        Frame backdrop = new Frame();
        UiMediaSupport.styleBackdrop(backdrop);
        backdrop.setPadding(120.0f, 60.0f);

        Frame panel = new Frame();
        UiMediaSupport.styleModalCard(panel);

        VBox mainBox = new VBox();
        mainBox.setSpacing(18.0f);
        mainBox.setFillWidth(true);

        HBox header = new HBox();
        header.setSpacing(20.0f);
        header.setAlignCenterY(true);
        this.headerTitle = UiMediaSupport.createTitleText("Preferences", 34.0f);
        this.saveAndCloseButton = UiMediaSupport.createActionButton("Save & Close", 180.0f, 40.0f, this::saveAndClose);
        header.addChild(headerTitle);
        header.addChild(saveAndCloseButton);

        Grid columns = new Grid(2, 1);
        columns.setSpacing(32.0f, 16.0f);

        VBox leftCol = new VBox();
        leftCol.setSpacing(12.0f);
        leftCol.setFillWidth(true);

        this.masterSlider = addSliderRow(leftCol, "Master Volume", prefs.getMasterVolume(), 0.0f, 1.0f, val -> {
            prefs.setMasterVolume(val);
            syncAudioVolumes();
        });
        this.musicSlider = addSliderRow(leftCol, "Music Volume", prefs.getMusicVolume(), 0.0f, 1.0f, val -> {
            prefs.setMusicVolume(val);
            syncAudioVolumes();
        });
        this.sfxSlider = addSliderRow(leftCol, "SFX Volume", prefs.getSoundVolume(), 0.0f, 1.0f, val -> {
            prefs.setSoundVolume(val);
            syncAudioVolumes();
        });
        this.voiceSlider = addSliderRow(leftCol, "Voice Volume", prefs.getVoiceVolume(), 0.0f, 1.0f, val -> {
            prefs.setVoiceVolume(val);
            syncAudioVolumes();
        });
        this.ambientSlider = addSliderRow(leftCol, "Ambient Volume", prefs.getAmbientVolume(), 0.0f, 1.0f, val -> {
            prefs.setAmbientVolume(val);
            syncAudioVolumes();
        });
        this.textSpeedSlider = addSliderRow(leftCol, "Text Speed (CPS)", prefs.getTextSpeedCps(), 0.0f, 120.0f,
                prefs::setTextSpeedCps);
        this.autoDelaySlider = addSliderRow(leftCol, "Auto-Advance Delay (s)",
                prefs.getAutoForwardDelaySeconds(), 0.5f, 10.0f, prefs::setAutoForwardDelaySeconds);

        VBox rightCol = new VBox();
        rightCol.setSpacing(12.0f);
        rightCol.setFillWidth(true);

        this.fullscreenToggle = addToggleRow(rightCol, "Fullscreen", prefs.isFullscreen(), () -> {
            prefs.setFullscreen(!prefs.isFullscreen());
            refreshLabels();
        });
        this.skipUnreadToggle = addToggleRow(rightCol, "Skip Unread", prefs.isSkipUnread(), () -> {
            prefs.setSkipUnread(!prefs.isSkipUnread());
            refreshLabels();
        });
        this.skipAfterChoicesToggle = addToggleRow(rightCol, "Skip After Choices", prefs.isSkipAfterChoices(), () -> {
            prefs.setSkipAfterChoices(!prefs.isSkipAfterChoices());
            refreshLabels();
        });
        this.dialogueModeButton = addSelectorRow(
                rightCol, "Dialogue Mode", prefs.getDialogueMode(), this::cycleDialogueMode);
        this.languageButton = addSelectorRow(rightCol, "Language", prefs.getLanguage(), this::cycleLanguage);
        this.renderModeButton = addSelectorRow(rightCol, "Render Mode", prefs.getRenderMode(), this::toggleRenderMode);
        this.selfVoicingToggle = addToggleRow(rightCol, "Self-Voicing [V]", prefs.isSelfVoicing(), () -> {
            boolean next = !prefs.isSelfVoicing();
            prefs.setSelfVoicing(next);
            TextToSpeechService tts = UiMediaSupport.findTtsService(session);
            if (tts != null) {
                tts.setEnabled(next);
            }
            refreshLabels();
        });
        this.dyslexiaFontToggle = addToggleRow(
                rightCol, "High-Contrast Dyslexia Font", prefs.isHighContrastDyslexiaFont(), () -> {
                    boolean next = !prefs.isHighContrastDyslexiaFont();
                    prefs.setHighContrastDyslexiaFont(next);
                    if (fontManager != null) {
                        fontManager.setDyslexiaFriendlyMode(next);
                    }
                    refreshLabels();
                });
        this.uiScaleSlider = addSliderRow(rightCol, "UI Text Scale", prefs.getUiTextScale(), 0.75f, 1.75f, val -> {
            prefs.setUiTextScale(val);
            if (fontManager != null) {
                fontManager.setGlobalTextScale(val);
            }
        });

        columns.addChild(leftCol);
        columns.addChild(rightCol);
        mainBox.addChild(header);
        mainBox.addChild(columns);
        panel.addChild(mainBox);
        backdrop.addChild(panel);
        setRoot(backdrop);

        addKeyBinding(GLFW.GLFW_KEY_ESCAPE, this::saveAndClose);
        session.getLocalizationManager().addLanguageChangeListener(ignored -> refreshLabels());
        refreshLabels();
    }

    private Slider addSliderRow(
            VBox container,
            String labelText,
            float initial,
            float min,
            float max,
            java.util.function.Consumer<Float> onChange
    ) {
        HBox row = new HBox();
        row.setSpacing(14.0f);
        row.setAlignCenterY(true);
        TextWidget label = UiMediaSupport.createTitleText(labelText, 19.0f);
        label.setPreferredSize(240.0f, 34.0f);
        Slider slider = new Slider(initial, min, max, onChange);
        slider.setPreferredSize(420.0f, 34.0f);
        row.addChild(label);
        row.addChild(slider);
        container.addChild(row);
        return slider;
    }

    private TextButton addToggleRow(VBox container, String labelText, boolean initial, Runnable action) {
        HBox row = new HBox();
        row.setSpacing(14.0f);
        row.setAlignCenterY(true);
        TextWidget label = UiMediaSupport.createTitleText(labelText, 19.0f);
        label.setPreferredSize(320.0f, 38.0f);
        TextButton btn = UiMediaSupport.createActionButton(initial ? "ON" : "OFF", 160.0f, 38.0f, action);
        btn.setSelected(initial);
        row.addChild(label);
        row.addChild(btn);
        container.addChild(row);
        return btn;
    }

    private TextButton addSelectorRow(VBox container, String labelText, String initialValue, Runnable action) {
        HBox row = new HBox();
        row.setSpacing(14.0f);
        row.setAlignCenterY(true);
        TextWidget label = UiMediaSupport.createTitleText(labelText, 19.0f);
        label.setPreferredSize(320.0f, 38.0f);
        TextButton btn = UiMediaSupport.createActionButton(initialValue, 200.0f, 38.0f, action);
        row.addChild(label);
        row.addChild(btn);
        container.addChild(row);
        return btn;
    }

    @Override
    public void onShow() {
        refreshFromPreferences();
    }

    /**
     * Synchronizes all sliders and toggle labels from {@link PreferencesState}.
     */
    public void refreshFromPreferences() {
        PreferencesState prefs = session.getPreferencesState();
        masterSlider.setValue(prefs.getMasterVolume());
        musicSlider.setValue(prefs.getMusicVolume());
        sfxSlider.setValue(prefs.getSoundVolume());
        voiceSlider.setValue(prefs.getVoiceVolume());
        ambientSlider.setValue(prefs.getAmbientVolume());
        textSpeedSlider.setValue(prefs.getTextSpeedCps());
        autoDelaySlider.setValue(prefs.getAutoForwardDelaySeconds());
        uiScaleSlider.setValue(prefs.getUiTextScale());
        refreshLabels();
    }

    private void refreshLabels() {
        LocalizationManager loc = session.getLocalizationManager();
        headerTitle.setText(UiTranslations.resolve(loc, "ui.preferences", "Preferences"));
        saveAndCloseButton.setText(UiTranslations.resolve(loc, "ui.save_close", "Save & Close"));

        PreferencesState prefs = session.getPreferencesState();
        fullscreenToggle.setText(prefs.isFullscreen() ? "ON" : "OFF");
        fullscreenToggle.setSelected(prefs.isFullscreen());

        skipUnreadToggle.setText(prefs.isSkipUnread() ? "ON" : "OFF");
        skipUnreadToggle.setSelected(prefs.isSkipUnread());

        skipAfterChoicesToggle.setText(prefs.isSkipAfterChoices() ? "ON" : "OFF");
        skipAfterChoicesToggle.setSelected(prefs.isSkipAfterChoices());

        dialogueModeButton.setText(prefs.getDialogueMode());
        languageButton.setText(prefs.getLanguage());
        renderModeButton.setText(prefs.getRenderMode());

        selfVoicingToggle.setText(prefs.isSelfVoicing() ? "ON" : "OFF");
        selfVoicingToggle.setSelected(prefs.isSelfVoicing());

        dyslexiaFontToggle.setText(prefs.isHighContrastDyslexiaFont() ? "ON" : "OFF");
        dyslexiaFontToggle.setSelected(prefs.isHighContrastDyslexiaFont());
    }

    /**
     * Cycles through {@code ADV}, {@code NVL}, and {@code BUBBLE} dialogue modes.
     */
    public void cycleDialogueMode() {
        PreferencesState prefs = session.getPreferencesState();
        int idx = DIALOGUE_MODES.indexOf(prefs.getDialogueMode());
        String next = DIALOGUE_MODES.get((idx + 1) % DIALOGUE_MODES.size());
        prefs.setDialogueMode(next);
        refreshLabels();
    }

    /**
     * Cycles through available languages in {@link LocalizationManager} and updates live.
     */
    public void cycleLanguage() {
        LocalizationManager loc = session.getLocalizationManager();
        PreferencesState prefs = session.getPreferencesState();
        List<String> languages = new ArrayList<>(loc.getAvailableLanguages());
        if (languages.isEmpty()) {
            return;
        }
        int idx = languages.indexOf(loc.getCurrentLanguage());
        String next = languages.get((idx + 1) % languages.size());
        loc.setLanguage(next);
        prefs.setLanguage(next);
        refreshLabels();
    }

    /**
     * Sets a specific translation language live on both {@link LocalizationManager} and {@link PreferencesState}.
     *
     * @param language target language code
     */
    public void selectLanguage(String language) {
        if (language == null || language.isBlank()) {
            return;
        }
        session.getLocalizationManager().setLanguage(language);
        session.getPreferencesState().setLanguage(language);
        refreshLabels();
    }

    /**
     * Toggles the configured render mode between {@code "2D"} and {@code "3D"}.
     */
    public void toggleRenderMode() {
        PreferencesState prefs = session.getPreferencesState();
        prefs.setRenderMode("3D".equals(prefs.getRenderMode()) ? "2D" : "3D");
        refreshLabels();
    }

    /**
     * Persists {@link PreferencesState} to disk in the session's saves directory.
     */
    public void savePreferences() {
        try {
            session.getPreferencesState().save(session.getSaveManager().getSavesDir());
        } catch (IOException ex) {
            session.getEngineApi().logError("Failed to save preferences: " + ex.getMessage(), ex);
        }
    }

    private void saveAndClose() {
        savePreferences();
        if (screenManager != null) {
            screenManager.hideScreen(SCREEN_NAME);
        } else {
            setVisible(false);
            onHide();
        }
    }

    private void syncAudioVolumes() {
        AudioEngine audio = UiMediaSupport.findAudioEngine(session);
        if (audio != null) {
            audio.syncVolumesFromPreferences();
        }
    }

    /**
     * Sets the fontManager value.
     *
     * @param fontManager the fontManager argument
     */
    public void setFontManager(FontManager fontManager) {
        this.fontManager = fontManager;
    }

    /**
     * Returns the masterSlider value.
     *
     * @return the resulting value
     */
    public Slider getMasterSlider() {
        return masterSlider;
    }

    /**
     * Returns the musicSlider value.
     *
     * @return the resulting value
     */
    public Slider getMusicSlider() {
        return musicSlider;
    }

    /**
     * Returns the sfxSlider value.
     *
     * @return the resulting value
     */
    public Slider getSfxSlider() {
        return sfxSlider;
    }

    /**
     * Returns the voiceSlider value.
     *
     * @return the resulting value
     */
    public Slider getVoiceSlider() {
        return voiceSlider;
    }

    /**
     * Returns the ambientSlider value.
     *
     * @return the resulting value
     */
    public Slider getAmbientSlider() {
        return ambientSlider;
    }

    /**
     * Returns the textSpeedSlider value.
     *
     * @return the resulting value
     */
    public Slider getTextSpeedSlider() {
        return textSpeedSlider;
    }

    /**
     * Returns the autoDelaySlider value.
     *
     * @return the resulting value
     */
    public Slider getAutoDelaySlider() {
        return autoDelaySlider;
    }

    /**
     * Returns the uiScaleSlider value.
     *
     * @return the resulting value
     */
    public Slider getUiScaleSlider() {
        return uiScaleSlider;
    }

    /**
     * Returns the fullscreenToggle value.
     *
     * @return the resulting value
     */
    public TextButton getFullscreenToggle() {
        return fullscreenToggle;
    }

    /**
     * Returns the skipUnreadToggle value.
     *
     * @return the resulting value
     */
    public TextButton getSkipUnreadToggle() {
        return skipUnreadToggle;
    }

    /**
     * Returns the skipAfterChoicesToggle value.
     *
     * @return the resulting value
     */
    public TextButton getSkipAfterChoicesToggle() {
        return skipAfterChoicesToggle;
    }

    /**
     * Returns the dialogueModeButton value.
     *
     * @return the resulting value
     */
    public TextButton getDialogueModeButton() {
        return dialogueModeButton;
    }

    /**
     * Returns the languageButton value.
     *
     * @return the resulting value
     */
    public TextButton getLanguageButton() {
        return languageButton;
    }

    /**
     * Returns the renderModeButton value.
     *
     * @return the resulting value
     */
    public TextButton getRenderModeButton() {
        return renderModeButton;
    }

    /**
     * Returns the selfVoicingToggle value.
     *
     * @return the resulting value
     */
    public TextButton getSelfVoicingToggle() {
        return selfVoicingToggle;
    }

    /**
     * Returns the dyslexiaFontToggle value.
     *
     * @return the resulting value
     */
    public TextButton getDyslexiaFontToggle() {
        return dyslexiaFontToggle;
    }
}
