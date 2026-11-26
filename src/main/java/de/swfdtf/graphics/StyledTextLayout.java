package de.swfdtf.graphics;

import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Rect2f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Rich text word-wrapping, layout, animation-aware OpenGL renderer, and progressive
 * {@link TypewriterController} for KAGAYAKI Visual Novel text.
 *
 * <p>Consumes a {@link TextTagParser.ParsedRichText} and lays out positioned {@link GlyphQuad}s,
 * {@link LineDecoration}s (underlines, strikethroughs, drop-shadow/outline metadata),
 * centered Furigana {@link RubyLayout}s, {@link InlineImageLayout}s, and clickable
 * {@link HyperlinkHitbox} bounds without requiring {@code java.awt}.</p>
 */
public final class StyledTextLayout {

    private static final float SMALL_CAPS_SCALE = 0.78f;
    private static final float RUBY_FONT_SCALE = 0.50f;
    private static final float RUBY_GAP_FACTOR = 0.08f;
    private static final float ITALIC_SKEW_FACTOR = 0.20f;
    private static final float BOLD_OFFSET_FACTOR = 0.045f;
    private static final float DEFAULT_SHADOW_OFFSET = 2.0f;
    private static final String DEFAULT_SHADOW_HEX = "#000000b3";
    private static final Rect2f FULL_UV_RECT = new Rect2f(0.0f, 0.0f, 1.0f, 1.0f);

    /**
     * Decoration categories generated or applied by {@link StyledTextLayout}.
     */
    public enum DecorationKind {
        /** Underline option. */
        UNDERLINE,
        /** Strikethrough option. */
        STRIKETHROUGH,
        /** Drop Shadow option. */
        DROP_SHADOW,
        /** Outline option. */
        OUTLINE
    }

    /**
     * Immutable positioned glyph quad ready for rendering or hit-testing.
     *
     * @param atlas           backing {@link FontAtlas}
     * @param codepoint       rendered Unicode codepoint (already uppercased when {@code smallCapsApplied} is true)
     * @param charIndex       0-based character index in {@link TextTagParser.ParsedRichText#plainText()}
     * @param lineIndex       0-based wrapped line index
     * @param x               quad left X in layout-local coordinates
     * @param y               quad top Y in layout-local coordinates
     * @param width           quad width in pixels
     * @param height          quad height in pixels
     * @param advance         horizontal pen advance consumed by this glyph
     * @param baselineY       baseline Y of the owning line in layout-local coordinates
     * @param effectiveSizePx effective font size in pixels
     * @param uvRect          normalized atlas UV sub-rectangle
     * @param colorRgba       straight RGBA color {@code [r, g, b, a]}
     * @param bold            whether bold embolden pass is active
     * @param italic          whether italic slant is active
     * @param smallCaps       whether small-caps transformation was applied to a lowercase letter
     * @param wave            whether {@code {wave}} animation is active
     * @param jitter          whether {@code {jitter}}/{@code {shake}} animation is active
     * @param hasGradient     whether {@code {gradient=...}} was applied to this glyph
     */
    public record GlyphQuad(
            FontAtlas atlas,
            int codepoint,
            int charIndex,
            int lineIndex,
            float x,
            float y,
            float width,
            float height,
            float advance,
            float baselineY,
            float effectiveSizePx,
            Rect2f uvRect,
            float[] colorRgba,
            boolean bold,
            boolean italic,
            boolean smallCaps,
            boolean wave,
            boolean jitter,
            boolean hasGradient
    ) {
        /**
         * Validates and clones the glyph tint array.
         */
        public GlyphQuad {
            colorRgba = colorRgba.clone();
        }

        /**
         * Returns a defensive copy of the straight RGBA color array.
         *
         * @return cloned RGBA array
         */
        @Override
        public float[] colorRgba() {
            return colorRgba.clone();
        }
    }

    /**
     * Immutable line decoration descriptor (underline, strikethrough, drop-shadow, or outline rule).
     *
     * @param kind           decoration type
     * @param lineIndex      0-based wrapped line index
     * @param x              left X coordinate in layout-local space
     * @param y              top Y coordinate in layout-local space
     * @param width          span width in pixels
     * @param thickness      stroke thickness in pixels
     * @param colorRgba      straight RGBA color {@code [r, g, b, a]}
     * @param startCharIndex inclusive start character index in {@code plainText}
     * @param endCharIndex   exclusive end character index in {@code plainText}
     */
    public record LineDecoration(
            DecorationKind kind,
            int lineIndex,
            float x,
            float y,
            float width,
            float thickness,
            float[] colorRgba,
            int startCharIndex,
            int endCharIndex
    ) {
        /**
         * Validates decoration kind and clones the color array.
         */
        public LineDecoration {
            Objects.requireNonNull(kind, "kind must not be null");
            colorRgba = colorRgba.clone();
        }

        /**
         * Returns a defensive copy of the decoration RGBA color array.
         *
         * @return cloned RGBA array
         */
        @Override
        public float[] colorRgba() {
            return colorRgba.clone();
        }
    }

    /**
     * Immutable Furigana/Ruby annotation positioned directly centered above its base text.
     *
     * @param rubyText       phonetic reading text
     * @param atlas          backing {@link FontAtlas}
     * @param lineIndex      0-based wrapped line index
     * @param x              left X of the centered ruby run in layout-local coordinates
     * @param baselineY      baseline Y of the ruby run above the base text
     * @param width          measured width of the ruby run in pixels
     * @param height         line height of the ruby run in pixels
     * @param baseCenterX    exact horizontal center X of the base text span
     * @param fontSize       ruby font size in pixels
     * @param startCharIndex inclusive start character index of the base text in {@code plainText}
     * @param endCharIndex   exclusive end character index of the base text in {@code plainText}
     * @param rubyQuads      immutable list of positioned {@link GlyphQuad}s for the ruby characters
     */
    public record RubyLayout(
            String rubyText,
            FontAtlas atlas,
            int lineIndex,
            float x,
            float baselineY,
            float width,
            float height,
            float baseCenterX,
            float fontSize,
            int startCharIndex,
            int endCharIndex,
            List<GlyphQuad> rubyQuads
    ) {
        /**
         * Stores an immutable copy of the ruby glyph quads.
         */
        public RubyLayout {
            rubyQuads = List.copyOf(rubyQuads);
        }
    }

    /**
     * Immutable positioned inline image slot generated from {@code {image=...}}.
     *
     * @param imagePath asset path of the inline image
     * @param charIndex character index of the {@code \uFFFC} placeholder in {@code plainText}
     * @param lineIndex 0-based wrapped line index
     * @param x         left X in layout-local coordinates
     * @param y         top Y in layout-local coordinates
     * @param width     allocated width in pixels
     * @param height    allocated height in pixels
     * @param alpha     effective straight opacity in {@code [0, 1]}
     */
    public record InlineImageLayout(
            String imagePath,
            int charIndex,
            int lineIndex,
            float x,
            float y,
            float width,
            float height,
            float alpha
    ) {
    }

    /**
     * Immutable clickable hyperlink hitbox produced from {@code {a=target}...{/a}}.
     *
     * @param bounds       axis-aligned bounding rectangle in layout-local coordinates
     * @param actionTarget action/target payload from {@code {a=...}}
     * @param lineIndex    0-based wrapped line index
     */
    public record HyperlinkHitbox(Rect2f bounds, String actionTarget, int lineIndex) {
        /**
         * Validates non-null hyperlink hitbox bounds and action target.
         */
        public HyperlinkHitbox {
            Objects.requireNonNull(bounds, "bounds must not be null");
            Objects.requireNonNull(actionTarget, "actionTarget must not be null");
        }
    }

    /**
     * Immutable metrics for a single wrapped line in the layout.
     *
     * @param lineIndex      0-based line index
     * @param y              top Y of the line in layout-local coordinates
     * @param baselineY      baseline Y in layout-local coordinates
     * @param width          rendered horizontal width of the line in pixels
     * @param height         total vertical line height in pixels (including ruby headroom if present)
     * @param startCharIndex inclusive start character index in {@code plainText}
     * @param endCharIndex   exclusive end character index in {@code plainText}
     */
    public record TextLine(
            int lineIndex,
            float y,
            float baselineY,
            float width,
            float height,
            int startCharIndex,
            int endCharIndex
    ) {
    }

    /**
     * Progressive typewriter reveal controller managing {@code {cps=...}}, {@code {fast}},
     * {@code {w}}, {@code {w=t}}, {@code {p}}, {@code {p=t}}, and {@code {nw}} cues.
     */
    public static final class TypewriterController {

        /** Default reveal rate in characters per second when no {@code {cps=...}} tag is active. */
        public static final float DEFAULT_CPS = 40.0f;

        private final TextTagParser.ParsedRichText richText;
        private final float[] perCharCps;
        private final int totalCharCount;

        private float defaultCps;
        private int visibleCharCount;
        private double charAccumulator;
        private boolean waiting;
        private boolean waitingTimed;
        private double waitRemainingSeconds;
        private TextTagParser.TextCue activeWaitCue;
        private int nextCueIndex;

        /**
         * Creates a {@code TypewriterController} with the default {@value #DEFAULT_CPS} CPS speed.
         *
         * @param richText parsed rich text to control
         */
        public TypewriterController(TextTagParser.ParsedRichText richText) {
            this(richText, DEFAULT_CPS);
        }

