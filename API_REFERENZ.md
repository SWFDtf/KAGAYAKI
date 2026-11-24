# KAGAYAKI — Öffentliche API-Referenz (M1–M7)

Diese Referenz dokumentiert die verifizierten öffentlichen Java-Schnittstellen aller abgeschlossenen KAGAYAKI-Pakete (`de.swfdtf`, `de.swfdtf.core`, `de.swfdtf.graphics`, `de.swfdtf.math`, `de.swfdtf.git`, `de.swfdtf.parser`, `de.swfdtf.state`, `de.swfdtf.audio`, `de.swfdtf.ui`, `de.swfdtf.tools`) sowie die Gradle-Tasks aus `build.gradle`.

---

## 1. Gradle-Tasks (`build.gradle`)

| Task | Gruppe | Beschreibung |
| :--- | :--- | :--- |
| `./gradlew build` | `build` | Kompiliert Java 21 Quellen, führt JUnit-6-Tests aus und paketiert das Standard-JAR. |
| `./gradlew test` | `verification` | Führt alle JUnit-Jupiter-Tests (`6.0.0`) mit ausführlichem Event-Logging aus (M1–M6-Basis: **157** Tests in **43** Suites, ergänzt um die M7-Suites `DirectorAndInspectorsTest`, `ToolsPackagerAndTemplateTest` und `EndToEndEngineTest` unter `de.swfdtf.tools`). |
| `./gradlew run` | `application` | Startet `de.swfdtf.Main` (unter macOS automatisch mit `-XstartOnFirstThread`). |
| `./gradlew runStudio` | `kagayaki` | Startet `de.swfdtf.Main` mit `--studio` (optional `-PprojectDir=<pfad>` ergänzt `--project <pfad>`). |
| `./gradlew runStandalone` | `kagayaki` | Startet `de.swfdtf.StandaloneMain` (optional `-PprojectDir=<pfad>` ergänzt `--standalone <pfad>`). |
| `./gradlew packageStandalone` | `kagayaki` | Erstellt ein ausführbares Fat-JAR `build/dist/kagayaki-standalone-<version>.jar` mit `Main-Class: de.swfdtf.StandaloneMain` und schließt `de/swfdtf/tools/StudioWindowController*` sowie `**/AGENT_CONTEXT.md` aus. |

---

## 2. Paket `de.swfdtf` (Einstiegspunkte)

### `Main`
Primärer Anwendungs-Einstiegspunkt. Verarbeitet optional `--create-template <dir>` bzw. `--create-template=<dir>` (erzeugt ein Starterprojekt via `ProjectTemplateGenerator.generate(...)` und ergänzt `--project <dir>`) und startet `EngineApplication` standardmäßig im `EngineMode.STUDIO_DUAL_WINDOW`-Modus, sofern `--standalone` nicht explizit übergeben wurde.
* `public static void main(String[] args)`

### `StandaloneMain`
Dedizierter Einstiegspunkt für exportierte Standalone-Builds (nur Fenster B ohne Studio-Werkzeuge). Erzwingt `EngineMode.STANDALONE_GAME`.
* `public static void main(String[] args)`

---

## 3. Paket `de.swfdtf.core`

### `EngineAPI`
Zentrale Engine-Fassade (verfügbar als `engine` in `@->{ ... }<<`-Java-Blöcken, UI-Screens und Studio-Werkzeugen).

* **Konstanten**:
  * `public static final String ENGINE_VERSION = "KAGAYAKI 1.0.0"`
  * `public static final String RENDER_BACKEND_NAME = "LWJGL 3 / OpenGL 3.3 Core"`
* **Konstruktoren**:
  * `public EngineAPI(EngineConfig config)`
  * `public EngineAPI(EngineConfig config, ProjectDescriptor projectDescriptor, EngineLogger logger, GameClock clock, InputManager input)`
* **Logging**:
  * `public void logInfo(String message)`
  * `public void logWarn(String message)`
  * `public void logError(String message)`
  * `public void logError(String message, Throwable throwable)`
  * `public void logDebug(String message)`
* **Subsysteme & Konfiguration**:
  * `public EngineConfig getConfig()`
  * `public ProjectDescriptor getProjectDescriptor()`
  * `public void setProjectDescriptor(ProjectDescriptor descriptor)`
  * `public GameClock getClock()`
  * `public InputManager getInput()`
  * `public EngineLogger getLogger()`
* **Lebenszyklus & Engine-Info**:
  * `public void requestQuit()` / `public boolean isQuitRequested()`
  * `public void requestLiveReload()` / `public boolean consumeLiveReloadRequested()`
  * `public String getEngineVersion()`
  * `public String getRenderBackendName()`
* **Dynamische Runtime-Properties**:
  * `public Object getProperty(String key)`
  * `public void setProperty(String key, Object value)` (`null` entfernt den Eintrag)
  * `public Object removeProperty(String key)`
  * `public Map<String, Object> getPropertiesSnapshot()`
* **Typisierter Service-Locator**:
  * `public <T> void registerService(Class<T> serviceType, T instance)`
  * `public <T> T getService(Class<T> serviceType)`
  * `public <T> T requireService(Class<T> serviceType)`
  * `public boolean hasService(Class<?> serviceType)`
  * `public void unregisterService(Class<?> serviceType)`

### `EngineConfig` & `EngineMode`
Unveränderliche Laufzeitkonfiguration als Java-`record`.

```java
public enum EngineMode { STUDIO_DUAL_WINDOW, STANDALONE_GAME }

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
```
* **Konstanten**: `DEFAULT_LOGICAL_WIDTH = 1920`, `DEFAULT_LOGICAL_HEIGHT = 1080`, `DEFAULT_TARGET_FPS = 120`.
* **Fabrikmethoden & Abfragen**:
  * `public static EngineConfig defaults()`
  * `public static EngineConfig.Builder builder()`
  * `public EngineConfig.Builder toBuilder()`
  * `public static EngineConfig fromArgs(String[] args)` — unterstützt `--studio`, `--standalone [projectDir]`, `--project <dir>`, `--mode <2D|3D>`, `--render-mode <2D|3D>`, `--2d`, `--3d`, `--width <px>`, `--height <px>`, `--vsync`, `--no-vsync`, `--fps <n>`, `--target-fps <n>`, `--smoke-test`, `--headless` sowie `=`-Varianten.
  * `public boolean isStudioMode()` / `public boolean isStandaloneMode()` / `public boolean is3DMode()`
* **`EngineConfig.Builder`**:
  * `engineMode(EngineMode)`, `renderModeName(String)`, `projectDir(Path)`, `logicalWidth(int)`, `logicalHeight(int)`, `vsync(boolean)`, `targetFps(int)`, `smokeTest(boolean)`, `headless(boolean)`, `build()`.

### `ProjectDescriptor`
Gson-serialisierbares Projekt-Metadatenmodell für `project.vjava.json`.

* **Konstante**: `public static final String FILE_NAME = "project.vjava.json"`
* **Konstruktoren**:
  * `public ProjectDescriptor()`
  * `public ProjectDescriptor(String title, String version, String author, String renderMode, int logicalWidth, int logicalHeight, String entryLabel, String defaultLanguage, String dialogueMode, String windowTitle, String iconPath)`
* **Statische Helfer**:
  * `public static ProjectDescriptor createDefault(String title)`
  * `public static ProjectDescriptor load(Path projectDir) throws IOException`
  * `public static void save(Path projectDir, ProjectDescriptor descriptor) throws IOException`
* **Instanzmethoden**:
  * `public void normalizeDefaults()`
  * Getter & Setter für `title`, `version`, `author`, `renderMode`, `logicalWidth`, `logicalHeight`, `entryLabel`, `defaultLanguage`, `dialogueMode`, `windowTitle`, `iconPath`.

### `GameClock`
Deterministische 60-Hz-Fixed-Timestep-Simulationsuhr.

* **Konstanten**:
  * `public static final double FIXED_TIMESTEP = 1.0 / 60.0`
  * `public static final double MAX_FRAME_DELTA = 0.25`
  * `public static final int MAX_STEPS_PER_FRAME = 15`
* **Methoden**:
  * `public int advanceFrame(double realDeltaSeconds)`
  * `public double getInterpolationAlpha()`
  * `public double getTotalTimeSeconds()`
  * `public long getTickCount()`
  * `public int getCurrentFps()`
  * `public boolean isPaused()` / `public void setPaused(boolean paused)`
  * `public double getTimeScale()` / `public void setTimeScale(double timeScale)`
  * `public double getFixedTimestep()`
  * `public void reset()`

### `InputManager`
Zentralisierter GLFW-Eingabemanager (auch direkt im Headless-Modus testbar).

* **Konstanten**:
  * `KEY_COUNT = GLFW.GLFW_KEY_LAST + 1`
  * `MOUSE_BUTTON_COUNT = GLFW.GLFW_MOUSE_BUTTON_LAST + 1`
  * `MAX_JOYSTICKS = GLFW.GLFW_JOYSTICK_LAST + 1`
  * `GAMEPAD_BUTTON_COUNT = GLFW.GLFW_GAMEPAD_BUTTON_LAST + 1`
  * `GAMEPAD_AXIS_COUNT = GLFW.GLFW_GAMEPAD_AXIS_LAST + 1`
* **Lebenszyklus & Frame-Steuerung**:
  * `attachToWindow(long windowHandle)`, `detachCallbacks()`, `dispose()`, `beginFrame()`, `endFrame()`, `pollGamepads()`, `reset()`
* **Event-Hooks (für GLFW & Headless-Tests)**:
  * `onKey(int key, int scancode, int action, int mods)`, `onChar(int codepoint)`, `onMouseButton(int button, int action, int mods)`, `onCursorPos(double windowX, double windowY)`, `onScroll(double xOffset, double yOffset)`, `onWindowFocus(boolean focused)`, `updateGamepadState(int joystickId, boolean connected, boolean[] buttons, float[] axes)`
* **Tastatur & Text**:
  * `isKeyDown(int key)`, `wasKeyJustPressed(int key)`, `wasKeyJustReleased(int key)`
  * `isShiftDown()`, `isCtrlDown()`, `isAltDown()`, `isSuperDown()`
  * `drainTypedChars()`
* **Viewport & Maus**:
  * `setLogicalSize(int logicalWidth, int logicalHeight)`, `setViewport(double vpX, double vpY, double vpW, double vpH)`, `updateLetterboxViewport(int windowWidth, int windowHeight)`
  * `windowToLogicalX(double windowX)`, `windowToLogicalY(double windowY)`
  * `getWindowMouseX()`, `getWindowMouseY()`, `getLogicalMouseX()`, `getLogicalMouseY()`, `getClampedLogicalMouseX()`, `getClampedLogicalMouseY()`, `getMouseDeltaX()`, `getMouseDeltaY()`, `isMouseInsideViewport()`
  * `isMouseButtonDown(int button)`, `wasMouseButtonJustPressed(int button)`, `wasMouseButtonJustReleased(int button)`
  * `getScrollX()`, `getScrollY()`, `consumeScrollX()`, `consumeScrollY()`
* **Gamepad**:
  * `isGamepadConnected()`, `isGamepadConnected(int joystickId)`, `getActiveGamepadId()`
  * `isGamepadButtonDown(int button)`, `isGamepadButtonDown(int joystickId, int button)`, `wasGamepadButtonJustPressed(int button)`, `wasGamepadButtonJustReleased(int button)`
  * `getGamepadAxis(int axis)`, `getGamepadAxis(int joystickId, int axis)`, `getRawGamepadAxis(int joystickId, int axis)`
  * `setGamepadDeadzone(float gamepadDeadzone)`, `getGamepadDeadzone()`

### `EngineApplication` & `EngineApplication.Updatable`
Top-Level-Laufzeitkoordinator (`implements AutoCloseable`) für Initialisierung, 60-Hz-Fixed-Timestep-Simulation, 2D/3D-Pipeline-Umschaltung, Input-Routing, `GameHost`-Lebenszyklus und Dual-Window-/Standalone-Ausführung.

* **Funktionales Sub-Interface**:
  * `@FunctionalInterface public interface Updatable { void update(double fixedDeltaSeconds); }`
* **Konstruktoren**:
  * `public EngineApplication(EngineConfig config)`
  * `public EngineApplication(EngineConfig config, StudioBridge studioBridge)`
  * `public EngineApplication(EngineConfig config, StudioBridge studioBridge, GameHost gameHost)`
* **Lebenszyklus & Schleife**:
  * `public void initialize()`
  * `public void run()`
  * `public void stepOnce(double realDeltaSeconds)`
  * `public void close()`
* **Pipeline- & Subsystem-Verwaltung**:
  * `public void switchRenderPipeline(RenderMode mode)`
  * `public void addUpdatable(Updatable updatable)`
  * `public void removeUpdatable(Updatable updatable)`
