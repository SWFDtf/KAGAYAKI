package de.swfdtf.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Chapter 10 unified Virtual File System ({@code GameVfs}), prioritized Mod/DLC layering with
 * conflict &amp; cycle detection, bad-mod crash quarantine, starter mod generation, isolated plugin
 * hot-reload classloading, path-traversal guards, and multi-module `.vjava` resolution
 * ({@code #0771}–{@code #0773}, {@code #0781}, {@code #0798}, {@code #0817}, {@code #0823},
 * {@code #0827}, {@code #0838}, {@code #0847}).
 */
public final class GameVfsAndModServices {

    private GameVfsAndModServices() {
    }

    /**
     * Normalized path sanitization guard shared by {@link GameVfs} ({@code #0798}, {@code #0817}).
     *
     * @param relativePath candidate virtual path
     * @return clean forward-slash relative path
     * @throws SecurityException if path escapes root or contains illegal segments
     */
    public static String normalizeSafeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new SecurityException("Empty VFS path rejected");
        }
        String unified = relativePath.replace('\\', '/').trim();
        if (unified.startsWith("/") || unified.contains(":")) {
            throw new SecurityException("Absolute or drive-qualified VFS path rejected: " + relativePath);
        }
        String[] parts = unified.split("/");
        List<String> clean = new ArrayList<>(parts.length);
        for (String seg : parts) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                throw new SecurityException("Path traversal ('..') rejected by GameVfs: " + relativePath);
            }
            clean.add(seg);
        }
        if (clean.isEmpty()) {
            throw new SecurityException("Path resolves to empty after normalization: " + relativePath);
        }
        return String.join("/", clean);
    }

    /**
     * Unified read-only Virtual File System unifying prioritized Mods, mounted DLC {@code .vpak}s,
     * base archive/directory, and classpath defaults ({@code #0771}, {@code #0773}, {@code #0781},
     * {@code #0798}, {@code #0817}).
     */
    public static final class GameVfs {

        private final List<Path> modRoots = new ArrayList<>();
        private final List<VpakArchiver.VpakArchive> dlcMounts = new ArrayList<>();
        private final List<Path> baseDirectories = new ArrayList<>();
        private final Map<String, byte[]> classpathDefaults = new LinkedHashMap<>();

        /**
         * Creates an empty {@code GameVfs}.
         */
        public GameVfs() {
        }

        /**
         * Registers an active mod directory at the end of the mod chain (later adds override earlier).
         *
         * @param modDir normalized root directory of mod
         */
        public synchronized void mountModDirectory(Path modDir) {
            Objects.requireNonNull(modDir, "modDir must not be null");
            modRoots.add(modDir.toAbsolutePath().normalize());
        }

        /**
         * Mounts an additional DLC/episode {@code .vpak} archive transparently into the VFS tree ({@code #0773}).
         *
         * @param archive opened {@link VpakArchiver.VpakArchive}
         */
        public synchronized void mountAdditionalVpak(VpakArchiver.VpakArchive archive) {
            dlcMounts.add(Objects.requireNonNull(archive, "archive must not be null"));
        }

        /**
         * Registers a base project filesystem directory (normalized via {@code toAbsolutePath().normalize()})
         * ({@code #0817}).
         *
         * @param baseDir base project directory
         */
        public synchronized void mountBaseDirectory(Path baseDir) {
            Objects.requireNonNull(baseDir, "baseDir must not be null");
            baseDirectories.add(baseDir.toAbsolutePath().normalize());
        }

        /**
         * Registers fallback classpath/in-memory bytes.
         *
         * @param relativePath virtual resource path
         * @param bytes        resource payload bytes
         */
        public synchronized void registerClasspathDefault(String relativePath, byte[] bytes) {
            classpathDefaults.put(normalizeSafeRelativePath(relativePath), bytes.clone());
        }

        /**
         * Resolves and reads resource bytes respecting priority order:
         * Mods (newest override) -&gt; DLC `.vpak`s -&gt; Base dirs -&gt; Classpath defaults ({@code #0781}).
         *
         * @param relativePath virtual asset path
         * @return resource bytes if found
         * @throws IOException       if I/O read fails
         * @throws SecurityException if {@code relativePath} attempts traversal
         */
        public synchronized Optional<byte[]> readBytes(String relativePath) throws IOException {
            String clean = normalizeSafeRelativePath(relativePath);
            for (int i = modRoots.size() - 1; i >= 0; i--) {
                Path root = modRoots.get(i);
                Path candidate = root.resolve(clean).normalize();
                if (!candidate.startsWith(root)) {
                    throw new SecurityException("Mod path escapes mod root: " + relativePath);
                }
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(Files.readAllBytes(candidate));
                }
            }
            for (int i = dlcMounts.size() - 1; i >= 0; i--) {
                VpakArchiver.VpakArchive vpak = dlcMounts.get(i);
                if (vpak.contains(clean)) {
                    return Optional.of(vpak.readEntry(clean));
                }
            }
            for (int i = baseDirectories.size() - 1; i >= 0; i--) {
                Path base = baseDirectories.get(i);
                Path candidate = base.resolve(clean).normalize();
                if (!candidate.startsWith(base)) {
                    throw new SecurityException("Base path escapes project root: " + relativePath);
                }
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(Files.readAllBytes(candidate));
                }
            }
            byte[] cp = classpathDefaults.get(clean);
            return cp != null ? Optional.of(cp.clone()) : Optional.empty();
        }
    }

    /**
     * Manages {@code mods.json} ordering, conflict reporting, dependency cycle rejection, crash quarantine
     * disabling bad mods cleanly on next boot, and starter mod generation
     * ({@code #0771}, {@code #0772}, {@code #0823}, {@code #0827}).
     */
    public static final class ModManager {

        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

        /**
         * Declared mod metadata entry in {@code mods.json}.
         *
         * @param id           mod identifier
         * @param enabled      whether enabled by player
         * @param dependencies required mod IDs loaded beforehand
         * @param touchedFiles relative asset/script paths modified by this mod
         */
        public record ModDescriptor(
                String id,
                boolean enabled,
                List<String> dependencies,
                List<String> touchedFiles
        ) {
            /** Defensively copies lists. */
            public ModDescriptor {
                Objects.requireNonNull(id, "id must not be null");
                dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
                touchedFiles = touchedFiles != null ? List.copyOf(touchedFiles) : List.of();
            }
        }

        /**
         * Overlap conflict warning when two enabled mods modify the same file ({@code #0772}).
         *
         * @param filePath relative path modified by multiple mods
         * @param modIds   conflicting mod IDs in load order (last wins)
         */
        public record ModConflictReport(String filePath, List<String> modIds) {
        }

        private final Set<String> crashDisabledMods = new LinkedHashSet<>();

        /**
         * Creates a {@code ModManager}.
         */
        public ModManager() {
        }

        /**
         * Marks a crashing mod as quarantined/disabled for the next boot ({@code #0827}).
         *
         * @param modId failing mod identifier
         */
        public synchronized void markModCrashQuarantined(String modId) {
            if (modId != null && !modId.isBlank()) {
                crashDisabledMods.add(modId.trim());
            }
        }

        /**
         * Returns whether {@code modId} is currently quarantined due to a previous crash ({@code #0827}).
         *
         * @param modId mod identifier
         * @return {@code true} if quarantined
         */
        public synchronized boolean isQuarantined(String modId) {
            return modId != null && crashDisabledMods.contains(modId.trim());
        }

        /**
         * Validates dependencies and returns topological load order, rejecting cyclic dependencies with
         * {@link IllegalStateException} ({@code #0772}).
         *
         * @param mods candidate mods
         * @return ordered list of enabled, non-quarantined mods ready to mount
         */
        public synchronized List<ModDescriptor> resolveLoadOrder(List<ModDescriptor> mods) {
            if (mods == null || mods.isEmpty()) {
                return List.of();
            }
            Map<String, ModDescriptor> byId = new LinkedHashMap<>();
            for (ModDescriptor m : mods) {
                if (m.enabled() && !crashDisabledMods.contains(m.id())) {
                    byId.put(m.id(), m);
                }
            }
            Map<String, Integer> state = new LinkedHashMap<>();
            List<ModDescriptor> ordered = new ArrayList<>();
            for (String id : byId.keySet()) {
                visitTopological(id, byId, state, ordered, new ArrayDeque<>());
            }
            return List.copyOf(ordered);
        }

        /**
         * Detects file-level override collisions across active mods ({@code #0772}).
         *
         * @param mods active ordered mods
         * @return list of {@link ModConflictReport} entries
         */
        public static List<ModConflictReport> detectFileConflicts(List<ModDescriptor> mods) {
            if (mods == null || mods.size() < 2) {
                return List.of();
            }
            Map<String, List<String>> owners = new LinkedHashMap<>();
            for (ModDescriptor m : mods) {
                if (!m.enabled()) {
                    continue;
                }
                for (String file : m.touchedFiles()) {
                    owners.computeIfAbsent(normalizeSafeRelativePath(file), k -> new ArrayList<>()).add(m.id());
                }
            }
            List<ModConflictReport> conflicts = new ArrayList<>();
            for (Map.Entry<String, List<String>> e : owners.entrySet()) {
                if (e.getValue().size() > 1) {
                    conflicts.add(new ModConflictReport(e.getKey(), List.copyOf(e.getValue())));
                }
            }
            return List.copyOf(conflicts);
        }

        /**
         * Scaffolds a starter mod under {@code mods/<modId>/} with {@code plugin.json} and sample override
         * script ({@code #0823}).
         *
         * @param projectRoot root project directory
         * @param modId       mod folder ID
         * @return created mod root directory
         * @throws IOException if writing scaffold files fails
         */
        public static Path createStarterModTemplate(Path projectRoot, String modId) throws IOException {
            Objects.requireNonNull(projectRoot, "projectRoot must not be null");
            String cleanId = normalizeSafeRelativePath(modId);
            Path modDir = projectRoot.resolve("mods").resolve(cleanId);
            Files.createDirectories(modDir.resolve("scripts"));
            Map<String, Object> manifest = Map.of(
                    "id", cleanId,
                    "displayName", cleanId + " Mod",
                    "version", "1.0.0",
                    "minEngineApiVersion", "1.0.0",
                    "permissions", List.of("UI", "AUDIO")
            );
            Files.writeString(modDir.resolve("plugin.json"), GSON.toJson(manifest), StandardCharsets.UTF_8);
            Files.writeString(modDir.resolve("scripts").resolve("override.vjava"),
                    "label mod_" + cleanId + ":\n    \"Mod " + cleanId + " aktiv!\"\n    return\n",
                    StandardCharsets.UTF_8);
            return modDir;
        }

        private static void visitTopological(
                String id,
                Map<String, ModDescriptor> byId,
                Map<String, Integer> state,
                List<ModDescriptor> ordered,
                Deque<String> stack
        ) {
            int cur = state.getOrDefault(id, 0);
            if (cur == 2) {
                return;
            }
            if (cur == 1) {
                throw new IllegalStateException("Cyclic mod dependency detected involving: " + id
                        + " stack=" + stack);
            }
            state.put(id, 1);
            stack.push(id);
            ModDescriptor mod = byId.get(id);
            if (mod != null) {
                for (String dep : mod.dependencies()) {
                    if (byId.containsKey(dep)) {
                        visitTopological(dep, byId, state, ordered, stack);
                    }
                }
                ordered.add(mod);
            }
            stack.pop();
            state.put(id, 2);
        }
    }

    /**
     * Manages hot-reloadable plugin JARs over isolated {@link URLClassLoader} closing old loaders cleanly
     * ({@code #0838}) plus multi-module `.vjava` dependency lookup ({@code #0847}).
     */
    public static final class PluginHotReloader implements AutoCloseable {

        private URLClassLoader activeLoader;
        private int reloadCount = 0;

        /**
         * Creates a {@code PluginHotReloader}.
         */
        public PluginHotReloader() {
        }

        /**
         * Replaces any existing isolated {@link URLClassLoader}, closing the previous loader cleanly, and
         * opens a fresh loader for {@code jarOrDirPaths} ({@code #0838}).
         *
         * @param jarOrDirPaths plugin JAR/directory paths
         * @return newly opened {@link URLClassLoader}
         * @throws IOException if closing prior loader or building URLs fails
         */
        public synchronized URLClassLoader reload(List<Path> jarOrDirPaths) throws IOException {
            close();
            URL[] urls = new URL[jarOrDirPaths != null ? jarOrDirPaths.size() : 0];
            for (int i = 0; i < urls.length; i++) {
                urls[i] = jarOrDirPaths.get(i).toAbsolutePath().normalize().toUri().toURL();
            }
            activeLoader = new URLClassLoader(urls, PluginHotReloader.class.getClassLoader());
            reloadCount++;
            return activeLoader;
        }

        /**
         * Returns whether an active plugin classloader is loaded.
         *
         * @return {@code true} if active
         */
        public synchronized boolean hasActiveLoader() {
            return activeLoader != null;
        }

        /**
         * Returns reload invocation count.
         *
         * @return reload count
         */
        public synchronized int getReloadCount() {
            return reloadCount;
        }

        @Override
        public synchronized void close() throws IOException {
            if (activeLoader != null) {
                activeLoader.close();
                activeLoader = null;
            }
        }

        /**
         * Resolves reusable {@code .vjava} module dependency paths by namespace ({@code #0847}).
         *
         * @param moduleRoots   configured shared library directories
         * @param moduleName    requested library package name (e.g. {@code "inventory_pack"})
         * @return resolved module script path if present
         */
        public static Optional<Path> resolveModuleScript(List<Path> moduleRoots, String moduleName) {
            if (moduleRoots == null || moduleName == null || moduleName.isBlank()) {
                return Optional.empty();
            }
            String clean = normalizeSafeRelativePath(moduleName);
            for (Path root : moduleRoots) {
                Path candidate = root.toAbsolutePath().normalize().resolve(clean + ".vjava").normalize();
                if (candidate.startsWith(root.toAbsolutePath().normalize()) && Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        }

        /**
         * Parses valid JSON syntax check cleanly on mod manifests (`mods.json`) ({@code #0772}).
         *
         * @param modsJson raw {@code mods.json} text
         * @return true if syntactically valid JSON
         */
        public static boolean isValidModsJson(String modsJson) {
            if (modsJson == null || modsJson.isBlank()) {
                return false;
            }
            try {
                return JsonParser.parseString(modsJson) != null;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
    }
}
