package de.swfdtf.ui;

import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;

/**
 * Horizontal stacking layout container measuring children and assigning sequential horizontal bounds.
 */
public class HBox extends UiNode {

    private float spacing = 0.0f;
    private boolean alignCenterY = false;
    private boolean fillHeight = false;

    /**
     * Creates an empty {@code HBox}.
     */
    public HBox() {
    }

    /**
     * Sets horizontal spacing in logical pixels between consecutive visible children.
     *
     * @param spacing spacing in pixels ({@code >= 0})
     * @return {@code this}
     */
    public HBox setSpacing(float spacing) {
        this.spacing = Math.max(0.0f, spacing);
        return this;
    }

    /** @return horizontal spacing between children */
    public float getSpacing() {
        return spacing;
    }

    /**
     * Configures whether children are vertically centered within the content box.
     *
     * @param alignCenterY {@code true} to center children vertically
     * @return {@code this}
     */
    public HBox setAlignCenterY(boolean alignCenterY) {
        this.alignCenterY = alignCenterY;
        return this;
    }

    /** @return {@code true} if children are vertically centered */
    public boolean isAlignCenterY() {
        return alignCenterY;
    }

    /**
     * Configures whether children stretch vertically to match the content box height.
     *
     * @param fillHeight {@code true} to stretch children vertically
     * @return {@code this}
     */
    public HBox setFillHeight(boolean fillHeight) {
        this.fillHeight = fillHeight;
        return this;
    }

    /** @return {@code true} if children stretch vertically */
    public boolean isFillHeight() {
        return fillHeight;
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        float innerAvailW = Math.max(0.0f, (getPreferredWidth() > 0.0f ? getPreferredWidth() : availableWidth)
                - getPaddingLeft() - getPaddingRight());
        float innerAvailH = Math.max(0.0f, (getPreferredHeight() > 0.0f ? getPreferredHeight() : availableHeight)
                - getPaddingTop() - getPaddingBottom());

        float totalW = 0.0f;
        float maxH = 0.0f;
        int visibleCount = 0;
        for (UiNode child : children) {
            if (!child.isVisible()) {
                continue;
            }
            Vec2f childSize = child.measure(innerAvailW, innerAvailH);
            totalW += childSize.x();
            maxH = Math.max(maxH, childSize.y());
            visibleCount++;
        }
        if (visibleCount > 1) {
            totalW += spacing * (visibleCount - 1);
        }

        float w = getPreferredWidth() > 0.0f
                ? getPreferredWidth()
                : totalW + getPaddingLeft() + getPaddingRight();
        float h = getPreferredHeight() > 0.0f
                ? getPreferredHeight()
                : maxH + getPaddingTop() + getPaddingBottom();
        return new Vec2f(w, h);
    }

    @Override
    public void layout(Rect2f resolvedBounds) {
        setBounds(resolvedBounds);
        Rect2f inner = getContentBounds();
        float cursorX = inner.x();
        for (UiNode child : children) {
            if (!child.isVisible()) {
                continue;
            }
            float remainingW = Math.max(0.0f, inner.right() - cursorX);
            Vec2f measured = child.measure(remainingW, inner.height());
            float childW = measured.x();
            float childH = fillHeight ? inner.height() : measured.y();
            float childY = (!fillHeight && alignCenterY)
                    ? inner.y() + (inner.height() - childH) * 0.5f
                    : inner.y();
            child.layout(new Rect2f(cursorX, childY, childW, childH));
            cursorX += childW + spacing;
        }
    }
}
