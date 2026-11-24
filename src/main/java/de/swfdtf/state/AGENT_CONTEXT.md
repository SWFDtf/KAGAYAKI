# Package `de.swfdtf.state` — AGENT_CONTEXT

## Zweck des Pakets
Vollständige 4-Schichten-State-, Rollback-, Save/Load-, Verlaufs-, Meta-Progression- und Lokalisierungs-Architektur von KAGAYAKI (Meilenstein M3 + M4):
- **Tier 1 (Transient Runtime Session)**: `SessionState` (Sitzungsstart, Spielzeit, aktiver Save-Slot, Auto-Save-Zeitstempel, transiente Flags).
- **Tier 2 (Deterministic Rollback & Save Slots)**: `StateStore`, `Revertable<S>`, `RevertableMap<K, V>`, `RevertableList<E>`, `RevertableStateStore` (Checkpoints, Rollback/Roll-Forward, Branch-Trimming, `block_rollback`-Barrieren, `HistoryLog`-Jump-Back), `HistoryLog`, `SaveValueCodec` (typerhaltender Gson-Codec inkl. `__vjavaClass`-Rekonstruktion), `SaveSlotData` und `SaveManager` (atomare `.ksav`-GZIP-JSON-Dateien mit LWJGL-STB-PNG-Thumbnails, Quick-Save & rotierendem Auto-Save).
- **Tier 3 (Cross-Save Persistent Data)**: `PersistentAccess`, `SimplePersistentAccess`, `PersistentState` (atomare GZIP-JSON-Persistenz in `saves/persistent.dat`), `AchievementManager`, `GalleryManager` und `EndingsManager`.
- **Tier 4 (User Preferences & Live Localization)**: `PreferencesState` (`saves/preferences.json`) und `LocalizationManager` (`.vjava`-`translate`-Indizierung, Live-Sprachwechsel, `tl/<lang>/`-Asset-Auflösung und `.vjava`-Template-Generator).

## Abhängigkeiten zu anderen `de.swfdtf.*`-Paketen
- `de.swfdtf.math` (`Transform2D` in `RevertableMap`, `SaveValueCodec`; `ColorMatrix4f`, `Rect2f` in `SaveValueCodec`)
- `de.swfdtf.graphics` (`SceneController` in `SaveSlotData`)
- `de.swfdtf.parser` (`VJavaAst` in `LocalizationManager`)
- Externe Bibliotheken: `com.google.gson.*` (`PersistentState`, `PreferencesState`, `SaveManager`, `SaveValueCodec`), `org.lwjgl.stb.STBImageWrite` / `STBIWriteCallback` / `org.lwjgl.system.MemoryUtil` (`SaveManager`).

## Enthaltene Typen

