# Contributing to KAGAYAKI

Vielen Dank für dein Interesse an **KAGAYAKI**!

## Code-Stil & Qualität

- **Java 21 LTS** ohne `java.awt.*` oder `javax.imageio.*` (macOS `-XstartOnFirstThread` Kompatibilität).
- **Checkstyle (`checkstyleMain` / `checkstyleTest`)**:
  - 4 Leerzeichen Einrückung (keine Tabs), K&R-Klammern, max. 120 Zeichen je Zeile
  - Alphabetische Imports (`de.swfdtf.*`/`org.*`/`com.*`, Leerzeile, `java.*`, Leerzeile, `import static`), keine ungenutzten Imports
- **Javadoc (`-Xdoclint:all -Werror`)**: Öffentliche Klassen, Konstruktoren, Methoden und Records vollständig mit `@param` / `@return` / `@throws` dokumentieren.
- **Paket-Dokumentation**: Jede neue Top-Level-Klasse in `src/main/java/de/swfdtf/<pkg>/` in der zugehörigen `AGENT_CONTEXT.md` eintragen.

## Entwicklungs- & Git-Workflow

1. Tests vor oder parallel zur Änderung schreiben (`JUnit 6`, Mockito).
2. Vor jedem Commit ausführen:
   ```bash
   ./gradlew check javadoc test e2eTest
   ./gradlew -p demo check test lintDemo
   ```
3. Optionalen Pre-Commit-Hook aktivieren:
   ```bash
   git config core.hooksPath scripts/hooks
   ```
4. Aussagekräftige Commit-Titel verwenden (`feat:`, `fix:`, `docs:`, `test:`, `refactor:`).

Siehe auch [docs/HANDBUCH_DE.md](docs/HANDBUCH_DE.md) und [SECURITY.md](SECURITY.md).
