package de.swfdtf.ui;

import org.lwjgl.glfw.GLFW;

/**
 * Reusable modal confirmation dialog screen ({@code Z = 950}) with Confirm / Cancel buttons
 * and {@code Escape} cancellation support.
 */
public class ConfirmationDialogScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "confirm_dialog";

    private final ScreenManager screenManager;
    private final TextWidget titleLabel;
    private final TextWidget messageLabel;
    private final TextButton confirmButton;
    private final TextButton cancelButton;

    private Runnable onConfirmAction;
    private Runnable onCancelAction;

    /**
     * Creates a {@code ConfirmationDialogScreen} managed by {@code screenManager}.
     *
     * @param screenManager UI screen manager
     */
    public ConfirmationDialogScreen(ScreenManager screenManager) {
        super(SCREEN_NAME, 950, true);
        this.screenManager = screenManager;

        Frame backdrop = new Frame();
        UiMediaSupport.styleBackdrop(backdrop);
        backdrop.setPadding(560.0f, 360.0f);

        Frame card = new Frame();
        UiMediaSupport.styleModalCard(card);
        card.setBorderColorHex(ThemePalette.WARNING);

        VBox content = new VBox();
        content.setSpacing(18.0f);
        content.setFillWidth(true);

        this.titleLabel = UiMediaSupport.createTitleText("Bestätigen", 30.0f);
        this.messageLabel = new TextWidget("");
        this.messageLabel.setFontSize(22.0f).setColorHex(ThemePalette.TEXT_PRIMARY);

        HBox buttons = new HBox();
        buttons.setSpacing(16.0f);
        buttons.setAlignCenterY(true);

        this.confirmButton = UiMediaSupport.createActionButton("Überschreiben", 180.0f, 44.0f, this::confirm);
        this.confirmButton.setBorderColorHex(ThemePalette.ERROR);
        this.cancelButton = UiMediaSupport.createActionButton("Abbrechen", 160.0f, 44.0f, this::cancel);

        buttons.addChild(this.confirmButton);
        buttons.addChild(this.cancelButton);

        content.addChild(this.titleLabel);
        content.addChild(this.messageLabel);
        content.addChild(buttons);

        card.addChild(content);
        backdrop.addChild(card);
        setRoot(backdrop);

        addKeyBinding(GLFW.GLFW_KEY_ESCAPE, this::cancel);
    }

    /**
     * Configures labels and callbacks before displaying this confirmation popup.
     *
     * @param title        dialog heading text
     * @param message      body confirmation prompt
     * @param confirmLabel confirm button label
     * @param cancelLabel  cancel button label
     * @param onConfirm    callback invoked on confirmation (nullable)
     * @param onCancel     callback invoked on cancellation (nullable)
     * @return {@code this}
     */
    public ConfirmationDialogScreen configure(
            String title,
            String message,
            String confirmLabel,
            String cancelLabel,
            Runnable onConfirm,
            Runnable onCancel
    ) {
        this.titleLabel.setText(title != null ? title : "Bestätigen");
        this.messageLabel.setText(message != null ? message : "");
        this.confirmButton.setText(confirmLabel != null ? confirmLabel : "OK");
        this.cancelButton.setText(cancelLabel != null ? cancelLabel : "Abbrechen");
        this.onConfirmAction = onConfirm;
        this.onCancelAction = onCancel;
        return this;
    }

    /**
     * Dismisses the modal and runs the configured confirm action.
     */
    public void confirm() {
        hideDialog();
        if (onConfirmAction != null) {
            onConfirmAction.run();
        }
    }

    /**
     * Dismisses the modal and runs the configured cancel action.
     */
    public void cancel() {
        hideDialog();
        if (onCancelAction != null) {
            onCancelAction.run();
        }
    }

    /**
     * Returns the confirm action button.
     *
     * @return confirm button
     */
    public TextButton getConfirmButton() {
        return confirmButton;
    }

    /**
     * Returns the cancel action button.
     *
     * @return cancel button
     */
    public TextButton getCancelButton() {
        return cancelButton;
    }

    private void hideDialog() {
        if (screenManager != null) {
            screenManager.hideScreen(SCREEN_NAME);
        } else {
            setVisible(false);
            onHide();
        }
    }
}
