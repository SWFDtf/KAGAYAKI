package de.swfdtf.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-safe registry managing {@link EnginePlugin} registration and enable/disable lifecycle
 * callbacks against an {@link EngineAPI}.
 */
public final class PluginRegistry {

    private final EngineAPI api;
    private final Object lock = new Object();
    private final Map<String, PluginEntry> plugins = new LinkedHashMap<>();

    /**
     * Creates a {@link PluginRegistry} bound to the given {@link EngineAPI}.
     *
     * @param api engine facade passed to plugin lifecycle hooks (must not be null)
     */
    public PluginRegistry(EngineAPI api) {
        this.api = Objects.requireNonNull(api, "api must not be null");
    }

    /**
     * Registers a plugin in disabled state.
     *
     * @param plugin plugin instance to register (must not be null; {@link EnginePlugin#id()} must be non-blank)
     * @throws IllegalStateException    if a plugin with the same id is already registered
     * @throws IllegalArgumentException if the plugin id is blank
     */
    public void register(EnginePlugin plugin) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        String id = requireValidId(plugin.id());

        synchronized (lock) {
            if (plugins.containsKey(id)) {
                throw new IllegalStateException("Plugin already registered with id: " + id);
            }
            plugins.put(id, new PluginEntry(plugin));
        }
    }

    /**
     * Unregisters the plugin identified by {@code id}, disabling it first if currently enabled.
     *
     * @param id unique plugin identifier (must not be null or blank)
     * @return {@code true} if a plugin was removed, {@code false} if no plugin matched {@code id}
     */
    public boolean unregister(String id) {
        String validId = requireValidId(id);

        synchronized (lock) {
            PluginEntry entry = plugins.get(validId);
            if (entry == null) {
                return false;
            }
            if (entry.enabled) {
                try {
                    entry.plugin.onDisable(api);
                } finally {
                    entry.enabled = false;
                }
            }
            plugins.remove(validId);
            return true;
        }
    }

    /**
     * Enables the registered plugin with {@code id}, invoking {@link EnginePlugin#onEnable(EngineAPI)}
     * if it was previously disabled.
     *
     * @param id unique plugin identifier (must not be null or blank)
     * @throws IllegalArgumentException if no plugin is registered with {@code id}
     */
    public void enable(String id) {
        String validId = requireValidId(id);

        synchronized (lock) {
            PluginEntry entry = requireEntry(validId);
            if (entry.enabled) {
                return;
            }
            entry.plugin.onEnable(api);
            entry.enabled = true;
        }
    }

    /**
     * Disables the registered plugin with {@code id}, invoking {@link EnginePlugin#onDisable(EngineAPI)}
     * if it was previously enabled.
     *
     * @param id unique plugin identifier (must not be null or blank)
     * @throws IllegalArgumentException if no plugin is registered with {@code id}
     */
    public void disable(String id) {
        String validId = requireValidId(id);

        synchronized (lock) {
            PluginEntry entry = requireEntry(validId);
            if (!entry.enabled) {
                return;
            }
            try {
                entry.plugin.onDisable(api);
            } finally {
                entry.enabled = false;
            }
        }
    }

    /**
     * Returns whether a plugin with {@code id} is currently registered and enabled.
     *
     * @param id unique plugin identifier (must not be null or blank)
     * @return {@code true} if registered and enabled
     */
    public boolean isEnabled(String id) {
        String validId = requireValidId(id);

        synchronized (lock) {
            PluginEntry entry = plugins.get(validId);
            return entry != null && entry.enabled;
        }
    }

    /**
     * Looks up a registered plugin by its unique identifier.
     *
     * @param id unique plugin identifier (must not be null)
     * @return optional matching {@link EnginePlugin}
     */
    public Optional<EnginePlugin> findPlugin(String id) {
        Objects.requireNonNull(id, "id must not be null");
        if (id.isBlank()) {
            return Optional.empty();
        }

        synchronized (lock) {
            PluginEntry entry = plugins.get(id.trim());
            return entry != null ? Optional.of(entry.plugin) : Optional.empty();
        }
    }

    /**
     * Returns an unmodifiable snapshot of all registered plugins in registration order.
     *
     * @return unmodifiable list of all registered plugins
     */
    public List<EnginePlugin> listAll() {
        synchronized (lock) {
            if (plugins.isEmpty()) {
                return List.of();
            }
            List<EnginePlugin> result = new ArrayList<>(plugins.size());
            for (PluginEntry entry : plugins.values()) {
                result.add(entry.plugin);
            }
            return Collections.unmodifiableList(result);
        }
    }

    /**
     * Returns an unmodifiable snapshot of all currently enabled plugins in registration order.
     *
     * @return unmodifiable list of enabled plugins
     */
    public List<EnginePlugin> listEnabled() {
        synchronized (lock) {
            List<EnginePlugin> result = new ArrayList<>();
            for (PluginEntry entry : plugins.values()) {
                if (entry.enabled) {
                    result.add(entry.plugin);
                }
            }
            return result.isEmpty() ? List.of() : Collections.unmodifiableList(result);
        }
    }

    private PluginEntry requireEntry(String id) {
        PluginEntry entry = plugins.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown plugin id: " + id);
        }
        return entry;
    }

    private static String requireValidId(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String trimmed = id.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        return trimmed;
    }

    private static final class PluginEntry {
        private final EnginePlugin plugin;
        private boolean enabled;

        private PluginEntry(EnginePlugin plugin) {
            this.plugin = plugin;
            this.enabled = false;
        }
    }
}
