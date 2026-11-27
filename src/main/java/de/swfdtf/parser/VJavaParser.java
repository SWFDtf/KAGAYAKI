package de.swfdtf.parser;

import de.swfdtf.parser.VJavaAst.AfterTimerStatement;
import de.swfdtf.parser.VJavaAst.AlwaysLayerEntry;
import de.swfdtf.parser.VJavaAst.AnimStateStatement;
import de.swfdtf.parser.VJavaAst.AtlStep;
import de.swfdtf.parser.VJavaAst.AttachStatement;
import de.swfdtf.parser.VJavaAst.AttractStatement;
import de.swfdtf.parser.VJavaAst.AttributeEntry;
import de.swfdtf.parser.VJavaAst.BlockRollbackStatement;
import de.swfdtf.parser.VJavaAst.CallStatement;
import de.swfdtf.parser.VJavaAst.Camera3DStatement;
import de.swfdtf.parser.VJavaAst.CameraClampStatement;
import de.swfdtf.parser.VJavaAst.CameraFollowStatement;
import de.swfdtf.parser.VJavaAst.CharacterDef;
import de.swfdtf.parser.VJavaAst.ClimbZoneStatement;
import de.swfdtf.parser.VJavaAst.ColliderBox;
import de.swfdtf.parser.VJavaAst.ColliderWallStatement;
import de.swfdtf.parser.VJavaAst.CollisionMaskStatement;
import de.swfdtf.parser.VJavaAst.ConditionalLayerEntry;
import de.swfdtf.parser.VJavaAst.DespawnStatement;
import de.swfdtf.parser.VJavaAst.Fog3DStatement;
import de.swfdtf.parser.VJavaAst.FollowStatement;
import de.swfdtf.parser.VJavaAst.GroupLayerEntry;
import de.swfdtf.parser.VJavaAst.HideStatement;
import de.swfdtf.parser.VJavaAst.HitboxStatement;
import de.swfdtf.parser.VJavaAst.IfBranch;
import de.swfdtf.parser.VJavaAst.IfStatement;
import de.swfdtf.parser.VJavaAst.ImageDef;
import de.swfdtf.parser.VJavaAst.ImpulseStatement;
import de.swfdtf.parser.VJavaAst.InputStatement;
import de.swfdtf.parser.VJavaAst.JavaBlockNode;
import de.swfdtf.parser.VJavaAst.JavaBlockStatement;
import de.swfdtf.parser.VJavaAst.JumpStatement;
import de.swfdtf.parser.VJavaAst.KnockbackStatement;
import de.swfdtf.parser.VJavaAst.LabelNode;
import de.swfdtf.parser.VJavaAst.LayeredEntry;
import de.swfdtf.parser.VJavaAst.LayeredImageDef;
import de.swfdtf.parser.VJavaAst.LocalVarStatement;
import de.swfdtf.parser.VJavaAst.MatchCase;
import de.swfdtf.parser.VJavaAst.MatchStatement;
import de.swfdtf.parser.VJavaAst.Material3DStatement;
import de.swfdtf.parser.VJavaAst.MenuChoice;
import de.swfdtf.parser.VJavaAst.MenuStatement;
import de.swfdtf.parser.VJavaAst.MovePathStatement;
import de.swfdtf.parser.VJavaAst.MoveStatement;
import de.swfdtf.parser.VJavaAst.MovingPlatformStatement;
import de.swfdtf.parser.VJavaAst.NvlStatement;
import de.swfdtf.parser.VJavaAst.OnCollideStatement;
import de.swfdtf.parser.VJavaAst.OnEventStatement;
import de.swfdtf.parser.VJavaAst.OnInteractStatement;
import de.swfdtf.parser.VJavaAst.OnOverlapStatement;
import de.swfdtf.parser.VJavaAst.OnTweenEndStatement;
import de.swfdtf.parser.VJavaAst.ParallelStatement;
import de.swfdtf.parser.VJavaAst.PatrolStatement;
import de.swfdtf.parser.VJavaAst.PauseStatement;
import de.swfdtf.parser.VJavaAst.PhysicsInitStatement;
import de.swfdtf.parser.VJavaAst.PhysicsStopStatement;
import de.swfdtf.parser.VJavaAst.PlayStatement;
import de.swfdtf.parser.VJavaAst.PointClickInitStatement;
import de.swfdtf.parser.VJavaAst.QueueStatement;
import de.swfdtf.parser.VJavaAst.RandomBranch;
import de.swfdtf.parser.VJavaAst.RandomStatement;
import de.swfdtf.parser.VJavaAst.RepeatStatement;
import de.swfdtf.parser.VJavaAst.ReturnStatement;
import de.swfdtf.parser.VJavaAst.RotateStatement;
import de.swfdtf.parser.VJavaAst.SayStatement;
import de.swfdtf.parser.VJavaAst.ScaleStatement;
import de.swfdtf.parser.VJavaAst.Scene3DStatement;
import de.swfdtf.parser.VJavaAst.SceneStatement;
import de.swfdtf.parser.VJavaAst.ScreenDef;
import de.swfdtf.parser.VJavaAst.ScreenItem;
import de.swfdtf.parser.VJavaAst.ScriptFileAst;
import de.swfdtf.parser.VJavaAst.SetBlockStatement;
import de.swfdtf.parser.VJavaAst.SetVarStatement;
import de.swfdtf.parser.VJavaAst.ShakeLayerStatement;
import de.swfdtf.parser.VJavaAst.ShowStatement;
import de.swfdtf.parser.VJavaAst.SolidPlatformStatement;
import de.swfdtf.parser.VJavaAst.Spawn3DStatement;
import de.swfdtf.parser.VJavaAst.SpawnCutsceneStatement;
import de.swfdtf.parser.VJavaAst.SpawnStatement;
import de.swfdtf.parser.VJavaAst.Statement;
import de.swfdtf.parser.VJavaAst.StopStatement;
import de.swfdtf.parser.VJavaAst.SurfaceFrictionStatement;
import de.swfdtf.parser.VJavaAst.TimeScaleStatement;
import de.swfdtf.parser.VJavaAst.TransformDef;
import de.swfdtf.parser.VJavaAst.TranslateBlock;
import de.swfdtf.parser.VJavaAst.TranslateEntry;
import de.swfdtf.parser.VJavaAst.TriggerZoneStatement;
import de.swfdtf.parser.VJavaAst.VoiceStatement;
import de.swfdtf.parser.VJavaAst.WaitMoveStatement;
import de.swfdtf.parser.VJavaAst.Wasd3DInitStatement;
import de.swfdtf.parser.VJavaAst.WasdInitStatement;
import de.swfdtf.parser.VJavaAst.WasdStopStatement;
import de.swfdtf.parser.VJavaAst.WhileStatement;
import de.swfdtf.parser.VJavaAst.WindowStatement;
import de.swfdtf.parser.VJavaAst.WithStatement;
import de.swfdtf.parser.VJavaAst.YSortStatement;
import de.swfdtf.parser.VJavaLexer.Token;
import de.swfdtf.parser.VJavaLexer.TokenType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Recursive-descent parser converting {@link VJavaLexer.Token} streams into {@link VJavaAst.ScriptFileAst}.
 *
 * <p>Supports all KAGAYAKI {@code .vjava} top-level declarations ({@code define}, {@code default},
 * {@code $}, {@code image}, {@code layeredimage}, {@code transform}, {@code screen}, {@code translate},
 * {@code label}, and native {@code @->{ ... }<<} blocks) as well as full label statement trees.</p>
 */
public final class VJavaParser {

    private static final Set<String> ATL_WARPERS = Set.of(
            "linear", "ease", "easein", "easeout", "easeinout",
            "ease_in", "ease_out", "ease_in_out", "smoothstep",
            "ease_back", "ease_bounce", "ease_elastic"
    );

    private static final Set<String> ATL_BLOCK_CONTAINERS = Set.of(
            "parallel", "block", "choice", "contains"
    );

    private static final Set<String> SCREEN_WIDGET_KEYWORDS = Set.of(
            "vbox", "hbox", "grid", "gridbox", "fixed", "frame", "window", "viewport", "vpgrid",
            "textbutton", "imagebutton", "text", "label", "bar", "vbar", "slider",
            "input", "selectbox", "checkbox", "toggleswitch", "tabpanel", "tab",
            "hotspot", "hotbar", "imagemap", "draggroup", "drag",
            "key", "timer", "custom", "add", "null", "button", "side", "mousearea",
            "use", "on", "if", "elif", "else", "for"
    );

    private static final Set<String> SCREEN_POSITIONAL_WIDGETS = Set.of(
            "text", "textbutton", "label", "key", "timer", "custom",
            "add", "use", "hotspot", "hotbar", "side", "tab"
    );

    private static final int MAX_BLOCK_DEPTH = 64;

    private final Path filePath;
    private final String sourceFile;
    private final List<Token> tokens;
    private int cursor;
    private int javaBlockSequence;
    private int blockDepth;

    private VJavaParser(Path filePath, String sourceFile, List<Token> tokens) {
        this.filePath = filePath;
        this.sourceFile = sourceFile;
        this.tokens = tokens;
        this.cursor = 0;
        this.javaBlockSequence = 0;
    }

    /**
     * Reads, lexes, and parses a {@code .vjava} file from disk.
     *
     * @param filePath path to the {@code .vjava} file
     * @return parsed {@link ScriptFileAst}
     * @throws IOException         if reading the file fails
     * @throws VJavaParseException if lexing or parsing fails
     */
    public static ScriptFileAst parse(Path filePath) throws IOException {
        Objects.requireNonNull(filePath, "filePath must not be null");
        String raw = Files.readString(filePath, StandardCharsets.UTF_8);
        String preprocessed = MacroRegistry.preprocessSource(raw);
        String sf = filePath.toString().replace('\\', '/');
        List<Token> tokens = VJavaLexer.tokenize(preprocessed, sf);
        return parse(filePath, tokens);
    }

    /**
     * Lexes and parses raw {@code .vjava} source text for the given logical path.
     *
     * @param filePath path representing the script file
     * @param source   raw {@code .vjava} script text
     * @return parsed {@link ScriptFileAst}
     * @throws VJavaParseException if lexing or parsing fails
     */
    public static ScriptFileAst parse(Path filePath, String source) {
        Objects.requireNonNull(filePath, "filePath must not be null");
        Objects.requireNonNull(source, "source must not be null");
        String sf = filePath.toString().replace('\\', '/');
        String preprocessed = MacroRegistry.preprocessSource(source);
        List<Token> tokens = VJavaLexer.tokenize(preprocessed, sf);
        return parse(filePath, tokens);
    }

    /**
     * Parses a token list produced by {@link VJavaLexer}, inferring the file path from the tokens.
     *
     * @param tokens list of lexer tokens
     * @return parsed {@link ScriptFileAst}
     * @throws VJavaParseException if syntax rules are violated
     */
    public static ScriptFileAst parse(List<Token> tokens) {
        Objects.requireNonNull(tokens, "tokens must not be null");
        String sf = tokens.isEmpty() ? "script.vjava" : tokens.get(0).sourceFile();
        Path inferredPath = Path.of(sf.equals("<memory>") || sf.equals("<unknown>") ? "script.vjava" : sf);
        return parse(inferredPath, tokens);
    }

    /**
     * Parses a token list produced by {@link VJavaLexer} into a {@link ScriptFileAst}.
     *
     * @param filePath file path recorded on the resulting {@link ScriptFileAst}
     * @param tokens   token stream emitted by {@link VJavaLexer}
     * @return parsed {@link ScriptFileAst}
     * @throws VJavaParseException if syntax rules are violated
     */
    public static ScriptFileAst parse(Path filePath, List<Token> tokens) {
        Objects.requireNonNull(filePath, "filePath must not be null");
        Objects.requireNonNull(tokens, "tokens must not be null");
        String sf = tokens.isEmpty()
                ? filePath.toString().replace('\\', '/')
                : tokens.get(0).sourceFile();
        return new VJavaParser(filePath, sf, tokens).parseScriptFile();
    }

    private ScriptFileAst parseScriptFile() {
        List<CharacterDef> characters = new ArrayList<>();
        List<ImageDef> images = new ArrayList<>();
        List<LayeredImageDef> layeredImages = new ArrayList<>();
        List<TransformDef> transforms = new ArrayList<>();
        List<LabelNode> labels = new ArrayList<>();
        List<ScreenDef> screens = new ArrayList<>();
        List<TranslateBlock> translations = new ArrayList<>();
        List<JavaBlockNode> topLevelJavaBlocks = new ArrayList<>();
        List<SetVarStatement> defaultVariables = new ArrayList<>();
        Map<String, String> configValues = new LinkedHashMap<>();
        List<String> includes = new ArrayList<>();

        while (!isAtEnd()) {
            Token token = peek();
            switch (token.type()) {
                case EOF -> {
                    cursor = tokens.size();
                }
                case JAVA_BLOCK -> {
                    consume();
                    topLevelJavaBlocks.add(toJavaBlockNode(token));
                }
                case LINE -> {
                    consume();
                    String text = token.text();
                    if (text.startsWith("$")) {
                        defaultVariables.add(parseSetVar(text, false, token));
                    } else {
                        String keyword = firstWord(text);
                        switch (keyword) {
                            case "define" -> parseDefine(token, characters, defaultVariables);
                            case "default" -> defaultVariables.add(parseDefaultVar(token));
                            case "config" -> configValues.putAll(parseConfigBlock(token));
                            case "include" -> includes.add(parseIncludeDirective(token));
                            case "image" -> images.add(parseImageDef(token));
                            case "layeredimage" -> layeredImages.add(parseLayeredImageDef(token));
                            case "transform" -> transforms.add(parseTransformDef(token));
                            case "screen" -> screens.add(parseScreenDef(token));
                            case "translate" -> translations.add(parseTranslateBlock(token));
                            case "label" -> labels.add(parseLabelNode(token));
                            default -> throw error(token, "Unexpected top-level declaration: '" + text + "'");
                        }
                    }
                }
                case INDENT -> throw error(token, "Unexpected indentation at top level");
                case DEDENT -> {
                    // Should not happen at top level, treat defensively as error
                    throw error(token, "Unexpected dedent at top level");
                }
            }
        }

        return new ScriptFileAst(
                filePath,
                characters,
                images,
                layeredImages,
                transforms,
                labels,
                screens,
                translations,
                topLevelJavaBlocks,
                defaultVariables,
                configValues,
                includes
        );
    }

    private Map<String, String> parseConfigBlock(Token token) {
        String headerRest = stripRequiredColon(stripKeywordSafe(token.text(), "config"), token);
        if (!headerRest.isEmpty()) {
            throw error(token, "Unexpected header text after 'config:': " + headerRest);
        }
        Map<String, String> entries = new LinkedHashMap<>();
        if (consumeOptionalIndent()) {
            while (!isAtEnd() && peek().type() != TokenType.DEDENT) {
                Token lineTok = expectLine("config block");
                String line = lineTok.text();
                int eqIdx = findCharOutsideQuotesAndBrackets(line, '=');
                String key;
                String val;
                if (eqIdx > 0) {
                    key = line.substring(0, eqIdx).trim();
                    val = unquoteIfSingleStringLiteral(line.substring(eqIdx + 1).trim(), lineTok);
                } else {
                    List<String> parts = splitWhitespaceTopLevel(line);
                    if (parts.size() < 2) {
                        throw error(lineTok, "Expected '<key> = <value>' in config block: " + line);
                    }
                    key = parts.get(0);
                    val = unquoteIfSingleStringLiteral(
                            String.join(" ", parts.subList(1, parts.size())), lineTok);
                }
                entries.put(key, val);
            }
            consumeRequiredDedent(token);
        }
        return entries;
    }

    private String parseIncludeDirective(Token token) {
        String rest = stripKeyword(token.text(), "include", token);
        String target = unquoteIfSingleStringLiteral(rest, token);
        if (target.isBlank()) {
            throw error(token, "Expected script path after 'include'");
        }
        return target.replace('\\', '/');
    }

    // ---- Top-Level Declaration Parsers ----

    private void parseDefine(
            Token token,
            List<CharacterDef> characters,
            List<SetVarStatement> defaultVariables
    ) {
        String rest = stripKeyword(token.text(), "define", token);
        int eqIdx = findCharOutsideQuotesAndBrackets(rest, '=');
        if (eqIdx <= 0) {
            throw error(token, "Expected '=' in define declaration: " + token.text());
        }
        String varName = rest.substring(0, eqIdx).trim();
        String rhs = rest.substring(eqIdx + 1).trim();
        if (varName.isEmpty() || rhs.isEmpty()) {
            throw error(token, "Malformed define declaration: " + token.text());
        }

        if (rhs.startsWith("Character(") && rhs.endsWith(")")) {
            characters.add(parseCharacterDef(varName, rhs, token));
        } else {
            defaultVariables.add(new SetVarStatement(varName, rhs, false, token.sourceFile(), token.lineNumber()));
        }
    }

