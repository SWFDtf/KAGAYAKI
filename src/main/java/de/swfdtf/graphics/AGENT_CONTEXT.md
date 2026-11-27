# Package `de.swfdtf.graphics` — AGENT_CONTEXT

## Zweck des Pakets
LWJGL-3 / OpenGL-3.3-Core-Grafik-, Animations-, Transitions-, Partikel-, Video- und Textsubsystem von KAGAYAKI (Meilensteine M1–M3, M5 & M6, vollständig ohne `java.awt`): Dual-Window- und Standalone-GLFW-Verwaltung mit gemeinsamen OpenGL-Objekt-Namespaces (`WindowManager`, `GlfwWindow`), Z-sortierter Szenengraph (`SceneGraph`, `SceneLayer`, `SceneNode`, `SpriteNode`, `LayeredImage`, `CustomDisplayable`) mit 8 Standard-Ebenen (`-100` bis `1000`), High-Level-Bühnensteuerung (`SceneController` inklusive `StageEntry`/`StageSnapshot` für Skript-Laufzeit und Rollback sowie `LayeredImage`-Anbindung), Offscreen-Framebuffer (`Framebuffer`), GLSL-Shader-Compiler (`ShaderProgram`), das einheitliche Pipeline-Interface `IRenderPipeline` mit `RenderPipeline2D` (Premultiplied Alpha, `ColorMatrix4f`, SDF-Rounded-Rectangles, Pivot-Rotation, Letterbox-Blit) und `RenderPipeline3DStub`, reine STB-Bild-/Texturverwaltung (`Texture`, `TextureManager`, `NineSliceDrawable`), Rich-Text-, Typografie-, Furigana- und Typewriter-Engine (`TextTagParser`, `FontAtlas`, `FontManager`, `StyledTextLayout`), deklarative ATL-Animationen (`ATLTransform`, `AnimationEngine`), GPU-Dual-FBO-Szenenübergänge (`Transition`, `TransitionManager`), prozedurales 2D-Partikelsystem (`ParticleSystem`) sowie Video-Playback (`VideoPlayer`, `MovieSprite`).

