# Package `de.swfdtf.tools` — AGENT_CONTEXT

## Zweck des Pakets
Entwickler-Werkzeuge, Dual-Window-Studio-UI und Standalone-Build-Pipeline von KAGAYAKI (Meilenstein M7): `ProjectTemplateGenerator` erzeugt lauffähige Starter-Projekte inklusive prozeduraler PNG-/WAV-Assets (ohne `java.awt`); `VJavaLinter` prüft `.vjava`-Skripte statisch auf fehlende Labels/Assets/Sprecher, unerreichbare Labels und In-Memory-Java-21-Kompilierbarkeit; `VpakArchiver` packt und verifiziert Deflate-/CRC32-komprimierte `.vpak`-Archive; `StandaloneBuilder` exportiert eigenständige Game-only-Distributionen; `LiveDirectorOverlay` (`D`) und `DeveloperInspectors` (`Shift+R`, `Shift+I`, `Shift+C`, `Shift+O`) bieten In-Engine-Regie-, Live-Reload-, Style-Inspektor-, Koordinaten-Picker-, Live-Java-Konsolen- und Warp-Werkzeuge; `StudioWindowController` (`implements StudioBridge`) betreibt Studio-Fenster A.

## Abhängigkeiten zu anderen `de.swfdtf.*`-Paketen
- `de.swfdtf.core` (`EngineAPI`, `EngineApplication`, `EngineLogger`, `ProjectDescriptor`, `StudioBridge`)
- `de.swfdtf.audio` (`AudioDecoder`)
- `de.swfdtf.graphics` (`FontManager`, `RenderMode`, `RenderPipeline2D`, `SceneController`, `SceneLayer`, `SpriteNode`, `TextureManager`)
- `de.swfdtf.math` (`Rect2f`, `Transform2D`)
- `de.swfdtf.parser` (`JavaBlockCompiler`, `ProjectScanner`, `ScriptRuntime`, `ScriptSession`, `VJavaAst`, `VJavaCompileException`, `VJavaParseException`)
- `de.swfdtf.state` (`HistoryLog`, `PersistentAccess`, `SaveSlotData`, `StateStore`)
- `de.swfdtf.ui` (`Fixed`, `HBox`, `Screen`, `ScreenManager`, `Slider`, `TextButton`, `TextInput`, `TextWidget`, `ThemePalette`, `UiNode`, `VBox`, `Viewport`, `WindowBox`)
- Externe Bibliotheken: `com.google.gson.*` (`StandaloneBuilder`), `org.lwjgl.glfw.GLFW` (`LiveDirectorOverlay`, `DeveloperInspectors`).

## Enthaltene Typen

