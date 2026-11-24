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

    /** Elevated interactive surface such as buttons & dialogue box ({@code #1f2232}). */
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
}
