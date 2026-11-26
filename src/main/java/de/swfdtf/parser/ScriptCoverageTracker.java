package de.swfdtf.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Tracks executed statements and branch arms across {@link ScriptRuntime} runs to produce
 * per-file and project-wide statement and branch QA coverage reports.
 */
public final class ScriptCoverageTracker {

    /**
     * Per-file statement and branch coverage metrics.
     *
     * @param sourceFile        relative script path
     * @param totalStatements   total parsed statements in file
     * @param coveredStatements executed statements in file
     * @param totalBranches     total branch arms ({@code if}/{@code match}/{@code menu})
     * @param coveredBranches   executed branch arms
     */
    public record FileCoverage(
            String sourceFile,
            int totalStatements,
            int coveredStatements,
            int totalBranches,
            int coveredBranches
    ) {
        /**
         * Returns statement coverage percentage in {@code [0.0, 100.0]}.
         *
         * @return statement coverage percentage
         */
        public double statementPercentage() {
            return totalStatements <= 0 ? 100.0 : (coveredStatements * 100.0) / totalStatements;
        }

        /**
         * Returns branch coverage percentage in {@code [0.0, 100.0]}.
         *
         * @return branch coverage percentage
         */
        public double branchPercentage() {
            return totalBranches <= 0 ? 100.0 : (coveredBranches * 100.0) / totalBranches;
        }
    }

    /**
     * Project-wide coverage summary across all scanned {@code .vjava} files.
     *
     * @param totalStatements   total project statements
     * @param coveredStatements executed project statements
     * @param totalBranches     total branch arms
     * @param coveredBranches   executed branch arms
     * @param perFile           unmodifiable per-file breakdown keyed by relative path
     */
    public record CoverageReport(
            int totalStatements,
            int coveredStatements,
            int totalBranches,
            int coveredBranches,
            Map<String, FileCoverage> perFile
    ) {
        /** Defensively copies per-file breakdown. */
        public CoverageReport {
            perFile = perFile != null ? Collections.unmodifiableMap(new LinkedHashMap<>(perFile)) : Map.of();
        }

        /**
         * Returns overall statement coverage percentage in {@code [0.0, 100.0]}.
         *
         * @return statement coverage percentage
         */
        public double statementPercentage() {
            return totalStatements <= 0 ? 100.0 : (coveredStatements * 100.0) / totalStatements;
        }

        /**
         * Returns overall branch coverage percentage in {@code [0.0, 100.0]}.
         *
         * @return branch coverage percentage
         */
        public double branchPercentage() {
            return totalBranches <= 0 ? 100.0 : (coveredBranches * 100.0) / totalBranches;
        }
    }

    private record StmtKey(String sourceFile, int lineNumber, String kind) {
    }

    private record BranchKey(String sourceFile, int lineNumber, int armIndex) {
    }

    private final Set<StmtKey> executedStatements = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<BranchKey> executedBranches = Collections.synchronizedSet(new LinkedHashSet<>());

    /** Creates an empty {@code ScriptCoverageTracker}. */
    public ScriptCoverageTracker() {
    }

    /**
     * Records execution of {@code stmt}.
     *
     * @param stmt executed statement
     */
    public void recordStatement(VJavaAst.Statement stmt) {
        if (stmt == null) {
            return;
        }
        String sf = normalizeFile(stmt.sourceFile());
        executedStatements.add(new StmtKey(sf, stmt.lineNumber(), stmt.getClass().getSimpleName()));
    }

    /**
     * Records execution of a conditional, match, or menu choice branch arm.
     *
     * @param sourceFile source file path
     * @param lineNumber 1-based branch line number
     * @param armIndex   0-based branch index within parent construct
     */
    public void recordBranch(String sourceFile, int lineNumber, int armIndex) {
        executedBranches.add(new BranchKey(normalizeFile(sourceFile), lineNumber, armIndex));
    }

    /** Clears all recorded statement and branch execution hits. */
    public void reset() {
        executedStatements.clear();
        executedBranches.clear();
    }

