package de.swfdtf.tools;

import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SceneLayer;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.math.Transform2D;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.ui.Fixed;
import de.swfdtf.ui.HBox;
import de.swfdtf.ui.Screen;
import de.swfdtf.ui.ScreenManager;
import de.swfdtf.ui.Slider;
import de.swfdtf.ui.TextButton;
import de.swfdtf.ui.TextInput;
import de.swfdtf.ui.TextWidget;
import de.swfdtf.ui.ThemePalette;
import de.swfdtf.ui.VBox;
import de.swfdtf.ui.Viewport;
import de.swfdtf.ui.WindowBox;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * In-engine Live Director overlay ({@code Z = 1000}, toggleable via {@code D}) for inspecting
 * active stage sprites, interactively adjusting transform properties ({@code xpos}, {@code ypos},
 * {@code zoom}, {@code alpha}) and attributes, and atomically writing edits back into the
 * originating {@code .vjava} source script.
 */
public class LiveDirectorOverlay extends Screen {

    /** Canonical screen registry name. */
    public static final String SCREEN_NAME = "live_director";

    private static final float PANEL_X = 1410.0f;
    private static final float PANEL_Y = 36.0f;
    private static final float PANEL_W = 480.0f;
    private static final float PANEL_H = 980.0f;

    private static final Set<String> INLINE_ATL_PROPERTY_PREFIXES = Set.of(
            "xpos", "ypos", "xalign", "yalign", "xanchor", "yanchor",
            "zoom", "xzoom", "yzoom", "rotate", "alpha", "blur",
            "parallax_x", "parallax_y"
    );

    private ScriptSession session;
    private final ScreenManager screenManager;

    private final WindowBox panelBox;
    private final HBox spriteTabsRow;
    private final VBox attributeButtonsColumn;
    private final TextInput attributesInput;
    private final Slider xposSlider;
    private final Slider yposSlider;
    private final Slider zoomSlider;
    private final Slider alphaSlider;
    private final TextWidget xposValueLabel;
    private final TextWidget yposValueLabel;
    private final TextWidget zoomValueLabel;
    private final TextWidget alphaValueLabel;
    private final TextButton applyButton;
    private final TextButton refreshButton;
    private final TextWidget statusLabel;

    private final List<TextButton> spriteButtons = new ArrayList<>();
    private final List<TextButton> attributeButtons = new ArrayList<>();
    private String selectedSpriteTag;
    private boolean updatingControls = false;
    private boolean draggingStageSprite = false;
    private float dragOffsetX = 0.0f;
    private float dragOffsetY = 0.0f;
    private String lastGeneratedSnippet = "";

    /**
     * Creates a {@code LiveDirectorOverlay} bound to {@code session} without a {@link ScreenManager}.
     *
     * @param session active script session
     */
    public LiveDirectorOverlay(ScriptSession session) {
        this(session, null);
    }

