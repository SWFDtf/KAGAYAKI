package de.swfdtf.parser;

import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.core.ResourceProvider;
import de.swfdtf.parser.VJavaAst.CharacterDef;
import de.swfdtf.parser.VJavaAst.ImageDef;
import de.swfdtf.parser.VJavaAst.JavaBlockNode;
import de.swfdtf.parser.VJavaAst.LabelNode;
import de.swfdtf.parser.VJavaAst.LayeredImageDef;
import de.swfdtf.parser.VJavaAst.ProjectAst;
import de.swfdtf.parser.VJavaAst.ScreenDef;
import de.swfdtf.parser.VJavaAst.ScriptFileAst;
import de.swfdtf.parser.VJavaAst.SetVarStatement;
import de.swfdtf.parser.VJavaAst.TransformDef;
import de.swfdtf.parser.VJavaAst.TranslateBlock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Recursively discovers and compiles all {@code .vjava} scripts, project metadata,
 * and {@code assets/} resources under a KAGAYAKI project root into a unified {@link ProjectAst}.
 *
 * <p>Script files may live at any nesting depth within the project directory (excluding build
 * and runtime directories {@code .git}, {@code build}, {@code .gradle}, {@code saves}, {@code out},
 * and {@code dist}). Files are processed in deterministic lexicographical relative-path order.
 * Duplicate definitions (labels, characters, screens, images, layered images, transforms) emit
 * non-fatal warnings and apply deterministic last-wins override semantics.</p>
 */
public final class ProjectScanner {

