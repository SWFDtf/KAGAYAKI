package de.swfdtf.core;

import de.swfdtf.core.PluginAndRuntimeLifecycleServices.PluginCommandHandler;
import de.swfdtf.core.PluginAndRuntimeLifecycleServices.PluginCommandRouter;
import de.swfdtf.core.PluginAndRuntimeLifecycleServices.PluginLifecycleDispatcher;
import de.swfdtf.core.PluginAndRuntimeLifecycleServices.PluginLifecycleListener;
import de.swfdtf.core.PluginAndRuntimeLifecycleServices.PluginManifest;
import de.swfdtf.core.PluginAndRuntimeLifecycleServices.PluginManifestValidator;
import de.swfdtf.core.PluginAndRuntimeLifecycleServices.PluginPermission;
import de.swfdtf.core.PluginAndRuntimeLifecycleServices.PluginSerializerRegistry;
import de.swfdtf.core.PluginAndRuntimeLifecycleServices.PluginTypeSerializer;
import de.swfdtf.core.PluginAndRuntimeLifecycleServices.PluginUiSlotRegistry;
import de.swfdtf.core.PluginAndRuntimeLifecycleServices.PluginUiSlotRegistry.MenuButtonContribution;
import de.swfdtf.core.PluginAndRuntimeLifecycleServices.ProjectExtensionsPreserver;
import de.swfdtf.core.PluginAndRuntimeLifecycleServices.RotatingLogWriter;
import de.swfdtf.core.PluginAndRuntimeLifecycleServices.ShutdownResourceCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests covering Chapter 10 plugin manifest permissions &amp; semver compatibility,
 * fault-isolated lifecycle callbacks, custom plugin DSL commands, serializer fallback on unload,
 * priority UI slots, rotating log files, clean executor shutdown, and descriptor extension
 * preservation (Wave P2-10).
 */
class WaveP2Chapter10PluginsAndLifecycleTest {

    @Test
    void testManifestParsingPermissionsAndCompatibilityGuard() {
        String validJson = "{\"id\":\"combat_ext\",\"version\":\"1.2.0\","
                + "\"minEngineApiVersion\":\"1.0.0\",\"permissions\":[\"UI\",\"STATE\"]}";
        PluginManifest manifest = PluginManifestValidator.parseManifestJson(validJson);
        assertEquals("combat_ext", manifest.id());
        assertEquals(Set.of(PluginPermission.UI, PluginPermission.STATE), manifest.permissions());
        PluginManifestValidator.assertCompatible(manifest, EngineVersion.VERSION);

        PluginManifest incompatible = new PluginManifest(
                "future_mod", "Future", "1.0", "2.0.0", Set.of(PluginPermission.AUDIO));
        assertThrows(IllegalStateException.class,
                () -> PluginManifestValidator.assertCompatible(incompatible, "1.0.0"));
    }

