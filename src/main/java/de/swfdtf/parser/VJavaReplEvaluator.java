package de.swfdtf.parser;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Interactive single-line REPL evaluator for KAGAYAKI {@code .vjava} commands during debug or
 * paused preview sessions (e.g. {@code move lena to 500 500} or {@code $gold += 10}).
 */
public final class VJavaReplEvaluator {

    /**
     * Outcome of evaluating a single REPL line against a {@link ScriptRuntime}.
     *
     * @param success whether parsing and execution succeeded
     * @param message diagnostic or summary message
     * @param value   evaluated expression result if applicable (may be {@code null})
     */
    public record ReplResult(boolean success, String message, Object value) {
    }

    private VJavaReplEvaluator() {
    }

    /**
     * Parses and executes a single {@code .vjava} statement line (or {@code ? <expr>} evaluation)
     * immediately against the active {@code runtime} without disrupting the current script label.
     *
     * @param line    single {@code .vjava} command or {@code ? <expr>} query
     * @param runtime active runtime target
     * @return structured {@link ReplResult}
     */
    public static ReplResult evaluateLine(String line, ScriptRuntime runtime) {
        Objects.requireNonNull(line, "line must not be null");
        Objects.requireNonNull(runtime, "runtime must not be null");
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return new ReplResult(true, "No-op (empty line)", null);
        }
        if (trimmed.startsWith("?")) {
            String expr = trimmed.substring(1).trim();
            try {
                Object val = ConditionEvaluator.evaluate(expr, runtime.getStateStore(), runtime.getPersistentAccess());
                return new ReplResult(true, String.valueOf(val), val);
            } catch (RuntimeException ex) {
                return new ReplResult(false, ex.getMessage(), null);
            }
        }
        String wrapped = "label __repl_eval__:\n    " + trimmed + "\n";
        try {
            VJavaAst.ScriptFileAst parsed = VJavaParser.parse(Path.of("<repl>"), wrapped);
            if (parsed.labels().isEmpty()) {
                return new ReplResult(false, "REPL produced no statements", null);
            }
            runtime.executeReplStatements(parsed.labels().get(0).statements());
            return new ReplResult(true, "Executed: " + trimmed, null);
        } catch (RuntimeException ex) {
            return new ReplResult(false, ex.getMessage(), null);
        }
    }
}
