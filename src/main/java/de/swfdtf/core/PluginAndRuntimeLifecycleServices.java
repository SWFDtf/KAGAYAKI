package de.swfdtf.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Chapter 10 plugin sandboxing/manifest validation, fault-isolated plugin lifecycle events,
 * custom DSL command delegation, forward-compatible plugin serializers, UI menu extension slots,
 * rotating log persistence, clean background-thread/child-process shutdown coordination, and
 * forward-compatible descriptor extension preservation
 * ({@code #0785}, {@code #0796}, {@code #0797}, {@code #0806}, {@code #0814}, {@code #0822},
 * {@code #0824}, {@code #0831}, {@code #0833}, {@code #0839}).
 */
public final class PluginAndRuntimeLifecycleServices {

    private PluginAndRuntimeLifecycleServices() {
    }

    /**
     * Declared capability permissions checked against {@code plugin.json} before loading ({@code #0785}).
     */
    public enum PluginPermission {
        /** Allows contributing custom UI buttons/screens. */
        UI,
        /** Allows registering or modifying audio cues/filters. */
        AUDIO,
        /** Allows registering state serializers or store hooks. */
        STATE,
        /** Allows sandboxed reads from mod/plugin bundle files. */
        FILESYSTEM
    }

    /**
     * Parsed {@code plugin.json} capability and compatibility manifest ({@code #0785}).
     *
     * @param id                   unique plugin identifier
     * @param displayName          human-readable display title
     * @param version              plugin semantic version
     * @param minEngineApiVersion  minimum required engine API version (e.g. {@code "1.0.0"})
     * @param permissions          declared {@link PluginPermission}s
     */
    public record PluginManifest(
            String id,
            String displayName,
            String version,
            String minEngineApiVersion,
            Set<PluginPermission> permissions
    ) {
        /** Defensively copies permissions set. */
        public PluginManifest {
            Objects.requireNonNull(id, "id must not be null");
            if (id.isBlank()) {
                throw new IllegalArgumentException("plugin id must not be blank");
            }
            displayName = displayName != null && !displayName.isBlank() ? displayName : id;
            version = version != null && !version.isBlank() ? version : "1.0.0";
            minEngineApiVersion = minEngineApiVersion != null && !minEngineApiVersion.isBlank()
                    ? minEngineApiVersion : "1.0.0";
            permissions = permissions != null ? Collections.unmodifiableSet(EnumSet.copyOf(permissions)) : Set.of();
        }
    }

    /**
     * Parses and validates {@code plugin.json} compatibility against running engine API version ({@code #0785}).
     */
    public static final class PluginManifestValidator {

        private static final Gson GSON = new GsonBuilder().create();

        private record RawManifest(
                String id,
                String displayName,
                String version,
                String minEngineApiVersion,
                List<String> permissions
        ) {
        }

        /**
         * Creates a {@code PluginManifestValidator}.
         */
        public PluginManifestValidator() {
        }

        /**
         * Parses JSON string into immutable {@link PluginManifest}.
         *
         * @param jsonText raw {@code plugin.json} UTF-8 content
         * @return validated {@link PluginManifest}
         */
        public static PluginManifest parseManifestJson(String jsonText) {
            Objects.requireNonNull(jsonText, "jsonText must not be null");
            RawManifest raw = GSON.fromJson(jsonText, RawManifest.class);
            if (raw == null || raw.id() == null || raw.id().isBlank()) {
                throw new IllegalArgumentException("plugin.json missing required 'id' field");
            }
            EnumSet<PluginPermission> perms = EnumSet.noneOf(PluginPermission.class);
            if (raw.permissions() != null) {
                for (String p : raw.permissions()) {
                    if (p != null && !p.isBlank()) {
                        perms.add(PluginPermission.valueOf(p.trim().toUpperCase()));
                    }
                }
            }
            return new PluginManifest(raw.id(), raw.displayName(), raw.version(), raw.minEngineApiVersion(), perms);
        }

        /**
         * Validates that {@code currentEngineVersion} meets {@code manifest.minEngineApiVersion()} ({@code #0785}).
         *
         * @param manifest             parsed plugin manifest
         * @param currentEngineVersion active engine version string
         * @throws IllegalStateException if plugin requires a newer engine API version
         */
        public static void assertCompatible(PluginManifest manifest, String currentEngineVersion) {
            Objects.requireNonNull(manifest, "manifest must not be null");
            String current = currentEngineVersion != null ? currentEngineVersion : EngineVersion.VERSION;
            if (compareSemver(current, manifest.minEngineApiVersion()) < 0) {
                throw new IllegalStateException("Plugin '" + manifest.id() + "' requires engine API >= "
                        + manifest.minEngineApiVersion() + " (running " + current + ")");
            }
        }

        private static int compareSemver(String a, String b) {
            int[] pa = parseParts(a);
            int[] pb = parseParts(b);
            for (int i = 0; i < 3; i++) {
                if (pa[i] != pb[i]) {
                    return Integer.compare(pa[i], pb[i]);
                }
            }
            return 0;
        }

        private static int[] parseParts(String version) {
            String clean = version.trim().split("[-+]")[0];
            String[] segs = clean.split("\\.");
            int[] nums = new int[3];
            for (int i = 0; i < Math.min(3, segs.length); i++) {
                try {
                    nums[i] = Integer.parseInt(segs[i]);
                } catch (NumberFormatException ignored) {
                    nums[i] = 0;
                }
            }
            return nums;
        }
    }

    /**
     * Structured plugin lifecycle listener interface (no {@code I}-prefix) ({@code #0796}).
     */
    public interface PluginLifecycleListener {
        /**
         * Returns owning plugin ID.
         *
         * @return plugin identifier
         */
        String pluginId();

        /**
         * Invoked when plugin finishes loading.
         *
         * @param api engine API facade
         */
        void onLoad(EngineAPI api);

        /**
         * Invoked when a new play session starts.
         *
         * @param api engine API facade
         */
        void onSessionStart(EngineAPI api);

        /**
         * Invoked when active script label/scene changes.
         *
         * @param api       engine API facade
         * @param sceneName target scene or label name
         */
        void onSceneChange(EngineAPI api, String sceneName);

        /**
         * Invoked when engine shuts down or plugin is unloaded.
         *
         * @param api engine API facade
         */
        void onDispose(EngineAPI api);
    }

    /**
     * Fault-isolated dispatcher invoking registered {@link PluginLifecycleListener}s safely ({@code #0796}).
     */
    public static final class PluginLifecycleDispatcher {

        private final List<PluginLifecycleListener> listeners = new ArrayList<>();
        private final Consumer<String> faultReporter;

        /**
         * Creates a {@code PluginLifecycleDispatcher} logging faults to {@code faultReporter}.
         *
         * @param faultReporter optional consumer receiving fault messages
         */
        public PluginLifecycleDispatcher(Consumer<String> faultReporter) {
            this.faultReporter = faultReporter;
        }

        /**
         * Registers a lifecycle listener.
         *
         * @param listener plugin listener
         */
        public synchronized void register(PluginLifecycleListener listener) {
            listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
        }

        /**
         * Dispatches {@code onLoad} safely across all registered listeners isolating exceptions.
         *
         * @param api engine API
         */
        public void fireLoad(EngineAPI api) {
            dispatch(l -> l.onLoad(api), "onLoad");
        }

        /**
         * Dispatches {@code onSessionStart} safely isolating plugin faults.
         *
         * @param api engine API
         */
        public void fireSessionStart(EngineAPI api) {
            dispatch(l -> l.onSessionStart(api), "onSessionStart");
        }

        /**
         * Dispatches {@code onSceneChange} safely isolating plugin faults.
         *
         * @param api       engine API
         * @param sceneName active label/scene name
         */
        public void fireSceneChange(EngineAPI api, String sceneName) {
            dispatch(l -> l.onSceneChange(api, sceneName), "onSceneChange");
        }

        /**
         * Dispatches {@code onDispose} safely isolating plugin faults.
         *
         * @param api engine API
         */
        public void fireDispose(EngineAPI api) {
            dispatch(l -> l.onDispose(api), "onDispose");
        }

        private void dispatch(Consumer<PluginLifecycleListener> action, String hookName) {
            List<PluginLifecycleListener> snapshot;
            synchronized (this) {
                snapshot = List.copyOf(listeners);
            }
            for (PluginLifecycleListener listener : snapshot) {
                try {
                    action.accept(listener);
                } catch (RuntimeException ex) {
                    if (faultReporter != null) {
                        faultReporter.accept("[PluginFault] plugin=" + listener.pluginId()
                                + " hook=" + hookName + " error=" + ex.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Extensible {@code .vjava} DSL command handler interface (no {@code I}-prefix) ({@code #0797}).
     */
    public interface PluginCommandHandler {
        /**
         * Returns registered lowercase command keyword/prefix.
         *
         * @return command prefix (e.g. {@code "weather_fx"})
         */
        String prefix();

        /**
         * Executes plugin command with raw argument tail.
         *
         * @param rawArgs argument tail following command prefix
         * @return optional string outcome or feedback
         */
        Optional<String> execute(String rawArgs);
    }

    /**
     * Routes unknown plugin-prefixed DSL statements cleanly without modifying core parser ({@code #0797}).
     */
    public static final class PluginCommandRouter {

        private final Map<String, PluginCommandHandler> handlers = new LinkedHashMap<>();

        /**
         * Creates an empty {@code PluginCommandRouter}.
         */
        public PluginCommandRouter() {
        }

        /**
         * Registers a plugin command handler by its prefix.
         *
         * @param handler command handler
         */
        public synchronized void register(PluginCommandHandler handler) {
            Objects.requireNonNull(handler, "handler must not be null");
            String key = Objects.requireNonNull(handler.prefix(), "prefix must not be null").trim().toLowerCase();
            if (key.isEmpty()) {
                throw new IllegalArgumentException("Command prefix must not be blank");
            }
            handlers.put(key, handler);
        }

        /**
         * Attempts to route a full command statement {@code "<prefix> <args>"} to a registered handler.
         *
         * @param statementLine raw command statement
         * @return result if a plugin handled the statement, empty if prefix unrecognized
         */
        public synchronized Optional<String> tryDispatch(String statementLine) {
            if (statementLine == null || statementLine.isBlank()) {
                return Optional.empty();
            }
            String trimmed = statementLine.trim();
            int sp = trimmed.indexOf(' ');
            String prefix = (sp > 0 ? trimmed.substring(0, sp) : trimmed).toLowerCase();
            String args = sp > 0 ? trimmed.substring(sp + 1).trim() : "";
            PluginCommandHandler handler = handlers.get(prefix);
            if (handler == null) {
                return Optional.empty();
            }
            return handler.execute(args);
        }
    }

    /**
     * Custom plugin type serializer interface (no {@code I}-prefix) ({@code #0814}).
     */
    public interface PluginTypeSerializer {
        /**
         * Returns unique type discriminator tag written inside saved payloads.
         *
         * @return discriminator type tag
         */
        String typeTag();

        /**
         * Serializes a custom plugin object into a plain JSON-safe map.
         *
         * @param instance custom object instance
         * @return string-keyed serialized property map
         */
        Map<String, Object> serialize(Object instance);

        /**
         * Reconstructs object from serialized map.
         *
         * @param payload serialized property map
         * @return reconstructed instance
         */
        Object deserialize(Map<String, Object> payload);
    }

    /**
     * Registry preserving raw fallback maps cleanly if the owning plugin serializer is unloaded ({@code #0814}).
     */
    public static final class PluginSerializerRegistry {

        private final Map<String, PluginTypeSerializer> serializers = new LinkedHashMap<>();

        /**
         * Creates an empty {@code PluginSerializerRegistry}.
         */
        public PluginSerializerRegistry() {
        }

        /**
         * Registers a {@link PluginTypeSerializer}.
         *
         * @param serializer serializer instance
         */
        public synchronized void register(PluginTypeSerializer serializer) {
            Objects.requireNonNull(serializer, "serializer must not be null");
            serializers.put(serializer.typeTag(), serializer);
        }

        /**
         * Unregisters a serializer by type tag (simulating plugin unload).
         *
         * @param typeTag type tag to remove
         */
        public synchronized void unregister(String typeTag) {
            serializers.remove(typeTag);
        }

        /**
         * Deserializes tagged payload or falls back cleanly to immutable map copy if unloaded ({@code #0814}).
         *
         * @param typeTag registered type discriminator
         * @param payload serialized fields map
         * @return deserialized domain object or defensive fallback map
         */
        public synchronized Object deserializeOrFallback(String typeTag, Map<String, Object> payload) {
            Map<String, Object> safePayload = payload != null
                    ? Collections.unmodifiableMap(new LinkedHashMap<>(payload))
                    : Map.of();
            PluginTypeSerializer serializer = serializers.get(typeTag);
            if (serializer == null) {
                return safePayload;
            }
            return serializer.deserialize(safePayload);
        }
    }

    /**
     * Priority-sorted menu button extension slot for plugins ({@code #0806}).
     */
    public static final class PluginUiSlotRegistry {

        /**
         * Menu slot contribution descriptor ({@code #0806}).
         *
         * @param pluginId originating plugin ID
         * @param menuSlot target UI menu slot (e.g. {@code "MAIN_MENU"}, {@code "PAUSE_MENU"})
         * @param label    button label text
         * @param priority ascending display priority index
         */
        public record MenuButtonContribution(String pluginId, String menuSlot, String label, int priority) {
        }

        private final List<MenuButtonContribution> contributions = new ArrayList<>();

        /**
         * Creates an empty {@code PluginUiSlotRegistry}.
         */
        public PluginUiSlotRegistry() {
        }

        /**
         * Registers a custom button extension safely.
         *
         * @param contribution button contribution descriptor
         */
        public synchronized void registerButton(MenuButtonContribution contribution) {
            contributions.add(Objects.requireNonNull(contribution, "contribution must not be null"));
        }

        /**
         * Returns priority-ordered button contributions matching {@code menuSlot}.
         *
         * @param menuSlot menu slot filter
         * @return sorted immutable list of {@link MenuButtonContribution}s
         */
        public synchronized List<MenuButtonContribution> getButtonsForSlot(String menuSlot) {
            List<MenuButtonContribution> filtered = new ArrayList<>();
            for (MenuButtonContribution c : contributions) {
                if (c.menuSlot().equalsIgnoreCase(menuSlot)) {
                    filtered.add(c);
                }
            }
            filtered.sort(Comparator.comparingInt(MenuButtonContribution::priority)
                    .thenComparing(MenuButtonContribution::pluginId));
            return List.copyOf(filtered);
        }
    }

    /**
     * Rolling log file writer capping size and file count (default 5 files x 5 MiB) ({@code #0833}).
     */
    public static final class RotatingLogWriter {

        private final Path baseLogFile;
        private final long maxBytesPerFile;
        private final int maxRotatedFiles;

        /**
         * Creates a {@code RotatingLogWriter} with 5 MiB cap and 5 files retention ({@code #0833}).
         *
         * @param logsDir directory containing {@code kagayaki.log}
         */
        public RotatingLogWriter(Path logsDir) {
            this(logsDir.resolve("kagayaki.log"), 5L * 1024L * 1024L, 5);
        }

        /**
         * Creates a {@code RotatingLogWriter} with configurable byte limit and retention count.
         *
         * @param baseLogFile     primary log path
         * @param maxBytesPerFile byte threshold before rotating
         * @param maxRotatedFiles max archive count ({@code >= 1})
         */
        public RotatingLogWriter(Path baseLogFile, long maxBytesPerFile, int maxRotatedFiles) {
            this.baseLogFile = Objects.requireNonNull(baseLogFile, "baseLogFile must not be null");
            if (maxBytesPerFile < 64L || maxRotatedFiles < 1) {
                throw new IllegalArgumentException("Invalid rotation bounds");
            }
            this.maxBytesPerFile = maxBytesPerFile;
            this.maxRotatedFiles = maxRotatedFiles;
        }

        /**
         * Appends {@code line} to the primary log, rotating {@code .1 .. .N} beforehand if needed.
         *
         * @param line log entry line
         * @throws IOException if file write or rotation fails
         */
        public synchronized void appendLine(String line) throws IOException {
            Objects.requireNonNull(line, "line must not be null");
            Path parent = baseLogFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String payload = line.endsWith("\n") ? line : (line + "\n");
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            if (Files.exists(baseLogFile) && Files.size(baseLogFile) + bytes.length > maxBytesPerFile) {
                rotateFiles();
            }
            Files.write(baseLogFile, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        private void rotateFiles() throws IOException {
            Path oldest = rotatedPath(maxRotatedFiles);
            Files.deleteIfExists(oldest);
            for (int i = maxRotatedFiles - 1; i >= 1; i--) {
                Path src = rotatedPath(i);
                Path dst = rotatedPath(i + 1);
                if (Files.exists(src)) {
                    Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (Files.exists(baseLogFile)) {
                Files.move(baseLogFile, rotatedPath(1), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private Path rotatedPath(int index) {
            return baseLogFile.resolveSibling(baseLogFile.getFileName().toString() + "." + index);
        }
    }

    /**
     * Tracks background executors and spawned OS child processes for guaranteed sub-second teardown on
     * shutdown / {@code SIGTERM} ({@code #0824}, {@code #0839}).
     */
    public static final class ShutdownResourceCoordinator {

        private final List<ExecutorService> executors = new ArrayList<>();
        private final List<Process> childProcesses = new ArrayList<>();

        /**
         * Creates a {@code ShutdownResourceCoordinator}.
         */
        public ShutdownResourceCoordinator() {
        }

        /**
         * Registers a background {@link ExecutorService} for managed shutdown.
         *
         * @param executor executor to manage
         */
        public synchronized void registerExecutor(ExecutorService executor) {
            if (executor != null) {
                executors.add(executor);
            }
        }

        /**
         * Registers a spawned OS child {@link Process} (e.g. TTS subprocess) for cleanup ({@code #0839}).
         *
         * @param process subprocess handle
         */
        public synchronized void registerChildProcess(Process process) {
            if (process != null) {
                childProcesses.add(process);
            }
        }

        /**
         * Terminates all registered child processes and stops background executors cleanly ({@code #0824}).
         *
         * @param timeoutMillis max grace time per executor
         * @return {@code true} if all executors terminated cleanly within budget
         */
        public synchronized boolean shutdownAll(long timeoutMillis) {
            for (Process p : childProcesses) {
                if (p.isAlive()) {
                    p.destroyForcibly();
                }
            }
            childProcesses.clear();
            boolean allClean = true;
            for (ExecutorService exec : executors) {
                exec.shutdownNow();
                try {
                    if (!exec.awaitTermination(Math.max(10L, timeoutMillis), TimeUnit.MILLISECONDS)) {
                        allClean = false;
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    allClean = false;
                }
            }
            executors.clear();
            return allClean;
        }
    }

    /**
     * Preserves unknown forward-compatible extension fields in {@code project.vjava.json} across Studio
     * saves ({@code #0831}).
     */
    public static final class ProjectExtensionsPreserver {

        private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

        /**
         * Creates a {@code ProjectExtensionsPreserver}.
         */
        public ProjectExtensionsPreserver() {
        }

        /**
         * Saves updated descriptor fields on top of {@code originalJson}, keeping any unrecognized extension
         * properties intact ({@code #0831}).
         *
         * @param originalJson existing {@code project.vjava.json} string
         * @param updates      map of updated standard properties
         * @return merged formatted JSON preserving unknown properties
         */
        public static String mergePreservingUnknownFields(String originalJson, Map<String, Object> updates) {
            JsonObject base = (originalJson != null && !originalJson.isBlank())
                    ? JsonParser.parseString(originalJson).getAsJsonObject()
                    : new JsonObject();
            if (updates != null) {
                for (Map.Entry<String, Object> entry : updates.entrySet()) {
                    JsonElement val = PRETTY_GSON.toJsonTree(entry.getValue());
                    base.add(entry.getKey(), val);
                }
            }
            return PRETTY_GSON.toJson(base);
        }
    }
}
