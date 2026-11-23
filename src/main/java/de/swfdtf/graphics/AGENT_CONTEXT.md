# Package `de.swfdtf.graphics` — AGENT_CONTEXT

## Zweck des Pakets
LWJGL-3 / OpenGL-3.3-Core-Grafik- und Textsubsystem von KAGAYAKI (Meilensteine M1–M3, vollständig ohne `java.awt`): Dual-Window- und Standalone-GLFW-Verwaltung mit gemeinsamen OpenGL-Objekt-Namespaces (`WindowManager`, `GlfwWindow`), Z-sortierter Szenengraph (`SceneGraph`, `SceneLayer`, `SceneNode`, `SpriteNode`, `CustomDisplayable`) mit 8 Standard-Ebenen (`-100` bis `1000`), High-Level-Bühnensteuerung (`SceneController` inklusive `StageEntry`/`StageSnapshot` für Skript-Laufzeit und Rollback), Offscreen-Framebuffer (`Framebuffer`), GLSL-Shader-Compiler (`ShaderProgram`), das einheitliche Pipeline-Interface `IRenderPipeline` mit `RenderPipeline2D` (Premultiplied Alpha, `ColorMatrix4f`, SDF-Rounded-Rectangles, Pivot-Rotation, Letterbox-Blit) und `RenderPipeline3DStub`, reine STB-Bild-/Texturverwaltung (`Texture`, `TextureManager`, `NineSliceDrawable`) sowie die Rich-Text-, Typografie-, Furigana- und Typewriter-Engine (`TextTagParser`, `FontAtlas`, `FontManager`, `StyledTextLayout`).

## Abhängigkeiten zu anderen `de.swfdtf.*`-Paketen
- `de.swfdtf.math` (`ColorMatrix4f`, `Rect2f`, `ScreenShake`, `Transform2D`, `Vec2f`)
- `de.swfdtf.core` (`EngineLogger` in `WindowManager`, `RenderPipeline3DStub`, `TextureManager`, `SceneController`)
- Externe Bibliotheken: `org.lwjgl.glfw.*`, `org.lwjgl.opengl.*`, `org.lwjgl.stb.*` (`STBImage`, `STBImageWrite`, `STBTruetype`, `STBTTFontinfo`), `org.lwjgl.system.*`, `org.joml.Matrix4f`.

## Enthaltene Typen

