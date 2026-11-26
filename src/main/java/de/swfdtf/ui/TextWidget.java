package de.swfdtf.ui;

import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.StyledTextLayout;
import de.swfdtf.graphics.TextTagParser;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;

/**
 * Rich-text UI widget powered by {@link TextTagParser} and {@link StyledTextLayout}, supporting
 * word wrapping, drop shadows, progressive {@link StyledTextLayout.TypewriterController} reveal,
 * and automatic headless {@link FontManager} fallback.
 */
public class TextWidget extends UiNode {

    /** Default text size in logical pixels. */
    public static final float DEFAULT_FONT_SIZE = 24.0f;

    /** Default line height multiplier. */
    public static final float DEFAULT_LINE_SPACING = 1.15f;

    private String text = "";
    private float fontSize = DEFAULT_FONT_SIZE;
    private float lineSpacing = DEFAULT_LINE_SPACING;
    private String colorHex = ThemePalette.TEXT_PRIMARY;
    private boolean dropShadow = false;
    private float typewriterCps = 0.0f;
    private FontManager customFontManager;

    private TextTagParser.ParsedRichText parsedText;
    private StyledTextLayout cachedLayout;
    private StyledTextLayout.TypewriterController typewriter;
    private float cachedMaxWidth = Float.POSITIVE_INFINITY;
    private boolean layoutDirty = true;

    /**
     * Creates a {@code TextWidget} displaying the given raw or rich-tagged text.
     *
     * @param text text content (may contain {@code {b}}, {@code {color=...}}, etc.)
     */
    public TextWidget(String text) {
        setText(text);
    }

    /**
     * Replaces the text content and rebuilds the rich-text parse tree and typewriter state.
     *
     * @param text new text (null treated as empty string)
     * @return {@code this}
     */
    public TextWidget setText(String text) {
        this.text = text != null ? text : "";
        this.parsedText = TextTagParser.parse(this.text);
        if (this.typewriterCps > 0.0f) {
            this.typewriter = new StyledTextLayout.TypewriterController(this.parsedText, this.typewriterCps);
        }
        this.layoutDirty = true;
        return this;
    }

    /**
     * Returns raw text string.
     *
     * @return raw text string
     */
    public String getText() {
        return text;
    }

    /**
     * Sets base font size in logical pixels.
     *
     * @param fontSize font size ({@code > 0})
     * @return {@code this}
     */
    public TextWidget setFontSize(float fontSize) {
        if (Float.isFinite(fontSize) && fontSize > 0.0f) {
            this.fontSize = fontSize;
            this.layoutDirty = true;
        }
        return this;
    }

    /**
     * Returns base font size in logical pixels.
     *
     * @return base font size in logical pixels
     */
    public float getFontSize() {
        return fontSize;
    }

    /**
     * Sets line spacing multiplier.
     *
     * @param lineSpacing line spacing multiplier ({@code > 0})
     * @return {@code this}
     */
    public TextWidget setLineSpacing(float lineSpacing) {
        if (Float.isFinite(lineSpacing) && lineSpacing > 0.0f) {
            this.lineSpacing = lineSpacing;
            this.layoutDirty = true;
        }
        return this;
    }

    /**
     * Returns line spacing multiplier.
     *
     * @return line spacing multiplier
     */
    public float getLineSpacing() {
        return lineSpacing;
    }

    /**
     * Sets default foreground text color hex string.
     *
     * @param colorHex hex color (defaults to {@link ThemePalette#TEXT_PRIMARY} if null/blank)
     * @return {@code this}
     */
    public TextWidget setColorHex(String colorHex) {
        this.colorHex = (colorHex == null || colorHex.isBlank()) ? ThemePalette.TEXT_PRIMARY : colorHex;
        this.layoutDirty = true;
        return this;
    }

    /**
     * Returns foreground hex color.
     *
     * @return foreground hex color
     */
    public String getColorHex() {
        return colorHex;
    }

    /**
     * Enables or disables a subtle drop shadow behind rendered glyphs.
     *
     * @param dropShadow {@code true} to enable drop shadow
     * @return {@code this}
     */
    public TextWidget setDropShadow(boolean dropShadow) {
        this.dropShadow = dropShadow;
        if (cachedLayout != null) {
            applyDropShadow(cachedLayout);
        }
        return this;
    }

    /**
     * Returns {@code true} if drop shadow is enabled.
     *
     * @return {@code true} if drop shadow is enabled
     */
    public boolean isDropShadow() {
        return dropShadow;
    }

