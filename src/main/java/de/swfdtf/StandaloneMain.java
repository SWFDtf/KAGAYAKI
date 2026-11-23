package de.swfdtf;

import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineMode;

/**
 * Dedicated entry point for exported standalone builds (Window B only, without Studio tools).
 */
public final class StandaloneMain {

    private StandaloneMain() {
    }

    /**
     * Standalone runtime entry point forcing {@link EngineMode#STANDALONE_GAME}.
     *
     * @param args optional CLI flags such as {@code --project <dir>}, {@code --mode 2D|3D}, {@code --smoke-test}
     */
    public static void main(String[] args) {
        EngineConfig parsed = EngineConfig.fromArgs(args)
                .toBuilder()
                .engineMode(EngineMode.STANDALONE_GAME)
                .build();

        try (EngineApplication app = new EngineApplication(parsed)) {
            app.run();
        }
    }
}
