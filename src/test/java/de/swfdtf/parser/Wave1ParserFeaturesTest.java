package de.swfdtf.parser;

import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.parser.HotReloadWatcher.ReloadDelta;
import de.swfdtf.parser.ScriptProfiler.LabelProfile;
import de.swfdtf.state.SimpleStateStore;
import de.swfdtf.state.StateStore;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Wave1ParserFeaturesTest {

    @Nested
    class ScriptProfilerTests {

        @Test
        void aggregatesHitsTotalsMaxAverageAndTopLabels() {
            ScriptProfiler profiler = new ScriptProfiler();

            assertTrue(profiler.hottestLabel().isEmpty());
            assertTrue(profiler.findLabelProfile("start").isEmpty());
            assertTrue(profiler.formatReport().contains("no executions recorded"));

            profiler.recordExecution("start", 0, 1_000_000L);
            profiler.recordExecution("start", 1, 3_000_000L);
            profiler.recordExecution("battle", 0, 5_000_000L);
            profiler.recordExecution("ending", 0, 500_000L);

            LabelProfile start = profiler.findLabelProfile("start").orElseThrow();
            assertEquals("start", start.label());
            assertEquals(2L, start.hitCount());
            assertEquals(4_000_000L, start.totalNanos());
            assertEquals(3_000_000L, start.maxNanos());
            assertEquals(2.0, start.averageMillis(), 1e-6);

            LabelProfile hottest = profiler.hottestLabel().orElseThrow();
            assertEquals("battle", hottest.label());
            assertEquals(5_000_000L, hottest.totalNanos());

            List<LabelProfile> top2 = profiler.topLabels(2);
            assertEquals(List.of("battle", "start"), top2.stream().map(LabelProfile::label).toList());
            assertThrows(UnsupportedOperationException.class, () -> top2.add(start));
            assertEquals(List.of(), profiler.topLabels(0));

            String report = profiler.formatReport();
            assertTrue(report.contains("labels=3"));
            assertTrue(report.contains("hits=4"));
            assertTrue(report.contains("battle:"));
            assertTrue(report.contains("start:"));

            profiler.reset();
            assertTrue(profiler.hottestLabel().isEmpty());
            assertTrue(profiler.topLabels(10).isEmpty());
        }

        @Test
        void recordsTimedRunnablesIncludingOnExceptionAndValidatesGuards() {
            ScriptProfiler profiler = new ScriptProfiler();
            Runnable mockTask = mock(Runnable.class);

            profiler.recordTimed("intro", 0, mockTask);
            verify(mockTask, times(1)).run();
            assertEquals(1L, profiler.findLabelProfile("intro").orElseThrow().hitCount());

            assertThrows(IllegalStateException.class, () ->
                    profiler.recordTimed("failing_label", 2, () -> {
                        throw new IllegalStateException("boom");
                    })
            );
            assertEquals(1L, profiler.findLabelProfile("failing_label").orElseThrow().hitCount());

            assertThrows(NullPointerException.class, () -> profiler.recordExecution(null, 0, 10L));
            assertThrows(IllegalArgumentException.class, () -> profiler.recordExecution("  ", 0, 10L));
            assertThrows(IllegalArgumentException.class, () -> profiler.recordExecution("ok", 0, -1L));
            assertThrows(IllegalArgumentException.class, () -> profiler.topLabels(-1));
            assertThrows(NullPointerException.class, () -> profiler.recordTimed("ok", 0, null));
        }
    }

    @Nested
    class VariableWatchpointManagerTests {

        @Mock
        private Consumer<WatchpointEvent> listener;

        @Mock
        private Predicate<Object> condition;

        @Mock
        private StateStore mockStateStore;

        @Test
        void watchpointEventExposesOptionalPreviousAndNewValues() {
            WatchpointEvent initial = new WatchpointEvent("affection", null, 5, "chapter1:0");
            assertTrue(initial.previousOptional().isEmpty());
            assertEquals(Optional.of(5), initial.newOptional());

            WatchpointEvent cleared = new WatchpointEvent("affection", 5, null, "chapter1:1");
            assertEquals(Optional.of(5), cleared.previousOptional());
            assertTrue(cleared.newOptional().isEmpty());

            assertThrows(NullPointerException.class, () -> new WatchpointEvent(null, 1, 2, "ctx"));
            assertThrows(NullPointerException.class, () -> new WatchpointEvent("k", 1, 2, null));
        }

        @Test
        void watchKeyDispatchesOnChangeOnlyAndRespectsUnwatch() {
            VariableWatchpointManager manager = new VariableWatchpointManager();
            SimpleStateStore store = new SimpleStateStore();

            String watchId = manager.watchKey("score", listener);
            assertEquals(0, manager.checkAll(store, "start:0"));
            verifyNoInteractions(listener);

            store.set("score", 10);
            assertEquals(1, manager.checkAll(store, "start:1"));

            // Second poll without change must not fire
            assertEquals(0, manager.checkAll(store, "start:2"));

            store.set("score", 25);
            assertEquals(1, manager.checkAll(store, "start:3"));

            store.remove("score");
            assertEquals(1, manager.checkAll(store, "start:4"));

            InOrder order = inOrder(listener);
            order.verify(listener).accept(new WatchpointEvent("score", null, 10, "start:1"));
            order.verify(listener).accept(new WatchpointEvent("score", 10, 25, "start:3"));
            order.verify(listener).accept(new WatchpointEvent("score", 25, null, "start:4"));

            assertTrue(manager.unwatch(watchId));
            assertFalse(manager.unwatch(watchId));

            store.set("score", 99);
            assertEquals(0, manager.checkAll(store, "start:5"));
            verify(listener, times(3)).accept(any());
        }

        @Test
        void watchWhenFiltersWithMockitoPredicateAndMockedStateStore() {
            VariableWatchpointManager manager = new VariableWatchpointManager(2);
            when(mockStateStore.get("hp")).thenReturn(100, 50, 0, 0);
            when(condition.test(100)).thenReturn(false);
            when(condition.test(50)).thenReturn(false);
            when(condition.test(0)).thenReturn(true);

            manager.watchWhen("hp", condition, listener);

            assertEquals(0, manager.checkAll(mockStateStore, "turn:1")); // null -> 100 (false)
            assertEquals(0, manager.checkAll(mockStateStore, "turn:2")); // 100 -> 50 (false)
            assertEquals(1, manager.checkAll(mockStateStore, "turn:3")); // 50 -> 0 (true)
            assertEquals(0, manager.checkAll(mockStateStore, "turn:4")); // 0 -> 0 (unchanged)

            ArgumentCaptor<WatchpointEvent> captor = ArgumentCaptor.forClass(WatchpointEvent.class);
            verify(listener, times(1)).accept(captor.capture());
            WatchpointEvent fired = captor.getValue();
            assertEquals("hp", fired.key());
            assertEquals(50, fired.previousValue());
            assertEquals(0, fired.newValue());
            assertEquals("turn:3", fired.sourceContext());

            // Verify bounded recent history and unmodifiability
            manager.watchKey("flag", event -> {});
            when(mockStateStore.get("flag")).thenReturn("a", "b");
            manager.checkAll(mockStateStore, "turn:5");
            manager.checkAll(mockStateStore, "turn:6");

            List<WatchpointEvent> history = manager.getRecentHistory();
            assertEquals(2, history.size());
            assertEquals("a", history.get(0).newValue());
            assertEquals("b", history.get(1).newValue());
            assertThrows(UnsupportedOperationException.class, () -> history.add(fired));

            manager.clear();
            assertTrue(manager.getRecentHistory().isEmpty());
        }

        @Test
        void supportsUnwatchInsideListenerCallbackAndValidatesGuards() {
            VariableWatchpointManager manager = new VariableWatchpointManager();
            SimpleStateStore store = new SimpleStateStore();
            AtomicReference<String> idRef = new AtomicReference<>();

            String id = manager.watchKey("once", event -> manager.unwatch(idRef.get()));
            idRef.set(id);

            store.set("once", true);
            assertEquals(1, manager.checkAll(store, "cb:0"));
            store.set("once", false);
            assertEquals(0, manager.checkAll(store, "cb:1"));

            assertThrows(NullPointerException.class, () -> manager.watchKey(null, listener));
            assertThrows(IllegalArgumentException.class, () -> manager.watchKey(" ", listener));
            assertThrows(NullPointerException.class, () -> manager.watchWhen("k", null, listener));
            assertThrows(NullPointerException.class, () -> manager.checkAll(null, "ctx"));
            assertThrows(NullPointerException.class, () -> manager.checkAll(store, null));
        }
    }

    @Nested
    class MacroRegistryTests {

        @Test
        void registersFindsUnregistersSnapshotsAndExpandsNestedMacros() {
            MacroRegistry registry = new MacroRegistry();
            registry.register("hero", "Lena");
            registry.register("greeting", "Hallo, {{hero}}!");
            registry.register("special", "$100 \\ backslash");

            assertEquals(Optional.of("Lena"), registry.find("hero"));
            assertTrue(registry.find("missing").isEmpty());

            Map<String, String> snap = registry.snapshot();
            assertEquals(3, snap.size());
            assertThrows(UnsupportedOperationException.class, () -> snap.put("x", "y"));

            String expanded = registry.expand("{{ greeting }} Price: {{special}} {{unknown}}");
            assertEquals("Hallo, Lena! Price: $100 \\ backslash {{unknown}}", expanded);

            // Diamond reuse must NOT be treated as a cycle
            registry.register("twice", "{{hero}} & {{hero}}");
            assertEquals("Lena & Lena", registry.expand("{{twice}}"));

            assertTrue(registry.unregister("special"));
            assertFalse(registry.unregister("special"));
            assertTrue(registry.find("special").isEmpty());

            registry.clear();
            assertTrue(registry.snapshot().isEmpty());
            assertEquals("{{hero}}", registry.expand("{{hero}}"));
        }

        @Test
        void detectsDirectAndIndirectCyclesAndEnforcesMaxDepth() {
            MacroRegistry registry = new MacroRegistry();
            registry.register("self", "loop={{self}}");
            IllegalStateException directEx = assertThrows(
                    IllegalStateException.class,
                    () -> registry.expand("{{self}}")
            );
            assertTrue(directEx.getMessage().contains("Cyclic"));

            registry.clear();
            registry.register("a", "A->{{b}}");
            registry.register("b", "B->{{c}}");
            registry.register("c", "C->{{a}}");
            IllegalStateException indirectEx = assertThrows(
                    IllegalStateException.class,
                    () -> registry.expand("start:{{a}}")
            );
            assertTrue(indirectEx.getMessage().contains("a -> b -> c -> a"));

            MacroRegistry shallow = new MacroRegistry(2);
            shallow.register("m1", "{{m2}}");
            shallow.register("m2", "{{m3}}");
            shallow.register("m3", "leaf");
            assertEquals("leaf", shallow.expand("{{m2}}"));
            assertThrows(IllegalStateException.class, () -> shallow.expand("{{m1}}"));

            assertThrows(IllegalArgumentException.class, () -> new MacroRegistry(0));
            assertThrows(NullPointerException.class, () -> shallow.register(null, "v"));
            assertThrows(IllegalArgumentException.class, () -> shallow.register("   ", "v"));
            assertThrows(NullPointerException.class, () -> shallow.register("k", null));
            assertThrows(NullPointerException.class, () -> shallow.expand(null));
        }
    }

    @Nested
    class TypeSafeStateViewTests {

        @Test
        void readsTypedOptionalValuesAndBigDecimalCleanly() {
            SimpleStateStore store = new SimpleStateStore();
            store.set("name", "Akane");
            store.set("intNum", 42);
            store.set("intStr", " 108 ");
            store.set("longNum", 9_000_000_000L);
            store.set("longStr", "9000000001");
            store.set("doubleNum", 3.25);
            store.set("doubleStr", "6.5");
            store.set("boolVal", Boolean.TRUE);
            store.set("boolStr", "false");
            store.set("boolNum", 0);
            store.set("badText", "not-a-number");
            store.set("nanVal", Double.NaN);
            store.set("decExact", new BigDecimal("123.450"));
            store.set("decBigInt", new BigInteger("98765432109876543210"));
            store.set("decStr", " 42.195 ");
            store.set("customList", List.of("a", "b"));

            TypeSafeStateView view = new TypeSafeStateView(store);

            assertEquals(Optional.of("Akane"), view.getString("name"));
            assertEquals(Optional.of("42"), view.getString("intNum"));
            assertTrue(view.getString("missing").isEmpty());

            assertEquals(Optional.of(42), view.getInt("intNum"));
            assertEquals(Optional.of(108), view.getInt("intStr"));
            assertTrue(view.getInt("badText").isEmpty());

            assertEquals(Optional.of(9_000_000_000L), view.getLong("longNum"));
            assertEquals(Optional.of(9_000_000_001L), view.getLong("longStr"));
            assertTrue(view.getLong("badText").isEmpty());

            assertEquals(Optional.of(3.25), view.getDouble("doubleNum"));
            assertEquals(Optional.of(6.5), view.getDouble("doubleStr"));
            assertTrue(view.getDouble("badText").isEmpty());

            assertEquals(Optional.of(true), view.getBoolean("boolVal"));
            assertEquals(Optional.of(false), view.getBoolean("boolStr"));
            assertEquals(Optional.of(false), view.getBoolean("boolNum"));
            assertTrue(view.getBoolean("badText").isEmpty());

            assertEquals(Optional.of(new BigDecimal("123.450")), view.getDecimal("decExact"));
            assertEquals(Optional.of(new BigDecimal("98765432109876543210")), view.getDecimal("decBigInt"));
            assertEquals(Optional.of(new BigDecimal("42.195")), view.getDecimal("decStr"));
            assertEquals(0, new BigDecimal("3.25").compareTo(view.getDecimal("doubleNum").orElseThrow()));
            assertEquals(Optional.of(BigDecimal.valueOf(42L)), view.getDecimal("intNum"));
            assertTrue(view.getDecimal("nanVal").isEmpty());
            assertTrue(view.getDecimal("badText").isEmpty());

            assertEquals(Optional.of(List.of("a", "b")), view.getAs("customList", List.class));
            assertTrue(view.getAs("customList", Integer.class).isEmpty());

            assertTrue(view.has("name"));
            assertFalse(view.has("missing"));

            Set<String> keys = view.keys();
            assertTrue(keys.contains("name"));
            assertThrows(UnsupportedOperationException.class, () -> keys.add("mutated"));
        }

        @Test
        void delegatesToMockedStateStoreAndValidatesNullGuards() {
            StateStore mockStore = mock(StateStore.class);
            when(mockStore.get("flag")).thenReturn(true);
            when(mockStore.has("flag")).thenReturn(true);
            when(mockStore.keys()).thenReturn(Set.of("flag"));

            TypeSafeStateView view = new TypeSafeStateView(mockStore);
            assertEquals(Optional.of(true), view.getBoolean("flag"));
            assertTrue(view.has("flag"));
            assertEquals(Set.of("flag"), view.keys());

            assertThrows(NullPointerException.class, () -> new TypeSafeStateView(null));
            assertThrows(NullPointerException.class, () -> view.getString(null));
            assertThrows(NullPointerException.class, () -> view.getAs("flag", null));
            assertThrows(NullPointerException.class, () -> view.has(null));
        }
    }

    @Nested
    class HotReloadWatcherTests {

        @Test
        void detectsAddedModifiedDeletedScriptsAndDescriptorWhileSkippingIgnoredDirs(
                @TempDir Path tempDir
        ) throws IOException {
            Path descriptor = tempDir.resolve(ProjectDescriptor.FILE_NAME);
            ProjectDescriptor.save(tempDir, ProjectDescriptor.createDefault("Hot Reload Test"));

            Path mainScript = tempDir.resolve("main.vjava");
            Files.writeString(mainScript, "label start:\n    \"Hello\"\n");

            Path chaptersDir = tempDir.resolve("chapters");
            Files.createDirectories(chaptersDir);
            Path ch1 = chaptersDir.resolve("ch1.vjava");
            Files.writeString(ch1, "label ch1:\n    return\n");

            // Ignored directories and non-vjava files
            for (String skipped : ProjectScanner.SKIPPED_DIRECTORIES) {
                Path skippedDir = tempDir.resolve(skipped);
                Files.createDirectories(skippedDir);
                Files.writeString(skippedDir.resolve("ignored.vjava"), "label x:\n    return\n");
            }
            Files.writeString(tempDir.resolve("README.txt"), "ignored");

            HotReloadWatcher watcher = new HotReloadWatcher(tempDir);
            watcher.initializeSnapshot();

            ReloadDelta initialPoll = watcher.pollChanges();
            assertFalse(initialPoll.hasChanges());
            assertTrue(initialPoll.added().isEmpty());
            assertTrue(initialPoll.modified().isEmpty());
            assertTrue(initialPoll.deleted().isEmpty());

            // 1) Add new .vjava, modify main.vjava (size + timestamp) and descriptor, delete ch1.vjava
            Path ch2 = chaptersDir.resolve("ch2.vjava");
            Files.writeString(ch2, "label ch2:\n    return\n");

            Files.writeString(mainScript, "label start:\n    \"Updated Hello World!\"\n");
            Files.setLastModifiedTime(mainScript, FileTime.from(Instant.now().plusSeconds(5)));

            ProjectDescriptor updatedDescriptor = ProjectDescriptor.createDefault("Hot Reload Test v2");
            ProjectDescriptor.save(tempDir, updatedDescriptor);
            Files.setLastModifiedTime(descriptor, FileTime.from(Instant.now().plusSeconds(5)));

            Files.delete(ch1);

            // Changes inside skipped directories or non-vjava files must be ignored
            Files.writeString(tempDir.resolve("build/new_ignored.vjava"), "label ignored:\n    return\n");
            Files.writeString(tempDir.resolve("README.txt"), "still ignored");

            ReloadDelta delta = watcher.pollChanges();
            assertTrue(delta.hasChanges());
            assertEquals(List.of(ch2.toAbsolutePath().normalize()), delta.added());
            assertEquals(
                    List.of(mainScript.toAbsolutePath().normalize(), descriptor.toAbsolutePath().normalize()),
                    delta.modified()
            );
            assertEquals(List.of(ch1.toAbsolutePath().normalize()), delta.deleted());

            assertThrows(UnsupportedOperationException.class, () -> delta.added().add(mainScript));
            assertThrows(UnsupportedOperationException.class, () -> delta.modified().add(mainScript));
            assertThrows(UnsupportedOperationException.class, () -> delta.deleted().add(mainScript));

            // Subsequent poll without changes returns empty delta
            ReloadDelta steadyPoll = watcher.pollChanges();
            assertFalse(steadyPoll.hasChanges());
        }

        @Test
        void validatesProjectRootPath(@TempDir Path tempDir) throws IOException {
            Path missing = tempDir.resolve("does_not_exist");
            assertThrows(IllegalArgumentException.class, () -> new HotReloadWatcher(missing));

            Path regularFile = tempDir.resolve("file.txt");
            Files.writeString(regularFile, "not a dir");
            assertThrows(IllegalArgumentException.class, () -> new HotReloadWatcher(regularFile));

            assertThrows(NullPointerException.class, () -> new HotReloadWatcher(null));
        }
    }
}