| Typ | Art | Beschreibung |
| :--- | :--- | :--- |
| `StateStore` | `interface` | Vertrag für den veränderbaren VN-Variablenspeicher (`state`) inklusive Typ-Coercion, Checkpoint-Snapshots (`snapshotMap`/`restoreMap`) und `blockRollback`-Sperre. |
| `PersistentAccess` | `interface` | Vertrag für globale, spielstandübergreifende Persistenz (`persistent`) für Variablen, gelesene Zeilen-IDs sowie freigeschaltete CGs, Musik-Tracks, Endings und Achievements. |
| `SimpleStateStore` | `class` | Thread-sichere In-Memory-Implementierung von `StateStore` (`LinkedHashMap` + `rollbackBlocked`-Flag) für leichtgewichtige M3-Laufzeiten und Unit-Tests. |
| `SimplePersistentAccess` | `class` | Thread-sichere In-Memory-Implementierung von `PersistentAccess` (`LinkedHashMap` + `LinkedHashSet`s) für leichtgewichtige M3-Laufzeiten und Unit-Tests. |
| `Revertable<S>` | `interface` | Generischer Vertrag für deterministische Snapshot-Erfassung (`captureState`) und -Wiederherstellung (`restoreState`). |
| `RevertableMap<K, V>` | `class` | `LinkedHashMap`-basierte `Revertable<Map<K, V>>`-Map mit zyklus- und tiefensicherem Cloning (`cloneValue`, `deepEquals`) für Container, Arrays, `Transform2D`, `Serializable` und Referenz-Erhalt für nicht-serialisierbare POJOs. |
| `RevertableList<E>` | `class` | `ArrayList`-basierte `Revertable<List<E>>`-Liste mit tiefensicherer Snapshot-Kopie und `modCount`-Pflege. |
| `RevertableStateStore` | `class` | Transaktionale `StateStore`-Implementierung auf Basis von `RevertableMap` mit Checkpoints (`Checkpoint`), Rollback, Roll-Forward, Branch-Trimming, `jumpToCheckpoint` und `block_rollback`-Barrieren. |
| `RevertableStateStore.Checkpoint` | `record` | Unveränderlicher Checkpoint (`id`, `variablesSnapshot`, `runtimeSnapshot`, `summary`, `rollbackBarrierBefore`). |
| `SessionState` | `final class` | Tier-1-Sitzungszustand für Startzeit, aktive Spielzeit (`updatePlaytime`), aktiven Save-Slot, Auto-Save-Zeitstempel und transiente Boolean-Flags. |
| `PersistentState` | `final class` | Tier-3-Dateipersistenz (`implements PersistentAccess`), atomar als GZIP-komprimiertes JSON unter `saves/persistent.dat` gespeichert. |
| `PreferencesState` | `final class` | Tier-4-Benutzereinstellungen (Audio-Pegel, Textgeschwindigkeit, Skip-Verhalten, Render-Modus, Sprache, Dialogmodus, Barrierefreiheit) in `saves/preferences.json`. |
| `HistoryLog` | `final class` | Kapazitätsbegrenzter (`DEFAULT_MAX_ENTRIES = 500`) chronologischer Dialogverlauf mit Checkpoint-Korrelation und `truncateAfterCheckpoint`. |
| `HistoryLog.HistoryEntry` | `record` | Unveränderlicher Backlog-Eintrag (`entryId`, `checkpointId`, `lineId`, `speakerName`, `speakerColorHex`, `text`, `voicePath`, `timestamp`). |
| `SaveValueCodec` | `final class` | Typerhaltender Gson-Codec für Primitive, `Long`/`Float`/`Short`/`Byte`/`Character`/`BigInteger`/`BigDecimal`, `Instant`, `UUID`, `Enum`, `Transform2D`, `ColorMatrix4f`, `Rect2f`, Collections, Arrays und beliebige `@->{ ... }<<`-Klassen (`__vjavaClass`). |
| `SaveSlotData` | `record` | Unveränderliches Spielstandsmodell (`metadata`, `variables`, `scriptPosition`, `historyEntries`) für `.ksav`-Dateien. |
| `SaveSlotData.SaveMetadata` | `record` | Leichtgewichtige Slot-Metadaten inklusive PNG-Thumbnail-Bytes (`slotId`, `customName`, `savedAt`, `currentLabel`, `dialoguePreview`, `playtimeSeconds`, `gameVersion`, `thumbnailWidth`, `thumbnailHeight`, `thumbnailPng`). |
| `SaveSlotData.SavedStackFrame` | `record` | Von AST-Objekten entkoppelter Call-/Block-Frame (`labelName`, `statementIndex`, `isCallBoundary`). |
| `SaveSlotData.SavedScriptPosition` | `record` | Entkoppelter Skript-Ausführungszustand inklusive NVL-Puffer, Voice-Pfad und `SceneController.StageSnapshot`. |
| `SaveManager` | `final class` | Tier-2-Save/Load-Manager für atomare `.ksav`-Dateien (`saves/<slotId>.ksav`), LWJGL-STB-PNG-Thumbnails (`encodeRgbaToPng`), Seiten-Listing, Quick-Save (`"quick"`) und rotierenden Auto-Save (`auto_1`..`auto_3`). |
| `AchievementManager` | `class` | Verwaltet Achievement-Definitionen (`AchievementDef`), persistenten Unlock-Status über `PersistentAccess`, Toast-Warteschlange (`AchievementToast`) und Unlock-Listener. |
| `GalleryManager` | `class` | Verwaltet CG-Galerie-Einträge (`GalleryEntry` mit Variationen/Kategorien) und deren persistenten Unlock-Status. |
| `EndingsManager` | `class` | Verwaltet verzweigte Story-Endings (`EndingDef` mit `parentEndingId`-Baumstruktur) inklusive Completion-Prozentwert. |
| `LocalizationManager` | `class` | Verwaltet Live-Sprachwechsel mit Listenern, Indizierung von `VJavaAst.TranslateBlock`s, `translateDialogue`/`translateUi`, `tl/<lang>/`-Asset-Auflösung und `.vjava`-Template-Generierung (`extractTranslationTemplate`). |
| `AutoSaveScheduler` | `final class` | Konfigurierbarer Zeit-/Schritt-Scheduler für rotierende Auto-Saves (`auto_1`..`auto_<maxSlots>`) über `SaveManager`. |
| `SaveSlotQueryService` | `final class` | Tagging, Volltextsuche, Filterung nach Tag/Label und Sortierung (Zeitstempel/Spielzeit) über `SaveSlotData` mit `Optional`- und unveränderlichen Listen-Ergebnissen. |
| `SaveMigrationStep` | `interface` | Versionsschritt (`fromVersion()`, `toVersion()`, `migrate(SaveSlotData)`) für abwärtskompatible Spielstand-Migrationen. |
| `SaveMigrationService` | `final class` | Validierte Migrations-Pipeline für `SaveSlotData` inklusive Versions-Tracking (`__saveVersion`) und Migrationsprotokoll (`__migrationTrail`). |

