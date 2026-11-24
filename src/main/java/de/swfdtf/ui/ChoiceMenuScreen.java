package de.swfdtf.ui;

import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    private ScriptRuntime.MenuPrompt lastPrompt;

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
            timeoutBar.setRange(prompt.timeoutSeconds().floatValue());
            timeoutBar.setValue((float) Math.max(0.0, prompt.remainingSeconds()));
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
            TextButton btn = new TextButton(opt.text(), () -> {
                if (opt.enabled()) {
                    session.choose(opt.index());
                    syncFromRuntime(true);
                }
            });
            btn.setPreferredSize(850.0f, 54.0f);
            btn.setEnabled(opt.enabled());
            btn.setBackgroundColorHex(opt.enabled() ? ThemePalette.ELEVATED : ThemePalette.PANEL_SURFACE);
            btn.setBorderColorHex(opt.enabled() ? ThemePalette.ACCENT_IDLE : ThemePalette.BORDER);
            btn.setBorderThickness(1.5f);
            btn.setCornerRadius(8.0f);
            if (!opt.enabled()) {
                btn.setAlpha(0.55f);
            }
            optionButtons.add(btn);
            optionsContainer.addChild(btn);
        }
    }

    /** @return unmodifiable list of currently rendered visible choice buttons */
    public List<TextButton> getOptionButtons() {
        return Collections.unmodifiableList(optionButtons);
    }

    /** @return countdown timer progress bar widget */
    public Bar getTimeoutBar() {
        return timeoutBar;
    }

    public TextWidget getCaptionLabel() {
        return captionLabel;
    }
}
