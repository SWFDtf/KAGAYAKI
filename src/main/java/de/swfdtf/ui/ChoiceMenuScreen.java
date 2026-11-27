package de.swfdtf.ui;

import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.state.PersistentAccess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Branching choice menu overlay screen ({@code Z = 300}) rendering {@link ScriptRuntime.MenuPrompt}
 * options, hiding invisible options, graying out disabled options, and displaying a live {@link Bar}
 * countdown timer for timed choices.
 */
public class ChoiceMenuScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "choice_menu";

    private final ScriptSession session;
    private final Fixed rootContainer;
    private final Frame cardFrame;
    private final VBox layoutBox;
    private final TextWidget captionLabel;
    private final Bar timeoutBar;
    private final VBox optionsContainer;
    private final List<TextButton> optionButtons = new ArrayList<>();
    private final Map<Integer, String> disabledReasons = new HashMap<>();
    private final Map<Integer, String> choiceBadges = new HashMap<>();

    private ScriptRuntime.MenuPrompt lastPrompt;
    private boolean numericHotkeysEnabled = true;
    private PersistentAccess choiceHistoryStore;
    private String currentHistoryLabel = "";

    /**
     * Creates a {@code ChoiceMenuScreen} bound to {@code session}.
     *
     * @param session active script session
     */
    public ChoiceMenuScreen(ScriptSession session) {
        super(SCREEN_NAME, SceneLayer.Z_OVERLAY, false);
        this.session = Objects.requireNonNull(session, "session must not be null");

        this.rootContainer = new Fixed();
        this.cardFrame = new Frame();
        UiMediaSupport.styleModalCard(this.cardFrame);
        this.cardFrame.setBackgroundColorHex(ThemePalette.PANEL_GLASS);

        this.layoutBox = new VBox();
        this.layoutBox.setSpacing(14.0f);
        this.layoutBox.setFillWidth(true);

        this.captionLabel = UiMediaSupport.createTitleText("", 26.0f);
        this.captionLabel.setVisible(false);

        this.timeoutBar = new Bar(0.0f, 1.0f);
        this.timeoutBar.setPreferredHeight(12.0f);
        this.timeoutBar.setFillColorHex(ThemePalette.WARNING);
        this.timeoutBar.setVisible(false);

        this.optionsContainer = new VBox();
        this.optionsContainer.setSpacing(12.0f);
        this.optionsContainer.setFillWidth(true);

        this.layoutBox.addChild(captionLabel);
        this.layoutBox.addChild(timeoutBar);
        this.layoutBox.addChild(optionsContainer);
        this.cardFrame.addChild(layoutBox);
        this.rootContainer.addChildAt(cardFrame, 510.0f, 220.0f, 900.0f, 520.0f);

        setRoot(this.rootContainer);
    }

    @Override
    public void onShow() {
        syncFromRuntime(true);
    }

    @Override
    public void update(double dt) {
        if (!isVisible()) {
            return;
        }
        syncFromRuntime(false);
        super.update(dt);
    }

    /**
     * Rebuilds visible choice buttons and updates the countdown {@link Bar} from {@link ScriptRuntime#getActiveMenu()}.
     *
     * @param forceRebuild {@code true} to force button reconstruction
     */
    public void syncFromRuntime(boolean forceRebuild) {
        ScriptRuntime.MenuPrompt prompt = session.getRuntime().getActiveMenu();
        if (prompt == null) {
            if (lastPrompt != null || forceRebuild) {
                lastPrompt = null;
                optionsContainer.clearChildren();
                optionButtons.clear();
                captionLabel.setVisible(false);
                timeoutBar.setVisible(false);
            }
            return;
        }

        if (prompt.timeoutSeconds() != null && prompt.timeoutSeconds() > 0.0) {
            timeoutBar.setVisible(true);
            float maxSec = prompt.timeoutSeconds().floatValue();
            float remSec = (float) Math.max(0.0, prompt.remainingSeconds());
            timeoutBar.setRange(maxSec);
            timeoutBar.setValue(remSec);
            timeoutBar.setFillColorHex((remSec / maxSec) <= 0.25f ? ThemePalette.ERROR : ThemePalette.WARNING);
        } else {
            timeoutBar.setVisible(false);
        }

        boolean structureChanged = forceRebuild || lastPrompt == null
                || !Objects.equals(lastPrompt.caption(), prompt.caption())
                || !Objects.equals(lastPrompt.options(), prompt.options());

        lastPrompt = prompt;
        if (!structureChanged) {
            return;
        }

        if (prompt.caption() != null && !prompt.caption().isBlank()) {
            captionLabel.setText(prompt.caption());
            captionLabel.setVisible(true);
        } else {
            captionLabel.setText("");
            captionLabel.setVisible(false);
        }

        optionsContainer.clearChildren();
        optionButtons.clear();

        for (ScriptRuntime.ChoiceOption opt : prompt.options()) {
            if (!opt.visible()) {
                continue;
            }
            String badge = choiceBadges.get(opt.index());
            String displayText = (badge != null && !badge.isBlank()) ? badge + " " + opt.text() : opt.text();
            TextButton btn = new TextButton(displayText, () -> {
                if (opt.enabled()) {
                    if (choiceHistoryStore != null) {
                        UiPolishAndServices.ChoiceHistoryTracker.recordChoice(
                                choiceHistoryStore, currentHistoryLabel, opt.text());
                    }
                    session.choose(opt.index());
                    syncFromRuntime(true);
                }
            });
            btn.setPreferredSize(850.0f, 54.0f);
            btn.setEnabled(opt.enabled());
            boolean previouslyPicked = choiceHistoryStore != null
                    && UiPolishAndServices.ChoiceHistoryTracker.wasPreviouslyChosen(
                            choiceHistoryStore, currentHistoryLabel, opt.text());
            if (!opt.enabled()) {
                btn.setBackgroundColorHex(ThemePalette.PANEL_SURFACE);
                btn.setBorderColorHex(ThemePalette.BORDER);
                String reason = disabledReasons.get(opt.index());
                if (reason != null && !reason.isBlank()) {
                    btn.setTooltipText(reason);
                }
                btn.setAlpha(0.55f);
            } else {
                btn.setBackgroundColorHex(previouslyPicked ? ThemePalette.PANEL_SURFACE : ThemePalette.ELEVATED);
                btn.setBorderColorHex(ThemePalette.ACCENT_IDLE);
            }
            btn.setBorderThickness(1.5f);
            btn.setCornerRadius(8.0f);
            optionButtons.add(btn);
            optionsContainer.addChild(btn);
        }
    }

    /**
     * Returns an unmodifiable list of currently rendered visible choice buttons.
     *
     * @return visible option buttons
     */
    public List<TextButton> getOptionButtons() {
        return Collections.unmodifiableList(optionButtons);
    }

    /**
     * Returns the countdown timer progress bar widget.
     *
     * @return timeout bar widget
     */
    public Bar getTimeoutBar() {
        return timeoutBar;
    }

    /**
     * Returns the optional menu caption label widget.
     *
     * @return caption label
     */
    public TextWidget getCaptionLabel() {
        return captionLabel;
    }

    /**
     * Enables or disables {@code 1..9} numeric hotkeys for selecting visible options ({@code #0406}).
     *
     * @param enabled {@code true} to allow numeric choice shortcuts
     */
    public void setNumericHotkeysEnabled(boolean enabled) {
        this.numericHotkeysEnabled = enabled;
    }

    /**
     * Returns whether numeric choice hotkeys are enabled.
     *
     * @return {@code true} if enabled
     */
    public boolean isNumericHotkeysEnabled() {
        return numericHotkeysEnabled;
    }

    /**
     * Attempts to activate option {@code digit1To9} (1-based) via numeric hotkey ({@code #0406}).
     *
     * @param digit1To9 key digit in {@code [1..9]}
     * @return {@code true} if a matching enabled option was triggered
     */
    public boolean handleNumericHotkey(int digit1To9) {
        if (!numericHotkeysEnabled || digit1To9 < 1 || digit1To9 > 9) {
            return false;
        }
        int idx = digit1To9 - 1;
        if (idx >= optionButtons.size()) {
            return false;
        }
        TextButton btn = optionButtons.get(idx);
        if (!btn.isEnabled()) {
            return false;
        }
        btn.click();
        return true;
    }

    /**
     * Configures an explanatory tooltip displayed on a disabled choice option ({@code #0390}).
     *
     * @param optionIndex original 0-based choice option index
     * @param reason      human-readable lock explanation (e.g., {@code "Requires Courage >= 20"})
     */
    public void setDisabledReason(int optionIndex, String reason) {
        if (reason == null || reason.isBlank()) {
            disabledReasons.remove(optionIndex);
        } else {
            disabledReasons.put(optionIndex, reason.trim());
        }
    }

    /**
     * Configures an optional prefix badge icon/label next to a choice option ({@code #0405}).
     *
     * @param optionIndex original 0-based choice option index
     * @param badge       badge token (e.g., {@code "[♥]"} or {@code "[★]"})
     */
    public void setChoiceBadge(int optionIndex, String badge) {
        if (badge == null || badge.isBlank()) {
            choiceBadges.remove(optionIndex);
        } else {
            choiceBadges.put(optionIndex, badge.trim());
        }
    }

    /**
     * Binds persistent choice history tracking for visual marking of previously chosen options ({@code #0422}).
     *
     * @param persistent  persistent storage
     * @param labelScope  logical menu scope label
     */
    public void bindChoiceHistory(PersistentAccess persistent, String labelScope) {
        this.choiceHistoryStore = persistent;
        this.currentHistoryLabel = labelScope != null ? labelScope : "";
    }
}