    private CharacterDef parseCharacterDef(String id, String characterCall, Token token) {
        String inside = characterCall.substring("Character(".length(), characterCall.length() - 1).trim();
        String displayName = null;
        String colorHex = null;
        String outlineColorHex = null;
        String imageTag = null;
        String voiceTag = null;
        String textboxSkin = null;
        String voiceBleepSound = null;
        Map<String, String> properties = new LinkedHashMap<>();

        if (!inside.isEmpty()) {
            List<String> args = splitTopLevelComma(inside);
            boolean positionalHandled = false;
            for (String rawArg : args) {
                if (rawArg.isEmpty()) {
                    continue;
                }
                int eqIdx = findCharOutsideQuotesAndBrackets(rawArg, '=');
                if (eqIdx > 0) {
                    String key = rawArg.substring(0, eqIdx).trim();
                    String rawVal = rawArg.substring(eqIdx + 1).trim();
                    String val = unquoteIfSingleStringLiteral(rawVal, token);
                    properties.put(key, val);
                    switch (key) {
                        case "name" -> displayName = val;
                        case "color", "who_color" -> colorHex = val;
                        case "outline_color", "who_outline_color" -> outlineColorHex = val;
                        case "image" -> imageTag = val;
                        case "voice_tag" -> voiceTag = val;
                        case "textbox" -> textboxSkin = val;
                        case "voice_bleep" -> voiceBleepSound = val;
                        default -> {
                            // Arbitrary keyword property already recorded in properties map
                        }
                    }
                } else if (!positionalHandled) {
                    positionalHandled = true;
                    if ("None".equals(rawArg) || "null".equals(rawArg)) {
                        displayName = "";
                    } else {
                        displayName = unquoteIfSingleStringLiteral(rawArg, token);
                    }
                } else {
                    throw error(token, "Unexpected extra positional argument in Character(): " + rawArg);
                }
            }
        }

        return new CharacterDef(
                id,
                displayName,
                colorHex,
                outlineColorHex,
                imageTag,
                voiceTag,
                textboxSkin,
                voiceBleepSound,
                properties,
                token.sourceFile(),
                token.lineNumber()
        );
    }

    private SetVarStatement parseDefaultVar(Token token) {
        String rest = stripKeyword(token.text(), "default", token);
        return parseAssignmentExpression(rest, true, token);
    }

    private SetVarStatement parseSetVar(String lineText, boolean isDefaultOnly, Token token) {
        String rest = lineText.substring(1).trim();
        if (rest.isEmpty()) {
            throw error(token, "Empty '$' variable assignment");
        }
        return parseAssignmentExpression(rest, isDefaultOnly, token);
    }

    private SetVarStatement parseAssignmentExpression(String exprText, boolean isDefaultOnly, Token token) {
        // Support +=, -=, *=, /= as well as =
        for (String compoundOp : List.of("+=", "-=", "*=", "/=")) {
            int idx = findSubstringOutsideQuotesAndBrackets(exprText, compoundOp);
            if (idx > 0) {
                String varName = exprText.substring(0, idx).trim();
                String rhs = exprText.substring(idx + compoundOp.length()).trim();
                if (varName.isEmpty() || rhs.isEmpty()) {
                    throw error(token, "Invalid compound assignment: " + exprText);
                }
                char op = compoundOp.charAt(0);
                return new SetVarStatement(
                        varName,
                        varName + " " + op + " " + rhs,
                        isDefaultOnly,
                        token.sourceFile(),
                        token.lineNumber()
                );
            }
        }

        int eqIdx = findAssignmentEquals(exprText);
        if (eqIdx <= 0) {
            throw error(token, "Expected '=' in variable assignment: " + exprText);
        }
        String varName = exprText.substring(0, eqIdx).trim();
        String rhs = exprText.substring(eqIdx + 1).trim();
        if (varName.isEmpty() || rhs.isEmpty()) {
            throw error(token, "Invalid variable assignment: " + exprText);
        }
        return new SetVarStatement(varName, rhs, isDefaultOnly, token.sourceFile(), token.lineNumber());
    }

    private ImageDef parseImageDef(Token token) {
        String rest = stripKeyword(token.text(), "image", token);
        int eqIdx = findCharOutsideQuotesAndBrackets(rest, '=');
        if (eqIdx <= 0) {
            throw error(token, "Expected '=' in image declaration: " + token.text());
        }
        String lhs = rest.substring(0, eqIdx).trim();
        String rhs = rest.substring(eqIdx + 1).trim();
        List<String> tagParts = splitWhitespaceTopLevel(lhs);
        if (tagParts.isEmpty() || rhs.isEmpty()) {
            throw error(token, "Malformed image declaration: " + token.text());
        }
        String tag = tagParts.get(0);
        List<String> attributes = tagParts.size() > 1 ? tagParts.subList(1, tagParts.size()) : List.of();
        String assetPath = unquoteIfSingleStringLiteral(rhs, token);
        return new ImageDef(tag, attributes, assetPath, token.sourceFile(), token.lineNumber());
    }

    private LayeredImageDef parseLayeredImageDef(Token token) {
        String header = stripRequiredColon(stripKeyword(token.text(), "layeredimage", token), token);
        if (header.isEmpty()) {
            throw error(token, "Expected name after 'layeredimage'");
        }
        List<LayeredEntry> entries = new ArrayList<>();
        if (consumeOptionalIndent()) {
            while (!isAtEnd() && peek().type() != TokenType.DEDENT) {
                Token lineTok = expectLine("layeredimage body");
                String line = lineTok.text();
                String kw = firstWord(line);
                switch (kw) {
                    case "always" -> parseLayeredAlways(lineTok, entries);
                    case "group" -> entries.add(parseLayeredGroup(lineTok));
                    case "if" -> entries.add(parseLayeredConditional(lineTok, "if"));
                    case "elif" -> entries.add(parseLayeredConditional(lineTok, "elif"));
                    case "else" -> entries.add(parseLayeredElse(lineTok));
                    default -> throw error(lineTok, "Unexpected entry in layeredimage: " + line);
                }
            }
            consumeRequiredDedent(token);
        }
        return new LayeredImageDef(header, entries, token.sourceFile(), token.lineNumber());
    }

    private void parseLayeredAlways(Token lineTok, List<LayeredEntry> entries) {
        String text = lineTok.text();
        if (endsWithColonOutsideQuotes(text)) {
            expectIndent(lineTok);
            while (!isAtEnd() && peek().type() != TokenType.DEDENT) {
                Token sub = expectLine("always block");
                String subKw = firstWord(sub.text());
                if ("if".equals(subKw)) {
                    entries.add(parseLayeredConditional(sub, "if"));
                } else {
                    entries.add(new AlwaysLayerEntry(unquoteIfSingleStringLiteral(sub.text(), sub)));
                }
            }
            consumeRequiredDedent(lineTok);
        } else {
            String assetOrExpr = stripKeyword(text, "always", lineTok);
            entries.add(new AlwaysLayerEntry(unquoteIfSingleStringLiteral(assetOrExpr, lineTok)));
        }
    }

    private GroupLayerEntry parseLayeredGroup(Token lineTok) {
        String groupName = stripRequiredColon(stripKeyword(lineTok.text(), "group", lineTok), lineTok);
        if (groupName.isEmpty()) {
            throw error(lineTok, "Expected group name after 'group'");
        }
        List<AttributeEntry> attributes = new ArrayList<>();
        if (consumeOptionalIndent()) {
            while (!isAtEnd() && peek().type() != TokenType.DEDENT) {
                Token attrTok = expectLine("layeredimage group");
                if (!"attribute".equals(firstWord(attrTok.text()))) {
                    throw error(attrTok, "Expected 'attribute' inside layeredimage group, found: " + attrTok.text());
                }
                attributes.add(parseLayeredAttribute(attrTok));
            }
            consumeRequiredDedent(lineTok);
        }
        return new GroupLayerEntry(groupName, attributes);
    }

    private AttributeEntry parseLayeredAttribute(Token attrTok) {
        String raw = attrTok.text();
        boolean hasBlock = endsWithColonOutsideQuotes(raw);
        String bodyHeader = hasBlock ? raw.substring(0, raw.length() - 1).trim() : raw;
        String rest = stripKeyword(bodyHeader, "attribute", attrTok);

        String inlineAsset = null;
        if (!hasBlock) {
            // Check inline expression at the end if quoted string or known trailing token
            List<String> parts = splitWhitespaceTopLevel(rest);
            if (parts.size() >= 2 && isQuotedLiteral(parts.get(parts.size() - 1))) {
                inlineAsset = unquoteIfSingleStringLiteral(parts.get(parts.size() - 1), attrTok);
                rest = String.join(" ", parts.subList(0, parts.size() - 1));
            }
        }

        // Parse <name> [default] [if <cond>]
        String condExpr = null;
        int ifIdx = findKeywordOutsideQuotesAndBrackets(rest, "if");
        if (ifIdx >= 0) {
            String afterIf = rest.substring(ifIdx + 2).trim();
            rest = rest.substring(0, ifIdx).trim();
            // Handle edge case where 'default' trails after condition
            if (afterIf.endsWith(" default")) {
                condExpr = afterIf.substring(0, afterIf.length() - " default".length()).trim();
                rest = rest + " default";
            } else {
                condExpr = afterIf;
            }
            if (condExpr.isEmpty()) {
                throw error(attrTok, "Expected condition expression after 'if' in attribute");
            }
        }

        List<String> headerParts = splitWhitespaceTopLevel(rest);
        if (headerParts.isEmpty()) {
            throw error(attrTok, "Expected attribute name in layeredimage attribute");
        }
        String attrName = headerParts.get(0);
        boolean isDefault = headerParts.size() > 1 && headerParts.subList(1, headerParts.size()).contains("default");

        String assetOrExpr = inlineAsset;
        if (hasBlock) {
            expectIndent(attrTok);
            while (!isAtEnd() && peek().type() != TokenType.DEDENT) {
                Token valTok = expectLine("attribute body");
                if (assetOrExpr == null) {
                    assetOrExpr = unquoteIfSingleStringLiteral(valTok.text(), valTok);
                }
            }
            consumeRequiredDedent(attrTok);
        }
        if (assetOrExpr == null) {
            assetOrExpr = "";
        }
        return new AttributeEntry(attrName, isDefault, assetOrExpr, condExpr);
    }

    private ConditionalLayerEntry parseLayeredConditional(Token lineTok, String keyword) {
        String cond = stripRequiredColon(stripKeyword(lineTok.text(), keyword, lineTok), lineTok);
        if (cond.isEmpty()) {
            throw error(lineTok, "Expected condition expression after '" + keyword + "'");
        }
        String assetOrExpr = readSingleBlockExpression(lineTok);
        return new ConditionalLayerEntry(cond, assetOrExpr);
    }

    private ConditionalLayerEntry parseLayeredElse(Token lineTok) {
        String header = stripRequiredColon(stripKeyword(lineTok.text(), "else", lineTok), lineTok);
        if (!header.isEmpty()) {
            throw error(lineTok, "Unexpected condition after 'else:'");
        }
        String assetOrExpr = readSingleBlockExpression(lineTok);
        return new ConditionalLayerEntry("true", assetOrExpr);
    }

    private String readSingleBlockExpression(Token parentToken) {
        expectIndent(parentToken);
        String value = "";
        while (!isAtEnd() && peek().type() != TokenType.DEDENT) {
            Token child = expectLine("conditional layer");
            if (value.isEmpty()) {
                value = unquoteIfSingleStringLiteral(child.text(), child);
            }
        }
        consumeRequiredDedent(parentToken);
        return value;
    }

    private TransformDef parseTransformDef(Token token) {
        String header = stripRequiredColon(stripKeyword(token.text(), "transform", token), token);
        int parenIdx = header.indexOf('(');
        String name = (parenIdx >= 0 ? header.substring(0, parenIdx) : header).trim();
        if (name.isEmpty()) {
            throw error(token, "Expected transform name after 'transform'");
        }
        List<AtlStep> steps = consumeOptionalIndent() ? parseAtlBlock(token) : List.of();
        return new TransformDef(name, steps, token.sourceFile(), token.lineNumber());
    }

    private List<AtlStep> parseAtlBlock(Token parentToken) {
        enterNestedBlock(parentToken, "ATL");
        try {
            List<AtlStep> steps = new ArrayList<>();
            while (!isAtEnd() && peek().type() != TokenType.DEDENT) {
                Token lineTok = expectLine("ATL block");
                steps.add(parseAtlStep(lineTok));
            }
            consumeRequiredDedent(parentToken);
            return steps;
        } finally {
            blockDepth--;
        }
    }

    private AtlStep parseAtlStep(Token lineTok) {
        String raw = lineTok.text();
        boolean hasBlock = endsWithColonOutsideQuotes(raw);
        String header = hasBlock ? raw.substring(0, raw.length() - 1).trim() : raw;
        List<String> parts = splitWhitespaceTopLevel(header);
        if (parts.isEmpty()) {
            throw error(lineTok, "Empty ATL step");
        }

        String kw = parts.get(0);
        if (ATL_BLOCK_CONTAINERS.contains(kw)) {
            if (!hasBlock) {
                throw error(lineTok, "ATL '" + kw + "' requires ':' followed by indented steps");
            }
            expectIndent(lineTok);
            List<AtlStep> children = parseAtlBlock(lineTok);
            return new AtlStep(kw, 0.0f, null, Map.of(), children);
        }

        if ("on".equals(kw)) {
            if (!hasBlock || parts.size() < 2) {
                throw error(lineTok, "ATL 'on <event>:' requires event name and indented block");
            }
            String eventName = String.join(" ", parts.subList(1, parts.size()));
            expectIndent(lineTok);
            List<AtlStep> children = parseAtlBlock(lineTok);
            return new AtlStep("on", 0.0f, null, Map.of("event", eventName), children);
        }

        if ("pause".equals(kw)) {
            float duration = parts.size() >= 2 ? parseFloatStrict(parts.get(1), lineTok, "pause duration") : 0.0f;
            return new AtlStep("pause", duration, null, Map.of(), List.of());
        }

        if ("repeat".equals(kw)) {
            Map<String, String> props = parts.size() >= 2
                    ? Map.of("count", parts.get(1))
                    : Map.of();
            return new AtlStep("repeat", 0.0f, null, props, List.of());
        }

        if ("pass".equals(kw)) {
            return new AtlStep("pass", 0.0f, null, Map.of(), List.of());
        }

        if ("warp".equals(kw)) {
            if (parts.size() < 3) {
                throw error(lineTok, "ATL 'warp' requires warper expression and duration");
            }
            String easeName = parts.get(1);
            float duration = parseFloatStrict(parts.get(2), lineTok, "warp duration");
            Map<String, String> props = parseAtlProperties(parts.subList(3, parts.size()), lineTok);
            if (hasBlock && consumeOptionalIndent()) {
                mergeIndentedAtlProperties(lineTok, props);
            }
            return new AtlStep("interpolate", duration, easeName, props, List.of());
        }

        if (ATL_WARPERS.contains(kw)) {
            if (parts.size() < 2) {
                throw error(lineTok, "ATL '" + kw + "' requires a duration in seconds");
            }
            float duration = parseFloatStrict(parts.get(1), lineTok, kw + " duration");
            Map<String, String> props = parseAtlProperties(parts.subList(2, parts.size()), lineTok);
            if (hasBlock && consumeOptionalIndent()) {
                mergeIndentedAtlProperties(lineTok, props);
            }
            return new AtlStep("interpolate", duration, kw, props, List.of());
        }

        // Standard property assignment line (e.g. xpos 0.5, matrixcolor TintMatrix("#fff"), etc.)
        if (hasBlock) {
            throw error(lineTok, "Unexpected ':' in ATL property line: " + raw);
        }
        Map<String, String> props = parseAtlProperties(parts, lineTok);
        return new AtlStep("set", 0.0f, null, props, List.of());
    }

    private void mergeIndentedAtlProperties(Token parentToken, Map<String, String> targetProps) {
        while (!isAtEnd() && peek().type() != TokenType.DEDENT) {
            Token propTok = expectLine("ATL interpolation properties");
            List<String> propParts = splitWhitespaceTopLevel(propTok.text());
            targetProps.putAll(parseAtlProperties(propParts, propTok));
        }
        consumeRequiredDedent(parentToken);
    }

    private Map<String, String> parseAtlProperties(List<String> tokensList, Token lineTok) {
        Map<String, String> props = new LinkedHashMap<>();
        int i = 0;
        while (i < tokensList.size()) {
            String keyToken = tokensList.get(i);
            int eqIdx = findCharOutsideQuotesAndBrackets(keyToken, '=');
            if (eqIdx > 0) {
                String k = keyToken.substring(0, eqIdx).trim();
                String v = keyToken.substring(eqIdx + 1).trim();
                props.put(k, unquoteIfSingleStringLiteral(v, lineTok));
                i++;
                continue;
            }
            if (i + 1 >= tokensList.size()) {
                props.put(keyToken, "true");
                i++;
                break;
            }
            StringBuilder valueBuilder = new StringBuilder(tokensList.get(i + 1));
            int next = i + 2;
            while (next + 1 < tokensList.size() && isBinaryOperator(tokensList.get(next))) {
                valueBuilder.append(' ').append(tokensList.get(next)).append(' ').append(tokensList.get(next + 1));
                next += 2;
            }
            props.put(keyToken, unquoteIfSingleStringLiteral(valueBuilder.toString(), lineTok));
            i = next;
        }
        return props;
    }

    // ---- Screen Parser ----

