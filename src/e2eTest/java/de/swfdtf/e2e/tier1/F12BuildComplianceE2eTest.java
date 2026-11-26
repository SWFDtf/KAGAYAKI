package de.swfdtf.e2e.tier1;

import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.e2e.support.E2eWorkspaceSupport;
import de.swfdtf.tools.ProjectTemplateGenerator;
import de.swfdtf.tools.StandaloneBuilder;
import de.swfdtf.tools.VJavaLinter;
import de.swfdtf.tools.VpakManifestInspector;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 1 happy-path E2E tests for Feature Area F12: build quality, coding style,
 * Javadoc compliance, architecture invariants, and release readiness.
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER1)
public final class F12BuildComplianceE2eTest {

    /** Creates a new {@code F12BuildComplianceE2eTest} instance. */
    public F12BuildComplianceE2eTest() {
    }

    private static final List<String> MAIN_PACKAGES = List.of(
            "src/main/java/de/swfdtf",
            "src/main/java/de/swfdtf/core",
            "src/main/java/de/swfdtf/math",
            "src/main/java/de/swfdtf/graphics",
            "src/main/java/de/swfdtf/parser",
            "src/main/java/de/swfdtf/state",
            "src/main/java/de/swfdtf/audio",
            "src/main/java/de/swfdtf/ui",
            "src/main/java/de/swfdtf/tools",
            "src/main/java/de/swfdtf/git"
    );

    private static final Pattern ACTUAL_AWT_IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?(?:java\\.awt|javax\\.imageio)\\.");

    private static final Pattern FORBIDDEN_STATE_IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?de\\.swfdtf\\.(?:parser|ui|tools)\\.");

    private static final Pattern I_PREFIX_INTERFACE =
            Pattern.compile("(?m)^\\s*(?:public\\s+|protected\\s+|private\\s+)?(?:sealed\\s+|non-sealed\\s+)?"
                    + "interface\\s+(I[A-Z][A-Za-z0-9_]*)\\b");