        /**
         * Creates a {@code TypewriterController} with a caller-specified default CPS speed.
         *
         * @param richText   parsed rich text to control
         * @param defaultCps characters per second ({@code <= 0} reveals instantaneously unless paused by cues)
         */
        public TypewriterController(TextTagParser.ParsedRichText richText, float defaultCps) {
            this.richText = Objects.requireNonNull(richText, "richText must not be null");
            this.defaultCps = defaultCps;
            this.totalCharCount = richText.plainText().length();
            this.perCharCps = buildPerCharCps(richText, totalCharCount);
            reset();
        }

        private static float[] buildPerCharCps(TextTagParser.ParsedRichText richText, int totalLen) {
            float[] arr = new float[totalLen];
            int cursor = 0;
            for (TextTagParser.TextSpan span : richText.spans()) {
                float spanCps = span.style().cps();
                int len = span.text().length();
                for (int i = 0; i < len && cursor < totalLen; i++) {
                    arr[cursor++] = spanCps;
                }
            }
            while (cursor < totalLen) {
                arr[cursor++] = -1.0f;
            }
            return arr;
        }

        /**
         * Resets the typewriter back to the initial state (immediately revealing any unblocked
         * {@code {fast}} prefix and triggering any cue at index {@code 0}).
         */
        public void reset() {
            this.visibleCharCount = 0;
            this.charAccumulator = 0.0;
            this.waiting = false;
            this.waitingTimed = false;
            this.waitRemainingSeconds = 0.0;
            this.activeWaitCue = null;
            this.nextCueIndex = 0;
            advanceFastAndCues(0.0);
        }

        /**
         * Advances the typewriter simulation by {@code dt} seconds.
         *
         * @param dt elapsed delta time in seconds ({@code >= 0})
         * @throws IllegalArgumentException if {@code dt < 0} or not finite
         */
        public void advance(double dt) {
            if (!Double.isFinite(dt) || dt < 0.0) {
                throw new IllegalArgumentException("dt must be finite and >= 0, got " + dt);
            }
            if (isFinished()) {
                return;
            }
            advanceFastAndCues(dt);
        }

        /**
         * Handles a player click/confirm input:
         * <ul>
         *   <li>If currently paused at a wait cue ({@code {w}}, {@code {w=t}}, {@code {p}}, {@code {p=t}}),
         *       consumes the wait and immediately advances past any {@code {p}} newline and {@code {fast}} prefix.</li>
         *   <li>If currently typing, immediately reveals all characters up to the next blocking wait cue
         *       (entering that wait) or to the end of the text.</li>
         * </ul>
         *
         * @return {@code true} if state changed, {@code false} if already finished
         */
        public boolean clickOrAdvance() {
            if (isFinished()) {
                return false;
            }
            if (waiting) {
                boolean wasParagraph = activeWaitCue != null
                        && activeWaitCue.kind() == TextTagParser.CueKind.PARAGRAPH;
                clearWaitState();
                if (wasParagraph
                        && visibleCharCount < totalCharCount
                        && richText.plainText().charAt(visibleCharCount) == '\n') {
                    visibleCharCount++;
                }
                advanceFastAndCues(0.0);
                return true;
            }

            charAccumulator = 0.0;
            while (visibleCharCount <= totalCharCount) {
                if (triggerBlockingCuesAtCurrentIndex()) {
                    return true;
                }
                if (visibleCharCount >= totalCharCount) {
                    return true;
                }
                visibleCharCount++;
            }
            return true;
        }

        private void advanceFastAndCues(double dt) {
            double remainingDt = dt;

            if (waiting) {
                if (!waitingTimed) {
                    return;
                }
                waitRemainingSeconds -= remainingDt;
                if (waitRemainingSeconds > 1e-9) {
                    return;
                }
                remainingDt = Math.max(0.0, -waitRemainingSeconds);
                boolean wasParagraph = activeWaitCue != null
                        && activeWaitCue.kind() == TextTagParser.CueKind.PARAGRAPH;
                clearWaitState();
                if (wasParagraph
                        && visibleCharCount < totalCharCount
                        && richText.plainText().charAt(visibleCharCount) == '\n') {
                    visibleCharCount++;
                }
            }

            while (true) {
                if (triggerBlockingCuesAtCurrentIndex()) {
                    return;
                }
                if (visibleCharCount >= totalCharCount) {
                    return;
                }
                if (visibleCharCount < richText.fastPrefixEndIndex()) {
                    visibleCharCount++;
                    continue;
                }

                float effectiveCps = resolveEffectiveCps(visibleCharCount);
                if (effectiveCps <= 0.0f) {
                    visibleCharCount++;
                    continue;
                }

                double secondsPerChar = 1.0 / effectiveCps;
                if (remainingDt > 0.0) {
                    charAccumulator += remainingDt;
                    remainingDt = 0.0;
                }
                if (charAccumulator + 1e-12 >= secondsPerChar) {
                    charAccumulator -= secondsPerChar;
                    visibleCharCount++;
                } else {
                    return;
                }
            }
        }

        private boolean triggerBlockingCuesAtCurrentIndex() {
            List<TextTagParser.TextCue> cues = richText.cues();
            while (nextCueIndex < cues.size()) {
                TextTagParser.TextCue cue = cues.get(nextCueIndex);
                if (cue.index() > visibleCharCount) {
                    return false;
                }
                nextCueIndex++;
                if (cue.index() < visibleCharCount) {
                    continue;
                }
                switch (cue.kind()) {
                    case WAIT_CLICK -> {
                        enterClickWait(cue);
                        return true;
                    }
                    case WAIT_TIMED -> {
                        if (cue.seconds() > 0.0) {
                            enterTimedWait(cue, cue.seconds());
                            return true;
                        }
                    }
                    case PARAGRAPH -> {
                        if (cue.seconds() > 0.0) {
                            enterTimedWait(cue, cue.seconds());
                        } else {
                            enterClickWait(cue);
                        }
                        return true;
                    }
                    case NO_WAIT, FAST -> {
                        // Non-blocking cues.
                    }
                }
            }
            return false;
        }

        private void enterClickWait(TextTagParser.TextCue cue) {
            this.waiting = true;
            this.waitingTimed = false;
            this.waitRemainingSeconds = 0.0;
            this.activeWaitCue = cue;
            this.charAccumulator = 0.0;
        }

        private void enterTimedWait(TextTagParser.TextCue cue, double seconds) {
            this.waiting = true;
            this.waitingTimed = true;
            this.waitRemainingSeconds = seconds;
            this.activeWaitCue = cue;
            this.charAccumulator = 0.0;
        }

        private void clearWaitState() {
            this.waiting = false;
            this.waitingTimed = false;
            this.waitRemainingSeconds = 0.0;
            this.activeWaitCue = null;
        }

        private float resolveEffectiveCps(int charIndex) {
            if (charIndex >= 0 && charIndex < perCharCps.length && perCharCps[charIndex] >= 0.0f) {
                return perCharCps[charIndex];
            }
            return defaultCps;
        }

        /**
         * Returns {@code true} if paused at a {@code {w}}, {@code {w=t}}, {@code {p}}, or {@code {p=t}} cue.
         *
         * @return {@code true} if paused at a {@code {w}}, {@code {w=t}}, {@code {p}}, or {@code {p=t}} cue
         */
        public boolean isWaiting() {
            return waiting;
        }

        /**
         * Returns {@code true} if currently paused on a timed wait cue.
         *
         * @return {@code true} if currently paused on a timed wait cue
         */
        public boolean isTimedWait() {
            return waiting && waitingTimed;
        }

        /**
         * Returns remaining seconds on the active timed wait, or {@code 0.0}.
         *
         * @return remaining seconds on the active timed wait, or {@code 0.0}
         */
        public double getWaitRemainingSeconds() {
            return waitingTimed ? Math.max(0.0, waitRemainingSeconds) : 0.0;
        }

        /**
         * Returns currently active wait cue, or {@code null}.
         *
         * @return currently active wait cue, or {@code null}
         */
        public TextTagParser.TextCue getActiveWaitCue() {
            return activeWaitCue;
        }

        /**
         * Returns {@code true} if all characters are visible and no pending wait cues remain.
         *
         * @return {@code true} if all characters are visible and no pending wait cues remain
         */
        public boolean isFinished() {
            return !waiting && visibleCharCount >= totalCharCount && nextCueIndex >= richText.cues().size();
        }

        /**
         * Returns number of characters currently revealed in {@link TextTagParser.ParsedRichText#plainText()}.
         *
         * @return number of characters currently revealed in {@link TextTagParser.ParsedRichText#plainText()}
         */
        public int getVisibleCharCount() {
            return visibleCharCount;
        }

        /**
         * Returns total character count in {@link TextTagParser.ParsedRichText#plainText()}.
         *
         * @return total character count in {@link TextTagParser.ParsedRichText#plainText()}
         */
        public int getTotalCharCount() {
            return totalCharCount;
        }

        /**
         * Returns {@code true} if the parsed rich text contained {@code {nw}}.
         *
         * @return {@code true} if the parsed rich text contained {@code {nw}}
         */
        public boolean hasNoWait() {
            return richText.hasNoWait();
        }

        /**
         * Returns {@code true} if finished and {@code {nw}} requests immediate auto-advance.
         *
         * @return {@code true} if finished and {@code {nw}} requests immediate auto-advance
         */
        public boolean shouldAutoAdvance() {
            return isFinished() && richText.hasNoWait();
        }

