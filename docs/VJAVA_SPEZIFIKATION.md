# `.vjava` Sprach- und Projekt-Spezifikation (M2-Stand)

> **Hinweis zum Implementierungsstand**: Die in diesem Dokument beschriebene `.vjava`-Skriptsyntax bildet die verbindliche Zielspezifikation für Meilenstein **M3**. Bereits vollständig implementiert sind:
> * **M1**: Projekt-Metadaten (`ProjectDescriptor` für `project.vjava.json`), Laufzeit-Konfiguration (`EngineConfig`) sowie die Java-Skript-Fassade (`EngineAPI`, zugänglich als `engine` in `@->{ ... }<<`-Blöcken).
> * **M2**: Die komplette Inline-Rich-Text-Syntax inklusive Variableninterpolation, Flow-Control-Cues, Furigana, Inline-Bildern, Hyperlinks und Text-Animationstags (`TextTagParser`, `StyledTextLayout`, `StyledTextLayout.TypewriterController`, `FontAtlas`, `FontManager`) sowie das erweiterbare `CustomDisplayable`-Interface für prozedurale Grafik in `@->{ ... }<<`-Blöcken.

---

## 1. Projekt-Dateisystemstruktur

Ein KAGAYAKI-Projekt besitzt folgende Struktur:

```text
mein_vn_projekt/
├── project.vjava.json        # Projekt-Metadaten (geladen/gespeichert über ProjectDescriptor)
├── main.vjava                # Beliebig im Projektordner platzierbar
├── kapitel_1/
│   └── szene_01.vjava        # Rekursiv gescannte .vjava-Dateien (M3)
├── assets/                   # Feste Asset-Verzeichnisse
│   ├── images/               # Hintergründe, Sprites, Layered-Parts, UI-9Slice
│   ├── audio/                # Musik, SFX, Voice-Over
│   ├── video/                # Cutscenes & animierte Hintergründe
│   └── fonts/                # TTF/OTF-Schriftarten
├── tl/                       # Übersetzungsdateien (M7)
└── saves/                    # Spielstände & persistent.dat (M4)
```

### Verifizierte `project.vjava.json`-Felder (`ProjectDescriptor`, M1)
| JSON-Feld | Typ | Standardwert | Beschreibung |
| :--- | :--- | :--- | :--- |
| `title` | `String` | `"Untitled KAGAYAKI Project"` | Projekt-Titel |
| `version` | `String` | `"1.0.0"` | Semantische Projektversion |
| `author` | `String` | `""` | Autor oder Studio-Name |
| `renderMode` | `String` | `"2D"` | Render-Modus (`"2D"` oder `"3D"`) |
| `logicalWidth` | `int` | `1920` | Logische Canvas-Breite |
| `logicalHeight` | `int` | `1080` | Logische Canvas-Höhe |
| `entryLabel` | `String` | `"start"` | Einstiegs-Label beim Spielstart |
| `defaultLanguage` | `String` | `"deutsch"` | Standard-Sprachkennung |
| `dialogueMode` | `String` | `"ADV"` | Standard-Dialogmodus (`"ADV"`, `"NVL"`, `"BUBBLE"`) |
| `windowTitle` | `String` | entspricht `title` | Fenstertitel-Override |
| `iconPath` | `String` | `""` | Relativer Pfad zum Fenster-Icon |

---

## 2. Hybride `.vjava`-Syntax

`.vjava`-Dateien kombinieren zwei klar getrennte Syntaxebenen:

### 2.1 Einrückungsbasierte VN-Skriptebene (ohne `{ }`-Blöcke)
* Blöcke werden über konsistente Einrückung (4 Leerzeichen) strukturiert.
* Geschweifte Klammern `{ ... }` werden auf VN-Ebene **ausschließlich** für Inline-Text-Tags (siehe Abschnitt 3) verwendet.
* Geplante Schlüsselwörter (M3–M6):
  * Deklaration: `define`, `layeredimage`, `transform`, `screen`, `translate`
  * Ablauf & Dialog: `label`, `jump`, `call`, `return`, `menu`, `if`, `else`
  * Szene & Audio: `scene`, `show`, `hide`, `with`, `play`, `queue`, `stop`, `voice`
  * Deklarative UI: `vbox`, `hbox`, `grid`, `fixed`, `frame`, `window`, `viewport`, `vpgrid`, `textbutton`, `imagebutton`, `hotspot`, `hotbar`, `bar`, `vbar`, `slider`, `input`, `key`, `timer`, `draggroup`, `drag`

