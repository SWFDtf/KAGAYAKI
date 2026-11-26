package de.swfdtf.e2e.tier2;

import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineVersion;
import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.e2e.support.E2eWorkspaceSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 2 negative, boundary, and compliance-guard E2E tests for Feature Area F12:
 * Checkstyle violation detection, Javadoc {@code -Xdoclint:all} enforcement, interface naming
 * boundary, AWT/state-cycle comment vs import distinction, {@code AGENT_CONTEXT.md} sync negative
 * cases, CLI invalid flags, protected repository file integrity, and Gradle task isolation.
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER2)
public final class F12ComplianceBoundaryE2eTest {

    /** Creates a new {@code F12ComplianceBoundaryE2eTest} instance. */
    public F12ComplianceBoundaryE2eTest() {
    }

    private static final String ARCH_GUARD_FQN = "de.swfdtf.core.ArchitectureGuardTest";
    private static final String ARCH_GUARD_SRC = "src/test/java/de/swfdtf/core/ArchitectureGuardTest.java";
    private static final String E2E_ENGINE_FQN = "de.swfdtf.tools.EndToEndEngineTest";
    private static final String E2E_ENGINE_SRC = "src/test/java/de/swfdtf/tools/EndToEndEngineTest.java";
    private static final String LAUNCHER_CLASS = "org.junit.platform.launcher.core.LauncherFactory";

    private static final Pattern TOOL_VERSION_PATTERN =
            Pattern.compile("toolVersion\\s*=\\s*['\"]([^'\"]+)['\"]");

    private static final Set<String> PROTECTED_REPO_FILES = Set.of(
            "master_prompt_architektur_blaupause_java_lwjgl_3_vjava.md",
            ".idea/gradle.xml",
            ".idea/misc.xml"
    );

    /**
     * Verifies goal {@code #0941}: repository Checkstyle configuration enforces tab-free
     * indentation, maximum line length, and K&amp;R braces by executing real Gradle
     * {@code checkstyleMain} against compliant and violating Java sources.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if Checkstyle config inspection or Gradle execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void checkstyleConfigRejectsStyleViolations(@TempDir Path tempDir) throws Exception {
        Path repoRoot = E2eWorkspaceSupport.repoRoot();
        Path checkstyleDir = repoRoot.resolve("gradle/checkstyle");
        Path checkstyleXml = checkstyleDir.resolve("checkstyle.xml");
        assertTrue(Files.isRegularFile(checkstyleXml), "Expected checkstyle.xml under gradle/checkstyle/");

        String configContent = Files.readString(checkstyleXml, StandardCharsets.UTF_8);
        assertTrue(configContent.contains("FileTabCharacter"), "Checkstyle must forbid tabs");
        assertTrue(configContent.contains("LineLength"), "Checkstyle must enforce max line length");
        assertTrue(configContent.contains("LeftCurly"), "Checkstyle must enforce K&R LeftCurly braces");

        String buildGradle = Files.readString(repoRoot.resolve("build.gradle"), StandardCharsets.UTF_8);
        assertTrue(buildGradle.contains("checkstyle"), "build.gradle must apply checkstyle plugin");
        assertFalse(
                buildGradle.contains("ignoreFailures = true") || buildGradle.contains("ignoreFailures true"),
                "Checkstyle must not ignore failures in build.gradle"
        );

        Path csProbe = tempDir.resolve("cs_probe");
        Files.createDirectories(csProbe.resolve("src/main/java/sample"));
        Files.writeString(csProbe.resolve("settings.gradle"), "rootProject.name = 'cs-probe'\n",
                StandardCharsets.UTF_8);

        StringBuilder toolVersionLine = new StringBuilder();
        Matcher tvMatcher = TOOL_VERSION_PATTERN.matcher(buildGradle);
        if (tvMatcher.find()) {
            toolVersionLine.append("    toolVersion = '").append(tvMatcher.group(1)).append("'\n");
        }
        String probeGradle = "plugins {\n"
                + "    id 'java'\n"
                + "    id 'checkstyle'\n"
                + "}\n"
                + "repositories {\n"
                + "    mavenCentral()\n"
                + "}\n"
                + "checkstyle {\n"
                + toolVersionLine
                + "    configDirectory = file('" + escapeGroovyPath(checkstyleDir) + "')\n"
                + "    configFile = file('" + escapeGroovyPath(checkstyleXml) + "')\n"
                + "    ignoreFailures = false\n"
                + "    maxErrors = 0\n"
                + "    maxWarnings = 0\n"
                + "}\n";
        Files.writeString(csProbe.resolve("build.gradle"), probeGradle, StandardCharsets.UTF_8);

        Files.writeString(csProbe.resolve("src/main/java/sample/GoodStyle.java"), """
                package sample;

                /**
                 * Compliant sample class for Checkstyle validation.
                 */
                public final class GoodStyle {

