package de.swfdtf.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Thread-safe structured logger backed by a bounded in-memory ring buffer and
 * lock-free listener fan-out for Studio consoles and diagnostics.
 */
public final class EngineLogger {

    /**
     * Default ring-buffer capacity for retained log entries.
     */
    public static final int DEFAULT_CAPACITY = 2000;

    private static final String DEFAULT_CATEGORY = "Engine";
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    /**
     * Severity levels supported by {@link EngineLogger}.
     */
    public enum Level {
        /** Diagnostic debug message. */
        DEBUG,
        /** Informational message. */
        INFO,
        /** Non-fatal warning message. */
        WARN,
        /** Error message. */
        ERROR
    }

    /**
     * Immutable structured log record stored in the ring buffer and broadcast to listeners.
     *
     * @param timestamp UTC instant when the entry was emitted
     * @param level     severity level
     * @param category  subsystem or component tag
     * @param message   human-readable log text
     */
    public record LogEntry(Instant timestamp, Level level, String category, String message) {

        /**
         * Validates that all record components are non-null.
         */
        public LogEntry {
            Objects.requireNonNull(timestamp, "timestamp must not be null");
            Objects.requireNonNull(level, "level must not be null");
            Objects.requireNonNull(category, "category must not be null");
            Objects.requireNonNull(message, "message must not be null");
        }

        /**
         * Formats this log entry into a concise single-line representation.
         *
         * @return formatted log line
         */
        public String format() {
            return "[" + TIME_FORMATTER.format(timestamp) + "] [" + level.name() + "] [" + category + "] " + message;
        }
    }

    private final int capacity;
    private final ArrayDeque<LogEntry> buffer;
    private final ReentrantLock bufferLock = new ReentrantLock();
    private final CopyOnWriteArrayList<Consumer<LogEntry>> listeners = new CopyOnWriteArrayList<>();

    private volatile Level minimumLevel = Level.DEBUG;
    private volatile boolean consoleOutputEnabled = true;