## Abhängigkeiten zu anderen `de.swfdtf.*`-Paketen
- `de.swfdtf.math` (`ColorMatrix4f`, `Easing`, `Rect2f`, `ScreenShake`, `Transform2D`, `Vec2f`)
- `de.swfdtf.core` (`EngineLogger` in `WindowManager`, `RenderPipeline3DStub`, `TextureManager`, `SceneController`, `VideoPlayer`)
- `de.swfdtf.parser` (`VJavaAst` in `ATLTransform`, `LayeredImage`, `SceneController`; `ConditionEvaluator` in `LayeredImage`)
- `de.swfdtf.state` (`StateStore`, `PersistentAccess` in `LayeredImage`, `SceneController`)
- `de.swfdtf.audio` (`LipSyncAnalyzer` in `LayeredImage`)
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
| `LayeredImage` | `class` | Mehrstufiges Composite-Sprite (`implements CustomDisplayable`) aus `VJavaAst.LayeredImageDef` oder `Builder` mit `always`-, `group`- (inkl. `0.20s`-Crossfade und `LipSyncAnalyzer`-Mundformen) und `if`-Ebenen. |
| `LayeredImage.LayerRenderInfo` & `Builder` | `record` / `final class` | Render-Snapshot einer einzelnen aktiven Ebene (`assetPath`, `alpha`) sowie Fluent-Builder zur programmatischen `LayeredImage`-Erstellung. |
| `SceneController` | `final class` | High-Level-Bühnensteuerung (`scene` in `@->{ ... }<<`) für tag-basiertes `setScene`, `showSprite` (mit Positions-/Transform-Erhalt und `LayeredImage`-Erstellung/-Aktualisierung), `hideSprite`, `update(dt)` sowie Bild-/Transform-/`LayeredImage`-Registry. |
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
| `ATLTransform` | `final class` | Deklarative ATL-Transformations-Timeline (`fromAst`, `Builder`) mit `set`, `linear`/`ease*`/`cubicBezier`-Interpolation, `pause`, `repeat`, `parallel`, `block`, `choice`, `crop` und `matrixcolor`-Ausdrücken gegen `Transform2D`. |
| `AnimationEngine` | `final class` | Zentraler Timeline-Manager für aktive, per String-ID an `SceneNode`s gebundene `ATLTransform`-Animationen inklusive Abschluss-Callbacks. |
| `Transition` | `final class` | Unveränderlicher Deskriptor für 20 Szenenübergangsarten (`Kind`: `DISSOLVE`, `FADE`, `PIXELLATE`, `MOVE`, `EASE`, `WIPE_*`, `SLIDE_*`, `PUSH_*`, `IRIS_*`, `BLINDS`, `SQUARES`, `IMAGE_DISSOLVE`, `MULTIPLE`) inklusive `Transition.parse(String)`. |
| `TransitionManager` | `final class` | Headless-fähige Transitions-Timeline-State-Machine und OpenGL-3.3-Core-Dual-FBO-Shader-Compositor (`captureFromFbo`, `renderComposite`) inklusive `MULTIPLE`-Sequenzierung. |
| `ParticleSystem` | `final class` | GPU-batchbares und headless simulierbares 2D-Partikel- und Wettersystem (`implements CustomDisplayable`) mit `Particle`, `ParticleEmitter` und 5 Presets (`SNOW_BLOSSOM`, `RAIN`, `CHERRY_BLOSSOM`, `SPARKS`, `DUST`). |
| `VideoPlayer` | `final class` | Video-Playback-Controller (`implements AutoCloseable`) mit austauschbaren `FrameSource`-Streams (`ProceduralTestFrameSource`, Virtual-Thread-`FfmpegProcessFrameSource`) und optionalem OpenGL-Textur-Upload. |
| `MovieSprite` | `class` | Animierter `SpriteNode` (`extends SpriteNode implements AutoCloseable`), der einen `VideoPlayer` für Cutscenes und Video-Hintergründe in den Szenengraph einbindet. |
| `WeatherOverlayController` | `final class` | Dynamische Wetter-Presets (`CLEAR`, `RAIN`, `SNOW`, `FOG`, `EMBERS`) mit Windvektor, sanftem Intensitätsübergang, Nebel-Tint (`ColorMatrix4f`) und optionaler `ParticleSystem`-Synchronisation. |
| `ColorBlindnessFilter` | `final class` | Barrierefreiheits-Farbmatrix-Generator (`NONE`, `PROTANOPIA`, `DEUTERANOPIA`, `TRITANOPIA`, `HIGH_CONTRAST`, `GRAYSCALE`) mit Intensitätsmischung `0..1`. |
| `CameraDirectorPresets` | `final class` | Kinematische Kamera-Presets (`ESTABLISHING`, `CLOSE_UP`, `DRAMATIC_TILT`, `PAN_LEFT_TO_RIGHT`, `ZOOM_PULSE`) mit flüssiger Interpolation zu `CameraState` und `Transform2D`. |
| `ObjectMotionController` | `final class` | Interaktive Bewegungs-, WASD-, 2D-Physik- und AABB-Kollisionssteuerung (`MotionInput`) auf aktiven `SceneController`-Sprites. |
| `ObjectMotionController.MotionInput` | `record` | Unveränderlicher Bewegungs- und Sprung-Eingabevektor (`up`, `down`, `left`, `right`, `jumpPressed`) für `ObjectMotionController.update`. |
| `ScreenshotCaptureService` | `final class` | Schreibt RGBA8-Framebuffer-Snapshots ohne `java.awt` über `TextureManager.writePng` als PNGs inklusive Dateinamen-Bereinigung und Listing. |
| `CinematicLetterboxController` | `final class` | Animiert Kinobalken (`Rect2f` oben/unten) im logischen `1920x1080`-Koordinatenraum. |
| `DynamicTextureAtlas` | `final class` | Dynamischer Shelf-Packer für Sprite-/Icon-Atlanten (`AtlasRegion`, `BatchStats`) im Premultiplied-`RGBA8`-Format zur Minimierung von Textur-Binds. |
| `AnimatedSpriteNode` | `class` | Animierter Sprite-Sheet-`SpriteNode` (`PlayMode`: `LOOP`, `PING_PONG`, `ONCE`) mit automatischer Grid-UV-Fortschaltung über `update(dt)`. |
| `WebpVp8lDecoder` | `final class` | Reiner Java-Decoder für verlustfreie `RIFF/WEBP/VP8L`-Bilder (`DecodedRgba`) ohne `java.awt`. |
| `ParticleEmitterNode` | `class` | Szenengraph-Knoten (`extends SpriteNode`) für Schnee-, Regen-, Glühwürmchen-, Sakura- und Funken-Partikel inklusive Dialogbox-Kollision und Intensitäts-Fade. |
| `Lighting2DSystem` | `final class` | 2D-Punktlicht- und Normal-Map-(`*_n.png`)-Beleuchtungssystem mit Flacker-Modulation sowie CPU-/Shader-Auswertung. |
| `CustomShaderRegistry` | `final class` | Sicherer GLSL-Loader (`assets/shaders/`, `define shader`), Syntax-Validator mit Zeilennummer, Hot-Reload mit Fallback auf vorherigen gültigen Shader und Core-Shader-Manifestprüfung. |
| `RenderCacheNode` | `class` | Statischer UI-Unterbaum-Cache (`extends SceneNode`), der unveränderte UI-Hierarchien auf einen einzelnen Draw-Call reduziert bis Kinder oder Zustand invalidieren. |
| `GlResourceAuditor` | `final class` | OpenGL-Lebenszyklus-Auditor (`VAO`, `VBO`, `FBO`, `TEXTURE`, `SHADER`) zur Leck-Prüfung bei `dispose()` sowie Pass-Timer-Speicher mit `0.0 ms`-Headless-Fallback. |
| `GraphicsRuntimeServices` | `final class` | Gebündelte P2-Grafik-Services: `FramePacer` (FPS-Cap + `15 FPS` Idle-Throttle), `SoftwareRasterizer`, `AnimatedImageDecoder` (`stbi_load_gif_from_memory`), `VectorDebugRenderer`, `SpeechBubblePointer`, `ColorMatrixKeyframeTimeline`, `SplitViewConfig`, `SafeAreaGuides`, `AdaptiveContrastAdvisor`, `ColorLut`, `HdrToneMapper`, `FocusVignetteController`, `ReflectionZone`, `MotionTrailTracker`, `GradientSpec`, `SoftwareCursorRenderer` und `MaskConfig`. |
| `Mesh3D` | `final class` | 3D-Mesh-Container & Importer mit prozeduralen Primitiven (`Plane`, `Cube`, `Sphere`, `Cylinder`), Wavefront `.obj`/`.mtl`- & binärem `.glb`-Parser, `BoundingBox3D`/`BoundingSphere3D`, Tangenten, glatten Normalen und frame-budgetierter `MeshUploadQueue`. |
| `Material3D` | `final class` | 3D-Material-Deskriptor für PBR-Cook-Torrance-GGX, Toon-Shading, Wasser, Cutout und Decal (`ShaderStyle`) inklusive Uniform-Overrides und gemeinsamer Albedo-Textur-Auflösung. |
| `Camera3D` | `final class` | JOML-`Matrix4f`-3D-Kamera mit Perspektiv-/Orthoprojektion, FOV-Clamping `[50°, 110°]`, Keyframe-`moveTo`, `wasd3d`-Steuerung (`±89°` Pitch-Clamp), SpringArm-Kollision, `CameraRail`, `ShotPreset`, Trauma-Shake, Stereo-Matrizen, Frustum-Culling und `screenPointToRay`. |
| `Scene3DWorld` | `final class` | 3D-Weltgraph mit `Node3D` (LOD-Hysterese, 2.5D-Billboards, Collider, 2D-Surface-Attach), Top-8-Lichtselektion, `Fog3DConfig`, `Skydome3D`, planarer Spiegelungsmatrix, `InspectItemOverlay`, `Minimap3DProjector`, `Scene3DSnapshot` und `Scene3DPreloader`. |
| `RenderPipeline3D` | `final class` | OpenGL-3.3-Core-3D-Render-Pipeline (`implements IRenderPipeline`) mit Composite-Reihenfolge (`Sky2D -> World3D -> Billboards -> Foreground2D -> UI`), std140-Kamera-UBO (`176 B`), Tiefen-Prepass, MSAA, Auflösungs-Skalierung und nahtlosem 2D-Backdrop-Fallback. |

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
    public void registerLayeredImage(VJavaAst.LayeredImageDef def)
    public VJavaAst.LayeredImageDef getLayeredImageDefinition(String name)
    public void setStateContext(StateStore stateStore, PersistentAccess persistentAccess)
    public void update(double dt)
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