                    /** Creates a compliant instance. */
                    public GoodStyle() {
                    }

                    /**
                     * Adds two integers.
                     *
                     * @param left  left operand
                     * @param right right operand
                     * @return sum of operands
                     */
                    public int add(int left, int right) {
                        return left + right;
                    }
                }
                """, StandardCharsets.UTF_8);

        E2eCliRunner.ProcessResult goodResult = E2eCliRunner.runGradle(
                repoRoot,
                List.of("-p", csProbe.toString(), "checkstyleMain"),
                Duration.ofMinutes(2)
        );
        assertTrue(
                goodResult.isSuccess(),
                "Compliant GoodStyle.java must pass checkstyleMain:\n"
                        + goodResult.stdout() + "\n" + goodResult.stderr()
        );

        String longLiteral = "x".repeat(130);
        Files.writeString(csProbe.resolve("src/main/java/sample/BadStyle.java"),
                "package sample;\n\n"
                        + "public class BadStyle\n"
                        + "{\n"
                        + "\tpublic String bad() {\n"
                        + "        return \"" + longLiteral + "\";\n"
                        + "    }\n"
                        + "}\n",
                StandardCharsets.UTF_8);

        E2eCliRunner.ProcessResult badResult = E2eCliRunner.runGradle(
                repoRoot,
                List.of("-p", csProbe.toString(), "checkstyleMain"),
                Duration.ofMinutes(2)
        );
        assertFalse(badResult.isSuccess(), "Violating BadStyle.java must fail checkstyleMain");
        String badOutput = badResult.stdout() + "\n" + badResult.stderr();
        assertTrue(
                badOutput.contains("BadStyle.java"),
                "Checkstyle output must reference BadStyle.java, got:\n" + badOutput
        );
    }

    /**
     * Verifies goal {@code #0942}: the real {@code ArchitectureGuardTest} interface naming audit
     * permits allowlisted {@code IRenderPipeline}, rejects newly introduced {@code I*}-prefixed
     * interfaces, and accepts compliant interfaces and {@code @interface} annotations.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if copying production sources or executing the guard test fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void interfaceNamingAuditFlagsDisallowedIPrefixOutsideAllowlist(@TempDir Path tempDir) throws Exception {
        Path repoRoot = E2eWorkspaceSupport.repoRoot();
        Path syntheticRepo = tempDir.resolve("repo");
        copyProductionTrees(repoRoot, syntheticRepo);
        Path guardSource = repoRoot.resolve(ARCH_GUARD_SRC);

        E2eCliRunner.ProcessResult baselineRes = compileAndRunRepoGuardTest(
                tempDir,
                syntheticRepo,
                guardSource,
                ARCH_GUARD_FQN,
                "productionInterfacesFollowNamingConventionWithoutNewIPrefix"
        );
        assertTrue(
                baselineRes.isSuccess(),
                "Control pass must allow legacy IRenderPipeline:\n"
                        + baselineRes.stdout() + "\n" + baselineRes.stderr()
        );

        Path disallowedFile = syntheticRepo.resolve("src/main/java/de/swfdtf/core/ICustomDisallowedService.java");
        Files.writeString(disallowedFile, """
                package de.swfdtf.core;

                public interface ICustomDisallowedService {
                }
                """, StandardCharsets.UTF_8);

        E2eCliRunner.ProcessResult rejectedRes = compileAndRunRepoGuardTest(
                tempDir,
                syntheticRepo,
                guardSource,
                ARCH_GUARD_FQN,
                "productionInterfacesFollowNamingConventionWithoutNewIPrefix"
        );
        assertFalse(rejectedRes.isSuccess(), "Guard must reject new I*-prefixed interface");
        String rejectedOutput = rejectedRes.stdout() + "\n" + rejectedRes.stderr();
        assertTrue(
                rejectedOutput.contains("de.swfdtf.core.ICustomDisallowedService"),
                "Failure output must identify de.swfdtf.core.ICustomDisallowedService, got:\n" + rejectedOutput
        );

        Files.delete(disallowedFile);
        Files.writeString(
                syntheticRepo.resolve("src/main/java/de/swfdtf/core/CustomAllowedService.java"),
                """
                package de.swfdtf.core;

