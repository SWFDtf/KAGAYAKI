# Package `de.swfdtf.ui` — AGENT_CONTEXT

## Zweck des Pakets
Vollständiges Dark-Slate-UI-, Screen-Management-, Widget-, `screen`-AST-Builder-, `LayeredImage`-Widget-, Spiel-Laufzeitkoordinations- (`GameRuntimeCoordinator`) sowie eingebautes VN-Menü- und 3-Modi-Dialog-Subsystem von KAGAYAKI (Meilensteine M6–M7): `ThemePalette` definiert das kanonische Dark-Slate-Farbschema (`#0d0e15` bis `#f85149`); `UiNode`, `Screen` und `ScreenManager` bilden den Z-sortierten Screen-Stack mit Modal-Blockierung, Tastatur-Shortcuts, Timern und Focus-Traversal; 8 Layout-Container (`VBox`, `HBox`, `Grid`, `Fixed`, `Frame`, `WindowBox`, `Viewport`, `VpGrid`) und 13 interaktive Widgets (`TextWidget`, `TextButton`, `ImageButton`, `Hotspot`, `Hotbar`, `Imagemap`, `Bar`, `VBar`, `Slider`, `TextInput`, `DragItem`, `DragGroup`, `CustomDisplayableWidget`) unterstützen `ScreenAstBuilder` zur direkten Instanziierung geparster `.vjava`-`screen`-Definitionen; zudem enthält das Paket die 3 umschaltbaren Dialogmodi (`AdvDialogueScreen`, `NvlDialogueScreen`, `BubbleDialogueScreen` inkl. `Shift+B`-Editor), Quick-Menu/Choice/Input-Overlays (`QuickMenuScreen`, `ChoiceMenuScreen`, `InputOverlayScreen`), sämtliche eingebauten Meta-/System-Screens (`MainMenuScreen`, `SaveLoadScreen`, `PreferencesScreen`, `ControlsScreen`, `AboutScreen`, `HistoryLogScreen`, `GalleryScreen`, `MusicRoomScreen`, `EndingsTreeScreen`, `AchievementsScreen`), `NotificationToastOverlay` sowie `GameRuntimeCoordinator` (`implements GameHost`) zur Verdrahtung aller Laufzeit-Subsysteme mit `EngineApplication`.

## Abhängigkeiten zu anderen `de.swfdtf.*`-Paketen
- `de.swfdtf.core` (`InputManager` in `ScreenManager`, `GameRuntimeCoordinator`; `ProjectDescriptor` in `MainMenuScreen`, `AboutScreen`; `EngineAPI`, `EngineApplication`, `GameHost` in `AboutScreen`, `GameRuntimeCoordinator`)
- `de.swfdtf.graphics` (`SceneNode` in `ThemePalette`; `FontManager`, `RenderPipeline2D`, `TextureManager`, `Texture`, `NineSliceDrawable`, `SceneLayer`, `SceneController`, `SpriteNode`, `StyledTextLayout`, `TextTagParser`, `CustomDisplayable`, `RenderMode`, `AnimationEngine`, `TransitionManager`, `IRenderPipeline`, `SceneGraph` in Widgets, `Screen`, `ScreenAstBuilder`, `UiRenderSupport`, `UiMediaSupport`, Dialogue-/Meta-Screens, `GameRuntimeCoordinator`)
- `de.swfdtf.math` (`Rect2f`, `Vec2f`, `ColorMatrix4f`)
- `de.swfdtf.parser` (`VJavaAst`, `ConditionEvaluator`, `JavaBlockCompiler`, `ProjectScanner`, `ScriptRuntime`, `ScriptSession`)
- `de.swfdtf.state` (`StateStore`, `PersistentAccess`, `PreferencesState`, `LocalizationManager`, `SaveManager`, `SaveSlotData`, `HistoryLog`, `GalleryManager`, `EndingsManager`, `AchievementManager`)
- `de.swfdtf.audio` (`AudioEngine`, `LipSyncAnalyzer`, `TextToSpeechService`)
- Externe Bibliotheken: `org.lwjgl.glfw.GLFW`, `org.lwjgl.opengl.GL11`, `org.lwjgl.stb.STBImage`, `org.lwjgl.system.*`, `com.google.gson.*`.

## Enthaltene Typen

### Kern, Theme & Screen-Management
| Typ | Art | Beschreibung |
| :--- | :--- | :--- |
| `ThemePalette` | `final class` | Kanonische Dark-Slate-Farbkonstanten (`CANVAS_BG`..`ERROR`), kuratierte `ThemeVariant`-Varianten (`DARK_SLATE`, `MIDNIGHT_HIGH_CONTRAST`, `DAYLIGHT`), Straight-/Premultiplied-RGBA-Konverter sowie WCAG-2.1-Kontrastberechnung (`contrastRatio`, `meetsWcagAA`). |
| `UiNode` | `abstract class` | Abstrakte Basisklasse aller UI-Container und -Widgets mit `measure`/`layout`/`update`/`render`, Padding, Box-Styling, Z-order-Input-Dispatching und Fokus-Flags. |
| `Screen` | `class` | Deklarativer Screen-Container mit Wurzel-`UiNode`, `zOrder`, `modal`-Flag, `onShow`/`onHide`-Lebenszyklus, `KeyBinding`-Shortcuts und `ScreenTimer`-Timern. |
| `Screen.KeyBinding` & `ScreenTimer` | `record` / `final class` | Screen-lokale GLFW-Tastenkürzel-Bindung (`glfwKey`, `requiredModsMask`, `action`) sowie einmaliger/wiederholender Screen-Timer. |
| `ScreenManager` | `final class` | Z-sortierter Screen-Stack-Verwalter mit Modal-Input-Sperrung, Mouse-/Scroll-/Char-/Key-Routing, `Tab`/`Shift+Tab`-Focus-Traversal und Rendering. |

