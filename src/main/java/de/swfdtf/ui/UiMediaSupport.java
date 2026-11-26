package de.swfdtf.ui;

import de.swfdtf.audio.AudioEngine;
import de.swfdtf.audio.TextToSpeechService;
import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.Texture;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.parser.ScriptSession;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Shared Dark-Slate styling, formatting, service-lookup, and CPU/GPU thumbnail utilities
 * for built-in KAGAYAKI UI screens.
 */
final class UiMediaSupport {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private UiMediaSupport() {
    }

    static AudioEngine findAudioEngine(ScriptSession session) {
        if (session == null || session.getEngineApi() == null) {
            return null;
        }
        return session.getEngineApi().getService(AudioEngine.class);
    }

    static TextToSpeechService findTtsService(ScriptSession session) {
        if (session == null || session.getEngineApi() == null) {
            return null;
        }
        return session.getEngineApi().getService(TextToSpeechService.class);
    }

    static void styleModalCard(UiNode node) {
        node.setBackgroundColorHex(ThemePalette.PANEL_SURFACE)
                .setBorderColorHex(ThemePalette.BORDER)
                .setBorderThickness(1.5f)
                .setCornerRadius(10.0f)
                .setPadding(24.0f);
    }

    static void styleElevatedCard(UiNode node) {
        node.setBackgroundColorHex(ThemePalette.ELEVATED)
                .setBorderColorHex(ThemePalette.BORDER)
                .setBorderThickness(1.0f)
                .setCornerRadius(8.0f)
                .setPadding(12.0f);
    }

    static void styleBackdrop(UiNode root) {
        root.setBackgroundColorHex(ThemePalette.PANEL_GLASS);
    }

    static TextButton createActionButton(String label, float width, float height, Runnable action) {
        TextButton btn = new TextButton(label, action);
        btn.setPreferredSize(width, height);
        btn.setBackgroundColorHex(ThemePalette.ELEVATED);
        btn.setBorderColorHex(ThemePalette.BORDER);
        btn.setBorderThickness(1.0f);
        btn.setCornerRadius(6.0f);
        return btn;
    }

    static TextWidget createTitleText(String text, float fontSize) {
        TextWidget label = new TextWidget(text);
        label.setFontSize(fontSize);
        label.setColorHex(ThemePalette.TEXT_PRIMARY);
        label.setDropShadow(true);
        return label;
    }

    static TextWidget createMutedText(String text, float fontSize) {
        TextWidget label = new TextWidget(text);
        label.setFontSize(fontSize);
        label.setColorHex(ThemePalette.TEXT_MUTED);
        label.setDropShadow(false);
        return label;
    }

    static String formatTimestamp(Instant instant) {
        if (instant == null) {
            return "-";
        }
        return TIMESTAMP_FORMAT.format(instant);
    }