                public interface CustomAllowedService {
                }
                """,
                StandardCharsets.UTF_8
        );
        Files.writeString(
                syntheticRepo.resolve("src/main/java/de/swfdtf/core/IAnnotationMarker.java"),
                """
                package de.swfdtf.core;

                public @interface IAnnotationMarker {
                }
                """,
                StandardCharsets.UTF_8
        );

        E2eCliRunner.ProcessResult compliantRes = compileAndRunRepoGuardTest(
                tempDir,
                syntheticRepo,
                guardSource,
                ARCH_GUARD_FQN,
                "productionInterfacesFollowNamingConventionWithoutNewIPrefix"
        );
        assertTrue(
                compliantRes.isSuccess(),
                "Compliant interface and @interface must pass naming guard:\n"
                        + compliantRes.stdout() + "\n" + compliantRes.stderr()
        );
    }

    /**
     * Verifies the real {@code ArchitectureGuardTest} AWT/ImageIO and {@code de.swfdtf.state}
     * dependency-cycle guards detect actual forbidden imports while ignoring comments and literals.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if copying production sources or executing the guard tests fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void awtAndStateCycleGuardsDetectForbiddenImportsWhileIgnoringComments(@TempDir Path tempDir) throws Exception {
        Path repoRoot = E2eWorkspaceSupport.repoRoot();
        Path syntheticRepo = tempDir.resolve("repo");
        copyProductionTrees(repoRoot, syntheticRepo);
        Path guardSource = repoRoot.resolve(ARCH_GUARD_SRC);

        Path commentProbe = syntheticRepo.resolve("src/main/java/de/swfdtf/state/CommentAndLiteralProbe.java");
        Files.writeString(commentProbe, """
                package de.swfdtf.state;

                // java.awt.Color
                /* javax.imageio.ImageIO import de.swfdtf.parser.VJavaAst; */
                public final class CommentAndLiteralProbe {
                    private final String note = "java.awt.Frame de.swfdtf.ui.Screen";
                }
                """, StandardCharsets.UTF_8);

        E2eCliRunner.ProcessResult awtCommentPass = compileAndRunRepoGuardTest(
                tempDir,
                syntheticRepo,
                guardSource,
                ARCH_GUARD_FQN,
                "productionCodeHasNoAwtOrImageIoUsages"
        );
        assertTrue(
                awtCommentPass.isSuccess(),
                "Comments/literals mentioning java.awt must not fail AWT guard:\n"
                        + awtCommentPass.stdout() + "\n" + awtCommentPass.stderr()
        );

        E2eCliRunner.ProcessResult cycleCommentPass = compileAndRunRepoGuardTest(
                tempDir,
                syntheticRepo,
                guardSource,
                ARCH_GUARD_FQN,
                "stateHasNoParserUiOrToolsDependenciesAndPackageLayersRemainBounded"
        );
        assertTrue(
                cycleCommentPass.isSuccess(),
                "Comments/literals mentioning parser/ui must not fail state cycle guard:\n"
                        + cycleCommentPass.stdout() + "\n" + cycleCommentPass.stderr()
        );

        Path awtProbe = syntheticRepo.resolve("src/main/java/de/swfdtf/core/ForbiddenAwtProbe.java");
        Files.writeString(
                awtProbe,
                "package de.swfdtf.core;\n\n"
                        + "import " + "java.awt.Color;\n\n"
                        + "public final class ForbiddenAwtProbe {\n}\n",
                StandardCharsets.UTF_8
        );

        E2eCliRunner.ProcessResult awtFail = compileAndRunRepoGuardTest(
                tempDir,
                syntheticRepo,
                guardSource,
                ARCH_GUARD_FQN,
                "productionCodeHasNoAwtOrImageIoUsages"
        );
        assertFalse(awtFail.isSuccess(), "AWT guard must reject actual import java.awt.Color");
        String awtOutput = awtFail.stdout() + "\n" + awtFail.stderr();
        assertTrue(
                awtOutput.contains("ForbiddenAwtProbe.java"),
                "AWT guard failure must cite ForbiddenAwtProbe.java, got:\n" + awtOutput
        );

        Files.delete(awtProbe);
        Path cycleProbe = syntheticRepo.resolve("src/main/java/de/swfdtf/state/ForbiddenCycleProbe.java");
        Files.writeString(cycleProbe, """
                package de.swfdtf.state;

