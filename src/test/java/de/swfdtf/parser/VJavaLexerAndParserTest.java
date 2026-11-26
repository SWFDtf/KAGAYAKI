package de.swfdtf.parser;

import de.swfdtf.parser.VJavaAst.AlwaysLayerEntry;
import de.swfdtf.parser.VJavaAst.AtlStep;
import de.swfdtf.parser.VJavaAst.AttributeEntry;
import de.swfdtf.parser.VJavaAst.BlockRollbackStatement;
import de.swfdtf.parser.VJavaAst.CallStatement;
import de.swfdtf.parser.VJavaAst.CharacterDef;
import de.swfdtf.parser.VJavaAst.ConditionalLayerEntry;
import de.swfdtf.parser.VJavaAst.GroupLayerEntry;
import de.swfdtf.parser.VJavaAst.HideStatement;
import de.swfdtf.parser.VJavaAst.IfStatement;
import de.swfdtf.parser.VJavaAst.ImageDef;
import de.swfdtf.parser.VJavaAst.InputStatement;
import de.swfdtf.parser.VJavaAst.JavaBlockNode;
import de.swfdtf.parser.VJavaAst.JavaBlockStatement;
import de.swfdtf.parser.VJavaAst.JumpStatement;
import de.swfdtf.parser.VJavaAst.LabelNode;
import de.swfdtf.parser.VJavaAst.LayeredImageDef;
import de.swfdtf.parser.VJavaAst.MenuChoice;
import de.swfdtf.parser.VJavaAst.MenuStatement;
import de.swfdtf.parser.VJavaAst.NvlStatement;
import de.swfdtf.parser.VJavaAst.PauseStatement;
import de.swfdtf.parser.VJavaAst.PlayStatement;
import de.swfdtf.parser.VJavaAst.QueueStatement;
import de.swfdtf.parser.VJavaAst.ReturnStatement;
import de.swfdtf.parser.VJavaAst.SayStatement;
import de.swfdtf.parser.VJavaAst.SceneStatement;
import de.swfdtf.parser.VJavaAst.ScreenDef;
import de.swfdtf.parser.VJavaAst.ScreenItem;
import de.swfdtf.parser.VJavaAst.ScriptFileAst;
import de.swfdtf.parser.VJavaAst.SetVarStatement;
import de.swfdtf.parser.VJavaAst.ShowStatement;
import de.swfdtf.parser.VJavaAst.Statement;
import de.swfdtf.parser.VJavaAst.StopStatement;
import de.swfdtf.parser.VJavaAst.TransformDef;
import de.swfdtf.parser.VJavaAst.TranslateBlock;
import de.swfdtf.parser.VJavaAst.TranslateEntry;
import de.swfdtf.parser.VJavaAst.VoiceStatement;
import de.swfdtf.parser.VJavaAst.WindowStatement;
import de.swfdtf.parser.VJavaAst.WithStatement;
import de.swfdtf.parser.VJavaLexer.Token;
import de.swfdtf.parser.VJavaLexer.TokenType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VJavaLexerAndParserTest {

    private static final String SECTION_8_REFERENCE = """
            // Datei: main.vjava (kann beliebig im Projektordner platziert werden)
            // VN-Syntax ist einrückungsbasiert (ohne { }), Java-Klassen/Code stehen in @->{ ... }<<

            define lena = Character("Lena", color="#58a6ff", image="lena", voice_tag="lena")

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

            label start:
                scene bg_akademie_nacht with dissolve(0.8)
                play music "assets/audio/nachtwind.ogg" fadein 2.0

                :: Kode für VN: Initialisiere Spielerdaten mit den eigenen Java-Klassen @->{
                    SpezialGegenstand amulett = new SpezialGegenstand("Sternenkompass", 100, "Nachtsicht");
                    state.set("spieler_name", "Kaito");
                    state.set("aktives_item", amulett);
                    state.set("mut_punkte", DialogHelfer.berechneBonus(10, true));
                    engine.logInfo("Startzustand mit SpezialGegenstand '" + amulett.name + "' geladen.");
                }<<

                show lena neutral at center with easein(0.4)
                voice "assets/audio/voice/lena_01.ogg"
                lena "Willkommen in der ersten Beispielszene, [spieler_name].{w=0.4} \
            Bereit für den Übergang in Dialog 2?"

                menu:
                    "Ja, direkt weiter zu Dialog 2!" if mut_punkte >= 20 -> jump dialog_zwei
                    "Erst noch die Umgebung untersuchen." -> jump umgebung_pruefen

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
            """;

    @Test
    void parsesSection8ReferenceScriptCompletely() {
        List<Token> tokens = VJavaLexer.tokenize(SECTION_8_REFERENCE, "main.vjava");
        assertEquals(TokenType.EOF, tokens.get(tokens.size() - 1).type());

        ScriptFileAst ast = VJavaParser.parse(Path.of("main.vjava"), tokens);

        // Character definition
        assertEquals(1, ast.characters().size());
        CharacterDef lena = ast.characters().get(0);
        assertEquals("lena", lena.id());
        assertEquals("Lena", lena.displayName());
        assertEquals("#58a6ff", lena.colorHex());
        assertEquals("lena", lena.imageTag());
        assertEquals("lena", lena.voiceTag());

        // Top-level Java block with classes & subclasses
        assertEquals(1, ast.topLevelJavaBlocks().size());
        JavaBlockNode topJava = ast.topLevelJavaBlocks().get(0);
        assertEquals("Definiere eigene Java-Klassen & Unterklassen für das Spielsystem", topJava.description());
        assertTrue(topJava.code().contains("public static class InventarItem"));
        assertTrue(topJava.code().contains("public static class SpezialGegenstand extends InventarItem"));
        assertTrue(topJava.code().contains("public static class DialogHelfer"));

        // LayeredImage
        assertEquals(1, ast.layeredImages().size());
        LayeredImageDef layered = ast.layeredImages().get(0);
        assertEquals("lena", layered.name());
        assertEquals(3, layered.entries().size());
        assertEquals(new AlwaysLayerEntry("assets/images/char/lena_base.png"), layered.entries().get(0));

        GroupLayerEntry outfit = (GroupLayerEntry) layered.entries().get(1);
        assertEquals("outfit", outfit.groupName());
        assertEquals(2, outfit.attributes().size());
        assertEquals(
                new AttributeEntry("uniform", true, "assets/images/char/lena_uniform.png", null),
                outfit.attributes().get(0));
        assertEquals(
                new AttributeEntry("casual", false, "assets/images/char/lena_casual.png", null),
                outfit.attributes().get(1));

        GroupLayerEntry face = (GroupLayerEntry) layered.entries().get(2);
        assertEquals("face", face.groupName());
        assertEquals(3, face.attributes().size());
        assertTrue(face.attributes().get(0).isDefault());
        assertEquals("entschlossen", face.attributes().get(2).name());

        // Labels
        assertEquals(3, ast.labels().size());

        LabelNode start = ast.labels().get(0);
        assertEquals("start", start.name());
        assertEquals(7, start.statements().size());

        SceneStatement scene1 = (SceneStatement) start.statements().get(0);
        assertEquals("bg_akademie_nacht", scene1.bgTag());
        assertEquals("dissolve(0.8)", scene1.transitionExpr());

        PlayStatement play1 = (PlayStatement) start.statements().get(1);
        assertEquals("music", play1.channel());
        assertEquals("assets/audio/nachtwind.ogg", play1.assetPath());
        assertTrue(play1.loop());
        assertEquals(2.0, play1.fadeInSeconds(), 1e-6);
        assertEquals(1.0, play1.volume(), 1e-6);

        JavaBlockStatement javaStmt = (JavaBlockStatement) start.statements().get(2);
        assertEquals("Initialisiere Spielerdaten mit den eigenen Java-Klassen", javaStmt.block().description());
        assertTrue(javaStmt.block().code().contains("new SpezialGegenstand(\"Sternenkompass\", 100, \"Nachtsicht\")"));

        ShowStatement show1 = (ShowStatement) start.statements().get(3);
        assertEquals("lena", show1.tag());
        assertEquals(List.of("neutral"), show1.attributes());
        assertEquals("center", show1.positionExpr());
        assertEquals("easein(0.4)", show1.transitionExpr());

        VoiceStatement voice1 = (VoiceStatement) start.statements().get(4);
        assertEquals("assets/audio/voice/lena_01.ogg", voice1.assetPath());

        SayStatement say1 = (SayStatement) start.statements().get(5);
        assertEquals("lena", say1.speakerId());
        assertEquals(
                "Willkommen in der ersten Beispielszene, [spieler_name].{w=0.4} Bereit für den Übergang in Dialog 2?",
                say1.text());
        assertNull(say1.transitionExpr());

        MenuStatement menu = (MenuStatement) start.statements().get(6);
        assertNull(menu.caption());
        assertNull(menu.timeoutSeconds());
        assertEquals(2, menu.choices().size());
        MenuChoice choice1 = menu.choices().get(0);
        assertEquals("Ja, direkt weiter zu Dialog 2!", choice1.text());
        assertEquals("mut_punkte >= 20", choice1.conditionExpr());
        assertEquals("dialog_zwei", choice1.inlineJumpTarget());
        assertFalse(choice1.disabledWhenFalse());

        MenuChoice choice2 = menu.choices().get(1);
        assertEquals("Erst noch die Umgebung untersuchen.", choice2.text());
        assertNull(choice2.conditionExpr());
        assertEquals("umgebung_pruefen", choice2.inlineJumpTarget());

        // Second label
        LabelNode umgebung = ast.labels().get(1);
        assertEquals("umgebung_pruefen", umgebung.name());
        assertEquals(3, umgebung.statements().size());
        SayStatement sayVpunch = (SayStatement) umgebung.statements().get(1);
        assertEquals("vpunch", sayVpunch.transitionExpr());
        JumpStatement jump1 = (JumpStatement) umgebung.statements().get(2);
        assertEquals("dialog_zwei", jump1.targetLabel());
        assertFalse(jump1.isExpression());

        // Third label
        LabelNode dialogZwei = ast.labels().get(2);
        assertEquals("dialog_zwei", dialogZwei.name());
        assertEquals(5, dialogZwei.statements().size());
        StopStatement stop1 = (StopStatement) dialogZwei.statements().get(3);
        assertEquals("music", stop1.channel());
        assertEquals(1.5, stop1.fadeOutSeconds(), 1e-6);
        ReturnStatement ret = (ReturnStatement) dialogZwei.statements().get(4);
        assertNull(ret.expression());
    }

    @Test
    void supportsIfElifElseCallReturnInputFlowAndMenus() {
        String script = """
                default mut_punkte = 10
                $ global_flag = true
                define custom_char = Character("Mira", color="#ff00aa", outline_color="#111111", image="mira", \
                voice_tag="mira_v", textbox="assets/ui/box.png", \
                voice_bleep="assets/audio/bleep.ogg", mood_style="calm")
                image bg akademie nacht = "assets/images/bg/akademie_nacht.png"

                label flow_test:
                    nvl show
                    window hide
                    block_rollback
                    input player_name "Wie heißt du?" default="Kaito" allow="A-Za-z" length=16
                    $ mut_punkte = mut_punkte + 5
                    call sub_routine(player_name, 42)
                    if mut_punkte >= 30:
                        custom_char "Sehr mutig!"
                    elif mut_punkte >= 15:
                        "Erzähler: \\"Mutig genug\\" mit Anführungszeichen."
                    else:
                        pass
                        jump expression next_target
                    menu "Wähle schnell:":
                        timeout 5.0 -> jump timeout_label
                        "Option A" if mut_punkte > 0 disabled -> jump opt_a
                        "Option B":
                            custom_char "Eingerückter Zweig!"
                            hide custom_char with dissolve(0.2)
                    queue music "assets/audio/a.ogg" "assets/audio/b.ogg"
                    queue sfx:
                        "assets/audio/s1.ogg"
                        "assets/audio/s2.ogg"
                    pause 1.25
                    pause
                    with fade(0.3)
                    nvl clear
                    nvl hide
                    window show
                    return mut_punkte

                label sub_routine:
                    return
                """;

        ScriptFileAst ast = VJavaParser.parse(Path.of("flow.vjava"), script);

        assertEquals(2, ast.defaultVariables().size());
        assertTrue(ast.defaultVariables().get(0).isDefaultOnly());
        assertFalse(ast.defaultVariables().get(1).isDefaultOnly());

        CharacterDef mira = ast.characters().get(0);
        assertEquals("#ff00aa", mira.colorHex());
        assertEquals("#111111", mira.outlineColorHex());
        assertEquals("mira", mira.imageTag());
        assertEquals("mira_v", mira.voiceTag());
        assertEquals("assets/ui/box.png", mira.textboxSkin());
        assertEquals("assets/audio/bleep.ogg", mira.voiceBleepSound());
        assertEquals("calm", mira.properties().get("mood_style"));

        ImageDef img = ast.images().get(0);
        assertEquals("bg", img.tag());
        assertEquals(List.of("akademie", "nacht"), img.attributes());
        assertEquals("bg akademie nacht", img.fullKey());
        assertEquals("assets/images/bg/akademie_nacht.png", img.assetPath());

        List<Statement> stmts = ast.labels().get(0).statements();
        assertEquals("show", ((NvlStatement) stmts.get(0)).action());
        assertEquals("hide", ((WindowStatement) stmts.get(1)).action());
        assertTrue(stmts.get(2) instanceof BlockRollbackStatement);

        InputStatement input = (InputStatement) stmts.get(3);
        assertEquals("player_name", input.targetVar());
        assertEquals("Wie heißt du?", input.prompt());
        assertEquals("Kaito", input.defaultValue());
        assertEquals("A-Za-z", input.allowChars());
        assertEquals(16, input.maxLength());

        SetVarStatement setVar = (SetVarStatement) stmts.get(4);
        assertEquals("mut_punkte", setVar.varName());
        assertEquals("mut_punkte + 5", setVar.expression());

        CallStatement call = (CallStatement) stmts.get(5);
        assertEquals("sub_routine", call.targetLabel());
        assertEquals(List.of("player_name", "42"), call.args());

        IfStatement ifStmt = (IfStatement) stmts.get(6);
        assertEquals(3, ifStmt.branches().size());
        assertEquals("mut_punkte >= 30", ifStmt.branches().get(0).conditionExpr());
        assertEquals("mut_punkte >= 15", ifStmt.branches().get(1).conditionExpr());
        SayStatement escapedSay = (SayStatement) ifStmt.branches().get(1).body().get(0);
        assertNull(escapedSay.speakerId());
        assertEquals("Erzähler: \"Mutig genug\" mit Anführungszeichen.", escapedSay.text());
        assertNull(ifStmt.branches().get(2).conditionExpr());
        JumpStatement exprJump = (JumpStatement) ifStmt.branches().get(2).body().get(0);
        assertTrue(exprJump.isExpression());
        assertEquals("next_target", exprJump.targetLabel());

        MenuStatement menu = (MenuStatement) stmts.get(7);
        assertEquals("Wähle schnell:", menu.caption());
        assertEquals(5.0, menu.timeoutSeconds(), 1e-6);
        assertEquals("timeout_label", menu.timeoutJumpLabel());
        assertEquals(2, menu.choices().size());
        assertTrue(menu.choices().get(0).disabledWhenFalse());
        assertEquals("opt_a", menu.choices().get(0).inlineJumpTarget());
        assertNull(menu.choices().get(1).inlineJumpTarget());
        assertEquals(2, menu.choices().get(1).body().size());
        assertTrue(menu.choices().get(1).body().get(1) instanceof HideStatement);

        QueueStatement q1 = (QueueStatement) stmts.get(8);
        assertEquals(List.of("assets/audio/a.ogg", "assets/audio/b.ogg"), q1.assetPaths());
        QueueStatement q2 = (QueueStatement) stmts.get(9);
        assertEquals("sfx", q2.channel());
        assertEquals(List.of("assets/audio/s1.ogg", "assets/audio/s2.ogg"), q2.assetPaths());

        assertEquals(1.25, ((PauseStatement) stmts.get(10)).seconds(), 1e-6);
        assertNull(((PauseStatement) stmts.get(11)).seconds());
        assertEquals("fade(0.3)", ((WithStatement) stmts.get(12)).transitionExpr());

        ReturnStatement ret = (ReturnStatement) stmts.get(16);
        assertEquals("mut_punkte", ret.expression());
    }

    @Test
    void parsesTransformsScreensTranslationsAndLayeredConditionals() {
        String script = """
                transform pulse_and_spin:
                    xpos 0.5
                    alpha 0.0
                    matrixcolor TintMatrix("#58a6ff") * BrightnessMatrix(0.1)
                    linear 0.5 alpha 1.0 zoom 1.2
                    parallel:
                        easein 1.0 rotate 360
                        easeout 1.0 rotate 0
                    block:
                        ease 0.25 yoffset -8
                        pause 0.5
                    choice:
                        alpha 0.9
                    repeat

                layeredimage hero:
                    always:
                        "assets/images/hero_base.png"
                    if has_scar:
                        "assets/images/hero_scar.png"

                screen pause_overlay(title_text, show_save=true):
                    modal true
                    zorder 400
                    frame:
                        xalign 0.5
                        yalign 0.5
                        vbox:
                            spacing 12
                            text "[title_text]" size 32
                            grid 2 3:
                                textbutton "Resume" action Return()
                                imagebutton auto "assets/ui/save_%s.png" action Show("save")
                                bar value VariableValue("bgm_vol", 1.0) xsize 300
                                vbar value VariableValue("sfx_vol", 1.0)
                                slider value VariableValue("cps", 60)
                                input value VariableInputValue("name") length 20
                            hbox:
                                viewport:
                                    vpgrid:
                                        cols 2
                                        custom "MiniMapWidget" zoom=1.5
                                fixed:
                                    window:
                                        text "Nested"
                                    imagemap:
                                        ground "assets/ui/map.png"
                                        hotspot (10, 20, 100, 40) action Jump("town")
                                        hotbar (10, 80, 200, 30) value VariableValue("hp", 100)
                                    draggroup:
                                        drag:
                                            drag_name "token"
                                            draggable true
                    key "K_ESCAPE" action Return()
                    timer 10.0 action Jump("idle") repeat true

                translate deutsch strings:
                    old "Start"
                    new "Starten"

                translate deutsch intro_01:
                    # lena "Hello world"
                    lena "Hallo Welt"
                    # "Quiet night."
                    "Stille Nacht."

                label inline_atl:
                    show hero at center:
                        alpha 0.0
                        linear 0.3 alpha 1.0
                    return
                """;

        ScriptFileAst ast = VJavaParser.parse(Path.of("ui_and_atl.vjava"), script);

        // Transform
        assertEquals(1, ast.transforms().size());
        TransformDef tr = ast.transforms().get(0);
        assertEquals("pulse_and_spin", tr.name());
        assertEquals(8, tr.steps().size());
        assertEquals("set", tr.steps().get(0).kind());
        assertEquals("0.5", tr.steps().get(0).properties().get("xpos"));
        assertEquals(
                "TintMatrix(\"#58a6ff\") * BrightnessMatrix(0.1)",
                tr.steps().get(2).properties().get("matrixcolor"));
        AtlStep linearStep = tr.steps().get(3);
        assertEquals("interpolate", linearStep.kind());
        assertEquals("linear", linearStep.easeName());
        assertEquals(0.5f, linearStep.durationSeconds(), 1e-5f);
        assertEquals("1.0", linearStep.properties().get("alpha"));
        assertEquals("1.2", linearStep.properties().get("zoom"));
        assertEquals("parallel", tr.steps().get(4).kind());
        assertEquals(2, tr.steps().get(4).children().size());
        assertEquals("block", tr.steps().get(5).kind());
        assertEquals("pause", tr.steps().get(5).children().get(1).kind());
        assertEquals("choice", tr.steps().get(6).kind());
        assertEquals("repeat", tr.steps().get(7).kind());

        // LayeredImage conditional
        LayeredImageDef hero = ast.layeredImages().get(0);
        assertEquals(new ConditionalLayerEntry("has_scar", "assets/images/hero_scar.png"), hero.entries().get(1));

        // Screen
        ScreenDef scr = ast.screens().get(0);
        assertEquals("pause_overlay", scr.name());
        assertEquals(List.of("title_text", "show_save=true"), scr.parameters());
        assertTrue(scr.modal());
        assertEquals(400, scr.zOrder());
        assertEquals(3, scr.items().size());

        ScreenItem frame = scr.items().get(0);
        assertEquals("frame", frame.keyword());
        assertEquals("0.5", frame.properties().get("xalign"));
        ScreenItem vbox = frame.children().get(0);
        assertEquals("vbox", vbox.keyword());
        assertEquals("12", vbox.properties().get("spacing"));
        ScreenItem grid = vbox.children().get(1);
        assertEquals("grid", grid.keyword());
        assertEquals("2 3", grid.primaryArg());
        assertEquals("2", grid.properties().get("cols"));
        assertEquals("3", grid.properties().get("rows"));
        assertEquals(6, grid.children().size());
        assertEquals("Return()", grid.children().get(0).properties().get("action"));
        assertEquals("assets/ui/save_%s.png", grid.children().get(1).properties().get("auto"));

        ScreenItem keyItem = scr.items().get(1);
        assertEquals("key", keyItem.keyword());
        assertEquals("K_ESCAPE", keyItem.primaryArg());
        assertEquals("Return()", keyItem.properties().get("action"));

        ScreenItem timerItem = scr.items().get(2);
        assertEquals("timer", timerItem.keyword());
        assertEquals("10.0", timerItem.primaryArg());
        assertEquals("Jump(\"idle\")", timerItem.properties().get("action"));
        assertEquals("true", timerItem.properties().get("repeat"));

        // Translations
        assertEquals(2, ast.translations().size());
        TranslateBlock stringsTl = ast.translations().get(0);
        assertEquals("deutsch", stringsTl.language());
        assertEquals("strings", stringsTl.id());
        assertEquals(List.of(new TranslateEntry(null, "Start", "Starten")), stringsTl.entries());

        TranslateBlock dlgTl = ast.translations().get(1);
        assertEquals(2, dlgTl.entries().size());
        assertEquals(new TranslateEntry("lena", "Hello world", "Hallo Welt"), dlgTl.entries().get(0));
        assertEquals(new TranslateEntry(null, "Quiet night.", "Stille Nacht."), dlgTl.entries().get(1));

        // Inline ATL on show
        ShowStatement inlineShow = (ShowStatement) ast.labels().get(0).statements().get(0);
        assertEquals(2, inlineShow.inlineAtl().size());
        assertEquals("interpolate", inlineShow.inlineAtl().get(1).kind());
    }

    @Test
    void handlesBomCrlfCommentsAndJavaBlockMarkerInsideStrings() {
        String source = "\uFEFF"
                + "define c = Character(\"Bob\", color=\"#112233\") // trailing comment\r\n"
                + ":: Kode für VN @->{\r\n"
                + "    String trap = \"}<< not closing\";\r\n"
                + "    // }<< comment trap\r\n"
                + "    /* }<< block comment trap */\r\n"
                + "    String tb = \"\"\"\r\n"
                + "        }<< text block trap\r\n"
                + "        \"\"\";\r\n"
                + "    if (trap.length() > 0) {\r\n"
                + "        System.out.println(tb);\r\n"
                + "    }\r\n"
                + "}<< // after close\r\n"
                + "label start:\r\n"
                + "    c \"Color #abcdef // literal\" # stripped comment\r\n"
                + "    return\r\n";

        ScriptFileAst ast = VJavaParser.parse(Path.of("bom_test.vjava"), source);
        assertEquals("#112233", ast.characters().get(0).colorHex());
        assertEquals(1, ast.topLevelJavaBlocks().size());
        assertTrue(ast.topLevelJavaBlocks().get(0).code().contains("String trap = \"}<< not closing\";"));
        assertTrue(ast.topLevelJavaBlocks().get(0).code().contains("System.out.println(tb);"));
        SayStatement say = (SayStatement) ast.labels().get(0).statements().get(0);
        assertEquals("Color #abcdef // literal", say.text());
    }

    @Test
    void handlesMenuBlockTimeoutAndEscapedQuotesInChoices() {
        String source = """
                layeredimage sprite_a:
                    always "assets/images/base.png"
                    group mood:
                        attribute happy if points > 5:
                            "assets/images/happy.png"

                label branch:
                    show sprite_a with dissolve(0.2) at right
                    menu:
                        timeout 4.5:
                            jump timed_out
                        "Sage \\"A -> B\\" jetzt" if points >= 1 -> jump chosen_one
                """;

        ScriptFileAst ast = VJavaParser.parse(Path.of("menu_ext.vjava"), source);
        LayeredImageDef layered = ast.layeredImages().get(0);
        assertEquals(new AlwaysLayerEntry("assets/images/base.png"), layered.entries().get(0));
        GroupLayerEntry group = (GroupLayerEntry) layered.entries().get(1);
        assertEquals(
                new AttributeEntry("happy", false, "assets/images/happy.png", "points > 5"),
                group.attributes().get(0));

        ShowStatement show = (ShowStatement) ast.labels().get(0).statements().get(0);
        assertEquals("sprite_a", show.tag());
        assertEquals("right", show.positionExpr());
        assertEquals("dissolve(0.2)", show.transitionExpr());

        MenuStatement menu = (MenuStatement) ast.labels().get(0).statements().get(1);
        assertEquals(4.5, menu.timeoutSeconds(), 1e-6);
        assertEquals("timed_out", menu.timeoutJumpLabel());
        assertEquals("Sage \"A -> B\" jetzt", menu.choices().get(0).text());
        assertEquals("points >= 1", menu.choices().get(0).conditionExpr());
        assertEquals("chosen_one", menu.choices().get(0).inlineJumpTarget());
    }

    @Test
    void throwsVJavaParseExceptionOnInvalidIndentationAndSyntaxErrors() {
        String badDedent = """
                label start:
                    lena "Line 1"
                  lena "Bad dedent"
                """;
        VJavaParseException dedentEx = assertThrows(
                VJavaParseException.class,
                () -> VJavaLexer.tokenize(badDedent, "bad_indent.vjava")
        );
        assertEquals("bad_indent.vjava", dedentEx.getSourceFile());
        assertEquals(3, dedentEx.getLineNumber());
        assertTrue(dedentEx.getMessage().contains("bad_indent.vjava:3"));

        String unterminatedJava = """
                :: Kode für VN: Broken @->{
                    int x = 1;
                """;
        VJavaParseException javaEx = assertThrows(
                VJavaParseException.class,
                () -> VJavaLexer.tokenize(unterminatedJava, "broken_java.vjava")
        );
        assertEquals(1, javaEx.getLineNumber());

        String orphanedElse = """
                label start:
                    else:
                        return
                """;
        VJavaParseException elseEx = assertThrows(
                VJavaParseException.class,
                () -> VJavaParser.parse(Path.of("orphan.vjava"), orphanedElse)
        );
        assertNotNull(elseEx.getMessage());
        assertEquals(2, elseEx.getLineNumber());
    }
}