        /**
         * Returns configured default CPS speed.
         *
         * @return configured default CPS speed
         */
        public float getDefaultCps() {
            return defaultCps;
        }

        /**
         * Updates the default CPS speed used when no local {@code {cps=...}} tag overrides it.
         *
         * @param defaultCps characters per second ({@code <= 0} means instant)
         */
        public void setDefaultCps(float defaultCps) {
            this.defaultCps = defaultCps;
        }
    }

    private final TextTagParser.ParsedRichText richText;
    private final float maxWidth;
    private final float baseFontSize;
    private final float lineSpacing;
    private final float[] baseColorRgba;

    private final List<GlyphQuad> glyphQuads;
    private final List<LineDecoration> decorations;
    private final List<RubyLayout> rubyLayouts;
    private final List<InlineImageLayout> inlineImages;
    private final List<HyperlinkHitbox> hyperlinkHitboxes;
    private final List<TextLine> lines;
    private final float totalWidth;
    private final float totalHeight;

    private float shadowOffsetX = DEFAULT_SHADOW_OFFSET;
    private float shadowOffsetY = DEFAULT_SHADOW_OFFSET;
    private float[] shadowColorRgba = SceneNode.parseHexRgba(DEFAULT_SHADOW_HEX);
    private float outlineThickness = 0.0f;
    private float[] outlineColorRgba = new float[]{0.0f, 0.0f, 0.0f, 1.0f};

    /**
     * Lays out and word-wraps {@code richText} using {@code fontManager}.
     *
     * @param richText     parsed rich text model
     * @param fontManager  font atlas provider and accessibility settings
     * @param maxWidth     maximum line width in pixels ({@code <= 0} or {@code Infinite} disables wrapping)
     * @param baseFontSize base pixel font size ({@code > 0})
     * @param lineSpacing  line height multiplier ({@code > 0}, e.g. {@code 1.2f})
     * @param baseColorHex default hex color (e.g. {@code "#f0f6fc"}), or {@code null} for white
     * @param uploadGl     whether requested font atlases should be uploaded to OpenGL
     */
    public StyledTextLayout(
            TextTagParser.ParsedRichText richText,
            FontManager fontManager,
            float maxWidth,
            float baseFontSize,
            float lineSpacing,
            String baseColorHex,
            boolean uploadGl
    ) {
        this.richText = Objects.requireNonNull(richText, "richText must not be null");
        Objects.requireNonNull(fontManager, "fontManager must not be null");
        if (!Float.isFinite(baseFontSize) || baseFontSize <= 0.0f) {
            throw new IllegalArgumentException("baseFontSize must be finite and > 0, got " + baseFontSize);
        }
        this.maxWidth = (maxWidth > 0.0f && Float.isFinite(maxWidth)) ? maxWidth : Float.POSITIVE_INFINITY;
        this.baseFontSize = baseFontSize;
        this.lineSpacing = (lineSpacing > 0.0f && Float.isFinite(lineSpacing)) ? lineSpacing : 1.0f;
        this.baseColorRgba = (baseColorHex != null && !baseColorHex.isBlank())
                ? SceneNode.parseHexRgba(baseColorHex)
                : new float[]{1.0f, 1.0f, 1.0f, 1.0f};

        LayoutBuilder builder = new LayoutBuilder(
                richText,
                fontManager,
                this.maxWidth,
                this.baseFontSize,
                this.lineSpacing,
                this.baseColorRgba,
                uploadGl
        );
        builder.build();

        this.glyphQuads = Collections.unmodifiableList(builder.glyphQuads);
        this.decorations = Collections.unmodifiableList(builder.decorations);
        this.rubyLayouts = Collections.unmodifiableList(builder.rubyLayouts);
        this.inlineImages = Collections.unmodifiableList(builder.inlineImages);
        this.hyperlinkHitboxes = Collections.unmodifiableList(builder.hyperlinkHitboxes);
        this.lines = Collections.unmodifiableList(builder.lines);
        this.totalWidth = builder.totalWidth;
        this.totalHeight = builder.totalHeight;
    }

    /**
     * Convenience factory delegating to {@link #StyledTextLayout}.
     *
     * @param richText     parsed rich text model
     * @param fontManager  font atlas provider
     * @param maxWidth     maximum line width in pixels
     * @param baseFontSize base pixel font size
     * @param lineSpacing  line height multiplier
     * @param baseColorHex default hex color
     * @param uploadGl     whether to upload atlases to OpenGL
     * @return computed {@link StyledTextLayout}
     */
    public static StyledTextLayout layout(
            TextTagParser.ParsedRichText richText,
            FontManager fontManager,
            float maxWidth,
            float baseFontSize,
            float lineSpacing,
            String baseColorHex,
            boolean uploadGl
    ) {
        return new StyledTextLayout(richText, fontManager, maxWidth, baseFontSize, lineSpacing, baseColorHex, uploadGl);
    }

    /**
     * Configures drop-shadow rendering offset and color (set offsets to {@code 0} to disable).
     *
     * @param offsetX        horizontal shadow offset in pixels
     * @param offsetY        vertical shadow offset in pixels
     * @param shadowColorHex hex shadow color (e.g. {@code "#000000b3"})
     * @return {@code this} for fluent chaining
     */
    public StyledTextLayout setDropShadow(float offsetX, float offsetY, String shadowColorHex) {
        this.shadowOffsetX = offsetX;
        this.shadowOffsetY = offsetY;
        this.shadowColorRgba = shadowColorHex != null
                ? SceneNode.parseHexRgba(shadowColorHex)
                : new float[]{0.0f, 0.0f, 0.0f, 0.0f};
        return this;
    }

    /**
     * Configures character outline rendering thickness and color (set {@code thickness <= 0} to disable).
     *
     * @param thickness       outline offset radius in pixels ({@code >= 0})
     * @param outlineColorHex hex outline color (e.g. {@code "#0d0e15"})
     * @return {@code this} for fluent chaining
     */
    public StyledTextLayout setOutline(float thickness, String outlineColorHex) {
        this.outlineThickness = Math.max(0.0f, thickness);
        this.outlineColorRgba = outlineColorHex != null
                ? SceneNode.parseHexRgba(outlineColorHex)
                : new float[]{0.0f, 0.0f, 0.0f, 1.0f};
        return this;
    }

    /**
     * Renders the complete layout (all characters visible at full opacity).
     *
     * @param pipeline           active 2D render pipeline
     * @param textureManager     texture manager for inline images (may be {@code null})
     * @param originX            top-left canvas X coordinate
     * @param originY            top-left canvas Y coordinate
     * @param elapsedTimeSeconds total elapsed animation clock in seconds
     */
    public void render(
            RenderPipeline2D pipeline,
            TextureManager textureManager,
            float originX,
            float originY,
            double elapsedTimeSeconds
    ) {
        render(pipeline, textureManager, originX, originY, elapsedTimeSeconds, Integer.MAX_VALUE, 1.0f);
    }

    /**
     * Renders visible glyphs, drop shadows/outlines, decorations, Furigana, and inline images
     * through {@link RenderPipeline2D}, applying real-time {@code {wave}}, {@code {jitter}}, and
     * {@code {gradient}} effects.
     *
     * @param pipeline           initialized 2D render pipeline
     * @param textureManager     texture manager for {@code {image=...}} inline icons (may be {@code null})
     * @param originX            top-left X origin on the logical canvas
     * @param originY            top-left Y origin on the logical canvas
     * @param elapsedTimeSeconds elapsed animation time in seconds for {@code {wave}} / {@code {jitter}}
     * @param visibleCharLimit   maximum visible character index ({@code < 0} reveals all characters)
     * @param globalAlpha        global opacity multiplier in {@code [0, 1]}
     */
    public void render(
            RenderPipeline2D pipeline,
            TextureManager textureManager,
            float originX,
            float originY,
            double elapsedTimeSeconds,
            int visibleCharLimit,
            float globalAlpha
    ) {
        if (pipeline == null || !pipeline.isInitialized()) {
            return;
        }
        float clampedGlobalAlpha = Math.clamp(globalAlpha, 0.0f, 1.0f);
        if (clampedGlobalAlpha <= 0.0f || visibleCharLimit == 0) {
            return;
        }
        int effectiveLimit = visibleCharLimit < 0 ? Integer.MAX_VALUE : visibleCharLimit;

        for (GlyphQuad quad : glyphQuads) {
            if (quad.charIndex() >= effectiveLimit) {
                continue;
            }
            renderGlyphQuad(pipeline, quad, originX, originY, elapsedTimeSeconds, clampedGlobalAlpha);
        }

        for (RubyLayout ruby : rubyLayouts) {
            if (effectiveLimit <= ruby.startCharIndex()) {
                continue;
            }
            for (GlyphQuad rubyQuad : ruby.rubyQuads()) {
                renderGlyphQuad(pipeline, rubyQuad, originX, originY, elapsedTimeSeconds, clampedGlobalAlpha);
            }
        }

        for (LineDecoration dec : decorations) {
            if (effectiveLimit <= dec.startCharIndex()) {
                continue;
            }
            float visibleWidth = computeVisibleDecorationWidth(dec, effectiveLimit);
            if (visibleWidth <= 0.0f) {
                continue;
            }
            float[] color = dec.colorRgba();
            float effAlpha = color[3] * clampedGlobalAlpha;
            if (effAlpha <= 0.0f) {
                continue;
            }
            if ((shadowOffsetX != 0.0f || shadowOffsetY != 0.0f) && shadowColorRgba[3] > 0.0f) {
                pipeline.drawRect(
                        originX + dec.x() + shadowOffsetX,
                        originY + dec.y() + shadowOffsetY,
                        visibleWidth,
                        dec.thickness(),
                        shadowColorRgba[0],
                        shadowColorRgba[1],
                        shadowColorRgba[2],
                        shadowColorRgba[3] * effAlpha
                );
            }
            pipeline.drawRect(
                    originX + dec.x(),
                    originY + dec.y(),
                    visibleWidth,
                    dec.thickness(),
                    color[0],
                    color[1],
                    color[2],
                    effAlpha
            );
        }

        if (textureManager != null) {
            for (InlineImageLayout img : inlineImages) {
                if (effectiveLimit <= img.charIndex()) {
                    continue;
                }
                Texture tex = textureManager.loadTexture(img.imagePath());
                if (tex == null || tex.getTextureId() == 0) {
                    continue;
                }
                float texAspect = tex.getWidth() / (float) tex.getHeight();
                float boxAspect = img.width() / img.height();
                float drawW = img.width();
                float drawH = img.height();
                if (texAspect > boxAspect) {
                    drawH = drawW / texAspect;
                } else {
                    drawW = drawH * texAspect;
                }
                float drawX = originX + img.x() + (img.width() - drawW) * 0.5f;
                float drawY = originY + img.y() + (img.height() - drawH) * 0.5f;
                float imgAlpha = Math.clamp(img.alpha() * clampedGlobalAlpha, 0.0f, 1.0f);
                if (imgAlpha > 0.0f) {
                    pipeline.drawTexturedQuad(
                            tex.getTextureId(),
                            drawX,
                            drawY,
                            drawW,
                            drawH,
                            FULL_UV_RECT,
                            new float[]{1.0f, 1.0f, 1.0f, imgAlpha},
                            ColorMatrix4f.identity()
                    );
                }
            }
        }
    }

