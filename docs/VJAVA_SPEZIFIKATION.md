# `.vjava` Sprach- und Projekt-Spezifikation (M4-Stand)

> **Implementierungsstand (Meilenstein M4 abgeschlossen)**: Alle in diesem Dokument beschriebenen `.vjava`-Sprachkonstrukte, Projektscan-Regeln, Ausdrucks- und Bedingungsgrammatiken, die In-Memory-Java-21-Kompilierung sowie das 4-Tier-State-, Rollback-, Save/Load- und Lokalisierungssystem sind vollständig implementiert und verifiziert:
> * **M1**: Projekt-Metadaten (`ProjectDescriptor` für `project.vjava.json`), Laufzeit-Konfiguration (`EngineConfig`) und Java-Skript-Fassade (`EngineAPI`, Binding `engine`).
> * **M2**: Komplette Inline-Rich-Text-Syntax inklusive Variableninterpolation, Flow-Control-Cues, Furigana, Inline-Bildern, Hyperlinks und Text-Animationstags (`TextTagParser`, `StyledTextLayout`, `TypewriterController`, `FontAtlas`, `FontManager`) sowie `CustomDisplayable`.
> * **M3**: Vollständiger Lexer (`VJavaLexer`), rekursiv-absteigender AST-Parser (`VJavaParser` / `VJavaAst`), rekursiver Projektscanner (`ProjectScanner`), In-Memory-Java-21-Compiler (`JavaBlockCompiler` / `VJavaExecutable`), Ausdrucks-Evaluator (`ConditionEvaluator`), nicht-blockierende Skript-Laufzeit (`ScriptRuntime`), Bühnensteuerung (`SceneController`, Binding `scene`) sowie State-Verträge (`StateStore` als `state`, `PersistentAccess` als `persistent`).
> * **M4**: Sitzungskoordinator (`ScriptSession`), deterministischer Rollback-/Roll-Forward-Speicher (`RevertableStateStore`, `RevertableMap`, `RevertableList`), 4-Tier-Persistenz (`SessionState`, `PersistentState`, `PreferencesState`), `HistoryLog`, atomares GZIP-JSON-Save/Load mit PNG-Thumbnails und `__vjavaClass`-Codec (`SaveManager`, `SaveSlotData`, `SaveValueCodec`), Meta-Progression (`AchievementManager`, `GalleryManager`, `EndingsManager`) sowie Live-Lokalisierung (`LocalizationManager`).

---

## 1. Projekt-Dateisystemstruktur & `ProjectScanner`

Ein KAGAYAKI-Projekt besitzt folgende Struktur:

```text
mein_vn_projekt/
├── project.vjava.json        # Projekt-Metadaten (geladen über ProjectDescriptor, sonst Defaults)
├── main.vjava                # Beliebig im Projektordner platzierbar
├── kapitel_1/
│   └── szene_01.vjava        # Rekursiv gescannte *.vjava-Dateien in beliebiger Tiefe
├── assets/                   # Feste Asset-Verzeichnisse (rekursiv durch ProjectScanner indiziert)
│   ├── images/               # Hintergründe, Sprites, Layered-Parts, UI-9Slice
│   ├── audio/                # Musik, SFX, Voice-Over
│   ├── video/                # Cutscenes & animierte Hintergründe
│   └── fonts/                # TTF/OTF-Schriftarten
├── tl/                       # Übersetzungsdateien (.vjava translate) & tl/<lang>/-Assets (M4)
└── saves/                    # Spielstände (*.ksav), persistent.dat & preferences.json (M4)
```

### Regeln des `ProjectScanner`
* **Rekursive Skriptsuche**: Alle `*.vjava`-Dateien unter `projectRoot` werden rekursiv gefunden, unabhängig von der Verzeichnistiefe.
* **Ausgeschlossene Verzeichnisse (`ProjectScanner.SKIPPED_DIRECTORIES`)**: `.git`, `build`, `.gradle`, `saves`, `out`, `dist`.
* **Deterministische Reihenfolge**: Gefundene Dateien werden lexikografisch nach ihrem relativen Unix-Pfad (`/`-getrennt) sortiert geparst.
* **Duplikat-Semantik (Last-Wins)**: Doppelte Definitionen (`character`, `image`, `layeredimage`, `transform`, `label`, `screen`) erzeugen eine nicht-fatale Warnung in `ProjectAst.warnings()` (mit Datei- und Zeilenangabe) und werden deterministisch durch den späteren Eintrag überschrieben.
* **`project.vjava.json`**: Fehlt die Datei, erzeugt `ProjectScanner` automatisch `ProjectDescriptor.createDefault(ordnername)`.

