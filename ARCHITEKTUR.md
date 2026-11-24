# KAGAYAKI — Systemarchitektur

## 1. Übersicht & Leitprinzipien

**KAGAYAKI** (`de.swfdtf`) ist eine auf **Java 21** und **LWJGL 3** (`OpenGL 3.3 Core`, `GLFW`, `OpenAL`, `STB`) basierende Visual-Novel-Engine und Dual-Window-Autorenumgebung für `.vjava`-Skripte. Das gesamte Grafik- und Textsubsystem verzichtet vollständig auf `java.awt` / Swing.

### Technologischer Stack (verifiziert in `build.gradle`)
* **Sprache & Toolchain**: Java 21 (`sourceCompatibility = JavaVersion.VERSION_21`, `targetCompatibility = JavaVersion.VERSION_21`)
* **Build-System**: Gradle (Groovy DSL) mit automatischer Plattform-/Architektur-Erkennung für LWJGL-Natives (`windows`, `windows-arm64`, `macos`, `macos-arm64`, `linux`, `linux-arm64`) und `-XstartOnFirstThread` auf macOS
* **Grafik, Fenster & Audio**: LWJGL `3.3.6` (`lwjgl`, `lwjgl-glfw`, `lwjgl-opengl`, `lwjgl-openal`, `lwjgl-stb`)
* **Mathematik & Serialisierung**: `org.joml:joml:1.10.8`, `com.google.code.gson:gson:2.11.0`
* **Testing**: JUnit Jupiter `6.0.0` (`useJUnitPlatform()`) — verifizierte M1–M6-Basis (**157** Tests in **43** Suites in `audio`, `core`, `git`, `graphics`, `math`, `parser`, `state`, `ui`) plus die M7-Tooling- und End-to-End-Suites unter `src/test/java/de/swfdtf/tools/` (`DirectorAndInspectorsTest`, `ToolsPackagerAndTemplateTest`, `EndToEndEngineTest`)

---

## 2. Dual-Window Studio vs. Standalone Runtime

Die Engine trennt Entwicklungsbetrieb und Auslieferung über `EngineMode`:

1. **`EngineMode.STUDIO_DUAL_WINDOW`** (Entwicklungs- & Autorenmodus, `de.swfdtf.Main` bzw. `--studio`, optional `--create-template <dir>`):
   * **Fenster A (Studio-Werkzeug, `WindowManager.getStudioWindow()`)**: Projekt-Explorer, Starter-Vorlagen-Generator (`ProjectTemplateGenerator`), Live-Lint-Prüfer (`VJavaLinter`), State-Variablen-Inspektor, Konsolen-Log (`EngineLogger`), 2D/3D-RenderMode-Umschalter, Live-Reload und Standalone-Exporter (`StandaloneBuilder`).
   * **Fenster B (Live-Ausführungsfenster, `WindowManager.getGameWindow()`)**: Laufende Visual Novel über `GameRuntimeCoordinator` (`GameHost`) mit Live-Reload (`Shift+R` / `EngineAPI.requestLiveReload()`), `LiveDirectorOverlay` (`D`) und `DeveloperInspectors` (`Shift+I`, `Shift+C`, `Shift+O`).
   * **Shared OpenGL Namespace**: `WindowManager.initDualWindows(...)` erzeugt zuerst Fenster B (Basis-Kontext) und anschließend Fenster A mit `share = gameWindow.getHandle()`, sodass Texturen, Buffer und Shader-Programme geteilt werden, während jedes `GlfwWindow` eigene `GLCapabilities`, VAOs und FBOs verwaltet.
   * **Entkopplung via `StudioBridge` & `GameHost`**: Das Core-Paket kommuniziert mit der Studio-UI ausschließlich über `de.swfdtf.core.StudioBridge` (`init`, `update`, `render`, `onReload`, `dispose`) und mit der Spiel-Laufzeit über `de.swfdtf.core.GameHost` (`init`, `update`, `renderUi`, `onReload`, `dispose`). `EngineApplication` lädt `de.swfdtf.tools.StudioWindowController` (im Studio-Modus) sowie `de.swfdtf.ui.GameRuntimeCoordinator` (sofern `project.vjava.json` existiert oder ein `GameHost` übergeben wurde) dynamisch per Reflection und fällt ansonsten sauber auf eine leere Studio-Basisfläche (`#161822`) zurück.

2. **`EngineMode.STANDALONE_GAME`** (Produktiver Export- & Spielmodus, `de.swfdtf.StandaloneMain` bzw. `--standalone`):
   * Startet ausschließlich **Fenster B** (`WindowManager.initStandaloneWindow(...)`) über `GameRuntimeCoordinator` ohne Studio-Overhead (`StudioWindowController`, `LiveDirectorOverlay` und `DeveloperInspectors` werden nicht geladen).
   * Der Gradle-Task `packageStandalone` erzeugt `kagayaki-standalone-*.jar` unter `build/dist/` mit `Main-Class: de.swfdtf.StandaloneMain` und schließt `de/swfdtf/tools/StudioWindowController*` sowie `**/AGENT_CONTEXT.md` explizit aus.
   * `StandaloneBuilder` schnürt lint-geprüfte Projekte in ein kompaktes Export-Bundle aus `game.vpak` (`VpakArchiver` mit Deflate + CRC32), `run_game.sh` / `run_game.bat`, optionalem `kagayaki-standalone.jar` und `standalone-packaging.json` (`jlink`/`jpackage`-Metadaten).

---

## 3. Paketstruktur & Status (Meilenstein M7 abgeschlossen — Gesamtlieferung)

