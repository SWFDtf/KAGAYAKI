package de.swfdtf.ui;

import de.swfdtf.graphics.CustomDisplayable;
import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.graphics.TextTagParser;
import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.ConditionEvaluator;
import de.swfdtf.parser.JavaBlockCompiler;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.state.PersistentAccess;
import de.swfdtf.state.StateStore;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Instantiates a live {@link Screen} and its full {@link UiNode} container/widget tree directly
 * from a parsed {@link VJavaAst.ScreenDef} AST node.
 */
public final class ScreenAstBuilder {

    private static final Pattern VARIABLE_VALUE_PATTERN = Pattern.compile(
            "^VariableValue\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*(?:,\\s*([^)]+))?\\)$"
    );
    private static final Pattern VARIABLE_INPUT_PATTERN = Pattern.compile(
            "^VariableInputValue\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*(?:,\\s*([^)]+))?\\)$"
    );

    private ScreenAstBuilder() {
    }

    /**
     * Builds a runnable {@link Screen} from {@code def}, wiring text interpolation, conditional
     * branches, {@link StateStore} variable bindings for sliders/inputs/bars, and UI actions.
     *
     * @param def              parsed screen AST definition
     * @param runtimeOrNull    optional active {@link ScriptRuntime} for variable lookup/mutation
     * @param actionDispatcher callback receiving action expressions (e.g. {@code "Return()"}, {@code "Jump(\"town\")"})
     * @return constructed {@link Screen}
     */
    public static Screen build(
            VJavaAst.ScreenDef def,
            ScriptRuntime runtimeOrNull,
            Consumer<String> actionDispatcher
    ) {
        Objects.requireNonNull(def, "def must not be null");
        int zOrder = def.zOrder() != 0 ? def.zOrder() : SceneLayer.Z_SCREENS;
        Screen screen = new Screen(def.name(), zOrder, def.modal());

        Fixed root = new Fixed();
        root.setId("screen:" + def.name());
        buildChildrenInto(root, def.items(), screen, runtimeOrNull, actionDispatcher);
        screen.setRoot(root);
        return screen;
    }

    private static void buildChildrenInto(
            UiNode parent,
            List<VJavaAst.ScreenItem> items,
            Screen screen,
            ScriptRuntime runtime,
            Consumer<String> actionDispatcher
    ) {
        StateStore state = runtime != null ? runtime.getStateStore() : null;
        PersistentAccess persistent = runtime != null ? runtime.getPersistentAccess() : null;

        boolean ifChainMatched = false;
        boolean inIfChain = false;

        for (VJavaAst.ScreenItem item : items) {
            String kw = item.keyword().toLowerCase(Locale.ROOT);
            if ("if".equals(kw)) {
                inIfChain = true;
                ifChainMatched = evaluateConditionSafe(item.primaryArg(), state, persistent);
                if (ifChainMatched) {
                    buildChildrenInto(parent, item.children(), screen, runtime, actionDispatcher);
                }
                continue;
            }
            if ("elif".equals(kw)) {
                if (inIfChain && !ifChainMatched) {
                    ifChainMatched = evaluateConditionSafe(item.primaryArg(), state, persistent);
                    if (ifChainMatched) {
                        buildChildrenInto(parent, item.children(), screen, runtime, actionDispatcher);
                    }
                }
                continue;
            }
            if ("else".equals(kw)) {
                if (inIfChain && !ifChainMatched) {
                    ifChainMatched = true;
                    buildChildrenInto(parent, item.children(), screen, runtime, actionDispatcher);
                }
                inIfChain = false;
                continue;
            }

            inIfChain = false;
            ifChainMatched = false;

            if ("key".equals(kw)) {
                int glfwKey = parseGlfwKey(item.primaryArg());
                Runnable action = toActionRunnable(item.properties().get("action"), actionDispatcher);
                if (glfwKey > 0 && action != null) {
                    screen.addKeyBinding(glfwKey, action);
                }
                continue;
            }
            if ("timer".equals(kw)) {
                double interval = parseDouble(item.primaryArg(), 1.0);
                boolean repeat = parseBoolean(item.properties().get("repeat"), false);
                Runnable action = toActionRunnable(item.properties().get("action"), actionDispatcher);
                if (action != null) {
                    screen.addTimer(interval, repeat, action);
                }
                continue;
            }
            if ("java_block".equals(kw)) {
                continue;
            }

            UiNode child = buildItem(item, screen, runtime, actionDispatcher);
            if (child != null) {
                attachChild(parent, child, item.properties());
            }
        }
    }

