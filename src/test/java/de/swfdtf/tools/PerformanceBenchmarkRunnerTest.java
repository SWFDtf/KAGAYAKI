package de.swfdtf.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PerformanceBenchmarkRunner} verifying deterministic metrics and Markdown output.
 */
class PerformanceBenchmarkRunnerTest {

    @Test
    void runsAllBenchmarksAndWritesMarkdownReport(@TempDir Path tempDir) throws IOException {
        PerformanceBenchmarkRunner runner = new PerformanceBenchmarkRunner();
        PerformanceBenchmarkRunner.BenchmarkSuiteReport report = runner.runAll();

        assertEquals(5, report.metrics().size());
        for (PerformanceBenchmarkRunner.BenchmarkMetric metric : report.metrics()) {
            assertTrue(metric.iterations() > 0);
            assertTrue(metric.opsPerSecond() > 0.0);
        }

        Path outFile = tempDir.resolve("BENCHMARKS.md");
        runner.writeMarkdown(outFile, report);
        String md = Files.readString(outFile, StandardCharsets.UTF_8);
        assertTrue(md.contains("VJavaLexer.tokenize"));
        assertTrue(md.contains("VJavaParser.parse"));
        assertTrue(md.contains("ObjectMotionController.update"));
        assertTrue(md.contains("SceneGraph.traverseOrdered"));
        assertTrue(md.contains("StyledTextLayout.layout"));

        Path repoBenchmarks = Path.of("docs/BENCHMARKS.md");
        assertTrue(Files.isRegularFile(repoBenchmarks), "Expected baseline docs/BENCHMARKS.md to exist");
    }
}
