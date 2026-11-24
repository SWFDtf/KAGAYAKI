package de.swfdtf.audio;

import de.swfdtf.core.EngineLogger;
import de.swfdtf.graphics.TextTagParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Non-blocking Self-Voicing accessibility service (toggled via {@code V} key / preferences)
 * backed by a single virtual-thread speech queue.
 *
 * <p>Strips KAGAYAKI rich-text markup and flow cues via {@link TextTagParser#parse(String)} before
 * dispatching utterances to listeners and optional OS native speech synthesis ({@code say} on macOS,
 * {@code spd-say}/{@code espeak} on Linux, PowerShell {@code SpeechSynthesizer} on Windows).</p>
 */
public final class TextToSpeechService implements AutoCloseable {

    /** Maximum number of spoken utterances retained in {@link #getSpokenHistory()}. */
    public static final int DEFAULT_MAX_HISTORY = 500;

    private static final String LOG_CATEGORY = "TextToSpeech";

    private record QueuedUtterance(String text, long generation) {
    }

    private final EngineLogger logger;
    private final Object stateLock = new Object();
    private final Deque<QueuedUtterance> queue = new ArrayDeque<>();
    private final Deque<String> spokenHistory = new ArrayDeque<>();
    private final Thread workerThread;

    private volatile boolean enabled = false;
    private volatile boolean osCommandExecutionEnabled;
    private volatile Consumer<String> onSpeakListener;

    private long generation = 0L;
    private boolean busy = false;
    private boolean disposed = false;
    private Process activeProcess = null;

    /**
     * Creates a {@code TextToSpeechService} with OS speech command execution enabled
     * (though initially disabled until {@link #setEnabled(boolean)} is called).
     */
    public TextToSpeechService() {
        this(new EngineLogger(), true);
    }

    /**
     * Creates a {@code TextToSpeechService} with explicit logger and OS command execution setting.
     *
     * @param logger                    structured logger (must not be {@code null})
     * @param osCommandExecutionEnabled {@code false} for headless tests or dry-run inspection
     */
    public TextToSpeechService(EngineLogger logger, boolean osCommandExecutionEnabled) {
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
        this.osCommandExecutionEnabled = osCommandExecutionEnabled;
        this.workerThread = Thread.ofVirtual()
                .name("kagayaki-tts-worker")
                .start(this::workerLoop);
    }

    /**
     * Strips KAGAYAKI rich-text formatting tags, cues, inline image placeholders, and excess
     * whitespace from {@code rawText} for clean speech synthesis.
     *
     * @param rawText raw VN dialogue string (nullable)
     * @return clean plain text suitable for TTS
     */
    public static String stripRichTextTags(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return "";
        }
        String spoken = TextTagParser.parse(rawText).spokenText();
        return spoken.replaceAll("[ \\t\\r\\n]+", " ").trim();
    }

    /**
     * Builds the OS-native command line for speaking {@code utterance} on {@code osName}.
     *
     * @param osName    operating system name (e.g., {@code System.getProperty("os.name")})
     * @param utterance plain utterance string (must not be {@code null})
     * @return immutable argument list ready for {@link ProcessBuilder}
     */
    public static List<String> buildSpeechCommand(String osName, String utterance) {
        Objects.requireNonNull(utterance, "utterance must not be null");
        String os = osName != null ? osName.toLowerCase(Locale.ROOT) : "";
        if (os.contains("mac") || os.contains("darwin")) {
            return List.of("say", utterance);
        }
        if (os.contains("win")) {
            String escaped = utterance.replace("'", "''");
            String psScript = "Add-Type -AssemblyName System.Speech; "
                    + "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                    + "$s.Speak('" + escaped + "')";
            String encoded = Base64.getEncoder().encodeToString(psScript.getBytes(StandardCharsets.UTF_16LE));
            return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
        }
        return List.of("spd-say", "--wait", utterance);
    }

    /**
     * Enables or disables self-voicing speech output. Disabling immediately stops active/queued speech.
     *
     * @param enabled {@code true} to enable speech output
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            stop();
        }
    }

    /** @return {@code true} if self-voicing speech output is enabled */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Controls whether external OS TTS processes are launched when utterances are processed.
     *
     * @param osCommandExecutionEnabled {@code false} in headless tests to skip spawning OS processes
     */
    public void setOsCommandExecutionEnabled(boolean osCommandExecutionEnabled) {
        this.osCommandExecutionEnabled = osCommandExecutionEnabled;
    }

    /** @return {@code true} if OS speech commands are executed for spoken utterances */
    public boolean isOsCommandExecutionEnabled() {
        return osCommandExecutionEnabled;
    }

    /**
     * Enqueues a VN dialogue line for non-blocking speech after stripping inline tags such as
     * {@code {b}}, {@code {w=0.4}}, {@code {color=...}}, and {@code {image=...}}.
     *
     * <p>Does nothing when disabled, disposed, or when the stripped text is blank.</p>
     *
     * @param text raw or plain dialogue text (nullable)
     */
    public void speak(String text) {
        if (!enabled || disposed || text == null) {
            return;
        }
        String cleaned = stripRichTextTags(text);
        if (cleaned.isEmpty()) {
            return;
        }

        synchronized (stateLock) {
            if (!enabled || disposed) {
                return;
            }
            queue.addLast(new QueuedUtterance(cleaned, generation));
            stateLock.notifyAll();
        }
    }

    /**
     * Clears all pending utterances and terminates any currently running OS speech process.
     */
    public void stop() {
        Process processToKill;
        synchronized (stateLock) {
            generation++;
            queue.clear();
            processToKill = activeProcess;
            stateLock.notifyAll();
        }
        if (processToKill != null) {
            processToKill.destroyForcibly();
        }
    }

    /**
     * Returns an immutable snapshot of spoken utterances processed by this service in order.
     *
     * @return list of stripped spoken utterances
     */
    public List<String> getSpokenHistory() {
        synchronized (spokenHistory) {
            return new ArrayList<>(spokenHistory);
        }
    }

    /**
     * Clears the recorded spoken utterance history.
     */
    public void clearSpokenHistory() {
        synchronized (spokenHistory) {
            spokenHistory.clear();
        }
    }

    /**
     * Registers a listener notified whenever an utterance begins speaking (after tag stripping).
     *
     * @param listener callback receiving the cleaned spoken text (nullable to unregister)
     */
    public void setOnSpeakListener(Consumer<String> listener) {
        this.onSpeakListener = listener;
    }

    /**
     * Returns the number of utterances currently waiting in the speech queue.
     *
     * @return queued utterance count
     */
    public int getPendingCount() {
        synchronized (stateLock) {
            return queue.size();
        }
    }

    /**
     * Blocks until the speech queue is empty and no utterance is actively being processed,
     * or until {@code timeoutMillis} expires.
     *
     * @param timeoutMillis maximum wait time in milliseconds ({@code >= 0})
     * @return {@code true} if idle was reached before timeout, {@code false} otherwise
     */
    public boolean awaitIdle(long timeoutMillis) {
        if (timeoutMillis < 0L) {
            throw new IllegalArgumentException("timeoutMillis must be >= 0, got: " + timeoutMillis);
        }
        long deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L;
        synchronized (stateLock) {
            while (!disposed && (!queue.isEmpty() || busy)) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    return false;
                }
                long waitMs = remainingNanos / 1_000_000L;
                int waitNs = (int) (remainingNanos % 1_000_000L);
                try {
                    stateLock.wait(waitMs, waitNs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /** @return {@code true} if {@link #dispose()} has been called */
    public boolean isDisposed() {
        synchronized (stateLock) {
            return disposed;
        }
    }

    /**
     * Stops any active/queued speech and shuts down the background TTS worker thread cleanly.
     */
    public void dispose() {
        Process processToKill;
        synchronized (stateLock) {
            if (disposed) {
                return;
            }
            disposed = true;
            enabled = false;
            generation++;
            queue.clear();
            processToKill = activeProcess;
            stateLock.notifyAll();
        }
        if (processToKill != null) {
            processToKill.destroyForcibly();
        }
        workerThread.interrupt();
        try {
            workerThread.join(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Disposes the TTS service; alias for {@link #dispose()}.
     */
    @Override
    public void close() {
        dispose();
    }

    // ---- Worker Loop ----

    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            QueuedUtterance next;
            synchronized (stateLock) {
                while (queue.isEmpty() && !disposed) {
                    try {
                        stateLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (disposed) {
                    return;
                }
                next = queue.pollFirst();
                if (next == null || next.generation() != generation || !enabled) {
                    stateLock.notifyAll();
                    continue;
                }
                busy = true;
            }

            try {
                processUtterance(next);
            } finally {
                synchronized (stateLock) {
                    busy = false;
                    activeProcess = null;
                    stateLock.notifyAll();
                }
            }
        }
    }

    private void processUtterance(QueuedUtterance item) {
        synchronized (stateLock) {
            if (disposed || !enabled || item.generation() != generation) {
                return;
            }
        }

        recordSpoken(item.text());

        Consumer<String> listener = this.onSpeakListener;
        if (listener != null) {
            try {
                listener.accept(item.text());
            } catch (RuntimeException ignored) {
                // Keep worker resilient against external listener errors.
            }
        }

        if (osCommandExecutionEnabled) {
            executeOsSpeech(item.text(), item.generation());
        }
    }

    private void recordSpoken(String text) {
        synchronized (spokenHistory) {
            if (spokenHistory.size() >= DEFAULT_MAX_HISTORY) {
                spokenHistory.pollFirst();
            }
            spokenHistory.addLast(text);
        }
    }

    private void executeOsSpeech(String utterance, long expectedGeneration) {
        String osName = System.getProperty("os.name", "");
        List<String> primaryCmd = buildSpeechCommand(osName, utterance);
        if (!runSpeechProcess(primaryCmd, expectedGeneration)) {
            String osLower = osName.toLowerCase(Locale.ROOT);
            if (!osLower.contains("mac") && !osLower.contains("darwin") && !osLower.contains("win")) {
                runSpeechProcess(List.of("espeak", utterance), expectedGeneration);
            }
        }
    }

    private boolean runSpeechProcess(List<String> command, long expectedGeneration) {
        Process process;
        synchronized (stateLock) {
            if (disposed || !enabled || expectedGeneration != generation) {
                return true;
            }
        }

        try {
            process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            logger.debug(LOG_CATEGORY, "TTS command unavailable (" + command.get(0) + "): " + e.getMessage());
            return false;
        }

        synchronized (stateLock) {
            if (disposed || !enabled || expectedGeneration != generation) {
                process.destroyForcibly();
                return true;
            }
            activeProcess = process;
        }

        try {
            process.waitFor();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        return true;
    }
}