### 2.2 Native Java-Ebene (`:: Kode für VN ... @->{ ... }<<`)
* Java-Logik, Methoden und **eigene Klassen/Unterklassen** (z. B. Implementierungen von `de.swfdtf.graphics.CustomDisplayable`) stehen zwischen `@->{` und `}<<` (optional eingeleitet mit `:: Kode für VN: <Beschreibung>`).
* Innerhalb von `@->{ ... }<<` gilt regulärer Java-21-Code **mit** geschweiften Klammern `{ ... }`, Imports und Hilfsklassen.
* Kompilierung zur Laufzeit über die Java Compiler API (`javax.tools.JavaCompiler`, M3).

#### Verifizierte `engine`-Fassade (`de.swfdtf.core.EngineAPI`, aktiv in M1)
Java-Codeblöcke erhalten über die Variable `engine` direkten Zugriff auf `EngineAPI`:
* Logging: `engine.logInfo(msg)`, `engine.logWarn(msg)`, `engine.logError(msg)`, `engine.logError(msg, t)`, `engine.logDebug(msg)`
* Konfiguration & Projekt: `engine.getConfig()`, `engine.getProjectDescriptor()`, `engine.setProjectDescriptor(desc)`
* Subsysteme: `engine.getClock()`, `engine.getInput()`, `engine.getLogger()`
* Lebenszyklus: `engine.requestQuit()`, `engine.isQuitRequested()`, `engine.requestLiveReload()`, `engine.consumeLiveReloadRequested()`
* Metadaten & Services: `engine.getEngineVersion()`, `engine.getRenderBackendName()`, `engine.getProperty(key)` / `setProperty(key, val)`, `engine.getService(type)` / `requireService(type)`

Geplante weitere Bindings für M3–M4: `state` (Rollback-fähiger Sitzungszustand), `persistent` (globaler Zustand), `scene` (Szenen-Steuerung).

---

## 3. Verifizierte Inline-Rich-Text-Tags & Interpolation (`TextTagParser`, aktiv in M2)

Alle hier aufgeführten Tags, Interpolationen und Escape-Sequenzen sind in `de.swfdtf.graphics.TextTagParser`, `StyledTextLayout` und `StyledTextLayout.TypewriterController` vollständig implementiert und getestet.

### 3.1 Variablen-Interpolation & Escaping
| Syntax | Verhalten |
| :--- | :--- |
| `[variablenname]` | Ersetzt den Platzhalter vor dem Tag-Parsing über die Lookup-Funktion (`interpolateVariables`); unbekannte Schlüssel bleiben wörtlich als `[key]` stehen. |
| `[[` | Erzeugt eine wörtliche öffnende eckige Klammer `[` ohne Interpolation. |
| `{{` | Erzeugt eine wörtliche öffnende geschweifte Klammer `{` ohne Tag-Interpretation. |

### 3.2 Formatierungs-, Farb- & Metrik-Tags
Alle Style-Tags arbeiten stack-basiert (geschachtelte Tags werden beim Schließen sauber auf den vorherigen Wert zurückgesetzt):

