# KAGAYAKI — Entwicklungs-Changelog

Dieses Dokument protokolliert chronologisch abgeschlossene Teilmodule, Architektur-Änderungen und Meilensteine von **KAGAYAKI** (`de.swfdtf`, Repository: `https://github.com/SWFDtf/KAGAYAKI`).

---

## [Welle 4 — Abgeschlossen (60 / 60 Features)] Schneewittchen-Demo-Integration (`demo/`)

* **`de.swfdtf.demo.features` (`Wave4DemoProgressionTest`)**:
  1. `SnowWhiteChapterCatalog` (7 Schneewittchen-Kapitel in `ChapterRegistry` + Label-Unlock).
  2. `SnowWhiteCodexCatalog` (8 Märchen-Codex-Einträge über `CodexGlossaryManager`).
  3. `SnowWhiteAffinityDirector` (Meilenstein-gesteuerte Zuneigung für `jaeger`, `zwerge`, `prinz`, `koenigin`).
  4. `SnowWhiteQuoteBook` (deduplizierte, durchsuchbare Zitat-Lesezeichen mit atomarer JSON-Persistenz).
  5. `SnowWhiteReadingStats` (Lese-Fortschritt kombiniert mit `ScriptWordCountAnalyzer`).
* **`de.swfdtf.demo.director` (`Wave4DemoDirectorTest`)**:
  6. `SnowWhiteWeatherDirector` (Kapitel-Wetter-Choreographie über `WeatherOverlayController`).
  7. `SnowWhiteCameraChoreographer` (Kapitel-Kamera- & Letterbox-Presets).
  8. `SnowWhiteAudioShowcase` (Audio-Cues, Ducking, Ambient-Crossfade, Captions & Voice-Bookmarks).
  9. `SnowWhiteAccessibilityProfile` (Presets `STANDARD`, `HIGH_CONTRAST`, `AUTO_CINEMA` inkl. JSON-Save/Load).
  10. `SnowWhiteScreenshotShowcase` (prozedurale Kapitel-Vorschau-PNGs über `ScreenshotCaptureService` ohne `java.awt`).
* **`de.swfdtf.demo.qa` & `demo.Main` (`Wave4DemoQaTest`)**:
  11. `SnowWhitePlaythroughSuite` (Automatisierte Playthroughs über alle 3 `ChoiceStrategy`-Modi).
  12. `SnowWhiteFlowAndTranslationReport` (Mermaid/DOT, Übersetzungsabdeckung & Asset-Audit als Markdown).
  13. `SnowWhiteIntegrityAuditor` (SHA-256-Manifest, Asset-Metadaten, Pre-Flight & `.vpak`-CRC32-Prüfung).
  14. `SnowWhiteDiagnosticExporter` (Profiling, Watchpoints, State-Diff & JSON-Diagnosebundle).
  15. `SnowWhiteShowcaseCli` & CLI-Flags `--showcase`, `--preflight`, `--flow-graph` + Gradle-Task `runDemoShowcase`.

---

## [Welle 3 — Abgeschlossen] 15 Gameplay-, Grafik-, Audio- & Accessibility-Features

* **`de.swfdtf.graphics` (`Wave3GraphicsFeaturesTest`)**:
  1. `WeatherOverlayController` (dynamische Wetter-Presets `CLEAR`/`RAIN`/`SNOW`/`FOG`/`EMBERS`, Wind, Nebel-Tint & `ParticleSystem`-Sync).
  2. `ColorBlindnessFilter` (`ColorMatrix4f`-Barrierefreiheitsfilter für Protanopie, Deuteranopie, Tritanopie, High-Contrast & Grayscale).
  3. `CameraDirectorPresets` (kinematische Kamera-Presets mit `Transform2D`-Interpolation).
  4. `ScreenshotCaptureService` (pure STB-PNG-Aufnahme über `TextureManager.writePng` ohne `java.awt`).
  5. `CinematicLetterboxController` (animierte Kinobalken im `1920x1080`-Raum).
