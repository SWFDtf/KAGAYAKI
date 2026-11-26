package de.swfdtf.parser;

import de.swfdtf.state.PersistentAccess;
import de.swfdtf.state.StateStore;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Recursive-descent tokenizer, parser, and evaluator for KAGAYAKI Visual Novel expressions
 * and branch conditions.
 *
 * <p>Supports arithmetic ({@code + - * / %}), comparisons ({@code == != < <= > >=}),
 * boolean logic ({@code && || !} as well as {@code and or not}), string concatenation,
 * parentheses, ternary conditionals ({@code cond ? a : b}), object field/record/getter access
 * (e.g., {@code aktives_item.name}), index access ({@code list[0]}), and method invocations
 * (e.g., {@code "Kaito".equals(spieler_name)}, {@code state.get(...)}, {@code persistent.get(...)}).</p>
 */
public final class ConditionEvaluator {

    private static final Set<String> BLOCKED_MEMBERS = Set.of(
            "class",
            "classLoader",
            "module",
            "protectionDomain",
            "declaredFields",
            "declaredMethods",
            "declaredConstructors",
            "runtime",
            "securityManager"
    );

    private static final Set<String> BLOCKED_METHODS = Set.of(
            "getClass",
            "forName",
            "getClassLoader",
            "getContextClassLoader",
            "setContextClassLoader",
            "getModule",
            "getProtectionDomain",
            "getDeclaredMethod",
            "getMethod",
            "getDeclaredMethods",
            "getMethods",
            "getDeclaredField",
            "getField",
            "getDeclaredFields",
            "getFields",
            "getDeclaredConstructor",
            "getConstructor",
            "getConstructors",
            "getDeclaredConstructors",
            "newInstance",
            "defineClass",
            "loadClass",
            "exit",
            "halt",
            "exec",
            "load",
            "loadLibrary",
            "getRuntime",
            "setSecurityManager",
            "getSecurityManager",
            "start",
            "wait",
            "notify",
            "notifyAll",
            "clone",
            "finalize"
    );

    private static final List<String> BLOCKED_CLASS_PREFIXES = List.of(
            "java.lang.reflect.",
            "java.lang.invoke.",
            "java.lang.instrument.",
            "java.lang.management.",
            "java.lang.module.",
            "javax.script.",
            "sun.misc.",
            "jdk.internal.",
            "java.nio.file."
    );

    /**
     * Public constructor allowing instance-based or static invocation.
     */
    public ConditionEvaluator() {
    }

