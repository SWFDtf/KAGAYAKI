# KAGAYAKI — Entwicklungs-Changelog

Dieses Dokument protokolliert chronologisch abgeschlossene Teilmodule, Architektur-Änderungen und Meilensteine von **KAGAYAKI** (`de.swfdtf`).

---

## [M4 — Abgeschlossen] 4-Tier-State, Deterministisches Rollback, Save/Load, `HistoryLog`, Meta-Manager, `LocalizationManager` & `ScriptSession`

### Paket `de.swfdtf.state`
* `Revertable<S>`, `RevertableMap<K, V>` & `RevertableList<E>`: Generischer Snapshot-Vertrag sowie zyklus- und tiefensichere Container (`cloneValue`, `deepEquals`) für Collections, Arrays, `Transform2D`, `Serializable` und Referenz-Erhalt für nicht-serialisierbare `@->{ ... }<<`-POJOs.
* `RevertableStateStore` (+ `Checkpoint`): Transaktionale `StateStore`-Implementierung mit automatischer Checkpoint-Aufzeichnung, deterministischem `rollback()`/`rollForward()`, Erhalt der Roll-Forward-Spur bei identischem Replay, Branch-Trimming bei Verzweigung, `jumpToCheckpoint(long)` für `HistoryLog`-Jump-Back und unumkehrbaren `block_rollback`-Barrieren (`rollbackBarrierBefore`).
* `SessionState` (Tier 1): Transiente Sitzungsverwaltung (Startzeit, `updatePlaytime(double)`, aktiver Save-Slot, Auto-Save-Zeitstempel, Session-Flags).
* `PersistentState` (Tier 3): Atomar geschriebene GZIP-JSON-Persistenz (`saves/persistent.dat`) für globale Variablen, gelesene Dialogzeilen (`readLines`) sowie CG-/Track-/Ending-/Achievement-Freischaltungen (`implements PersistentAccess`).
* `PreferencesState` (Tier 4): Menschenlesbare JSON-Konfiguration (`saves/preferences.json`) für Audio-Pegel, Textgeschwindigkeit, Auto-Forward-Delay, Skip-Verhalten, Render-Modus, Sprache, Dialogmodus und Barrierefreiheit (`normalizeDefaults()`).
* `HistoryLog` (+ `HistoryEntry`): Kapazitätsbegrenzter (`DEFAULT_MAX_ENTRIES = 500`) Dialog-Backlog mit `checkpointId`-Korrelation, `getRecentEntries(int)`, `truncateAfterCheckpoint(long)` und `snapshot()`/`restore(...)`.
* `SaveValueCodec`, `SaveSlotData` (+ `SaveMetadata`, `SavedStackFrame`, `SavedScriptPosition`) & `SaveManager` (Tier 2): Atomare GZIP-JSON-Spielstände (`saves/<slotId>.ksav`) mit LWJGL-STB-PNG-Thumbnails (`encodeRgbaToPng` ohne `java.awt`), Seitenabfrage (`listPageSlots`), Quick-Save (`"quick"`), rotierendem Auto-Save (`auto_1`..`auto_3`) und typerhaltender JSON-Serialisierung inklusive `__vjavaClass`-Rekonstruktion für benutzerdefinierte `@->{ ... }<<`-Klassen.
* `AchievementManager` (+ `AchievementDef`, `AchievementToast`), `GalleryManager` (+ `GalleryEntry`) & `EndingsManager` (+ `EndingDef`): Thread-sichere Meta-Game-Manager auf Basis von `PersistentAccess` (inklusive Toast-Queue, CG-Variationen und Eltern-Kind-Endings-Baum mit Completion-Prozentwert).
* `LocalizationManager`: Live-Sprachwechsel mit Listenern, Indizierung von `VJavaAst.TranslateBlock`-Einträgen (`translateDialogue`, `translateUi`), `tl/<lang>/`-Asset-Auflösung (`resolveLocalizedAsset`) und automatischer `.vjava`-Übersetzungsvorlagen-Generierung (`extractTranslationTemplate`).
* JUnit-6-Tests in `src/test/java/de/swfdtf/state/` (`LocalizationManagerTest`, `MetaManagersTest`, `PersistenceAndHistoryTest`, `RevertableStateStoreTest`, `SaveManagerTest`).

