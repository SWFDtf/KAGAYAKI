package de.swfdtf.state;

import de.swfdtf.parser.VJavaAst;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Manages live runtime language switching, {@code .vjava} {@link VJavaAst.TranslateBlock}
 * indexing and lookup, {@code tl/<lang>/} localized asset resolution, and automatic
 * {@code .vjava} translation template generation.
 *
 * <p>Thread-safe for concurrent access across script execution, UI screen layout,
 * and asset loading. Language switching notifies registered listeners immediately
 * without requiring an engine restart.</p>
 */
public class LocalizationManager {

    private static final String DEFAULT_FALLBACK_LANGUAGE = "deutsch";
    private static final Set<String> TRANSLATABLE_SCREEN_WIDGETS = Set.of("text", "textbutton", "label");
    private static final List<String> TRANSLATABLE_SCREEN_PROPERTIES = List.of("tooltip", "placeholder");

    private String defaultLanguage;
    private String currentLanguage;
    private boolean languageExplicitlySet = false;

    private final Map<String, String> languageDisplayNames = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> projectById = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> projectByOriginal = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> manualTranslations = new LinkedHashMap<>();
    private final Set<String> indexedLanguages = new LinkedHashSet<>();
    private final Set<String> manualLanguages = new LinkedHashSet<>();
    private final List<Consumer<String>> languageChangeListeners = new ArrayList<>();

    /**
     * Creates a {@link LocalizationManager} initialized to the KAGAYAKI default language ({@code "deutsch"}).
     */
    public LocalizationManager() {
        this(DEFAULT_FALLBACK_LANGUAGE);
    }

    /**
     * Creates a {@link LocalizationManager} with an explicit default language identifier.
     *
     * @param defaultLanguage initial default language identifier (defaults to {@code "deutsch"} if null or blank)
     */
    public LocalizationManager(String defaultLanguage) {
        String normalized = (defaultLanguage == null || defaultLanguage.isBlank())
                ? DEFAULT_FALLBACK_LANGUAGE
                : defaultLanguage.trim();
        this.defaultLanguage = normalized;
        this.currentLanguage = normalized;
        recordLanguageDisplayName(normalized);
    }

