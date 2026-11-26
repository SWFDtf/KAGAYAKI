package de.swfdtf.tools;

import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.VJavaAst;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Headless CI/CD command-line runner exposing Studio checks (lint, preflight, flow-graph export,
 * standalone build, release-readiness, and HTML production dashboard) with deterministic exit
 * codes ({@code 0} on clean success, {@code != 0} on validation failure) ({@code #0170}).
 */
public final class StudioCliRunner {

    private StudioCliRunner() {
    }

    /**
     * CLI entrypoint delegating to {@link #run(String[], PrintStream, PrintStream)} and exiting via
     * {@link System#exit(int)} on non-zero return codes.
     *
     * @param args CLI command arguments
     */
    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    /**
     * Executes a headless Studio command writing diagnostics to standard output streams.
     *
     * @param args command arguments (e.g., {@code lint <dir>}, {@code preflight <dir>})
     * @return {@code 0} on clean success, non-zero exit code on errors or invalid usage
     */
    public static int run(String[] args) {
        return run(args, System.out, System.err);
    }

    /**
     * Executes a headless Studio command writing diagnostics to supplied streams.
     *
     * @param args command arguments
     * @param out  standard output stream
     * @param err  error output stream
     * @return {@code 0} on clean success, non-zero on failure
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Objects.requireNonNull(out, "out must not be null");
        Objects.requireNonNull(err, "err must not be null");
        if (args == null || args.length < 2) {
            err.println("Usage: StudioCliRunner <lint|preflight|flow-graph|standalone-build|release-check|html-report>"
                    + " <projectDir> [outputPath]");
            return 2;
        }
        String cmd = args[0].trim();
        Path projectDir = Path.of(args[1]).toAbsolutePath().normalize();
        try {
            return switch (cmd) {
                case "lint" -> runLint(projectDir, out, err);
                case "preflight" -> runPreflight(projectDir, out, err);
                case "flow-graph" -> runFlowGraph(projectDir, args, out, err);
                case "standalone-build" -> runStandaloneBuild(projectDir, args, out, err);
                case "release-check" -> runReleaseCheck(projectDir, out, err);
                case "html-report" -> runHtmlReport(projectDir, args, out, err);
                default -> {
                    err.println("Unknown Studio CLI command: " + cmd);
                    yield 2;
                }
            };
        } catch (Exception ex) {
            err.println("Studio CLI failed [" + cmd + "]: " + ex.getMessage());
            return 1;
        }
    }

    private static int runLint(Path projectDir, PrintStream out, PrintStream err) {
        VJavaLinter.LintReport report = VJavaLinter.lint(projectDir);
        out.println("Lint result: errors=" + report.errorCount() + ", warnings=" + report.warningCount());
        if (report.hasErrors()) {
            report.issues().forEach(issue -> err.println(
                    "[" + issue.severity() + "] " + issue.code() + " (" + issue.sourceFile()
                            + ":" + issue.lineNumber() + "): " + issue.message()));
            return 1;
        }
        return 0;
    }

    private static int runPreflight(Path projectDir, PrintStream out, PrintStream err) {
        ExportPreFlightChecker.PreFlightReport report = new ExportPreFlightChecker().check(projectDir);
        out.println(report.summaryMarkdown());
        if (!report.readyForRelease()) {
            err.println("Preflight checks failed.");
            return 1;
        }
        return 0;
    }

    private static int runFlowGraph(Path projectDir, String[] args, PrintStream out, PrintStream err)
            throws IOException {
        if (args.length < 3) {
            err.println("Missing output file path for flow-graph");
            return 2;
        }
        Path outputFile = Path.of(args[2]).toAbsolutePath().normalize();
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }
        VJavaAst.ProjectAst ast = ProjectScanner.scan(projectDir);
        ScriptFlowGraphExporter exporter = new ScriptFlowGraphExporter();
        ScriptFlowGraphExporter.FlowGraph graph = exporter.buildGraph(ast);
        String content = outputFile.toString().endsWith(".dot")
                ? exporter.toDot(graph)
                : exporter.toMermaid(graph);
        Files.writeString(outputFile, content, StandardCharsets.UTF_8);
        out.println("Exported flow graph to " + outputFile);
        return 0;
    }

    private static int runStandaloneBuild(Path projectDir, String[] args, PrintStream out, PrintStream err)
            throws IOException {
        if (args.length < 3) {
            err.println("Missing output directory path for standalone-build");
            return 2;
        }
        Path outputDir = Path.of(args[2]).toAbsolutePath().normalize();
        StandaloneBuilder.BuildResult result = StandaloneBuilder.build(projectDir, outputDir, null);
        out.println("Standalone build complete: " + result.vpakFile()
                + " (" + result.packagedEntries() + " entries)");
        return 0;
    }

    private static int runReleaseCheck(Path projectDir, PrintStream out, PrintStream err) throws IOException {
        StudioReportAndQaService.ReleaseReadinessReport report =
                new StudioReportAndQaService().evaluateReleaseReadiness(projectDir);
        out.println(report.summary());
        if (!report.ready()) {
            err.println("Release readiness check failed.");
            return 1;
        }
        return 0;
    }

    private static int runHtmlReport(Path projectDir, String[] args, PrintStream out, PrintStream err)
            throws IOException {
        if (args.length < 3) {
            err.println("Missing output HTML path for html-report");
            return 2;
        }
        Path outputHtml = Path.of(args[2]).toAbsolutePath().normalize();
        Path written = new StudioReportAndQaService().exportHtmlDashboard(projectDir, outputHtml);
        out.println("Generated HTML dashboard: " + written);
        return 0;
    }
}
