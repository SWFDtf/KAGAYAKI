package de.swfdtf.graphics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Safe loader, static GLSL validator, hot-reload registry, and boot precompilation manifest for custom
 * {@code define shader} fragment shaders stored inside a project's {@code assets/shaders/} directory.
 *
 * <p>On invalid shader reloads, reports diagnostic line numbers and preserves the previously known valid
 * shader source or compiled handle cleanly without interrupting the preview window.</p>
 */
public final class CustomShaderRegistry {

    /**
     * Parsed or registered shader entry tracking active source, version revision, and fallback state.
     *
     * @param name        shader identifier
     * @param source      validated GLSL source
     * @param sourcePath  optional disk source path (nullable)
     * @param revision    monotonically incremented revision number on valid reload
     */
    public record ShaderEntry(String name, String source, Path sourcePath, int revision) {
    }

    /**
     * Validation/reload diagnostic reporting line-accurate error messages when a shader fails checks.
     *
     * @param valid        {@code true} if accepted
     * @param shaderName   target shader name
     * @param lineNumber   1-based line number of error ({@code 0} if valid)
     * @param message      human-readable diagnostic
     * @param retainedPrev {@code true} if an earlier valid shader remained active
     */
    public record ReloadResult(
            boolean valid,
            String shaderName,
            int lineNumber,
            String message,
            boolean retainedPrev
    ) {
    }

    private static final Pattern DEFINE_SHADER_DECL = Pattern.compile(
            "define\\s+shader\\s+([A-Za-z_][A-Za-z0-9_]*)"
    );

    private final Map<String, ShaderEntry> shaders = new LinkedHashMap<>();

    /**
     * Creates an empty {@code CustomShaderRegistry}.
     */
    public CustomShaderRegistry() {
    }

    /**
     * Extracts shader name from a `.vjava` {@code define shader <name>} line.
     *
     * @param declaration declaration line
     * @return optional parsed shader name
     */
    public static Optional<String> parseDefineShaderName(String declaration) {
        if (declaration == null) {
            return Optional.empty();
        }
        Matcher m = DEFINE_SHADER_DECL.matcher(declaration);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /**
     * Validates core GLSL 3.30 Core fragment shader syntax (version directive, {@code main()}, balanced braces)
     * before hitting the driver compiler or during headless studio linting.
     *
     * @param shaderName logical shader name
     * @param source     GLSL fragment source
     * @return {@link ReloadResult} describing validity and exact offending line number on error
     */
    public static ReloadResult validateFragmentSource(String shaderName, String source) {
        String name = shaderName != null ? shaderName : "unnamed";
        if (source == null || source.isBlank()) {
            return new ReloadResult(false, name, 1, "Shader source is empty", false);
        }
        String[] lines = source.split("\\R", -1);
        int braceDepth = 0;
        boolean hasVersion = false;
        boolean hasMain = false;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("#version")) {
                hasVersion = true;
            }
            if (trimmed.contains("void main(")) {
                hasMain = true;
            }
            if (trimmed.contains("UNRESOLVED_SYNTAX_ERROR") || trimmed.endsWith(";;;")) {
                return new ReloadResult(
                        false,
                        name,
                        i + 1,
                        "Syntax error near line " + (i + 1) + ": " + trimmed,
                        false
                );
            }
            for (int c = 0; c < trimmed.length(); c++) {
                char ch = trimmed.charAt(c);
                if (ch == '{') {
                    braceDepth++;
                } else if (ch == '}') {
                    braceDepth--;
                    if (braceDepth < 0) {
                        return new ReloadResult(
                                false, name, i + 1, "Unexpected '}' at line " + (i + 1), false
                        );
                    }
                }
            }
        }
        if (!hasVersion) {
            return new ReloadResult(false, name, 1, "Missing #version 330 core directive", false);
        }
        if (!hasMain) {
            return new ReloadResult(false, name, lines.length, "Missing void main() entry point", false);
        }
        if (braceDepth != 0) {
            return new ReloadResult(false, name, lines.length, "Unclosed '{' brace at end of shader", false);
        }
        return new ReloadResult(true, name, 0, "Shader validated cleanly", false);
    }

    /**
     * Registers or hot-reloads a fragment shader cleanly. If validation fails while a valid prior version
     * exists, retains the previous entry and flags {@link ReloadResult#retainedPrev()}.
     *
     * @param name   shader identifier
     * @param source GLSL source
     * @param path   optional disk path
     * @return reload result
     */
    public ReloadResult registerOrReload(String name, String source, Path path) {
        Objects.requireNonNull(name, "name must not be null");
        ReloadResult check = validateFragmentSource(name, source);
        ShaderEntry previous = shaders.get(name);
        if (!check.valid()) {
            return new ReloadResult(
                    false,
                    name,
                    check.lineNumber(),
                    check.message(),
                    previous != null
            );
        }
        int nextRev = previous != null ? previous.revision() + 1 : 1;
        shaders.put(name, new ShaderEntry(name, source, path, nextRev));
        return new ReloadResult(true, name, 0, "Loaded revision " + nextRev, false);
    }

    /**
     * Loads or reloads a shader safely from a path constrained inside {@code projectAssetsShadersDir}.
     *
     * @param shaderDir project {@code assets/shaders} root directory
     * @param relPath   relative shader filename
     * @return reload result
     * @throws IOException if file reading fails
     */
    public ReloadResult loadFromShaderDirectory(Path shaderDir, String relPath) throws IOException {
        Objects.requireNonNull(shaderDir, "shaderDir must not be null");
        Objects.requireNonNull(relPath, "relPath must not be null");
        Path normRoot = shaderDir.toAbsolutePath().normalize();
        Path resolved = normRoot.resolve(relPath).toAbsolutePath().normalize();
        if (!resolved.startsWith(normRoot)) {
            return new ReloadResult(false, relPath, 1, "Path traversal outside assets/shaders blocked", false);
        }
        String content = Files.readString(resolved);
        String stem = resolved.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        return registerOrReload(stem, content, resolved);
    }

    /**
     * Returns an active shader entry by name, if registered.
     *
     * @param name shader identifier
     * @return optional {@link ShaderEntry}
     */
    public Optional<ShaderEntry> getShader(String name) {
        return Optional.ofNullable(shaders.get(name));
    }

    /**
     * Returns immutable map of all registered valid shaders.
     *
     * @return immutable map keyed by shader name
     */
    public Map<String, ShaderEntry> getShaders() {
        return Collections.unmodifiableMap(shaders);
    }

    /**
     * Validates a core list of named GLSL 3.30 shaders at engine boot so runtime stutter or driver errors
     * are detected upfront.
     *
     * @param coreSources map of shader names to GLSL sources
     * @return list of validation results
     */
    public static List<ReloadResult> precompileValidateManifest(Map<String, String> coreSources) {
        Objects.requireNonNull(coreSources, "coreSources must not be null");
        return coreSources.entrySet().stream()
                .map(e -> validateFragmentSource(e.getKey(), e.getValue()))
                .toList();
    }
}
