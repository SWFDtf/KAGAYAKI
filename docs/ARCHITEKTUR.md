# KAGAYAKI — Systemarchitektur

## 1. Übersicht & Leitprinzipien

**KAGAYAKI** (`de.swfdtf`) ist eine auf **Java 21** und **LWJGL 3** (`OpenGL 3.3 Core`, `GLFW`, `OpenAL`, `STB`) basierende Visual-Novel-Engine und Dual-Window-Autorenumgebung für `.vjava`-Skripte. Das gesamte Grafik- und Textsubsystem verzichtet vollständig auf `java.awt` / Swing.

### Technologischer Stack (verifiziert in `build.gradle`)
* **Sprache & Toolchain**: Java 21 (`sourceCompatibility = JavaVersion.VERSION_21`, `targetCompatibility = JavaVersion.VERSION_21`)
* **Build-System**: Gradle (Groovy DSL) mit automatischer Plattform-/Architektur-Erkennung für LWJGL-Natives (`windows`, `windows-arm64`, `macos`, `macos-arm64`, `linux`, `linux-arm64`) und `-XstartOnFirstThread` auf macOS
* **Grafik, Fenster & Audio**: LWJGL `3.3.6` (`lwjgl`, `lwjgl-glfw`, `lwjgl-opengl`, `lwjgl-openal`, `lwjgl-stb`)
* **Mathematik & Serialisierung**: `org.joml:joml:1.10.8`, `com.google.code.gson:gson:2.11.0`
* **Testing**: JUnit Jupiter `6.0.0` (`useJUnitPlatform()`) — **121** Tests (laut `build/test-results/test/`) in `core`, `git`, `graphics`, `math`, `parser` und `state`

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

## 3. Paketstruktur & Status (Meilenstein M4 abgeschlossen)

| Paket | Status | Verantwortung |
| :--- | :--- | :--- |
| `de.swfdtf` (`Main` / `StandaloneMain`) | **Implementiert (M1)** | `Main` (Standard `STUDIO_DUAL_WINDOW`, optional `--standalone`) und `StandaloneMain` (erzwingt `STANDALONE_GAME`) als CLI-Einstiegspunkte für `EngineApplication`. |
| `de.swfdtf.math` | **Implementiert (M1)** | `Vec2f`, `Rect2f`, `ColorMatrix4f` (Premultiplied Alpha), `Easing` (`EaseType` + `cubicBezier`), `Transform2D`, `ScreenShake` (`vpunch`/`hpunch`/`custom`). |
| `de.swfdtf.core` | **Implementiert (M1)** | `EngineMode`, `EngineConfig` (+ `Builder`, CLI-Parser), `ProjectDescriptor` (`project.vjava.json`), `EngineLogger` (Ringpuffer + Listener), `GameClock` (60 Hz Fixed-Timestep), `InputManager` (GLFW + Letterbox-Mapping + Gamepad), `EngineAPI` (Fassade + Service-Locator), `StudioBridge`, `EngineApplication` (+ `Updatable`). |
| `de.swfdtf.graphics` | **Implementiert (M1 + M2 + M3)** | `RenderMode`, `IRenderPipeline`, `RenderPipeline2D` (OpenGL 3.3 Core, Offscreen-`Framebuffer`, `ShaderProgram`, SDF-Rounded-Rects, Pivot-Rotation, Letterbox-Blit), `RenderPipeline3DStub`, `SceneGraph`, `SceneLayer`, `SceneNode`, `SpriteNode`, `SceneController` (+ `StageEntry`, `StageSnapshot`), `CustomDisplayable`, `GlfwWindow`, `WindowManager`, `Texture`, `TextureManager` (`STBImage`/`STBImageWrite`, Fallback-Schraffur), `NineSliceDrawable`, `TextTagParser`, `FontAtlas` (`STBTruetype` + CPU-Fallback), `FontManager`, `StyledTextLayout` (+ `TypewriterController`). |
| `de.swfdtf.git` | **Implementiert (M1)** | `GitController` (nicht-interaktive Git-CLI-Steuerung mit Commit-Policy-Validierung). |
| `de.swfdtf.parser` | **Implementiert (M3 + M4)** | `VJavaLexer`, `VJavaParser`, `VJavaAst`, `VJavaParseException`, `ProjectScanner`, `JavaBlockCompiler` (`VJavaUserTypes` + `VJavaSnippet_<i>` via `javax.tools.JavaCompiler`), `VJavaExecutable`, `VJavaCompileException`, `ConditionEvaluator`, `ScriptRuntime` und `ScriptSession`. |
| `de.swfdtf.state` | **Implementiert (M3 + M4)** | Verträge `StateStore` / `PersistentAccess` / `Revertable`, In-Memory-Implementierungen (`SimpleStateStore`, `SimplePersistentAccess`), deterministischer Rollback-Speicher (`RevertableMap`, `RevertableList`, `RevertableStateStore`), 4-Tier-Persistenz (`SessionState`, `PersistentState`, `PreferencesState`), `HistoryLog`, `SaveValueCodec`, `SaveSlotData`, `SaveManager`, Meta-Manager (`AchievementManager`, `GalleryManager`, `EndingsManager`) und `LocalizationManager`. |
| `de.swfdtf.audio` *(geplant)* | Geplant (M5) | OpenAL-Audio-Mixer (`music`, `sound`, `voice`, `ambient`), Crossfades und RMS-Lip-Sync. |
| `de.swfdtf.ui` *(geplant)* | Geplant (M6) | Deklarative Screens, Layout-Container, Widgets, ADV/NVL/Sprechblasen-Modi. |
| `de.swfdtf.tools` *(geplant)* | Geplant (M7) | `StudioWindowController`, Vorlagen-Generator, Live-Regisseur, Lint und `.vpak`-Packager. |

