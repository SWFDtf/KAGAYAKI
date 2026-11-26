package de.swfdtf.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic source formatter for KAGAYAKI {@code .vjava} hybrid scripts.
 *
 * <p>Normalizes line endings to {@code \n}, converts leading tabs on Visual Novel lines to
 * 4 spaces, canonicalizes horizontal whitespace inside motion/physics DSL commands, collapses more
 * than two consecutive blank lines to at most two, ensures a single trailing {@code \n} for
 * non-empty scripts, and preserves embedded {@code @->{ ... }<<} Java block interiors verbatim.</p>
 */
public final class VJavaScriptFormatter {

    private static final String TAB_SPACES = "    ";
    private static final String JAVA_BLOCK_OPEN = "@->{";
    private static final String JAVA_BLOCK_CLOSE = "}<<";
    private static final String KODE_PREFIX = "::";
    private static final int MAX_CONSECUTIVE_BLANK_LINES = 2;

    private static final Set<String> CANONICAL_SPACE_KEYWORDS = Set.of(
            "move", "move_by", "move_path", "rotate", "scale",
            "wasd_init", "wasd_stop", "physics_init", "physics_stop",
            "on_collide", "on_overlap", "solid_platform", "collision_mask", "hitbox",
            "wait_move", "camera", "trigger_zone", "patrol", "follow",
            "after", "on_interact", "collider", "shake", "knockback",
            "climb_zone", "moving_platform", "attract", "spawn", "despawn",
            "on_tween_end", "on_event", "spawn_cutscene", "time_scale",
            "attach", "ysort", "point_click_init", "surface_friction", "impulse", "anim_state"
    );

    private enum JavaLexState {
        NORMAL,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        TEXT_BLOCK,
        CHAR
    }

    /**
     * Creates a new {@code VJavaScriptFormatter}.
     */
    public VJavaScriptFormatter() {
    }

    /**
     * Formats raw {@code .vjava} script source text according to KAGAYAKI style rules while
     * preserving {@code @->{ ... }<<} Java block interiors verbatim.
     *
     * @param source raw {@code .vjava} source text (must not be {@code null})
     * @return formatted {@code .vjava} source text
     */
    public String format(String source) {
        Objects.requireNonNull(source, "source must not be null");
        String normalized = normalizeLineEndings(source);
        int length = normalized.length();
        if (length == 0) {
            return "";
        }

        StringBuilder out = new StringBuilder(length + 16);
        int pendingBlankLines = 0;
        boolean hasEmittedContent = false;
        int pos = 0;

        while (pos < length) {
            int lineStart = pos;
            int lineEnd = normalized.indexOf('\n', pos);
            boolean hasNewline = lineEnd >= 0;
            if (!hasNewline) {
                lineEnd = length;
            }

            int indentEnd = findIndentEnd(normalized, lineStart, lineEnd);
            if (indentEnd == lineEnd) {
                pendingBlankLines++;
                pos = hasNewline ? lineEnd + 1 : length;
                continue;
            }

            String rawContent = normalized.substring(indentEnd, lineEnd);
            int javaOpenInRaw = findJavaBlockOpen(rawContent);
            if (javaOpenInRaw >= 0) {
                int openPos = indentEnd + javaOpenInRaw;
                String formattedIndent = convertLeadingTabs(normalized, lineStart, indentEnd);
                String prefixBetweenIndentAndOpen = normalized.substring(indentEnd, openPos);

                int closePos = findJavaBlockClose(normalized, openPos + JAVA_BLOCK_OPEN.length());
                if (closePos < 0) {
                    flushPendingBlankLines(out, pendingBlankLines);
                    pendingBlankLines = 0;
                    out.append(formattedIndent)
                            .append(prefixBetweenIndentAndOpen)
                            .append(normalized, openPos, length);
                    if (normalized.charAt(length - 1) != '\n') {
                        out.append('\n');
                    }
                    hasEmittedContent = true;
                    break;
                }

                int closeEnd = closePos + JAVA_BLOCK_CLOSE.length();
                int afterBlockLineEnd = normalized.indexOf('\n', closeEnd);
                boolean blockLineHasNewline = afterBlockLineEnd >= 0;
                if (!blockLineHasNewline) {
                    afterBlockLineEnd = length;
                }
                String trailingAfterClose = stripTrailingWhitespace(
                        normalized.substring(closeEnd, afterBlockLineEnd)
                );

                flushPendingBlankLines(out, pendingBlankLines);
                pendingBlankLines = 0;

                out.append(formattedIndent)
                        .append(prefixBetweenIndentAndOpen)
                        .append(normalized, openPos, closeEnd)
                        .append(trailingAfterClose)
                        .append('\n');
                hasEmittedContent = true;

                pos = blockLineHasNewline ? afterBlockLineEnd + 1 : length;
                continue;
            }

            String formattedIndent = convertLeadingTabs(normalized, lineStart, indentEnd);
            String strippedContent = canonicalizeDslLineWhitespace(stripTrailingWhitespace(rawContent));

            flushPendingBlankLines(out, pendingBlankLines);
            pendingBlankLines = 0;

            out.append(formattedIndent).append(strippedContent).append('\n');
            hasEmittedContent = true;
            pos = hasNewline ? lineEnd + 1 : length;
        }

        if (!hasEmittedContent) {
            return "";
        }
        return out.toString();
    }

