package de.swfdtf.tools;

import de.swfdtf.core.EngineVersion;
import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.ObjectMotionController;
import de.swfdtf.graphics.SceneGraph;
import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.graphics.SceneNode;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.graphics.StyledTextLayout;
import de.swfdtf.graphics.TextTagParser;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.parser.VJavaLexer;
import de.swfdtf.parser.VJavaParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic micro-benchmark runner measuring core KAGAYAKI subsystem throughput for regression
 * tracking and generating {@code docs/BENCHMARKS.md} ({@code #0947}).
 */
public final class PerformanceBenchmarkRunner {

    private static final String SAMPLE_SCRIPT = """
            define snow = Character("Schneewittchen", color="#e6edf3")
            default apples = 3

            label start:
                scene bg_forest with dissolve(0.5)
                show hero at center
                move hero to 960 540 duration 1.0 easing ease_out
                snow "Ein sonniger Tag im Wald, [apples] Aepfel gesammelt."
                if apples >= 2:
                    $ apples += 1
                    jump finale
                return

            label finale:
                "Ende."
                return
            """;

    /**
     * Single measured benchmark metric entry.
     *
     * @param name         descriptive benchmark identifier
     * @param iterations   measured loop count
     * @param totalNanos   total elapsed nanoseconds over the measured iterations
     * @param avgMicros    average latency per iteration in microseconds
     * @param opsPerSecond throughput in operations per second
     * @param notes        workload context description
     */
    public record BenchmarkMetric(
            String name,
            int iterations,
            long totalNanos,
            double avgMicros,
            double opsPerSecond,
            String notes
    ) {
        /**
         * Validates non-negative counts and finite timings.
         */
        public BenchmarkMetric {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(notes, "notes must not be null");
            if (iterations <= 0) {
                throw new IllegalArgumentException("iterations must be > 0");
            }
            if (totalNanos < 0L) {
                throw new IllegalArgumentException("totalNanos must be >= 0");
            }
        }
    }

    /**
     * Aggregated benchmark suite execution report with host environment metadata.
     *
     * @param engineVersion KAGAYAKI engine version string
     * @param javaVersion   JVM runtime version
     * @param osName        operating system name
     * @param osArch        processor architecture
     * @param metrics       ordered immutable list of benchmark metrics
     */
    public record BenchmarkSuiteReport(
            String engineVersion,
            String javaVersion,
            String osName,
            String osArch,
            List<BenchmarkMetric> metrics
    ) {
        /**
         * Enforces non-null metadata and immutable metric list.
         */
        public BenchmarkSuiteReport {
            Objects.requireNonNull(engineVersion, "engineVersion must not be null");
            Objects.requireNonNull(javaVersion, "javaVersion must not be null");
            Objects.requireNonNull(osName, "osName must not be null");
            Objects.requireNonNull(osArch, "osArch must not be null");
            metrics = List.copyOf(Objects.requireNonNull(metrics, "metrics must not be null"));
        }
    }

    /**
     * Creates a new {@code PerformanceBenchmarkRunner}.
     */
    public PerformanceBenchmarkRunner() {
    }

    /**
     * Executes all reproducible KAGAYAKI micro-benchmarks and returns the structured report.
     *
     * @return measured {@link BenchmarkSuiteReport}
     */
    public BenchmarkSuiteReport runAll() {
        List<BenchmarkMetric> metrics = List.of(
                benchmarkLexer(),
                benchmarkParser(),
                benchmarkMotionController(),
                benchmarkSceneTraversal(),
                benchmarkRichTextLayout()
        );
        return new BenchmarkSuiteReport(
                EngineVersion.formatBanner(),
                System.getProperty("java.version", "unknown"),
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.arch", "unknown"),
                metrics
        );
    }

    /**
     * Formats a {@link BenchmarkSuiteReport} into Markdown suitable for {@code docs/BENCHMARKS.md}.
     *
     * @param report benchmark report to format (must not be {@code null})
     * @return formatted Markdown report
     */
    public String formatMarkdown(BenchmarkSuiteReport report) {
        Objects.requireNonNull(report, "report must not be null");
        StringBuilder sb = new StringBuilder();
        sb.append("# KAGAYAKI Performance Benchmarks\n\n");
        sb.append("> **Engine:** ").append(report.engineVersion()).append("  \n");
        sb.append("> **Environment:** Java ").append(report.javaVersion())
                .append(" · ").append(report.osName())
                .append(" (").append(report.osArch()).append(")\n\n");
        sb.append("| Benchmark | Iterations | Total (ms) | Avg (us/op) | Throughput (ops/s) | Notes |\n");
        sb.append("|---|---:|---:|---:|---:|---|\n");
        for (BenchmarkMetric metric : report.metrics()) {
            double totalMs = metric.totalNanos() / 1_000_000.0;
            sb.append(String.format(
                    Locale.ROOT,
                    "| %s | %d | %.2f | %.3f | %.1f | %s |\n",
                    metric.name(),
                    metric.iterations(),
                    totalMs,
                    metric.avgMicros(),
                    metric.opsPerSecond(),
                    metric.notes()
            ));
        }
        sb.append("\nGenerated deterministically via `de.swfdtf.tools.PerformanceBenchmarkRunner` (`#0947`).\n");
        return sb.toString();
    }

    /**
     * Writes the formatted benchmark Markdown report to {@code outputPath}.
     *
     * @param outputPath target Markdown file path (must not be {@code null})
     * @param report     benchmark suite report (must not be {@code null})
     * @throws IOException if creating directories or writing fails
     */
    public void writeMarkdown(Path outputPath, BenchmarkSuiteReport report) throws IOException {
        Objects.requireNonNull(outputPath, "outputPath must not be null");
        Objects.requireNonNull(report, "report must not be null");
        Path normalized = outputPath.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(normalized, formatMarkdown(report), StandardCharsets.UTF_8);
    }

    /**
     * CLI entrypoint executing benchmarks and writing {@code docs/BENCHMARKS.md} (or a custom path).
     *
     * @param args optional first argument overriding destination Markdown file path
     * @throws IOException if writing the report fails
     */
    public static void main(String[] args) throws IOException {
        Path dest = (args != null && args.length > 0 && !args[0].isBlank())
                ? Path.of(args[0])
                : Path.of("docs/BENCHMARKS.md");
        PerformanceBenchmarkRunner runner = new PerformanceBenchmarkRunner();
        BenchmarkSuiteReport report = runner.runAll();
        runner.writeMarkdown(dest, report);
    }

    private static BenchmarkMetric benchmarkLexer() {
        return measure("VJavaLexer.tokenize", 200, 1_500, "15-line hybrid .vjava script", () -> {
            List<VJavaLexer.Token> tokens = VJavaLexer.tokenize(SAMPLE_SCRIPT, "bench.vjava");
            return tokens.size();
        });
    }

    private static BenchmarkMetric benchmarkParser() {
        Path benchPath = Path.of("bench.vjava");
        List<VJavaLexer.Token> preTokenized = VJavaLexer.tokenize(SAMPLE_SCRIPT, "bench.vjava");
        return measure("VJavaParser.parse", 200, 1_500, "AST construction from tokens", () -> {
            VJavaAst.ScriptFileAst ast = VJavaParser.parse(benchPath, preTokenized);
            return ast.labels().size();
        });
    }

    private static BenchmarkMetric benchmarkMotionController() {
        Map<String, SpriteNode> sprites = new LinkedHashMap<>();
        SpriteNode hero = new SpriteNode("hero", "hero");
        hero.setWidth(64.0f).setHeight(64.0f);
        hero.getTransform().setXpos(400.0f).setYpos(300.0f);
        sprites.put("hero", hero);
        ObjectMotionController motion = new ObjectMotionController(sprites);
        motion.enableWasd("hero", 240.0f, 0.0f, 0.0f, 1920.0f, 1080.0f, true);
        motion.enablePhysics("hero", 980.0f, 520.0f, 540.0f);
        motion.startMove("hero", 960.0f, 540.0f, false, 2.0, "ease_out");
        ObjectMotionController.MotionInput input =
                new ObjectMotionController.MotionInput(true, false, false, true, true);
        return measure("ObjectMotionController.update", 500, 5_000, "WASD + gravity + tween tick", () -> {
            motion.update(1.0 / 60.0, input);
            return (int) hero.getTransform().getXpos();
        });
    }

    private static BenchmarkMetric benchmarkSceneTraversal() {
        SceneGraph graph = new SceneGraph();
        for (int i = 0; i < 100; i++) {
            SpriteNode node = new SpriteNode("sprite_" + i, "sprite_" + i);
            node.setWidth(64.0f).setHeight(64.0f);
            node.getTransform().setXpos(i * 10.0f).setYpos((i % 10) * 40.0f);
            graph.addNode(SceneGraph.LAYER_MASTER, node);
        }
        return measure("SceneGraph.traverseOrdered", 500, 5_000, "100-node layered traversal", () -> {
            int visible = 0;
            for (SceneLayer layer : graph.getLayersSorted()) {
                for (SceneNode node : layer.getNodes()) {
                    if (node.isVisible()) {
                        visible++;
                    }
                }
            }
            return visible;
        });
    }

    private static BenchmarkMetric benchmarkRichTextLayout() {
        FontManager fontManager = new FontManager();
        String rich = "{b}Schneewittchen:{/b} Hinter den sieben Bergen [apples] Aepfel"
                + " und {color=#ff8800}Gold{/color}.";
        return measure("StyledTextLayout.layout", 300, 2_000, "Tag parse + word-wrap layout", () -> {
            TextTagParser.ParsedRichText parsed = TextTagParser.parse(rich, key -> 7);
            StyledTextLayout layout = StyledTextLayout.layout(
                    parsed, fontManager, 620.0f, 24.0f, 1.15f, "#ffffff", false);
            return layout.getLines().size();
        });
    }

    @FunctionalInterface
    private interface IntBenchmarkTask {
        int execute();
    }

    private static BenchmarkMetric measure(
            String name,
            int warmupIterations,
            int measuredIterations,
            String notes,
            IntBenchmarkTask task
    ) {
        int sink = 0;
        for (int i = 0; i < warmupIterations; i++) {
            sink += task.execute();
        }
        long start = System.nanoTime();
        for (int i = 0; i < measuredIterations; i++) {
            sink += task.execute();
        }
        long elapsedNanos = Math.max(1L, System.nanoTime() - start);
        if (sink == Integer.MIN_VALUE) {
            throw new IllegalStateException("Impossible sink sentinel");
        }
        double totalSeconds = elapsedNanos / 1_000_000_000.0;
        double avgMicros = (elapsedNanos / 1_000.0) / measuredIterations;
        double opsPerSec = measuredIterations / totalSeconds;
        return new BenchmarkMetric(name, measuredIterations, elapsedNanos, avgMicros, opsPerSec, notes);
    }
}