### `LayeredImage`
```java
public class LayeredImage implements CustomDisplayable {
    public static final float DEFAULT_CROSSFADE_DURATION_SECONDS = 0.20f;
    public static final String DEFAULT_MOUTH_GROUP_NAME = "mouth";

    public record LayerRenderInfo(String assetPath, float alpha) {}

    public static final class Builder {
        public Builder always(String assetPath)
        public Builder group(String groupName)
        public Builder attribute(String groupName, String attrName, boolean isDefault, String assetPath)
        public Builder attribute(String groupName, String attrName, boolean isDefault, String assetPath, String conditionExpr)
        public Builder conditional(String conditionExpr, String assetPath)
        public Builder textureManager(TextureManager textureManager)
        public Builder crossfadeDuration(float seconds)
        public Builder mouthGroupName(String mouthGroupName)
        public LayeredImage build()
    }

    public LayeredImage(VJavaAst.LayeredImageDef def)
    public LayeredImage(VJavaAst.LayeredImageDef def, TextureManager textureManager)
    public static Builder builder(String name)

    public void setAttributes(Collection<String> attributes)
    public void setAttributes(Collection<String> attributes, StateStore state, PersistentAccess persistent)
    public void resolve(StateStore state, PersistentAccess persistent)
    public void bindLipSync(LipSyncAnalyzer analyzer)
    public boolean applyMouthShape(LipSyncAnalyzer.MouthShape shape)
    @Override public void update(double dt)
    @Override public void render(RenderPipeline2D pipeline, Rect2f bounds, float alpha, ColorMatrix4f colorMatrix)
    @Override public float getPreferredWidth()
    @Override public float getPreferredHeight()
    public List<LayerRenderInfo> getActiveRenderLayers()
    public void registerLayerTexture(String assetPath, Texture texture)

    public String getName()
    public List<String> getResolvedLayerAssets()
    public List<String> getSelectedAttributes()
    public String getActiveAttribute(String groupName)
    public Map<String, String> getActiveGroupAttributes()
    public float getGroupPreviousAlpha(String groupName)
    public float getGroupCurrentAlpha(String groupName)
    public float getGroupFadeProgress(String groupName)
    public boolean isCrossfading()
    public float getCrossfadeDurationSeconds()
    public void setCrossfadeDurationSeconds(float crossfadeDurationSeconds)
    public String getMouthGroupName()
    public void setMouthGroupName(String mouthGroupName)
    public LipSyncAnalyzer getBoundLipSync()
    public TextureManager getTextureManager()
    public void setTextureManager(TextureManager textureManager)
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

### `ATLTransform`
```java
public final class ATLTransform {
    public static final class Builder {
        public Builder set()
        public Builder set(String property, String value)
        public Builder linear(double durationSeconds)
        public Builder ease(double durationSeconds)
        public Builder easein(double durationSeconds)
        public Builder easeIn(double durationSeconds)
        public Builder easeout(double durationSeconds)
        public Builder easeOut(double durationSeconds)
        public Builder easeInOut(double durationSeconds)
        public Builder smoothstep(double durationSeconds)
        public Builder interpolate(double durationSeconds, Easing.EaseType easeType)
        public Builder cubicBezier(double durationSeconds, float p1x, float p1y, float p2x, float p2y)
        public Builder pause(double durationSeconds)
        public Builder repeat()
        public Builder repeat(int count)
        public Builder repeat(int count, ATLTransform body)
        public Builder parallel(ATLTransform... branches)
        public Builder parallel(List<ATLTransform> branches)
        public Builder block(ATLTransform... children)
        public Builder block(List<ATLTransform> children)
        public Builder choice(List<ATLTransform> children)
        public Builder choice(List<ATLTransform> children, RandomGenerator random)
        public Builder choice(RandomGenerator random, ATLTransform... children)
        public Builder step(VJavaAst.AtlStep astStep)
        public Builder xpos(float xpos) / public Builder ypos(float ypos)
        public Builder xanchor(float xanchor) / public Builder yanchor(float yanchor)
        public Builder xalign(float xalign) / public Builder yalign(float yalign)
        public Builder zoom(float zoom) / public Builder xzoom(float xzoom) / public Builder yzoom(float yzoom)
        public Builder rotate(float rotateDegrees) / public Builder alpha(float alpha) / public Builder blur(float blur)
        public Builder parallaxX(float parallaxX) / public Builder parallaxY(float parallaxY)
        public Builder crop(Rect2f crop) / public Builder crop(float x, float y, float width, float height)
        public Builder colorMatrix(ColorMatrix4f colorMatrix) / public Builder matrixcolor(String expr)
        public Builder property(String key, String value)
        public ATLTransform build()
    }