| Paket | Status | Verantwortung |
| :--- | :--- | :--- |
| `de.swfdtf` (`Main` / `StandaloneMain`) | **Implementiert (M1 + M7)** | `Main` (Standard `STUDIO_DUAL_WINDOW`, optional `--standalone`, `--create-template <dir>`) und `StandaloneMain` (erzwingt `STANDALONE_GAME`) als CLI-Einstiegspunkte für `EngineApplication`. |
| `de.swfdtf.math` | **Implementiert (M1)** | `Vec2f`, `Rect2f`, `ColorMatrix4f` (Premultiplied Alpha), `Easing` (`EaseType` + `cubicBezier`), `Transform2D`, `ScreenShake` (`vpunch`/`hpunch`/`custom`). |
| `de.swfdtf.core` | **Implementiert (M1 + M7)** | `EngineMode`, `EngineConfig` (+ `Builder`, CLI-Parser), `ProjectDescriptor` (`project.vjava.json`), `EngineLogger` (Ringpuffer + Listener), `GameClock` (60 Hz Fixed-Timestep), `InputManager` (GLFW + Letterbox-Mapping + Gamepad), `EngineAPI` (Fassade + Service-Locator), `StudioBridge`, `GameHost`, `EngineApplication` (+ `Updatable`). |
| `de.swfdtf.graphics` | **Implementiert (M1 + M2 + M3 + M5 + M6)** | `RenderMode`, `IRenderPipeline`, `RenderPipeline2D` (OpenGL 3.3 Core, Offscreen-`Framebuffer`, `ShaderProgram`, SDF-Rounded-Rects, Pivot-Rotation, Letterbox-Blit), `RenderPipeline3DStub`, `SceneGraph`, `SceneLayer`, `SceneNode`, `SpriteNode`, `LayeredImage`, `SceneController` (+ `StageEntry`, `StageSnapshot`), `CustomDisplayable`, `GlfwWindow`, `WindowManager`, `Texture`, `TextureManager` (`STBImage`/`STBImageWrite`, Fallback-Schraffur), `NineSliceDrawable`, `TextTagParser`, `FontAtlas` (`STBTruetype` + CPU-Fallback), `FontManager`, `StyledTextLayout` (+ `TypewriterController`), `ATLTransform`, `AnimationEngine`, `Transition`, `TransitionManager`, `ParticleSystem`, `VideoPlayer` und `MovieSprite`. |
| `de.swfdtf.git` | **Implementiert (M1)** | `GitController` (nicht-interaktive Git-CLI-Steuerung mit Commit-Policy-Validierung). |
| `de.swfdtf.parser` | **Implementiert (M3 + M4 + M6)** | `VJavaLexer`, `VJavaParser`, `VJavaAst`, `VJavaParseException`, `ProjectScanner`, `JavaBlockCompiler` (`VJavaUserTypes` + `VJavaSnippet_<i>` via `javax.tools.JavaCompiler`), `VJavaExecutable`, `VJavaCompileException`, `ConditionEvaluator`, `ScriptRuntime` und `ScriptSession`. |
| `de.swfdtf.state` | **Implementiert (M3 + M4)** | Verträge `StateStore` / `PersistentAccess` / `Revertable`, In-Memory-Implementierungen (`SimpleStateStore`, `SimplePersistentAccess`), deterministischer Rollback-Speicher (`RevertableMap`, `RevertableList`, `RevertableStateStore`), 4-Tier-Persistenz (`SessionState`, `PersistentState`, `PreferencesState`), `HistoryLog`, `SaveValueCodec`, `SaveSlotData`, `SaveManager`, Meta-Manager (`AchievementManager`, `GalleryManager`, `EndingsManager`) und `LocalizationManager`. |
| `de.swfdtf.audio` | **Implementiert (M5)** | `AudioDsp` (`BiquadFilter`, RMS, DFT-Spektrumbänder, Goertzel), `AudioDecoder` (`STBVorbis`-OGG, WAV-Codec, Ton-/Akkord-/Bleep-Synthese), `AudioChannel` (Crossfade, Queue, Filter, `ChannelSnapshot`), `AudioEngine` (OpenAL + Headless-Virtual-Mixer, `ScriptRuntime.AudioBridge`, `tl/<lang>/`-Auflösung), `LipSyncAnalyzer` (`MouthShape`) und `TextToSpeechService` (Virtual-Thread-Self-Voicing). |
| `de.swfdtf.ui` | **Implementiert (M6 + M7)** | `ThemePalette`, `UiNode`, `Screen`, `ScreenManager`, 8 Layout-Container (`VBox`, `HBox`, `Grid`, `Fixed`, `Frame`, `WindowBox`, `Viewport`, `VpGrid`), 13 Widgets (`TextWidget`, `TextButton`, `ImageButton`, `Hotspot`, `Hotbar`, `Imagemap`, `Bar`, `VBar`, `Slider`, `TextInput`, `DragItem`, `DragGroup`, `CustomDisplayableWidget`), `ScreenAstBuilder`, 3 Dialogmodi (`AdvDialogueScreen`, `NvlDialogueScreen`, `BubbleDialogueScreen`), `QuickMenuScreen`, `ChoiceMenuScreen`, `InputOverlayScreen`, alle 10 System-/Meta-Screens, `NotificationToastOverlay` sowie `GameRuntimeCoordinator` (`implements GameHost`). |
| `de.swfdtf.tools` | **Implementiert (M7)** | `ProjectTemplateGenerator`, `VJavaLinter` (`Severity`, `LintIssue`, `LintReport`), `VpakArchiver` (`VpakEntryInfo`, `VpakArchive`), `StandaloneBuilder` (`BuildResult`), `LiveDirectorOverlay`, `DeveloperInspectors` (`StyleInspectionResult`, `PickedCoordinate`, `ConsoleExecutionResult`) und `StudioWindowController` (`implements StudioBridge`). |

---

## 4. Service-Entkopplung über `EngineAPI`