## Öffentliche API-Signaturen

### `StateStore`
```java
public interface StateStore {
    void set(String key, Object value);
    Object get(String key);
    Object getOrDefault(String key, Object defaultValue);
    String getString(String key, String defaultValue);
    int getInt(String key, int defaultValue);
    double getDouble(String key, double defaultValue);
    boolean getBoolean(String key, boolean defaultValue);
    boolean has(String key);
    Object remove(String key);
    Set<String> keys();
    Map<String, Object> snapshotMap();
    void restoreMap(Map<String, Object> snapshot);
    void blockRollback();
    boolean isRollbackBlocked();
    void clear();
}
```

### `PersistentAccess`
```java
public interface PersistentAccess {
    void set(String key, Object value);
    Object get(String key);
    Object getOrDefault(String key, Object defaultValue);
    boolean has(String key);
    void markLineRead(String lineId);
    boolean isLineRead(String lineId);
    void unlockCg(String cgId);
    boolean isCgUnlocked(String cgId);
    void unlockTrack(String trackId);
    boolean isTrackUnlocked(String trackId);
    void unlockEnding(String endingId);
    boolean isEndingUnlocked(String endingId);
    void unlockAchievement(String achievementId);
    boolean isAchievementUnlocked(String achievementId);
    Set<String> getUnlockedCgs();
    Set<String> getUnlockedTracks();
    Set<String> getUnlockedEndings();
    Set<String> getUnlockedAchievements();
}
```

### `SimpleStateStore` & `SimplePersistentAccess`
```java
public class SimpleStateStore implements StateStore {
    // Alle StateStore-Methoden (synchronized) plus:
    public synchronized void resetRollbackBlock()
}

public class SimplePersistentAccess implements PersistentAccess {
    // Alle PersistentAccess-Methoden (synchronized)
}
```

