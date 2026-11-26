package de.swfdtf.e2e.support;

import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineMode;
import de.swfdtf.tools.StandaloneBuilder;
import de.swfdtf.tools.VJavaLinter;
import de.swfdtf.tools.VpakArchiver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Opaque-box process launcher and public entry-point facade for KAGAYAKI E2E tests.
 */
public final class E2eCliRunner {

    private static final int IO_CHUNK_SIZE = 4096;

    /**
     * Captured result of an executed subprocess.
     *
     * @param exitCode process exit code ({@code -1} if timed out and forcibly destroyed)
     * @param stdout   captured UTF-8 standard output
     * @param stderr   captured UTF-8 standard error
     * @param elapsed  measured wall-clock duration
     * @param timedOut {@code true} when the process exceeded its timeout and was terminated
     */
    public record ProcessResult(
            int exitCode,
            String stdout,
            String stderr,
            Duration elapsed,
            boolean timedOut
    ) {
        /**
         * Normalizes nullable text and duration fields.
         */
        public ProcessResult {
            stdout = stdout != null ? stdout : "";
            stderr = stderr != null ? stderr : "";
            elapsed = elapsed != null ? elapsed : Duration.ZERO;
        }

        /**
         * Returns {@code true} if the process completed within timeout with exit code {@code 0}.
         *
         * @return {@code true} when successful
         */
        public boolean isSuccess() {
            return !timedOut && exitCode == 0;
        }
    }

    private E2eCliRunner() {
    }

    /**
     * Launches a Java main class in an isolated subprocess inheriting the current classpath.
     *
     * @param mainClass  fully qualified main class name
     * @param args       CLI arguments passed to {@code mainClass}
     * @param workingDir working directory for the subprocess
     * @param timeout    maximum allowed runtime before forcible termination
     * @return captured {@link ProcessResult}
     * @throws IOException          if starting the subprocess fails
     * @throws InterruptedException if waiting for completion is interrupted
     */
    public static ProcessResult runJavaMain(
            String mainClass,
            List<String> args,
            Path workingDir,
            Duration timeout
    ) throws IOException, InterruptedException {
        return launchJavaMain(mainClass, args, workingDir, timeout, false);
    }

    /**
     * Launches a Java main class in an isolated subprocess, adding {@code -XstartOnFirstThread}
     * on macOS hosts as required for GLFW/LWJGL window creation.
     *
     * @param mainClass  fully qualified main class name
     * @param args       CLI arguments passed to {@code mainClass}
     * @param workingDir working directory for the subprocess
     * @param timeout    maximum allowed runtime before forcible termination
     * @return captured {@link ProcessResult}
     * @throws IOException          if starting the subprocess fails
     * @throws InterruptedException if waiting for completion is interrupted
     */
    public static ProcessResult runJavaMainWithMacFirstThread(
            String mainClass,
            List<String> args,
            Path workingDir,
            Duration timeout
    ) throws IOException, InterruptedException {
        return launchJavaMain(mainClass, args, workingDir, timeout, true);
    }

    private static ProcessResult launchJavaMain(
            String mainClass,
            List<String> args,
            Path workingDir,
            Duration timeout,
            boolean macFirstThread
    ) throws IOException, InterruptedException {
        Objects.requireNonNull(mainClass, "mainClass must not be null");
        Objects.requireNonNull(args, "args must not be null");
        Objects.requireNonNull(workingDir, "workingDir must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");

        List<String> command = new ArrayList<>();
        command.add(resolveJavaExecutable());
        if (macFirstThread && isMacOs()) {
            command.add("-XstartOnFirstThread");
        }
        String classpath = System.getProperty("java.class.path", "");
        if (!classpath.isBlank()) {
            command.add("-cp");
            command.add(classpath);
        }
        command.add(mainClass);
        command.addAll(args);

        return executeCommand(command, workingDir, timeout);
    }

