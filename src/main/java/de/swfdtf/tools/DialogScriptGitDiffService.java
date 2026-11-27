package de.swfdtf.tools;

import de.swfdtf.parser.VJavaAst;
import de.swfdtf.parser.VJavaParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Computes dialogue- and choice-focused diffs of {@code .vjava} story scripts against Git {@code HEAD}
 * for paginated Story-Review views in Studio ({@code #0128}).
 *
 * <p>Unchanged labels are strictly excluded from emitted diff reports.</p>
 */
public final class DialogScriptGitDiffService {

    /**
     * Granular change classification for a label or dialogue/choice entry.
     */
    public enum ChangeKind {
        /** Label or entry exists in working tree but not in Git {@code HEAD}. */
        ADDED,
        /** Label or entry existed in Git {@code HEAD} but was removed. */
        REMOVED,
        /** Label or dialogue entry exists in both versions with modified content. */
        MODIFIED
    }

    /**
     * Difference on a single dialogue line within a changed label.
     *
     * @param lineNumber 1-based line number in the working (or {@code HEAD} if removed) script
     * @param kind       line change kind
     * @param beforeLine previous dialogue line text (empty when added)
     * @param afterLine  new dialogue line text (empty when removed)
     */
    public record LineDiff(int lineNumber, ChangeKind kind, String beforeLine, String afterLine) {
        /** Normalizes optional strings. */
        public LineDiff {
            Objects.requireNonNull(kind, "kind must not be null");
            beforeLine = beforeLine != null ? beforeLine : "";
            afterLine = afterLine != null ? afterLine : "";
        }
    }

    /**
     * Aggregated dialogue and choice delta for one modified/added/removed label.
     *
     * @param labelName      script label name
     * @param sourceFile     project-relative {@code .vjava} path
     * @param kind           overall label change classification
     * @param lineDiffs      changed dialogue lines within this label
     * @param addedChoices   menu choices present in working script but absent in {@code HEAD}
     * @param removedChoices menu choices present in {@code HEAD} but absent in working script
     */
    public record LabelDiff(
            String labelName,
            String sourceFile,
            ChangeKind kind,
            List<LineDiff> lineDiffs,
            List<String> addedChoices,
            List<String> removedChoices
    ) {
        /** Normalizes null collections to immutable empty lists. */
        public LabelDiff {
            Objects.requireNonNull(labelName, "labelName must not be null");
            sourceFile = sourceFile != null ? sourceFile : "";
            Objects.requireNonNull(kind, "kind must not be null");
            lineDiffs = lineDiffs != null ? List.copyOf(lineDiffs) : List.of();
            addedChoices = addedChoices != null ? List.copyOf(addedChoices) : List.of();
            removedChoices = removedChoices != null ? List.copyOf(removedChoices) : List.of();
        }
    }

    /**
     * Story-review diff report listing exclusively changed labels with paging helpers.
     *
     * @param changedLabels ordered list of changed, added, or removed labels
     */
    public record ScriptDiffReport(List<LabelDiff> changedLabels) {
        /** Copies changed label list defensively. */
        public ScriptDiffReport {
            changedLabels = changedLabels != null ? List.copyOf(changedLabels) : List.of();
        }

        /**
         * Returns a single page slice of changed labels for paginated Studio reviews.
         *
         * @param pageIndex zero-based page index
         * @param pageSize  maximum labels per page ({@code > 0})
         * @return immutable sublist of {@link LabelDiff} entries for the requested page
         */
        public List<LabelDiff> page(int pageIndex, int pageSize) {
            if (pageSize <= 0) {
                throw new IllegalArgumentException("pageSize must be > 0, got " + pageSize);
            }
            if (pageIndex < 0) {
                return List.of();
            }
            int from = pageIndex * pageSize;
            if (from >= changedLabels.size()) {
                return List.of();
            }
            int to = Math.min(changedLabels.size(), from + pageSize);
            return changedLabels.subList(from, to);
        }

        /**
         * Computes total page count for {@code pageSize}.
         *
         * @param pageSize labels per page ({@code > 0})
         * @return number of pages ({@code >= 0})
         */
        public int totalPages(int pageSize) {
            if (pageSize <= 0) {
                throw new IllegalArgumentException("pageSize must be > 0, got " + pageSize);
            }
            if (changedLabels.isEmpty()) {
                return 0;
            }
            return (changedLabels.size() + pageSize - 1) / pageSize;
        }
    }

    /** Creates a {@code DialogScriptGitDiffService}. */
    public DialogScriptGitDiffService() {
    }

    /**
     * Compares all working-tree {@code **}{@code /*.vjava} files under {@code projectRoot} against
     * Git {@code HEAD} and returns only labels whose dialogue lines or choices changed.
     *
     * @param projectRoot Git repository / KAGAYAKI project root (must not be {@code null})
     * @return paginated {@link ScriptDiffReport}
     * @throws IOException if directory traversal fails
     */
    public ScriptDiffReport diffAgainstGitHead(Path projectRoot) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot must not be null");
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return new ScriptDiffReport(List.of());
        }

        List<Path> scriptFiles;
        try (Stream<Path> walk = Files.walk(root)) {
            scriptFiles = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".vjava"))
                    .filter(p -> !isExcludedPath(root, p))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        List<LabelDiff> diffs = new ArrayList<>();
        for (Path scriptPath : scriptFiles) {
            String rel = root.relativize(scriptPath).toString().replace('\\', '/');
            String workingSource = Files.readString(scriptPath, StandardCharsets.UTF_8);
            String headSource = readFileAtGitHead(root, rel);
            ScriptDiffReport fileDiff = diffSources(rel, headSource, workingSource);
            diffs.addAll(fileDiff.changedLabels());
        }
        return new ScriptDiffReport(diffs);
    }

    /**
     * Compares an older {@code HEAD} script source string against a newer working script source
     * string without requiring disk or Git I/O, omitting all unchanged labels.
     *
     * @param relativePath  display source file path
     * @param headSource    baseline script text from Git {@code HEAD} (nullable/empty if new file)
     * @param workingSource current working-tree script text (nullable/empty if deleted file)
     * @return {@link ScriptDiffReport} containing exclusively changed labels
     */
    public ScriptDiffReport diffSources(String relativePath, String headSource, String workingSource) {
        String sourceLabel = relativePath != null ? relativePath : "main.vjava";
        Path virtualPath = Path.of(sourceLabel);

        Map<String, VJavaAst.LabelNode> headLabels = parseLabelsMap(virtualPath, headSource);
        Map<String, VJavaAst.LabelNode> workLabels = parseLabelsMap(virtualPath, workingSource);

        Set<String> allLabelNames = new LinkedHashSet<>(headLabels.keySet());
        allLabelNames.addAll(workLabels.keySet());

        List<LabelDiff> result = new ArrayList<>();
        for (String labelName : allLabelNames) {
            VJavaAst.LabelNode before = headLabels.get(labelName);
            VJavaAst.LabelNode after = workLabels.get(labelName);
            if (before == null && after != null) {
                List<String> afterLines = collectDialogueLines(after.statements());
                List<String> afterChoices = collectChoiceTexts(after.statements());
                List<LineDiff> lineDiffs = new ArrayList<>(afterLines.size());
                int baseLine = Math.max(1, after.lineNumber());
                for (int i = 0; i < afterLines.size(); i++) {
                    lineDiffs.add(new LineDiff(baseLine + i, ChangeKind.ADDED, "", afterLines.get(i)));
                }
                result.add(new LabelDiff(
                        labelName, sourceLabel, ChangeKind.ADDED, lineDiffs, afterChoices, List.of()));
            } else if (before != null && after == null) {
                List<String> beforeLines = collectDialogueLines(before.statements());
                List<String> beforeChoices = collectChoiceTexts(before.statements());
                List<LineDiff> lineDiffs = new ArrayList<>(beforeLines.size());
                int baseLine = Math.max(1, before.lineNumber());
                for (int i = 0; i < beforeLines.size(); i++) {
                    lineDiffs.add(new LineDiff(baseLine + i, ChangeKind.REMOVED, beforeLines.get(i), ""));
                }
                result.add(new LabelDiff(
                        labelName, sourceLabel, ChangeKind.REMOVED, lineDiffs, List.of(), beforeChoices));
            } else if (before != null) {
                LabelDiff diff = compareExistingLabel(sourceLabel, labelName, before, after);
                if (diff != null) {
                    result.add(diff);
                }
            }
        }
        return new ScriptDiffReport(result);
    }

    private static LabelDiff compareExistingLabel(
            String sourceLabel,
            String labelName,
            VJavaAst.LabelNode before,
            VJavaAst.LabelNode after
    ) {
        List<String> beforeLines = collectDialogueLines(before.statements());
        List<String> afterLines = collectDialogueLines(after.statements());
        List<String> beforeChoices = collectChoiceTexts(before.statements());
        List<String> afterChoices = collectChoiceTexts(after.statements());

        List<LineDiff> lineDiffs = new ArrayList<>();
        int maxLines = Math.max(beforeLines.size(), afterLines.size());
        int baseLine = Math.max(1, after.lineNumber());
        for (int i = 0; i < maxLines; i++) {
            String prev = i < beforeLines.size() ? beforeLines.get(i) : null;
            String curr = i < afterLines.size() ? afterLines.get(i) : null;
            if (prev == null && curr != null) {
                lineDiffs.add(new LineDiff(baseLine + i, ChangeKind.ADDED, "", curr));
            } else if (prev != null && curr == null) {
                lineDiffs.add(new LineDiff(baseLine + i, ChangeKind.REMOVED, prev, ""));
            } else if (!Objects.equals(prev, curr)) {
                lineDiffs.add(new LineDiff(baseLine + i, ChangeKind.MODIFIED, prev, curr));
            }
        }

        List<String> addedChoices = new ArrayList<>();
        for (String c : afterChoices) {
            if (!beforeChoices.contains(c)) {
                addedChoices.add(c);
            }
        }
        List<String> removedChoices = new ArrayList<>();
        for (String c : beforeChoices) {
            if (!afterChoices.contains(c)) {
                removedChoices.add(c);
            }
        }

        if (lineDiffs.isEmpty() && addedChoices.isEmpty() && removedChoices.isEmpty()) {
            return null;
        }
        return new LabelDiff(labelName, sourceLabel, ChangeKind.MODIFIED, lineDiffs, addedChoices, removedChoices);
    }

    private static Map<String, VJavaAst.LabelNode> parseLabelsMap(Path path, String source) {
        if (source == null || source.isBlank()) {
            return Collections.emptyMap();
        }
        VJavaAst.ScriptFileAst ast = VJavaParser.parse(path, source);
        Map<String, VJavaAst.LabelNode> map = new LinkedHashMap<>();
        for (VJavaAst.LabelNode label : ast.labels()) {
            map.put(label.name(), label);
        }
        return map;
    }

    private static List<String> collectDialogueLines(List<VJavaAst.Statement> statements) {
        List<String> lines = new ArrayList<>();
        collectDialogueRecursive(statements, lines);
        return lines;
    }

    private static void collectDialogueRecursive(List<VJavaAst.Statement> statements, List<String> lines) {
        if (statements == null) {
            return;
        }
        for (VJavaAst.Statement stmt : statements) {
            if (stmt instanceof VJavaAst.SayStatement say) {
                String speaker = say.speakerId() != null && !say.speakerId().isBlank()
                        ? say.speakerId().trim() + ": "
                        : "";
                lines.add(speaker + (say.text() != null ? say.text() : ""));
            } else if (stmt instanceof VJavaAst.IfStatement ifs) {
                for (VJavaAst.IfBranch branch : ifs.branches()) {
                    collectDialogueRecursive(branch.body(), lines);
                }
            } else if (stmt instanceof VJavaAst.MenuStatement menu) {
                for (VJavaAst.MenuChoice choice : menu.choices()) {
                    collectDialogueRecursive(choice.body(), lines);
                }
            } else if (stmt instanceof VJavaAst.MatchStatement match) {
                for (VJavaAst.MatchCase mc : match.cases()) {
                    collectDialogueRecursive(mc.body(), lines);
                }
                collectDialogueRecursive(match.defaultBody(), lines);
            }
        }
    }

    private static List<String> collectChoiceTexts(List<VJavaAst.Statement> statements) {
        List<String> choices = new ArrayList<>();
        collectChoicesRecursive(statements, choices);
        return choices;
    }

    private static void collectChoicesRecursive(List<VJavaAst.Statement> statements, List<String> choices) {
        if (statements == null) {
            return;
        }
        for (VJavaAst.Statement stmt : statements) {
            if (stmt instanceof VJavaAst.MenuStatement menu) {
                for (VJavaAst.MenuChoice choice : menu.choices()) {
                    if (choice.text() != null) {
                        choices.add(choice.text());
                    }
                    collectChoicesRecursive(choice.body(), choices);
                }
            } else if (stmt instanceof VJavaAst.IfStatement ifs) {
                for (VJavaAst.IfBranch branch : ifs.branches()) {
                    collectChoicesRecursive(branch.body(), choices);
                }
            } else if (stmt instanceof VJavaAst.MatchStatement match) {
                for (VJavaAst.MatchCase mc : match.cases()) {
                    collectChoicesRecursive(mc.body(), choices);
                }
                collectChoicesRecursive(match.defaultBody(), choices);
            }
        }
    }

    private static String readFileAtGitHead(Path repoRoot, String relUnixPath) {
        if (!Files.exists(repoRoot.resolve(".git"))) {
            return "";
        }
        try {
            Process proc = new ProcessBuilder("git", "show", "HEAD:" + relUnixPath)
                    .directory(repoRoot.toFile())
                    .start();
            byte[] out = proc.getInputStream().readAllBytes();
            boolean finished = proc.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return "";
            }
            return proc.exitValue() == 0 ? new String(out, StandardCharsets.UTF_8) : "";
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "";
        }
    }

    private static boolean isExcludedPath(Path root, Path file) {
        String rel = root.relativize(file).toString().replace('\\', '/');
        return rel.startsWith(".git/") || rel.startsWith("build/") || rel.startsWith("dist/");
    }
}