### `Revertable`, `RevertableMap` & `RevertableList`
```java
public interface Revertable<S> {
    S captureState();
    void restoreState(S snapshot);
}

public class RevertableMap<K, V> extends AbstractMap<K, V> implements Revertable<Map<K, V>> {
    public RevertableMap()
    public RevertableMap(Map<? extends K, ? extends V> initial)
    public Map<K, V> captureState()
    public void restoreState(Map<K, V> snapshot)
    public static Object cloneValue(Object value)
    public static boolean deepEquals(Object a, Object b)
}

public class RevertableList<E> extends AbstractList<E> implements Revertable<List<E>> {
    public RevertableList()
    public RevertableList(int initialCapacity)
    public RevertableList(Collection<? extends E> initial)
    public List<E> captureState()
    public void restoreState(List<E> snapshot)
}
```

### `RevertableStateStore`
```java
public class RevertableStateStore implements StateStore {
    public record Checkpoint(long id, Map<String, Object> variablesSnapshot, Object runtimeSnapshot, String summary, boolean rollbackBarrierBefore) {}

    public RevertableStateStore()
    public synchronized void setRuntimeHooks(Supplier<Object> snapshotter, Consumer<Object> restorer)
    public synchronized long recordCheckpoint(String summary)
    public synchronized long recordCheckpoint(Object runtimeSnapshot, String summary)
    public synchronized boolean canRollback()
    public synchronized boolean rollback()
    public synchronized boolean canRollForward()
    public synchronized boolean rollForward()
    public synchronized boolean jumpToCheckpoint(long checkpointId)
    public synchronized Checkpoint getCurrentCheckpoint()
    public synchronized List<Checkpoint> getCheckpoints()
    public synchronized int getCursorIndex()
    public synchronized void clearCheckpoints()
    // + vollständige Implementierung aller StateStore-Methoden
}
```

### `SessionState`
```java
public final class SessionState {
    public SessionState()
    public synchronized void updatePlaytime(double dt)
    public synchronized void setFlag(String key, boolean value)
    public synchronized boolean getFlag(String key)
    public synchronized boolean getFlag(String key, boolean defaultValue)
    public synchronized boolean hasFlag(String key)
    public synchronized Boolean removeFlag(String key)
    public synchronized Map<String, Boolean> getSessionFlags()
    public synchronized Instant getSessionStartTime()
    public synchronized void setSessionStartTime(Instant sessionStartTime)
    public synchronized double getPlaytimeSeconds()
    public synchronized void setPlaytimeSeconds(double playtimeSeconds)
    public synchronized String getActiveSaveSlotId()
    public synchronized void setActiveSaveSlotId(String activeSaveSlotId)
    public synchronized Instant getLastAutoSaveTime()
    public synchronized void setLastAutoSaveTime(Instant lastAutoSaveTime)
    public synchronized void markAutoSaved()
    public synchronized void reset()
}
```

### `PersistentState` & `PreferencesState`
```java
public final class PersistentState implements PersistentAccess {
    public static final String FILE_NAME = "persistent.dat";

    public PersistentState()
    public static PersistentState load(Path savesDir) throws IOException
    public static PersistentState load(Path savesDir, ClassLoader optionalUserClassLoader) throws IOException
    public synchronized void save(Path savesDir) throws IOException
    public synchronized Set<String> getReadLines()
    public synchronized Map<String, Object> getDataSnapshot()
    public synchronized void clear()
    // + vollständige Implementierung aller PersistentAccess-Methoden
}

public final class PreferencesState {
    public static final String FILE_NAME = "preferences.json";

    public PreferencesState()
    public static PreferencesState load(Path savesDir) throws IOException
    public synchronized void save(Path savesDir) throws IOException
    public synchronized void normalizeDefaults()
    // Synchronized Getter & Setter für:
    // masterVolume, musicVolume, soundVolume, voiceVolume, ambientVolume,
    // textSpeedCps, autoForwardDelaySeconds, skipUnread, skipAfterChoices,
    // fullscreen, renderMode, language, dialogueMode, selfVoicing,
    // highContrastDyslexiaFont, uiTextScale
}
```

