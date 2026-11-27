package de.swfdtf.ui;

import de.swfdtf.graphics.SceneLayer;

/**
 * Modal asynchronous progress spinner overlay ({@code #0404}) for background `.vpak` exports
 * or integrity audits, updating smoothly without blocking the render loop.
 */
public class ProgressSpinnerModal extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "progress_spinner_modal";

    private final Fixed rootContainer;
    private final Frame cardFrame;
    private final TextWidget titleWidget;
    private final TextWidget statusWidget;
    private final Bar progressBar;

    private boolean active = false;
    private boolean indeterminate = false;
    private float spinnerAngleDegrees = 0.0f;
    private float progress = 0.0f;

    /**
     * Creates a modal progress spinner overlay ({@code Z = 940}).
     */
    public ProgressSpinnerModal() {
        super(SCREEN_NAME, SceneLayer.Z_INSPECTOR - 60, true);
        setVisible(false);

        this.rootContainer = new Fixed();
        this.cardFrame = new Frame();
        UiMediaSupport.styleModalCard(this.cardFrame);

        VBox box = new VBox();
        box.setSpacing(12.0f);
        box.setAlignCenterX(true);
        this.titleWidget = UiMediaSupport.createTitleText("Arbeite...", 26.0f);
        this.progressBar = new Bar(0.0f, 1.0f);
        this.progressBar.setPreferredSize(440.0f, 14.0f);
        this.progressBar.setFillColorHex(ThemePalette.ACCENT_HOVER);
        this.statusWidget = UiMediaSupport.createMutedText("", 18.0f);
        box.addChild(this.titleWidget);
        box.addChild(this.progressBar);
        box.addChild(this.statusWidget);

        this.cardFrame.addChild(box);
        this.rootContainer.addChildAt(this.cardFrame, 660.0f, 410.0f, 600.0f, 220.0f);
        setRoot(this.rootContainer);
    }

    /**
     * Starts an asynchronous progress modal.
     *
     * @param title         task headline
     * @param indeterminate {@code true} if percentage is unknown
     */
    public void startJob(String title, boolean indeterminate) {
        this.titleWidget.setText(title != null ? title : "Wird ausgeführt...");
        this.statusWidget.setText("");
        this.indeterminate = indeterminate;
        this.progress = 0.0f;
        this.spinnerAngleDegrees = 0.0f;
        this.progressBar.setValue(0.0f);
        this.active = true;
        setVisible(true);
        layout(1920.0f, 1080.0f);
    }

    /**
     * Updates normalized progress {@code [0, 1]} and status caption from worker feedback.
     *
     * @param value  progress fraction
     * @param status optional status line
     */
    public void updateProgress(float value, String status) {
        this.progress = Float.isFinite(value) ? Math.clamp(value, 0.0f, 1.0f) : 0.0f;
        this.progressBar.setValue(this.progress);
        if (status != null) {
            this.statusWidget.setText(status);
        }
    }

    /**
     * Completes and closes this modal overlay cleanly.
     */
    public void completeJob() {
        this.active = false;
        this.progress = 1.0f;
        this.progressBar.setValue(1.0f);
        setVisible(false);
    }

    @Override
    public void update(double dt) {
        if (!active || !isVisible() || dt <= 0.0) {
            return;
        }
        spinnerAngleDegrees = (spinnerAngleDegrees + (float) (dt * 240.0)) % 360.0f;
        super.update(dt);
    }

    /**
     * Returns whether an async job modal is active.
     *
     * @return {@code true} if active
     */
    public boolean isJobActive() {
        return active && isVisible();
    }

    /**
     * Returns whether the job runs in indeterminate mode.
     *
     * @return {@code true} if indeterminate
     */
    public boolean isIndeterminate() {
        return indeterminate;
    }

    /**
     * Returns current spinner rotation angle in degrees {@code [0, 360)}.
     *
     * @return spinner angle
     */
    public float getSpinnerAngleDegrees() {
        return spinnerAngleDegrees;
    }

    /**
     * Returns current job progress in {@code [0, 1]}.
     *
     * @return progress fraction
     */
    public float getProgress() {
        return progress;
    }
}
