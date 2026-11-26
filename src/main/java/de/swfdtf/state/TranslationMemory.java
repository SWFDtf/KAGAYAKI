package de.swfdtf.state;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory Translation Memory storing source-to-translation pairs with exact 100% lookup
 * and normalized Levenshtein fuzzy matching for minor script edits.
 */
public final class TranslationMemory {

    /** Default normalized similarity threshold above which a match is emitted as {@code fuzzy}. */
    public static final double DEFAULT_FUZZY_THRESHOLD = 0.75;

    /**
     * Matched translation lookup result.
     *
     * @param sourceText     original stored source string
     * @param translatedText stored translation string
     * @param similarity     similarity score in {@code [0.0, 1.0]} ({@code 1.0} for exact match)
     * @param fuzzy          {@code true} if matched heuristically rather than exact 100%
     */
    public record MatchResult(String sourceText, String translatedText, double similarity, boolean fuzzy) {
        @Override
        public String toString() {
            return (fuzzy ? "# fuzzy " : "")
                    + "MatchResult[similarity="
                    + String.format(Locale.ROOT, "%.2f", similarity)
                    + ", translatedText=" + translatedText + "]";
        }
    }

    private final Map<String, String> entries = new LinkedHashMap<>();
    private final double fuzzyThreshold;

    /**
     * Creates a {@code TranslationMemory} with {@link #DEFAULT_FUZZY_THRESHOLD}.
     */
    public TranslationMemory() {
        this(DEFAULT_FUZZY_THRESHOLD);
    }

    /**
     * Creates a {@code TranslationMemory} with an explicit fuzzy similarity threshold.
     *
     * @param fuzzyThreshold minimum normalized similarity in {@code (0.0, 1.0]}
     */
    public TranslationMemory(double fuzzyThreshold) {
        if (!Double.isFinite(fuzzyThreshold) || fuzzyThreshold <= 0.0 || fuzzyThreshold > 1.0) {
            throw new IllegalArgumentException("fuzzyThreshold must be in (0.0, 1.0], got " + fuzzyThreshold);
        }
        this.fuzzyThreshold = fuzzyThreshold;
    }

    /**
     * Learns or updates a source-to-translation mapping in memory.
     *
     * @param source      source dialogue or UI text (must not be {@code null})
     * @param translation translated text (must not be {@code null})
     */
    public synchronized void learn(String source, String translation) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(translation, "translation must not be null");
        entries.put(source, translation);
    }

    /**
     * Looks up an exact 100% match or the highest-scoring fuzzy match exceeding the threshold.
     *
     * @param source query source string (nullable)
     * @return matching {@link MatchResult} or {@link Optional#empty()} if none meets threshold
     */
    public synchronized Optional<MatchResult> match(String source) {
        if (source == null || source.isEmpty() || entries.isEmpty()) {
            return Optional.empty();
        }
        String exact = entries.get(source);
        if (exact != null) {
            return Optional.of(new MatchResult(source, exact, 1.0, false));
        }
        String bestSource = null;
        String bestTranslation = null;
        double bestScore = -1.0;
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            double sim = similarity(source, entry.getKey());
            if (sim > bestScore) {
                bestScore = sim;
                bestSource = entry.getKey();
                bestTranslation = entry.getValue();
            }
        }
        if (bestSource != null && bestScore >= fuzzyThreshold) {
            return Optional.of(new MatchResult(bestSource, bestTranslation, bestScore, true));
        }
        return Optional.empty();
    }

    /**
     * Returns the number of learned source-to-translation entries.
     *
     * @return number of stored entries
     */
    public synchronized int size() {
        return entries.size();
    }

    /**
     * Clears all stored translation memory entries.
     */
    public synchronized void clear() {
        entries.clear();
    }

    private static double similarity(String a, String b) {
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) {
            return 1.0;
        }
        int dist = levenshteinDistance(a, b);
        return Math.max(0.0, 1.0 - ((double) dist / maxLen));
    }

    private static int levenshteinDistance(String a, String b) {
        int m = a.length();
        int n = b.length();
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= n; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[n];
    }
}
