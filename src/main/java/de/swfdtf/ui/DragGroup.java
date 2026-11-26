package de.swfdtf.ui;

import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Container coordinating drag-and-drop interactions across {@link DragItem} children:
 * drag initiation, pointer tracking, target intersection snapping, and drop callbacks.
 */
public class DragGroup extends UiNode {

    private final List<DragItem> items = new ArrayList<>();
    private DragItem activeDraggedItem;
    private float dragOffsetX = 0.0f;
    private float dragOffsetY = 0.0f;

    /**
     * Creates an empty {@code DragGroup}.
     */
    public DragGroup() {
    }

    /**
     * Registers a {@link DragItem} inside this group.
     *
     * @param item drag item or drop target
     * @return {@code this}
     */
    public DragGroup addDragItem(DragItem item) {
        if (item != null) {
            if (!items.contains(item)) {
                items.add(item);
            }
            if (!children.contains(item)) {
                super.addChild(item);
            }
        }
        return this;
    }

    @Override
    public UiNode addChild(UiNode child) {
        super.addChild(child);
        if (child instanceof DragItem item && !items.contains(item)) {
            items.add(item);
        }
        return this;
    }

    @Override
    public boolean removeChild(UiNode child) {
        if (child instanceof DragItem item) {
            items.remove(item);
            if (activeDraggedItem == item) {
                activeDraggedItem = null;
            }
        }
        return super.removeChild(child);
    }

    @Override
    public void clearChildren() {
        items.clear();
        activeDraggedItem = null;
        super.clearChildren();
    }

    /**
     * Returns unmodifiable list of {@link DragItem}s managed by this group.
     *
     * @return unmodifiable list of {@link DragItem}s managed by this group
     */
    public List<DragItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    /**
     * Returns currently dragged {@link DragItem}, or {@code null}.
     *
     * @return currently dragged {@link DragItem}, or {@code null}
     */
    public DragItem getActiveDraggedItem() {
        return activeDraggedItem;
    }