### `HistoryLog`
```java
public final class HistoryLog {
    public static final int DEFAULT_MAX_ENTRIES = 500;
    public record HistoryEntry(long entryId, long checkpointId, String lineId, String speakerName, String speakerColorHex, String text, String voicePath, Instant timestamp) {}

    public HistoryLog()
    public HistoryLog(int maxEntries)
    public synchronized HistoryEntry append(long checkpointId, String lineId, String speakerName, String speakerColorHex, String text, String voicePath)
    public synchronized HistoryEntry append(long checkpointId, String lineId, String speakerName, String speakerColorHex, String text, String voicePath, Instant timestamp)
    public synchronized void append(HistoryEntry entry)
    public synchronized List<HistoryEntry> getEntries()
    public synchronized List<HistoryEntry> getRecentEntries(int max)
    public synchronized void truncateAfterCheckpoint(long checkpointId)
    public synchronized void clear()
    public synchronized List<HistoryEntry> snapshot()
    public synchronized void restore(List<HistoryEntry> entries)
    public synchronized int size()
    public synchronized boolean isEmpty()
    public int getMaxEntries()
}
```

### `SaveValueCodec`, `SaveSlotData` & `SaveManager`
```java
public final class SaveValueCodec {
    public static final String KEY_VJAVA_CLASS = "__vjavaClass";
    public static final String KEY_FIELDS = "fields";
    public static final String KEY_KTYPE = "__ktype";

    public static Gson createGson(boolean prettyPrinting)
    public static JsonElement encode(Object value)
    public static JsonObject encodeMap(Map<String, ?> map)
    public static Object decode(JsonElement element, ClassLoader optionalUserClassLoader)
    public static Map<String, Object> decodeMap(JsonObject obj, ClassLoader optionalUserClassLoader)
}

public record SaveSlotData(SaveMetadata metadata, Map<String, Object> variables, SavedScriptPosition scriptPosition, List<HistoryLog.HistoryEntry> historyEntries) {
    public SaveSlotData withMetadata(SaveMetadata updatedMetadata)
    public record SaveMetadata(String slotId, String customName, Instant savedAt, String currentLabel, String dialoguePreview, double playtimeSeconds, String gameVersion, int thumbnailWidth, int thumbnailHeight, byte[] thumbnailPng) {}
    public record SavedStackFrame(String labelName, int statementIndex, boolean isCallBoundary) {}
    public record SavedScriptPosition(String currentLabel, int statementIndex, List<SavedStackFrame> callStack, String statusName, String activeSpeakerId, String activeSpeakerName, String activeDialogueText, boolean nvlMode, List<String> nvlLines, String activeVoicePath, SceneController.StageSnapshot stageSnapshot) {}
}

public final class SaveManager {
    public static final String SAVE_EXTENSION = ".ksav";
    public static final String QUICK_SAVE_SLOT = "quick";
    public static final String AUTO_SAVE_PREFIX = "auto_";
    public static final int DEFAULT_AUTO_SAVE_SLOTS = 3;

    public SaveManager(Path savesDir)
    public SaveManager(Path savesDir, int autoSaveSlotCount)
    public static byte[] encodeRgbaToPng(int width, int height, byte[] rgbaBytes)
    public static byte[] encodeRgbaToPng(int width, int height, byte[] rgbaBytes, boolean flipVertically)
    public static String slotIdForPage(int pageIndex, int slotIndexInPage, int slotsPerPage)
    public synchronized void saveSlot(String slotId, String customName, SaveSlotData data) throws IOException
    public synchronized void saveSlot(String slotId, SaveSlotData data) throws IOException
    public synchronized SaveSlotData loadSlot(String slotId) throws IOException
    public synchronized SaveSlotData loadSlot(String slotId, ClassLoader optionalUserClassLoader) throws IOException
    public synchronized Optional<SaveMetadata> readSlotMetadata(String slotId) throws IOException
    public synchronized List<SaveMetadata> listPageSlots(int pageIndex, int slotsPerPage) throws IOException
    public synchronized void quickSave(SaveSlotData data) throws IOException
    public synchronized SaveSlotData quickLoad(ClassLoader optionalUserClassLoader) throws IOException
    public synchronized SaveSlotData quickLoad() throws IOException
    public synchronized String autoSave(SaveSlotData data) throws IOException
    public synchronized boolean deleteSlot(String slotId) throws IOException
    public synchronized boolean slotExists(String slotId)
    public Path getSavesDir()
    public int getAutoSaveSlotCount()
}
```

