package de.swfdtf.ui;

import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;

import java.util.ArrayList;
import java.util.List;

/**
 * Vertical stacking layout container measuring children and assigning sequential vertical bounds.
 */
public class VBox extends UiNode {

    private float spacing = 0.0f;
    private boolean fillWidth = false;
    private boolean alignCenterX = false;

    /**
     * Creates an empty {@code VBox}.
     */
    public VBox() {
    }

    /**
     * Sets vertical spacing in logical pixels between consecutive visible children.
     *
     * @param spacing spacing in pixels ({@code >= 0})
     * @return {@code this}
     */
    public VBox setSpacing(float spacing) {
        this.spacing = Math.max(0.0f, spacing);
        return this;
    }

    /**
     * Returns vertical spacing between children.
     *
     * @return vertical spacing between children
     */
    public float getSpacing() {
        return spacing;
    }

    /**
     * Configures whether children are stretched horizontally to fill the container's content width.
     *
     * @param fillWidth {@code true} to stretch children to content width
     * @return {@code this}
     */
    public VBox setFillWidth(boolean fillWidth) {
        this.fillWidth = fillWidth;
        return this;
    }

    /**
     * Returns {@code true} if children stretch to content width.
     *
     * @return {@code true} if children stretch to content width
     */
    public boolean isFillWidth() {
        return fillWidth;
    }

    /**
     * Configures whether non-stretched children are horizontally centered within the content box.
     *
     * @param alignCenterX {@code true} to center children horizontally
     * @return {@code this}
     */
    public VBox setAlignCenterX(boolean alignCenterX) {
        this.alignCenterX = alignCenterX;
        return this;
    }

    /**
     * Returns {@code true} if children are horizontally centered.
     *
     * @return {@code true} if children are horizontally centered
     */
    public boolean isAlignCenterX() {
        return alignCenterX;
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        float innerAvailW = Math.max(0.0f, (getPreferredWidth() > 0.0f ? getPreferredWidth() : availableWidth)
                - getPaddingLeft() - getPaddingRight());
        float innerAvailH = Math.max(0.0f, (getPreferredHeight() > 0.0f ? getPreferredHeight() : availableHeight)
                - getPaddingTop() - getPaddingBottom());

        float maxW = 0.0f;
        float totalH = 0.0f;
        int visibleCount = 0;
        for (UiNode child : children) {
            if (!child.isVisible()) {
                continue;
            }
            Vec2f childSize = child.measure(innerAvailW, innerAvailH);
            maxW = Math.max(maxW, childSize.x());
            totalH += childSize.y();
            visibleCount++;
        }
        if (visibleCount > 1) {
            totalH += spacing * (visibleCount - 1);
        }

        float w = getPreferredWidth() > 0.0f
                ? getPreferredWidth()
                : maxW + getPaddingLeft() + getPaddingRight();
        float h = getPreferredHeight() > 0.0f
                ? getPreferredHeight()
                : totalH + getPaddingTop() + getPaddingBottom();
        return new Vec2f(w, h);
    }

    @Override
    public void layout(Rect2f resolvedBounds) {
        setBounds(resolvedBounds);
        Rect2f inner = getContentBounds();
        List<UiNode> visibleChildren = new ArrayList<>();
        for (UiNode child : children) {
            if (child.isVisible()) {
                visibleChildren.add(child);
            }
        }

        float cursorY = inner.y();
        for (UiNode child : visibleChildren) {
            float remainingH = Math.max(0.0f, inner.bottom() - cursorY);
            Vec2f measured = child.measure(inner.width(), remainingH);
            float childW = fillWidth ? inner.width() : measured.x();
            float childH = measured.y();
            float childX = (!fillWidth && alignCenterX)
                    ? inner.x() + (inner.width() - childW) * 0.5f
                    : inner.x();
            child.layout(new Rect2f(childX, cursorY, childW, childH));
            cursorY += childH + spacing;
        }
    }
}