* **Zugriffsmethoden**:
  * `public EngineAPI getApi()`
  * `public SceneGraph getSceneGraph()`
  * `public WindowManager getWindowManager()`
  * `public IRenderPipeline getActivePipeline()`
  * `public boolean isInitialized()`
  * `public GameHost getGameHost()`

### Weitere Typen in `de.swfdtf.core`
* `EngineLogger`: Thread-sicherer Ringpuffer-Logger (`DEFAULT_CAPACITY = 2000`, `Level`, `LogEntry`, `addListener`, `getRecentEntries`, `setMinimumLevel`).
* `StudioBridge`: Entkopplungsinterface (`init`, `update`, `render`, `onReload`, `dispose`) für `StudioWindowController`.
* `GameHost`: Lebenszyklus-Interface (`init(EngineApplication, boolean)`, `update(double, InputManager)`, `renderUi(double)`, `onReload()`, `dispose()`) zur Entkopplung von `GameRuntimeCoordinator`.

---

## 4. Paket `de.swfdtf.graphics`

### `RenderMode`
```java
public enum RenderMode { MODE_2D, MODE_3D }
```
* `public static RenderMode fromString(String s)` — akzeptiert `"2D"`, `"3D"`, `"MODE_2D"`, `"MODE_3D"` (case-insensitive).

### `IRenderPipeline`
Einheitlicher Render-Pipeline-Vertrag für 2D- und 3D-Backends:
* `void init(int logicalWidth, int logicalHeight)`
* `void beginFrame(int windowWidth, int windowHeight)`
* `void renderSceneGraph(SceneGraph graph)`
* `void endFrame()`
* `RenderMode getMode()`
* `void dispose()`

### `RenderPipeline2D`
Produktive LWJGL-3-/OpenGL-3.3-Core-2D-Pipeline (`implements IRenderPipeline`) mit Offscreen-`Framebuffer`, Premultiplied-Alpha-Blending (`GL_ONE, GL_ONE_MINUS_SRC_ALPHA`), `ColorMatrix4f`, SDF-Rounded-Rectangles, Pivot-Rotation und Letterbox-Blit auf `#0d0e15`.

* **Konstanten**: `DEFAULT_LOGICAL_WIDTH = 1920`, `DEFAULT_LOGICAL_HEIGHT = 1080`, `CLEAR_R = 0x0D / 255.0f`, `CLEAR_G = 0x0E / 255.0f`, `CLEAR_B = 0x15 / 255.0f`, `CLEAR_A = 1.0f`.
* **Statische Helfer**:
  * `public static Rect2f computeLetterboxViewport(int windowW, int windowH, int logicalW, int logicalH)`
* **Zeichenoperationen (in logischen Koordinaten)**:
  * `public void drawRect(float x, float y, float width, float height, float r, float g, float b, float a)`
  * `public void drawRect(float x, float y, float width, float height, float[] rgba)`
  * `public void drawRoundedRect(float x, float y, float width, float height, float cornerRadius, float r, float g, float b, float a)`
  * `public void drawRoundedRect(float x, float y, float width, float height, float cornerRadius, float[] fillRgba, float borderThickness, float[] borderRgba)`
  * `public void drawTexturedQuad(int textureId, float x, float y, float width, float height)`
  * `public void drawTexturedQuad(int textureId, float x, float y, float width, float height, Rect2f uvRect, float alpha)`
  * `public void drawTexturedQuad(int textureId, float x, float y, float width, float height, Rect2f uvRect, float[] tintRgba, ColorMatrix4f colorMatrix)`
  * `public void drawTexturedQuad(int textureId, float x, float y, float width, float height, Rect2f uvRect, float[] tintRgba, ColorMatrix4f colorMatrix, float rotateDegrees, float originX, float originY)`
  * `public void drawTexturedQuadPivot(int textureId, float x, float y, float width, float height, Rect2f uvRect, float[] tintRgba, ColorMatrix4f colorMatrix, float rotateDegrees, float pivotX, float pivotY)`
* **Zustand & Custom-Render-Kontext**:
  * `public Matrix4f getLogicalProjection()`, `public Rect2f getCurrentRenderBounds()`, `public float getCurrentEffectiveAlpha()`, `public ColorMatrix4f getCurrentEffectiveColorMatrix()`, `public float getCurrentRotationDegrees()`, `public float getCurrentAnchorX()`, `public float getCurrentAnchorY()`
  * `public Rect2f getLastLetterboxViewport()`, `public Framebuffer getOffscreenFramebuffer()`, `public int getLogicalWidth()`, `public int getLogicalHeight()`, `public boolean isInitialized()`

### `RenderPipeline3DStub`
Architektonischer 3D-Bühnen-Stub (`implements IRenderPipeline`) mit Perspektiv- und Kamera-Matrizen sowie kontrolliertem `EngineLogger`-Warning-Logging.

* **Konstanten**: `DEFAULT_FOV_DEGREES = 75.0f`, `Z_NEAR = 100.0f`, `Z_FAR = 100000.0f`.
* **Konstruktoren**: `RenderPipeline3DStub()`, `RenderPipeline3DStub(EngineLogger logger)`, `RenderPipeline3DStub(EngineLogger logger, boolean glEnabled)`.
* **Methoden**:
  * `public static float computeCameraDistanceZ(int logicalWidth, float fovDegrees)`
  * `public Matrix4f getProjectionMatrix()`, `public Matrix4f getViewMatrix()`, `public float getCameraDistanceZ()`, `public float getFovDegrees()`, `public int getLogicalWidth()`, `public int getLogicalHeight()`, `public boolean isInitialized()`, `public boolean isWarningLogged()`

### `SceneGraph`, `SceneLayer`, `SceneNode`, `SpriteNode` & `CustomDisplayable`
Hierarchischer, Z-sortierter Szenengraph mit 8 vorregistrierten Standard-Ebenen und erweiterbaren VN-Sprites:
* **`SceneGraph`**:
  * Konstanten: `LAYER_BACKGROUND = "background"`, `LAYER_MASTER = "master"`, `LAYER_FOREGROUND = "foreground"`, `LAYER_SCREENS = "screens"`, `LAYER_OVERLAY = "overlay"`, `LAYER_MODAL = "modal"`, `LAYER_TOAST = "toast"`, `LAYER_INSPECTOR = "inspector"`.
  * Methoden: `getLayer(String)`, `getOrCreateLayer(String, int)`, `getLayersSorted()`, `addNode(String, SceneNode)`, `removeByTag(String, String)`, `clearAll()`, `getScreenShake()` / `setScreenShake(ScreenShake)`, `update(float dt)`, `setNormalizedMouse(float nx, float ny)`, `getNormalizedMouseX()`, `getNormalizedMouseY()`, `getNormalizedMouse()`.
* **`SceneLayer`**:
  * Z-Konstanten: `Z_BACKGROUND = -100`, `Z_MASTER = 0`, `Z_FOREGROUND = 100`, `Z_SCREENS = 200`, `Z_OVERLAY = 300`, `Z_MODAL = 400`, `Z_TOAST = 500`, `Z_INSPECTOR = 1000`.
  * Methoden: `getName()`, `getZOrder()` / `setZOrder(int)`, `isVisible()` / `setVisible(boolean)`, `getParallaxFactor()` / `setParallaxFactor(float)`, `addNode(SceneNode)`, `removeNode(SceneNode)`, `removeByTag(String)`, `findByTag(String)`, `findById(String)`, `clear()`, `size()`, `isEmpty()`, `getNodes()`, `getSortedNodes()`.
* **`SceneNode`**:
  * Konstruktoren: `SceneNode()`, `SceneNode(String id)`, `SceneNode(String id, String tag)`.
  * Hilfsmethode: `public static float[] parseHexRgba(String hex)`.
  * Fluent-Eigenschaften: `id`, `tag`, `zOrder`, `visible`, `transform` (`Transform2D`), `width`/`height`/`setSize(w, h)`, `fillColor` (`setFillColorRgba`, `setFillColor`, `setFillColorPremultiplied`, `setColorHex`, `getPremultipliedFillColor`), `cornerRadius`, `borderThickness`, `borderColor` (`setBorderColorRgba`, `setBorderColor`, `setBorderColorHex`, `getPremultipliedBorderColor`), `textureId`, `uvRect` (`Rect2f`), `userData`.
  * Hierarchie & Custom-Render: `getParent()`, `addChild(SceneNode)`, `removeChild(SceneNode)`, `clearChildren()`, `getChildren()`, `getCustomRenderer()` / `setCustomRenderer(BiConsumer<SceneNode, RenderPipeline2D>)`, `protected boolean shouldDrawStandardQuad()`, `protected void onCustomRender(RenderPipeline2D pipeline)`.
* **`SpriteNode` (`extends SceneNode`)**:
  * Konstruktoren: `SpriteNode()`, `SpriteNode(String id)`, `SpriteNode(String id, String tag)`, `SpriteNode(String id, String imageTag, List<String> attributes)`.
  * Methoden: `getImageTag()` / `setImageTag(String)`, `getAttributes()` / `setAttributes(List<String>)`, `getAssetPath()` / `setAssetPath(String)`, `getTexture()` / `setTexture(Texture)`, `getNineSlice()` / `setNineSlice(NineSliceDrawable)`, `getCustomDisplayable()` / `setCustomDisplayable(CustomDisplayable)`, `update(double dt)`.
* **`CustomDisplayable` (`interface`)**:
  * `void update(double dt)`
  * `void render(RenderPipeline2D pipeline, Rect2f bounds, float alpha, ColorMatrix4f colorMatrix)`
  * `default float getPreferredWidth()` / `default float getPreferredHeight()`

### `SceneController` (M3 + M6, Binding `scene`)
High-Level-Bühnensteuerung für `scene`/`show`/`hide` auf `SceneGraph` (`LAYER_BACKGROUND`, `LAYER_MASTER`) inklusive Positionserhalt beim Tag-Wechsel, `LayeredImage`-Auswertung und deterministischer Stage-Snapshots.
* **Records**:
  * `public record StageEntry(String tag, List<String> attributes, String positionName, String assetPath, Transform2D transform)`
  * `public record StageSnapshot(String backgroundTag, String backgroundAssetPath, Transform2D backgroundTransform, Map<String, StageEntry> activeSprites)`
* **Konstruktoren**:
  * `public SceneController(SceneGraph sceneGraph)`
  * `public SceneController(SceneGraph sceneGraph, TextureManager textureManager, EngineLogger logger, Path projectRoot, boolean glTextureUploadEnabled)`
* **Bühnensteuerung & Registry**:
  * `public void setScene(String sceneName, String transitionExpr)`
  * `public SpriteNode showSprite(String tag, List<String> attributes, String positionOrTransform, String transitionExpr)`
  * `public boolean hideSprite(String tag, String transitionExpr)`
  * `public void registerImageDefinition(String key, String assetPath)`
  * `public void registerTransform(String name, Transform2D transform)`
  * `public void registerLayeredImage(VJavaAst.LayeredImageDef def)`
  * `public VJavaAst.LayeredImageDef getLayeredImageDefinition(String name)`
  * `public void setStateContext(StateStore stateStore, PersistentAccess persistentAccess)`
  * `public void update(double dt)`
  * `public void clearMasterSprites()`
* **Snapshots & Abfragen**:
  * `public StageSnapshot snapshotState()` / `public void restoreState(StageSnapshot snapshot)`
  * `public SpriteNode getActiveSprite(String tag)` / `public Map<String, SpriteNode> getActiveSprites()`
  * `public String getBackgroundTag()` / `public SpriteNode getBackgroundNode()` / `public String getLastTransitionExpr()` / `public SceneGraph getSceneGraph()`
  * `public void setTransitionListener(BiConsumer<String, String> listener)` / `public void setProjectRoot(Path projectRoot)`

### `LayeredImage` (M6, `implements CustomDisplayable`)
Mehrstufiges Composite-Sprite aus `VJavaAst.LayeredImageDef` oder `LayeredImage.builder(name)` mit `always`-, `group`- (inkl. `0.20s`-Crossfade und `LipSyncAnalyzer`-Anbindung) und `if`-Ebenen:
* **Konstanten**: `DEFAULT_CROSSFADE_DURATION_SECONDS = 0.20f`, `DEFAULT_MOUTH_GROUP_NAME = "mouth"`.
* **Record & Builder**: `LayerRenderInfo(String assetPath, float alpha)`; `LayeredImage.Builder` (`always`, `group`, `attribute`, `conditional`, `textureManager`, `crossfadeDuration`, `mouthGroupName`, `build`).
* **Konstruktoren & Fabrik**: `LayeredImage(VJavaAst.LayeredImageDef)`, `LayeredImage(VJavaAst.LayeredImageDef, TextureManager)`, `LayeredImage.builder(String name)`.
* **Methoden**: `setAttributes(Collection<String>)`, `setAttributes(Collection<String>, StateStore, PersistentAccess)`, `resolve(StateStore, PersistentAccess)`, `bindLipSync(LipSyncAnalyzer)`, `applyMouthShape(LipSyncAnalyzer.MouthShape)`, `update(double)`, `render(RenderPipeline2D, Rect2f, float, ColorMatrix4f)`, `getPreferredWidth()`, `getPreferredHeight()`, `getActiveRenderLayers()`, `registerLayerTexture(String, Texture)`, `getName()`, `getResolvedLayerAssets()`, `getSelectedAttributes()`, `getActiveAttribute(String)`, `getActiveGroupAttributes()`, `getGroupPreviousAlpha(String)`, `getGroupCurrentAlpha(String)`, `getGroupFadeProgress(String)`, `isCrossfading()`, `getCrossfadeDurationSeconds()` / `setCrossfadeDurationSeconds(float)`, `getMouthGroupName()` / `setMouthGroupName(String)`, `getBoundLipSync()`, `getTextureManager()` / `setTextureManager(TextureManager)`.

