package de.swfdtf.ui;

import de.swfdtf.audio.AudioEngine;
import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.parser.ScriptSession;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Built-in Dark-Slate Music Room screen ({@code Z = 400}, modal) listing unlocked music tracks
 * from {@link de.swfdtf.state.PersistentAccess#getUnlockedTracks()}, controlling playback via
 * {@link AudioEngine}, and rendering real-time 16-band frequency {@link VBar} visualizers from
 * {@link AudioEngine#getMusicSpectrumBands(int)}.
 */
public class MusicRoomScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "music_room";

    /** Number of logarithmic frequency bands rendered in the visualizer. */
    public static final int SPECTRUM_BAND_COUNT = 16;

    private final ScriptSession session;
    private final ScreenManager screenManager;
    private AudioEngine explicitAudioEngine;

    private final Map<String, String> trackDisplayNames = new LinkedHashMap<>();
    private final Viewport trackViewport;
    private final VBox trackListBox;
    private final List<TextButton> trackButtons = new ArrayList<>();
    private final TextWidget nowPlayingLabel;
    private final TextButton stopButton;
    private final Frame spectrumContainer;
    private final HBox spectrumBarsRow;
    private final List<VBar> spectrumBars = new ArrayList<>(SPECTRUM_BAND_COUNT);

    private String currentTrackId;
    private final float[] spectrumBands = new float[SPECTRUM_BAND_COUNT];

    /**
     * Creates a {@code MusicRoomScreen} bound to {@code session} and {@code screenManager}.
     *
     * @param session       active script session
     * @param screenManager UI screen manager
     */
    public MusicRoomScreen(ScriptSession session, ScreenManager screenManager) {
        this(session, screenManager, null);
    }

    /**
     * Creates a {@code MusicRoomScreen} with an explicit {@link AudioEngine}.
     */
    public MusicRoomScreen(ScriptSession session, ScreenManager screenManager, AudioEngine audioEngine) {
        super(SCREEN_NAME, SceneLayer.Z_MODAL, true);
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.screenManager = screenManager;
        this.explicitAudioEngine = audioEngine;

        Frame backdrop = new Frame();
        UiMediaSupport.styleBackdrop(backdrop);
        backdrop.setPadding(160.0f, 80.0f);

        Frame panel = new Frame();
        UiMediaSupport.styleModalCard(panel);

        VBox mainBox = new VBox();
        mainBox.setSpacing(18.0f);
        mainBox.setFillWidth(true);

        HBox header = new HBox();
        header.setSpacing(18.0f);
        header.setAlignCenterY(true);
        header.addChild(UiMediaSupport.createTitleText("Music Room", 34.0f));
        this.stopButton = UiMediaSupport.createActionButton("Stop", 120.0f, 38.0f, this::stopPlayback);
        header.addChild(stopButton);
        header.addChild(UiMediaSupport.createActionButton("Back", 120.0f, 38.0f, this::closeScreen));

        this.nowPlayingLabel = UiMediaSupport.createMutedText("Stopped", 22.0f);

        this.spectrumContainer = new Frame();
        this.spectrumContainer.setPreferredSize(1500.0f, 220.0f);
        this.spectrumContainer.setBackgroundColorHex(ThemePalette.CANVAS_BG);
        this.spectrumContainer.setBorderColorHex(ThemePalette.BORDER);
        this.spectrumContainer.setBorderThickness(1.0f);
        this.spectrumContainer.setCornerRadius(8.0f);
        this.spectrumContainer.setPadding(18.0f);

        this.spectrumBarsRow = new HBox();
        this.spectrumBarsRow.setSpacing(12.0f);
        this.spectrumBarsRow.setAlignCenterY(true);
        for (int i = 0; i < SPECTRUM_BAND_COUNT; i++) {
            VBar vbar = new VBar(0.0f, 1.0f);
            vbar.setPreferredSize(78.0f, 184.0f);
            vbar.setFillColorHex(ThemePalette.ACCENT_IDLE);
            vbar.setTrackColorHex(ThemePalette.ELEVATED);
            this.spectrumBars.add(vbar);
            this.spectrumBarsRow.addChild(vbar);
        }
        this.spectrumContainer.addChild(this.spectrumBarsRow);

        this.trackViewport = new Viewport();
        this.trackViewport.setPreferredSize(1500.0f, 440.0f);
        this.trackListBox = new VBox();
        this.trackListBox.setSpacing(10.0f);
        this.trackListBox.setFillWidth(true);
        this.trackViewport.addChild(trackListBox);

        mainBox.addChild(header);
        mainBox.addChild(nowPlayingLabel);
        mainBox.addChild(spectrumContainer);
        mainBox.addChild(trackViewport);
        panel.addChild(mainBox);
        backdrop.addChild(panel);
        setRoot(backdrop);

        addKeyBinding(GLFW.GLFW_KEY_ESCAPE, this::closeScreen);
        refreshTracks();
    }

    /**
     * Registers a friendly display title for a track asset ID.
     *
     * @param trackId     track identifier matching {@link de.swfdtf.state.PersistentAccess#unlockTrack(String)}
     * @param displayName human-readable track title
     */
    public void registerTrackTitle(String trackId, String displayName) {
        if (trackId != null && !trackId.isBlank() && displayName != null) {
            trackDisplayNames.put(trackId, displayName);
        }
    }

    @Override
    public void onShow() {
        refreshTracks();
    }

    @Override
    public void update(double dt) {
        if (!isVisible()) {
            return;
        }
        AudioEngine audio = resolveAudioEngine();
        if (audio != null) {
            audio.update(dt);
            float[] bands = audio.getMusicSpectrumBands(SPECTRUM_BAND_COUNT);
            int count = Math.min(bands.length, SPECTRUM_BAND_COUNT);
            for (int i = 0; i < count; i++) {
                float level = Math.max(0.0f, Math.min(1.0f, bands[i]));
                spectrumBands[i] = level;
                VBar bar = spectrumBars.get(i);
                bar.setValue(level);
                String hex = level > 0.72f
                        ? ThemePalette.ACTIVE_SELECTED
                        : (level > 0.35f ? ThemePalette.ACCENT_HOVER : ThemePalette.ACCENT_IDLE);
                bar.setFillColorHex(hex);
            }
            if (currentTrackId != null && !audio.getChannel(AudioEngine.CHANNEL_MUSIC).isPlaying()) {
                nowPlayingLabel.setText("Stopped");
            }
        }
        super.update(dt);
    }

    /**
     * Rebuilds the track list from {@link de.swfdtf.state.PersistentState#getUnlockedTracks()}.
     */
    public void refreshTracks() {
        trackListBox.clearChildren();
        trackButtons.clear();

        Set<String> unlocked = session.getPersistentState().getUnlockedTracks();
        if (unlocked.isEmpty()) {
            trackListBox.addChild(UiMediaSupport.createMutedText("No music tracks unlocked yet.", 22.0f));
            return;
        }

        for (String trackId : unlocked) {
            String title = trackDisplayNames.getOrDefault(trackId, trackId);
            TextButton btn = UiMediaSupport.createActionButton(title, 1440.0f, 46.0f, () -> playTrack(trackId));
            btn.setSelected(Objects.equals(trackId, currentTrackId));
            trackButtons.add(btn);
            trackListBox.addChild(btn);
        }
    }

    /**
     * Plays the given unlocked track on {@link AudioEngine#CHANNEL_MUSIC}.
     *
     * @param trackId track path or identifier
     */
    public void playTrack(String trackId) {
        if (trackId == null || !session.getPersistentState().isTrackUnlocked(trackId)) {
            return;
        }
        this.currentTrackId = trackId;
        AudioEngine audio = resolveAudioEngine();
        if (audio != null) {
            audio.play(AudioEngine.CHANNEL_MUSIC, trackId, true, 0.25, 1.0);
        }
        String title = trackDisplayNames.getOrDefault(trackId, trackId);
        nowPlayingLabel.setText("Now Playing: " + title);
        refreshTracks();
    }

    /**
     * Stops active music playback.
     */
    public void stopPlayback() {
        AudioEngine audio = resolveAudioEngine();
        if (audio != null) {
            audio.stop(AudioEngine.CHANNEL_MUSIC, 0.2);
        }
        currentTrackId = null;
        java.util.Arrays.fill(spectrumBands, 0.0f);
        for (VBar bar : spectrumBars) {
            bar.setValue(0.0f);
        }
        nowPlayingLabel.setText("Stopped");
        refreshTracks();
    }

    private void closeScreen() {
        if (screenManager != null) {
            screenManager.hideScreen(SCREEN_NAME);
        } else {
            setVisible(false);
            onHide();
        }
    }

    private AudioEngine resolveAudioEngine() {
        return explicitAudioEngine != null ? explicitAudioEngine : UiMediaSupport.findAudioEngine(session);
    }

    public void setAudioEngine(AudioEngine audioEngine) {
        this.explicitAudioEngine = audioEngine;
    }

    public float[] getSpectrumBands() {
        return spectrumBands.clone();
    }

    public String getCurrentTrackId() {
        return currentTrackId;
    }

    public TextWidget getNowPlayingLabel() {
        return nowPlayingLabel;
    }

    public TextButton getStopButton() {
        return stopButton;
    }

    public List<TextButton> getTrackButtons() {
        return Collections.unmodifiableList(trackButtons);
    }

    /** @return unmodifiable list of the 16 spectrum {@link VBar} visualizer widgets */
    public List<VBar> getSpectrumBars() {
        return Collections.unmodifiableList(spectrumBars);
    }
}
