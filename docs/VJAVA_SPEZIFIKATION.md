# `.vjava` Sprach- und Projekt-Spezifikation (Entwurf / M1-Basis)

> **Hinweis zum Implementierungsstand**: Die in diesem Dokument beschriebene `.vjava`-Skriptsyntax bildet die verbindliche Zielspezifikation für Meilenstein **M3**. In **M1** sind bereits die Projekt-Metadaten (`ProjectDescriptor` für `project.vjava.json`), die Ausführungs-Konfiguration (`EngineConfig`) sowie die Java-Skript-Fassade (`EngineAPI`, zugänglich als `engine` in `@->{ ... }<<`-Blöcken) vollständig implementiert.

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
* Geschweifte Klammern `{ ... }` werden auf VN-Ebene **ausschließlich** für Inline-Text-Tags (z. B. `{b}`, `{color=#58a6ff}`, `{w=0.4}`, `{rb}...{/rb}{rt}...{/rt}`) verwendet.
* Geplante Schlüsselwörter (M3–M6):
  * Deklaration: `define`, `layeredimage`, `transform`, `screen`, `translate`
  * Ablauf & Dialog: `label`, `jump`, `call`, `return`, `menu`, `if`, `else`
  * Szene & Audio: `scene`, `show`, `hide`, `with`, `play`, `queue`, `stop`, `voice`
  * Deklarative UI: `vbox`, `hbox`, `grid`, `fixed`, `frame`, `window`, `viewport`, `vpgrid`, `textbutton`, `imagebutton`, `hotspot`, `hotbar`, `bar`, `vbar`, `slider`, `input`, `key`, `timer`, `draggroup`, `drag`

### 2.2 Native Java-Ebene (`:: Kode für VN ... @->{ ... }<<`)
* Java-Logik, Methoden und **eigene Klassen/Unterklassen** stehen zwischen `@->{` und `}<<` (optional eingeleitet mit `:: Kode für VN: <Beschreibung>`).
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

## 3. Syntaktisches Referenzbeispiel (Zielformat M3)

```vjava
define lena = Character("Lena", color="#58a6ff", image="lena", voice_tag="lena")

:: Kode für VN: Eigene Java-Klassen definieren @->{
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
    lena "Willkommen in KAGAYAKI.{w=0.4} Bereit?"
    return
```
