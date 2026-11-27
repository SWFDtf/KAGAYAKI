package de.swfdtf.parser;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SceneGraph;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.state.AchievementManager;
import de.swfdtf.state.EndingsManager;
import de.swfdtf.state.GalleryManager;
import de.swfdtf.state.HistoryLog;
import de.swfdtf.state.LocalizationManager;
import de.swfdtf.state.PersistentState;
import de.swfdtf.state.PreferencesState;
import de.swfdtf.state.RevertableStateStore;
import de.swfdtf.state.SaveManager;
import de.swfdtf.state.SaveMigrationService;
import de.swfdtf.state.SaveSlotData;
import de.swfdtf.state.SessionState;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * High-level runtime session coordinator binding {@link ScriptRuntime} to the full 4-tier
 * persistence architecture ({@link SessionState}, {@link RevertableStateStore}, {@link PersistentState},
 * {@link PreferencesState}), {@link HistoryLog}, {@link SaveManager}, {@link LocalizationManager},
 * and meta-game managers ({@link AchievementManager}, {@link GalleryManager}, {@link EndingsManager}).
 */
public final class ScriptSession {

    private VJavaAst.ProjectAst projectAst;
    private final EngineAPI engineApi;
    private final RevertableStateStore stateStore;
    private final PersistentState persistentState;
    private final PreferencesState preferencesState;
    private final SessionState sessionState;
    private final HistoryLog historyLog;
    private final SaveManager saveManager;
    private final SaveMigrationService saveMigrationService;
    private final LocalizationManager localizationManager;
    private final AchievementManager achievementManager;
    private final GalleryManager galleryManager;
    private final EndingsManager endingsManager;
    private final SceneController sceneController;
    private final ScriptRuntime runtime;

    /**
     * Creates a headless-ready {@link ScriptSession} storing saves under {@code project.projectRoot()/saves}.
     *
     * @param project   unified project AST
     * @param engineApi active engine API
     * @throws IOException if loading persistent state or preferences fails
     */
    public ScriptSession(VJavaAst.ProjectAst project, EngineAPI engineApi) throws IOException {
        this(
                project,
                engineApi,
                new SceneController(
                        new SceneGraph(),
                        null,
                        Objects.requireNonNull(engineApi, "engineApi must not be null").getLogger(),
                        Objects.requireNonNull(project, "project must not be null").projectRoot(),
                        false
                ),
                resolveSavesDir(project)
        );
    }

    /**
     * Creates a full {@link ScriptSession} with an explicit {@link SceneGraph}, {@link TextureManager},
     * and saves directory.
     *
     * @param project          unified project AST
     * @param engineApi        active engine API
     * @param sceneGraph       target scene graph
     * @param textureManager   optional texture manager (nullable in headless tests)
     * @param glTextures       whether textures should be uploaded to OpenGL immediately
     * @param savesDir         directory for saves, {@code persistent.dat}, and {@code preferences.json}
     * @throws IOException if loading persistent state or preferences fails
     */
    public ScriptSession(
            VJavaAst.ProjectAst project,
            EngineAPI engineApi,
            SceneGraph sceneGraph,
            TextureManager textureManager,
            boolean glTextures,
            Path savesDir
    ) throws IOException {
        this(
                project,
                engineApi,
                new SceneController(
                        sceneGraph,
                        textureManager,
                        Objects.requireNonNull(engineApi, "engineApi must not be null").getLogger(),
                        Objects.requireNonNull(project, "project must not be null").projectRoot(),
                        glTextures
                ),
                savesDir
        );
    }

