package de.swfdtf.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages Git CLI operations in a target repository root directory.
 *
 * <p>All Git commands are executed via {@link ProcessBuilder} with non-interactive terminal prompts
 * disabled and concurrent stream draining to prevent process deadlocks.</p>
 */
public final class GitController {

    /**
     * Allowed Conventional-style commit types enforced by KAGAYAKI's Git policy.
     */
    public static final Set<String> ALLOWED_COMMIT_TYPES = Set.of(
            "feat", "fix", "arch", "render", "parser", "audio", "ui", "docs", "test", "build"
    );

    /**
     * Default timeout applied to Git subprocess invocations.
     */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final Path repoRoot;
    private final Duration commandTimeout;

    /**
     * Result of executing a Git CLI command.
     *
     * @param exitCode process exit code ({@code -1} if the process failed to launch or timed out)
     * @param stdout   captured standard output text (UTF-8)
     * @param stderr   captured standard error text (UTF-8)
     * @param success  {@code true} when {@code exitCode == 0}
     */
    public record CommandResult(int exitCode, String stdout, String stderr, boolean success) {
    }

    /**
     * Creates a Git controller targeting the specified repository root using the default timeout.
     *
     * @param repoRoot target repository directory
     */
    public GitController(Path repoRoot) {
        this(repoRoot, DEFAULT_TIMEOUT);
    }

    /**
     * Creates a Git controller targeting the specified repository root and timeout.
     *
     * @param repoRoot       target repository directory
     * @param commandTimeout maximum time to wait for each Git CLI invocation
     */
    public GitController(Path repoRoot, Duration commandTimeout) {
        this.repoRoot = Objects.requireNonNull(repoRoot, "repoRoot must not be null")
                .toAbsolutePath()
                .normalize();
        this.commandTimeout = Objects.requireNonNull(commandTimeout, "commandTimeout must not be null");
        if (commandTimeout.isNegative() || commandTimeout.isZero()) {
            throw new IllegalArgumentException("commandTimeout must be positive");
        }
    }

    /**
     * Returns the normalized absolute repository root path.
     *
     * @return repository root path
     */
    public Path getRepoRoot() {
        return repoRoot;
    }

    /**
     * Checks whether {@code repoRoot} is the root of a valid Git repository work tree.
     *
     * @return {@code true} if {@code repoRoot/.git} exists and Git confirms it is inside a work tree
     */
    public boolean isGitRepository() {
        if (!Files.isDirectory(repoRoot) || !Files.exists(repoRoot.resolve(".git"))) {
            return false;
        }
        CommandResult result = executeGit(List.of("rev-parse", "--is-inside-work-tree"));
        return result.success() && "true".equals(result.stdout().trim());
    }

    /**
     * Initializes a Git repository at {@code repoRoot}, creating the directory if it does not yet exist.
     *
     * @return command execution result
     */
    public CommandResult init() {
        try {
            Files.createDirectories(repoRoot);
        } catch (IOException e) {
            return new CommandResult(-1, "", "Failed to create repository directory: " + e.getMessage(), false);
        }
        return executeGit(List.of("init"));
    }

    /**
     * Returns the working tree status via {@code git status}.
     *
     * @return command execution result
     */
    public CommandResult status() {
        return executeGit(List.of("status"));
    }

