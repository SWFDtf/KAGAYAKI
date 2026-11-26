# Package `de.swfdtf.core` — AGENT_CONTEXT

## Zweck des Pakets
Zentrales Laufzeit-Fundament von KAGAYAKI: Ausführungsmodi (`STUDIO_DUAL_WINDOW` vs. `STANDALONE_GAME`), unveränderliche Engine-Konfiguration inklusive CLI-Parser, JSON-Projektmetadaten (`project.vjava.json`), Thread-sicheres strukturiertes Logging, deterministische 60-Hz-Simulationsuhr, zentralisiertes GLFW-Input-Tracking mit Letterbox-Mausprojektion, öffentliche Skript-/UI-Fassade (`EngineAPI`) mit typisiertem Service-Locator, die Entkopplungsschnittstellen `StudioBridge` (für Studio-Fenster A) und `GameHost` (für Spiel-Laufzeitkoordination in `de.swfdtf.ui`) sowie der Top-Level-Laufzeitkoordinator `EngineApplication`.

## Abhängigkeiten zu anderen `de.swfdtf.*`-Paketen
- `de.swfdtf.graphics` (`GlfwWindow`, `IRenderPipeline`, `RenderMode`, `RenderPipeline2D`, `RenderPipeline3DStub`, `SceneGraph`, `WindowManager`) in `EngineApplication`
- `de.swfdtf.math` (`Rect2f`) in `EngineApplication`
- Externe Bibliotheken: `org.lwjgl.glfw.*`, `org.lwjgl.opengl.GL11`, `org.lwjgl.system.*` (`InputManager`, `EngineApplication`); `com.google.gson.*` (`ProjectDescriptor`, `KeybindingRemapper`).

## Enthaltene Typen