| Typ | Art | Beschreibung |
| :--- | :--- | :--- |
| `RenderMode` | `enum` | Definiert den aktiven Render-Backend-Modus (`MODE_2D`, `MODE_3D`) inklusive String-Parser `fromString`. |
| `IRenderPipeline` | `interface` | Einheitlicher Render-Pipeline-Vertrag (`init`, `beginFrame`, `renderSceneGraph`, `endFrame`, `getMode`, `dispose`) zur Abstraktion von 2D- und 3D-Backends. |
| `RenderPipeline2D` | `final class` | Produktive OpenGL-3.3-Core-2D-Pipeline mit `1920×1080`-Offscreen-FBO, Premultiplied-Alpha-Blending, `ColorMatrix4f`, SDF-Rounded-Rectangles, Pivot-Rotation, Custom-Render-Kontext und Letterbox-/Pillarbox-Blit. |
| `RenderPipeline3DStub` | `final class` | Architektonischer 3D-Bühnen-Stub mit Perspektiv-Projektion (`FOV = 75°`, `Z_NEAR = 100`, `Z_FAR = 100000`), LookAt-Kamera und kontrolliertem Warning-Logging. |
| `SceneGraph` | `final class` | Wurzel-Szenengraph mit 8 vorregistrierten Z-Order-Ebenen (`-100` bis `1000`), aktivem `ScreenShake` und normalisierten Mauskoordinaten für 2.5D-Parallax. |
| `SceneLayer` | `final class` | Benannte, Z-sortierte Ebene im `SceneGraph` mit Sichtbarkeit, Parallax-Faktor, Tag-/ID-Suche und stabiler Z-Sortierung der Knoten. |
| `SceneNode` | `class` | Hierarchischer Szenengraph-Knoten für texturierte Sprites, SDF-Rechtecke (mit Eckenradius und Rahmen), Container-Vererbung sowie `shouldDrawStandardQuad`/`onCustomRender`-Hooks. |
| `SpriteNode` | `class` | VN-Sprite-Knoten (`extends SceneNode`) mit `imageTag`/`attributes`/`assetPath`-Metadaten sowie nativer Unterstützung für `Texture`, `NineSliceDrawable` und `CustomDisplayable`. |
| `SceneController` | `final class` | High-Level-Bühnensteuerung (`scene` in `@->{ ... }<<`) für tag-basiertes `setScene`, `showSprite` (mit Positions-/Transform-Erhalt) und `hideSprite` auf `SceneGraph`-Ebenen inklusive Bild-/Transform-Registry. |
| `SceneController.StageEntry` & `StageSnapshot` | `record` | Unveränderliche Schnappschüsse einzelner Bühnen-Sprites und des gesamten Bühnenzustands (Hintergrund + aktive Sprites) für deterministisches M4-Rollback und Save/Load. |
| `CustomDisplayable` | `interface` | Erweiterbarer Vertrag (`update`, `render`, `getPreferredWidth`, `getPreferredHeight`) für prozedurale Grafik, Partikel oder Live-Gauges in Java oder `.vjava`-`@->{ ... }<<`-Blöcken. |
| `Texture` | `final class` | OpenGL-2D-Textur-Handle im Premultiplied-`RGBA8`-Format (`GL_LINEAR`, `GL_CLAMP_TO_EDGE`) inklusive CPU-Premultiplication (`premultiplyRgba`) und Fallback-Markierung. |
| `TextureManager` | `final class` | Reiner `STBImage`/`STBImageWrite`-Textur-Loader, kanonischer Pfad-Cache, PNG-Exporter (`writePng`) und prozeduraler Magenta/Dunkelviolett-Schraffur-Fallback bei fehlenden/defekten Assets. |
| `NineSliceDrawable` | `final class` | 9-Slice-Panel-Renderer (`SliceQuad`) für skalierbare Dialogboxen/UI-Rahmen mit proportionalem Clamping bei kleinen Zielgrößen und Pivot-Rotation. |
| `ShaderProgram` | `final class` | GLSL-Shader-Compiler/-Linker für OpenGL 3.3 Core mit Uniform-Location-Caching und Typ-Helfern (`mat4`, `vec4`, `vec2`, `float`, `int`). |
| `Framebuffer` | `final class` | Offscreen-OpenGL-FBO mit `RGBA8`-Farbtextur (`GL_LINEAR`, `GL_CLAMP_TO_EDGE`), Resize-Support und Top-to-Bottom-RGBA-Readback (`readPixelsRgba`). |
| `GlfwWindow` | `final class` | Objektorientierter Wrapper um ein natives GLFW-Fenster inklusive OpenGL-3.3-Core-Kontext, HiDPI-Framebuffer-Tracking und per-Window-`GLCapabilities`. |
| `WindowManager` | `final class` | Verwaltet den GLFW-Lebenszyklus und erzeugt entweder Dual-Windows (Studio A + Game B mit geteiltem Objekt-Namespace) oder ein einzelnes Standalone-Fenster. |
| `TextTagParser` | `final class` | Parser für VN-Rich-Text mit `[var]`-Interpolation, `[[`/`{{`-Escaping, Formatierungs-/Metrik-Tags, Flow-Cues (`CueKind`), Furigana (`RubyPair`), Inline-Bildern (`InlineImageSpan`), Hyperlinks und Echtzeit-Effekten (`wave`, `jitter`/`shake`, `gradient`). |
| `FontAtlas` | `final class` | Dynamischer Premultiplied-`RGBA8`-Glyphenatlas auf Basis von `STBTruetype` mit integriertem prozeduralen CPU-Bitmap-Fallback (inkl. Umlauten) für Headless/fehlende Fonts. |
| `FontManager` | `final class` | Zentrale Font-Registry und `FontAtlas`-Cache mit In-Memory-/Datei-/Ressourcen-/System-Font-Auflösung sowie globalen Barrierefreiheits-Einstellungen (`dyslexiaFriendlyMode`, `globalTextScale`). |
| `StyledTextLayout` | `final class` | Wortumbruch-, Metrik-, Furigana-, Hyperlink-Hitbox- und Animations-Renderer (`GlyphQuad`, `LineDecoration`, `RubyLayout`, `InlineImageLayout`, `HyperlinkHitbox`, `TextLine`) inklusive `TypewriterController`. |