### Verifizierte `project.vjava.json`-Felder (`ProjectDescriptor`)
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

## 2. Lexikalische Regeln & Einrückung (`VJavaLexer`)

`.vjava`-Dateien kombinieren zwei klar getrennte Syntaxebenen:

### 2.1 Zeilen-, Kommentar- & Einrückungsregeln (VN-Skriptebene)
* **Kodierung & Zeilenenden**: UTF-8 (ein optionales UTF-8-BOM `\uFEFF` wird entfernt); `\r\n` und `\r` werden zu `\n` normalisiert.
* **Einrückung (`INDENT` / `DEDENT`)**:
  * Blöcke nach `:` werden über konsistente Einrückung strukturiert (Konvention: **4 Leerzeichen**; ein Tabulator `\t` zählt als `4` Spalten).
  * Ein `VJavaLexer` verwaltet einen Einrückungs-Stack (Start bei `0`). Jede tiefere Einrückung erzeugt `INDENT`, jedes Zurückspringen erzeugt ein oder mehrere `DEDENT`-Tokens bis zur passenden Stack-Tiefe.
  * Passt ein Zurückspringen auf keine vorherige Einrückungsebene, wird sofort `VJavaParseException` mit Quelldatei und Zeilennummer geworfen.
  * Leerzeilen und reine Kommentarzeilen beeinflussen den Einrückungs-Stack nicht.
* **Kommentare (außerhalb von `@->{ ... }<<`)**:
  * Sowohl `# ...` als auch `// ...` leiten Zeilen- bzw. Endkommentare außerhalb von Anführungszeichen (`"..."`, `'...'`) ein.
  * `#`-Kommentare unmittelbar vor Einträgen in `translate`-Blöcken werden vom Lexer mitgeführt, sodass `VJavaParser` den Originaltext automatisch aus `# "..."` bzw. `# speaker "..."` extrahieren kann.
* **Geschweifte Klammern `{ ... }`**: Auf VN-Skriptebene existieren **keine** `{}`-Codeblöcke; `{ ... }` innerhalb von Dialog-Strings sind ausschließlich Inline-Rich-Text-Tags (siehe Abschnitt 6).

### 2.2 Native Java-Ebene (`:: Kode für VN ... @->{ ... }<<`)
* Eingebettete Java-21-Blöcke werden mit `@->{` geöffnet und mit `}<<` geschlossen:
  * **Einzeilig**: `@->{ state.set("punkte", 10); }<<` oder `:: Punkte setzen @->{ state.set("punkte", 10); }<<`
  * **Mehrzeilig mit Einzeilen-Header**:
    ```vjava
    :: Kode für VN: Eigene Logik @->{
        state.set("besucht", true);
    }<<
    ```
  * **Zweizeiliger Header** (`::` auf der Zeile unmittelbar vor `@->{`):
    ```vjava
    :: Kode für VN: Beschreibung
    @->{
        engine.logInfo("Hallo");
    }<<
    ```
* Das Präfix `::` (mit optionalem `Kode für VN:` / `Kode fuer VN:`) wird bereinigt und als `JavaBlockNode.description()` gespeichert.
* Innerhalb von `@->{ ... }<<` verfolgt `VJavaLexer` Java-Kommentare (`//`, `/* ... */`), String-Literale (`"..."`), Java-Textblöcke (`""" ... """`) und Zeichenliterale (`'...'`), sodass geschweifte Klammern `{ ... }` und selbst die Zeichenfolge `}<<` innerhalb von Strings oder Kommentaren den Block nicht vorzeitig schließen.
* Nach `}<<` sind auf derselben Zeile nur noch Leerzeichen oder Kommentare (`//`, `#`) zulässig; alles andere wirft `VJavaParseException`. Ein fehlendes `}<<` vor Dateiende wirft ebenfalls `VJavaParseException`.

---

## 3. Top-Level-Deklarationen (`VJavaParser`)

Auf Einrückungstiefe `0` sind folgende Deklarationen gültig:

### 3.1 `define`, `default` & Top-Level-`$`
* **Charakterdefinition (`CharacterDef`)**:
  ```vjava
  define lena = Character("Lena", color="#58a6ff", outline_color="#000000", image="lena", voice_tag="lena", textbox="modern", voice_bleep="assets/audio/bleep.ogg")
  define narrator = Character(None)
  ```
  * Erstes Positionsargument ist der Anzeigename (`None` oder `null` erzeugt einen leeren Anzeigenamen).
  * Unterstützte benannte Felder: `name`, `color` / `who_color`, `outline_color` / `who_outline_color`, `image`, `voice_tag`, `textbox`, `voice_bleep` sowie beliebige Zusatz-Properties in `CharacterDef.properties()`.