    private ScreenDef parseScreenDef(Token token) {
        String header = stripRequiredColon(stripKeyword(token.text(), "screen", token), token);
        if (header.isEmpty()) {
            throw error(token, "Expected screen name after 'screen'");
        }
        String name;
        List<String> parameters = new ArrayList<>();
        int openParen = header.indexOf('(');
        if (openParen >= 0) {
            int closeParen = header.lastIndexOf(')');
            if (closeParen < openParen) {
                throw error(token, "Unclosed parameter list in screen declaration: " + token.text());
            }
            name = header.substring(0, openParen).trim();
            String inside = header.substring(openParen + 1, closeParen).trim();
            if (!inside.isEmpty()) {
                for (String param : splitTopLevelComma(inside)) {
                    if (!param.isEmpty()) {
                        parameters.add(param);
                    }
                }
            }
        } else {
            name = header;
        }

        if (name.isEmpty()) {
            throw error(token, "Screen name must not be empty");
        }

        boolean modal = false;
        int zOrder = 0;
        List<ScreenItem> items = new ArrayList<>();

        if (consumeOptionalIndent()) {
            while (!isAtEnd() && peek().type() != TokenType.DEDENT) {
                Token curr = peek();
                if (curr.type() == TokenType.JAVA_BLOCK) {
                    consume();
                    items.add(javaBlockAsScreenItem(curr));
                    continue;
                }
                Token itemTok = expectLine("screen body");
                String line = itemTok.text();
                String kw = firstWord(line);
                if ("modal".equals(kw) && !endsWithColonOutsideQuotes(line)) {
                    String val = stripKeyword(line, "modal", itemTok);
                    modal = parseBooleanStrict(val, itemTok, "modal");
                } else if ("zorder".equals(kw) && !endsWithColonOutsideQuotes(line)) {
                    String val = stripKeyword(line, "zorder", itemTok);
                    zOrder = parseIntStrict(val, itemTok, "zorder");
                } else {
                    items.add(parseScreenItem(itemTok));
                }
            }
            consumeRequiredDedent(token);
        }

        return new ScreenDef(name, parameters, modal, zOrder, items, token.sourceFile(), token.lineNumber());
    }

    private ScreenItem parseScreenItem(Token itemTok) {
        enterNestedBlock(itemTok, "screen");
        try {
            return parseScreenItemInternal(itemTok);
        } finally {
            blockDepth--;
        }
    }

    private ScreenItem parseScreenItemInternal(Token itemTok) {
        String raw = itemTok.text();
        boolean hasBlock = endsWithColonOutsideQuotes(raw);
        String header = hasBlock ? raw.substring(0, raw.length() - 1).trim() : raw;
        List<String> tokensList = splitWhitespaceTopLevel(header);
        if (tokensList.isEmpty()) {
            throw error(itemTok, "Empty screen item");
        }

        String keyword = tokensList.get(0);
        String primaryArg = null;
        Map<String, String> properties = new LinkedHashMap<>();
        int propStartIndex = 1;

        if ("if".equals(keyword) || "elif".equals(keyword) || "for".equals(keyword)) {
            if (tokensList.size() > 1) {
                primaryArg = String.join(" ", tokensList.subList(1, tokensList.size()));
            }
            propStartIndex = tokensList.size();
        } else if ("grid".equals(keyword)) {
            if (tokensList.size() >= 3 && isNumericLiteral(tokensList.get(1)) && isNumericLiteral(tokensList.get(2))) {
                primaryArg = tokensList.get(1) + " " + tokensList.get(2);
                properties.put("cols", tokensList.get(1));
                properties.put("rows", tokensList.get(2));
                propStartIndex = 3;
            } else if (tokensList.size() >= 2 && findCharOutsideQuotesAndBrackets(tokensList.get(1), '=') < 0) {
                primaryArg = unquoteIfSingleStringLiteral(tokensList.get(1), itemTok);
                propStartIndex = 2;
            }
        } else if (SCREEN_POSITIONAL_WIDGETS.contains(keyword)) {
            if (tokensList.size() >= 2 && findCharOutsideQuotesAndBrackets(tokensList.get(1), '=') < 0) {
                primaryArg = unquoteIfSingleStringLiteral(tokensList.get(1), itemTok);
                propStartIndex = 2;
            }
        } else if (tokensList.size() >= 2
                && (isQuotedLiteral(tokensList.get(1)) || tokensList.get(1).startsWith("("))
                && findCharOutsideQuotesAndBrackets(tokensList.get(1), '=') < 0) {
            primaryArg = unquoteIfSingleStringLiteral(tokensList.get(1), itemTok);
            propStartIndex = 2;
        }

        parseKeyValueTokens(tokensList.subList(propStartIndex, tokensList.size()), properties, itemTok);

        List<ScreenItem> children = new ArrayList<>();
        if (hasBlock && consumeOptionalIndent()) {
            while (!isAtEnd() && peek().type() != TokenType.DEDENT) {
                Token childTok = peek();
                if (childTok.type() == TokenType.JAVA_BLOCK) {
                    consume();
                    children.add(javaBlockAsScreenItem(childTok));
                    continue;
                }
                Token lineChild = expectLine("screen item body");
                String childText = lineChild.text();
                String childFirst = firstWord(childText);
                if (SCREEN_WIDGET_KEYWORDS.contains(childFirst) || endsWithColonOutsideQuotes(childText)) {
                    children.add(parseScreenItem(lineChild));
                } else {
                    parseScreenPropertyLine(childText, properties, lineChild);
                }
            }
            consumeRequiredDedent(itemTok);
        }

        return new ScreenItem(
                keyword,
                primaryArg,
                properties,
                children,
                itemTok.sourceFile(),
                itemTok.lineNumber()
        );
    }

    private void parseScreenPropertyLine(String line, Map<String, String> properties, Token lineTok) {
        int eqIdx = findCharOutsideQuotesAndBrackets(line, '=');
        int firstSpace = findFirstWhitespaceOutsideQuotesAndBrackets(line);
        if (eqIdx > 0 && (firstSpace < 0 || eqIdx < firstSpace)) {
            String key = line.substring(0, eqIdx).trim();
            String val = unquoteIfSingleStringLiteral(line.substring(eqIdx + 1).trim(), lineTok);
            properties.put(key, val);
            return;
        }
        if (firstSpace < 0) {
            properties.put(line.trim(), "true");
            return;
        }
        String key = line.substring(0, firstSpace).trim();
        String val = unquoteIfSingleStringLiteral(line.substring(firstSpace + 1).trim(), lineTok);
        properties.put(key, val);
    }

    private void parseKeyValueTokens(List<String> tokensList, Map<String, String> properties, Token lineTok) {
        int i = 0;
        while (i < tokensList.size()) {
            String tok = tokensList.get(i);
            int eqIdx = findCharOutsideQuotesAndBrackets(tok, '=');
            if (eqIdx > 0) {
                String k = tok.substring(0, eqIdx).trim();
                String v = unquoteIfSingleStringLiteral(tok.substring(eqIdx + 1).trim(), lineTok);
                properties.put(k, v);
                i++;
                continue;
            }
            if (i + 1 >= tokensList.size()) {
                properties.put(tok, "true");
                i++;
                break;
            }
            String nextTok = tokensList.get(i + 1);
            properties.put(tok, unquoteIfSingleStringLiteral(nextTok, lineTok));
            i += 2;
        }
    }

    private ScreenItem javaBlockAsScreenItem(Token javaTok) {
        return new ScreenItem(
                "java_block",
                javaTok.description(),
                Map.of("code", javaTok.code()),
                List.of(),
                javaTok.sourceFile(),
                javaTok.lineNumber()
        );
    }

    // ---- Translate Block Parser ----

    private TranslateBlock parseTranslateBlock(Token token) {
        String header = stripRequiredColon(stripKeyword(token.text(), "translate", token), token);
        List<String> parts = splitWhitespaceTopLevel(header);
        if (parts.size() != 2) {
            throw error(token, "Expected 'translate <lang> <id>:', got: " + token.text());
        }
        String language = parts.get(0);
        String id = parts.get(1);
        List<TranslateEntry> entries = new ArrayList<>();

        if (consumeOptionalIndent()) {
            String pendingOldText = null;
            while (!isAtEnd() && peek().type() != TokenType.DEDENT) {
                Token entryTok = expectLine("translate block");
                String line = entryTok.text();
                String commentOriginal = extractOriginalFromPrecedingComments(entryTok);

                if (line.startsWith("old ")) {
                    pendingOldText = extractSingleQuotedString(line.substring(4).trim(), entryTok);
                } else if (line.startsWith("new ")) {
                    String newText = extractSingleQuotedString(line.substring(4).trim(), entryTok);
                    entries.add(new TranslateEntry(null, pendingOldText, newText));
                    pendingOldText = null;
                } else if (line.startsWith("\"")) {
                    String translated = extractLeadingQuotedString(line, entryTok).value();
                    String orig = commentOriginal != null ? commentOriginal : pendingOldText;
                    entries.add(new TranslateEntry(null, orig, translated));
                    pendingOldText = null;
                } else {
                    List<String> entryParts = splitWhitespaceTopLevel(line);
                    if (entryParts.size() >= 2 && isQuotedLiteral(entryParts.get(1))) {
                        String speaker = entryParts.get(0);
                        String translated = unescapeAndUnquote(entryParts.get(1), entryTok);
                        String orig = commentOriginal != null ? commentOriginal : pendingOldText;
                        entries.add(new TranslateEntry(speaker, orig, translated));
                        pendingOldText = null;
                    } else {
                        throw error(entryTok, "Invalid entry inside translate block: " + line);
                    }
                }
            }
            consumeRequiredDedent(token);
        }

        return new TranslateBlock(language, id, entries, token.sourceFile(), token.lineNumber());
    }

    private String extractOriginalFromPrecedingComments(Token entryTok) {
        List<String> comments = entryTok.precedingComments();
        for (int i = comments.size() - 1; i >= 0; i--) {
            String comment = comments.get(i).trim();
            if (comment.startsWith("\"")) {
                return extractLeadingQuotedString(comment, entryTok).value();
            }
            List<String> parts = splitWhitespaceTopLevel(comment);
            if (parts.size() >= 2 && parts.get(1).startsWith("\"")) {
                return unescapeAndUnquote(parts.get(1), entryTok);
            }
        }
        return null;
    }

    // ---- Label & Statement Parsers ----

    private LabelNode parseLabelNode(Token token) {
        String header = stripRequiredColon(stripKeyword(token.text(), "label", token), token);
        int parenIdx = header.indexOf('(');
        String name = (parenIdx >= 0 ? header.substring(0, parenIdx) : header).trim();
        if (name.isEmpty()) {
            throw error(token, "Expected label name after 'label'");
        }
        List<Statement> statements = consumeOptionalIndent() ? parseStatementBlock(token) : List.of();
        return new LabelNode(name, statements, token.sourceFile(), token.lineNumber());
    }

    private List<Statement> parseStatementBlock(Token parentToken) {
        enterNestedBlock(parentToken, "statement");
        try {
            List<Statement> statements = new ArrayList<>();
            while (!isAtEnd() && peek().type() != TokenType.DEDENT) {
                Token curr = peek();
                if (curr.type() == TokenType.JAVA_BLOCK) {
                    consume();
                    JavaBlockNode block = toJavaBlockNode(curr);
                    statements.add(new JavaBlockStatement(block, curr.sourceFile(), curr.lineNumber()));
                    continue;
                }
                if (curr.type() != TokenType.LINE) {
                    throw error(curr, "Unexpected token inside statement block: " + curr.type());
                }
                consume();
                String text = curr.text();
                if (text.startsWith("$")) {
                    statements.add(parseSetVar(text, false, curr));
                    continue;
                }
                if (text.startsWith("\"")) {
                    statements.add(parseSayStatement(null, text, curr));
                    continue;
                }

                String keyword = firstWord(text);
                switch (keyword) {
                    case "pass" -> {
                        // Explicit no-op placeholder for empty indented blocks
                    }
                    case "scene" -> statements.add(parseSceneStatement(curr));
                    case "show" -> statements.add(parseShowStatement(curr));
                    case "hide" -> statements.add(parseHideStatement(curr));
                    case "with" -> statements.add(parseWithStatement(curr));
                    case "play" -> statements.add(parsePlayStatement(curr));
                    case "queue" -> statements.add(parseQueueStatement(curr));
                    case "stop" -> statements.add(parseStopStatement(curr));
                    case "voice" -> statements.add(parseVoiceStatement(curr));
                    case "nvl" -> statements.add(parseNvlStatement(curr));
                    case "window" -> statements.add(parseWindowStatement(curr));
                    case "block_rollback" -> {
                        String extra = text.substring("block_rollback".length()).trim();
                        if (!extra.isEmpty()) {
                            throw error(curr, "Unexpected arguments after 'block_rollback': " + extra);
                        }
                        statements.add(new BlockRollbackStatement(curr.sourceFile(), curr.lineNumber()));
                    }
                    case "pause" -> statements.add(parsePauseStatement(curr));
                    case "jump" -> statements.add(parseJumpStatement(curr));
                    case "call" -> statements.add(parseCallStatement(curr));
                    case "return" -> statements.add(parseReturnStatement(curr));
                    case "input" -> statements.add(parseInputStatement(curr));
                    case "move" -> statements.add(parseMoveStatement(curr, false));
                    case "move_by" -> statements.add(parseMoveStatement(curr, true));
                    case "move_path" -> statements.add(parseMovePathStatement(curr));
                    case "rotate" -> statements.add(parseRotateStatement(curr));
                    case "scale" -> statements.add(parseScaleStatement(curr));
                    case "collision_mask" -> statements.add(parseCollisionMaskStatement(curr));
                    case "hitbox" -> statements.add(parseHitboxStatement(curr));
                    case "on_overlap" -> statements.add(parseOnOverlapStatement(curr));
                    case "solid_platform" -> statements.add(parseSolidPlatformStatement(curr));
                    case "parallel" -> statements.add(parseParallelStatement(curr));
                    case "wait_move" -> statements.add(parseWaitMoveStatement(curr));
                    case "camera" -> statements.add(parseCameraStatement(curr));
                    case "trigger_zone" -> statements.add(parseTriggerZoneStatement(curr));
                    case "wasd_init" -> statements.add(parseWasdInitStatement(curr));
                    case "wasd_stop" -> statements.add(parseWasdStopStatement(curr));
                    case "physics_init" -> statements.add(parsePhysicsInitStatement(curr));
                    case "physics_stop" -> statements.add(parsePhysicsStopStatement(curr));
                    case "on_collide" -> statements.add(parseOnCollideStatement(curr));
                    case "patrol" -> statements.add(parsePatrolStatement(curr));
                    case "follow" -> statements.add(parseFollowStatement(curr));
                    case "after" -> statements.add(parseAfterTimerStatement(curr));
                    case "on_interact" -> statements.add(parseOnInteractStatement(curr));
                    case "repeat" -> statements.add(parseRepeatStatement(curr));
                    case "while" -> statements.add(parseWhileStatement(curr));
                    case "anim_state" -> statements.add(parseAnimStateStatement(curr));
                    case "collider" -> statements.add(parseColliderWallStatement(curr));
                    case "shake" -> statements.add(parseShakeLayerStatement(curr));
                    case "knockback" -> statements.add(parseKnockbackStatement(curr));
                    case "climb_zone" -> statements.add(parseClimbZoneStatement(curr));
                    case "moving_platform" -> statements.add(parseMovingPlatformStatement(curr));
                    case "attract" -> statements.add(parseAttractStatement(curr));
                    case "spawn" -> statements.add(parseSpawnStatement(curr));
                    case "despawn" -> statements.add(parseDespawnStatement(curr));
                    case "on_tween_end" -> statements.add(parseOnTweenEndStatement(curr));
                    case "on_event" -> statements.add(parseOnEventStatement(curr));
                    case "spawn_cutscene" -> statements.add(parseSpawnCutsceneStatement(curr));
                    case "random" -> statements.add(parseRandomStatement(curr));
                    case "set" -> statements.add(parseSetBlockStatement(curr));
                    case "local" -> statements.add(parseLocalVarStatement(curr));
                    case "time_scale" -> statements.add(parseTimeScaleStatement(curr));
                    case "attach" -> statements.add(parseAttachStatement(curr));
                    case "ysort" -> statements.add(parseYSortStatement(curr));
                    case "point_click_init" -> statements.add(parsePointClickInitStatement(curr));
                    case "surface_friction" -> statements.add(parseSurfaceFrictionStatement(curr));
                    case "impulse" -> statements.add(parseImpulseStatement(curr));
                    case "scene3d" -> statements.add(parseScene3DStatement(curr));
                    case "spawn3d" -> statements.add(parseSpawn3DStatement(curr));
                    case "camera3d" -> statements.add(parseCamera3DStatement(curr));
                    case "wasd3d_init" -> statements.add(parseWasd3DInitStatement(curr));
                    case "fog3d" -> statements.add(parseFog3DStatement(curr));
                    case "material3d" -> statements.add(parseMaterial3DStatement(curr));
                    case "match" -> statements.add(parseMatchStatement(curr));
                    case "case", "default" -> throw error(curr, "Orphaned '" + keyword + "' outside 'match' block");
                    case "if" -> statements.add(parseIfStatement(curr));
                    case "elif", "else" -> throw error(curr, "Orphaned '" + keyword + "' without preceding 'if'");
                    case "menu" -> statements.add(parseMenuStatement(curr));
                    default -> {
                        // Speaker dialogue: <speakerId> "<text>" [with <transition>]
                        String remainder = text.substring(keyword.length()).trim();
                        if (remainder.startsWith("\"")) {
                            statements.add(parseSayStatement(keyword, remainder, curr));
                        } else {
                            throw error(curr, "Unknown statement: '" + text + "'");
                        }
                    }
                }
            }
            consumeRequiredDedent(parentToken);
            return statements;
        } finally {
            blockDepth--;
        }
    }

    private void enterNestedBlock(Token token, String blockKind) {
        blockDepth++;
        if (blockDepth > MAX_BLOCK_DEPTH) {
            throw error(token, "Maximum " + blockKind + " nesting depth (" + MAX_BLOCK_DEPTH + ") exceeded");
        }
    }

