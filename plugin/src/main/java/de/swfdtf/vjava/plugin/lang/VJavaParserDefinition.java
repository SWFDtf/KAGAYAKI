package de.swfdtf.vjava.plugin.lang;

import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;

/**
 * Minimal {@link ParserDefinition} for the VJava language.
 *
 * <p>VJava does not need a full PSI tree for the current feature set
 * (syntax highlighting + injection). We produce a flat token stream only.
 * A proper parser (with statement PSI nodes) can be added later.
 */
public class VJavaParserDefinition implements ParserDefinition {

    public static final IFileElementType FILE = new IFileElementType(VJavaLanguage.INSTANCE);

    public static final TokenSet WHITESPACE = TokenSet.create(TokenType.WHITE_SPACE);
    public static final TokenSet COMMENTS   = TokenSet.create(VJavaTokenTypes.LINE_COMMENT);
    public static final TokenSet STRINGS    = TokenSet.create(VJavaTokenTypes.STRING);

    @Override
    public @NotNull Lexer createLexer(Project project) {
        return new VJavaLexer();
    }

    @Override
    public @NotNull PsiParser createParser(Project project) {
        // Flat-file parser: wraps the whole file in a single FILE element.
        return (root, builder) -> {
            com.intellij.lang.PsiBuilder.Marker file = builder.mark();
            while (!builder.eof()) {
                builder.advanceLexer();
            }
            file.done(FILE);
            return builder.getTreeBuilt();
        };
    }

    @Override
    public @NotNull IFileElementType getFileNodeType() {
        return FILE;
    }

    @Override
    public @NotNull TokenSet getWhitespaceTokens() {
        return WHITESPACE;
    }

    @Override
    public @NotNull TokenSet getCommentTokens() {
        return COMMENTS;
    }

    @Override
    public @NotNull TokenSet getStringLiteralElements() {
        return STRINGS;
    }

    @Override
    public @NotNull PsiElement createElement(ASTNode node) {
        // All nodes are wrapped in a plain leaf element.
        // Replace with generated PSI factory when a grammar is added.
        throw new UnsupportedOperationException(
            "VJava does not use PSI nodes yet – token type: " + node.getElementType());
    }

    @Override
    public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
        return new VJavaPsiFile(viewProvider);
    }
}
