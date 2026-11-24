package de.swfdtf.ui;

import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.Rect2f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Image-map container combining a {@code ground} base image, an optional {@code hover} overlay
 * texture, and positioned {@link Hotspot} / {@link Hotbar} child regions.
 */
public class Imagemap extends UiNode {

    private String groundPath;
    private String hoverPath;
    private final List<Hotspot> hotspots = new ArrayList<>();
    private final List<Hotbar> hotbars = new ArrayList<>();

    /**
     * Creates an {@code Imagemap} with base ground and optional hover-state image paths.
     *
     * @param groundPath base image asset path
     * @param hoverPath  hover overlay image asset path (nullable)
     */
    public Imagemap(String groundPath, String hoverPath) {
        this.groundPath = groundPath;
        this.hoverPath = hoverPath;
    }

    /** @return ground image asset path */
    public String getGroundPath() {
        return groundPath;
    }

    /**
     * Sets the ground image asset path.
     *
     * @param groundPath ground asset path
     * @return {@code this}
     */
    public Imagemap setGroundPath(String groundPath) {
        this.groundPath = groundPath;
        return this;
    }

    /** @return hover image asset path, or {@code null} */
    public String getHoverPath() {
        return hoverPath;
    }

    /**
     * Sets the hover overlay image asset path.
     *
     * @param hoverPath hover asset path (nullable)
     * @return {@code this}
     */
    public Imagemap setHoverPath(String hoverPath) {
        this.hoverPath = hoverPath;
        return this;
    }

    /**
     * Creates, registers, and returns a clickable {@link Hotspot} within this imagemap.
     *
     * @param area    hotspot region relative to this imagemap's content box
     * @param onClick click callback
     * @return created {@link Hotspot}
     */
    public Hotspot addHotspot(Rect2f area, Runnable onClick) {
        Hotspot hotspot = new Hotspot(area, onClick);
        hotspots.add(hotspot);
        addChild(hotspot);
        return hotspot;
    }

    /**
     * Creates, registers, and returns a {@link Hotbar} gauge within this imagemap.
     *
     * @param area          hotbar region relative to this imagemap's content box
     * @param valueSupplier dynamic value supplier
     * @param maxValue      maximum gauge value
     * @return created {@link Hotbar}
     */
    public Hotbar addHotbar(Rect2f area, Supplier<Float> valueSupplier, float maxValue) {
        Hotbar hotbar = new Hotbar(area, valueSupplier, maxValue);
        hotbars.add(hotbar);
        addChild(hotbar);
        return hotbar;
    }

    @Override
    public UiNode addChild(UiNode child) {
        super.addChild(child);
        if (child instanceof Hotspot h && !hotspots.contains(h)) {
            hotspots.add(h);
        } else if (child instanceof Hotbar hb && !hotbars.contains(hb)) {
            hotbars.add(hb);
        }
        return this;
    }

    @Override
    public boolean removeChild(UiNode child) {
        hotspots.remove(child);
        hotbars.remove(child);
        return super.removeChild(child);
    }

    @Override
    public void clearChildren() {
        hotspots.clear();
        hotbars.clear();
        super.clearChildren();
    }

    /** @return unmodifiable list of registered {@link Hotspot}s */
    public List<Hotspot> getHotspots() {
        return Collections.unmodifiableList(hotspots);
    }

    /** @return unmodifiable list of registered {@link Hotbar}s */
    public List<Hotbar> getHotbars() {
        return Collections.unmodifiableList(hotbars);
    }

    @Override
    public void layout(Rect2f resolvedBounds) {
        setBounds(resolvedBounds);
        Rect2f inner = getContentBounds();
        for (UiNode child : children) {
            if (!child.isVisible()) {
                continue;
            }
            if (child instanceof Hotspot h) {
                Rect2f a = h.getArea();
                h.layout(new Rect2f(inner.x() + a.x(), inner.y() + a.y(), a.width(), a.height()));
            } else if (child instanceof Hotbar hb) {
                Rect2f a = hb.getArea();
                hb.layout(new Rect2f(inner.x() + a.x(), inner.y() + a.y(), a.width(), a.height()));
            } else {
                child.layout(inner);
            }
        }
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
        Rect2f b = getBounds();
        UiRenderSupport.drawTexture(pipeline, textureManager, groundPath, b, null, getAlpha());

        if (hoverPath != null && !hoverPath.isBlank() && b.width() > 0.0f && b.height() > 0.0f) {
            for (Hotspot hotspot : hotspots) {
                if (!hotspot.isVisible() || (!hotspot.isHovered() && !hotspot.isFocused())) {
                    continue;
                }
                Rect2f hb = hotspot.getBounds();
                float u = Math.clamp((hb.x() - b.x()) / b.width(), 0.0f, 1.0f);
                float v = Math.clamp((hb.y() - b.y()) / b.height(), 0.0f, 1.0f);
                float uw = Math.clamp(hb.width() / b.width(), 0.0f, 1.0f - u);
                float vh = Math.clamp(hb.height() / b.height(), 0.0f, 1.0f - v);
                UiRenderSupport.drawTexture(
                        pipeline, textureManager, hoverPath, hb, new Rect2f(u, v, uw, vh), getAlpha()
                );
            }
        }
    }
}