    private SceneStatement parseSceneStatement(Token token) {
        String rest = stripKeyword(token.text(), "scene", token);
        int withIdx = findKeywordOutsideQuotesAndBrackets(rest, "with");
        String bgPart = (withIdx >= 0 ? rest.substring(0, withIdx) : rest).trim();
        String transition = withIdx >= 0 ? rest.substring(withIdx + 4).trim() : null;
        if (bgPart.isEmpty()) {
            throw error(token, "Expected background tag after 'scene'");
        }
        if (withIdx >= 0 && transition.isEmpty()) {
            throw error(token, "Expected transition expression after 'with'");
        }
        return new SceneStatement(bgPart, transition, token.sourceFile(), token.lineNumber());
    }

    private ShowStatement parseShowStatement(Token token) {
        String raw = token.text();
        boolean hasInlineAtl = endsWithColonOutsideQuotes(raw);
        String header = hasInlineAtl ? raw.substring(0, raw.length() - 1).trim() : raw;
        String rest = stripKeyword(header, "show", token);

        int atIdx = findKeywordOutsideQuotesAndBrackets(rest, "at");
        int withIdx = findKeywordOutsideQuotesAndBrackets(rest, "with");

        String tagAndAttrsPart;
        String positionExpr = null;
        String transitionExpr = null;

        if (atIdx >= 0 && withIdx >= 0) {
            if (atIdx < withIdx) {
                tagAndAttrsPart = rest.substring(0, atIdx).trim();
                positionExpr = rest.substring(atIdx + 2, withIdx).trim();
                transitionExpr = rest.substring(withIdx + 4).trim();
            } else {
                tagAndAttrsPart = rest.substring(0, withIdx).trim();
                transitionExpr = rest.substring(withIdx + 4, atIdx).trim();
                positionExpr = rest.substring(atIdx + 2).trim();
            }
        } else if (atIdx >= 0) {
            tagAndAttrsPart = rest.substring(0, atIdx).trim();
            positionExpr = rest.substring(atIdx + 2).trim();
        } else if (withIdx >= 0) {
            tagAndAttrsPart = rest.substring(0, withIdx).trim();
            transitionExpr = rest.substring(withIdx + 4).trim();
        } else {
            tagAndAttrsPart = rest;
        }

        List<String> tagTokens = splitWhitespaceTopLevel(tagAndAttrsPart);
        if (tagTokens.isEmpty()) {
            throw error(token, "Expected sprite tag after 'show'");
        }
        if (atIdx >= 0 && (positionExpr == null || positionExpr.isEmpty())) {
            throw error(token, "Expected position expression after 'at'");
        }
        if (withIdx >= 0 && (transitionExpr == null || transitionExpr.isEmpty())) {
            throw error(token, "Expected transition expression after 'with'");
        }

        String tag = tagTokens.get(0);
        List<String> attributes = tagTokens.size() > 1 ? tagTokens.subList(1, tagTokens.size()) : List.of();
        List<AtlStep> inlineAtl = List.of();
        if (hasInlineAtl) {
            expectIndent(token);
            inlineAtl = parseAtlBlock(token);
        }

        return new ShowStatement(
                tag, attributes, positionExpr, transitionExpr, inlineAtl, token.sourceFile(), token.lineNumber());
    }

    private HideStatement parseHideStatement(Token token) {
        String rest = stripKeyword(token.text(), "hide", token);
        int withIdx = findKeywordOutsideQuotesAndBrackets(rest, "with");
        String tag = (withIdx >= 0 ? rest.substring(0, withIdx) : rest).trim();
        String transition = withIdx >= 0 ? rest.substring(withIdx + 4).trim() : null;
        if (tag.isEmpty()) {
            throw error(token, "Expected sprite tag after 'hide'");
        }
        if (withIdx >= 0 && transition.isEmpty()) {
            throw error(token, "Expected transition expression after 'with'");
        }
        return new HideStatement(tag, transition, token.sourceFile(), token.lineNumber());
    }

    private WithStatement parseWithStatement(Token token) {
        String transition = stripKeyword(token.text(), "with", token);
        if (transition.isEmpty()) {
            throw error(token, "Expected transition expression after 'with'");
        }
        return new WithStatement(transition, token.sourceFile(), token.lineNumber());
    }

    private PlayStatement parsePlayStatement(Token token) {
        String rest = stripKeyword(token.text(), "play", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() < 2) {
            throw error(token, "Expected 'play <channel> \"<asset>\"', got: " + token.text());
        }
        String channel = parts.get(0);
        String assetPath = unquoteIfSingleStringLiteral(parts.get(1), token);

        String normalizedChan = channel.toLowerCase(Locale.ROOT);
        boolean loop = "music".equals(normalizedChan) || "ambient".equals(normalizedChan);
        double fadeInSeconds = 0.0;
        double volume = 1.0;
        double loopStartSeconds = 0.0;

        int i = 2;
        while (i < parts.size()) {
            String opt = parts.get(i);
            switch (opt) {
                case "loop" -> {
                    loop = true;
                    i++;
                }
                case "noloop" -> {
                    loop = false;
                    i++;
                }
                case "fadein" -> {
                    if (i + 1 >= parts.size()) {
                        throw error(token, "Expected duration after 'fadein'");
                    }
                    fadeInSeconds = parseDoubleStrict(parts.get(i + 1), token, "fadein");
                    i += 2;
                }
                case "volume" -> {
                    if (i + 1 >= parts.size()) {
                        throw error(token, "Expected volume value after 'volume'");
                    }
                    volume = parseDoubleStrict(parts.get(i + 1), token, "volume");
                    i += 2;
                }
                case "loop_start_sec" -> {
                    if (i + 1 >= parts.size()) {
                        throw error(token, "Expected duration in seconds after 'loop_start_sec'");
                    }
                    loopStartSeconds = parseDoubleStrict(parts.get(i + 1), token, "loop_start_sec");
                    if (!Double.isFinite(loopStartSeconds) || loopStartSeconds < 0.0) {
                        throw error(token, "Invalid negative 'loop_start_sec' value: " + loopStartSeconds);
                    }
                    i += 2;
                }
                default -> throw error(token, "Unknown option in 'play' statement: " + opt);
            }
        }

        return new PlayStatement(
                channel,
                assetPath,
                loop,
                fadeInSeconds,
                volume,
                loopStartSeconds,
                token.sourceFile(),
                token.lineNumber()
        );
    }

    private QueueStatement parseQueueStatement(Token token) {
        String raw = token.text();
        boolean hasBlock = endsWithColonOutsideQuotes(raw);
        String header = hasBlock ? raw.substring(0, raw.length() - 1).trim() : raw;
        String rest = stripKeyword(header, "queue", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.isEmpty()) {
            throw error(token, "Expected channel name after 'queue'");
        }
        String channel = parts.get(0);
        List<String> assets = new ArrayList<>();

        for (int i = 1; i < parts.size(); i++) {
            String item = parts.get(i);
            if (item.startsWith("[") && item.endsWith("]")) {
                String inner = item.substring(1, item.length() - 1).trim();
                for (String elem : splitTopLevelComma(inner)) {
                    if (!elem.isEmpty()) {
                        assets.add(unquoteIfSingleStringLiteral(elem, token));
                    }
                }
            } else {
                String cleaned = item.endsWith(",") ? item.substring(0, item.length() - 1).trim() : item;
                if (!cleaned.isEmpty()) {
                    assets.add(unquoteIfSingleStringLiteral(cleaned, token));
                }
            }
        }

        if (hasBlock) {
            expectIndent(token);
            while (!isAtEnd() && peek().type() != TokenType.DEDENT) {
                Token assetTok = expectLine("queue asset list");
                String entry = assetTok.text();
                if (entry.endsWith(",")) {
                    entry = entry.substring(0, entry.length() - 1).trim();
                }
                if (!entry.isEmpty()) {
                    assets.add(unquoteIfSingleStringLiteral(entry, assetTok));
                }
            }
            consumeRequiredDedent(token);
        }

        if (assets.isEmpty()) {
            throw error(token, "Expected at least one asset path in 'queue' statement");
        }
        return new QueueStatement(channel, assets, token.sourceFile(), token.lineNumber());
    }

    private StopStatement parseStopStatement(Token token) {
        String rest = stripKeyword(token.text(), "stop", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.isEmpty()) {
            throw error(token, "Expected channel name after 'stop'");
        }
        String channel = parts.get(0);
        double fadeOutSeconds = 0.0;
        if (parts.size() > 1) {
            if (parts.size() == 3 && "fadeout".equals(parts.get(1))) {
                fadeOutSeconds = parseDoubleStrict(parts.get(2), token, "fadeout");
            } else {
                throw error(token, "Invalid options in 'stop' statement: " + token.text());
            }
        }
        return new StopStatement(channel, fadeOutSeconds, token.sourceFile(), token.lineNumber());
    }

    private VoiceStatement parseVoiceStatement(Token token) {
        String rest = stripKeyword(token.text(), "voice", token);
        if (rest.isEmpty()) {
            throw error(token, "Expected audio asset path after 'voice'");
        }
        String assetPath = unquoteIfSingleStringLiteral(rest, token);
        return new VoiceStatement(assetPath, token.sourceFile(), token.lineNumber());
    }

    private NvlStatement parseNvlStatement(Token token) {
        String action = stripKeyword(token.text(), "nvl", token);
        if (!"show".equals(action) && !"hide".equals(action) && !"clear".equals(action)) {
            throw error(token, "Expected 'nvl show', 'nvl hide', or 'nvl clear', got: " + token.text());
        }
        return new NvlStatement(action, token.sourceFile(), token.lineNumber());
    }

    private WindowStatement parseWindowStatement(Token token) {
        String action = stripKeyword(token.text(), "window", token);
        if (!"show".equals(action) && !"hide".equals(action) && !"auto".equals(action)) {
            throw error(token, "Expected 'window show', 'window hide', or 'window auto', got: " + token.text());
        }
        return new WindowStatement(action, token.sourceFile(), token.lineNumber());
    }

    private MoveStatement parseMoveStatement(Token token, boolean relative) {
        String keyword = relative ? "move_by" : "move";
        String rest = stripKeyword(token.text(), keyword, token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.isEmpty()) {
            throw error(token, "Expected sprite tag after '" + keyword + "'");
        }
        String tag = parts.get(0);
        int coordIdx = 1;
        boolean viewportRelative = false;
        if (!relative && parts.size() > 1) {
            if ("rel".equalsIgnoreCase(parts.get(1))) {
                viewportRelative = true;
                coordIdx = 2;
            } else if ("to".equalsIgnoreCase(parts.get(1))) {
                coordIdx = 2;
            }
        }
        if (parts.size() < coordIdx + 2) {
            throw error(token, "Expected coordinates '<x> <y>' in '" + keyword + "' statement");
        }
        double targetX = parseDoubleStrict(parts.get(coordIdx), token, keyword + " x");
        double targetY = parseDoubleStrict(parts.get(coordIdx + 1), token, keyword + " y");
        double durationSeconds = 0.0;
        String easing = "linear";

        int i = coordIdx + 2;
        while (i < parts.size()) {
            String opt = parts.get(i).toLowerCase(Locale.ROOT);
            switch (opt) {
                case "duration" -> {
                    if (i + 1 >= parts.size()) {
                        throw error(token, "Expected duration value after 'duration'");
                    }
                    durationSeconds = parseDoubleStrict(parts.get(i + 1), token, "duration");
                    i += 2;
                }
                case "ease", "easing" -> {
                    if (i + 1 >= parts.size()) {
                        throw error(token, "Expected easing type after '" + parts.get(i) + "'");
                    }
                    easing = parts.get(i + 1).toLowerCase(Locale.ROOT);
                    i += 2;
                }
                default -> throw error(token, "Unknown option in '" + keyword + "' statement: " + parts.get(i));
            }
        }
        return new MoveStatement(
                tag, targetX, targetY, relative, viewportRelative,
                durationSeconds, easing, token.sourceFile(), token.lineNumber());
    }

    private WasdInitStatement parseWasdInitStatement(Token token) {
        String rest = stripKeyword(token.text(), "wasd_init", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.isEmpty()) {
            throw error(token, "Expected sprite tag after 'wasd_init'");
        }
        String tag = parts.get(0);
        double speed = 260.0;
        Double minX = null;
        Double minY = null;
        Double maxX = null;
        Double maxY = null;
        boolean normalizeDiagonal = true;
        double friction = 0.0;
        double acceleration = 0.0;
        boolean autoFlip = false;
        String dashKey = null;

        int i = 1;
        while (i < parts.size()) {
            String opt = parts.get(i).toLowerCase(Locale.ROOT);
            switch (opt) {
                case "speed" -> {
                    if (i + 1 >= parts.size()) {
                        throw error(token, "Expected speed value after 'speed'");
                    }
                    speed = parseDoubleStrict(parts.get(i + 1), token, "speed");
                    i += 2;
                }
                case "bounds" -> {
                    if (i + 4 >= parts.size()) {
                        throw error(token, "Expected 'bounds <x1> <y1> <x2> <y2>'");
                    }
                    minX = parseDoubleStrict(parts.get(i + 1), token, "bounds x1");
                    minY = parseDoubleStrict(parts.get(i + 2), token, "bounds y1");
                    maxX = parseDoubleStrict(parts.get(i + 3), token, "bounds x2");
                    maxY = parseDoubleStrict(parts.get(i + 4), token, "bounds y2");
                    i += 5;
                }
                case "diagonal_norm" -> {
                    if (i + 1 >= parts.size()) {
                        throw error(token, "Expected boolean after 'diagonal_norm'");
                    }
                    normalizeDiagonal = parseBooleanStrict(parts.get(i + 1), token, "diagonal_norm");
                    i += 2;
                }
                case "friction" -> {
                    if (i + 1 >= parts.size()) {
                        throw error(token, "Expected numeric value after 'friction'");
                    }
                    friction = Math.max(0.0, parseDoubleStrict(parts.get(i + 1), token, "friction"));
                    i += 2;
                }
                case "accel", "acceleration" -> {
                    if (i + 1 >= parts.size()) {
                        throw error(token, "Expected numeric value after '" + opt + "'");
                    }
                    acceleration = Math.max(0.0, parseDoubleStrict(parts.get(i + 1), token, opt));
                    i += 2;
                }
                case "auto_flip" -> {
                    if (i + 1 >= parts.size()) {
                        throw error(token, "Expected boolean after 'auto_flip'");
                    }
                    autoFlip = parseBooleanStrict(parts.get(i + 1), token, "auto_flip");
                    i += 2;
                }
                case "dash_key" -> {
                    if (i + 1 >= parts.size()) {
                        throw error(token, "Expected key identifier after 'dash_key'");
                    }
                    dashKey = unquoteIfSingleStringLiteral(parts.get(i + 1), token);
                    i += 2;
                }
                default -> throw error(token, "Unknown option in 'wasd_init': " + parts.get(i));
            }
        }
        return new WasdInitStatement(
                tag, speed, minX, minY, maxX, maxY, normalizeDiagonal,
                friction, acceleration, autoFlip, dashKey, token.sourceFile(), token.lineNumber());
    }

    private WasdStopStatement parseWasdStopStatement(Token token) {
        String raw = token.text().trim();
        String rest = raw.length() > "wasd_stop".length() ? raw.substring("wasd_stop".length()).trim() : "";
        if (rest.contains(" ")) {
            throw error(token, "Expected optional single sprite tag after 'wasd_stop'");
        }
        return new WasdStopStatement(rest.isEmpty() ? null : rest, token.sourceFile(), token.lineNumber());
    }

    private PhysicsInitStatement parsePhysicsInitStatement(Token token) {
        String rest = stripKeyword(token.text(), "physics_init", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.isEmpty()) {
            throw error(token, "Expected sprite tag after 'physics_init'");
        }
        String tag = parts.get(0);
        double gravity = 980.0;
        double jumpForce = 460.0;
        double groundY = 920.0;

        int i = 1;
        while (i < parts.size()) {
            String opt = parts.get(i).toLowerCase(Locale.ROOT);
            switch (opt) {
                case "gravity" -> {
                    if (i + 1 >= parts.size()) {
                        throw error(token, "Expected gravity value after 'gravity'");
                    }
                    gravity = parseDoubleStrict(parts.get(i + 1), token, "gravity");
                    i += 2;
                }
                case "jump_force" -> {
                    if (i + 1 >= parts.size()) {
                        throw error(token, "Expected jump force value after 'jump_force'");
                    }
                    jumpForce = parseDoubleStrict(parts.get(i + 1), token, "jump_force");
                    i += 2;
                }
                case "ground_y" -> {
                    if (i + 1 >= parts.size()) {
                        throw error(token, "Expected ground Y coordinate after 'ground_y'");
                    }
                    groundY = parseDoubleStrict(parts.get(i + 1), token, "ground_y");
                    i += 2;
                }
                default -> throw error(token, "Unknown option in 'physics_init': " + parts.get(i));
            }
        }
        return new PhysicsInitStatement(tag, gravity, jumpForce, groundY, token.sourceFile(), token.lineNumber());
    }

    private PhysicsStopStatement parsePhysicsStopStatement(Token token) {
        String raw = token.text().trim();
        String rest = raw.length() > "physics_stop".length() ? raw.substring("physics_stop".length()).trim() : "";
        if (rest.contains(" ")) {
            throw error(token, "Expected optional single sprite tag after 'physics_stop'");
        }
        return new PhysicsStopStatement(rest.isEmpty() ? null : rest, token.sourceFile(), token.lineNumber());
    }

