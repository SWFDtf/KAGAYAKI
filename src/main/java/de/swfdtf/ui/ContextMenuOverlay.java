package de.swfdtf.ui;

import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.math.Rect2f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Right-click contextual popup menu ({@code #0421}) clamped within the logical {@code 1920x1080}
 * viewport, closing cleanly when clicking outside or pressing {@code Escape}.
 */
public class ContextMenuOverlay extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "context_menu_overlay";

    private static final float MENU_WIDTH = 240.0f;
    private static final float ENTRY_HEIGHT = 38.0f;
    private static final float LOGICAL_W = 1920.0f;
    private static final float LOGICAL_H = 1080.0f;

    /**
     * Single actionable entry shown in a {@link ContextMenuOverlay}.
     *
     * @param label   entry text (e.g., {@code "Load"}, {@code "Overwrite"}, {@code "Delete"})
     * @param enabled whether the action may be clicked
     * @param action  callback executed when clicked
     */
    public record MenuEntry(String label, boolean enabled, Runnable action) {
    }

    private final Fixed rootContainer;
    private final Frame menuBox;
    private final VBox entriesBox;
    private final List<MenuEntry> entries = new ArrayList<>();
    private final List<TextButton> buttons = new ArrayList<>();

    /**
     * Creates a hidden modal context menu overlay ({@code Z = 960}).
     */
    public ContextMenuOverlay() {
        super(SCREEN_NAME, SceneLayer.Z_INSPECTOR - 40, true);
        setVisible(false);

        this.rootContainer = new Fixed();
        this.menuBox = new Frame();
        this.menuBox.setBackgroundColorHex(ThemePalette.PANEL_SURFACE)
                .setBorderColorHex(ThemePalette.ACCENT_IDLE)
                .setBorderThickness(1.5f)
                .setCornerRadius(6.0f)
                .setPadding(6.0f, 6.0f);
        this.entriesBox = new VBox();
        this.entriesBox.setSpacing(4.0f);
        this.entriesBox.setFillWidth(true);
        this.menuBox.addChild(this.entriesBox);
        this.rootContainer.addChildAt(this.menuBox, 0.0f, 0.0f, MENU_WIDTH, 120.0f);
        setRoot(this.rootContainer);

        addKeyBinding(GLFW.GLFW_KEY_ESCAPE, 0, this::close);
    }

    /**
     * Populates entries and opens the context menu near {@code (anchorX, anchorY)}.
     *
     * @param anchorX desired X position in logical pixels
     * @param anchorY desired Y position in logical pixels
     * @param items   ordered context menu actions
     */
    public void openAt(float anchorX, float anchorY, List<MenuEntry> items) {
        Objects.requireNonNull(items, "items must not be null");
        this.entries.clear();
        this.entries.addAll(items);
        this.entriesBox.clearChildren();
        this.buttons.clear();

        for (MenuEntry entry : items) {
            TextButton btn = UiMediaSupport.createActionButton(
                    entry.label(), MENU_WIDTH - 12.0f, ENTRY_HEIGHT, () -> {
                        if (entry.enabled()) {
                            close();
                            if (entry.action() != null) {
                                entry.action().run();
                            }
                        }
                    });
            btn.setEnabled(entry.enabled());
            if (!entry.enabled()) {
                btn.setAlpha(0.45f);
            }
            this.buttons.add(btn);
            this.entriesBox.addChild(btn);
        }

        float totalH = Math.max(48.0f, items.size() * (ENTRY_HEIGHT + 4.0f) + 12.0f);
        float clampedX = Math.clamp(anchorX, 8.0f, LOGICAL_W - MENU_WIDTH - 8.0f);
        float clampedY = Math.clamp(anchorY, 8.0f, LOGICAL_H - totalH - 8.0f);

        this.rootContainer.clearChildren();
        this.rootContainer.addChildAt(this.menuBox, clampedX, clampedY, MENU_WIDTH, totalH);
        setVisible(true);
        layout(LOGICAL_W, LOGICAL_H);
    }

    /**
     * Handles a pointer click at {@code (lx, ly)}, closing immediately with zero leak-through
     * if the click falls outside the popup frame.
     *
     * @param lx     logical click X
     * @param ly     logical click Y
     * @param button GLFW mouse button index
     * @return {@code true} if the overlay was open and consumed the click
     */
    public boolean handlePointerClick(float lx, float ly, int button) {
        if (!isVisible()) {
            return false;
        }
        Rect2f box = menuBox.getBounds();
        if (!box.contains(lx, ly)) {
            close();
            return true;
        }
        return rootContainer.dispatchMouseDown(lx, ly, button);
    }

    /**
     * Hides this context menu overlay cleanly.
     */
    public void close() {
        setVisible(false);
    }

    /**
     * Returns active menu bounds in logical pixels.
     *
     * @return menu card {@link Rect2f}
     */
    public Rect2f getMenuBounds() {
        return menuBox.getBounds();
    }

    /**
     * Returns immutable list of rendered entry buttons.
     *
     * @return context menu buttons
     */
    public List<TextButton> getEntryButtons() {
        return Collections.unmodifiableList(buttons);
    }
}
