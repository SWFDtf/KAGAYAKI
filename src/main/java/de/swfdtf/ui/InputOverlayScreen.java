package de.swfdtf.ui;

import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;

import java.util.Objects;

/**
 * Modal text input overlay screen ({@code Z = 300}) rendering {@link ScriptRuntime.InputPrompt}
 * with a {@link TextInput} field and Confirm {@link TextButton}.
 */
public class InputOverlayScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "input_overlay";

    private final ScriptSession session;
    private final Frame backdrop;
    private final WindowBox dialogWindow;
    private final TextWidget promptLabel;
    private final TextInput textInput;
    private final TextButton confirmButton;

    private ScriptRuntime.InputPrompt lastPrompt;

    /**
     * Creates an {@code InputOverlayScreen} bound to {@code session}.
     *
     * @param session active script session
     */
    public InputOverlayScreen(ScriptSession session) {
        super(SCREEN_NAME, SceneLayer.Z_OVERLAY, true);
        this.session = Objects.requireNonNull(session, "session must not be null");

        this.backdrop = new Frame();
        UiMediaSupport.styleBackdrop(this.backdrop);

        Fixed centerHolder = new Fixed();
        this.dialogWindow = new WindowBox("Input Required");
        UiMediaSupport.styleModalCard(this.dialogWindow);

        VBox content = new VBox();
        content.setSpacing(18.0f);
        content.setFillWidth(true);

        this.promptLabel = UiMediaSupport.createTitleText("", 24.0f);
        this.textInput = new TextInput("", this::submitCurrentText);
        this.textInput.setPreferredSize(580.0f, 48.0f);
        this.textInput.setPlaceholder("Enter text...");

        this.confirmButton = UiMediaSupport.createActionButton("Confirm", 180.0f, 46.0f,
                () -> submitCurrentText(textInput.getText()));
        this.confirmButton.setBorderColorHex(ThemePalette.ACCENT_IDLE);

        HBox actions = new HBox();
        actions.setSpacing(12.0f);
        actions.addChild(this.confirmButton);

        content.addChild(this.promptLabel);
        content.addChild(this.textInput);
        content.addChild(actions);
        this.dialogWindow.addChild(content);

        centerHolder.addChildAt(this.dialogWindow, 620.0f, 360.0f, 680.0f, 280.0f);
        this.backdrop.addChild(centerHolder);
        setRoot(this.backdrop);
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
     * Synchronizes prompt text, default value, allowed characters, and max length from runtime.
     */
    public void syncFromRuntime(boolean force) {
        ScriptRuntime.InputPrompt prompt = session.getRuntime().getActiveInput();
        if (prompt == null) {
            lastPrompt = null;
            return;
        }
        if (!force && Objects.equals(prompt, lastPrompt)) {
            return;
        }
        lastPrompt = prompt;
        promptLabel.setText(prompt.prompt() != null ? prompt.prompt() : "Enter value:");
        textInput.setAllowChars(prompt.allowChars());
        textInput.setMaxLength(prompt.maxLength());
        textInput.setText(prompt.defaultValue() != null ? prompt.defaultValue() : "");
        textInput.setFocused(true);
    }

    /**
     * Submits the current input text to {@link ScriptSession#submitInput(String)}.
     *
     * @param text submitted value
     */
    public void submitCurrentText(String text) {
        session.submitInput(text != null ? text : "");
        lastPrompt = null;
    }

    public TextWidget getPromptLabel() {
        return promptLabel;
    }

    public TextInput getTextInput() {
        return textInput;
    }

    public TextButton getConfirmButton() {
        return confirmButton;
    }
}
