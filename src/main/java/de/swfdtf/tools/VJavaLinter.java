package de.swfdtf.tools;

import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.graphics.ObjectMotionController;
import de.swfdtf.graphics.Transition;
import de.swfdtf.parser.JavaBlockCompiler;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.parser.VJavaCompileException;
import de.swfdtf.parser.VJavaParseException;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
        WARNING
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
            return lint(project);
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

        Map<String, Set<String>> callGraph = new LinkedHashMap<>();
        for (VJavaAst.LabelNode label : project.labels().values()) {
            Set<String> outgoing = callGraph.computeIfAbsent(label.name(), ignored -> new LinkedHashSet<>());
            checkStatements(project, label.statements(), outgoing, knownAssets, issues);
        }

        checkUnreachableLabels(project, entryLabel, callGraph, issues);
        checkJavaBlocks(project, issues);

        return createReport(issues);
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

    private static void checkStatements(
            VJavaAst.ProjectAst project,
            List<VJavaAst.Statement> statements,
            Set<String> outgoingEdges,
            Set<String> knownAssets,
            List<LintIssue> issues
    ) {
        for (VJavaAst.Statement stmt : statements) {
            if (stmt instanceof VJavaAst.JumpStatement jump) {
                if (!jump.isExpression()) {
                    String target = jump.targetLabel() != null ? jump.targetLabel().trim() : "";
                    if (target.isEmpty() || !project.labels().containsKey(target)) {
                        issues.add(new LintIssue(
                                Severity.ERROR,
                                "MISSING_JUMP_TARGET",
                                "Jump target label '" + target + "' is not defined.",
                                jump.sourceFile(),
                                jump.lineNumber()
                        ));
                    } else {
                        outgoingEdges.add(target);
                    }
                }
            } else if (stmt instanceof VJavaAst.CallStatement call) {
                String target = call.targetLabel() != null ? call.targetLabel().trim() : "";
                if (IDENTIFIER_PATTERN.matcher(target).matches()) {
                    if (!project.labels().containsKey(target)) {
                        issues.add(new LintIssue(
                                Severity.ERROR,
                                "MISSING_CALL_TARGET",
                                "Call target label '" + target + "' is not defined.",
                                call.sourceFile(),
                                call.lineNumber()
                        ));
                    } else {
                        outgoingEdges.add(target);
                    }
                }
            } else if (stmt instanceof VJavaAst.MenuStatement menu) {
                if (menu.timeoutJumpLabel() != null && !menu.timeoutJumpLabel().isBlank()) {
                    String timeoutTarget = menu.timeoutJumpLabel().trim();
                    if (!project.labels().containsKey(timeoutTarget)) {
                        issues.add(new LintIssue(
                                Severity.ERROR,
                                "MISSING_TIMEOUT_TARGET",
                                "Menu timeout target label '" + timeoutTarget + "' is not defined.",
                                menu.sourceFile(),
                                menu.lineNumber()
                        ));
                    } else {
                        outgoingEdges.add(timeoutTarget);
                    }
                }
                for (VJavaAst.MenuChoice choice : menu.choices()) {
                    if (choice.inlineJumpTarget() != null && !choice.inlineJumpTarget().isBlank()) {
                        String choiceTarget = choice.inlineJumpTarget().trim();
                        if (!project.labels().containsKey(choiceTarget)) {
                            issues.add(new LintIssue(
                                    Severity.ERROR,
                                    "MISSING_MENU_TARGET",
                                    "Menu choice target label '" + choiceTarget + "' is not defined.",
                                    choice.sourceFile(),
                                    choice.lineNumber()
                            ));
                        } else {
                            outgoingEdges.add(choiceTarget);
                        }
                    }
                    checkStatements(project, choice.body(), outgoingEdges, knownAssets, issues);
                }
            } else if (stmt instanceof VJavaAst.IfStatement ifStmt) {
                for (VJavaAst.IfBranch branch : ifStmt.branches()) {
                    checkStatements(project, branch.body(), outgoingEdges, knownAssets, issues);
                }
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
            } else if (stmt instanceof VJavaAst.QueueStatement queue) {
                for (String path : queue.assetPaths()) {
                    verifyAssetExists(project, knownAssets, path, queue.sourceFile(), queue.lineNumber(), issues);
                }
            } else if (stmt instanceof VJavaAst.VoiceStatement voice) {
                verifyAssetExists(
                        project, knownAssets, voice.assetPath(), voice.sourceFile(), voice.lineNumber(), issues);
            } else if (stmt instanceof VJavaAst.OnCollideStatement collide) {
                String target = collide.targetLabel() != null ? collide.targetLabel().trim() : "";
                if (target.isEmpty() || !project.labels().containsKey(target)) {
                    issues.add(new LintIssue(
                            Severity.ERROR,
                            "MISSING_COLLIDE_TARGET",
                            "Collision target label '" + target + "' is not defined.",
                            collide.sourceFile(),
                            collide.lineNumber()
                    ));
                } else {
                    outgoingEdges.add(target);
                }
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
            }
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
                || lower.endsWith(".tga");
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
                || lower.endsWith(".wave");
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
            } else {
                warnings++;
            }
        }
        return new LintReport(issues, errors, warnings);
    }
}
