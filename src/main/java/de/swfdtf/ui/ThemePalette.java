package de.swfdtf.ui;

import de.swfdtf.graphics.SceneNode;

/**
 * Canonical KAGAYAKI Dark Immersive UI color palette (Blueprint Section 6.2).
 */
public final class ThemePalette {

    /** Canvas / deepest background ({@code #0d0e15}). */
    public static final String CANVAS_BG = "#0d0e15";

    /** Panel / card surface ({@code #161822}). */
    public static final String PANEL_SURFACE = "#161822";

    /** Semi-transparent frosted glass modal backdrop ({@code #161822d9}). */
    public static final String PANEL_GLASS = "#161822d9";

    /** Elevated interactive surface such as buttons &amp; dialogue box ({@code #1f2232}). */
    public static final String ELEVATED = "#1f2232";

    /** 1px structural border lines ({@code #2e334a}). */
    public static final String BORDER = "#2e334a";

    /** Primary accent in idle state ({@code #388bfd}). */
    public static final String ACCENT_IDLE = "#388bfd";

    /** Hover and keyboard focus accent ({@code #58a6ff}). */
    public static final String ACCENT_HOVER = "#58a6ff";

    /** Active / selected highlight ({@code #f0883e}). */
    public static final String ACTIVE_SELECTED = "#f0883e";

    /** Disabled text / control tint ({@code #8b949e7f}). */
    public static final String DISABLED = "#8b949e7f";

    /** Primary readable body text ({@code #f0f6fc}). */
    public static final String TEXT_PRIMARY = "#f0f6fc";

    /** Secondary muted label text ({@code #8b949e}). */
    public static final String TEXT_MUTED = "#8b949e";

    /** Success status accent ({@code #3fb950}). */
    public static final String SUCCESS = "#3fb950";

    /** Warning / caution accent ({@code #d29922}). */
    public static final String WARNING = "#d29922";

    /** Error / destructive action accent ({@code #f85149}). */
    public static final String ERROR = "#f85149";

    /** WCAG 2.1 Level AA minimum contrast ratio for normal body text ({@code 4.5:1}). */
    public static final double WCAG_AA_NORMAL_CONTRAST = 4.5;

    /**
     * Curated Studio &amp; UI color theme variants ({@code #0129}).
     */
    public enum ThemeVariant {
        /** Default KAGAYAKI Dark Slate palette. */
        DARK_SLATE(
                "Dark Slate",
                CANVAS_BG, PANEL_SURFACE, PANEL_GLASS, ELEVATED, BORDER,
                ACCENT_IDLE, ACCENT_HOVER, ACTIVE_SELECTED,
                TEXT_PRIMARY, TEXT_MUTED, SUCCESS, WARNING, ERROR
        ),
        /** High-contrast dark palette for accessibility and bright studio rooms. */
        MIDNIGHT_HIGH_CONTRAST(
                "Midnight High Contrast",
                "#05060a", "#0d1018", "#0d1018e6", "#161b2a", "#4e5b7c",
                "#4ea1ff", "#7cc0ff", "#ff9e44",
                "#ffffff", "#c2cbd6", "#46d160", "#f0b429", "#ff6b6b"
        ),
        /** Soft daylight palette for brightly lit workspaces. */
        DAYLIGHT(
                "Daylight",
                "#edf1f7", "#ffffff", "#ffffffe6", "#e2e8f0", "#cbd5e1",
                "#1d4ed8", "#2563eb", "#ea580c",
                "#0f172a", "#475569", "#15803d", "#b45309", "#b91c1c"
        );

        private final String displayName;
        private final String canvasBg;
        private final String panelSurface;
        private final String panelGlass;
        private final String elevated;
        private final String border;
        private final String accentIdle;
        private final String accentHover;
        private final String activeSelected;
        private final String textPrimary;
        private final String textMuted;
        private final String success;
        private final String warning;
        private final String error;

        ThemeVariant(
                String displayName,
                String canvasBg,
                String panelSurface,
                String panelGlass,
                String elevated,
                String border,
                String accentIdle,
                String accentHover,
                String activeSelected,
                String textPrimary,
                String textMuted,
                String success,
                String warning,
                String error
        ) {
            this.displayName = displayName;
            this.canvasBg = canvasBg;
            this.panelSurface = panelSurface;
            this.panelGlass = panelGlass;
            this.elevated = elevated;
            this.border = border;
            this.accentIdle = accentIdle;
            this.accentHover = accentHover;
            this.activeSelected = activeSelected;
            this.textPrimary = textPrimary;
            this.textMuted = textMuted;
            this.success = success;
            this.warning = warning;
            this.error = error;
        }

        /**
         * Returns the human-readable theme display name.
         *
         * @return human-readable theme display name
         */
        public String displayName() {
            return displayName;
        }

        /**
         * Returns the canvas background hex color.
         *
         * @return canvas background hex
         */
        public String canvasBg() {
            return canvasBg;
        }

        /**
         * Returns the panel surface hex color.
         *
         * @return panel surface hex
         */
        public String panelSurface() {
            return panelSurface;
        }