### Erweiterung `de.swfdtf.parser`
* `ScriptRuntime`: Um Live-Lokalisierung (`setLocalizationManager`, `getLocalizationManager`, Übersetzung von Dialogen, Menü-Captions, Menü-Optionen und Input-Prompts vor `[var]`-Interpolation) sowie Save-Slot-Positionsexport/-import (`toSavedPosition`, `restoreSavedPosition`) erweitert.
* `ScriptSession`: High-Level-Sitzungskoordinator, der `ScriptRuntime` mit `RevertableStateStore`, `PersistentState`, `PreferencesState`, `SessionState`, `HistoryLog`, `SaveManager`, `LocalizationManager`, `AchievementManager`, `GalleryManager` und `EndingsManager` verbindet, alle Services in `EngineAPI` registriert und Rollback-, History-, Quick-Save-/Quick-Load- sowie Auto-Save-Operationen bereitstellt.
* JUnit-6-Integrationstest in `src/test/java/de/swfdtf/parser/ScriptRollbackAndSaveIntegrationTest.java` — insgesamt **121** grüne Tests laut `build/test-results/test/` über alle Pakete.

### Dokumentation
* `src/main/java/de/swfdtf/state/AGENT_CONTEXT.md` und `src/main/java/de/swfdtf/parser/AGENT_CONTEXT.md` vollständig auf M4 aktualisiert.
* `docs/ARCHITEKTUR.md`, `docs/API_REFERENZ.md`, `docs/VJAVA_SPEZIFIKATION.md` und `docs/CHANGELOG_DEV.md` auf den abgeschlossenen Stand von Meilenstein M4 aktualisiert.

---

## [M3 — Abgeschlossen] `.vjava`-Lexer, Parser, `ProjectScanner`, In-Memory-Java-Compiler, `SceneController`, `StateStore` & `ScriptRuntime`

