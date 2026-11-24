package de.swfdtf.ui;

import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.state.SaveManager;
import de.swfdtf.state.SaveSlotData;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Dual-mode Save/Load screen ({@code Z = 400}, modal) supporting paginated 6-slot grids
 * across 5 pages, Quick Save / Auto Save filter tabs, custom save naming, PNG thumbnail
 * previews, metadata display, and slot deletion.
 */
public class SaveLoadScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "save_load";

    /** Number of save cards rendered per page. */
    public static final int SLOTS_PER_PAGE = 6;

    /** Number of manual save pages ({@code 1..5}). */
    public static final int TOTAL_PAGES = 5;

    /** Active interaction mode. */
    public enum Mode {
        SAVE,
        LOAD
    }

    /** Slot filter category tab. */
    public enum FilterTab {
        MANUAL,
        QUICK,
        AUTO
    }

    /**
     * Interactive slot card model exposed for UI binding and verification.
     */
    public static final class SlotCardView {
        private final String slotId;
        private final Frame cardNode;
        private final UiMediaSupport.PreviewImageNode previewNode;
        private final TextWidget titleLabel;
        private final TextWidget metaLabel;
        private final TextWidget previewTextLabel;
        private final TextButton actionButton;
        private final TextButton deleteButton;
        private final boolean occupied;

        private SlotCardView(
                String slotId,
                Frame cardNode,
                UiMediaSupport.PreviewImageNode previewNode,
                TextWidget titleLabel,
                TextWidget metaLabel,
                TextWidget previewTextLabel,
                TextButton actionButton,
                TextButton deleteButton,
                boolean occupied
        ) {
            this.slotId = slotId;
            this.cardNode = cardNode;
            this.previewNode = previewNode;
            this.titleLabel = titleLabel;
            this.metaLabel = metaLabel;
            this.previewTextLabel = previewTextLabel;
            this.actionButton = actionButton;
            this.deleteButton = deleteButton;
            this.occupied = occupied;
        }

        public String getSlotId() { return slotId; }
        public Frame getCardNode() { return cardNode; }
        public TextWidget getTitleLabel() { return titleLabel; }
        public TextWidget getMetaLabel() { return metaLabel; }
        public TextWidget getPreviewTextLabel() { return previewTextLabel; }
        public TextButton getActionButton() { return actionButton; }
        public TextButton getDeleteButton() { return deleteButton; }
        public boolean isOccupied() { return occupied; }
        public boolean hasThumbnail() { return previewNode.hasImageSource(); }
        public int getThumbnailWidth() { return previewNode.getDecodedWidth(); }
        public int getThumbnailHeight() { return previewNode.getDecodedHeight(); }
    }

    private final ScriptSession session;
    private final ScreenManager screenManager;
    private final TextWidget headerTitle;
    private final TextInput customNameInput;
    private final TextButton manualTabButton;
    private final TextButton quickTabButton;
    private final TextButton autoTabButton;
    private final HBox pageBar;
    private final List<TextButton> pageButtons = new ArrayList<>();
    private final Grid slotGrid;
    private final List<SlotCardView> visibleCards = new ArrayList<>();

    private Mode mode = Mode.LOAD;
    private FilterTab filterTab = FilterTab.MANUAL;
    private int currentPage = 0;

    /**
     * Creates a {@code SaveLoadScreen} bound to {@code session} and {@code screenManager}.
     *
     * @param session       active script session
     * @param screenManager UI screen manager
     */
    public SaveLoadScreen(ScriptSession session, ScreenManager screenManager) {
        super(SCREEN_NAME, SceneLayer.Z_MODAL, true);
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.screenManager = screenManager;

        Frame backdrop = new Frame();
        UiMediaSupport.styleBackdrop(backdrop);
        backdrop.setPadding(90.0f, 60.0f);

        Frame panel = new Frame();
        UiMediaSupport.styleModalCard(panel);

        VBox mainLayout = new VBox();
        mainLayout.setSpacing(18.0f);
        mainLayout.setFillWidth(true);

        HBox topBar = new HBox();
        topBar.setSpacing(18.0f);
        topBar.setAlignCenterY(true);

        this.headerTitle = UiMediaSupport.createTitleText("Load Game", 34.0f);
        this.manualTabButton = UiMediaSupport.createActionButton("Save Slots", 150.0f, 40.0f,
                () -> setFilterTab(FilterTab.MANUAL));
        this.quickTabButton = UiMediaSupport.createActionButton("Quick Save", 150.0f, 40.0f,
                () -> setFilterTab(FilterTab.QUICK));
        this.autoTabButton = UiMediaSupport.createActionButton("Auto Save", 150.0f, 40.0f,
                () -> setFilterTab(FilterTab.AUTO));
        TextButton closeBtn = UiMediaSupport.createActionButton("Back", 120.0f, 40.0f, this::closeScreen);

        topBar.addChild(headerTitle);
        topBar.addChild(manualTabButton);
        topBar.addChild(quickTabButton);
        topBar.addChild(autoTabButton);
        topBar.addChild(closeBtn);

        HBox saveNameRow = new HBox();
        saveNameRow.setSpacing(12.0f);
        saveNameRow.setAlignCenterY(true);
        saveNameRow.addChild(UiMediaSupport.createMutedText("Save Label:", 20.0f));
        this.customNameInput = new TextInput("", text -> {});
        this.customNameInput.setPreferredSize(480.0f, 40.0f);
        this.customNameInput.setPlaceholder("Optional custom save title...");
        saveNameRow.addChild(this.customNameInput);

        this.slotGrid = new Grid(3, 2);
        this.slotGrid.setSpacing(18.0f, 18.0f);

        this.pageBar = new HBox();
        this.pageBar.setSpacing(10.0f);
        this.pageBar.setAlignCenterY(true);
        for (int p = 0; p < TOTAL_PAGES; p++) {
            final int pageIdx = p;
            TextButton pageBtn = UiMediaSupport.createActionButton(String.valueOf(p + 1), 54.0f, 38.0f,
                    () -> setPage(pageIdx));
            pageButtons.add(pageBtn);
            pageBar.addChild(pageBtn);
        }

        mainLayout.addChild(topBar);
        mainLayout.addChild(saveNameRow);
        mainLayout.addChild(slotGrid);
        mainLayout.addChild(pageBar);
        panel.addChild(mainLayout);
        backdrop.addChild(panel);
        setRoot(backdrop);

        addKeyBinding(GLFW.GLFW_KEY_ESCAPE, this::closeScreen);
        refreshSlots();
    }

    @Override
    public void onShow() {
        refreshSlots();
    }

    /**
     * Switches between {@link Mode#SAVE} and {@link Mode#LOAD} and refreshes slots.
     *
     * @param mode target mode
     */
    public void setMode(Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        headerTitle.setText(mode == Mode.SAVE ? "Save Game" : "Load Game");
        customNameInput.setVisible(mode == Mode.SAVE);
        refreshSlots();
    }

    public Mode getMode() {
        return mode;
    }

    /**
     * Selects the active slot filter tab ({@code MANUAL}, {@code QUICK}, or {@code AUTO}).
     */
    public void setFilterTab(FilterTab tab) {
        this.filterTab = Objects.requireNonNull(tab, "tab must not be null");
        refreshSlots();
    }

    public FilterTab getFilterTab() {
        return filterTab;
    }

    /**
     * Navigates to a 0-based page index in {@code [0, TOTAL_PAGES - 1]}.
     */
    public void setPage(int pageIndex) {
        this.currentPage = Math.max(0, Math.min(TOTAL_PAGES - 1, pageIndex));
        refreshSlots();
    }

    public int getCurrentPage() {
        return currentPage;
    }

    /**
     * Reloads slot metadata from {@link SaveManager} and rebuilds the slot grid.
     */
    public void refreshSlots() {
        manualTabButton.setSelected(filterTab == FilterTab.MANUAL);
        quickTabButton.setSelected(filterTab == FilterTab.QUICK);
        autoTabButton.setSelected(filterTab == FilterTab.AUTO);
        pageBar.setVisible(filterTab == FilterTab.MANUAL);
        for (int i = 0; i < pageButtons.size(); i++) {
            pageButtons.get(i).setSelected(i == currentPage);
        }

        slotGrid.clearChildren();
        visibleCards.clear();

        SaveManager saveManager = session.getSaveManager();
        if (saveManager == null) {
            return;
        }

        try {
            if (filterTab == FilterTab.MANUAL) {
                List<SaveSlotData.SaveMetadata> metas = saveManager.listPageSlots(currentPage, SLOTS_PER_PAGE);
                for (int i = 0; i < SLOTS_PER_PAGE; i++) {
                    String slotId = SaveManager.slotIdForPage(currentPage, i, SLOTS_PER_PAGE);
                    SaveSlotData.SaveMetadata meta = i < metas.size() ? metas.get(i) : null;
                    addSlotCard(slotId, meta);
                }
            } else if (filterTab == FilterTab.QUICK) {
                Optional<SaveSlotData.SaveMetadata> quickMeta = saveManager.readSlotMetadata(SaveManager.QUICK_SAVE_SLOT);
                addSlotCard(SaveManager.QUICK_SAVE_SLOT, quickMeta.orElse(null));
            } else {
                for (int i = 1; i <= saveManager.getAutoSaveSlotCount(); i++) {
                    String autoId = SaveManager.AUTO_SAVE_PREFIX + i;
                    Optional<SaveSlotData.SaveMetadata> meta = saveManager.readSlotMetadata(autoId);
                    addSlotCard(autoId, meta.orElse(null));
                }
            }
        } catch (IOException ex) {
            session.getEngineApi().logError("Failed to list save slots: " + ex.getMessage(), ex);
        }
    }

    private void addSlotCard(String slotId, SaveSlotData.SaveMetadata meta) {
        boolean occupied = meta != null;
        Frame card = new Frame();
        UiMediaSupport.styleElevatedCard(card);
        card.setPreferredSize(510.0f, 250.0f);

        HBox cardRow = new HBox();
        cardRow.setSpacing(14.0f);

        UiMediaSupport.PreviewImageNode thumb = new UiMediaSupport.PreviewImageNode();
        thumb.setPreferredSize(180.0f, 110.0f);
        if (occupied && meta.thumbnailPng().length > 0) {
            thumb.setPngBytes(meta.thumbnailPng());
        } else {
            thumb.setPlaceholderLabel(occupied ? "SAVED" : "EMPTY");
        }

        VBox infoCol = new VBox();
        infoCol.setSpacing(8.0f);
        infoCol.setFillWidth(true);

        String title = occupied
                ? (meta.customName() != null && !meta.customName().isBlank() ? meta.customName() : slotId)
                : slotId + " (Empty)";
        TextWidget titleLbl = UiMediaSupport.createTitleText(title, 22.0f);

        String metaStr = occupied
                ? UiMediaSupport.formatTimestamp(meta.savedAt()) + " | " + UiMediaSupport.formatPlaytime(meta.playtimeSeconds())
                : "No save data";
        TextWidget metaLbl = UiMediaSupport.createMutedText(metaStr, 16.0f);

        String previewStr = occupied && meta.dialoguePreview() != null ? meta.dialoguePreview() : "";
        TextWidget previewLbl = UiMediaSupport.createMutedText(previewStr, 17.0f);

        HBox actionRow = new HBox();
        actionRow.setSpacing(10.0f);

        String actionText = mode == Mode.SAVE ? "Save" : "Load";
        TextButton actionBtn = UiMediaSupport.createActionButton(actionText, 110.0f, 34.0f,
                () -> handleSlotAction(slotId));
        actionBtn.setEnabled(mode == Mode.SAVE || occupied);
        if (!actionBtn.isEnabled()) {
            actionBtn.setAlpha(0.45f);
        }

        TextButton deleteBtn = UiMediaSupport.createActionButton("Delete", 100.0f, 34.0f,
                () -> handleSlotDelete(slotId));
        deleteBtn.setEnabled(occupied);
        deleteBtn.setBorderColorHex(occupied ? ThemePalette.ERROR : ThemePalette.BORDER);
        if (!occupied) {
            deleteBtn.setAlpha(0.45f);
        }

        actionRow.addChild(actionBtn);
        actionRow.addChild(deleteBtn);

        infoCol.addChild(titleLbl);
        infoCol.addChild(metaLbl);
        infoCol.addChild(previewLbl);
        infoCol.addChild(actionRow);

        cardRow.addChild(thumb);
        cardRow.addChild(infoCol);
        card.addChild(cardRow);

        slotGrid.addChild(card);
        visibleCards.add(new SlotCardView(
                slotId, card, thumb, titleLbl, metaLbl, previewLbl, actionBtn, deleteBtn, occupied
        ));
    }

    /**
     * Executes save or load for the given {@code slotId} according to the active {@link Mode}.
     */
    public void handleSlotAction(String slotId) {
        try {
            if (mode == Mode.SAVE) {
                String label = customNameInput.getText();
                session.saveToSlot(slotId, label, 0, 0, null);
                refreshSlots();
            } else if (session.getSaveManager().slotExists(slotId)) {
                session.loadFromSlot(slotId);
                closeScreen();
                if (screenManager != null) {
                    screenManager.hideScreen(MainMenuScreen.SCREEN_NAME);
                }
            }
        } catch (IOException ex) {
            session.getEngineApi().logError("Save/Load failed for slot '" + slotId + "': " + ex.getMessage(), ex);
        }
    }

    /**
     * Deletes {@code slotId} from disk and refreshes the grid.
     */
    public void handleSlotDelete(String slotId) {
        try {
            if (session.getSaveManager().deleteSlot(slotId)) {
                refreshSlots();
            }
        } catch (IOException ex) {
            session.getEngineApi().logError("Delete failed for slot '" + slotId + "': " + ex.getMessage(), ex);
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

    public List<SlotCardView> getVisibleCards() {
        return Collections.unmodifiableList(visibleCards);
    }

    public TextInput getCustomNameInput() {
        return customNameInput;
    }

    public List<TextButton> getPageButtons() {
        return Collections.unmodifiableList(pageButtons);
    }
}
