# Schneewittchen — Praxisnahes Demo-Projekt für vJava (KAGAYAKI)

Dieses Verzeichnis enthält ein vollständiges, lauffähiges Demo-Projekt für **vJava** (die Skript- und Java-21-Visual-Novel-Engine **KAGAYAKI**). Als fachliches Szenario dient das Märchen **„Schneewittchen und die sieben Zwerge“**, um sämtliche Kernkonzepte von vJava greifbar zu machen.

---

## Inhaltliche Grundlage (7 Kapitel)

1. **Vorgeschichte & Wunsch der Königin:** Am Ebenholzfenster sticht sich die gute Königin in den Finger; drei Tropfen Blut fallen in den Schnee. Ihr Wunsch erfüllt sich mit Schneewittchens Geburt, doch sie stirbt dabei.
2. **Die böse Stiefmutter & der Zauberspiegel:** Die eitle neue Königin befragt ihren Zauberspiegel (*„Spieglein, Spieglein an der Wand…“*), bis dieser verkündet, dass Schneewittchen tausendmal schöner sei.
3. **Die Flucht in den Wald:** Der Jäger soll Schneewittchen töten, verschont es jedoch aus Mitleid und bringt stattdessen die Organe eines jungen Frischlings zurück.
4. **Das Haus der sieben Zwerge:** Schneewittchen findet das kleine Häuschen mit den sieben Tellern, Bechern und Bettchen, schläft im siebten Bettchen ein und wird von den sieben Zwergen aufgenommen und eindringlich gewarnt.
5. **Die drei Mordversuche der Königin:** Verkleidet zieht die Königin über die sieben Berge:
   - *1. Versuch (Schnürriemen):* Als Krämerin schnürt sie Schneewittchen ohnmächtig (`OHNMAECHTIG`) — die Zwerge schneiden den Riemen durch (`LEBENDIG`).
   - *2. Versuch (Giftkamm):* Als anderes altes Weib steckt sie den vergifteten Kamm ins Haar (`VERGIFTET`) — die Zwerge entfernen ihn rechtzeitig (`LEBENDIG`).
   - *3. Versuch (Vergifteter Apfel):* Als Bäuerin teilt sie einen halb weißen, halb roten Apfel; Schneewittchen beißt in die vergiftete rote Hälfte (`VERGIFTET`).
6. **Der Glassarg & die Erlösung:** Die Zwerge betten Schneewittchen in einen Glassarg auf dem Berg. Ein Königssohn erbittet den Sarg; beim Hinabtragen stolpern seine Diener über einen Strauch, das Apfelstück löst sich und Schneewittchen erwacht (`ERLOEST`).
7. **Hochzeit & Ende:** Nach der unumkehrbaren Schicksalswende (`block_rollback`) feiern Schneewittchen und der Prinz Hochzeit; die böse Stiefmutter wird in rotglühenden eisernen Pantoffeln gerichtet (`BESTRAFT`).

---

## Verwendete vJava- & KAGAYAKI-Funktionen

- **Deklarative `.vjava`-Skripte (`project/`)**: `define Character(...)`, `default`-Variablen, `image`, `layeredimage schneewittchen` (inkl. `group kleid`, `group zustand`, `group mouth` für Lip-Sync und bedingtem `if`-Overlay), ATL-`transform`-Animationen (`easein`, `easeout`, `linear`, `repeat`), `scene`/`show`/`hide`/`with` (`dissolve`, `fade`, `wipeleft`, `irisout`, `vpunch`, `hpunch`), `play`/`voice`/`stop`, `call`/`return` für die wiederverwendbare Spiegel-Subroutine, `menu`-Entscheidungen und `block_rollback`.
- **Eingebettete Java-21-Klassen (`:: Kode für VN ... @->{ ... }<<`)**: Definiert `FigurZustand`, `AnschlagProtokoll` und `MaerchenStatus` direkt in `main.vjava`, kompiliert im Arbeitsspeicher über `JavaBlockCompiler` (`VJavaUserTypes`) und greift auf `state`, `persistent` und `engine` zu.
- **Deklaratives UI & Lokalisierung**: `ui/screens.vjava` (`screen maerchen_hud` via `ScreenAstBuilder`) und `tl/english/translations.vjava` (`LocalizationManager` mit Live-Sprachwechsel Deutsch/Englisch).
- **4-Tier-Persistenz & Meta-Systeme**: `RevertableStateStore` (Rollback & Roll-Forward + Barriere), `SaveManager` (atomares GZIP-JSON Quick-Save/Load mit `__vjavaClass`-Rekonstruktion), `HistoryLog`, `AchievementManager`, `GalleryManager` und `EndingsManager`.
- **Prozedurale Assets (`SnowWhiteAssetGenerator`)**: Alle PNG-Hintergründe/-Sprites und WAV-Musik-/SFX-/Voice-Dateien werden ohne `java.awt` direkt über `TextureManager.writePng` und `AudioDecoder.encodeWav` erzeugt — `VJavaLinter` meldet **0 Fehler und 0 Warnungen**.
- **Welle-4-Showcase & Qualitätssicherung (`de.swfdtf.demo.features`, `director`, `qa`)**:
  - *Progression & Story*: `SnowWhiteChapterCatalog`, `SnowWhiteCodexCatalog`, `SnowWhiteAffinityDirector`, `SnowWhiteQuoteBook`, `SnowWhiteReadingStats`.
  - *Regie, Audio & Accessibility*: `SnowWhiteWeatherDirector`, `SnowWhiteCameraChoreographer`, `SnowWhiteAudioShowcase`, `SnowWhiteAccessibilityProfile`, `SnowWhiteScreenshotShowcase`.
  - *QA, Integrität & CLI*: `SnowWhitePlaythroughSuite`, `SnowWhiteFlowAndTranslationReport`, `SnowWhiteIntegrityAuditor`, `SnowWhiteDiagnosticExporter`, `SnowWhiteShowcaseCli`.

