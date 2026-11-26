package de.swfdtf.tools;

import de.swfdtf.audio.AudioChannel;
import de.swfdtf.audio.AudioEngine;
import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineEvent;
import de.swfdtf.core.EngineEventBus;
import de.swfdtf.core.EngineLogger;
import de.swfdtf.core.EnginePlugin;
import de.swfdtf.core.EngineVersion;
import de.swfdtf.core.EventSubscription;
import de.swfdtf.core.InputManager;
import de.swfdtf.core.PluginRegistry;
import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.core.StudioBridge;
import de.swfdtf.graphics.ColorBlindnessFilter;
import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderMode;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.ConditionEvaluator;
import de.swfdtf.parser.HotReloadWatcher;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptDebugger;
import de.swfdtf.parser.ScriptProfiler;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.parser.VariableWatchpointManager;
import de.swfdtf.parser.WatchpointEvent;
import de.swfdtf.state.HistoryLog;
import de.swfdtf.state.LocalizationManager;
import de.swfdtf.state.SaveSlotData;
import de.swfdtf.ui.Frame;
import de.swfdtf.ui.HBox;
import de.swfdtf.ui.Screen;
import de.swfdtf.ui.ScreenManager;
import de.swfdtf.ui.TextButton;
import de.swfdtf.ui.TextInput;
import de.swfdtf.ui.TextWidget;
import de.swfdtf.ui.ThemePalette;
import de.swfdtf.ui.UiNode;
import de.swfdtf.ui.VBox;
import de.swfdtf.ui.Viewport;
import de.swfdtf.ui.WindowBox;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Dual-Window Studio controller rendering Window A ("KAGAYAKI Studio") side-by-side
 * with Window B (Live Game Preview).
 *
 * <p>Provides Project Explorer, Starter Template Generator, Asset Scanner, Live Lint Checker,
 * Variable/State Inspector, Live Console, RenderMode (2D / 3D Stub) switcher, Live Reload,
 * and Standalone Distribution Exporter.</p>
 */
public final class StudioWindowController implements StudioBridge {

    /** Active top-level Studio view in Window A. */
    public enum StudioView {
        /** Project Hub / Main Menu showing engine &amp; project versions, New Project, and Recent Projects. */
        MAIN_MENU,
        /** Full 3-column Studio IDE workspace with Explorer, Linter, Live Variables, and Console. */
        WORKSPACE
    }

    /** Screen name of the Studio Main Menu / Project Hub. */
    public static final String MAIN_MENU_SCREEN_NAME = "studio_main_menu";

    /** Screen name of the 3-column Studio Workspace. */
    public static final String WORKSPACE_SCREEN_NAME = "studio_workspace";

    /** Modal screen name for the interactive New Project creation dialog. */
    public static final String NEW_PROJECT_DIALOG_NAME = "studio_new_project_dialog";

    /** Modal screen name for the {@code Ctrl+Shift+P} interactive Command Palette. */
    public static final String COMMAND_PALETTE_SCREEN_NAME = "studio_command_palette";

    /** Modal screen name for the {@code F1} keyboard shortcut cheat-sheet overlay ({@code #0120}). */
    public static final String SHORTCUT_OVERLAY_SCREEN_NAME = "studio_shortcut_overlay";

    private EngineAPI api;
    private long studioWindowHandle;
    private long gameWindowHandle;
    private boolean glReady = false;

    private RenderPipeline2D studioPipeline;
    private InputManager studioInput;
    private FontManager fontManager;
    private TextureManager textureManager;
    private ScreenManager studioScreenManager;
    private Screen studioMainMenuScreen;
    private Screen studioRootScreen;
    private Screen newProjectDialogScreen;
    private Screen commandPaletteScreen;
    private Screen shortcutOverlayScreen;

    private Path activeProjectRoot;
    private StudioView activeView = StudioView.MAIN_MENU;
    private ProjectTemplateGenerator.TemplateType selectedTemplateType =
            ProjectTemplateGenerator.TemplateType.FULL_DEMO;
    private final StudioWorkspaceState workspaceState = StudioWorkspaceState.defaults();
    private final VJavaSyntaxHighlighter syntaxHighlighter = new VJavaSyntaxHighlighter();
    private final VariableWatchpointManager watchpointManager = new VariableWatchpointManager();
    private final ScriptDebugger scriptDebugger = new ScriptDebugger();
    private final StudioCommandPalette commandPalette = new StudioCommandPalette();
    private final AssetMetadataInspector assetMetadataInspector = new AssetMetadataInspector();
    private final UnusedAssetDetector unusedAssetDetector = new UnusedAssetDetector();
    private final ScriptFlowGraphExporter flowGraphExporter = new ScriptFlowGraphExporter();
    private final ProjectSearchService searchService = new ProjectSearchService();
    private final LabelBookmarkManager bookmarkManager = new LabelBookmarkManager();
    private final ExportPreFlightChecker preFlightChecker = new ExportPreFlightChecker();
    private final DiagnosticBundleExporter diagnosticBundleExporter = new DiagnosticBundleExporter();
    private final ScriptWordCountAnalyzer wordCountAnalyzer = new ScriptWordCountAnalyzer();
    private final AssetChecksumVerifier checksumVerifier = new AssetChecksumVerifier();
    private final VJavaAutocompleteEngine autocompleteEngine = new VJavaAutocompleteEngine();
    private final ProjectRefactorService refactorService = new ProjectRefactorService();
    private final StudioAuthoringToolkit authoringToolkit = new StudioAuthoringToolkit();
    private final StudioReportAndQaService reportAndQaService = new StudioReportAndQaService();
    private final Deque<List<String>> trashedAssetBatches = new ArrayDeque<>();

    // Wave P2-2 Studio state, filters, metrics & overlays (#0101-#0168)
    private ThemePalette.ThemeVariant themeVariant = ThemePalette.ThemeVariant.DARK_SLATE;
    private EngineLogger.Level consoleMinLevel = EngineLogger.Level.DEBUG;
    private String consoleCategoryFilter = "";
    private String explorerFilterQuery = "";
    private boolean autoReloadEnabled = false;
    private HotReloadWatcher hotReloadWatcher;
    private AssetChecksumVerifier.AssetManifest baselineAssetManifest;
    private boolean shortcutOverlayVisible = false;
    private boolean onboardingTourVisible = false;
    private boolean keybindingTestOverlayVisible = false;
    private float previewSpeedMultiplier = 1.0f;
    private ColorBlindnessFilter.AccessibilityMode accessibilityPreviewMode =
            ColorBlindnessFilter.AccessibilityMode.NONE;
    private boolean dyslexiaFontPreview = false;
    private String lastToastMessage = "";
    private String liveTelemetryText = "0 FPS | 0 Textures | 0 KB";
    private String gitStatusBadge = "no-git";
    private ScriptSession lastGoodSession;
    private final List<String> capturedBusEvents = new CopyOnWriteArrayList<>();
    private final List<String> recordedTestKeys = new ArrayList<>();
    private EventSubscription eventBusSnifferSub;
    private final List<WindowBox> themedWindowBoxes = new ArrayList<>();
    private final List<TextButton> themedButtons = new ArrayList<>();
    private final List<TextButton> mainMenuFocusButtons = new ArrayList<>();
    private final List<TextButton> toolbarFocusButtons = new ArrayList<>();
    private int mainMenuFocusIndex = -1;
    private int toolbarFocusIndex = -1;
    private WindowBox explorerWindowBox;
    private WindowBox lintWindowBox;
    private WindowBox varsWindowBox;
    private WindowBox consoleWindowBox;

    private VJavaAst.ProjectAst lastScannedAst;
    private VJavaLinter.LintReport lastLintReport;
    private StandaloneBuilder.BuildResult lastExportResult;

    private TextWidget headerStatusWidget;
    private TextWidget explorerListWidget;
    private TextWidget lintSummaryWidget;
    private TextWidget variablesListWidget;
    private TextWidget consoleLogWidget;
    private TextButton renderModeButton;

    // Embedded Editor, Explorer & Asset Gallery (#0089, #0090, #0098, #0099)
    private TextInput codeEditorInput;
    private TextWidget editorStatusWidget;
    private Path activeEditorFile;
    private int activeEditorLine = 1;
    private VBox explorerFilesBox;
    private VBox explorerAssetsBox;
    private TextWidget assetPreviewWidget;

    // Linter Auto-Fix, Click-to-Line & Flow Graph (#0091, #0092, #0093)
    private VBox lintIssuesBox;
    private VBox flowGraphBox;
    private final List<TextButton> flowNodeButtons = new ArrayList<>();
    private boolean flowGraphVisible = false;

    // Debugger, Watchpoints, Live State Variables & Live Director Snippet (#0094-#0097)
    private VBox variableEditorsBox;
    private final Map<String, TextInput> variableInputs = new LinkedHashMap<>();
    private final Map<String, String> lastCommittedVariableTexts = new LinkedHashMap<>();
    private TextWidget watchpointStatusWidget;
    private TextWidget debuggerStatusWidget;
    private TextWidget directorSnippetWidget;

    // New Project Dialog (#0088) & Command Palette (#0100)
    private TextInput newProjectTitleInput;
    private TextInput newProjectPathInput;
    private TextInput newProjectAuthorInput;
    private TextButton newProjectRenderModeBtn;
    private TextWidget newProjectErrorWidget;
    private String newProjectRenderMode = "2D";

    private TextInput paletteSearchInput;
    private TextWidget paletteMatchesWidget;

    private TextWidget mainMenuVersionWidget;
    private TextWidget mainMenuActiveProjectWidget;
    private TextButton templateToggleButton;
    private TextWidget mainMenuRecentWidget;
    private TextWidget mainMenuStatusWidget;

    private double elapsedSeconds = 0.0;
    private double refreshTimer = 0.0;
    private boolean disposed = false;

    /** Creates a new {@code StudioWindowController} instance. */
    public StudioWindowController() {
    }

    @Override
    public void init(EngineAPI api, long studioWindowHandle, long gameWindowHandle) {
        this.api = Objects.requireNonNull(api, "api must not be null");
        this.studioWindowHandle = studioWindowHandle;
        this.gameWindowHandle = gameWindowHandle;
        this.glReady = !api.getConfig().headless() && studioWindowHandle != 0L;

        Path projectRoot = api.getConfig().projectDir().toAbsolutePath().normalize();
        this.activeProjectRoot = projectRoot;
        if (Files.isDirectory(projectRoot)) {
            workspaceState.recordOpenedProject(projectRoot);
        }
        this.fontManager = new FontManager(projectRoot.resolve("assets/fonts"), true);
        this.textureManager = new TextureManager(api.getLogger(), projectRoot);
        this.studioScreenManager = new ScreenManager();
        this.studioScreenManager.setLogicalSize(1920.0f, 1080.0f);
        this.studioInput = new InputManager();
        this.studioInput.setLogicalSize(1920, 1080);

        if (glReady) {
            this.studioPipeline = new RenderPipeline2D();
            this.studioPipeline.init(1920, 1080);
            this.studioInput.attachToWindow(studioWindowHandle);
        }

        buildMainMenuUi();
        buildStudioUi();
        buildNewProjectDialogUi();
        buildCommandPaletteUi();
        buildShortcutOverlayUi();
        registerPaletteCommands();
        subscribeEventBusSniffer();
        this.onboardingTourVisible = !workspaceState.isOnboardingTourCompleted();
        applyLayoutPreset(workspaceState.getLayoutPreset());
        refreshAssetManifestBaseline();
        refreshGitStatusBadge();
        api.registerService(VariableWatchpointManager.class, watchpointManager);
        api.registerService(ScriptDebugger.class, scriptDebugger);
        api.registerService(StudioCommandPalette.class, commandPalette);
        api.registerService(StudioWindowController.class, this);

        showMainMenu();
        refreshProjectAndLint();
        refreshInspectors();

        api.getLogger().info("Studio", "StudioWindowController initialized (" + EngineVersion.formatBanner() + ").");
    }

