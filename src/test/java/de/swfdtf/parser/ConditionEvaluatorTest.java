package de.swfdtf.parser;

import de.swfdtf.state.SimplePersistentAccess;
import de.swfdtf.state.SimpleStateStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConditionEvaluator}.
 */
class ConditionEvaluatorTest {

    public static class TestItem {
        public final String name;
        public final int wert;

        public TestItem(String name, int wert) {
            this.name = name;
            this.wert = wert;
        }

        public String getBeschreibung() {
            return name + " (" + wert + ")";
        }
    }

    public static class SpecialTestItem extends TestItem {
        public final String effekt;

        public SpecialTestItem(String name, int wert, String effekt) {
            super(name, wert);
            this.effekt = effekt;
        }
    }

    public record RecordPair(String key, int score) {
    }

    @Test
    void evaluatesArithmeticParenthesesAndTernaryExpressions() {
        SimpleStateStore state = new SimpleStateStore();
        SimplePersistentAccess persistent = new SimplePersistentAccess();
        state.set("a", 10);
        state.set("b", 3);
        state.set("ratio", 2.5);

        assertEquals(16, ConditionEvaluator.evaluate("a + b * 2", state, persistent));
        assertEquals(26, ConditionEvaluator.evaluate("(a + b) * 2", state, persistent));
        assertEquals(1, ConditionEvaluator.evaluate("a % b", state, persistent));
        assertEquals(-7, ConditionEvaluator.evaluate("-a + b", state, persistent));
        assertEquals(25.0, (Double) ConditionEvaluator.evaluate("a * ratio", state, persistent), 1e-9);
        assertEquals("high", ConditionEvaluator.evaluate("a > 5 ? \"high\" : \"low\"", state, persistent));
        assertEquals("low", ConditionEvaluator.evaluate("a < 5 ? \"high\" : \"low\"", state, persistent));
    }

    @Test
    void evaluatesComparisonsBooleanLogicAndShortCircuiting() {
        SimpleStateStore state = new SimpleStateStore();
        SimplePersistentAccess persistent = new SimplePersistentAccess();
        state.set("mut_punkte", 35);
        state.set("count", 3);
        state.set("spieler_name", "Kaito");
        state.set("null_item", null);

        assertTrue(ConditionEvaluator.evaluateBoolean("mut_punkte >= 20", state, persistent));
        assertTrue(ConditionEvaluator.evaluateBoolean("count == 3 && !visited", state, persistent));
        assertTrue(ConditionEvaluator.evaluateBoolean("count == 3 and not visited", state, persistent));
        assertFalse(ConditionEvaluator.evaluateBoolean("count == 2 || visited", state, persistent));
        assertTrue(ConditionEvaluator.evaluateBoolean("\"Kaito\".equals(spieler_name)", state, persistent));
        assertTrue(ConditionEvaluator.evaluateBoolean("spieler_name == \"Kaito\"", state, persistent));
        assertFalse(ConditionEvaluator.evaluateBoolean("null_item != null && null_item.wert > 10", state, persistent));
        assertTrue(ConditionEvaluator.evaluateBoolean("null_item == null || null_item.wert > 10", state, persistent));
    }

    @Test
    void evaluatesFieldsGettersRecordsCollectionsAndStoreMethods() {
        SimpleStateStore state = new SimpleStateStore();
        SimplePersistentAccess persistent = new SimplePersistentAccess();

        SpecialTestItem amulett = new SpecialTestItem("Sternenkompass", 100, "Nachtsicht");
        state.set("aktives_item", amulett);
        state.set("pair", new RecordPair("rank", 99));
        state.set("items", List.of("Kompass", "Schlüssel"));
        state.set("meta", Map.of("zone", "Bibliothek"));
        persistent.unlockCg("cg_dach_nacht");
        persistent.set("runs", 2);

        assertEquals("Sternenkompass", ConditionEvaluator.evaluate("aktives_item.name", state, persistent));
        assertEquals("Nachtsicht", ConditionEvaluator.evaluate("aktives_item.effekt", state, persistent));
        assertEquals("Sternenkompass (100)", ConditionEvaluator.evaluate("aktives_item.beschreibung", state, persistent));
        assertEquals("Sternenkompass (100)", ConditionEvaluator.evaluate("aktives_item.getBeschreibung()", state, persistent));
        assertEquals("rank", ConditionEvaluator.evaluate("pair.key", state, persistent));
        assertEquals(99, ConditionEvaluator.evaluate("pair.score", state, persistent));
        assertEquals("Schlüssel", ConditionEvaluator.evaluate("items[1]", state, persistent));
        assertTrue(ConditionEvaluator.evaluateBoolean("items.contains(\"Kompass\")", state, persistent));
        assertEquals("Bibliothek", ConditionEvaluator.evaluate("meta.zone", state, persistent));
        assertEquals(100, ConditionEvaluator.evaluate("state.get(\"aktives_item\").wert", state, persistent));
        assertEquals(2, ConditionEvaluator.evaluate("persistent.get(\"runs\")", state, persistent));
        assertTrue(ConditionEvaluator.evaluateBoolean("persistent.isCgUnlocked(\"cg_dach_nacht\")", state, persistent));
        assertEquals(25, ConditionEvaluator.evaluate("Math.max(10, 25)", state, persistent));
    }

    @Test
    void honorsTruthinessRulesAndRejectsMalformedExpressions() {
        SimpleStateStore state = new SimpleStateStore();
        SimplePersistentAccess persistent = new SimplePersistentAccess();
        state.set("zero", 0);
        state.set("zeroDouble", 0.0);
        state.set("nonZero", -4.5);
        state.set("emptyStr", "");
        state.set("nonEmptyStr", "Lena");
        state.set("obj", new Object());

        assertFalse(ConditionEvaluator.evaluateBoolean("unset_var", state, persistent));
        assertNull(ConditionEvaluator.evaluate("unset_var", state, persistent));
        assertFalse(ConditionEvaluator.evaluateBoolean("zero", state, persistent));
        assertFalse(ConditionEvaluator.evaluateBoolean("zeroDouble", state, persistent));
        assertTrue(ConditionEvaluator.evaluateBoolean("nonZero", state, persistent));
        assertFalse(ConditionEvaluator.evaluateBoolean("emptyStr", state, persistent));
        assertTrue(ConditionEvaluator.evaluateBoolean("nonEmptyStr", state, persistent));
        assertTrue(ConditionEvaluator.evaluateBoolean("obj", state, persistent));

        assertThrows(IllegalArgumentException.class, () -> ConditionEvaluator.evaluate("", state, persistent));
        assertThrows(IllegalArgumentException.class, () -> ConditionEvaluator.evaluate("(1 + 2", state, persistent));
    }
}
