package de.swfdtf.ui;

import de.swfdtf.audio.AmbientCrossfadeMixer;
import de.swfdtf.audio.AudioCueRegistry;
import de.swfdtf.audio.AudioDuckingController;
import de.swfdtf.audio.AudioEngine;
import de.swfdtf.audio.ClosedCaptionQueue;
import de.swfdtf.audio.LipSyncAnalyzer;
import de.swfdtf.audio.TextToSpeechService;
import de.swfdtf.audio.VoicePlaybackBookmark;
import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.GameHost;
import de.swfdtf.core.InputManager;
import de.swfdtf.graphics.AnimationEngine;
import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.IRenderPipeline;
import de.swfdtf.graphics.ObjectMotionController;
import de.swfdtf.graphics.RenderMode;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SceneGraph;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.graphics.Transition;
import de.swfdtf.graphics.TransitionManager;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.state.PreferencesState;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Full gameplay runtime coordinator hosted by {@link EngineApplication}.
 *
 * <p>Wires {@link ProjectScanner}, {@link ScriptSession}, {@link AudioEngine},
 * {@link LipSyncAnalyzer}, {@link TextToSpeechService}, {@link AnimationEngine},
 * {@link TransitionManager}, {@link FontManager}, {@link TextureManager}, {@link ScreenManager},
 * all three dialogue screens (ADV/NVL/Bubble), all built-in Dark-Slate screens,
 * accessibility shortcuts, and (in Studio mode only) dynamic developer overlays.</p>
 */
public final class GameRuntimeCoordinator implements GameHost {

    private static final String LIVE_DIRECTOR_CLASS = "de.swfdtf.tools.LiveDirectorOverlay";
    private static final String DEVELOPER_INSPECTORS_CLASS = "de.swfdtf.tools.DeveloperInspectors";

    private EngineApplication app;
    private EngineAPI api;
    private boolean glEnabled;

    private TextureManager textureManager;
    private FontManager fontManager;
    private AudioEngine audioEngine;
    private LipSyncAnalyzer lipSyncAnalyzer;
    private TextToSpeechService ttsService;
    private AnimationEngine animationEngine;
    private TransitionManager transitionManager;
    private ScreenManager screenManager;
    private ScriptSession session;

    private AdvDialogueScreen advScreen;
    private NvlDialogueScreen nvlScreen;
    private BubbleDialogueScreen bubbleScreen;
    private QuickMenuScreen quickMenuScreen;
    private ChoiceMenuScreen choiceMenuScreen;
    private InputOverlayScreen inputOverlayScreen;
    private MainMenuScreen mainMenuScreen;
    private SaveLoadScreen saveLoadScreen;
    private PreferencesScreen preferencesScreen;
    private ControlsScreen controlsScreen;
    private AboutScreen aboutScreen;
    private HistoryLogScreen historyLogScreen;
    private GalleryScreen galleryScreen;
    private MusicRoomScreen musicRoomScreen;
    private EndingsTreeScreen endingsTreeScreen;
    private AchievementsScreen achievementsScreen;
    private NotificationToastOverlay toastOverlay;

    private Screen liveDirectorScreen;
    private Screen developerInspectorsScreen;

    private final boolean[] prevKeys = new boolean[512];
    private String lastSpokenLineId = "";
    private boolean autoStartMainMenu = true;
    private boolean initialized = false;

    /**
     * Creates a new {@code GameRuntimeCoordinator} instance.
     */
    public GameRuntimeCoordinator() {
    }

    /**
     * Creates a new {@code GameRuntimeCoordinator} instance.
     *
     * @param autoStartMainMenu the autoStartMainMenu argument
     */
    public GameRuntimeCoordinator(boolean autoStartMainMenu) {
        this.autoStartMainMenu = autoStartMainMenu;
    }

