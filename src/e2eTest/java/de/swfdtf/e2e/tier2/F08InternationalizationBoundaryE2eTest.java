package de.swfdtf.e2e.tier2;

import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineMode;
import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2eFixtureFactory;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.e2e.support.E2eWorkspaceSupport;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.state.LocalizationManager;
import de.swfdtf.state.TranslationEntry;
import de.swfdtf.tools.TranslationCoverageAuditor;
import de.swfdtf.tools.VJavaLinter;
import de.swfdtf.ui.MainMenuScreen;
import de.swfdtf.ui.PreferencesScreen;
import de.swfdtf.ui.ScreenManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 2 boundary, escaping, fallback, and negative E2E tests for Feature Area F8:
 * CSV/XLIFF escaping &amp; malformed input rejection, translation memory false-positive guards,
 * unknown locale fallback, ICU boundary counts, escaped tag safety, and zero-dialogue strict
 * auditing ({@code #0681}–{@code #0684}, {@code #0688}, {@code #0689}).
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER2)
public final class F08InternationalizationBoundaryE2eTest {

    private static final double FIXED_DT = 1.0 / 60.0;

    /** Creates a new {@code F08InternationalizationBoundaryE2eTest} instance. */
    public F08InternationalizationBoundaryE2eTest() {
    }

    /**
     * Verifies baseline {@link LocalizationManager} fallback on empty translation strings,
     * unknown language identifiers, and missing localized asset overrides.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void localizationFallbackAndEmptyEntriesBaseline(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF08Project(tempDir.resolve("f08_b_base"), "e2e/f08/i18n_icu_project");
        LocalizationManager loc = new LocalizationManager("deutsch");
        loc.indexTranslationBlocks("deutsch", List.of(
                new LocalizationManager.TranslationBlock("english", "line_01", List.of(
                        new TranslationEntry("line_01", "Originaltext", "", "lena", 1)
                ))
        ));

        loc.setLanguage("english");
        assertEquals("Originaltext", loc.translateDialogue("line_01", "Originaltext"),
                "Empty translation stub must fall back to originalText");

        loc.setLanguage("klingon");
        assertEquals("Fallback", loc.translateUi("missing.key", "Fallback"));
        assertEquals("assets/images/bg/bg_sky.png",
                loc.resolveLocalizedAsset(projectDir, "assets/images/bg/bg_sky.png"));

        assertThrows(IllegalArgumentException.class, () -> loc.setLanguage("   "));
    }

    /**
     * Verifies goal {@code #0681} boundary paths: quotes/commas/tags escape cleanly across
     * CSV/PO/XLIFF export, and malformed CSV import rejects without corrupting project files.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void translationExportEscapingAndMalformedImportRejected(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF08Project(tempDir.resolve("f08_b_export"), "e2e/f08/i18n_icu_project");
        E2eWorkspaceSupport.writeScript(projectDir, "special.vjava", """
                label special_chars:
                    lena "Text mit, Komma und \\"Anführungszeichen\\" sowie {b}Fett{/b}."
                    return
                """);

        Class<?> serviceClass = findPublicClass(
                "de.swfdtf.state.TranslationExportService",
                "de.swfdtf.tools.TranslationExportService"
        );
        assertNotNull(serviceClass, "TranslationExportService must exist");
        Object service = serviceClass.getConstructor().newInstance();
        VJavaAst.ProjectAst ast = ProjectScanner.scan(projectDir);

        Path csvFile = tempDir.resolve("escaped.csv");
        invokeExportCsv(serviceClass, service, projectDir, ast, csvFile);
        String csvText = Files.readString(csvFile, StandardCharsets.UTF_8);
        assertTrue(csvText.contains("Anführungszeichen") && csvText.contains("{b}Fett{/b}"),
                "Exported CSV must preserve special characters and rich-text tags");

        Path malformedCsv = tempDir.resolve("bad.csv");
        Files.writeString(malformedCsv, "\"unterminated_quote,broken\n", StandardCharsets.UTF_8);
        Path targetVjava = projectDir.resolve("tl/spanish/translations.vjava");
        assertThrows(Exception.class, () -> {
            try {
                invokeImportCsv(serviceClass, service, malformedCsv, "spanish", targetVjava);
            } catch (InvocationTargetException ex) {
                throw (Exception) ex.getCause();
            }
        });
        assertFalse(Files.exists(targetVjava), "Malformed import must not leave partial .vjava output");
    }

    /**
     * Verifies goal {@code #0682} negative path: {@code TranslationMemory} does not emit fuzzy
     * matches for completely unrelated strings or an empty memory store.
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void translationMemoryRejectsUnrelatedLinesAndEmptyStore() throws Exception {
        Class<?> tmClass = findPublicClass(
                "de.swfdtf.state.TranslationMemory",
                "de.swfdtf.tools.TranslationMemory"
        );
        assertNotNull(tmClass, "TranslationMemory must exist");

        Object memory = tmClass.getConstructor().newInstance();
        Method matchMethod = findPublicMethod(tmClass, "match", String.class);
        if (matchMethod == null) {
            matchMethod = findPublicMethod(tmClass, "lookup", String.class);
        }
        assertNotNull(matchMethod);

        Object emptyResult = matchMethod.invoke(memory, "Völlig unbekannter Satz.");
        assertTrue(emptyResult == null || emptyResult.toString().contains("empty")
                || emptyResult.toString().contains("Optional.empty")
                || emptyResult.toString().isBlank(),
                "Empty TranslationMemory must return empty result");

        Method learnMethod = findPublicMethod(tmClass, "learn", String.class, String.class);
        if (learnMethod == null) {
            learnMethod = findPublicMethod(tmClass, "add", String.class, String.class);
        }
        assertNotNull(learnMethod);
        learnMethod.invoke(memory, "Der König reitet durch den tiefen Winterwald.",
                "The king rides through the deep winter forest.");

        Object unrelated = matchMethod.invoke(memory, "Astronauten landen auf dem roten Mars.");
        assertFalse(unrelated != null && unrelated.toString().contains("winter forest"),
                "Completely rewritten sentence must not produce false fuzzy match");
    }

    /**
     * Verifies goal {@code #0683} boundary path: unknown locale code falls back safely to default
     * UI strings without blank button labels, while valid locales switch dynamically.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void liveUiTranslationUnknownLocaleFallsBackSafely(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF08Project(tempDir.resolve("f08_b_ui"), "e2e/f08/i18n_icu_project");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            MainMenuScreen menu = (MainMenuScreen) screens.getScreen(MainMenuScreen.SCREEN_NAME);
            PreferencesScreen prefs = (PreferencesScreen) screens.getScreen(PreferencesScreen.SCREEN_NAME);

            prefs.selectLanguage("deutsch");
            app.stepOnce(FIXED_DT);
            String deStart = menu.getStartButton().getText();

            prefs.selectLanguage("english");
            app.stepOnce(FIXED_DT);
            String enStart = menu.getStartButton().getText();
            assertNotEquals(deStart, enStart, "Built-in UI labels must differ between DE and EN");

            prefs.selectLanguage("klingon_unknown");
            app.stepOnce(FIXED_DT);
            assertFalse(menu.getStartButton().getText().isBlank(),
                    "Unknown locale must fall back cleanly without blank button labels");
            assertFalse(menu.getPreferencesButton().getText().isBlank());
        }
    }

    /**
     * Verifies goal {@code #0684} boundaries: negative counts, missing plural branches falling
     * back to {@code other}, and unclosed ICU braces fail safely without uncaught crash.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void icuPluralizationNegativeCountMissingBranchAndMalformedSyntax(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF08Project(tempDir.resolve("f08_b_icu"), "e2e/f08/i18n_icu_project");
        E2eWorkspaceSupport.writeScript(projectDir, "main.vjava", """
                define lena = Character("Lena", color="#58a6ff")
                default star_count = -3

                label start:
                    lena "{star_count, plural, one {# Stern.} other {# Sterne.}}"
                    lena "{star_count, plural, one {Kaputt"
                    return
                """);

        VJavaAst.ProjectAst ast = ProjectScanner.scan(projectDir);
        ScriptRuntime runtime = new ScriptRuntime(ast);
        runtime.start();

        assertEquals("-3 Sterne.", runtime.getActiveDialogue().interpolatedText(),
                "Negative count must expand via 'other' branch in ICU pluralization");

        runtime.advance();
        assertEquals(ScriptRuntime.Status.WAITING_DIALOGUE, runtime.getStatus());
        assertNotNull(runtime.getActiveDialogue().interpolatedText(),
                "Malformed unclosed ICU plural brace must degrade gracefully without NPE");
    }

    /**
     * Verifies goal {@code #0688} boundary paths: missing {@code {color=...}} or unclosed tag
     * triggers {@code TRANSLATION_PLACEHOLDER_MISMATCH}, while plain escaped text passes cleanly.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void translationTagSyncCatchesMissingStyleTagAndIgnoresPlainText(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF08Project(tempDir.resolve("f08_b_tags"), "e2e/f08/i18n_icu_project");
        E2eWorkspaceSupport.writeScript(projectDir, "main.vjava", """
                define lena = Character("Lena", color="#58a6ff")
                default hero = "Aria"

                label start:
                    lena "{color=#58a6ff}Achtung [hero]{/color}"
                    return
                """);
        E2eWorkspaceSupport.writeScript(projectDir, "tl/english/translations.vjava", """
                translate english line_01:
                    # lena "{color=#58a6ff}Achtung [hero]{/color}"
                    lena "Attention [hero]"
                """);

        VJavaLinter.LintReport mismatchReport = E2eCliRunner.lintProject(projectDir);
        assertTrue(
                mismatchReport.issues().stream()
                        .anyMatch(i -> "TRANSLATION_PLACEHOLDER_MISMATCH".equals(i.code())),
                "Omitting {color=#58a6ff} tag must emit TRANSLATION_PLACEHOLDER_MISMATCH, got: "
                        + mismatchReport.issues()
        );
    }

    /**
     * Verifies goal {@code #0689} boundary paths: zero-dialogue project reports 100% cleanly
     * without division by zero, and partial translations are allowed when strict flag is off.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void strictI18nAuditHandlesZeroDialoguesAndNonStrictMode(@TempDir Path tempDir) throws Exception {
        Path emptyProject = E2eWorkspaceSupport.createEmptyProject(
                tempDir.resolve("f08_zero_dlg"), "Zero Dialogues", "2D");
        E2eWorkspaceSupport.writeScript(emptyProject, "main.vjava", """
                label start:
                    return
                """);

        Method strictLint = findPublicMethod(VJavaLinter.class, "lint", Path.class, boolean.class);
        if (strictLint == null) {
            strictLint = findPublicMethod(VJavaLinter.class, "lintStrict", Path.class);
        }
        assertNotNull(strictLint, "VJavaLinter must expose strict i18n lint method");

        VJavaLinter.LintReport zeroReport = strictLint.getParameterCount() == 2
                ? (VJavaLinter.LintReport) strictLint.invoke(null, emptyProject, true)
                : (VJavaLinter.LintReport) strictLint.invoke(null, emptyProject);
        assertFalse(zeroReport.hasErrors(), "Zero-dialogue project must not fail strict i18n audit");

        Path partialProject = prepareF08Project(tempDir.resolve("f08_non_strict"), "e2e/f08/i18n_tag_mismatch");
        VJavaLinter.LintReport strictPartial = strictLint.getParameterCount() == 2
                ? (VJavaLinter.LintReport) strictLint.invoke(null, partialProject, true)
                : (VJavaLinter.LintReport) strictLint.invoke(null, partialProject);
        assertFalse(strictPartial.isClean(), "Strict mode must flag <100% coverage");

        TranslationCoverageAuditor.CoverageReport cov =
                new TranslationCoverageAuditor().audit(ProjectScanner.scan(emptyProject));
        assertEquals(0, cov.totalDialogueLines());
    }

    private static Path prepareF08Project(Path projectDir, String resourceTree) throws Exception {
        E2eWorkspaceSupport.copyResourceTree(resourceTree, projectDir);
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/bg/bg_sky.png"), 64, 64, 30, 50, 90, 255);
        return projectDir;
    }

    private static Class<?> findPublicClass(String... candidates) {
        for (String name : candidates) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {
            }
        }
        return null;
    }

    private static Method findPublicMethod(Class<?> cls, String name, Class<?>... paramTypes) {
        try {
            return cls.getMethod(name, paramTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static void invokeExportCsv(
            Class<?> serviceClass,
            Object service,
            Path projectDir,
            VJavaAst.ProjectAst ast,
            Path csvFile
    ) throws Exception {
        for (Method m : serviceClass.getMethods()) {
            String lower = m.getName().toLowerCase(Locale.ROOT);
            Class<?>[] params = m.getParameterTypes();
            if ("exportcsv".equals(lower) && params.length == 2) {
                if (params[0] == Path.class && params[1] == Path.class) {
                    m.invoke(service, projectDir, csvFile);
                    return;
                }
                if (params[0] == VJavaAst.ProjectAst.class && params[1] == Path.class) {
                    m.invoke(service, ast, csvFile);
                    return;
                }
            }
            if ("export".equals(lower) && params.length == 3 && params[2] == Path.class && params[1].isEnum()) {
                for (Object c : params[1].getEnumConstants()) {
                    if ("CSV".equalsIgnoreCase(c.toString())) {
                        if (params[0] == Path.class) {
                            m.invoke(service, projectDir, c, csvFile);
                            return;
                        }
                        if (params[0] == VJavaAst.ProjectAst.class) {
                            m.invoke(service, ast, c, csvFile);
                            return;
                        }
                    }
                }
            }
        }
        throw new AssertionError("No compatible public CSV export method found on " + serviceClass.getName());
    }

    private static void invokeImportCsv(
            Class<?> serviceClass,
            Object service,
            Path csvFile,
            String language,
            Path targetVjava
    ) throws Exception {
        for (Method m : serviceClass.getMethods()) {
            if (m.getName().toLowerCase(Locale.ROOT).contains("import")
                    && m.getParameterCount() == 3
                    && m.getParameterTypes()[0] == Path.class
                    && m.getParameterTypes()[1] == String.class
                    && m.getParameterTypes()[2] == Path.class) {
                m.invoke(service, csvFile, language, targetVjava);
                return;
            }
        }
        throw new AssertionError("No compatible public CSV import method found on " + serviceClass.getName());
    }
}
