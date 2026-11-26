package de.swfdtf.ui;

import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.state.GalleryManager;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Built-in Dark-Slate CG Gallery screen ({@code Z = 400}, modal) powered by {@link GalleryManager},
 * supporting unlocked/locked CG cards, multi-variation cycling, and full-screen zoom mode.
 */
public class GalleryScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "gallery";

    /**
     * Card view model for a gallery entry.
     */
    public static final class GalleryCardView {
        private final GalleryManager.GalleryEntry entry;
        private final boolean unlocked;
        private final UiMediaSupport.PreviewImageNode previewNode;
        private final TextButton selectButton;

        private GalleryCardView(
                GalleryManager.GalleryEntry entry,
                boolean unlocked,
                UiMediaSupport.PreviewImageNode previewNode,
                TextButton selectButton
        ) {
            this.entry = entry;
            this.unlocked = unlocked;
            this.previewNode = previewNode;
            this.selectButton = selectButton;
        }

        /**
         * Returns the entry value.
         *
         * @return the resulting value
         */
        public GalleryManager.GalleryEntry getEntry() {
            return entry;
        }

        /**
         * Returns whether unlocked is active.
         *
         * @return the resulting value
         */
        public boolean isUnlocked() {
            return unlocked;
        }

        /**
         * Returns the selectButton value.
         *
         * @return the resulting value
         */
        public TextButton getSelectButton() {
            return selectButton;
        }

        /**
         * Returns the displayedAssetPath value.
         *
         * @return the resulting value
         */
        public String getDisplayedAssetPath() {
            return previewNode.getAssetPath();
        }
    }

    private final ScriptSession session;
    private final ScreenManager screenManager;

    private final TextWidget headerCounter;
    private final VpGrid grid;
    private final Frame viewerPanel;
    private final UiMediaSupport.PreviewImageNode viewerImage;
    private final TextWidget viewerTitle;
    private final TextWidget viewerDescription;
    private final TextWidget variationLabel;
    private final TextButton prevVariationButton;
    private final TextButton nextVariationButton;
    private final TextButton zoomButton;

    private final Frame zoomOverlay;
    private final UiMediaSupport.PreviewImageNode zoomImage;

    private final List<GalleryCardView> cardViews = new ArrayList<>();
    private GalleryManager.GalleryEntry selectedEntry;
    private int variationIndex = 0;
    private boolean zoomed = false;

    /**
     * Creates a {@code GalleryScreen} bound to {@code session} and {@code screenManager}.
     *
     * @param session       active script session
     * @param screenManager UI screen manager
     */
    public GalleryScreen(ScriptSession session, ScreenManager screenManager) {
        super(SCREEN_NAME, SceneLayer.Z_MODAL, true);
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.screenManager = screenManager;

        Fixed root = new Fixed();

        Frame backdrop = new Frame();
        UiMediaSupport.styleBackdrop(backdrop);
        backdrop.setPadding(80.0f, 50.0f);

        Frame panel = new Frame();
        UiMediaSupport.styleModalCard(panel);

        VBox mainBox = new VBox();
        mainBox.setSpacing(16.0f);
        mainBox.setFillWidth(true);

        HBox topBar = new HBox();
        topBar.setSpacing(18.0f);
        topBar.setAlignCenterY(true);
        topBar.addChild(UiMediaSupport.createTitleText("CG Gallery", 34.0f));
        this.headerCounter = UiMediaSupport.createMutedText("0 / 0 Unlocked", 20.0f);
        topBar.addChild(headerCounter);
        topBar.addChild(UiMediaSupport.createActionButton("Back", 120.0f, 38.0f, this::closeScreen));

        HBox bodySplit = new HBox();
        bodySplit.setSpacing(24.0f);

        this.grid = new VpGrid(2, 340.0f, 220.0f);
        this.grid.setSpacing(16.0f, 16.0f);
        this.grid.setPreferredSize(740.0f, 760.0f);

        this.viewerPanel = new Frame();
        UiMediaSupport.styleElevatedCard(this.viewerPanel);
        this.viewerPanel.setPreferredSize(880.0f, 760.0f);

        VBox viewerBox = new VBox();
        viewerBox.setSpacing(12.0f);
        viewerBox.setFillWidth(true);

        this.viewerImage = new UiMediaSupport.PreviewImageNode();
        this.viewerImage.setPreferredSize(840.0f, 480.0f);
        this.viewerTitle = UiMediaSupport.createTitleText("Select an unlocked CG", 24.0f);
        this.viewerDescription = UiMediaSupport.createMutedText("", 18.0f);
        this.variationLabel = UiMediaSupport.createMutedText("", 18.0f);

        HBox viewerControls = new HBox();
        viewerControls.setSpacing(12.0f);
        viewerControls.setAlignCenterY(true);
        this.prevVariationButton = UiMediaSupport.createActionButton("Prev Variation", 170.0f, 38.0f,
                () -> stepVariation(-1));
        this.nextVariationButton = UiMediaSupport.createActionButton("Next Variation", 170.0f, 38.0f,
                () -> stepVariation(1));
        this.zoomButton = UiMediaSupport.createActionButton("Fullscreen Zoom", 190.0f, 38.0f,
                this::toggleZoom);
        viewerControls.addChild(prevVariationButton);
        viewerControls.addChild(nextVariationButton);
        viewerControls.addChild(zoomButton);

        viewerBox.addChild(viewerImage);
        viewerBox.addChild(viewerTitle);
        viewerBox.addChild(viewerDescription);
        viewerBox.addChild(variationLabel);
        viewerBox.addChild(viewerControls);
        viewerPanel.addChild(viewerBox);

        bodySplit.addChild(grid);
        bodySplit.addChild(viewerPanel);

        mainBox.addChild(topBar);
        mainBox.addChild(bodySplit);
        panel.addChild(mainBox);
        backdrop.addChild(panel);

        this.zoomOverlay = new Frame() {
            @Override
            protected boolean onMouseDown(float lx, float ly, int button) {
                if (zoomed) {
                    setZoomed(false);
                    return true;
                }
                return false;
            }
        };
        this.zoomOverlay.setBackgroundColorHex(ThemePalette.CANVAS_BG);
        this.zoomOverlay.setPadding(24.0f);
        this.zoomImage = new UiMediaSupport.PreviewImageNode();
        this.zoomOverlay.addChild(this.zoomImage);
        this.zoomOverlay.setVisible(false);

        root.addChildAt(backdrop, 0.0f, 0.0f, 1920.0f, 1080.0f);
        root.addChildAt(zoomOverlay, 0.0f, 0.0f, 1920.0f, 1080.0f);
        setRoot(root);

        addKeyBinding(GLFW.GLFW_KEY_ESCAPE, () -> {
            if (zoomed) {
                setZoomed(false);
            } else {
                closeScreen();
            }
        });
        addKeyBinding(GLFW.GLFW_KEY_LEFT, () -> stepVariation(-1));
        addKeyBinding(GLFW.GLFW_KEY_RIGHT, () -> stepVariation(1));

        refreshGallery();
    }

    @Override
    public void onShow() {
        refreshGallery();
    }

    /**
     * Reloads gallery entries from {@link GalleryManager} and updates cards and viewer state.
     */
    public void refreshGallery() {
        GalleryManager gm = session.getGalleryManager();
        grid.clearChildren();
        cardViews.clear();

        headerCounter.setText(gm.getUnlockedCount() + " / " + gm.getTotalCount() + " Unlocked");

        for (GalleryManager.GalleryEntry entry : gm.getAllEntries()) {
            boolean unlocked = gm.isUnlocked(entry.id());
            Frame card = new Frame();
            UiMediaSupport.styleElevatedCard(card);

            VBox col = new VBox();
            col.setSpacing(8.0f);
            col.setFillWidth(true);

            UiMediaSupport.PreviewImageNode thumb = new UiMediaSupport.PreviewImageNode();
            thumb.setPreferredSize(310.0f, 130.0f);
            if (unlocked) {
                thumb.setAssetPath(entry.thumbnailPath());
            } else {
                thumb.setPlaceholderLabel("LOCKED");
            }

            TextButton btn = UiMediaSupport.createActionButton(
                    unlocked ? entry.title() : "Locked CG",
                    310.0f,
                    36.0f,
                    () -> selectEntry(entry)
            );
            btn.setEnabled(unlocked);
            if (!unlocked) {
                btn.setAlpha(0.45f);
            }

            col.addChild(thumb);
            col.addChild(btn);
            card.addChild(col);
            grid.addChild(card);

            cardViews.add(new GalleryCardView(entry, unlocked, thumb, btn));
        }

        if (selectedEntry != null && !gm.isUnlocked(selectedEntry.id())) {
            selectedEntry = null;
        }
        updateViewer();
    }

    /**
     * Selects an unlocked gallery entry and resets variation index to {@code 0}.
     *
     * @param entry gallery entry to select
     */
    public void selectEntry(GalleryManager.GalleryEntry entry) {
        if (entry == null || !session.getGalleryManager().isUnlocked(entry.id())) {
            return;
        }
        this.selectedEntry = entry;
        this.variationIndex = 0;
        updateViewer();
    }

    /**
     * Cycles the active variation index by {@code delta} ({@code -1} or {@code +1}).
     *
     * @param delta relative step offset
     */
    public void stepVariation(int delta) {
        if (selectedEntry == null) {
            return;
        }
        List<String> paths = resolveVariationPaths(selectedEntry);
        if (paths.isEmpty()) {
            return;
        }
        variationIndex = Math.floorMod(variationIndex + delta, paths.size());
        updateViewer();
    }

    /**
     * Toggles full-screen CG zoom mode when an entry is selected.
     */
    public void toggleZoom() {
        if (selectedEntry == null) {
            return;
        }
        setZoomed(!zoomed);
    }

    /**
     * Sets the zoomed value.
     *
     * @param zoomed the zoomed argument
     */
    public void setZoomed(boolean zoomed) {
        this.zoomed = zoomed && selectedEntry != null;
        zoomOverlay.setVisible(this.zoomed);
        updateViewer();
    }

    /**
     * Returns whether zoomed is active.
     *
     * @return the resulting value
     */
    public boolean isZoomed() {
        return zoomed;
    }

    private void updateViewer() {
        if (selectedEntry == null) {
            viewerImage.setAssetPath(null);
            viewerTitle.setText("Select an unlocked CG");
            viewerDescription.setText("");
            variationLabel.setText("");
            prevVariationButton.setEnabled(false);
            nextVariationButton.setEnabled(false);
            zoomButton.setEnabled(false);
            return;
        }
        List<String> paths = resolveVariationPaths(selectedEntry);
        String activePath = paths.isEmpty()
                ? selectedEntry.thumbnailPath()
                : paths.get(Math.min(variationIndex, paths.size() - 1));
        viewerImage.setAssetPath(activePath);
        zoomImage.setAssetPath(activePath);

        viewerTitle.setText(selectedEntry.title());
        viewerDescription.setText(selectedEntry.description() != null ? selectedEntry.description() : "");
        int count = Math.max(1, paths.size());
        variationLabel.setText("Variation " + (variationIndex + 1) + " / " + count);

        boolean canCycle = count > 1;
        prevVariationButton.setEnabled(canCycle);
        nextVariationButton.setEnabled(canCycle);
        zoomButton.setEnabled(true);
    }

    private static List<String> resolveVariationPaths(GalleryManager.GalleryEntry entry) {
        if (entry.variationPaths() != null && !entry.variationPaths().isEmpty()) {
            return entry.variationPaths();
        }
        if (entry.thumbnailPath() != null && !entry.thumbnailPath().isBlank()) {
            return List.of(entry.thumbnailPath());
        }
        return List.of();
    }

    private void closeScreen() {
        setZoomed(false);
        if (screenManager != null) {
            screenManager.hideScreen(SCREEN_NAME);
        } else {
            setVisible(false);
            onHide();
        }
    }

    /**
     * Returns the cardViews value.
     *
     * @return the resulting value
     */
    public List<GalleryCardView> getCardViews() {
        return Collections.unmodifiableList(cardViews);
    }

    /**
     * Returns the selectedEntry value.
     *
     * @return the resulting value
     */
    public GalleryManager.GalleryEntry getSelectedEntry() {
        return selectedEntry;
    }

    /**
     * Returns the variationIndex value.
     *
     * @return the resulting value
     */
    public int getVariationIndex() {
        return variationIndex;
    }

    /**
     * Returns the activeVariationPath value.
     *
     * @return the resulting value
     */
    public String getActiveVariationPath() {
        return viewerImage.getAssetPath();
    }
}
