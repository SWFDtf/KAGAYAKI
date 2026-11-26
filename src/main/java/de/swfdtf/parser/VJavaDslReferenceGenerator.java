package de.swfdtf.parser;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates an authoritative Markdown reference for every permitted {@link VJavaAst.Statement}
 * in the KAGAYAKI {@code .vjava} DSL and verifies that every documented code example parses cleanly.
 */
public final class VJavaDslReferenceGenerator {

    /**
     * Documentation entry for a single {@link VJavaAst.Statement} subtype.
     *
     * @param statementName AST record simple name
     * @param syntax        canonical DSL syntax signature
     * @param description   concise behavioral summary
     * @param exampleScript valid multi-line {@code .vjava} snippet using the command
     */
    public record DslEntry(String statementName, String syntax, String description, String exampleScript) {
    }

    private static final Map<Class<? extends VJavaAst.Statement>, DslEntry> ENTRIES = createEntries();

    private VJavaDslReferenceGenerator() {
    }

    /**
     * Returns an unmodifiable map covering all permitted {@link VJavaAst.Statement} classes.
     *
     * @return immutable map of statement class to reference entry
     */
    public static Map<Class<? extends VJavaAst.Statement>, DslEntry> getEntries() {
        return ENTRIES;
    }

    /**
     * Renders the complete Markdown reference document covering all permitted DSL statements.
     *
     * @return Markdown documentation string
     */
    public static String generateMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# KAGAYAKI `.vjava` DSL Reference\n\n");
        for (DslEntry entry : ENTRIES.values()) {
            sb.append("## `").append(entry.statementName()).append("`\n\n");
            sb.append("- **Syntax:** `").append(entry.syntax()).append("`\n");
            sb.append("- **Description:** ").append(entry.description()).append("\n\n");
            sb.append("```vjava\n").append(entry.exampleScript().stripTrailing()).append("\n```\n\n");
        }
        return sb.toString();
    }

    /**
     * Verifies at runtime that every permitted subclass of {@link VJavaAst.Statement} has a
     * documented entry whose example script parses without errors via {@link VJavaParser}.
     *
     * @return number of verified statement types
     */
    public static int verifyAllExamplesParse() {
        Class<?>[] permitted = VJavaAst.Statement.class.getPermittedSubclasses();
        if (permitted == null || permitted.length == 0) {
            throw new IllegalStateException("No permitted subclasses found on VJavaAst.Statement");
        }
        for (Class<?> cls : permitted) {
            DslEntry entry = ENTRIES.get(cls);
            if (entry == null) {
                throw new IllegalStateException("Missing DSL reference entry for: " + cls.getSimpleName());
            }
            String wrapped = entry.exampleScript().contains("label ")
                    ? entry.exampleScript()
                    : "label ref_check:\n" + indentLines(entry.exampleScript()) + "\n";
            VJavaParser.parse(Path.of("dsl_ref_" + cls.getSimpleName() + ".vjava"), wrapped);
        }
        return permitted.length;
    }

    private static String indentLines(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append("    ").append(line).append('\n');
        }
        return sb.toString();
    }

    private static void reg(
            Map<Class<? extends VJavaAst.Statement>, DslEntry> map,
            Class<? extends VJavaAst.Statement> cls,
            String syntax,
            String desc,
            String example
    ) {
        map.put(cls, new DslEntry(cls.getSimpleName(), syntax, desc, example));
    }

    private static Map<Class<? extends VJavaAst.Statement>, DslEntry> createEntries() {
        Map<Class<? extends VJavaAst.Statement>, DslEntry> m = new LinkedHashMap<>();
        reg(m, VJavaAst.JavaBlockStatement.class, "@->{ ... }<<", "Executes an embedded Java 21 block.",
                "@->{\nstate.set(\"ready\", true);\n}<<");
        reg(m, VJavaAst.SceneStatement.class, "scene <bgTag> [with <trans>]", "Sets the stage background.",
                "scene bg_forest with fade");
        reg(m, VJavaAst.ShowStatement.class, "show <tag> [attrs] [at <pos>]", "Displays a sprite.",
                "show lena happy at center");
        reg(m, VJavaAst.HideStatement.class, "hide <tag> [with <trans>]", "Hides an active sprite.",
                "hide lena with dissolve");
        reg(m, VJavaAst.SayStatement.class, "[speaker] \"<text>\"", "Displays a dialogue line.",
                "lena \"Hello world!\"");
        reg(m, VJavaAst.JumpStatement.class, "jump <label>", "Jumps unconditionally to a label.",
                "jump next_scene");
        reg(m, VJavaAst.CallStatement.class, "call <label>", "Calls a subroutine label.",
                "call helper_scene");
        reg(m, VJavaAst.ReturnStatement.class, "return [expr]", "Returns from a subroutine or label.",
                "return");
        reg(m, VJavaAst.PlayStatement.class, "play <ch> \"<path>\" [loop]", "Plays audio on a channel.",
                "play music \"assets/audio/bgm.wav\" loop");
        reg(m, VJavaAst.QueueStatement.class, "queue <ch> \"<p1>\" \"<p2>\"", "Queues sequential audio.",
                "queue music \"assets/audio/a.wav\" \"assets/audio/b.wav\"");
        reg(m, VJavaAst.StopStatement.class, "stop <ch> [fadeout <s>]", "Stops audio on a channel.",
                "stop music fadeout 0.5");
        reg(m, VJavaAst.VoiceStatement.class, "voice \"<path>\"", "Attaches voice audio to next line.",
                "voice \"assets/audio/voice/line1.wav\"");
        reg(m, VJavaAst.NvlStatement.class, "nvl show|hide|clear", "Controls NVL narrative mode.",
                "nvl show");
        reg(m, VJavaAst.BlockRollbackStatement.class, "block_rollback", "Prevents rollback past point.",
                "block_rollback");
        reg(m, VJavaAst.InputStatement.class, "input <var> \"<prompt>\"", "Prompts player text input.",
                "input player_name \"Enter name:\"");
        reg(m, VJavaAst.MenuStatement.class, "menu:", "Presents interactive player choices.",
                "menu:\n    \"Continue\" -> jump next");
        reg(m, VJavaAst.IfStatement.class, "if <cond>: ... else:", "Conditional branch control flow.",
                "if score > 5:\n    \"High score\"");
        reg(m, VJavaAst.SetVarStatement.class, "$var = <expr>", "Assigns a runtime state variable.",
                "$score = 10");
        reg(m, VJavaAst.PauseStatement.class, "pause [seconds]", "Pauses execution temporarily.",
                "pause 0.5");
        reg(m, VJavaAst.WithStatement.class, "with <transition>", "Triggers a stage transition.",
                "with vpunch");
        reg(m, VJavaAst.WindowStatement.class, "window show|hide|auto", "Controls dialogue UI visibility.",
                "window hide");
        reg(m, VJavaAst.MoveStatement.class, "move <tag> [to|rel] <x> <y>", "Moves a sprite.",
                "move player to 400 500 duration 0.5 ease spring");
        reg(m, VJavaAst.MovePathStatement.class, "move_path <tag> x1 y1 x2 y2 x3 y3",
                "Moves a sprite along a Bezier path.",
                "move_path star 100 100 300 50 500 200 duration 1.0");
        reg(m, VJavaAst.RotateStatement.class, "rotate <tag> to <deg>", "Animates shortest-arc rotation.",
                "rotate star to 90 duration 0.5");
        reg(m, VJavaAst.ScaleStatement.class, "scale <tag> to <zx> <zy>", "Animates non-uniform scale.",
                "scale star to 1.5 1.5 duration 0.4");
        reg(m, VJavaAst.CollisionMaskStatement.class, "collision_mask <tag> <layer> collides_with ...",
                "Filters selective collision pairs.",
                "collision_mask player hero collides_with pickup");
        reg(m, VJavaAst.HitboxStatement.class, "hitbox <tag> circle <r> | rect <w> <h>",
                "Sets a custom collision hitbox shape.",
                "hitbox coin circle 24");
        reg(m, VJavaAst.OnOverlapStatement.class, "on_overlap <a > <b> <enter|stay|exit> <label>",
                "Registers an overlap lifecycle subroutine trigger.",
                "on_overlap player zone enter on_zone_enter");
        reg(m, VJavaAst.SolidPlatformStatement.class, "solid_platform <tag> [one_way]",
                "Marks a sprite as a walkable platform.",
                "solid_platform ledge one_way");
        reg(m, VJavaAst.ParallelStatement.class, "parallel:", "Runs child statements concurrently.",
                "parallel:\n    move a to 100 200 duration 0.5\n    scale a to 1.2 duration 0.5");
        reg(m, VJavaAst.WaitMoveStatement.class, "wait_move <tag>", "Waits until active tween ends.",
                "wait_move lena");
        reg(m, VJavaAst.CameraFollowStatement.class, "camera follow <tag> [smooth <f>]",
                "Tracks a target sprite with the camera.",
                "camera follow player smooth 5.0");
        reg(m, VJavaAst.TriggerZoneStatement.class, "trigger_zone <id> rect x y w h call|jump <lbl>",
                "Defines an invisible trigger rectangle.",
                "trigger_zone door rect 100 200 80 120 call open_door");
        reg(m, VJavaAst.WasdInitStatement.class, "wasd_init <tag> [speed <s>] [auto_flip <bool>]",
                "Binds keyboard/analog movement to a sprite.",
                "wasd_init player speed 280 auto_flip true");
        reg(m, VJavaAst.WasdStopStatement.class, "wasd_stop [tag]", "Disables WASD movement.",
                "wasd_stop player");
        reg(m, VJavaAst.PhysicsInitStatement.class, "physics_init <tag> gravity <g> jump_force <f> ground_y <y>",
                "Enables 2D gravity and jumping.",
                "physics_init player gravity 980 jump_force 450 ground_y 800");
        reg(m, VJavaAst.PhysicsStopStatement.class, "physics_stop [tag]", "Disables 2D physics.",
                "physics_stop player");
        reg(m, VJavaAst.OnCollideStatement.class, "on_collide <tagA> <tagB> jump <label>",
                "Jumps when two hitboxes overlap.",
                "on_collide player goal jump victory");
        reg(m, VJavaAst.MatchStatement.class, "match <expr>: case ... default:",
                "Pattern-matches a value across cases.",
                "match rank:\n    case 1:\n        \"Gold\"\n    default:\n        \"Silver\"");
        reg(m, VJavaAst.PatrolStatement.class, "patrol <tag> between x1 y1 x2 y2 [speed <s>]",
                "Moves a sprite continuously between waypoints.",
                "patrol guard between 100 500 500 500 speed 120");
        reg(m, VJavaAst.FollowStatement.class, "follow <tag> target <target> [speed <s>]",
                "Pursues a target sprite with hysteresis.",
                "follow pet target player speed 160 stop_radius 50");
        reg(m, VJavaAst.AfterTimerStatement.class, "after <sec> jump|call <label>",
                "Fires a delayed jump or subroutine call.",
                "after 2.0 jump time_up");
        reg(m, VJavaAst.CameraClampStatement.class, "camera clamp x1 y1 x2 y2",
                "Restricts camera viewport bounds.",
                "camera clamp 0 0 1280 720");
        reg(m, VJavaAst.OnInteractStatement.class, "on_interact <tag> <dist> jump|call <label>",
                "Triggers interaction when E/Enter pressed in range.",
                "on_interact chest 90 call open_chest");
        reg(m, VJavaAst.RepeatStatement.class, "repeat <count>:", "Repeats a block a fixed number of times.",
                "repeat 3:\n    $count += 1");
        reg(m, VJavaAst.WhileStatement.class, "while <cond>:", "Repeats while condition holds (guarded).",
                "while $hp > 0:\n    $hp -= 10");
        reg(m, VJavaAst.AnimStateStatement.class, "anim_state <tag> idle <a> walk <b> jump <c>",
                "Maps velocity/grounded state to sprite attributes.",
                "anim_state player idle stand walk run jump air");
        reg(m, VJavaAst.ColliderWallStatement.class, "collider wall <id> x y w h",
                "Adds solid blocking collider rectangle(s).",
                "collider wall brick 300 200 64 200");
        reg(m, VJavaAst.ShakeLayerStatement.class, "shake <layer> amp <px> dur <s>",
                "Shakes a scene layer without shifting hitboxes.",
                "shake master amp 18 dur 0.3");
        reg(m, VJavaAst.KnockbackStatement.class, "knockback <tag> vx <x> vy <y> [invuln <s>]",
                "Applies decaying knockback and invulnerability.",
                "knockback player vx -220 vy -120 invuln 0.6");
        reg(m, VJavaAst.ClimbZoneStatement.class, "climb_zone <id> rect x y w h",
                "Defines a gravity-free ladder/climb zone.",
                "climb_zone ladder rect 400 200 60 300");
        reg(m, VJavaAst.MovingPlatformStatement.class, "moving_platform <tag> between x1 y1 x2 y2",
                "Creates a moving platform carrying riders.",
                "moving_platform lift between 200 600 600 600 speed 100");
        reg(m, VJavaAst.AttractStatement.class, "attract <tag> target <player> radius <r>",
                "Pulls pickups magnetically inside radius.",
                "attract coin target player radius 140 speed 260");
        reg(m, VJavaAst.SpawnStatement.class, "spawn <tag> <asset> [at <x> <y>]",
                "Spawns a dynamic stage sprite.",
                "spawn spark \"assets/images/spark.png\" at 500 500");
        reg(m, VJavaAst.DespawnStatement.class, "despawn <tag>", "Despawns a sprite cleanly.",
                "despawn spark");
        reg(m, VJavaAst.OnTweenEndStatement.class, "on_tween_end <tag> jump|call <label>",
                "Triggers when a sprite motion tween completes.",
                "on_tween_end actor jump after_walk");
        reg(m, VJavaAst.OnEventStatement.class, "on_event \"<name>\" call|jump <label>",
                "Listens for an EngineEventBus event.",
                "on_event \"door_unlocked\" call handle_unlock");
        reg(m, VJavaAst.SpawnCutsceneStatement.class, "spawn_cutscene <label>",
                "Runs a background cutscene coroutine.",
                "spawn_cutscene bg_clouds");
        reg(m, VJavaAst.RandomStatement.class, "random: weight <w>:",
                "Chooses a weighted random branch.",
                "random:\n    weight 70:\n        $drop = \"common\"\n    weight 30:\n        $drop = \"rare\"");
        reg(m, VJavaAst.SetBlockStatement.class, "set:", "Assigns multiple variables atomically.",
                "set:\n    gold = 100\n    rank = \"A\"");
        reg(m, VJavaAst.LocalVarStatement.class, "local $tmp = <expr>",
                "Declares a call-frame-scoped local variable.",
                "local $tmp = 42");
        reg(m, VJavaAst.TimeScaleStatement.class, "time_scale <factor>", "Scales motion/physics time.",
                "time_scale 0.5");
        reg(m, VJavaAst.AttachStatement.class, "attach <child> to <parent> [offset <x> <y>]",
                "Parents a child sprite onto another sprite.",
                "attach hat to player offset 0 -48");
        reg(m, VJavaAst.YSortStatement.class, "ysort <true|false>",
                "Sorts master sprites dynamically by Y coordinate.",
                "ysort true");
        reg(m, VJavaAst.PointClickInitStatement.class, "point_click_init <tag> [speed <s>]",
                "Enables click-to-walk stage navigation.",
                "point_click_init player speed 240");
        reg(m, VJavaAst.SurfaceFrictionStatement.class, "surface_friction <id> rect x y w h factor <f>",
                "Multiplies acceleration/friction inside a floor zone.",
                "surface_friction ice rect 100 600 400 80 factor 0.4");
        reg(m, VJavaAst.ImpulseStatement.class, "impulse <tag> vx <x> vy <y>",
                "Applies an instantaneous launch velocity impulse.",
                "impulse player vx 0 vy -820");
        return java.util.Collections.unmodifiableMap(m);
    }
}
