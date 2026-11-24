package de.swfdtf.tools;

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
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.ui.HBox;
import de.swfdtf.ui.Screen;
import de.swfdtf.ui.ScreenManager;
import de.swfdtf.ui.TextButton;
import de.swfdtf.ui.TextWidget;
import de.swfdtf.ui.ThemePalette;
import de.swfdtf.ui.VBox;
import de.swfdtf.ui.Viewport;
import de.swfdtf.ui.WindowBox;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        /** Project Hub / Main Menu showing engine & project versions, New Project, and Recent Projects. */
        MAIN_MENU,
        /** Full 3-column Studio IDE workspace with Explorer, Linter, Live Variables, and Console. */
        WORKSPACE
    }

    /** Screen name of the Studio Main Menu / Project Hub. */
    public static final String MAIN_MENU_SCREEN_NAME = "studio_main_menu";

    /** Screen name of the 3-column Studio Workspace. */
    public static final String WORKSPACE_SCREEN_NAME = "studio_workspace";

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

    private Path activeProjectRoot;
    private StudioView activeView = StudioView.MAIN_MENU;
    private ProjectTemplateGenerator.TemplateType selectedTemplateType = ProjectTemplateGenerator.TemplateType.FULL_DEMO;
    private final StudioWorkspaceState workspaceState = StudioWorkspaceState.defaults();

    private VJavaAst.ProjectAst lastScannedAst;
    private VJavaLinter.LintReport lastLintReport;
    private StandaloneBuilder.BuildResult lastExportResult;

    private TextWidget headerStatusWidget;
    private TextWidget explorerListWidget;
    private TextWidget lintSummaryWidget;
    private TextWidget variablesListWidget;
    private TextWidget consoleLogWidget;
    private TextButton renderModeButton;

    private TextWidget mainMenuVersionWidget;
    private TextWidget mainMenuActiveProjectWidget;
    private TextButton templateToggleButton;
    private TextWidget mainMenuRecentWidget;
    private TextWidget mainMenuStatusWidget;

    private double elapsedSeconds = 0.0;
    private double refreshTimer = 0.0;
    private boolean disposed = false;

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
        showMainMenu();
        refreshProjectAndLint();
        refreshInspectors();

        api.registerService(StudioWindowController.class, this);
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
        studioScreenManager.registerScreen(studioMainMenuScreen);
    }

    private void buildStudioUi() {
        studioRootScreen = new Screen(WORKSPACE_SCREEN_NAME, SceneLayer.Z_SCREENS, false);
        VBox rootLayout = new VBox();
        rootLayout.setSpacing(12.0f)
                .setPadding(24.0f)
                .setBackgroundColorHex(ThemePalette.CANVAS_BG);

        // Top Toolbar
        HBox toolbar = new HBox();
        toolbar.setSpacing(10.0f).setPreferredHeight(64.0f);
        toolbar.setPadding(10.0f).setBackgroundColorHex(ThemePalette.PANEL_SURFACE)
                .setBorderColorHex(ThemePalette.BORDER).setBorderThickness(1.0f).setCornerRadius(8.0f);

        headerStatusWidget = new TextWidget(EngineVersion.formatBanner());
        headerStatusWidget.setFontSize(20.0f).setColorHex(ThemePalette.TEXT_PRIMARY).setPreferredWidth(470.0f);

        TextButton mainMenuBtn = new TextButton("Hauptmenü", this::showMainMenu);
        mainMenuBtn.setPreferredSize(150.0f, 44.0f);

        TextButton newTemplateBtn = new TextButton("Neues Projekt", this::onNewProjectClicked);
        newTemplateBtn.setPreferredSize(180.0f, 44.0f);

        TextButton lintBtn = new TextButton("Run Lint", this::runLint);
        lintBtn.setPreferredSize(130.0f, 44.0f);

        TextButton reloadBtn = new TextButton("Reload (Shift+R)", () -> api.requestLiveReload());
        reloadBtn.setPreferredSize(190.0f, 44.0f);

        renderModeButton = new TextButton("Mode: 2D", this::toggleRenderMode);
        renderModeButton.setPreferredSize(150.0f, 44.0f);

        TextButton exportBtn = new TextButton("Export Standalone", () -> {
            Path projectRoot = getActiveProjectRoot();
            exportStandalone(projectRoot.resolve("dist"), null);
        });
        exportBtn.setPreferredSize(210.0f, 44.0f);

        toolbar.addChild(headerStatusWidget);
        toolbar.addChild(mainMenuBtn);
        toolbar.addChild(newTemplateBtn);
        toolbar.addChild(lintBtn);
        toolbar.addChild(reloadBtn);
        toolbar.addChild(renderModeButton);
        toolbar.addChild(exportBtn);

        // Main 3-column workspace
        HBox columns = new HBox();
        columns.setSpacing(16.0f).setPreferredHeight(640.0f);

        WindowBox explorerBox = new WindowBox("Project Explorer & Assets");
        explorerBox.setPreferredSize(580.0f, 640.0f);
        Viewport explorerVp = new Viewport();
        explorerListWidget = new TextWidget("No project scanned.");
        explorerListWidget.setFontSize(17.0f).setColorHex(ThemePalette.TEXT_PRIMARY);
        explorerVp.addChild(explorerListWidget);
        explorerBox.addChild(explorerVp);

        WindowBox lintBox = new WindowBox("Static Analysis (VJavaLinter)");
        lintBox.setPreferredSize(640.0f, 640.0f);
        Viewport lintVp = new Viewport();
        lintSummaryWidget = new TextWidget("Lint not run.");
        lintSummaryWidget.setFontSize(17.0f).setColorHex(ThemePalette.TEXT_PRIMARY);
        lintVp.addChild(lintSummaryWidget);
        lintBox.addChild(lintVp);

        WindowBox varsBox = new WindowBox("Live State Variables");
        varsBox.setPreferredSize(620.0f, 640.0f);
        Viewport varsVp = new Viewport();
        variablesListWidget = new TextWidget("(empty)");
        variablesListWidget.setFontSize(17.0f).setColorHex(ThemePalette.TEXT_PRIMARY);
        varsVp.addChild(variablesListWidget);
        varsBox.addChild(varsVp);

        columns.addChild(explorerBox);
        columns.addChild(lintBox);
        columns.addChild(varsBox);

        // Bottom Console
        WindowBox consoleBox = new WindowBox("Studio & Engine Console");
        consoleBox.setPreferredHeight(300.0f);
        Viewport consoleVp = new Viewport();
        consoleLogWidget = new TextWidget("");
        consoleLogWidget.setFontSize(16.0f).setColorHex(ThemePalette.TEXT_MUTED);
        consoleVp.addChild(consoleLogWidget);
        consoleBox.addChild(consoleVp);

        rootLayout.addChild(toolbar);
        rootLayout.addChild(columns);
        rootLayout.addChild(consoleBox);

        studioRootScreen.setRoot(rootLayout);
        studioScreenManager.registerScreen(studioRootScreen);
    }

    @Override
    public void update(double dt) {
        elapsedSeconds += dt;
        refreshTimer += dt;
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
     * Generates a playable starter template in {@code targetDir} using {@link ProjectTemplateGenerator.TemplateType#FULL_DEMO}
     * and refreshes diagnostics.
     */
    public Path generateStarterProject(Path targetDir, String title, String renderMode) {
        return createNewProject(targetDir, title, renderMode, ProjectTemplateGenerator.TemplateType.FULL_DEMO, false);
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
        return createNewProject(targetDir, title, "2D", templateType, true);
    }

    private Path createNewProject(
            Path targetDir,
            String title,
            String renderMode,
            ProjectTemplateGenerator.TemplateType templateType,
            boolean openWorkspaceView
    ) {
        Objects.requireNonNull(targetDir, "targetDir must not be null");
        ProjectTemplateGenerator.TemplateType effectiveType = templateType != null
                ? templateType
                : ProjectTemplateGenerator.TemplateType.FULL_DEMO;
        try {
            Path created = ProjectTemplateGenerator.generate(targetDir, title, renderMode, effectiveType);
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

    private void toggleTemplateType() {
        selectedTemplateType = switch (selectedTemplateType) {
            case FULL_DEMO -> ProjectTemplateGenerator.TemplateType.STARTER;
            case STARTER -> ProjectTemplateGenerator.TemplateType.MINIMAL;
            case MINIMAL -> ProjectTemplateGenerator.TemplateType.FULL_DEMO;
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
     */
    public VJavaLinter.LintReport runLint() {
        refreshProjectAndLint();
        return lastLintReport;
    }

    /**
     * Switches the owning {@link EngineApplication} between {@link RenderMode#MODE_2D}
     * and {@link RenderMode#MODE_3D} (activating the controlled 3D stub warning).
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
     */
    public StandaloneBuilder.BuildResult exportStandalone(Path outputDir, Path optionalStandaloneJar) {
        Path projectRoot = getActiveProjectRoot();
        try {
            StandaloneBuilder.BuildResult result = StandaloneBuilder.build(projectRoot, outputDir, optionalStandaloneJar);
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
            this.lastScannedAst = ProjectScanner.scan(projectRoot);
            this.lastLintReport = VJavaLinter.lint(this.lastScannedAst);
        } else {
            this.lastScannedAst = null;
            this.lastLintReport = new VJavaLinter.LintReport(List.of(), 0, 0);
        }
        refreshInspectorTextViews();
    }

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

    /** @return currently active top-level Studio view ({@link StudioView#MAIN_MENU} or {@link StudioView#WORKSPACE}) */
    public StudioView getActiveView() {
        return activeView;
    }

    /** @return currently selected scaffolding template preset */
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

    /** @return formatted engine and {@code .vjava} specification version banner */
    public String getEngineVersionBanner() {
        return EngineVersion.formatBanner();
    }

    /** @return active project semantic version string */
    public String getActiveProjectVersion() {
        ProjectDescriptor desc = api != null ? api.getProjectDescriptor() : null;
        return (desc != null && desc.getVersion() != null && !desc.getVersion().isBlank())
                ? desc.getVersion()
                : EngineVersion.VERSION;
    }

    /** @return active Studio project root directory */
    public Path getActiveProjectRoot() {
        if (activeProjectRoot != null) {
            return activeProjectRoot;
        }
        return api.getConfig().projectDir().toAbsolutePath().normalize();
    }

    /** @return unmodifiable list of recently opened or created project paths */
    public List<String> getRecentProjects() {
        return workspaceState.getRecentProjectPaths();
    }

    public VJavaAst.ProjectAst getLastScannedAst() {
        return lastScannedAst;
    }

    public VJavaLinter.LintReport getLastLintReport() {
        return lastLintReport;
    }

    public StandaloneBuilder.BuildResult getLastExportResult() {
        return lastExportResult;
    }

    public long getStudioWindowHandle() {
        return studioWindowHandle;
    }

    public long getGameWindowHandle() {
        return gameWindowHandle;
    }

    public boolean isGlReady() {
        return glReady;
    }

    public boolean isDisposed() {
        return disposed;
    }

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