### Paket `de.swfdtf.parser`
* `VJavaAst`: Unveränderliches AST-Modell mit `Statement`-Hierarchie (21 Record-Varianten), `JavaBlockNode`, `CharacterDef`, `ImageDef`, `LayeredImageDef` (`AlwaysLayerEntry`, `GroupLayerEntry`, `AttributeEntry`, `ConditionalLayerEntry`), `TransformDef` (`AtlStep`), `ScreenDef` (`ScreenItem`), `TranslateBlock` (`TranslateEntry`), `LabelNode`, `ScriptFileAst` und `ProjectAst`.
* `VJavaLexer` (+ `TokenType`, `Token`) & `VJavaParseException`: Einrückungsbewusster Lexer (`INDENT`/`DEDENT`/`LINE`/`JAVA_BLOCK`/`EOF`, 4-Spalten-Tab-Expansion, `#`/`//`-Kommentare) mit sauberer Isolation eingebetteter `@->{ ... }<<`-Java-Blöcke (inklusive `:: Kode für VN: ...`-Header und Java-String-/Textblock-/Kommentar-Tracking).
* `VJavaParser`: Rekursiv-absteigender Parser für alle `.vjava`-Top-Level-Deklarationen (`define`, `default`, `$`, `image`, `layeredimage`, `transform`, `screen`, `translate`, `label`, `@->{ ... }<<`) und Label-Statements (`scene`, `show`, `hide`, `with`, `play`, `queue`, `stop`, `voice`, `menu` inline/eingerückt/timed, `if`/`elif`/`else`, `jump`/`call`/`return`, `input`, `nvl`, `window`, `pause`, `block_rollback`, `pass`, `$`, Dialoge).
* `ProjectScanner`: Rekursiver Scanner für `**/*.vjava` (unter Auslassung von `.git`, `build`, `.gradle`, `saves`, `out`, `dist`), `project.vjava.json` (`ProjectDescriptor`) und `assets/` mit lexikografischer Sortierung und Last-Wins-Duplikatwarnungen.
* `JavaBlockCompiler` (+ `CompiledProjectBlocks`), `VJavaExecutable` & `VJavaCompileException` (+ `CompileDiagnostic`): In-Memory-Java-21-Compiler via `ToolProvider.getSystemJavaCompiler()` und `ForwardingJavaFileManager` (ohne temporäre Dateien). Kompiliert benutzerdefinierte Klassen/Records/Enums/Interfaces/Unterklassen und statische Helfer in `de.swfdtf.vjava.gen.VJavaUserTypes` sowie Snippets in `VJavaSnippet_<i>` mit Bindings `(state, persistent, engine, scene)` und zeilen-gemappten Compiler-Diagnosen.
* `ConditionEvaluator`: Rekursiv-absteigender Ausdrucks- und Bedingungs-Evaluator (Arithmetik, Vergleiche, Logik `&&`/`||`/`!`/`and`/`or`/`not`, Ternär `?:`, Feld-/Record-/Getter-Zugriffe, Indexierung, Methodenaufrufe, `state`, `persistent`, `Math` und KAGAYAKI-Truthiness).
* `ScriptRuntime` (+ `Status`, `DialoguePrompt`, `ChoiceOption`, `MenuPrompt`, `InputPrompt`, `AudioBridge`, `AudioEvent`, `StackFrame`, `ExecutionSnapshot`): Nicht-blockierende State-Machine-Skript-Laufzeit mit `[var]`-Interpolation (`TextTagParser`), NVL-Modus, Call-Stack, timed `menu`/`pause`-Countdowns, `AudioBridge`, `EngineAPI`-Service-Registrierung und M4-Snapshot-/Checkpoint-Hooks.
* JUnit-6-Tests in `src/test/java/de/swfdtf/parser/` (`VJavaLexerAndParserTest`, `ProjectScannerTest`, `JavaBlockCompilerTest`, `ConditionEvaluatorTest`, `ScriptRuntimeIntegrationTest`) — insgesamt **101** Tests laut `build/test-results/test/` über alle Pakete.

### Paket `de.swfdtf.state`
* `StateStore` & `SimpleStateStore`: Vertragsinterface (`state`-Binding) inklusive Typ-Coercion, `snapshotMap`/`restoreMap` und `blockRollback` sowie thread-sichere M3-In-Memory-Implementierung.
* `PersistentAccess` & `SimplePersistentAccess`: Vertragsinterface (`persistent`-Binding) für globale Variablen, Read-Tracking (`markLineRead`/`isLineRead`) sowie CG-/Track-/Ending-/Achievement-Freischaltungen inklusive thread-sicherer In-Memory-Implementierung.

### Erweiterung `de.swfdtf.graphics`
* `SceneController` (+ `StageEntry`, `StageSnapshot`): High-Level-Bühnensteuerung (`scene`-Binding) für `setScene`, `showSprite` (inklusive automatischem Positionserhalt bei wiederholtem Tag ohne `at`), `hideSprite`, eingebaute Positionen (`left`, `center`, `truecenter`, `right`, `offscreenleft`, `offscreenright`, `reset`), `image`/`transform`-Registrierung sowie `snapshotState`/`restoreState`.

### Dokumentation
* `src/main/java/de/swfdtf/parser/AGENT_CONTEXT.md` und `src/main/java/de/swfdtf/state/AGENT_CONTEXT.md` neu erstellt.
* `src/main/java/de/swfdtf/graphics/AGENT_CONTEXT.md` um `SceneController`, `StageEntry` und `StageSnapshot` erweitert.
* `docs/VJAVA_SPEZIFIKATION.md`, `docs/ARCHITEKTUR.md`, `docs/API_REFERENZ.md` und `docs/CHANGELOG_DEV.md` vollständig auf Meilenstein M3 aktualisiert.