### `Texture`, `TextureManager` & `NineSliceDrawable`
Reine STB-Texturpipeline (`STBImage` / `STBImageWrite`, Premultiplied `RGBA8`) ohne `java.awt`:
* **`Texture`**:
  * Konstruktoren: `Texture(int textureId, int width, int height, String name, boolean premultiplied, boolean fallback)`, `Texture(int textureId, int width, int height, String name, boolean premultiplied)`.
  * Statisch: `premultiplyRgba(byte[] straightRgba)`, `createFromRgba(int width, int height, byte[] rgbaBytes, boolean alreadyPremultiplied, String name)`.
  * Instanz: `bind(int textureUnit)`, `unbind()`, `dispose()`, `getTextureId()`, `getWidth()`, `getHeight()`, `getName()`, `isPremultiplied()`, `isFallback()`, `isDisposed()`.
* **`TextureManager`**:
  * Konstanten: `DEFAULT_FALLBACK_WIDTH = 128`, `DEFAULT_FALLBACK_HEIGHT = 128`.
  * Konstruktoren: `TextureManager()`, `TextureManager(EngineLogger)`, `TextureManager(Path assetRoot)`, `TextureManager(EngineLogger, Path assetRoot)`.
  * Statisch: `generateMissingAssetRgba(int width, int height)`, `writePng(Path destination, int width, int height, byte[] straightRgba)`, `readImageFileRgba(Path path, int[] outSize)`.
  * Instanz: `loadTexture(String assetPath)`, `loadTexture(Path path)`, `getOrCreateFallbackTexture()`, `getCached(String assetPath)`, `getCached(Path path)`, `getCacheSize()`, `getAssetRoot()` / `setAssetRoot(Path)`, `getLogger()`, `clearCache()`, `dispose()`.
* **`NineSliceDrawable`**:
  * Record: `public record SliceQuad(Rect2f destRect, Rect2f uvRect)`.
  * Konstruktoren: `NineSliceDrawable(Texture, float inset)`, `NineSliceDrawable(Texture, float left, float top, float right, float bottom)`, `NineSliceDrawable(int textureId, float texWidth, float texHeight, float left, float top, float right, float bottom)`.
  * Methoden: `computeSlices(float x, float y, float width, float height)`, `render(RenderPipeline2D, float x, float y, float width, float height)`, `render(..., float[] tintRgba, ColorMatrix4f)`, `render(..., float[] tintRgba, ColorMatrix4f, float rotateDegrees, float anchorX, float anchorY)`, `getTexture()`, `getTextureId()`, `getTexWidth()`, `getTexHeight()`, `getLeft()`, `getTop()`, `getRight()`, `getBottom()`.

### `TextTagParser`, `FontAtlas`, `FontManager` & `StyledTextLayout`
Rich-Text-, Font-Atlas-, Furigana-, Barrierefreiheits- und Typewriter-Subsystem:
* **`TextTagParser`**:
  * Konstante: `public static final char OBJECT_REPLACEMENT_CHAR = '\uFFFC'`.
  * Enum: `CueKind { WAIT_CLICK, WAIT_TIMED, PARAGRAPH, NO_WAIT, FAST }`.
  * Records: `TextStyle` (+ `TextStyle.DEFAULT`, `resolveFontSize(float)`), `TextCue(int index, CueKind kind, double seconds)`, `RubyPair(int startIndex, int endIndex, String baseText, String rubyText)`, `InlineImageSpan(int charIndex, String imagePath, float width, float height)`, `TextSpan(String text, TextStyle style, String rubyReading, String inlineImagePath)` (+ `withRubyReading`), `ParsedRichText(String plainText, List<TextSpan> spans, List<TextCue> cues, int fastPrefixEndIndex, boolean hasNoWait)` (+ `rubyPairs()`, `inlineImages()`, `spokenText()`).
  * Statische Methoden: `interpolateVariables(String raw, Function<String, Object> lookup)`, `parse(String raw)`, `parse(String raw, Function<String, Object> lookup)`.
* **`FontAtlas` (`implements AutoCloseable`)**:
  * Record: `GlyphMetrics(int codepoint, float advanceWidth, float leftSideBearing, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1)` (+ `width()`, `height()`).
  * Fabriken: `createBuiltinFallback(float baseSize, boolean uploadGl)`, `fromTrueTypeBytes(byte[] fontBytes, float baseSize, boolean uploadGl)`, `fromTrueTypeFile(Path fontPath, float baseSize, boolean uploadGl) throws IOException`.
  * Methoden: `uploadToOpenGl()`, `getGlyph(int codepoint)`, `ensureCodepoints(String text)`, `getKerning(int cp1, int cp2, float fontSize)`, `measureTextWidth(String text, float fontSize, float extraKerning)`, `hasGlyph(int codepoint)`, `getGlTextureId()`, `getBaseSize()`, `getAscent()`, `getAscent(float fontSize)`, `getDescent()`, `getLineGap()`, `getLineHeight()`, `getLineHeight(float fontSize)`, `getAtlasWidth()`, `getAtlasHeight()`, `isBuiltinFallback()`, `isDisposed()`, `getAtlasRgbaCopy()`, `dispose()`, `close()`.
* **`FontManager` (`implements AutoCloseable`)**:
  * Konstruktoren: `FontManager()`, `FontManager(Path assetRoot, boolean systemFontFallbackEnabled)`.
  * Statisch: `discoverSystemFontPath(boolean dyslexiaFriendly)`.
  * Registry & Auflösung: `registerFont(String fontKey, byte[] fontBytes)`, `registerFont(String fontKey, Path filePath) throws IOException`, `setAssetRoot(Path)` / `getAssetRoot()`, `setDefaultFontPath(String)` / `getDefaultFontPath()`, `setDyslexiaFontPath(String)` / `getDyslexiaFontPath()`, `setSystemFontFallbackEnabled(boolean)` / `isSystemFontFallbackEnabled()`.
  * Barrierefreiheit & Caching: `setDyslexiaFriendlyMode(boolean)` / `isDyslexiaFriendlyMode()`, `setGlobalTextScale(float)` / `getGlobalTextScale()`, `scaleFontSize(float)`, `getAccessibilityExtraKerning()`, `getAccessibilityLineSpacingMultiplier()`, `getAtlas(String fontPath, float sizePx, boolean uploadGl)`, `getCachedAtlasCount()`, `isDisposed()`, `dispose()`, `close()`.
* **`StyledTextLayout` & `StyledTextLayout.TypewriterController`**:
  * Enum: `DecorationKind { UNDERLINE, STRIKETHROUGH, DROP_SHADOW, OUTLINE }`.
  * Records: `GlyphQuad`, `LineDecoration`, `RubyLayout`, `InlineImageLayout`, `HyperlinkHitbox`, `TextLine`.
  * Konstruktor / Fabrik: `StyledTextLayout(ParsedRichText, FontManager, float maxWidth, float baseFontSize, float lineSpacing, String baseColorHex, boolean uploadGl)`, `StyledTextLayout.layout(...)`.
  * Rendering & Hit-Testing: `setDropShadow(float offsetX, float offsetY, String shadowColorHex)`, `setOutline(float thickness, String outlineColorHex)`, `render(RenderPipeline2D, TextureManager, float originX, float originY, double elapsedTimeSeconds)`, `render(..., int visibleCharLimit, float globalAlpha)`, `hitTestHyperlink(float localX, float localY)`, `hitTestHyperlink(float canvasX, float canvasY, float originX, float originY)`.
  * Getter: `getRichText()`, `getMaxWidth()`, `getBaseFontSize()`, `getLineSpacing()`, `getGlyphQuads()`, `getDecorations()`, `getRubyLayouts()`, `getInlineImages()`, `getHyperlinkHitboxes()`, `getLines()`, `getLineCount()`, `getTotalWidth()`, `getTotalHeight()`, `getShadowOffsetX()`, `getShadowOffsetY()`, `getOutlineThickness()`.
  * **`TypewriterController`**: `DEFAULT_CPS = 40.0f`; Konstruktoren `TypewriterController(ParsedRichText)` / `TypewriterController(ParsedRichText, float defaultCps)`; Steuerung `reset()`, `advance(double dt)`, `clickOrAdvance()`, `isWaiting()`, `isTimedWait()`, `getWaitRemainingSeconds()`, `getActiveWaitCue()`, `isFinished()`, `getVisibleCharCount()`, `getTotalCharCount()`, `hasNoWait()`, `shouldAutoAdvance()`, `getDefaultCps()`, `setDefaultCps(float)`.

### `ShaderProgram`, `Framebuffer`, `GlfwWindow` & `WindowManager`
* **`ShaderProgram`**: OpenGL-3.3-Core-GLSL-Programm (`ShaderProgram()`, `ShaderProgram(vertexSrc, fragmentSrc)`, `compile`, `bind`, `unbind`, `setUniformMat4` (`float[]` / `Matrix4f`), `setUniformVec4`, `setUniformVec2`, `setUniformFloat`, `setUniformInt`, `getUniformLocation`, `getProgramId`, `isCompiled`, `dispose`).
* **`Framebuffer`**: Offscreen-`RGBA8`-FBO (`Framebuffer()`, `Framebuffer(width, height)`, `init`, `resize`, `bind`, `unbind`, `readPixelsRgba`, `getTextureId`, `getFboId`, `getWidth`, `getHeight`, `isInitialized`, `dispose`).
* **`GlfwWindow`**: Wrapper um natives GLFW-Fenster (`GlfwWindow(handle, title, w, h)`, `makeCurrent`, `swapBuffers`, `shouldClose`, `setShouldClose`, `setTitle`, `setVsync`, `pollFramebufferSize`, `show`, `hide`, `focus`, `destroy`, `getHandle`, `getTitle`, `getWindowWidth`, `getWindowHeight`, `getFramebufferWidth`, `getFramebufferHeight`, `getGlCapabilities`, `isVsync`, `isDestroyed`).
* **`WindowManager`**: GLFW-Lebenszyklus & Fenstererzeugung (`WindowManager()`, `WindowManager(EngineLogger)`, `initDualWindows(...)`, `initStandaloneWindow(...)`, `getGameWindow()`, `getStudioWindow()`, `hasStudioWindow()`, `pollEvents()`, `anyWindowShouldClose()`, `isGlfwInitialized()`, `dispose()`).

### `ATLTransform` & `AnimationEngine` (M5)
Deklarative ATL-Transformations-Timelines und knotengebundene Animationsverwaltung:
* **`ATLTransform`**:
  * Fabriken & Parser: `fromAst(List<VJavaAst.AtlStep>)`, `fromAst(VJavaAst.TransformDef)`, `builder()`, `parseColorMatrix(String)`, `parseCropRect(String)`.
  * Ablaufsteuerung: `start(Transform2D)`, `update(double dt, Transform2D)`, `isFinished()`, `isStarted()`, `getElapsedSeconds()`, `reset()`.
  * **`ATLTransform.Builder`**: `set()`, `set(String, String)`, `linear(double)`, `ease(double)`, `easein(double)` / `easeIn(double)`, `easeout(double)` / `easeOut(double)`, `easeInOut(double)`, `smoothstep(double)`, `interpolate(double, Easing.EaseType)`, `cubicBezier(double, float, float, float, float)`, `pause(double)`, `repeat()`, `repeat(int)`, `repeat(int, ATLTransform)`, `parallel(...)`, `block(...)`, `choice(...)`, `step(VJavaAst.AtlStep)`, Property-Methoden (`xpos`, `ypos`, `xanchor`, `yanchor`, `xalign`, `yalign`, `zoom`, `xzoom`, `yzoom`, `rotate`, `alpha`, `blur`, `parallaxX`, `parallaxY`, `crop`, `colorMatrix`, `matrixcolor`, `property`), `build()`.