    /**
     * Directory names excluded during recursive {@code *.vjava} script discovery.
     */
    public static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            ".git", "build", ".gradle", "saves", "out", "dist"
    );

    private static final String SCRIPT_EXTENSION = ".vjava";
    private static final String ASSETS_DIR_NAME = "assets";

    private ProjectScanner() {
    }

    /**
     * Scans a KAGAYAKI project directory, loads {@code project.vjava.json} (or creates defaults),
     * parses all discovered {@code *.vjava} files, indexes {@code assets/}, and merges everything
     * into a unified {@link ProjectAst}.
     *
     * @param projectRoot root directory of the KAGAYAKI project
     * @return merged project AST
     * @throws IllegalArgumentException if {@code projectRoot} does not exist or is not a directory
     * @throws UncheckedIOException     if filesystem traversal or reading fails
     * @throws VJavaParseException      if any {@code .vjava} file contains lexical or syntax errors
     */
    public static ProjectAst scan(Path projectRoot) {
        Objects.requireNonNull(projectRoot, "projectRoot must not be null");
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        if (!Files.exists(normalizedRoot) || !Files.isDirectory(normalizedRoot)) {
            throw new IllegalArgumentException("Project root does not exist or is not a directory: " + normalizedRoot);
        }

        ProjectDescriptor descriptor = loadOrDefaultDescriptor(normalizedRoot);
        List<Path> scriptFiles = discoverScriptFiles(normalizedRoot);
        List<String> discoveredAssets = discoverAssetPaths(normalizedRoot);

        Map<String, CharacterDef> characters = new LinkedHashMap<>();
        Map<String, ImageDef> images = new LinkedHashMap<>();
        Map<String, LayeredImageDef> layeredImages = new LinkedHashMap<>();
        Map<String, TransformDef> transforms = new LinkedHashMap<>();
        Map<String, LabelNode> labels = new LinkedHashMap<>();
        Map<String, ScreenDef> screens = new LinkedHashMap<>();
        List<TranslateBlock> translations = new ArrayList<>();
        List<JavaBlockNode> topLevelJavaBlocks = new ArrayList<>();
        List<SetVarStatement> defaultVariables = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (Path scriptPath : scriptFiles) {
            String relativeSource = toUnixRelativePath(normalizedRoot, scriptPath);
            String content = readUtf8(scriptPath);
            mergeScriptContent(
                    scriptPath,
                    relativeSource,
                    content,
                    characters,
                    images,
                    layeredImages,
                    transforms,
                    labels,
                    screens,
                    translations,
                    topLevelJavaBlocks,
                    defaultVariables,
                    warnings
            );
        }

        return new ProjectAst(
                normalizedRoot,
                descriptor,
                scriptFiles,
                characters,
                images,
                layeredImages,
                transforms,
                labels,
                screens,
                translations,
                topLevelJavaBlocks,
                defaultVariables,
                discoveredAssets,
                warnings
        );
    }

    /**
     * Scans a packed project backed by {@link ResourceProvider} (e.g., {@code game.vpak}) without
     * requiring loose files on disk.
     *
     * @param virtualRoot virtual project root path associated with the AST
     * @param provider    resource provider serving {@code project.vjava.json}, scripts, and assets
     * @return merged project AST
     */
    public static ProjectAst scan(Path virtualRoot, ResourceProvider provider) {
        Objects.requireNonNull(virtualRoot, "virtualRoot must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Path normalizedRoot = virtualRoot.toAbsolutePath().normalize();

        ProjectDescriptor descriptor = loadDescriptorFromProvider(normalizedRoot, provider);
        List<String> allPaths = provider.listPaths();
        List<String> scriptRelPaths = allPaths.stream()
                .filter(p -> p.endsWith(SCRIPT_EXTENSION) && !hasSkippedDirectory(p))
                .sorted()
                .toList();
        List<String> discoveredAssets = allPaths.stream()
                .filter(p -> p.startsWith(ASSETS_DIR_NAME + "/"))
                .sorted()
                .toList();

        List<Path> scriptFiles = new ArrayList<>(scriptRelPaths.size());
        Map<String, CharacterDef> characters = new LinkedHashMap<>();
        Map<String, ImageDef> images = new LinkedHashMap<>();
        Map<String, LayeredImageDef> layeredImages = new LinkedHashMap<>();
        Map<String, TransformDef> transforms = new LinkedHashMap<>();
        Map<String, LabelNode> labels = new LinkedHashMap<>();
        Map<String, ScreenDef> screens = new LinkedHashMap<>();
        List<TranslateBlock> translations = new ArrayList<>();
        List<JavaBlockNode> topLevelJavaBlocks = new ArrayList<>();
        List<SetVarStatement> defaultVariables = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (String relPath : scriptRelPaths) {
            Path virtualFile = normalizedRoot.resolve(relPath);
            scriptFiles.add(virtualFile);
            String content;
            try {
                content = new String(provider.readBytes(relPath), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new UncheckedIOException("Failed to read packed .vjava script: " + relPath, ex);
            }
            mergeScriptContent(
                    virtualFile,
                    relPath,
                    content,
                    characters,
                    images,
                    layeredImages,
                    transforms,
                    labels,
                    screens,
                    translations,
                    topLevelJavaBlocks,
                    defaultVariables,
                    warnings
            );
        }

        return new ProjectAst(
                normalizedRoot,
                descriptor,
                scriptFiles,
                characters,
                images,
                layeredImages,
                transforms,
                labels,
                screens,
                translations,
                topLevelJavaBlocks,
                defaultVariables,
                discoveredAssets,
                warnings
        );
    }

    private static void mergeScriptContent(
            Path scriptPath,
            String relativeSource,
            String content,
            Map<String, CharacterDef> characters,
            Map<String, ImageDef> images,
            Map<String, LayeredImageDef> layeredImages,
            Map<String, TransformDef> transforms,
            Map<String, LabelNode> labels,
            Map<String, ScreenDef> screens,
            List<TranslateBlock> translations,
            List<JavaBlockNode> topLevelJavaBlocks,
            List<SetVarStatement> defaultVariables,
            List<String> warnings
    ) {
        List<VJavaLexer.Token> tokens = VJavaLexer.tokenize(content, relativeSource);
        ScriptFileAst fileAst = VJavaParser.parse(scriptPath, tokens);

        mergeEntries("character", fileAst.characters(), characters, warnings,
                CharacterDef::id, CharacterDef::sourceFile, CharacterDef::lineNumber);
        mergeEntries("image", fileAst.images(), images, warnings,
                ImageDef::fullKey, ImageDef::sourceFile, ImageDef::lineNumber);
        mergeEntries("layeredimage", fileAst.layeredImages(), layeredImages, warnings,
                LayeredImageDef::name, LayeredImageDef::sourceFile, LayeredImageDef::lineNumber);
        mergeEntries("transform", fileAst.transforms(), transforms, warnings,
                TransformDef::name, TransformDef::sourceFile, TransformDef::lineNumber);
        mergeEntries("label", fileAst.labels(), labels, warnings,
                LabelNode::name, LabelNode::sourceFile, LabelNode::lineNumber);
        mergeEntries("screen", fileAst.screens(), screens, warnings,
                ScreenDef::name, ScreenDef::sourceFile, ScreenDef::lineNumber);

        translations.addAll(fileAst.translations());
        topLevelJavaBlocks.addAll(fileAst.topLevelJavaBlocks());
        defaultVariables.addAll(fileAst.defaultVariables());
    }

    private static ProjectDescriptor loadDescriptorFromProvider(Path virtualRoot, ResourceProvider provider) {
        if (provider.contains(ProjectDescriptor.FILE_NAME)) {
            try {
                String json = new String(
                        provider.readBytes(ProjectDescriptor.FILE_NAME), StandardCharsets.UTF_8);
                return ProjectDescriptor.parseJson(json);
            } catch (IOException ex) {
                throw new UncheckedIOException("Failed to load packed " + ProjectDescriptor.FILE_NAME, ex);
            }
        }
        Path fileName = virtualRoot.getFileName();
        String fallbackTitle = fileName != null ? fileName.toString() : "Untitled KAGAYAKI Project";
        return ProjectDescriptor.createDefault(fallbackTitle);
    }

    private static boolean hasSkippedDirectory(String relPath) {
        String[] segments = relPath.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (SKIPPED_DIRECTORIES.contains(segments[i])) {
                return true;
            }
        }
        return false;
    }

    private static ProjectDescriptor loadOrDefaultDescriptor(Path projectRoot) {
        Path descriptorFile = projectRoot.resolve(ProjectDescriptor.FILE_NAME);
        if (Files.isRegularFile(descriptorFile)) {
            try {
                return ProjectDescriptor.load(projectRoot);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to load " + descriptorFile, e);
            }
        }
        Path fileName = projectRoot.getFileName();
        String fallbackTitle = fileName != null ? fileName.toString() : "Untitled KAGAYAKI Project";
        return ProjectDescriptor.createDefault(fallbackTitle);
    }

    private static List<Path> discoverScriptFiles(Path projectRoot) {
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(projectRoot)) {
                        Path dirName = dir.getFileName();
                        if (dirName != null && SKIPPED_DIRECTORIES.contains(dirName.toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        Path fileName = file.getFileName();
                        if (fileName != null && fileName.toString().endsWith(SCRIPT_EXTENSION)) {
                            found.add(file.toAbsolutePath().normalize());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan project scripts in " + projectRoot, e);
        }
        found.sort(Comparator.comparing(p -> toUnixRelativePath(projectRoot, p)));
        return found;
    }

    private static List<String> discoverAssetPaths(Path projectRoot) {
        Path assetsDir = projectRoot.resolve(ASSETS_DIR_NAME);
        if (!Files.exists(assetsDir) || !Files.isDirectory(assetsDir)) {
            return List.of();
        }
        List<String> assets = new ArrayList<>();
        try {
            Files.walkFileTree(assetsDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        assets.add(toUnixRelativePath(projectRoot, file));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan assets directory in " + assetsDir, e);
        }
        assets.sort(String::compareTo);
        return assets;
    }

    private static String readUtf8(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read .vjava script: " + file, e);
        }
    }

    private static String toUnixRelativePath(Path root, Path target) {
        return root.relativize(target.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static <T> void mergeEntries(
            String kind,
            List<T> incoming,
            Map<String, T> target,
            List<String> warnings,
            Function<T, String> keyExtractor,
            Function<T, String> sourceFileExtractor,
            ToIntFunction<T> lineExtractor
    ) {
        for (T entry : incoming) {
            String key = keyExtractor.apply(entry);
            T prev = target.put(key, entry);
            if (prev != null) {
                warnings.add(formatDuplicateWarning(
                        kind,
                        key,
                        sourceFileExtractor.apply(entry),
                        lineExtractor.applyAsInt(entry),
                        sourceFileExtractor.apply(prev),
                        lineExtractor.applyAsInt(prev)
                ));
            }
        }
    }

    private static String formatDuplicateWarning(
            String kind,
            String name,
            String newFile,
            int newLine,
            String oldFile,
            int oldLine
    ) {
        return "Duplicate " + kind + " '" + name + "' at " + newFile + ":" + newLine
                + " overrides previous definition at " + oldFile + ":" + oldLine;
    }
}
