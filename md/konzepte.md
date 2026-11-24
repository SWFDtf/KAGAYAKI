# Konzepte & Architektur

KAGAYAKI trennt bewusst das Schreiben von Geschichten von der Technik im Hintergrund. Hier erfährst du, wie die Bausteine zusammenarbeiten.

## 1. Unsere Philosophie: Einfach zuerst, mächtig bei Bedarf

Beim Schreiben einer Visual Novel möchtest du dich auf Figuren, Stimmungen und Entscheidungen konzentrieren. Deshalb nutzt KAGAYAKI zwei Ebenen in derselben Datei:

- **Die Geschichten-Ebene (`.vjava`):** Du schreibst Dialoge, Regieanweisungen und Menüs mit einfacher Einrückung (4 Leerzeichen). Es gibt keine geschweiften Klammern für Blöcke und keinen Ballast.
- **Die Java-Ebene (`@->{ ... }<<`):** Wenn du eigene Spielmechaniken, Berechnungen oder komplexe Logik brauchst, fügst du einen Java-21-Block ein. KAGAYAKI kompiliert ihn automatisch im Speicher.

## 2. Das Zwei-Fenster-Prinzip

KAGAYAKI unterscheidet zwei Betriebsarten:

- **Studio-Modus (`--studio`):** Öffnet zwei Fenster gleichzeitig. Fenster A zeigt Werkzeuge wie den Projekt-Explorer und das Protokoll. Fenster B zeigt dein laufendes Spiel und aktualisiert Änderungen sofort per Live-Reload.
- **Standalone-Modus (`--standalone`):** Startet nur das eigentliche Spielfenster. So lieferst du dein fertiges Spiel sauber und ohne Entwicklungswerkzeuge an Spielerinnen und Spieler aus.

## 3. Wie ein Projekt aufgebaut ist

KAGAYAKI findet deine Skripte und Medien automatisch. Du kannst deine Kapitel-Ordner so sortieren, wie es für deine Geschichte am besten passt:

```text
mein_projekt/
├── project.vjava.json    # Titel, Sprache, Auflösung (Standard: 1920x1080)
├── main.vjava            # Einstiegspunkt deiner Geschichte
├── kapitel_1/            # Beliebige Unterordner für deine Szenen
├── assets/
│   ├── images/           # Hintergründe, Figuren und Oberflächen
│   ├── audio/            # Musik, Geräusche und Sprachausgabe
│   ├── video/            # Zwischensequenzen
│   └── fonts/            # Eigene Schriftarten (TTF/OTF)
├── tl/                   # Übersetzungen in andere Sprachen
└── saves/                # Spielstände und Einstellungen
```

## 4. Die Bühne in Schichten

Alles, was auf dem Bildschirm erscheint, liegt auf geordneten Ebenen (genannt **Z-Order**). So bleibt immer klar, was vorne und was hinten liegt:

| Ebene | Was liegt darauf? |
| :--- | :--- |
| Hintergrund (`background`) | Orte, Standbilder, Videos und Panoramen |
| Bühne (`master`) | Figuren-Sprites und zusammengesetzte Bilder |
| Vordergrund (`foreground`) | Partikel wie Regen oder Schnee, Lichteffekte |
| Dialoge (`screens`) | Textfenster (ADV, NVL oder Sprechblasen) und Schnellmenü |
| Menüs & Hinweise | Auswahl-Buttons, Speicherbildschirm und Benachrichtigungen |

## 5. Zurückspulen, Speichern und Barrierefreiheit

Jeder Schritt in einer Geschichte wird ordentlich protokolliert. Dadurch können Spielerinnen und Spieler jederzeit mit dem Mausrad oder der Taste zurückblättern (**Rollback**), ohne dass Spielstände durcheinandergeraten.

Außerdem unterstützt KAGAYAKI drei Dialogstile (klassisch unten, Vollbild-Roman oder Sprechblasen), eine eingebaute Vorlesefunktion (Text-to-Speech) und eine saubere Skalierung auf jede Bildschirmgröße — ohne unscharfe Ränder.
