package de.swfdtf.demo.qa;

import de.swfdtf.demo.service.SnowWhiteEngineService;
import de.swfdtf.parser.ScriptProfiler;
import de.swfdtf.parser.ScriptProfiler.LabelProfile;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.TypeSafeStateView;
import de.swfdtf.parser.VariableWatchpointManager;
import de.swfdtf.parser.WatchpointEvent;
import de.swfdtf.state.RevertableStateStore;
import de.swfdtf.tools.DiagnosticBundleExporter;
import de.swfdtf.tools.StateSnapshotDiffService;
import de.swfdtf.tools.StateSnapshotDiffService.StateDiff;
import de.swfdtf.tools.VJavaLinter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Führt eine vollständige Headless-Sitzung des Schneewittchen-Projekts unter Profiling
 * ({@link ScriptProfiler}) und Variablen-Überwachung ({@link VariableWatchpointManager}) aus,
 * liest typisierte Zustandswerte über {@link TypeSafeStateView}, berechnet das Zustands-Delta
 * über {@link StateSnapshotDiffService} und exportiert ein JSON-Diagnosebundle über
 * {@link DiagnosticBundleExporter}.
 */
public final class SnowWhiteDiagnosticExporter {

    /** Standard-Obergrenze für profilierte Interaktionsschritte. */
    public static final int DEFAULT_MAX_STEPS = 1000;

    private static final double PAUSE_STEP_EPSILON_SECONDS = 0.001;
    private static final int TOP_PROFILED_LABELS_LIMIT = 10;

    /**
     * Überwachte Schlüsselvariablen: enthält sowohl die im Schneewittchen-Skript definierten
     * Zustandsvariablen als auch die kanonischen Diagnose-Schlüsselnamen.
     */
    private static final List<String> WATCHED_VARIABLES = List.of(
            "schneewittchen_zustand",
            "koenigin_zustand",
            "anschlag_zaehler",
            "schneewittchen_status",
            "stiefmutter_status",
            "anschlags_zaehler",
            "kapitel_nr",
            "spiegel_abfragen"
    );

    private final DiagnosticBundleExporter bundleExporter;
    private final StateSnapshotDiffService diffService;
    private final int maxSteps;

    /**
     * Erzeugt einen {@code SnowWhiteDiagnosticExporter} mit Standardkomponenten.
     */
    public SnowWhiteDiagnosticExporter() {
        this(new DiagnosticBundleExporter(), new StateSnapshotDiffService(), DEFAULT_MAX_STEPS);
    }

    /**
     * Erzeugt einen {@code SnowWhiteDiagnosticExporter} mit injizierten Export- und Diff-Diensten.
     *
     * @param bundleExporter JSON-Diagnose-Exporter (darf nicht {@code null} sein)
     * @param diffService    Dienst zum Vergleichen von Zustands-Snapshots (darf nicht {@code null} sein)
     */
    public SnowWhiteDiagnosticExporter(
            DiagnosticBundleExporter bundleExporter,
            StateSnapshotDiffService diffService
    ) {
        this(bundleExporter, diffService, DEFAULT_MAX_STEPS);
    }