                import de.swfdtf.parser.VJavaAst;

                public final class ForbiddenCycleProbe {
                }
                """, StandardCharsets.UTF_8);

        E2eCliRunner.ProcessResult cycleFail = compileAndRunRepoGuardTest(
                tempDir,
                syntheticRepo,
                guardSource,
                ARCH_GUARD_FQN,
                "stateHasNoParserUiOrToolsDependenciesAndPackageLayersRemainBounded"
        );
        assertFalse(cycleFail.isSuccess(), "State cycle guard must reject import de.swfdtf.parser.VJavaAst");
        String cycleOutput = cycleFail.stdout() + "\n" + cycleFail.stderr();
        assertTrue(
                cycleOutput.contains("ForbiddenCycleProbe.java") && cycleOutput.contains("de.swfdtf.parser"),
                "State cycle failure must cite ForbiddenCycleProbe.java and de.swfdtf.parser, got:\n" + cycleOutput
        );
    }

    /**
     * Verifies goal {@code #0944}: the real {@code EndToEndEngineTest} {@code AGENT_CONTEXT.md}
     * sync audit passes on synchronized copies and detects phantom symbols, undocumented Java
     * files, and missing {@code AGENT_CONTEXT.md} files.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if copying production sources or executing the sync audit fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void agentContextSyncDetectsMissingAndPhantomReferences(@TempDir Path tempDir) throws Exception {
        Path repoRoot = E2eWorkspaceSupport.repoRoot();
        Path syntheticRepo = tempDir.resolve("repo");
        copyProductionTrees(repoRoot, syntheticRepo);
        Path engineTestSource = repoRoot.resolve(E2E_ENGINE_SRC);

        E2eCliRunner.ProcessResult controlRes = compileAndRunRepoGuardTest(
                tempDir,
                syntheticRepo,
                engineTestSource,
                E2E_ENGINE_FQN,
                "everyMainJavaPackageHasAgentContextDocumentation"
        );
        assertTrue(
                controlRes.isSuccess(),
                "Synchronized copy must pass real AGENT_CONTEXT.md sync audit:\n"
                        + controlRes.stdout() + "\n" + controlRes.stderr()
        );

        Path mathContext = syntheticRepo.resolve("src/main/java/de/swfdtf/math/AGENT_CONTEXT.md");
        String originalMathContext = Files.readString(mathContext, StandardCharsets.UTF_8);
        assertTrue(
                originalMathContext.contains("## Öffentliche API-Signaturen"),
                "Expected ## Öffentliche API-Signaturen section in math/AGENT_CONTEXT.md"
        );
        String withPhantomRow = originalMathContext.replace(
                "## Öffentliche API-Signaturen",
                "| `GhostNonExistentType` | `class` | Phantom |\n\n## Öffentliche API-Signaturen"
        );
        Files.writeString(mathContext, withPhantomRow, StandardCharsets.UTF_8);

        E2eCliRunner.ProcessResult phantomRes = compileAndRunRepoGuardTest(
                tempDir,
                syntheticRepo,
                engineTestSource,
                E2E_ENGINE_FQN,
                "everyMainJavaPackageHasAgentContextDocumentation"
        );
        assertFalse(phantomRes.isSuccess(), "Sync audit must reject phantom GhostNonExistentType");
        String phantomOut = phantomRes.stdout() + "\n" + phantomRes.stderr();
        assertTrue(
                phantomOut.contains("GhostNonExistentType"),
                "Failure output must cite GhostNonExistentType, got:\n" + phantomOut
        );

        Files.writeString(mathContext, originalMathContext, StandardCharsets.UTF_8);
        Path undocumentedFile = syntheticRepo.resolve("src/main/java/de/swfdtf/math/UndocumentedProbe.java");
        Files.writeString(undocumentedFile, """
                package de.swfdtf.math;