* **`AnimationEngine`**:
  * Methoden: `play(String id, SceneNode node, ATLTransform anim)`, `play(String id, SceneNode node, ATLTransform anim, Runnable onComplete)`, `stop(String id)`, `stopAll()`, `update(double dt)`, `isPlaying(String id)`, `getActiveCount()`, `getAnimation(String id)`, `getActiveIds()`.

### `Transition` & `TransitionManager` (M5)
Szenenübergangs-Deskriptoren und Dual-FBO-OpenGL-Compositor:
* **`Transition`**:
  * Enum `Kind`: `NONE`, `DISSOLVE`, `FADE`, `PIXELLATE`, `MOVE`, `EASE`, `WIPE_LEFT`, `WIPE_RIGHT`, `WIPE_UP`, `WIPE_DOWN`, `SLIDE_LEFT`, `SLIDE_RIGHT`, `PUSH_LEFT`, `PUSH_RIGHT`, `IRIS_IN`, `IRIS_OUT`, `BLINDS`, `SQUARES`, `IMAGE_DISSOLVE`, `MULTIPLE`.
  * Konstanten: `DEFAULT_DURATION_SECONDS = 0.5f`, `DEFAULT_FADE_COLOR_HEX = "#000000"`, `DEFAULT_RAMP_LENGTH = 8.0f`, `DEFAULT_SLICE_COUNT = 16`.
  * Fabriken & Parser: `none()`, `dissolve(float)`, `fade(float)`, `fade(float, String)`, `pixellate(float)`, `move(float)`, `ease(float)`, `ease(float, Easing.EaseType)`, `wipeLeft(float)`, `wipeRight(float)`, `wipeUp(float)`, `wipeDown(float)`, `slideLeft(float)`, `slideRight(float)`, `pushLeft(float)`, `pushRight(float)`, `irisIn(float)`, `irisOut(float)`, `blinds(float, int)`, `squares(float, int)`, `imageDissolve(String, float, float, boolean)`, `multiple(Transition...)`, `multiple(List<Transition>)`, `parse(String)`.
  * Getter: `getKind()`, `getDurationSeconds()`, `getFadeColorHex()`, `getFadeColorRgba()`, `getMaskImagePath()`, `getRampLength()`, `isReverseMask()`, `getSliceCount()`, `getSubTransitions()`, `getEaseType()`.
* **`TransitionManager`**:
  * Timeline: `startTransition(Transition)`, `update(double dt)`, `cancel()`, `isActive()`, `getProgress()`, `getActiveTransition()`, `getActiveStepIndex()`, `getActiveStepProgress()`, `getCurrentEffectiveTransition()`.
  * OpenGL-Compositor: `initGl(int logicalW, int logicalH)`, `captureFromFbo(Framebuffer src)`, `renderComposite(Framebuffer toFbo, TextureManager texMgr, int targetWidth, int targetHeight)`, `disposeGl()`, `isGlInitialized()`, `getFromFramebuffer()`.

### `ParticleSystem`, `VideoPlayer` & `MovieSprite` (M5)
2D-Partikelsystem (`CustomDisplayable`) und Video-Playback:
* **`ParticleSystem` (`implements CustomDisplayable`)**:
  * Konstanten: `DEFAULT_WIDTH = RenderPipeline2D.DEFAULT_LOGICAL_WIDTH`, `DEFAULT_HEIGHT = RenderPipeline2D.DEFAULT_LOGICAL_HEIGHT`.
  * Enum `EmitterKind`: `SNOW_BLOSSOM`, `RAIN`, `CHERRY_BLOSSOM`, `SPARKS`, `DUST`.
  * Innere Klassen: `Particle`, `ParticleEmitter` (`update`, `render`, `getKind`, `getParticles`, `getParticleCount`, `setBounds`, `setWindX`, `setSpeed`, `setTexture`, `setActive`).
  * Presets: `snowBlossom(int, float, float[, Random])`, `rain(int, float, float[, Random])`, `cherryBlossoms(int[, Random])`, `sparks(int[, Random])`, `dust(int[, Random])`.
  * System & Szene: `ParticleSystem()`, `ParticleSystem(float, float)`, `addEmitter`, `removeEmitter`, `clearEmitters`, `getEmitters`, `getTotalParticleCount`, `update`, `render`, `getPreferredWidth`, `getPreferredHeight`, `toSceneNode(String)`, `attachToForeground(SceneGraph)`.
* **`VideoPlayer` (`implements AutoCloseable`)**:
  * Enum `State { STOPPED, PLAYING, PAUSED, ENDED }`; Interface `FrameSource extends AutoCloseable`; Implementierungen `ProceduralTestFrameSource` und `FfmpegProcessFrameSource` (`isFfmpegAvailable()`).
  * Konstruktoren: `VideoPlayer()`, `VideoPlayer(boolean glEnabled)`, `VideoPlayer(EngineLogger logger, boolean glEnabled)`.
  * Methoden: `play(Path)`, `play(FrameSource)`, `pause()`, `resume()`, `stop()`, `setLoop(boolean)` / `isLoop()`, `update(double dt)`, `close()`, `getCurrentTexture()`, `getLatestFrameRgba()`, `getFrameSource()`, `getState()`, `isPlaying()`, `isPaused()`, `isStopped()`, `isEnded()`, `getCurrentFrameIndex()`, `getWidth()`, `getHeight()`, `getFps()`, `isGlEnabled()` / `setGlEnabled(boolean)`.
* **`MovieSprite` (`extends SpriteNode implements AutoCloseable`)**:
  * Konstruktoren: `MovieSprite()`, `MovieSprite(String id)`, `MovieSprite(boolean glEnabled)`, `MovieSprite(String id, boolean glEnabled)`, `MovieSprite(String id, VideoPlayer)`, `MovieSprite(String id, String tag, VideoPlayer)`.
  * Methoden: `play(Path)`, `play(VideoPlayer.FrameSource)`, `pause()`, `resume()`, `stop()`, `setLoop(boolean)` / `isLoop()`, `update(double dt)`, `getVideoPlayer()`, `close()`.

---

## 5. Paket `de.swfdtf.git`

### `GitController`
Nicht-interaktive Git-CLI-Steuerung über `ProcessBuilder`.

* **Konstanten**:
  * `public static final Set<String> ALLOWED_COMMIT_TYPES = Set.of("feat", "fix", "arch", "render", "parser", "audio", "ui", "docs", "test", "build")`
  * `public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30)`
* **Ergebnis-Record**:
  * `public record CommandResult(int exitCode, String stdout, String stderr, boolean success)`
* **Konstruktoren**:
  * `public GitController(Path repoRoot)`
  * `public GitController(Path repoRoot, Duration commandTimeout)`
* **Methoden**:
  * `public Path getRepoRoot()`
  * `public boolean isGitRepository()`
  * `public CommandResult init()`
  * `public CommandResult status()`
  * `public CommandResult add(List<Path> files)`
  * `public CommandResult commit(String type, String module, String summary, List<String> bulletPoints)`
  * `public static String formatCommitMessage(String type, String module, String summary, List<String> bulletPoints)`
  * `public CommandResult push(String remote, String branch)`
  * `public CommandResult log(int limit)`

---

## 6. Paket `de.swfdtf.math`

### `ColorMatrix4f`
Unveränderliche 4×4-Farbtransformationsmatrix (Column-Major) für Premultiplied-Alpha-RGBA `(R*A, G*A, B*A, A)`.

* **Konstanten**: `LUMA_R = 0.2126f`, `LUMA_G = 0.7152f`, `LUMA_B = 0.0722f`
* **Fabrikmethoden**:
  * `identity()`, `ofColumnMajor(float[] m)`, `ofRowMajor(float[] m)`
  * `brightness(float b)`, `contrast(float c)`, `saturation(float s)`, `hueRotate(float degrees)`, `tint(float r, float g, float b)`, `opacity(float a)`, `invert()`
* **Operationen**:
  * `multiply(ColorMatrix4f other)`, `lerp(ColorMatrix4f target, float t)`, `toFloatArray()`, `applyPremultiplied(float rPre, float gPre, float bPre, float a)`

### `Transform2D`
Veränderbarer 2D-Transformationszustand für Sprites und Szenengraph-Knoten (Fluent-Setter geben `Transform2D` zurück).

* **Konstruktor**: `public Transform2D()`
* **Eigenschaften (Getter/Setter)**:
  * `xpos`, `ypos`, `xanchor`, `yanchor`, `xalign` (+ `hasXalign()`), `yalign` (+ `hasYalign()`), `zoom`, `xzoom`, `yzoom`, `rotate`, `alpha`, `blur`, `colorMatrix` (`ColorMatrix4f`), `crop` (`Rect2f`), `parallaxX`, `parallaxY`
* **Methoden**:
  * `public Transform2D copy()`
  * `public Transform2D lerpTo(Transform2D target, float t)`
  * `public Rect2f resolveBounds(float width, float height, float containerWidth, float containerHeight)`

### `Easing` & `Easing.EaseType`
Standard-Easing-Kurven und kubische Bézier-Evaluation.

* **Enum**: `EaseType { LINEAR, EASE, EASE_IN, EASE_OUT, EASE_IN_OUT, SMOOTHSTEP }`
* **Statische Methoden**:
  * `linear(float t)`, `ease(float t)`, `easeIn(float t)`, `easeOut(float t)`, `easeInOut(float t)`, `smoothstep(float t)`
  * `cubicBezier(float t, float p1x, float p1y, float p2x, float p2y)`
  * `evaluate(EaseType type, float t)`

### `ScreenShake`
Gedämpfter harmonischer Oszillator: `Delta(t) = A * exp(-lambda * t) * sin(omega * t)`.

* **Konstanten**: `DEFAULT_AMPLITUDE = 30.0f`, `DEFAULT_DECAY = 5.0f`, `DEFAULT_OMEGA = 40.0f`, `DEFAULT_DURATION_SECONDS = 1.0f`
* **Fabrikmethoden**:
  * `vpunch()`, `hpunch()`, `custom(float ampX, float ampY, float decay, float omega, float durationSeconds)`
* **Methoden**:
  * `update(float dt)`, `getOffset()` (`Vec2f`), `isFinished()`, `reset()`, `getAmplitudeX()`, `getAmplitudeY()`, `getDecay()`, `getOmega()`, `getDurationSeconds()`, `getElapsed()`

### Weitere Typen in `de.swfdtf.math`
* `Vec2f(float x, float y)`: `ZERO`, `ONE`, `HALF`, `add`, `sub`, `scale`, `lerp`, `length`, `distance`.
* `Rect2f(float x, float y, float width, float height)`: `right`, `bottom`, `centerX`, `centerY`, `contains`, `intersects`, `translate`, `inset`.

---

## 7. Paket `de.swfdtf.parser` (M3 + M4)

### `VJavaLexer` & `VJavaParseException`
Einrückungs- und Java-Block-bewusster Lexer (`4` Leerzeichen bzw. Tabulator = `4` Spalten, `#`/`//`-Kommentare, `@->{ ... }<<` unter Beachtung von Java-Strings/Textblöcken/Kommentaren).
* **`VJavaLexer.TokenType`**: `INDENT`, `DEDENT`, `LINE`, `JAVA_BLOCK`, `EOF`.
* **`VJavaLexer.Token`**: `Token(TokenType type, String sourceFile, int lineNumber, int indentColumns, String text, String description, String code)` mit Accessoren `type()`, `sourceFile()`, `lineNumber()`, `indentColumns()`, `text()`, `description()`, `code()`.
* **Methoden**:
  * `public static List<Token> tokenize(Path file) throws IOException`
  * `public static List<Token> tokenize(String source, String sourceFile)`
* **`VJavaParseException` (`extends RuntimeException`)**: Konstruktoren `(String sourceFile, int lineNumber, String message)` und `(..., Throwable cause)`; Getter `getSourceFile()`, `getLineNumber()`.

### `VJavaParser` & `VJavaAst`
Rekursiv-absteigender `.vjava`-Parser und unveränderliche AST-Datenstrukturen:
* **`VJavaParser`**:
  * `public static ScriptFileAst parse(Path filePath) throws IOException`
  * `public static ScriptFileAst parse(Path filePath, String source)`
  * `public static ScriptFileAst parse(List<Token> tokens)`
  * `public static ScriptFileAst parse(Path filePath, List<Token> tokens)`
* **`VJavaAst`**:
  * `Statement` (`sealed interface` mit `sourceFile()`, `lineNumber()`): `JavaBlockStatement`, `SceneStatement`, `ShowStatement`, `HideStatement`, `SayStatement`, `JumpStatement`, `CallStatement`, `ReturnStatement`, `PlayStatement`, `QueueStatement`, `StopStatement`, `VoiceStatement`, `NvlStatement`, `BlockRollbackStatement`, `InputStatement`, `MenuStatement` (+ `MenuChoice`), `IfStatement` (+ `IfBranch`), `SetVarStatement`, `PauseStatement`, `WithStatement`, `WindowStatement`.
  * Top-Level-Records: `JavaBlockNode`, `CharacterDef`, `ImageDef` (`fullKey()`), `LayeredImageDef` (`LayeredEntry`: `AlwaysLayerEntry`, `GroupLayerEntry` mit `AttributeEntry`, `ConditionalLayerEntry`), `TransformDef` (`AtlStep`), `ScreenDef` (`ScreenItem`), `TranslateBlock` (`TranslateEntry`), `LabelNode`, `ScriptFileAst`, `ProjectAst`.

