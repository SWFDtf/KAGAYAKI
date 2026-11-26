package de.swfdtf.ui;

import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Explicit-coordinate layout container placing children at fixed offsets or normalized alignments
 * inside its content bounds.
 */
public class Fixed extends UiNode {

    /**
     * Optional alignment placement for children without an explicit pixel rectangle.
     *
     * @param xPos      horizontal pixel offset
     * @param yPos      vertical pixel offset
     * @param xAlign    normalized horizontal alignment
     * @param yAlign    normalized vertical alignment
     * @param hasXPos   whether {@code xPos} is set
     * @param hasYPos   whether {@code yPos} is set
     * @param hasXAlign whether {@code xAlign} is set
     * @param hasYAlign whether {@code yAlign} is set
     */
    public record AlignmentSpec(
            float xPos,
            float yPos,
            float xAlign,
            float yAlign,
            boolean hasXPos,
            boolean hasYPos,
            boolean hasXAlign,
            boolean hasYAlign
    ) {
    }

    private final Map<UiNode, Rect2f> explicitBounds = new IdentityHashMap<>();
    private final Map<UiNode, AlignmentSpec> alignments = new IdentityHashMap<>();

    /**
     * Creates an empty {@code Fixed} container.
     */
    public Fixed() {
    }

    /**
     * Adds a child positioned at explicit content-relative coordinates and dimensions.
     *
     * @param child  child node
     * @param x      content-relative X coordinate
     * @param y      content-relative Y coordinate
     * @param width  assigned width ({@code <= 0} uses child's measured width)
     * @param height assigned height ({@code <= 0} uses child's measured height)
     * @return {@code this}
     */
    public Fixed addChildAt(UiNode child, float x, float y, float width, float height) {
        if (child != null) {
            addChild(child);
            explicitBounds.put(child, new Rect2f(x, y, width, height));
        }
        return this;
    }

    /**
     * Associates alignment metadata with a child added to this {@code Fixed} container.
     *
     * @param child child node
     * @param spec  alignment specification
     * @return {@code this}
     */
    public Fixed setChildAlignment(UiNode child, AlignmentSpec spec) {
        if (child != null && spec != null) {
            alignments.put(child, spec);
        }
        return this;
    }

    @Override
    public boolean removeChild(UiNode child) {
        explicitBounds.remove(child);
        alignments.remove(child);
        return super.removeChild(child);
    }

    @Override
    public void clearChildren() {
        explicitBounds.clear();
        alignments.clear();
        super.clearChildren();
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        float innerW = Math.max(0.0f, availableWidth - getPaddingLeft() - getPaddingRight());
        float innerH = Math.max(0.0f, availableHeight - getPaddingTop() - getPaddingBottom());
        float maxRight = 0.0f;
        float maxBottom = 0.0f;

        for (UiNode child : children) {
            if (!child.isVisible()) {
                continue;
            }
            Rect2f fixed = explicitBounds.get(child);
            if (fixed != null) {
                Vec2f measured = (fixed.width() <= 0.0f || fixed.height() <= 0.0f)
                        ? child.measure(innerW, innerH)
                        : Vec2f.ZERO;
                float w = fixed.width() > 0.0f ? fixed.width() : measured.x();
                float h = fixed.height() > 0.0f ? fixed.height() : measured.y();
                maxRight = Math.max(maxRight, fixed.x() + w);
                maxBottom = Math.max(maxBottom, fixed.y() + h);
            } else {
                Vec2f measured = child.measure(innerW, innerH);
                maxRight = Math.max(maxRight, measured.x());
                maxBottom = Math.max(maxBottom, measured.y());
            }
        }

        float w = getPreferredWidth() > 0.0f
                ? getPreferredWidth()
                : (maxRight > 0.0f ? maxRight + getPaddingLeft() + getPaddingRight() : availableWidth);
        float h = getPreferredHeight() > 0.0f
                ? getPreferredHeight()
                : (maxBottom > 0.0f ? maxBottom + getPaddingTop() + getPaddingBottom() : availableHeight);
        return new Vec2f(w, h);
    }

    @Override
    public void layout(Rect2f resolvedBounds) {
        setBounds(resolvedBounds);
        Rect2f inner = getContentBounds();
        for (UiNode child : children) {
            if (!child.isVisible()) {
                continue;
            }
            Rect2f fixed = explicitBounds.get(child);
            if (fixed != null) {
                Vec2f measured = (fixed.width() <= 0.0f || fixed.height() <= 0.0f)
                        ? child.measure(inner.width(), inner.height())
                        : Vec2f.ZERO;
                float w = fixed.width() > 0.0f ? fixed.width() : measured.x();
                float h = fixed.height() > 0.0f ? fixed.height() : measured.y();
                child.layout(new Rect2f(inner.x() + fixed.x(), inner.y() + fixed.y(), w, h));
                continue;
            }

            AlignmentSpec align = alignments.get(child);
            Vec2f measured = child.measure(inner.width(), inner.height());
            float w = child.getPreferredWidth() > 0.0f ? child.getPreferredWidth() : measured.x();
            float h = child.getPreferredHeight() > 0.0f ? child.getPreferredHeight() : measured.y();
            float x = inner.x();
            float y = inner.y();
            if (align != null) {
                if (align.hasXPos()) {
                    x = inner.x() + align.xPos();
                } else if (align.hasXAlign()) {
                    x = inner.x() + (inner.width() - w) * align.xAlign();
                }
                if (align.hasYPos()) {
                    y = inner.y() + align.yPos();
                } else if (align.hasYAlign()) {
                    y = inner.y() + (inner.height() - h) * align.yAlign();
                }
            }
            child.layout(new Rect2f(x, y, w, h));
        }
    }
}
