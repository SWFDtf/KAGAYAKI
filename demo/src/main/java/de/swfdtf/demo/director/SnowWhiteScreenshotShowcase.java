package de.swfdtf.demo.director;

import de.swfdtf.graphics.ScreenshotCaptureService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Erzeugt prozedurale RGBA8-Kapitelvorschaukarten für das Schneewittchen-Demo und speichert sie
 * vollständig ohne {@code java.awt.*} über {@link ScreenshotCaptureService} als PNG-Dateien.
 */
public final class SnowWhiteScreenshotShowcase {

    /** Niedrigste gültige Kapitelnummer im Schneewittchen-Demo. */
    public static final int MIN_CHAPTER = 1;

    /** Höchste gültige Kapitelnummer im Schneewittchen-Demo. */
    public static final int MAX_CHAPTER = 7;

    /** Standardbreite einer prozeduralen Kapitelvorschau in Pixeln (16:9). */
    public static final int DEFAULT_WIDTH = 320;

    /** Standardhöhe einer prozeduralen Kapitelvorschau in Pixeln (16:9). */
    public static final int DEFAULT_HEIGHT = 180;

    private static final Map<Integer, String> DEFAULT_CHAPTER_TITLES = createDefaultChapterTitles();

    private final ScreenshotCaptureService captureService;
    private final int width;
    private final int height;