    public static ATLTransform fromAst(List<VJavaAst.AtlStep> steps)
    public static ATLTransform fromAst(VJavaAst.TransformDef transformDef)
    public static Builder builder()
    public static ColorMatrix4f parseColorMatrix(String expr)
    public static Rect2f parseCropRect(String expr)

    public void start(Transform2D target)
    public void update(double dt, Transform2D target)
    public boolean isFinished()
    public boolean isStarted()
    public double getElapsedSeconds()
    public void reset()
}
```

### `AnimationEngine`
```java
public final class AnimationEngine {
    public void play(String id, SceneNode node, ATLTransform anim)
    public void play(String id, SceneNode node, ATLTransform anim, Runnable onComplete)
    public boolean stop(String id)
    public void stopAll()
    public void update(double dt)
    public boolean isPlaying(String id)
    public int getActiveCount()
    public ATLTransform getAnimation(String id)
    public List<String> getActiveIds()
}
```

### `Transition`
```java
public final class Transition {
    public enum Kind {
        NONE, DISSOLVE, FADE, PIXELLATE, MOVE, EASE,
        WIPE_LEFT, WIPE_RIGHT, WIPE_UP, WIPE_DOWN,
        SLIDE_LEFT, SLIDE_RIGHT, PUSH_LEFT, PUSH_RIGHT,
        IRIS_IN, IRIS_OUT, BLINDS, SQUARES, IMAGE_DISSOLVE, MULTIPLE
    }

