package de.swfdtf.demo;

import de.swfdtf.core.EngineMode;
import de.swfdtf.demo.qa.SnowWhiteFlowAndTranslationReport;
import de.swfdtf.demo.qa.SnowWhiteShowcaseCli;
import de.swfdtf.demo.service.SnowWhiteConsoleNarrator;
import de.swfdtf.demo.service.SnowWhiteEngineService;
import de.swfdtf.demo.story.SnowWhiteStoryRunner;
import de.swfdtf.tools.ExportPreFlightChecker;
import de.swfdtf.tools.StandaloneBuilder;
import de.swfdtf.tools.VJavaLinter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Einstiegspunkt für das Schneewittchen-vJava-Demo-Projekt.
 *
 * <p>Unterstützte Aufrufparameter:
 * <ul>
 *   <li>{@code --auto} (Standard): Führt das komplette Märchen automatisch in der Konsole aus.</li>
 *   <li>{@code --interactive}: Interaktive Konsolensteuerung mit Rollback, Quick-Save und Sprachwechsel.</li>
 *   <li>{@code --studio}: Startet das KAGAYAKI Dual-Window-Studio mit dem Schneewittchen-Projekt.</li>
 *   <li>{@code --standalone}: Startet das KAGAYAKI Standalone-Spielfenster.</li>
 *   <li>{@code --lint}: Prüft das {@code .vjava}-Projekt statisch mit {@link VJavaLinter}.</li>
 *   <li>{@code --generate-assets}: Erzeugt alle prozeduralen PNG- und WAV-Assets.</li>
 *   <li>{@code --export <zielordner>}: Exportiert das Projekt als {@code .vpak}-Bundle.</li>
 *   <li>{@code --showcase <zielordner>}: Erzeugt Flow-Graph, Audit, Diagnostik und Asset-Manifest.</li>
 *   <li>{@code --preflight}: Führt die Release-Vorabprüfung ({@link ExportPreFlightChecker}) aus.</li>
 *   <li>{@code --flow-graph}: Gibt den Mermaid-Flowchart des Schneewittchen-Skripts aus.</li>
 *   <li>{@code --project <ordner>}: Alternativer Pfad zum {@code .vjava}-Projekt (Standard: {@code project}).</li>
 * </ul>
 */
public final class Main {

    private Main() {
    }

    /**
     * Programmstart.
     *
     * @param args Kommandozeilenargumente
     * @throws IOException falls Dateizugriffe fehlschlagen
     */
    public static void main(String[] args) throws IOException {
        Path projectDir = resolveDefaultProjectDir();
        String mode = "--auto";
        Path exportDir = null;
        Path showcaseDir = null;

        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--project" -> {
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException("Pfad nach --project fehlt.");
                        }
                        projectDir = Path.of(args[++i]);
                    }
                    case "--export" -> {
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException("Zielverzeichnis nach --export fehlt.");
                        }
                        mode = "--export";
                        exportDir = Path.of(args[++i]);
                    }
                    case "--showcase" -> {
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException("Zielverzeichnis nach --showcase fehlt.");
                        }
                        mode = "--showcase";
                        showcaseDir = Path.of(args[++i]);
                    }
                    case "--auto", "--interactive", "--studio", "--standalone",
                            "--lint", "--generate-assets", "--preflight", "--flow-graph" ->
                            mode = arg;
                    default -> throw new IllegalArgumentException("Unbekanntes Argument: " + arg);
                }
            }
        }

        SnowWhiteEngineService service = new SnowWhiteEngineService(projectDir);
        SnowWhiteConsoleNarrator narrator = new SnowWhiteConsoleNarrator();

        switch (mode) {
            case "--generate-assets" -> {
                service.ensureAssets();
                System.out.println("[Assets] Prozedurale PNG- und WAV-Dateien unter "
                        + service.getProjectDir().resolve("assets") + " bereitgestellt.");
            }
            case "--lint" -> {
                service.ensureAssets();
                VJavaLinter.LintReport report = service.runLint();
                narrator.printLintReport(report);
                if (report.hasErrors()) {
                    System.exit(1);
                }
            }
            case "--preflight" -> {
                service.ensureAssets();
                ExportPreFlightChecker.PreFlightReport preFlight =
                        new ExportPreFlightChecker().check(service.getProjectDir());
                System.out.println(preFlight.summaryMarkdown());
                if (!preFlight.readyForRelease()) {
                    System.exit(1);
                }
            }
            case "--flow-graph" -> {
                service.ensureAssets();
                SnowWhiteFlowAndTranslationReport.AuditBundle audit =
                        new SnowWhiteFlowAndTranslationReport().analyze(service.getProjectDir());
                System.out.println(audit.mermaidDiagram());
            }
            case "--showcase" -> {
                service.ensureAssets();
                SnowWhiteShowcaseCli.ShowcaseOutput output =
                        new SnowWhiteShowcaseCli().generateShowcase(service.getProjectDir(), showcaseDir);
                System.out.println("[Showcase] Artefakte erzeugt in: " + output.outputDir());
            }
            case "--studio" -> service.launchGui(EngineMode.STUDIO_DUAL_WINDOW);
            case "--standalone" -> service.launchGui(EngineMode.STANDALONE_GAME);
            case "--export" -> {
                StandaloneBuilder.BuildResult result = service.exportStandalone(exportDir);
                System.out.println("[Export] Standalone-Bundle erstellt: " + result.vpakFile()
                        + " (" + result.packagedEntries() + " Einträge)");
            }
            case "--interactive" -> {
                Path savesDir = service.getProjectDir().resolve("saves");
                new SnowWhiteStoryRunner(service, narrator).runInteractiveDemo(savesDir);
            }
            default -> {
                Path tempSaves = Files.createTempDirectory("schneewittchen-demo-saves-");
                new SnowWhiteStoryRunner(service, narrator).runAutoDemo(tempSaves);
            }
        }
    }

    private static Path resolveDefaultProjectDir() {
        Path direct = Path.of("project");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path fromRepoRoot = Path.of("demo", "project");
        if (Files.isDirectory(fromRepoRoot)) {
            return fromRepoRoot;
        }
        return direct;
    }
}
