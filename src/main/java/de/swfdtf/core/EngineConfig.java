package de.swfdtf.core;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable runtime configuration for the KAGAYAKI engine.
 *
 * @param engineMode     execution mode ({@link EngineMode#STUDIO_DUAL_WINDOW} or {@link EngineMode#STANDALONE_GAME})
 * @param renderModeName active render backend mode ({@code "2D"} or {@code "3D"})
 * @param projectDir     root directory of the active {@code .vjava} project
 * @param logicalWidth   logical canvas width (default {@code 1920})
 * @param logicalHeight  logical canvas height (default {@code 1080})
 * @param vsync          whether vertical synchronization is enabled (default {@code true})
 * @param targetFps      render frame cap when VSync is disabled (default {@code 120})
 * @param smokeTest      whether to run automated initialization/smoke verification and exit cleanly
 * @param headless       whether to skip creating native GLFW/OpenGL windows
 */
public record EngineConfig(
        EngineMode engineMode,
        String renderModeName,
        Path projectDir,
        int logicalWidth,
        int logicalHeight,
        boolean vsync,
        int targetFps,
        boolean smokeTest,
        boolean headless
) {

    /**
     * Default logical canvas width (16:9 HD).
     */
    public static final int DEFAULT_LOGICAL_WIDTH = 1920;

    /**
     * Default logical canvas height (16:9 HD).
     */
    public static final int DEFAULT_LOGICAL_HEIGHT = 1080;

    /**
     * Default render frame rate target.
     */
    public static final int DEFAULT_TARGET_FPS = 120;

    /**
     * Compact canonical constructor enforcing invariants and normalizing defaults.
     */
    public EngineConfig {
        Objects.requireNonNull(engineMode, "engineMode must not be null");
        renderModeName = normalizeRenderMode(renderModeName);
        projectDir = (projectDir != null ? projectDir : Path.of(".")).normalize();
        if (logicalWidth <= 0) {
            throw new IllegalArgumentException("logicalWidth must be positive, got: " + logicalWidth);
        }
        if (logicalHeight <= 0) {
            throw new IllegalArgumentException("logicalHeight must be positive, got: " + logicalHeight);
        }
        if (targetFps <= 0) {
            throw new IllegalArgumentException("targetFps must be positive, got: " + targetFps);
        }
    }

    /**
     * Returns an {@link EngineConfig} populated with standard defaults.
     *
     * @return default engine configuration
     */
    public static EngineConfig defaults() {
        return builder().build();
    }

    /**
     * Creates a new mutable {@link Builder} initialized with engine defaults.
     *
     * @return new config builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a {@link Builder} pre-populated with values from this configuration.
     *
     * @return builder initialized from this config
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Parses command-line arguments into an immutable {@link EngineConfig}.
     *
     * <p>Supported flags include:
     * {@code --studio}, {@code --standalone [projectDir]}, {@code --project <dir>},
     * {@code --mode <2D|3D>}, {@code --render-mode <2D|3D>}, {@code --2d}, {@code --3d},
     * {@code --width <px>}, {@code --height <px>}, {@code --vsync}, {@code --no-vsync},
     * {@code --fps <n>}, {@code --target-fps <n>}, {@code --smoke-test}, {@code --headless}.</p>
     *
     * @param args CLI argument array (may be {@code null} or empty)
     * @return parsed engine configuration
     */
    public static EngineConfig fromArgs(String[] args) {
        Builder builder = builder();
        if (args == null || args.length == 0) {
            return builder.build();
        }

        boolean projectExplicitlySet = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null || arg.isEmpty()) {
                continue;
            }

            switch (arg) {
                case "--studio" -> builder.engineMode(EngineMode.STUDIO_DUAL_WINDOW);
                case "--standalone" -> {
                    builder.engineMode(EngineMode.STANDALONE_GAME);
                    if (i + 1 < args.length && args[i + 1] != null && !args[i + 1].startsWith("-")) {
                        builder.projectDir(Path.of(args[++i]));
                        projectExplicitlySet = true;
                    }
                }
                case "--2d" -> builder.renderModeName("2D");
                case "--3d" -> builder.renderModeName("3D");
                case "--vsync" -> builder.vsync(true);
                case "--no-vsync" -> builder.vsync(false);
                case "--smoke-test" -> builder.smokeTest(true);
                case "--headless" -> builder.headless(true);
                case "--project" -> {
                    builder.projectDir(Path.of(requireNextValue(args, i++, "--project")));
                    projectExplicitlySet = true;
                }
                case "--mode", "--render-mode" ->
                    builder.renderModeName(requireNextValue(args, i++, arg));
                case "--width" ->
                    builder.logicalWidth(parsePositiveInt(requireNextValue(args, i++, "--width"), "--width"));
                case "--height" ->
                    builder.logicalHeight(parsePositiveInt(requireNextValue(args, i++, "--height"), "--height"));
                case "--fps", "--target-fps" ->
                    builder.targetFps(parsePositiveInt(requireNextValue(args, i++, arg), arg));
                default -> {
                    if (arg.startsWith("--project=")) {
                        builder.projectDir(Path.of(arg.substring("--project=".length())));
                        projectExplicitlySet = true;
                    } else if (arg.startsWith("--mode=")) {
                        builder.renderModeName(arg.substring("--mode=".length()));
                    } else if (arg.startsWith("--render-mode=")) {
                        builder.renderModeName(arg.substring("--render-mode=".length()));
                    } else if (arg.startsWith("--width=")) {
                        builder.logicalWidth(parsePositiveInt(arg.substring("--width=".length()), "--width"));
                    } else if (arg.startsWith("--height=")) {
                        builder.logicalHeight(parsePositiveInt(arg.substring("--height=".length()), "--height"));
                    } else if (arg.startsWith("--fps=")) {
                        builder.targetFps(parsePositiveInt(arg.substring("--fps=".length()), "--fps"));
                    } else if (arg.startsWith("--target-fps=")) {
                        builder.targetFps(parsePositiveInt(arg.substring("--target-fps=".length()), "--target-fps"));
                    } else if (arg.startsWith("--vsync=")) {
                        builder.vsync(Boolean.parseBoolean(arg.substring("--vsync=".length())));
                    } else if (!arg.startsWith("-") && !projectExplicitlySet) {
                        builder.projectDir(Path.of(arg));
                        projectExplicitlySet = true;
                    } else {
                        throw new IllegalArgumentException("Unknown CLI argument: " + arg);
                    }
                }
            }
        }
        return builder.build();
    }

    /**
     * Returns whether the engine is running in Dual-Window Studio mode.
     *
     * @return {@code true} for {@link EngineMode#STUDIO_DUAL_WINDOW}
     */
    public boolean isStudioMode() {
        return engineMode == EngineMode.STUDIO_DUAL_WINDOW;
    }

    /**
     * Returns whether the engine is running in Standalone Game mode.
     *
     * @return {@code true} for {@link EngineMode#STANDALONE_GAME}
     */
    public boolean isStandaloneMode() {
        return engineMode == EngineMode.STANDALONE_GAME;
    }

    /**
     * Returns whether the configured render mode is {@code "3D"}.
     *
     * @return {@code true} when {@code "3D"} is selected
     */
    public boolean is3DMode() {
        return "3D".equals(renderModeName);
    }

    private static String normalizeRenderMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "2D";
        }
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        if (!"2D".equals(normalized) && !"3D".equals(normalized)) {
            throw new IllegalArgumentException("renderModeName must be '2D' or '3D', got: " + mode);
        }
        return normalized;
    }

    private static String requireNextValue(String[] args, int index, String flag) {
        if (index + 1 >= args.length || args[index + 1] == null) {
            throw new IllegalArgumentException("Missing value for argument: " + flag);
        }
        return args[index + 1];
    }

    private static int parsePositiveInt(String raw, String optionName) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value <= 0) {
                throw new IllegalArgumentException(optionName + " must be positive, got: " + value);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for " + optionName + ": " + raw, e);
        }
    }

    /**
     * Fluent builder for creating immutable {@link EngineConfig} instances.
     */
    public static final class Builder {

        private EngineMode engineMode = EngineMode.STANDALONE_GAME;
        private String renderModeName = "2D";
        private Path projectDir = Path.of(".");
        private int logicalWidth = DEFAULT_LOGICAL_WIDTH;
        private int logicalHeight = DEFAULT_LOGICAL_HEIGHT;
        private boolean vsync = true;
        private int targetFps = DEFAULT_TARGET_FPS;
        private boolean smokeTest = false;
        private boolean headless = false;

        private Builder() {
        }

        private Builder(EngineConfig config) {
            this.engineMode = config.engineMode();
            this.renderModeName = config.renderModeName();
            this.projectDir = config.projectDir();
            this.logicalWidth = config.logicalWidth();
            this.logicalHeight = config.logicalHeight();
            this.vsync = config.vsync();
            this.targetFps = config.targetFps();
            this.smokeTest = config.smokeTest();
            this.headless = config.headless();
        }

        /**
         * Sets the execution mode.
         *
         * @param engineMode studio or standalone mode
         * @return this builder
         */
        public Builder engineMode(EngineMode engineMode) {
            this.engineMode = Objects.requireNonNull(engineMode, "engineMode must not be null");
            return this;
        }

        /**
         * Sets the render mode name ({@code "2D"} or {@code "3D"}).
         *
         * @param renderModeName render mode string
         * @return this builder
         */
        public Builder renderModeName(String renderModeName) {
            this.renderModeName = normalizeRenderMode(renderModeName);
            return this;
        }

        /**
         * Sets the project root directory.
         *
         * @param projectDir path to the project directory
         * @return this builder
         */
        public Builder projectDir(Path projectDir) {
            this.projectDir = Objects.requireNonNull(projectDir, "projectDir must not be null");
            return this;
        }

        /**
         * Sets the logical render width.
         *
         * @param logicalWidth width in logical pixels
         * @return this builder
         */
        public Builder logicalWidth(int logicalWidth) {
            this.logicalWidth = logicalWidth;
            return this;
        }

        /**
         * Sets the logical render height.
         *
         * @param logicalHeight height in logical pixels
         * @return this builder
         */
        public Builder logicalHeight(int logicalHeight) {
            this.logicalHeight = logicalHeight;
            return this;
        }

        /**
         * Sets whether vertical sync is enabled.
         *
         * @param vsync VSync toggle
         * @return this builder
         */
        public Builder vsync(boolean vsync) {
            this.vsync = vsync;
            return this;
        }

        /**
         * Sets the target frame rate limit.
         *
         * @param targetFps target FPS
         * @return this builder
         */
        public Builder targetFps(int targetFps) {
            this.targetFps = targetFps;
            return this;
        }

        /**
         * Sets whether automated smoke-test mode is enabled.
         *
         * @param smokeTest smoke test flag
         * @return this builder
         */
        public Builder smokeTest(boolean smokeTest) {
            this.smokeTest = smokeTest;
            return this;
        }

        /**
         * Sets whether headless execution (no GLFW window) is enabled.
         *
         * @param headless headless flag
         * @return this builder
         */
        public Builder headless(boolean headless) {
            this.headless = headless;
            return this;
        }

        /**
         * Builds an immutable {@link EngineConfig}.
         *
         * @return validated engine configuration
         */
        public EngineConfig build() {
            return new EngineConfig(
                    engineMode,
                    renderModeName,
                    projectDir,
                    logicalWidth,
                    logicalHeight,
                    vsync,
                    targetFps,
                    smokeTest,
                    headless
            );
        }
    }
}
