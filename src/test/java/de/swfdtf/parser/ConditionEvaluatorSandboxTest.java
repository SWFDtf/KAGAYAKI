package de.swfdtf.parser;

import de.swfdtf.state.SimplePersistentAccess;
import de.swfdtf.state.SimpleStateStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security unit tests verifying {@link ConditionEvaluator} blocks reflection, classloader,
 * process, and runtime escapes while allowing benign gameplay expressions.
 */
class ConditionEvaluatorSandboxTest {

    public record SafeBadge(String title, int tier) {
    }

    @Test
    void blocksReflectionAndDangerousTypeExploits() {
        SimpleStateStore state = new SimpleStateStore();
        SimplePersistentAccess persistent = new SimplePersistentAccess();
        state.set("clsRef", Class.class);
        state.set("runtimeRef", Runtime.getRuntime());
        state.set("threadRef", Thread.currentThread());

        List<String> exploitPayloads = List.of(
                "\"\".getClass()",
                "\"\".class",
                "state.getClass()",
                "state.class",
                "\"\".getClass().getName()",
                "\"\".getClass().getClassLoader()",
                "\"\".getClass().forName(\"java.lang.Runtime\")",
                "clsRef.forName(\"java.lang.String\")",
                "runtimeRef.exec(\"echo pwned\")",
                "threadRef.getContextClassLoader()"
        );

        for (String payload : exploitPayloads) {
            SecurityException ex = assertThrows(
                    SecurityException.class,
                    () -> ConditionEvaluator.evaluate(payload, state, persistent),
                    "Expected SecurityException for payload: " + payload
            );
            String msg = ex.getMessage().toLowerCase(Locale.ROOT);
            assertTrue(msg.contains("sandbox") || msg.contains("blocked"),
                    "Expected descriptive sandbox message, got: " + ex.getMessage());
        }
    }

    @Test
    void preservesSafeExpressionsAndGameObjects() {
        SimpleStateStore state = new SimpleStateStore();
        SimplePersistentAccess persistent = new SimplePersistentAccess();
        state.set("name", "SnowWhite");
        state.set("badge", new SafeBadge("Mirror", 2));
        state.set("items", List.of("Apple", "Comb"));

        assertEquals(9, ConditionEvaluator.evaluate("name.length()", state, persistent));
        assertEquals(10, ConditionEvaluator.evaluate("Math.max(4, 10)", state, persistent));
        assertEquals("Mirror", ConditionEvaluator.evaluate("badge.title", state, persistent));
        assertEquals("Comb", ConditionEvaluator.evaluate("items[1]", state, persistent));
        assertEquals("SnowWhite", ConditionEvaluator.evaluate("state.get(\"name\")", state, persistent));
    }
}
