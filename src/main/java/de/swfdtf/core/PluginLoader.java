package de.swfdtf.core;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Discovers, loads, and enables runtime {@link EnginePlugin} implementations from
 * {@code plugins/*.jar} via {@link ServiceLoader} without crashing engine startup when
 * individual plugin archives are malformed or fail to initialize.
 */
public final class PluginLoader {

    private static final String LOG_CATEGORY = "PluginLoader";

    private PluginLoader() {
    }

    /**
     * Scans {@code pluginsDir} for {@code *.jar} archives, loads {@link EnginePlugin}
     * services via {@link ServiceLoader}, and registers/enables them in {@code registry}.
     *
     * <p>Corrupt JARs, missing service classes, or failing plugin hooks are logged as warnings
     * and skipped safely so the engine keeps booting cleanly.</p>
     *
     * @param pluginsDir directory scanned for plugin JARs (may be {@code null} or absent)
     * @param registry   target {@link PluginRegistry} (must not be {@code null})
     * @param logger     logger for diagnostics and isolation warnings (must not be {@code null})
     * @return list of opened {@link URLClassLoader} handles that should be closed on shutdown
     */
    public static List<URLClassLoader> loadFromDirectory(
            Path pluginsDir,
            PluginRegistry registry,
            EngineLogger logger
    ) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(logger, "logger must not be null");

        if (pluginsDir == null || !Files.isDirectory(pluginsDir)) {
            return List.of();
        }

        List<Path> jarFiles = discoverPluginJars(pluginsDir, logger);
        if (jarFiles.isEmpty()) {
            return List.of();
        }

        List<URLClassLoader> classLoaders = new ArrayList<>();
        for (Path jarPath : jarFiles) {
            URLClassLoader loader = null;
            boolean loadedAtLeastOne = false;
            try {
                URL jarUrl = jarPath.toUri().toURL();
                loader = new URLClassLoader(new URL[]{jarUrl}, EnginePlugin.class.getClassLoader());
                ServiceLoader<EnginePlugin> serviceLoader = ServiceLoader.load(EnginePlugin.class, loader);
                var iterator = serviceLoader.iterator();
                while (true) {
                    boolean hasNext;
                    try {
                        hasNext = iterator.hasNext();
                    } catch (ServiceConfigurationError | LinkageError | RuntimeException ex) {
                        logger.warn(LOG_CATEGORY, "Skipped unreadable plugin entry in '"
                                + jarPath.getFileName() + "': " + ex.getMessage());
                        break;
                    }
                    if (!hasNext) {
                        break;
                    }
                    try {
                        EnginePlugin plugin = iterator.next();
                        registry.register(plugin);
                        registry.enable(plugin.id());
                        loadedAtLeastOne = true;
                        logger.info(LOG_CATEGORY, "Loaded plugin '" + plugin.id()
                                + "' v" + plugin.version() + " from " + jarPath.getFileName());
                    } catch (ServiceConfigurationError | LinkageError | RuntimeException ex) {
                        logger.warn(LOG_CATEGORY, "Failed to load/enable plugin from '"
                                + jarPath.getFileName() + "': " + ex.getMessage());
                    }
                }
                if (loadedAtLeastOne) {
                    classLoaders.add(loader);
                } else {
                    closeQuietly(loader, logger, jarPath);
                }
            } catch (IOException | RuntimeException ex) {
                logger.warn(LOG_CATEGORY, "Failed to open plugin JAR '" + jarPath.getFileName()
                        + "': " + ex.getMessage());
                closeQuietly(loader, logger, jarPath);
            }
        }
        return List.copyOf(classLoaders);
    }

    private static List<Path> discoverPluginJars(Path pluginsDir, EngineLogger logger) {
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDir)) {
            for (Path candidate : stream) {
                if (Files.isRegularFile(candidate)
                        && candidate.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    jars.add(candidate.toAbsolutePath().normalize());
                }
            }
        } catch (IOException ex) {
            logger.warn(LOG_CATEGORY, "Failed to scan plugins directory '" + pluginsDir + "': " + ex.getMessage());
            return List.of();
        }
        jars.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return jars;
    }

    private static void closeQuietly(URLClassLoader loader, EngineLogger logger, Path jarPath) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (IOException ex) {
            logger.warn(LOG_CATEGORY, "Failed to close classloader for '" + jarPath.getFileName()
                    + "': " + ex.getMessage());
        }
    }
}