| Typ | Art | Beschreibung |
| :--- | :--- | :--- |
| `ProjectTemplateGenerator` | `final class` | Erzeugt vollständige, Lint-saubere KAGAYAKI-Starterprojekte (`project.vjava.json`, `main.vjava`, `ui/screens.vjava`, `tl/english/translations.vjava`) samt prozeduralen PNG- und WAV-Assets ohne `java.awt`. |
| `VJavaLinter` | `final class` | Statischer Projekt- und Compiler-Validator für Entry-Label, `jump`/`call`/`menu`-Ziele, Label-Erreichbarkeit, `assets/`-Existenz, Sprecherdefinitionen und `@->{ ... }<<`-Java-21-Kompilierung. |
| `VJavaLinter.Severity` | `enum` | Schweregrad einer Lint-Diagnose (`ERROR`, `WARNING`). |
| `VJavaLinter.LintIssue` | `record` | Einzelne Lint-Diagnose mit `severity`, `code`, `message`, `sourceFile` und 1-basierter `lineNumber`. |
| `VJavaLinter.LintReport` | `record` | Aggregierter Lint-Bericht mit unveränderlicher `issues`-Liste sowie `errorCount`, `warningCount`, `isClean()` und `hasErrors()`. |
| `VpakArchiver` | `final class` | Binärer `.vpak`-Packer und -Reader (`MAGIC = "VPAK"`, `FORMAT_VERSION = 1`) mit Deflate-Kompression und CRC32-Integritätsprüfung pro Eintrag. |
| `VpakArchiver.VpakEntryInfo` | `record` | Metadaten eines gepackten `.vpak`-Eintrags (`path`, `uncompressedSize`, `compressedSize`, `crc32`). |
| `VpakArchiver.VpakArchive` | `static final class` | Geöffnetes `.vpak`-Archiv-Handle (`implements AutoCloseable`) für indiziertes Lesen, Pfadprüfung und CRC32-verifiziertes Entpacken. |
| `StandaloneBuilder` | `final class` | Exportiert lint-validierte Projekte in eigenständige Standalone-Distributionen (`game.vpak`, `run_game.sh`/`run_game.bat` für `de.swfdtf.StandaloneMain --standalone .`, optionales JAR, `standalone-packaging.json`). |
| `StandaloneBuilder.BuildResult` | `record` | Zusammenfassung eines abgeschlossenen Standalone-Exports mit Ausgabepfaden und Anzahl gepackter `.vpak`-Einträge. |
| `LiveDirectorOverlay` | `class` | In-Engine-Live-Regie-Overlay (`extends Screen`, `Z = 1000`, `D`-Toggle) zur interaktiven Anpassung aktiver Bühnen-Sprites (`xpos`, `ypos`, `zoom`, `alpha`, Attribute) inklusive atomarem Zurückschreiben in `.vjava`. |
| `DeveloperInspectors` | `class` | Einheitliche Entwickler-Inspektor-Suite (`extends Screen`, `Z = 1000`) für Live-Reload (`Shift+R`), Style-Inspektor (`Shift+I`), Koordinaten-Picker (`Shift+C`), Live-Java-Konsole (`Shift+O`) und Label-Warping. |
| `DeveloperInspectors.StyleInspectionResult` | `record` | Schnappschuss-Bericht eines inspizierten `UiNode` inklusive `formatReport()`. |
| `DeveloperInspectors.PickedCoordinate` | `record` | Erfasste Koordinate im logischen `1920x1080`- und normierten `[0..1]`-Raum samt ATL-Snippets. |
| `DeveloperInspectors.ConsoleExecutionResult` | `record` | Ergebnis einer Ad-hoc-Java-Ausführung in der Live-Java-Konsole (`success`, `code`, `output`, `errorMessage`). |
| `StudioWindowController` | `final class` | Dual-Window-Studio-Controller (`implements StudioBridge`) für Fenster A mit dediziertem `InputManager`, Projekt-Explorer, Template-Generator, Live-Linter, State-Variablen-Inspektor, Konsole, 2D/3D-RenderMode-Umschalter, Live-Reload und Standalone-Exporter. |
| `AssetChecksumVerifier` | `final class` | SHA-256-Manifest-Erzeugung (`AssetManifest`) und Integritätsprüfung (`VerificationReport`) für `assets/**`. |
| `DiagnosticBundleExporter` | `final class` | Atomarer JSON-Diagnosebundle-Export (`DiagnosticReport`: Engine-Version, Konfiguration, `EngineLogger`-Einträge, optionaler `LintReport`, Metriken). |
| `ScriptFlowGraphExporter` | `final class` | Generiert gerichtete Label-/Transition-Graphen (`FlowGraph`) aus `ProjectAst` und exportiert Mermaid (`flowchart TD`) sowie Graphviz DOT. |
| `ScriptWordCountAnalyzer` | `final class` | Berechnet Wort-, Zeichen-, Dialogzeilen-Statistiken pro Sprecher und geschätzte Lesezeit unter Stripping von Rich-Text-Tags (`ScriptStatsReport`). |
| `UnusedAssetDetector` | `final class` | Vergleicht `ProjectAst.discoveredAssets()` gegen Skript-, Charakter-, `LayeredImage`- und `Screen`-Referenzen (`AssetUsageReport`). |
| `TranslationCoverageAuditor` | `final class` | Prüft Dialogzeilen gegen `TranslateBlock`-Übersetzungen pro Sprache (`CoverageReport`, `LanguageCoverage`). |
| `ProjectSearchService` | `final class` | Deterministische Volltext- und Regex-Suche über `**/*.vjava` (`SearchMatch`). |
| `VJavaScriptFormatter` | `final class` | Formatiert `.vjava`-Skripte (4-Space-Einrückung, Leerzeilen, Trailing-Whitespace) unter Erhalt von `@->{ ... }<<`-Java-Blöcken. |
| `StateSnapshotDiffService` | `final class` | Berechnet strukturierte Deltas (`StateDiff`: `added`, `removed`, `modified`) zwischen State-Maps oder `SaveSlotData`. |
| `AssetMetadataInspector` | `final class` | Liest PNG-`IHDR`-Dimensionen und WAV-`RIFF`-Metadaten (Samplerate, Kanäle, Dauer) ohne `java.awt`. |
| `LabelBookmarkManager` | `final class` | Verwaltet kategorisierte Label-Lesezeichen mit atomarer Gson-JSON-Persistenz (`LabelBookmark`). |
| `VJavaLinterAutoFixer` | `final class` | Idempotente, sichere Auto-Fixes für `UNDEFINED_SPEAKER` und fehlende `jump`/`call`-Ziel-Labels (`AutoFixResult`). |
| `AutomatedPlaythroughRunner` | `final class` | Headless-Playthrough-Bot für `ScriptRuntime` mit `ChoiceStrategy` (`FIRST_CHOICE`, `LAST_CHOICE`, `ROUND_ROBIN`) und Label-Coverage (`PlaythroughReport`). |
| `StudioCommandPalette` | `final class` | Durchsuchbare Studio-Befehlspalette (`PaletteCommand`) mit Shortcut-Metadaten und begrenzter Historie. |
| `ExportPreFlightChecker` | `final class` | Release-Vorabprüfung (`PreFlightReport`) über `ProjectDescriptor`, `VJavaLinter`, `assets/` und `tl/` als Markdown-Checkliste. |
| `VpakManifestInspector` | `final class` | Prüft alle Einträge eines `.vpak`-Archivs inklusive CRC32-Verifikation, Kompressionsrate und Extension-Statistik (`VpakInspectionReport`). |
| `StudioWorkspaceState` | `final class` | Persistiert Studio-Workspace-Einstellungen (letztes Projekt, Recent-Projects, Panel-Auswahl) atomar als JSON. |
| `PerformanceBenchmarkRunner` | `final class` | Reproduzierbare Micro-Benchmark-Suite für Lexer, Parser, Motion-Controller, SceneGraph-Traversal und Rich-Text-Layout inkl. `docs/BENCHMARKS.md`-Export (`BenchmarkMetric`, `BenchmarkSuiteReport`). |

