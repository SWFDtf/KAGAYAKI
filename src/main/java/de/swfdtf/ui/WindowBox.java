package de.swfdtf.ui;

import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.StyledTextLayout;
import de.swfdtf.graphics.TextTagParser;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;

/**
 * Elevated Dark-Slate window container featuring a styled title header bar above its content area.
 */
public class WindowBox extends UiNode {

    /** Default title header bar height in logical pixels. */
    public static final float DEFAULT_HEADER_HEIGHT = 40.0f;

    /** Default title font size in logical pixels. */
    public static final float DEFAULT_TITLE_FONT_SIZE = 20.0f;

    private String title = "";
    private float headerHeight = DEFAULT_HEADER_HEIGHT;
    private float titleFontSize = DEFAULT_TITLE_FONT_SIZE;
    private String headerBackgroundColorHex = ThemePalette.ELEVATED;
    private String titleColorHex = ThemePalette.TEXT_PRIMARY;
    private StyledTextLayout cachedTitleLayout;
    private boolean titleLayoutDirty = true;

    /**
     * Creates an untitled {@code WindowBox}.
     */
    public WindowBox() {
        this("");
    }

    /**
     * Creates a {@code WindowBox} with the specified window title.
     *
     * @param title window header title text
     */
    public WindowBox(String title) {
        this.title = title != null ? title : "";
        setBackgroundColorHex(ThemePalette.PANEL_SURFACE);
        setBorderColorHex(ThemePalette.BORDER);
        setBorderThickness(1.0f);
        setCornerRadius(10.0f);
    }

    /**
     * Updates the window header title text.
     *
     * @param title header title (null treated as empty string)
     * @return {@code this}
     */
    public WindowBox setTitle(String title) {
        this.title = title != null ? title : "";
        this.titleLayoutDirty = true;
        return this;
    }

    /** @return current window title text */
    public String getTitle() {
        return title;
    }

    /**
     * Configures the title header bar height in logical pixels.
     *
     * @param headerHeight header bar height ({@code >= 0})
     * @return {@code this}
     */
    public WindowBox setHeaderHeight(float headerHeight) {
        this.headerHeight = Math.max(0.0f, headerHeight);
        return this;
    }

    /** @return title header bar height in logical pixels */
    public float getHeaderHeight() {
        return headerHeight;
    }

    /**
     * Configures the header title font size.
     *
     * @param titleFontSize font size in logical pixels ({@code > 0})
     * @return {@code this}
     */
    public WindowBox setTitleFontSize(float titleFontSize) {
        if (titleFontSize > 0.0f) {
            this.titleFontSize = titleFontSize;
            this.titleLayoutDirty = true;
        }
        return this;
    }

    /** @return header title font size */
    public float getTitleFontSize() {
        return titleFontSize;
    }

    /**
     * Returns the bounds reserved for the window's content area below the header bar.
     *
     * @return content area {@link Rect2f}
     */
    public Rect2f getWindowContentBounds() {
        Rect2f inner = getContentBounds();
        float bodyY = inner.y() + headerHeight;
        float bodyH = Math.max(0.0f, inner.height() - headerHeight);
        return new Rect2f(inner.x(), bodyY, inner.width(), bodyH);
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        float innerAvailW = Math.max(0.0f, (getPreferredWidth() > 0.0f ? getPreferredWidth() : availableWidth)
                - getPaddingLeft() - getPaddingRight());
        float innerAvailH = Math.max(0.0f, (getPreferredHeight() > 0.0f ? getPreferredHeight() : availableHeight)
                - getPaddingTop() - getPaddingBottom() - headerHeight);

        float maxW = 0.0f;
        float maxH = 0.0f;
        for (UiNode child : children) {
            if (!child.isVisible()) {
                continue;
            }
            Vec2f m = child.measure(innerAvailW, innerAvailH);
            maxW = Math.max(maxW, m.x());
            maxH = Math.max(maxH, m.y());
        }

        float w = getPreferredWidth() > 0.0f
                ? getPreferredWidth()
                : Math.max(maxW, 180.0f) + getPaddingLeft() + getPaddingRight();
        float h = getPreferredHeight() > 0.0f
                ? getPreferredHeight()
                : maxH + headerHeight + getPaddingTop() + getPaddingBottom();
        return new Vec2f(w, h);
    }

    @Override
    public void layout(Rect2f resolvedBounds) {
        setBounds(resolvedBounds);
        Rect2f body = getWindowContentBounds();
        for (UiNode child : children) {
            if (child.isVisible()) {
                child.layout(body);
            }
        }
    }

    @Override
    protected void drawBackgroundBox(RenderPipeline2D pipeline, float effectiveAlpha) {
        Rect2f b = getBounds();
        UiRenderSupport.drawRoundedRect(
                pipeline,
                b.x(), b.y(), b.width(), b.height(),
                getCornerRadius(),
                getBackgroundColorHex(),
                getBorderThickness(),
                getBorderColorHex(),
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
        Rect2f b = getBounds();
        if (!UiRenderSupport.isPipelineReady(pipeline) || headerHeight <= 0.0f || b.width() <= 0.0f) {
            return;
        }
        float effectiveHeaderH = Math.min(headerHeight, b.height());
        UiRenderSupport.drawRoundedRect(
                pipeline,
                b.x(), b.y(), b.width(), effectiveHeaderH,
                getCornerRadius(),
                headerBackgroundColorHex,
                getBorderThickness(),
                getBorderColorHex(),
                getAlpha()
        );
        if (!title.isEmpty()) {
            if (titleLayoutDirty || cachedTitleLayout == null) {
                cachedTitleLayout = StyledTextLayout.layout(
                        TextTagParser.parse(title),
                        UiRenderSupport.resolveFontManager(fontManager),
                        Math.max(1.0f, b.width() - 24.0f),
                        titleFontSize,
                        1.15f,
                        titleColorHex,
                        true
                );
                titleLayoutDirty = false;
            }
            float textY = b.y() + Math.max(0.0f, (effectiveHeaderH - cachedTitleLayout.getTotalHeight()) * 0.5f);
            cachedTitleLayout.render(pipeline, textureManager, b.x() + 14.0f, textY, elapsedSeconds, -1, getAlpha());
        }
    }
}
