# KAGAYAKI — Öffentliche API-Referenz (M1 + M2 + M3)

Diese Referenz dokumentiert die verifizierten öffentlichen Java-Schnittstellen der abgeschlossenen M1-, M2- und M3-Pakete (`de.swfdtf`, `de.swfdtf.core`, `de.swfdtf.graphics`, `de.swfdtf.math`, `de.swfdtf.git`, `de.swfdtf.parser`, `de.swfdtf.state`) sowie die Gradle-Tasks aus `build.gradle`.

---

## 1. Gradle-Tasks (`build.gradle`)

| Task | Gruppe | Beschreibung |
| :--- | :--- | :--- |
| `./gradlew build` | `build` | Kompiliert Java 21 Quellen, führt JUnit-6-Tests aus und paketiert das Standard-JAR. |
| `./gradlew test` | `verification` | Führt alle JUnit-Jupiter-Tests (`6.0.0`, 101 Tests laut `build/test-results/test/`) mit ausführlichem Event-Logging aus. |
| `./gradlew run` | `application` | Startet `de.swfdtf.Main` (unter macOS automatisch mit `-XstartOnFirstThread`). |
| `./gradlew runStudio` | `kagayaki` | Startet `de.swfdtf.Main` mit `--studio` (optional `-PprojectDir=<pfad>` ergänzt `--project <pfad>`). |
| `./gradlew runStandalone` | `kagayaki` | Startet `de.swfdtf.StandaloneMain` (optional `-PprojectDir=<pfad>` ergänzt `--standalone <pfad>`). |
| `./gradlew packageStandalone` | `kagayaki` | Erstellt ein ausführbares Fat-JAR `build/dist/kagayaki-standalone-<version>.jar` mit `Main-Class: de.swfdtf.StandaloneMain` und schließt `de/swfdtf/tools/StudioWindowController*` sowie `**/AGENT_CONTEXT.md` aus. |

---

## 2. Paket `de.swfdtf` (Einstiegspunkte)

### `Main`
Primärer Anwendungs-Einstiegspunkt. Startet `EngineApplication` standardmäßig im `EngineMode.STUDIO_DUAL_WINDOW`-Modus, sofern `--standalone` nicht explizit übergeben wurde.
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
Top-Level-Laufzeitkoordinator (`implements AutoCloseable`) für Initialisierung, 60-Hz-Fixed-Timestep-Simulation, 2D/3D-Pipeline-Umschaltung, Input-Routing und Dual-Window-/Standalone-Ausführung.

* **Funktionales Sub-Interface**:
  * `@FunctionalInterface public interface Updatable { void update(double fixedDeltaSeconds); }`
* **Konstruktoren**:
  * `public EngineApplication(EngineConfig config)`
  * `public EngineApplication(EngineConfig config, StudioBridge studioBridge)`
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

### Weitere Typen in `de.swfdtf.core`
* `EngineLogger`: Thread-sicherer Ringpuffer-Logger (`DEFAULT_CAPACITY = 2000`, `Level`, `LogEntry`, `addListener`, `getRecentEntries`, `setMinimumLevel`).
* `StudioBridge`: Entkopplungsinterface (`init`, `update`, `render`, `onReload`, `dispose`).

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

### `SceneController` (M3, Binding `scene`)
High-Level-Bühnensteuerung für `scene`/`show`/`hide` auf `SceneGraph` (`LAYER_BACKGROUND`, `LAYER_MASTER`) inklusive Positionserhalt beim Tag-Wechsel und deterministischer Stage-Snapshots.
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
  * `public void clearMasterSprites()`
* **Snapshots & Abfragen**:
  * `public StageSnapshot snapshotState()` / `public void restoreState(StageSnapshot snapshot)`
  * `public SpriteNode getActiveSprite(String tag)` / `public Map<String, SpriteNode> getActiveSprites()`
  * `public String getBackgroundTag()` / `public SpriteNode getBackgroundNode()` / `public String getLastTransitionExpr()` / `public SceneGraph getSceneGraph()`
  * `public void setTransitionListener(BiConsumer<String, String> listener)` / `public void setProjectRoot(Path projectRoot)`

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