* **`de.swfdtf.audio` (`Wave3AudioFeaturesTest`)**:
  6. `AudioDuckingController` (Attack/Release-BGM-Absenkung bei aktiver Sprachausgabe).
  7. `AmbientCrossfadeMixer` (mehrschichtiger Ambient-Soundscape-Mixer mit Presets).
  8. `ClosedCaptionQueue` (Untertitel-Warteschlange für `DIALOGUE`/`SFX`/`MUSIC`).
  9. `AudioCueRegistry` (logische Audio-Cues mit Gson-JSON-Export/-Import).
  10. `VoicePlaybackBookmark` (begrenzter Voice-Line-Wiedergabeverlauf nach Zeile und Sprecher).
* **`de.swfdtf.state` (`Wave3GameplayFeaturesTest`)**:
  11. `AutoReadController` (dynamisches CPS-Auto-Read mit `waitForVoice`).
  12. `SkipModeController` (`OFF`/`READ_ONLY`/`ALL` mit `stopOnChoice`).
  13. `ChapterRegistry` (Kapitelauswahl & Freischaltung über `PersistentAccess`).
  14. `CodexGlossaryManager` (Codex/Glossar mit Suche, Kategorien & `PersistentAccess`).
  15. `RelationshipAffinityTracker` (Charakter-Zuneigungsstufen `HOSTILE`..`DEVOTED`, Events & optionale `StateStore`-Bindung).

---

## [Welle 2 — Abgeschlossen] 15 Studio-Tooling-, Analyse- & Authoring-Features (`de.swfdtf.tools`)

* **Analyse & Suche (`Wave2AnalysisToolsTest`)**:
  1. `ScriptFlowGraphExporter` (Mermaid `flowchart TD` & Graphviz DOT aus `ProjectAst` inkl. unerreichbarer Labels).
  2. `ScriptWordCountAnalyzer` (Wort-/Zeichen-/Dialogstatistik pro Sprecher & geschätzte Lesezeit).
  3. `UnusedAssetDetector` (Cross-Referenzierung von `assets/**` gegen Skript-, `LayeredImage`- & `Screen`-Referenzen).
  4. `TranslationCoverageAuditor` (Übersetzungsabdeckung je Sprache inkl. fehlender Texte).
  5. `ProjectSearchService` (Volltext- und Regex-Suche über `**/*.vjava`).
* **Authoring, Formatierung, Diffing & Lesezeichen (`Wave2AuthoringToolsTest`)**:
  6. `VJavaScriptFormatter` (4-Space-Normalisierung unter Erhalt von `@->{ ... }<<`-Blöcken).
  7. `StateSnapshotDiffService` (strukturierter Vergleich zweier State-Maps oder `SaveSlotData`).
  8. `AssetMetadataInspector` (PNG-`IHDR`- & WAV-`RIFF`-Inspektion ohne `java.awt`).
  9. `LabelBookmarkManager` (kategorisierte Label-Lesezeichen mit atomarer JSON-Persistenz).
  10. `VJavaLinterAutoFixer` (idempotente Auto-Fixes für fehlende Sprecher & `jump`/`call`-Stubs).
* **Automatisierung, Palette & Release-Prüfung (`Wave2ReleaseToolsTest`)**:
  11. `AutomatedPlaythroughRunner` (Headless-Playthrough-Bot mit `ChoiceStrategy` & Label-Coverage).
  12. `StudioCommandPalette` (durchsuchbare Befehlspalette mit Verlauf).
  13. `ExportPreFlightChecker` (Release-Checkliste aus `ProjectDescriptor`, `VJavaLinter`, Assets & Übersetzungen).
  14. `VpakManifestInspector` (vollständige `.vpak`-CRC32-Verifikation & Extension-Größenbericht).
  15. `StudioWorkspaceState` (atomare JSON-Persistenz für Studio-Workspace-Präferenzen).

---

## [Welle 1 & GUI-/Demo-Reparatur — Abgeschlossen] Dual-Window-Reparatur & 15 Fundament-Features

