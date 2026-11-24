package de.swfdtf;

import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineMode;
import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.tools.ProjectTemplateGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Primary entry point for KAGAYAKI.
 *
 * <p>Defaults to {@link EngineMode#STUDIO_DUAL_WINDOW} (Studio Window A + Live Preview Window B)
 * unless {@code --standalone} is explicitly passed on the command line. Also supports
 * {@code --create-template <dir>} to generate a complete playable starter project.</p>
 */
public final class Main {

    private Main() {
    }

    /**
     * Application entry point.
     *
     * @param args CLI flags such as {@code --studio}, {@code --standalone <dir>}, {@code --project <dir>},
     *             {@code --mode 2D|3D}, {@code --create-template <dir>}, {@code --smoke-test}, {@code --headless}
     */
    public static void main(String[] args) {
        List<String> remainingArgs = new ArrayList<>();
        Path templateTarget = null;
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--create-template".equals(arg)) {
                    if (i + 1 >= args.length || args[i + 1] == null || args[i + 1].isBlank()) {
                        throw new IllegalArgumentException("Missing directory argument after --create-template");
                    }
                    templateTarget = Path.of(args[++i]);
                } else if (arg != null && arg.startsWith("--create-template=")) {
                    templateTarget = Path.of(arg.substring("--create-template=".length()));
                } else {
                    remainingArgs.add(arg);
                }
            }
        }

        if (templateTarget != null) {
            try {
                Path generated = ProjectTemplateGenerator.generate(templateTarget);
                remainingArgs.add("--project");
                remainingArgs.add(generated.toString());
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to create starter template at " + templateTarget, ex);
            }
        }

        EngineConfig parsed = buildConfig(args);

        try (EngineApplication app = new EngineApplication(parsed)) {
            app.run();
        }
    }

    static EngineConfig buildConfig(String[] args) {
        List<String> remainingArgs = new ArrayList<>();
        Path templateTarget = null;
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--create-template".equals(arg)) {
                    if (i + 1 >= args.length || args[i + 1] == null || args[i + 1].isBlank()) {
                        throw new IllegalArgumentException("Missing directory argument after --create-template");
                    }
                    templateTarget = Path.of(args[++i]);
                } else if (arg != null && arg.startsWith("--create-template=")) {
                    templateTarget = Path.of(arg.substring("--create-template=".length()));
                } else {
                    remainingArgs.add(arg);
                }
            }
        }

        boolean projectAssigned = hasExplicitProjectArg(remainingArgs.toArray(String[]::new));
        if (templateTarget != null) {
            try {
                Path generated = ProjectTemplateGenerator.generate(templateTarget);
                remainingArgs.add("--project");
                remainingArgs.add(generated.toString());
                projectAssigned = true;
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to create starter template at " + templateTarget, ex);
            }
        }

        if (!projectAssigned) {
            resolveDefaultProjectDir(Path.of(".")).ifPresent(dir -> {
                remainingArgs.add("--project");
                remainingArgs.add(dir.toString());
            });
        }

        String[] filtered = remainingArgs.toArray(String[]::new);
        EngineConfig parsed = EngineConfig.fromArgs(filtered);
        if (!containsExplicitModeFlag(filtered)) {
            parsed = parsed.toBuilder().engineMode(EngineMode.STUDIO_DUAL_WINDOW).build();
        }
        return parsed;
    }

    /**
     * Resolves a default project directory when no explicit {@code --project} argument was supplied.
     *
     * @param workingDir current working directory to probe
     * @return an {@link Optional} containing {@code workingDir} or {@code demo/project} if it holds a project descriptor
     */
    static Optional<Path> resolveDefaultProjectDir(Path workingDir) {
        if (workingDir == null) {
            return Optional.empty();
        }
        Path rootDescriptor = workingDir.resolve(ProjectDescriptor.FILE_NAME);
        if (Files.isRegularFile(rootDescriptor)) {
            return Optional.of(workingDir.normalize());
        }
        Path demoProject = workingDir.resolve("demo").resolve("project");
        if (Files.isRegularFile(demoProject.resolve(ProjectDescriptor.FILE_NAME))) {
            return Optional.of(demoProject.normalize());
        }
        return Optional.empty();
    }

    static boolean hasExplicitProjectArg(String[] args) {
        if (args == null) {
            return false;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null || arg.isEmpty()) {
                continue;
            }
            if ("--project".equals(arg) || arg.startsWith("--project=")) {
                return true;
            }
            if ("--standalone".equals(arg) && i + 1 < args.length
                    && args[i + 1] != null && !args[i + 1].startsWith("-")) {
                return true;
            }
            if (!arg.startsWith("-") && (i == 0 || !isFlagWithValue(args[i - 1]))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFlagWithValue(String prev) {
        return "--project".equals(prev) || "--mode".equals(prev) || "--render-mode".equals(prev)
                || "--width".equals(prev) || "--height".equals(prev) || "--fps".equals(prev)
                || "--target-fps".equals(prev);
    }

    static boolean containsExplicitModeFlag(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if ("--studio".equals(arg) || "--standalone".equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