---

## 4. Service-Entkopplung über `EngineAPI`

Um zyklische Paketabhängigkeiten zu vermeiden, stellt `EngineAPI` einen Thread-sicheren, typisierten Service-Locator (`registerService`, `getService`, `requireService`, `hasService`, `unregisterService`) sowie eine atomare Property-Map (`getProperty`, `setProperty`, `removeProperty`, `getPropertiesSnapshot`) bereit. `EngineApplication` registriert beim Start automatisch `SceneGraph`, sich selbst (`EngineApplication`) sowie bei `switchRenderPipeline(...)` die aktive `IRenderPipeline` (inklusive Property `"renderMode"`). `ScriptRuntime` registriert bei Konstruktion automatisch `StateStore`, `PersistentAccess`, `SceneController` und sich selbst (`ScriptRuntime`), während `ScriptSession` zusätzlich alle M4-Subsysteme (`RevertableStateStore`, `PersistentState`, `PreferencesState`, `SessionState`, `HistoryLog`, `SaveManager`, `LocalizationManager`, `AchievementManager`, `GalleryManager`, `EndingsManager` und `ScriptSession`) im Service-Locator einträgt, ohne dass `de.swfdtf.core` diese Pakete importieren muss.

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

## 10. Laufzeit-Koordination (`EngineApplication` & `GlfwWindow`)

* **`EngineApplication`**: Implementiert `AutoCloseable`, verwaltet `EngineAPI`, `SceneGraph`, `WindowManager`, registrierte `EngineApplication.Updatable`-Hooks, die aktive `IRenderPipeline` sowie die optionale `StudioBridge`.
* **Deterministische Schleife (`run()` / `stepOnce(double)`)**: Pollt Eingabe- und Fensterereignisse, verarbeitet `api.consumeLiveReloadRequested()`, führt `GameClock.advanceFrame(realDelta)` in deterministischen `1/60 s`-Schritten für `SceneGraph.update`, `Updatable.update` und `StudioBridge.update` aus und rendert anschließend Game-Fenster B und (im Studio-Modus) Studio-Fenster A.
* **Test- & Automatisierungsmodi**: `--headless` überspringt GLFW-Fenstererzeugung und OpenGL-Kontexte für deterministische JUnit-Tests über `stepOnce(...)`; `--smoke-test` beendet `run()` sauber nach `5` Frames.
* **`GlfwWindow`**: Kapselt das native GLFW-Fenster-Handle, unterscheidet zwischen logischer Fenstergröße (Screen Coordinates) und physischer Framebuffer-Pixelgröße (HiDPI/Retina) und schaltet `GLCapabilities` sauber pro Fensterkontext um.
