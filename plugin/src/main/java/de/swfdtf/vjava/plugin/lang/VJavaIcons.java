package de.swfdtf.vjava.plugin.lang;

import com.intellij.openapi.util.IconLoader;

import javax.swing.*;

/**
 * Icons for vjava plugin.
 */
public final class VJavaIcons {

    /** File icon (16x16). Falls back to null if not found – IntelliJ will use a generic icon. */
    public static final Icon FILE = IconLoader.findIcon("/icons/vjava_file.svg", VJavaIcons.class.getClassLoader());

    private VJavaIcons() {}
}
