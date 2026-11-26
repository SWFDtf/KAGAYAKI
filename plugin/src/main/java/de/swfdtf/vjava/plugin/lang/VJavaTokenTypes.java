package de.swfdtf.vjava.plugin.lang;

import com.intellij.psi.tree.IElementType;
import com.intellij.psi.TokenType;

/**
 * All token types produced by {@link VJavaLexer}.
 *
 * Token names intentionally mirror DefaultLanguageHighlighterColors
 * semantic categories so the highlighter mapping is obvious.
 */
public interface VJavaTokenTypes {

    // ------------------------------------------------------------------ //
    //  Whitespace & Bad Character                                          //
    // ------------------------------------------------------------------ //
    IElementType WHITE_SPACE   = TokenType.WHITE_SPACE;
    IElementType BAD_CHARACTER = TokenType.BAD_CHARACTER;

    // ------------------------------------------------------------------ //
    //  Comments                                                            //
    // ------------------------------------------------------------------ //
    /** Lines starting with # or // */
    IElementType LINE_COMMENT  = new VJavaTokenType("LINE_COMMENT");

    // ------------------------------------------------------------------ //
    //  String Literals                                                     //
    // ------------------------------------------------------------------ //
    IElementType STRING        = new VJavaTokenType("STRING");

    // ------------------------------------------------------------------ //
    //  Numeric Literals                                                    //
    // ------------------------------------------------------------------ //
    IElementType NUMBER        = new VJavaTokenType("NUMBER");

    // ------------------------------------------------------------------ //
    //  Keywords & built-in boolean/null literals                          //
    // ------------------------------------------------------------------ //
    IElementType KEYWORD       = new VJavaTokenType("KEYWORD");

    // ------------------------------------------------------------------ //
    //  Predefined symbols: transitions, warpers, positions,               //
    //  matrix functions, global objects, built-in constructors            //
    // ------------------------------------------------------------------ //
    IElementType PREDEFINED    = new VJavaTokenType("PREDEFINED");

    // ------------------------------------------------------------------ //
    //  Operators & Punctuation                                             //
    // ------------------------------------------------------------------ //
    IElementType OPERATION_SIGN = new VJavaTokenType("OPERATION_SIGN");

    // ------------------------------------------------------------------ //
    //  Dollar sign used as statement prefix ($)                           //
    // ------------------------------------------------------------------ //
    IElementType DOLLAR        = new VJavaTokenType("DOLLAR");

    // ------------------------------------------------------------------ //
    //  Java-block delimiters  @->{  and  }<<                              //
    // ------------------------------------------------------------------ //
    IElementType JAVA_BLOCK_START  = new VJavaTokenType("JAVA_BLOCK_START");
    IElementType JAVA_BLOCK_END    = new VJavaTokenType("JAVA_BLOCK_END");
    IElementType JAVA_BLOCK_CONTENT = new VJavaTokenType("JAVA_BLOCK_CONTENT");

    // ------------------------------------------------------------------ //
    //  Doc-prefix for java blocks   ::  …  @->{                          //
    // ------------------------------------------------------------------ //
    IElementType DOC_PREFIX    = new VJavaTokenType("DOC_PREFIX");

    // ------------------------------------------------------------------ //
    //  Arrow  ->  (menu options)                                           //
    // ------------------------------------------------------------------ //
    IElementType ARROW         = new VJavaTokenType("ARROW");

    // ------------------------------------------------------------------ //
    //  Colon  :  (block opener)                                            //
    // ------------------------------------------------------------------ //
    IElementType COLON         = new VJavaTokenType("COLON");

    // ------------------------------------------------------------------ //
    //  Plain identifier                                                   //
    // ------------------------------------------------------------------ //
    IElementType IDENTIFIER    = new VJavaTokenType("IDENTIFIER");

    // ------------------------------------------------------------------ //
    //  Parentheses, brackets                                              //
    // ------------------------------------------------------------------ //
    IElementType LPAREN        = new VJavaTokenType("LPAREN");
    IElementType RPAREN        = new VJavaTokenType("RPAREN");
    IElementType LBRACKET      = new VJavaTokenType("LBRACKET");
    IElementType RBRACKET      = new VJavaTokenType("RBRACKET");

    // ------------------------------------------------------------------ //
    //  Comma                                                               //
    // ------------------------------------------------------------------ //
    IElementType COMMA         = new VJavaTokenType("COMMA");

    // ------------------------------------------------------------------ //
    //  Dot                                                                 //
    // ------------------------------------------------------------------ //
    IElementType DOT           = new VJavaTokenType("DOT");
}
