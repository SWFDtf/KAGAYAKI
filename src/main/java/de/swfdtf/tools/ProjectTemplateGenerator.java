package de.swfdtf.tools;

import de.swfdtf.audio.AudioDecoder;
import de.swfdtf.core.EngineVersion;
import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.graphics.TextureManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Generates complete, lint-clean KAGAYAKI starter projects with {@code project.vjava.json},
 * {@code .vjava} scripts (including embedded Java classes, {@code layeredimage} with lip-sync
 * mouth groups, branching choices, UI screen, and English translation), plus procedural PNG
 * and WAV assets generated without {@code java.awt.*} or {@code javax.imageio.*}.
 */
public final class ProjectTemplateGenerator {

    private static final String DEFAULT_TITLE = "KAGAYAKI Starter";
    private static final String DEFAULT_RENDER_MODE = "2D";
    private static final int BG_WIDTH = 640;
    private static final int BG_HEIGHT = 360;
    private static final int CHAR_WIDTH = 256;
    private static final int CHAR_HEIGHT = 384;
    private static final int SPRITE_SIZE = 96;
    private static final int UI_FRAME_WIDTH = 256;
    private static final int UI_FRAME_HEIGHT = 96;
    private static final int AUDIO_SAMPLE_RATE = 22_050;

    /**
     * Available project scaffolding templates offered in Studio and CLI generators.
     */
    public enum TemplateType {
        /** Full playable demo template with Main Menu, Save/Load screens, branching story, and WASD arena. */
        FULL_DEMO,
        /** Classic compact visual-novel starter template (3 labels, layeredimage, HUD, EN translation). */
        STARTER,
        /** Lightweight minimal project skeleton with entry label and HUD badge. */
        MINIMAL,
        /** Walkable 2D action-adventure template with platforms, NPCs, dialog zones, and key puzzle. */
        ACTION_ADVENTURE_2D,
        /** Hybrid 3D exploration template with 3D hall/pillar meshes, WASD3D camera, fog, and 2D backdrop fallback. */
        HYBRID_3D_EXPLORATION
    }

    private ProjectTemplateGenerator() {
    }

    /**
     * Generates a starter KAGAYAKI project in {@code targetDir} using a default title, {@code "2D"} render mode,
     * and {@link TemplateType#STARTER}.
     *
     * @param targetDir destination project root directory
     * @return normalized path to the generated project root directory
     * @throws IOException if writing project files or assets fails
     */
    public static Path generate(Path targetDir) throws IOException {
        Objects.requireNonNull(targetDir, "targetDir must not be null");
        Path fileName = targetDir.getFileName();
        String inferredTitle = (fileName != null && !fileName.toString().isBlank())
                ? fileName.toString()
                : DEFAULT_TITLE;
        return generate(targetDir, inferredTitle, DEFAULT_RENDER_MODE, TemplateType.STARTER);
    }

    /**
     * Generates a starter KAGAYAKI project in {@code targetDir} with the specified title and render mode,
     * defaulting to {@link TemplateType#STARTER}.
     *
     * @param targetDir  destination project root directory
     * @param title      project display title (falls back to default if null or blank)
     * @param renderMode render backend mode ({@code "2D"} or {@code "3D"})
     * @return normalized path to the generated project root directory
     * @throws IOException if writing project files or assets fails
     */
    public static Path generate(Path targetDir, String title, String renderMode) throws IOException {
        return generate(targetDir, title, renderMode, TemplateType.STARTER);
    }

    /**
     * Generates a KAGAYAKI project in {@code targetDir} using the requested {@link TemplateType}.
     *
     * @param targetDir    destination project root directory
     * @param title        project display title (falls back to default if null or blank)
     * @param renderMode   render backend mode ({@code "2D"} or {@code "3D"})
     * @param templateType scaffolding preset ({@link TemplateType#FULL_DEMO}, {@link TemplateType#STARTER},
     *                     or {@link TemplateType#MINIMAL})
     * @return normalized path to the generated project root directory
     * @throws IOException if writing project files or assets fails
     */
    public static Path generate(
            Path targetDir,
            String title,
            String renderMode,
            TemplateType templateType
    ) throws IOException {
        return generate(targetDir, title, null, renderMode, templateType);
    }

    /**
     * Generates a KAGAYAKI project in {@code targetDir} with custom author metadata using the
     * requested {@link TemplateType}.
     *
     * @param targetDir    destination project root directory
     * @param title        project display title (falls back to default if null or blank)
     * @param author       project author metadata (falls back to Studio default if null or blank)
     * @param renderMode   render backend mode ({@code "2D"} or {@code "3D"})
     * @param templateType scaffolding preset ({@link TemplateType#FULL_DEMO}, {@link TemplateType#STARTER},
     *                     or {@link TemplateType#MINIMAL})
     * @return normalized path to the generated project root directory
     * @throws IOException if writing project files or assets fails
     */
    public static Path generate(
            Path targetDir,
            String title,
            String author,
            String renderMode,
            TemplateType templateType
    ) throws IOException {
        Objects.requireNonNull(targetDir, "targetDir must not be null");
        TemplateType effectiveType = templateType != null ? templateType : TemplateType.STARTER;
        Path root = targetDir.toAbsolutePath().normalize();
        Files.createDirectories(root);

        String effectiveTitle = (title == null || title.isBlank()) ? DEFAULT_TITLE : title.trim();
        String effectiveAuthor = (author == null || author.isBlank())
                ? "KAGAYAKI Studio (" + EngineVersion.VERSION + ")"
                : author.trim();
        ProjectDescriptor descriptor = ProjectDescriptor.createDefault(effectiveTitle);
        if (renderMode != null && !renderMode.isBlank()) {
            descriptor.setRenderMode(renderMode);
        }
        if (effectiveType == TemplateType.HYBRID_3D_EXPLORATION) {
            descriptor.setRenderMode("3D");
        }
        descriptor.setAuthor(effectiveAuthor);
        descriptor.setVersion(EngineVersion.VERSION);
        descriptor.normalizeDefaults();
        ProjectDescriptor.save(root, descriptor);

        switch (effectiveType) {
            case MINIMAL -> {
                writeUtf8(root.resolve("main.vjava"), buildMinimalMainScript());
                writeUtf8(root.resolve("ui/screens.vjava"), buildMinimalScreensScript(effectiveTitle));
                generateMinimalImages(root);
                generateMinimalAudio(root);
            }
            case STARTER -> {
                writeUtf8(root.resolve("main.vjava"), buildMainScript(false));
                writeUtf8(root.resolve("ui/screens.vjava"), buildStarterScreensScript());
                writeUtf8(root.resolve("tl/english/translations.vjava"), buildEnglishTranslationScript(false));
                generateProceduralImages(root, false);
                generateProceduralAudio(root, false);
            }
            case FULL_DEMO -> {
                writeUtf8(root.resolve("main.vjava"), buildMainScript(true));
                writeUtf8(root.resolve("ui/screens.vjava"), buildScreensScript(effectiveTitle));
                writeUtf8(root.resolve("tl/english/translations.vjava"), buildEnglishTranslationScript(true));
                generateProceduralImages(root, true);
                generateProceduralAudio(root, true);
            }
            case ACTION_ADVENTURE_2D -> {
                writeUtf8(root.resolve("main.vjava"), buildActionAdventureMainScript());
                writeUtf8(root.resolve("ui/screens.vjava"), buildActionAdventureScreensScript(effectiveTitle));
                writeUtf8(root.resolve("tl/english/translations.vjava"), buildActionAdventureEnglishScript());
                generateActionAdventureImages(root);
                generateActionAdventureAudio(root);
            }
            case HYBRID_3D_EXPLORATION -> {
                writeUtf8(root.resolve("main.vjava"), buildHybrid3dMainScript());
                writeUtf8(root.resolve("ui/screens.vjava"), buildHybrid3dScreensScript(effectiveTitle));
                writeUtf8(root.resolve("tl/english/translations.vjava"), buildHybrid3dEnglishScript());
                generateHybrid3dAssets(root);
            }
        }

        return root;
    }