    private void renderGlyphQuad(
            RenderPipeline2D pipeline,
            GlyphQuad quad,
            float originX,
            float originY,
            double elapsedTimeSeconds,
            float globalAlpha
    ) {
        if (quad.width() <= 0.0f || quad.height() <= 0.0f) {
            return;
        }
        FontAtlas atlas = quad.atlas();
        if (atlas.getGlTextureId() == 0) {
            atlas.uploadToOpenGl();
        }
        int texId = atlas.getGlTextureId();
        if (texId == 0) {
            return;
        }

        float dx = 0.0f;
        float dy = 0.0f;
        if (quad.wave()) {
            float amp = Math.max(2.0f, quad.effectiveSizePx() * 0.14f);
            dy += (float) Math.sin(elapsedTimeSeconds * 5.0 + quad.charIndex() * 0.65) * amp;
        }
        if (quad.jitter()) {
            float amp = Math.max(1.0f, quad.effectiveSizePx() * 0.06f);
            double phase = Math.floor(elapsedTimeSeconds * 45.0);
            dx += (float) Math.sin(phase * 12.9898 + quad.charIndex() * 78.233) * amp;
            dy += (float) Math.cos(phase * 93.9898 + quad.charIndex() * 37.719) * amp;
        }

        float baseX = originX + quad.x() + dx;
        float baseY = originY + quad.y() + dy;
        float[] fg = quad.colorRgba();
        fg[3] *= globalAlpha;
        if (fg[3] <= 0.0f) {
            return;
        }

        if (outlineThickness > 0.0f && outlineColorRgba[3] > 0.0f) {
            float[] outColor = new float[]{
                    outlineColorRgba[0],
                    outlineColorRgba[1],
                    outlineColorRgba[2],
                    outlineColorRgba[3] * fg[3]
            };
            float d = outlineThickness;
            drawStyledGlyph(pipeline, texId, baseX - d, baseY, quad, outColor);
            drawStyledGlyph(pipeline, texId, baseX + d, baseY, quad, outColor);
            drawStyledGlyph(pipeline, texId, baseX, baseY - d, quad, outColor);
            drawStyledGlyph(pipeline, texId, baseX, baseY + d, quad, outColor);
        }

        if ((shadowOffsetX != 0.0f || shadowOffsetY != 0.0f) && shadowColorRgba[3] > 0.0f) {
            float[] shColor = new float[]{
                    shadowColorRgba[0],
                    shadowColorRgba[1],
                    shadowColorRgba[2],
                    shadowColorRgba[3] * fg[3]
            };
            drawStyledGlyph(pipeline, texId, baseX + shadowOffsetX, baseY + shadowOffsetY, quad, shColor);
        }

        drawStyledGlyph(pipeline, texId, baseX, baseY, quad, fg);
    }

    private static void drawStyledGlyph(
            RenderPipeline2D pipeline,
            int texId,
            float x,
            float y,
            GlyphQuad quad,
            float[] tintRgba
    ) {
        drawSinglePassGlyph(pipeline, texId, x, y, quad, tintRgba);
        if (quad.bold()) {
            float boldDx = Math.max(0.75f, quad.effectiveSizePx() * BOLD_OFFSET_FACTOR);
            drawSinglePassGlyph(pipeline, texId, x + boldDx, y, quad, tintRgba);
        }
    }

    private static void drawSinglePassGlyph(
            RenderPipeline2D pipeline,
            int texId,
            float x,
            float y,
            GlyphQuad quad,
            float[] tintRgba
    ) {
        if (!quad.italic()) {
            pipeline.drawTexturedQuad(
                    texId,
                    x,
                    y,
                    quad.width(),
                    quad.height(),
                    quad.uvRect(),
                    tintRgba,
                    ColorMatrix4f.identity()
            );
            return;
        }

        int slices = 4;
        float skewTotal = quad.height() * ITALIC_SKEW_FACTOR;
        float sliceH = quad.height() / slices;
        Rect2f uv = quad.uvRect();
        float sliceVh = uv.height() / slices;
        for (int s = 0; s < slices; s++) {
            float tTop = s / (float) slices;
            float skewOffset = skewTotal * (1.0f - tTop - (0.5f / slices));
            float sy = y + s * sliceH;
            float sh = (s == slices - 1) ? (quad.height() - s * sliceH) : sliceH;
            float sv = uv.y() + s * sliceVh;
            float svh = (s == slices - 1) ? (uv.height() - s * sliceVh) : sliceVh;
            pipeline.drawTexturedQuad(
                    texId,
                    x + skewOffset,
                    sy,
                    quad.width(),
                    sh,
                    new Rect2f(uv.x(), sv, uv.width(), svh),
                    tintRgba,
                    ColorMatrix4f.identity()
            );
        }
    }

    private float computeVisibleDecorationWidth(LineDecoration dec, int effectiveLimit) {
        if (effectiveLimit >= dec.endCharIndex()) {
            return dec.width();
        }
        float maxRight = dec.x();
        for (GlyphQuad quad : glyphQuads) {
            int idx = quad.charIndex();
            if (quad.lineIndex() == dec.lineIndex()
                    && idx >= dec.startCharIndex()
                    && idx < effectiveLimit) {
                float right = quad.x() + quad.advance();
                if (right > maxRight) {
                    maxRight = right;
                }
            }
        }
        for (InlineImageLayout img : inlineImages) {
            int idx = img.charIndex();
            if (img.lineIndex() == dec.lineIndex()
                    && idx >= dec.startCharIndex()
                    && idx < effectiveLimit) {
                float right = img.x() + img.width();
                if (right > maxRight) {
                    maxRight = right;
                }
            }
        }
        return Math.max(0.0f, Math.min(dec.width(), maxRight - dec.x()));
    }

    /**
     * Tests whether a point in layout-local coordinates hits any hyperlink hitbox.
     *
     * @param localX X coordinate relative to layout origin
     * @param localY Y coordinate relative to layout origin
     * @return matching hyperlink target string, or empty if no link was hit
     */
    public Optional<String> hitTestHyperlink(float localX, float localY) {
        for (HyperlinkHitbox hitbox : hyperlinkHitboxes) {
            if (hitbox.bounds().contains(localX, localY)) {
                return Optional.of(hitbox.actionTarget());
            }
        }
        return Optional.empty();
    }

    /**
     * Tests whether a canvas point hits any hyperlink hitbox given layout origin {@code (originX, originY)}.
     *
     * @param canvasX X coordinate in logical canvas space
     * @param canvasY Y coordinate in logical canvas space
     * @param originX layout top-left X in logical canvas space
     * @param originY layout top-left Y in logical canvas space
     * @return matching hyperlink target string, or empty if no link was hit
     */
    public Optional<String> hitTestHyperlink(float canvasX, float canvasY, float originX, float originY) {
        return hitTestHyperlink(canvasX - originX, canvasY - originY);
    }

    /**
     * Returns source {@link TextTagParser.ParsedRichText}.
     *
     * @return source {@link TextTagParser.ParsedRichText}
     */
    public TextTagParser.ParsedRichText getRichText() {
        return richText;
    }

    /**
     * Returns configured maximum wrapping width ({@link Float#POSITIVE_INFINITY} if unwrapped).
     *
     * @return configured maximum wrapping width ({@link Float#POSITIVE_INFINITY} if unwrapped)
     */
    public float getMaxWidth() {
        return maxWidth;
    }

    /**
     * Returns configured unscaled base font size.
     *
     * @return configured unscaled base font size
     */
    public float getBaseFontSize() {
        return baseFontSize;
    }