| Typ | Art | Beschreibung |
| :--- | :--- | :--- |
| `EngineMode` | `enum` | Definiert den Laufzeitmodus (`STUDIO_DUAL_WINDOW` für Dual-Window-Entwicklung oder `STANDALONE_GAME` für exportierte Spiele). |
| `EngineConfig` | `record` | Unveränderliche Laufzeitkonfiguration inklusive Validierung, Builder-API und CLI-Argument-Parser (`fromArgs`). |
| `EngineConfig.Builder` | `final class` | Fluent-Builder zur Erstellung unveränderlicher `EngineConfig`-Instanzen. |
| `EngineLogger` | `final class` | Thread-sicherer, strukturierter Logger mit begrenztem Ringpuffer (`DEFAULT_CAPACITY = 2000`) und Listener-Fan-Out für Studio-Konsolen. |
| `EngineLogger.Level` | `enum` | Schweregrade für Log-Einträge (`DEBUG`, `INFO`, `WARN`, `ERROR`). |
| `EngineLogger.LogEntry` | `record` | Unveränderlicher strukturierter Log-Eintrag mit Zeitstempel, Level, Kategorie, Nachricht und Einzeilen-Formatierung. |
| `ProjectDescriptor` | `final class` | Gson-serialisierbares Projekt-Metadatenmodell für `project.vjava.json` inklusive Laden, Speichern und Standardwert-Normalisierung. |
| `EngineVersion` | `final class` | Zentrale Versionskonstanten (`ENGINE_NAME`, `VERSION`, `VJAVA_SPEC_VERSION`) und Banner-/Header-Formatierer. |
| `GameClock` | `final class` | Deterministische 60-Hz-Fixed-Timestep-Uhr (`FIXED_TIMESTEP = 1.0 / 60.0`) mit Frame-Delta-Clamping, Zeit-Skalierung, Pause und FPS-Messung. |
| `InputManager` | `final class` | Zentraler GLFW-Eingabemanager für Tastatur, Modifier, Unicode-Texteingabe, Letterbox-gemappte logische Mauskoordinaten, Scroll-Deltas und Gamepads (inkl. Headless-Test-Hooks). |
| `EngineAPI` | `final class` | Öffentliche Engine-Fassade für `@->{ ... }<<`-Java-Blöcke (`engine`), UI-Screens und Studio-Werkzeuge inklusive Properties und typisiertem Service-Locator. |
| `StudioBridge` | `interface` | Entkopplungsschnittstelle zwischen `EngineApplication` und `StudioWindowController` (`tools`-Paket) zum sauberen Ausschluss in Standalone-Builds. |
| `GameHost` | `interface` | Lebenszyklus-Brücke, die `EngineApplication` von Spielablauf, UI-Screens, Audio-Mixing und Skript-Ausführung in `de.swfdtf.ui` (`GameRuntimeCoordinator`) entkoppelt. |
| `EngineApplication` | `final class` | Top-Level-Laufzeitkoordinator (`AutoCloseable`) für Initialisierung, 60-Hz-Fixed-Timestep-Loop, 2D/3D-Pipeline-Umschaltung, OpenGL-Kontextwechsel zwischen Fenster A/B, Input-Routing, `GameHost`-Lebenszyklus und Dual-Window-/Standalone-Ausführung. |
| `EngineApplication.Updatable` | `@FunctionalInterface` | Funktionaler Callback für deterministische Fixed-Timestep-Subsysteme (`update(double fixedDeltaSeconds)`). |
| `EngineEvent` | `interface` | Basisvertrag für Ereignisse im `EngineEventBus` (`default long timestampNanos()`). |
| `EventSubscription` | `interface` | `AutoCloseable`-Handle zum Prüfen (`isActive()`) und Abbestellen (`unsubscribe()`) eines Listeners. |
| `EngineEventBus` | `final class` | Thread-sicherer typisierter Pub/Sub-Event-Bus mit Prioritätsreihenfolge und Fehlerisolation pro Listener. |
| `EngineCommand` | `interface` | Command-Pattern-Vertrag (`description()`, `execute()`, `undo()`) für rückgängig machbare Editor-/Engine-Aktionen. |
| `CommandStack` | `final class` | Undo-/Redo-Verlauf mit konfigurierbarer Maximaltiefe, `Optional<String>`-Vorschau und Beschreibungsverlauf. |
| `EnginePlugin` | `interface` | Lebenszyklus-Vertrag (`id()`, `displayName()`, `version()`, `onEnable`, `onDisable`) für Engine-Erweiterungen. |
| `PluginRegistry` | `final class` | Registrierungs- und Lebenszyklus-Manager für `EnginePlugin`-Instanzen mit `Optional`-Suche und unveränderlichen Listen. |
| `PluginLoader` | `final class` | Lädt Laufzeit-Plugins dynamisch aus `plugins/*.jar` per `ServiceLoader<EnginePlugin>` mit Fehlerisolation pro JAR. |
| `ResourceProvider` | `interface` | Schreibgeschützte Schnittstelle für datei- oder `.vpak`-gestützten Projektressourcenzugriff (`contains`, `readBytes`, `listPaths`, `verifyIntegrity`). |
| `KeybindingRemapper` | `final class` | Konfigurierbares Action-zu-GLFW-Tastenzuordnungssystem mit Konflikterkennung, Swap-Unterstützung und Gson-JSON-Persistenz. |
| `GamepadNavigationRouter` | `final class` | Übersetzt `InputManager`-D-Pad-/Stick-Eingaben inklusive Deadzone und Wiederholungsrate in diskrete `NavigationDirection`-Ereignisse. |

## Öffentliche API-Signaturen

### `EngineMode`
```java
public enum EngineMode {
    STUDIO_DUAL_WINDOW,
    STANDALONE_GAME
}
```

### `EngineConfig` & `EngineConfig.Builder`
```java
public record EngineConfig(
        EngineMode engineMode,
        String renderModeName,
        Path projectDir,
        int logicalWidth,
        int logicalHeight,
        boolean vsync,
        int targetFps,
        boolean smokeTest,
        boolean headless
)
public static final int DEFAULT_LOGICAL_WIDTH = 1920
public static final int DEFAULT_LOGICAL_HEIGHT = 1080
public static final int DEFAULT_TARGET_FPS = 120

public static EngineConfig defaults()
public static Builder builder()
public Builder toBuilder()
public static EngineConfig fromArgs(String[] args)
public boolean isStudioMode()
public boolean isStandaloneMode()
public boolean is3DMode()

public static final class Builder {
    public Builder engineMode(EngineMode engineMode)
    public Builder renderModeName(String renderModeName)
    public Builder projectDir(Path projectDir)
    public Builder logicalWidth(int logicalWidth)
    public Builder logicalHeight(int logicalHeight)
    public Builder vsync(boolean vsync)
    public Builder targetFps(int targetFps)
    public Builder smokeTest(boolean smokeTest)
    public Builder headless(boolean headless)
    public EngineConfig build()
}
```