    private static void writeUtf8(Path file, String content) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String buildMainScript(boolean includeDemoArena) {
        String arenaImageDefs = includeDemoArena ? """
                image player = "assets/images/sprites/player.png"
                image goal_star = "assets/images/sprites/goal_star.png"
                """ : "";
        String arenaMenuChoice = includeDemoArena
                ? "        \"Interaktive WASD- & Physik-Arena testen.\" -> jump wasd_arena\n"
                : "";
        String arenaLabels = includeDemoArena ? """

                label wasd_arena:
                    scene bg_akademie_nacht with fade(0.3)
                    show player
                    show goal_star
                    move player to 200 780
                    move goal_star to 1480 780 duration 0.4 ease ease_out
                    move_by goal_star 0 -20 duration 0.4 ease ease_in_out
                    wasd_init player speed 280 bounds 80 120 1840 940 diagonal_norm true
                    physics_init player gravity 900 jump_force 440 ground_y 780
                    on_collide player goal_star jump arena_victory
                    lena "Bewege Kaito mit WASD / Pfeiltasten und springe mit Leertaste bis zum Stern!"
                    return

                label arena_victory:
                    wasd_stop player
                    physics_stop player
                    play sound "assets/audio/chime.wav"
                    show lena happy at center with dissolve(0.2)
                    lena "Geschafft! Du hast das Ziel in der interaktiven WASD-Arena erreicht."
                    stop music fadeout 1.0
                    return
                """ : "";
        return """
                // Datei: main.vjava
                define lena = Character("Lena", color="#58a6ff", image="lena", voice_tag="lena")
                define kaito = Character("Kaito", color="#f0883e")

                :: Kode für VN: Definiere eigene Java-Klassen & Unterklassen für das Spielsystem @->{
                    public static class InventarItem {
                        public final String name;
                        public final int wert;

                        public InventarItem(String name, int wert) {
                            this.name = name;
                            this.wert = wert;
                        }
                    }

                    public static class SpezialGegenstand extends InventarItem {
                        public final String effekt;

                        public SpezialGegenstand(String name, int wert, String effekt) {
                            super(name, wert);
                            this.effekt = effekt;
                        }
                    }

                    public static class DialogHelfer {
                        public static int berechneBonus(int basis, boolean hatArtefakt) {
                            return hatArtefakt ? basis + 25 : basis;
                        }
                    }
                }<<

                image bg_akademie_nacht = "assets/images/bg/bg_akademie_nacht.png"
                image bg_bibliothek = "assets/images/bg/bg_bibliothek.png"
                image ui_dialog_frame = "assets/images/ui/dialog_frame.png"
                %s
                layeredimage lena:
                    always:
                        "assets/images/char/lena_base.png"
                    group outfit:
                        attribute uniform default:
                            "assets/images/char/lena_uniform.png"
                        attribute casual:
                            "assets/images/char/lena_casual.png"
                    group face:
                        attribute neutral default:
                            "assets/images/char/lena_face_neutral.png"
                        attribute happy:
                            "assets/images/char/lena_face_happy.png"
                        attribute entschlossen:
                            "assets/images/char/lena_face_determined.png"
                    group mouth:
                        attribute closed default:
                            "assets/images/char/lena_mouth_closed.png"
                        attribute half:
                            "assets/images/char/lena_mouth_half.png"
                        attribute open:
                            "assets/images/char/lena_mouth_open.png"

                label start:
                    scene bg_akademie_nacht with dissolve(0.8)
                    play music "assets/audio/nachtwind.wav" fadein 2.0

                    :: Kode für VN: Initialisiere Spielerdaten mit den eigenen Java-Klassen @->{
                        SpezialGegenstand amulett = new SpezialGegenstand("Sternenkompass", 100, "Nachtsicht");
                        state.set("spieler_name", "Kaito");
                        state.set("aktives_item", amulett);
                        state.set("mut_punkte", DialogHelfer.berechneBonus(10, true));
                        engine.logInfo("Startzustand mit SpezialGegenstand '" + amulett.name + "' geladen.");
                    }<<

                    show lena neutral at center with easein(0.4)
                    voice "assets/audio/voice/lena_01.wav"
                    lena "Willkommen in der ersten Beispielszene, [spieler_name].{w=0.4} \
                Bereit für den Übergang in Dialog 2?"
                    kaito "Der Sternenkompass zeigt direkt hinüber zur Bibliothek."

                    menu:
                        "Ja, direkt weiter zu Dialog 2!" if mut_punkte >= 20 -> jump dialog_zwei
                        "Erst noch die Umgebung untersuchen." -> jump umgebung_pruefen
                %s
                label umgebung_pruefen:
                    show lena happy with dissolve(0.2)
                    lena "Gute Idee! Dein Sternenkompass leuchtet im Dunkeln." with vpunch
                    jump dialog_zwei

                label dialog_zwei:
                    scene bg_bibliothek with fade(0.5)
                    show lena entschlossen at left with easein(0.3)
                    lena "Das hier ist der zweite Beispieldialog der Vorlage. \
                Du kannst jetzt speichern, laden oder zurückspulen!"
                    stop music fadeout 1.5
                    return
                %s""".formatted(arenaImageDefs, arenaMenuChoice, arenaLabels);
    }