---

## [M2 — Abgeschlossen] Texturen (`STBImage`), 9-Slice, `SpriteNode`, `CustomDisplayable` & Rich-Text-/Typografie-Pipeline

### Erweiterung `de.swfdtf.graphics` (ohne `java.awt`)
* `Texture`: Premultiplied-`RGBA8`-OpenGL-Textur-Handle (`GL_LINEAR`, `GL_CLAMP_TO_EDGE`) inklusive `premultiplyRgba`, `createFromRgba`, `bind`/`unbind` und `isFallback()`-Flag.
* `TextureManager`: Reiner `STBImage`/`STBImageWrite`-Asset-Loader und kanonischer Pfad-Cache (`loadTexture`, `readImageFileRgba`, `writePng`) mit prozeduraler `128×128`-Magenta/Dunkelviolett-Schraffur (`generateMissingAssetRgba`) und `EngineLogger`-Warnung bei fehlenden/defekten Dateien.
* `NineSliceDrawable` (+ `SliceQuad`): 9-Slice-UI-Panel-Renderer (4 Ecken, 4 Kanten, 1 Zentrum) mit proportionalem Corner-Clamping bei kleinen Zielmaßen und Pivot-Rotation.
* `CustomDisplayable` & `SpriteNode`: Erweiterbarer Displayable-Vertrag (`update`, `render`, `getPreferredWidth/Height`) und VN-Sprite-Knoten (`imageTag`, `attributes`, `assetPath`) mit direkter Unterstützung für `Texture`, `NineSliceDrawable` und `CustomDisplayable`.
* `SceneNode` & `RenderPipeline2D`: `SceneNode.shouldDrawStandardQuad()`-Hook ergänzt; `RenderPipeline2D` um Pivot-Rotation (`drawTexturedQuad(..., rotateDegrees, originX, originY)` / `drawTexturedQuadPivot(...)`), `getLogicalProjection()` sowie Custom-Render-Kontext (`getCurrentRenderBounds`, `getCurrentEffectiveAlpha`, `getCurrentEffectiveColorMatrix`, `getCurrentRotationDegrees`, `getCurrentAnchorX/Y`) erweitert.
* `TextTagParser`: Vollständiger Rich-Text-Parser (`interpolateVariables`, `parse`) für `[var]`-Interpolation, `[[`/`{{`-Escaping, Inline-Styles (`{b}`, `{i}`, `{u}`, `{s}`, `{sc}`, `{plain}`, `{color}`, `{alpha}`, `{size}`, `{font}`, `{k}`), Flow-Cues (`{w}`, `{w=t}`, `{p}`, `{p=t}`, `{nw}`, `{fast}`, `{cps=n}`), Furigana (`{rb}...{/rb}{rt}...{/rt}`), Inline-Bilder (`{image=...}` mit `\uFFFC`), Hyperlinks (`{a=...}...{/a}`) sowie Text-Shader-Tags (`{wave}`, `{jitter}`/`{shake}`, `{gradient=c1,c2}`).
* `FontAtlas`: Dynamischer `STBTruetype`-Glyphenatlas (Premultiplied `RGBA8`, Shelf-Packing bis `4096×4096`, Kerning-Cache) inklusive autarkem CPU-Bitmap-Fallback (ASCII, `äöüÄÖÜß`, Typografie-Symbole, CJK-Boxen) für Headless/fehlende Fonts.
* `FontManager`: Zentrale Font-Registry (In-Memory, Datei, Asset-Root, Klassenpfad-Ressourcen, optionale System-Font-Erkennung) und globale Barrierefreiheits-Einstellungen (`dyslexiaFriendlyMode`, `globalTextScale`).
* `StyledTextLayout` (+ `TypewriterController`, `GlyphQuad`, `LineDecoration`, `RubyLayout`, `InlineImageLayout`, `HyperlinkHitbox`, `TextLine`): Wortumbruch (inkl. CJK), Drop-Shadow/Outline, zentrierte Furigana-Positionierung, Hyperlink-Hit-Testing, Echtzeit-Animation (`wave`, `jitter`, `gradient`) und deterministischer Typewriter-Controller.
* JUnit-6-Tests in `src/test/java/de/swfdtf/graphics/` (`TextureMathTest`, `NineSliceDrawableTest`, `SpriteNodeTest`, `TextTagParserTest`, `StyledTextLayoutTest`) ergänzt — insgesamt **83** grüne Unit-Tests über alle Pakete.