                public final class UndocumentedProbe {
                }
                """, StandardCharsets.UTF_8);

        E2eCliRunner.ProcessResult undocRes = compileAndRunRepoGuardTest(
                tempDir,
                syntheticRepo,
                engineTestSource,
                E2E_ENGINE_FQN,
                "everyMainJavaPackageHasAgentContextDocumentation"
        );
        assertFalse(undocRes.isSuccess(), "Sync audit must reject undocumented UndocumentedProbe.java");
        String undocOut = undocRes.stdout() + "\n" + undocRes.stderr();
        assertTrue(
                undocOut.contains("UndocumentedProbe.java"),
                "Failure output must cite UndocumentedProbe.java, got:\n" + undocOut
        );

        Files.delete(undocumentedFile);
        Files.delete(mathContext);

        E2eCliRunner.ProcessResult missingContextRes = compileAndRunRepoGuardTest(
                tempDir,
                syntheticRepo,
                engineTestSource,
                E2E_ENGINE_FQN,
                "everyMainJavaPackageHasAgentContextDocumentation"
        );
        assertFalse(missingContextRes.isSuccess(), "Sync audit must reject missing AGENT_CONTEXT.md");
        String missingOut = missingContextRes.stdout() + "\n" + missingContextRes.stderr();
        assertTrue(
                missingOut.contains("Missing AGENT_CONTEXT.md"),
                "Failure output must cite Missing AGENT_CONTEXT.md, got:\n" + missingOut
        );
    }

    /**
     * Verifies {@code Main} and {@link EngineConfig#fromArgs(String[])} CLI argument boundary
     * validation rejects missing {@code --create-template} directory and unknown flags cleanly.
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void mainCliRejectsMissingCreateTemplateArgumentAndUnknownFlags() {
        assertThrows(
                IllegalArgumentException.class,
                () -> EngineConfig.fromArgs(new String[]{"--unknown-flag"})
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> EngineConfig.fromArgs(new String[]{"--mode", "INVALID_MODE"})
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> EngineConfig.fromArgs(new String[]{"--width", "0"})
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> de.swfdtf.Main.main(new String[]{"--create-template"})
        );
    }

    /**
     * Verifies protected repository files ({@code master_prompt_architektur_blaupause_java_lwjgl_3_vjava.md},
     * {@code .idea/gradle.xml}, {@code .idea/misc.xml}) are never staged in Git, and verifies
     * goals {@code #1000}/{@code #1020} release metadata consistency.
     *
     * @throws Exception if running Git commands fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void protectedRepoFilesRemainUnstagedInGitIndex() throws Exception {
        Path repoRoot = E2eWorkspaceSupport.repoRoot();
        E2eCliRunner.ProcessResult staged = E2eCliRunner.runCommand(
                repoRoot,
                List.of("git", "diff", "--cached", "--name-only"),
                Duration.ofSeconds(15)
        );
        assertTrue(staged.isSuccess(), "git diff --cached failed: " + staged.stderr());
        for (String line : staged.stdout().lines().toList()) {
            String trimmed = line.trim();
            assertFalse(
                    PROTECTED_REPO_FILES.contains(trimmed),
                    "Protected file must never be staged in Git index: " + trimmed
            );
        }
        assertFalse(EngineVersion.VERSION.isBlank());
        assertTrue(Files.isRegularFile(repoRoot.resolve("ZIELE.md")));
    }

    /**
     * Verifies {@code build.gradle} isolates {@code src/e2eTest} from {@code src/test} both on
     * the runtime classpath and in the Gradle task graph ({@code test --dry-run} vs
     * {@code e2eTest --dry-run}).
     *
     * @throws Exception if Gradle dry-run execution fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void e2eTestSourceSetIsolatedFromUnitTestTask() throws Exception {
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName(ARCH_GUARD_FQN),
                "Unit test classes from src/test must not leak onto e2eTest runtimeClasspath"
        );

        Path repoRoot = E2eWorkspaceSupport.repoRoot();
        E2eCliRunner.ProcessResult unitDryRun = E2eCliRunner.runGradle(
                repoRoot,
                List.of("test", "--dry-run"),
                Duration.ofMinutes(2)
        );
        assertTrue(
                unitDryRun.isSuccess(),
                "gradlew test --dry-run failed:\n" + unitDryRun.stdout() + "\n" + unitDryRun.stderr()
        );
        String unitGraph = unitDryRun.stdout();
        assertTrue(unitGraph.contains(":test SKIPPED"), "Expected :test task in unit test dry-run");
        assertTrue(unitGraph.contains(":compileTestJava SKIPPED"), "Expected :compileTestJava in unit test dry-run");
        assertFalse(unitGraph.contains(":e2eTest"), "Unit test task must not schedule :e2eTest");
        assertFalse(unitGraph.contains(":compileE2eTestJava"), "Unit test task must not compile :e2eTest");

        E2eCliRunner.ProcessResult e2eDryRun = E2eCliRunner.runGradle(
                repoRoot,
                List.of("e2eTest", "--dry-run"),
                Duration.ofMinutes(2)
        );
        assertTrue(
                e2eDryRun.isSuccess(),
                "gradlew e2eTest --dry-run failed:\n" + e2eDryRun.stdout() + "\n" + e2eDryRun.stderr()
        );
        String e2eGraph = e2eDryRun.stdout();
        assertTrue(e2eGraph.contains(":e2eTest SKIPPED"), "Expected :e2eTest task in e2eTest dry-run");
        assertTrue(e2eGraph.contains(":compileE2eTestJava SKIPPED"), "Expected :compileE2eTestJava in e2eTest dry-run");
    }

    /**
     * Verifies goal {@code #0943}: {@code build.gradle} configures {@code -Xdoclint:all} and
     * {@code javadoc -encoding UTF-8 -Xdoclint:all -Werror} accepts compliant Javadoc while
     * rejecting broken {@code {@link ...}} references and missing parameter tags.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if reading {@code build.gradle} or running {@code javadoc} fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void javadocDoclintRejectsBrokenLinksAndMissingTags(@TempDir Path tempDir) throws Exception {
        Path repoRoot = E2eWorkspaceSupport.repoRoot();
        String buildGradle = Files.readString(repoRoot.resolve("build.gradle"), StandardCharsets.UTF_8);
        assertTrue(buildGradle.contains("Xdoclint:all"), "Expected -Xdoclint:all configured in build.gradle");

        String javadocExe = resolveJdkToolExecutable("javadoc");
        Path goodSrc = tempDir.resolve("good_src/sample/GoodDoc.java");
        Files.createDirectories(goodSrc.getParent());
        Files.writeString(goodSrc, """
                package sample;