### `AchievementManager`, `GalleryManager` & `EndingsManager`
```java
public class AchievementManager {
    public record AchievementDef(String id, String title, String description, String iconPath, boolean hidden) {}
    public record AchievementToast(AchievementDef achievement, Instant unlockedAt) {}

    public AchievementManager(PersistentAccess persistentAccess)
    public synchronized void register(AchievementDef achievement)
    public boolean unlock(String id)
    public synchronized boolean isUnlocked(String id)
    public synchronized Optional<AchievementToast> pollToast()
    public synchronized List<AchievementDef> getAllAchievements()
    public synchronized int getUnlockedCount()
    public synchronized int getTotalCount()
    public synchronized void setOnUnlockListener(Consumer<AchievementToast> listener)
}

public class GalleryManager {
    public record GalleryEntry(String id, String title, String category, String thumbnailPath, List<String> variationPaths, String description) {}

    public GalleryManager(PersistentAccess persistentAccess)
    public synchronized void register(GalleryEntry entry)
    public synchronized boolean unlock(String id)
    public synchronized boolean isUnlocked(String id)
    public synchronized List<GalleryEntry> getAllEntries()
    public synchronized List<GalleryEntry> getUnlockedEntries()
    public synchronized Optional<GalleryEntry> getEntry(String id)
    public synchronized int getUnlockedCount()
    public synchronized int getTotalCount()
}

public class EndingsManager {
    public record EndingDef(String id, String title, String description, String branchLabel, String parentEndingId) {}

    public EndingsManager(PersistentAccess persistentAccess)
    public synchronized void register(EndingDef ending)
    public synchronized boolean unlock(String id)
    public synchronized boolean isUnlocked(String id)
    public synchronized List<EndingDef> getAllEndings()
    public synchronized Optional<EndingDef> getEnding(String id)
    public synchronized List<EndingDef> getChildEndings(String parentEndingId)
    public synchronized double getCompletionPercentage()
    public synchronized int getUnlockedCount()
    public synchronized int getTotalCount()
}
```

### `LocalizationManager`
```java
public class LocalizationManager {
    public LocalizationManager()
    public LocalizationManager(String defaultLanguage)
    public void setLanguage(String language)
    public synchronized String getCurrentLanguage()
    public synchronized String getDefaultLanguage()
    public synchronized void addLanguageChangeListener(Consumer<String> listener)
    public synchronized void removeLanguageChangeListener(Consumer<String> listener)
    public synchronized void indexProject(VJavaAst.ProjectAst project)
    public synchronized void registerTranslation(String language, String keyOrOriginal, String translatedText)
    public synchronized String translateDialogue(String lineId, String originalText)
    public synchronized String translateUi(String key, String fallbackText)
    public synchronized String resolveLocalizedAsset(Path projectRoot, String assetPath)
    public synchronized Set<String> getAvailableLanguages()
    public static String extractTranslationTemplate(VJavaAst.ProjectAst project, String targetLanguage)
}
```
