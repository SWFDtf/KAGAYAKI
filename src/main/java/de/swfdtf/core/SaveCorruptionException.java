package de.swfdtf.core;

/**
 * Unchecked engine exception signaling corrupted, truncated, or schema-incompatible save slot data
 * ({@code #0974}).
 */
public class SaveCorruptionException extends KagayakiException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a {@code SaveCorruptionException} with descriptive error details.
     *
     * @param message human-readable save corruption message
     */
    public SaveCorruptionException(String message) {
        super("SAVE_CORRUPTION", message);
    }

    /**
     * Creates a {@code SaveCorruptionException} wrapping a lower-level parsing or I/O cause.
     *
     * @param message human-readable save corruption message
     * @param cause   underlying root cause
     */
    public SaveCorruptionException(String message, Throwable cause) {
        super("SAVE_CORRUPTION", message, cause);
    }
}