## Öffentliche API-Signaturen

### `RenderMode`
```java
public enum RenderMode {
    MODE_2D,
    MODE_3D;

    public static RenderMode fromString(String s)
}
```

### `IRenderPipeline`
```java
public interface IRenderPipeline {
    void init(int logicalWidth, int logicalHeight);
    void beginFrame(int windowWidth, int windowHeight);
    void renderSceneGraph(SceneGraph graph);
    void endFrame();
    RenderMode getMode();
    void dispose();
}
```

### `RenderPipeline2D`
```java
public final class RenderPipeline2D implements IRenderPipeline {
    public static final int DEFAULT_LOGICAL_WIDTH = 1920;
    public static final int DEFAULT_LOGICAL_HEIGHT = 1080;
    public static final float CLEAR_R = 0x0D / 255.0f;
    public static final float CLEAR_G = 0x0E / 255.0f;
    public static final float CLEAR_B = 0x15 / 255.0f;
    public static final float CLEAR_A = 1.0f;

    public static Rect2f computeLetterboxViewport(int windowW, int windowH, int logicalW, int logicalH)

    @Override public void init(int logicalWidth, int logicalHeight)
    @Override public void beginFrame(int windowWidth, int windowHeight)
    @Override public void renderSceneGraph(SceneGraph graph)
    @Override public void endFrame()
    @Override public RenderMode getMode()
    @Override public void dispose()

    public void drawRect(float x, float y, float width, float height, float r, float g, float b, float a)
    public void drawRect(float x, float y, float width, float height, float[] rgba)
    public void drawRoundedRect(float x, float y, float width, float height, float cornerRadius, float r, float g, float b, float a)
    public void drawRoundedRect(float x, float y, float width, float height, float cornerRadius, float[] fillRgba, float borderThickness, float[] borderRgba)
    public void drawTexturedQuad(int textureId, float x, float y, float width, float height)
    public void drawTexturedQuad(int textureId, float x, float y, float width, float height, Rect2f uvRect, float alpha)
    public void drawTexturedQuad(int textureId, float x, float y, float width, float height, Rect2f uvRect, float[] tintRgba, ColorMatrix4f colorMatrix)
    public void drawTexturedQuad(int textureId, float x, float y, float width, float height, Rect2f uvRect, float[] tintRgba, ColorMatrix4f colorMatrix, float rotateDegrees, float originX, float originY)
    public void drawTexturedQuadPivot(int textureId, float x, float y, float width, float height, Rect2f uvRect, float[] tintRgba, ColorMatrix4f colorMatrix, float rotateDegrees, float pivotX, float pivotY)

    public Matrix4f getLogicalProjection()
    public Rect2f getCurrentRenderBounds()
    public float getCurrentEffectiveAlpha()
    public ColorMatrix4f getCurrentEffectiveColorMatrix()
    public float getCurrentRotationDegrees()
    public float getCurrentAnchorX()
    public float getCurrentAnchorY()
    public Rect2f getLastLetterboxViewport()
    public Framebuffer getOffscreenFramebuffer()
    public int getLogicalWidth()
    public int getLogicalHeight()
    public boolean isInitialized()
}
```

### `RenderPipeline3DStub`
```java
public final class RenderPipeline3DStub implements IRenderPipeline {
    public static final float DEFAULT_FOV_DEGREES = 75.0f;
    public static final float Z_NEAR = 100.0f;
    public static final float Z_FAR = 100000.0f;

    public RenderPipeline3DStub()
    public RenderPipeline3DStub(EngineLogger logger)
    public RenderPipeline3DStub(EngineLogger logger, boolean glEnabled)

    public static float computeCameraDistanceZ(int logicalWidth, float fovDegrees)

    @Override public void init(int logicalWidth, int logicalHeight)
    @Override public void beginFrame(int windowWidth, int windowHeight)
    @Override public void renderSceneGraph(SceneGraph graph)
    @Override public void endFrame()
    @Override public RenderMode getMode()
    @Override public void dispose()

    public Matrix4f getProjectionMatrix()
    public Matrix4f getViewMatrix()
    public float getCameraDistanceZ()
    public float getFovDegrees()
    public int getLogicalWidth()
    public int getLogicalHeight()
    public boolean isInitialized()
    public boolean isWarningLogged()
}
```

