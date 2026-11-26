package de.swfdtf.vjava.plugin.lang;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;

%%

%class _VJavaLexer
%implements FlexLexer
%unicode
%function advance
%type IElementType
%eof{ return;
%eof}

%{
  // Track whether we are inside a Java block so } alone doesn't end it
  private int javaBlockDepth = 0;
%}

/* ------------------------------------------------------------------ */
/* Character classes                                                   */
/* ------------------------------------------------------------------ */
LineTerminator  = \r|\n|\r\n
WhiteSpace      = [ \t\f]+
Identifier      = [a-zA-Z_][a-zA-Z0-9_]*

/* Integer literals */
HexInt          = 0[xX][0-9a-fA-F][0-9a-fA-F_]*
DecInt          = [0-9][0-9_]*
/* Float literals */
Float           = ({DecInt}\.{DecInt}? | \.{DecInt}) ([eE][+-]?{DecInt})? [fF]?
                | {DecInt} [eE][+-]?{DecInt} [fF]?

/* ------------------------------------------------------------------ */
/* Keywords                                                            */
/* ------------------------------------------------------------------ */
/* Boolean / Null */
BoolNull  = "true" | "false" | "null" | "None"

/* Top-level & structure declarations */
TopLevel  = "define" | "default" | "image" | "layeredimage" | "transform"
          | "screen" | "translate" | "label"

/* Layeredimage & ATL structural keywords */
Structural = "always" | "group" | "attribute" | "pause" | "repeat"
           | "parallel" | "block" | "choice" | "contains" | "on"
           | "warp" | "infinite" | "forever"

/* Screen UI & Widgets */
Widgets   = "modal" | "zorder" | "key" | "timer" | "vbox" | "hbox"
          | "grid" | "fixed" | "frame" | "window" | "viewport" | "vpgrid"
          | "imagemap" | "draggroup" | "side" | "text" | "textbutton"
          | "button" | "imagebutton" | "bar" | "vbar" | "slider" | "input"
          | "hotspot" | "hotbar" | "drag" | "mousearea" | "custom" | "add"
          | "use" | "for"

/* Control flow */
Control   = "if" | "elif" | "else" | "menu" | "timeout" | "disabled"
          | "jump" | "call" | "expression" | "return" | "pass"
          | "block_rollback" | "with" | "at" | "to" | "as"

/* Stage & Audio */
Stage     = "scene" | "show" | "hide" | "nvl" | "clear" | "auto"
          | "play" | "queue" | "stop" | "voice" | "loop" | "noloop"
          | "fadein" | "fadeout" | "volume" | "all"

/* Motion & Physics */
Motion    = "move" | "move_by" | "wasd_init" | "wasd_stop"
          | "physics_init" | "physics_stop" | "on_collide"
          | "duration" | "ease" | "easing" | "speed" | "bounds"
          | "diagonal_norm" | "gravity" | "jump_force" | "ground_y"

/* Localisation */
Localise  = "strings" | "old" | "new" | "allow" | "length"

/* Hard  */
HardKW = {BoolNull} | {TopLevel} | {Structural} | {Widgets}
       | {Control} | {Stage} | {Motion} | {Localise}

/* ------------------------------------------------------------------ */
/* Predefined symbols                                                  */
/* ------------------------------------------------------------------ */
/* Characters / UI value types */
PredChars = "Character" | "VariableValue" | "VariableInputValue"

/* Transitions & warpers */
PredTrans = "dissolve" | "fade" | "pixellate" | "pixelate"
          | "wipeleft" | "wiperight" | "wipeup" | "wipedown"
          | "slideleft" | "slideright" | "pushleft" | "pushright"
          | "irisin" | "irisout" | "blinds" | "squares"
          | "imagedissolve" | "multiple" | "vpunch" | "hpunch"
          | "none" | "linear" | "easein" | "ease_in" | "easeout" | "ease_out"
          | "easeinout" | "ease_in_out" | "smoothstep" | "ease_back"
          | "cubic_bezier"

/* Matrixcolor functions */
PredMatrix = "identity" | "brightness" | "contrast" | "saturation"
           | "hue" | "opacity" | "invert" | "tint"

/* Positions */
PredPos = "left" | "center" | "truecenter" | "right"
        | "offscreenleft" | "offscreenright" | "reset"

/* Global objects */
PredGlobal = "state" | "persistent" | "engine" | "Math"

PredAll = {PredChars} | {PredTrans} | {PredMatrix} | {PredPos} | {PredGlobal}