### Layout-Container
| Typ | Art | Beschreibung |
| :--- | :--- | :--- |
| `VBox` | `class` | Vertikaler Stapel-Container (`extends UiNode`) mit `spacing`, `fillWidth` und `alignCenterX`. |
| `HBox` | `class` | Horizontaler Stapel-Container (`extends UiNode`) mit `spacing`, `alignCenterY` und `fillHeight`. |
| `Grid` | `class` | Gleichmäßiger Zeilen-/Spalten-Rastercontainer (`extends UiNode`) mit konfigurierbarem horizontalem und vertikalem Zellabstand. |
| `GridBox` | `class` | Automatischer Spaltenraster-Container (`extends UiNode`) für dynamisch verteilte Kinder in `cols` Spalten. |
| `TabPanel` | `class` | Mehrseitiger Tab-Container (`extends UiNode`) mit Reiterleiste und `Ctrl+Tab`-Zykluswechsel. |
| `Fixed` | `class` | Koordinaten- und ausrichtungsbasierter Container (`extends UiNode`) mit expliziten Pixel-Rechtecken (`addChildAt`) und `AlignmentSpec` (`xpos`/`ypos`/`xalign`/`yalign`). |
| `Frame` | `class` | Gerundeter Dark-Slate-Panel-Container (`extends UiNode`) mit optionalem `NineSliceDrawable`-Rahmen. |
| `WindowBox` | `class` | Erhabener Dark-Slate-Fenster-Container (`extends UiNode`) mit Titelkopfzeile (`DEFAULT_HEADER_HEIGHT = 40.0f`) über dem Inhaltsbereich. |
| `Viewport` | `class` | Scrollbarer Scissor-Clipping-Container (`extends UiNode`) mit Mausrad-Unterstützung und interaktiver Scrollbar-Ziehfunktion. |
| `VpGrid` | `class` | Virtualisierter scrollbarer Rastercontainer (`extends UiNode`), der ausschließlich den sichtbaren Zeilenbereich `[firstVisibleRow..lastVisibleRow]` layoutet, rendert und hit-testet. |

### Widgets & AST-Builder
| Typ | Art | Beschreibung |
| :--- | :--- | :--- |
| `TextWidget` | `class` | Rich-Text-UI-Widget (`extends UiNode`) auf Basis von `TextTagParser` und `StyledTextLayout` mit Wortumbruch, Drop-Shadow und optionalem `TypewriterController`. |
| `TextButton` | `class` | Interaktiver Dark-Slate-Button (`extends UiNode`) mit Hover-/Focus-/Selected-/Disabled-Styling sowie Mausklick- und `Enter`/`Space`-Aktivierung. |
| `ImageButton` | `class` | Bildbasierter interaktiver Button (`extends UiNode`) mit eigenen Asset-Pfaden für Idle, Hover, Selected und Disabled. |
| `Hotspot` | `class` | Klickbarer Rechteckbereich (`extends UiNode`) innerhalb von `Imagemap` oder `Fixed` mit optionalem Hover-Overlay-Farbton. |
| `Hotbar` | `class` | Regionsbasierte Fortschrittsanzeige (`extends UiNode`) innerhalb von `Imagemap`/`Fixed` mit dynamischem `Supplier<Float>`. |
| `Imagemap` | `class` | Bildkarten-Container (`extends UiNode`) aus `ground`-Basisbild, `hover`-Overlay-Textur und positionierten `Hotspot`/`Hotbar`-Kindern. |
| `Bar` | `class` | Horizontaler Fortschritts-/Pegelbalken (`extends UiNode`) mit Dark-Slate-Track und Füllfarbe. |
| `VBar` | `class` | Vertikaler Bottom-to-Top-Fortschritts-/Pegelbalken (`extends UiNode`), u. a. für den Musikraum-Spektrumanalyzer. |
| `Slider` | `class` | Interaktiver horizontaler Schieberegler (`extends UiNode`) mit Klick-Sprung, Dragging, `step`-Einrasten und Links/Rechts-Tastensteuerung. |
| `TextInput` | `class` | Einzeiliges oder mehrzeiliges (`setMultiline(true)`) Texteingabefeld (`extends UiNode`) mit `allowChars`-Whitelist, `maxLength`-Begrenzung, Cursor-Navigation (`LEFT`/`RIGHT`/`HOME`/`END`), `Delete`/`Backspace` und Enter-Submit. |
| `SelectBox` | `class` | Aufklappbares Auswahlfeld (`extends UiNode`) für Listen-Optionen per Klick, Tastatur und `Escape`-Abbruch. |
| `Checkbox` | `class` | Fokusierbare Checkbox (`extends UiNode`) für boolesche Zustände via Klick oder `Space`/`Enter`. |
| `ToggleSwitch` | `class` | Fokusierbarer Kippschalter (`extends UiNode`) mit Pill-Track und Zustandswechsel-Callback. |
| `DragItem` | `class` | Ziehbares Element und/oder Drop-Target-Widget (`extends UiNode`) zur Koordination durch `DragGroup`. |
| `DragGroup` | `class` | Drag-and-Drop-Container (`extends UiNode`) für Pointer-Tracking, Drop-Target-Schnittsuche, zentriertes Einrasten (`snappedTo`) und `onDrop`-Callbacks. |
| `CustomDisplayableWidget` | `class` | UI-Wrapper (`extends UiNode`), der beliebige `CustomDisplayable`-Instanzen (z. B. `LayeredImage`, `ParticleSystem`) in deklarative UI-Layouts einbettet. |
| `ScreenAstBuilder` | `final class` | Instanziiert aus einem geparsten `VJavaAst.ScreenDef` einen lauffähigen `Screen` samt `UiNode`-Baum, `if`/`elif`/`else`-Zweigen, `key`/`timer`-Bindungen, `VariableValue`/`VariableInputValue` und Action-Dispatching. |

### Dialog-Modi & In-Game-Overlays
| Typ | Art | Beschreibung |
| :--- | :--- | :--- |
| `AdvDialogueScreen` | `class` | Klassischer unterer ADV-Dialogkasten (`Z = 200`, `(140, 790, 1640, 250)`) mit Sprecher-Badge, `PreferencesState`-Typewriter, gedrosselten Voice-Bleeps, Auto-Forward, Skip, TTS und QuickMenu-Leiste. |
| `NvlDialogueScreen` | `class` | Vollbild-NVL-Dialogmodus (`Z = 200`) mit lichtdurchlässigem Backdrop und scrollbarer `Viewport`-Absatzliste aus `ScriptRuntime.getNvlLines()`. |
| `BubbleDialogueScreen` | `class` | Comic-/Manga-Sprechblasen-Dialogmodus (`Z = 200`) mit dynamischem Sprite-Anker-Pointer-Tail, Multi-Bubble-Retain-Modus und integriertem `Shift+B`-Layout-Editor (`saves/bubble_layouts.json`). |
| `BubbleDialogueScreen.BubbleLayoutConfig` & `BubbleEntry` | `record` | Serialisierbare Sprechblasen-/Tail-Geometrie und aktiver/beibehaltener Sprechblaseneintrag. |
| `QuickMenuScreen` | `class` | Schwebende QuickMenu-HUD-Leiste (`Z = 210`) mit Buttons für `Back`, `History`, `Skip`, `Auto`, `Save`, `Q.Save`, `Q.Load` und `Prefs`. |
| `ChoiceMenuScreen` | `class` | Auswahlmenü-Overlay (`Z = 300`) für `ScriptRuntime.MenuPrompt` mit Sichtbarkeits-/Disabled-Logik und Live-Timeout-`Bar`. |
| `InputOverlayScreen` | `class` | Modales Texteingabe-Overlay (`Z = 300`) für `ScriptRuntime.InputPrompt` mit `TextInput` (Whitelist/MaxLength) und Confirm-Button. |

