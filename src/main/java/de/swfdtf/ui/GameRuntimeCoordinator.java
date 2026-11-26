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
import de.swfdtf.core.GamepadNavigationRouter;
import de.swfdtf.core.InputManager;
import de.swfdtf.core.KeybindingRemapper;
import de.swfdtf.core.ResourceProvider;
import de.swfdtf.graphics.AnimationEngine;
import de.swfdtf.graphics.ColorBlindnessFilter;
import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.IRenderPipeline;
import de.swfdtf.graphics.ObjectMotionController;
import de.swfdtf.graphics.RenderMode;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SceneGraph;
import de.swfdtf.graphics.SceneNode;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.graphics.Transition;
import de.swfdtf.graphics.TransitionManager;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.state.AutoSaveScheduler;
import de.swfdtf.state.ChapterRegistry;
import de.swfdtf.state.CodexGlossaryManager;
import de.swfdtf.state.PreferencesState;
import de.swfdtf.state.QuoteBook;
import de.swfdtf.state.RelationshipAffinityTracker;
import de.swfdtf.state.SaveSlotData;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.UncheckedIOException;
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
    private static final String COLLISION_DEBUG_TAG = "collision_debug_overlay";
    private static final float ACTION_PROMPT_MAX_DISTANCE = 160.0f;

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
    private AutoSaveScheduler autoSaveScheduler;
    private ScriptRuntime.Status lastObservedRuntimeStatus = ScriptRuntime.Status.READY;

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
    private ClosedCaptionOverlayScreen closedCaptionOverlay;
    private ActionPromptOverlayScreen actionPromptOverlay;
    private ConfirmationDialogScreen confirmationDialogScreen;
    private CodexScreen codexScreen;
    private ChapterSelectScreen chapterSelectScreen;
    private RelationshipOverviewScreen relationshipOverviewScreen;
    private QuoteBookScreen quoteBookScreen;
    private KeybindingRemapper keybindingRemapper;
    private ColorBlindnessFilter colorBlindnessFilter;

    private CodexGlossaryManager codexGlossaryManager;
    private ChapterRegistry chapterRegistry;
    private RelationshipAffinityTracker relationshipTracker;
    private QuoteBook quoteBook;

    private Screen liveDirectorScreen;
    private Screen developerInspectorsScreen;

    private final boolean[] prevKeys = new boolean[512];
    private String lastSpokenLineId = "";
    private boolean autoStartMainMenu = true;
    private boolean collisionDebugOverlayEnabled = false;
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
        ResourceProvider resourceProvider = api.getService(ResourceProvider.class);
        this.textureManager = new TextureManager(api.getLogger(), projectRoot);
        this.textureManager.setResourceProvider(resourceProvider);
        this.fontManager = new FontManager(projectRoot.resolve("assets/fonts"), true);
        this.animationEngine = new AnimationEngine();
        this.transitionManager = new TransitionManager();
        this.lipSyncAnalyzer = new LipSyncAnalyzer();
        this.ttsService = new TextToSpeechService();
        this.ttsService.setOsCommandExecutionEnabled(!api.getConfig().headless());
        this.colorBlindnessFilter = new ColorBlindnessFilter();
        this.keybindingRemapper = createDefaultKeybindingRemapper();

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
        this.autoSaveScheduler = new AutoSaveScheduler(
                session.getSaveManager(),
                () -> this.session != null
                        ? this.session.createSaveSlotData("auto_pending", "Auto Save", 0, 0, null)
                        : null
        );
        this.lastObservedRuntimeStatus = session.getRuntime().getStatus();
        this.codexGlossaryManager = new CodexGlossaryManager(session.getPersistentState());
        this.chapterRegistry = new ChapterRegistry(session.getPersistentState());
        this.relationshipTracker = new RelationshipAffinityTracker(session.getStateStore());
        this.quoteBook = new QuoteBook(session.getPersistentState());

        this.audioEngine = new AudioEngine(
                projectRoot,
                session.getPreferencesState(),
                session.getLocalizationManager(),
                api.getLogger(),
                api.getConfig().headless()
        );
        this.audioEngine.setResourceProvider(resourceProvider);
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
        ResourceProvider provider = api.getService(ResourceProvider.class);
        if (provider != null) {
            return ProjectScanner.scan(projectRoot, provider);
        }
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
        api.registerService(AutoSaveScheduler.class, autoSaveScheduler);
        api.registerService(KeybindingRemapper.class, keybindingRemapper);
        api.registerService(GamepadNavigationRouter.class, screenManager.getGamepadRouter());
        api.registerService(ColorBlindnessFilter.class, colorBlindnessFilter);
        api.registerService(CodexGlossaryManager.class, codexGlossaryManager);
        api.registerService(ChapterRegistry.class, chapterRegistry);
        api.registerService(RelationshipAffinityTracker.class, relationshipTracker);
        api.registerService(QuoteBook.class, quoteBook);
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
        this.controlsScreen = new ControlsScreen(screenManager, keybindingRemapper);
        this.aboutScreen = new AboutScreen(session, screenManager);
        this.historyLogScreen = new HistoryLogScreen(session, screenManager, audioEngine);
        this.galleryScreen = new GalleryScreen(session, screenManager);
        this.musicRoomScreen = new MusicRoomScreen(session, screenManager, audioEngine);
        this.endingsTreeScreen = new EndingsTreeScreen(session, screenManager);
        this.achievementsScreen = new AchievementsScreen(session, screenManager);
        this.toastOverlay = new NotificationToastOverlay(session, screenManager);
        this.closedCaptionOverlay = new ClosedCaptionOverlayScreen(audioEngine.getClosedCaptionQueue());
        this.actionPromptOverlay = new ActionPromptOverlayScreen();
        this.confirmationDialogScreen = new ConfirmationDialogScreen(screenManager);
        this.codexScreen = new CodexScreen(codexGlossaryManager, screenManager);
        this.chapterSelectScreen = new ChapterSelectScreen(chapterRegistry, session, screenManager);
        this.relationshipOverviewScreen = new RelationshipOverviewScreen(relationshipTracker, screenManager);
        this.quoteBookScreen = new QuoteBookScreen(quoteBook, screenManager);

        registerHidden(advScreen);
        registerHidden(nvlScreen);
        registerHidden(bubbleScreen);
        registerHidden(quickMenuScreen);
        registerHidden(choiceMenuScreen);
        registerHidden(inputOverlayScreen);
        registerHidden(mainMenuScreen);
        registerHidden(saveLoadScreen);
        registerHidden(preferencesScreen);
        registerHidden(controlsScreen);
        registerHidden(aboutScreen);
        registerHidden(historyLogScreen);
        registerHidden(galleryScreen);
        registerHidden(musicRoomScreen);
        registerHidden(endingsTreeScreen);
        registerHidden(achievementsScreen);
        registerHidden(closedCaptionOverlay);
        registerHidden(actionPromptOverlay);
        registerHidden(confirmationDialogScreen);
        registerHidden(codexScreen);
        registerHidden(chapterSelectScreen);
        registerHidden(relationshipOverviewScreen);
        registerHidden(quoteBookScreen);
        screenManager.showScreen(toastOverlay);

        // Register custom .vjava screen definitions
        for (VJavaAst.ScreenDef def : session.getRuntime().getProjectAst().screens().values()) {
            Screen custom = ScreenAstBuilder.build(def, session.getRuntime(), action -> handleAstScreenAction(action));
            if ("template_save_screen".equals(def.name()) || "template_load_screen".equals(def.name())) {
                custom = wrapTemplateSaveLoadScreen(def, custom);
            }
            registerHidden(custom);
        }
    }

    private Screen wrapTemplateSaveLoadScreen(VJavaAst.ScreenDef def, Screen base) {
        boolean saveMode = "template_save_screen".equals(def.name());
        java.util.List<TextButton> slotButtons = new java.util.ArrayList<>();
        VBox targetVBox = findFirstVBox(base.getRoot());
        for (int i = 1; i <= 4; i++) {
            final int slotNum = i;
            final String slotId = "slot_" + i;
            TextButton btn = UiMediaSupport.createActionButton("Slot " + i + " — Leer", 340.0f, 36.0f, () -> { });
            slotButtons.add(btn);
            if (targetVBox != null) {
                targetVBox.addChild(btn);
            } else if (base.getRoot() != null) {
                base.getRoot().addChild(btn);
            }
        }
        Screen wrapped = new Screen(base.getName(), base.getZOrder(), base.isModal()) {
            @Override
            public void onShow() {
                refreshTemplateSlotButtons(slotButtons, saveMode);
            }
        };
        wrapped.setRoot(base.getRoot());
        for (Screen.KeyBinding kb : base.getKeyBindings()) {
            wrapped.addKeyBinding(kb.glfwKey(), kb.requiredModsMask(), kb.action());
        }
        refreshTemplateSlotButtons(slotButtons, saveMode);
        return wrapped;
    }

    private void refreshTemplateSlotButtons(java.util.List<TextButton> slotButtons, boolean saveMode) {
        for (int i = 0; i < slotButtons.size(); i++) {
            final int slotNum = i + 1;
            final String slotId = "slot_" + slotNum;
            TextButton btn = slotButtons.get(i);
            boolean exists = session.getSaveManager().slotExists(slotId);
            String slotLabel = "Slot " + slotNum + " — Leer";
            if (exists) {
                SaveSlotData.SaveMetadata meta = null;
                try {
                    meta = session.getSaveManager().readSlotMetadata(slotId).orElse(null);
                } catch (IOException ignored) {
                }
                String name = meta != null && meta.customName() != null && !meta.customName().isBlank()
                        ? meta.customName()
                        : "Belegt";
                slotLabel = "Slot " + slotNum + " — " + name;
            }
            btn.setText(slotLabel);
            btn.setEnabled(saveMode || exists);
            btn.setOnClick(() -> {
                try {
                    if (saveMode) {
                        session.saveToSlot(slotId, "Slot " + slotNum, 0, 0, null);
                        refreshTemplateSlotButtons(slotButtons, true);
                    } else if (session.getSaveManager().slotExists(slotId)) {
                        session.loadFromSlot(slotId);
                        screenManager.hideScreen("template_load_screen");
                        screenManager.hideScreen(MainMenuScreen.SCREEN_NAME);
                    }
                } catch (IOException ex) {
                    api.logError("Template slot action failed: " + ex.getMessage());
                }
            });
        }
    }

    private static VBox findFirstVBox(UiNode node) {
        if (node == null) {
            return null;
        }
        if (node instanceof VBox box) {
            return box;
        }
        for (UiNode child : node.getChildren()) {
            VBox found = findFirstVBox(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void registerHidden(Screen screen) {
        if (screen != null) {
            screen.setVisible(false);
            screenManager.registerScreen(screen);
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
        if (autoSaveScheduler != null && autoSaveScheduler.isEnabled()) {
            ScriptRuntime.Status currentStatus = session.getRuntime().getStatus();
            try {
                if (currentStatus == ScriptRuntime.Status.WAITING_MENU
                        && lastObservedRuntimeStatus != ScriptRuntime.Status.WAITING_MENU) {
                    autoSaveScheduler.forceSave();
                } else {
                    autoSaveScheduler.update(dt);
                }
            } catch (UncheckedIOException ex) {
                api.logWarn("Auto-save skipped due to I/O error: " + ex.getMessage());
            }
            lastObservedRuntimeStatus = currentStatus;
        }
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

        if (collisionDebugOverlayEnabled) {
            syncCollisionDebugOverlay();
        }
        syncDialogueAndOverlayScreens();
        if (closedCaptionOverlay != null) {
            closedCaptionOverlay.syncFromQueue();
        }
        syncActionPromptOverlay();
        screenManager.update(dt, input);
    }

    private ObjectMotionController.MotionInput extractMotionInput(InputManager input) {
        float axisX = input.getGamepadAxis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X);
        float axisY = input.getGamepadAxis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y);
        int quickSaveKey = keybindingRemapper.getKeyCode("QuickSave").orElse(GLFW.GLFW_KEY_S);
        int autoKey = keybindingRemapper.getKeyCode("Auto").orElse(GLFW.GLFW_KEY_A);
        int rightKey = keybindingRemapper.getKeyCode("Right").orElse(GLFW.GLFW_KEY_D);

        boolean up = keybindingRemapper.isActionDown("Up", input) || axisY < 0.0f;
        boolean down = keybindingRemapper.isActionDown("Down", input)
                || (quickSaveKey == GLFW.GLFW_KEY_S && input.isKeyDown(GLFW.GLFW_KEY_S))
                || axisY > 0.0f;
        boolean left = keybindingRemapper.isActionDown("Left", input)
                || (autoKey == GLFW.GLFW_KEY_A && input.isKeyDown(GLFW.GLFW_KEY_A))
                || axisX < 0.0f;
        boolean right = keybindingRemapper.isActionDown("Right", input)
                || (rightKey == GLFW.GLFW_KEY_D && input.isKeyDown(GLFW.GLFW_KEY_RIGHT))
                || axisX > 0.0f;
        int jumpKey = keybindingRemapper.getKeyCode("Jump").orElse(GLFW.GLFW_KEY_UP);
        int upKey = keybindingRemapper.getKeyCode("Up").orElse(GLFW.GLFW_KEY_W);
        boolean jump = input.wasKeyJustPressed(jumpKey)
                || input.wasKeyJustPressed(upKey)
                || input.wasKeyJustPressed(GLFW.GLFW_KEY_SPACE);
        return new ObjectMotionController.MotionInput(up, down, left, right, jump);
    }

    private void handleGlobalShortcuts(InputManager input) {
        boolean shift = input.isShiftDown();
        boolean ctrl = input.isCtrlDown();

        int selfVoicingKey = keybindingRemapper.getKeyCode("SelfVoicing").orElse(GLFW.GLFW_KEY_V);
        if (justPressed(input, selfVoicingKey) && !ctrl && !shift && !screenManager.isModalActive()) {
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

        if (justPressed(input, GLFW.GLFW_KEY_F9)) {
            collisionDebugOverlayEnabled = !collisionDebugOverlayEnabled;
            api.setProperty("collisionDebugOverlay", collisionDebugOverlayEnabled);
            syncCollisionDebugOverlay();
        }

        if (screenManager.isModalActive()) {
            if (justPressed(input, GLFW.GLFW_KEY_ESCAPE)) {
                Screen topModal = screenManager.getTopModalScreen();
                if (topModal instanceof ControlsScreen cs && cs.isCapturingKey()) {
                    return;
                }
                if (topModal != null && !MainMenuScreen.SCREEN_NAME.equals(topModal.getName())) {
                    screenManager.hideScreen(topModal.getName());
                }
            }
            return;
        }

        int rollbackKey = keybindingRemapper.getKeyCode("Rollback").orElse(GLFW.GLFW_KEY_PAGE_UP);
        int rollForwardKey = keybindingRemapper.getKeyCode("RollForward").orElse(GLFW.GLFW_KEY_PAGE_DOWN);
        int quickSaveKey = keybindingRemapper.getKeyCode("QuickSave").orElse(GLFW.GLFW_KEY_S);
        int quickLoadKey = keybindingRemapper.getKeyCode("QuickLoad").orElse(GLFW.GLFW_KEY_L);
        int historyKey = keybindingRemapper.getKeyCode("History").orElse(GLFW.GLFW_KEY_H);
        int autoKey = keybindingRemapper.getKeyCode("Auto").orElse(GLFW.GLFW_KEY_A);
        int advanceKey = keybindingRemapper.getKeyCode("Advance").orElse(GLFW.GLFW_KEY_SPACE);
        int rightKey = keybindingRemapper.getKeyCode("Right").orElse(GLFW.GLFW_KEY_D);
        int skipKey = keybindingRemapper.getKeyCode("Skip").orElse(GLFW.GLFW_KEY_LEFT_CONTROL);

        // In-game shortcuts when no modal screen blocks input
        if (justPressed(input, rollbackKey)) {
            if (!session.rollback() && session.getStateStore().isRollbackBlocked() && toastOverlay != null) {
                toastOverlay.pushToast("Rollback Blocked", "Cannot roll back past a decision barrier.");
            }
        } else if (justPressed(input, rollForwardKey)) {
            session.rollForward();
        } else if (justPressed(input, quickSaveKey) && (!wasdActive || quickSaveKey != GLFW.GLFW_KEY_S)
                && !ctrl && !shift) {
            try {
                session.quickSave();
                toastOverlay.pushToast("Quick Save", "Saved to Quick Slot");
            } catch (IOException ex) {
                api.logError("QuickSave failed: " + ex.getMessage());
            }
        } else if (justPressed(input, quickLoadKey) && (!wasdActive || quickLoadKey != rightKey)
                && !ctrl && !shift) {
            try {
                if (session.getSaveManager().slotExists("quick")) {
                    session.quickLoad();
                    toastOverlay.pushToast("Quick Load", "Loaded Quick Slot");
                }
            } catch (IOException ex) {
                api.logError("QuickLoad failed: " + ex.getMessage());
            }
        } else if (justPressed(input, historyKey) && !ctrl && !shift) {
            historyLogScreen.refreshEntries();
            screenManager.showScreen(historyLogScreen);
        } else if (!ctrl && !shift && justPressed(input, GLFW.GLFW_KEY_B)) {
            ScriptRuntime runtime = session.getRuntime();
            ScriptRuntime.DialoguePrompt prompt = runtime.getActiveDialogue();
            if (runtime.getStatus() == ScriptRuntime.Status.WAITING_DIALOGUE && prompt != null) {
                quoteBook.addQuote(prompt.speakerName(), prompt.interpolatedText(), runtime.getCurrentLabel());
                if (toastOverlay != null) {
                    toastOverlay.pushToast("Quote Book", "Zitat gespeichert");
                }
            }
        } else if (justPressed(input, autoKey) && (!wasdActive || autoKey != GLFW.GLFW_KEY_A)
                && !ctrl && !shift) {
            advScreen.toggleAuto();
            toastOverlay.setAutoPillActive(advScreen.isAutoActive());
        } else if (justPressed(input, GLFW.GLFW_KEY_ESCAPE)) {
            screenManager.showScreen(mainMenuScreen);
        } else if ((justPressed(input, advanceKey)
                || (advanceKey == GLFW.GLFW_KEY_SPACE && justPressed(input, GLFW.GLFW_KEY_ENTER)))
                && !ctrl && !shift) {
            advanceCurrentDialogueMode();
        }

        boolean skipHeld = input.isKeyDown(skipKey) || (skipKey == GLFW.GLFW_KEY_LEFT_CONTROL && ctrl);
        if (skipHeld && advScreen != null) {
            advScreen.setSkipActive(true);
            toastOverlay.setSkipPillActive(true);
        }
    }

    private void syncCollisionDebugOverlay() {
        if (app == null || app.getSceneGraph() == null) {
            return;
        }
        SceneGraph graph = app.getSceneGraph();
        graph.removeByTag(SceneGraph.LAYER_INSPECTOR, COLLISION_DEBUG_TAG);
        if (!collisionDebugOverlayEnabled || session == null) {
            return;
        }
        SceneNode banner = new SceneNode("collision_debug:banner", COLLISION_DEBUG_TAG);
        banner.getTransform().setXpos(16.0f).setYpos(16.0f);
        banner.setSize(220.0f, 28.0f);
        banner.setFillColorRgba(new float[]{0.05f, 0.14f, 0.10f, 0.78f});
        banner.setBorderThickness(2.0f);
        banner.setBorderColorRgba(new float[]{0.15f, 0.95f, 0.55f, 0.95f});
        graph.addNode(SceneGraph.LAYER_INSPECTOR, banner);

        SceneController sc = session.getSceneController();
        ObjectMotionController motion = sc.getMotionController();
        for (java.util.Map.Entry<String, SpriteNode> entry : sc.getActiveSprites().entrySet()) {
            Rect2f box = motion.resolveDebugHitbox(entry.getKey(), entry.getValue());
            SceneNode boxNode = new SceneNode("collision_debug:sprite:" + entry.getKey(), COLLISION_DEBUG_TAG);
            boxNode.getTransform().setXpos(box.x()).setYpos(box.y());
            boxNode.setSize(Math.max(1.0f, box.width()), Math.max(1.0f, box.height()));
            boxNode.setFillColorRgba(new float[]{0.10f, 0.90f, 0.45f, 0.16f});
            boxNode.setBorderThickness(2.0f);
            boxNode.setBorderColorRgba(new float[]{0.15f, 1.0f, 0.50f, 0.92f});
            graph.addNode(SceneGraph.LAYER_INSPECTOR, boxNode);
        }
        for (java.util.Map.Entry<String, Rect2f> zone : motion.getTriggerZoneRects().entrySet()) {
            Rect2f zr = zone.getValue();
            SceneNode zoneNode = new SceneNode("collision_debug:zone:" + zone.getKey(), COLLISION_DEBUG_TAG);
            zoneNode.getTransform().setXpos(zr.x()).setYpos(zr.y());
            zoneNode.setSize(Math.max(1.0f, zr.width()), Math.max(1.0f, zr.height()));
            zoneNode.setFillColorRgba(new float[]{1.0f, 0.85f, 0.15f, 0.14f});
            zoneNode.setBorderThickness(2.0f);
            zoneNode.setBorderColorRgba(new float[]{1.0f, 0.85f, 0.15f, 0.92f});
            graph.addNode(SceneGraph.LAYER_INSPECTOR, zoneNode);
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

    private void syncActionPromptOverlay() {
        if (actionPromptOverlay == null || session == null) {
            return;
        }
        if (screenManager.isModalActive()) {
            actionPromptOverlay.updatePrompt(false, 0.0f, 0.0f, "", "");
            return;
        }
        SceneController sc = session.getSceneController();
        ObjectMotionController motion = sc.getMotionController();
        String playerTag = motion.getPrimaryWasdTag();
        if (playerTag == null) {
            actionPromptOverlay.updatePrompt(false, 0.0f, 0.0f, "", "");
            return;
        }
        SpriteNode playerSprite = sc.getActiveSprite(playerTag);
        if (playerSprite == null) {
            actionPromptOverlay.updatePrompt(false, 0.0f, 0.0f, "", "");
            return;
        }
        float px = playerSprite.getTransform().getXpos();
        float py = playerSprite.getTransform().getYpos();
        SpriteNode nearestPartner = null;
        double nearestDist = Double.MAX_VALUE;
        for (String partnerTag : motion.getInteractivePartnersFor(playerTag)) {
            SpriteNode partner = sc.getActiveSprite(partnerTag);
            if (partner == null) {
                continue;
            }
            double dist = Math.hypot(partner.getTransform().getXpos() - px, partner.getTransform().getYpos() - py);
            if (dist <= ACTION_PROMPT_MAX_DISTANCE && dist < nearestDist) {
                nearestDist = dist;
                nearestPartner = partner;
            }
        }
        if (nearestPartner == null) {
            actionPromptOverlay.updatePrompt(false, 0.0f, 0.0f, "", "");
            return;
        }
        int interactKey = keybindingRemapper.getKeyCode("Interact").orElse(GLFW.GLFW_KEY_E);
        String keyBadge = ControlsScreen.formatKeyName(interactKey);
        actionPromptOverlay.updatePrompt(
                true,
                nearestPartner.getTransform().getXpos(),
                nearestPartner.getTransform().getYpos(),
                keyBadge,
                "Untersuchen"
        );
    }

    private static KeybindingRemapper createDefaultKeybindingRemapper() {
        KeybindingRemapper remapper = new KeybindingRemapper();
        remapper.registerDefault("Advance", GLFW.GLFW_KEY_SPACE);
        remapper.registerDefault("QuickSave", GLFW.GLFW_KEY_S);
        remapper.registerDefault("QuickLoad", GLFW.GLFW_KEY_L);
        remapper.registerDefault("Auto", GLFW.GLFW_KEY_A);
        remapper.registerDefault("Skip", GLFW.GLFW_KEY_LEFT_CONTROL);
        remapper.registerDefault("History", GLFW.GLFW_KEY_H);
        remapper.registerDefault("Rollback", GLFW.GLFW_KEY_PAGE_UP);
        remapper.registerDefault("RollForward", GLFW.GLFW_KEY_PAGE_DOWN);
        remapper.registerDefault("SelfVoicing", GLFW.GLFW_KEY_V);
        remapper.registerDefault("Up", GLFW.GLFW_KEY_W);
        remapper.registerDefault("Down", GLFW.GLFW_KEY_DOWN);
        remapper.registerDefault("Left", GLFW.GLFW_KEY_LEFT);
        remapper.registerDefault("Right", GLFW.GLFW_KEY_D);
        remapper.registerDefault("Jump", GLFW.GLFW_KEY_UP);
        remapper.registerDefault("Interact", GLFW.GLFW_KEY_E);
        return remapper;
    }

    private void applyPreferencesToSubsystems(PreferencesState prefs) {
        fontManager.setDyslexiaFriendlyMode(prefs.isHighContrastDyslexiaFont());
        fontManager.setGlobalTextScale(prefs.getUiTextScale());
        ttsService.setEnabled(prefs.isSelfVoicing());
        api.getInput().setGamepadDeadzone(prefs.getGamepadDeadzone());
        screenManager.getGamepadRouter().setDeadzone(prefs.getGamepadDeadzone());
        colorBlindnessFilter.setMode(prefs.isHighContrastDyslexiaFont()
                ? ColorBlindnessFilter.AccessibilityMode.HIGH_CONTRAST
                : ColorBlindnessFilter.AccessibilityMode.NONE);
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

    /**
     * Returns the wired auto-save scheduler instance.
     *
     * @return active {@link AutoSaveScheduler}, or {@code null} before {@link #init}
     */
    public AutoSaveScheduler getAutoSaveScheduler() {
        return autoSaveScheduler;
    }
}
