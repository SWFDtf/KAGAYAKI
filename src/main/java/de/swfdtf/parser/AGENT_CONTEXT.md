# Package `de.swfdtf.parser` — AGENT_CONTEXT

## Zweck des Pakets
Vollständige `.vjava`-Skript-Pipeline von KAGAYAKI (Meilenstein M3 + M4): einrückungsbasierter Lexer (`VJavaLexer`), rekursiv-absteigender AST-Parser (`VJavaParser` / `VJavaAst`), rekursiver Projekt- und Asset-Scanner (`ProjectScanner`), In-Memory-Java-21-Compiler über `javax.tools.JavaCompiler` für `@->{ ... }<<`-Blöcke und benutzerdefinierte Klassen (`JavaBlockCompiler`, `VJavaExecutable`, `VJavaCompileException`), Expressions- und Bedingungs-Evaluator (`ConditionEvaluator`), die nicht-blockierende State-Machine-Skript-Laufzeit (`ScriptRuntime`) sowie der M4-Sitzungskoordinator (`ScriptSession`) für 4-Tier-Persistenz, deterministisches Rollback/Roll-Forward, `HistoryLog`, Save/Load, Lokalisierung und Meta-Progression.

## Abhängigkeiten zu anderen `de.swfdtf.*`-Paketen
- `de.swfdtf.core` (`ProjectDescriptor` in `VJavaAst`, `ProjectScanner`, `ScriptSession`; `EngineAPI`, `EngineConfig` in `VJavaExecutable`, `JavaBlockCompiler`, `ScriptRuntime`, `ScriptSession`)
- `de.swfdtf.graphics` (`SceneController`, `SceneGraph`, `SpriteNode`, `TextTagParser` in `VJavaExecutable`, `JavaBlockCompiler`, `ScriptRuntime`, `ScriptSession`; `TextureManager` in `ScriptSession`)
- `de.swfdtf.math` (`Transform2D`, `ScreenShake` in `JavaBlockCompiler`, `ScriptRuntime`)
- `de.swfdtf.state` (`StateStore`, `PersistentAccess`, `SimpleStateStore`, `SimplePersistentAccess` in `VJavaExecutable`, `JavaBlockCompiler`, `ConditionEvaluator`, `ScriptRuntime`; `LocalizationManager`, `SaveSlotData` in `ScriptRuntime`, `ScriptSession`; `LocalizationManager`, `TranslationEntry` in `VJavaLocalizationBridge`; `RevertableStateStore`, `PersistentState`, `PreferencesState`, `SessionState`, `HistoryLog`, `SaveManager`, `AchievementManager`, `GalleryManager`, `EndingsManager` in `ScriptSession`)
- JDK-Module: `javax.tools.*` (`JavaCompiler`, `ForwardingJavaFileManager`, `DiagnosticCollector`) in `JavaBlockCompiler` und `VJavaCompileException`.

## Enthaltene Typen

