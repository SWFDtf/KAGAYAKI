package de.swfdtf.ui;

import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.StyledTextLayout;
import de.swfdtf.graphics.TextTagParser;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * Interactive single-line text input box supporting character whitelisting, maximum length
 * clamping, Backspace deletion, placeholder text, and Enter submission.
 */
public class TextInput extends UiNode {

    /** Default preferred width in logical pixels. */
    public static final float DEFAULT_WIDTH = 340.0f;

    /** Default preferred height in logical pixels. */
    public static final float DEFAULT_HEIGHT = 44.0f;

    /** Default input font size in logical pixels. */
    public static final float DEFAULT_FONT_SIZE = 20.0f;

    private String text = "";
    private int cursorIndex = 0;
    private String placeholder = "";
    private String allowChars;
    private int maxLength = 0;
    private float fontSize = DEFAULT_FONT_SIZE;
    private Consumer<String> onSubmit;
    private Consumer<String> onChanged;

    /**
     * Creates a {@code TextInput} field.
     *
     * @param initialText initial text value (nullable)
     * @param onSubmit    callback invoked on Enter key press (nullable)
     */
    public TextInput(String initialText, Consumer<String> onSubmit) {
        this.onSubmit = onSubmit;
        setFocusable(true);
        setCornerRadius(6.0f);
        setBorderThickness(1.0f);
        setPadding(12.0f, 8.0f);
        setText(initialText);
    }

