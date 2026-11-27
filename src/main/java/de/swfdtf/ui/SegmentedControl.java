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
import java.util.function.BiConsumer;

/**
 * Compact segmented switch / split-button bar ({@code #0407}) for multi-choice toggles
 * such as {@code Windowed / Fullscreen / Borderless} or {@code 2D / 3D}, with smooth animated
 * selection highlight (or instant snap when {@code reduceMotion} is active).
 */
public class SegmentedControl extends UiNode {

    private static final float DEFAULT_SEGMENT_WIDTH = 140.0f;
    private static final float DEFAULT_HEIGHT = 42.0f;

    private final List<String> options = new ArrayList<>();
    private int selectedIndex = 0;
    private float animatedIndex = 0.0f;
    private boolean reduceMotion = false;
    private BiConsumer<Integer, String> onSelect;

    /**
     * Creates a {@code SegmentedControl} with the provided option labels.
     *
     * @param options  segment labels
     * @param onSelect callback receiving {@code (selectedIndex, selectedLabel)}
     */
    public SegmentedControl(List<String> options, BiConsumer<Integer, String> onSelect) {
        setFocusable(true);
        setCornerRadius(6.0f);
        setBorderThickness(1.0f);
        setOptions(options);
        this.onSelect = onSelect;
    }

    /**
     * Replaces available segment labels and clamps the active index.
     *
     * @param options segment labels
     * @return {@code this}
     */
    public SegmentedControl setOptions(List<String> options) {
        this.options.clear();
        if (options != null) {
            for (String opt : options) {
                this.options.add(opt != null ? opt : "");
            }
        }
        if (this.options.isEmpty()) {
            this.selectedIndex = 0;
            this.animatedIndex = 0.0f;
        } else {
            this.selectedIndex = Math.clamp(this.selectedIndex, 0, this.options.size() - 1);
            this.animatedIndex = this.selectedIndex;
        }
        return this;
    }

    /**
     * Returns immutable view of option labels.
     *
     * @return option labels
     */
    public List<String> getOptions() {
        return Collections.unmodifiableList(options);
    }

    /**
     * Selects a segment index and notifies the change callback if changed.
     *
     * @param index segment index
     * @return {@code this}
     */
    public SegmentedControl setSelectedIndex(int index) {
        if (options.isEmpty()) {
            return this;
        }
        int clamped = Math.clamp(index, 0, options.size() - 1);
        boolean changed = clamped != this.selectedIndex;
        this.selectedIndex = clamped;
        if (reduceMotion) {
            this.animatedIndex = clamped;
        }
        if (changed && onSelect != null) {
            onSelect.accept(clamped, options.get(clamped));
        }
        return this;
    }

    /**
     * Returns active selected segment index.
     *
     * @return selected index
     */
    public int getSelectedIndex() {
        return selectedIndex;
    }

    /**
     * Returns currently selected option label, or empty string if empty.
     *
     * @return selected option label
     */
    public String getSelectedOption() {
        return (selectedIndex >= 0 && selectedIndex < options.size()) ? options.get(selectedIndex) : "";
    }

    /**
     * Returns animated floating-point highlight index in {@code [0, options.size() - 1]}.
     *
     * @return animated segment position
     */
    public float getAnimatedIndex() {
        return animatedIndex;
    }

    /**
     * Enables or disables smooth highlight sliding.
     *
     * @param reduceMotion {@code true} to snap instantly without interpolation
     * @return {@code this}
     */
    public SegmentedControl setReduceMotion(boolean reduceMotion) {
        this.reduceMotion = reduceMotion;
        if (reduceMotion) {
            this.animatedIndex = selectedIndex;
        }
        return this;
    }

    /**
     * Returns whether reduced motion is enabled.
     *
     * @return {@code true} if instant snap is active
     */
    public boolean isReduceMotion() {
        return reduceMotion;
    }

    @Override
    public void update(double dt) {
        if (reduceMotion || dt <= 0.0) {
            animatedIndex = selectedIndex;
        } else {
            float alpha = Math.min(1.0f, (float) (dt * 14.0));
            animatedIndex += (selectedIndex - animatedIndex) * alpha;
            if (Math.abs(selectedIndex - animatedIndex) < 0.005f) {
                animatedIndex = selectedIndex;
            }
        }
        super.update(dt);
    }

    @Override
    protected boolean onMouseDown(float lx, float ly, int button) {
        if (!isEnabled() || button != 0 || options.isEmpty()) {
            return false;
        }
        Rect2f b = getBounds();
        float segW = Math.max(1.0f, b.width() / options.size());
        int clicked = Math.clamp((int) ((lx - b.x()) / segW), 0, options.size() - 1);
        setSelectedIndex(clicked);
        return true;
    }

    @Override
    protected boolean onKeyPressed(int key, int mods) {
        if (!isEnabled() || !isFocused() || options.isEmpty()) {
            return false;
        }
        if (key == GLFW.GLFW_KEY_LEFT) {
            setSelectedIndex(selectedIndex - 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT) {
            setSelectedIndex(selectedIndex + 1);
            return true;
        }
        return false;
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        float w = getPreferredWidth() > 0.0f
                ? getPreferredWidth()
                : Math.max(120.0f, options.size() * DEFAULT_SEGMENT_WIDTH);
        float h = getPreferredHeight() > 0.0f ? getPreferredHeight() : DEFAULT_HEIGHT;
        return new Vec2f(w, h);
    }

    @Override
    protected void drawBackgroundBox(RenderPipeline2D pipeline, float effectiveAlpha) {
        Rect2f b = getBounds();
        UiRenderSupport.drawRoundedRect(
                pipeline, b.x(), b.y(), b.width(), b.height(),
                getCornerRadius(), ThemePalette.PANEL_SURFACE,
                getBorderThickness(), isFocused() ? ThemePalette.ACCENT_HOVER : ThemePalette.BORDER,
                effectiveAlpha
        );
    }

    @Override
    protected void renderSelf(
            RenderPipeline2D pipeline,
            FontManager fontManager,
            TextureManager textureManager,
            double elapsedSeconds
    ) {
        if (!UiRenderSupport.isPipelineReady(pipeline) || options.isEmpty()) {
            return;
        }
        Rect2f b = getBounds();
        float segW = b.width() / options.size();
        float pillX = b.x() + animatedIndex * segW + 2.0f;
        UiRenderSupport.drawRoundedRect(
                pipeline, pillX, b.y() + 2.0f, Math.max(4.0f, segW - 4.0f), Math.max(4.0f, b.height() - 4.0f),
                Math.max(2.0f, getCornerRadius() - 1.0f),
                ThemePalette.ACCENT_IDLE, 0.0f, null, getAlpha()
        );
        FontManager fm = UiRenderSupport.resolveFontManager(fontManager);
        for (int i = 0; i < options.size(); i++) {
            String color = (i == selectedIndex) ? ThemePalette.TEXT_PRIMARY : ThemePalette.TEXT_MUTED;
            StyledTextLayout layout = StyledTextLayout.layout(
                    TextTagParser.parse(options.get(i)), fm, segW - 8.0f, 18.0f, 1.1f, color, true
            );
            float tx = b.x() + i * segW + Math.max(0.0f, (segW - layout.getTotalWidth()) * 0.5f);
            float ty = b.y() + Math.max(0.0f, (b.height() - layout.getTotalHeight()) * 0.5f);
            layout.render(pipeline, textureManager, tx, ty, elapsedSeconds, -1, getAlpha());
        }
    }
}