## 7. Paket `de.swfdtf.parser` (M3)

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
Nicht-blockierende State-Machine-Skript-Laufzeit mit Dialogue-/Menu-/Input-/Pause-Zuständen, Audio-Bridge und M4-Snapshot-Hooks:
* **Enum**: `Status { READY, WAITING_DIALOGUE, WAITING_MENU, WAITING_INPUT, PAUSING, ENDED }`
* **Records & Interfaces**: `DialoguePrompt`, `ChoiceOption`, `MenuPrompt`, `InputPrompt`, `AudioBridge`, `AudioEvent`, `StackFrame`, `ExecutionSnapshot`.
* **Konstruktoren**:
  * `public ScriptRuntime(VJavaAst.ProjectAst project)`
  * `public ScriptRuntime(VJavaAst.ProjectAst project, EngineAPI engineApi)`
  * `public ScriptRuntime(VJavaAst.ProjectAst project, EngineAPI engineApi, StateStore stateStore, PersistentAccess persistentAccess, SceneController sceneController)`
* **Steuerung & Snapshots**:
  * `compile()`, `start()`, `start(String entryLabel)`, `advance()`, `choose(int optionIndex)`, `submitInput(String text)`, `update(double dt)`, `jumpToLabel(String label)`, `snapshotExecution()`, `restoreExecution(ExecutionSnapshot)`
* **Getter & Hooks**:
  * `getStatus()`, `getActiveDialogue()`, `getActiveMenu()`, `getActiveInput()`, `getStateStore()`, `getPersistentAccess()`, `getSceneController()`, `getEngineApi()`, `getProjectAst()`, `getAudioEvents()`, `setAudioBridge(AudioBridge)`, `setCheckpointListener(Runnable)`, `getCompiledBlocks()`, `getCurrentLabel()`, `getStatementIndex()`, `isNvlMode()`, `getNvlLines()`, `getActiveVoicePath()`, `getPauseRemainingSeconds()`, `getWindowAction()`.

---

## 8. Paket `de.swfdtf.state` (M3-Verträge & In-Memory-Basis)

### `StateStore` & `SimpleStateStore`
Vertrag (Binding `state`) und thread-sichere M3-In-Memory-Implementierung für Sitzungsvariablen und Rollback-Barrieren:
* **Methoden (`StateStore`)**:
  * `set(String key, Object value)`, `get(String key)`, `getOrDefault(String key, Object defaultValue)`, `getString(String key, String defaultValue)`, `getInt(String key, int defaultValue)`, `getDouble(String key, double defaultValue)`, `getBoolean(String key, boolean defaultValue)`
  * `has(String key)`, `remove(String key)`, `keys()`, `snapshotMap()`, `restoreMap(Map<String, Object> snapshot)`, `blockRollback()`, `isRollbackBlocked()`, `clear()`
* **Zusatz in `SimpleStateStore`**: `resetRollbackBlock()`.

### `PersistentAccess` & `SimplePersistentAccess`
Vertrag (Binding `persistent`) und thread-sichere M3-In-Memory-Implementierung für spielstandübergreifende Persistenz:
* **Variablen**: `set(String key, Object value)`, `get(String key)`, `getOrDefault(String key, Object defaultValue)`, `has(String key)`
* **Read-Tracking & Freischaltungen**: `markLineRead(String)` / `isLineRead(String)`, `unlockCg(String)` / `isCgUnlocked(String)` / `getUnlockedCgs()`, `unlockTrack(String)` / `isTrackUnlocked(String)` / `getUnlockedTracks()`, `unlockEnding(String)` / `isEndingUnlocked(String)` / `getUnlockedEndings()`, `unlockAchievement(String)` / `isAchievementUnlocked(String)` / `getUnlockedAchievements()`.
