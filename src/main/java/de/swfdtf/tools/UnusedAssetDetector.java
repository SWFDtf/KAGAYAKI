package de.swfdtf.tools;

import de.swfdtf.parser.VJavaAst;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Detects unused and missing project assets by comparing normalized {@code assets/...} references
 * extracted from a {@link VJavaAst.ProjectAst} against {@link VJavaAst.ProjectAst#discoveredAssets()}.
 */
public final class UnusedAssetDetector {

    private static final Set<String> IMAGE_ASSET_PROPERTIES = Set.of(
            "background", "ground", "idle", "hover", "selected_idle", "selected_hover",
            "insensitive", "image", "child"
    );

    /**
     * Immutable asset usage report with deterministically sorted sets.
     *
     * @param discoveredAssets unmodifiable sorted set of assets discovered under {@code assets/}
     * @param referencedAssets unmodifiable sorted set of assets referenced across scripts and screens
     * @param unusedAssets     unmodifiable sorted set of discovered assets never referenced by scripts/screens
     * @param missingAssets    unmodifiable sorted set of referenced assets absent from {@code discoveredAssets}
     */
    public record AssetUsageReport(
            Set<String> discoveredAssets,
            Set<String> referencedAssets,
            Set<String> unusedAssets,
            Set<String> missingAssets
    ) {

        /**
         * Enforces non-null elements and wraps every set as an unmodifiable sorted set.
         */
        public AssetUsageReport {
            discoveredAssets = copyUnmodifiableSortedSet(discoveredAssets, "discoveredAssets");
            referencedAssets = copyUnmodifiableSortedSet(referencedAssets, "referencedAssets");
            unusedAssets = copyUnmodifiableSortedSet(unusedAssets, "unusedAssets");
            missingAssets = copyUnmodifiableSortedSet(missingAssets, "missingAssets");
        }

        /**
         * Returns {@code true} when all referenced assets exist in {@code discoveredAssets}.
         *
         * @return {@code true} if {@code missingAssets} is empty
         */
        public boolean isClean() {
            return missingAssets.isEmpty();
        }
    }

    /**
     * Creates a new {@code UnusedAssetDetector}.
     */
    public UnusedAssetDetector() {
    }

    /**
     * Analyzes asset declarations and usages across {@code project}.
     *
     * <p>Collects asset paths from {@link VJavaAst.ImageDef}, {@link VJavaAst.LayeredImageDef}
     * ({@code always}, {@code group}, and {@code if} layers), {@link VJavaAst.SceneStatement},
     * {@link VJavaAst.ShowStatement}, {@link VJavaAst.PlayStatement}, {@link VJavaAst.QueueStatement},
     * {@link VJavaAst.VoiceStatement}, and {@link VJavaAst.ScreenDef} {@link VJavaAst.ScreenItem}
     * image/background properties.</p>
     *
     * @param project unified project AST (must not be {@code null})
     * @return immutable {@link AssetUsageReport}
     */
    public AssetUsageReport analyze(VJavaAst.ProjectAst project) {
        Objects.requireNonNull(project, "project must not be null");

        SortedSet<String> discovered = new TreeSet<>();
        for (String asset : project.discoveredAssets()) {
            if (asset != null && !asset.isBlank()) {
                discovered.add(normalizeAssetPath(asset));
            }
        }

        SortedSet<String> referenced = new TreeSet<>();
        collectImageDefAssets(project, referenced);
        collectLayeredImageAssets(project, referenced);
        collectScreenAssets(project, referenced);
        for (VJavaAst.LabelNode label : project.labels().values()) {
            collectStatementAssets(project, discovered, label.statements(), referenced);
        }

        SortedSet<String> unused = new TreeSet<>(discovered);
        unused.removeAll(referenced);

        SortedSet<String> missing = new TreeSet<>(referenced);
        missing.removeAll(discovered);

        return new AssetUsageReport(discovered, referenced, unused, missing);
    }

    private static void collectImageDefAssets(VJavaAst.ProjectAst project, Set<String> referenced) {
        for (VJavaAst.ImageDef img : project.images().values()) {
            addNormalizedAssetCandidate(img.assetPath(), referenced);
        }
    }

    private static void collectLayeredImageAssets(VJavaAst.ProjectAst project, Set<String> referenced) {
        for (VJavaAst.LayeredImageDef layered : project.layeredImages().values()) {
            for (VJavaAst.LayeredEntry entry : layered.entries()) {
                if (entry instanceof VJavaAst.AlwaysLayerEntry always) {
                    addLiteralAssetCandidate(always.assetOrExpr(), referenced);
                } else if (entry instanceof VJavaAst.GroupLayerEntry group) {
                    for (VJavaAst.AttributeEntry attr : group.attributes()) {
                        addLiteralAssetCandidate(attr.assetOrExpr(), referenced);
                    }
                } else if (entry instanceof VJavaAst.ConditionalLayerEntry cond) {
                    addLiteralAssetCandidate(cond.assetOrExpr(), referenced);
                }
            }
        }
    }

    private static void collectScreenAssets(VJavaAst.ProjectAst project, Set<String> referenced) {
        for (VJavaAst.ScreenDef screen : project.screens().values()) {
            collectScreenItemAssets(screen.items(), referenced);
        }
    }

    private static void collectScreenItemAssets(List<VJavaAst.ScreenItem> items, Set<String> referenced) {
        for (VJavaAst.ScreenItem item : items) {
            for (Map.Entry<String, String> prop : item.properties().entrySet()) {
                String key = prop.getKey().toLowerCase(Locale.ROOT);
                String val = stripQuotes(prop.getValue());
                if (IMAGE_ASSET_PROPERTIES.contains(key) && isLiteralAssetPath(val)) {
                    addNormalizedAssetCandidate(val, referenced);
                }
            }
            String keyword = item.keyword() != null ? item.keyword().toLowerCase(Locale.ROOT) : "";
            if (("add".equals(keyword) || "image".equals(keyword) || "imagebutton".equals(keyword))
                    && item.primaryArg() != null) {
                String arg = stripQuotes(item.primaryArg());
                if (isLiteralAssetPath(arg)) {
                    addNormalizedAssetCandidate(arg, referenced);
                }
            }
            if (!item.children().isEmpty()) {
                collectScreenItemAssets(item.children(), referenced);
            }
        }
    }

    private static void collectStatementAssets(
            VJavaAst.ProjectAst project,
            Set<String> discovered,
            List<VJavaAst.Statement> statements,
            Set<String> referenced
    ) {
        for (VJavaAst.Statement stmt : statements) {
            if (stmt instanceof VJavaAst.SceneStatement scene) {
                resolveVisualTagAsset(project, discovered, scene.bgTag(), List.of(), referenced);
            } else if (stmt instanceof VJavaAst.ShowStatement show) {
                resolveVisualTagAsset(project, discovered, show.tag(), show.attributes(), referenced);
            } else if (stmt instanceof VJavaAst.PlayStatement play) {
                addNormalizedAssetCandidate(play.assetPath(), referenced);
            } else if (stmt instanceof VJavaAst.QueueStatement queue) {
                for (String path : queue.assetPaths()) {
                    addNormalizedAssetCandidate(path, referenced);
                }
            } else if (stmt instanceof VJavaAst.VoiceStatement voice) {
                addNormalizedAssetCandidate(voice.assetPath(), referenced);
            } else if (stmt instanceof VJavaAst.IfStatement ifStmt) {
                for (VJavaAst.IfBranch branch : ifStmt.branches()) {
                    if (!branch.body().isEmpty()) {
                        collectStatementAssets(project, discovered, branch.body(), referenced);
                    }
                }
            } else if (stmt instanceof VJavaAst.MenuStatement menu) {
                for (VJavaAst.MenuChoice choice : menu.choices()) {
                    if (!choice.body().isEmpty()) {
                        collectStatementAssets(project, discovered, choice.body(), referenced);
                    }
                }
            }
        }
    }

    private static void resolveVisualTagAsset(
            VJavaAst.ProjectAst project,
            Set<String> discovered,
            String rawTag,
            List<String> attributes,
            Set<String> referenced
    ) {
        if (rawTag == null || rawTag.isBlank()) {
            return;
        }
        String cleanTag = stripQuotes(rawTag);
        if (project.layeredImages().containsKey(cleanTag)) {
            return;
        }
        if (!attributes.isEmpty()) {
            String fullKey = cleanTag + " " + String.join(" ", attributes);
            if (project.images().containsKey(fullKey)) {
                return;
            }
        }
        if (project.images().containsKey(cleanTag)) {
            return;
        }
        if (isDirectPathTag(cleanTag)) {
            addNormalizedAssetCandidate(cleanTag, referenced);
            return;
        }

        String candidateSuffix = attributes.isEmpty()
                ? cleanTag + ".png"
                : cleanTag + "_" + String.join("_", attributes) + ".png";
        List<String> candidates = List.of(
                "assets/images/" + candidateSuffix,
                "assets/images/bg/" + candidateSuffix,
                "assets/images/char/" + candidateSuffix,
                "assets/images/sprites/" + candidateSuffix
        );
        for (String candidate : candidates) {
            if (discovered.contains(candidate)) {
                referenced.add(candidate);
                return;
            }
        }
        referenced.add(candidates.get(0));
    }

    private static void addLiteralAssetCandidate(String rawValue, Set<String> referenced) {
        String stripped = stripQuotes(rawValue);
        if (isLiteralAssetPath(stripped)) {
            addNormalizedAssetCandidate(stripped, referenced);
        }
    }

    private static void addNormalizedAssetCandidate(String rawPath, Set<String> referenced) {
        if (rawPath == null || rawPath.isBlank()) {
            return;
        }
        String normalized = normalizeAssetPath(stripQuotes(rawPath));
        if (!normalized.isEmpty() && !normalized.contains("%s")) {
            referenced.add(normalized);
        }
    }

    private static boolean isDirectPathTag(String tag) {
        String lower = tag.toLowerCase(Locale.ROOT);
        return tag.contains("/")
                || lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".webp")
                || lower.endsWith(".bmp")
                || lower.endsWith(".tga");
    }

    private static boolean isLiteralAssetPath(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.contains("+") || value.contains("(") || value.contains("?") || value.contains("[")) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return value.startsWith("assets/")
                || value.contains("/")
                || lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".webp")
                || lower.endsWith(".bmp")
                || lower.endsWith(".tga")
                || lower.endsWith(".ogg")
                || lower.endsWith(".oga")
                || lower.endsWith(".wav")
                || lower.endsWith(".wave");
    }

    private static String normalizeAssetPath(String raw) {
        String s = raw.trim().replace('\\', '/');
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }

    private static String stripQuotes(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static Set<String> copyUnmodifiableSortedSet(Set<String> source, String fieldName) {
        SortedSet<String> sorted = new TreeSet<>();
        if (source != null) {
            for (String item : source) {
                sorted.add(Objects.requireNonNull(item, fieldName + " must not contain null entries"));
            }
        }
        return Collections.unmodifiableSortedSet(sorted);
    }
}