    @Override
    public void init(EngineApplication app, boolean glEnabled) {
        this.app = Objects.requireNonNull(app, "app must not be null");
        this.api = app.getApi();
        this.glEnabled = glEnabled;

        Path projectRoot = api.getConfig().projectDir().toAbsolutePath().normalize();
        this.textureManager = new TextureManager(api.getLogger(), projectRoot);
        this.fontManager = new FontManager(projectRoot.resolve("assets/fonts"), true);
        this.animationEngine = new AnimationEngine();
        this.transitionManager = new TransitionManager();
        this.lipSyncAnalyzer = new LipSyncAnalyzer();
        this.ttsService = new TextToSpeechService();
        this.ttsService.setOsCommandExecutionEnabled(!api.getConfig().headless());

        this.screenManager = new ScreenManager();
        this.screenManager.setLogicalSize(api.getConfig().logicalWidth(), api.getConfig().logicalHeight());

        VJavaAst.ProjectAst projectAst = loadProjectAst(projectRoot);
        if (projectAst.descriptor() != null) {
            api.setProjectDescriptor(projectAst.descriptor());
        }

        SceneGraph sceneGraph = app.getSceneGraph();
        SceneController sceneController = new SceneController(
                sceneGraph, textureManager, api.getLogger(), projectRoot, glEnabled
        );
        sceneController.setTransitionListener((context, expr) -> handleSceneTransition(expr));

        Path savesDir = projectRoot.resolve("saves");
        try {
            this.session = new ScriptSession(projectAst, api, sceneController, savesDir);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to initialize ScriptSession: " + ex.getMessage(), ex);
        }

        this.audioEngine = new AudioEngine(
                projectRoot,
                session.getPreferencesState(),
                session.getLocalizationManager(),
                api.getLogger(),
                api.getConfig().headless()
        );
        this.audioEngine.init();
        this.session.getRuntime().setAudioBridge(this.audioEngine.createScriptAudioBridge());

        applyPreferencesToSubsystems(session.getPreferencesState());
        registerCoreServices();
        buildAndRegisterScreens();

        if (api.getConfig().isStudioMode()) {
            attachStudioToolsIfPresent();
        }

        if (autoStartMainMenu && mainMenuScreen != null) {
            screenManager.showScreen(mainMenuScreen);
        }

        this.initialized = true;
    }

    private void handleSceneTransition(String transitionExpr) {
        if (transitionExpr == null || transitionExpr.isBlank()) {
            return;
        }
        Transition transition;
        try {
            transition = Transition.parse(transitionExpr);
        } catch (IllegalArgumentException ex) {
            api.logWarn("Ignoring invalid transition expression '" + transitionExpr + "': " + ex.getMessage());
            return;
        }
        if (glEnabled && app.getActivePipeline() instanceof RenderPipeline2D pipeline2D && pipeline2D.isInitialized()) {
            if (!transitionManager.isGlInitialized()) {
                transitionManager.initGl(pipeline2D.getLogicalWidth(), pipeline2D.getLogicalHeight());
            }
            if (pipeline2D.getOffscreenFramebuffer() != null && pipeline2D.getOffscreenFramebuffer().isInitialized()) {
                transitionManager.captureFromFbo(pipeline2D.getOffscreenFramebuffer());
            }
        }
        transitionManager.startTransition(transition);
    }

    private VJavaAst.ProjectAst loadProjectAst(Path projectRoot) {
        if (Files.isDirectory(projectRoot)) {
            return ProjectScanner.scan(projectRoot);
        }
        return new VJavaAst.ProjectAst(
                projectRoot,
                api.getProjectDescriptor(),
                java.util.List.of(),
                java.util.Map.of(),
                java.util.Map.of(),
                java.util.Map.of(),
                java.util.Map.of(),
                java.util.Map.of(),
                java.util.Map.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of()
        );
    }

