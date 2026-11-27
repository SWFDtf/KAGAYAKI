package de.swfdtf.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.swfdtf.audio.AudioDecoder;
import de.swfdtf.core.EngineVersion;
import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.VJavaAst;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Community template validator CLI report, HTML team showcase dashboard exporter, Studio Hub
 * static changelog/explorer helpers, and procedural demo WAV sound kit
 * ({@code #0881}, {@code #0896}, {@code #0907}, {@code #0911}, {@code #0916}, {@code #0931},
 * {@code #0933}).
 */
public final class TemplateEcosystemServices {

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    private TemplateEcosystemServices() {
    }

    /**
     * Report produced by {@link CommunityTemplateValidator#validateTemplate(Path)} ({@code #0881}).
     *
     * @param templateDir    validated template root directory
     * @param passed         {@code true} when zero lint errors, zero warnings, and clean assets
     * @param lintErrors     linter error count
     * @param lintWarnings   linter warning count
     * @param missingAssets  missing asset paths count
     * @param summaryLines   diagnostic detail lines
     */
    public record TemplateValidationReport(
            String templateDir,
            boolean passed,
            int lintErrors,
            int lintWarnings,
            int missingAssets,
            List<String> summaryLines
    ) {
        /**
         * Formats validation report as machine-readable JSON ({@code #0881}).
         *
         * @return JSON report string
         */
        public String toJson() {
            return PRETTY.toJson(this);
        }

        /**
         * Formats validation report as concise plaintext summary ({@code #0881}).
         *
         * @return plaintext report string
         */
        public String toPlainText() {
            return (passed ? "[PASS] " : "[FAIL] ") + templateDir
                    + " errors=" + lintErrors + " warnings=" + lintWarnings
                    + " missingAssets=" + missingAssets;
        }
    }

    /**
     * Validates starter and community templates strictly for zero lint issues, valid descriptor,
     * and 100% asset completeness ({@code #0881}).
     */
    public static final class CommunityTemplateValidator {

        /**
         * Creates a {@code CommunityTemplateValidator}.
         */
        public CommunityTemplateValidator() {
        }

        /**
         * Validates {@code templateDir} and returns structured JSON/plaintext report ({@code #0881}).
         *
         * @param templateDir template project directory
         * @return immutable {@link TemplateValidationReport}
         * @throws IOException if scanning or reading files fails
         */
        public static TemplateValidationReport validateTemplate(Path templateDir) throws IOException {
            Objects.requireNonNull(templateDir, "templateDir must not be null");
            Path norm = templateDir.toAbsolutePath().normalize();
            List<String> notes = new ArrayList<>();
            if (!Files.isRegularFile(norm.resolve(ProjectDescriptor.FILE_NAME))) {
                notes.add("Missing project.vjava.json");
                return new TemplateValidationReport(norm.toString(), false, 1, 0, 0, List.copyOf(notes));
            }
            VJavaAst.ProjectAst ast = ProjectScanner.scan(norm);
            VJavaLinter.LintReport lint = VJavaLinter.lint(ast, false);
            UnusedAssetDetector.AssetUsageReport assets = new UnusedAssetDetector().analyze(ast);
            int missingCount = assets.missingAssets().size();
            boolean passed = lint.errorCount() == 0 && lint.warningCount() == 0 && missingCount == 0;
            notes.add("Labels: " + ast.labels().size() + ", Assets: " + assets.discoveredAssets().size());
            return new TemplateValidationReport(
                    norm.toString(),
                    passed,
                    lint.errorCount(),
                    lint.warningCount(),
                    missingCount,
                    Collections.unmodifiableList(notes)
            );
        }
    }

    /**
     * Generates a single-file HTML showcase dashboard combining metrics, linter health, and word counts
     * for teams ({@code #0907}).
     */
    public static final class EngineShowcaseReportExporter {

        /**
         * Creates an {@code EngineShowcaseReportExporter}.
         */
        public EngineShowcaseReportExporter() {
        }

        /**
         * Renders standalone HTML dashboard and writes it to {@code outputHtml} ({@code #0907}).
         *
         * @param outputHtml   target HTML file under {@code build/reports/}
         * @param projectTitle project display name
         * @param lintErrors   linter error count
         * @param totalWords   dialogue word count
         * @param avgFrameMs   measured average frame time in ms
         * @return normalized written HTML path
         * @throws IOException if writing fails
         */
        public static Path exportHtmlDashboard(
                Path outputHtml,
                String projectTitle,
                int lintErrors,
                int totalWords,
                double avgFrameMs
        ) throws IOException {
            Objects.requireNonNull(outputHtml, "outputHtml must not be null");
            Path norm = outputHtml.toAbsolutePath().normalize();
            if (norm.getParent() != null) {
                Files.createDirectories(norm.getParent());
            }
            String html = "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">"
                    + "<title>KAGAYAKI Showcase Report — " + projectTitle + "</title></head>"
                    + "<body style=\"font-family:sans-serif;background:#0d1117;color:#f0f6fc;padding:24px\">"
                    + "<h1>KAGAYAKI Showcase Report (" + EngineVersion.VERSION + ")</h1>"
                    + "<p>Project: <strong>" + projectTitle + "</strong></p>"
                    + "<ul><li>Lint Errors: " + lintErrors + "</li>"
                    + "<li>Script Words: " + totalWords + "</li>"
                    + "<li>Avg Frame Time: " + String.format(Locale.ROOT, "%.2f ms", avgFrameMs) + "</li></ul>"
                    + "</body></html>";
            Files.writeString(norm, html, StandardCharsets.UTF_8);
            return norm;
        }
    }

    /**
     * Offline Studio Hub changelog news, OS explorer launcher without shell injection, and smart
     * demo fallback resolver ({@code #0911}, {@code #0916}, {@code #0931}, {@code #0933}).
     */
    public static final class StudioHubExtras {

        /**
         * Creates a {@code StudioHubExtras}.
         */
        public StudioHubExtras() {
        }

        /**
         * Returns static offline release highlights for installed {@link EngineVersion} ({@code #0916}).
         *
         * @return immutable list of release highlight strings
         */
        public static List<String> getOfflineChangelogHighlights() {
            return List.of(
                    "KAGAYAKI " + EngineVersion.VERSION + ": 2.5D/3D Hybrid Rendering Pipeline & PBR/Toon Materials",
                    "Template Ecosystem: FULL_DEMO, ACTION_ADVENTURE_2D, HYBRID_3D_MYSTERY, NVL_LITERARY, SANDBOX_2D",
                    "Integrated VJava Linter, Flow-Graph Exporter, .vpak Packaging & Mod Loader"
            );
        }

        /**
         * Builds safe argument list for opening a project folder in OS file explorer without shell invocation
         * (returns empty when running headless) ({@code #0933}).
         *
         * @param projectDir validated directory
         * @param osName     OS name
         * @param headless   true when headless tests run
         * @return safe command token list or empty when headless
         */
        public static List<String> buildSafeOpenExplorerCommand(Path projectDir, String osName, boolean headless) {
            if (headless || projectDir == null || !Files.isDirectory(projectDir)) {
                return List.of();
            }
            String abs = projectDir.toAbsolutePath().normalize().toString();
            String os = osName != null ? osName.toLowerCase(Locale.ROOT) : "";
            if (os.contains("mac")) {
                return List.of("open", abs);
            }
            if (os.contains("win")) {
                return List.of("explorer.exe", abs);
            }
            return List.of("xdg-open", abs);
        }

        /**
         * Resolves effective startup project directory falling back from explicit CLI path to {@code demo/project},
         * or signalling Studio Hub New-Project creation when absent ({@code #0931}).
         *
         * @param explicitDir optional explicit path
         * @param defaultDemo default demo path
         * @return resolved existing project path, or {@code null} to open Hub wizard
         */
        public static Path resolveInitialOrFallbackProject(Path explicitDir, Path defaultDemo) {
            if (explicitDir != null && Files.isRegularFile(explicitDir.resolve(ProjectDescriptor.FILE_NAME))) {
                return explicitDir.toAbsolutePath().normalize();
            }
            if (defaultDemo != null && Files.isRegularFile(defaultDemo.resolve(ProjectDescriptor.FILE_NAME))) {
                return defaultDemo.toAbsolutePath().normalize();
            }
            return null;
        }
    }

    /**
     * Synthesizes crisp 16-bit PCM WAV UI click, save chime, and victory fanfare samples via
     * {@link AudioDecoder} ({@code #0896}).
     */
    public static final class ProceduralDemoSoundKit {

        private static final int SAMPLE_RATE = 22_050;

        /**
         * Creates a {@code ProceduralDemoSoundKit}.
         */
        public ProceduralDemoSoundKit() {
        }

        /**
         * Generates harmonic click, save-chime, and goal-fanfare WAV files under {@code assetsDir} ({@code #0896}).
         *
         * @param assetsAudioDir target directory
         * @return list of generated WAV file paths
         * @throws IOException if WAV encoding/writing fails
         */
        public static List<Path> generateSoundKit(Path assetsAudioDir) throws IOException {
            Objects.requireNonNull(assetsAudioDir, "assetsAudioDir must not be null");
            Files.createDirectories(assetsAudioDir);
            Path click = writeChordWav(assetsAudioDir.resolve("ui_click.wav"),
                    new double[]{660.0, 990.0}, 0.08, 0.25f);
            Path save = writeChordWav(assetsAudioDir.resolve("save_chime.wav"),
                    new double[]{523.25, 659.25, 783.99}, 0.22, 0.28f);
            Path victory = writeChordWav(assetsAudioDir.resolve("goal_fanfare.wav"),
                    new double[]{523.25, 659.25, 783.99, 1046.50}, 0.45, 0.30f);
            return List.of(click, save, victory);
        }

        private static Path writeChordWav(Path file, double[] freqs, double durSec, float amp) throws IOException {
            AudioDecoder.DecodedAudio chord = AudioDecoder.synthesizeChord(freqs, durSec, SAMPLE_RATE, amp);
            byte[] wav = AudioDecoder.encodeWav(chord.pcm16(), chord.channels(), chord.sampleRate());
            Files.write(file, wav);
            return file;
        }
    }
}
