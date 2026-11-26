package de.swfdtf.vjava.plugin.injection;

import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLanguageInjectionHost;
import de.swfdtf.vjava.plugin.lang.VJavaTokenTypes;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Injects Java 21 into the content of {@code @->{ … }<<} blocks in .vjava files.
 *
 * <p>The strategy:
 * <ol>
 *   <li>Find PSI elements whose token type is {@code JAVA_BLOCK_CONTENT}.</li>
 *   <li>Register the whole text range of that element as an injection host
 *       for {@code JavaLanguage.INSTANCE}, wrapping it in a minimal synthetic
 *       class so the Java parser/completion can work correctly.</li>
 * </ol>
 *
 * <p>The synthetic wrapper is:
 * <pre>{@code
 *   import de.swfdtf.state.*;
 *   import de.swfdtf.core.*;
 *   import java.util.*;
 *   class _VJavaInjected {
 *       /* injected content here *\/
 *   }
 * }</pre>
 *
 * <p>This gives IntelliJ full Java completion and navigation inside Java blocks.
 */
public class VJavaJavaInjector implements MultiHostInjector {

    private static final String JAVA_PREFIX =
        "import de.swfdtf.state.*; import de.swfdtf.core.*; import de.swfdtf.graphics.*;" +
        "import de.swfdtf.math.*; import java.util.*;" +
        "@SuppressWarnings(\"all\") class _VJavaInjected { ";

    private static final String JAVA_SUFFIX = " }";

    // Lazy-resolve JavaLanguage to avoid hard dependency at load time
    // when the Java plugin may not be enabled
    private static com.intellij.lang.Language getJavaLanguage() {
        try {
            return com.intellij.lang.java.JavaLanguage.INSTANCE;
        } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
            return null;
        }
    }

    @Override
    public void getLanguagesToInject(
            @NotNull MultiHostRegistrar registrar,
            @NotNull PsiElement context) {

        // We only handle JAVA_BLOCK_CONTENT tokens
        if (context.getNode() == null) return;
        if (context.getNode().getElementType() != VJavaTokenTypes.JAVA_BLOCK_CONTENT) return;
        if (!(context instanceof PsiLanguageInjectionHost host)) return;
        if (!host.isValidHost()) return;

        com.intellij.lang.Language java = getJavaLanguage();
        if (java == null) return;

        // The full text of this token is the raw Java code content
        String content = context.getText();
        if (content.isBlank()) return;

        TextRange range = new TextRange(0, content.length());

        registrar.startInjecting(java)
                 .addPlace(JAVA_PREFIX, JAVA_SUFFIX, host, range)
                 .doneInjecting();
    }

    @Override
    public @NotNull List<? extends Class<? extends PsiElement>> elementsToInjectIn() {
        // VJava PSI elements are plain PsiElement wrappers around leaf nodes.
        // We use the base PsiElement class and filter by token type in
        // getLanguagesToInject.
        return List.of(PsiElement.class);
    }
}
