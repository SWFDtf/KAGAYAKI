# KAGAYAKI — Systemarchitektur

## 1. Übersicht & Leitprinzipien

**KAGAYAKI** (`de.swfdtf`) ist eine auf **Java 21** und **LWJGL 3** (`OpenGL 3.3 Core`, `GLFW`, `OpenAL`, `STB`) basierende Visual-Novel-Engine und Dual-Window-Autorenumgebung für `.vjava`-Skripte.

### Technologischer Stack (verifiziert in `build.gradle`)
* **Sprache & Toolchain**: Java 21 (`sourceCompatibility = JavaVersion.VERSION_21`, `targetCompatibility = JavaVersion.VERSION_21`)
* **Build-System**: Gradle (Groovy DSL) mit automatischer Plattform-/Architektur-Erkennung für LWJGL-Natives (`windows`, `windows-arm64`, `macos`, `macos-arm64`, `linux`, `linux-arm64`) und `-XstartOnFirstThread` auf macOS
* **Grafik, Fenster & Audio**: LWJGL `3.3.6` (`lwjgl`, `lwjgl-glfw`, `lwjgl-opengl`, `lwjgl-openal`, `lwjgl-stb`)
* **Mathematik & Serialisierung**: `org.joml:joml:1.10.8`, `com.google.code.gson:gson:2.11.0`
* **Testing**: JUnit Jupiter `6.0.0` (`useJUnitPlatform()`)

---

## 2. Dual-Window Studio vs. Standalone Runtime

Die Engine trennt Entwicklungsbetrieb und Auslieferung über `EngineMode`:

1. **`EngineMode.STUDIO_DUAL_WINDOW`** (Entwicklungs- & Autorenmodus, `de.swfdtf.Main` bzw. `--studio`):
   * **Fenster A (Studio-Werkzeug, `WindowManager.getStudioWindow()`)**: Projekt-Explorer, Vorlagen-Generator, Lint-Prüfer, Inspektoren, Konsolen-Log (`EngineLogger`) und Build-Export.
   * **Fenster B (Live-Ausführungsfenster, `WindowManager.getGameWindow()`)**: Laufende Visual Novel mit Live-Reload (`EngineAPI.requestLiveReload()`) und Diagnose-Overlays.
   * **Shared OpenGL Namespace**: `WindowManager.initDualWindows(...)` erzeugt zuerst Fenster B (Basis-Kontext) und anschließend Fenster A mit `share = gameWindow.getHandle()`, sodass Texturen, Buffer und Shader-Programme geteilt werden, während jedes `GlfwWindow` eigene `GLCapabilities`, VAOs und FBOs verwaltet.
   * **Entkopplung via `StudioBridge`**: Das Core-Paket kommuniziert mit der Studio-UI ausschließlich über das Interface `de.swfdtf.core.StudioBridge` (`init`, `update`, `render`, `onReload`, `dispose`). `EngineApplication` lädt `de.swfdtf.tools.StudioWindowController` dynamisch per Reflection (falls vorhanden) und fällt ansonsten sauber auf eine leere Studio-Basisfläche (`#161822`) zurück.

2. **`EngineMode.STANDALONE_GAME`** (Produktiver Export- & Spielmodus, `de.swfdtf.StandaloneMain` bzw. `--standalone`):
   * Startet ausschließlich **Fenster B** (`WindowManager.initStandaloneWindow(...)`) ohne Studio-Overhead.
   * Der Gradle-Task `packageStandalone` erzeugt `kagayaki-standalone-*.jar` unter `build/dist/` mit `Main-Class: de.swfdtf.StandaloneMain` und schließt `de/swfdtf/tools/StudioWindowController*` sowie `**/AGENT_CONTEXT.md` explizit aus.

---

## 3. Paketstruktur & Status (Meilenstein M1 abgeschlossen)

