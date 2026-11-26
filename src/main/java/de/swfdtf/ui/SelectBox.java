package de.swfdtf.ui;

import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.StyledTextLayout;
import de.swfdtf.graphics.TextTagParser;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Interactive dropdown selector widget supporting keyboard and mouse option picking.
 */
public class SelectBox extends UiNode {

    private static final float OPTION_MIN_HEIGHT = 34.0f;

    private final List<String> options = new ArrayList<>();
    private final List<TextButton> optionButtons = new ArrayList<>();
    private int selectedIndex = -1;
    private boolean expanded = false;
    private Consumer<String> onChanged;

    /**
     * Creates an empty {@code SelectBox}.
     */
    public SelectBox() {
        setFocusable(true);
        setBackgroundColorHex(ThemePalette.ELEVATED);
        setBorderColorHex(ThemePalette.BORDER);
        setBorderThickness(1.5f);
        setCornerRadius(6.0f);
        setPreferredSize(240.0f, 44.0f);
    }

    /**
     * Creates a {@code SelectBox} initialized with the given options.
     *
     * @param options initial option strings
     */
    public SelectBox(List<String> options) {
        this();
        setOptions(options);
    }

    /**
     * Replaces all selectable options and resets selection to index {@code 0} when non-empty.
     *
     * @param newOptions ordered list of option strings
     * @return {@code this}
     */
    public SelectBox setOptions(List<String> newOptions) {
        options.clear();
        optionButtons.clear();
        clearChildren();
        if (newOptions != null) {
            for (String opt : newOptions) {
                if (opt != null) {
                    options.add(opt.trim());
                }
            }
        }
        for (int i = 0; i < options.size(); i++) {
            final int idx = i;
            String label = options.get(i);
            TextButton btn = new TextButton(label, () -> selectIndex(idx, true));
            btn.setBackgroundColorHex(ThemePalette.PANEL_SURFACE);
            btn.setBorderColorHex(ThemePalette.ACCENT_IDLE);
            btn.setBorderThickness(1.0f);
            btn.setCornerRadius(4.0f);
            btn.setVisible(expanded);
            optionButtons.add(btn);
            addChild(btn);
        }
        if (options.isEmpty()) {
            selectedIndex = -1;
        } else if (selectedIndex < 0 || selectedIndex >= options.size()) {
            selectedIndex = 0;
        }
        return this;
    }

    /**
     * Returns an unmodifiable view of available options.
     *
     * @return option strings
     */
    public List<String> getOptions() {
        return Collections.unmodifiableList(options);
    }