    /**
     * Computes a {@link CoverageReport} comparing recorded hits against all statements and branches
     * defined in {@code project}.
     *
     * @param project compiled project AST
     * @return immutable coverage report
     */
    public CoverageReport buildReport(VJavaAst.ProjectAst project) {
        Objects.requireNonNull(project, "project must not be null");
        Map<String, List<StmtKey>> stmtsByFile = new LinkedHashMap<>();
        Map<String, List<BranchKey>> branchesByFile = new LinkedHashMap<>();

        for (VJavaAst.LabelNode label : project.labels().values()) {
            collectItems(label.statements(), stmtsByFile, branchesByFile);
        }

        Map<String, FileCoverage> perFile = new LinkedHashMap<>();
        int totalStmts = 0;
        int coveredStmts = 0;
        int totalBranches = 0;
        int coveredBranches = 0;

        Set<String> allFiles = new LinkedHashSet<>(stmtsByFile.keySet());
        allFiles.addAll(branchesByFile.keySet());
        for (String sf : allFiles) {
            List<StmtKey> fStmts = stmtsByFile.getOrDefault(sf, List.of());
            List<BranchKey> fBranches = branchesByFile.getOrDefault(sf, List.of());
            int fCoveredStmts = 0;
            for (StmtKey key : fStmts) {
                if (executedStatements.contains(key)) {
                    fCoveredStmts++;
                }
            }
            int fCoveredBranches = 0;
            for (BranchKey bKey : fBranches) {
                if (executedBranches.contains(bKey)) {
                    fCoveredBranches++;
                }
            }
            perFile.put(sf, new FileCoverage(
                    sf, fStmts.size(), fCoveredStmts, fBranches.size(), fCoveredBranches));
            totalStmts += fStmts.size();
            coveredStmts += fCoveredStmts;
            totalBranches += fBranches.size();
            coveredBranches += fCoveredBranches;
        }
        return new CoverageReport(totalStmts, coveredStmts, totalBranches, coveredBranches, perFile);
    }

    private static void collectItems(
            List<VJavaAst.Statement> statements,
            Map<String, List<StmtKey>> stmtsByFile,
            Map<String, List<BranchKey>> branchesByFile
    ) {
        for (VJavaAst.Statement stmt : statements) {
            String sf = normalizeFile(stmt.sourceFile());
            stmtsByFile.computeIfAbsent(sf, k -> new ArrayList<>())
                    .add(new StmtKey(sf, stmt.lineNumber(), stmt.getClass().getSimpleName()));

            if (stmt instanceof VJavaAst.IfStatement ifs) {
                for (int i = 0; i < ifs.branches().size(); i++) {
                    VJavaAst.IfBranch branch = ifs.branches().get(i);
                    branchesByFile.computeIfAbsent(sf, k -> new ArrayList<>())
                            .add(new BranchKey(sf, ifs.lineNumber(), i));
                    collectItems(branch.body(), stmtsByFile, branchesByFile);
                }
            } else if (stmt instanceof VJavaAst.MatchStatement ms) {
                for (int i = 0; i < ms.cases().size(); i++) {
                    VJavaAst.MatchCase mc = ms.cases().get(i);
                    branchesByFile.computeIfAbsent(sf, k -> new ArrayList<>())
                            .add(new BranchKey(sf, mc.lineNumber(), i));
                    collectItems(mc.body(), stmtsByFile, branchesByFile);
                }
                if (ms.defaultBody() != null) {
                    branchesByFile.computeIfAbsent(sf, k -> new ArrayList<>())
                            .add(new BranchKey(sf, ms.lineNumber(), ms.cases().size()));
                    collectItems(ms.defaultBody(), stmtsByFile, branchesByFile);
                }
            } else if (stmt instanceof VJavaAst.MenuStatement menu) {
                for (int i = 0; i < menu.choices().size(); i++) {
                    VJavaAst.MenuChoice ch = menu.choices().get(i);
                    branchesByFile.computeIfAbsent(sf, k -> new ArrayList<>())
                            .add(new BranchKey(sf, ch.lineNumber(), i));
                    collectItems(ch.body(), stmtsByFile, branchesByFile);
                }
            } else if (stmt instanceof VJavaAst.ParallelStatement ps) {
                collectItems(ps.statements(), stmtsByFile, branchesByFile);
            } else if (stmt instanceof VJavaAst.RepeatStatement rs) {
                collectItems(rs.body(), stmtsByFile, branchesByFile);
            } else if (stmt instanceof VJavaAst.WhileStatement ws) {
                collectItems(ws.body(), stmtsByFile, branchesByFile);
            } else if (stmt instanceof VJavaAst.RandomStatement rnd) {
                for (int i = 0; i < rnd.branches().size(); i++) {
                    VJavaAst.RandomBranch rb = rnd.branches().get(i);
                    branchesByFile.computeIfAbsent(sf, k -> new ArrayList<>())
                            .add(new BranchKey(sf, rb.lineNumber(), i));
                    collectItems(rb.body(), stmtsByFile, branchesByFile);
                }
            } else if (stmt instanceof VJavaAst.SetBlockStatement sb) {
                for (VJavaAst.SetVarStatement sv : sb.assignments()) {
                    stmtsByFile.computeIfAbsent(sf, k -> new ArrayList<>())
                            .add(new StmtKey(sf, sv.lineNumber(), sv.getClass().getSimpleName()));
                }
            }
        }
    }

    private static String normalizeFile(String sourceFile) {
        return sourceFile != null ? sourceFile.replace('\\', '/') : "<unknown>";
    }
}
