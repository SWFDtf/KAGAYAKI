package de.swfdtf.ui;

import de.swfdtf.graphics.SceneLayer;

/**
 * Non-modal floating world-interaction prompt badge ({@code Z = Z_SCREENS + 30}) displaying
 * dynamic keybinding hints such as {@code "[E] Untersuchen"} near interactive WASD objects.
 */
public class ActionPromptOverlayScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "action_prompt";

    private static final float BADGE_WIDTH = 240.0f;
    private static final float BADGE_HEIGHT = 44.0f;

    private final Fixed rootContainer;
    private final Frame promptCard;
    private final TextWidget promptText;

    /**
     * Creates an initially hidden {@code ActionPromptOverlayScreen}.
     */
    public ActionPromptOverlayScreen() {
        super(SCREEN_NAME, SceneLayer.Z_SCREENS + 30, false);
        setVisible(false);

        this.rootContainer = new Fixed();
        this.promptCard = new Frame();
        this.promptCard.setBackgroundColorHex(ThemePalette.PANEL_SURFACE)
                .setBorderColorHex(ThemePalette.ACCENT_HOVER)
                .setBorderThickness(1.5f)
                .setCornerRadius(8.0f)
                .setPadding(12.0f, 8.0f);

        this.promptText = UiMediaSupport.createTitleText("", 20.0f);
        this.promptText.setVisible(false);
        this.promptCard.addChild(this.promptText);

        this.rootContainer.addChildAt(this.promptCard, 16.0f, 16.0f, BADGE_WIDTH, BADGE_HEIGHT);
        setRoot(this.rootContainer);
    }

    /**
     * Updates visibility, screen position, and keybinding label of the world interaction badge.
     *
     * @param visible     {@code true} to show the badge, {@code false} to hide it
     * @param anchorX     world/stage X coordinate near the interactive object
     * @param anchorY     world/stage Y coordinate near the interactive object
     * @param keyLabel    formatted key label from {@code KeybindingRemapper} (e.g., {@code "E"})
     * @param actionLabel human-readable interaction verb (e.g., {@code "Untersuchen"})
     */
    public void updatePrompt(
            boolean visible,
            float anchorX,
            float anchorY,
            String keyLabel,
            String actionLabel
    ) {
        if (!visible) {
            this.promptText.setText("");
            this.promptText.setVisible(false);
            setVisible(false);
            return;
        }
        String safeKey = (keyLabel == null || keyLabel.isBlank()) ? "E" : keyLabel.trim();
        String safeAction = (actionLabel == null || actionLabel.isBlank()) ? "Untersuchen" : actionLabel.trim();
        this.promptText.setText("[" + safeKey + "] " + safeAction);
        this.promptText.setVisible(true);

        float clampedX = Math.max(16.0f, Math.min(1920.0f - BADGE_WIDTH - 16.0f, anchorX - BADGE_WIDTH * 0.5f));
        float clampedY = Math.max(16.0f, Math.min(1080.0f - BADGE_HEIGHT - 16.0f, anchorY - BADGE_HEIGHT - 16.0f));
        this.rootContainer.setChildAlignment(
                this.promptCard,
                new Fixed.AlignmentSpec(clampedX, clampedY, 0.0f, 0.0f, true, true, false, false)
        );
        setVisible(true);
    }

    /**
     * Returns the badge label widget displaying {@code "[Key] Action"}.
     *
     * @return prompt label widget
     */
    public TextWidget getPromptText() {
        return promptText;
    }
}
