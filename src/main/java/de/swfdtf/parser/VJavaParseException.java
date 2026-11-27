package de.swfdtf.parser;

import de.swfdtf.core.KagayakiException;

/**
 * Unchecked exception thrown when lexical analysis, indentation validation,
 * or syntax parsing of a {@code .vjava} script fails.
 */
public class VJavaParseException extends KagayakiException {

    private static final long serialVersionUID = 1L;

    /**
     * Source file path where parsing failed.
     *
     * @serial
     */
    private final String sourceFile;

    /**
     * 1-based line number where parsing failed.
     *
     * @serial
     */
    private final int lineNumber;

    /**
     * Creates a parse exception with source location context.
     *
     * @param sourceFile path or identifier of the {@code .vjava} source file
     * @param lineNumber 1-based line number where the error occurred
     * @param message    human-readable diagnostic description
     */
    public VJavaParseException(String sourceFile, int lineNumber, String message) {
        super("VJAVA_PARSE_ERROR", formatMessage(sourceFile, lineNumber, message));
        this.sourceFile = normalizeSourceFile(sourceFile);
        this.lineNumber = lineNumber;
    }

    /**
     * Creates a parse exception with source location context and an underlying cause.
     *
     * @param sourceFile path or identifier of the {@code .vjava} source file
     * @param lineNumber 1-based line number where the error occurred
     * @param message    human-readable diagnostic description
     * @param cause      underlying cause
     */
    public VJavaParseException(String sourceFile, int lineNumber, String message, Throwable cause) {
        super("VJAVA_PARSE_ERROR", formatMessage(sourceFile, lineNumber, message), cause);
        this.sourceFile = normalizeSourceFile(sourceFile);
        this.lineNumber = lineNumber;
    }

    /**
     * Formats the parse error together with the source snippet line and a {@code ^} column pointer
     * ({@code #0988}).
     *
     * @param sourceLine source text of the failing line (nullable)
     * @param column     1-based column position on the line ({@code >= 1})
     * @return multi-line diagnostic with file, line, source snippet, and {@code ^} pointer
     */
    public String formatDiagnosticWithCaret(String sourceLine, int column) {
        String safeLine = sourceLine != null ? sourceLine : "";
        int clampedCol = Math.max(1, column);
        String pointer = " ".repeat(clampedCol - 1) + "^";
        return getMessage() + "\n  " + safeLine + "\n  " + pointer;
    }

    /**
     * Returns the source file name or relative path associated with this error.
     *
     * @return source file identifier (never {@code null})
     */
    public String getSourceFile() {
        return sourceFile;
    }

    /**
     * Returns the 1-based line number where the error was detected.
     *
     * @return 1-based line number (or {@code <= 0} if unknown)
     */
    public int getLineNumber() {
        return lineNumber;
    }

    private static String normalizeSourceFile(String sourceFile) {
        return (sourceFile == null || sourceFile.isBlank()) ? "<unknown>" : sourceFile;
    }

    private static String formatMessage(String sourceFile, int lineNumber, String message) {
        String file = normalizeSourceFile(sourceFile);
        String msg = (message == null || message.isBlank()) ? "Syntax error" : message;
        return lineNumber > 0
                ? "[" + file + ":" + lineNumber + "] " + msg
                : "[" + file + "] " + msg;
    }
}