| Paket | Status | Verantwortung |
| :--- | :--- | :--- |
| `de.swfdtf` (`Main` / `StandaloneMain`) | **Implementiert (M1)** | `Main` (Standard `STUDIO_DUAL_WINDOW`, optional `--standalone`) und `StandaloneMain` (erzwingt `STANDALONE_GAME`) als CLI-Einstiegspunkte für `EngineApplication`. |
| `de.swfdtf.math` | **Implementiert (M1)** | `Vec2f`, `Rect2f`, `ColorMatrix4f` (Premultiplied Alpha), `Easing` (`EaseType` + `cubicBezier`), `Transform2D`, `ScreenShake` (`vpunch`/`hpunch`/`custom`). |
| `de.swfdtf.core` | **Implementiert (M1)** | `EngineMode`, `EngineConfig` (+ `Builder`, CLI-Parser), `ProjectDescriptor` (`project.vjava.json`), `EngineLogger` (Ringpuffer + Listener), `GameClock` (60 Hz Fixed-Timestep), `InputManager` (GLFW + Letterbox-Mapping + Gamepad), `EngineAPI` (Fassade + Service-Locator), `StudioBridge`, `EngineApplication` (+ `Updatable`). |
| `de.swfdtf.graphics` | **Implementiert (M1)** | `RenderMode`, `IRenderPipeline`, `RenderPipeline2D` (OpenGL 3.3 Core, Offscreen-`Framebuffer`, `ShaderProgram`, SDF-Rounded-Rects, Letterbox-Blit), `RenderPipeline3DStub`, `SceneGraph`, `SceneLayer`, `SceneNode`, `GlfwWindow`, `WindowManager`. |
| `de.swfdtf.git` | **Implementiert (M1)** | `GitController` (nicht-interaktive Git-CLI-Steuerung mit Commit-Policy-Validierung). |
| `de.swfdtf.parser` *(geplant)* | Geplant (M3) | Rekursiver `.vjava`-Scanner, einrückungsbasierter VN-Parser und In-Memory-Java-Compiler (`@->{ ... }<<`). |
| `de.swfdtf.state` *(geplant)* | Geplant (M4) | Deterministischer Rollback-Speicher (`RevertableMap`, `RevertableList`), Save/Load-System, `HistoryLog`. |
| `de.swfdtf.audio` *(geplant)* | Geplant (M5) | OpenAL-Audio-Mixer (`music`, `sound`, `voice`, `ambient`), Crossfades und RMS-Lip-Sync. |
| `de.swfdtf.ui` *(geplant)* | Geplant (M6) | Deklarative Screens, Layout-Container, Widgets, ADV/NVL/Sprechblasen-Modi. |
| `de.swfdtf.tools` *(geplant)* | Geplant (M7) | `StudioWindowController`, Vorlagen-Generator, Live-Regisseur, Lint und `.vpak`-Packager. |

---

## 4. Service-Entkopplung über `EngineAPI`

Um zyklische Paketabhängigkeiten zu vermeiden, stellt `EngineAPI` einen Thread-sicheren, typisierten Service-Locator (`registerService`, `getService`, `requireService`, `hasService`, `unregisterService`) sowie eine atomare Property-Map (`getProperty`, `setProperty`, `removeProperty`, `getPropertiesSnapshot`) bereit. `EngineApplication` registriert beim Start automatisch `SceneGraph`, sich selbst (`EngineApplication`) sowie bei `switchRenderPipeline(...)` die aktive `IRenderPipeline` (inklusive Property `"renderMode"`). Höhere Subsysteme (`state`, `audio`, `scene`, `scriptRuntime`) können sich analog registrieren, ohne dass `de.swfdtf.core` diese Pakete importieren muss.

---

## 5. Logische Auflösung, Letterboxing & Z-Order-Hierarchie

### Logische Referenzauflösung & Offscreen-Letterboxing
* Standardauflösung: **`1920 × 1080`** (`EngineConfig.DEFAULT_LOGICAL_WIDTH` × `EngineConfig.DEFAULT_LOGICAL_HEIGHT`, 16:9).
* `RenderPipeline2D` rendert den gesamten `SceneGraph` zunächst in einen logisch aufgelösten `RGBA8`-Offscreen-`Framebuffer` (`GL_LINEAR`, `GL_CLAMP_TO_EDGE`) mit orthographischer Projektion `ortho(0, logicalW, logicalH, 0, -1000, 1000)`.
* Bei `endFrame()` berechnet `RenderPipeline2D.computeLetterboxViewport(windowW, windowH, logicalW, logicalH)` das zentrierte, seitenverhältnistreue Zielrechteck und blittet den Offscreen-FBO über die Blueprint-Canvas-Farbe `#0d0e15` in den physischen Fenster-Framebuffer.
* `InputManager.updateLetterboxViewport(...)` / `InputManager.setViewport(...)` projiziert Fenster-Mauskoordinaten (`windowToLogicalX`, `windowToLogicalY`) exakt in den logischen Canvas-Raum; `EngineApplication` speist daraus zusätzlich `SceneGraph.setNormalizedMouse(nx, ny)` für 2.5D-Parallax.

### Z-Order-Ebenen-Architektur (`SceneGraph` & `SceneLayer`)
`SceneGraph` registriert bei der Konstruktion automatisch alle 8 Standard-Ebenen (`SceneLayer.Z_*` / `SceneGraph.LAYER_*`):