    /**
     * Returns configured line spacing multiplier.
     *
     * @return configured line spacing multiplier
     */
    public float getLineSpacing() {
        return lineSpacing;
    }

    /**
     * Returns immutable list of all positioned glyph quads.
     *
     * @return immutable list of all positioned glyph quads
     */
    public List<GlyphQuad> getGlyphQuads() {
        return glyphQuads;
    }

    /**
     * Returns immutable list of all underline/strikethrough line decorations.
     *
     * @return immutable list of all underline/strikethrough line decorations
     */
    public List<LineDecoration> getDecorations() {
        return decorations;
    }

    /**
     * Returns immutable list of all Furigana {@link RubyLayout}s.
     *
     * @return immutable list of all Furigana {@link RubyLayout}s
     */
    public List<RubyLayout> getRubyLayouts() {
        return rubyLayouts;
    }

    /**
     * Returns immutable list of all positioned {@link InlineImageLayout}s.
     *
     * @return immutable list of all positioned {@link InlineImageLayout}s
     */
    public List<InlineImageLayout> getInlineImages() {
        return inlineImages;
    }

    /**
     * Returns immutable list of clickable {@link HyperlinkHitbox} bounds.
     *
     * @return immutable list of clickable {@link HyperlinkHitbox} bounds
     */
    public List<HyperlinkHitbox> getHyperlinkHitboxes() {
        return hyperlinkHitboxes;
    }

    /**
     * Returns immutable list of wrapped {@link TextLine} metrics.
     *
     * @return immutable list of wrapped {@link TextLine} metrics
     */
    public List<TextLine> getLines() {
        return lines;
    }

    /**
     * Returns number of wrapped lines.
     *
     * @return number of wrapped lines
     */
    public int getLineCount() {
        return lines.size();
    }

    /**
     * Returns widest wrapped line width in pixels.
     *
     * @return widest wrapped line width in pixels
     */
    public float getTotalWidth() {
        return totalWidth;
    }

    /**
     * Returns total vertical layout height in pixels.
     *
     * @return total vertical layout height in pixels
     */
    public float getTotalHeight() {
        return totalHeight;
    }

    /**
     * Returns configured drop shadow horizontal offset.
     *
     * @return configured drop shadow horizontal offset
     */
    public float getShadowOffsetX() {
        return shadowOffsetX;
    }

    /**
     * Returns configured drop shadow vertical offset.
     *
     * @return configured drop shadow vertical offset
     */
    public float getShadowOffsetY() {
        return shadowOffsetY;
    }

    /**
     * Returns configured outline radius in pixels.
     *
     * @return configured outline radius in pixels
     */
    public float getOutlineThickness() {
        return outlineThickness;
    }

    // -------------------------------------------------------------------------
    // Internal layout builder
    // -------------------------------------------------------------------------

    private record StyledChar(
            int codepoint,
            int charIndex,
            TextTagParser.TextStyle style,
            float[] resolvedColorRgba,
            boolean hasGradient
    ) {
    }

    private sealed interface LayoutAtom permits WordAtom, SpaceAtom, NewlineAtom, RubyAtom, ImageAtom {
        float width();
    }

    private record WordAtom(List<StyledChar> chars, float width) implements LayoutAtom {
    }

    private record SpaceAtom(StyledChar ch, float width) implements LayoutAtom {
    }

    private record NewlineAtom(int charIndex, TextTagParser.TextStyle style) implements LayoutAtom {
        @Override
        public float width() {
            return 0.0f;
        }
    }

    private record RubyAtom(
            List<StyledChar> baseChars,
            String rubyText,
            float baseWidth,
            float rubyWidth,
            float rubyFontSize,
            FontAtlas rubyAtlas,
            float totalWidth
    ) implements LayoutAtom {
        @Override
        public float width() {
            return totalWidth;
        }
    }

    private record ImageAtom(
            String imagePath,
            int charIndex,
            float width,
            float height,
            float alpha
    ) implements LayoutAtom {
    }

    private static final class LayoutBuilder {
        private final TextTagParser.ParsedRichText richText;
        private final FontManager fontManager;
        private final float maxWidth;
        private final float baseFontSize;
        private final float lineSpacing;
        private final float[] baseColorRgba;
        private final boolean uploadGl;

        final List<GlyphQuad> glyphQuads = new ArrayList<>();
        final List<LineDecoration> decorations = new ArrayList<>();
        final List<RubyLayout> rubyLayouts = new ArrayList<>();
        final List<InlineImageLayout> inlineImages = new ArrayList<>();
        final List<HyperlinkHitbox> hyperlinkHitboxes = new ArrayList<>();
        final List<TextLine> lines = new ArrayList<>();

        float totalWidth = 0.0f;
        float totalHeight = 0.0f;

        LayoutBuilder(
                TextTagParser.ParsedRichText richText,
                FontManager fontManager,
                float maxWidth,
                float baseFontSize,
                float lineSpacing,
                float[] baseColorRgba,
                boolean uploadGl
        ) {
            this.richText = richText;
            this.fontManager = fontManager;
            this.maxWidth = maxWidth;
            this.baseFontSize = baseFontSize;
            this.lineSpacing = lineSpacing;
            this.baseColorRgba = baseColorRgba;
            this.uploadGl = uploadGl;
        }

        void build() {
            List<LayoutAtom> atoms = buildAtoms();
            List<List<LayoutAtom>> wrappedLines = wrapAtoms(atoms);
            positionLines(wrappedLines);
        }

        private List<LayoutAtom> buildAtoms() {
            List<TextTagParser.TextSpan> spans = richText.spans();
            int totalChars = richText.plainText().length();
            float[][] gradientColors = resolvePerCharGradientColors(spans, totalChars);

            List<LayoutAtom> atoms = new ArrayList<>();
            List<StyledChar> currentWord = new ArrayList<>();
            int globalCharIdx = 0;

            int spanIdx = 0;
            while (spanIdx < spans.size()) {
                TextTagParser.TextSpan span = spans.get(spanIdx);

                if (span.inlineImagePath() != null) {
                    flushWord(atoms, currentWord);
                    float effSize = fontManager.scaleFontSize(span.style().resolveFontSize(baseFontSize));
                    float imgSize = Math.max(8.0f, effSize * 1.10f);
                    atoms.add(new ImageAtom(
                            span.inlineImagePath(),
                            globalCharIdx,
                            imgSize,
                            imgSize,
                            span.style().alpha()
                    ));
                    globalCharIdx += span.text().length();
                    spanIdx++;
                    continue;
                }

                if (span.rubyReading() != null && !span.rubyReading().isEmpty()) {
                    flushWord(atoms, currentWord);
                    String rubyReading = span.rubyReading();
                    List<StyledChar> rubyBaseChars = new ArrayList<>();
                    TextTagParser.TextStyle firstStyle = span.style();

                    while (spanIdx < spans.size()
                            && spans.get(spanIdx).inlineImagePath() == null
                            && rubyReading.equals(spans.get(spanIdx).rubyReading())) {
                        TextTagParser.TextSpan rSpan = spans.get(spanIdx);
                        String txt = rSpan.text();
                        for (int i = 0; i < txt.length(); ) {
                            int cp = txt.codePointAt(i);
                            int count = Character.charCount(cp);
                            rubyBaseChars.add(makeStyledChar(cp, globalCharIdx, rSpan.style(), gradientColors));
                            globalCharIdx += count;
                            i += count;
                        }
                        spanIdx++;
                    }

                    float baseW = measureStyledCharsWidth(rubyBaseChars);
                    float effBaseSize = fontManager.scaleFontSize(firstStyle.resolveFontSize(baseFontSize));
                    float rubySize = Math.max(6.0f, effBaseSize * RUBY_FONT_SCALE);
                    FontAtlas rubyAtlas = fontManager.getAtlas(firstStyle.fontPath(), rubySize, uploadGl);
                    rubyAtlas.ensureCodepoints(rubyReading);
                    float extraK = firstStyle.kerning() + fontManager.getAccessibilityExtraKerning();
                    float rubyW = rubyAtlas.measureTextWidth(rubyReading, rubySize, extraK);
                    float slotW = Math.max(baseW, rubyW);
                    atoms.add(new RubyAtom(rubyBaseChars, rubyReading, baseW, rubyW, rubySize, rubyAtlas, slotW));
                    continue;
                }

                String text = span.text();
                for (int i = 0; i < text.length(); ) {
                    int cp = text.codePointAt(i);
                    int count = Character.charCount(cp);
                    if (cp == '\n') {
                        flushWord(atoms, currentWord);
                        atoms.add(new NewlineAtom(globalCharIdx, span.style()));
                    } else if (cp == '\r') {
                        // Ignore carriage returns cleanly.
                    } else if (cp == ' ' || cp == '\t' || cp == '\u00A0') {
                        flushWord(atoms, currentWord);
                        StyledChar sc = makeStyledChar(cp, globalCharIdx, span.style(), gradientColors);
                        float w = measureStyledCharsWidth(List.of(sc));
                        atoms.add(new SpaceAtom(sc, w));
                    } else if (isCjkBreakable(cp)) {
                        flushWord(atoms, currentWord);
                        StyledChar sc = makeStyledChar(cp, globalCharIdx, span.style(), gradientColors);
                        atoms.add(new WordAtom(List.of(sc), measureStyledCharsWidth(List.of(sc))));
                    } else {
                        currentWord.add(makeStyledChar(cp, globalCharIdx, span.style(), gradientColors));
                    }
                    globalCharIdx += count;
                    i += count;
                }
                spanIdx++;
            }

            flushWord(atoms, currentWord);
            return atoms;
        }

