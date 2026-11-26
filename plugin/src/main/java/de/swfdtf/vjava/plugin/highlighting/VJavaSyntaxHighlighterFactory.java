package de.swfdtf.vjava.plugin.highlighting;

import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Factory that provides {@link VJavaSyntaxHighlighter} to the platform.
 */
public class VJavaSyntaxHighlighterFactory extends SyntaxHighlighterFactory {

    @Override
    public @NotNull SyntaxHighlighter getSyntaxHighlighter(
            @Nullable Project project,
            @Nullable VirtualFile virtualFile) {
        return new VJavaSyntaxHighlighter();
    }
}