| Typ | Art | Beschreibung |
| :--- | :--- | :--- |
| `VJavaAst` | `final class` | Unveränderliches AST-Modell für `.vjava`-Skripte (`Statement`-Hierarchie, Deklarationen, `ScriptFileAst`, `ProjectAst`). |
| `VJavaAst.Statement` | `sealed interface` | Gemeinsamer Vertrag aller ausführbaren Statements innerhalb von Labels, `if`-Zweigen und `menu`-Optionen (27 Record-Varianten). |
| `VJavaAst.JavaBlockNode` | `record` | Eingebetteter nativer Java-Block (`@->{ ... }<<`) mit eindeutiger `id`, optionaler `description`, `code`, `sourceFile` und `lineNumber`. |
| `VJavaAst.CharacterDef` | `record` | Charakterdefinition aus `define <id> = Character(...)` mit Farbe, Outline, Bild-/Voice-Tag, Textbox-Skin und Properties. |
| `VJavaAst.ImageDef` | `record` | Eigenständige Bildalias-Deklaration (`image <tag> [attrs...] = "<path>"`). |
| `VJavaAst.LayeredImageDef` | `record` | Mehrstufige Composite-Sprite-Definition (`layeredimage <name>:`) mit `AlwaysLayerEntry`, `GroupLayerEntry` (`AttributeEntry`) und `ConditionalLayerEntry`. |
| `VJavaAst.TransformDef` & `AtlStep` | `record` | Benannte ATL-Transformationsdefinition (`transform <name>:`) aus geschachtelten `AtlStep`-Animations- und Property-Schritten. |
| `VJavaAst.ScreenDef` & `ScreenItem` | `record` | Deklarative UI-Screen-Definition (`screen <name>(params):`) mit `modal`, `zorder` und rekursiven `ScreenItem`-Widgets. |
| `VJavaAst.TranslateBlock` & `TranslateEntry` | `record` | Lokalisierungsblock (`translate <lang> <id>:`) mit Original- und Übersetzungstexten. |
| `VJavaAst.LabelNode` | `record` | Benannter Skript-Einstiegspunkt (`label <name>:`) mit sequenzieller Statement-Liste. |
| `VJavaAst.ScriptFileAst` | `record` | Geparster AST einer einzelnen `.vjava`-Quelldatei vor der projektweiten Zusammenführung. |
| `VJavaAst.ProjectAst` | `record` | Global zusammengeführter Projekt-AST aus `ProjectScanner` inklusive `ProjectDescriptor`, Assets und Duplikat-Warnungen. |
| `VJavaParseException` | `class` | Ungeprüfte Ausnahme bei Lexer-, Einrückungs- oder Syntaxfehlern mit `sourceFile` und 1-basierter `lineNumber`. |
| `VJavaLexer` | `final class` | Zeilen- und einrückungsbewusster Lexer, der VN-Zeilen (`INDENT`/`DEDENT`/`LINE`) sauber von Java-Blöcken (`JAVA_BLOCK`) trennt. |
| `VJavaLexer.TokenType` | `enum` | Token-Kategorien: `INDENT`, `DEDENT`, `LINE`, `JAVA_BLOCK`, `EOF`. |
| `VJavaLexer.Token` | `final class` | Unveränderliches Token mit Typ, Quelldatei, Zeilennummer, Einrückungstiefe, Text, Java-Beschreibung und Java-Quellcode. |
| `VJavaParser` | `final class` | Rekursiv-absteigender Parser, der `Token`-Listen in `ScriptFileAst` überführt. |
| `ProjectScanner` | `final class` | Rekursiver Scanner für `**/*.vjava`, `project.vjava.json` und `assets/` mit deterministischer Sortierung und Last-Wins-Duplikatwarnungen. |
| `VJavaCompileException` | `class` | Ausnahme bei Fehlern des In-Memory-Java-Compilers inklusive zeilen-gemappter `CompileDiagnostic`-Records und generiertem Quellcode. |
| `VJavaCompileException.CompileDiagnostic` | `record` | Strukturierter Compiler-Diagnoseeintrag (`blockId`, `sourceFile`, `lineNumber`, `columnNumber`, `kind`, `message`). |
| `VJavaExecutable` | `@FunctionalInterface` | Laufzeitvertrag für jedes in-memory kompilierte `@->{ ... }<<`-Snippet (`execute(state, persistent, engine, scene)`). |
| `JavaBlockCompiler` | `final class` | In-Memory-Java-21-Compiler; sammelt Imports, benutzerdefinierte Klassen/Typen (`VJavaUserTypes`) und Snippet-Klassen (`VJavaSnippet_<i>`). |
| `JavaBlockCompiler.CompiledProjectBlocks` | `final class` | Registry aller kompilierten `VJavaExecutable`-Instanzen und des zugehörigen In-Memory-`ClassLoader` inklusive `loadUserType`/`findUserType`. |
| `ConditionEvaluator` | `final class` | Rekursiv-absteigender Ausdrucks- und Bedingungs-Evaluator für Arithmetik, Vergleiche, Logik, Ternär-Operator, Reflexions-/Getter-Zugriff, Indexierung und Methodenaufrufe. |
| `ScriptRuntime` | `final class` | Nicht-blockierende State-Machine-Skript-Laufzeit mit Dialogue/Menu/Input/Pause-Zuständen, ATL-/Scene-/`LayeredImage`-Anbindung (`SceneController.setStateContext` & `registerLayeredImage`), Audio-Bridge, Live-Lokalisierung (`LocalizationManager`), M4-Snapshot-Hooks und `SaveSlotData.SavedScriptPosition`-Serialisierung. |
| `ScriptRuntime.Status` | `enum` | Laufzeitzustände: `READY`, `WAITING_DIALOGUE`, `WAITING_MENU`, `WAITING_INPUT`, `PAUSING`, `ENDED`. |
| `ScriptRuntime.DialoguePrompt` | `record` | Aktive Dialogzeile während `WAITING_DIALOGUE` inklusive `lineId`, interpoliertem Text, Voice-Pfad, `alreadyRead` und `nvlMode`. |
| `ScriptRuntime.ChoiceOption` & `MenuPrompt` | `record` | Evaluierte Auswahloptionen (`enabled`, `visible`) und aktiver Menü-Prompt (inklusive `timeoutSeconds` / `remainingSeconds`). |
| `ScriptRuntime.InputPrompt` | `record` | Aktiver Texteingabe-Prompt (`targetVar`, `prompt`, `defaultValue`, `allowChars`, `maxLength`). |
| `ScriptRuntime.AudioBridge` & `AudioEvent` | `interface` / `record` | Entkoppelte Audio-Rückrufschnittstelle (`play`, `queue`, `stop`, `voice`) und protokolliertes Audio-Ereignis. |
| `ScriptRuntime.StackFrame` & `ExecutionSnapshot` | `record` | Call-/Block-Stackframe und unveränderlicher Ausführungs-Snapshot für Subroutinen und deterministisches M4-Rollback. |
| `ScriptSession` | `final class` | M4-Laufzeitsitzungskoordinator, der `ScriptRuntime` mit 4-Tier-Persistenz (`SessionState`, `RevertableStateStore`, `PersistentState`, `PreferencesState`), `HistoryLog`, `SaveManager`, `LocalizationManager` und Meta-Managern (`AchievementManager`, `GalleryManager`, `EndingsManager`) verdrahtet. |
| `ScriptProfiler` | `final class` | Laufzeit-Profiler für Label-/Statement-Ausführungszeiten (`LabelProfile`, Hit-Counts, `hottestLabel()`, `topLabels(int)`, Bericht). |
| `WatchpointEvent` | `record` | Unveränderliches Änderungsereignis für überwachte `StateStore`-Schlüssel (`previousOptional()`, `newOptional()`). |
| `VariableWatchpointManager` | `final class` | Überwacht `StateStore`-Variablen auf Änderungen oder Bedingungserfüllung (`watchKey`, `watchWhen`, `checkAll`) inklusive Historie. |
| `MacroRegistry` | `final class` | Text-Makro-Registry (`{{macro}}`) mit Zyklus-/Tiefenprüfung (`IllegalStateException`) und `Optional`-Lookup. |
| `TypeSafeStateView` | `final class` | Typsichere Lese-Fassade über `StateStore` mit `Optional<String|Integer|Long|Double|Boolean|BigDecimal|T>`-Methoden. |
| `VJavaLocalizationBridge` | `final class` | Brücke zwischen `VJavaAst.ProjectAst` und dem AST-freien `LocalizationManager` (`indexProject`, `toTranslationBlocks`, `extractTranslationTemplate`). |
| `HotReloadWatcher` | `final class` | Nicht-blockierender Polling-Dateiwächter für `**/*.vjava` und `project.vjava.json` (`ReloadDelta` mit `added`/`modified`/`deleted`). |
| `ScriptDebugger` | `final class` | Interaktiver AST-Statement-Debugger (`Breakpoint`, `DebugMode`, `stepInto`, `stepOver`, `continueExecution`, Call-Stack-Zusammenfassung). |

