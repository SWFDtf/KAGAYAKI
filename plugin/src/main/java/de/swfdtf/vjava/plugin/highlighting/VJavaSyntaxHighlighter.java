package de.swfdtf.vjava.plugin.highlighting;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import de.swfdtf.vjava.plugin.lang.VJavaLexer;
import de.swfdtf.vjava.plugin.lang.VJavaTokenTypes;
import org.jetbrains.annotations.NotNull;

import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

/**
 * Maps VJava lexer token types to {@link TextAttributesKey} colour slots.
 *
 * Keys are defined here; their default colours come from
 * {@link DefaultLanguageHighlighterColors} so they look good out of the box.
 * Users can override everything in <em>Settings &gt; Editor &gt; Color Scheme &gt; vjava</em>.
 */
public class VJavaSyntaxHighlighter extends SyntaxHighlighterBase {

    // ------------------------------------------------------------------ //
    //  Public TextAttributesKey constants                                  //
    //  (referenced from VJavaColorSettingsPage & the Annotator)           //
    // ------------------------------------------------------------------ //

    public static final TextAttributesKey COMMENT =
        createTextAttributesKey("VJAVA_LINE_COMMENT",   DefaultLanguageHighlighterColors.LINE_COMMENT);

    public static final TextAttributesKey STRING =
        createTextAttributesKey("VJAVA_STRING",         DefaultLanguageHighlighterColors.STRING);

    public static final TextAttributesKey NUMBER =
        createTextAttributesKey("VJAVA_NUMBER",         DefaultLanguageHighlighterColors.NUMBER);

    public static final TextAttributesKey KEYWORD =
        createTextAttributesKey("VJAVA_KEYWORD",        DefaultLanguageHighlighterColors.KEYWORD);

    public static final TextAttributesKey PREDEFINED =
        createTextAttributesKey("VJAVA_PREDEFINED",     DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL);

    public static final TextAttributesKey OPERATION_SIGN =
        createTextAttributesKey("VJAVA_OPERATION_SIGN", DefaultLanguageHighlighterColors.OPERATION_SIGN);

    public static final TextAttributesKey DOLLAR =
        createTextAttributesKey("VJAVA_DOLLAR",         DefaultLanguageHighlighterColors.KEYWORD);

    public static final TextAttributesKey ARROW =
        createTextAttributesKey("VJAVA_ARROW",          DefaultLanguageHighlighterColors.OPERATION_SIGN);

    public static final TextAttributesKey COLON =
        createTextAttributesKey("VJAVA_COLON",          DefaultLanguageHighlighterColors.OPERATION_SIGN);

    public static final TextAttributesKey IDENTIFIER =
        createTextAttributesKey("VJAVA_IDENTIFIER",     DefaultLanguageHighlighterColors.IDENTIFIER);

    public static final TextAttributesKey JAVA_BLOCK_DELIMITER =
        createTextAttributesKey("VJAVA_JAVA_BLOCK_DELIMITER", DefaultLanguageHighlighterColors.TEMPLATE_LANGUAGE_COLOR);

    public static final TextAttributesKey JAVA_BLOCK_CONTENT =
        createTextAttributesKey("VJAVA_JAVA_BLOCK_CONTENT", DefaultLanguageHighlighterColors.TEMPLATE_LANGUAGE_COLOR);

    public static final TextAttributesKey DOC_PREFIX =
        createTextAttributesKey("VJAVA_DOC_PREFIX",     DefaultLanguageHighlighterColors.DOC_COMMENT);

    /** Used by the Annotator for [var] interpolation inside strings */
    public static final TextAttributesKey INTERPOLATION =
        createTextAttributesKey("VJAVA_INTERPOLATION",  DefaultLanguageHighlighterColors.INSTANCE_FIELD);

    /** Used by the Annotator for {tag} markup inside strings */
    public static final TextAttributesKey MARKUP_TAG =
        createTextAttributesKey("VJAVA_MARKUP_TAG",     DefaultLanguageHighlighterColors.MARKUP_TAG);

    public static final TextAttributesKey BAD_CHARACTER =
        createTextAttributesKey("VJAVA_BAD_CHARACTER",  HighlighterColors.BAD_CHARACTER);

    // ------------------------------------------------------------------ //
    //  SyntaxHighlighterBase implementation                               //
    // ------------------------------------------------------------------ //

    private static final TextAttributesKey[] COMMENT_KEYS        = { COMMENT };
    private static final TextAttributesKey[] STRING_KEYS          = { STRING };
    private static final TextAttributesKey[] NUMBER_KEYS          = { NUMBER };
    private static final TextAttributesKey[] KEYWORD_KEYS         = { KEYWORD };
    private static final TextAttributesKey[] PREDEFINED_KEYS      = { PREDEFINED };
    private static final TextAttributesKey[] OPERATION_SIGN_KEYS  = { OPERATION_SIGN };
    private static final TextAttributesKey[] DOLLAR_KEYS          = { DOLLAR };
    private static final TextAttributesKey[] ARROW_KEYS           = { ARROW };
    private static final TextAttributesKey[] COLON_KEYS           = { COLON };
    private static final TextAttributesKey[] IDENTIFIER_KEYS      = { IDENTIFIER };
    private static final TextAttributesKey[] JAVA_DELIM_KEYS      = { JAVA_BLOCK_DELIMITER };
    private static final TextAttributesKey[] JAVA_CONTENT_KEYS    = { JAVA_BLOCK_CONTENT };
    private static final TextAttributesKey[] DOC_PREFIX_KEYS      = { DOC_PREFIX };
    private static final TextAttributesKey[] BAD_CHAR_KEYS        = { BAD_CHARACTER };
    private static final TextAttributesKey[] EMPTY_KEYS           = {};

    @Override
    public @NotNull Lexer getHighlightingLexer() {
        return new VJavaLexer();
    }

    @Override
    public TextAttributesKey @NotNull [] getTokenHighlights(IElementType tokenType) {
        if (tokenType == VJavaTokenTypes.LINE_COMMENT)      return COMMENT_KEYS;
        if (tokenType == VJavaTokenTypes.STRING)            return STRING_KEYS;
        if (tokenType == VJavaTokenTypes.NUMBER)            return NUMBER_KEYS;
        if (tokenType == VJavaTokenTypes.KEYWORD)           return KEYWORD_KEYS;
        if (tokenType == VJavaTokenTypes.PREDEFINED)        return PREDEFINED_KEYS;
        if (tokenType == VJavaTokenTypes.OPERATION_SIGN)    return OPERATION_SIGN_KEYS;
        if (tokenType == VJavaTokenTypes.DOLLAR)            return DOLLAR_KEYS;
        if (tokenType == VJavaTokenTypes.ARROW)             return ARROW_KEYS;
        if (tokenType == VJavaTokenTypes.COLON)             return COLON_KEYS;
        if (tokenType == VJavaTokenTypes.IDENTIFIER)        return IDENTIFIER_KEYS;
        if (tokenType == VJavaTokenTypes.JAVA_BLOCK_START
         || tokenType == VJavaTokenTypes.JAVA_BLOCK_END)   return JAVA_DELIM_KEYS;
        if (tokenType == VJavaTokenTypes.JAVA_BLOCK_CONTENT) return JAVA_CONTENT_KEYS;
        if (tokenType == VJavaTokenTypes.DOC_PREFIX)        return DOC_PREFIX_KEYS;
        if (tokenType == VJavaTokenTypes.BAD_CHARACTER)     return BAD_CHAR_KEYS;
        return EMPTY_KEYS;
    }
}