    public static final float DEFAULT_DURATION_SECONDS = 0.5f;
    public static final String DEFAULT_FADE_COLOR_HEX = "#000000";
    public static final float DEFAULT_RAMP_LENGTH = 8.0f;
    public static final int DEFAULT_SLICE_COUNT = 16;

    public static Transition none()
    public static Transition dissolve(float durationSeconds)
    public static Transition fade(float durationSeconds)
    public static Transition fade(float durationSeconds, String fadeColorHex)
    public static Transition pixellate(float durationSeconds)
    public static Transition move(float durationSeconds)
    public static Transition ease(float durationSeconds)
    public static Transition ease(float durationSeconds, Easing.EaseType easeType)
    public static Transition wipeLeft(float durationSeconds) / public static Transition wipeRight(float durationSeconds)
    public static Transition wipeUp(float durationSeconds) / public static Transition wipeDown(float durationSeconds)
    public static Transition slideLeft(float durationSeconds) / public static Transition slideRight(float durationSeconds)
    public static Transition pushLeft(float durationSeconds) / public static Transition pushRight(float durationSeconds)
    public static Transition irisIn(float durationSeconds) / public static Transition irisOut(float durationSeconds)
    public static Transition blinds(float durationSeconds, int sliceCount)
    public static Transition squares(float durationSeconds, int sliceCount)
    public static Transition imageDissolve(String maskImagePath, float durationSeconds, float rampLength, boolean reverseMask)
    public static Transition multiple(Transition... subTransitions)
    public static Transition multiple(List<Transition> subTransitions)
    public static Transition parse(String expr)

    public Kind getKind()
    public float getDurationSeconds()
    public String getFadeColorHex()
    public float[] getFadeColorRgba()
    public String getMaskImagePath()
    public float getRampLength()
    public boolean isReverseMask()
    public int getSliceCount()
    public List<Transition> getSubTransitions()
    public Easing.EaseType getEaseType()
}
```

### `TransitionManager`
```java
public final class TransitionManager {
    public void startTransition(Transition transition)
    public void update(double dt)
    public void cancel()
    public boolean isActive()
    public float getProgress()
    public Transition getActiveTransition()
    public int getActiveStepIndex()
    public float getActiveStepProgress()
    public Transition getCurrentEffectiveTransition()

    public void initGl(int logicalW, int logicalH)
    public void captureFromFbo(Framebuffer src)
    public void renderComposite(Framebuffer toFbo, TextureManager texMgr, int targetWidth, int targetHeight)
    public void disposeGl()
    public boolean isGlInitialized()
    public Framebuffer getFromFramebuffer()
}
```

### `ParticleSystem`
```java
public final class ParticleSystem implements CustomDisplayable {
    public static final float DEFAULT_WIDTH = RenderPipeline2D.DEFAULT_LOGICAL_WIDTH;
    public static final float DEFAULT_HEIGHT = RenderPipeline2D.DEFAULT_LOGICAL_HEIGHT;

    public enum EmitterKind { SNOW_BLOSSOM, RAIN, CHERRY_BLOSSOM, SPARKS, DUST }

    public static final class Particle {
        public float x, y, vx, vy, rotation, angularVelocity, size, alpha, life, maxLife;
        public final float[] colorRgba;
        public float swayPhase, swayAmplitude, swayFrequency;