| Tag | Schließen / Reset | Wirkung |
| :--- | :--- | :--- |
| `{b}` | `{/b}` | Fettdruck (Embolden-Pass im `StyledTextLayout`). |
| `{i}` | `{/i}` | Kursivschrift (gescherte Rendering-Slices). |
| `{u}` | `{/u}` | Unterstreichung (`LineDecoration` mit `DecorationKind.UNDERLINE`). |
| `{s}` | `{/s}` | Durchstreichung (`LineDecoration` mit `DecorationKind.STRIKETHROUGH`). |
| `{sc}` | `{/sc}` | Kapitälchen (Kleinbuchstaben werden in `0.78×`-Skalierung als Großbuchstaben gerendert). |
| `{plain}` | — | Setzt sämtliche aktiven Formatierungs- und Effekt-Stacks auf den Standardzustand zurück. |
| `{color=#RRGGBB}` | `{/color}` | Überschreibt die Textfarbe (unterstützt `#RGB`, `#RGBA`, `#RRGGBB`, `#RRGGBBAA`, auch ohne `#`). |
| `{alpha=0.5}` | `{/alpha}` | Multipliziert die Deckkraft im Bereich `[0.0, 1.0]`. |
| `{size=36}` / `{size=+8}` / `{size=-4}` | `{/size}` | Absolute Pixel-Schriftgröße (`> 0`) oder relatives Größen-Delta. |
| `{font=pfad}` | `{/font}` | Wechselt die Schriftart (Asset-Pfad oder registrierter `FontManager`-Schlüssel). |
| `{k=2.5}` | `{/k}` | Zusätzlicher Zeichenabstand (Kerning) in logischen Pixeln. |

### 3.3 Ablauf-, Pausen- & Typewriter-Cues
| Tag | `CueKind` / Verhalten |
| :--- | :--- |
| `{w}` | `WAIT_CLICK` — pausiert den Typewriter bis zum Klick/Bestätigen des Spielers. |
| `{w=1.5}` | `WAIT_TIMED` — pausiert den Typewriter für die angegebene Dauer in Sekunden (`>= 0`). |
| `{p}` / `{p=0.5}` | `PARAGRAPH` — fügt `\n` ein und pausiert bis zum Klick bzw. für `t` Sekunden. |
| `{nw}` | `NO_WAIT` — markiert den Text für automatisches Weiterblättern ohne Klick-Pause am Ende (`shouldAutoAdvance()`). |
| `{fast}` | `FAST` — deckt alle Zeichen vor dem Tag sofort und ohne Typewriter-Verzögerung auf. |
| `{cps=60}` ... `{/cps}` | Lokale Typewriter-Geschwindigkeit in Zeichen/Sekunde (`0` = sofortige Ausgabe). |

### 3.4 Furigana, Inline-Bilder, Hyperlinks & Echtzeit-Effekte
| Tag | Wirkung |
| :--- | :--- |
| `{rb}漢字{/rb}{rt}かんじ{/rt}` | Ordnet die Lautlesung (`RubyPair` / `RubyLayout`) zentriert oberhalb des Basistexts an und reserviert automatisch Zeilen-Headroom. |
| `{image=pfad}` | Fügt einen `\uFFFC`-Platzhalter (`OBJECT_REPLACEMENT_CHAR`) ein und rendert ein inline skaliertes Bild (`InlineImageSpan` / `InlineImageLayout`); `spokenText()` blendet den Platzhalter für TTS/Logs aus. |
| `{a=ziel}...{/a}` | Erzeugt klickbare `HyperlinkHitbox`-Rechtecke (`StyledTextLayout.hitTestHyperlink(...)`). |
| `{wave}...{/wave}` | Vertikale Sinuswellen-Animation pro Zeichen. |
| `{jitter}...{/jitter}` / `{shake}...{/shake}` | Hochfrequente Zitter-/Shake-Animation pro Zeichen. |
| `{gradient=#c1,#c2}...{/gradient}` | Linearer 2-Stop-Farbverlauf über alle Zeichen des Spans (Trennzeichen `,`, `;` oder Leerzeichen). |

---

## 4. Syntaktisches Referenzbeispiel (Zielformat M3)

```vjava
define lena = Character("Lena", color="#58a6ff", image="lena", voice_tag="lena")

:: Kode für VN: Eigene Java-Klassen & CustomDisplayable definieren @->{
    public static class InventarItem {
        public final String name;
        public final int wert;

        public InventarItem(String name, int wert) {
            this.name = name;
            this.wert = wert;
        }
    }
}<<

label start:
    scene bg_akademie_nacht with dissolve(0.8)
    :: Kode für VN: Engine-Logging & Zustand setzen @->{
        engine.logInfo("Szene gestartet in " + engine.getEngineVersion());
        // state.set(...) folgt in M3/M4
    }<<
    lena "Willkommen in {gradient=#58a6ff,#f0f6fc}KAGAYAKI{/gradient}.{w=0.4} {rb}準備{/rb}{rt}じゅんび{/rt}はいい？"
    return
```