    /**
     * Executes an executable standalone JAR via {@code java -jar}.
     *
     * @param jarFile    path to the runnable JAR file
     * @param args       CLI arguments passed to the JAR entry point
     * @param workingDir working directory for the subprocess
     * @param timeout    maximum allowed runtime before forcible termination
     * @return captured {@link ProcessResult}
     * @throws IOException          if starting the subprocess fails
     * @throws InterruptedException if waiting for completion is interrupted
     */
    public static ProcessResult runJar(
            Path jarFile,
            List<String> args,
            Path workingDir,
            Duration timeout
    ) throws IOException, InterruptedException {
        Objects.requireNonNull(jarFile, "jarFile must not be null");
        Objects.requireNonNull(args, "args must not be null");
        Objects.requireNonNull(workingDir, "workingDir must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");

        List<String> command = new ArrayList<>();
        command.add(resolveJavaExecutable());
        command.add("-jar");
        command.add(jarFile.toAbsolutePath().normalize().toString());
        command.addAll(args);

        return executeCommand(command, workingDir, timeout);
    }

    /**
     * Invokes the Gradle wrapper in {@code workingDir} with {@code --no-daemon --console=plain}.
     *
     * @param workingDir    project directory containing {@code gradlew}
     * @param tasksAndFlags Gradle task names and extra flags
     * @param timeout       maximum allowed runtime before forcible termination
     * @return captured {@link ProcessResult}
     * @throws IOException          if the Gradle wrapper cannot be started
     * @throws InterruptedException if waiting for completion is interrupted
     */
    public static ProcessResult runGradle(
            Path workingDir,
            List<String> tasksAndFlags,
            Duration timeout
    ) throws IOException, InterruptedException {
        Objects.requireNonNull(workingDir, "workingDir must not be null");
        Objects.requireNonNull(tasksAndFlags, "tasksAndFlags must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");

        Path normalizedDir = workingDir.toAbsolutePath().normalize();
        String wrapperName = isWindowsOs() ? "gradlew.bat" : "gradlew";
        Path wrapper = normalizedDir.resolve(wrapperName);
        if (!FilesExistCheck.isFile(wrapper)) {
            wrapper = E2eWorkspaceSupport.repoRoot().resolve(wrapperName);
        }

        List<String> command = new ArrayList<>();
        command.add(wrapper.toAbsolutePath().normalize().toString());
        command.addAll(tasksAndFlags);
        if (!tasksAndFlags.contains("--no-daemon")) {
            command.add("--no-daemon");
        }
        if (!tasksAndFlags.contains("--console=plain")) {
            command.add("--console=plain");
        }
        return executeCommand(command, normalizedDir, timeout);
    }

    /**
     * Executes an arbitrary OS command in {@code workingDir} with a bounded timeout.
     *
     * @param workingDir working directory for the subprocess
     * @param command    non-empty command and argument list
     * @param timeout    maximum allowed runtime before forcible termination
     * @return captured {@link ProcessResult}
     * @throws IOException              if starting the subprocess fails
     * @throws InterruptedException     if waiting for completion is interrupted
     * @throws IllegalArgumentException if {@code command} is empty
     */
    public static ProcessResult runCommand(
            Path workingDir,
            List<String> command,
            Duration timeout
    ) throws IOException, InterruptedException {
        Objects.requireNonNull(workingDir, "workingDir must not be null");
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        return executeCommand(List.copyOf(command), workingDir, timeout);
    }

    /**
     * Instantiates an {@link EngineApplication} configured for deterministic headless execution.
     *
     * @param projectDir KAGAYAKI project root directory
     * @param mode       target {@link EngineMode}
     * @param smokeTest  whether smoke-test auto-termination is enabled
     * @return configured {@link EngineApplication} instance (caller manages {@link EngineApplication#close()})
     */
    public static EngineApplication startHeadlessEngine(Path projectDir, EngineMode mode, boolean smokeTest) {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        EngineConfig config = EngineConfig.builder()
                .engineMode(mode)
                .projectDir(projectDir.toAbsolutePath().normalize())
                .headless(true)
                .smokeTest(smokeTest)
                .build();
        return new EngineApplication(config);
    }

