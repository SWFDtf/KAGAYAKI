package de.swfdtf.ui;

import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.state.AchievementManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Non-modal notification toast overlay ({@code Z = 500}) polling
 * {@link AchievementManager#pollToast()} for animated achievement banners and displaying a
 * live Skip / Auto status pill.
 */
public class NotificationToastOverlay extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "notification_toast";

    /** Total display duration for a single toast banner in seconds. */
    public static final double DEFAULT_TOAST_DURATION = 4.0;

    private static final double FADE_IN_SECONDS = 0.25;
    private static final double FADE_OUT_SECONDS = 0.35;

    /**
     * Queued toast notification payload.
     *
     * @param title    headline text
     * @param subtitle body description
     */
    public record ToastPayload(String title, String subtitle) {
    }

    /**
     * Stacked multi-toast entry with icon badge, progress bar ratio, and computed vertical Y slot ({@code #0373}).
     *
     * @param title         headline text
     * @param subtitle      body description
     * @param progressRatio normalized progress in {@code [0, 1]}
     * @param iconBadge     optional icon tag
     * @param slotY         non-overlapping logical Y position
     * @param ageSeconds    elapsed display seconds
     */
    public record StackedToast(
            String title,
            String subtitle,
            float progressRatio,
            String iconBadge,
            float slotY,
            double ageSeconds
    ) {
    }

    private final ScriptSession session;
    private final ScreenManager screenManager;
    private final Fixed rootContainer;
    private final Frame toastCard;
    private final TextWidget toastHeader;
    private final TextWidget toastTitle;
    private final TextWidget toastSubtitle;
    private final Frame statusPill;
    private final TextWidget statusPillText;

    private final Deque<ToastPayload> pendingToasts = new ArrayDeque<>();
    private final List<StackedToast> stackedToasts = new ArrayList<>();
    private ToastPayload activeToast;
    private double activeToastElapsed = 0.0;
    private boolean manualSkipStatus = false;
    private boolean manualAutoStatus = false;

    /**
     * Creates a {@code NotificationToastOverlay} bound to {@code session} and {@code screenManager}.
     *
     * @param session       active script session
     * @param screenManager UI screen manager (nullable)
     */
    public NotificationToastOverlay(ScriptSession session, ScreenManager screenManager) {
        super(SCREEN_NAME, SceneLayer.Z_TOAST, false);
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.screenManager = screenManager;

        this.rootContainer = new Fixed();

        this.toastCard = new Frame();
        this.toastCard.setBackgroundColorHex(ThemePalette.PANEL_SURFACE)
                .setBorderColorHex(ThemePalette.ACTIVE_SELECTED)
                .setBorderThickness(2.0f)
                .setCornerRadius(10.0f)
                .setPadding(16.0f, 12.0f);
        this.toastCard.setVisible(false);

        VBox toastBox = new VBox();
        toastBox.setSpacing(4.0f);
        this.toastHeader = UiMediaSupport.createTitleText("ACHIEVEMENT UNLOCKED", 14.0f);
        this.toastHeader.setColorHex(ThemePalette.ACTIVE_SELECTED);
        this.toastTitle = UiMediaSupport.createTitleText("", 22.0f);
        this.toastSubtitle = UiMediaSupport.createMutedText("", 16.0f);
        toastBox.addChild(toastHeader);
        toastBox.addChild(toastTitle);
        toastBox.addChild(toastSubtitle);
        this.toastCard.addChild(toastBox);

        this.statusPill = new Frame();
        this.statusPill.setBackgroundColorHex(ThemePalette.PANEL_GLASS)
                .setBorderColorHex(ThemePalette.ACCENT_IDLE)
                .setBorderThickness(1.5f)
                .setCornerRadius(18.0f)
                .setPadding(16.0f, 8.0f);
        this.statusPill.setVisible(false);
        this.statusPillText = UiMediaSupport.createTitleText("", 18.0f);
        this.statusPillText.setColorHex(ThemePalette.ACCENT_HOVER);
        this.statusPill.addChild(this.statusPillText);

        this.rootContainer.addChildAt(this.statusPill, 28.0f, 24.0f, 220.0f, 40.0f);
        this.rootContainer.addChildAt(this.toastCard, 1410.0f, 24.0f, 480.0f, 108.0f);
        setRoot(this.rootContainer);
    }

    @Override
    public void update(double dt) {
        if (!isVisible()) {
            return;
        }
        pollAchievementToasts();
        advanceStackedToasts(Math.max(0.0, dt));
        updateActiveToastAnimation(dt);
        updateStatusPill();
        super.update(dt);
    }

    /**
     * Pushes a custom toast notification banner onto the display queue.
     *
     * @param title    banner headline
     * @param subtitle banner detail text
     */
    public void pushToast(String title, String subtitle) {
        pendingToasts.addLast(new ToastPayload(
                title != null ? title : "",
                subtitle != null ? subtitle : ""
        ));
    }

    private void pollAchievementToasts() {
        AchievementManager am = session.getAchievementManager();
        if (am == null) {
            return;
        }
        Optional<AchievementManager.AchievementToast> polled;
        while ((polled = am.pollToast()).isPresent()) {
            AchievementManager.AchievementDef def = polled.get().achievement();
            pendingToasts.addLast(new ToastPayload(
                    def.title(),
                    def.description() != null ? def.description() : ""
            ));
        }
    }

    private void updateActiveToastAnimation(double dt) {
        if (activeToast == null && !pendingToasts.isEmpty()) {
            activeToast = pendingToasts.removeFirst();
            activeToastElapsed = 0.0;
            toastTitle.setText(activeToast.title());
            toastSubtitle.setText(activeToast.subtitle());
            toastCard.setVisible(true);
            toastCard.setAlpha(0.0f);
        }

        if (activeToast == null) {
            toastCard.setVisible(false);
            return;
        }

        activeToastElapsed += Math.max(0.0, dt);
        if (activeToastElapsed >= DEFAULT_TOAST_DURATION) {
            activeToast = null;
            toastCard.setVisible(false);
            return;
        }

        float alpha = 1.0f;
        if (activeToastElapsed < FADE_IN_SECONDS) {
            alpha = (float) (activeToastElapsed / FADE_IN_SECONDS);
        } else if (activeToastElapsed > DEFAULT_TOAST_DURATION - FADE_OUT_SECONDS) {
            alpha = (float) ((DEFAULT_TOAST_DURATION - activeToastElapsed) / FADE_OUT_SECONDS);
        }
        toastCard.setAlpha(Math.max(0.0f, Math.min(1.0f, alpha)));
    }

    private void updateStatusPill() {
        boolean skip = manualSkipStatus;
        boolean auto = manualAutoStatus;
        if (screenManager != null
                && screenManager.getScreen(AdvDialogueScreen.SCREEN_NAME) instanceof AdvDialogueScreen adv) {
            skip = skip || adv.isSkipActive();
            auto = auto || adv.isAutoActive();
        }
        if (skip) {
            statusPill.setVisible(true);
            statusPillText.setText("SKIPPING >>");
            statusPillText.setColorHex(ThemePalette.ACTIVE_SELECTED);
        } else if (auto) {
            statusPill.setVisible(true);
            statusPillText.setText("AUTO >");
            statusPillText.setColorHex(ThemePalette.ACCENT_HOVER);
        } else {
            statusPill.setVisible(false);
            statusPillText.setText("");
        }
    }

    /**
     * Sets the skipPillActive value.
     *
     * @param active the active argument
     */
    public void setSkipPillActive(boolean active) {
        this.manualSkipStatus = active;
        updateStatusPill();
    }

    /**
     * Sets the autoPillActive value.
     *
     * @param active the active argument
     */
    public void setAutoPillActive(boolean active) {
        this.manualAutoStatus = active;
        updateStatusPill();
    }

    /**
     * Returns whether toastVisible is active.
     *
     * @return the resulting value
     */
    public boolean isToastVisible() {
        return activeToast != null && toastCard.isVisible();
    }

    /**
     * Returns the activeToast value.
     *
     * @return the resulting value
     */
    public ToastPayload getActiveToast() {
        return activeToast;
    }

    /**
     * Returns the pendingToastCount value.
     *
     * @return the resulting value
     */
    public int getPendingToastCount() {
        return pendingToasts.size();
    }

    /**
     * Returns the toastCard value.
     *
     * @return the resulting value
     */
    public Frame getToastCard() {
        return toastCard;
    }

    /**
     * Returns the statusPill value.
     *
     * @return the resulting value
     */
    public Frame getStatusPill() {
        return statusPill;
    }

    /**
     * Returns the statusPillText value.
     *
     * @return the resulting value
     */
    public TextWidget getStatusPillText() {
        return statusPillText;
    }

    /**
     * Pushes a toast onto the non-overlapping vertical toast stack ({@code #0373}).
     *
     * @param title         toast headline
     * @param subtitle      toast subtitle
     * @param progressRatio progress indicator ratio {@code [0..1]}
     * @param iconBadge     optional icon tag
     */
    public void pushStackedToast(String title, String subtitle, float progressRatio, String iconBadge) {
        float y = 24.0f + stackedToasts.size() * 104.0f;
        stackedToasts.add(new StackedToast(
                title != null ? title : "",
                subtitle != null ? subtitle : "",
                Float.isFinite(progressRatio) ? Math.clamp(progressRatio, 0.0f, 1.0f) : 1.0f,
                iconBadge != null ? iconBadge : "",
                y,
                0.0
        ));
    }

    /**
     * Returns immutable snapshot of active non-overlapping stacked toasts ({@code #0373}).
     *
     * @return currently visible stacked toasts
     */
    public List<StackedToast> getVisibleStackedToasts() {
        return Collections.unmodifiableList(stackedToasts);
    }

    private void advanceStackedToasts(double dt) {
        if (stackedToasts.isEmpty() || dt <= 0.0) {
            return;
        }
        Iterator<StackedToast> it = stackedToasts.iterator();
        List<StackedToast> updated = new ArrayList<>();
        int slotIdx = 0;
        while (it.hasNext()) {
            StackedToast item = it.next();
            double nextAge = item.ageSeconds() + dt;
            if (nextAge < DEFAULT_TOAST_DURATION) {
                updated.add(new StackedToast(
                        item.title(),
                        item.subtitle(),
                        item.progressRatio(),
                        item.iconBadge(),
                        24.0f + slotIdx * 104.0f,
                        nextAge
                ));
                slotIdx++;
            }
        }
        stackedToasts.clear();
        stackedToasts.addAll(updated);
    }
}