## Öffentliche API-Signaturen

### `VJavaAst`
```java
public final class VJavaAst {
    public sealed interface Statement permits
            JavaBlockStatement, SceneStatement, ShowStatement, HideStatement,
            SayStatement, JumpStatement, CallStatement, ReturnStatement,
            PlayStatement, QueueStatement, StopStatement, VoiceStatement,
            NvlStatement, BlockRollbackStatement, InputStatement,
            MenuStatement, IfStatement, SetVarStatement,
            PauseStatement, WithStatement, WindowStatement {
        String sourceFile();
        int lineNumber();
    }

    public record JavaBlockNode(String id, String description, String code, String sourceFile, int lineNumber) {}
    public record CharacterDef(String id, String displayName, String colorHex, String outlineColorHex, String imageTag, String voiceTag, String textboxSkin, String voiceBleepSound, Map<String, String> properties, String sourceFile, int lineNumber) {}
    public record ImageDef(String tag, List<String> attributes, String assetPath, String sourceFile, int lineNumber) {
        public String fullKey()
    }
    public sealed interface LayeredEntry permits AlwaysLayerEntry, GroupLayerEntry, ConditionalLayerEntry {}
    public record AlwaysLayerEntry(String assetOrExpr) implements LayeredEntry {}
    public record AttributeEntry(String name, boolean isDefault, String assetOrExpr, String conditionExpr) {}
    public record GroupLayerEntry(String groupName, List<AttributeEntry> attributes) implements LayeredEntry {}
    public record ConditionalLayerEntry(String conditionExpr, String assetOrExpr) implements LayeredEntry {}
    public record LayeredImageDef(String name, List<LayeredEntry> entries, String sourceFile, int lineNumber) {}
    public record AtlStep(String kind, float durationSeconds, String easeName, Map<String, String> properties, List<AtlStep> children) {}
    public record TransformDef(String name, List<AtlStep> steps, String sourceFile, int lineNumber) {}
    public record ScreenItem(String keyword, String primaryArg, Map<String, String> properties, List<ScreenItem> children, String sourceFile, int lineNumber) {}
    public record ScreenDef(String name, List<String> parameters, boolean modal, int zOrder, List<ScreenItem> items, String sourceFile, int lineNumber) {}
    public record TranslateEntry(String speaker, String originalText, String translatedText) {}
    public record TranslateBlock(String language, String id, List<TranslateEntry> entries, String sourceFile, int lineNumber) {}
    public record LabelNode(String name, List<Statement> statements, String sourceFile, int lineNumber) {}

    public record JavaBlockStatement(JavaBlockNode block, String sourceFile, int lineNumber) implements Statement {}
    public record SceneStatement(String bgTag, String transitionExpr, String sourceFile, int lineNumber) implements Statement {}
    public record ShowStatement(String tag, List<String> attributes, String positionExpr, String transitionExpr, List<AtlStep> inlineAtl, String sourceFile, int lineNumber) implements Statement {}
    public record HideStatement(String tag, String transitionExpr, String sourceFile, int lineNumber) implements Statement {}
    public record SayStatement(String speakerId, String text, String transitionExpr, String sourceFile, int lineNumber) implements Statement {}
    public record JumpStatement(String targetLabel, boolean isExpression, String sourceFile, int lineNumber) implements Statement {}
    public record CallStatement(String targetLabel, List<String> args, String sourceFile, int lineNumber) implements Statement {}
    public record ReturnStatement(String expression, String sourceFile, int lineNumber) implements Statement {}
    public record PlayStatement(String channel, String assetPath, boolean loop, double fadeInSeconds, double volume, double loopStartSeconds, String sourceFile, int lineNumber) implements Statement {}
    public record QueueStatement(String channel, List<String> assetPaths, String sourceFile, int lineNumber) implements Statement {}
    public record StopStatement(String channel, double fadeOutSeconds, String sourceFile, int lineNumber) implements Statement {}
    public record VoiceStatement(String assetPath, String sourceFile, int lineNumber) implements Statement {}
    public record NvlStatement(String action, String sourceFile, int lineNumber) implements Statement {}
    public record BlockRollbackStatement(String sourceFile, int lineNumber) implements Statement {}
    public record InputStatement(String targetVar, String prompt, String defaultValue, String allowChars, int maxLength, String sourceFile, int lineNumber) implements Statement {}
    public record MenuChoice(String text, String conditionExpr, boolean disabledWhenFalse, List<Statement> body, String inlineJumpTarget, String sourceFile, int lineNumber) {}
    public record MenuStatement(String caption, Double timeoutSeconds, String timeoutJumpLabel, List<MenuChoice> choices, String sourceFile, int lineNumber) implements Statement {}
    public record IfBranch(String conditionExpr, List<Statement> body) {}
    public record IfStatement(List<IfBranch> branches, String sourceFile, int lineNumber) implements Statement {}
    public record SetVarStatement(String varName, String expression, boolean isDefaultOnly, String sourceFile, int lineNumber) implements Statement {}
    public record PauseStatement(Double seconds, String sourceFile, int lineNumber) implements Statement {}
    public record WithStatement(String transitionExpr, String sourceFile, int lineNumber) implements Statement {}
    public record WindowStatement(String action, String sourceFile, int lineNumber) implements Statement {}

    public record ScriptFileAst(
            Path filePath, List<CharacterDef> characters, List<ImageDef> images,
            List<LayeredImageDef> layeredImages, List<TransformDef> transforms,
            List<LabelNode> labels, List<ScreenDef> screens,
            List<TranslateBlock> translations, List<JavaBlockNode> topLevelJavaBlocks,
            List<SetVarStatement> defaultVariables
    ) {}

    public record ProjectAst(
            Path projectRoot, ProjectDescriptor descriptor, List<Path> scriptFiles,
            Map<String, CharacterDef> characters, Map<String, ImageDef> images,
            Map<String, LayeredImageDef> layeredImages, Map<String, TransformDef> transforms,
            Map<String, LabelNode> labels, Map<String, ScreenDef> screens,
            List<TranslateBlock> translations, List<JavaBlockNode> topLevelJavaBlocks,
            List<SetVarStatement> defaultVariables, List<String> discoveredAssets, List<String> warnings
    ) {}
}
```

