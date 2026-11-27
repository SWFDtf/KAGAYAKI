package de.swfdtf.graphics;

import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Rect2f;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@link CustomDisplayable} bridge embedding any {@link Skeletal2DAdapter} seamlessly into a
 * {@link SpriteNode} inside the Z-ordered {@link SceneGraph} ({@code #0213}).
 */
public final class Skeletal2DDisplayable implements CustomDisplayable {

    private static final Rect2f FULL_UV = new Rect2f(0.0f, 0.0f, 1.0f, 1.0f);

    private final Skeletal2DAdapter adapter;
    private int lastRenderedSlotCount;

    /**
     * Wraps a {@link Skeletal2DAdapter} as a {@link CustomDisplayable}.
     *
     * @param adapter skeletal 2D plugin adapter (must not be {@code null})
     */
    public Skeletal2DDisplayable(Skeletal2DAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter must not be null");
    }

    /**
     * Returns the backing skeletal 2D adapter.
     *
     * @return skeletal 2D adapter
     */
    public Skeletal2DAdapter getAdapter() {
        return adapter;
    }

    /**
     * Returns the number of visible slots processed in the most recent {@link #render} call.
     *
     * @return slot count rendered last frame
     */
    public int getLastRenderedSlotCount() {
        return lastRenderedSlotCount;
    }

    @Override
    public void update(double dt) {
        adapter.update(dt);
    }

    @Override
    public void render(RenderPipeline2D pipeline, Rect2f bounds, float alpha, ColorMatrix4f colorMatrix) {
        Objects.requireNonNull(pipeline, "pipeline must not be null");
        Objects.requireNonNull(bounds, "bounds must not be null");
        if (alpha <= 0.0f) {
            this.lastRenderedSlotCount = 0;
            return;
        }

        Map<String, Skeletal2DAdapter.BonePose> boneLookup = new LinkedHashMap<>();
        for (Skeletal2DAdapter.BonePose pose : adapter.getWorldBonePoses()) {
            boneLookup.put(pose.name(), pose);
        }

        int rendered = 0;
        float originX = bounds.x() + bounds.width() * 0.5f;
        float originY = bounds.y() + bounds.height();
        ColorMatrix4f effectiveMatrix = colorMatrix != null ? colorMatrix : ColorMatrix4f.identity();

        for (Skeletal2DAdapter.SlotAttachment slot : adapter.getResolvedSlots()) {
            float slotAlpha = Math.clamp(slot.alpha() * alpha, 0.0f, 1.0f);
            if (slotAlpha <= 0.0f || slot.width() <= 0.0f || slot.height() <= 0.0f) {
                continue;
            }
            Skeletal2DAdapter.BonePose bone = boneLookup.get(slot.boneName());
            float boneX = bone != null ? bone.x() : 0.0f;
            float boneY = bone != null ? bone.y() : 0.0f;
            float scaleX = bone != null ? bone.scaleX() : 1.0f;
            float scaleY = bone != null ? bone.scaleY() : 1.0f;
            float rotDeg = bone != null ? bone.rotationDeg() : 0.0f;

            float quadW = Math.max(1.0f, slot.width() * Math.abs(scaleX));
            float quadH = Math.max(1.0f, slot.height() * Math.abs(scaleY));
            float drawX = originX + boneX - quadW * 0.5f;
            float drawY = originY + boneY - quadH;
            float[] tint = new float[] {1.0f, 1.0f, 1.0f, slotAlpha};

            if (slot.textureId() > 0 && pipeline.isInitialized()) {
                pipeline.drawTexturedQuadPivot(
                        slot.textureId(),
                        drawX,
                        drawY,
                        quadW,
                        quadH,
                        FULL_UV,
                        tint,
                        effectiveMatrix,
                        rotDeg,
                        0.5f,
                        0.5f
                );
            }
            rendered++;
        }
        this.lastRenderedSlotCount = rendered;
    }

    @Override
    public float getPreferredWidth() {
        return adapter.getPreferredWidth();
    }

    @Override
    public float getPreferredHeight() {
        return adapter.getPreferredHeight();
    }
}