        public Particle(float x, float y, float vx, float vy, float rotation, float angularVelocity, float size, float alpha, float life, float maxLife, float[] colorRgba)
        public float getX() / public float getY() / public float getVx() / public float getVy()
        public float getRotation() / public float getAngularVelocity() / public float getSize()
        public float getAlpha() / public float getLife() / public float getMaxLife() / public float[] getColorRgba()
    }

    public static final class ParticleEmitter {
        public ParticleEmitter(EmitterKind kind, List<Particle> particles, float boundsWidth, float boundsHeight, float speed, float windX, Random random)
        public void update(double dt)
        public void render(RenderPipeline2D pipeline, Rect2f bounds, float alpha, ColorMatrix4f colorMatrix)
        public EmitterKind getKind() / public List<Particle> getParticles() / public int getParticleCount()
        public float getBoundsWidth() / public float getBoundsHeight() / public ParticleEmitter setBounds(float width, float height)
        public float getWindX() / public ParticleEmitter setWindX(float windX)
        public float getSpeed() / public ParticleEmitter setSpeed(float speed)
        public Texture getTexture() / public ParticleEmitter setTexture(Texture texture)
        public boolean isActive() / public ParticleEmitter setActive(boolean active)
    }

    public ParticleSystem()
    public ParticleSystem(float width, float height)

    public static ParticleEmitter snowBlossom(int count, float speed, float windX)
    public static ParticleEmitter snowBlossom(int count, float speed, float windX, Random random)
    public static ParticleEmitter rain(int count, float speed, float windX)
    public static ParticleEmitter rain(int count, float speed, float windX, Random random)
    public static ParticleEmitter cherryBlossoms(int count)
    public static ParticleEmitter cherryBlossoms(int count, Random random)
    public static ParticleEmitter sparks(int count)
    public static ParticleEmitter sparks(int count, Random random)
    public static ParticleEmitter dust(int count)
    public static ParticleEmitter dust(int count, Random random)

    public ParticleSystem addEmitter(ParticleEmitter emitter)
    public boolean removeEmitter(ParticleEmitter emitter)
    public void clearEmitters()
    public List<ParticleEmitter> getEmitters()
    public int getTotalParticleCount()
    @Override public void update(double dt)
    public void render(RenderPipeline2D pipeline)
    @Override public void render(RenderPipeline2D pipeline, Rect2f bounds, float alpha, ColorMatrix4f colorMatrix)
    @Override public float getPreferredWidth()
    @Override public float getPreferredHeight()
    public SpriteNode toSceneNode(String id)
    public SpriteNode attachToForeground(SceneGraph sceneGraph)
}
```

### `VideoPlayer`
```java
public final class VideoPlayer implements AutoCloseable {
    public enum State { STOPPED, PLAYING, PAUSED, ENDED }

    public interface FrameSource extends AutoCloseable {
        int getWidth();
        int getHeight();
        double getFps();
        byte[] nextRgbaFrame();
        void reset();
        @Override void close();
    }

    public static final class ProceduralTestFrameSource implements FrameSource {
        public ProceduralTestFrameSource(int width, int height, double fps)
        public ProceduralTestFrameSource(int width, int height, double fps, int totalFrames)
        @Override public int getWidth() / @Override public int getHeight() / @Override public double getFps()
        @Override public byte[] nextRgbaFrame() / @Override public void reset() / @Override public void close()
        public int getFrameIndex() / public int getTotalFrames()
    }

    public static final class FfmpegProcessFrameSource implements FrameSource {
        public FfmpegProcessFrameSource(Path videoPath)
        public FfmpegProcessFrameSource(Path videoPath, int width, int height, double fps)
        public FfmpegProcessFrameSource(Path videoPath, int width, int height, double fps, EngineLogger logger)
        public static boolean isFfmpegAvailable()
        @Override public int getWidth() / @Override public int getHeight() / @Override public double getFps()
        @Override public byte[] nextRgbaFrame() / @Override public void reset() / @Override public void close()
        public boolean isAvailable() / public String getErrorMessage()
    }

    public VideoPlayer()
    public VideoPlayer(boolean glEnabled)
    public VideoPlayer(EngineLogger logger, boolean glEnabled)

    public void play(Path videoPath)
    public void play(FrameSource source)
    public void pause()
    public void resume()
    public void stop()
    public void setLoop(boolean loop) / public boolean isLoop()
    public void update(double dt)
    @Override public void close()