    /**
     * Erzeugt einen {@code SnowWhiteScreenshotShowcase} mit Standardauflösung ({@code 320x180})
     * und einem neuen {@link ScreenshotCaptureService}.
     */
    public SnowWhiteScreenshotShowcase() {
        this(new ScreenshotCaptureService(), DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    /**
     * Erzeugt einen {@code SnowWhiteScreenshotShowcase} mit übergebenem {@link ScreenshotCaptureService}
     * in Standardauflösung ({@code 320x180}).
     *
     * @param captureService Screenshot-Dienst für STB-PNG-Ausgabe (darf nicht {@code null} sein)
     * @throws NullPointerException falls {@code captureService} {@code null} ist
     */
    public SnowWhiteScreenshotShowcase(ScreenshotCaptureService captureService) {
        this(captureService, DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    /**
     * Erzeugt einen {@code SnowWhiteScreenshotShowcase} mit benutzerdefinierten Bildabmessungen.
     *
     * @param captureService Screenshot-Dienst für STB-PNG-Ausgabe (darf nicht {@code null} sein)
     * @param width          Bildbreite in Pixeln ({@code >= 64})
     * @param height         Bildhöhe in Pixeln ({@code >= 64})
     * @throws NullPointerException     falls {@code captureService} {@code null} ist
     * @throws IllegalArgumentException falls {@code width < 64} oder {@code height < 64}
     */
    public SnowWhiteScreenshotShowcase(ScreenshotCaptureService captureService, int width, int height) {
        this.captureService = Objects.requireNonNull(captureService, "captureService darf nicht null sein");
        if (width < 64 || height < 64) {
            throw new IllegalArgumentException(
                    "Vorschaubild-Abmessungen müssen mindestens 64x64 sein, war: " + width + "x" + height
            );
        }
        this.width = width;
        this.height = height;
    }

    /**
     * Gibt eine unveränderliche Zuordnung der Standard-Kapiteltitel für Kapitel {@code 1..7} zurück.
     *
     * @return unveränderliche Map von Kapitelnummer auf deutschen Titel
     */
    public static Map<Integer, String> getDefaultChapterTitles() {
        return DEFAULT_CHAPTER_TITLES;
    }

    /**
     * Rendert eine prozedurale RGBA8-Szenenvorschau für das angegebene Kapitel und schreibt sie
     * über {@link ScreenshotCaptureService} als PNG nach {@code outputDir}.
     *
     * @param outputDir     Zielverzeichnis für die PNG-Datei (darf nicht {@code null} sein)
     * @param chapterNumber Kapitelnummer in {@code 1..7}
     * @param title         Kapiteltitel für Dateinamen-Ableitung und Motivakzent (darf nicht leer sein)
     * @return normierter Pfad zur erzeugten PNG-Datei
     * @throws NullPointerException     falls {@code outputDir} oder {@code title} {@code null} ist
     * @throws IllegalArgumentException falls {@code chapterNumber} außerhalb {@code 1..7} liegt oder {@code title} leer ist
     * @throws IOException              falls das Erstellen des Verzeichnisses oder das Schreiben fehlschlägt
     */
    public Path renderChapterPreview(Path outputDir, int chapterNumber, String title) throws IOException {
        Objects.requireNonNull(outputDir, "outputDir darf nicht null sein");
        validateChapterNumber(chapterNumber);
        String validTitle = requireNonBlank(title, "title");

        byte[] rgbaBytes = renderSceneCardRgba(chapterNumber, validTitle, width, height);
        String stem = String.format(
                Locale.ROOT,
                "kapitel_%02d_%s",
                chapterNumber,
                ScreenshotCaptureService.sanitizeFileName(validTitle)
        );
        return captureService.captureRgba(outputDir, stem, width, height, rgbaBytes);
    }

    /**
     * Rendert für alle sieben Schneewittchen-Kapitel ({@code 1..7}) jeweils eine Vorschaukarte
     * in {@code outputDir}.
     *
     * @param outputDir Zielverzeichnis für alle PNG-Vorschaukarten (darf nicht {@code null} sein)
     * @return unveränderliche Liste der erzeugten PNG-Pfade in Kapitelreihenfolge {@code 1..7}
     * @throws NullPointerException falls {@code outputDir} {@code null} ist
     * @throws IOException          falls ein Schreibvorgang fehlschlägt
     */
    public List<Path> renderAllChapterPreviews(Path outputDir) throws IOException {
        Objects.requireNonNull(outputDir, "outputDir darf nicht null sein");
        List<Path> generated = new ArrayList<>(MAX_CHAPTER);
        for (int chapter = MIN_CHAPTER; chapter <= MAX_CHAPTER; chapter++) {
            String title = DEFAULT_CHAPTER_TITLES.get(chapter);
            generated.add(renderChapterPreview(outputDir, chapter, title));
        }
        return List.copyOf(generated);
    }

    /**
     * Listet alle PNG-Vorschaubilder in {@code outputDir} lexikographisch sortiert auf.
     *
     * @param outputDir zu durchsuchendes Verzeichnis (darf nicht {@code null} sein)
     * @return unveränderliche sortierte Liste vorhandener PNG-Dateien
     * @throws NullPointerException falls {@code outputDir} {@code null} ist
     * @throws IOException          falls {@code outputDir} kein Verzeichnis ist oder das Auflisten fehlschlägt
     */
    public List<Path> listPreviews(Path outputDir) throws IOException {
        Objects.requireNonNull(outputDir, "outputDir darf nicht null sein");
        return captureService.listScreenshots(outputDir);
    }

    /**
     * Sucht das erste vorhandene Vorschaubild für {@code chapterNumber} innerhalb von {@code outputDir}.
     *
     * @param outputDir     Vorschau-Verzeichnis (darf nicht {@code null} sein)
     * @param chapterNumber Kapitelnummer in {@code 1..7}
     * @return {@link Optional} mit dem gefundenen Dateipfad oder leer, wenn keine passende Datei existiert
     * @throws NullPointerException     falls {@code outputDir} {@code null} ist
     * @throws IllegalArgumentException falls {@code chapterNumber} außerhalb {@code 1..7} liegt
     * @throws IOException              falls das Verzeichnis nicht aufgelistet werden kann
     */
    public Optional<Path> findPreviewForChapter(Path outputDir, int chapterNumber) throws IOException {
        Objects.requireNonNull(outputDir, "outputDir darf nicht null sein");
        validateChapterNumber(chapterNumber);
        String prefix = String.format(Locale.ROOT, "kapitel_%02d_", chapterNumber);
        for (Path path : listPreviews(outputDir)) {
            if (path.getFileName().toString().startsWith(prefix)) {
                return Optional.of(path);
            }
        }
        return Optional.empty();
    }

    /**
     * Gibt den gekapselten {@link ScreenshotCaptureService} zurück.
     *
     * @return verwendeter {@link ScreenshotCaptureService}
     */
    public ScreenshotCaptureService getCaptureService() {
        return captureService;
    }

    /**
     * Gibt die konfigurierte Breite der gerenderten Vorschaubilder in Pixeln zurück.
     *
     * @return Bildbreite in Pixeln
     */
    public int getWidth() {
        return width;
    }

    /**
     * Gibt die konfigurierte Höhe der gerenderten Vorschaubilder in Pixeln zurück.
     *
     * @return Bildhöhe in Pixeln
     */
    public int getHeight() {
        return height;
    }

    private static byte[] renderSceneCardRgba(int chapter, String title, int w, int h) {
        byte[] rgba = new byte[w * h * 4];
        int[] palette = chapterPalette(chapter);
        paintGradient(rgba, w, h, palette[0], palette[1], palette[2], palette[3], palette[4], palette[5]);
        paintChapterMotif(rgba, w, h, chapter, title.hashCode());
        paintCinemaFrameAndProgress(rgba, w, h, chapter);
        return rgba;
    }

    private static int[] chapterPalette(int chapter) {
        return switch (chapter) {
            case 1 -> new int[]{20, 32, 66, 198, 218, 242};
            case 2 -> new int[]{36, 18, 56, 94, 48, 128};
            case 3 -> new int[]{14, 34, 24, 38, 74, 52};
            case 4 -> new int[]{56, 36, 22, 164, 108, 62};
            case 5 -> new int[]{28, 16, 24, 124, 28, 40};
            case 6 -> new int[]{30, 52, 82, 174, 212, 238};
            case 7 -> new int[]{88, 58, 20, 238, 196, 108};
            default -> new int[]{24, 24, 32, 96, 96, 112};
        };
    }

    private static void paintGradient(byte[] rgba, int w, int h, int r1, int g1, int b1, int r2, int g2, int b2) {
        for (int y = 0; y < h; y++) {
            float t = (float) y / Math.max(1, h - 1);
            int r = Math.round(r1 + (r2 - r1) * t);
            int g = Math.round(g1 + (g2 - g1) * t);
            int b = Math.round(b1 + (b2 - b1) * t);
            for (int x = 0; x < w; x++) {
                putPixel(rgba, w, x, y, r, g, b, 255);
            }
        }
    }

    private static void paintChapterMotif(byte[] rgba, int w, int h, int chapter, int titleHash) {
        int cx = w / 2;
        int cy = h / 2;
        switch (chapter) {
            case 1 -> {
                fillRect(rgba, w, h, cx - 28, cy - 36, cx + 28, cy + 36, 32, 24, 28, 255);
                fillRect(rgba, w, h, cx - 22, cy - 30, cx + 22, cy + 30, 220, 236, 252, 255);
                scatterFlakes(rgba, w, h, 248, 252, 255);
            }
            case 2 -> {
                fillEllipse(rgba, w, h, cx, cy, 38, 54, 196, 160, 74, 255);
                fillEllipse(rgba, w, h, cx, cy, 30, 46, 128, 182, 238, 255);
            }
            case 3 -> {
                for (int tx = w / 6; tx < w; tx += w / 5) {
                    fillRect(rgba, w, h, tx - 5, cy, tx + 5, h - 18, 48, 30, 20, 255);
                    fillEllipse(rgba, w, h, tx, cy - 12, 22, 34, 20, 68, 36, 255);
                }
            }
            case 4 -> {
                fillRect(rgba, w, h, cx - 44, cy - 12, cx + 44, cy + 40, 146, 98, 58, 255);
                fillRect(rgba, w, h, cx - 10, cy + 8, cx + 10, cy + 40, 82, 48, 24, 255);
                fillRect(rgba, w, h, cx - 50, cy - 26, cx + 50, cy - 10, 118, 42, 36, 255);
            }
            case 5 -> {
                fillEllipse(rgba, w, h, cx, cy, 28, 26, 208, 28, 44, 255);
                fillRect(rgba, w, h, cx - 2, cy - 34, cx + 2, cy - 22, 74, 48, 28, 255);
                for (int rx = 16; rx < w; rx += 24) {
                    fillRect(rgba, w, h, rx, 20, rx + 1, 52, 160, 192, 228, 200);
                }
            }
            case 6 -> {
                fillRect(rgba, w, h, cx - 56, cy - 18, cx + 56, cy + 26, 168, 218, 246, 210);
                fillRect(rgba, w, h, cx - 52, cy - 14, cx + 52, cy + 22, 228, 244, 255, 160);
                scatterFlakes(rgba, w, h, 240, 248, 255);
            }
            case 7 -> {
                fillRect(rgba, w, h, cx - 36, cy + 8, cx + 36, cy + 24, 244, 198, 64, 255);
                fillEllipse(rgba, w, h, cx - 22, cy, 10, 14, 252, 218, 88, 255);
                fillEllipse(rgba, w, h, cx, cy - 6, 12, 18, 255, 228, 108, 255);
                fillEllipse(rgba, w, h, cx + 22, cy, 10, 14, 252, 218, 88, 255);
            }
            default -> fillEllipse(rgba, w, h, cx, cy, 24, 24, 200, 200, 210, 255);
        }

        int accentR = 96 + Math.floorMod(titleHash, 140);
        int accentG = 96 + Math.floorMod(titleHash >>> 8, 140);
        int accentB = 96 + Math.floorMod(titleHash >>> 16, 140);
        fillRect(rgba, w, h, 14, 22, 34, 28, accentR, accentG, accentB, 255);
    }

    private static void paintCinemaFrameAndProgress(byte[] rgba, int w, int h, int activeChapter) {
        int barH = Math.max(8, h / 12);
        fillRect(rgba, w, h, 0, 0, w - 1, barH - 1, 10, 10, 16, 255);
        fillRect(rgba, w, h, 0, h - barH, w - 1, h - 1, 10, 10, 16, 255);

        int gap = 4;
        int segmentW = Math.max(8, (w - 32 - (MAX_CHAPTER - 1) * gap) / MAX_CHAPTER);
        int startX = 16;
        int segY0 = h - barH + 2;
        int segY1 = h - 3;
        for (int i = MIN_CHAPTER; i <= MAX_CHAPTER; i++) {
            int x0 = startX + (i - 1) * (segmentW + gap);
            int x1 = x0 + segmentW - 1;
            if (i <= activeChapter) {
                fillRect(rgba, w, h, x0, segY0, x1, segY1, 232, 198, 112, 255);
            } else {
                fillRect(rgba, w, h, x0, segY0, x1, segY1, 54, 58, 72, 255);
            }
        }
    }

    private static void scatterFlakes(byte[] rgba, int w, int h, int r, int g, int b) {
        for (int y = 16; y < h - 16; y += 19) {
            for (int x = (y * 13) % 23; x < w; x += 29) {
                putPixel(rgba, w, x, y, r, g, b, 255);
                if (x + 1 < w) {
                    putPixel(rgba, w, x + 1, y, r, g, b, 255);
                }
            }
        }
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

    private static Map<Integer, String> createDefaultChapterTitles() {
        Map<Integer, String> titles = new LinkedHashMap<>();
        titles.put(1, "Vorgeschichte & Wunsch der Königin");
        titles.put(2, "Die böse Stiefmutter & der Zauberspiegel");
        titles.put(3, "Die Flucht in den tiefen Wald");
        titles.put(4, "Das Haus der sieben Zwerge");
        titles.put(5, "Die drei Mordversuche der Königin");
        titles.put(6, "Der Glassarg & die Erlösung");
        titles.put(7, "Hochzeit & Strafe der bösen Königin");
        return Collections.unmodifiableMap(titles);
    }

    private static void validateChapterNumber(int chapterNumber) {
        if (chapterNumber < MIN_CHAPTER || chapterNumber > MAX_CHAPTER) {
            throw new IllegalArgumentException(
                    "chapterNumber muss zwischen " + MIN_CHAPTER + " und " + MAX_CHAPTER + " liegen, war: " + chapterNumber
            );
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " darf nicht null sein");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " darf nicht leer sein");
        }
        return trimmed;
    }
}
