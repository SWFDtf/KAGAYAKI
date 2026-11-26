package de.swfdtf.demo.service;

import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.demo.model.CharacterStatus;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.TextTagParser;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.state.AchievementManager;
import de.swfdtf.state.EndingsManager;
import de.swfdtf.state.GalleryManager;
import de.swfdtf.state.StateStore;
import de.swfdtf.tools.VJavaLinter;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Formatiert die Ausführungsschritte des Schneewittchen-Märchens als übersichtliche,
 * gut lesbare Konsolenausgabe auf Deutsch.
 *
 * <p>Bereinigt Inline-Rich-Text-Tags über {@link TextTagParser#parse(String)} und
 * zeigt Zustand, Bühne, Audio-Cues, Rollback-Prüfungen und Meta-Freischaltungen an.</p>
 */
public final class SnowWhiteConsoleNarrator {

    private int lastChapter = -1;
    private String lastBackground = "";
    private int lastAudioEventCount = 0;

    /** Erstellt einen neuen Konsolen-Erzähler für die Demo. */
    public SnowWhiteConsoleNarrator() {
    }

    /**
     * Gibt das Startbanner mit Projekt-Metadaten aus.
     *
     * @param descriptor Projektbeschreibung aus {@code project.vjava.json}
     */
    public void printBanner(ProjectDescriptor descriptor) {
        System.out.println("================================================================================");
        System.out.println("  vJava / KAGAYAKI Demo-Projekt: " + descriptor.getTitle());
        System.out.println("  Version: " + descriptor.getVersion()
                + " | Auflösung: " + descriptor.getLogicalWidth() + "x" + descriptor.getLogicalHeight()
                + " | Sprache: " + descriptor.getDefaultLanguage());
        System.out.println("================================================================================");
    }

    /**
     * Gibt das Ergebnis des statischen {@link VJavaLinter} aus.
     *
     * @param report Prüfbericht
     */
    public void printLintReport(VJavaLinter.LintReport report) {
        if (report.isClean()) {
            System.out.println("[VJavaLinter] Projektprüfung erfolgreich: 0 Fehler, 0 Warnungen.");
            return;
        }
        System.out.println("[VJavaLinter] Fehler=" + report.errorCount() + ", Warnungen=" + report.warningCount());
        for (VJavaLinter.LintIssue issue : report.issues()) {
            System.out.printf("  - [%s] %s (%s:%d): %s%n",
                    issue.severity(), issue.code(), issue.sourceFile(), issue.lineNumber(), issue.message());
        }
    }

    /**
     * Gibt den aktuellen Schritt (Kapitelwechsel, Bühnenwechsel, Audioereignisse, Dialog oder Menü) aus.
     *
     * @param session aktive {@link ScriptSession}
     * @param step    aktueller Schrittzähler
     */
    public void printCurrentStep(ScriptSession session, int step) {
        StateStore state = session.getStateStore();
        int kapitel = state.getInt("kapitel_nr", 1);
        String kapitelTitel = state.getString("kapitel_titel", "");
        CharacterStatus status = CharacterStatus.fromStateValue(state.getString("schneewittchen_status", "LEBENDIG"));
        int anschlaege = state.getInt("anschlags_zaehler", 0);

        if (kapitel != lastChapter) {
            lastChapter = kapitel;
            System.out.println();
            System.out.println("--------------------------------------------------------------------------------");
            System.out.printf("KAPITEL %d/7: %s%n", kapitel, kapitelTitel);
            System.out.printf("HUD -> Schneewittchen: [%s] | Anschläge: %d/3 | Label: %s%n",
                    status.getLabel(), anschlaege, session.getRuntime().getCurrentLabel());
            System.out.println("--------------------------------------------------------------------------------");
        }

        SceneController.StageSnapshot stage = session.getSceneController().snapshotState();
        String bg = stage.backgroundTag() != null ? stage.backgroundTag() : "";
        if (!Objects.equals(bg, lastBackground) && !bg.isEmpty()) {
            lastBackground = bg;
            String sprites = formatSprites(stage.activeSprites());
            System.out.printf("  [Bühne] Szene: %s | Figuren: %s%n", bg, sprites);
        }

        List<ScriptRuntime.AudioEvent> audioEvents = session.getRuntime().getAudioEvents();
        while (lastAudioEventCount < audioEvents.size()) {
            ScriptRuntime.AudioEvent ev = audioEvents.get(lastAudioEventCount++);
            System.out.printf("  [Audio] %-5s Kanal=%-6s Asset=%s%n",
                    ev.action().toUpperCase(), ev.channel(), ev.assetPath() != null ? ev.assetPath() : "-");
        }

        ScriptRuntime.Status rtStatus = session.getRuntime().getStatus();
        if (rtStatus == ScriptRuntime.Status.WAITING_DIALOGUE) {
            ScriptRuntime.DialoguePrompt dlg = session.getRuntime().getActiveDialogue();
            if (dlg != null) {
                String cleanText = TextTagParser.parse(dlg.interpolatedText()).spokenText();
                String speaker = (dlg.speakerName() != null && !dlg.speakerName().isBlank())
                        ? dlg.speakerName()
                        : "Erzähler";
                System.out.printf("  [%02d | %s] %s: %s%n",
                        step, status.name(), speaker, cleanText);
            }
        } else if (rtStatus == ScriptRuntime.Status.WAITING_MENU) {
            ScriptRuntime.MenuPrompt menu = session.getRuntime().getActiveMenu();
            if (menu != null) {
                System.out.println();
                System.out.println("  [Entscheidung] " + (menu.caption() != null ? menu.caption() : "Wähle eine Option:"));
                for (ScriptRuntime.ChoiceOption opt : menu.options()) {
                    if (opt.visible()) {
                        String cleanChoice = TextTagParser.parse(opt.text()).spokenText();
                        System.out.printf("    (%d) %s%s%n",
                                opt.index() + 1,
                                cleanChoice,
                                opt.enabled() ? "" : " [deaktiviert]");
                    }
                }
            }
        }
    }

    /**
     * Gibt eine Hervorhebung für technische vJava-Feature-Demonstrationen aus (Rollback, Save, Sprache).
     *
     * @param featureName Name der demonstrierten Funktion
     * @param details     Erläuterung des beobachteten Verhaltens
     */
    public void printFeatureDemo(String featureName, String details) {
        System.out.printf("  >>> [vJava-Feature: %s] %s%n", featureName, details);
    }

    /**
     * Gibt die abschließende Zusammenfassung aller freigeschalteten Meta-Daten und Zustände aus.
     *
     * @param session abgeschlossene {@link ScriptSession}
     */
    public void printFinalSummary(ScriptSession session) {
        StateStore state = session.getStateStore();
        AchievementManager achievements = session.getAchievementManager();
        GalleryManager gallery = session.getGalleryManager();
        EndingsManager endings = session.getEndingsManager();

        boolean hasBarrier = session.getStateStore().getCheckpoints().stream()
                .anyMatch(de.swfdtf.state.RevertableStateStore.Checkpoint::rollbackBarrierBefore);

        System.out.println();
        System.out.println("================================================================================");
        System.out.println("  MÄRCHENABSCHLUSS & vJava-ZUSTANDSBERICHT");
        System.out.println("================================================================================");
        System.out.println("  Schneewittchen-Zustand : " + state.getString("schneewittchen_status", "-"));
        System.out.println("  Stiefmutter-Zustand    : " + state.getString("stiefmutter_status", "-"));
        System.out.println("  Rollback-Barriere      : " + hasBarrier + " (gesetzt durch block_rollback in Kapitel 7)");
        System.out.println("  HistoryLog-Einträge    : " + session.getHistoryLog().size());
        System.out.printf("  Achievements           : %d / %d%n",
                achievements.getUnlockedCount(), achievements.getTotalCount());
        System.out.printf("  Galerie-CGs            : %d / %d%n",
                gallery.getUnlockedCount(), gallery.getTotalCount());
        System.out.printf("  Endings                : %d / %d (%.0f%%)%n",
                endings.getUnlockedCount(), endings.getTotalCount(), endings.getCompletionPercentage());
        System.out.println("================================================================================");
    }

    private static String formatSprites(Map<String, SceneController.StageEntry> sprites) {
        if (sprites == null || sprites.isEmpty()) {
            return "keine";
        }
        StringBuilder sb = new StringBuilder();
        for (SceneController.StageEntry entry : sprites.values()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(entry.tag());
            if (!entry.attributes().isEmpty()) {
                sb.append("[").append(String.join(" ", entry.attributes())).append("]");
            }
        }
        return sb.toString();
    }
}
