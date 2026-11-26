package de.swfdtf.vjava.plugin.lang;

import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.util.NlsContexts;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * FileType registration for .vjava files.
 */
public final class VJavaFileType extends LanguageFileType {

    public static final VJavaFileType INSTANCE = new VJavaFileType();

    private VJavaFileType() {
        super(VJavaLanguage.INSTANCE);
    }

    @Override
    public @NonNls @NotNull String getName() {
        return "vjava";
    }

    @Override
    public @NlsContexts.Label @NotNull String getDescription() {
        return "vjava script file (KAGAYAKI Visual Novel Engine)";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "vjava";
    }

    @Override
    public @Nullable Icon getIcon() {
        return VJavaIcons.FILE;
    }
}
