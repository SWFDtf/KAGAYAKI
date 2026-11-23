package de.swfdtf.parser;

import java.util.List;
import java.util.Objects;

/**
 * Exception thrown when compiling embedded {@code @->{ ... }<<} Java blocks fails.
 *
 * <p>Carries the originating {@code .vjava} block identifier, source file path, 1-based line number,
 * and structured {@code javac} diagnostic messages for editor error highlighting and Studio console
 * reporting.</p>
 */
public class VJavaCompileException extends RuntimeException {

    /**
     * Structured diagnostic entry captured from {@code javax.tools.DiagnosticCollector}.
     *
     * @param blockId      originating {@link VJavaAst.JavaBlockNode#id()} (empty if global)
     * @param sourceFile   originating {@code .vjava} file path
     * @param lineNumber   mapped 1-based line number in the {@code .vjava} script (or generated line if unmapped)
     * @param columnNumber 1-based column number reported by {@code javac} ({@code -1} if unavailable)
     * @param kind         diagnostic severity/kind (e.g., {@code "ERROR"})
     * @param message      human-readable compiler diagnostic message
     */
    public record CompileDiagnostic(
            String blockId,
            String sourceFile,
            int lineNumber,
            long columnNumber,
            String kind,
            String message
    ) {
        public CompileDiagnostic {
            blockId = blockId != null ? blockId : "";
            sourceFile = sourceFile != null ? sourceFile : "";
            kind = kind != null ? kind : "ERROR";
            message = message != null ? message : "";
        }

        /**
         * Formats this diagnostic as a concise single-line summary.
         *
         * @return formatted diagnostic text
         */
        public String format() {
            String location = sourceFile.isBlank() ? "<memory>" : sourceFile;
            if (lineNumber > 0) {
                location += ":" + lineNumber;
                if (columnNumber > 0) {
                    location += ":" + columnNumber;
                }
            }
            String prefix = blockId.isBlank() ? "" : "[" + blockId + "] ";
            return prefix + location + " (" + kind + "): " + message;
        }
    }

    private final String blockId;
    private final String sourceFile;
    private final int lineNumber;
    private final List<String> diagnosticMessages;
    private final List<CompileDiagnostic> diagnostics;
    private final String generatedSource;

    /**
     * Constructs a {@code VJavaCompileException} with a summary message.
     *
     * @param message failure description
     */
    public VJavaCompileException(String message) {
        this(message, "", "", -1, List.of(), "", null);
    }

    /**
     * Constructs a {@code VJavaCompileException} wrapping an underlying cause.
     *
     * @param message failure description
     * @param cause   root cause throwable
     */
    public VJavaCompileException(String message, Throwable cause) {
        this(message, "", "", -1, List.of(), "", cause);
    }

    /**
     * Constructs a {@code VJavaCompileException} for a specific block and list of diagnostic messages.
     *
     * @param blockId            originating Java block ID
     * @param sourceFile         originating {@code .vjava} source path
     * @param lineNumber         originating 1-based line number
     * @param diagnosticMessages compiler error messages
     */
    public VJavaCompileException(
            String blockId,
            String sourceFile,
            int lineNumber,
            List<String> diagnosticMessages
    ) {
        this(
                buildDefaultMessage(blockId, sourceFile, lineNumber, diagnosticMessages),
                blockId,
                sourceFile,
                lineNumber,
                toCompileDiagnostics(blockId, sourceFile, lineNumber, diagnosticMessages),
                "",
                null
        );
    }

    /**
     * Constructs a fully detailed {@code VJavaCompileException}.
     *
     * @param message         overall failure message
     * @param blockId         primary failing block ID
     * @param sourceFile      primary failing source file path
     * @param lineNumber      primary failing 1-based line number
     * @param diagnostics     structured compiler diagnostics
     * @param generatedSource generated Java compilation unit text (if available)
     * @param cause           optional underlying cause
     */
    public VJavaCompileException(
            String message,
            String blockId,
            String sourceFile,
            int lineNumber,
            List<CompileDiagnostic> diagnostics,
            String generatedSource,
            Throwable cause
    ) {
        super(Objects.requireNonNullElse(message, "VJava compilation failed"), cause);
        this.blockId = blockId != null ? blockId : "";
        this.sourceFile = sourceFile != null ? sourceFile : "";
        this.lineNumber = lineNumber;
        this.diagnostics = diagnostics != null ? List.copyOf(diagnostics) : List.of();
        this.diagnosticMessages = this.diagnostics.stream()
                .map(CompileDiagnostic::message)
                .toList();
        this.generatedSource = generatedSource != null ? generatedSource : "";
    }

    /**
     * Returns the primary failing {@link VJavaAst.JavaBlockNode#id()}, or an empty string if unavailable.
     *
     * @return block identifier
     */
    public String getBlockId() {
        return blockId;
    }

    /**
     * Returns the originating {@code .vjava} source file path, or an empty string if unavailable.
     *
     * @return source file path
     */
    public String getSourceFile() {
        return sourceFile;
    }

    /**
     * Returns the mapped 1-based {@code .vjava} line number, or {@code -1} if unavailable.
     *
     * @return 1-based line number or {@code -1}
     */
    public int getLineNumber() {
        return lineNumber;
    }

    /**
     * Returns immutable raw {@code javac} diagnostic message strings.
     *
     * @return list of diagnostic messages
     */
    public List<String> getDiagnosticMessages() {
        return diagnosticMessages;
    }

    /**
     * Returns immutable structured {@link CompileDiagnostic} records for all reported issues.
     *
     * @return list of structured diagnostics
     */
    public List<CompileDiagnostic> getDiagnostics() {
        return diagnostics;
    }

    /**
     * Returns the generated Java source unit that triggered the failure, or an empty string if omitted.
     *
     * @return generated Java source code
     */
    public String getGeneratedSource() {
        return generatedSource;
    }

    private static List<CompileDiagnostic> toCompileDiagnostics(
            String blockId,
            String sourceFile,
            int lineNumber,
            List<String> messages
    ) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .map(msg -> new CompileDiagnostic(blockId, sourceFile, lineNumber, -1L, "ERROR", msg))
                .toList();
    }

    private static String buildDefaultMessage(
            String blockId,
            String sourceFile,
            int lineNumber,
            List<String> messages
    ) {
        StringBuilder sb = new StringBuilder("Failed to compile VJava block");
        if (blockId != null && !blockId.isBlank()) {
            sb.append(" '").append(blockId).append('\'');
        }
        if (sourceFile != null && !sourceFile.isBlank()) {
            sb.append(" at ").append(sourceFile);
            if (lineNumber > 0) {
                sb.append(':').append(lineNumber);
            }
        } else if (lineNumber > 0) {
            sb.append(" at line ").append(lineNumber);
        }
        if (messages != null && !messages.isEmpty()) {
            sb.append(": ").append(String.join("; ", messages));
        }
        return sb.toString();
    }
}