* **Konstanten / globale `define`-Werte**: `define max_punkte = 100` (wird als `SetVarStatement(..., isDefaultOnly=false)` gespeichert und vor Spielstart über `ConditionEvaluator` ausgewertet).
* **Standardvariablen (`default`)**: `default punkte = 0` — setzt die Variable bei Spielstart nur, falls `state.has(varName)` noch `false` ist (`isDefaultOnly=true`).
* **Top-Level-Zuweisung (`$`)**: `$ debug_modus = true` (unterstützt `=`, `+=`, `-=`, `*=`, `/=`).

### 3.2 `image`
```vjava
image bg_akademie = "assets/images/bg/akademie.png"
image lena happy winter = "assets/images/char/lena_happy_winter.png"
```
* Das erste Wort nach `image` ist `tag`, alle weiteren Wörter vor `=` sind `attributes`. Der zusammengesetzte Schlüssel (`ImageDef.fullKey()`) wird automatisch bei `SceneController.registerImageDefinition(...)` registriert.

### 3.3 `layeredimage`
```vjava
layeredimage lena:
    always "assets/images/char/lena_base.png"
    group gesicht:
        attribute neutral default "assets/images/char/lena_neutral.png"
        attribute happy if stimmung > 0:
            "assets/images/char/lena_happy.png"
    if hat_brille:
        "assets/images/char/lena_brille.png"
    elif stimmung < 0:
        "assets/images/char/lena_traurig.png"
    else:
        "assets/images/char/lena_normal.png"
```
* `always` akzeptiert sowohl einen direkten Pfad/Ausdruck als auch einen eingerückten Block (`always:`) mit optionalen `if`-Einträgen.
* `group <name>:` enthält `attribute <name> [default] [if <cond>] ["<asset>" | : "<asset>"]`-Einträge.

### 3.4 `transform` (ATL-Transformationsblöcke)
```vjava
transform center_zoom:
    xalign 0.5 yalign 1.0
    zoom 1.0 alpha 0.0
    easein 0.5 zoom 1.1 alpha 1.0
    pause 0.2
    parallel:
        linear 1.0 rotate 5.0
    repeat
```
* Unterstützte ATL-Schritte (`VJavaAst.AtlStep`):
  * **Property-Set (`set`)**: z. B. `xpos`, `ypos`, `xanchor`, `yanchor`, `xalign`, `yalign`, `zoom`, `xzoom`, `yzoom`, `rotate`, `alpha`, `blur`, `parallax_x`, `parallax_y` (Syntax `key val`, `key=val`, inkl. einfacher Rechenausdrücke wie `0.5 + 0.1`).
  * **Warper-Interpolation (`interpolate`)**: `linear`, `ease`, `easein`, `easeout`, `easeinout`, `ease_in`, `ease_out`, `ease_in_out`, `smoothstep`, `ease_back`, `ease_bounce`, `ease_elastic` jeweils mit Dauer in Sekunden sowie optionalen Inline-Properties oder eingerücktem `:`-Block; alternativ `warp <warperExpr> <duration> [props]`.
  * **Steuerung**: `pause <seconds>`, `repeat [count]`, `pass`.
  * **Container**: `parallel:`, `block:`, `choice:`, `contains:`, `on <event>:`.
* `ScriptRuntime` wendet numerische ATL-Eigenschaften auf `Transform2D` an und registriert benannte Transforms automatisch in `SceneController`.

### 3.5 `screen` (Deklarative UI-Screens)
```vjava
screen status_hud(titel):
    modal false
    zorder 250
    frame xpos 40 ypos 40:
        vbox spacing 8:
            text "[titel]" size 28
            textbutton "Schließen" action "hide_screen"
    :: Kode für VN: Screen-Hook @->{
        engine.logDebug("Screen-Item ausgeführt");
    }<<
```
* Kopfzeile: `screen <name>:` oder `screen <name>(param1, param2):`.
* Direktiven auf oberster Screen-Ebene: `modal true|false`, `zorder <int>`.
* Erkannte Widget-Schlüsselwörter (`VJavaParser`): `vbox`, `hbox`, `grid` (inkl. `grid <cols> <rows>`), `fixed`, `frame`, `window`, `viewport`, `vpgrid`, `textbutton`, `imagebutton`, `text`, `label`, `bar`, `vbar`, `slider`, `input`, `hotspot`, `hotbar`, `imagemap`, `draggroup`, `drag`, `key`, `timer`, `custom`, `add`, `null`, `button`, `side`, `mousearea`, `use`, `on`, `if`, `elif`, `else`, `for` sowie eingebettete `@->{ ... }<<`-Blöcke (`keyword="java_block"`).

