package de.swfdtf.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Line- and indentation-aware lexer for KAGAYAKI {@code .vjava} hybrid scripts.
 *
 * <p>Separates indentation-based Visual Novel script lines from embedded native Java blocks
 * delimited by {@code @->{ ... }<<} (optionally preceded by {@code :: Kode für VN: ...}).
 * Inside {@code @->{ ... }<<}, Java braces {@code {}} inside classes, methods, strings,
 * text blocks, character literals, and comments are preserved verbatim without prematurely
 * terminating the block.</p>
 */
public final class VJavaLexer {

    private static final String JAVA_BLOCK_OPEN = "@->{";
    private static final String JAVA_BLOCK_CLOSE = "}<<";
    private static final String KODE_PREFIX = "::";

    /**
     * Token categories emitted by {@link VJavaLexer}.
     */
    public enum TokenType {
        /** Increases indentation block depth. */
        INDENT,
        /** Decreases indentation block depth back to a prior stack level. */
        DEDENT,
        /** Single logical Visual Novel script line (with trailing comments stripped). */
        LINE,
        /** Native Java block delimited by {@code @->{ ... }<<}. */
        JAVA_BLOCK,
        /** Synthetic end-of-file marker. */
        EOF
    }

    /**
     * Immutable lexical token produced by {@link VJavaLexer}.
     */
    public static final class Token {
        private final TokenType type;
        private final String sourceFile;
        private final int lineNumber;
        private final int indentColumns;
        private final String text;
        private final String description;
        private final String code;
        private final List<String> precedingComments;

        /**
         * Creates a new {@link Token}.
         *
         * @param type          token category
         * @param sourceFile    source file path or identifier
         * @param lineNumber    1-based starting line number
         * @param indentColumns leading space column count
         * @param text          normalized VN line text (or header text for Java blocks)
         * @param description   optional description extracted from {@code :: Kode für VN: ...}
         * @param code          verbatim inner Java code for {@link TokenType#JAVA_BLOCK}, else {@code ""}
         */
        public Token(
                TokenType type,
                String sourceFile,
                int lineNumber,
                int indentColumns,
                String text,
                String description,
                String code
        ) {
            this(type, sourceFile, lineNumber, indentColumns, text, description, code, List.of());
        }

        Token(
                TokenType type,
                String sourceFile,
                int lineNumber,
                int indentColumns,
                String text,
                String description,
                String code,
                List<String> precedingComments
        ) {
            this.type = Objects.requireNonNull(type, "type must not be null");
            this.sourceFile = sourceFile != null ? sourceFile : "<unknown>";
            this.lineNumber = lineNumber;
            this.indentColumns = indentColumns;
            this.text = text != null ? text : "";
            this.description = description != null ? description : "";
            this.code = code != null ? code : "";
            this.precedingComments = precedingComments != null ? List.copyOf(precedingComments) : List.of();
        }

        /**
         * Returns the token category.
         *
         * @return token category
         */
        public TokenType type() {
            return type;
        }

        /**
         * Returns the source file identifier.
         *
         * @return source file identifier
         */
        public String sourceFile() {
            return sourceFile;
        }

        /**
         * Returns the 1-based line number where this token starts.
         *
         * @return 1-based line number
         */
        public int lineNumber() {
            return lineNumber;
        }

        /**
         * Returns the leading indentation column count.
         *
         * @return leading indentation column count
         */
        public int indentColumns() {
            return indentColumns;
        }

        /**
         * Returns the trimmed line text (excluding indentation and trailing comments).
         *
         * @return trimmed line text
         */
        public String text() {
            return text;
        }

        /**
         * Returns the optional Java block description from <code>:: ... @-&gt;&#123;</code>.
         *
         * @return optional Java block description
         */
        public String description() {
            return description;
        }

        /**
         * Returns the verbatim inner Java source for {@link TokenType#JAVA_BLOCK} tokens.
         *
         * @return verbatim inner Java source
         */
        public String code() {
            return code;
        }

