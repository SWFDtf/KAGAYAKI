package de.swfdtf.state;

import de.swfdtf.parser.ConditionEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property-based and determinism tests for {@link ConditionEvaluator}, {@link SimpleStateStore},
 * and {@link RevertableStateStore} checking associativity, null safety, overflows, and type edge cases.
 */
class ConditionAndStatePropertyTest {

    @Test
    void conditionEvaluatorSatisfiesArithmeticAssociativityAndCommutativity() {
        SimpleStateStore state = new SimpleStateStore();
        SimplePersistentAccess persistent = new SimplePersistentAccess();
        Random rng = new Random(0xBEEF21L);

        for (int i = 0; i < 200; i++) {
            int a = rng.nextInt(-200, 201);
            int b = rng.nextInt(-200, 201);
            int c = rng.nextInt(-200, 201);
            state.set("a", a);
            state.set("b", b);
            state.set("c", c);

            assertEquals(
                    ConditionEvaluator.evaluate("(a + b) + c", state, persistent),
                    ConditionEvaluator.evaluate("a + (b + c)", state, persistent)
            );
            assertEquals(
                    ConditionEvaluator.evaluate("(a * b) * c", state, persistent),
                    ConditionEvaluator.evaluate("a * (b * c)", state, persistent)
            );
            assertEquals(
                    ConditionEvaluator.evaluate("a + b", state, persistent),
                    ConditionEvaluator.evaluate("b + a", state, persistent)
            );
        }
    }

    @Test
    void conditionEvaluatorHandlesNullSafetyOverflowsAndTypeEdgeCasesDeterministically() {
        SimpleStateStore state = new SimpleStateStore();
        SimplePersistentAccess persistent = new SimplePersistentAccess();
        state.set("nullVal", null);
        state.set("empty", "");
        state.set("maxInt", Integer.MAX_VALUE);
        state.set("minInt", Integer.MIN_VALUE);
        state.set("maxLong", Long.MAX_VALUE);

        assertEquals(Boolean.TRUE, ConditionEvaluator.evaluate("null == null", null, null));
        assertEquals(Boolean.FALSE, ConditionEvaluator.evaluate("null == 0", null, null));
        assertEquals(Boolean.TRUE, ConditionEvaluator.evaluate("null != 0", null, null));
        assertEquals(Boolean.FALSE, ConditionEvaluator.evaluate("null && true", null, null));
        assertEquals(Boolean.TRUE, ConditionEvaluator.evaluate("null || true", null, null));
        assertEquals(Boolean.TRUE, ConditionEvaluator.evaluate("!nullVal", state, persistent));
        assertEquals("null_ok", ConditionEvaluator.evaluate("nullVal + \"_ok\"", state, persistent));
        assertFalse(ConditionEvaluator.evaluateBoolean("empty", state, persistent));
        assertNull(ConditionEvaluator.evaluate("missing_var", null, null));

        assertTrue(ConditionEvaluator.evaluateBoolean("maxInt > minInt", state, persistent));
        assertEquals(Long.valueOf(Integer.MAX_VALUE + 1L),
                ConditionEvaluator.evaluate("maxInt + 1L", state, persistent));
        assertEquals(Double.valueOf(Double.POSITIVE_INFINITY),
                ConditionEvaluator.evaluate("1.0 / 0.0", state, persistent));
        assertThrows(ArithmeticException.class, () -> ConditionEvaluator.evaluate("10 / 0", state, persistent));
        assertThrows(ArithmeticException.class, () -> ConditionEvaluator.evaluate("10 % 0", state, persistent));
        assertThrows(IllegalArgumentException.class,
                () -> ConditionEvaluator.evaluate("\"abc\" < 123", state, persistent));
        assertThrows(IllegalArgumentException.class,
                () -> ConditionEvaluator.evaluate("null < 1", state, persistent));
    }

    @Test
    void revertableStateStoreMaintainsDeterministicRollbackAndRollForwardInvariants() {
        RevertableStateStore store = new RevertableStateStore();
        Random rng = new Random(0xCAFEBABEL);
        store.recordCheckpoint("init");

        for (int step = 0; step < 200; step++) {
            int action = rng.nextInt(10);
            if (action < 6) {
                store.set("hp", rng.nextInt(0, 100));
                store.set("flag", rng.nextBoolean());
                store.set("tags", List.of("a", String.valueOf(step)));
                store.set("meta", Map.of("step", step));
                store.recordCheckpoint("step_" + step);
            } else if (action < 8 && store.canRollback()) {
                Map<String, Object> beforeUndo = store.snapshotMap();
                assertTrue(store.rollback());
                assertTrue(store.canRollForward());
                assertTrue(store.rollForward());
                assertEquals(beforeUndo, store.snapshotMap());
            } else if (store.canRollback()) {
                assertTrue(store.rollback());
            }

            assertFalse(store.has("definitely_missing_key"));
            assertEquals(42, store.getInt("non_numeric_or_missing", 42));
            assertEquals(1.5, store.getDouble("non_numeric_or_missing", 1.5), 1e-9);
            assertTrue(store.getBoolean("missing_bool", true));
        }
    }
}
