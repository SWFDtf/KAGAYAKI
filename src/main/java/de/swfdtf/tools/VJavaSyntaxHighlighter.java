package de.swfdtf.tools;

import de.swfdtf.ui.ThemePalette;

import java.util.ArrayList;
import java.util.List;

/**
 * Fast single-pass lexical syntax highlighter for {@code .vjava} source scripts mapped to
 * the Dark-Slate {@link ThemePalette}.
 */
public final class VJavaSyntaxHighlighter {

    /**
     * Highlight token categories for {@code .vjava} syntax elements.
     */
    public enum TokenType {
        /** Structural DSL keywords ({@code label}, {@code show}, {@code jump}, etc.). */
        KEYWORD,
        /** Double- or single-quoted string literals. */
        STRING,
        /** Line comments ({@code //} or {@code #}). */
        COMMENT,
        /** {@code $}-prefixed variable declarations or inline references. */
        VARIABLE,
        /** Embedded {@code @->{ ... }<<} native Java blocks. */
        JAVA_BLOCK,
        /** Numeric literals. */
        NUMBER,
        /** Unhighlighted source spans (identifiers, whitespace, punctuation). */
        DEFAULT
    }

    /**
     * Immutable highlighted span in source character offsets {@code [startOffset, endOffset)}.
     *
     * @param startOffset inclusive zero-based start offset
     * @param endOffset   exclusive end offset
     * @param type        classified {@link TokenType}
     * @param colorHex    Dark-Slate hex color code
     */
    public record TokenRun(int startOffset, int endOffset, TokenType type, String colorHex) {
    }

    /**
     * Creates a new {@code VJavaSyntaxHighlighter}.
     */
    public VJavaSyntaxHighlighter() {
    }

    /**
     * Tokenizes {@code source} into Dark-Slate colored {@link TokenRun} spans in a single O(n) pass.
     *
     * @param source raw {@code .vjava} script text (nullable)
     * @return immutable list of contiguous {@link TokenRun} spans
     */
    public List<TokenRun> tokenize(String source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        int len = source.length();
        List<TokenRun> runs = new ArrayList<>(Math.max(16, len / 8));
        int i = 0;

        while (i < len) {
            char c = source.charAt(i);

            // Embedded @->{ ... }<< Java block
            if (c == '@' && i + 3 < len
                    && source.charAt(i + 1) == '-'
                    && source.charAt(i + 2) == '>'
                    && source.charAt(i + 3) == '{') {
                int end = scanJavaBlockEnd(source, i + 4, len);
                runs.add(new TokenRun(i, end, TokenType.JAVA_BLOCK, ThemePalette.ACCENT_HOVER));
                i = end;
                continue;
            }

            // Line comments: // or #
            if (c == '#' || (c == '/' && i + 1 < len && source.charAt(i + 1) == '/')) {
                int end = scanToLineEnd(source, i, len);
                runs.add(new TokenRun(i, end, TokenType.COMMENT, ThemePalette.TEXT_MUTED));
                i = end;
                continue;
            }

            // String literals "..." or '...' (unterminated terminates safely at EOL/EOF)
            if (c == '"' || c == '\'') {
                int end = scanStringEnd(source, i, len, c);
                runs.add(new TokenRun(i, end, TokenType.STRING, ThemePalette.SUCCESS));
                i = end;
                continue;
            }

            // $ variable marker / identifier
            if (c == '$') {
                int end = i + 1;
                while (end < len && isIdentPart(source.charAt(end))) {
                    end++;
                }
                runs.add(new TokenRun(i, end, TokenType.VARIABLE, ThemePalette.ACTIVE_SELECTED));
                i = end;
                continue;
            }

            // Numeric literal
            if (isAsciiDigit(c)) {
                int end = i + 1;
                while (end < len && (isAsciiDigit(source.charAt(end)) || source.charAt(end) == '.')) {
                    end++;
                }
                runs.add(new TokenRun(i, end, TokenType.NUMBER, ThemePalette.WARNING));
                i = end;
                continue;
            }

            // Identifier / keyword
            if (isIdentStart(c)) {
                int end = i + 1;
                while (end < len && isIdentPart(source.charAt(end))) {
                    end++;
                }
                if (isKeyword(source, i, end)) {
                    runs.add(new TokenRun(i, end, TokenType.KEYWORD, ThemePalette.ACCENT_IDLE));
                } else {
                    runs.add(new TokenRun(i, end, TokenType.DEFAULT, ThemePalette.TEXT_PRIMARY));
                }
                i = end;
                continue;
            }

            // Plain whitespace / punctuation chunk
            int start = i;
            i++;
            while (i < len) {
                char next = source.charAt(i);
                if (next == '@' || next == '#' || next == '/' || next == '"' || next == '\''
                        || next == '$' || isAsciiDigit(next) || isIdentStart(next)) {
                    break;
                }
                i++;
            }
            runs.add(new TokenRun(start, i, TokenType.DEFAULT, ThemePalette.TEXT_PRIMARY));
        }
        return runs;
    }