/* ------------------------------------------------------------------ */
/* Logical keywords used as operators                                  */
/* ------------------------------------------------------------------ */
LogicKW = "and" | "or" | "not"

/* ------------------------------------------------------------------ */
/* States                                                              */
/* ------------------------------------------------------------------ */
%state IN_STRING_DQ
%state IN_STRING_SQ
%state IN_JAVA_BLOCK
%state IN_DOC_PREFIX

%%

/* ------------------------------------------------------------------ */
/* INITIAL state                                                       */
/* ------------------------------------------------------------------ */
<YYINITIAL> {

  /* Whitespace */
  {WhiteSpace}         { return VJavaTokenTypes.WHITE_SPACE; }
  {LineTerminator}     { return VJavaTokenTypes.WHITE_SPACE; }

  /* Single-line comments */
  "#" [^\r\n]*         { return VJavaTokenTypes.LINE_COMMENT; }
  "//" [^\r\n]*        { return VJavaTokenTypes.LINE_COMMENT; }

  /* ---- Doc-prefix for java blocks  :: description @->{ ---- */
  "::" / [^\r\n]*"@->{" {
    yybegin(IN_DOC_PREFIX);
    return VJavaTokenTypes.DOC_PREFIX;
  }

  /* ---- Java block start (no prefix) @->{ ---- */
  "@->{"               {
    yybegin(IN_JAVA_BLOCK);
    javaBlockDepth = 0;
    return VJavaTokenTypes.JAVA_BLOCK_START;
  }

  /* ---- Double-quoted strings ---- */
  \"                   { yybegin(IN_STRING_DQ); return VJavaTokenTypes.STRING; }

  /* ---- Single-quoted strings ---- */
  \'                   { yybegin(IN_STRING_SQ); return VJavaTokenTypes.STRING; }

  /* ---- Numbers ---- */
  {HexInt}             { return VJavaTokenTypes.NUMBER; }
  {Float}              { return VJavaTokenTypes.NUMBER; }
  {DecInt}             { return VJavaTokenTypes.NUMBER; }

  /* ---- Dollar prefix ---- */
  "$"                  { return VJavaTokenTypes.DOLLAR; }

  /* ---- Arrow operator (must be before single -) ---- */
  "->"                 { return VJavaTokenTypes.ARROW; }

  /* ---- Compound assignment operators ---- */
  "+=" | "-=" | "*=" | "/=" { return VJavaTokenTypes.OPERATION_SIGN; }

  /* ---- Comparison & equality ---- */
  "==" | "!=" | "<=" | ">=" | "<" | ">" { return VJavaTokenTypes.OPERATION_SIGN; }

  /* ---- Logical operators (symbol form) ---- */
  "&&" | "||" | "!"   { return VJavaTokenTypes.OPERATION_SIGN; }

  /* ---- Assignment ---- */
  "="                  { return VJavaTokenTypes.OPERATION_SIGN; }

  /* ---- Arithmetic ---- */
  "+" | "-" | "*" | "/" | "%" { return VJavaTokenTypes.OPERATION_SIGN; }

  /* ---- Ternary ---- */
  "?"                  { return VJavaTokenTypes.OPERATION_SIGN; }

  /* ---- Colon (block opener / ternary else) ---- */
  ":"                  { return VJavaTokenTypes.COLON; }

  /* ---- Punctuation ---- */
  "("                  { return VJavaTokenTypes.LPAREN; }
  ")"                  { return VJavaTokenTypes.RPAREN; }
  "["                  { return VJavaTokenTypes.LBRACKET; }
  "]"                  { return VJavaTokenTypes.RBRACKET; }
  ","                  { return VJavaTokenTypes.COMMA; }
  "."                  { return VJavaTokenTypes.DOT; }
  "#"                  { return VJavaTokenTypes.OPERATION_SIGN; } /* colour literals like #FF0000 after = */

  /* ---- Keywords (must come BEFORE identifier rule) ---- */
  {HardKW} / [^a-zA-Z0-9_]  { return VJavaTokenTypes.KEYWORD; }

  /* ---- Logical keywords used as operators ---- */
  {LogicKW} / [^a-zA-Z0-9_] { return VJavaTokenTypes.OPERATION_SIGN; }

  /* ---- Predefined symbols ---- */
  {PredAll} / [^a-zA-Z0-9_]  { return VJavaTokenTypes.PREDEFINED; }

  /* ---- Generic identifier ---- */
  {Identifier}         { return VJavaTokenTypes.IDENTIFIER; }

  /* ---- Catch-all ---- */
  .                    { return VJavaTokenTypes.BAD_CHARACTER; }
}

