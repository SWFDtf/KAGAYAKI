package de.swfdtf.parser;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registry and recursive expander for reusable {@code .vjava} text/snippet macros using
 * {@code {{macro_name}}} placeholders.
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

    private final int maxExpansionDepth;
    private final Map<String, String> macros = new LinkedHashMap<>();

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