### `VJavaParseException`
```java
public class VJavaParseException extends RuntimeException {
    public VJavaParseException(String sourceFile, int lineNumber, String message)
    public VJavaParseException(String sourceFile, int lineNumber, String message, Throwable cause)
    public String getSourceFile()
    public int getLineNumber()
}
```

### `VJavaLexer`
```java
public final class VJavaLexer {
    public enum TokenType { INDENT, DEDENT, LINE, JAVA_BLOCK, EOF }

    public static final class Token {
        public Token(TokenType type, String sourceFile, int lineNumber, int indentColumns, String text, String description, String code)
        public TokenType type()
        public String sourceFile()
        public int lineNumber()
        public int indentColumns()
        public String text()
        public String description()
        public String code()
    }

    public static List<Token> tokenize(Path file) throws IOException
    public static List<Token> tokenize(String source, String sourceFile)
}
```

### `VJavaParser`
```java
public final class VJavaParser {
    public static ScriptFileAst parse(Path filePath) throws IOException
    public static ScriptFileAst parse(Path filePath, String source)
    public static ScriptFileAst parse(List<Token> tokens)
    public static ScriptFileAst parse(Path filePath, List<Token> tokens)
}
```

### `ProjectScanner`
```java
public final class ProjectScanner {
    public static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", "build", ".gradle", "saves", "out", "dist");

    public static ProjectAst scan(Path projectRoot)
}
```

