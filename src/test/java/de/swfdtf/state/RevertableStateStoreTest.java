package de.swfdtf.state;

import de.swfdtf.math.Transform2D;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RevertableStateStore}, {@link RevertableMap}, and {@link RevertableList}.
 */
class RevertableStateStoreTest {

    /** Custom non-Serializable POJO simulating an {@code @->{ ... }<<} user class. */
    static final class NonSerializableItem {
        public final String name;
        public int charges;

        NonSerializableItem(String name, int charges) {
            this.name = name;
            this.charges = charges;
        }
    }

    /** Custom Serializable value verifying isolated deep cloning. */
    static final class SerializableWallet implements Serializable {
        int coins;

        SerializableWallet(int coins) {
            this.coins = coins;
        }
    }

    /** Custom Revertable object with no-arg constructor verifying capture/restore protocol. */
    static final class CustomRevertableCounter implements Revertable<Integer> {
        int count = 0;

        @Override
        public Integer captureState() {
            return count;
        }

        @Override
        public void restoreState(Integer snapshot) {
            this.count = snapshot;
        }
    }

    @Test
    void supportsBasicStateMutationsAndTypeCoercions() {
        RevertableStateStore store = new RevertableStateStore();
        store.set("name", "Lena");
        store.set("courage", 35);
        store.set("ratio", "2.75");
        store.set("active", "true");
        store.set("zero", 0);

        assertEquals("Lena", store.getString("name", ""));
        assertEquals(35, store.getInt("courage", 0));
        assertEquals(2.75, store.getDouble("ratio", 0.0), 1e-9);
        assertTrue(store.getBoolean("active", false));
        assertFalse(store.getBoolean("zero", true));
        assertTrue(store.has("name"));
        assertEquals(Set.of("name", "courage", "ratio", "active", "zero"), store.keys());

        assertEquals("Lena", store.remove("name"));
        assertFalse(store.has("name"));
        assertEquals("fallback", store.getOrDefault("name", "fallback"));

        store.set("courage", null);
        assertFalse(store.has("courage"));
    }

    @Test
    void rollbackAndCleanRollForwardPreserveChoiceBranches() {
        RevertableStateStore store = new RevertableStateStore();
        AtomicReference<String> runtimeCursor = new AtomicReference<>("start:0");
        store.setRuntimeHooks(runtimeCursor::get, val -> runtimeCursor.set((String) val));

        store.set("affinity", 10);
        store.set("branch", "intro");
        long cp1 = store.recordCheckpoint("Line 1");

        store.set("affinity", 20);
        runtimeCursor.set("menu:1");
        long cp2 = store.recordCheckpoint("Choice menu");

        store.set("affinity", 35);
        store.set("branch", "academy_roof");
        runtimeCursor.set("roof:0");
        long cp3 = store.recordCheckpoint("Roof branch");

        assertFalse(store.canRollForward());
        assertTrue(store.canRollback());
        assertEquals(2, store.getCursorIndex());
        assertEquals(cp3, store.getCurrentCheckpoint().id());

        // Roll back to menu and intro
        assertTrue(store.rollback());
        assertEquals(20, store.getInt("affinity", -1));
        assertEquals("intro", store.getString("branch", ""));
        assertEquals("menu:1", runtimeCursor.get());
        assertTrue(store.canRollForward());

        assertTrue(store.rollback());
        assertEquals(10, store.getInt("affinity", -1));
        assertEquals("start:0", runtimeCursor.get());
        assertFalse(store.canRollback());
        assertTrue(store.canRollForward());
        assertEquals(cp1, store.getCurrentCheckpoint().id());

        // Step forward cleanly via recordCheckpoint -> must preserve existing roll-forward trail!
        store.set("affinity", 20);
        runtimeCursor.set("menu:1");
        long replayedCp2 = store.recordCheckpoint("Choice menu");
        assertEquals(cp2, replayedCp2);
        assertTrue(store.canRollForward());
        assertEquals(3, store.getCheckpoints().size());

        // Explicit rollForward restores the previously explored choice branch
        assertTrue(store.rollForward());
        assertEquals(35, store.getInt("affinity", -1));
        assertEquals("academy_roof", store.getString("branch", ""));
        assertEquals("roof:0", runtimeCursor.get());
        assertFalse(store.canRollForward());
    }

