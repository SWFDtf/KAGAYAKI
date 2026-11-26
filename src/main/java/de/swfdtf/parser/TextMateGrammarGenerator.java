package de.swfdtf.parser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates an official TextMate syntax-highlighting grammar ({@code .tmLanguage.json})
 * for KAGAYAKI {@code .vjava} hybrid scripts, covering all narrative, control-flow,
 * stage motion, physics, collision, and embedded Java keywords.
 */
public final class TextMateGrammarGenerator {

    /** Canonical keyword list recognized in {@code .vjava} scripts. */
    public static final List<String> VJAVA_KEYWORDS = List.of(
            "label", "jump", "call", "return", "menu", "if", "elif", "else",
            "match", "case", "default", "repeat", "while", "set", "local",
            "random", "weight", "parallel", "show", "hide", "scene", "with",
            "window", "nvl", "play", "queue", "stop", "voice", "pause",
            "input", "block_rollback", "define", "image", "layeredimage",
            "transform", "screen", "translate", "config", "include", "macro",
            "move", "move_by", "move_path", "rotate", "scale",
            "wasd_init", "wasd_stop", "physics_init", "physics_stop",
            "on_collide", "on_overlap", "solid_platform", "collision_mask",
            "hitbox", "wait_move", "camera", "trigger_zone", "patrol",
            "follow", "after", "on_interact", "collider", "shake",
            "knockback", "climb_zone", "moving_platform", "attract",
            "spawn", "despawn", "on_tween_end", "on_event", "spawn_cutscene",
            "time_scale", "attach", "ysort", "point_click_init",
            "surface_friction", "impulse", "anim_state", "pass"
    );

    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private TextMateGrammarGenerator() {
    }

    /**
     * Builds the complete {@code vjava.tmLanguage.json} document as a formatted JSON string.
     *
     * @return valid TextMate grammar JSON string
     */
    public static String generateTmLanguageJson() {
        String keywordRegex = "\\b(" + String.join("|", VJAVA_KEYWORDS) + ")\\b";

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("$schema", "https://raw.githubusercontent.com/martinring/tmlanguage/master/tmlanguage.json");
        root.put("name", "KAGAYAKI vJava");
        root.put("scopeName", "source.vjava");
        root.put("fileTypes", List.of("vjava"));

        Map<String, Object> commentRule = new LinkedHashMap<>();
        commentRule.put("name", "comment.line.double-slash.vjava");
        commentRule.put("match", "//.*$|#.*$");

        Map<String, Object> javaBlockRule = new LinkedHashMap<>();
        javaBlockRule.put("name", "meta.embedded.block.java.vjava");
        javaBlockRule.put("begin", "@->\\{");
        javaBlockRule.put("end", "\\}<<");
        javaBlockRule.put("patterns", List.of(Map.of("include", "source.java")));

        Map<String, Object> doubleStringRule = new LinkedHashMap<>();
        doubleStringRule.put("name", "string.quoted.double.vjava");
        doubleStringRule.put("begin", "\"");
        doubleStringRule.put("end", "\"");
        doubleStringRule.put("patterns", List.of(
                Map.of("name", "constant.character.escape.vjava", "match", "\\\\.")
        ));

        Map<String, Object> singleStringRule = new LinkedHashMap<>();
        singleStringRule.put("name", "string.quoted.single.vjava");
        singleStringRule.put("begin", "'");
        singleStringRule.put("end", "'");

        Map<String, Object> variableRule = new LinkedHashMap<>();
        variableRule.put("name", "variable.other.vjava");
        variableRule.put("match", "\\$[A-Za-z_][A-Za-z0-9_]*");

        Map<String, Object> numberRule = new LinkedHashMap<>();
        numberRule.put("name", "constant.numeric.vjava");
        numberRule.put("match", "\\b-?[0-9]+(\\.[0-9]+)?\\b");

        Map<String, Object> keywordRule = new LinkedHashMap<>();
        keywordRule.put("name", "keyword.control.vjava");
        keywordRule.put("match", keywordRegex);

        root.put("patterns", List.of(
                javaBlockRule,
                commentRule,
                doubleStringRule,
                singleStringRule,
                variableRule,
                numberRule,
                keywordRule
        ));
        return PRETTY_GSON.toJson(root);
    }
}