### Eingebaute System-, Meta- & Toast-Screens
| Typ | Art | Beschreibung |
| :--- | :--- | :--- |
| `MainMenuScreen` | `class` | Dark-Slate-Hauptmenü (`Z = 400`, modal) mit Projekt-Banner, `Start`, automatisch ermitteltem `Continue`, `Load`, `Gallery`, `Music Room`, `Endings`, `Achievements`, `Preferences`, `Controls`, `About` und `Quit`. |
| `SaveLoadScreen` | `class` | Dual-Mode Save/Load-Screen (`Z = 400`, modal) mit 5×6-Slot-Raster (`MANUAL`/`QUICK`/`AUTO`-Tabs), Custom-Namen, STB-PNG-Thumbnail-Vorschau, Metadaten und Slot-Löschung. |
| `SaveLoadScreen.Mode`, `FilterTab` & `SlotCardView` | `enum` / `static final class` | Interaktionsmodus (`SAVE`/`LOAD`), Filter-Tab (`MANUAL`/`QUICK`/`AUTO`) und Slot-Karten-View-Model. |
| `PreferencesScreen` | `class` | Einstellungs-Screen (`Z = 400`, modal) mit 5 Audio-Pegel-, Textgeschwindigkeits-, Auto-Delay- und UI-Scale-Slidern, Skip-/Fullscreen-Toggles, `ADV`/`NVL`/`BUBBLE`-, Sprach- (`LocalizationManager`), `2D`/`3D`- sowie TTS-/Dyslexie-Umschaltern. |
| `ControlsScreen` | `class` | Tabellen-Referenzscreen (`Z = 400`, modal) aller Keyboard-, Mouse- und Gamepad-Bindungen mit Kategorie-Filtertabs. |
| `ControlsScreen.Category` & `BindingEntry` | `enum` / `record` | Kategorie-Filter (`ALL`, `KEYBOARD`, `MOUSE`, `GAMEPAD`) und unveränderlicher Bindungseintrag. |
| `AboutScreen` | `class` | Info-Screen (`Z = 400`, modal) für Projekttitel, Version, Autor, `EngineAPI.ENGINE_VERSION`, `RENDER_BACKEND_NAME`, aktiven `RenderMode` und Open-Source-Lizenzen. |
| `HistoryLogScreen` | `class` | Scrollbares Dialog-Backlog (`Z = 400`, modal) mit Sprecherfarbe, Text, `Replay Voice` (`AudioEngine`) und `Jump Back` (`ScriptSession.jumpToHistoryEntry`). |
| `HistoryLogScreen.HistoryRowView` | `static final class` | View-Model einer einzelnen History-Eintragskarte. |
| `GalleryScreen` | `class` | CG-Galerie (`Z = 400`, modal) auf Basis von `GalleryManager` mit `VpGrid`, gesperrten/freigeschalteten Karten, Variations-Blättern (`Prev`/`Next`) und Vollbild-Zoom. |
| `GalleryScreen.GalleryCardView` | `static final class` | View-Model einer Galerie-Kachel. |
| `MusicRoomScreen` | `class` | Musikraum (`Z = 400`, modal) für freigeschaltete Tracks (`PersistentState.getUnlockedTracks()`) mit `AudioEngine`-Steuerung und 16-Band-`VBar`-Echtzeitspektrum. |
| `EndingsTreeScreen` | `class` | Endings-Routenbaum (`Z = 400`, modal) auf Basis von `EndingsManager` mit Eltern-Kind-Hierarchie, Freischaltstatus und Gesamt-Completion-`Bar`. |
| `EndingsTreeScreen.TreeNodeView` | `record` | Flacher Knoten-Schnappschuss eines Endings im Routenbaum. |
| `AchievementsScreen` | `class` | Erfolgsübersicht (`Z = 400`, modal) auf Basis von `AchievementManager` mit Fortschrittsbalken und Maskierung versteckter gesperrter Achievements (`"???"`). |
| `AchievementsScreen.AchievementRowView` | `record` | View-Model einer einzelnen Achievement-Zeile. |
| `NotificationToastOverlay` | `class` | Nicht-modales Toast-Overlay (`Z = 500`), das `AchievementManager.pollToast()` animiert ein-/ausblendet (`4.0s`) und eine `SKIPPING >>`/`AUTO >`-Status-Pill anzeigt. |
| `NotificationToastOverlay.ToastPayload` | `record` | Toast-Nachrichten-Nutzlast `(title, subtitle)`. |
| `WrapBox` | `class` | Responsiver Flexbox-ähnlicher Wrap-Container (`#0376`) für automatische Zeilenumbrüche über `horizontalSpacing`/`verticalSpacing`. |
| `SegmentedControl` | `class` | Animiertes Segmented Control (`#0407`) für mehrstufige Optionen (`Windowed/Fullscreen`, `2D/3D`) mit `reduceMotion`-Support. |
| `ContextMenuOverlay` | `class` | Rechtsklick-Kontextmenü-Overlay (`#0421`) innerhalb von `1920x1080` mit sofortiger Schließung bei Klick außerhalb oder `Escape`. |
| `CreditsScreen` | `class` | Vertikal scrollender Credits-Screen (`#0374`) mit 4x-Fast-Forward und automatischem Rückkehr-Callback. |
| `SplashScreen` | `class` | Kaltstart-Lade- und Splash-Overlay (`#0382`) mit KAGAYAKI-Banner, `EngineVersion`, Fortschrittsbalken und Skip-Option. |
| `ChapterBannerOverlay` | `class` | Transiente Kapitel-Titel-Einblendung (`#0366`) mit automatischem Fade-Out und Skip-Abbruch. |
| `ProgressSpinnerModal` | `class` | Modales Asynchron-Fortschritts-Overlay (`#0404`) für lange Export-/Scan-Aufgaben ohne Loop-Blockierung. |
| `UiPolishAndServices` | `final class` | Gebündelte Wave-P2-5-UI-/Barrierefreiheits-Services (`UiSoundCueRouter`, `HudVisibilityController`, `SpeakerDimmingDirector`, `HotbarInventoryBinder`, `PromptGlyphMapper`, `UiSkinRegistry`, `DialoguePunctuationPacer`, `ChoiceHistoryTracker`, `WasdArenaHudGuide`, `UiWidgetTreeDumper`, `UiLayoutOverlapAuditor`, `SaveSlotMetaManager`, `GameplayStatsPresenter`, `AdaptiveDialogueBoxCalculator`, `BubbleCollisionResolver`, `HoverGlossaryPreview`, `GalleryViewerController`, `MusicRoomSeekController`). |
| `UiTranslations` | `final class` | Fünfsprachiges UI-Übersetzungsbündel (`deutsch`, `english`, `french`, `spanish`, `japanese`) mit Projekt-Override und sicherem Fallback. |
| `ClosedCaptionOverlayScreen` | `class` | Bodenverankertes Closed-Caption-Overlay (`Z = Z_TOAST - 10`) für bis zu 3 gleichzeitige Untertitel aus `ClosedCaptionQueue`. |
| `ActionPromptOverlayScreen` | `class` | Kontextsensitives Interaktions-Badge (`[Key] Aktion`) neben interaktiven WASD-Objekten mit `KeybindingRemapper`-Synchronisation. |
| `ConfirmationDialogScreen` | `class` | Wiederverwendbarer modaler Bestätigungsdialog (`Z = 950`) mit `Bestätigen`/`Abbrechen` und `Escape`-Schließung. |
| `CodexScreen` | `class` | In-Game-Codex/Glossar-Screen (`Z = 460`, modal) mit `"Neu"`-Badges, Lesefortschritt und Sperrschutz. |
| `ChapterSelectScreen` | `class` | Kapitelauswahl-Screen (`Z = 460`, modal) mit Aktivierung freigeschalteter `ChapterRegistry`-Einträge. |
| `RelationshipOverviewScreen` | `class` | Beziehungsübersicht (`Z = 460`, modal) mit Affinitäts-Punkten, Tiers und Fortschritts-`Bar`s. |
| `QuoteBookScreen` | `class` | Zitatebuch-Screen (`Z = 460`, modal) für über Taste `B` gespeicherte Dialogzitate aus `QuoteBook`. |
| `GameRuntimeCoordinator` | `final class` | Vollständiger Spiel-Laufzeitkoordinator (`implements GameHost`) für `EngineApplication`, der `ScriptSession`, `AudioEngine`, `LipSyncAnalyzer`, `TextToSpeechService`, `AnimationEngine`, `TransitionManager`, `ScreenManager`, alle Dialog-/System-Screens, globale Shortcuts und (im Studio-Modus) `LiveDirectorOverlay`/`DeveloperInspectors` integriert. |