Um zyklische Paketabhängigkeiten zu vermeiden, stellt `EngineAPI` einen Thread-sicheren, typisierten Service-Locator (`registerService`, `getService`, `requireService`, `hasService`, `unregisterService`) sowie eine atomare Property-Map (`getProperty`, `setProperty`, `removeProperty`, `getPropertiesSnapshot`) bereit. `EngineApplication` registriert beim Start automatisch `SceneGraph`, sich selbst (`EngineApplication`), einen aktiven `GameHost` sowie bei `switchRenderPipeline(...)` die aktive `IRenderPipeline` (inklusive Property `"renderMode"`). `GameRuntimeCoordinator` registriert `TextureManager`, `FontManager`, `AudioEngine`, `LipSyncAnalyzer`, `TextToSpeechService`, `AnimationEngine`, `TransitionManager` und `ScreenManager` (sowie im Studio-Modus `LiveDirectorOverlay` unter `Screen.class`), während `StudioWindowController` sich unter `StudioWindowController.class` einträgt. `ScriptRuntime` registriert bei Konstruktion automatisch `StateStore`, `PersistentAccess`, `SceneController` und sich selbst (`ScriptRuntime`), während `ScriptSession` zusätzlich alle M4-Subsysteme (`RevertableStateStore`, `PersistentState`, `PreferencesState`, `SessionState`, `HistoryLog`, `SaveManager`, `LocalizationManager`, `AchievementManager`, `GalleryManager`, `EndingsManager` und `ScriptSession`) im Service-Locator einträgt, ohne dass `de.swfdtf.core` diese Pakete importieren muss.

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

## 6. Render-Pipeline-, Textur- & Displayable-Architektur

### `IRenderPipeline` (`RenderPipeline2D` & `RenderPipeline3DStub`)
`IRenderPipeline` abstrahiert das Render-Backend über einen einheitlichen Lebenszyklus (`init`, `beginFrame`, `renderSceneGraph`, `endFrame`, `getMode`, `dispose`), sodass `EngineApplication.switchRenderPipeline(RenderMode)` zur Laufzeit zwischen `MODE_2D` und `MODE_3D` umschalten kann:

1. **`RenderPipeline2D` (`RenderMode.MODE_2D`)**:
   * **Premultiplied Alpha**: Verwendet `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)` und berechnet `fillPremul`/`borderPremul` über hierarchisch vererbte Alpha-Werte sowie verkettete `ColorMatrix4f`-Instanzen.
   * **SDF-Geometrie, Sprites & Pivot-Rotation**: Der Quad-Fragment-Shader unterstützt texturierte Quads (inkl. `Transform2D.crop`-UV-Subrechtecken, negativen `xzoom`/`yzoom`-Spiegelungen und Pivot-Rotation via `drawTexturedQuadPivot`) sowie SDF-kantengeglättete abgerundete Rechtecke mit optionalem Rahmen (`cornerRadius`, `borderThickness`, `borderColor`).
   * **Szenen-Effekte**: Wendet `SceneGraph.getScreenShake()` ausschließlich auf Weltebenen mit `zOrder < SceneLayer.Z_SCREENS (200)` an und kombiniert Ebenen-Parallax (`SceneLayer.getParallaxFactor()`) mit Knoten-Parallax (`Transform2D.getParallaxX/Y()`).
   * **Custom Rendering & Context-Exposure**: Während `onCustomRender(RenderPipeline2D)` stellt die Pipeline aufgelöste `currentRenderBounds`, `currentEffectiveAlpha`, `currentEffectiveColorMatrix`, `currentRotationDegrees` und `currentAnchorX/Y` bereit. `SceneNode.shouldDrawStandardQuad()` steuert, ob vorab ein Standard-Quad gezeichnet wird.

2. **`RenderPipeline3DStub` (`RenderMode.MODE_3D`)**:
   * Bereitet perspektivische Projektions- (`DEFAULT_FOV_DEGREES = 75.0f`, `Z_NEAR = 100.0f`, `Z_FAR = 100000.0f`) und LookAt-Kameramatrizen mit `Z_camera = (logicalWidth / 2) / tan(fov / 2)` vor.
   * Loggt bei Initialisierung und erstem `renderSceneGraph`-Aufruf kontrollierte Warnungen über `EngineLogger`, ohne im aktuellen Ausbau 3D-Geometrie zu zeichnen.

### Reine STB-Texturpipeline, 9-Slice & Erweiterbare Displayables (M2)
* **`Texture` & `TextureManager`**: Dekodiert Bilddateien (PNG, JPG, BMP, TGA) über `STBImage.stbi_load_from_memory`, konvertiert Straight-RGBA8 mittels `Texture.premultiplyRgba` in Premultiplied-RGBA8 und lädt GPU-Texturen mit `GL_LINEAR` + `GL_CLAMP_TO_EDGE` hoch (verhindert dunkle Halos an Alpha-Kanten). `TextureManager.writePng` schreibt PNGs via `STBImageWrite`. Fehlende oder defekte Assets führen niemals zum Absturz: `EngineLogger` protokolliert eine Warnung und liefert eine gecachte `128×128`-Fallback-Textur (`#FF00FF`/`#1A0A1E`-Diagonalschraffur mit 2-px-Rahmen, `isFallback() == true`).
* **`NineSliceDrawable`**: Teilt Dialograhmen/UI-Panels in 9 überlappungsfreie `SliceQuad`-Zonen (4 feste Ecken, 4 einachsig skalierte Kanten, 1 Zentrum), skaliert Ecken bei zu kleinen Zielmaßen proportional herunter und unterstützt Pivot-Rotation über `drawTexturedQuadPivot`.
* **`SpriteNode` & `CustomDisplayable`**: `SpriteNode` erweitert `SceneNode` um VN-Metadaten (`imageTag`, `attributes`, `assetPath`) sowie direkte Bindung an `Texture`, `NineSliceDrawable` und `CustomDisplayable` (`update(double)`, `render(RenderPipeline2D, Rect2f, float, ColorMatrix4f)`, `getPreferredWidth/Height()`).

---

## 7. Text-, Typografie- & Rich-Text-Pipeline (M2)

