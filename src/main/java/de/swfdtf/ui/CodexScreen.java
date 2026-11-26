package de.swfdtf.ui;

import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.state.CodexGlossaryManager;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * In-game Codex / Glossary modal screen ({@code Z = 460}) displaying unlocked lore entries,
 * {@code "Neu"} unread badges cleared on selection, and locked placeholders without leaking content.
 */
public class CodexScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "codex";

    private final CodexGlossaryManager codexManager;
    private final ScreenManager screenManager;
    private final VBox entriesList;
    private final TextWidget detailTitle;
    private final TextWidget detailSummary;
    private final TextWidget detailBody;

    private final Set<String> readIds = new HashSet<>();
    private String selectedEntryId;

    /**
     * Creates a {@code CodexScreen} bound to {@code codexManager} and {@code screenManager}.
     *
     * @param codexManager  glossary state manager
     * @param screenManager UI screen manager
     */
    public CodexScreen(CodexGlossaryManager codexManager, ScreenManager screenManager) {
        super(SCREEN_NAME, SceneLayer.Z_MODAL + 60, true);
        this.codexManager = Objects.requireNonNull(codexManager, "codexManager must not be null");
        this.screenManager = screenManager;

        Frame backdrop = new Frame();
        UiMediaSupport.styleBackdrop(backdrop);
        backdrop.setPadding(160.0f, 90.0f);

        Frame panel = new Frame();
        UiMediaSupport.styleModalCard(panel);

        VBox layout = new VBox();
        layout.setSpacing(18.0f);
        layout.setFillWidth(true);

        HBox header = new HBox();
        header.setSpacing(20.0f);
        header.setAlignCenterY(true);
        header.addChild(UiMediaSupport.createTitleText("Codex & Glossar", 34.0f));
        header.addChild(UiMediaSupport.createActionButton("Zurück", 140.0f, 40.0f, this::closeScreen));

        HBox bodySplit = new HBox();
        bodySplit.setSpacing(24.0f);

        Viewport listViewport = new Viewport();
        listViewport.setPreferredSize(560.0f, 680.0f);
        this.entriesList = new VBox();
        this.entriesList.setSpacing(10.0f);
        this.entriesList.setFillWidth(true);
        listViewport.addChild(this.entriesList);

        Frame detailCard = new Frame();
        detailCard.setPreferredSize(900.0f, 680.0f);
        detailCard.setBackgroundColorHex(ThemePalette.ELEVATED)
                .setBorderColorHex(ThemePalette.BORDER)
                .setBorderThickness(1.0f)
                .setCornerRadius(8.0f)
                .setPadding(24.0f);
        VBox detailBox = new VBox();
        detailBox.setSpacing(14.0f);
        detailBox.setFillWidth(true);
        this.detailTitle = UiMediaSupport.createTitleText("", 28.0f);
        this.detailSummary = new TextWidget("");
        this.detailSummary.setFontSize(22.0f).setColorHex(ThemePalette.ACCENT_HOVER);
        this.detailBody = new TextWidget("");
        this.detailBody.setFontSize(20.0f).setColorHex(ThemePalette.TEXT_PRIMARY);
        detailBox.addChild(this.detailTitle);
        detailBox.addChild(this.detailSummary);
        detailBox.addChild(this.detailBody);
        detailCard.addChild(detailBox);

        bodySplit.addChild(listViewport);
        bodySplit.addChild(detailCard);

        layout.addChild(header);
        layout.addChild(bodySplit);
        panel.addChild(layout);
        backdrop.addChild(panel);
        setRoot(backdrop);

        addKeyBinding(GLFW.GLFW_KEY_ESCAPE, this::closeScreen);
    }

    @Override
    public void onShow() {
        refreshEntries();
    }

    /**
     * Selects an entry by ID if unlocked, marks it read, and refreshes displayed details.
     *
     * @param entryId target codex entry identifier
     */
    public void openEntry(String entryId) {
        if (entryId != null && !entryId.isBlank() && codexManager.isUnlocked(entryId)) {
            this.selectedEntryId = entryId;
            this.readIds.add(entryId);
        }
        refreshEntries();
    }

    /**
     * Rebuilds the entry list and selected detail card from {@link CodexGlossaryManager}.
     */
    public void refreshEntries() {
        entriesList.clearChildren();
        List<CodexGlossaryManager.CodexEntry> all = codexManager.listAll();

        for (CodexGlossaryManager.CodexEntry entry : all) {
            boolean unlocked = codexManager.isUnlocked(entry.id());
            HBox row = new HBox();
            row.setSpacing(10.0f);
            row.setAlignCenterY(true);
            if (unlocked) {
                TextButton btn = UiMediaSupport.createActionButton(
                        entry.title(),
                        380.0f,
                        42.0f,
                        () -> openEntry(entry.id())
                );
                if (entry.id().equals(selectedEntryId)) {
                    btn.setBorderColorHex(ThemePalette.ACTIVE_SELECTED);
                }
                row.addChild(btn);
                if (!readIds.contains(entry.id())) {
                    TextWidget badge = new TextWidget("Neu");
                    badge.setFontSize(18.0f).setColorHex(ThemePalette.ACTIVE_SELECTED);
                    badge.setPreferredSize(70.0f, 32.0f);
                    row.addChild(badge);
                }
            } else {
                TextButton lockedBtn = UiMediaSupport.createActionButton("??? (Gesperrt)", 380.0f, 42.0f, () -> { });
                lockedBtn.setEnabled(false);
                lockedBtn.setAlpha(0.45f);
                row.addChild(lockedBtn);
            }
            entriesList.addChild(row);
        }

        if (selectedEntryId != null && codexManager.isUnlocked(selectedEntryId)) {
            codexManager.findById(selectedEntryId).ifPresentOrElse(entry -> {
                detailTitle.setText(entry.title());
                detailSummary.setText(entry.summary());
                detailBody.setText(entry.fullText());
            }, this::clearDetail);
        } else {
            clearDetail();
        }
        if (screenManager != null) {
            layout(screenManager.getLogicalWidth(), screenManager.getLogicalHeight());
        }
    }

    private void clearDetail() {
        detailTitle.setText("");
        detailSummary.setText("");
        detailBody.setText("");
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