    private static String buildMinimalMainScript() {
        return """
                // Datei: main.vjava (Minimal-Vorlage)
                define narrator = Character("KAGAYAKI", color="#58a6ff")

                image bg_akademie_nacht = "assets/images/bg/bg_akademie_nacht.png"

                label start:
                    scene bg_akademie_nacht with fade(0.4)
                    play music "assets/audio/nachtwind.wav" fadein 1.0
                    narrator "Minimal-Projekt bereit. Ergänze weitere Labels, Sprites und UI-Screens."
                    stop music fadeout 1.0
                    return
                """;
    }

    private static String buildActionAdventureMainScript() {
        return """
                // Datei: main.vjava (Action-Adventure 2D Vorlage)
                define hero = Character("Aria", color="#58a6ff")
                define guardian = Character("Wächter der Ruine", color="#f0883e")

                default has_vault_key = false
                default room_name = "Ruinenhalle"
                default quest_done = false

                image bg_ruins = "assets/images/bg/bg_ruins.png"
                image bg_vault = "assets/images/bg/bg_vault.png"
                image player = "assets/images/sprites/player.png"
                image npc_guardian = "assets/images/sprites/npc_guardian.png"
                image golden_key = "assets/images/sprites/golden_key.png"
                image vault_gate = "assets/images/sprites/vault_gate.png"
                image platform_stone = "assets/images/sprites/platform_stone.png"

                label start:
                    jump hall_room

                label hall_room:
                    $ room_name = "Ruinenhalle"
                    scene bg_ruins with dissolve(0.5)
                    play music "assets/audio/quest_theme.wav" fadein 1.0
                    show player
                    show platform_stone
                    show npc_guardian
                    show golden_key
                    show vault_gate
                    move player to 240 780
                    move platform_stone to 780 650 duration 0.4 ease ease_out
                    move golden_key to 820 560 duration 0.4 ease ease_in_out
                    move npc_guardian to 540 780 duration 0.3 ease ease_out
                    move vault_gate to 1560 760 duration 0.3 ease ease_out
                    wasd_init player speed 280 bounds 80 120 1840 940 diagonal_norm true
                    physics_init player gravity 900 jump_force 440 ground_y 780
                    on_collide player golden_key jump key_collected
                    on_collide player npc_guardian jump npc_talk
                    on_collide player vault_gate jump vault_door
                    hero "Finde den goldenen Schlüssel auf der Plattform, sprich mit dem Wächter und öffne das Tor!"
                    return

                label key_collected:
                    $ has_vault_key = true
                    hide golden_key with dissolve(0.2)
                    play sound "assets/audio/key_chime.wav"
                    hero "Der goldene Schlüssel gehört mir! Jetzt weiter zum Schatzgewölbe-Tor."
                    jump vault_door

                label npc_talk:
                    guardian "Die Plattform führt dich hoch zum alten Schlüssel. Ohne ihn bleibt das Tor verschlossen."
                    jump vault_door

                label vault_door:
                    if has_vault_key:
                        jump quest_complete
                    else:
                        hero "Das Tor ist verschlossen! Hole zuerst den Schlüssel."
                        return

                label quest_complete:
                    $ quest_done = true
                    $ room_name = "Schatzkammer"
                    wasd_stop player
                    physics_stop player
                    hide platform_stone with fade(0.3)
                    hide npc_guardian with fade(0.3)
                    hide vault_gate with fade(0.3)
                    scene bg_vault with fade(0.6)
                    play sound "assets/audio/key_chime.wav"
                    hero "Das Tor der Schatzkammer steht offen! Rätsel der Ruine gelöst."
                    stop music fadeout 1.0
                    return
                """;
    }

    private static String buildActionAdventureScreensScript(String projectTitle) {
        String escapedTitle = projectTitle.replace("\"", "'");
        return """
                // Datei: ui/screens.vjava (Action-Adventure 2D)
                screen action_hud():
                    zorder 50
                    frame:
                        xalign 0.02
                        yalign 0.02
                        background "assets/images/ui/dialog_frame.png"
                        vbox:
                            spacing 6
                            text "%s" size 20
                            text "Raum: [room_name] | Schlüssel: [has_vault_key]" size 16
                """.formatted(escapedTitle);
    }

    private static String buildActionAdventureEnglishScript() {
        return """
                // Datei: tl/english/translations.vjava (Action-Adventure 2D)
                translate english strings:
                    old "Finde den goldenen Schlüssel auf der Plattform, sprich mit dem Wächter und öffne das Tor!"
                    new "Find the golden key on the platform, speak with the guardian, and open the gate!"
                    old "Der goldene Schlüssel gehört mir! Jetzt weiter zum Schatzgewölbe-Tor."
                    new "I got the golden key! Now on to the vault gate."
                    old "Die Plattform führt dich hoch zum alten Schlüssel. Ohne ihn bleibt das Tor verschlossen."
                    new "The platform leads up to the ancient key. Without it the gate remains locked."
                    old "Das Tor ist verschlossen! Hole zuerst den Schlüssel."
                    new "The gate is locked! Collect the key first."
                    old "Das Tor der Schatzkammer steht offen! Rätsel der Ruine gelöst."
                    new "The vault gate stands open! Ruins puzzle solved."
                """;
    }

