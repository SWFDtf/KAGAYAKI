package de.swfdtf.tools;

import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.parser.VJavaAst;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds and exports directed control-flow graphs across labels, conditionals, menus, and UI
 * screen actions in a {@link VJavaAst.ProjectAst}.
 *
 * <p>Supports reachability analysis from the project's configured entry label (plus {@code Jump}
 * and {@code Call} actions declared inside {@code screen} definitions) and exports graphs in
 * Mermaid ({@code flowchart TD}) and Graphviz DOT formats.</p>
 */
public final class ScriptFlowGraphExporter {

    private static final String DEFAULT_ENTRY_LABEL = "start";
    private static final String ENTRY_NODE_ID = "__entry__";
    private static final String SCREEN_PREFIX = "screen:";
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SCREEN_LABEL_ACTION_PATTERN =
            Pattern.compile("\\b(Jump|Call)\\(\\s*\"([^\"]+)\"\\s*\\)");

    /**
     * Kind of directed control-flow transition in a {@link FlowGraph}.
     */
    public enum EdgeKind {
        /** Direct {@code jump} statement or screen {@code Jump(...)} action. */
        JUMP,
        /** Subroutine {@code call} statement or screen {@code Call(...)} action. */
        CALL,
        /** Choice branch inside a {@code menu} block. */
        MENU,
        /** Automatic timeout branch on a {@code menu} block. */
        MENU_TIMEOUT,
        /** Synthetic entry edge targeting the project's entry label. */
        ENTRY
    }

    /**
     * Immutable directed edge in a script control-flow graph.
     *
     * @param fromLabel      source label or synthetic origin (e.g. {@code "__entry__"} or {@code "screen:<name>"})
     * @param toLabel        target label name
     * @param kind           transition kind
     * @param conditionLabel optional branch/choice condition description (never {@code null})
     */
    public record FlowEdge(String fromLabel, String toLabel, EdgeKind kind, String conditionLabel) {

        /**
         * Validates edge endpoints and normalizes optional condition text.
         */
        public FlowEdge {
            Objects.requireNonNull(fromLabel, "fromLabel must not be null");
            Objects.requireNonNull(toLabel, "toLabel must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            if (fromLabel.isBlank()) {
                throw new IllegalArgumentException("fromLabel must not be blank");
            }
            if (toLabel.isBlank()) {
                throw new IllegalArgumentException("toLabel must not be blank");
            }
            conditionLabel = conditionLabel != null ? conditionLabel : "";
        }
    }

    /**
     * Immutable control-flow graph for a KAGAYAKI project.
     *
     * @param entryLabel        configured project entry label name
     * @param labels            unmodifiable set of all defined script labels
     * @param edges             unmodifiable list of directed flow edges in discovery order
     * @param unreachableLabels unmodifiable set of defined labels unreachable from {@code entryLabel} or screens
     */
    public record FlowGraph(
            String entryLabel,
            Set<String> labels,
            List<FlowEdge> edges,
            Set<String> unreachableLabels
    ) {

        /**
         * Enforces non-null fields and defensive unmodifiable collections.
         */
        public FlowGraph {
            Objects.requireNonNull(entryLabel, "entryLabel must not be null");
            if (entryLabel.isBlank()) {
                throw new IllegalArgumentException("entryLabel must not be blank");
            }
            labels = copyUnmodifiableOrderedSet(labels, "labels");
            edges = edges != null ? List.copyOf(edges) : List.of();
            unreachableLabels = copyUnmodifiableOrderedSet(unreachableLabels, "unreachableLabels");
        }
    }

    /**
     * Creates a new {@code ScriptFlowGraphExporter}.
     */
    public ScriptFlowGraphExporter() {
    }