        List<String> precedingComments() {
            return precedingComments;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Token other)) {
                return false;
            }
            return lineNumber == other.lineNumber
                    && indentColumns == other.indentColumns
                    && type == other.type
                    && Objects.equals(sourceFile, other.sourceFile)
                    && Objects.equals(text, other.text)
                    && Objects.equals(description, other.description)
                    && Objects.equals(code, other.code);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, sourceFile, lineNumber, indentColumns, text, description, code);
        }

        @Override
        public String toString() {
            return "Token{"
                    + "type=" + type
                    + ", sourceFile='" + sourceFile + '\''
                    + ", lineNumber=" + lineNumber
                    + ", indentColumns=" + indentColumns
                    + ", text='" + text + '\''
                    + (description.isEmpty() ? "" : ", description='" + description + '\'')
                    + (code.isEmpty() ? "" : ", codeLength=" + code.length())
                    + '}';
        }
    }

    private VJavaLexer() {
    }

    /**
     * Reads and tokenizes a {@code .vjava} file from disk using UTF-8 encoding.
     *
     * @param file path to the {@code .vjava} script file
     * @return immutable list of tokens ending with {@link TokenType#EOF}
     * @throws IOException         if reading the file fails
     * @throws VJavaParseException if lexical or indentation rules are violated
     */
    public static List<Token> tokenize(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        return tokenize(content, file.toString().replace('\\', '/'));
    }

    /**
     * Tokenizes raw {@code .vjava} source text into indentation-aware tokens.
     *
     * @param source     raw script source text
     * @param sourceFile logical source file path or name for diagnostics
     * @return immutable list of tokens ending with {@link TokenType#EOF}
     * @throws VJavaParseException if indentation alignment or Java block delimiters are invalid
     */
    public static List<Token> tokenize(String source, String sourceFile) {
        Objects.requireNonNull(source, "source must not be null");
        String effectiveFile = (sourceFile == null || sourceFile.isBlank()) ? "<memory>" : sourceFile;

        String normalized = normalizeSource(source);
        int length = normalized.length();

        List<Token> tokens = new ArrayList<>();
        Deque<Integer> indentStack = new ArrayDeque<>();
        indentStack.push(0);

        List<String> pendingHashComments = new ArrayList<>();
        String pendingDescription = null;
        int pendingDescriptionIndent = -1;
        int pendingDescriptionLine = -1;

        int pos = 0;
        int lineNumber = 1;

        while (pos < length) {
            int lineStart = pos;
            int lineEnd = normalized.indexOf('\n', pos);
            boolean hasNewline = lineEnd >= 0;
            if (!hasNewline) {
                lineEnd = length;
            }

            int indentEnd = lineStart;
            int indentColumns = 0;
            while (indentEnd < lineEnd) {
                char c = normalized.charAt(indentEnd);
                if (c == ' ') {
                    indentColumns++;
                    indentEnd++;
                } else if (c == '\t') {
                    indentColumns += 4;
                    indentEnd++;
                } else {
                    break;
                }
            }

            // Pure blank line -> skip completely
            if (indentEnd == lineEnd) {
                pos = hasNewline ? lineEnd + 1 : length;
                lineNumber++;
                continue;
            }

            String rawContent = normalized.substring(indentEnd, lineEnd);

            // Pure comment lines outside Java blocks
            if (rawContent.startsWith("//")) {
                pos = hasNewline ? lineEnd + 1 : length;
                lineNumber++;
                continue;
            }
            if (rawContent.startsWith("#")) {
                pendingHashComments.add(rawContent.substring(1).trim());
                pos = hasNewline ? lineEnd + 1 : length;
                lineNumber++;
                continue;
            }

            // Two-line :: description followed by @->{
            if (pendingDescription != null) {
                if (rawContent.startsWith(JAVA_BLOCK_OPEN)) {
                    int openOffsetInSource = indentEnd;
                    JavaBlockScanResult result = scanJavaBlock(
                            normalized,
                            openOffsetInSource,
                            pendingDescriptionLine,
                            effectiveFile
                    );
                    emitIndentationChanges(
                            tokens,
                            indentStack,
                            pendingDescriptionIndent,
                            pendingDescriptionLine,
                            effectiveFile,
                            pendingHashComments
                    );
                    tokens.add(new Token(
                            TokenType.JAVA_BLOCK,
                            effectiveFile,
                            pendingDescriptionLine,
                            pendingDescriptionIndent,
                            rawContent,
                            pendingDescription,
                            result.code(),
                            pendingHashComments
                    ));
                    pendingHashComments.clear();
                    pendingDescription = null;
                    pos = result.nextPos();
                    lineNumber = result.nextLineNumber();
                    continue;
                } else {
                    throw new VJavaParseException(
                            effectiveFile,
                            pendingDescriptionLine,
                            "Expected '@->{' after '::' Java block header"
                    );
                }
            }

            // Check if this line starts a native Java block (:: ... @->{ or @->{)
            int javaOpenInRaw = findJavaBlockOpen(rawContent);
            if (javaOpenInRaw >= 0) {
                String prefix = rawContent.substring(0, javaOpenInRaw).trim();
                String description = extractJavaBlockDescription(prefix);
                int openOffsetInSource = indentEnd + javaOpenInRaw;

                JavaBlockScanResult result = scanJavaBlock(
                        normalized,
                        openOffsetInSource,
                        lineNumber,
                        effectiveFile
                );

                emitIndentationChanges(
                        tokens,
                        indentStack,
                        indentColumns,
                        lineNumber,
                        effectiveFile,
                        pendingHashComments
                );
                tokens.add(new Token(
                        TokenType.JAVA_BLOCK,
                        effectiveFile,
                        lineNumber,
                        indentColumns,
                        stripTrailingComments(rawContent),
                        description,
                        result.code(),
                        pendingHashComments
                ));
                pendingHashComments.clear();
                pos = result.nextPos();
                lineNumber = result.nextLineNumber();
                continue;
            }

            // Support ":: Kode für VN: ..." on the line immediately before "@->{"
            if (rawContent.startsWith(KODE_PREFIX)) {
                pendingDescription = extractJavaBlockDescription(stripTrailingComments(rawContent));
                pendingDescriptionIndent = indentColumns;
                pendingDescriptionLine = lineNumber;
                pos = hasNewline ? lineEnd + 1 : length;
                lineNumber++;
                continue;
            }

            String strippedLine = stripTrailingComments(rawContent);
            if (strippedLine.isEmpty()) {
                pos = hasNewline ? lineEnd + 1 : length;
                lineNumber++;
                continue;
            }

            emitIndentationChanges(
                    tokens,
                    indentStack,
                    indentColumns,
                    lineNumber,
                    effectiveFile,
                    pendingHashComments
            );

            tokens.add(new Token(
                    TokenType.LINE,
                    effectiveFile,
                    lineNumber,
                    indentColumns,
                    strippedLine,
                    "",
                    "",
                    pendingHashComments
            ));
            pendingHashComments.clear();

            pos = hasNewline ? lineEnd + 1 : length;
            lineNumber++;
        }

        if (pendingDescription != null) {
            throw new VJavaParseException(
                    effectiveFile,
                    pendingDescriptionLine,
                    "Expected '@->{' after '::' Java block header before end of file"
            );
        }

        // Unwind any remaining open indentation blocks at EOF
        int eofLine = Math.max(1, lineNumber - 1);
        while (indentStack.size() > 1) {
            indentStack.pop();
            tokens.add(new Token(
                    TokenType.DEDENT,
                    effectiveFile,
                    eofLine,
                    indentStack.peek(),
                    "",
                    "",
                    ""
            ));
        }

        tokens.add(new Token(TokenType.EOF, effectiveFile, eofLine, 0, "", "", ""));
        return List.copyOf(tokens);
    }

    private static String normalizeSource(String source) {
        String s = source;
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static void emitIndentationChanges(
            List<Token> tokens,
            Deque<Integer> indentStack,
            int indentColumns,
            int lineNumber,
            String sourceFile,
            List<String> pendingHashComments
    ) {
        int currentIndent = indentStack.peek();
        if (indentColumns > currentIndent) {
            indentStack.push(indentColumns);
            tokens.add(new Token(TokenType.INDENT, sourceFile, lineNumber, indentColumns, "", "", ""));
        } else if (indentColumns < currentIndent) {
            pendingHashComments.clear();
            while (indentStack.size() > 1 && indentStack.peek() > indentColumns) {
                indentStack.pop();
                tokens.add(new Token(TokenType.DEDENT, sourceFile, lineNumber, indentStack.peek(), "", "", ""));
            }
            if (indentStack.peek() != indentColumns) {
                throw new VJavaParseException(
                        sourceFile,
                        lineNumber,
                        "Invalid dedent alignment: expected " + indentStack.peek()
                                + " indentation columns, but found " + indentColumns
                );
            }
        }
    }

    private static int findJavaBlockOpen(String rawContent) {
        if (rawContent.startsWith(JAVA_BLOCK_OPEN)) {
            return 0;
        }
        if (rawContent.startsWith(KODE_PREFIX)) {
            int idx = findMarkerOutsideStrings(rawContent, JAVA_BLOCK_OPEN);
            return idx;
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

    private static String extractJavaBlockDescription(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String trimmed = prefix.trim();
        if (trimmed.startsWith(KODE_PREFIX)) {
            trimmed = trimmed.substring(KODE_PREFIX.length()).trim();
        }
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("kode für vn")) {
            trimmed = trimmed.substring("kode für vn".length()).trim();
            if (trimmed.startsWith(":")) {
                trimmed = trimmed.substring(1).trim();
            }
        } else if (lower.startsWith("kode fuer vn")) {
            trimmed = trimmed.substring("kode fuer vn".length()).trim();
            if (trimmed.startsWith(":")) {
                trimmed = trimmed.substring(1).trim();
            }
        }
        return trimmed;
    }

    private record JavaBlockScanResult(String code, int nextPos, int nextLineNumber) {
    }

    private enum JavaLexState {
        NORMAL,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        TEXT_BLOCK,
        CHAR
    }

    private static JavaBlockScanResult scanJavaBlock(
            String source,
            int openMarkerPos,
            int startLine,
            String sourceFile
    ) {
        int codeStart = openMarkerPos + JAVA_BLOCK_OPEN.length();
        int len = source.length();
        int pos = codeStart;
        int currentLine = startLine;
        JavaLexState state = JavaLexState.NORMAL;
        boolean escaped = false;
        int closeMarkerPos = -1;

        while (pos < len) {
            char c = source.charAt(pos);
            switch (state) {
                case NORMAL -> {
                    if (source.startsWith(JAVA_BLOCK_CLOSE, pos)) {
                        closeMarkerPos = pos;
                        pos += JAVA_BLOCK_CLOSE.length();
                    } else if (c == '/' && pos + 1 < len && source.charAt(pos + 1) == '/') {
                        state = JavaLexState.LINE_COMMENT;
                        pos += 2;
                        continue;
                    } else if (c == '/' && pos + 1 < len && source.charAt(pos + 1) == '*') {
                        state = JavaLexState.BLOCK_COMMENT;
                        pos += 2;
                        continue;
                    } else if (source.startsWith("\"\"\"", pos)) {
                        state = JavaLexState.TEXT_BLOCK;
                        pos += 3;
                        continue;
                    } else if (c == '"') {
                        state = JavaLexState.STRING;
                        pos++;
                        continue;
                    } else if (c == '\'') {
                        state = JavaLexState.CHAR;
                        pos++;
                        continue;
                    } else {
                        if (c == '\n') {
                            currentLine++;
                        }
                        pos++;
                        continue;
                    }
                }
                case LINE_COMMENT -> {
                    if (c == '\n') {
                        currentLine++;
                        state = JavaLexState.NORMAL;
                    }
                    pos++;
                    continue;
                }
                case BLOCK_COMMENT -> {
                    if (c == '*' && pos + 1 < len && source.charAt(pos + 1) == '/') {
                        state = JavaLexState.NORMAL;
                        pos += 2;
                        continue;
                    }
                    if (c == '\n') {
                        currentLine++;
                    }
                    pos++;
                    continue;
                }
                case STRING -> {
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        state = JavaLexState.NORMAL;
                    } else if (c == '\n') {
                        currentLine++;
                        state = JavaLexState.NORMAL;
                    }
                    pos++;
                    continue;
                }
                case TEXT_BLOCK -> {
                    if (escaped) {
                        escaped = false;
                        if (c == '\n') {
                            currentLine++;
                        }
                        pos++;
                        continue;
                    }
                    if (c == '\\') {
                        escaped = true;
                        pos++;
                        continue;
                    }
                    if (source.startsWith("\"\"\"", pos)) {
                        state = JavaLexState.NORMAL;
                        pos += 3;
                        continue;
                    }
                    if (c == '\n') {
                        currentLine++;
                    }
                    pos++;
                    continue;
                }
                case CHAR -> {
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '\'') {
                        state = JavaLexState.NORMAL;
                    } else if (c == '\n') {
                        currentLine++;
                        state = JavaLexState.NORMAL;
                    }
                    pos++;
                    continue;
                }
            }
            if (closeMarkerPos >= 0) {
                break;
            }
        }

        if (closeMarkerPos < 0) {
            throw new VJavaParseException(
                    sourceFile,
                    startLine,
                    "Unterminated native Java block '@->{' (missing closing '}<<')"
            );
        }

        // Validate trailing content on the line containing '}<<'
        int lineEnd = source.indexOf('\n', pos);
        boolean hasNewline = lineEnd >= 0;
        if (!hasNewline) {
            lineEnd = len;
        }
        String trailing = source.substring(pos, lineEnd).trim();
        if (!trailing.isEmpty() && !trailing.startsWith("//") && !trailing.startsWith("#")) {
            throw new VJavaParseException(
                    sourceFile,
                    currentLine,
                    "Unexpected content after '}<<': " + trailing
            );
        }

        String rawCode = extractInnerJavaCode(source, codeStart, closeMarkerPos);
        int nextPos = hasNewline ? lineEnd + 1 : len;
        int nextLine = hasNewline ? currentLine + 1 : currentLine;
        return new JavaBlockScanResult(rawCode, nextPos, nextLine);
    }

    private static String extractInnerJavaCode(String source, int start, int end) {
        if (end <= start) {
            return "";
        }
        boolean hasNewline = false;
        for (int i = start; i < end; i++) {
            if (source.charAt(i) == '\n') {
                hasNewline = true;
                break;
            }
        }
        if (!hasNewline) {
            return source.substring(start, end).strip();
        }

        int s = start;
        int e = end;

        // If '@->{' is followed immediately by horizontal spaces and a newline, advance past that newline
        int firstBreak = s;
        while (firstBreak < e && (source.charAt(firstBreak) == ' ' || source.charAt(firstBreak) == '\t')) {
            firstBreak++;
        }
        if (firstBreak < e && source.charAt(firstBreak) == '\n') {
            s = firstBreak + 1;
        }

        // If '}<<' is preceded on its line only by horizontal whitespace, trim that whitespace and its newline
        int lastLineStart = e;
        while (lastLineStart > s
                && (source.charAt(lastLineStart - 1) == ' ' || source.charAt(lastLineStart - 1) == '\t')) {
            lastLineStart--;
        }
        if (lastLineStart > s && source.charAt(lastLineStart - 1) == '\n') {
            e = lastLineStart - 1;
        }

        if (e <= s) {
            return "";
        }
        return source.substring(s, e);
    }

    private static String stripTrailingComments(String rawLine) {
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        boolean escaped = false;
        int len = rawLine.length();
        int cutIndex = len;

        for (int i = 0; i < len; i++) {
            char c = rawLine.charAt(i);
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
            } else if (c == '\'') {
                inSingleQuote = true;
            } else if (c == '#') {
                cutIndex = i;
                break;
            } else if (c == '/' && i + 1 < len && rawLine.charAt(i + 1) == '/') {
                cutIndex = i;
                break;
            }
        }
        return rawLine.substring(0, cutIndex).trim();
    }
}