### GUI-, Kontext- & Einstiegspunkt-Reparaturen (`main` & `demo`)
* `EngineApplication`: Sauberes OpenGL-Kontext-Switching (`studioWindow.makeCurrent()` / `gameWindow.makeCurrent()`) bei Studio-Bridge-Initialisierung, Render-Pipeline-Umschaltung und `close()`, damit VAOs/FBOs von Fenster A und Fenster B strikt im jeweiligen OpenGL-Kontext leben.
* `StudioWindowController`: Eigenen `InputManager` (`getStudioInput()`) für Fenster A mit Retina/HiDPI-Viewport-Skalierung an `GLFW` und `ScreenManager` verdrahtet, sodass Studio-Toolbar und Panels auf Klicks reagieren.
* `Main` & `StandaloneMain`: Automatischer Projekt-Fallback auf `demo/project`, falls kein explizites `--project` / `--standalone` übergeben wurde und im Arbeitsverzeichnis keine `project.vjava.json` liegt.
* `build.gradle`: `mockito-core` & `mockito-junit-jupiter` (`5.14.2`) ergänzt; verifiziert durch `MainEntryPointsTest` und erweiterte Tests in `EngineApplicationTest` & `EndToEndEngineTest`.

### 15 Neue Fundament-Features (Welle 1)
* **`de.swfdtf.core` (`Wave1CoreFeaturesTest`)**:
  1. `EngineEvent`, `EventSubscription`, `EngineEventBus` (typisierter Pub/Sub-Bus mit Prioritäten & Fehlerisolation).
  2. `EngineCommand`, `CommandStack` (Undo/Redo-Verlauf mit Max-Tiefe & `Optional<String>`-Vorschau).
  3. `EnginePlugin`, `PluginRegistry` (Plugin-Lebenszyklus gegen `EngineAPI`).
  4. `KeybindingRemapper` (konfigurierbare GLFW-Tastenbelegung mit Konflikterkennung, Swap & JSON-Export/-Import).
  5. `GamepadNavigationRouter` (D-Pad/Stick-UI-Navigation mit Deadzone & Repeat-Timing).
* **`de.swfdtf.parser` (`Wave1ParserFeaturesTest`)**:
  6. `ScriptProfiler` (Label-/Statement-Timing-Profiling, Hit-Counts, `hottestLabel()`, Bericht).
  7. `WatchpointEvent`, `VariableWatchpointManager` (`StateStore`-Überwachung mit Prädikaten & Historie).
  8. `MacroRegistry` (`{{macro}}`-Ersetzung mit Zyklus- & Tiefenerkennung).
  9. `TypeSafeStateView` (typsichere `Optional`-Lese-Fassade inkl. `BigDecimal`).
  10. `HotReloadWatcher` (nicht-blockierender Dateisystem-Poller für `.vjava` und `project.vjava.json`).
* **`de.swfdtf.state` (`Wave1StateFeaturesTest`)**:
  11. `AutoSaveScheduler` (rotierende Auto-Saves nach Zeit oder Dialogschritten).
  12. `SaveSlotQueryService` (Tagging, Suche, Filterung & Sortierung über `SaveSlotData`).
  13. `SaveMigrationStep`, `SaveMigrationService` (versionierte Save-Migrations-Pipeline).
* **`de.swfdtf.tools` (`Wave1ToolsFeaturesTest`)**:
  14. `AssetChecksumVerifier` (SHA-256-Manifest & Integritätsprüfung für `assets/**`).
  15. `DiagnosticBundleExporter` (atomarer JSON-Diagnoseexport mit Log, Config, Lint & Metriken).

---

## [M7 — Abgeschlossen] Dual-Window-Studio, Live-Regisseur, Entwickler-Inspektoren, VJavaLinter, Starter-Generator, VPAK-Export & Gesamtintegration

### Erweiterung `de.swfdtf`, `de.swfdtf.core` & `de.swfdtf.ui`
* `Main`: Um `--create-template <dir>` / `--create-template=<dir>` erweitert; generiert über `ProjectTemplateGenerator` ein vollständiges Starterprojekt und übergibt `--project <dir>` an `EngineConfig.fromArgs(...)`.
* `GameHost` (`de.swfdtf.core`): Neues Lifecycle-Interface (`init`, `update`, `renderUi`, `onReload`, `dispose`), das `EngineApplication` von der Spiel-Laufzeit in `de.swfdtf.ui` entkoppelt.
* `EngineApplication`: Um `EngineApplication(EngineConfig, StudioBridge, GameHost)`, `getGameHost()`, automatische Reflection-Instanziierung von `GameRuntimeCoordinator` (sofern `project.vjava.json` vorhanden) sowie Integration von `gameHost.update`/`renderUi`/`onReload`/`dispose` in den 60-Hz-Loop erweitert.
* `GameRuntimeCoordinator` (`de.swfdtf.ui`, `implements GameHost`): Verdrahtet `ProjectScanner`, `ScriptSession`, `AudioEngine`, `LipSyncAnalyzer`, `TextToSpeechService`, `AnimationEngine`, `TransitionManager`, `ScreenManager`, alle 3 Dialog-Modi, alle eingebauten System-/Meta-Screens, globale Spiel-Shortcuts und (im Studio-Modus) `LiveDirectorOverlay` sowie `DeveloperInspectors`.

