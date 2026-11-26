package de.swfdtf.parser;

import de.swfdtf.state.LocalizationManager;
import de.swfdtf.state.TranslationEntry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Bridges parsed {@link VJavaAst.ProjectAst} translation blocks and dialogue/screen AST nodes
 * into the AST-free {@link LocalizationManager} in {@code de.swfdtf.state}.
 */
public final class VJavaLocalizationBridge {

    private static final Set<String> TRANSLATABLE_SCREEN_WIDGETS = Set.of("text", "textbutton", "label");
    private static final List<String> TRANSLATABLE_SCREEN_PROPERTIES = List.of("tooltip", "placeholder");

    private VJavaLocalizationBridge() {
    }

    /**
     * Indexes all {@link VJavaAst.TranslateBlock} entries from {@code project} into {@code manager}.
     *
     * @param manager target localization manager (must not be {@code null})
     * @param project parsed project AST (must not be {@code null})
     * @throws NullPointerException if {@code manager} or {@code project} is {@code null}
     */
    public static void indexProject(LocalizationManager manager, VJavaAst.ProjectAst project) {
        Objects.requireNonNull(manager, "manager must not be null");
        Objects.requireNonNull(project, "project must not be null");
        String defaultLanguage = (project.descriptor() != null)
                ? project.descriptor().getDefaultLanguage()
                : null;
        manager.indexTranslationBlocks(defaultLanguage, toTranslationBlocks(project));
    }

    /**
     * Converts all {@link VJavaAst.TranslateBlock} entries from {@code project} into immutable
     * AST-free {@link LocalizationManager.TranslationBlock} instances.
     *
     * @param project parsed project AST (must not be {@code null})
     * @return immutable list of converted translation blocks
     * @throws NullPointerException if {@code project} is {@code null}
     */
    public static List<LocalizationManager.TranslationBlock> toTranslationBlocks(VJavaAst.ProjectAst project) {
        Objects.requireNonNull(project, "project must not be null");
        List<LocalizationManager.TranslationBlock> blocks = new ArrayList<>();
        for (VJavaAst.TranslateBlock block : project.translations()) {
            if (block == null || block.language() == null || block.language().isBlank()) {
                continue;
            }
            List<TranslationEntry> entries = new ArrayList<>(block.entries().size());
            int lineNumber = Math.max(0, block.lineNumber());
            for (VJavaAst.TranslateEntry entry : block.entries()) {
                entries.add(new TranslationEntry(
                        block.id(),
                        entry.originalText(),
                        entry.translatedText(),
                        entry.speaker(),
                        lineNumber
                ));
            }
            blocks.add(new LocalizationManager.TranslationBlock(block.language(), block.id(), entries));
        }
        return List.copyOf(blocks);
    }

    /**
     * Scans all {@link VJavaAst.LabelNode} dialogue lines, {@link VJavaAst.MenuStatement} choices
     * (and captions), {@link VJavaAst.InputStatement} prompts, and {@link VJavaAst.ScreenDef} UI texts
     * in {@code project} and generates valid {@code .vjava} {@code translate <targetLanguage> <id>:}
     * blocks ready for translation and clean re-parsing by {@link VJavaParser}.
     *
     * @param project        parsed project AST to scan
     * @param targetLanguage target language identifier for generated {@code translate} headers
     * @return formatted {@code .vjava} translation template script
     * @throws NullPointerException     if {@code project} or {@code targetLanguage} is {@code null}
     * @throws IllegalArgumentException if {@code targetLanguage} is blank
     */
    public static String extractTranslationTemplate(VJavaAst.ProjectAst project, String targetLanguage) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(targetLanguage, "targetLanguage must not be null");
        String sanitizedLang = targetLanguage.trim().replaceAll("\\s+", "_");
        if (sanitizedLang.isEmpty()) {
            throw new IllegalArgumentException("targetLanguage must not be blank");
        }

        List<ExtractedDialogue> dialogues = new ArrayList<>();
        Set<String> uiStrings = new LinkedHashSet<>();
        Set<String> usedBlockIds = new LinkedHashSet<>();

        for (VJavaAst.LabelNode label : project.labels().values()) {
            collectFromStatements(label.name(), label.statements(), dialogues, uiStrings, usedBlockIds);
        }
        for (VJavaAst.ScreenDef screen : project.screens().values()) {
            collectFromScreenItems(screen.items(), uiStrings);
        }

        StringBuilder out = new StringBuilder();
        out.append("// KAGAYAKI translation template for language: ").append(sanitizedLang).append('\n');

