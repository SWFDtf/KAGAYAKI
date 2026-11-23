package de.swfdtf.graphics;

import java.util.Locale;

/**
 * Identifies the active rendering backend mode for KAGAYAKI.
 */
public enum RenderMode {

    /**
     * Production LWJGL 3 / OpenGL 3.3 Core 2D layered and sprite rendering pipeline.
     */
    MODE_2D,

    /**
     * Architectural perspective-camera stub reserved for future 3D stage extensions.
     */
    MODE_3D;

    /**
     * Parses a render mode from string representations such as {@code "2D"}, {@code "3D"},
     * {@code "MODE_2D"}, or {@code "MODE_3D"} (case-insensitive, surrounding whitespace ignored).
     *
     * @param s input mode string
     * @return matching {@link RenderMode}
     * @throws IllegalArgumentException if {@code s} is {@code null}, blank, or unrecognized
     */
    public static RenderMode fromString(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("Render mode string must not be null or blank");
        }
        String normalized = s.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "2D", "MODE_2D" -> MODE_2D;
            case "3D", "MODE_3D" -> MODE_3D;
            default -> throw new IllegalArgumentException("Unsupported render mode: " + s);
        };
    }
}