    private void buildMainMenuUi() {
        studioMainMenuScreen = new Screen(MAIN_MENU_SCREEN_NAME, SceneLayer.Z_SCREENS, false);
        VBox hubLayout = new VBox();
        hubLayout.setSpacing(18.0f)
                .setPadding(36.0f)
                .setBackgroundColorHex(ThemePalette.CANVAS_BG);

        // Header banner with Engine Version & Active Project Version
        VBox headerCard = new VBox();
        headerCard.setSpacing(8.0f).setPadding(24.0f)
                .setBackgroundColorHex(ThemePalette.PANEL_SURFACE)
                .setBorderColorHex(ThemePalette.BORDER).setBorderThickness(1.0f).setCornerRadius(10.0f);

        mainMenuVersionWidget = new TextWidget(EngineVersion.formatBanner() + " — Studio Hauptmenü");
        mainMenuVersionWidget.setFontSize(34.0f).setColorHex(ThemePalette.TEXT_PRIMARY);
        mainMenuActiveProjectWidget = new TextWidget("Aktives Projekt: wird geladen...");
        mainMenuActiveProjectWidget.setFontSize(20.0f).setColorHex(ThemePalette.TEXT_MUTED);
        headerCard.addChild(mainMenuVersionWidget);
        headerCard.addChild(mainMenuActiveProjectWidget);

        // Action row
        HBox actionRow = new HBox();
        actionRow.setSpacing(14.0f).setPreferredHeight(64.0f);

        TextButton newProjectBtn = new TextButton("Neues Projekt", this::onNewProjectClicked);
        newProjectBtn.setPreferredSize(230.0f, 52.0f);

        templateToggleButton = new TextButton(formatTemplateButtonText(), this::toggleTemplateType);
        templateToggleButton.setPreferredSize(470.0f, 52.0f);

        TextButton openWorkspaceBtn = new TextButton("Workspace öffnen", this::showWorkspace);
        openWorkspaceBtn.setPreferredSize(230.0f, 52.0f);

        TextButton openDemoBtn = new TextButton("Demo laden", this::loadBundledDemoProject);
        openDemoBtn.setPreferredSize(200.0f, 52.0f);

        actionRow.addChild(newProjectBtn);
        actionRow.addChild(templateToggleButton);
        actionRow.addChild(openWorkspaceBtn);
        actionRow.addChild(openDemoBtn);
        mainMenuFocusButtons.clear();
        mainMenuFocusButtons.add(newProjectBtn);
        mainMenuFocusButtons.add(templateToggleButton);
        mainMenuFocusButtons.add(openWorkspaceBtn);
        mainMenuFocusButtons.add(openDemoBtn);
        themedButtons.addAll(mainMenuFocusButtons);

        // Overview columns: Recent Projects + Template Features
        HBox hubColumns = new HBox();
        hubColumns.setSpacing(18.0f).setPreferredHeight(560.0f);

        WindowBox recentBox = new WindowBox("Zuletzt geöffnete Projekte");
        recentBox.setPreferredSize(900.0f, 560.0f);
        themedWindowBoxes.add(recentBox);
        Viewport recentVp = new Viewport();
        mainMenuRecentWidget = new TextWidget("(noch keine Projekte)");
        mainMenuRecentWidget.setFontSize(18.0f).setColorHex(ThemePalette.TEXT_PRIMARY);
        recentVp.addChild(mainMenuRecentWidget);
        recentBox.addChild(recentVp);

        WindowBox templateBox = new WindowBox("Vorlage & Engine-Funktionen (" + EngineVersion.VERSION + ")");
        templateBox.setPreferredSize(920.0f, 560.0f);
        themedWindowBoxes.add(templateBox);
        Viewport templateVp = new Viewport();
        mainMenuStatusWidget = new TextWidget(
                "• Vollständige Demo-Vorlage: Main Menu + Version, Save/Load (6 Slots + QuickSave),\n"
                        + "  Einstellungen, Story mit Verzweigung, DE/EN-Übersetzung & WASD-/Physik-Arena.\n"
                        + "• Minimal-Vorlage: Schlankes Grundgerüst mit Start-Label und HUD-Badge.\n"
                        + "• Studio IDE: 0-Fehler-VJavaLinter, Live Reload (Shift+R) & .vpak-Standalone-Export."
        );
        mainMenuStatusWidget.setFontSize(18.0f).setColorHex(ThemePalette.TEXT_PRIMARY);
        templateVp.addChild(mainMenuStatusWidget);
        templateBox.addChild(templateVp);

        hubColumns.addChild(recentBox);
        hubColumns.addChild(templateBox);

        hubLayout.addChild(headerCard);
        hubLayout.addChild(actionRow);
        hubLayout.addChild(hubColumns);
        studioMainMenuScreen.setRoot(hubLayout);
        studioMainMenuScreen.addKeyBinding(
                GLFW.GLFW_KEY_P, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT, this::openCommandPalette);
        studioMainMenuScreen.addKeyBinding(GLFW.GLFW_KEY_F1, this::toggleShortcutOverlay);
        studioMainMenuScreen.addKeyBinding(GLFW.GLFW_KEY_TAB, this::focusNextButton);
        studioMainMenuScreen.addKeyBinding(GLFW.GLFW_KEY_TAB, GLFW.GLFW_MOD_SHIFT, this::focusPrevButton);
        studioMainMenuScreen.addKeyBinding(GLFW.GLFW_KEY_ENTER, this::activateFocusedButton);
        studioMainMenuScreen.addKeyBinding(GLFW.GLFW_KEY_ESCAPE, this::dismissOverlays);
        studioScreenManager.registerScreen(studioMainMenuScreen);
    }

    private void buildStudioUi() {
        studioRootScreen = new Screen(WORKSPACE_SCREEN_NAME, SceneLayer.Z_SCREENS, false);
        VBox rootLayout = new VBox();
        rootLayout.setSpacing(10.0f);
        rootLayout.setFillWidth(true);
        rootLayout.setPadding(16.0f);
        rootLayout.setBackgroundColorHex(ThemePalette.CANVAS_BG);

        // Top Toolbar (compact row fitting strictly within 1888px)
        HBox toolbar = new HBox();
        toolbar.setSpacing(8.0f);
        toolbar.setFillHeight(true);
        toolbar.setPreferredHeight(56.0f);
        toolbar.setPadding(8.0f).setBackgroundColorHex(ThemePalette.PANEL_SURFACE)
                .setBorderColorHex(ThemePalette.BORDER).setBorderThickness(1.0f).setCornerRadius(8.0f);

        headerStatusWidget = new TextWidget(EngineVersion.formatBanner());
        headerStatusWidget.setFontSize(17.0f).setColorHex(ThemePalette.TEXT_PRIMARY).setPreferredWidth(360.0f);

        TextButton mainMenuBtn = createToolbarButton("Hauptmenü", 118.0f, this::showMainMenu);
        TextButton newTemplateBtn = createToolbarButton("Neues Projekt", 136.0f, this::onNewProjectClicked);
        TextButton saveScriptBtn = createToolbarButton("Speichern (Ctrl+S)", 152.0f, this::saveActiveEditorFile);
        TextButton lintBtn = createToolbarButton("Run Lint", 96.0f, this::runLint);
        TextButton flowBtn = createToolbarButton("Flow-Graph", 116.0f, this::toggleFlowGraph);
        TextButton stepIntoBtn = createToolbarButton("Step Into", 104.0f, this::debugStepInto);
        TextButton stepOverBtn = createToolbarButton("Step Over", 104.0f, this::debugStepOver);
        TextButton continueBtn = createToolbarButton("Continue", 100.0f, this::debugContinue);
        TextButton reloadBtn = createToolbarButton("Reload", 88.0f, () -> api.requestLiveReload());
        renderModeButton = createToolbarButton("Mode: 2D", 106.0f, this::toggleRenderMode);
        TextButton exportBtn = createToolbarButton("Export", 88.0f, () -> {
            Path projectRoot = getActiveProjectRoot();
            exportStandalone(projectRoot.resolve("dist"), null);
        });
        TextButton paletteBtn = createToolbarButton("Palette", 92.0f, this::openCommandPalette);

        toolbar.addChild(headerStatusWidget);
        toolbar.addChild(mainMenuBtn);
        toolbar.addChild(newTemplateBtn);
        toolbar.addChild(saveScriptBtn);
        toolbar.addChild(lintBtn);
        toolbar.addChild(flowBtn);
        toolbar.addChild(stepIntoBtn);
        toolbar.addChild(stepOverBtn);
        toolbar.addChild(continueBtn);
        toolbar.addChild(reloadBtn);
        toolbar.addChild(renderModeButton);
        toolbar.addChild(exportBtn);
        toolbar.addChild(paletteBtn);
        toolbarFocusButtons.clear();
        toolbarFocusButtons.addAll(List.of(
                mainMenuBtn, newTemplateBtn, saveScriptBtn, lintBtn, flowBtn,
                stepIntoBtn, stepOverBtn, continueBtn, reloadBtn, renderModeButton, exportBtn, paletteBtn));
        themedButtons.addAll(toolbarFocusButtons);

        // Main 3-column workspace (1888 x 780)
        HBox columns = new HBox();
        columns.setSpacing(12.0f);
        columns.setFillHeight(true);
        columns.setPreferredHeight(780.0f);

        // Column 1: Embedded Code Editor (first so visible input 0 is editor), Files, Assets Preview & Cleanup
        WindowBox explorerBox = new WindowBox("Project Explorer, Editor & Assets");
        explorerBox.setPreferredSize(616.0f, 780.0f);
        this.explorerWindowBox = explorerBox;
        themedWindowBoxes.add(explorerBox);
        Viewport explorerVp = new Viewport();
        VBox explorerCol = new VBox();
        explorerCol.setSpacing(8.0f).setFillWidth(true);

        editorStatusWidget = new TextWidget("Editor: Wähle eine .vjava-Datei unten");
        editorStatusWidget.setFontSize(14.0f).setColorHex(ThemePalette.ACCENT_HOVER);
        codeEditorInput = new TextInput("", text -> saveActiveEditorFile()).setMultiline(true);
        codeEditorInput.setId("studio_code_editor");
        codeEditorInput.setPreferredSize(580.0f, 180.0f);
        codeEditorInput.setVisible(false);

        explorerFilesBox = new VBox();
        explorerFilesBox.setSpacing(6.0f).setFillWidth(true);

        HBox cleanupRow = new HBox();
        cleanupRow.setSpacing(8.0f);
        cleanupRow.setFillHeight(true);
        cleanupRow.setPreferredHeight(38.0f);
        TextButton cleanupBtn = createToolbarButton("Unused Cleanup", 180.0f, this::cleanupUnusedAssets);
        TextButton undoCleanupBtn = createToolbarButton("Undo Cleanup", 160.0f, this::undoLastAssetCleanup);
        cleanupRow.addChild(cleanupBtn);
        cleanupRow.addChild(undoCleanupBtn);

        assetPreviewWidget = new TextWidget("Asset-Vorschau: Klicke ein Asset an.");
        assetPreviewWidget.setFontSize(14.0f).setColorHex(ThemePalette.TEXT_PRIMARY);
        explorerAssetsBox = new VBox();
        explorerAssetsBox.setSpacing(6.0f).setFillWidth(true);
        explorerListWidget = new TextWidget("No project scanned.");
        explorerListWidget.setFontSize(14.0f).setColorHex(ThemePalette.TEXT_MUTED);

        explorerCol.addChild(editorStatusWidget);
        explorerCol.addChild(codeEditorInput);
        explorerCol.addChild(explorerFilesBox);
        explorerCol.addChild(cleanupRow);
        explorerCol.addChild(assetPreviewWidget);
        explorerCol.addChild(explorerAssetsBox);
        explorerCol.addChild(explorerListWidget);
        explorerVp.addChild(explorerCol);
        explorerBox.addChild(explorerVp);

        // Column 2: Static Analysis (VJavaLinter), Click-to-Line, Auto-Fix & Visual Flow Graph
        WindowBox lintBox = new WindowBox("Static Analysis & Story Flow-Graph");
        lintBox.setPreferredSize(624.0f, 780.0f);
        this.lintWindowBox = lintBox;
        themedWindowBoxes.add(lintBox);
        Viewport lintVp = new Viewport();
        VBox lintCol = new VBox();
        lintCol.setSpacing(8.0f).setFillWidth(true);

        TextButton autoFixBtn = createToolbarButton("One-Click Auto-Fix", 200.0f, this::applyLinterAutoFix);
        autoFixBtn.setBackgroundColorHex(ThemePalette.ACCENT_IDLE);
        lintSummaryWidget = new TextWidget("Lint not run.");
        lintSummaryWidget.setFontSize(15.0f).setColorHex(ThemePalette.TEXT_PRIMARY);
        lintIssuesBox = new VBox();
        lintIssuesBox.setSpacing(6.0f).setFillWidth(true);

        flowGraphBox = new VBox();
        flowGraphBox.setSpacing(6.0f).setFillWidth(true);
        flowGraphBox.setVisible(flowGraphVisible);

        lintCol.addChild(autoFixBtn);
        lintCol.addChild(lintSummaryWidget);
        lintCol.addChild(lintIssuesBox);
        lintCol.addChild(flowGraphBox);
        lintVp.addChild(lintCol);
        lintBox.addChild(lintVp);

        // Column 3: Live State Variables, Debugger, Watchpoints & Live Director Snippet
        WindowBox varsBox = new WindowBox("Live State Variables & Debugger");
        varsBox.setPreferredSize(624.0f, 780.0f);
        this.varsWindowBox = varsBox;
        themedWindowBoxes.add(varsBox);
        Viewport varsVp = new Viewport();
        VBox varsCol = new VBox();
        varsCol.setSpacing(8.0f).setFillWidth(true);

        debuggerStatusWidget = new TextWidget("Debugger: READY");
        debuggerStatusWidget.setFontSize(14.0f).setColorHex(ThemePalette.ACCENT_HOVER);
        watchpointStatusWidget = new TextWidget("Watchpoints: 0 active");
        watchpointStatusWidget.setFontSize(14.0f).setColorHex(ThemePalette.TEXT_MUTED);
        directorSnippetWidget = new TextWidget("Live Director Snippet: (none)");
        directorSnippetWidget.setFontSize(14.0f).setColorHex(ThemePalette.SUCCESS);

        variableEditorsBox = new VBox();
        variableEditorsBox.setSpacing(6.0f).setFillWidth(true);
        variablesListWidget = new TextWidget("(empty)");
        variablesListWidget.setFontSize(14.0f).setColorHex(ThemePalette.TEXT_MUTED);

        varsCol.addChild(debuggerStatusWidget);
        varsCol.addChild(watchpointStatusWidget);
        varsCol.addChild(directorSnippetWidget);
        varsCol.addChild(variableEditorsBox);
        varsCol.addChild(variablesListWidget);
        varsVp.addChild(varsCol);
        varsBox.addChild(varsVp);

        columns.addChild(explorerBox);
        columns.addChild(lintBox);
        columns.addChild(varsBox);

        // Bottom Console (190px)
        WindowBox consoleBox = new WindowBox("Studio & Engine Console");
        consoleBox.setPreferredHeight(190.0f);
        this.consoleWindowBox = consoleBox;
        themedWindowBoxes.add(consoleBox);
        Viewport consoleVp = new Viewport();
        consoleLogWidget = new TextWidget("");
        consoleLogWidget.setFontSize(15.0f).setColorHex(ThemePalette.TEXT_MUTED);
        consoleVp.addChild(consoleLogWidget);
        consoleBox.addChild(consoleVp);

        rootLayout.addChild(toolbar);
        rootLayout.addChild(columns);
        rootLayout.addChild(consoleBox);

        studioRootScreen.setRoot(rootLayout);
        studioRootScreen.addKeyBinding(GLFW.GLFW_KEY_S, GLFW.GLFW_MOD_CONTROL, this::saveActiveEditorFile);
        studioRootScreen.addKeyBinding(
                GLFW.GLFW_KEY_P, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT, this::openCommandPalette);
        studioRootScreen.addKeyBinding(GLFW.GLFW_KEY_F1, this::toggleShortcutOverlay);
        studioRootScreen.addKeyBinding(GLFW.GLFW_KEY_TAB, this::focusNextButton);
        studioRootScreen.addKeyBinding(GLFW.GLFW_KEY_TAB, GLFW.GLFW_MOD_SHIFT, this::focusPrevButton);
        studioRootScreen.addKeyBinding(GLFW.GLFW_KEY_ESCAPE, this::dismissOverlays);
        studioRootScreen.addKeyBinding(GLFW.GLFW_KEY_ENTER, this::onWorkspaceEnterPressed);
        studioScreenManager.registerScreen(studioRootScreen);
    }