### Paketinterne Render- & Medienhelfer
| Typ | Art | Beschreibung |
| :--- | :--- | :--- |
| `UiRenderSupport` | `final class` *(package-private)* | Paketinterne Render-, Scissor-Stack-(`ScissorState` via `GL11.glScissor`), Scrollbar-Zeichen- und Headless-`FontManager`-Fallback-Hilfen. |
| `UiMediaSupport` | `final class` *(package-private)* | Paketinterne Dark-Slate-Karten-/Button-Helfer, Zeitstempel-/Spielzeit-Formatierung, `AudioEngine`/`TextToSpeechService`-Lookup und `PreviewImageNode` (STB-PNG-Dekodierung & lazy GL-Upload). |

## Öffentliche API-Signaturen

### `ThemePalette`
```java
public final class ThemePalette {
    public static final String CANVAS_BG = "#0d0e15";
    public static final String PANEL_SURFACE = "#161822";
    public static final String PANEL_GLASS = "#161822d9";
    public static final String ELEVATED = "#1f2232";
    public static final String BORDER = "#2e334a";
    public static final String ACCENT_IDLE = "#388bfd";
    public static final String ACCENT_HOVER = "#58a6ff";
    public static final String ACTIVE_SELECTED = "#f0883e";
    public static final String DISABLED = "#8b949e7f";
    public static final String TEXT_PRIMARY = "#f0f6fc";
    public static final String TEXT_MUTED = "#8b949e";
    public static final String SUCCESS = "#3fb950";
    public static final String WARNING = "#d29922";
    public static final String ERROR = "#f85149";

    public static float[] straightRgba(String hex)
    public static float[] premultipliedRgba(String hex, float alphaMultiplier)
}
```

### `UiNode`
```java
public abstract class UiNode {
    public Vec2f measure(float availableWidth, float availableHeight)
    public void layout(Rect2f resolvedBounds)
    public void update(double dt)
    public void render(RenderPipeline2D pipeline, FontManager fontManager, TextureManager textureManager, double elapsedSeconds)

    public UiNode dispatchMouseMove(float lx, float ly)
    public boolean dispatchMouseDown(float lx, float ly, int button)
    public boolean dispatchMouseUp(float lx, float ly, int button)
    public boolean dispatchMouseScroll(float lx, float ly, double scrollY)
    public boolean dispatchKeyPressed(int key, int mods)
    public boolean dispatchCharTyped(char ch)
    public void setHoveredDeep(boolean hovered)

    public UiNode addChild(UiNode child)
    public boolean removeChild(UiNode child)
    public void clearChildren()
    public List<UiNode> getChildren()
    public UiNode getParent()
    public Rect2f getContentBounds()
    public UiNode setPadding(float all) / setPadding(float horizontal, float vertical) / setPadding(float left, float top, float right, float bottom)
    public String getId() / setId(String id)
    public Rect2f getBounds() / setBounds(Rect2f bounds)
    public float getPreferredWidth() / setPreferredWidth(float w)
    public float getPreferredHeight() / setPreferredHeight(float h)
    public UiNode setPreferredSize(float w, float h)
    public boolean isVisible() / setVisible(boolean visible)
    public boolean isEnabled() / setEnabled(boolean enabled)
    public boolean isFocusable() / setFocusable(boolean focusable)
    public boolean isHovered() / isFocused() / setFocused(boolean focused) / isPressed()
    public float getAlpha() / setAlpha(float alpha)
    public String getBackgroundColorHex() / setBackgroundColorHex(String hex)
    public String getBorderColorHex() / setBorderColorHex(String hex)
    public float getBorderThickness() / setBorderThickness(float t)
    public float getCornerRadius() / setCornerRadius(float r)
}
```