    /**
     * Selects an option matching {@code value} if present without firing {@code onChanged}.
     *
     * @param value option text to select
     * @return {@code this}
     */
    public SelectBox setSelectedOption(String value) {
        if (value == null) {
            return this;
        }
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).equals(value)) {
                selectIndex(i, false);
                return this;
            }
        }
        return this;
    }

    /**
     * Returns the currently selected option text, or {@code ""} if none is selected.
     *
     * @return selected option text
     */
    public String getSelectedOption() {
        if (selectedIndex >= 0 && selectedIndex < options.size()) {
            return options.get(selectedIndex);
        }
        return "";
    }

    /**
     * Returns whether the dropdown option list is currently expanded.
     *
     * @return {@code true} if expanded
     */
    public boolean isExpanded() {
        return expanded;
    }

    /**
     * Expands or collapses the dropdown option list.
     *
     * @param expanded target expanded state
     * @return {@code this}
     */
    public SelectBox setExpanded(boolean expanded) {
        this.expanded = expanded;
        for (TextButton btn : optionButtons) {
            btn.setVisible(expanded);
        }
        if (getBounds().width() > 0.0f && getBounds().height() > 0.0f) {
            layout(getBounds());
        }
        return this;
    }

    /**
     * Registers a callback invoked when the selected option changes via interaction.
     *
     * @param onChanged change listener (nullable)
     * @return {@code this}
     */
    public SelectBox setOnChanged(Consumer<String> onChanged) {
        this.onChanged = onChanged;
        return this;
    }

    private void selectIndex(int index, boolean notify) {
        if (index < 0 || index >= options.size()) {
            return;
        }
        boolean changed = selectedIndex != index;
        selectedIndex = index;
        setExpanded(false);
        if (notify && changed && onChanged != null) {
            onChanged.accept(options.get(index));
        }
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        float w = getPreferredWidth() > 0.0f ? getPreferredWidth() : 240.0f;
        float h = getPreferredHeight() > 0.0f ? getPreferredHeight() : 44.0f;
        return new Vec2f(w, h);
    }

    @Override
    public void layout(Rect2f resolvedBounds) {
        setBoundsOnly(resolvedBounds);
        Rect2f b = getBounds();
        float optH = Math.max(OPTION_MIN_HEIGHT, b.height() > 0.0f ? b.height() : 40.0f);
        for (int i = 0; i < optionButtons.size(); i++) {
            TextButton btn = optionButtons.get(i);
            btn.layout(new Rect2f(b.x(), b.bottom() + i * optH, b.width(), optH));
        }
    }

    private void setBoundsOnly(Rect2f resolvedBounds) {
        // Delegate super.layout with children temporarily skipped by saving/setting bounds directly
        Rect2f target = resolvedBounds != null ? resolvedBounds : new Rect2f(0.0f, 0.0f, 0.0f, 0.0f);
        super.layout(target);
    }

    @Override
    public boolean dispatchMouseDown(float lx, float ly, int button) {
        if (!isVisible() || !isEnabled()) {
            return false;
        }
        if (expanded) {
            for (int i = optionButtons.size() - 1; i >= 0; i--) {
                if (optionButtons.get(i).dispatchMouseDown(lx, ly, button)) {
                    return true;
                }
            }
        }
        if (!getBounds().contains(lx, ly)) {
            if (expanded && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                setExpanded(false);
            }
            return false;
        }
        if (onMouseDown(lx, ly, button)) {
            return true;
        }
        return false;
    }

    @Override
    public boolean dispatchMouseUp(float lx, float ly, int button) {
        if (!isVisible()) {
            return false;
        }
        boolean handled = false;
        if (expanded || getBounds().contains(lx, ly)) {
            for (int i = optionButtons.size() - 1; i >= 0; i--) {
                if (optionButtons.get(i).dispatchMouseUp(lx, ly, button)) {
                    handled = true;
                }
            }
        }
        if (isEnabled() && getBounds().contains(lx, ly) && onMouseUp(lx, ly, button)) {
            handled = true;
        }
        return handled;
    }

    @Override
    public UiNode dispatchMouseMove(float lx, float ly) {
        if (!isVisible()) {
            setHoveredDeep(false);
            return null;
        }
        UiNode hitChild = null;
        if (expanded) {
            for (int i = optionButtons.size() - 1; i >= 0; i--) {
                UiNode candidate = optionButtons.get(i).dispatchMouseMove(lx, ly);
                if (candidate != null && hitChild == null) {
                    hitChild = candidate;
                }
            }
        }
        if (hitChild != null) {
            return hitChild;
        }
        return getBounds().contains(lx, ly) && isEnabled() ? this : null;
    }

    @Override
    protected boolean onMouseDown(float lx, float ly, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && isEnabled()) {
            setFocused(true);
            setExpanded(!expanded);
            return true;
        }
        return false;
    }

    @Override
    public boolean dispatchKeyPressed(int key, int mods) {
        if (!isVisible() || !isEnabled()) {
            return false;
        }
        if (expanded && key == GLFW.GLFW_KEY_ESCAPE) {
            setExpanded(false);
            return true;
        }
        return onKeyPressed(key, mods);
    }

    @Override
    protected boolean onKeyPressed(int key, int mods) {
        if (!isFocused() || !isEnabled()) {
            return false;
        }
        if (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            setExpanded(!expanded);
            return true;
        }
        if (key == GLFW.GLFW_KEY_DOWN && !options.isEmpty()) {
            selectIndex((selectedIndex + 1) % options.size(), true);
            return true;
        }
        if (key == GLFW.GLFW_KEY_UP && !options.isEmpty()) {
            selectIndex((selectedIndex - 1 + options.size()) % options.size(), true);
            return true;
        }
        return false;
    }

    @Override
    protected void renderSelf(
            RenderPipeline2D pipeline,
            FontManager fontManager,
            TextureManager textureManager,
            double elapsedSeconds
    ) {
        if (!UiRenderSupport.isPipelineReady(pipeline)) {
            return;
        }
        Rect2f b = getBounds();
        String activeBorder = isFocused() || isHovered() || expanded
                ? ThemePalette.ACCENT_HOVER
                : Objects.requireNonNullElse(getBorderColorHex(), ThemePalette.BORDER);
        pipeline.drawRoundedRect(
                b.x(), b.y(), b.width(), b.height(), getCornerRadius(),
                new float[]{0.0f, 0.0f, 0.0f, 0.0f}, 1.5f,
                ThemePalette.premultipliedRgba(activeBorder, getAlpha())
        );

        String display = getSelectedOption() + (expanded ? " ▲" : " ▼");
        FontManager fm = UiRenderSupport.resolveFontManager(fontManager);
        StyledTextLayout layout = StyledTextLayout.layout(
                TextTagParser.parse(display),
                fm,
                Math.max(10.0f, b.width() - 20.0f),
                20.0f,
                1.1f,
                ThemePalette.TEXT_PRIMARY,
                true
        );
        layout.render(pipeline, textureManager, b.x() + 12.0f, b.y() + 10.0f, elapsedSeconds, -1, getAlpha());
    }
}
