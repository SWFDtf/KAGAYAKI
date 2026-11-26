package de.swfdtf.tools;

import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineMode;
import de.swfdtf.graphics.RenderMode;
import de.swfdtf.graphics.RenderPipeline3DStub;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.ui.GameRuntimeCoordinator;
import de.swfdtf.ui.MainMenuScreen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndToEndEngineTest {

    @Test
    void fullDualWindowStudioStarterProjectLifecycleStandaloneExportAndDocsCheck(@TempDir Path tempDir)
            throws Exception {
        Path projectDir = tempDir.resolve("my_vn_project");
        Path distDir = tempDir.resolve("dist_out");

        // 1. Generate starter template & verify clean lint
        ProjectTemplateGenerator.generate(projectDir, "KAGAYAKI Demo", "2D");
        VJavaLinter.LintReport lint = VJavaLinter.lint(projectDir);
        assertTrue(lint.isClean(), "Starter template lint issues: " + lint.issues());

        // 2. Boot EngineApplication in STUDIO_DUAL_WINDOW headless mode
        // (automatically loads GameRuntimeCoordinator & StudioWindowController)
        EngineConfig studioConfig = EngineConfig.builder()
                .engineMode(EngineMode.STUDIO_DUAL_WINDOW)
                .projectDir(projectDir)
                .headless(true)
                .smokeTest(true)
                .build();

        try (EngineApplication app = new EngineApplication(studioConfig)) {
            app.initialize();

            StudioWindowController studio = app.getApi().getService(StudioWindowController.class);
            assertNotNull(studio, "StudioWindowController should be registered in STUDIO_DUAL_WINDOW mode");
            assertNotNull(studio.getLastScannedAst());
            assertTrue(studio.getLastLintReport().isClean());

            GameRuntimeCoordinator game = assertInstanceOf(GameRuntimeCoordinator.class, app.getGameHost());
            ScriptSession session = game.getSession();
            assertNotNull(session);

            // Main menu starts visible in GameRuntimeCoordinator
            assertTrue(game.getMainMenuScreen().isVisible());

            // 3. Click "Start" from Main Menu -> enters Dialogue 1
            game.getScreenManager().hideScreen(MainMenuScreen.SCREEN_NAME);
            session.start();
            app.stepOnce(1.0 / 60.0);

            assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, session.getRuntime().getStatus());
            assertTrue(game.getAdvScreen().isVisible());
            assertEquals("Willkommen in der ersten Beispielszene, Kaito.{w=0.4} Bereit für den Übergang in Dialog 2?",
                    session.getRuntime().getActiveDialogue().interpolatedText());
            assertEquals(35, session.getStateStore().getInt("mut_punkte", 0));

            // 4. Advance past Kaito's line -> Menu -> Choose Branch 2 -> Dialogue 2 -> QuickSave & Rollback
            session.advance();
            assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, session.getRuntime().getStatus());
            assertEquals("Der Sternenkompass zeigt direkt hinüber zur Bibliothek.",
                    session.getRuntime().getActiveDialogue().interpolatedText());
            session.advance();
            assertEquals(ScriptRuntime.Status.WAITING_MENU, session.getRuntime().getStatus());
            session.choose(1); // umgebung_pruefen
            assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, session.getRuntime().getStatus());
            session.quickSave();

            session.advance(); // jump dialog_zwei
            assertEquals("dialog_zwei", session.getRuntime().getCurrentLabel());

            // Rollback back from Dialogue 2 to umgebung_pruefen
            assertTrue(session.rollback());
            assertEquals("umgebung_pruefen", session.getRuntime().getCurrentLabel());

            // Roll-forward back to Dialogue 2
            assertTrue(session.rollForward());
            assertEquals("dialog_zwei", session.getRuntime().getCurrentLabel());

            // Quick-Load back to umgebung_pruefen
            session.quickLoad();
            assertEquals("umgebung_pruefen", session.getRuntime().getCurrentLabel());

            // 5. Studio mode switching to 3D Stub works without crash
            RenderMode switched = studio.switchRenderMode(RenderMode.MODE_3D);
            assertEquals(RenderMode.MODE_3D, switched);
            assertInstanceOf(RenderPipeline3DStub.class, app.getActivePipeline());
            app.stepOnce(1.0 / 60.0);
            assertTrue(((RenderPipeline3DStub) app.getActivePipeline()).isWarningLogged());
            studio.switchRenderMode(RenderMode.MODE_2D);

            // 6. Export standalone game bundle via StudioWindowController
            StandaloneBuilder.BuildResult export = studio.exportStandalone(distDir, null);
            assertTrue(Files.isRegularFile(export.vpakFile()));
            assertTrue(Files.isRegularFile(export.launcherScript()));
            assertTrue(Files.isRegularFile(export.packagingDescriptor()));

            try (VpakArchiver.VpakArchive archive = VpakArchiver.open(export.vpakFile())) {
                assertTrue(archive.contains("project.vjava.json"));
                assertTrue(archive.contains("main.vjava"));
                assertTrue(archive.contains("assets/audio/nachtwind.wav"));
            }

            // 7. Trigger Live Reload & verify Studio refresh + Studio input initialization
            assertNotNull(studio.getStudioInput(), "StudioWindowController should expose its own InputManager");
            studio.getStudioInput().onCursorPos(820.0, 44.0);
            app.getApi().requestLiveReload();
            app.stepOnce(1.0 / 60.0);
            studio.refreshInspectors();
        }

        // 8. Verify Standalone Mode does NOT load StudioWindowController
        EngineConfig standaloneConfig = EngineConfig.builder()
                .engineMode(EngineMode.STANDALONE_GAME)
                .projectDir(projectDir)
                .headless(true)
                .smokeTest(true)
                .build();

        try (EngineApplication standaloneApp = new EngineApplication(standaloneConfig)) {
            standaloneApp.initialize();
            assertEquals(null, standaloneApp.getApi().getService(StudioWindowController.class));
            assertFalse(standaloneApp.getWindowManager().hasStudioWindow());
            assertNotNull(standaloneApp.getGameHost());
            standaloneApp.run();
        }
    }

    @Test
    void everyMainJavaPackageHasAgentContextDocumentation() throws IOException {
        Path repoRoot = resolveRepoRoot();
        Path mainRoot = repoRoot.resolve("src/main/java/de/swfdtf");
        Map<String, PackageInventory> inventories = scanPackageInventories(mainRoot);

        assertEquals(Set.of(
                "de.swfdtf",
                "de.swfdtf.core",
                "de.swfdtf.math",
                "de.swfdtf.graphics",
                "de.swfdtf.parser",
                "de.swfdtf.state",
                "de.swfdtf.audio",
                "de.swfdtf.ui",
                "de.swfdtf.tools",
                "de.swfdtf.git"
        ), inventories.keySet(), "Discovered main Java packages changed; update AGENT_CONTEXT.md coverage");

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

        assertTrue(syncErrors.isEmpty(),
                "AGENT_CONTEXT.md synchronization mismatches detected:\n" + String.join("\n", syncErrors));
    }

    @Test
    void agentContextAuditCatchesMultiPackageBulletsAndUnbacktickedHeadings() {
        PackageInventory stateInv = new PackageInventory(
                Path.of("src/main/java/de/swfdtf/state"),
                Set.of("Alpha"),
                Map.of("Alpha", Set.of("Alpha", "Inner")),
                Set.of("Alpha", "Inner"),
                Set.of("de.swfdtf.parser")
        );
        PackageInventory parserInv = new PackageInventory(
                Path.of("src/main/java/de/swfdtf/parser"),
                Set.of("VJavaAst"),
                Map.of("VJavaAst", Set.of("VJavaAst")),
                Set.of("VJavaAst"),
                Set.of()
        );
        Map<String, PackageInventory> inventories = Map.of(
                "de.swfdtf.state", stateInv,
                "de.swfdtf.parser", parserInv
        );

        String badDepsMarkdown = """
                ## Abhängigkeiten zu anderen `de.swfdtf.*`-Paketen
                - `de.swfdtf.parser` und `de.swfdtf.nonexistent` (`VJavaAst`)
                """;
        List<String> depErrors = new ArrayList<>();
        auditDependenciesSection("de.swfdtf.state", stateInv, inventories, badDepsMarkdown, depErrors);
        assertTrue(depErrors.stream().anyMatch(e -> e.contains("de.swfdtf.nonexistent")),
                "Expected unknown second package on bullet line to be flagged: " + depErrors);

        String badHeadingsMarkdown = """
                ## Öffentliche API-Signaturen

                ### Kern, Theme & Screen-Management
                ### Alpha
                ### Alpha.Inner
                ### PhantomUnbackticked
                ### Alpha.MissingNested
                """;
        List<String> headingErrors = new ArrayList<>();
        auditPublicApiSignatures("de.swfdtf.state", stateInv, badHeadingsMarkdown, headingErrors);
        assertEquals(2, headingErrors.size(), "Expected exactly 2 heading errors: " + headingErrors);
        assertTrue(headingErrors.stream().anyMatch(e -> e.contains("PhantomUnbackticked")));
        assertTrue(headingErrors.stream().anyMatch(e -> e.contains("Alpha.MissingNested")));

        String stripped = stripCommentsAndStrings("interface/*c*/Foo {}\nString s = \"\"\"\nline1\nline2\n\"\"\";\n");
        assertTrue(stripped.contains("interface Foo"));
        assertEquals(6, stripped.split("\n", -1).length);
    }

    private record PackageInventory(
            Path directory,
            Set<String> topLevelTypes,
            Map<String, Set<String>> declaredTypesByFile,
            Set<String> allDeclaredTypes,
            Set<String> actualInternalDeps
    ) {
    }

    private static Path resolveRepoRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("src/main/java/de/swfdtf"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate KAGAYAKI repository root");
    }

    private static Map<String, PackageInventory> scanPackageInventories(Path mainRoot) throws IOException {
        Map<String, List<Path>> filesByPackage = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(mainRoot)) {
            List<Path> javaFiles = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
            for (Path file : javaFiles) {
                Path parent = file.getParent();
                Path rel = mainRoot.relativize(parent);
                String pkg = rel.toString().isEmpty()
                        ? "de.swfdtf"
                        : "de.swfdtf." + rel.toString().replace('\\', '.').replace('/', '.');
                filesByPackage.computeIfAbsent(pkg, ignored -> new ArrayList<>()).add(file);
            }
        }

        Pattern typeDeclPattern = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Za-z_][A-Za-z0-9_]*)");
        Pattern importPattern = Pattern.compile(
                "^\\s*import\\s+(?:static\\s+)?(de\\.swfdtf(?:\\.[a-z][a-z0-9_]*)*)\\.(?:[A-Z*])",
                Pattern.MULTILINE
        );
        Pattern fqcnPattern = Pattern.compile("\\b(de\\.swfdtf(?:\\.[a-z][a-z0-9_]*)*)\\.[A-Z][A-Za-z0-9_]*");

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
                Matcher typeMatcher = typeDeclPattern.matcher(stripped);
                while (typeMatcher.find()) {
                    String typeName = typeMatcher.group(1);
                    fileDeclared.add(typeName);
                    allDeclared.add(typeName);
                }
                declaredByFile.put(stem, fileDeclared);

                Matcher impMatcher = importPattern.matcher(stripped);
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
                    Matcher fqcnMatcher = fqcnPattern.matcher(line);
                    while (fqcnMatcher.find()) {
                        String target = fqcnMatcher.group(1);
                        if (!target.equals(pkg)) {
                            deps.add(target);
                        }
                    }
                }
            }

            inventories.put(pkg, new PackageInventory(dir, topLevelTypes, declaredByFile, allDeclared, deps));
        }
        return inventories;
    }

    private static void auditTypesTable(
            String pkg,
            PackageInventory inv,
            String markdown,
            List<String> errors
    ) {
        List<String> section = extractSectionLines(markdown, "## Enthaltene Typen");
        if (section.isEmpty()) {
            errors.add(pkg + ": missing '## Enthaltene Typen' section");
            return;
        }

        Pattern backtickPattern = Pattern.compile("`([^`]+)`");
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

            Matcher m = backtickPattern.matcher(col1);
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

        for (String actualTopLevel : inv.topLevelTypes()) {
            if (!documentedRootTypes.contains(actualTopLevel)) {
                errors.add(pkg + " table: missing documentation row for " + actualTopLevel + ".java");
            }
        }
    }

    private static void auditPublicApiSignatures(
            String pkg,
            PackageInventory inv,
            String markdown,
            List<String> errors
    ) {
        List<String> section = extractSectionLines(markdown, "## Öffentliche API-Signaturen");
        if (section.isEmpty()) {
            errors.add(pkg + ": missing '## Öffentliche API-Signaturen' section");
            return;
        }

        Pattern backtickPattern = Pattern.compile("`([^`]+)`");
        Pattern unbacktickedTypeHeadingPattern =
                Pattern.compile("^([A-Z][A-Za-z0-9_]*(?:\\.[A-Z][A-Za-z0-9_]*)*)$");
        Pattern codeTypeDeclPattern = Pattern.compile(
                "^\\s*(?:public\\s+)?(?:(?:static|final|abstract|sealed|non-sealed)\\s+)*"
                        + "(?:class|interface|enum|record)\\s+([A-Za-z_][A-Za-z0-9_]*)"
        );

        boolean inFence = false;
        for (String line : section) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (!inFence && trimmed.startsWith("### ")) {
                Matcher m = backtickPattern.matcher(trimmed);
                boolean hasBacktickTokens = false;
                while (m.find()) {
                    hasBacktickTokens = true;
                    String token = stripGenerics(m.group(1).trim());
                    validateApiHeadingTypeToken(pkg, inv, token, errors);
                }
                if (!hasBacktickTokens) {
                    String headingTitle = trimmed.substring(4).trim();
                    if (unbacktickedTypeHeadingPattern.matcher(headingTitle).matches()) {
                        validateApiHeadingTypeToken(pkg, inv, headingTitle, errors);
                    }
                }
            } else if (inFence) {
                Matcher declMatcher = codeTypeDeclPattern.matcher(line);
                if (declMatcher.find()) {
                    String typeName = declMatcher.group(1);
                    if (!inv.allDeclaredTypes().contains(typeName)) {
                        errors.add(pkg + " API code block: declares non-existent type '" + typeName + "'");
                    }
                }
            }
        }
    }

    private static void validateApiHeadingTypeToken(
            String pkg,
            PackageInventory inv,
            String token,
            List<String> errors
    ) {
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

    private static void auditDependenciesSection(
            String pkg,
            PackageInventory inv,
            Map<String, PackageInventory> allInventories,
            String markdown,
            List<String> errors
    ) {
        List<String> section = extractSectionLinesPrefix(markdown, "## Abhängigkeiten");
        if (section.isEmpty()) {
            errors.add(pkg + ": missing '## Abhängigkeiten' section");
            return;
        }

        Pattern pkgTokenPattern = Pattern.compile("`(de\\.swfdtf(?:\\.[a-z][a-z0-9_]*)*)`");
        Pattern backtickPattern = Pattern.compile("`([^`]+)`");
        Set<String> documentedDeps = new TreeSet<>();

        for (String line : section) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("-")) {
                continue;
            }
            Matcher pkgMatcher = pkgTokenPattern.matcher(trimmed);
            List<PackageInventory> lineDepInventories = new ArrayList<>();
            while (pkgMatcher.find()) {
                String depPkg = pkgMatcher.group(1);
                if (!depPkg.equals(pkg)) {
                    documentedDeps.add(depPkg);
                }
                PackageInventory depInv = allInventories.get(depPkg);
                if (depInv == null) {
                    errors.add(pkg + " dependencies: documents unknown internal package '" + depPkg + "'");
                } else {
                    lineDepInventories.add(depInv);
                }
            }
            if (lineDepInventories.isEmpty()) {
                continue;
            }

            Matcher typeMatcher = backtickPattern.matcher(trimmed);
            while (typeMatcher.find()) {
                String token = stripGenerics(typeMatcher.group(1).trim());
                if (token.startsWith("de.swfdtf") || !token.matches("[A-Z][A-Za-z0-9_]*")) {
                    continue;
                }
                boolean found = inv.allDeclaredTypes().contains(token);
                if (!found) {
                    for (PackageInventory depInv : lineDepInventories) {
                        if (depInv.allDeclaredTypes().contains(token)) {
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) {
                    errors.add(pkg + " dependencies: cited type '" + token + "' not found in "
                            + pkg + " or cited packages");
                }
            }
        }

        if (!inv.actualInternalDeps().equals(documentedDeps)) {
            errors.add(pkg + " dependencies mismatch: actual=" + inv.actualInternalDeps()
                    + " vs documented=" + documentedDeps);
        }
    }

    private static List<String> extractSectionLines(String markdown, String exactHeading) {
        List<String> lines = List.of(markdown.split("\r?\n", -1));
        List<String> result = new ArrayList<>();
        boolean inSection = false;
        for (String line : lines) {
            if (line.startsWith("## ")) {
                if (inSection) {
                    break;
                }
                if (line.trim().equals(exactHeading)) {
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

    private static List<String> extractSectionLinesPrefix(String markdown, String headingPrefix) {
        List<String> lines = List.of(markdown.split("\r?\n", -1));
        List<String> result = new ArrayList<>();
        boolean inSection = false;
        for (String line : lines) {
            if (line.startsWith("## ")) {
                if (inSection) {
                    break;
                }
                if (line.trim().startsWith(headingPrefix)) {
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
                out.append(' ');
                i += 2;
                while (i < n) {
                    char ch = source.charAt(i);
                    if (ch == '\n') {
                        out.append('\n');
                    } else if (ch == '*' && i + 1 < n && source.charAt(i + 1) == '/') {
                        i += 2;
                        break;
                    }
                    i++;
                }
            } else if (c == '"' && i + 2 < n && next == '"' && source.charAt(i + 2) == '"') {
                i += 3;
                while (i < n) {
                    char ch = source.charAt(i);
                    if (ch == '\\' && i + 1 < n) {
                        if (source.charAt(i + 1) == '\n') {
                            out.append('\n');
                        }
                        i += 2;
                        continue;
                    }
                    if (ch == '\n') {
                        out.append('\n');
                        i++;
                        continue;
                    }
                    if (ch == '"' && i + 2 < n && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                        i += 3;
                        break;
                    }
                    i++;
                }
            } else if (c == '"') {
                i++;
                while (i < n) {
                    char ch = source.charAt(i);
                    if (ch == '\\' && i + 1 < n) {
                        if (source.charAt(i + 1) == '\n') {
                            out.append('\n');
                        }
                        i += 2;
                        continue;
                    }
                    if (ch == '\n') {
                        out.append('\n');
                        i++;
                        break;
                    }
                    i++;
                    if (ch == '"') {
                        break;
                    }
                }
            } else if (c == '\'') {
                i++;
                while (i < n) {
                    char ch = source.charAt(i);
                    if (ch == '\\' && i + 1 < n) {
                        if (source.charAt(i + 1) == '\n') {
                            out.append('\n');
                        }
                        i += 2;
                        continue;
                    }
                    if (ch == '\n') {
                        out.append('\n');
                        i++;
                        break;
                    }
                    i++;
                    if (ch == '\'') {
                        break;
                    }
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
