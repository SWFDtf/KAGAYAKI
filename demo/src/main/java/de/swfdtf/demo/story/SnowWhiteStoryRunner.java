package de.swfdtf.demo.story;

import de.swfdtf.demo.features.SnowWhiteChapterCatalog;
import de.swfdtf.demo.features.SnowWhiteCodexCatalog;
import de.swfdtf.demo.features.SnowWhiteReadingStats;
import de.swfdtf.demo.model.CharacterStatus;
import de.swfdtf.demo.model.StoryCheckpointSummary;
import de.swfdtf.demo.service.SnowWhiteConsoleNarrator;
import de.swfdtf.demo.service.SnowWhiteEngineService;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.state.LocalizationManager;
import de.swfdtf.tools.VJavaLinter;
import de.swfdtf.ui.Screen;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Führt die Schneewittchen-Handlung über {@link ScriptSession} aus und demonstriert
 * dabei alle Kernfunktionen von vJava (Zustände, Menüverzweigung, Rollback/Roll-Forward,
 * Quick-Save/Load, Live-Lokalisierung, {@code block_rollback}, HUD-Screen und Meta-Manager).
 */
public final class SnowWhiteStoryRunner {

    private final SnowWhiteEngineService engineService;
    private final SnowWhiteConsoleNarrator narrator;

    /**
     * Erzeugt einen Story-Runner für das übergebene Projekt.
     *
     * @param engineService konfigurierter {@link SnowWhiteEngineService}
     * @param narrator      Konsolen-Erzähler für die formatierte Ausgabe
     */
    public SnowWhiteStoryRunner(SnowWhiteEngineService engineService, SnowWhiteConsoleNarrator narrator) {
        this.engineService = Objects.requireNonNull(engineService, "engineService darf nicht null sein");
        this.narrator = Objects.requireNonNull(narrator, "narrator darf nicht null sein");
    }