    private static int scanToLineEnd(String source, int start, int len) {
        int i = start;
        while (i < len && source.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    private static int scanStringEnd(String source, int start, int len, char quote) {
        int i = start + 1;
        while (i < len) {
            char ch = source.charAt(i);
            if (ch == '\\' && i + 1 < len) {
                i += 2;
                continue;
            }
            if (ch == '\n') {
                return i;
            }
            if (ch == quote) {
                return i + 1;
            }
            i++;
        }
        return len;
    }

    private static int scanJavaBlockEnd(String source, int start, int len) {
        int i = start;
        while (i < len) {
            char ch = source.charAt(i);
            if (ch == '"' || ch == '\'') {
                i++;
                while (i < len) {
                    char sc = source.charAt(i);
                    if (sc == '\\' && i + 1 < len) {
                        i += 2;
                        continue;
                    }
                    if (sc == ch) {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (ch == '/' && i + 1 < len) {
                char next = source.charAt(i + 1);
                if (next == '/') {
                    i = scanToLineEnd(source, i + 2, len);
                    continue;
                }
                if (next == '*') {
                    i += 2;
                    while (i + 1 < len && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                        i++;
                    }
                    i = Math.min(len, i + 2);
                    continue;
                }
            }
            if (ch == '}' && i + 2 < len && source.charAt(i + 1) == '<' && source.charAt(i + 2) == '<') {
                return i + 3;
            }
            i++;
        }
        return len;
    }

    private static boolean isAsciiDigit(char ch) {
        return ch >= '0' && ch <= '9';
    }

    private static boolean isIdentStart(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || ch == '_';
    }

    private static boolean isKeyword(String source, int start, int end) {
        int len = end - start;
        return switch (len) {
            case 2 -> matches(source, start, "if", 2)
                    || matches(source, start, "at", 2)
                    || matches(source, start, "as", 2);
            case 3 -> matches(source, start, "bar", 3)
                    || matches(source, start, "tab", 3)
                    || matches(source, start, "nvl", 3);
            case 4 -> matches(source, start, "jump", 4)
                    || matches(source, start, "call", 4)
                    || matches(source, start, "menu", 4)
                    || matches(source, start, "elif", 4)
                    || matches(source, start, "else", 4)
                    || matches(source, start, "case", 4)
                    || matches(source, start, "show", 4)
                    || matches(source, start, "hide", 4)
                    || matches(source, start, "play", 4)
                    || matches(source, start, "stop", 4)
                    || matches(source, start, "with", 4)
                    || matches(source, start, "move", 4)
                    || matches(source, start, "true", 4)
                    || matches(source, start, "null", 4)
                    || matches(source, start, "vbox", 4)
                    || matches(source, start, "hbox", 4)
                    || matches(source, start, "grid", 4)
                    || matches(source, start, "text", 4)
                    || matches(source, start, "vbar", 4);
            case 5 -> matches(source, start, "label", 5)
                    || matches(source, start, "image", 5)
                    || matches(source, start, "match", 5)
                    || matches(source, start, "scene", 5)
                    || matches(source, start, "voice", 5)
                    || matches(source, start, "pause", 5)
                    || matches(source, start, "scale", 5)
                    || matches(source, start, "false", 5)
                    || matches(source, start, "fixed", 5)
                    || matches(source, start, "frame", 5)
                    || matches(source, start, "input", 5)
                    || matches(source, start, "modal", 5);
            case 6 -> matches(source, start, "define", 6)
                    || matches(source, start, "screen", 6)
                    || matches(source, start, "return", 6)
                    || matches(source, start, "rotate", 6)
                    || matches(source, start, "camera", 6)
                    || matches(source, start, "hitbox", 6)
                    || matches(source, start, "window", 6)
                    || matches(source, start, "slider", 6)
                    || matches(source, start, "zorder", 6)
                    || matches(source, start, "action", 6)
                    || matches(source, start, "bubble", 6);
            case 7 -> matches(source, start, "default", 7)
                    || matches(source, start, "gridbox", 7);
            case 8 -> matches(source, start, "parallel", 8)
                    || matches(source, start, "viewport", 8)
                    || matches(source, start, "checkbox", 8)
                    || matches(source, start, "tabpanel", 8);
            case 9 -> matches(source, start, "transform", 9)
                    || matches(source, start, "translate", 9)
                    || matches(source, start, "move_path", 9)
                    || matches(source, start, "wasd_init", 9)
                    || matches(source, start, "wasd_stop", 9)
                    || matches(source, start, "wait_move", 9)
                    || matches(source, start, "selectbox", 9);
            case 10 -> matches(source, start, "textbutton", 10)
                    || matches(source, start, "on_overlap", 10);
            case 12 -> matches(source, start, "layeredimage", 12)
                    || matches(source, start, "trigger_zone", 12)
                    || matches(source, start, "toggleswitch", 12);
            case 14 -> matches(source, start, "collision_mask", 14)
                    || matches(source, start, "solid_platform", 14)
                    || matches(source, start, "block_rollback", 14);
            default -> false;
        };
    }

    private static boolean matches(String source, int start, String keyword, int len) {
        return source.regionMatches(start, keyword, 0, len);
    }

    private static boolean isIdentPart(char ch) {
        return isIdentStart(ch) || isAsciiDigit(ch);
    }
}