### Dokumentation
* `src/main/java/de/swfdtf/graphics/AGENT_CONTEXT.md` um alle M2-Klassen, Records und aktualisierten Signaturen von `RenderPipeline2D`/`SceneNode` ergänzt.
* `docs/ARCHITEKTUR.md`, `docs/API_REFERENZ.md`, `docs/VJAVA_SPEZIFIKATION.md` und `docs/CHANGELOG_DEV.md` auf den abgeschlossenen Stand von Meilenstein M2 aktualisiert.

---

## [M1 — Abgeschlossen] Grafik-Subsystem, WindowManager, `EngineApplication` & Einstiegspunkte

### Paket `de.swfdtf` (Einstiegspunkte)
* `Main`: Primärer Einstiegspunkt; startet `EngineApplication` standardmäßig in `EngineMode.STUDIO_DUAL_WINDOW`, sofern `--standalone` nicht explizit in den CLI-Argumenten angegeben wurde.
* `StandaloneMain`: Dedizierter Einstiegspunkt für exportierte Standalone-Builds (`packageStandalone`); erzwingt `EngineMode.STANDALONE_GAME`.

### Erweiterung `de.swfdtf.core`
* `EngineApplication` (+ `EngineApplication.Updatable`): Top-Level-Laufzeitkoordinator (`AutoCloseable`) mit Initialisierung, deterministischer 60-Hz-Fixed-Timestep-Schleife (`run()`, `stepOnce(double)`), dynamischer `StudioBridge`-Einbindung (`de.swfdtf.tools.StudioWindowController`), `switchRenderPipeline(RenderMode)`, Service-Registrierung (`SceneGraph`, `EngineApplication`, `IRenderPipeline`), `--headless`- und `--smoke-test`-Unterstützung (5 Frames).
* JUnit-6-Tests in `src/test/java/de/swfdtf/core/EngineApplicationTest.java` ergänzt.

### Paket `de.swfdtf.graphics`
* `RenderMode`: Backend-Modus-Enum (`MODE_2D`, `MODE_3D`) inklusive `fromString(String)`.
* `IRenderPipeline`: Einheitliches Render-Pipeline-Interface (`init`, `beginFrame`, `renderSceneGraph`, `endFrame`, `getMode`, `dispose`).
* `Framebuffer`: OpenGL-Offscreen-FBO mit `RGBA8`-Farbtextur (`GL_LINEAR`, `GL_CLAMP_TO_EDGE`), `resize` und Top-to-Bottom-Readback (`readPixelsRgba`).
* `ShaderProgram`: OpenGL-3.3-Core-GLSL-Compiler/-Linker mit Uniform-Location-Caching und Matrix-/Vektor-/Skalar-Settern.
* `GlfwWindow` & `WindowManager`: GLFW-Lebenszyklus-Management für Dual-Window (`initDualWindows` mit gemeinsamem OpenGL-Objekt-Namespace zwischen Game-Fenster B und Studio-Fenster A) sowie Standalone-Modus (`initStandaloneWindow`), inklusive HiDPI-Framebuffer-Tracking und per-Window-`GLCapabilities`.
* `SceneGraph`, `SceneLayer` & `SceneNode`: Z-sortierter Szenengraph mit 8 vorregistrierten Standard-Ebenen (`-100` bis `1000`), `ScreenShake` auf Weltebenen (`< Z_SCREENS`), 2.5D-Maus-Parallax, hierarchischer Vererbung (Bounds, Alpha, `ColorMatrix4f`), SDF-Rounded-Rectangles/Borders, Hex-Farbparser (`parseHexRgba`) und `onCustomRender`-Hook.
* `RenderPipeline2D`: Produktive OpenGL-3.3-Core-2D-Pipeline mit `1920×1080`-Offscreen-FBO, Premultiplied-Alpha-Blending (`GL_ONE, GL_ONE_MINUS_SRC_ALPHA`), SDF-Rechtecken, texturierten Quads und Letterbox-/Pillarbox-Blit auf `#0d0e15` (`computeLetterboxViewport`).
* `RenderPipeline3DStub`: Architektonischer 3D-Stub (`FOV = 75.0f`, `Z_NEAR = 100.0f`, `Z_FAR = 100000.0f`, `computeCameraDistanceZ`) mit kontrolliertem Warning-Logging über `EngineLogger`.
* JUnit-6-Tests in `src/test/java/de/swfdtf/graphics/` (`SceneGraphTest`, `RenderPipeline2DMathTest`, `RenderPipeline3DStubTest`) angelegt.

