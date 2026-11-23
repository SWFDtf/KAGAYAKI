package de.swfdtf.core;

import de.swfdtf.graphics.RenderMode;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.RenderPipeline3DStub;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineApplicationTest {

    @Test
    void headlessSmokeTestRunsDeterministicUpdatesAndTerminatesCleanly() {
        EngineConfig config = EngineConfig.builder()
                .engineMode(EngineMode.STANDALONE_GAME)
                .renderModeName("2D")
                .headless(true)
                .smokeTest(true)
                .build();

        AtomicInteger updates = new AtomicInteger(0);
        try (EngineApplication app = new EngineApplication(config)) {
            app.addUpdatable(dt -> updates.incrementAndGet());
            app.initialize();
            assertInstanceOf(RenderPipeline2D.class, app.getActivePipeline());
            app.stepOnce(1.0 / 30.0); // 2 fixed steps at 1/60
            assertEquals(2, updates.get());
            app.run();
            assertTrue(app.isInitialized());
        }
    }

    @Test
    void studioBridgeLifecycleAnd3DStubSwitchingWorkInHeadlessMode() {
        EngineConfig config = EngineConfig.builder()
                .engineMode(EngineMode.STUDIO_DUAL_WINDOW)
                .renderModeName("3D")
                .headless(true)
                .build();

        AtomicInteger studioInitCount = new AtomicInteger();
        AtomicInteger studioUpdateCount = new AtomicInteger();
        AtomicInteger studioReloadCount = new AtomicInteger();
        AtomicInteger studioDisposeCount = new AtomicInteger();

        StudioBridge bridge = new StudioBridge() {
            @Override
            public void init(EngineAPI api, long studioWindowHandle, long gameWindowHandle) {
                studioInitCount.incrementAndGet();
            }

            @Override
            public void update(double dt) {
                studioUpdateCount.incrementAndGet();
            }

            @Override
            public void render(int windowWidth, int windowHeight) {
            }

            @Override
            public void onReload() {
                studioReloadCount.incrementAndGet();
            }

            @Override
            public void dispose() {
                studioDisposeCount.incrementAndGet();
            }
        };

        try (EngineApplication app = new EngineApplication(config, bridge)) {
            app.initialize();
            assertEquals(1, studioInitCount.get());
            assertInstanceOf(RenderPipeline3DStub.class, app.getActivePipeline());
            assertEquals(RenderMode.MODE_3D, app.getActivePipeline().getMode());

            app.getApi().requestLiveReload();
            app.stepOnce(1.0 / 60.0);
            assertEquals(1, studioReloadCount.get());
            assertEquals(1, studioUpdateCount.get());

            app.switchRenderPipeline(RenderMode.MODE_2D);
            assertEquals(RenderMode.MODE_2D, app.getActivePipeline().getMode());
        }
        assertEquals(1, studioDisposeCount.get());
    }
}