    private static String buildScreensScript(String projectTitle) {
        String escapedTitle = projectTitle.replace("\"", "'");
        return """
                // Datei: ui/screens.vjava
                screen hud_badge():
                    zorder 50
                    frame:
                        xalign 0.02
                        yalign 0.02
                        background "assets/images/ui/dialog_frame.png"
                        vbox:
                            spacing 6
                            text "Mut: [mut_punkte]" size 20
                            text "Spieler: [spieler_name]" size 16

                screen template_main_menu():
                    modal true
                    zorder 410
                    frame:
                        xalign 0.5
                        yalign 0.5
                        background "assets/images/ui/dialog_frame.png"
                        vbox:
                            spacing 10
                            text "%s" size 34
                            text "%s" size 18
                            textbutton "Neues Spiel" action "Start()"
                            textbutton "Spiel laden (Save/Load)" action "ShowMenu(\\"load_screen\\")"
                            textbutton "WASD- & Physik-Arena" action "Jump(\\"wasd_arena\\")"
                            textbutton "Einstellungen" action "ShowMenu(\\"preferences_screen\\")"
                            textbutton "Beenden" action "Quit()"

                screen template_save_screen():
                    modal true
                    zorder 420
                    frame:
                        xalign 0.5
                        yalign 0.5
                        background "assets/images/ui/dialog_frame.png"
                        vbox:
                            spacing 8
                            text "Spielstand speichern" size 28
                            textbutton "Schnellspeichern (Quick Slot)" action "QuickSave()"
                            text "Slots 1 - 6 stehen über das Save/Load-Menü bereit." size 16
                            textbutton "Zurück" action "Return()"

                screen template_load_screen():
                    modal true
                    zorder 420
                    frame:
                        xalign 0.5
                        yalign 0.5
                        background "assets/images/ui/dialog_frame.png"
                        vbox:
                            spacing 8
                            text "Spielstand laden" size 28
                            textbutton "Schnellladen (Quick Slot)" action "QuickLoad()"
                            text "Slots 1 - 6 stehen über das Save/Load-Menü bereit." size 16
                            textbutton "Zurück" action "Return()"

                screen template_preferences_screen():
                    modal true
                    zorder 420
                    frame:
                        xalign 0.5
                        yalign 0.5
                        background "assets/images/ui/dialog_frame.png"
                        vbox:
                            spacing 8
                            text "Einstellungen (Audio & Text)" size 28
                            text "Textgeschwindigkeit, Musik, SFX und Sprache im Optionsmenü konfigurierbar." size 16
                            textbutton "Zurück" action "Return()"
                """.formatted(escapedTitle, EngineVersion.formatBanner());
    }

    private static String buildStarterScreensScript() {
        return """
                // Datei: ui/screens.vjava
                screen hud_badge():
                    zorder 50
                    frame:
                        xalign 0.02
                        yalign 0.02
                        background "assets/images/ui/dialog_frame.png"
                        vbox:
                            spacing 6
                            text "Mut: [mut_punkte]" size 20
                            text "Spieler: [spieler_name]" size 16
                """;
    }

    private static String buildMinimalScreensScript(String projectTitle) {
        String escapedTitle = projectTitle.replace("\"", "'");
        return """
                // Datei: ui/screens.vjava (Minimal-Vorlage)
                screen hud_badge():
                    zorder 50
                    frame:
                        xalign 0.02
                        yalign 0.02
                        background "assets/images/ui/dialog_frame.png"
                        vbox:
                            spacing 6
                            text "%s" size 20
                            text "%s" size 14
                """.formatted(escapedTitle, EngineVersion.formatBanner());
    }

    private static String buildEnglishTranslationScript(boolean includeDemoArena) {
        String arenaTranslations = includeDemoArena ? """
                    old "Interaktive WASD- & Physik-Arena testen."
                    new "Test interactive WASD & physics arena."
                """ : "";
        String arenaDialogue = includeDemoArena ? """

                translate english arena_01:
                    # lena "Bewege Kaito mit WASD / Pfeiltasten und springe mit Leertaste bis zum Stern!"
                    lena "Move Kaito with WASD / Arrow keys and jump with Space to reach the star!"
                """ : "";
        return """
                // Datei: tl/english/translations.vjava
                translate english strings:
                    old "Ja, direkt weiter zu Dialog 2!"
                    new "Yes, continue straight to Dialogue 2!"
                    old "Erst noch die Umgebung untersuchen."
                    new "Inspect the surroundings first."
                %s
                translate english start_01:
                    # lena "Willkommen in der ersten Beispielszene, [spieler_name].{w=0.4} \
                Bereit für den Übergang in Dialog 2?"
                    lena "Welcome to the first sample scene, [spieler_name].{w=0.4} Ready to proceed to Dialogue 2?"
                %s""".formatted(arenaTranslations, arenaDialogue);
    }

    private static void generateMinimalImages(Path root) {
        TextureManager.writePng(
                root.resolve("assets/images/bg/bg_akademie_nacht.png"),
                BG_WIDTH,
                BG_HEIGHT,
                renderNightAcademyBg(BG_WIDTH, BG_HEIGHT)
        );
        TextureManager.writePng(
                root.resolve("assets/images/ui/dialog_frame.png"),
                UI_FRAME_WIDTH,
                UI_FRAME_HEIGHT,
                renderDialogFrame(UI_FRAME_WIDTH, UI_FRAME_HEIGHT)
        );
    }

    private static void generateMinimalAudio(Path root) throws IOException {
        AudioDecoder.DecodedAudio drone = AudioDecoder.synthesizeTone(
                220.0,
                0.8,
                AUDIO_SAMPLE_RATE,
                0.22f
        );
        byte[] nachtwindWav = AudioDecoder.encodeWav(drone.pcm16(), drone.channels(), drone.sampleRate());
        Path nachtwindPath = root.resolve("assets/audio/nachtwind.wav");
        Files.createDirectories(nachtwindPath.getParent());
        Files.write(nachtwindPath, nachtwindWav);
    }