### Paket `de.swfdtf.tools`
* `ProjectTemplateGenerator`: Erzeugt ohne `java.awt` lauffähige, Lint-saubere Starterprojekte (`project.vjava.json`, `main.vjava` mit eingebetteten Java-Klassen, `layeredimage` inkl. Lip-Sync-`mouth`-Gruppe und Verzweigung, `ui/screens.vjava`, `tl/english/translations.vjava` sowie prozedurale PNG- und WAV-Assets).
* `VJavaLinter` (+ `Severity`, `LintIssue`, `LintReport`): Statischer Projekt-Validator für `MISSING_ENTRY_LABEL`, `MISSING_JUMP_TARGET`, `MISSING_CALL_TARGET`, `MISSING_MENU_TARGET`, `MISSING_TIMEOUT_TARGET`, `UNREACHABLE_LABEL`, `MISSING_ASSET`, `UNDEFINED_SPEAKER`, `DUPLICATE_DEFINITION`, `PARSE_ERROR` und In-Memory-Java-21-Kompilierung (`JAVA_COMPILE_ERROR`).
* `VpakArchiver` (+ `VpakEntryInfo`, `VpakArchive`): Binärer `.vpak`-Container (`MAGIC = "VPAK"`, `FORMAT_VERSION = 1`) mit Deflate-Kompression, CRC32-Integritätsprüfung, Pfad-Traversal-Schutz beim Entpacken (`extractAll`) und deterministischer Archivierungsreihenfolge.
* `StandaloneBuilder` (+ `BuildResult`): Exportiert lint-geprüfte Projekte mit `game.vpak`, `run_game.sh` (inkl. `-XstartOnFirstThread` auf macOS), `run_game.bat` (`de.swfdtf.StandaloneMain --standalone .`), optionalem `kagayaki-standalone.jar` und `standalone-packaging.json` (`jlink`/`jpackage`-Deskriptor).
* `LiveDirectorOverlay` (`extends Screen`, `Z = 1000`, `D`-Toggle): Live-Bühneninspektor für aktive Sprites, Attribute und Transform-Slider (`xpos`, `ypos`, `zoom`, `alpha`) inklusive atomarem Zurückschreiben von `show <tag>` in `.vjava`-Dateien (`updateShowLineInScript`).
* `DeveloperInspectors` (`extends Screen`, `Z = 1000`): Live-Reload (`Shift+R`), Style-Inspector (`Shift+I`, `StyleInspectionResult`), Coordinate-Picker (`Shift+C`, `PickedCoordinate`), Live-Java-Konsole (`Shift+O`, `ConsoleExecutionResult`) und Label-Warping (`warpToLabel`).
* `StudioWindowController` (`implements StudioBridge`): Dual-Window-Studio-Controller für Fenster A mit Toolbar, Projekt-Explorer, Lint-Zusammenfassung, Live-Variableninspektor, `EngineLogger`-Konsole, 2D/3D-RenderMode-Umschaltung und Standalone-Export.
* JUnit-6-Tests in `src/test/java/de/swfdtf/tools/` (`DirectorAndInspectorsTest`, `ToolsPackagerAndTemplateTest`, `EndToEndEngineTest`).

