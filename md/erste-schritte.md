# Erste Schritte

In wenigen Minuten läuft KAGAYAKI auf deinem Rechner. Du brauchst keine tiefen Programmierkenntnisse, um zu beginnen.

## 1. Was du brauchst

- **Java 21:** Die Laufzeitumgebung, auf der KAGAYAKI aufbaut.
- **Ein Texteditor:** Zum Beispiel VS Code, IntelliJ IDEA oder jeder andere Editor, der UTF-8-Textdateien speichert.
- **Das Projekt-Repository:** Lade KAGAYAKI herunter oder klone es mit Git.

Gradle — das Werkzeug zum Bauen und Starten des Projekts — ist über den mitgelieferten `gradlew`-Wrapper bereits enthalten. Du musst es nicht extra installieren.

## 2. KAGAYAKI starten

Öffne ein Terminal im Hauptordner des Projekts und führe diesen Befehl aus:

```bash
./gradlew runStudio
```

Unter Windows nutzt du stattdessen:

```bat
gradlew.bat runStudio
```

Damit startet der **Studio-Modus**: Du siehst ein Werkzeugfenster für dein Projekt und ein zweites Fenster, in dem dein Spiel live läuft.

> **Tipp:** Mit `./gradlew runStandalone` startest du nur das Spielfenster ohne Entwicklungswerkzeuge.

## 3. Deine erste Szene schreiben

Geschichten in KAGAYAKI stehen in Dateien mit der Endung `.vjava`. Hier ist ein vollständiges, kleines Beispiel für eine Datei `main.vjava`:

```vjava
define a = Character("Aoi", color="#7aa2ff")

label start:
    "Willkommen in deiner ersten Geschichte mit KAGAYAKI."
    a "Schön, dass du hier bist! Alles funktioniert."
    return
```

Jede eingerückte Zeile unter `label start:` gehört zu dieser Szene. Die Anführungszeichen ohne Namen davor sind Erzählertext. Mit `a "..."` spricht die Figur *Aoi*.

## 4. Prüfen, ob alles gesund ist

Wenn du sicherstellen möchtest, dass alle Funktionen der Engine sauber arbeiten, kannst du jederzeit die eingebauten Tests ausführen:

```bash
./gradlew test
```

## Wie geht es weiter?

Lies als Nächstes die [Konzepte](konzepte.md), um zu verstehen, wie Ordner, Szenen und Grafik zusammenspielen. Oder springe direkt zu den [Anleitungen & FAQ](anleitungen.md).