    /**
     * Creates a fully wired {@link ScriptSession} around an explicit {@link SceneController} and saves directory.
     *
     * @param project         unified project AST
     * @param engineApi       active engine API
     * @param sceneController stage controller
     * @param savesDir        saves directory path
     * @throws IOException if loading persistent state or preferences fails
     */
    public ScriptSession(
            VJavaAst.ProjectAst project,
            EngineAPI engineApi,
            SceneController sceneController,
            Path savesDir
    ) throws IOException {
        this.projectAst = Objects.requireNonNull(project, "project must not be null");
        this.engineApi = Objects.requireNonNull(engineApi, "engineApi must not be null");
        this.sceneController = Objects.requireNonNull(sceneController, "sceneController must not be null");
        Path resolvedSavesDir = Objects.requireNonNull(savesDir, "savesDir must not be null");

        this.stateStore = new RevertableStateStore();
        this.persistentState = PersistentState.load(resolvedSavesDir);
        this.preferencesState = PreferencesState.load(resolvedSavesDir);
        this.sessionState = new SessionState();
        this.historyLog = new HistoryLog();
        this.saveManager = new SaveManager(resolvedSavesDir);
        this.saveMigrationService = new SaveMigrationService();

        String defaultLang = project.descriptor() != null
                ? project.descriptor().getDefaultLanguage()
                : "deutsch";
        this.localizationManager = new LocalizationManager(defaultLang);
        VJavaLocalizationBridge.indexProject(this.localizationManager, project);
        if (preferencesState.getLanguage() != null && !preferencesState.getLanguage().isBlank()) {
            this.localizationManager.setLanguage(preferencesState.getLanguage());
        }

        this.achievementManager = new AchievementManager(persistentState);
        this.galleryManager = new GalleryManager(persistentState);
        this.endingsManager = new EndingsManager(persistentState);

        this.runtime = new ScriptRuntime(project, engineApi, stateStore, persistentState, sceneController);
        this.runtime.setLocalizationManager(localizationManager);

        registerServices();
        wireRollbackAndHistoryHooks();
    }

    private void registerServices() {
        engineApi.registerService(RevertableStateStore.class, stateStore);
        engineApi.registerService(PersistentState.class, persistentState);
        engineApi.registerService(PreferencesState.class, preferencesState);
        engineApi.registerService(SessionState.class, sessionState);
        engineApi.registerService(HistoryLog.class, historyLog);
        engineApi.registerService(SaveManager.class, saveManager);
        engineApi.registerService(SaveMigrationService.class, saveMigrationService);
        engineApi.registerService(LocalizationManager.class, localizationManager);
        engineApi.registerService(AchievementManager.class, achievementManager);
        engineApi.registerService(GalleryManager.class, galleryManager);
        engineApi.registerService(EndingsManager.class, endingsManager);
        engineApi.registerService(ScriptSession.class, this);
    }

    private void wireRollbackAndHistoryHooks() {
        stateStore.setRuntimeHooks(
                runtime::snapshotExecution,
                snap -> runtime.restoreExecution((ScriptRuntime.ExecutionSnapshot) snap)
        );
        runtime.setCheckpointListener(this::recordInteractionCheckpoint);
    }

    private void recordInteractionCheckpoint() {
        String summary = buildCheckpointSummary();
        boolean hadFuture = stateStore.canRollForward();
        long previousCheckpointId = stateStore.getCurrentCheckpoint() != null
                ? stateStore.getCurrentCheckpoint().id()
                : 0L;

        long checkpointId = stateStore.recordCheckpoint(runtime.snapshotExecution(), summary);
        if (hadFuture && previousCheckpointId > 0L) {
            historyLog.truncateAfterCheckpoint(previousCheckpointId);
        }

        ScriptRuntime.DialoguePrompt dlg = runtime.getActiveDialogue();
        if (dlg != null) {
            boolean alreadyLoggedAtCheckpoint = historyLog.getEntries().stream()
                    .anyMatch(e -> e.checkpointId() == checkpointId);
            if (!alreadyLoggedAtCheckpoint) {
                historyLog.append(
                        checkpointId,
                        dlg.lineId(),
                        dlg.speakerName(),
                        dlg.speakerColorHex(),
                        dlg.interpolatedText(),
                        dlg.voicePath()
                );
            }
        }
    }

