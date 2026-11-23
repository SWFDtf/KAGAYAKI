package de.swfdtf.parser;

import de.swfdtf.core.ProjectDescriptor;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable Abstract Syntax Tree (AST) model for KAGAYAKI {@code .vjava} visual novel scripts.
 *
 * <p>Covers indentation-based VN declarations ({@code define}, {@code image}, {@code layeredimage},
 * {@code transform}, {@code label}, {@code screen}, {@code translate}), statements (dialogue,
 * scenes, sprites, audio, menus, conditionals, jumps, calls, inputs), and embedded native Java
 * blocks ({@code :: Kode für VN ... @->{ ... }<<}).</p>
 */
public final class VJavaAst {

    private VJavaAst() {
    }

    /**
     * Sealed hierarchy of all executable VN statements inside labels, menu branches, and conditionals.
     */
    public sealed interface Statement permits
            JavaBlockStatement,
            SceneStatement,
            ShowStatement,
            HideStatement,
            SayStatement,
            JumpStatement,
            CallStatement,
            ReturnStatement,
            PlayStatement,
            QueueStatement,
            StopStatement,
            VoiceStatement,
            NvlStatement,
            BlockRollbackStatement,
            InputStatement,
            MenuStatement,
            IfStatement,
            SetVarStatement,
            PauseStatement,
            WithStatement,
            WindowStatement {

        String sourceFile();

        int lineNumber();
    }

    /**
     * Embedded native Java block ({@code @->{ ... }<<}).
     */
    public record JavaBlockNode(
            String id,
            String description,
            String code,
            String sourceFile,
            int lineNumber
    ) {
        public JavaBlockNode {
            Objects.requireNonNull(id, "id must not be null");
            description = description != null ? description : "";
            code = code != null ? code : "";
            sourceFile = sourceFile != null ? sourceFile : "";
        }
    }

    /**
     * Character speaker definition ({@code define lena = Character("Lena", ...)}).
     */
    public record CharacterDef(
            String id,
            String displayName,
            String colorHex,
            String outlineColorHex,
            String imageTag,
            String voiceTag,
            String textboxSkin,
            String voiceBleepSound,
            Map<String, String> properties,
            String sourceFile,
            int lineNumber
    ) {
        public CharacterDef {
            Objects.requireNonNull(id, "id must not be null");
            displayName = displayName != null ? displayName : id;
            properties = properties != null ? Map.copyOf(properties) : Map.of();
        }
    }

    /**
     * Standalone image declaration ({@code image bg_akademie = "assets/images/bg/akademie.png"}).
     */
    public record ImageDef(
            String tag,
            List<String> attributes,
            String assetPath,
            String sourceFile,
            int lineNumber
    ) {
        public ImageDef {
            Objects.requireNonNull(tag, "tag must not be null");
            attributes = attributes != null ? List.copyOf(attributes) : List.of();
            Objects.requireNonNull(assetPath, "assetPath must not be null");
        }

        public String fullKey() {
            return attributes.isEmpty() ? tag : tag + " " + String.join(" ", attributes);
        }
    }

    /**
     * Entry inside a {@link LayeredImageDef}.
     */
    public sealed interface LayeredEntry permits AlwaysLayerEntry, GroupLayerEntry, ConditionalLayerEntry {
    }

    /**
     * Unconditional base layer inside {@code layeredimage}.
     */
    public record AlwaysLayerEntry(String assetOrExpr) implements LayeredEntry {
    }

    /**
     * Attribute option inside a mutually exclusive {@code group}.
     */
    public record AttributeEntry(String name, boolean isDefault, String assetOrExpr, String conditionExpr) {
    }

    /**
     * Mutually exclusive attribute group inside {@code layeredimage}.
     */
    public record GroupLayerEntry(String groupName, List<AttributeEntry> attributes) implements LayeredEntry {
        public GroupLayerEntry {
            attributes = attributes != null ? List.copyOf(attributes) : List.of();
        }
    }

    /**
     * Conditional overlay layer inside {@code layeredimage}.
     */
    public record ConditionalLayerEntry(String conditionExpr, String assetOrExpr) implements LayeredEntry {
    }

    /**
     * Multi-layered composite sprite definition ({@code layeredimage lena:}).
     */
    public record LayeredImageDef(
            String name,
            List<LayeredEntry> entries,
            String sourceFile,
            int lineNumber
    ) {
        public LayeredImageDef {
            Objects.requireNonNull(name, "name must not be null");
            entries = entries != null ? List.copyOf(entries) : List.of();
        }
    }