    /**
     * Configures progressive typewriter reveal speed in characters per second ({@code <= 0} disables).
     *
     * @param cps reveal speed in characters per second
     * @return {@code this}
     */
    public TextWidget setTypewriterCps(float cps) {
        this.typewriterCps = Float.isFinite(cps) ? Math.max(0.0f, cps) : 0.0f;
        if (this.typewriterCps > 0.0f) {
            this.typewriter = new StyledTextLayout.TypewriterController(parsedText, this.typewriterCps);
        } else {
            this.typewriter = null;
        }
        return this;
    }

    /**
     * Returns configured typewriter CPS ({@code 0} when disabled).
     *
     * @return configured typewriter CPS ({@code 0} when disabled)
     */
    public float getTypewriterCps() {
        return typewriterCps;
    }

    /**
     * Sets an explicit {@link FontManager} for layout and rendering (optional).
     *
     * @param fontManager font manager (nullable)
     * @return {@code this}
     */
    public TextWidget setFontManager(FontManager fontManager) {
        this.customFontManager = fontManager;
        this.layoutDirty = true;
        return this;
    }

    /**
     * Returns active {@link StyledTextLayout.TypewriterController}, or {@code null} if disabled.
     *
     * @return active {@link StyledTextLayout.TypewriterController}, or {@code null} if disabled
     */
    public StyledTextLayout.TypewriterController getTypewriter() {
        return typewriter;
    }

    /**
     * Returns the computed {@link StyledTextLayout}, building it lazily with headless fallback if needed.
     *
     * @return computed {@link StyledTextLayout}
     */
    public StyledTextLayout getLayout() {
        float targetMaxW = cachedMaxWidth;
        if (getBounds().width() > 0.0f) {
            targetMaxW = Math.max(1.0f, getContentBounds().width());
        } else if (getPreferredWidth() > 0.0f) {
            targetMaxW = Math.max(1.0f, getPreferredWidth() - getPaddingLeft() - getPaddingRight());
        }
        return ensureLayout(targetMaxW, customFontManager, false);
    }

    private StyledTextLayout ensureLayout(float maxWidth, FontManager fm, boolean uploadGl) {
        float normalizedMax = (maxWidth > 0.0f && Float.isFinite(maxWidth)) ? maxWidth : Float.POSITIVE_INFINITY;
        if (cachedLayout == null || layoutDirty || Math.abs(normalizedMax - cachedMaxWidth) > 0.5f) {
            FontManager resolvedFm = UiRenderSupport.resolveFontManager(fm != null ? fm : customFontManager);
            cachedLayout = StyledTextLayout.layout(
                    parsedText,
                    resolvedFm,
                    normalizedMax,
                    fontSize,
                    lineSpacing,
                    colorHex,
                    uploadGl
            );
            applyDropShadow(cachedLayout);
            cachedMaxWidth = normalizedMax;
            layoutDirty = false;
        }
        return cachedLayout;
    }

    private void applyDropShadow(StyledTextLayout layout) {
        if (dropShadow) {
            layout.setDropShadow(2.0f, 2.0f, "#000000b3");
        } else {
            layout.setDropShadow(0.0f, 0.0f, null);
        }
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        float wrapWidth;
        if (getPreferredWidth() > 0.0f) {
            wrapWidth = Math.max(1.0f, getPreferredWidth() - getPaddingLeft() - getPaddingRight());
        } else if (availableWidth > 0.0f && Float.isFinite(availableWidth)) {
            wrapWidth = Math.max(1.0f, availableWidth - getPaddingLeft() - getPaddingRight());
        } else {
            wrapWidth = Float.POSITIVE_INFINITY;
        }

        StyledTextLayout layout = ensureLayout(wrapWidth, customFontManager, false);
        float w = getPreferredWidth() > 0.0f
                ? getPreferredWidth()
                : layout.getTotalWidth() + getPaddingLeft() + getPaddingRight();
        float h = getPreferredHeight() > 0.0f
                ? getPreferredHeight()
                : layout.getTotalHeight() + getPaddingTop() + getPaddingBottom();
        return new Vec2f(w, h);
    }

    @Override
    public void layout(Rect2f resolvedBounds) {
        super.layout(resolvedBounds);
        Rect2f inner = getContentBounds();
        float wrapW = inner.width() > 0.0f ? inner.width() : Float.POSITIVE_INFINITY;
        ensureLayout(wrapW, customFontManager, false);
    }

    @Override
    public void update(double dt) {
        if (typewriter != null) {
            typewriter.advance(dt);
        }
        super.update(dt);
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
        Rect2f inner = getContentBounds();
        float wrapW = inner.width() > 0.0f ? inner.width() : Float.POSITIVE_INFINITY;
        StyledTextLayout layout = ensureLayout(wrapW, fontManager, true);
        int visibleChars = typewriter != null ? typewriter.getVisibleCharCount() : -1;
        layout.render(pipeline, textureManager, inner.x(), inner.y(), elapsedSeconds, visibleChars, getAlpha());
    }
}
