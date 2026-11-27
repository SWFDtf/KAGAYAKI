# KAGAYAKI Save & Preferences JSON Schema (`#0555`, `#0595`)

Dieses Dokument beschreibt die stabile JSON-Struktur für `SaveSlotData` (`.ksav`, GZIP-komprimiertes oder Klartext-UTF-8 JSON) und `PreferencesState` (`preferences.json`) zur Verwendung in Save-Editoren, Migrations-Pipelines und externen Tools.

## 1. `SaveSlotData` Schema (`v1.0.0+`)

| Feld | Typ | Pflicht | Beschreibung |
| :--- | :--- | :--- | :--- |
| `metadata` | `SaveMetadata` | Ja | Slot-Header mit ID, Notiz, Zeitstempel, Spielzeit, Version & PNG-Thumbnail |
| `variables` | `Map<String, Object>` | Ja | Typerhaltend serialisierte VN-Variablen (`SaveValueCodec` inkl. `__ktype` / `__vjavaClass`) |
| `scriptPosition` | `SavedScriptPosition` | Ja | Entkoppelte Laufzeitkoordinaten (`currentLabel`, `statementIndex`, `callStack`, `stageSnapshot`) |
| `historyEntries` | `List<HistoryEntry>` | Ja | Chronologischer Dialog-Backlog |
| `voiceBookmark` | `SavedVoiceBookmark` | Nein | Aktives Lesezeichen für lange Monolog-Audio-Dateien |

### `SaveMetadata`
- `slotId` (`string`): Validierte Slot-ID ohne Pfad-Traversal (`[A-Za-z0-9][A-Za-z0-9._-]{0,127}`)
- `customName` (`string`): Benutzernotiz / Titel (`#0541`)
- `savedAt` (`string`, UTC ISO-8601): Speicherzeitpunkt
- `currentLabel` (`string`): Aktives `.vjava`-Label für Kapitel-Filter (`#0561`)
- `dialoguePreview` (`string`): Vorschautext des aktuellen Dialogs
- `playtimeSeconds` (`number`): Kumulierte Spielzeit in Sekunden (`#0537`)
- `gameVersion` (`string`): Projektversion (z. B. `"1.0.0"`)
- `thumbnailWidth` / `thumbnailHeight` (`integer`): Thumbnail-Auflösung in Pixeln
- `thumbnailPng` (`string`, Base64): Rohbytes des PNG-Thumbnails

### Besondere Variablen in `variables`
- `__saveVersion` (`integer`): Aktuelle Save-Schema-Version (Default `1`)
- `__migrationTrail` (`string[]`): Protokoll durchlaufener Migrationen (z. B. `["1->2"]`)
- `developerTainted` (`boolean`): Gesetzt bei aktivem Studio-Cheat/Warp (`#0545`)
- `randomSeed` (`integer` / `long`): Deterministischer PRNG-Seed (`#0540`)

## 2. `PreferencesState` Schema (`preferences.json`)

| Feld | Typ | Wertebereich / Default | Beschreibung |
| :--- | :--- | :--- | :--- |
| `masterVolume`, `musicVolume`, `soundVolume`, `voiceVolume`, `ambientVolume`, `uiVolume` | `float` | `[0.0, 1.0]` | Audiokanal-Lautstärken (perzeptiv skaliert via `v^2`) |
| `textSpeedCps` | `float` | `>= 0.0` (Default `40.0`) | Schreibmaschinen-Geschwindigkeit (`0` = sofort) |
| `autoForwardDelaySeconds` | `float` | `>= 0.0` (Default `2.5`) | Wartezeit für Auto-Read bei unvertonter Zeile |
| `autoReadVoiceWaitSeconds` | `double` | `[0.0, 10.0]` (Default `0.75`) | Nachlaufpause nach beendetem Voice-Clip (`#0553`) |
| `autosaveOnQuit` | `boolean` | Default `true` | Automatisches Sichern beim normalen Schließen (`#0526`) |
| `saveSlotSortMode` | `string` | `"LAST_SAVED"` / `"SLOT_NUMBER"` | Sortiermodus im Load-/Save-Bildschirm (`#0580`) |
| `language` | `string` | Default `"deutsch"` | Aktive Lokalisierungssprache |
| `renderMode` | `string` | `"2D"` / `"3D"` | Aktiver Render-Modus |
| `reduceMotion`, `monoAudio`, `muteOnFocusLoss`, `stopVoiceOnAdvance`, `accessibilityAudioCues` | `boolean` | Defaults siehe Klasse | Barrierefreiheits- und Audio-Fokus-Optionen |

## 3. Regression-Fixture
Siehe `src/test/resources/saves/save_v1_0_0_fixture.json` (`#0595`).
