package de.swfdtf.tools;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Searchable Studio command registry and execution history tracker for quick command palette
 * workflows (e.g., running lint checks, triggering live reload, or exporting builds).
 */
public final class StudioCommandPalette {

    /** Default maximum number of recent executed command IDs retained in history. */
    public static final int DEFAULT_MAX_RECENT_COMMANDS = 20;

    private static final Comparator<PaletteCommand> COMMAND_SORT_ORDER = Comparator
            .comparing(PaletteCommand::category, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(PaletteCommand::title, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(PaletteCommand::id, String.CASE_INSENSITIVE_ORDER);

    /**
     * Single executable command entry registered in {@link StudioCommandPalette}.
     *
     * @param id       unique non-blank command identifier
     * @param title    human-readable command title
     * @param category logical category label (defaults to {@code "General"} if null/blank)
     * @param shortcut optional keyboard shortcut display label (empty if null)
     * @param action   callback invoked when the command is executed
     */
    public record PaletteCommand(
            String id,
            String title,
            String category,
            String shortcut,
            Runnable action
    ) {
        public PaletteCommand {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(title, "title must not be null");
            Objects.requireNonNull(action, "action must not be null");
            if (id.isBlank()) {
                throw new IllegalArgumentException("id must not be blank");
            }
            if (title.isBlank()) {
                throw new IllegalArgumentException("title must not be blank");
            }
            id = id.trim();
            title = title.trim();
            category = (category == null || category.isBlank()) ? "General" : category.trim();
            shortcut = shortcut != null ? shortcut.trim() : "";
        }
    }

    private final Map<String, PaletteCommand> commands = new LinkedHashMap<>();
    private final Deque<String> recentCommandIds = new ArrayDeque<>();
    private final int maxRecentCommands;

    /**
     * Creates a {@code StudioCommandPalette} retaining up to {@link #DEFAULT_MAX_RECENT_COMMANDS}
     * recent command executions.
     */
    public StudioCommandPalette() {
        this(DEFAULT_MAX_RECENT_COMMANDS);
    }

    /**
     * Creates a {@code StudioCommandPalette} with a configurable recent history limit.
     *
     * @param maxRecentCommands maximum number of recent command IDs to retain ({@code >= 0})
     */
    public StudioCommandPalette(int maxRecentCommands) {
        if (maxRecentCommands < 0) {
            throw new IllegalArgumentException("maxRecentCommands must be >= 0, got " + maxRecentCommands);
        }
        this.maxRecentCommands = maxRecentCommands;
    }

    /**
     * Registers or replaces a {@link PaletteCommand} by its unique {@link PaletteCommand#id()}.
     *
     * @param command command to register (must not be {@code null})
     */
    public synchronized void register(PaletteCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        commands.put(command.id(), command);
    }

    /**
     * Unregisters the command identified by {@code id}.
     *
     * @param id command identifier (must not be {@code null})
     * @return {@code true} if a command was removed, {@code false} if not found or blank
     */
    public synchronized boolean unregister(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String trimmed = id.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        PaletteCommand removed = commands.remove(trimmed);
        if (removed != null) {
            recentCommandIds.remove(trimmed);
            return true;
        }
        return false;
    }

    /**
     * Looks up a registered command by its exact identifier.
     *
     * @param id command identifier (must not be {@code null})
     * @return matching {@link PaletteCommand}, or {@link Optional#empty()} if absent
     */
    public synchronized Optional<PaletteCommand> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String trimmed = id.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(commands.get(trimmed));
    }

    /**
     * Searches registered commands using a case-insensitive substring match across
     * {@code title}, {@code category}, and {@code id}, returning results sorted cleanly
     * by category, title, and id.
     *
     * @param query search query string (must not be {@code null}; blank returns all commands)
     * @return unmodifiable sorted list of matching {@link PaletteCommand} instances
     */
    public synchronized List<PaletteCommand> search(String query) {
        Objects.requireNonNull(query, "query must not be null");
        String normalizedQuery = query.trim().toLowerCase(Locale.ROOT);
        if (normalizedQuery.isEmpty()) {
            return commands.values().stream()
                    .sorted(COMMAND_SORT_ORDER)
                    .toList();
        }
        return commands.values().stream()
                .filter(cmd -> matchesQuery(cmd, normalizedQuery))
                .sorted(COMMAND_SORT_ORDER)
                .toList();
    }

    /**
     * Executes the command registered under {@code id} and records it at the front of the
     * bounded recent-command history.
     *
     * @param id command identifier (must not be {@code null})
     * @return {@code true} if the command existed and ran, {@code false} otherwise
     */
    public boolean executeById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        PaletteCommand command;
        synchronized (this) {
            String trimmed = id.trim();
            if (trimmed.isEmpty()) {
                return false;
            }
            command = commands.get(trimmed);
            if (command == null) {
                return false;
            }
        }
        command.action().run();
        synchronized (this) {
            if (maxRecentCommands > 0) {
                recentCommandIds.remove(command.id());
                recentCommandIds.addFirst(command.id());
                while (recentCommandIds.size() > maxRecentCommands) {
                    recentCommandIds.removeLast();
                }
            }
        }
        return true;
    }

    /**
     * Returns an unmodifiable snapshot of recently executed command IDs ordered from most
     * recent to least recent.
     *
     * @return unmodifiable list of recent command IDs
     */
    public synchronized List<String> getRecentCommandIds() {
        return List.copyOf(recentCommandIds);
    }

    /**
     * Returns an unmodifiable list of all currently registered commands sorted by category,
     * title, and id.
     *
     * @return unmodifiable list of all registered commands
     */
    public synchronized List<PaletteCommand> listAll() {
        return commands.values().stream()
                .sorted(COMMAND_SORT_ORDER)
                .toList();
    }

    private static boolean matchesQuery(PaletteCommand cmd, String lowerQuery) {
        return cmd.title().toLowerCase(Locale.ROOT).contains(lowerQuery)
                || cmd.category().toLowerCase(Locale.ROOT).contains(lowerQuery)
                || cmd.id().toLowerCase(Locale.ROOT).contains(lowerQuery);
    }
}
