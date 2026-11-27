package de.swfdtf.ui;

import de.swfdtf.graphics.SceneLayer;

/**
 * Transient chapter title banner overlay ({@code #0366}, e.g., {@code "Kapitel 2 — Der dunkle Wald"})
 * fading out automatically after a configured duration and immediately skippable.
 */
public class ChapterBannerOverlay extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "chapter_banner";

    private static final double FADE_IN_SECONDS = 0.25;
    private static final double FADE_OUT_SECONDS = 0.35;

    private final Fixed rootContainer;
    private final Frame bannerCard;
    private final TextWidget titleWidget;
    private final TextWidget subtitleWidget;

    private double durationSeconds = 3.0;
    private double elapsedSeconds = 0.0;
    private boolean active = false;

    /**
     * Creates a non-modal chapter banner overlay ({@code Z = 510}).
     */
    public ChapterBannerOverlay() {
        super(SCREEN_NAME, SceneLayer.Z_TOAST + 10, false);
        setVisible(false);

        this.rootContainer = new Fixed();
        this.bannerCard = new Frame();
        this.bannerCard.setBackgroundColorHex(ThemePalette.PANEL_GLASS)
                .setBorderColorHex(ThemePalette.ACCENT_IDLE)
                .setBorderThickness(2.0f)
                .setCornerRadius(10.0f)
                .setPadding(24.0f, 14.0f);

        VBox box = new VBox();
        box.setSpacing(6.0f);
        box.setAlignCenterX(true);
        this.titleWidget = UiMediaSupport.createTitleText("", 30.0f);
        this.titleWidget.setColorHex(ThemePalette.ACCENT_HOVER);
        this.subtitleWidget = UiMediaSupport.createMutedText("", 20.0f);
        box.addChild(this.titleWidget);
        box.addChild(this.subtitleWidget);

        this.bannerCard.addChild(box);
        this.rootContainer.addChildAt(this.bannerCard, 460.0f, 110.0f, 1000.0f, 110.0f);
        setRoot(this.rootContainer);
    }

    /**
     * Displays a chapter banner for {@code durationSec} seconds.
     *
     * @param title       chapter headline
     * @param subtitle    optional chapter subtitle
     * @param durationSec total visible duration in seconds
     */
    public void showBanner(String title, String subtitle, double durationSec) {
        this.titleWidget.setText(title != null ? title : "");
        this.subtitleWidget.setText(subtitle != null ? subtitle : "");
        this.subtitleWidget.setVisible(subtitle != null && !subtitle.isBlank());
        this.durationSeconds = (Double.isFinite(durationSec) && durationSec > 0.1) ? durationSec : 3.0;
        this.elapsedSeconds = 0.0;
        this.active = true;
        this.bannerCard.setAlpha(0.0f);
        setVisible(true);
        layout(1920.0f, 1080.0f);
    }

    /**
     * Immediately hides the chapter banner (e.g. when player clicks Skip).
     */
    public void skip() {
        this.active = false;
        setVisible(false);
    }

    @Override
    public void update(double dt) {
        if (!active || !isVisible() || dt <= 0.0) {
            return;
        }
        elapsedSeconds += dt;
        if (elapsedSeconds >= durationSeconds) {
            skip();
            return;
        }
        float alpha = 1.0f;
        if (elapsedSeconds < FADE_IN_SECONDS) {
            alpha = (float) (elapsedSeconds / FADE_IN_SECONDS);
        } else if (elapsedSeconds > durationSeconds - FADE_OUT_SECONDS) {
            alpha = (float) ((durationSeconds - elapsedSeconds) / FADE_OUT_SECONDS);
        }
        bannerCard.setAlpha(Math.clamp(alpha, 0.0f, 1.0f));
        super.update(dt);
    }

    /**
     * Returns whether a banner is currently displayed.
     *
     * @return {@code true} if active and visible
     */
    public boolean isBannerActive() {
        return active && isVisible();
    }

    /**
     * Returns current banner alpha in {@code [0, 1]}.
     *
     * @return card opacity
     */
    public float getBannerAlpha() {
        return bannerCard.getAlpha();
    }
}
