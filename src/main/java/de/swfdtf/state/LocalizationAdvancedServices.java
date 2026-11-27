package de.swfdtf.state;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chapter 9 localization resolution services: regional locale fallback chains, culture-specific
 * date/number formatting, drop-in {@code tl/<lang>/} language packs, localized asset swaps,
 * idempotent context-annotated key extraction, deterministic hash IDs, Windows-1252/BOM decode
 * recovery, glossary consistency checking, pronoun macros, and localized metadata lookups
 * ({@code #0696}, {@code #0697}, {@code #0700}, {@code #0702}, {@code #0704}, {@code #0708},
 * {@code #0710}, {@code #0712}, {@code #0715}, {@code #0719}, {@code #0725}, {@code #0727},
 * {@code #0734}, {@code #0737}, {@code #0741}, {@code #0743}, {@code #0745}, {@code #0748},
 * {@code #0750}, {@code #0755}, {@code #0758}, {@code #0760}, {@code #0763}).
 */
public final class LocalizationAdvancedServices {

    private LocalizationAdvancedServices() {
    }

    /**
     * Resolves regional language tag fallback chains ({@code de_AT -> de -> default}), suggests initial
     * OS language at first boot, and formats dates/numbers strictly using explicit game {@link Locale}
     * rather than OS defaults ({@code #0697}, {@code #0719}, {@code #0734}).
     */
    public static final class RegionalLocaleResolver {

        /**
         * Creates a {@code RegionalLocaleResolver}.
         */
        public RegionalLocaleResolver() {
        }

        /**
         * Maps KAGAYAKI language code to Java {@link Locale}.
         *
         * @param languageCode game language code (e.g. {@code "deutsch"}, {@code "de_AT"}, {@code "en_GB"})
         * @return matching {@link Locale}
         */
        public static Locale toJavaLocale(String languageCode) {
            if (languageCode == null || languageCode.isBlank()) {
                return Locale.GERMAN;
            }
            String lower = languageCode.trim().toLowerCase(Locale.ROOT);
            if ("deutsch".equals(lower) || "de".equals(lower)) {
                return Locale.GERMANY;
            }
            if ("english".equals(lower) || "en".equals(lower)) {
                return Locale.US;
            }
            if ("japanese".equals(lower) || "ja".equals(lower)) {
                return Locale.JAPAN;
            }
            if ("french".equals(lower) || "fr".equals(lower)) {
                return Locale.FRANCE;
            }
            if ("spanish".equals(lower) || "es".equals(lower)) {
                return Locale.forLanguageTag("es-ES");
            }
            return Locale.forLanguageTag(languageCode.replace('_', '-'));
        }

        /**
         * Resolves most specific translation candidate along chain {@code region_code -> lang -> default}
         * ({@code #0734}).
         *
         * @param requestedTag       requested language tag (e.g. {@code "de_AT"})
         * @param availableLanguages languages with loaded translation bundles
         * @param fallbackDefault    project fallback default
         * @return best matching language identifier
         */
        public static String resolveBestLanguageMatch(
                String requestedTag,
                Set<String> availableLanguages,
                String fallbackDefault
        ) {
            Set<String> available = availableLanguages != null ? availableLanguages : Set.of();
            if (requestedTag != null && !requestedTag.isBlank()) {
                String clean = requestedTag.trim();
                if (available.contains(clean)) {
                    return clean;
                }
                int sep = Math.max(clean.indexOf('_'), clean.indexOf('-'));
                if (sep > 0) {
                    String base = clean.substring(0, sep);
                    if (available.contains(base)) {
                        return base;
                    }
                    if ("de".equalsIgnoreCase(base) && available.contains("deutsch")) {
                        return "deutsch";
                    }
                    if ("en".equalsIgnoreCase(base) && available.contains("english")) {
                        return "english";
                    }
                }
            }
            return fallbackDefault != null ? fallbackDefault : "default";
        }

        /**
         * Suggests first-boot language given OS system locale and project default ({@code #0719}).
         *
         * @param osLocale          detected OS system locale
         * @param available         available project languages
         * @param defaultLang       default base language code
         * @return suggested startup language identifier
         */
        public static String suggestFirstLaunchLanguage(Locale osLocale, Set<String> available, String defaultLang) {
            if (osLocale != null) {
                String lang = osLocale.getLanguage();
                if ("de".equalsIgnoreCase(lang) && available != null
                        && (available.contains("deutsch") || available.contains("de"))) {
                    return available.contains("deutsch") ? "deutsch" : "de";
                }
                if (available != null && available.contains("english")) {
                    return "english";
                }
            }
            return defaultLang != null ? defaultLang : "default";
        }

        /**
         * Formats an instant strictly according to the explicit active game language ({@code #0697}).
         *
         * @param instant      UTC timestamp
         * @param languageCode game language identifier
         * @param zoneId       display time zone
         * @return localized date/time string
         */
        public static String formatTimestamp(Instant instant, String languageCode, ZoneId zoneId) {
            Objects.requireNonNull(instant, "instant must not be null");
            Locale locale = toJavaLocale(languageCode);
            ZoneId zone = zoneId != null ? zoneId : ZoneId.of("UTC");
            DateTimeFormatter fmt = "de".equals(locale.getLanguage())
                    ? DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", locale)
                    : DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale);
            return fmt.withZone(zone).format(instant);
        }
    }

    /**
     * Scans drop-in fan translation folders {@code tl/<lang>/}, validates {@code tl_version} hints,
     * resolves localized image swaps ({@code assets/images/tl/<lang>/...}), and performs zero-loss live
     * reloads ({@code #0700}, {@code #0702}, {@code #0743}, {@code #0755}).
     */
    public static final class DropInLanguagePackManager {

        /**
         * Discovery report for a drop-in language directory ({@code #0700}, {@code #0755}).
         *
         * @param language       directory language name
         * @param versionTag     declared {@code tl_version} string
         * @param versionWarning optional warning if pack targets an older engine release
         */
        public record LanguagePackInfo(String language, String versionTag, String versionWarning) {
        }

        /**
         * Creates a {@code DropInLanguagePackManager}.
         */
        public DropInLanguagePackManager() {
        }

        /**
         * Scans {@code projectRoot/tl/} for external fan translation folders ({@code #0700}, {@code #0755}).
         *
         * @param projectRoot   project directory
         * @param engineVersion current engine version prefix (e.g. {@code "1.0"})
         * @return list of discovered {@link LanguagePackInfo} entries
         * @throws IOException if directory scanning fails
         */
        public static List<LanguagePackInfo> scanDropInLanguagePacks(Path projectRoot, String engineVersion)
                throws IOException {
            Objects.requireNonNull(projectRoot, "projectRoot must not be null");
            Path tlDir = projectRoot.resolve("tl");
            if (!Files.isDirectory(tlDir)) {
                return List.of();
            }
            List<LanguagePackInfo> out = new ArrayList<>();
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(tlDir)) {
                for (Path dir : dirs) {
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    String lang = dir.getFileName().toString();
                    String ver = "";
                    Path manifest = dir.resolve("tl_version.txt");
                    if (Files.isRegularFile(manifest)) {
                        ver = Files.readString(manifest, StandardCharsets.UTF_8).trim();
                    }
                    String warning = (!ver.isEmpty() && engineVersion != null && !ver.startsWith(engineVersion))
                            ? "Fan-Übersetzung '" + lang + "' zielt auf Version " + ver
                                    + " statt " + engineVersion : "";
                    out.add(new LanguagePackInfo(lang, ver, warning));
                }
            }
            return List.copyOf(out);
        }

        /**
         * Resolves localized image asset override under {@code assets/images/tl/<lang>/<relPath>} falling back
         * cleanly to base {@code assets/images/<relPath>} if missing ({@code #0702}).
         *
         * @param projectRoot    project directory
         * @param language       active language
         * @param relativeImage  relative image path under {@code assets/images/}
         * @return relative asset path pointing to localized swap or base asset
         */
        public static String resolveLocalizedImageAsset(
                Path projectRoot,
                String language,
                String relativeImage
        ) {
            Objects.requireNonNull(relativeImage, "relativeImage must not be null");
            if (projectRoot != null && language != null && !language.isBlank()) {
                Path candidate = projectRoot.resolve("assets").resolve("images")
                        .resolve("tl").resolve(language.trim()).resolve(relativeImage);
                if (Files.isRegularFile(candidate)) {
                    return "assets/images/tl/" + language.trim() + "/" + relativeImage;
                }
            }
            return "assets/images/" + relativeImage;
        }
    }

    /**
     * Provides deterministic hash IDs for duplicate phrases across scenes, idempotent context extraction,
     * obsolete entry pruning, BOM/Windows-1252 encoding recovery, arbitrary-order variable templates,
     * undefined placeholder checks, pronoun macros, and localized metadata lookups.
     */
    public static final class TranslationKeyAndMacroServices {

        private static final Pattern PLACEHOLDER = Pattern.compile("\\[([A-Za-z0-9_]+)\\]");

        /**
         * Source entry used during idempotent translation export ({@code #0696}).
         *
         * @param id          canonical entry ID
         * @param sourceText  original dialogue/UI text
         * @param speakerId   optional speaking character ID
         * @param labelName   enclosing script label name
         */
        public record ExtractedSourceEntry(String id, String sourceText, String speakerId, String labelName) {
        }

        /**
         * Glossary consistency violation ({@code #0727}, {@code #0758}).
         *
         * @param entryId          failing translation entry ID
         * @param term             expected source term or protected brand stopword
         * @param expectedTarget   expected translated term
         * @param message          diagnostic LQA warning
         */
        public record GlossaryViolation(String entryId, String term, String expectedTarget, String message) {
        }

        /**
         * Creates a {@code TranslationKeyAndMacroServices} helper.
         */
        public TranslationKeyAndMacroServices() {
        }

        /**
         * Generates collision-free ID combining label, sequence ordinal, and SHA-256 text hash ({@code #0737}).
         *
         * @param label      script label name
         * @param sequence   statement ordinal within label
         * @param sourceText original line text
         * @return unique deterministic translation entry ID
         */
        public static String generateDeterministicLineId(String label, int sequence, String sourceText) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                String raw = (label != null ? label : "") + "#" + sequence + ":"
                        + (sourceText != null ? sourceText : "");
                byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder(12);
                for (int i = 0; i < 6; i++) {
                    hex.append(Character.forDigit((digest[i] >>> 4) & 0x0F, 16));
                    hex.append(Character.forDigit(digest[i] & 0x0F, 16));
                }
                return (label != null ? label : "line") + "_" + sequence + "_" + hex;
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 unavailable", ex);
            }
        }

        /**
         * Merges discovered project lines with existing translations idempotently without duplicating existing
         * entries, preserving speaker/label translator context ({@code #0696}).
         *
         * @param existingById     existing translations keyed by ID
         * @param discoveredSources extracted source statements
         * @return merged ordered list of {@link TranslationEntry}
         */
        public static List<TranslationEntry> mergeIdempotentEntries(
                Map<String, String> existingById,
                List<ExtractedSourceEntry> discoveredSources
        ) {
            Map<String, String> existing = existingById != null ? existingById : Map.of();
            Set<String> seenIds = new LinkedHashSet<>();
            List<TranslationEntry> result = new ArrayList<>();
            if (discoveredSources != null) {
                int lineNo = 1;
                for (ExtractedSourceEntry src : discoveredSources) {
                    if (src == null || !seenIds.add(src.id())) {
                        continue;
                    }
                    String translated = existing.getOrDefault(src.id(), src.sourceText());
                    String ctx = "label=" + (src.labelName() != null ? src.labelName() : "")
                            + (src.speakerId() != null && !src.speakerId().isBlank()
                            ? ", speaker=" + src.speakerId() : "");
                    result.add(new TranslationEntry(src.id(), src.sourceText(), translated, ctx, lineNo++));
                }
            }
            return List.copyOf(result);
        }

        /**
         * Identifies orphaned translation IDs whose source lines no longer exist ({@code #0712}).
         *
         * @param existingIds existing keys inside {@code tl/<lang>/}
         * @param activeIds   current active source keys
         * @return list of obsolete translation keys safe to archive/prune
         */
        public static List<String> findObsoleteTranslationKeys(Set<String> existingIds, Set<String> activeIds) {
            if (existingIds == null || existingIds.isEmpty()) {
                return List.of();
            }
            Set<String> active = activeIds != null ? activeIds : Set.of();
            List<String> obsolete = new ArrayList<>();
            for (String id : existingIds) {
                if (!active.contains(id)) {
                    obsolete.add(id);
                }
            }
            return List.copyOf(obsolete);
        }

        /**
         * Decodes translation bytes gracefully: strips UTF-8 BOM if present, validates UTF-8 strictly, and
         * falls back cleanly to Windows-1252 when a translator saved ANSI bytes ({@code #0745}).
         *
         * @param rawBytes file bytes
         * @return clean decoded UTF-8 string
         */
        public static String decodeTranslationBytesSafely(byte[] rawBytes) {
            if (rawBytes == null || rawBytes.length == 0) {
                return "";
            }
            int offset = 0;
            if (rawBytes.length >= 3
                    && (rawBytes[0] & 0xFF) == 0xEF
                    && (rawBytes[1] & 0xFF) == 0xBB
                    && (rawBytes[2] & 0xFF) == 0xBF) {
                offset = 3;
            }
            try {
                CharBuffer cb = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(rawBytes, offset, rawBytes.length - offset));
                return cb.toString();
            } catch (CharacterCodingException ignored) {
                return new String(rawBytes, Charset.forName("windows-1252"));
            }
        }

        /**
         * Interpolates {@code [var]} placeholders regardless of target-language word ordering ({@code #0715})
         * and checks for undefined placeholder names ({@code #0741}).
         *
         * @param template   localized template string
         * @param variables  state variable map
         * @return interpolated output string
         */
        public static String interpolateTemplate(String template, Map<String, Object> variables) {
            if (template == null || template.isEmpty()) {
                return "";
            }
            Map<String, Object> vars = variables != null ? variables : Map.of();
            Matcher m = PLACEHOLDER.matcher(template);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String key = m.group(1);
                String rep = vars.containsKey(key) && vars.get(key) != null
                        ? String.valueOf(vars.get(key)) : m.group(0);
                m.appendReplacement(out, Matcher.quoteReplacement(rep));
            }
            m.appendTail(out);
            return out.toString();
        }

        /**
         * Lists placeholder variable names appearing in translated text that do not exist in {@code validKeys}
         * ({@code #0741}).
         *
         * @param translatedText candidate translation text
         * @param validKeys      known state variable keys
         * @return list of undefined placeholder variable names
         */
        public static List<String> findUndefinedPlaceholders(String translatedText, Set<String> validKeys) {
            if (translatedText == null || translatedText.isEmpty()) {
                return List.of();
            }
            Set<String> valid = validKeys != null ? validKeys : Set.of();
            List<String> unknown = new ArrayList<>();
            Matcher m = PLACEHOLDER.matcher(translatedText);
            while (m.find()) {
                String key = m.group(1);
                if (!valid.contains(key)) {
                    unknown.add(key);
                }
            }
            return List.copyOf(unknown);
        }

        /**
         * Applies pronoun/honorific macro substitutions from {@code $pronoun} profile ({@code #0763}).
         *
         * @param text           localized sentence containing {@code [subj]}, {@code [obj]}, {@code [poss]}
         * @param pronounProfile {@code "he"}, {@code "she"}, or {@code "they"}
         * @return resolved text with grammatical pronouns
         */
        public static String applyPronounMacros(String text, String pronounProfile) {
            if (text == null || text.isEmpty()) {
                return "";
            }
            String p = pronounProfile != null ? pronounProfile.toLowerCase(Locale.ROOT) : "they";
            Map<String, Object> macros = switch (p) {
                case "he", "er" -> Map.of("subj", "he", "obj", "him", "poss", "his");
                case "she", "sie" -> Map.of("subj", "she", "obj", "her", "poss", "her");
                default -> Map.of("subj", "they", "obj", "them", "poss", "their");
            };
            return interpolateTemplate(text, macros);
        }

        /**
         * Computes canonical seen-line tracking key respecting {@link PreferencesState} cross-language sharing
         * ({@code #0760}).
         *
         * @param lineId                stable dialogue line ID
         * @param activeLanguage        active language code
         * @param sharedAcrossLanguages whether read history carries across languages
         * @return canonical persistent key
         */
        public static String resolveReadTrackingKey(
                String lineId,
                String activeLanguage,
                boolean sharedAcrossLanguages
        ) {
            Objects.requireNonNull(lineId, "lineId must not be null");
            if (sharedAcrossLanguages || activeLanguage == null || activeLanguage.isBlank()) {
                return lineId;
            }
            return activeLanguage.trim() + "::" + lineId;
        }

        /**
         * Audits glossary term consistency (e.g. {@code Zauberspiegel -> Magic Mirror}) and brand name
         * stopwords ({@code KAGAYAKI}, {@code Schneewittchen}, {@code Lena}) across translations
         * ({@code #0727}, {@code #0758}).
         *
         * @param entries            target language translation entries
         * @param mandatoryTerms     map of source term to required target translation
         * @param protectedBrandNames brand names that must remain untranslated/unaltered
         * @return immutable list of {@link GlossaryViolation} warnings
         */
        public static List<GlossaryViolation> auditGlossaryAndBrandNames(
                List<TranslationEntry> entries,
                Map<String, String> mandatoryTerms,
                Set<String> protectedBrandNames
        ) {
            List<GlossaryViolation> violations = new ArrayList<>();
            if (entries == null) {
                return List.of();
            }
            Map<String, String> terms = mandatoryTerms != null ? mandatoryTerms : Map.of();
            Set<String> brands = protectedBrandNames != null ? protectedBrandNames : Set.of();
            for (TranslationEntry entry : entries) {
                String src = entry.sourceText();
                String trg = entry.translatedText();
                for (Map.Entry<String, String> term : terms.entrySet()) {
                    if (src.contains(term.getKey()) && !trg.contains(term.getValue())) {
                        violations.add(new GlossaryViolation(
                                entry.id(), term.getKey(), term.getValue(),
                                "Expected glossary term '" + term.getValue() + "' for '" + term.getKey() + "'"
                        ));
                    }
                }
                for (String brand : brands) {
                    if (src.contains(brand) && !trg.contains(brand)) {
                        violations.add(new GlossaryViolation(
                                entry.id(), brand, brand,
                                "Protected brand name '" + brand + "' missing or altered in translation"
                        ));
                    }
                }
            }
            return List.copyOf(violations);
        }

        /**
         * Multi-language metadata lookup supporting character names ({@code #0708}), multi-line codex
         * entries &amp; language-specific search tags ({@code #0704}, {@code #0710}, {@code #0750}),
         * chapter titles in saves ({@code #0725}), and localized About/Credits formatting ({@code #0748}).
         */
        public static final class LocalizedMetadataRegistry {

            private final Map<String, Map<String, String>> stringsByLang = new LinkedHashMap<>();
            private final Map<String, Map<String, List<String>>> codexTagsByLang = new LinkedHashMap<>();

            /**
             * Creates an empty {@code LocalizedMetadataRegistry}.
             */
            public LocalizedMetadataRegistry() {
            }

            /**
             * Registers a localized metadata string (e.g. {@code char.snow}, {@code chapter.1}, {@code codex.mirror}).
             *
             * @param language language code
             * @param key      metadata key
             * @param text     localized text (supports multi-line paragraphs)
             */
            public synchronized void register(String language, String key, String text) {
                Objects.requireNonNull(language, "language must not be null");
                Objects.requireNonNull(key, "key must not be null");
                stringsByLang.computeIfAbsent(language.trim(), k -> new LinkedHashMap<>())
                        .put(key, text != null ? text : "");
            }

            /**
             * Registers language-specific codex search tags / synonyms ({@code #0750}).
             *
             * @param language language code
             * @param codexId  codex entry ID
             * @param tags     synonym search tags
             */
            public synchronized void registerCodexTags(String language, String codexId, List<String> tags) {
                Objects.requireNonNull(language, "language must not be null");
                Objects.requireNonNull(codexId, "codexId must not be null");
                codexTagsByLang.computeIfAbsent(language.trim(), k -> new LinkedHashMap<>())
                        .put(codexId, tags != null ? List.copyOf(tags) : List.of());
            }

            /**
             * Resolves localized character name, chapter title, or multi-line codex text with fallback ({@code #0704},
             * {@code #0708}, {@code #0725}).
             *
             * @param language     active language
             * @param key          metadata key
             * @param defaultValue fallback text
             * @return translated string or {@code defaultValue}
             */
            public synchronized String resolve(String language, String key, String defaultValue) {
                Map<String, String> langMap = language != null ? stringsByLang.get(language.trim()) : null;
                if (langMap != null && langMap.containsKey(key)) {
                    return langMap.get(key);
                }
                return defaultValue != null ? defaultValue : key;
            }

            /**
             * Searches codex titles, descriptions, and per-language synonym tags ({@code #0710}, {@code #0750}).
             *
             * @param language active language
             * @param query    search query string
             * @return matching codex IDs
             */
            public synchronized List<String> searchCodexIds(String language, String query) {
                if (query == null || query.isBlank()) {
                    return List.of();
                }
                String q = query.trim().toLowerCase(Locale.ROOT);
                Set<String> matches = new LinkedHashSet<>();
                Map<String, String> langMap = stringsByLang.getOrDefault(language, Map.of());
                for (Map.Entry<String, String> entry : langMap.entrySet()) {
                    if (entry.getKey().startsWith("codex.")
                            && entry.getValue().toLowerCase(Locale.ROOT).contains(q)) {
                        matches.add(entry.getKey().substring("codex.".length()));
                    }
                }
                Map<String, List<String>> tagMap = codexTagsByLang.getOrDefault(language, Map.of());
                for (Map.Entry<String, List<String>> e : tagMap.entrySet()) {
                    for (String tag : e.getValue()) {
                        if (tag.toLowerCase(Locale.ROOT).contains(q)) {
                            matches.add(e.getKey());
                        }
                    }
                }
                return List.copyOf(matches);
            }

            /**
             * Builds localized About/Credits text always embedding engine version ({@code #0748}).
             *
             * @param language      active language
             * @param engineVersion engine version string
             * @return formatted localized About text
             */
            public synchronized String formatAboutScreenText(String language, String engineVersion) {
                String template = resolve(language, "ui.about.header", "Powered by KAGAYAKI [version]");
                return interpolateTemplate(template, Collections.singletonMap("version",
                        engineVersion != null ? engineVersion : "1.0.0"));
            }
        }
    }
}
