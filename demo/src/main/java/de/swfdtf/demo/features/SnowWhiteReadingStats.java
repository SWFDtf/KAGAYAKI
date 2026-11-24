package de.swfdtf.demo.features;

import de.swfdtf.parser.VJavaAst;
import de.swfdtf.tools.ScriptWordCountAnalyzer;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Erfasst veränderliche Laufzeit-Lesestatistiken (gelesene Dialogzeilen, getroffene
 * Menüentscheidungen und besuchte Kapitel) und verknüpft sie mit der statischen
 * Skriptanalyse von {@link ScriptWordCountAnalyzer}.
 */
public final class SnowWhiteReadingStats {

    private final ScriptWordCountAnalyzer analyzer;
    private final Set<Integer> visitedChapters = new TreeSet<>();
    private int linesRead;
    private int choicesTaken;

    /**
     * Erzeugt eine neue {@link SnowWhiteReadingStats}-Instanz mit Standard-{@link ScriptWordCountAnalyzer}.
     */
    public SnowWhiteReadingStats() {
        this(new ScriptWordCountAnalyzer());
    }

    /**
     * Erzeugt eine {@link SnowWhiteReadingStats}-Instanz mit explizitem {@link ScriptWordCountAnalyzer}.
     *
     * @param analyzer zu verwendender Wortzahl-Analysator (darf nicht {@code null} sein)
     */
    public SnowWhiteReadingStats(ScriptWordCountAnalyzer analyzer) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer darf nicht null sein");
    }

    /**
     * Protokolliert eine gelesene Dialogzeile inklusive aktuellem Skript-Label und Kapitelnummer.
     *
     * @param label         aktuelles {@code .vjava}-Label (darf nicht {@code null} oder leer sein)
     * @param chapterNumber zugehörige Kapitelnummer ({@code >= 1})
     * @throws IllegalArgumentException falls {@code label} leer oder {@code chapterNumber < 1} ist
     */
    public synchronized void recordDialogueLine(String label, int chapterNumber) {
        Objects.requireNonNull(label, "label darf nicht null sein");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label darf nicht leer sein");
        }
        if (chapterNumber < 1) {
            throw new IllegalArgumentException("chapterNumber muss >= 1 sein, war: " + chapterNumber);
        }
        linesRead++;
        visitedChapters.add(chapterNumber);
    }

    /**
     * Protokolliert eine getroffene Auswahl in einem Skript-Menü.
     */
    public synchronized void recordChoice() {
        choicesTaken++;
    }

    /**
     * Liefert die bisher gelesene Anzahl an Dialogzeilen.
     *
     * @return Anzahl gelesener Dialogzeilen
     */
    public synchronized int getLinesRead() {
        return linesRead;
    }

    /**
     * Liefert die bisher getroffene Anzahl an Menüentscheidungen.
     *
     * @return Anzahl getroffener Entscheidungen
     */
    public synchronized int getChoicesTaken() {
        return choicesTaken;
    }

    /**
     * Liefert eine unveränderliche, aufsteigend sortierte Menge aller bisher besuchten Kapitelnummern.
     *
     * @return unveränderliche Menge besuchter Kapitelnummern
     */
    public synchronized Set<Integer> getVisitedChapters() {
        return Collections.unmodifiableSet(new TreeSet<>(visitedChapters));
    }

    /**
     * Analysiert das übergebene {@link VJavaAst.ProjectAst} statisch mithilfe von
     * {@link ScriptWordCountAnalyzer}.
     *
     * @param projectAst geparster Projekt-AST (darf nicht {@code null} sein)
     * @return unveränderlicher {@link ScriptWordCountAnalyzer.ScriptStatsReport}
     */
    public ScriptWordCountAnalyzer.ScriptStatsReport analyzeProject(VJavaAst.ProjectAst projectAst) {
        Objects.requireNonNull(projectAst, "projectAst darf nicht null sein");
        return analyzer.analyze(projectAst);
    }

    /**
     * Erzeugt eine kombinierte Zusammenfassung aus bisherigen Laufzeit-Lesezählern und den
     * statischen Projekt-Metriken des {@link VJavaAst.ProjectAst}.
     *
     * @param projectAst geparster Projekt-AST (darf nicht {@code null} sein)
     * @return formatierte Lese- und Skriptstatistik
     */
    public synchronized String formatSummary(VJavaAst.ProjectAst projectAst) {
        Objects.requireNonNull(projectAst, "projectAst darf nicht null sein");
        ScriptWordCountAnalyzer.ScriptStatsReport report = analyzeProject(projectAst);
        double dialogueCoveragePercent = report.totalLines() == 0
                ? 0.0
                : Math.min(100.0, (linesRead * 100.0) / report.totalLines());

        StringBuilder sb = new StringBuilder();
        sb.append("Schneewittchen-Lesestatistik:").append(System.lineSeparator());
        sb.append(String.format(
                Locale.ROOT,
                "  Laufzeit: %d Zeilen gelesen, %d Entscheidungen getroffen, besuchte Kapitel: %s%n",
                linesRead,
                choicesTaken,
                getVisitedChapters()
        ));
        sb.append(String.format(
                Locale.ROOT,
                "  Skript-Umfang: %d Zeilen, %d Wörter, %d Zeichen, geschätzte Lesezeit: %.2f Min. (Abdeckung: %.1f%%)%n",
                report.totalLines(),
                report.totalWords(),
                report.totalCharacters(),
                report.estimatedReadingMinutes(),
                dialogueCoveragePercent
        ));
        if (!report.perSpeaker().isEmpty()) {
            sb.append("  Sprecher-Verteilung:").append(System.lineSeparator());
            for (Map.Entry<String, ScriptWordCountAnalyzer.SpeakerStats> entry : report.perSpeaker().entrySet()) {
                ScriptWordCountAnalyzer.SpeakerStats stats = entry.getValue();
                sb.append(String.format(
                        Locale.ROOT,
                        "    - %s: %d Zeilen, %d Wörter%n",
                        stats.speakerId(),
                        stats.lineCount(),
                        stats.wordCount()
                ));
            }
        }
        return sb.toString();
    }
}