    @Test
    void divergingBranchAfterRollbackTrimsInvalidatedFutureFrames() {
        RevertableStateStore store = new RevertableStateStore();
        AtomicReference<String> runtimePos = new AtomicReference<>("scene:0");
        store.setRuntimeHooks(runtimePos::get, v -> runtimePos.set((String) v));

        store.set("route", "common");
        long cp1 = store.recordCheckpoint("Common 1");

        store.set("route", "choice");
        runtimePos.set("menu:0");
        long cp2 = store.recordCheckpoint("Menu");

        store.set("route", "branch_A");
        runtimePos.set("branchA:0");
        store.recordCheckpoint("Branch A line 1");

        store.set("route", "branch_A_end");
        runtimePos.set("branchA:1");
        store.recordCheckpoint("Branch A line 2");

        assertEquals(4, store.getCheckpoints().size());

        // Roll back to Menu (cp2)
        assertTrue(store.rollback());
        assertTrue(store.rollback());
        assertEquals(cp2, store.getCurrentCheckpoint().id());
        assertTrue(store.canRollForward());

        // Take a diverging choice (Branch B)
        store.set("route", "branch_B");
        runtimePos.set("branchB:0");
        long cpB = store.recordCheckpoint("Branch B line 1");

        assertFalse(store.canRollForward());
        assertEquals(3, store.getCheckpoints().size());
        assertEquals(List.of(cp1, cp2, cpB),
                store.getCheckpoints().stream().map(RevertableStateStore.Checkpoint::id).toList());
        assertEquals("branch_B", store.getString("route", ""));
        assertEquals("branchB:0", runtimePos.get());
    }

    @Test
    void blockRollbackBarrierBlocksRollbackAndJumpToCheckpointAcrossBoundary() {
        RevertableStateStore store = new RevertableStateStore();

        store.set("phase", 1);
        long cp1 = store.recordCheckpoint("Before choice");

        store.set("phase", 2);
        long cp2 = store.recordCheckpoint("At irreversible gate");

        // Trigger block_rollback before next checkpoint
        store.blockRollback();
        assertTrue(store.isRollbackBlocked());
        assertFalse(store.canRollback());
        assertFalse(store.rollback());
        assertFalse(store.jumpToCheckpoint(cp1));

        store.set("phase", 3);
        long cp3 = store.recordCheckpoint("Past point of no return");
        assertTrue(store.isRollbackBlocked());
        assertFalse(store.canRollback());
        assertFalse(store.rollback());
        assertFalse(store.jumpToCheckpoint(cp2));
        assertFalse(store.jumpToCheckpoint(cp1));

        // Advance one more line after the barrier
        store.set("phase", 4);
        long cp4 = store.recordCheckpoint("Line after barrier");
        assertFalse(store.isRollbackBlocked());
        assertTrue(store.canRollback());

        // Jump back across the barrier to cp1 or cp2 MUST be refused
        assertFalse(store.jumpToCheckpoint(cp2));
        assertFalse(store.jumpToCheckpoint(cp1));
        assertEquals(4, store.getInt("phase", 0));

        // Rolling back or jumping back to cp3 (on the safe side of the barrier) succeeds
        assertTrue(store.jumpToCheckpoint(cp3));
        assertEquals(3, store.getInt("phase", 0));
        assertTrue(store.isRollbackBlocked());
        assertFalse(store.canRollback());

        // Roll forward still works back to cp4
        assertTrue(store.rollForward());
        assertEquals(4, store.getInt("phase", 0));
        assertEquals(cp4, store.getCurrentCheckpoint().id());
    }