    private static UiNode buildItem(
            VJavaAst.ScreenItem item,
            Screen screen,
            ScriptRuntime runtime,
            Consumer<String> actionDispatcher
    ) {
        String kw = item.keyword().toLowerCase(Locale.ROOT);
        Map<String, String> props = item.properties();
        StateStore state = runtime != null ? runtime.getStateStore() : null;

        UiNode node = switch (kw) {
            case "vbox" -> buildVBox(props);
            case "hbox" -> buildHBox(props);
            case "grid" -> buildGrid(item.primaryArg(), props);
            case "fixed" -> new Fixed();
            case "frame" -> new Frame();
            case "window" -> buildWindowBox(item.primaryArg(), props, runtime);
            case "viewport" -> buildViewport(props);
            case "vpgrid" -> buildVpGrid(props);
            case "text", "label" -> buildTextWidget(item.primaryArg(), props, runtime);
            case "textbutton", "button" -> buildTextButton(item.primaryArg(), props, runtime, actionDispatcher);
            case "imagebutton" -> buildImageButton(props, actionDispatcher);
            case "bar" -> buildBar(props, state);
            case "vbar" -> buildVBar(props, state);
            case "slider" -> buildSlider(props, state);
            case "input" -> buildTextInput(props, state, actionDispatcher);
            case "hotspot" -> buildHotspot(item.primaryArg(), props, actionDispatcher);
            case "hotbar" -> buildHotbar(item.primaryArg(), props, state);
            case "imagemap" -> buildImagemap(props);
            case "draggroup" -> new DragGroup();
            case "drag" -> buildDragItem(item.primaryArg(), props);
            case "custom" -> buildCustomWidget(item.primaryArg(), runtime);
            default -> new Fixed();
        };

        applyCommonProperties(node, props);

        if (!item.children().isEmpty() && !(node instanceof TextWidget)) {
            buildChildrenInto(node, item.children(), screen, runtime, actionDispatcher);
        }
        return node;
    }

    private static void attachChild(UiNode parent, UiNode child, Map<String, String> props) {
        if (parent instanceof DragGroup group && child instanceof DragItem dragItem) {
            float x = parseFloat(props.get("xpos"), 0.0f);
            float y = parseFloat(props.get("ypos"), 0.0f);
            float w = dragItem.getPreferredWidth() > 0.0f ? dragItem.getPreferredWidth() : DragItem.DEFAULT_SIZE;
            float h = dragItem.getPreferredHeight() > 0.0f ? dragItem.getPreferredHeight() : DragItem.DEFAULT_SIZE;
            if (props.containsKey("xpos") || props.containsKey("ypos")) {
                dragItem.setBounds(new Rect2f(x, y, w, h));
            }
            group.addDragItem(dragItem);
            return;
        }
        if (parent instanceof Imagemap imagemap) {
            imagemap.addChild(child);
            return;
        }
        if (parent instanceof Fixed fixed) {
            boolean hasXPos = props.containsKey("xpos");
            boolean hasYPos = props.containsKey("ypos");
            boolean hasXAlign = props.containsKey("xalign");
            boolean hasYAlign = props.containsKey("yalign");
            if (hasXPos && hasYPos && !hasXAlign && !hasYAlign) {
                fixed.addChildAt(
                        child,
                        parseFloat(props.get("xpos"), 0.0f),
                        parseFloat(props.get("ypos"), 0.0f),
                        child.getPreferredWidth(),
                        child.getPreferredHeight()
                );
            } else {
                fixed.addChild(child);
                if (hasXPos || hasYPos || hasXAlign || hasYAlign) {
                    fixed.setChildAlignment(child, new Fixed.AlignmentSpec(
                            parseFloat(props.get("xpos"), 0.0f),
                            parseFloat(props.get("ypos"), 0.0f),
                            parseFloat(props.get("xalign"), 0.0f),
                            parseFloat(props.get("yalign"), 0.0f),
                            hasXPos, hasYPos, hasXAlign, hasYAlign
                    ));
                }
            }
            return;
        }
        parent.addChild(child);
    }

    private static VBox buildVBox(Map<String, String> props) {
        VBox box = new VBox();
        if (props.containsKey("spacing")) {
            box.setSpacing(parseFloat(props.get("spacing"), 0.0f));
        }
        if (props.containsKey("fill_width")) {
            box.setFillWidth(parseBoolean(props.get("fill_width"), false));
        }
        if (props.containsKey("xalign") && Math.abs(parseFloat(props.get("xalign"), 0.0f) - 0.5f) < 0.01f) {
            box.setAlignCenterX(true);
        }
        return box;
    }