### Dokumentation
* `src/main/java/de/swfdtf/tools/AGENT_CONTEXT.md` neu erstellt.
* `src/main/java/de/swfdtf/AGENT_CONTEXT.md`, `src/main/java/de/swfdtf/core/AGENT_CONTEXT.md` und `src/main/java/de/swfdtf/ui/AGENT_CONTEXT.md` auf den finalen M7-Stand aktualisiert.
* `docs/ARCHITEKTUR.md`, `docs/API_REFERENZ.md`, `docs/VJAVA_SPEZIFIKATION.md` und `docs/CHANGELOG_DEV.md` auf den abgeschlossenen Projekt-Endstand von Meilenstein M7 aktualisiert.

---

## [M6 — Abgeschlossen] `LayeredImage`, Dark-Slate-UI-System, `ScreenAstBuilder`, 3 Dialog-Modi & Eingebaute System-/Meta-Screens

### Erweiterung `de.swfdtf.graphics` & `de.swfdtf.parser`
* `LayeredImage` (+ `LayerRenderInfo`, `Builder`, `implements CustomDisplayable`): Mehrstufiges Composite-Sprite aus `VJavaAst.LayeredImageDef` oder `Builder` mit `always`-, `group`- (inkl. `0.20s`-Crossfade bei Attributwechsel und `LipSyncAnalyzer`-Anbindung auf Gruppe `"mouth"`) und bedingten `if`-Ebenen.
* `SceneController`: Um `registerLayeredImage`, `getLayeredImageDefinition`, `setStateContext(StateStore, PersistentAccess)` und `update(double dt)` erweitert; `showSprite(...)` erzeugt oder aktualisiert bei registrierter `LayeredImageDef` direkt einen `LayeredImage`-basierten `SpriteNode`.
* `ScriptRuntime`: Verdrahtet `StateStore`/`PersistentAccess` (`setStateContext`) und `projectAst.layeredImages()` (`registerLayeredImage`) automatisch mit `SceneController`.
* JUnit-6-Test in `src/test/java/de/swfdtf/graphics/LayeredImageTest.java` (4 Tests).

### Paket `de.swfdtf.ui`
* **Kern & Theme**: `ThemePalette` (Dark-Slate-Hexkonstanten `#0d0e15`–`#f85149` + RGBA-/Premultiplied-Helfer), `UiNode`, `Screen` (+ `KeyBinding`, `ScreenTimer`), `ScreenManager` (Z-sortierter Screen-Stack, Modal-Blockierung, `Tab`/`Shift+Tab`-Focus-Traversal, `InputManager`-Routing) sowie paketinterne Helfer `UiRenderSupport` (`ScissorState` via `GL11.glScissor`, Scrollbar, Headless-`FontManager`-Fallback) und `UiMediaSupport` (`PreviewImageNode` mit STB-PNG-Dekodierung).
* **Layout-Container**: `VBox`, `HBox`, `Grid`, `Fixed` (+ `AlignmentSpec`), `Frame` (Dark-Slate oder `NineSliceDrawable`), `WindowBox`, `Viewport` (Scissor-Clipping + Scrollbar-Dragging) und `VpGrid` (Row-Virtualisierung `[firstVisibleRow..lastVisibleRow]`).
* **Widgets & AST-Builder**: `TextWidget` (`TextTagParser` + `StyledTextLayout` + `TypewriterController`), `TextButton`, `ImageButton`, `Hotspot`, `Hotbar`, `Imagemap`, `Bar`, `VBar`, `Slider`, `TextInput` (`allowChars`/`maxLength`/Caret), `DragItem`, `DragGroup`, `CustomDisplayableWidget` sowie `ScreenAstBuilder` (`screen`-AST-Instanziierung mit `if`/`elif`/`else`, `key`/`timer`, `VariableValue`/`VariableInputValue`, `custom`-Displayables und Action-Dispatcher).
* **3 Dialog-Modi & In-Game-Overlays**: `AdvDialogueScreen` (`(140, 790, 1640, 250)`, `PreferencesState`-CPS, `0.055s`-Voice-Bleep-Drosselung, Auto/Skip/TTS, QuickMenu-Leiste), `NvlDialogueScreen` (Vollbild-`Viewport` aus `ScriptRuntime.getNvlLines()`), `BubbleDialogueScreen` (+ `BubbleLayoutConfig`, `BubbleEntry`, dynamischer Sprite-Anker-Tail, Retain-Modus, `Shift+B`-Layout-Editor mit `saves/bubble_layouts.json`), `QuickMenuScreen` (`Z = 210`), `ChoiceMenuScreen` (inkl. Timeout-`Bar`) und `InputOverlayScreen`.
* **Eingebaute System-, Meta- & Toast-Screens**: `MainMenuScreen` (inkl. automatischem `Continue` über neuesten Save-Slot), `SaveLoadScreen` (5×6 manuelle Slots + `QUICK`/`AUTO`-Tabs, Custom-Namen, STB-PNG-Thumbnails, Löschen), `PreferencesScreen` (Audio/Text/Modus/Sprache/RenderMode/TTS/Dyslexie/UI-Scale), `ControlsScreen` (`ALL`/`KEYBOARD`/`MOUSE`/`GAMEPAD`), `AboutScreen`, `HistoryLogScreen` (`Replay Voice` + `Jump Back`), `GalleryScreen` (`VpGrid` + Variationen + Vollbild-Zoom), `MusicRoomScreen` (16-Band-`VBar`-Spektrum), `EndingsTreeScreen` (Eltern-Kind-Routenbaum + Completion-`Bar`), `AchievementsScreen` (Maskierung versteckter gesperrter Achievements als `"???"`) und `NotificationToastOverlay` (`AchievementManager.pollToast()` + `SKIPPING >>`/`AUTO >`-Status-Pill).
* JUnit-6-Tests in `src/test/java/de/swfdtf/ui/` (`UiLayoutAndWidgetsTest`, `DialogueModesTest`, `BuiltInScreensTest`) — insgesamt **157** grüne Tests in **43** Suites laut `build/test-results/test/` über alle Pakete (`audio`, `core`, `git`, `graphics`, `math`, `parser`, `state`, `ui`).