    @Test
    void deepCloningIsolatesCollectionsArraysTransformsSerializablesAndHandlesNonSerializablePojos() {
        RevertableMap<String, Object> map = new RevertableMap<>();
        RevertableList<Object> inventory = new RevertableList<>();
        inventory.add("Key");
        List<String> nestedList = new ArrayList<>(List.of("alpha"));
        Map<String, Object> nestedMap = new LinkedHashMap<>();
        nestedMap.put("hp", 100);
        Set<String> tags = new LinkedHashSet<>(List.of("prolog"));
        int[] stats = new int[]{10, 20, 30};
        Transform2D transform = new Transform2D().setXpos(100.0f).setAlpha(0.8f);
        SerializableWallet wallet = new SerializableWallet(50);
        CustomRevertableCounter counter = new CustomRevertableCounter();
        counter.count = 7;
        NonSerializableItem pojo = new NonSerializableItem("Sternenkompass", 3);

        map.put("inventory", inventory);
        map.put("nestedList", nestedList);
        map.put("nestedMap", nestedMap);
        map.put("tags", tags);
        map.put("stats", stats);
        map.put("transform", transform);
        map.put("wallet", wallet);
        map.put("counter", counter);
        map.put("pojo", pojo);

        // Self-referential cycle inside a list must not stack-overflow
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        map.put("cyclic", cyclic);

        Map<String, Object> snapshot = map.captureState();

        // Mutate every mutable structure in-place after snapshot capture
        inventory.add("Sword");
        nestedList.add("beta");
        nestedMap.put("hp", 0);
        tags.add("spoiler");
        stats[0] = 999;
        transform.setXpos(555.0f).setAlpha(0.1f);
        wallet.coins = 9999;
        counter.count = 42;

        map.restoreState(snapshot);

        @SuppressWarnings("unchecked")
        RevertableList<Object> restoredInv = (RevertableList<Object>) map.get("inventory");
        assertEquals(List.of("Key"), restoredInv);
        // Must remain mutable and isolated from snapshot even after restore
        restoredInv.add("Potion");

        @SuppressWarnings("unchecked")
        List<String> restoredNestedList = (List<String>) map.get("nestedList");
        assertEquals(List.of("alpha"), restoredNestedList);

        @SuppressWarnings("unchecked")
        Map<String, Object> restoredNestedMap = (Map<String, Object>) map.get("nestedMap");
        assertEquals(100, restoredNestedMap.get("hp"));

        @SuppressWarnings("unchecked")
        Set<String> restoredTags = (Set<String>) map.get("tags");
        assertEquals(Set.of("prolog"), restoredTags);

        assertArrayEquals(new int[]{10, 20, 30}, (int[]) map.get("stats"));

        Transform2D restoredTransform = (Transform2D) map.get("transform");
        assertNotSame(transform, restoredTransform);
        assertEquals(100.0f, restoredTransform.getXpos(), 1e-5f);
        assertEquals(0.8f, restoredTransform.getAlpha(), 1e-5f);

        SerializableWallet restoredWallet = (SerializableWallet) map.get("wallet");
        assertNotSame(wallet, restoredWallet);
        assertEquals(50, restoredWallet.coins);

        CustomRevertableCounter restoredCounter = (CustomRevertableCounter) map.get("counter");
        assertNotSame(counter, restoredCounter);
        assertEquals(7, restoredCounter.count);

        // Non-Serializable POJO reference is preserved safely without throwing
        assertSame(pojo, map.get("pojo"));

        @SuppressWarnings("unchecked")
        List<Object> restoredCyclic = (List<Object>) map.get("cyclic");
        assertSame(restoredCyclic, restoredCyclic.get(0));

        // Restoring the same snapshot a second time must yield clean original inventory ("Key" only)
        map.restoreState(snapshot);
        assertEquals(List.of("Key"), map.get("inventory"));
        assertNotNull(map.get("pojo"));
        assertNull(map.get("nonexistent"));
    }

    @Test
    void revertableListCapturesAndRestoresIndependently() {
        RevertableList<List<String>> list = new RevertableList<>();
        List<String> inner = new ArrayList<>(List.of("one"));
        list.add(inner);

        List<List<String>> snap = list.captureState();
        inner.add("two");
        list.add(new ArrayList<>(List.of("three")));

        assertEquals(2, list.size());
        assertEquals(List.of("one", "two"), list.get(0));

        list.restoreState(snap);
        assertEquals(1, list.size());
        assertEquals(List.of("one"), list.get(0));

        // Modifying restored element must not pollute snapshot
        list.get(0).add("mutated");
        list.restoreState(snap);
        assertEquals(List.of("one"), list.get(0));
    }
}
