# Tutorial: Erstes eigenes `.vjava`-Projekt bis `.vpak` (`#0929`)

1. **Studio starten**: `./gradlew run --args="--studio"` öffnet **Studio (Fenster A)** und **Game Preview (Fenster B)**.
2. **Neues Projekt anlegen**: Im Studio-Hub auf **Neues Projekt** klicken, Vorlage `FULL_DEMO` (oder `TUTORIAL_5_MIN`) wählen.
3. **Erste Szene schreiben** in `main.vjava`:
   ```vjava
   define lena = Character("Lena", color="#58a6ff")
   label start:
       scene bg_akademie_nacht with fade(0.5)
       show lena neutral at center
       lena "Hallo aus meinem ersten KAGAYAKI-Projekt!"
       return
   ```
4. **Live testen**: In Fenster A auf **Lint** prüfen (`0 Fehler, 0 Warnungen`) und mit `Shift+R` im Spielfenster sofort testen.
5. **Standalone-Export**: Im Studio **Build Standalone (.vpak)** ausführen — erzeugt `dist/` mit `game.vpak`, `run_game.sh` und `run_game.bat`.