    /**
     * Creates a logger with {@link #DEFAULT_CAPACITY} retained entries.
     */
    public EngineLogger() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a logger with a custom bounded ring-buffer capacity.
     *
     * @param capacity maximum number of retained entries (must be {@code > 0})
     */
    public EngineLogger(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got: " + capacity);
        }
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(Math.min(capacity, 256));
    }

    /**
     * Emits a structured log message if {@code level} meets the configured minimum level.
     *
     * @param level    severity level
     * @param category subsystem category (falls back to {@code "Engine"} if null/blank)
     * @param message  message text (treated as empty string if null)
     */
    public void log(Level level, String category, String message) {
        Objects.requireNonNull(level, "level must not be null");
        if (level.ordinal() < minimumLevel.ordinal()) {
            return;
        }

        String normalizedCategory = (category == null || category.isBlank()) ? DEFAULT_CATEGORY : category.trim();
        String normalizedMessage = message != null ? message : "";
        LogEntry entry = new LogEntry(Instant.now(), level, normalizedCategory, normalizedMessage);

        bufferLock.lock();
        try {
            if (buffer.size() >= capacity) {
                buffer.pollFirst();
            }
            buffer.addLast(entry);
        } finally {
            bufferLock.unlock();
        }

        if (consoleOutputEnabled) {
            String formatted = entry.format();
            if (level == Level.ERROR) {
                System.err.println(formatted);
            } else {
                System.out.println(formatted);
            }
        }

        for (Consumer<LogEntry> listener : listeners) {
            try {
                listener.accept(entry);
            } catch (RuntimeException ignored) {
                // Prevent faulty UI/tool listeners from crashing engine execution.
            }
        }
    }

    /**
     * Logs an {@link Level#INFO} entry under the default {@code "Engine"} category.
     *
     * @param message message text
     */
    public void info(String message) {
        log(Level.INFO, DEFAULT_CATEGORY, message);
    }

    /**
     * Logs an {@link Level#INFO} entry under the given category.
     *
     * @param category subsystem category
     * @param message  message text
     */
    public void info(String category, String message) {
        log(Level.INFO, category, message);
    }

    /**
     * Logs a {@link Level#WARN} entry under the default {@code "Engine"} category.
     *
     * @param message message text
     */
    public void warn(String message) {
        log(Level.WARN, DEFAULT_CATEGORY, message);
    }

    /**
     * Logs a {@link Level#WARN} entry under the given category.
     *
     * @param category subsystem category
     * @param message  message text
     */
    public void warn(String category, String message) {
        log(Level.WARN, category, message);
    }

    /**
     * Logs an {@link Level#ERROR} entry under the default {@code "Engine"} category.
     *
     * @param message message text
     */
    public void error(String message) {
        log(Level.ERROR, DEFAULT_CATEGORY, message);
    }

    /**
     * Logs an {@link Level#ERROR} entry under the given category.
     *
     * @param category subsystem category
     * @param message  message text
     */
    public void error(String category, String message) {
        log(Level.ERROR, category, message);
    }

    /**
     * Logs an {@link Level#ERROR} entry including the formatted stack trace of {@code throwable}.
     *
     * @param category  subsystem category
     * @param message   message text
     * @param throwable optional exception to format
     */
    public void error(String category, String message, Throwable throwable) {
        if (throwable == null) {
            error(category, message);
            return;
        }
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            if (message != null && !message.isEmpty()) {
                pw.println(message);
            }
            throwable.printStackTrace(pw);
        }
        log(Level.ERROR, category, sw.toString().stripTrailing());
    }

    /**
     * Logs a {@link Level#DEBUG} entry under the default {@code "Engine"} category.
     *
     * @param message message text
     */
    public void debug(String message) {
        log(Level.DEBUG, DEFAULT_CATEGORY, message);
    }

    /**
     * Logs a {@link Level#DEBUG} entry under the given category.
     *
     * @param category subsystem category
     * @param message  message text
     */
    public void debug(String category, String message) {
        log(Level.DEBUG, category, message);
    }

    /**
     * Registers a listener notified synchronously on each emitted {@link LogEntry}.
     *
     * @param listener consumer callback (must not be null)
     */
    public void addListener(Consumer<LogEntry> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.addIfAbsent(listener);
    }

    /**
     * Unregisters a previously registered log listener.
     *
     * @param listener consumer callback to remove
     */
    public void removeListener(Consumer<LogEntry> listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /**
     * Returns up to {@code max} most recent log entries in chronological order (oldest-to-newest).
     *
     * @param max maximum number of entries to return
     * @return immutable snapshot list of recent entries
     */
    public List<LogEntry> getRecentEntries(int max) {
        if (max <= 0) {
            return List.of();
        }
        bufferLock.lock();
        try {
            int count = Math.min(max, buffer.size());
            if (count == 0) {
                return List.of();
            }
            List<LogEntry> result = new ArrayList<>(count);
            int skip = buffer.size() - count;
            int index = 0;
            for (LogEntry entry : buffer) {
                if (index >= skip) {
                    result.add(entry);
                }
                index++;
            }
            return Collections.unmodifiableList(result);
        } finally {
            bufferLock.unlock();
        }
    }

    /**
     * Clears all buffered log entries.
     */
    public void clear() {
        bufferLock.lock();
        try {
            buffer.clear();
        } finally {
            bufferLock.unlock();
        }
    }

    /**
     * Returns the current number of buffered entries.
     *
     * @return current buffer size
     */
    public int size() {
        bufferLock.lock();
        try {
            return buffer.size();
        } finally {
            bufferLock.unlock();
        }
    }

    /**
     * Returns the maximum capacity of the ring buffer.
     *
     * @return buffer capacity
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Sets the minimum severity level required for log entries to be recorded.
     *
     * @param minimumLevel minimum severity level
     */
    public void setMinimumLevel(Level minimumLevel) {
        this.minimumLevel = Objects.requireNonNull(minimumLevel, "minimumLevel must not be null");
    }

    /**
     * Returns the current minimum severity filter.
     *
     * @return active minimum log level
     */
    public Level getMinimumLevel() {
        return minimumLevel;
    }

    /**
     * Enables or disables writing formatted log entries to {@code System.out} / {@code System.err}.
     *
     * @param consoleOutputEnabled {@code true} to write to console streams
     */
    public void setConsoleOutputEnabled(boolean consoleOutputEnabled) {
        this.consoleOutputEnabled = consoleOutputEnabled;
    }

    /**
     * Returns whether formatted console output is currently enabled.
     *
     * @return {@code true} if console output is enabled
     */
    public boolean isConsoleOutputEnabled() {
        return consoleOutputEnabled;
    }
}
