package de.swfdtf.tools;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.graphics.Mesh3D;
import de.swfdtf.graphics.ObjectMotionController;
import de.swfdtf.graphics.Transition;
import de.swfdtf.parser.JavaBlockCompiler;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.parser.VJavaCompileException;
import de.swfdtf.parser.VJavaParseException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static analyzer and compiler validator for KAGAYAKI {@code .vjava} projects.
 *
 * <p>Validates entry label presence, static {@code jump}/{@code call}/{@code menu} targets,
 * label reachability, asset existence under {@code assets/}, speaker definitions, and
 * in-memory Java 21 compilation of all {@code @->{ ... }<<} blocks via {@link JavaBlockCompiler}.</p>
 */
public final class VJavaLinter {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern LABEL_REF_PATTERN = Pattern.compile("[A-Za-z_.][A-Za-z0-9_.]*");
    private static final Set<String> BUILTIN_TRANSFORM_NAMES = Set.of(
            "left", "center", "truecenter", "right", "offscreenleft", "offscreenright", "reset"
    );
    private static final Pattern SCREEN_LABEL_ACTION_PATTERN =
            Pattern.compile("\\b(?:Jump|Call)\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Set<String> IMAGE_ASSET_PROPERTIES = Set.of(
            "background", "ground", "idle", "hover", "selected_idle", "selected_hover", "insensitive"
    );

    /**
     * Diagnostic severity level emitted by {@link VJavaLinter}.
     */
    public enum Severity {
        /** Fatal issue blocking standalone builds or safe execution. */
        ERROR,
        /** Non-fatal diagnostic such as unreachable labels or undefined speaker IDs. */
        WARNING,
        /** Informational hint such as unused symbols that does not fail {@link LintReport#isClean()}. */
        INFO
    }

    /**
     * Single lint diagnostic associated with a source file and 1-based line number.
     *
     * @param severity   diagnostic severity
     * @param code       stable machine-readable diagnostic code
     * @param message    human-readable diagnostic explanation
     * @param sourceFile project-relative source file path (empty if project-wide)
     * @param lineNumber 1-based line number ({@code <= 0} if unknown)
     */
    public record LintIssue(
            Severity severity,
            String code,
            String message,
            String sourceFile,
            int lineNumber
    ) {
        /** Validates severity and normalizes null strings. */
        public LintIssue {
            Objects.requireNonNull(severity, "severity must not be null");
            code = code != null ? code : "LINT_ISSUE";
            message = message != null ? message : "";
            sourceFile = sourceFile != null ? sourceFile : "";
        }
    }

    /**
     * Aggregated result of linting a KAGAYAKI project.
     *
     * @param issues       immutable list of all emitted lint issues
     * @param errorCount   number of {@link Severity#ERROR} issues
     * @param warningCount number of {@link Severity#WARNING} issues
     */
    public record LintReport(
            List<LintIssue> issues,
            int errorCount,
            int warningCount
    ) {
        /** Normalizes null issue lists to an empty immutable list. */
        public LintReport {
            issues = issues != null ? List.copyOf(issues) : List.of();
        }

        /**
         * Returns {@code true} when neither errors nor warnings were reported.
         *
         * @return {@code true} if completely clean
         */
        public boolean isClean() {
            return errorCount == 0 && warningCount == 0;
        }

        /**
         * Returns {@code true} if at least one {@link Severity#ERROR} issue was reported.
         *
         * @return {@code true} when errors exist
         */
        public boolean hasErrors() {
            return errorCount > 0;
        }
    }

    private VJavaLinter() {
    }

    /**
     * Scans and lints the KAGAYAKI project directory at {@code projectDir}.
     *
     * @param projectDir root directory of the project
     * @return aggregated {@link LintReport}
     */
    public static LintReport lint(Path projectDir) {
        return lint(projectDir, false);
    }

    /**
     * Scans and lints the KAGAYAKI project directory at {@code projectDir}, optionally enforcing
     * 100% translation coverage across every declared target language.
     *
     * @param projectDir root directory of the project
     * @param strictI18n {@code true} to emit an {@link Severity#ERROR} whenever any language has
     *                   incomplete translation coverage
     * @return aggregated {@link LintReport}
     */
    public static LintReport lint(Path projectDir, boolean strictI18n) {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Path normalizedRoot = projectDir.toAbsolutePath().normalize();
        if (!Files.exists(normalizedRoot) || !Files.isDirectory(normalizedRoot)) {
            return createReport(List.of(new LintIssue(
                    Severity.ERROR,
                    "PROJECT_NOT_FOUND",
                    "Project directory does not exist or is not a directory: " + normalizedRoot,
                    "",
                    1
            )));
        }

        try {
            VJavaAst.ProjectAst project = ProjectScanner.scan(normalizedRoot);
            return lint(project, strictI18n);
        } catch (VJavaParseException parseEx) {
            return createReport(List.of(new LintIssue(
                    Severity.ERROR,
                    "PARSE_ERROR",
                    parseEx.getMessage(),
                    parseEx.getSourceFile(),
                    parseEx.getLineNumber()
            )));
        } catch (UncheckedIOException ioEx) {
            return createReport(List.of(new LintIssue(
                    Severity.ERROR,
                    "PROJECT_IO_ERROR",
                    ioEx.getMessage(),
                    ProjectDescriptor.FILE_NAME,
                    1
            )));
        }
    }

    /**
     * Lints an already scanned {@link VJavaAst.ProjectAst}.
     *
     * @param project unified project AST
     * @return aggregated {@link LintReport}
     */
    public static LintReport lint(VJavaAst.ProjectAst project) {
        return lint(project, false);
    }

    /**
     * Lints an already scanned {@link VJavaAst.ProjectAst}, optionally enforcing 100% translation
     * coverage across every declared target language.
     *
     * @param project    unified project AST
     * @param strictI18n {@code true} to emit an {@link Severity#ERROR} for incomplete translations
     * @return aggregated {@link LintReport}
     */
    public static LintReport lint(VJavaAst.ProjectAst project, boolean strictI18n) {
        Objects.requireNonNull(project, "project must not be null");
        List<LintIssue> issues = new ArrayList<>();

        for (String warning : project.warnings()) {
            issues.add(new LintIssue(
                    Severity.WARNING,
                    "DUPLICATE_DEFINITION",
                    warning,
                    "",
                    0
            ));
        }

        String entryLabel = resolveEntryLabel(project.descriptor());
        if (!project.labels().containsKey(entryLabel)) {
            issues.add(new LintIssue(
                    Severity.ERROR,
                    "MISSING_ENTRY_LABEL",
                    "Entry label '" + entryLabel + "' is not defined in any .vjava script.",
                    ProjectDescriptor.FILE_NAME,
                    1
            ));
        }

        Set<String> knownAssets = buildKnownAssetSet(project);
        checkDescriptorAssets(project, knownAssets, issues);
        checkImageDefinitions(project, knownAssets, issues);
        checkLayeredImageDefinitions(project, knownAssets, issues);
        checkScreenDefinitions(project, knownAssets, issues);
        checkTranslationSpeakers(project, issues);
        checkTranslationPlaceholdersAndTags(project, issues);
        if (strictI18n) {
            checkStrictTranslationCoverage(project, issues);
        }

        Map<String, Set<String>> callGraph = new LinkedHashMap<>();
        for (VJavaAst.LabelNode label : project.labels().values()) {
            Set<String> outgoing = callGraph.computeIfAbsent(label.name(), ignored -> new LinkedHashSet<>());
            checkStatements(project, label.name(), label.statements(), outgoing, knownAssets, issues);
        }

        checkUnreachableLabels(project, entryLabel, callGraph, issues);
        checkStrictTypes(project, issues);
        checkJavaBlocks(project, issues);
        checkDuplicateCollisionTriggers(project, issues);
        checkDeprecatedLabelTargets(project, issues);
        checkUnusedSymbols(project, issues);
        issues.addAll(DialogPacingLinter.analyze(project));

        List<LintIssue> effectiveIssues = applyProjectRuleOverrides(project.projectRoot(), issues);
        return createReport(effectiveIssues);
    }

    /** File name of optional per-project lint rule configuration ({@code #0153}). */
    public static final String LINT_CONFIG_FILE = ".vjavalint.json";