### `ProjectScanner`
Rekursiver `.vjava`-Projekt- und Asset-Scanner.
* **Konstante**: `public static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", "build", ".gradle", "saves", "out", "dist")`
* **Methode**: `public static ProjectAst scan(Path projectRoot)`

### `JavaBlockCompiler`, `VJavaExecutable` & `VJavaCompileException`
In-Memory-Java-21-Compiler via `javax.tools.JavaCompiler` für `@->{ ... }<<`-Blöcke und benutzerdefinierte Typen (`de.swfdtf.vjava.gen.VJavaUserTypes` + `VJavaSnippet_<i>`).
* **`VJavaExecutable` (`@FunctionalInterface`)**:
  * `void execute(StateStore state, PersistentAccess persistent, EngineAPI engine, SceneController scene) throws Exception;`
* **`JavaBlockCompiler`**:
  * Konstanten: `GENERATED_PACKAGE = "de.swfdtf.vjava.gen"`, `USER_TYPES_SIMPLE_NAME = "VJavaUserTypes"`, `USER_TYPES_BINARY_NAME = GENERATED_PACKAGE + "." + USER_TYPES_SIMPLE_NAME`.
  * Methoden: `compile(VJavaAst.ProjectAst)`, `compile(List<VJavaAst.JavaBlockNode>)`, `collectJavaBlocks(VJavaAst.ProjectAst)`.
  * **`CompiledProjectBlocks`**: `getExecutable(String)`, `requireExecutable(String)`, `hasExecutable(String)`, `getExecutables()`, `getClassLoader()`, `loadUserType(String)`, `findUserType(String)`, `hasUserTypes()`, `size()`.
* **`VJavaCompileException` (`extends RuntimeException`)**:
  * Record: `CompileDiagnostic(String blockId, String sourceFile, int lineNumber, long columnNumber, String kind, String message)` (+ `format()`).
  * Getter: `getBlockId()`, `getSourceFile()`, `getLineNumber()`, `getDiagnosticMessages()`, `getDiagnostics()`, `getGeneratedSource()`.

### `ConditionEvaluator`
Ausdrucks- und Bedingungs-Evaluator für Arithmetik, Vergleiche, Logik (`&&`/`||`/`!`, `and`/`or`/`not`), Ternär-Ausdrücke (`?:`), Feld-/Getter-Zugriffe, Indexierung und Methodenaufrufe:
* `public ConditionEvaluator()`
* `public static Object evaluate(String expr, StateStore state, PersistentAccess persistent)`
* `public static boolean evaluateBoolean(String expr, StateStore state, PersistentAccess persistent)`
* `public static boolean isTruthy(Object value)`

### `ScriptRuntime`
Nicht-blockierende State-Machine-Skript-Laufzeit mit Dialogue-/Menu-/Input-/Pause-Zuständen, Audio-Bridge, Live-Lokalisierung und M4-Snapshot-/Save-Hooks:
* **Enum**: `Status { READY, WAITING_DIALOGUE, WAITING_MENU, WAITING_INPUT, PAUSING, ENDED }`
* **Records & Interfaces**: `DialoguePrompt`, `ChoiceOption`, `MenuPrompt`, `InputPrompt`, `AudioBridge`, `AudioEvent`, `StackFrame`, `ExecutionSnapshot`.
* **Konstruktoren**:
  * `public ScriptRuntime(VJavaAst.ProjectAst project)`
  * `public ScriptRuntime(VJavaAst.ProjectAst project, EngineAPI engineApi)`
  * `public ScriptRuntime(VJavaAst.ProjectAst project, EngineAPI engineApi, StateStore stateStore, PersistentAccess persistentAccess, SceneController sceneController)`
* **Steuerung & Snapshots**:
  * `compile()`, `start()`, `start(String entryLabel)`, `advance()`, `choose(int optionIndex)`, `submitInput(String text)`, `update(double dt)`, `jumpToLabel(String label)`, `snapshotExecution()`, `restoreExecution(ExecutionSnapshot)`, `toSavedPosition()`, `restoreSavedPosition(SaveSlotData.SavedScriptPosition)`
* **Getter & Hooks**:
  * `getStatus()`, `getActiveDialogue()`, `getActiveMenu()`, `getActiveInput()`, `getStateStore()`, `getPersistentAccess()`, `getSceneController()`, `getEngineApi()`, `getProjectAst()`, `getAudioEvents()`, `setAudioBridge(AudioBridge)`, `setLocalizationManager(LocalizationManager)`, `getLocalizationManager()`, `setCheckpointListener(Runnable)`, `getCompiledBlocks()`, `getCurrentLabel()`, `getStatementIndex()`, `isNvlMode()`, `getNvlLines()`, `getActiveVoicePath()`, `getPauseRemainingSeconds()`, `getWindowAction()`.

### `ScriptSession` (M4)
High-Level-Sitzungskoordinator, der `ScriptRuntime` mit der 4-Tier-Persistenz (`SessionState`, `RevertableStateStore`, `PersistentState`, `PreferencesState`), `HistoryLog`, `SaveManager`, `LocalizationManager` sowie `AchievementManager`, `GalleryManager` und `EndingsManager` verbindet und automatisch im `EngineAPI`-Service-Locator registriert.
* **Konstruktoren**:
  * `public ScriptSession(VJavaAst.ProjectAst project, EngineAPI engineApi) throws IOException`
  * `public ScriptSession(VJavaAst.ProjectAst project, EngineAPI engineApi, SceneGraph sceneGraph, TextureManager textureManager, boolean glTextures, Path savesDir) throws IOException`
  * `public ScriptSession(VJavaAst.ProjectAst project, EngineAPI engineApi, SceneController sceneController, Path savesDir) throws IOException`
* **Spielablauf & Rollback**:
  * `start()`, `start(String entryLabel)`, `advance()`, `choose(int optionIndex)`, `submitInput(String text)`, `update(double dt)`
  * `rollback()`, `rollForward()`, `jumpToHistoryEntry(HistoryLog.HistoryEntry entry)`
* **Save / Load & Persistenz**:
  * `createSaveSlotData(String slotId, String customName, int thumbWidth, int thumbHeight, byte[] thumbRgba)`
  * `saveToSlot(String slotId, String customName, int thumbWidth, int thumbHeight, byte[] thumbRgba) throws IOException`
  * `quickSave() throws IOException`, `quickLoad() throws IOException`, `autoSave() throws IOException`
  * `loadFromSlot(String slotId) throws IOException`, `saveMetaState() throws IOException`
* **Getter**:
  * `getRuntime()`, `getStateStore()`, `getPersistentState()`, `getPreferencesState()`, `getSessionState()`, `getHistoryLog()`, `getSaveManager()`, `getLocalizationManager()`, `getAchievementManager()`, `getGalleryManager()`, `getEndingsManager()`, `getSceneController()`, `getEngineApi()`.

---

## 8. Paket `de.swfdtf.state` (M3 + M4)

### `StateStore` & `SimpleStateStore`
Vertrag (Binding `state`) und thread-sichere In-Memory-Basisimplementierung für Sitzungsvariablen und Rollback-Barrieren:
* **Methoden (`StateStore`)**:
  * `set(String key, Object value)`, `get(String key)`, `getOrDefault(String key, Object defaultValue)`, `getString(String key, String defaultValue)`, `getInt(String key, int defaultValue)`, `getDouble(String key, double defaultValue)`, `getBoolean(String key, boolean defaultValue)`
  * `has(String key)`, `remove(String key)`, `keys()`, `snapshotMap()`, `restoreMap(Map<String, Object> snapshot)`, `blockRollback()`, `isRollbackBlocked()`, `clear()`
* **Zusatz in `SimpleStateStore`**: `resetRollbackBlock()`.

### `PersistentAccess` & `SimplePersistentAccess`
Vertrag (Binding `persistent`) und thread-sichere In-Memory-Basisimplementierung für spielstandübergreifende Persistenz:
* **Variablen**: `set(String key, Object value)`, `get(String key)`, `getOrDefault(String key, Object defaultValue)`, `has(String key)`
* **Read-Tracking & Freischaltungen**: `markLineRead(String)` / `isLineRead(String)`, `unlockCg(String)` / `isCgUnlocked(String)` / `getUnlockedCgs()`, `unlockTrack(String)` / `isTrackUnlocked(String)` / `getUnlockedTracks()`, `unlockEnding(String)` / `isEndingUnlocked(String)` / `getUnlockedEndings()`, `unlockAchievement(String)` / `isAchievementUnlocked(String)` / `getUnlockedAchievements()`.

### `Revertable<S>`, `RevertableMap<K, V>`, `RevertableList<E>` & `RevertableStateStore` (M4)
Deterministisches Rollback-/Roll-Forward-Subsystem mit Deep-Safe-Snapshot-Kloning und Checkpoint-Timeline:
* **`Revertable<S>`**: `S captureState()`, `void restoreState(S snapshot)`.
* **`RevertableMap<K, V>` (`extends AbstractMap<K, V> implements Revertable<Map<K, V>>`)**: Konstruktoren `RevertableMap()`, `RevertableMap(Map<? extends K, ? extends V>)`; `captureState()`, `restoreState(...)`; statische Helfer `cloneValue(Object)`, `deepEquals(Object, Object)`.
* **`RevertableList<E>` (`extends AbstractList<E> implements Revertable<List<E>>`)**: Konstruktoren `RevertableList()`, `RevertableList(int)`, `RevertableList(Collection<? extends E>)`; `captureState()`, `restoreState(...)`.
* **`RevertableStateStore` (`implements StateStore`)**:
  * Record: `Checkpoint(long id, Map<String, Object> variablesSnapshot, Object runtimeSnapshot, String summary, boolean rollbackBarrierBefore)`.
  * Methoden: `setRuntimeHooks(Supplier<Object>, Consumer<Object>)`, `recordCheckpoint(String)`, `recordCheckpoint(Object, String)`, `canRollback()`, `rollback()`, `canRollForward()`, `rollForward()`, `jumpToCheckpoint(long)`, `getCurrentCheckpoint()`, `getCheckpoints()`, `getCursorIndex()`, `clearCheckpoints()` sowie alle `StateStore`-Methoden.

### `SessionState`, `PersistentState` & `PreferencesState` (M4)
Tier-1-, Tier-3- und Tier-4-Zustandscontainer:
* **`SessionState`** (Tier 1): `updatePlaytime(double)`, `setFlag(String, boolean)`, `getFlag(String)` / `getFlag(String, boolean)`, `hasFlag(String)`, `removeFlag(String)`, `getSessionFlags()`, `getSessionStartTime()` / `setSessionStartTime(Instant)`, `getPlaytimeSeconds()` / `setPlaytimeSeconds(double)`, `getActiveSaveSlotId()` / `setActiveSaveSlotId(String)`, `getLastAutoSaveTime()` / `setLastAutoSaveTime(Instant)`, `markAutoSaved()`, `reset()`.
* **`PersistentState`** (Tier 3, `implements PersistentAccess`): Konstante `FILE_NAME = "persistent.dat"`; `load(Path)`, `load(Path, ClassLoader)`, `save(Path)`, `getReadLines()`, `getDataSnapshot()`, `clear()` sowie alle `PersistentAccess`-Methoden.
* **`PreferencesState`** (Tier 4): Konstante `FILE_NAME = "preferences.json"`; `load(Path)`, `save(Path)`, `normalizeDefaults()`; Getter/Setter für `masterVolume`, `musicVolume`, `soundVolume`, `voiceVolume`, `ambientVolume`, `textSpeedCps`, `autoForwardDelaySeconds`, `skipUnread`, `skipAfterChoices`, `fullscreen`, `renderMode`, `language`, `dialogueMode`, `selfVoicing`, `highContrastDyslexiaFont`, `uiTextScale`.

