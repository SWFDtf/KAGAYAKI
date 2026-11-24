package de.swfdtf.tools;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineLogger;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.JavaBlockCompiler;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.parser.VJavaCompileException;
import de.swfdtf.state.HistoryLog;
import de.swfdtf.state.PersistentAccess;
import de.swfdtf.state.SaveSlotData;
import de.swfdtf.state.StateStore;
import de.swfdtf.ui.Fixed;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Unified developer inspector suite ({@code Z = 1000}) providing:
 * <ul>
 *     <li><b>Live Reload ({@code Shift + R})</b>: re-scans {@code .vjava} files, recompiles Java
 *     blocks, and preserves active label, statement index, and {@link StateStore} variables.</li>
 *     <li><b>Style Inspector ({@code Shift + I})</b>: inspects any {@link UiNode} under logical
 *     cursor coordinates {@code (lx, ly)} across {@link ScreenManager}.</li>
 *     <li><b>Coordinate Picker ({@code Shift + C})</b>: tracks logical and normalized coordinates
 *     in {@code 1920x1080}, formats {@code xpos/ypos} and {@code xalign/yalign} snippets, and
 *     records picked coordinate history.</li>
 *     <li><b>Live Java Console ({@code Shift + O})</b>: compiles and executes ad-hoc Java snippets
 *     via {@link JavaBlockCompiler} against live {@link StateStore}, {@link PersistentAccess},
 *     {@link EngineAPI}, {@link SceneController}, and {@code VJavaUserTypes}.</li>
 *     <li><b>Script Warping</b>: jumps cleanly to any script label via
 *     {@link #warpToLabel(ScriptSession, String)}.</li>
 * </ul>
 */
public class DeveloperInspectors extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "developer_inspectors";

    private static final int MAX_LOG_ENTRIES = 200;
    private static final int MAX_PICKED_HISTORY = 100;

    /**
     * Snapshot report describing a {@link UiNode} inspected at a logical canvas coordinate.
     *
     * @param screenName         owning {@link Screen} name
     * @param nodeClass          simple Java class name of the inspected node
     * @param nodeId             node ID (empty string if unset)
     * @param bounds             resolved logical bounds {@code (x, y, width, height)}
     * @param backgroundColorHex configured background hex color (nullable)
     * @param borderColorHex     configured border hex color (nullable)
     * @param borderThickness    border thickness in logical pixels
     * @param cornerRadius       corner radius in logical pixels
     * @param visible            whether the node is visible
     * @param enabled            whether the node is enabled
     * @param hovered            whether the node is currently hovered
     * @param focused            whether the node currently holds keyboard focus
     */
    public record StyleInspectionResult(
            String screenName,
            String nodeClass,
            String nodeId,
            Rect2f bounds,
            String backgroundColorHex,
            String borderColorHex,
            float borderThickness,
            float cornerRadius,
            boolean visible,
            boolean enabled,
            boolean hovered,
            boolean focused
    ) {
        /**
         * Formats this inspection snapshot into a concise multi-line developer report.
         *
         * @return formatted style report
         */
        public String formatReport() {
            String bg = backgroundColorHex != null ? backgroundColorHex : "none";
            String border = borderColorHex != null ? borderColorHex : "none";
            String idPart = (nodeId != null && !nodeId.isBlank()) ? "#" + nodeId : "(no-id)";
            return String.format(
                    Locale.ROOT,
                    "[%s] %s %s\nBounds: (%.1f, %.1f, %.1f, %.1f)\nBg: %s | Border: %s (%.1fpx) | Radius: %.1f\nState: visible=%s enabled=%s hovered=%s focused=%s",
                    screenName,
                    nodeClass,
                    idPart,
                    bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                    bg,
                    border,
                    borderThickness,
                    cornerRadius,
                    visible,
                    enabled,
                    hovered,
                    focused
            );
        }
    }

    /**
     * Captured coordinate pick in logical {@code 1920x1080} and normalized {@code [0..1]} space.
     *
     * @param x               logical X coordinate
     * @param y               logical Y coordinate
     * @param normalizedX     normalized X coordinate ({@code x / logicalWidth})
     * @param normalizedY     normalized Y coordinate ({@code y / logicalHeight})
     * @param xposYposSnippet formatted {@code xpos <x> ypos <y>} snippet
     * @param alignSnippet    formatted {@code xalign <nx> yalign <ny>} snippet
     */
    public record PickedCoordinate(
            float x,
            float y,
            float normalizedX,
            float normalizedY,
            String xposYposSnippet,
            String alignSnippet
    ) {
    }

    /**
     * Result of executing an ad-hoc Java snippet in the Live Java Console.
     *
     * @param success      {@code true} if compilation and execution succeeded without error
     * @param code         executed Java snippet text
     * @param output       captured {@link EngineLogger} output lines
     * @param errorMessage compilation or runtime error message (nullable when {@code success} is true)
     */
    public record ConsoleExecutionResult(
            boolean success,
            String code,
            String output,
            String errorMessage
    ) {
    }

    private final class InspectorRoot extends Fixed {
        @Override
        public UiNode dispatchMouseMove(float lx, float ly) {
            updateCursorPosition(lx, ly);
            if (styleInspectorActive) {
                inspectAt(lx, ly);
            }
            return super.dispatchMouseMove(lx, ly);
        }

        @Override
        protected boolean onMouseDown(float lx, float ly, int button) {
            if (button == 0 && isClickInsideVisibleToolPanel(lx, ly)) {
                return true;
            }
            if (button == 0 && coordinatePickerActive) {
                pickCoordinate(lx, ly);
                return true;
            }
            if (button == 0 && styleInspectorActive) {
                inspectAt(lx, ly);
                return true;
            }
            return false;
        }
    }

    private ScriptSession session;
    private final ScreenManager screenManager;
    private final JavaBlockCompiler javaBlockCompiler = new JavaBlockCompiler();

    private final WindowBox styleInspectorBox;
    private final TextWidget styleInspectorText;

    private final WindowBox coordinatePickerBox;
    private final TextWidget coordinateLiveLabel;
    private final TextWidget coordinateHistoryLabel;

    private final WindowBox consoleBox;
    private final TextInput warpLabelInput;
    private final TextButton warpButton;
    private final TextInput consoleInput;
    private final TextButton consoleExecuteButton;
    private final TextButton reloadButton;
    private final VBox consoleLogBox;
    private final Viewport consoleLogViewport;

    private final List<PickedCoordinate> pickedHistory = new ArrayList<>();
    private final List<String> consoleLog = new ArrayList<>();

    private boolean styleInspectorActive = false;
    private boolean coordinatePickerActive = false;
    private boolean consoleActive = false;

    private StyleInspectionResult lastInspectedStyle;
    private float cursorX = 0.0f;
    private float cursorY = 0.0f;
    private float normalizedX = 0.0f;
    private float normalizedY = 0.0f;
    private int consoleCounter = 0;

    private LiveDirectorOverlay linkedDirector;
    private Consumer<ScriptSession> onLiveReload;

    /**
     * Creates a {@code DeveloperInspectors} overlay bound to {@code session} without a {@link ScreenManager}.
     *
     * @param session active script session
     */
    public DeveloperInspectors(ScriptSession session) {
        this(session, null);
    }

    /**
     * Creates a {@code DeveloperInspectors} overlay bound to {@code session} and {@link ScreenManager}.
     *
     * @param session       active script session
     * @param screenManager UI screen manager (nullable for standalone unit tests)
     */
    public DeveloperInspectors(ScriptSession session, ScreenManager screenManager) {
        super(SCREEN_NAME, SceneLayer.Z_INSPECTOR, false);
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.screenManager = screenManager;

        InspectorRoot root = new InspectorRoot();

        // Style Inspector Panel (Shift + I)
        this.styleInspectorBox = new WindowBox("Style Inspector [Shift+I]");
        this.styleInspectorBox.setPadding(12.0f, 10.0f);
        this.styleInspectorBox.setBackgroundColorHex(ThemePalette.PANEL_GLASS)
                .setBorderColorHex(ThemePalette.ACCENT_HOVER)
                .setBorderThickness(1.5f);
        this.styleInspectorText = createText("Move cursor over any UI widget...", 15.0f, ThemePalette.TEXT_PRIMARY);
        this.styleInspectorBox.addChild(styleInspectorText);
        this.styleInspectorBox.setVisible(false);

        // Coordinate Picker Panel (Shift + C)
        this.coordinatePickerBox = new WindowBox("Coordinate Picker [Shift+C]");
        this.coordinatePickerBox.setPadding(12.0f, 10.0f);
        this.coordinatePickerBox.setBackgroundColorHex(ThemePalette.PANEL_GLASS)
                .setBorderColorHex(ThemePalette.ACCENT_IDLE)
                .setBorderThickness(1.5f);
        VBox pickerContent = new VBox().setSpacing(8.0f).setFillWidth(true);
        this.coordinateLiveLabel = createText("x=0 y=0 | nx=0.000 ny=0.000", 15.0f, ThemePalette.TEXT_PRIMARY);
        TextButton pickNowBtn = createButton("Pick Current", 140.0f, 32.0f, this::pickCurrentCoordinate);
        TextButton clearPicksBtn = createButton("Clear History", 140.0f, 32.0f, this::clearPickedHistory);
        HBox pickerBtns = new HBox().setSpacing(8.0f);
        pickerBtns.addChild(pickNowBtn);
        pickerBtns.addChild(clearPicksBtn);
        this.coordinateHistoryLabel = createText("Click canvas to record coordinates.", 14.0f, ThemePalette.TEXT_MUTED);
        pickerContent.addChild(coordinateLiveLabel);
        pickerContent.addChild(pickerBtns);
        pickerContent.addChild(coordinateHistoryLabel);
        this.coordinatePickerBox.addChild(pickerContent);
        this.coordinatePickerBox.setVisible(false);

        // Live Java Console + Script Warping Panel (Shift + O)
        this.consoleBox = new WindowBox("Live Java Console [Shift+O] & Warp");
        this.consoleBox.setPadding(12.0f, 10.0f);
        this.consoleBox.setBackgroundColorHex(ThemePalette.PANEL_GLASS)
                .setBorderColorHex(ThemePalette.ACTIVE_SELECTED)
                .setBorderThickness(1.5f);

        VBox consoleLayout = new VBox().setSpacing(8.0f).setFillWidth(true);

        HBox warpRow = new HBox().setSpacing(8.0f).setAlignCenterY(true);
        warpRow.setPreferredHeight(38.0f);
        this.warpLabelInput = new TextInput("", text -> warpToLabel(text));
        this.warpLabelInput.setPlaceholder("Label name to warp...");
        this.warpLabelInput.setPreferredSize(340.0f, 36.0f);
        this.warpButton = createButton("Warp", 110.0f, 36.0f, () -> warpToLabel(warpLabelInput.getText()));
        this.reloadButton = createButton("Reload (Shift+R)", 180.0f, 36.0f, this::triggerLiveReload);
        warpRow.addChild(warpLabelInput);
        warpRow.addChild(warpButton);
        warpRow.addChild(reloadButton);

        this.consoleLogBox = new VBox().setSpacing(4.0f).setFillWidth(true);
        this.consoleLogViewport = new Viewport();
        this.consoleLogViewport.setPreferredHeight(300.0f);
        this.consoleLogViewport.setBackgroundColorHex(ThemePalette.CANVAS_BG)
                .setBorderColorHex(ThemePalette.BORDER)
                .setBorderThickness(1.0f)
                .setPadding(8.0f);
        this.consoleLogViewport.addChild(consoleLogBox);

        HBox inputRow = new HBox().setSpacing(8.0f).setAlignCenterY(true);
        inputRow.setPreferredHeight(40.0f);
        this.consoleInput = new TextInput("", text -> executeConsoleSnippet(text));
        this.consoleInput.setPlaceholder("e.g. state.set(\"gold\", 999);");
        this.consoleInput.setPreferredSize(580.0f, 38.0f);
        this.consoleExecuteButton = createButton("Execute", 120.0f, 38.0f, () -> executeConsoleSnippet(consoleInput.getText()));
        this.consoleExecuteButton.setBackgroundColorHex(ThemePalette.ACCENT_IDLE);
        TextButton clearConsoleBtn = createButton("Clear", 90.0f, 38.0f, this::clearConsoleLog);
        inputRow.addChild(consoleInput);
        inputRow.addChild(consoleExecuteButton);
        inputRow.addChild(clearConsoleBtn);

        consoleLayout.addChild(warpRow);
        consoleLayout.addChild(consoleLogViewport);
        consoleLayout.addChild(inputRow);
        this.consoleBox.addChild(consoleLayout);
        this.consoleBox.setVisible(false);

        root.addChildAt(styleInspectorBox, 36.0f, 36.0f, 500.0f, 280.0f);
        root.addChildAt(coordinatePickerBox, 36.0f, 336.0f, 500.0f, 300.0f);
        root.addChildAt(consoleBox, 560.0f, 540.0f, 830.0f, 500.0f);
        setRoot(root);

        addKeyBinding(GLFW.GLFW_KEY_R, GLFW.GLFW_MOD_SHIFT, this::triggerLiveReload);
        addKeyBinding(GLFW.GLFW_KEY_I, GLFW.GLFW_MOD_SHIFT, this::toggleStyleInspector);
        addKeyBinding(GLFW.GLFW_KEY_C, GLFW.GLFW_MOD_SHIFT, this::toggleCoordinatePicker);
        addKeyBinding(GLFW.GLFW_KEY_O, GLFW.GLFW_MOD_SHIFT, this::toggleConsole);
    }

    @Override
    public boolean triggerKeyBindings(int key, int mods) {
        if (isTextInputFocused()) {
            if (key == GLFW.GLFW_KEY_ESCAPE && consoleActive) {
                setConsoleActive(false);
                return true;
            }
            return false;
        }
        if (key == GLFW.GLFW_KEY_D && mods == 0 && linkedDirector != null) {
            linkedDirector.toggle();
            return true;
        }
        return super.triggerKeyBindings(key, mods);
    }

    @Override
    public void render(
            RenderPipeline2D pipeline,
            FontManager fontManager,
            TextureManager textureManager,
            double elapsedSeconds
    ) {
        if (!isVisible()) {
            return;
        }
        if (pipeline != null && pipeline.isInitialized()) {
            if (styleInspectorActive && lastInspectedStyle != null) {
                Rect2f b = lastInspectedStyle.bounds();
                float[] border = ThemePalette.premultipliedRgba(ThemePalette.ACCENT_HOVER, 0.95f);
                float[] fill = new float[]{0.0f, 0.0f, 0.0f, 0.0f};
                pipeline.drawRoundedRect(b.x(), b.y(), b.width(), b.height(), lastInspectedStyle.cornerRadius(), fill, 2.0f, border);
            }
            if (coordinatePickerActive) {
                float[] crosshairColor = ThemePalette.premultipliedRgba(ThemePalette.ACCENT_HOVER, 0.7f);
                float logicalW = screenManager != null ? screenManager.getLogicalWidth() : RenderPipeline2D.DEFAULT_LOGICAL_WIDTH;
                float logicalH = screenManager != null ? screenManager.getLogicalHeight() : RenderPipeline2D.DEFAULT_LOGICAL_HEIGHT;
                pipeline.drawRect(0.0f, cursorY, logicalW, 1.0f, crosshairColor);
                pipeline.drawRect(cursorX, 0.0f, 1.0f, logicalH, crosshairColor);
            }
        }
        super.render(pipeline, fontManager, textureManager, elapsedSeconds);
    }

    // ---- 1. Live Reload (Shift + R) ----

    /**
     * Triggers a hot live-reload on the currently bound {@link ScriptSession}.
     *
     * @return reloaded {@link ScriptSession}
     */
    public ScriptSession triggerLiveReload() {
        return triggerLiveReload(this.session);
    }

    /**
     * Re-scans the project's {@code .vjava} files via {@link ProjectScanner#scan(Path)},
     * recompiles embedded Java blocks via {@link JavaBlockCompiler}, and creates an updated
     * {@link ScriptSession} while preserving the current label, statement index, and all
     * {@link StateStore} variables.
     *
     * @param currentSession active session to reload
     * @return newly compiled and restored {@link ScriptSession}
     */
    public ScriptSession triggerLiveReload(ScriptSession currentSession) {
        Objects.requireNonNull(currentSession, "currentSession must not be null");
        ScriptRuntime oldRuntime = currentSession.getRuntime();
        VJavaAst.ProjectAst oldAst = oldRuntime.getProjectAst();
        Path projectRoot = oldAst.projectRoot();
        if (projectRoot == null) {
            throw new IllegalStateException("Cannot live-reload ScriptSession without a projectRoot path");
        }

        String prevLabel = oldRuntime.getCurrentLabel();
        int prevIndex = oldRuntime.getStatementIndex();
        Map<String, Object> savedVariables = currentSession.getStateStore().snapshotMap();
        SaveSlotData.SavedScriptPosition savedPosition = oldRuntime.toSavedPosition();
        List<HistoryLog.HistoryEntry> savedHistory = currentSession.getHistoryLog().snapshot();
        double savedPlaytime = currentSession.getSessionState().getPlaytimeSeconds();
        String savedSlotId = currentSession.getSessionState().getActiveSaveSlotId();
        String activeLanguage = currentSession.getLocalizationManager() != null
                ? currentSession.getLocalizationManager().getCurrentLanguage()
                : null;

        EngineAPI engineApi = currentSession.getEngineApi();
        SceneController sceneController = currentSession.getSceneController();
        Path savesDir = currentSession.getSaveManager().getSavesDir();

        VJavaAst.ProjectAst reloadedAst = ProjectScanner.scan(projectRoot);
        if (reloadedAst.descriptor() != null) {
            engineApi.setProjectDescriptor(reloadedAst.descriptor());
        }

        ScriptSession reloadedSession;
        try {
            reloadedSession = new ScriptSession(reloadedAst, engineApi, sceneController, savesDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize reloaded ScriptSession", e);
        }

        ScriptRuntime newRuntime = reloadedSession.getRuntime();
        newRuntime.compile();

        reloadedSession.getStateStore().restoreMap(savedVariables);
        reloadedSession.getHistoryLog().restore(savedHistory);
        reloadedSession.getSessionState().setPlaytimeSeconds(savedPlaytime);
        reloadedSession.getSessionState().setActiveSaveSlotId(savedSlotId);
        if (activeLanguage != null && !activeLanguage.isBlank()) {
            reloadedSession.getLocalizationManager().setLanguage(activeLanguage);
        }

        if (prevLabel != null && !prevLabel.isBlank()) {
            if (reloadedAst.labels().containsKey(prevLabel)) {
                List<VJavaAst.Statement> newStmts = reloadedAst.labels().get(prevLabel).statements();
                int clampedIndex = newStmts.isEmpty() ? 0 : Math.min(prevIndex, newStmts.size() - 1);
                SaveSlotData.SavedScriptPosition adjustedPos = clampedIndex == savedPosition.statementIndex()
                        ? savedPosition
                        : new SaveSlotData.SavedScriptPosition(
                                savedPosition.currentLabel(),
                                clampedIndex,
                                savedPosition.callStack(),
                                savedPosition.statusName(),
                                savedPosition.activeSpeakerId(),
                                savedPosition.activeSpeakerName(),
                                savedPosition.activeDialogueText(),
                                savedPosition.nvlMode(),
                                savedPosition.nvlLines(),
                                savedPosition.activeVoicePath(),
                                savedPosition.stageSnapshot()
                        );
                newRuntime.restoreSavedPosition(adjustedPos);
            } else {
                newRuntime.start();
                reloadedSession.getStateStore().restoreMap(savedVariables);
            }
            reloadedSession.getStateStore().recordCheckpoint(newRuntime.snapshotExecution(), "live_reload");
        }

        this.session = reloadedSession;
        if (linkedDirector != null) {
            linkedDirector.setSession(reloadedSession);
        }
        if (onLiveReload != null) {
            onLiveReload.accept(reloadedSession);
        }

        appendConsoleLine("[LiveReload] Reloaded " + reloadedAst.scriptFiles().size()
                + " script(s) @ " + (newRuntime.getCurrentLabel() != null ? newRuntime.getCurrentLabel() : "<idle>")
                + "#" + newRuntime.getStatementIndex());
        return reloadedSession;
    }

    // ---- 2. Style Inspector (Shift + I) ----

    /**
     * Toggles the Style Inspector panel ({@code Shift + I}).
     *
     * @return new active state
     */
    public boolean toggleStyleInspector() {
        setStyleInspectorActive(!styleInspectorActive);
        return styleInspectorActive;
    }

    /**
     * Enables or disables the Style Inspector panel.
     *
     * @param active active toggle
     */
    public void setStyleInspectorActive(boolean active) {
        this.styleInspectorActive = active;
        this.styleInspectorBox.setVisible(active);
        if (active) {
            inspectAt(cursorX, cursorY);
        }
    }

    /** @return {@code true} if Style Inspector is currently active */
    public boolean isStyleInspectorActive() {
        return styleInspectorActive;
    }

    /**
     * Inspects the topmost visible {@link UiNode} under logical coordinates {@code (lx, ly)}
     * across {@link ScreenManager} (excluding this inspector overlay).
     *
     * @param lx logical X coordinate
     * @param ly logical Y coordinate
     * @return inspection snapshot, or {@code null} if no UI node covers {@code (lx, ly)}
     */
    public StyleInspectionResult inspectAt(float lx, float ly) {
        if (screenManager == null) {
            lastInspectedStyle = null;
            styleInspectorText.setText("No ScreenManager attached.");
            return null;
        }
        List<Screen> visible = screenManager.getVisibleScreensSorted();
        for (int i = visible.size() - 1; i >= 0; i--) {
            Screen screen = visible.get(i);
            if (screen == this || screen.getRoot() == null) {
                continue;
            }
            UiNode hit = findDeepestNodeAt(screen.getRoot(), lx, ly);
            if (hit != null) {
                StyleInspectionResult result = new StyleInspectionResult(
                        screen.getName(),
                        hit.getClass().getSimpleName(),
                        hit.getId(),
                        hit.getBounds(),
                        hit.getBackgroundColorHex(),
                        hit.getBorderColorHex(),
                        hit.getBorderThickness(),
                        hit.getCornerRadius(),
                        hit.isVisible(),
                        hit.isEnabled(),
                        hit.isHovered(),
                        hit.isFocused()
                );
                this.lastInspectedStyle = result;
                this.styleInspectorText.setText(result.formatReport());
                return result;
            }
        }
        this.lastInspectedStyle = null;
        this.styleInspectorText.setText(String.format(Locale.ROOT, "No UiNode at (%.1f, %.1f)", lx, ly));
        return null;
    }

    /** @return most recent {@link StyleInspectionResult}, or {@code null} */
    public StyleInspectionResult getLastInspectedStyle() {
        return lastInspectedStyle;
    }

    // ---- 3. Coordinate Picker (Shift + C) ----

    /**
     * Toggles the Coordinate Picker overlay ({@code Shift + C}).
     *
     * @return new active state
     */
    public boolean toggleCoordinatePicker() {
        setCoordinatePickerActive(!coordinatePickerActive);
        return coordinatePickerActive;
    }

    /**
     * Enables or disables the Coordinate Picker overlay.
     *
     * @param active active toggle
     */
    public void setCoordinatePickerActive(boolean active) {
        this.coordinatePickerActive = active;
        this.coordinatePickerBox.setVisible(active);
        if (active) {
            refreshCoordinateLabels();
        }
    }

    /** @return {@code true} if Coordinate Picker is currently active */
    public boolean isCoordinatePickerActive() {
        return coordinatePickerActive;
    }

    /**
     * Updates the tracked logical cursor coordinates and normalized {@code 1920x1080} position.
     *
     * @param lx logical X coordinate
     * @param ly logical Y coordinate
     */
    public void updateCursorPosition(float lx, float ly) {
        float logicalW = screenManager != null ? screenManager.getLogicalWidth() : RenderPipeline2D.DEFAULT_LOGICAL_WIDTH;
        float logicalH = screenManager != null ? screenManager.getLogicalHeight() : RenderPipeline2D.DEFAULT_LOGICAL_HEIGHT;
        this.cursorX = lx;
        this.cursorY = ly;
        this.normalizedX = lx / Math.max(1.0f, logicalW);
        this.normalizedY = ly / Math.max(1.0f, logicalH);
        if (coordinatePickerActive) {
            refreshCoordinateLabels();
        }
    }

    /**
     * Records a picked coordinate at {@code (lx, ly)}, formats {@code xpos/ypos} and
     * {@code xalign/yalign} snippets, and appends it to history.
     *
     * @param lx logical X coordinate
     * @param ly logical Y coordinate
     * @return recorded {@link PickedCoordinate}
     */
    public PickedCoordinate pickCoordinate(float lx, float ly) {
        updateCursorPosition(lx, ly);
        PickedCoordinate picked = new PickedCoordinate(
                cursorX,
                cursorY,
                normalizedX,
                normalizedY,
                formatPosSnippet(cursorX, cursorY),
                formatAlignSnippet(normalizedX, normalizedY)
        );
        if (pickedHistory.size() >= MAX_PICKED_HISTORY) {
            pickedHistory.remove(0);
        }
        pickedHistory.add(picked);
        refreshCoordinateLabels();
        return picked;
    }

    /**
     * Picks and records the current tracked cursor coordinate.
     *
     * @return recorded {@link PickedCoordinate}
     */
    public PickedCoordinate pickCurrentCoordinate() {
        return pickCoordinate(cursorX, cursorY);
    }

    /** @return immutable list of picked coordinates in chronological order */
    public List<PickedCoordinate> getPickedHistory() {
        return Collections.unmodifiableList(new ArrayList<>(pickedHistory));
    }

    /**
     * Clears the recorded coordinate pick history.
     */
    public void clearPickedHistory() {
        pickedHistory.clear();
        refreshCoordinateLabels();
    }

    /**
     * Formats logical pixel coordinates into a {@code .vjava} ATL {@code xpos <x> ypos <y>} snippet.
     *
     * @param lx logical X coordinate
     * @param ly logical Y coordinate
     * @return formatted position snippet
     */
    public static String formatPosSnippet(float lx, float ly) {
        return "xpos " + formatCoordNumber(lx) + " ypos " + formatCoordNumber(ly);
    }

    /**
     * Formats normalized coordinates into a {@code .vjava} ATL {@code xalign <nx> yalign <ny>} snippet.
     *
     * @param nx normalized X alignment
     * @param ny normalized Y alignment
     * @return formatted alignment snippet
     */
    public static String formatAlignSnippet(float nx, float ny) {
        return String.format(Locale.ROOT, "xalign %.3f yalign %.3f", nx, ny);
    }

    // ---- 4. Live Java Console (Shift + O) ----

    /**
     * Toggles the Live Java Console &amp; Script Warping panel ({@code Shift + O}).
     *
     * @return new visibility state
     */
    public boolean toggleConsole() {
        setConsoleActive(!consoleActive);
        return consoleActive;
    }

    /**
     * Opens or closes the Live Java Console &amp; Script Warping panel.
     *
     * @param active active toggle
     */
    public void setConsoleActive(boolean active) {
        this.consoleActive = active;
        this.consoleBox.setVisible(active);
        if (active && screenManager != null) {
            layout(screenManager.getLogicalWidth(), screenManager.getLogicalHeight());
        }
    }

    /** @return {@code true} if the Live Java Console is currently open */
    public boolean isConsoleActive() {
        return consoleActive;
    }

    /**
     * Compiles and executes an ad-hoc Java snippet against the current {@link ScriptSession}.
     *
     * @param code Java statements to execute against {@code (state, persistent, engine, scene)}
     *             and {@code VJavaUserTypes}
     * @return structured {@link ConsoleExecutionResult}
     */
    public ConsoleExecutionResult executeConsoleSnippet(String code) {
        return executeConsoleSnippet(this.session, code);
    }

    /**
     * Compiles and executes an ad-hoc Java snippet via {@link JavaBlockCompiler} against the live
     * {@link StateStore}, {@link PersistentAccess}, {@link EngineAPI}, {@link SceneController},
     * and any {@code VJavaUserTypes} declared in {@code targetSession}'s project AST.
     *
     * @param targetSession target session providing live subsystems and project AST
     * @param code          Java code snippet
     * @return structured {@link ConsoleExecutionResult}
     */
    public ConsoleExecutionResult executeConsoleSnippet(ScriptSession targetSession, String code) {
        Objects.requireNonNull(targetSession, "targetSession must not be null");
        String trimmed = code != null ? code.trim() : "";
        if (trimmed.isEmpty()) {
            return new ConsoleExecutionResult(false, "", "", "Snippet must not be blank.");
        }

        ScriptRuntime runtime = targetSession.getRuntime();
        if (runtime.getCompiledBlocks() == null) {
            runtime.compile();
        }

        String blockId = "__live_console_" + (++consoleCounter);
        List<VJavaAst.JavaBlockNode> projectBlocks = JavaBlockCompiler.collectJavaBlocks(runtime.getProjectAst());
        List<VJavaAst.JavaBlockNode> compileUnits = new ArrayList<>(projectBlocks.size() + 1);
        compileUnits.addAll(projectBlocks);
        compileUnits.add(new VJavaAst.JavaBlockNode(blockId, "Live Java Console", trimmed, "<console>", 1));

        appendConsoleLine("> " + trimmed);

        JavaBlockCompiler.CompiledProjectBlocks compiled;
        try {
            compiled = javaBlockCompiler.compile(compileUnits);
        } catch (VJavaCompileException ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : "Compilation failed";
            appendConsoleLine("[COMPILE ERROR] " + msg);
            return new ConsoleExecutionResult(false, trimmed, "", msg);
        }

        EngineAPI engineApi = targetSession.getEngineApi();
        StateStore stateStore = targetSession.getStateStore();
        PersistentAccess persistent = targetSession.getPersistentState();
        SceneController sceneController = targetSession.getSceneController();

        List<String> capturedLogs = new ArrayList<>();
        Consumer<EngineLogger.LogEntry> logCapture = entry -> capturedLogs.add(entry.format());
        engineApi.getLogger().addListener(logCapture);
        try {
            compiled.requireExecutable(blockId).execute(stateStore, persistent, engineApi, sceneController);
            String joinedOutput = String.join("\n", capturedLogs);
            if (!capturedLogs.isEmpty()) {
                for (String line : capturedLogs) {
                    appendConsoleLine(line);
                }
            } else {
                appendConsoleLine("[OK] Executed successfully.");
            }
            return new ConsoleExecutionResult(true, trimmed, joinedOutput, null);
        } catch (Exception ex) {
            String msg = ex.getClass().getSimpleName() + ": " + (ex.getMessage() != null ? ex.getMessage() : "");
            appendConsoleLine("[RUNTIME ERROR] " + msg);
            return new ConsoleExecutionResult(false, trimmed, String.join("\n", capturedLogs), msg);
        } finally {
            engineApi.getLogger().removeListener(logCapture);
        }
    }

    /** @return immutable chronological list of console log lines */
    public List<String> getConsoleLog() {
        return Collections.unmodifiableList(new ArrayList<>(consoleLog));
    }

    /**
     * Clears the Live Java Console output history.
     */
    public void clearConsoleLog() {
        consoleLog.clear();
        rebuildConsoleLogWidgets();
    }

    // ---- 5. Script Warping ----

    /**
     * Warps the currently bound {@link ScriptSession} cleanly to {@code labelName}.
     *
     * @param labelName target label name
     */
    public void warpToLabel(String labelName) {
        warpToLabel(this.session, labelName);
    }

    /**
     * Warps {@code targetSession}'s runtime cleanly to {@code labelName}, clearing stale prompts
     * and call stacks and stepping to the first interaction point in that label.
     *
     * @param targetSession target script session
     * @param labelName     target label name
     */
    public void warpToLabel(ScriptSession targetSession, String labelName) {
        Objects.requireNonNull(targetSession, "targetSession must not be null");
        if (labelName == null || labelName.isBlank()) {
            throw new IllegalArgumentException("labelName must not be null or blank");
        }
        String cleanLabel = labelName.trim();
        targetSession.getRuntime().jumpToLabel(cleanLabel);
        appendConsoleLine("[Warp] Jumped to label '" + cleanLabel + "' (status=" + targetSession.getRuntime().getStatus() + ")");
    }

    /**
     * Returns all declared label names in the active project AST.
     *
     * @return immutable list of label names
     */
    public List<String> getAvailableLabels() {
        return List.copyOf(session.getRuntime().getProjectAst().labels().keySet());
    }

    // ---- Internal UI & Hit-Testing Helpers ----

    private UiNode findDeepestNodeAt(UiNode node, float lx, float ly) {
        if (node == null || !node.isVisible() || !node.getBounds().contains(lx, ly)) {
            return null;
        }
        List<UiNode> children = node.getChildren();
        for (int i = children.size() - 1; i >= 0; i--) {
            UiNode hit = findDeepestNodeAt(children.get(i), lx, ly);
            if (hit != null) {
                return hit;
            }
        }
        return node;
    }

    private boolean isClickInsideVisibleToolPanel(float lx, float ly) {
        return (styleInspectorBox.isVisible() && styleInspectorBox.getBounds().contains(lx, ly))
                || (coordinatePickerBox.isVisible() && coordinatePickerBox.getBounds().contains(lx, ly))
                || (consoleBox.isVisible() && consoleBox.getBounds().contains(lx, ly));
    }

    private void refreshCoordinateLabels() {
        String posSnippet = formatPosSnippet(cursorX, cursorY);
        String alignSnippet = formatAlignSnippet(normalizedX, normalizedY);
        coordinateLiveLabel.setText(posSnippet + "\n" + alignSnippet);

        if (pickedHistory.isEmpty()) {
            coordinateHistoryLabel.setText("Click canvas to record coordinates.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, pickedHistory.size() - 5);
        for (int i = pickedHistory.size() - 1; i >= start; i--) {
            PickedCoordinate p = pickedHistory.get(i);
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("#").append(i + 1).append(": ").append(p.xposYposSnippet()).append(" | ").append(p.alignSnippet());
        }
        coordinateHistoryLabel.setText(sb.toString());
    }

    private void appendConsoleLine(String line) {
        if (line == null) {
            return;
        }
        if (consoleLog.size() >= MAX_LOG_ENTRIES) {
            consoleLog.remove(0);
        }
        consoleLog.add(line);
        rebuildConsoleLogWidgets();
    }

    private void rebuildConsoleLogWidgets() {
        consoleLogBox.clearChildren();
        for (String line : consoleLog) {
            String color = line.startsWith("[COMPILE ERROR]") || line.startsWith("[RUNTIME ERROR]")
                    ? ThemePalette.ERROR
                    : (line.startsWith(">") ? ThemePalette.ACCENT_HOVER : ThemePalette.TEXT_PRIMARY);
            consoleLogBox.addChild(createText(line, 14.0f, color));
        }
        if (consoleLogViewport.getBounds().height() > 0.0f) {
            consoleLogViewport.layout(consoleLogViewport.getBounds());
            consoleLogViewport.setScrollY(consoleLogViewport.getMaxScrollY());
        }
    }

    private boolean isTextInputFocused() {
        return screenManager != null && screenManager.getFocusedNode() instanceof TextInput;
    }

    private static String formatCoordNumber(float value) {
        if (Math.abs(value - Math.round(value)) < 0.05f) {
            return Integer.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static TextWidget createText(String text, float fontSize, String colorHex) {
        TextWidget w = new TextWidget(text);
        w.setFontSize(fontSize).setColorHex(colorHex);
        return w;
    }

    private static TextButton createButton(String label, float width, float height, Runnable onClick) {
        TextButton btn = new TextButton(label, onClick);
        btn.setFontSize(14.0f).setPreferredSize(width, height);
        return btn;
    }

    // ---- Public Configuration & Accessors ----

    /** @return currently bound {@link ScriptSession} */
    public ScriptSession getSession() {
        return session;
    }

    /**
     * Updates the active {@link ScriptSession} reference.
     *
     * @param session new script session
     */
    public void setSession(ScriptSession session) {
        this.session = Objects.requireNonNull(session, "session must not be null");
    }

    /**
     * Links a {@link LiveDirectorOverlay} so {@code D} toggling and Live Reload keep both tools synchronized.
     *
     * @param linkedDirector director overlay (nullable)
     */
    public void setLinkedDirector(LiveDirectorOverlay linkedDirector) {
        this.linkedDirector = linkedDirector;
    }

    /**
     * Sets a callback invoked whenever {@link #triggerLiveReload(ScriptSession)} produces a new {@link ScriptSession}.
     *
     * @param onLiveReload callback receiving the reloaded session (nullable)
     */
    public void setOnLiveReload(Consumer<ScriptSession> onLiveReload) {
        this.onLiveReload = onLiveReload;
    }

    /** @return latest tracked logical X cursor position */
    public float getCursorX() {
        return cursorX;
    }

    /** @return latest tracked logical Y cursor position */
    public float getCursorY() {
        return cursorY;
    }

    /** @return latest tracked normalized X cursor coordinate */
    public float getNormalizedX() {
        return normalizedX;
    }

    /** @return latest tracked normalized Y cursor coordinate */
    public float getNormalizedY() {
        return normalizedY;
    }

    /** @return Style Inspector panel {@link WindowBox} */
    public WindowBox getStyleInspectorBox() {
        return styleInspectorBox;
    }

    /** @return Coordinate Picker panel {@link WindowBox} */
    public WindowBox getCoordinatePickerBox() {
        return coordinatePickerBox;
    }

    /** @return Live Java Console &amp; Warp panel {@link WindowBox} */
    public WindowBox getConsoleBox() {
        return consoleBox;
    }

    /** @return Live Java Console snippet {@link TextInput} */
    public TextInput getConsoleInput() {
        return consoleInput;
    }

    /** @return Live Java Console execute {@link TextButton} */
    public TextButton getConsoleExecuteButton() {
        return consoleExecuteButton;
    }

    /** @return Script Warp target label {@link TextInput} */
    public TextInput getWarpLabelInput() {
        return warpLabelInput;
    }

    /** @return Script Warp {@link TextButton} */
    public TextButton getWarpButton() {
        return warpButton;
    }

    /** @return Live Reload {@link TextButton} */
    public TextButton getReloadButton() {
        return reloadButton;
    }
}
