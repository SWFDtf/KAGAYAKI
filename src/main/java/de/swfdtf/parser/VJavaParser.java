package de.swfdtf.parser;

import de.swfdtf.parser.VJavaAst.AlwaysLayerEntry;
import de.swfdtf.parser.VJavaAst.AtlStep;
import de.swfdtf.parser.VJavaAst.AttributeEntry;
import de.swfdtf.parser.VJavaAst.BlockRollbackStatement;
import de.swfdtf.parser.VJavaAst.CallStatement;
import de.swfdtf.parser.VJavaAst.CharacterDef;
import de.swfdtf.parser.VJavaAst.ConditionalLayerEntry;
import de.swfdtf.parser.VJavaAst.GroupLayerEntry;
import de.swfdtf.parser.VJavaAst.HideStatement;
import de.swfdtf.parser.VJavaAst.IfBranch;
import de.swfdtf.parser.VJavaAst.IfStatement;
import de.swfdtf.parser.VJavaAst.ImageDef;
import de.swfdtf.parser.VJavaAst.InputStatement;
import de.swfdtf.parser.VJavaAst.JavaBlockNode;
import de.swfdtf.parser.VJavaAst.JavaBlockStatement;
import de.swfdtf.parser.VJavaAst.JumpStatement;
import de.swfdtf.parser.VJavaAst.LabelNode;
import de.swfdtf.parser.VJavaAst.LayeredEntry;
import de.swfdtf.parser.VJavaAst.LayeredImageDef;
import de.swfdtf.parser.VJavaAst.MenuChoice;
import de.swfdtf.parser.VJavaAst.MenuStatement;
import de.swfdtf.parser.VJavaAst.NvlStatement;
import de.swfdtf.parser.VJavaAst.PauseStatement;
import de.swfdtf.parser.VJavaAst.PlayStatement;
import de.swfdtf.parser.VJavaAst.QueueStatement;
import de.swfdtf.parser.VJavaAst.ReturnStatement;
import de.swfdtf.parser.VJavaAst.SayStatement;
import de.swfdtf.parser.VJavaAst.SceneStatement;
import de.swfdtf.parser.VJavaAst.ScreenDef;
import de.swfdtf.parser.VJavaAst.ScreenItem;
import de.swfdtf.parser.VJavaAst.ScriptFileAst;
import de.swfdtf.parser.VJavaAst.SetVarStatement;
import de.swfdtf.parser.VJavaAst.ShowStatement;
import de.swfdtf.parser.VJavaAst.Statement;
import de.swfdtf.parser.VJavaAst.StopStatement;
import de.swfdtf.parser.VJavaAst.TransformDef;
import de.swfdtf.parser.VJavaAst.TranslateBlock;
import de.swfdtf.parser.VJavaAst.TranslateEntry;
import de.swfdtf.parser.VJavaAst.VoiceStatement;
import de.swfdtf.parser.VJavaAst.WindowStatement;
import de.swfdtf.parser.VJavaAst.WithStatement;
import de.swfdtf.parser.VJavaLexer.Token;
import de.swfdtf.parser.VJavaLexer.TokenType;

import java.io.IOException;
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
            "vbox", "hbox", "grid", "fixed", "frame", "window", "viewport", "vpgrid",
            "textbutton", "imagebutton", "text", "label", "bar", "vbar", "slider",
            "input", "hotspot", "hotbar", "imagemap", "draggroup", "drag",
            "key", "timer", "custom", "add", "null", "button", "side", "mousearea",
            "use", "on", "if", "elif", "else", "for"
    );

    private static final Set<String> SCREEN_POSITIONAL_WIDGETS = Set.of(
            "text", "textbutton", "label", "key", "timer", "custom",
            "add", "use", "hotspot", "hotbar", "side"
    );

    private final Path filePath;
    private final String sourceFile;
    private final List<Token> tokens;
    private int cursor;
    private int javaBlockSequence;

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
        List<Token> tokens = VJavaLexer.tokenize(filePath);
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
        List<Token> tokens = VJavaLexer.tokenize(source, sf);
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
                defaultVariables
        );
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
        List<AtlStep> steps = new ArrayList<>();
        while (!isAtEnd() && peek().type() != TokenType.DEDENT) {
            Token lineTok = expectLine("ATL block");
            steps.add(parseAtlStep(lineTok));
        }
        consumeRequiredDedent(parentToken);
        return steps;
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

        return new ShowStatement(tag, attributes, positionExpr, transitionExpr, inlineAtl, token.sourceFile(), token.lineNumber());
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
                default -> throw error(token, "Unknown option in 'play' statement: " + opt);
            }
        }

        return new PlayStatement(channel, assetPath, loop, fadeInSeconds, volume, token.sourceFile(), token.lineNumber());
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

        return new MenuStatement(caption, timeoutSeconds, timeoutJumpLabel, choices, menuToken.sourceFile(), menuToken.lineNumber());
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