    public Texture getCurrentTexture()
    public byte[] getLatestFrameRgba()
    public FrameSource getFrameSource()
    public State getState()
    public boolean isPlaying() / public boolean isPaused() / public boolean isStopped() / public boolean isEnded()
    public long getCurrentFrameIndex()
    public int getWidth() / public int getHeight() / public double getFps()
    public boolean isGlEnabled() / public void setGlEnabled(boolean glEnabled)
}
```

### `MovieSprite`
```java
public class MovieSprite extends SpriteNode implements AutoCloseable {
    public MovieSprite()
    public MovieSprite(String id)
    public MovieSprite(boolean glEnabled)
    public MovieSprite(String id, boolean glEnabled)
    public MovieSprite(String id, VideoPlayer videoPlayer)
    public MovieSprite(String id, String tag, VideoPlayer videoPlayer)

    public MovieSprite play(Path videoPath)
    public MovieSprite play(VideoPlayer.FrameSource source)
    public void pause()
    public void resume()
    public void stop()
    public MovieSprite setLoop(boolean loop)
    public boolean isLoop()
    @Override public void update(double dt)
    public VideoPlayer getVideoPlayer()
    @Override public void close()
}
```


### `ObjectMotionController`
Interaktive Bewegungs-, WASD-, 2D-Physik- und AABB-Kollisionssteuerung auf `SceneController`-Sprites:
```java
public final class ObjectMotionController {
    public static final Set<String> SUPPORTED_EASINGS;
    public record MotionInput(boolean up, boolean down, boolean left, boolean right, boolean jumpPressed) { ... }

    public ObjectMotionController(Map<String, SpriteNode> activeSprites)
    public void startMove(String tag, float x, float y, boolean relative, double durationSeconds, String easing)
    public void enableWasd(String tag, float speedPxPerSec, Float minX, Float minY, Float maxX, Float maxY, boolean normalizeDiagonal)
    public void disableWasd(String tag)
    public void enablePhysics(String tag, float gravityPxPerSec2, float jumpForcePxPerSec, float groundY)
    public void disablePhysics(String tag)
    public void registerCollisionTrigger(String tagA, String tagB, String targetLabel)
    public void setCollisionJumpListener(Consumer<String> listener)
    public List<String> update(double dt, MotionInput input)
    public boolean hasActiveWasd()
    public boolean hasActivePhysics()
    public boolean isGrounded(String tag)
}
```
- `SceneController` ergänzt `updateMotion(double dt, ObjectMotionController.MotionInput)` und `getMotionController()`.

### `DynamicTextureAtlas`
```java
public final class DynamicTextureAtlas implements AutoCloseable {
    public record AtlasRegion(String key, int x, int y, int width, int height, Rect2f uvRect) {}
    public record BatchStats(int spriteCount, int originalBindCount, int atlasBindCount, double bindReductionRatio) {}

    public DynamicTextureAtlas()
    public DynamicTextureAtlas(int initialSize, int maxSize, int paddingPx)
    public AtlasRegion pack(String key, int width, int height, byte[] straightRgba)
    public AtlasRegion packFromFile(String key, Path imagePath)
    public Optional<AtlasRegion> getRegion(String key)
    public boolean contains(String key)
    public BatchStats computeBatchStats(List<String> drawKeys)
    public void uploadToOpenGl()
    public int getRegionCount() / public int getAtlasWidth() / public int getAtlasHeight()
    public int getGlTextureId() / public boolean isDirty() / public byte[] getAtlasRgbaCopy()
    public void dispose() / @Override public void close()
}
```

### `AnimatedSpriteNode`
```java
public class AnimatedSpriteNode extends SpriteNode {
    public enum PlayMode { LOOP, PING_PONG, ONCE }

    public AnimatedSpriteNode(String id, int columns, int rows, float fps)
    public AnimatedSpriteNode(String id, int columns, int rows, float fps, PlayMode playMode)
    public AnimatedSpriteNode(String id, String tag, int columns, int rows, float fps, PlayMode playMode)
    @Override public void update(double dt)
    public Rect2f computeFrameUv(int frameIndex)
    public void reset()
    public int getColumns() / public int getRows() / public int getFrameCount() / public AnimatedSpriteNode setFrameCount(int frameCount)
    public float getFps() / public AnimatedSpriteNode setFps(float fps)
    public PlayMode getPlayMode() / public AnimatedSpriteNode setPlayMode(PlayMode playMode)
    public int getCurrentFrame() / public AnimatedSpriteNode setCurrentFrame(int frameIndex)
    public boolean isPaused() / public AnimatedSpriteNode setPaused(boolean paused) / public boolean isFinished()
}
```

### `WebpVp8lDecoder`
```java
public final class WebpVp8lDecoder {
    public record DecodedRgba(int width, int height, byte[] straightRgba) {}