### `SceneGraph`
```java
public final class SceneGraph {
    public static final String LAYER_BACKGROUND = "background";
    public static final String LAYER_MASTER = "master";
    public static final String LAYER_FOREGROUND = "foreground";
    public static final String LAYER_SCREENS = "screens";
    public static final String LAYER_OVERLAY = "overlay";
    public static final String LAYER_MODAL = "modal";
    public static final String LAYER_TOAST = "toast";
    public static final String LAYER_INSPECTOR = "inspector";

    public SceneGraph()
    public SceneLayer getLayer(String name)
    public SceneLayer getOrCreateLayer(String name, int zOrder)
    public List<SceneLayer> getLayersSorted()
    public void addNode(String layerName, SceneNode node)
    public int removeByTag(String layerName, String tag)
    public void clearAll()
    public ScreenShake getScreenShake()
    public void setScreenShake(ScreenShake screenShake)
    public void update(float dt)
    public void setNormalizedMouse(float nx, float ny)
    public float getNormalizedMouseX()
    public float getNormalizedMouseY()
    public Vec2f getNormalizedMouse()
}
```

### `SceneLayer`
```java
public final class SceneLayer {
    public static final int Z_BACKGROUND = -100;
    public static final int Z_MASTER = 0;
    public static final int Z_FOREGROUND = 100;
    public static final int Z_SCREENS = 200;
    public static final int Z_OVERLAY = 300;
    public static final int Z_MODAL = 400;
    public static final int Z_TOAST = 500;
    public static final int Z_INSPECTOR = 1000;

    public SceneLayer(String name, int zOrder)
    public SceneLayer(String name, int zOrder, float parallaxFactor)
    public String getName()
    public int getZOrder()
    public SceneLayer setZOrder(int zOrder)
    public boolean isVisible()
    public SceneLayer setVisible(boolean visible)
    public float getParallaxFactor()
    public SceneLayer setParallaxFactor(float parallaxFactor)
    public SceneLayer addNode(SceneNode node)
    public boolean removeNode(SceneNode node)
    public int removeByTag(String tag)
    public List<SceneNode> findByTag(String tag)
    public SceneNode findById(String id)
    public void clear()
    public int size()
    public boolean isEmpty()
    public List<SceneNode> getNodes()
    public List<SceneNode> getSortedNodes()
}
```

### `SceneNode`
```java
public class SceneNode {
    public SceneNode()
    public SceneNode(String id)
    public SceneNode(String id, String tag)
    public static float[] parseHexRgba(String hex)

    public String getId() / public SceneNode setId(String id)
    public String getTag() / public SceneNode setTag(String tag)
    public int getZOrder() / public SceneNode setZOrder(int zOrder)
    public boolean isVisible() / public SceneNode setVisible(boolean visible)
    public Transform2D getTransform() / public SceneNode setTransform(Transform2D transform)
    public float getWidth() / public SceneNode setWidth(float width)
    public float getHeight() / public SceneNode setHeight(float height)
    public SceneNode setSize(float width, float height)
    public float[] getFillColorRgba() / public SceneNode setFillColorRgba(float[] rgba)
    public SceneNode setFillColor(float r, float g, float b, float a)
    public SceneNode setFillColorPremultiplied(float rPre, float gPre, float bPre, float a)
    public SceneNode setColorHex(String hex)
    public boolean isFillColorPremultiplied()
    public float[] getPremultipliedFillColor(float additionalAlpha)
    public float getCornerRadius() / public SceneNode setCornerRadius(float cornerRadius)
    public float getBorderThickness() / public SceneNode setBorderThickness(float borderThickness)
    public float[] getBorderColorRgba() / public SceneNode setBorderColorRgba(float[] rgba)
    public SceneNode setBorderColor(float r, float g, float b, float a)
    public SceneNode setBorderColorHex(String hex)
    public float[] getPremultipliedBorderColor(float additionalAlpha)
    public int getTextureId() / public SceneNode setTextureId(int textureId)
    public Rect2f getUvRect() / public SceneNode setUvRect(Rect2f uvRect)
    public Object getUserData() / public SceneNode setUserData(Object userData)
    public SceneNode getParent()
    public SceneNode addChild(SceneNode child)
    public boolean removeChild(SceneNode child)
    public void clearChildren()
    public List<SceneNode> getChildren()
    public BiConsumer<SceneNode, RenderPipeline2D> getCustomRenderer()
    public SceneNode setCustomRenderer(BiConsumer<SceneNode, RenderPipeline2D> customRenderer)
    protected boolean shouldDrawStandardQuad()
    protected void onCustomRender(RenderPipeline2D pipeline)
}
```