### `EngineLogger`, `EngineLogger.Level` & `EngineLogger.LogEntry`
```java
public final class EngineLogger
public static final int DEFAULT_CAPACITY = 2000

public enum Level { DEBUG, INFO, WARN, ERROR }
public record LogEntry(Instant timestamp, Level level, String category, String message) {
    public String format()
}

public EngineLogger()
public EngineLogger(int capacity)
public void log(Level level, String category, String message)
public void info(String message)
public void info(String category, String message)
public void warn(String message)
public void warn(String category, String message)
public void error(String message)
public void error(String category, String message)
public void error(String category, String message, Throwable throwable)
public void debug(String message)
public void debug(String category, String message)
public void addListener(Consumer<LogEntry> listener)
public void removeListener(Consumer<LogEntry> listener)
public List<LogEntry> getRecentEntries(int max)
public void clear()
public int size()
public int getCapacity()
public void setMinimumLevel(Level minimumLevel)
public Level getMinimumLevel()
public void setConsoleOutputEnabled(boolean consoleOutputEnabled)
public boolean isConsoleOutputEnabled()
```

### `ProjectDescriptor`
```java
public final class ProjectDescriptor
public static final String FILE_NAME = "project.vjava.json"

public ProjectDescriptor()
public ProjectDescriptor(
        String title, String version, String author, String renderMode,
        int logicalWidth, int logicalHeight, String entryLabel,
        String defaultLanguage, String dialogueMode, String windowTitle, String iconPath
)
public static ProjectDescriptor createDefault(String title)
public static ProjectDescriptor load(Path projectDir) throws IOException
public static void save(Path projectDir, ProjectDescriptor descriptor) throws IOException
public void normalizeDefaults()
public String getTitle() / public void setTitle(String title)
public String getVersion() / public void setVersion(String version)
public String getAuthor() / public void setAuthor(String author)
public String getRenderMode() / public void setRenderMode(String renderMode)
public int getLogicalWidth() / public void setLogicalWidth(int logicalWidth)
public int getLogicalHeight() / public void setLogicalHeight(int logicalHeight)
public String getEntryLabel() / public void setEntryLabel(String entryLabel)
public String getDefaultLanguage() / public void setDefaultLanguage(String defaultLanguage)
public String getDialogueMode() / public void setDialogueMode(String dialogueMode)
public String getWindowTitle() / public void setWindowTitle(String windowTitle)
public String getIconPath() / public void setIconPath(String iconPath)
```

### `GameClock`
```java
public final class GameClock
public static final double FIXED_TIMESTEP = 1.0 / 60.0
public static final double MAX_FRAME_DELTA = 0.25
public static final int MAX_STEPS_PER_FRAME = 15

public int advanceFrame(double realDeltaSeconds)
public double getInterpolationAlpha()
public double getTotalTimeSeconds()
public long getTickCount()
public int getCurrentFps()
public boolean isPaused()
public void setPaused(boolean paused)
public double getTimeScale()
public void setTimeScale(double timeScale)
public double getFixedTimestep()
public void reset()
```

### `InputManager`
```java
public final class InputManager
public static final int KEY_COUNT = GLFW.GLFW_KEY_LAST + 1
public static final int MOUSE_BUTTON_COUNT = GLFW.GLFW_MOUSE_BUTTON_LAST + 1
public static final int MAX_JOYSTICKS = GLFW.GLFW_JOYSTICK_LAST + 1
public static final int GAMEPAD_BUTTON_COUNT = GLFW.GLFW_GAMEPAD_BUTTON_LAST + 1
public static final int GAMEPAD_AXIS_COUNT = GLFW.GLFW_GAMEPAD_AXIS_LAST + 1

public void attachToWindow(long windowHandle)
public void detachCallbacks()
public void dispose()
public void beginFrame()
public void endFrame()
public void pollGamepads()
public void updateGamepadState(int joystickId, boolean connected, boolean[] buttons, float[] axes)
public void onKey(int key, int scancode, int action, int mods)
public void onChar(int codepoint)
public void onMouseButton(int button, int action, int mods)
public void onCursorPos(double windowX, double windowY)
public void onScroll(double xOffset, double yOffset)
public void onWindowFocus(boolean focused)
public boolean isKeyDown(int key)
public boolean wasKeyJustPressed(int key)
public boolean wasKeyJustReleased(int key)
public boolean isShiftDown()
public boolean isCtrlDown()
public boolean isAltDown()
public boolean isSuperDown()
public String drainTypedChars()
public void setLogicalSize(int logicalWidth, int logicalHeight)
public void setViewport(double vpX, double vpY, double vpW, double vpH)
public void updateLetterboxViewport(int windowWidth, int windowHeight)
public double windowToLogicalX(double windowX)
public double windowToLogicalY(double windowY)
public double getWindowMouseX()
public double getWindowMouseY()
public double getLogicalMouseX()
public double getLogicalMouseY()
public double getClampedLogicalMouseX()
public double getClampedLogicalMouseY()
public double getMouseDeltaX()
public double getMouseDeltaY()
public boolean isMouseInsideViewport()
public boolean isMouseButtonDown(int button)
public boolean wasMouseButtonJustPressed(int button)
public boolean wasMouseButtonJustReleased(int button)
public double getScrollX()
public double getScrollY()
public double consumeScrollX()
public double consumeScrollY()
public boolean isGamepadConnected()
public boolean isGamepadConnected(int joystickId)
public int getActiveGamepadId()
public boolean isGamepadButtonDown(int button)
public boolean isGamepadButtonDown(int joystickId, int button)
public boolean wasGamepadButtonJustPressed(int button)
public boolean wasGamepadButtonJustReleased(int button)
public float getGamepadAxis(int axis)
public float getGamepadAxis(int joystickId, int axis)
public float getRawGamepadAxis(int joystickId, int axis)
public void setGamepadDeadzone(float gamepadDeadzone)
public float getGamepadDeadzone()
public void reset()
```