    private OnCollideStatement parseOnCollideStatement(Token token) {
        String rest = stripKeyword(token.text(), "on_collide", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() != 4 || !"jump".equalsIgnoreCase(parts.get(2))) {
            throw error(token, "Expected 'on_collide <tagA> <tagB> jump <label>', got: " + token.text());
        }
        return new OnCollideStatement(parts.get(0), parts.get(1), parts.get(3), token.sourceFile(), token.lineNumber());
    }

    private MovePathStatement parseMovePathStatement(Token token) {
        String rest = stripKeyword(token.text(), "move_path", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() < 7) {
            throw error(token,
                    "Expected 'move_path <tag> <x1> <y1> <x2> <y2> <x3> <y3> [duration <sec>] [ease <name>]'");
        }
        String tag = parts.get(0);
        double x1 = parseDoubleStrict(parts.get(1), token, "move_path x1");
        double y1 = parseDoubleStrict(parts.get(2), token, "move_path y1");
        double x2 = parseDoubleStrict(parts.get(3), token, "move_path x2");
        double y2 = parseDoubleStrict(parts.get(4), token, "move_path y2");
        double x3 = parseDoubleStrict(parts.get(5), token, "move_path x3");
        double y3 = parseDoubleStrict(parts.get(6), token, "move_path y3");
        double durationSeconds = 0.0;
        String easing = "linear";
        int i = 7;
        while (i < parts.size()) {
            String opt = parts.get(i).toLowerCase(Locale.ROOT);
            switch (opt) {
                case "duration" -> {
                    if (i + 1 >= parts.size()) {
                        throw error(token, "Expected duration value after 'duration'");
                    }
                    durationSeconds = parseDoubleStrict(parts.get(i + 1), token, "duration");
                    i += 2;
                }
                case "ease", "easing" -> {
                    if (i + 1 >= parts.size()) {
                        throw error(token, "Expected easing type after '" + parts.get(i) + "'");
                    }
                    easing = parts.get(i + 1).toLowerCase(Locale.ROOT);
                    i += 2;
                }
                default -> throw error(token, "Unknown option in 'move_path' statement: " + parts.get(i));
            }
        }
        return new MovePathStatement(
                tag, x1, y1, x2, y2, x3, y3, durationSeconds, easing, token.sourceFile(), token.lineNumber());
    }

    private RotateStatement parseRotateStatement(Token token) {
        String rest = stripKeyword(token.text(), "rotate", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.isEmpty()) {
            throw error(token, "Expected sprite tag after 'rotate'");
        }
        String tag = parts.get(0);
        int degIdx = (parts.size() > 1 && "to".equalsIgnoreCase(parts.get(1))) ? 2 : 1;
        if (parts.size() <= degIdx) {
            throw error(token, "Expected target degrees in 'rotate' statement");
        }
        double targetDegrees = parseDoubleStrict(parts.get(degIdx), token, "rotate degrees");
        double durationSeconds = 0.0;
        String easing = "linear";
        int i = degIdx + 1;
        while (i < parts.size()) {
            String opt = parts.get(i).toLowerCase(Locale.ROOT);
            switch (opt) {
                case "duration" -> {
                    if (i + 1 >= parts.size()) {
                        throw error(token, "Expected duration value after 'duration'");
                    }
                    durationSeconds = parseDoubleStrict(parts.get(i + 1), token, "duration");
                    i += 2;
                }
                case "ease", "easing" -> {
                    if (i + 1 >= parts.size()) {
                        throw error(token, "Expected easing type after '" + parts.get(i) + "'");
                    }
                    easing = parts.get(i + 1).toLowerCase(Locale.ROOT);
                    i += 2;
                }
                default -> throw error(token, "Unknown option in 'rotate' statement: " + parts.get(i));
            }
        }
        return new RotateStatement(tag, targetDegrees, durationSeconds, easing, token.sourceFile(), token.lineNumber());
    }

    private ScaleStatement parseScaleStatement(Token token) {
        String rest = stripKeyword(token.text(), "scale", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.isEmpty()) {
            throw error(token, "Expected sprite tag after 'scale'");
        }
        String tag = parts.get(0);
        int idx = (parts.size() > 1 && "to".equalsIgnoreCase(parts.get(1))) ? 2 : 1;
        if (parts.size() <= idx) {
            throw error(token, "Expected zoom factor in 'scale' statement");
        }
        double zx = parseDoubleStrict(parts.get(idx), token, "scale xzoom");
        double zy = zx;
        int i = idx + 1;
        if (i < parts.size()) {
            String nextLower = parts.get(i).toLowerCase(Locale.ROOT);
            if (!"duration".equals(nextLower) && !"ease".equals(nextLower) && !"easing".equals(nextLower)) {
                zy = parseDoubleStrict(parts.get(i), token, "scale yzoom");
                i++;
            }
        }
        double durationSeconds = 0.0;
        String easing = "linear";
        while (i < parts.size()) {
            String opt = parts.get(i).toLowerCase(Locale.ROOT);
            switch (opt) {
                case "duration" -> {
                    if (i + 1 >= parts.size()) {
                        throw error(token, "Expected duration value after 'duration'");
                    }
                    durationSeconds = parseDoubleStrict(parts.get(i + 1), token, "duration");
                    i += 2;
                }
                case "ease", "easing" -> {
                    if (i + 1 >= parts.size()) {
                        throw error(token, "Expected easing type after '" + parts.get(i) + "'");
                    }
                    easing = parts.get(i + 1).toLowerCase(Locale.ROOT);
                    i += 2;
                }
                default -> throw error(token, "Unknown option in 'scale' statement: " + parts.get(i));
            }
        }
        return new ScaleStatement(tag, zx, zy, durationSeconds, easing, token.sourceFile(), token.lineNumber());
    }

    private CollisionMaskStatement parseCollisionMaskStatement(Token token) {
        String rest = stripKeyword(token.text(), "collision_mask", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() < 2) {
            throw error(token, "Expected 'collision_mask <tag> <layer> [collides_with <mask1> ...]'");
        }
        String tag = parts.get(0);
        String layer = parts.get(1);
        List<String> collidesWith = new ArrayList<>();
        if (parts.size() > 2) {
            if (!"collides_with".equalsIgnoreCase(parts.get(2)) || parts.size() < 4) {
                throw error(token, "Expected 'collides_with <mask>...' in 'collision_mask' statement");
            }
            for (int i = 3; i < parts.size(); i++) {
                String raw = parts.get(i).replace(",", "").trim();
                if (!raw.isEmpty()) {
                    collidesWith.add(raw);
                }
            }
        }
        return new CollisionMaskStatement(tag, layer, collidesWith, token.sourceFile(), token.lineNumber());
    }

    private HitboxStatement parseHitboxStatement(Token token) {
        String rest = stripKeyword(token.text(), "hitbox", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() < 3) {
            throw error(token, "Expected 'hitbox <tag> circle <radius>' or 'hitbox <tag> rect <w> <h>'");
        }
        String tag = parts.get(0);
        String shape = parts.get(1).toLowerCase(Locale.ROOT);
        if ("circle".equals(shape)) {
            if (parts.size() != 3) {
                throw error(token, "Expected 'hitbox <tag> circle <radius>'");
            }
            double radius = parseDoubleStrict(parts.get(2), token, "hitbox circle radius");
            if (!Double.isFinite(radius) || radius <= 0.0) {
                throw error(token, "Hitbox circle radius must be positive, got: " + radius);
            }
            return new HitboxStatement(tag, "circle", radius, 0.0, token.sourceFile(), token.lineNumber());
        }
        if ("rect".equals(shape)) {
            if (parts.size() != 4) {
                throw error(token, "Expected 'hitbox <tag> rect <w> <h>'");
            }
            double w = parseDoubleStrict(parts.get(2), token, "hitbox rect width");
            double h = parseDoubleStrict(parts.get(3), token, "hitbox rect height");
            if (!Double.isFinite(w) || !Double.isFinite(h) || w <= 0.0 || h <= 0.0) {
                throw error(token, "Hitbox rect dimensions must be positive, got: " + w + "x" + h);
            }
            return new HitboxStatement(tag, "rect", w, h, token.sourceFile(), token.lineNumber());
        }
        throw error(token, "Unsupported hitbox shape '" + parts.get(1) + "' (expected circle or rect)");
    }

    private OnOverlapStatement parseOnOverlapStatement(Token token) {
        String rest = stripKeyword(token.text(), "on_overlap", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() != 4) {
            throw error(token, "Expected 'on_overlap <tagA> <tagB> <enter|stay|exit> <label>'");
        }
        String phase = parts.get(2).toLowerCase(Locale.ROOT);
        if (!"enter".equals(phase) && !"stay".equals(phase) && !"exit".equals(phase)) {
            throw error(token, "Invalid overlap phase '" + parts.get(2) + "' (expected enter, stay, or exit)");
        }
        return new OnOverlapStatement(parts.get(0), parts.get(1), phase, parts.get(3),
                token.sourceFile(), token.lineNumber());
    }

    private SolidPlatformStatement parseSolidPlatformStatement(Token token) {
        String rest = stripKeyword(token.text(), "solid_platform", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.isEmpty() || parts.size() > 2) {
            throw error(token, "Expected 'solid_platform <tag> [one_way]'");
        }
        String tag = parts.get(0);
        boolean oneWay = false;
        if (parts.size() == 2) {
            if (!"one_way".equalsIgnoreCase(parts.get(1))) {
                throw error(token, "Unknown option in 'solid_platform': " + parts.get(1));
            }
            oneWay = true;
        }
        return new SolidPlatformStatement(tag, oneWay, token.sourceFile(), token.lineNumber());
    }

    private ParallelStatement parseParallelStatement(Token token) {
        String headerRest = stripRequiredColon(stripKeywordSafe(token.text(), "parallel"), token);
        if (!headerRest.isEmpty()) {
            throw error(token, "Unexpected header arguments in 'parallel:' statement: " + headerRest);
        }
        if (!consumeOptionalIndent()) {
            throw error(token, "Expected indented body after 'parallel:'");
        }
        List<Statement> nested = parseStatementBlock(token);
        return new ParallelStatement(nested, token.sourceFile(), token.lineNumber());
    }

    private WaitMoveStatement parseWaitMoveStatement(Token token) {
        String rest = stripKeyword(token.text(), "wait_move", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() != 1) {
            throw error(token, "Expected 'wait_move <tag>', got: " + token.text());
        }
        return new WaitMoveStatement(parts.get(0), token.sourceFile(), token.lineNumber());
    }

    private Statement parseCameraStatement(Token token) {
        String rest = stripKeyword(token.text(), "camera", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.isEmpty()) {
            throw error(token, "Expected subcommand after 'camera' (follow <tag>, clamp <x1 y1 x2 y2>, or stop)");
        }
        String sub = parts.get(0).toLowerCase(Locale.ROOT);
        if ("stop".equals(sub)) {
            return new CameraFollowStatement(null, 0.0, token.sourceFile(), token.lineNumber());
        }
        if ("clamp".equals(sub)) {
            if (parts.size() != 5) {
                throw error(token, "Expected 'camera clamp <x1> <y1> <x2> <y2>', got: " + token.text());
            }
            double minX = parseDoubleStrict(parts.get(1), token, "camera clamp x1");
            double minY = parseDoubleStrict(parts.get(2), token, "camera clamp y1");
            double maxX = parseDoubleStrict(parts.get(3), token, "camera clamp x2");
            double maxY = parseDoubleStrict(parts.get(4), token, "camera clamp y2");
            return new CameraClampStatement(minX, minY, maxX, maxY, token.sourceFile(), token.lineNumber());
        }
        if (!"follow".equals(sub) || parts.size() < 2) {
            throw error(token, "Expected 'camera follow <tag> [smooth <factor>]', got: " + token.text());
        }
        String targetTag = parts.get(1);
        double smooth = 5.0;
        int i = 2;
        while (i < parts.size()) {
            String opt = parts.get(i).toLowerCase(Locale.ROOT);
            if ("smooth".equals(opt)) {
                if (i + 1 >= parts.size()) {
                    throw error(token, "Expected numeric factor after 'smooth'");
                }
                smooth = parseDoubleStrict(parts.get(i + 1), token, "camera smooth");
                i += 2;
            } else {
                throw error(token, "Unknown option in 'camera follow': " + parts.get(i));
            }
        }
        return new CameraFollowStatement(targetTag, smooth, token.sourceFile(), token.lineNumber());
    }

    private PatrolStatement parsePatrolStatement(Token token) {
        String rest = stripKeyword(token.text(), "patrol", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() < 6 || !"between".equalsIgnoreCase(parts.get(1))) {
            throw error(token, "Expected 'patrol <tag> between <x1> <y1> <x2> <y2> [speed <s>] [loop <bool>]'");
        }
        String tag = parts.get(0);
        double x1 = parseDoubleStrict(parts.get(2), token, "patrol x1");
        double y1 = parseDoubleStrict(parts.get(3), token, "patrol y1");
        double x2 = parseDoubleStrict(parts.get(4), token, "patrol x2");
        double y2 = parseDoubleStrict(parts.get(5), token, "patrol y2");
        double speed = 150.0;
        boolean pingPong = true;
        int i = 6;
        while (i < parts.size()) {
            String opt = parts.get(i).toLowerCase(Locale.ROOT);
            if ("speed".equals(opt) && i + 1 < parts.size()) {
                speed = parseDoubleStrict(parts.get(i + 1), token, "patrol speed");
                i += 2;
            } else if ("loop".equals(opt) && i + 1 < parts.size()) {
                pingPong = !parseBooleanStrict(parts.get(i + 1), token, "patrol loop");
                i += 2;
            } else {
                throw error(token, "Unknown option in 'patrol': " + parts.get(i));
            }
        }
        return new PatrolStatement(tag, x1, y1, x2, y2, speed, pingPong, token.sourceFile(), token.lineNumber());
    }

    private FollowStatement parseFollowStatement(Token token) {
        String rest = stripKeyword(token.text(), "follow", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() < 3 || !"target".equalsIgnoreCase(parts.get(1))) {
            throw error(token, "Expected 'follow <tag> target <targetTag> [speed <s>] [stop_radius <r>]'");
        }
        String tag = parts.get(0);
        String target = parts.get(2);
        double speed = 180.0;
        double stopRadius = 60.0;
        double resumeRadius = 90.0;
        int i = 3;
        while (i < parts.size()) {
            String opt = parts.get(i).toLowerCase(Locale.ROOT);
            if ("speed".equals(opt) && i + 1 < parts.size()) {
                speed = parseDoubleStrict(parts.get(i + 1), token, "follow speed");
                i += 2;
            } else if ("stop_radius".equals(opt) && i + 1 < parts.size()) {
                stopRadius = parseDoubleStrict(parts.get(i + 1), token, "stop_radius");
                resumeRadius = Math.max(resumeRadius, stopRadius + 20.0);
                i += 2;
            } else if ("resume_radius".equals(opt) && i + 1 < parts.size()) {
                resumeRadius = parseDoubleStrict(parts.get(i + 1), token, "resume_radius");
                i += 2;
            } else {
                throw error(token, "Unknown option in 'follow': " + parts.get(i));
            }
        }
        return new FollowStatement(tag, target, speed, stopRadius, resumeRadius,
                token.sourceFile(), token.lineNumber());
    }

    private AfterTimerStatement parseAfterTimerStatement(Token token) {
        String rest = stripKeyword(token.text(), "after", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() != 3) {
            throw error(token, "Expected 'after <seconds> <jump|call> <label>'");
        }
        double sec = parseDoubleStrict(parts.get(0), token, "after delay");
        String action = parts.get(1).toLowerCase(Locale.ROOT);
        if (!"jump".equals(action) && !"call".equals(action)) {
            throw error(token, "Expected 'jump' or 'call' in 'after', got: " + parts.get(1));
        }
        return new AfterTimerStatement(sec, action, parts.get(2), token.sourceFile(), token.lineNumber());
    }

    private OnInteractStatement parseOnInteractStatement(Token token) {
        String rest = stripKeyword(token.text(), "on_interact", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() != 4) {
            throw error(token, "Expected 'on_interact <tag> <dist> <jump|call> <label>'");
        }
        double dist = parseDoubleStrict(parts.get(1), token, "on_interact distance");
        String action = parts.get(2).toLowerCase(Locale.ROOT);
        if (!"jump".equals(action) && !"call".equals(action)) {
            throw error(token, "Expected 'jump' or 'call' in 'on_interact', got: " + parts.get(2));
        }
        return new OnInteractStatement(parts.get(0), dist, action, parts.get(3),
                token.sourceFile(), token.lineNumber());
    }

    private RepeatStatement parseRepeatStatement(Token token) {
        String countExpr = stripRequiredColon(stripKeyword(token.text(), "repeat", token), token);
        if (countExpr.isEmpty()) {
            throw error(token, "Expected repetition count after 'repeat'");
        }
        expectIndent(token);
        List<Statement> body = parseStatementBlock(token);
        return new RepeatStatement(countExpr, body, token.sourceFile(), token.lineNumber());
    }

    private WhileStatement parseWhileStatement(Token token) {
        String condExpr = stripRequiredColon(stripKeyword(token.text(), "while", token), token);
        if (condExpr.isEmpty()) {
            throw error(token, "Expected condition expression after 'while'");
        }
        expectIndent(token);
        List<Statement> body = parseStatementBlock(token);
        return new WhileStatement(condExpr, body, token.sourceFile(), token.lineNumber());
    }

