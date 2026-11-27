package de.swfdtf.tools;

import de.swfdtf.state.LocalizationAdvancedServices.TranslationKeyAndMacroServices.ExtractedSourceEntry;
import de.swfdtf.state.LocalizationManager;
import de.swfdtf.state.TranslationEntry;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chapter 9 localization QA tooling: pseudo-localization stress generator, per-chapter translator
 * worklist splitting, missing source word counts, real-time >130% length warnings, LQA note
 * extraction, unclosed inline tag / escape / hardcoded UI string validators, translated full-text
 * search, localized marketing screenshot path layout, and DE/EN demo audit verification
 * ({@code #0703}, {@code #0706}, {@code #0713}, {@code #0716}, {@code #0721}, {@code #0722},
 * {@code #0731}, {@code #0736}, {@code #0746}, {@code #0752}, {@code #0753}, {@code #0757},
 * {@code #0761}, {@code #0765}).
 */
public final class LocalizationToolingServices {

    private LocalizationToolingServices() {
    }

    /**
     * Generates pseudo-localized strings {@code [!! ... !!]} with accented Latin characters and ~40%
     * length expansion while keeping {@code [var]} and formatting tags intact ({@code #0703}).
     */
    public static final class PseudoLocalizationGenerator {

        private static final String PLAIN_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        private static final String ACCENT_CHARS = "àbçdèfgħìjkłmnòpqřştùvwxÿžÀBÇDÈFGĦÌJKŁMNÒPQŘŞTÙVWXŸŽ";

        /**
         * Creates a {@code PseudoLocalizationGenerator}.
         */
        public PseudoLocalizationGenerator() {
        }

        /**
         * Converts source text into expanded accented pseudo-localization preserving {@code [var]} and
         * inline rich tags verbatim ({@code #0703}).
         *
         * @param sourceText source English/German string
         * @return accented +40% padded pseudo-localized string
         */
        public static String pseudoLocalize(String sourceText) {
            if (sourceText == null || sourceText.isEmpty()) {
                return "";
            }
            StringBuilder core = new StringBuilder();
            int depthBrace = 0;
            int depthBracket = 0;
            int plainChars = 0;
            for (int i = 0; i < sourceText.length(); i++) {
                char c = sourceText.charAt(i);
                if (c == '{') {
                    depthBrace++;
                    core.append(c);
                } else if (c == '}' && depthBrace > 0) {
                    depthBrace--;
                    core.append(c);
                } else if (c == '[') {
                    depthBracket++;
                    core.append(c);
                } else if (c == ']' && depthBracket > 0) {
                    depthBracket--;
                    core.append(c);
                } else if (depthBrace > 0 || depthBracket > 0) {
                    core.append(c);
                } else {
                    plainChars++;
                    int idx = PLAIN_CHARS.indexOf(c);
                    core.append(idx >= 0 ? ACCENT_CHARS.charAt(idx) : c);
                }
            }
            int extra = Math.max(2, (int) Math.round(plainChars * 0.40));
            String pad = "~".repeat(Math.min(extra, 24));
            return "[!! " + core + " " + pad + "!!]";
        }
    }

    /**
     * Translator worklist splitting per chapter, missing word count estimation (Dialogue vs UI),
     * live >130% length ratio traffic-light warning, and {@code ## LQA Note:} extraction
     * ({@code #0716}, {@code #0722}, {@code #0731}, {@code #0736}).
     */
    public static final class TranslatorWorklistAndStatsService {

        /**
         * Missing word summary split cleanly between dialogue and UI strings ({@code #0736}).
         *
         * @param missingDialogueWords missing dialogue source word count
         * @param missingUiWords       missing UI source word count
         */
        public record MissingWordStats(int missingDialogueWords, int missingUiWords) {
            /**
             * Returns total missing words across dialogue and UI.
             *
             * @return total missing word count
             */
            public int totalMissingWords() {
                return missingDialogueWords + missingUiWords;
            }
        }

        /**
         * Creates a {@code TranslatorWorklistAndStatsService}.
         */
        public TranslatorWorklistAndStatsService() {
        }

        /**
         * Groups untranslated entries cleanly by chapter without introducing duplicate entry IDs
         * ({@code #0716}).
         *
         * @param entries         extracted project entries
         * @param existingTargets already translated entry IDs
         * @return map of chapter/label name to deduplicated untranslated entries
         */
        public static Map<String, List<ExtractedSourceEntry>> buildUntranslatedWorklistByChapter(
                List<ExtractedSourceEntry> entries,
                Set<String> existingTargets
        ) {
            Set<String> translated = existingTargets != null ? existingTargets : Set.of();
            Set<String> seenIds = new LinkedHashSet<>();
            Map<String, List<ExtractedSourceEntry>> byChapter = new LinkedHashMap<>();
            if (entries != null) {
                for (ExtractedSourceEntry entry : entries) {
                    if (entry == null || translated.contains(entry.id()) || !seenIds.add(entry.id())) {
                        continue;
                    }
                    String ch = (entry.labelName() != null && !entry.labelName().isBlank())
                            ? entry.labelName() : "default";
                    byChapter.computeIfAbsent(ch, k -> new ArrayList<>()).add(entry);
                }
            }
            Map<String, List<ExtractedSourceEntry>> out = new LinkedHashMap<>();
            for (Map.Entry<String, List<ExtractedSourceEntry>> e : byChapter.entrySet()) {
                out.put(e.getKey(), List.copyOf(e.getValue()));
            }
            return Collections.unmodifiableMap(out);
        }

        /**
         * Sums missing words separated by Dialogue vs UI context ({@code #0736}).
         *
         * @param allEntries       ordered entries in project
         * @param translatedById   completed translations keyed by entry ID
         * @return {@link MissingWordStats}
         */
        public static MissingWordStats computeMissingWordStats(
                List<TranslationEntry> allEntries,
                Map<String, String> translatedById
        ) {
            Map<String, String> done = translatedById != null ? translatedById : Map.of();
            int dlg = 0;
            int ui = 0;
            if (allEntries != null) {
                for (TranslationEntry entry : allEntries) {
                    String val = done.get(entry.id());
                    if (val != null && !val.isBlank()) {
                        continue;
                    }
                    int wc = countWords(entry.sourceText());
                    String ctx = entry.context() != null ? entry.context().toLowerCase(Locale.ROOT) : "";
                    if (ctx.contains("ui") || ctx.contains("screen") || ctx.contains("button")) {
                        ui += wc;
                    } else {
                        dlg += wc;
                    }
                }
            }
            return new MissingWordStats(dlg, ui);
        }

        /**
         * Evaluates live translation expansion traffic light: warns above 130% original length ({@code #0731}).
         *
         * @param originalText   source text
         * @param translatedText candidate translation
         * @return {@code "WARN_OVER_130_PERCENT"} or {@code "OK"}
         */
        public static String evaluateLengthTrafficLight(String originalText, String translatedText) {
            if (originalText == null || originalText.isEmpty() || translatedText == null) {
                return "OK";
            }
            double ratio = (double) translatedText.length() / originalText.length();
            return ratio > 1.30 ? "WARN_OVER_130_PERCENT" : "OK";
        }

        /**
         * Extracts special {@code ## LQA Note: ...} comments for translators while ignoring standard
         * {@code //} developer comments ({@code #0722}).
         *
         * @param scriptText raw {@code .vjava} script content
         * @return list of extracted LQA note strings
         */
        public static List<String> extractLqaNotes(String scriptText) {
            if (scriptText == null || scriptText.isEmpty()) {
                return List.of();
            }
            List<String> notes = new ArrayList<>();
            for (String line : scriptText.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("## LQA Note:")) {
                    notes.add(trimmed.substring("## LQA Note:".length()).trim());
                }
            }
            return List.copyOf(notes);
        }

        private static int countWords(String text) {
            if (text == null || text.isBlank()) {
                return 0;
            }
            return text.trim().split("\\s+").length;
        }
    }

    /**
     * Static translation syntax/tag validation, bilingual translated search, screenshot export paths,
     * and DE/EN demo completeness verification
     * ({@code #0706}, {@code #0713}, {@code #0721}, {@code #0746}, {@code #0752}, {@code #0753},
     * {@code #0757}, {@code #0761}, {@code #0765}).
     */
    public static final class TranslationStaticValidator {

        /**
         * Issue reported by translation linter ({@code #0706}, {@code #0713}, {@code #0753}).
         *
         * @param lineNumber 1-based line number
         * @param code       issue code
         * @param message    human-readable diagnostic
         */
        public record ValidationIssue(int lineNumber, String code, String message) {
        }

        /**
         * Bilingual search match linking translated text back to source label ({@code #0757}).
         *
         * @param language       language identifier
         * @param labelName      originating label name
         * @param entryId        entry ID
         * @param translatedText matched target text
         * @param sourceText     corresponding source text
         */
        public record BilingualSearchHit(
                String language,
                String labelName,
                String entryId,
                String translatedText,
                String sourceText
        ) {
        }

        /**
         * Creates a {@code TranslationStaticValidator}.
         */
        public TranslationStaticValidator() {
        }

        /**
         * Checks text entries or {@code tl/} script lines for unclosed inline tags and broken escape
         * or quote syntax ({@code #0713}, {@code #0753}, {@code #0761}).
         *
         * @param translationLines ordered script lines from a {@code tl/<lang>/*.vjava} file
         * @return list of {@link ValidationIssue}s with 1-based line numbers
         */
        public static List<ValidationIssue> validateTranslationLines(List<String> translationLines) {
            if (translationLines == null || translationLines.isEmpty()) {
                return List.of();
            }
            List<ValidationIssue> issues = new ArrayList<>();
            for (int i = 0; i < translationLines.size(); i++) {
                int lineNo = i + 1;
                String line = translationLines.get(i);
                if (line == null) {
                    continue;
                }
                if (hasUnescapedOddQuotes(line)) {
                    issues.add(new ValidationIssue(lineNo, "UNCLOSED_QUOTE",
                            "Unbalanced quotation mark in translation line " + lineNo));
                }
                if (hasUnclosedInlineTag(line)) {
                    issues.add(new ValidationIssue(lineNo, "UNCLOSED_INLINE_TAG",
                            "Unclosed rich-text formatting tag on line " + lineNo));
                }
            }
            return List.copyOf(issues);
        }

        /**
         * Flags potential hardcoded UI string literals outside localization tables ({@code #0706}).
         *
         * @param uiSourceLines Java/script UI lines
         * @return list of warnings with line numbers
         */
        public static List<ValidationIssue> warnHardcodedUiStrings(List<String> uiSourceLines) {
            if (uiSourceLines == null) {
                return List.of();
            }
            List<ValidationIssue> issues = new ArrayList<>();
            Pattern literalText = Pattern.compile("new TextButton\\(\\s*\"([^\"]{3,})\"");
            for (int i = 0; i < uiSourceLines.size(); i++) {
                String line = uiSourceLines.get(i);
                if (line == null || line.contains("translateUi(")) {
                    continue;
                }
                Matcher m = literalText.matcher(line);
                if (m.find()) {
                    issues.add(new ValidationIssue(i + 1, "HARDCODED_UI_STRING",
                            "Hardcoded UI literal '" + m.group(1) + "' should use LocalizationManager.translateUi"));
                }
            }
            return List.copyOf(issues);
        }

        /**
         * Searches translated entries matching {@code query} and returns bilingual hits showing source
         * label and source text ({@code #0757}).
         *
         * @param language     target language
         * @param labelById    map of entryId to enclosing label name
         * @param entries      target language translation entries
         * @param query        search term
         * @return matching {@link BilingualSearchHit}s
         */
        public static List<BilingualSearchHit> searchTranslatedEntries(
                String language,
                Map<String, String> labelById,
                List<TranslationEntry> entries,
                String query
        ) {
            if (entries == null || query == null || query.isBlank()) {
                return List.of();
            }
            String q = query.trim().toLowerCase(Locale.ROOT);
            Map<String, String> labels = labelById != null ? labelById : Map.of();
            List<BilingualSearchHit> hits = new ArrayList<>();
            for (TranslationEntry entry : entries) {
                if (entry.translatedText().toLowerCase(Locale.ROOT).contains(q)) {
                    hits.add(new BilingualSearchHit(
                            language,
                            labels.getOrDefault(entry.id(), entry.context()),
                            entry.id(),
                            entry.translatedText(),
                            entry.sourceText()
                    ));
                }
            }
            return List.copyOf(hits);
        }

        /**
         * Builds per-language marketing screenshot output path {@code dist/screenshots/<lang>/<scene>.png}
         * ({@code #0752}).
         *
         * @param distRoot  distribution root directory
         * @param language  language directory segment
         * @param sceneName normalized screenshot scene name
         * @return resolved screenshot target path
         */
        public static Path resolveLocalizedScreenshotPath(Path distRoot, String language, String sceneName) {
            Objects.requireNonNull(distRoot, "distRoot must not be null");
            String safeLang = (language != null && !language.isBlank()) ? language.trim() : "default";
            String safeScene = (sceneName != null && !sceneName.isBlank())
                    ? sceneName.trim().replaceAll("[^A-Za-z0-9._-]", "_") : "scene";
            return distRoot.resolve("screenshots").resolve(safeLang).resolve(safeScene + ".png");
        }

        /**
         * Audits bilingual DE/EN completeness across registered keys using {@link LocalizationManager}
         * ({@code #0721}, {@code #0746}, {@code #0765}).
         *
         * @param localization populated {@link LocalizationManager}
         * @param requiredKeys UI &amp; dialogue keys that must resolve to non-blank strings in both DE &amp; EN
         * @param languages    languages to verify (e.g. {@code List.of("deutsch", "english")})
         * @return list of missing {@code lang:key} errors (empty if 100% localized)
         */
        public static List<String> verifyMultiLanguageCompleteness(
                LocalizationManager localization,
                List<String> requiredKeys,
                List<String> languages
        ) {
            Objects.requireNonNull(localization, "localization must not be null");
            if (requiredKeys == null || languages == null) {
                return List.of();
            }
            List<String> missing = new ArrayList<>();
            for (String lang : languages) {
                localization.setLanguage(lang);
                for (String key : requiredKeys) {
                    String resolved = localization.translateUi(key, "__MISSING__");
                    if (resolved == null || resolved.isBlank() || "__MISSING__".equals(resolved)) {
                        missing.add(lang + ":" + key);
                    }
                }
            }
            return List.copyOf(missing);
        }

        private static boolean hasUnescapedOddQuotes(String line) {
            int quotes = 0;
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                    quotes++;
                }
            }
            return (quotes % 2) != 0;
        }

        private static boolean hasUnclosedInlineTag(String line) {
            List<String> stack = new ArrayList<>();
            Matcher m = Pattern.compile("\\{(/?)([a-zA-Z]+)(?:=[^}]*)?\\}").matcher(line);
            while (m.find()) {
                boolean closing = !m.group(1).isEmpty();
                String tag = m.group(2).toLowerCase(Locale.ROOT);
                if ("w".equals(tag) || "p".equals(tag) || "nw".equals(tag)
                        || "fast".equals(tag) || "instant".equals(tag) || "image".equals(tag)) {
                    continue;
                }
                if (!closing) {
                    stack.add(tag);
                } else {
                    int idx = stack.lastIndexOf(tag);
                    if (idx >= 0) {
                        stack.remove(idx);
                    }
                }
            }
            return !stack.isEmpty();
        }
    }
}
