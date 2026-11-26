# Package `de.swfdtf` — AGENT_CONTEXT

## Zweck des Pakets
Wurzelpaket mit den Anwendungs-Einstiegspunkten von KAGAYAKI: `Main` für den standardmäßigen Studio-Dual-Window-Start (mit optionalem `--standalone`-Override und `--create-template <dir>` zur Starterprojekt-Generierung) und `StandaloneMain` als dedizierter Einstiegspunkt für exportierte Standalone-Builds (`packageStandalone`).

## Abhängigkeiten zu anderen `de.swfdtf.*`-Paketen
- `de.swfdtf.core` (`EngineApplication`, `EngineConfig`, `EngineMode`, `ProjectDescriptor`)
- `de.swfdtf.tools` (`ProjectTemplateGenerator` in `Main` für `--create-template`)

## Enthaltene Typen

| Typ | Art | Beschreibung |
| :--- | :--- | :--- |
| `Main` | `final class` | Primärer Anwendungs-Einstiegspunkt; verarbeitet optional `--create-template <dir>` / `--create-template=<dir>` über `ProjectTemplateGenerator` und startet `EngineApplication` standardmäßig im `STUDIO_DUAL_WINDOW`-Modus, sofern `--standalone` nicht explizit übergeben wurde. |
| `StandaloneMain` | `final class` | Dedizierter Einstiegspunkt für exportierte Standalone-JARs; erzwingt `EngineMode.STANDALONE_GAME` (nur Live-Game-Fenster B ohne Studio-Werkzeuge). |

## Öffentliche API-Signaturen

### `Main`
```java
public final class Main {
    public static void main(String[] args)
    static boolean containsExplicitModeFlag(String[] args)
}
```

### `StandaloneMain`
```java
public final class StandaloneMain {
    public static void main(String[] args)
}
```