| Z-Wert | Konstante (`SceneLayer` / `SceneGraph`) | Inhalt |
| :---: | :--- | :--- |
| `-100` | `Z_BACKGROUND` / `LAYER_BACKGROUND` | Standbilder, Videos, Panoramen |
| `0` | `Z_MASTER` / `LAYER_MASTER` | 2D-Sprites, `LayeredImage` (architektonisch vorbereitet für 3D-Bühne) |
| `100` | `Z_FOREGROUND` / `LAYER_FOREGROUND` | Partikel, Wetter, Beleuchtungs-Vignetten |
| `200` | `Z_SCREENS` / `LAYER_SCREENS` | ADV/NVL/Sprechblasen-Dialogfenster, Quick-Menu, HUDs |
| `300` | `Z_OVERLAY` / `LAYER_OVERLAY` | Auswahlmenüs (`menu`), Texteingaben (`input`), Popups |
| `400` | `Z_MODAL` / `LAYER_MODAL` | Hauptmenü, Save/Load, Einstellungen, Controls, About, Historie, Galerie |
| `500` | `Z_TOAST` / `LAYER_TOAST` | Achievement-Toasts, Skip-/Auto-HUD |
| `1000` | `Z_INSPECTOR` / `LAYER_INSPECTOR` | Mauszeiger & Live-Inspektoren |

---

## 6. Render-Pipeline-Architektur (`IRenderPipeline`)

`IRenderPipeline` abstrahiert das Render-Backend über einen einheitlichen Lebenszyklus (`init`, `beginFrame`, `renderSceneGraph`, `endFrame`, `getMode`, `dispose`), sodass `EngineApplication.switchRenderPipeline(RenderMode)` zur Laufzeit zwischen `MODE_2D` und `MODE_3D` umschalten kann:

1. **`RenderPipeline2D` (`RenderMode.MODE_2D`)**:
   * **Premultiplied Alpha**: Verwendet `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)` und berechnet `fillPremul`/`borderPremul` über hierarchisch vererbte Alpha-Werte sowie verkettete `ColorMatrix4f`-Instanzen.
   * **SDF-Geometrie & Sprites**: Der Quad-Fragment-Shader unterstützt texturierte Quads (inkl. `Transform2D.crop`-UV-Subrechtecken und negativen `xzoom`/`yzoom`-Spiegelungen) sowie SDF-kantengeglättete abgerundete Rechtecke mit optionalem Rahmen (`cornerRadius`, `borderThickness`, `borderColor`).
   * **Szenen-Effekte**: Wendet `SceneGraph.getScreenShake()` ausschließlich auf Weltebenen mit `zOrder < SceneLayer.Z_SCREENS (200)` an und kombiniert Ebenen-Parallax (`SceneLayer.getParallaxFactor()`) mit Knoten-Parallax (`Transform2D.getParallaxX/Y()`).
   * **Custom Rendering**: Jeder `SceneNode` erlaubt benutzerdefiniertes Rendering via `onCustomRender(RenderPipeline2D)` oder `setCustomRenderer(...)`.

2. **`RenderPipeline3DStub` (`RenderMode.MODE_3D`)**:
   * Bereitet perspektivische Projektions- (`DEFAULT_FOV_DEGREES = 75.0f`, `Z_NEAR = 100.0f`, `Z_FAR = 100000.0f`) und LookAt-Kameramatrizen mit `Z_camera = (logicalWidth / 2) / tan(fov / 2)` vor.
   * Loggt bei Initialisierung und erstem `renderSceneGraph`-Aufruf kontrollierte Warnungen über `EngineLogger` und räumt optional den OpenGL-Buffer, ohne im M1-Ausbau 3D-Geometrie zu zeichnen.

---

## 7. Laufzeit-Koordination (`EngineApplication` & `GlfwWindow`)

* **`EngineApplication`**: Implementiert `AutoCloseable`, verwaltet `EngineAPI`, `SceneGraph`, `WindowManager`, registrierte `EngineApplication.Updatable`-Hooks, die aktive `IRenderPipeline` sowie die optionale `StudioBridge`.
* **Deterministische Schleife (`run()` / `stepOnce(double)`)**: Pollt Eingabe- und Fensterereignisse, verarbeitet `api.consumeLiveReloadRequested()`, führt `GameClock.advanceFrame(realDelta)` in deterministischen `1/60 s`-Schritten für `SceneGraph.update`, `Updatable.update` und `StudioBridge.update` aus und rendert anschließend Game-Fenster B und (im Studio-Modus) Studio-Fenster A.
* **Test- & Automatisierungsmodi**: `--headless` überspringt GLFW-Fenstererzeugung und OpenGL-Kontexte für deterministische JUnit-Tests über `stepOnce(...)`; `--smoke-test` beendet `run()` sauber nach `5` Frames.
* **`GlfwWindow`**: Kapselt das native GLFW-Fenster-Handle, unterscheidet zwischen logischer Fenstergröße (Screen Coordinates) und physischer Framebuffer-Pixelgröße (HiDPI/Retina) und schaltet `GLCapabilities` sauber pro Fensterkontext um.
