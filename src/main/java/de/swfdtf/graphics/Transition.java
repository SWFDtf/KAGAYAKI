package de.swfdtf.graphics;

import de.swfdtf.math.Easing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable descriptor for scene transitions in KAGAYAKI, supporting standard wipes, slides,
 * pushes, irises, blinds, squares, pixellation, color fades, grayscale mask dissolves
 * ({@code ImageDissolve}), and chained {@code MultipleTransition} sequences.
 *
 * <p>Instances may be constructed via typed factory methods ({@link #dissolve(float)},
 * {@link #fade(float, String)}, {@link #imageDissolve(String, float, float, boolean)},
 * {@link #multiple(List)}, etc.) or parsed from {@code .vjava} script expressions via
 * {@link #parse(String)}.</p>
 */
public final class Transition {

    /**
     * Supported transition effect categories.
     */
    public enum Kind {
        /** None option. */
        NONE,
        /** Dissolve option. */
        DISSOLVE,
        /** Fade option. */
        FADE,
        /** Pixellate option. */
        PIXELLATE,
        /** Move option. */
        MOVE,
        /** Ease option. */
        EASE,
        /** Wipe Left option. */
        WIPE_LEFT,
        /** Wipe Right option. */
        WIPE_RIGHT,
        /** Wipe Up option. */
        WIPE_UP,
        /** Wipe Down option. */
        WIPE_DOWN,
        /** Slide Left option. */
        SLIDE_LEFT,
        /** Slide Right option. */
        SLIDE_RIGHT,
        /** Push Left option. */
        PUSH_LEFT,
        /** Push Right option. */
        PUSH_RIGHT,
        /** Iris In option. */
        IRIS_IN,
        /** Iris Out option. */
        IRIS_OUT,
        /** Blinds option. */
        BLINDS,
        /** Squares option. */
        SQUARES,
        /** Image Dissolve option. */
        IMAGE_DISSOLVE,
        /** Multiple option. */
        MULTIPLE
    }

    /** Default transition duration in seconds when omitted from a script expression. */
    public static final float DEFAULT_DURATION_SECONDS = 0.5f;

    /** Default hex fade color ({@code #000000}). */
    public static final String DEFAULT_FADE_COLOR_HEX = "#000000";

    /** Default edge ramp softness in 8-bit grayscale steps for {@code IMAGE_DISSOLVE}. */
    public static final float DEFAULT_RAMP_LENGTH = 8.0f;

    /** Default slice/grid count for {@code BLINDS} and {@code SQUARES}. */
    public static final int DEFAULT_SLICE_COUNT = 16;

    private static final Transition NONE_INSTANCE = new Transition(
            Kind.NONE,
            0.0f,
            DEFAULT_FADE_COLOR_HEX,
            null,
            DEFAULT_RAMP_LENGTH,
            false,
            DEFAULT_SLICE_COUNT,
            List.of(),
            Easing.EaseType.LINEAR
    );

    private final Kind kind;
    private final float durationSeconds;
    private final String fadeColorHex;
    private final String maskImagePath;
    private final float rampLength;
    private final boolean reverseMask;
    private final int sliceCount;
    private final List<Transition> subTransitions;
    private final Easing.EaseType easeType;

    private Transition(
            Kind kind,
            float durationSeconds,
            String fadeColorHex,
            String maskImagePath,
            float rampLength,
            boolean reverseMask,
            int sliceCount,
            List<Transition> subTransitions,
            Easing.EaseType easeType
    ) {
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.durationSeconds = Math.max(0.0f, durationSeconds);
        this.fadeColorHex = (fadeColorHex != null && !fadeColorHex.isBlank())
                ? fadeColorHex.trim()
                : DEFAULT_FADE_COLOR_HEX;
        this.maskImagePath = (maskImagePath != null && !maskImagePath.isBlank())
                ? maskImagePath.trim()
                : null;
        this.rampLength = Math.max(0.0f, rampLength);
        this.reverseMask = reverseMask;
        this.sliceCount = Math.max(1, sliceCount);
        this.subTransitions = subTransitions != null && !subTransitions.isEmpty()
                ? Collections.unmodifiableList(new ArrayList<>(subTransitions))
                : List.of();
        this.easeType = easeType != null ? easeType : Easing.EaseType.EASE;
    }

    // ---- Factory Methods ----

    /**
     * Executes {@code none}.
     *
     * @return the resulting value
     */
    public static Transition none() {
        return NONE_INSTANCE;
    }

    /**
     * Executes {@code dissolve}.
     *
     * @param durationSeconds the durationSeconds argument
     * @return the resulting value
     */
    public static Transition dissolve(float durationSeconds) {
        return simple(Kind.DISSOLVE, durationSeconds);
    }

    /**
     * Executes {@code fade}.
     *
     * @param durationSeconds the durationSeconds argument
     * @return the resulting value
     */
    public static Transition fade(float durationSeconds) {
        return fade(durationSeconds, DEFAULT_FADE_COLOR_HEX);
    }

    /**
     * Executes {@code fade}.
     *
     * @param durationSeconds the durationSeconds argument
     * @param fadeColorHex the fadeColorHex argument
     * @return the resulting value
     */
    public static Transition fade(float durationSeconds, String fadeColorHex) {
        String hex = normalizeColorHex(fadeColorHex);
        return new Transition(
                Kind.FADE,
                durationSeconds,
                hex,
                null,
                DEFAULT_RAMP_LENGTH,
                false,
                DEFAULT_SLICE_COUNT,
                List.of(),
                Easing.EaseType.LINEAR
        );
    }

    /**
     * Executes {@code pixellate}.
     *
     * @param durationSeconds the durationSeconds argument
     * @return the resulting value
     */
    public static Transition pixellate(float durationSeconds) {
        return simple(Kind.PIXELLATE, durationSeconds);
    }

    /**
     * Executes {@code move}.
     *
     * @param durationSeconds the durationSeconds argument
     * @return the resulting value
     */
    public static Transition move(float durationSeconds) {
        return new Transition(
                Kind.MOVE,
                durationSeconds,
                DEFAULT_FADE_COLOR_HEX,
                null,
                DEFAULT_RAMP_LENGTH,
                false,
                DEFAULT_SLICE_COUNT,
                List.of(),
                Easing.EaseType.EASE_IN_OUT
        );
    }

    /**
     * Executes {@code ease}.
     *
     * @param durationSeconds the durationSeconds argument
     * @return the resulting value
     */
    public static Transition ease(float durationSeconds) {
        return ease(durationSeconds, Easing.EaseType.EASE);
    }

    /**
     * Executes {@code ease}.
     *
     * @param durationSeconds the durationSeconds argument
     * @param easeType the easeType argument
     * @return the resulting value
     */
    public static Transition ease(float durationSeconds, Easing.EaseType easeType) {
        return new Transition(
                Kind.EASE,
                durationSeconds,
                DEFAULT_FADE_COLOR_HEX,
                null,
                DEFAULT_RAMP_LENGTH,
                false,
                DEFAULT_SLICE_COUNT,
                List.of(),
                easeType
        );
    }

    /**
     * Executes {@code wipeLeft}.
     *
     * @param durationSeconds the durationSeconds argument
     * @return the resulting value
     */
    public static Transition wipeLeft(float durationSeconds) {
        return simple(Kind.WIPE_LEFT, durationSeconds);
    }

    /**
     * Executes {@code wipeRight}.
     *
     * @param durationSeconds the durationSeconds argument
     * @return the resulting value
     */
    public static Transition wipeRight(float durationSeconds) {
        return simple(Kind.WIPE_RIGHT, durationSeconds);
    }

    /**
     * Executes {@code wipeUp}.
     *
     * @param durationSeconds the durationSeconds argument
     * @return the resulting value
     */
    public static Transition wipeUp(float durationSeconds) {
        return simple(Kind.WIPE_UP, durationSeconds);
    }

    /**
     * Executes {@code wipeDown}.
     *
     * @param durationSeconds the durationSeconds argument
     * @return the resulting value
     */
    public static Transition wipeDown(float durationSeconds) {
        return simple(Kind.WIPE_DOWN, durationSeconds);
    }

    /**
     * Executes {@code slideLeft}.
     *
     * @param durationSeconds the durationSeconds argument
     * @return the resulting value
     */
    public static Transition slideLeft(float durationSeconds) {
        return simple(Kind.SLIDE_LEFT, durationSeconds);
    }

    /**
     * Executes {@code slideRight}.
     *
     * @param durationSeconds the durationSeconds argument
     * @return the resulting value
     */
    public static Transition slideRight(float durationSeconds) {
        return simple(Kind.SLIDE_RIGHT, durationSeconds);
    }

    /**
     * Executes {@code pushLeft}.
     *
     * @param durationSeconds the durationSeconds argument
     * @return the resulting value
     */
    public static Transition pushLeft(float durationSeconds) {
        return simple(Kind.PUSH_LEFT, durationSeconds);
    }

    /**
     * Executes {@code pushRight}.
     *
     * @param durationSeconds the durationSeconds argument
     * @return the resulting value
     */
    public static Transition pushRight(float durationSeconds) {
        return simple(Kind.PUSH_RIGHT, durationSeconds);
    }

    /**
     * Executes {@code irisIn}.
     *
     * @param durationSeconds the durationSeconds argument
     * @return the resulting value
     */
    public static Transition irisIn(float durationSeconds) {
        return simple(Kind.IRIS_IN, durationSeconds);
    }

    /**
     * Executes {@code irisOut}.
     *
     * @param durationSeconds the durationSeconds argument
     * @return the resulting value
     */
    public static Transition irisOut(float durationSeconds) {
        return simple(Kind.IRIS_OUT, durationSeconds);
    }

    /**
     * Executes {@code blinds}.
     *
     * @param durationSeconds the durationSeconds argument
     * @param sliceCount the sliceCount argument
     * @return the resulting value
     */
    public static Transition blinds(float durationSeconds, int sliceCount) {
        return new Transition(
                Kind.BLINDS,
                durationSeconds,
                DEFAULT_FADE_COLOR_HEX,
                null,
                DEFAULT_RAMP_LENGTH,
                false,
                sliceCount,
                List.of(),
                Easing.EaseType.LINEAR
        );
    }

    /**
     * Executes {@code squares}.
     *
     * @param durationSeconds the durationSeconds argument
     * @param sliceCount the sliceCount argument
     * @return the resulting value
     */
    public static Transition squares(float durationSeconds, int sliceCount) {
        return new Transition(
                Kind.SQUARES,
                durationSeconds,
                DEFAULT_FADE_COLOR_HEX,
                null,
                DEFAULT_RAMP_LENGTH,
                false,
                sliceCount,
                List.of(),
                Easing.EaseType.LINEAR
        );
    }

    /**
     * Executes {@code imageDissolve}.
     *
     * @param maskImagePath the maskImagePath argument
     * @param durationSeconds the durationSeconds argument
     * @param rampLength the rampLength argument
     * @param reverseMask the reverseMask argument
     * @return the resulting value
     */
    public static Transition imageDissolve(
            String maskImagePath,
            float durationSeconds,
            float rampLength,
            boolean reverseMask
    ) {
        if (maskImagePath == null || maskImagePath.isBlank()) {
            throw new IllegalArgumentException("maskImagePath must not be null or blank");
        }
        return new Transition(
                Kind.IMAGE_DISSOLVE,
                durationSeconds,
                DEFAULT_FADE_COLOR_HEX,
                maskImagePath,
                rampLength,
                reverseMask,
                DEFAULT_SLICE_COUNT,
                List.of(),
                Easing.EaseType.LINEAR
        );
    }

    /**
     * Executes {@code multiple}.
     *
     * @param subTransitions the subTransitions argument
     * @return the resulting value
     */
    public static Transition multiple(Transition... subTransitions) {
        Objects.requireNonNull(subTransitions, "subTransitions must not be null");
        return multiple(List.of(subTransitions));
    }

    /**
     * Executes {@code multiple}.
     *
     * @param subTransitions the subTransitions argument
     * @return the resulting value
     */
    public static Transition multiple(List<Transition> subTransitions) {
        Objects.requireNonNull(subTransitions, "subTransitions must not be null");
        List<Transition> cleaned = new ArrayList<>(subTransitions.size());
        float totalDuration = 0.0f;
        for (Transition t : subTransitions) {
            if (t != null) {
                cleaned.add(t);
                totalDuration += t.getDurationSeconds();
            }
        }
        return new Transition(
                Kind.MULTIPLE,
                totalDuration,
                DEFAULT_FADE_COLOR_HEX,
                null,
                DEFAULT_RAMP_LENGTH,
                false,
                DEFAULT_SLICE_COUNT,
                cleaned,
                Easing.EaseType.LINEAR
        );
    }

    private static Transition simple(Kind kind, float durationSeconds) {
        return new Transition(
                kind,
                durationSeconds,
                DEFAULT_FADE_COLOR_HEX,
                null,
                DEFAULT_RAMP_LENGTH,
                false,
                DEFAULT_SLICE_COUNT,
                List.of(),
                Easing.EaseType.LINEAR
        );
    }

    // ---- Expression Parser ----

    /**
     * Parses a declarative transition expression string into a {@link Transition} instance.
     *
     * <p>Supported forms include {@code "none"}, {@code "dissolve(0.8)"}, {@code "fade(0.5)"},
     * {@code "fade(0.5, #ffffff)"}, {@code "easein(0.4)"}, {@code "pixellate(0.6)"},
     * {@code "wipeleft(0.5)"}, {@code "wiperight(0.5)"}, {@code "wipeup(0.5)"}, {@code "wipedown(0.5)"},
     * {@code "slideleft(0.5)"}, {@code "slideright(0.5)"}, {@code "pushleft(0.5)"}, {@code "pushright(0.5)"},
     * {@code "irisin(0.5)"}, {@code "irisout(0.5)"}, {@code "blinds(0.6, 12)"}, {@code "squares(0.6, 16)"},
     * {@code "imagedissolve('mask.png', 1.0, ramplen=16)"}, and
     * {@code "multiple(fade(0.3), dissolve(0.5))"}.</p>
     *
     * @param expr transition expression (null, blank, or {@code "none"} returns {@link #none()})
     * @return parsed {@link Transition}
     * @throws IllegalArgumentException if the expression syntax or transition name is invalid
     */
    public static Transition parse(String expr) {
        if (expr == null) {
            return none();
        }
        String trimmed = expr.trim();
        if (trimmed.isEmpty() || "none".equalsIgnoreCase(trimmed)) {
            return none();
        }

        int openParen = trimmed.indexOf('(');
        String name;
        String argBody;
        if (openParen >= 0) {
            if (!trimmed.endsWith(")")) {
                throw new IllegalArgumentException("Unclosed parenthesis in transition expression: " + expr);
            }
            name = trimmed.substring(0, openParen).trim();
            argBody = trimmed.substring(openParen + 1, trimmed.length() - 1).trim();
        } else {
            name = trimmed;
            argBody = "";
        }

        String norm = name.toLowerCase(Locale.ROOT).replace("_", "");
        List<String> rawArgs = argBody.isEmpty() ? List.of() : splitTopLevelComma(argBody);

        return switch (norm) {
            case "none" -> none();
            case "dissolve" -> dissolve(extractDuration(rawArgs, 0, DEFAULT_DURATION_SECONDS, expr));
            case "fade" -> parseFade(rawArgs, expr);
            case "pixellate", "pixelate" -> pixellate(extractDuration(rawArgs, 0, DEFAULT_DURATION_SECONDS, expr));
            case "move" -> move(extractDuration(rawArgs, 0, DEFAULT_DURATION_SECONDS, expr));
            case "ease" -> ease(extractDuration(rawArgs, 0, DEFAULT_DURATION_SECONDS, expr), Easing.EaseType.EASE);
            case "easein" -> ease(extractDuration(rawArgs, 0, DEFAULT_DURATION_SECONDS, expr), Easing.EaseType.EASE_IN);
            case "easeout" ->
                ease(extractDuration(rawArgs, 0, DEFAULT_DURATION_SECONDS, expr), Easing.EaseType.EASE_OUT);
            case "easeinout" ->
                ease(extractDuration(rawArgs, 0, DEFAULT_DURATION_SECONDS, expr), Easing.EaseType.EASE_IN_OUT);
            case "wipeleft" -> wipeLeft(extractDuration(rawArgs, 0, DEFAULT_DURATION_SECONDS, expr));
            case "wiperight" -> wipeRight(extractDuration(rawArgs, 0, DEFAULT_DURATION_SECONDS, expr));
            case "wipeup" -> wipeUp(extractDuration(rawArgs, 0, DEFAULT_DURATION_SECONDS, expr));
            case "wipedown" -> wipeDown(extractDuration(rawArgs, 0, DEFAULT_DURATION_SECONDS, expr));
            case "slideleft" -> slideLeft(extractDuration(rawArgs, 0, DEFAULT_DURATION_SECONDS, expr));
            case "slideright" -> slideRight(extractDuration(rawArgs, 0, DEFAULT_DURATION_SECONDS, expr));
            case "pushleft" -> pushLeft(extractDuration(rawArgs, 0, DEFAULT_DURATION_SECONDS, expr));
            case "pushright" -> pushRight(extractDuration(rawArgs, 0, DEFAULT_DURATION_SECONDS, expr));
            case "irisin" -> irisIn(extractDuration(rawArgs, 0, DEFAULT_DURATION_SECONDS, expr));
            case "irisout" -> irisOut(extractDuration(rawArgs, 0, DEFAULT_DURATION_SECONDS, expr));
            case "blinds" -> parseSliced(Kind.BLINDS, rawArgs, expr);
            case "squares" -> parseSliced(Kind.SQUARES, rawArgs, expr);
            case "imagedissolve" -> parseImageDissolve(rawArgs, expr);
            case "multiple", "multipletransition" -> parseMultiple(argBody, rawArgs, expr);
            default -> throw new IllegalArgumentException("Unknown transition expression: " + expr);
        };
    }

    private static Transition parseFade(List<String> rawArgs, String expr) {
        List<String> positional = new ArrayList<>();
        String colorHex = DEFAULT_FADE_COLOR_HEX;

        for (String arg : rawArgs) {
            int eq = findTopLevelEquals(arg);
            if (eq > 0) {
                String k = arg.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String v = stripQuotes(arg.substring(eq + 1).trim());
                if ("color".equals(k)) {
                    colorHex = normalizeColorHex(v);
                } else if ("time".equals(k) || "duration".equals(k)) {
                    positional.add(0, v);
                }
            } else {
                positional.add(stripQuotes(arg));
            }
        }

        if (positional.isEmpty()) {
            return fade(DEFAULT_DURATION_SECONDS, colorHex);
        }
        if (positional.size() == 1) {
            return fade(parseFloatStrict(positional.get(0), expr), colorHex);
        }
        if (positional.size() == 2) {
            float dur = parseFloatStrict(positional.get(0), expr);
            String second = positional.get(1);
            if (isColorToken(second)) {
                return fade(dur, normalizeColorHex(second));
            }
            float inTime = parseFloatStrict(second, expr);
            return fade(dur + inTime, colorHex);
        }
        // Ren'Py Fade(out_time, hold_time, in_time, [color=...])
        float outT = parseFloatStrict(positional.get(0), expr);
        float holdT = parseFloatStrict(positional.get(1), expr);
        float inT = parseFloatStrict(positional.get(2), expr);
        if (positional.size() >= 4 && isColorToken(positional.get(3))) {
            colorHex = normalizeColorHex(positional.get(3));
        }
        return fade(outT + holdT + inT, colorHex);
    }

    private static Transition parseSliced(Kind kind, List<String> rawArgs, String expr) {
        float duration = DEFAULT_DURATION_SECONDS;
        int slices = DEFAULT_SLICE_COUNT;
        int posIdx = 0;

        for (String arg : rawArgs) {
            int eq = findTopLevelEquals(arg);
            if (eq > 0) {
                String k = arg.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String v = stripQuotes(arg.substring(eq + 1).trim());
                switch (k) {
                    case "time", "duration" -> duration = parseFloatStrict(v, expr);
                    case "slices", "count", "slicecount" -> slices = parseIntStrict(v, expr);
                    default -> {
                        // Ignore extra keyword metadata
                    }
                }
            } else {
                String val = stripQuotes(arg);
                if (posIdx == 0) {
                    duration = parseFloatStrict(val, expr);
                } else if (posIdx == 1) {
                    slices = parseIntStrict(val, expr);
                }
                posIdx++;
            }
        }

        return kind == Kind.BLINDS ? blinds(duration, slices) : squares(duration, slices);
    }

    private static Transition parseImageDissolve(List<String> rawArgs, String expr) {
        String maskPath = null;
        float duration = DEFAULT_DURATION_SECONDS;
        float rampLen = DEFAULT_RAMP_LENGTH;
        boolean reverse = false;
        int posIdx = 0;

        for (String arg : rawArgs) {
            int eq = findTopLevelEquals(arg);
            if (eq > 0) {
                String k = arg.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String v = stripQuotes(arg.substring(eq + 1).trim());
                switch (k) {
                    case "image", "mask", "maskimage" -> maskPath = v;
                    case "time", "duration" -> duration = parseFloatStrict(v, expr);
                    case "ramplen", "ramp", "ramplength" -> rampLen = parseFloatStrict(v, expr);
                    case "reverse", "reversemask" -> reverse = Boolean.parseBoolean(v);
                    default -> {
                        // Ignore unknown optional keywords
                    }
                }
            } else {
                String val = stripQuotes(arg);
                switch (posIdx) {
                    case 0 -> maskPath = val;
                    case 1 -> duration = parseFloatStrict(val, expr);
                    case 2 -> rampLen = parseFloatStrict(val, expr);
                    case 3 -> reverse = Boolean.parseBoolean(val);
                    default -> {
                        // Ignore extra positional args
                    }
                }
                posIdx++;
            }
        }

        if (maskPath == null || maskPath.isBlank()) {
            throw new IllegalArgumentException("imagedissolve requires a mask image path: " + expr);
        }
        return imageDissolve(maskPath, duration, rampLen, reverse);
    }

    private static Transition parseMultiple(String argBody, List<String> rawArgs, String expr) {
        List<String> items = rawArgs;
        if (items.size() == 1) {
            String single = argBody.trim();
            if (single.startsWith("[") && single.endsWith("]")) {
                String inside = single.substring(1, single.length() - 1).trim();
                items = inside.isEmpty() ? List.of() : splitTopLevelComma(inside);
            }
        }
        List<Transition> children = new ArrayList<>();
        for (String item : items) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()
                    || "true".equalsIgnoreCase(trimmed)
                    || "false".equalsIgnoreCase(trimmed)) {
                continue;
            }
            children.add(parse(trimmed));
        }
        if (children.isEmpty() && !rawArgs.isEmpty()) {
            throw new IllegalArgumentException("Invalid multiple transition arguments: " + expr);
        }
        return multiple(children);
    }

    private static float extractDuration(List<String> rawArgs, int positionalIndex, float fallback, String expr) {
        int pos = 0;
        for (String arg : rawArgs) {
            int eq = findTopLevelEquals(arg);
            if (eq > 0) {
                String k = arg.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String v = stripQuotes(arg.substring(eq + 1).trim());
                if ("time".equals(k) || "duration".equals(k)) {
                    return parseFloatStrict(v, expr);
                }
            } else {
                if (pos == positionalIndex) {
                    return parseFloatStrict(stripQuotes(arg), expr);
                }
                pos++;
            }
        }
        return fallback;
    }

    private static float parseFloatStrict(String raw, String expr) {
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid float '" + raw + "' in transition: " + expr, ex);
        }
    }

    private static int parseIntStrict(String raw, String expr) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid integer '" + raw + "' in transition: " + expr, ex);
        }
    }

    private static String normalizeColorHex(String hex) {
        if (hex == null || hex.isBlank()) {
            return DEFAULT_FADE_COLOR_HEX;
        }
        String trimmed = stripQuotes(hex.trim());
        if (!trimmed.startsWith("#")) {
            trimmed = "#" + trimmed;
        }
        SceneNode.parseHexRgba(trimmed);
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static boolean isColorToken(String token) {
        String s = token.trim();
        if (s.startsWith("#")) {
            return true;
        }
        return (s.length() == 6 || s.length() == 8) && s.chars().allMatch(c -> Character.digit(c, 16) >= 0);
    }

    private static List<String> splitTopLevelComma(String text) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && (inSingle || inDouble)) {
                i++;
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble) {
                if (c == '(' || c == '[') {
                    depth++;
                } else if (c == ')' || c == ']') {
                    depth = Math.max(0, depth - 1);
                } else if (c == ',' && depth == 0) {
                    String piece = text.substring(start, i).trim();
                    if (!piece.isEmpty()) {
                        result.add(piece);
                    }
                    start = i + 1;
                }
            }
        }
        String tail = text.substring(start).trim();
        if (!tail.isEmpty()) {
            result.add(tail);
        }
        return result;
    }

    private static int findTopLevelEquals(String text) {
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble) {
                if (c == '(' || c == '[') {
                    depth++;
                } else if (c == ')' || c == ']') {
                    depth = Math.max(0, depth - 1);
                } else if (c == '=' && depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String stripQuotes(String raw) {
        String s = raw.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // ---- Getters ----

    /**
     * Returns transition effect kind.
     *
     * @return transition effect kind
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * Returns total transition duration in seconds.
     *
     * @return total transition duration in seconds
     */
    public float getDurationSeconds() {
        return durationSeconds;
    }

    /**
     * Returns fade midpoint hex color (default {@code "#000000"}).
     *
     * @return fade midpoint hex color (default {@code "#000000"})
     */
    public String getFadeColorHex() {
        return fadeColorHex;
    }

    /**
     * Returns parsed 4-element straight RGBA array for {@link #getFadeColorHex()}.
     *
     * @return parsed 4-element straight RGBA array for {@link #getFadeColorHex()}
     */
    public float[] getFadeColorRgba() {
        return SceneNode.parseHexRgba(fadeColorHex);
    }

    /**
     * Returns grayscale mask image path for {@link Kind#IMAGE_DISSOLVE}, or {@code null}.
     *
     * @return grayscale mask image path for {@link Kind#IMAGE_DISSOLVE}, or {@code null}
     */
    public String getMaskImagePath() {
        return maskImagePath;
    }

    /**
     * Returns edge ramp softness in 8-bit levels for {@link Kind#IMAGE_DISSOLVE} (default {@code 8.0f}).
     *
     * @return edge ramp softness in 8-bit levels for {@link Kind#IMAGE_DISSOLVE} (default {@code 8.0f})
     */
    public float getRampLength() {
        return rampLength;
    }

    /**
     * Returns {@code true} if the {@link Kind#IMAGE_DISSOLVE} grayscale mask threshold is inverted.
     *
     * @return {@code true} if the {@link Kind#IMAGE_DISSOLVE} grayscale mask threshold is inverted
     */
    public boolean isReverseMask() {
        return reverseMask;
    }

    /**
     * Returns slice/cell count used by {@link Kind#BLINDS} and {@link Kind#SQUARES}.
     *
     * @return slice/cell count used by {@link Kind#BLINDS} and {@link Kind#SQUARES}
     */
    public int getSliceCount() {
        return sliceCount;
    }

    /**
     * Returns unmodifiable list of child transitions for {@link Kind#MULTIPLE}.
     *
     * @return unmodifiable list of child transitions for {@link Kind#MULTIPLE}
     */
    public List<Transition> getSubTransitions() {
        return subTransitions;
    }

    /**
     * Returns easing curve associated with {@link Kind#EASE} / {@link Kind#MOVE} transitions.
     *
     * @return easing curve associated with {@link Kind#EASE} / {@link Kind#MOVE} transitions
     */
    public Easing.EaseType getEaseType() {
        return easeType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Transition other)) {
            return false;
        }
        return kind == other.kind
                && Float.compare(durationSeconds, other.durationSeconds) == 0
                && Float.compare(rampLength, other.rampLength) == 0
                && reverseMask == other.reverseMask
                && sliceCount == other.sliceCount
                && easeType == other.easeType
                && Objects.equals(fadeColorHex, other.fadeColorHex)
                && Objects.equals(maskImagePath, other.maskImagePath)
                && Objects.equals(subTransitions, other.subTransitions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                kind,
                durationSeconds,
                fadeColorHex,
                maskImagePath,
                rampLength,
                reverseMask,
                sliceCount,
                subTransitions,
                easeType
        );
    }

    @Override
    public String toString() {
        return "Transition{kind=" + kind + ", durationSeconds=" + durationSeconds + '}';
    }
}