### Dokumentation
* `src/main/java/de/swfdtf/ui/AGENT_CONTEXT.md` neu erstellt.
* `src/main/java/de/swfdtf/graphics/AGENT_CONTEXT.md` (`LayeredImage`, `SceneController`) und `src/main/java/de/swfdtf/parser/AGENT_CONTEXT.md` (`ScriptRuntime`) aktualisiert.
* `docs/ARCHITEKTUR.md`, `docs/API_REFERENZ.md`, `docs/VJAVA_SPEZIFIKATION.md` und `docs/CHANGELOG_DEV.md` auf den abgeschlossenen Stand von Meilenstein M6 aktualisiert.

---

## [M5 — Abgeschlossen] OpenAL-Audio-Mixer, DSP, Lip-Sync, TTS, ATL-Animationen, Dual-FBO-Transitions, Partikel & Video-Playback

### Paket `de.swfdtf.audio`
* `AudioDsp` (+ `FilterType`, `BiquadFilter`): Direct-Form-II-Transposed Second-Order-IIR-Biquad-Filter (`createLowpass`, `createHighpass`, `createBandpass`), RMS-Berechnung (`computeRms`), Hann-gefensterte logarithmische Radix-2-DFT-Spektrumbänder (`computeSpectrumBands`) und Goertzel-Analyse (`computeGoertzelMagnitude`).
* `AudioDecoder` (+ `DecodedAudio`): Dekodierung von `.ogg` (`STBVorbis`) und `.wav` (8/16-Bit PCM, 32-Bit IEEE-Float), 16-Bit-WAV-Encoder (`encodeWav`), Sample-Konverter (`floatToPcm16`, `pcm16ToFloat`) sowie prozedurale Synthese (`synthesizeTone`, `synthesizeChord`, `synthesizeVoiceBleep`).
* `AudioChannel` (+ `ChannelSnapshot`): Benannter Mixer-Kanal mit Fade-In/Fade-Out, Dual-Slot-Crossfading, FIFO-Queue, optionalem `BiquadFilter`, Echtzeit-RMS/-Spektrum, optionaler OpenAL-Source-Verwaltung und Rollback-Snapshots.
* `AudioEngine`: Zentraler OpenAL-Audio-Manager (`implements AutoCloseable, ScriptRuntime.AudioBridge`) für `music`, `sound`, `voice`, `ambient` und benutzerdefinierte Kanäle mit Headless-Virtual-Mixer-Fallback, `PreferencesState`-Lautstärkekopplung, `LocalizationManager`-`tl/<lang>/`-Auflösung, `playVoiceBleep` und `snapshotAllChannels`/`restoreAllChannels`.
* `LipSyncAnalyzer` (+ `MouthShape`): Attack/Release-Hüllkurven-Glättung vom Voice-RMS (`AudioEngine.getActiveVoiceRms()`) zu `openness` (`[0, 1]`) und `MouthShape` (`CLOSED`, `HALF_OPEN`, `OPEN`).
* `TextToSpeechService`: Nicht-blockierender Virtual-Thread-Self-Voicing-Dienst (`implements AutoCloseable`) mit `TextTagParser`-Markup-Bereinigung (`stripRichTextTags`) und plattformspezifischer OS-Sprachausgabe (`say`, `spd-say`/`espeak`, PowerShell `SpeechSynthesizer`).
* JUnit-6-Tests in `src/test/java/de/swfdtf/audio/` (`AudioDecoderAndChannelTest`, `AudioDspTest`, `AudioEngineAndLipSyncTest`).

