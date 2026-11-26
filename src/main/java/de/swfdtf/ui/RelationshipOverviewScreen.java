package de.swfdtf.ui;

import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.state.RelationshipAffinityTracker;
import org.lwjgl.glfw.GLFW;

import java.util.Map;
import java.util.Objects;

/**
 * Character Relationship &amp; Affinity overview modal screen ({@code Z = 460}) displaying
 * character scores, {@link RelationshipAffinityTracker.RelationshipTier} badges, and progress {@link Bar}s.
 */
public class RelationshipOverviewScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "relationships";

    private static final float MIN_AFFINITY = -100.0f;
    private static final float MAX_AFFINITY = 100.0f;

    private final RelationshipAffinityTracker tracker;
    private final ScreenManager screenManager;
    private final VBox rowsContainer;

    /**
     * Creates a {@code RelationshipOverviewScreen}.
     *
     * @param tracker       relationship affinity tracker
     * @param screenManager UI screen manager
     */
    public RelationshipOverviewScreen(RelationshipAffinityTracker tracker, ScreenManager screenManager) {
        super(SCREEN_NAME, SceneLayer.Z_MODAL + 60, true);
        this.tracker = Objects.requireNonNull(tracker, "tracker must not be null");
        this.screenManager = screenManager;

        Frame backdrop = new Frame();
        UiMediaSupport.styleBackdrop(backdrop);
        backdrop.setPadding(240.0f, 110.0f);

        Frame panel = new Frame();
        UiMediaSupport.styleModalCard(panel);

        VBox layout = new VBox();
        layout.setSpacing(18.0f);
        layout.setFillWidth(true);

        HBox header = new HBox();
        header.setSpacing(20.0f);
        header.setAlignCenterY(true);
        header.addChild(UiMediaSupport.createTitleText("Beziehungen & Vertrauen", 34.0f));
        header.addChild(UiMediaSupport.createActionButton("Zurück", 140.0f, 40.0f, this::closeScreen));

        Viewport viewport = new Viewport();
        viewport.setPreferredSize(1320.0f, 660.0f);
        this.rowsContainer = new VBox();
        this.rowsContainer.setSpacing(14.0f);
        this.rowsContainer.setFillWidth(true);
        viewport.addChild(this.rowsContainer);

        layout.addChild(header);
        layout.addChild(viewport);
        panel.addChild(layout);
        backdrop.addChild(panel);
        setRoot(backdrop);

        addKeyBinding(GLFW.GLFW_KEY_ESCAPE, this::closeScreen);
    }

    @Override
    public void onShow() {
        refreshRelationships();
    }

    /**
     * Rebuilds character affinity rows and progress bars from {@link RelationshipAffinityTracker}.
     */
    public void refreshRelationships() {
        rowsContainer.clearChildren();
        Map<String, Integer> all = tracker.getAllAffinities();
        for (Map.Entry<String, Integer> entry : all.entrySet()) {
            String charId = entry.getKey();
            int points = entry.getValue();
            RelationshipAffinityTracker.RelationshipTier tier =
                    RelationshipAffinityTracker.RelationshipTier.fromPoints(points);

            Frame card = new Frame();
            card.setBackgroundColorHex(ThemePalette.ELEVATED)
                    .setBorderColorHex(ThemePalette.BORDER)
                    .setBorderThickness(1.0f)
                    .setCornerRadius(8.0f)
                    .setPadding(14.0f);
            card.setPreferredSize(1280.0f, 76.0f);

            HBox row = new HBox();
            row.setSpacing(18.0f);
            row.setAlignCenterY(true);

            TextWidget nameLbl = new TextWidget(charId);
            nameLbl.setFontSize(22.0f).setColorHex(ThemePalette.TEXT_PRIMARY);
            nameLbl.setPreferredSize(240.0f, 36.0f);

            TextWidget tierLbl = new TextWidget(tier.name() + " (" + points + ")");
            tierLbl.setFontSize(20.0f).setColorHex(resolveTierColor(tier));
            tierLbl.setPreferredSize(240.0f, 36.0f);

            float normalized = Math.clamp(points - MIN_AFFINITY, 0.0f, MAX_AFFINITY - MIN_AFFINITY);
            Bar bar = new Bar(normalized, MAX_AFFINITY - MIN_AFFINITY);
            bar.setFillColorHex(resolveTierColor(tier));
            bar.setPreferredSize(520.0f, 24.0f);

            row.addChild(nameLbl);
            row.addChild(tierLbl);
            row.addChild(bar);
            card.addChild(row);
            rowsContainer.addChild(card);
        }
        if (screenManager != null) {
            layout(screenManager.getLogicalWidth(), screenManager.getLogicalHeight());
        }
    }

    private static String resolveTierColor(RelationshipAffinityTracker.RelationshipTier tier) {
        return switch (tier) {
            case HOSTILE -> ThemePalette.ERROR;
            case STRANGER, ACQUAINTANCE -> ThemePalette.WARNING;
            case FRIEND -> ThemePalette.ACCENT_HOVER;
            case TRUSTED, DEVOTED -> ThemePalette.SUCCESS;
        };
    }

    private void closeScreen() {
        if (screenManager != null) {
            screenManager.hideScreen(SCREEN_NAME);
        } else {
            setVisible(false);
            onHide();
        }
    }
}
