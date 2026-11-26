package de.swfdtf.vjava.plugin.highlighting;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Map;

/**
 * Provides the "Settings &gt; Editor &gt; Color Scheme &gt; vjava" page.
 *
 * The demo text uses custom XML-like tags (e.g. {@code <kw>define</kw>}) that
 * are mapped via {@link #getAdditionalHighlightingTagToDescriptorMap()} to the
 * corresponding {@link TextAttributesKey}. This is the standard IntelliJ approach
 * for coloured demo text in the Color Settings page.
 */
public class VJavaColorSettingsPage implements ColorSettingsPage {

    private static final AttributesDescriptor[] DESCRIPTORS = {
        new AttributesDescriptor("Comment//Line comment",              VJavaSyntaxHighlighter.COMMENT),
        new AttributesDescriptor("String//String literal",             VJavaSyntaxHighlighter.STRING),
        new AttributesDescriptor("String//Interpolation [var]",        VJavaSyntaxHighlighter.INTERPOLATION),
        new AttributesDescriptor("String//Markup tag {b}…{/b}",       VJavaSyntaxHighlighter.MARKUP_TAG),
        new AttributesDescriptor("Number",                             VJavaSyntaxHighlighter.NUMBER),
        new AttributesDescriptor("Keyword",                            VJavaSyntaxHighlighter.KEYWORD),
        new AttributesDescriptor("Predefined symbol",                  VJavaSyntaxHighlighter.PREDEFINED),
        new AttributesDescriptor("Operator//Operation sign",           VJavaSyntaxHighlighter.OPERATION_SIGN),
        new AttributesDescriptor("Operator//Dollar ($) prefix",        VJavaSyntaxHighlighter.DOLLAR),
        new AttributesDescriptor("Operator//Arrow (->)",               VJavaSyntaxHighlighter.ARROW),
        new AttributesDescriptor("Operator//Colon (:)",                VJavaSyntaxHighlighter.COLON),
        new AttributesDescriptor("Identifier",                         VJavaSyntaxHighlighter.IDENTIFIER),
        new AttributesDescriptor("Java block//Delimiter (@->{ }<<)",   VJavaSyntaxHighlighter.JAVA_BLOCK_DELIMITER),
        new AttributesDescriptor("Java block//Content",                VJavaSyntaxHighlighter.JAVA_BLOCK_CONTENT),
        new AttributesDescriptor("Java block//Doc prefix (:: …)",      VJavaSyntaxHighlighter.DOC_PREFIX),
        new AttributesDescriptor("Bad character",                      VJavaSyntaxHighlighter.BAD_CHARACTER),
    };

    // language=HTML - tags here are NOT HTML, just IntelliJ's colour-demo markup
    @NonNls
    private static final String DEMO =
        """
        # This is a vjava file (KAGAYAKI VN engine)
        // Line comment with //

        <kw>define</kw> <id>lena</id> <op>=</op> <pre>Character</pre>(<str>"Lena"</str>, color<op>=</op><str>"#58a6ff"</str>)
        <kw>define</kw> <id>narrator</id> <op>=</op> <pre>Character</pre>(<kw>None</kw>)
        <kw>default</kw> <id>punkte</id> <op>=</op> <num>0</num>
        <kw>default</kw> <id>name</id> <op>=</op> <str>"Alex"</str>

        <kw>image</kw> <id>bg_hof</id> <op>=</op> <str>"assets/images/bg/hof.png"</str>

        <kw>transform</kw> <id>schweben</id><col>:</col>
            <id>ypos</id> <num>500</num>
            <pre>easeinout</pre> <num>1.0</num> <id>ypos</id> <num>480</num>
            <kw>repeat</kw>

        <doc>:: Hilfsmethode @->{</doc><jd>
            public static int bonus(int basis) { return basis + 25; }
        </jd><jd>}<<</jd>

        <kw>label</kw> <id>start</id><col>:</col>
            <kw>scene</kw> <id>bg_hof</id> <kw>with</kw> <pre>dissolve</pre>(<num>0.8</num>)
            <kw>show</kw> <id>lena</id> <kw>at</kw> <pre>left</pre> <kw>with</kw> <pre>dissolve</pre>(<num>0.3</num>)
            <kw>play</kw> <id>music</id> <str>"assets/audio/theme.ogg"</str> <kw>fadein</kw> <num>1.0</num>
            <id>lena</id> <str>"Hallo <itp>[name]</itp>! <mt>{b}</mt>Fett<mt>{/b}</mt>."</str>
            <dollar>$</dollar> <id>punkte</id> <op>+=</op> <num>10</num>
            <kw>if</kw> <id>punkte</id> <op>>=</op> <num>10</num> <op>and</op> <id>name</id> <op>!=</op> <str>""</str><col>:</col>
                <id>lena</id> <str>"Gut, <itp>[name]</itp>!"</str>
            <kw>menu</kw> <str>"Was tun?"</str><col>:</col>
                <kw>timeout</kw> <num>5.0</num> <arrow>-></arrow> <kw>jump</kw> <id>ende</id>
                <str>"Spielen"</str> <arrow>-></arrow> <kw>jump</kw> <id>arena</id>
            <kw>return</kw>
        """;

    @Override
    public @Nullable Icon getIcon() {
        return de.swfdtf.vjava.plugin.lang.VJavaIcons.FILE;
    }

    @Override
    public @NotNull SyntaxHighlighter getHighlighter() {
        return new VJavaSyntaxHighlighter();
    }

    @Override
    public @NonNls @NotNull String getDemoText() {
        return DEMO;
    }

    @Override
    public @Nullable Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        return Map.ofEntries(
            Map.entry("kw",     VJavaSyntaxHighlighter.KEYWORD),
            Map.entry("pre",    VJavaSyntaxHighlighter.PREDEFINED),
            Map.entry("str",    VJavaSyntaxHighlighter.STRING),
            Map.entry("num",    VJavaSyntaxHighlighter.NUMBER),
            Map.entry("op",     VJavaSyntaxHighlighter.OPERATION_SIGN),
            Map.entry("dollar", VJavaSyntaxHighlighter.DOLLAR),
            Map.entry("arrow",  VJavaSyntaxHighlighter.ARROW),
            Map.entry("col",    VJavaSyntaxHighlighter.COLON),
            Map.entry("id",     VJavaSyntaxHighlighter.IDENTIFIER),
            Map.entry("doc",    VJavaSyntaxHighlighter.DOC_PREFIX),
            Map.entry("jd",     VJavaSyntaxHighlighter.JAVA_BLOCK_CONTENT),
            Map.entry("itp",    VJavaSyntaxHighlighter.INTERPOLATION),
            Map.entry("mt",     VJavaSyntaxHighlighter.MARKUP_TAG)
        );
    }

    @Override
    public AttributesDescriptor @NotNull [] getAttributeDescriptors() {
        return DESCRIPTORS;
    }

    @Override
    public ColorDescriptor @NotNull [] getColorDescriptors() {
        return ColorDescriptor.EMPTY_ARRAY;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "vjava";
    }
}