    private static void generateProceduralImages(Path root, boolean includeDemoArena) {
        TextureManager.writePng(
                root.resolve("assets/images/bg/bg_akademie_nacht.png"),
                BG_WIDTH,
                BG_HEIGHT,
                renderNightAcademyBg(BG_WIDTH, BG_HEIGHT)
        );
        TextureManager.writePng(
                root.resolve("assets/images/bg/bg_bibliothek.png"),
                BG_WIDTH,
                BG_HEIGHT,
                renderLibraryBg(BG_WIDTH, BG_HEIGHT)
        );

        TextureManager.writePng(
                root.resolve("assets/images/char/lena_base.png"),
                CHAR_WIDTH,
                CHAR_HEIGHT,
                renderLenaBase(CHAR_WIDTH, CHAR_HEIGHT)
        );
        TextureManager.writePng(
                root.resolve("assets/images/char/lena_uniform.png"),
                CHAR_WIDTH,
                CHAR_HEIGHT,
                renderLenaOutfit(CHAR_WIDTH, CHAR_HEIGHT, 30, 43, 72, 88, 166, 255)
        );
        TextureManager.writePng(
                root.resolve("assets/images/char/lena_casual.png"),
                CHAR_WIDTH,
                CHAR_HEIGHT,
                renderLenaOutfit(CHAR_WIDTH, CHAR_HEIGHT, 44, 110, 122, 240, 136, 62)
        );

        TextureManager.writePng(
                root.resolve("assets/images/char/lena_face_neutral.png"),
                CHAR_WIDTH,
                CHAR_HEIGHT,
                renderLenaFace(CHAR_WIDTH, CHAR_HEIGHT, FaceMood.NEUTRAL)
        );
        TextureManager.writePng(
                root.resolve("assets/images/char/lena_face_happy.png"),
                CHAR_WIDTH,
                CHAR_HEIGHT,
                renderLenaFace(CHAR_WIDTH, CHAR_HEIGHT, FaceMood.HAPPY)
        );
        TextureManager.writePng(
                root.resolve("assets/images/char/lena_face_determined.png"),
                CHAR_WIDTH,
                CHAR_HEIGHT,
                renderLenaFace(CHAR_WIDTH, CHAR_HEIGHT, FaceMood.DETERMINED)
        );

        TextureManager.writePng(
                root.resolve("assets/images/char/lena_mouth_closed.png"),
                CHAR_WIDTH,
                CHAR_HEIGHT,
                renderLenaMouth(CHAR_WIDTH, CHAR_HEIGHT, 2)
        );
        TextureManager.writePng(
                root.resolve("assets/images/char/lena_mouth_half.png"),
                CHAR_WIDTH,
                CHAR_HEIGHT,
                renderLenaMouth(CHAR_WIDTH, CHAR_HEIGHT, 6)
        );
        TextureManager.writePng(
                root.resolve("assets/images/char/lena_mouth_open.png"),
                CHAR_WIDTH,
                CHAR_HEIGHT,
                renderLenaMouth(CHAR_WIDTH, CHAR_HEIGHT, 11)
        );

        TextureManager.writePng(
                root.resolve("assets/images/ui/dialog_frame.png"),
                UI_FRAME_WIDTH,
                UI_FRAME_HEIGHT,
                renderDialogFrame(UI_FRAME_WIDTH, UI_FRAME_HEIGHT)
        );
        if (includeDemoArena) {
            TextureManager.writePng(
                    root.resolve("assets/images/sprites/player.png"),
                    SPRITE_SIZE,
                    SPRITE_SIZE,
                    renderPlayerBadgeSprite(SPRITE_SIZE, SPRITE_SIZE)
            );
            TextureManager.writePng(
                    root.resolve("assets/images/sprites/goal_star.png"),
                    SPRITE_SIZE,
                    SPRITE_SIZE,
                    renderGoalStarSprite(SPRITE_SIZE, SPRITE_SIZE)
            );
        }
    }

    private static void generateProceduralAudio(Path root, boolean includeDemoArena) throws IOException {
        double[] chordFreqs = {220.0, 277.18, 329.63, 440.0};
        AudioDecoder.DecodedAudio first = AudioDecoder.synthesizeTone(
                chordFreqs[0],
                1.2,
                AUDIO_SAMPLE_RATE,
                0.28f
        );
        float[] mixed = new float[first.frameCount()];
        for (double freq : chordFreqs) {
            AudioDecoder.DecodedAudio voice = AudioDecoder.synthesizeTone(
                    freq,
                    1.2,
                    AUDIO_SAMPLE_RATE,
                    0.28f
            );
            float[] samples = voice.monoOrInterleavedFloat();
            for (int i = 0; i < mixed.length && i < samples.length; i++) {
                mixed[i] += samples[i] / chordFreqs.length;
            }
        }
        short[] chordPcm = AudioDecoder.floatToPcm16(mixed);
        byte[] nachtwindWav = AudioDecoder.encodeWav(chordPcm, 1, AUDIO_SAMPLE_RATE);
        Path nachtwindPath = root.resolve("assets/audio/nachtwind.wav");
        Files.createDirectories(nachtwindPath.getParent());
        Files.write(nachtwindPath, nachtwindWav);

        AudioDecoder.DecodedAudio voiceTone = AudioDecoder.synthesizeTone(
                523.25,
                0.35,
                AUDIO_SAMPLE_RATE,
                0.35f
        );
        byte[] voiceWav = AudioDecoder.encodeWav(voiceTone.pcm16(), voiceTone.channels(), voiceTone.sampleRate());
        Path voicePath = root.resolve("assets/audio/voice/lena_01.wav");
        Files.createDirectories(voicePath.getParent());
        Files.write(voicePath, voiceWav);

        if (includeDemoArena) {
            AudioDecoder.DecodedAudio chimeTone = AudioDecoder.synthesizeTone(
                    783.99,
                    0.28,
                    AUDIO_SAMPLE_RATE,
                    0.32f
            );
            byte[] chimeWav = AudioDecoder.encodeWav(chimeTone.pcm16(), chimeTone.channels(), chimeTone.sampleRate());
            Path chimePath = root.resolve("assets/audio/chime.wav");
            Files.createDirectories(chimePath.getParent());
            Files.write(chimePath, chimeWav);
        }
    }

    private static void generateActionAdventureImages(Path root) {
        TextureManager.writePng(
                root.resolve("assets/images/bg/bg_ruins.png"),
                BG_WIDTH,
                BG_HEIGHT,
                renderNightAcademyBg(BG_WIDTH, BG_HEIGHT)
        );
        TextureManager.writePng(
                root.resolve("assets/images/bg/bg_vault.png"),
                BG_WIDTH,
                BG_HEIGHT,
                renderLibraryBg(BG_WIDTH, BG_HEIGHT)
        );
        TextureManager.writePng(
                root.resolve("assets/images/sprites/player.png"),
                SPRITE_SIZE,
                SPRITE_SIZE,
                renderPlayerBadgeSprite(SPRITE_SIZE, SPRITE_SIZE)
        );
        TextureManager.writePng(
                root.resolve("assets/images/sprites/npc_guardian.png"),
                SPRITE_SIZE,
                SPRITE_SIZE,
                renderGuardianSprite(SPRITE_SIZE, SPRITE_SIZE)
        );
        TextureManager.writePng(
                root.resolve("assets/images/sprites/golden_key.png"),
                SPRITE_SIZE,
                SPRITE_SIZE,
                renderGoalStarSprite(SPRITE_SIZE, SPRITE_SIZE)
        );
        TextureManager.writePng(
                root.resolve("assets/images/sprites/vault_gate.png"),
                SPRITE_SIZE,
                SPRITE_SIZE,
                renderVaultGateSprite(SPRITE_SIZE, SPRITE_SIZE)
        );
        TextureManager.writePng(
                root.resolve("assets/images/sprites/platform_stone.png"),
                SPRITE_SIZE,
                SPRITE_SIZE,
                renderPlatformSprite(SPRITE_SIZE, SPRITE_SIZE)
        );
        TextureManager.writePng(
                root.resolve("assets/images/ui/dialog_frame.png"),
                UI_FRAME_WIDTH,
                UI_FRAME_HEIGHT,
                renderDialogFrame(UI_FRAME_WIDTH, UI_FRAME_HEIGHT)
        );
    }

