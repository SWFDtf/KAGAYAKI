package de.swfdtf.vjava.plugin.lang;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import org.jetbrains.annotations.NotNull;

/**
 * Root PSI file node for .vjava files.
 */
public class VJavaPsiFile extends PsiFileBase {

    public VJavaPsiFile(@NotNull FileViewProvider viewProvider) {
        super(viewProvider, VJavaLanguage.INSTANCE);
    }

    @Override
    public @NotNull FileType getFileType() {
        return VJavaFileType.INSTANCE;
    }

    @Override
    public String toString() {
        return "VJava File";
    }
}