    /**
     * Runs {@link VJavaLinter#lint(Path)} on {@code projectDir}.
     *
     * @param projectDir project root directory
     * @return static lint and compile report
     */
    public static VJavaLinter.LintReport lintProject(Path projectDir) {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        return VJavaLinter.lint(projectDir);
    }

    /**
     * Packs {@code projectDir} into {@code outputVpak} using {@link VpakArchiver#pack(Path, Path)}.
     *
     * @param projectDir source project directory
     * @param outputVpak target {@code .vpak} archive path
     * @return immutable list of packed archive entries
     * @throws IOException if scanning or writing the archive fails
     */
    public static List<VpakArchiver.VpakEntryInfo> packVpak(
            Path projectDir,
            Path outputVpak
    ) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Objects.requireNonNull(outputVpak, "outputVpak must not be null");
        return VpakArchiver.pack(projectDir, outputVpak);
    }

    /**
     * Exports a standalone distribution bundle via {@link StandaloneBuilder#build(Path, Path, Path)}.
     *
     * @param projectDir  source project directory
     * @param outputDir   target standalone distribution directory
     * @param optionalJar optional prebuilt standalone JAR (nullable)
     * @return build artifact summary
     * @throws IOException if exporting fails
     */
    public static StandaloneBuilder.BuildResult exportStandalone(
            Path projectDir,
            Path outputDir,
            Path optionalJar
    ) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        return StandaloneBuilder.build(projectDir, outputDir, optionalJar);
    }

    private static ProcessResult executeCommand(
            List<String> command,
            Path workingDir,
            Duration timeout
    ) throws IOException, InterruptedException {
        long timeoutMillis = Math.max(1L, timeout.toMillis());
        long startNanos = System.nanoTime();

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toAbsolutePath().normalize().toFile());
        Process process = pb.start();

        StreamCollector stdoutCollector = new StreamCollector(process.getInputStream());
        StreamCollector stderrCollector = new StreamCollector(process.getErrorStream());
        Thread stdoutThread = new Thread(stdoutCollector, "e2e-stdout-reader");
        Thread stderrThread = new Thread(stderrCollector, "e2e-stderr-reader");
        stdoutThread.setDaemon(true);
        stderrThread.setDaemon(true);
        stdoutThread.start();
        stderrThread.start();

        boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        boolean timedOut = !finished;
        int exitCode;
        if (timedOut) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            exitCode = -1;
        } else {
            exitCode = process.exitValue();
        }

        stdoutThread.join(2000L);
        stderrThread.join(2000L);

        Duration elapsed = Duration.ofNanos(Math.max(0L, System.nanoTime() - startNanos));
        return new ProcessResult(
                exitCode,
                stdoutCollector.asUtf8(),
                stderrCollector.asUtf8(),
                elapsed,
                timedOut
        );
    }

    private static String resolveJavaExecutable() {
        String javaHome = System.getProperty("java.home", "");
        String exeName = isWindowsOs() ? "java.exe" : "java";
        if (!javaHome.isBlank()) {
            Path candidate = Path.of(javaHome, "bin", exeName);
            if (FilesExistCheck.isFile(candidate)) {
                return candidate.toString();
            }
        }
        return exeName;
    }

    private static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static boolean isWindowsOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static final class FilesExistCheck {
        private static boolean isFile(Path path) {
            return java.nio.file.Files.isRegularFile(path);
        }
    }

    private static final class StreamCollector implements Runnable {

        private final InputStream stream;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private StreamCollector(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[IO_CHUNK_SIZE];
            try (InputStream in = stream) {
                int read;
                while ((read = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
            } catch (IOException ignored) {
                // Stream closed on process termination.
            }
        }

        private String asUtf8() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