    /**
     * Führt einen vollständigen, deterministischen Demo-Durchlauf aller 7 Kapitel von Schneewittchen aus.
     *
     * @param savesDir temporäres oder projektlokales Verzeichnis für Spielstände
     * @return chronologische Liste der erfassten Kapitel- und Zustands-Checkpoints
     * @throws IOException falls Linting oder Persistenz fehlschlagen
     */
    public List<StoryCheckpointSummary> runAutoDemo(Path savesDir) throws IOException {
        engineService.ensureAssets();
        VJavaLinter.LintReport lint = engineService.runLint();

        try (SnowWhiteEngineService.SessionContext ctx = engineService.createHeadlessSession(savesDir)) {
            narrator.printBanner(ctx.projectAst().descriptor());
            narrator.printLintReport(lint);
            if (lint.hasErrors()) {
                throw new IllegalStateException("VJavaLinter meldet Fehler: " + lint.issues());
            }

            ScriptSession session = ctx.session();
            ScriptRuntime runtime = session.getRuntime();
            session.start();

            Screen hudScreen = engineService.buildHudScreen(session);
            if (hudScreen != null) {
                narrator.printFeatureDemo("ScreenAstBuilder",
                        "Deklarativer Screen '" + hudScreen.getName() + "' (zOrder=" + hudScreen.getZOrder() + ") geladen.");
            }

            List<StoryCheckpointSummary> history = new ArrayList<>();
            SnowWhiteChapterCatalog chapterCatalog = new SnowWhiteChapterCatalog(session.getPersistentState());
            SnowWhiteCodexCatalog codexCatalog = new SnowWhiteCodexCatalog(session.getPersistentState());
            SnowWhiteReadingStats readingStats = new SnowWhiteReadingStats();
            boolean localizationShown = false;
            boolean rollbackShown = false;
            boolean quickSaveShown = false;
            boolean rollbackBarrierShown = false;
            int step = 0;

            while (runtime.getStatus() != ScriptRuntime.Status.ENDED && step < 500) {
                step++;
                ctx.audioEngine().update(1.0 / 60.0);
                session.update(1.0 / 60.0);

                narrator.printCurrentStep(session, step);
                history.add(captureSummary(session, step));
                String currentLabel = runtime.getCurrentLabel();
                if (currentLabel != null && !currentLabel.isBlank()) {
                    chapterCatalog.unlockForLabel(currentLabel);
                    codexCatalog.unlockForLabel(currentLabel);
                    if (runtime.getStatus() == ScriptRuntime.Status.WAITING_DIALOGUE) {
                        readingStats.recordDialogueLine(currentLabel, session.getStateStore().getInt("kapitel_nr", 1));
                    }
                }

                // 1. Live-Lokalisierung am berühmten Zauberspiegel-Satz demonstrieren
                if (!localizationShown
                        && runtime.getStatus() == ScriptRuntime.Status.WAITING_DIALOGUE
                        && "zauberspiegel_befragen".equals(runtime.getCurrentLabel())
                        && session.getStateStore().getInt("spiegel_abfragen", 0) == 2
                        && runtime.getActiveDialogue() != null
                        && "spiegel".equals(runtime.getActiveDialogue().speakerId())) {
                    LocalizationManager loc = session.getLocalizationManager();
                    String deutsch = runtime.getActiveDialogue().interpolatedText();
                    loc.setLanguage("english");
                    String englisch = loc.translateDialogue(
                            runtime.getActiveDialogue().lineId(),
                            runtime.getActiveDialogue().rawText()
                    );
                    loc.setLanguage("deutsch");
                    narrator.printFeatureDemo("LocalizationManager",
                            "Live-Sprachwechsel -> DE: \"" + deutsch + "\" | EN: \"" + englisch + "\"");
                    localizationShown = true;
                }

                // 2. Quick-Save & Quick-Load beim ersten Anschlag demonstrieren
                if (!quickSaveShown
                        && runtime.getStatus() == ScriptRuntime.Status.WAITING_DIALOGUE
                        && "anschlag_1_schnuerriemen".equals(runtime.getCurrentLabel())
                        && "OHNMAECHTIG".equals(session.getStateStore().getString("schneewittchen_status", ""))) {
                    session.quickSave();
                    session.quickLoad();
                    narrator.printFeatureDemo("SaveManager",
                            "Atomares GZIP-JSON Quick-Save & Quick-Load inkl. VJavaUserTypes-Rekonstruktion erfolgreich.");
                    quickSaveShown = true;
                }

                // 3. block_rollback-Barriere in Kapitel 7 prüfen
                if (!rollbackBarrierShown
                        && "kapitel_7_hochzeit_und_ende".equals(runtime.getCurrentLabel())
                        && session.getStateStore().isRollbackBlocked()) {
                    boolean blockedAttempt = session.rollback();
                    narrator.printFeatureDemo("block_rollback",
                            "Rollback-Barriere aktiv! Rücksprungversuch -> erlaubt=" + blockedAttempt);
                    rollbackBarrierShown = true;
                }

                // 4. Weiterblättern oder Menüwahl treffen
                if (runtime.getStatus() == ScriptRuntime.Status.WAITING_DIALOGUE) {
                    session.advance();
                } else if (runtime.getStatus() == ScriptRuntime.Status.WAITING_MENU) {
                    session.choose(0);
                    if (!rollbackShown) {
                        String labelAfterChoice = runtime.getCurrentLabel();
                        boolean rolledBack = session.rollback();
                        boolean rolledForward = session.rollForward();
                        narrator.printFeatureDemo("RevertableStateStore",
                                "Nach Menüwahl ('" + labelAfterChoice + "'): rollback()=" + rolledBack
                                        + ", rollForward()=" + rolledForward
                                        + " -> zurück bei '" + runtime.getCurrentLabel() + "'");
                        rollbackShown = true;
                    }
                } else if (runtime.getStatus() == ScriptRuntime.Status.PAUSING) {
                    session.advance();
                } else if (runtime.getStatus() == ScriptRuntime.Status.WAITING_INPUT) {
                    session.submitInput("");
                }
            }

            session.saveMetaState();
            narrator.printFeatureDemo("SnowWhiteMeta", chapterCatalog.formatChapterOverview());
            narrator.printFeatureDemo("SnowWhiteReadingStats", readingStats.formatSummary(ctx.projectAst()));
            narrator.printFinalSummary(session);
            return List.copyOf(history);
        }
    }