    private AnimStateStatement parseAnimStateStatement(Token token) {
        String rest = stripKeyword(token.text(), "anim_state", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.isEmpty()) {
            throw error(token, "Expected 'anim_state <tag> idle <a1> walk <a2> jump <a3>'");
        }
        String tag = parts.get(0);
        String idle = "idle";
        String walk = "walk";
        String jump = "jump";
        int i = 1;
        while (i + 1 < parts.size()) {
            switch (parts.get(i).toLowerCase(Locale.ROOT)) {
                case "idle" -> idle = parts.get(i + 1);
                case "walk" -> walk = parts.get(i + 1);
                case "jump" -> jump = parts.get(i + 1);
                default -> throw error(token, "Unknown option in 'anim_state': " + parts.get(i));
            }
            i += 2;
        }
        return new AnimStateStatement(tag, idle, walk, jump, token.sourceFile(), token.lineNumber());
    }

    private ColliderWallStatement parseColliderWallStatement(Token token) {
        String raw = token.text();
        boolean hasBlock = endsWithColonOutsideQuotes(raw);
        String header = hasBlock ? raw.substring(0, raw.length() - 1).trim() : raw;
        String rest = stripKeyword(header, "collider", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() < 2) {
            throw error(token, "Expected 'collider wall <id> <x> <y> <w> <h>'");
        }
        String kind = parts.get(0).toLowerCase(Locale.ROOT);
        String id = parts.get(1);
        List<ColliderBox> boxes = new ArrayList<>();
        if ("wall".equals(kind) && parts.size() == 6) {
            boxes.add(new ColliderBox(
                    parseDoubleStrict(parts.get(2), token, "wall x"),
                    parseDoubleStrict(parts.get(3), token, "wall y"),
                    parseDoubleStrict(parts.get(4), token, "wall w"),
                    parseDoubleStrict(parts.get(5), token, "wall h")));
        } else if (hasBlock && consumeOptionalIndent()) {
            while (!isAtEnd() && peek().type() != TokenType.DEDENT) {
                Token boxTok = expectLine("collider box list");
                List<String> bParts = splitWhitespaceTopLevel(boxTok.text());
                int off = "box".equalsIgnoreCase(bParts.get(0)) || "rect".equalsIgnoreCase(bParts.get(0)) ? 1 : 0;
                if (bParts.size() < off + 4) {
                    throw error(boxTok, "Expected '<x> <y> <w> <h>' in collider block");
                }
                boxes.add(new ColliderBox(
                        parseDoubleStrict(bParts.get(off), boxTok, "box x"),
                        parseDoubleStrict(bParts.get(off + 1), boxTok, "box y"),
                        parseDoubleStrict(bParts.get(off + 2), boxTok, "box w"),
                        parseDoubleStrict(bParts.get(off + 3), boxTok, "box h")));
            }
            consumeRequiredDedent(token);
        } else {
            throw error(token, "Malformed 'collider' statement: " + token.text());
        }
        return new ColliderWallStatement(id, boxes, token.sourceFile(), token.lineNumber());
    }

    private ShakeLayerStatement parseShakeLayerStatement(Token token) {
        String rest = stripKeyword(token.text(), "shake", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.isEmpty()) {
            throw error(token, "Expected 'shake <layer> amp <px> dur <s>'");
        }
        String layer = parts.get(0);
        double amp = 20.0;
        double dur = 0.4;
        int i = 1;
        while (i + 1 < parts.size()) {
            String opt = parts.get(i).toLowerCase(Locale.ROOT);
            if ("amp".equals(opt)) {
                amp = parseDoubleStrict(parts.get(i + 1), token, "shake amp");
                i += 2;
            } else if ("dur".equals(opt) || "duration".equals(opt)) {
                dur = parseDoubleStrict(parts.get(i + 1), token, "shake dur");
                i += 2;
            } else {
                throw error(token, "Unknown option in 'shake': " + parts.get(i));
            }
        }
        return new ShakeLayerStatement(layer, amp, dur, token.sourceFile(), token.lineNumber());
    }

    private KnockbackStatement parseKnockbackStatement(Token token) {
        String rest = stripKeyword(token.text(), "knockback", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() < 5) {
            throw error(token, "Expected 'knockback <tag> vx <x> vy <y> [invuln <s>]'");
        }
        String tag = parts.get(0);
        double vx = 0.0;
        double vy = 0.0;
        double invuln = 0.5;
        int i = 1;
        while (i + 1 < parts.size()) {
            switch (parts.get(i).toLowerCase(Locale.ROOT)) {
                case "vx" -> vx = parseDoubleStrict(parts.get(i + 1), token, "knockback vx");
                case "vy" -> vy = parseDoubleStrict(parts.get(i + 1), token, "knockback vy");
                case "invuln" -> invuln = parseDoubleStrict(parts.get(i + 1), token, "knockback invuln");
                default -> throw error(token, "Unknown option in 'knockback': " + parts.get(i));
            }
            i += 2;
        }
        return new KnockbackStatement(tag, vx, vy, invuln, token.sourceFile(), token.lineNumber());
    }

    private ClimbZoneStatement parseClimbZoneStatement(Token token) {
        String rest = stripKeyword(token.text(), "climb_zone", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() != 6 || !"rect".equalsIgnoreCase(parts.get(1))) {
            throw error(token, "Expected 'climb_zone <id> rect <x> <y> <w> <h>'");
        }
        return new ClimbZoneStatement(
                parts.get(0),
                parseDoubleStrict(parts.get(2), token, "climb_zone x"),
                parseDoubleStrict(parts.get(3), token, "climb_zone y"),
                parseDoubleStrict(parts.get(4), token, "climb_zone w"),
                parseDoubleStrict(parts.get(5), token, "climb_zone h"),
                token.sourceFile(), token.lineNumber());
    }

    private MovingPlatformStatement parseMovingPlatformStatement(Token token) {
        String rest = stripKeyword(token.text(), "moving_platform", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() < 6 || !"between".equalsIgnoreCase(parts.get(1))) {
            throw error(token, "Expected 'moving_platform <tag> between <x1> <y1> <x2> <y2> [speed <s>] [one_way]'");
        }
        String tag = parts.get(0);
        double x1 = parseDoubleStrict(parts.get(2), token, "moving_platform x1");
        double y1 = parseDoubleStrict(parts.get(3), token, "moving_platform y1");
        double x2 = parseDoubleStrict(parts.get(4), token, "moving_platform x2");
        double y2 = parseDoubleStrict(parts.get(5), token, "moving_platform y2");
        double speed = 120.0;
        boolean oneWay = false;
        int i = 6;
        while (i < parts.size()) {
            String opt = parts.get(i).toLowerCase(Locale.ROOT);
            if ("speed".equals(opt) && i + 1 < parts.size()) {
                speed = parseDoubleStrict(parts.get(i + 1), token, "moving_platform speed");
                i += 2;
            } else if ("one_way".equals(opt)) {
                oneWay = true;
                i++;
            } else {
                throw error(token, "Unknown option in 'moving_platform': " + parts.get(i));
            }
        }
        return new MovingPlatformStatement(tag, x1, y1, x2, y2, speed, oneWay,
                token.sourceFile(), token.lineNumber());
    }

    private AttractStatement parseAttractStatement(Token token) {
        String rest = stripKeyword(token.text(), "attract", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() < 5 || !"target".equalsIgnoreCase(parts.get(1))) {
            throw error(token, "Expected 'attract <tag> target <player> radius <r> [speed <s>]'");
        }
        String tag = parts.get(0);
        String target = parts.get(2);
        double radius = 150.0;
        double speed = 240.0;
        int i = 3;
        while (i + 1 < parts.size()) {
            switch (parts.get(i).toLowerCase(Locale.ROOT)) {
                case "radius" -> radius = parseDoubleStrict(parts.get(i + 1), token, "attract radius");
                case "speed" -> speed = parseDoubleStrict(parts.get(i + 1), token, "attract speed");
                default -> throw error(token, "Unknown option in 'attract': " + parts.get(i));
            }
            i += 2;
        }
        return new AttractStatement(tag, target, radius, speed, token.sourceFile(), token.lineNumber());
    }

    private SpawnStatement parseSpawnStatement(Token token) {
        String rest = stripKeyword(token.text(), "spawn", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() < 2) {
            throw error(token, "Expected 'spawn <tag> <assetOrTag> [at <x> <y>]'");
        }
        String tag = parts.get(0);
        String asset = unquoteIfSingleStringLiteral(parts.get(1), token);
        Double x = null;
        Double y = null;
        if (parts.size() >= 5 && "at".equalsIgnoreCase(parts.get(2))) {
            x = parseDoubleStrict(parts.get(3), token, "spawn x");
            y = parseDoubleStrict(parts.get(4), token, "spawn y");
        }
        return new SpawnStatement(tag, asset, x, y, token.sourceFile(), token.lineNumber());
    }

    private DespawnStatement parseDespawnStatement(Token token) {
        String rest = stripKeyword(token.text(), "despawn", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() != 1) {
            throw error(token, "Expected 'despawn <tag>'");
        }
        return new DespawnStatement(parts.get(0), token.sourceFile(), token.lineNumber());
    }

    private OnTweenEndStatement parseOnTweenEndStatement(Token token) {
        String rest = stripKeyword(token.text(), "on_tween_end", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() != 3) {
            throw error(token, "Expected 'on_tween_end <tag> <jump|call> <label>'");
        }
        String action = parts.get(1).toLowerCase(Locale.ROOT);
        if (!"jump".equals(action) && !"call".equals(action)) {
            throw error(token, "Expected 'jump' or 'call' in 'on_tween_end'");
        }
        return new OnTweenEndStatement(parts.get(0), action, parts.get(2),
                token.sourceFile(), token.lineNumber());
    }

    private OnEventStatement parseOnEventStatement(Token token) {
        String rest = stripKeyword(token.text(), "on_event", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() != 3) {
            throw error(token, "Expected 'on_event \"<name>\" <call|jump> <label>'");
        }
        String eventName = unquoteIfSingleStringLiteral(parts.get(0), token);
        String action = parts.get(1).toLowerCase(Locale.ROOT);
        if (!"call".equals(action) && !"jump".equals(action)) {
            throw error(token, "Expected 'call' or 'jump' in 'on_event'");
        }
        return new OnEventStatement(eventName, action, parts.get(2), token.sourceFile(), token.lineNumber());
    }

    private SpawnCutsceneStatement parseSpawnCutsceneStatement(Token token) {
        String label = stripKeyword(token.text(), "spawn_cutscene", token).trim();
        if (label.isEmpty()) {
            throw error(token, "Expected label name after 'spawn_cutscene'");
        }
        return new SpawnCutsceneStatement(label, token.sourceFile(), token.lineNumber());
    }

    private RandomStatement parseRandomStatement(Token token) {
        String extra = stripRequiredColon(stripKeywordSafe(token.text(), "random"), token);
        if (!extra.isEmpty()) {
            throw error(token, "Unexpected arguments in 'random:' header");
        }
        expectIndent(token);
        List<RandomBranch> branches = new ArrayList<>();
        while (!isAtEnd() && peek().type() != TokenType.DEDENT) {
            Token armTok = expectLine("random branch");
            String header = stripRequiredColon(armTok.text(), armTok);
            List<String> parts = splitWhitespaceTopLevel(header);
            String weightStr = "weight".equalsIgnoreCase(parts.get(0)) && parts.size() >= 2
                    ? parts.get(1) : parts.get(0);
            double weight = parseDoubleStrict(weightStr, armTok, "random weight");
            expectIndent(armTok);
            List<Statement> body = parseStatementBlock(armTok);
            branches.add(new RandomBranch(weight, body, armTok.sourceFile(), armTok.lineNumber()));
        }
        consumeRequiredDedent(token);
        if (branches.isEmpty()) {
            throw error(token, "Empty 'random:' block requires at least one weighted branch");
        }
        return new RandomStatement(branches, token.sourceFile(), token.lineNumber());
    }

    private SetBlockStatement parseSetBlockStatement(Token token) {
        String extra = stripRequiredColon(stripKeywordSafe(token.text(), "set"), token);
        if (!extra.isEmpty()) {
            throw error(token, "Unexpected arguments in 'set:' header");
        }
        expectIndent(token);
        List<SetVarStatement> assignments = new ArrayList<>();
        while (!isAtEnd() && peek().type() != TokenType.DEDENT) {
            Token lineTok = expectLine("set block");
            String raw = lineTok.text().startsWith("$") ? lineTok.text().substring(1).trim() : lineTok.text();
            assignments.add(parseAssignmentExpression(raw, false, lineTok));
        }
        consumeRequiredDedent(token);
        return new SetBlockStatement(assignments, token.sourceFile(), token.lineNumber());
    }

    private LocalVarStatement parseLocalVarStatement(Token token) {
        String rest = stripKeyword(token.text(), "local", token);
        String clean = rest.startsWith("$") ? rest.substring(1).trim() : rest;
        SetVarStatement parsed = parseAssignmentExpression(clean, false, token);
        return new LocalVarStatement(parsed.varName(), parsed.expression(),
                token.sourceFile(), token.lineNumber());
    }

    private TimeScaleStatement parseTimeScaleStatement(Token token) {
        String rest = stripKeyword(token.text(), "time_scale", token);
        double factor = parseDoubleStrict(rest, token, "time_scale");
        return new TimeScaleStatement(factor, token.sourceFile(), token.lineNumber());
    }

    private AttachStatement parseAttachStatement(Token token) {
        String rest = stripKeyword(token.text(), "attach", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() < 3 || !"to".equalsIgnoreCase(parts.get(1))) {
            throw error(token, "Expected 'attach <child> to <parent> [offset <x> <y>]'");
        }
        String child = parts.get(0);
        String parent = parts.get(2);
        double ox = 0.0;
        double oy = 0.0;
        if (parts.size() >= 6 && "offset".equalsIgnoreCase(parts.get(3))) {
            ox = parseDoubleStrict(parts.get(4), token, "attach offsetX");
            oy = parseDoubleStrict(parts.get(5), token, "attach offsetY");
        }
        return new AttachStatement(child, parent, ox, oy, token.sourceFile(), token.lineNumber());
    }

    private YSortStatement parseYSortStatement(Token token) {
        String rest = stripKeyword(token.text(), "ysort", token);
        boolean enabled = parseBooleanStrict(rest, token, "ysort");
        return new YSortStatement(enabled, token.sourceFile(), token.lineNumber());
    }

    private PointClickInitStatement parsePointClickInitStatement(Token token) {
        String rest = stripKeyword(token.text(), "point_click_init", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.isEmpty()) {
            throw error(token, "Expected sprite tag after 'point_click_init'");
        }
        String tag = parts.get(0);
        double speed = 260.0;
        Double minX = null;
        Double minY = null;
        Double maxX = null;
        Double maxY = null;
        int i = 1;
        while (i < parts.size()) {
            String opt = parts.get(i).toLowerCase(Locale.ROOT);
            if ("speed".equals(opt) && i + 1 < parts.size()) {
                speed = parseDoubleStrict(parts.get(i + 1), token, "point_click_init speed");
                i += 2;
            } else if ("bounds".equals(opt) && i + 4 < parts.size()) {
                minX = parseDoubleStrict(parts.get(i + 1), token, "bounds x1");
                minY = parseDoubleStrict(parts.get(i + 2), token, "bounds y1");
                maxX = parseDoubleStrict(parts.get(i + 3), token, "bounds x2");
                maxY = parseDoubleStrict(parts.get(i + 4), token, "bounds y2");
                i += 5;
            } else {
                throw error(token, "Unknown option in 'point_click_init': " + parts.get(i));
            }
        }
        return new PointClickInitStatement(tag, speed, minX, minY, maxX, maxY,
                token.sourceFile(), token.lineNumber());
    }

    private SurfaceFrictionStatement parseSurfaceFrictionStatement(Token token) {
        String rest = stripKeyword(token.text(), "surface_friction", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() < 8 || !"rect".equalsIgnoreCase(parts.get(1))
                || !"factor".equalsIgnoreCase(parts.get(6))) {
            throw error(token, "Expected 'surface_friction <id> rect <x> <y> <w> <h> factor <f>'");
        }
        return new SurfaceFrictionStatement(
                parts.get(0),
                parseDoubleStrict(parts.get(2), token, "surface_friction x"),
                parseDoubleStrict(parts.get(3), token, "surface_friction y"),
                parseDoubleStrict(parts.get(4), token, "surface_friction w"),
                parseDoubleStrict(parts.get(5), token, "surface_friction h"),
                parseDoubleStrict(parts.get(7), token, "surface_friction factor"),
                token.sourceFile(), token.lineNumber());
    }

    private ImpulseStatement parseImpulseStatement(Token token) {
        String rest = stripKeyword(token.text(), "impulse", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() < 5) {
            throw error(token, "Expected 'impulse <tag> vx <x> vy <y>'");
        }
        String tag = parts.get(0);
        double vx = 0.0;
        double vy = 0.0;
        int i = 1;
        while (i + 1 < parts.size()) {
            switch (parts.get(i).toLowerCase(Locale.ROOT)) {
                case "vx" -> vx = parseDoubleStrict(parts.get(i + 1), token, "impulse vx");
                case "vy" -> vy = parseDoubleStrict(parts.get(i + 1), token, "impulse vy");
                default -> throw error(token, "Unknown option in 'impulse': " + parts.get(i));
            }
            i += 2;
        }
        return new ImpulseStatement(tag, vx, vy, token.sourceFile(), token.lineNumber());
    }

    private Scene3DStatement parseScene3DStatement(Token token) {
        String rest = stripKeyword(token.text(), "scene3d", token).trim();
        if (rest.isEmpty()) {
            throw error(token, "Expected 'scene3d <prefabOrAsset>'");
        }
        List<String> parts = splitWhitespaceTopLevel(rest);
        return new Scene3DStatement(
                unquoteIfSingleStringLiteral(parts.get(0), token),
                token.sourceFile(),
                token.lineNumber()
        );
    }

