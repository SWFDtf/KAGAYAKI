package de.swfdtf.ui;

import de.swfdtf.audio.AudioEngine;
import de.swfdtf.audio.TextToSpeechService;
import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.graphics.StyledTextLayout;
import de.swfdtf.graphics.TextTagParser;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.state.PreferencesState;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.util.Objects;

/**
 * Classic ADV bottom-panel dialogue presentation screen ({@code Z = 200}).
 *
 * <p>Features the canonical {@code (140, 790, 1640, 250)} dialogue box, elevated speaker badge,
 * {@link PreferencesState#getTextSpeedCps()}-synced typewriter reveal, throttled character voice
 * bleeps, Auto-Forward timer, Skip mode respecting {@code skipUnread} and {@code skipAfterChoices},
 * self-voicing TTS integration, and an embedded QuickMenu action row.</p>
 */
public class AdvDialogueScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "adv_dialogue";

    /** Logical X coordinate of the bottom ADV dialogue box. */
    public static final float PANEL_X = 140.0f;

    /** Logical Y coordinate of the bottom ADV dialogue box. */
    public static final float PANEL_Y = 790.0f;

    /** Logical width of the bottom ADV dialogue box. */
    public static final float PANEL_W = 1640.0f;

    /** Logical height of the bottom ADV dialogue box. */
    public static final float PANEL_H = 250.0f;

    /** Minimum time in seconds between consecutive procedural voice bleeps. */
    public static final double BLEEP_THROTTLE_SECONDS = 0.055;

    /** Step interval in seconds while fast-skipping dialogue lines. */
    public static final double SKIP_STEP_SECONDS = 0.05;

    private final ScriptSession session;
    private final ScreenManager screenManager;
    private AudioEngine explicitAudioEngine;
    private TextToSpeechService explicitTtsService;

    private final Fixed rootContainer;
    private final Frame dialoguePanel;
    private final Frame speakerBox;
    private final TextWidget speakerText;
    private final TextWidget dialogueText;
    private final TextWidget statusBadge;
    private final HBox quickMenuRow;
    private final TextButton skipButton;
    private final TextButton autoButton;

    private ScriptRuntime.DialoguePrompt lastPrompt;
    private StyledTextLayout.TypewriterController fallbackTypewriter;
    private int lastObservedVisibleChars = 0;
    private double bleepCooldown = 0.0;
    private int bleepCount = 0;

    private boolean skipActive = false;
    private boolean autoActive = false;
    private double autoTimer = 0.0;
    private double skipTimer = 0.0;

    /**
     * Creates an {@code AdvDialogueScreen} bound to {@code session} and {@code screenManager}.
     *
     * @param session       active script session
     * @param screenManager UI screen manager for opening modal screens
     */
    public AdvDialogueScreen(ScriptSession session, ScreenManager screenManager) {
        this(session, screenManager, null, null);
    }

    /**
     * Creates an {@code AdvDialogueScreen} with explicit audio/TTS dependencies (useful for testing).
     *
     * @param session       active script session
     * @param screenManager UI screen manager
     * @param audioEngine   optional audio engine override
     * @param ttsService    optional TTS service override
     */
    public AdvDialogueScreen(
            ScriptSession session,
            ScreenManager screenManager,
            AudioEngine audioEngine,
            TextToSpeechService ttsService
    ) {
        super(SCREEN_NAME, SceneLayer.Z_SCREENS, false);
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.screenManager = screenManager;
        this.explicitAudioEngine = audioEngine;
        this.explicitTtsService = ttsService;

        this.rootContainer = new Fixed();

        this.dialoguePanel = new Frame() {
            @Override
            protected boolean onMouseDown(float lx, float ly, int button) {
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    handlePrimaryAdvance();
                    return true;
                }
                return false;
            }
        };
        this.dialoguePanel.setBackgroundColorHex(ThemePalette.PANEL_GLASS)
                .setBorderColorHex(ThemePalette.BORDER)
                .setBorderThickness(1.5f)
                .setCornerRadius(10.0f)
                .setPadding(24.0f);

        this.speakerBox = new Frame();
        this.speakerBox.setBackgroundColorHex(ThemePalette.ELEVATED)
                .setBorderColorHex(ThemePalette.ACCENT_IDLE)
                .setBorderThickness(1.5f)
                .setCornerRadius(8.0f)
                .setPadding(16.0f, 8.0f);

        this.speakerText = new TextWidget("");
        this.speakerText.setFontSize(26.0f);
        this.speakerText.setColorHex(ThemePalette.ACCENT_HOVER);
        this.speakerText.setDropShadow(true);
        this.speakerBox.addChild(this.speakerText);

        this.dialogueText = new TextWidget("");
        this.dialogueText.setFontSize(28.0f);
        this.dialogueText.setColorHex(ThemePalette.TEXT_PRIMARY);
        this.dialogueText.setDropShadow(true);

        this.statusBadge = new TextWidget("");
        this.statusBadge.setFontSize(18.0f);
        this.statusBadge.setColorHex(ThemePalette.ACTIVE_SELECTED);
        this.statusBadge.setDropShadow(true);

        this.quickMenuRow = new HBox();
        this.quickMenuRow.setSpacing(8.0f);
        this.quickMenuRow.setAlignCenterY(true);

        TextButton rollbackBtn = createQuickButton("Rollback", () -> session.rollback());
        TextButton historyBtn = createQuickButton("History", () -> showScreen(HistoryLogScreen.SCREEN_NAME));
        this.skipButton = createQuickButton("Skip", this::toggleSkip);
        this.autoButton = createQuickButton("Auto", this::toggleAuto);
        TextButton qSaveBtn = createQuickButton("Q.Save", this::performQuickSave);
        TextButton qLoadBtn = createQuickButton("Q.Load", this::performQuickLoad);
        TextButton prefsBtn = createQuickButton("Prefs", () -> showScreen(PreferencesScreen.SCREEN_NAME));

        this.quickMenuRow.addChild(rollbackBtn);
        this.quickMenuRow.addChild(historyBtn);
        this.quickMenuRow.addChild(this.skipButton);
        this.quickMenuRow.addChild(this.autoButton);
        this.quickMenuRow.addChild(qSaveBtn);
        this.quickMenuRow.addChild(qLoadBtn);
        this.quickMenuRow.addChild(prefsBtn);

        this.rootContainer.addChildAt(this.dialoguePanel, PANEL_X, PANEL_Y, PANEL_W, PANEL_H);
        this.rootContainer.addChildAt(this.speakerBox, PANEL_X + 24.0f, PANEL_Y - 42.0f, 340.0f, 50.0f);
        this.rootContainer.addChildAt(this.dialogueText, PANEL_X + 36.0f, PANEL_Y + 26.0f, PANEL_W - 72.0f, 152.0f);
        this.rootContainer.addChildAt(this.statusBadge, PANEL_X + PANEL_W - 240.0f, PANEL_Y + 12.0f, 210.0f, 28.0f);
        this.rootContainer.addChildAt(
                this.quickMenuRow, PANEL_X + PANEL_W - 770.0f, PANEL_Y + PANEL_H - 50.0f, 740.0f, 36.0f);

        setRoot(this.rootContainer);
        registerDefaultBindings();
    }

    private void registerDefaultBindings() {
        addKeyBinding(GLFW.GLFW_KEY_SPACE, this::handlePrimaryAdvance);
        addKeyBinding(GLFW.GLFW_KEY_ENTER, this::handlePrimaryAdvance);
        addKeyBinding(GLFW.GLFW_KEY_KP_ENTER, this::handlePrimaryAdvance);
        addKeyBinding(GLFW.GLFW_KEY_A, this::toggleAuto);
        addKeyBinding(GLFW.GLFW_KEY_S, this::performQuickSave);
        addKeyBinding(GLFW.GLFW_KEY_L, this::performQuickLoad);
        addKeyBinding(GLFW.GLFW_KEY_H, () -> showScreen(HistoryLogScreen.SCREEN_NAME));
        addKeyBinding(GLFW.GLFW_KEY_V, this::toggleSelfVoicing);
        addKeyBinding(GLFW.GLFW_KEY_PAGE_UP, () -> session.rollback());
        addKeyBinding(GLFW.GLFW_KEY_PAGE_DOWN, () -> session.rollForward());
    }

    private static TextButton createQuickButton(String label, Runnable action) {
        TextButton btn = new TextButton(label, action);
        btn.setPreferredSize(98.0f, 34.0f);
        btn.setBackgroundColorHex(ThemePalette.ELEVATED);
        btn.setBorderColorHex(ThemePalette.BORDER);
        btn.setBorderThickness(1.0f);
        btn.setCornerRadius(6.0f);
        return btn;
    }

    @Override
    public void onShow() {
        syncFromRuntime(true);
        updateStatusIndicators();
    }

    @Override
    public void update(double dt) {
        if (!isVisible()) {
            return;
        }
        ScriptRuntime runtime = session.getRuntime();
        PreferencesState prefs = session.getPreferencesState();

        if (runtime.getStatus() == ScriptRuntime.Status.WAITING_MENU && !prefs.isSkipAfterChoices()) {
            skipActive = false;
        }

        syncFromRuntime(false);

        if (bleepCooldown > 0.0) {
            bleepCooldown = Math.max(0.0, bleepCooldown - dt);
        }

        StyledTextLayout.TypewriterController activeTw = resolveActiveTypewriter();
        int beforeChars = activeTw != null ? activeTw.getVisibleCharCount() : lastObservedVisibleChars;

        boolean hasWidgetTw = dialogueText.getTypewriter() != null;
        super.update(dt);
        if (!hasWidgetTw && fallbackTypewriter != null && dt >= 0.0) {
            fallbackTypewriter.advance(dt);
        }

        activeTw = resolveActiveTypewriter();
        int afterChars = activeTw != null ? activeTw.getVisibleCharCount() : beforeChars;
        if (afterChars > lastObservedVisibleChars) {
            triggerVoiceBleepForNewChars(lastObservedVisibleChars, afterChars);
            lastObservedVisibleChars = afterChars;
        }

        if (runtime.getStatus() == ScriptRuntime.Status.WAITING_DIALOGUE && lastPrompt != null) {
            handleSkipAndAuto(dt, runtime, prefs, activeTw);
        }
        updateStatusIndicators();
    }

    private void handleSkipAndAuto(
            double dt,
            ScriptRuntime runtime,
            PreferencesState prefs,
            StyledTextLayout.TypewriterController tw
    ) {
        if (skipActive) {
            if (!canSkipPrompt(lastPrompt, prefs)) {
                skipActive = false;
                skipTimer = 0.0;
                return;
            }
            skipTimer += dt;
            if (skipTimer >= SKIP_STEP_SECONDS) {
                skipTimer = 0.0;
                revealAllImmediate(tw);
                session.advance();
                syncFromRuntime(false);
            }
            return;
        }

        if (autoActive) {
            boolean finished = tw == null || tw.isFinished();
            if (finished) {
                autoTimer += dt;
                if (autoTimer >= prefs.getAutoForwardDelaySeconds()) {
                    autoTimer = 0.0;
                    session.advance();
                    syncFromRuntime(false);
                }
            }
        }
    }

    /**
     * Advances the typewriter reveal to the next wait/end, or advances the script if finished.
     */
    public void handlePrimaryAdvance() {
        ScriptRuntime runtime = session.getRuntime();
        if (runtime.getStatus() != ScriptRuntime.Status.WAITING_DIALOGUE) {
            session.advance();
            syncFromRuntime(false);
            return;
        }
        StyledTextLayout.TypewriterController tw = resolveActiveTypewriter();
        if (tw != null && !tw.isFinished()) {
            tw.clickOrAdvance();
            lastObservedVisibleChars = tw.getVisibleCharCount();
            return;
        }
        autoTimer = 0.0;
        session.advance();
        syncFromRuntime(false);
    }

    /**
     * Toggles skip mode.
     *
     * @return new skip state
     */
    public boolean toggleSkip() {
        setSkipActive(!skipActive);
        return skipActive;
    }

    /**
     * Sets skip mode active or inactive.
     *
     * @param active desired skip state
     */
    public void setSkipActive(boolean active) {
        this.skipActive = active;
        this.skipTimer = 0.0;
        if (active) {
            this.autoActive = false;
            this.autoTimer = 0.0;
        }
        updateStatusIndicators();
    }

    /**
     * Toggles auto-advance mode.
     *
     * @return new auto-advance state
     */
    public boolean toggleAuto() {
        setAutoActive(!autoActive);
        return autoActive;
    }

    /**
     * Sets auto-advance mode active or inactive.
     *
     * @param active desired auto-advance state
     */
    public void setAutoActive(boolean active) {
        this.autoActive = active;
        this.autoTimer = 0.0;
        if (active) {
            this.skipActive = false;
            this.skipTimer = 0.0;
        }
        updateStatusIndicators();
    }

    /**
     * Returns whether skip mode is currently active.
     *
     * @return {@code true} if skip mode is currently active
     */
    public boolean isSkipActive() {
        return skipActive;
    }

    /**
     * Returns whether auto-advance mode is currently active.
     *
     * @return {@code true} if auto-advance mode is currently active
     */
    public boolean isAutoActive() {
        return autoActive;
    }

    /**
     * Returns the number of voice bleeps triggered so far.
     *
     * @return number of voice bleeps triggered so far
     */
    public int getBleepCount() {
        return bleepCount;
    }

    /**
     * Returns the current typewriter visible character count.
     *
     * @return current typewriter visible character count
     */
    public int getVisibleCharCount() {
        StyledTextLayout.TypewriterController tw = resolveActiveTypewriter();
        return tw != null ? tw.getVisibleCharCount() : dialogueText.getText().length();
    }

    /**
     * Returns whether the active line's typewriter reveal has finished.
     *
     * @return {@code true} when the active line's typewriter reveal has finished
     */
    public boolean isTypewriterFinished() {
        StyledTextLayout.TypewriterController tw = resolveActiveTypewriter();
        return tw == null || tw.isFinished();
    }

    /**
     * Returns the canonical logical bounds of the bottom ADV panel.
     *
     * @return canonical logical bounds of the bottom ADV panel
     */
    public Rect2f getDialogueBoxBounds() {
        return new Rect2f(PANEL_X, PANEL_Y, PANEL_W, PANEL_H);
    }

    /**
     * Returns the speakerNameWidget value.
     *
     * @return the resulting value
     */
    public TextWidget getSpeakerNameWidget() {
        return speakerText;
    }

    /**
     * Returns the dialogueTextWidget value.
     *
     * @return the resulting value
     */
    public TextWidget getDialogueTextWidget() {
        return dialogueText;
    }

    /**
     * Returns the quickMenuRow value.
     *
     * @return the resulting value
     */
    public HBox getQuickMenuRow() {
        return quickMenuRow;
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
     * Sets the ttsService value.
     *
     * @param ttsService the ttsService argument
     */
    public void setTtsService(TextToSpeechService ttsService) {
        this.explicitTtsService = ttsService;
    }

    /**
     * Synchronizes speaker badge, dialogue text, typewriter speed, and TTS state from {@link ScriptRuntime}.
     *
     * @param force {@code true} to force refresh even if the active prompt reference is unchanged
     */
    public void syncFromRuntime(boolean force) {
        ScriptRuntime runtime = session.getRuntime();
        ScriptRuntime.DialoguePrompt prompt = runtime.getActiveDialogue();
        PreferencesState prefs = session.getPreferencesState();

        if (!force && Objects.equals(prompt, lastPrompt)) {
            float targetCps = Math.max(0.0f, prefs.getTextSpeedCps());
            if (Math.abs(dialogueText.getTypewriterCps() - targetCps) > 0.01f) {
                dialogueText.setTypewriterCps(targetCps);
            }
            return;
        }

        lastPrompt = prompt;
        autoTimer = 0.0;
        if (prompt == null) {
            speakerBox.setVisible(false);
            speakerText.setText("");
            dialogueText.setText("");
            fallbackTypewriter = null;
            lastObservedVisibleChars = 0;
            return;
        }

        String speakerName = prompt.speakerName();
        boolean hasSpeaker = speakerName != null && !speakerName.isBlank();
        speakerBox.setVisible(hasSpeaker);
        speakerText.setText(hasSpeaker ? speakerName : "");
        if (prompt.speakerColorHex() != null && !prompt.speakerColorHex().isBlank()) {
            speakerText.setColorHex(prompt.speakerColorHex());
        } else {
            speakerText.setColorHex(ThemePalette.ACCENT_HOVER);
        }

        String lineText = prompt.interpolatedText() != null ? prompt.interpolatedText() : "";
        float cps = prefs.getTextSpeedCps();
        dialogueText.setText(lineText);
        dialogueText.setTypewriterCps(cps);

        fallbackTypewriter = new StyledTextLayout.TypewriterController(TextTagParser.parse(lineText), cps);
        StyledTextLayout.TypewriterController tw = resolveActiveTypewriter();
        lastObservedVisibleChars = tw != null ? tw.getVisibleCharCount() : 0;

        if (prefs.isSelfVoicing()) {
            TextToSpeechService tts = resolveTts();
            if (tts != null) {
                tts.setEnabled(true);
                tts.speak(lineText);
            }
        }
    }

    private StyledTextLayout.TypewriterController resolveActiveTypewriter() {
        StyledTextLayout.TypewriterController widgetTw = dialogueText.getTypewriter();
        return widgetTw != null ? widgetTw : fallbackTypewriter;
    }

    private static boolean canSkipPrompt(ScriptRuntime.DialoguePrompt prompt, PreferencesState prefs) {
        return prompt != null && (prompt.alreadyRead() || prefs.isSkipUnread());
    }

    private static void revealAllImmediate(StyledTextLayout.TypewriterController tw) {
        if (tw == null) {
            return;
        }
        int guard = 0;
        while (!tw.isFinished() && guard++ < 64) {
            tw.clickOrAdvance();
        }
    }

    private void triggerVoiceBleepForNewChars(int prevCount, int nextCount) {
        if (lastPrompt == null || bleepCooldown > 0.0) {
            return;
        }
        String plain = dialogueText.getTypewriter() != null
                ? TextTagParser.parse(dialogueText.getText()).plainText()
                : TextTagParser.parse(lastPrompt.interpolatedText()).plainText();
        int end = Math.min(nextCount, plain.length());
        boolean hasSpeakableChar = false;
        for (int i = Math.max(0, prevCount); i < end; i++) {
            if (!Character.isWhitespace(plain.charAt(i))) {
                hasSpeakableChar = true;
                break;
            }
        }
        if (!hasSpeakableChar) {
            return;
        }

        AudioEngine audio = resolveAudioEngine();
        if (audio != null) {
            audio.playVoiceBleep(computeSpeakerBleepHz(lastPrompt.speakerId()));
        }
        bleepCount++;
        bleepCooldown = BLEEP_THROTTLE_SECONDS;
    }

    private static double computeSpeakerBleepHz(String speakerId) {
        if (speakerId == null || speakerId.isBlank()) {
            return 440.0;
        }
        int hash = Math.abs(speakerId.hashCode());
        return 300.0 + (hash % 280);
    }

    private void toggleSelfVoicing() {
        PreferencesState prefs = session.getPreferencesState();
        boolean next = !prefs.isSelfVoicing();
        prefs.setSelfVoicing(next);
        TextToSpeechService tts = resolveTts();
        if (tts != null) {
            tts.setEnabled(next);
            if (next && lastPrompt != null) {
                tts.speak(lastPrompt.interpolatedText());
            } else if (!next) {
                tts.stop();
            }
        }
        updateStatusIndicators();
    }

    private void performQuickSave() {
        try {
            session.quickSave();
        } catch (IOException ex) {
            session.getEngineApi().logError("Quick save failed: " + ex.getMessage(), ex);
        }
    }

    private void performQuickLoad() {
        try {
            session.quickLoad();
            syncFromRuntime(true);
        } catch (IOException ex) {
            session.getEngineApi().logError("Quick load failed: " + ex.getMessage(), ex);
        }
    }

    private void showScreen(String name) {
        if (screenManager != null) {
            screenManager.showScreen(name);
        }
    }

    private void updateStatusIndicators() {
        skipButton.setSelected(skipActive);
        autoButton.setSelected(autoActive);
        String badge = "";
        if (skipActive) {
            badge = "SKIP >>";
        } else if (autoActive) {
            badge = "AUTO >";
        }
        if (session.getPreferencesState().isSelfVoicing()) {
            badge = badge.isEmpty() ? "TTS [V]" : badge + " | TTS";
        }
        statusBadge.setText(badge);
    }

    private AudioEngine resolveAudioEngine() {
        return explicitAudioEngine != null ? explicitAudioEngine : UiMediaSupport.findAudioEngine(session);
    }

    private TextToSpeechService resolveTts() {
        return explicitTtsService != null ? explicitTtsService : UiMediaSupport.findTtsService(session);
    }
}