* **`TextTagParser`**: Zweistufiger Parser (`interpolateVariables` für `[var]`-Expansion und `[[`-Escaping; `parse` für `{{`-Escaping, geschachtelte Style-Stacks, Flow-Control-Cues, Furigana, Inline-Bilder und Echtzeit-Effekte). Unterstützt `{b}`, `{i}`, `{u}`, `{s}`, `{sc}`, `{plain}`, `{color=#...}`, `{alpha=...}`, `{size=...}` (absolut und `+`/`-` relativ), `{font=...}`, `{k=...}`, `{cps=...}`, `{w}`/`{w=t}`, `{p}`/`{p=t}`, `{nw}`, `{fast}`, `{rb}...{/rb}{rt}...{/rt}`, `{image=...}`, `{a=...}...{/a}`, `{wave}`, `{jitter}`/`{shake}` sowie `{gradient=c1,c2}`.
* **`FontAtlas` & `FontManager`**: `FontAtlas` rasterisiert TrueType/OpenType-Fonts via `STBTruetype` dynamisch in wachsende Premultiplied-`RGBA8`-Atlanten (Shelf-Packing bis `4096×4096`, `GL_LINEAR`, `GL_CLAMP_TO_EDGE`, Kerning-Cache) und besitzt einen autarken CPU-Bitmap-Fallback (inkl. `äöüÄÖÜß`, Typografie-Zeichen und CJK-Boxen) für Headless-Tests und fehlende Fonts. `FontManager` verwaltet Font-Registrierungen (Bytes, Datei, Asset-Root, Klassenpfad-Ressource, optionale OS-Font-Erkennung) sowie globale Barrierefreiheit (`dyslexiaFriendlyMode` mit OpenDyslexic-/Verdana-Priorisierung, `+1.5 px` Tracking, `1.25×` Zeilenabstand und `globalTextScale` in `[0.5, 3.0]`).
* **`StyledTextLayout` & `TypewriterController`**: Führt Wortumbruch (inkl. CJK-Umbruch), per-Zeichen-Farbverläufe, Underline/Strikethrough, Drop-Shadow/4-Wege-Outline, zentrierte Furigana-Positionierung (`RubyLayout`), `InlineImageLayout`-Slots (`\uFFFC`) und `HyperlinkHitbox`-Abfragen aus. `StyledTextLayout.TypewriterController` steuert die deterministische Zeichen-für-Zeichen-Enthüllung inklusive `{cps=...}`, `{fast}`-Sofortpräfix, `{w}`/`{w=t}`/`{p}`/`{p=t}`-Wartezuständen, `clickOrAdvance()` und `{nw}`-Auto-Advance.

---

## 8. `.vjava`-Skript-Pipeline, In-Memory-Compiler, Bühne & State (M3)

### Projekt-Scanning, Lexing & Parsing (`de.swfdtf.parser`)
* **`ProjectScanner`**: Durchsucht `projectRoot` rekursiv (unter Auslassung von `.git`, `build`, `.gradle`, `saves`, `out`, `dist`), lädt `project.vjava.json` (`ProjectDescriptor`), indiziert `assets/` und führt alle `*.vjava`-Dateien in deterministischer lexikografischer Pfadreihenfolge zu `VJavaAst.ProjectAst` zusammen (Last-Wins-Überschreibung mit Warnungen in `ProjectAst.warnings()`).
* **`VJavaLexer` & `VJavaParser`**: Einrückungsbewusster Lexer (`INDENT`/`DEDENT`-Stack, `4` Spalten/Tab, `#`/`//`-Kommentare, präzise Trennung von `@->{ ... }<<` unter Beachtung von Java-Strings, Textblöcken und Kommentaren) und rekursiv-absteigender AST-Parser für `define`, `default`, `$`, `image`, `layeredimage`, `transform` (ATL), `screen`, `translate`, `label` sowie alle 21 `VJavaAst.Statement`-Typen. Fehler liefern `VJavaParseException` mit Datei- und Zeilenangabe.

### In-Memory-Java-21-Kompilierung (`JavaBlockCompiler` & `VJavaExecutable`)
* Nutzt `javax.tools.ToolProvider.getSystemJavaCompiler()` mit `ForwardingJavaFileManager` und eigenem `InMemoryClassLoader` (ohne temporäre Dateien).
* Zerlegt jeden `@->{ ... }<<`-Block in `import`-Direktiven, benutzerdefinierte Typen (`class`, `interface`, `record`, `enum`, `@interface`), statische Hilfsmember/Initialisierer und ausführbare Snippet-Statements.
* Alle Typen und statischen Helfer werden als `public static` in `de.swfdtf.vjava.gen.VJavaUserTypes` vorab kompiliert; jedes ausführbare Snippet wird als `de.swfdtf.vjava.gen.VJavaSnippet_<i> implements VJavaExecutable` generiert und erhält direkten Zugriff auf `state` (`StateStore`), `persistent` (`PersistentAccess`), `engine` (`EngineAPI`) und `scene` (`SceneController`).
* Kompilierungsfehler werden über `VJavaCompileException` / `CompileDiagnostic` exakt auf Block-ID, `.vjava`-Dateipfad und Ursprungszeile zurückgemappt.

### Ausdrucks-Evaluator, Bühne & State-Machine (`ConditionEvaluator`, `SceneController`, `ScriptRuntime`, `de.swfdtf.state`)
* **`ConditionEvaluator`**: Rekursiv-absteigender Tokenizer/Parser/Evaluator für arithmetische, relationale, logische (`&&`/`||`/`!`, `and`/`or`/`not`) und ternäre Ausdrücke, Feld-/Record-/Getter-Zugriffe, Array-/List-/Map-Indexierung und Methodenaufrufe auf `state`, `persistent`, `Math` und benutzerdefinierten `VJavaUserTypes`-Objekten.
* **`SceneController` (`de.swfdtf.graphics`)**: Steuert `LAYER_BACKGROUND` und `LAYER_MASTER` des `SceneGraph`, löst `image`/`layeredimage`-Definitionen sowie eingebaute Positionen (`left`, `center`, `truecenter`, `right`, `offscreenleft`, `offscreenright`, `reset`) und benannte `Transform2D`-Presets auf, bewahrt bei wiederholtem `show <tag>` ohne `at` die bestehende Position und liefert `StageSnapshot` für Rollback/Save.
* **`StateStore` & `PersistentAccess` (`de.swfdtf.state`)**: Definieren die stabilen M3/M4-Verträge (leichtgewichtige In-Memory-Basis `SimpleStateStore` / `SimplePersistentAccess`, Produktivbetrieb über `RevertableStateStore` und `PersistentState`).
* **`ScriptRuntime` & `ScriptSession`**: `ScriptRuntime` ist ein nicht-blockierender State-Machine-Interpreter (`READY`, `WAITING_DIALOGUE`, `WAITING_MENU`, `WAITING_INPUT`, `PAUSING`, `ENDED`) mit `[var]`-Interpolation (`TextTagParser`), NVL-Puffer, Call-Stack für `call`/`return` (`_call_args`/`_return`), zeitgesteuerten `menu`-Timeouts und `pause`-Countdowns (`update(double dt)`), `AudioBridge`-Integration, `LocalizationManager`-Anbindung, `ExecutionSnapshot` (`snapshotExecution`/`restoreExecution`), `SavedScriptPosition` (`toSavedPosition`/`restoreSavedPosition`) und `setCheckpointListener`. `ScriptSession` verdrahtet `ScriptRuntime` mit allen M4-Tier-Managern.

