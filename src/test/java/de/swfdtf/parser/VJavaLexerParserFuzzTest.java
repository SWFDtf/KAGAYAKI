package de.swfdtf.parser;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fuzzing suite verifying {@link VJavaLexer} and {@link VJavaParser} never hang or throw
 * uncaught runtime/stack-overflow errors on 5,000 mutated and truncated scripts.
 */
class VJavaLexerParserFuzzTest {

    private static final int ITERATIONS = 5_000;
    private static final long BUDGET_MILLIS = 2_000L;

    @Test
    void fuzzes5000MutatedTruncatedAndDeeplyNestedScriptsUnder2Seconds() {
        List<String> seeds = List.of(
                "label start:\n    \"Hallo\"\n    return\n",
                "default score = 0\nlabel check:\n    if score >= 0:\n        $ score += 1\n    return\n",
                "label jblock:\n    :: java @->{\n        state.set(\"ok\", true);\n    }<<\n    return\n",
                "menu \"Choose\":\n    \"Go\" -> jump end\nlabel end:\n    return\n",
                "transform spin:\n    xpos 100\n    ease_in 0.5 xpos 300\n    repeat\n"
        );

        Random rng = new Random(0x5F3759DFEL);
        long startNanos = System.nanoTime();

        for (int i = 0; i < ITERATIONS; i++) {
            String script = buildMutatedScript(seeds.get(i % seeds.size()), rng, i);
            try {
                List<VJavaLexer.Token> tokens = VJavaLexer.tokenize(script, "fuzz_" + i + ".vjava");
                VJavaParser.parse(Path.of("fuzz_" + i + ".vjava"), tokens);
            } catch (VJavaParseException expected) {
                assertNotNull(expected.getSourceFile());
                assertTrue(expected.getLineNumber() >= 0);
            }
        }

        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        assertTrue(elapsedMillis < BUDGET_MILLIS,
                "5,000 fuzz iterations exceeded 2,000 ms budget: " + elapsedMillis + " ms");
    }

    private static String buildMutatedScript(String seed, Random rng, int index) {
        return switch (index % 6) {
            case 0 -> seed.substring(0, Math.max(1, rng.nextInt(seed.length() + 1)));
            case 1 -> " ".repeat(rng.nextInt(13)) + seed.replace("\n", "\n" + " ".repeat(rng.nextInt(9)));
            case 2 -> seed.replace("}<<", "").replace("\"", rng.nextBoolean() ? "'" : "\"");
            case 3 -> {
                StringBuilder deep = new StringBuilder("label deep:\n");
                for (int d = 1; d <= 90; d++) {
                    deep.append("    ".repeat(d)).append("if true:\n");
                }
                deep.append("    ".repeat(91)).append("\"leaf\"\n");
                yield deep.toString();
            }
            case 4 -> "\t\n::\n@->{\n" + seed.substring(0, seed.length() / 2);
            default -> {
                StringBuilder sb = new StringBuilder(seed.length() + 16);
                for (int j = 0; j < seed.length(); j++) {
                    char c = seed.charAt(j);
                    sb.append(rng.nextInt(5) == 0 ? (char) (32 + rng.nextInt(94)) : c);
                }
                yield sb.toString();
            }
        };
    }
}