    private static void generateActionAdventureAudio(Path root) throws IOException {
        AudioDecoder.DecodedAudio theme = AudioDecoder.synthesizeChord(
                new double[]{220.0, 261.63, 329.63},
                1.0,
                AUDIO_SAMPLE_RATE,
                0.24f
        );
        byte[] themeWav = AudioDecoder.encodeWav(theme.pcm16(), theme.channels(), theme.sampleRate());
        Path themePath = root.resolve("assets/audio/quest_theme.wav");
        Files.createDirectories(themePath.getParent());
        Files.write(themePath, themeWav);

        AudioDecoder.DecodedAudio chimeTone = AudioDecoder.synthesizeTone(
                880.0,
                0.25,
                AUDIO_SAMPLE_RATE,
                0.30f
        );
        byte[] chimeWav = AudioDecoder.encodeWav(chimeTone.pcm16(), chimeTone.channels(), chimeTone.sampleRate());
        Path chimePath = root.resolve("assets/audio/key_chime.wav");
        Files.createDirectories(chimePath.getParent());
        Files.write(chimePath, chimeWav);
    }

    private static String buildHybrid3dMainScript() {
        return """
                // Datei: main.vjava (3D-Erkundung Hybrid Vorlage)
                define hero = Character("Kira", color="#58a6ff")

                default area_name = "Astral-Halle"

                image bg_hall_2d = "assets/images/bg/bg_hall_2d.png"

                label start:
                    jump hall_3d

                label hall_3d:
                    $ area_name = "Astral-Halle (3D)"
                    scene3d "assets/models/hall.obj"
                    spawn3d pillar "assets/models/pillar.obj" at 2.0 0.0 -4.0
                    material3d pillar tint "#c8a878" roughness 0.45 metallic 0.10
                    fog3d "#1b2842" density 0.025 falloff 0.15
                    camera3d look_at 0.0 1.4 -4.0 duration 0.5
                    wasd3d_init speed 4.5 fov 72.0
                    play sound "assets/audio/chime.wav"
                    hero "Willkommen in der hybriden 3D-Astral-Halle! Nutze WASD3D zur Erkundung."
                    menu:
                        "3D-Pfeiler weiter erkunden":
                            hero "Das Material des Pfeilers glänzt im Nebel der Astral-Halle."
                            return
                        "Auf 2D-Ansicht umschalten":
                            jump hall_2d_fallback

                label hall_2d_fallback:
                    $ area_name = "Astral-Halle (2D-Backdrop)"
                    scene bg_hall_2d with fade(0.4)
                    play sound "assets/audio/chime.wav"
                    hero "Der nahtlose Fallback auf den 2D-Backdrop funktioniert!"
                    return
                """;
    }

    private static String buildHybrid3dScreensScript(String projectTitle) {
        String escapedTitle = projectTitle.replace("\"", "'");
        return """
                // Datei: ui/screens.vjava (3D-Erkundung Hybrid)
                screen hybrid3d_hud():
                    zorder 50
                    frame:
                        xalign 0.02
                        yalign 0.02
                        background "assets/images/ui/dialog_frame.png"
                        vbox:
                            spacing 6
                            text "%s" size 20
                            text "Zone: [area_name]" size 16
                """.formatted(escapedTitle);
    }

    private static String buildHybrid3dEnglishScript() {
        return """
                // Datei: tl/english/translations.vjava (3D-Erkundung Hybrid)
                translate english strings:
                    old "Willkommen in der hybriden 3D-Astral-Halle! Nutze WASD3D zur Erkundung."
                    new "Welcome to the hybrid 3D Astral Hall! Use WASD3D to explore."
                    old "Das Material des Pfeilers glänzt im Nebel der Astral-Halle."
                    new "The pillar material gleams inside the Astral Hall mist."
                    old "Der nahtlose Fallback auf den 2D-Backdrop funktioniert!"
                    new "Seamless fallback to the 2D backdrop works!"
                """;
    }

    private static void generateHybrid3dAssets(Path root) throws IOException {
        writeUtf8(root.resolve("assets/models/hall.mtl"), """
                # KAGAYAKI 3D Hall Material
                newmtl hall_stone
                Kd 0.62 0.68 0.78
                Ks 0.25 0.25 0.25
                Ns 32.0
                d 1.0
                """);
        writeUtf8(root.resolve("assets/models/hall.obj"), """
                # KAGAYAKI Hybrid 3D Hall Floor
                mtllib hall.mtl
                usemtl hall_stone
                v -8.0 0.0 -8.0
                v 8.0 0.0 -8.0
                v 8.0 0.0 8.0
                v -8.0 0.0 8.0
                vt 0.0 0.0
                vt 1.0 0.0
                vt 1.0 1.0
                vt 0.0 1.0
                vn 0.0 1.0 0.0
                f 1/1/1 2/2/1 3/3/1
                f 1/1/1 3/3/1 4/4/1
                """);
        writeUtf8(root.resolve("assets/models/pillar.obj"), """
                # KAGAYAKI Hybrid 3D Pillar Box
                v -0.5 0.0 -0.5
                v 0.5 0.0 -0.5
                v 0.5 2.5 -0.5
                v -0.5 2.5 -0.5
                v -0.5 0.0 0.5
                v 0.5 0.0 0.5
                v 0.5 2.5 0.5
                v -0.5 2.5 0.5
                vt 0.0 0.0
                vt 1.0 0.0
                vt 1.0 1.0
                vt 0.0 1.0
                vn 0.0 0.0 1.0
                vn 0.0 0.0 -1.0
                vn 1.0 0.0 0.0
                vn -1.0 0.0 0.0
                f 5/1/1 6/2/1 7/3/1
                f 5/1/1 7/3/1 8/4/1
                f 1/1/2 4/4/2 3/3/2
                f 1/1/2 3/3/2 2/2/2
                f 2/1/3 3/4/3 7/3/3
                f 2/1/3 7/3/3 6/2/3
                f 1/1/4 5/2/4 8/3/4
                f 1/1/4 8/3/4 4/4/4
                """);
        TextureManager.writePng(
                root.resolve("assets/images/bg/bg_hall_2d.png"),
                BG_WIDTH,
                BG_HEIGHT,
                renderNightAcademyBg(BG_WIDTH, BG_HEIGHT)
        );
        TextureManager.writePng(
                root.resolve("assets/images/ui/dialog_frame.png"),
                UI_FRAME_WIDTH,
                UI_FRAME_HEIGHT,
                renderDialogFrame(UI_FRAME_WIDTH, UI_FRAME_HEIGHT)
        );
        AudioDecoder.DecodedAudio chimeTone = AudioDecoder.synthesizeTone(
                783.99,
                0.28,
                AUDIO_SAMPLE_RATE,
                0.32f
        );
        byte[] chimeWav = AudioDecoder.encodeWav(chimeTone.pcm16(), chimeTone.channels(), chimeTone.sampleRate());
        Path chimePath = root.resolve("assets/audio/chime.wav");
        Files.createDirectories(chimePath.getParent());
        Files.write(chimePath, chimeWav);
    }

