package de.swfdtf.e2e.tier2;

import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.e2e.support.E2eWorkspaceSupport;
import de.swfdtf.parser.ConditionEvaluator;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.VJavaLexer;
import de.swfdtf.parser.VJavaParseException;
import de.swfdtf.parser.VJavaParser;
import de.swfdtf.state.SimplePersistentAccess;
import de.swfdtf.state.SimpleStateStore;
import de.swfdtf.tools.VJavaLinter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 2 boundary, security, and negative E2E tests for Feature Area F1: {@code .vjava}
 * type-checking errors, malformed scripts, 5,000-script parser fuzzing, boundary evaluation,
 * and reflection sandbox exploit blocking.
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER2)
public final class F01ScriptingBoundaryE2eTest {

    /** Creates a new {@code F01ScriptingBoundaryE2eTest} instance. */
    public F01ScriptingBoundaryE2eTest() {
    }

    private static final int FUZZ_ITERATIONS = 5_000;
    private static final long FUZZ_BUDGET_MILLIS = 2_000L;

    /**
     * Verifies goal {@code #0032}: adding boolean + asset path string triggers a clear
     * {@link VJavaLinter} type-mismatch diagnostic with file and line context.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void strictTypeCheckRejectsBooleanPlusPath(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f01_type_mismatch");
        E2eWorkspaceSupport.copyResourceTree("e2e/f01/type_mismatch", projectDir);

        VJavaLinter.LintReport report = E2eCliRunner.lintProject(projectDir);
        assertFalse(report.issues().isEmpty(), "Expected lint diagnostic for boolean + path addition");
        assertTrue(
                report.hasErrors() || report.issues().stream()
                        .anyMatch(i -> i.code().contains("TYPE") || i.message().toLowerCase().contains("type")),
                "Expected clear type diagnostic, got: " + report.issues()
        );
        assertTrue(
                report.issues().stream().anyMatch(i -> i.sourceFile().contains("main.vjava") && i.lineNumber() > 0),
                "Expected sourceFile and positive lineNumber on diagnostic: " + report.issues()
        );
    }

    /**
     * Verifies goal {@code #0033}: non-exhaustive or duplicate {@code match} arms emit a
     * deterministic parse or lint diagnostic rather than silently failing.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void matchStatementMissingDefaultOrDuplicateCaseHandledSafely(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f01_match_invalid");
        E2eWorkspaceSupport.copyResourceTree("e2e/f01/match_invalid", projectDir);

        VJavaLinter.LintReport report = E2eCliRunner.lintProject(projectDir);
        assertFalse(report.isClean(), "Expected diagnostic for non-exhaustive / duplicate match block");
        assertFalse(
                report.issues().stream().anyMatch(i -> i.message().contains("Unknown statement")),
                "match syntax must be recognized by parser/linter, got: " + report.issues()
        );
        assertTrue(
                report.issues().stream().anyMatch(
                        i -> i.lineNumber() > 0 && isMatchExhaustiveOrDuplicateDiagnostic(i)
                ),
                "Expected duplicate-case or missing-default match diagnostic, got: " + report.issues()
        );

        // Isolate missing-default (unique cases, no default:) to ensure exhaustiveness is checked.
        Path missingDefaultDir = tempDir.resolve("f01_match_no_default");
        E2eWorkspaceSupport.createEmptyProject(missingDefaultDir, "Match Missing Default", "2D");
        E2eWorkspaceSupport.writeScript(missingDefaultDir, "main.vjava", """
                default route = "west"

                label start:
                    match route:
                        case "east":
                            "Osten."
                        case "west":
                            "Westen."
                    return
                """);
        VJavaLinter.LintReport missingDefaultReport = E2eCliRunner.lintProject(missingDefaultDir);
        assertFalse(missingDefaultReport.isClean(), "Non-exhaustive match without default must emit diagnostic");
        assertFalse(
                missingDefaultReport.issues().stream().anyMatch(i -> i.message().contains("Unknown statement")),
                "match syntax must be recognized, got: " + missingDefaultReport.issues()
        );
        assertTrue(
                missingDefaultReport.issues().stream().anyMatch(
                        i -> i.lineNumber() > 0 && isMatchExhaustiveOrDuplicateDiagnostic(i)
                ),
                "Expected missing-default / exhaustiveness diagnostic, got: " + missingDefaultReport.issues()
        );

        Path missingColonDir = tempDir.resolve("f01_match_nocolon");
        E2eWorkspaceSupport.createEmptyProject(missingColonDir, "Match Syntax Error", "2D");
        E2eWorkspaceSupport.writeScript(missingColonDir, "main.vjava", """
                label start:
                    match mood
                        case "happy":
                            "Hi"
                    return
                """);
        VJavaParseException parseEx = assertThrows(
                VJavaParseException.class,
                () -> ProjectScanner.scan(missingColonDir)
        );
        assertFalse(
                parseEx.getMessage().contains("Unknown statement"),
                "Expected match header ':' syntax error, not Unknown statement: " + parseEx.getMessage()
        );
        assertTrue(
                parseEx.getMessage().contains(":")
                        || parseEx.getMessage().toLowerCase(Locale.ROOT).contains("match"),
                "Expected ':' or 'match' in parse error, got: " + parseEx.getMessage()
        );
    }

    private static boolean isMatchExhaustiveOrDuplicateDiagnostic(VJavaLinter.LintIssue issue) {
        String code = issue.code().toUpperCase(Locale.ROOT);
        String msg = issue.message().toLowerCase(Locale.ROOT);
        if (msg.contains("unknown statement")) {
            return false;
        }
        return code.contains("MATCH")
                || code.contains("CASE")
                || code.contains("DEFAULT")
                || msg.contains("default")
                || msg.contains("duplicate")
                || msg.contains("exhaust");
    }

    /**
     * Verifies goal {@code #0048}: format specifiers handle missing variables, {@code null}
     * values, non-numeric strings, and invalid format patterns without uncaught exceptions,
     * while accurately formatting valid numeric controls.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void formatSpecifierBoundaryHandlesNullUnknownAndInvalidPatterns(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f01_format_boundary");
        E2eWorkspaceSupport.copyResourceTree("e2e/f01/format_boundary", projectDir);

        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(projectDir));
        runtime.start();

        // Positive control inside boundary test: guarantees failure when #0048 is unimplemented.
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        assertEquals("Valid: 1234.50", runtime.getActiveDialogue().interpolatedText());

        runtime.advance();
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        assertEquals("Missing: [missing:.2f]", runtime.getActiveDialogue().interpolatedText());

        runtime.advance();
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        String nullOut = runtime.getActiveDialogue().interpolatedText();
        assertTrue(
                "Null: [null_var:.2f]".equals(nullOut)
                        || "Null: null".equals(nullOut)
                        || "Null: ".equals(nullOut),
                "Expected safe null formatting fallback, got: " + nullOut
        );

        runtime.advance();
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        String textOut = runtime.getActiveDialogue().interpolatedText();
        assertTrue(
                "Text: nicht_numerisch".equals(textOut)
                        || "Text: [text_var:.2f]".equals(textOut),
                "Expected raw value or placeholder fallback for non-numeric .2f, got: " + textOut
        );

        runtime.advance();
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        String badSpecOut = runtime.getActiveDialogue().interpolatedText();
        assertTrue(
                "BadSpec: 1234.5".equals(badSpecOut)
                        || "BadSpec: [gold:invalid_spec]".equals(badSpecOut),
                "Expected raw value or placeholder fallback for invalid specifier, got: " + badSpecOut
        );

        runtime.advance();
        assertEquals(ScriptRuntime.Status.ENDED, runtime.getStatus());
    }

    /**
     * Verifies goal {@code #0938}: 5,000 deterministic seeded mutated/truncated scripts run
     * through {@link VJavaLexer} and {@link VJavaParser} in under 2 seconds total with only
     * structured {@link VJavaParseException} failures.
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void lexerAndParserFuzzing5000MutatedScriptsUnder2Seconds() {
        List<String> seeds = List.of(
                "label start:\n    \"Hello\"\n    return\n",
                "label start:\n    if true:\n        $ x = 1 + 2\n    return\n",
                "label start:\n    :: block @->{\n        state.set(\"a\", 1);\n    }<<\n    return\n",
                "menu \"Pick\":\n    \"A\" -> jump a\n    \"B\" -> jump b\n",
                "transform drift:\n    xpos 0.0\n    linear 1.0 xpos 200.0\n    repeat\n"
        );
        Random rng = new Random(0xC0FFEEL);
        long startNanos = System.nanoTime();

        for (int i = 0; i < FUZZ_ITERATIONS; i++) {
            String mutated = mutateScript(seeds.get(i % seeds.size()), rng, i);
            try {
                List<VJavaLexer.Token> tokens = VJavaLexer.tokenize(mutated, "fuzz_" + i + ".vjava");
                VJavaParser.parse(Path.of("fuzz_" + i + ".vjava"), tokens);
            } catch (VJavaParseException expected) {
                assertNotNull(expected.getSourceFile());
            }
        }

        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        assertTrue(
                elapsedMillis < FUZZ_BUDGET_MILLIS,
                "5,000-script fuzzing exceeded 2s budget: " + elapsedMillis + " ms"
        );
    }

    private static String mutateScript(String base, Random rng, int index) {
        int mode = index % 5;
        return switch (mode) {
            case 0 -> base.substring(0, Math.max(1, rng.nextInt(base.length() + 1)));
            case 1 -> " ".repeat(rng.nextInt(17)) + base.replace("\n", "\n" + " ".repeat(rng.nextInt(9)));
            case 2 -> base.replace("}<<", "").replace("\"", rng.nextBoolean() ? "\"" : "'");
            case 3 -> "label deep:\n" + "    if true:\n".repeat(120) + "        \"leaf\"\n";
            default -> {
                StringBuilder sb = new StringBuilder(base.length() + 32);
                for (int i = 0; i < base.length(); i++) {
                    char c = base.charAt(i);
                    if (rng.nextInt(6) == 0) {
                        sb.append((char) (33 + rng.nextInt(90)));
                    } else {
                        sb.append(c);
                    }
                }
                yield sb.toString();
            }
        };
    }

    /**
     * Verifies goal {@code #0938} baseline: unclosed {@code @->{} Java block produces a
     * structured {@link VJavaParseException} and {@code PARSE_ERROR} lint diagnostic with file
     * and positive line number.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void malformedJavaBlockAndBadIndentationProduceStructuredParseErrors(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f01_malformed");
        E2eWorkspaceSupport.copyResourceTree("e2e/f01/malformed_baseline", projectDir);

        VJavaParseException parseEx = assertThrows(
                VJavaParseException.class,
                () -> ProjectScanner.scan(projectDir)
        );
        assertTrue(parseEx.getSourceFile().contains("main.vjava"));
        assertTrue(parseEx.getLineNumber() > 0);

        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertTrue(lint.hasErrors());
        assertEquals("PARSE_ERROR", lint.issues().get(0).code());
        assertTrue(lint.issues().get(0).lineNumber() > 0);
    }

    /**
     * Verifies baseline {@code JAVA_COMPILE_ERROR} diagnostic reports exact source file and line
     * for broken Java code inside {@code @->{ ... }<<}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void javaBlockCompileErrorReportsExactSourceFileAndLine(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f01_broken_java");
        E2eWorkspaceSupport.copyResourceTree("e2e/f01/broken_javablock", projectDir);

        VJavaLinter.LintReport report = E2eCliRunner.lintProject(projectDir);
        assertTrue(report.hasErrors(), "Expected JAVA_COMPILE_ERROR for invalid Java assignment");
        assertTrue(
                report.issues().stream().anyMatch(
                        i -> "JAVA_COMPILE_ERROR".equals(i.code())
                                && i.sourceFile().contains("main.vjava")
                                && i.lineNumber() > 0
                ),
                "Expected JAVA_COMPILE_ERROR with file and line, got: " + report.issues()
        );
    }

    /**
     * Verifies goal {@code #0939}: {@link ConditionEvaluator} handles division/modulo by zero,
     * {@code null} operands, and empty string boundaries without uncaught {@link NullPointerException}
     * or {@link ClassCastException}.
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void conditionEvaluatorNullOverflowAndZeroDivisionBoundaries() {
        SimpleStateStore state = new SimpleStateStore();
        SimplePersistentAccess persistent = new SimplePersistentAccess();
        state.set("empty_str", "");
        state.set("big_int", Integer.MAX_VALUE);

        assertThrows(ArithmeticException.class, () -> ConditionEvaluator.evaluate("10 / 0", state, persistent));
        assertThrows(ArithmeticException.class, () -> ConditionEvaluator.evaluate("10 % 0", state, persistent));
        assertEquals(Boolean.TRUE, ConditionEvaluator.evaluate("null == null", state, persistent));
        assertEquals(Boolean.FALSE, ConditionEvaluator.evaluate("null && true", state, persistent));
        assertFalse(ConditionEvaluator.evaluateBoolean("empty_str", state, persistent));
        assertEquals(0, ConditionEvaluator.evaluate("empty_str.length()", state, persistent));
        assertTrue(ConditionEvaluator.evaluateBoolean("big_int > 0", state, persistent));
    }

    /**
     * Verifies goal {@code #0945}: {@link ConditionEvaluator} and {@link ScriptRuntime} sandbox
     * strictly reject reflection escape payloads targeting {@code Class.forName}, {@code ClassLoader},
     * {@code Runtime}, {@code ProcessBuilder}, and {@code System.exit}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void conditionEvaluatorSandboxBlocksReflectionExploits(@TempDir Path tempDir) throws Exception {
        SimpleStateStore state = new SimpleStateStore();
        SimplePersistentAccess persistent = new SimplePersistentAccess();

        // Safe expressions must continue to evaluate normally.
        assertEquals(3, ConditionEvaluator.evaluate("\"abc\".length()", state, persistent));
        assertEquals(3, ConditionEvaluator.evaluate("1 + 2", state, persistent));

        // Probes that SUCCEED on unsandboxed ConditionEvaluator (must be blocked by #0945).
        state.set("clsRef", Class.class);
        List<String> directReflectionProbes = List.of(
                "\"\".getClass()",
                "state.getClass()",
                "clsRef.forName(\"java.lang.String\")"
        );
        for (String payload : directReflectionProbes) {
            RuntimeException ex = assertThrows(
                    RuntimeException.class,
                    () -> ConditionEvaluator.evaluate(payload, state, persistent),
                    "Sandbox must reject reflection probe: " + payload
            );
            assertSandboxRejection(ex, payload);
        }

        // Chained reflection payloads must fail via sandbox rejection, NOT accidental
        // "No matching method" / "Cannot access member on null" baseline errors.
        List<String> chainedPayloads = List.of(
                "\"\".getClass().getName()",
                "\"\".getClass().getClassLoader()",
                "\"\".getClass().forName(\"java.lang.Runtime\")"
        );
        for (String payload : chainedPayloads) {
            RuntimeException ex = assertThrows(
                    RuntimeException.class,
                    () -> ConditionEvaluator.evaluate(payload, state, persistent),
                    "Sandbox must reject chained payload: " + payload
            );
            assertSandboxRejection(ex, payload);
        }

        Path projectDir = tempDir.resolve("f01_sandbox_escape");
        E2eWorkspaceSupport.copyResourceTree("e2e/f01/sandbox_escape", projectDir);
        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(projectDir));
        RuntimeException scriptEx = assertThrows(RuntimeException.class, runtime::start);
        assertSandboxRejection(scriptEx, "ScriptRuntime $ exploit = \"\".getClass()");
    }

    private static void assertSandboxRejection(RuntimeException ex, String payload) {
        if (ex instanceof SecurityException) {
            return;
        }
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase(Locale.ROOT) : "";
        assertFalse(
                msg.contains("no matching method") || msg.contains("cannot access member"),
                "Payload '" + payload + "' failed accidentally rather than via sandbox: " + ex.getMessage()
        );
        boolean sandboxMentioned = msg.contains("sandbox")
                || msg.contains("block")
                || msg.contains("forbidden")
                || msg.contains("denied")
                || msg.contains("disallow")
                || msg.contains("not allowed")
                || msg.contains("restricted")
                || msg.contains("security")
                || msg.contains("reflection")
                || msg.contains("getclass");
        assertTrue(
                sandboxMentioned,
                "Expected SecurityException or sandbox rejection message for '"
                        + payload + "', got: " + ex.getClass().getSimpleName() + ": " + ex.getMessage()
        );
    }
}
