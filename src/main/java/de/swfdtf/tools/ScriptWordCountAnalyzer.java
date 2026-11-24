package de.swfdtf.tools;

import de.swfdtf.parser.VJavaAst;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Computes dialogue line, word, character, and estimated reading-time statistics for a
 * {@link VJavaAst.ProjectAst}.
 *
 * <p>Recursively traverses {@link VJavaAst.SayStatement}s across all script labels, including
 * nested {@link VJavaAst.IfStatement} and {@link VJavaAst.MenuStatement} branches, and strips
 * inline {@code {...}} rich-text tags prior to counting words and characters.</p>
 */
public final class ScriptWordCountAnalyzer {

    /** Assumed reading speed in words per minute used to estimate reading time. */
    public static final double WORDS_PER_MINUTE = 200.0;

    /** Canonical speaker ID assigned to narrator dialogue lines without an explicit speaker. */
    public static final String NARRATOR_SPEAKER_ID = "narrator";

    private static final Pattern WHITESPACE_SPLIT = Pattern.compile("\\s+");

    /**
     * Aggregated dialogue statistics for a single speaker.
     *
     * @param speakerId speaker identifier (or {@value #NARRATOR_SPEAKER_ID} for narration)
     * @param lineCount number of spoken dialogue lines
     * @param wordCount total number of words across all lines
     * @param charCount total number of characters across all tag-stripped lines
     */
    public record SpeakerStats(String speakerId, int lineCount, int wordCount, int charCount) {

        /**
         * Validates speaker identifier and non-negative counters.
         */
        public SpeakerStats {
            Objects.requireNonNull(speakerId, "speakerId must not be null");
            if (speakerId.isBlank()) {
                throw new IllegalArgumentException("speakerId must not be blank");
            }
            if (lineCount < 0 || wordCount < 0 || charCount < 0) {
                throw new IllegalArgumentException("SpeakerStats counts must be >= 0");
            }
        }
    }

    /**
     * Immutable script word-count report across an entire project.
     *
     * @param totalLines              total number of {@link VJavaAst.SayStatement} lines
     * @param totalWords              total word count across all dialogue lines
     * @param totalCharacters         total character count across all tag-stripped dialogue lines
     * @param estimatedReadingMinutes estimated reading duration in minutes ({@code totalWords / 200.0})
     * @param perSpeaker              unmodifiable sorted map of speaker ID to {@link SpeakerStats}
     */
    public record ScriptStatsReport(
            int totalLines,
            int totalWords,
            int totalCharacters,
            double estimatedReadingMinutes,
            Map<String, SpeakerStats> perSpeaker
    ) {

        /**
         * Validates report totals and wraps {@code perSpeaker} in an unmodifiable sorted map.
         */
        public ScriptStatsReport {
            if (totalLines < 0 || totalWords < 0 || totalCharacters < 0) {
                throw new IllegalArgumentException("Totals must be >= 0");
            }
            if (Double.isNaN(estimatedReadingMinutes) || estimatedReadingMinutes < 0.0) {
                throw new IllegalArgumentException("estimatedReadingMinutes must be >= 0.0");
            }
            TreeMap<String, SpeakerStats> sorted = new TreeMap<>();
            if (perSpeaker != null) {
                for (Map.Entry<String, SpeakerStats> entry : perSpeaker.entrySet()) {
                    String key = Objects.requireNonNull(entry.getKey(), "speaker key must not be null");
                    SpeakerStats stats = Objects.requireNonNull(entry.getValue(), "speaker stats must not be null");
                    sorted.put(key, stats);
                }
            }
            perSpeaker = Collections.unmodifiableMap(sorted);
        }

        /**
         * Looks up statistics for a specific speaker identifier.
         *
         * @param speakerId speaker identifier to look up (must not be {@code null})
         * @return matching {@link SpeakerStats}, if present
         */
        public Optional<SpeakerStats> findSpeaker(String speakerId) {
            Objects.requireNonNull(speakerId, "speakerId must not be null");
            return Optional.ofNullable(perSpeaker.get(speakerId.trim()));
        }
    }