    @Test
    void testLifecycleFaultIsolationCommandRouterSerializerFallbackAndUiSlots() {
        EngineAPI api = new EngineAPI(EngineConfig.defaults());
        List<String> faults = new ArrayList<>();
        List<String> events = new ArrayList<>();
        PluginLifecycleDispatcher dispatcher = new PluginLifecycleDispatcher(faults::add);

        dispatcher.register(new PluginLifecycleListener() {
            @Override
            public String pluginId() {
                return "broken_plugin";
            }

            @Override
            public void onLoad(EngineAPI a) {
                throw new RuntimeException("boom");
            }

            @Override
            public void onSessionStart(EngineAPI a) {
            }

            @Override
            public void onSceneChange(EngineAPI a, String sceneName) {
            }

            @Override
            public void onDispose(EngineAPI a) {
            }
        });
        dispatcher.register(new PluginLifecycleListener() {
            @Override
            public String pluginId() {
                return "healthy_plugin";
            }

            @Override
            public void onLoad(EngineAPI a) {
                events.add("load");
            }

            @Override
            public void onSessionStart(EngineAPI a) {
                events.add("start");
            }

            @Override
            public void onSceneChange(EngineAPI a, String sceneName) {
                events.add("scene:" + sceneName);
            }

            @Override
            public void onDispose(EngineAPI a) {
                events.add("dispose");
            }
        });

        dispatcher.fireLoad(api);
        dispatcher.fireSessionStart(api);
        dispatcher.fireSceneChange(api, "ch1");
        dispatcher.fireDispose(api);
        assertEquals(1, faults.size());
        assertEquals(List.of("load", "start", "scene:ch1", "dispose"), events);

        // #0797 custom DSL command delegation
        PluginCommandRouter router = new PluginCommandRouter();
        router.register(new PluginCommandHandler() {
            @Override
            public String prefix() {
                return "dice_roll";
            }

            @Override
            public Optional<String> execute(String rawArgs) {
                return Optional.of("rolled:" + rawArgs);
            }
        });
        assertEquals(Optional.of("rolled:2d6"), router.tryDispatch("dice_roll 2d6"));
        assertTrue(router.tryDispatch("unknown_cmd").isEmpty());

        // #0814 custom serializer fallback after unload
        PluginSerializerRegistry serializers = new PluginSerializerRegistry();
        serializers.register(new PluginTypeSerializer() {
            @Override
            public String typeTag() {
                return "CustomQuest";
            }

            @Override
            public Map<String, Object> serialize(Object instance) {
                return Map.of("step", 3);
            }

            @Override
            public Object deserialize(Map<String, Object> payload) {
                return "Quest(" + payload.get("step") + ")";
            }
        });
        assertEquals("Quest(3)", serializers.deserializeOrFallback("CustomQuest", Map.of("step", 3)));
        serializers.unregister("CustomQuest");
        assertTrue(serializers.deserializeOrFallback("CustomQuest", Map.of("step", 3)) instanceof Map<?, ?>);

        // #0806 UI slot priority sorting
        PluginUiSlotRegistry slots = new PluginUiSlotRegistry();
        slots.registerButton(new MenuButtonContribution("p2", "PAUSE_MENU", "Lore", 20));
        slots.registerButton(new MenuButtonContribution("p1", "PAUSE_MENU", "Mods", 5));
        List<MenuButtonContribution> pauseBtns = slots.getButtonsForSlot("PAUSE_MENU");
        assertEquals("Mods", pauseBtns.get(0).label());
        assertEquals("Lore", pauseBtns.get(1).label());
    }

    @Test
    void testRotatingLogsShutdownCoordinatorAndUnknownExtensionPreservation(@TempDir Path tempDir)
            throws IOException {
        Path logFile = tempDir.resolve("logs").resolve("kagayaki.log");
        RotatingLogWriter logger = new RotatingLogWriter(logFile, 80L, 5);
        for (int i = 0; i < 20; i++) {
            logger.appendLine("Log entry line number " + i + " with padding text");
        }
        assertTrue(Files.exists(logFile));
        assertTrue(Files.exists(tempDir.resolve("logs").resolve("kagayaki.log.1")));
        assertFalse(Files.exists(tempDir.resolve("logs").resolve("kagayaki.log.6")));

        ShutdownResourceCoordinator shutdown = new ShutdownResourceCoordinator();
        ExecutorService exec = Executors.newSingleThreadExecutor();
        shutdown.registerExecutor(exec);
        assertTrue(shutdown.shutdownAll(500L));
        assertTrue(exec.isShutdown());

        // #0831 forward-compatible JSON field preservation
        String original = "{\"title\":\"MyGame\",\"futureFlag\":{\"enabled\":true}}";
        String updated = ProjectExtensionsPreserver.mergePreservingUnknownFields(
                original, Map.of("title", "RenamedGame"));
        assertTrue(updated.contains("RenamedGame"));
        assertTrue(updated.contains("futureFlag"));
    }
}
