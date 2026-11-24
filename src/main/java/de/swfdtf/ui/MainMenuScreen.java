package de.swfdtf.ui;

import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.state.SaveManager;
import de.swfdtf.state.SaveSlotData;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Built-in Dark-Slate Main Menu screen ({@code Z = 400}, modal) providing game launch,
 * newest-save Continue, Load, Gallery, Music Room, Endings, Achievements, Preferences,
 * Controls, About, and Quit actions.
 */
public class MainMenuScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "main_menu";

    private final ScriptSession session;
    private final ScreenManager screenManager;
    private final TextWidget titleBanner;
    private final TextWidget subtitleBanner;
    private final TextButton startButton;
    private final TextButton continueButton;
    private final TextButton loadButton;
    private final TextButton galleryButton;
    private final TextButton musicRoomButton;
    private final TextButton endingsButton;
    private final TextButton achievementsButton;
    private final TextButton preferencesButton;
    private final TextButton controlsButton;
    private final TextButton aboutButton;
    private final TextButton quitButton;

    private String newestSaveSlotId;

    /**
     * Creates a {@code MainMenuScreen} bound to {@code session} and {@code screenManager}.
     *
     * @param session       active script session
     * @param screenManager screen manager for navigating sub-screens
     */
    public MainMenuScreen(ScriptSession session, ScreenManager screenManager) {
        super(SCREEN_NAME, SceneLayer.Z_MODAL, true);
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.screenManager = screenManager;

        Frame root = new Frame();
        root.setBackgroundColorHex(ThemePalette.CANVAS_BG);

        Fixed layout = new Fixed();

        Frame bannerCard = new Frame();
        UiMediaSupport.styleModalCard(bannerCard);
        VBox bannerBox = new VBox();
        bannerBox.setSpacing(10.0f);

        ProjectDescriptor desc = session.getEngineApi().getProjectDescriptor();
        String projectTitle = desc != null ? desc.getTitle() : "KAGAYAKI Visual Novel";
        String projectVersion = desc != null ? "v" + desc.getVersion() : "v1.0.0";

        this.titleBanner = UiMediaSupport.createTitleText(projectTitle, 52.0f);
        this.subtitleBanner = UiMediaSupport.createMutedText(projectVersion, 22.0f);
        bannerBox.addChild(titleBanner);
        bannerBox.addChild(subtitleBanner);
        bannerCard.addChild(bannerBox);

        Frame menuPanel = new Frame();
        UiMediaSupport.styleModalCard(menuPanel);
        VBox buttonColumn = new VBox();
        buttonColumn.setSpacing(10.0f);
        buttonColumn.setFillWidth(true);

        this.startButton = createMenuButton("Start", this::onStart);
        this.continueButton = createMenuButton("Continue", this::onContinue);
        this.loadButton = createMenuButton("Load", this::onLoad);
        this.galleryButton = createMenuButton("Gallery", () -> openSubScreen(GalleryScreen.SCREEN_NAME));
        this.musicRoomButton = createMenuButton("Music Room", () -> openSubScreen(MusicRoomScreen.SCREEN_NAME));
        this.endingsButton = createMenuButton("Endings", () -> openSubScreen(EndingsTreeScreen.SCREEN_NAME));
        this.achievementsButton = createMenuButton("Achievements", () -> openSubScreen(AchievementsScreen.SCREEN_NAME));
        this.preferencesButton = createMenuButton("Preferences", () -> openSubScreen(PreferencesScreen.SCREEN_NAME));
        this.controlsButton = createMenuButton("Controls", () -> openSubScreen(ControlsScreen.SCREEN_NAME));
        this.aboutButton = createMenuButton("About", () -> openSubScreen(AboutScreen.SCREEN_NAME));
        this.quitButton = createMenuButton("Quit", () -> session.getEngineApi().requestQuit());

        buttonColumn.addChild(startButton);
        buttonColumn.addChild(continueButton);
        buttonColumn.addChild(loadButton);
        buttonColumn.addChild(galleryButton);
        buttonColumn.addChild(musicRoomButton);
        buttonColumn.addChild(endingsButton);
        buttonColumn.addChild(achievementsButton);
        buttonColumn.addChild(preferencesButton);
        buttonColumn.addChild(controlsButton);
        buttonColumn.addChild(aboutButton);
        buttonColumn.addChild(quitButton);
        menuPanel.addChild(buttonColumn);

        layout.addChildAt(bannerCard, 120.0f, 120.0f, 1080.0f, 180.0f);
        layout.addChildAt(menuPanel, 120.0f, 330.0f, 380.0f, 650.0f);
        root.addChild(layout);

        setRoot(root);
        refreshContinueState();
    }

    private static TextButton createMenuButton(String label, Runnable action) {
        return UiMediaSupport.createActionButton(label, 330.0f, 44.0f, action);
    }

    @Override
    public void onShow() {
        ProjectDescriptor desc = session.getEngineApi().getProjectDescriptor();
        if (desc != null) {
            titleBanner.setText(desc.getTitle());
            subtitleBanner.setText("v" + desc.getVersion());
        }
        refreshContinueState();
    }

    /**
     * Scans saved slots (manual, quick, auto) to locate the newest save for the Continue button.
     */
    public void refreshContinueState() {
        newestSaveSlotId = findNewestSaveSlotId();
        continueButton.setEnabled(newestSaveSlotId != null);
        continueButton.setAlpha(newestSaveSlotId != null ? 1.0f : 0.45f);
    }

    private String findNewestSaveSlotId() {
        SaveManager saveManager = session.getSaveManager();
        if (saveManager == null) {
            return null;
        }
        String bestSlot = null;
        Instant bestTime = null;

        try {
            Optional<SaveSlotData.SaveMetadata> quick = saveManager.readSlotMetadata(SaveManager.QUICK_SAVE_SLOT);
            if (quick.isPresent()) {
                bestSlot = SaveManager.QUICK_SAVE_SLOT;
                bestTime = quick.get().savedAt();
            }
            for (int i = 1; i <= saveManager.getAutoSaveSlotCount(); i++) {
                String autoId = SaveManager.AUTO_SAVE_PREFIX + i;
                Optional<SaveSlotData.SaveMetadata> meta = saveManager.readSlotMetadata(autoId);
                if (meta.isPresent() && isNewer(meta.get().savedAt(), bestTime)) {
                    bestSlot = autoId;
                    bestTime = meta.get().savedAt();
                }
            }
            for (int page = 0; page < 5; page++) {
                List<SaveSlotData.SaveMetadata> pageSlots = saveManager.listPageSlots(page, SaveLoadScreen.SLOTS_PER_PAGE);
                for (SaveSlotData.SaveMetadata meta : pageSlots) {
                    if (meta != null && isNewer(meta.savedAt(), bestTime)) {
                        bestSlot = meta.slotId();
                        bestTime = meta.savedAt();
                    }
                }
            }
        } catch (IOException ex) {
            session.getEngineApi().logWarn("Failed to scan save slots for Continue: " + ex.getMessage());
        }
        return bestSlot;
    }

    private static boolean isNewer(Instant candidate, Instant currentBest) {
        if (candidate == null) {
            return currentBest == null;
        }
        return currentBest == null || candidate.isAfter(currentBest);
    }

    private void onStart() {
        session.start();
        if (screenManager != null) {
            screenManager.hideScreen(SCREEN_NAME);
        }
    }

    private void onContinue() {
        refreshContinueState();
        if (newestSaveSlotId == null) {
            return;
        }
        try {
            session.loadFromSlot(newestSaveSlotId);
            if (screenManager != null) {
                screenManager.hideScreen(SCREEN_NAME);
            }
        } catch (IOException ex) {
            session.getEngineApi().logError("Continue failed: " + ex.getMessage(), ex);
        }
    }

    private void onLoad() {
        if (screenManager == null) {
            return;
        }
        if (screenManager.getScreen(SaveLoadScreen.SCREEN_NAME) instanceof SaveLoadScreen saveLoad) {
            saveLoad.setMode(SaveLoadScreen.Mode.LOAD);
        }
        screenManager.showScreen(SaveLoadScreen.SCREEN_NAME);
    }

    private void openSubScreen(String name) {
        if (screenManager != null) {
            screenManager.showScreen(name);
        }
    }

    public String getNewestSaveSlotId() { return newestSaveSlotId; }
    public TextWidget getTitleBanner() { return titleBanner; }
    public TextButton getStartButton() { return startButton; }
    public TextButton getContinueButton() { return continueButton; }
    public TextButton getLoadButton() { return loadButton; }
    public TextButton getGalleryButton() { return galleryButton; }
    public TextButton getMusicRoomButton() { return musicRoomButton; }
    public TextButton getEndingsButton() { return endingsButton; }
    public TextButton getAchievementsButton() { return achievementsButton; }
    public TextButton getPreferencesButton() { return preferencesButton; }
    public TextButton getControlsButton() { return controlsButton; }
    public TextButton getAboutButton() { return aboutButton; }
    public TextButton getQuitButton() { return quitButton; }
}
