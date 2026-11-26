package de.swfdtf.ui;

import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.parser.ScriptSession;

import java.io.IOException;
import java.util.Objects;

/**
 * Floating Dark-Slate QuickMenu HUD bar ({@code Z = 210}) providing one-click access to
 * Rollback, History, Skip, Auto, Save, Quick-Save, Quick-Load, and Preferences.
 */
public class QuickMenuScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "quick_menu";

    /** Z-order immediately above dialogue screens ({@code 210}). */
    public static final int Z_ORDER = SceneLayer.Z_SCREENS + 10;

    private final ScriptSession session;
    private final ScreenManager screenManager;
    private final HBox barBox;

    private final TextButton backButton;
    private final TextButton historyButton;
    private final TextButton skipButton;
    private final TextButton skipChoiceButton;
    private final TextButton autoButton;
    private final TextButton saveButton;
    private final TextButton quickSaveButton;
    private final TextButton quickLoadButton;
    private final TextButton prefsButton;

    /**
     * Creates a {@code QuickMenuScreen} bound to {@code session} and {@code screenManager}.
     *
     * @param session       active script session
     * @param screenManager UI screen manager
     */
    public QuickMenuScreen(ScriptSession session, ScreenManager screenManager) {
        super(SCREEN_NAME, Z_ORDER, false);
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.screenManager = screenManager;

        Fixed root = new Fixed();

        this.barBox = new HBox();
        this.barBox.setSpacing(10.0f);
        this.barBox.setAlignCenterY(true);
        this.barBox.setBackgroundColorHex(ThemePalette.PANEL_GLASS)
                .setBorderColorHex(ThemePalette.BORDER)
                .setBorderThickness(1.0f)
                .setCornerRadius(8.0f)
                .setPadding(12.0f, 6.0f);

        this.backButton = createHudButton("Back", () -> session.rollback());
        this.historyButton = createHudButton("History", () -> openScreen(HistoryLogScreen.SCREEN_NAME));
        this.skipButton = createHudButton("Skip", this::toggleSkip);
        this.skipChoiceButton = UiMediaSupport.createActionButton("Skip Choice", 118.0f, 32.0f, this::skipToChoice);
        this.autoButton = createHudButton("Auto", this::toggleAuto);
        this.saveButton = createHudButton("Save", this::openSaveScreen);
        this.quickSaveButton = createHudButton("Q.Save", this::quickSave);
        this.quickLoadButton = createHudButton("Q.Load", this::quickLoad);
        this.prefsButton = createHudButton("Prefs", () -> openScreen(PreferencesScreen.SCREEN_NAME));

        this.barBox.addChild(backButton);
        this.barBox.addChild(historyButton);
        this.barBox.addChild(skipButton);
        this.barBox.addChild(skipChoiceButton);
        this.barBox.addChild(autoButton);
        this.barBox.addChild(saveButton);
        this.barBox.addChild(quickSaveButton);
        this.barBox.addChild(quickLoadButton);
        this.barBox.addChild(prefsButton);

        root.addChildAt(barBox, 440.0f, 1026.0f, 1040.0f, 46.0f);
        setRoot(root);
    }

    private static TextButton createHudButton(String label, Runnable action) {
        return UiMediaSupport.createActionButton(label, 98.0f, 32.0f, action);
    }

    @Override
    public void update(double dt) {
        super.update(dt);
        if (screenManager != null
                && screenManager.getScreen(AdvDialogueScreen.SCREEN_NAME) instanceof AdvDialogueScreen adv) {
            skipButton.setSelected(adv.isSkipActive());
            skipChoiceButton.setSelected(adv.isSkipToChoiceActive());
            autoButton.setSelected(adv.isAutoActive());
        }
        backButton.setEnabled(session.getStateStore().canRollback());
    }

    private void skipToChoice() {
        if (screenManager != null
                && screenManager.getScreen(AdvDialogueScreen.SCREEN_NAME) instanceof AdvDialogueScreen adv) {
            adv.startSkipToNextChoice();
            skipChoiceButton.setSelected(adv.isSkipToChoiceActive());
        }
    }

    /**
     * Returns the Skip-to-Choice action button.
     *
     * @return skip-to-choice button
     */
    public TextButton getSkipChoiceButton() {
        return skipChoiceButton;
    }

    private void toggleSkip() {
        if (screenManager != null
                && screenManager.getScreen(AdvDialogueScreen.SCREEN_NAME) instanceof AdvDialogueScreen adv) {
            adv.toggleSkip();
            skipButton.setSelected(adv.isSkipActive());
        } else {
            skipButton.setSelected(!skipButton.isSelected());
        }
    }

    private void toggleAuto() {
        if (screenManager != null
                && screenManager.getScreen(AdvDialogueScreen.SCREEN_NAME) instanceof AdvDialogueScreen adv) {
            adv.toggleAuto();
            autoButton.setSelected(adv.isAutoActive());
        } else {
            autoButton.setSelected(!autoButton.isSelected());
        }
    }

    private void openSaveScreen() {
        if (screenManager == null) {
            return;
        }
        if (screenManager.getScreen(SaveLoadScreen.SCREEN_NAME) instanceof SaveLoadScreen saveLoad) {
            saveLoad.setMode(SaveLoadScreen.Mode.SAVE);
        }
        screenManager.showScreen(SaveLoadScreen.SCREEN_NAME);
    }

    private void openScreen(String name) {
        if (screenManager != null) {
            screenManager.showScreen(name);
        }
    }

    private void quickSave() {
        try {
            session.quickSave();
        } catch (IOException ex) {
            session.getEngineApi().logError("Quick save failed: " + ex.getMessage(), ex);
        }
    }

    private void quickLoad() {
        try {
            session.quickLoad();
        } catch (IOException ex) {
            session.getEngineApi().logError("Quick load failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Returns the barBox value.
     *
     * @return the resulting value
     */
    public HBox getBarBox() {
        return barBox;
    }

    /**
     * Returns the backButton value.
     *
     * @return the resulting value
     */
    public TextButton getBackButton() {
        return backButton;
    }

    /**
     * Returns the historyButton value.
     *
     * @return the resulting value
     */
    public TextButton getHistoryButton() {
        return historyButton;
    }

    /**
     * Returns the skipButton value.
     *
     * @return the resulting value
     */
    public TextButton getSkipButton() {
        return skipButton;
    }

    /**
     * Returns the autoButton value.
     *
     * @return the resulting value
     */
    public TextButton getAutoButton() {
        return autoButton;
    }

    /**
     * Returns the saveButton value.
     *
     * @return the resulting value
     */
    public TextButton getSaveButton() {
        return saveButton;
    }

    /**
     * Returns the quickSaveButton value.
     *
     * @return the resulting value
     */
    public TextButton getQuickSaveButton() {
        return quickSaveButton;
    }

    /**
     * Returns the quickLoadButton value.
     *
     * @return the resulting value
     */
    public TextButton getQuickLoadButton() {
        return quickLoadButton;
    }

    /**
     * Returns the prefsButton value.
     *
     * @return the resulting value
     */
    public TextButton getPrefsButton() {
        return prefsButton;
    }
}