        private float[][] resolvePerCharGradientColors(List<TextTagParser.TextSpan> spans, int totalChars) {
            float[][] result = new float[totalChars][];
            int cursor = 0;
            int idx = 0;
            while (idx < spans.size()) {
                TextTagParser.TextSpan s = spans.get(idx);
                String gStart = s.style().gradientStartHex();
                String gEnd = s.style().gradientEndHex();
                if (gStart == null || gEnd == null) {
                    cursor += s.text().length();
                    idx++;
                    continue;
                }
                int runStart = cursor;
                int runLen = 0;
                while (idx < spans.size()
                        && gStart.equals(spans.get(idx).style().gradientStartHex())
                        && gEnd.equals(spans.get(idx).style().gradientEndHex())) {
                    runLen += spans.get(idx).text().length();
                    idx++;
                }
                float[] c0 = SceneNode.parseHexRgba(gStart);
                float[] c1 = SceneNode.parseHexRgba(gEnd);
                for (int k = 0; k < runLen && (runStart + k) < totalChars; k++) {
                    float t = runLen > 1 ? k / (float) (runLen - 1) : 0.0f;
                    result[runStart + k] = new float[]{
                            c0[0] + (c1[0] - c0[0]) * t,
                            c0[1] + (c1[1] - c0[1]) * t,
                            c0[2] + (c1[2] - c0[2]) * t,
                            c0[3] + (c1[3] - c0[3]) * t
                    };
                }
                cursor += runLen;
            }
            return result;
        }

        private StyledChar makeStyledChar(
                int codepoint,
                int charIndex,
                TextTagParser.TextStyle style,
                float[][] gradientColors
        ) {
            float[] grad = (charIndex >= 0 && charIndex < gradientColors.length) ? gradientColors[charIndex] : null;
            float[] rgba;
            boolean hasGrad = grad != null;
            if (hasGrad) {
                rgba = new float[]{grad[0], grad[1], grad[2], grad[3] * style.alpha()};
            } else if (style.colorHex() != null) {
                float[] parsed = SceneNode.parseHexRgba(style.colorHex());
                rgba = new float[]{parsed[0], parsed[1], parsed[2], parsed[3] * style.alpha()};
            } else {
                rgba = new float[]{
                        baseColorRgba[0],
                        baseColorRgba[1],
                        baseColorRgba[2],
                        baseColorRgba[3] * style.alpha()
                };
            }
            return new StyledChar(codepoint, charIndex, style, rgba, hasGrad);
        }

        private void flushWord(List<LayoutAtom> atoms, List<StyledChar> currentWord) {
            if (currentWord.isEmpty()) {
                return;
            }
            List<StyledChar> copy = List.copyOf(currentWord);
            currentWord.clear();
            atoms.add(new WordAtom(copy, measureStyledCharsWidth(copy)));
        }

        private float measureStyledCharsWidth(List<StyledChar> chars) {
            float w = 0.0f;
            int prevCp = -1;
            FontAtlas prevAtlas = null;
            for (StyledChar sc : chars) {
                ResolvedGlyph rg = resolveGlyph(sc);
                if (prevCp != -1 && prevAtlas == rg.atlas()) {
                    w += rg.atlas().getKerning(prevCp, rg.renderCp(), rg.effectiveSizePx());
                }
                if (prevCp != -1) {
                    w += sc.style().kerning() + fontManager.getAccessibilityExtraKerning();
                }
                w += rg.advance();
                prevCp = rg.renderCp();
                prevAtlas = rg.atlas();
            }
            return w;
        }

        private record ResolvedGlyph(
                FontAtlas atlas,
                FontAtlas.GlyphMetrics metrics,
                int renderCp,
                float effectiveSizePx,
                float scale,
                float advance,
                boolean smallCapsApplied
        ) {
        }

        private ResolvedGlyph resolveGlyph(StyledChar sc) {
            TextTagParser.TextStyle style = sc.style();
            float fullSize = fontManager.scaleFontSize(style.resolveFontSize(baseFontSize));
            boolean isLowerSc = style.smallCaps() && Character.isLowerCase(sc.codepoint());
            int renderCp = isLowerSc ? Character.toUpperCase(sc.codepoint()) : sc.codepoint();
            float effSize = isLowerSc ? Math.max(6.0f, fullSize * SMALL_CAPS_SCALE) : fullSize;
            FontAtlas atlas = fontManager.getAtlas(style.fontPath(), effSize, uploadGl);
            FontAtlas.GlyphMetrics metrics = atlas.getGlyph(renderCp);
            if (uploadGl && atlas.getGlTextureId() != 0) {
                atlas.ensureCodepoints(Character.toString(renderCp));
            }
            float scale = effSize / atlas.getBaseSize();
            float boldExtra = style.bold() ? Math.max(0.75f, effSize * BOLD_OFFSET_FACTOR) : 0.0f;
            float adv = metrics.advanceWidth() * scale + boldExtra;
            return new ResolvedGlyph(atlas, metrics, renderCp, effSize, scale, adv, isLowerSc);
        }

        private List<List<LayoutAtom>> wrapAtoms(List<LayoutAtom> atoms) {
            List<List<LayoutAtom>> wrapped = new ArrayList<>();
            List<LayoutAtom> currentLine = new ArrayList<>();
            float currentLineWidth = 0.0f;

            for (LayoutAtom atom : atoms) {
                if (atom instanceof NewlineAtom) {
                    currentLine.add(atom);
                    wrapped.add(currentLine);
                    currentLine = new ArrayList<>();
                    currentLineWidth = 0.0f;
                    continue;
                }

                float gap = computeInterAtomGap(currentLine, atom);
                if (atom instanceof SpaceAtom) {
                    if (currentLine.isEmpty()) {
                        continue;
                    }
                    if (Float.isFinite(maxWidth) && currentLineWidth + gap + atom.width() > maxWidth) {
                        wrapped.add(currentLine);
                        currentLine = new ArrayList<>();
                        currentLineWidth = 0.0f;
                    } else {
                        currentLine.add(atom);
                        currentLineWidth += gap + atom.width();
                    }
                    continue;
                }

                float atomW = atom.width();
                if (Float.isFinite(maxWidth) && currentLineWidth + gap + atomW > maxWidth) {
                    if (!currentLine.isEmpty()) {
                        trimTrailingSpaces(currentLine);
                        wrapped.add(currentLine);
                        currentLine = new ArrayList<>();
                        currentLineWidth = 0.0f;
                        gap = 0.0f;
                    }

                    if (atom instanceof WordAtom word && word.width() > maxWidth) {
                        List<WordAtom> splitParts = splitOversizedWord(word);
                        for (int p = 0; p < splitParts.size(); p++) {
                            WordAtom part = splitParts.get(p);
                            if (p < splitParts.size() - 1) {
                                List<LayoutAtom> forcedLine = new ArrayList<>();
                                forcedLine.add(part);
                                wrapped.add(forcedLine);
                            } else {
                                currentLine.add(part);
                                currentLineWidth = part.width();
                            }
                        }
                        continue;
                    }
                }

                currentLine.add(atom);
                currentLineWidth += gap + atomW;
            }

            if (!currentLine.isEmpty() || wrapped.isEmpty()) {
                trimTrailingSpaces(currentLine);
                wrapped.add(currentLine);
            }
            return wrapped;
        }

        private float computeInterAtomGap(List<LayoutAtom> currentLine, LayoutAtom nextAtom) {
            if (currentLine.isEmpty()) {
                return 0.0f;
            }
            StyledChar nextFirst = firstStyledChar(nextAtom);
            if (nextFirst == null) {
                return 0.0f;
            }
            return nextFirst.style().kerning() + fontManager.getAccessibilityExtraKerning();
        }

        private static StyledChar firstStyledChar(LayoutAtom atom) {
            if (atom instanceof WordAtom w && !w.chars().isEmpty()) {
                return w.chars().get(0);
            }
            if (atom instanceof SpaceAtom s) {
                return s.ch();
            }
            if (atom instanceof RubyAtom r && !r.baseChars().isEmpty()) {
                return r.baseChars().get(0);
            }
            return null;
        }

        private List<WordAtom> splitOversizedWord(WordAtom word) {
            List<WordAtom> pieces = new ArrayList<>();
            List<StyledChar> buf = new ArrayList<>();
            float bufWidth = 0.0f;
            for (StyledChar sc : word.chars()) {
                float cw = measureStyledCharsWidth(List.of(sc));
                if (!buf.isEmpty() && bufWidth + cw > maxWidth) {
                    pieces.add(new WordAtom(List.copyOf(buf), bufWidth));
                    buf.clear();
                    bufWidth = 0.0f;
                }
                buf.add(sc);
                bufWidth += cw;
            }
            if (!buf.isEmpty()) {
                pieces.add(new WordAtom(List.copyOf(buf), bufWidth));
            }
            return pieces;
        }

        private static void trimTrailingSpaces(List<LayoutAtom> line) {
            while (!line.isEmpty() && line.get(line.size() - 1) instanceof SpaceAtom) {
                line.remove(line.size() - 1);
            }
        }

