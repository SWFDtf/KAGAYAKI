package de.swfdtf.state;

/**
 * AST-free immutable translation entry representing a single dialogue line or UI string mapping.
 *
 * @param id             optional block or line identifier (e.g., {@code "start_5"}, {@code "strings"}, or {@code null})
 * @param sourceText     original source language text (nullable if ID-only override)
 * @param translatedText target translated text (nullable or empty when untranslated)
 * @param context        optional context such as speaker ID or source location (nullable)
 * @param lineNumber     1-based source line number ({@code 0} if unspecified)
 */
public record TranslationEntry(
        String id,
        String sourceText,
        String translatedText,
        String context,
        int lineNumber
) {

    /**
     * Validates that {@code lineNumber} is non-negative.
     *
     * @throws IllegalArgumentException if {@code lineNumber} is negative
     */
    public TranslationEntry {
        if (lineNumber < 0) {
            throw new IllegalArgumentException("lineNumber must be >= 0, got: " + lineNumber);
        }
    }
}