                /**
                 * Compliant Javadoc sample.
                 */
                public final class GoodDoc {

                    /** Creates a new {@code GoodDoc} instance. */
                    public GoodDoc() {
                    }

                    /**
                     * Computes the square of {@code value}.
                     *
                     * @param value input integer
                     * @return squared integer
                     */
                    public int square(int value) {
                        return value * value;
                    }
                }
                """, StandardCharsets.UTF_8);

        Path goodOut = tempDir.resolve("good_javadoc_out");
        E2eCliRunner.ProcessResult goodRes = E2eCliRunner.runCommand(
                tempDir,
                List.of(
                        javadocExe,
                        "-encoding", "UTF-8",
                        "-Xdoclint:all",
                        "-Werror",
                        "-d", goodOut.toString(),
                        goodSrc.toString()
                ),
                Duration.ofSeconds(30)
        );
        assertTrue(
                goodRes.isSuccess(),
                "Compliant GoodDoc.java must pass javadoc -Xdoclint:all -Werror:\n"
                        + goodRes.stdout() + "\n" + goodRes.stderr()
        );

        Path badSrc = tempDir.resolve("bad_src/sample/BadDoc.java");
        Files.createDirectories(badSrc.getParent());
        Files.writeString(badSrc, """
                package sample;

                /**
                 * Broken reference to {@link MissingTargetType}.
                 */
                public final class BadDoc {

