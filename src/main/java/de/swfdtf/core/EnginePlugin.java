package de.swfdtf.core;

/**
 * Extension plugin contract managed by {@link PluginRegistry} against an {@link EngineAPI} instance.
 */
public interface EnginePlugin {

    /**
     * Returns the unique, non-blank identifier of this plugin.
     *
     * @return unique plugin identifier
     */
    String id();

    /**
     * Returns the human-readable display name of this plugin.
     *
     * @return plugin display name
     */
    String displayName();

    /**
     * Returns the semantic version string of this plugin.
     *
     * @return plugin version string
     */
    String version();

    /**
     * Invoked when the plugin transitions from disabled to enabled.
     *
     * @param api active {@link EngineAPI} facade
     */
    default void onEnable(EngineAPI api) {
    }

    /**
     * Invoked when the plugin transitions from enabled to disabled or is unregistered while enabled.
     *
     * @param api active {@link EngineAPI} facade
     */
    default void onDisable(EngineAPI api) {
    }
}
