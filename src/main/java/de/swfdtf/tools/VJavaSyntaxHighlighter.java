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
        List<TokenRun> runs = new ArrayList<>(Math.max(32, len / 6));
        int defaultStart = -1;
        int i = 0;

        while (i < len) {
            char c = source.charAt(i);

            // Embedded @->{ ... }<< Java block
            if (c == '@' && i + 3 < len
                    && source.charAt(i + 1) == '-'
                    && source.charAt(i + 2) == '>'
                    && source.charAt(i + 3) == '{') {
                if (defaultStart >= 0) {
                    runs.add(new TokenRun(defaultStart, i, TokenType.DEFAULT, ThemePalette.TEXT_PRIMARY));
                    defaultStart = -1;
                }
                int end = scanJavaBlockEnd(source, i + 4, len);
                runs.add(new TokenRun(i, end, TokenType.JAVA_BLOCK, ThemePalette.ACCENT_HOVER));
                i = end;
                continue;
            }

            // Line comments: // or #
            if (c == '#' || (c == '/' && i + 1 < len && source.charAt(i + 1) == '/')) {
                if (defaultStart >= 0) {
                    runs.add(new TokenRun(defaultStart, i, TokenType.DEFAULT, ThemePalette.TEXT_PRIMARY));
                    defaultStart = -1;
                }
                int end = scanToLineEnd(source, i, len);
                runs.add(new TokenRun(i, end, TokenType.COMMENT, ThemePalette.TEXT_MUTED));
                i = end;
                continue;
            }

            // String literals "..." or '...' (unterminated terminates safely at EOL/EOF)
            if (c == '"' || c == '\'') {
                if (defaultStart >= 0) {
                    runs.add(new TokenRun(defaultStart, i, TokenType.DEFAULT, ThemePalette.TEXT_PRIMARY));
                    defaultStart = -1;
                }
                int end = scanStringEnd(source, i, len, c);
                runs.add(new TokenRun(i, end, TokenType.STRING, ThemePalette.SUCCESS));
                i = end;
                continue;
            }

            // $ variable marker / identifier
            if (c == '$') {
                if (defaultStart >= 0) {
                    runs.add(new TokenRun(defaultStart, i, TokenType.DEFAULT, ThemePalette.TEXT_PRIMARY));
                    defaultStart = -1;
                }
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
                if (defaultStart >= 0) {
                    runs.add(new TokenRun(defaultStart, i, TokenType.DEFAULT, ThemePalette.TEXT_PRIMARY));
                    defaultStart = -1;
                }
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
                    if (defaultStart >= 0) {
                        runs.add(new TokenRun(defaultStart, i, TokenType.DEFAULT, ThemePalette.TEXT_PRIMARY));
                        defaultStart = -1;
                    }
                    runs.add(new TokenRun(i, end, TokenType.KEYWORD, ThemePalette.ACCENT_IDLE));
                } else if (defaultStart < 0) {
                    defaultStart = i;
                }
                i = end;
                continue;
            }

            // Plain whitespace / punctuation chunk coalesced into DEFAULT run
            if (defaultStart < 0) {
                defaultStart = i;
            }
            i++;
            while (i < len) {
                char next = source.charAt(i);
                if (next == '@' || next == '#' || next == '/' || next == '"' || next == '\''
                        || next == '$' || isAsciiDigit(next) || isIdentStart(next)) {
                    break;
                }
                i++;
            }
        }
        if (defaultStart >= 0) {
            runs.add(new TokenRun(defaultStart, len, TokenType.DEFAULT, ThemePalette.TEXT_PRIMARY));
        }
        return runs;
    }

    private static int scanToLineEnd(String source, int start, int len) {
        int nl = source.indexOf('\n', start);
        return nl >= 0 ? nl : len;
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
        char first = source.charAt(start);
        return switch (len) {
            case 2 -> (first == 'i' && source.charAt(start + 1) == 'f')
                    || (first == 'a' && (source.charAt(start + 1) == 't' || source.charAt(start + 1) == 's'));
            case 3 -> isKeywordLen3(source, start, first);
            case 4 -> isKeywordLen4(source, start, first);
            case 5 -> isKeywordLen5(source, start, first);
            case 6 -> isKeywordLen6(source, start, first);
            case 7 -> (first == 'd' && matches(source, start, "default", 7))
                    || (first == 'g' && matches(source, start, "gridbox", 7));
            case 8 -> isKeywordLen8(source, start, first);
            case 9 -> isKeywordLen9(source, start, first);
            case 10 -> (first == 't' && matches(source, start, "textbutton", 10))
                    || (first == 'o' && matches(source, start, "on_overlap", 10));
            case 12 -> (first == 'l' && matches(source, start, "layeredimage", 12))
                    || (first == 't' && (matches(source, start, "trigger_zone", 12)
                            || matches(source, start, "toggleswitch", 12)));
            case 14 -> (first == 'c' && matches(source, start, "collision_mask", 14))
                    || (first == 's' && matches(source, start, "solid_platform", 14))
                    || (first == 'b' && matches(source, start, "block_rollback", 14));
            default -> false;
        };
    }

    private static boolean isKeywordLen3(String source, int start, char first) {
        return switch (first) {
            case 'b' -> matches(source, start, "bar", 3);
            case 't' -> matches(source, start, "tab", 3);
            case 'n' -> matches(source, start, "nvl", 3);
            default -> false;
        };
    }

    private static boolean isKeywordLen4(String source, int start, char first) {
        return switch (first) {
            case 'j' -> matches(source, start, "jump", 4);
            case 'c' -> matches(source, start, "call", 4) || matches(source, start, "case", 4);
            case 'm' -> matches(source, start, "menu", 4) || matches(source, start, "move", 4);
            case 'e' -> matches(source, start, "elif", 4) || matches(source, start, "else", 4);
            case 's' -> matches(source, start, "show", 4) || matches(source, start, "stop", 4);
            case 'h' -> matches(source, start, "hide", 4) || matches(source, start, "hbox", 4);
            case 'p' -> matches(source, start, "play", 4);
            case 'w' -> matches(source, start, "with", 4);
            case 't' -> matches(source, start, "true", 4) || matches(source, start, "text", 4);
            case 'n' -> matches(source, start, "null", 4);
            case 'v' -> matches(source, start, "vbox", 4) || matches(source, start, "vbar", 4);
            case 'g' -> matches(source, start, "grid", 4);
            default -> false;
        };
    }

    private static boolean isKeywordLen5(String source, int start, char first) {
        return switch (first) {
            case 'l' -> matches(source, start, "label", 5);
            case 'i' -> matches(source, start, "image", 5) || matches(source, start, "input", 5);
            case 'm' -> matches(source, start, "match", 5) || matches(source, start, "modal", 5);
            case 's' -> matches(source, start, "scene", 5) || matches(source, start, "scale", 5);
            case 'v' -> matches(source, start, "voice", 5);
            case 'p' -> matches(source, start, "pause", 5);
            case 'f' -> matches(source, start, "false", 5)
                    || matches(source, start, "fixed", 5)
                    || matches(source, start, "frame", 5);
            default -> false;
        };
    }

    private static boolean isKeywordLen6(String source, int start, char first) {
        return switch (first) {
            case 'd' -> matches(source, start, "define", 6);
            case 's' -> matches(source, start, "screen", 6) || matches(source, start, "slider", 6);
            case 'r' -> matches(source, start, "return", 6) || matches(source, start, "rotate", 6);
            case 'c' -> matches(source, start, "camera", 6);
            case 'h' -> matches(source, start, "hitbox", 6);
            case 'w' -> matches(source, start, "window", 6);
            case 'z' -> matches(source, start, "zorder", 6);
            case 'a' -> matches(source, start, "action", 6);
            case 'b' -> matches(source, start, "bubble", 6);
            default -> false;
        };
    }

    private static boolean isKeywordLen8(String source, int start, char first) {
        return switch (first) {
            case 'p' -> matches(source, start, "parallel", 8);
            case 'v' -> matches(source, start, "viewport", 8);
            case 'c' -> matches(source, start, "checkbox", 8);
            case 't' -> matches(source, start, "tabpanel", 8);
            default -> false;
        };
    }

    private static boolean isKeywordLen9(String source, int start, char first) {
        return switch (first) {
            case 't' -> matches(source, start, "transform", 9) || matches(source, start, "translate", 9);
            case 'm' -> matches(source, start, "move_path", 9);
            case 'w' -> matches(source, start, "wasd_init", 9)
                    || matches(source, start, "wasd_stop", 9)
                    || matches(source, start, "wait_move", 9);
            case 's' -> matches(source, start, "selectbox", 9);
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