### `SpriteNode`
```java
public class SpriteNode extends SceneNode {
    public SpriteNode()
    public SpriteNode(String id)
    public SpriteNode(String id, String tag)
    public SpriteNode(String id, String imageTag, List<String> attributes)

    public String getImageTag() / public SpriteNode setImageTag(String imageTag)
    public List<String> getAttributes() / public SpriteNode setAttributes(List<String> attributes)
    public String getAssetPath() / public SpriteNode setAssetPath(String assetPath)
    public Texture getTexture() / public SpriteNode setTexture(Texture texture)
    public NineSliceDrawable getNineSlice() / public SpriteNode setNineSlice(NineSliceDrawable nineSlice)
    public CustomDisplayable getCustomDisplayable() / public SpriteNode setCustomDisplayable(CustomDisplayable customDisplayable)
    public void update(double dt)

    @Override protected boolean shouldDrawStandardQuad()
    @Override protected void onCustomRender(RenderPipeline2D pipeline)
}
```

### `SceneController`
```java
public final class SceneController {
    public record StageEntry(String tag, List<String> attributes, String positionName, String assetPath, Transform2D transform) {}
    public record StageSnapshot(String backgroundTag, String backgroundAssetPath, Transform2D backgroundTransform, Map<String, StageEntry> activeSprites) {}

    public SceneController(SceneGraph sceneGraph)
    public SceneController(SceneGraph sceneGraph, TextureManager textureManager, EngineLogger logger, Path projectRoot, boolean glTextureUploadEnabled)

    public void setScene(String sceneName, String transitionExpr)
    public SpriteNode showSprite(String tag, List<String> attributes, String positionOrTransform, String transitionExpr)
    public boolean hideSprite(String tag, String transitionExpr)
    public void registerImageDefinition(String key, String assetPath)
    public void registerTransform(String name, Transform2D transform)
    public StageSnapshot snapshotState()
    public void restoreState(StageSnapshot snapshot)
    public void clearMasterSprites()

    public SpriteNode getActiveSprite(String tag)
    public Map<String, SpriteNode> getActiveSprites()
    public String getBackgroundTag()
    public SpriteNode getBackgroundNode()
    public String getLastTransitionExpr()
    public SceneGraph getSceneGraph()
    public void setTransitionListener(BiConsumer<String, String> listener)
    public void setProjectRoot(Path projectRoot)
}
```

### `CustomDisplayable`
```java
public interface CustomDisplayable {
    void update(double dt);
    void render(RenderPipeline2D pipeline, Rect2f bounds, float alpha, ColorMatrix4f colorMatrix);
    default float getPreferredWidth()
    default float getPreferredHeight()
}
```

### `Texture`
```java
public final class Texture {
    public Texture(int textureId, int width, int height, String name, boolean premultiplied, boolean fallback)
    public Texture(int textureId, int width, int height, String name, boolean premultiplied)

    public static byte[] premultiplyRgba(byte[] straightRgba)
    public static Texture createFromRgba(int width, int height, byte[] rgbaBytes, boolean alreadyPremultiplied, String name)

    public void bind(int textureUnit)
    public void unbind()
    public void dispose()
    public int getTextureId()
    public int getWidth()
    public int getHeight()
    public String getName()
    public boolean isPremultiplied()
    public boolean isFallback()
    public boolean isDisposed()
}
```