### `VJavaCompileException`
```java
public class VJavaCompileException extends RuntimeException {
    public record CompileDiagnostic(String blockId, String sourceFile, int lineNumber, long columnNumber, String kind, String message) {
        public String format()
    }

    public VJavaCompileException(String message)
    public VJavaCompileException(String message, Throwable cause)
    public VJavaCompileException(String blockId, String sourceFile, int lineNumber, List<String> diagnosticMessages)
    public VJavaCompileException(String message, String blockId, String sourceFile, int lineNumber, List<CompileDiagnostic> diagnostics, String generatedSource, Throwable cause)

    public String getBlockId()
    public String getSourceFile()
    public int getLineNumber()
    public List<String> getDiagnosticMessages()
    public List<CompileDiagnostic> getDiagnostics()
    public String getGeneratedSource()
}
```

### `VJavaExecutable`
```java
@FunctionalInterface
public interface VJavaExecutable {
    void execute(StateStore state, PersistentAccess persistent, EngineAPI engine, SceneController scene) throws Exception;
}
```

### `JavaBlockCompiler`
```java
public final class JavaBlockCompiler {
    public static final String GENERATED_PACKAGE = "de.swfdtf.vjava.gen";
    public static final String USER_TYPES_SIMPLE_NAME = "VJavaUserTypes";
    public static final String USER_TYPES_BINARY_NAME = GENERATED_PACKAGE + "." + USER_TYPES_SIMPLE_NAME;

    public static final class CompiledProjectBlocks {
        public VJavaExecutable getExecutable(String blockId)
        public VJavaExecutable requireExecutable(String blockId)
        public boolean hasExecutable(String blockId)
        public Map<String, VJavaExecutable> getExecutables()
        public ClassLoader getClassLoader()
        public Class<?> loadUserType(String simpleName)
        public Optional<Class<?>> findUserType(String simpleName)
        public boolean hasUserTypes()
        public int size()
    }

    public CompiledProjectBlocks compile(VJavaAst.ProjectAst project) throws VJavaCompileException
    public CompiledProjectBlocks compile(List<VJavaAst.JavaBlockNode> blocks) throws VJavaCompileException
    public static List<VJavaAst.JavaBlockNode> collectJavaBlocks(VJavaAst.ProjectAst project)
}
```

