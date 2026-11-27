package de.swfdtf.ui;

import de.swfdtf.graphics.SceneLayer;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Smooth vertical scrolling credits screen ({@code #0374}) with fast-forward support and automatic
 * completion callback invocation upon reaching the bottom of the credits roll.
 */
public class CreditsScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "credits";

    private static final float START_SCROLL_Y = 980.0f;
    private static final float DEFAULT_SPEED_PX_PER_SEC = 75.0f;
    private static final float LINE_STEP_PX = 42.0f;

    /**
     * Single headed section inside the credits roll.
     *
     * @param heading section title (e.g. {@code "Engine & Architecture"})
     * @param entries credited names/roles under that heading
     */
    public record CreditSection(String heading, List<String> entries) {
    }

    private final Fixed rootContainer;
    private final Frame backdropFrame;
    private final VBox rollColumn;
    private final List<CreditSection> sections = new ArrayList<>();

    private double scrollSpeedPxPerSec = DEFAULT_SPEED_PX_PER_SEC;
    private boolean fastForward = false;
    private float currentOffsetY = START_SCROLL_Y;
    private float totalRollHeight = 400.0f;
    private boolean finished = false;
    private Runnable onFinished;

    /**
     * Creates a modal {@code CreditsScreen} ({@code Z = 400}).
     */
    public CreditsScreen() {
        super(SCREEN_NAME, SceneLayer.Z_MODAL, true);
        setVisible(false);

        this.rootContainer = new Fixed();
        this.backdropFrame = new Frame();
        this.backdropFrame.setBackgroundColorHex(ThemePalette.CANVAS_BG);
        this.rootContainer.addChildAt(this.backdropFrame, 0.0f, 0.0f, 1920.0f, 1080.0f);

        this.rollColumn = new VBox();
        this.rollColumn.setSpacing(14.0f);
        this.rollColumn.setAlignCenterX(true);
        this.rootContainer.addChildAt(this.rollColumn, 360.0f, START_SCROLL_Y, 1200.0f, totalRollHeight);
        setRoot(this.rootContainer);

        addKeyBinding(GLFW.GLFW_KEY_ESCAPE, 0, this::finishNow);
        addKeyBinding(GLFW.GLFW_KEY_SPACE, 0, () -> setFastForward(!fastForward));
    }

    /**
     * Populates sections for the scrolling credits roll.
     *
     * @param newSections ordered credits sections
     * @return {@code this}
     */
    public CreditsScreen setSections(List<CreditSection> newSections) {
        Objects.requireNonNull(newSections, "newSections must not be null");
        this.sections.clear();
        this.sections.addAll(newSections);
        this.rollColumn.clearChildren();

        int lines = 0;
        for (CreditSection sec : newSections) {
            TextWidget head = UiMediaSupport.createTitleText(sec.heading(), 32.0f);
            head.setColorHex(ThemePalette.ACCENT_HOVER);
            this.rollColumn.addChild(head);
            lines++;
            for (String person : sec.entries()) {
                this.rollColumn.addChild(UiMediaSupport.createTitleText(person, 24.0f));
                lines++;
            }
            lines++;
        }
        this.totalRollHeight = Math.max(240.0f, lines * LINE_STEP_PX);
        return this;
    }

    /**
     * Configures callback executed when scrolling finishes or user skips out.
     *
     * @param onFinished completion callback (nullable)
     * @return {@code this}
     */
    public CreditsScreen setOnFinished(Runnable onFinished) {
        this.onFinished = onFinished;
        return this;
    }

    /**
     * Configures base vertical scroll speed in logical pixels per second.
     *
     * @param speedPxPerSec base speed ({@code > 0})
     * @return {@code this}
     */
    public CreditsScreen setScrollSpeed(double speedPxPerSec) {
        this.scrollSpeedPxPerSec = (Double.isFinite(speedPxPerSec) && speedPxPerSec > 0.0)
                ? speedPxPerSec : DEFAULT_SPEED_PX_PER_SEC;
        return this;
    }

    /**
     * Toggles 4x fast-forward scroll multiplier.
     *
     * @param fastForward {@code true} for 4x speed
     */
    public void setFastForward(boolean fastForward) {
        this.fastForward = fastForward;
    }

    /**
     * Returns whether fast-forward scroll is active.
     *
     * @return {@code true} if fast-forwarding
     */
    public boolean isFastForward() {
        return fastForward;
    }

    @Override
    public void onShow() {
        this.currentOffsetY = START_SCROLL_Y;
        this.finished = false;
        this.fastForward = false;
        this.rootContainer.clearChildren();
        this.rootContainer.addChildAt(this.backdropFrame, 0.0f, 0.0f, 1920.0f, 1080.0f);
        this.rootContainer.addChildAt(this.rollColumn, 360.0f, currentOffsetY, 1200.0f, totalRollHeight);
        layout(1920.0f, 1080.0f);
    }

    @Override
    public void update(double dt) {
        if (!isVisible() || finished || dt <= 0.0) {
            return;
        }
        double speed = scrollSpeedPxPerSec * (fastForward ? 4.0 : 1.0);
        currentOffsetY -= (float) (speed * dt);
        this.rootContainer.clearChildren();
        this.rootContainer.addChildAt(this.backdropFrame, 0.0f, 0.0f, 1920.0f, 1080.0f);
        this.rootContainer.addChildAt(this.rollColumn, 360.0f, currentOffsetY, 1200.0f, totalRollHeight);
        layout(1920.0f, 1080.0f);
        super.update(dt);

        if (currentOffsetY + totalRollHeight <= 0.0f) {
            finishNow();
        }
    }

    /**
     * Marks credits as completed, hides the screen, and fires {@code onFinished}.
     */
    public void finishNow() {
        if (finished) {
            return;
        }
        finished = true;
        setVisible(false);
        if (onFinished != null) {
            onFinished.run();
        }
    }

    /**
     * Returns current vertical roll offset in logical pixels.
     *
     * @return Y offset of credits column
     */
    public float getCurrentOffsetY() {
        return currentOffsetY;
    }

    /**
     * Returns whether the credits roll has finished.
     *
     * @return {@code true} when completed
     */
    public boolean isFinished() {
        return finished;
    }

    /**
     * Returns immutable list of configured credit sections.
     *
     * @return credit sections
     */
    public List<CreditSection> getSections() {
        return Collections.unmodifiableList(sections);
    }
}