    /**
     * Startet eine interaktive Konsolen-Sitzung, in der der Benutzer Dialoge weiterblättern,
     * Menüentscheidungen wählen sowie Rollback, Quick-Save und Sprachwechsel ausprobieren kann.
     *
     * @param savesDir Verzeichnis für Spielstände
     * @throws IOException falls Konsolen- oder Datei-I/O fehlschlägt
     */
    public void runInteractiveDemo(Path savesDir) throws IOException {
        engineService.ensureAssets();
        Files.createDirectories(savesDir);
        VJavaLinter.LintReport lint = engineService.runLint();

        try (SnowWhiteEngineService.SessionContext ctx = engineService.createHeadlessSession(savesDir);
             BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            narrator.printBanner(ctx.projectAst().descriptor());
            narrator.printLintReport(lint);
            if (lint.hasErrors()) {
                throw new IllegalStateException("VJavaLinter meldet Fehler: " + lint.issues());
            }

            System.out.println("Steuerung: [Enter]=Weiter | [r]=Rollback | [f]=Roll-Forward | [s]=QuickSave | [l]=QuickLoad | [e]=DE/EN | [q]=Beenden");
            ScriptSession session = ctx.session();
            ScriptRuntime runtime = session.getRuntime();
            session.start();

            int step = 0;
            while (runtime.getStatus() != ScriptRuntime.Status.ENDED) {
                step++;
                session.update(1.0 / 60.0);
                narrator.printCurrentStep(session, step);

                if (runtime.getStatus() == ScriptRuntime.Status.WAITING_MENU) {
                    System.out.print("Auswahl-Nummer eingeben (oder q zum Beenden): ");
                    String input = reader.readLine();
                    if (input == null || "q".equalsIgnoreCase(input.trim())) {
                        break;
                    }
                    try {
                        int idx = Integer.parseInt(input.trim()) - 1;
                        session.choose(idx);
                    } catch (RuntimeException ex) {
                        System.out.println("  Ungültige Eingabe, wähle Option 1.");
                        session.choose(0);
                    }
                    continue;
                }

                System.out.print("> ");
                String cmd = reader.readLine();
                if (cmd == null) {
                    break;
                }
                String trimmed = cmd.trim().toLowerCase();
                switch (trimmed) {
                    case "q" -> {
                        return;
                    }
                    case "r" -> narrator.printFeatureDemo("Rollback",
                            session.rollback() ? "Einen Schritt zurückgespult." : "Rollback nicht möglich (Start oder Barriere).");
                    case "f" -> narrator.printFeatureDemo("Roll-Forward",
                            session.rollForward() ? "Einen Schritt vorgespult." : "Kein Roll-Forward-Pfad vorhanden.");
                    case "s" -> {
                        session.quickSave();
                        narrator.printFeatureDemo("Quick-Save", "Spielstand im Quick-Slot gesichert.");
                    }
                    case "l" -> {
                        if (session.getSaveManager().slotExists("quick")) {
                            session.quickLoad();
                            narrator.printFeatureDemo("Quick-Load", "Quick-Slot erfolgreich geladen.");
                        } else {
                            narrator.printFeatureDemo("Quick-Load", "Noch kein Quick-Slot vorhanden.");
                        }
                    }
                    case "e" -> {
                        LocalizationManager loc = session.getLocalizationManager();
                        String nextLang = "deutsch".equalsIgnoreCase(loc.getCurrentLanguage()) ? "english" : "deutsch";
                        loc.setLanguage(nextLang);
                        narrator.printFeatureDemo("Sprache", "Aktive Sprache umgestellt auf: " + nextLang);
                    }
                    default -> session.advance();
                }
            }

            session.saveMetaState();
            narrator.printFinalSummary(session);
        }
    }

    private static StoryCheckpointSummary captureSummary(ScriptSession session, int step) {
        var state = session.getStateStore();
        var runtime = session.getRuntime();
        String bg = session.getSceneController().getBackgroundTag();
        return new StoryCheckpointSummary(
                step,
                state.getInt("kapitel_nr", 1),
                state.getString("kapitel_titel", ""),
                runtime.getCurrentLabel(),
                bg != null ? bg : "",
                CharacterStatus.fromStateValue(state.getString("schneewittchen_status", "LEBENDIG")),
                state.getInt("anschlags_zaehler", 0)
        );
    }
}