### `HistoryLog`, `SaveValueCodec`, `SaveSlotData` & `SaveManager` (M4)
Dialog-Backlog und atomares GZIP-JSON-Save/Load-System:
* **`HistoryLog`**: Konstante `DEFAULT_MAX_ENTRIES = 500`; Record `HistoryEntry(long entryId, long checkpointId, String lineId, String speakerName, String speakerColorHex, String text, String voicePath, Instant timestamp)`; Konstruktoren `HistoryLog()`, `HistoryLog(int)`; Methoden `append(...)`, `getEntries()`, `getRecentEntries(int)`, `truncateAfterCheckpoint(long)`, `clear()`, `snapshot()`, `restore(List<HistoryEntry>)`, `size()`, `isEmpty()`, `getMaxEntries()`.
* **`SaveValueCodec`**: Konstanten `KEY_VJAVA_CLASS = "__vjavaClass"`, `KEY_FIELDS = "fields"`, `KEY_KTYPE = "__ktype"`; Methoden `createGson(boolean prettyPrinting)`, `encode(Object)`, `encodeMap(Map<String, ?>)`, `decode(JsonElement, ClassLoader)`, `decodeMap(JsonObject, ClassLoader)`.
* **`SaveSlotData`**: Record `(SaveMetadata metadata, Map<String, Object> variables, SavedScriptPosition scriptPosition, List<HistoryLog.HistoryEntry> historyEntries)` mit `withMetadata(SaveMetadata)` sowie Sub-Records `SaveMetadata`, `SavedStackFrame`, `SavedScriptPosition`.
* **`SaveManager`**: Konstanten `SAVE_EXTENSION = ".ksav"`, `QUICK_SAVE_SLOT = "quick"`, `AUTO_SAVE_PREFIX = "auto_"`, `DEFAULT_AUTO_SAVE_SLOTS = 3`; statische Helfer `encodeRgbaToPng(int, int, byte[])`, `encodeRgbaToPng(int, int, byte[], boolean)`, `slotIdForPage(int, int, int)`; Methoden `saveSlot(...)`, `loadSlot(...)`, `readSlotMetadata(String)`, `listPageSlots(int, int)`, `quickSave(SaveSlotData)`, `quickLoad(...)`, `autoSave(SaveSlotData)`, `deleteSlot(String)`, `slotExists(String)`, `getSavesDir()`, `getAutoSaveSlotCount()`.

### `AchievementManager`, `GalleryManager` & `EndingsManager` (M4)
Meta-Progression auf Basis von `PersistentAccess`:
* **`AchievementManager`**: Records `AchievementDef(String id, String title, String description, String iconPath, boolean hidden)` und `AchievementToast(AchievementDef achievement, Instant unlockedAt)`; Methoden `register(AchievementDef)`, `unlock(String)`, `isUnlocked(String)`, `pollToast()`, `getAllAchievements()`, `getUnlockedCount()`, `getTotalCount()`, `setOnUnlockListener(Consumer<AchievementToast>)`.
* **`GalleryManager`**: Record `GalleryEntry(String id, String title, String category, String thumbnailPath, List<String> variationPaths, String description)`; Methoden `register(GalleryEntry)`, `unlock(String)`, `isUnlocked(String)`, `getAllEntries()`, `getUnlockedEntries()`, `getEntry(String)`, `getUnlockedCount()`, `getTotalCount()`.
* **`EndingsManager`**: Record `EndingDef(String id, String title, String description, String branchLabel, String parentEndingId)`; Methoden `register(EndingDef)`, `unlock(String)`, `isUnlocked(String)`, `getAllEndings()`, `getEnding(String)`, `getChildEndings(String)`, `getCompletionPercentage()`, `getUnlockedCount()`, `getTotalCount()`.

### `LocalizationManager` (M4)
Live-Lokalisierung, `tl/<lang>/`-Asset-Auflösung und Template-Extraktion:
* Konstruktoren: `LocalizationManager()`, `LocalizationManager(String defaultLanguage)`.
* Methoden: `setLanguage(String)`, `getCurrentLanguage()`, `getDefaultLanguage()`, `addLanguageChangeListener(Consumer<String>)`, `removeLanguageChangeListener(Consumer<String>)`, `indexProject(VJavaAst.ProjectAst)`, `registerTranslation(String language, String keyOrOriginal, String translatedText)`, `translateDialogue(String lineId, String originalText)`, `translateUi(String key, String fallbackText)`, `resolveLocalizedAsset(Path projectRoot, String assetPath)`, `getAvailableLanguages()`, `extractTranslationTemplate(VJavaAst.ProjectAst project, String targetLanguage)`.


---

## 9. Paket `de.swfdtf.audio` (M5)

### `AudioDsp`
DSP-Helferklasse für Biquad-IIR-Filter, RMS-Messung, logarithmische Spektrumbänder und Goertzel-Frequenzanalyse:
* **Konstante**: `public static final float DEFAULT_SAMPLE_RATE = 44100.0f`
* **Enum**: `FilterType { LOWPASS, HIGHPASS, BANDPASS }`
* **`BiquadFilter`**: `createLowpass(float sampleRate, float cutoffHz, float q)`, `createHighpass(...)`, `createBandpass(...)`; Instanzmethoden `processSample(float)`, `processBuffer(float[])`, `processBuffer(float[], int, int)`, `reset()`, `copy()`, `getType()`, `getSampleRate()`, `getCutoffHz()`, `getQ()`.
* **Statische Analyse-Methoden**: `computeRms(float[], int, int)`, `computeRms(float[])`, `computeSpectrumBands(float[], int, int, int)`, `computeSpectrumBands(float[], int, int, int, float)`, `computeGoertzelMagnitude(float[], int, int, float targetFreqHz, float sampleRate)`.

### `AudioDecoder`
Reiner LWJGL-/Java-Audiodekoder (`STBVorbis` + RIFF WAVE), WAV-Encoder und prozeduraler Synthesizer:
* **Record**: `DecodedAudio(float[] monoOrInterleavedFloat, short[] pcm16, int channels, int sampleRate, double durationSeconds)` (+ `frameCount()`, `toMonoFloat()`).
* **Dekodierung & Kodierung**: `decode(Path) throws IOException`, `decodeOgg(byte[]) throws IOException`, `decodeWav(byte[]) throws IOException`, `encodeWav(short[], int channels, int sampleRate)`, `floatToPcm16(float[])`, `pcm16ToFloat(short[])`.
* **Synthese**: `synthesizeTone(double freqHz, double durationSeconds, int sampleRate, float amplitude)`, `synthesizeChord(double[] freqsHz, double durationSeconds, int sampleRate, float amplitude)`, `synthesizeVoiceBleep(double baseFreqHz, double durationSeconds, int sampleRate)`.

### `AudioChannel`
Benannter logischer Mixer-Kanal mit Fade-Envelopes, Dual-Slot-Crossfade, Queue, optionalem DSP-Filter, OpenAL-Source-Verwaltung und Snapshot/Restore:
* **Record**: `ChannelSnapshot(String assetPath, boolean loop, float targetVolume, boolean playing, double playbackSeconds)`.
* **Konstruktoren**: `AudioChannel(String name)`, `AudioChannel(String name, boolean openAlEnabled)`.
* **Wiedergabe & Queue**: `play(String assetPath, AudioDecoder.DecodedAudio audio, boolean loop, double fadeInSeconds, float targetVolume)`, `queue(List<String>)`, `pollNextQueued()`, `clearQueue()`, `getQueuedPaths()`, `stop(double fadeOutSeconds)`, `update(double dt)`.
* **DSP & Telemetrie**: `setFilter(AudioDsp.BiquadFilter)`, `getFilter()`, `getPlaybackSeconds()`, `getCurrentRms()`, `getCurrentSpectrumBands(int bands)`.
* **Snapshots & Status**: `captureSnapshot()`, `restoreSnapshot(ChannelSnapshot)`, `restoreSnapshot(ChannelSnapshot, AudioDecoder.DecodedAudio)`, `getName()`, `isPlaying()`, `isLooping()`, `isCrossfading()`, `isFadingOut()`, `getAssetPath()`, `getTargetVolume()`, `getFadeGain()`, `getOutgoingFadeGain()`, `getChannelMixGain()`, `setChannelMixGain(float)`, `getCurrentEffectiveVolume()`, `isOpenAlEnabled()`, `setOpenAlEnabled(boolean)`, `dispose()`.

### `AudioEngine` (`implements AutoCloseable, ScriptRuntime.AudioBridge`)
Zentraler OpenAL-Audio-Mixer mit Headless-Virtual-Mixer-Fallback, `PreferencesState`-Synchronisation und `tl/<lang>/`-Pfadauflösung:
* **Konstanten**: `CHANNEL_MUSIC = "music"`, `CHANNEL_SOUND = "sound"`, `CHANNEL_VOICE = "voice"`, `CHANNEL_AMBIENT = "ambient"`, `DEFAULT_VOICE_BLEEP_DURATION = 0.055`, `DEFAULT_SYNTH_SAMPLE_RATE = 44100`.
* **Konstruktoren**: `AudioEngine()`, `AudioEngine(Path projectRoot, boolean headless)`, `AudioEngine(Path projectRoot, PreferencesState, LocalizationManager, EngineLogger, boolean headless)`.
* **Lebenszyklus & Kanäle**: `init()`, `getChannel(String name)`, `getChannels()`, `resolveAudioPath(String assetPath)`, `registerInMemoryAudio(String assetPath, AudioDecoder.DecodedAudio)`, `syncVolumesFromPreferences()`, `createScriptAudioBridge()`, `update(double dt)`, `dispose()`, `close()`.
* **`ScriptRuntime.AudioBridge` & Bleeps**: `play(String ch, String path, boolean loop, double fadeIn, double vol)`, `queue(String ch, List<String> paths)`, `stop(String ch, double fadeOut)`, `voice(String path)`, `playVoiceBleep(double freqHz)`, `playVoiceBleep(double freqHz, double durationSeconds)`.
* **Telemetrie & Snapshots**: `getMusicSpectrumBands(int bandCount)`, `getActiveVoiceRms()`, `snapshotAllChannels()`, `restoreAllChannels(Map<String, AudioChannel.ChannelSnapshot>)`.
* **Zustand & Konfiguration**: `isOpenAlActive()`, `isInitialized()`, `isDisposed()`, `isHeadless()`, `getProjectRoot()` / `setProjectRoot(Path)`, `getPreferencesState()` / `setPreferencesState(PreferencesState)`, `getLocalizationManager()` / `setLocalizationManager(LocalizationManager)`, `getLogger()`.

### `LipSyncAnalyzer`
Attack-/Release-Hüllkurven-Analysator für Voice-RMS-gesteuertes Lip-Syncing:
* **Konstanten**: `DEFAULT_ATTACK_SECONDS = 0.025`, `DEFAULT_RELEASE_SECONDS = 0.12`, `DEFAULT_NOISE_FLOOR_RMS = 0.01f`, `DEFAULT_MAX_RMS = 0.50f`, `DEFAULT_HALF_OPEN_THRESHOLD = 0.08f`, `DEFAULT_OPEN_THRESHOLD = 0.45f`.
* **Enum**: `MouthShape { CLOSED, HALF_OPEN, OPEN }`.
* **Konstruktoren**: `LipSyncAnalyzer()`, `LipSyncAnalyzer(double attackSeconds, double releaseSeconds, float halfOpenThreshold, float openThreshold)`, `LipSyncAnalyzer(double attackSeconds, double releaseSeconds, float noiseFloorRms, float maxRms, float halfOpenThreshold, float openThreshold)`.
* **Methoden**: `update(AudioEngine, double dt)`, `updateFromRms(float rms, double dt)`, `reset()`, `getMouthShape()`, `getOpenness()`, Getter/Setter für `attackSeconds`, `releaseSeconds`, `noiseFloorRms`, `maxRms`, `halfOpenThreshold`, `openThreshold`.

### `TextToSpeechService` (`implements AutoCloseable`)
Nicht-blockierender Self-Voicing-TTS-Dienst auf einem Java-21-Virtual-Thread mit Rich-Text-Bereinigung (`TextTagParser`):
* **Konstante**: `DEFAULT_MAX_HISTORY = 500`.
* **Konstruktoren**: `TextToSpeechService()`, `TextToSpeechService(EngineLogger logger, boolean osCommandExecutionEnabled)`.
* **Statische Helfer**: `stripRichTextTags(String rawText)`, `buildSpeechCommand(String osName, String utterance)`.
* **Methoden**: `setEnabled(boolean)` / `isEnabled()`, `setOsCommandExecutionEnabled(boolean)` / `isOsCommandExecutionEnabled()`, `speak(String text)`, `stop()`, `getSpokenHistory()`, `clearSpokenHistory()`, `setOnSpeakListener(Consumer<String>)`, `getPendingCount()`, `awaitIdle(long timeoutMillis)`, `isDisposed()`, `dispose()`, `close()`.

---

## 10. Paket `de.swfdtf.ui` (M6)

