package de.swfdtf.state;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
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
 * Manages live runtime language switching, AST-free {@link TranslationBlock} /
 * {@link TranslationEntry} indexing and lookup, and {@code tl/<lang>/} localized asset resolution.
 *
 * <p>Thread-safe for concurrent access across script execution, UI screen layout,
 * and asset loading. Language switching notifies registered listeners immediately
 * without requiring an engine restart.</p>
 */
public class LocalizationManager {

    private static final String DEFAULT_FALLBACK_LANGUAGE = "deutsch";

    /**
     * AST-free translation block grouping entries for a language and optional block identifier.
     *
     * @param language target language identifier (must not be {@code null})
     * @param id       block identifier (e.g., dialogue block ID or {@code "strings"}, nullable)
     * @param entries  ordered list of translation entries in this block
     */
    public record TranslationBlock(String language, String id, List<TranslationEntry> entries) {

        /**
         * Validates non-null {@code language} and defensively copies {@code entries}.
         *
         * @throws NullPointerException if {@code language} is {@code null}
         */
        public TranslationBlock {
            Objects.requireNonNull(language, "language must not be null");
            entries = entries != null ? List.copyOf(entries) : List.of();
        }
    }

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
     * @throws NullPointerException     if {@code language} is {@code null}
     * @throws IllegalArgumentException if {@code language} is blank
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
     * @throws NullPointerException if {@code listener} is {@code null}
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
     * Indexes all {@link TranslationBlock} entries by both {@code (language, id)} and
     * {@code (language, sourceText)}.
     *
     * <p>Replaces any previously indexed project translations while preserving manually registered
     * translations via {@link #registerTranslation(String, String, String)} or
     * {@link #registerTranslation(String, TranslationEntry)}. Empty translation values
     * (such as unedited template stubs) are skipped so dialogue and UI fall back cleanly to their
     * original text.</p>
     *
     * @param projectDefaultLanguage optional project default language identifier (nullable/blank to keep current)
     * @param blocks                 collection of {@link TranslationBlock} entries to index
     * @throws NullPointerException if {@code blocks} is {@code null}
     */
    public synchronized void indexTranslationBlocks(
            String projectDefaultLanguage,
            Collection<TranslationBlock> blocks
    ) {
        Objects.requireNonNull(blocks, "blocks must not be null");

        projectById.clear();
        projectByOriginal.clear();
        indexedLanguages.clear();

        if (projectDefaultLanguage != null && !projectDefaultLanguage.isBlank()) {
            String descLang = projectDefaultLanguage.trim();
            this.defaultLanguage = descLang;
            recordLanguageDisplayName(descLang);
            if (!this.languageExplicitlySet) {
                this.currentLanguage = descLang;
            }
        }

        for (TranslationBlock block : blocks) {
            if (block == null || block.language() == null || block.language().isBlank()) {
                continue;
            }
            String displayLang = block.language().trim();
            String langKey = normalizeLanguageKey(displayLang);
            recordLanguageDisplayName(displayLang);
            indexedLanguages.add(langKey);

            List<TranslationEntry> entries = block.entries();
            String blockId = block.id() != null ? block.id().trim() : "";
            boolean isStringsBlock = "strings".equalsIgnoreCase(blockId);

            for (TranslationEntry entry : entries) {
                if (entry == null) {
                    continue;
                }
                String translated = entry.translatedText();
                if (translated == null || translated.isEmpty()) {
                    continue;
                }
                if (entry.sourceText() != null) {
                    projectByOriginal
                            .computeIfAbsent(langKey, ignored -> new LinkedHashMap<>())
                            .put(entry.sourceText(), translated);
                }
                String effectiveId = !blockId.isEmpty()
                        ? blockId
                        : (entry.id() != null ? entry.id().trim() : "");
                boolean entryIsStrings = isStringsBlock || "strings".equalsIgnoreCase(effectiveId);
                if (!effectiveId.isEmpty() && !entryIsStrings) {
                    Map<String, String> byIdMap =
                            projectById.computeIfAbsent(langKey, ignored -> new LinkedHashMap<>());
                    if (entries.size() == 1) {
                        byIdMap.put(effectiveId, translated);
                    } else {
                        byIdMap.putIfAbsent(effectiveId, translated);
                    }
                }
            }
        }
    }

    /**
     * Indexes translation entries grouped by language identifier.
     *
     * <p>Groups entries per language by {@link TranslationEntry#id()} into {@link TranslationBlock}
     * instances and delegates to {@link #indexTranslationBlocks(String, Collection)}.</p>
     *
     * @param projectDefaultLanguage optional project default language identifier (nullable/blank to keep current)
     * @param entriesByLanguage      mapping from language identifier to ordered {@link TranslationEntry} list
     * @throws NullPointerException if {@code entriesByLanguage} is {@code null}
     */
    public synchronized void indexTranslations(
            String projectDefaultLanguage,
            Map<String, List<TranslationEntry>> entriesByLanguage
    ) {
        Objects.requireNonNull(entriesByLanguage, "entriesByLanguage must not be null");
        List<TranslationBlock> blocks = new ArrayList<>();
        for (Map.Entry<String, List<TranslationEntry>> langEntry : entriesByLanguage.entrySet()) {
            String language = langEntry.getKey();
            List<TranslationEntry> entries = langEntry.getValue();
            if (language == null || language.isBlank() || entries == null) {
                continue;
            }
            if (entries.isEmpty()) {
                blocks.add(new TranslationBlock(language, "", List.of()));
                continue;
            }
            Map<String, List<TranslationEntry>> groupedById = new LinkedHashMap<>();
            for (TranslationEntry entry : entries) {
                if (entry == null) {
                    continue;
                }
                String blockId = entry.id() != null ? entry.id().trim() : "";
                groupedById.computeIfAbsent(blockId, ignored -> new ArrayList<>()).add(entry);
            }
            if (groupedById.isEmpty()) {
                blocks.add(new TranslationBlock(language, "", List.of()));
            } else {
                for (Map.Entry<String, List<TranslationEntry>> group : groupedById.entrySet()) {
                    blocks.add(new TranslationBlock(language, group.getKey(), group.getValue()));
                }
            }
        }
        indexTranslationBlocks(projectDefaultLanguage, blocks);
    }

    /**
     * Registers or overrides a single translation entry for the specified language.
     *
     * @param language       target language identifier
     * @param keyOrOriginal  dialogue line ID, UI key, or original source text
     * @param translatedText translated text (passing {@code null} or empty string removes the override)
     * @throws NullPointerException     if {@code language} or {@code keyOrOriginal} is {@code null}
     * @throws IllegalArgumentException if {@code language} is blank
     */
    public synchronized void registerTranslation(String language, String keyOrOriginal, String translatedText) {
        Objects.requireNonNull(language, "language must not be null");
        Objects.requireNonNull(keyOrOriginal, "keyOrOriginal must not be null");
        String displayLang = language.trim();
        if (displayLang.isEmpty()) {
            throw new IllegalArgumentException("language must not be blank");
        }
        String langKey = normalizeLanguageKey(displayLang);

        if (translatedText == null || translatedText.isEmpty()) {
            Map<String, String> existing = manualTranslations.get(langKey);
            if (existing != null) {
                existing.remove(keyOrOriginal);
                pruneManualLanguageIfEmpty(langKey);
            }
            return;
        }

        recordLanguageDisplayName(displayLang);
        manualLanguages.add(langKey);
        manualTranslations.computeIfAbsent(langKey, ignored -> new LinkedHashMap<>())
                .put(keyOrOriginal, translatedText);
    }

    /**
     * Registers or removes a manual translation override from an AST-free {@link TranslationEntry}.
     *
     * <p>Registers under {@link TranslationEntry#id()} (when non-blank and not {@code "strings"})
     * and {@link TranslationEntry#sourceText()} (when non-null). Passing a {@code null} or empty
     * {@link TranslationEntry#translatedText()} removes matching overrides without polluting
     * {@link #getAvailableLanguages()}.</p>
     *
     * @param language target language identifier (must not be null or blank)
     * @param entry    translation entry describing key/source and target text (must not be null)
     * @throws NullPointerException     if {@code language} or {@code entry} is {@code null}
     * @throws IllegalArgumentException if {@code language} is blank
     */
    public synchronized void registerTranslation(String language, TranslationEntry entry) {
        Objects.requireNonNull(language, "language must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        String displayLang = language.trim();
        if (displayLang.isEmpty()) {
            throw new IllegalArgumentException("language must not be blank");
        }
        String langKey = normalizeLanguageKey(displayLang);
        String entryId = entry.id() != null ? entry.id().trim() : "";
        boolean hasUsableId = !entryId.isEmpty() && !"strings".equalsIgnoreCase(entryId);
        String translated = entry.translatedText();

        if (translated == null || translated.isEmpty()) {
            Map<String, String> existing = manualTranslations.get(langKey);
            if (existing != null) {
                if (hasUsableId) {
                    existing.remove(entryId);
                }
                if (entry.sourceText() != null) {
                    existing.remove(entry.sourceText());
                }
                pruneManualLanguageIfEmpty(langKey);
            }
            return;
        }

        if (!hasUsableId && entry.sourceText() == null) {
            return;
        }

        recordLanguageDisplayName(displayLang);
        manualLanguages.add(langKey);
        Map<String, String> langMap = manualTranslations.computeIfAbsent(langKey, ignored -> new LinkedHashMap<>());
        if (hasUsableId) {
            langMap.put(entryId, translated);
        }
        if (entry.sourceText() != null) {
            langMap.put(entry.sourceText(), translated);
        }
    }

    /**
     * Translates a dialogue line for the active language.
     *
     * <p>Performs a fast O(1) fallback to {@code originalText} when the default language is active
     * and no override translations have been registered for it. Otherwise checks registered and
     * project-indexed translations by trimmed {@code lineId} first, then by {@code originalText}.</p>
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
            String trimmedLineId = lineId.trim();
            if (!trimmedLineId.isEmpty()) {
                String byManualId = lookupMap(manualTranslations, langKey, trimmedLineId);
                if (byManualId != null) {
                    return byManualId;
                }
                String byProjectId = lookupMap(projectById, langKey, trimmedLineId);
                if (byProjectId != null) {
                    return byProjectId;
                }
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
     * languages discovered in indexed {@link TranslationBlock}s or manual registrations).
     *
     * @return immutable set of language identifiers
     */
    public synchronized Set<String> getAvailableLanguages() {
        Set<String> available = new LinkedHashSet<>();
        String defaultKey = normalizeLanguageKey(defaultLanguage);
        available.add(languageDisplayNames.getOrDefault(defaultKey, defaultLanguage));
        for (String langKey : indexedLanguages) {
            available.add(languageDisplayNames.getOrDefault(langKey, langKey));
        }
        for (String langKey : manualLanguages) {
            available.add(languageDisplayNames.getOrDefault(langKey, langKey));
        }
        return Collections.unmodifiableSet(available);
    }

    // ---- Internal Helpers ----

    private void pruneManualLanguageIfEmpty(String langKey) {
        Map<String, String> langMap = manualTranslations.get(langKey);
        if (langMap == null || langMap.isEmpty()) {
            manualTranslations.remove(langKey);
            manualLanguages.remove(langKey);
            if (!indexedLanguages.contains(langKey)
                    && !langKey.equals(normalizeLanguageKey(defaultLanguage))) {
                languageDisplayNames.remove(langKey);
            }
        }
    }

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
}
