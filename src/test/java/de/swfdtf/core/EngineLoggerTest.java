package de.swfdtf.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineLoggerTest {

    @Test
    void enforcesRingBufferCapacityAndReturnsRecentEntriesInOrder() {
        EngineLogger logger = new EngineLogger(3);
        logger.setConsoleOutputEnabled(false);

        logger.info("A", "msg1");
        logger.warn("B", "msg2");
        logger.error("C", "msg3");
        logger.debug("D", "msg4");

        assertEquals(3, logger.size());

        List<EngineLogger.LogEntry> all = logger.getRecentEntries(10);
        assertEquals(3, all.size());
        assertEquals("msg2", all.get(0).message());
        assertEquals(EngineLogger.Level.WARN, all.get(0).level());
        assertEquals("msg3", all.get(1).message());
        assertEquals(EngineLogger.Level.ERROR, all.get(1).level());
        assertEquals("msg4", all.get(2).message());
        assertEquals(EngineLogger.Level.DEBUG, all.get(2).level());

        List<EngineLogger.LogEntry> lastTwo = logger.getRecentEntries(2);
        assertEquals(2, lastTwo.size());
        assertEquals("msg3", lastTwo.get(0).message());
        assertEquals("msg4", lastTwo.get(1).message());
    }

    @Test
    void notifiesListenersAndFiltersByMinimumLevel() {
        EngineLogger logger = new EngineLogger(100);
        logger.setConsoleOutputEnabled(false);
        List<EngineLogger.LogEntry> received = new ArrayList<>();
        logger.addListener(received::add);

        logger.setMinimumLevel(EngineLogger.Level.WARN);
        logger.debug("Core", "ignored debug");
        logger.info("Core", "ignored info");
        logger.warn("Core", "kept warn");
        logger.error("Core", "kept error", new IllegalStateException("boom"));

        assertEquals(2, received.size());
        assertEquals(EngineLogger.Level.WARN, received.get(0).level());
        assertTrue(received.get(1).message().contains("IllegalStateException: boom"));
    }

    @Test
    void handlesConcurrentLoggingSafely() throws InterruptedException {
        EngineLogger logger = new EngineLogger(500);
        logger.setConsoleOutputEnabled(false);
        int threads = 8;
        int perThread = 200;
        CountDownLatch latch = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        logger.info("Worker-" + threadId, "entry-" + i);
                    }
                    latch.countDown();
                });
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        }

        assertEquals(500, logger.size());
        assertEquals(500, logger.getRecentEntries(500).size());
    }
}
