package de.swfdtf.vjava.plugin.lang;

import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a single token type produced by the VJava lexer.
 */
public final class VJavaTokenType extends IElementType {

    public VJavaTokenType(@NotNull @NonNls String debugName) {
        super(debugName, VJavaLanguage.INSTANCE);
    }

    @Override
    public String toString() {
        return "VJavaTokenType." + super.toString();
    }
}