---

## 9. 4-Tier-State, Deterministisches Rollback, Save/Load, Lokalisierung & Meta-Progression (M4)

### 9.1 4-Tier-Persistenzmodell (`de.swfdtf.state`)
1. **Tier 1 — Transient Runtime Session (`SessionState`)**: Verfolgt `sessionStartTime`, akkumulierte `playtimeSeconds`, `activeSaveSlotId`, `lastAutoSaveTime` und transiente Session-Flags; wird nie direkt als Save-Slot geschrieben.
2. **Tier 2 — Deterministischer Rollback- & Spielstand-Speicher (`RevertableStateStore`, `HistoryLog`, `SaveManager`)**:
   * `Revertable<S>`, `RevertableMap<K, V>` und `RevertableList<E>` isolieren verschachtelte Collections, Arrays, `Transform2D` und `Serializable`-Objekte zyklenfest per Deep-Clone (`RevertableMap.cloneValue`) und schützen nicht-serialisierbare `@->{ ... }<<`-POJOs per Referenz-Erhalt.
   * `RevertableStateStore` zeichnet an jedem Interaktionspunkt (`WAITING_DIALOGUE`, `WAITING_MENU`, `WAITING_INPUT`) unveränderliche `Checkpoint`-Records (Variablen + `ScriptRuntime.ExecutionSnapshot` + `SceneController.StageSnapshot`) auf, bewahrt den Roll-Forward-Pfad bei unverändertem Replay, schneidet veraltete Future-Checkpoints bei Verzweigung ab (Branch-Trimming) und setzt `block_rollback`-Barrieren (`blockRollback()`).
   * `HistoryLog` speichert bis zu `500` (`DEFAULT_MAX_ENTRIES`) `HistoryEntry`-Zeilen mit `checkpointId`-Korrelation für direkten Jump-Back (`ScriptSession.jumpToHistoryEntry`) und `truncateAfterCheckpoint(...)`.
   * `SaveManager` persistiert `SaveSlotData` atomar (Temp-Datei + `ATOMIC_MOVE` / `REPLACE_EXISTING`) als GZIP-komprimiertes JSON (`saves/<slotId>.ksav`) inklusive LWJGL-STB-PNG-Thumbnails (`encodeRgbaToPng`), Seitenabfrage (`listPageSlots`), Quick-Save (`quick`) und rotierendem Auto-Save (`auto_1`..`auto_3`). `SaveValueCodec` serialisiert zusätzlich Java-Zahltypen, `Instant`, `UUID`, `Enum`, `Transform2D`, `ColorMatrix4f`, `Rect2f` sowie beliebige `@->{ ... }<<`-`VJavaUserTypes`-Klassen via `__vjavaClass`-Typdiskriminator und reflektive Konstruktor-/Feldrekonstruktion.
3. **Tier 3 — Spielstandübergreifende Persistenz (`PersistentState`)**:
   * Implementiert `PersistentAccess` und speichert globale Variablen, `readLines`, `unlockedCgs`, `unlockedTracks`, `unlockedEndings` sowie `unlockedAchievements` atomar in `saves/persistent.dat` (GZIP-JSON).
   * Darauf aufbauend verwalten `AchievementManager` (mit `AchievementDef`, Deduplizierung und `AchievementToast`-Queue), `GalleryManager` (`GalleryEntry` mit Multi-Variations-Pfaden) und `EndingsManager` (`EndingDef` mit `parentEndingId`-Routenbaum und Completion-Prozentwert) den Meta-Fortschritt.
4. **Tier 4 — Benutzereinstellungen & Lokalisierung (`PreferencesState`, `LocalizationManager`)**:
   * `PreferencesState` speichert Audio-Pegel, Textgeschwindigkeit, Auto-Forward-Delay, Skip-Optionen, Render-Modus, Sprache, Dialogmodus und Barrierefreiheit menschenlesbar in `saves/preferences.json`.
   * `LocalizationManager` indiziert alle `VJavaAst.TranslateBlock`-Einträge aus `ProjectAst`, schaltet Sprachen zur Laufzeit ohne Neustart um (`setLanguage` + Listener), übersetzt Dialoge (`translateDialogue`) sowie UI-/Menütexte (`translateUi`), löst sprachspezifische Assets unter `tl/<lang>/` auf (`resolveLocalizedAsset`) und generiert aus einem `ProjectAst` direkt `.vjava`-Übersetzungsvorlagen (`extractTranslationTemplate`).

---

## 10. Audio-, ATL-Animations-, Transitions-, Partikel-, Video- & TTS-Architektur (M5)

