package de.swfdtf.ui;

import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;

import java.util.ArrayList;
import java.util.List;

/**
 * Responsive flexbox-like wrapping container ({@code #0376}) that flows visible child nodes
 * horizontally and automatically wraps onto new rows when {@link #getContentBounds()} width is exceeded.
 */
public class WrapBox extends UiNode {

    private float horizontalSpacing = 8.0f;
    private float verticalSpacing = 8.0f;

    /**
     * Creates a {@code WrapBox} with default {@code 8 px} gaps.
     */
    public WrapBox() {
    }

    /**
     * Creates a {@code WrapBox} with explicit horizontal and vertical item gaps.
     *
     * @param horizontalSpacing gap between sibling items on the same row
     * @param verticalSpacing   gap between wrapped rows
     */
    public WrapBox(float horizontalSpacing, float verticalSpacing) {
        setHorizontalSpacing(horizontalSpacing);
        setVerticalSpacing(verticalSpacing);
    }

    /**
     * Sets horizontal spacing between items in a row.
     *
     * @param horizontalSpacing non-negative gap in pixels
     * @return {@code this}
     */
    public WrapBox setHorizontalSpacing(float horizontalSpacing) {
        this.horizontalSpacing = Float.isFinite(horizontalSpacing) ? Math.max(0.0f, horizontalSpacing) : 0.0f;
        return this;
    }

    /**
     * Returns horizontal item spacing.
     *
     * @return horizontal spacing in pixels
     */
    public float getHorizontalSpacing() {
        return horizontalSpacing;
    }

    /**
     * Sets vertical spacing between wrapped rows.
     *
     * @param verticalSpacing non-negative gap in pixels
     * @return {@code this}
     */
    public WrapBox setVerticalSpacing(float verticalSpacing) {
        this.verticalSpacing = Float.isFinite(verticalSpacing) ? Math.max(0.0f, verticalSpacing) : 0.0f;
        return this;
    }

    /**
     * Returns vertical row spacing.
     *
     * @return vertical spacing in pixels
     */
    public float getVerticalSpacing() {
        return verticalSpacing;
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        float maxRowWidth = Math.max(
                1.0f,
                (getPreferredWidth() > 0.0f ? getPreferredWidth() : availableWidth)
                        - getPaddingLeft() - getPaddingRight()
        );
        float cursorX = 0.0f;
        float rowHeight = 0.0f;
        float totalHeight = 0.0f;
        float usedMaxWidth = 0.0f;
        boolean hasRowItems = false;

        for (UiNode child : children) {
            if (!child.isVisible()) {
                continue;
            }
            Vec2f childSize = child.measure(maxRowWidth, availableHeight);
            float cw = Math.min(maxRowWidth, Math.max(0.0f, childSize.x()));
            float ch = Math.max(0.0f, childSize.y());
            float neededX = hasRowItems ? (cursorX + horizontalSpacing + cw) : cw;
            if (hasRowItems && neededX > maxRowWidth) {
                totalHeight += rowHeight + verticalSpacing;
                usedMaxWidth = Math.max(usedMaxWidth, cursorX);
                cursorX = cw;
                rowHeight = ch;
            } else {
                cursorX = neededX;
                rowHeight = Math.max(rowHeight, ch);
                hasRowItems = true;
            }
        }
        if (hasRowItems) {
            totalHeight += rowHeight;
            usedMaxWidth = Math.max(usedMaxWidth, cursorX);
        }

        float w = getPreferredWidth() > 0.0f
                ? getPreferredWidth()
                : usedMaxWidth + getPaddingLeft() + getPaddingRight();
        float h = getPreferredHeight() > 0.0f
                ? getPreferredHeight()
                : totalHeight + getPaddingTop() + getPaddingBottom();
        return new Vec2f(w, h);
    }

    @Override
    public void layout(Rect2f resolvedBounds) {
        setBounds(resolvedBounds);
        Rect2f inner = getContentBounds();
        float maxW = Math.max(1.0f, inner.width());

        List<UiNode> rowNodes = new ArrayList<>();
        List<Vec2f> rowSizes = new ArrayList<>();
        float cursorX = 0.0f;
        float cursorY = inner.y();
        float rowHeight = 0.0f;

        for (UiNode child : children) {
            if (!child.isVisible()) {
                continue;
            }
            Vec2f sz = child.measure(maxW, inner.height());
            float cw = Math.min(maxW, Math.max(0.0f, sz.x()));
            float ch = Math.max(0.0f, sz.y());
            float nextX = rowNodes.isEmpty() ? cw : (cursorX + horizontalSpacing + cw);

            if (!rowNodes.isEmpty() && nextX > maxW) {
                layoutRow(rowNodes, rowSizes, inner.x(), cursorY, rowHeight);
                cursorY += rowHeight + verticalSpacing;
                rowNodes.clear();
                rowSizes.clear();
                cursorX = cw;
                rowHeight = ch;
            } else {
                cursorX = nextX;
                rowHeight = Math.max(rowHeight, ch);
            }
            rowNodes.add(child);
            rowSizes.add(new Vec2f(cw, ch));
        }

        if (!rowNodes.isEmpty()) {
            layoutRow(rowNodes, rowSizes, inner.x(), cursorY, rowHeight);
        }
    }

    private void layoutRow(
            List<UiNode> rowNodes,
            List<Vec2f> rowSizes,
            float startX,
            float rowY,
            float rowHeight
    ) {
        float x = startX;
        for (int i = 0; i < rowNodes.size(); i++) {
            UiNode node = rowNodes.get(i);
            Vec2f sz = rowSizes.get(i);
            float y = rowY + Math.max(0.0f, (rowHeight - sz.y()) * 0.5f);
            node.layout(new Rect2f(x, y, sz.x(), sz.y()));
            x += sz.x() + horizontalSpacing;
        }
    }

    @Override
    protected void renderSelf(
            RenderPipeline2D pipeline,
            FontManager fontManager,
            TextureManager textureManager,
            double elapsedSeconds
    ) {
        // Container background rendered by UiNode#drawBackgroundBox.
    }
}