### `Screen` & `ScreenManager`
```java
public class Screen {
    public record KeyBinding(int glfwKey, int requiredModsMask, Runnable action) {}
    public static final class ScreenTimer {
        public ScreenTimer(double intervalSeconds, boolean repeat, Runnable action)
        public void update(double dt)
        public boolean isFinished()
    }

    public Screen(String name)
    public Screen(String name, int zOrder, boolean modal)
    public void onShow()
    public void onHide()
    public void layout(float logicalWidth, float logicalHeight)
    public void update(double dt)
    public void render(RenderPipeline2D pipeline, FontManager fontManager, TextureManager textureManager, double elapsedSeconds)
    public Screen addKeyBinding(int glfwKey, Runnable action)
    public Screen addKeyBinding(int glfwKey, int requiredModsMask, Runnable action)
    public Screen addTimer(double intervalSeconds, boolean repeat, Runnable action)
    public boolean triggerKeyBindings(int key, int mods)
    public List<KeyBinding> getKeyBindings()
    public String getName()
    public int getZOrder() / setZOrder(int zOrder)
    public boolean isModal() / setModal(boolean modal)
    public boolean isVisible() / setVisible(boolean visible)
    public UiNode getRoot() / setRoot(UiNode root)
}

public final class ScreenManager {
    public void setLogicalSize(float width, float height)
    public void registerScreen(Screen screen)
    public void showScreen(Screen screen) / public boolean showScreen(String name)
    public boolean hideScreen(String name)
    public boolean toggleScreen(String name)
    public Screen getScreen(String name)
    public List<Screen> getVisibleScreensSorted()
    public Screen getTopModalScreen()
    public boolean isModalActive()
    public List<Screen> getInputEligibleScreensTopDown()
    public void update(double dt, InputManager input)
    public void routeInput(InputManager input)
    public boolean dispatchMouseDown(float lx, float ly, int button)
    public boolean dispatchMouseUp(float lx, float ly, int button)
    public boolean dispatchMouseScroll(float lx, float ly, double scrollY)
    public boolean dispatchKeyPressed(int key, int mods)
    public boolean dispatchCharTyped(char ch)
    public void cycleFocus(Screen screen, boolean reverse)
    public void render(RenderPipeline2D pipeline, FontManager fontManager, TextureManager textureManager, double elapsedSeconds)
    public UiNode getFocusedNode()
    public float getLogicalWidth() / public float getLogicalHeight()
}
```

### Layout-Container (`VBox`, `HBox`, `Grid`, `Fixed`, `Frame`, `WindowBox`, `Viewport`, `VpGrid`)
```java
public class VBox extends UiNode {
    public VBox()
    public VBox setSpacing(float spacing) / getSpacing()
    public VBox setFillWidth(boolean fillWidth) / isFillWidth()
    public VBox setAlignCenterX(boolean alignCenterX) / isAlignCenterX()
}

public class HBox extends UiNode {
    public HBox()
    public HBox setSpacing(float spacing) / getSpacing()
    public HBox setAlignCenterY(boolean alignCenterY) / isAlignCenterY()
    public HBox setFillHeight(boolean fillHeight) / isFillHeight()
}

public class Grid extends UiNode {
    public Grid(int columns, int rows)
    public Grid setSpacing(float h, float v)
    public int getColumns() / getRows() / float getHorizontalSpacing() / float getVerticalSpacing()
}

public class Fixed extends UiNode {
    public record AlignmentSpec(float xPos, float yPos, float xAlign, float yAlign, boolean hasXPos, boolean hasYPos, boolean hasXAlign, boolean hasYAlign) {}
    public Fixed()
    public Fixed addChildAt(UiNode child, float x, float y, float width, float height)
    public Fixed setChildAlignment(UiNode child, AlignmentSpec spec)
}

public class Frame extends UiNode {
    public static final float DEFAULT_CORNER_RADIUS = 8.0f;
    public static final float DEFAULT_BORDER_THICKNESS = 1.0f;
    public Frame()
    public Frame setNineSlice(NineSliceDrawable nineSlice) / public NineSliceDrawable getNineSlice()
}

public class WindowBox extends UiNode {
    public static final float DEFAULT_HEADER_HEIGHT = 40.0f;
    public static final float DEFAULT_TITLE_FONT_SIZE = 20.0f;
    public WindowBox() / public WindowBox(String title)
    public WindowBox setTitle(String title) / getTitle()
    public WindowBox setHeaderHeight(float headerHeight) / getHeaderHeight()
    public WindowBox setTitleFontSize(float titleFontSize) / getTitleFontSize()
    public Rect2f getWindowContentBounds()
}

public class Viewport extends UiNode {
    public static final float DEFAULT_SCROLL_STEP = 48.0f;
    public static final float SCROLLBAR_WIDTH = 8.0f;
    public Viewport()
    public Viewport setScrollY(float scrollY) / getScrollY() / float getMaxScrollY() / float getContentHeight()
    public Viewport setScrollbarVisible(boolean scrollbarVisible) / isScrollbarVisible()
    public Viewport setScrollStep(float scrollStep) / getScrollStep()
    public boolean isDraggingScrollbar()
}

public class VpGrid extends UiNode {
    public static final float DEFAULT_SCROLL_STEP = 64.0f;
    public VpGrid(int columns, float cellWidth, float cellHeight)
    public VpGrid setSpacing(float h, float v)
    public int getColumns() / float getCellWidth() / float getCellHeight() / float getHorizontalSpacing() / float getVerticalSpacing()
    public VpGrid setScrollY(float scrollY) / getScrollY() / float getMaxScrollY() / float getTotalContentHeight()
    public int getTotalRows() / int getFirstVisibleRow() / int getLastVisibleRow()
    public VpGrid setScrollbarVisible(boolean scrollbarVisible) / isScrollbarVisible()
    public VpGrid setScrollStep(float scrollStep) / getScrollStep()
}
```