### 10.1 OpenAL-Audio-Mixer, DSP, Lip-Sync & Self-Voicing (`de.swfdtf.audio`)
* **`AudioDecoder` & `AudioDsp`**: Dekodiert `.ogg` (Ogg Vorbis via `STBVorbis`) und `.wav` (8/16-Bit PCM, 32-Bit IEEE-Float) in normalisierte Float- und 16-Bit-PCM-Puffer (`DecodedAudio`), kodiert WAVs (`encodeWav`) und synthetisiert Sinustöne, Akkorde sowie Charakter-Voice-Bleeps (`synthesizeVoiceBleep`). `AudioDsp` stellt zustandsbehaftete Second-Order-IIR-`BiquadFilter` (`LOWPASS`, `HIGHPASS`, `BANDPASS`), RMS-Berechnung (`computeRms`), Hann-gefensterte logarithmische Radix-2-DFT-Spektrumbänder (`computeSpectrumBands`) und Goertzel-Analyse (`computeGoertzelMagnitude`) bereit.
* **`AudioChannel` & `AudioEngine`**: `AudioEngine` verwaltet die vier Standardkanäle `music`, `sound`, `voice` und `ambient` (plus beliebige Zusatzkanäle) über `AudioChannel` mit Dual-Slot-Crossfading, linearem Fade-In/Fade-Out, FIFO-Playlist (`queue`), DSP-Filterung, `PreferencesState`-Lautstärkekopplung (`masterVolume * channelVolume`), `tl/<lang>/`-Asset-Auflösung via `LocalizationManager`, `ScriptRuntime.AudioBridge`-Implementierung sowie vollständigem Snapshot/Restore (`ChannelSnapshot`, `snapshotAllChannels`/`restoreAllChannels`). Im Headless-Modus oder ohne Audiogerät arbeitet der Mixer deterministisch im Virtual-Mixer-Modus weiter.
* **`LipSyncAnalyzer` & `TextToSpeechService`**: `LipSyncAnalyzer` glättet die Echtzeit-Voice-RMS-Amplitude (`AudioEngine.getActiveVoiceRms()`) per asymmetrischer Attack-/Release-Hüllkurve zu `openness` (`[0, 1]`) und diskreten `MouthShape`-Zuständen (`CLOSED`, `HALF_OPEN`, `OPEN`). `TextToSpeechService` betreibt eine nicht-blockierende Virtual-Thread-Queue für Barrierefreiheits-Self-Voicing (`selfVoicing` / `V`), bereinigt Dialogzeilen über `TextTagParser.parse(rawText).spokenText()` und unterstützt OS-native Sprachsynthese (`say`, `spd-say`/`espeak`, PowerShell `SpeechSynthesizer`).

### 10.2 ATL-Animationen, GPU-Transitions, Partikel & Video (`de.swfdtf.graphics`)
* **`ATLTransform` & `AnimationEngine`**: `ATLTransform` interpretiert `VJavaAst.AtlStep`-Listen (`fromAst`) sowie programmatische `ATLTransform.Builder`-Timelines (`set`, `linear`/`ease*`/`cubicBezier`-Interpolation, `pause`, endliches/unendliches `repeat`, `parallel`, `block`, `choice`, `crop`, `matrixcolor`-Ausdrücke mit `*`-Verkettung) direkt auf `Transform2D`. `AnimationEngine` verwaltet aktive, per String-ID an `SceneNode`s gebundene `ATLTransform`-Instanzen inklusive Abschluss-Callbacks.
* **`Transition` & `TransitionManager`**: `Transition` beschreibt und parst (`Transition.parse(expr)`) 20 Übergangsarten (`NONE`, `DISSOLVE`, `FADE`, `PIXELLATE`, `MOVE`, `EASE`, `WIPE_LEFT/RIGHT/UP/DOWN`, `SLIDE_LEFT/RIGHT`, `PUSH_LEFT/RIGHT`, `IRIS_IN/OUT`, `BLINDS`, `SQUARES`, `IMAGE_DISSOLVE` mit `ramplen`/`reverse`, `MULTIPLE`). `TransitionManager` kombiniert eine headless testbare Timeline-State-Machine (inklusive `MULTIPLE`-Sub-Step-Tracking) mit einem OpenGL-3.3-Core-Dual-FBO-Shader-Compositor (`captureFromFbo`, `renderComposite`).
* **`ParticleSystem`**: Implementiert `CustomDisplayable` für GPU-fähige und headless simulierbare 2D-Wetter-/FX-Partikel (`Particle`, `ParticleEmitter` mit Wind, Sinus-Swinging, Rotation, Lebensdauer-Respawn und Wrap-Around) inklusive 5 Presets (`snowBlossom`, `rain`, `cherryBlossoms`, `sparks`, `dust`) zur direkten Einbindung auf `SceneGraph.LAYER_FOREGROUND` (`Z = 100`).
* **`VideoPlayer` & `MovieSprite`**: `VideoPlayer` treibt austauschbare `FrameSource`-Streams (`ProceduralTestFrameSource` für Tests/Fallbacks, Virtual-Thread-`FfmpegProcessFrameSource` mit `ffprobe`-Erkennung und fehlertolerantem Fallback bei fehlendem `ffmpeg`), steuert FPS-genaues Frame-Pacing, Looping und OpenGL-`glTexSubImage2D`-Updates. `MovieSprite` (`extends SpriteNode`) integriert `VideoPlayer` direkt in den `SceneGraph`.

---

## 11. Deklaratives UI-System, Dark-Slate-Palette, `LayeredImage` & 3 Dialog-Modi (M6)

### 11.1 `LayeredImage` & Composite-Bühnenanbindung (`de.swfdtf.graphics`)
* **`LayeredImage` (`implements CustomDisplayable`)**: Wertet `VJavaAst.LayeredImageDef` (oder `LayeredImage.builder(...)`) deterministisch gegen `selectedAttributes`, `StateStore` und `PersistentAccess` aus (`always`-Basisebenen, sich gegenseitig ausschließende `group`-Attribute mit `default`-Fallback sowie eigenständige `if`-Overlay-Ebenen).
* **Gruppen-Crossfade & Lip-Sync**: Bei Attributwechsel innerhalb einer `group` blendet `LayeredImage` über `DEFAULT_CROSSFADE_DURATION_SECONDS = 0.20f` zwischen ausgehender und eingehender Ebene über (`getActiveRenderLayers()`). Über `bindLipSync(LipSyncAnalyzer)` bzw. `applyMouthShape(MouthShape)` steuert der Voice-RMS-Analysator direkt die `"mouth"`-Gruppe (`closed`, `half`/`half_open`, `open`).
* **`SceneController`-Integration**: `ScriptRuntime` registriert alle `LayeredImageDef`s via `SceneController.registerLayeredImage(...)` und übergibt den Laufzeitkontext per `SceneController.setStateContext(stateStore, persistentAccess)`. `SceneController.showSprite(...)` erzeugt oder aktualisiert dann automatisch einen `LayeredImage`-basierten `SpriteNode` unter Beibehaltung der laufenden Crossfade-Zustände; `SceneController.update(double dt)` treibt aktive `CustomDisplayable`s an.