    /**
     * Builds a {@link FlowGraph} from {@code project} by traversing all label statements
     * (including nested {@link VJavaAst.IfStatement} and {@link VJavaAst.MenuStatement} blocks)
     * as well as {@code Jump}/{@code Call} actions in {@link VJavaAst.ScreenDef} widgets.
     *
     * @param project unified project AST (must not be {@code null})
     * @return immutable {@link FlowGraph}
     */
    public FlowGraph buildGraph(VJavaAst.ProjectAst project) {
        Objects.requireNonNull(project, "project must not be null");

        String entryLabel = resolveEntryLabel(project.descriptor());
        Set<String> labels = new LinkedHashSet<>(project.labels().keySet());
        Set<FlowEdge> edges = new LinkedHashSet<>();

        if (labels.contains(entryLabel)) {
            edges.add(new FlowEdge(ENTRY_NODE_ID, entryLabel, EdgeKind.ENTRY, ""));
        }

        for (VJavaAst.ScreenDef screen : project.screens().values()) {
            collectScreenEdges(SCREEN_PREFIX + screen.name(), screen.items(), edges);
        }

        for (VJavaAst.LabelNode label : project.labels().values()) {
            collectStatementEdges(label.name(), label.statements(), "", edges);
        }

        Set<String> unreachableLabels = computeUnreachableLabels(entryLabel, labels, edges);
        return new FlowGraph(entryLabel, labels, List.copyOf(edges), unreachableLabels);
    }

    /**
     * Renders {@code graph} as a Mermaid {@code flowchart TD} diagram.
     *
     * @param graph control-flow graph to render (must not be {@code null})
     * @return Mermaid flowchart text
     */
    public String toMermaid(FlowGraph graph) {
        Objects.requireNonNull(graph, "graph must not be null");

        StringBuilder sb = new StringBuilder();
        sb.append("flowchart TD\n");

        if (graph.labels().contains(graph.entryLabel())) {
            sb.append("    ")
                    .append(mermaidNodeId(ENTRY_NODE_ID))
                    .append("([\"Entry\"])\n");
        }

        Set<String> declaredNodes = new LinkedHashSet<>();
        declaredNodes.add(ENTRY_NODE_ID);
        for (String label : graph.labels()) {
            String nodeId = mermaidNodeId(label);
            declaredNodes.add(label);
            sb.append("    ")
                    .append(nodeId)
                    .append("[\"")
                    .append(escapeMermaidLabel(label))
                    .append("\"]\n");
        }

        for (FlowEdge edge : graph.edges()) {
            if (declaredNodes.add(edge.fromLabel())) {
                sb.append("    ")
                        .append(mermaidNodeId(edge.fromLabel()))
                        .append("([\"")
                        .append(escapeMermaidLabel(edge.fromLabel()))
                        .append("\"])\n");
            }
            if (declaredNodes.add(edge.toLabel())) {
                sb.append("    ")
                        .append(mermaidNodeId(edge.toLabel()))
                        .append("[\"")
                        .append(escapeMermaidLabel(edge.toLabel()))
                        .append("\"]\n");
            }
        }

        for (FlowEdge edge : graph.edges()) {
            String arrow = edge.kind() == EdgeKind.CALL ? "-.->" : "-->";
            String edgeText = formatEdgeLabel(edge);
            sb.append("    ")
                    .append(mermaidNodeId(edge.fromLabel()))
                    .append(' ')
                    .append(arrow)
                    .append("|\"")
                    .append(escapeMermaidLabel(edgeText))
                    .append("\"| ")
                    .append(mermaidNodeId(edge.toLabel()))
                    .append('\n');
        }

        if (!graph.unreachableLabels().isEmpty()) {
            sb.append("    classDef unreachable stroke-dasharray: 5 5\n");
            List<String> unreachableIds = new ArrayList<>(graph.unreachableLabels().size());
            for (String label : graph.unreachableLabels()) {
                unreachableIds.add(mermaidNodeId(label));
            }
            sb.append("    class ")
                    .append(String.join(",", unreachableIds))
                    .append(" unreachable\n");
        }

        return sb.toString();
    }