    private Spawn3DStatement parseSpawn3DStatement(Token token) {
        String rest = stripKeyword(token.text(), "spawn3d", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        int atIdx = -1;
        for (int i = 0; i < parts.size(); i++) {
            if ("at".equalsIgnoreCase(parts.get(i))) {
                atIdx = i;
                break;
            }
        }
        if (atIdx < 1 || atIdx + 3 >= parts.size()) {
            throw error(token, "Expected 'spawn3d [<id>] <mesh> at <x> <y> <z>'");
        }
        String id = atIdx >= 2
                ? parts.get(0)
                : unquoteIfSingleStringLiteral(parts.get(0), token);
        String mesh = atIdx >= 2
                ? unquoteIfSingleStringLiteral(parts.get(1), token)
                : unquoteIfSingleStringLiteral(parts.get(0), token);
        double x = parseDoubleStrict(parts.get(atIdx + 1), token, "spawn3d x");
        double y = parseDoubleStrict(parts.get(atIdx + 2), token, "spawn3d y");
        double z = parseDoubleStrict(parts.get(atIdx + 3), token, "spawn3d z");
        return new Spawn3DStatement(id, mesh, x, y, z, token.sourceFile(), token.lineNumber());
    }

    private Camera3DStatement parseCamera3DStatement(Token token) {
        String rest = stripKeyword(token.text(), "camera3d", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() < 4) {
            throw error(token, "Expected 'camera3d <move|look_at> <x> <y> <z> [duration <s>]'");
        }
        String mode = parts.get(0).toLowerCase(Locale.ROOT);
        if (!"move".equals(mode) && !"look_at".equals(mode)) {
            throw error(token, "Unknown camera3d mode '" + parts.get(0) + "', expected 'move' or 'look_at'");
        }
        double x = parseDoubleStrict(parts.get(1), token, "camera3d x");
        double y = parseDoubleStrict(parts.get(2), token, "camera3d y");
        double z = parseDoubleStrict(parts.get(3), token, "camera3d z");
        double duration = 0.0;
        int i = 4;
        while (i + 1 < parts.size()) {
            if ("duration".equalsIgnoreCase(parts.get(i))) {
                duration = parseDoubleStrict(parts.get(i + 1), token, "camera3d duration");
            }
            i += 2;
        }
        return new Camera3DStatement(mode, x, y, z, duration, token.sourceFile(), token.lineNumber());
    }

    private Wasd3DInitStatement parseWasd3DInitStatement(Token token) {
        String rest = stripKeyword(token.text(), "wasd3d_init", token);
        List<String> parts = rest.isEmpty() ? List.of() : splitWhitespaceTopLevel(rest);
        double speed = 5.0;
        double fov = 70.0;
        int i = 0;
        while (i + 1 < parts.size()) {
            String k = parts.get(i).toLowerCase(Locale.ROOT);
            if ("speed".equals(k)) {
                speed = parseDoubleStrict(parts.get(i + 1), token, "wasd3d_init speed");
            } else if ("fov".equals(k)) {
                fov = parseDoubleStrict(parts.get(i + 1), token, "wasd3d_init fov");
            }
            i += 2;
        }
        return new Wasd3DInitStatement(speed, fov, token.sourceFile(), token.lineNumber());
    }

    private Fog3DStatement parseFog3DStatement(Token token) {
        String rest = stripKeyword(token.text(), "fog3d", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.isEmpty()) {
            throw error(token, "Expected 'fog3d <hexColor> [density <d>] [falloff <f>]'");
        }
        String colorHex = unquoteIfSingleStringLiteral(parts.get(0), token);
        double density = 0.03;
        double falloff = 0.1;
        int i = 1;
        while (i + 1 < parts.size()) {
            String k = parts.get(i).toLowerCase(Locale.ROOT);
            if ("density".equals(k)) {
                density = parseDoubleStrict(parts.get(i + 1), token, "fog3d density");
            } else if ("falloff".equals(k)) {
                falloff = parseDoubleStrict(parts.get(i + 1), token, "fog3d falloff");
            }
            i += 2;
        }
        return new Fog3DStatement(colorHex, density, falloff, token.sourceFile(), token.lineNumber());
    }

    private Material3DStatement parseMaterial3DStatement(Token token) {
        String rest = stripKeyword(token.text(), "material3d", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() < 3) {
            throw error(token, "Expected 'material3d <id> tint <hex> [roughness <r>] [metallic <m>]'");
        }
        String id = parts.get(0);
        String tintHex = "#ffffff";
        double roughness = -1.0;
        double metallic = -1.0;
        int i = 1;
        while (i + 1 < parts.size()) {
            String k = parts.get(i).toLowerCase(Locale.ROOT);
            switch (k) {
                case "tint" -> tintHex = unquoteIfSingleStringLiteral(parts.get(i + 1), token);
                case "roughness" -> roughness = parseDoubleStrict(parts.get(i + 1), token, "material3d roughness");
                case "metallic" -> metallic = parseDoubleStrict(parts.get(i + 1), token, "material3d metallic");
                default -> throw error(token, "Unknown option in 'material3d': " + parts.get(i));
            }
            i += 2;
        }
        return new Material3DStatement(id, tintHex, roughness, metallic, token.sourceFile(), token.lineNumber());
    }

    private TriggerZoneStatement parseTriggerZoneStatement(Token token) {
        String rest = stripKeyword(token.text(), "trigger_zone", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() != 8 || !"rect".equalsIgnoreCase(parts.get(1))) {
            throw error(token,
                    "Expected 'trigger_zone <id> rect <x> <y> <w> <h> <call|jump> <label>', got: " + token.text());
        }
        String zoneId = parts.get(0);
        double x = parseDoubleStrict(parts.get(2), token, "trigger_zone x");
        double y = parseDoubleStrict(parts.get(3), token, "trigger_zone y");
        double w = parseDoubleStrict(parts.get(4), token, "trigger_zone width");
        double h = parseDoubleStrict(parts.get(5), token, "trigger_zone height");
        if (!Double.isFinite(w) || !Double.isFinite(h) || w <= 0.0 || h <= 0.0) {
            throw error(token, "trigger_zone dimensions must be positive");
        }
        String action = parts.get(6).toLowerCase(Locale.ROOT);
        if (!"call".equals(action) && !"jump".equals(action)) {
            throw error(token, "trigger_zone action must be 'call' or 'jump', got: " + parts.get(6));
        }
        String targetLabel = parts.get(7);
        return new TriggerZoneStatement(zoneId, x, y, w, h, action, targetLabel,
                token.sourceFile(), token.lineNumber());
    }

    private MatchStatement parseMatchStatement(Token matchToken) {
        String rawAfterKw = stripKeywordSafe(matchToken.text(), "match");
        if (!endsWithColonOutsideQuotes(rawAfterKw)) {
            throw error(matchToken, "Expected ':' at end of 'match' header: " + matchToken.text());
        }
        String subjectExpr = stripRequiredColon(rawAfterKw, matchToken);
        if (subjectExpr.isEmpty()) {
            throw error(matchToken, "Expected expression after 'match'");
        }
        expectIndent(matchToken);
        List<MatchCase> cases = new ArrayList<>();
        List<Statement> defaultBody = null;
        try {
            while (!isAtEnd() && peek().type() != TokenType.DEDENT) {
                Token armTok = expectLine("match body");
                String armKw = firstWord(armTok.text());
                if ("case".equals(armKw)) {
                    String afterCase = stripKeywordSafe(armTok.text(), "case");
                    if (!endsWithColonOutsideQuotes(afterCase)) {
                        throw error(armTok, "Expected ':' after 'case' pattern: " + armTok.text());
                    }
                    String patternExpr = stripRequiredColon(afterCase, armTok);
                    if (patternExpr.isEmpty()) {
                        throw error(armTok, "Expected pattern expression after 'case'");
                    }
                    expectIndent(armTok);
                    cases.add(new MatchCase(
                            patternExpr, parseStatementBlock(armTok), armTok.sourceFile(), armTok.lineNumber()));
                } else if ("default".equals(armKw)) {
                    String afterDefault = stripKeywordSafe(armTok.text(), "default");
                    if (!endsWithColonOutsideQuotes(afterDefault)) {
                        throw error(armTok, "Expected ':' after 'default': " + armTok.text());
                    }
                    String extra = stripRequiredColon(afterDefault, armTok);
                    if (!extra.isEmpty()) {
                        throw error(armTok, "Unexpected expression after 'default': " + extra);
                    }
                    if (defaultBody != null) {
                        throw error(armTok, "Duplicate 'default:' branch in 'match' statement");
                    }
                    expectIndent(armTok);
                    defaultBody = parseStatementBlock(armTok);
                } else {
                    throw error(armTok, "Expected 'case <expr>:' or 'default:' inside 'match' block, got: "
                            + armTok.text());
                }
            }
            consumeRequiredDedent(matchToken);
        } finally {
            // Note: blockDepth is managed per parseStatementBlock call
        }
        if (cases.isEmpty() && defaultBody == null) {
            throw error(matchToken, "Empty 'match' block requires at least one 'case' or 'default' branch");
        }
        return new MatchStatement(subjectExpr, cases, defaultBody, matchToken.sourceFile(), matchToken.lineNumber());
    }

    private PauseStatement parsePauseStatement(Token token) {
        String rest = token.text().length() > "pause".length()
                ? token.text().substring("pause".length()).trim()
                : "";
        if (rest.isEmpty() || "hard".equals(rest)) {
            return new PauseStatement(null, token.sourceFile(), token.lineNumber());
        }
        double seconds = parseDoubleStrict(rest, token, "pause");
        return new PauseStatement(seconds, token.sourceFile(), token.lineNumber());
    }

    private JumpStatement parseJumpStatement(Token token) {
        String rest = stripKeyword(token.text(), "jump", token);
        if (rest.startsWith("expression ")) {
            String expr = rest.substring("expression ".length()).trim();
            if (expr.isEmpty()) {
                throw error(token, "Expected target expression after 'jump expression'");
            }
            return new JumpStatement(expr, true, token.sourceFile(), token.lineNumber());
        }
        if (rest.isEmpty()) {
            throw error(token, "Expected target label after 'jump'");
        }
        return new JumpStatement(rest, false, token.sourceFile(), token.lineNumber());
    }

    private CallStatement parseCallStatement(Token token) {
        String rest = stripKeyword(token.text(), "call", token);
        if (rest.isEmpty()) {
            throw error(token, "Expected target label after 'call'");
        }
        int openParen = findCharOutsideQuotesAndBrackets(rest, '(');
        String targetLabel;
        List<String> args = new ArrayList<>();
        if (openParen >= 0) {
            int closeParen = findMatchingClosingParen(rest, openParen);
            if (closeParen < 0) {
                throw error(token, "Unclosed '(' in call statement: " + token.text());
            }
            targetLabel = rest.substring(0, openParen).trim();
            String inside = rest.substring(openParen + 1, closeParen).trim();
            if (!inside.isEmpty()) {
                for (String arg : splitTopLevelComma(inside)) {
                    if (!arg.isEmpty()) {
                        args.add(arg);
                    }
                }
            }
        } else {
            List<String> parts = splitWhitespaceTopLevel(rest);
            targetLabel = parts.get(0);
            if ("expression".equals(targetLabel) && parts.size() >= 2) {
                targetLabel = String.join(" ", parts.subList(1, parts.size()));
            }
        }
        if (targetLabel.isEmpty()) {
            throw error(token, "Target label in 'call' must not be empty");
        }
        return new CallStatement(targetLabel, args, token.sourceFile(), token.lineNumber());
    }

    private ReturnStatement parseReturnStatement(Token token) {
        String rest = token.text().length() > "return".length()
                ? token.text().substring("return".length()).trim()
                : "";
        String expr = rest.isEmpty() ? null : rest;
        return new ReturnStatement(expr, token.sourceFile(), token.lineNumber());
    }

    private InputStatement parseInputStatement(Token token) {
        String rest = stripKeyword(token.text(), "input", token);
        List<String> parts = splitWhitespaceTopLevel(rest);
        if (parts.size() < 2) {
            throw error(token, "Expected 'input <var> \"<prompt>\"', got: " + token.text());
        }
        String targetVar = parts.get(0);
        String prompt = unquoteIfSingleStringLiteral(parts.get(1), token);
        String defaultValue = null;
        String allowChars = null;
        int maxLength = 0;

        Map<String, String> opts = new LinkedHashMap<>();
        parseKeyValueTokens(parts.subList(2, parts.size()), opts, token);
        for (Map.Entry<String, String> entry : opts.entrySet()) {
            switch (entry.getKey()) {
                case "default" -> defaultValue = entry.getValue();
                case "allow" -> allowChars = entry.getValue();
                case "length" -> maxLength = parseIntStrict(entry.getValue(), token, "input length");
                default -> throw error(token, "Unknown option in 'input' statement: " + entry.getKey());
            }
        }

        return new InputStatement(
                targetVar,
                prompt,
                defaultValue,
                allowChars,
                maxLength,
                token.sourceFile(),
                token.lineNumber()
        );
    }

    private IfStatement parseIfStatement(Token ifToken) {
        List<IfBranch> branches = new ArrayList<>();
        String ifCond = stripRequiredColon(stripKeyword(ifToken.text(), "if", ifToken), ifToken);
        if (ifCond.isEmpty()) {
            throw error(ifToken, "Expected condition expression after 'if'");
        }
        expectIndent(ifToken);
        branches.add(new IfBranch(ifCond, parseStatementBlock(ifToken)));

        while (!isAtEnd() && peek().type() == TokenType.LINE) {
            String nextText = peek().text();
            String kw = firstWord(nextText);
            if ("elif".equals(kw)) {
                Token elifTok = consume();
                String elifCond = stripRequiredColon(stripKeyword(elifTok.text(), "elif", elifTok), elifTok);
                if (elifCond.isEmpty()) {
                    throw error(elifTok, "Expected condition expression after 'elif'");
                }
                expectIndent(elifTok);
                branches.add(new IfBranch(elifCond, parseStatementBlock(elifTok)));
            } else if ("else".equals(kw)) {
                Token elseTok = consume();
                String extra = stripRequiredColon(stripKeywordSafe(elseTok.text(), "else"), elseTok);
                if (!extra.isEmpty()) {
                    throw error(elseTok, "Unexpected expression after 'else'");
                }
                expectIndent(elseTok);
                branches.add(new IfBranch(null, parseStatementBlock(elseTok)));
                break;
            } else {
                break;
            }
        }

        return new IfStatement(branches, ifToken.sourceFile(), ifToken.lineNumber());
    }

    private MenuStatement parseMenuStatement(Token menuToken) {
        String afterMenu = stripRequiredColon(stripKeywordSafe(menuToken.text(), "menu"), menuToken);
        String caption = null;
        if (!afterMenu.isEmpty() && afterMenu.startsWith("\"")) {
            caption = extractLeadingQuotedString(afterMenu, menuToken).value();
        }

        Double timeoutSeconds = null;
        String timeoutJumpLabel = null;
        List<MenuChoice> choices = new ArrayList<>();

        expectIndent(menuToken);
        while (!isAtEnd() && peek().type() != TokenType.DEDENT) {
            Token itemTok = expectLine("menu body");
            String line = itemTok.text();
            String kw = firstWord(line);

            if ("timeout".equals(kw)) {
                int arrowIdx = findSubstringOutsideQuotesAndBrackets(line, "->");
                if (arrowIdx >= 0) {
                    String beforeArrow = line.substring("timeout".length(), arrowIdx).trim();
                    String afterArrow = line.substring(arrowIdx + 2).trim();
                    timeoutSeconds = parseDoubleStrict(beforeArrow, itemTok, "menu timeout");
                    timeoutJumpLabel = extractJumpTarget(afterArrow, itemTok);
                } else if (endsWithColonOutsideQuotes(line)) {
                    String durStr = stripRequiredColon(stripKeyword(line, "timeout", itemTok), itemTok);
                    timeoutSeconds = parseDoubleStrict(durStr, itemTok, "menu timeout");
                    expectIndent(itemTok);
                    List<Statement> timeoutBody = parseStatementBlock(itemTok);
                    for (Statement s : timeoutBody) {
                        if (s instanceof JumpStatement jumpStmt) {
                            timeoutJumpLabel = jumpStmt.targetLabel();
                            break;
                        }
                    }
                    if (timeoutJumpLabel == null) {
                        throw error(itemTok, "Menu 'timeout:' block must contain a 'jump <label>' statement");
                    }
                } else {
                    throw error(itemTok, "Expected '-> jump <label>' or ':' in menu timeout");
                }
                continue;
            }

            if (line.startsWith("\"")) {
                QuotedSlice slice = extractLeadingQuotedString(line, itemTok);
                String remainder = line.substring(slice.endIndex()).trim();
                if (remainder.isEmpty()) {
                    // Bare caption line inside menu
                    if (caption == null) {
                        caption = slice.value();
                        continue;
                    }
                    throw error(itemTok, "Menu choice must end with '-> jump <label>' or ':'");
                }
                choices.add(parseMenuChoice(slice.value(), remainder, itemTok));
                continue;
            }

            throw error(itemTok, "Unexpected entry inside 'menu:': " + line);
        }
        consumeRequiredDedent(menuToken);

        return new MenuStatement(
                caption, timeoutSeconds, timeoutJumpLabel, choices, menuToken.sourceFile(), menuToken.lineNumber());
    }

    private MenuChoice parseMenuChoice(String text, String remainder, Token itemTok) {
        int arrowIdx = findSubstringOutsideQuotesAndBrackets(remainder, "->");
        if (arrowIdx >= 0) {
            String qualifierPart = remainder.substring(0, arrowIdx).trim();
            String targetPart = remainder.substring(arrowIdx + 2).trim();
            QualifierResult qual = parseChoiceQualifiers(qualifierPart, itemTok);
            String targetLabel = extractJumpTarget(targetPart, itemTok);
            return new MenuChoice(
                    text,
                    qual.conditionExpr(),
                    qual.disabledWhenFalse(),
                    List.of(),
                    targetLabel,
                    itemTok.sourceFile(),
                    itemTok.lineNumber()
            );
        }

        if (endsWithColonOutsideQuotes(remainder)) {
            String qualifierPart = remainder.substring(0, remainder.length() - 1).trim();
            QualifierResult qual = parseChoiceQualifiers(qualifierPart, itemTok);
            expectIndent(itemTok);
            List<Statement> body = parseStatementBlock(itemTok);
            return new MenuChoice(
                    text,
                    qual.conditionExpr(),
                    qual.disabledWhenFalse(),
                    body,
                    null,
                    itemTok.sourceFile(),
                    itemTok.lineNumber()
            );
        }

        throw error(itemTok, "Menu choice must specify '-> jump <label>' or ':' block");
    }

    private record QualifierResult(String conditionExpr, boolean disabledWhenFalse) {
    }

    private QualifierResult parseChoiceQualifiers(String rawQualifiers, Token token) {
        String q = rawQualifiers.trim();
        if (q.isEmpty()) {
            return new QualifierResult(null, false);
        }
        boolean disabled = false;
        if (q.equals("disabled")) {
            return new QualifierResult(null, true);
        }
        if (q.startsWith("disabled ")) {
            disabled = true;
            q = q.substring("disabled".length()).trim();
        } else if (endsWithWord(q, "disabled")) {
            disabled = true;
            q = q.substring(0, q.length() - "disabled".length()).trim();
        }

        String conditionExpr = null;
        if (!q.isEmpty()) {
            if (!q.startsWith("if ")) {
                throw error(token, "Expected 'if <condition>' or 'disabled' on menu choice, found: " + q);
            }
            conditionExpr = q.substring(3).trim();
            if (conditionExpr.isEmpty()) {
                throw error(token, "Expected condition expression after 'if' on menu choice");
            }
        }
        return new QualifierResult(conditionExpr, disabled);
    }

    private String extractJumpTarget(String expr, Token token) {
        if (expr.isEmpty()) {
            throw error(token, "Expected jump target after '->'");
        }
        String target = expr.startsWith("jump ") ? expr.substring(5).trim() : expr;
        if (target.isEmpty()) {
            throw error(token, "Expected label name after '-> jump'");
        }
        return target;
    }

    private SayStatement parseSayStatement(String speakerId, String quotedAndRest, Token token) {
        QuotedSlice slice = extractLeadingQuotedString(quotedAndRest, token);
        String trailing = quotedAndRest.substring(slice.endIndex()).trim();
        String transitionExpr = null;
        if (!trailing.isEmpty()) {
            if ("with".equals(firstWord(trailing))) {
                transitionExpr = trailing.substring(4).trim();
                if (transitionExpr.isEmpty()) {
                    throw error(token, "Expected transition expression after 'with'");
                }
            } else {
                throw error(token, "Unexpected trailing tokens in dialogue line: " + trailing);
            }
        }
        return new SayStatement(speakerId, slice.value(), transitionExpr, token.sourceFile(), token.lineNumber());
    }

    private JavaBlockNode toJavaBlockNode(Token token) {
        String id = buildJavaBlockId(token.lineNumber());
        return new JavaBlockNode(
                id,
                token.description(),
                token.code(),
                token.sourceFile(),
                token.lineNumber()
        );
    }

    private String buildJavaBlockId(int lineNumber) {
        javaBlockSequence++;
        Path fileNamePath = filePath.getFileName();
        String baseName = fileNamePath != null ? fileNamePath.toString() : "script";
        StringBuilder sanitized = new StringBuilder();
        for (int i = 0; i < baseName.length(); i++) {
            char c = baseName.charAt(i);
            sanitized.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return "jb_" + sanitized + "_L" + lineNumber + "_" + javaBlockSequence;
    }

    // ---- Token Cursor Helpers ----

    private boolean isAtEnd() {
        return cursor >= tokens.size() || tokens.get(cursor).type() == TokenType.EOF;
    }

    private Token peek() {
        if (cursor >= tokens.size()) {
            return new Token(TokenType.EOF, sourceFile, 1, 0, "", "", "");
        }
        return tokens.get(cursor);
    }

    private Token consume() {
        Token t = peek();
        if (cursor < tokens.size()) {
            cursor++;
        }
        return t;
    }

    private boolean consumeOptionalIndent() {
        if (!isAtEnd() && peek().type() == TokenType.INDENT) {
            consume();
            return true;
        }
        return false;
    }

    private void expectIndent(Token parentToken) {
        if (isAtEnd() || peek().type() != TokenType.INDENT) {
            throw error(parentToken, "Expected indented block after: " + parentToken.text());
        }
        consume();
    }

    private void consumeRequiredDedent(Token parentToken) {
        if (isAtEnd() || peek().type() != TokenType.DEDENT) {
            Token t = isAtEnd() ? parentToken : peek();
            throw error(t, "Expected end of indented block started at line " + parentToken.lineNumber());
        }
        consume();
    }

    private Token expectLine(String context) {
        Token t = peek();
        if (t.type() != TokenType.LINE) {
            throw error(t, "Expected script line in " + context + ", found " + t.type());
        }
        return consume();
    }

    private VJavaParseException error(Token token, String message) {
        return new VJavaParseException(token.sourceFile(), token.lineNumber(), message);
    }

    // ---- String & Expression Scanning Helpers ----

    private record QuotedSlice(String value, int endIndex) {
    }

    private QuotedSlice extractLeadingQuotedString(String text, Token token) {
        if (text.isEmpty() || (text.charAt(0) != '"' && text.charAt(0) != '\'')) {
            throw error(token, "Expected quoted string literal, got: " + text);
        }
        char quote = text.charAt(0);
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                sb.append(unescapeChar(c));
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == quote) {
                return new QuotedSlice(sb.toString(), i + 1);
            }
            sb.append(c);
        }
        throw error(token, "Unterminated string literal: " + text);
    }