    private static byte[] renderGuardianSprite(int w, int h) {
        byte[] rgba = new byte[w * h * 4];
        fillEllipse(rgba, w, h, w / 2, h / 2, w / 2 - 6, h / 2 - 6, 210, 128, 56, 255);
        fillEllipse(rgba, w, h, w / 2, h / 2 - 8, 15, 15, 240, 215, 195, 255);
        fillRect(rgba, w, h, w / 2 - 16, h / 2 + 8, w / 2 + 16, h - 10, 88, 54, 34, 255);
        return rgba;
    }

    private static byte[] renderVaultGateSprite(int w, int h) {
        byte[] rgba = new byte[w * h * 4];
        fillRect(rgba, w, h, 8, 6, w - 8, h - 4, 62, 74, 96, 255);
        fillRect(rgba, w, h, 18, 18, w - 18, h - 8, 28, 36, 52, 255);
        fillEllipse(rgba, w, h, w / 2, h / 2, 10, 10, 232, 186, 68, 255);
        return rgba;
    }

    private static byte[] renderPlatformSprite(int w, int h) {
        byte[] rgba = new byte[w * h * 4];
        fillRect(rgba, w, h, 4, h / 3, w - 4, (2 * h) / 3, 98, 110, 134, 255);
        fillRect(rgba, w, h, 4, h / 3, w - 4, h / 3 + 6, 152, 168, 198, 255);
        return rgba;
    }

    private enum FaceMood {
        NEUTRAL,
        HAPPY,
        DETERMINED
    }

    private static byte[] renderNightAcademyBg(int w, int h) {
        byte[] rgba = new byte[w * h * 4];
        int moonCx = (int) (w * 0.78);
        int moonCy = (int) (h * 0.22);
        int moonR = 28;
        int horizonY = (int) (h * 0.68);

        for (int y = 0; y < h; y++) {
            float t = (float) y / Math.max(1, h - 1);
            int baseR = lerp(8, 22, t);
            int baseG = lerp(13, 36, t);
            int baseB = lerp(28, 66, t);

            for (int x = 0; x < w; x++) {
                int r = baseR;
                int g = baseG;
                int b = baseB;

                int dx = x - moonCx;
                int dy = y - moonCy;
                double dist = Math.hypot(dx, dy);
                if (dist <= moonR) {
                    r = 242;
                    g = 246;
                    b = 255;
                } else if (dist <= moonR * 3.0) {
                    float glow = (float) Math.pow(1.0 - (dist - moonR) / (moonR * 2.0), 2.0) * 0.35f;
                    r = clampByte(r + Math.round(140 * glow));
                    g = clampByte(g + Math.round(170 * glow));
                    b = clampByte(b + Math.round(220 * glow));
                }

                if (y < horizonY && ((x * 73 + y * 151) % 397 == 0)) {
                    r = 220;
                    g = 232;
                    b = 255;
                }

                // Academy silhouette
                int roofline = horizonY - (((x / 40) % 3 == 1) ? 45 : 18);
                if (x > w / 3 && x < (2 * w) / 3 && y >= roofline) {
                    r = 12;
                    g = 17;
                    b = 30;
                    if ((x % 24 >= 8 && x % 24 <= 15) && (y % 28 >= 8 && y % 28 <= 18) && y < h - 30) {
                        r = 240;
                        g = 196;
                        b = 110;
                    }
                } else if (y >= horizonY + 12) {
                    r = 10;
                    g = 14;
                    b = 24;
                }

                putPixel(rgba, w, x, y, r, g, b, 255);
            }
        }
        return rgba;
    }

    private static byte[] renderLibraryBg(int w, int h) {
        byte[] rgba = new byte[w * h * 4];
        int lampCx = w / 2;
        int lampCy = (int) (h * 0.28);

        for (int y = 0; y < h; y++) {
            float t = (float) y / Math.max(1, h - 1);
            int baseR = lerp(26, 46, t);
            int baseG = lerp(18, 30, t);
            int baseB = lerp(14, 22, t);

            for (int x = 0; x < w; x++) {
                int r = baseR;
                int g = baseG;
                int b = baseB;

                // Bookshelf pattern
                if (y > 24 && y < h - 36 && (x < w * 0.38 || x > w * 0.62)) {
                    if (y % 44 < 5 || x % 68 < 5) {
                        r = 56;
                        g = 36;
                        b = 24;
                    } else {
                        int spineHash = (x / 9 + y / 44) % 4;
                        switch (spineHash) {
                            case 0 -> {
                                r = 92;
                                g = 44;
                                b = 44;
                            }
                            case 1 -> {
                                r = 46;
                                g = 74;
                                b = 96;
                            }
                            case 2 -> {
                                r = 54;
                                g = 82;
                                b = 56;
                            }
                            default -> {
                                r = 106;
                                g = 84;
                                b = 52;
                            }
                        }
                    }
                }

                double dist = Math.hypot(x - lampCx, y - lampCy);
                if (dist < 160.0) {
                    float glow = (float) Math.pow(1.0 - dist / 160.0, 2.0) * 0.65f;
                    r = clampByte(r + Math.round(255 * glow));
                    g = clampByte(g + Math.round(210 * glow));
                    b = clampByte(b + Math.round(125 * glow));
                }

                putPixel(rgba, w, x, y, r, g, b, 255);
            }
        }
        return rgba;
    }

    private static byte[] renderLenaBase(int w, int h) {
        byte[] rgba = new byte[w * h * 4];
        int cx = w / 2;
        int headCy = (int) (h * 0.30);

        fillEllipse(rgba, w, h, cx, headCy + 6, 56, 72, 54, 82, 130, 255); // back hair
        fillRect(rgba, w, h, cx - 16, headCy + 42, cx + 16, headCy + 82, 240, 206, 186, 255); // neck
        fillEllipse(rgba, w, h, cx, (int) (h * 0.72), 68, 120, 236, 204, 184, 255); // torso base
        fillEllipse(rgba, w, h, cx, headCy, 44, 52, 247, 218, 198, 255); // face
        fillEllipse(rgba, w, h, cx, headCy - 30, 48, 26, 66, 98, 152, 255); // bangs
        return rgba;
    }