    private static HBox buildHBox(Map<String, String> props) {
        HBox box = new HBox();
        if (props.containsKey("spacing")) {
            box.setSpacing(parseFloat(props.get("spacing"), 0.0f));
        }
        if (props.containsKey("yalign") && Math.abs(parseFloat(props.get("yalign"), 0.0f) - 0.5f) < 0.01f) {
            box.setAlignCenterY(true);
        }
        return box;
    }

    private static Grid buildGrid(String primaryArg, Map<String, String> props) {
        int cols = parseInt(props.get("cols"), 0);
        int rows = parseInt(props.get("rows"), 0);
        if (cols <= 0 && primaryArg != null && !primaryArg.isBlank()) {
            String[] parts = primaryArg.trim().split("\\s+");
            if (parts.length >= 1) {
                cols = parseInt(parts[0], 1);
            }
            if (parts.length >= 2) {
                rows = parseInt(parts[1], 0);
            }
        }
        Grid grid = new Grid(Math.max(1, cols), Math.max(0, rows));
        float spacing = parseFloat(props.get("spacing"), 0.0f);
        float hSpacing = parseFloat(props.get("xspacing"), spacing);
        float vSpacing = parseFloat(props.get("yspacing"), spacing);
        grid.setSpacing(hSpacing, vSpacing);
        return grid;
    }

    private static WindowBox buildWindowBox(String primaryArg, Map<String, String> props, ScriptRuntime runtime) {
        String rawTitle = props.getOrDefault("title", primaryArg != null ? primaryArg : "");
        return new WindowBox(interpolateText(rawTitle, runtime));
    }

    private static Viewport buildViewport(Map<String, String> props) {
        Viewport vp = new Viewport();
        String scrollbars = props.get("scrollbars");
        if (scrollbars != null && ("none".equalsIgnoreCase(scrollbars) || "false".equalsIgnoreCase(scrollbars))) {
            vp.setScrollbarVisible(false);
        }
        if (props.containsKey("yinitial")) {
            vp.setScrollY(parseFloat(props.get("yinitial"), 0.0f));
        }
        return vp;
    }

    private static VpGrid buildVpGrid(Map<String, String> props) {
        int cols = Math.max(1, parseInt(props.get("cols"), 2));
        float cellW = parseFloat(props.get("cell_width"), parseFloat(props.get("xsize"), 240.0f));
        float cellH = parseFloat(props.get("cell_height"), parseFloat(props.get("ysize"), 160.0f));
        VpGrid vpGrid = new VpGrid(cols, cellW, cellH);
        float spacing = parseFloat(props.get("spacing"), 0.0f);
        float hSpacing = parseFloat(props.get("xspacing"), spacing);
        float vSpacing = parseFloat(props.get("yspacing"), spacing);
        vpGrid.setSpacing(hSpacing, vSpacing);
        return vpGrid;
    }

    private static TextWidget buildTextWidget(String primaryArg, Map<String, String> props, ScriptRuntime runtime) {
        String raw = primaryArg != null ? primaryArg : props.getOrDefault("text", "");
        TextWidget widget = new TextWidget(interpolateText(raw, runtime));
        if (props.containsKey("size")) {
            widget.setFontSize(parseFloat(props.get("size"), TextWidget.DEFAULT_FONT_SIZE));
        }
        if (props.containsKey("color")) {
            widget.setColorHex(props.get("color"));
        }
        if (props.containsKey("drop_shadow")) {
            widget.setDropShadow(parseBoolean(props.get("drop_shadow"), false));
        }
        if (props.containsKey("cps")) {
            widget.setTypewriterCps(parseFloat(props.get("cps"), 0.0f));
        }
        return widget;
    }

    private static TextButton buildTextButton(
            String primaryArg,
            Map<String, String> props,
            ScriptRuntime runtime,
            Consumer<String> actionDispatcher
    ) {
        String label = interpolateText(primaryArg != null ? primaryArg : props.getOrDefault("text", ""), runtime);
        Runnable action = toActionRunnable(props.get("action"), actionDispatcher);
        TextButton btn = new TextButton(label, action);
        if (props.containsKey("selected")) {
            btn.setSelected(parseBoolean(props.get("selected"), false));
        }
        if (props.containsKey("size")) {
            btn.setFontSize(parseFloat(props.get("size"), TextButton.DEFAULT_FONT_SIZE));
        }
        return btn;
    }