        private void positionLines(List<List<LayoutAtom>> wrappedLines) {
            float cursorY = 0.0f;
            float spacingMult = lineSpacing * fontManager.getAccessibilityLineSpacingMultiplier();
            float defaultSize = fontManager.scaleFontSize(baseFontSize);
            FontAtlas defaultAtlas = fontManager.getAtlas(null, defaultSize, uploadGl);

            for (int lineIdx = 0; lineIdx < wrappedLines.size(); lineIdx++) {
                List<LayoutAtom> lineAtoms = wrappedLines.get(lineIdx);
                LineMetrics lm = computeLineMetrics(lineAtoms, defaultAtlas, defaultSize);

                float lineBaselineY = cursorY + lm.rubyHeadroom + lm.ascent;
                float bodyHeight = (lm.ascent + lm.descentAbs + lm.lineGap) * spacingMult;
                float lineTotalHeight = lm.rubyHeadroom + bodyHeight;

                float cursorX = 0.0f;
                int prevCp = -1;
                FontAtlas prevAtlas = null;

                int lineStartChar = Integer.MAX_VALUE;
                int lineEndChar = 0;

                // Track open decorations & hyperlinks on this line.
                DecorationTracker underlineTracker = new DecorationTracker(DecorationKind.UNDERLINE, lineIdx);
                DecorationTracker strikeTracker = new DecorationTracker(DecorationKind.STRIKETHROUGH, lineIdx);
                HyperlinkTracker linkTracker = new HyperlinkTracker(lineIdx);

                for (LayoutAtom atom : lineAtoms) {
                    if (atom instanceof NewlineAtom nl) {
                        lineStartChar = Math.min(lineStartChar, nl.charIndex());
                        lineEndChar = Math.max(lineEndChar, nl.charIndex() + 1);
                        continue;
                    }
                    if (atom instanceof SpaceAtom sp) {
                        StyledChar sc = sp.ch();
                        lineStartChar = Math.min(lineStartChar, sc.charIndex());
                        lineEndChar = Math.max(lineEndChar, sc.charIndex() + 1);
                        ResolvedGlyph rg = resolveGlyph(sc);
                        float kern = computeInterGlyphAdvance(prevCp, prevAtlas, rg, sc.style());
                        float startX = cursorX + kern;
                        float endX = startX + rg.advance();
                        updateTrackers(
                                underlineTracker, strikeTracker, linkTracker,
                                sc.style(), sc.resolvedColorRgba(),
                                startX, endX, lineBaselineY, rg.effectiveSizePx(),
                                cursorY + lm.rubyHeadroom, bodyHeight, sc.charIndex()
                        );
                        cursorX = endX;
                        prevCp = rg.renderCp();
                        prevAtlas = rg.atlas();
                        continue;
                    }
                    if (atom instanceof WordAtom word) {
                        for (StyledChar sc : word.chars()) {
                            lineStartChar = Math.min(lineStartChar, sc.charIndex());
                            lineEndChar = Math.max(lineEndChar, sc.charIndex() + 1);
                            ResolvedGlyph rg = resolveGlyph(sc);
                            float kern = computeInterGlyphAdvance(prevCp, prevAtlas, rg, sc.style());
                            float penX = cursorX + kern;
                            GlyphQuad quad = emitGlyphQuad(sc, rg, lineIdx, penX, lineBaselineY);
                            float endX = penX + rg.advance();
                            updateTrackers(
                                    underlineTracker, strikeTracker, linkTracker,
                                    sc.style(), sc.resolvedColorRgba(),
                                    penX, endX, lineBaselineY, rg.effectiveSizePx(),
                                    quad.y(), Math.max(rg.effectiveSizePx(), quad.height()), sc.charIndex()
                            );
                            cursorX = endX;
                            prevCp = rg.renderCp();
                            prevAtlas = rg.atlas();
                        }
                        continue;
                    }
                    if (atom instanceof RubyAtom ruby) {
                        float slotStartX = cursorX + (prevCp != -1
                                ? ruby.baseChars().get(0).style().kerning() + fontManager.getAccessibilityExtraKerning()
                                : 0.0f);
                        float slotEndX = slotStartX + ruby.totalWidth();
                        float slotCenterX = slotStartX + ruby.totalWidth() * 0.5f;
                        float baseOffsetX = Math.max(0.0f, (ruby.totalWidth() - ruby.baseWidth()) * 0.5f);

                        float basePenX = slotStartX + baseOffsetX;
                        int rPrevCp = -1;
                        FontAtlas rPrevAtlas = null;
                        int rStartChar = Integer.MAX_VALUE;
                        int rEndChar = 0;

                        for (StyledChar sc : ruby.baseChars()) {
                            rStartChar = Math.min(rStartChar, sc.charIndex());
                            rEndChar = Math.max(rEndChar, sc.charIndex() + 1);
                            lineStartChar = Math.min(lineStartChar, sc.charIndex());
                            lineEndChar = Math.max(lineEndChar, sc.charIndex() + 1);

                            ResolvedGlyph rg = resolveGlyph(sc);
                            float kern = computeInterGlyphAdvance(rPrevCp, rPrevAtlas, rg, sc.style());
                            float penX = basePenX + kern;
                            GlyphQuad quad = emitGlyphQuad(sc, rg, lineIdx, penX, lineBaselineY);
                            float endX = penX + rg.advance();
                            updateTrackers(
                                    underlineTracker, strikeTracker, linkTracker,
                                    sc.style(), sc.resolvedColorRgba(),
                                    penX, endX, lineBaselineY, rg.effectiveSizePx(),
                                    quad.y(), Math.max(rg.effectiveSizePx(), quad.height()), sc.charIndex()
                            );
                            basePenX = endX;
                            rPrevCp = rg.renderCp();
                            rPrevAtlas = rg.atlas();
                        }

                        // Position ruby reading centered directly above base text.
                        float rubyX = slotCenterX - ruby.rubyWidth() * 0.5f;
                        float rubyDescentAbs = Math.abs(ruby.rubyAtlas().getDescent())
                                * (ruby.rubyFontSize() / ruby.rubyAtlas().getBaseSize());
                        float rubyBaselineY = lineBaselineY - lm.ascent - rubyDescentAbs
                                - Math.max(2.0f, ruby.rubyFontSize() * RUBY_GAP_FACTOR);
                        List<GlyphQuad> rQuads = buildRubyGlyphQuads(
                                ruby, lineIdx, rubyX, rubyBaselineY,
                                ruby.baseChars().get(0), rStartChar
                        );
                        rubyLayouts.add(new RubyLayout(
                                ruby.rubyText(),
                                ruby.rubyAtlas(),
                                lineIdx,
                                rubyX,
                                rubyBaselineY,
                                ruby.rubyWidth(),
                                ruby.rubyAtlas().getLineHeight(ruby.rubyFontSize()),
                                slotCenterX,
                                ruby.rubyFontSize(),
                                rStartChar,
                                rEndChar,
                                rQuads
                        ));

                        cursorX = slotEndX;
                        prevCp = rPrevCp;
                        prevAtlas = rPrevAtlas;
                        continue;
                    }
                    if (atom instanceof ImageAtom img) {
                        float gap = prevCp != -1 ? fontManager.getAccessibilityExtraKerning() : 0.0f;
                        float imgX = cursorX + gap;
                        float imgY = lineBaselineY - img.height() * 0.85f;
                        inlineImages.add(new InlineImageLayout(
                                img.imagePath(),
                                img.charIndex(),
                                lineIdx,
                                imgX,
                                imgY,
                                img.width(),
                                img.height(),
                                img.alpha()
                        ));
                        lineStartChar = Math.min(lineStartChar, img.charIndex());
                        lineEndChar = Math.max(lineEndChar, img.charIndex() + 1);
                        cursorX = imgX + img.width();
                        prevCp = -1;
                        prevAtlas = null;
                    }
                }

                underlineTracker.flush(decorations);
                strikeTracker.flush(decorations);
                linkTracker.flush(hyperlinkHitboxes);

                if (lineStartChar == Integer.MAX_VALUE) {
                    lineStartChar = 0;
                    lineEndChar = 0;
                }
                lines.add(new TextLine(
                        lineIdx, cursorY, lineBaselineY, cursorX, lineTotalHeight, lineStartChar, lineEndChar));
                totalWidth = Math.max(totalWidth, cursorX);
                cursorY += lineTotalHeight;
            }
            totalHeight = cursorY;
        }

        private GlyphQuad emitGlyphQuad(
                StyledChar sc,
                ResolvedGlyph rg,
                int lineIdx,
                float penX,
                float baselineY
        ) {
            FontAtlas.GlyphMetrics gm = rg.metrics();
            float scale = rg.scale();
            float qx = penX + gm.x0() * scale;
            float qy = baselineY + gm.y0() * scale;
            float qw = gm.width() * scale;
            float qh = gm.height() * scale;
            GlyphQuad quad = new GlyphQuad(
                    rg.atlas(),
                    rg.renderCp(),
                    sc.charIndex(),
                    lineIdx,
                    qx,
                    qy,
                    qw,
                    qh,
                    rg.advance(),
                    baselineY,
                    rg.effectiveSizePx(),
                    new Rect2f(gm.u0(), gm.v0(), gm.u1() - gm.u0(), gm.v1() - gm.v0()),
                    sc.resolvedColorRgba(),
                    sc.style().bold(),
                    sc.style().italic(),
                    rg.smallCapsApplied(),
                    sc.style().wave(),
                    sc.style().jitter(),
                    sc.hasGradient()
            );
            glyphQuads.add(quad);
            return quad;
        }