    private void registerCoreServices() {
        api.registerService(TextureManager.class, textureManager);
        api.registerService(FontManager.class, fontManager);
        api.registerService(AudioEngine.class, audioEngine);
        api.registerService(AudioDuckingController.class, audioEngine.getDuckingController());
        api.registerService(AmbientCrossfadeMixer.class, audioEngine.getAmbientCrossfadeMixer());
        api.registerService(AudioCueRegistry.class, audioEngine.getCueRegistry());
        api.registerService(ClosedCaptionQueue.class, audioEngine.getClosedCaptionQueue());
        api.registerService(VoicePlaybackBookmark.class, audioEngine.getVoiceBookmark());
        api.registerService(LipSyncAnalyzer.class, lipSyncAnalyzer);
        api.registerService(TextToSpeechService.class, ttsService);
        api.registerService(AnimationEngine.class, animationEngine);
        api.registerService(TransitionManager.class, transitionManager);
        api.registerService(ScreenManager.class, screenManager);
    }

    private void buildAndRegisterScreens() {
        this.advScreen = new AdvDialogueScreen(session, screenManager, audioEngine, ttsService);
        this.nvlScreen = new NvlDialogueScreen(session);
        this.bubbleScreen = new BubbleDialogueScreen(session);
        this.quickMenuScreen = new QuickMenuScreen(session, screenManager);
        this.choiceMenuScreen = new ChoiceMenuScreen(session);
        this.inputOverlayScreen = new InputOverlayScreen(session);
        this.mainMenuScreen = new MainMenuScreen(session, screenManager);
        this.saveLoadScreen = new SaveLoadScreen(session, screenManager);
        this.preferencesScreen = new PreferencesScreen(session, screenManager);
        this.preferencesScreen.setFontManager(fontManager);
        this.controlsScreen = new ControlsScreen(screenManager);
        this.aboutScreen = new AboutScreen(session, screenManager);
        this.historyLogScreen = new HistoryLogScreen(session, screenManager, audioEngine);
        this.galleryScreen = new GalleryScreen(session, screenManager);
        this.musicRoomScreen = new MusicRoomScreen(session, screenManager, audioEngine);
        this.endingsTreeScreen = new EndingsTreeScreen(session, screenManager);
        this.achievementsScreen = new AchievementsScreen(session, screenManager);
        this.toastOverlay = new NotificationToastOverlay(session, screenManager);

        screenManager.registerScreen(advScreen);
        screenManager.registerScreen(nvlScreen);
        screenManager.registerScreen(bubbleScreen);
        screenManager.registerScreen(quickMenuScreen);
        screenManager.registerScreen(choiceMenuScreen);
        screenManager.registerScreen(inputOverlayScreen);
        screenManager.registerScreen(mainMenuScreen);
        screenManager.registerScreen(saveLoadScreen);
        screenManager.registerScreen(preferencesScreen);
        screenManager.registerScreen(controlsScreen);
        screenManager.registerScreen(aboutScreen);
        screenManager.registerScreen(historyLogScreen);
        screenManager.registerScreen(galleryScreen);
        screenManager.registerScreen(musicRoomScreen);
        screenManager.registerScreen(endingsTreeScreen);
        screenManager.registerScreen(achievementsScreen);
        screenManager.showScreen(toastOverlay);

        // Register custom .vjava screen definitions
        for (VJavaAst.ScreenDef def : session.getRuntime().getProjectAst().screens().values()) {
            Screen custom = ScreenAstBuilder.build(def, session.getRuntime(), action -> handleAstScreenAction(action));
            screenManager.registerScreen(custom);
        }
    }