    private static ImageButton buildImageButton(Map<String, String> props, Consumer<String> actionDispatcher) {
        String auto = props.get("auto");
        String idle = props.get("idle");
        String hover = props.get("hover");
        String disabled = props.getOrDefault("disabled", props.get("insensitive"));
        if (auto != null && !auto.isBlank()) {
            if (idle == null) {
                idle = auto.replace("%s", "idle");
            }
            if (hover == null) {
                hover = auto.replace("%s", "hover");
            }
            if (disabled == null) {
                disabled = auto.replace("%s", "insensitive");
            }
        }
        Runnable action = toActionRunnable(props.get("action"), actionDispatcher);
        ImageButton btn = new ImageButton(idle, hover, action);
        if (disabled != null) {
            btn.setDisabledPath(disabled);
        }
        return btn;
    }

    private static Bar buildBar(Map<String, String> props, StateStore state) {
        ValueBinding binding = parseValueBinding(props.get("value"), props.get("range"), state);
        Bar bar = new Bar(binding.initialValue(), binding.max());
        if (props.containsKey("color")) {
            bar.setFillColorHex(props.get("color"));
        }
        return bar;
    }

    private static VBar buildVBar(Map<String, String> props, StateStore state) {
        ValueBinding binding = parseValueBinding(props.get("value"), props.get("range"), state);
        VBar vbar = new VBar(binding.initialValue(), binding.max());
        if (props.containsKey("color")) {
            vbar.setFillColorHex(props.get("color"));
        }
        return vbar;
    }

    private static Slider buildSlider(Map<String, String> props, StateStore state) {
        ValueBinding binding = parseValueBinding(props.get("value"), props.get("range"), state);
        float min = parseFloat(props.get("min"), 0.0f);
        Consumer<Float> onChanged = (state != null && binding.varName() != null)
                ? val -> state.set(binding.varName(), val)
                : null;
        Slider slider = new Slider(binding.initialValue(), min, binding.max(), onChanged);
        if (props.containsKey("step")) {
            slider.setStep(parseFloat(props.get("step"), 0.0f));
        }
        return slider;
    }

    private static TextInput buildTextInput(
            Map<String, String> props,
            StateStore state,
            Consumer<String> actionDispatcher
    ) {
        String varName = null;
        String initial = props.getOrDefault("default", "");
        String valExpr = props.get("value");
        if (valExpr != null) {
            Matcher m = VARIABLE_INPUT_PATTERN.matcher(valExpr.trim());
            if (m.matches()) {
                varName = m.group(1);
                if (state != null && state.has(varName)) {
                    initial = state.getString(varName, initial);
                }
            }
        }
        final String targetVar = varName;
        String actionExpr = props.get("action");
        TextInput input = new TextInput(initial, submitted -> {
            if (state != null && targetVar != null) {
                state.set(targetVar, submitted);
            }
            if (actionDispatcher != null && actionExpr != null && !actionExpr.isBlank()) {
                actionDispatcher.accept(actionExpr);
            }
        });
        if (state != null && targetVar != null) {
            input.setOnChanged(text -> state.set(targetVar, text));
        }
        if (props.containsKey("length")) {
            input.setMaxLength(parseInt(props.get("length"), 0));
        }
        if (props.containsKey("allow")) {
            input.setAllowChars(props.get("allow"));
        }
        if (props.containsKey("placeholder")) {
            input.setPlaceholder(props.get("placeholder"));
        }
        return input;
    }

    private static Hotspot buildHotspot(String primaryArg, Map<String, String> props, Consumer<String> actionDispatcher) {
        Rect2f rect = parseRect(primaryArg);
        Hotspot hs = new Hotspot(rect, toActionRunnable(props.get("action"), actionDispatcher));
        if (props.containsKey("hovered_color")) {
            hs.setHoveredColorHex(props.get("hovered_color"));
        }
        return hs;
    }

    private static Hotbar buildHotbar(String primaryArg, Map<String, String> props, StateStore state) {
        Rect2f rect = parseRect(primaryArg);
        ValueBinding binding = parseValueBinding(props.get("value"), props.get("range"), state);
        Supplier<Float> supplier = (state != null && binding.varName() != null)
                ? () -> (float) state.getDouble(binding.varName(), 0.0)
                : binding::initialValue;
        return new Hotbar(rect, supplier, binding.max());
    }

