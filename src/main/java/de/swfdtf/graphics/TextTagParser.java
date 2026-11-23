package de.swfdtf.graphics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Parser for KAGAYAKI Visual Novel rich text strings supporting {@code [variable]} interpolation,
 * escaped {@code [[} / {@code {{} sequences, inline formatting/metric tags, flow-control cues,
 * Furigana ({@code {rb}/{rt}}), inline images ({@code {image=...}}), hyperlinks ({@code {a=...}}),
 * and real-time text shader tags ({@code {wave}}, {@code {jitter}}/{@code {shake}}, {@code {gradient=...}}).
 */
public final class TextTagParser {

    /**
     * Unicode Object Replacement Character inserted into {@link ParsedRichText#plainText()} at the
     * character position of each {@code {image=...}} inline image span.
     */
    public static final char OBJECT_REPLACEMENT_CHAR = '\uFFFC';

    private TextTagParser() {
    }

    /**
     * Flow-control cue categories parsed from inline text tags.
     */
    public enum CueKind {
        /** {@code {w}} — wait indefinitely for player click/advance. */
        WAIT_CLICK,
        /** {@code {w=1.5}} — pause typing for a timed duration in seconds. */
        WAIT_TIMED,
        /** {@code {p}} or {@code {p=0.5}} — paragraph newline and click/timed pause. */
        PARAGRAPH,
        /** {@code {nw}} — advance dialogue automatically without waiting for player click. */
        NO_WAIT,
        /** {@code {fast}} — reveal all characters prior to this tag instantaneously. */
        FAST
    }

    /**
     * Immutable active style state attached to a {@link TextSpan}.
     *
     * @param bold             whether bold weight/embolden rendering is active
     * @param italic           whether italic slant rendering is active
     * @param underline        whether underline decoration is active
     * @param strikethrough    whether strikethrough decoration is active
     * @param smallCaps        whether lowercase letters are rendered as small capitals
     * @param colorHex         optional hex color override (e.g. {@code "#RRGGBB"}), or {@code null} for default color
     * @param alpha            opacity multiplier in {@code [0, 1]} (default {@code 1.0f})
     * @param fontSize         absolute pixel font size override ({@code <= 0} inherits base size)
     * @param sizeDelta        relative pixel size offset applied after {@code fontSize}/base size
     * @param fontPath         optional font asset path override, or {@code null} for default font
     * @param kerning          extra per-character horizontal spacing in pixels
     * @param cps              local typewriter speed in chars/sec ({@code < 0} uses controller default, {@code 0} = instant)
     * @param wave             whether sine-wave vertical animation is active
     * @param jitter           whether jitter/shake animation is active
     * @param gradientStartHex optional left/start gradient hex color, or {@code null}
     * @param gradientEndHex   optional right/end gradient hex color, or {@code null}
     * @param hyperlinkTarget  optional hyperlink target action string from {@code {a=...}}, or {@code null}
     */
    public record TextStyle(
            boolean bold,
            boolean italic,
            boolean underline,
            boolean strikethrough,
            boolean smallCaps,
            String colorHex,
            float alpha,
            float fontSize,
            float sizeDelta,
            String fontPath,
            float kerning,
            float cps,
            boolean wave,
            boolean jitter,
            String gradientStartHex,
            String gradientEndHex,
            String hyperlinkTarget
    ) {
        /** Default neutral text style with no active tags. */
        public static final TextStyle DEFAULT = new TextStyle(
                false,
                false,
                false,
                false,
                false,
                null,
                1.0f,
                0.0f,
                0.0f,
                null,
                0.0f,
                -1.0f,
                false,
                false,
                null,
                null,
                null
        );

        /**
         * Resolves the effective pixel font size given the layout's base font size.
         *
         * @param baseFontSize base font size in pixels
         * @return effective pixel font size clamped to at least {@code 1.0f}
         */
        public float resolveFontSize(float baseFontSize) {
            float anchorSize = fontSize > 0.0f ? fontSize : baseFontSize;
            return Math.max(1.0f, anchorSize + sizeDelta);
        }
    }

    /**
     * Immutable flow-control cue positioned at a character index in {@link ParsedRichText#plainText()}.
     *
     * @param index   0-based character index in {@code plainText} where the cue triggers
     * @param kind    cue type
     * @param seconds timed wait duration in seconds (for {@link CueKind#WAIT_TIMED} or timed {@link CueKind#PARAGRAPH}),
     *                or {@code 0.0} for untimed/click cues
     */
    public record TextCue(int index, CueKind kind, double seconds) {
        public TextCue {
            Objects.requireNonNull(kind, "kind must not be null");
            if (index < 0) {
                throw new IllegalArgumentException("index must be >= 0, got " + index);
            }
            if (seconds < 0.0 || !Double.isFinite(seconds)) {
                throw new IllegalArgumentException("seconds must be finite and >= 0, got " + seconds);
            }
        }
    }

    /**
     * Immutable Furigana/Ruby annotation pair spanning a base text range in {@link ParsedRichText#plainText()}.
     *
     * @param startIndex inclusive start character index in {@code plainText}
     * @param endIndex   exclusive end character index in {@code plainText}
     * @param baseText   base characters (e.g. Kanji)
     * @param rubyText   phonetic/ruby reading displayed above the base text
     */
    public record RubyPair(int startIndex, int endIndex, String baseText, String rubyText) {
        public RubyPair {
            Objects.requireNonNull(baseText, "baseText must not be null");
            Objects.requireNonNull(rubyText, "rubyText must not be null");
        }
    }

    /**
     * Immutable inline image descriptor positioned at a character slot in {@link ParsedRichText#plainText()}.
     *
     * @param charIndex character index of the {@link #OBJECT_REPLACEMENT_CHAR} placeholder in {@code plainText}
     * @param imagePath image asset path
     * @param width     optional explicit display width in pixels ({@code <= 0} auto-scales to line height)
     * @param height    optional explicit display height in pixels ({@code <= 0} auto-scales to line height)
     */
    public record InlineImageSpan(int charIndex, String imagePath, float width, float height) {
        public InlineImageSpan {
            Objects.requireNonNull(imagePath, "imagePath must not be null");
        }
    }

    /**
     * Immutable styled run within a {@link ParsedRichText}.
     *
     * @param text            plain character text for this run (or {@code "\uFFFC"} for an inline image)
     * @param style           active {@link TextStyle} for this run
     * @param rubyReading     optional Furigana reading attached via {@code {rb}...{/rb}{rt}...{/rt}}, or {@code null}
     * @param inlineImagePath optional image asset path attached via {@code {image=...}}, or {@code null}
     */
    public record TextSpan(
            String text,
            TextStyle style,
            String rubyReading,
            String inlineImagePath
    ) {
        public TextSpan {
            Objects.requireNonNull(text, "text must not be null");
            Objects.requireNonNull(style, "style must not be null");
        }

        /**
         * Returns a copy of this span with the supplied Furigana reading attached.
         *
         * @param reading ruby reading string
         * @return updated {@link TextSpan}
         */
        public TextSpan withRubyReading(String reading) {
            return new TextSpan(text, style, reading, inlineImagePath);
        }
    }

    /**
     * Immutable result of parsing a VN rich text string.
     *
     * @param plainText          rendered character stream (with {@code \n} for {@code {p}} and {@code \uFFFC} for inline images)
     * @param spans              ordered immutable list of {@link TextSpan} runs
     * @param cues               ordered immutable list of {@link TextCue} flow-control events
     * @param fastPrefixEndIndex exclusive end character index for {@code {fast}} instant reveal ({@code 0} if absent)
     * @param hasNoWait          {@code true} if {@code {nw}} is present anywhere in the text
     */
    public record ParsedRichText(
            String plainText,
            List<TextSpan> spans,
            List<TextCue> cues,
            int fastPrefixEndIndex,
            boolean hasNoWait
    ) {
        public ParsedRichText {
            Objects.requireNonNull(plainText, "plainText must not be null");
            spans = List.copyOf(Objects.requireNonNull(spans, "spans must not be null"));
            cues = List.copyOf(Objects.requireNonNull(cues, "cues must not be null"));
        }

        /**
         * Derives contiguous {@link RubyPair} ranges from the parsed {@link #spans()}.
         *
         * @return immutable list of {@link RubyPair} entries in character order
         */
        public List<RubyPair> rubyPairs() {
            List<RubyPair> pairs = new ArrayList<>();
            int cursor = 0;
            int activeStart = -1;
            String activeRuby = null;
            StringBuilder activeBase = new StringBuilder();

            for (TextSpan span : spans) {
                int len = span.text().length();
                String ruby = span.rubyReading();
                if (ruby != null && !ruby.isEmpty()) {
                    if (activeRuby == null) {
                        activeStart = cursor;
                        activeRuby = ruby;
                        activeBase.setLength(0);
                        activeBase.append(span.text());
                    } else if (activeRuby.equals(ruby)) {
                        activeBase.append(span.text());
                    } else {
                        pairs.add(new RubyPair(activeStart, cursor, activeBase.toString(), activeRuby));
                        activeStart = cursor;
                        activeRuby = ruby;
                        activeBase.setLength(0);
                        activeBase.append(span.text());
                    }
                } else if (activeRuby != null) {
                    pairs.add(new RubyPair(activeStart, cursor, activeBase.toString(), activeRuby));
                    activeRuby = null;
                    activeBase.setLength(0);
                }
                cursor += len;
            }
            if (activeRuby != null) {
                pairs.add(new RubyPair(activeStart, cursor, activeBase.toString(), activeRuby));
            }
            return List.copyOf(pairs);
        }

        /**
         * Derives all {@link InlineImageSpan} descriptors from the parsed {@link #spans()}.
         *
         * @return immutable list of {@link InlineImageSpan} entries in character order
         */
        public List<InlineImageSpan> inlineImages() {
            List<InlineImageSpan> images = new ArrayList<>();
            int cursor = 0;
            for (TextSpan span : spans) {
                if (span.inlineImagePath() != null) {
                    images.add(new InlineImageSpan(cursor, span.inlineImagePath(), 0.0f, 0.0f));
                }
                cursor += span.text().length();
            }
            return List.copyOf(images);
        }

        /**
         * Returns the plain text stripped of {@link #OBJECT_REPLACEMENT_CHAR} placeholders (useful for TTS / logs).
         *
         * @return human-readable plain text without image placeholders
         */
        public String spokenText() {
            if (plainText.indexOf(OBJECT_REPLACEMENT_CHAR) < 0) {
                return plainText;
            }
            StringBuilder sb = new StringBuilder(plainText.length());
            for (int i = 0; i < plainText.length(); i++) {
                char c = plainText.charAt(i);
                if (c != OBJECT_REPLACEMENT_CHAR) {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }

    /**
     * Expands {@code [variable]} placeholders in {@code raw} using {@code lookup}, while unescaping
     * {@code [[} to literal {@code [}. Escaped {@code {{} sequences are preserved intact for tag parsing.
     *
     * <p>If {@code lookup} is {@code null} or returns {@code null} for a key, the placeholder
     * {@code [key]} is preserved verbatim.</p>
     *
     * @param raw    raw VN string (if {@code null}, returns {@code ""})
     * @param lookup variable lookup function (may be {@code null})
     * @return interpolated string
     */
    public static String interpolateVariables(String raw, Function<String, Object> lookup) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        int len = raw.length();
        int i = 0;
        while (i < len) {
            char c = raw.charAt(i);
            if (c == '[') {
                if (i + 1 < len && raw.charAt(i + 1) == '[') {
                    out.append('[');
                    i += 2;
                    continue;
                }
                int closeIdx = raw.indexOf(']', i + 1);
                if (closeIdx < 0) {
                    out.append('[');
                    i++;
                    continue;
                }
                int nestedOpen = raw.indexOf('[', i + 1);
                if (nestedOpen >= 0 && nestedOpen < closeIdx) {
                    out.append('[');
                    i++;
                    continue;
                }
                String key = raw.substring(i + 1, closeIdx).trim();
                if (!key.isEmpty() && lookup != null) {
                    Object value = lookup.apply(key);
                    if (value != null) {
                        out.append(value);
                        i = closeIdx + 1;
                        continue;
                    }
                }
                out.append(raw, i, closeIdx + 1);
                i = closeIdx + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Parses a VN rich text string without variable interpolation (escaped {@code [[} and {@code {{}
     * are still unescaped).
     *
     * @param raw raw VN string (if {@code null}, returns an empty {@link ParsedRichText})
     * @return parsed rich text representation
     */
    public static ParsedRichText parse(String raw) {
        return parse(raw, null);
    }

    /**
     * Interpolates {@code [variable]} placeholders via {@code lookup} and parses all inline VN tags.
     *
     * @param raw    raw VN string (if {@code null}, returns an empty {@link ParsedRichText})
     * @param lookup optional variable lookup callback (may be {@code null})
     * @return parsed rich text representation
     */
    public static ParsedRichText parse(String raw, Function<String, Object> lookup) {
        if (raw == null || raw.isEmpty()) {
            return new ParsedRichText("", List.of(), List.of(), 0, false);
        }

        String expanded = interpolateVariables(raw, lookup);
        ParserState state = new ParserState();
        int len = expanded.length();
        int i = 0;

        while (i < len) {
            char c = expanded.charAt(i);
            if (c == '{') {
                if (i + 1 < len && expanded.charAt(i + 1) == '{') {
                    state.appendChar('{');
                    i += 2;
                    continue;
                }
                int closeIdx = findClosingBrace(expanded, i + 1);
                if (closeIdx > i + 1) {
                    String tagBody = expanded.substring(i + 1, closeIdx).trim();
                    if (state.tryHandleTag(tagBody)) {
                        i = closeIdx + 1;
                        continue;
                    }
                }
                state.appendChar('{');
                i++;
            } else {
                state.appendChar(c);
                i++;
            }
        }

        state.flushSpan();
        return new ParsedRichText(
                state.plainText.toString(),
                state.spans,
                state.cues,
                state.fastPrefixEndIndex,
                state.hasNoWait
        );
    }

    private static int findClosingBrace(String s, int fromIndex) {
        for (int i = fromIndex; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '}') {
                return i;
            }
            if (ch == '{') {
                return -1;
            }
        }
        return -1;
    }

    private record SizeEntry(float fontSize, float sizeDelta) {
    }

    private record GradientEntry(String startHex, String endHex) {
    }

    private static final class ParserState {
        private final StringBuilder plainText = new StringBuilder();
        private final StringBuilder currentRun = new StringBuilder();
        private final List<TextSpan> spans = new ArrayList<>();
        private final List<TextCue> cues = new ArrayList<>();

        private int boldDepth = 0;
        private int italicDepth = 0;
        private int underlineDepth = 0;
        private int strikethroughDepth = 0;
        private int smallCapsDepth = 0;
        private int waveDepth = 0;
        private int jitterDepth = 0;

        private final Deque<String> colorStack = new ArrayDeque<>();
        private final Deque<Float> alphaStack = new ArrayDeque<>();
        private final Deque<SizeEntry> sizeStack = new ArrayDeque<>();
        private final Deque<String> fontStack = new ArrayDeque<>();
        private final Deque<Float> kerningStack = new ArrayDeque<>();
        private final Deque<Float> cpsStack = new ArrayDeque<>();
        private final Deque<GradientEntry> gradientStack = new ArrayDeque<>();
        private final Deque<String> hyperlinkStack = new ArrayDeque<>();

        private int fastPrefixEndIndex = 0;
        private boolean hasNoWait = false;

        private boolean inRubyBase = false;
        private int rubyBaseSpanStart = -1;
        private int rubyBaseSpanEnd = -1;

        private boolean inRubyReading = false;
        private final StringBuilder rubyReadingBuffer = new StringBuilder();

        void appendChar(char c) {
            if (inRubyReading) {
                rubyReadingBuffer.append(c);
                return;
            }
            currentRun.append(c);
            plainText.append(c);
        }

        void flushSpan() {
            if (currentRun.isEmpty()) {
                return;
            }
            spans.add(new TextSpan(currentRun.toString(), currentStyle(), null, null));
            currentRun.setLength(0);
        }

        TextStyle currentStyle() {
            SizeEntry size = sizeStack.isEmpty() ? new SizeEntry(0.0f, 0.0f) : sizeStack.peek();
            GradientEntry grad = gradientStack.peek();
            return new TextStyle(
                    boldDepth > 0,
                    italicDepth > 0,
                    underlineDepth > 0,
                    strikethroughDepth > 0,
                    smallCapsDepth > 0,
                    colorStack.peek(),
                    alphaStack.isEmpty() ? 1.0f : alphaStack.peek(),
                    size.fontSize(),
                    size.sizeDelta(),
                    fontStack.peek(),
                    kerningStack.isEmpty() ? 0.0f : kerningStack.peek(),
                    cpsStack.isEmpty() ? -1.0f : cpsStack.peek(),
                    waveDepth > 0,
                    jitterDepth > 0,
                    grad != null ? grad.startHex() : null,
                    grad != null ? grad.endHex() : null,
                    hyperlinkStack.peek()
            );
        }

        boolean tryHandleTag(String tagBody) {
            if (tagBody.isEmpty()) {
                return false;
            }
            int eqIdx = tagBody.indexOf('=');
            String name = (eqIdx >= 0 ? tagBody.substring(0, eqIdx) : tagBody)
                    .trim()
                    .toLowerCase(Locale.ROOT);
            String arg = eqIdx >= 0 ? tagBody.substring(eqIdx + 1).trim() : null;

            return switch (name) {
                case "b" -> arg == null && pushFlag(() -> boldDepth++);
                case "/b" -> arg == null && popFlag(() -> boldDepth = Math.max(0, boldDepth - 1));
                case "i" -> arg == null && pushFlag(() -> italicDepth++);
                case "/i" -> arg == null && popFlag(() -> italicDepth = Math.max(0, italicDepth - 1));
                case "u" -> arg == null && pushFlag(() -> underlineDepth++);
                case "/u" -> arg == null && popFlag(() -> underlineDepth = Math.max(0, underlineDepth - 1));
                case "s" -> arg == null && pushFlag(() -> strikethroughDepth++);
                case "/s" -> arg == null && popFlag(() -> strikethroughDepth = Math.max(0, strikethroughDepth - 1));
                case "sc" -> arg == null && pushFlag(() -> smallCapsDepth++);
                case "/sc" -> arg == null && popFlag(() -> smallCapsDepth = Math.max(0, smallCapsDepth - 1));
                case "plain" -> arg == null && resetAllStyles();

                case "color" -> handleColor(arg);
                case "/color" -> popStack(colorStack, arg);
                case "alpha" -> handleAlpha(arg);
                case "/alpha" -> popStack(alphaStack, arg);
                case "size" -> handleSize(arg);
                case "/size" -> popStack(sizeStack, arg);
                case "font" -> handleNonBlankPush(fontStack, arg);
                case "/font" -> popStack(fontStack, arg);
                case "k" -> handleKerning(arg);
                case "/k" -> popStack(kerningStack, arg);

                case "w" -> handleWait(arg);
                case "p" -> handleParagraph(arg);
                case "nw" -> handleNoWait(arg);
                case "fast" -> handleFast(arg);
                case "cps" -> handleCps(arg);
                case "/cps" -> popStack(cpsStack, arg);

                case "rb" -> handleRubyBaseStart(arg);
                case "/rb" -> handleRubyBaseEnd(arg);
                case "rt" -> handleRubyReadingStart(arg);
                case "/rt" -> handleRubyReadingEnd(arg);
                case "image" -> handleInlineImage(arg);
                case "a" -> handleNonBlankPush(hyperlinkStack, arg);
                case "/a" -> popStack(hyperlinkStack, arg);

                case "wave" -> pushFlag(() -> waveDepth++);
                case "/wave" -> arg == null && popFlag(() -> waveDepth = Math.max(0, waveDepth - 1));
                case "jitter", "shake" -> pushFlag(() -> jitterDepth++);
                case "/jitter", "/shake" -> arg == null && popFlag(() -> jitterDepth = Math.max(0, jitterDepth - 1));
                case "gradient" -> handleGradient(arg);
                case "/gradient" -> popStack(gradientStack, arg);
                default -> false;
            };
        }

        private boolean pushFlag(Runnable action) {
            flushSpan();
            action.run();
            return true;
        }

        private boolean popFlag(Runnable action) {
            flushSpan();
            action.run();
            return true;
        }

        private boolean resetAllStyles() {
            flushSpan();
            boldDepth = 0;
            italicDepth = 0;
            underlineDepth = 0;
            strikethroughDepth = 0;
            smallCapsDepth = 0;
            waveDepth = 0;
            jitterDepth = 0;
            colorStack.clear();
            alphaStack.clear();
            sizeStack.clear();
            fontStack.clear();
            kerningStack.clear();
            cpsStack.clear();
            gradientStack.clear();
            hyperlinkStack.clear();
            return true;
        }

        private boolean handleColor(String arg) {
            if (!isValidHexColor(arg)) {
                return false;
            }
            flushSpan();
            colorStack.push(normalizeHex(arg));
            return true;
        }

        private boolean handleAlpha(String arg) {
            Float value = parseFiniteFloat(arg);
            if (value == null) {
                return false;
            }
            flushSpan();
            float clamped = Math.clamp(value, 0.0f, 1.0f);
            alphaStack.push(clamped);
            return true;
        }

        private boolean handleSize(String arg) {
            if (arg == null || arg.isEmpty()) {
                return false;
            }
            Float value = parseFiniteFloat(arg);
            if (value == null) {
                return false;
            }
            flushSpan();
            SizeEntry current = sizeStack.isEmpty() ? new SizeEntry(0.0f, 0.0f) : sizeStack.peek();
            if (arg.startsWith("+") || arg.startsWith("-")) {
                sizeStack.push(new SizeEntry(current.fontSize(), current.sizeDelta() + value));
            } else {
                if (value <= 0.0f) {
                    return false;
                }
                sizeStack.push(new SizeEntry(value, 0.0f));
            }
            return true;
        }

        private boolean handleKerning(String arg) {
            Float value = parseFiniteFloat(arg);
            if (value == null) {
                return false;
            }
            flushSpan();
            kerningStack.push(value);
            return true;
        }

        private boolean handleNonBlankPush(Deque<String> stack, String arg) {
            if (arg == null || arg.isEmpty()) {
                return false;
            }
            flushSpan();
            stack.push(arg);
            return true;
        }

        private boolean popStack(Deque<?> stack, String arg) {
            if (arg != null) {
                return false;
            }
            flushSpan();
            if (!stack.isEmpty()) {
                stack.pop();
            }
            return true;
        }

        private boolean handleWait(String arg) {
            if (arg == null) {
                cues.add(new TextCue(plainText.length(), CueKind.WAIT_CLICK, 0.0));
                return true;
            }
            Double secs = parseNonNegativeDouble(arg);
            if (secs == null) {
                return false;
            }
            cues.add(new TextCue(plainText.length(), CueKind.WAIT_TIMED, secs));
            return true;
        }

        private boolean handleParagraph(String arg) {
            double secs = 0.0;
            if (arg != null) {
                Double parsed = parseNonNegativeDouble(arg);
                if (parsed == null) {
                    return false;
                }
                secs = parsed;
            }
            cues.add(new TextCue(plainText.length(), CueKind.PARAGRAPH, secs));
            appendChar('\n');
            return true;
        }

        private boolean handleNoWait(String arg) {
            if (arg != null) {
                return false;
            }
            cues.add(new TextCue(plainText.length(), CueKind.NO_WAIT, 0.0));
            hasNoWait = true;
            return true;
        }

        private boolean handleFast(String arg) {
            if (arg != null) {
                return false;
            }
            int idx = plainText.length();
            cues.add(new TextCue(idx, CueKind.FAST, 0.0));
            fastPrefixEndIndex = Math.max(fastPrefixEndIndex, idx);
            return true;
        }

        private boolean handleCps(String arg) {
            Float value = parseFiniteFloat(arg);
            if (value == null || value < 0.0f) {
                return false;
            }
            flushSpan();
            cpsStack.push(value);
            return true;
        }

        private boolean handleRubyBaseStart(String arg) {
            if (arg != null) {
                return false;
            }
            flushSpan();
            inRubyBase = true;
            rubyBaseSpanStart = spans.size();
            rubyBaseSpanEnd = spans.size();
            return true;
        }

        private boolean handleRubyBaseEnd(String arg) {
            if (arg != null) {
                return false;
            }
            flushSpan();
            if (inRubyBase) {
                inRubyBase = false;
                rubyBaseSpanEnd = spans.size();
            }
            return true;
        }

        private boolean handleRubyReadingStart(String arg) {
            if (arg != null) {
                return false;
            }
            flushSpan();
            inRubyReading = true;
            rubyReadingBuffer.setLength(0);
            return true;
        }

        private boolean handleRubyReadingEnd(String arg) {
            if (arg != null) {
                return false;
            }
            inRubyReading = false;
            String reading = rubyReadingBuffer.toString();
            rubyReadingBuffer.setLength(0);
            if (!reading.isEmpty() && rubyBaseSpanStart >= 0 && rubyBaseSpanEnd > rubyBaseSpanStart) {
                for (int idx = rubyBaseSpanStart; idx < rubyBaseSpanEnd && idx < spans.size(); idx++) {
                    spans.set(idx, spans.get(idx).withRubyReading(reading));
                }
            }
            rubyBaseSpanStart = -1;
            rubyBaseSpanEnd = -1;
            return true;
        }

        private boolean handleInlineImage(String arg) {
            if (arg == null || arg.isEmpty()) {
                return false;
            }
            flushSpan();
            String placeholder = String.valueOf(OBJECT_REPLACEMENT_CHAR);
            plainText.append(OBJECT_REPLACEMENT_CHAR);
            spans.add(new TextSpan(placeholder, currentStyle(), null, arg));
            return true;
        }

        private boolean handleGradient(String arg) {
            if (arg == null || arg.isEmpty()) {
                return false;
            }
            String[] parts = arg.split("[,;\\s]+");
            if (parts.length != 2 || !isValidHexColor(parts[0]) || !isValidHexColor(parts[1])) {
                return false;
            }
            flushSpan();
            gradientStack.push(new GradientEntry(normalizeHex(parts[0]), normalizeHex(parts[1])));
            return true;
        }

        private static boolean isValidHexColor(String hex) {
            if (hex == null || hex.isEmpty()) {
                return false;
            }
            try {
                SceneNode.parseHexRgba(hex);
                return true;
            } catch (IllegalArgumentException ex) {
                return false;
            }
        }

        private static String normalizeHex(String hex) {
            String trimmed = hex.trim();
            return trimmed.startsWith("#") ? trimmed : "#" + trimmed;
        }

        private static Float parseFiniteFloat(String s) {
            if (s == null || s.isEmpty()) {
                return null;
            }
            try {
                float val = Float.parseFloat(s);
                return Float.isFinite(val) ? val : null;
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        private static Double parseNonNegativeDouble(String s) {
            if (s == null || s.isEmpty()) {
                return null;
            }
            try {
                double val = Double.parseDouble(s);
                return (Double.isFinite(val) && val >= 0.0) ? val : null;
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }
}
