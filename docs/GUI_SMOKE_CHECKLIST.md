# GUI Smoke-Checkliste (Window A & Window B — macOS / Windows / Linux) (`#0895`)

| # | Ziel-Fenster | Aktion | Erwartetes Ergebnis |
| :- | :--- | :--- | :--- |
| 1 | Window A | Engine im `--studio`-Modus starten | Project-Explorer, Flow-Graph, Live-Linter (`0 Errors`) & „Neues Projekt“-Button sichtbar |
| 2 | Window A | „Neues Projekt“ → `FULL_DEMO` anlegen | `main.vjava`, `ui/screens.vjava`, `assets/icon.png`, `.gitignore` angelegt & direkt spielbar |
| 3 | Window B | „Start“ im `template_main_menu` klicken | Hintergrund, Dialogbox, Typewriter & Sprachausgabe laufen synchron ohne Audio-Knacken |
| 4 | Window B | `Shift+R` nach Skript-Änderung drücken | Aktuelle Szene aktualisiert sofort unter Erhalt von `$mut_punkte` & Inventar |
| 5 | Window B | `wasd_init` mit Tastatur & Gamepad Stick | Figur bewegt sich flüssig; Kollision mit Zielstern triggert Ziel-Label |
| 6 | Window B | `QuickSave` / `Load` auf Slot 1–6 | Thumbnail + Custom-Note sichtbar; Load stellt exakten Zustand wieder her |