## Öffentliche API-Signaturen

### `ProjectTemplateGenerator`
```java
public final class ProjectTemplateGenerator {
    public static Path generate(Path targetDir) throws IOException
    public static Path generate(Path targetDir, String title, String renderMode) throws IOException
}
```

### `VJavaLinter`
```java
public final class VJavaLinter {
    public enum Severity { ERROR, WARNING }
    public record LintIssue(Severity severity, String code, String message, String sourceFile, int lineNumber) {}
    public record LintReport(List<LintIssue> issues, int errorCount, int warningCount) {
        public boolean isClean()
        public boolean hasErrors()
    }

    public static LintReport lint(Path projectDir)
    public static LintReport lint(VJavaAst.ProjectAst project)
}
```

### `VpakArchiver`
```java
public final class VpakArchiver {
    public static final String MAGIC = "VPAK";
    public static final int FORMAT_VERSION = 1;

    public record VpakEntryInfo(String path, long uncompressedSize, long compressedSize, long crc32) {}

    public static List<VpakEntryInfo> pack(Path projectDir, Path outputVpak) throws IOException
    public static VpakArchive open(Path vpakFile) throws IOException

    public static final class VpakArchive implements AutoCloseable {
        public List<VpakEntryInfo> listEntries()
        public boolean contains(String path)
        public byte[] readEntry(String path) throws IOException
        public void extractAll(Path targetDir) throws IOException
        @Override public void close() throws IOException
    }
}
```