    private String buildCheckpointSummary() {
        ScriptRuntime.DialoguePrompt dlg = runtime.getActiveDialogue();
        if (dlg != null) {
            String text = dlg.interpolatedText();
            return text.length() > 64 ? text.substring(0, 64) + "..." : text;
        }
        ScriptRuntime.MenuPrompt menu = runtime.getActiveMenu();
        if (menu != null) {
            return menu.caption() != null ? "Menu: " + menu.caption() : "Menu";
        }
        ScriptRuntime.InputPrompt input = runtime.getActiveInput();
        if (input != null) {
            return "Input: " + input.prompt();
        }
        return runtime.getCurrentLabel() != null ? runtime.getCurrentLabel() : "checkpoint";
    }

    /**
     * Compiles and starts script execution at the project's configured entry label.
     */
    public void start() {
        stateStore.clear();
        historyLog.clear();
        sessionState.reset();
        runtime.start();
    }

    /**
     * Hot-swaps updated labels from {@code updatedProject} during an active session while preserving
     * {@link RevertableStateStore} variables and re-indexing localization entries ({@code #0079}).
     *
     * @param updatedProject updated project AST (must not be {@code null})
     * @return hot-swap summary reported by {@link ScriptRuntime#hotSwapProject}
     */
    public ScriptRuntime.HotSwapResult hotSwapProject(VJavaAst.ProjectAst updatedProject) {
        Objects.requireNonNull(updatedProject, "updatedProject must not be null");
        this.projectAst = updatedProject;
        VJavaLocalizationBridge.indexProject(localizationManager, updatedProject);
        return runtime.hotSwapProject(updatedProject);
    }

    /**
     * Compiles and starts script execution at {@code entryLabel}.
     *
     * @param entryLabel starting label name
     */
    public void start(String entryLabel) {
        stateStore.clear();
        historyLog.clear();
        sessionState.reset();
        runtime.start(entryLabel);
    }

    /**
     * Advances dialogue or pauses to the next interaction point.
     */
    public void advance() {
        runtime.advance();
    }

    /**
     * Selects a menu choice option by 0-based index.
     *
     * @param optionIndex 0-based option index
     */
    public void choose(int optionIndex) {
        runtime.choose(optionIndex);
    }

    /**
     * Submits player text for an active input prompt.
     *
     * @param text player input
     */
    public void submitInput(String text) {
        runtime.submitInput(text);
    }

    /**
     * Updates session playtime and runtime timers/timeouts.
     *
     * @param dt delta time in seconds
     */
    public void update(double dt) {
        sessionState.updatePlaytime(dt);
        runtime.update(dt);
    }

    /**
     * Rolls back one interaction step (MouseWheel Up / {@code PageUp}), respecting {@code block_rollback}.
     *
     * @return {@code true} if rollback succeeded
     */
    public boolean rollback() {
        return stateStore.rollback();
    }

    /**
     * Rolls forward one interaction step along an intact trail (MouseWheel Down / {@code PageDown}).
     *
     * @return {@code true} if roll-forward succeeded
     */
    public boolean rollForward() {
        return stateStore.rollForward();
    }

    /**
     * Jumps directly back to the checkpoint associated with {@code entry} from {@link HistoryLog},
     * respecting {@code block_rollback} barriers.
     *
     * @param entry history log entry to jump to
     * @return {@code true} if jump succeeded
     */
    public boolean jumpToHistoryEntry(HistoryLog.HistoryEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        return stateStore.jumpToCheckpoint(entry.checkpointId());
    }