### Widgets & `ScreenAstBuilder`
```java
public class TextWidget extends UiNode {
    public static final float DEFAULT_FONT_SIZE = 24.0f;
    public static final float DEFAULT_LINE_SPACING = 1.15f;
    public TextWidget(String text)
    public TextWidget setText(String text) / getText()
    public TextWidget setFontSize(float fontSize) / getFontSize()
    public TextWidget setLineSpacing(float lineSpacing) / getLineSpacing()
    public TextWidget setColorHex(String colorHex) / getColorHex()
    public TextWidget setDropShadow(boolean dropShadow) / isDropShadow()
    public TextWidget setTypewriterCps(float cps) / getTypewriterCps()
    public TextWidget setFontManager(FontManager fontManager)
    public StyledTextLayout.TypewriterController getTypewriter()
    public StyledTextLayout getLayout()
}

public class TextButton extends UiNode {
    public static final float DEFAULT_FONT_SIZE = 22.0f;
    public static final float DEFAULT_CORNER_RADIUS = 6.0f;
    public TextButton(String label, Runnable onClick)
    public TextButton setText(String text) / getText()
    public TextButton setOnClick(Runnable onClick) / getOnClick()
    public TextButton setSelected(boolean selected) / isSelected()
    public TextButton setFontSize(float fontSize) / getFontSize()
    public String getCurrentSurfaceColorHex() / getCurrentBorderColorHex() / getCurrentTextColorHex()
    public void click()
}

public class ImageButton extends UiNode {
    public ImageButton(String idlePath, String hoverPath, Runnable onClick)
    public ImageButton setDisabledPath(String disabledPath) / getDisabledPath()
    public ImageButton setIdlePath(String idlePath) / getIdlePath()
    public ImageButton setHoverPath(String hoverPath) / getHoverPath()
    public ImageButton setSelectedPath(String selectedPath) / getSelectedPath()
    public ImageButton setSelected(boolean selected) / isSelected()
    public ImageButton setOnClick(Runnable onClick) / getOnClick()
    public String getCurrentImagePath()
    public void click()
}

public class Hotspot extends UiNode {
    public Hotspot(Rect2f area, Runnable onClick)
    public Rect2f getArea() / Hotspot setArea(Rect2f area)
    public Hotspot setOnClick(Runnable onClick) / getOnClick()
    public Hotspot setHoveredColorHex(String hoveredColorHex) / getHoveredColorHex()
    public boolean isInside(float lx, float ly)
}

public class Hotbar extends UiNode {
    public Hotbar(Rect2f area, Supplier<Float> valueSupplier, float maxValue)
    public Rect2f getArea() / Hotbar setArea(Rect2f area)
    public float getMaxValue() / Hotbar setMaxValue(float maxValue)
    public float getCurrentValue() / float getNormalizedRatio()
    public Hotbar setFillColorHex(String fillColorHex) / getFillColorHex()
    public Hotbar setTrackColorHex(String trackColorHex) / getTrackColorHex()
}

public class Imagemap extends UiNode {
    public Imagemap(String groundPath, String hoverPath)
    public String getGroundPath() / Imagemap setGroundPath(String groundPath)
    public String getHoverPath() / Imagemap setHoverPath(String hoverPath)
    public Hotspot addHotspot(Rect2f area, Runnable onClick)
    public Hotbar addHotbar(Rect2f area, Supplier<Float> valueSupplier, float maxValue)
    public List<Hotspot> getHotspots() / List<Hotbar> getHotbars()
}

public class Bar extends UiNode {
    public static final float DEFAULT_WIDTH = 300.0f;
    public static final float DEFAULT_HEIGHT = 24.0f;
    public Bar(float value, float max)
    public Bar setValue(float value) / float getValue()
    public Bar setRange(float max) / float getMax() / float getNormalizedValue()
    public Bar setFillColorHex(String fillColorHex) / getFillColorHex()
    public Bar setTrackColorHex(String trackColorHex) / getTrackColorHex()
}

public class VBar extends UiNode {
    public static final float DEFAULT_WIDTH = 24.0f;
    public static final float DEFAULT_HEIGHT = 200.0f;
    public VBar(float value, float max)
    public VBar setValue(float value) / float getValue()
    public VBar setRange(float max) / float getMax() / float getNormalizedValue()
    public VBar setFillColorHex(String fillColorHex) / getFillColorHex()
    public VBar setTrackColorHex(String trackColorHex) / getTrackColorHex()
}

public class Slider extends UiNode {
    public static final float DEFAULT_WIDTH = 300.0f;
    public static final float DEFAULT_HEIGHT = 28.0f;
    public Slider(float value, float min, float max, Consumer<Float> onChanged)
    public Slider setValue(float value) / float getValue() / float getMin() / float getMax()
    public Slider setStep(float step) / float getStep()
    public boolean isDragging()
    public Slider setOnChanged(Consumer<Float> onChanged)
    public float getNormalizedValue()
}

public class TextInput extends UiNode {
    public static final float DEFAULT_WIDTH = 340.0f;
    public static final float DEFAULT_HEIGHT = 44.0f;
    public static final float DEFAULT_FONT_SIZE = 20.0f;
    public TextInput(String initialText, Consumer<String> onSubmit)
    public TextInput setText(String text) / getText()
    public TextInput setPlaceholder(String placeholder) / getPlaceholder()
    public TextInput setAllowChars(String allowChars) / getAllowChars()
    public TextInput setMaxLength(int maxLength) / int getMaxLength()
    public TextInput setOnSubmit(Consumer<String> onSubmit)
    public TextInput setOnChanged(Consumer<String> onChanged)
    public TextInput setFontSize(float fontSize) / float getFontSize()
}

public class DragItem extends UiNode {
    public static final float DEFAULT_SIZE = 96.0f;
    public DragItem(String id, Object payload)
    public Object getPayload() / DragItem setPayload(Object payload)
    public DragItem setDropTarget(boolean dropTarget) / isDropTarget()
    public DragItem setDraggable(boolean draggable) / isDraggable()
    public DragItem setSnappable(boolean snappable) / isSnappable()
    public DragItem setOnDrop(BiConsumer<DragItem, DragItem> onDrop) / getOnDrop()
    public DragItem getSnappedTo() / boolean isDragging()
}

public class DragGroup extends UiNode {
    public DragGroup()
    public DragGroup addDragItem(DragItem item)
    public List<DragItem> getItems()
    public DragItem getActiveDraggedItem()
    public DragItem findDropTargetAt(float lx, float ly, DragItem dragged)
    public DragItem findIntersectingDropTarget(DragItem dragged)
}

public class CustomDisplayableWidget extends UiNode {
    public CustomDisplayableWidget(CustomDisplayable displayable)
    public CustomDisplayable getDisplayable()
    public CustomDisplayableWidget setDisplayable(CustomDisplayable displayable)
}

public final class ScreenAstBuilder {
    public static Screen build(VJavaAst.ScreenDef def, ScriptRuntime runtimeOrNull, Consumer<String> actionDispatcher)
}
```

