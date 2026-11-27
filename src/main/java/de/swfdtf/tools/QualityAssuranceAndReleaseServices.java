package de.swfdtf.tools;

import de.swfdtf.core.EngineVersion;
import de.swfdtf.graphics.ObjectMotionController;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.state.SaveManager;
import de.swfdtf.tools.AutomatedPlaythroughRunner.ChoiceStrategy;
import de.swfdtf.tools.AutomatedPlaythroughRunner.PlaythroughReport;
import de.swfdtf.tools.ProjectTemplateGenerator.TemplateType;
import org.lwjgl.stb.STBIWriteCallback;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chapter 12 quality assurance, roadmap statistics, repository link validation, compatibility
 * notice formatting, live sprite inspection, studio collision warper, screenshot export formatters,
 * changelog validation, and consolidated multi-template playtest runner ({@code #0982}, {@code #0986},
 * {@code #0989}, {@code #1005}, {@code #1006}, {@code #1010}, {@code #1011}, {@code #1016},
 * {@code #1017}).
 */
public final class QualityAssuranceAndReleaseServices {

    private QualityAssuranceAndReleaseServices() {
    }

    /**
     * Parses {@code ZIELE.md} checklist counts by priority and formats Markdown progress tables for
     * {@code CHANGELOG_DEV.md} ({@code #0982}).
     */
    public static final class RoadmapProgressReporter {

        private static final Pattern GOAL_LINE =
                Pattern.compile("^- \\[([ xX])\\] \\*\\*#\\d+\\*\\* `\\[(P[0-3])\\]`");

        /**
         * Aggregated priority progress statistics ({@code #0982}).
         *
         * @param completedByPriority completed count keyed by priority ({@code P0..P3})
         * @param openByPriority      open count keyed by priority ({@code P0..P3})
         */
        public record RoadmapSummary(
                Map<String, Integer> completedByPriority,
                Map<String, Integer> openByPriority
        ) {
            /**
             * Defensively copies summary maps into immutable maps.
             */
            public RoadmapSummary {
                completedByPriority = Map.copyOf(Objects.requireNonNull(completedByPriority));
                openByPriority = Map.copyOf(Objects.requireNonNull(openByPriority));
            }

            /**
             * Formats summary counts as a Markdown table ({@code #0982}).
             *
             * @return Markdown progress table
             */
            public String formatMarkdownTable() {
                StringBuilder sb = new StringBuilder();
                sb.append("| Priority | Completed | Open | Total |\n");
                sb.append("| :--- | ---: | ---: | ---: |\n");
                for (String p : List.of("P0", "P1", "P2", "P3")) {
                    int done = completedByPriority.getOrDefault(p, 0);
                    int open = openByPriority.getOrDefault(p, 0);
                    sb.append("| ").append(p).append(" | ").append(done).append(" | ").append(open)
                            .append(" | ").append(done + open).append(" |\n");
                }
                return sb.toString();
            }
        }

        /**
         * Creates a {@code RoadmapProgressReporter}.
         */
        public RoadmapProgressReporter() {
        }

        /**
         * Summarizes completed vs. open checklist goals in {@code zielePath} ({@code #0982}).
         *
         * @param zielePath path to {@code ZIELE.md}
         * @return immutable {@link RoadmapSummary}
         * @throws IOException if file reading fails
         */
        public static RoadmapSummary summarizeZieleMarkdown(Path zielePath) throws IOException {
            Objects.requireNonNull(zielePath, "zielePath must not be null");
            Map<String, Integer> done = new LinkedHashMap<>();
            Map<String, Integer> open = new LinkedHashMap<>();
            for (String line : Files.readAllLines(zielePath, StandardCharsets.UTF_8)) {
                Matcher m = GOAL_LINE.matcher(line.trim());
                if (m.find()) {
                    boolean isDone = !" ".equals(m.group(1));
                    String priority = m.group(2);
                    if (isDone) {
                        done.merge(priority, 1, Integer::sum);
                    } else {
                        open.merge(priority, 1, Integer::sum);
                    }
                }
            }
            return new RoadmapSummary(done, open);
        }
    }

    /**
     * Validates local relative hyperlinks inside Markdown and HTML documentation files ({@code #0986}).
     */
    public static final class RepositoryLinkChecker {

        private static final Pattern MD_LINK = Pattern.compile("\\[[^\\]]*\\]\\(([^)\\s]+)\\)");
        private static final Pattern HTML_HREF = Pattern.compile("href\\s*=\\s*\"([^\"]+)\"");

        /**
         * Broken link entry reported by {@link RepositoryLinkChecker} ({@code #0986}).
         *
         * @param sourceFile document containing the link
         * @param target     unresolved relative link target
         */
        public record BrokenLink(String sourceFile, String target) {
        }

        /**
         * Creates a {@code RepositoryLinkChecker}.
         */
        public RepositoryLinkChecker() {
        }

        /**
         * Verifies all local relative links across {@code docFiles} exist on disk ({@code #0986}).
         *
         * @param repoRoot repository root path (must not be {@code null})
         * @param docFiles list of Markdown/HTML files to audit
         * @return immutable list of unresolved local relative links (empty when healthy)
         * @throws IOException if reading a doc file fails
         */
        public static List<BrokenLink> checkRelativeLinks(Path repoRoot, List<Path> docFiles) throws IOException {
            Objects.requireNonNull(repoRoot, "repoRoot must not be null");
            Objects.requireNonNull(docFiles, "docFiles must not be null");
            Path normRoot = repoRoot.toAbsolutePath().normalize();
            List<BrokenLink> broken = new ArrayList<>();
            for (Path doc : docFiles) {
                Path absDoc = doc.isAbsolute() ? doc.normalize() : normRoot.resolve(doc).normalize();
                if (!Files.isRegularFile(absDoc)) {
                    broken.add(new BrokenLink(doc.toString(), doc.toString()));
                    continue;
                }
                String content = Files.readString(absDoc, StandardCharsets.UTF_8);
                Pattern pat = absDoc.toString().toLowerCase(Locale.ROOT).endsWith(".html") ? HTML_HREF : MD_LINK;
                Matcher m = pat.matcher(content);
                while (m.find()) {
                    String rawTarget = m.group(1).trim();
                    if (isExternalOrAnchor(rawTarget)) {
                        continue;
                    }
                    String pathOnly = stripFragmentAndQuery(rawTarget);
                    if (pathOnly.isEmpty()) {
                        continue;
                    }
                    Path resolved = absDoc.getParent().resolve(pathOnly).normalize();
                    if (!Files.exists(resolved)) {
                        broken.add(new BrokenLink(normRoot.relativize(absDoc).toString(), rawTarget));
                    }
                }
            }
            return List.copyOf(broken);
        }

        private static boolean isExternalOrAnchor(String target) {
            String lower = target.toLowerCase(Locale.ROOT);
            return lower.startsWith("http://")
                    || lower.startsWith("https://")
                    || lower.startsWith("mailto:")
                    || lower.startsWith("file://")
                    || lower.startsWith("javascript:")
                    || lower.startsWith("#");
        }

        private static String stripFragmentAndQuery(String raw) {
            int hash = raw.indexOf('#');
            String trimmed = hash >= 0 ? raw.substring(0, hash) : raw;
            int q = trimmed.indexOf('?');
            return q >= 0 ? trimmed.substring(0, q) : trimmed;
        }
    }

    /**
     * Formats non-intrusive Studio Hub compatibility notices comparing a project's authored engine
     * version with the running Studio version ({@code #0989}).
     */
    public static final class ProjectVersionCompatibilityBadge {

        /**
         * Creates a {@code ProjectVersionCompatibilityBadge}.
         */
        public ProjectVersionCompatibilityBadge() {
        }

        /**
         * Formats compatibility comparison status label ({@code #0989}).
         *
         * @param projectVersion project authored engine version (nullable)
         * @param studioVersion  running Studio version (defaults to {@link EngineVersion#VERSION} if null)
         * @return readable compatibility string
         */
        public static String formatNotice(String projectVersion, String studioVersion) {
            String pv = (projectVersion == null || projectVersion.isBlank()) ? "1.0.0" : projectVersion.trim();
            String sv = (studioVersion == null || studioVersion.isBlank())
                    ? EngineVersion.VERSION
                    : studioVersion.trim();
            if (pv.equals(sv)) {
                return "Projekt erstellt mit v" + pv + " (aktuell mit Studio v" + sv + ")";
            }
            return "Projekt erstellt mit v" + pv + ", Studio v" + sv;
        }
    }

    /**
     * Real-time Studio sprite inspector snapshot table capturing every stage sprite's live
     * position, resolved hitbox, and active WASD control status ({@code #1005}).
     */
    public static final class LiveSpriteInspectorModel {

        /**
         * Immutable row describing one active stage sprite ({@code #1005}).
         *
         * @param tag        sprite semantic tag
         * @param x          logical X position
         * @param y          logical Y position
         * @param hitbox     resolved stage hitbox rectangle
         * @param wasdActive {@code true} if WASD movement is currently attached
         */
        public record SpriteRow(String tag, float x, float y, Rect2f hitbox, boolean wasdActive) {
        }

        /**
         * Creates a {@code LiveSpriteInspectorModel}.
         */
        public LiveSpriteInspectorModel() {
        }

        /**
         * Captures immutable live sprite rows from {@code controller} ({@code #1005}).
         *
         * @param controller stage controller from Window B (must not be {@code null})
         * @return immutable list of {@link SpriteRow}
         */
        public static List<SpriteRow> captureRows(SceneController controller) {
            Objects.requireNonNull(controller, "controller must not be null");
            ObjectMotionController motion = controller.getMotionController();
            Set<String> wasdTags = motion.getActiveWasdTags();
            Map<String, SpriteNode> sprites = controller.getActiveSprites();
            List<SpriteRow> rows = new ArrayList<>(sprites.size());
            for (Map.Entry<String, SpriteNode> entry : sprites.entrySet()) {
                String tag = entry.getKey();
                SpriteNode node = entry.getValue();
                Rect2f hitbox = motion.resolveDebugHitbox(tag, node);
                rows.add(new SpriteRow(
                        tag,
                        node.getTransform().getXpos(),
                        node.getTransform().getYpos(),
                        hitbox,
                        wasdTags.contains(tag)
                ));
            }
            return List.copyOf(rows);
        }
    }

    /**
     * Studio-only instant scenario warper moving the player sprite directly to a target object to
     * test {@code on_collide} outcomes rapidly ({@code #1006}).
     */
    public static final class ArenaScenarioCollisionWarper {

        /**
         * Creates an {@code ArenaScenarioCollisionWarper}.
         */
        public ArenaScenarioCollisionWarper() {
        }

        /**
         * Warps {@code playerTag} onto {@code targetTag}'s coordinates when running inside Studio mode
         * ({@code #1006}).
         *
         * @param controller target stage controller
         * @param playerTag  player sprite tag
         * @param targetTag  goal/hazard sprite tag
         * @param studioMode must be {@code true}; standalone runtime strictly blocks warping
         * @return {@code true} if both sprites exist in Studio mode and player was warped
         */
        public static boolean warpPlayerToTarget(
                SceneController controller,
                String playerTag,
                String targetTag,
                boolean studioMode
        ) {
            Objects.requireNonNull(controller, "controller must not be null");
            if (!studioMode) {
                return false;
            }
            Map<String, SpriteNode> sprites = controller.getActiveSprites();
            SpriteNode player = sprites.get(playerTag);
            SpriteNode target = sprites.get(targetTag);
            if (player == null || target == null) {
                return false;
            }
            player.getTransform()
                    .setXpos(target.getTransform().getXpos())
                    .setYpos(target.getTransform().getYpos());
            return true;
        }
    }

    /**
     * Captures ready Studio layout state automatically saved right after creating a new project
     * ({@code #1010}).
     */
    public static final class StudioNewProjectAutoLayoutState {

        /**
         * Post-creation Studio readiness summary ({@code #1010}).
         *
         * @param projectDir       created project root
         * @param lintClean        {@code true} when initial scan/lint has 0 errors
         * @param explorerExpanded whether left explorer panel opened on project files
         * @param activeTab        selected Studio tab identifier
         */
        public record ReadyLayout(Path projectDir, boolean lintClean, boolean explorerExpanded, String activeTab) {
        }

        /**
         * Creates a {@code StudioNewProjectAutoLayoutState}.
         */
        public StudioNewProjectAutoLayoutState() {
        }

        /**
         * Scans and validates {@code projectDir} immediately after project creation so Studio stays
         * immediately playable in Window B without manual restart ({@code #1010}).
         *
         * @param projectDir newly generated project root
         * @return {@link ReadyLayout} status
         */
        public static ReadyLayout prepareNewProjectLayout(Path projectDir) {
            Objects.requireNonNull(projectDir, "projectDir must not be null");
            VJavaAst.ProjectAst ast = ProjectScanner.scan(projectDir);
            VJavaLinter.LintReport lint = VJavaLinter.lint(ast, false);
            return new ReadyLayout(projectDir.toAbsolutePath().normalize(), !lint.hasErrors(), true, "EXPLORER");
        }
    }

    /**
     * Exports Window B framebuffers cleanly as either lossless PNG or compact JPEG web preview
     * without {@code java.awt} ({@code #1011}).
     */
    public static final class ScreenshotFormatExporter {

        private static final Object STB_JPEG_LOCK = new Object();

        /**
         * Creates a {@code ScreenshotFormatExporter}.
         */
        public ScreenshotFormatExporter() {
        }

        /**
         * Encodes RGBA8 pixels losslessly as PNG via STB ({@code #1011}).
         *
         * @param width  image width ({@code > 0})
         * @param height image height ({@code > 0})
         * @param rgba   RGBA8 pixel array
         * @return PNG bytes
         */
        public static byte[] exportLosslessPng(int width, int height, byte[] rgba) {
            return SaveManager.encodeRgbaToPng(width, height, rgba, false);
        }

        /**
         * Encodes RGBA8 pixels compactly as JPEG via LWJGL {@link STBImageWrite} ({@code #1011}).
         *
         * @param width   image width ({@code > 0})
         * @param height  image height ({@code > 0})
         * @param rgba    RGBA8 pixel array
         * @param quality JPEG quality in {@code 1..100}
         * @return compact JPEG bytes
         */
        public static byte[] exportCompactJpegPreview(int width, int height, byte[] rgba, int quality) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Dimensions must be positive: " + width + "x" + height);
            }
            Objects.requireNonNull(rgba, "rgba must not be null");
            int expected = Math.multiplyExact(Math.multiplyExact(width, height), 4);
            if (rgba.length != expected) {
                throw new IllegalArgumentException("Expected " + expected + " RGBA bytes, got " + rgba.length);
            }
            int clampedQuality = Math.clamp(quality, 1, 100);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(512, expected / 8));
            ByteBuffer pixels = MemoryUtil.memAlloc(expected);
            STBIWriteCallback callback = STBIWriteCallback.create((ctx, data, size) -> {
                ByteBuffer chunk = STBIWriteCallback.getData(data, size);
                byte[] bytes = new byte[size];
                chunk.get(bytes);
                out.write(bytes, 0, size);
            });
            try {
                pixels.put(rgba).flip();
                boolean ok;
                synchronized (STB_JPEG_LOCK) {
                    ok = STBImageWrite.stbi_write_jpg_to_func(
                            callback, 0L, width, height, 4, pixels, clampedQuality) != 0;
                }
                if (!ok) {
                    throw new IllegalStateException("STBImageWrite.stbi_write_jpg_to_func failed");
                }
                return out.toByteArray();
            } finally {
                callback.free();
                MemoryUtil.memFree(pixels);
            }
        }
    }

    /**
     * Validates that {@code docs/CHANGELOG_DEV.md} contains properly formatted versioned entries
     * before cutting releases ({@code #1016}).
     */
    public static final class ChangelogReleaseValidator {

        private static final Pattern VERSION_HEADING = Pattern.compile(
                "^##\\s+.*(?:v?\\d+\\.\\d+|\\d{4}-\\d{2}-\\d{2}|Milestone|Meilenstein|Welle|Ziele)",
                Pattern.CASE_INSENSITIVE);

        /**
         * Creates a {@code ChangelogReleaseValidator}.
         */
        public ChangelogReleaseValidator() {
        }

        /**
         * Checks that {@code changelogPath} exists and defines at least one structured version heading
         * ({@code #1016}).
         *
         * @param changelogPath path to {@code docs/CHANGELOG_DEV.md}
         * @return {@code true} if valid structured changelog entries are present
         * @throws IOException if file reading fails
         */
        public static boolean validate(Path changelogPath) throws IOException {
            Objects.requireNonNull(changelogPath, "changelogPath must not be null");
            if (!Files.isRegularFile(changelogPath)) {
                return false;
            }
            for (String line : Files.readAllLines(changelogPath, StandardCharsets.UTF_8)) {
                if (VERSION_HEADING.matcher(line.trim()).find()) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Runs unified automated verification across {@code FULL_DEMO}, {@code MINIMAL}, and the Snow
     * White demo directory, summarizing results cleanly ({@code #1017}).
     */
    public static final class PlaytestSuiteAggregator {

        /**
         * Aggregated single-template playtest verification record ({@code #1017}).
         *
         * @param name        project/template label
         * @param lintErrors  fatal lint error count
         * @param lintWarns   lint warning count
         * @param endedClean  {@code true} when automated playthrough completed cleanly
         * @param steps       executed steps count
         */
        public record ProjectPlaytestResult(
                String name,
                int lintErrors,
                int lintWarns,
                boolean endedClean,
                int steps
        ) {
            /**
             * Returns whether the reference project passed lint cleanly and ended cleanly.
             *
             * @return {@code true} when 0 errors and clean completion
             */
            public boolean passed() {
                return lintErrors == 0 && lintWarns == 0 && endedClean;
            }
        }

        /**
         * Creates a {@code PlaytestSuiteAggregator}.
         */
        public PlaytestSuiteAggregator() {
        }

        /**
         * Verifies {@code FULL_DEMO}, {@code MINIMAL}, and optionally {@code demoProjectDir} in one
         * consolidated playtest suite ({@code #1017}).
         *
         * @param tempDir        temporary workspace root for generated templates
         * @param demoProjectDir optional Snow White demo directory (nullable)
         * @return immutable list of {@link ProjectPlaytestResult}
         * @throws IOException if template generation fails
         */
        public static List<ProjectPlaytestResult> runReferencePlaytests(Path tempDir, Path demoProjectDir)
                throws IOException {
            Objects.requireNonNull(tempDir, "tempDir must not be null");
            List<ProjectPlaytestResult> results = new ArrayList<>();
            Path fullDir = ProjectTemplateGenerator.generate(
                    tempDir.resolve("full_demo"), "Full Demo QA", "QA", "2D", TemplateType.FULL_DEMO);
            results.add(verifyProject("FULL_DEMO", fullDir));

            Path minDir = ProjectTemplateGenerator.generate(
                    tempDir.resolve("minimal_demo"), "Minimal QA", "QA", "2D", TemplateType.MINIMAL);
            results.add(verifyProject("MINIMAL", minDir));

            if (demoProjectDir != null && Files.isDirectory(demoProjectDir)) {
                results.add(verifyProject("Schneewittchen", demoProjectDir));
            }
            return List.copyOf(results);
        }

        private static ProjectPlaytestResult verifyProject(String name, Path dir) {
            VJavaAst.ProjectAst ast = ProjectScanner.scan(dir);
            VJavaLinter.LintReport lint = VJavaLinter.lint(ast, false);
            PlaythroughReport run = new AutomatedPlaythroughRunner()
                    .run(ast, ChoiceStrategy.FIRST_CHOICE, 1000);
            return new ProjectPlaytestResult(
                    name,
                    lint.errorCount(),
                    lint.warningCount(),
                    run.endedCleanly(),
                    run.stepsExecuted()
            );
        }
    }
}
