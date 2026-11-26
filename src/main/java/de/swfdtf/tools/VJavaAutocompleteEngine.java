package de.swfdtf.tools;

import de.swfdtf.parser.VJavaAst;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Context-aware code completion engine for {@code .vjava} scripts proposing DSL keywords,
 * declared character/image tags, labels, transforms, and real project asset paths ({@code #0112}).
 */
public final class VJavaAutocompleteEngine {

    private static final List<String> DSL_KEYWORDS = List.of(
            "label", "scene", "show", "hide", "with", "play music", "play sound",
            "queue", "stop", "voice", "jump", "call", "return", "menu", "if",
            "elif", "else", "match", "define", "default", "image", "layeredimage",
            "transform", "move", "move_by", "move_path", "rotate", "scale",
            "wasd_init", "wasd_stop", "physics_init", "physics_stop",
            "on_collide", "on_interact", "patrol", "follow", "after", "repeat",
            "while", "spawn", "despawn", "camera", "trigger_zone", "collider",
            "shake", "knockback", "climb_zone", "moving_platform", "attract",
            "time_scale", "attach", "ysort", "point_click_init", "surface_friction",
            "impulse", "random", "set", "local", "on_event", "spawn_cutscene"
    );

    /**
     * Single completion suggestion returned by {@link #suggest}.
     *
     * @param text     insertion candidate text
     * @param category semantic category ({@code keyword}, {@code tag}, {@code asset}, {@code label}, etc.)
     * @param detail   short contextual detail string
     */
    public record Suggestion(String text, String category, String detail) {
    }

    /** Creates a new {@code VJavaAutocompleteEngine}. */
    public VJavaAutocompleteEngine() {
    }

    /**
     * Computes ranked completion suggestions for the current line prefix before cursor.
     *
     * @param project    parsed project AST (nullable for keyword-only suggestions)
     * @param linePrefix raw text on the current line up to cursor
     * @return immutable ranked list of matching {@link Suggestion} entries
     */
    public List<Suggestion> suggest(VJavaAst.ProjectAst project, String linePrefix) {
        Objects.requireNonNull(linePrefix, "linePrefix must not be null");
        String trimmedLeft = linePrefix.stripLeading();
        String lower = trimmedLeft.toLowerCase(Locale.ROOT);
        List<Suggestion> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        if (project != null) {
            if (lower.startsWith("show ") || lower.startsWith("scene ") || lower.startsWith("spawn ")) {
                String token = extractTrailingToken(trimmedLeft);
                if (lower.contains(" at ")) {
                    addBuiltInAndCustomTransforms(project, token, results, seen);
                } else {
                    addMatchingImageTagsAndAssets(project, token, results, seen);
                }
                return List.copyOf(results);
            }
            if (lower.startsWith("play music ") || lower.startsWith("play sound ")
                    || lower.startsWith("play ambient ") || lower.startsWith("voice ")) {
                String token = stripLeadingQuotes(extractTrailingToken(trimmedLeft));
                addMatchingAudioAssets(project, token, results, seen);
                return List.copyOf(results);
            }
            if (lower.startsWith("jump ") || lower.startsWith("call ") || lower.contains("-> jump ")) {
                String token = extractTrailingToken(trimmedLeft);
                for (String label : project.labels().keySet()) {
                    addIfMatches(label, token, "label", "label " + label, results, seen);
                }
                return List.copyOf(results);
            }
            String token = extractTrailingToken(trimmedLeft);
            if (!token.isEmpty()) {
                for (String kw : DSL_KEYWORDS) {
                    addIfMatches(kw, token, "keyword", "DSL command", results, seen);
                }
                for (String charId : project.characters().keySet()) {
                    addIfMatches(charId, token, "character", "define Character", results, seen);
                }
                for (String label : project.labels().keySet()) {
                    addIfMatches(label, token, "label", "Script label", results, seen);
                }
                return List.copyOf(results);
            }
        }

        String token = extractTrailingToken(trimmedLeft);
        for (String kw : DSL_KEYWORDS) {
            addIfMatches(kw, token, "keyword", "DSL command", results, seen);
        }
        return List.copyOf(results);
    }

    private static void addBuiltInAndCustomTransforms(
            VJavaAst.ProjectAst project,
            String token,
            List<Suggestion> results,
            Set<String> seen
    ) {
        for (String builtin : List.of("left", "center", "truecenter", "right", "offscreenleft", "offscreenright")) {
            addIfMatches(builtin, token, "transform", "built-in position", results, seen);
        }
        for (String name : project.transforms().keySet()) {
            addIfMatches(name, token, "transform", "custom transform", results, seen);
        }
    }

    private static void addMatchingImageTagsAndAssets(
            VJavaAst.ProjectAst project,
            String token,
            List<Suggestion> results,
            Set<String> seen
    ) {
        for (String key : project.images().keySet()) {
            addIfMatches(key, token, "image", "image tag", results, seen);
        }
        for (String layered : project.layeredImages().keySet()) {
            addIfMatches(layered, token, "layeredimage", "layered sprite", results, seen);
        }
        for (String asset : project.discoveredAssets()) {
            String low = asset.toLowerCase(Locale.ROOT);
            if (low.endsWith(".png") || low.endsWith(".webp") || low.endsWith(".jpg") || low.endsWith(".jpeg")) {
                addIfMatches(asset, token, "asset", "image asset", results, seen);
            }
        }
    }

    private static void addMatchingAudioAssets(
            VJavaAst.ProjectAst project,
            String token,
            List<Suggestion> results,
            Set<String> seen
    ) {
        for (String asset : project.discoveredAssets()) {
            String low = asset.toLowerCase(Locale.ROOT);
            if (low.endsWith(".wav") || low.endsWith(".ogg") || low.endsWith(".oga")) {
                addIfMatches("\"" + asset + "\"", token, "audio", "audio asset", results, seen);
            }
        }
    }

    private static void addIfMatches(
            String candidate,
            String prefix,
            String category,
            String detail,
            List<Suggestion> out,
            Set<String> seen
    ) {
        if (seen.contains(candidate)) {
            return;
        }
        String normCandidate = stripLeadingQuotes(candidate).toLowerCase(Locale.ROOT);
        String normPrefix = stripLeadingQuotes(prefix).toLowerCase(Locale.ROOT);
        if (normPrefix.isEmpty() || normCandidate.contains(normPrefix)) {
            seen.add(candidate);
            out.add(new Suggestion(candidate, category, detail));
        }
    }

    private static String extractTrailingToken(String text) {
        if (text.isEmpty() || Character.isWhitespace(text.charAt(text.length() - 1))) {
            return "";
        }
        int idx = text.length() - 1;
        while (idx >= 0 && !Character.isWhitespace(text.charAt(idx))) {
            idx--;
        }
        return text.substring(idx + 1);
    }

    private static String stripLeadingQuotes(String s) {
        String t = s.trim();
        if (t.startsWith("\"") || t.startsWith("'")) {
            t = t.substring(1);
        }
        if (t.endsWith("\"") || t.endsWith("'")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