### 3.6 `translate` (Lokalisierungsblöcke & M4-Laufzeitauflösung)
```vjava
translate deutsch kapitel_1_01:
    # lena "Good morning!"
    lena "Guten Morgen!"

    old "Start Game"
    new "Spiel starten"
```
* **Syntax & Parser (`VJavaParser`)**:
  * Kopfzeile: `translate <lang> <id>:`.
  * String-Paare `old "..."` gefolgt von `new "..."` (konventionsgemäß unter `translate <lang> strings:`).
  * Narrator-Einträge `"<übersetzt>"` (optional mit `# "<original>"` in der Kommentarzeile davor).
  * Sprecher-Einträge `<speaker> "<übersetzt>"` (optional mit `# <speaker> "<original>"` davor).
* **Laufzeitauflösung (`LocalizationManager` & `ScriptRuntime`, M4)**:
  * `LocalizationManager.indexProject(ProjectAst)` indiziert alle `TranslateBlock`-Einträge (case-insensitiv nach Sprachkennung) sowohl über `(language, id)` (sofern `id != "strings"`) als auch über `(language, originalText)`. Leere Übersetzungstexte (`""`, z. B. unausgefüllte Templates) werden übersprungen, sodass sauber auf den Originaltext zurückgefallen wird.
  * **Dialog-Übersetzung**: Bei Eintritt in `WAITING_DIALOGUE` (und beim Laden über `restoreSavedPosition`) fragt `ScriptRuntime` den angehängten oder über `EngineAPI` bezogenen `LocalizationManager.translateDialogue(...)` zunächst mit `<currentLabel>_<lineNumber>` und anschließend mit der kanonischen `lineId` (`<sourceFile>:<lineNumber>:<hash>`) bzw. dem Originaltext ab, **bevor** `[var]`-Platzhalter interpoliert werden.
  * **UI- & Menü-Übersetzung**: Menü-Captions, `MenuChoice`-Texte und `input`-Prompts werden analog über `LocalizationManager.translateUi(...)` aufgelöst.
  * **Live-Sprachwechsel & Asset-Overrides**: `LocalizationManager.setLanguage(lang)` schaltet die aktive Sprache zur Laufzeit ohne Neustart um (inkl. Listener-Benachrichtigung). `resolveLocalizedAsset(projectRoot, assetPath)` prüft automatisch `tl/<lang>/<assetPath>` sowie bei `assets/...`-Pfaden `tl/<lang>/<ohne-assets-prefix>`, bevor auf das Original-Asset zurückgefallen wird.
  * **Template-Extraktion**: `LocalizationManager.extractTranslationTemplate(project, targetLanguage)` scannt alle `LabelNode`s und `ScreenDef`s und erzeugt eine direkt von `VJavaParser` einlesbare `.vjava`-Übersetzungsvorlage (`translate <lang> <id>:` + `translate <lang> strings:`).

### 3.7 `label` & Top-Level-Java-Blöcke
* `label <name>:` (oder `label <name>(...):`) definiert ein ausführbares Skript-Label mit eingerücktem Statement-Block (`LabelNode`).
* Top-Level-`@->{ ... }<<`-Blöcke werden vor Eintritt in das Entry-Label einmalig beim `ScriptRuntime.start(...)` in Deklarationsreihenfolge ausgeführt (ideal für Klassen-, Record- und Hilfsmethoden-Definitionen).

---

## 4. Label-Statements & Laufzeitsemantik (`VJavaParser` & `ScriptRuntime`)

Innerhalb eines `label:`-Blocks (sowie verschachtelt in `if`/`elif`/`else` und `menu`-Auswahlblöcken) sind folgende Anweisungen verfügbar:

### 4.1 Dialog & Textfenster
| Syntax | AST-Typ / Bedeutung |
| :--- | :--- |
| `"<text>" [with <trans>]` | Narrator-Dialogzeile (`SayStatement` mit `speakerId = null`); wartet im Zustand `WAITING_DIALOGUE`. |
| `<speakerId> "<text>" [with <trans>]` | Charakter-Dialogzeile; löst `CharacterDef.displayName()` und `colorHex()` auf. |
| `nvl show` / `nvl hide` / `nvl clear` | Aktiviert/deaktiviert den kumulativen NVL-Puffer (`nvlMode`) bzw. leert `nvlLines`. |
| `window show` / `window hide` / `window auto` | Steuert das Dialogfenster-Verhalten (`ScriptRuntime.getWindowAction()` und `EngineAPI`-Property `"dialogueWindowAction"`). |