    /**
     * Switches the active runtime language immediately and notifies registered listeners
     * if the language changed.
     *
     * @param language target language identifier (must not be null or blank)
     */
    public void setLanguage(String language) {
        Objects.requireNonNull(language, "language must not be null");
        String trimmed = language.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("language must not be blank");
        }
        List<Consumer<String>> listenersSnapshot = null;
        synchronized (this) {
            boolean changed = !trimmed.equals(this.currentLanguage);
            this.currentLanguage = trimmed;
            this.languageExplicitlySet = true;
            if (changed && !languageChangeListeners.isEmpty()) {
                listenersSnapshot = List.copyOf(languageChangeListeners);
            }
        }
        if (listenersSnapshot != null) {
            for (Consumer<String> listener : listenersSnapshot) {
                listener.accept(trimmed);
            }
        }
    }

    /**
     * Returns the currently active language identifier.
     *
     * @return current language identifier
     */
    public synchronized String getCurrentLanguage() {
        return currentLanguage;
    }

    /**
     * Returns the configured project default language identifier.
     *
     * @return default language identifier
     */
    public synchronized String getDefaultLanguage() {
        return defaultLanguage;
    }

    /**
     * Registers a listener notified whenever {@link #setLanguage(String)} switches to a new language.
     *
     * @param listener consumer receiving the newly active language identifier
     */
    public synchronized void addLanguageChangeListener(Consumer<String> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        languageChangeListeners.add(listener);
    }

    /**
     * Unregisters a previously added language change listener.
     *
     * @param listener listener to remove
     */
    public synchronized void removeLanguageChangeListener(Consumer<String> listener) {
        if (listener != null) {
            languageChangeListeners.remove(listener);
        }
    }

    /**
     * Indexes all {@link VJavaAst.TranslateBlock} entries from a parsed {@link VJavaAst.ProjectAst}
     * by both {@code (language, id)} and {@code (language, originalText)}.
     *
     * <p>Replaces any previously indexed project translations while preserving manually registered
     * translations via {@link #registerTranslation(String, String, String)}. Empty translation values
     * (such as unedited template stubs) are skipped so dialogue and UI fall back cleanly to their
     * original text.</p>
     *
     * @param project parsed project AST
     */
    public synchronized void indexProject(VJavaAst.ProjectAst project) {
        Objects.requireNonNull(project, "project must not be null");

        projectById.clear();
        projectByOriginal.clear();
        indexedLanguages.clear();

        if (project.descriptor() != null
                && project.descriptor().getDefaultLanguage() != null
                && !project.descriptor().getDefaultLanguage().isBlank()) {
            String descLang = project.descriptor().getDefaultLanguage().trim();
            this.defaultLanguage = descLang;
            recordLanguageDisplayName(descLang);
            if (!this.languageExplicitlySet) {
                this.currentLanguage = descLang;
            }
        }

        for (VJavaAst.TranslateBlock block : project.translations()) {
            if (block.language() == null || block.language().isBlank()) {
                continue;
            }
            String displayLang = block.language().trim();
            String langKey = normalizeLanguageKey(displayLang);
            recordLanguageDisplayName(displayLang);
            indexedLanguages.add(langKey);

            List<VJavaAst.TranslateEntry> entries = block.entries();
            String blockId = block.id() != null ? block.id().trim() : "";
            boolean isStringsBlock = "strings".equalsIgnoreCase(blockId);

            for (VJavaAst.TranslateEntry entry : entries) {
                String translated = entry.translatedText();
                if (translated == null || translated.isEmpty()) {
                    continue;
                }
                if (entry.originalText() != null) {
                    projectByOriginal
                            .computeIfAbsent(langKey, ignored -> new LinkedHashMap<>())
                            .put(entry.originalText(), translated);
                }
                if (!blockId.isEmpty() && !isStringsBlock) {
                    Map<String, String> byIdMap = projectById.computeIfAbsent(langKey, ignored -> new LinkedHashMap<>());
                    if (entries.size() == 1) {
                        byIdMap.put(blockId, translated);
                    } else {
                        byIdMap.putIfAbsent(blockId, translated);
                    }
                }
            }
        }
    }

    /**
     * Registers or overrides a single translation entry for the specified language.
     *
     * @param language       target language identifier
     * @param keyOrOriginal  dialogue line ID, UI key, or original source text
     * @param translatedText translated text (passing {@code null} removes the override)
     */
    public synchronized void registerTranslation(String language, String keyOrOriginal, String translatedText) {
        Objects.requireNonNull(language, "language must not be null");
        Objects.requireNonNull(keyOrOriginal, "keyOrOriginal must not be null");
        String displayLang = language.trim();
        if (displayLang.isEmpty()) {
            throw new IllegalArgumentException("language must not be blank");
        }
        String langKey = normalizeLanguageKey(displayLang);
        recordLanguageDisplayName(displayLang);
        manualLanguages.add(langKey);

        Map<String, String> langMap = manualTranslations.computeIfAbsent(langKey, ignored -> new LinkedHashMap<>());
        if (translatedText == null) {
            langMap.remove(keyOrOriginal);
        } else {
            langMap.put(keyOrOriginal, translatedText);
        }
    }

    /**
     * Translates a dialogue line for the active language.
     *
     * <p>Performs a fast O(1) fallback to {@code originalText} when the default language is active
     * and no override translations have been registered for it. Otherwise checks registered and
     * project-indexed translations by {@code lineId} first, then by {@code originalText}.</p>
     *
     * @param lineId       canonical dialogue line identifier (nullable)
     * @param originalText original script dialogue text
     * @return translated text for the active language, or {@code originalText} if unavailable
     */
    public synchronized String translateDialogue(String lineId, String originalText) {
        String langKey = normalizeLanguageKey(currentLanguage);
        if (isDefaultFastPath(langKey)) {
            return originalText;
        }
        if (lineId != null) {
            String byManualId = lookupMap(manualTranslations, langKey, lineId);
            if (byManualId != null) {
                return byManualId;
            }
            String byProjectId = lookupMap(projectById, langKey, lineId);
            if (byProjectId != null) {
                return byProjectId;
            }
        }
        if (originalText != null) {
            String byManualOrig = lookupMap(manualTranslations, langKey, originalText);
            if (byManualOrig != null) {
                return byManualOrig;
            }
            String byProjectOrig = lookupMap(projectByOriginal, langKey, originalText);
            if (byProjectOrig != null) {
                return byProjectOrig;
            }
        }
        return originalText;
    }

    /**
     * Translates a UI string or menu label for the active language.
     *
     * <p>Checks registered and project-indexed translations by {@code key} first,
     * then by {@code fallbackText}, before falling back to {@code fallbackText}.</p>
     *
     * @param key          UI translation key or source string (nullable)
     * @param fallbackText default text returned when no translation is registered
     * @return translated UI text, or {@code fallbackText}
     */
    public synchronized String translateUi(String key, String fallbackText) {
        String langKey = normalizeLanguageKey(currentLanguage);
        if (isDefaultFastPath(langKey)) {
            return fallbackText;
        }
        if (key != null) {
            String byManualKey = lookupMap(manualTranslations, langKey, key);
            if (byManualKey != null) {
                return byManualKey;
            }
            String byProjId = lookupMap(projectById, langKey, key);
            if (byProjId != null) {
                return byProjId;
            }
            String byProjOrig = lookupMap(projectByOriginal, langKey, key);
            if (byProjOrig != null) {
                return byProjOrig;
            }
        }
        if (fallbackText != null && !fallbackText.equals(key)) {
            String byManualFallback = lookupMap(manualTranslations, langKey, fallbackText);
            if (byManualFallback != null) {
                return byManualFallback;
            }
            String byProjFallback = lookupMap(projectByOriginal, langKey, fallbackText);
            if (byProjFallback != null) {
                return byProjFallback;
            }
        }
        return fallbackText;
    }

    /**
     * Resolves a localized asset path under {@code tl/<lang>/} if a matching file exists on disk,
     * otherwise returning the original {@code assetPath}.
     *
     * <p>For example, given {@code "assets/images/title.png"} and active language {@code "en"},
     * checks {@code tl/en/assets/images/title.png} and {@code tl/en/images/title.png} in order
     * before falling back to {@code "assets/images/title.png"}.</p>
     *
     * @param projectRoot project root directory containing {@code assets/} and optional {@code tl/}
     * @param assetPath   relative original asset path
     * @return resolved localized relative path if present, otherwise original {@code assetPath}
     */
    public synchronized String resolveLocalizedAsset(Path projectRoot, String assetPath) {
        if (assetPath == null || assetPath.isBlank() || projectRoot == null) {
            return assetPath;
        }
        String normalizedAsset = assetPath.trim().replace('\\', '/');
        while (normalizedAsset.startsWith("./")) {
            normalizedAsset = normalizedAsset.substring(2);
        }
        while (normalizedAsset.startsWith("/")) {
            normalizedAsset = normalizedAsset.substring(1);
        }
        if (normalizedAsset.isEmpty()) {
            return assetPath;
        }

        String lang = currentLanguage;
        String candidateFull = "tl/" + lang + "/" + normalizedAsset;
        if (isExistingRegularFile(projectRoot, candidateFull)) {
            return candidateFull;
        }

        if (normalizedAsset.startsWith("assets/")) {
            String withoutAssetsPrefix = normalizedAsset.substring("assets/".length());
            if (!withoutAssetsPrefix.isEmpty()) {
                String candidateShort = "tl/" + lang + "/" + withoutAssetsPrefix;
                if (isExistingRegularFile(projectRoot, candidateShort)) {
                    return candidateShort;
                }
            }
        }

        return assetPath;
    }

    /**
     * Returns an immutable ordered set of all available languages (default language plus any
     * languages discovered in indexed {@link VJavaAst.TranslateBlock}s or manual registrations).
     *
     * @return immutable set of language identifiers
     */
    public synchronized Set<String> getAvailableLanguages() {
        Set<String> available = new LinkedHashSet<>();
        available.add(defaultLanguage);
        for (String langKey : indexedLanguages) {
            available.add(languageDisplayNames.getOrDefault(langKey, langKey));
        }
        for (String langKey : manualLanguages) {
            available.add(languageDisplayNames.getOrDefault(langKey, langKey));
        }
        return Collections.unmodifiableSet(available);
    }

    /**
     * Scans all {@link VJavaAst.LabelNode} dialogue lines, {@link VJavaAst.MenuStatement} choices
     * (and captions), and {@link VJavaAst.ScreenDef} UI texts in {@code project} and generates valid
     * {@code .vjava} {@code translate <targetLanguage> <id>:} blocks ready for translation and
     * clean re-parsing by {@code VJavaParser}.
     *
     * @param project        parsed project AST to scan
     * @param targetLanguage target language identifier for generated {@code translate} headers
     * @return formatted {@code .vjava} translation template script
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

    // ---- Internal Helpers ----

    private boolean isDefaultFastPath(String langKey) {
        if (!langKey.equals(normalizeLanguageKey(defaultLanguage))) {
            return false;
        }
        return !hasEntriesForLanguage(langKey);
    }

    private boolean hasEntriesForLanguage(String langKey) {
        Map<String, String> manual = manualTranslations.get(langKey);
        if (manual != null && !manual.isEmpty()) {
            return true;
        }
        Map<String, String> byId = projectById.get(langKey);
        if (byId != null && !byId.isEmpty()) {
            return true;
        }
        Map<String, String> byOrig = projectByOriginal.get(langKey);
        return byOrig != null && !byOrig.isEmpty();
    }

    private static String lookupMap(Map<String, Map<String, String>> rootMap, String langKey, String key) {
        Map<String, String> langMap = rootMap.get(langKey);
        return langMap != null ? langMap.get(key) : null;
    }

    private static String normalizeLanguageKey(String language) {
        return language.trim().toLowerCase(Locale.ROOT);
    }

    private void recordLanguageDisplayName(String displayLang) {
        String key = normalizeLanguageKey(displayLang);
        languageDisplayNames.putIfAbsent(key, displayLang);
    }

    private static boolean isExistingRegularFile(Path projectRoot, String relativeUnixPath) {
        try {
            Path resolved = projectRoot.resolve(relativeUnixPath).normalize();
            return Files.isRegularFile(resolved);
        } catch (RuntimeException ignored) {
            return false;
        }
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

    private static String buildUniqueDialogueId(
            String labelName,
            int statementIndex,
            VJavaAst.SayStatement say,
            Set<String> usedBlockIds
    ) {
        String loc;
        if (say.sourceFile() != null && !say.sourceFile().isBlank() && say.lineNumber() > 0) {
            loc = sanitizeIdentifier(say.sourceFile().replace('\\', '/')) + ":" + say.lineNumber();
        } else {
            loc = sanitizeIdentifier(labelName) + ":" + statementIndex;
        }
        int hash = Objects.hash(say.speakerId(), say.text());
        String baseId = loc + ":" + Integer.toHexString(hash);

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