    /**
     * Reads {@code vjavaFile}, formats its contents, and atomically rewrites the file via a
     * temporary file and {@link StandardCopyOption#ATOMIC_MOVE} only if the formatted output
     * differs from the existing file content.
     *
     * @param vjavaFile path to the {@code .vjava} script file (must not be {@code null})
     * @return {@code true} if the file was modified on disk, {@code false} if already formatted
     * @throws IOException if the file does not exist, is not a regular file, or I/O fails
     */
    public boolean formatFile(Path vjavaFile) throws IOException {
        Objects.requireNonNull(vjavaFile, "vjavaFile must not be null");
        Path normalizedFile = vjavaFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedFile)) {
            throw new IOException("Script file does not exist or is not a regular file: " + normalizedFile);
        }

        String original = Files.readString(normalizedFile, StandardCharsets.UTF_8);
        String formatted = format(original);
        if (formatted.equals(original)) {
            return false;
        }

        writeAtomically(normalizedFile, formatted);
        return true;
    }

    private static void flushPendingBlankLines(StringBuilder out, int pendingBlankLines) {
        int count = Math.min(pendingBlankLines, MAX_CONSECUTIVE_BLANK_LINES);
        for (int i = 0; i < count; i++) {
            out.append('\n');
        }
    }

    private static String normalizeLineEndings(String source) {
        String s = source;
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static int findIndentEnd(String s, int lineStart, int lineEnd) {
        int idx = lineStart;
        while (idx < lineEnd) {
            char c = s.charAt(idx);
            if (c == ' ' || c == '\t') {
                idx++;
            } else {
                break;
            }
        }
        return idx;
    }

    private static String convertLeadingTabs(String s, int lineStart, int indentEnd) {
        StringBuilder sb = new StringBuilder(indentEnd - lineStart);
        for (int i = lineStart; i < indentEnd; i++) {
            char c = s.charAt(i);
            if (c == '\t') {
                sb.append(TAB_SPACES);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String canonicalizeDslLineWhitespace(String content) {
        int sp = content.indexOf(' ');
        String kw = sp >= 0 ? content.substring(0, sp) : content;
        if (!CANONICAL_SPACE_KEYWORDS.contains(kw)) {
            return content;
        }
        StringBuilder out = new StringBuilder(content.length());
        boolean inDouble = false;
        boolean inSingle = false;
        boolean escaped = false;
        boolean prevSpace = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (escaped) {
                out.append(c);
                escaped = false;
                prevSpace = false;
                continue;
            }
            if ((inDouble || inSingle) && c == '\\') {
                out.append(c);
                escaped = true;
                prevSpace = false;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                out.append(c);
                prevSpace = false;
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                out.append(c);
                prevSpace = false;
            } else if (!inDouble && !inSingle && (c == ' ' || c == '\t')) {
                if (!prevSpace) {
                    out.append(' ');
                    prevSpace = true;
                }
            } else {
                out.append(c);
                prevSpace = false;
            }
        }
        return out.toString();
    }

    private static String stripTrailingWhitespace(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return end == s.length() ? s : s.substring(0, end);
    }

    private static int findJavaBlockOpen(String rawContent) {
        if (rawContent.startsWith(JAVA_BLOCK_OPEN)) {
            return 0;
        }
        if (rawContent.startsWith(KODE_PREFIX)) {
            return findMarkerOutsideStrings(rawContent, JAVA_BLOCK_OPEN);
        }
        return -1;
    }

    private static int findMarkerOutsideStrings(String line, String marker) {
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        boolean escaped = false;
        int limit = line.length() - marker.length();
        for (int i = 0; i <= limit; i++) {
            char c = line.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inDoubleQuote) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }
            if (inSingleQuote) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }
            if (c == '"') {
                inDoubleQuote = true;
                continue;
            }
            if (c == '\'') {
                inSingleQuote = true;
                continue;
            }
            if (c == '#' || (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/')) {
                return -1;
            }
            if (line.startsWith(marker, i)) {
                return i;
            }
        }
        return -1;
    }

    private static int findJavaBlockClose(String source, int searchStart) {
        int len = source.length();
        int pos = searchStart;
        JavaLexState state = JavaLexState.NORMAL;
        boolean escaped = false;

        while (pos < len) {
            char c = source.charAt(pos);
            switch (state) {
                case NORMAL -> {
                    if (source.startsWith(JAVA_BLOCK_CLOSE, pos)) {
                        return pos;
                    }
                    if (c == '/' && pos + 1 < len && source.charAt(pos + 1) == '/') {
                        state = JavaLexState.LINE_COMMENT;
                        pos += 2;
                    } else if (c == '/' && pos + 1 < len && source.charAt(pos + 1) == '*') {
                        state = JavaLexState.BLOCK_COMMENT;
                        pos += 2;
                    } else if (source.startsWith("\"\"\"", pos)) {
                        state = JavaLexState.TEXT_BLOCK;
                        pos += 3;
                    } else if (c == '"') {
                        state = JavaLexState.STRING;
                        pos++;
                    } else if (c == '\'') {
                        state = JavaLexState.CHAR;
                        pos++;
                    } else {
                        pos++;
                    }
                }
                case LINE_COMMENT -> {
                    if (c == '\n') {
                        state = JavaLexState.NORMAL;
                    }
                    pos++;
                }
                case BLOCK_COMMENT -> {
                    if (c == '*' && pos + 1 < len && source.charAt(pos + 1) == '/') {
                        state = JavaLexState.NORMAL;
                        pos += 2;
                    } else {
                        pos++;
                    }
                }
                case STRING -> {
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"' || c == '\n') {
                        state = JavaLexState.NORMAL;
                    }
                    pos++;
                }
                case TEXT_BLOCK -> {
                    if (escaped) {
                        escaped = false;
                        pos++;
                    } else if (c == '\\') {
                        escaped = true;
                        pos++;
                    } else if (source.startsWith("\"\"\"", pos)) {
                        state = JavaLexState.NORMAL;
                        pos += 3;
                    } else {
                        pos++;
                    }
                }
                case CHAR -> {
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '\'' || c == '\n') {
                        state = JavaLexState.NORMAL;
                    }
                    pos++;
                }
            }
        }
        return -1;
    }

    private static void writeAtomically(Path targetFile, String content) throws IOException {
        Path parent = targetFile.getParent();
        Path tempFile = parent != null
                ? Files.createTempFile(parent, ".vjava-fmt-", ".tmp")
                : Files.createTempFile(".vjava-fmt-", ".tmp");
        boolean moved = false;
        try {
            Files.writeString(tempFile, content, StandardCharsets.UTF_8);
            try {
                Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tempFile);
            }
        }
    }
}