    /**
     * Evaluates an expression string against {@code state} and {@code persistent} and returns the raw result.
     *
     * @param expr       expression text (must not be null or blank)
     * @param state      mutable variable store (may be {@code null})
     * @param persistent persistent storage (may be {@code null})
     * @return evaluated result object (may be {@code null})
     */
    public static Object evaluate(String expr, StateStore state, PersistentAccess persistent) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("Expression must not be null or blank");
        }
        List<Token> tokens = tokenize(expr);
        Parser parser = new Parser(expr, tokens);
        ExprNode ast = parser.parseExpression();
        return ast.eval(new EvalContext(state, persistent));
    }

    /**
     * Evaluates an expression string and coerces its result to a boolean according to KAGAYAKI truthiness rules:
     * {@code Boolean} uses its value, {@code Number} is truthy when non-zero (and not {@code NaN}),
     * {@code String} is truthy when non-empty, and any other non-null {@code Object} is {@code true}.
     *
     * @param expr       expression text (must not be null or blank)
     * @param state      mutable variable store (may be {@code null})
     * @param persistent persistent storage (may be {@code null})
     * @return boolean truthiness result
     */
    public static boolean evaluateBoolean(String expr, StateStore state, PersistentAccess persistent) {
        return isTruthy(evaluate(expr, state, persistent));
    }

    /**
     * Applies KAGAYAKI truthiness rules to an arbitrary evaluated value.
     *
     * @param value evaluated object (nullable)
     * @return {@code true} if truthy, {@code false} if {@code null}/falsy
     */
    public static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return d != 0.0 && !Double.isNaN(d);
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        if (value instanceof Character c) {
            return c != '\0';
        }
        return true;
    }

    // ---- Tokenizer ----

    private enum TokenKind {
        NUMBER,
        STRING,
        IDENT,
        BOOLEAN,
        NULL,
        OP,
        LPAREN,
        RPAREN,
        LBRACKET,
        RBRACKET,
        LBRACE,
        RBRACE,
        DOT,
        COMMA,
        QUESTION,
        COLON,
        EOF
    }

    private record Token(TokenKind kind, String text, Object literalValue, int position) {
    }

    private static List<Token> tokenize(String expr) {
        List<Token> tokens = new ArrayList<>();
        int len = expr.length();
        int i = 0;

        while (i < len) {
            char c = expr.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            // String literal "..." or '...'
            if (c == '"' || c == '\'') {
                int start = i;
                char quote = c;
                i++;
                StringBuilder sb = new StringBuilder();
                boolean closed = false;
                while (i < len) {
                    char sc = expr.charAt(i);
                    if (sc == '\\' && i + 1 < len) {
                        char esc = expr.charAt(i + 1);
                        switch (esc) {
                            case 'n' -> sb.append('\n');
                            case 't' -> sb.append('\t');
                            case 'r' -> sb.append('\r');
                            case '\\' -> sb.append('\\');
                            case '"' -> sb.append('"');
                            case '\'' -> sb.append('\'');
                            default -> sb.append(esc);
                        }
                        i += 2;
                        continue;
                    }
                    if (sc == quote) {
                        i++;
                        closed = true;
                        break;
                    }
                    sb.append(sc);
                    i++;
                }
                if (!closed) {
                    throw new IllegalArgumentException(
                            "Unterminated string literal at position " + start + " in: " + expr);
                }
                tokens.add(new Token(TokenKind.STRING, expr.substring(start, i), sb.toString(), start));
                continue;
            }

            // Numeric literal (including leading dot floats like .5)
            if (Character.isDigit(c) || (c == '.' && i + 1 < len && Character.isDigit(expr.charAt(i + 1)))) {
                int start = i;
                Token numToken = readNumberToken(expr, start);
                i = numToken.position() + numToken.text().length();
                tokens.add(numToken);
                continue;
            }

            // Two-character operators
            if (i + 1 < len) {
                String two = expr.substring(i, i + 2);
                if ("&&".equals(two) || "||".equals(two) || "==".equals(two)
                        || "!=".equals(two) || ">=".equals(two) || "<=".equals(two)) {
                    tokens.add(new Token(TokenKind.OP, two, null, i));
                    i += 2;
                    continue;
                }
            }

            // Single-character punctuation & operators
            TokenKind singleKind = switch (c) {
                case '(' -> TokenKind.LPAREN;
                case ')' -> TokenKind.RPAREN;
                case '[' -> TokenKind.LBRACKET;
                case ']' -> TokenKind.RBRACKET;
                case '{' -> TokenKind.LBRACE;
                case '}' -> TokenKind.RBRACE;
                case '.' -> TokenKind.DOT;
                case ',' -> TokenKind.COMMA;
                case '?' -> TokenKind.QUESTION;
                case ':' -> TokenKind.COLON;
                case '+', '-', '*', '/', '%', '!', '<', '>' -> TokenKind.OP;
                default -> null;
            };
            if (singleKind != null) {
                tokens.add(new Token(singleKind, String.valueOf(c), null, i));
                i++;
                continue;
            }

            // Identifier / keyword
            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                i++;
                while (i < len && Character.isJavaIdentifierPart(expr.charAt(i))) {
                    i++;
                }
                String word = expr.substring(start, i);
                boolean afterDot = !tokens.isEmpty() && tokens.get(tokens.size() - 1).kind() == TokenKind.DOT;
                if (!afterDot) {
                    if ("true".equals(word)) {
                        tokens.add(new Token(TokenKind.BOOLEAN, word, Boolean.TRUE, start));
                        continue;
                    }
                    if ("false".equals(word)) {
                        tokens.add(new Token(TokenKind.BOOLEAN, word, Boolean.FALSE, start));
                        continue;
                    }
                    if ("null".equals(word)) {
                        tokens.add(new Token(TokenKind.NULL, word, null, start));
                        continue;
                    }
                    if ("and".equals(word)) {
                        tokens.add(new Token(TokenKind.OP, "&&", null, start));
                        continue;
                    }
                    if ("or".equals(word)) {
                        tokens.add(new Token(TokenKind.OP, "||", null, start));
                        continue;
                    }
                    if ("not".equals(word)) {
                        tokens.add(new Token(TokenKind.OP, "!", null, start));
                        continue;
                    }
                }
                tokens.add(new Token(TokenKind.IDENT, word, null, start));
                continue;
            }

            throw new IllegalArgumentException("Unexpected character '" + c + "' at position " + i + " in: " + expr);
        }

        tokens.add(new Token(TokenKind.EOF, "", null, len));
        return tokens;
    }

    private static Token readNumberToken(String expr, int start) {
        int len = expr.length();
        int i = start;

        // Hex (0x...) or binary (0b...)
        if (expr.charAt(i) == '0' && i + 1 < len && (expr.charAt(i + 1) == 'x' || expr.charAt(i + 1) == 'X')) {
            i += 2;
            while (i < len && (isHexDigit(expr.charAt(i)) || expr.charAt(i) == '_')) {
                i++;
            }
            boolean isLong = i < len && (expr.charAt(i) == 'l' || expr.charAt(i) == 'L');
            int digitsEnd = i;
            if (isLong) {
                i++;
            }
            String rawHex = expr.substring(start + 2, digitsEnd).replace("_", "");
            long parsed = Long.parseLong(rawHex, 16);
            Number val;
            if (!isLong && parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE) {
                val = Integer.valueOf((int) parsed);
            } else {
                val = Long.valueOf(parsed);
            }
            return new Token(TokenKind.NUMBER, expr.substring(start, i), val, start);
        }

        boolean isFloat = false;
        while (i < len && (Character.isDigit(expr.charAt(i)) || expr.charAt(i) == '_')) {
            i++;
        }
        if (i < len && expr.charAt(i) == '.') {
            isFloat = true;
            i++;
            while (i < len && (Character.isDigit(expr.charAt(i)) || expr.charAt(i) == '_')) {
                i++;
            }
        }
        if (i < len && (expr.charAt(i) == 'e' || expr.charAt(i) == 'E')) {
            isFloat = true;
            i++;
            if (i < len && (expr.charAt(i) == '+' || expr.charAt(i) == '-')) {
                i++;
            }
            while (i < len && (Character.isDigit(expr.charAt(i)) || expr.charAt(i) == '_')) {
                i++;
            }
        }

        char suffix = i < len ? expr.charAt(i) : '\0';
        String numericCore = expr.substring(start, i).replace("_", "");
        if (suffix == 'f' || suffix == 'F') {
            i++;
            return new Token(TokenKind.NUMBER, expr.substring(start, i), Float.parseFloat(numericCore), start);
        }
        if (suffix == 'd' || suffix == 'D') {
            i++;
            return new Token(TokenKind.NUMBER, expr.substring(start, i), Double.parseDouble(numericCore), start);
        }
        if (isFloat) {
            return new Token(TokenKind.NUMBER, expr.substring(start, i), Double.parseDouble(numericCore), start);
        }
        boolean isLong = suffix == 'l' || suffix == 'L';
        if (isLong) {
            i++;
        }
        long parsed = Long.parseLong(numericCore);
        Number val;
        if (!isLong && parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE) {
            val = Integer.valueOf((int) parsed);
        } else {
            val = Long.valueOf(parsed);
        }
        return new Token(TokenKind.NUMBER, expr.substring(start, i), val, start);
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    // ---- Recursive-Descent Parser ----

    private static final class Parser {
        private final String source;
        private final List<Token> tokens;
        private int cursor = 0;

        Parser(String source, List<Token> tokens) {
            this.source = source;
            this.tokens = tokens;
        }

        ExprNode parseExpression() {
            ExprNode node = parseTernary();
            if (peek().kind() != TokenKind.EOF) {
                throw syntaxError("Unexpected token '" + peek().text() + "'", peek());
            }
            return node;
        }

        private ExprNode parseTernary() {
            ExprNode condition = parseLogicalOr();
            if (match(TokenKind.QUESTION)) {
                ExprNode trueBranch = parseTernary();
                expect(TokenKind.COLON, "Expected ':' in ternary expression");
                ExprNode falseBranch = parseTernary();
                return new TernaryNode(condition, trueBranch, falseBranch);
            }
            return condition;
        }

        private ExprNode parseLogicalOr() {
            ExprNode left = parseLogicalAnd();
            while (matchOp("||")) {
                ExprNode right = parseLogicalAnd();
                left = new BinaryOpNode("||", left, right);
            }
            return left;
        }

        private ExprNode parseLogicalAnd() {
            ExprNode left = parseEquality();
            while (matchOp("&&")) {
                ExprNode right = parseEquality();
                left = new BinaryOpNode("&&", left, right);
            }
            return left;
        }

        private ExprNode parseEquality() {
            ExprNode left = parseRelational();
            while (true) {
                if (matchOp("==")) {
                    left = new BinaryOpNode("==", left, parseRelational());
                } else if (matchOp("!=")) {
                    left = new BinaryOpNode("!=", left, parseRelational());
                } else {
                    return left;
                }
            }
        }

        private ExprNode parseRelational() {
            ExprNode left = parseAdditive();
            while (true) {
                if (matchOp("<")) {
                    left = new BinaryOpNode("<", left, parseAdditive());
                } else if (matchOp("<=")) {
                    left = new BinaryOpNode("<=", left, parseAdditive());
                } else if (matchOp(">")) {
                    left = new BinaryOpNode(">", left, parseAdditive());
                } else if (matchOp(">=")) {
                    left = new BinaryOpNode(">=", left, parseAdditive());
                } else {
                    return left;
                }
            }
        }

        private ExprNode parseAdditive() {
            ExprNode left = parseMultiplicative();
            while (true) {
                if (matchOp("+")) {
                    left = new BinaryOpNode("+", left, parseMultiplicative());
                } else if (matchOp("-")) {
                    left = new BinaryOpNode("-", left, parseMultiplicative());
                } else {
                    return left;
                }
            }
        }

        private ExprNode parseMultiplicative() {
            ExprNode left = parseUnary();
            while (true) {
                if (matchOp("*")) {
                    left = new BinaryOpNode("*", left, parseUnary());
                } else if (matchOp("/")) {
                    left = new BinaryOpNode("/", left, parseUnary());
                } else if (matchOp("%")) {
                    left = new BinaryOpNode("%", left, parseUnary());
                } else {
                    return left;
                }
            }
        }

        private ExprNode parseUnary() {
            if (matchOp("!")) {
                return new UnaryOpNode("!", parseUnary());
            }
            if (matchOp("-")) {
                return new UnaryOpNode("-", parseUnary());
            }
            if (matchOp("+")) {
                return new UnaryOpNode("+", parseUnary());
            }
            return parsePostfix();
        }

        private ExprNode parsePostfix() {
            ExprNode expr = parsePrimary();
            while (true) {
                if (match(TokenKind.DOT)) {
                    Token memberTok = expect(TokenKind.IDENT, "Expected field or method name after '.'");
                    if (match(TokenKind.LPAREN)) {
                        List<ExprNode> args = parseArgumentList();
                        expr = new MethodCallNode(expr, memberTok.text(), args);
                    } else {
                        expr = new MemberAccessNode(expr, memberTok.text());
                    }
                } else if (match(TokenKind.LBRACKET)) {
                    ExprNode index = parseTernary();
                    expect(TokenKind.RBRACKET, "Expected ']' after index expression");
                    expr = new IndexAccessNode(expr, index);
                } else {
                    return expr;
                }
            }
        }

        private List<ExprNode> parseArgumentList() {
            List<ExprNode> args = new ArrayList<>();
            if (!check(TokenKind.RPAREN)) {
                do {
                    args.add(parseTernary());
                } while (match(TokenKind.COMMA));
            }
            expect(TokenKind.RPAREN, "Expected ')' after method arguments");
            return args;
        }

        private ExprNode parsePrimary() {
            Token tok = peek();
            return switch (tok.kind()) {
                case NUMBER, STRING, BOOLEAN, NULL -> {
                    advance();
                    yield new LiteralNode(tok.literalValue());
                }
                case IDENT -> {
                    advance();
                    yield new IdentNode(tok.text());
                }
                case LPAREN -> {
                    advance();
                    ExprNode inner = parseTernary();
                    expect(TokenKind.RPAREN, "Expected matching ')'");
                    yield inner;
                }
                case LBRACKET -> {
                    advance();
                    List<ExprNode> elements = new ArrayList<>();
                    if (!check(TokenKind.RBRACKET)) {
                        do {
                            elements.add(parseTernary());
                        } while (match(TokenKind.COMMA));
                    }
                    expect(TokenKind.RBRACKET, "Expected closing ']' in list literal");
                    yield new ListLiteralNode(elements);
                }
                case LBRACE -> {
                    advance();
                    List<MapEntryNode> entries = new ArrayList<>();
                    if (!check(TokenKind.RBRACE)) {
                        do {
                            ExprNode key = parseTernary();
                            expect(TokenKind.COLON, "Expected ':' in map literal");
                            ExprNode val = parseTernary();
                            entries.add(new MapEntryNode(key, val));
                        } while (match(TokenKind.COMMA));
                    }
                    expect(TokenKind.RBRACE, "Expected closing '}' in map literal");
                    yield new MapLiteralNode(entries);
                }
                default -> throw syntaxError("Expected expression near '" + tok.text() + "'", tok);
            };
        }

        private Token peek() {
            return tokens.get(cursor);
        }

        private Token advance() {
            Token t = tokens.get(cursor);
            if (cursor < tokens.size() - 1) {
                cursor++;
            }
            return t;
        }

        private boolean check(TokenKind kind) {
            return peek().kind() == kind;
        }

        private boolean match(TokenKind kind) {
            if (check(kind)) {
                advance();
                return true;
            }
            return false;
        }

        private boolean matchOp(String op) {
            Token t = peek();
            if (t.kind() == TokenKind.OP && op.equals(t.text())) {
                advance();
                return true;
            }
            return false;
        }

        private Token expect(TokenKind kind, String message) {
            Token t = peek();
            if (t.kind() != kind) {
                throw syntaxError(message + ", found '" + t.text() + "'", t);
            }
            return advance();
        }

        private IllegalArgumentException syntaxError(String message, Token token) {
            return new IllegalArgumentException(
                    message + " at position " + token.position() + " in expression: " + source);
        }
    }

    // ---- AST & Evaluation ----

    private record EvalContext(StateStore state, PersistentAccess persistent) {
    }

    private sealed interface ExprNode permits
            LiteralNode,
            IdentNode,
            ListLiteralNode,
            MapLiteralNode,
            UnaryOpNode,
            BinaryOpNode,
            TernaryNode,
            MemberAccessNode,
            MethodCallNode,
            IndexAccessNode {
        Object eval(EvalContext ctx);
    }

    private record ListLiteralNode(List<ExprNode> elements) implements ExprNode {
        @Override
        public Object eval(EvalContext ctx) {
            List<Object> evaluated = new ArrayList<>(elements.size());
            for (ExprNode elem : elements) {
                evaluated.add(elem.eval(ctx));
            }
            return Collections.unmodifiableList(evaluated);
        }
    }

    private record MapEntryNode(ExprNode key, ExprNode value) {
    }

    private record MapLiteralNode(List<MapEntryNode> entries) implements ExprNode {
        @Override
        public Object eval(EvalContext ctx) {
            Map<Object, Object> evaluated = new LinkedHashMap<>(entries.size());
            for (MapEntryNode entry : entries) {
                evaluated.put(entry.key().eval(ctx), entry.value().eval(ctx));
            }
            return Collections.unmodifiableMap(evaluated);
        }
    }

    private record LiteralNode(Object value) implements ExprNode {
        @Override
        public Object eval(EvalContext ctx) {
            return value;
        }
    }

    private record IdentNode(String name) implements ExprNode {
        @Override
        public Object eval(EvalContext ctx) {
            if ("state".equals(name)) {
                return ctx.state();
            }
            if ("persistent".equals(name)) {
                return ctx.persistent();
            }
            if ("Math".equals(name)) {
                return Math.class;
            }
            String key = name.startsWith("$") && name.length() > 1 ? name.substring(1) : name;
            if (ctx.state() != null) {
                if (ctx.state().has(name)) {
                    return ctx.state().get(name);
                }
                if (!key.equals(name) && ctx.state().has(key)) {
                    return ctx.state().get(key);
                }
            }
            if (ctx.persistent() != null) {
                if (ctx.persistent().has(name)) {
                    return ctx.persistent().get(name);
                }
                if (!key.equals(name) && ctx.persistent().has(key)) {
                    return ctx.persistent().get(key);
                }
            }
            return null;
        }
    }

    private record UnaryOpNode(String op, ExprNode operand) implements ExprNode {
        @Override
        public Object eval(EvalContext ctx) {
            Object val = operand.eval(ctx);
            return switch (op) {
                case "!" -> !isTruthy(val);
                case "-" -> negateNumber(val);
                case "+" -> requireNumber(val, "+");
                default -> throw new IllegalStateException("Unsupported unary operator: " + op);
            };
        }
    }

    private record BinaryOpNode(String op, ExprNode left, ExprNode right) implements ExprNode {
        @Override
        public Object eval(EvalContext ctx) {
            if ("&&".equals(op)) {
                return isTruthy(left.eval(ctx)) && isTruthy(right.eval(ctx));
            }
            if ("||".equals(op)) {
                return isTruthy(left.eval(ctx)) || isTruthy(right.eval(ctx));
            }

            Object lhs = left.eval(ctx);
            Object rhs = right.eval(ctx);

            return switch (op) {
                case "==" -> areEqual(lhs, rhs);
                case "!=" -> !areEqual(lhs, rhs);
                case "<" -> compareValues(lhs, rhs) < 0;
                case "<=" -> compareValues(lhs, rhs) <= 0;
                case ">" -> compareValues(lhs, rhs) > 0;
                case ">=" -> compareValues(lhs, rhs) >= 0;
                case "+" -> addValues(lhs, rhs);
                case "-" -> subtractNumbers(lhs, rhs);
                case "*" -> multiplyNumbers(lhs, rhs);
                case "/" -> divideNumbers(lhs, rhs);
                case "%" -> moduloNumbers(lhs, rhs);
                default -> throw new IllegalStateException("Unsupported binary operator: " + op);
            };
        }
    }

    private record TernaryNode(ExprNode cond, ExprNode ifTrue, ExprNode ifFalse) implements ExprNode {
        @Override
        public Object eval(EvalContext ctx) {
            return isTruthy(cond.eval(ctx)) ? ifTrue.eval(ctx) : ifFalse.eval(ctx);
        }
    }

    private record MemberAccessNode(ExprNode target, String member) implements ExprNode {
        @Override
        public Object eval(EvalContext ctx) {
            Object instance = target.eval(ctx);
            return resolveMember(instance, member);
        }
    }

    private record MethodCallNode(ExprNode target, String methodName, List<ExprNode> args) implements ExprNode {
        @Override
        public Object eval(EvalContext ctx) {
            Object instance = target.eval(ctx);
            List<Object> evaluatedArgs = new ArrayList<>(args.size());
            for (ExprNode arg : args) {
                evaluatedArgs.add(arg.eval(ctx));
            }
            return invokeMethod(instance, methodName, evaluatedArgs);
        }
    }

    private record IndexAccessNode(ExprNode target, ExprNode index) implements ExprNode {
        @Override
        public Object eval(EvalContext ctx) {
            Object container = target.eval(ctx);
            Object idx = index.eval(ctx);
            if (container == null) {
                throw new IllegalStateException("Cannot index into null value");
            }
            if (container instanceof Map<?, ?> map) {
                return map.get(idx);
            }
            if (container instanceof List<?> list) {
                int i = requireNumber(idx, "[]").intValue();
                return list.get(i);
            }
            if (container instanceof String s) {
                int i = requireNumber(idx, "[]").intValue();
                return String.valueOf(s.charAt(i));
            }
            if (container.getClass().isArray()) {
                int i = requireNumber(idx, "[]").intValue();
                return Array.get(container, i);
            }
            throw new IllegalArgumentException("Type " + container.getClass().getName() + " does not support indexing");
        }
    }

    // ---- Arithmetic & Comparison Semantics ----

    private static Number requireNumber(Object value, String op) {
        if (value instanceof Number n) {
            return n;
        }
        throw new IllegalArgumentException(
                "Operator '" + op + "' requires numeric operand, got "
                        + (value == null ? "null" : value.getClass().getSimpleName() + " (" + value + ")")
        );
    }

    private static boolean isFloating(Number a, Number b) {
        return a instanceof Double || a instanceof Float || b instanceof Double || b instanceof Float;
    }

    private static boolean isLong(Number a, Number b) {
        return a instanceof Long || b instanceof Long;
    }

    private static Number negateNumber(Object val) {
        Number n = requireNumber(val, "-");
        if (n instanceof Double || n instanceof Float) {
            return -n.doubleValue();
        }
        if (n instanceof Long) {
            return -n.longValue();
        }
        return -n.intValue();
    }

    private static Object addValues(Object lhs, Object rhs) {
        if (lhs instanceof String || rhs instanceof String) {
            return String.valueOf(lhs) + String.valueOf(rhs);
        }
        Number a = requireNumber(lhs, "+");
        Number b = requireNumber(rhs, "+");
        if (isFloating(a, b)) {
            return a.doubleValue() + b.doubleValue();
        }
        if (isLong(a, b)) {
            return a.longValue() + b.longValue();
        }
        return a.intValue() + b.intValue();
    }

    private static Number subtractNumbers(Object lhs, Object rhs) {
        Number a = requireNumber(lhs, "-");
        Number b = requireNumber(rhs, "-");
        if (isFloating(a, b)) {
            return a.doubleValue() - b.doubleValue();
        }
        if (isLong(a, b)) {
            return a.longValue() - b.longValue();
        }
        return a.intValue() - b.intValue();
    }

    private static Number multiplyNumbers(Object lhs, Object rhs) {
        Number a = requireNumber(lhs, "*");
        Number b = requireNumber(rhs, "*");
        if (isFloating(a, b)) {
            return a.doubleValue() * b.doubleValue();
        }
        if (isLong(a, b)) {
            return a.longValue() * b.longValue();
        }
        return a.intValue() * b.intValue();
    }

    private static Number divideNumbers(Object lhs, Object rhs) {
        Number a = requireNumber(lhs, "/");
        Number b = requireNumber(rhs, "/");
        if (isFloating(a, b)) {
            return a.doubleValue() / b.doubleValue();
        }
        if (isLong(a, b)) {
            long denom = b.longValue();
            if (denom == 0L) {
                throw new ArithmeticException("Division by zero");
            }
            return a.longValue() / denom;
        }
        int denom = b.intValue();
        if (denom == 0) {
            throw new ArithmeticException("Division by zero");
        }
        return a.intValue() / denom;
    }

    private static Number moduloNumbers(Object lhs, Object rhs) {
        Number a = requireNumber(lhs, "%");
        Number b = requireNumber(rhs, "%");
        if (isFloating(a, b)) {
            return a.doubleValue() % b.doubleValue();
        }
        if (isLong(a, b)) {
            long denom = b.longValue();
            if (denom == 0L) {
                throw new ArithmeticException("Modulo by zero");
            }
            return a.longValue() % denom;
        }
        int denom = b.intValue();
        if (denom == 0) {
            throw new ArithmeticException("Modulo by zero");
        }
        return a.intValue() % denom;
    }

    private static boolean areEqual(Object lhs, Object rhs) {
        if (lhs == rhs) {
            return true;
        }
        if (lhs == null || rhs == null) {
            return false;
        }
        if (lhs instanceof Number a && rhs instanceof Number b) {
            if (isFloating(a, b)) {
                return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
            }
            return a.longValue() == b.longValue();
        }
        return Objects.equals(lhs, rhs);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(Object lhs, Object rhs) {
        if (lhs == null || rhs == null) {
            throw new IllegalArgumentException("Cannot compare null values: " + lhs + " vs " + rhs);
        }
        if (lhs instanceof Number a && rhs instanceof Number b) {
            if (isFloating(a, b)) {
                return Double.compare(a.doubleValue(), b.doubleValue());
            }
            return Long.compare(a.longValue(), b.longValue());
        }
        if (lhs instanceof Comparable c && lhs.getClass().isInstance(rhs)) {
            return c.compareTo(rhs);
        }
        throw new IllegalArgumentException(
                "Incompatible types for relational comparison: "
                        + lhs.getClass().getSimpleName() + " and " + rhs.getClass().getSimpleName()
        );
    }

    // ---- Reflection: Member & Method Resolution ----

    private static void assertTargetAllowed(Object target, Class<?> clazz, String action) {
        if (target instanceof Class<?> cls && cls != Math.class) {
            throw new SecurityException(
                    "Sandbox blocked reflection access to Class '" + cls.getName() + "' during " + action);
        }
        if (isRestrictedClass(clazz)) {
            throw new SecurityException(
                    "Sandbox blocked restricted type '" + clazz.getName() + "' during " + action);
        }
    }

    private static boolean isRestrictedClass(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        if (clazz == Class.class
                || ClassLoader.class.isAssignableFrom(clazz)
                || Module.class.isAssignableFrom(clazz)
                || Runtime.class.isAssignableFrom(clazz)
                || System.class.isAssignableFrom(clazz)
                || ProcessBuilder.class.isAssignableFrom(clazz)
                || Process.class.isAssignableFrom(clazz)
                || Thread.class.isAssignableFrom(clazz)
                || ThreadGroup.class.isAssignableFrom(clazz)
                || java.lang.reflect.Member.class.isAssignableFrom(clazz)
                || java.io.File.class.isAssignableFrom(clazz)) {
            return true;
        }
        String name = clazz.getName();
        for (String prefix : BLOCKED_CLASS_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static Object assertReturnValueAllowed(Object value, String context) {
        if (value == null) {
            return null;
        }
        if (value instanceof Class<?> || isRestrictedClass(value.getClass())) {
            throw new SecurityException(
                    "Sandbox blocked restricted return value of type '"
                            + value.getClass().getName() + "' from " + context);
        }
        return value;
    }

    private static Object resolveMember(Object target, String member) {
        if (target == null) {
            throw new IllegalStateException("Cannot access member '" + member + "' on null");
        }
        boolean staticOnly = target instanceof Class<?>;
        Class<?> clazz = staticOnly ? (Class<?>) target : target.getClass();
        if (BLOCKED_MEMBERS.contains(member)) {
            throw new SecurityException(
                    "Sandbox blocked restricted member access '" + member + "' on " + clazz.getName());
        }
        assertTargetAllowed(target, clazz, "member access '" + member + "'");
        Object receiver = staticOnly ? null : target;

        if (!staticOnly && clazz.isArray() && "length".equals(member)) {
            return Array.getLength(target);
        }

        // 1. Public field on class or superclasses
        try {
            Field f = clazz.getField(member);
            if (!staticOnly || Modifier.isStatic(f.getModifiers())) {
                return assertReturnValueAllowed(f.get(receiver), member);
            }
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            // Fall through to declared fields / getter methods
        }

        // 2. Non-public field in accessible class/module (e.g., package-private user class)
        Class<?> walk = clazz;
        while (walk != null && walk != Object.class) {
            try {
                Field f = walk.getDeclaredField(member);
                if (!staticOnly || Modifier.isStatic(f.getModifiers())) {
                    if (f.trySetAccessible()) {
                        return assertReturnValueAllowed(f.get(receiver), member);
                    }
                }
            } catch (NoSuchFieldException | IllegalAccessException ignored) {
                // Continue walking hierarchy
            }
            walk = walk.getSuperclass();
        }

        // 3. Zero-arg accessor methods: member(), getMember(), isMember()
        String cap = Character.toUpperCase(member.charAt(0)) + member.substring(1);
        List<String> methodCandidates = List.of(member, "get" + cap, "is" + cap);
        for (String candidateName : methodCandidates) {
            if (BLOCKED_METHODS.contains(candidateName)) {
                throw new SecurityException(
                        "Sandbox blocked reflection accessor '" + candidateName + "' on " + clazz.getName());
            }
            Method found = findZeroArgAccessibleMethod(clazz, candidateName, staticOnly);
            if (found != null) {
                try {
                    return assertReturnValueAllowed(found.invoke(receiver), candidateName);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    Throwable cause = e instanceof InvocationTargetException ite ? ite.getCause() : e;
                    throw new IllegalStateException(
                            "Failed invoking accessor '" + candidateName + "' on " + clazz.getSimpleName(), cause);
                }
            }
        }

        // 4. Map key lookup
        if (target instanceof Map<?, ?> map) {
            return assertReturnValueAllowed(map.get(member), "map['" + member + "']");
        }

        throw new IllegalArgumentException("Unknown member '" + member + "' on type " + clazz.getName());
    }

    private static Method findZeroArgAccessibleMethod(Class<?> clazz, String name, boolean staticOnly) {
        for (Class<?> type : collectAccessibleHierarchy(clazz)) {
            try {
                Method m = type.getMethod(name);
                if (m.getParameterCount() == 0 && (!staticOnly || Modifier.isStatic(m.getModifiers()))) {
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
                // Try next type in hierarchy
            }
        }
        return null;
    }

    private static Object invokeMethod(Object target, String methodName, List<Object> args) {
        if (target == null) {
            throw new IllegalStateException("Cannot invoke method '" + methodName + "' on null");
        }
        boolean staticOnly = target instanceof Class<?>;
        Class<?> clazz = staticOnly ? (Class<?>) target : target.getClass();
        if (BLOCKED_METHODS.contains(methodName)) {
            throw new SecurityException(
                    "Sandbox blocked reflection/restricted method '" + methodName + "' on " + clazz.getName());
        }
        assertTargetAllowed(target, clazz, "method call '" + methodName + "'");
        Object receiver = staticOnly ? null : target;

        List<Method> candidates = new ArrayList<>();
        for (Class<?> type : collectAccessibleHierarchy(clazz)) {
            for (Method m : type.getMethods()) {
                if (m.getName().equals(methodName) && (!staticOnly || Modifier.isStatic(m.getModifiers()))) {
                    candidates.add(m);
                }
            }
        }

        Method bestMethod = null;
        int bestScore = Integer.MAX_VALUE;
        Object[] bestArgs = null;

        for (Method candidate : candidates) {
            InvocationMatch match = matchMethodArguments(candidate, args);
            if (match != null && match.score() < bestScore) {
                bestScore = match.score();
                bestMethod = candidate;
                bestArgs = match.adaptedArgs();
            }
        }

        if (bestMethod == null) {
            throw new IllegalArgumentException(
                    "No matching method '" + methodName + "' with " + args.size() + " argument(s) on " + clazz.getName()
            );
        }

        try {
            if (!bestMethod.canAccess(receiver)) {
                bestMethod.trySetAccessible();
            }
            return assertReturnValueAllowed(bestMethod.invoke(receiver, bestArgs), methodName);
        } catch (IllegalAccessException | InvocationTargetException e) {
            Throwable cause = e instanceof InvocationTargetException ite ? ite.getCause() : e;
            throw new IllegalStateException(
                    "Error invoking method '" + methodName + "' on " + clazz.getSimpleName()
                            + ": " + cause.getMessage(),
                    cause
            );
        }
    }

    private static List<Class<?>> collectAccessibleHierarchy(Class<?> leaf) {
        Set<Class<?>> ordered = new LinkedHashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        queue.add(leaf);
        while (!queue.isEmpty()) {
            Class<?> current = queue.poll();
            if (current == null) {
                continue;
            }
            if (Modifier.isPublic(current.getModifiers())
                    || current.getModule().equals(ConditionEvaluator.class.getModule())) {
                ordered.add(current);
            }
            if (current.getSuperclass() != null) {
                queue.add(current.getSuperclass());
            }
            for (Class<?> iface : current.getInterfaces()) {
                queue.add(iface);
            }
        }
        return List.copyOf(ordered);
    }

    private record InvocationMatch(int score, Object[] adaptedArgs) {
    }

    private static InvocationMatch matchMethodArguments(Method method, List<Object> args) {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (!method.isVarArgs()) {
            if (paramTypes.length != args.size()) {
                return null;
            }
            Object[] adapted = new Object[paramTypes.length];
            int totalScore = 0;
            for (int i = 0; i < paramTypes.length; i++) {
                ArgAdaptation res = adaptArgument(args.get(i), paramTypes[i]);
                if (res == null) {
                    return null;
                }
                adapted[i] = res.value();
                totalScore += res.cost();
            }
            return new InvocationMatch(totalScore, adapted);
        }

        int fixedCount = paramTypes.length - 1;
        if (args.size() < fixedCount) {
            return null;
        }
        Object[] adapted = new Object[paramTypes.length];
        int totalScore = 100; // Prefer non-varargs overloads when available
        for (int i = 0; i < fixedCount; i++) {
            ArgAdaptation res = adaptArgument(args.get(i), paramTypes[i]);
            if (res == null) {
                return null;
            }
            adapted[i] = res.value();
            totalScore += res.cost();
        }
        Class<?> varargComponent = paramTypes[fixedCount].getComponentType();
        if (args.size() == paramTypes.length && args.get(fixedCount) != null
                && paramTypes[fixedCount].isInstance(args.get(fixedCount))) {
            adapted[fixedCount] = args.get(fixedCount);
            return new InvocationMatch(totalScore + 1, adapted);
        }
        int varargLen = args.size() - fixedCount;
        Object varargArray = Array.newInstance(varargComponent, varargLen);
        for (int i = 0; i < varargLen; i++) {
            ArgAdaptation res = adaptArgument(args.get(fixedCount + i), varargComponent);
            if (res == null) {
                return null;
            }
            Array.set(varargArray, i, res.value());
            totalScore += res.cost();
        }
        adapted[fixedCount] = varargArray;
        return new InvocationMatch(totalScore, adapted);
    }

    private record ArgAdaptation(Object value, int cost) {
    }

    private static ArgAdaptation adaptArgument(Object arg, Class<?> targetType) {
        if (arg == null) {
            return targetType.isPrimitive() ? null : new ArgAdaptation(null, 5);
        }
        Class<?> argType = arg.getClass();
        if (targetType == argType) {
            return new ArgAdaptation(arg, 0);
        }
        if (targetType.isPrimitive()) {
            Class<?> boxedTarget = boxPrimitive(targetType);
            if (boxedTarget == argType) {
                return new ArgAdaptation(arg, 1);
            }
            if (arg instanceof Number n && Number.class.isAssignableFrom(boxedTarget)) {
                Object converted = convertNumber(n, boxedTarget);
                if (converted != null) {
                    return new ArgAdaptation(converted, 4);
                }
            }
            return null;
        }
        if (targetType.isAssignableFrom(argType)) {
            return new ArgAdaptation(arg, targetType == Object.class ? 8 : 2);
        }
        if (arg instanceof Number n && Number.class.isAssignableFrom(targetType)) {
            Object converted = convertNumber(n, targetType);
            if (converted != null) {
                return new ArgAdaptation(converted, 5);
            }
        }
        return null;
    }

    private static Class<?> boxPrimitive(Class<?> primitive) {
        if (primitive == int.class) {
            return Integer.class;
        }
        if (primitive == long.class) {
            return Long.class;
        }
        if (primitive == double.class) {
            return Double.class;
        }
        if (primitive == float.class) {
            return Float.class;
        }
        if (primitive == boolean.class) {
            return Boolean.class;
        }
        if (primitive == char.class) {
            return Character.class;
        }
        if (primitive == byte.class) {
            return Byte.class;
        }
        if (primitive == short.class) {
            return Short.class;
        }
        return primitive;
    }

    private static Object convertNumber(Number n, Class<?> boxedTarget) {
        if (boxedTarget == Integer.class) {
            return n.intValue();
        }
        if (boxedTarget == Long.class) {
            return n.longValue();
        }
        if (boxedTarget == Double.class) {
            return n.doubleValue();
        }
        if (boxedTarget == Float.class) {
            return n.floatValue();
        }
        if (boxedTarget == Short.class) {
            return n.shortValue();
        }
        if (boxedTarget == Byte.class) {
            return n.byteValue();
        }
        return null;
    }
}