    /**
     * Captures the complete current session into a serializable {@link SaveSlotData}.
     *
     * @param slotId      target slot ID
     * @param customName  optional custom save name
     * @param thumbWidth  thumbnail width ({@code <= 0} omits thumbnail)
     * @param thumbHeight thumbnail height ({@code <= 0} omits thumbnail)
     * @param thumbRgba   optional straight RGBA8 thumbnail pixels (nullable)
     * @return serializable save slot payload
     */
    public SaveSlotData createSaveSlotData(
            String slotId,
            String customName,
            int thumbWidth,
            int thumbHeight,
            byte[] thumbRgba
    ) {
        byte[] pngBytes = null;
        int w = 0;
        int h = 0;
        if (thumbRgba != null && thumbWidth > 0 && thumbHeight > 0) {
            pngBytes = SaveManager.encodeRgbaToPng(thumbWidth, thumbHeight, thumbRgba);
            w = thumbWidth;
            h = thumbHeight;
        }

        ProjectDescriptor desc = projectAst.descriptor();
        String gameVersion = desc != null ? desc.getVersion() : "1.0.0";
        String label = runtime.getCurrentLabel() != null ? runtime.getCurrentLabel() : "";
        String preview = buildCheckpointSummary();
        String displaySlotName = (customName != null && !customName.isBlank()) ? customName : preview;

        SaveSlotData.SaveMetadata metadata = new SaveSlotData.SaveMetadata(
                slotId,
                displaySlotName,
                Instant.now(),
                label,
                preview,
                sessionState.getPlaytimeSeconds(),
                gameVersion,
                w,
                h,
                pngBytes
        );

        SaveSlotData.SavedVoiceBookmark voiceBookmark = null;
        ScriptRuntime.AudioBridge bridge = runtime.getAudioBridge();
        if (bridge != null) {
            String voicePath = bridge.getActiveVoiceAssetPath();
            if (voicePath != null && !voicePath.isBlank()) {
                voiceBookmark = new SaveSlotData.SavedVoiceBookmark(
                        voicePath,
                        Math.max(0.0, bridge.getActiveVoicePlaybackSeconds())
                );
            }
        }

        return new SaveSlotData(
                metadata,
                stateStore.snapshotMap(),
                runtime.toSavedPosition(),
                historyLog.snapshot(),
                voiceBookmark
        );
    }

    /**
     * Saves the live game state to {@code slotId} on disk and updates persistent storage.
     *
     * @param slotId      slot identifier
     * @param customName  optional user save title
     * @param thumbWidth  thumbnail width ({@code 0} if none)
     * @param thumbHeight thumbnail height ({@code 0} if none)
     * @param thumbRgba   RGBA8 pixels (nullable)
     * @throws IOException if writing fails
     */
    public void saveToSlot(String slotId, String customName, int thumbWidth, int thumbHeight, byte[] thumbRgba)
            throws IOException {
        SaveSlotData data = createSaveSlotData(slotId, customName, thumbWidth, thumbHeight, thumbRgba);
        saveManager.saveSlot(slotId, customName, data);
        sessionState.setActiveSaveSlotId(slotId);
        persistentState.save(saveManager.getSavesDir());
    }

    /**
     * Performs Quick-Save ({@code S}) into {@link SaveManager#QUICK_SAVE_SLOT}.
     *
     * @throws IOException if writing fails
     */
    public void quickSave() throws IOException {
        SaveSlotData data = createSaveSlotData(SaveManager.QUICK_SAVE_SLOT, "Quick Save", 0, 0, null);
        saveManager.quickSave(data);
        persistentState.save(saveManager.getSavesDir());
    }

    /**
     * Performs Quick-Load ({@code L}) from {@link SaveManager#QUICK_SAVE_SLOT}.
     *
     * @throws IOException if reading fails
     */
    public void quickLoad() throws IOException {
        loadFromSlot(SaveManager.QUICK_SAVE_SLOT);
    }

    /**
     * Performs rotating Auto-Save into {@code auto_1..auto_3}.
     *
     * @return chosen auto-save slot ID
     * @throws IOException if writing fails
     */
    public String autoSave() throws IOException {
        SaveSlotData data = createSaveSlotData("auto_pending", "Auto Save", 0, 0, null);
        String slot = saveManager.autoSave(data);
        sessionState.markAutoSaved();
        persistentState.save(saveManager.getSavesDir());
        return slot;
    }