    /**
     * Erzeugt einen {@code SnowWhiteDiagnosticExporter} mit konfigurierbarer Schrittgrenze.
     *
     * @param bundleExporter JSON-Diagnose-Exporter (darf nicht {@code null} sein)
     * @param diffService    Dienst zum Vergleichen von Zustands-Snapshots (darf nicht {@code null} sein)
     * @param maxSteps       maximale Anzahl auszuführender Schritte ({@code > 0})
     */
    public SnowWhiteDiagnosticExporter(
            DiagnosticBundleExporter bundleExporter,
            StateSnapshotDiffService diffService,
            int maxSteps
    ) {
        this.bundleExporter = Objects.requireNonNull(bundleExporter, "bundleExporter darf nicht null sein");
        this.diffService = Objects.requireNonNull(diffService, "diffService darf nicht null sein");
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps muss > 0 sein, erhalten: " + maxSteps);
        }
        this.maxSteps = maxSteps;
    }

    /**
     * Führt eine profilierte Headless-Sitzung für {@code projectDir} aus, überwacht zentrale
     * Schneewittchen-Variablen, vergleicht Anfangs- und Endzustand und schreibt das strukturierte
     * Diagnose-JSON atomar nach {@code targetJson}.
     *
     * @param projectDir Wurzelverzeichnis des KAGAYAKI-{@code .vjava}-Projekts (darf nicht {@code null} sein)
     * @param savesDir   Verzeichnis für temporäre Spielstände und Persistenz (darf nicht {@code null} sein)
     * @param targetJson Zieldateipfad für das Diagnose-JSON-Bundle (darf nicht {@code null} sein)
     * @return normalisierter Pfad zur geschriebenen JSON-Diagnosedatei
     * @throws IOException              falls Sitzungsinitialisierung oder JSON-Export fehlschlägt
     * @throws IllegalArgumentException falls {@code projectDir} kein gültiges Verzeichnis ist
     */
    public Path exportProfiledDiagnostics(Path projectDir, Path savesDir, Path targetJson) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir darf nicht null sein");
        Objects.requireNonNull(savesDir, "savesDir darf nicht null sein");
        Objects.requireNonNull(targetJson, "targetJson darf nicht null sein");

        Path normalizedProject = projectDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedProject)) {
            throw new IllegalArgumentException("Projektverzeichnis existiert nicht oder ist kein Verzeichnis: " + normalizedProject);
        }
        Path normalizedSaves = savesDir.toAbsolutePath().normalize();
        Path normalizedTarget = targetJson.toAbsolutePath().normalize();
        Files.createDirectories(normalizedSaves);

        SnowWhiteEngineService engineService = new SnowWhiteEngineService(normalizedProject);
        try (SnowWhiteEngineService.SessionContext ctx = engineService.createHeadlessSession(normalizedSaves)) {
            VJavaLinter.LintReport lintReport = engineService.runLint();
            ScriptSession session = ctx.session();
            ScriptRuntime runtime = session.getRuntime();
            RevertableStateStore stateStore = session.getStateStore();

            ScriptProfiler profiler = new ScriptProfiler();
            VariableWatchpointManager watchpoints = new VariableWatchpointManager();
            TypeSafeStateView stateView = new TypeSafeStateView(stateStore);

            for (String watchedKey : WATCHED_VARIABLES) {
                watchpoints.watchKey(watchedKey, event -> { });
            }

            profiler.recordTimed("start", 0, session::start);
            Map<String, Object> initialSnapshot = new LinkedHashMap<>(stateStore.snapshotMap());
            watchpoints.checkAll(stateStore, safeLabel(runtime));

            int stepsExecuted = 0;
            while (runtime.getStatus() != ScriptRuntime.Status.ENDED && stepsExecuted < maxSteps) {
                String currentLabel = safeLabel(runtime);
                int stmtIndex = runtime.getStatementIndex();
                profiler.recordTimed(currentLabel, stmtIndex, () -> executeStep(session, runtime));
                watchpoints.checkAll(stateStore, currentLabel);
                stepsExecuted++;
            }

            Map<String, Object> finalSnapshot = new LinkedHashMap<>(stateStore.snapshotMap());
            StateDiff stateDiff = diffService.diff(initialSnapshot, finalSnapshot);

            Map<String, Object> extraMetrics = buildMetrics(
                    stepsExecuted,
                    runtime,
                    profiler,
                    watchpoints,
                    stateView,
                    stateDiff
            );

            return bundleExporter.export(normalizedTarget, ctx.api(), lintReport, extraMetrics);
        }
    }

    private static void executeStep(ScriptSession session, ScriptRuntime runtime) {
        switch (runtime.getStatus()) {
            case WAITING_DIALOGUE -> session.advance();
            case WAITING_MENU -> session.choose(0);
            case WAITING_INPUT -> session.submitInput("");
            case PAUSING -> {
                double remaining = runtime.getPauseRemainingSeconds();
                if (remaining > 0.0) {
                    session.update(remaining + PAUSE_STEP_EPSILON_SECONDS);
                } else {
                    session.advance();
                }
            }
            case READY -> session.advance();
            case ENDED -> {
                // Laufzeit hat das Skriptende bereits erreicht.
            }
        }
    }

    private static Map<String, Object> buildMetrics(
            int stepsExecuted,
            ScriptRuntime runtime,
            ScriptProfiler profiler,
            VariableWatchpointManager watchpoints,
            TypeSafeStateView stateView,
            StateDiff stateDiff
    ) {
        Map<String, Object> metrics = new TreeMap<>();
        metrics.put("execution.stepsExecuted", stepsExecuted);
        metrics.put("execution.endedCleanly", runtime.getStatus() == ScriptRuntime.Status.ENDED);
        metrics.put("execution.finalLabel", safeLabel(runtime));

        metrics.put("profiler.hottestLabel", profiler.hottestLabel().map(LabelProfile::label).orElse(""));
        metrics.put("profiler.report", profiler.formatReport());
        metrics.put("profiler.topLabels", serializeTopLabels(profiler.topLabels(TOP_PROFILED_LABELS_LIMIT)));

        List<WatchpointEvent> events = watchpoints.getRecentHistory();
        metrics.put("watchpoints.watchedKeys", WATCHED_VARIABLES);
        metrics.put("watchpoints.eventCount", events.size());
        metrics.put("watchpoints.events", serializeWatchpointEvents(events));

        metrics.put("state.typedValues", extractTypedStateValues(stateView));
        metrics.put("state.diff.totalChanges", stateDiff.totalChanges());
        metrics.put("state.diff.addedKeys", List.copyOf(stateDiff.added().keySet()));
        metrics.put("state.diff.removedKeys", List.copyOf(stateDiff.removed().keySet()));
        metrics.put("state.diff.modifiedKeys", List.copyOf(stateDiff.modified().keySet()));
        metrics.put("state.diff.summary", stateDiff.formatSummary());
        return metrics;
    }

    private static List<Map<String, Object>> serializeTopLabels(List<LabelProfile> profiles) {
        List<Map<String, Object>> list = new ArrayList<>(profiles.size());
        for (LabelProfile profile : profiles) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", profile.label());
            row.put("hitCount", profile.hitCount());
            row.put("totalMillis", profile.totalMillis());
            row.put("averageMillis", profile.averageMillis());
            row.put("maxNanos", profile.maxNanos());
            list.add(row);
        }
        return list;
    }

    private static List<Map<String, Object>> serializeWatchpointEvents(List<WatchpointEvent> events) {
        List<Map<String, Object>> list = new ArrayList<>(events.size());
        for (WatchpointEvent event : events) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", event.key());
            row.put("previousValue", event.previousOptional().map(String::valueOf).orElse(null));
            row.put("newValue", event.newOptional().map(String::valueOf).orElse(null));
            row.put("sourceContext", event.sourceContext());
            list.add(row);
        }
        return list;
    }

    private static Map<String, Object> extractTypedStateValues(TypeSafeStateView stateView) {
        Map<String, Object> typed = new TreeMap<>();
        Optional<String> snowWhiteState = stateView.getString("schneewittchen_status")
                .or(() -> stateView.getString("schneewittchen_zustand"));
        Optional<String> queenState = stateView.getString("stiefmutter_status")
                .or(() -> stateView.getString("koenigin_zustand"));
        Optional<Integer> attackCount = stateView.getInt("anschlags_zaehler")
                .or(() -> stateView.getInt("anschlag_zaehler"));
        Optional<Integer> chapterNr = stateView.getInt("kapitel_nr");
        Optional<String> chapterTitle = stateView.getString("kapitel_titel");
        Optional<Integer> mirrorQueries = stateView.getInt("spiegel_abfragen");
        Optional<Boolean> cautionBonus = stateView.getBoolean("vorsicht_bonus");
        Optional<Boolean> protectionPromise = stateView.getBoolean("hat_schutzversprechen");

        snowWhiteState.ifPresent(val -> typed.put("schneewittchen_status", val));
        queenState.ifPresent(val -> typed.put("stiefmutter_status", val));
        attackCount.ifPresent(val -> typed.put("anschlags_zaehler", val));
        chapterNr.ifPresent(val -> typed.put("kapitel_nr", val));
        chapterTitle.ifPresent(val -> typed.put("kapitel_titel", val));
        mirrorQueries.ifPresent(val -> typed.put("spiegel_abfragen", val));
        cautionBonus.ifPresent(val -> typed.put("vorsicht_bonus", val));
        protectionPromise.ifPresent(val -> typed.put("hat_schutzversprechen", val));
        return typed;
    }

    private static String safeLabel(ScriptRuntime runtime) {
        String label = runtime.getCurrentLabel();
        return (label != null && !label.isBlank()) ? label : "<unbekannt>";
    }
}
