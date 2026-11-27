package de.swfdtf.core;

/**
 * Common unchecked base exception hierarchy for KAGAYAKI engine subsystems ({@code #0974}).
 *
 * <p>Allows UI crash boundaries and tooling diagnostics to distinguish structured engine failures
 * (script syntax, Java block compilation, save corruption, and driver initialization) from
 * unexpected internal bugs.</p>
 */
public class KagayakiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Machine-readable error category code.
     *
     * @serial
     */
    private final String errorCode;

    /**
     * Creates a {@code KagayakiException} with default error code {@code "ENGINE_ERROR"}.
     *
     * @param message human-readable error message
     */
    public KagayakiException(String message) {
        this("ENGINE_ERROR", message, null);
    }

    /**
     * Creates a {@code KagayakiException} wrapping an underlying cause.
     *
     * @param message human-readable error message
     * @param cause   root cause throwable (nullable)
     */
    public KagayakiException(String message, Throwable cause) {
        this("ENGINE_ERROR", message, cause);
    }

    /**
     * Creates a {@code KagayakiException} with an explicit machine-readable error code.
     *
     * @param errorCode stable error code identifier
     * @param message   human-readable error message
     */
    public KagayakiException(String errorCode, String message) {
        this(errorCode, message, null);
    }

    /**
     * Creates a {@code KagayakiException} with an explicit error code and underlying cause.
     *
     * @param errorCode stable error code identifier
     * @param message   human-readable error message
     * @param cause     root cause throwable (nullable)
     */
    public KagayakiException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = (errorCode == null || errorCode.isBlank()) ? "ENGINE_ERROR" : errorCode;
    }

    /**
     * Returns the stable machine-readable error category code.
     *
     * @return non-blank error code string
     */
    public String getErrorCode() {
        return errorCode;
    }
}