    private static TextButton createToolbarButton(String label, float width, Runnable action) {
        TextButton btn = new TextButton(label, action);
        btn.setPreferredSize(width, 38.0f);
        btn.setFontSize(15.0f);
        btn.setBackgroundColorHex(ThemePalette.ELEVATED);
        btn.setBorderColorHex(ThemePalette.BORDER);
        btn.setBorderThickness(1.0f);
        btn.setCornerRadius(6.0f);
        return btn;
    }

    private void buildNewProjectDialogUi() {
        newProjectDialogScreen = new Screen(NEW_PROJECT_DIALOG_NAME, 800, true);
        Frame backdrop = new Frame();
        backdrop.setBackgroundColorHex(ThemePalette.PANEL_GLASS).setPadding(360.0f, 180.0f);
        Frame card = new Frame();
        card.setBackgroundColorHex(ThemePalette.PANEL_SURFACE)
                .setBorderColorHex(ThemePalette.ACCENT_IDLE)
                .setBorderThickness(1.5f)
                .setCornerRadius(10.0f)
                .setPadding(24.0f);

        VBox box = new VBox();
        box.setSpacing(12.0f).setFillWidth(true);
        TextWidget title = new TextWidget("Neues KAGAYAKI Projekt erstellen");
        title.setFontSize(26.0f).setColorHex(ThemePalette.TEXT_PRIMARY);

        newProjectTitleInput = new TextInput("Neues KAGAYAKI Spiel", null);
        newProjectTitleInput.setId("new_project_title").setPreferredSize(720.0f, 40.0f);
        newProjectPathInput = new TextInput("", null);
        newProjectPathInput.setId("new_project_path").setPreferredSize(720.0f, 40.0f);
        newProjectAuthorInput = new TextInput("KAGAYAKI Author", null);
        newProjectAuthorInput.setId("new_project_author").setPreferredSize(720.0f, 40.0f);

        newProjectRenderModeBtn = createToolbarButton("RenderMode: 2D", 220.0f, () -> {
            newProjectRenderMode = "2D".equals(newProjectRenderMode) ? "3D" : "2D";
            newProjectRenderModeBtn.setText("RenderMode: " + newProjectRenderMode);
        });
        newProjectErrorWidget = new TextWidget("");
        newProjectErrorWidget.setFontSize(15.0f).setColorHex(ThemePalette.ERROR);

        HBox actions = new HBox();
        actions.setSpacing(12.0f);
        actions.setFillHeight(true);
        actions.setPreferredHeight(44.0f);
        TextButton createBtn = createToolbarButton("Erstellen", 180.0f, this::confirmNewProjectDialog);
        createBtn.setBackgroundColorHex(ThemePalette.ACCENT_IDLE);
        TextButton cancelBtn = createToolbarButton("Abbrechen", 160.0f,
                () -> studioScreenManager.hideScreenImmediate(NEW_PROJECT_DIALOG_NAME));
        actions.addChild(createBtn);
        actions.addChild(cancelBtn);

        box.addChild(title);
        box.addChild(newProjectTitleInput);
        box.addChild(newProjectPathInput);
        box.addChild(newProjectAuthorInput);
        box.addChild(newProjectRenderModeBtn);
        box.addChild(newProjectErrorWidget);
        box.addChild(actions);

        card.addChild(box);
        backdrop.addChild(card);
        newProjectDialogScreen.setRoot(backdrop);
        newProjectDialogScreen.setVisible(false);
        newProjectDialogScreen.addKeyBinding(
                GLFW.GLFW_KEY_ESCAPE, () -> studioScreenManager.hideScreenImmediate(NEW_PROJECT_DIALOG_NAME));
        studioScreenManager.registerScreen(newProjectDialogScreen);
    }

    private void buildCommandPaletteUi() {
        commandPaletteScreen = new Screen(COMMAND_PALETTE_SCREEN_NAME, 850, true);
        Frame backdrop = new Frame();
        backdrop.setBackgroundColorHex(ThemePalette.PANEL_GLASS).setPadding(420.0f, 180.0f);
        Frame card = new Frame();
        card.setBackgroundColorHex(ThemePalette.PANEL_SURFACE)
                .setBorderColorHex(ThemePalette.ACCENT_HOVER)
                .setBorderThickness(1.5f)
                .setCornerRadius(10.0f)
                .setPadding(20.0f);

        VBox box = new VBox();
        box.setSpacing(10.0f).setFillWidth(true);
        TextWidget title = new TextWidget("Befehlspalette (Ctrl+Shift+P)");
        title.setFontSize(22.0f).setColorHex(ThemePalette.TEXT_PRIMARY);

        paletteSearchInput = new TextInput("", text -> executeTopPaletteCommand());
        paletteSearchInput.setOnChanged(text -> refreshPaletteMatches());
        paletteSearchInput.setPreferredSize(760.0f, 42.0f);

        paletteMatchesWidget = new TextWidget("");
        paletteMatchesWidget.setFontSize(16.0f).setColorHex(ThemePalette.TEXT_MUTED);

        box.addChild(title);
        box.addChild(paletteSearchInput);
        box.addChild(paletteMatchesWidget);

        card.addChild(box);
        backdrop.addChild(card);
        commandPaletteScreen.setRoot(backdrop);
        commandPaletteScreen.setVisible(false);
        commandPaletteScreen.addKeyBinding(GLFW.GLFW_KEY_ENTER, this::executeTopPaletteCommand);
        commandPaletteScreen.addKeyBinding(GLFW.GLFW_KEY_ESCAPE,
                () -> studioScreenManager.hideScreenImmediate(COMMAND_PALETTE_SCREEN_NAME));
        studioScreenManager.registerScreen(commandPaletteScreen);
    }

    private void registerPaletteCommands() {
        commandPalette.register(new StudioCommandPalette.PaletteCommand(
                "lint.run", "Run Lint", "Analysis", "F5", this::runLint));
        commandPalette.register(new StudioCommandPalette.PaletteCommand(
                "project.reload", "Live Reload", "Project", "Shift+R", () -> api.requestLiveReload()));
        commandPalette.register(new StudioCommandPalette.PaletteCommand(
                "editor.save", "Save Active Script", "Editor", "Ctrl+S", this::saveActiveEditorFile));
        commandPalette.register(new StudioCommandPalette.PaletteCommand(
                "view.flow", "Toggle Flow Graph", "View", "", this::toggleFlowGraph));
        commandPalette.register(new StudioCommandPalette.PaletteCommand(
                "assets.cleanup", "Unused Assets Cleanup", "Assets", "", this::cleanupUnusedAssets));
        commandPalette.register(new StudioCommandPalette.PaletteCommand(
                "render.toggle", "Toggle Render Mode (2D/3D)", "View", "", this::toggleRenderMode));
        commandPalette.register(new StudioCommandPalette.PaletteCommand(
                "help.shortcuts", "Keyboard Shortcuts", "Help", "F1", this::toggleShortcutOverlay));
        commandPalette.register(new StudioCommandPalette.PaletteCommand(
                "qa.preflight", "Run Release PreFlight", "Release", "", this::runPreFlightCheck));
    }

    private void buildShortcutOverlayUi() {
        shortcutOverlayScreen = new Screen(SHORTCUT_OVERLAY_SCREEN_NAME, 860, true);
        Frame backdrop = new Frame();
        backdrop.setBackgroundColorHex(ThemePalette.PANEL_GLASS).setPadding(360.0f, 160.0f);
        Frame card = new Frame();
        card.setBackgroundColorHex(ThemePalette.PANEL_SURFACE)
                .setBorderColorHex(ThemePalette.ACCENT_IDLE)
                .setBorderThickness(1.5f)
                .setCornerRadius(10.0f)
                .setPadding(24.0f);
        VBox content = new VBox();
        content.setSpacing(10.0f).setFillWidth(true);
        TextWidget title = new TextWidget("Tastaturkürzel & Studio-Hilfe (F1)");
        title.setFontSize(24.0f).setColorHex(ThemePalette.TEXT_PRIMARY);
        TextWidget body = new TextWidget(
                "• Allgemein: F1 = Hilfe ein/aus | Esc = Schließen | Ctrl+Shift+P = Befehlspalette\n"
                        + "• Editor: Ctrl+S = Speichern & Lint | Tab / Shift+Tab = Fokus-Navigation\n"
                        + "• Vorschau Window B: Shift+R = Live Reload | Shift+I = Style | Shift+C = Pick | D = Director"
        );
        body.setFontSize(17.0f).setColorHex(ThemePalette.TEXT_MUTED);
        TextButton closeBtn = createToolbarButton("Schließen (Esc)", 180.0f, this::dismissOverlays);
        content.addChild(title);
        content.addChild(body);
        content.addChild(closeBtn);
        card.addChild(content);
        backdrop.addChild(card);
        shortcutOverlayScreen.setRoot(backdrop);
        shortcutOverlayScreen.setVisible(false);
        shortcutOverlayScreen.addKeyBinding(GLFW.GLFW_KEY_ESCAPE, this::dismissOverlays);
        shortcutOverlayScreen.addKeyBinding(GLFW.GLFW_KEY_F1, this::toggleShortcutOverlay);
        studioScreenManager.registerScreen(shortcutOverlayScreen);
    }

    @Override
    public void update(double dt) {
        elapsedSeconds += dt;
        refreshTimer += dt;
        syncLiveRuntimePanels();
        if (refreshTimer >= 0.5) {
            refreshTimer = 0.0;
            refreshLiveTelemetry();
            pollAutoHotReload();
            refreshInspectors();
        }
        if (studioInput != null) {
            studioInput.beginFrame();
            studioScreenManager.update(dt, studioInput);
            studioInput.endFrame();
        } else {
            studioScreenManager.update(dt, null);
        }
    }

    @Override
    public void render(int windowWidth, int windowHeight) {
        if (!glReady || studioPipeline == null || windowWidth <= 0 || windowHeight <= 0) {
            return;
        }
        studioPipeline.beginFrame(windowWidth, windowHeight);
        studioScreenManager.render(studioPipeline, fontManager, textureManager, elapsedSeconds);
        studioPipeline.endFrame();
        updateStudioInputViewport(windowWidth, windowHeight);
    }