        for (ExtractedDialogue dlg : dialogues) {
            out.append('\n');
            out.append("translate ").append(sanitizedLang).append(' ').append(dlg.blockId()).append(":\n");
            String escapedOriginal = escapeVJavaString(dlg.originalText());
            if (dlg.speakerId() != null && !dlg.speakerId().isBlank()) {
                String speaker = sanitizeIdentifier(dlg.speakerId());
                out.append("    # ").append(speaker).append(" \"").append(escapedOriginal).append("\"\n");
                out.append("    ").append(speaker).append(" \"\"\n");
            } else {
                out.append("    # \"").append(escapedOriginal).append("\"\n");
                out.append("    \"\"\n");
            }
        }

        if (!uiStrings.isEmpty()) {
            out.append('\n');
            out.append("translate ").append(sanitizedLang).append(" strings:\n");
            for (String uiText : uiStrings) {
                String escaped = escapeVJavaString(uiText);
                out.append("    old \"").append(escaped).append("\"\n");
                out.append("    new \"\"\n");
            }
        }

        return out.toString();
    }

    private record ExtractedDialogue(String blockId, String speakerId, String originalText) {
    }

    private static void collectFromStatements(
            String labelName,
            List<VJavaAst.Statement> statements,
            List<ExtractedDialogue> dialogues,
            Set<String> uiStrings,
            Set<String> usedBlockIds
    ) {
        for (int i = 0; i < statements.size(); i++) {
            VJavaAst.Statement stmt = statements.get(i);
            if (stmt instanceof VJavaAst.SayStatement say) {
                if (say.text() != null && !say.text().isEmpty()) {
                    String blockId = buildUniqueDialogueId(labelName, i, say, usedBlockIds);
                    dialogues.add(new ExtractedDialogue(blockId, say.speakerId(), say.text()));
                }
            } else if (stmt instanceof VJavaAst.MenuStatement menu) {
                if (menu.caption() != null && !menu.caption().isBlank()) {
                    uiStrings.add(menu.caption());
                }
                for (VJavaAst.MenuChoice choice : menu.choices()) {
                    if (choice.text() != null && !choice.text().isBlank()) {
                        uiStrings.add(choice.text());
                    }
                    if (!choice.body().isEmpty()) {
                        collectFromStatements(labelName, choice.body(), dialogues, uiStrings, usedBlockIds);
                    }
                }
            } else if (stmt instanceof VJavaAst.InputStatement input) {
                if (input.prompt() != null && !input.prompt().isBlank()) {
                    uiStrings.add(input.prompt());
                }
            } else if (stmt instanceof VJavaAst.IfStatement ifs) {
                for (VJavaAst.IfBranch branch : ifs.branches()) {
                    if (!branch.body().isEmpty()) {
                        collectFromStatements(labelName, branch.body(), dialogues, uiStrings, usedBlockIds);
                    }
                }
            }
        }
    }

    private static void collectFromScreenItems(List<VJavaAst.ScreenItem> items, Set<String> uiStrings) {
        for (VJavaAst.ScreenItem item : items) {
            String keyword = item.keyword() != null ? item.keyword().toLowerCase(Locale.ROOT) : "";
            if ("java_block".equals(keyword)) {
                continue;
            }
            if (TRANSLATABLE_SCREEN_WIDGETS.contains(keyword)
                    && item.primaryArg() != null
                    && !item.primaryArg().isBlank()) {
                uiStrings.add(item.primaryArg());
            }
            for (String propKey : TRANSLATABLE_SCREEN_PROPERTIES) {
                String propVal = item.properties().get(propKey);
                if (propVal != null && !propVal.isBlank()) {
                    uiStrings.add(propVal);
                }
            }
            if (!item.children().isEmpty()) {
                collectFromScreenItems(item.children(), uiStrings);
            }
        }
    }

    static String buildDialogueLineId(String labelName, int statementIndex, VJavaAst.SayStatement say) {
        String loc;
        if (say.sourceFile() != null && !say.sourceFile().isBlank() && say.lineNumber() > 0) {
            loc = sanitizeIdentifier(say.sourceFile().replace('\\', '/')) + ":" + say.lineNumber();
        } else {
            loc = sanitizeIdentifier(labelName) + ":" + statementIndex;
        }
        int hash = Objects.hash(say.speakerId(), say.text());
        return loc + ":" + Integer.toHexString(hash);
    }

    private static String buildUniqueDialogueId(
            String labelName,
            int statementIndex,
            VJavaAst.SayStatement say,
            Set<String> usedBlockIds
    ) {
        String baseId = buildDialogueLineId(labelName, statementIndex, say);

        String candidate = baseId;
        int suffix = 2;
        while (!usedBlockIds.add(candidate)) {
            candidate = baseId + "_" + suffix++;
        }
        return candidate;
    }

    private static String sanitizeIdentifier(String raw) {
        if (raw == null || raw.isBlank()) {
            return "line";
        }
        return raw.trim().replaceAll("\\s+", "_");
    }

    private static String escapeVJavaString(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
