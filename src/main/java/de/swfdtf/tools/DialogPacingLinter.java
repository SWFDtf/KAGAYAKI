package de.swfdtf.tools;

import de.swfdtf.parser.VJavaAst;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Optional dialog mood and pacing linter analyzing uninterrupted monologue runs without
 * player interaction ({@code menu}, {@code input}) or scene switches ({@code #0169}).
 *
 * <p>Emits advisory {@link VJavaLinter.Severity#INFO} hints starting at {@value #DEFAULT_MAX_MONOLOGUE_SENTENCES}
 * uninterrupted sentences.</p>
 */
public final class DialogPacingLinter {

    /** Default sentence threshold before emitting an advisory pacing hint ({@code #0169}). */
    public static final int DEFAULT_MAX_MONOLOGUE_SENTENCES = 15;

    /** Machine-readable diagnostic code emitted for overly long uninterrupted monologues. */
    public static final String ISSUE_CODE_LONG_MONOLOGUE = "PACING_LONG_MONOLOGUE";

    /**
     * Summary pacing metrics collected across a project.
     *
     * @param longestMonologueSentences maximum consecutive sentences without choice or scene change
     * @param totalSayStatements        total dialogue lines scanned
     * @param totalInteractiveMenus     total choice menus encountered
     * @param flaggedLabels             label names exceeding the pacing threshold
     */
    public record PacingMetrics(
            int longestMonologueSentences,
            int totalSayStatements,
            int totalInteractiveMenus,
            Set<String> flaggedLabels
    ) {
        /** Enforces defensive set copy. */
        public PacingMetrics {
            flaggedLabels = flaggedLabels != null
                    ? Collections.unmodifiableSet(new LinkedHashSet<>(flaggedLabels))
                    : Set.of();
        }
    }

    private static final class RunTracker {
        private int consecutiveSentences = 0;
        private int runStartLine = 1;
        private String runSourceFile = "";
        private boolean emittedForCurrentRun = false;
        private int maxObservedRun = 0;
        private int totalSay = 0;
        private int totalMenus = 0;

        private void reset() {
            consecutiveSentences = 0;
            emittedForCurrentRun = false;
        }
    }

    private DialogPacingLinter() {
    }

    /**
     * Analyzes {@code project} using {@link #DEFAULT_MAX_MONOLOGUE_SENTENCES} ({@code 15}).
     *
     * @param project parsed project AST (must not be {@code null})
     * @return immutable list of {@link VJavaLinter.Severity#INFO} pacing diagnostics
     */
    public static List<VJavaLinter.LintIssue> analyze(VJavaAst.ProjectAst project) {
        return analyze(project, DEFAULT_MAX_MONOLOGUE_SENTENCES);
    }

    /**
     * Analyzes {@code project} using an explicit {@code maxMonologueSentences} threshold.
     *
     * @param project               parsed project AST (must not be {@code null})
     * @param maxMonologueSentences sentence count at or above which an {@code INFO} hint appears ({@code >= 1})
     * @return immutable list of {@link VJavaLinter.Severity#INFO} pacing diagnostics
     */
    public static List<VJavaLinter.LintIssue> analyze(
            VJavaAst.ProjectAst project,
            int maxMonologueSentences
    ) {
        Objects.requireNonNull(project, "project must not be null");
        if (maxMonologueSentences < 1) {
            throw new IllegalArgumentException("maxMonologueSentences must be >= 1, got " + maxMonologueSentences);
        }

        List<VJavaLinter.LintIssue> issues = new ArrayList<>();
        for (VJavaAst.LabelNode label : project.labels().values()) {
            RunTracker tracker = new RunTracker();
            walkStatements(label.statements(), label.name(), maxMonologueSentences, tracker, issues);
        }
        return List.copyOf(issues);
    }

    /**
     * Computes aggregate pacing telemetry across all labels in {@code project}.
     *
     * @param project parsed project AST (must not be {@code null})
     * @return immutable {@link PacingMetrics} summary
     */
    public static PacingMetrics analyzeMetrics(VJavaAst.ProjectAst project) {
        return analyzeMetrics(project, DEFAULT_MAX_MONOLOGUE_SENTENCES);
    }

    /**
     * Computes aggregate pacing telemetry across all labels using an explicit threshold.
     *
     * @param project               parsed project AST (must not be {@code null})
     * @param maxMonologueSentences threshold applied for flagging labels
     * @return immutable {@link PacingMetrics} summary
     */
    public static PacingMetrics analyzeMetrics(VJavaAst.ProjectAst project, int maxMonologueSentences) {
        Objects.requireNonNull(project, "project must not be null");
        if (maxMonologueSentences < 1) {
            throw new IllegalArgumentException("maxMonologueSentences must be >= 1, got " + maxMonologueSentences);
        }

        int longest = 0;
        int totalSay = 0;
        int totalMenus = 0;
        Set<String> flagged = new LinkedHashSet<>();

        for (VJavaAst.LabelNode label : project.labels().values()) {
            List<VJavaLinter.LintIssue> localIssues = new ArrayList<>();
            RunTracker tracker = new RunTracker();
            walkStatements(label.statements(), label.name(), maxMonologueSentences, tracker, localIssues);
            longest = Math.max(longest, tracker.maxObservedRun);
            totalSay += tracker.totalSay;
            totalMenus += tracker.totalMenus;
            if (!localIssues.isEmpty()) {
                flagged.add(label.name());
            }
        }
        return new PacingMetrics(longest, totalSay, totalMenus, flagged);
    }

    /**
     * Counts sentence units inside a single dialogue line using terminal sentence punctuation
     * ({@code .}, {@code !}, {@code ?}), returning at least {@code 1} when text is non-blank.
     *
     * @param text raw dialogue text
     * @return estimated sentence count ({@code >= 0})
     */
    public static int countSentences(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int sentences = 0;
        boolean inPunctRun = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean terminal = (c == '.' || c == '!' || c == '?');
            if (terminal && !inPunctRun) {
                sentences++;
                inPunctRun = true;
            } else if (!terminal) {
                inPunctRun = false;
            }
        }
        return Math.max(1, sentences);
    }

    private static void walkStatements(
            List<VJavaAst.Statement> statements,
            String labelName,
            int threshold,
            RunTracker tracker,
            List<VJavaLinter.LintIssue> issues
    ) {
        if (statements == null) {
            return;
        }
        for (VJavaAst.Statement stmt : statements) {
            if (stmt instanceof VJavaAst.SayStatement say) {
                tracker.totalSay++;
                if (tracker.consecutiveSentences == 0) {
                    tracker.runStartLine = Math.max(1, say.lineNumber());
                    tracker.runSourceFile = say.sourceFile() != null ? say.sourceFile() : "";
                }
                tracker.consecutiveSentences += countSentences(say.text());
                tracker.maxObservedRun = Math.max(tracker.maxObservedRun, tracker.consecutiveSentences);
                if (tracker.consecutiveSentences >= threshold && !tracker.emittedForCurrentRun) {
                    tracker.emittedForCurrentRun = true;
                    issues.add(new VJavaLinter.LintIssue(
                            VJavaLinter.Severity.INFO,
                            ISSUE_CODE_LONG_MONOLOGUE,
                            "Label '" + labelName + "' reaches " + tracker.consecutiveSentences
                                    + " consecutive sentences without choice or scene change (threshold: "
                                    + threshold + ").",
                            tracker.runSourceFile,
                            tracker.runStartLine
                    ));
                }
            } else if (stmt instanceof VJavaAst.SceneStatement
                    || stmt instanceof VJavaAst.JumpStatement
                    || stmt instanceof VJavaAst.CallStatement
                    || stmt instanceof VJavaAst.InputStatement) {
                tracker.reset();
            } else if (stmt instanceof VJavaAst.MenuStatement menu) {
                tracker.totalMenus++;
                tracker.reset();
                for (VJavaAst.MenuChoice choice : menu.choices()) {
                    RunTracker branchTracker = new RunTracker();
                    walkStatements(choice.body(), labelName, threshold, branchTracker, issues);
                    tracker.totalSay += branchTracker.totalSay;
                    tracker.totalMenus += branchTracker.totalMenus;
                    tracker.maxObservedRun = Math.max(tracker.maxObservedRun, branchTracker.maxObservedRun);
                }
            } else if (stmt instanceof VJavaAst.IfStatement ifs) {
                for (VJavaAst.IfBranch branch : ifs.branches()) {
                    walkStatements(branch.body(), labelName, threshold, tracker, issues);
                }
            } else if (stmt instanceof VJavaAst.MatchStatement match) {
                for (VJavaAst.MatchCase mc : match.cases()) {
                    walkStatements(mc.body(), labelName, threshold, tracker, issues);
                }
                walkStatements(match.defaultBody(), labelName, threshold, tracker, issues);
            }
        }
    }
}