### `EngineAPI`
```java
public final class EngineAPI
public static final String ENGINE_VERSION = "KAGAYAKI 1.0.0"
public static final String RENDER_BACKEND_NAME = "LWJGL 3 / OpenGL 3.3 Core"

public EngineAPI(EngineConfig config)
public EngineAPI(EngineConfig config, ProjectDescriptor projectDescriptor, EngineLogger logger, GameClock clock, InputManager input)
public void logInfo(String message)
public void logWarn(String message)
public void logError(String message)
public void logError(String message, Throwable throwable)
public void logDebug(String message)
public EngineConfig getConfig()
public ProjectDescriptor getProjectDescriptor()
public void setProjectDescriptor(ProjectDescriptor descriptor)
public GameClock getClock()
public InputManager getInput()
public EngineLogger getLogger()
public void requestQuit()
public boolean isQuitRequested()
public void requestLiveReload()
public boolean consumeLiveReloadRequested()
public String getEngineVersion()
public String getRenderBackendName()
public Object getProperty(String key)
public void setProperty(String key, Object value)
public Object removeProperty(String key)
public Map<String, Object> getPropertiesSnapshot()
public <T> void registerService(Class<T> serviceType, T instance)
public <T> T getService(Class<T> serviceType)
public <T> T requireService(Class<T> serviceType)
public boolean hasService(Class<?> serviceType)
public void unregisterService(Class<?> serviceType)
```

### `StudioBridge`
```java
public interface StudioBridge {
    void init(EngineAPI api, long studioWindowHandle, long gameWindowHandle);
    void update(double dt);
    void render(int windowWidth, int windowHeight);
    void onReload();
    void dispose();
}
```

### `GameHost`
```java
public interface GameHost {
    void init(EngineApplication app, boolean glEnabled);
    void update(double dt, InputManager input);
    void renderUi(double elapsedSeconds);
    void onReload();
    void dispose();
}
```

### `EngineApplication` & `EngineApplication.Updatable`
```java
public final class EngineApplication implements AutoCloseable {
    @FunctionalInterface
    public interface Updatable {
        void update(double fixedDeltaSeconds);
    }

    public EngineApplication(EngineConfig config)
    public EngineApplication(EngineConfig config, StudioBridge studioBridge)
    public EngineApplication(EngineConfig config, StudioBridge studioBridge, GameHost gameHost)
    public void initialize()
    public void run()
    public void stepOnce(double realDeltaSeconds)
    public void switchRenderPipeline(RenderMode mode)
    public void addUpdatable(Updatable updatable)
    public void removeUpdatable(Updatable updatable)
    public EngineAPI getApi()
    public SceneGraph getSceneGraph()
    public WindowManager getWindowManager()
    public IRenderPipeline getActivePipeline()
    public boolean isInitialized()
    public GameHost getGameHost()
    @Override public void close()
}
```


### `EngineVersion`
```java
public final class EngineVersion {
    public static final String ENGINE_NAME = "KAGAYAKI";
    public static final String VERSION = "1.0.0";
    public static final String VJAVA_SPEC_VERSION = "1.1";

    public static String formatBanner()
    public static String formatProjectHeader(String projectTitle, String projectVersion)
    public static String formatProjectHeader(ProjectDescriptor descriptor)
}
```