    /**
     * Step inside an ATL {@code transform} block or inline sprite animation.
     */
    public record AtlStep(
            String kind,
            float durationSeconds,
            String easeName,
            Map<String, String> properties,
            List<AtlStep> children
    ) {
        public AtlStep {
            kind = kind != null ? kind : "set";
            properties = properties != null ? Map.copyOf(properties) : Map.of();
            children = children != null ? List.copyOf(children) : List.of();
        }
    }

    /**
     * Named ATL transform declaration ({@code transform center_zoom:}).
     */
    public record TransformDef(
            String name,
            List<AtlStep> steps,
            String sourceFile,
            int lineNumber
    ) {
        public TransformDef {
            Objects.requireNonNull(name, "name must not be null");
            steps = steps != null ? List.copyOf(steps) : List.of();
        }
    }

    /**
     * Declarative UI element inside a {@code screen} block.
     */
    public record ScreenItem(
            String keyword,
            String primaryArg,
            Map<String, String> properties,
            List<ScreenItem> children,
            String sourceFile,
            int lineNumber
    ) {
        public ScreenItem {
            Objects.requireNonNull(keyword, "keyword must not be null");
            properties = properties != null ? Map.copyOf(properties) : Map.of();
            children = children != null ? List.copyOf(children) : List.of();
        }
    }

    /**
     * Declarative UI screen definition ({@code screen main_menu():}).
     */
    public record ScreenDef(
            String name,
            List<String> parameters,
            boolean modal,
            int zOrder,
            List<ScreenItem> items,
            String sourceFile,
            int lineNumber
    ) {
        public ScreenDef {
            Objects.requireNonNull(name, "name must not be null");
            parameters = parameters != null ? List.copyOf(parameters) : List.of();
            items = items != null ? List.copyOf(items) : List.of();
        }
    }

    /**
     * Single translated string entry inside a {@link TranslateBlock}.
     */
    public record TranslateEntry(String speaker, String originalText, String translatedText) {
    }

    /**
     * Localization translation block ({@code translate deutsch <id>:}).
     */
    public record TranslateBlock(
            String language,
            String id,
            List<TranslateEntry> entries,
            String sourceFile,
            int lineNumber
    ) {
        public TranslateBlock {
            Objects.requireNonNull(language, "language must not be null");
            Objects.requireNonNull(id, "id must not be null");
            entries = entries != null ? List.copyOf(entries) : List.of();
        }
    }

    /**
     * Script label containing sequential VN statements ({@code label start:}).
     */
    public record LabelNode(
            String name,
            List<Statement> statements,
            String sourceFile,
            int lineNumber
    ) {
        public LabelNode {
            Objects.requireNonNull(name, "name must not be null");
            statements = statements != null ? List.copyOf(statements) : List.of();
        }
    }

    // ---- Statements ----

    public record JavaBlockStatement(JavaBlockNode block, String sourceFile, int lineNumber) implements Statement {
    }

    public record SceneStatement(String bgTag, String transitionExpr, String sourceFile, int lineNumber) implements Statement {
    }

    public record ShowStatement(
            String tag,
            List<String> attributes,
            String positionExpr,
            String transitionExpr,
            List<AtlStep> inlineAtl,
            String sourceFile,
            int lineNumber
    ) implements Statement {
        public ShowStatement {
            attributes = attributes != null ? List.copyOf(attributes) : List.of();
            inlineAtl = inlineAtl != null ? List.copyOf(inlineAtl) : List.of();
        }
    }

    public record HideStatement(String tag, String transitionExpr, String sourceFile, int lineNumber) implements Statement {
    }

    public record SayStatement(String speakerId, String text, String transitionExpr, String sourceFile, int lineNumber) implements Statement {
    }

    public record JumpStatement(String targetLabel, boolean isExpression, String sourceFile, int lineNumber) implements Statement {
    }

    public record CallStatement(String targetLabel, List<String> args, String sourceFile, int lineNumber) implements Statement {
        public CallStatement {
            args = args != null ? List.copyOf(args) : List.of();
        }
    }

    public record ReturnStatement(String expression, String sourceFile, int lineNumber) implements Statement {
    }

    public record PlayStatement(
            String channel,
            String assetPath,
            boolean loop,
            double fadeInSeconds,
            double volume,
            String sourceFile,
            int lineNumber
    ) implements Statement {
    }

    public record QueueStatement(String channel, List<String> assetPaths, String sourceFile, int lineNumber) implements Statement {
        public QueueStatement {
            assetPaths = assetPaths != null ? List.copyOf(assetPaths) : List.of();
        }
    }