                    public int broken(int x) {
                        return x;
                    }
                }
                """, StandardCharsets.UTF_8);

        Path badOut = tempDir.resolve("bad_javadoc_out");
        E2eCliRunner.ProcessResult badRes = E2eCliRunner.runCommand(
                tempDir,
                List.of(
                        javadocExe,
                        "-encoding", "UTF-8",
                        "-Xdoclint:all",
                        "-Werror",
                        "-d", badOut.toString(),
                        badSrc.toString()
                ),
                Duration.ofSeconds(30)
        );
        assertFalse(badRes.isSuccess(), "Broken Javadoc must fail javadoc -Xdoclint:all -Werror");
        String badOutput = badRes.stdout() + "\n" + badRes.stderr();
        assertTrue(
                badOutput.contains("BadDoc.java") && badOutput.contains("MissingTargetType"),
                "Javadoc error output must cite BadDoc.java and MissingTargetType, got:\n" + badOutput
        );
    }

    private static void copyProductionTrees(Path repoRoot, Path targetRepoRoot) throws IOException {
        copyDirectoryTree(repoRoot.resolve("src/main/java/de/swfdtf"),
                targetRepoRoot.resolve("src/main/java/de/swfdtf"));
        Path demoSrc = repoRoot.resolve("demo/src/main/java");
        if (Files.isDirectory(demoSrc)) {
            copyDirectoryTree(demoSrc, targetRepoRoot.resolve("demo/src/main/java"));
        }
    }

    private static void copyDirectoryTree(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path srcPath : walk.sorted(Comparator.naturalOrder()).toList()) {
                Path rel = source.relativize(srcPath);
                Path destPath = target.resolve(rel.toString());
                if (Files.isDirectory(srcPath)) {
                    Files.createDirectories(destPath);
                } else {
                    Files.createDirectories(destPath.getParent());
                    Files.copy(srcPath, destPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static E2eCliRunner.ProcessResult compileAndRunRepoGuardTest(
            Path tempDir,
            Path syntheticRepoRoot,
            Path testSourceFile,
            String testClassFqn,
            String testMethodName
    ) throws IOException, InterruptedException {
        assertTrue(Files.isRegularFile(testSourceFile), "Missing guard test source: " + testSourceFile);

        Path repoRoot = E2eWorkspaceSupport.repoRoot();
        String effectiveClasspath = resolveEffectiveClasspath(repoRoot);
        String sanitizedName = testClassFqn.replace('.', '_');
        Path classesDir = tempDir.resolve("guard_classes_" + sanitizedName);

        if (!Files.isRegularFile(classesDir.resolve("GuardMethodRunner.class"))) {
            Files.createDirectories(classesDir);
            Path runnerSrc = tempDir.resolve("guard_runner_" + sanitizedName + "/GuardMethodRunner.java");
            Files.createDirectories(runnerSrc.getParent());
            Files.writeString(runnerSrc, """
                    import org.junit.platform.engine.discovery.DiscoverySelectors;
                    import org.junit.platform.launcher.Launcher;
                    import org.junit.platform.launcher.LauncherDiscoveryRequest;
                    import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
                    import org.junit.platform.launcher.core.LauncherFactory;
                    import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
                    import org.junit.platform.launcher.listeners.TestExecutionSummary;