    /**
     * Sets the current input text, applying any active character filter and length cap.
     *
     * @param text new text value (null treated as empty)
     * @return {@code this}
     */
    public TextInput setText(String text) {
        String raw = text != null ? text : "";
        StringBuilder filtered = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (!Character.isISOControl(ch) && isCharAllowed(ch)) {
                if (maxLength > 0 && filtered.length() >= maxLength) {
                    break;
                }
                filtered.append(ch);
            }
        }
        this.text = filtered.toString();
        this.cursorIndex = this.text.length();
        return this;
    }

    /**
     * Returns current zero-based caret offset inside {@link #getText()}.
     *
     * @return cursor character index
     */
    public int getCursorIndex() {
        return cursorIndex;
    }

    /**
     * Moves the caret to {@code cursorIndex}, clamped to {@code [0, getText().length()]}.
     *
     * @param cursorIndex desired cursor position
     * @return {@code this}
     */
    public TextInput setCursorIndex(int cursorIndex) {
        this.cursorIndex = Math.clamp(cursorIndex, 0, text.length());
        return this;
    }

    /**
     * Returns current input text.
     *
     * @return current input text
     */
    public String getText() {
        return text;
    }

    /**
     * Sets placeholder text shown in muted color when the input is empty.
     *
     * @param placeholder placeholder string (null treated as empty)
     * @return {@code this}
     */
    public TextInput setPlaceholder(String placeholder) {
        this.placeholder = placeholder != null ? placeholder : "";
        return this;
    }

    /**
     * Returns placeholder text.
     *
     * @return placeholder text
     */
    public String getPlaceholder() {
        return placeholder;
    }

    /**
     * Restricts typed characters to those contained in {@code allowChars} (null/empty allows all).
     *
     * @param allowChars whitelist string (nullable)
     * @return {@code this}
     */
    public TextInput setAllowChars(String allowChars) {
        this.allowChars = allowChars;
        setText(this.text);
        return this;
    }

    /**
     * Returns allowed-character whitelist, or {@code null}.
     *
     * @return allowed-character whitelist, or {@code null}
     */
    public String getAllowChars() {
        return allowChars;
    }

    /**
     * Sets the maximum number of characters ({@code <= 0} means unlimited).
     *
     * @param maxLength max character count
     * @return {@code this}
     */
    public TextInput setMaxLength(int maxLength) {
        this.maxLength = Math.max(0, maxLength);
        if (this.maxLength > 0 && this.text.length() > this.maxLength) {
            this.text = this.text.substring(0, this.maxLength);
        }
        return this;
    }

    /**
     * Returns maximum allowed length ({@code 0} when unlimited).
     *
     * @return maximum allowed length ({@code 0} when unlimited)
     */
    public int getMaxLength() {
        return maxLength;
    }

    /**
     * Sets the Enter-submit callback.
     *
     * @param onSubmit submit callback (nullable)
     * @return {@code this}
     */
    public TextInput setOnSubmit(Consumer<String> onSubmit) {
        this.onSubmit = onSubmit;
        return this;
    }

    /**
     * Sets an optional live-change callback invoked whenever characters are inserted or deleted.
     *
     * @param onChanged change listener (nullable)
     * @return {@code this}
     */
    public TextInput setOnChanged(Consumer<String> onChanged) {
        this.onChanged = onChanged;
        return this;
    }

    /**
     * Sets the text font size in logical pixels.
     *
     * @param fontSize font size ({@code > 0})
     * @return {@code this}
     */
    public TextInput setFontSize(float fontSize) {
        if (Float.isFinite(fontSize) && fontSize > 0.0f) {
            this.fontSize = fontSize;
        }
        return this;
    }

    /**
     * Returns font size in logical pixels.
     *
     * @return font size in logical pixels
     */
    public float getFontSize() {
        return fontSize;
    }

    private boolean isCharAllowed(char ch) {
        return allowChars == null || allowChars.isEmpty() || allowChars.indexOf(ch) >= 0;
    }

    private void clearFocusInTree(UiNode node) {
        if (node == null) {
            return;
        }
        node.setFocused(false);
        for (UiNode child : node.getChildren()) {
            clearFocusInTree(child);
        }
    }

    private UiNode findRoot() {
        UiNode curr = this;
        while (curr.getParent() != null) {
            curr = curr.getParent();
        }
        return curr;
    }

    @Override
    protected boolean isInteractive() {
        return true;
    }

    @Override
    protected boolean onMouseDown(float lx, float ly, int button) {
        if (button == 0 && isEnabled() && getBounds().contains(lx, ly)) {
            clearFocusInTree(findRoot());
            setFocused(true);
            cursorIndex = text.length();
            return true;
        }
        return false;
    }

    @Override
    protected boolean onCharTyped(char ch) {
        if (!isEnabled() || !isFocused() || Character.isISOControl(ch)) {
            return false;
        }
        if (!isCharAllowed(ch)) {
            return true;
        }
        if (maxLength > 0 && text.length() >= maxLength) {
            return true;
        }
        int idx = Math.clamp(cursorIndex, 0, text.length());
        text = text.substring(0, idx) + ch + text.substring(idx);
        cursorIndex = idx + 1;
        if (onChanged != null) {
            onChanged.accept(text);
        }
        return true;
    }

    @Override
    protected boolean onKeyPressed(int key, int mods) {
        if (!isEnabled() || !isFocused()) {
            return false;
        }
        if (key == GLFW.GLFW_KEY_LEFT) {
            if (cursorIndex > 0) {
                cursorIndex--;
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT) {
            if (cursorIndex < text.length()) {
                cursorIndex++;
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_HOME) {
            cursorIndex = 0;
            return true;
        }
        if (key == GLFW.GLFW_KEY_END) {
            cursorIndex = text.length();
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (cursorIndex > 0 && !text.isEmpty()) {
                int idx = Math.clamp(cursorIndex, 1, text.length());
                text = text.substring(0, idx - 1) + text.substring(idx);
                cursorIndex = idx - 1;
                if (onChanged != null) {
                    onChanged.accept(text);
                }
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_DELETE) {
            if (cursorIndex < text.length()) {
                text = text.substring(0, cursorIndex) + text.substring(cursorIndex + 1);
                if (onChanged != null) {
                    onChanged.accept(text);
                }
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (onSubmit != null) {
                onSubmit.accept(text);
            }
            return true;
        }
        return false;
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        float w = getPreferredWidth() > 0.0f
                ? getPreferredWidth()
                : (availableWidth > 0.0f ? Math.min(DEFAULT_WIDTH, availableWidth) : DEFAULT_WIDTH);
        float h = getPreferredHeight() > 0.0f ? getPreferredHeight() : DEFAULT_HEIGHT;
        return new Vec2f(w, h);
    }

    @Override
    protected void drawBackgroundBox(RenderPipeline2D pipeline, float effectiveAlpha) {
        Rect2f b = getBounds();
        String fillHex = !isEnabled() ? ThemePalette.PANEL_SURFACE : ThemePalette.ELEVATED;
        String borderHex = isFocused()
                ? ThemePalette.ACCENT_HOVER
                : (isHovered() ? ThemePalette.ACCENT_IDLE : ThemePalette.BORDER);
        UiRenderSupport.drawRoundedRect(
                pipeline, b.x(), b.y(), b.width(), b.height(),
                getCornerRadius(), fillHex, getBorderThickness(), borderHex, effectiveAlpha
        );
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
        String display = text.isEmpty() ? placeholder : text;
        String color = text.isEmpty() ? ThemePalette.TEXT_MUTED : ThemePalette.TEXT_PRIMARY;
        if (!isEnabled()) {
            color = ThemePalette.DISABLED;
        }
        StyledTextLayout layout = StyledTextLayout.layout(
                TextTagParser.parse(display),
                UiRenderSupport.resolveFontManager(fontManager),
                Float.POSITIVE_INFINITY,
                fontSize,
                1.1f,
                color,
                true
        );
        float textY = inner.y() + Math.max(0.0f, (inner.height() - layout.getTotalHeight()) * 0.5f);
        UiRenderSupport.ScissorState scissor = UiRenderSupport.pushScissor(pipeline, inner);
        try {
            if (!display.isEmpty()) {
                layout.render(pipeline, textureManager, inner.x(), textY, elapsedSeconds, -1, getAlpha());
            }
            if (isFocused() && isEnabled() && ((long) (elapsedSeconds * 2.0) % 2L) == 0L) {
                float caretX = inner.x() + (text.isEmpty() ? 0.0f : layout.getTotalWidth()) + 2.0f;
                float caretH = Math.min(inner.height(), fontSize * 1.15f);
                float caretY = inner.y() + Math.max(0.0f, (inner.height() - caretH) * 0.5f);
                UiRenderSupport.drawRect(pipeline, caretX, caretY, 2.0f, caretH, ThemePalette.ACCENT_HOVER, getAlpha());
            }
        } finally {
            UiRenderSupport.popScissor(scissor);
        }
    }
}
