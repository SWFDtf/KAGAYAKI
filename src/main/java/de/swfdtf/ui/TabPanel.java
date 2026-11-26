package de.swfdtf.ui;

import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Multi-tabbed UI container with clickable tab strip headers and {@code Ctrl+Tab} wrapping.
 */
public class TabPanel extends UiNode {

    private static final float HEADER_HEIGHT = 40.0f;
    private static final float HEADER_GAP = 6.0f;

    private static final class TabEntry {
        private final String title;
        private final TextButton headerButton;
        private final UiNode content;

        private TabEntry(String title, TextButton headerButton, UiNode content) {
            this.title = title;
            this.headerButton = headerButton;
            this.content = content;
        }
    }

    private final List<TabEntry> tabs = new ArrayList<>();
    private int activeTabIndex = 0;

    /**
     * Creates an empty {@code TabPanel}.
     */
    public TabPanel() {
        setBackgroundColorHex(ThemePalette.PANEL_SURFACE);
        setBorderColorHex(ThemePalette.BORDER);
        setBorderThickness(1.0f);
        setCornerRadius(6.0f);
    }

    /**
     * Appends a new named tab containing {@code content} as its panel body.
     *
     * @param title   tab header title
     * @param content tab body container/node
     * @return {@code this}
     */
    public TabPanel addTab(String title, UiNode content) {
        String safeTitle = title != null && !title.isBlank() ? title : "Tab " + (tabs.size() + 1);
        UiNode safeContent = Objects.requireNonNull(content, "content must not be null");
        final int idx = tabs.size();
        TextButton headerBtn = new TextButton(safeTitle, () -> selectTab(idx));
        headerBtn.setPreferredSize(140.0f, HEADER_HEIGHT - 4.0f);
        TabEntry entry = new TabEntry(safeTitle, headerBtn, safeContent);
        tabs.add(entry);
        addChild(headerBtn);
        addChild(safeContent);
        applyActiveVisibility();
        return this;
    }

    /**
     * Selects the tab at {@code index}, clamping out-of-range values modulo tab count if non-empty.
     *
     * @param index target tab index
     * @return {@code this}
     */
    public TabPanel selectTab(int index) {
        if (tabs.isEmpty()) {
            return this;
        }
        int size = tabs.size();
        this.activeTabIndex = ((index % size) + size) % size;
        applyActiveVisibility();
        if (getBounds().width() > 0.0f && getBounds().height() > 0.0f) {
            layout(getBounds());
        }
        return this;
    }

    /**
     * Cycles to the next or previous tab, wrapping cleanly around ends.
     *
     * @param reverse {@code true} to move backward, {@code false} to move forward
     * @return {@code this}
     */
    public TabPanel cycleTab(boolean reverse) {
        if (tabs.isEmpty()) {
            return this;
        }
        int delta = reverse ? -1 : 1;
        return selectTab(activeTabIndex + delta);
    }

    /**
     * Returns the zero-based active tab index.
     *
     * @return active tab index
     */
    public int getActiveTabIndex() {
        return activeTabIndex;
    }

    /**
     * Returns the number of registered tabs.
     *
     * @return tab count
     */
    public int getTabCount() {
        return tabs.size();
    }

    /**
     * Returns the title of the currently active tab, or {@code ""} if empty.
     *
     * @return active tab title
     */
    public String getActiveTabTitle() {
        if (activeTabIndex >= 0 && activeTabIndex < tabs.size()) {
            return tabs.get(activeTabIndex).title;
        }
        return "";
    }

    private void applyActiveVisibility() {
        for (int i = 0; i < tabs.size(); i++) {
            TabEntry entry = tabs.get(i);
            boolean active = i == activeTabIndex;
            entry.headerButton.setBorderColorHex(active ? ThemePalette.ACTIVE_SELECTED : ThemePalette.BORDER);
            entry.headerButton.setBackgroundColorHex(active ? ThemePalette.ACCENT_IDLE : ThemePalette.ELEVATED);
            setSubtreeVisible(entry.content, active);
        }
    }

    private static void setSubtreeVisible(UiNode node, boolean visible) {
        if (node == null) {
            return;
        }
        if (node instanceof SelectBox sb) {
            sb.setVisible(visible);
            if (!visible) {
                sb.setExpanded(false);
            }
            return;
        }
        node.setVisible(visible);
        for (UiNode child : node.getChildren()) {
            setSubtreeVisible(child, visible);
        }
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        float w = getPreferredWidth() > 0.0f ? getPreferredWidth() : 480.0f;
        float h = getPreferredHeight() > 0.0f ? getPreferredHeight() : 280.0f;
        return new Vec2f(w, h);
    }

    @Override
    public void layout(Rect2f resolvedBounds) {
        Rect2f target = resolvedBounds != null ? resolvedBounds : new Rect2f(0.0f, 0.0f, 0.0f, 0.0f);
        super.layout(target);
        Rect2f inner = getContentBounds();
        if (tabs.isEmpty()) {
            return;
        }
        float btnW = Math.max(80.0f,
                Math.min(180.0f, (inner.width() - HEADER_GAP * Math.max(0, tabs.size() - 1)) / tabs.size()));
        float cursorX = inner.x();
        for (TabEntry entry : tabs) {
            entry.headerButton.layout(new Rect2f(cursorX, inner.y(), btnW, HEADER_HEIGHT - 4.0f));
            cursorX += btnW + HEADER_GAP;
        }
        Rect2f contentBounds = new Rect2f(
                inner.x(),
                inner.y() + HEADER_HEIGHT,
                inner.width(),
                Math.max(1.0f, inner.height() - HEADER_HEIGHT)
        );
        for (int i = 0; i < tabs.size(); i++) {
            TabEntry entry = tabs.get(i);
            if (i == activeTabIndex && entry.content.isVisible()) {
                entry.content.layout(contentBounds);
            }
        }
    }

    @Override
    public boolean dispatchKeyPressed(int key, int mods) {
        if (!isVisible() || !isEnabled()) {
            return false;
        }
        if (key == GLFW.GLFW_KEY_TAB && (mods & GLFW.GLFW_MOD_CONTROL) != 0 && !tabs.isEmpty()) {
            cycleTab((mods & GLFW.GLFW_MOD_SHIFT) != 0);
            return true;
        }
        return super.dispatchKeyPressed(key, mods);
    }
}
