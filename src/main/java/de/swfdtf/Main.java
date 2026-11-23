package de.swfdtf;

import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineMode;

/**
 * Primary entry point for KAGAYAKI.
 *
 * <p>Defaults to {@link EngineMode#STUDIO_DUAL_WINDOW} (Studio Window A + Live Preview Window B)
 * unless {@code --standalone} is explicitly passed on the command line.</p>
 */
public final class Main {

    private Main() {
    }

    /**
     * Application entry point.
     *
     * @param args CLI flags such as {@code --studio}, {@code --standalone <dir>}, {@code --project <dir>},
     *             {@code --mode 2D|3D}, {@code --smoke-test}, {@code --headless}
     */
    public static void main(String[] args) {
        EngineConfig parsed = EngineConfig.fromArgs(args);
        if (!containsExplicitModeFlag(args)) {
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
