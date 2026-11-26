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
            WindowStatement,
            MoveStatement,
            MovePathStatement,
            RotateStatement,
            ScaleStatement,
            CollisionMaskStatement,
            HitboxStatement,
            OnOverlapStatement,
            SolidPlatformStatement,
            ParallelStatement,
            WaitMoveStatement,
            CameraFollowStatement,
            TriggerZoneStatement,
            WasdInitStatement,
            WasdStopStatement,
            PhysicsInitStatement,
            PhysicsStopStatement,
            OnCollideStatement,
            MatchStatement {

        /**
         * Returns the source file path where this statement appears.
         *
         * @return source file path
         */
        String sourceFile();

        /**
         * Returns the 1-based line number where this statement appears.
         *
         * @return 1-based line number
         */
        int lineNumber();
    }

    /**
     * Embedded native Java block ({@code @->{ ... }<<}).
     *
     * @param id          generated or explicit block identifier
     * @param description optional block description text
     * @param code        raw Java source inside the block
     * @param sourceFile  source script path
     * @param lineNumber  1-based starting line number
     */
    public record JavaBlockNode(
            String id,
            String description,
            String code,
            String sourceFile,
            int lineNumber
    ) {
        /** Validates ID and normalizes optional strings. */
        public JavaBlockNode {
            Objects.requireNonNull(id, "id must not be null");
            description = description != null ? description : "";
            code = code != null ? code : "";
            sourceFile = sourceFile != null ? sourceFile : "";
        }
    }

    /**
     * Character speaker definition ({@code define lena = Character("Lena", ...)}).
     *
     * @param id              character identifier
     * @param displayName     display name shown in dialogue boxes
     * @param colorHex        optional speaker name hex color
     * @param outlineColorHex optional outline hex color
     * @param imageTag        associated sprite/layeredimage tag
     * @param voiceTag        associated voice folder/tag
     * @param textboxSkin     optional custom textbox skin asset path
     * @param voiceBleepSound optional voice bleep sound asset path
     * @param properties      additional character properties
     * @param sourceFile      source script path
     * @param lineNumber      1-based line number
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
        /** Validates ID and normalizes display name and properties map. */
        public CharacterDef {
            Objects.requireNonNull(id, "id must not be null");
            displayName = displayName != null ? displayName : id;
            properties = properties != null ? Map.copyOf(properties) : Map.of();
        }
    }

    /**
     * Standalone image declaration ({@code image bg_akademie = "assets/images/bg/akademie.png"}).
     *
     * @param tag        base image tag
     * @param attributes optional attribute qualifiers
     * @param assetPath  image asset path
     * @param sourceFile source script path
     * @param lineNumber 1-based line number
     */
    public record ImageDef(
            String tag,
            List<String> attributes,
            String assetPath,
            String sourceFile,
            int lineNumber
    ) {
        /** Validates tag and asset path and copies attributes list. */
        public ImageDef {
            Objects.requireNonNull(tag, "tag must not be null");
            attributes = attributes != null ? List.copyOf(attributes) : List.of();
            Objects.requireNonNull(assetPath, "assetPath must not be null");
        }

        /**
         * Returns the space-joined full image key combining tag and attributes.
         *
         * @return full image key
         */
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
     *
     * @param assetOrExpr asset path or expression
     */
    public record AlwaysLayerEntry(String assetOrExpr) implements LayeredEntry {
    }

    /**
     * Attribute option inside a mutually exclusive {@code group}.
     *
     * @param name          attribute name
     * @param isDefault     whether this attribute is selected by default
     * @param assetOrExpr   asset path or expression
     * @param conditionExpr optional condition expression
     */
    public record AttributeEntry(String name, boolean isDefault, String assetOrExpr, String conditionExpr) {
    }

    /**
     * Mutually exclusive attribute group inside {@code layeredimage}.
     *
     * @param groupName  group name
     * @param attributes attribute entries in declaration order
     */
    public record GroupLayerEntry(String groupName, List<AttributeEntry> attributes) implements LayeredEntry {
        /** Copies attributes list defensively. */
        public GroupLayerEntry {
            attributes = attributes != null ? List.copyOf(attributes) : List.of();
        }
    }

    /**
     * Conditional overlay layer inside {@code layeredimage}.
     *
     * @param conditionExpr condition expression
     * @param assetOrExpr   asset path or expression
     */
    public record ConditionalLayerEntry(String conditionExpr, String assetOrExpr) implements LayeredEntry {
    }

    /**
     * Multi-layered composite sprite definition ({@code layeredimage lena:}).
     *
     * @param name       layered image tag name
     * @param entries    ordered layer entries
     * @param sourceFile source script path
     * @param lineNumber 1-based line number
     */
    public record LayeredImageDef(
            String name,
            List<LayeredEntry> entries,
            String sourceFile,
            int lineNumber
    ) {
        /** Validates name and copies entries list. */
        public LayeredImageDef {
            Objects.requireNonNull(name, "name must not be null");
            entries = entries != null ? List.copyOf(entries) : List.of();
        }
    }

    /**
     * Step inside an ATL {@code transform} block or inline sprite animation.
     *
     * @param kind            step kind ({@code "set"}, {@code "interpolate"}, {@code "parallel"}, etc.)
     * @param durationSeconds duration in seconds
     * @param easeName        easing curve name
     * @param properties      ATL property map
     * @param children        nested child steps
     */
    public record AtlStep(
            String kind,
            float durationSeconds,
            String easeName,
            Map<String, String> properties,
            List<AtlStep> children
    ) {
        /** Normalizes null kind and defensively copies properties and children. */
        public AtlStep {
            kind = kind != null ? kind : "set";
            properties = properties != null ? Map.copyOf(properties) : Map.of();
            children = children != null ? List.copyOf(children) : List.of();
        }
    }

    /**
     * Named ATL transform declaration ({@code transform center_zoom:}).
     *
     * @param name       transform identifier
     * @param steps      ordered ATL steps
     * @param sourceFile source script path
     * @param lineNumber 1-based line number
     */
    public record TransformDef(
            String name,
            List<AtlStep> steps,
            String sourceFile,
            int lineNumber
    ) {
        /** Validates name and copies steps list. */
        public TransformDef {
            Objects.requireNonNull(name, "name must not be null");
            steps = steps != null ? List.copyOf(steps) : List.of();
        }
    }

    /**
     * Declarative UI element inside a {@code screen} block.
     *
     * @param keyword    widget/container keyword
     * @param primaryArg primary positional argument
     * @param properties widget property map
     * @param children   nested child screen items
     * @param sourceFile source script path
     * @param lineNumber 1-based line number
     */
    public record ScreenItem(
            String keyword,
            String primaryArg,
            Map<String, String> properties,
            List<ScreenItem> children,
            String sourceFile,
            int lineNumber
    ) {
        /** Validates keyword and defensively copies properties and children. */
        public ScreenItem {
            Objects.requireNonNull(keyword, "keyword must not be null");
            properties = properties != null ? Map.copyOf(properties) : Map.of();
            children = children != null ? List.copyOf(children) : List.of();
        }
    }

    /**
     * Declarative UI screen definition ({@code screen main_menu():}).
     *
     * @param name       screen name
     * @param parameters declared parameter names
     * @param modal      whether screen blocks input to lower screens
     * @param zOrder     z-order priority
     * @param items      top-level screen items
     * @param sourceFile source script path
     * @param lineNumber 1-based line number
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
        /** Validates name and copies parameter and item lists. */
        public ScreenDef {
            Objects.requireNonNull(name, "name must not be null");
            parameters = parameters != null ? List.copyOf(parameters) : List.of();
            items = items != null ? List.copyOf(items) : List.of();
        }
    }

    /**
     * Single translated string entry inside a {@link TranslateBlock}.
     *
     * @param speaker        optional speaker ID
     * @param originalText   source language text
     * @param translatedText target language text
     */
    public record TranslateEntry(String speaker, String originalText, String translatedText) {
    }

    /**
     * Localization translation block ({@code translate deutsch <id>:}).
     *
     * @param language   target language name
     * @param id         block/line identifier or {@code "strings"}
     * @param entries    translation entries
     * @param sourceFile source script path
     * @param lineNumber 1-based line number
     */
    public record TranslateBlock(
            String language,
            String id,
            List<TranslateEntry> entries,
            String sourceFile,
            int lineNumber
    ) {
        /** Validates language and id and copies entries list. */
        public TranslateBlock {
            Objects.requireNonNull(language, "language must not be null");
            Objects.requireNonNull(id, "id must not be null");
            entries = entries != null ? List.copyOf(entries) : List.of();
        }
    }

    /**
     * Script label containing sequential VN statements ({@code label start:}).
     *
     * @param name       label name
     * @param statements ordered statements inside the label
     * @param sourceFile source script path
     * @param lineNumber 1-based line number
     */
    public record LabelNode(
            String name,
            List<Statement> statements,
            String sourceFile,
            int lineNumber
    ) {
        /** Validates label name and copies statements list. */
        public LabelNode {
            Objects.requireNonNull(name, "name must not be null");
            statements = statements != null ? List.copyOf(statements) : List.of();
        }
    }

    // ---- Statements ----

    /**
     * Statement executing an embedded native Java block.
     *
     * @param block      compiled Java block AST node
     * @param sourceFile source script path
     * @param lineNumber 1-based line number
     */
    public record JavaBlockStatement(
            JavaBlockNode block, String sourceFile, int lineNumber) implements Statement {
    }

    /**
     * Background scene change statement ({@code scene <bgTag> [with <transition>]}).
     *
     * @param bgTag          background image tag
     * @param transitionExpr optional transition expression
     * @param sourceFile     source script path
     * @param lineNumber     1-based line number
     */
    public record SceneStatement(
            String bgTag, String transitionExpr, String sourceFile, int lineNumber) implements Statement {
    }

    /**
     * Displays or updates a character/sprite on stage ({@code show <tag> ...}).
     *
     * @param tag            sprite or layered image tag
     * @param attributes     attribute qualifiers
     * @param positionExpr   optional position/transform expression
     * @param transitionExpr optional transition expression
     * @param inlineAtl      inline ATL animation steps
     * @param sourceFile     source script path
     * @param lineNumber     1-based line number
     */
    public record ShowStatement(
            String tag,
            List<String> attributes,
            String positionExpr,
            String transitionExpr,
            List<AtlStep> inlineAtl,
            String sourceFile,
            int lineNumber
    ) implements Statement {
        /** Copies attribute and inline ATL lists defensively. */
        public ShowStatement {
            attributes = attributes != null ? List.copyOf(attributes) : List.of();
            inlineAtl = inlineAtl != null ? List.copyOf(inlineAtl) : List.of();
        }
    }

    /**
     * Hides a displayed sprite from the stage ({@code hide <tag> [with <transition>]}).
     *
     * @param tag            sprite tag
     * @param transitionExpr optional transition expression
     * @param sourceFile     source script path
     * @param lineNumber     1-based line number
     */
    public record HideStatement(
            String tag, String transitionExpr, String sourceFile, int lineNumber) implements Statement {
    }

    /**
     * Dialogue or narration statement.
     *
     * @param speakerId      speaker character ID (nullable for narrator)
     * @param text           dialogue text
     * @param transitionExpr optional inline transition expression
     * @param sourceFile     source script path
     * @param lineNumber     1-based line number
     */
    public record SayStatement(
            String speakerId,
            String text,
            String transitionExpr,
            String sourceFile,
            int lineNumber) implements Statement {
    }

    /**
     * Unconditional jump to another label ({@code jump <target>}).
     *
     * @param targetLabel  target label name or expression
     * @param isExpression whether {@code targetLabel} should be evaluated as an expression
     * @param sourceFile   source script path
     * @param lineNumber   1-based line number
     */
    public record JumpStatement(
            String targetLabel, boolean isExpression, String sourceFile, int lineNumber) implements Statement {
    }

    /**
     * Subroutine call to another label ({@code call <target>(...)}).
     *
     * @param targetLabel target label name
     * @param args        call argument expressions
     * @param sourceFile  source script path
     * @param lineNumber  1-based line number
     */
    public record CallStatement(
            String targetLabel, List<String> args, String sourceFile, int lineNumber) implements Statement {
        /** Copies argument list defensively. */
        public CallStatement {
            args = args != null ? List.copyOf(args) : List.of();
        }
    }

    /**
     * Returns from a called subroutine or terminates script execution.
     *
     * @param expression optional return value expression
     * @param sourceFile source script path
     * @param lineNumber 1-based line number
     */
    public record ReturnStatement(String expression, String sourceFile, int lineNumber) implements Statement {
    }

    /**
     * Plays an audio track or sound effect on a named channel.
     *
     * @param channel          audio channel name
     * @param assetPath        audio asset path
     * @param loop             whether playback loops
     * @param fadeInSeconds    fade-in duration in seconds
     * @param volume           target volume multiplier
     * @param loopStartSeconds loop restart position in seconds when wrapping ({@code >= 0})
     * @param sourceFile       source script path
     * @param lineNumber       1-based line number
     */
    public record PlayStatement(
            String channel,
            String assetPath,
            boolean loop,
            double fadeInSeconds,
            double volume,
            double loopStartSeconds,
            String sourceFile,
            int lineNumber
    ) implements Statement {
        /**
         * Backwards-compatible constructor defaulting {@code loopStartSeconds} to {@code 0.0}.
         *
         * @param channel       audio channel name
         * @param assetPath     audio asset path
         * @param loop          whether playback loops
         * @param fadeInSeconds fade-in duration in seconds
         * @param volume        target volume multiplier
         * @param sourceFile    source script path
         * @param lineNumber    1-based line number
         */
        public PlayStatement(
                String channel,
                String assetPath,
                boolean loop,
                double fadeInSeconds,
                double volume,
                String sourceFile,
                int lineNumber
        ) {
            this(channel, assetPath, loop, fadeInSeconds, volume, 0.0, sourceFile, lineNumber);
        }
    }

    /**
     * Enqueues audio tracks on a named channel.
     *
     * @param channel    audio channel name
     * @param assetPaths ordered audio asset paths
     * @param sourceFile source script path
     * @param lineNumber 1-based line number
     */
    public record QueueStatement(
            String channel, List<String> assetPaths, String sourceFile, int lineNumber) implements Statement {
        /** Copies asset paths list defensively. */
        public QueueStatement {
            assetPaths = assetPaths != null ? List.copyOf(assetPaths) : List.of();
        }
    }

    /**
     * Stops playback on a named audio channel.
     *
     * @param channel        audio channel name
     * @param fadeOutSeconds fade-out duration in seconds
     * @param sourceFile     source script path
     * @param lineNumber     1-based line number
     */
    public record StopStatement(
            String channel, double fadeOutSeconds, String sourceFile, int lineNumber) implements Statement {
    }

    /**
     * Associates a voice audio file with the next dialogue line.
     *
     * @param assetPath  voice audio asset path
     * @param sourceFile source script path
     * @param lineNumber 1-based line number
     */
    public record VoiceStatement(String assetPath, String sourceFile, int lineNumber) implements Statement {
    }

    /**
     * Controls NVL cumulative dialogue mode ({@code nvl show}, {@code nvl hide}, {@code nvl clear}).
     *
     * @param action     NVL action keyword
     * @param sourceFile source script path
     * @param lineNumber 1-based line number
     */
    public record NvlStatement(String action, String sourceFile, int lineNumber) implements Statement {
    }

    /**
     * Places a one-way rollback barrier ({@code block_rollback}).
     *
     * @param sourceFile source script path
     * @param lineNumber 1-based line number
     */
    public record BlockRollbackStatement(String sourceFile, int lineNumber) implements Statement {
    }

    /**
     * Prompts the player for text input stored into a state variable.
     *
     * @param targetVar    target state variable name
     * @param prompt       prompt text
     * @param defaultValue default input text
     * @param allowChars   optional allowed-character filter
     * @param maxLength    maximum input length
     * @param sourceFile   source script path
     * @param lineNumber   1-based line number
     */
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

    /**
     * Single choice option inside a {@link MenuStatement}.
     *
     * @param text              choice display label
     * @param conditionExpr     optional visibility/enabled condition
     * @param disabledWhenFalse whether failed condition disables instead of hides
     * @param body              nested statements executed on selection
     * @param inlineJumpTarget  optional inline jump target label
     * @param sourceFile        source script path
     * @param lineNumber        1-based line number
     */
    public record MenuChoice(
            String text,
            String conditionExpr,
            boolean disabledWhenFalse,
            List<Statement> body,
            String inlineJumpTarget,
            String sourceFile,
            int lineNumber
    ) {
        /** Copies body statements list defensively. */
        public MenuChoice {
            body = body != null ? List.copyOf(body) : List.of();
        }
    }

    /**
     * Interactive player choice menu ({@code menu:}).
     *
     * @param caption          optional menu caption
     * @param timeoutSeconds   optional choice timeout in seconds
     * @param timeoutJumpLabel optional label jumped to on timeout
     * @param choices          ordered choice entries
     * @param sourceFile       source script path
     * @param lineNumber       1-based line number
     */
    public record MenuStatement(
            String caption,
            Double timeoutSeconds,
            String timeoutJumpLabel,
            List<MenuChoice> choices,
            String sourceFile,
            int lineNumber
    ) implements Statement {
        /** Copies choices list defensively. */
        public MenuStatement {
            choices = choices != null ? List.copyOf(choices) : List.of();
        }
    }

    /**
     * Conditional branch ({@code if}/{@code elif}/{@code else}) inside {@link IfStatement}.
     *
     * @param conditionExpr condition expression ({@code null} for {@code else})
     * @param body          branch body statements
     */
    public record IfBranch(String conditionExpr, List<Statement> body) {
        /** Copies body statements list defensively. */
        public IfBranch {
            body = body != null ? List.copyOf(body) : List.of();
        }
    }

    /**
     * Conditional control-flow block ({@code if ... elif ... else ...}).
     *
     * @param branches   ordered conditional branches
     * @param sourceFile source script path
     * @param lineNumber 1-based line number
     */
    public record IfStatement(List<IfBranch> branches, String sourceFile, int lineNumber) implements Statement {
        /** Copies branches list defensively. */
        public IfStatement {
            branches = branches != null ? List.copyOf(branches) : List.of();
        }
    }

    /**
     * Variable assignment or default initialization ({@code $ x = ...} or {@code default x = ...}).
     *
     * @param varName       variable name
     * @param expression    value expression
     * @param isDefaultOnly {@code true} for {@code default} statements
     * @param sourceFile    source script path
     * @param lineNumber    1-based line number
     */
    public record SetVarStatement(
            String varName,
            String expression,
            boolean isDefaultOnly,
            String sourceFile,
            int lineNumber) implements Statement {
    }

    /**
     * Pauses execution for a fixed duration or until player click ({@code pause [sec]}).
     *
     * @param seconds    duration in seconds ({@code null} to wait for click)
     * @param sourceFile source script path
     * @param lineNumber 1-based line number
     */
    public record PauseStatement(Double seconds, String sourceFile, int lineNumber) implements Statement {
    }

    /**
     * Applies a standalone transition to the active stage ({@code with <transition>}).
     *
     * @param transitionExpr transition expression
     * @param sourceFile     source script path
     * @param lineNumber     1-based line number
     */
    public record WithStatement(String transitionExpr, String sourceFile, int lineNumber) implements Statement {
    }

    /**
     * Controls dialogue window visibility ({@code window show|hide|auto}).
     *
     * @param action     window action keyword
     * @param sourceFile source script path
     * @param lineNumber 1-based line number
     */
    public record WindowStatement(String action, String sourceFile, int lineNumber) implements Statement {
    }

    /**
     * Moves a sprite to an absolute or relative stage coordinate, optionally animated over time.
     *
     * @param tag             target sprite tag
     * @param targetX         target X coordinate (or relative delta X)
     * @param targetY         target Y coordinate (or relative delta Y)
     * @param relative        {@code true} for {@code move_by}, {@code false} for {@code move ... to}
     * @param durationSeconds tween duration in seconds ({@code 0} for instant placement)
     * @param easing          easing curve name ({@code linear}, {@code ease_in}, {@code ease_out}, {@code ease_in_out})
     * @param sourceFile      source script path
     * @param lineNumber      1-based line number
     */
    public record MoveStatement(
            String tag,
            double targetX,
            double targetY,
            boolean relative,
            double durationSeconds,
            String easing,
            String sourceFile,
            int lineNumber
    ) implements Statement {
    }

    /**
     * Binds interactive WASD / arrow-key movement to a sprite tag with optional speed, bounds,
     * and inertia parameters.
     *
     * @param tag               target sprite tag
     * @param speedPxPerSec     speed in logical pixels per second
     * @param minX              optional minimum X clamp (nullable)
     * @param minY              optional minimum Y clamp (nullable)
     * @param maxX              optional maximum X clamp (nullable)
     * @param maxY              optional maximum Y clamp (nullable)
     * @param normalizeDiagonal whether diagonal vectors are normalized to constant speed
     * @param friction          deceleration damping rate when input is released ({@code <= 0} disables inertia)
     * @param acceleration      acceleration responsiveness when input is held ({@code <= 0} uses instant speed)
     * @param sourceFile        source script path
     * @param lineNumber        1-based line number
     */
    public record WasdInitStatement(
            String tag,
            double speedPxPerSec,
            Double minX,
            Double minY,
            Double maxX,
            Double maxY,
            boolean normalizeDiagonal,
            double friction,
            double acceleration,
            String sourceFile,
            int lineNumber
    ) implements Statement {
        /**
         * Backwards-compatible constructor without explicit inertia parameters.
         *
         * @param tag               target sprite tag
         * @param speedPxPerSec     speed in logical pixels per second
         * @param minX              optional minimum X clamp (nullable)
         * @param minY              optional minimum Y clamp (nullable)
         * @param maxX              optional maximum X clamp (nullable)
         * @param maxY              optional maximum Y clamp (nullable)
         * @param normalizeDiagonal whether diagonal vectors are normalized to constant speed
         * @param sourceFile        source script path
         * @param lineNumber        1-based line number
         */
        public WasdInitStatement(
                String tag,
                double speedPxPerSec,
                Double minX,
                Double minY,
                Double maxX,
                Double maxY,
                boolean normalizeDiagonal,
                String sourceFile,
                int lineNumber
        ) {
            this(tag, speedPxPerSec, minX, minY, maxX, maxY, normalizeDiagonal, 0.0, 0.0, sourceFile, lineNumber);
        }
    }

    /**
     * Stops WASD control for a specific sprite tag or all sprites ({@code tag == null}).
     *
     * @param tag        optional sprite tag (nullable)
     * @param sourceFile source script path
     * @param lineNumber 1-based line number
     */
    public record WasdStopStatement(String tag, String sourceFile, int lineNumber) implements Statement {
    }

    /**
     * Enables 2D vertical gravity and jump physics for a sprite tag.
     *
     * @param tag               target sprite tag
     * @param gravityPxPerSec2  downward gravity acceleration
     * @param jumpForcePxPerSec upward jump velocity
     * @param groundY           ground plane Y coordinate
     * @param sourceFile        source script path
     * @param lineNumber        1-based line number
     */
    public record PhysicsInitStatement(
            String tag,
            double gravityPxPerSec2,
            double jumpForcePxPerSec,
            double groundY,
            String sourceFile,
            int lineNumber
    ) implements Statement {
    }

    /**
     * Disables 2D physics for a specific sprite tag or all sprites ({@code tag == null}).
     *
     * @param tag        optional sprite tag (nullable)
     * @param sourceFile source script path
     * @param lineNumber 1-based line number
     */
    public record PhysicsStopStatement(String tag, String sourceFile, int lineNumber) implements Statement {
    }

    /**
     * Registers an AABB collision trigger between two sprites that jumps to {@code targetLabel}.
     *
     * @param tagA        first sprite tag
     * @param tagB        second sprite tag
     * @param targetLabel label to jump to on overlap
     * @param sourceFile  source script path
     * @param lineNumber  1-based line number
     */
    public record OnCollideStatement(
            String tagA,
            String tagB,
            String targetLabel,
            String sourceFile,
            int lineNumber
    ) implements Statement {
    }

    /**
     * Single {@code case <pattern>:} arm inside a {@link MatchStatement}.
     *
     * @param patternExpr pattern expression compared for equality against the match subject
     * @param body        arm body statements
     * @param sourceFile  source script path
     * @param lineNumber  1-based line number
     */
    public record MatchCase(
            String patternExpr,
            List<Statement> body,
            String sourceFile,
            int lineNumber
    ) {
        /** Copies case body statements defensively. */
        public MatchCase {
            body = body != null ? List.copyOf(body) : List.of();
        }
    }

    /**
     * Pattern-matching control-flow statement ({@code match <expr>:}).
     *
     * @param subjectExpr expression evaluated once and matched against each case
     * @param cases       ordered case arms
     * @param defaultBody optional default branch statements ({@code null} when {@code default:} is omitted)
     * @param sourceFile  source script path
     * @param lineNumber  1-based line number
     */
    public record MatchStatement(
            String subjectExpr,
            List<MatchCase> cases,
            List<Statement> defaultBody,
            String sourceFile,
            int lineNumber
    ) implements Statement {
        /** Copies case arms and optional default body defensively. */
        public MatchStatement {
            cases = cases != null ? List.copyOf(cases) : List.of();
            defaultBody = defaultBody != null ? List.copyOf(defaultBody) : null;
        }
    }

    /**
     * Cubic Bezier path tween statement ({@code move_path <tag> x1 y1 x2 y2 x3 y3 [duration <d>] [ease <e>]}).
     *
     * @param tag             target sprite tag
     * @param x1              first control point X
     * @param y1              first control point Y
     * @param x2              second control point X
     * @param y2              second control point Y
     * @param x3              end point X
     * @param y3              end point Y
     * @param durationSeconds duration in seconds
     * @param easing          easing curve name
     * @param sourceFile      source script path
     * @param lineNumber      1-based line number
     */
    public record MovePathStatement(
            String tag,
            double x1,
            double y1,
            double x2,
            double y2,
            double x3,
            double y3,
            double durationSeconds,
            String easing,
            String sourceFile,
            int lineNumber
    ) implements Statement {
    }

    /**
     * Shortest-arc sprite rotation tween ({@code rotate <tag> [to] <deg> [duration <d>] [ease <e>]}).
     *
     * @param tag             target sprite tag
     * @param targetDegrees   target rotation angle in degrees
     * @param durationSeconds duration in seconds
     * @param easing          easing curve name
     * @param sourceFile      source script path
     * @param lineNumber      1-based line number
     */
    public record RotateStatement(
            String tag,
            double targetDegrees,
            double durationSeconds,
            String easing,
            String sourceFile,
            int lineNumber
    ) implements Statement {
    }

    /**
     * Sprite non-uniform scale tween ({@code scale <tag> [to] <zx> [<zy>] [duration <d>] [ease <e>]}).
     *
     * @param tag             target sprite tag
     * @param targetXZoom     target horizontal zoom factor
     * @param targetYZoom     target vertical zoom factor
     * @param durationSeconds duration in seconds
     * @param easing          easing curve name
     * @param sourceFile      source script path
     * @param lineNumber      1-based line number
     */
    public record ScaleStatement(
            String tag,
            double targetXZoom,
            double targetYZoom,
            double durationSeconds,
            String easing,
            String sourceFile,
            int lineNumber
    ) implements Statement {
    }

    /**
     * Assigns a collision layer and accepted target layers to a sprite tag.
     *
     * @param tag          target sprite tag
     * @param layer        layer name this sprite belongs to
     * @param collidesWith list of layer names this sprite collides with
     * @param sourceFile   source script path
     * @param lineNumber   1-based line number
     */
    public record CollisionMaskStatement(
            String tag,
            String layer,
            List<String> collidesWith,
            String sourceFile,
            int lineNumber
    ) implements Statement {
        /** Copies mask target layers defensively. */
        public CollisionMaskStatement {
            collidesWith = collidesWith != null ? List.copyOf(collidesWith) : List.of();
        }
    }

    /**
     * Configures an explicit circular or rectangular collision hitbox for a sprite tag.
     *
     * @param tag        target sprite tag
     * @param shape      hitbox shape ({@code "circle"} or {@code "rect"})
     * @param param1     radius for circle or width for rect
     * @param param2     unused for circle ({@code 0.0}) or height for rect
     * @param sourceFile source script path
     * @param lineNumber 1-based line number
     */
    public record HitboxStatement(
            String tag,
            String shape,
            double param1,
            double param2,
            String sourceFile,
            int lineNumber
    ) implements Statement {
    }

    /**
     * Registers an overlap phase callback ({@code enter}, {@code stay}, {@code exit}) between two sprites.
     *
     * @param tagA        first sprite tag
     * @param tagB        second sprite tag
     * @param phase       overlap phase ({@code "enter"}, {@code "stay"}, or {@code "exit"})
     * @param targetLabel subroutine label to invoke
     * @param sourceFile  source script path
     * @param lineNumber  1-based line number
     */
    public record OnOverlapStatement(
            String tagA,
            String tagB,
            String phase,
            String targetLabel,
            String sourceFile,
            int lineNumber
    ) implements Statement {
    }

    /**
     * Marks a sprite tag as a solid or one-way collision platform for physics bodies.
     *
     * @param tag        target platform sprite tag
     * @param oneWay     {@code true} if upward jumps may pass through the platform from below
     * @param sourceFile source script path
     * @param lineNumber 1-based line number
     */
    public record SolidPlatformStatement(
            String tag,
            boolean oneWay,
            String sourceFile,
            int lineNumber
    ) implements Statement {
    }

    /**
     * Executes nested statements concurrently in the same frame ({@code parallel:}).
     *
     * @param statements nested statements launched simultaneously
     * @param sourceFile source script path
     * @param lineNumber 1-based line number
     */
    public record ParallelStatement(
            List<Statement> statements,
            String sourceFile,
            int lineNumber
    ) implements Statement {
        /** Copies nested statements list defensively. */
        public ParallelStatement {
            statements = statements != null ? List.copyOf(statements) : List.of();
        }
    }

    /**
     * Pauses script progression until all active motion tweens for {@code tag} complete.
     *
     * @param tag        target sprite tag
     * @param sourceFile source script path
     * @param lineNumber 1-based line number
     */
    public record WaitMoveStatement(
            String tag,
            String sourceFile,
            int lineNumber
    ) implements Statement {
    }

    /**
     * Enables or disables smooth camera follow tracking on the stage.
     *
     * @param targetTag    sprite tag to follow, or {@code null} to stop following
     * @param smoothFactor smoothing rate ({@code >= 0})
     * @param sourceFile   source script path
     * @param lineNumber   1-based line number
     */
    public record CameraFollowStatement(
            String targetTag,
            double smoothFactor,
            String sourceFile,
            int lineNumber
    ) implements Statement {
    }

    /**
     * Defines an invisible rectangular trigger zone invoking or jumping to a label upon entry.
     *
     * @param zoneId      unique trigger zone identifier
     * @param x           zone left X coordinate
     * @param y           zone top Y coordinate
     * @param width       zone width
     * @param height      zone height
     * @param action      trigger action ({@code "call"} or {@code "jump"})
     * @param targetLabel target label name
     * @param sourceFile  source script path
     * @param lineNumber  1-based line number
     */
    public record TriggerZoneStatement(
            String zoneId,
            double x,
            double y,
            double width,
            double height,
            String action,
            String targetLabel,
            String sourceFile,
            int lineNumber
    ) implements Statement {
    }

    /**
     * Single parsed {@code .vjava} source unit prior to global project merging.
     *
     * @param filePath           source file path
     * @param characters         character definitions in this file
     * @param images             standalone image definitions
     * @param layeredImages      layered image definitions
     * @param transforms         ATL transform definitions
     * @param labels             label definitions
     * @param screens            UI screen definitions
     * @param translations       translation blocks
     * @param topLevelJavaBlocks top-level embedded Java blocks
     * @param defaultVariables   default variable declarations
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
        /** Copies all AST collection lists defensively. */
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
     *
     * @param projectRoot        normalized project root directory
     * @param descriptor         loaded project descriptor
     * @param scriptFiles        scanned script file paths
     * @param characters         merged character definitions keyed by ID
     * @param images             merged image definitions keyed by full key
     * @param layeredImages      merged layered image definitions keyed by name
     * @param transforms         merged transform definitions keyed by name
     * @param labels             merged label definitions keyed by name
     * @param screens            merged screen definitions keyed by name
     * @param translations       all translation blocks
     * @param topLevelJavaBlocks all top-level embedded Java blocks
     * @param defaultVariables   all default variable declarations
     * @param discoveredAssets   project-relative paths of discovered asset files
     * @param warnings           non-fatal scanner warnings
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
        /** Defensively copies all AST collections and maps. */
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