### Erweiterung `de.swfdtf.graphics`
* `ATLTransform` (+ `Builder`): Ausführbare ATL-Transformations-Timelines aus `VJavaAst.AtlStep` / `TransformDef` (`fromAst`) oder Fluent-`Builder` mit `set`, `linear`/`ease*`/`cubicBezier`-Interpolation, `pause`, `repeat`, `parallel`, `block`, `choice`, `crop` und `matrixcolor`-Ausdrücken (`parseColorMatrix`) auf `Transform2D`.
* `AnimationEngine`: Zentraler Timeline-Manager für aktive, per ID an `SceneNode`s gebundene `ATLTransform`-Instanzen inklusive Abschluss-Callbacks.
* `Transition` (+ `Kind`): Unveränderlicher Deskriptor und Ausdrucksparser (`Transition.parse`) für 20 Übergangstypen (`NONE`, `DISSOLVE`, `FADE`, `PIXELLATE`, `MOVE`, `EASE`, `WIPE_*`, `SLIDE_*`, `PUSH_*`, `IRIS_*`, `BLINDS`, `SQUARES`, `IMAGE_DISSOLVE`, `MULTIPLE`).
* `TransitionManager`: Headless-Transitions-Timeline-State-Machine (inklusive `MULTIPLE`-Sub-Step-Tracking) und OpenGL-3.3-Core-Dual-FBO-Shader-Compositor (`initGl`, `captureFromFbo`, `renderComposite`, `disposeGl`).
* `ParticleSystem` (+ `EmitterKind`, `Particle`, `ParticleEmitter`): GPU-batchbares und headless simulierbares 2D-Partikelsystem (`implements CustomDisplayable`) mit 5 Presets (`snowBlossom`, `rain`, `cherryBlossoms`, `sparks`, `dust`) zur Anbindung an `SceneGraph.LAYER_FOREGROUND`.
* `VideoPlayer` (+ `State`, `FrameSource`, `ProceduralTestFrameSource`, `FfmpegProcessFrameSource`) & `MovieSprite`: FPS-genauer Video-Playback-Controller mit Virtual-Thread-FFmpeg-Streaming und Szenengraph-Sprite-Wrapper (`extends SpriteNode`).
* JUnit-6-Tests in `src/test/java/de/swfdtf/graphics/` (`ATLAndAnimationTest`, `ParticleAndVideoTest`, `TransitionManagerTest`) — insgesamt **140** grüne Tests laut `build/test-results/test/` über alle Pakete (`audio`, `core`, `git`, `graphics`, `math`, `parser`, `state`).

### Dokumentation
* `src/main/java/de/swfdtf/audio/AGENT_CONTEXT.md` neu erstellt.
* `src/main/java/de/swfdtf/graphics/AGENT_CONTEXT.md` um `ATLTransform`, `AnimationEngine`, `Transition`, `TransitionManager`, `ParticleSystem`, `VideoPlayer` und `MovieSprite` erweitert.
* `docs/ARCHITEKTUR.md`, `docs/API_REFERENZ.md`, `docs/VJAVA_SPEZIFIKATION.md` und `docs/CHANGELOG_DEV.md` auf den abgeschlossenen Stand von Meilenstein M5 aktualisiert.

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