    /**
     * Stages the specified files using {@code git add -- <files...>}.
     *
     * @param files non-empty list of file paths (relative to {@code repoRoot} or absolute)
     * @return command execution result
     */
    public CommandResult add(List<Path> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("files must not be null or empty");
        }
        List<String> args = new ArrayList<>(files.size() + 2);
        args.add("add");
        args.add("--");
        for (Path file : files) {
            Objects.requireNonNull(file, "file entry must not be null");
            args.add(toGitPathArgument(file));
        }
        return executeGit(args);
    }

    /**
     * Formats a commit message according to the KAGAYAKI commit specification and creates a commit.
     *
     * <p>Allowed types: {@code feat}, {@code fix}, {@code arch}, {@code render}, {@code parser},
     * {@code audio}, {@code ui}, {@code docs}, {@code test}, {@code build}.</p>
     *
     * @param type         commit category type (must be one of {@link #ALLOWED_COMMIT_TYPES})
     * @param module       target module name
     * @param summary      concise one-line summary
     * @param bulletPoints optional detail bullet lines appended after a blank line
     * @return command execution result
     */
    public CommandResult commit(String type, String module, String summary, List<String> bulletPoints) {
        String message = formatCommitMessage(type, module, summary, bulletPoints);
        return executeGit(List.of("commit", "-m", message));
    }

    /**
     * Validates commit metadata and formats the exact commit message string:
     * {@code <type>(<module>): <summary>\n\n- <bullet1>...}.
     *
     * @param type         commit type
     * @param module       target module name
     * @param summary      one-line commit summary
     * @param bulletPoints optional bullet points
     * @return formatted commit message
     */
    public static String formatCommitMessage(String type, String module, String summary, List<String> bulletPoints) {
        if (type == null || !ALLOWED_COMMIT_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "Invalid commit type '" + type + "'. Allowed types: " + ALLOWED_COMMIT_TYPES
            );
        }
        String cleanModule = requireSingleLineText(module, "module");
        String cleanSummary = requireSingleLineText(summary, "summary");

        StringBuilder sb = new StringBuilder();
        sb.append(type).append('(').append(cleanModule).append("): ").append(cleanSummary);

        if (bulletPoints != null && !bulletPoints.isEmpty()) {
            List<String> normalizedBullets = new ArrayList<>(bulletPoints.size());
            for (String bullet : bulletPoints) {
                if (bullet == null) {
                    continue;
                }
                String trimmed = bullet.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("- ")) {
                    normalizedBullets.add(trimmed);
                } else {
                    normalizedBullets.add("- " + trimmed);
                }
            }
            if (!normalizedBullets.isEmpty()) {
                sb.append("\n\n");
                for (int i = 0; i < normalizedBullets.size(); i++) {
                    if (i > 0) {
                        sb.append('\n');
                    }
                    sb.append(normalizedBullets.get(i));
                }
            }
        }
        return sb.toString();
    }

    /**
     * Pushes commits to the given remote and branch via {@code git push <remote> <branch>}.
     *
     * @param remote remote name (e.g. {@code origin})
     * @param branch branch name (e.g. {@code main})
     * @return command execution result
     */
    public CommandResult push(String remote, String branch) {
        String cleanRemote = requireSingleLineText(remote, "remote");
        String cleanBranch = requireSingleLineText(branch, "branch");
        return executeGit(List.of("push", cleanRemote, cleanBranch));
    }

    /**
     * Retrieves recent commit history entries via {@code git log -n <limit>}.
     *
     * @param limit maximum number of commits to show (must be {@code > 0})
     * @return command execution result
     */
    public CommandResult log(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got: " + limit);
        }
        return executeGit(List.of("log", "-n", Integer.toString(limit)));
    }

    private String toGitPathArgument(Path path) {
        if (!path.isAbsolute()) {
            return path.toString();
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(repoRoot)) {
            Path relative = repoRoot.relativize(normalized);
            return relative.toString().isEmpty() ? "." : relative.toString();
        }
        return normalized.toString();
    }

    private static String requireSingleLineText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be null or blank");
        }
        String trimmed = value.trim();
        if (trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(fieldName + " must be a single line");
        }
        return trimmed;
    }

    private CommandResult executeGit(List<String> gitArgs) {
        if (!Files.isDirectory(repoRoot)) {
            return new CommandResult(-1, "", "Repository root directory does not exist: " + repoRoot, false);
        }

        List<String> command = new ArrayList<>(gitArgs.size() + 1);
        command.add("git");
        command.addAll(gitArgs);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(repoRoot.toFile());
        pb.redirectErrorStream(false);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new CommandResult(-1, "", "Failed to start git process: " + e.getMessage(), false);
        }

        CompletableFuture<String> stdoutFuture = readStreamAsync(process.getInputStream());
        CompletableFuture<String> stderrFuture = readStreamAsync(process.getErrorStream());

        try {
            boolean finished = process.waitFor(commandTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                String out = safeJoin(stdoutFuture);
                String err = safeJoin(stderrFuture);
                String timeoutMsg = "Git command timed out after " + commandTimeout.toMillis() + " ms";
                String combinedErr = err.isEmpty() ? timeoutMsg : err + System.lineSeparator() + timeoutMsg;
                return new CommandResult(-1, out, combinedErr, false);
            }

            int exitCode = process.exitValue();
            String stdout = stdoutFuture.join();
            String stderr = stderrFuture.join();
            return new CommandResult(exitCode, stdout, stderr, exitCode == 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new CommandResult(-1, safeJoin(stdoutFuture), "Interrupted while waiting for git: " + e.getMessage(), false);
        }
    }

    private static CompletableFuture<String> readStreamAsync(InputStream inputStream) {
        return CompletableFuture.supplyAsync(() -> {
            try (inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                return out.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }, Runnable::run);
    }

    private static String safeJoin(CompletableFuture<String> future) {
        try {
            return future.get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }
}