### `TextureManager`
```java
public final class TextureManager {
    public static final int DEFAULT_FALLBACK_WIDTH = 128;
    public static final int DEFAULT_FALLBACK_HEIGHT = 128;

    public TextureManager()
    public TextureManager(EngineLogger logger)
    public TextureManager(Path assetRoot)
    public TextureManager(EngineLogger logger, Path assetRoot)

    public static byte[] generateMissingAssetRgba(int width, int height)
    public static void writePng(Path destination, int width, int height, byte[] straightRgba)
    public static byte[] readImageFileRgba(Path path, int[] outSize)

    public Texture loadTexture(String assetPath)
    public Texture loadTexture(Path path)
    public Texture getOrCreateFallbackTexture()
    public Texture getCached(String assetPath)
    public Texture getCached(Path path)
    public int getCacheSize()
    public Path getAssetRoot()
    public void setAssetRoot(Path assetRoot)
    public EngineLogger getLogger()
    public void clearCache()
    public void dispose()
}
```

### `NineSliceDrawable`
```java
public final class NineSliceDrawable {
    public record SliceQuad(Rect2f destRect, Rect2f uvRect) {}

    public NineSliceDrawable(Texture texture, float inset)
    public NineSliceDrawable(Texture texture, float left, float top, float right, float bottom)
    public NineSliceDrawable(int textureId, float texWidth, float texHeight, float left, float top, float right, float bottom)

    public List<SliceQuad> computeSlices(float x, float y, float width, float height)
    public void render(RenderPipeline2D pipeline, float x, float y, float width, float height)
    public void render(RenderPipeline2D pipeline, float x, float y, float width, float height, float[] tintRgba, ColorMatrix4f colorMatrix)
    public void render(RenderPipeline2D pipeline, float x, float y, float width, float height, float[] tintRgba, ColorMatrix4f colorMatrix, float rotateDegrees, float anchorX, float anchorY)

    public Texture getTexture()
    public int getTextureId()
    public float getTexWidth()
    public float getTexHeight()
    public float getLeft()
    public float getTop()
    public float getRight()
    public float getBottom()
}
```

### `ShaderProgram`
```java
public final class ShaderProgram {
    public ShaderProgram()
    public ShaderProgram(String vertexSrc, String fragmentSrc)
    public void compile(String vertexSrc, String fragmentSrc)
    public void bind()
    public void unbind()
    public void setUniformMat4(String name, float[] columnMajorMatrix)
    public void setUniformMat4(String name, Matrix4f matrix)
    public void setUniformVec4(String name, float x, float y, float z, float w)
    public void setUniformVec2(String name, float x, float y)
    public void setUniformFloat(String name, float value)
    public void setUniformInt(String name, int value)
    public int getUniformLocation(String name)
    public int getProgramId()
    public boolean isCompiled()
    public void dispose()
}
```

### `Framebuffer`
```java
public final class Framebuffer {
    public Framebuffer()
    public Framebuffer(int width, int height)
    public void init()
    public void resize(int width, int height)
    public void bind()
    public void unbind()
    public byte[] readPixelsRgba()
    public int getTextureId()
    public int getFboId()
    public int getWidth()
    public int getHeight()
    public boolean isInitialized()
    public void dispose()
}
```

### `GlfwWindow`
```java
public final class GlfwWindow {
    public GlfwWindow(long handle, String title, int windowWidth, int windowHeight)
    public void makeCurrent()
    public void swapBuffers()
    public boolean shouldClose()
    public void setShouldClose(boolean close)
    public void setTitle(String title)
    public void setVsync(boolean vsync)
    public void pollFramebufferSize()
    public void show()
    public void hide()
    public void focus()
    public void destroy()
    public long getHandle()
    public String getTitle()
    public int getWindowWidth()
    public int getWindowHeight()
    public int getFramebufferWidth()
    public int getFramebufferHeight()
    public GLCapabilities getGlCapabilities()
    public boolean isVsync()
    public boolean isDestroyed()
}
```

### `WindowManager`
```java
public final class WindowManager {
    public WindowManager()
    public WindowManager(EngineLogger logger)
    public void initDualWindows(String studioTitle, String gameTitle, int gameWidth, int gameHeight, boolean vsync)
    public void initDualWindows(String studioTitle, int studioWidth, int studioHeight, String gameTitle, int gameWidth, int gameHeight, boolean vsync)
    public void initStandaloneWindow(String gameTitle, int width, int height, boolean vsync)
    public GlfwWindow getGameWindow()
    public GlfwWindow getStudioWindow()
    public boolean hasStudioWindow()
    public void pollEvents()
    public boolean anyWindowShouldClose()
    public boolean isGlfwInitialized()
    public void dispose()
}
```

