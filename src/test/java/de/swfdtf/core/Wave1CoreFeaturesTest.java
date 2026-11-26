package de.swfdtf.core;

import de.swfdtf.core.GamepadNavigationRouter.NavigationDirection;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Wave1CoreFeaturesTest {

    private record TestEvent(String payload) implements EngineEvent {
    }

    private interface BaseUiEvent extends EngineEvent {
        String source();
    }

    private record ButtonPressedEvent(String source) implements BaseUiEvent {
    }

    @Nested
    class EngineEventBusTests {

        @Test
        void deliversEventsInPriorityOrderWithFifoTieBreaking() {
            EngineEventBus bus = new EngineEventBus();
            List<String> order = new ArrayList<>();

            @SuppressWarnings("unchecked")
            Consumer<TestEvent> mockLow = mock(Consumer.class);
            @SuppressWarnings("unchecked")
            Consumer<TestEvent> mockHigh = mock(Consumer.class);

            bus.subscribe(TestEvent.class, e -> {
                order.add("low-1");
                mockLow.accept(e);
            }, 1);
            bus.subscribe(TestEvent.class, e -> {
                order.add("high");
                mockHigh.accept(e);
            }, 10);
            bus.subscribe(TestEvent.class, e -> order.add("low-2"), 1);
            bus.subscribe(TestEvent.class, e -> order.add("default"));

            TestEvent event = new TestEvent("go");
            assertTrue(event.timestampNanos() > 0L);

            bus.publish(event);

            assertEquals(List.of("high", "low-1", "low-2", "default"), order);
            InOrder inOrder = inOrder(mockHigh, mockLow);
            inOrder.verify(mockHigh).accept(event);
            inOrder.verify(mockLow).accept(event);
            assertEquals(4, bus.listenerCount(TestEvent.class));
        }

        @Test
        void supportsPolymorphicDispatchAndAutoCloseableUnsubscribe() throws Exception {
            EngineEventBus bus = new EngineEventBus();
            List<String> received = new ArrayList<>();

            EventSubscription baseSub = bus.subscribe(BaseUiEvent.class, e -> received.add("base:" + e.source()), 5);
            try (EventSubscription exactSub = bus.subscribe(
                    ButtonPressedEvent.class, e -> received.add("exact:" + e.source()), 10)) {
                assertTrue(exactSub.isActive());
                assertTrue(baseSub.isActive());
                bus.publish(new ButtonPressedEvent("start"));
                assertEquals(List.of("exact:start", "base:start"), received);
            }

            assertEquals(0, bus.listenerCount(ButtonPressedEvent.class));
            assertEquals(1, bus.listenerCount(BaseUiEvent.class));

            received.clear();
            bus.publish(new ButtonPressedEvent("next"));
            assertEquals(List.of("base:next"), received);

            baseSub.unsubscribe();
            baseSub.unsubscribe();
            assertFalse(baseSub.isActive());
            assertEquals(0, bus.listenerCount(BaseUiEvent.class));
        }

        @Test
        void isolatesListenerExceptionsAndLogsToOptionalLogger() {
            EngineLogger logger = new EngineLogger();
            logger.setConsoleOutputEnabled(false);
            EngineEventBus bus = new EngineEventBus(logger);

            assertTrue(bus.getLogger().isPresent());
            assertTrue(new EngineEventBus().getLogger().isEmpty());

            @SuppressWarnings("unchecked")
            Consumer<TestEvent> failing = mock(Consumer.class);
            @SuppressWarnings("unchecked")
            Consumer<TestEvent> healthy = mock(Consumer.class);
            doThrow(new IllegalStateException("listener failure")).when(failing).accept(any());

            bus.subscribe(TestEvent.class, failing, 10);
            bus.subscribe(TestEvent.class, healthy, 1);

            TestEvent event = new TestEvent("ping");
            bus.publish(event);

            verify(failing, times(1)).accept(event);
            verify(healthy, times(1)).accept(event);
            assertEquals(1, logger.size());
            assertEquals(EngineLogger.Level.ERROR, logger.getRecentEntries(1).get(0).level());
        }

        @Test
        void clearDeactivatesAllSubscriptionsAndRejectsNullArguments() {
            EngineEventBus bus = new EngineEventBus();
            EventSubscription sub = bus.subscribe(TestEvent.class, e -> {
            });
            assertTrue(sub.isActive());

            bus.clear();
            assertFalse(sub.isActive());
            assertEquals(0, bus.listenerCount(TestEvent.class));

            assertThrows(NullPointerException.class, () -> new EngineEventBus(null));
            assertThrows(NullPointerException.class, () -> bus.subscribe(null, e -> {
            }));
            assertThrows(NullPointerException.class, () -> bus.subscribe(TestEvent.class, null));
            assertThrows(NullPointerException.class, () -> bus.publish(null));
            assertThrows(NullPointerException.class, () -> bus.listenerCount(null));
        }

        @Test
        void handlesConcurrentSubscribesAndPublishesSafely() throws Exception {
            EngineEventBus bus = new EngineEventBus();
            AtomicInteger count = new AtomicInteger();
            int threads = 4;
            int iterations = 100;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threads);

            try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                for (int t = 0; t < threads; t++) {
                    pool.submit(() -> {
                        try {
                            startLatch.await();
                            for (int i = 0; i < iterations; i++) {
                                EventSubscription s = bus.subscribe(TestEvent.class, e -> count.incrementAndGet());
                                bus.publish(new TestEvent("concurrent"));
                                s.unsubscribe();
                            }
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }
                startLatch.countDown();
                assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
            }
            assertEquals(0, bus.listenerCount(TestEvent.class));
            assertTrue(count.get() > 0);
        }
    }

    @Nested
    class CommandStackTests {

        @Test
        void executesUndoesRedoesAndClearsRedoOnNewCommand() {
            CommandStack stack = new CommandStack();
            EngineCommand cmd1 = mock(EngineCommand.class);
            EngineCommand cmd2 = mock(EngineCommand.class);
            EngineCommand cmd3 = mock(EngineCommand.class);
            when(cmd1.description()).thenReturn("Move Sprite");
            when(cmd2.description()).thenReturn("Rename Layer");
            when(cmd3.description()).thenReturn("Delete Node");

            assertFalse(stack.canUndo());
            assertFalse(stack.canRedo());
            assertFalse(stack.undo());
            assertFalse(stack.redo());
            assertTrue(stack.nextUndoDescription().isEmpty());
            assertTrue(stack.nextRedoDescription().isEmpty());

            stack.execute(cmd1);
            stack.execute(cmd2);

            verify(cmd1, times(1)).execute();
            verify(cmd2, times(1)).execute();
            assertEquals(2, stack.undoSize());
            assertEquals(0, stack.redoSize());
            assertEquals(Optional.of("Rename Layer"), stack.nextUndoDescription());
            assertEquals(List.of("Move Sprite", "Rename Layer"), stack.undoHistoryDescriptions());

            assertTrue(stack.undo());
            verify(cmd2, times(1)).undo();
            assertEquals(1, stack.undoSize());
            assertEquals(1, stack.redoSize());
            assertEquals(Optional.of("Move Sprite"), stack.nextUndoDescription());
            assertEquals(Optional.of("Rename Layer"), stack.nextRedoDescription());

            assertTrue(stack.redo());
            verify(cmd2, times(2)).execute();
            assertEquals(2, stack.undoSize());
            assertEquals(0, stack.redoSize());

            assertTrue(stack.undo());
            assertEquals(1, stack.redoSize());
            stack.execute(cmd3);
            assertEquals(0, stack.redoSize());
            assertFalse(stack.canRedo());
            assertEquals(List.of("Move Sprite", "Delete Node"), stack.undoHistoryDescriptions());

            List<String> history = stack.undoHistoryDescriptions();
            assertThrows(UnsupportedOperationException.class, () -> history.add("X"));

            stack.clear();
            assertEquals(0, stack.undoSize());
            assertEquals(0, stack.redoSize());
        }

        @Test
        void enforcesMaxDepthAndValidatesInputs() {
            assertThrows(IllegalArgumentException.class, () -> new CommandStack(0));
            assertThrows(IllegalArgumentException.class, () -> new CommandStack(-5));

            CommandStack stack = new CommandStack(2);
            assertEquals(2, stack.getMaxDepth());
            assertThrows(NullPointerException.class, () -> stack.execute(null));

            for (int i = 1; i <= 4; i++) {
                final int idx = i;
                stack.execute(new EngineCommand() {
                    @Override
                    public String description() {
                        return "Cmd-" + idx;
                    }

                    @Override
                    public void execute() {
                    }

                    @Override
                    public void undo() {
                    }
                });
            }

            assertEquals(2, stack.undoSize());
            assertEquals(List.of("Cmd-3", "Cmd-4"), stack.undoHistoryDescriptions());
        }
    }

    @Nested
    class PluginRegistryTests {

        @Test
        void managesRegistrationEnableDisableAndUnregisterLifecycle() {
            EngineAPI api = new EngineAPI(EngineConfig.defaults());
            api.getLogger().setConsoleOutputEnabled(false);
            PluginRegistry registry = new PluginRegistry(api);

            EnginePlugin pluginA = mock(EnginePlugin.class);
            when(pluginA.id()).thenReturn("dialogue-fx");
            when(pluginA.displayName()).thenReturn("Dialogue Effects");
            when(pluginA.version()).thenReturn("1.2.0");

            EnginePlugin pluginB = mock(EnginePlugin.class);
            when(pluginB.id()).thenReturn("steam-bridge");

            registry.register(pluginA);
            registry.register(pluginB);

            assertEquals(List.of(pluginA, pluginB), registry.listAll());
            assertTrue(registry.listEnabled().isEmpty());
            assertEquals(Optional.of(pluginA), registry.findPlugin("dialogue-fx"));
            assertTrue(registry.findPlugin("missing").isEmpty());
            assertFalse(registry.isEnabled("dialogue-fx"));

            registry.enable("dialogue-fx");
            registry.enable("dialogue-fx");
            verify(pluginA, times(1)).onEnable(api);
            assertTrue(registry.isEnabled("dialogue-fx"));
            assertEquals(List.of(pluginA), registry.listEnabled());

            registry.disable("dialogue-fx");
            registry.disable("dialogue-fx");
            verify(pluginA, times(1)).onDisable(api);
            assertFalse(registry.isEnabled("dialogue-fx"));

            registry.enable("steam-bridge");
            assertTrue(registry.unregister("steam-bridge"));
            verify(pluginB, times(1)).onDisable(api);
            assertFalse(registry.unregister("steam-bridge"));
            assertEquals(List.of(pluginA), registry.listAll());

            assertThrows(UnsupportedOperationException.class, () -> registry.listAll().add(pluginB));
            assertThrows(UnsupportedOperationException.class, () -> registry.listEnabled().add(pluginB));
        }

        @Test
        void rejectsInvalidIdsDuplicatesAndUnknownPlugins() {
            EngineAPI api = new EngineAPI(EngineConfig.defaults());
            PluginRegistry registry = new PluginRegistry(api);

            assertThrows(NullPointerException.class, () -> new PluginRegistry(null));
            assertThrows(NullPointerException.class, () -> registry.register(null));

            EnginePlugin blankPlugin = mock(EnginePlugin.class);
            when(blankPlugin.id()).thenReturn("   ");
            assertThrows(IllegalArgumentException.class, () -> registry.register(blankPlugin));

            EnginePlugin valid = mock(EnginePlugin.class);
            when(valid.id()).thenReturn("core-ext");
            registry.register(valid);
            assertThrows(IllegalStateException.class, () -> registry.register(valid));

            assertThrows(IllegalArgumentException.class, () -> registry.enable("unknown"));
            assertThrows(IllegalArgumentException.class, () -> registry.disable("unknown"));
            assertThrows(NullPointerException.class, () -> registry.findPlugin(null));
            verify(valid, never()).onEnable(any());
        }
    }

    @Nested
    class KeybindingRemapperTests {

        @Test
        void rebindsWithConflictDetectionSwapResetAndInputQuery() {
            KeybindingRemapper remapper = new KeybindingRemapper();
            remapper.registerDefault("confirm", GLFW.GLFW_KEY_SPACE);
            remapper.registerDefault("cancel", GLFW.GLFW_KEY_ESCAPE);
            remapper.registerDefault("menu", GLFW.GLFW_KEY_M);

            assertEquals(Optional.of(GLFW.GLFW_KEY_SPACE), remapper.getKeyCode("confirm"));
            assertEquals(Optional.of("confirm"), remapper.findActionForKey(GLFW.GLFW_KEY_SPACE));
            assertEquals(Optional.of("cancel"), remapper.conflictFor("confirm", GLFW.GLFW_KEY_ESCAPE));
            assertTrue(remapper.conflictFor("confirm", GLFW.GLFW_KEY_SPACE).isEmpty());

            assertThrows(IllegalStateException.class, () -> remapper.bind("confirm", GLFW.GLFW_KEY_ESCAPE));
            assertThrows(IllegalStateException.class, () -> remapper.rebind("confirm", GLFW.GLFW_KEY_M));

            remapper.rebind("confirm", GLFW.GLFW_KEY_ENTER);
            assertEquals(Optional.of(GLFW.GLFW_KEY_ENTER), remapper.getKeyCode("confirm"));

            Optional<String> swapped = remapper.rebindAllowingSwap("confirm", GLFW.GLFW_KEY_ESCAPE);
            assertEquals(Optional.of("cancel"), swapped);
            assertEquals(Optional.of(GLFW.GLFW_KEY_ESCAPE), remapper.getKeyCode("confirm"));
            assertEquals(Optional.of(GLFW.GLFW_KEY_ENTER), remapper.getKeyCode("cancel"));

            InputManager mockInput = mock(InputManager.class);
            when(mockInput.isKeyDown(GLFW.GLFW_KEY_ESCAPE)).thenReturn(true);
            assertTrue(remapper.isActionDown("confirm", mockInput));
            assertFalse(remapper.isActionDown("menu", mockInput));

            remapper.resetToDefaults();
            assertEquals(Optional.of(GLFW.GLFW_KEY_SPACE), remapper.getKeyCode("confirm"));
            assertEquals(Optional.of(GLFW.GLFW_KEY_ESCAPE), remapper.getKeyCode("cancel"));

            Map<String, Integer> snapshot = remapper.snapshotBindings();
            assertThrows(UnsupportedOperationException.class, () -> snapshot.put("hack", 1));
        }

        @Test
        void serializesAndDeserializesJsonSafely() {
            KeybindingRemapper remapper = new KeybindingRemapper();
            remapper.registerDefault("confirm", GLFW.GLFW_KEY_SPACE);
            remapper.registerDefault("cancel", GLFW.GLFW_KEY_ESCAPE);
            remapper.rebind("confirm", GLFW.GLFW_KEY_F);

            String json = remapper.toJson();

            KeybindingRemapper loaded = new KeybindingRemapper();
            loaded.registerDefault("confirm", GLFW.GLFW_KEY_SPACE);
            loaded.registerDefault("cancel", GLFW.GLFW_KEY_ESCAPE);
            loaded.loadJson(json);

            assertEquals(Optional.of(GLFW.GLFW_KEY_F), loaded.getKeyCode("confirm"));
            assertEquals(Optional.of(GLFW.GLFW_KEY_ESCAPE), loaded.getKeyCode("cancel"));

            assertThrows(NullPointerException.class, () -> loaded.loadJson(null));
            assertThrows(IllegalArgumentException.class, () -> loaded.loadJson("   "));
            assertThrows(IllegalArgumentException.class, () -> loaded.loadJson("[]"));
            assertThrows(IllegalArgumentException.class, () -> loaded.loadJson("{invalid"));
            assertThrows(IllegalArgumentException.class, () -> loaded.loadJson("{\"unknown\": 32}"));
            assertThrows(IllegalArgumentException.class, () -> loaded.loadJson("{\"confirm\": -1}"));
            assertThrows(IllegalArgumentException.class, () -> loaded.loadJson("{\"confirm\": 32.5}"));
            assertThrows(IllegalStateException.class,
                    () -> loaded.loadJson("{\"confirm\": 65, \"cancel\": 65}"));

            // Failed loadJson calls must leave existing bindings intact
            assertEquals(Optional.of(GLFW.GLFW_KEY_F), loaded.getKeyCode("confirm"));
        }
    }

    @Nested
    class GamepadNavigationRouterTests {

        @Test
        void routesDpadHoldRepeatAndFaceButtonsWithRealInputManager() {
            InputManager input = new InputManager();
            GamepadNavigationRouter router = new GamepadNavigationRouter(0.4f, 0.30, 0.10);

            boolean[] buttons = new boolean[InputManager.GAMEPAD_BUTTON_COUNT];
            float[] axes = new float[InputManager.GAMEPAD_AXIS_COUNT];

            // Frame 1: DPAD_DOWN + Button A pressed
            buttons[GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN] = true;
            buttons[GLFW.GLFW_GAMEPAD_BUTTON_A] = true;
            input.beginFrame();
            input.updateGamepadState(0, true, buttons, axes);
            List<NavigationDirection> frame1 = router.poll(input, 0.016);
            input.endFrame();

            assertEquals(List.of(NavigationDirection.DOWN, NavigationDirection.CONFIRM), frame1);
            assertThrows(UnsupportedOperationException.class, () -> frame1.add(NavigationDirection.UP));

            // Frame 2: Still held within initial delay (0.20s < 0.30s) -> no events, no duplicate CONFIRM
            input.beginFrame();
            input.updateGamepadState(0, true, buttons, axes);
            List<NavigationDirection> frame2 = router.poll(input, 0.20);
            input.endFrame();
            assertTrue(frame2.isEmpty());

            // Frame 3: Crosses initial delay (0.20 + 0.12 = 0.32 >= 0.30, remainder 0.02 < 0.10) -> 1 DOWN
            input.beginFrame();
            input.updateGamepadState(0, true, buttons, axes);
            List<NavigationDirection> frame3 = router.poll(input, 0.12);
            input.endFrame();
            assertEquals(List.of(NavigationDirection.DOWN), frame3);

            // Frame 4: Advance by 0.19s (0.02 + 0.19 = 0.21 >= 2 * 0.10) -> 2 DOWN repeats
            input.beginFrame();
            input.updateGamepadState(0, true, buttons, axes);
            List<NavigationDirection> frame4 = router.poll(input, 0.19);
            input.endFrame();
            assertEquals(List.of(NavigationDirection.DOWN, NavigationDirection.DOWN), frame4);
        }

        @Test
        void routesAnalogStickWithDeadzoneAndMocksCancelAndDisconnect() {
            GamepadNavigationRouter router = new GamepadNavigationRouter(0.35f, 0.40, 0.12);
            InputManager mockInput = mock(InputManager.class);

            when(mockInput.isGamepadConnected()).thenReturn(true);
            // Inside deadzone -> ignored
            when(mockInput.getGamepadAxis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X)).thenReturn(0.30f);
            when(mockInput.getGamepadAxis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y)).thenReturn(-0.20f);
            assertTrue(router.poll(mockInput, 0.016).isEmpty());

            // Outside deadzone: left stick up + right + B button pressed
            when(mockInput.getGamepadAxis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X)).thenReturn(0.75f);
            when(mockInput.getGamepadAxis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y)).thenReturn(-0.80f);
            when(mockInput.wasGamepadButtonJustPressed(GLFW.GLFW_GAMEPAD_BUTTON_B)).thenReturn(true);
            assertEquals(
                    List.of(NavigationDirection.UP, NavigationDirection.RIGHT, NavigationDirection.CANCEL),
                    router.poll(mockInput, 0.016)
            );

            // Disconnected gamepad returns empty list and resets repeat state
            when(mockInput.isGamepadConnected()).thenReturn(false);
            assertTrue(router.poll(mockInput, 0.016).isEmpty());

            assertThrows(NullPointerException.class, () -> router.poll(null, 0.016));
            assertThrows(IllegalArgumentException.class, () -> router.poll(mockInput, -0.1));
            assertThrows(IllegalArgumentException.class, () -> router.setDeadzone(1.0f));
            assertThrows(IllegalArgumentException.class, () -> router.setInitialRepeatDelaySeconds(0.0));
            assertThrows(IllegalArgumentException.class, () -> router.setRepeatIntervalSeconds(-1.0));
        }
    }
}