* Vor jeder Dialogzeile interpoliert `ScriptRuntime` `[var]`-Platzhalter über `TextTagParser.interpolateVariables` (Lookup in `state`, `persistent` und für `.`/`[`/`(`-Ausdrücke via `ConditionEvaluator`).
* Jede Zeile erhält eine deterministische `lineId` (`<sourceFile>:<lineNumber>:<hash>`). `DialoguePrompt.alreadyRead()` gibt an, ob die Zeile vor Anzeige bereits in `persistent.isLineRead(lineId)` markiert war; danach wird `persistent.markLineRead(lineId)` aufgerufen.

### 4.2 Bühne, Sprites, Transitions & Pausen (`SceneController`)
| Syntax | Verhalten |
| :--- | :--- |
| `scene <bgTag> [with <trans>]` | Leert `LAYER_MASTER`, setzt den Hintergrund auf `LAYER_BACKGROUND` (`1920×1080`) und löst optionale Transition aus. |
| `show <tag> [attrs...] [at <pos>] [with <trans>]` | Zeigt/aktualisiert ein Sprite auf `LAYER_MASTER`. Ist `tag` bereits sichtbar und wird kein `at <pos>` angegeben, bleibt Position/Transform erhalten; `at` und `with` dürfen in beliebiger Reihenfolge stehen. |
| `show <tag> ... :` + ATL-Block | Wie `show`, wendet zusätzlich Inline-ATL-Schritte auf das `Transform2D` des erzeugten `SpriteNode` an. |
| `hide <tag> [with <trans>]` | Entfernt das Sprite mit dem semantischen `tag` aus `LAYER_MASTER`. |
| `with <transitionExpr>` | Löst Transitionseffekte aus (setzt `EngineAPI`-Property `"lastTransition"`; `vpunch` und `hpunch` starten `ScreenShake.vpunch()` / `ScreenShake.hpunch()` am `SceneGraph`). |
| `pause` / `pause hard` | Pausiert im Zustand `PAUSING` bis `advance()`. |
| `pause <seconds>` | Zeitgesteuerte Pause in `PAUSING`; läuft über `ScriptRuntime.update(dt)` ab oder wird per `advance()` übersprungen. |

* **Eingebaute benannte Positionen in `SceneController`**:
  * `left` (`xalign=0.15, yalign=1.0`)
  * `center` (`xalign=0.5, yalign=1.0`, Standard bei neuem Sprite ohne `at`)
  * `truecenter` (`xalign=0.5, yalign=0.5`)
  * `right` (`xalign=0.85, yalign=1.0`)
  * `offscreenleft` (`xpos=-600, yalign=1.0`)
  * `offscreenright` (`xpos=2520, yalign=1.0`)
  * `reset` (Standard-`Transform2D()`)
* **Asset-Pfad-Auflösung in `SceneController`**: Prüft zuerst `image`-/`layeredimage`-Definitionen (`"<tag> <attrs...>"`, dann `"<tag>"`), danach direkte Pfade (enthält `/` oder endet auf `.png`/`.jpg`/`.webp`), ansonsten Fallback-Kandidaten unter `assets/images/`, `assets/images/bg/`, `assets/images/char/`, `assets/images/sprites/`.

### 4.3 Audio (`play`, `queue`, `stop`, `voice`)
| Syntax | Verhalten |
| :--- | :--- |
| `play <channel> "<asset>" [loop\|noloop] [fadein <sec>] [volume <vol>]` | Spielt Audio auf `<channel>` ab (`loop` ist standardmäßig `true` für `music` und `ambient`, sonst `false`). Bei `channel="music"` wird automatisch `persistent.unlockTrack(assetPath)` aufgerufen. |
| `queue <channel> "<a.ogg>", "<b.ogg>"` / `queue <channel> ["a.ogg", "b.ogg"]` / `queue <channel>:` + eingerückte Liste | Reiht einen oder mehrere Audio-Pfade in die Kanal-Warteschlange ein. |
| `stop <channel> [fadeout <sec>]` | Stoppt den Kanal (optional mit Fade-Out-Dauer). |
| `voice "<asset>"` | Hinterlegt den Voice-Pfad für die nachfolgende Dialogzeile und meldet ihn an `AudioBridge.voice(...)`. |

