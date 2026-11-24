package de.swfdtf.demo.qa;

import de.swfdtf.tools.AutomatedPlaythroughRunner;
import de.swfdtf.tools.AutomatedPlaythroughRunner.ChoiceStrategy;
import de.swfdtf.tools.AutomatedPlaythroughRunner.PlaythroughReport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Automatisierte Durchlauf-Testsuite für das Schneewittchen-{@code .vjava}-Projekt,
 * die sämtliche Auswahlstrategien ({@link ChoiceStrategy#FIRST_CHOICE},
 * {@link ChoiceStrategy#LAST_CHOICE} und {@link ChoiceStrategy#ROUND_ROBIN}) über
 * {@link AutomatedPlaythroughRunner} ausführt und die Ergebnisse zusammenführt.
 */
public final class SnowWhitePlaythroughSuite {

    /** Standard-Obergrenze für Interaktionsschritte pro automatischem Durchlauf. */
    public static final int DEFAULT_MAX_STEPS = 2000;

    private static final List<ChoiceStrategy> STRATEGIES = List.of(
            ChoiceStrategy.FIRST_CHOICE,
            ChoiceStrategy.LAST_CHOICE,
            ChoiceStrategy.ROUND_ROBIN
    );

    private final AutomatedPlaythroughRunner runner;
    private final int defaultMaxSteps;

    /**
     * Unveränderlicher Gesamtbericht über alle ausgeführten Auswahlstrategien.
     *
     * @param reports               unveränderliche Zuordnung von {@link ChoiceStrategy} zum jeweiligen {@link PlaythroughReport}
     * @param combinedVisitedLabels unveränderliche Vereinigungsmenge aller besuchten Skript-Labels
     * @param allEndedCleanly       {@code true}, wenn alle Strategien sauber mit {@code ENDED} abgeschlossen haben
     */
    public record CombinedPlaythroughReport(
            Map<ChoiceStrategy, PlaythroughReport> reports,
            Set<String> combinedVisitedLabels,
            boolean allEndedCleanly
    ) {

        /**
         * Validiert Eingaben und kapselt Sammlungen unveränderlich.
         */
        public CombinedPlaythroughReport {
            Objects.requireNonNull(reports, "reports darf nicht null sein");
            EnumMap<ChoiceStrategy, PlaythroughReport> copy = new EnumMap<>(ChoiceStrategy.class);
            for (Map.Entry<ChoiceStrategy, PlaythroughReport> entry : reports.entrySet()) {
                ChoiceStrategy strategy = Objects.requireNonNull(entry.getKey(), "Strategie-Schlüssel darf nicht null sein");
                PlaythroughReport report = Objects.requireNonNull(entry.getValue(), "PlaythroughReport darf nicht null sein");
                copy.put(strategy, report);
            }
            reports = Collections.unmodifiableMap(copy);

            Objects.requireNonNull(combinedVisitedLabels, "combinedVisitedLabels darf nicht null sein");
            Set<String> labelsCopy = new LinkedHashSet<>(combinedVisitedLabels.size());
            for (String label : combinedVisitedLabels) {
                labelsCopy.add(Objects.requireNonNull(label, "combinedVisitedLabels darf keine null-Einträge enthalten"));
            }
            combinedVisitedLabels = Collections.unmodifiableSet(labelsCopy);
        }

        /**
         * Liefert den {@link PlaythroughReport} für eine bestimmte Strategie, sofern vorhanden.
         *
         * @param strategy gewünschte Auswahlstrategie (darf nicht {@code null} sein)
         * @return optionaler {@link PlaythroughReport}
         */
        public Optional<PlaythroughReport> reportFor(ChoiceStrategy strategy) {
            Objects.requireNonNull(strategy, "strategy darf nicht null sein");
            return Optional.ofNullable(reports.get(strategy));
        }
    }

    /**
     * Erzeugt eine {@code SnowWhitePlaythroughSuite} mit Standard-{@link AutomatedPlaythroughRunner}
     * und {@link #DEFAULT_MAX_STEPS}.
     */
    public SnowWhitePlaythroughSuite() {
        this(new AutomatedPlaythroughRunner(), DEFAULT_MAX_STEPS);
    }

    /**
     * Erzeugt eine {@code SnowWhitePlaythroughSuite} mit explizitem Runner und {@link #DEFAULT_MAX_STEPS}.
     *
     * @param runner zu verwendender {@link AutomatedPlaythroughRunner} (darf nicht {@code null} sein)
     */
    public SnowWhitePlaythroughSuite(AutomatedPlaythroughRunner runner) {
        this(runner, DEFAULT_MAX_STEPS);
    }

    /**
     * Erzeugt eine {@code SnowWhitePlaythroughSuite} mit explizitem Runner und konfigurierter Schrittgrenze.
     *
     * @param runner          zu verwendender {@link AutomatedPlaythroughRunner} (darf nicht {@code null} sein)
     * @param defaultMaxSteps maximale Interaktionsschritte pro Strategie ({@code > 0})
     */
    public SnowWhitePlaythroughSuite(AutomatedPlaythroughRunner runner, int defaultMaxSteps) {
        this.runner = Objects.requireNonNull(runner, "runner darf nicht null sein");
        if (defaultMaxSteps <= 0) {
            throw new IllegalArgumentException("defaultMaxSteps muss > 0 sein, erhalten: " + defaultMaxSteps);
        }
        this.defaultMaxSteps = defaultMaxSteps;
    }

    /**
     * Führt alle drei Auswahlstrategien ({@link ChoiceStrategy#FIRST_CHOICE},
     * {@link ChoiceStrategy#LAST_CHOICE}, {@link ChoiceStrategy#ROUND_ROBIN}) gegen das Projekt
     * unter {@code projectDir} aus.
     *
     * @param projectDir Wurzelverzeichnis des KAGAYAKI-{@code .vjava}-Projekts (darf nicht {@code null} sein)
     * @return unveränderlicher {@link CombinedPlaythroughReport}
     * @throws IllegalArgumentException falls {@code projectDir} kein existierendes Verzeichnis ist
     */
    public CombinedPlaythroughReport runAllStrategies(Path projectDir) {
        return runAllStrategies(projectDir, defaultMaxSteps);
    }

    /**
     * Führt alle drei Auswahlstrategien mit einer expliziten Schritt-Obergrenze aus.
     *
     * @param projectDir Wurzelverzeichnis des KAGAYAKI-{@code .vjava}-Projekts (darf nicht {@code null} sein)
     * @param maxSteps   maximale Interaktionsschritte pro Strategie ({@code > 0})
     * @return unveränderlicher {@link CombinedPlaythroughReport}
     * @throws IllegalArgumentException falls {@code projectDir} ungültig oder {@code maxSteps <= 0} ist
     */
    public CombinedPlaythroughReport runAllStrategies(Path projectDir, int maxSteps) {
        Objects.requireNonNull(projectDir, "projectDir darf nicht null sein");
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps muss > 0 sein, erhalten: " + maxSteps);
        }
        Path normalizedDir = projectDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedDir)) {
            throw new IllegalArgumentException("Projektverzeichnis existiert nicht oder ist kein Verzeichnis: " + normalizedDir);
        }

        EnumMap<ChoiceStrategy, PlaythroughReport> reports = new EnumMap<>(ChoiceStrategy.class);
        Set<String> combinedLabels = new LinkedHashSet<>();
        boolean allClean = true;

        for (ChoiceStrategy strategy : STRATEGIES) {
            PlaythroughReport report = runner.run(normalizedDir, strategy, maxSteps);
            if (report == null) {
                throw new IllegalStateException("AutomatedPlaythroughRunner lieferte null für Strategie " + strategy);
            }
            reports.put(strategy, report);
            combinedLabels.addAll(report.visitedLabels());
            if (!report.endedCleanly()) {
                allClean = false;
            }
        }

        return new CombinedPlaythroughReport(reports, combinedLabels, allClean);
    }
}