### Kern, Dark-Slate-Theme & Screen-Stack
* **`ThemePalette`**: Kanonische Dark-Slate-Hexkonstanten (`CANVAS_BG = "#0d0e15"`, `PANEL_SURFACE = "#161822"`, `PANEL_GLASS = "#161822d9"`, `ELEVATED = "#1f2232"`, `BORDER = "#2e334a"`, `ACCENT_IDLE = "#388bfd"`, `ACCENT_HOVER = "#58a6ff"`, `ACTIVE_SELECTED = "#f0883e"`, `DISABLED = "#8b949e7f"`, `TEXT_PRIMARY = "#f0f6fc"`, `TEXT_MUTED = "#8b949e"`, `SUCCESS = "#3fb950"`, `WARNING = "#d29922"`, `ERROR = "#f85149"`) sowie `straightRgba(String)` und `premultipliedRgba(String, float)`.
* **`UiNode`** (`abstract class`): `measure(float, float)`, `layout(Rect2f)`, `update(double)`, `render(RenderPipeline2D, FontManager, TextureManager, double)`, Input-Dispatch (`dispatchMouseMove`, `dispatchMouseDown`, `dispatchMouseUp`, `dispatchMouseScroll`, `dispatchKeyPressed`, `dispatchCharTyped`, `setHoveredDeep`), Baum-Management (`addChild`, `removeChild`, `clearChildren`, `getChildren`, `getParent`), Padding-/Bounds-/Stil-Properties (`id`, `bounds`, `preferredSize`, `visible`, `enabled`, `focusable`, `hovered`, `focused`, `pressed`, `alpha`, `backgroundColorHex`, `borderColorHex`, `borderThickness`, `cornerRadius`).
* **`Screen`**: Sub-Typen `KeyBinding(int glfwKey, int requiredModsMask, Runnable action)` und `ScreenTimer(double intervalSeconds, boolean repeat, Runnable action)`; Konstruktoren `Screen(String)`, `Screen(String, int zOrder, boolean modal)`; Methoden `onShow()`, `onHide()`, `layout(float, float)`, `update(double)`, `render(...)`, `addKeyBinding(...)`, `addTimer(...)`, `triggerKeyBindings(int, int)`, `getKeyBindings()`, `getName()`, `getZOrder()`/`setZOrder`, `isModal()`/`setModal`, `isVisible()`/`setVisible`, `getRoot()`/`setRoot`.
* **`ScreenManager`**: `setLogicalSize(float, float)`, `registerScreen(Screen)`, `showScreen(Screen|String)`, `hideScreen(String)`, `toggleScreen(String)`, `getScreen(String)`, `getVisibleScreensSorted()`, `getTopModalScreen()`, `isModalActive()`, `getInputEligibleScreensTopDown()`, `update(double, InputManager)`, `routeInput(InputManager)`, `dispatchMouseDown`/`Up`/`Scroll`, `dispatchKeyPressed`, `dispatchCharTyped`, `cycleFocus(Screen, boolean)`, `render(...)`, `getFocusedNode()`, `getLogicalWidth()`, `getLogicalHeight()`.

### Layout-Container (`extends UiNode`)
* **`VBox`**: `setSpacing(float)`/`getSpacing()`, `setFillWidth(boolean)`/`isFillWidth()`, `setAlignCenterX(boolean)`/`isAlignCenterX()`.
* **`HBox`**: `setSpacing(float)`/`getSpacing()`, `setAlignCenterY(boolean)`/`isAlignCenterY()`, `setFillHeight(boolean)`/`isFillHeight()`.
* **`Grid`**: `Grid(int columns, int rows)`, `setSpacing(float h, float v)`, `getColumns()`, `getRows()`, `getHorizontalSpacing()`, `getVerticalSpacing()`.
* **`Fixed`**: `AlignmentSpec(xPos, yPos, xAlign, yAlign, hasXPos, hasYPos, hasXAlign, hasYAlign)`, `addChildAt(UiNode, float x, float y, float width, float height)`, `setChildAlignment(UiNode, AlignmentSpec)`.
* **`Frame`**: `DEFAULT_CORNER_RADIUS = 8.0f`, `DEFAULT_BORDER_THICKNESS = 1.0f`; `setNineSlice(NineSliceDrawable)` / `getNineSlice()`.
* **`WindowBox`**: `DEFAULT_HEADER_HEIGHT = 40.0f`, `DEFAULT_TITLE_FONT_SIZE = 20.0f`; `WindowBox()`, `WindowBox(String title)`, `setTitle`/`getTitle`, `setHeaderHeight`/`getHeaderHeight`, `setTitleFontSize`/`getTitleFontSize`, `getWindowContentBounds()`.
* **`Viewport`**: `DEFAULT_SCROLL_STEP = 48.0f`, `SCROLLBAR_WIDTH = 8.0f`; `setScrollY`/`getScrollY`, `getMaxScrollY()`, `getContentHeight()`, `setScrollbarVisible`/`isScrollbarVisible`, `setScrollStep`/`getScrollStep`, `isDraggingScrollbar()`.
* **`VpGrid`**: `DEFAULT_SCROLL_STEP = 64.0f`; `VpGrid(int columns, float cellWidth, float cellHeight)`, `setSpacing(float, float)`, `setScrollY`/`getScrollY`, `getMaxScrollY()`, `getTotalContentHeight()`, `getTotalRows()`, `getFirstVisibleRow()`, `getLastVisibleRow()`, `setScrollbarVisible`/`isScrollbarVisible`, `setScrollStep`/`getScrollStep`.

### Widgets & `ScreenAstBuilder`
* **`TextWidget`**: `DEFAULT_FONT_SIZE = 24.0f`, `DEFAULT_LINE_SPACING = 1.15f`; `TextWidget(String)`, `setText`/`getText`, `setFontSize`/`getFontSize`, `setLineSpacing`/`getLineSpacing`, `setColorHex`/`getColorHex`, `setDropShadow`/`isDropShadow`, `setTypewriterCps`/`getTypewriterCps`, `setFontManager`, `getTypewriter()`, `getLayout()`.
* **`TextButton`**: `DEFAULT_FONT_SIZE = 22.0f`, `DEFAULT_CORNER_RADIUS = 6.0f`; `TextButton(String label, Runnable onClick)`, `setText`/`getText`, `setOnClick`/`getOnClick`, `setSelected`/`isSelected`, `setFontSize`/`getFontSize`, `getCurrentSurfaceColorHex()`, `getCurrentBorderColorHex()`, `getCurrentTextColorHex()`, `click()`.
* **`ImageButton`**: `ImageButton(String idlePath, String hoverPath, Runnable onClick)`, `setDisabledPath`/`getDisabledPath`, `setIdlePath`/`getIdlePath`, `setHoverPath`/`getHoverPath`, `setSelectedPath`/`getSelectedPath`, `setSelected`/`isSelected`, `setOnClick`/`getOnClick`, `getCurrentImagePath()`, `click()`.
* **`Hotspot`**: `Hotspot(Rect2f area, Runnable onClick)`, `getArea()`/`setArea`, `setOnClick`/`getOnClick`, `setHoveredColorHex`/`getHoveredColorHex`, `isInside(float, float)`.
* **`Hotbar`**: `Hotbar(Rect2f area, Supplier<Float> valueSupplier, float maxValue)`, `getArea()`/`setArea`, `getMaxValue()`/`setMaxValue`, `getCurrentValue()`, `getNormalizedRatio()`, `setFillColorHex`/`getFillColorHex`, `setTrackColorHex`/`getTrackColorHex()`.
* **`Imagemap`**: `Imagemap(String groundPath, String hoverPath)`, `getGroundPath()`/`setGroundPath`, `getHoverPath()`/`setHoverPath`, `addHotspot(Rect2f, Runnable)`, `addHotbar(Rect2f, Supplier<Float>, float)`, `getHotspots()`, `getHotbars()`.
* **`Bar` & `VBar`**: `Bar(float value, float max)` (`DEFAULT_WIDTH = 300.0f`, `DEFAULT_HEIGHT = 24.0f`), `VBar(float value, float max)` (`DEFAULT_WIDTH = 24.0f`, `DEFAULT_HEIGHT = 200.0f`); `setValue`/`getValue`, `setRange`/`getMax`, `getNormalizedValue()`, `setFillColorHex`/`getFillColorHex`, `setTrackColorHex`/`getTrackColorHex`.
* **`Slider`**: `DEFAULT_WIDTH = 300.0f`, `DEFAULT_HEIGHT = 28.0f`; `Slider(float value, float min, float max, Consumer<Float> onChanged)`, `setValue`/`getValue`, `getMin()`, `getMax()`, `setStep`/`getStep`, `isDragging()`, `setOnChanged`, `getNormalizedValue()`.
* **`TextInput`**: `DEFAULT_WIDTH = 340.0f`, `DEFAULT_HEIGHT = 44.0f`, `DEFAULT_FONT_SIZE = 20.0f`; `TextInput(String initialText, Consumer<String> onSubmit)`, `setText`/`getText`, `setPlaceholder`/`getPlaceholder`, `setAllowChars`/`getAllowChars`, `setMaxLength`/`getMaxLength`, `setOnSubmit`, `setOnChanged`, `setFontSize`/`getFontSize`.
* **`DragItem` & `DragGroup`**: `DragItem(String id, Object payload)` (`DEFAULT_SIZE = 96.0f`, `setDropTarget`/`isDropTarget`, `setDraggable`/`isDraggable`, `setSnappable`/`isSnappable`, `setOnDrop`/`getOnDrop`, `getSnappedTo()`, `isDragging()`); `DragGroup` (`addDragItem`, `getItems()`, `getActiveDraggedItem()`, `findDropTargetAt`, `findIntersectingDropTarget`).
* **`CustomDisplayableWidget`**: `CustomDisplayableWidget(CustomDisplayable)`, `getDisplayable()`, `setDisplayable(CustomDisplayable)`.
* **`ScreenAstBuilder`**: `public static Screen build(VJavaAst.ScreenDef def, ScriptRuntime runtimeOrNull, Consumer<String> actionDispatcher)`.

