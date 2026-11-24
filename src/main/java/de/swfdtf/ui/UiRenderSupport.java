package de.swfdtf.ui;

import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.Texture;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Rect2f;
import org.lwjgl.opengl.GL11;

/**
 * Package-private rendering, scissor-clipping, and headless font fallback utilities shared across
 * {@code de.swfdtf.ui} containers and widgets.
 */
final class UiRenderSupport {

    private static final Rect2f FULL_UV_RECT = new Rect2f(0.0f, 0.0f, 1.0f, 1.0f);
    private static final float[] NO_COLOR = new float[]{0.0f, 0.0f, 0.0f, 0.0f};
    private static final FontManager HEADLESS_FALLBACK_FONT_MANAGER = new FontManager();

    record ScissorState(boolean wasEnabled, int x, int y, int width, int height) {
    }

    private UiRenderSupport() {
    }

    static FontManager resolveFontManager(FontManager fontManager) {
        return fontManager != null ? fontManager : HEADLESS_FALLBACK_FONT_MANAGER;
    }

    static boolean isPipelineReady(RenderPipeline2D pipeline) {
        return pipeline != null && pipeline.isInitialized();
    }

    static float[] straightRgba(String hex, float alphaMultiplier) {
        float[] s = ThemePalette.straightRgba(hex);
        float a = Math.clamp(s[3] * alphaMultiplier, 0.0f, 1.0f);
        return new float[]{s[0], s[1], s[2], a};
    }

    static void drawRoundedRect(
            RenderPipeline2D pipeline,
            float x,
            float y,
            float width,
            float height,
            float cornerRadius,
            String fillHex,
            float borderThickness,
            String borderHex,
            float effectiveAlpha
    ) {
        if (!isPipelineReady(pipeline) || width <= 0.0f || height <= 0.0f || effectiveAlpha <= 0.0f) {
            return;
        }
        float[] fill = fillHex != null ? straightRgba(fillHex, effectiveAlpha) : NO_COLOR;
        float[] border = (borderHex != null && borderThickness > 0.0f)
                ? straightRgba(borderHex, effectiveAlpha)
                : NO_COLOR;
        pipeline.drawRoundedRect(x, y, width, height, cornerRadius, fill, borderThickness, border);
    }

    static void drawRect(
            RenderPipeline2D pipeline,
            float x,
            float y,
            float width,
            float height,
            String fillHex,
            float effectiveAlpha
    ) {
        if (!isPipelineReady(pipeline) || width <= 0.0f || height <= 0.0f || effectiveAlpha <= 0.0f || fillHex == null) {
            return;
        }
        pipeline.drawRect(x, y, width, height, straightRgba(fillHex, effectiveAlpha));
    }

    static void drawTexture(
            RenderPipeline2D pipeline,
            TextureManager textureManager,
            String path,
            Rect2f bounds,
            Rect2f uvRect,
            float effectiveAlpha
    ) {
        if (!isPipelineReady(pipeline) || textureManager == null || path == null || path.isBlank()
                || bounds == null || bounds.width() <= 0.0f || bounds.height() <= 0.0f || effectiveAlpha <= 0.0f) {
            return;
        }
        Texture tex = textureManager.loadTexture(path);
        if (tex != null && tex.getTextureId() != 0) {
            pipeline.drawTexturedQuad(
                    tex.getTextureId(),
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    uvRect != null ? uvRect : FULL_UV_RECT,
                    new float[]{1.0f, 1.0f, 1.0f, Math.clamp(effectiveAlpha, 0.0f, 1.0f)},
                    ColorMatrix4f.identity()
            );
        }
    }

    static ScissorState pushScissor(RenderPipeline2D pipeline, Rect2f clipBounds) {
        if (!isPipelineReady(pipeline) || clipBounds == null) {
            return null;
        }
        boolean wasEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] prevBox = new int[4];
        if (wasEnabled) {
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, prevBox);
        }
        int logicalH = pipeline.getLogicalHeight();
        int sx = Math.max(0, Math.round(clipBounds.x()));
        int sy = Math.max(0, Math.round(logicalH - clipBounds.bottom()));
        int sw = Math.max(0, Math.round(clipBounds.width()));
        int sh = Math.max(0, Math.round(clipBounds.height()));

        if (wasEnabled) {
            int ix1 = Math.max(sx, prevBox[0]);
            int iy1 = Math.max(sy, prevBox[1]);
            int ix2 = Math.min(sx + sw, prevBox[0] + prevBox[2]);
            int iy2 = Math.min(sy + sh, prevBox[1] + prevBox[3]);
            sx = ix1;
            sy = iy1;
            sw = Math.max(0, ix2 - ix1);
            sh = Math.max(0, iy2 - iy1);
        }

        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(sx, sy, sw, sh);
        return new ScissorState(wasEnabled, prevBox[0], prevBox[1], prevBox[2], prevBox[3]);
    }

    static void popScissor(ScissorState state) {
        if (state == null) {
            return;
        }
        if (state.wasEnabled()) {
            GL11.glScissor(state.x(), state.y(), state.width(), state.height());
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        } else {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
    }

    static void drawScrollbar(
            RenderPipeline2D pipeline,
            Rect2f trackBounds,
            float scrollY,
            float maxScrollY,
            float contentHeight,
            float viewportHeight,
            float alpha,
            boolean dragging
    ) {
        if (!isPipelineReady(pipeline) || maxScrollY <= 0.0f || contentHeight <= 0.0f || viewportHeight <= 0.0f) {
            return;
        }
        drawRoundedRect(
                pipeline,
                trackBounds.x(), trackBounds.y(), trackBounds.width(), trackBounds.height(),
                trackBounds.width() * 0.5f,
                ThemePalette.PANEL_SURFACE, 0.0f, null, alpha * 0.8f
        );
        float thumbH = Math.max(24.0f, trackBounds.height() * (viewportHeight / contentHeight));
        thumbH = Math.min(trackBounds.height(), thumbH);
        float travel = Math.max(0.0f, trackBounds.height() - thumbH);
        float thumbY = trackBounds.y() + travel * (scrollY / maxScrollY);
        String thumbColor = dragging ? ThemePalette.ACTIVE_SELECTED : ThemePalette.ACCENT_IDLE;
        drawRoundedRect(
                pipeline,
                trackBounds.x(), thumbY, trackBounds.width(), thumbH,
                trackBounds.width() * 0.5f,
                thumbColor, 0.0f, null, alpha
        );
    }
}