### Dokumentation
* `src/main/java/de/swfdtf/AGENT_CONTEXT.md` und `src/main/java/de/swfdtf/graphics/AGENT_CONTEXT.md` neu erstellt.
* `src/main/java/de/swfdtf/core/AGENT_CONTEXT.md` um `EngineApplication`, `EngineApplication.Updatable` und interne Paketabhängigkeiten ergänzt.
* `docs/ARCHITEKTUR.md`, `docs/API_REFERENZ.md` und `docs/CHANGELOG_DEV.md` auf den abgeschlossenen Stand von Meilenstein M1 aktualisiert.

---

## [M1 — Start] Fundament: Gradle-Build, `math`, `core` & `git` *(aktueller Arbeitsstand vor M1-Commit)*

### Build- & Projekt-Setup (`build.gradle`, `settings.gradle`)
* Java 21 Toolchain (`sourceCompatibility` / `targetCompatibility = 21`) mit UTF-8 Compiler-Encoding.
* LWJGL `3.3.6` BOM und Plattform-Natives-Erkennung (`lwjgl`, `lwjgl-glfw`, `lwjgl-opengl`, `lwjgl-openal`, `lwjgl-stb`) für Windows, macOS (inkl. `-XstartOnFirstThread`) und Linux (x64 & arm64).
* Abhängigkeiten: `org.joml:joml:1.10.8`, `com.google.code.gson:gson:2.11.0`, `org.junit:junit-bom:6.0.0` (JUnit Jupiter).
* Gradle-Tasks in Gruppe `kagayaki`: `runStudio`, `runStandalone`, `packageStandalone` (schließt `StudioWindowController*` und `**/AGENT_CONTEXT.md` aus).

### Paket `de.swfdtf.math`
* `Vec2f`: Unveränderlicher 2D-Vektor mit `ZERO`, `ONE`, `HALF`, `add`, `sub`, `scale`, `lerp`, `length`, `distance`.
* `Rect2f`: Achsenparalleles Rechteck mit `right`, `bottom`, `centerX`, `centerY`, `contains`, `intersects`, `translate`, `inset`.
* `ColorMatrix4f`: Premultiplied-Alpha-4×4-Matrix (Column-Major) mit `identity`, `ofColumnMajor`, `ofRowMajor`, `brightness`, `contrast`, `saturation` (Rec. 709), `hueRotate` (Rodrigues), `tint`, `opacity`, `invert`, `multiply`, `lerp`, `toFloatArray`, `applyPremultiplied`.
* `Easing` & `Easing.EaseType`: `linear`, `ease`, `easeIn`, `easeOut`, `easeInOut`, `smoothstep`, `cubicBezier`, `evaluate`.
* `Transform2D`: Ren'Py-kompatibles 2D-Transformationsmodell (`xpos`/`ypos`, `xanchor`/`yanchor`, `xalign`/`yalign`, `zoom`/`xzoom`/`yzoom`, `rotate`, `alpha`, `blur`, `colorMatrix`, `crop`, `parallaxX`/`parallaxY`, `copy`, `lerpTo`, `resolveBounds`).
* `ScreenShake`: Gedämpfte harmonische Oszillation (`vpunch`, `hpunch`, `custom`) mit Blueprint-Defaults (`A = 30.0f`, `lambda = 5.0f`, `omega = 40.0f`, `durationSeconds = 1.0f`).
* JUnit-6-Tests in `src/test/java/de/swfdtf/math/` angelegt.

