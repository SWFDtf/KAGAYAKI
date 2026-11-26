package de.swfdtf.ui;

import de.swfdtf.graphics.SceneLayer;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Built-in Dark-Slate Controls reference screen ({@code Z = 400}, modal) presenting a tabular
 * overview of all Keyboard, Mouse, and Gamepad bindings.
 */
public class ControlsScreen extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "controls";

    /** Binding category filter. */
    public enum Category {
        /** Show all binding categories. */
        ALL,
        /** Keyboard bindings only. */
        KEYBOARD,
        /** Mouse bindings only. */
        MOUSE,
        /** Gamepad bindings only. */
        GAMEPAD
    }

    /**
     * Immutable control binding descriptor.
     *
     * @param category binding device category
     * @param input    key, mouse action, or gamepad button
     * @param action   in-game behavior description
     */
    public record BindingEntry(Category category, String input, String action) {
    }

    private static final List<BindingEntry> DEFAULT_BINDINGS = List.of(
            new BindingEntry(Category.KEYBOARD, "Space / Enter", "Advance dialogue / confirm selection"),
            new BindingEntry(Category.KEYBOARD, "PageUp / ScrollUp", "Rollback one step"),
            new BindingEntry(Category.KEYBOARD, "PageDown / ScrollDown", "Roll-forward one step"),
            new BindingEntry(Category.KEYBOARD, "S", "Quick-Save to slot 'quick'"),
            new BindingEntry(Category.KEYBOARD, "L", "Quick-Load from slot 'quick'"),
            new BindingEntry(Category.KEYBOARD, "H", "Open Dialogue History Backlog"),
            new BindingEntry(Category.KEYBOARD, "Ctrl", "Hold or toggle Skip Dialogue"),
            new BindingEntry(Category.KEYBOARD, "A", "Toggle Auto-Advance"),
            new BindingEntry(Category.KEYBOARD, "V", "Toggle Self-Voicing Accessibility TTS"),
            new BindingEntry(Category.KEYBOARD, "D", "Cycle Dialogue Mode (ADV / NVL / Bubble)"),
            new BindingEntry(Category.KEYBOARD, "Shift + R", "Hot-Reload .vjava scripts & assets"),
            new BindingEntry(Category.KEYBOARD, "Shift + B", "Toggle In-Game Bubble Layout Editor"),
            new BindingEntry(Category.KEYBOARD, "Shift + I", "Toggle UI / Scene Inspector Overlay"),
            new BindingEntry(Category.KEYBOARD, "Shift + C", "Toggle Developer Console"),
            new BindingEntry(Category.KEYBOARD, "Shift + O", "Toggle Performance / State Overlay"),
            new BindingEntry(Category.MOUSE, "Left Click", "Advance dialogue / activate UI control"),
            new BindingEntry(Category.MOUSE, "Right Click", "Open Quick Menu / back out"),
            new BindingEntry(Category.MOUSE, "Middle Click", "Hide UI overlay to inspect stage"),
            new BindingEntry(Category.MOUSE, "ScrollUp / ScrollDown", "Rollback / Roll-forward dialogue history"),
            new BindingEntry(Category.GAMEPAD, "A / Cross", "Advance dialogue / confirm"),
            new BindingEntry(Category.GAMEPAD, "B / Circle", "Back / cancel / hide menu"),
            new BindingEntry(Category.GAMEPAD, "X / Square", "Open History Backlog"),
            new BindingEntry(Category.GAMEPAD, "Y / Triangle", "Toggle Auto-Advance"),
            new BindingEntry(Category.GAMEPAD, "LB / RB", "Rollback / Skip"),
            new BindingEntry(Category.GAMEPAD, "D-Pad / Left Stick", "Navigate UI focus")
    );

    private final ScreenManager screenManager;
    private final VBox rowsContainer;
    private final Viewport viewport;
    private final TextButton allTab;
    private final TextButton keyboardTab;
    private final TextButton mouseTab;
    private final TextButton gamepadTab;

    private Category activeCategory = Category.ALL;
    private final List<BindingEntry> visibleBindings = new ArrayList<>();

    /**
     * Creates a {@code ControlsScreen} optionally wired to {@code screenManager}.
     *
     * @param screenManager UI screen manager (nullable)
     */
    public ControlsScreen(ScreenManager screenManager) {
        super(SCREEN_NAME, SceneLayer.Z_MODAL, true);
        this.screenManager = screenManager;

        Frame backdrop = new Frame();
        UiMediaSupport.styleBackdrop(backdrop);
        backdrop.setPadding(160.0f, 70.0f);

        Frame panel = new Frame();
        UiMediaSupport.styleModalCard(panel);

        VBox mainBox = new VBox();
        mainBox.setSpacing(16.0f);
        mainBox.setFillWidth(true);

        HBox topBar = new HBox();
        topBar.setSpacing(14.0f);
        topBar.setAlignCenterY(true);
        topBar.addChild(UiMediaSupport.createTitleText("Controls", 34.0f));
        this.allTab = UiMediaSupport.createActionButton("All", 110.0f, 38.0f, () -> setCategory(Category.ALL));
        this.keyboardTab = UiMediaSupport.createActionButton(
                "Keyboard", 140.0f, 38.0f, () -> setCategory(Category.KEYBOARD));
        this.mouseTab = UiMediaSupport.createActionButton("Mouse", 120.0f, 38.0f, () -> setCategory(Category.MOUSE));
        this.gamepadTab = UiMediaSupport.createActionButton(
                "Gamepad", 140.0f, 38.0f, () -> setCategory(Category.GAMEPAD));
        TextButton closeBtn = UiMediaSupport.createActionButton("Back", 120.0f, 38.0f, this::closeScreen);

        topBar.addChild(allTab);
        topBar.addChild(keyboardTab);
        topBar.addChild(mouseTab);
        topBar.addChild(gamepadTab);
        topBar.addChild(closeBtn);

        this.viewport = new Viewport();
        this.viewport.setPreferredSize(1500.0f, 740.0f);

        this.rowsContainer = new VBox();
        this.rowsContainer.setSpacing(8.0f);
        this.rowsContainer.setFillWidth(true);

        this.viewport.addChild(rowsContainer);
        mainBox.addChild(topBar);
        mainBox.addChild(viewport);
        panel.addChild(mainBox);
        backdrop.addChild(panel);
        setRoot(backdrop);

        addKeyBinding(GLFW.GLFW_KEY_ESCAPE, this::closeScreen);
        rebuildTable();
    }

    @Override
    public void onShow() {
        rebuildTable();
    }

    /**
     * Filters the bindings table to {@code category}.
     *
     * @param category target device category filter
     */
    public void setCategory(Category category) {
        this.activeCategory = category != null ? category : Category.ALL;
        rebuildTable();
    }

    /**
     * Returns the active category filter.
     *
     * @return active category
     */
    public Category getActiveCategory() {
        return activeCategory;
    }

    private void rebuildTable() {
        allTab.setSelected(activeCategory == Category.ALL);
        keyboardTab.setSelected(activeCategory == Category.KEYBOARD);
        mouseTab.setSelected(activeCategory == Category.MOUSE);
        gamepadTab.setSelected(activeCategory == Category.GAMEPAD);

        rowsContainer.clearChildren();
        visibleBindings.clear();

        for (BindingEntry entry : DEFAULT_BINDINGS) {
            if (activeCategory != Category.ALL && entry.category() != activeCategory) {
                continue;
            }
            visibleBindings.add(entry);
            Frame rowFrame = new Frame();
            UiMediaSupport.styleElevatedCard(rowFrame);
            rowFrame.setPadding(12.0f, 8.0f);

            HBox row = new HBox();
            row.setSpacing(20.0f);
            row.setAlignCenterY(true);

            TextWidget catLbl = UiMediaSupport.createMutedText(entry.category().name(), 18.0f);
            catLbl.setPreferredSize(160.0f, 28.0f);
            TextWidget keyLbl = UiMediaSupport.createTitleText(entry.input(), 20.0f);
            keyLbl.setColorHex(ThemePalette.ACCENT_HOVER);
            keyLbl.setPreferredSize(320.0f, 28.0f);
            TextWidget actionLbl = UiMediaSupport.createTitleText(entry.action(), 20.0f);

            row.addChild(catLbl);
            row.addChild(keyLbl);
            row.addChild(actionLbl);
            rowFrame.addChild(row);
            rowsContainer.addChild(rowFrame);
        }
        viewport.setScrollY(0.0f);
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
     * Returns the immutable list of currently visible bindings.
     *
     * @return visible binding entries
     */
    public List<BindingEntry> getVisibleBindings() {
        return Collections.unmodifiableList(visibleBindings);
    }
}