    private static byte[] renderLenaOutfit(
            int w,
            int h,
            int mainR,
            int mainG,
            int mainB,
            int accentR,
            int accentG,
            int accentB
    ) {
        byte[] rgba = new byte[w * h * 4];
        int cx = w / 2;
        int shoulderY = (int) (h * 0.50);
        fillEllipse(rgba, w, h, cx, (int) (h * 0.76), 72, 118, mainR, mainG, mainB, 255);
        fillRect(rgba, w, h, cx - 22, shoulderY, cx + 22, shoulderY + 34, 236, 242, 250, 255);
        fillEllipse(rgba, w, h, cx, shoulderY + 26, 22, 10, accentR, accentG, accentB, 255);
        return rgba;
    }

    private static byte[] renderLenaFace(int w, int h, FaceMood mood) {
        byte[] rgba = new byte[w * h * 4];
        int cx = w / 2;
        int eyeY = (int) (h * 0.30);
        int leftEyeX = cx - 18;
        int rightEyeX = cx + 18;

        if (mood == FaceMood.HAPPY) {
            fillRect(rgba, w, h, leftEyeX - 8, eyeY - 2, leftEyeX + 8, eyeY + 2, 34, 48, 78, 255);
            fillRect(rgba, w, h, rightEyeX - 8, eyeY - 2, rightEyeX + 8, eyeY + 2, 34, 48, 78, 255);
            fillEllipse(rgba, w, h, leftEyeX - 4, eyeY + 14, 9, 5, 255, 143, 163, 180);
            fillEllipse(rgba, w, h, rightEyeX + 4, eyeY + 14, 9, 5, 255, 143, 163, 180);
        } else {
            fillEllipse(rgba, w, h, leftEyeX, eyeY, 8, 9, 250, 252, 255, 255);
            fillEllipse(rgba, w, h, rightEyeX, eyeY, 8, 9, 250, 252, 255, 255);
            fillEllipse(rgba, w, h, leftEyeX, eyeY + 1, 5, 6, 56, 130, 235, 255);
            fillEllipse(rgba, w, h, rightEyeX, eyeY + 1, 5, 6, 56, 130, 235, 255);
            int browY = mood == FaceMood.DETERMINED ? eyeY - 12 : eyeY - 14;
            fillRect(rgba, w, h, leftEyeX - 9, browY, leftEyeX + 8, browY + 3, 42, 62, 98, 255);
            fillRect(rgba, w, h, rightEyeX - 8, browY, rightEyeX + 9, browY + 3, 42, 62, 98, 255);
        }
        return rgba;
    }

    private static byte[] renderLenaMouth(int w, int h, int radiusY) {
        byte[] rgba = new byte[w * h * 4];
        int cx = w / 2;
        int mouthY = (int) (h * 0.37);
        fillEllipse(rgba, w, h, cx, mouthY, 10, radiusY, 182, 86, 98, 255);
        if (radiusY > 3) {
            fillEllipse(rgba, w, h, cx, mouthY, 7, Math.max(1, radiusY - 3), 118, 42, 54, 255);
        }
        return rgba;
    }

    private static byte[] renderDialogFrame(int w, int h) {
        byte[] rgba = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean outerBorder = x < 2 || y < 2 || x >= w - 2 || y >= h - 2;
                boolean innerLine = x == 4 || y == 4 || x == w - 5 || y == h - 5;
                if (outerBorder) {
                    putPixel(rgba, w, x, y, 88, 166, 255, 245);
                } else if (innerLine) {
                    putPixel(rgba, w, x, y, 130, 192, 255, 110);
                } else {
                    putPixel(rgba, w, x, y, 14, 20, 34, 220);
                }
            }
        }
        return rgba;
    }

    private static byte[] renderPlayerBadgeSprite(int w, int h) {
        byte[] rgba = new byte[w * h * 4];
        fillEllipse(rgba, w, h, w / 2, h / 2, w / 2 - 4, h / 2 - 4, 88, 166, 255, 255);
        fillEllipse(rgba, w, h, w / 2, h / 2 - 8, 16, 16, 245, 224, 210, 255);
        fillRect(rgba, w, h, w / 2 - 18, h / 2 + 10, w / 2 + 18, h - 12, 34, 62, 118, 255);
        return rgba;
    }

    private static byte[] renderGoalStarSprite(int w, int h) {
        byte[] rgba = new byte[w * h * 4];
        int cx = w / 2;
        int cy = h / 2;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx = Math.abs(x - cx);
                double dy = Math.abs(y - cy);
                double manhattan = dx + dy;
                if (manhattan < w * 0.42 || (dx < w * 0.14 && dy < h * 0.45) || (dy < h * 0.14 && dx < w * 0.45)) {
                    putPixel(rgba, w, x, y, 255, 214, 72, 255);
                }
            }
        }
        return rgba;
    }

    private static void fillRect(
            byte[] rgba, int w, int h, int x0, int y0, int x1, int y1, int r, int g, int b, int a) {
        int minX = Math.max(0, x0);
        int maxX = Math.min(w - 1, x1);
        int minY = Math.max(0, y0);
        int maxY = Math.min(h - 1, y1);
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                putPixel(rgba, w, x, y, r, g, b, a);
            }
        }
    }

    private static void fillEllipse(
            byte[] rgba,
            int w,
            int h,
            int cx,
            int cy,
            int rx,
            int ry,
            int r,
            int g,
            int b,
            int a
    ) {
        if (rx <= 0 || ry <= 0) {
            return;
        }
        int minX = Math.max(0, cx - rx);
        int maxX = Math.min(w - 1, cx + rx);
        int minY = Math.max(0, cy - ry);
        int maxY = Math.min(h - 1, cy + ry);
        double invRx2 = 1.0 / (rx * (double) rx);
        double invRy2 = 1.0 / (ry * (double) ry);
        for (int y = minY; y <= maxY; y++) {
            double dy = y - cy;
            for (int x = minX; x <= maxX; x++) {
                double dx = x - cx;
                if (dx * dx * invRx2 + dy * dy * invRy2 <= 1.0) {
                    putPixel(rgba, w, x, y, r, g, b, a);
                }
            }
        }
    }

    private static void putPixel(byte[] rgba, int w, int x, int y, int r, int g, int b, int a) {
        int idx = (y * w + x) * 4;
        rgba[idx] = (byte) r;
        rgba[idx + 1] = (byte) g;
        rgba[idx + 2] = (byte) b;
        rgba[idx + 3] = (byte) a;
    }

    private static int lerp(int a, int b, float t) {
        return Math.round(a + (b - a) * Math.clamp(t, 0.0f, 1.0f));
    }

    private static int clampByte(int value) {
        return Math.clamp(value, 0, 255);
    }
}