    /**
     * Renders {@code graph} as a Graphviz {@code digraph} document.
     *
     * @param graph control-flow graph to render (must not be {@code null})
     * @return Graphviz DOT text
     */
    public String toDot(FlowGraph graph) {
        Objects.requireNonNull(graph, "graph must not be null");

        StringBuilder sb = new StringBuilder();
        sb.append("digraph ScriptFlow {\n");
        sb.append("    rankdir=TB;\n");

        if (graph.labels().contains(graph.entryLabel())) {
            sb.append("    \"")
                    .append(escapeDot(ENTRY_NODE_ID))
                    .append("\" [label=\"Entry\", shape=circle];\n");
        }

        for (String label : graph.labels()) {
            boolean isEntry = label.equals(graph.entryLabel());
            boolean isUnreachable = graph.unreachableLabels().contains(label);
            sb.append("    \"")
                    .append(escapeDot(label))
                    .append("\" [shape=")
                    .append(isEntry ? "doubleoctagon" : "box");
            if (isUnreachable) {
                sb.append(", style=dashed");
            }
            sb.append("];\n");
        }

        for (FlowEdge edge : graph.edges()) {
            String edgeLabel = formatEdgeLabel(edge);
            sb.append("    \"")
                    .append(escapeDot(edge.fromLabel()))
                    .append("\" -> \"")
                    .append(escapeDot(edge.toLabel()))
                    .append("\" [label=\"")
                    .append(escapeDot(edgeLabel))
                    .append("\"");
            if (edge.kind() == EdgeKind.CALL) {
                sb.append(", style=dashed");
            }
            sb.append("];\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Convenience method that builds a {@link FlowGraph} from {@code project} and exports it as Mermaid text.
     *
     * @param project unified project AST (must not be {@code null})
     * @return Mermaid {@code flowchart TD} text
     */
    public String exportMermaid(VJavaAst.ProjectAst project) {
        Objects.requireNonNull(project, "project must not be null");
        return toMermaid(buildGraph(project));
    }

    private static void collectScreenEdges(
            String screenOrigin,
            List<VJavaAst.ScreenItem> items,
            Set<FlowEdge> edges
    ) {
        for (VJavaAst.ScreenItem item : items) {
            if (item.primaryArg() != null) {
                extractScreenActionEdges(screenOrigin, item.primaryArg(), edges);
            }
            for (String propVal : item.properties().values()) {
                extractScreenActionEdges(screenOrigin, propVal, edges);
            }
            if (!item.children().isEmpty()) {
                collectScreenEdges(screenOrigin, item.children(), edges);
            }
        }
    }

    private static void extractScreenActionEdges(String screenOrigin, String rawValue, Set<FlowEdge> edges) {
        if (rawValue == null || rawValue.isBlank()) {
            return;
        }
        Matcher matcher = SCREEN_LABEL_ACTION_PATTERN.matcher(rawValue);
        while (matcher.find()) {
            String action = matcher.group(1);
            String target = matcher.group(2).trim();
            if (!target.isEmpty()) {
                EdgeKind kind = "Call".equals(action) ? EdgeKind.CALL : EdgeKind.JUMP;
                edges.add(new FlowEdge(screenOrigin, target, kind, screenOrigin));
            }
        }
    }

    private static void collectStatementEdges(
            String fromLabel,
            List<VJavaAst.Statement> statements,
            String activeCondition,
            Set<FlowEdge> edges
    ) {
        for (VJavaAst.Statement stmt : statements) {
            if (stmt instanceof VJavaAst.JumpStatement jump) {
                if (!jump.isExpression() && jump.targetLabel() != null && !jump.targetLabel().isBlank()) {
                    edges.add(new FlowEdge(fromLabel, jump.targetLabel().trim(), EdgeKind.JUMP, activeCondition));
                }
            } else if (stmt instanceof VJavaAst.CallStatement call) {
                String target = call.targetLabel() != null ? call.targetLabel().trim() : "";
                if (!target.isEmpty() && IDENTIFIER_PATTERN.matcher(target).matches()) {
                    edges.add(new FlowEdge(fromLabel, target, EdgeKind.CALL, activeCondition));
                }
            } else if (stmt instanceof VJavaAst.MenuStatement menu) {
                if (menu.timeoutJumpLabel() != null && !menu.timeoutJumpLabel().isBlank()) {
                    String timeoutTarget = menu.timeoutJumpLabel().trim();
                    String timeoutDesc = menu.timeoutSeconds() != null
                            ? "timeout(" + menu.timeoutSeconds() + "s)"
                            : "timeout";
                    edges.add(new FlowEdge(
                            fromLabel,
                            timeoutTarget,
                            EdgeKind.MENU_TIMEOUT,
                            combineCondition(activeCondition, timeoutDesc)
                    ));
                }
                for (VJavaAst.MenuChoice choice : menu.choices()) {
                    String choiceLabel = formatChoiceCondition(choice);
                    String branchCond = combineCondition(activeCondition, choiceLabel);
                    if (choice.inlineJumpTarget() != null && !choice.inlineJumpTarget().isBlank()) {
                        edges.add(new FlowEdge(
                                fromLabel,
                                choice.inlineJumpTarget().trim(),
                                EdgeKind.MENU,
                                branchCond
                        ));
                    }
                    if (!choice.body().isEmpty()) {
                        collectStatementEdges(fromLabel, choice.body(), branchCond, edges);
                    }
                }
            } else if (stmt instanceof VJavaAst.IfStatement ifStmt) {
                for (VJavaAst.IfBranch branch : ifStmt.branches()) {
                    String ifCond = (branch.conditionExpr() != null && !branch.conditionExpr().isBlank())
                            ? branch.conditionExpr().trim()
                            : "else";
                    String combined = combineCondition(activeCondition, ifCond);
                    if (!branch.body().isEmpty()) {
                        collectStatementEdges(fromLabel, branch.body(), combined, edges);
                    }
                }
            }
        }
    }

    private static Set<String> computeUnreachableLabels(
            String entryLabel,
            Set<String> labels,
            Set<FlowEdge> edges
    ) {
        if (labels.isEmpty()) {
            return Set.of();
        }

        Set<String> reachable = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>();

        if (labels.contains(entryLabel)) {
            reachable.add(entryLabel);
            queue.add(entryLabel);
        }

        for (FlowEdge edge : edges) {
            if (edge.fromLabel().startsWith(SCREEN_PREFIX)
                    && labels.contains(edge.toLabel())
                    && reachable.add(edge.toLabel())) {
                queue.add(edge.toLabel());
            }
        }

        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (FlowEdge edge : edges) {
                if (edge.fromLabel().equals(current)
                        && labels.contains(edge.toLabel())
                        && reachable.add(edge.toLabel())) {
                    queue.add(edge.toLabel());
                }
            }
        }

        Set<String> unreachable = new LinkedHashSet<>();
        for (String label : labels) {
            if (!reachable.contains(label)) {
                unreachable.add(label);
            }
        }
        return unreachable;
    }

    private static String resolveEntryLabel(ProjectDescriptor descriptor) {
        if (descriptor == null) {
            return DEFAULT_ENTRY_LABEL;
        }
        String entry = descriptor.getEntryLabel();
        if (entry == null || entry.isBlank()) {
            return DEFAULT_ENTRY_LABEL;
        }
        return entry.trim();
    }

    private static String formatChoiceCondition(VJavaAst.MenuChoice choice) {
        String text = choice.text() != null ? choice.text().trim() : "";
        String cond = choice.conditionExpr() != null ? choice.conditionExpr().trim() : "";
        if (!text.isEmpty() && !cond.isEmpty()) {
            return text + " [" + cond + "]";
        }
        return !text.isEmpty() ? text : cond;
    }

    private static String combineCondition(String parent, String child) {
        if (parent == null || parent.isBlank()) {
            return child != null ? child : "";
        }
        if (child == null || child.isBlank()) {
            return parent;
        }
        return parent + " && " + child;
    }

    private static String formatEdgeLabel(FlowEdge edge) {
        String prefix = switch (edge.kind()) {
            case ENTRY -> "entry";
            case JUMP -> "jump";
            case CALL -> "call";
            case MENU -> "menu";
            case MENU_TIMEOUT -> "timeout";
        };
        if (edge.conditionLabel().isBlank()) {
            return prefix;
        }
        return prefix + ": " + edge.conditionLabel();
    }

    private static String mermaidNodeId(String raw) {
        if (ENTRY_NODE_ID.equals(raw)) {
            return "__entry__";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        if (sb.isEmpty() || Character.isDigit(sb.charAt(0))) {
            sb.insert(0, 'n');
        }
        return sb.toString();
    }

    private static String escapeMermaidLabel(String raw) {
        return raw.replace("\"", "'")
                .replace("|", "/")
                .replace('\r', ' ')
                .replace('\n', ' ');
    }

    private static String escapeDot(String raw) {
        return raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    private static Set<String> copyUnmodifiableOrderedSet(Set<String> source, String fieldName) {
        if (source == null || source.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> copy = new LinkedHashSet<>(source.size());
        for (String item : source) {
            copy.add(Objects.requireNonNull(item, fieldName + " must not contain null entries"));
        }
        return Collections.unmodifiableSet(copy);
    }
}
