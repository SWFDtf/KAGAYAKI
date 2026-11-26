package de.swfdtf.ui;

import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.state.AchievementManager;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Built-in Dark-Slate Achievements screen ({@code Z = 400}, modal) displaying unlocked,
 * locked, and masked hidden achievements from {@link AchievementManager}.
 */
public class AchievementsScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "achievements";

    /**
     * View representation of a single achievement row.
     *
     * @param definition       backing achievement definition
     * @param unlocked         whether the achievement is unlocked
     * @param titleLabel       title text widget
     * @param descriptionLabel description text widget
     * @param statusLabel      status badge text widget
     */
    public record AchievementRowView(
            AchievementManager.AchievementDef definition,
            boolean unlocked,
            TextWidget titleLabel,
            TextWidget descriptionLabel,
            TextWidget statusLabel
    ) {
    }

    private final ScriptSession session;
    private final ScreenManager screenManager;
    private final TextWidget summaryLabel;
    private final Bar progressBar;
    private final Viewport viewport;
    private final VBox listBox;
    private final List<AchievementRowView> rows = new ArrayList<>();

    /**
     * Creates an {@code AchievementsScreen} bound to {@code session} and {@code screenManager}.
     *
     * @param session       active script session
     * @param screenManager UI screen manager
     */
    public AchievementsScreen(ScriptSession session, ScreenManager screenManager) {
        super(SCREEN_NAME, SceneLayer.Z_MODAL, true);
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.screenManager = screenManager;

        Frame backdrop = new Frame();
        UiMediaSupport.styleBackdrop(backdrop);
        backdrop.setPadding(160.0f, 80.0f);

        Frame panel = new Frame();
        UiMediaSupport.styleModalCard(panel);

        VBox mainBox = new VBox();
        mainBox.setSpacing(16.0f);
        mainBox.setFillWidth(true);

        HBox header = new HBox();
        header.setSpacing(18.0f);
        header.setAlignCenterY(true);
        header.addChild(UiMediaSupport.createTitleText("Achievements", 34.0f));
        this.summaryLabel = UiMediaSupport.createMutedText("0 / 0 Unlocked", 22.0f);
        header.addChild(summaryLabel);
        header.addChild(UiMediaSupport.createActionButton("Back", 120.0f, 38.0f, this::closeScreen));

        this.progressBar = new Bar(0.0f, 1.0f);
        this.progressBar.setPreferredSize(1500.0f, 16.0f);
        this.progressBar.setFillColorHex(ThemePalette.ACCENT_IDLE);

        this.viewport = new Viewport();
        this.viewport.setPreferredSize(1500.0f, 690.0f);
        this.listBox = new VBox();
        this.listBox.setSpacing(10.0f);
        this.listBox.setFillWidth(true);
        this.viewport.addChild(listBox);

        mainBox.addChild(header);
        mainBox.addChild(progressBar);
        mainBox.addChild(viewport);
        panel.addChild(mainBox);
        backdrop.addChild(panel);
        setRoot(backdrop);

        addKeyBinding(GLFW.GLFW_KEY_ESCAPE, this::closeScreen);
        refreshAchievements();
    }

    @Override
    public void onShow() {
        refreshAchievements();
    }

    /**
     * Reloads achievement definitions and unlock status from {@link AchievementManager}.
     */
    public void refreshAchievements() {
        AchievementManager am = session.getAchievementManager();
        int unlockedCount = am.getUnlockedCount();
        int totalCount = am.getTotalCount();
        summaryLabel.setText(unlockedCount + " / " + totalCount + " Unlocked");
        progressBar.setRange(Math.max(1, totalCount));
        progressBar.setValue(unlockedCount);

        listBox.clearChildren();
        rows.clear();

        List<AchievementManager.AchievementDef> defs = am.getAllAchievements();
        if (defs.isEmpty()) {
            listBox.addChild(UiMediaSupport.createMutedText("No achievements registered.", 22.0f));
            return;
        }

        for (AchievementManager.AchievementDef def : defs) {
            boolean unlocked = am.isUnlocked(def.id());
            boolean masked = !unlocked && def.hidden();

            Frame card = new Frame();
            UiMediaSupport.styleElevatedCard(card);
            if (!unlocked) {
                card.setAlpha(0.75f);
            }

            VBox body = new VBox();
            body.setSpacing(6.0f);

            HBox row = new HBox();
            row.setSpacing(14.0f);
            row.setAlignCenterY(true);

            String title = masked ? "???" : def.title();
            String desc = masked ? "Hidden achievement." : (def.description() != null ? def.description() : "");
            String status = unlocked ? "UNLOCKED" : (def.hidden() ? "HIDDEN" : "LOCKED");

            TextWidget titleLbl = UiMediaSupport.createTitleText(title, 22.0f);
            TextWidget statusLbl = UiMediaSupport.createTitleText(status, 18.0f);
            statusLbl.setColorHex(unlocked ? ThemePalette.SUCCESS : ThemePalette.TEXT_MUTED);
            TextWidget descLbl = UiMediaSupport.createMutedText(desc, 18.0f);

            row.addChild(titleLbl);
            row.addChild(statusLbl);
            body.addChild(row);
            body.addChild(descLbl);
            card.addChild(body);
            listBox.addChild(card);

            rows.add(new AchievementRowView(def, unlocked, titleLbl, descLbl, statusLbl));
        }
    }

    private void closeScreen() {
        if (screenManager != null) {
            screenManager.hideScreen(SCREEN_NAME);
        } else {
            setVisible(false);
            onHide();
        }
    }

    /**
     * Returns the rendered achievement row views.
     *
     * @return immutable list of achievement rows
     */
    public List<AchievementRowView> getRows() {
        return Collections.unmodifiableList(rows);
    }

    /**
     * Returns the unlocked count summary label.
     *
     * @return summary label
     */
    public TextWidget getSummaryLabel() {
        return summaryLabel;
    }

    /**
     * Returns the overall achievement completion progress bar.
     *
     * @return progress bar
     */
    public Bar getProgressBar() {
        return progressBar;
    }
}
