package de.swfdtf;

import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineMode;
import de.swfdtf.tools.ProjectTemplateGenerator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

        String[] filtered = remainingArgs.toArray(String[]::new);
        EngineConfig parsed = EngineConfig.fromArgs(filtered);
        if (!containsExplicitModeFlag(filtered)) {
            parsed = parsed.toBuilder().engineMode(EngineMode.STUDIO_DUAL_WINDOW).build();
        }

        try (EngineApplication app = new EngineApplication(parsed)) {
            app.run();
        }
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
