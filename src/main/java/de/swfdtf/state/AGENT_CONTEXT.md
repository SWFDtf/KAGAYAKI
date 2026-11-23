# Package `de.swfdtf.state` — AGENT_CONTEXT

## Zweck des Pakets
Vertragsinterfaces und thread-sichere In-Memory-Basisimplementierungen für den veränderbaren Visual-Novel-Sitzungszustand (`StateStore`, in `.vjava`-Java-Blöcken als `state` verfügbar) und den spielstandübergreifenden persistenten Speicher (`PersistentAccess`, verfügbar als `persistent` für globale Variablen, gelesene Dialogzeilen, CG-Galerie, Musik-Tracks, Endings und Achievements). In Meilenstein M3 dienen `SimpleStateStore` und `SimplePersistentAccess` der Skriptausführung und Tests; in Meilenstein M4 werden sie durch den deterministischen Rollback-Speicher (`RevertableStateStore`) und Dateipersistenz (`PersistentState`) erweitert.

## Abhängigkeiten zu anderen `de.swfdtf.*`-Paketen
- Keine internen Paketabhängigkeiten (reines Java 21 JDK).

## Enthaltene Typen

| Typ | Art | Beschreibung |
| :--- | :--- | :--- |
| `StateStore` | `interface` | Vertrag für den veränderbaren VN-Variablenspeicher (`state`) inklusive Typ-Coercion, Checkpoint-Snapshots (`snapshotMap`/`restoreMap`) und `blockRollback`-Sperre. |
| `PersistentAccess` | `interface` | Vertrag für globale, spielstandübergreifende Persistenz (`persistent`) für Variablen, gelesene Zeilen-IDs sowie freigeschaltete CGs, Musik-Tracks, Endings und Achievements. |
| `SimpleStateStore` | `class` | Thread-sichere In-Memory-Implementierung von `StateStore` (`LinkedHashMap` + `rollbackBlocked`-Flag) für M3-Laufzeit und Unit-Tests. |
| `SimplePersistentAccess` | `class` | Thread-sichere In-Memory-Implementierung von `PersistentAccess` (`LinkedHashMap` + `LinkedHashSet`s) für M3-Laufzeit und Unit-Tests. |

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

### `SimpleStateStore`
```java
public class SimpleStateStore implements StateStore {
    public synchronized void set(String key, Object value)
    public synchronized Object get(String key)
    public synchronized Object getOrDefault(String key, Object defaultValue)
    public synchronized String getString(String key, String defaultValue)
    public synchronized int getInt(String key, int defaultValue)
    public synchronized double getDouble(String key, double defaultValue)
    public synchronized boolean getBoolean(String key, boolean defaultValue)
    public synchronized boolean has(String key)
    public synchronized Object remove(String key)
    public synchronized Set<String> keys()
    public synchronized Map<String, Object> snapshotMap()
    public synchronized void restoreMap(Map<String, Object> snapshot)
    public synchronized void blockRollback()
    public synchronized boolean isRollbackBlocked()
    public synchronized void resetRollbackBlock()
    public synchronized void clear()
}
```

### `SimplePersistentAccess`
```java
public class SimplePersistentAccess implements PersistentAccess {
    public synchronized void set(String key, Object value)
    public synchronized Object get(String key)
    public synchronized Object getOrDefault(String key, Object defaultValue)
    public synchronized boolean has(String key)
    public synchronized void markLineRead(String lineId)
    public synchronized boolean isLineRead(String lineId)
    public synchronized void unlockCg(String cgId)
    public synchronized boolean isCgUnlocked(String cgId)
    public synchronized void unlockTrack(String trackId)
    public synchronized boolean isTrackUnlocked(String trackId)
    public synchronized void unlockEnding(String endingId)
    public synchronized boolean isEndingUnlocked(String endingId)
    public synchronized void unlockAchievement(String achievementId)
    public synchronized boolean isAchievementUnlocked(String achievementId)
    public synchronized Set<String> getUnlockedCgs()
    public synchronized Set<String> getUnlockedTracks()
    public synchronized Set<String> getUnlockedEndings()
    public synchronized Set<String> getUnlockedAchievements()
}
```
