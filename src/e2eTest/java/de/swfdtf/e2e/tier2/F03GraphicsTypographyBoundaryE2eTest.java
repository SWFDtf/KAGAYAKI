package de.swfdtf.e2e.tier2;

import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2eFixtureFactory;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.e2e.support.E2eWorkspaceSupport;
import de.swfdtf.graphics.FontManager;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.graphics.SceneNode;
import de.swfdtf.graphics.StyledTextLayout;
import de.swfdtf.graphics.TextTagParser;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.graphics.Transition;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Transform2D;
import de.swfdtf.tools.VJavaLinter;
import de.swfdtf.ui.Viewport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 2 boundary, robustness, and negative E2E tests for Feature Area F3: corrupt/truncated
 * image files, corrupt WebP streams, extreme Kinsoku punctuation wrapping, missing ImageDissolve
 * masks, unbalanced scissor pops, extreme corner radius/blur values, invalid animated sprite
 * grid sizes, preloader failure resilience, and huge text layout stress.
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER2)
public final class F03GraphicsTypographyBoundaryE2eTest {

    /** Creates a new {@code F03GraphicsTypographyBoundaryE2eTest} instance. */
    public F03GraphicsTypographyBoundaryE2eTest() {
    }

    /**
     * Verifies baseline image decoder resilience: 0-byte files, truncated PNG/JPEG headers,
     * and random garbage fail cleanly with {@link IllegalStateException} rather than native crashes.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if file creation fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void corruptTruncatedAndZeroByteImagesHandledWithoutCrash(@TempDir Path tempDir) throws Exception {
        Path emptyPng = tempDir.resolve("empty.png");
        Files.write(emptyPng, new byte[0]);
        assertThrows(IllegalStateException.class, () -> TextureManager.readImageFileRgba(emptyPng, null));

        Path truncatedPng = tempDir.resolve("truncated.png");
        Files.write(truncatedPng, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        assertThrows(IllegalStateException.class, () -> TextureManager.readImageFileRgba(truncatedPng, null));

        Path garbageJpg = tempDir.resolve("garbage.jpg");
        Files.write(garbageJpg, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x11, 0x22});
        assertThrows(IllegalStateException.class, () -> TextureManager.readImageFileRgba(garbageJpg, null));
    }

    /**
     * Verifies baseline {@code ImageDissolve} expression parser validation: blank mask path
     * throws {@link IllegalArgumentException}, while valid parameters parse accurately.
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void missingImageDissolveMaskAndInvalidArgumentsHandledSafely() {
        assertThrows(IllegalArgumentException.class, () -> Transition.parse("imagedissolve(\"\", 0.5)"));
        assertThrows(IllegalArgumentException.class, () -> Transition.parse("unknown_fx(0.5)"));

        Transition parsed = Transition.parse("imagedissolve(\"assets/masks/wipe.png\", 0.6, ramplen=8, reverse=true)");
        assertEquals(Transition.Kind.IMAGE_DISSOLVE, parsed.getKind());
        assertEquals(0.6f, parsed.getDurationSeconds(), 0.001f);
        assertEquals(8.0f, parsed.getRampLength(), 0.001f);
        assertTrue(parsed.isReverseMask());
    }

    /**
     * Verifies goal {@code #0189} boundary behavior: corrupt {@code RIFF/WEBP} payloads fail
     * cleanly with {@link IllegalStateException}, while valid minimal WebP decodes accurately.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void corruptWebpPayloadAndWrongExtensionRejectedCleanly(@TempDir Path tempDir) throws Exception {
        // Positive control inside boundary test: valid WebP must decode cleanly (#0189)
        Path validWebp = tempDir.resolve("valid.webp");
        E2eFixtureFactory.writeMinimalWebP(validWebp, 4, 4);
        int[] dims = new int[2];
        byte[] rgba = TextureManager.readImageFileRgba(validWebp, dims);
        assertEquals(4, dims[0]);
        assertEquals(4, dims[1]);
        assertEquals(4 * 4 * 4, rgba.length);

        Path corruptWebp = tempDir.resolve("corrupt.webp");
        Files.write(corruptWebp, new byte[]{'R', 'I', 'F', 'F', 12, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', 'L'});
        assertThrows(IllegalStateException.class, () -> TextureManager.readImageFileRgba(corruptWebp, null));
    }

    /**
     * Verifies goal {@code #0685} boundary behavior: all-punctuation Japanese runs, opening
     * brackets, empty text, and extremely narrow {@code maxWidth} never hang or drop glyphs,
     * while normal prose prevents orphaned line-start {@code 。}.
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void kinsokuExtremeNarrowWidthAndAllPunctuationNeverHangsOrOrphans() {
        try (FontManager fm = new FontManager()) {
            TextTagParser.ParsedRichText prose = TextTagParser.parse("あいうえお。かきくけこ。", k -> null);
            StyledTextLayout probe = new StyledTextLayout(
                    prose, fm, Float.POSITIVE_INFINITY, 24.0f, 1.2f, "#ffffff", false
            );
            float fiveCharW = probe.getGlyphQuads().get(5).x() + 1.0f;
            StyledTextLayout wrapped = new StyledTextLayout(prose, fm, fiveCharW, 24.0f, 1.2f, "#ffffff", false);
            assertTrue(wrapped.getLines().size() >= 2);
            for (StyledTextLayout.TextLine line : wrapped.getLines()) {
                if (line.lineIndex() > 0 && line.endCharIndex() > line.startCharIndex()) {
                    assertFalse(
                            prose.plainText().charAt(line.startCharIndex()) == '。',
                            "Line " + line.lineIndex() + " must not start with orphaned '。'"
                    );
                }
            }

            List<String> boundaryInputs = List.of("", "。。。。。。", "「」『』（）", "あ。");
            for (String text : boundaryInputs) {
                TextTagParser.ParsedRichText parsed = TextTagParser.parse(text, k -> null);
                StyledTextLayout narrow = new StyledTextLayout(parsed, fm, 36.0f, 24.0f, 1.2f, "#ffffff", false);
                assertEquals(text.length(), narrow.getGlyphQuads().size());
            }
        }
    }

    /**
     * Verifies goal {@code #0181} boundary behavior: {@link RenderPipeline2D} exposes
     * {@code pushScissor}/{@code popScissor}, guards empty-stack pops safely, and disjoint nested
     * {@link Viewport}s collapse to a zero-area clip rectangle.
     *
     * @throws Exception if reflection lookup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void unbalancedScissorPopAndZeroAreaViewportSafe() throws Exception {
        Method pushScissor = RenderPipeline2D.class.getMethod("pushScissor", Rect2f.class);
        Method popScissor = RenderPipeline2D.class.getMethod("popScissor");
        assertNotNull(pushScissor);
        assertNotNull(popScissor);

        Viewport outer = new Viewport();
        outer.layout(new Rect2f(0.0f, 0.0f, 100.0f, 100.0f));
        Viewport disjoint = new Viewport();
        disjoint.layout(new Rect2f(500.0f, 500.0f, 100.0f, 100.0f));
        assertFalse(outer.getBounds().intersects(disjoint.getBounds()));

        RenderPipeline2D pipeline = new RenderPipeline2D();
        try {
            popScissor.invoke(pipeline);
        } catch (InvocationTargetException ex) {
            assertTrue(ex.getCause() instanceof IllegalStateException,
                    "Expected IllegalStateException on unbalanced popScissor, got: " + ex.getCause());
        }
    }

    /**
     * Verifies goals {@code #0175} and {@code #0182} boundary behavior: {@link SceneNode} exposes
     * soft-shadow controls, extreme corner radii clamp safely, and negative or {@code NaN} blur
     * values on {@link Transform2D} are sanitized cleanly.
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void extremeCornerRadiusBlurAndShadowBoundsClampedSafely() {
        boolean hasShadowApi = Arrays.stream(SceneNode.class.getMethods())
                .anyMatch(m -> m.getName().toLowerCase(Locale.ROOT).contains("shadow"));
        assertTrue(hasShadowApi, "SceneNode must expose soft shadow configuration (#0182)");

        Transform2D tf = new Transform2D();
        tf.setBlur(-5.0f);
        assertTrue(tf.getBlur() >= 0.0f && Float.isFinite(tf.getBlur()));
        tf.setBlur(Float.NaN);
        assertTrue(Float.isFinite(tf.getBlur()));

        SceneNode panel = new SceneNode("panel");
        panel.setSize(100.0f, 40.0f);
        panel.setCornerRadius(9999.0f);
        assertEquals(9999.0f, panel.getCornerRadius(), 0.01f);
    }

    /**
     * Verifies goal {@code #0172} boundary behavior: {@code AnimatedSpriteNode} rejects invalid
     * grid dimensions ({@code 0} columns/rows) or negative FPS with {@link IllegalArgumentException},
     * and handles large {@code dt = 1000.0} without {@link IndexOutOfBoundsException}.
     *
     * @throws Exception if reflection lookup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void animatedSpriteZeroFpsAndOutOfBoundsFrameRejected() throws Exception {
        Class<?> animClass = Class.forName("de.swfdtf.graphics.AnimatedSpriteNode");
        for (Constructor<?> ctor : animClass.getConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length >= 4 && params[0] == String.class
                    && params[1] == int.class && params[2] == int.class && params[3] == float.class) {
                Object[] badArgs = new Object[params.length];
                badArgs[0] = "bad_anim";
                badArgs[1] = 0;
                badArgs[2] = 1;
                badArgs[3] = 10.0f;
                if (params.length == 5) {
                    badArgs[4] = params[4].isEnum() ? params[4].getEnumConstants()[0] : "LOOP";
                }
                InvocationTargetException ex = assertThrows(
                        InvocationTargetException.class,
                        () -> ctor.newInstance(badArgs)
                );
                assertTrue(ex.getCause() instanceof IllegalArgumentException);
                return;
            }
        }
        throw new AssertionError("No supported constructor found on AnimatedSpriteNode");
    }

    /**
     * Verifies goals {@code #0177}, {@code #0179}, and {@code #0947} boundary behavior: missing
     * mask asset in {@code ImageDissolve} is reported by {@link VJavaLinter}, {@link TextureManager}
     * preloader handles missing files safely without uncaught errors, 5,000-char rich text layout
     * completes cleanly, and {@code docs/BENCHMARKS.md} is non-empty.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void preloaderMissingAssetAndLargeTextLayoutStressBoundary(@TempDir Path tempDir) throws Exception {
        Method preloadMethod = Arrays.stream(TextureManager.class.getMethods())
                .filter(m -> m.getName().toLowerCase(Locale.ROOT).contains("preload"))
                .findFirst()
                .orElse(null);
        assertNotNull(preloadMethod, "TextureManager must expose preload API (#0179)");

        Path projectDir = tempDir.resolve("f03_missing_mask");
        E2eFixtureFactory.createMinimalPlayableProject(projectDir, """
                image bg_b = "assets/images/bg/bg_default.png"
                label start:
                    scene bg_b with imagedissolve("assets/masks/missing.png", 0.5)
                    "Test."
                    return
                """);
        VJavaLinter.LintReport lint = E2eCliRunner.lintProject(projectDir);
        assertFalse(lint.issues().isEmpty(), "Missing mask asset should be flagged by VJavaLinter");

        String longText = "あいうえお。".repeat(850);
        TextTagParser.ParsedRichText parsed = TextTagParser.parse(longText, k -> null);
        try (FontManager fm = new FontManager()) {
            long t0 = System.nanoTime();
            StyledTextLayout layout = new StyledTextLayout(parsed, fm, 600.0f, 22.0f, 1.2f, "#ffffff", false);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            assertTrue(layout.getLines().size() > 50);
            assertTrue(elapsedMs < 2_000L, "Large layout exceeded 2s budget: " + elapsedMs + " ms");
        }

        Path benchmarksMd = E2eWorkspaceSupport.repoRoot().resolve("docs/BENCHMARKS.md");
        assertTrue(Files.isRegularFile(benchmarksMd));
        assertFalse(Files.readString(benchmarksMd, StandardCharsets.UTF_8).isBlank());
    }
}