### `TextTagParser`
```java
public final class TextTagParser {
    public static final char OBJECT_REPLACEMENT_CHAR = '\uFFFC';

    public enum CueKind { WAIT_CLICK, WAIT_TIMED, PARAGRAPH, NO_WAIT, FAST }

    public record TextStyle(
            boolean bold, boolean italic, boolean underline, boolean strikethrough, boolean smallCaps,
            String colorHex, float alpha, float fontSize, float sizeDelta, String fontPath,
            float kerning, float cps, boolean wave, boolean jitter,
            String gradientStartHex, String gradientEndHex, String hyperlinkTarget
    ) {
        public static final TextStyle DEFAULT;
        public float resolveFontSize(float baseFontSize)
    }

    public record TextCue(int index, CueKind kind, double seconds) {}
    public record RubyPair(int startIndex, int endIndex, String baseText, String rubyText) {}
    public record InlineImageSpan(int charIndex, String imagePath, float width, float height) {}
    public record TextSpan(String text, TextStyle style, String rubyReading, String inlineImagePath) {
        public TextSpan withRubyReading(String reading)
    }
    public record ParsedRichText(String plainText, List<TextSpan> spans, List<TextCue> cues, int fastPrefixEndIndex, boolean hasNoWait) {
        public List<RubyPair> rubyPairs()
        public List<InlineImageSpan> inlineImages()
        public String spokenText()
    }

    public static String interpolateVariables(String raw, Function<String, Object> lookup)
    public static ParsedRichText parse(String raw)
    public static ParsedRichText parse(String raw, Function<String, Object> lookup)
}
```

### `FontAtlas`
```java
public final class FontAtlas implements AutoCloseable {
    public record GlyphMetrics(
            int codepoint, float advanceWidth, float leftSideBearing,
            float x0, float y0, float x1, float y1,
            float u0, float v0, float u1, float v1
    ) {
        public float width()
        public float height()
    }

    public static FontAtlas createBuiltinFallback(float baseSize, boolean uploadGl)
    public static FontAtlas fromTrueTypeBytes(byte[] fontBytes, float baseSize, boolean uploadGl)
    public static FontAtlas fromTrueTypeFile(Path fontPath, float baseSize, boolean uploadGl) throws IOException

    public void uploadToOpenGl()
    public GlyphMetrics getGlyph(int codepoint)
    public void ensureCodepoints(String text)
    public float getKerning(int cp1, int cp2, float fontSize)
    public float measureTextWidth(String text, float fontSize, float extraKerning)
    public boolean hasGlyph(int codepoint)
    public int getGlTextureId()
    public float getBaseSize()
    public float getAscent()
    public float getAscent(float fontSize)
    public float getDescent()
    public float getLineGap()
    public float getLineHeight()
    public float getLineHeight(float fontSize)
    public int getAtlasWidth()
    public int getAtlasHeight()
    public boolean isBuiltinFallback()
    public boolean isDisposed()
    public byte[] getAtlasRgbaCopy()
    public void dispose()
    @Override public void close()
}
```

### `FontManager`
```java
public final class FontManager implements AutoCloseable {
    public FontManager()
    public FontManager(Path assetRoot, boolean systemFontFallbackEnabled)

    public static Optional<Path> discoverSystemFontPath(boolean dyslexiaFriendly)

    public FontManager registerFont(String fontKey, byte[] fontBytes)
    public FontManager registerFont(String fontKey, Path filePath) throws IOException
    public FontManager setAssetRoot(Path assetRoot) / public Path getAssetRoot()
    public FontManager setDefaultFontPath(String defaultFontPath) / public String getDefaultFontPath()
    public FontManager setDyslexiaFontPath(String dyslexiaFontPath) / public String getDyslexiaFontPath()
    public FontManager setSystemFontFallbackEnabled(boolean enabled) / public boolean isSystemFontFallbackEnabled()
    public FontManager setDyslexiaFriendlyMode(boolean dyslexiaFriendlyMode) / public boolean isDyslexiaFriendlyMode()
    public FontManager setGlobalTextScale(float globalTextScale) / public float getGlobalTextScale()
    public float scaleFontSize(float requestedSizePx)
    public float getAccessibilityExtraKerning()
    public float getAccessibilityLineSpacingMultiplier()
    public FontAtlas getAtlas(String fontPath, float sizePx, boolean uploadGl)
    public void dispose()
    @Override public void close()
    public boolean isDisposed()
    public int getCachedAtlasCount()
}
```