### `StandaloneBuilder`
```java
public final class StandaloneBuilder {
    public record BuildResult(
            Path outputDir,
            Path vpakFile,
            Path standaloneJar,
            Path launcherScript,
            Path packagingDescriptor,
            int packagedEntries
    ) {}

    public static BuildResult build(Path projectDir, Path outputDir, Path optionalStandaloneJar) throws IOException
}
```

### `LiveDirectorOverlay`
```java
public class LiveDirectorOverlay extends Screen {
    public static final String SCREEN_NAME = "live_director";

    public LiveDirectorOverlay(ScriptSession session)
    public LiveDirectorOverlay(ScriptSession session, ScreenManager screenManager)

    public boolean toggle()
    @Override public Screen setVisible(boolean visible)
    @Override public void onShow()
    @Override public boolean triggerKeyBindings(int key, int mods)
    public void refreshFromStage()
    public void selectSprite(String tag)
    public boolean commitCurrentSpriteEdit(String tag)
    public static boolean updateShowLineInScript(Path vjavaFile, int oneBasedLine, String tag, List<String> attributes, String positionExpr)

    public ScriptSession getSession() / public void setSession(ScriptSession session)
    public String getSelectedSpriteTag()
    public WindowBox getPanelBox()
    public Slider getXposSlider() / getYposSlider() / getZoomSlider() / getAlphaSlider()
    public TextInput getAttributesInput()
    public TextButton getApplyButton() / getRefreshButton()
    public TextWidget getStatusLabel()
    public List<TextButton> getSpriteButtons()
}
```

### `DeveloperInspectors`
```java
public class DeveloperInspectors extends Screen {
    public static final String SCREEN_NAME = "developer_inspectors";

    public record StyleInspectionResult(
            String screenName, String nodeClass, String nodeId, Rect2f bounds,
            String backgroundColorHex, String borderColorHex, float borderThickness, float cornerRadius,
            boolean visible, boolean enabled, boolean hovered, boolean focused
    ) {
        public String formatReport()
    }
    public record PickedCoordinate(float x, float y, float normalizedX, float normalizedY, String xposYposSnippet, String alignSnippet) {}
    public record ConsoleExecutionResult(boolean success, String code, String output, String errorMessage) {}

    public DeveloperInspectors(ScriptSession session)
    public DeveloperInspectors(ScriptSession session, ScreenManager screenManager)

    @Override public boolean triggerKeyBindings(int key, int mods)
    @Override public void render(RenderPipeline2D pipeline, FontManager fontManager, TextureManager textureManager, double elapsedSeconds)

    public ScriptSession triggerLiveReload()
    public ScriptSession triggerLiveReload(ScriptSession currentSession)

    public boolean toggleStyleInspector() / public void setStyleInspectorActive(boolean active) / public boolean isStyleInspectorActive()
    public StyleInspectionResult inspectAt(float lx, float ly) / public StyleInspectionResult getLastInspectedStyle()

    public boolean toggleCoordinatePicker() / public void setCoordinatePickerActive(boolean active) / public boolean isCoordinatePickerActive()
    public void updateCursorPosition(float lx, float ly)
    public PickedCoordinate pickCoordinate(float lx, float ly) / public PickedCoordinate pickCurrentCoordinate()
    public List<PickedCoordinate> getPickedHistory() / public void clearPickedHistory()
    public static String formatPosSnippet(float lx, float ly)
    public static String formatAlignSnippet(float nx, float ny)

    public boolean toggleConsole() / public void setConsoleActive(boolean active) / public boolean isConsoleActive()
    public ConsoleExecutionResult executeConsoleSnippet(String code)
    public ConsoleExecutionResult executeConsoleSnippet(ScriptSession targetSession, String code)
    public List<String> getConsoleLog() / public void clearConsoleLog()

    public void warpToLabel(String labelName) / public void warpToLabel(ScriptSession targetSession, String labelName)
    public List<String> getAvailableLabels()

    public ScriptSession getSession() / public void setSession(ScriptSession session)
    public void setLinkedDirector(LiveDirectorOverlay linkedDirector)
    public void setOnLiveReload(Consumer<ScriptSession> onLiveReload)
    public float getCursorX() / getCursorY() / getNormalizedX() / getNormalizedY()
    public WindowBox getStyleInspectorBox() / getCoordinatePickerBox() / getConsoleBox()
    public TextInput getConsoleInput() / getWarpLabelInput()
    public TextButton getConsoleExecuteButton() / getWarpButton() / getReloadButton()
}
```