### `ConditionEvaluator`
```java
public final class ConditionEvaluator {
    public ConditionEvaluator()
    public static Object evaluate(String expr, StateStore state, PersistentAccess persistent)
    public static boolean evaluateBoolean(String expr, StateStore state, PersistentAccess persistent)
    public static boolean isTruthy(Object value)
}
```

### `ScriptRuntime`
```java
public final class ScriptRuntime {
    public enum Status { READY, WAITING_DIALOGUE, WAITING_MENU, WAITING_INPUT, PAUSING, ENDED }

    public record DialoguePrompt(String lineId, String speakerId, String speakerName, String speakerColorHex, String rawText, String interpolatedText, String voicePath, String transitionExpr, boolean alreadyRead, boolean nvlMode) {}
    public record ChoiceOption(int index, String text, boolean enabled, boolean visible, VJavaAst.MenuChoice astChoice) {}
    public record MenuPrompt(String caption, Double timeoutSeconds, double remainingSeconds, List<ChoiceOption> options) {}
    public record InputPrompt(String targetVar, String prompt, String defaultValue, String allowChars, int maxLength) {}
    public interface AudioBridge {
        void play(String ch, String path, boolean loop, double fadeIn, double vol);
        void queue(String ch, List<String> paths);
        void stop(String ch, double fadeOut);
        void voice(String path);
        default void play(String ch, String path, boolean loop, double fadeIn, double vol, double loopStartSeconds)
        default String getActiveVoiceAssetPath()
        default double getActiveVoicePlaybackSeconds()
        default void resumeVoiceAt(String assetPath, double offsetSeconds)
    }
    public record AudioEvent(String action, String channel, String assetPath, List<String> queuedPaths, boolean loop, double fadeSeconds, double volume) {}
    public record StackFrame(String labelName, int statementIndex, List<VJavaAst.Statement> statements, List<VJavaAst.Statement> childStatements, boolean isCallBoundary) {
        public StackFrame(String labelName, int statementIndex)
    }
    public record ExecutionSnapshot(String currentLabel, int statementIndex, List<StackFrame> callStack, Status status, DialoguePrompt activeDialogue, MenuPrompt activeMenu, InputPrompt activeInput, boolean nvlMode, List<String> nvlLines, String activeVoicePath, SceneController.StageSnapshot stageSnapshot) {}

    public ScriptRuntime(VJavaAst.ProjectAst project)
    public ScriptRuntime(VJavaAst.ProjectAst project, EngineAPI engineApi)
    public ScriptRuntime(VJavaAst.ProjectAst project, EngineAPI engineApi, StateStore stateStore, PersistentAccess persistentAccess, SceneController sceneController)

    public JavaBlockCompiler.CompiledProjectBlocks compile() throws VJavaCompileException
    public void start()
    public void start(String entryLabel)
    public void advance()
    public void choose(int optionIndex)
    public void submitInput(String text)
    public void update(double dt)
    public void jumpToLabel(String label)
    public ExecutionSnapshot snapshotExecution()
    public void restoreExecution(ExecutionSnapshot snapshot)
    public SaveSlotData.SavedScriptPosition toSavedPosition()
    public void restoreSavedPosition(SaveSlotData.SavedScriptPosition pos)

    public Status getStatus()
    public DialoguePrompt getActiveDialogue()
    public MenuPrompt getActiveMenu()
    public InputPrompt getActiveInput()
    public StateStore getStateStore()
    public PersistentAccess getPersistentAccess()
    public SceneController getSceneController()
    public EngineAPI getEngineApi()
    public VJavaAst.ProjectAst getProjectAst()
    public List<AudioEvent> getAudioEvents()
    public void setAudioBridge(AudioBridge audioBridge)
    public AudioBridge getAudioBridge()
    public void setLocalizationManager(LocalizationManager localizationManager)
    public LocalizationManager getLocalizationManager()
    public void setCheckpointListener(Runnable checkpointListener)
    public JavaBlockCompiler.CompiledProjectBlocks getCompiledBlocks()
    public String getCurrentLabel()
    public int getStatementIndex()
    public boolean isNvlMode()
    public List<String> getNvlLines()
    public String getActiveVoicePath()
    public double getPauseRemainingSeconds()
    public String getWindowAction()
}
```