        /**
         * Returns the semi-transparent glass modal hex color.
         *
         * @return semi-transparent glass modal hex
         */
        public String panelGlass() {
            return panelGlass;
        }

        /**
         * Returns the elevated surface hex color.
         *
         * @return elevated control/button background hex
         */
        public String elevated() {
            return elevated;
        }

        /**
         * Returns the structural border hex color.
         *
         * @return border color hex
         */
        public String border() {
            return border;
        }

        /**
         * Returns the idle accent hex color.
         *
         * @return idle accent color hex
         */
        public String accentIdle() {
            return accentIdle;
        }

        /**
         * Returns the hover/focus accent hex color.
         *
         * @return hover/focus accent color hex
         */
        public String accentHover() {
            return accentHover;
        }

        /**
         * Returns the selected highlight hex color.
         *
         * @return selected highlight color hex
         */
        public String activeSelected() {
            return activeSelected;
        }

        /**
         * Returns the primary text hex color.
         *
         * @return primary text color hex
         */
        public String textPrimary() {
            return textPrimary;
        }

        /**
         * Returns the secondary muted text hex color.
         *
         * @return muted text color hex
         */
        public String textMuted() {
            return textMuted;
        }

        /**
         * Returns the success indicator hex color.
         *
         * @return success color hex
         */
        public String success() {
            return success;
        }

        /**
         * Returns the warning indicator hex color.
         *
         * @return warning color hex
         */
        public String warning() {
            return warning;
        }

        /**
         * Returns the error indicator hex color.
         *
         * @return error color hex
         */
        public String error() {
            return error;
        }

        /**
         * Resolves a variant by enum name or display name, defaulting to {@link #DARK_SLATE}.
         *
         * @param raw candidate variant string (nullable)
         * @return matching {@link ThemeVariant}
         */
        public static ThemeVariant fromNameOrDefault(String raw) {
            if (raw == null || raw.isBlank()) {
                return DARK_SLATE;
            }
            String trimmed = raw.trim();
            for (ThemeVariant v : values()) {
                if (v.name().equalsIgnoreCase(trimmed) || v.displayName.equalsIgnoreCase(trimmed)) {
                    return v;
                }
            }
            return DARK_SLATE;
        }
    }

    private ThemePalette() {
    }

    /**
     * Parses a hex string into straight RGBA floats {@code [r, g, b, a]}.
     *
     * @param hex {@code #RRGGBB} or {@code #RRGGBBAA}
     * @return 4-float straight RGBA array
     */
    public static float[] straightRgba(String hex) {
        return SceneNode.parseHexRgba(hex);
    }

    /**
     * Parses a hex string and converts to premultiplied RGBA scaled by {@code alphaMultiplier}.
     *
     * @param hex             {@code #RRGGBB} or {@code #RRGGBBAA}
     * @param alphaMultiplier additional opacity multiplier
     * @return 4-float premultiplied RGBA array
     */
    public static float[] premultipliedRgba(String hex, float alphaMultiplier) {
        float[] s = SceneNode.parseHexRgba(hex);
        float a = Math.max(0.0f, Math.min(1.0f, s[3] * alphaMultiplier));
        return new float[]{s[0] * a, s[1] * a, s[2] * a, a};
    }

    /**
     * Computes WCAG 2.1 relative luminance in {@code [0.0, 1.0]} for an sRGB hex color.
     *
     * @param hex color hex string ({@code #RRGGBB} or {@code #RRGGBBAA})
     * @return relative luminance
     */
    public static double relativeLuminance(String hex) {
        float[] rgba = straightRgba(hex);
        double r = linearizeChannel(rgba[0]);
        double g = linearizeChannel(rgba[1]);
        double b = linearizeChannel(rgba[2]);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /**
     * Computes WCAG 2.1 contrast ratio between foreground and background colors ({@code 1.0}..{@code 21.0}).
     *
     * @param foregroundHex foreground color hex
     * @param backgroundHex background color hex
     * @return contrast ratio ({@code >= 1.0})
     */
    public static double contrastRatio(String foregroundHex, String backgroundHex) {
        double l1 = relativeLuminance(foregroundHex);
        double l2 = relativeLuminance(backgroundHex);
        double lighter = Math.max(l1, l2);
        double darker = Math.min(l1, l2);
        return (lighter + 0.05) / (darker + 0.05);
    }

    /**
     * Returns whether the contrast ratio between {@code foregroundHex} and {@code backgroundHex} meets
     * WCAG 2.1 Level AA ({@code >= 4.5:1}).
     *
     * @param foregroundHex foreground color hex
     * @param backgroundHex background color hex
     * @return {@code true} if compliant with WCAG AA normal text contrast
     */
    public static boolean meetsWcagAA(String foregroundHex, String backgroundHex) {
        return contrastRatio(foregroundHex, backgroundHex) >= WCAG_AA_NORMAL_CONTRAST;
    }

    private static double linearizeChannel(double c) {
        double clamped = Math.max(0.0, Math.min(1.0, c));
        return clamped <= 0.03928
                ? clamped / 12.92
                : Math.pow((clamped + 0.055) / 1.055, 2.4);
    }
}
