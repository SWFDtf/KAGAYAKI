package de.swfdtf.ui;

import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Vec2f;

import java.util.function.BiConsumer;

/**
 * Draggable item and/or drop-target widget managed by {@link DragGroup}.
 */
public class DragItem extends UiNode {

    /** Default item width when unconstrained. */
    public static final float DEFAULT_SIZE = 96.0f;

    private Object payload;
    private boolean dropTarget = false;
    private boolean draggable = true;
    private boolean snappable = true;
    private boolean dragging = false;
    private DragItem snappedTo;
    private BiConsumer<DragItem, DragItem> onDrop;

    /**
     * Creates a {@code DragItem} with identifier and optional payload object.
     *
     * @param id      unique item identifier
     * @param payload optional domain payload (nullable)
     */
    public DragItem(String id, Object payload) {
        setId(id);
        this.payload = payload;
        setFocusable(true);
        setCornerRadius(6.0f);
        setBorderThickness(1.0f);
    }

    /**
     * Returns optional domain payload.
     *
     * @return optional domain payload
     */
    public Object getPayload() {
        return payload;
    }

    /**
     * Sets the domain payload associated with this drag item.
     *
     * @param payload payload object (nullable)
     * @return {@code this}
     */
    public DragItem setPayload(Object payload) {
        this.payload = payload;
        return this;
    }

    /**
     * Configures whether this item acts as a drop target for dragged items.
     *
     * @param dropTarget {@code true} if drop target
     * @return {@code this}
     */
    public DragItem setDropTarget(boolean dropTarget) {
        this.dropTarget = dropTarget;
        return this;
    }

    /**
     * Returns {@code true} if this item accepts dropped items.
     *
     * @return {@code true} if this item accepts dropped items
     */
    public boolean isDropTarget() {
        return dropTarget;
    }

    /**
     * Configures whether this item can be picked up and dragged.
     *
     * @param draggable {@code true} if draggable
     * @return {@code this}
     */
    public DragItem setDraggable(boolean draggable) {
        this.draggable = draggable;
        return this;
    }

    /**
     * Returns {@code true} if draggable.
     *
     * @return {@code true} if draggable
     */
    public boolean isDraggable() {
        return draggable;
    }

    /**
     * Configures whether this item snaps onto a matching drop target upon release.
     *
     * @param snappable {@code true} to snap on drop
     * @return {@code this}
     */
    public DragItem setSnappable(boolean snappable) {
        this.snappable = snappable;
        return this;
    }

    /**
     * Returns {@code true} if snapping is enabled.
     *
     * @return {@code true} if snapping is enabled
     */
    public boolean isSnappable() {
        return snappable;
    }

    /**
     * Registers a drop callback invoked as {@code onDrop.accept(draggedItem, dropTarget)}.
     *
     * @param onDrop drop handler (nullable)
     * @return {@code this}
     */
    public DragItem setOnDrop(BiConsumer<DragItem, DragItem> onDrop) {
        this.onDrop = onDrop;
        return this;
    }

    /**
     * Returns configured drop callback.
     *
     * @return configured drop callback
     */
    public BiConsumer<DragItem, DragItem> getOnDrop() {
        return onDrop;
    }

    /**
     * Returns the {@link DragItem} drop target this item is currently snapped onto, or {@code null}.
     *
     * @return the {@link DragItem} drop target this item is currently snapped onto, or {@code null}
     */
    public DragItem getSnappedTo() {
        return snappedTo;
    }

    void setSnappedTo(DragItem snappedTo) {
        this.snappedTo = snappedTo;
    }

    /**
     * Returns {@code true} while currently being dragged.
     *
     * @return {@code true} while currently being dragged
     */
    public boolean isDragging() {
        return dragging;
    }

    void setDragging(boolean dragging) {
        this.dragging = dragging;
    }

    @Override
    protected boolean isInteractive() {
        return draggable || dropTarget;
    }

    @Override
    public Vec2f measure(float availableWidth, float availableHeight) {
        float w = getPreferredWidth() > 0.0f ? getPreferredWidth() : DEFAULT_SIZE;
        float h = getPreferredHeight() > 0.0f ? getPreferredHeight() : DEFAULT_SIZE;
        return new Vec2f(w, h);
    }

    @Override
    protected void drawBackgroundBox(RenderPipeline2D pipeline, float effectiveAlpha) {
        Rect2f b = getBounds();
        String fillHex = getBackgroundColorHex() != null
                ? getBackgroundColorHex()
                : (dropTarget ? ThemePalette.PANEL_SURFACE : ThemePalette.ELEVATED);
        String fallbackBorder = isHovered() ? ThemePalette.ACCENT_HOVER : ThemePalette.BORDER;
        String borderHex = getBorderColorHex() != null
                ? getBorderColorHex()
                : (dragging ? ThemePalette.ACTIVE_SELECTED : fallbackBorder);
        UiRenderSupport.drawRoundedRect(
                pipeline, b.x(), b.y(), b.width(), b.height(),
                getCornerRadius(), fillHex, getBorderThickness(), borderHex, effectiveAlpha
        );
    }
}