### `ScriptSession`
```java
public final class ScriptSession {
    public ScriptSession(VJavaAst.ProjectAst project, EngineAPI engineApi) throws IOException
    public ScriptSession(VJavaAst.ProjectAst project, EngineAPI engineApi, SceneGraph sceneGraph, TextureManager textureManager, boolean glTextures, Path savesDir) throws IOException
    public ScriptSession(VJavaAst.ProjectAst project, EngineAPI engineApi, SceneController sceneController, Path savesDir) throws IOException

    public void start()
    public void start(String entryLabel)
    public void advance()
    public void choose(int optionIndex)
    public void submitInput(String text)
    public void update(double dt)

    public boolean rollback()
    public boolean rollForward()
    public boolean jumpToHistoryEntry(HistoryLog.HistoryEntry entry)

    public SaveSlotData createSaveSlotData(String slotId, String customName, int thumbWidth, int thumbHeight, byte[] thumbRgba)
    public void saveToSlot(String slotId, String customName, int thumbWidth, int thumbHeight, byte[] thumbRgba) throws IOException
    public void quickSave() throws IOException
    public void quickLoad() throws IOException
    public String autoSave() throws IOException
    public void loadFromSlot(String slotId) throws IOException
    public void saveMetaState() throws IOException

    public ScriptRuntime getRuntime()
    public RevertableStateStore getStateStore()
    public PersistentState getPersistentState()
    public PreferencesState getPreferencesState()
    public SessionState getSessionState()
    public HistoryLog getHistoryLog()
    public SaveManager getSaveManager()
    public LocalizationManager getLocalizationManager()
    public AchievementManager getAchievementManager()
    public GalleryManager getGalleryManager()
    public EndingsManager getEndingsManager()
    public SceneController getSceneController()
    public EngineAPI getEngineApi()
}
```


### Neue Bewegungs-, WASD-, Physik- & Kollisions-Statements (`VJavaAst` & `VJavaParser`)
- `VJavaAst.Statement` erweitert um:
  - `MoveStatement(String tag, double targetX, double targetY, boolean relative, double durationSeconds, String easing, String sourceFile, int lineNumber)` (`move ... to ...`, `move_by ...`)
  - `WasdInitStatement(String tag, double speedPxPerSec, Double minX, Double minY, Double maxX, Double maxY, boolean normalizeDiagonal, String sourceFile, int lineNumber)` (`wasd_init`)
  - `WasdStopStatement(String tag, String sourceFile, int lineNumber)` (`wasd_stop`)
  - `PhysicsInitStatement(String tag, double gravityPxPerSec2, double jumpForcePxPerSec, double groundY, String sourceFile, int lineNumber)` (`physics_init`)
  - `PhysicsStopStatement(String tag, String sourceFile, int lineNumber)` (`physics_stop`)
  - `OnCollideStatement(String tagA, String tagB, String targetLabel, String sourceFile, int lineNumber)` (`on_collide <tagA> <tagB> jump <label>`)
- `ScriptRuntime` verdrahtet alle 6 Statements + `setCollisionJumpListener(targetLabel -> jumpToLabel(targetLabel))` auf `SceneController.getMotionController()`.

### `VJavaLocalizationBridge`
```java
public final class VJavaLocalizationBridge {
    public static void indexProject(LocalizationManager manager, VJavaAst.ProjectAst project)
    public static List<LocalizationManager.TranslationBlock> toTranslationBlocks(VJavaAst.ProjectAst project)
    public static String extractTranslationTemplate(VJavaAst.ProjectAst project, String targetLanguage)
}
```
