# vjava – Sprachreferenz

Vollständige Referenz aller Schlüsselwörter, Anweisungen, Operatoren und Inline-Tags der Skriptsprache **vjava** (Dateiendung `.vjava`).

> **Was ist vjava?** vjava ist die Skriptsprache der Visual-Novel-Engine **KAGAYAKI** (Java 21 + LWJGL 3). Sie kombiniert eine einrückungsbasierte, deklarative Story-Syntax mit eingebettetem **Java 21**, das zur Laufzeit im Arbeitsspeicher kompiliert wird. Es gibt keinen separaten Compiler-Befehl: Die Engine lädt, parst und führt `.vjava`-Dateien direkt aus (siehe [getting-started.md](getting-started.md)).

> **Verifikation:** Alle Syntaxregeln wurden gegen `VJavaLexer`, `VJavaParser`, `ScriptRuntime` und `ObjectMotionController` abgeglichen. Die Beispiele wurden mit dem eingebauten Linter (`VJavaLinter`) geprüft (0 Fehler, 0 Warnungen). Ergänzende Detailtiefe: [VJAVA_SPEZIFIKATION.md](VJAVA_SPEZIFIKATION.md).

## Inhaltsverzeichnis

1. [Allgemeine Syntaxregeln](#1-allgemeine-syntaxregeln)
2. [Top-Level-Deklarationen](#2-top-level-deklarationen)
   `define` · `default` · `$` · `image` · `layeredimage` · `transform` · `screen` · `translate` · `label` · `config` · `include` · `@->{ }<<`
3. [Label-Anweisungen](#3-label-anweisungen)
   Dialog · `nvl` · `window` · `scene` · `show` · `hide` · `with` · `pause` · `play` · `queue` · `stop` · `voice` · `menu` · `if` · `match` · `repeat` · `while` · `random` · `set` · `local` · `parallel` · `after` · `on_event` · `spawn_cutscene` · `$` · `jump` · `call` · `return` · `input` · `block_rollback` · `pass`
4. [Bewegung, Steuerung, Physik & 3D](#4-bewegung-steuerung-physik--3d)
   `move` · `move_by` · `move_path` · `rotate` · `scale` · `wait_move` · `on_tween_end` · `wasd_init` · `wasd_stop` · `point_click_init` · `physics_init` · `physics_stop` · `on_collide` · `on_overlap` · `on_interact` · `collision_mask` · `hitbox` · `trigger_zone` · `solid_platform` · `moving_platform` · `collider` · `climb_zone` · `surface_friction` · `knockback` · `impulse` · `attract` · `camera` · `shake` · `anim_state` · `patrol` · `follow` · `spawn` · `despawn` · `attach` · `ysort` · `time_scale` · `scene3d` · `spawn3d` · `camera3d` · `wasd3d_init` · `fog3d` · `material3d`
5. [Ausdrücke & Operatoren](#5-ausdrücke--operatoren)
6. [Eingebettetes Java](#6-eingebettetes-java----)
7. [Transitions](#7-transitions)
8. [Inline-Text-Tags](#8-inline-text-tags)
9. [Screen-Sprache (UI)](#9-screen-sprache-ui)
10. [Vollständiges Beispiel](#10-vollständiges-beispiel)
11. [Typische Fehler](#11-typische-fehler)

---

## 1. Allgemeine Syntaxregeln

| Regel | Beschreibung |
| :--- | :--- |
| Kodierung | UTF-8 (BOM wird ignoriert); `\r\n` und `\r` werden zu `\n` normalisiert. |
| Einrückung | Blöcke beginnen nach einem `:` und werden durch Einrückung gebildet. Konvention: **4 Leerzeichen**; ein Tab zählt als 4 Spalten. Ein Rücksprung auf eine nicht existierende Ebene wirft `VJavaParseException`. |
| Kommentare | `# …` und `// …` (jeweils bis Zeilenende, außerhalb von Anführungszeichen). |
| Leerzeilen | Leerzeilen und reine Kommentarzeilen sind bedeutungslos. |
| Geschweifte Klammern | Auf Skriptebene gibt es **keine** `{}`-Blöcke. `{…}` in Dialogtexten sind Text-Tags (siehe [Abschnitt 8](#8-inline-text-tags)). |
| Strings | In doppelten Anführungszeichen: `"Text"`. |
| Zugelassene Top-Level-Schlüsselwörter | `define`, `default`, `$`, `image`, `layeredimage`, `transform`, `screen`, `translate`, `label`, `config`, `include` sowie `@->{ … }<<`. Alles andere auf Ebene 0 ist ein Parserfehler. |
| Dateien | Alle `*.vjava` unter dem Projektordner werden rekursiv gefunden und in lexikografischer Pfadreihenfolge zu **einem** Projekt zusammengeführt. Ausgeschlossen: `.git`, `build`, `.gradle`, `saves`, `out`, `dist`. |
| Doppelte Definitionen | Spätere Definitionen überschreiben frühere (*Last-Wins*) und erzeugen eine Warnung. |

### Argument-Schreibweise in dieser Referenz

| Notation | Bedeutung |
| :--- | :--- |
| `<name>` | Pflichtargument |
| `[…]` | optional |
| `a \| b` | Alternative |
| `…` | beliebig oft wiederholbar |

---

## 2. Top-Level-Deklarationen

### 2.1 `define` – Charaktere und Konstanten

**Syntax**

```text
define <id> = Character(<name>, [color=…], [outline_color=…], [image=…], [voice_tag=…], [textbox=…], [voice_bleep=…])
define <id> = <ausdruck>
```

**Beschreibung:** Legt einen Sprecher (`Character`) oder eine globale Konstante an. Ein Sprecher wird später mit `<id> "Text"` verwendet.

**Argumente von `Character(…)`**

| Argument | Typ | Pflicht | Beschreibung |
| :--- | :--- | :--- | :--- |
| 1. Positionsargument / `name` | String oder `None`/`null` | ja | Anzeigename. `None` erzeugt einen leeren Namen (Erzähler). |
| `color` (Alias `who_color`) | String `"#RRGGBB"` | nein | Farbe des Sprechernamens. |
| `outline_color` (Alias `who_outline_color`) | String `"#RRGGBB"` | nein | Konturfarbe des Namens. |
| `image` | String | nein | Bild-Tag des zugehörigen Sprites (für Bubble-Dialoge u. a.). |
| `voice_tag` | String | nein | Kennung für Voice-Zuordnung. |
| `textbox` | String | nein | Textbox-Skin. |
| `voice_bleep` | String (Asset-Pfad) | nein | Tippgeräusch pro Zeichen. |
| beliebige weitere Namen | beliebig | nein | Landen in `CharacterDef.properties()`. |

**Beispiel**

```vjava
define lena = Character("Lena", color="#58a6ff", outline_color="#000000", image="lena", voice_tag="lena")
define narrator = Character(None)
define max_punkte = 100

label start:
    lena "Hallo, ich bin Lena."
    narrator "Und ich spreche ohne Namensschild."
    return
```

### 2.2 `default` – Standardvariablen

**Syntax:** `default <variable> = <ausdruck>`

**Beschreibung:** Setzt eine Spielvariable beim Spielstart **nur**, wenn sie noch nicht existiert (`state.has(name) == false`). Ideal für Anfangswerte, die durch Spielstände überschrieben werden.

| Argument | Typ | Beschreibung |
| :--- | :--- | :--- |
| `<variable>` | Bezeichner | Name der Variablen im `state`. |
| `<ausdruck>` | [Ausdruck](#5-ausdrücke--operatoren) | Startwert (Zahl, String, Boolean, …). |

```vjava
default punkte = 0
default spielername = "Alex"
default hat_schluessel = false

label start:
    "Du hast [punkte] Punkte und heißt [spielername]."
    return
```

### 2.3 `$` auf Top-Level – globale Zuweisung

**Syntax:** `$ <variable> <op> <ausdruck>` mit `<op>` ∈ `=`, `+=`, `-=`, `*=`, `/=`

```vjava
$ debug_modus = true

label start:
    if debug_modus:
        "Debug aktiv."
    return
```

### 2.4 `image` – Bilddefinition

**Syntax:** `image <tag> [attribut …] = "<pfad>"`

**Beschreibung:** Registriert ein Bild. Das erste Wort ist der **Tag**, alle weiteren Wörter vor `=` sind **Attribute**. Der Schlüssel `tag attr1 attr2` wird bei `show`/`scene` aufgelöst.

| Argument | Typ | Beschreibung |
| :--- | :--- | :--- |
| `<tag>` | Bezeichner | Name zum Anzeigen (`show lena`). |
| `attribut …` | Bezeichner (0..n) | Varianten, z. B. `happy`, `winter`. |
| `<pfad>` | String | Pfad relativ zum Projektordner (z. B. `assets/images/bg/hof.png`). Formate: PNG/JPG/WebP. |

```vjava
define lena = Character("Lena")

image bg_hof = "assets/images/bg/hof.png"
image lena happy = "assets/images/char/lena_happy.png"

label start:
    scene bg_hof
    show lena happy
    lena "Ich bin glücklich!"
    return
```

### 2.5 `layeredimage` – mehrstufige Sprites

**Syntax**

```text
layeredimage <tag>:
    always "<pfad>"                    # oder: always: + eingerückter Pfad
    group <gruppenname>:
        attribute <name> [default] [if <bedingung>] ["<pfad>"]    # Pfad alternativ als eingerückte Zeile
    if <bedingung>:
        "<pfad>"
    elif <bedingung>:
        "<pfad>"
    else:
        "<pfad>"
```

| Element | Beschreibung |
| :--- | :--- |
| `always` | Ebene, die immer gezeichnet wird. |
| `group <name>:` | Gruppe sich gegenseitig ausschließender Attribute. |
| `attribute <name>` | Auswählbare Variante. `default` = Standard der Gruppe; `if <bed>` = nur wählbar, wenn die Bedingung wahr ist. |
| `if` / `elif` / `else` | Zustandsabhängige Overlay-Ebene (Bedingung wird gegen `state` ausgewertet). |

Laufzeitverhalten: `show <tag> <attribut …>` wählt Attribute; Attributwechsel innerhalb einer Gruppe überblenden **0,20 s**. Eine Gruppe namens `mouth` mit den Attributen `closed`, `half`/`half_open`, `open` wird automatisch vom Lip-Sync (Voice-Lautstärke) gesteuert.

```vjava
define lena = Character("Lena")
default stimmung = 0

layeredimage lena:
    always "assets/images/char/lena_base.png"
    group gesicht:
        attribute neutral default "assets/images/char/lena_neutral.png"
        attribute happy if stimmung > 0:
            "assets/images/char/lena_happy.png"
    if stimmung > 50:
        "assets/images/char/lena_aura.png"

label start:
    show lena happy
    lena "Sieh mal, mein Gesicht ändert sich."
    return
```

### 2.6 `transform` – ATL-Animationen

**Syntax**

```text
transform <name>:
    <eigenschaft> <wert> [<eigenschaft> <wert> …]
    <warper> <sekunden> <eigenschaft> <wert> …
    pause <sekunden>
    repeat [anzahl]
    parallel: / block: / choice: / contains: / on <event>:
```

**Eigenschaften (Property-Set)**

| Eigenschaft | Typ | Bedeutung |
| :--- | :--- | :--- |
| `xpos`, `ypos`, `pos (x, y)` | Zahl | Absolute Position (logisch 1920×1080). |
| `xanchor`, `yanchor`, `anchor` | Zahl | Ankerpunkt im Sprite. |
| `xalign`, `yalign`, `align` | 0.0–1.0 oder `none` | Relative Position (0.5 = Mitte). |
| `zoom`, `xzoom`, `yzoom` | Zahl | Skalierung. |
| `rotate` | Zahl (Grad) | Drehung. |
| `alpha` | 0.0–1.0 | Deckkraft. |
| `blur` | Zahl | Weichzeichner. |
| `parallax_x`, `parallax_y` | Zahl | Parallaxe. |
| `crop` | `(x, y, w, h)` oder `none` | Ausschnitt. |
| `matrixcolor` | Matrix-Ausdruck | `identity()`, `brightness(b)`, `contrast(c)`, `saturation(s)`, `hue(deg)`, `opacity(a)`, `invert([t])`, `tint(r, g, b[, a])` / `tint("#hex")`, verkettbar mit `*`. |

**Warper (Interpolation):** `linear`, `ease`, `easein`/`ease_in`, `easeout`/`ease_out`, `easeinout`/`ease_in_out`, `smoothstep`, `ease_back`, `cubic_bezier(p1x, p1y, p2x, p2y)`, oder `warp <warperExpr> <dauer> [props]`. Jeweils gefolgt von der Dauer in Sekunden.

**Steuerung:** `pause <sek>` · `repeat [n | infinite | forever]` (`<= 0` = endlos) · `pass` · `parallel:` (Zweige gleichzeitig) · `block:` · `choice:` (zufälliger Zweig) · `contains:` · `on <event>:`.

```vjava
define lena = Character("Lena")
image lena happy = "assets/images/char/lena_happy.png"

transform schweben:
    ypos 500
    easeinout 1.0 ypos 480
    easeinout 1.0 ypos 500
    repeat

transform einblenden:
    xalign 0.5 yalign 1.0
    zoom 1.0 alpha 0.0
    easein 0.5 zoom 1.1 alpha 1.0
    pause 0.2
    parallel:
        linear 1.0 rotate 5.0

label start:
    show lena happy:
        xalign 0.5 yalign 1.0
        alpha 0.0
        linear 0.6 alpha 1.0
    lena "Ich blende sanft ein."
    return
```

### 2.7 `screen` – UI-Screens

Siehe ausführlich [Abschnitt 9](#9-screen-sprache-ui).

**Syntax:** `screen <name>[(<param>, …)]:` + eingerückte Direktiven/Widgets.

```vjava
screen punkte_hud:
    zorder 250
    frame xpos 40 ypos 40:
        text "Punkte: [punkte]" size 28
```

### 2.8 `translate` – Lokalisierung

**Syntax**

```text
translate <sprache> <id>:
    # <sprecher> "<original>"          (optional, Kommentar mit Originaltext)
    <sprecher> "<übersetzung>"         oder  "<übersetzung>" für den Erzähler

translate <sprache> strings:
    old "<originaltext>"
    new "<übersetzung>"
```

| Argument | Beschreibung |
| :--- | :--- |
| `<sprache>` | Sprachkennung (case-insensitiv), z. B. `english`. Ordner `tl/<sprache>/` enthält zusätzlich Asset-Overrides. |
| `<id>` | `<label>_<zeilennummer>` oder kanonische Zeilen-ID; `strings` für String-Paare (Menüs, UI). |
| `old` / `new` | Originaltext und Übersetzung. Leere Übersetzungen werden übersprungen (Fallback auf Original). |

Empfohlener Ablageort: `tl/<sprache>/translations.vjava`. Der aktive Sprachwechsel erfolgt zur Laufzeit ohne Neustart (`LocalizationManager.setLanguage`).

```vjava
define lena = Character("Lena")

label start:
    lena "Hallo, Welt!"
    menu:
        "Spielen" -> jump ende
    return

label ende:
    return

translate english start_4:
    # lena "Hallo, Welt!"
    lena "Hello, world!"

translate english strings:
    old "Spielen"
    new "Play"
```

### 2.9 `label` – Skriptabschnitt

**Syntax:** `label <name>[(<param>, …)]:` + eingerückter Statement-Block.

| Argument | Beschreibung |
| :--- | :--- |
| `<name>` | Eindeutiger Bezeichner. Der **Einstiegs-Label** wird in `project.vjava.json` (`entryLabel`, Standard `start`) festgelegt. |
| `(<param>, …)` | Optionale Parameter für `call <label>(args)`. |

```vjava
label start:
    "Das ist der Anfang."
    jump kapitel_2

label kapitel_2:
    "Kapitel 2."
    return
```

### 2.10 `@->{ … }<<` – Java-Block (Top-Level)

Siehe [Abschnitt 6](#6-eingebettetes-java----). Top-Level-Blöcke laufen **einmal beim Start** in Deklarationsreihenfolge und eignen sich für Klassen, Records und Hilfsmethoden.

### 2.11 `config` – Projektkonfiguration

**Syntax:** `config:` mit eingerückten Schlüssel-Wert-Zuweisungen (`schluessel = wert`). Erlaubt projektweite Einstellungen (z. B. Fenster- oder Textvorgaben) direkt im Skript.

```vjava
config:
    title = "Mein Winter-Abenteuer"
    rollback_enabled = true
```

### 2.12 `include` – Skript-Einbindung

**Syntax:** `include "<relativer_pfad.vjava>"`

Deklariert eine explizite Modul-Abhängigkeit auf eine weitere `.vjava`-Datei im Projekt. `ProjectScanner` lädt alle `.vjava`-Dateien des Projektordners und prüft `include`-Ziele über `VJavaLinter`.

---

## 3. Label-Anweisungen

Innerhalb eines `label`-Blocks (und verschachtelt in `if`/`menu`) sind folgende Anweisungen gültig. Jede steht auf einer eigenen Zeile.

### 3.1 Dialog

**Syntax**

```text
"<text>" [with <transition>]
<sprecher> "<text>" [with <transition>]
```

| Argument | Beschreibung |
| :--- | :--- |
| `<sprecher>` | ID eines mit `define … Character(…)` angelegten Sprechers. Unbekannte Sprecher meldet der Linter (`UNDEFINED_SPEAKER`). |
| `"<text>"` | Text; unterstützt `[variable]`-Interpolation und [Inline-Tags](#8-inline-text-tags). |

Der Ablauf pausiert, bis der Spieler weiterklickt (`WAITING_DIALOGUE`). Jede Zeile erhält eine stabile Zeilen-ID (für Gelesen-Markierung, Skip, Übersetzung).

```vjava
define lena = Character("Lena", color="#58a6ff")
default punkte = 7

label start:
    "Ein Erzähler spricht."
    lena "Du hast [punkte] Punkte – {b}super{/b}!"
    return
```

### 3.2 `nvl`

**Syntax:** `nvl show` | `nvl hide` | `nvl clear`

Aktiviert/deaktiviert den kumulativen Vollbild-Textmodus bzw. leert den Textpuffer.

```vjava
define lena = Character("Lena")

label start:
    nvl show
    lena "Erste Zeile bleibt stehen."
    lena "Zweite Zeile erscheint darunter."
    nvl clear
    nvl hide
    return
```

### 3.3 `window`

**Syntax:** `window show` | `window hide` | `window auto`

Steuert das Dialogfenster (`show` = immer sichtbar, `hide` = ausgeblendet, `auto` = nur bei Text).

```vjava
label start:
    window hide
    pause 1.0
    window auto
    "Das Fenster ist wieder da."
    return
```

### 3.4 `scene`

**Syntax:** `scene <bgTag> [with <transition>]`

Entfernt alle Sprites der Master-Ebene, setzt den Hintergrund (1920×1080) und löst optional eine Transition aus.

| Argument | Beschreibung |
| :--- | :--- |
| `<bgTag>` | Bild-Tag (siehe `image`) oder direkter Pfad. |
| `with <transition>` | Optionaler [Übergang](#7-transitions). |

```vjava
image bg_hof = "assets/images/bg/hof.png"

label start:
    scene bg_hof with dissolve(0.8)
    "Der Hof."
    return
```

### 3.5 `show`

**Syntax:** `show <tag> [attribut …] [at <position>] [with <transition>] [:]`

`at` und `with` dürfen in beliebiger Reihenfolge stehen. Endet die Zeile mit `:`, folgt ein eingerückter ATL-Block (siehe [2.6](#26-transform--atl-animationen)), der direkt angewendet wird.

| Argument | Beschreibung |
| :--- | :--- |
| `<tag>` | Bild-/Layeredimage-Tag. |
| `attribut …` | Attribute (z. B. `happy`). |
| `at <position>` | Eingebaute Position oder `transform`-Name: `left` (xalign 0.15), `center` (0.5, Standard bei neuem Sprite), `truecenter` (0.5/0.5), `right` (0.85), `offscreenleft`, `offscreenright`, `reset`. |
| `with <transition>` | Optionaler [Übergang](#7-transitions). |

Ist der Tag schon sichtbar und wird kein `at` angegeben, bleibt die Position erhalten.

```vjava
define lena = Character("Lena")
image lena happy = "assets/images/char/lena_happy.png"

transform schweben:
    ypos 500
    easeinout 1.0 ypos 480
    easeinout 1.0 ypos 500
    repeat

label start:
    show lena happy at left with dissolve(0.3)
    lena "Ich stehe links."
    show lena happy at schweben
    lena "Jetzt schwebe ich."
    return
```

### 3.6 `hide`

**Syntax:** `hide <tag> [with <transition>]`

```vjava
define lena = Character("Lena")
image lena happy = "assets/images/char/lena_happy.png"

label start:
    show lena happy
    lena "Tschüss!"
    hide lena with dissolve(0.3)
    return
```

### 3.7 `with`

**Syntax:** `with <transition>`

Löst einen Übergang für die zuvor ausgeführten Bühnenänderungen aus. Zusätzlich gibt es die Screen-Shake-Presets `vpunch` und `hpunch`.

```vjava
label start:
    "Achtung!"
    with vpunch
    "Es hat gerumpelt."
    return
```

### 3.8 `pause`

**Syntax:** `pause` | `pause hard` | `pause <sekunden>`

Ohne Argument/`hard`: wartet auf Klick. Mit Zahl: zeitgesteuerte Pause (per Klick überspringbar).

| Argument | Typ | Beschreibung |
| :--- | :--- | :--- |
| `<sekunden>` | Dezimalzahl | Dauer in Sekunden. |

```vjava
label start:
    "Warte kurz …"
    pause 1.5
    "Weiter geht's."
    return
```

### 3.9 `play`

**Syntax:** `play <kanal> "<asset>" [loop | noloop] [fadein <sek>] [volume <wert>] [loop_start <sek>]`

| Argument | Typ | Standard | Beschreibung |
| :--- | :--- | :--- | :--- |
| `<kanal>` | Bezeichner | – | `music`, `sound`, `voice`, `ambient` oder eigener Name (wird bei Bedarf angelegt). |
| `"<asset>"` | String | – | Pfad (`.ogg`/`.wav`). `tl/<sprache>/…`-Overrides werden berücksichtigt. |
| `loop` / `noloop` | Flag | `loop` für `music`/`ambient`, sonst `noloop` | Wiederholung. |
| `fadein <sek>` | Zahl | 0 | Einblenddauer. |
| `volume <wert>` | Zahl | 1.0 | Kanal-Lautstärke (0.0–1.0). |
| `loop_start <sek>` | Zahl | 0 | Einsprungzeitpunkt in Sekunden beim nahtlosen Musik-Loop nach dem Intro. |

Unbekannte Optionen erzeugen einen Parserfehler. Auf `music` wird der Titel automatisch im Musikraum freigeschaltet.

```vjava
label start:
    play music "assets/audio/theme.ogg" fadein 1.0 volume 0.8
    "Musik läuft."
    play sound "assets/audio/klick.wav" noloop
    return
```

### 3.10 `queue`

**Syntax**

```text
queue <kanal> "<a>", "<b>" …
queue <kanal> ["<a>", "<b>"]
queue <kanal>:
    "<a>"
    "<b>"
```

Reiht Titel in die Warteschlange des Kanals ein; der nächste startet automatisch nach dem aktuellen. Mindestens ein Pfad ist Pflicht.

```vjava
label start:
    play music "assets/audio/theme.ogg"
    queue music:
        "assets/audio/theme2.ogg"
        "assets/audio/theme3.ogg"
    return
```

### 3.11 `stop`

**Syntax:** `stop <kanal> [fadeout <sek>]`

| Argument | Beschreibung |
| :--- | :--- |
| `<kanal>` | Kanalname oder `all` (alle Kanäle). |
| `fadeout <sek>` | Lineares Ausblenden (Standard 0 = sofort). Leert die Queue. |

```vjava
label start:
    play music "assets/audio/theme.ogg"
    "Gleich ist Ruhe."
    stop music fadeout 2.0
    return
```

### 3.12 `voice`

**Syntax:** `voice "<asset>"`

Setzt die Sprachausgabe für die **nachfolgende** Dialogzeile; steuert zugleich den Lip-Sync.

```vjava
define lena = Character("Lena")

label start:
    voice "assets/audio/hallo.ogg"
    lena "Hallo!"
    return
```

### 3.13 `menu`

**Syntax**

```text
menu ["<frage>"]:
    [timeout <sek> -> [jump] <label>]
    "<text>" [if <bed>] [disabled | disabled if <bed>] -> [jump] <label>
    "<text>" [qualifier]:
        <statements>
```

| Element | Beschreibung |
| :--- | :--- |
| `"<frage>"` | Titel – direkt nach `menu` oder als erste eigenständige String-Zeile. |
| `timeout <sek> -> <label>` | Springt nach Ablauf zum Label (ohne Timeout-Ziel: erste verfügbare Option). Alternativ `timeout <sek>:` + eingerücktes `jump <label>`. |
| `"<text>" -> <label>` | Option mit Direktsprung (`jump` optional). |
| `"<text>":` + Block | Option mit beliebigen Anweisungen. |
| `if <bed>` | Option wird bei `false` **versteckt**. |
| `disabled` | Dauerhaft ausgegraut. |
| `disabled if <bed>` | Sichtbar, aber bei **wahrer** Bedingung nicht wählbar. (Alternativ `if <bed> disabled`.) |

```vjava
define lena = Character("Lena")
default kennt_lena = true
default hat_schluessel = false

label start:
    menu "Was möchtest du tun?":
        timeout 8.0 -> jump zeit_abgelaufen

        "Den Flur untersuchen" -> jump flur
        "Mit Lena sprechen" if kennt_lena -> gespraech
        "Geheime Tür öffnen" disabled if not hat_schluessel:
            "Die Tür öffnet sich knarrend."
            jump flur

label flur:
    "Ein langer Flur."
    return

label gespraech:
    lena "Hallo!"
    return

label zeit_abgelaufen:
    "Du hast zu lange gezögert."
    return
```

### 3.14 `if` / `elif` / `else`

**Syntax**

```text
if <bedingung>:
    <statements>
elif <bedingung>:
    <statements>
else:
    <statements>
```

Bedingungen siehe [Ausdrücke](#5-ausdrücke--operatoren). `elif`/`else` ohne vorangehendes `if` ist ein Fehler.

```vjava
default punkte = 12

label start:
    if punkte >= 20:
        "Gold!"
    elif punkte >= 10:
        "Silber!"
    else:
        "Bronze."
    return
```

### 3.15 `$` – Variablenzuweisung

**Syntax:** `$ <variable> <op> <ausdruck>` mit `<op>` ∈ `=`, `+=`, `-=`, `*=`, `/=`

```vjava
default punkte = 0

label start:
    $ punkte = 5
    $ punkte += 10
    $ punkte *= 2
    "Jetzt: [punkte]"
    return
```

### 3.16 `jump`

**Syntax:** `jump <label>` | `jump expression <ausdruck>`

Springt ohne Rückkehr (kein Call-Frame). Mit `expression` wird der Labelname berechnet.

```vjava
default ziel = "kapitel_2"

label start:
    jump expression ziel

label kapitel_2:
    "Dynamisch gesprungen."
    return
```

### 3.17 `call`

**Syntax:** `call <label>` | `call <label>(<arg1>, <arg2>, …)` | `call expression <ausdruck>`

Ruft ein Label als Unterprogramm auf; `return` kehrt zurück. Argumente liegen unter `state.get("_call_args")`, der Rückgabewert unter `state.get("_return")`.

```vjava
label start:
    call begruessung("Lena")
    "Zurück im Hauptablauf."
    return

label begruessung(name):
    "Hallo und willkommen!"
    return
```

### 3.18 `return`

**Syntax:** `return` | `return <ausdruck>`

Kehrt zum Aufrufer zurück. Ist der Call-Stack leer, endet das Skript.

```vjava
label start:
    call berechne
    "Ergebnis: [_return]"
    return

label berechne:
    return 6 * 7
```

### 3.19 `input`

**Syntax:** `input <variable> "<prompt>" [default "<text>"] [allow "<zeichen>"] [length <n>]`

| Argument | Typ | Beschreibung |
| :--- | :--- | :--- |
| `<variable>` | Bezeichner | Zielvariable im `state`. |
| `"<prompt>"` | String | Eingabeaufforderung. |
| `default` | String | Vorbelegung. |
| `allow` | String | Whitelist erlaubter Zeichen. |
| `length` | Ganzzahl | Maximale Zeichenzahl. |

Unbekannte Optionen erzeugen einen Parserfehler.

```vjava
default spielername = "Alex"

label start:
    input spielername "Wie heißt du?" default "Alex" length 12
    "Willkommen, [spielername]!"
    return
```

### 3.20 `block_rollback`

**Syntax:** `block_rollback` (ohne Argumente)

Setzt eine Rollback-Barriere: Der Spieler kann nicht mehr hinter diesen Punkt zurückspulen (nützlich nach Entscheidungen oder Zufall).

```vjava
label start:
    "Du wirfst den Würfel …"
    block_rollback
    "Es ist eine Sechs."
    return
```

### 3.21 `pass`

**Syntax:** `pass` – leere Anweisung, Platzhalter für sonst leere Blöcke.

```vjava
default offen = false

label start:
    if offen:
        pass
    else:
        "Geschlossen."
    return
```

### 3.22 `:: … @->{ … }<<` (Java-Anweisung)

Siehe [Abschnitt 6](#6-eingebettetes-java----).

### 3.23 `match` – Pattern Matching

**Syntax:** `match <ausdruck>:` mit eingerückten `case <muster>:`-Zweigen und optionalem `default:`-Zweig.

```vjava
label bewertung:
    match rank:
        case 1:
            "Gold"
        case 2:
            "Silber"
        default:
            "Teilnahme"
    return
```

### 3.24 `repeat` & `while` – Schleifen

**Syntax:** `repeat <anzahl>:` bzw. `while <bedingung>:` mit eingerücktem Rumpf. `while` ist gegen Endlosschleifen mit einem Iterationslimit abgesichert (`ScriptExecutionException`).

```vjava
label runde:
    repeat 3:
        $ punkte += 10
    while $hp > 0:
        $ hp -= 50
    return
```

### 3.25 `random` – Gewichtete Zufallszweige

**Syntax:** `random:` mit eingerückten `weight <gewicht>:`-Blöcken.

```vjava
label schatz:
    random:
        weight 70:
            $ fund = "Münze"
        weight 30:
            $ fund = "Rubin"
    return
```

### 3.26 `set` & `local` – Block- und Lokalvariablen

**Syntax:** `set:` weist mehrere Variablen in einem eingerückten Block (`name = ausdruck`) kompakt zu. `local $<name> = <ausdruck>` deklariert eine auf den aktuellen `call`-Stackframe begrenzte Variable.

```vjava
label helfer:
    set:
        gold = 100
        rang = "A"
    local $tmp = 42
    "Temporär: [tmp], Gold: [gold]"
    return
```

### 3.27 `parallel`, `after`, `on_event` & `spawn_cutscene` – Nebenläufigkeit & Ereignisse

| Befehl | Syntax | Beschreibung |
| :--- | :--- | :--- |
| `parallel:` | `parallel:` + Block | Führt Kind-Anweisungen gleichzeitig aus (z. B. parallele `move`/`scale`-Tweens). |
| `after` | `after <sek> jump\|call <label>` | Startet einen Timer und springt bzw. ruft `<label>` nach Ablauf auf. |
| `on_event` | `on_event "<name>" call\|jump <label>` | Bindet einen Listener am `EngineEventBus` an eine `.vjava`-Subroutine. |
| `spawn_cutscene` | `spawn_cutscene <label>` | Führt `<label>` nicht-blockierend als Hintergrund-Coroutine aus. |

```vjava
label cutscene_start:
    on_event "tor_offen" call reaktion
    after 2.5 jump weiter
    parallel:
        move tor to 400 200 duration 0.8
        scale tor to 1.2 duration 0.8
    spawn_cutscene wolken_zug
    return
```

---

## 4. Bewegung, Steuerung, Physik & 3D

Diese Anweisungen steuern **Sprites** auf der Bühne (Koordinaten im logischen Raster **1920×1080**, Ursprung links oben). Sie sind **nicht blockierend**: Die Anweisung startet die Bewegung, das Skript läuft sofort weiter. Der zugehörige Sprite muss vorher mit `show` angezeigt worden sein.

### 4.1 `move`

**Syntax:** `move <tag> [to] <x> <y> [duration <sek>] [ease <kurve>]`

| Argument | Typ | Standard | Beschreibung |
| :--- | :--- | :--- | :--- |
| `<tag>` | Bezeichner | – | Sprite-Tag. |
| `to` | Schlüsselwort | – | Optional, nur lesbarkeitshalber. |
| `<x>`, `<y>` | Zahl | – | Zielkoordinaten. |
| `duration <sek>` | Zahl | `0` (sofort) | Dauer des Tweens. |
| `ease` / `easing <kurve>` | `linear`, `ease_in`, `ease_out`, `ease_in_out` | `linear` | Beschleunigungskurve. |

```vjava
image player = "assets/images/char/player.png"

label start:
    show player
    move player to 200 780
    move player to 1480 780 duration 1.5 ease ease_in_out
    "Angekommen."
    return
```

### 4.2 `move_by`

**Syntax:** `move_by <tag> <dx> <dy> [duration <sek>] [ease <kurve>]`

Wie `move`, aber **relativ** zur aktuellen Position (kein `to`).

```vjava
image star = "assets/images/char/star.png"

label start:
    show star
    move_by star 0 -20 duration 0.4 ease ease_out
    "Der Stern hüpft."
    return
```

### 4.3 `wasd_init`

**Syntax:** `wasd_init <tag> [speed <px/s>] [bounds <x1> <y1> <x2> <y2>] [diagonal_norm <true|false>]`

Bindet die Tastatursteuerung (**W A S D** bzw. Pfeiltasten) an einen Sprite. Springen (bei aktiver Physik) mit **Leertaste/W**. Die Steuerung ist deaktiviert, solange ein modaler Screen offen ist.

| Argument | Typ | Standard | Beschreibung |
| :--- | :--- | :--- | :--- |
| `<tag>` | Bezeichner | – | Sprite-Tag. |
| `speed` | Zahl (px/s) | `260` | Geschwindigkeit. |
| `bounds x1 y1 x2 y2` | 4 Zahlen | unbegrenzt | Erlaubter Bewegungsbereich (min x, min y, max x, max y). |
| `diagonal_norm` | `true`/`false` | `true` | Diagonale Bewegung auf konstante Geschwindigkeit normalisieren. |

```vjava
image player = "assets/images/char/player.png"

label start:
    show player
    wasd_init player speed 280 bounds 80 120 1840 940 diagonal_norm true
    "Steuere den Spieler mit WASD."
    return
```

### 4.4 `wasd_stop`

**Syntax:** `wasd_stop [<tag>]` – ohne Tag werden **alle** Steuerungen beendet.

```vjava
image player = "assets/images/char/player.png"

label start:
    show player
    wasd_init player
    "Bewege dich."
    wasd_stop player
    "Steuerung beendet."
    return
```

### 4.5 `physics_init`

**Syntax:** `physics_init <tag> [gravity <px/s²>] [jump_force <px/s>] [ground_y <y>]`

Aktiviert vertikale Schwerkraft und Sprungphysik.

| Argument | Typ | Standard | Beschreibung |
| :--- | :--- | :--- | :--- |
| `gravity` | Zahl | `980` | Fallbeschleunigung (px/s²). |
| `jump_force` | Zahl | `460` | Sprunggeschwindigkeit (px/s). |
| `ground_y` | Zahl | `920` | Y-Koordinate des Bodens. |

```vjava
image player = "assets/images/char/player.png"

label start:
    show player
    wasd_init player
    physics_init player gravity 900 jump_force 440 ground_y 780
    "Springe mit der Leertaste."
    return
```

### 4.6 `physics_stop`

**Syntax:** `physics_stop [<tag>]` – ohne Tag werden alle Physikkörper deaktiviert.

```vjava
image player = "assets/images/char/player.png"

label start:
    show player
    physics_init player
    "Die Schwerkraft wirkt."
    physics_stop player
    "Schwerelos."
    return
```

### 4.7 `on_collide`

**Syntax:** `on_collide <tagA> <tagB> jump <label>`

Registriert einen AABB-Kollisionsauslöser: Beim ersten Überlappen von `tagA` und `tagB` springt das Skript zum Label. Genau vier Wörter sind erlaubt, sonst Parserfehler.

```vjava
define lena = Character("Lena")
image player = "assets/images/char/player.png"
image star = "assets/images/char/star.png"

label arena:
    show player
    show star
    move player to 200 780
    move star to 1480 780
    wasd_init player speed 280
    physics_init player ground_y 780
    on_collide player star jump sieg
    lena "Erreiche den Stern!"
    return

label sieg:
    wasd_stop player
    physics_stop player
    lena "Geschafft!"
    return
```

### 4.8 Erweiterte Tweens & Synchronisation

| Befehl | Syntax | Beschreibung |
| :--- | :--- | :--- |
| `move_path` | `move_path <tag> <x1> <y1> <x2> <y2> <x3> <y3> [duration <sek>] [ease <kurve>]` | Bewegt einen Sprite entlang einer kubischen Bézier-Kurve. |
| `rotate` | `rotate <tag> to <grad> [duration <sek>] [ease <kurve>]` | Rotiert über den kürzesten Winkelweg (`spring`, `ease_in_out` u. a.). |
| `scale` | `scale <tag> to <zx> <zy> [duration <sek>] [ease <kurve>]` | Skaliert einen Sprite (optional nicht-uniform in X/Y). |
| `wait_move` | `wait_move <tag>` | Wartet bis zum Abschluss der aktiven Bewegung von `<tag>`. |
| `on_tween_end` | `on_tween_end <tag> jump\|call <label>` | Löst bei Abschluss einer Tween-Animation ein Ziel-Label aus. |

### 4.9 Steuerung, Kollision, Plattformen & Physik-Zonen

| Befehl | Syntax | Beschreibung |
| :--- | :--- | :--- |
| `point_click_init` | `point_click_init <tag> [speed <px>]` | Aktiviert Klick-zum-Gehen-Steuerung per Mausziel. |
| `on_overlap` | `on_overlap <a> <b> enter\|stay\|exit <label>` | Überwacht Lebenszyklus-Phasen (`enter`, `stay`, `exit`) zweier Hitboxen. |
| `on_interact` | `on_interact <tag> <dist> jump\|call <label>` | Löst bei `E`/`Enter` in Reichweite `<dist>` eine Interaktion aus. |
| `collision_mask` | `collision_mask <tag> <layer> collides_with <l1> ...` | Filtert Kollisionspaare nach logischen Schichten. |
| `hitbox` | `hitbox <tag> circle <r> \| rect <w> <h>` | Überschreibt die Standard-Bounding-Box durch Kreis- oder Rechteck-Hitbox. |
| `trigger_zone` | `trigger_zone <id> rect <x> <y> <w> <h> call\|jump <label>` | Definiert eine unsichtbare Auslösezone. |
| `solid_platform` | `solid_platform <tag> [one_way]` | Markiert ein Sprite als begehbare oder einseitig durchlässige Plattform. |
| `moving_platform` | `moving_platform <tag> between <x1> <y1> <x2> <y2> [speed <px>]` | Bewegliche Plattform, die darauf stehende Spieler mitführt. |
| `collider` | `collider wall <id> <x> <y> <w> <h>` | Erzeugt ein statisches blockierendes Rechteck-Hindernis. |
| `climb_zone` | `climb_zone <id> rect <x> <y> <w> <h>` | Schaltet in Leiter-/Kletterzonen Schwerkraft zugunsten von `W`/`S` aus. |
| `surface_friction` | `surface_friction <id> rect <x> <y> <w> <h> factor <f>` | Skaliert Reibung/Trägheit (z. B. Eisfläche `0.4`). |
| `knockback` | `knockback <tag> vx <x> vy <y> [invuln <sek>]` | Verleiht Rückstoß-Impuls mit abklingender Unverwundbarkeitsphase. |
| `impulse` | `impulse <tag> vx <x> vy <y>` | Setzt eine sofortige Geschwindigkeits-Impulskraft. |
| `attract` | `attract <tag> target <ziel> radius <r> [speed <px>]` | Zieht Collectibles magnetisch ab Radius `<r>` an `<ziel>` heran. |

### 4.10 Kamera, Effekte, KI & Dynamische Sprites

| Befehl | Syntax | Beschreibung |
| :--- | :--- | :--- |
| `camera` | `camera follow <tag> [smooth <f>]` / `camera clamp <x1> <y1> <x2> <y2>` | Verfolgt ein Sprite weich und begrenzt den sichtbaren Kamerabereich. |
| `shake` | `shake <ebene> amp <px> dur <sek>` | Schüttelt eine Szene-Ebene rein visuell ohne Hitbox-Versatz. |
| `anim_state` | `anim_state <tag> idle <a> walk <b> jump <c>` | Koppelt Bewegungszustand automatisch an Sprite-Attribute. |
| `patrol` | `patrol <tag> between <x1> <y1> <x2> <y2> [speed <px>]` | Lässt NPCs/Wachen kontinuierlich zwischen zwei Punkten patrouillieren. |
| `follow` | `follow <tag> target <ziel> [speed <px>] [stop_radius <r>]` | Lässt Begleiter mit Hysterese (und 2D-A* in `GridPathfinder2D`) folgen. |
| `spawn` / `despawn` | `spawn <tag> "<asset>" [at <x> <y>]` / `despawn <tag>` | Erzeugt oder entfernt dynamische Instanzen ohne `define`. |
| `attach` | `attach <kind> to <eltern> [offset <x> <y>]` | Verknüpft Kind-Sprites relativ zu einem Eltern-Sprite. |
| `ysort` | `ysort <true\|false>` | Sortiert Master-Sprites automatisch anhand ihrer Y-Fußkoordinate. |
| `time_scale` | `time_scale <faktor>` | Zeitlupe bzw. Zeitraffer auf Bewegung, Physik und Timer. |

### 4.11 3D-Bühne & Hybrid-Szenen

Aktiviert bzw. steuert die `RenderPipeline3D` innerhalb hybrider VN-/Erkundungsszenen (`Sky2D -> World3D -> Billboards -> Foreground2D -> UI`):

| Befehl | Syntax | Beschreibung |
| :--- | :--- | :--- |
| `scene3d` | `scene3d <prefab>` | Lädt eine definierte 3D-Bühnenkonfiguration (`Scene3DWorld`). |
| `spawn3d` | `spawn3d [<id>] "<mesh.obj\|glb>" at <x> <y> <z>` | Platziert ein `.obj`- oder `.glb`-3D-Modell im Raum. |
| `camera3d` | `camera3d move\|look_at <x> <y> <z> [duration <sek>]` | Interpoliert Kameraposition oder LookAt-Fokus im 3D-Raum. |
| `wasd3d_init` | `wasd3d_init [speed <wert>] [fov <grad>]` | Aktiviert First-Person-/Orbit-Steuerung mit `±89°`-Gimbal-Schutz. |
| `fog3d` | `fog3d "<hex>" [density <d>] [falloff <f>]` | Konfiguriert exponentiellen Distanz- und Höhen-Nebel. |
| `material3d` | `material3d <id> tint "<hex>" [roughness <r>] [metallic <m>]` | Überschreibt PBR-/Toon-Materialeigenschaften eines `Node3D`. |

```vjava
label schloss_3d:
    scene3d throne_room
    spawn3d desk "assets/models/desk.obj" at 0 0 -4
    material3d desk tint "#ffd700" roughness 0.25 metallic 0.85
    fog3d "#1a2438" density 0.04
    camera3d move 0 2 6 duration 1.0
    wasd3d_init speed 5.0 fov 75
    return
```

---

## 5. Ausdrücke & Operatoren

Ausdrücke werden von `ConditionEvaluator` ausgewertet (in `if`/`elif`, Menü-`if`, `$`, `jump expression`, `call`-Argumenten, `return` und `[…]`-Interpolationen).

### 5.1 Literale

| Typ | Beispiele |
| :--- | :--- |
| Ganzzahl | `42`, `0xFF`, `1_000` |
| Gleitkomma | `3.14`, `.5`, `1.25f`, `1e-3` |
| String | `"Text"`, `'Text'` (Escapes `\n \t \r \\ \" \'`) |
| Boolean | `true`, `false` |
| Null | `null` (in `Character(…)` auch `None`) |

### 5.2 Operatoren (hohe → niedrige Präzedenz)

| Stufe | Operator(en) | Beschreibung |
| :--- | :--- | :--- |
| 1 | `.feld`, `.methode(args)`, `[index]` | Zugriff auf Felder/Getter/Methoden, Index auf `Map`, `List`, String, Array |
| 2 | `!`, `not`, `-`, `+` (unär) | Negation, Vorzeichen |
| 3 | `*`, `/`, `%` | Multiplikation, Division, Rest |
| 4 | `+`, `-` | Addition/Subtraktion; `+` verkettet Strings |
| 5 | `<`, `<=`, `>`, `>=`, `in <a..b>`, `between <a> and <b>` | Vergleich sowie geschlossene Bereichsprüfung (`x in 1..10`) |
| 6 | `==`, `!=` | Gleichheit (mit numerischer Typ-Angleichung) |
| 7 | `&&`, `and` | Logisches UND (Short-Circuit) |
| 8 | `\|\|`, `or` | Logisches ODER (Short-Circuit) |
| 9 | `a ? b : c` | Ternärer Operator |

Zuweisungsoperatoren (nur in `$`): `=`, `+=`, `-=`, `*=`, `/=`.

### 5.3 Bezeichner & Wahrheitswerte

* Variablennamen lesen zuerst aus `state`, dann aus `persistent`. Vordefiniert: `state`, `persistent`, `Math`.
* **Wahrheitswerte:** `null` → falsch · `Boolean` → direkt · `Number` → wahr, wenn ≠ 0 und nicht NaN · `String` → wahr, wenn nicht leer · jedes andere Objekt → wahr.

```vjava
default hp = 40
default items = 3
default name = "Lena"

label start:
    $ bonus = hp > 30 ? 10 : 0
    $ text = name + " hat " + items + " Items"
    if hp > 0 and (items >= 3 or bonus > 0) and not (name == ""):
        "[text]; Bonus [bonus]; Wurzel von 16: [Math.sqrt(16)]"
    return
```

---

## 6. Eingebettetes Java (`@->{ … }<<`)

**Syntax**

```text
@->{ <java-code> }<<                                  # einzeilig
:: <Beschreibung> @->{ <java-code> }<<                # einzeilig mit Beschreibung
:: Kode für VN: <Beschreibung> @->{                   # mehrzeilig
    <java-code>
}<<
```

Das Präfix `:: ` (optional mit `Kode für VN:`/`Kode fuer VN:`) ist ein reiner Beschreibungstext. Blöcke dürfen auf Top-Level, in Labels, `if`- und `menu`-Blöcken stehen. Nach `}<<` sind auf der Zeile nur Leerraum oder Kommentare erlaubt. Klammern, Strings, Textblöcke (`"""`) und Kommentare im Java-Code beenden den Block nicht vorzeitig.

**Bindings (Variablen im Java-Block)**

| Binding | Typ | Wichtige Methoden |
| :--- | :--- | :--- |
| `state` | `StateStore` | `set`, `get`, `getOrDefault`, `getString`, `getInt`, `getDouble`, `getBoolean`, `has`, `remove`, `keys`, `blockRollback`, `clear` |
| `persistent` | `PersistentAccess` | `set`, `get`, `has`, `markLineRead`, `isLineRead`, `unlockCg`, `unlockTrack`, `unlockEnding`, `unlockAchievement` |
| `engine` | `EngineAPI` | `logInfo`, `logWarn`, `logError`, `logDebug`, `getConfig`, `getProjectDescriptor`, `getClock`, `getInput`, `requestQuit`, `requestLiveReload` |
| `scene` | `SceneController` | `setScene`, `showSprite`, `hideSprite`, `registerImageDefinition`, `registerTransform`, `clearMasterSprites`, `getActiveSprite`, `getSceneGraph` |

**Kompilierung:** In-Memory via `javax.tools` (benötigt ein **JDK**, kein reines JRE). Deklarierte Typen und `static`-Methoden werden in `de.swfdtf.vjava.gen.VJavaUserTypes` gesammelt und sind in **allen** späteren Blöcken und in Ausdrücken (`[item.name]`) nutzbar. Automatisch importiert: `de.swfdtf.state.*`, `de.swfdtf.core.*`, `de.swfdtf.graphics.*`, `de.swfdtf.math.*`, `java.util.*`. Kompilierfehler enthalten die **.vjava-Zeilennummer**.

```vjava
define lena = Character("Lena")

:: Kode für VN: Eigene Klasse und Hilfsmethode @->{
    public static class InventarItem {
        public final String name;
        public final int wert;

        public InventarItem(String name, int wert) {
            this.name = name;
            this.wert = wert;
        }
    }

    public static int bonus(int basis) {
        return basis + 25;
    }
}<<

label start:
    :: Item setzen @->{
        state.set("aktives_item", new InventarItem("Sternenschlüssel", 42));
        state.set("gesamt", bonus(42));
        engine.logInfo("Item gesetzt");
        persistent.unlockCg("cg_intro");
    }<<
    lena "Du hast [aktives_item.name] (Wert [aktives_item.wert], gesamt [gesamt])."
    if aktives_item.wert >= 40:
        lena "Wertvoll!"
    return
```

---

## 7. Transitions

Verwendbar in `with …` (an `scene`, `show`, `hide`, Dialog oder als eigene Zeile). Standarddauer ohne Angabe: **0,5 s**, Standardfarbe der Fades: `#000000`.

| Ausdruck | Beschreibung |
| :--- | :--- |
| `none` | Kein Übergang. |
| `dissolve(t)` | Überblendung. |
| `fade(t)` / `fade(t, #RRGGBB)` / `fade(aus, halten, ein, color=#RRGGBB)` | Ausblenden zu Farbe und wieder ein. |
| `pixellate(t)` (auch `pixelate`) | Verpixelung. |
| `move(t)`, `ease(t)`, `easein(t)`, `easeout(t)`, `easeinout(t)` | Bewegungs-/Easing-Übergänge. |
| `wipeleft(t)`, `wiperight(t)`, `wipeup(t)`, `wipedown(t)` | Wischblenden. |
| `slideleft(t)`, `slideright(t)`, `pushleft(t)`, `pushright(t)` | Schieben. |
| `irisin(t)`, `irisout(t)` | Irisblende. |
| `blinds(t[, n])`, `squares(t[, n])` | Jalousien/Quadrate (n Standard 16). |
| `imagedissolve("maske.png", t[, ramplen=8][, reverse=true])` | Überblendung über Graustufenmaske. |
| `multiple(t1, t2, …)` | Hintereinander ausgeführte Transitions. |
| `vpunch`, `hpunch` | Bildschirmwackeln vertikal/horizontal. |

```vjava
define lena = Character("Lena")
image bg_hof = "assets/images/bg/hof.png"
image bg_nacht = "assets/images/bg/nacht.png"
image lena happy = "assets/images/char/lena_happy.png"

label start:
    scene bg_hof with fade(1.0, #000000)
    show lena happy at right with dissolve(0.4)
    lena "Weiter geht es …" with vpunch
    scene bg_nacht with wipeleft(0.7)
    hide lena with pixellate(0.5)
    return
```

---

## 8. Inline-Text-Tags

Gelten in Dialogen, Menütexten, Prompts und Screen-Texten.

### 8.1 Interpolation & Escapes

| Syntax | Wirkung |
| :--- | :--- |
| `[variable]` | Wert aus `state`/`persistent`; auch Ausdrücke mit `.`, `[`, `(`. Unbekannte Namen bleiben wörtlich stehen. |
| `[variable:<format>]` | Format-Spezifizierer wie `[gold:03d]`, `[hp:.1f]` oder `[quote:.0%]`. |
| `[[` | Wörtliches `[`. |
| `{{` | Wörtliches `{`. |

### 8.2 Formatierung

| Tag | Schließen | Wirkung |
| :--- | :--- | :--- |
| `{b}` | `{/b}` | Fett |
| `{i}` | `{/i}` | Kursiv |
| `{u}` | `{/u}` | Unterstrichen |
| `{s}` | `{/s}` | Durchgestrichen |
| `{sc}` | `{/sc}` | Kapitälchen |
| `{plain}` | – | Alle Formatierungen zurücksetzen |
| `{color=#RRGGBB}` | `{/color}` | Farbe (`#RGB`, `#RGBA`, `#RRGGBB`, `#RRGGBBAA`) |
| `{alpha=0.5}` | `{/alpha}` | Deckkraft 0.0–1.0 |
| `{size=36}` / `{size=+8}` / `{size=-4}` | `{/size}` | Absolute oder relative Schriftgröße |
| `{font=pfad}` | `{/font}` | Schriftart |
| `{k=2.5}` | `{/k}` | Zeichenabstand |

### 8.3 Ablaufsteuerung

| Tag | Wirkung |
| :--- | :--- |
| `{w}` / `{w=1.5}` | Warten auf Klick / Sekunden |
| `{p}` / `{p=0.5}` | Absatz + Warten |
| `{nw}` | Kein Warten am Zeilenende (automatisch weiter) |
| `{fast}` | Text bis hier sofort anzeigen |
| `{cps=60}…{/cps}` | Tippgeschwindigkeit in Zeichen/s (0 = sofort) |

### 8.4 Spezial & Effekte

| Tag | Wirkung |
| :--- | :--- |
| `{rb}漢字{/rb}{rt}かんじ{/rt}` | Furigana (Lesehilfe über dem Text) |
| `{image=pfad}` | Inline-Bild |
| `{a=ziel}…{/a}` | Hyperlink |
| `{wave}…{/wave}` | Wellenanimation |
| `{jitter}…{/jitter}` / `{shake}…{/shake}` | Zittern |
| `{gradient=#c1,#c2}…{/gradient}` | Farbverlauf |

```vjava
define lena = Character("Lena", color="#58a6ff")
default name = "Alex"

label start:
    lena "Hallo [name]! {b}Fett{/b}, {i}kursiv{/i}, {color=#ff5555}rot{/color}.{w=0.5} Weiter …"
    lena "{cps=15}Langsam …{/cps}{p}Neuer Absatz mit {wave}Welle{/wave} und {{Klammer}}."
    return
```

---

## 9. Screen-Sprache (UI)

**Kopf:** `screen <name>[(<param>, …)]:`

**Direktiven auf Screen-Ebene**

| Direktive | Beschreibung |
| :--- | :--- |
| `modal true\|false` | Blockiert Eingaben an darunterliegende Elemente. |
| `zorder <int>` | Zeichenreihenfolge (Standard `200`). |
| `key <TASTE> action "<aktion>"` | Tastenbindung (`K_ESCAPE`, `ESCAPE`, `RETURN`, `SPACE`, `A`–`Z`). |
| `timer <sek> [repeat true] action "<aktion>"` | Zeitgeber. |

**Container:** `vbox`, `hbox`, `grid [<spalten> <zeilen>]`, `fixed`, `frame`, `window`, `viewport`, `vpgrid`, `imagemap`, `draggroup`, `side`.

**Widgets:** `text`, `label`, `textbutton`, `button`, `imagebutton`, `bar`, `vbar`, `slider`, `input`, `hotspot`, `hotbar`, `drag`, `mousearea`, `custom <JavaKlasse>`, `add`, `null`, `use`.

**Kontrollfluss:** `if` / `elif` / `else`, `for`, `on`.

**Gemeinsame Eigenschaften:** `id`, `xsize`/`width`, `ysize`/`height`, `padding`/`xpadding`/`ypadding`, `background` (`#…`), `border_color`, `border_thickness`, `corner_radius`, `alpha`, `sensitive`/`enabled`, `xpos`, `ypos`, `xalign`, `yalign`, `spacing`.

**Wichtige Widget-Argumente**

| Widget | Argumente |
| :--- | :--- |
| `text "<s>"` | `size`, `color`, `drop_shadow`, `cps` (mit `[var]`-Interpolation) |
| `textbutton "<s>"` | `action`, `selected`, `size` |
| `imagebutton` | `auto "pfad_%s.png"` (→ `idle`/`hover`/`insensitive`) oder `idle`, `hover`, `disabled`/`insensitive`, `action` |
| `bar`, `vbar` | `value VariableValue("var", max)` |
| `slider` | `value VariableValue("var", max)`, `min`, `range`, `step` |
| `input` | `value=VariableInputValue("var")`, `default`, `length`, `allow`, `placeholder`, `action` |
| `hotspot` | `(x, y, w, h)`, `hovered_color`, `action` |
| `drag` | `drag_name`, `payload`, `draggable`, `droppable`/`drop_target`, `snappable` |
| `viewport` | `scrollbars`, `yinitial` |
| `vpgrid` | `cols`, `cell_width`/`xsize`, `cell_height`/`ysize`, `spacing` |

**Aktionen (`action "…"`)**

| Aktion | Wirkung |
| :--- | :--- |
| `jump:<label>` oder `Jump("<label>")` | Springt zu Label (schließt das Hauptmenü). |
| `show_screen:<name>` / `ShowMenu("<name>")` | Screen anzeigen (Aliase: `save_screen`, `load_screen`, `preferences_screen`). |
| `hide_screen:<name>` / `Hide("<name>")` | Screen ausblenden. |
| `Start()` / `start` | Spiel starten (Hauptmenü). |
| `Return()` | Oberstes modales Fenster schließen. |
| `QuickSave()` / `QuickLoad()` | Schnellspeichern/-laden. |
| `Quit()` / `quit` | Programm beenden. |

```vjava
default punkte = 25
default name = "Alex"

screen status_hud(titel):
    modal false
    zorder 250
    frame xpos 40 ypos 40:
        vbox spacing 8:
            text "[titel]" size 28
            text "Punkte: [punkte]" size 22
            bar value VariableValue("punkte", 100)
            slider value VariableValue("sfx_level", 1.0)
            if punkte > 20:
                text "Gut gemacht!" color "#3fb950"
            textbutton "Schließen" action "hide_screen:status_hud"
            textbutton "Zur Arena" action "Jump(\"arena\")"
    key K_ESCAPE action "Return()"

screen name_eingabe:
    modal true
    vbox spacing 12:
        text "Dein Name?"
        input value=VariableInputValue("name") length 12
        textbutton "OK" action "hide_screen:name_eingabe"

label start:
    "Screens definiert."
    return

label arena:
    "Arena."
    return
```

> **Hinweis:** Es gibt keine eigene Label-Anweisung zum Einblenden eines Screens. Screens werden über UI-Aktionen (`show_screen:<name>` / `ShowMenu("<name>")`) oder aus Java über `ScreenManager.showScreen(name)` angezeigt. Beachte außerdem, dass der Aktionsstring in `action "…"` Anführungszeichen mit `\"` maskieren muss.

---

## 10. Vollständiges Beispiel

Das folgende Projekt (`main.vjava`) deckt nahezu alle Konstrukte ab und besteht den `VJavaLinter` ohne Befunde, sofern die referenzierten Assets existieren.

```vjava
define lena = Character("Lena", color="#58a6ff")
define narrator = Character(None)
define max_punkte = 100
default punkte = 0
default name = "Spieler"

image bg_hof = "assets/images/bg/hof.png"
image lena happy = "assets/images/char/lena_happy.png"
image player = "assets/images/char/player.png"
image star = "assets/images/char/star.png"

transform schweben:
    ypos 500
    easeinout 1.0 ypos 480
    easeinout 1.0 ypos 500
    repeat

:: Hilfsklasse @->{
    public static int bonus(int basis) { return basis + 25; }
}<<

label start:
    scene bg_hof with dissolve(0.8)
    show lena happy at left with dissolve(0.3)
    play music "assets/audio/theme.ogg" fadein 1.0 volume 0.8
    voice "assets/audio/hallo.ogg"
    lena "Hallo, {b}Welt{/b}! Du hast [punkte] Punkte."
    "Erzähler ohne Sprecher."
    input name "Wie heißt du?" default "Alex" length 12
    $ punkte += 10
    if punkte >= 10 and name != "":
        lena "Gut, [name]."
    elif punkte > 0:
        lena "Naja."
    else:
        pass
    menu "Was tun?":
        timeout 5.0 -> jump ende
        "Spielen" -> jump arena
        "Reden" if punkte > 5 -> reden
        "Gesperrt" disabled if punkte > 100:
            lena "Nie."
    call unterprogramm(3)
    pause 1.5
    hide lena with dissolve(0.3)
    stop music fadeout 2.0
    jump ende

label unterprogramm(n):
    :: Rechnen @->{ state.set("ergebnis", bonus(3)); }<<
    return

label reden:
    block_rollback
    lena "Reden."
    return

label arena:
    show player
    show star
    move player to 200 780
    move star to 1480 780 duration 0.4 ease ease_out
    move_by star 0 -20 duration 0.4 ease ease_in_out
    wasd_init player speed 280 bounds 80 120 1840 940 diagonal_norm true
    physics_init player gravity 900 jump_force 440 ground_y 780
    on_collide player star jump sieg
    lena "Los!"
    return

label sieg:
    wasd_stop player
    physics_stop player
    nvl show
    "Gewonnen."
    nvl clear
    nvl hide
    window hide
    window auto
    jump ende

label ende:
    return
```

---

## 11. Typische Fehler

| Meldung / Symptom | Ursache | Lösung |
| :--- | :--- | :--- |
| `Unknown statement: '…'` | Unbekanntes Schlüsselwort oder Sprecher ohne `"…"`. | Schreibweise prüfen; Sprecher als `id "Text"`. |
| `Orphaned 'elif'/'else' without preceding 'if'` | `elif`/`else` ohne `if`. | Einrückung und Reihenfolge prüfen. |
| `Unknown option in 'play' statement` | Ungültige Option. | Erlaubt: `loop`, `noloop`, `fadein`, `volume`, `loop_start`. |
| `Expected 'on_collide <tagA> <tagB> jump <label>'` | Falsche Wortanzahl. | Genau vier Wörter. |
| `VJavaParseException` (Einrückung) | Rücksprung passt zu keiner Ebene / Tabs-Mix. | Einheitlich 4 Leerzeichen. |
| `MISSING_JUMP_TARGET`, `MISSING_ASSET`, `UNDEFINED_SPEAKER`, `UNREACHABLE_LABEL` | Lint-Befunde (`ERROR`/`WARNING`). | Ziel/Asset/Sprecher anlegen bzw. Label verlinken. |
| `PACING_LONG_MONOLOGUE` | Optionaler Pacing-Hinweis (`INFO`) ab 15 Sätzen ohne Choice/Szenenwechsel. | Ggf. Choice, Pause oder Szenenwechsel einstreuen (`DialogPacingLinter`). |
| `ScriptExecutionException` (`MAX_CALL_STACK_DEPTH` / Schleifenlimit) | Endlos-`call` (`> 128` Ebenen) oder unbegrenzte `while`-Schleife. | Abbruchbedingung bzw. `return` prüfen. |
| `VJavaCompileException` | Fehler in `@->{ }<<`. | Fehlerzeile bezieht sich auf die `.vjava`-Datei; Java-21-Syntax prüfen, JDK (nicht JRE) verwenden. |
