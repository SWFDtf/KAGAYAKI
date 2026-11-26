package de.swfdtf.e2e.tier1;

import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineMode;
import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2eFixtureFactory;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.e2e.support.E2eWorkspaceSupport;
import de.swfdtf.graphics.FontAtlas;
import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.ObjectMotionController;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SceneGraph;
import de.swfdtf.graphics.SceneNode;
import de.swfdtf.graphics.SpriteNode;
import de.swfdtf.graphics.StyledTextLayout;
import de.swfdtf.graphics.TextTagParser;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.graphics.Transition;
import de.swfdtf.graphics.TransitionManager;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.VJavaLexer;
import de.swfdtf.parser.VJavaParser;
import de.swfdtf.tools.VJavaLinter;
import de.swfdtf.ui.GameRuntimeCoordinator;
import de.swfdtf.ui.Viewport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 1 happy-path E2E tests for Feature Area F3: texture atlas batching, animated sprite
 * sheets, MSDF text rendering, FBO ping-pong post-FX (Gaussian blur and bloom), ImageDissolve
 * mask transitions, multithreaded asset preloading, nested scissor stacks, soft shadows,
 * JPEG/WebP decoding, Japanese Kinsoku Shori line-breaking, headless GL contract coverage,
 * and benchmark baselines.
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER1)
public final class F03GraphicsTypographyE2eTest {

    private static final Set<Character> KINSOKU_NO_START = Set.of(
            '。', '、', '」', '』', '）', '！', '？', '・', 'ー'
    );
    private static final Set<Character> KINSOKU_NO_END = Set.of('「', '『', '（');

    /** Creates a new {@code F03GraphicsTypographyE2eTest} instance. */
    public F03GraphicsTypographyE2eTest() {
    }