### 11.2 Dark-Slate-Theme, Screen-Stack, Container & Widgets (`de.swfdtf.ui`)
* **`ThemePalette`**: Einheitliches Dark-Slate-Farbschema (`CANVAS_BG = "#0d0e15"`, `PANEL_SURFACE = "#161822"`, `PANEL_GLASS = "#161822d9"`, `ELEVATED = "#1f2232"`, `BORDER = "#2e334a"`, `ACCENT_IDLE = "#388bfd"`, `ACCENT_HOVER = "#58a6ff"`, `ACTIVE_SELECTED = "#f0883e"`, `DISABLED = "#8b949e7f"`, `TEXT_PRIMARY = "#f0f6fc"`, `TEXT_MUTED = "#8b949e"`, `SUCCESS = "#3fb950"`, `WARNING = "#d29922"`, `ERROR = "#f85149"`).
* **`UiNode`, `Screen` & `ScreenManager`**: `ScreenManager` sortiert sichtbare `Screen`s aufsteigend nach `zOrder`, sperrt Input unterhalb des obersten modalen Screens (`getInputEligibleScreensTopDown()`), löst screen-lokale `KeyBinding`s und `ScreenTimer` aus, verwaltet `Tab`/`Shift+Tab`-Focus-Cycling (`cycleFocus`) und leitet Maus-, Scroll-, Zeichen- und Tastaturereignisse vom `InputManager` in logischen Canvas-Koordinaten weiter.
* **Layout-Container & Widgets**:
  * Container: `VBox`, `HBox`, `Grid`, `Fixed` (`addChildAt` + `AlignmentSpec`), `Frame` (Dark-Slate oder `NineSliceDrawable`), `WindowBox` (Titelkopfzeile), `Viewport` (Scissor-Clipping via `UiRenderSupport.pushScissor`/`popScissor` + interaktive Scrollbar) und `VpGrid` (Row-Virtualisierung `[firstVisibleRow..lastVisibleRow]`).
  * Widgets: `TextWidget` (`TextTagParser` + `StyledTextLayout` + `TypewriterController`), `TextButton`, `ImageButton`, `Hotspot`, `Hotbar`, `Imagemap`, `Bar`, `VBar`, `Slider`, `TextInput` (`allowChars`/`maxLength`/Caret), `DragItem`/`DragGroup` (Drop-Target-Snapping) und `CustomDisplayableWidget` (bettet `LayeredImage`/`ParticleSystem` in UI-Bäume ein).
* **`ScreenAstBuilder`**: Übersetzt geparste `VJavaAst.ScreenDef`-Bäume direkt in lauffähige `Screen`-Instanzen inklusive `if`/`elif`/`else`-Auswertung über `ConditionEvaluator`, `[var]`-Interpolation, `key`/`timer`-Bindungen, `VariableValue`/`VariableInputValue`-State-Bindings, `custom`-Displayable-Instanziierung (`VJavaUserTypes` / Klassenname) und Action-Dispatching.

### 11.3 Die 3 Dialog-Modi & eingebaute System-/Meta-Screens
* **3 Dialog-Modi (`Z = 200`)**:
  1. **`AdvDialogueScreen`**: Klassische Dialogbox `(140, 790, 1640, 250)` mit Sprecher-Badge, `PreferencesState.getTextSpeedCps()`-Typewriter, gedrosselten Voice-Bleeps (`0.055s` via `AudioEngine.playVoiceBleep`), Auto-Forward, Skip (`skipUnread`/`skipAfterChoices`), Self-Voicing-TTS und QuickMenu-Leiste.
  2. **`NvlDialogueScreen`**: Vollbild-`PANEL_GLASS`-Overlay mit scrollbarer `Viewport`-Absatzliste aus `ScriptRuntime.getNvlLines()`.
  3. **`BubbleDialogueScreen`**: Dynamische Sprechblasen über dem Sprecher-Sprite (`SceneController.getActiveSprite`), Multi-Bubble-Retain-Modus (`setRetainPreviousBubbles`) und integriertem `Shift+B`-Drag-Editor (Persistenz in `saves/bubble_layouts.json`).
* **Overlays & eingebaute System-/Meta-Screens**: `QuickMenuScreen` (`Z = 210`), `ChoiceMenuScreen` (`Z = 300`, Timeout-`Bar`), `InputOverlayScreen` (`Z = 300`, modal), `MainMenuScreen` (`Z = 400`, automatisches `Continue` über neuesten Save-Slot), `SaveLoadScreen` (`Z = 400`, 5×6 manuelle Slots + `QUICK`/`AUTO`-Tabs, Custom-Name, STB-PNG-Thumbnails, Löschen), `PreferencesScreen` (`Z = 400`, Live-Audio-/Text-/Modus-/Sprach-/TTS-/Dyslexie-/Scale-Einstellungen), `ControlsScreen` (`Z = 400`, Keyboard/Mouse/Gamepad-Tabelle), `AboutScreen` (`Z = 400`), `HistoryLogScreen` (`Z = 400`, `Replay Voice` + `Jump Back`), `GalleryScreen` (`Z = 400`, `VpGrid` + Variationen + Zoom), `MusicRoomScreen` (`Z = 400`, 16-Band-`VBar`-Spektrum), `EndingsTreeScreen` (`Z = 400`, Routenbaum + Completion-`Bar`), `AchievementsScreen` (`Z = 400`, versteckte Einträge als `"???"`) und `NotificationToastOverlay` (`Z = 500`, Achievement-Toast-Animation + `SKIPPING >>`/`AUTO >`-Status-Pill).