### Paket `de.swfdtf.core`
* `EngineMode`: `STUDIO_DUAL_WINDOW`, `STANDALONE_GAME`.
* `EngineConfig` (+ `EngineConfig.Builder`): Unveränderliche Engine-Konfiguration mit CLI-Parser `fromArgs` (`--studio`, `--standalone`, `--project`, `--mode`/`--render-mode`, `--2d`/`--3d`, `--width`, `--height`, `--vsync`/`--no-vsync`, `--fps`/`--target-fps`, `--smoke-test`, `--headless`).
* `ProjectDescriptor`: Gson-Persistenz für `project.vjava.json` (`load`, `save`, `createDefault`, `normalizeDefaults`).
* `EngineLogger` (+ `Level`, `LogEntry`): Thread-sicherer Ringpuffer-Logger (`DEFAULT_CAPACITY = 2000`) mit Listener-Fan-Out und optionaler Konsolenausgabe.
* `GameClock`: Deterministische 60-Hz-Fixed-Timestep-Simulation (`FIXED_TIMESTEP = 1.0 / 60.0`, `MAX_FRAME_DELTA = 0.25`, `MAX_STEPS_PER_FRAME = 15`), Pause, `timeScale`, Interpolations-Alpha und FPS-Messung.
* `InputManager`: Zentralisiertes GLFW-Input-Tracking (Tastatur, Modifier, Unicode-Texteingabe via `drainTypedChars`, Letterbox-Viewport-Mausprojektion, Scroll-Deltas, Gamepads mit Deadzone) inklusive Headless-Test-Methoden.
* `EngineAPI`: Öffentliche Skript-/UI-Fassade (`ENGINE_VERSION = "KAGAYAKI 1.0.0"`, `RENDER_BACKEND_NAME = "LWJGL 3 / OpenGL 3.3 Core"`) mit Properties und typisiertem Service-Locator.
* `StudioBridge`: Entkopplungsschnittstelle zwischen Core-Runtime und Studio-UI (`tools`).
* JUnit-6-Tests in `src/test/java/de/swfdtf/core/` angelegt.

### Paket `de.swfdtf.git`
* `GitController` (+ `CommandResult`): Nicht-interaktive Git-CLI-Steuerung (`init`, `status`, `add`, `commit`, `push`, `log`, `isGitRepository`) mit `GIT_TERMINAL_PROMPT=0`, 30-Sekunden-Default-Timeout und erzwungener Commit-Policy (`ALLOWED_COMMIT_TYPES`: `feat`, `fix`, `arch`, `render`, `parser`, `audio`, `ui`, `docs`, `test`, `build`).
* JUnit-6-Tests in `src/test/java/de/swfdtf/git/` angelegt.

### Dokumentation
* Lokale `AGENT_CONTEXT.md` für `de.swfdtf.math`, `de.swfdtf.core` und `de.swfdtf.git` erstellt.
* Globale Dokumentation in `/docs/` (`ARCHITEKTUR.md`, `VJAVA_SPEZIFIKATION.md`, `CHANGELOG_DEV.md`, `API_REFERENZ.md`) initialisiert.

---

## [Initial] Projekt-Initialisierung
* Commit `178919a`: `Created Project KAGAYAKI` (Basis-Repository auf `main`).