    /**
     * Loads a saved slot from disk, restoring custom {@code @->{ ... }<<} Java objects,
     * {@link RevertableStateStore}, {@link HistoryLog}, {@link ScriptRuntime}, and {@link SceneController}.
     *
     * @param slotId target slot ID
     * @throws IOException if reading or deserializing fails
     */
    public void loadFromSlot(String slotId) throws IOException {
        if (runtime.getCompiledBlocks() == null) {
            runtime.compile();
        }
        ClassLoader userCl = runtime.getCompiledBlocks() != null
                ? runtime.getCompiledBlocks().getClassLoader()
                : getClass().getClassLoader();
        SaveSlotData loaded = saveMigrationService.migrateToLatest(saveManager.loadSlot(slotId, userCl));

        stateStore.clear();
        stateStore.restoreMap(loaded.variables());
        historyLog.restore(loaded.historyEntries());
        runtime.restoreSavedPosition(loaded.scriptPosition());
        if (loaded.voiceBookmark() != null
                && loaded.voiceBookmark().assetPath() != null
                && !loaded.voiceBookmark().assetPath().isBlank()
                && runtime.getAudioBridge() != null) {
            runtime.getAudioBridge().resumeVoiceAt(
                    loaded.voiceBookmark().assetPath(),
                    loaded.voiceBookmark().offsetSeconds()
            );
        }
        stateStore.recordCheckpoint(runtime.snapshotExecution(), buildCheckpointSummary());
        if (loaded.metadata() != null) {
            sessionState.setPlaytimeSeconds(loaded.metadata().playtimeSeconds());
            sessionState.setActiveSaveSlotId(loaded.metadata().slotId());
        }
    }

    /**
     * Persists both {@link PersistentState} and {@link PreferencesState} to disk.
     *
     * @throws IOException if file I/O fails
     */
    public void saveMetaState() throws IOException {
        persistentState.save(saveManager.getSavesDir());
        preferencesState.save(saveManager.getSavesDir());
    }

    private static Path resolveSavesDir(VJavaAst.ProjectAst project) {
        Path root = project.projectRoot() != null ? project.projectRoot() : Path.of(".");
        return root.resolve("saves");
    }

    /**
     * Returns the script execution runtime.
     *
     * @return script runtime
     */
    public ScriptRuntime getRuntime() {
        return runtime;
    }

    /**
     * Returns the revertable state store.
     *
     * @return revertable state store
     */
    public RevertableStateStore getStateStore() {
        return stateStore;
    }

    /**
     * Returns the persistent state storage.
     *
     * @return persistent state storage
     */
    public PersistentState getPersistentState() {
        return persistentState;
    }

    /**
     * Returns the user preferences state.
     *
     * @return user preferences state
     */
    public PreferencesState getPreferencesState() {
        return preferencesState;
    }

    /**
     * Returns the session state.
     *
     * @return session state
     */
    public SessionState getSessionState() {
        return sessionState;
    }

    /**
     * Returns the dialogue history log.
     *
     * @return dialogue history log
     */
    public HistoryLog getHistoryLog() {
        return historyLog;
    }

    /**
     * Returns the save slot manager.
     *
     * @return save slot manager
     */
    public SaveManager getSaveManager() {
        return saveManager;
    }

    /**
     * Returns the save schema migration service applied on live slot loads.
     *
     * @return save migration service
     */
    public SaveMigrationService getSaveMigrationService() {
        return saveMigrationService;
    }

    /**
     * Returns the localization manager.
     *
     * @return localization manager
     */
    public LocalizationManager getLocalizationManager() {
        return localizationManager;
    }

    /**
     * Returns the achievement manager.
     *
     * @return achievement manager
     */
    public AchievementManager getAchievementManager() {
        return achievementManager;
    }

    /**
     * Returns the gallery manager.
     *
     * @return gallery manager
     */
    public GalleryManager getGalleryManager() {
        return galleryManager;
    }

    /**
     * Returns the endings manager.
     *
     * @return endings manager
     */
    public EndingsManager getEndingsManager() {
        return endingsManager;
    }

    /**
     * Returns the stage scene controller.
     *
     * @return stage scene controller
     */
    public SceneController getSceneController() {
        return sceneController;
    }

    /**
     * Returns the engine API facade.
     *
     * @return engine API facade
     */
    public EngineAPI getEngineApi() {
        return engineApi;
    }
}
