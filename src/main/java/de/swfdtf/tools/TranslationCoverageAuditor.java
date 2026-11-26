package de.swfdtf.tools;

import de.swfdtf.parser.VJavaAst;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Audits localization coverage by comparing dialogue {@link VJavaAst.SayStatement} texts
 * across all labels against {@link VJavaAst.TranslateBlock} entries per target language.
 */
public final class TranslationCoverageAuditor {

    /**
     * Per-language translation coverage summary.
     *
     * @param language          target language name
     * @param expectedEntries   total number of expected dialogue lines
     * @param translatedEntries number of dialogue lines with a non-blank translation
     * @param untranslatedTexts immutable list of source dialogue texts lacking a translation
     */
    public record LanguageCoverage(
            String language,
            int expectedEntries,
            int translatedEntries,
            List<String> untranslatedTexts
    ) {

        /**
         * Validates fields and wraps {@code untranslatedTexts} in an unmodifiable list.
         */
        public LanguageCoverage {
            Objects.requireNonNull(language, "language must not be null");
            if (language.isBlank()) {
                throw new IllegalArgumentException("language must not be blank");
            }
            if (expectedEntries < 0 || translatedEntries < 0) {
                throw new IllegalArgumentException("Entry counts must be >= 0");
            }
            untranslatedTexts = untranslatedTexts != null ? List.copyOf(untranslatedTexts) : List.of();
        }

        /**
         * Computes the percentage of expected dialogue entries that have translations.
         *
         * @return coverage percentage in {@code [0.0, 100.0]} (returns {@code 100.0} when {@code expectedEntries == 0})
         */
        public double coveragePercent() {
            if (expectedEntries == 0) {
                return 100.0;
            }
            return (translatedEntries * 100.0) / expectedEntries;
        }
    }

    /**
     * Aggregated localization coverage report across all languages discovered in {@link VJavaAst.TranslateBlock}s.
     *
     * @param totalDialogueLines total number of {@link VJavaAst.SayStatement} dialogue lines in the project
     * @param languages          unmodifiable map of language identifier to {@link LanguageCoverage}
     */
    public record CoverageReport(int totalDialogueLines, Map<String, LanguageCoverage> languages) {

        /**
         * Validates totals and wraps {@code languages} in an unmodifiable sorted map.
         */
        public CoverageReport {
            if (totalDialogueLines < 0) {
                throw new IllegalArgumentException("totalDialogueLines must be >= 0");
            }
            TreeMap<String, LanguageCoverage> sorted = new TreeMap<>();
            if (languages != null) {
                for (Map.Entry<String, LanguageCoverage> entry : languages.entrySet()) {
                    String langKey = Objects.requireNonNull(entry.getKey(), "language key must not be null");
                    LanguageCoverage cov = Objects.requireNonNull(entry.getValue(), "coverage must not be null");
                    sorted.put(langKey, cov);
                }
            }
            languages = Collections.unmodifiableMap(sorted);
        }

        /**
         * Looks up coverage details for {@code language} using exact or case-insensitive matching.
         *
         * @param language target language identifier (must not be {@code null})
         * @return matching {@link LanguageCoverage}, if present
         */
        public Optional<LanguageCoverage> findLanguage(String language) {
            Objects.requireNonNull(language, "language must not be null");
            String trimmed = language.trim();
            if (trimmed.isEmpty()) {
                return Optional.empty();
            }
            LanguageCoverage exact = languages.get(trimmed);
            if (exact != null) {
                return Optional.of(exact);
            }
            for (LanguageCoverage cov : languages.values()) {
                if (cov.language().equalsIgnoreCase(trimmed)) {
                    return Optional.of(cov);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Creates a new {@code TranslationCoverageAuditor}.
     */
    public TranslationCoverageAuditor() {
    }

    /**
     * Audits translation coverage for every language declared in {@code project.translations()}.
     *
     * @param project unified project AST (must not be {@code null})
     * @return immutable {@link CoverageReport}
     */
    public CoverageReport audit(VJavaAst.ProjectAst project) {
        Objects.requireNonNull(project, "project must not be null");

        List<String> dialogueTexts = new ArrayList<>();
        for (VJavaAst.LabelNode label : project.labels().values()) {
            collectDialogueTexts(label.statements(), dialogueTexts);
        }

        Map<String, String> displayNames = new LinkedHashMap<>();
        Map<String, Set<String>> translatedByLang = new LinkedHashMap<>();

        for (VJavaAst.TranslateBlock block : project.translations()) {
            if (block.language() == null || block.language().isBlank()) {
                continue;
            }
            String displayName = block.language().trim();
            String normKey = displayName.toLowerCase(Locale.ROOT);
            displayNames.putIfAbsent(normKey, displayName);
            Set<String> translatedOriginals = translatedByLang.computeIfAbsent(
                    normKey, ignored -> new LinkedHashSet<>());

            for (VJavaAst.TranslateEntry entry : block.entries()) {
                if (entry.originalText() != null
                        && !entry.originalText().isEmpty()
                        && entry.translatedText() != null
                        && !entry.translatedText().isBlank()) {
                    translatedOriginals.add(entry.originalText());
                }
            }
        }

        Map<String, LanguageCoverage> coverages = new TreeMap<>();
        int expected = dialogueTexts.size();
        for (Map.Entry<String, String> langEntry : displayNames.entrySet()) {
            String normKey = langEntry.getKey();
            String displayName = langEntry.getValue();
            Set<String> translatedSet = translatedByLang.getOrDefault(normKey, Set.of());

            int translatedCount = 0;
            List<String> untranslated = new ArrayList<>();
            for (String text : dialogueTexts) {
                if (translatedSet.contains(text)) {
                    translatedCount++;
                } else {
                    untranslated.add(text);
                }
            }

            coverages.put(
                    displayName,
                    new LanguageCoverage(displayName, expected, translatedCount, untranslated)
            );
        }

        return new CoverageReport(expected, coverages);
    }

    private static void collectDialogueTexts(List<VJavaAst.Statement> statements, List<String> out) {
        for (VJavaAst.Statement stmt : statements) {
            if (stmt instanceof VJavaAst.SayStatement say) {
                if (say.text() != null && !say.text().isEmpty()) {
                    out.add(say.text());
                }
            } else if (stmt instanceof VJavaAst.IfStatement ifStmt) {
                for (VJavaAst.IfBranch branch : ifStmt.branches()) {
                    if (!branch.body().isEmpty()) {
                        collectDialogueTexts(branch.body(), out);
                    }
                }
            } else if (stmt instanceof VJavaAst.MenuStatement menu) {
                for (VJavaAst.MenuChoice choice : menu.choices()) {
                    if (!choice.body().isEmpty()) {
                        collectDialogueTexts(choice.body(), out);
                    }
                }
            }
        }
    }
}
