# Anleitungen & Häufige Fragen

Hier findest du kompakte Rezepte für den Alltag mit KAGAYAKI sowie Antworten auf typische Fragen.

## 1. Entscheidungen und Verzweigungen bauen

Mit `menu:` bietest du den Spielerinnen und Spielern Auswahlmöglichkeiten an. Jede Option leitet zu einem eigenen Block oder einem anderen `label` weiter:

```vjava
label kreuzung:
    "Welchen Weg möchtest du nehmen?"
    menu:
        "Durch den ruhigen Park gehen":
            jump park_szene
        "Die belebte Straße nehmen":
            jump strasse_szene
```

## 2. Bilder und Musik einbinden

Lege Grafiken in `assets/images/` und Musik oder Geräusche in `assets/audio/` ab. Danach nutzt du sie mit kurzen Befehlen direkt in deiner Szene:

```vjava
scene bg park with dissolve
show aoi laechelnd at center
play music "assets/audio/morgenstimmung.ogg" fadein 1.5

a "Hörst du die Vögel? Ein guter Tag beginnt."
```

## 3. Eigene Logik mit Java einfügen

Wenn du Punkte zählen oder eine eigene Berechnung ausführen möchtest, öffnest du einen Java-Block mit `@->{` und schließt ihn mit `}<<`:

```vjava
:: Punkte erhöhen @->{
    int aktuell = state.getInt("mut", 0);
    state.set("mut", aktuell + 1);
}<<
```

Über `state` speicherst du Werte, die automatisch beim Zurückblättern und in Spielständen erhalten bleiben.

## 4. Fertiges Spiel exportieren

Um eine eigenständige Datei für Spielerinnen und Spieler zu erzeugen, führst du diesen Befehl aus:

```bash
./gradlew packageStandalone
```

Das fertige Programm liegt anschließend im Ordner `build/dist/`.

## 5. Häufige Fragen (FAQ)

### Muss ich Java können, um mit KAGAYAKI zu arbeiten?
Nein. Für Dialoge, Szenenwechsel, Bilder, Musik und Entscheidungen reicht die einfache `.vjava`-Skriptsprache völlig aus. Java-Blöcke sind rein optional.

### Was passiert, wenn ein Bild oder eine Datei fehlt?
KAGAYAKI stürzt nicht ab. Stattdessen zeigt die Engine ein deutlich erkennbares Platzhalter-Muster an und schreibt einen klaren Hinweis in das Protokoll.

### Kann ich meine Geschichte in mehrere Sprachen übersetzen?
Ja. Im Ordner `tl/` legst du Übersetzungsdateien und bei Bedarf eigene Sprachaufnahmen oder Grafiken pro Sprache ab.

### Auf welchen Betriebssystemen läuft KAGAYAKI?
Die Engine unterstützt Windows, macOS (inklusive Apple Silicon) und Linux. Die passenden Grafik- und Audio-Bibliotheken werden automatisch über Gradle geladen.