    public static boolean isWebp(byte[] data)
    public static DecodedRgba decode(byte[] data)
}
```

### Ergänzte Wave-2a-APIs in bestehenden Grafikklassen
- `RenderPipeline2D`: `PostProcessConfig`, `pushScissor`, `popScissor`, `getActiveScissor`, `getScissorDepth`, `intersectScissorRects`, `toGlScissorBox`, `drawMsdfTextQuad`, `computeSoftShadowAlpha`, `computeGaussianWeights`, `computeBloomBrightness`, `setPostProcessConfig`, `getPostProcessConfig`, `setSceneBlurRadius`, `getSceneBlurRadius`, `setBloomConfig`, `isBloomEnabled`, `getLastFrameNodeBlurRadius`
- `SceneNode`: `setShadow`, `setShadowOffset`, `getShadowOffsetX`, `getShadowOffsetY`, `getShadowRadius`, `setShadowRadius`, `getShadowColorRgba`, `setShadowColorRgba`, `setShadowColorHex`, `hasShadow`, `getPremultipliedShadowColor`
- `TextureManager`: `DecodedImage`, `preloadAsync`, `preloadAllAsync`, `isPreloaded`, `getPreloadedCount`, `uploadPreloadedTextures` sowie WebP/VP8L-Dispatch in `readImageFileRgba`
- `FontAtlas`: `GlyphFieldMode`, `createSdfBuiltinFallback`, `fromTrueTypeBytesSdf`, `computeSdfFromCoverage`, `evaluateMsdfMedian`, `evaluateSdfCoverage`, `getFieldMode`, `getSdfSpreadPx`
- `StyledTextLayout`: Japanische Kinsoku-Shori-Umbruchregeln in `wrapAtoms` (`KINSOKU_NO_START` / `KINSOKU_NO_END`)

### Wave P2-4 3D-Pipeline & Hybrid-2D/3D-Klassen
- `Mesh3D`: Prozedurale Primitiven (`createPlane`, `createCube`, `createSphere`, `createCylinder`), `.obj`/`.mtl`-Parser (`parseObj`, `loadObj`), binärer `.glb`-Parser (`parseGlb`, `loadGlb`), `BoundingBox3D`, `BoundingSphere3D`, `SubMesh`, `computeTangents`, `computeSmoothNormals`, `MeshUploadQueue`.
- `Material3D`: `ShaderStyle { PBR, TOON, WATER, CUTOUT, DECAL }`, `defaultPbr`, `withOverrides`, `evaluateCookTorranceGgxNdf`, `evaluateToonQuantization`, `evaluateFresnelSchlick`, `resolveSharedAlbedoTexture`.
- `Camera3D`: `ProjectionMode { PERSPECTIVE, ORTHOGRAPHIC }`, `Ray3D`, `CameraRail`, `ShotPreset`, FOV `[50°, 110°]`, `moveTo`, `applyWasdInput` (`±89°` Pitch-Clamp, Strafe-Tilt-Toggle), Dialog-Fokus-Blend, quadratisches Trauma-Decay, `resolveSpringArmPosition`, Stereo-Eye-Matrizen, Frustum-AABB-Culling, `screenPointToRay`, `rayIntersectsAabbDistance`.
- `Scene3DWorld`: `Node3D` (inkl. LOD-Hysterese, Billboards, Collider, 2D-Surface-Attach), `Light3D`, `Fog3DConfig`, `Skydome3D`, `InspectItemOverlay`, `Minimap3DProjector`, `Scene3DSnapshot`, `Scene3DPreloader`, `selectTopNearestLights`, `getSortedDrawNodes`, `pickNode`, `moveWithWallCollision`, `computePlanarReflectionMatrix`, `getNode`, `reset`.
- `RenderPipeline3D`: OpenGL 3.3 Core (`implements IRenderPipeline`), `ShadowQuality`, `DebugViewMode`, `COMPOSITE_PASS_ORDER`, `GLOBAL_CAMERA_UBO_STD140_BYTES = 176`, `verifyStd140UboAlignment`, `validateCore3DShaders`, `computeInternalRenderSize`, `setFallbackTo2dBackdrop`, `getMode` -> `RenderMode.MODE_3D`.