    private void attachStudioToolsIfPresent() {
        try {
            Class<?> directorClass = Class.forName(LIVE_DIRECTOR_CLASS);
            Object directorObj = directorClass.getConstructor(ScriptSession.class, ScreenManager.class)
                    .newInstance(session, screenManager);
            if (directorObj instanceof Screen s) {
                s.setVisible(false);
                screenManager.registerScreen(s);
                liveDirectorScreen = s;
                api.registerService(Screen.class, s);
            }
        } catch (ReflectiveOperationException ignored) {
            // Standalone build excludes tools if desired
        }

        try {
            Class<?> inspClass = Class.forName(DEVELOPER_INSPECTORS_CLASS);
            Object inspObj = inspClass.getConstructor(ScriptSession.class, ScreenManager.class)
                    .newInstance(session, screenManager);
            if (inspObj instanceof Screen s) {
                s.setVisible(true);
                screenManager.registerScreen(s);
                developerInspectorsScreen = s;
                if (liveDirectorScreen != null) {
                    try {
                        inspClass.getMethod("setLinkedDirector", Class.forName(LIVE_DIRECTOR_CLASS))
                                .invoke(s, liveDirectorScreen);
                    } catch (ReflectiveOperationException ignored) {
                    }
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private void handleAstScreenAction(String action) {
        if (action == null || action.isBlank()) {
            return;
        }
        String trimmed = action.trim();
        if (trimmed.startsWith("jump:")) {
            session.getRuntime().jumpToLabel(trimmed.substring("jump:".length()).trim());
        } else if (trimmed.startsWith("show_screen:")) {
            screenManager.showScreen(trimmed.substring("show_screen:".length()).trim());
        } else if (trimmed.startsWith("hide_screen:")) {
            screenManager.hideScreen(trimmed.substring("hide_screen:".length()).trim());
        } else if ("start".equalsIgnoreCase(trimmed) || "Start()".equalsIgnoreCase(trimmed)) {
            screenManager.hideScreen(MainMenuScreen.SCREEN_NAME);
            session.start();
        } else if ("quit".equalsIgnoreCase(trimmed) || "Quit()".equalsIgnoreCase(trimmed)) {
            api.requestQuit();
        } else if ("QuickSave()".equalsIgnoreCase(trimmed)) {
            try {
                session.quickSave();
                toastOverlay.pushToast("Quick Save", "Saved to Quick Slot");
            } catch (IOException ex) {
                api.logError("QuickSave failed: " + ex.getMessage());
            }
        } else if ("QuickLoad()".equalsIgnoreCase(trimmed)) {
            try {
                if (session.getSaveManager().slotExists("quick")) {
                    session.quickLoad();
                    toastOverlay.pushToast("Quick Load", "Loaded Quick Slot");
                }
            } catch (IOException ex) {
                api.logError("QuickLoad failed: " + ex.getMessage());
            }
        } else {
            String targetArg = extractQuotedScreenArg(trimmed);
            if (trimmed.startsWith("Jump(") && targetArg != null) {
                screenManager.hideScreen(MainMenuScreen.SCREEN_NAME);
                session.getRuntime().jumpToLabel(targetArg);
            } else if (trimmed.startsWith("ShowMenu(") && targetArg != null) {
                String resolved = resolveBuiltinScreenAlias(targetArg);
                screenManager.showScreen(resolved);
            } else if (trimmed.startsWith("Hide(") && targetArg != null) {
                screenManager.hideScreen(resolveBuiltinScreenAlias(targetArg));
            } else if ("Return()".equalsIgnoreCase(trimmed)) {
                Screen topModal = screenManager.getTopModalScreen();
                if (topModal != null && !MainMenuScreen.SCREEN_NAME.equals(topModal.getName())) {
                    screenManager.hideScreen(topModal.getName());
                }
            }
        }
    }

    private static String extractQuotedScreenArg(String expr) {
        int first = expr.indexOf('"');
        int last = expr.lastIndexOf('"');
        if (first >= 0 && last > first) {
            return expr.substring(first + 1, last).trim();
        }
        return null;
    }

    private static String resolveBuiltinScreenAlias(String screenName) {
        return switch (screenName) {
            case "save_screen", "load_screen" -> SaveLoadScreen.SCREEN_NAME;
            case "preferences_screen" -> PreferencesScreen.SCREEN_NAME;
            default -> screenName;
        };
    }

    @Override
    public void update(double dt, InputManager input) {
        if (!initialized) {
            return;
        }
        session.update(dt);
        audioEngine.update(dt);
        animationEngine.update(dt);
        transitionManager.update(dt);
        ObjectMotionController.MotionInput motionInput = (input != null && !screenManager.isModalActive())
                ? extractMotionInput(input)
                : ObjectMotionController.MotionInput.NONE;
        session.getSceneController().updateMotion(dt, motionInput);
        lipSyncAnalyzer.updateFromRms(audioEngine.getActiveVoiceRms(), dt);

        if (input != null) {
            handleGlobalShortcuts(input);
        }

        syncDialogueAndOverlayScreens();
        screenManager.update(dt, input);
    }

    private ObjectMotionController.MotionInput extractMotionInput(InputManager input) {
        boolean up = input.isKeyDown(GLFW.GLFW_KEY_W) || input.isKeyDown(GLFW.GLFW_KEY_UP);
        boolean down = input.isKeyDown(GLFW.GLFW_KEY_S) || input.isKeyDown(GLFW.GLFW_KEY_DOWN);
        boolean left = input.isKeyDown(GLFW.GLFW_KEY_A) || input.isKeyDown(GLFW.GLFW_KEY_LEFT);
        boolean right = input.isKeyDown(GLFW.GLFW_KEY_D) || input.isKeyDown(GLFW.GLFW_KEY_RIGHT);
        boolean jump = input.wasKeyJustPressed(GLFW.GLFW_KEY_SPACE)
                || input.wasKeyJustPressed(GLFW.GLFW_KEY_W)
                || input.wasKeyJustPressed(GLFW.GLFW_KEY_UP);
        return new ObjectMotionController.MotionInput(up, down, left, right, jump);
    }

    private void handleGlobalShortcuts(InputManager input) {
        boolean shift = input.isShiftDown();
        boolean ctrl = input.isCtrlDown();

        if (justPressed(input, GLFW.GLFW_KEY_V) && !ctrl && !shift && !screenManager.isModalActive()) {
            PreferencesState prefs = session.getPreferencesState();
            prefs.setSelfVoicing(!prefs.isSelfVoicing());
            ttsService.setEnabled(prefs.isSelfVoicing());
            toastOverlay.pushToast("Accessibility", "Self-Voicing: " + (prefs.isSelfVoicing() ? "ON" : "OFF"));
        }

        if (shift && justPressed(input, GLFW.GLFW_KEY_R) && api.getConfig().isStudioMode()) {
            onReload();
            return;
        }

        boolean wasdActive = session.getSceneController().getMotionController().hasActiveWasd();

        if (!wasdActive && justPressed(input, GLFW.GLFW_KEY_D) && !shift && !ctrl && api.getConfig().isStudioMode()
                && liveDirectorScreen != null && !screenManager.isModalActive()) {
            liveDirectorScreen.setVisible(!liveDirectorScreen.isVisible());
            if (liveDirectorScreen.isVisible()) {
                liveDirectorScreen.onShow();
            }
        }

        if (shift && justPressed(input, GLFW.GLFW_KEY_B) && bubbleScreen != null) {
            bubbleScreen.toggleEditorMode();
        }

        if (developerInspectorsScreen != null && api.getConfig().isStudioMode()) {
            int mods = shift ? GLFW.GLFW_MOD_SHIFT : 0;
            if (shift && justPressed(input, GLFW.GLFW_KEY_I)) {
                developerInspectorsScreen.triggerKeyBindings(GLFW.GLFW_KEY_I, mods);
            }
            if (shift && justPressed(input, GLFW.GLFW_KEY_C)) {
                developerInspectorsScreen.triggerKeyBindings(GLFW.GLFW_KEY_C, mods);
            }
            if (shift && justPressed(input, GLFW.GLFW_KEY_O)) {
                developerInspectorsScreen.triggerKeyBindings(GLFW.GLFW_KEY_O, mods);
            }
        }

        if (screenManager.isModalActive()) {
            if (justPressed(input, GLFW.GLFW_KEY_ESCAPE)) {
                Screen topModal = screenManager.getTopModalScreen();
                if (topModal != null && !MainMenuScreen.SCREEN_NAME.equals(topModal.getName())) {
                    screenManager.hideScreen(topModal.getName());
                }
            }
            return;
        }

        // In-game shortcuts when no modal screen blocks input
        if (justPressed(input, GLFW.GLFW_KEY_PAGE_UP)) {
            session.rollback();
        } else if (justPressed(input, GLFW.GLFW_KEY_PAGE_DOWN)) {
            session.rollForward();
        } else if (!wasdActive && justPressed(input, GLFW.GLFW_KEY_S) && !ctrl && !shift) {
            try {
                session.quickSave();
                toastOverlay.pushToast("Quick Save", "Saved to Quick Slot");
            } catch (IOException ex) {
                api.logError("QuickSave failed: " + ex.getMessage());
            }
        } else if (justPressed(input, GLFW.GLFW_KEY_L) && !ctrl && !shift) {
            try {
                if (session.getSaveManager().slotExists("quick")) {
                    session.quickLoad();
                    toastOverlay.pushToast("Quick Load", "Loaded Quick Slot");
                }
            } catch (IOException ex) {
                api.logError("QuickLoad failed: " + ex.getMessage());
            }
        } else if (justPressed(input, GLFW.GLFW_KEY_H) && !ctrl && !shift) {
            historyLogScreen.refreshEntries();
            screenManager.showScreen(historyLogScreen);
        } else if (!wasdActive && justPressed(input, GLFW.GLFW_KEY_A) && !ctrl && !shift) {
            advScreen.toggleAuto();
            toastOverlay.setAutoPillActive(advScreen.isAutoActive());
        } else if (justPressed(input, GLFW.GLFW_KEY_ESCAPE)) {
            screenManager.showScreen(mainMenuScreen);
        } else if ((justPressed(input, GLFW.GLFW_KEY_SPACE) || justPressed(input, GLFW.GLFW_KEY_ENTER))
                && !ctrl && !shift) {
            advanceCurrentDialogueMode();
        }

        if (ctrl && advScreen != null) {
            advScreen.setSkipActive(true);
            toastOverlay.setSkipPillActive(true);
        } else if (advScreen != null && !input.isCtrlDown() && advScreen.isSkipActive()) {
            // Leave skip toggleable from QuickMenu too; hold Ctrl activates fast skip
        }
    }

    private void advanceCurrentDialogueMode() {
        ScriptRuntime.Status status = session.getRuntime().getStatus();
        if (status == ScriptRuntime.Status.WAITING_DIALOGUE || status == ScriptRuntime.Status.PAUSING) {
            String mode = resolveDialogueMode();
            switch (mode) {
                case "NVL" -> nvlScreen.handlePrimaryAdvance();
                case "BUBBLE" -> bubbleScreen.handlePrimaryAdvance();
                default -> advScreen.handlePrimaryAdvance();
            }
        }
    }

    private void syncDialogueAndOverlayScreens() {
        if (mainMenuScreen.isVisible()) {
            advScreen.setVisible(false);
            nvlScreen.setVisible(false);
            bubbleScreen.setVisible(false);
            quickMenuScreen.setVisible(false);
            choiceMenuScreen.setVisible(false);
            inputOverlayScreen.setVisible(false);
            return;
        }

        ScriptRuntime.Status status = session.getRuntime().getStatus();
        String mode = resolveDialogueMode();
        boolean showDialogue = status == ScriptRuntime.Status.WAITING_DIALOGUE
                || status == ScriptRuntime.Status.WAITING_MENU
                || status == ScriptRuntime.Status.PAUSING;

        if ("NVL".equals(mode) || (session.getRuntime().isNvlMode() && !"BUBBLE".equals(mode))) {
            advScreen.setVisible(false);
            bubbleScreen.setVisible(false);
            nvlScreen.setVisible(showDialogue);
            if (showDialogue) {
                nvlScreen.syncFromRuntime(false);
            }
        } else if ("BUBBLE".equals(mode)) {
            advScreen.setVisible(false);
            nvlScreen.setVisible(false);
            bubbleScreen.setVisible(showDialogue);
            if (showDialogue) {
                bubbleScreen.syncFromRuntime(false);
            }
        } else {
            nvlScreen.setVisible(false);
            bubbleScreen.setVisible(false);
            advScreen.setVisible(showDialogue);
            if (showDialogue) {
                advScreen.syncFromRuntime(false);
            }
        }

        quickMenuScreen.setVisible(showDialogue);
        if (status == ScriptRuntime.Status.WAITING_MENU) {
            choiceMenuScreen.setVisible(true);
            choiceMenuScreen.syncFromRuntime(false);
        } else {
            choiceMenuScreen.setVisible(false);
        }

        if (status == ScriptRuntime.Status.WAITING_INPUT) {
            inputOverlayScreen.setVisible(true);
            inputOverlayScreen.syncFromRuntime(false);
        } else {
            inputOverlayScreen.setVisible(false);
        }

        ScriptRuntime.DialoguePrompt dlg = session.getRuntime().getActiveDialogue();
        if (dlg != null && !Objects.equals(dlg.lineId(), lastSpokenLineId)) {
            lastSpokenLineId = dlg.lineId();
            if (ttsService.isEnabled()) {
                ttsService.speak(dlg.interpolatedText());
            }
        }
    }

    private String resolveDialogueMode() {
        String mode = session.getPreferencesState().getDialogueMode();
        if (mode == null || mode.isBlank()) {
            return "ADV";
        }
        return mode.trim().toUpperCase(Locale.ROOT);
    }

    private void applyPreferencesToSubsystems(PreferencesState prefs) {
        fontManager.setDyslexiaFriendlyMode(prefs.isHighContrastDyslexiaFont());
        fontManager.setGlobalTextScale(prefs.getUiTextScale());
        ttsService.setEnabled(prefs.isSelfVoicing());
        if ("3D".equalsIgnoreCase(prefs.getRenderMode())) {
            app.switchRenderPipeline(RenderMode.MODE_3D);
        }
    }

    private boolean justPressed(InputManager input, int key) {
        if (key < 0 || key >= prevKeys.length) {
            return false;
        }
        boolean down = input.isKeyDown(key);
        boolean result = down && !prevKeys[key];
        prevKeys[key] = down;
        return result;
    }

    @Override
    public void renderUi(double elapsedSeconds) {
        if (!initialized || !glEnabled) {
            return;
        }
        IRenderPipeline active = app.getActivePipeline();
        if (active instanceof RenderPipeline2D pipeline2D) {
            screenManager.render(pipeline2D, fontManager, textureManager, elapsedSeconds);
        }
    }

    @Override
    public void onReload() {
        Path projectRoot = api.getConfig().projectDir().toAbsolutePath().normalize();
        if (!Files.isDirectory(projectRoot)) {
            return;
        }
        VJavaAst.ProjectAst rescanned = ProjectScanner.scan(projectRoot);
        ScriptRuntime.ExecutionSnapshot snapshot = session.getRuntime().snapshotExecution();
        java.util.Map<String, Object> vars = session.getStateStore().snapshotMap();
        try {
            ScriptSession reloaded = new ScriptSession(
                    rescanned,
                    api,
                    app.getSceneGraph(),
                    textureManager,
                    glEnabled,
                    projectRoot.resolve("saves")
            );
            reloaded.getRuntime().compile();
            reloaded.getStateStore().restoreMap(vars);
            reloaded.getRuntime().setAudioBridge(audioEngine.createScriptAudioBridge());
            reloaded.getSceneController().setTransitionListener((context, expr) -> handleSceneTransition(expr));
            reloaded.getRuntime().restoreExecution(snapshot);
            this.session = reloaded;
            api.logInfo("Live Reload completed cleanly for project: " + projectRoot.getFileName());
            if (toastOverlay != null) {
                toastOverlay.pushToast("Live Reload", "Reloaded .vjava scripts cleanly");
            }
        } catch (Exception ex) {
            api.logError("Live Reload failed: " + ex.getMessage(), ex);
            if (toastOverlay != null) {
                toastOverlay.pushToast("Reload Error", ex.getMessage());
            }
        }
    }

    @Override
    public void dispose() {
        try {
            if (session != null) {
                session.saveMetaState();
            }
        } catch (IOException ex) {
            if (api != null) {
                api.logWarn("Failed to save meta state on shutdown: " + ex.getMessage());
            }
        }
        if (ttsService != null) {
            ttsService.dispose();
        }
        if (audioEngine != null) {
            audioEngine.dispose();
        }
        if (transitionManager != null) {
            transitionManager.disposeGl();
        }
        if (fontManager != null) {
            fontManager.dispose();
        }
        if (textureManager != null) {
            textureManager.dispose();
        }
    }

    /**
     * Returns the session value.
     *
     * @return the resulting value
     */
    public ScriptSession getSession() {
        return session;
    }

    /**
     * Returns the screenManager value.
     *
     * @return the resulting value
     */
    public ScreenManager getScreenManager() {
        return screenManager;
    }

    /**
     * Returns the audioEngine value.
     *
     * @return the resulting value
     */
    public AudioEngine getAudioEngine() {
        return audioEngine;
    }

    /**
     * Returns the fontManager value.
     *
     * @return the resulting value
     */
    public FontManager getFontManager() {
        return fontManager;
    }

    /**
     * Returns the textureManager value.
     *
     * @return the resulting value
     */
    public TextureManager getTextureManager() {
        return textureManager;
    }

    /**
     * Returns the animationEngine value.
     *
     * @return the resulting value
     */
    public AnimationEngine getAnimationEngine() {
        return animationEngine;
    }

    /**
     * Returns the transitionManager value.
     *
     * @return the resulting value
     */
    public TransitionManager getTransitionManager() {
        return transitionManager;
    }

    /**
     * Returns the lipSyncAnalyzer value.
     *
     * @return the resulting value
     */
    public LipSyncAnalyzer getLipSyncAnalyzer() {
        return lipSyncAnalyzer;
    }

    /**
     * Returns the ttsService value.
     *
     * @return the resulting value
     */
    public TextToSpeechService getTtsService() {
        return ttsService;
    }

    /**
     * Returns the mainMenuScreen value.
     *
     * @return the resulting value
     */
    public MainMenuScreen getMainMenuScreen() {
        return mainMenuScreen;
    }

    /**
     * Returns the advScreen value.
     *
     * @return the resulting value
     */
    public AdvDialogueScreen getAdvScreen() {
        return advScreen;
    }

    /**
     * Returns the nvlScreen value.
     *
     * @return the resulting value
     */
    public NvlDialogueScreen getNvlScreen() {
        return nvlScreen;
    }

    /**
     * Returns the bubbleScreen value.
     *
     * @return the resulting value
     */
    public BubbleDialogueScreen getBubbleScreen() {
        return bubbleScreen;
    }

    /**
     * Returns the choiceMenuScreen value.
     *
     * @return the resulting value
     */
    public ChoiceMenuScreen getChoiceMenuScreen() {
        return choiceMenuScreen;
    }

    /**
     * Returns the preferencesScreen value.
     *
     * @return the resulting value
     */
    public PreferencesScreen getPreferencesScreen() {
        return preferencesScreen;
    }

    /**
     * Returns the toastOverlay value.
     *
     * @return the resulting value
     */
    public NotificationToastOverlay getToastOverlay() {
        return toastOverlay;
    }
}