/* ------------------------------------------------------------------ */
/* IN_DOC_PREFIX – consume until @->{ is found, then switch to java   */
/* ------------------------------------------------------------------ */
<IN_DOC_PREFIX> {
  [^\r\n]*"@->{"       {
    yybegin(IN_JAVA_BLOCK);
    javaBlockDepth = 0;
    // We want the @->{ to be a separate token - but JFlex doesn't allow
    // multiple tokens per rule. We emit the whole prefix + @->{ as DOC_PREFIX
    // and the highlighter colours it. For injection purposes the MultiHostInjector
    // will find JAVA_BLOCK_CONTENT tokens anyway.
    return VJavaTokenTypes.DOC_PREFIX;
  }
  /* Should not happen, but fall back */
  [^\r\n]*             { yybegin(YYINITIAL); return VJavaTokenTypes.DOC_PREFIX; }
  {LineTerminator}     { yybegin(YYINITIAL); return VJavaTokenTypes.WHITE_SPACE; }
}

/* ------------------------------------------------------------------ */
/* IN_JAVA_BLOCK – consume Java code until }<<                        */
/* { increments depth, } alone decrements, }<<  ends the block       */
/* ------------------------------------------------------------------ */
<IN_JAVA_BLOCK> {
  /* Closing sequence: }<<  ends the java block */
  "}<<"                {
    yybegin(YYINITIAL);
    return VJavaTokenTypes.JAVA_BLOCK_END;
  }

  /* Opening brace INSIDE java code (track depth, but don't end) */
  "{"                  {
    javaBlockDepth++;
    return VJavaTokenTypes.JAVA_BLOCK_CONTENT;
  }

  /* Closing brace that is NOT }<<  */
  "}"                  {
    if (javaBlockDepth > 0) javaBlockDepth--;
    return VJavaTokenTypes.JAVA_BLOCK_CONTENT;
  }

  /* Java double-quoted string (don't let }<<  inside end the block) */
  \"([^\\\"]|\\.)*\"   { return VJavaTokenTypes.JAVA_BLOCK_CONTENT; }

  /* Java single-quoted char literal */
  \'([^\\\'\\n]|\\.)\' { return VJavaTokenTypes.JAVA_BLOCK_CONTENT; }

  /* Java text block """...""" */
  "\"\"\"" ~"\"\"\""   { return VJavaTokenTypes.JAVA_BLOCK_CONTENT; }

  /* Java line comment */
  "//" [^\r\n]*        { return VJavaTokenTypes.JAVA_BLOCK_CONTENT; }

  /* Java block comment */
  "/*" ~"*/"           { return VJavaTokenTypes.JAVA_BLOCK_CONTENT; }

  /* Anything else – accumulate in one character at a time for correctness */
  [^{}\"\']+           { return VJavaTokenTypes.JAVA_BLOCK_CONTENT; }

  /* Fallback for single chars we haven't matched */
  .|\n                 { return VJavaTokenTypes.JAVA_BLOCK_CONTENT; }
}

/* ------------------------------------------------------------------ */
/* IN_STRING_DQ – double-quoted string body                           */
/* ------------------------------------------------------------------ */
<IN_STRING_DQ> {
  \"                   { yybegin(YYINITIAL); return VJavaTokenTypes.STRING; }
  \\[ntrb\\\"\'0]      { return VJavaTokenTypes.STRING; }  /* escape sequences */
  [^\\\"\r\n]+         { return VJavaTokenTypes.STRING; }
  \\.                  { return VJavaTokenTypes.STRING; }  /* other escapes */
  {LineTerminator}     { yybegin(YYINITIAL); return VJavaTokenTypes.STRING; }
  <<EOF>>              { yybegin(YYINITIAL); return VJavaTokenTypes.STRING; }
}

/* ------------------------------------------------------------------ */
/* IN_STRING_SQ – single-quoted string body                           */
/* ------------------------------------------------------------------ */
<IN_STRING_SQ> {
  \'                   { yybegin(YYINITIAL); return VJavaTokenTypes.STRING; }
  \\[ntrb\\\"\'0]      { return VJavaTokenTypes.STRING; }
  [^\\\'\r\n]+         { return VJavaTokenTypes.STRING; }
  \\.                  { return VJavaTokenTypes.STRING; }
  {LineTerminator}     { yybegin(YYINITIAL); return VJavaTokenTypes.STRING; }
  <<EOF>>              { yybegin(YYINITIAL); return VJavaTokenTypes.STRING; }
}