    private static List<LintIssue> applyProjectRuleOverrides(Path projectRoot, List<LintIssue> issues) {
        if (projectRoot == null || issues.isEmpty()) {
            return issues;
        }
        Path configPath = projectRoot.resolve(LINT_CONFIG_FILE);
        if (!Files.isRegularFile(configPath)) {
            return issues;
        }
        Map<String, String> overrides = new LinkedHashMap<>();
        try {
            String raw = Files.readString(configPath, StandardCharsets.UTF_8);
            JsonObject root = new Gson().fromJson(raw, JsonObject.class);
            if (root != null) {
                JsonObject rulesObj = root.has("rules") && root.get("rules").isJsonObject()
                        ? root.getAsJsonObject("rules")
                        : root;
                for (Map.Entry<String, JsonElement> entry : rulesObj.entrySet()) {
                    if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                        overrides.put(entry.getKey().trim().toUpperCase(Locale.ROOT),
                                entry.getValue().getAsString().trim().toUpperCase(Locale.ROOT));
                    }
                }
            }
        } catch (IOException | JsonParseException ignored) {
            return issues;
        }
        if (overrides.isEmpty()) {
            return issues;
        }
        List<LintIssue> adjusted = new ArrayList<>(issues.size());
        for (LintIssue issue : issues) {
            String ruleAction = overrides.get(issue.code().toUpperCase(Locale.ROOT));
            if ("OFF".equals(ruleAction) || "NONE".equals(ruleAction) || "IGNORE".equals(ruleAction)) {
                continue;
            }
            if ("ERROR".equals(ruleAction)) {
                adjusted.add(new LintIssue(
                        Severity.ERROR, issue.code(), issue.message(), issue.sourceFile(), issue.lineNumber()));
            } else if ("WARNING".equals(ruleAction) || "WARN".equals(ruleAction)) {
                adjusted.add(new LintIssue(
                        Severity.WARNING, issue.code(), issue.message(), issue.sourceFile(), issue.lineNumber()));
            } else if ("INFO".equals(ruleAction)) {
                adjusted.add(new LintIssue(
                        Severity.INFO, issue.code(), issue.message(), issue.sourceFile(), issue.lineNumber()));
            } else {
                adjusted.add(issue);
            }
        }
        return adjusted;
    }

    private static String resolveEntryLabel(ProjectDescriptor descriptor) {
        if (descriptor == null || descriptor.getEntryLabel() == null || descriptor.getEntryLabel().isBlank()) {
            return "start";
        }
        return descriptor.getEntryLabel().trim();
    }

    private static Set<String> buildKnownAssetSet(VJavaAst.ProjectAst project) {
        Set<String> known = new HashSet<>();
        for (String asset : project.discoveredAssets()) {
            known.add(normalizeAssetPath(asset));
        }
        return known;
    }

    private static void checkDescriptorAssets(
            VJavaAst.ProjectAst project,
            Set<String> knownAssets,
            List<LintIssue> issues
    ) {
        ProjectDescriptor descriptor = project.descriptor();
        if (descriptor != null && descriptor.getIconPath() != null && !descriptor.getIconPath().isBlank()) {
            verifyAssetExists(
                    project,
                    knownAssets,
                    descriptor.getIconPath(),
                    ProjectDescriptor.FILE_NAME,
                    1,
                    issues
            );
        }
    }

    private static void checkImageDefinitions(
            VJavaAst.ProjectAst project,
            Set<String> knownAssets,
            List<LintIssue> issues
    ) {
        for (VJavaAst.ImageDef img : project.images().values()) {
            verifyAssetExists(project, knownAssets, img.assetPath(), img.sourceFile(), img.lineNumber(), issues);
        }
    }

    private static void checkLayeredImageDefinitions(
            VJavaAst.ProjectAst project,
            Set<String> knownAssets,
            List<LintIssue> issues
    ) {
        for (VJavaAst.LayeredImageDef layered : project.layeredImages().values()) {
            for (VJavaAst.LayeredEntry entry : layered.entries()) {
                if (entry instanceof VJavaAst.AlwaysLayerEntry always) {
                    checkLiteralAssetCandidate(
                            project,
                            knownAssets,
                            always.assetOrExpr(),
                            layered.sourceFile(),
                            layered.lineNumber(),
                            issues
                    );
                } else if (entry instanceof VJavaAst.GroupLayerEntry group) {
                    for (VJavaAst.AttributeEntry attr : group.attributes()) {
                        checkLiteralAssetCandidate(
                                project,
                                knownAssets,
                                attr.assetOrExpr(),
                                layered.sourceFile(),
                                layered.lineNumber(),
                                issues
                        );
                    }
                } else if (entry instanceof VJavaAst.ConditionalLayerEntry cond) {
                    checkLiteralAssetCandidate(
                            project,
                            knownAssets,
                            cond.assetOrExpr(),
                            layered.sourceFile(),
                            layered.lineNumber(),
                            issues
                    );
                }
            }
        }
    }

    private static void checkScreenDefinitions(
            VJavaAst.ProjectAst project,
            Set<String> knownAssets,
            List<LintIssue> issues
    ) {
        for (VJavaAst.ScreenDef screen : project.screens().values()) {
            for (VJavaAst.ScreenItem item : screen.items()) {
                checkScreenItemAssets(project, knownAssets, item, issues);
            }
        }
    }

    private static void checkScreenItemAssets(
            VJavaAst.ProjectAst project,
            Set<String> knownAssets,
            VJavaAst.ScreenItem item,
            List<LintIssue> issues
    ) {
        for (Map.Entry<String, String> prop : item.properties().entrySet()) {
            String key = prop.getKey().toLowerCase(Locale.ROOT);
            String val = stripQuotes(prop.getValue());
            if (IMAGE_ASSET_PROPERTIES.contains(key) && isLiteralAssetPath(val)) {
                verifyAssetExists(project, knownAssets, val, item.sourceFile(), item.lineNumber(), issues);
            }
        }
        if ("add".equalsIgnoreCase(item.keyword()) && item.primaryArg() != null) {
            String arg = stripQuotes(item.primaryArg());
            if (isLiteralAssetPath(arg)) {
                verifyAssetExists(project, knownAssets, arg, item.sourceFile(), item.lineNumber(), issues);
            }
        }
        for (VJavaAst.ScreenItem child : item.children()) {
            checkScreenItemAssets(project, knownAssets, child, issues);
        }
    }

    private static void checkTranslationSpeakers(VJavaAst.ProjectAst project, List<LintIssue> issues) {
        for (VJavaAst.TranslateBlock block : project.translations()) {
            for (VJavaAst.TranslateEntry entry : block.entries()) {
                if (entry.speaker() != null && !entry.speaker().isBlank()) {
                    String speakerId = entry.speaker().trim();
                    if (!project.characters().containsKey(speakerId)) {
                        issues.add(new LintIssue(
                                Severity.WARNING,
                                "UNDEFINED_SPEAKER",
                                "Undefined speaker ID '" + speakerId + "' in translate block '" + block.id() + "'.",
                                block.sourceFile(),
                                block.lineNumber()
                        ));
                    }
                }
            }
        }
    }

    private static void checkTranslationPlaceholdersAndTags(VJavaAst.ProjectAst project, List<LintIssue> issues) {
        for (VJavaAst.TranslateBlock block : project.translations()) {
            for (VJavaAst.TranslateEntry entry : block.entries()) {
                String orig = entry.originalText();
                String trans = entry.translatedText();
                if (orig == null || orig.isBlank() || trans == null || trans.isBlank()) {
                    continue;
                }
                Set<String> expectedPlaceholders = extractBracketPlaceholders(orig);
                Set<String> actualPlaceholders = extractBracketPlaceholders(trans);
                Set<String> expectedTags = extractStyleTags(orig);
                Set<String> actualTags = extractStyleTags(trans);

                List<String> missing = new ArrayList<>();
                for (String placeholder : expectedPlaceholders) {
                    if (!actualPlaceholders.contains(placeholder)) {
                        missing.add("[" + placeholder + "]");
                    }
                }
                for (String tag : expectedTags) {
                    if (!actualTags.contains(tag)) {
                        missing.add("{" + tag + "}");
                    }
                }
                if (!missing.isEmpty()) {
                    issues.add(new LintIssue(
                            Severity.WARNING,
                            "TRANSLATION_PLACEHOLDER_MISMATCH",
                            "Translation in block '" + block.id() + "' (" + block.language()
                                    + ") omits source placeholders/tags: " + String.join(", ", missing),
                            block.sourceFile(),
                            block.lineNumber()
                    ));
                }
            }
        }
    }

    private static void checkStrictTranslationCoverage(VJavaAst.ProjectAst project, List<LintIssue> issues) {
        TranslationCoverageAuditor.CoverageReport report = new TranslationCoverageAuditor().audit(project);
        if (report.totalDialogueLines() <= 0) {
            return;
        }
        for (TranslationCoverageAuditor.LanguageCoverage cov : report.languages().values()) {
            if (cov.coveragePercent() < 100.0) {
                issues.add(new LintIssue(
                        Severity.ERROR,
                        "INCOMPLETE_TRANSLATION_COVERAGE",
                        "Language '" + cov.language() + "' has incomplete translation coverage: "
                                + cov.translatedEntries() + "/" + cov.expectedEntries()
                                + " (" + String.format(Locale.US, "%.1f%%", cov.coveragePercent()) + ").",
                        "tl/" + cov.language() + "/translations.vjava",
                        1
                ));
            }
        }
    }

    private static Set<String> extractBracketPlaceholders(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        int i = 0;
        int len = text.length();
        while (i < len) {
            char c = text.charAt(i);
            if (c == '[' && i + 1 < len && text.charAt(i + 1) == '[') {
                i += 2;
                continue;
            }
            if (c == '[') {
                int close = text.indexOf(']', i + 1);
                if (close > i + 1) {
                    String inner = text.substring(i + 1, close).trim();
                    if (!inner.isEmpty()) {
                        tokens.add(inner);
                    }
                    i = close + 1;
                    continue;
                }
            }
            i++;
        }
        return tokens;
    }

    private static Set<String> extractStyleTags(String text) {
        Set<String> tags = new LinkedHashSet<>();
        int i = 0;
        int len = text.length();
        while (i < len) {
            char c = text.charAt(i);
            if (c == '{' && i + 1 < len && text.charAt(i + 1) == '{') {
                i += 2;
                continue;
            }
            if (c == '{') {
                int close = findMatchingClosingBrace(text, i);
                if (close > i + 1) {
                    String inner = text.substring(i + 1, close).trim();
                    if (isIcuExpression(inner)) {
                        i = close + 1;
                        continue;
                    }
                    if (!inner.isEmpty()) {
                        tags.add(inner.toLowerCase(Locale.ROOT));
                    }
                    i = close + 1;
                    continue;
                }
            }
            i++;
        }
        return tags;
    }

    private static int findMatchingClosingBrace(String text, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isIcuExpression(String inner) {
        int comma1 = inner.indexOf(',');
        if (comma1 <= 0) {
            return false;
        }
        int comma2 = inner.indexOf(',', comma1 + 1);
        if (comma2 <= comma1 + 1) {
            return false;
        }
        String kind = inner.substring(comma1 + 1, comma2).trim().toLowerCase(Locale.ROOT);
        return "plural".equals(kind) || "select".equals(kind);
    }

    private static void checkStatements(
            VJavaAst.ProjectAst project,
            String currentLabel,
            List<VJavaAst.Statement> statements,
            Set<String> outgoingEdges,
            Set<String> knownAssets,
            List<LintIssue> issues
    ) {
        for (VJavaAst.Statement stmt : statements) {
            if (stmt instanceof VJavaAst.JumpStatement jump) {
                if (!jump.isExpression()) {
                    String rawTarget = jump.targetLabel() != null ? jump.targetLabel().trim() : "";
                    String resolved = resolveNamespaceLabel(project, currentLabel, rawTarget);
                    if (resolved == null) {
                        issues.add(new LintIssue(
                                Severity.ERROR,
                                "MISSING_JUMP_TARGET",
                                "Jump target label '" + rawTarget + "' is not defined.",
                                jump.sourceFile(),
                                jump.lineNumber()
                        ));
                    } else {
                        outgoingEdges.add(resolved);
                    }
                }
            } else if (stmt instanceof VJavaAst.CallStatement call) {
                String rawTarget = call.targetLabel() != null ? call.targetLabel().trim() : "";
                if (LABEL_REF_PATTERN.matcher(rawTarget).matches()) {
                    String resolved = resolveNamespaceLabel(project, currentLabel, rawTarget);
                    if (resolved == null) {
                        issues.add(new LintIssue(
                                Severity.ERROR,
                                "MISSING_CALL_TARGET",
                                "Call target label '" + rawTarget + "' is not defined.",
                                call.sourceFile(),
                                call.lineNumber()
                        ));
                    } else {
                        outgoingEdges.add(resolved);
                    }
                }
            } else if (stmt instanceof VJavaAst.MenuStatement menu) {
                if (menu.timeoutJumpLabel() != null && !menu.timeoutJumpLabel().isBlank()) {
                    String rawTimeout = menu.timeoutJumpLabel().trim();
                    String resolved = resolveNamespaceLabel(project, currentLabel, rawTimeout);
                    if (resolved == null) {
                        issues.add(new LintIssue(
                                Severity.ERROR,
                                "MISSING_TIMEOUT_TARGET",
                                "Menu timeout target label '" + rawTimeout + "' is not defined.",
                                menu.sourceFile(),
                                menu.lineNumber()
                        ));
                    } else {
                        outgoingEdges.add(resolved);
                    }
                }
                for (VJavaAst.MenuChoice choice : menu.choices()) {
                    if (choice.inlineJumpTarget() != null && !choice.inlineJumpTarget().isBlank()) {
                        String rawChoice = choice.inlineJumpTarget().trim();
                        String resolved = resolveNamespaceLabel(project, currentLabel, rawChoice);
                        if (resolved == null) {
                            issues.add(new LintIssue(
                                    Severity.ERROR,
                                    "MISSING_MENU_TARGET",
                                    "Menu choice target label '" + rawChoice + "' is not defined.",
                                    choice.sourceFile(),
                                    choice.lineNumber()
                            ));
                        } else {
                            outgoingEdges.add(resolved);
                        }
                    }
                    checkStatements(project, currentLabel, choice.body(), outgoingEdges, knownAssets, issues);
                }
            } else if (stmt instanceof VJavaAst.IfStatement ifStmt) {
                for (VJavaAst.IfBranch branch : ifStmt.branches()) {
                    checkStatements(project, currentLabel, branch.body(), outgoingEdges, knownAssets, issues);
                }
            } else if (stmt instanceof VJavaAst.MatchStatement matchStmt) {
                Set<String> seenPatterns = new HashSet<>();
                for (VJavaAst.MatchCase mc : matchStmt.cases()) {
                    String normPattern = mc.patternExpr() != null ? mc.patternExpr().trim() : "";
                    if (!seenPatterns.add(normPattern)) {
                        issues.add(new LintIssue(
                                Severity.ERROR,
                                "DUPLICATE_MATCH_CASE",
                                "Duplicate match case pattern '" + normPattern + "'.",
                                mc.sourceFile(),
                                mc.lineNumber()
                        ));
                    }
                    checkStatements(project, currentLabel, mc.body(), outgoingEdges, knownAssets, issues);
                }
                if (matchStmt.defaultBody() == null) {
                    issues.add(new LintIssue(
                            Severity.WARNING,
                            "MATCH_MISSING_DEFAULT",
                            "Non-exhaustive match statement is missing a 'default:' branch.",
                            matchStmt.sourceFile(),
                            matchStmt.lineNumber()
                    ));
                } else {
                    checkStatements(project, currentLabel, matchStmt.defaultBody(),
                            outgoingEdges, knownAssets, issues);
                }
            } else if (stmt instanceof VJavaAst.ParallelStatement parallelStmt) {
                checkStatements(project, currentLabel, parallelStmt.statements(),
                        outgoingEdges, knownAssets, issues);
            } else if (stmt instanceof VJavaAst.RepeatStatement repStmt) {
                checkStatements(project, currentLabel, repStmt.body(), outgoingEdges, knownAssets, issues);
            } else if (stmt instanceof VJavaAst.WhileStatement whileStmt) {
                checkStatements(project, currentLabel, whileStmt.body(), outgoingEdges, knownAssets, issues);
            } else if (stmt instanceof VJavaAst.RandomStatement rndStmt) {
                for (VJavaAst.RandomBranch rb : rndStmt.branches()) {
                    checkStatements(project, currentLabel, rb.body(), outgoingEdges, knownAssets, issues);
                }
            } else if (stmt instanceof VJavaAst.AfterTimerStatement afterStmt) {
                checkResolvedTarget(project, currentLabel, afterStmt.targetLabel(), "MISSING_TIMER_TARGET",
                        "Timer target label", afterStmt.sourceFile(), afterStmt.lineNumber(),
                        outgoingEdges, issues);
            } else if (stmt instanceof VJavaAst.OnInteractStatement interactStmt) {
                checkResolvedTarget(project, currentLabel, interactStmt.targetLabel(), "MISSING_INTERACT_TARGET",
                        "Interact target label", interactStmt.sourceFile(), interactStmt.lineNumber(),
                        outgoingEdges, issues);
            } else if (stmt instanceof VJavaAst.OnTweenEndStatement tweenEndStmt) {
                checkResolvedTarget(project, currentLabel, tweenEndStmt.targetLabel(), "MISSING_TWEEN_END_TARGET",
                        "Tween-end target label", tweenEndStmt.sourceFile(), tweenEndStmt.lineNumber(),
                        outgoingEdges, issues);
            } else if (stmt instanceof VJavaAst.OnEventStatement eventStmt) {
                checkResolvedTarget(project, currentLabel, eventStmt.targetLabel(), "MISSING_EVENT_TARGET",
                        "Event target label", eventStmt.sourceFile(), eventStmt.lineNumber(),
                        outgoingEdges, issues);
            } else if (stmt instanceof VJavaAst.SpawnCutsceneStatement cutsceneStmt) {
                checkResolvedTarget(project, currentLabel, cutsceneStmt.targetLabel(), "MISSING_CUTSCENE_TARGET",
                        "Cutscene target label", cutsceneStmt.sourceFile(), cutsceneStmt.lineNumber(),
                        outgoingEdges, issues);
            } else if (stmt instanceof VJavaAst.SayStatement say) {
                if (say.speakerId() != null && !say.speakerId().isBlank()) {
                    String speakerId = say.speakerId().trim();
                    if (!project.characters().containsKey(speakerId)) {
                        issues.add(new LintIssue(
                                Severity.WARNING,
                                "UNDEFINED_SPEAKER",
                                "Undefined speaker ID '" + speakerId
                                        + "' (missing 'define " + speakerId + " = Character(...)').",
                                say.sourceFile(),
                                say.lineNumber()
                        ));
                    }
                }
            } else if (stmt instanceof VJavaAst.SceneStatement scene) {
                verifySceneOrShowAsset(
                        project,
                        knownAssets,
                        scene.bgTag(),
                        List.of(),
                        scene.sourceFile(),
                        scene.lineNumber(),
                        issues
                );
                verifyTransitionAssets(
                        project, knownAssets, scene.transitionExpr(), scene.sourceFile(), scene.lineNumber(), issues
                );
            } else if (stmt instanceof VJavaAst.ShowStatement show) {
                verifySceneOrShowAsset(
                        project,
                        knownAssets,
                        show.tag(),
                        show.attributes(),
                        show.sourceFile(),
                        show.lineNumber(),
                        issues
                );
                checkTransformReference(project, show.positionExpr(), show.sourceFile(), show.lineNumber(), issues);
                verifyTransitionAssets(
                        project, knownAssets, show.transitionExpr(), show.sourceFile(), show.lineNumber(), issues
                );
            } else if (stmt instanceof VJavaAst.HideStatement hide) {
                verifyTransitionAssets(
                        project, knownAssets, hide.transitionExpr(), hide.sourceFile(), hide.lineNumber(), issues
                );
            } else if (stmt instanceof VJavaAst.WithStatement withStmt) {
                verifyTransitionAssets(
                        project,
                        knownAssets,
                        withStmt.transitionExpr(),
                        withStmt.sourceFile(),
                        withStmt.lineNumber(),
                        issues
                );
            } else if (stmt instanceof VJavaAst.PlayStatement play) {
                verifyAssetExists(project, knownAssets, play.assetPath(), play.sourceFile(), play.lineNumber(), issues);
                verifyAudioFormatAndDuration(project, play.assetPath(), play.fadeInSeconds(),
                        play.sourceFile(), play.lineNumber(), issues);
            } else if (stmt instanceof VJavaAst.QueueStatement queue) {
                for (String path : queue.assetPaths()) {
                    verifyAssetExists(project, knownAssets, path, queue.sourceFile(), queue.lineNumber(), issues);
                    verifyAudioFormatAndDuration(project, path, 0.0,
                            queue.sourceFile(), queue.lineNumber(), issues);
                }
            } else if (stmt instanceof VJavaAst.VoiceStatement voice) {
                verifyAssetExists(
                        project, knownAssets, voice.assetPath(), voice.sourceFile(), voice.lineNumber(), issues);
                verifyAudioFormatAndDuration(project, voice.assetPath(), 0.0,
                        voice.sourceFile(), voice.lineNumber(), issues);
            } else if (stmt instanceof VJavaAst.OnCollideStatement collide) {
                checkResolvedTarget(project, currentLabel, collide.targetLabel(), "MISSING_COLLIDE_TARGET",
                        "Collision target label", collide.sourceFile(), collide.lineNumber(),
                        outgoingEdges, issues);
            } else if (stmt instanceof VJavaAst.OnOverlapStatement overlap) {
                checkResolvedTarget(project, currentLabel, overlap.targetLabel(), "MISSING_OVERLAP_TARGET",
                        "Overlap target label", overlap.sourceFile(), overlap.lineNumber(),
                        outgoingEdges, issues);
            } else if (stmt instanceof VJavaAst.TriggerZoneStatement zone) {
                checkResolvedTarget(project, currentLabel, zone.targetLabel(), "MISSING_TRIGGER_TARGET",
                        "Trigger zone target label", zone.sourceFile(), zone.lineNumber(),
                        outgoingEdges, issues);
            } else if (stmt instanceof VJavaAst.MovePathStatement path) {
                checkDurationAndEasing(
                        path.tag(), path.durationSeconds(), path.easing(),
                        path.sourceFile(), path.lineNumber(), issues);
            } else if (stmt instanceof VJavaAst.RotateStatement rot) {
                checkDurationAndEasing(
                        rot.tag(), rot.durationSeconds(), rot.easing(),
                        rot.sourceFile(), rot.lineNumber(), issues);
            } else if (stmt instanceof VJavaAst.ScaleStatement scale) {
                checkDurationAndEasing(
                        scale.tag(), scale.durationSeconds(), scale.easing(),
                        scale.sourceFile(), scale.lineNumber(), issues);
            } else if (stmt instanceof VJavaAst.MoveStatement move) {
                if (move.durationSeconds() < 0.0) {
                    issues.add(new LintIssue(
                            Severity.WARNING,
                            "INVALID_MOVE_DURATION",
                            "Negative move duration " + move.durationSeconds() + " for sprite '" + move.tag() + "'.",
                            move.sourceFile(),
                            move.lineNumber()
                    ));
                }
                if (move.easing() != null && !ObjectMotionController.SUPPORTED_EASINGS.contains(
                        move.easing().trim().toLowerCase(Locale.ROOT))) {
                    issues.add(new LintIssue(
                            Severity.WARNING,
                            "UNKNOWN_EASING",
                            "Unknown easing '" + move.easing() + "' for sprite '" + move.tag() + "'.",
                            move.sourceFile(),
                            move.lineNumber()
                    ));
                }
            } else if (stmt instanceof VJavaAst.WasdInitStatement wasd) {
                if (wasd.speedPxPerSec() <= 0.0) {
                    issues.add(new LintIssue(
                            Severity.WARNING,
                            "INVALID_WASD_SPEED",
                            "WASD speed must be positive for sprite '" + wasd.tag() + "'.",
                            wasd.sourceFile(),
                            wasd.lineNumber()
                    ));
                }
                if (wasd.minX() != null && wasd.maxX() != null && wasd.minX() >= wasd.maxX()
                        || wasd.minY() != null && wasd.maxY() != null && wasd.minY() >= wasd.maxY()) {
                    issues.add(new LintIssue(
                            Severity.WARNING,
                            "INVALID_WASD_BOUNDS",
                            "WASD bounds must have min < max for sprite '" + wasd.tag() + "'.",
                            wasd.sourceFile(),
                            wasd.lineNumber()
                    ));
                }
            } else if (stmt instanceof VJavaAst.PhysicsInitStatement phys) {
                if (phys.gravityPxPerSec2() < 0.0 || phys.jumpForcePxPerSec() < 0.0) {
                    issues.add(new LintIssue(
                            Severity.WARNING,
                            "INVALID_PHYSICS_CONFIG",
                            "Physics gravity and jump_force must be >= 0 for sprite '" + phys.tag() + "'.",
                            phys.sourceFile(),
                            phys.lineNumber()
                    ));
                }
            } else if (stmt instanceof VJavaAst.Scene3DStatement scene3d) {
                verifyMeshAssetAndPolyBudget(
                        project,
                        knownAssets,
                        scene3d.prefabOrAsset(),
                        true,
                        scene3d.sourceFile(),
                        scene3d.lineNumber(),
                        issues
                );
            } else if (stmt instanceof VJavaAst.Spawn3DStatement spawn3d) {
                verifyMeshAssetAndPolyBudget(
                        project,
                        knownAssets,
                        spawn3d.meshAsset(),
                        true,
                        spawn3d.sourceFile(),
                        spawn3d.lineNumber(),
                        issues
                );
            }
        }
    }

    private static final int HIGH_POLY_VERTEX_THRESHOLD = 100_000;
    private static final Set<String> BUILTIN_3D_PREFAB_KEYWORDS = Set.of(
            "cube", "sphere", "plane", "cylinder", "hall", "courtyard", "arena", "default", "empty"
    );

    private static void verifyMeshAssetAndPolyBudget(
            VJavaAst.ProjectAst project,
            Set<String> knownAssets,
            String rawMeshRef,
            boolean checkVertexBudget,
            String sourceFile,
            int lineNumber,
            List<LintIssue> issues
    ) {
        if (rawMeshRef == null || rawMeshRef.isBlank()) {
            return;
        }
        String stripped = stripQuotes(rawMeshRef);
        String lower = stripped.toLowerCase(Locale.ROOT);
        if (!stripped.contains("/") && BUILTIN_3D_PREFAB_KEYWORDS.contains(lower)) {
            return;
        }
        verifyAssetExists(project, knownAssets, stripped, sourceFile, lineNumber, issues);
        if (!checkVertexBudget || project.projectRoot() == null) {
            return;
        }
        String normalized = normalizeAssetPath(stripped);
        try {
            Path meshFile = project.projectRoot().resolve(normalized).normalize();
            if (!Files.isRegularFile(meshFile)) {
                return;
            }
            int vertexCount = 0;
            if (lower.endsWith(".obj")) {
                vertexCount = countObjVertices(meshFile);
            } else if (lower.endsWith(".glb")) {
                Mesh3D mesh = Mesh3D.loadGlb(meshFile);
                vertexCount = mesh.getVertexCount();
            }
            if (vertexCount > HIGH_POLY_VERTEX_THRESHOLD) {
                issues.add(new LintIssue(
                        Severity.WARNING,
                        "HIGH_POLY_MESH",
                        "3D mesh '" + normalized + "' has " + vertexCount
                                + " vertices (exceeds " + HIGH_POLY_VERTEX_THRESHOLD + ").",
                        sourceFile,
                        lineNumber
                ));
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Invalid or non-mesh files are reported by missing asset or runtime loader checks.
        }
    }

    private static int countObjVertices(Path objFile) throws IOException {
        int rawPositions = 0;
        for (String line : Files.readAllLines(objFile, StandardCharsets.UTF_8)) {
            if (line.startsWith("v ")) {
                rawPositions++;
            }
        }
        return rawPositions;
    }

    private static void verifyAudioFormatAndDuration(
            VJavaAst.ProjectAst project,
            String rawAudioPath,
            double fadeDurationSeconds,
            String sourceFile,
            int lineNumber,
            List<LintIssue> issues
    ) {
        if (!Double.isFinite(fadeDurationSeconds) || fadeDurationSeconds < 0.0) {
            issues.add(new LintIssue(
                    Severity.ERROR,
                    "NEGATIVE_FADE_DURATION",
                    "Audio fade/crossfade duration must be >= 0, got: " + fadeDurationSeconds,
                    sourceFile,
                    lineNumber
            ));
        }
        if (rawAudioPath == null || rawAudioPath.isBlank()) {
            return;
        }
        String normalized = normalizeAssetPath(stripQuotes(rawAudioPath));
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".aac") || lower.endsWith(".m4a")) {
            issues.add(new LintIssue(
                    Severity.ERROR,
                    "UNSUPPORTED_AUDIO_FORMAT",
                    "Unsupported audio format '" + normalized + "'. Please convert to Ogg Vorbis (.ogg) or WAV (.wav).",
                    sourceFile,
                    lineNumber
            ));
            return;
        }
        if (project.projectRoot() == null) {
            return;
        }
        try {
            Path audioFile = project.projectRoot().resolve(normalized).normalize();
            if (!Files.isRegularFile(audioFile)) {
                return;
            }
            byte[] header = Files.readAllBytes(audioFile);
            if (header.length == 0) {
                issues.add(new LintIssue(
                        Severity.ERROR,
                        "UNSUPPORTED_AUDIO_FORMAT",
                        "Empty (0-byte) audio file: '" + normalized + "'.",
                        sourceFile,
                        lineNumber
                ));
            } else if ((lower.endsWith(".wav") || lower.endsWith(".wave")) && header.length >= 12) {
                boolean isRiffWave = header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                        && header[8] == 'W' && header[9] == 'A' && header[10] == 'V' && header[11] == 'E';
                if (!isRiffWave) {
                    issues.add(new LintIssue(
                            Severity.ERROR,
                            "UNSUPPORTED_AUDIO_FORMAT",
                            "File '" + normalized + "' does not have a valid RIFF/WAVE header.",
                            sourceFile,
                            lineNumber
                    ));
                }
            } else if ((lower.endsWith(".ogg") || lower.endsWith(".oga")) && header.length >= 12) {
                boolean isOgg = header[0] == 'O' && header[1] == 'g' && header[2] == 'g' && header[3] == 'S';
                if (!isOgg) {
                    issues.add(new LintIssue(
                            Severity.ERROR,
                            "UNSUPPORTED_AUDIO_FORMAT",
                            "File '" + normalized + "' does not have a valid OggS Vorbis header.",
                            sourceFile,
                            lineNumber
                    ));
                }
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // File accessibility errors are handled by asset inspection passes.
        }
    }

    private static void checkUnreachableLabels(
            VJavaAst.ProjectAst project,
            String entryLabel,
            Map<String, Set<String>> callGraph,
            List<LintIssue> issues
    ) {
        if (!project.labels().containsKey(entryLabel)) {
            return;
        }

        Set<String> reachable = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        reachable.add(entryLabel);
        queue.add(entryLabel);

        for (VJavaAst.ScreenDef screen : project.screens().values()) {
            collectScreenReferencedLabels(screen.items(), queue, reachable, project.labels().keySet());
        }

        while (!queue.isEmpty()) {
            String current = queue.poll();
            Set<String> nextTargets = callGraph.getOrDefault(current, Set.of());
            for (String target : nextTargets) {
                if (project.labels().containsKey(target) && reachable.add(target)) {
                    queue.add(target);
                }
            }
        }

        for (VJavaAst.LabelNode label : project.labels().values()) {
            if (!reachable.contains(label.name())) {
                issues.add(new LintIssue(
                        Severity.WARNING,
                        "UNREACHABLE_LABEL",
                        "Label '" + label.name() + "' is unreachable from entry label '" + entryLabel + "'.",
                        label.sourceFile(),
                        label.lineNumber()
                ));
            }
        }
    }

    private static void collectScreenReferencedLabels(
            List<VJavaAst.ScreenItem> items,
            Queue<String> queue,
            Set<String> reachable,
            Set<String> allLabels
    ) {
        for (VJavaAst.ScreenItem item : items) {
            for (String propVal : item.properties().values()) {
                Matcher m = SCREEN_LABEL_ACTION_PATTERN.matcher(propVal);
                while (m.find()) {
                    String target = m.group(1).trim();
                    if (allLabels.contains(target) && reachable.add(target)) {
                        queue.add(target);
                    }
                }
            }
            collectScreenReferencedLabels(item.children(), queue, reachable, allLabels);
        }
    }

    private static void checkJavaBlocks(VJavaAst.ProjectAst project, List<LintIssue> issues) {
        List<VJavaAst.JavaBlockNode> blocks = JavaBlockCompiler.collectJavaBlocks(project);
        if (blocks.isEmpty()) {
            return;
        }
        checkBlockingCallsInJavaBlocks(blocks, issues);

        JavaBlockCompiler compiler = new JavaBlockCompiler();
        try {
            compiler.compile(project);
        } catch (VJavaCompileException compileEx) {
            List<VJavaCompileException.CompileDiagnostic> diagnostics = compileEx.getDiagnostics();
            if (diagnostics.isEmpty()) {
                issues.add(new LintIssue(
                        Severity.ERROR,
                        "JAVA_COMPILE_ERROR",
                        compileEx.getMessage(),
                        compileEx.getSourceFile(),
                        compileEx.getLineNumber()
                ));
                return;
            }
            boolean emittedError = false;
            for (VJavaCompileException.CompileDiagnostic diag : diagnostics) {
                if ("ERROR".equalsIgnoreCase(diag.kind())) {
                    emittedError = true;
                    issues.add(new LintIssue(
                            Severity.ERROR,
                            "JAVA_COMPILE_ERROR",
                            diag.message(),
                            diag.sourceFile(),
                            diag.lineNumber()
                    ));
                }
            }
            if (!emittedError) {
                VJavaCompileException.CompileDiagnostic first = diagnostics.get(0);
                issues.add(new LintIssue(
                        Severity.ERROR,
                        "JAVA_COMPILE_ERROR",
                        first.message(),
                        first.sourceFile(),
                        first.lineNumber()
                ));
            }
        }
    }

    private static void checkBlockingCallsInJavaBlocks(
            List<VJavaAst.JavaBlockNode> blocks,
            List<LintIssue> issues
    ) {
        for (VJavaAst.JavaBlockNode block : blocks) {
            if (block.code() != null && block.code().contains("Thread.sleep(")) {
                issues.add(new LintIssue(
                        Severity.WARNING,
                        "BLOCKING_CALL_IN_JAVA_BLOCK",
                        "Blocking call 'Thread.sleep(...)' in Java block stalls the render thread; "
                                + "prefer DSL 'pause <sec>'.",
                        block.sourceFile(),
                        block.lineNumber()
                ));
            }
        }
    }

    private static void checkDuplicateCollisionTriggers(VJavaAst.ProjectAst project, List<LintIssue> issues) {
        for (VJavaAst.LabelNode label : project.labels().values()) {
            Map<String, VJavaAst.OnCollideStatement> seenPairs = new LinkedHashMap<>();
            collectCollisionDuplicates(label.statements(), seenPairs, issues);
        }
    }

    private static void collectCollisionDuplicates(
            List<VJavaAst.Statement> statements,
            Map<String, VJavaAst.OnCollideStatement> seenPairs,
            List<LintIssue> issues
    ) {
        if (statements == null || statements.isEmpty()) {
            return;
        }
        for (VJavaAst.Statement stmt : statements) {
            if (stmt instanceof VJavaAst.OnCollideStatement collide) {
                String a = collide.tagA() != null ? collide.tagA().trim().toLowerCase(Locale.ROOT) : "";
                String b = collide.tagB() != null ? collide.tagB().trim().toLowerCase(Locale.ROOT) : "";
                String pairKey = a.compareTo(b) <= 0 ? (a + "|" + b) : (b + "|" + a);
                VJavaAst.OnCollideStatement first = seenPairs.putIfAbsent(pairKey, collide);
                if (first != null) {
                    issues.add(new LintIssue(
                            Severity.WARNING,
                            "DUPLICATE_COLLISION_TRIGGER",
                            "Duplicate on_collide trigger for sprite pair '" + collide.tagA() + "' / '"
                                    + collide.tagB() + "' (first declared at line " + first.lineNumber()
                                    + ", duplicated at line " + collide.lineNumber() + ").",
                            collide.sourceFile(),
                            collide.lineNumber()
                    ));
                }
            } else {
                for (List<VJavaAst.Statement> nested : childStatementLists(stmt)) {
                    collectCollisionDuplicates(nested, seenPairs, issues);
                }
            }
        }
    }

    private static List<List<VJavaAst.Statement>> childStatementLists(VJavaAst.Statement stmt) {
        List<List<VJavaAst.Statement>> lists = new ArrayList<>();
        if (stmt instanceof VJavaAst.MenuStatement menu) {
            for (VJavaAst.MenuChoice choice : menu.choices()) {
                lists.add(choice.body());
            }
        } else if (stmt instanceof VJavaAst.IfStatement ifs) {
            for (VJavaAst.IfBranch branch : ifs.branches()) {
                lists.add(branch.body());
            }
        } else if (stmt instanceof VJavaAst.MatchStatement ms) {
            for (VJavaAst.MatchCase mc : ms.cases()) {
                lists.add(mc.body());
            }
            if (ms.defaultBody() != null) {
                lists.add(ms.defaultBody());
            }
        } else if (stmt instanceof VJavaAst.ParallelStatement ps) {
            lists.add(ps.statements());
        } else if (stmt instanceof VJavaAst.RepeatStatement rs) {
            lists.add(rs.body());
        } else if (stmt instanceof VJavaAst.WhileStatement ws) {
            lists.add(ws.body());
        } else if (stmt instanceof VJavaAst.RandomStatement rndStmt) {
            for (VJavaAst.RandomBranch rb : rndStmt.branches()) {
                lists.add(rb.body());
            }
        }
        return lists;
    }

    private static void checkDeprecatedLabelTargets(VJavaAst.ProjectAst project, List<LintIssue> issues) {
        if (project.projectRoot() == null) {
            return;
        }
        Map<String, List<String>> fileLines = new HashMap<>();
        Map<String, String> deprecatedReasons = new LinkedHashMap<>();
        for (VJavaAst.LabelNode label : project.labels().values()) {
            String comment = readPrecedingDeprecatedNote(project.projectRoot(), label, fileLines);
            if (comment != null) {
                deprecatedReasons.put(label.name(), comment);
            }
        }
        if (deprecatedReasons.isEmpty()) {
            return;
        }
        for (VJavaAst.LabelNode label : project.labels().values()) {
            checkDeprecatedJumps(project, label.name(), label.statements(), deprecatedReasons, issues);
        }
    }

    private static String readPrecedingDeprecatedNote(
            Path projectRoot,
            VJavaAst.LabelNode label,
            Map<String, List<String>> fileCache
    ) {
        if (label.sourceFile() == null || label.lineNumber() <= 1) {
            return null;
        }
        List<String> lines = fileCache.computeIfAbsent(label.sourceFile(), rel -> {
            try {
                Path p = projectRoot.resolve(rel).normalize();
                return Files.isRegularFile(p) ? Files.readAllLines(p, StandardCharsets.UTF_8) : List.of();
            } catch (IOException | InvalidPathException ignored) {
                return List.of();
            }
        });
        int idx = label.lineNumber() - 2;
        if (idx >= 0 && idx < lines.size()) {
            String prev = lines.get(idx).trim();
            if (prev.toLowerCase(Locale.ROOT).contains("@deprecated")) {
                return prev;
            }
        }
        return null;
    }

    private static void checkDeprecatedJumps(
            VJavaAst.ProjectAst project,
            String currentLabel,
            List<VJavaAst.Statement> statements,
            Map<String, String> deprecatedReasons,
            List<LintIssue> issues
    ) {
        if (statements == null || statements.isEmpty()) {
            return;
        }
        for (VJavaAst.Statement stmt : statements) {
            if (stmt instanceof VJavaAst.JumpStatement jump && !jump.isExpression()) {
                emitDeprecatedWarningIfMatched(
                        project, currentLabel, jump.targetLabel(), jump.sourceFile(),
                        jump.lineNumber(), deprecatedReasons, issues);
            } else if (stmt instanceof VJavaAst.CallStatement call) {
                emitDeprecatedWarningIfMatched(
                        project, currentLabel, call.targetLabel(), call.sourceFile(),
                        call.lineNumber(), deprecatedReasons, issues);
            } else {
                for (List<VJavaAst.Statement> nested : childStatementLists(stmt)) {
                    checkDeprecatedJumps(project, currentLabel, nested, deprecatedReasons, issues);
                }
            }
        }
    }

    private static void emitDeprecatedWarningIfMatched(
            VJavaAst.ProjectAst project,
            String currentLabel,
            String rawTarget,
            String sourceFile,
            int lineNumber,
            Map<String, String> deprecatedReasons,
            List<LintIssue> issues
    ) {
        if (rawTarget == null) {
            return;
        }
        String resolved = resolveNamespaceLabel(project, currentLabel, rawTarget.trim());
        if (resolved != null && deprecatedReasons.containsKey(resolved)) {
            issues.add(new LintIssue(
                    Severity.INFO,
                    "DEPRECATED_LABEL_TARGET",
                    "Target label '" + resolved + "' is marked @deprecated ("
                            + deprecatedReasons.get(resolved) + ").",
                    sourceFile,
                    lineNumber
            ));
        }
    }

    private static void checkDurationAndEasing(
            String tag,
            double durationSeconds,
            String easing,
            String sourceFile,
            int lineNumber,
            List<LintIssue> issues
    ) {
        if (durationSeconds < 0.0) {
            issues.add(new LintIssue(
                    Severity.WARNING,
                    "INVALID_MOVE_DURATION",
                    "Negative motion duration " + durationSeconds + " for sprite '" + tag + "'.",
                    sourceFile,
                    lineNumber
            ));
        }
        if (easing != null && !ObjectMotionController.SUPPORTED_EASINGS.contains(
                easing.trim().toLowerCase(Locale.ROOT))) {
            issues.add(new LintIssue(
                    Severity.WARNING,
                    "UNKNOWN_EASING",
                    "Unknown easing '" + easing + "' for sprite '" + tag + "'.",
                    sourceFile,
                    lineNumber
            ));
        }
    }

    private enum ExprType {
        NUMBER,
        BOOLEAN,
        STRING,
        NULL,
        UNKNOWN
    }

    private static void checkStrictTypes(VJavaAst.ProjectAst project, List<LintIssue> issues) {
        Map<String, ExprType> globalEnv = new HashMap<>();
        for (VJavaAst.SetVarStatement dv : project.defaultVariables()) {
            ExprType t = inferExpressionType(
                    dv.expression(), globalEnv, dv.varName(), dv.sourceFile(), dv.lineNumber(), issues);
            globalEnv.put(dv.varName(), t);
        }
        for (VJavaAst.LabelNode label : project.labels().values()) {
            Map<String, ExprType> localEnv = new HashMap<>(globalEnv);
            checkStatementTypes(label.statements(), localEnv, issues);
        }
    }

    private static void checkStatementTypes(
            List<VJavaAst.Statement> statements,
            Map<String, ExprType> env,
            List<LintIssue> issues
    ) {
        for (VJavaAst.Statement stmt : statements) {
            if (stmt instanceof VJavaAst.SetVarStatement sv) {
                ExprType inferred = inferExpressionType(
                        sv.expression(), env, sv.varName(), sv.sourceFile(), sv.lineNumber(), issues);
                env.put(sv.varName(), inferred);
            } else if (stmt instanceof VJavaAst.IfStatement ifs) {
                for (VJavaAst.IfBranch branch : ifs.branches()) {
                    checkStatementTypes(branch.body(), new HashMap<>(env), issues);
                }
            } else if (stmt instanceof VJavaAst.MatchStatement ms) {
                for (VJavaAst.MatchCase mc : ms.cases()) {
                    checkStatementTypes(mc.body(), new HashMap<>(env), issues);
                }
                if (ms.defaultBody() != null) {
                    checkStatementTypes(ms.defaultBody(), new HashMap<>(env), issues);
                }
            } else if (stmt instanceof VJavaAst.MenuStatement menu) {
                for (VJavaAst.MenuChoice choice : menu.choices()) {
                    checkStatementTypes(choice.body(), new HashMap<>(env), issues);
                }
            } else if (stmt instanceof VJavaAst.ParallelStatement ps) {
                checkStatementTypes(ps.statements(), env, issues);
            }
        }
    }

    private static ExprType inferExpressionType(
            String rawExpr,
            Map<String, ExprType> env,
            String targetVar,
            String sourceFile,
            int lineNumber,
            List<LintIssue> issues
    ) {
        if (rawExpr == null) {
            return ExprType.UNKNOWN;
        }
        String expr = unwrapOuterParens(rawExpr.trim());
        if (expr.isEmpty()) {
            return ExprType.UNKNOWN;
        }

        int orIdx = findBinaryOpOutsideQuotes(expr, "||");
        if (orIdx > 0) {
            inferExpressionType(expr.substring(0, orIdx), env, targetVar, sourceFile, lineNumber, issues);
            inferExpressionType(expr.substring(orIdx + 2), env, targetVar, sourceFile, lineNumber, issues);
            return ExprType.BOOLEAN;
        }
        int andIdx = findBinaryOpOutsideQuotes(expr, "&&");
        if (andIdx > 0) {
            inferExpressionType(expr.substring(0, andIdx), env, targetVar, sourceFile, lineNumber, issues);
            inferExpressionType(expr.substring(andIdx + 2), env, targetVar, sourceFile, lineNumber, issues);
            return ExprType.BOOLEAN;
        }

        for (String cmpOp : List.of("==", "!=", "<=", ">=", "<", ">")) {
            int cmpIdx = findBinaryOpOutsideQuotes(expr, cmpOp);
            if (cmpIdx > 0) {
                inferExpressionType(expr.substring(0, cmpIdx), env, targetVar, sourceFile, lineNumber, issues);
                inferExpressionType(
                        expr.substring(cmpIdx + cmpOp.length()), env, targetVar, sourceFile, lineNumber, issues);
                return ExprType.BOOLEAN;
            }
        }

        int addSubIdx = findAdditiveOpRightToLeft(expr);
        if (addSubIdx > 0) {
            char op = expr.charAt(addSubIdx);
            ExprType left = inferExpressionType(
                    expr.substring(0, addSubIdx), env, targetVar, sourceFile, lineNumber, issues);
            ExprType right = inferExpressionType(
                    expr.substring(addSubIdx + 1), env, targetVar, sourceFile, lineNumber, issues);
            if (op == '+') {
                if (left == ExprType.BOOLEAN && (right == ExprType.STRING
                        || right == ExprType.NUMBER || right == ExprType.BOOLEAN)) {
                    issues.add(new LintIssue(
                            Severity.ERROR,
                            "TYPE_MISMATCH",
                            "Type mismatch in assignment to '" + targetVar + "': cannot add "
                                    + left + " and " + right + ".",
                            sourceFile,
                            lineNumber
                    ));
                    return ExprType.UNKNOWN;
                }
                if (right == ExprType.BOOLEAN && (left == ExprType.STRING
                        || left == ExprType.NUMBER || left == ExprType.BOOLEAN)) {
                    issues.add(new LintIssue(
                            Severity.ERROR,
                            "TYPE_MISMATCH",
                            "Type mismatch in assignment to '" + targetVar + "': cannot add "
                                    + left + " and " + right + ".",
                            sourceFile,
                            lineNumber
                    ));
                    return ExprType.UNKNOWN;
                }
                if (left == ExprType.STRING || right == ExprType.STRING) {
                    return ExprType.STRING;
                }
                if (left == ExprType.NUMBER && right == ExprType.NUMBER) {
                    return ExprType.NUMBER;
                }
                return ExprType.UNKNOWN;
            } else {
                if (left == ExprType.BOOLEAN || left == ExprType.STRING
                        || right == ExprType.BOOLEAN || right == ExprType.STRING) {
                    issues.add(new LintIssue(
                            Severity.ERROR,
                            "TYPE_MISMATCH",
                            "Type mismatch in assignment to '" + targetVar + "': operator '" + op
                                    + "' is invalid for " + left + " and " + right + ".",
                            sourceFile,
                            lineNumber
                    ));
                    return ExprType.UNKNOWN;
                }
                return ExprType.NUMBER;
            }
        }

        int mulIdx = findMultiplicativeOpRightToLeft(expr);
        if (mulIdx > 0) {
            char op = expr.charAt(mulIdx);
            ExprType left = inferExpressionType(
                    expr.substring(0, mulIdx), env, targetVar, sourceFile, lineNumber, issues);
            ExprType right = inferExpressionType(
                    expr.substring(mulIdx + 1), env, targetVar, sourceFile, lineNumber, issues);
            if (left == ExprType.BOOLEAN || left == ExprType.STRING
                    || right == ExprType.BOOLEAN || right == ExprType.STRING) {
                issues.add(new LintIssue(
                        Severity.ERROR,
                        "TYPE_MISMATCH",
                        "Type mismatch in assignment to '" + targetVar + "': operator '" + op
                                + "' is invalid for " + left + " and " + right + ".",
                        sourceFile,
                        lineNumber
                ));
                return ExprType.UNKNOWN;
            }
            return ExprType.NUMBER;
        }

        if (expr.startsWith("!")) {
            inferExpressionType(expr.substring(1), env, targetVar, sourceFile, lineNumber, issues);
            return ExprType.BOOLEAN;
        }
        if ((expr.startsWith("-") || expr.startsWith("+")) && expr.length() > 1) {
            inferExpressionType(expr.substring(1), env, targetVar, sourceFile, lineNumber, issues);
            return ExprType.NUMBER;
        }
        if ((expr.startsWith("\"") && expr.endsWith("\"") && expr.length() >= 2)
                || (expr.startsWith("'") && expr.endsWith("'") && expr.length() >= 2)) {
            return ExprType.STRING;
        }
        if ("true".equals(expr) || "false".equals(expr)) {
            return ExprType.BOOLEAN;
        }
        if ("null".equals(expr) || "None".equals(expr)) {
            return ExprType.NULL;
        }
        try {
            Double.parseDouble(expr);
            return ExprType.NUMBER;
        } catch (NumberFormatException ignored) {
            // Identifier or compound call
        }
        if (IDENTIFIER_PATTERN.matcher(expr).matches()) {
            return env.getOrDefault(expr, ExprType.UNKNOWN);
        }
        return ExprType.UNKNOWN;
    }

    private static String unwrapOuterParens(String expr) {
        String s = expr;
        while (s.startsWith("(") && s.endsWith(")") && s.length() >= 2) {
            int depth = 0;
            boolean inSingle = false;
            boolean inDouble = false;
            boolean wrapsWhole = true;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && (inSingle || inDouble)) {
                    i++;
                    continue;
                }
                if (c == '\'' && !inDouble) {
                    inSingle = !inSingle;
                } else if (c == '"' && !inSingle) {
                    inDouble = !inDouble;
                } else if (!inSingle && !inDouble) {
                    if (c == '(') {
                        depth++;
                    } else if (c == ')') {
                        depth--;
                        if (depth == 0 && i < s.length() - 1) {
                            wrapsWhole = false;
                            break;
                        }
                    }
                }
            }
            if (!wrapsWhole || depth != 0) {
                break;
            }
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static int findBinaryOpOutsideQuotes(String expr, String op) {
        int paren = 0;
        int bracket = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        int limit = expr.length() - op.length();
        for (int i = 0; i <= limit; i++) {
            char c = expr.charAt(i);
            if (c == '\\' && (inSingle || inDouble)) {
                i++;
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble) {
                if (c == '(') {
                    paren++;
                } else if (c == ')') {
                    paren = Math.max(0, paren - 1);
                } else if (c == '[') {
                    bracket++;
                } else if (c == ']') {
                    bracket = Math.max(0, bracket - 1);
                } else if (paren == 0 && bracket == 0 && expr.startsWith(op, i)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findAdditiveOpRightToLeft(String expr) {
        int paren = 0;
        int bracket = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        int lastIdx = -1;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '\\' && (inSingle || inDouble)) {
                i++;
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble) {
                if (c == '(') {
                    paren++;
                } else if (c == ')') {
                    paren = Math.max(0, paren - 1);
                } else if (c == '[') {
                    bracket++;
                } else if (c == ']') {
                    bracket = Math.max(0, bracket - 1);
                } else if (paren == 0 && bracket == 0 && (c == '+' || c == '-') && i > 0) {
                    char prev = findPrevNonSpace(expr, i - 1);
                    if (prev != '+' && prev != '-' && prev != '*' && prev != '/'
                            && prev != '(' && prev != '=' && prev != '!' && prev != '<' && prev != '>') {
                        lastIdx = i;
                    }
                }
            }
        }
        return lastIdx;
    }

    private static int findMultiplicativeOpRightToLeft(String expr) {
        int paren = 0;
        int bracket = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        int lastIdx = -1;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '\\' && (inSingle || inDouble)) {
                i++;
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble) {
                if (c == '(') {
                    paren++;
                } else if (c == ')') {
                    paren = Math.max(0, paren - 1);
                } else if (c == '[') {
                    bracket++;
                } else if (c == ']') {
                    bracket = Math.max(0, bracket - 1);
                } else if (paren == 0 && bracket == 0 && (c == '*' || c == '/' || c == '%') && i > 0) {
                    lastIdx = i;
                }
            }
        }
        return lastIdx;
    }

    private static char findPrevNonSpace(String s, int idx) {
        for (int i = idx; i >= 0; i--) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c;
            }
        }
        return '(';
    }

    private static void verifyTransitionAssets(
            VJavaAst.ProjectAst project,
            Set<String> knownAssets,
            String transitionExpr,
            String sourceFile,
            int lineNumber,
            List<LintIssue> issues
    ) {
        if (transitionExpr == null || transitionExpr.isBlank()) {
            return;
        }
        try {
            Transition parsed = Transition.parse(transitionExpr);
            verifyTransitionNodeAssets(project, knownAssets, parsed, sourceFile, lineNumber, issues);
        } catch (IllegalArgumentException ignored) {
            // Invalid transition expressions are ignored cleanly here to preserve existing parse/runtime checks.
        }
    }

    private static void verifyTransitionNodeAssets(
            VJavaAst.ProjectAst project,
            Set<String> knownAssets,
            Transition transition,
            String sourceFile,
            int lineNumber,
            List<LintIssue> issues
    ) {
        if (transition == null) {
            return;
        }
        if (transition.getKind() == Transition.Kind.IMAGE_DISSOLVE
                && transition.getMaskImagePath() != null
                && !transition.getMaskImagePath().isBlank()) {
            verifyAssetExists(project, knownAssets, transition.getMaskImagePath(), sourceFile, lineNumber, issues);
        }
        for (Transition sub : transition.getSubTransitions()) {
            verifyTransitionNodeAssets(project, knownAssets, sub, sourceFile, lineNumber, issues);
        }
    }

    private static void verifySceneOrShowAsset(
            VJavaAst.ProjectAst project,
            Set<String> knownAssets,
            String tag,
            List<String> attributes,
            String sourceFile,
            int lineNumber,
            List<LintIssue> issues
    ) {
        if (tag == null || tag.isBlank()) {
            return;
        }
        String cleanTag = stripQuotes(tag);
        if (project.layeredImages().containsKey(cleanTag)) {
            return;
        }
        if (!attributes.isEmpty()) {
            String fullKey = cleanTag + " " + String.join(" ", attributes);
            if (project.images().containsKey(fullKey)) {
                return;
            }
        }
        if (project.images().containsKey(cleanTag)) {
            return;
        }
        if (isDirectPathTag(cleanTag)) {
            verifyAssetExists(project, knownAssets, cleanTag, sourceFile, lineNumber, issues);
            return;
        }

        String baseName = attributes.isEmpty()
                ? cleanTag
                : cleanTag + "_" + String.join("_", attributes);
        List<String> extensions = List.of(".png", ".jpg", ".jpeg", ".webp");
        List<String> folders = List.of(
                "assets/images/",
                "assets/images/bg/",
                "assets/images/char/",
                "assets/images/sprites/"
        );
        for (String ext : extensions) {
            for (String folder : folders) {
                if (assetExists(project, knownAssets, folder + baseName + ext)) {
                    return;
                }
            }
        }

        issues.add(new LintIssue(
                Severity.ERROR,
                "MISSING_ASSET",
                "Referenced visual asset for '" + cleanTag + "' could not be found under assets/.",
                sourceFile,
                lineNumber
        ));
    }

    private static void checkLiteralAssetCandidate(
            VJavaAst.ProjectAst project,
            Set<String> knownAssets,
            String rawValue,
            String sourceFile,
            int lineNumber,
            List<LintIssue> issues
    ) {
        String stripped = stripQuotes(rawValue);
        if (isLiteralAssetPath(stripped)) {
            verifyAssetExists(project, knownAssets, stripped, sourceFile, lineNumber, issues);
        }
    }

    private static void verifyAssetExists(
            VJavaAst.ProjectAst project,
            Set<String> knownAssets,
            String rawAssetPath,
            String sourceFile,
            int lineNumber,
            List<LintIssue> issues
    ) {
        if (rawAssetPath == null || rawAssetPath.isBlank()) {
            return;
        }
        String normalized = normalizeAssetPath(stripQuotes(rawAssetPath));
        if (normalized.contains("%s")) {
            return;
        }
        if (!assetExists(project, knownAssets, normalized)) {
            issues.add(new LintIssue(
                    Severity.ERROR,
                    "MISSING_ASSET",
                    "Missing referenced asset: '" + normalized + "'.",
                    sourceFile,
                    lineNumber
            ));
        }
    }

    private static boolean assetExists(VJavaAst.ProjectAst project, Set<String> knownAssets, String assetPath) {
        String normalized = normalizeAssetPath(assetPath);
        if (knownAssets.contains(normalized)) {
            return true;
        }
        Path root = project.projectRoot();
        if (root != null) {
            try {
                Path candidate = root.resolve(normalized).normalize();
                if (Files.isRegularFile(candidate)) {
                    return true;
                }
            } catch (InvalidPathException ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean isDirectPathTag(String tag) {
        String lower = tag.toLowerCase(Locale.ROOT);
        return tag.contains("/")
                || lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".webp")
                || lower.endsWith(".bmp")
                || lower.endsWith(".tga")
                || lower.endsWith(".obj")
                || lower.endsWith(".glb")
                || lower.endsWith(".gltf")
                || lower.endsWith(".mtl");
    }

    private static boolean isLiteralAssetPath(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.contains("+") || value.contains("(") || value.contains("?") || value.contains("[")) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return value.startsWith("assets/")
                || value.contains("/")
                || lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".webp")
                || lower.endsWith(".bmp")
                || lower.endsWith(".tga")
                || lower.endsWith(".ogg")
                || lower.endsWith(".oga")
                || lower.endsWith(".wav")
                || lower.endsWith(".wave")
                || lower.endsWith(".obj")
                || lower.endsWith(".glb")
                || lower.endsWith(".gltf")
                || lower.endsWith(".mtl");
    }

    private static String normalizeAssetPath(String raw) {
        String s = raw.trim().replace('\\', '/');
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }

    private static String stripQuotes(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static LintReport createReport(List<LintIssue> issues) {
        int errors = 0;
        int warnings = 0;
        for (LintIssue issue : issues) {
            if (issue.severity() == Severity.ERROR) {
                errors++;
            } else if (issue.severity() == Severity.WARNING) {
                warnings++;
            }
        }
        return new LintReport(issues, errors, warnings);
    }

    private static String resolveNamespaceLabel(
            VJavaAst.ProjectAst project,
            String currentLabel,
            String rawTarget
    ) {
        if (rawTarget == null || rawTarget.isBlank()) {
            return null;
        }
        String trimmed = rawTarget.trim();
        if (project.labels().containsKey(trimmed)) {
            return trimmed;
        }
        if (currentLabel != null) {
            int dotIdx = currentLabel.lastIndexOf('.');
            if (dotIdx > 0) {
                String ns = currentLabel.substring(0, dotIdx);
                String rel = trimmed.startsWith(".") ? ns + trimmed : ns + "." + trimmed;
                if (project.labels().containsKey(rel)) {
                    return rel;
                }
            }
        }
        return null;
    }

    private static void checkResolvedTarget(
            VJavaAst.ProjectAst project,
            String currentLabel,
            String rawTarget,
            String code,
            String labelKind,
            String sourceFile,
            int lineNumber,
            Set<String> outgoingEdges,
            List<LintIssue> issues
    ) {
        String resolved = resolveNamespaceLabel(project, currentLabel, rawTarget);
        if (resolved == null) {
            issues.add(new LintIssue(
                    Severity.ERROR,
                    code,
                    labelKind + " '" + (rawTarget != null ? rawTarget.trim() : "") + "' is not defined.",
                    sourceFile,
                    lineNumber
            ));
        } else {
            outgoingEdges.add(resolved);
        }
    }

    private static void checkTransformReference(
            VJavaAst.ProjectAst project,
            String positionExpr,
            String sourceFile,
            int lineNumber,
            List<LintIssue> issues
    ) {
        if (positionExpr == null || positionExpr.isBlank()) {
            return;
        }
        String pos = positionExpr.trim();
        if (!IDENTIFIER_PATTERN.matcher(pos).matches()) {
            return;
        }
        String lower = pos.toLowerCase(Locale.ROOT);
        if (BUILTIN_TRANSFORM_NAMES.contains(lower) || project.transforms().containsKey(pos)) {
            return;
        }
        String best = findClosestTransform(pos, project.transforms().keySet());
        String hint = best != null ? " — did you mean '" + best + "'?" : ".";
        issues.add(new LintIssue(
                Severity.WARNING,
                "UNKNOWN_TRANSFORM",
                "Unknown transform or position '" + pos + "'" + hint,
                sourceFile,
                lineNumber
        ));
    }

    private static String findClosestTransform(String target, Set<String> customTransforms) {
        List<String> candidates = new ArrayList<>(BUILTIN_TRANSFORM_NAMES);
        candidates.addAll(customTransforms);
        String best = null;
        int bestDist = 4;
        String lowerTarget = target.toLowerCase(Locale.ROOT);
        for (String cand : candidates) {
            int d = levenshteinDistance(lowerTarget, cand.toLowerCase(Locale.ROOT));
            if (d < bestDist) {
                bestDist = d;
                best = cand;
            }
        }
        return best;
    }

    private static int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    private static void checkUnusedSymbols(VJavaAst.ProjectAst project, List<LintIssue> issues) {
        Set<String> usedSpeakers = new HashSet<>();
        StringBuilder codeCorpus = new StringBuilder();
        for (VJavaAst.JavaBlockNode jbn : project.topLevelJavaBlocks()) {
            codeCorpus.append(jbn.code()).append('\n');
        }
        for (VJavaAst.LabelNode lbl : project.labels().values()) {
            collectUsageFromStatements(lbl.statements(), usedSpeakers, codeCorpus);
        }
        for (VJavaAst.ScreenDef screen : project.screens().values()) {
            for (VJavaAst.ScreenItem item : screen.items()) {
                collectScreenCorpus(item, codeCorpus);
            }
        }
        String corpus = codeCorpus.toString();

        for (VJavaAst.CharacterDef ch : project.characters().values()) {
            if (!usedSpeakers.contains(ch.id()) && !containsWholeWord(corpus, ch.id())) {
                issues.add(new LintIssue(
                        Severity.INFO,
                        "UNUSED_CHARACTER",
                        "Character '" + ch.id() + "' is defined but never referenced.",
                        ch.sourceFile(),
                        ch.lineNumber()
                ));
            }
        }
        Set<String> seenVars = new HashSet<>();
        for (VJavaAst.SetVarStatement dv : project.defaultVariables()) {
            if (!dv.isDefaultOnly() || !seenVars.add(dv.varName())) {
                continue;
            }
            if (!containsWholeWord(corpus, dv.varName())) {
                issues.add(new LintIssue(
                        Severity.INFO,
                        "UNUSED_VARIABLE",
                        "Default variable '" + dv.varName() + "' is initialized but never read.",
                        dv.sourceFile(),
                        dv.lineNumber()
                ));
            }
        }
    }

    private static void collectUsageFromStatements(
            List<VJavaAst.Statement> statements,
            Set<String> usedSpeakers,
            StringBuilder corpus
    ) {
        for (VJavaAst.Statement stmt : statements) {
            if (stmt instanceof VJavaAst.SayStatement say) {
                if (say.speakerId() != null) {
                    usedSpeakers.add(say.speakerId().trim());
                }
                if (say.text() != null) {
                    corpus.append(say.text()).append('\n');
                }
            } else if (stmt instanceof VJavaAst.ShowStatement show) {
                usedSpeakers.add(show.tag());
            } else if (stmt instanceof VJavaAst.JavaBlockStatement jbs) {
                corpus.append(jbs.block().code()).append('\n');
            } else if (stmt instanceof VJavaAst.SetVarStatement sv && !sv.isDefaultOnly()) {
                corpus.append(sv.expression()).append('\n');
            } else if (stmt instanceof VJavaAst.LocalVarStatement lv) {
                corpus.append(lv.expression()).append('\n');
            } else if (stmt instanceof VJavaAst.JumpStatement js) {
                corpus.append(js.targetLabel()).append('\n');
            } else if (stmt instanceof VJavaAst.IfStatement ifs) {
                for (VJavaAst.IfBranch br : ifs.branches()) {
                    if (br.conditionExpr() != null) {
                        corpus.append(br.conditionExpr()).append('\n');
                    }
                    collectUsageFromStatements(br.body(), usedSpeakers, corpus);
                }
            } else if (stmt instanceof VJavaAst.MatchStatement ms) {
                corpus.append(ms.subjectExpr()).append('\n');
                for (VJavaAst.MatchCase mc : ms.cases()) {
                    corpus.append(mc.patternExpr()).append('\n');
                    collectUsageFromStatements(mc.body(), usedSpeakers, corpus);
                }
                if (ms.defaultBody() != null) {
                    collectUsageFromStatements(ms.defaultBody(), usedSpeakers, corpus);
                }
            } else if (stmt instanceof VJavaAst.MenuStatement menu) {
                for (VJavaAst.MenuChoice ch : menu.choices()) {
                    if (ch.conditionExpr() != null) {
                        corpus.append(ch.conditionExpr()).append('\n');
                    }
                    corpus.append(ch.text()).append('\n');
                    collectUsageFromStatements(ch.body(), usedSpeakers, corpus);
                }
            } else if (stmt instanceof VJavaAst.ParallelStatement ps) {
                collectUsageFromStatements(ps.statements(), usedSpeakers, corpus);
            } else if (stmt instanceof VJavaAst.RepeatStatement rs) {
                corpus.append(rs.countExpr()).append('\n');
                collectUsageFromStatements(rs.body(), usedSpeakers, corpus);
            } else if (stmt instanceof VJavaAst.WhileStatement ws) {
                corpus.append(ws.conditionExpr()).append('\n');
                collectUsageFromStatements(ws.body(), usedSpeakers, corpus);
            } else if (stmt instanceof VJavaAst.RandomStatement rnd) {
                for (VJavaAst.RandomBranch rb : rnd.branches()) {
                    collectUsageFromStatements(rb.body(), usedSpeakers, corpus);
                }
            }
        }
    }

    private static void collectScreenCorpus(VJavaAst.ScreenItem item, StringBuilder corpus) {
        if (item.primaryArg() != null) {
            corpus.append(item.primaryArg()).append('\n');
        }
        for (String val : item.properties().values()) {
            corpus.append(val).append('\n');
        }
        for (VJavaAst.ScreenItem child : item.children()) {
            collectScreenCorpus(child, corpus);
        }
    }

    private static boolean containsWholeWord(String corpus, String word) {
        if (word == null || word.isEmpty()) {
            return false;
        }
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(corpus).find();
    }
}