    private static final Pattern TYPE_DECL_PATTERN =
            Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Za-z_][A-Za-z0-9_]*)");

    private static final Pattern INTERNAL_IMPORT_PATTERN =
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?(de\\.swfdtf(?:\\.[a-z][a-z0-9_]*)*)\\.(?:[A-Z*])",
                    Pattern.MULTILINE);

    private static final Pattern INTERNAL_FQCN_PATTERN =
            Pattern.compile("\\b(de\\.swfdtf(?:\\.[a-z][a-z0-9_]*)*)\\.[A-Z][A-Za-z0-9_]*");

    private static final Pattern BACKTICK_TOKEN_PATTERN = Pattern.compile("`([^`]+)`");

    private static final Pattern CODE_TYPE_DECL_PATTERN = Pattern.compile(
            "^\\s*(?:public\\s+)?(?:(?:static|final|abstract|sealed|non-sealed)\\s+)*"
                    + "(?:class|interface|enum|record)\\s+([A-Za-z_][A-Za-z0-9_]*)"
    );

    private static final Pattern PKG_TOKEN_PATTERN =
            Pattern.compile("`(de\\.swfdtf(?:\\.[a-z][a-z0-9_]*)*)`");

    /**
     * Verifies baseline {@code AGENT_CONTEXT.md} coverage for every main Java package.
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void everyMainJavaPackageHasAgentContextDocumentation() {
        Path repoRoot = E2eWorkspaceSupport.repoRoot();
        for (String pkg : MAIN_PACKAGES) {
            Path contextFile = repoRoot.resolve(pkg).resolve("AGENT_CONTEXT.md");
            assertTrue(Files.isRegularFile(contextFile), "Missing AGENT_CONTEXT.md in " + pkg);
        }
    }

    /**
     * Verifies goal {@code #0944}: every main Java package's {@code AGENT_CONTEXT.md} structurally
     * synchronizes {@code ## Enthaltene Typen}, {@code ## Öffentliche API-Signaturen}, and
     * {@code ## Abhängigkeiten} against top-level and nested Java declarations and internal imports.
     *
     * @throws IOException if reading source or context files fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void everyMainJavaPackageHasSynchronizedAgentContextSymbols() throws IOException {
        Path repoRoot = E2eWorkspaceSupport.repoRoot();
        Path mainRoot = repoRoot.resolve("src/main/java/de/swfdtf");
        Map<String, PackageInventory> inventories = scanPackageInventories(mainRoot);

        for (String expectedDir : MAIN_PACKAGES) {
            Path rel = repoRoot.resolve("src/main/java/de/swfdtf").relativize(repoRoot.resolve(expectedDir));
            String expectedPkg = rel.toString().isEmpty()
                    ? "de.swfdtf"
                    : "de.swfdtf." + rel.toString().replace('\\', '.').replace('/', '.');
            assertTrue(
                    inventories.containsKey(expectedPkg),
                    "Expected main package missing from scan: " + expectedPkg
            );
        }

        List<String> syncErrors = new ArrayList<>();
        for (Map.Entry<String, PackageInventory> entry : inventories.entrySet()) {
            String pkg = entry.getKey();
            PackageInventory inv = entry.getValue();
            Path contextFile = inv.directory().resolve("AGENT_CONTEXT.md");
            assertTrue(Files.isRegularFile(contextFile), "Missing AGENT_CONTEXT.md in " + pkg);

            String markdown = Files.readString(contextFile, StandardCharsets.UTF_8);
            auditTypesTable(pkg, inv, markdown, syncErrors);
            auditPublicApiSignatures(pkg, inv, markdown, syncErrors);
            auditDependenciesSection(pkg, inv, inventories, markdown, syncErrors);
        }
        assertTrue(
                syncErrors.isEmpty(),
                "AGENT_CONTEXT.md synchronization mismatches detected:\n" + String.join("\n", syncErrors)
        );
    }

    private record PackageInventory(
            Path directory,
            Set<String> topLevelTypes,
            Map<String, Set<String>> declaredTypesByFile,
            Set<String> allDeclaredTypes,
            Set<String> actualInternalDeps
    ) {
    }

    private static Map<String, PackageInventory> scanPackageInventories(Path mainRoot) throws IOException {
        Map<String, List<Path>> filesByPackage = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(mainRoot)) {
            List<Path> javaFiles = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
            for (Path file : javaFiles) {
                Path rel = mainRoot.relativize(file.getParent());
                String pkg = rel.toString().isEmpty()
                        ? "de.swfdtf"
                        : "de.swfdtf." + rel.toString().replace('\\', '.').replace('/', '.');
                filesByPackage.computeIfAbsent(pkg, ignored -> new ArrayList<>()).add(file);
            }
        }

        Map<String, PackageInventory> inventories = new LinkedHashMap<>();
        for (Map.Entry<String, List<Path>> entry : filesByPackage.entrySet()) {
            String pkg = entry.getKey();
            List<Path> files = entry.getValue();
            Path dir = files.get(0).getParent();

            Set<String> topLevelTypes = new TreeSet<>();
            Map<String, Set<String>> declaredByFile = new LinkedHashMap<>();
            Set<String> allDeclared = new TreeSet<>();
            Set<String> deps = new TreeSet<>();

            for (Path file : files) {
                String filename = file.getFileName().toString();
                String stem = filename.substring(0, filename.length() - ".java".length());
                topLevelTypes.add(stem);

                String stripped = stripCommentsAndStrings(Files.readString(file, StandardCharsets.UTF_8));
                Set<String> fileDeclared = new TreeSet<>();
                Matcher typeMatcher = TYPE_DECL_PATTERN.matcher(stripped);
                while (typeMatcher.find()) {
                    String typeName = typeMatcher.group(1);
                    fileDeclared.add(typeName);
                    allDeclared.add(typeName);
                }
                declaredByFile.put(stem, Set.copyOf(fileDeclared));
                collectInternalDependencies(stripped, pkg, deps);
            }

            inventories.put(pkg, new PackageInventory(
                    dir,
                    Set.copyOf(topLevelTypes),
                    Map.copyOf(declaredByFile),
                    Set.copyOf(allDeclared),
                    Set.copyOf(deps)
            ));
        }
        return Map.copyOf(inventories);
    }

    private static void collectInternalDependencies(String stripped, String pkg, Set<String> deps) {
        Matcher impMatcher = INTERNAL_IMPORT_PATTERN.matcher(stripped);
        while (impMatcher.find()) {
            String target = impMatcher.group(1);
            if (!target.equals(pkg)) {
                deps.add(target);
            }
        }
        for (String line : stripped.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) {
                continue;
            }
            Matcher fqcnMatcher = INTERNAL_FQCN_PATTERN.matcher(line);
            while (fqcnMatcher.find()) {
                String target = fqcnMatcher.group(1);
                if (!target.equals(pkg)) {
                    deps.add(target);
                }
            }
        }
    }

    private static void auditTypesTable(
            String pkg,
            PackageInventory inv,
            String markdown,
            List<String> errors
    ) {
        List<String> section = extractSectionLines(markdown, "## Enthaltene Typen", false);
        if (section.isEmpty()) {
            errors.add(pkg + ": missing '## Enthaltene Typen' section");
            return;
        }

        Set<String> documentedRootTypes = new LinkedHashSet<>();
        for (String line : section) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("|")) {
                continue;
            }
            String[] cols = trimmed.split("\\|", -1);
            if (cols.length < 3) {
                continue;
            }
            String col1 = cols[1].trim();
            if (col1.isEmpty() || "Typ".equals(col1) || col1.startsWith(":---") || col1.startsWith("---")) {
                continue;
            }
            auditTypesTableColumn(pkg, inv, col1, documentedRootTypes, errors);
        }

        for (String actualTopLevel : inv.topLevelTypes()) {
            if (!documentedRootTypes.contains(actualTopLevel)) {
                errors.add(pkg + " table: missing documentation row for " + actualTopLevel + ".java");
            }
        }
    }

    private static void auditTypesTableColumn(
            String pkg,
            PackageInventory inv,
            String col1,
            Set<String> documentedRootTypes,
            List<String> errors
    ) {
        Matcher m = BACKTICK_TOKEN_PATTERN.matcher(col1);
        String currentOuter = null;
        while (m.find()) {
            String rawToken = stripGenerics(m.group(1).trim());
            if (rawToken.isEmpty()) {
                continue;
            }
            int dotIdx = rawToken.indexOf('.');
            if (dotIdx > 0) {
                String outer = rawToken.substring(0, dotIdx);
                String inner = rawToken.substring(dotIdx + 1);
                currentOuter = outer;
                documentedRootTypes.add(outer);
                if (!inv.topLevelTypes().contains(outer)) {
                    errors.add(pkg + " table: references missing top-level type '" + outer
                            + "' via '" + rawToken + "'");
                } else if (!inv.declaredTypesByFile().getOrDefault(outer, Set.of()).contains(inner)) {
                    errors.add(pkg + " table: references missing nested type '" + inner
                            + "' in " + outer + ".java");
                }
            } else {
                if (currentOuter != null
                        && inv.declaredTypesByFile().getOrDefault(currentOuter, Set.of()).contains(rawToken)) {
                    continue;
                }
                currentOuter = rawToken;
                documentedRootTypes.add(rawToken);
                if (!inv.topLevelTypes().contains(rawToken)) {
                    errors.add(pkg + " table: documents non-existent type '" + rawToken + "'");
                }
            }
        }
    }

    private static void auditPublicApiSignatures(
            String pkg,
            PackageInventory inv,
            String markdown,
            List<String> errors
    ) {
        List<String> section = extractSectionLines(markdown, "## Öffentliche API-Signaturen", false);
        if (section.isEmpty()) {
            errors.add(pkg + ": missing '## Öffentliche API-Signaturen' section");
            return;
        }

        boolean inFence = false;
        for (String line : section) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (!inFence && trimmed.startsWith("### ")) {
                Matcher m = BACKTICK_TOKEN_PATTERN.matcher(trimmed);
                while (m.find()) {
                    String token = stripGenerics(m.group(1).trim());
                    int dotIdx = token.indexOf('.');
                    if (dotIdx > 0) {
                        String outer = token.substring(0, dotIdx);
                        String inner = token.substring(dotIdx + 1);
                        if (!inv.declaredTypesByFile().getOrDefault(outer, Set.of()).contains(inner)) {
                            errors.add(pkg + " API heading: references missing nested type '" + token + "'");
                        }
                    } else if (!inv.topLevelTypes().contains(token)) {
                        errors.add(pkg + " API heading: references missing top-level type '" + token + "'");
                    }
                }
            } else if (inFence) {
                Matcher declMatcher = CODE_TYPE_DECL_PATTERN.matcher(line);
                if (declMatcher.find()) {
                    String typeName = declMatcher.group(1);
                    if (!inv.allDeclaredTypes().contains(typeName)) {
                        errors.add(pkg + " API code block: declares non-existent type '" + typeName + "'");
                    }
                }
            }
        }
    }

    private static void auditDependenciesSection(
            String pkg,
            PackageInventory inv,
            Map<String, PackageInventory> allInventories,
            String markdown,
            List<String> errors
    ) {
        List<String> section = extractSectionLines(markdown, "## Abhängigkeiten", true);
        if (section.isEmpty()) {
            errors.add(pkg + ": missing '## Abhängigkeiten' section");
            return;
        }

        Set<String> documentedDeps = new TreeSet<>();
        for (String line : section) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("-")) {
                continue;
            }
            Matcher pkgMatcher = PKG_TOKEN_PATTERN.matcher(trimmed);
            if (!pkgMatcher.find()) {
                continue;
            }
            String depPkg = pkgMatcher.group(1);
            if (!depPkg.equals(pkg)) {
                documentedDeps.add(depPkg);
            }

            PackageInventory depInv = allInventories.get(depPkg);
            if (depInv == null) {
                errors.add(pkg + " dependencies: documents unknown internal package '" + depPkg + "'");
                continue;
            }

            Matcher typeMatcher = BACKTICK_TOKEN_PATTERN.matcher(trimmed);
            while (typeMatcher.find()) {
                String token = stripGenerics(typeMatcher.group(1).trim());
                if (token.startsWith("de.swfdtf") || !token.matches("[A-Z][A-Za-z0-9_]*")) {
                    continue;
                }
                if (!depInv.allDeclaredTypes().contains(token) && !inv.allDeclaredTypes().contains(token)) {
                    errors.add(pkg + " dependencies: cited type '" + token + "' not found in "
                            + depPkg + " or " + pkg);
                }
            }
        }

        if (!inv.actualInternalDeps().equals(documentedDeps)) {
            errors.add(pkg + " dependencies mismatch: actual=" + inv.actualInternalDeps()
                    + " vs documented=" + documentedDeps);
        }
    }

    private static List<String> extractSectionLines(String markdown, String heading, boolean prefixMatch) {
        List<String> lines = List.of(markdown.split("\r?\n", -1));
        List<String> result = new ArrayList<>();
        boolean inSection = false;
        for (String line : lines) {
            if (line.startsWith("## ")) {
                if (inSection) {
                    break;
                }
                String trimmed = line.trim();
                if (prefixMatch ? trimmed.startsWith(heading) : trimmed.equals(heading)) {
                    inSection = true;
                }
                continue;
            }
            if (inSection) {
                result.add(line);
            }
        }
        return result;
    }

    private static String stripGenerics(String token) {
        int angleIdx = token.indexOf('<');
        return angleIdx >= 0 ? token.substring(0, angleIdx).trim() : token;
    }

    private static String stripCommentsAndStrings(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int n = source.length();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            char next = (i + 1 < n) ? source.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                i += 2;
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                i = skipBlockComment(source, out, i + 2);
            } else if (c == '"' && i + 2 < n && next == '"' && source.charAt(i + 2) == '"') {
                i = skipTextBlock(source, i + 3);
            } else if (c == '"' || c == '\'') {
                i = skipQuotedLiteral(source, out, i + 1, c);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int skipBlockComment(String source, StringBuilder out, int start) {
        int n = source.length();
        int i = start;
        while (i < n) {
            char ch = source.charAt(i);
            if (ch == '\n') {
                out.append('\n');
            } else if (ch == '*' && i + 1 < n && source.charAt(i + 1) == '/') {
                return i + 2;
            }
            i++;
        }
        return n;
    }

    private static int skipTextBlock(String source, int start) {
        int n = source.length();
        int i = start;
        while (i < n) {
            char ch = source.charAt(i);
            if (ch == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (ch == '"' && i + 2 < n && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                return i + 3;
            }
            i++;
        }
        return n;
    }

    private static int skipQuotedLiteral(String source, StringBuilder out, int start, char quote) {
        int n = source.length();
        int i = start;
        while (i < n) {
            char ch = source.charAt(i);
            if (ch == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (ch == '\n') {
                out.append('\n');
                return i + 1;
            }
            i++;
            if (ch == quote) {
                return i;
            }
        }
        return n;
    }

    /**
     * Verifies the macOS {@code -XstartOnFirstThread} invariant: zero actual {@code java.awt.*}
     * or {@code javax.imageio.*} imports in {@code src/main/java}, {@code demo/src/main/java},
     * and {@code src/e2eTest/java}.
     *
     * @throws IOException if scanning Java source trees fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void productionAndE2eSourcesContainZeroAwtOrImageIoImports() throws IOException {
        Path repoRoot = E2eWorkspaceSupport.repoRoot();
        List<Path> sourceRoots = List.of(
                repoRoot.resolve("src/main/java"),
                repoRoot.resolve("demo/src/main/java"),
                repoRoot.resolve("src/e2eTest/java")
        );
        List<String> violations = new ArrayList<>();
        for (Path root : sourceRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                List<Path> javaFiles = walk.filter(p -> p.toString().endsWith(".java")).toList();
                for (Path file : javaFiles) {
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    if (ACTUAL_AWT_IMPORT.matcher(source).find()) {
                        violations.add(repoRoot.relativize(file).toString());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "Forbidden java.awt/javax.imageio imports found in: " + violations);
    }

    /**
     * Verifies {@code Main} and {@code StandaloneMain} CLI smoke-test invocations exit with code 0
     * on {@code demo/project} in headless mode.
     *
     * @throws Exception if subprocess execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void mainAndStandaloneSmokeTestExitZeroOnDemoProject() throws Exception {
        Path repoRoot = E2eWorkspaceSupport.repoRoot();
        Path demoProject = repoRoot.resolve("demo").resolve("project");
        assertTrue(Files.isDirectory(demoProject), "Expected demo/project directory");

        E2eCliRunner.ProcessResult mainRes = E2eCliRunner.runJavaMainWithMacFirstThread(
                "de.swfdtf.Main",
                List.of("--studio", "--project", demoProject.toString(), "--headless", "--smoke-test"),
                repoRoot,
                Duration.ofSeconds(30)
        );
        assertTrue(mainRes.isSuccess(), "Main headless smoke test failed: " + mainRes.stderr());

        E2eCliRunner.ProcessResult standaloneRes = E2eCliRunner.runJavaMainWithMacFirstThread(
                "de.swfdtf.StandaloneMain",
                List.of("--project", demoProject.toString(), "--headless", "--smoke-test"),
                repoRoot,
                Duration.ofSeconds(30)
        );
        assertTrue(standaloneRes.isSuccess(), "StandaloneMain headless smoke test failed: " + standaloneRes.stderr());
    }

    /**
     * Verifies backwards compatibility: {@link ProjectTemplateGenerator#generate(Path)} and
     * {@link ProjectTemplateGenerator#generate(Path, String, String)} default to
     * {@link ProjectTemplateGenerator.TemplateType#STARTER} rather than {@code FULL_DEMO}.
     *
     * @param tempDir temporary test workspace
     * @throws IOException if template generation or reading fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void templateGeneratorBackwardsCompatibilityDefaultsToStarter(@TempDir Path tempDir) throws IOException {
        Path defaultOneArg = tempDir.resolve("starter_1arg");
        Path defaultThreeArg = tempDir.resolve("starter_3arg");
        ProjectTemplateGenerator.generate(defaultOneArg);
        ProjectTemplateGenerator.generate(defaultThreeArg, "Compat Title", "2D");

        String script1 = Files.readString(defaultOneArg.resolve("main.vjava"), StandardCharsets.UTF_8);
        String script3 = Files.readString(defaultThreeArg.resolve("main.vjava"), StandardCharsets.UTF_8);

        assertTrue(script1.contains("umgebung_pruefen"), "1-arg generate must produce STARTER template");
        assertFalse(script1.contains("wasd_arena"), "1-arg generate must not default to FULL_DEMO");
        assertTrue(script3.contains("umgebung_pruefen"), "3-arg generate must produce STARTER template");
        assertFalse(script3.contains("wasd_arena"), "3-arg generate must not default to FULL_DEMO");
    }

    /**
     * Verifies goal {@code #0942}: zero newly introduced {@code I*}-prefixed interfaces exist in
     * {@code src/main/java} or {@code demo/src/main/java} (with {@code IRenderPipeline} as the
     * sole allowlisted legacy exception).
     *
     * @throws IOException if scanning Java source trees fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void interfaceNamingAuditAllowsOnlyLegacyIRenderPipeline() throws IOException {
        Path repoRoot = E2eWorkspaceSupport.repoRoot();
        List<String> disallowed = new ArrayList<>();
        for (Path root : List.of(repoRoot.resolve("src/main/java"), repoRoot.resolve("demo/src/main/java"))) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    Matcher matcher = I_PREFIX_INTERFACE.matcher(content);
                    while (matcher.find()) {
                        String ifaceName = matcher.group(1);
                        if (!"IRenderPipeline".equals(ifaceName)) {
                            disallowed.add(repoRoot.relativize(file) + " -> " + ifaceName);
                        }
                    }
                }
            }
        }
        assertTrue(disallowed.isEmpty(), "Disallowed I*-prefixed interfaces found: " + disallowed);
    }

    /**
     * Verifies goal {@code #0941} and {@code #0943}: {@code ./gradlew check javadoc} executes
     * Checkstyle/Spotless and {@code -Xdoclint:all} Javadoc cleanly with zero errors/warnings.
     *
     * @throws Exception if Gradle invocation fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void gradleCheckAndJavadocPassWithDoclintAll() throws Exception {
        Path repoRoot = E2eWorkspaceSupport.repoRoot();
        String buildGradle = Files.readString(repoRoot.resolve("build.gradle"), StandardCharsets.UTF_8);
        assertTrue(
                buildGradle.contains("checkstyle") || buildGradle.contains("spotless"),
                "Expected Checkstyle or Spotless plugin configured in build.gradle"
        );
        assertTrue(buildGradle.contains("Xdoclint:all"), "Expected -Xdoclint:all configured in build.gradle");

        E2eCliRunner.ProcessResult result = E2eCliRunner.runGradle(
                repoRoot,
                List.of("check", "javadoc"),
                Duration.ofMinutes(3)
        );
        assertTrue(result.isSuccess(), "Gradle check/javadoc failed:\n" + result.stdout() + "\n" + result.stderr());
    }

    /**
     * Verifies acyclic package architecture invariant and goals {@code #1000}/{@code #1020}:
     * {@code de.swfdtf.state} has zero imports from {@code parser}/{@code ui}/{@code tools},
     * and the unified authoring-to-{@code .vpak} pipeline completes cleanly.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if scanning or pipeline execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void statePackageAcyclicAndUnifiedAuthoringToVpakPipelineReady(@TempDir Path tempDir) throws Exception {
        Path repoRoot = E2eWorkspaceSupport.repoRoot();
        Path stateDir = repoRoot.resolve("src/main/java/de/swfdtf/state");
        List<String> cycleViolations = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(stateDir)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(file, StandardCharsets.UTF_8);
                if (FORBIDDEN_STATE_IMPORT.matcher(src).find()) {
                    cycleViolations.add(repoRoot.relativize(file).toString());
                }
            }
        }
        assertTrue(cycleViolations.isEmpty(), "Forbidden imports in de.swfdtf.state: " + cycleViolations);

        Path proj = tempDir.resolve("authoring_pipeline");
        ProjectTemplateGenerator.generate(
                proj,
                "Release Pipeline",
                "2D",
                ProjectTemplateGenerator.TemplateType.STARTER
        );
        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(proj);
        assertTrue(lint.isClean());

        StandaloneBuilder.BuildResult export = E2eCliRunner.exportStandalone(proj, tempDir.resolve("dist"), null);
        VpakManifestInspector.VpakInspectionReport report = new VpakManifestInspector().inspect(export.vpakFile());
        assertTrue(report.integrityVerified());
        assertEquals(export.packagedEntries(), report.entryCount());
    }
}
