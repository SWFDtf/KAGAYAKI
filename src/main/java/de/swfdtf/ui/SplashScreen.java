package de.swfdtf.ui;

import de.swfdtf.core.EngineVersion;
import de.swfdtf.graphics.SceneLayer;
import org.lwjgl.glfw.GLFW;

/**
 * Cold-start boot splash and loading overlay ({@code #0382}) displaying the KAGAYAKI brand,
 * {@link EngineVersion} banner, load progress bar, and click/key skip in quick-test/demo mode.
 */
public class SplashScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "splash_screen";

    private final Fixed rootContainer;
    private final TextWidget brandLabel;
    private final TextWidget versionLabel;
    private final TextWidget statusLabel;
    private final Bar progressBar;

    private float progress = 0.0f;
    private boolean skippable = true;
    private boolean dismissed = false;
    private Runnable onComplete;

    /**
     * Creates a splash screen ({@code Z = 550}) showing engine version metadata and progress.
     */
    public SplashScreen() {
        super(SCREEN_NAME, SceneLayer.Z_TOAST + 50, true);

        this.rootContainer = new Fixed();
        Frame bg = new Frame();
        bg.setBackgroundColorHex(ThemePalette.CANVAS_BG);
        this.rootContainer.addChildAt(bg, 0.0f, 0.0f, 1920.0f, 1080.0f);

        VBox centerBox = new VBox();
        centerBox.setSpacing(18.0f);
        centerBox.setAlignCenterX(true);

        this.brandLabel = UiMediaSupport.createTitleText("KAGAYAKI", 56.0f);
        this.brandLabel.setColorHex(ThemePalette.ACCENT_HOVER);

        this.versionLabel = UiMediaSupport.createMutedText(EngineVersion.formatBanner(), 22.0f);
        this.statusLabel = UiMediaSupport.createMutedText("Initialisiere Assets & Subsysteme...", 20.0f);

        this.progressBar = new Bar(0.0f, 1.0f);
        this.progressBar.setPreferredSize(560.0f, 16.0f);
        this.progressBar.setFillColorHex(ThemePalette.ACCENT_IDLE);

        centerBox.addChild(this.brandLabel);
        centerBox.addChild(this.versionLabel);
        centerBox.addChild(this.progressBar);
        centerBox.addChild(this.statusLabel);

        this.rootContainer.addChildAt(centerBox, 560.0f, 380.0f, 800.0f, 320.0f);
        setRoot(this.rootContainer);

        addKeyBinding(GLFW.GLFW_KEY_SPACE, 0, this::trySkip);
        addKeyBinding(GLFW.GLFW_KEY_ENTER, 0, this::trySkip);
    }

    /**
     * Updates normalized load progress {@code [0, 1]} and status caption.
     *
     * @param value   progress fraction
     * @param message optional status message (null keeps existing)
     */
    public void setProgress(float value, String message) {
        this.progress = Float.isFinite(value) ? Math.clamp(value, 0.0f, 1.0f) : 0.0f;
        this.progressBar.setValue(this.progress);
        if (message != null && !message.isBlank()) {
            this.statusLabel.setText(message);
        }
        if (this.progress >= 1.0f && !dismissed) {
            dismiss();
        }
    }

    /**
     * Returns current splash loading progress in {@code [0, 1]}.
     *
     * @return progress fraction
     */
    public float getProgress() {
        return progress;
    }

    /**
     * Enables or disables skipping via click/Space/Enter (e.g., demo/quick-test mode).
     *
     * @param skippable {@code true} to allow skipping before progress reaches {@code 1.0}
     * @return {@code this}
     */
    public SplashScreen setSkippable(boolean skippable) {
        this.skippable = skippable;
        return this;
    }

    /**
     * Returns whether skipping is permitted.
     *
     * @return {@code true} if skippable
     */
    public boolean isSkippable() {
        return skippable;
    }

    /**
     * Sets callback invoked once when splash loading finishes or is skipped.
     *
     * @param onComplete completion callback (nullable)
     * @return {@code this}
     */
    public SplashScreen setOnComplete(Runnable onComplete) {
        this.onComplete = onComplete;
        return this;
    }

    /**
     * Skips the splash screen if {@link #isSkippable()} is {@code true}.
     *
     * @return {@code true} if skipped now
     */
    public boolean trySkip() {
        if (!skippable || dismissed) {
            return false;
        }
        this.progress = 1.0f;
        this.progressBar.setValue(1.0f);
        dismiss();
        return true;
    }

    private void dismiss() {
        if (dismissed) {
            return;
        }
        dismissed = true;
        setVisible(false);
        if (onComplete != null) {
            onComplete.run();
        }
    }

    /**
     * Returns whether this splash screen has completed and dismissed.
     *
     * @return {@code true} when dismissed
     */
    public boolean isDismissed() {
        return dismissed;
    }

    /**
     * Returns displayed version banner text.
     *
     * @return formatted engine version string
     */
    public String getVersionBannerText() {
        return versionLabel.getText();
    }
}