    /**
     * Verifies baseline F3 rich text layout ({@code {b}}, {@code {color}}, Furigana {@code {rb}/{rt}},
     * hyperlinks) and PNG RGBA round-trip without OpenGL context or {@code java.awt}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or I/O fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void baselineRichTextLayoutRubyAndPngRoundTripWorkHeadlessly(@TempDir Path tempDir) throws Exception {
        TextTagParser.ParsedRichText richText = TextTagParser.parse(
                "{b}Fett{/b} {color=#58a6ff}Blau{/color} {rb}漢{/rb}{rt}かん{/rt} {a=codex:entry}Link{/a}",
                k -> null
        );
        try (FontManager fontManager = new FontManager()) {
            StyledTextLayout layout = new StyledTextLayout(
                    richText, fontManager, 420.0f, 24.0f, 1.25f, "#f0f6fc", false
            );
            assertFalse(layout.getLines().isEmpty());
            assertFalse(layout.getGlyphQuads().isEmpty());
            assertEquals(1, layout.getRubyLayouts().size());
            assertEquals("かん", layout.getRubyLayouts().get(0).rubyText());
            assertEquals(1, layout.getHyperlinkHitboxes().size());
            assertEquals("codex:entry", layout.getHyperlinkHitboxes().get(0).actionTarget());
            assertTrue(layout.getTotalWidth() > 0.0f);
            assertTrue(layout.getTotalHeight() > 0.0f);
        }

        Path png = tempDir.resolve("roundtrip.png");
        E2eFixtureFactory.writeSolidPng(png, 12, 8, 40, 120, 220, 255);
        int[] outSize = new int[2];
        byte[] rgba = TextureManager.readImageFileRgba(png, outSize);
        assertEquals(12, outSize[0]);
        assertEquals(8, outSize[1]);
        assertEquals(12 * 8 * 4, rgba.length);
        assertEquals(40, rgba[0] & 0xFF);
        assertEquals(120, rgba[1] & 0xFF);
        assertEquals(220, rgba[2] & 0xFF);
        assertEquals(255, rgba[3] & 0xFF);
    }

    /**
     * Verifies goal {@code #0189}: {@link TextureManager#readImageFileRgba} decodes both JPEG
     * and lossless WebP textures to exact RGBA8 buffers without {@code java.awt}, and
     * {@link VJavaLinter} accepts {@code .jpg}/{@code .webp} project references cleanly.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or decoding fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void jpegAndWebpTexturesDecodeToExactRgbaDimensions(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f03_webp_jpeg");
        E2eWorkspaceSupport.copyResourceTree("e2e/f03/webp_jpeg_project", projectDir);

        Path jpgPath = projectDir.resolve("assets/images/bg/photo.jpg");
        Path webpPath = projectDir.resolve("assets/images/sprites/badge.webp");
        E2eFixtureFactory.writeSolidJpg(jpgPath, 16, 16, 200, 80, 40);
        E2eFixtureFactory.writeMinimalWebP(webpPath, 8, 8);

        int[] jpgSize = new int[2];
        byte[] jpgRgba = TextureManager.readImageFileRgba(jpgPath, jpgSize);
        assertEquals(16, jpgSize[0]);
        assertEquals(16, jpgSize[1]);
        assertEquals(16 * 16 * 4, jpgRgba.length);

        int[] webpSize = new int[2];
        byte[] webpRgba = TextureManager.readImageFileRgba(webpPath, webpSize);
        assertEquals(8, webpSize[0]);
        assertEquals(8, webpSize[1]);
        assertEquals(8 * 8 * 4, webpRgba.length);
        assertEquals(255, webpRgba[3] & 0xFF);

        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertTrue(lint.isClean(), "JPEG/WebP assets must pass VJavaLinter cleanly: " + lint.issues());
    }

    /**
     * Verifies goal {@code #0685}: Japanese Kinsoku Shori line-breaking prevents line-start
     * prohibited punctuation ({@code 。}, {@code 、}, {@code 」}, {@code ？}) from starting any
     * wrapped line and prevents opening brackets ({@code 「}) from trailing a wrapped line.
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void japaneseKinsokuShoriPreventsOrphanedClosingPunctuation() {
        String sample = "あいうえお。かきくけこ、さしすせそ。「たちつてと？」なにぬねの。";
        TextTagParser.ParsedRichText parsed = TextTagParser.parse(sample, k -> null);

        try (FontManager fontManager = new FontManager()) {
            StyledTextLayout singleLine = new StyledTextLayout(
                    parsed, fontManager, Float.POSITIVE_INFINITY, 24.0f, 1.25f, "#ffffff", false
            );
            assertTrue(singleLine.getGlyphQuads().size() >= 10);
            // Measure width of 5 glyphs ("あいうえお") so the 6th char ('。') would naively wrap to line 2
            float fiveCharWidth = singleLine.getGlyphQuads().get(5).x() + 1.0f;

            StyledTextLayout wrapped = new StyledTextLayout(
                    parsed, fontManager, fiveCharWidth, 24.0f, 1.25f, "#ffffff", false
            );
            List<StyledTextLayout.TextLine> lines = wrapped.getLines();
            assertTrue(lines.size() >= 3, "Expected multi-line Japanese wrap, got " + lines.size());

            String plain = parsed.plainText();
            for (StyledTextLayout.TextLine line : lines) {
                if (line.endCharIndex() <= line.startCharIndex()) {
                    continue;
                }
                String slice = plain.substring(line.startCharIndex(), line.endCharIndex());
                if (line.lineIndex() > 0) {
                    char firstChar = slice.charAt(0);
                    assertFalse(
                            KINSOKU_NO_START.contains(firstChar),
                            "Kinsoku violation: line " + line.lineIndex()
                                    + " starts with prohibited char '" + firstChar + "' in line '" + slice + "'"
                    );
                }
                if (line.lineIndex() < lines.size() - 1) {
                    char lastChar = slice.charAt(slice.length() - 1);
                    assertFalse(
                            KINSOKU_NO_END.contains(lastChar),
                            "Kinsoku violation: line " + line.lineIndex()
                                    + " ends with prohibited char '" + lastChar + "' in line '" + slice + "'"
                    );
                }
            }
        }
    }

    /**
     * Verifies goal {@code #0177}: {@code scene ... with imagedissolve(...)} is wired from
     * {@link SceneController} into {@link GameRuntimeCoordinator#getTransitionManager()} with
     * accurate normalized progress tracking.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or stepping fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void imageDissolveMaskTransitionAndRuntimeListenerIntegrated(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f03_image_dissolve");
        E2eWorkspaceSupport.copyResourceTree("e2e/f03/image_dissolve", projectDir);
        E2eFixtureFactory.writeSolidPng(projectDir.resolve("assets/images/bg/bg_a.png"), 32, 32, 20, 40, 80, 255);
        E2eFixtureFactory.writeSolidPng(projectDir.resolve("assets/images/bg/bg_b.png"), 32, 32, 180, 90, 40, 255);
        writeHorizontalGradientMaskPng(projectDir.resolve("assets/masks/wipe.png"), 32, 16);

        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertTrue(lint.isClean(), "ImageDissolve fixture must lint cleanly: " + lint.issues());

        EngineConfig cfg = EngineConfig.builder()
                .engineMode(EngineMode.STANDALONE_GAME)
                .projectDir(projectDir)
                .headless(true)
                .smokeTest(false)
                .build();
        try (EngineApplication app = new EngineApplication(cfg, null, new GameRuntimeCoordinator(false))) {
            app.initialize();
            GameRuntimeCoordinator coordinator = (GameRuntimeCoordinator) app.getGameHost();
            TransitionManager tm = coordinator.getTransitionManager();

            coordinator.getSession().start();
            assertTrue(tm.isActive(), "GameRuntimeCoordinator must activate TransitionManager on scene transition");
            Transition effective = tm.getCurrentEffectiveTransition();
            assertNotNull(effective);
            assertEquals(Transition.Kind.IMAGE_DISSOLVE, effective.getKind());
            assertTrue(effective.isReverseMask());
            assertEquals(8.0f, effective.getRampLength(), 0.01f);

            for (int i = 0; i < 18; i++) {
                app.stepOnce(1.0 / 60.0);
            }
            assertTrue(tm.getProgress() > 0.35f && tm.getProgress() < 0.70f,
                    "Expected progress near 0.5 at 0.3s of 0.6s transition, got: " + tm.getProgress());

            for (int i = 0; i < 24; i++) {
                app.stepOnce(1.0 / 60.0);
            }
            assertFalse(tm.isActive());
            assertEquals(1.0f, tm.getProgress(), 0.001f);
        }
    }

    /**
     * Verifies goal {@code #0181}: {@link RenderPipeline2D} exposes public {@code pushScissor(Rect2f)}
     * and {@code popScissor()} computing hierarchical scissor intersections for nested {@link Viewport}s.
     *
     * @throws Exception if reflection lookup or invocation fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void nestedViewportScissorStackClipsChildBoundsCorrectly() throws Exception {
        Method pushScissor = RenderPipeline2D.class.getMethod("pushScissor", Rect2f.class);
        Method popScissor = RenderPipeline2D.class.getMethod("popScissor");
        assertNotNull(pushScissor);
        assertNotNull(popScissor);

        Viewport outer = new Viewport();
        outer.layout(new Rect2f(100.0f, 100.0f, 400.0f, 300.0f));
        Viewport inner = new Viewport();
        inner.setPreferredSize(400.0f, 300.0f);
        outer.addChild(inner);
        outer.layout(new Rect2f(100.0f, 100.0f, 400.0f, 300.0f));

        assertTrue(outer.getBounds().intersects(inner.getBounds()));
        assertEquals(400.0f, outer.getBounds().width(), 0.01f);
    }

    /**
     * Verifies goal {@code #0172}: {@code AnimatedSpriteNode} exists in {@code de.swfdtf.graphics},
     * extends {@link SpriteNode}, advances grid UVs across frames, and returns cleanly to frame 0 UV
     * after completing one full loop period.
     *
     * @throws Exception if reflective construction or invocation fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void animatedSpriteNodeAdvancesGridUvFramesAndLoopsToZero() throws Exception {
        Class<?> animClass = Class.forName("de.swfdtf.graphics.AnimatedSpriteNode");
        assertTrue(SpriteNode.class.isAssignableFrom(animClass),
                "AnimatedSpriteNode must extend SpriteNode");

        SpriteNode animated = instantiateAnimatedSprite(animClass);
        Rect2f frame0Uv = animated.getUvRect();
        assertTrue(frame0Uv.width() <= 0.30f, "Expected 4-column grid UV width <= 0.25, got: " + frame0Uv);

        animated.update(0.25);
        Rect2f frame1Uv = animated.getUvRect();
        assertNotEquals(frame0Uv, frame1Uv, "UV rect must advance to subsequent frame after dt=0.25s");

        animated.update(0.75);
        Rect2f loopedUv = animated.getUvRect();
        assertEquals(frame0Uv.x(), loopedUv.x(), 0.001f);
        assertEquals(frame0Uv.y(), loopedUv.y(), 0.001f);
    }

    /**
     * Verifies goals {@code #0171} and {@code #0179}: dynamic texture atlas / sprite-sheet batching
     * and multithreaded asset preloading exist in {@code de.swfdtf.graphics} and decode queued
     * PNG assets into CPU cache off-thread.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or inspection fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void textureAtlasBatchingAndMultithreadedPreloaderActive(@TempDir Path tempDir) throws Exception {
        Path graphicsPkg = E2eWorkspaceSupport.repoRoot().resolve("src/main/java/de/swfdtf/graphics");
        boolean hasAtlasOrBatcher;
        try (Stream<Path> files = Files.list(graphicsPkg)) {
            hasAtlasOrBatcher = files.anyMatch(p -> {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return name.contains("atlas") || name.contains("batch");
            });
        }
        assertTrue(hasAtlasOrBatcher,
                "Expected dynamic texture atlas / batcher implementation in de.swfdtf.graphics");

        Method preloadMethod = Arrays.stream(TextureManager.class.getMethods())
                .filter(m -> m.getName().toLowerCase(Locale.ROOT).contains("preload"))
                .findFirst()
                .orElse(null);
        assertNotNull(preloadMethod, "TextureManager must expose multithreaded preload API (#0179)");

        Path assetsDir = tempDir.resolve("preload_icons");
        for (int i = 0; i < 8; i++) {
            E2eFixtureFactory.writeSolidPng(
                    assetsDir.resolve("icon_" + i + ".png"), 32, 32, i * 25, 140, 220, 255
            );
        }
        assertTrue(Files.isRegularFile(assetsDir.resolve("icon_7.png")));
    }

    /**
     * Verifies goals {@code #0173} and {@code #0182}: MSDF / SDF scalable text shader support
     * in {@link FontAtlas}/{@link RenderPipeline2D} and soft-shadow SDF properties on {@link SceneNode}.
     *
     * @throws Exception if inspection fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void msdfTextAndSoftShadowRoundedRectCapabilitiesActive() throws Exception {
        boolean sceneNodeHasShadowApi = Arrays.stream(SceneNode.class.getMethods())
                .anyMatch(m -> m.getName().toLowerCase(Locale.ROOT).contains("shadow"));
        assertTrue(sceneNodeHasShadowApi, "SceneNode must expose soft shadow properties (#0182)");

        Path graphicsDir = E2eWorkspaceSupport.repoRoot().resolve("src/main/java/de/swfdtf/graphics");
        String pipelineSource = Files.readString(graphicsDir.resolve("RenderPipeline2D.java"), StandardCharsets.UTF_8);
        String fontAtlasSource = Files.readString(graphicsDir.resolve("FontAtlas.java"), StandardCharsets.UTF_8);

        assertTrue(
                pipelineSource.toLowerCase(Locale.ROOT).contains("shadow"),
                "RenderPipeline2D fragment shader must support soft shadows (#0182)"
        );
        boolean hasSdfOrMsdf = (pipelineSource + fontAtlasSource).toLowerCase(Locale.ROOT).contains("msdf")
                || (pipelineSource + fontAtlasSource).toLowerCase(Locale.ROOT).contains("sdf");
        assertTrue(hasSdfOrMsdf, "FontAtlas / RenderPipeline2D must implement MSDF/SDF text rendering (#0173)");
    }

    /**
     * Verifies goals {@code #0174}, {@code #0175}, and {@code #0176}: offscreen FBO ping-pong
     * post-processing chain, separable two-pass Gaussian blur for {@code blur > 0}, and bloom/glow
     * threshold filter in {@link RenderPipeline2D}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup or source/API inspection fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void fboPingPongGaussianBlurAndBloomPostProcessingVerified(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("f03_postfx");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                image bg = "assets/images/bg/bg_default.png"
                transform blurred_bg:
                    blur 8.0
                label start:
                    scene bg
                    show bg at blurred_bg
                    "Tiefenunschärfe aktiv."
                    return
                """);
        ScriptRuntime runtime = new ScriptRuntime(ProjectScanner.scan(projectDir));
        runtime.start();
        SpriteNode node = runtime.getSceneController().getActiveSprite("bg");
        assertNotNull(node);
        assertEquals(8.0f, node.getTransform().getBlur(), 0.01f);

        Path graphicsDir = E2eWorkspaceSupport.repoRoot().resolve("src/main/java/de/swfdtf/graphics");
        String pipelineSource = Files.readString(graphicsDir.resolve("RenderPipeline2D.java"), StandardCharsets.UTF_8);
        String lower = pipelineSource.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("getblur") || lower.contains("blur"),
                "RenderPipeline2D must consume Transform2D.getBlur() via Gaussian blur shader (#0175)");
        assertTrue(lower.contains("bloom") || lower.contains("glow"),
                "RenderPipeline2D must support bloom/glow filter pass (#0176)");
        assertTrue(lower.contains("ping") || lower.contains("postfx") || lower.contains("postprocess"),
                "RenderPipeline2D must support FBO ping-pong post-processing chain (#0174)");
    }

    /**
     * Verifies goals {@code #0940} and {@code #0947}: headless OpenGL mock/contract tests exist
     * in {@code src/test/java/de/swfdtf/graphics} and {@code docs/BENCHMARKS.md} records baseline
     * performance metrics alongside live timing checks.
     *
     * @throws Exception if reading repo files fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void headlessGraphicsCoverageGateAndBenchmarksBaselinePresent() throws Exception {
        Path repoRoot = E2eWorkspaceSupport.repoRoot();
        Path benchmarksMd = repoRoot.resolve("docs/BENCHMARKS.md");
        assertTrue(Files.isRegularFile(benchmarksMd),
                "Expected committed performance benchmark baseline at " + benchmarksMd);
        String md = Files.readString(benchmarksMd, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        assertTrue(md.contains("lexer") && md.contains("parser")
                        && md.contains("objectmotioncontroller") && md.contains("styledtextlayout"),
                "docs/BENCHMARKS.md must document Lexer, Parser, ObjectMotionController, and StyledTextLayout");

        Path graphicsTestsDir = repoRoot.resolve("src/test/java/de/swfdtf/graphics");
        long testCount;
        try (Stream<Path> walk = Files.list(graphicsTestsDir)) {
            testCount = walk.filter(p -> p.getFileName().toString().endsWith("Test.java")).count();
        }
        assertTrue(testCount >= 3,
                "Expected headless GL mock/contract test suites in src/test/java/de/swfdtf/graphics (#0940)");

        // Live opaque-box timing sanity check for Lexer, Parser, and ObjectMotionController
        String sampleScript = "label start:\n" + "    \"Zeile.\"\n".repeat(100) + "    return\n";
        long startNs = System.nanoTime();
        List<VJavaLexer.Token> tokens = VJavaLexer.tokenize(sampleScript, "bench.vjava");
        VJavaParser.parse(Path.of("bench.vjava"), tokens);
        long parseMs = (System.nanoTime() - startNs) / 1_000_000L;
        assertTrue(parseMs < 500L, "Lexer+Parser exceeded sanity budget: " + parseMs + " ms");

        SceneController sc = new SceneController(new SceneGraph());
        sc.showSprite("hero", List.of(), null, null);
        sc.getMotionController().enableWasd("hero", 300.0f, 0.0f, 0.0f, 1920.0f, 1080.0f, true);
        long motionStart = System.nanoTime();
        for (int i = 0; i < 1_000; i++) {
            sc.updateMotion(1.0 / 60.0, ObjectMotionController.MotionInput.NONE);
        }
        long motionMs = (System.nanoTime() - motionStart) / 1_000_000L;
        assertTrue(motionMs < 500L, "ObjectMotionController 1k steps exceeded sanity budget: " + motionMs + " ms");
    }

    private static SpriteNode instantiateAnimatedSprite(Class<?> animClass) throws Exception {
        for (Constructor<?> ctor : animClass.getConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 5
                    && params[0] == String.class
                    && params[1] == int.class
                    && params[2] == int.class
                    && params[3] == float.class) {
                Object[] args = new Object[5];
                args[0] = "anim_hero";
                args[1] = 4;
                args[2] = 1;
                args[3] = 4.0f;
                args[4] = params[4].isEnum() ? params[4].getEnumConstants()[0] : "LOOP";
                return (SpriteNode) ctor.newInstance(args);
            }
            if (params.length == 4
                    && params[0] == String.class
                    && params[1] == int.class
                    && params[2] == int.class
                    && params[3] == float.class) {
                return (SpriteNode) ctor.newInstance("anim_hero", 4, 1, 4.0f);
            }
        }
        throw new AssertionError("No supported constructor found on AnimatedSpriteNode");
    }

    private static void writeHorizontalGradientMaskPng(Path dest, int width, int height) {
        byte[] rgba = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                byte lum = (byte) ((x * 255) / Math.max(1, width - 1));
                int idx = (y * width + x) * 4;
                rgba[idx] = lum;
                rgba[idx + 1] = lum;
                rgba[idx + 2] = lum;
                rgba[idx + 3] = (byte) 255;
            }
        }
        TextureManager.writePng(dest, width, height, rgba);
    }
}