    /**
     * Creates a {@code LiveDirectorOverlay} bound to {@code session} and optional {@link ScreenManager}.
     *
     * @param session       active script session
     * @param screenManager UI screen manager (nullable)
     */
    public LiveDirectorOverlay(ScriptSession session, ScreenManager screenManager) {
        super(SCREEN_NAME, SceneLayer.Z_INSPECTOR, false);
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.screenManager = screenManager;

        Fixed root = new Fixed() {
            @Override
            protected boolean onMouseDown(float lx, float ly, int button) {
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT
                        && !panelBox.getBounds().contains(lx, ly)
                        && selectedSpriteTag != null) {
                    SpriteNode sprite = session.getSceneController().getActiveSprite(selectedSpriteTag);
                    if (sprite != null) {
                        draggingStageSprite = true;
                        dragOffsetX = sprite.getTransform().getXpos() - lx;
                        dragOffsetY = sprite.getTransform().getYpos() - ly;
                        return true;
                    }
                }
                return super.onMouseDown(lx, ly, button);
            }

            @Override
            protected void onMouseMoved(float lx, float ly, boolean inside) {
                if (draggingStageSprite && selectedSpriteTag != null) {
                    applyDraggedCoordinates(lx, ly);
                }
                super.onMouseMoved(lx, ly, inside);
            }

            @Override
            protected boolean onMouseUp(float lx, float ly, int button) {
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && draggingStageSprite) {
                    applyDraggedCoordinates(lx, ly);
                    draggingStageSprite = false;
                    return true;
                }
                return super.onMouseUp(lx, ly, button);
            }
        };
        this.panelBox = new WindowBox("Live Director [D]");
        this.panelBox.setPadding(14.0f, 12.0f);
        this.panelBox.setBackgroundColorHex(ThemePalette.PANEL_GLASS)
                .setBorderColorHex(ThemePalette.ACCENT_IDLE)
                .setBorderThickness(1.5f);

        VBox content = new VBox();
        content.setSpacing(10.0f).setFillWidth(true);

        TextWidget spritesHeader = createLabel("Active Stage Sprites", 15.0f, ThemePalette.TEXT_MUTED);
        this.spriteTabsRow = new HBox();
        this.spriteTabsRow.setSpacing(8.0f).setAlignCenterY(true);
        this.spriteTabsRow.setPreferredHeight(38.0f);

        TextWidget attrsHeader = createLabel("Attributes (space-separated)", 15.0f, ThemePalette.TEXT_MUTED);
        this.attributesInput = new TextInput("", text -> applyAttributesFromInput());
        this.attributesInput.setPlaceholder("e.g. happy winter");
        this.attributesInput.setPreferredHeight(38.0f);
        this.attributesInput.setOnChanged(text -> applyAttributesFromInput());

        this.attributeButtonsColumn = new VBox();
        this.attributeButtonsColumn.setSpacing(6.0f).setFillWidth(true);
        Viewport attrViewport = new Viewport();
        attrViewport.setPreferredHeight(120.0f);
        attrViewport.addChild(attributeButtonsColumn);

        this.xposValueLabel = createLabel("xpos: 0.0", 15.0f, ThemePalette.TEXT_PRIMARY);
        this.xposSlider = createSlider(0.0f, -600.0f, 1920.0f, 1.0f, val -> onTransformSliderChanged("xpos", val));

        this.yposValueLabel = createLabel("ypos: 0.0", 15.0f, ThemePalette.TEXT_PRIMARY);
        this.yposSlider = createSlider(0.0f, -600.0f, 1080.0f, 1.0f, val -> onTransformSliderChanged("ypos", val));

        this.zoomValueLabel = createLabel("zoom: 1.00", 15.0f, ThemePalette.TEXT_PRIMARY);
        this.zoomSlider = createSlider(1.0f, 0.1f, 3.0f, 0.05f, val -> onTransformSliderChanged("zoom", val));

        this.alphaValueLabel = createLabel("alpha: 1.00", 15.0f, ThemePalette.TEXT_PRIMARY);
        this.alphaSlider = createSlider(1.0f, 0.0f, 1.0f, 0.05f, val -> onTransformSliderChanged("alpha", val));

        HBox actionRow = new HBox();
        actionRow.setSpacing(10.0f).setAlignCenterY(true);
        this.applyButton = createActionButton("Apply & Write to .vjava", 270.0f, 40.0f, () -> {
            if (selectedSpriteTag != null) {
                commitCurrentSpriteEdit(selectedSpriteTag);
            }
        });
        this.applyButton.setBackgroundColorHex(ThemePalette.ACCENT_IDLE);

        this.refreshButton = createActionButton("Refresh", 140.0f, 40.0f, this::refreshFromStage);
        actionRow.addChild(applyButton);
        actionRow.addChild(refreshButton);

        this.statusLabel = createLabel("Ready", 14.0f, ThemePalette.TEXT_MUTED);

        content.addChild(spritesHeader);
        content.addChild(spriteTabsRow);
        content.addChild(attrsHeader);
        content.addChild(attributesInput);
        content.addChild(attrViewport);
        content.addChild(xposValueLabel);
        content.addChild(xposSlider);
        content.addChild(yposValueLabel);
        content.addChild(yposSlider);
        content.addChild(zoomValueLabel);
        content.addChild(zoomSlider);
        content.addChild(alphaValueLabel);
        content.addChild(alphaSlider);
        content.addChild(actionRow);
        content.addChild(statusLabel);

        this.panelBox.addChild(content);
        root.addChildAt(panelBox, PANEL_X, PANEL_Y, PANEL_W, PANEL_H);
        setRoot(root);

        addKeyBinding(GLFW.GLFW_KEY_D, this::toggle);
        super.setVisible(false);
    }

    /**
     * Toggles visibility of the Live Director overlay and refreshes stage state when opened.
     *
     * @return new visibility state ({@code true} if now visible)
     */
    public boolean toggle() {
        boolean next = !isVisible();
        setVisible(next);
        return next;
    }

    @Override
    public Screen setVisible(boolean visible) {
        super.setVisible(visible);
        if (visible) {
            refreshFromStage();
            float logicalW = screenManager != null ? screenManager.getLogicalWidth() : 1920.0f;
            float logicalH = screenManager != null ? screenManager.getLogicalHeight() : 1080.0f;
            layout(logicalW, logicalH);
        }
        return this;
    }

    @Override
    public void onShow() {
        refreshFromStage();
    }

    @Override
    public boolean triggerKeyBindings(int key, int mods) {
        if (key == GLFW.GLFW_KEY_D && mods == 0) {
            if (isTextInputFocused()) {
                return false;
            }
            toggle();
            return true;
        }
        return super.triggerKeyBindings(key, mods);
    }

    /**
     * Synchronizes sprite tabs, transform sliders, and attribute controls from {@link SceneController}.
     */
    public void refreshFromStage() {
        SceneController controller = session.getSceneController();
        Map<String, SpriteNode> sprites = controller.getActiveSprites();

        spriteTabsRow.clearChildren();
        spriteButtons.clear();

        if (sprites.isEmpty()) {
            selectedSpriteTag = null;
            attributeButtonsColumn.clearChildren();
            attributeButtons.clear();
            updatingControls = true;
            try {
                attributesInput.setText("");
                xposSlider.setValue(0.0f);
                yposSlider.setValue(0.0f);
                zoomSlider.setValue(1.0f);
                alphaSlider.setValue(1.0f);
                updateSliderLabels(0.0f, 0.0f, 1.0f, 1.0f);
            } finally {
                updatingControls = false;
            }
            statusLabel.setText("No active sprites on stage.");
            return;
        }

        if (selectedSpriteTag == null || !sprites.containsKey(selectedSpriteTag)) {
            selectedSpriteTag = sprites.keySet().iterator().next();
        }

        for (String tag : sprites.keySet()) {
            TextButton tab = createActionButton(tag, 108.0f, 34.0f, () -> selectSprite(tag));
            tab.setSelected(tag.equals(selectedSpriteTag));
            spriteTabsRow.addChild(tab);
            spriteButtons.add(tab);
        }

        populateControlsForSprite(sprites.get(selectedSpriteTag));
    }

    /**
     * Selects an active stage sprite by semantic tag and loads its transform and attributes into controls.
     *
     * @param tag active sprite tag
     */
    public void selectSprite(String tag) {
        if (tag == null) {
            return;
        }
        SpriteNode node = session.getSceneController().getActiveSprite(tag);
        if (node == null) {
            return;
        }
        this.selectedSpriteTag = tag;
        for (TextButton btn : spriteButtons) {
            btn.setSelected(tag.equals(btn.getText()));
        }
        populateControlsForSprite(node);
    }

    /**
     * Writes the live sprite's current attributes and transform back into the active {@code .vjava}
     * script file at the {@link VJavaAst.ShowStatement} associated with {@code tag}.
     *
     * @param tag semantic sprite tag on stage
     * @return {@code true} if the source file was located and atomically updated
     */
    public boolean commitCurrentSpriteEdit(String tag) {
        if (tag == null || tag.isBlank()) {
            return false;
        }
        SceneController controller = session.getSceneController();
        SpriteNode sprite = controller.getActiveSprite(tag);
        if (sprite == null) {
            statusLabel.setText("Sprite '" + tag + "' not active on stage.");
            return false;
        }

        ScriptRuntime runtime = session.getRuntime();
        VJavaAst.ProjectAst projectAst = runtime.getProjectAst();
        VJavaAst.ShowStatement targetShow = findRelevantShowStatement(runtime, tag);
        if (targetShow == null) {
            statusLabel.setText("No 'show " + tag + "' statement found in script AST.");
            return false;
        }

        Path scriptPath = resolveScriptFile(projectAst, targetShow.sourceFile());
        if (scriptPath == null) {
            statusLabel.setText("Could not resolve script file: " + targetShow.sourceFile());
            return false;
        }

        SceneController.StageEntry stageEntry = controller.snapshotState().activeSprites().get(tag);
        String positionExpr = buildPositionExprForSprite(sprite, stageEntry);
        boolean updated = updateShowLineInScript(
                scriptPath,
                targetShow.lineNumber(),
                tag,
                sprite.getAttributes(),
                positionExpr
        );
        if (updated) {
            statusLabel.setText("Saved '" + tag + "' to " + scriptPath.getFileName() + ":" + targetShow.lineNumber());
        } else {
            statusLabel.setText("Failed to update 'show " + tag + "' in " + scriptPath.getFileName());
        }
        return updated;
    }

    /**
     * Atomically updates a matching {@code show <tag>...} statement in {@code vjavaFile},
     * preserving exact leading indentation, trailing {@code with <transition>} clauses, and
     * trailing comments while replacing attributes, {@code at <pos>}, and/or inline ATL properties.
     *
     * @param vjavaFile    target {@code .vjava} script file path
     * @param oneBasedLine 1-based hint line number (nearest matching {@code show <tag>} line is preferred)
     * @param tag          semantic sprite tag
     * @param attributes   replacement attribute list (may be empty or {@code null})
     * @param positionExpr replacement position expression or inline ATL properties (may be {@code null})
     * @return {@code true} if a matching {@code show <tag>} statement was found and written atomically
     */
    public static boolean updateShowLineInScript(
            Path vjavaFile,
            int oneBasedLine,
            String tag,
            List<String> attributes,
            String positionExpr
    ) {
        if (vjavaFile == null || !Files.isRegularFile(vjavaFile) || tag == null || tag.isBlank()) {
            return false;
        }

        String rawContent;
        try {
            rawContent = Files.readString(vjavaFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return false;
        }

        String lineEnding = rawContent.contains("\r\n") ? "\r\n" : "\n";
        boolean hadTrailingNewline = rawContent.endsWith("\n");
        String normalized = rawContent.replace("\r\n", "\n");
        List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
        if (hadTrailingNewline && !lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }

        int targetIdx = findMatchingShowLineIndex(lines, oneBasedLine, tag.trim());
        if (targetIdx < 0) {
            return false;
        }

        String originalLine = lines.get(targetIdx);
        String leadingIndent = extractLeadingWhitespace(originalLine);
        String afterIndent = originalLine.substring(leadingIndent.length());

        CommentSplit split = splitTrailingComment(afterIndent);
        String codePart = split.code().stripTrailing();
        boolean originallyHadColon = endsWithColonOutsideQuotes(codePart);
        String headerWithoutColon = originallyHadColon
                ? codePart.substring(0, codePart.length() - 1).stripTrailing()
                : codePart;

        String transitionExpr = extractWithClause(headerWithoutColon);
        List<String> cleanAttrs = sanitizeAttributes(attributes);
        ParsedPositionSpec posSpec = parsePositionSpec(positionExpr);

        StringBuilder rewritten = new StringBuilder();
        rewritten.append(leadingIndent).append("show ").append(tag.trim());
        for (String attr : cleanAttrs) {
            rewritten.append(' ').append(attr);
        }
        if (posSpec.positionClause() != null && !posSpec.positionClause().isBlank()) {
            rewritten.append(" at ").append(posSpec.positionClause());
        }
        if (transitionExpr != null && !transitionExpr.isBlank()) {
            rewritten.append(" with ").append(transitionExpr);
        }
        if (!posSpec.inlineAtlLines().isEmpty()) {
            rewritten.append(':');
        }
        if (!split.commentSuffix().isEmpty()) {
            rewritten.append(split.commentSuffix());
        }

        int blockEndExclusive = targetIdx + 1;
        if (originallyHadColon) {
            blockEndExclusive = findIndentedBlockEnd(lines, targetIdx, leadingIndent.length());
        }

        List<String> replacementLines = new ArrayList<>();
        replacementLines.add(rewritten.toString());
        if (!posSpec.inlineAtlLines().isEmpty()) {
            String childIndent = leadingIndent + "    ";
            for (String atlLine : posSpec.inlineAtlLines()) {
                replacementLines.add(childIndent + atlLine);
            }
        }

        lines.subList(targetIdx, blockEndExclusive).clear();
        lines.addAll(targetIdx, replacementLines);

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out.append(lineEnding);
            }
            out.append(lines.get(i));
        }
        if (hadTrailingNewline) {
            out.append(lineEnding);
        }

        return writeAtomicUtf8(vjavaFile, out.toString());
    }

    // ---- Internal Stage & Write-Back Helpers ----

    private void populateControlsForSprite(SpriteNode node) {
        if (node == null) {
            return;
        }
        updatingControls = true;
        try {
            String attrsText = String.join(" ", node.getAttributes());
            attributesInput.setText(attrsText);
            rebuildAttributePresets(node);

            Transform2D t = node.getTransform();
            float logicalW = screenManager != null ? screenManager.getLogicalWidth() : 1920.0f;
            float logicalH = screenManager != null ? screenManager.getLogicalHeight() : 1080.0f;
            float resolvedX = t.hasXalign()
                    ? t.resolveBounds(node.getWidth(), node.getHeight(), logicalW, logicalH).x()
                    : t.getXpos();
            float resolvedY = t.hasXalign() || t.hasYalign()
                    ? t.resolveBounds(node.getWidth(), node.getHeight(), logicalW, logicalH).y()
                    : t.getYpos();

            xposSlider.setValue(resolvedX);
            yposSlider.setValue(resolvedY);
            zoomSlider.setValue(t.getZoom());
            alphaSlider.setValue(t.getAlpha());
            updateSliderLabels(
                    xposSlider.getValue(), yposSlider.getValue(), zoomSlider.getValue(), alphaSlider.getValue());
        } finally {
            updatingControls = false;
        }
    }

    private void rebuildAttributePresets(SpriteNode node) {
        attributeButtonsColumn.clearChildren();
        attributeButtons.clear();

        Set<String> availableAttrs = new LinkedHashSet<>();
        String tag = node.getImageTag();
        VJavaAst.ProjectAst ast = session.getRuntime().getProjectAst();

        VJavaAst.LayeredImageDef layeredDef = ast.layeredImages().get(tag);
        if (layeredDef != null) {
            for (VJavaAst.LayeredEntry entry : layeredDef.entries()) {
                if (entry instanceof VJavaAst.GroupLayerEntry group) {
                    for (VJavaAst.AttributeEntry attr : group.attributes()) {
                        availableAttrs.add(attr.name());
                    }
                }
            }
        }
        for (VJavaAst.ImageDef img : ast.images().values()) {
            if (tag.equals(img.tag())) {
                availableAttrs.addAll(img.attributes());
            }
        }

        if (availableAttrs.isEmpty()) {
            return;
        }

        HBox currentRow = new HBox().setSpacing(6.0f);
        int countInRow = 0;
        for (String attr : availableAttrs) {
            boolean active = node.getAttributes().contains(attr);
            TextButton btn = createActionButton(attr, 134.0f, 30.0f, () -> toggleSpriteAttribute(attr));
            btn.setSelected(active);
            currentRow.addChild(btn);
            attributeButtons.add(btn);
            countInRow++;
            if (countInRow >= 3) {
                attributeButtonsColumn.addChild(currentRow);
                currentRow = new HBox().setSpacing(6.0f);
                countInRow = 0;
            }
        }
        if (countInRow > 0) {
            attributeButtonsColumn.addChild(currentRow);
        }
    }

    private void toggleSpriteAttribute(String attr) {
        if (selectedSpriteTag == null) {
            return;
        }
        SpriteNode sprite = session.getSceneController().getActiveSprite(selectedSpriteTag);
        if (sprite == null) {
            return;
        }
        List<String> updated = new ArrayList<>(sprite.getAttributes());
        if (updated.contains(attr)) {
            updated.remove(attr);
        } else {
            updated.add(attr);
        }
        applyAttributesToStage(updated);
    }

    private void applyAttributesFromInput() {
        if (updatingControls || selectedSpriteTag == null) {
            return;
        }
        String raw = attributesInput.getText();
        List<String> parsed = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            for (String token : raw.trim().split("\\s+")) {
                if (!token.isBlank()) {
                    parsed.add(token.trim());
                }
            }
        }
        applyAttributesToStage(parsed);
    }

    private void applyAttributesToStage(List<String> nextAttributes) {
        SpriteNode current = session.getSceneController().getActiveSprite(selectedSpriteTag);
        if (current == null) {
            return;
        }
        Transform2D preserved = current.getTransform().copy();
        SpriteNode updated = session.getSceneController().showSprite(selectedSpriteTag, nextAttributes, null, null);
        updated.setTransform(preserved);
        populateControlsForSprite(updated);
    }

    private void onTransformSliderChanged(String property, float value) {
        if (updatingControls || selectedSpriteTag == null) {
            return;
        }
        SpriteNode sprite = session.getSceneController().getActiveSprite(selectedSpriteTag);
        if (sprite == null) {
            return;
        }
        Transform2D t = sprite.getTransform();
        switch (property) {
            case "xpos" -> t.setXalign(Float.NaN).setXanchor(0.0f).setXpos(value);
            case "ypos" -> t.setYalign(Float.NaN).setYanchor(0.0f).setYpos(value);
            case "zoom" -> t.setZoom(value);
            case "alpha" -> t.setAlpha(value);
            default -> {
                // Ignore unsupported slider key
            }
        }
        updateSliderLabels(xposSlider.getValue(), yposSlider.getValue(), zoomSlider.getValue(), alphaSlider.getValue());
        publishTransformSnippet(selectedSpriteTag, t.getXpos(), t.getYpos());
    }

    private void applyDraggedCoordinates(float lx, float ly) {
        SpriteNode sprite = session.getSceneController().getActiveSprite(selectedSpriteTag);
        if (sprite == null) {
            return;
        }
        float nx = Math.clamp(lx + dragOffsetX, 0.0f, 1920.0f);
        float ny = Math.clamp(ly + dragOffsetY, 0.0f, 1080.0f);
        Transform2D t = sprite.getTransform();
        t.setXalign(Float.NaN).setXanchor(0.0f).setXpos(nx);
        t.setYalign(Float.NaN).setYanchor(0.0f).setYpos(ny);
        updatingControls = true;
        try {
            xposSlider.setValue(nx);
            yposSlider.setValue(ny);
            updateSliderLabels(nx, ny, zoomSlider.getValue(), alphaSlider.getValue());
        } finally {
            updatingControls = false;
        }
        publishTransformSnippet(selectedSpriteTag, nx, ny);
    }

    private void publishTransformSnippet(String tag, float xpos, float ypos) {
        if (tag == null || tag.isBlank()) {
            return;
        }
        String snippet = String.format(Locale.US, "move %s xpos %.0f ypos %.0f", tag, xpos, ypos);
        this.lastGeneratedSnippet = snippet;
        statusLabel.setText(snippet);
        if (session.getEngineApi() != null) {
            session.getEngineApi().setProperty("studio.lastTransformSnippet", snippet);
        }
    }

    /**
     * Returns the most recently generated {@code move ... xpos ... ypos ...} transform snippet.
     *
     * @return last generated transform snippet (empty string if none yet)
     */
    public String getLastGeneratedSnippet() {
        return lastGeneratedSnippet;
    }

    private void updateSliderLabels(float xpos, float ypos, float zoom, float alpha) {
        xposValueLabel.setText(String.format(Locale.ROOT, "xpos: %.1f", xpos));
        yposValueLabel.setText(String.format(Locale.ROOT, "ypos: %.1f", ypos));
        zoomValueLabel.setText(String.format(Locale.ROOT, "zoom: %.2f", zoom));
        alphaValueLabel.setText(String.format(Locale.ROOT, "alpha: %.2f", alpha));
    }

    private boolean isTextInputFocused() {
        return screenManager != null && screenManager.getFocusedNode() instanceof TextInput;
    }

    private static VJavaAst.ShowStatement findRelevantShowStatement(ScriptRuntime runtime, String tag) {
        VJavaAst.ProjectAst ast = runtime.getProjectAst();
        String currentLabel = runtime.getCurrentLabel();
        if (currentLabel != null && ast.labels().containsKey(currentLabel)) {
            List<VJavaAst.Statement> stmts = ast.labels().get(currentLabel).statements();
            int maxIdx = Math.min(runtime.getStatementIndex(), stmts.size() - 1);
            for (int i = maxIdx; i >= 0; i--) {
                VJavaAst.ShowStatement match = findShowInStatementReverse(stmts.get(i), tag);
                if (match != null) {
                    return match;
                }
            }
        }

        for (VJavaAst.LabelNode label : ast.labels().values()) {
            for (int i = label.statements().size() - 1; i >= 0; i--) {
                VJavaAst.ShowStatement match = findShowInStatementReverse(label.statements().get(i), tag);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static VJavaAst.ShowStatement findShowInStatementReverse(VJavaAst.Statement stmt, String tag) {
        if (stmt instanceof VJavaAst.ShowStatement show && tag.equals(show.tag())) {
            return show;
        }
        if (stmt instanceof VJavaAst.IfStatement ifs) {
            for (int b = ifs.branches().size() - 1; b >= 0; b--) {
                List<VJavaAst.Statement> body = ifs.branches().get(b).body();
                for (int i = body.size() - 1; i >= 0; i--) {
                    VJavaAst.ShowStatement nested = findShowInStatementReverse(body.get(i), tag);
                    if (nested != null) {
                        return nested;
                    }
                }
            }
        }
        if (stmt instanceof VJavaAst.MenuStatement ms) {
            for (int c = ms.choices().size() - 1; c >= 0; c--) {
                List<VJavaAst.Statement> body = ms.choices().get(c).body();
                for (int i = body.size() - 1; i >= 0; i--) {
                    VJavaAst.ShowStatement nested = findShowInStatementReverse(body.get(i), tag);
                    if (nested != null) {
                        return nested;
                    }
                }
            }
        }
        return null;
    }

    private static Path resolveScriptFile(VJavaAst.ProjectAst ast, String sourceFile) {
        if (sourceFile == null || sourceFile.isBlank()) {
            return null;
        }
        Path direct = Path.of(sourceFile);
        if (direct.isAbsolute() && Files.isRegularFile(direct)) {
            return direct;
        }
        if (ast.projectRoot() != null) {
            Path resolved = ast.projectRoot().resolve(sourceFile).normalize();
            if (Files.isRegularFile(resolved)) {
                return resolved;
            }
        }
        for (Path script : ast.scriptFiles()) {
            String normalized = script.toString().replace('\\', '/');
            if (normalized.endsWith("/" + sourceFile) || script.getFileName().toString().equals(sourceFile)) {
                return script;
            }
        }
        return null;
    }

    private String buildPositionExprForSprite(SpriteNode sprite, SceneController.StageEntry stageEntry) {
        Transform2D t = sprite.getTransform();
        String positionName = stageEntry != null ? stageEntry.positionName() : null;
        boolean defaultScaleAlpha = Math.abs(t.getZoom() - 1.0f) < 1e-4f && Math.abs(t.getAlpha() - 1.0f) < 1e-4f;

        if ((t.hasXalign() || t.hasYalign()) && positionName != null && !positionName.isBlank()) {
            if (defaultScaleAlpha) {
                return positionName;
            }
            return positionName + ":\n"
                    + String.format(Locale.ROOT, "zoom %.2f alpha %.2f", t.getZoom(), t.getAlpha());
        }

        if (defaultScaleAlpha && Math.abs(t.getXpos()) < 1e-4f
                && Math.abs(t.getYpos()) < 1e-4f && positionName != null) {
            return positionName;
        }

        return String.format(
                Locale.ROOT,
                "xpos %.1f ypos %.1f zoom %.2f alpha %.2f",
                t.getXpos(),
                t.getYpos(),
                t.getZoom(),
                t.getAlpha()
        );
    }

    private static int findMatchingShowLineIndex(List<String> lines, int oneBasedLine, String tag) {
        int preferredIdx = oneBasedLine - 1;
        if (preferredIdx >= 0 && preferredIdx < lines.size() && isShowLineForTag(lines.get(preferredIdx), tag)) {
            return preferredIdx;
        }
        int bestIdx = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < lines.size(); i++) {
            if (isShowLineForTag(lines.get(i), tag)) {
                int dist = oneBasedLine > 0 ? Math.abs(i - preferredIdx) : i;
                if (dist < bestDistance) {
                    bestDistance = dist;
                    bestIdx = i;
                }
            }
        }
        return bestIdx;
    }

    private static boolean isShowLineForTag(String line, String tag) {
        String trimmed = splitTrailingComment(line.stripLeading()).code().trim();
        if (trimmed.endsWith(":")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        if (!trimmed.startsWith("show")) {
            return false;
        }
        if (trimmed.length() <= 4 || !Character.isWhitespace(trimmed.charAt(4))) {
            return false;
        }
        String rest = trimmed.substring(4).stripLeading();
        if (!rest.startsWith(tag)) {
            return false;
        }
        return rest.length() == tag.length() || Character.isWhitespace(rest.charAt(tag.length()));
    }

    private static String extractLeadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    private record CommentSplit(String code, String commentSuffix) {
    }

    private static CommentSplit splitTrailingComment(String text) {
        boolean inDouble = false;
        boolean inSingle = false;
        boolean escaped = false;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inDouble) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (inSingle) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (c == '"') {
                inDouble = true;
            } else if (c == '\'') {
                inSingle = true;
            } else if (c == '#' || (c == '/' && i + 1 < len && text.charAt(i + 1) == '/')) {
                int suffixStart = i;
                while (suffixStart > 0
                        && (text.charAt(suffixStart - 1) == ' ' || text.charAt(suffixStart - 1) == '\t')) {
                    suffixStart--;
                }
                return new CommentSplit(text.substring(0, suffixStart), text.substring(suffixStart));
            }
        }
        return new CommentSplit(text, "");
    }

    private static boolean endsWithColonOutsideQuotes(String line) {
        boolean inDouble = false;
        boolean inSingle = false;
        boolean escaped = false;
        int lastNonWs = -1;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                escaped = false;
                lastNonWs = i;
                continue;
            }
            if (inDouble) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inDouble = false;
                }
                lastNonWs = i;
                continue;
            }
            if (inSingle) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '\'') {
                    inSingle = false;
                }
                lastNonWs = i;
                continue;
            }
            if (c == '"') {
                inDouble = true;
                lastNonWs = i;
            } else if (c == '\'') {
                inSingle = true;
                lastNonWs = i;
            } else if (!Character.isWhitespace(c)) {
                lastNonWs = i;
            }
        }
        return lastNonWs >= 0 && line.charAt(lastNonWs) == ':';
    }

    private static String extractWithClause(String headerWithoutColon) {
        String rest = headerWithoutColon.substring(4).trim();
        int withIdx = findKeywordOutsideQuotesAndBrackets(rest, "with");
        if (withIdx < 0) {
            return null;
        }
        int atIdx = findKeywordOutsideQuotesAndBrackets(rest, "at");
        if (atIdx > withIdx) {
            return rest.substring(withIdx + 4, atIdx).trim();
        }
        return rest.substring(withIdx + 4).trim();
    }

    private static int findKeywordOutsideQuotesAndBrackets(String text, String keyword) {
        boolean inDouble = false;
        boolean inSingle = false;
        boolean escaped = false;
        int parenDepth = 0;
        int bracketDepth = 0;
        int len = text.length();
        int kwLen = keyword.length();

        for (int i = 0; i <= len - kwLen; i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inDouble) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (inSingle) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (c == '"') {
                inDouble = true;
            } else if (c == '\'') {
                inSingle = true;
            } else if (c == '(') {
                parenDepth++;
            } else if (c == ')' && parenDepth > 0) {
                parenDepth--;
            } else if (c == '[') {
                bracketDepth++;
            } else if (c == ']' && bracketDepth > 0) {
                bracketDepth--;
            } else if (parenDepth == 0 && bracketDepth == 0 && text.regionMatches(i, keyword, 0, kwLen)) {
                boolean leftOk = i == 0 || Character.isWhitespace(text.charAt(i - 1));
                boolean rightOk = (i + kwLen) == len || Character.isWhitespace(text.charAt(i + kwLen));
                if (leftOk && rightOk) {
                    return i;
                }
            }
        }
        return -1;
    }

    private record ParsedPositionSpec(String positionClause, List<String> inlineAtlLines) {
    }

    private static ParsedPositionSpec parsePositionSpec(String rawPositionExpr) {
        if (rawPositionExpr == null || rawPositionExpr.isBlank()) {
            return new ParsedPositionSpec(null, List.of());
        }
        String trimmed = rawPositionExpr.replace("\r\n", "\n").trim();
        if (trimmed.contains("\n")) {
            String[] splitLines = trimmed.split("\n");
            String first = splitLines[0].trim();
            if (first.endsWith(":")) {
                first = first.substring(0, first.length() - 1).trim();
            }
            first = stripLeadingAt(first);
            List<String> atl = new ArrayList<>();
            for (int i = 1; i < splitLines.length; i++) {
                String line = splitLines[i].trim();
                if (!line.isEmpty()) {
                    atl.add(line);
                }
            }
            return new ParsedPositionSpec(first.isEmpty() ? null : first, List.copyOf(atl));
        }

        String noAt = stripLeadingAt(trimmed);
        if (looksLikeInlineAtlProperties(noAt)) {
            return new ParsedPositionSpec(null, List.of(noAt));
        }
        return new ParsedPositionSpec(noAt.isEmpty() ? null : noAt, List.of());
    }

    private static String stripLeadingAt(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("at") && trimmed.length() > 2 && Character.isWhitespace(trimmed.charAt(2))) {
            return trimmed.substring(2).trim();
        }
        return trimmed;
    }

    private static boolean looksLikeInlineAtlProperties(String expr) {
        String[] parts = expr.split("\\s+");
        if (parts.length < 2) {
            return false;
        }
        String first = parts[0].toLowerCase(Locale.ROOT);
        int eqIdx = first.indexOf('=');
        String key = eqIdx > 0 ? first.substring(0, eqIdx) : first;
        return INLINE_ATL_PROPERTY_PREFIXES.contains(key);
    }

    private static List<String> sanitizeAttributes(List<String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(attributes.size());
        for (String attr : attributes) {
            if (attr != null && !attr.isBlank()) {
                result.add(attr.trim());
            }
        }
        return result;
    }

    private static int findIndentedBlockEnd(List<String> lines, int headerIdx, int headerIndentCols) {
        int i = headerIdx + 1;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.isBlank()) {
                int lookahead = i + 1;
                while (lookahead < lines.size() && lines.get(lookahead).isBlank()) {
                    lookahead++;
                }
                if (lookahead < lines.size()
                        && extractLeadingWhitespace(lines.get(lookahead)).length() > headerIndentCols) {
                    i = lookahead;
                    continue;
                }
                break;
            }
            if (extractLeadingWhitespace(line).length() > headerIndentCols) {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    private static boolean writeAtomicUtf8(Path targetFile, String content) {
        Path normalizedTarget = targetFile.toAbsolutePath().normalize();
        Path parent = normalizedTarget.getParent();
        if (parent == null) {
            return false;
        }
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile(parent, normalizedTarget.getFileName().toString() + ".", ".tmp");
            Files.writeString(tempFile, content, StandardCharsets.UTF_8);
            try {
                Files.move(
                        tempFile,
                        normalizedTarget,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tempFile, normalizedTarget, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Best-effort cleanup
                }
            }
        }
    }

    private static TextWidget createLabel(String text, float fontSize, String colorHex) {
        TextWidget label = new TextWidget(text);
        label.setFontSize(fontSize).setColorHex(colorHex);
        return label;
    }

    private static Slider createSlider(
            float value, float min, float max, float step, java.util.function.Consumer<Float> onChanged) {
        Slider slider = new Slider(value, min, max, onChanged);
        slider.setStep(step);
        slider.setPreferredSize(430.0f, 26.0f);
        return slider;
    }

    private static TextButton createActionButton(String text, float width, float height, Runnable onClick) {
        TextButton button = new TextButton(text, onClick);
        button.setFontSize(15.0f).setPreferredSize(width, height);
        return button;
    }

    // ---- Public Accessors ----

    /**
     * Returns the bound script session.
     *
     * @return bound {@link ScriptSession}
     */
    public ScriptSession getSession() {
        return session;
    }

    /**
     * Rebinds this overlay to an updated {@link ScriptSession} (e.g. after Live Reload).
     *
     * @param session new script session
     */
    public void setSession(ScriptSession session) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        if (isVisible()) {
            refreshFromStage();
        }
    }

    /**
     * Returns the currently selected stage sprite tag.
     *
     * @return currently selected stage sprite tag, or {@code null}
     */
    public String getSelectedSpriteTag() {
        return selectedSpriteTag;
    }

    /**
     * Returns the root inspector panel window box.
     *
     * @return root {@link WindowBox} inspector panel
     */
    public WindowBox getPanelBox() {
        return panelBox;
    }

    /**
     * Returns the {@code xpos} slider control.
     *
     * @return {@code xpos} slider control
     */
    public Slider getXposSlider() {
        return xposSlider;
    }

    /**
     * Returns the {@code ypos} slider control.
     *
     * @return {@code ypos} slider control
     */
    public Slider getYposSlider() {
        return yposSlider;
    }

    /**
     * Returns the {@code zoom} slider control.
     *
     * @return {@code zoom} slider control
     */
    public Slider getZoomSlider() {
        return zoomSlider;
    }

    /**
     * Returns the {@code alpha} slider control.
     *
     * @return {@code alpha} slider control
     */
    public Slider getAlphaSlider() {
        return alphaSlider;
    }

    /**
     * Returns the attributes text input field.
     *
     * @return attributes text input field
     */
    public TextInput getAttributesInput() {
        return attributesInput;
    }

    /**
     * Returns the Apply &amp; Write button.
     *
     * @return {@code Apply & Write to .vjava} button
     */
    public TextButton getApplyButton() {
        return applyButton;
    }

    /**
     * Returns the stage refresh button.
     *
     * @return stage refresh button
     */
    public TextButton getRefreshButton() {
        return refreshButton;
    }

    /**
     * Returns the status feedback text widget.
     *
     * @return status feedback text widget
     */
    public TextWidget getStatusLabel() {
        return statusLabel;
    }

    /**
     * Returns an immutable view of active sprite selection buttons.
     *
     * @return immutable view of active sprite selection buttons
     */
    public List<TextButton> getSpriteButtons() {
        return Collections.unmodifiableList(spriteButtons);
    }
}
