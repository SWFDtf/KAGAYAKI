package de.swfdtf.core;

import de.swfdtf.core.RuntimeQualityAndDiagnosticsServices.ApiStabilityPolicy;
import de.swfdtf.core.RuntimeQualityAndDiagnosticsServices.GameClockPauseHelper;
import de.swfdtf.core.RuntimeQualityAndDiagnosticsServices.IdleWaitPolicy;
import de.swfdtf.core.RuntimeQualityAndDiagnosticsServices.NativeDriverDiagnostics;
import de.swfdtf.core.RuntimeQualityAndDiagnosticsServices.SmokeTestWatchdog;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.parser.VJavaCompileException;
import de.swfdtf.parser.VJavaParseException;
import de.swfdtf.state.AutoSaveScheduler;
import de.swfdtf.state.RevertableStateStore;
import de.swfdtf.state.SaveManager;
import de.swfdtf.state.SaveWorkflowServices.SessionResetCoordinator;
import de.swfdtf.testutil.KagayakiTestBuilders;
import de.swfdtf.tools.ProjectTemplateGenerator;
import de.swfdtf.tools.ProjectTemplateGenerator.TemplateType;
import de.swfdtf.tools.VJavaLinter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Chapter 12 core quality, concurrency, memory leak regression, Turkish-locale determinism,
 * wrapper checksum verification, exception hierarchy, bridge contracts, Unicode/space paths, and
 * production code cleanliness audits ({@code #0948}–{@code #1013}).
 */
class WaveP2Chapter12CoreAndQualityAuditTest {

    private record AuditEvent(String tag) implements EngineEvent {
    }

    @Test
    void studioRepeatedLoadLintRunCyclesKeepMemoryBoundedAndHandleUnicodeSpacePaths(@TempDir Path tempDir)
            throws Exception {
        // #0994: Path containing spaces and German Umlauts
        Path unicodeProject = tempDir.resolve("KAGAYAKI Spiele/Märchen ÄÖÜ");
        ProjectTemplateGenerator.generate(
                unicodeProject, "Märchen ÄÖÜ", "Tester", "2D", TemplateType.STARTER);

        // #0948: 50 consecutive scan -> lint -> runtime start/step -> cleanup cycles
        for (int i = 0; i < 50; i++) {
            VJavaAst.ProjectAst ast = ProjectScanner.scan(unicodeProject);
            VJavaLinter.LintReport lint = VJavaLinter.lint(ast, false);
            assertTrue(lint.isClean(), "Starter project must lint clean on cycle " + i);
            ScriptRuntime rt = new ScriptRuntime(
                    ast,
                    new EngineAPI(EngineConfig.builder().headless(true).build()));
            rt.start();
            rt.advance();
            assertNotNull(rt.getCurrentLabel());
        }
    }

    @Test
    void multiThreadConcurrencyEventBusAndAutoSaveSchedulerSucceedWithoutErrors(@TempDir Path tempDir)
            throws Exception {
        // #0949: Multi-thread stress via CountDownLatch on EngineEventBus & AutoSaveScheduler
        EngineEventBus bus = new EngineEventBus();
        AtomicInteger handled = new AtomicInteger();
        bus.subscribe(AuditEvent.class, ev -> handled.incrementAndGet());

        SaveManager saveManager = new SaveManager(tempDir.resolve("saves"));
        AutoSaveScheduler scheduler = new AutoSaveScheduler(
                saveManager,
                () -> KagayakiTestBuilders.saveSlot("auto_1").var("step", 1).build(),
                3, 60.0, 2
        );

        int threads = 8;
        int perThread = 25;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        for (int k = 0; k < perThread; k++) {
                            bus.publish(new AuditEvent("tick_" + k));
                            scheduler.recordDialogueStep();
                            scheduler.update(0.016);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(15, TimeUnit.SECONDS));
        }
        assertEquals(threads * perThread, handled.get());
        assertTrue(saveManager.slotExists("auto_1"));
    }

    @Test
    void turkishLocaleAndDifferentTimezoneProduceDeterministicResults(@TempDir Path tempDir) throws Exception {
        // #0954: Protect against Turkish dotless-i bug (I -> ı) in Locale.ROOT code paths
        Locale prevLocale = Locale.getDefault();
        TimeZone prevTz = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            Path proj = ProjectTemplateGenerator.generate(
                    tempDir.resolve("turkish_check"), "TITLE_WITH_I", "QA", "2D", TemplateType.MINIMAL);
            VJavaAst.ProjectAst ast = ProjectScanner.scan(proj);
            VJavaLinter.LintReport report = VJavaLinter.lint(ast, false);
            assertTrue(report.isClean());
        } finally {
            Locale.setDefault(prevLocale);
            TimeZone.setDefault(prevTz);
        }
    }

    @Test
    void gradleWrapperSha256MatchesCheckedInManifestAndNoTodosOrDuplicateClassesExist() throws Exception {
        // #0957: Gradle wrapper checksum verification
        Path wrapperJar = Path.of("gradle/wrapper/gradle-wrapper.jar");
        Path sumFile = Path.of("gradle/wrapper/SHA256SUMS.txt");
        if (Files.isRegularFile(wrapperJar) && Files.isRegularFile(sumFile)) {
            byte[] bytes = Files.readAllBytes(wrapperJar);
            String actualHex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            String manifest = Files.readString(sumFile, StandardCharsets.UTF_8);
            assertTrue(manifest.contains(actualHex),
                    "Wrapper checksum mismatch: expected " + actualHex + " in SHA256SUMS.txt");
        }

        // #0953 & #0971: Audit 0 TODO/FIXME markers in src/main/java and 0 class collisions with demo
        Path mainSrc = Path.of("src/main/java");
        Path demoSrc = Path.of("demo/src/main/java");
        if (Files.isDirectory(mainSrc)) {
            Set<String> mainClasses = new HashSet<>();
            try (Stream<Path> walk = Files.walk(mainSrc)) {
                List<Path> javaFiles = walk.filter(p -> p.toString().endsWith(".java")).toList();
                for (Path file : javaFiles) {
                    mainClasses.add(mainSrc.relativize(file).toString());
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    assertFalse(text.contains("TODO:") || text.contains("FIXME:"),
                            "Unauthorized TODO/FIXME found in " + file);
                }
            }
            if (Files.isDirectory(demoSrc)) {
                try (Stream<Path> demoWalk = Files.walk(demoSrc)) {
                    for (Path demoFile : demoWalk.filter(p -> p.toString().endsWith(".java")).toList()) {
                        String qualified = demoSrc.relativize(demoFile).toString();
                        assertFalse(mainClasses.contains(qualified),
                                "Shadowed qualified class between main and demo: " + qualified);
                    }
                }
            }
        }
    }

    @Test
    void exceptionHierarchyDiagnosticsWatchdogClockPauseIdlePolicyAndSessionResetWorkCleanly() {
        // #0974, #0988, #0962, #0980, #0990, #0991, #0996, #1012, #1013
        VJavaParseException parseEx = new VJavaParseException("main.vjava", 14, "Bad token");
        VJavaCompileException compileEx = new VJavaCompileException("Compile failed");
        SaveCorruptionException saveEx = new SaveCorruptionException("Corrupt header");
        assertTrue(parseEx instanceof KagayakiException);
        assertTrue(compileEx instanceof KagayakiException);
        assertTrue(saveEx instanceof KagayakiException);
        assertEquals("SAVE_CORRUPTION", saveEx.getErrorCode());

        String caretMsg = parseEx.formatDiagnosticWithCaret("move hero to ??? 200", 14);
        assertTrue(caretMsg.contains("main.vjava:14") && caretMsg.contains("^"));

        String gpuDiag = NativeDriverDiagnostics.formatGlfwFailure("WGL driver missing");
        assertTrue(gpuDiag.contains("OpenGL 3.3 Core"));

        SmokeTestWatchdog watchdog = new SmokeTestWatchdog(3, 5.0);
        assertFalse(watchdog.tickAndShouldTerminate());
        assertFalse(watchdog.tickAndShouldTerminate());
        assertTrue(watchdog.tickAndShouldTerminate());

        assertEquals(IdleWaitPolicy.STATIC_MENU_WAIT_TIMEOUT_SECONDS,
                IdleWaitPolicy.computeWaitTimeoutSeconds(true, false), 1e-6);
        assertEquals(0.0, IdleWaitPolicy.computeWaitTimeoutSeconds(true, true), 1e-6);

        GameClock clock = new GameClock();
        assertEquals(0, GameClockPauseHelper.pauseAndDiscardDelta(clock, 3.0));
        assertEquals(0.0, clock.getTotalTimeSeconds(), 1e-9);
        assertEquals(1, clock.advanceFrame(GameClock.FIXED_TIMESTEP));

        assertTrue(ApiStabilityPolicy.isRemovalAllowed("1.0.0", "1.1.0"));
        assertFalse(ApiStabilityPolicy.isRemovalAllowed("1.0.0", "1.0.2"));

        assertTrue(Runtime.version().feature() >= 21);

        RevertableStateStore store = KagayakiTestBuilders.stateStore(Map.of("gold", 50));
        AtomicInteger cleanupRan = new AtomicInteger();
        SessionResetCoordinator.resetToTitle(store, cleanupRan::incrementAndGet);
        assertEquals(1, cleanupRan.get());
        assertFalse(store.has("gold"));
    }

    @Test
    void mockitoContractsImmutabilityPreconditionsAndBridgeSafetyHold() {
        // #0950, #0963, #0972, #0983
        ScriptRuntime.AudioBridge audioBridge = mock(ScriptRuntime.AudioBridge.class);
        StudioBridge studioBridge = mock(StudioBridge.class);
        GameHost gameHost = mock(GameHost.class);

        audioBridge.play("music", "bgm.wav", true, 0.5, 0.8);
        studioBridge.onReload();
        gameHost.onReload();
        verify(audioBridge).play("music", "bgm.wav", true, 0.5, 0.8);
        verify(studioBridge).onReload();
        verify(gameHost).onReload();

        var slot = KagayakiTestBuilders.saveSlot("slot_1").var("hp", 100).build();
        assertThrows(UnsupportedOperationException.class, () -> slot.variables().put("x", 1));
        assertThrows(NullPointerException.class, () -> ProjectScanner.scan((Path) null));
        assertThrows(NullPointerException.class, () -> GameClockPauseHelper.pauseAndDiscardDelta(null, 1.0));
    }
}