    public record StopStatement(String channel, double fadeOutSeconds, String sourceFile, int lineNumber) implements Statement {
    }

    public record VoiceStatement(String assetPath, String sourceFile, int lineNumber) implements Statement {
    }

    public record NvlStatement(String action, String sourceFile, int lineNumber) implements Statement {
    }

    public record BlockRollbackStatement(String sourceFile, int lineNumber) implements Statement {
    }

    public record InputStatement(
            String targetVar,
            String prompt,
            String defaultValue,
            String allowChars,
            int maxLength,
            String sourceFile,
            int lineNumber
    ) implements Statement {
    }

    public record MenuChoice(
            String text,
            String conditionExpr,
            boolean disabledWhenFalse,
            List<Statement> body,
            String inlineJumpTarget,
            String sourceFile,
            int lineNumber
    ) {
        public MenuChoice {
            body = body != null ? List.copyOf(body) : List.of();
        }
    }

    public record MenuStatement(
            String caption,
            Double timeoutSeconds,
            String timeoutJumpLabel,
            List<MenuChoice> choices,
            String sourceFile,
            int lineNumber
    ) implements Statement {
        public MenuStatement {
            choices = choices != null ? List.copyOf(choices) : List.of();
        }
    }

    public record IfBranch(String conditionExpr, List<Statement> body) {
        public IfBranch {
            body = body != null ? List.copyOf(body) : List.of();
        }
    }

    public record IfStatement(List<IfBranch> branches, String sourceFile, int lineNumber) implements Statement {
        public IfStatement {
            branches = branches != null ? List.copyOf(branches) : List.of();
        }
    }

    public record SetVarStatement(String varName, String expression, boolean isDefaultOnly, String sourceFile, int lineNumber) implements Statement {
    }

    public record PauseStatement(Double seconds, String sourceFile, int lineNumber) implements Statement {
    }

    public record WithStatement(String transitionExpr, String sourceFile, int lineNumber) implements Statement {
    }

    public record WindowStatement(String action, String sourceFile, int lineNumber) implements Statement {
    }

    /**
     * Single parsed {@code .vjava} source unit prior to global project merging.
     */
    public record ScriptFileAst(
            Path filePath,
            List<CharacterDef> characters,
            List<ImageDef> images,
            List<LayeredImageDef> layeredImages,
            List<TransformDef> transforms,
            List<LabelNode> labels,
            List<ScreenDef> screens,
            List<TranslateBlock> translations,
            List<JavaBlockNode> topLevelJavaBlocks,
            List<SetVarStatement> defaultVariables
    ) {
        public ScriptFileAst {
            characters = List.copyOf(characters);
            images = List.copyOf(images);
            layeredImages = List.copyOf(layeredImages);
            transforms = List.copyOf(transforms);
            labels = List.copyOf(labels);
            screens = List.copyOf(screens);
            translations = List.copyOf(translations);
            topLevelJavaBlocks = List.copyOf(topLevelJavaBlocks);
            defaultVariables = List.copyOf(defaultVariables);
        }
    }

    /**
     * Unified global AST produced by {@code ProjectScanner} from all {@code **\/*.vjava} files
     * across a project directory.
     */
    public record ProjectAst(
            Path projectRoot,
            ProjectDescriptor descriptor,
            List<Path> scriptFiles,
            Map<String, CharacterDef> characters,
            Map<String, ImageDef> images,
            Map<String, LayeredImageDef> layeredImages,
            Map<String, TransformDef> transforms,
            Map<String, LabelNode> labels,
            Map<String, ScreenDef> screens,
            List<TranslateBlock> translations,
            List<JavaBlockNode> topLevelJavaBlocks,
            List<SetVarStatement> defaultVariables,
            List<String> discoveredAssets,
            List<String> warnings
    ) {
        public ProjectAst {
            scriptFiles = List.copyOf(scriptFiles);
            characters = Collections.unmodifiableMap(new LinkedHashMap<>(characters));
            images = Collections.unmodifiableMap(new LinkedHashMap<>(images));
            layeredImages = Collections.unmodifiableMap(new LinkedHashMap<>(layeredImages));
            transforms = Collections.unmodifiableMap(new LinkedHashMap<>(transforms));
            labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
            screens = Collections.unmodifiableMap(new LinkedHashMap<>(screens));
            translations = List.copyOf(translations);
            topLevelJavaBlocks = List.copyOf(topLevelJavaBlocks);
            defaultVariables = List.copyOf(defaultVariables);
            discoveredAssets = List.copyOf(discoveredAssets);
            warnings = List.copyOf(warnings);
        }
    }
}
