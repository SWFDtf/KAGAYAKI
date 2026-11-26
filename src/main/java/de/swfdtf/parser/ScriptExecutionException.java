package de.swfdtf.parser;

/**
 * Unchecked runtime exception thrown when a {@code .vjava} script violates an execution
 * guard (such as {@code while} loop iteration limits or recursive call depth bounds).
 */
public class ScriptExecutionException extends RuntimeException {

    /**
     * Constructs a new {@code ScriptExecutionException} with the given message.
     *
     * @param message descriptive runtime error message
     */
    public ScriptExecutionException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code ScriptExecutionException} with message and cause.
     *
     * @param message descriptive runtime error message
     * @param cause   underlying exception cause
     */
    public ScriptExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