    private static Imagemap buildImagemap(Map<String, String> props) {
        return new Imagemap(props.get("ground"), props.get("hover"));
    }

    private static DragItem buildDragItem(String primaryArg, Map<String, String> props) {
        String id = props.getOrDefault("drag_name", primaryArg != null ? primaryArg : "drag");
        DragItem item = new DragItem(id, props.get("payload"));
        if (props.containsKey("draggable")) {
            item.setDraggable(parseBoolean(props.get("draggable"), true));
        }
        if (props.containsKey("droppable")) {
            item.setDropTarget(parseBoolean(props.get("droppable"), false));
        }
        if (props.containsKey("drop_target")) {
            item.setDropTarget(parseBoolean(props.get("drop_target"), false));
        }
        if (props.containsKey("snappable")) {
            item.setSnappable(parseBoolean(props.get("snappable"), true));
        }
        return item;
    }

    private static CustomDisplayableWidget buildCustomWidget(String primaryArg, ScriptRuntime runtime) {
        CustomDisplayable instance = instantiateCustomDisplayable(primaryArg, runtime);
        return new CustomDisplayableWidget(instance);
    }

    private static CustomDisplayable instantiateCustomDisplayable(String className, ScriptRuntime runtime) {
        if (className != null && !className.isBlank()) {
            String trimmed = className.trim();
            if (runtime != null && runtime.getCompiledBlocks() != null) {
                JavaBlockCompiler.CompiledProjectBlocks compiled = runtime.getCompiledBlocks();
                Optional<Class<?>> userCls = compiled.findUserType(trimmed);
                if (userCls.isPresent() && CustomDisplayable.class.isAssignableFrom(userCls.get())) {
                    try {
                        return (CustomDisplayable) userCls.get().getDeclaredConstructor().newInstance();
                    } catch (ReflectiveOperationException ignored) {
                    }
                }
            }
            try {
                Class<?> cls = Class.forName(trimmed);
                if (CustomDisplayable.class.isAssignableFrom(cls)) {
                    return (CustomDisplayable) cls.getDeclaredConstructor().newInstance();
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return new CustomDisplayable() {
            @Override
            public void update(double dt) {
            }

            @Override
            public void render(
                    de.swfdtf.graphics.RenderPipeline2D pipeline,
                    Rect2f bounds,
                    float alpha,
                    ColorMatrix4f colorMatrix
            ) {
            }
        };
    }

    private static void applyCommonProperties(UiNode node, Map<String, String> props) {
        if (props.containsKey("id")) {
            node.setId(props.get("id"));
        }
        if (props.containsKey("xsize") || props.containsKey("width")) {
            node.setPreferredWidth(parseFloat(props.getOrDefault("xsize", props.get("width")), 0.0f));
        }
        if (props.containsKey("ysize") || props.containsKey("height")) {
            node.setPreferredHeight(parseFloat(props.getOrDefault("ysize", props.get("height")), 0.0f));
        }
        if (props.containsKey("padding")) {
            applyPaddingProp(node, props.get("padding"));
        }
        if (props.containsKey("xpadding") || props.containsKey("ypadding")) {
            float xp = parseFloat(props.get("xpadding"), node.getPaddingLeft());
            float yp = parseFloat(props.get("ypadding"), node.getPaddingTop());
            node.setPadding(xp, yp);
        }
        if (props.containsKey("background") && !(node instanceof Imagemap)) {
            String bg = props.get("background");
            if (bg.startsWith("#")) {
                node.setBackgroundColorHex(bg);
            }
        }
        if (props.containsKey("border_color")) {
            node.setBorderColorHex(props.get("border_color"));
        }
        if (props.containsKey("border_thickness")) {
            node.setBorderThickness(parseFloat(props.get("border_thickness"), node.getBorderThickness()));
        }
        if (props.containsKey("corner_radius")) {
            node.setCornerRadius(parseFloat(props.get("corner_radius"), node.getCornerRadius()));
        }
        if (props.containsKey("alpha")) {
            node.setAlpha(parseFloat(props.get("alpha"), 1.0f));
        }
        if (props.containsKey("sensitive")) {
            node.setEnabled(parseBoolean(props.get("sensitive"), true));
        } else if (props.containsKey("enabled")) {
            node.setEnabled(parseBoolean(props.get("enabled"), true));
        }
    }

    private static void applyPaddingProp(UiNode node, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String cleaned = raw.trim().replace("(", "").replace(")", "");
        String[] parts = cleaned.split("[,\\s]+");
        if (parts.length == 1) {
            node.setPadding(parseFloat(parts[0], 0.0f));
        } else if (parts.length == 2) {
            node.setPadding(parseFloat(parts[0], 0.0f), parseFloat(parts[1], 0.0f));
        } else if (parts.length >= 4) {
            node.setPadding(
                    parseFloat(parts[0], 0.0f),
                    parseFloat(parts[1], 0.0f),
                    parseFloat(parts[2], 0.0f),
                    parseFloat(parts[3], 0.0f)
            );
        }
    }

    private record ValueBinding(String varName, float initialValue, float max) {
    }

    private static ValueBinding parseValueBinding(String rawValue, String rawRange, StateStore state) {
        float fallbackMax = parseFloat(rawRange, 1.0f);
        if (rawValue == null || rawValue.isBlank()) {
            return new ValueBinding(null, 0.0f, Math.max(0.0001f, fallbackMax));
        }
        String trimmed = rawValue.trim();
        Matcher m = VARIABLE_VALUE_PATTERN.matcher(trimmed);
        if (m.matches()) {
            String varName = m.group(1);
            float max = m.group(2) != null ? parseFloat(m.group(2), fallbackMax) : fallbackMax;
            float current = (state != null && state.has(varName))
                    ? (float) state.getDouble(varName, 0.0)
                    : 0.0f;
            return new ValueBinding(varName, current, Math.max(0.0001f, max));
        }
        float lit = parseFloat(trimmed, 0.0f);
        return new ValueBinding(null, lit, Math.max(0.0001f, fallbackMax));
    }

    private static Rect2f parseRect(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Rect2f(0.0f, 0.0f, 0.0f, 0.0f);
        }
        String cleaned = raw.trim().replace("(", "").replace(")", "");
        String[] parts = cleaned.split("[,\\s]+");
        if (parts.length < 4) {
            return new Rect2f(0.0f, 0.0f, 0.0f, 0.0f);
        }
        return new Rect2f(
                parseFloat(parts[0], 0.0f),
                parseFloat(parts[1], 0.0f),
                parseFloat(parts[2], 0.0f),
                parseFloat(parts[3], 0.0f)
        );
    }

    private static String interpolateText(String raw, ScriptRuntime runtime) {
        if (raw == null) {
            return "";
        }
        if (runtime == null) {
            return raw;
        }
        StateStore state = runtime.getStateStore();
        return TextTagParser.interpolateVariables(raw, key -> state.get(key));
    }

    private static Runnable toActionRunnable(String actionExpr, Consumer<String> actionDispatcher) {
        if (actionExpr == null || actionExpr.isBlank()) {
            return null;
        }
        String trimmed = actionExpr.trim();
        return () -> {
            if (actionDispatcher != null) {
                actionDispatcher.accept(trimmed);
            }
        };
    }

    private static boolean evaluateConditionSafe(String expr, StateStore state, PersistentAccess persistent) {
        if (expr == null || expr.isBlank()) {
            return false;
        }
        try {
            return ConditionEvaluator.evaluateBoolean(expr, state, persistent);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static int parseGlfwKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return 0;
        }
        String norm = rawKey.trim().toUpperCase(Locale.ROOT);
        if (norm.startsWith("K_")) {
            norm = norm.substring(2);
        }
        return switch (norm) {
            case "ESCAPE", "ESC" -> GLFW.GLFW_KEY_ESCAPE;
            case "RETURN", "ENTER" -> GLFW.GLFW_KEY_ENTER;
            case "SPACE" -> GLFW.GLFW_KEY_SPACE;
            case "TAB" -> GLFW.GLFW_KEY_TAB;
            case "BACKSPACE" -> GLFW.GLFW_KEY_BACKSPACE;
            case "LEFT" -> GLFW.GLFW_KEY_LEFT;
            case "RIGHT" -> GLFW.GLFW_KEY_RIGHT;
            case "UP" -> GLFW.GLFW_KEY_UP;
            case "DOWN" -> GLFW.GLFW_KEY_DOWN;
            default -> (norm.length() == 1 && norm.charAt(0) >= 'A' && norm.charAt(0) <= 'Z')
                    ? GLFW.GLFW_KEY_A + (norm.charAt(0) - 'A')
                    : 0;
        };
    }

    private static float parseFloat(String raw, float fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double parseDouble(String raw, double fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(lower)) {
            return true;
        }
        if ("false".equals(lower)) {
            return false;
        }
        return fallback;
    }
}
