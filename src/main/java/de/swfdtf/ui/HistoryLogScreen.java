package de.swfdtf.ui;

import de.swfdtf.audio.AudioEngine;
import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.state.HistoryLog;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Scrollable Dialogue History Backlog screen ({@code Z = 400}, modal) listing {@link HistoryLog}
 * entries with speaker color, dialogue text, per-line {@code Replay Voice} via {@link AudioEngine},
 * and {@code Jump Back} via {@link ScriptSession#jumpToHistoryEntry(HistoryLog.HistoryEntry)}.
 */
public class HistoryLogScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "history_log";

    /**
     * Row view model for a rendered history entry.
     */
    public static final class HistoryRowView {
        private final HistoryLog.HistoryEntry entry;
        private final TextWidget speakerLabel;
        private final TextWidget textLabel;
        private final TextButton replayVoiceButton;
        private final TextButton jumpBackButton;

        private HistoryRowView(
                HistoryLog.HistoryEntry entry,
                TextWidget speakerLabel,
                TextWidget textLabel,
                TextButton replayVoiceButton,
                TextButton jumpBackButton
        ) {
            this.entry = entry;
            this.speakerLabel = speakerLabel;
            this.textLabel = textLabel;
            this.replayVoiceButton = replayVoiceButton;
            this.jumpBackButton = jumpBackButton;
        }

        /**
         * Returns the entry value.
         *
         * @return the resulting value
         */
        public HistoryLog.HistoryEntry getEntry() {
            return entry;
        }

        /**
         * Returns the speakerLabel value.
         *
         * @return the resulting value
         */
        public TextWidget getSpeakerLabel() {
            return speakerLabel;
        }

        /**
         * Returns the textLabel value.
         *
         * @return the resulting value
         */
        public TextWidget getTextLabel() {
            return textLabel;
        }

        /**
         * Returns the replayVoiceButton value.
         *
         * @return the resulting value
         */
        public TextButton getReplayVoiceButton() {
            return replayVoiceButton;
        }

        /**
         * Returns the jumpBackButton value.
         *
         * @return the resulting value
         */
        public TextButton getJumpBackButton() {
            return jumpBackButton;
        }
    }

    private final ScriptSession session;
    private final ScreenManager screenManager;
    private AudioEngine explicitAudioEngine;

    private final Viewport viewport;
    private final VBox entriesBox;
    private final List<HistoryRowView> rowViews = new ArrayList<>();

    /**
     * Creates a {@code HistoryLogScreen} bound to {@code session} and {@code screenManager}.
     *
     * @param session       active script session
     * @param screenManager UI screen manager
     */
    public HistoryLogScreen(ScriptSession session, ScreenManager screenManager) {
        this(session, screenManager, null);
    }

    /**
     * Creates a {@code HistoryLogScreen} with an optional explicit {@link AudioEngine}.
     *
     * @param session       active script session
     * @param screenManager UI screen manager
     * @param audioEngine   explicit audio engine (nullable)
     */
    public HistoryLogScreen(ScriptSession session, ScreenManager screenManager, AudioEngine audioEngine) {
        super(SCREEN_NAME, SceneLayer.Z_MODAL, true);
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.screenManager = screenManager;
        this.explicitAudioEngine = audioEngine;

        Frame backdrop = new Frame();
        UiMediaSupport.styleBackdrop(backdrop);
        backdrop.setPadding(150.0f, 70.0f);

        Frame panel = new Frame();
        UiMediaSupport.styleModalCard(panel);

        VBox mainBox = new VBox();
        mainBox.setSpacing(16.0f);
        mainBox.setFillWidth(true);

        HBox header = new HBox();
        header.setSpacing(20.0f);
        header.setAlignCenterY(true);
        header.addChild(UiMediaSupport.createTitleText("Dialogue History", 34.0f));
        header.addChild(UiMediaSupport.createActionButton("Back", 120.0f, 38.0f, this::closeScreen));

        this.viewport = new Viewport();
        this.viewport.setPreferredSize(1520.0f, 760.0f);

        this.entriesBox = new VBox();
        this.entriesBox.setSpacing(12.0f);
        this.entriesBox.setFillWidth(true);

        this.viewport.addChild(entriesBox);
        mainBox.addChild(header);
        mainBox.addChild(viewport);
        panel.addChild(mainBox);
        backdrop.addChild(panel);
        setRoot(backdrop);

        addKeyBinding(GLFW.GLFW_KEY_ESCAPE, this::closeScreen);
        refreshEntries();
    }

    @Override
    public void onShow() {
        refreshEntries();
    }

    /**
     * Rebuilds row cards from {@link ScriptSession#getHistoryLog()}.
     */
    public void refreshEntries() {
        entriesBox.clearChildren();
        rowViews.clear();

        List<HistoryLog.HistoryEntry> entries = session.getHistoryLog().getEntries();
        if (entries.isEmpty()) {
            entriesBox.addChild(UiMediaSupport.createMutedText("No dialogue history recorded yet.", 22.0f));
            return;
        }

        for (HistoryLog.HistoryEntry entry : entries) {
            Frame rowCard = new Frame();
            UiMediaSupport.styleElevatedCard(rowCard);

            VBox cardBody = new VBox();
            cardBody.setSpacing(8.0f);
            cardBody.setFillWidth(true);

            HBox topLine = new HBox();
            topLine.setSpacing(14.0f);
            topLine.setAlignCenterY(true);

            String speaker = (entry.speakerName() != null && !entry.speakerName().isBlank())
                    ? entry.speakerName()
                    : "Narrator";
            TextWidget speakerLbl = UiMediaSupport.createTitleText(speaker, 22.0f);
            if (entry.speakerColorHex() != null && !entry.speakerColorHex().isBlank()) {
                speakerLbl.setColorHex(entry.speakerColorHex());
            } else {
                speakerLbl.setColorHex(ThemePalette.ACCENT_HOVER);
            }

            boolean hasVoice = entry.voicePath() != null && !entry.voicePath().isBlank();
            TextButton replayBtn = UiMediaSupport.createActionButton("Replay Voice", 150.0f, 32.0f,
                    () -> replayVoice(entry.voicePath()));
            replayBtn.setEnabled(hasVoice);
            if (!hasVoice) {
                replayBtn.setAlpha(0.45f);
            }

            TextButton jumpBtn = UiMediaSupport.createActionButton("Jump Back", 130.0f, 32.0f,
                    () -> jumpBackTo(entry));

            topLine.addChild(speakerLbl);
            topLine.addChild(replayBtn);
            topLine.addChild(jumpBtn);

            TextWidget textLbl = UiMediaSupport.createTitleText(entry.text() != null ? entry.text() : "", 22.0f);

            cardBody.addChild(topLine);
            cardBody.addChild(textLbl);
            rowCard.addChild(cardBody);
            entriesBox.addChild(rowCard);

            rowViews.add(new HistoryRowView(entry, speakerLbl, textLbl, replayBtn, jumpBtn));
        }

        viewport.setScrollY(viewport.getMaxScrollY());
    }

    /**
     * Replays a voice clip via {@link AudioEngine#voice(String)}.
     *
     * @param voicePath path to the voice audio file
     */
    public void replayVoice(String voicePath) {
        if (voicePath == null || voicePath.isBlank()) {
            return;
        }
        AudioEngine audio = explicitAudioEngine != null ? explicitAudioEngine : UiMediaSupport.findAudioEngine(session);
        if (audio != null) {
            audio.voice(voicePath);
        }
    }

    /**
     * Jumps back to {@code entry}'s checkpoint and closes the history log on success.
     *
     * @param entry target history entry
     * @return {@code true} if rollback succeeded
     */
    public boolean jumpBackTo(HistoryLog.HistoryEntry entry) {
        if (entry == null) {
            return false;
        }
        boolean jumped = session.jumpToHistoryEntry(entry);
        if (jumped) {
            closeScreen();
        }
        return jumped;
    }

    /**
     * Formats a history entry into clean copyable plain text ({@code "Speaker: Text"}) ({@code #0399}).
     *
     * @param entry history entry to format
     * @return formatted clipboard string
     */
    public static String formatCopyableEntry(HistoryLog.HistoryEntry entry) {
        if (entry == null) {
            return "";
        }
        String cleanText = entry.text() != null
                ? entry.text().replaceAll("\\{[^}]*\\}", "").trim()
                : "";
        String speaker = entry.speakerName() != null ? entry.speakerName().trim() : "";
        return speaker.isEmpty() ? cleanText : speaker + ": " + cleanText;
    }

    private void closeScreen() {
        if (screenManager != null) {
            screenManager.hideScreen(SCREEN_NAME);
        } else {
            setVisible(false);
            onHide();
        }
    }

    /**
     * Sets the audioEngine value.
     *
     * @param audioEngine the audioEngine argument
     */
    public void setAudioEngine(AudioEngine audioEngine) {
        this.explicitAudioEngine = audioEngine;
    }

    /**
     * Returns the rowViews value.
     *
     * @return the resulting value
     */
    public List<HistoryRowView> getRowViews() {
        return Collections.unmodifiableList(rowViews);
    }

    /**
     * Returns the viewport value.
     *
     * @return the resulting value
     */
    public Viewport getViewport() {
        return viewport;
    }
}
