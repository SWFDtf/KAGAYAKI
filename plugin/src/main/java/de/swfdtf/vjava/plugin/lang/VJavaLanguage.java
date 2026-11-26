package de.swfdtf.vjava.plugin.lang;

import com.intellij.lang.Language;

/**
 * Singleton Language instance for vjava.
 */
public final class VJavaLanguage extends Language {

    public static final VJavaLanguage INSTANCE = new VJavaLanguage();

    private VJavaLanguage() {
        super("vjava");
    }
}
