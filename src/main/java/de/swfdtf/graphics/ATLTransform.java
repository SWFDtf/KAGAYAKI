package de.swfdtf.graphics;

import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Easing;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Transform2D;
import de.swfdtf.parser.VJavaAst;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Evaluates and executes declarative ATL (Animation and Transformation Language) timelines
 * against a target {@link Transform2D}.
 *
 * <p>Supports construction from parsed {@link VJavaAst.AtlStep} lists (via {@link #fromAst(List)})
 * as well as a fluent {@link #builder()} API. Timeline steps include timed interpolations
 * ({@code linear}, {@code ease}, {@code easein}, {@code easeout}, {@code easeinout},
 * {@code smoothstep}, cubic Bezier), {@code pause}, {@code repeat} (finite or infinite when
 * {@code count <= 0}), {@code parallel}, {@code block}, {@code choice} (randomized branch
 * selection), and instant {@code set} assignments.</p>
 *
 * <p>Interpolated properties on {@link Transform2D} include {@code xpos}, {@code ypos},
 * {@code xanchor}, {@code yanchor}, {@code xalign}, {@code yalign}, {@code zoom}, {@code xzoom},
 * {@code yzoom}, {@code rotate}, {@code alpha}, {@code blur}, {@code parallax_x}/{@code parallaxX},
 * {@code parallax_y}/{@code parallaxY}, {@code crop}, and {@code matrixcolor} (supporting
 * {@code brightness}, {@code contrast}, {@code saturation}, {@code hue}, {@code tint},
 * {@code invert}, {@code opacity}, and chained {@code *} products).</p>
 */
public final class ATLTransform {

    private static final double TIME_EPSILON = 1.0e-9;

    private final BlockStepNode rootBlock;
    private StepRuntime rootRuntime;
    private boolean started = false;
    private boolean finished = false;
    private double elapsedSeconds = 0.0;

    private ATLTransform(List<StepNode> steps) {
        this.rootBlock = new BlockStepNode(steps);
        this.rootRuntime = rootBlock.createRuntime();
    }

    /**
     * Builds an executable {@code ATLTransform} from a list of parsed {@link VJavaAst.AtlStep} nodes.
     *
     * @param steps parsed AST step list (non-null; may be empty)
     * @return new {@code ATLTransform} instance
     */
    public static ATLTransform fromAst(List<VJavaAst.AtlStep> steps) {
        Objects.requireNonNull(steps, "steps must not be null");
        return new ATLTransform(convertAstSteps(steps));
    }

    /**
     * Builds an executable {@code ATLTransform} from a {@link VJavaAst.TransformDef}.
     *
     * @param transformDef parsed transform definition (non-null)
     * @return new {@code ATLTransform} instance
     */
    public static ATLTransform fromAst(VJavaAst.TransformDef transformDef) {
        Objects.requireNonNull(transformDef, "transformDef must not be null");
        return fromAst(transformDef.steps());
    }

    /**
     * Creates a new fluent {@link Builder} for constructing an {@code ATLTransform} programmatically.
     *
     * @return new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Parses a {@code matrixcolor} expression into a premultiplied-alpha {@link ColorMatrix4f}.
     *
     * <p>Supports {@code brightness(b)}, {@code contrast(c)}, {@code saturation(s)},
     * {@code hue(deg)} / {@code hueRotate(deg)}, {@code tint(r, g, b)} / {@code tint("#hex")},
     * {@code invert()} / {@code invert(t)}, {@code opacity(a)}, and Ren'Py equivalents
     * ({@code BrightnessMatrix}, {@code ContrastMatrix}, {@code SaturationMatrix},
     * {@code HueMatrix}, {@code TintMatrix}, {@code InvertMatrix}, {@code OpacityMatrix}),
     * as well as chained {@code *} matrix products.</p>
     *
     * @param expr color matrix expression (non-null, non-blank)
     * @return evaluated {@link ColorMatrix4f}
     * @throws IllegalArgumentException if the expression is malformed or contains unknown operations
     */
    public static ColorMatrix4f parseColorMatrix(String expr) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("Color matrix expression must not be null or blank");
        }
        List<String> factors = splitTopLevel(expr.trim(), '*');
        ColorMatrix4f combined = ColorMatrix4f.identity();
        for (String factor : factors) {
            ColorMatrix4f next = parseSingleColorMatrixCall(factor.trim());
            combined = combined.equals(ColorMatrix4f.identity()) ? next : combined.multiply(next);
        }
        return combined;
    }

    /**
     * Parses a {@code crop} rectangle expression such as {@code "(10, 20, 100, 200)"},
     * {@code "10, 20, 100, 200"}, {@code "10 20 100 200"}, or {@code "null"}/{@code "none"}.
     *
     * @param expr crop expression string
     * @return parsed {@link Rect2f}, or {@code null} for {@code "null"}/{@code "none"}
     * @throws IllegalArgumentException if the expression cannot be parsed into 4 finite floats
     */
    public static Rect2f parseCropRect(String expr) {
        if (expr == null) {
            return null;
        }
        String trimmed = stripQuotes(expr.trim());
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || "null".equals(lower) || "none".equals(lower)) {
            return null;
        }
        if (trimmed.startsWith("(") && trimmed.endsWith(")")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        String[] parts = trimmed.contains(",")
                ? trimmed.split(",")
                : trimmed.split("\\s+");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Expected 4 components for crop rect, got: " + expr);
        }
        float x = Float.parseFloat(parts[0].trim());
        float y = Float.parseFloat(parts[1].trim());
        float w = Float.parseFloat(parts[2].trim());
        float h = Float.parseFloat(parts[3].trim());
        return new Rect2f(x, y, w, h);
    }

    /**
     * Starts or restarts evaluation of this timeline against {@code target}, immediately applying
     * any leading instant {@code set} steps.
     *
     * @param target mutable {@link Transform2D} driven by this animation (non-null)
     */
    public void start(Transform2D target) {
        Objects.requireNonNull(target, "target must not be null");
        reset();
        started = true;
        StepResult result = rootRuntime.advance(0.0, target);
        finished = result.finished();
    }

    /**
     * Advances the animation timeline by {@code dt} seconds and updates {@code target}.
     * If {@link #start(Transform2D)} has not yet been called, the timeline is started automatically.
     *
     * @param dt     elapsed time step in seconds (negative values are clamped to {@code 0})
     * @param target mutable {@link Transform2D} to update (non-null)
     */
    public void update(double dt, Transform2D target) {
        Objects.requireNonNull(target, "target must not be null");
        if (finished) {
            return;
        }
        double safeDt = Double.isFinite(dt) && dt > 0.0 ? dt : 0.0;
        if (!started) {
            started = true;
        }
        elapsedSeconds += safeDt;
        StepResult result = rootRuntime.advance(safeDt, target);
        finished = result.finished();
    }

    /**
     * Returns whether all finite steps in this timeline have completed.
     *
     * @return {@code true} if finished
     */
    public boolean isFinished() {
        return finished;
    }

    /**
     * Returns whether {@link #start(Transform2D)} or {@link #update(double, Transform2D)} has begun execution.
     *
     * @return {@code true} if started
     */
    public boolean isStarted() {
        return started;
    }

    /**
     * Returns total accumulated playback time in seconds since the last {@link #start(Transform2D)} or {@link #reset()}.
     *
     * @return elapsed playback time in seconds
     */
    public double getElapsedSeconds() {
        return elapsedSeconds;
    }

    /**
     * Resets all timeline state back to the beginning without mutating any {@link Transform2D}.
     */
    public void reset() {
        this.rootRuntime = rootBlock.createRuntime();
        this.started = false;
        this.finished = false;
        this.elapsedSeconds = 0.0;
    }

    // ---- AST Conversion ----

    private static List<StepNode> convertAstSteps(List<VJavaAst.AtlStep> astSteps) {
        List<StepNode> nodes = new ArrayList<>(astSteps.size());
        for (VJavaAst.AtlStep step : astSteps) {
            if (step == null) {
                continue;
            }
            String kind = step.kind() != null ? step.kind().trim().toLowerCase(Locale.ROOT) : "set";
            switch (kind) {
                case "set" -> nodes.add(new SetStepNode(parsePropertyUpdates(step.properties())));
                case "pause" -> nodes.add(new PauseStepNode(Math.max(0.0, step.durationSeconds())));
                case "pass", "on" -> nodes.add(new PassStepNode());
                case "repeat" -> nodes.add(new RepeatStepNode(parseRepeatCount(step.properties().get("count"))));
                case "block" -> nodes.add(new BlockStepNode(convertAstSteps(step.children())));
                case "parallel" -> {
                    List<ATLTransform> branches = new ArrayList<>(step.children().size());
                    for (VJavaAst.AtlStep child : step.children()) {
                        branches.add(new ATLTransform(convertAstSteps(List.of(child))));
                    }
                    nodes.add(new ParallelStepNode(branches));
                }
                case "choice" -> {
                    List<ATLTransform> choices = new ArrayList<>(step.children().size());
                    for (VJavaAst.AtlStep child : step.children()) {
                        choices.add(new ATLTransform(convertAstSteps(List.of(child))));
                    }
                    nodes.add(new ChoiceStepNode(choices, new Random()));
                }
                case "interpolate" -> {
                    EaseCurve curve = parseEaseCurve(step.easeName());
                    nodes.add(new InterpolateStepNode(
                            Math.max(0.0, step.durationSeconds()),
                            curve,
                            parsePropertyUpdates(step.properties())
                    ));
                }
                case "contains" -> nodes.add(new BlockStepNode(convertAstSteps(step.children())));
                default -> {
                    EaseCurve curve = parseEaseCurve(kind);
                    nodes.add(new InterpolateStepNode(
                            Math.max(0.0, step.durationSeconds()),
                            curve,
                            parsePropertyUpdates(step.properties())
                    ));
                }
            }
        }
        return nodes;
    }

    private static int parseRepeatCount(String rawCount) {
        if (rawCount == null || rawCount.isBlank()) {
            return 0;
        }
        String trimmed = rawCount.trim().toLowerCase(Locale.ROOT);
        if ("infinite".equals(trimmed) || "forever".equals(trimmed)) {
            return 0;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid ATL repeat count: " + rawCount, ex);
        }
    }

    private static List<PropertyUpdate> parsePropertyUpdates(Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return List.of();
        }
        List<PropertyUpdate> updates = new ArrayList<>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = normalizePropKey(entry.getKey());
            String val = entry.getValue().trim();
            switch (key) {
                case "xpos" -> updates.add(PropertyUpdate.ofFloat(PropertyKind.XPOS, parseFloatVal(val, key)));
                case "ypos" -> updates.add(PropertyUpdate.ofFloat(PropertyKind.YPOS, parseFloatVal(val, key)));
                case "xanchor" -> updates.add(PropertyUpdate.ofFloat(PropertyKind.XANCHOR, parseFloatVal(val, key)));
                case "yanchor" -> updates.add(PropertyUpdate.ofFloat(PropertyKind.YANCHOR, parseFloatVal(val, key)));
                case "xalign" -> updates.add(PropertyUpdate.ofFloat(PropertyKind.XALIGN, parseOptionalAlign(val)));
                case "yalign" -> updates.add(PropertyUpdate.ofFloat(PropertyKind.YALIGN, parseOptionalAlign(val)));
                case "pos" -> {
                    float[] pair = parseFloatPair(val, key);
                    updates.add(PropertyUpdate.ofFloat(PropertyKind.XPOS, pair[0]));
                    updates.add(PropertyUpdate.ofFloat(PropertyKind.YPOS, pair[1]));
                }
                case "anchor" -> {
                    float[] pair = parseFloatPair(val, key);
                    updates.add(PropertyUpdate.ofFloat(PropertyKind.XANCHOR, pair[0]));
                    updates.add(PropertyUpdate.ofFloat(PropertyKind.YANCHOR, pair[1]));
                }
                case "align" -> {
                    float[] pair = parseFloatPair(val, key);
                    updates.add(PropertyUpdate.ofFloat(PropertyKind.XALIGN, pair[0]));
                    updates.add(PropertyUpdate.ofFloat(PropertyKind.YALIGN, pair[1]));
                }
                case "zoom" -> updates.add(PropertyUpdate.ofFloat(PropertyKind.ZOOM, parseFloatVal(val, key)));
                case "xzoom" -> updates.add(PropertyUpdate.ofFloat(PropertyKind.XZOOM, parseFloatVal(val, key)));
                case "yzoom" -> updates.add(PropertyUpdate.ofFloat(PropertyKind.YZOOM, parseFloatVal(val, key)));
                case "rotate" -> updates.add(PropertyUpdate.ofFloat(PropertyKind.ROTATE, parseFloatVal(val, key)));
                case "alpha" -> updates.add(PropertyUpdate.ofFloat(PropertyKind.ALPHA, parseFloatVal(val, key)));
                case "blur" -> updates.add(PropertyUpdate.ofFloat(PropertyKind.BLUR, parseFloatVal(val, key)));
                case "parallax_x", "parallaxx" ->
                        updates.add(PropertyUpdate.ofFloat(PropertyKind.PARALLAX_X, parseFloatVal(val, key)));
                case "parallax_y", "parallaxy" ->
                        updates.add(PropertyUpdate.ofFloat(PropertyKind.PARALLAX_Y, parseFloatVal(val, key)));
                case "crop" -> updates.add(PropertyUpdate.ofCrop(parseCropRect(val)));
                case "matrixcolor" -> updates.add(PropertyUpdate.ofColorMatrix(parseColorMatrix(val)));
                default -> {
                    // Ignore unknown non-Transform2D ATL properties (such as subpixel, xoffset, etc.)
                }
            }
        }
        return updates;
    }

    private static String normalizePropKey(String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }

    private static float parseFloatVal(String raw, String key) {
        try {
            return Float.parseFloat(stripQuotes(raw));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid float for ATL property '" + key + "': " + raw, ex);
        }
    }

    private static float parseOptionalAlign(String raw) {
        String lower = stripQuotes(raw).toLowerCase(Locale.ROOT);
        if ("none".equals(lower) || "null".equals(lower) || "nan".equals(lower)) {
            return Float.NaN;
        }
        return parseFloatVal(lower, "align");
    }

    private static float[] parseFloatPair(String raw, String key) {
        String trimmed = stripQuotes(raw).trim();
        if (trimmed.startsWith("(") && trimmed.endsWith(")")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        String[] parts = trimmed.contains(",") ? trimmed.split(",") : trimmed.split("\\s+");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Expected 2 float values for '" + key + "', got: " + raw);
        }
        return new float[]{
                parseFloatVal(parts[0].trim(), key),
                parseFloatVal(parts[1].trim(), key)
        };
    }

    private static ColorMatrix4f parseSingleColorMatrixCall(String call) {
        int openParen = call.indexOf('(');
        String name;
        String argBody;
        if (openParen >= 0) {
            int closeParen = call.lastIndexOf(')');
            if (closeParen < openParen) {
                throw new IllegalArgumentException("Unclosed parenthesis in color matrix expression: " + call);
            }
            name = call.substring(0, openParen).trim().toLowerCase(Locale.ROOT);
            argBody = call.substring(openParen + 1, closeParen).trim();
        } else {
            name = call.trim().toLowerCase(Locale.ROOT);
            argBody = "";
        }

        List<String> args = argBody.isEmpty() ? List.of() : splitTopLevel(argBody, ',');
        return switch (name) {
            case "identity", "identitymatrix" -> ColorMatrix4f.identity();
            case "brightness", "brightnessmatrix" -> {
                requireArgCount(name, args, 1);
                yield ColorMatrix4f.brightness(Float.parseFloat(stripQuotes(args.get(0))));
            }
            case "contrast", "contrastmatrix" -> {
                requireArgCount(name, args, 1);
                yield ColorMatrix4f.contrast(Float.parseFloat(stripQuotes(args.get(0))));
            }
            case "saturation", "saturationmatrix" -> {
                requireArgCount(name, args, 1);
                yield ColorMatrix4f.saturation(Float.parseFloat(stripQuotes(args.get(0))));
            }
            case "hue", "huerotate", "hue_rotate", "huematrix" -> {
                requireArgCount(name, args, 1);
                yield ColorMatrix4f.hueRotate(Float.parseFloat(stripQuotes(args.get(0))));
            }
            case "opacity", "opacitymatrix" -> {
                requireArgCount(name, args, 1);
                yield ColorMatrix4f.opacity(Float.parseFloat(stripQuotes(args.get(0))));
            }
            case "invert", "invertmatrix" -> {
                if (args.isEmpty()) {
                    yield ColorMatrix4f.invert();
                }
                float amount = Float.parseFloat(stripQuotes(args.get(0)));
                yield ColorMatrix4f.identity().lerp(ColorMatrix4f.invert(), amount);
            }
            case "tint", "tintmatrix" -> parseTintMatrix(args, call);
            default -> throw new IllegalArgumentException("Unknown matrixcolor operation: " + call);
        };
    }

    private static ColorMatrix4f parseTintMatrix(List<String> args, String originalExpr) {
        if (args.size() == 1) {
            String single = stripQuotes(args.get(0)).trim();
            if (single.startsWith("#") || isHexColorDigits(single)) {
                float[] rgba = SceneNode.parseHexRgba(single);
                ColorMatrix4f tint = ColorMatrix4f.tint(rgba[0], rgba[1], rgba[2]);
                return rgba[3] < 1.0f ? tint.multiply(ColorMatrix4f.opacity(rgba[3])) : tint;
            }
            float scalar = Float.parseFloat(single);
            return ColorMatrix4f.tint(scalar, scalar, scalar);
        }
        if (args.size() == 3) {
            float r = Float.parseFloat(stripQuotes(args.get(0)));
            float g = Float.parseFloat(stripQuotes(args.get(1)));
            float b = Float.parseFloat(stripQuotes(args.get(2)));
            return ColorMatrix4f.tint(r, g, b);
        }
        if (args.size() == 4) {
            float r = Float.parseFloat(stripQuotes(args.get(0)));
            float g = Float.parseFloat(stripQuotes(args.get(1)));
            float b = Float.parseFloat(stripQuotes(args.get(2)));
            float a = Float.parseFloat(stripQuotes(args.get(3)));
            return ColorMatrix4f.tint(r, g, b).multiply(ColorMatrix4f.opacity(a));
        }
        throw new IllegalArgumentException("Expected 1, 3, or 4 arguments for tint(): " + originalExpr);
    }

    private static boolean isHexColorDigits(String s) {
        if (s.length() != 3 && s.length() != 4 && s.length() != 6 && s.length() != 8) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static void requireArgCount(String name, List<String> args, int expected) {
        if (args.size() != expected) {
            throw new IllegalArgumentException(
                    "Expected " + expected + " argument(s) for " + name + "(), got " + args.size()
            );
        }
    }

    private static EaseCurve parseEaseCurve(String easeName) {
        if (easeName == null || easeName.isBlank()) {
            return EaseCurve.ofPreset(Easing.EaseType.LINEAR);
        }
        String norm = easeName.trim().toLowerCase(Locale.ROOT);
        if (norm.startsWith("cubic_bezier(") || norm.startsWith("cubicbezier(") || norm.startsWith("bezier(")) {
            int open = norm.indexOf('(');
            int close = norm.lastIndexOf(')');
            if (close > open) {
                List<String> parts = splitTopLevel(norm.substring(open + 1, close), ',');
                if (parts.size() == 4) {
                    return EaseCurve.ofBezier(
                            Float.parseFloat(parts.get(0).trim()),
                            Float.parseFloat(parts.get(1).trim()),
                            Float.parseFloat(parts.get(2).trim()),
                            Float.parseFloat(parts.get(3).trim())
                    );
                }
            }
        }
        return switch (norm) {
            case "linear" -> EaseCurve.ofPreset(Easing.EaseType.LINEAR);
            case "ease" -> EaseCurve.ofPreset(Easing.EaseType.EASE);
            case "easein", "ease_in", "ease-in" -> EaseCurve.ofPreset(Easing.EaseType.EASE_IN);
            case "easeout", "ease_out", "ease-out" -> EaseCurve.ofPreset(Easing.EaseType.EASE_OUT);
            case "easeinout", "ease_in_out", "ease-in-out" -> EaseCurve.ofPreset(Easing.EaseType.EASE_IN_OUT);
            case "smoothstep" -> EaseCurve.ofPreset(Easing.EaseType.SMOOTHSTEP);
            case "ease_back", "easeback" -> EaseCurve.ofBezier(0.34f, 1.56f, 0.64f, 1.0f);
            default -> EaseCurve.ofPreset(Easing.EaseType.LINEAR);
        };
    }

    private static List<String> splitTopLevel(String text, char delimiter) {
        List<String> parts = new ArrayList<>();
        int parenDepth = 0;
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
                    parenDepth++;
                } else if (c == ')' || c == ']') {
                    parenDepth = Math.max(0, parenDepth - 1);
                } else if (c == delimiter && parenDepth == 0) {
                    String piece = text.substring(start, i).trim();
                    if (!piece.isEmpty()) {
                        parts.add(piece);
                    }
                    start = i + 1;
                }
            }
        }
        String tail = text.substring(start).trim();
        if (!tail.isEmpty()) {
            parts.add(tail);
        }
        return parts;
    }

    private static String stripQuotes(String raw) {
        String s = raw.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // ---- Internal Step Model & Runtime ----

    private enum PropertyKind {
        XPOS, YPOS, XANCHOR, YANCHOR, XALIGN, YALIGN,
        ZOOM, XZOOM, YZOOM, ROTATE, ALPHA, BLUR,
        PARALLAX_X, PARALLAX_Y, CROP, COLOR_MATRIX
    }

    private record PropertyUpdate(PropertyKind kind, float floatValue, Rect2f cropValue, ColorMatrix4f matrixValue) {
        static PropertyUpdate ofFloat(PropertyKind kind, float val) {
            return new PropertyUpdate(kind, val, null, null);
        }

        static PropertyUpdate ofCrop(Rect2f crop) {
            return new PropertyUpdate(PropertyKind.CROP, 0.0f, crop, null);
        }

        static PropertyUpdate ofColorMatrix(ColorMatrix4f matrix) {
            return new PropertyUpdate(PropertyKind.COLOR_MATRIX, 0.0f, null, matrix);
        }

        void applyDirect(Transform2D target) {
            switch (kind) {
                case XPOS -> target.setXpos(floatValue);
                case YPOS -> target.setYpos(floatValue);
                case XANCHOR -> target.setXanchor(floatValue);
                case YANCHOR -> target.setYanchor(floatValue);
                case XALIGN -> target.setXalign(floatValue);
                case YALIGN -> target.setYalign(floatValue);
                case ZOOM -> target.setZoom(floatValue);
                case XZOOM -> target.setXzoom(floatValue);
                case YZOOM -> target.setYzoom(floatValue);
                case ROTATE -> target.setRotate(floatValue);
                case ALPHA -> target.setAlpha(floatValue);
                case BLUR -> target.setBlur(floatValue);
                case PARALLAX_X -> target.setParallaxX(floatValue);
                case PARALLAX_Y -> target.setParallaxY(floatValue);
                case CROP -> target.setCrop(cropValue);
                case COLOR_MATRIX -> target.setColorMatrix(matrixValue);
            }
        }

        Object captureStart(Transform2D source) {
            return switch (kind) {
                case XPOS -> source.getXpos();
                case YPOS -> source.getYpos();
                case XANCHOR -> source.getXanchor();
                case YANCHOR -> source.getYanchor();
                case XALIGN -> source.getXalign();
                case YALIGN -> source.getYalign();
                case ZOOM -> source.getZoom();
                case XZOOM -> source.getXzoom();
                case YZOOM -> source.getYzoom();
                case ROTATE -> source.getRotate();
                case ALPHA -> source.getAlpha();
                case BLUR -> source.getBlur();
                case PARALLAX_X -> source.getParallaxX();
                case PARALLAX_Y -> source.getParallaxY();
                case CROP -> source.getCrop();
                case COLOR_MATRIX -> source.getColorMatrix();
            };
        }

        void applyInterpolated(Transform2D target, Object startVal, float t) {
            switch (kind) {
                case XPOS -> target.setXpos(lerpFloat((Float) startVal, floatValue, t));
                case YPOS -> target.setYpos(lerpFloat((Float) startVal, floatValue, t));
                case XANCHOR -> target.setXanchor(lerpFloat((Float) startVal, floatValue, t));
                case YANCHOR -> target.setYanchor(lerpFloat((Float) startVal, floatValue, t));
                case XALIGN -> target.setXalign(lerpOptionalFloat((Float) startVal, floatValue, t));
                case YALIGN -> target.setYalign(lerpOptionalFloat((Float) startVal, floatValue, t));
                case ZOOM -> target.setZoom(lerpFloat((Float) startVal, floatValue, t));
                case XZOOM -> target.setXzoom(lerpFloat((Float) startVal, floatValue, t));
                case YZOOM -> target.setYzoom(lerpFloat((Float) startVal, floatValue, t));
                case ROTATE -> target.setRotate(lerpFloat((Float) startVal, floatValue, t));
                case ALPHA -> target.setAlpha(lerpFloat((Float) startVal, floatValue, t));
                case BLUR -> target.setBlur(lerpFloat((Float) startVal, floatValue, t));
                case PARALLAX_X -> target.setParallaxX(lerpFloat((Float) startVal, floatValue, t));
                case PARALLAX_Y -> target.setParallaxY(lerpFloat((Float) startVal, floatValue, t));
                case CROP -> target.setCrop(lerpCrop((Rect2f) startVal, cropValue, t));
                case COLOR_MATRIX -> target.setColorMatrix(((ColorMatrix4f) startVal).lerp(matrixValue, t));
            }
        }

        private static float lerpFloat(float a, float b, float t) {
            return a + (b - a) * t;
        }

        private static float lerpOptionalFloat(float a, float b, float t) {
            if (Float.isNaN(a) && Float.isNaN(b)) {
                return Float.NaN;
            }
            if (!Float.isNaN(a) && !Float.isNaN(b)) {
                return lerpFloat(a, b, t);
            }
            return t >= 1.0f ? b : (t <= 0.0f ? a : (t < 0.5f ? a : b));
        }

        private static Rect2f lerpCrop(Rect2f a, Rect2f b, float t) {
            if (a == null && b == null) {
                return null;
            }
            if (a != null && b != null) {
                return new Rect2f(
                        lerpFloat(a.x(), b.x(), t),
                        lerpFloat(a.y(), b.y(), t),
                        lerpFloat(a.width(), b.width(), t),
                        lerpFloat(a.height(), b.height(), t)
                );
            }
            return t < 0.5f ? a : b;
        }
    }

    private record EaseCurve(Easing.EaseType preset, boolean customBezier, float p1x, float p1y, float p2x, float p2y) {
        static EaseCurve ofPreset(Easing.EaseType type) {
            return new EaseCurve(type, false, 0.0f, 0.0f, 0.0f, 0.0f);
        }

        static EaseCurve ofBezier(float p1x, float p1y, float p2x, float p2y) {
            return new EaseCurve(Easing.EaseType.LINEAR, true, p1x, p1y, p2x, p2y);
        }

        float evaluate(float t) {
            if (customBezier) {
                return Easing.cubicBezier(t, p1x, p1y, p2x, p2y);
            }
            return Easing.evaluate(preset, t);
        }
    }

    private record StepResult(boolean finished, double remainingDt) {
    }

    private sealed interface StepNode permits
            SetStepNode,
            PassStepNode,
            PauseStepNode,
            RepeatStepNode,
            InterpolateStepNode,
            BlockStepNode,
            ParallelStepNode,
            ChoiceStepNode {
        StepRuntime createRuntime();
    }

    private interface StepRuntime {
        StepResult advance(double dt, Transform2D target);

        void reset();
    }

    private record SetStepNode(List<PropertyUpdate> updates) implements StepNode {
        @Override
        public StepRuntime createRuntime() {
            return new StepRuntime() {
                private boolean done = false;

                @Override
                public StepResult advance(double dt, Transform2D target) {
                    if (!done) {
                        for (PropertyUpdate update : updates) {
                            update.applyDirect(target);
                        }
                        done = true;
                    }
                    return new StepResult(true, dt);
                }

                @Override
                public void reset() {
                    done = false;
                }
            };
        }
    }

    private static final class PassStepNode implements StepNode {
        @Override
        public StepRuntime createRuntime() {
            return new StepRuntime() {
                @Override
                public StepResult advance(double dt, Transform2D target) {
                    return new StepResult(true, dt);
                }

                @Override
                public void reset() {
                    // Stateless no-op
                }
            };
        }
    }

    private record PauseStepNode(double duration) implements StepNode {
        @Override
        public StepRuntime createRuntime() {
            return new StepRuntime() {
                private double elapsed = 0.0;

                @Override
                public StepResult advance(double dt, Transform2D target) {
                    if (duration <= TIME_EPSILON) {
                        return new StepResult(true, dt);
                    }
                    double remaining = duration - elapsed;
                    double tol = Math.max(TIME_EPSILON, duration * 1.0e-6);
                    if (dt + tol >= remaining) {
                        elapsed = duration;
                        double leftover = Math.max(0.0, dt - remaining);
                        return new StepResult(true, leftover);
                    }
                    elapsed += dt;
                    return new StepResult(false, 0.0);
                }

                @Override
                public void reset() {
                    elapsed = 0.0;
                }
            };
        }
    }

    private record RepeatStepNode(int count) implements StepNode {
        @Override
        public StepRuntime createRuntime() {
            return new StepRuntime() {
                @Override
                public StepResult advance(double dt, Transform2D target) {
                    return new StepResult(true, dt);
                }

                @Override
                public void reset() {
                    // Handled by enclosing BlockStepNode runtime
                }
            };
        }
    }

    private record InterpolateStepNode(
            double duration,
            EaseCurve curve,
            List<PropertyUpdate> updates
    ) implements StepNode {
        @Override
        public StepRuntime createRuntime() {
            return new StepRuntime() {
                private boolean initialized = false;
                private double elapsed = 0.0;
                private Object[] startValues;

                @Override
                public StepResult advance(double dt, Transform2D target) {
                    if (!initialized) {
                        startValues = new Object[updates.size()];
                        for (int i = 0; i < updates.size(); i++) {
                            startValues[i] = updates.get(i).captureStart(target);
                        }
                        initialized = true;
                    }

                    if (duration <= TIME_EPSILON) {
                        for (PropertyUpdate update : updates) {
                            update.applyDirect(target);
                        }
                        return new StepResult(true, dt);
                    }

                    double remaining = duration - elapsed;
                    double tol = Math.max(TIME_EPSILON, duration * 1.0e-6);
                    if (dt + tol >= remaining) {
                        elapsed = duration;
                        float eased = curve.evaluate(1.0f);
                        for (int i = 0; i < updates.size(); i++) {
                            updates.get(i).applyInterpolated(target, startValues[i], eased);
                        }
                        double leftover = Math.max(0.0, dt - remaining);
                        return new StepResult(true, leftover);
                    }

                    elapsed += dt;
                    float rawT = (float) Math.clamp(elapsed / duration, 0.0, 1.0);
                    float eased = curve.evaluate(rawT);
                    for (int i = 0; i < updates.size(); i++) {
                        updates.get(i).applyInterpolated(target, startValues[i], eased);
                    }
                    return new StepResult(false, 0.0);
                }

                @Override
                public void reset() {
                    initialized = false;
                    elapsed = 0.0;
                    startValues = null;
                }
            };
        }
    }

    private static final class BlockStepNode implements StepNode {
        private final List<StepNode> steps;

        BlockStepNode(List<StepNode> steps) {
            this.steps = List.copyOf(steps);
        }

        @Override
        public StepRuntime createRuntime() {
            List<StepRuntime> runtimes = new ArrayList<>(steps.size());
            for (StepNode step : steps) {
                runtimes.add(step.createRuntime());
            }
            return new StepRuntime() {
                private int cursor = 0;
                private final int[] repeatCyclesCompleted = new int[steps.size()];

                @Override
                public StepResult advance(double dt, Transform2D target) {
                    double remainingDt = dt;
                    double dtAtCycleStart = remainingDt;
                    boolean wrappedInZeroTime = false;

                    while (cursor < steps.size()) {
                        StepNode node = steps.get(cursor);
                        if (node instanceof RepeatStepNode repeatNode) {
                            int targetCount = repeatNode.count();
                            boolean shouldLoop;
                            if (targetCount <= 0) {
                                shouldLoop = true;
                            } else {
                                repeatCyclesCompleted[cursor]++;
                                shouldLoop = repeatCyclesCompleted[cursor] < targetCount;
                            }
                            if (shouldLoop) {
                                for (int i = 0; i < cursor; i++) {
                                    runtimes.get(i).reset();
                                    repeatCyclesCompleted[i] = 0;
                                }
                                cursor = 0;
                                if (targetCount <= 0 && Math.abs(remainingDt - dtAtCycleStart) <= TIME_EPSILON) {
                                    // Guard against zero-duration infinite repeat loops hanging the thread
                                    wrappedInZeroTime = true;
                                    break;
                                }
                                dtAtCycleStart = remainingDt;
                                continue;
                            } else {
                                cursor++;
                                continue;
                            }
                        }

                        StepResult stepRes = runtimes.get(cursor).advance(remainingDt, target);
                        remainingDt = stepRes.remainingDt();
                        if (!stepRes.finished()) {
                            return new StepResult(false, 0.0);
                        }
                        cursor++;
                    }

                    if (wrappedInZeroTime) {
                        return new StepResult(false, 0.0);
                    }
                    return new StepResult(cursor >= steps.size(), remainingDt);
                }

                @Override
                public void reset() {
                    cursor = 0;
                    for (int i = 0; i < repeatCyclesCompleted.length; i++) {
                        repeatCyclesCompleted[i] = 0;
                    }
                    for (StepRuntime runtime : runtimes) {
                        runtime.reset();
                    }
                }
            };
        }
    }

    private static final class ParallelStepNode implements StepNode {
        private final List<ATLTransform> branches;

        ParallelStepNode(List<ATLTransform> branches) {
            this.branches = List.copyOf(branches);
        }

        @Override
        public StepRuntime createRuntime() {
            List<StepRuntime> branchRuntimes = new ArrayList<>(branches.size());
            for (ATLTransform branch : branches) {
                branchRuntimes.add(branch.rootBlock.createRuntime());
            }
            return new StepRuntime() {
                private final boolean[] branchFinished = new boolean[branchRuntimes.size()];

                @Override
                public StepResult advance(double dt, Transform2D target) {
                    if (branchRuntimes.isEmpty()) {
                        return new StepResult(true, dt);
                    }
                    boolean allDone = true;
                    double minLeftover = dt;

                    for (int i = 0; i < branchRuntimes.size(); i++) {
                        if (branchFinished[i]) {
                            continue;
                        }
                        StepResult res = branchRuntimes.get(i).advance(dt, target);
                        if (res.finished()) {
                            branchFinished[i] = true;
                            minLeftover = Math.min(minLeftover, res.remainingDt());
                        } else {
                            allDone = false;
                        }
                    }

                    if (allDone) {
                        return new StepResult(true, minLeftover);
                    }
                    return new StepResult(false, 0.0);
                }

                @Override
                public void reset() {
                    for (int i = 0; i < branchFinished.length; i++) {
                        branchFinished[i] = false;
                    }
                    for (StepRuntime rt : branchRuntimes) {
                        rt.reset();
                    }
                }
            };
        }
    }

    private static final class ChoiceStepNode implements StepNode {
        private final List<ATLTransform> choices;
        private final RandomGenerator random;

        ChoiceStepNode(List<ATLTransform> choices, RandomGenerator random) {
            this.choices = List.copyOf(choices);
            this.random = Objects.requireNonNull(random, "random must not be null");
        }

        @Override
        public StepRuntime createRuntime() {
            List<StepRuntime> runtimes = new ArrayList<>(choices.size());
            for (ATLTransform choice : choices) {
                runtimes.add(choice.rootBlock.createRuntime());
            }
            return new StepRuntime() {
                private int selectedIndex = -1;

                @Override
                public StepResult advance(double dt, Transform2D target) {
                    if (runtimes.isEmpty()) {
                        return new StepResult(true, dt);
                    }
                    if (selectedIndex < 0) {
                        selectedIndex = random.nextInt(runtimes.size());
                    }
                    return runtimes.get(selectedIndex).advance(dt, target);
                }

                @Override
                public void reset() {
                    if (selectedIndex >= 0 && selectedIndex < runtimes.size()) {
                        runtimes.get(selectedIndex).reset();
                    }
                    selectedIndex = -1;
                }
            };
        }
    }

    // ---- Fluent Builder ----

    /**
     * Fluent builder for creating declarative {@link ATLTransform} timelines in Java.
     */
    public static final class Builder {

        private enum ActiveStepKind { NONE, SET, INTERPOLATE }

        private final List<StepNode> steps = new ArrayList<>();
        private ActiveStepKind activeKind = ActiveStepKind.NONE;
        private double activeDuration = 0.0;
        private EaseCurve activeEase = EaseCurve.ofPreset(Easing.EaseType.LINEAR);
        private List<PropertyUpdate> activeProperties = new ArrayList<>();

        private Builder() {
        }

        private void flushActiveStep() {
            if (activeKind == ActiveStepKind.SET) {
                if (!activeProperties.isEmpty()) {
                    steps.add(new SetStepNode(Collections.unmodifiableList(new ArrayList<>(activeProperties))));
                }
            } else if (activeKind == ActiveStepKind.INTERPOLATE) {
                steps.add(new InterpolateStepNode(
                        activeDuration,
                        activeEase,
                        Collections.unmodifiableList(new ArrayList<>(activeProperties))
                ));
            }
            activeKind = ActiveStepKind.NONE;
            activeDuration = 0.0;
            activeProperties = new ArrayList<>();
        }

        private void ensurePropertyTarget() {
            if (activeKind == ActiveStepKind.NONE) {
                activeKind = ActiveStepKind.SET;
                activeProperties = new ArrayList<>();
            }
        }

        /**
         * Begins an instant {@code set} property step. Subsequent property calls attach to this step.
         *
         * @return {@code this}
         */
        public Builder set() {
            flushActiveStep();
            activeKind = ActiveStepKind.SET;
            activeProperties = new ArrayList<>();
            return this;
        }

        /**
         * Adds a single instant property assignment step.
         *
         * @param property property name (e.g., {@code "alpha"}, {@code "matrixcolor"})
         * @param value    string value
         * @return {@code this}
         */
        public Builder set(String property, String value) {
            flushActiveStep();
            Map<String, String> map = new LinkedHashMap<>();
            map.put(property, value);
            List<PropertyUpdate> updates = parsePropertyUpdates(map);
            if (!updates.isEmpty()) {
                steps.add(new SetStepNode(updates));
            }
            return this;
        }

        /**
         * Begins a {@code linear} interpolation step over {@code durationSeconds}.
         *
         * @param durationSeconds duration in seconds ({@code >= 0})
         * @return {@code this}
         */
        public Builder linear(double durationSeconds) {
            return interpolate(durationSeconds, Easing.EaseType.LINEAR);
        }

        /**
         * Begins an {@code ease} interpolation step over {@code durationSeconds}.
         *
         * @param durationSeconds duration in seconds ({@code >= 0})
         * @return {@code this}
         */
        public Builder ease(double durationSeconds) {
            return interpolate(durationSeconds, Easing.EaseType.EASE);
        }

        /**
         * Begins an {@code easein} interpolation step over {@code durationSeconds}.
         *
         * @param durationSeconds duration in seconds ({@code >= 0})
         * @return {@code this}
         */
        public Builder easein(double durationSeconds) {
            return easeIn(durationSeconds);
        }

        /**
         * Begins an {@code easein} interpolation step over {@code durationSeconds}.
         *
         * @param durationSeconds duration in seconds ({@code >= 0})
         * @return {@code this}
         */
        public Builder easeIn(double durationSeconds) {
            return interpolate(durationSeconds, Easing.EaseType.EASE_IN);
        }

        /**
         * Begins an {@code easeout} interpolation step over {@code durationSeconds}.
         *
         * @param durationSeconds duration in seconds ({@code >= 0})
         * @return {@code this}
         */
        public Builder easeout(double durationSeconds) {
            return easeOut(durationSeconds);
        }

        /**
         * Begins an {@code easeout} interpolation step over {@code durationSeconds}.
         *
         * @param durationSeconds duration in seconds ({@code >= 0})
         * @return {@code this}
         */
        public Builder easeOut(double durationSeconds) {
            return interpolate(durationSeconds, Easing.EaseType.EASE_OUT);
        }

        /**
         * Begins an {@code easeinout} interpolation step over {@code durationSeconds}.
         *
         * @param durationSeconds duration in seconds ({@code >= 0})
         * @return {@code this}
         */
        public Builder easeInOut(double durationSeconds) {
            return interpolate(durationSeconds, Easing.EaseType.EASE_IN_OUT);
        }

        /**
         * Begins a {@code smoothstep} interpolation step over {@code durationSeconds}.
         *
         * @param durationSeconds duration in seconds ({@code >= 0})
         * @return {@code this}
         */
        public Builder smoothstep(double durationSeconds) {
            return interpolate(durationSeconds, Easing.EaseType.SMOOTHSTEP);
        }

        /**
         * Begins an interpolation step with an explicit {@link Easing.EaseType}.
         *
         * @param durationSeconds duration in seconds ({@code >= 0})
         * @param easeType        easing curve preset
         * @return {@code this}
         */
        public Builder interpolate(double durationSeconds, Easing.EaseType easeType) {
            Objects.requireNonNull(easeType, "easeType must not be null");
            flushActiveStep();
            activeKind = ActiveStepKind.INTERPOLATE;
            activeDuration = Math.max(0.0, durationSeconds);
            activeEase = EaseCurve.ofPreset(easeType);
            activeProperties = new ArrayList<>();
            return this;
        }

        /**
         * Begins a custom cubic Bezier interpolation step.
         *
         * @param durationSeconds duration in seconds ({@code >= 0})
         * @param p1x             first control point X in {@code [0, 1]}
         * @param p1y             first control point Y
         * @param p2x             second control point X in {@code [0, 1]}
         * @param p2y             second control point Y
         * @return {@code this}
         */
        public Builder cubicBezier(double durationSeconds, float p1x, float p1y, float p2x, float p2y) {
            flushActiveStep();
            activeKind = ActiveStepKind.INTERPOLATE;
            activeDuration = Math.max(0.0, durationSeconds);
            activeEase = EaseCurve.ofBezier(p1x, p1y, p2x, p2y);
            activeProperties = new ArrayList<>();
            return this;
        }

        /**
         * Appends a timed {@code pause} step.
         *
         * @param durationSeconds pause duration in seconds ({@code >= 0})
         * @return {@code this}
         */
        public Builder pause(double durationSeconds) {
            flushActiveStep();
            steps.add(new PauseStepNode(Math.max(0.0, durationSeconds)));
            return this;
        }

        /**
         * Appends an infinite {@code repeat} directive to the current block.
         *
         * @return {@code this}
         */
        public Builder repeat() {
            return repeat(0);
        }

        /**
         * Appends a {@code repeat} directive to the current block.
         * Values {@code <= 0} repeat infinitely; values {@code >= 1} run {@code count} total cycles.
         *
         * @param count cycle count ({@code <= 0} for infinite)
         * @return {@code this}
         */
        public Builder repeat(int count) {
            flushActiveStep();
            steps.add(new RepeatStepNode(count));
            return this;
        }

        /**
         * Appends a scoped {@code block} whose child steps repeat {@code count} times ({@code <= 0} infinite).
         *
         * @param count cycle count ({@code <= 0} for infinite)
         * @param body  child timeline to repeat
         * @return {@code this}
         */
        public Builder repeat(int count, ATLTransform body) {
            Objects.requireNonNull(body, "body must not be null");
            flushActiveStep();
            List<StepNode> blockSteps = new ArrayList<>(body.rootBlock.steps);
            blockSteps.add(new RepeatStepNode(count));
            steps.add(new BlockStepNode(blockSteps));
            return this;
        }

        /**
         * Appends a {@code parallel} step executing all child branches simultaneously.
         *
         * @param branches child timelines
         * @return {@code this}
         */
        public Builder parallel(ATLTransform... branches) {
            Objects.requireNonNull(branches, "branches must not be null");
            return parallel(List.of(branches));
        }

        /**
         * Appends a {@code parallel} step executing all child branches simultaneously.
         *
         * @param branches child timeline list
         * @return {@code this}
         */
        public Builder parallel(List<ATLTransform> branches) {
            Objects.requireNonNull(branches, "branches must not be null");
            flushActiveStep();
            steps.add(new ParallelStepNode(branches));
            return this;
        }

        /**
         * Appends a sequential {@code block} step wrapping child timelines in order.
         *
         * @param children child timelines
         * @return {@code this}
         */
        public Builder block(ATLTransform... children) {
            Objects.requireNonNull(children, "children must not be null");
            return block(List.of(children));
        }

        /**
         * Appends a sequential {@code block} step wrapping child timelines in order.
         *
         * @param children child timeline list
         * @return {@code this}
         */
        public Builder block(List<ATLTransform> children) {
            Objects.requireNonNull(children, "children must not be null");
            flushActiveStep();
            List<StepNode> childNodes = new ArrayList<>();
            for (ATLTransform child : children) {
                if (child != null) {
                    childNodes.add(child.rootBlock);
                }
            }
            steps.add(new BlockStepNode(childNodes));
            return this;
        }

        /**
         * Appends a {@code choice} step that randomly picks one child timeline using a default {@link Random}.
         *
         * @param children candidate timelines
         * @return {@code this}
         */
        public Builder choice(List<ATLTransform> children) {
            return choice(children, new Random());
        }

        /**
         * Appends a {@code choice} step that randomly picks one child timeline using {@code random}.
         *
         * @param children candidate timelines
         * @param random   random generator (non-null)
         * @return {@code this}
         */
        public Builder choice(List<ATLTransform> children, RandomGenerator random) {
            Objects.requireNonNull(children, "children must not be null");
            Objects.requireNonNull(random, "random must not be null");
            flushActiveStep();
            steps.add(new ChoiceStepNode(children, random));
            return this;
        }

        /**
         * Appends a {@code choice} step that randomly picks one child timeline using {@code random}.
         *
         * @param random   random generator (non-null)
         * @param children candidate timelines
         * @return {@code this}
         */
        public Builder choice(RandomGenerator random, ATLTransform... children) {
            Objects.requireNonNull(children, "children must not be null");
            return choice(List.of(children), random);
        }

        /**
         * Appends a parsed {@link VJavaAst.AtlStep} directly to this timeline.
         *
         * @param astStep AST step
         * @return {@code this}
         */
        public Builder step(VJavaAst.AtlStep astStep) {
            Objects.requireNonNull(astStep, "astStep must not be null");
            flushActiveStep();
            steps.addAll(convertAstSteps(List.of(astStep)));
            return this;
        }

        public Builder xpos(float xpos) {
            ensurePropertyTarget();
            activeProperties.add(PropertyUpdate.ofFloat(PropertyKind.XPOS, xpos));
            return this;
        }

        public Builder ypos(float ypos) {
            ensurePropertyTarget();
            activeProperties.add(PropertyUpdate.ofFloat(PropertyKind.YPOS, ypos));
            return this;
        }

        public Builder xanchor(float xanchor) {
            ensurePropertyTarget();
            activeProperties.add(PropertyUpdate.ofFloat(PropertyKind.XANCHOR, xanchor));
            return this;
        }

        public Builder yanchor(float yanchor) {
            ensurePropertyTarget();
            activeProperties.add(PropertyUpdate.ofFloat(PropertyKind.YANCHOR, yanchor));
            return this;
        }

        public Builder xalign(float xalign) {
            ensurePropertyTarget();
            activeProperties.add(PropertyUpdate.ofFloat(PropertyKind.XALIGN, xalign));
            return this;
        }

        public Builder yalign(float yalign) {
            ensurePropertyTarget();
            activeProperties.add(PropertyUpdate.ofFloat(PropertyKind.YALIGN, yalign));
            return this;
        }

        public Builder zoom(float zoom) {
            ensurePropertyTarget();
            activeProperties.add(PropertyUpdate.ofFloat(PropertyKind.ZOOM, zoom));
            return this;
        }

        public Builder xzoom(float xzoom) {
            ensurePropertyTarget();
            activeProperties.add(PropertyUpdate.ofFloat(PropertyKind.XZOOM, xzoom));
            return this;
        }

        public Builder yzoom(float yzoom) {
            ensurePropertyTarget();
            activeProperties.add(PropertyUpdate.ofFloat(PropertyKind.YZOOM, yzoom));
            return this;
        }

        public Builder rotate(float rotateDegrees) {
            ensurePropertyTarget();
            activeProperties.add(PropertyUpdate.ofFloat(PropertyKind.ROTATE, rotateDegrees));
            return this;
        }

        public Builder alpha(float alpha) {
            ensurePropertyTarget();
            activeProperties.add(PropertyUpdate.ofFloat(PropertyKind.ALPHA, alpha));
            return this;
        }

        public Builder blur(float blur) {
            ensurePropertyTarget();
            activeProperties.add(PropertyUpdate.ofFloat(PropertyKind.BLUR, blur));
            return this;
        }

        public Builder parallaxX(float parallaxX) {
            ensurePropertyTarget();
            activeProperties.add(PropertyUpdate.ofFloat(PropertyKind.PARALLAX_X, parallaxX));
            return this;
        }

        public Builder parallaxY(float parallaxY) {
            ensurePropertyTarget();
            activeProperties.add(PropertyUpdate.ofFloat(PropertyKind.PARALLAX_Y, parallaxY));
            return this;
        }

        public Builder crop(Rect2f crop) {
            ensurePropertyTarget();
            activeProperties.add(PropertyUpdate.ofCrop(crop));
            return this;
        }

        public Builder crop(float x, float y, float width, float height) {
            return crop(new Rect2f(x, y, width, height));
        }

        public Builder colorMatrix(ColorMatrix4f colorMatrix) {
            Objects.requireNonNull(colorMatrix, "colorMatrix must not be null");
            ensurePropertyTarget();
            activeProperties.add(PropertyUpdate.ofColorMatrix(colorMatrix));
            return this;
        }

        public Builder matrixcolor(String expr) {
            ensurePropertyTarget();
            activeProperties.add(PropertyUpdate.ofColorMatrix(parseColorMatrix(expr)));
            return this;
        }

        public Builder property(String key, String value) {
            ensurePropertyTarget();
            Map<String, String> single = new LinkedHashMap<>();
            single.put(key, value);
            activeProperties.addAll(parsePropertyUpdates(single));
            return this;
        }

        /**
         * Builds the immutable-timeline {@link ATLTransform} instance.
         *
         * @return new {@link ATLTransform}
         */
        public ATLTransform build() {
            flushActiveStep();
            return new ATLTransform(steps);
        }
    }
}