    private void updateStudioInputViewport(int fbWidth, int fbHeight) {
        if (studioInput == null || studioPipeline == null || studioWindowHandle == 0L) {
            return;
        }
        Rect2f vp = studioPipeline.getLastLetterboxViewport();
        if (vp.width() <= 0.0f || vp.height() <= 0.0f || fbWidth <= 0 || fbHeight <= 0) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer winW = stack.mallocInt(1);
            IntBuffer winH = stack.mallocInt(1);
            GLFW.glfwGetWindowSize(studioWindowHandle, winW, winH);
            int screenW = winW.get(0);
            int screenH = winH.get(0);
            float scaleX = screenW > 0 ? screenW / (float) fbWidth : 1.0f;
            float scaleY = screenH > 0 ? screenH / (float) fbHeight : 1.0f;
            studioInput.setViewport(
                    vp.x() * scaleX,
                    vp.y() * scaleY,
                    vp.width() * scaleX,
                    vp.height() * scaleY
            );
        }
    }

    @Override
    public void onReload() {
        refreshProjectAndLint();
        refreshInspectors();
        api.getLogger().info("Studio", "Studio workspace refreshed after live reload.");
    }

    /**
     * Generates a playable starter template in {@code targetDir} using
     * {@link ProjectTemplateGenerator.TemplateType#FULL_DEMO} and refreshes diagnostics.
     *
     * @param targetDir  destination directory for the new project
     * @param title      project display title
     * @param renderMode render backend mode ({@code "2D"} or {@code "3D"})
     * @return normalized path to the generated project root
     */
    public Path generateStarterProject(Path targetDir, String title, String renderMode) {
        return createNewProject(
                targetDir, title, null, renderMode, ProjectTemplateGenerator.TemplateType.FULL_DEMO, false);
    }

    /**
     * Creates a new project from the specified {@link ProjectTemplateGenerator.TemplateType},
     * switches the active Studio project root, updates Recent Projects, runs {@link VJavaLinter},
     * and navigates into {@link StudioView#WORKSPACE}.
     *
     * @param targetDir    destination directory for the new project
     * @param title        project title
     * @param templateType template preset ({@code FULL_DEMO} or {@code MINIMAL})
     * @return normalized path to the created project directory
     */
    public Path createNewProject(
            Path targetDir,
            String title,
            ProjectTemplateGenerator.TemplateType templateType
    ) {
        return createNewProject(targetDir, title, null, "2D", templateType, true);
    }

    private Path createNewProject(
            Path targetDir,
            String title,
            String author,
            String renderMode,
            ProjectTemplateGenerator.TemplateType templateType,
            boolean openWorkspaceView
    ) {
        Objects.requireNonNull(targetDir, "targetDir must not be null");
        ProjectTemplateGenerator.TemplateType effectiveType = templateType != null
                ? templateType
                : ProjectTemplateGenerator.TemplateType.FULL_DEMO;
        try {
            Path created = ProjectTemplateGenerator.generate(targetDir, title, author, renderMode, effectiveType);
            this.activeProjectRoot = created;
            workspaceState.recordOpenedProject(created);
            lastScannedAst = ProjectScanner.scan(created);
            lastLintReport = VJavaLinter.lint(lastScannedAst);
            if (lastScannedAst.descriptor() != null) {
                api.setProjectDescriptor(lastScannedAst.descriptor());
            }
            api.getLogger().info("Studio", "Neues Projekt erstellt (" + effectiveType + "): " + created);
            if (openWorkspaceView) {
                showWorkspace();
            }
            refreshInspectorTextViews();
            return created;
        } catch (IOException ex) {
            api.getLogger().error("Studio", "Starter project generation failed: " + ex.getMessage(), ex);
            throw new IllegalStateException("Failed to generate starter project", ex);
        }
    }

    private void onNewProjectClicked() {
        if (newProjectDialogScreen != null && studioScreenManager != null) {
            Path baseRoot = getActiveProjectRoot();
            Path parentDir = baseRoot.getParent() != null ? baseRoot.getParent() : baseRoot;
            int index = 1;
            Path candidate = parentDir.resolve("kagayaki_projekt_" + index);
            while (Files.exists(candidate)) {
                index++;
                candidate = parentDir.resolve("kagayaki_projekt_" + index);
            }
            newProjectTitleInput.setText("KAGAYAKI Projekt " + index);
            newProjectPathInput.setText(candidate.toString());
            newProjectAuthorInput.setText("KAGAYAKI Author");
            newProjectErrorWidget.setText("");
            studioScreenManager.showScreen(newProjectDialogScreen);
            return;
        }
        onNewProjectQuickFallback();
    }

    private void onNewProjectQuickFallback() {
        Path baseRoot = getActiveProjectRoot();
        Path parentDir = baseRoot.getParent() != null ? baseRoot.getParent() : baseRoot;
        int index = 1;
        Path candidate = parentDir.resolve("kagayaki_projekt_" + index);
        while (Files.exists(candidate)) {
            index++;
            candidate = parentDir.resolve("kagayaki_projekt_" + index);
        }
        createNewProject(candidate, "KAGAYAKI Projekt " + index, selectedTemplateType);
    }

    private void confirmNewProjectDialog() {
        String rawTitle = newProjectTitleInput.getText().trim();
        String rawPath = newProjectPathInput.getText().trim();
        String rawAuthor = newProjectAuthorInput.getText().trim();
        if (rawPath.isBlank() || rawPath.contains("..")) {
            newProjectErrorWidget.setText("Ungültiger Zielpfad.");
            return;
        }
        Path targetDir = Path.of(rawPath).toAbsolutePath().normalize();
        createNewProject(targetDir, rawTitle, rawAuthor, newProjectRenderMode, selectedTemplateType, true);
        studioScreenManager.hideScreenImmediate(NEW_PROJECT_DIALOG_NAME);
    }

    private void toggleTemplateType() {
        selectedTemplateType = switch (selectedTemplateType) {
            case FULL_DEMO -> ProjectTemplateGenerator.TemplateType.STARTER;
            case STARTER -> ProjectTemplateGenerator.TemplateType.MINIMAL;
            case MINIMAL -> ProjectTemplateGenerator.TemplateType.ACTION_ADVENTURE_2D;
            case ACTION_ADVENTURE_2D -> ProjectTemplateGenerator.TemplateType.FULL_DEMO;
        };
        if (templateToggleButton != null) {
            templateToggleButton.setText(formatTemplateButtonText());
        }
    }

    private String formatTemplateButtonText() {
        return switch (selectedTemplateType) {
            case FULL_DEMO -> "Vorlage: Vollständige Demo (Main Menu, Save/Load, WASD)";
            case STARTER -> "Vorlage: Klassischer Starter";
            case MINIMAL -> "Vorlage: Minimal (Grundgerüst)";
            case ACTION_ADVENTURE_2D -> "Vorlage: Action-Adventure 2D (WASD, Plattformen, Rätsel)";
        };
    }

    private void loadBundledDemoProject() {
        Path demoDir = Path.of("demo/project").toAbsolutePath().normalize();
        if (!Files.isDirectory(demoDir)) {
            api.getLogger().warn("Studio", "Bundled demo project not found at: " + demoDir);
            return;
        }
        this.activeProjectRoot = demoDir;
        workspaceState.recordOpenedProject(demoDir);
        refreshProjectAndLint();
        if (lastScannedAst != null && lastScannedAst.descriptor() != null) {
            api.setProjectDescriptor(lastScannedAst.descriptor());
        }
        showWorkspace();
        refreshInspectors();
    }

    /**
     * Switches Studio Window A to the {@link StudioView#MAIN_MENU} Project Hub screen.
     */
    public void showMainMenu() {
        this.activeView = StudioView.MAIN_MENU;
        if (studioScreenManager != null && studioMainMenuScreen != null) {
            studioScreenManager.hideScreen(WORKSPACE_SCREEN_NAME);
            studioScreenManager.showScreen(studioMainMenuScreen);
        }
        refreshInspectorTextViews();
    }

    /**
     * Switches Studio Window A to the {@link StudioView#WORKSPACE} IDE view.
     */
    public void showWorkspace() {
        this.activeView = StudioView.WORKSPACE;
        if (studioScreenManager != null && studioRootScreen != null) {
            studioScreenManager.hideScreen(MAIN_MENU_SCREEN_NAME);
            studioScreenManager.showScreen(studioRootScreen);
        }
        refreshInspectorTextViews();
    }

    /**
     * Scans the active project directory and executes {@link VJavaLinter}.
     *
     * @return latest lint report
     */
    public VJavaLinter.LintReport runLint() {
        refreshProjectAndLint();
        return lastLintReport;
    }

    /**
     * Switches the owning {@link EngineApplication} between {@link RenderMode#MODE_2D}
     * and {@link RenderMode#MODE_3D} (activating the controlled 3D stub warning).
     *
     * @param targetMode desired render mode
     * @return active render mode after switching
     */
    public RenderMode switchRenderMode(RenderMode targetMode) {
        EngineApplication app = api.getService(EngineApplication.class);
        if (app != null) {
            app.switchRenderPipeline(targetMode);
            if (renderModeButton != null) {
                renderModeButton.setText(targetMode == RenderMode.MODE_3D ? "Mode: 3D (Stub)" : "Mode: 2D");
            }
            return app.getActivePipeline().getMode();
        }
        return targetMode;
    }

    private void toggleRenderMode() {
        EngineApplication app = api.getService(EngineApplication.class);
        RenderMode current = (app != null && app.getActivePipeline() != null)
                ? app.getActivePipeline().getMode()
                : RenderMode.MODE_2D;
        switchRenderMode(current == RenderMode.MODE_2D ? RenderMode.MODE_3D : RenderMode.MODE_2D);
    }

    /**
     * Exports the active project into a standalone Game-only bundle in {@code outputDir}.
     *
     * @param outputDir             target standalone distribution directory
     * @param optionalStandaloneJar optional prebuilt standalone JAR to bundle (nullable)
     * @return standalone build result metadata
     */
    public StandaloneBuilder.BuildResult exportStandalone(Path outputDir, Path optionalStandaloneJar) {
        Path projectRoot = getActiveProjectRoot();
        try {
            StandaloneBuilder.BuildResult result =
                    StandaloneBuilder.build(projectRoot, outputDir, optionalStandaloneJar);
            this.lastExportResult = result;
            api.getLogger().info("Studio", "Exported standalone bundle to " + result.outputDir()
                    + " (" + result.packagedEntries() + " entries in .vpak)");
            refreshInspectors();
            return result;
        } catch (IOException ex) {
            api.getLogger().error("Studio", "Standalone export failed: " + ex.getMessage(), ex);
            throw new IllegalStateException("Standalone export failed", ex);
        }
    }

    private void refreshProjectAndLint() {
        Path projectRoot = getActiveProjectRoot();
        if (Files.isDirectory(projectRoot)) {
            try {
                this.lastScannedAst = ProjectScanner.scan(projectRoot);
                this.lastLintReport = VJavaLinter.lint(this.lastScannedAst);
            } catch (de.swfdtf.parser.VJavaParseException | IllegalArgumentException ex) {
                this.lastScannedAst = null;
                this.lastLintReport = VJavaLinter.lint(projectRoot);
            }
        } else {
            this.lastScannedAst = null;
            this.lastLintReport = new VJavaLinter.LintReport(List.of(), 0, 0);
        }
        refreshInspectorTextViews();
    }

    /** Refreshes the inspector text widgets from current AST, lint, and runtime state. */
    public void refreshInspectors() {
        refreshInspectorTextViews();
    }

    private void refreshInspectorTextViews() {
        ProjectDescriptor desc = api.getProjectDescriptor();
        String title = desc != null ? desc.getTitle() : "KAGAYAKI";
        String projVer = getActiveProjectVersion();

        if (mainMenuVersionWidget != null) {
            mainMenuVersionWidget.setText(EngineVersion.formatBanner() + " — Studio Hauptmenü");
        }
        if (mainMenuActiveProjectWidget != null) {
            mainMenuActiveProjectWidget.setText(
                    "Aktives Projekt: " + title + " (v" + projVer + ") | Pfad: " + getActiveProjectRoot()
            );
        }
        if (mainMenuRecentWidget != null) {
            List<String> recents = workspaceState.getRecentProjectPaths();
            if (recents.isEmpty()) {
                mainMenuRecentWidget.setText("(noch keine zuletzt geöffneten Projekte)");
            } else {
                StringBuilder sb = new StringBuilder();
                for (String path : recents) {
                    sb.append("• ").append(path).append('\n');
                }
                mainMenuRecentWidget.setText(sb.toString());
            }
        }
        if (templateToggleButton != null) {
            templateToggleButton.setText(formatTemplateButtonText());
        }

        if (headerStatusWidget != null) {
            headerStatusWidget.setText(
                    "Studio v" + EngineVersion.VERSION + " — " + title
                            + " (v" + projVer + ") | " + api.getClock().getCurrentFps() + " FPS"
            );
        }

        if (explorerListWidget != null) {
            if (lastScannedAst == null) {
                explorerListWidget.setText("No project directory loaded.");
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("Scripts (").append(lastScannedAst.scriptFiles().size()).append("):\n");
                for (Path script : lastScannedAst.scriptFiles()) {
                    sb.append("  • ").append(lastScannedAst.projectRoot().relativize(script)).append('\n');
                }
                sb.append("\nAssets (").append(lastScannedAst.discoveredAssets().size()).append("):\n");
                for (String asset : lastScannedAst.discoveredAssets()) {
                    sb.append("  • ").append(asset).append('\n');
                }
                explorerListWidget.setText(sb.toString());
            }
        }

        if (lintSummaryWidget != null && lastLintReport != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("Errors: ").append(lastLintReport.errorCount())
                    .append(" | Warnings: ").append(lastLintReport.warningCount()).append('\n');
            if (lastLintReport.issues().isEmpty()) {
                sb.append("\nNo issues detected. Ready to run or export.");
            } else {
                for (VJavaLinter.LintIssue issue : lastLintReport.issues()) {
                    sb.append('\n').append('[').append(issue.severity()).append("] ")
                            .append(issue.code()).append(" (")
                            .append(issue.sourceFile()).append(':').append(issue.lineNumber()).append(")\n  ")
                            .append(issue.message());
                }
            }
            lintSummaryWidget.setText(sb.toString());
        }

        if (variablesListWidget != null) {
            ScriptSession session = api.getService(ScriptSession.class);
            if (session == null) {
                variablesListWidget.setText("No active ScriptSession.");
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("Label: ").append(session.getRuntime().getCurrentLabel())
                        .append(" [").append(session.getRuntime().getStatus()).append("]\n\n");
                Map<String, Object> vars = session.getStateStore().snapshotMap();
                if (vars.isEmpty()) {
                    sb.append("(no state variables set)");
                } else {
                    for (Map.Entry<String, Object> e : vars.entrySet()) {
                        sb.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
                    }
                }
                variablesListWidget.setText(sb.toString());
            }
        }

        if (consoleLogWidget != null) {
            List<EngineLogger.LogEntry> entries = getFilteredConsoleEntries(12);
            List<String> lines = new ArrayList<>(entries.size());
            for (EngineLogger.LogEntry e : entries) {
                lines.add(e.format());
            }
            consoleLogWidget.setText(String.join("\n", lines));
        }

        rebuildExplorerWidgets();
        rebuildLintIssueButtons();
        rebuildFlowGraph();
        seedDefaultVariablesFromAst();
        rebuildVariableEditors();
        syncLiveRuntimePanels();
        layoutWorkspace();
    }

    private void syncLiveRuntimePanels() {
        ScriptSession session = api.getService(ScriptSession.class);
        if (session != null) {
            ScriptRuntime rt = session.getRuntime();
            String currentLabel = rt.getCurrentLabel();
            for (TextButton btn : flowNodeButtons) {
                btn.setSelected(currentLabel != null && currentLabel.equals(btn.getText()));
            }
            watchpointManager.checkAll(session.getStateStore(), currentLabel != null ? currentLabel : "");
            if (watchpointStatusWidget != null) {
                List<WatchpointEvent> hist = watchpointManager.getRecentHistory();
                String latest = hist.isEmpty()
                        ? "Watchpoints logged: 0"
                        : "Watchpoint: " + hist.get(hist.size() - 1).key()
                        + " = " + hist.get(hist.size() - 1).newValue();
                watchpointStatusWidget.setText(latest);
            }
            if (debuggerStatusWidget != null) {
                debuggerStatusWidget.setText(
                        "Debugger: " + scriptDebugger.getMode()
                                + " | Stack: " + String.join(" -> ", scriptDebugger.captureCallStackSummary(rt))
                );
            }
        }
        Object snippet = api != null ? api.getProperty("studio.lastTransformSnippet") : null;
        if (directorSnippetWidget != null) {
            directorSnippetWidget.setText(snippet != null
                    ? "Live Director: " + snippet
                    : "Live Director Snippet: (none)");
        }
    }

    private void rebuildExplorerWidgets() {
        if (explorerFilesBox == null || explorerAssetsBox == null) {
            return;
        }
        explorerFilesBox.clearChildren();
        explorerAssetsBox.clearChildren();
        if (lastScannedAst == null) {
            return;
        }
        for (Path script : lastScannedAst.scriptFiles()) {
            Path rel = lastScannedAst.projectRoot().relativize(script);
            TextButton btn = createToolbarButton(rel.toString().replace('\\', '/'), 540.0f,
                    () -> openFileInEditor(script, 1));
            explorerFilesBox.addChild(btn);
        }
        List<String> visibleAssets = filterExplorerEntries(lastScannedAst.discoveredAssets(), explorerFilterQuery);
        for (String asset : visibleAssets) {
            TextButton btn = createToolbarButton(asset, 540.0f, () -> inspectAssetItem(asset));
            explorerAssetsBox.addChild(btn);
        }
    }

    private void openFileInEditor(Path file, int lineNumber) {
        if (file == null || !Files.isRegularFile(file) || codeEditorInput == null) {
            return;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String[] lines = content.split("\n", -1);
            int maxLine = Math.max(1, lines.length);
            this.activeEditorFile = file.toAbsolutePath().normalize();
            this.activeEditorLine = Math.clamp(lineNumber, 1, maxLine);
            codeEditorInput.setVisible(true);
            codeEditorInput.setText(content);
            List<VJavaSyntaxHighlighter.TokenRun> runs = syntaxHighlighter.tokenize(content);
            if (editorStatusWidget != null) {
                editorStatusWidget.setText(
                        file.getFileName() + ":" + this.activeEditorLine
                                + " (" + maxLine + " Zeilen, " + runs.size() + " Tokens)"
                );
            }
            layoutWorkspace();
        } catch (IOException ex) {
            api.getLogger().warn("Studio", "Could not open script in editor: " + ex.getMessage());
        }
    }

    private void saveActiveEditorFile() {
        if (activeEditorFile == null || codeEditorInput == null || !codeEditorInput.isVisible()) {
            return;
        }
        try {
            String updated = codeEditorInput.getText();
            Files.writeString(activeEditorFile, updated, StandardCharsets.UTF_8);
            List<VJavaSyntaxHighlighter.TokenRun> runs = syntaxHighlighter.tokenize(updated);
            if (editorStatusWidget != null) {
                editorStatusWidget.setText("Gespeichert: " + activeEditorFile.getFileName()
                        + " (" + runs.size() + " Tokens)");
            }
            runLint();
            api.requestLiveReload();
        } catch (IOException ex) {
            api.getLogger().error("Studio", "Failed saving editor file: " + ex.getMessage(), ex);
        }
    }

    private void inspectAssetItem(String relPath) {
        if (relPath == null || relPath.isBlank()) {
            return;
        }
        try {
            AssetMetadataInspector.AssetMetadata meta =
                    assetMetadataInspector.inspectAsset(getActiveProjectRoot(), relPath).orElse(null);
            if (meta == null) {
                assetPreviewWidget.setText("Asset nicht gefunden: " + relPath);
                return;
            }
            if (meta.widthPx().isPresent() && meta.heightPx().isPresent()) {
                assetPreviewWidget.setText(relPath + " — " + meta.widthPx().orElseThrow()
                        + "x" + meta.heightPx().orElseThrow() + " (" + meta.mediaType() + ")");
            } else if (meta.sampleRateHz().isPresent()) {
                assetPreviewWidget.setText(relPath + " — " + meta.sampleRateHz().orElseThrow() + " Hz Audio");
                AudioEngine audio = api.getService(AudioEngine.class);
                if (audio != null) {
                    audio.play("sound", relPath, false, 0.0, 1.0);
                }
            } else {
                assetPreviewWidget.setText(relPath + " (" + meta.byteSize() + " Bytes)");
            }
        } catch (IOException | IllegalArgumentException ex) {
            assetPreviewWidget.setText("Vorschau fehlgeschlagen: " + relPath);
        }
    }

    private void cleanupUnusedAssets() {
        if (lastScannedAst == null) {
            return;
        }
        UnusedAssetDetector.AssetUsageReport report = unusedAssetDetector.analyze(lastScannedAst);
        Path projectRoot = getActiveProjectRoot();
        Path trashRoot = projectRoot.resolve(".trash");
        List<String> moved = new ArrayList<>();
        for (String rel : report.unusedAssets()) {
            Path src = projectRoot.resolve(rel).normalize();
            if (!src.startsWith(projectRoot) || !Files.isRegularFile(src)) {
                continue;
            }
            Path dst = trashRoot.resolve(rel).normalize();
            try {
                if (dst.getParent() != null) {
                    Files.createDirectories(dst.getParent());
                }
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                moved.add(rel);
            } catch (IOException ex) {
                api.getLogger().warn("Studio", "Could not move unused asset '" + rel + "': " + ex.getMessage());
            }
        }
        if (!moved.isEmpty()) {
            trashedAssetBatches.push(moved);
        }
        refreshProjectAndLint();
    }

    private void undoLastAssetCleanup() {
        if (trashedAssetBatches.isEmpty()) {
            return;
        }
        Path projectRoot = getActiveProjectRoot();
        Path trashRoot = projectRoot.resolve(".trash");
        List<String> batch = trashedAssetBatches.pop();
        for (String rel : batch) {
            Path src = trashRoot.resolve(rel).normalize();
            Path dst = projectRoot.resolve(rel).normalize();
            if (Files.isRegularFile(src)) {
                try {
                    if (dst.getParent() != null) {
                        Files.createDirectories(dst.getParent());
                    }
                    Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ex) {
                    api.getLogger().warn("Studio", "Could not restore trashed asset '" + rel + "': " + ex.getMessage());
                }
            }
        }
        refreshProjectAndLint();
    }

    private void rebuildLintIssueButtons() {
        if (lintIssuesBox == null) {
            return;
        }
        lintIssuesBox.clearChildren();
        if (lastLintReport == null || lastLintReport.issues().isEmpty()) {
            return;
        }
        for (VJavaLinter.LintIssue issue : lastLintReport.issues()) {
            String fileLabel = issue.sourceFile() != null
                    ? Path.of(issue.sourceFile()).getFileName().toString()
                    : "script.vjava";
            String label = "[" + issue.severity() + "] " + issue.code()
                    + " (" + fileLabel + ":" + issue.lineNumber() + ")";
            TextButton btn = createToolbarButton(label, 580.0f, () -> jumpToLintIssue(issue));
            lintIssuesBox.addChild(btn);
        }
    }

    private void jumpToLintIssue(VJavaLinter.LintIssue issue) {
        if (issue == null) {
            return;
        }
        Path target = issue.sourceFile() != null && !issue.sourceFile().isBlank()
                ? getActiveProjectRoot().resolve(issue.sourceFile()).normalize()
                : getActiveProjectRoot().resolve("main.vjava");
        if (!Files.isRegularFile(target) && lastScannedAst != null && !lastScannedAst.scriptFiles().isEmpty()) {
            target = lastScannedAst.scriptFiles().get(0);
        }
        openFileInEditor(target, Math.max(1, issue.lineNumber()));
    }

    private void applyLinterAutoFix() {
        try {
            VJavaLinterAutoFixer fixer = new VJavaLinterAutoFixer();
            VJavaLinterAutoFixer.AutoFixResult result = fixer.applySafeFixes(getActiveProjectRoot());
            api.getLogger().info("Studio", "Auto-Fix applied " + result.appliedCount() + " repair(s).");
            runLint();
            if (activeEditorFile != null && Files.isRegularFile(activeEditorFile)) {
                openFileInEditor(activeEditorFile, activeEditorLine);
            }
        } catch (IOException ex) {
            api.getLogger().error("Studio", "Auto-Fix failed: " + ex.getMessage(), ex);
        }
    }

    private void toggleFlowGraph() {
        flowGraphVisible = !flowGraphVisible;
        if (flowGraphBox != null) {
            flowGraphBox.setVisible(flowGraphVisible);
            rebuildFlowGraph();
            layoutWorkspace();
        }
    }

    private void rebuildFlowGraph() {
        if (flowGraphBox == null) {
            return;
        }
        flowGraphBox.clearChildren();
        flowNodeButtons.clear();
        flowGraphBox.setVisible(flowGraphVisible);
        if (!flowGraphVisible || lastScannedAst == null) {
            return;
        }
        ScriptFlowGraphExporter.FlowGraph graph = flowGraphExporter.buildGraph(lastScannedAst);
        ScriptSession session = api.getService(ScriptSession.class);
        String activeLabel = session != null ? session.getRuntime().getCurrentLabel() : null;

        for (String label : graph.labels()) {
            TextButton nodeBtn = createToolbarButton(label, 540.0f, () -> {
                if (session != null) {
                    session.getRuntime().jumpToLabel(label);
                    syncLiveRuntimePanels();
                }
            });
            nodeBtn.setSelected(label.equals(activeLabel));
            flowNodeButtons.add(nodeBtn);
            flowGraphBox.addChild(nodeBtn);
        }
        if (!graph.unreachableLabels().isEmpty()) {
            TextWidget unreachableTxt = new TextWidget(
                    "Unreachable: " + String.join(", ", graph.unreachableLabels()));
            unreachableTxt.setFontSize(14.0f).setColorHex(ThemePalette.WARNING);
            flowGraphBox.addChild(unreachableTxt);
        }
    }

    private void seedDefaultVariablesFromAst() {
        ScriptSession session = api != null ? api.getService(ScriptSession.class) : null;
        if (session == null || lastScannedAst == null) {
            return;
        }
        for (VJavaAst.SetVarStatement dv : lastScannedAst.defaultVariables()) {
            if (!session.getStateStore().has(dv.varName())) {
                Object val = ConditionEvaluator.evaluate(
                        dv.expression(), session.getStateStore(), session.getPersistentState());
                session.getStateStore().set(dv.varName(), val);
            }
        }
    }

    private void rebuildVariableEditors() {
        if (variableEditorsBox == null) {
            return;
        }
        ScriptSession session = api != null ? api.getService(ScriptSession.class) : null;
        if (session == null) {
            return;
        }
        Map<String, Object> vars = session.getStateStore().snapshotMap();
        if (!variableInputs.keySet().equals(vars.keySet())) {
            variableEditorsBox.clearChildren();
            variableInputs.clear();
            lastCommittedVariableTexts.clear();
            for (Map.Entry<String, Object> entry : vars.entrySet()) {
                String key = entry.getKey();
                String textVal = String.valueOf(entry.getValue());
                HBox row = new HBox();
                row.setSpacing(8.0f);
                row.setFillHeight(true);
                row.setPreferredHeight(36.0f);
                TextWidget keyLbl = new TextWidget(key + " =");
                keyLbl.setFontSize(15.0f).setColorHex(ThemePalette.TEXT_PRIMARY).setPreferredWidth(180.0f);
                TextInput valInput = new TextInput(textVal, submitted -> commitSingleVariableInput(key));
                valInput.setId("var_" + key);
                valInput.setPreferredSize(360.0f, 34.0f);
                valInput.setVisible(true);
                row.addChild(keyLbl);
                row.addChild(valInput);
                variableEditorsBox.addChild(row);
                variableInputs.put(key, valInput);
                lastCommittedVariableTexts.put(key, textVal);
            }
            return;
        }
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            String key = entry.getKey();
            TextInput input = variableInputs.get(key);
            String storeText = String.valueOf(entry.getValue());
            String prevText = lastCommittedVariableTexts.get(key);
            if (input != null && !input.isFocused() && input.getText().equals(prevText)
                    && !storeText.equals(prevText)) {
                input.setText(storeText);
                lastCommittedVariableTexts.put(key, storeText);
            }
        }
    }

    private void commitDirtyVariableInputs() {
        for (String key : List.copyOf(variableInputs.keySet())) {
            TextInput input = variableInputs.get(key);
            if (input != null && !input.getText().equals(lastCommittedVariableTexts.get(key))) {
                commitSingleVariableInput(key);
            }
        }
    }

    private void commitSingleVariableInput(String key) {
        ScriptSession session = api != null ? api.getService(ScriptSession.class) : null;
        TextInput input = variableInputs.get(key);
        if (session == null || input == null) {
            return;
        }
        String raw = input.getText();
        String prevText = lastCommittedVariableTexts.getOrDefault(key, "");
        Object prevVal = session.getStateStore().get(key);
        Object parsed;
        if (prevVal instanceof Integer) {
            try {
                parsed = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ex) {
                input.setText(prevText);
                return;
            }
        } else if (prevVal instanceof Long) {
            try {
                parsed = Long.parseLong(raw.trim());
            } catch (NumberFormatException ex) {
                input.setText(prevText);
                return;
            }
        } else if (prevVal instanceof Double || prevVal instanceof Float) {
            try {
                double d = Double.parseDouble(raw.trim());
                if (!Double.isFinite(d)) {
                    input.setText(prevText);
                    return;
                }
                parsed = (prevVal instanceof Float) ? (float) d : d;
            } catch (NumberFormatException ex) {
                input.setText(prevText);
                return;
            }
        } else if (prevVal instanceof Boolean) {
            String trimmed = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (!"true".equals(trimmed) && !"false".equals(trimmed)) {
                input.setText(prevText);
                return;
            }
            parsed = Boolean.parseBoolean(trimmed);
        } else {
            parsed = raw;
        }
        session.getStateStore().set(key, parsed);
        lastCommittedVariableTexts.put(key, String.valueOf(parsed));
        syncLiveRuntimePanels();
    }

    private void debugStepInto() {
        ScriptSession session = api != null ? api.getService(ScriptSession.class) : null;
        if (session != null) {
            scriptDebugger.stepInto(session.getRuntime());
            refreshInspectors();
        }
    }

    private void debugStepOver() {
        ScriptSession session = api != null ? api.getService(ScriptSession.class) : null;
        if (session != null) {
            scriptDebugger.stepOver(session.getRuntime());
            refreshInspectors();
        }
    }

    private void debugContinue() {
        ScriptSession session = api != null ? api.getService(ScriptSession.class) : null;
        if (session != null) {
            scriptDebugger.continueExecution(session.getRuntime());
            refreshInspectors();
        }
    }

    private void openCommandPalette() {
        if (commandPaletteScreen == null || studioScreenManager == null) {
            return;
        }
        paletteSearchInput.setText("");
        refreshPaletteMatches();
        studioScreenManager.showScreen(commandPaletteScreen);
    }

    private void refreshPaletteMatches() {
        if (paletteMatchesWidget == null || paletteSearchInput == null) {
            return;
        }
        List<StudioCommandPalette.PaletteCommand> matches = commandPalette.search(paletteSearchInput.getText());
        if (matches.isEmpty()) {
            paletteMatchesWidget.setText("(keine passenden Befehle)");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (StudioCommandPalette.PaletteCommand cmd : matches) {
            sb.append("• ").append(cmd.title())
                    .append(" [").append(cmd.category()).append("]")
                    .append(cmd.shortcut().isBlank() ? "" : " (" + cmd.shortcut() + ")")
                    .append('\n');
        }
        paletteMatchesWidget.setText(sb.toString());
    }

    private void executeTopPaletteCommand() {
        List<StudioCommandPalette.PaletteCommand> matches = commandPalette.search(paletteSearchInput.getText());
        studioScreenManager.hideScreenImmediate(COMMAND_PALETTE_SCREEN_NAME);
        if (!matches.isEmpty()) {
            commandPalette.executeById(matches.get(0).id());
        }
    }

    private void layoutWorkspace() {
        if (studioScreenManager != null && studioRootScreen != null && studioRootScreen.isVisible()) {
            studioRootScreen.layout(studioScreenManager.getLogicalWidth(), studioScreenManager.getLogicalHeight());
        }
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (eventBusSnifferSub != null) {
            eventBusSnifferSub.unsubscribe();
            eventBusSnifferSub = null;
        }
        if (studioInput != null) {
            studioInput.dispose();
            studioInput = null;
        }
        if (studioPipeline != null) {
            studioPipeline.dispose();
            studioPipeline = null;
        }
        if (fontManager != null) {
            fontManager.dispose();
        }
        if (textureManager != null) {
            textureManager.dispose();
        }
    }

    /**
     * Returns the currently active top-level Studio view.
     *
     * @return currently active top-level Studio view ({@link StudioView#MAIN_MENU} or {@link StudioView#WORKSPACE})
     */
    public StudioView getActiveView() {
        return activeView;
    }

    /**
     * Returns the currently selected scaffolding template preset.
     *
     * @return currently selected scaffolding template preset
     */
    public ProjectTemplateGenerator.TemplateType getSelectedTemplateType() {
        return selectedTemplateType;
    }

    /**
     * Updates the selected template preset for the "Neues Projekt" action.
     *
     * @param templateType template preset (non-null)
     */
    public void setSelectedTemplateType(ProjectTemplateGenerator.TemplateType templateType) {
        this.selectedTemplateType = Objects.requireNonNull(templateType, "templateType must not be null");
        if (templateToggleButton != null) {
            templateToggleButton.setText(formatTemplateButtonText());
        }
    }

    /**
     * Returns the formatted engine and DSL version banner.
     *
     * @return formatted engine and {@code .vjava} specification version banner
     */
    public String getEngineVersionBanner() {
        return EngineVersion.formatBanner();
    }

    /**
     * Returns the active project semantic version string.
     *
     * @return active project semantic version string
     */
    public String getActiveProjectVersion() {
        ProjectDescriptor desc = api != null ? api.getProjectDescriptor() : null;
        return (desc != null && desc.getVersion() != null && !desc.getVersion().isBlank())
                ? desc.getVersion()
                : EngineVersion.VERSION;
    }

    /**
     * Returns the active Studio project root directory.
     *
     * @return active Studio project root directory
     */
    public Path getActiveProjectRoot() {
        if (activeProjectRoot != null) {
            return activeProjectRoot;
        }
        return api.getConfig().projectDir().toAbsolutePath().normalize();
    }

    /**
     * Returns an unmodifiable list of recently opened or created project paths.
     *
     * @return unmodifiable list of recently opened or created project paths
     */
    public List<String> getRecentProjects() {
        return workspaceState.getRecentProjectPaths();
    }

    /**
     * Returns the most recently scanned project AST.
     *
     * @return latest {@link VJavaAst.ProjectAst}, or {@code null} if not yet scanned
     */
    public VJavaAst.ProjectAst getLastScannedAst() {
        return lastScannedAst;
    }

    /**
     * Returns the most recent project lint report.
     *
     * @return latest {@link VJavaLinter.LintReport}, or {@code null} if not yet linted
     */
    public VJavaLinter.LintReport getLastLintReport() {
        return lastLintReport;
    }

    /**
     * Returns the most recent standalone export build result.
     *
     * @return latest {@link StandaloneBuilder.BuildResult}, or {@code null} if not yet exported
     */
    public StandaloneBuilder.BuildResult getLastExportResult() {
        return lastExportResult;
    }

    /**
     * Returns the native GLFW handle of Studio Window A.
     *
     * @return native Studio window handle, or {@code 0L} when headless
     */
    public long getStudioWindowHandle() {
        return studioWindowHandle;
    }

    /**
     * Returns the native GLFW handle of Game Window B.
     *
     * @return native Game window handle, or {@code 0L} when headless
     */
    public long getGameWindowHandle() {
        return gameWindowHandle;
    }

    /**
     * Returns whether an OpenGL context is ready for Studio rendering.
     *
     * @return {@code true} if OpenGL rendering is enabled for the Studio window
     */
    public boolean isGlReady() {
        return glReady;
    }

    /**
     * Returns whether this controller has been disposed.
     *
     * @return {@code true} if {@link #dispose()} has been called
     */
    public boolean isDisposed() {
        return disposed;
    }

    /**
     * Returns the Studio Window A screen manager.
     *
     * @return Studio {@link ScreenManager}, or {@code null} before {@link #init}
     */
    public ScreenManager getStudioScreenManager() {
        return studioScreenManager;
    }

    /**
     * Returns the Studio Window A input manager used to route mouse and keyboard events.
     *
     * @return the Studio {@link InputManager}, or {@code null} before {@link #init} or after {@link #dispose}
     */
    public InputManager getStudioInput() {
        return studioInput;
    }

    // ---- Wave P2-2 Studio IDE API & Features (#0101-#0168) ----

    /**
     * Searches project script/text files for {@code query} and returns deterministic matches ({@code #0101}).
     *
     * @param query         literal search query
     * @param caseSensitive whether matching is case-sensitive
     * @return immutable list of {@link ProjectSearchService.SearchMatch}
     */
    public List<ProjectSearchService.SearchMatch> searchProject(String query, boolean caseSensitive) {
        return searchService.searchPlainText(getActiveProjectRoot(), query, caseSensitive);
    }

    /**
     * Executes plain-text search-and-replace across the active project ({@code #0101}).
     *
     * @param query         search query
     * @param replacement   replacement string
     * @param caseSensitive whether matching is case-sensitive
     * @param dryRun        when {@code true}, returns preview without writing files
     * @return {@link ProjectSearchService.ReplaceResult}
     */
    public ProjectSearchService.ReplaceResult replaceProjectText(
            String query,
            String replacement,
            boolean caseSensitive,
            boolean dryRun
    ) {
        ProjectSearchService.ReplaceResult res = searchService.replacePlainText(
                getActiveProjectRoot(), query, replacement, caseSensitive, dryRun);
        if (!dryRun && res.modifiedFiles() > 0) {
            refreshProjectAndLint();
        }
        return res;
    }

    /**
     * Opens a matched search result directly in the embedded editor at its 1-based line ({@code #0101}).
     *
     * @param match search match entry
     */
    public void openSearchMatch(ProjectSearchService.SearchMatch match) {
        if (match != null) {
            openFileInEditor(match.file(), match.lineNumber());
        }
    }

    /**
     * Returns the Studio label bookmark manager ({@code #0102}).
     *
     * @return active {@link LabelBookmarkManager}
     */
    public LabelBookmarkManager getBookmarkManager() {
        return bookmarkManager;
    }

    /**
     * Saves a label bookmark and optionally jumps directly to it in editor and preview ({@code #0102}).
     *
     * @param labelName target script label
     * @param category  bookmark category
     * @param note      authoring note
     * @return created/updated bookmark
     */
    public LabelBookmarkManager.LabelBookmark addBookmark(String labelName, String category, String note) {
        return bookmarkManager.addOrUpdate(labelName, category, note);
    }

    /**
     * Jumps to a bookmarked label in both the embedded script editor and active preview session ({@code #0102}).
     *
     * @param labelName target label name
     */
    public void jumpToBookmark(String labelName) {
        if (labelName == null || labelName.isBlank()) {
            return;
        }
        if (lastScannedAst != null && lastScannedAst.labels().containsKey(labelName)) {
            VJavaAst.LabelNode node = lastScannedAst.labels().get(labelName);
            if (node.sourceFile() != null && !node.sourceFile().isBlank()) {
                openFileInEditor(getActiveProjectRoot().resolve(node.sourceFile()), Math.max(1, node.lineNumber()));
            }
        }
        ScriptSession session = api != null ? api.getService(ScriptSession.class) : null;
        if (session != null && session.getRuntime().getProjectAst().labels().containsKey(labelName)) {
            session.getRuntime().jumpToLabel(labelName);
            syncLiveRuntimePanels();
        }
    }

    /**
     * Runs {@link ExportPreFlightChecker} checklist validation on the active project ({@code #0103}).
     *
     * @return preflight validation report
     */
    public ExportPreFlightChecker.PreFlightReport runPreFlightCheck() {
        return preFlightChecker.check(getActiveProjectRoot());
    }

    /**
     * Inspects a packed {@code .vpak} archive via {@link VpakManifestInspector} ({@code #0104}).
     *
     * @param vpakFile archive path
     * @return inspection report
     * @throws IOException if reading archive fails
     */
    public VpakManifestInspector.VpakInspectionReport inspectVpakArchive(Path vpakFile) throws IOException {
        return new VpakManifestInspector().inspect(vpakFile);
    }

    /**
     * Builds editable Translation Matrix rows for {@code targetLanguage} ({@code #0105}).
     *
     * @param targetLanguage target language identifier
     * @return translation matrix rows
     */
    public List<StudioReportAndQaService.TranslationMatrixRow> buildTranslationMatrix(String targetLanguage) {
        if (lastScannedAst == null) {
            return List.of();
        }
        return reportAndQaService.buildTranslationMatrix(lastScannedAst, targetLanguage);
    }

    /**
     * Ranks label profiling hotspots from an active {@link ScriptProfiler} ({@code #0106}).
     *
     * @param profiler script profiler
     * @param limit    max entries
     * @return sorted list of {@link StudioReportAndQaService.ProfilerHotspot}
     */
    public List<StudioReportAndQaService.ProfilerHotspot> getProfilerHotspots(ScriptProfiler profiler, int limit) {
        return reportAndQaService.rankHotspots(profiler, limit);
    }

    /**
     * Exports a full ZIP diagnostic bundle containing logs, lint report, AST summary, and system info
     * ({@code #0107}).
     *
     * @param outputZip destination ZIP file path
     * @return path to exported ZIP archive
     * @throws IOException if ZIP assembly fails
     */
    public Path exportDiagnosticZipBundle(Path outputZip) throws IOException {
        return diagnosticBundleExporter.exportZipBundle(outputZip, api, lastLintReport, lastScannedAst, Map.of());
    }

    /**
     * Sets the minimum severity level shown in the Studio live console ({@code #0108}).
     *
     * @param minLevel minimum log severity
     */
    public void setConsoleMinLevel(EngineLogger.Level minLevel) {
        this.consoleMinLevel = minLevel != null ? minLevel : EngineLogger.Level.DEBUG;
        refreshInspectorTextViews();
    }

    /**
     * Filters the Studio live console by subsystem category (empty string disables filter) ({@code #0108}).
     *
     * @param category subsystem filter text
     */
    public void setConsoleCategoryFilter(String category) {
        this.consoleCategoryFilter = category != null ? category.trim() : "";
        refreshInspectorTextViews();
    }

    /**
     * Returns filtered recent log entries matching active level and category filters ({@code #0108}).
     *
     * @param limit maximum entries to return
     * @return filtered log entry list
     */
    public List<EngineLogger.LogEntry> getFilteredConsoleEntries(int limit) {
        if (api == null) {
            return List.of();
        }
        List<EngineLogger.LogEntry> raw = api.getLogger().getRecentEntries(Math.max(limit * 4, 64));
        List<EngineLogger.LogEntry> filtered = new ArrayList<>();
        for (EngineLogger.LogEntry entry : raw) {
            if (entry.level().ordinal() < consoleMinLevel.ordinal()) {
                continue;
            }
            if (!consoleCategoryFilter.isEmpty()
                    && !entry.category().toLowerCase(Locale.ROOT).contains(
                    consoleCategoryFilter.toLowerCase(Locale.ROOT))) {
                continue;
            }
            filtered.add(entry);
            if (filtered.size() >= limit) {
                break;
            }
        }
        return List.copyOf(filtered);
    }

    /**
     * Applies a Studio workspace layout preset ({@code DOCKING}, {@code COMPACT}, {@code DEBUG})
     * and persists it in {@link StudioWorkspaceState} ({@code #0109}).
     *
     * @param preset layout preset name
     */
    public void applyLayoutPreset(String preset) {
        workspaceState.setLayoutPreset(preset);
        Map<String, Boolean> vis = workspaceState.getPanelVisibility();
        if (explorerWindowBox != null) {
            explorerWindowBox.setVisible(vis.getOrDefault("explorer", Boolean.TRUE));
        }
        if (lintWindowBox != null) {
            lintWindowBox.setVisible(vis.getOrDefault("linter", Boolean.TRUE));
        }
        if (varsWindowBox != null) {
            varsWindowBox.setVisible(vis.getOrDefault("variables", Boolean.TRUE));
        }
        boolean showFlow = vis.getOrDefault("flowGraph", Boolean.FALSE);
        this.flowGraphVisible = showFlow;
        if (flowGraphBox != null) {
            flowGraphBox.setVisible(showFlow);
            rebuildFlowGraph();
        }
        layoutWorkspace();
    }

    /**
     * Returns the persistent Studio workspace preferences object ({@code #0109}, {@code #0164}).
     *
     * @return workspace state instance
     */
    public StudioWorkspaceState getWorkspaceState() {
        return workspaceState;
    }

    /**
     * Runs {@link AutomatedPlaythroughRunner} asynchronously without blocking Window A ({@code #0110}).
     *
     * @param strategy choice selection strategy
     * @param maxSteps maximum interaction steps
     * @return future yielding {@link AutomatedPlaythroughRunner.PlaythroughReport}
     */
    public CompletableFuture<AutomatedPlaythroughRunner.PlaythroughReport> runPlaythroughAsync(
            AutomatedPlaythroughRunner.ChoiceStrategy strategy,
            int maxSteps
    ) {
        VJavaAst.ProjectAst ast = lastScannedAst;
        if (ast == null) {
            return CompletableFuture.completedFuture(new AutomatedPlaythroughRunner.PlaythroughReport(
                    false, 0, 0, 0, java.util.Set.of(), 0.0, java.util.Optional.of("No project loaded")));
        }
        return CompletableFuture.supplyAsync(() -> {
            AutomatedPlaythroughRunner.PlaythroughReport report =
                    new AutomatedPlaythroughRunner().run(ast, strategy, maxSteps);
            if (api != null) {
                api.getLogger().info("QA", "Playthrough (" + strategy + ") labelCoverage="
                        + String.format(Locale.ROOT, "%.1f%%", report.labelCoveragePercent()));
            }
            return report;
        });
    }

    /**
     * Computes structural differences between two {@link SaveSlotData} snapshots ({@code #0111}).
     *
     * @param before baseline save slot
     * @param after  target save slot
     * @return structured state diff
     */
    public StateSnapshotDiffService.StateDiff diffSaveStates(SaveSlotData before, SaveSlotData after) {
        return new StateSnapshotDiffService().diff(before, after);
    }

    /**
     * Returns context-aware `.vjava` autocomplete suggestions for {@code linePrefix} ({@code #0112}).
     *
     * @param linePrefix line text before cursor
     * @return completion suggestion list
     */
    public List<VJavaAutocompleteEngine.Suggestion> autocompleteAtCursor(String linePrefix) {
        return autocompleteEngine.suggest(lastScannedAst, linePrefix);
    }

    /**
     * Returns real-time script word counts and reading-time statistics ({@code #0115}).
     *
     * @return word count report
     */
    public ScriptWordCountAnalyzer.ScriptStatsReport getWordCountReport() {
        return lastScannedAst != null
                ? wordCountAnalyzer.analyze(lastScannedAst)
                : new ScriptWordCountAnalyzer.ScriptStatsReport(0, 0, 0, 0.0, Map.of());
    }

    /**
     * Captures an updated SHA-256 baseline manifest for project assets ({@code #0116}).
     */
    public void refreshAssetManifestBaseline() {
        Path root = getActiveProjectRoot();
        if (Files.isDirectory(root)) {
            try {
                this.baselineAssetManifest = checksumVerifier.generateManifest(root);
            } catch (IOException ignored) {
                this.baselineAssetManifest = null;
            }
        }
    }

    /**
     * Verifies project assets against the recorded SHA-256 baseline ({@code #0116}).
     *
     * @return verification report showing valid, missing, modified, or unexpected assets
     * @throws IOException if verification fails
     */
    public AssetChecksumVerifier.VerificationReport checkAssetIntegrity() throws IOException {
        if (baselineAssetManifest == null) {
            refreshAssetManifestBaseline();
        }
        if (baselineAssetManifest == null) {
            return new AssetChecksumVerifier.VerificationReport(0, List.of(), List.of(), List.of());
        }
        return checksumVerifier.verify(getActiveProjectRoot(), baselineAssetManifest);
    }

    /**
     * Refreshes the header Git status badge ({@code #0118}).
     */
    public void refreshGitStatusBadge() {
        Path root = getActiveProjectRoot();
        if (!Files.isDirectory(root) || !Files.exists(root.resolve(".git"))) {
            this.gitStatusBadge = "no-git";
            return;
        }
        try {
            Process proc = new ProcessBuilder("git", "status", "--porcelain", "--branch")
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            byte[] outBytes = proc.getInputStream().readAllBytes();
            boolean finished = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                this.gitStatusBadge = "git";
                return;
            }
            if (proc.exitValue() != 0) {
                this.gitStatusBadge = "git";
                return;
            }
            String[] lines = new String(outBytes, StandardCharsets.UTF_8).split("\n");
            String branch = "HEAD";
            int dirty = 0;
            for (String line : lines) {
                if (line.startsWith("## ")) {
                    String header = line.substring(3).trim();
                    int dots = header.indexOf("...");
                    branch = dots > 0 ? header.substring(0, dots) : header;
                } else if (!line.isBlank()) {
                    dirty++;
                }
            }
            this.gitStatusBadge = dirty == 0
                    ? branch + " (clean)"
                    : branch + " (" + dirty + " changed)";
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            this.gitStatusBadge = "git";
        }
    }

    /**
     * Returns the formatted Studio header Git status badge text ({@code #0118}).
     *
     * @return formatted Git badge string
     */
    public String getGitStatusBadge() {
        return gitStatusBadge;
    }

    /**
     * Safely reloads project scripts while retaining {@code lastGoodSession} active if parsing or
     * compilation fails, surfacing a clear diagnostic toast instead of crashing Window B ({@code #0119}).
     *
     * @return {@code true} if reload succeeded cleanly, {@code false} if rolled back to previous session
     */
    public boolean safeReloadScripts() {
        ScriptSession currentSession = api != null ? api.getService(ScriptSession.class) : null;
        if (currentSession != null) {
            this.lastGoodSession = currentSession;
        }
        Path root = getActiveProjectRoot();
        try {
            VJavaAst.ProjectAst rescanned = ProjectScanner.scan(root);
            VJavaLinter.LintReport lint = VJavaLinter.lint(rescanned);
            if (lint.hasErrors()) {
                this.lastLintReport = lint;
                this.lastToastMessage = "Safe-Reload gestoppt (" + lint.errorCount()
                        + " Lint-Fehler) — bisherige Session bleibt aktiv.";
                if (api != null) {
                    api.getLogger().warn("Studio", lastToastMessage);
                }
                return false;
            }
            this.lastScannedAst = rescanned;
            this.lastLintReport = lint;
            this.lastToastMessage = "Safe-Reload erfolgreich.";
            if (api != null) {
                api.requestLiveReload();
            }
            refreshInspectorTextViews();
            return true;
        } catch (Exception ex) {
            this.lastToastMessage = "Safe-Reload Rollback (" + ex.getMessage()
                    + ") — letzte funktionierende ScriptSession bleibt aktiv.";
            if (api != null) {
                api.getLogger().warn("Studio", lastToastMessage);
                if (lastGoodSession != null) {
                    api.registerService(ScriptSession.class, lastGoodSession);
                }
            }
            return false;
        }
    }

    /**
     * Returns the latest Studio status/rollback toast message ({@code #0119}).
     *
     * @return latest toast message
     */
    public String getLastToastMessage() {
        return lastToastMessage;
    }

    /**
     * Toggles the {@code F1} keyboard shortcut cheat-sheet overlay ({@code #0120}).
     */
    public void toggleShortcutOverlay() {
        shortcutOverlayVisible = !shortcutOverlayVisible;
        if (studioScreenManager == null || shortcutOverlayScreen == null) {
            return;
        }
        if (shortcutOverlayVisible) {
            studioScreenManager.showScreen(shortcutOverlayScreen);
        } else {
            studioScreenManager.hideScreenImmediate(SHORTCUT_OVERLAY_SCREEN_NAME);
        }
    }

    /**
     * Returns whether the {@code F1} keyboard shortcut overlay is currently open ({@code #0120}).
     *
     * @return {@code true} if shortcut overlay is visible
     */
    public boolean isShortcutOverlayVisible() {
        return shortcutOverlayVisible;
    }

    /**
     * Closes open modal Studio overlays ({@code Escape}) ({@code #0120}).
     */
    public void dismissOverlays() {
        shortcutOverlayVisible = false;
        if (studioScreenManager != null) {
            studioScreenManager.hideScreenImmediate(SHORTCUT_OVERLAY_SCREEN_NAME);
            studioScreenManager.hideScreenImmediate(COMMAND_PALETTE_SCREEN_NAME);
            studioScreenManager.hideScreenImmediate(NEW_PROJECT_DIALOG_NAME);
        }
    }

    /**
     * Updates the Studio High-DPI UI scaling factor clamped to {@code [0.80, 1.50]} ({@code #0121}).
     *
     * @param scale scaling multiplier (e.g., {@code 1.25f})
     */
    public void setUiScale(float scale) {
        workspaceState.setUiScale(scale);
        if (studioScreenManager != null) {
            float s = workspaceState.getUiScale();
            studioScreenManager.setLogicalSize(1920.0f / s, 1080.0f / s);
            layoutWorkspace();
        }
    }

    /**
     * Returns the current Studio High-DPI UI scale factor ({@code #0121}).
     *
     * @return UI scale factor in {@code [0.80, 1.50]}
     */
    public float getUiScale() {
        return workspaceState.getUiScale();
    }

    /**
     * Switches the active Studio project directory without restarting Studio ({@code #0122}).
     *
     * @param targetProjectRoot existing project root directory
     */
    public void switchActiveProject(Path targetProjectRoot) {
        Objects.requireNonNull(targetProjectRoot, "targetProjectRoot must not be null");
        Path normalized = targetProjectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("Project directory does not exist: " + normalized);
        }
        this.activeProjectRoot = normalized;
        workspaceState.recordOpenedProject(normalized);
        this.hotReloadWatcher = autoReloadEnabled ? new HotReloadWatcher(normalized) : null;
        if (this.hotReloadWatcher != null) {
            this.hotReloadWatcher.initializeSnapshot();
        }
        refreshProjectAndLint();
        if (lastScannedAst != null && lastScannedAst.descriptor() != null && api != null) {
            api.setProjectDescriptor(lastScannedAst.descriptor());
        }
        refreshAssetManifestBaseline();
        refreshGitStatusBadge();
        showWorkspace();
        refreshInspectors();
    }

    /**
     * Applies one of the curated Studio {@link ThemePalette.ThemeVariant} themes immediately
     * across registered {@link WindowBox} and {@link TextButton} controls ({@code #0129}).
     *
     * @param variant target theme variant
     */
    public void applyThemeVariant(ThemePalette.ThemeVariant variant) {
        ThemePalette.ThemeVariant effective = variant != null ? variant : ThemePalette.ThemeVariant.DARK_SLATE;
        this.themeVariant = effective;
        workspaceState.setThemeVariant(effective.name());
        for (WindowBox wb : themedWindowBoxes) {
            wb.setBackgroundColorHex(effective.panelSurface());
            wb.setBorderColorHex(effective.border());
        }
        for (TextButton btn : themedButtons) {
            btn.setBackgroundColorHex(effective.elevated());
            btn.setBorderColorHex(effective.border());
        }
        applyRootScreenBackground(studioMainMenuScreen, effective.canvasBg());
        applyRootScreenBackground(studioRootScreen, effective.canvasBg());
    }

    private static void applyRootScreenBackground(Screen screen, String hex) {
        if (screen != null && screen.getRoot() != null) {
            screen.getRoot().setBackgroundColorHex(hex);
        }
    }

    /**
     * Returns the currently active Studio {@link ThemePalette.ThemeVariant} ({@code #0129}).
     *
     * @return active theme variant
     */
    public ThemePalette.ThemeVariant getThemeVariant() {
        return themeVariant;
    }

    private void refreshLiveTelemetry() {
        int fps = api != null ? api.getClock().getCurrentFps() : 0;
        int texCount = textureManager != null ? textureManager.getCacheSize() : 0;
        int preloadCount = textureManager != null ? textureManager.getPreloadedCount() : 0;
        long usedKb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024L;
        this.liveTelemetryText = String.format(
                Locale.ROOT,
                "%d FPS | %d Tex (%d preloaded) | %d KB Heap",
                fps, texCount, preloadCount, usedKb);
    }

    /**
     * Returns formatted FPS, texture cache, and heap telemetry refreshed on a 0.5s cadence ({@code #0130}).
     *
     * @return formatted telemetry string
     */
    public String getLiveTelemetryText() {
        return liveTelemetryText;
    }

    /**
     * Warps the active preview {@link ScriptSession} directly to the label surrounding the active
     * editor line while keeping state defaults intact ({@code #0133}).
     *
     * @param labelName target label name
     * @return {@code true} if jump succeeded
     */
    public boolean jumpToLabelInPreview(String labelName) {
        ScriptSession session = api != null ? api.getService(ScriptSession.class) : null;
        if (session == null || labelName == null || labelName.isBlank()) {
            return false;
        }
        seedDefaultVariablesFromAst();
        session.getRuntime().jumpToLabel(labelName.trim());
        syncLiveRuntimePanels();
        return true;
    }

    /**
     * Switches the active preview language immediately via {@link LocalizationManager} ({@code #0135}).
     *
     * @param language target language code (e.g., {@code "english"}, {@code "deutsch"})
     */
    public void switchPreviewLanguage(String language) {
        if (api == null || language == null || language.isBlank()) {
            return;
        }
        LocalizationManager loc = api.getService(LocalizationManager.class);
        if (loc != null) {
            loc.setLanguage(language.trim());
        }
        ScriptSession session = api.getService(ScriptSession.class);
        if (session != null) {
            session.getPreferencesState().setLanguage(language.trim());
        }
    }

    /**
     * Enables or disables automatic polling of external {@code .vjava} edits via
     * {@link HotReloadWatcher} ({@code #0136}).
     *
     * @param enabled auto-reload mode flag
     */
    public void setAutoReloadEnabled(boolean enabled) {
        this.autoReloadEnabled = enabled;
        if (enabled && Files.isDirectory(getActiveProjectRoot())) {
            this.hotReloadWatcher = new HotReloadWatcher(getActiveProjectRoot());
            this.hotReloadWatcher.initializeSnapshot();
        } else if (!enabled) {
            this.hotReloadWatcher = null;
        }
    }

    /**
     * Returns whether automatic {@link HotReloadWatcher} polling is active ({@code #0136}).
     *
     * @return {@code true} if enabled
     */
    public boolean isAutoReloadEnabled() {
        return autoReloadEnabled;
    }

    private void pollAutoHotReload() {
        if (!autoReloadEnabled || hotReloadWatcher == null) {
            return;
        }
        if (hotReloadWatcher.pollChanges().hasChanges()) {
            safeReloadScripts();
        }
    }

    /**
     * Exports the active Story Flow Graph as Mermaid ({@code .mmd}) or Graphviz DOT ({@code .dot})
     * ({@code #0138}).
     *
     * @param outputFile target output file path
     * @param asDot      {@code true} for DOT, {@code false} for Mermaid
     * @return written file path
     * @throws IOException if writing fails
     */
    public Path exportFlowGraphFile(Path outputFile, boolean asDot) throws IOException {
        Objects.requireNonNull(outputFile, "outputFile must not be null");
        if (lastScannedAst == null) {
            refreshProjectAndLint();
        }
        ScriptFlowGraphExporter.FlowGraph graph = flowGraphExporter.buildGraph(lastScannedAst);
        String content = asDot ? flowGraphExporter.toDot(graph) : flowGraphExporter.toMermaid(graph);
        Path norm = outputFile.toAbsolutePath().normalize();
        if (norm.getParent() != null) {
            Files.createDirectories(norm.getParent());
        }
        Files.writeString(norm, content, StandardCharsets.UTF_8);
        return norm;
    }

    /**
     * Mutes or unmutes a live OpenAL channel in real time ({@code #0141}).
     *
     * @param channelName channel identifier ({@code music}, {@code sound}, {@code voice}, {@code ambient})
     * @param muted       mute state
     */
    public void setAudioChannelMuted(String channelName, boolean muted) {
        AudioEngine audio = api != null ? api.getService(AudioEngine.class) : null;
        if (audio == null || channelName == null || channelName.isBlank()) {
            return;
        }
        AudioChannel ch = audio.getChannel(channelName);
        if (ch != null) {
            if (muted) {
                ch.setChannelMixGain(0.0f);
            } else {
                audio.syncVolumesFromPreferences();
            }
        }
    }

    /**
     * Configures live Studio accessibility preview (colorblindness simulation matrix and dyslexia-font
     * preference flag) without mutating saved game files on disk ({@code #0144}).
     *
     * @param mode         accessibility matrix mode
     * @param dyslexiaFont whether high-contrast dyslexia font toggle is previewed
     */
    public void setAccessibilityPreview(ColorBlindnessFilter.AccessibilityMode mode, boolean dyslexiaFont) {
        this.accessibilityPreviewMode = mode != null ? mode : ColorBlindnessFilter.AccessibilityMode.NONE;
        this.dyslexiaFontPreview = dyslexiaFont;
        ScriptSession session = api != null ? api.getService(ScriptSession.class) : null;
        if (session != null) {
            session.getPreferencesState().setHighContrastDyslexiaFont(dyslexiaFont);
        }
        if (api != null) {
            api.setProperty("studio.accessibilityMode", accessibilityPreviewMode.name());
        }
    }

    /**
     * Returns the active colorblindness preview mode.
     *
     * @return active colorblindness preview mode
     */
    public ColorBlindnessFilter.AccessibilityMode getAccessibilityPreviewMode() {
        return accessibilityPreviewMode;
    }

    /**
     * Returns whether dyslexia-friendly font preview is active.
     *
     * @return {@code true} if dyslexia font preview is active
     */
    public boolean isDyslexiaFontPreview() {
        return dyslexiaFontPreview;
    }

    /**
     * Jumps directly back to a historical rollback entry from {@link HistoryLog} ({@code #0145}).
     *
     * @param entry history log entry clicked in the rollback timeline
     * @return {@code true} if rollback restored cleanly
     */
    public boolean jumpToRollbackHistoryEntry(HistoryLog.HistoryEntry entry) {
        ScriptSession session = api != null ? api.getService(ScriptSession.class) : null;
        if (session == null || entry == null) {
            return false;
        }
        boolean ok = session.jumpToHistoryEntry(entry);
        syncLiveRuntimePanels();
        refreshInspectors();
        return ok;
    }

    /**
     * Sets the live Project Explorer search filter query ({@code #0154}).
     *
     * @param query substring filter (nullable clears filter)
     */
    public void setExplorerFilterQuery(String query) {
        this.explorerFilterQuery = query != null ? query.trim() : "";
        rebuildExplorerWidgets();
    }

    /**
     * Fast case-insensitive substring filter over arbitrarily large asset/script lists ({@code #0154}).
     *
     * @param items source entries
     * @param query filter substring
     * @return matching entries preserving order
     */
    public List<String> filterExplorerEntries(List<String> items, String query) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return items;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(Math.min(items.size(), 64));
        for (int i = 0, n = items.size(); i < n; i++) {
            String entry = items.get(i);
            if (entry != null && entry.toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(entry);
            }
        }
        return out;
    }

    /**
     * Configures preview dialogue speed multiplier ({@code 0.5x}..{@code 4.0x}) inside Studio without
     * overwriting persisted player save preferences ({@code #0156}).
     *
     * @param multiplier speed multiplier (clamped {@code [0.5f, 4.0f]})
     */
    public void setPreviewSpeedMultiplier(float multiplier) {
        float clamped = Float.isFinite(multiplier) ? Math.max(0.5f, Math.min(4.0f, multiplier)) : 1.0f;
        this.previewSpeedMultiplier = clamped;
        ScriptSession session = api != null ? api.getService(ScriptSession.class) : null;
        if (session != null) {
            session.getPreferencesState().setTextSpeedCps(40.0f * clamped);
        }
    }

    /**
     * Returns the active Studio preview dialogue speed multiplier.
     *
     * @return active Studio preview dialogue speed multiplier
     */
    public float getPreviewSpeedMultiplier() {
        return previewSpeedMultiplier;
    }

    /**
     * Lists all plugins registered in {@link PluginRegistry} with ID, version, and enabled state ({@code #0157}).
     *
     * @return immutable list of plugin metadata lines
     */
    public List<String> listRegisteredPluginsSummary() {
        PluginRegistry registry = api != null ? api.getService(PluginRegistry.class) : null;
        if (registry == null) {
            return List.of();
        }
        List<EnginePlugin> all = registry.listAll();
        List<String> lines = new ArrayList<>(all.size());
        for (EnginePlugin plugin : all) {
            lines.add(plugin.id() + " (" + plugin.displayName() + " v" + plugin.version()
                    + ") [" + (registry.isEnabled(plugin.id()) ? "ENABLED" : "DISABLED") + "]");
        }
        return List.copyOf(lines);
    }

    /**
     * Enables or disables the live keybinding input tester overlay ({@code #0158}).
     *
     * @param visible overlay toggle
     */
    public void setKeybindingTestOverlayVisible(boolean visible) {
        this.keybindingTestOverlayVisible = visible;
    }

    /**
     * Returns whether the keybinding test overlay is enabled.
     *
     * @return {@code true} when keybinding test overlay is enabled
     */
    public boolean isKeybindingTestOverlayVisible() {
        return keybindingTestOverlayVisible;
    }

    /**
     * Records a key press sample into the bounded keybinding test buffer ({@code #0158}).
     *
     * @param keyLabel human-readable key name
     */
    public void recordKeybindingTestKey(String keyLabel) {
        if (keyLabel != null && !keyLabel.isBlank()) {
            recordedTestKeys.add(keyLabel.trim());
            while (recordedTestKeys.size() > 32) {
                recordedTestKeys.remove(0);
            }
        }
    }

    /**
     * Returns an unmodifiable list of recently captured test keys.
     *
     * @return unmodifiable list of recently captured test keys
     */
    public List<String> getRecordedTestKeys() {
        return List.copyOf(recordedTestKeys);
    }

    /**
     * Subscribes the Studio EventBus Live Sniffer if an {@link EngineEventBus} service is registered
     * ({@code #0162}).
     */
    public void subscribeEventBusSniffer() {
        if (api == null || eventBusSnifferSub != null) {
            return;
        }
        EngineEventBus bus = api.getService(EngineEventBus.class);
        if (bus != null) {
            this.eventBusSnifferSub = bus.subscribe(EngineEvent.class, event -> {
                capturedBusEvents.add(event.getClass().getSimpleName() + ": " + event);
                while (capturedBusEvents.size() > 100) {
                    capturedBusEvents.remove(0);
                }
            });
        }
    }

    /**
     * Returns an immutable list of captured {@link EngineEventBus} events.
     *
     * @return immutable list of sniffed {@link EngineEventBus} events
     */
    public List<String> getCapturedBusEvents() {
        return List.copyOf(capturedBusEvents);
    }

    /**
     * Returns whether the Studio onboarding tour is visible.
     *
     * @return {@code true} when the Studio onboarding tour is visible
     */
    public boolean isOnboardingTourVisible() {
        return onboardingTourVisible;
    }

    /**
     * Marks the Studio onboarding tour completed/dismissed and stores preference ({@code #0164}).
     */
    public void completeOnboardingTour() {
        this.onboardingTourVisible = false;
        workspaceState.setOnboardingTourCompleted(true);
    }

    /**
     * Increments the active project's semantic version and updates descriptor &amp; UI views ({@code #0166}).
     *
     * @param bump semantic bump component
     * @return updated version string
     * @throws IOException if persistence fails
     */
    public String bumpActiveProjectVersion(ProjectRefactorService.VersionBump bump) throws IOException {
        String updated = refactorService.bumpProjectVersion(getActiveProjectRoot(), bump);
        if (api != null) {
            api.setProjectDescriptor(ProjectDescriptor.load(getActiveProjectRoot()));
        }
        refreshInspectorTextViews();
        return updated;
    }

    private List<TextButton> activeNavigationButtons() {
        return activeView == StudioView.MAIN_MENU ? mainMenuFocusButtons : toolbarFocusButtons;
    }

    private int activeFocusIndex() {
        return activeView == StudioView.MAIN_MENU ? mainMenuFocusIndex : toolbarFocusIndex;
    }

    private void setActiveFocusIndex(int idx) {
        if (activeView == StudioView.MAIN_MENU) {
            mainMenuFocusIndex = idx;
        } else {
            toolbarFocusIndex = idx;
        }
    }

    /**
     * Moves keyboard focus forward across active Studio Main Menu or Toolbar buttons ({@code Tab})
     * ({@code #0168}).
     */
    public void focusNextButton() {
        List<TextButton> buttons = activeNavigationButtons();
        if (buttons.isEmpty()) {
            return;
        }
        clearButtonFocus(buttons);
        int next = (activeFocusIndex() + 1) % buttons.size();
        setActiveFocusIndex(next);
        buttons.get(next).setFocused(true);
    }

    /**
     * Moves keyboard focus backward across active Studio Main Menu or Toolbar buttons ({@code Shift+Tab})
     * ({@code #0168}).
     */
    public void focusPrevButton() {
        List<TextButton> buttons = activeNavigationButtons();
        if (buttons.isEmpty()) {
            return;
        }
        clearButtonFocus(buttons);
        int cur = activeFocusIndex();
        int prev = cur <= 0 ? buttons.size() - 1 : cur - 1;
        setActiveFocusIndex(prev);
        buttons.get(prev).setFocused(true);
    }

    /**
     * Activates the currently focused Studio button ({@code Enter}) ({@code #0168}).
     */
    public void activateFocusedButton() {
        TextButton focused = getFocusedButton();
        if (focused != null) {
            focused.click();
        }
    }

    private void onWorkspaceEnterPressed() {
        TextButton focused = getFocusedButton();
        if (focused != null) {
            focused.click();
            return;
        }
        commitDirtyVariableInputs();
    }

    /**
     * Returns the currently keyboard-focused {@link TextButton}, or {@code null} if none ({@code #0168}).
     *
     * @return focused button or {@code null}
     */
    public TextButton getFocusedButton() {
        List<TextButton> buttons = activeNavigationButtons();
        int idx = activeFocusIndex();
        if (idx >= 0 && idx < buttons.size() && buttons.get(idx).isFocused()) {
            return buttons.get(idx);
        }
        for (TextButton btn : buttons) {
            if (btn.isFocused()) {
                return btn;
            }
        }
        return null;
    }

    private static void clearButtonFocus(List<TextButton> buttons) {
        for (UiNode btn : buttons) {
            btn.setFocused(false);
        }
    }
}