### 4.4 Auswahlmenüs (`menu`)
`ScriptRuntime` wechselt in `Status.WAITING_MENU` und unterstützt sowohl **Inline-Sprünge** als auch **eingerückte Statement-Blöcke** sowie **Timeouts** und **Bedingungen**:
```vjava
menu "Was möchtest du tun?":
    timeout 5.0 -> jump zeit_abgelaufen

    "Den Flur untersuchen" -> jump flur_szene
    "Mit Lena sprechen" if kennt_lena -> lena_gespraech
    "Geheime Tür öffnen" disabled if hat_schluessel:
        $ tuer_offen = true
        "Die Tür öffnet sich knarrend."
```
* **Caption**: Optional direkt nach `menu` (`menu "Frage":`) oder als erste alleinstehende String-Zeile im `menu:`-Block.
* **Timeout**: `timeout <sec> -> [jump] <label>` oder `timeout <sec>:` mit eingerücktem `jump <label>`. Läuft der Timer in `update(dt)` ab, springt die Laufzeit zum `timeoutJumpLabel` (oder wählt die erste sichtbare/aktive Option).
* **Auswahloptionen**:
  * Inline-Sprung: `"<text>" [qualifiers] -> [jump] <label>`
  * Eingerückter Block: `"<text>" [qualifiers]:` gefolgt von beliebigen Statements.
  * Qualifier: `if <cond>` (versteckt die Option bei `false`), `disabled` (dauerhaft deaktiviert), `disabled if <cond>` / `if <cond> disabled` (sichtbar, aber bei `false` nicht auswählbar).

### 4.5 Bedingungen, Variablen, Sprünge, Subroutinen & Eingabe
| Syntax | Verhalten |
| :--- | :--- |
| `if <cond>:` / `elif <cond>:` / `else:` | Wertet Zweige über `ConditionEvaluator.evaluateBoolean(...)` aus und betritt den ersten passenden Block. |
| `$ <var> = <expr>` / `+=` / `-=` / `*=` / `/=` | Wertet `<expr>` via `ConditionEvaluator.evaluate(...)` aus und speichert das Ergebnis in `state`. Verbundoperatoren werden zu `<var> <op> <expr>` expandiert. |
| `jump <label>` / `jump expression <expr>` | Leert lokale Block-Frames und springt direkt in das Ziel-Label (kein Call-Frame). |
| `call <label>` / `call <label>(arg1, arg2)` / `call expression <expr>` | Wertet optionale Argumente aus (abgelegt in `state.get("_call_args")`), legt ein `StackFrame(isCallBoundary=true)` auf den Call-Stack und springt ins Sub-Label. |
| `return` / `return <expr>` | Speichert optionalen Rückgabewert in `state.get("_return")`. Kehrt zum Aufrufer zurück; ist der Call-Stack leer, endet das Skript (`Status.ENDED`). |
| `input <var> "<prompt>" [default "..."] [allow "..."] [length <n>]` | Pausiert in `WAITING_INPUT`; `submitInput(text)` filtert anhand `allow` (Zeichen-Whitelist) und `length` (Maximalzeichenzahl) und schreibt nach `state`. |
| `block_rollback` | Ruft `state.blockRollback()` auf und setzt in `RevertableStateStore` eine unumkehrbare Rollback-Barriere (`pendingRollbackBarrier` → `Checkpoint.rollbackBarrierBefore`). Weder schrittweises `rollback()` noch `HistoryLog`-`jumpToCheckpoint(...)` können diese Grenze rückwärts überschreiten. |
| `pass` | Expliziter No-Op-Platzhalter in ansonsten leeren Blöcken. |
| `:: ... @->{ ... }<<` / `@->{ ... }<<` | Führt den kompilierten Java-Block mit `(state, persistent, engine, scene)` aus. |

---

## 5. Ausdrucks- & Bedingungssprache (`ConditionEvaluator`)

`ConditionEvaluator` wird für `if`/`elif`, `menu`-`if`-Qualifier, `$`-Zuweisungen, `jump expression`, `return <expr>`, `call`-Argumente und komplexe `[...]`-Textinterpolationen verwendet.

* **Literale**: Ganzzahlen (`int`/`long`, Dezimal & Hex `0x...`, `_`-Trennzeichen), Gleitkomma (`float`/`double`, z. B. `.5`, `1.25f`, `1e-3`), Strings (`"..."` und `'...'` mit Escapes `\n`, `\t`, `\r`, `\\`, `\"`, `\'`), `true`, `false`, `null`.
* **Operatoren (nach Präzedenz)**:
  1. Postfix: `.feld`, `.getFeld()`, `.isFeld()`, `.methode(args...)`, `[index]` (`Map`, `List`, `String`, Array, `array.length`)
  2. Unär: `!`, `not`, `-`, `+`
  3. Multiplikativ: `*`, `/`, `%`
  4. Additiv: `+`, `-` (`+` unterstützt String-Konkatenation)
  5. Relational: `<`, `<=`, `>`, `>=`
  6. Gleichheit: `==`, `!=` (inkl. numerischer Typ-Coercion `int`/`long`/`double`)
  7. Logisches UND: `&&`, `and` (Short-Circuit)
  8. Logisches ODER: `||`, `or` (Short-Circuit)
  9. Ternär: `<cond> ? <a_expr> : <b_expr>`