    /**
     * Creates a new {@code ScriptWordCountAnalyzer}.
     */
    public ScriptWordCountAnalyzer() {
    }

    /**
     * Analyzes all {@link VJavaAst.SayStatement}s across {@code project}.
     *
     * @param project unified project AST (must not be {@code null})
     * @return immutable {@link ScriptStatsReport}
     */
    public ScriptStatsReport analyze(VJavaAst.ProjectAst project) {
        Objects.requireNonNull(project, "project must not be null");

        Map<String, MutableCounter> counters = new TreeMap<>();
        int totalLines = 0;
        int totalWords = 0;
        int totalChars = 0;

        for (VJavaAst.LabelNode label : project.labels().values()) {
            int[] totals = new int[3];
            collectStatements(label.statements(), counters, totals);
            totalLines += totals[0];
            totalWords += totals[1];
            totalChars += totals[2];
        }

        Map<String, SpeakerStats> perSpeaker = new TreeMap<>();
        for (Map.Entry<String, MutableCounter> entry : counters.entrySet()) {
            MutableCounter c = entry.getValue();
            perSpeaker.put(entry.getKey(), new SpeakerStats(entry.getKey(), c.lines, c.words, c.chars));
        }

        double readingMinutes = totalWords / WORDS_PER_MINUTE;
        return new ScriptStatsReport(totalLines, totalWords, totalChars, readingMinutes, perSpeaker);
    }

    /**
     * Strips KAGAYAKI inline {@code {...}} rich-text tags while preserving escaped {@code {{} / {@code }}}
     * braces as single literal brace characters.
     *
     * @param text raw dialogue string (must not be {@code null})
     * @return plain dialogue text with inline tags removed
     */
    public static String stripRichTextTags(String text) {
        Objects.requireNonNull(text, "text must not be null");
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int len = text.length();
        while (i < len) {
            char c = text.charAt(i);
            if (c == '{') {
                if (i + 1 < len && text.charAt(i + 1) == '{') {
                    out.append('{');
                    i += 2;
                    continue;
                }
                int close = text.indexOf('}', i + 1);
                if (close >= 0) {
                    i = close + 1;
                    continue;
                }
            } else if (c == '}' && i + 1 < len && text.charAt(i + 1) == '}') {
                out.append('}');
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static void collectStatements(
            List<VJavaAst.Statement> statements,
            Map<String, MutableCounter> counters,
            int[] totals
    ) {
        for (VJavaAst.Statement stmt : statements) {
            if (stmt instanceof VJavaAst.SayStatement say) {
                String speaker = normalizeSpeaker(say.speakerId());
                String plainText = stripRichTextTags(say.text() != null ? say.text() : "");
                int words = countWords(plainText);
                int chars = plainText.length();

                MutableCounter counter = counters.computeIfAbsent(speaker, ignored -> new MutableCounter());
                counter.lines++;
                counter.words += words;
                counter.chars += chars;

                totals[0]++;
                totals[1] += words;
                totals[2] += chars;
            } else if (stmt instanceof VJavaAst.IfStatement ifStmt) {
                for (VJavaAst.IfBranch branch : ifStmt.branches()) {
                    if (!branch.body().isEmpty()) {
                        collectStatements(branch.body(), counters, totals);
                    }
                }
            } else if (stmt instanceof VJavaAst.MenuStatement menu) {
                for (VJavaAst.MenuChoice choice : menu.choices()) {
                    if (!choice.body().isEmpty()) {
                        collectStatements(choice.body(), counters, totals);
                    }
                }
            }
        }
    }

    private static String normalizeSpeaker(String rawSpeaker) {
        if (rawSpeaker == null || rawSpeaker.isBlank()) {
            return NARRATOR_SPEAKER_ID;
        }
        return rawSpeaker.trim();
    }

    private static int countWords(String plainText) {
        String trimmed = plainText.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        return WHITESPACE_SPLIT.split(trimmed).length;
    }

    private static final class MutableCounter {
        int lines;
        int words;
        int chars;
    }
}