### Dialog-Modi, Overlays & eingebaute System-/Meta-Screens
* **`AdvDialogueScreen`** (`SCREEN_NAME = "adv_dialogue"`, `PANEL_X = 140.0f`, `PANEL_Y = 790.0f`, `PANEL_W = 1640.0f`, `PANEL_H = 250.0f`, `BLEEP_THROTTLE_SECONDS = 0.055`, `SKIP_STEP_SECONDS = 0.05`): `AdvDialogueScreen(ScriptSession, ScreenManager[, AudioEngine, TextToSpeechService])`, `handlePrimaryAdvance()`, `toggleSkip()`, `setSkipActive(boolean)`, `isSkipActive()`, `toggleAuto()`, `setAutoActive(boolean)`, `isAutoActive()`, `getBleepCount()`, `getVisibleCharCount()`, `isTypewriterFinished()`, `getDialogueBoxBounds()`, `getSpeakerNameWidget()`, `getDialogueTextWidget()`, `getQuickMenuRow()`, `setAudioEngine`, `setTtsService`, `syncFromRuntime(boolean)`.
* **`NvlDialogueScreen`** (`SCREEN_NAME = "nvl_dialogue"`): `NvlDialogueScreen(ScriptSession)`, `handlePrimaryAdvance()`, `syncFromRuntime(boolean)`, `getEntries()`, `getEntryCount()`, `isLatestTypewriterFinished()`, `getViewport()`, `getParagraphContainer()`, `getParagraphWidgets()`.
* **`BubbleDialogueScreen`** (`SCREEN_NAME = "bubble_dialogue"`, `PRESETS_FILE_NAME = "bubble_layouts.json"`, `DEFAULT_BOX_W = 540.0f`, `DEFAULT_BOX_H = 160.0f`): Records `BubbleLayoutConfig` (`containsBox`, `isNearTailTip`) und `BubbleEntry`; `BubbleDialogueScreen(ScriptSession)`, `toggleEditorMode()`, `setEditorMode(boolean)`, `isEditorActive()`, `setRetainPreviousBubbles(boolean)`, `isRetainPreviousBubbles()`, `clearRetainedBubbles()`, `setLayoutPreset`/`getLayoutPreset`/`getLayoutPresets()`, `getActiveBubble()`, `getRetainedBubbles()`, `getVisibleBubbleCount()`, `handlePrimaryAdvance()`, `syncFromRuntime(boolean)`, `resolveLayoutForPrompt`, `resolveSpeakerAnchor`, `savePresetsToDisk()`.
* **`QuickMenuScreen`** (`SCREEN_NAME = "quick_menu"`, `Z_ORDER = 210`): `QuickMenuScreen(ScriptSession, ScreenManager)`, Button-Getter `getBarBox()`, `getBackButton()`, `getHistoryButton()`, `getSkipButton()`, `getAutoButton()`, `getSaveButton()`, `getQuickSaveButton()`, `getQuickLoadButton()`, `getPrefsButton()`.
* **`ChoiceMenuScreen`** (`SCREEN_NAME = "choice_menu"`): `ChoiceMenuScreen(ScriptSession)`, `syncFromRuntime(boolean)`, `getOptionButtons()`, `getTimeoutBar()`, `getCaptionLabel()`.
* **`InputOverlayScreen`** (`SCREEN_NAME = "input_overlay"`): `InputOverlayScreen(ScriptSession)`, `syncFromRuntime(boolean)`, `submitCurrentText(String)`, `getPromptLabel()`, `getTextInput()`, `getConfirmButton()`.
* **`MainMenuScreen`** (`SCREEN_NAME = "main_menu"`): `MainMenuScreen(ScriptSession, ScreenManager)`, `refreshContinueState()`, `getNewestSaveSlotId()`, `getTitleBanner()` sowie Button-Getter (`getStartButton()`, `getContinueButton()`, `getLoadButton()`, `getGalleryButton()`, `getMusicRoomButton()`, `getEndingsButton()`, `getAchievementsButton()`, `getPreferencesButton()`, `getControlsButton()`, `getAboutButton()`, `getQuitButton()`).
* **`SaveLoadScreen`** (`SCREEN_NAME = "save_load"`, `SLOTS_PER_PAGE = 6`, `TOTAL_PAGES = 5`): Enums `Mode { SAVE, LOAD }`, `FilterTab { MANUAL, QUICK, AUTO }`, `SlotCardView`; `SaveLoadScreen(ScriptSession, ScreenManager)`, `setMode`/`getMode`, `setFilterTab`/`getFilterTab`, `setPage`/`getCurrentPage`, `refreshSlots()`, `handleSlotAction(String)`, `handleSlotDelete(String)`, `getVisibleCards()`, `getCustomNameInput()`, `getPageButtons()`.
* **`PreferencesScreen`** (`SCREEN_NAME = "preferences"`): `PreferencesScreen(ScriptSession, ScreenManager)`, `refreshFromPreferences()`, `cycleDialogueMode()`, `cycleLanguage()`, `selectLanguage(String)`, `toggleRenderMode()`, `savePreferences()`, `setFontManager(FontManager)` sowie Slider-/Toggle-Getter.
* **`ControlsScreen`** (`SCREEN_NAME = "controls"`): Enum `Category { ALL, KEYBOARD, MOUSE, GAMEPAD }`, Record `BindingEntry(Category, String input, String action)`; `ControlsScreen(ScreenManager)`, `setCategory`/`getActiveCategory`, `getVisibleBindings()`.
* **`AboutScreen`** (`SCREEN_NAME = "about"`): `AboutScreen(ScriptSession, ScreenManager)`, `refreshInfo()` sowie Info-Widget-Getter.
* **`HistoryLogScreen`** (`SCREEN_NAME = "history_log"`): `HistoryRowView`; `HistoryLogScreen(ScriptSession, ScreenManager[, AudioEngine])`, `refreshEntries()`, `replayVoice(String)`, `jumpBackTo(HistoryLog.HistoryEntry)`, `setAudioEngine`, `getRowViews()`, `getViewport()`.
* **`GalleryScreen`** (`SCREEN_NAME = "gallery"`): `GalleryCardView`; `GalleryScreen(ScriptSession, ScreenManager)`, `refreshGallery()`, `selectEntry(GalleryManager.GalleryEntry)`, `stepVariation(int)`, `toggleZoom()`, `setZoomed(boolean)`/`isZoomed()`, `getCardViews()`, `getSelectedEntry()`, `getVariationIndex()`, `getActiveVariationPath()`.
* **`MusicRoomScreen`** (`SCREEN_NAME = "music_room"`, `SPECTRUM_BAND_COUNT = 16`): `MusicRoomScreen(ScriptSession, ScreenManager[, AudioEngine])`, `registerTrackTitle(String, String)`, `refreshTracks()`, `playTrack(String)`, `stopPlayback()`, `setAudioEngine`, `getSpectrumBands()`, `getCurrentTrackId()`, `getNowPlayingLabel()`, `getStopButton()`, `getTrackButtons()`, `getSpectrumBars()`.
* **`EndingsTreeScreen`** (`SCREEN_NAME = "endings_tree"`): Record `TreeNodeView(EndingsManager.EndingDef, int depth, boolean unlocked, TextWidget titleLabel, TextWidget statusLabel)`; `EndingsTreeScreen(ScriptSession, ScreenManager)`, `refreshTree()`, `getNodeViews()`, `getCompletionBar()`, `getCompletionLabel()`.
* **`AchievementsScreen`** (`SCREEN_NAME = "achievements"`): Record `AchievementRowView(AchievementManager.AchievementDef, boolean unlocked, TextWidget titleLabel, TextWidget descriptionLabel, TextWidget statusLabel)`; `AchievementsScreen(ScriptSession, ScreenManager)`, `refreshAchievements()`, `getRows()`, `getSummaryLabel()`, `getProgressBar()`.
* **`NotificationToastOverlay`** (`SCREEN_NAME = "notification_toast"`, `DEFAULT_TOAST_DURATION = 4.0`): Record `ToastPayload(String title, String subtitle)`; `NotificationToastOverlay(ScriptSession, ScreenManager)`, `pushToast(String, String)`, `setSkipPillActive(boolean)`, `setAutoPillActive(boolean)`, `isToastVisible()`, `getActiveToast()`, `getPendingToastCount()`, `getToastCard()`, `getStatusPill()`, `getStatusPillText()`.
* **`GameRuntimeCoordinator`** (`implements GameHost`): `GameRuntimeCoordinator()`, `GameRuntimeCoordinator(boolean autoStartMainMenu)`, `init(EngineApplication, boolean)`, `update(double, InputManager)`, `renderUi(double)`, `onReload()`, `dispose()`, sowie Getter `getSession()`, `getScreenManager()`, `getAudioEngine()`, `getFontManager()`, `getTextureManager()`, `getAnimationEngine()`, `getTransitionManager()`, `getLipSyncAnalyzer()`, `getTtsService()`, `getMainMenuScreen()`, `getAdvScreen()`, `getNvlScreen()`, `getBubbleScreen()`, `getChoiceMenuScreen()`, `getPreferencesScreen()`, `getToastOverlay()`.
* *(Paketintern)*: `UiRenderSupport` (GL-Scissor-Stack, Scrollbar-Rendering, Headless-`FontManager`-Fallback) und `UiMediaSupport` (Dark-Slate-Karten-/Button-Helfer, Zeitformatierung, `PreviewImageNode` mit STB-PNG-Dekodierung).

---

## 11. Paket `de.swfdtf.tools` (M7)

### `ProjectTemplateGenerator`
Erzeugt vollständige, Lint-saubere Starterprojekte inklusive prozeduraler PNG-/WAV-Assets (ohne `java.awt`).
* `public static Path generate(Path targetDir) throws IOException`
* `public static Path generate(Path targetDir, String title, String renderMode) throws IOException`

### `VJavaLinter`
Statischer Analyzer und Java-21-Compiler-Validator für KAGAYAKI-`.vjava`-Projekte:
* **Enum & Records**:
  * `public enum Severity { ERROR, WARNING }`
  * `public record LintIssue(Severity severity, String code, String message, String sourceFile, int lineNumber)`
  * `public record LintReport(List<LintIssue> issues, int errorCount, int warningCount)` mit `isClean()`, `hasErrors()`
* **Methoden**:
  * `public static LintReport lint(Path projectDir)`
  * `public static LintReport lint(VJavaAst.ProjectAst project)`

### `VpakArchiver`
Binärer `.vpak`-Packer und -Reader (`MAGIC = "VPAK"`, `FORMAT_VERSION = 1`) mit Deflate-Kompression und CRC32-Prüfung:
* **Record**: `public record VpakEntryInfo(String path, long uncompressedSize, long compressedSize, long crc32)`
* **Statische Methoden**: `pack(Path projectDir, Path outputVpak) throws IOException`, `open(Path vpakFile) throws IOException`
* **`VpakArchive` (`implements AutoCloseable`)**: `listEntries()`, `contains(String path)`, `readEntry(String path) throws IOException`, `extractAll(Path targetDir) throws IOException`, `close()`.

### `StandaloneBuilder`
Exportiert lint-geprüfte Projekte in eigenständige Distributionen (`game.vpak`, `run_game.sh`/`run_game.bat`, optionales `kagayaki-standalone.jar`, `standalone-packaging.json`):
* **Record**: `public record BuildResult(Path outputDir, Path vpakFile, Path standaloneJar, Path launcherScript, Path packagingDescriptor, int packagedEntries)`
* **Methode**: `public static BuildResult build(Path projectDir, Path outputDir, Path optionalStandaloneJar) throws IOException`

### `LiveDirectorOverlay` (`extends Screen`)
In-Engine-Live-Regie-Overlay (`SCREEN_NAME = "live_director"`, `Z = 1000`, `D`-Toggle) mit atomarem `.vjava`-Write-Back:
* **Konstruktoren**: `LiveDirectorOverlay(ScriptSession)`, `LiveDirectorOverlay(ScriptSession, ScreenManager)`
* **Steuerung & Write-Back**: `toggle()`, `setVisible(boolean)`, `onShow()`, `triggerKeyBindings(int, int)`, `refreshFromStage()`, `selectSprite(String)`, `commitCurrentSpriteEdit(String)`, `public static boolean updateShowLineInScript(Path vjavaFile, int oneBasedLine, String tag, List<String> attributes, String positionExpr)`
* **Zugriffsmethoden**: `getSession()`, `setSession(ScriptSession)`, `getSelectedSpriteTag()`, `getPanelBox()`, `getXposSlider()`, `getYposSlider()`, `getZoomSlider()`, `getAlphaSlider()`, `getAttributesInput()`, `getApplyButton()`, `getRefreshButton()`, `getStatusLabel()`, `getSpriteButtons()`.

### `DeveloperInspectors` (`extends Screen`)
Einheitliche Entwickler-Inspektor-Suite (`SCREEN_NAME = "developer_inspectors"`, `Z = 1000`):
* **Records**:
  * `StyleInspectionResult(String screenName, String nodeClass, String nodeId, Rect2f bounds, String backgroundColorHex, String borderColorHex, float borderThickness, float cornerRadius, boolean visible, boolean enabled, boolean hovered, boolean focused)` mit `formatReport()`
  * `PickedCoordinate(float x, float y, float normalizedX, float normalizedY, String xposYposSnippet, String alignSnippet)`
  * `ConsoleExecutionResult(boolean success, String code, String output, String errorMessage)`
* **Konstruktoren**: `DeveloperInspectors(ScriptSession)`, `DeveloperInspectors(ScriptSession, ScreenManager)`
* **Live Reload (`Shift+R`)**: `triggerLiveReload()`, `triggerLiveReload(ScriptSession)`
* **Style Inspector (`Shift+I`)**: `toggleStyleInspector()`, `setStyleInspectorActive(boolean)`, `isStyleInspectorActive()`, `inspectAt(float, float)`, `getLastInspectedStyle()`
* **Coordinate Picker (`Shift+C`)**: `toggleCoordinatePicker()`, `setCoordinatePickerActive(boolean)`, `isCoordinatePickerActive()`, `updateCursorPosition(float, float)`, `pickCoordinate(float, float)`, `pickCurrentCoordinate()`, `getPickedHistory()`, `clearPickedHistory()`, `formatPosSnippet(float, float)`, `formatAlignSnippet(float, float)`
* **Live Java Console (`Shift+O`) & Warp**: `toggleConsole()`, `setConsoleActive(boolean)`, `isConsoleActive()`, `executeConsoleSnippet(String)`, `executeConsoleSnippet(ScriptSession, String)`, `getConsoleLog()`, `clearConsoleLog()`, `warpToLabel(String)`, `warpToLabel(ScriptSession, String)`, `getAvailableLabels()`
* **Konfiguration & UI-Getter**: `getSession()`, `setSession(ScriptSession)`, `setLinkedDirector(LiveDirectorOverlay)`, `setOnLiveReload(Consumer<ScriptSession>)`, `getCursorX()`, `getCursorY()`, `getNormalizedX()`, `getNormalizedY()`, `getStyleInspectorBox()`, `getCoordinatePickerBox()`, `getConsoleBox()`, `getConsoleInput()`, `getConsoleExecuteButton()`, `getWarpLabelInput()`, `getWarpButton()`, `getReloadButton()`.

### `StudioWindowController` (`implements StudioBridge`)
Dual-Window-Studio-Controller für Fenster A:
* **Konstruktor & `StudioBridge`**: `StudioWindowController()`, `init(EngineAPI, long, long)`, `update(double)`, `render(int, int)`, `onReload()`, `dispose()`
* **Studio-Aktionen & Getter**: `generateStarterProject(Path, String, String)`, `runLint()`, `switchRenderMode(RenderMode)`, `exportStandalone(Path, Path)`, `refreshInspectors()`, `getLastScannedAst()`, `getLastLintReport()`, `getLastExportResult()`, `getStudioWindowHandle()`, `getGameWindowHandle()`, `isGlReady()`, `isDisposed()`, `getStudioScreenManager()`.