        private List<GlyphQuad> buildRubyGlyphQuads(
                RubyAtom ruby,
                int lineIdx,
                float startX,
                float baselineY,
                StyledChar baseAnchor,
                int rubyCharIndex
        ) {
            List<GlyphQuad> quads = new ArrayList<>();
            FontAtlas atlas = ruby.rubyAtlas();
            float scale = ruby.rubyFontSize() / atlas.getBaseSize();
            float extraK = baseAnchor.style().kerning() + fontManager.getAccessibilityExtraKerning();
            float penX = startX;
            int prevCp = -1;
            String text = ruby.rubyText();

            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                if (prevCp != -1) {
                    penX += atlas.getKerning(prevCp, cp, ruby.rubyFontSize()) + extraK;
                }
                FontAtlas.GlyphMetrics gm = atlas.getGlyph(cp);
                float qx = penX + gm.x0() * scale;
                float qy = baselineY + gm.y0() * scale;
                float qw = gm.width() * scale;
                float qh = gm.height() * scale;
                float adv = gm.advanceWidth() * scale;
                quads.add(new GlyphQuad(
                        atlas,
                        cp,
                        rubyCharIndex,
                        lineIdx,
                        qx,
                        qy,
                        qw,
                        qh,
                        adv,
                        baselineY,
                        ruby.rubyFontSize(),
                        new Rect2f(gm.u0(), gm.v0(), gm.u1() - gm.u0(), gm.v1() - gm.v0()),
                        baseAnchor.resolvedColorRgba(),
                        false,
                        false,
                        false,
                        baseAnchor.style().wave(),
                        baseAnchor.style().jitter(),
                        baseAnchor.hasGradient()
                ));
                penX += adv;
                prevCp = cp;
            }
            return quads;
        }

        private float computeInterGlyphAdvance(
                int prevCp,
                FontAtlas prevAtlas,
                ResolvedGlyph current,
                TextTagParser.TextStyle style
        ) {
            if (prevCp == -1) {
                return 0.0f;
            }
            float kern = (prevAtlas == current.atlas())
                    ? current.atlas().getKerning(prevCp, current.renderCp(), current.effectiveSizePx())
                    : 0.0f;
            return kern + style.kerning() + fontManager.getAccessibilityExtraKerning();
        }

        private void updateTrackers(
                DecorationTracker underline,
                DecorationTracker strike,
                HyperlinkTracker link,
                TextTagParser.TextStyle style,
                float[] rgba,
                float startX,
                float endX,
                float baselineY,
                float effSizePx,
                float boxTopY,
                float boxHeight,
                int charIndex
        ) {
            float thickness = Math.max(1.0f, effSizePx * 0.07f);
            if (style.underline()) {
                float uY = baselineY + Math.max(1.5f, effSizePx * 0.10f);
                underline.extend(decorations, startX, endX, uY, thickness, rgba, charIndex);
            } else {
                underline.flush(decorations);
            }

            if (style.strikethrough()) {
                float sY = baselineY - effSizePx * 0.30f;
                strike.extend(decorations, startX, endX, sY, thickness, rgba, charIndex);
            } else {
                strike.flush(decorations);
            }

            if (style.hyperlinkTarget() != null) {
                link.extend(hyperlinkHitboxes, style.hyperlinkTarget(), startX, boxTopY, endX - startX, boxHeight);
            } else {
                link.flush(hyperlinkHitboxes);
            }
        }

        private record LineMetrics(float ascent, float descentAbs, float lineGap, float rubyHeadroom) {
        }

        private LineMetrics computeLineMetrics(
                List<LayoutAtom> lineAtoms,
                FontAtlas defaultAtlas,
                float defaultSize
        ) {
            float maxAscent = defaultAtlas.getAscent(defaultSize);
            float maxDescentAbs = Math.abs(defaultAtlas.getDescent()) * (defaultSize / defaultAtlas.getBaseSize());
            float maxLineGap = defaultAtlas.getLineGap() * (defaultSize / defaultAtlas.getBaseSize());
            float maxRuby = 0.0f;

            boolean hasStyledMetrics = false;
            for (LayoutAtom atom : lineAtoms) {
                if (atom instanceof WordAtom word) {
                    for (StyledChar sc : word.chars()) {
                        ResolvedGlyph rg = resolveGlyph(sc);
                        float scale = rg.effectiveSizePx() / rg.atlas().getBaseSize();
                        if (!hasStyledMetrics) {
                            maxAscent = 0.0f;
                            maxDescentAbs = 0.0f;
                            maxLineGap = 0.0f;
                            hasStyledMetrics = true;
                        }
                        maxAscent = Math.max(maxAscent, rg.atlas().getAscent() * scale);
                        maxDescentAbs = Math.max(maxDescentAbs, Math.abs(rg.atlas().getDescent()) * scale);
                        maxLineGap = Math.max(maxLineGap, rg.atlas().getLineGap() * scale);
                    }
                } else if (atom instanceof RubyAtom ruby) {
                    for (StyledChar sc : ruby.baseChars()) {
                        ResolvedGlyph rg = resolveGlyph(sc);
                        float scale = rg.effectiveSizePx() / rg.atlas().getBaseSize();
                        if (!hasStyledMetrics) {
                            maxAscent = 0.0f;
                            maxDescentAbs = 0.0f;
                            maxLineGap = 0.0f;
                            hasStyledMetrics = true;
                        }
                        maxAscent = Math.max(maxAscent, rg.atlas().getAscent() * scale);
                        maxDescentAbs = Math.max(maxDescentAbs, Math.abs(rg.atlas().getDescent()) * scale);
                        maxLineGap = Math.max(maxLineGap, rg.atlas().getLineGap() * scale);
                    }
                    float rubyH = ruby.rubyAtlas().getLineHeight(ruby.rubyFontSize())
                            + Math.max(2.0f, ruby.rubyFontSize() * RUBY_GAP_FACTOR);
                    maxRuby = Math.max(maxRuby, rubyH);
                } else if (atom instanceof ImageAtom img) {
                    if (!hasStyledMetrics) {
                        maxAscent = Math.max(maxAscent, img.height() * 0.85f);
                        maxDescentAbs = Math.max(maxDescentAbs, img.height() * 0.15f);
                        hasStyledMetrics = true;
                    } else {
                        maxAscent = Math.max(maxAscent, img.height() * 0.85f);
                        maxDescentAbs = Math.max(maxDescentAbs, img.height() * 0.15f);
                    }
                }
            }
            return new LineMetrics(maxAscent, maxDescentAbs, maxLineGap, maxRuby);
        }

        private static boolean isCjkBreakable(int cp) {
            return (cp >= 0x3040 && cp <= 0x30FF)
                    || (cp >= 0x4E00 && cp <= 0x9FFF)
                    || (cp >= 0xAC00 && cp <= 0xD7AF);
        }
    }

    private static final class DecorationTracker {
        private final DecorationKind kind;
        private final int lineIndex;
        private boolean active = false;
        private float startX;
        private float endX;
        private float y;
        private float thickness;
        private float[] colorRgba;
        private int startChar;
        private int endChar;

        DecorationTracker(DecorationKind kind, int lineIndex) {
            this.kind = kind;
            this.lineIndex = lineIndex;
        }

        void extend(List<LineDecoration> out, float x0, float x1, float lineY, float thick, float[] rgba, int charIdx) {
            if (active && (!sameColor(colorRgba, rgba) || Math.abs(y - lineY) > 1.0f)) {
                flush(out);
            }
            if (!active) {
                active = true;
                startX = x0;
                endX = x1;
                y = lineY;
                thickness = thick;
                colorRgba = rgba.clone();
                startChar = charIdx;
                endChar = charIdx + 1;
            } else {
                endX = x1;
                thickness = Math.max(thickness, thick);
                endChar = charIdx + 1;
            }
        }

        private static boolean sameColor(float[] a, float[] b) {
            return Math.abs(a[0] - b[0]) < 1e-3f
                    && Math.abs(a[1] - b[1]) < 1e-3f
                    && Math.abs(a[2] - b[2]) < 1e-3f
                    && Math.abs(a[3] - b[3]) < 1e-3f;
        }

        void flush(List<LineDecoration> out) {
            if (active && endX > startX) {
                out.add(new LineDecoration(
                        kind, lineIndex, startX, y, endX - startX, thickness, colorRgba, startChar, endChar
                ));
            }
            active = false;
        }
    }

    private static final class HyperlinkTracker {
        private final int lineIndex;
        private String target;
        private float minX;
        private float minY;
        private float maxX;
        private float maxY;

        HyperlinkTracker(int lineIndex) {
            this.lineIndex = lineIndex;
        }

        void extend(List<HyperlinkHitbox> out, String nextTarget, float x, float y, float w, float h) {
            if (target != null && !target.equals(nextTarget)) {
                flush(out);
            }
            if (target == null) {
                target = nextTarget;
                minX = x;
                minY = y;
                maxX = x + w;
                maxY = y + h;
            } else {
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x + w);
                maxY = Math.max(maxY, y + h);
            }
        }

        void flush(List<HyperlinkHitbox> out) {
            if (target != null && maxX > minX && maxY > minY) {
                out.add(new HyperlinkHitbox(new Rect2f(minX, minY, maxX - minX, maxY - minY), target, lineIndex));
            }
            target = null;
        }
    }
}
