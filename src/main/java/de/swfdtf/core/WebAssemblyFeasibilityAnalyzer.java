package de.swfdtf.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Static WebAssembly / TeaVM / Browser portability auditor inspecting the headless KAGAYAKI kernel
 * and cataloging both LWJGL native bindings and non-LWJGL JVM platform blockers ({@code #1018}).
 */
public final class WebAssemblyFeasibilityAnalyzer {

    private static final Pattern IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+([A-Za-z0-9_.*]+)\\s*;",
            Pattern.MULTILINE);

    /**
     * Portability tier for a scanned source unit or package.
     */
    public enum PortabilityTier {
        /** Pure computational Java compatible directly with TeaVM / WasmGC. */
        DIRECTLY_PORTABLE,
        /** Requires browser/JS adapter shims (WebGL2, WebAudio, IndexedDB, JSON codec). */
        SHIM_REQUIRED,
        /** Blocked by JVM runtime/OS APIs not available inside standard WebAssembly sandboxes. */
        NATIVE_BLOCKED
    }

    /**
     * Individual platform API blocker discovered outside pure JVM core classes.
     *
     * @param relativeFile  repository-relative source path
     * @param packageName   source Java package name
     * @param apiIdentifier matched API import or usage token
     * @param lwjglRelated  {@code true} for LWJGL bindings, {@code false} for non-LWJGL JVM/OS blockers
     * @param tier          classified {@link PortabilityTier}
     * @param recommendation concrete browser/WASM mitigation strategy
     */
    public record DependencyFinding(
            String relativeFile,
            String packageName,
            String apiIdentifier,
            boolean lwjglRelated,
            PortabilityTier tier,
            String recommendation
    ) {
    }

    /**
     * Package-level portability rollup.
     *
     * @param packageName    Java package name
     * @param fileCount      number of scanned files in package
     * @param portableFiles  files directly portable without shims/blockers
     * @param shimFiles      files requiring browser/storage/audio/graphics shims
     * @param blockedFiles   files containing native OS/compiler/reflection blockers
     */
    public record PackageAudit(
            String packageName,
            int fileCount,
            int portableFiles,
            int shimFiles,
            int blockedFiles
    ) {
    }

    /**
     * Aggregated WebAssembly/TeaVM browser portability assessment report.
     *
     * @param totalFilesScanned          total {@code .java} files inspected
     * @param byPackage                  ordered package portability summary map
     * @param nonLwjglBlockers           non-LWJGL dependency findings documented beyond OpenGL/GLFW/OpenAL
     * @param lwjglFindings              LWJGL dependency findings requiring WebGL2/WebAudio shims
     * @param coreParserPortabilityPct   percentage of {@code math/core/parser/state} files without OS blockers
     */
    public record FeasibilityReport(
            int totalFilesScanned,
            Map<String, PackageAudit> byPackage,
            List<DependencyFinding> nonLwjglBlockers,
            List<DependencyFinding> lwjglFindings,
            double coreParserPortabilityPct
    ) {
        /** Normalizes maps and lists to immutable snapshots. */
        public FeasibilityReport {
            byPackage = byPackage != null ? Collections.unmodifiableMap(new LinkedHashMap<>(byPackage)) : Map.of();
            nonLwjglBlockers = nonLwjglBlockers != null ? List.copyOf(nonLwjglBlockers) : List.of();
            lwjglFindings = lwjglFindings != null ? List.copyOf(lwjglFindings) : List.of();
        }
    }

    private record Rule(
            String prefixOrToken,
            boolean regex,
            boolean lwjgl,
            PortabilityTier tier,
            String recommendation
    ) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("org.lwjgl", false, true, PortabilityTier.SHIM_REQUIRED,
                    "Replace LWJGL OpenGL/GLFW/OpenAL/STB with WebGL2/Canvas/WebAudio/Browser ImageDecoder shims."),
            new Rule("javax.tools", false, false, PortabilityTier.NATIVE_BLOCKED,
                    "Precompile @->{ ... }<< blocks ahead-of-time via CompiledScriptBundle (#0080)."),
            new Rule("ToolProvider", false, false, PortabilityTier.NATIVE_BLOCKED,
                    "Disable runtime javac in browser builds; load precompiled bytecode or transpiled snippets."),
            new Rule("java.lang.ProcessBuilder", false, false, PortabilityTier.NATIVE_BLOCKED,
                    "Gate CLI/Git subprocess features behind desktop-only capability flags."),
            new Rule("\\bnew\\s+ProcessBuilder\\b", true, false, PortabilityTier.NATIVE_BLOCKED,
                    "Gate CLI/Git subprocess features behind desktop-only capability flags."),
            new Rule("java.net.URLClassLoader", false, false, PortabilityTier.NATIVE_BLOCKED,
                    "Replace dynamic JAR/bytecode classloading with ahead-of-time TeaVM/Wasm static linking."),
            new Rule("java.nio.file", false, false, PortabilityTier.SHIM_REQUIRED,
                    "Bridge virtual filesystem reads/saves to VpakResourceProvider + browser IndexedDB."),
            new Rule("java.lang.reflect", false, false, PortabilityTier.SHIM_REQUIRED,
                    "Replace reflective ConditionEvaluator/Gson bindings with generated metadata adapters."),
            new Rule("java.net.http", false, false, PortabilityTier.SHIM_REQUIRED,
                    "Map HTTP client calls to browser fetch() API.")
    );

    private WebAssemblyFeasibilityAnalyzer() {
    }

    /**
     * Scans all production Java files under {@code repoRoot/src/main/java} and computes a structured
     * WebAssembly / TeaVM portability audit ({@code #1018}).
     *
     * @param repoRoot repository root directory (must not be {@code null})
     * @return immutable {@link FeasibilityReport}
     * @throws IOException if traversing source files fails
     */
    public static FeasibilityReport analyzeRepository(Path repoRoot) throws IOException {
        Objects.requireNonNull(repoRoot, "repoRoot must not be null");
        Path mainJava = repoRoot.toAbsolutePath().normalize().resolve("src/main/java");
        if (!Files.isDirectory(mainJava)) {
            throw new IllegalArgumentException("src/main/java not found under " + repoRoot);
        }

        List<Path> files;
        try (Stream<Path> walk = Files.walk(mainJava)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        Map<String, int[]> pkgCounts = new LinkedHashMap<>();
        List<DependencyFinding> nonLwjgl = new ArrayList<>();
        List<DependencyFinding> lwjgl = new ArrayList<>();
        int headlessTotal = 0;
        int headlessWithoutNativeBlock = 0;

        for (Path file : files) {
            String rel = repoRoot.toAbsolutePath().normalize().relativize(file).toString().replace('\\', '/');
            String src = Files.readString(file, StandardCharsets.UTF_8);
            String pkg = extractPackageName(src);

            List<DependencyFinding> fileFindings = inspectSource(rel, pkg, src);
            PortabilityTier highestTier = PortabilityTier.DIRECTLY_PORTABLE;
            boolean hasNativeBlock = false;
            for (DependencyFinding f : fileFindings) {
                if (f.lwjglRelated()) {
                    lwjgl.add(f);
                } else {
                    nonLwjgl.add(f);
                }
                if (f.tier() == PortabilityTier.NATIVE_BLOCKED) {
                    highestTier = PortabilityTier.NATIVE_BLOCKED;
                    hasNativeBlock = true;
                } else if (f.tier() == PortabilityTier.SHIM_REQUIRED
                        && highestTier == PortabilityTier.DIRECTLY_PORTABLE) {
                    highestTier = PortabilityTier.SHIM_REQUIRED;
                }
            }

            int[] counts = pkgCounts.computeIfAbsent(pkg, ignored -> new int[4]);
            counts[0]++;
            if (highestTier == PortabilityTier.DIRECTLY_PORTABLE) {
                counts[1]++;
            } else if (highestTier == PortabilityTier.SHIM_REQUIRED) {
                counts[2]++;
            } else {
                counts[3]++;
            }

            if (isHeadlessKernelPackage(pkg)) {
                headlessTotal++;
                if (!hasNativeBlock) {
                    headlessWithoutNativeBlock++;
                }
            }
        }

        Map<String, PackageAudit> byPackage = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : pkgCounts.entrySet()) {
            int[] c = e.getValue();
            byPackage.put(e.getKey(), new PackageAudit(e.getKey(), c[0], c[1], c[2], c[3]));
        }

        double pct = headlessTotal == 0 ? 100.0 : (headlessWithoutNativeBlock * 100.0) / headlessTotal;
        return new FeasibilityReport(files.size(), byPackage, nonLwjgl, lwjgl, pct);
    }

    private static List<DependencyFinding> inspectSource(String rel, String pkg, String src) {
        List<DependencyFinding> findings = new ArrayList<>();
        List<String> imports = new ArrayList<>();
        Matcher im = IMPORT_PATTERN.matcher(src);
        while (im.find()) {
            imports.add(im.group(1));
        }

        for (Rule rule : RULES) {
            boolean matched = false;
            String matchedToken = rule.prefixOrToken();
            if (rule.regex()) {
                Matcher rm = Pattern.compile(rule.prefixOrToken()).matcher(src);
                if (rm.find()) {
                    matched = true;
                    matchedToken = rm.group();
                }
            } else {
                for (String imp : imports) {
                    if (imp.startsWith(rule.prefixOrToken()) || imp.endsWith(rule.prefixOrToken())) {
                        matched = true;
                        matchedToken = imp;
                        break;
                    }
                }
                if (!matched && src.contains(rule.prefixOrToken())) {
                    matched = true;
                }
            }
            if (matched) {
                findings.add(new DependencyFinding(
                        rel, pkg, matchedToken, rule.lwjgl(), rule.tier(), rule.recommendation()));
            }
        }
        return findings;
    }

    private static boolean isHeadlessKernelPackage(String pkg) {
        return "de.swfdtf.math".equals(pkg)
                || "de.swfdtf.core".equals(pkg)
                || "de.swfdtf.parser".equals(pkg)
                || "de.swfdtf.state".equals(pkg);
    }

    private static String extractPackageName(String src) {
        for (String line : src.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ") && trimmed.endsWith(";")) {
                return trimmed.substring("package ".length(), trimmed.length() - 1).trim();
            }
        }
        return "default";
    }
}