### `StyledTextLayout`
```java
public final class StyledTextLayout {
    public enum DecorationKind { UNDERLINE, STRIKETHROUGH, DROP_SHADOW, OUTLINE }

    public record GlyphQuad(
            FontAtlas atlas, int codepoint, int charIndex, int lineIndex,
            float x, float y, float width, float height, float advance, float baselineY,
            float effectiveSizePx, Rect2f uvRect, float[] colorRgba,
            boolean bold, boolean italic, boolean smallCaps,
            boolean wave, boolean jitter, boolean hasGradient
    ) {}
    public record LineDecoration(
            DecorationKind kind, int lineIndex, float x, float y, float width, float thickness,
            float[] colorRgba, int startCharIndex, int endCharIndex
    ) {}
    public record RubyLayout(
            String rubyText, FontAtlas atlas, int lineIndex, float x, float baselineY,
            float width, float height, float baseCenterX, float fontSize,
            int startCharIndex, int endCharIndex, List<GlyphQuad> rubyQuads
    ) {}
    public record InlineImageLayout(
            String imagePath, int charIndex, int lineIndex,
            float x, float y, float width, float height, float alpha
    ) {}
    public record HyperlinkHitbox(Rect2f bounds, String actionTarget, int lineIndex) {}
    public record TextLine(int lineIndex, float y, float baselineY, float width, float height, int startCharIndex, int endCharIndex) {}

    public static final class TypewriterController {
        public static final float DEFAULT_CPS = 40.0f;

        public TypewriterController(TextTagParser.ParsedRichText richText)
        public TypewriterController(TextTagParser.ParsedRichText richText, float defaultCps)

        public void reset()
        public void advance(double dt)
        public boolean clickOrAdvance()
        public boolean isWaiting()
        public boolean isTimedWait()
        public double getWaitRemainingSeconds()
        public TextTagParser.TextCue getActiveWaitCue()
        public boolean isFinished()
        public int getVisibleCharCount()
        public int getTotalCharCount()
        public boolean hasNoWait()
        public boolean shouldAutoAdvance()
        public float getDefaultCps()
        public void setDefaultCps(float defaultCps)
    }

    public StyledTextLayout(TextTagParser.ParsedRichText richText, FontManager fontManager, float maxWidth, float baseFontSize, float lineSpacing, String baseColorHex, boolean uploadGl)
    public static StyledTextLayout layout(TextTagParser.ParsedRichText richText, FontManager fontManager, float maxWidth, float baseFontSize, float lineSpacing, String baseColorHex, boolean uploadGl)

    public StyledTextLayout setDropShadow(float offsetX, float offsetY, String shadowColorHex)
    public StyledTextLayout setOutline(float thickness, String outlineColorHex)
    public void render(RenderPipeline2D pipeline, TextureManager textureManager, float originX, float originY, double elapsedTimeSeconds)
    public void render(RenderPipeline2D pipeline, TextureManager textureManager, float originX, float originY, double elapsedTimeSeconds, int visibleCharLimit, float globalAlpha)
    public Optional<String> hitTestHyperlink(float localX, float localY)
    public Optional<String> hitTestHyperlink(float canvasX, float canvasY, float originX, float originY)

    public TextTagParser.ParsedRichText getRichText()
    public float getMaxWidth()
    public float getBaseFontSize()
    public float getLineSpacing()
    public List<GlyphQuad> getGlyphQuads()
    public List<LineDecoration> getDecorations()
    public List<RubyLayout> getRubyLayouts()
    public List<InlineImageLayout> getInlineImages()
    public List<HyperlinkHitbox> getHyperlinkHitboxes()
    public List<TextLine> getLines()
    public int getLineCount()
    public float getTotalWidth()
    public float getTotalHeight()
    public float getShadowOffsetX()
    public float getShadowOffsetY()
    public float getOutlineThickness()
}
```