---

## Projektstruktur

```text
demo/
├── build.gradle                     # Gradle-Konfiguration (Java 21, KAGAYAKI Composite Build, LWJGL 3.3.6, JUnit 6)
├── settings.gradle                  # Composite-Build-Einbindung des Hauptprojekts (..)
├── README.md                        # Diese Dokumentation
├── project/                         # Eigenständiges KAGAYAKI-.vjava-Projekt
│   ├── project.vjava.json           # Projekt-Metadaten (ProjectDescriptor)
│   ├── main.vjava                   # Charaktere, LayeredImage, ATL-Transforms, @->{ ... }<<, label start
│   ├── kapitel/                     # Die 7 Märchenkapitel in 4 .vjava-Dateien
│   ├── ui/screens.vjava             # Deklaratives HUD (screen maerchen_hud)
│   ├── tl/english/translations.vjava# Englische Live-Übersetzung
│   └── assets/                      # Prozedural erzeugte PNG-Bilder & WAV-Audiodateien
└── src/
    ├── main/java/de/swfdtf/demo/
    │   ├── Main.java                # CLI-Einstiegspunkt (--auto, --interactive, --studio, --standalone, --lint, --export, --showcase, --preflight, --flow-graph)
    │   ├── model/                   # CharacterStatus, MurderAttempt, StoryCheckpointSummary
    │   ├── service/                 # SnowWhiteAssetGenerator, SnowWhiteConsoleNarrator, SnowWhiteEngineService
    │   ├── story/                   # SnowWhiteStoryRunner
    │   ├── features/                # ChapterCatalog, CodexCatalog, AffinityDirector, QuoteBook, ReadingStats
    │   ├── director/                # WeatherDirector, CameraChoreographer, AudioShowcase, AccessibilityProfile, ScreenshotShowcase
    │   └── qa/                      # PlaythroughSuite, FlowAndTranslationReport, IntegrityAuditor, DiagnosticExporter, ShowcaseCli
    └── test/java/de/swfdtf/demo/
        ├── SnowWhiteDemoTest.java   # JUnit-6-Tests für Linter, Handlung, Rollback, Lokalisierung & .vpak-Export
        ├── Wave4DemoProgressionTest.java
        ├── Wave4DemoDirectorTest.java
        └── Wave4DemoQaTest.java
```

---

## Demo ausführen & testen

Alle Befehle werden vom Wurzelverzeichnis des Repositories aus mit dem Gradle-Wrapper gestartet:

```bash
# 1. Automatisierte Konsolen-Vorführung (erzählt alle 7 Kapitel + demonstriert vJava-Features)
./gradlew -p demo runDemoAuto

# 2. Interaktive Konsolen-Steuerung (Enter = Weiter, r = Rollback, f = Roll-Forward, s/l = QuickSave/Load, e = DE/EN)
./gradlew -p demo runDemoInteractive --console=plain

# 3. Statische Projektprüfung mit VJavaLinter (0 Fehler, 0 Warnungen)
./gradlew -p demo lintDemo

# 4. JUnit-6-Tests für das Demo-Projekt ausführen
./gradlew -p demo test

# 5. Vollständigen Showcase-Bericht generieren (Flow-Graph, Audit, Diagnostik, Manifest & Pre-Flight)
./gradlew -p demo runDemoShowcase

# 6. Grafische Ausführung im KAGAYAKI Dual-Window-Studio oder Standalone-Fenster
./gradlew -p demo runDemoStudio
./gradlew -p demo runDemoStandalone
```
