package de.swfdtf.tools;

import de.swfdtf.parser.VJavaAst;
import de.swfdtf.parser.VJavaLexer;
import de.swfdtf.parser.VJavaParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Visual No-Code Node-Graph Editor model supporting drag-and-drop label positioning, choice/jump
 * linking, and lossless {@code .vjava} round-trip export that preserves manual {@code @->{ ... }<<}
 * Java blocks character-for-character ({@code #1019}).
 */
public final class VisualNodeGraphEditor {

    /**
     * Editable visual dialogue entry inside a story node.
     *
     * @param speakerId optional speaker ID (nullable/blank for narrator)
     * @param text      dialogue line text
     */
    public record DialogueEntry(String speakerId, String text) {
        /** Normalizes optional strings. */
        public DialogueEntry {
            speakerId = speakerId != null ? speakerId.trim() : "";
            text = text != null ? text : "";
        }
    }

    /**
     * Outgoing menu choice port on a story node.
     *
     * @param choiceText  choice button text displayed to player
     * @param targetLabel target label name linked from this port
     */
    public record ChoicePort(String choiceText, String targetLabel) {
        /** Validates choice text and target label. */
        public ChoicePort {
            Objects.requireNonNull(choiceText, "choiceText must not be null");
            Objects.requireNonNull(targetLabel, "targetLabel must not be null");
        }
    }

    /**
     * Verbatim manual source slice (especially {@code @->{ ... }<<} Java blocks) preserved untouched
     * across visual graph import and re-export round-trips ({@code #1019}).
     *
     * @param orderIndex zero-based statement order index inside the label
     * @param rawCode    exact verbatim block text
     */
    public record PreservedJavaBlock(int orderIndex, String rawCode) {
        /** Enforces non-null verbatim block text. */
        public PreservedJavaBlock {
            Objects.requireNonNull(rawCode, "rawCode must not be null");
        }
    }

    /**
     * Single story label node placed on the 2D Studio node-graph canvas.
     *
     * @param labelName          unique {@code label} name
     * @param canvasX            2D editor X coordinate
     * @param canvasY            2D editor Y coordinate
     * @param sceneTag           optional {@code scene <bgTag>} statement at label start
     * @param dialogueLines      dialogue entries in execution order
     * @param choicePorts        outgoing menu choice ports
     * @param jumpTarget         optional trailing direct {@code jump <label>} target
     * @param preservedJavaBlocks manual {@code @->{ ... }<<} blocks kept verbatim on export
     */
    public record StoryNode(
            String labelName,
            float canvasX,
            float canvasY,
            String sceneTag,
            List<DialogueEntry> dialogueLines,
            List<ChoicePort> choicePorts,
            String jumpTarget,
            List<PreservedJavaBlock> preservedJavaBlocks
    ) {
        /** Enforces non-null fields and immutable collections. */
        public StoryNode {
            Objects.requireNonNull(labelName, "labelName must not be null");
            if (labelName.isBlank()) {
                throw new IllegalArgumentException("labelName must not be blank");
            }
            sceneTag = sceneTag != null ? sceneTag.trim() : "";
            dialogueLines = dialogueLines != null ? List.copyOf(dialogueLines) : List.of();
            choicePorts = choicePorts != null ? List.copyOf(choicePorts) : List.of();
            jumpTarget = jumpTarget != null ? jumpTarget.trim() : "";
            preservedJavaBlocks = preservedJavaBlocks != null ? List.copyOf(preservedJavaBlocks) : List.of();
        }
    }

    /**
     * Visual directed edge connecting two nodes on the StoryGraph canvas.
     *
     * @param fromLabel source node label
     * @param toLabel   target node label
     * @param portLabel port description ({@code "jump"} or choice text)
     */
    public record StoryEdge(String fromLabel, String toLabel, String portLabel) {
    }

    /**
     * Mutable No-Code StoryGraph canvas holding prelude declarations (including top-level Java blocks)
     * and placed {@link StoryNode}s.
     */
    public static final class StoryGraph {

        private final String preludeText;
        private final Map<String, StoryNode> nodes = new LinkedHashMap<>();

        /**
         * Creates an empty {@code StoryGraph}.
         *
         * @param preludeText top-level declarations/blocks prior to labels (preserved verbatim)
         */
        public StoryGraph(String preludeText) {
            this.preludeText = preludeText != null ? preludeText : "";
        }

        /**
         * Returns the preserved top-level script prelude before the first {@code label}.
         *
         * @return verbatim prelude string
         */
        public String getPreludeText() {
            return preludeText;
        }

        /**
         * Registers or replaces a node in the graph.
         *
         * @param node story node
         * @return {@code this} for fluent chaining
         */
        public StoryGraph putNode(StoryNode node) {
            Objects.requireNonNull(node, "node must not be null");
            nodes.put(node.labelName(), node);
            return this;
        }

        /**
         * Creates and places a new No-Code story node on the canvas.
         *
         * @param labelName new unique label identifier
         * @param canvasX   X coordinate on editor canvas
         * @param canvasY   Y coordinate on editor canvas
         * @return created {@link StoryNode}
         */
        public StoryNode addNode(String labelName, float canvasX, float canvasY) {
            StoryNode node = new StoryNode(
                    labelName, canvasX, canvasY, "", List.of(), List.of(), "", List.of());
            nodes.put(node.labelName(), node);
            return node;
        }

        /**
         * Updates the 2D canvas coordinates of an existing node (Drag &amp; Drop move).
         *
         * @param labelName target node label
         * @param newX      new canvas X coordinate
         * @param newY      new canvas Y coordinate
         * @return updated {@link StoryNode}
         */
        public StoryNode moveNode(String labelName, float newX, float newY) {
            StoryNode cur = requireNode(labelName);
            StoryNode updated = new StoryNode(
                    cur.labelName(),
                    newX,
                    newY,
                    cur.sceneTag(),
                    cur.dialogueLines(),
                    cur.choicePorts(),
                    cur.jumpTarget(),
                    cur.preservedJavaBlocks()
            );
            nodes.put(labelName, updated);
            return updated;
        }

        /**
         * Appends a dialogue line to an existing node.
         *
         * @param labelName target node label
         * @param speakerId optional speaker ID
         * @param text      dialogue text
         * @return updated {@link StoryNode}
         */
        public StoryNode addDialogueLine(String labelName, String speakerId, String text) {
            StoryNode cur = requireNode(labelName);
            List<DialogueEntry> lines = new ArrayList<>(cur.dialogueLines());
            lines.add(new DialogueEntry(speakerId, text));
            StoryNode updated = new StoryNode(
                    cur.labelName(), cur.canvasX(), cur.canvasY(), cur.sceneTag(),
                    lines, cur.choicePorts(), cur.jumpTarget(), cur.preservedJavaBlocks());
            nodes.put(labelName, updated);
            return updated;
        }

        /**
         * Connects a direct {@code jump} edge from {@code fromLabel} to {@code toLabel}.
         *
         * @param fromLabel source node label
         * @param toLabel   destination node label
         * @return updated source {@link StoryNode}
         */
        public StoryNode connectJump(String fromLabel, String toLabel) {
            StoryNode cur = requireNode(fromLabel);
            requireNode(toLabel);
            StoryNode updated = new StoryNode(
                    cur.labelName(), cur.canvasX(), cur.canvasY(), cur.sceneTag(),
                    cur.dialogueLines(), cur.choicePorts(), toLabel, cur.preservedJavaBlocks());
            nodes.put(fromLabel, updated);
            return updated;
        }

        /**
         * Adds a branching menu choice edge from {@code fromLabel} to {@code toLabel}.
         *
         * @param fromLabel  source node label
         * @param choiceText menu button label shown to players
         * @param toLabel    destination node label
         * @return updated source {@link StoryNode}
         */
        public StoryNode addChoiceEdge(String fromLabel, String choiceText, String toLabel) {
            StoryNode cur = requireNode(fromLabel);
            requireNode(toLabel);
            List<ChoicePort> ports = new ArrayList<>(cur.choicePorts());
            ports.add(new ChoicePort(choiceText, toLabel));
            StoryNode updated = new StoryNode(
                    cur.labelName(), cur.canvasX(), cur.canvasY(), cur.sceneTag(),
                    cur.dialogueLines(), ports, cur.jumpTarget(), cur.preservedJavaBlocks());
            nodes.put(fromLabel, updated);
            return updated;
        }

        /**
         * Returns an immutable view of all nodes keyed by label name.
         *
         * @return unmodifiable node map
         */
        public Map<String, StoryNode> getNodes() {
            return Collections.unmodifiableMap(nodes);
        }

        /**
         * Computes all outgoing directed visual edges across choices and jumps.
         *
         * @return immutable list of {@link StoryEdge}
         */
        public List<StoryEdge> getEdges() {
            List<StoryEdge> edges = new ArrayList<>();
            for (StoryNode node : nodes.values()) {
                for (ChoicePort port : node.choicePorts()) {
                    edges.add(new StoryEdge(node.labelName(), port.targetLabel(), port.choiceText()));
                }
                if (!node.jumpTarget().isBlank()) {
                    edges.add(new StoryEdge(node.labelName(), node.jumpTarget(), "jump"));
                }
            }
            return List.copyOf(edges);
        }

        private StoryNode requireNode(String labelName) {
            Objects.requireNonNull(labelName, "labelName must not be null");
            StoryNode node = nodes.get(labelName);
            if (node == null) {
                throw new IllegalArgumentException("Unknown StoryNode label: " + labelName);
            }
            return node;
        }
    }

    /** Creates a {@code VisualNodeGraphEditor}. */
    public VisualNodeGraphEditor() {
    }

    /**
     * Imports a {@code .vjava} script source into a {@link StoryGraph}, preserving top-level
     * declarations and every manual {@code @->{ ... }<<} Java block verbatim ({@code #1019}).
     *
     * @param sourceFile  script file identifier for diagnostics
     * @param vjavaSource raw {@code .vjava} script content (must not be {@code null})
     * @return editable {@link StoryGraph}
     */
    public StoryGraph importFromSource(Path sourceFile, String vjavaSource) {
        Objects.requireNonNull(vjavaSource, "vjavaSource must not be null");
        Path effectivePath = sourceFile != null ? sourceFile : Path.of("story.vjava");

        VJavaAst.ScriptFileAst ast = VJavaParser.parse(effectivePath, vjavaSource);
        List<VJavaLexer.Token> tokens = VJavaLexer.tokenize(
                vjavaSource, effectivePath.toString().replace('\\', '/'));

        String prelude = extractPreludeBeforeFirstLabel(vjavaSource, ast.labels());
        Map<String, List<PreservedJavaBlock>> verbatimJavaByLabel = extractVerbatimJavaBlocksByLabel(tokens);

        StoryGraph graph = new StoryGraph(prelude);
        int index = 0;
        for (VJavaAst.LabelNode label : ast.labels()) {
            String sceneTag = "";
            List<DialogueEntry> dialogues = new ArrayList<>();
            List<ChoicePort> choices = new ArrayList<>();
            String jumpTarget = "";

            for (VJavaAst.Statement stmt : label.statements()) {
                if (stmt instanceof VJavaAst.SceneStatement sc && sceneTag.isEmpty()) {
                    sceneTag = sc.bgTag();
                } else if (stmt instanceof VJavaAst.SayStatement say) {
                    dialogues.add(new DialogueEntry(say.speakerId(), say.text()));
                } else if (stmt instanceof VJavaAst.MenuStatement menu) {
                    for (VJavaAst.MenuChoice ch : menu.choices()) {
                        String target = resolveChoiceTarget(ch);
                        if (!target.isBlank()) {
                            choices.add(new ChoicePort(ch.text(), target));
                        }
                    }
                } else if (stmt instanceof VJavaAst.JumpStatement js) {
                    jumpTarget = js.targetLabel();
                }
            }

            float x = (index % 4) * 320.0f;
            float y = (index / 4) * 240.0f;
            index++;
            List<PreservedJavaBlock> preserved = verbatimJavaByLabel.getOrDefault(label.name(), List.of());
            graph.putNode(new StoryNode(
                    label.name(), x, y, sceneTag, dialogues, choices, jumpTarget, preserved));
        }
        return graph;
    }

    /**
     * Generates clean, valid {@code .vjava} source from {@code graph} while keeping every
     * preserved manual {@code @->{ ... }<<} Java block completely untouched ({@code #1019}).
     *
     * @param graph story graph to serialize (must not be {@code null})
     * @return formatted {@code .vjava} script source text
     */
    public String exportToVJava(StoryGraph graph) {
        Objects.requireNonNull(graph, "graph must not be null");
        StringBuilder out = new StringBuilder();
        String prelude = graph.getPreludeText().stripTrailing();
        if (!prelude.isEmpty()) {
            out.append(prelude).append("\n\n");
        }

        boolean firstNode = true;
        for (StoryNode node : graph.getNodes().values()) {
            if (!firstNode) {
                out.append('\n');
            }
            firstNode = false;
            out.append("label ").append(node.labelName()).append(":\n");

            boolean emittedStatement = false;
            if (!node.sceneTag().isBlank()) {
                out.append("    scene ").append(node.sceneTag()).append('\n');
                emittedStatement = true;
            }
            for (PreservedJavaBlock block : node.preservedJavaBlocks()) {
                emitPreservedJavaBlock(out, block.rawCode());
                emittedStatement = true;
            }
            for (DialogueEntry dlg : node.dialogueLines()) {
                out.append("    ");
                if (!dlg.speakerId().isBlank()) {
                    out.append(dlg.speakerId()).append(' ');
                }
                out.append('"').append(escapeVJavaString(dlg.text())).append('"').append('\n');
                emittedStatement = true;
            }
            if (!node.choicePorts().isEmpty()) {
                out.append("    menu:\n");
                for (ChoicePort port : node.choicePorts()) {
                    out.append("        \"").append(escapeVJavaString(port.choiceText())).append("\":\n");
                    out.append("            jump ").append(port.targetLabel()).append('\n');
                }
                emittedStatement = true;
            }
            if (!node.jumpTarget().isBlank()) {
                out.append("    jump ").append(node.jumpTarget()).append('\n');
                emittedStatement = true;
            } else if (!emittedStatement || node.choicePorts().isEmpty()) {
                out.append("    return\n");
            }
        }
        return out.toString();
    }

    private static String extractPreludeBeforeFirstLabel(String source, List<VJavaAst.LabelNode> labels) {
        if (labels.isEmpty()) {
            return source;
        }
        int firstLine = Math.max(1, labels.get(0).lineNumber());
        String[] lines = source.split("\n", -1);
        if (firstLine <= 1 || firstLine > lines.length + 1) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < firstLine - 1; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static Map<String, List<PreservedJavaBlock>> extractVerbatimJavaBlocksByLabel(
            List<VJavaLexer.Token> tokens
    ) {
        Map<String, List<PreservedJavaBlock>> byLabel = new LinkedHashMap<>();
        String currentLabel = null;
        int orderCounter = 0;

        for (VJavaLexer.Token token : tokens) {
            if (token.type() == VJavaLexer.TokenType.LINE) {
                String text = token.text().trim();
                if (token.indentColumns() == 0 && text.startsWith("label ") && text.endsWith(":")) {
                    currentLabel = text.substring("label ".length(), text.length() - 1).trim();
                    orderCounter = 0;
                }
            } else if (token.type() == VJavaLexer.TokenType.JAVA_BLOCK && currentLabel != null) {
                byLabel.computeIfAbsent(currentLabel, ignored -> new ArrayList<>())
                        .add(new PreservedJavaBlock(orderCounter++, token.code()));
            }
        }
        return byLabel;
    }

    private static void emitPreservedJavaBlock(StringBuilder out, String rawCode) {
        out.append("    @->{\n");
        String body = rawCode.replace("\r\n", "\n");
        String[] lines = body.split("\n", -1);
        int start = (lines.length > 0 && lines[0].isBlank()) ? 1 : 0;
        int end = (lines.length > start && lines[lines.length - 1].isBlank()) ? lines.length - 1 : lines.length;
        for (int i = start; i < end; i++) {
            out.append("        ").append(lines[i].stripLeading()).append('\n');
        }
        out.append("    }<<\n");
    }

    private static String resolveChoiceTarget(VJavaAst.MenuChoice choice) {
        if (choice.inlineJumpTarget() != null && !choice.inlineJumpTarget().isBlank()) {
            return choice.inlineJumpTarget().trim();
        }
        for (VJavaAst.Statement s : choice.body()) {
            if (s instanceof VJavaAst.JumpStatement js) {
                return js.targetLabel();
            }
        }
        return "";
    }

    private static String escapeVJavaString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
