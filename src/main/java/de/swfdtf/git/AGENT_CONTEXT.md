# Package `de.swfdtf.git` — AGENT_CONTEXT

## Zweck des Pakets
Nicht-interaktive Git-CLI-Steuerung für das KAGAYAKI-Projekt und Studio-Automatisierung mit erzwungener Commit-Nachrichten-Policy (`<type>(<module>): <summary>`), Timeout-Überwachung und deadlock-freiem Auslesen von `stdout`/`stderr`.

## Abhängigkeiten zu anderen `de.swfdtf.*`-Paketen
- Keine internen Paketabhängigkeiten (nutzt ausschließlich Java Standard Library und die System-`git`-CLI über `ProcessBuilder`).

## Enthaltene Typen

| Typ | Art | Beschreibung |
| :--- | :--- | :--- |
| `GitController` | `final class` | Kapselt `git init`, `status`, `add`, policy-konformes `commit`, `push` und `log` für ein Ziel-Repository mit konfigurierbarem Timeout (`DEFAULT_TIMEOUT = 30s`). |
| `GitController.CommandResult` | `record` | Unveränderliches Ergebnis einer Git-CLI-Ausführung mit `exitCode`, `stdout`, `stderr` und `success`-Flag. |

## Öffentliche API-Signaturen

### `GitController` & `GitController.CommandResult`
```java
public final class GitController
public static final Set<String> ALLOWED_COMMIT_TYPES = Set.of(
        "feat", "fix", "arch", "render", "parser", "audio", "ui", "docs", "test", "build"
)
public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30)

public record CommandResult(int exitCode, String stdout, String stderr, boolean success)

public GitController(Path repoRoot)
public GitController(Path repoRoot, Duration commandTimeout)
public Path getRepoRoot()
public boolean isGitRepository()
public CommandResult init()
public CommandResult status()
public CommandResult add(List<Path> files)
public CommandResult commit(String type, String module, String summary, List<String> bulletPoints)
public static String formatCommitMessage(String type, String module, String summary, List<String> bulletPoints)
public CommandResult push(String remote, String branch)
public CommandResult log(int limit)
```