---

## 12. Laufzeit-Koordination (`EngineApplication`, `GameRuntimeCoordinator` & `GlfwWindow`)

* **`EngineApplication` & `GameHost`**: Implementiert `AutoCloseable`, verwaltet `EngineAPI`, `SceneGraph`, `WindowManager`, registrierte `EngineApplication.Updatable`-Hooks, die aktive `IRenderPipeline`, den optionalen `GameHost` (`GameRuntimeCoordinator`) sowie die optionale `StudioBridge` (`StudioWindowController`).
* **`GameRuntimeCoordinator` (`de.swfdtf.ui`)**: Vollständige Spiel-Laufzeit auf Fenster B; verdrahtet `ProjectScanner`, `ScriptSession`, `AudioEngine`, `LipSyncAnalyzer`, `TextToSpeechService`, `AnimationEngine`, `TransitionManager`, `ScreenManager`, alle 3 Dialog-Modi, alle eingebauten System-/Meta-Screens, globale Spiel-Shortcuts und (im Studio-Modus) `LiveDirectorOverlay` sowie `DeveloperInspectors`.
* **Deterministische Schleife (`run()` / `stepOnce(double)`)**: Pollt Eingabe- und Fensterereignisse, verarbeitet `api.consumeLiveReloadRequested()` (`gameHost.onReload()` + `studioBridge.onReload()`), führt `GameClock.advanceFrame(realDelta)` in deterministischen `1/60 s`-Schritten für `SceneGraph.update`, `GameHost.update`, `Updatable.update` und `StudioBridge.update` aus und rendert anschließend Game-Fenster B (inklusive `gameHost.renderUi`) und (im Studio-Modus) Studio-Fenster A.
* **Test- & Automatisierungsmodi**: `--headless` überspringt GLFW-Fenstererzeugung und OpenGL-Kontexte für deterministische JUnit-Tests über `stepOnce(...)`; `--smoke-test` beendet `run()` sauber nach `5` Frames; `--create-template <dir>` generiert über `ProjectTemplateGenerator` ein spielbares Starterprojekt und startet es direkt.
* **`GlfwWindow`**: Kapselt das native GLFW-Fenster-Handle, unterscheidet zwischen logischer Fenstergröße (Screen Coordinates) und physischer Framebuffer-Pixelgröße (HiDPI/Retina) und schaltet `GLCapabilities` sauber pro Fensterkontext um.

---

## 13. Studio-Werkzeuge, Live-Regisseur, Linter & Standalone-Packaging (M7)

* **`ProjectTemplateGenerator` (`de.swfdtf.tools`)**: Erzeugt ohne `java.awt` vollständige, Lint-saubere Starterprojekte (`project.vjava.json`, `main.vjava` mit eingebetteten Java-Klassen, `layeredimage`-Lip-Sync-Gruppen und Verzweigung, `ui/screens.vjava`, `tl/english/translations.vjava`, prozedurale PNG-Bilder über `TextureManager.writePng` und WAV-Audio über `AudioDecoder.encodeWav`).
* **`VJavaLinter` (`de.swfdtf.tools`)**: Statischer Projekt-Validator mit `Severity.ERROR` / `Severity.WARNING` für fehlende Entry-Labels (`MISSING_ENTRY_LABEL`), defekte `jump`/`call`/`menu`-Ziele, unerreichbare Labels (`UNREACHABLE_LABEL`), fehlende `assets/`-Referenzen (`MISSING_ASSET`), undefinierte Sprecher (`UNDEFINED_SPEAKER`), Duplikate (`DUPLICATE_DEFINITION`) und Java-21-Kompilierfehler in `@->{ ... }<<`-Blöcken (`JAVA_COMPILE_ERROR`).
* **`LiveDirectorOverlay` (`D`, `Z = 1000`)**: Zeigt aktive Bühnen-Sprites (`SceneController.getActiveSprites`), erlaubt Live-Umschaltung von Attributen und Transform-Slidern (`xpos`, `ypos`, `zoom`, `alpha`) und schreibt Änderungen über `updateShowLineInScript(...)` atomar (Temp-Datei + `ATOMIC_MOVE` unter Erhalt von Einrückung, `with`-Klausel und Kommentaren) zurück in die `.vjava`-Quelldatei.
* **`DeveloperInspectors` (`Z = 1000`)**: Bündelt Live-Reload (`Shift+R` unter Erhalt von Label, Statement-Index und `StateStore`), Style-Inspector (`Shift+I`, Hit-Testing über `ScreenManager`), Coordinate-Picker (`Shift+C`, `1920×1080` + `[0..1]`-Snippets), Live-Java-Konsole (`Shift+O`, Ad-hoc-Kompilierung gegen `VJavaUserTypes` und Live-Subsysteme) sowie Label-Warping (`warpToLabel`).
* **`StudioWindowController` (`implements StudioBridge`)**: Rendert Fenster A mit Toolbar (`Generate Starter`, `Run Lint`, `Reload (Shift+R)`, `Mode: 2D/3D (Stub)`, `Export Standalone`), Projekt-Explorer & Asset-Liste, `VJavaLinter`-Diagnoseansicht, Live-`StateStore`-Variableninspektor und `EngineLogger`-Konsole.
* **`VpakArchiver` & `StandaloneBuilder`**: `VpakArchiver` packt Projektmetadaten, `.vjava`-Skripte, `assets/` und `tl/` (unter Ausschluss von `saves/`, `.git/`, `build/`, `dist/`) in ein indiziertes binäres `.vpak`-Archiv (`MAGIC = "VPAK"`, `FORMAT_VERSION = 1`, Deflate + CRC32). `StandaloneBuilder` bricht bei Lint-Fehlern ab und erzeugt das fertige Auslieferungsverzeichnis mit `game.vpak`, `run_game.sh` (inkl. `-XstartOnFirstThread` auf macOS), `run_game.bat`, optionalem `kagayaki-standalone.jar` und `standalone-packaging.json` (`jlink`/`jpackage`-Deskriptor).
