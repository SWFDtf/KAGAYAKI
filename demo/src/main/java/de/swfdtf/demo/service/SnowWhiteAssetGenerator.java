package de.swfdtf.demo.service;

import de.swfdtf.audio.AudioDecoder;
import de.swfdtf.graphics.TextureManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Erzeugt sämtliche prozeduralen PNG- und WAV-Assets für das Schneewittchen-vJava-Projekt
 * vollständig ohne {@code java.awt.*} über die KAGAYAKI-APIs {@link TextureManager#writePng}
 * und {@link AudioDecoder#encodeWav}.
 */
public final class SnowWhiteAssetGenerator {

    private static final int BG_WIDTH = 640;
    private static final int BG_HEIGHT = 360;
    private static final int CHAR_WIDTH = 256;
    private static final int CHAR_HEIGHT = 384;
    private static final int SAMPLE_RATE = 22_050;

    private SnowWhiteAssetGenerator() {
    }

    /**
     * Stellt sicher, dass alle referenzierten Bild- und Audiodateien im Projektverzeichnis existieren.
     *
     * @param projectDir Wurzelverzeichnis des {@code .vjava}-Projekts
     * @throws IOException falls ein Schreibvorgang fehlschlägt
     */
    public static void ensureAssets(Path projectDir) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir darf nicht null sein");
        Path root = projectDir.toAbsolutePath().normalize();
        generateBackgrounds(root);
        generateCharacterSprites(root);
        generateSnowWhiteLayers(root);
        generateAudioAssets(root);
    }

    private static void generateBackgrounds(Path root) throws IOException {
        writePngIfMissing(root.resolve("assets/images/bg/bg_schloss_winter.png"),
                BG_WIDTH, BG_HEIGHT, renderGradientBg(BG_WIDTH, BG_HEIGHT, 18, 28, 54, 210, 224, 244, true));
        writePngIfMissing(root.resolve("assets/images/bg/bg_spiegel_saal.png"),
                BG_WIDTH, BG_HEIGHT, renderGradientBg(BG_WIDTH, BG_HEIGHT, 32, 16, 48, 72, 34, 96, false));
        writePngIfMissing(root.resolve("assets/images/bg/bg_wald.png"),
                BG_WIDTH, BG_HEIGHT, renderForestBg(BG_WIDTH, BG_HEIGHT));
        writePngIfMissing(root.resolve("assets/images/bg/bg_zwergenhaus.png"),
                BG_WIDTH, BG_HEIGHT, renderGradientBg(BG_WIDTH, BG_HEIGHT, 48, 30, 18, 128, 78, 42, false));
        writePngIfMissing(root.resolve("assets/images/bg/bg_berg_glassarg.png"),
                BG_WIDTH, BG_HEIGHT, renderGradientBg(BG_WIDTH, BG_HEIGHT, 24, 42, 68, 148, 190, 224, true));
        writePngIfMissing(root.resolve("assets/images/bg/bg_hochzeitssaal.png"),
                BG_WIDTH, BG_HEIGHT, renderGradientBg(BG_WIDTH, BG_HEIGHT, 74, 48, 20, 232, 198, 120, false));
    }

    private static void generateCharacterSprites(Path root) throws IOException {
        writePngIfMissing(root.resolve("assets/images/char/koenigin_mutter.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderFigure(CHAR_WIDTH, CHAR_HEIGHT, 180, 190, 220, 245, 224, 210));
        writePngIfMissing(root.resolve("assets/images/char/stiefmutter_normal.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderFigure(CHAR_WIDTH, CHAR_HEIGHT, 120, 24, 52, 235, 210, 205));
        writePngIfMissing(root.resolve("assets/images/char/stiefmutter_wuetend.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderFigure(CHAR_WIDTH, CHAR_HEIGHT, 210, 32, 42, 238, 188, 188));
        writePngIfMissing(root.resolve("assets/images/char/stiefmutter_kraemerin.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderFigure(CHAR_WIDTH, CHAR_HEIGHT, 92, 74, 62, 205, 190, 175));
        writePngIfMissing(root.resolve("assets/images/char/stiefmutter_baeuerin.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderFigure(CHAR_WIDTH, CHAR_HEIGHT, 108, 86, 54, 212, 196, 180));
        writePngIfMissing(root.resolve("assets/images/char/spiegel.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderMirror(CHAR_WIDTH, CHAR_HEIGHT));
        writePngIfMissing(root.resolve("assets/images/char/jaeger.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderFigure(CHAR_WIDTH, CHAR_HEIGHT, 44, 98, 56, 226, 196, 174));
        writePngIfMissing(root.resolve("assets/images/char/zwerge.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderFigure(CHAR_WIDTH, CHAR_HEIGHT, 196, 118, 42, 236, 204, 180));
        writePngIfMissing(root.resolve("assets/images/char/prinz.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderFigure(CHAR_WIDTH, CHAR_HEIGHT, 56, 122, 228, 240, 214, 196));
        writePngIfMissing(root.resolve("assets/images/char/glassarg.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderGlassCoffin(CHAR_WIDTH, CHAR_HEIGHT));
    }

    private static void generateSnowWhiteLayers(Path root) throws IOException {
        // Basisfigur: schwarzes Haar, weiße Haut
        writePngIfMissing(root.resolve("assets/images/char/sw_base.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderFigure(CHAR_WIDTH, CHAR_HEIGHT, 32, 36, 48, 250, 242, 242));

        // Kleider-Gruppe
        writePngIfMissing(root.resolve("assets/images/char/sw_kleid_alltag.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderDressOverlay(CHAR_WIDTH, CHAR_HEIGHT, 46, 78, 148));
        writePngIfMissing(root.resolve("assets/images/char/sw_kleid_prinzessin.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderDressOverlay(CHAR_WIDTH, CHAR_HEIGHT, 192, 48, 72));
        writePngIfMissing(root.resolve("assets/images/char/sw_kleid_braut.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderDressOverlay(CHAR_WIDTH, CHAR_HEIGHT, 242, 244, 252));

        // Zustands-Gruppe
        writePngIfMissing(root.resolve("assets/images/char/sw_zustand_gesund.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderEyesOverlay(CHAR_WIDTH, CHAR_HEIGHT, true, 64, 132, 220));
        writePngIfMissing(root.resolve("assets/images/char/sw_zustand_schlafend.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderEyesOverlay(CHAR_WIDTH, CHAR_HEIGHT, false, 70, 70, 90));
        writePngIfMissing(root.resolve("assets/images/char/sw_zustand_ohnmaechtig.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderEyesOverlay(CHAR_WIDTH, CHAR_HEIGHT, false, 140, 120, 140));
        writePngIfMissing(root.resolve("assets/images/char/sw_zustand_vergiftet.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderEyesOverlay(CHAR_WIDTH, CHAR_HEIGHT, false, 178, 44, 62));
        writePngIfMissing(root.resolve("assets/images/char/sw_zustand_erloest.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderEyesOverlay(CHAR_WIDTH, CHAR_HEIGHT, true, 88, 192, 255));

        // Lip-Sync-Gruppe
        writePngIfMissing(root.resolve("assets/images/char/sw_mouth_closed.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderMouthOverlay(CHAR_WIDTH, CHAR_HEIGHT, 2));
        writePngIfMissing(root.resolve("assets/images/char/sw_mouth_half.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderMouthOverlay(CHAR_WIDTH, CHAR_HEIGHT, 5));
        writePngIfMissing(root.resolve("assets/images/char/sw_mouth_open.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderMouthOverlay(CHAR_WIDTH, CHAR_HEIGHT, 9));

        // Bedingtes Overlay bei Anschlag 3
        writePngIfMissing(root.resolve("assets/images/char/sw_apfel_aura.png"),
                CHAR_WIDTH, CHAR_HEIGHT, renderAuraOverlay(CHAR_WIDTH, CHAR_HEIGHT, 232, 48, 64));
    }

    private static void generateAudioAssets(Path root) throws IOException {
        writeChordWavIfMissing(root.resolve("assets/audio/music/winter_prolog.wav"),
                new double[]{261.63, 311.13, 392.00}, 1.4, 0.24f);
        writeChordWavIfMissing(root.resolve("assets/audio/music/spiegel_thema.wav"),
                new double[]{220.00, 261.63, 329.63, 415.30}, 1.4, 0.24f);
        writeChordWavIfMissing(root.resolve("assets/audio/music/wald_thema.wav"),
                new double[]{196.00, 246.94, 293.66, 392.00}, 1.4, 0.25f);
        writeChordWavIfMissing(root.resolve("assets/audio/music/gefahr_thema.wav"),
                new double[]{185.00, 220.00, 261.63, 349.23}, 1.4, 0.26f);
        writeChordWavIfMissing(root.resolve("assets/audio/music/finale_hochzeit.wav"),
                new double[]{261.63, 329.63, 392.00, 523.25}, 1.6, 0.28f);

        writeToneWavIfMissing(root.resolve("assets/audio/sfx/stich.wav"), 880.0, 0.15, 0.35f);
        writeToneWavIfMissing(root.resolve("assets/audio/sfx/spiegel_klang.wav"), 659.25, 0.45, 0.30f);
        writeToneWavIfMissing(root.resolve("assets/audio/sfx/fall.wav"), 146.83, 0.35, 0.40f);
        writeToneWavIfMissing(root.resolve("assets/audio/sfx/stolpern.wav"), 196.00, 0.30, 0.38f);
        writeChordWavIfMissing(root.resolve("assets/audio/sfx/jubel.wav"),
                new double[]{523.25, 659.25, 783.99}, 0.6, 0.32f);

        writeToneWavIfMissing(root.resolve("assets/audio/voice/sw_hallo.wav"), 587.33, 0.35, 0.30f);
        writeToneWavIfMissing(root.resolve("assets/audio/voice/sw_bitte.wav"), 523.25, 0.40, 0.30f);
        writeToneWavIfMissing(root.resolve("assets/audio/voice/sw_erwacht.wav"), 659.25, 0.38, 0.32f);
    }

    private static void writePngIfMissing(Path path, int w, int h, byte[] rgba) throws IOException {
        if (Files.isRegularFile(path)) {
            return;
        }
        Files.createDirectories(path.getParent());
        TextureManager.writePng(path, w, h, rgba);
    }

    private static void writeToneWavIfMissing(Path path, double freqHz, double duration, float amp) throws IOException {
        if (Files.isRegularFile(path)) {
            return;
        }
        Files.createDirectories(path.getParent());
        AudioDecoder.DecodedAudio audio = AudioDecoder.synthesizeTone(freqHz, duration, SAMPLE_RATE, amp);
        byte[] wav = AudioDecoder.encodeWav(audio.pcm16(), audio.channels(), audio.sampleRate());
        Files.write(path, wav);
    }

    private static void writeChordWavIfMissing(Path path, double[] freqs, double duration, float amp) throws IOException {
        if (Files.isRegularFile(path)) {
            return;
        }
        Files.createDirectories(path.getParent());
        AudioDecoder.DecodedAudio audio = AudioDecoder.synthesizeChord(freqs, duration, SAMPLE_RATE, amp);
        byte[] wav = AudioDecoder.encodeWav(audio.pcm16(), audio.channels(), audio.sampleRate());
        Files.write(path, wav);
    }

    private static byte[] renderGradientBg(int w, int h, int r1, int g1, int b1, int r2, int g2, int b2, boolean snow) {
        byte[] rgba = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            float t = (float) y / Math.max(1, h - 1);
            int r = Math.round(r1 + (r2 - r1) * t);
            int g = Math.round(g1 + (g2 - g1) * t);
            int b = Math.round(b1 + (b2 - b1) * t);
            for (int x = 0; x < w; x++) {
                int pr = r;
                int pg = g;
                int pb = b;
                if (snow && ((x * 53 + y * 97) % 313 == 0)) {
                    pr = 250;
                    pg = 252;
                    pb = 255;
                }
                putPixel(rgba, w, x, y, pr, pg, pb, 255);
            }
        }
        return rgba;
    }

    private static byte[] renderForestBg(int w, int h) {
        byte[] rgba = renderGradientBg(w, h, 12, 26, 18, 28, 62, 38, false);
        for (int treeX = 40; treeX < w; treeX += 76) {
            fillRect(rgba, w, h, treeX - 8, h / 2, treeX + 8, h - 1, 52, 34, 22, 255);
            fillEllipse(rgba, w, h, treeX, h / 2 - 20, 34, 64, 22, 74, 40, 255);
        }
        return rgba;
    }

    private static byte[] renderFigure(int w, int h, int dressR, int dressG, int dressB, int skinR, int skinG, int skinB) {
        byte[] rgba = new byte[w * h * 4];
        int cx = w / 2;
        int headCy = (int) (h * 0.28);
        fillEllipse(rgba, w, h, cx, (int) (h * 0.72), 68, 122, dressR, dressG, dressB, 255);
        fillEllipse(rgba, w, h, cx, headCy, 44, 52, skinR, skinG, skinB, 255);
        fillEllipse(rgba, w, h, cx, headCy - 32, 46, 22, 24, 24, 34, 255);
        return rgba;
    }

    private static byte[] renderMirror(int w, int h) {
        byte[] rgba = new byte[w * h * 4];
        int cx = w / 2;
        int cy = h / 2;
        fillEllipse(rgba, w, h, cx, cy, 86, 134, 178, 146, 72, 255);
        fillEllipse(rgba, w, h, cx, cy, 72, 118, 128, 168, 224, 235);
        return rgba;
    }

    private static byte[] renderGlassCoffin(int w, int h) {
        byte[] rgba = new byte[w * h * 4];
        fillRect(rgba, w, h, 24, 180, w - 24, 310, 140, 200, 245, 135);
        fillRect(rgba, w, h, 28, 184, w - 28, 188, 225, 245, 255, 220);
        fillRect(rgba, w, h, 28, 306, w - 28, 310, 225, 245, 255, 220);
        return rgba;
    }

    private static byte[] renderDressOverlay(int w, int h, int r, int g, int b) {
        byte[] rgba = new byte[w * h * 4];
        fillEllipse(rgba, w, h, w / 2, (int) (h * 0.73), 66, 112, r, g, b, 240);
        return rgba;
    }

    private static byte[] renderEyesOverlay(int w, int h, boolean open, int r, int g, int b) {
        byte[] rgba = new byte[w * h * 4];
        int cx = w / 2;
        int eyeY = (int) (h * 0.28);
        if (open) {
            fillEllipse(rgba, w, h, cx - 16, eyeY, 6, 7, r, g, b, 255);
            fillEllipse(rgba, w, h, cx + 16, eyeY, 6, 7, r, g, b, 255);
        } else {
            fillRect(rgba, w, h, cx - 22, eyeY - 1, cx - 10, eyeY + 2, r, g, b, 255);
            fillRect(rgba, w, h, cx + 10, eyeY - 1, cx + 22, eyeY + 2, r, g, b, 255);
        }
        return rgba;
    }

    private static byte[] renderMouthOverlay(int w, int h, int radiusY) {
        byte[] rgba = new byte[w * h * 4];
        fillEllipse(rgba, w, h, w / 2, (int) (h * 0.35), 9, radiusY, 196, 48, 68, 255);
        return rgba;
    }

    private static byte[] renderAuraOverlay(int w, int h, int r, int g, int b) {
        byte[] rgba = new byte[w * h * 4];
        fillEllipse(rgba, w, h, w / 2, (int) (h * 0.45), 96, 150, r, g, b, 72);
        return rgba;
    }

    private static void fillRect(byte[] rgba, int w, int h, int x0, int y0, int x1, int y1, int r, int g, int b, int a) {
        int minX = Math.max(0, x0);
        int maxX = Math.min(w - 1, x1);
        int minY = Math.max(0, y0);
        int maxY = Math.min(h - 1, y1);
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                putPixel(rgba, w, x, y, r, g, b, a);
            }
        }
    }

    private static void fillEllipse(
            byte[] rgba,
            int w,
            int h,
            int cx,
            int cy,
            int rx,
            int ry,
            int r,
            int g,
            int b,
            int a
    ) {
        int minX = Math.max(0, cx - rx);
        int maxX = Math.min(w - 1, cx + rx);
        int minY = Math.max(0, cy - ry);
        int maxY = Math.min(h - 1, cy + ry);
        double invRx2 = 1.0 / (rx * (double) rx);
        double invRy2 = 1.0 / (ry * (double) ry);
        for (int y = minY; y <= maxY; y++) {
            double dy = y - cy;
            for (int x = minX; x <= maxX; x++) {
                double dx = x - cx;
                if (dx * dx * invRx2 + dy * dy * invRy2 <= 1.0) {
                    putPixel(rgba, w, x, y, r, g, b, a);
                }
            }
        }
    }

    private static void putPixel(byte[] rgba, int w, int x, int y, int r, int g, int b, int a) {
        int idx = (y * w + x) * 4;
        rgba[idx] = (byte) r;
        rgba[idx + 1] = (byte) g;
        rgba[idx + 2] = (byte) b;
        rgba[idx + 3] = (byte) a;
    }
}