### `StudioWindowController`
```java
public final class StudioWindowController implements StudioBridge {
    public StudioWindowController()
    @Override public void init(EngineAPI api, long studioWindowHandle, long gameWindowHandle)
    @Override public void update(double dt)
    @Override public void render(int windowWidth, int windowHeight)
    @Override public void onReload()
    @Override public void dispose()

    public Path generateStarterProject(Path targetDir, String title, String renderMode)
    public VJavaLinter.LintReport runLint()
    public RenderMode switchRenderMode(RenderMode targetMode)
    public StandaloneBuilder.BuildResult exportStandalone(Path outputDir, Path optionalStandaloneJar)
    public void refreshInspectors()

    public VJavaAst.ProjectAst getLastScannedAst()
    public VJavaLinter.LintReport getLastLintReport()
    public StandaloneBuilder.BuildResult getLastExportResult()
    public long getStudioWindowHandle() / public long getGameWindowHandle()
    public boolean isGlReady() / public boolean isDisposed()
    public ScreenManager getStudioScreenManager()
}
```


### Erweiterungen Studio Main Menu & Template-Vorlagen (`StudioWindowController`, `ProjectTemplateGenerator`, `VJavaLinter`)
- `ProjectTemplateGenerator.TemplateType { FULL_DEMO, STARTER, MINIMAL }`:
  - `FULL_DEMO`: Standard im Studio-Hub / „Neues Projekt“-Button; enthält `template_main_menu` (inkl. `EngineVersion`), `template_save_screen`, `template_load_screen`, `template_preferences_screen`, verzweigte Story, DE/EN-Übersetzung sowie spielbare `wasd_arena` (`player.png`, `goal_star.png`, `chime.wav`) mit `0 Errors, 0 Warnings`.
  - `STARTER`: Kompakte klassische Starter-Vorlage (Default für abwärtskompatible `generate(Path)`-Signaturen).
  - `MINIMAL`: Schlankes Grundgerüst.
- `StudioWindowController`:
  - `StudioView { MAIN_MENU, WORKSPACE }` (`MAIN_MENU_SCREEN_NAME = "studio_main_menu"`, `WORKSPACE_SCREEN_NAME = "studio_workspace"`).
  - Neue Methoden: `createNewProject(Path, String, TemplateType)`, `showMainMenu()`, `showWorkspace()`, `getActiveView()`, `getSelectedTemplateType()`, `setSelectedTemplateType(TemplateType)`, `getEngineVersionBanner()`, `getActiveProjectVersion()`, `getActiveProjectRoot()`, `getRecentProjects()`, `getStudioInput()`.
- `VJavaLinter`: Prüft zusätzlich `OnCollideStatement` (`MISSING_COLLIDE_TARGET`), `MoveStatement` (`INVALID_MOVE_DURATION`, `UNKNOWN_EASING`), `WasdInitStatement` (`INVALID_WASD_SPEED`, `INVALID_WASD_BOUNDS`) und `PhysicsInitStatement` (`INVALID_PHYSICS_CONFIG`).
