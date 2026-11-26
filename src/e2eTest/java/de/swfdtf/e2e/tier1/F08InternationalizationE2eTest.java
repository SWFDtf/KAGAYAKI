package de.swfdtf.e2e.tier1;

import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineMode;
import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2eFixtureFactory;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.e2e.support.E2eWorkspaceSupport;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.state.LocalizationManager;
import de.swfdtf.state.TranslationEntry;
import de.swfdtf.tools.TranslationCoverageAuditor;
import de.swfdtf.tools.VJavaLinter;
import de.swfdtf.ui.MainMenuScreen;
import de.swfdtf.ui.PreferencesScreen;
import de.swfdtf.ui.SaveLoadScreen;
import de.swfdtf.ui.ScreenManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 1 happy-path E2E tests for Feature Area F8: PO/CSV/XLIFF export &amp; import,
 * fuzzy translation memory, live 5-language built-in UI translation, ICU pluralization &amp;
 * gender rules, placeholder/tag sync lint, and strict i18n coverage auditing
 * ({@code #0681}–{@code #0684}, {@code #0688}, {@code #0689}).
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER1)
public final class F08InternationalizationE2eTest {

    private static final double FIXED_DT = 1.0 / 60.0;

    /** Creates a new {@code F08InternationalizationE2eTest} instance. */
    public F08InternationalizationE2eTest() {
    }

    /**
     * Verifies baseline {@link LocalizationManager} live language switching, AST-free
     * {@link LocalizationManager.TranslationBlock} indexing, {@code tl/<lang>/} localized asset
     * resolution, and {@link TranslationCoverageAuditor}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void localizationManagerLiveSwitchAssetOverrideAndAuditorBaseline(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF08Project(tempDir.resolve("f08_base"), "e2e/f08/i18n_icu_project");
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("tl/english/assets/images/bg/bg_sky.png"), 32, 32, 200, 220, 255, 255);

        LocalizationManager loc = new LocalizationManager("deutsch");
        loc.indexTranslationBlocks("deutsch", List.of(
                new LocalizationManager.TranslationBlock("english", "greet_01", List.of(
                        new TranslationEntry("greet_01", "Hallo Welt", "Hello World", "lena", 1)
                )),
                new LocalizationManager.TranslationBlock("english", "strings", List.of(
                        new TranslationEntry("ui.start", "Starten", "Start", "ui", 2)
                ))
        ));

        assertEquals("Hallo Welt", loc.translateDialogue("greet_01", "Hallo Welt"));
        assertEquals("assets/images/bg/bg_sky.png",
                loc.resolveLocalizedAsset(projectDir, "assets/images/bg/bg_sky.png"));

        loc.setLanguage("english");
        assertEquals("Hello World", loc.translateDialogue("greet_01", "Hallo Welt"));
        assertEquals("Start", loc.translateUi("ui.start", "Starten"));
        assertEquals("tl/english/assets/images/bg/bg_sky.png",
                loc.resolveLocalizedAsset(projectDir, "assets/images/bg/bg_sky.png"));

        VJavaAst.ProjectAst ast = ProjectScanner.scan(projectDir);
        TranslationCoverageAuditor.CoverageReport report = new TranslationCoverageAuditor().audit(ast);
        assertEquals(3, report.totalDialogueLines());
        assertEquals(100.0, report.findLanguage("english").orElseThrow().coveragePercent(), 0.01);
        assertEquals(100.0, report.findLanguage("polish").orElseThrow().coveragePercent(), 0.01);
        assertEquals(100.0, report.findLanguage("japanese").orElseThrow().coveragePercent(), 0.01);
    }

    /**
     * Verifies goal {@code #0681}: {@code TranslationExportService} exports project dialogues
     * and UI strings to PO, CSV, and XLIFF, and re-importing a translated CSV creates a valid
     * {@code tl/<lang>/translations.vjava} passing {@link VJavaLinter}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or export/import fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void translationExportServiceRoundTripsPoCsvAndXliff(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF08Project(tempDir.resolve("f08_export"), "e2e/f08/i18n_icu_project");
        Class<?> serviceClass = findPublicClass(
                "de.swfdtf.state.TranslationExportService",
                "de.swfdtf.tools.TranslationExportService"
        );
        assertNotNull(serviceClass, "TranslationExportService must exist in de.swfdtf.state or de.swfdtf.tools");

        Object service = serviceClass.getConstructor().newInstance();
        VJavaAst.ProjectAst ast = ProjectScanner.scan(projectDir);
        Path outDir = tempDir.resolve("exported");
        Files.createDirectories(outDir);

        Path poFile = outDir.resolve("messages.po");
        Path csvFile = outDir.resolve("messages.csv");
        Path xliffFile = outDir.resolve("messages.xliff");

        invokeExport(serviceClass, service, projectDir, ast, "PO", poFile);
        invokeExport(serviceClass, service, projectDir, ast, "CSV", csvFile);
        invokeExport(serviceClass, service, projectDir, ast, "XLIFF", xliffFile);

        assertTrue(Files.isRegularFile(poFile) && Files.readString(poFile).contains("msgid"));
        assertTrue(Files.isRegularFile(csvFile) && Files.readString(csvFile).contains("Willkommen"));
        assertTrue(Files.isRegularFile(xliffFile) && Files.readString(xliffFile).contains("xliff"));

        String csvUpdated = Files.readString(csvFile, StandardCharsets.UTF_8)
                .replace("\"\"", "\"Bonjour, [spieler_name]!\"");
        Files.writeString(csvFile, csvUpdated, StandardCharsets.UTF_8);

        Path targetVjava = projectDir.resolve("tl/french/translations.vjava");
        invokeImportCsv(serviceClass, service, csvFile, "french", targetVjava);
        assertTrue(Files.isRegularFile(targetVjava), "CSV re-import must generate tl/french/translations.vjava");

        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertFalse(lint.hasErrors(), "Imported translation .vjava must pass VJavaLinter: " + lint.issues());
    }

    /**
     * Verifies goal {@code #0682}: {@code TranslationMemory} preserves exact 100% matches for
     * unchanged lines and flags minor source edits with {@code # fuzzy}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void translationMemoryPreservesExactNeighborsAndFlagsFuzzyEdit(@TempDir Path tempDir) throws Exception {
        Class<?> tmClass = findPublicClass(
                "de.swfdtf.state.TranslationMemory",
                "de.swfdtf.tools.TranslationMemory"
        );
        assertNotNull(tmClass, "TranslationMemory must exist in de.swfdtf.state or de.swfdtf.tools");

        Object memory = tmClass.getConstructor().newInstance();
        String oldLine1 = "Hallo Welt im ersten Kapitel.";
        String oldLine2 = "Der Spiegel spricht die Wahrheit.";
        String editedLine1 = "Hallo Welt, im ersten Kapitel.";

        Method learnMethod = findPublicMethod(tmClass, "learn", String.class, String.class);
        if (learnMethod == null) {
            learnMethod = findPublicMethod(tmClass, "add", String.class, String.class);
        }
        if (learnMethod == null) {
            learnMethod = findPublicMethod(tmClass, "put", String.class, String.class);
        }
        assertNotNull(learnMethod, "TranslationMemory must expose public learn/add/put(source, translation)");
        learnMethod.invoke(memory, oldLine1, "Hello world in the first chapter.");
        learnMethod.invoke(memory, oldLine2, "The mirror speaks the truth.");

        Path outVjava = tempDir.resolve("migrated_translations.vjava");
        Method matchOrMigrate = findPublicMethod(tmClass, "match", String.class);
        if (matchOrMigrate == null) {
            matchOrMigrate = findPublicMethod(tmClass, "lookup", String.class);
        }
        assertNotNull(matchOrMigrate, "TranslationMemory must expose public match/lookup(source)");

        Object exactRes = matchOrMigrate.invoke(memory, oldLine2);
        Object fuzzyRes = matchOrMigrate.invoke(memory, editedLine1);
        assertNotNull(exactRes);
        assertNotNull(fuzzyRes);
        assertTrue(exactRes.toString().contains("The mirror speaks the truth."),
                "Unchanged neighbor line must keep 100% translation match");
        assertTrue(fuzzyRes.toString().toLowerCase(Locale.ROOT).contains("fuzzy")
                        || fuzzyRes.toString().contains("Hello world in the first chapter."),
                "Minor edit must yield fuzzy match referencing prior translation, got: " + fuzzyRes);
        assertFalse(Files.exists(outVjava));
    }

    /**
     * Verifies goal {@code #0683}: switching language in {@link PreferencesScreen} immediately
     * translates built-in UI controls across {@code DE}, {@code EN}, {@code FR}, {@code ES},
     * and {@code JA}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void builtInUiScreensTranslateLiveAcrossFiveLanguages(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF08Project(tempDir.resolve("f08_ui_i18n"), "e2e/f08/i18n_icu_project");

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                projectDir, EngineMode.STANDALONE_GAME, false)) {
            app.initialize();
            ScreenManager screens = app.getApi().getService(ScreenManager.class);
            MainMenuScreen menu = (MainMenuScreen) screens.getScreen(MainMenuScreen.SCREEN_NAME);
            PreferencesScreen prefs = (PreferencesScreen) screens.getScreen(PreferencesScreen.SCREEN_NAME);
            SaveLoadScreen saveLoad = (SaveLoadScreen) screens.getScreen(SaveLoadScreen.SCREEN_NAME);
            assertNotNull(menu);
            assertNotNull(prefs);
            assertNotNull(saveLoad);

            Set<String> startLabels = new HashSet<>();
            Set<String> prefsLabels = new HashSet<>();
            for (String lang : List.of("deutsch", "english", "french", "spanish", "japanese")) {
                prefs.selectLanguage(lang);
                app.stepOnce(FIXED_DT);

                String startText = menu.getStartButton().getText();
                String prefsText = menu.getPreferencesButton().getText();
                String loadText = menu.getLoadButton().getText();
                assertFalse(startText.isBlank(), "Start label must not be blank for " + lang);
                assertFalse(prefsText.isBlank(), "Preferences label must not be blank for " + lang);
                assertFalse(loadText.isBlank(), "Load label must not be blank for " + lang);
                startLabels.add(startText);
                prefsLabels.add(prefsText);
            }

            assertEquals(5, startLabels.size(),
                    "Expected distinct localized Start labels across DE/EN/FR/ES/JA, got: " + startLabels);
            assertEquals(5, prefsLabels.size(),
                    "Expected distinct localized Preferences labels across DE/EN/FR/ES/JA, got: " + prefsLabels);
        }
    }

    /**
     * Verifies goal {@code #0684}: ICU pluralization and gender rules expand accurately across
     * {@code de}, {@code en}, {@code pl}, and {@code ja} with at least 4 count cases each.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void icuPluralizationAndGenderRulesAcrossDeEnPlJa(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF08Project(tempDir.resolve("f08_icu"), "e2e/f08/i18n_icu_project");
        VJavaAst.ProjectAst ast = ProjectScanner.scan(projectDir);

        // Polish: 1 -> one (gwiazda), 2 -> few (gwiazdy), 5 -> many (gwiazd), 22 -> few (gwiazdy)
        assertEquals("1 gwiazda znaleziona.", runPluralLine(ast, "polish", 1));
        assertEquals("2 gwiazdy znalezione.", runPluralLine(ast, "polish", 2));
        assertEquals("5 gwiazd znalezionych.", runPluralLine(ast, "polish", 5));
        assertEquals("22 gwiazdy znalezione.", runPluralLine(ast, "polish", 22));

        // German: 0, 1, 2, 5
        assertEquals("0 Sterne gefunden.", runPluralLine(ast, "deutsch", 0));
        assertEquals("1 Stern gefunden.", runPluralLine(ast, "deutsch", 1));
        assertEquals("2 Sterne gefunden.", runPluralLine(ast, "deutsch", 2));
        assertEquals("5 Sterne gefunden.", runPluralLine(ast, "deutsch", 5));

        // English: 0, 1, 2, 5
        assertEquals("0 stars found.", runPluralLine(ast, "english", 0));
        assertEquals("1 star found.", runPluralLine(ast, "english", 1));
        assertEquals("2 stars found.", runPluralLine(ast, "english", 2));
        assertEquals("5 stars found.", runPluralLine(ast, "english", 5));

        // Japanese: 0, 1, 2, 5 -> other
        assertEquals("星を0個見つけました。", runPluralLine(ast, "japanese", 0));
        assertEquals("星を1個見つけました。", runPluralLine(ast, "japanese", 1));
        assertEquals("星を2個見つけました。", runPluralLine(ast, "japanese", 2));
        assertEquals("星を5個見つけました。", runPluralLine(ast, "japanese", 5));

        // Gender select verification
        assertEquals("She is ready.", runGenderLine(ast, "english", "female"));
        assertEquals("He is ready.", runGenderLine(ast, "english", "male"));
    }

    /**
     * Verifies goal {@code #0688}: {@link VJavaLinter} detects omitted placeholders and style
     * tags in translation files and emits {@code TRANSLATION_PLACEHOLDER_MISMATCH} with file/line.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void linterDetectsTranslationPlaceholderAndTagMismatch(@TempDir Path tempDir) throws Exception {
        Path projectDir = prepareF08Project(tempDir.resolve("f08_mismatch"), "e2e/f08/i18n_tag_mismatch");

        VJavaLinter.LintReport report = E2eCliRunner.lintProject(projectDir);
        assertTrue(
                report.issues().stream().anyMatch(issue ->
                        "TRANSLATION_PLACEHOLDER_MISMATCH".equals(issue.code())
                                && issue.sourceFile().contains("translations.vjava")
                                && issue.lineNumber() > 0),
                "Expected TRANSLATION_PLACEHOLDER_MISMATCH diagnostic with source file & line, got: "
                        + report.issues()
        );
    }

    /**
     * Verifies goal {@code #0689}: {@link VJavaLinter} strict i18n mode flags incomplete
     * translation coverage below 100% while passing 100%-translated projects cleanly.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if fixture setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void linterStrictI18nAuditFlagsPartialTranslations(@TempDir Path tempDir) throws Exception {
        Path partialDir = prepareF08Project(tempDir.resolve("f08_partial"), "e2e/f08/i18n_tag_mismatch");
        Path completeDir = prepareF08Project(tempDir.resolve("f08_complete"), "e2e/f08/i18n_icu_project");

        VJavaLinter.LintReport strictPartial = invokeStrictLint(partialDir);
        assertNotNull(strictPartial,
                "VJavaLinter must expose strict i18n linting (e.g. lint(Path, boolean))");
        assertFalse(strictPartial.isClean(),
                "Strict i18n lint must emit warning/error for <100% translation coverage");
        assertTrue(
                strictPartial.issues().stream().anyMatch(i ->
                        i.message().toLowerCase(Locale.ROOT).contains("english")
                                || i.code().contains("TRANSLATION")
                                || i.code().contains("COVERAGE")),
                "Strict coverage diagnostic must identify target language/coverage, got: " + strictPartial.issues()
        );

        VJavaLinter.LintReport strictComplete = invokeStrictLint(completeDir);
        assertNotNull(strictComplete);
        assertFalse(strictComplete.hasErrors(),
                "100% translated project must pass strict i18n lint without errors: " + strictComplete.issues());
    }

    private static String runPluralLine(VJavaAst.ProjectAst ast, String language, int count) {
        ScriptRuntime runtime = new ScriptRuntime(ast);
        LocalizationManager loc = new LocalizationManager("deutsch");
        loc.indexTranslationBlocks("deutsch", toBlocks(ast));
        loc.setLanguage(language);
        runtime.setLocalizationManager(loc);
        runtime.getStateStore().set("star_count", count);
        runtime.start();
        runtime.advance();
        return runtime.getActiveDialogue().interpolatedText();
    }

    private static String runGenderLine(VJavaAst.ProjectAst ast, String language, String gender) {
        ScriptRuntime runtime = new ScriptRuntime(ast);
        LocalizationManager loc = new LocalizationManager("deutsch");
        loc.indexTranslationBlocks("deutsch", toBlocks(ast));
        loc.setLanguage(language);
        runtime.setLocalizationManager(loc);
        runtime.getStateStore().set("speaker_gender", gender);
        runtime.start();
        runtime.advance();
        runtime.advance();
        return runtime.getActiveDialogue().interpolatedText();
    }

    private static List<LocalizationManager.TranslationBlock> toBlocks(VJavaAst.ProjectAst ast) {
        return ast.translations().stream()
                .map(tb -> new LocalizationManager.TranslationBlock(
                        tb.language(),
                        tb.id(),
                        tb.entries().stream()
                                .map(e -> new TranslationEntry(
                                        tb.id(), e.originalText(), e.translatedText(), e.speaker(), tb.lineNumber()))
                                .toList()))
                .toList();
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

    private static void invokeExport(
            Class<?> serviceClass,
            Object service,
            Path projectDir,
            VJavaAst.ProjectAst ast,
            String format,
            Path outFile
    ) throws Exception {
        for (Method m : serviceClass.getMethods()) {
            String lower = m.getName().toLowerCase(Locale.ROOT);
            Class<?>[] params = m.getParameterTypes();
            if (lower.equals("export" + format.toLowerCase(Locale.ROOT)) && params.length == 2) {
                if (params[0] == Path.class && params[1] == Path.class) {
                    m.invoke(service, projectDir, outFile);
                    return;
                }
                if (params[0] == VJavaAst.ProjectAst.class && params[1] == Path.class) {
                    m.invoke(service, ast, outFile);
                    return;
                }
            }
            if ("export".equals(lower) && params.length == 3 && params[2] == Path.class) {
                if (params[0] == Path.class && params[1].isEnum()) {
                    Object enumVal = resolveEnum(params[1], format);
                    if (enumVal != null) {
                        m.invoke(service, projectDir, enumVal, outFile);
                        return;
                    }
                }
                if (params[0] == VJavaAst.ProjectAst.class && params[1].isEnum()) {
                    Object enumVal = resolveEnum(params[1], format);
                    if (enumVal != null) {
                        m.invoke(service, ast, enumVal, outFile);
                        return;
                    }
                }
            }
        }
        throw new AssertionError("No compatible public export method found on " + serviceClass.getName());
    }

    private static void invokeImportCsv(
            Class<?> serviceClass,
            Object service,
            Path csvFile,
            String language,
            Path targetVjava
    ) throws Exception {
        for (Method m : serviceClass.getMethods()) {
            String lower = m.getName().toLowerCase(Locale.ROOT);
            Class<?>[] params = m.getParameterTypes();
            if (lower.contains("import") && params.length == 3
                    && params[0] == Path.class && params[1] == String.class && params[2] == Path.class) {
                m.invoke(service, csvFile, language, targetVjava);
                return;
            }
        }
        throw new AssertionError("No compatible public CSV import method found on " + serviceClass.getName());
    }

    private static Object resolveEnum(Class<?> enumType, String name) {
        for (Object c : enumType.getEnumConstants()) {
            if (c.toString().equalsIgnoreCase(name)) {
                return c;
            }
        }
        return null;
    }

    private static VJavaLinter.LintReport invokeStrictLint(Path projectDir) throws Exception {
        Method strictBool = findPublicMethod(VJavaLinter.class, "lint", Path.class, boolean.class);
        if (strictBool != null) {
            return (VJavaLinter.LintReport) strictBool.invoke(null, projectDir, true);
        }
        Method strictAlt = findPublicMethod(VJavaLinter.class, "lintStrict", Path.class);
        if (strictAlt != null) {
            return (VJavaLinter.LintReport) strictAlt.invoke(null, projectDir);
        }
        return null;
    }
}