    /**
     * Finds the topmost eligible drop target containing logical coordinate {@code (lx, ly)},
     * excluding {@code dragged}.
     *
     * @param lx      logical X coordinate
     * @param ly      logical Y coordinate
     * @param dragged item currently being dragged (excluded from match)
     * @return matching drop target {@link DragItem}, or {@code null}
     */
    public DragItem findDropTargetAt(float lx, float ly, DragItem dragged) {
        for (int i = items.size() - 1; i >= 0; i--) {
            DragItem candidate = items.get(i);
            if (candidate != dragged
                    && candidate.isVisible()
                    && candidate.isEnabled()
                    && candidate.isDropTarget()
                    && candidate.getBounds().contains(lx, ly)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Finds the drop target with the greatest overlapping area against {@code dragged}'s bounds.
     *
     * @param dragged dragged item
     * @return best intersecting drop target, or {@code null}
     */
    public DragItem findIntersectingDropTarget(DragItem dragged) {
        if (dragged == null) {
            return null;
        }
        Rect2f dragBounds = dragged.getBounds();
        DragItem best = null;
        float bestArea = 0.0f;
        for (int i = items.size() - 1; i >= 0; i--) {
            DragItem candidate = items.get(i);
            if (candidate == dragged || !candidate.isVisible() || !candidate.isEnabled() || !candidate.isDropTarget()) {
                continue;
            }
            Rect2f tb = candidate.getBounds();
            if (dragBounds.intersects(tb)) {
                float overlapW = Math.min(dragBounds.right(), tb.right()) - Math.max(dragBounds.x(), tb.x());
                float overlapH = Math.min(dragBounds.bottom(), tb.bottom()) - Math.max(dragBounds.y(), tb.y());
                float area = Math.max(0.0f, overlapW) * Math.max(0.0f, overlapH);
                if (area > bestArea) {
                    bestArea = area;
                    best = candidate;
                }
            }
        }
        return best;
    }

    @Override
    public void layout(Rect2f resolvedBounds) {
        setBounds(resolvedBounds);
        Rect2f inner = getContentBounds();
        for (UiNode child : children) {
            if (!child.isVisible()) {
                continue;
            }
            if (child instanceof DragItem item) {
                Rect2f existing = item.getBounds();
                if (item.isDragging()
                        || item.getSnappedTo() != null
                        || (existing.width() > 0.0f && existing.height() > 0.0f)) {
                    // Preserve interactive drag/snap positions across frame relayouts
                    continue;
                }
                Vec2f measured = item.measure(inner.width(), inner.height());
                item.layout(new Rect2f(inner.x(), inner.y(), measured.x(), measured.y()));
            } else {
                child.layout(inner);
            }
        }
    }

    @Override
    public UiNode dispatchMouseMove(float lx, float ly) {
        if (!isVisible()) {
            setHoveredDeep(false);
            return null;
        }
        if (activeDraggedItem != null && activeDraggedItem.isEnabled()) {
            Rect2f prev = activeDraggedItem.getBounds();
            float nextX = lx - dragOffsetX;
            float nextY = ly - dragOffsetY;
            activeDraggedItem.layout(new Rect2f(nextX, nextY, prev.width(), prev.height()));
        }
        return super.dispatchMouseMove(lx, ly);
    }

    @Override
    public boolean dispatchMouseDown(float lx, float ly, int button) {
        if (!isVisible() || !isEnabled() || !getBounds().contains(lx, ly)) {
            return false;
        }
        if (button == 0) {
            for (int i = items.size() - 1; i >= 0; i--) {
                DragItem item = items.get(i);
                if (item.isVisible() && item.isEnabled() && item.isDraggable() && item.getBounds().contains(lx, ly)) {
                    activeDraggedItem = item;
                    item.setDragging(true);
                    item.setSnappedTo(null);
                    dragOffsetX = lx - item.getBounds().x();
                    dragOffsetY = ly - item.getBounds().y();
                    item.setFocused(true);
                    return true;
                }
            }
        }
        return super.dispatchMouseDown(lx, ly, button);
    }

    @Override
    public boolean dispatchMouseUp(float lx, float ly, int button) {
        if (!isVisible()) {
            return false;
        }
        if (button == 0 && activeDraggedItem != null) {
            DragItem dragged = activeDraggedItem;
            activeDraggedItem = null;
            dragged.setDragging(false);

            DragItem target = findDropTargetAt(lx, ly, dragged);
            if (target == null) {
                target = findIntersectingDropTarget(dragged);
            }
            if (target != null) {
                if (dragged.isSnappable() && target.isSnappable()) {
                    Rect2f tb = target.getBounds();
                    Rect2f db = dragged.getBounds();
                    float snapX = tb.centerX() - db.width() * 0.5f;
                    float snapY = tb.centerY() - db.height() * 0.5f;
                    dragged.layout(new Rect2f(snapX, snapY, db.width(), db.height()));
                    dragged.setSnappedTo(target);
                }
                if (target.getOnDrop() != null) {
                    target.getOnDrop().accept(dragged, target);
                }
                if (dragged.getOnDrop() != null && dragged.getOnDrop() != target.getOnDrop()) {
                    dragged.getOnDrop().accept(dragged, target);
                }
            }
            super.dispatchMouseUp(lx, ly, button);
            return true;
        }
        return super.dispatchMouseUp(lx, ly, button);
    }

    @Override
    public void render(
            RenderPipeline2D pipeline,
            FontManager fontManager,
            TextureManager textureManager,
            double elapsedSeconds
    ) {
        if (!isVisible() || getAlpha() <= 0.0f) {
            return;
        }
        drawBackgroundBox(pipeline, getAlpha());
        renderSelf(pipeline, fontManager, textureManager, elapsedSeconds);
        for (UiNode child : children) {
            if (child.isVisible() && child != activeDraggedItem) {
                child.render(pipeline, fontManager, textureManager, elapsedSeconds);
            }
        }
        if (activeDraggedItem != null && activeDraggedItem.isVisible()) {
            activeDraggedItem.render(pipeline, fontManager, textureManager, elapsedSeconds);
        }
    }
}
