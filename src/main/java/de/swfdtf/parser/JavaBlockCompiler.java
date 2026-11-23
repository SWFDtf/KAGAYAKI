package de.swfdtf.parser;

import de.swfdtf.core.EngineAPI;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.math.Transform2D;
import de.swfdtf.state.StateStore;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory Java 21 compiler for embedded {@code @->{ ... }<<} script blocks inside
 * {@link VJavaAst.ProjectAst}.
 *
 * <p>Uses {@link ToolProvider#getSystemJavaCompiler()} with an in-memory
 * {@link ForwardingJavaFileManager} and dedicated {@link ClassLoader}. Each block is cleanly
 * partitioned (while respecting string literals, text blocks, character literals, and comments)
 * into explicit {@code import} statements, user-defined class/interface/record/enum declarations,
 * top-level static helper members, and executable snippet body statements.</p>
 *
 * <p>All user-defined types and static helpers are aggregated into
 * {@code de.swfdtf.vjava.gen.VJavaUserTypes} (normalizing top-level types to
 * {@code public static ...}) and compiled prior to individual executable snippets
 * {@code de.swfdtf.vjava.gen.VJavaSnippet_<i>} implementing {@link VJavaExecutable}.</p>
 */
public final class JavaBlockCompiler {

    /** Generated package for in-memory compiled script classes and user types. */
    public static final String GENERATED_PACKAGE = "de.swfdtf.vjava.gen";

    /** Simple class name of the aggregated user type container. */
    public static final String USER_TYPES_SIMPLE_NAME = "VJavaUserTypes";

    /** Binary class name of the aggregated user type container. */
    public static final String USER_TYPES_BINARY_NAME = GENERATED_PACKAGE + "." + USER_TYPES_SIMPLE_NAME;

    private static final Set<String> TYPE_KEYWORDS = Set.of("class", "interface", "enum", "record");
    private static final Set<String> MODIFIER_KEYWORDS = Set.of(
            "public", "private", "protected", "static", "final", "abstract", "sealed", "non-sealed", "strictfp"
    );
    private static final Set<String> VISIBILITY_OR_STATIC_MODIFIERS = Set.of(
            "public", "private", "protected", "static"
    );
    private static final Set<String> STATEMENT_KEYWORDS = Set.of(
            "if", "else", "for", "while", "do", "switch", "try", "catch", "finally",
            "synchronized", "return", "throw", "assert", "break", "continue", "yield", "new"
    );

    /**
     * Registry of compiled {@link VJavaExecutable} snippets and user-defined types for a project.
     */
    public static final class CompiledProjectBlocks {

        private final Map<String, VJavaExecutable> executables;
        private final ClassLoader classLoader;
        private final boolean hasUserTypes;

        CompiledProjectBlocks(
                Map<String, VJavaExecutable> executables,
                ClassLoader classLoader,
                boolean hasUserTypes
        ) {
            this.executables = Collections.unmodifiableMap(new LinkedHashMap<>(executables));
            this.classLoader = Objects.requireNonNull(classLoader, "classLoader must not be null");
            this.hasUserTypes = hasUserTypes;
        }

        /**
         * Returns the compiled executable for {@code blockId}, or {@code null} if not registered.
         *
         * @param blockId block identifier from {@link VJavaAst.JavaBlockNode#id()}
         * @return compiled {@link VJavaExecutable} or {@code null}
         */
        public VJavaExecutable getExecutable(String blockId) {
            return blockId != null ? executables.get(blockId) : null;
        }

        /**
         * Returns the compiled executable for {@code blockId}, throwing if absent.
         *
         * @param blockId block identifier
         * @return compiled {@link VJavaExecutable}
         */
        public VJavaExecutable requireExecutable(String blockId) {
            VJavaExecutable executable = getExecutable(blockId);
            if (executable == null) {
                throw new IllegalArgumentException("No compiled VJava executable found for block id: " + blockId);
            }
            return executable;
        }

        /**
         * Returns whether a compiled executable exists for {@code blockId}.
         *
         * @param blockId block identifier
         * @return {@code true} if compiled
         */
        public boolean hasExecutable(String blockId) {
            return blockId != null && executables.containsKey(blockId);
        }

        /**
         * Returns an unmodifiable map of all compiled block executables keyed by block ID.
         *
         * @return immutable block-to-executable map
         */
        public Map<String, VJavaExecutable> getExecutables() {
            return executables;
        }

        /**
         * Returns the in-memory {@link ClassLoader} holding all compiled user types and snippets.
         *
         * @return compiled class loader
         */
        public ClassLoader getClassLoader() {
            return classLoader;
        }

        /**
         * Loads a user-defined class/interface/record/enum declared inside {@code @->{ ... }<<} blocks.
         *
         * @param simpleName simple name (e.g., {@code "SpezialGegenstand"}) or qualified binary name
         * @return loaded {@link Class} object
         * @throws IllegalArgumentException if no matching compiled type exists
         */
        public Class<?> loadUserType(String simpleName) {
            return findUserType(simpleName).orElseThrow(() ->
                    new IllegalArgumentException("Unknown compiled VJava user type: " + simpleName));
        }

        /**
         * Attempts to load a user-defined compiled type by simple or binary name.
         *
         * @param simpleName simple or qualified type name
         * @return optional loaded {@link Class}
         */
        public Optional<Class<?>> findUserType(String simpleName) {
            if (simpleName == null || simpleName.isBlank()) {
                return Optional.empty();
            }
            String trimmed = simpleName.trim();
            List<String> candidates = new ArrayList<>();
            if (!trimmed.contains(".") && !trimmed.contains("$")) {
                candidates.add(USER_TYPES_BINARY_NAME + "$" + trimmed);
                candidates.add(GENERATED_PACKAGE + "." + trimmed);
            } else {
                candidates.add(trimmed);
                if (!trimmed.startsWith(GENERATED_PACKAGE + ".")) {
                    candidates.add(USER_TYPES_BINARY_NAME + "$" + trimmed.replace('.', '$'));
                }
            }
            for (String candidate : candidates) {
                try {
                    return Optional.of(Class.forName(candidate, true, classLoader));
                } catch (ClassNotFoundException ignored) {
                    // Try next candidate
                }
            }
            return Optional.empty();
        }

        /**
         * Returns whether any shared user types or static helpers were compiled into {@code VJavaUserTypes}.
         *
         * @return {@code true} if {@code VJavaUserTypes} was generated
         */
        public boolean hasUserTypes() {
            return hasUserTypes;
        }

        /**
         * Returns the total number of registered block executables.
         *
         * @return compiled executable count
         */
        public int size() {
            return executables.size();
        }
    }

    /**
     * Compiles all {@link VJavaAst.JavaBlockNode} instances in {@code project}
     * (top-level blocks as well as blocks nested inside labels, conditionals, and menu choices).
     *
     * @param project unified project AST
     * @return compiled block registry and classloader
     * @throws VJavaCompileException if any block fails Java compilation
     */
    public CompiledProjectBlocks compile(VJavaAst.ProjectAst project) throws VJavaCompileException {
        Objects.requireNonNull(project, "project must not be null");
        return compile(collectJavaBlocks(project));
    }

    /**
     * Compiles an ordered list of {@link VJavaAst.JavaBlockNode} instances into in-memory bytecode.
     *
     * @param blocks ordered list of Java blocks to compile
     * @return compiled block registry and classloader
     * @throws VJavaCompileException if any block fails Java compilation
     */
    public CompiledProjectBlocks compile(List<VJavaAst.JavaBlockNode> blocks) throws VJavaCompileException {
        Objects.requireNonNull(blocks, "blocks must not be null");
        if (blocks.isEmpty()) {
            return new CompiledProjectBlocks(Map.of(), resolveParentClassLoader(), false);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new VJavaCompileException(
                    "System JavaCompiler is unavailable (ToolProvider.getSystemJavaCompiler() returned null). "
                            + "Ensure KAGAYAKI runs on a full JDK with jdk.compiler."
            );
        }

        List<ParsedBlock> parsedBlocks = new ArrayList<>(blocks.size());
        Set<String> scriptImports = new LinkedHashSet<>();
        List<ImportOrigin> importOrigins = new ArrayList<>();
        List<UserTypeMember> userTypeMembers = new ArrayList<>();

        for (VJavaAst.JavaBlockNode block : blocks) {
            ParsedBlock parsed = parseBlock(block);
            parsedBlocks.add(parsed);
            for (Segment importSeg : parsed.imports()) {
                String normalized = normalizeImportStatement(importSeg.text());
                if (scriptImports.add(normalized)) {
                    importOrigins.add(new ImportOrigin(normalized, block, importSeg.startLineOffset()));
                }
            }
            for (Segment typeSeg : parsed.typeDeclarations()) {
                userTypeMembers.add(new UserTypeMember(
                        normalizeTypeDeclaration(typeSeg.text()),
                        block,
                        typeSeg.startLineOffset()
                ));
            }
            for (Segment memberSeg : parsed.staticMembers()) {
                userTypeMembers.add(new UserTypeMember(
                        normalizeStaticMemberDeclaration(memberSeg.text()),
                        block,
                        memberSeg.startLineOffset()
                ));
            }
        }

        Map<String, byte[]> compiledBytecode = new LinkedHashMap<>();
        ClassLoader parentLoader = resolveParentClassLoader();
        String classpath = buildCompilerClasspath();
        boolean hasUserTypes = !userTypeMembers.isEmpty();

        if (hasUserTypes) {
            compileUserTypes(compiler, classpath, scriptImports, importOrigins, userTypeMembers, compiledBytecode);
        }

        Map<String, VJavaExecutable> executableMap = new LinkedHashMap<>();
        List<SnippetUnit> snippetUnits = new ArrayList<>();
        Map<String, SnippetUnit> unitByFileUri = new HashMap<>();

        int snippetIndex = 0;
        for (ParsedBlock parsed : parsedBlocks) {
            if (parsed.executableStatements().isEmpty()) {
                executableMap.put(parsed.block().id(), (state, persistent, engine, scene) -> {
                    // Declaration/import-only Java block
                });
                continue;
            }
            String className = "VJavaSnippet_" + (snippetIndex++);
            String binaryName = GENERATED_PACKAGE + "." + className;
            SnippetSource built = buildSnippetSource(className, parsed, scriptImports, hasUserTypes);
            InMemorySourceFile sourceObj = new InMemorySourceFile(binaryName, built.source());
            SnippetUnit unit = new SnippetUnit(parsed, className, binaryName, built, sourceObj);
            snippetUnits.add(unit);
            unitByFileUri.put(sourceObj.toUri().toString(), unit);
        }

        if (!snippetUnits.isEmpty()) {
            compileSnippets(compiler, classpath, snippetUnits, unitByFileUri, compiledBytecode);
        }

        InMemoryClassLoader classLoader = new InMemoryClassLoader(parentLoader, compiledBytecode);
        if (hasUserTypes) {
            try {
                Class.forName(USER_TYPES_BINARY_NAME, true, classLoader);
            } catch (ClassNotFoundException e) {
                throw new VJavaCompileException("Compiled VJavaUserTypes could not be loaded", e);
            }
        }

        for (SnippetUnit unit : snippetUnits) {
            try {
                Class<?> snippetClass = Class.forName(unit.binaryName(), true, classLoader);
                Object instance = snippetClass.getDeclaredConstructor().newInstance();
                executableMap.put(unit.parsed().block().id(), (VJavaExecutable) instance);
            } catch (Exception e) {
                VJavaAst.JavaBlockNode block = unit.parsed().block();
                throw new VJavaCompileException(
                        "Failed to instantiate compiled snippet '" + unit.binaryName() + "': " + e.getMessage(),
                        block.id(),
                        block.sourceFile(),
                        block.lineNumber(),
                        List.of(),
                        unit.built().source(),
                        e
                );
            }
        }

        return new CompiledProjectBlocks(executableMap, classLoader, hasUserTypes);
    }

    /**
     * Collects all {@link VJavaAst.JavaBlockNode} instances across top-level declarations and nested
     * label, conditional, and menu choice statements in deterministic execution order.
     *
     * @param project unified project AST
     * @return ordered list of all Java blocks
     */
    public static List<VJavaAst.JavaBlockNode> collectJavaBlocks(VJavaAst.ProjectAst project) {
        Objects.requireNonNull(project, "project must not be null");
        List<VJavaAst.JavaBlockNode> collected = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();

        for (VJavaAst.JavaBlockNode top : project.topLevelJavaBlocks()) {
            if (seenIds.add(top.id())) {
                collected.add(top);
            }
        }
        for (VJavaAst.LabelNode label : project.labels().values()) {
            collectFromStatements(label.statements(), collected, seenIds);
        }
        return collected;
    }

    private static void collectFromStatements(
            List<VJavaAst.Statement> statements,
            List<VJavaAst.JavaBlockNode> out,
            Set<String> seenIds
    ) {
        for (VJavaAst.Statement stmt : statements) {
            if (stmt instanceof VJavaAst.JavaBlockStatement jbs) {
                VJavaAst.JavaBlockNode block = jbs.block();
                if (seenIds.add(block.id())) {
                    out.add(block);
                }
            } else if (stmt instanceof VJavaAst.IfStatement ifs) {
                for (VJavaAst.IfBranch branch : ifs.branches()) {
                    collectFromStatements(branch.body(), out, seenIds);
                }
            } else if (stmt instanceof VJavaAst.MenuStatement ms) {
                for (VJavaAst.MenuChoice choice : ms.choices()) {
                    collectFromStatements(choice.body(), out, seenIds);
                }
            }
        }
    }

    private void compileUserTypes(
            JavaCompiler compiler,
            String classpath,
            Set<String> scriptImports,
            List<ImportOrigin> importOrigins,
            List<UserTypeMember> members,
            Map<String, byte[]> compiledBytecode
    ) {
        StringBuilder src = new StringBuilder();
        src.append("package ").append(GENERATED_PACKAGE).append(";\n\n");
        src.append("import de.swfdtf.state.*;\n");
        src.append("import de.swfdtf.core.*;\n");
        src.append("import de.swfdtf.graphics.*;\n");
        src.append("import de.swfdtf.math.*;\n");
        src.append("import java.util.*;\n");

        int currentLine = 8;
        Map<Integer, ImportOrigin> importLineMap = new HashMap<>();
        for (ImportOrigin origin : importOrigins) {
            src.append(origin.importStatement()).append('\n');
            importLineMap.put(currentLine, origin);
            currentLine++;
        }

        src.append('\n');
        currentLine++;
        src.append("public final class ").append(USER_TYPES_SIMPLE_NAME).append(" {\n");
        currentLine++;
        src.append("    private ").append(USER_TYPES_SIMPLE_NAME).append("() {}\n\n");
        currentLine += 2;

        List<LineRangeMapping> mappings = new ArrayList<>(members.size());
        for (UserTypeMember member : members) {
            int startLine = currentLine;
            String normalized = member.normalizedCode().stripTrailing();
            src.append(indentBlock(normalized, "    ")).append("\n\n");
            int lineCount = countLines(normalized);
            int endLine = startLine + Math.max(0, lineCount - 1);
            mappings.add(new LineRangeMapping(startLine, endLine, member.block(), member.lineOffset()));
            currentLine += lineCount + 1;
        }
        src.append("}\n");

        String generatedSource = src.toString();
        InMemorySourceFile sourceObj = new InMemorySourceFile(USER_TYPES_BINARY_NAME, generatedSource);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        try (StandardJavaFileManager stdManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
             InMemoryFileManager fileManager = new InMemoryFileManager(stdManager, compiledBytecode)) {

            List<String> options = buildCompilerOptions(classpath);
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    List.of(sourceObj)
            );
            Boolean success = task.call();
            if (!Boolean.TRUE.equals(success)) {
                throw buildUserTypesCompileException(diagnostics, mappings, importLineMap, members, generatedSource);
            }
        } catch (IOException e) {
            throw new VJavaCompileException("I/O failure while compiling VJava user types: " + e.getMessage(), e);
        }
    }

    private void compileSnippets(
            JavaCompiler compiler,
            String classpath,
            List<SnippetUnit> units,
            Map<String, SnippetUnit> unitByUri,
            Map<String, byte[]> compiledBytecode
    ) {
        List<JavaFileObject> sources = units.stream()
                .<JavaFileObject>map(SnippetUnit::sourceFile)
                .toList();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        try (StandardJavaFileManager stdManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
             InMemoryFileManager fileManager = new InMemoryFileManager(stdManager, compiledBytecode)) {

            List<String> options = buildCompilerOptions(classpath);
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    sources
            );
            Boolean success = task.call();
            if (!Boolean.TRUE.equals(success)) {
                throw buildSnippetsCompileException(diagnostics, units, unitByUri);
            }
        } catch (IOException e) {
            throw new VJavaCompileException("I/O failure while compiling VJava snippets: " + e.getMessage(), e);
        }
    }

    private VJavaCompileException buildUserTypesCompileException(
            DiagnosticCollector<JavaFileObject> diagnostics,
            List<LineRangeMapping> mappings,
            Map<Integer, ImportOrigin> importLineMap,
            List<UserTypeMember> members,
            String generatedSource
    ) {
        List<VJavaCompileException.CompileDiagnostic> entries = new ArrayList<>();
        VJavaAst.JavaBlockNode primaryBlock = members.get(0).block();
        int primaryLine = primaryBlock.lineNumber();

        for (Diagnostic<? extends JavaFileObject> diag : diagnostics.getDiagnostics()) {
            int genLine = (int) diag.getLineNumber();
            VJavaAst.JavaBlockNode matchedBlock = primaryBlock;
            int mappedLine = primaryLine;

            ImportOrigin imp = importLineMap.get(genLine);
            if (imp != null) {
                matchedBlock = imp.block();
                mappedLine = matchedBlock.lineNumber() + imp.lineOffset();
            } else {
                for (LineRangeMapping mapping : mappings) {
                    if (genLine >= mapping.startLine() && genLine <= mapping.endLine()) {
                        matchedBlock = mapping.block();
                        mappedLine = matchedBlock.lineNumber() + mapping.lineOffset() + (genLine - mapping.startLine());
                        break;
                    }
                }
            }

            if (entries.isEmpty()) {
                primaryBlock = matchedBlock;
                primaryLine = mappedLine;
            }
            entries.add(new VJavaCompileException.CompileDiagnostic(
                    matchedBlock.id(),
                    matchedBlock.sourceFile(),
                    mappedLine,
                    diag.getColumnNumber(),
                    diag.getKind().name(),
                    diag.getMessage(Locale.ROOT)
            ));
        }

        String summary = formatDiagnosticSummary("Failed to compile VJava user types", entries);
        return new VJavaCompileException(
                summary,
                primaryBlock.id(),
                primaryBlock.sourceFile(),
                primaryLine,
                entries,
                generatedSource,
                null
        );
    }

    private VJavaCompileException buildSnippetsCompileException(
            DiagnosticCollector<JavaFileObject> diagnostics,
            List<SnippetUnit> units,
            Map<String, SnippetUnit> unitByUri
    ) {
        List<VJavaCompileException.CompileDiagnostic> entries = new ArrayList<>();
        SnippetUnit primaryUnit = units.get(0);
        int primaryLine = primaryUnit.parsed().block().lineNumber();
        String primarySource = primaryUnit.built().source();

        for (Diagnostic<? extends JavaFileObject> diag : diagnostics.getDiagnostics()) {
            SnippetUnit unit = diag.getSource() != null
                    ? unitByUri.get(diag.getSource().toUri().toString())
                    : null;
            if (unit == null) {
                unit = primaryUnit;
            }

            VJavaAst.JavaBlockNode block = unit.parsed().block();
            int mappedLine = mapSnippetLine(unit, (int) diag.getLineNumber());
            if (entries.isEmpty()) {
                primaryUnit = unit;
                primaryLine = mappedLine;
                primarySource = unit.built().source();
            }

            entries.add(new VJavaCompileException.CompileDiagnostic(
                    block.id(),
                    block.sourceFile(),
                    mappedLine,
                    diag.getColumnNumber(),
                    diag.getKind().name(),
                    diag.getMessage(Locale.ROOT)
            ));
        }

        VJavaAst.JavaBlockNode primaryBlock = primaryUnit.parsed().block();
        String summary = formatDiagnosticSummary("Failed to compile VJava snippet", entries);
        return new VJavaCompileException(
                summary,
                primaryBlock.id(),
                primaryBlock.sourceFile(),
                primaryLine,
                entries,
                primarySource,
                null
        );
    }

    private static int mapSnippetLine(SnippetUnit unit, int generatedLine) {
        VJavaAst.JavaBlockNode block = unit.parsed().block();
        int bodyStart = unit.built().bodyStartLine();
        if (generatedLine < bodyStart) {
            return block.lineNumber();
        }
        int relative = generatedLine - bodyStart;
        for (SnippetBodyLineRange range : unit.built().bodyLineRanges()) {
            if (generatedLine >= range.genStartLine() && generatedLine <= range.genEndLine()) {
                return block.lineNumber() + range.blockStartLineOffset() + (generatedLine - range.genStartLine());
            }
        }
        return block.lineNumber() + relative;
    }

    private static String formatDiagnosticSummary(String prefix, List<VJavaCompileException.CompileDiagnostic> entries) {
        if (entries.isEmpty()) {
            return prefix;
        }
        List<String> formatted = entries.stream()
                .filter(d -> "ERROR".equals(d.kind()) || entries.size() <= 3)
                .limit(5)
                .map(VJavaCompileException.CompileDiagnostic::format)
                .toList();
        if (formatted.isEmpty()) {
            formatted = entries.stream().limit(5).map(VJavaCompileException.CompileDiagnostic::format).toList();
        }
        return prefix + ": " + String.join(" | ", formatted);
    }

    private static SnippetSource buildSnippetSource(
            String className,
            ParsedBlock parsed,
            Set<String> allScriptImports,
            boolean hasUserTypes
    ) {
        StringBuilder src = new StringBuilder();
        src.append("package ").append(GENERATED_PACKAGE).append(";\n\n");
        int currentLine = 3;

        if (hasUserTypes) {
            src.append("import ").append(USER_TYPES_BINARY_NAME).append(".*;\n");
            src.append("import static ").append(USER_TYPES_BINARY_NAME).append(".*;\n");
            currentLine += 2;
        }
        src.append("import de.swfdtf.parser.VJavaExecutable;\n");
        src.append("import de.swfdtf.state.*;\n");
        src.append("import de.swfdtf.core.*;\n");
        src.append("import de.swfdtf.graphics.*;\n");
        src.append("import de.swfdtf.math.*;\n");
        src.append("import java.util.*;\n");
        currentLine += 6;

        Set<String> localImports = new LinkedHashSet<>(allScriptImports);
        for (Segment seg : parsed.imports()) {
            localImports.add(normalizeImportStatement(seg.text()));
        }
        for (String imp : localImports) {
            src.append(imp).append('\n');
            currentLine++;
        }

        src.append('\n');
        src.append("public final class ").append(className).append(" implements VJavaExecutable {\n");
        src.append("    @Override\n");
        src.append("    public void execute(StateStore state, PersistentAccess persistent, EngineAPI engine, SceneController scene) throws Exception {\n");
        currentLine += 4;

        int bodyStartLine = currentLine;
        List<SnippetBodyLineRange> lineRanges = new ArrayList<>();
        for (Segment stmt : parsed.executableStatements()) {
            String trimmed = stmt.text().strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int genStart = currentLine;
            String indented = indentBlock(trimmed, "        ");
            src.append(indented).append('\n');
            int lines = countLines(trimmed);
            int genEnd = genStart + Math.max(0, lines - 1);
            lineRanges.add(new SnippetBodyLineRange(genStart, genEnd, stmt.startLineOffset()));
            currentLine += lines;
        }

        src.append("    }\n");
        src.append("}\n");
        return new SnippetSource(src.toString(), bodyStartLine, lineRanges);
    }

    // ---- Lexical Splitting & Classification ----

    static ParsedBlock parseBlock(VJavaAst.JavaBlockNode block) {
        List<Segment> segments = splitTopLevelSegments(block.code());
        List<Segment> imports = new ArrayList<>();
        List<Segment> typeDeclarations = new ArrayList<>();
        List<Segment> staticMembers = new ArrayList<>();
        List<Segment> executableStatements = new ArrayList<>();

        for (Segment seg : segments) {
            SegmentKind kind = classifySegment(seg.text());
            switch (kind) {
                case IMPORT -> imports.add(seg);
                case TYPE_DECLARATION -> typeDeclarations.add(seg);
                case STATIC_MEMBER -> staticMembers.add(seg);
                case STATEMENT -> executableStatements.add(seg);
            }
        }
        return new ParsedBlock(block, imports, typeDeclarations, staticMembers, executableStatements);
    }

    static List<Segment> splitTopLevelSegments(String code) {
        List<Segment> segments = new ArrayList<>();
        if (code == null || code.isBlank()) {
            return segments;
        }

        int len = code.length();
        int braceDepth = 0;
        int parenDepth = 0;
        int bracketDepth = 0;

        int segmentStart = 0;
        int segmentStartLine = 0;
        int currentLine = 0;
        boolean hasCodeChars = false;

        int i = 0;
        while (i < len) {
            char c = code.charAt(i);

            // Line comment //
            if (c == '/' && i + 1 < len && code.charAt(i + 1) == '/') {
                i += 2;
                while (i < len && code.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }

            // Block comment /* ... */
            if (c == '/' && i + 1 < len && code.charAt(i + 1) == '*') {
                i += 2;
                while (i < len) {
                    char bc = code.charAt(i);
                    if (bc == '\n') {
                        currentLine++;
                    } else if (bc == '*' && i + 1 < len && code.charAt(i + 1) == '/') {
                        i += 2;
                        break;
                    }
                    i++;
                }
                continue;
            }

            // Text block """ ... """
            if (c == '"' && i + 2 < len && code.charAt(i + 1) == '"' && code.charAt(i + 2) == '"') {
                if (!hasCodeChars) {
                    segmentStart = i;
                    segmentStartLine = currentLine;
                    hasCodeChars = true;
                }
                i += 3;
                while (i < len) {
                    char tc = code.charAt(i);
                    if (tc == '\n') {
                        currentLine++;
                    } else if (tc == '\\') {
                        i += 2;
                        continue;
                    } else if (tc == '"' && i + 2 < len && code.charAt(i + 1) == '"' && code.charAt(i + 2) == '"') {
                        i += 3;
                        break;
                    }
                    i++;
                }
                continue;
            }

            // String literal "..."
            if (c == '"') {
                if (!hasCodeChars) {
                    segmentStart = i;
                    segmentStartLine = currentLine;
                    hasCodeChars = true;
                }
                i++;
                while (i < len) {
                    char sc = code.charAt(i);
                    if (sc == '\n') {
                        currentLine++;
                    } else if (sc == '\\') {
                        i += 2;
                        continue;
                    } else if (sc == '"') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }

            // Char literal '...'
            if (c == '\'') {
                if (!hasCodeChars) {
                    segmentStart = i;
                    segmentStartLine = currentLine;
                    hasCodeChars = true;
                }
                i++;
                while (i < len) {
                    char cc = code.charAt(i);
                    if (cc == '\n') {
                        currentLine++;
                    } else if (cc == '\\') {
                        i += 2;
                        continue;
                    } else if (cc == '\'') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }

            if (Character.isWhitespace(c)) {
                if (c == '\n') {
                    currentLine++;
                }
                i++;
                continue;
            }

            if (!hasCodeChars) {
                segmentStart = i;
                segmentStartLine = currentLine;
                hasCodeChars = true;
            }

            if (c == '(') {
                parenDepth++;
                i++;
            } else if (c == ')') {
                parenDepth = Math.max(0, parenDepth - 1);
                i++;
            } else if (c == '[') {
                bracketDepth++;
                i++;
            } else if (c == ']') {
                bracketDepth = Math.max(0, bracketDepth - 1);
                i++;
            } else if (c == '{') {
                braceDepth++;
                i++;
            } else if (c == '}') {
                braceDepth = Math.max(0, braceDepth - 1);
                i++;
                if (braceDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                    int lookahead = skipWhitespaceAndComments(code, i);
                    if (lookahead < len) {
                        char nextChar = code.charAt(lookahead);
                        if (nextChar == ';') {
                            int linesSkipped = countNewlines(code, i, lookahead + 1);
                            currentLine += linesSkipped;
                            i = lookahead + 1;
                            segments.add(new Segment(code.substring(segmentStart, i).trim(), segmentStartLine));
                            segmentStart = i;
                            hasCodeChars = false;
                            continue;
                        }
                        if (nextChar == '.' || nextChar == ',' || nextChar == ')' || nextChar == ']') {
                            continue;
                        }
                        String nextWord = peekWord(code, lookahead);
                        if ("else".equals(nextWord) || "catch".equals(nextWord) || "finally".equals(nextWord)
                                || ("while".equals(nextWord) && startsWithKeyword(code.substring(segmentStart, i), "do"))) {
                            continue;
                        }
                    }
                    segments.add(new Segment(code.substring(segmentStart, i).trim(), segmentStartLine));
                    segmentStart = i;
                    hasCodeChars = false;
                }
            } else if (c == ';' && braceDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                i++;
                String segText = code.substring(segmentStart, i).trim();
                if (!segText.isEmpty() && !";".equals(segText)) {
                    segments.add(new Segment(segText, segmentStartLine));
                }
                segmentStart = i;
                hasCodeChars = false;
            } else {
                i++;
            }
        }

        if (hasCodeChars && segmentStart < len) {
            String tail = code.substring(segmentStart).trim();
            if (!tail.isEmpty() && !";".equals(tail)) {
                segments.add(new Segment(tail, segmentStartLine));
            }
        }

        return segments;
    }

    private static SegmentKind classifySegment(String segment) {
        List<Token> headerTokens = scanHeaderTokens(segment);
        if (headerTokens.isEmpty()) {
            return SegmentKind.STATEMENT;
        }

        int idx = skipLeadingAnnotations(headerTokens);
        if (idx >= headerTokens.size()) {
            return SegmentKind.STATEMENT;
        }

        Token first = headerTokens.get(idx);
        if ("import".equals(first.text())) {
            return SegmentKind.IMPORT;
        }

        // Handle static initializer block: static { ... }
        if ("static".equals(first.text()) && idx + 1 < headerTokens.size() && "{".equals(headerTokens.get(idx + 1).text())) {
            return SegmentKind.STATIC_MEMBER;
        }

        boolean hasVisibilityOrStatic = false;
        int modCursor = idx;
        while (modCursor < headerTokens.size()) {
            String tok = headerTokens.get(modCursor).text();
            if (MODIFIER_KEYWORDS.contains(tok)) {
                if (VISIBILITY_OR_STATIC_MODIFIERS.contains(tok)) {
                    hasVisibilityOrStatic = true;
                }
                modCursor++;
                // Check for annotations interleaved with modifiers
                while (modCursor < headerTokens.size() && "@".equals(headerTokens.get(modCursor).text())) {
                    modCursor = skipSingleAnnotation(headerTokens, modCursor);
                }
                continue;
            }
            break;
        }

        if (modCursor < headerTokens.size()) {
            String kw = headerTokens.get(modCursor).text();
            if (TYPE_KEYWORDS.contains(kw)) {
                return SegmentKind.TYPE_DECLARATION;
            }
            // Annotation type declaration: @interface Foo
            if ("@".equals(kw) && modCursor + 1 < headerTokens.size() && "interface".equals(headerTokens.get(modCursor + 1).text())) {
                return SegmentKind.TYPE_DECLARATION;
            }
        }

        if (hasVisibilityOrStatic) {
            return SegmentKind.STATIC_MEMBER;
        }

        if (looksLikeTopLevelMethodDeclaration(segment, headerTokens, idx)) {
            return SegmentKind.STATIC_MEMBER;
        }

        return SegmentKind.STATEMENT;
    }

    private static boolean looksLikeTopLevelMethodDeclaration(String segment, List<Token> tokens, int startTokenIndex) {
        if (!segment.endsWith("}")) {
            return false;
        }
        String first = tokens.get(startTokenIndex).text();
        if (STATEMENT_KEYWORDS.contains(first) || "{".equals(first)) {
            return false;
        }
        // Check for labeled statement: ident : ...
        if (startTokenIndex + 1 < tokens.size() && ":".equals(tokens.get(startTokenIndex + 1).text())) {
            return false;
        }
        int parenDepth = 0;
        int angleDepth = 0;
        boolean sawOpenParen = false;
        boolean sawCloseParen = false;

        for (int i = startTokenIndex; i < tokens.size(); i++) {
            String t = tokens.get(i).text();
            if ("(".equals(t)) {
                if (!sawOpenParen && parenDepth == 0 && angleDepth == 0) {
                    // Must have at least return type + method name before '('
                    if (i - startTokenIndex < 2) {
                        return false;
                    }
                    sawOpenParen = true;
                }
                parenDepth++;
            } else if (")".equals(t)) {
                parenDepth = Math.max(0, parenDepth - 1);
                if (sawOpenParen && parenDepth == 0) {
                    sawCloseParen = true;
                }
            } else if ("<".equals(t) && parenDepth == 0) {
                angleDepth++;
            } else if (">".equals(t) && parenDepth == 0 && angleDepth > 0) {
                angleDepth--;
            } else if ("=".equals(t) && parenDepth == 0) {
                return false;
            } else if ("{".equals(t) && parenDepth == 0) {
                return sawCloseParen;
            }
        }
        return false;
    }

    static String normalizeTypeDeclaration(String declaration) {
        return normalizeMemberOrTypeDeclaration(declaration, true);
    }

    static String normalizeStaticMemberDeclaration(String declaration) {
        List<Token> tokens = scanHeaderTokens(declaration);
        int idx = skipLeadingAnnotations(tokens);
        if (idx < tokens.size() && "static".equals(tokens.get(idx).text())
                && idx + 1 < tokens.size() && "{".equals(tokens.get(idx + 1).text())) {
            return declaration.trim();
        }
        return normalizeMemberOrTypeDeclaration(declaration, false);
    }

    private static String normalizeMemberOrTypeDeclaration(String declaration, boolean isType) {
        List<Token> tokens = scanHeaderTokens(declaration);
        if (tokens.isEmpty()) {
            return declaration.trim();
        }
        int afterAnnotationsIdx = skipLeadingAnnotations(tokens);
        String prefix = afterAnnotationsIdx < tokens.size()
                ? declaration.substring(0, tokens.get(afterAnnotationsIdx).startIndex())
                : "";

        int modCursor = afterAnnotationsIdx;
        List<String> preservedModifiers = new ArrayList<>();
        while (modCursor < tokens.size()) {
            String tok = tokens.get(modCursor).text();
            if (MODIFIER_KEYWORDS.contains(tok)) {
                if (!VISIBILITY_OR_STATIC_MODIFIERS.contains(tok)) {
                    preservedModifiers.add(tok);
                }
                modCursor++;
                continue;
            }
            break;
        }

        if (modCursor >= tokens.size()) {
            return declaration.trim();
        }

        int restStart = tokens.get(modCursor).startIndex();
        String rest = declaration.substring(restStart);
        StringBuilder sb = new StringBuilder();
        if (!prefix.isBlank()) {
            sb.append(prefix.stripTrailing()).append('\n');
        }
        sb.append("public static ");
        for (String mod : preservedModifiers) {
            sb.append(mod).append(' ');
        }
        sb.append(rest);
        return sb.toString().trim();
    }

    private static String normalizeImportStatement(String importStmt) {
        String trimmed = importStmt.trim();
        return trimmed.endsWith(";") ? trimmed : trimmed + ";";
    }

    private static List<Token> scanHeaderTokens(String text) {
        List<Token> tokens = new ArrayList<>();
        int len = text.length();
        int i = 0;
        while (i < len && tokens.size() < 64) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '/' && i + 1 < len && text.charAt(i + 1) == '/') {
                i += 2;
                while (i < len && text.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < len && text.charAt(i + 1) == '*') {
                i += 2;
                while (i < len) {
                    if (text.charAt(i) == '*' && i + 1 < len && text.charAt(i + 1) == '/') {
                        i += 2;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                int start = i;
                char quote = c;
                i++;
                while (i < len) {
                    if (text.charAt(i) == '\\') {
                        i += 2;
                        continue;
                    }
                    if (text.charAt(i) == quote) {
                        i++;
                        break;
                    }
                    i++;
                }
                tokens.add(new Token(text.substring(start, i), start, i));
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                i++;
                while (i < len && Character.isJavaIdentifierPart(text.charAt(i))) {
                    i++;
                }
                String word = text.substring(start, i);
                if ("non".equals(word) && text.startsWith("-sealed", i)
                        && (i + 7 >= len || !Character.isJavaIdentifierPart(text.charAt(i + 7)))) {
                    i += 7;
                    word = "non-sealed";
                }
                tokens.add(new Token(word, start, i));
                continue;
            }
            tokens.add(new Token(String.valueOf(c), i, i + 1));
            i++;
            if (c == '{') {
                break;
            }
        }
        return tokens;
    }

    private static int skipLeadingAnnotations(List<Token> tokens) {
        int idx = 0;
        while (idx < tokens.size() && "@".equals(tokens.get(idx).text())) {
            if (idx + 1 < tokens.size() && "interface".equals(tokens.get(idx + 1).text())) {
                break;
            }
            idx = skipSingleAnnotation(tokens, idx);
        }
        return idx;
    }

    private static int skipSingleAnnotation(List<Token> tokens, int atIdx) {
        int idx = atIdx + 1;
        while (idx < tokens.size()) {
            idx++;
            if (idx < tokens.size() && ".".equals(tokens.get(idx).text())) {
                idx++;
                continue;
            }
            break;
        }
        if (idx < tokens.size() && "(".equals(tokens.get(idx).text())) {
            int depth = 1;
            idx++;
            while (idx < tokens.size() && depth > 0) {
                String t = tokens.get(idx).text();
                if ("(".equals(t)) {
                    depth++;
                } else if (")".equals(t)) {
                    depth--;
                }
                idx++;
            }
        }
        return idx;
    }

    private static int skipWhitespaceAndComments(String code, int start) {
        int len = code.length();
        int i = start;
        while (i < len) {
            char c = code.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < len && code.charAt(i + 1) == '/') {
                i += 2;
                while (i < len && code.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < len && code.charAt(i + 1) == '*') {
                i += 2;
                while (i < len) {
                    if (code.charAt(i) == '*' && i + 1 < len && code.charAt(i + 1) == '/') {
                        i += 2;
                        break;
                    }
                    i++;
                }
            } else {
                break;
            }
        }
        return i;
    }

    private static String peekWord(String code, int start) {
        int i = start;
        int len = code.length();
        if (i >= len || !Character.isJavaIdentifierStart(code.charAt(i))) {
            return "";
        }
        while (i < len && Character.isJavaIdentifierPart(code.charAt(i))) {
            i++;
        }
        return code.substring(start, i);
    }

    private static boolean startsWithKeyword(String text, String keyword) {
        int start = skipWhitespaceAndComments(text, 0);
        return keyword.equals(peekWord(text, start));
    }

    private static int countNewlines(String text, int start, int end) {
        int count = 0;
        for (int i = start; i < end && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static int countLines(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static String indentBlock(String text, String indent) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder(text.length() + lines.length * indent.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            if (!lines[i].isEmpty()) {
                sb.append(indent).append(lines[i]);
            }
        }
        return sb.toString();
    }

    // ---- Classpath & ClassLoader Resolution ----

    private static ClassLoader resolveParentClassLoader() {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader ownLoader = JavaBlockCompiler.class.getClassLoader();
        if (contextLoader != null) {
            try {
                Class<?> loaded = Class.forName(VJavaExecutable.class.getName(), false, contextLoader);
                if (loaded == VJavaExecutable.class) {
                    return contextLoader;
                }
            } catch (ClassNotFoundException ignored) {
                // Fall back to own classloader
            }
        }
        return ownLoader != null ? ownLoader : ClassLoader.getSystemClassLoader();
    }

    private static List<String> buildCompilerOptions(String classpath) {
        List<String> options = new ArrayList<>();
        options.add("-encoding");
        options.add("UTF-8");
        options.add("-proc:none");
        if (!classpath.isBlank()) {
            options.add("-classpath");
            options.add(classpath);
        }
        return options;
    }

    private static String buildCompilerClasspath() {
        Set<String> entries = new LinkedHashSet<>();

        String sysCp = System.getProperty("java.class.path");
        if (sysCp != null && !sysCp.isBlank()) {
            for (String entry : sysCp.split(File.pathSeparator)) {
                addExistingPathEntry(entries, entry);
            }
        }

        collectClassLoaderUrls(Thread.currentThread().getContextClassLoader(), entries);
        collectClassLoaderUrls(JavaBlockCompiler.class.getClassLoader(), entries);

        addCodeSourceEntry(entries, JavaBlockCompiler.class);
        addCodeSourceEntry(entries, VJavaAst.class);
        addCodeSourceEntry(entries, StateStore.class);
        addCodeSourceEntry(entries, EngineAPI.class);
        addCodeSourceEntry(entries, SceneController.class);
        addCodeSourceEntry(entries, Transform2D.class);

        return String.join(File.pathSeparator, entries);
    }

    private static void collectClassLoaderUrls(ClassLoader loader, Set<String> entries) {
        ClassLoader current = loader;
        while (current != null) {
            if (current instanceof URLClassLoader urlLoader) {
                for (URL url : urlLoader.getURLs()) {
                    if ("file".equalsIgnoreCase(url.getProtocol())) {
                        try {
                            Path path = Path.of(url.toURI()).toAbsolutePath().normalize();
                            if (Files.exists(path)) {
                                entries.add(path.toString());
                            }
                        } catch (URISyntaxException | IllegalArgumentException ignored) {
                            // Ignore malformed URL entries
                        }
                    }
                }
            }
            current = current.getParent();
        }
    }

    private static void addCodeSourceEntry(Set<String> entries, Class<?> anchor) {
        try {
            CodeSource cs = anchor.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                Path path = Path.of(cs.getLocation().toURI()).toAbsolutePath().normalize();
                if (Files.exists(path)) {
                    entries.add(path.toString());
                }
            }
        } catch (URISyntaxException | SecurityException | IllegalArgumentException ignored) {
            // Ignore restricted or non-file code sources
        }
    }

    private static void addExistingPathEntry(Set<String> entries, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return;
        }
        try {
            Path p = Path.of(rawPath.trim()).toAbsolutePath().normalize();
            if (Files.exists(p)) {
                entries.add(p.toString());
            }
        } catch (IllegalArgumentException ignored) {
            // Ignore invalid path strings
        }
    }

    // ---- Internal In-Memory Compiler Infrastructure ----

    private enum SegmentKind {
        IMPORT,
        TYPE_DECLARATION,
        STATIC_MEMBER,
        STATEMENT
    }

    record Segment(String text, int startLineOffset) {
    }

    record ParsedBlock(
            VJavaAst.JavaBlockNode block,
            List<Segment> imports,
            List<Segment> typeDeclarations,
            List<Segment> staticMembers,
            List<Segment> executableStatements
    ) {
    }

    private record Token(String text, int startIndex, int endIndex) {
    }

    private record ImportOrigin(String importStatement, VJavaAst.JavaBlockNode block, int lineOffset) {
    }

    private record UserTypeMember(String normalizedCode, VJavaAst.JavaBlockNode block, int lineOffset) {
    }

    private record LineRangeMapping(int startLine, int endLine, VJavaAst.JavaBlockNode block, int lineOffset) {
    }

    private record SnippetBodyLineRange(int genStartLine, int genEndLine, int blockStartLineOffset) {
    }

    private record SnippetSource(String source, int bodyStartLine, List<SnippetBodyLineRange> bodyLineRanges) {
    }

    private record SnippetUnit(
            ParsedBlock parsed,
            String className,
            String binaryName,
            SnippetSource built,
            InMemorySourceFile sourceFile
    ) {
    }

    private static final class InMemorySourceFile extends SimpleJavaFileObject {
        private final String sourceCode;

        InMemorySourceFile(String binaryName, String sourceCode) {
            super(URI.create("string:///" + binaryName.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.sourceCode = sourceCode;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return sourceCode;
        }
    }

    private static final class InMemoryClassFile extends SimpleJavaFileObject {
        private final String binaryName;
        private final Map<String, byte[]> classBytes;

        InMemoryClassFile(String binaryName, Map<String, byte[]> classBytes) {
            super(URI.create("mem:///" + binaryName.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
            this.binaryName = binaryName;
            this.classBytes = classBytes;
        }

        String getBinaryName() {
            return binaryName;
        }

        @Override
        public OutputStream openOutputStream() {
            return new ByteArrayOutputStream() {
                @Override
                public void close() throws IOException {
                    super.close();
                    classBytes.put(binaryName, toByteArray());
                }
            };
        }

        @Override
        public InputStream openInputStream() throws IOException {
            byte[] bytes = classBytes.get(binaryName);
            if (bytes == null) {
                throw new IOException("Class bytes not found for " + binaryName);
            }
            return new ByteArrayInputStream(bytes);
        }
    }

    private static final class InMemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, byte[]> classBytes;

        InMemoryFileManager(StandardJavaFileManager fileManager, Map<String, byte[]> classBytes) {
            super(fileManager);
            this.classBytes = classBytes;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(
                Location location,
                String className,
                JavaFileObject.Kind kind,
                FileObject sibling
        ) throws IOException {
            if (kind == JavaFileObject.Kind.CLASS) {
                return new InMemoryClassFile(className, classBytes);
            }
            return super.getJavaFileForOutput(location, className, kind, sibling);
        }

        @Override
        public Iterable<JavaFileObject> list(
                Location location,
                String packageName,
                Set<JavaFileObject.Kind> kinds,
                boolean recurse
        ) throws IOException {
            List<JavaFileObject> results = new ArrayList<>();
            for (JavaFileObject item : super.list(location, packageName, kinds, recurse)) {
                results.add(item);
            }
            if (kinds.contains(JavaFileObject.Kind.CLASS)
                    && (location == StandardLocation.CLASS_OUTPUT || location == StandardLocation.CLASS_PATH)) {
                for (String binaryName : classBytes.keySet()) {
                    int lastDot = binaryName.lastIndexOf('.');
                    String pkg = lastDot >= 0 ? binaryName.substring(0, lastDot) : "";
                    boolean matches = recurse
                            ? (packageName.isEmpty() || pkg.equals(packageName) || pkg.startsWith(packageName + "."))
                            : pkg.equals(packageName);
                    if (matches) {
                        results.add(new InMemoryClassFile(binaryName, classBytes));
                    }
                }
            }
            return results;
        }

        @Override
        public String inferBinaryName(Location location, JavaFileObject file) {
            if (file instanceof InMemoryClassFile mem) {
                return mem.getBinaryName();
            }
            return super.inferBinaryName(location, file);
        }
    }

    private static final class InMemoryClassLoader extends ClassLoader {
        private final Map<String, byte[]> classBytes;

        InMemoryClassLoader(ClassLoader parent, Map<String, byte[]> classBytes) {
            super(parent);
            this.classBytes = Map.copyOf(classBytes);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classBytes.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