    static String formatPlaytime(double seconds) {
        long total = Math.max(0L, Math.round(seconds));
        long hours = total / 3600L;
        long minutes = (total % 3600L) / 60L;
        long secs = total % 60L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, secs);
    }

    /**
     * Lightweight image preview node that decodes in-memory PNG bytes or loads a path from
     * {@link TextureManager} lazily only when an active OpenGL {@link RenderPipeline2D} is present,
     * rendering a Dark-Slate placeholder card in headless or missing-asset situations.
     */
    static final class PreviewImageNode extends UiNode {
        private byte[] pngBytes = new byte[0];
        private String assetPath;
        private Texture uploadedTexture;
        private boolean uploadAttempted = false;
        private int decodedWidth = 0;
        private int decodedHeight = 0;
        private String placeholderLabel = "NO PREVIEW";

        PreviewImageNode() {
            setBackgroundColorHex(ThemePalette.CANVAS_BG);
            setBorderColorHex(ThemePalette.BORDER);
            setBorderThickness(1.0f);
            setCornerRadius(6.0f);
        }

        void setPngBytes(byte[] pngBytes) {
            disposeTexture();
            this.assetPath = null;
            this.pngBytes = pngBytes != null ? pngBytes.clone() : new byte[0];
            this.uploadAttempted = false;
            this.decodedWidth = 0;
            this.decodedHeight = 0;
            if (this.pngBytes.length > 0) {
                probePngDimensions(this.pngBytes);
            }
        }

        void setAssetPath(String assetPath) {
            disposeTexture();
            this.pngBytes = new byte[0];
            this.assetPath = assetPath;
            this.uploadAttempted = false;
            this.decodedWidth = 0;
            this.decodedHeight = 0;
        }

        void setPlaceholderLabel(String label) {
            this.placeholderLabel = label != null ? label : "";
        }

        boolean hasImageSource() {
            return pngBytes.length > 0 || (assetPath != null && !assetPath.isBlank());
        }

        int getDecodedWidth() {
            return decodedWidth;
        }

        int getDecodedHeight() {
            return decodedHeight;
        }

        String getAssetPath() {
            return assetPath;
        }

        String getPlaceholderLabel() {
            return placeholderLabel;
        }

        private void probePngDimensions(byte[] bytes) {
            ByteBuffer buf = MemoryUtil.memAlloc(bytes.length);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                buf.put(bytes).flip();
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                IntBuffer comp = stack.mallocInt(1);
                if (STBImage.stbi_info_from_memory(buf, w, h, comp)) {
                    decodedWidth = w.get(0);
                    decodedHeight = h.get(0);
                }
            } finally {
                MemoryUtil.memFree(buf);
            }
        }

        void disposeTexture() {
            if (uploadedTexture != null && !uploadedTexture.isDisposed()) {
                uploadedTexture.dispose();
            }
            uploadedTexture = null;
        }

        private Texture resolveTexture(TextureManager textureManager) {
            if (uploadedTexture != null && !uploadedTexture.isDisposed()) {
                return uploadedTexture;
            }
            if (assetPath != null && !assetPath.isBlank() && textureManager != null) {
                return textureManager.loadTexture(assetPath);
            }
            if (uploadAttempted || pngBytes.length == 0) {
                return null;
            }
            uploadAttempted = true;
            ByteBuffer raw = MemoryUtil.memAlloc(pngBytes.length);
            ByteBuffer decoded = null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                raw.put(pngBytes).flip();
                IntBuffer wBuf = stack.mallocInt(1);
                IntBuffer hBuf = stack.mallocInt(1);
                IntBuffer cBuf = stack.mallocInt(1);
                decoded = STBImage.stbi_load_from_memory(raw, wBuf, hBuf, cBuf, STBImage.STBI_rgb_alpha);
                if (decoded == null) {
                    return null;
                }
                int w = wBuf.get(0);
                int h = hBuf.get(0);
                if (w <= 0 || h <= 0) {
                    return null;
                }
                decodedWidth = w;
                decodedHeight = h;
                byte[] rgba = new byte[Math.multiplyExact(Math.multiplyExact(w, h), 4)];
                decoded.get(0, rgba);
                uploadedTexture = Texture.createFromRgba(w, h, rgba, false, "save_thumbnail");
                return uploadedTexture;
            } finally {
                if (decoded != null) {
                    decoded.clear();
                    STBImage.stbi_image_free(decoded);
                }
                MemoryUtil.memFree(raw);
            }
        }

        @Override
        protected void renderSelf(
                RenderPipeline2D pipeline,
                FontManager fontManager,
                TextureManager textureManager,
                double elapsedSeconds
        ) {
            if (pipeline == null || !pipeline.isInitialized()) {
                return;
            }
            var bounds = getContentBounds();
            if (bounds.width() <= 0.0f || bounds.height() <= 0.0f) {
                return;
            }
            Texture tex = resolveTexture(textureManager);
            if (tex != null && !tex.isDisposed() && tex.getTextureId() > 0) {
                pipeline.drawTexturedQuad(
                        tex.getTextureId(), bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                        null, getAlpha());
            } else {
                float[] accent = ThemePalette.premultipliedRgba(ThemePalette.ELEVATED, getAlpha());
                pipeline.drawRoundedRect(
                        bounds.x(), bounds.y(), bounds.width(), bounds.height(), 4.0f, accent, 0.0f, null);
            }
        }
    }
}
