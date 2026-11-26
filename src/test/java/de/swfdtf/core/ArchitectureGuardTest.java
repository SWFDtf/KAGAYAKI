package de.swfdtf.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Permanent architectural, platform, and naming guards for KAGAYAKI ({@code #0936}, {@code #0937}, {@code #0942}).
 */
class ArchitectureGuardTest {

    private static final String ALLOWED_I_INTERFACE_FILE =
            "src/main/java/de/swfdtf/graphics/IRenderPipeline.java";

    private static final Pattern PACKAGE_DECL_PATTERN = Pattern.compile(
            "\\bpackage\\s+(de\\s*\\.\\s*swfdtf(?:\\s*\\.\\s*[a-z][a-z0-9_]*)*)\\s*;"
    );
    private static final Pattern PACKAGE_STMT_PATTERN = Pattern.compile("\\bpackage\\s+[^;]+;");
    private static final Pattern IMPORT_STMT_PATTERN =
            Pattern.compile("\\bimport(?:\\s+static)?\\s+([^;]+);");
    private static final Pattern IMPORT_INTERNAL_TARGET_PATTERN = Pattern.compile(
            "^(de\\.swfdtf(?:\\.[a-z][a-z0-9_]*)*)\\.(?:[A-Z*]).*"
    );
    private static final Pattern FQCN_INTERNAL_PATTERN = Pattern.compile(
            "\\b(de\\s*\\.\\s*swfdtf(?:\\s*\\.\\s*[a-z0-9_]+)*)\\s*\\.\\s*[A-Z][A-Za-z0-9_]*"
    );

    private static final Pattern FORBIDDEN_AWT_IMAGEIO_PATTERN =
            Pattern.compile("\\b(?:java\\s*\\.\\s*awt|javax\\s*\\.\\s*imageio)\\b");
    private static final Pattern INTERFACE_DECL_PATTERN =
            Pattern.compile("(?:(@)\\s*)?\\binterface\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern I_PREFIX_NAME_PATTERN = Pattern.compile("^I(?![a-z]).*");

    private static final Set<String> FORBIDDEN_STATE_TARGETS = Set.of(
            "de.swfdtf.parser",
            "de.swfdtf.ui",
            "de.swfdtf.tools",
            "de.swfdtf.core",
            "de.swfdtf.audio",
            "de.swfdtf.git",
            "de.swfdtf"
    );

    private static final Map<String, Set<String>> ALLOWED_PACKAGE_DEPENDENCIES = Map.of(
            "de.swfdtf.math", Set.of(),
            "de.swfdtf.git", Set.of(),
            "de.swfdtf.core", Set.of("de.swfdtf.math", "de.swfdtf.graphics"),
            "de.swfdtf.state", Set.of("de.swfdtf.math", "de.swfdtf.graphics"),
            "de.swfdtf.graphics", Set.of(
                    "de.swfdtf.math", "de.swfdtf.core", "de.swfdtf.state", "de.swfdtf.audio", "de.swfdtf.parser"
            ),
            "de.swfdtf.audio", Set.of("de.swfdtf.core", "de.swfdtf.graphics", "de.swfdtf.state", "de.swfdtf.parser"),
            "de.swfdtf.parser", Set.of("de.swfdtf.core", "de.swfdtf.math", "de.swfdtf.graphics", "de.swfdtf.state"),
            "de.swfdtf.ui", Set.of(
                    "de.swfdtf.core", "de.swfdtf.math", "de.swfdtf.graphics",
                    "de.swfdtf.audio", "de.swfdtf.state", "de.swfdtf.parser"
            ),
            "de.swfdtf.tools", Set.of(
                    "de.swfdtf.core", "de.swfdtf.math", "de.swfdtf.graphics",
                    "de.swfdtf.audio", "de.swfdtf.state", "de.swfdtf.parser", "de.swfdtf.ui"
            ),
            "de.swfdtf", Set.of("de.swfdtf.core", "de.swfdtf.tools")
    );

    @Test
    void stateHasNoParserUiOrToolsDependenciesAndPackageLayersRemainBounded() throws IOException {
        Path repoRoot = resolveRepoRoot();
        Path mainSourceRoot = repoRoot.resolve("src/main/java/de/swfdtf");
        List<Path> javaFiles = collectJavaFiles(mainSourceRoot);
        assertFalse(javaFiles.isEmpty(), "Expected production Java files under " + mainSourceRoot);

        Map<String, Set<String>> actualDepsByPackage = new LinkedHashMap<>();
        List<String> violations = new ArrayList<>();

        for (Path file : javaFiles) {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String stripped = stripCommentsAndStrings(raw);
            String sourcePackage = extractPackage(stripped, file);
            Set<String> targets = actualDepsByPackage.computeIfAbsent(sourcePackage, ignored -> new TreeSet<>());

            Set<String> fileTargets = extractInternalDependencies(stripped, sourcePackage);
            targets.addAll(fileTargets);

            if ("de.swfdtf.state".equals(sourcePackage)) {
                for (String target : fileTargets) {
                    if (FORBIDDEN_STATE_TARGETS.contains(target)) {
                        violations.add(repoRoot.relativize(file) + " -> forbidden state dependency on " + target);
                    }
                }
            }
        }

        for (Map.Entry<String, Set<String>> entry : actualDepsByPackage.entrySet()) {
            String pkg = entry.getKey();
            Set<String> allowed = ALLOWED_PACKAGE_DEPENDENCIES.get(pkg);
            assertNotNull(allowed, "Undocumented package in architecture matrix: " + pkg);
            for (String actualTarget : entry.getValue()) {
                if (!allowed.contains(actualTarget)) {
                    violations.add(pkg + " -> " + actualTarget
                            + " (not in allowed dependency matrix: " + allowed + ")");
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Architecture layer violations detected:\n" + String.join("\n", violations));
    }

    @Test
    void productionCodeHasNoAwtOrImageIoUsages() throws IOException {
        Path repoRoot = resolveRepoRoot();
        List<Path> productionRoots = List.of(
                repoRoot.resolve("src/main/java"),
                repoRoot.resolve("demo/src/main/java")
        );

        List<String> violations = new ArrayList<>();
        for (Path root : productionRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            for (Path file : collectJavaFiles(root)) {
                String stripped = stripCommentsAndStrings(Files.readString(file, StandardCharsets.UTF_8));
                String relativePath = repoRoot.relativize(file).toString().replace('\\', '/');
                violations.addAll(findForbiddenAwtOrImageIoUsages(stripped, relativePath));
            }
        }

        assertTrue(violations.isEmpty(),
                "Forbidden java.awt / javax.imageio usages found in production code:\n"
                        + String.join("\n", violations));
    }

    @Test
    void productionInterfacesFollowNamingConventionWithoutNewIPrefix() throws IOException {
        Path repoRoot = resolveRepoRoot();
        List<Path> productionRoots = List.of(
                repoRoot.resolve("src/main/java"),
                repoRoot.resolve("demo/src/main/java")
        );

        Set<String> iPrefixedInterfaces = new TreeSet<>();
        for (Path root : productionRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            for (Path file : collectJavaFiles(root)) {
                String stripped = stripCommentsAndStrings(Files.readString(file, StandardCharsets.UTF_8));
                String pkg = extractPackage(stripped, file);
                String normalizedRelPath = repoRoot.relativize(file).toString().replace('\\', '/');
                iPrefixedInterfaces.addAll(findIPrefixedInterfaces(stripped, pkg, normalizedRelPath));
            }
        }

        assertEquals(Set.of("de.swfdtf.graphics.IRenderPipeline"), iPrefixedInterfaces,
                "Only legacy de.swfdtf.graphics.IRenderPipeline may use an I-prefix");
    }

    @Test
    void adversarialProbesCatchCommentSplitMultilineSameLineAndAllowlistBypasses() {
        // 1. /*...*/ emits space so tokens cannot merge: interface/*bypass*/IEvilInterface
        String commentBypassSrc = "package de.swfdtf.core;\npublic interface/*bypass*/IEvilInterface { void run(); }\n";
        String commentBypassStripped = stripCommentsAndStrings(commentBypassSrc);
        assertTrue(commentBypassStripped.contains("interface IEvilInterface"));
        Set<String> commentBypassFound = findIPrefixedInterfaces(
                commentBypassStripped, "de.swfdtf.core", "src/main/java/de/swfdtf/core/Evil.java");
        assertEquals(1, commentBypassFound.size());
        assertTrue(commentBypassFound.iterator().next().contains("de.swfdtf.core.IEvilInterface"));

        // 2. Multiline & comment-split java.awt / javax.imageio (#0937)
        String awtSrc = "package de.swfdtf.ui;\nObject x = java\n.awt.Color.RED;\n"
                + "Object y = javax /*c*/ . /*c*/ imageio.ImageIO.class;\n";
        List<String> awtHits = findForbiddenAwtOrImageIoUsages(
                stripCommentsAndStrings(awtSrc), "src/main/java/de/swfdtf/ui/Probe.java");
        assertEquals(2, awtHits.size(), "Expected both java\\n.awt and javax/*c*/./*c*/imageio hits: " + awtHits);
        assertTrue(awtHits.get(0).startsWith("src/main/java/de/swfdtf/ui/Probe.java:2:"));
        assertTrue(awtHits.get(1).startsWith("src/main/java/de/swfdtf/ui/Probe.java:4:"));

        // 3. Multiline de.swfdtf.parser\n.VJavaAst and same-line post-import FQCN (#0936)
        String multilineDepSrc = "package de.swfdtf.state;\nClass<?> c = de.swfdtf.parser\n.VJavaAst.class;\n";
        assertEquals(Set.of("de.swfdtf.parser"),
                extractInternalDependencies(stripCommentsAndStrings(multilineDepSrc), "de.swfdtf.state"));

        String sameLineDepSrc = "package de.swfdtf.state;\n"
                + "import java.util.List; class X { Class<?> c = de.swfdtf.parser.VJavaAst.class; }\n";
        assertEquals(Set.of("de.swfdtf.parser"),
                extractInternalDependencies(stripCommentsAndStrings(sameLineDepSrc), "de.swfdtf.state"));

        // 4. Nested Holder.IRenderPipeline cannot collide with top-level allowlist (#0942)
        String nestedHolderSrc = "package de.swfdtf.graphics;\n"
                + "public class Holder { public interface IRenderPipeline { void hack(); } }\n";
        Set<String> nestedHolderFound = findIPrefixedInterfaces(
                stripCommentsAndStrings(nestedHolderSrc),
                "de.swfdtf.graphics",
                "src/main/java/de/swfdtf/graphics/Holder.java");
        assertNotEquals(Set.of("de.swfdtf.graphics.IRenderPipeline"), nestedHolderFound);
        assertTrue(nestedHolderFound.iterator().next().contains("nested"));

        String nestedInsideAllowedFile = "package de.swfdtf.graphics;\n"
                + "public interface IRenderPipeline { interface IRenderPipeline {} }\n";
        Set<String> nestedInAllowedFound = findIPrefixedInterfaces(
                stripCommentsAndStrings(nestedInsideAllowedFile),
                "de.swfdtf.graphics",
                ALLOWED_I_INTERFACE_FILE);
        assertEquals(2, nestedInAllowedFound.size());
        assertNotEquals(Set.of("de.swfdtf.graphics.IRenderPipeline"), nestedInAllowedFound);

        // 5. Numeric/uppercase I-prefix (I2DRenderer) is flagged (#0942)
        String i2dSrc = "package de.swfdtf.graphics;\npublic interface I2DRenderer {}\n";
        Set<String> i2dFound = findIPrefixedInterfaces(
                stripCommentsAndStrings(i2dSrc),
                "de.swfdtf.graphics",
                "src/main/java/de/swfdtf/graphics/I2DRenderer.java");
        assertEquals(1, i2dFound.size());
        assertTrue(i2dFound.iterator().next().contains("I2DRenderer"));

        // 6. Annotation with whitespace (@ interface IAnnoWithSpace) is NOT falsely flagged (#0942)
        String annoWithSpaceSrc = "package de.swfdtf.core;\npublic @ interface IAnnoWithSpace {}\n";
        assertEquals(Set.of(), findIPrefixedInterfaces(
                stripCommentsAndStrings(annoWithSpaceSrc),
                "de.swfdtf.core",
                "src/main/java/de/swfdtf/core/IAnnoWithSpace.java"));

        // 7. JLS 3.3 Unicode escapes (e.g. java.\u0061wt.Color) cannot bypass production guards
        String escapedAwtSrc = "package de.swfdtf.ui;\nObject x = java.\\u0061wt.Color.RED;\n"
                + "String literal = \"\\\\u0061\";\n";
        List<String> escapedAwtHits = findForbiddenAwtOrImageIoUsages(
                stripCommentsAndStrings(escapedAwtSrc), "src/main/java/de/swfdtf/ui/EscapedProbe.java");
        assertEquals(1, escapedAwtHits.size(), "Expected decoded \\u0061 AWT hit: " + escapedAwtHits);
        assertTrue(escapedAwtHits.get(0).startsWith("src/main/java/de/swfdtf/ui/EscapedProbe.java:2:"));
    }

    static Path resolveRepoRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("src/main/java/de/swfdtf"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate KAGAYAKI repository root from working directory");
    }

    static String decodeUnicodeEscapes(String rawSource) {
        StringBuilder out = new StringBuilder(rawSource.length());
        int n = rawSource.length();
        int i = 0;
        int consecutiveBackslashes = 0;
        while (i < n) {
            char c = rawSource.charAt(i);
            if (c == '\\') {
                if ((consecutiveBackslashes & 1) == 0 && i + 1 < n && rawSource.charAt(i + 1) == 'u') {
                    int j = i + 1;
                    while (j < n && rawSource.charAt(j) == 'u') {
                        j++;
                    }
                    if (j + 4 <= n && isHexQuad(rawSource, j)) {
                        int codePoint = Integer.parseInt(rawSource, j, j + 4, 16);
                        out.append((char) codePoint);
                        i = j + 4;
                        consecutiveBackslashes = 0;
                        continue;
                    }
                }
                out.append(c);
                i++;
                consecutiveBackslashes++;
            } else {
                out.append(c);
                i++;
                consecutiveBackslashes = 0;
            }
        }
        return out.toString();
    }

    private static boolean isHexQuad(String text, int start) {
        for (int k = 0; k < 4; k++) {
            if (Character.digit(text.charAt(start + k), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    static String stripCommentsAndStrings(String rawSource) {
        String source = decodeUnicodeEscapes(rawSource);
        StringBuilder out = new StringBuilder(source.length());
        int n = source.length();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            char next = (i + 1 < n) ? source.charAt(i + 1) : '\0';

            if (c == '/' && next == '/') {
                i += 2;
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                out.append(' ');
                i += 2;
                while (i < n) {
                    char ch = source.charAt(i);
                    if (ch == '\n') {
                        out.append('\n');
                    } else if (ch == '*' && i + 1 < n && source.charAt(i + 1) == '/') {
                        i += 2;
                        break;
                    }
                    i++;
                }
            } else if (c == '"' && i + 2 < n && next == '"' && source.charAt(i + 2) == '"') {
                i += 3;
                while (i < n) {
                    char ch = source.charAt(i);
                    if (ch == '\\' && i + 1 < n) {
                        if (source.charAt(i + 1) == '\n') {
                            out.append('\n');
                        }
                        i += 2;
                        continue;
                    }
                    if (ch == '\n') {
                        out.append('\n');
                        i++;
                        continue;
                    }
                    if (ch == '"' && i + 2 < n && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                        i += 3;
                        break;
                    }
                    i++;
                }
            } else if (c == '"') {
                i++;
                while (i < n) {
                    char ch = source.charAt(i);
                    if (ch == '\\' && i + 1 < n) {
                        if (source.charAt(i + 1) == '\n') {
                            out.append('\n');
                        }
                        i += 2;
                        continue;
                    }
                    if (ch == '\n') {
                        out.append('\n');
                        i++;
                        break;
                    }
                    i++;
                    if (ch == '"') {
                        break;
                    }
                }
            } else if (c == '\'') {
                i++;
                while (i < n) {
                    char ch = source.charAt(i);
                    if (ch == '\\' && i + 1 < n) {
                        if (source.charAt(i + 1) == '\n') {
                            out.append('\n');
                        }
                        i += 2;
                        continue;
                    }
                    if (ch == '\n') {
                        out.append('\n');
                        i++;
                        break;
                    }
                    i++;
                    if (ch == '\'') {
                        break;
                    }
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    static List<String> findForbiddenAwtOrImageIoUsages(String strippedSource, String relativePath) {
        List<String> violations = new ArrayList<>();
        Matcher matcher = FORBIDDEN_AWT_IMAGEIO_PATTERN.matcher(strippedSource);
        while (matcher.find()) {
            int lineNumber = lineNumberAt(strippedSource, matcher.start());
            String snippet = matcher.group().replaceAll("\\s+", " ").trim();
            violations.add(relativePath + ":" + lineNumber + ": " + snippet);
        }
        return violations;
    }

    static Set<String> findIPrefixedInterfaces(
            String strippedSource,
            String pkg,
            String normalizedRelPath
    ) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = INTERFACE_DECL_PATTERN.matcher(strippedSource);
        int cursor = 0;
        int braceDepth = 0;
        while (matcher.find()) {
            for (int i = cursor; i < matcher.start(); i++) {
                char ch = strippedSource.charAt(i);
                if (ch == '{') {
                    braceDepth++;
                } else if (ch == '}' && braceDepth > 0) {
                    braceDepth--;
                }
            }
            cursor = matcher.start();
            if (matcher.group(1) != null) {
                continue;
            }
            String interfaceName = matcher.group(2);
            if (!I_PREFIX_NAME_PATTERN.matcher(interfaceName).matches()) {
                continue;
            }
            boolean isAllowedTopLevel = braceDepth == 0
                    && ALLOWED_I_INTERFACE_FILE.equals(normalizedRelPath)
                    && "IRenderPipeline".equals(interfaceName);
            if (isAllowedTopLevel) {
                found.add(pkg + "." + interfaceName);
            } else {
                int line = lineNumberAt(strippedSource, matcher.start());
                String scope = braceDepth > 0 ? "nested" : "top-level";
                found.add(pkg + "." + interfaceName + " (" + normalizedRelPath + ":" + line + ", " + scope + ")");
            }
        }
        return found;
    }

    static Set<String> extractInternalDependencies(String strippedSource, String sourcePackage) {
        Set<String> targets = new LinkedHashSet<>();
        Matcher importMatcher = IMPORT_STMT_PATTERN.matcher(strippedSource);
        while (importMatcher.find()) {
            String compact = importMatcher.group(1).replaceAll("\\s+", "");
            Matcher targetMatcher = IMPORT_INTERNAL_TARGET_PATTERN.matcher(compact);
            if (targetMatcher.matches()) {
                String target = targetMatcher.group(1);
                if (!target.equals(sourcePackage)) {
                    targets.add(target);
                }
            }
        }

        String withoutPackage = stripMatchesPreservingNewlines(PACKAGE_STMT_PATTERN, strippedSource);
        String remainingCode = stripMatchesPreservingNewlines(IMPORT_STMT_PATTERN, withoutPackage);
        Matcher fqcnMatcher = FQCN_INTERNAL_PATTERN.matcher(remainingCode);
        while (fqcnMatcher.find()) {
            String target = fqcnMatcher.group(1).replaceAll("\\s+", "");
            if (!target.equals(sourcePackage)) {
                targets.add(target);
            }
        }
        return targets;
    }

    private static List<Path> collectJavaFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    private static String extractPackage(String strippedSource, Path file) {
        Matcher matcher = PACKAGE_DECL_PATTERN.matcher(strippedSource);
        assertTrue(matcher.find(), "Missing package declaration in " + file);
        return matcher.group(1).replaceAll("\\s+", "");
    }

    private static String stripMatchesPreservingNewlines(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder sb = new StringBuilder(input.length());
        int lastEnd = 0;
        while (matcher.find()) {
            sb.append(input, lastEnd, matcher.start());
            sb.append(' ');
            for (int i = matcher.start(); i < matcher.end(); i++) {
                if (input.charAt(i) == '\n') {
                    sb.append('\n');
                }
            }
            lastEnd = matcher.end();
        }
        sb.append(input, lastEnd, input.length());
        return sb.toString();
    }

    private static int lineNumberAt(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
