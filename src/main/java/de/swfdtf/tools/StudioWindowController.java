package de.swfdtf.tools;

import de.swfdtf.audio.AudioEngine;
import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineLogger;
import de.swfdtf.core.EngineVersion;
import de.swfdtf.core.InputManager;
import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.core.StudioBridge;
import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderMode;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.ConditionEvaluator;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptDebugger;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.parser.VariableWatchpointManager;
import de.swfdtf.parser.WatchpointEvent;
import de.swfdtf.ui.Frame;
import de.swfdtf.ui.HBox;
import de.swfdtf.ui.Screen;
import de.swfdtf.ui.ScreenManager;
import de.swfdtf.ui.TextButton;
import de.swfdtf.ui.TextInput;
import de.swfdtf.ui.TextWidget;
import de.swfdtf.ui.ThemePalette;
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
import java.util.Map;
import java.util.Objects;

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
    private final Deque<List<String>> trashedAssetBatches = new ArrayDeque<>();

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
        registerPaletteCommands();
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

        // Overview columns: Recent Projects + Template Features
        HBox hubColumns = new HBox();
        hubColumns.setSpacing(18.0f).setPreferredHeight(560.0f);

        WindowBox recentBox = new WindowBox("Zuletzt geöffnete Projekte");
        recentBox.setPreferredSize(900.0f, 560.0f);
        Viewport recentVp = new Viewport();
        mainMenuRecentWidget = new TextWidget("(noch keine Projekte)");
        mainMenuRecentWidget.setFontSize(18.0f).setColorHex(ThemePalette.TEXT_PRIMARY);
        recentVp.addChild(mainMenuRecentWidget);
        recentBox.addChild(recentVp);

        WindowBox templateBox = new WindowBox("Vorlage & Engine-Funktionen (" + EngineVersion.VERSION + ")");
        templateBox.setPreferredSize(920.0f, 560.0f);
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

        // Main 3-column workspace (1888 x 780)
        HBox columns = new HBox();
        columns.setSpacing(12.0f);
        columns.setFillHeight(true);
        columns.setPreferredHeight(780.0f);

        // Column 1: Embedded Code Editor (first so visible input 0 is editor), Files, Assets Preview & Cleanup
        WindowBox explorerBox = new WindowBox("Project Explorer, Editor & Assets");
        explorerBox.setPreferredSize(616.0f, 780.0f);
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
        studioRootScreen.addKeyBinding(GLFW.GLFW_KEY_ENTER, this::commitDirtyVariableInputs);
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
    }

    @Override
    public void update(double dt) {
        elapsedSeconds += dt;
        refreshTimer += dt;
        syncLiveRuntimePanels();
        if (refreshTimer >= 0.5) {
            refreshTimer = 0.0;
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
            List<EngineLogger.LogEntry> entries = api.getLogger().getRecentEntries(12);
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
        for (String asset : lastScannedAst.discoveredAssets()) {
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
}
