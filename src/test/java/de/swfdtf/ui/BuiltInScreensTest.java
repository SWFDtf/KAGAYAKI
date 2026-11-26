package de.swfdtf.ui;

import de.swfdtf.audio.AudioDecoder;
import de.swfdtf.audio.AudioEngine;
import de.swfdtf.audio.TextToSpeechService;
import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.graphics.FontManager;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.state.AchievementManager;
import de.swfdtf.state.EndingsManager;
import de.swfdtf.state.GalleryManager;
import de.swfdtf.state.PreferencesState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltInScreensTest {

    private static final String TEST_SCRIPT = """
            define lena = Character("Lena", color="#58a6ff")

            label start:
                $ flag_secret = false
                $ score = 10
                voice "assets/audio/voice/line1.ogg"
                lena "First line in story."
                lena "Second line in story."
                menu "Pick a path":
                    "Always enabled" -> jump after_choice
                    "Locked option" if flag_secret disabled -> jump after_choice
                    "Hidden option" if flag_secret -> jump after_choice
                return

            label timed_menu:
                menu:
                    timeout 5.0 -> jump after_choice
                    "Fast option" -> jump after_choice
                    "Wait out" -> jump after_choice

            label input_scene:
                $ player_name = "Hero"
                input player_name "What is your name?" default="Kaito" \
            allow="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" length=12
                lena "Hello [player_name]!"
                return

            label after_choice:
                $ score = 99
                lena "Reached after choice."
                return

            translate english start_3:
                lena "First line in story (EN)."
            """;

    private ScriptSession createSession(Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("main.vjava"), TEST_SCRIPT);
        ProjectDescriptor desc = new ProjectDescriptor(
                "Chronicles of Kagayaki", "2.1.0", "SWFDTF Studio",
                "2D", 1920, 1080, "start", "deutsch", "ADV", "Kagayaki", ""
        );
        ProjectDescriptor.save(projectDir, desc);
        VJavaAst.ProjectAst project = ProjectScanner.scan(projectDir);
        EngineAPI api = new EngineAPI(EngineConfig.builder().projectDir(projectDir).headless(true).build());
        api.setProjectDescriptor(desc);
        return new ScriptSession(project, api);
    }

    @Test
    void mainMenuSaveLoadAndQuickMenuFlow(@TempDir Path projectDir) throws Exception {
        ScriptSession session = createSession(projectDir);
        ScreenManager sm = new ScreenManager();

        MainMenuScreen mainMenu = new MainMenuScreen(session, sm);
        SaveLoadScreen saveLoad = new SaveLoadScreen(session, sm);
        QuickMenuScreen quickMenu = new QuickMenuScreen(session, sm);
        AdvDialogueScreen adv = new AdvDialogueScreen(session, sm);
        sm.registerScreen(mainMenu);
        sm.registerScreen(saveLoad);
        sm.registerScreen(quickMenu);
        sm.registerScreen(adv);

        // Initially no saves exist -> Continue disabled
        sm.showScreen(mainMenu);
        assertEquals("Chronicles of Kagayaki", mainMenu.getTitleBanner().getText());
        assertFalse(mainMenu.getContinueButton().isEnabled());

        // Start game via Start button
        mainMenu.getStartButton().getOnClick().run();
        assertFalse(mainMenu.isVisible());
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, session.getRuntime().getStatus());
        assertEquals("First line in story.", session.getRuntime().getActiveDialogue().interpolatedText());

        // Save to slot_1 with custom name and PNG thumbnail via SaveManager + SaveLoadScreen
        saveLoad.setMode(SaveLoadScreen.Mode.SAVE);
        assertEquals(6, saveLoad.getVisibleCards().size());
        assertEquals("slot_1", saveLoad.getVisibleCards().get(0).getSlotId());

        byte[] rgba = new byte[]{
            (byte) 255, 0, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255,
            0, 0, (byte) 255, (byte) 255, (byte) 255, (byte) 255, 0, (byte) 255
        };
        session.saveToSlot("slot_1", "Chapter One", 2, 2, rgba);
        saveLoad.refreshSlots();
        SaveLoadScreen.SlotCardView slot1 = saveLoad.getVisibleCards().get(0);
        assertTrue(slot1.isOccupied());
        assertEquals("Chapter One", slot1.getTitleLabel().getText());
        assertTrue(slot1.hasThumbnail());
        assertEquals(2, slot1.getThumbnailWidth());
        assertEquals(2, slot1.getThumbnailHeight());

        // Page navigation 1..5 -> page 2 starts at slot_7
        saveLoad.setPage(1);
        assertEquals(1, saveLoad.getCurrentPage());
        assertEquals("slot_7", saveLoad.getVisibleCards().get(0).getSlotId());
        saveLoad.setPage(0);

        // Quick Save & Quick Save filter tab
        quickMenu.getQuickSaveButton().getOnClick().run();
        saveLoad.setFilterTab(SaveLoadScreen.FilterTab.QUICK);
        assertEquals(1, saveLoad.getVisibleCards().size());
        assertTrue(saveLoad.getVisibleCards().get(0).isOccupied());
        assertEquals("quick", saveLoad.getVisibleCards().get(0).getSlotId());

        // Auto Save & Auto Save filter tab
        session.autoSave();
        saveLoad.setFilterTab(SaveLoadScreen.FilterTab.AUTO);
        assertEquals(3, saveLoad.getVisibleCards().size());
        assertTrue(saveLoad.getVisibleCards().get(0).isOccupied());

        // Advance past line 1, then Continue from MainMenu should load newest save (or slot_1)
        session.advance();
        assertEquals("Second line in story.", session.getRuntime().getActiveDialogue().interpolatedText());

        mainMenu.refreshContinueState();
        assertTrue(mainMenu.getContinueButton().isEnabled());
        assertNotNull(mainMenu.getNewestSaveSlotId());

        // Delete slot_1 via SaveLoadScreen
        saveLoad.setFilterTab(SaveLoadScreen.FilterTab.MANUAL);
        saveLoad.handleSlotDelete("slot_1");
        assertFalse(saveLoad.getVisibleCards().get(0).isOccupied());

        // Quit button sets EngineAPI quit flag
        mainMenu.getQuitButton().getOnClick().run();
        assertTrue(session.getEngineApi().isQuitRequested());
    }

    @Test
    void choiceMenuAndInputOverlayWorkWithScriptSession(@TempDir Path projectDir) throws Exception {
        ScriptSession session = createSession(projectDir);
        ChoiceMenuScreen choiceScreen = new ChoiceMenuScreen(session);
        InputOverlayScreen inputScreen = new InputOverlayScreen(session);

        session.start("start");
        session.advance(); // line 2
        session.advance(); // menu
        assertEquals(ScriptRuntime.Status.WAITING_MENU, session.getRuntime().getStatus());

        choiceScreen.setVisible(true);
        choiceScreen.onShow();
        assertEquals("Pick a path", choiceScreen.getCaptionLabel().getText());
        // Hidden option must be omitted -> 2 visible buttons, 2nd button disabled
        assertEquals(2, choiceScreen.getOptionButtons().size());
        assertTrue(choiceScreen.getOptionButtons().get(0).isEnabled());
        assertFalse(choiceScreen.getOptionButtons().get(1).isEnabled());
        assertFalse(choiceScreen.getTimeoutBar().isVisible());

        // Choose first option
        choiceScreen.getOptionButtons().get(0).getOnClick().run();
        assertEquals("Reached after choice.", session.getRuntime().getActiveDialogue().interpolatedText());

        // Timed menu countdown bar
        session.start("timed_menu");
        choiceScreen.update(0.0);
        assertTrue(choiceScreen.getTimeoutBar().isVisible());
        assertEquals(5.0f, choiceScreen.getTimeoutBar().getMax(), 0.01f);
        session.update(2.0);
        choiceScreen.update(0.0);
        assertEquals(3.0f, choiceScreen.getTimeoutBar().getValue(), 0.1f);

        // Input overlay screen
        session.start("input_scene");
        assertEquals(ScriptRuntime.Status.WAITING_INPUT, session.getRuntime().getStatus());
        inputScreen.onShow();
        assertEquals("What is your name?", inputScreen.getPromptLabel().getText());
        assertEquals("Kaito", inputScreen.getTextInput().getText());
        assertEquals(12, inputScreen.getTextInput().getMaxLength());

        inputScreen.getTextInput().setText("Ren");
        inputScreen.getConfirmButton().getOnClick().run();
        assertEquals("Hello Ren!", session.getRuntime().getActiveDialogue().interpolatedText());
    }

    @Test
    void preferencesUpdatesVolumesLanguageAccessibilityAndPersists(@TempDir Path projectDir) throws Exception {
        ScriptSession session = createSession(projectDir);
        AudioEngine audio = new AudioEngine(projectDir, true);
        audio.setPreferencesState(session.getPreferencesState());
        audio.init();
        TextToSpeechService tts = new TextToSpeechService(session.getEngineApi().getLogger(), false);
        FontManager fontManager = new FontManager();
        session.getEngineApi().registerService(AudioEngine.class, audio);
        session.getEngineApi().registerService(TextToSpeechService.class, tts);
        session.getEngineApi().registerService(FontManager.class, fontManager);

        ScreenManager sm = new ScreenManager();
        PreferencesScreen prefsScreen = new PreferencesScreen(session, sm);
        prefsScreen.layout(1920.0f, 1080.0f);

        // Simulate user click at midpoint (0.5) of Master Volume and Music Volume sliders
        var masterBounds = prefsScreen.getMasterSlider().getBounds();
        assertTrue(prefsScreen.getMasterSlider().dispatchMouseDown(
                masterBounds.x() + masterBounds.width() * 0.5f,
                masterBounds.y() + masterBounds.height() * 0.5f,
                0));
        prefsScreen.getMasterSlider().dispatchMouseUp(
                masterBounds.x() + masterBounds.width() * 0.5f,
                masterBounds.y() + masterBounds.height() * 0.5f,
                0);

        var musicBounds = prefsScreen.getMusicSlider().getBounds();
        assertTrue(prefsScreen.getMusicSlider().dispatchMouseDown(
                musicBounds.x() + musicBounds.width() * 0.4f,
                musicBounds.y() + musicBounds.height() * 0.5f,
                0));
        prefsScreen.getMusicSlider().dispatchMouseUp(
                musicBounds.x() + musicBounds.width() * 0.4f,
                musicBounds.y() + musicBounds.height() * 0.5f,
                0);

        assertEquals(0.5f, session.getPreferencesState().getMasterVolume(), 0.02f);
        assertEquals(0.4f, session.getPreferencesState().getMusicVolume(), 0.02f);
        assertEquals(0.2f, audio.getChannel(AudioEngine.CHANNEL_MUSIC).getChannelMixGain(), 0.02f);

        prefsScreen.selectLanguage("english");
        assertEquals("english", session.getLocalizationManager().getCurrentLanguage());
        assertEquals("english", session.getPreferencesState().getLanguage());

        prefsScreen.getSelfVoicingToggle().getOnClick().run();
        assertTrue(session.getPreferencesState().isSelfVoicing());
        assertTrue(tts.isEnabled());

        prefsScreen.getDyslexiaFontToggle().getOnClick().run();
        assertTrue(session.getPreferencesState().isHighContrastDyslexiaFont());
        assertTrue(fontManager.isDyslexiaFriendlyMode());

        // Drag/click UI Text Scale slider to 75% of [0.75, 1.75] -> 1.50
        var scaleBounds = prefsScreen.getUiScaleSlider().getBounds();
        assertTrue(prefsScreen.getUiScaleSlider().dispatchMouseDown(
                scaleBounds.x() + scaleBounds.width() * 0.75f,
                scaleBounds.y() + scaleBounds.height() * 0.5f,
                0));
        prefsScreen.getUiScaleSlider().dispatchMouseUp(
                scaleBounds.x() + scaleBounds.width() * 0.75f,
                scaleBounds.y() + scaleBounds.height() * 0.5f,
                0);
        assertEquals(1.5f, session.getPreferencesState().getUiTextScale(), 0.02f);
        assertEquals(1.5f, fontManager.getGlobalTextScale(), 0.02f);

        prefsScreen.cycleDialogueMode();
        assertEquals("NVL", session.getPreferencesState().getDialogueMode());

        prefsScreen.savePreferences();
        PreferencesState reloaded = PreferencesState.load(session.getSaveManager().getSavesDir());
        assertEquals(0.5f, reloaded.getMasterVolume(), 0.01f);
        assertEquals("english", reloaded.getLanguage());
        assertEquals("NVL", reloaded.getDialogueMode());
        assertTrue(reloaded.isSelfVoicing());
        assertTrue(reloaded.isHighContrastDyslexiaFont());

        fontManager.close();
        tts.close();
        audio.close();
    }

    @Test
    void aboutControlsAndHistoryLogJumpBackAndVoiceReplay(@TempDir Path projectDir) throws Exception {
        ScriptSession session = createSession(projectDir);
        AudioEngine audio = new AudioEngine(projectDir, true);
        audio.setPreferencesState(session.getPreferencesState());
        audio.init();
        audio.registerInMemoryAudio("assets/audio/voice/line1.ogg",
                AudioDecoder.synthesizeTone(440.0, 0.25, 44100, 0.4f));
        session.getEngineApi().registerService(AudioEngine.class, audio);

        ScreenManager sm = new ScreenManager();
        AboutScreen about = new AboutScreen(session, sm);
        ControlsScreen controls = new ControlsScreen(sm);
        HistoryLogScreen history = new HistoryLogScreen(session, sm);

        about.refreshInfo();
        assertTrue(about.getProjectTitleValue().getText().contains("Chronicles of Kagayaki"));
        assertTrue(about.getProjectVersionValue().getText().contains("2.1.0"));
        assertTrue(about.getEngineVersionValue().getText().contains(EngineAPI.ENGINE_VERSION));
        assertTrue(about.getBackendValue().getText().contains(EngineAPI.RENDER_BACKEND_NAME));
        assertTrue(about.getRenderModeValue().getText().contains("MODE_2D"));

        assertFalse(controls.getVisibleBindings().isEmpty());
        controls.setCategory(ControlsScreen.Category.KEYBOARD);
        assertTrue(controls.getVisibleBindings().stream().anyMatch(b -> b.input().contains("Shift + B")));
        assertTrue(controls.getVisibleBindings().stream()
                .allMatch(b -> b.category() == ControlsScreen.Category.KEYBOARD));

        session.start("start");
        session.advance(); // line 2
        history.refreshEntries();
        assertEquals(2, history.getRowViews().size());
        HistoryLogScreen.HistoryRowView firstRow = history.getRowViews().get(0);
        assertTrue(firstRow.getReplayVoiceButton().isEnabled());

        firstRow.getReplayVoiceButton().getOnClick().run();
        assertTrue(audio.getChannel(AudioEngine.CHANNEL_VOICE).isPlaying());

        firstRow.getJumpBackButton().getOnClick().run();
        assertEquals("First line in story.", session.getRuntime().getActiveDialogue().interpolatedText());

        audio.close();
    }

    @Test
    void galleryMusicRoomEndingsAchievementsAndToastOverlay(@TempDir Path projectDir) throws Exception {
        ScriptSession session = createSession(projectDir);
        AudioEngine audio = new AudioEngine(projectDir, true);
        audio.setPreferencesState(session.getPreferencesState());
        audio.init();
        audio.registerInMemoryAudio("bgm_theme",
                AudioDecoder.synthesizeChord(new double[]{220.0, 440.0, 880.0}, 1.0, 44100, 0.5f));
        session.getEngineApi().registerService(AudioEngine.class, audio);

        ScreenManager sm = new ScreenManager();

        // Gallery
        GalleryManager gm = session.getGalleryManager();
        GalleryManager.GalleryEntry cg1 = new GalleryManager.GalleryEntry(
                "cg_1", "Sunset", "Events", "assets/cg1_a.png",
                List.of("assets/cg1_a.png", "assets/cg1_b.png"), "Evening sky"
        );
        GalleryManager.GalleryEntry cg2 = new GalleryManager.GalleryEntry(
                "cg_2", "Secret", "Events", "assets/cg2.png", List.of("assets/cg2.png"), "Locked"
        );
        gm.register(cg1);
        gm.register(cg2);
        gm.unlock("cg_1");

        GalleryScreen gallery = new GalleryScreen(session, sm);
        assertEquals(2, gallery.getCardViews().size());
        assertTrue(gallery.getCardViews().get(0).isUnlocked());
        assertFalse(gallery.getCardViews().get(1).isUnlocked());

        gallery.getCardViews().get(0).getSelectButton().getOnClick().run();
        assertEquals("assets/cg1_a.png", gallery.getActiveVariationPath());
        gallery.stepVariation(1);
        assertEquals("assets/cg1_b.png", gallery.getActiveVariationPath());
        gallery.toggleZoom();
        assertTrue(gallery.isZoomed());
        gallery.toggleZoom();
        assertFalse(gallery.isZoomed());

        // Music Room
        session.getPersistentState().unlockTrack("bgm_theme");
        MusicRoomScreen musicRoom = new MusicRoomScreen(session, sm);
        musicRoom.registerTrackTitle("bgm_theme", "Main Theme");
        musicRoom.refreshTracks();
        assertEquals(1, musicRoom.getTrackButtons().size());

        musicRoom.setVisible(true);
        musicRoom.playTrack("bgm_theme");
        musicRoom.update(0.05);
        assertEquals("bgm_theme", musicRoom.getCurrentTrackId());
        assertTrue(audio.getChannel(AudioEngine.CHANNEL_MUSIC).isPlaying());
        assertEquals(16, musicRoom.getSpectrumBands().length);
        assertEquals(16, musicRoom.getSpectrumBars().size());
        float maxBand = 0.0f;
        for (float b : musicRoom.getSpectrumBands()) {
            maxBand = Math.max(maxBand, b);
        }
        assertTrue(maxBand > 0.0f, "Expected non-zero spectrum energy during playback");
        assertTrue(musicRoom.getSpectrumBars().stream().anyMatch(bar -> bar.getValue() > 0.0f));
        musicRoom.stopPlayback();
        assertNull(musicRoom.getCurrentTrackId());

        // Endings Tree
        EndingsManager em = session.getEndingsManager();
        em.register(new EndingsManager.EndingDef("end_root", "Common Finale", "Root ending", "Route A", null));
        em.register(new EndingsManager.EndingDef("end_true", "True Ending", "Golden path", "True", "end_root"));
        em.unlock("end_root");

        EndingsTreeScreen endings = new EndingsTreeScreen(session, sm);
        assertEquals(2, endings.getNodeViews().size());
        assertEquals(0, endings.getNodeViews().get(0).depth());
        assertTrue(endings.getNodeViews().get(0).unlocked());
        assertEquals(1, endings.getNodeViews().get(1).depth());
        assertFalse(endings.getNodeViews().get(1).unlocked());
        assertEquals(50.0f, endings.getCompletionBar().getValue(), 0.1f);

        // Achievements + Notification Toast Overlay
        AchievementManager am = session.getAchievementManager();
        am.register(new AchievementManager.AchievementDef("ach_1", "First Step", "Started playing", "", false));
        am.register(new AchievementManager.AchievementDef("ach_secret", "Hidden Truth", "Found secret", "", true));
        am.unlock("ach_1");

        AchievementsScreen achievements = new AchievementsScreen(session, sm);
        assertEquals(2, achievements.getRows().size());
        assertTrue(achievements.getRows().get(0).unlocked());
        assertEquals("First Step", achievements.getRows().get(0).titleLabel().getText());
        assertFalse(achievements.getRows().get(1).unlocked());
        assertEquals("???", achievements.getRows().get(1).titleLabel().getText());

        NotificationToastOverlay toastOverlay = new NotificationToastOverlay(session, sm);
        sm.showScreen(toastOverlay);
        toastOverlay.update(0.1);
        assertTrue(toastOverlay.isToastVisible());
        assertEquals("First Step", toastOverlay.getActiveToast().title());

        toastOverlay.setSkipPillActive(true);
        assertTrue(toastOverlay.getStatusPill().isVisible());
        assertTrue(toastOverlay.getStatusPillText().getText().contains("SKIP"));

        // Advance toast past DEFAULT_TOAST_DURATION -> toast dismisses cleanly
        toastOverlay.update(NotificationToastOverlay.DEFAULT_TOAST_DURATION);
        assertFalse(toastOverlay.isToastVisible());

        audio.close();
    }
}