                    public final class GuardMethodRunner {
                        public static void main(String[] args) {
                            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                                    .selectors(DiscoverySelectors.selectMethod(args[0], args[1]))
                                    .build();
                            Launcher launcher = LauncherFactory.create();
                            SummaryGeneratingListener listener = new SummaryGeneratingListener();
                            launcher.execute(request, listener);
                            TestExecutionSummary summary = listener.getSummary();
                            for (TestExecutionSummary.Failure failure : summary.getFailures()) {
                                System.err.println(failure.getTestIdentifier().getDisplayName()
                                        + ": " + failure.getException());
                                failure.getException().printStackTrace(System.err);
                            }
                            long found = summary.getTestsFoundCount();
                            long failed = summary.getTestsFailedCount();
                            long aborted = summary.getTestsAbortedCount();
                            if (found == 0 || failed > 0 || aborted > 0) {
                                System.exit(1);
                            }
                        }
                    }
                    """, StandardCharsets.UTF_8);

            Path compileArgsFile = tempDir.resolve("compile_" + sanitizedName + ".args");
            List<String> compileArgs = new ArrayList<>();
            compileArgs.add("--release");
            compileArgs.add("21");
            compileArgs.add("-encoding");
            compileArgs.add("UTF-8");
            if (!effectiveClasspath.isBlank()) {
                compileArgs.add("-cp");
                compileArgs.add(effectiveClasspath);
            }
            compileArgs.add("-d");
            compileArgs.add(classesDir.toAbsolutePath().normalize().toString());
            compileArgs.add(testSourceFile.toAbsolutePath().normalize().toString());
            compileArgs.add(runnerSrc.toAbsolutePath().normalize().toString());
            Files.writeString(compileArgsFile, formatArgFile(compileArgs), StandardCharsets.UTF_8);

            E2eCliRunner.ProcessResult compileRes = E2eCliRunner.runCommand(
                    tempDir,
                    List.of(resolveJdkToolExecutable("javac"), "@" + compileArgsFile.toAbsolutePath().normalize()),
                    Duration.ofSeconds(30)
            );
            assertTrue(
                    compileRes.isSuccess(),
                    "Failed to compile guard test " + testSourceFile + ":\n"
                            + compileRes.stdout() + "\n" + compileRes.stderr()
            );
        }

        String runClasspath = classesDir.toAbsolutePath().normalize()
                + (effectiveClasspath.isBlank() ? "" : File.pathSeparator + effectiveClasspath);
        Path runArgsFile = tempDir.resolve("run_" + sanitizedName + "_" + testMethodName + ".args");
        List<String> runArgs = List.of(
                "-cp",
                runClasspath,
                "GuardMethodRunner",
                testClassFqn,
                testMethodName
        );
        Files.writeString(runArgsFile, formatArgFile(runArgs), StandardCharsets.UTF_8);

        return E2eCliRunner.runCommand(
                syntheticRepoRoot,
                List.of(resolveJdkToolExecutable("java"), "@" + runArgsFile.toAbsolutePath().normalize()),
                Duration.ofSeconds(30)
        );
    }

    private static String resolveEffectiveClasspath(Path repoRoot) {
        Set<String> entries = new LinkedHashSet<>();
        String sysCp = System.getProperty("java.class.path", "");
        if (!sysCp.isBlank()) {
            for (String entry : sysCp.split(Pattern.quote(File.pathSeparator))) {
                addExistingPath(entries, entry);
            }
        }
        collectClassLoaderUrls(F12ComplianceBoundaryE2eTest.class.getClassLoader(), entries);
        collectClassLoaderUrls(Thread.currentThread().getContextClassLoader(), entries);

        Path mainClasses = repoRoot.resolve("build/classes/java/main");
        if (Files.isDirectory(mainClasses)) {
            addExistingPath(entries, mainClasses.toString());
        }

        if (!hasClassOnClasspath(entries, LAUNCHER_CLASS)) {
            addCodeSourceOfClass(entries, LAUNCHER_CLASS);
        }
        if (!hasClassOnClasspath(entries, LAUNCHER_CLASS)) {
            findJarInGradleCache("org.junit.platform", "junit-platform-launcher")
                    .ifPresent(p -> addExistingPath(entries, p.toString()));
        }
        return String.join(File.pathSeparator, entries);
    }

    private static void collectClassLoaderUrls(ClassLoader loader, Set<String> entries) {
        ClassLoader current = loader;
        while (current != null) {
            if (current instanceof URLClassLoader urlLoader) {
                for (URL url : urlLoader.getURLs()) {
                    if ("file".equalsIgnoreCase(url.getProtocol())) {
                        try {
                            addExistingPath(entries, Path.of(url.toURI()).toString());
                        } catch (URISyntaxException ignored) {
                            // Skip non-URI file URLs.
                        }
                    }
                }
            }
            current = current.getParent();
        }
    }

    private static void addExistingPath(Set<String> entries, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return;
        }
        Path candidate = Path.of(rawPath).toAbsolutePath().normalize();
        if (Files.exists(candidate)) {
            entries.add(candidate.toString());
        }
    }

    private static boolean hasClassOnClasspath(Set<String> entries, String className) {
        List<URL> urls = new ArrayList<>();
        for (String entry : entries) {
            try {
                urls.add(Path.of(entry).toUri().toURL());
            } catch (MalformedURLException ignored) {
                // Skip invalid entry.
            }
        }
        String resource = className.replace('.', '/') + ".class";
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader()
        )) {
            return loader.getResource(resource) != null;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void addCodeSourceOfClass(Set<String> entries, String className) {
        try {
            Class<?> cls = Class.forName(className);
            CodeSource cs = cls.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null && "file".equalsIgnoreCase(cs.getLocation().getProtocol())) {
                addExistingPath(entries, Path.of(cs.getLocation().toURI()).toString());
            }
        } catch (ClassNotFoundException | URISyntaxException ignored) {
            // Fall back to Gradle cache lookup.
        }
    }

    private static Optional<Path> findJarInGradleCache(String group, String artifact) {
        String userHome = System.getProperty("user.home", "");
        if (userHome.isBlank()) {
            return Optional.empty();
        }
        Path artifactRoot = Path.of(userHome, ".gradle", "caches", "modules-2", "files-2.1", group, artifact);
        if (!Files.isDirectory(artifactRoot)) {
            return Optional.empty();
        }
        try (Stream<Path> walk = Files.walk(artifactRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".jar")
                                && !name.endsWith("-sources.jar")
                                && !name.endsWith("-javadoc.jar");
                    })
                    .sorted(Comparator.reverseOrder())
                    .findFirst();
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private static String formatArgFile(List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (String arg : args) {
            sb.append('"')
                    .append(arg.replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"')
                    .append('\n');
        }
        return sb.toString();
    }

    private static String resolveJdkToolExecutable(String toolName) {
        String javaHome = System.getProperty("java.home", "");
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String exeName = isWindows ? toolName + ".exe" : toolName;
        if (!javaHome.isBlank()) {
            Path candidate = Path.of(javaHome, "bin", exeName);
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return exeName;
    }

    private static String escapeGroovyPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("\\", "\\\\").replace("'", "\\'");
    }
}