    private String extractSingleQuotedString(String text, Token token) {
        QuotedSlice slice = extractLeadingQuotedString(text, token);
        String trailing = text.substring(slice.endIndex()).trim();
        if (!trailing.isEmpty()) {
            throw error(token, "Unexpected trailing content after string literal: " + trailing);
        }
        return slice.value();
    }

    private String unescapeAndUnquote(String literal, Token token) {
        return extractSingleQuotedString(literal, token);
    }

    private String unquoteIfSingleStringLiteral(String expr, Token token) {
        String trimmed = expr.trim();
        if (isQuotedLiteral(trimmed)) {
            return unescapeAndUnquote(trimmed, token);
        }
        return trimmed;
    }

    private static boolean isQuotedLiteral(String s) {
        if (s.length() < 2) {
            return false;
        }
        char quote = s.charAt(0);
        if (quote != '"' && quote != '\'') {
            return false;
        }
        boolean escaped = false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == quote) {
                return i == s.length() - 1;
            }
        }
        return false;
    }

    private static String unescapeChar(char c) {
        return switch (c) {
            case '"' -> "\"";
            case '\'' -> "'";
            case '\\' -> "\\";
            case 'n' -> "\n";
            case 't' -> "\t";
            case 'r' -> "\r";
            default -> "\\" + c;
        };
    }

    private static String firstWord(String text) {
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == ':' || c == '(') {
                break;
            }
            i++;
        }
        return text.substring(0, i);
    }

    private String stripKeyword(String text, String keyword, Token token) {
        String stripped = stripKeywordSafe(text, keyword);
        if (stripped.isEmpty()) {
            throw error(token, "Expected arguments after '" + keyword + "'");
        }
        return stripped;
    }

    private static String stripKeywordSafe(String text, String keyword) {
        if (text.length() <= keyword.length()) {
            return "";
        }
        return text.substring(keyword.length()).trim();
    }

    private String stripRequiredColon(String text, Token token) {
        if (!endsWithColonOutsideQuotes(text)) {
            throw error(token, "Expected ':' at end of line: " + token.text());
        }
        return text.substring(0, text.length() - 1).trim();
    }

    private static boolean endsWithColonOutsideQuotes(String text) {
        return text.endsWith(":");
    }

    private static boolean endsWithWord(String text, String word) {
        if (!text.endsWith(word)) {
            return false;
        }
        int prefixLen = text.length() - word.length();
        return prefixLen == 0 || Character.isWhitespace(text.charAt(prefixLen - 1));
    }

    private static boolean isNumericLiteral(String token) {
        if (token.isEmpty()) {
            return false;
        }
        int start = (token.charAt(0) == '-' || token.charAt(0) == '+') ? 1 : 0;
        if (start >= token.length()) {
            return false;
        }
        boolean hasDot = false;
        for (int i = start; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '.') {
                if (hasDot) {
                    return false;
                }
                hasDot = true;
            } else if (!Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBinaryOperator(String token) {
        return "+".equals(token) || "-".equals(token) || "*".equals(token) || "/".equals(token);
    }

    private float parseFloatStrict(String text, Token token, String context) {
        try {
            return Float.parseFloat(text);
        } catch (NumberFormatException e) {
            throw error(token, "Invalid number for " + context + ": '" + text + "'");
        }
    }

    private double parseDoubleStrict(String text, Token token, String context) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw error(token, "Invalid number for " + context + ": '" + text + "'");
        }
    }

    private int parseIntStrict(String text, Token token, String context) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw error(token, "Invalid integer for " + context + ": '" + text + "'");
        }
    }

    private boolean parseBooleanStrict(String text, Token token, String context) {
        String lower = text.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(lower)) {
            return true;
        }
        if ("false".equals(lower)) {
            return false;
        }
        throw error(token, "Invalid boolean for " + context + ": '" + text + "'");
    }

    private static List<String> splitWhitespaceTopLevel(String input) {
        List<String> result = new ArrayList<>();
        int parenDepth = 0;
        int bracketDepth = 0;
        int braceDepth = 0;
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        boolean escaped = false;
        int tokenStart = -1;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inDoubleQuote) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }
            if (inSingleQuote) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }

            if (Character.isWhitespace(c) && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                if (tokenStart >= 0) {
                    result.add(input.substring(tokenStart, i));
                    tokenStart = -1;
                }
                continue;
            }

            if (tokenStart < 0) {
                tokenStart = i;
            }
            switch (c) {
                case '"' -> inDoubleQuote = true;
                case '\'' -> inSingleQuote = true;
                case '(' -> parenDepth++;
                case ')' -> parenDepth = Math.max(0, parenDepth - 1);
                case '[' -> bracketDepth++;
                case ']' -> bracketDepth = Math.max(0, bracketDepth - 1);
                case '{' -> braceDepth++;
                case '}' -> braceDepth = Math.max(0, braceDepth - 1);
                default -> {
                }
            }
        }
        if (tokenStart >= 0) {
            result.add(input.substring(tokenStart));
        }
        return result;
    }

    private static List<String> splitTopLevelComma(String input) {
        List<String> result = new ArrayList<>();
        int parenDepth = 0;
        int bracketDepth = 0;
        int braceDepth = 0;
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        boolean escaped = false;
        int start = 0;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inDoubleQuote) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }
            if (inSingleQuote) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inDoubleQuote = true;
                case '\'' -> inSingleQuote = true;
                case '(' -> parenDepth++;
                case ')' -> parenDepth = Math.max(0, parenDepth - 1);
                case '[' -> bracketDepth++;
                case ']' -> bracketDepth = Math.max(0, bracketDepth - 1);
                case '{' -> braceDepth++;
                case '}' -> braceDepth = Math.max(0, braceDepth - 1);
                case ',' -> {
                    if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        result.add(input.substring(start, i).trim());
                        start = i + 1;
                    }
                }
                default -> {
                }
            }
        }
        if (start <= input.length()) {
            String tail = input.substring(start).trim();
            if (!tail.isEmpty() || !result.isEmpty()) {
                result.add(tail);
            }
        }
        return result;
    }

    private static int findCharOutsideQuotesAndBrackets(String input, char target) {
        int parenDepth = 0;
        int bracketDepth = 0;
        int braceDepth = 0;
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        boolean escaped = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inDoubleQuote) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }
            if (inSingleQuote) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }
            if (c == target && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                return i;
            }
            switch (c) {
                case '"' -> inDoubleQuote = true;
                case '\'' -> inSingleQuote = true;
                case '(' -> parenDepth++;
                case ')' -> parenDepth = Math.max(0, parenDepth - 1);
                case '[' -> bracketDepth++;
                case ']' -> bracketDepth = Math.max(0, bracketDepth - 1);
                case '{' -> braceDepth++;
                case '}' -> braceDepth = Math.max(0, braceDepth - 1);
                default -> {
                }
            }
        }
        return -1;
    }

    private static int findAssignmentEquals(String input) {
        int parenDepth = 0;
        int bracketDepth = 0;
        int braceDepth = 0;
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        boolean escaped = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inDoubleQuote) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }
            if (inSingleQuote) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }
            if (c == '=' && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                char prev = i > 0 ? input.charAt(i - 1) : '\0';
                char next = i + 1 < input.length() ? input.charAt(i + 1) : '\0';
                if (prev != '=' && prev != '!' && prev != '<' && prev != '>' && next != '=') {
                    return i;
                }
            }
            switch (c) {
                case '"' -> inDoubleQuote = true;
                case '\'' -> inSingleQuote = true;
                case '(' -> parenDepth++;
                case ')' -> parenDepth = Math.max(0, parenDepth - 1);
                case '[' -> bracketDepth++;
                case ']' -> bracketDepth = Math.max(0, bracketDepth - 1);
                case '{' -> braceDepth++;
                case '}' -> braceDepth = Math.max(0, braceDepth - 1);
                default -> {
                }
            }
        }
        return -1;
    }

    private static int findFirstWhitespaceOutsideQuotesAndBrackets(String input) {
        int parenDepth = 0;
        int bracketDepth = 0;
        int braceDepth = 0;
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        boolean escaped = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inDoubleQuote) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }
            if (inSingleQuote) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }
            if (Character.isWhitespace(c) && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                return i;
            }
            switch (c) {
                case '"' -> inDoubleQuote = true;
                case '\'' -> inSingleQuote = true;
                case '(' -> parenDepth++;
                case ')' -> parenDepth = Math.max(0, parenDepth - 1);
                case '[' -> bracketDepth++;
                case ']' -> bracketDepth = Math.max(0, bracketDepth - 1);
                case '{' -> braceDepth++;
                case '}' -> braceDepth = Math.max(0, braceDepth - 1);
                default -> {
                }
            }
        }
        return -1;
    }

    private static int findSubstringOutsideQuotesAndBrackets(String input, String sub) {
        int parenDepth = 0;
        int bracketDepth = 0;
        int braceDepth = 0;
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        boolean escaped = false;
        int limit = input.length() - sub.length();

        for (int i = 0; i <= limit; i++) {
            char c = input.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inDoubleQuote) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }
            if (inSingleQuote) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }
            if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 && input.startsWith(sub, i)) {
                return i;
            }
            switch (c) {
                case '"' -> inDoubleQuote = true;
                case '\'' -> inSingleQuote = true;
                case '(' -> parenDepth++;
                case ')' -> parenDepth = Math.max(0, parenDepth - 1);
                case '[' -> bracketDepth++;
                case ']' -> bracketDepth = Math.max(0, bracketDepth - 1);
                case '{' -> braceDepth++;
                case '}' -> braceDepth = Math.max(0, braceDepth - 1);
                default -> {
                }
            }
        }
        return -1;
    }

    private static int findKeywordOutsideQuotesAndBrackets(String input, String keyword) {
        int parenDepth = 0;
        int bracketDepth = 0;
        int braceDepth = 0;
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        boolean escaped = false;
        int kwLen = keyword.length();
        int limit = input.length() - kwLen;

        for (int i = 0; i <= limit; i++) {
            char c = input.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inDoubleQuote) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }
            if (inSingleQuote) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }
            if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 && input.startsWith(keyword, i)) {
                boolean leftOk = i == 0 || Character.isWhitespace(input.charAt(i - 1));
                boolean rightOk = (i + kwLen == input.length()) || Character.isWhitespace(input.charAt(i + kwLen));
                if (leftOk && rightOk) {
                    return i;
                }
            }
            switch (c) {
                case '"' -> inDoubleQuote = true;
                case '\'' -> inSingleQuote = true;
                case '(' -> parenDepth++;
                case ')' -> parenDepth = Math.max(0, parenDepth - 1);
                case '[' -> bracketDepth++;
                case ']' -> bracketDepth = Math.max(0, bracketDepth - 1);
                case '{' -> braceDepth++;
                case '}' -> braceDepth = Math.max(0, braceDepth - 1);
                default -> {
                }
            }
        }
        return -1;
    }

    private static int findMatchingClosingParen(String input, int openIndex) {
        int depth = 0;
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        boolean escaped = false;
        for (int i = openIndex; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inDoubleQuote) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }
            if (inSingleQuote) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }
            if (c == '"') {
                inDoubleQuote = true;
            } else if (c == '\'') {
                inSingleQuote = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