* **Vordefinierte Bezeichner**: `state` (`StateStore`), `persistent` (`PersistentAccess`), `Math` (`java.lang.Math`), sowie jeder Variablenname direkt aus `state` (Priorität) bzw. `persistent`.
* **KAGAYAKI-Truthiness (`isTruthy`)**:
  * `null` → `false`
  * `Boolean` → direkt `b`
  * `Number` → `d != 0.0 && !Double.isNaN(d)`
  * `String` → `!s.isEmpty()`
  * `Character` → `c != '\0'`
  * Jedes andere nicht-`null`-Objekt → `true`

---

## 6. Native Java-Kompilierung (`JavaBlockCompiler`) & Bindings

`JavaBlockCompiler` kompiliert alle `@->{ ... }<<`-Blöcke (Top-Level sowie innerhalb von `label`, `if`, `menu`) zur Laufzeit über `javax.tools.ToolProvider.getSystemJavaCompiler()` vollständig im Arbeitsspeicher (ohne temporäre `.java`/`.class`-Dateien auf der Festplatte):

1. **Segmentierung**: Jeder Block wird unter Berücksichtigung von Kommentaren, Strings, Textblöcken und Klammer-Tiefen in vier Kategorien zerlegt:
   * `import`-Anweisungen (werden projektweit dedupliziert und allen Snippets sowie `VJavaUserTypes` vorangestellt)
   * Benutzerdefinierte Typen (`class`, `interface`, `enum`, `record`, `@interface`, inkl. Unterklassen z. B. `extends CustomDisplayable`)
   * Top-Level-Statik-Member (Methoden, Felder, `static { ... }`-Blöcke)
   * Ausführbare Snippet-Anweisungen
2. **Gemeinsamer Typ-Container (`de.swfdtf.vjava.gen.VJavaUserTypes`)**:
   * Alle deklarierten Typen und statischen Hilfsmethoden werden als `public static ...` in `VJavaUserTypes` gebündelt und **vor** den Snippets kompiliert.
   * Dadurch können nachfolgende `@->{ ... }<<`-Blöcke und `ConditionEvaluator`-Ausdrücke (z. B. `aktives_item.wert`) nahtlos auf eigene Klassen, Records, Enums und Methoden aus demselben oder anderen `.vjava`-Dateien zugreifen.
   * Über `CompiledProjectBlocks.loadUserType(name)` / `findUserType(name)` sind diese Klassen auch direkt aus Java ladbar.
3. **Snippet-Klassen (`de.swfdtf.vjava.gen.VJavaSnippet_<i>`)**:
   * Implementieren `VJavaExecutable` (`execute(StateStore state, PersistentAccess persistent, EngineAPI engine, SceneController scene) throws Exception`).
   * Automatisch importierte Pakete: `de.swfdtf.state.*`, `de.swfdtf.core.*`, `de.swfdtf.graphics.*`, `de.swfdtf.math.*`, `java.util.*`, `de.swfdtf.parser.VJavaExecutable` sowie `VJavaUserTypes.*` und `static VJavaUserTypes.*`.
4. **Zeilen-gemappte Fehlerdiagnose**: Kompilierungsfehler werfen `VJavaCompileException` mit strukturierten `CompileDiagnostic`-Einträgen (`blockId`, `sourceFile`, auf die `.vjava`-Ursprungszeile zurückgemappte `lineNumber`, `columnNumber`, `kind`, `message`).

### Verfügbare Bindings innerhalb von `@->{ ... }<<`
| Binding | Typ | Verantwortung |
| :--- | :--- | :--- |
| `state` | `de.swfdtf.state.StateStore` | Veränderbare Spielvariablen (`set`, `get`, `getOrDefault`, `getString`, `getInt`, `getDouble`, `getBoolean`, `has`, `remove`, `keys`, `blockRollback`, `clear`). |
| `persistent` | `de.swfdtf.state.PersistentAccess` | Globale spielstandübergreifende Daten (`set`, `get`, `has`, `markLineRead`/`isLineRead`, `unlockCg`, `unlockTrack`, `unlockEnding`, `unlockAchievement`). |
| `engine` | `de.swfdtf.core.EngineAPI` | Engine-Logging (`logInfo`, `logWarn`, `logError`, `logDebug`), `getConfig()`, `getProjectDescriptor()`, `getClock()`, `getInput()`, `requestQuit()`, `requestLiveReload()`, Properties & Service-Locator. |
| `scene` | `de.swfdtf.graphics.SceneController` | Programmatische Bühnensteuerung (`setScene`, `showSprite`, `hideSprite`, `registerImageDefinition`, `registerTransform`, `clearMasterSprites`, `getActiveSprite`, `getSceneGraph`). |

