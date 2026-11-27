## Zusammenfassung

<!-- Beschreibe kurz Ziel, Kontext und geänderte Pakete. -->

## Checkliste (Java-Projekt-Leitfaden & KAGAYAKI)

- [ ] Code entspricht Checkstyle (4 Spaces, K&R, <= 120 Zeichen, saubere Imports)
- [ ] `./gradlew check javadoc test e2eTest` läuft ohne Fehler/Warnungen grün
- [ ] `./gradlew -p demo check test lintDemo` läuft ohne Fehler/Warnungen grün
- [ ] Neue Top-Level-Klassen in `src/main/java` sind in der Paket-`AGENT_CONTEXT.md` dokumentiert
- [ ] Keine `java.awt.*`- oder `javax.imageio.*`-Imports in Produktivcode hinzugefügt
- [ ] Bestehende Kommentare/Docstrings wurden respektiert