### Dialog-Modi, Overlays & System-Screens
```java
public class AdvDialogueScreen extends Screen {
    public static final String SCREEN_NAME = "adv_dialogue";
    public static final float PANEL_X = 140.0f, PANEL_Y = 790.0f, PANEL_W = 1640.0f, PANEL_H = 250.0f;
    public static final double BLEEP_THROTTLE_SECONDS = 0.055;
    public static final double SKIP_STEP_SECONDS = 0.05;

    public AdvDialogueScreen(ScriptSession session, ScreenManager screenManager)
    public AdvDialogueScreen(ScriptSession session, ScreenManager screenManager, AudioEngine audioEngine, TextToSpeechService ttsService)
    public void handlePrimaryAdvance()
    public boolean toggleSkip() / void setSkipActive(boolean active) / boolean isSkipActive()
    public boolean toggleAuto() / void setAutoActive(boolean active) / boolean isAutoActive()
    public int getBleepCount() / int getVisibleCharCount() / boolean isTypewriterFinished()
    public Rect2f getDialogueBoxBounds() / TextWidget getSpeakerNameWidget() / TextWidget getDialogueTextWidget() / HBox getQuickMenuRow()
    public void setAudioEngine(AudioEngine audioEngine) / void setTtsService(TextToSpeechService ttsService)
    public void syncFromRuntime(boolean force)
}

public class NvlDialogueScreen extends Screen {
    public static final String SCREEN_NAME = "nvl_dialogue";
    public NvlDialogueScreen(ScriptSession session)
    public void handlePrimaryAdvance()
    public void syncFromRuntime(boolean force)
    public List<String> getEntries() / int getEntryCount() / boolean isLatestTypewriterFinished()
    public Viewport getViewport() / VBox getParagraphContainer() / List<TextWidget> getParagraphWidgets()
}

public class BubbleDialogueScreen extends Screen {
    public static final String SCREEN_NAME = "bubble_dialogue";
    public static final String PRESETS_FILE_NAME = "bubble_layouts.json";
    public static final float DEFAULT_BOX_W = 540.0f, DEFAULT_BOX_H = 160.0f;

    public record BubbleLayoutConfig(float boxX, float boxY, float boxW, float boxH, float tailX, float tailY) {
        public boolean containsBox(float lx, float ly)
        public boolean isNearTailTip(float lx, float ly, float radius)
    }
    public record BubbleEntry(String lineId, String speakerId, String speakerName, String speakerColorHex, String text, BubbleLayoutConfig layout) {}

    public BubbleDialogueScreen(ScriptSession session)
    public boolean toggleEditorMode() / void setEditorMode(boolean active) / boolean isEditorActive()
    public void setRetainPreviousBubbles(boolean retainPreviousBubbles) / boolean isRetainPreviousBubbles() / void clearRetainedBubbles()
    public void setLayoutPreset(String key, BubbleLayoutConfig config) / BubbleLayoutConfig getLayoutPreset(String key) / Map<String, BubbleLayoutConfig> getLayoutPresets()
    public BubbleEntry getActiveBubble() / List<BubbleEntry> getRetainedBubbles() / int getVisibleBubbleCount()
    public void handlePrimaryAdvance() / void syncFromRuntime(boolean force)
    public BubbleLayoutConfig resolveLayoutForPrompt(ScriptRuntime.DialoguePrompt prompt)
    public Vec2f resolveSpeakerAnchor(String speakerId)
    public void savePresetsToDisk()
}

public class QuickMenuScreen extends Screen {
    public static final String SCREEN_NAME = "quick_menu";
    public static final int Z_ORDER = SceneLayer.Z_SCREENS + 10;
    public QuickMenuScreen(ScriptSession session, ScreenManager screenManager)
    public HBox getBarBox() / TextButton getBackButton() / getHistoryButton() / getSkipButton() / getAutoButton() / getSaveButton() / getQuickSaveButton() / getQuickLoadButton() / getPrefsButton()
}

public class ChoiceMenuScreen extends Screen {
    public static final String SCREEN_NAME = "choice_menu";
    public ChoiceMenuScreen(ScriptSession session)
    public void syncFromRuntime(boolean forceRebuild)
    public List<TextButton> getOptionButtons() / Bar getTimeoutBar() / TextWidget getCaptionLabel()
}

public class InputOverlayScreen extends Screen {
    public static final String SCREEN_NAME = "input_overlay";
    public InputOverlayScreen(ScriptSession session)
    public void syncFromRuntime(boolean force)
    public void submitCurrentText(String text)
    public TextWidget getPromptLabel() / TextInput getTextInput() / TextButton getConfirmButton()
}

public class MainMenuScreen extends Screen {
    public static final String SCREEN_NAME = "main_menu";
    public MainMenuScreen(ScriptSession session, ScreenManager screenManager)
    public void refreshContinueState()
    public String getNewestSaveSlotId() / TextWidget getTitleBanner()
    public TextButton getStartButton() / getContinueButton() / getLoadButton() / getGalleryButton() / getMusicRoomButton() / getEndingsButton() / getAchievementsButton() / getPreferencesButton() / getControlsButton() / getAboutButton() / getQuitButton()
}

public class SaveLoadScreen extends Screen {
    public static final String SCREEN_NAME = "save_load";
    public static final int SLOTS_PER_PAGE = 6;
    public static final int TOTAL_PAGES = 5;
    public enum Mode { SAVE, LOAD }
    public enum FilterTab { MANUAL, QUICK, AUTO }
    public static final class SlotCardView {
        public String getSlotId() / Frame getCardNode() / TextWidget getTitleLabel() / getMetaLabel() / getPreviewTextLabel() / TextButton getActionButton() / getDeleteButton() / boolean isOccupied() / boolean hasThumbnail() / int getThumbnailWidth() / int getThumbnailHeight()
    }

    public SaveLoadScreen(ScriptSession session, ScreenManager screenManager)
    public void setMode(Mode mode) / Mode getMode()
    public void setFilterTab(FilterTab tab) / FilterTab getFilterTab()
    public void setPage(int pageIndex) / int getCurrentPage()
    public void refreshSlots()
    public void handleSlotAction(String slotId) / void handleSlotDelete(String slotId)
    public List<SlotCardView> getVisibleCards() / TextInput getCustomNameInput() / List<TextButton> getPageButtons()
}

public class PreferencesScreen extends Screen {
    public static final String SCREEN_NAME = "preferences";
    public PreferencesScreen(ScriptSession session, ScreenManager screenManager)
    public void refreshFromPreferences()
    public void cycleDialogueMode() / void cycleLanguage() / void selectLanguage(String language) / void toggleRenderMode() / void savePreferences()
    public void setFontManager(FontManager fontManager)
    public Slider getMasterSlider() / getMusicSlider() / getSfxSlider() / getVoiceSlider() / getAmbientSlider() / getTextSpeedSlider() / getAutoDelaySlider() / getUiScaleSlider()
    public TextButton getFullscreenToggle() / getSkipUnreadToggle() / getSkipAfterChoicesToggle() / getDialogueModeButton() / getLanguageButton() / getRenderModeButton() / getSelfVoicingToggle() / getDyslexiaFontToggle()
}

public class ControlsScreen extends Screen {
    public static final String SCREEN_NAME = "controls";
    public enum Category { ALL, KEYBOARD, MOUSE, GAMEPAD }
    public record BindingEntry(Category category, String input, String action) {}

    public ControlsScreen(ScreenManager screenManager)
    public void setCategory(Category category) / Category getActiveCategory()
    public List<BindingEntry> getVisibleBindings()
}

public class AboutScreen extends Screen {
    public static final String SCREEN_NAME = "about";
    public AboutScreen(ScriptSession session, ScreenManager screenManager)
    public void refreshInfo()
    public TextWidget getProjectTitleValue() / getProjectVersionValue() / getAuthorValue() / getEngineVersionValue() / getBackendValue() / getRenderModeValue() / getLicenseSummary()
}

public class HistoryLogScreen extends Screen {
    public static final String SCREEN_NAME = "history_log";
    public static final class HistoryRowView {
        public HistoryLog.HistoryEntry getEntry() / TextWidget getSpeakerLabel() / getTextLabel() / TextButton getReplayVoiceButton() / getJumpBackButton()
    }

    public HistoryLogScreen(ScriptSession session, ScreenManager screenManager)
    public HistoryLogScreen(ScriptSession session, ScreenManager screenManager, AudioEngine audioEngine)
    public void refreshEntries() / void replayVoice(String voicePath) / boolean jumpBackTo(HistoryLog.HistoryEntry entry)
    public void setAudioEngine(AudioEngine audioEngine)
    public List<HistoryRowView> getRowViews() / Viewport getViewport()
}

public class GalleryScreen extends Screen {
    public static final String SCREEN_NAME = "gallery";
    public static final class GalleryCardView {
        public GalleryManager.GalleryEntry getEntry() / boolean isUnlocked() / TextButton getSelectButton() / String getDisplayedAssetPath()
    }

    public GalleryScreen(ScriptSession session, ScreenManager screenManager)
    public void refreshGallery() / void selectEntry(GalleryManager.GalleryEntry entry) / void stepVariation(int delta)
    public void toggleZoom() / void setZoomed(boolean zoomed) / boolean isZoomed()
    public List<GalleryCardView> getCardViews() / GalleryManager.GalleryEntry getSelectedEntry() / int getVariationIndex() / String getActiveVariationPath()
}

public class MusicRoomScreen extends Screen {
    public static final String SCREEN_NAME = "music_room";
    public static final int SPECTRUM_BAND_COUNT = 16;

    public MusicRoomScreen(ScriptSession session, ScreenManager screenManager)
    public MusicRoomScreen(ScriptSession session, ScreenManager screenManager, AudioEngine audioEngine)
    public void registerTrackTitle(String trackId, String displayName)
    public void refreshTracks() / void playTrack(String trackId) / void stopPlayback()
    public void setAudioEngine(AudioEngine audioEngine)
    public float[] getSpectrumBands() / String getCurrentTrackId() / TextWidget getNowPlayingLabel() / TextButton getStopButton() / List<TextButton> getTrackButtons() / List<VBar> getSpectrumBars()
}

public class EndingsTreeScreen extends Screen {
    public static final String SCREEN_NAME = "endings_tree";
    public record TreeNodeView(EndingsManager.EndingDef ending, int depth, boolean unlocked, TextWidget titleLabel, TextWidget statusLabel) {}

    public EndingsTreeScreen(ScriptSession session, ScreenManager screenManager)
    public void refreshTree()
    public List<TreeNodeView> getNodeViews() / Bar getCompletionBar() / TextWidget getCompletionLabel()
}

public class AchievementsScreen extends Screen {
    public static final String SCREEN_NAME = "achievements";
    public record AchievementRowView(AchievementManager.AchievementDef definition, boolean unlocked, TextWidget titleLabel, TextWidget descriptionLabel, TextWidget statusLabel) {}

    public AchievementsScreen(ScriptSession session, ScreenManager screenManager)
    public void refreshAchievements()
    public List<AchievementRowView> getRows() / TextWidget getSummaryLabel() / Bar getProgressBar()
}

public class NotificationToastOverlay extends Screen {
    public static final String SCREEN_NAME = "notification_toast";
    public static final double DEFAULT_TOAST_DURATION = 4.0;
    public record ToastPayload(String title, String subtitle) {}

    public NotificationToastOverlay(ScriptSession session, ScreenManager screenManager)
    public void pushToast(String title, String subtitle)
    public void setSkipPillActive(boolean active) / void setAutoPillActive(boolean active)
    public boolean isToastVisible() / ToastPayload getActiveToast() / int getPendingToastCount() / Frame getToastCard() / Frame getStatusPill() / TextWidget getStatusPillText()
}

public final class GameRuntimeCoordinator implements GameHost {
    public GameRuntimeCoordinator()
    public GameRuntimeCoordinator(boolean autoStartMainMenu)
    @Override public void init(EngineApplication app, boolean glEnabled)
    @Override public void update(double dt, InputManager input)
    @Override public void renderUi(double elapsedSeconds)
    @Override public void onReload()
    @Override public void dispose()

    public ScriptSession getSession()
    public ScreenManager getScreenManager()
    public AudioEngine getAudioEngine()
    public FontManager getFontManager()
    public TextureManager getTextureManager()
    public AnimationEngine getAnimationEngine()
    public TransitionManager getTransitionManager()
    public LipSyncAnalyzer getLipSyncAnalyzer()
    public TextToSpeechService getTtsService()
    public MainMenuScreen getMainMenuScreen()
    public AdvDialogueScreen getAdvScreen()
    public NvlDialogueScreen getNvlScreen()
    public BubbleDialogueScreen getBubbleScreen()
    public ChoiceMenuScreen getChoiceMenuScreen()
    public PreferencesScreen getPreferencesScreen()
    public NotificationToastOverlay getToastOverlay()
}
```

### Erweiterte Wave P2-5 UI-APIs
- `ScreenManager`: `pushSubScreen`, `popSubScreen`, `getBreadcrumbDepth`, `replaceScreen`, `resetFocusForReload`, `setFocusNarrator`, `setSoundCueListener` sowie automatisches Modal-Focus-Save/Restore.
- `ChoiceMenuScreen`: `setNumericHotkeysEnabled`, `handleNumericHotkey`, `setDisabledReason`, `setChoiceBadge`, `bindChoiceHistory`, roter `<25%`-Timeout-Indikator.
- `HistoryLogScreen`: `formatCopyableEntry(HistoryLog.HistoryEntry)`.
- `DragGroup`: `cancelActiveDrag()` bei Rechtsklick/Outside-Drop.
- `TextButton`: `setReduceMotion`, `isReduceMotion`, `getHoverScale`, `updateHoverAnimation`.
- `ThemePalette`: `resolveProjectAccentHex(ProjectDescriptor)`.
- `NotificationToastOverlay`: `StackedToast`, `pushStackedToast`, `getVisibleStackedToasts`.
