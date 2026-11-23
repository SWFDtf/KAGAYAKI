package de.swfdtf.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineAPITest {

    private interface SampleService {
        String ping();
    }

    @Test
    void exposesMetadataLifecycleFlagsPropertiesAndServiceRegistry() {
        EngineConfig config = EngineConfig.defaults();
        EngineAPI api = new EngineAPI(config);
        api.getLogger().setConsoleOutputEnabled(false);

        assertEquals("KAGAYAKI 1.0.0", api.getEngineVersion());
        assertEquals("LWJGL 3 / OpenGL 3.3 Core", api.getRenderBackendName());
        assertSame(config, api.getConfig());

        api.logInfo("Hello engine");
        api.logWarn("Warning engine");
        api.logError("Error engine");
        assertEquals(3, api.getLogger().size());

        assertFalse(api.isQuitRequested());
        api.requestQuit();
        assertTrue(api.isQuitRequested());

        assertFalse(api.consumeLiveReloadRequested());
        api.requestLiveReload();
        assertTrue(api.consumeLiveReloadRequested());
        assertFalse(api.consumeLiveReloadRequested());

        api.setProperty("spieler_name", "Kaito");
        assertEquals("Kaito", api.getProperty("spieler_name"));
        api.setProperty("spieler_name", null);
        assertNull(api.getProperty("spieler_name"));

        SampleService svc = () -> "pong";
        api.registerService(SampleService.class, svc);
        assertTrue(api.hasService(SampleService.class));
        assertSame(svc, api.getService(SampleService.class));
        assertEquals("pong", api.requireService(SampleService.class).ping());

        api.unregisterService(SampleService.class);
        assertNull(api.getService(SampleService.class));
        assertThrows(IllegalStateException.class, () -> api.requireService(SampleService.class));
    }
}
