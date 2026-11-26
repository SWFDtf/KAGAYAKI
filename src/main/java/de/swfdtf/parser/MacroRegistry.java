package de.swfdtf.parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registry and recursive expander for reusable {@code .vjava} text/snippet macros using
 * {@code {{macro_name}}} placeholders and parameterized {@code macro name(a, b):} blocks.
 *
 * <p>Supports nested macro expansion with active-stack cycle detection and configurable
 * maximum recursion depth, throwing {@link IllegalStateException} when cyclic references or
 * excessive nesting depths are encountered.</p>
 */
public final class MacroRegistry {

    /**
     * Default maximum nested macro expansion depth.
     */
    public static final int DEFAULT_MAX_EXPANSION_DEPTH = 16;

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([^{}]+?)\\}\\}");
    private static final Pattern PARAM_MACRO_HEADER_PATTERN =
            Pattern.compile("^macro\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)\\s*:\\s*$");
    private static final Pattern INVOCATION_PATTERN =
            Pattern.compile("^(?:\\{\\{)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)(?:\\}\\})?$");

    private record ParameterizedMacro(String name, List<String> params, String body) {
    }

    private final int maxExpansionDepth;
    private final Map<String, String> macros = new LinkedHashMap<>();
    private final Map<String, ParameterizedMacro> parameterizedMacros = new LinkedHashMap<>();

    /**
     * Creates a macro registry with {@value #DEFAULT_MAX_EXPANSION_DEPTH} maximum expansion depth.
     */
    public MacroRegistry() {
        this(DEFAULT_MAX_EXPANSION_DEPTH);
    }

    /**
     * Creates a macro registry with a custom maximum nested expansion depth.
     *
     * @param maxExpansionDepth maximum allowed recursive macro depth (must be {@code >= 1})
     */
    public MacroRegistry(int maxExpansionDepth) {
        if (maxExpansionDepth < 1) {
            throw new IllegalArgumentException("maxExpansionDepth must be >= 1");
        }
        this.maxExpansionDepth = maxExpansionDepth;
    }

    /**
     * Registers or overwrites a named macro expansion snippet.
     *
     * @param name      macro identifier (must not be {@code null} or blank)
     * @param expansion replacement text (must not be {@code null})
     */
    public synchronized void register(String name, String expansion) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(expansion, "expansion must not be null");
        String normalized = name.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        macros.put(normalized, expansion);
    }

    /**
     * Removes a registered macro by name.
     *
     * @param name macro identifier
     * @return {@code true} if the macro existed and was removed
     */
    public synchronized boolean unregister(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return macros.remove(name.trim()) != null;
    }

    /**
     * Looks up a registered macro expansion by name.
     *
     * @param name macro identifier
     * @return optional expansion string
     */
    public synchronized Optional<String> find(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return Optional.ofNullable(macros.get(name.trim()));
    }

    /**
     * Returns an unmodifiable insertion-ordered snapshot of all registered macros.
     *
     * @return unmodifiable macro map
     */
    public synchronized Map<String, String> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(macros));
    }

    /**
     * Expands all registered {@code {{macro_name}}} placeholders in {@code input}, resolving
     * nested macros up to {@link #getMaxExpansionDepth()}.
     *
     * <p>Unknown placeholders are preserved verbatim.</p>
     *
     * @param input source text or {@code .vjava} snippet
     * @return expanded text
     * @throws IllegalStateException if a cyclic macro reference or depth overflow occurs
     */
    public String expand(String input) {
        Objects.requireNonNull(input, "input must not be null");
        Map<String, String> macroSnapshot = snapshot();
        if (macroSnapshot.isEmpty() || input.indexOf("{{") < 0) {
            return input;
        }
        return expandRecursive(input, 0, new ArrayDeque<>(), macroSnapshot);
    }

    /**
     * Removes all registered macros.
     */
    public synchronized void clear() {
        macros.clear();
        parameterizedMacros.clear();
    }

    /**
     * Registers a parameterized block macro expandable via {@code name(arg1, arg2)}.
     *
     * @param name   macro identifier
     * @param params parameter names
     * @param body   macro expansion body template
     */
    public synchronized void registerParameterized(String name, List<String> params, String body) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(params, "params must not be null");
        Objects.requireNonNull(body, "body must not be null");
        String norm = name.trim();
        if (norm.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        parameterizedMacros.put(norm, new ParameterizedMacro(norm, List.copyOf(params), body));
    }

    /**
     * Expands a parameterized macro call with positional arguments.
     *
     * @param name macro name
     * @param args argument list matching parameter count
     * @return expanded body string
     */
    public synchronized String expandParameterized(String name, List<String> args) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(args, "args must not be null");
        ParameterizedMacro macro = parameterizedMacros.get(name.trim());
        if (macro == null) {
            throw new IllegalArgumentException("Unknown parameterized macro: " + name);
        }
        if (macro.params().size() != args.size()) {
            throw new IllegalArgumentException("Macro '" + name + "' expects "
                    + macro.params().size() + " arguments, got " + args.size());
        }
        String result = macro.body();
        for (int i = 0; i < macro.params().size(); i++) {
            String param = macro.params().get(i);
            String val = args.get(i);
            result = result.replace("{{" + param + "}}", val);
            result = replaceParamOutsideDollar(result, param, val);
        }
        return result;
    }

    /**
     * Extracts {@code macro name(a, b):} declarations from raw {@code .vjava} source and expands
     * all inline call sites at parse/preprocess time while preserving non-macro lines untouched.
     *
     * @param source raw {@code .vjava} script source
     * @return preprocessed script source ready for {@link VJavaLexer}
     */
    public static String preprocessSource(String source) {
        Objects.requireNonNull(source, "source must not be null");
        if (!source.contains("macro ")) {
            return source;
        }
        MacroRegistry local = new MacroRegistry();
        String[] rawLines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> keptLines = new ArrayList<>(rawLines.length);

        int i = 0;
        while (i < rawLines.length) {
            String line = rawLines[i];
            String trimmed = line.trim();
            if (line.equals(trimmed)) {
                Matcher header = PARAM_MACRO_HEADER_PATTERN.matcher(trimmed);
                if (header.matches()) {
                    String macroName = header.group(1);
                    List<String> params = parseCommaArgs(header.group(2));
                    List<String> bodyLines = new ArrayList<>();
                    i++;
                    int baseIndent = -1;
                    while (i < rawLines.length) {
                        String bLine = rawLines[i];
                        if (bLine.isBlank()) {
                            bodyLines.add("");
                            i++;
                            continue;
                        }
                        int indent = countLeadingSpaces(bLine);
                        if (indent == 0) {
                            break;
                        }
                        if (baseIndent < 0) {
                            baseIndent = indent;
                        }
                        int strip = Math.min(baseIndent, indent);
                        bodyLines.add(bLine.substring(strip));
                        i++;
                    }
                    local.registerParameterized(macroName, params, String.join("\n", bodyLines));
                    continue;
                }
            }
            keptLines.add(line);
            i++;
        }

        if (local.parameterizedMacros.isEmpty()) {
            return source;
        }

        StringBuilder out = new StringBuilder(source.length());
        for (int lineIdx = 0; lineIdx < keptLines.size(); lineIdx++) {
            String line = keptLines.get(lineIdx);
            int indent = countLeadingSpaces(line);
            String indentStr = line.substring(0, indent);
            String trimmed = line.substring(indent).trim();
            Matcher callMatcher = INVOCATION_PATTERN.matcher(trimmed);
            if (callMatcher.matches() && local.parameterizedMacros.containsKey(callMatcher.group(1))) {
                List<String> args = parseCommaArgs(callMatcher.group(2));
                String expanded = local.expandParameterized(callMatcher.group(1), args);
                String[] expLines = expanded.split("\n", -1);
                for (String expLine : expLines) {
                    if (!expLine.isBlank()) {
                        out.append(indentStr).append(expLine);
                    }
                    out.append('\n');
                }
            } else {
                out.append(line);
                if (lineIdx < keptLines.size() - 1) {
                    out.append('\n');
                }
            }
        }
        return out.toString();
    }

    private static int countLeadingSpaces(String s) {
        int c = 0;
        while (c < s.length() && s.charAt(c) == ' ') {
            c++;
        }
        return c;
    }

    private static List<String> parseCommaArgs(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inDouble = false;
        boolean inSingle = false;
        int parenDepth = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                cur.append(c).append(raw.charAt(++i));
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (!inDouble && !inSingle) {
                if (c == '(') {
                    parenDepth++;
                } else if (c == ')') {
                    parenDepth = Math.max(0, parenDepth - 1);
                } else if (c == ',' && parenDepth == 0) {
                    out.add(cur.toString().trim());
                    cur.setLength(0);
                    continue;
                }
            }
            cur.append(c);
        }
        out.add(cur.toString().trim());
        return out;
    }

    private static String replaceParamOutsideDollar(String body, String param, String replacement) {
        Pattern p = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(param) + "(?![A-Za-z0-9_])");
        return p.matcher(body).replaceAll(Matcher.quoteReplacement(replacement));
    }

    /**
     * Returns the configured maximum nested macro expansion depth.
     *
     * @return maximum recursion depth
     */
    public int getMaxExpansionDepth() {
        return maxExpansionDepth;
    }

    private String expandRecursive(
            String text,
            int depth,
            Deque<String> activeStack,
            Map<String, String> macroSnapshot
    ) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        if (!matcher.find()) {
            return text;
        }

        StringBuilder result = new StringBuilder(text.length());
        int lastAppendPos = 0;
        do {
            result.append(text, lastAppendPos, matcher.start());
            String macroName = matcher.group(1).trim();
            String replacement = macroSnapshot.get(macroName);
            if (replacement == null) {
                result.append(matcher.group(0));
            } else {
                if (activeStack.contains(macroName)) {
                    throw new IllegalStateException(
                            "Cyclic macro expansion detected: " + formatCycle(activeStack, macroName)
                    );
                }
                if (depth >= maxExpansionDepth) {
                    throw new IllegalStateException(
                            "Macro expansion exceeded maximum depth (" + maxExpansionDepth
                                    + ") while expanding '" + macroName + "'"
                    );
                }
                activeStack.addLast(macroName);
                try {
                    result.append(expandRecursive(replacement, depth + 1, activeStack, macroSnapshot));
                } finally {
                    activeStack.removeLast();
                }
            }
            lastAppendPos = matcher.end();
        } while (matcher.find());

        result.append(text, lastAppendPos, text.length());
        return result.toString();
    }

    private static String formatCycle(Deque<String> activeStack, String repeatedName) {
        StringBuilder sb = new StringBuilder();
        for (String frame : activeStack) {
            sb.append(frame).append(" -> ");
        }
        sb.append(repeatedName);
        return sb.toString();
    }
}
