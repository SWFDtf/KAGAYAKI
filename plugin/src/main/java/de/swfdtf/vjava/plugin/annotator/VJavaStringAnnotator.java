package de.swfdtf.vjava.plugin.annotator;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import de.swfdtf.vjava.plugin.highlighting.VJavaSyntaxHighlighter;
import de.swfdtf.vjava.plugin.lang.VJavaTokenTypes;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Annotator that provides sub-highlighting inside VJava string literals:
 * <ul>
 *   <li><b>Variable interpolation:</b> {@code [varname]} or {@code [obj.field]} (not {@code [[})</li>
 *   <li><b>Inline markup tags:</b> {@code {b}}, {@code {/b}}, {@code {color=#...}}, etc. (not {@code {{})</li>
 * </ul>
 *
 * This runs <em>after</em> the lexer-based syntax highlighter and works on
 * {@code STRING} tokens, adding zero-severity info annotations that just apply
 * custom text-attribute keys to sub-ranges of the token.
 */
public class VJavaStringAnnotator implements Annotator {

    // ------------------------------------------------------------------ //
    // Patterns                                                            //
    // ------------------------------------------------------------------ //

    /**
     * Matches [varname], [obj.field], [expr.method()] etc.,
     * but NOT [[ (escaped bracket).
     * Group 1 = the whole [....] span including brackets.
     */
    private static final Pattern INTERPOLATION_PATTERN =
        Pattern.compile("(?<!\\[)\\[([^\\[\\]\\r\\n]+)]");

    /**
     * Matches VJava inline text tags: {tag}, {tag=value}, {/tag}, {tag=#value}, {tag=...}
     * but NOT {{ (escaped brace).
     * Full list from spec section 8.
     */
    private static final Pattern MARKUP_TAG_PATTERN =
        Pattern.compile(
            "(?<!\\{)\\{" +                             // opening {, not {{
            "(?:" +
              // closing tags
              "/(?:b|i|u|s|sc|color|alpha|size|font|k|cps|rb|rt|a|wave|jitter|shake|gradient)" +
              "|" +
              // opening/self-closing tags
              "b|i|u|s|sc|plain|nw|fast|w|p" +
              "|w=[0-9.]+|p=[0-9.]+" +
              "|color=#[0-9a-fA-F]{3,8}" +
              "|alpha=[0-9.e-]+" +
              "|size=[+-]?[0-9]+" +
              "|font=[^}]+" +
              "|k=[0-9.e-]+" +
              "|cps=[0-9]+" +
              "|rb|rt|image=[^}]+" +
              "|a=[^}]+" +
              "|wave|jitter|shake" +
              "|gradient=#[0-9a-fA-F]{3,8},[^}]+" +
            ")" +
            "\\}",                                      // closing }
            Pattern.CASE_INSENSITIVE
        );

    // ------------------------------------------------------------------ //
    // Annotator implementation                                            //
    // ------------------------------------------------------------------ //

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        // We only annotate STRING tokens
        if (element.getNode() == null) return;
        if (element.getNode().getElementType() != VJavaTokenTypes.STRING) return;

        final String text = element.getText();
        final int startOffset = element.getTextRange().getStartOffset();

        // Skip very short or non-dq strings (single-char, e.g. just a quote)
        if (text.length() < 2) return;

        // Only process double-quoted strings for interpolation & markup
        // (single-quoted strings can appear in expressions, not dialog)
        boolean isDQ = text.startsWith("\"");
        if (!isDQ) return;

        // --- Variable interpolation [var] ---
        applyPattern(text, startOffset, INTERPOLATION_PATTERN,
                     VJavaSyntaxHighlighter.INTERPOLATION, holder);

        // --- Markup tags {b}, {color=...}, etc. ---
        applyPattern(text, startOffset, MARKUP_TAG_PATTERN,
                     VJavaSyntaxHighlighter.MARKUP_TAG, holder);
    }

    private static void applyPattern(
            @NotNull String text,
            int startOffset,
            @NotNull Pattern pattern,
            @NotNull TextAttributesKey key,
            @NotNull AnnotationHolder holder) {

        Matcher m = pattern.matcher(text);
        while (m.find()) {
            int rangeStart = startOffset + m.start();
            int rangeEnd   = startOffset + m.end();
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                  .range(TextRange.create(rangeStart, rangeEnd))
                  .textAttributes(key)
                  .create();
        }
    }
}