---

## 7. Verifizierte Inline-Rich-Text-Tags & Interpolation (`TextTagParser`, M2)

Alle hier aufgeführten Tags, Interpolationen und Escape-Sequenzen sind in `de.swfdtf.graphics.TextTagParser`, `StyledTextLayout` und `StyledTextLayout.TypewriterController` aktiv und werden von `ScriptRuntime` automatisch für Dialoge, Sprechernamen, Menü-Captions, Menü-Optionen und Input-Prompts genutzt:

### 7.1 Variablen-Interpolation & Escaping
| Syntax | Verhalten |
| :--- | :--- |
| `[variablenname]` | Ersetzt den Platzhalter vor dem Tag-Parsing über `state`/`persistent` (oder via `ConditionEvaluator` bei `.`/`[`/`(`); unbekannte Schlüssel bleiben wörtlich als `[key]` stehen. |
| `[[` | Erzeugt eine wörtliche öffnende eckige Klammer `[` ohne Interpolation. |
| `{{` | Erzeugt eine wörtliche öffnende geschweifte Klammer `{` ohne Tag-Interpretation. |

### 7.2 Formatierungs-, Farb- & Metrik-Tags
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

### 7.3 Ablauf-, Pausen- & Typewriter-Cues
| Tag | `CueKind` / Verhalten |
| :--- | :--- |
| `{w}` | `WAIT_CLICK` — pausiert den Typewriter bis zum Klick/Bestätigen des Spielers. |
| `{w=1.5}` | `WAIT_TIMED` — pausiert den Typewriter für die angegebene Dauer in Sekunden (`>= 0`). |
| `{p}` / `{p=0.5}` | `PARAGRAPH` — fügt `\n` ein und pausiert bis zum Klick bzw. für `t` Sekunden. |
| `{nw}` | `NO_WAIT` — markiert den Text für automatisches Weiterblättern ohne Klick-Pause am Ende (`shouldAutoAdvance()`). |
| `{fast}` | `FAST` — deckt alle Zeichen vor dem Tag sofort und ohne Typewriter-Verzögerung auf. |
| `{cps=60}` ... `{/cps}` | Lokale Typewriter-Geschwindigkeit in Zeichen/Sekunde (`0` = sofortige Ausgabe). |

### 7.4 Furigana, Inline-Bilder, Hyperlinks & Echtzeit-Effekte
| Tag | Wirkung |
| :--- | :--- |
| `{rb}漢字{/rb}{rt}かんじ{/rt}` | Ordnet die Lautlesung (`RubyPair` / `RubyLayout`) zentriert oberhalb des Basistexts an und reserviert automatisch Zeilen-Headroom. |
| `{image=pfad}` | Fügt einen `\uFFFC`-Platzhalter (`OBJECT_REPLACEMENT_CHAR`) ein und rendert ein inline skaliertes Bild (`InlineImageSpan` / `InlineImageLayout`); `spokenText()` blendet den Platzhalter für TTS/Logs aus. |
| `{a=ziel}...{/a}` | Erzeugt klickbare `HyperlinkHitbox`-Rechtecke (`StyledTextLayout.hitTestHyperlink(...)`). |
| `{wave}...{/wave}` | Vertikale Sinuswellen-Animation pro Zeichen. |
| `{jitter}...{/jitter}` / `{shake}...{/shake}` | Hochfrequente Zitter-/Shake-Animation pro Zeichen. |
| `{gradient=#c1,#c2}...{/gradient}` | Linearer 2-Stop-Farbverlauf über alle Zeichen des Spans (Trennzeichen `,`, `;` oder Leerzeichen). |

---

## 8. Verifiziertes Referenzbeispiel (M3)

```vjava
define lena = Character("Lena", color="#58a6ff", image="lena", voice_tag="lena")
default punktzahl = 0

:: Kode für VN: Eigene Java-Klasse definieren @->{
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
    show lena happy at left with easeinleft
    :: Kode für VN: Zustand, Persistent & Engine-API nutzen @->{
        engine.logInfo("Szene gestartet in " + engine.getEngineVersion());
        state.set("aktives_item", new InventarItem("Sternenschlüssel", 42));
        persistent.unlockCg("cg_akademie_intro");
    }<<
    lena "Willkommen in {gradient=#58a6ff,#f0f6fc}KAGAYAKI{/gradient}, du hast [aktives_item.name] ({w=0.4}{rb}準備{/rb}{rt}じゅんび{/rt})."
    menu "Wie weiter?":
        "Schlüssel einsetzen" if aktives_item.wert >= 40 -> jump geheimraum
        "Zurückgehen" -> jump ende
    return
```
