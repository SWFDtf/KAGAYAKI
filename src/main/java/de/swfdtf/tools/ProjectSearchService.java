package de.swfdtf.tools;

import de.swfdtf.parser.ProjectScanner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic full-text and regex search service across KAGAYAKI project files.
 *
 * <p>Skips build, VCS, and runtime directories ({@code .git}, {@code build}, {@code .gradle},
 * {@code saves}, {@code out}, {@code dist}) as well as binary files, returning results sorted
 * deterministically by project-relative path, 1-based line number, and 1-based column.</p>
 */
public final class ProjectSearchService {

    /**
     * Directory names excluded from recursive project searches.
     */
    public static final Set<String> SKIPPED_DIRECTORIES = ProjectScanner.SKIPPED_DIRECTORIES;

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".webp", ".bmp", ".tga", ".gif", ".ico",
            ".wav", ".ogg", ".oga", ".mp3", ".flac",
            ".ttf", ".otf", ".woff", ".woff2",
            ".vpak", ".jar", ".zip", ".class", ".dat", ".bin", ".exe", ".dll", ".so", ".dylib"
    );

    /**
     * Single text search match within a project file.
     *
     * @param file       normalized absolute path to the matched file
     * @param lineNumber 1-based line number within {@code file}
     * @param column     1-based column offset where the match begins
     * @param lineText   full line content without line terminators
     */
    public record SearchMatch(Path file, int lineNumber, int column, String lineText) {

        /**
         * Validates match coordinates and normalizes {@code lineText}.
         */
        public SearchMatch {
            Objects.requireNonNull(file, "file must not be null");
            if (lineNumber < 1) {
                throw new IllegalArgumentException("lineNumber must be >= 1, got " + lineNumber);
            }
            if (column < 1) {
                throw new IllegalArgumentException("column must be >= 1, got " + column);
            }
            lineText = lineText != null ? lineText : "";
        }
    }

    /**
     * Creates a new {@code ProjectSearchService}.
     */
    public ProjectSearchService() {
    }

    /**
     * Searches all text files under {@code projectRoot} for plain-text occurrences of {@code query}.
     *
     * @param projectRoot   root directory of the project (must not be {@code null})
     * @param query         non-empty literal search string (must not be {@code null})
     * @param caseSensitive {@code true} for case-sensitive matching, {@code false} for case-insensitive matching
     * @return unmodifiable list of {@link SearchMatch} sorted by file, line number, and column
     * @throws IllegalArgumentException if {@code query} is empty or {@code projectRoot} is not a directory
     * @throws UncheckedIOException     if filesystem traversal fails
     */
    public List<SearchMatch> searchPlainText(Path projectRoot, String query, boolean caseSensitive) {
        Objects.requireNonNull(projectRoot, "projectRoot must not be null");
        Objects.requireNonNull(query, "query must not be null");
        if (query.isEmpty()) {
            throw new IllegalArgumentException("query must not be empty");
        }

        Path normalizedRoot = validateProjectRoot(projectRoot);
        List<Path> candidateFiles = discoverTextCandidateFiles(normalizedRoot);
        List<SearchMatch> matches = new ArrayList<>();

        for (Path file : candidateFiles) {
            List<String> lines = readUtf8LinesIfText(file);
            if (lines == null) {
                continue;
            }
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int searchFrom = 0;
                while (searchFrom <= line.length() - query.length()) {
                    int idx = findPlainOccurrence(line, query, searchFrom, caseSensitive);
                    if (idx < 0) {
                        break;
                    }
                    matches.add(new SearchMatch(file, i + 1, idx + 1, line));
                    searchFrom = idx + query.length();
                }
            }
        }

        sortMatches(normalizedRoot, matches);
        return List.copyOf(matches);
    }

    /**
     * Searches all text files under {@code projectRoot} using the compiled regex {@code pattern}.
     *
     * @param projectRoot root directory of the project (must not be {@code null})
     * @param pattern     compiled regular expression pattern (must not be {@code null} or empty)
     * @return unmodifiable list of {@link SearchMatch} sorted by file, line number, and column
     * @throws IllegalArgumentException if {@code pattern} has an empty expression or {@code projectRoot} is invalid
     * @throws UncheckedIOException     if filesystem traversal fails
     */
    public List<SearchMatch> searchRegex(Path projectRoot, Pattern pattern) {
        Objects.requireNonNull(projectRoot, "projectRoot must not be null");
        Objects.requireNonNull(pattern, "pattern must not be null");
        if (pattern.pattern().isEmpty()) {
            throw new IllegalArgumentException("pattern must not be empty");
        }

        Path normalizedRoot = validateProjectRoot(projectRoot);
        List<Path> candidateFiles = discoverTextCandidateFiles(normalizedRoot);
        List<SearchMatch> matches = new ArrayList<>();

        for (Path file : candidateFiles) {
            List<String> lines = readUtf8LinesIfText(file);
            if (lines == null) {
                continue;
            }
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                Matcher matcher = pattern.matcher(line);
                while (matcher.find()) {
                    matches.add(new SearchMatch(file, i + 1, matcher.start() + 1, line));
                }
            }
        }

        sortMatches(normalizedRoot, matches);
        return List.copyOf(matches);
    }

    private static Path validateProjectRoot(Path projectRoot) {
        Path normalized = projectRoot.toAbsolutePath().normalize();
        if (!Files.exists(normalized) || !Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("Project root does not exist or is not a directory: " + normalized);
        }
        return normalized;
    }

    private static List<Path> discoverTextCandidateFiles(Path normalizedRoot) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(normalizedRoot)) {
                        Path dirName = dir.getFileName();
                        if (dirName != null && SKIPPED_DIRECTORIES.contains(dirName.toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && !hasBinaryExtension(file)) {
                        files.add(file.toAbsolutePath().normalize());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk project directory: " + normalizedRoot, e);
        }
        files.sort(Comparator.comparing(p -> toRelativeSortKey(normalizedRoot, p)));
        return files;
    }

    private static boolean hasBinaryExtension(Path file) {
        Path fileName = file.getFileName();
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toString().toLowerCase(Locale.ROOT);
        int dotIdx = lower.lastIndexOf('.');
        if (dotIdx < 0) {
            return false;
        }
        return BINARY_EXTENSIONS.contains(lower.substring(dotIdx));
    }

    private static List<String> readUtf8LinesIfText(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read file during project search: " + file, e);
        }
        for (byte b : bytes) {
            if (b == 0) {
                return null;
            }
        }
        CharBuffer decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException ignored) {
            return null;
        }
        return splitLines(decoded.toString());
    }

    private static List<String> splitLines(String content) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int len = content.length();
        for (int i = 0; i < len; i++) {
            char c = content.charAt(i);
            if (c == '\r' || c == '\n') {
                lines.add(content.substring(start, i));
                if (c == '\r' && i + 1 < len && content.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < len) {
            lines.add(content.substring(start));
        }
        return lines;
    }

    private static int findPlainOccurrence(String line, String query, int fromIndex, boolean caseSensitive) {
        if (caseSensitive) {
            return line.indexOf(query, fromIndex);
        }
        int limit = line.length() - query.length();
        for (int i = fromIndex; i <= limit; i++) {
            if (line.regionMatches(true, i, query, 0, query.length())) {
                return i;
            }
        }
        return -1;
    }

    private static void sortMatches(Path normalizedRoot, List<SearchMatch> matches) {
        matches.sort(
                Comparator.comparing((SearchMatch m) -> toRelativeSortKey(normalizedRoot, m.file()))
                        .thenComparingInt(SearchMatch::lineNumber)
                        .thenComparingInt(SearchMatch::column)
        );
    }

    private static String toRelativeSortKey(Path root, Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
