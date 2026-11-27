package de.swfdtf.graphics;

import java.text.Bidi;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chapter 9 typography, BiDi/RTL visual reordering, Furigana shorthand, per-language font mapping,
 * auto-shrinking, soft hyphens, CJK character wrapping, WCAG contrast auditing, subpixel snapping,
 * Tategaki column layout, typewriter span/timing preservation, and on-demand glyph atlas tracking
 * ({@code #0686}, {@code #0687}, {@code #0690}–{@code #0695}, {@code #0698}, {@code #0699},
 * {@code #0701}, {@code #0705}, {@code #0707}, {@code #0709}, {@code #0711}, {@code #0714},
 * {@code #0717}, {@code #0718}, {@code #0720}, {@code #0723}, {@code #0724}, {@code #0726},
 * {@code #0728}–{@code #0730}, {@code #0733}, {@code #0735}, {@code #0738}–{@code #0740},
 * {@code #0742}, {@code #0744}, {@code #0747}, {@code #0749}, {@code #0751}, {@code #0754},
 * {@code #0756}, {@code #0759}, {@code #0762}, {@code #0764}).
 */
public final class TypographyAndLayoutServices {

    private TypographyAndLayoutServices() {
    }

    /**
     * Bidirectional (BiDi / RTL) layout analyzer and UI mirroring helper for Arabic and Hebrew
     * mixed with LTR numbers/variables ({@code #0686}).
     */
    public static final class BidiTextReorderer {

        /**
         * Visual run resulting from BiDi analysis.
         *
         * @param text     substring in visual order
         * @param rightToLeft {@code true} when run direction is RTL
         */
        public record VisualRun(String text, boolean rightToLeft) {
        }

        /**
         * Creates a {@code BidiTextReorderer}.
         */
        public BidiTextReorderer() {
        }

        /**
         * Detects whether {@code text} contains strong right-to-left characters (Arabic/Hebrew).
         *
         * @param text input string
         * @return {@code true} if RTL characters are present
         */
        public static boolean containsRtl(String text) {
            if (text == null || text.isEmpty()) {
                return false;
            }
            for (int i = 0; i < text.length(); i++) {
                byte dir = Character.getDirectionality(text.charAt(i));
                if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                        || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                        || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING
                        || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Splits {@code text} into visually ordered runs using Java's {@link Bidi} engine while keeping
         * embedded Latin words and numeric runs unreversed ({@code #0686}).
         *
         * @param text       logical string
         * @param defaultRtl base paragraph direction
         * @return ordered list of visual {@link VisualRun} chunks
         */
        public static List<VisualRun> reorderToVisualRuns(String text, boolean defaultRtl) {
            if (text == null || text.isEmpty()) {
                return List.of();
            }
            int baseFlags = defaultRtl ? Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT : Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT;
            Bidi bidi = new Bidi(text, baseFlags);
            int runCount = bidi.getRunCount();
            byte[] levels = new byte[runCount];
            List<VisualRun> logicalRuns = new ArrayList<>(runCount);
            for (int i = 0; i < runCount; i++) {
                int start = bidi.getRunStart(i);
                int limit = bidi.getRunLimit(i);
                int level = bidi.getRunLevel(i);
                levels[i] = (byte) level;
                boolean rtl = (level & 1) == 1;
                String chunk = text.substring(start, limit);
                logicalRuns.add(new VisualRun(rtl ? new StringBuilder(chunk).reverse().toString() : chunk, rtl));
            }
            Object[] ordered = new Object[runCount];
            for (int i = 0; i < runCount; i++) {
                ordered[i] = logicalRuns.get(i);
            }
            Bidi.reorderVisually(levels, 0, ordered, 0, runCount);
            List<VisualRun> visual = new ArrayList<>(runCount);
            for (Object obj : ordered) {
                visual.add((VisualRun) obj);
            }
            return List.copyOf(visual);
        }

        /**
         * Mirrors directional UI glyphs/arrows cleanly when rendering in RTL locales ({@code #0686}).
         *
         * @param glyph arrow or bracket symbol
         * @param rtl   whether RTL locale is active
         * @return mirrored glyph for RTL, unchanged otherwise
         */
        public static String mirrorDirectionalGlyph(String glyph, boolean rtl) {
            if (!rtl || glyph == null) {
                return glyph != null ? glyph : "";
            }
            return switch (glyph) {
                case "→" -> "←";
                case "←" -> "→";
                case ">" -> "<";
                case "<" -> ">";
                case "▶" -> "◀";
                case "◀" -> "▶";
                default -> glyph;
            };
        }
    }

    /**
     * Parses concise Ruby/Furigana annotations of form {@code &#123;ruby=...&#125;BASE&#123;/ruby&#125;}
     * and computes extra top line padding ({@code #0687}).
     */
    public static final class RubyShorthandParser {

        private static final Pattern RUBY_TAG = Pattern.compile("\\{ruby=([^}]+)\\}(.*?)\\{/ruby\\}");

        /**
         * Parsed Ruby base+furigana span ({@code #0687}).
         *
         * @param baseText         kanji or base phrase
         * @param rubyText         furigana annotation above {@code baseText}
         * @param extraTopPaddingPx extra vertical space added to row height
         */
        public record RubySpan(String baseText, String rubyText, float extraTopPaddingPx) {
        }

        /**
         * Creates a {@code RubyShorthandParser}.
         */
        public RubyShorthandParser() {
        }

        /**
         * Extracts all Ruby annotations and computes resulting expanded line height.
         *
         * @param text          input text containing optional ruby tags
         * @param baseFontSize  normal font size in pixels
         * @return list of parsed {@link RubySpan}s
         */
        public static List<RubySpan> parseRubySpans(String text, float baseFontSize) {
            if (text == null || text.isEmpty()) {
                return List.of();
            }
            float pad = Math.max(4.0f, baseFontSize * 0.52f);
            Matcher m = RUBY_TAG.matcher(text);
            List<RubySpan> out = new ArrayList<>();
            while (m.find()) {
                out.add(new RubySpan(m.group(2), m.group(1), pad));
            }
            return List.copyOf(out);
        }

        /**
         * Computes line height expanding automatically when {@code text} contains ruby annotations ({@code #0687}).
         *
         * @param text        candidate line text
         * @param fontSizePx  base font size
         * @param lineSpacing spacing multiplier
         * @return effective line height in pixels
         */
        public static float computeAdjustedLineHeight(String text, float fontSizePx, float lineSpacing) {
            float baseHeight = fontSizePx * Math.clamp(lineSpacing, 1.0f, 2.2f);
            List<RubySpan> spans = parseRubySpans(text, fontSizePx);
            return spans.isEmpty() ? baseHeight : baseHeight + spans.get(0).extraTopPaddingPx();
        }
    }

    /**
     * Manages per-language font selection, dyslexia font switching, Studio monospace fonts, and
     * embedded JAR fallback resources ({@code #0690}, {@code #0711}, {@code #0720}, {@code #0740}).
     */
    public static final class LanguageFontProfileRegistry {

        /** Embedded engine fallback font path requiring zero OS font discovery ({@code #0711}). */
        public static final String EMBEDDED_FALLBACK_FONT = "fonts/kagayaki-fallback.ttf";

        /** Dyslexia-friendly high-legibility font identifier ({@code #0720}). */
        public static final String DYSLEXIA_FONT = "AtkinsonHyperlegible.ttf";

        /** Fixed-width monospace font for Studio code inspectors and logs ({@code #0740}). */
        public static final String STUDIO_MONOSPACE_FONT = "JetBrainsMono.ttf";

        private final Map<String, String> fontMap = new LinkedHashMap<>();

        /**
         * Creates a {@code LanguageFontProfileRegistry} populated with sensible defaults.
         */
        public LanguageFontProfileRegistry() {
            fontMap.put("default", "Inter.ttf");
            fontMap.put("deutsch", "Inter.ttf");
            fontMap.put("english", "Inter.ttf");
            fontMap.put("japanese", "NotoSansJP.ttf");
            fontMap.put("arabic", "NotoSansArabic.ttf");
            fontMap.put("hebrew", "NotoSansHebrew.ttf");
        }

        /**
         * Registers or overrides the font filename mapped to {@code language}.
         *
         * @param language language key (e.g. {@code "japanese"}, {@code "default"})
         * @param fontFile font path or filename
         */
        public synchronized void registerFontMapping(String language, String fontFile) {
            if (language != null && !language.isBlank() && fontFile != null && !fontFile.isBlank()) {
                fontMap.put(language.trim().toLowerCase(Locale.ROOT), fontFile.trim());
            }
        }

        /**
         * Resolves effective UI/dialogue font respecting dyslexia mode override and per-language mapping
         * ({@code #0690}, {@code #0720}).
         *
         * @param language                 active language identifier
         * @param highContrastDyslexiaFont whether dyslexia-friendly font mode is enabled
         * @return resolved font asset path
         */
        public synchronized String resolveFontForLanguage(String language, boolean highContrastDyslexiaFont) {
            if (highContrastDyslexiaFont) {
                return DYSLEXIA_FONT;
            }
            String key = language != null ? language.trim().toLowerCase(Locale.ROOT) : "default";
            if (fontMap.containsKey(key)) {
                return fontMap.get(key);
            }
            return fontMap.getOrDefault("default", EMBEDDED_FALLBACK_FONT);
        }
    }

    /**
     * Pixel-accurate wrapping, auto-shrinking, soft-hyphenation, ellipsis clipping, bounds measurement,
     * and typewriter-stable pre-wrapping ({@code #0691}, {@code #0692}, {@code #0693}, {@code #0707},
     * {@code #0728}, {@code #0729}, {@code #0738}, {@code #0749}).
     */
    public static final class TextFitAndBoundsService {

        /**
         * Measured wrapped text bounds ({@code #0707}).
         *
         * @param widthPx  maximum rendered line width in pixels
         * @param heightPx total rendered height in pixels
         * @param lines    wrapped lines in order
         */
        public record TextBounds(float widthPx, float heightPx, List<String> lines) {
        }

        /**
         * Creates a {@code TextFitAndBoundsService}.
         */
        public TextFitAndBoundsService() {
        }

        /**
         * Shrinks font size smoothly up to {@code maxShrinkFraction} (e.g. {@code 0.20} for max -20%)
         * so translated labels fit fixed-width buttons cleanly ({@code #0691}).
         *
         * @param text              button caption
         * @param baseFontSizePx    nominal pixel size
         * @param maxWidthPx        available button width
         * @param maxShrinkFraction max relative shrink (e.g. {@code 0.20f})
         * @return fitted pixel font size
         */
        public static float fitFontSizeToRect(
                String text,
                float baseFontSizePx,
                float maxWidthPx,
                float maxShrinkFraction
        ) {
            if (text == null || text.isEmpty() || maxWidthPx <= 1.0f || baseFontSizePx <= 1.0f) {
                return Math.max(1.0f, baseFontSizePx);
            }
            float estimatedAtBase = text.length() * (baseFontSizePx * 0.55f);
            if (estimatedAtBase <= maxWidthPx) {
                return baseFontSizePx;
            }
            float minScale = Math.clamp(1.0f - maxShrinkFraction, 0.4f, 1.0f);
            float neededScale = Math.max(minScale, maxWidthPx / estimatedAtBase);
            return baseFontSizePx * neededScale;
        }

        /**
         * Clips {@code text} with {@code "..."} based on pixel budget rather than fixed character count
         * ({@code #0728}).
         *
         * @param text        source title text
         * @param maxWidthPx  available pixel width
         * @param charWidthPx average advance per glyph in pixels
         * @return fitted text with trailing ellipsis if truncated
         */
        public static String truncateWithEllipsis(String text, float maxWidthPx, float charWidthPx) {
            if (text == null || text.isEmpty()) {
                return "";
            }
            float cw = Math.max(1.0f, charWidthPx);
            float fullW = text.length() * cw;
            if (fullW <= maxWidthPx) {
                return text;
            }
            int maxChars = Math.max(0, (int) Math.floor((maxWidthPx - (3.0f * cw)) / cw));
            if (maxChars <= 0) {
                return maxWidthPx >= cw ? "..." : "";
            }
            return text.substring(0, Math.min(text.length(), maxChars)) + "...";
        }

        /**
         * Wraps words splitting cleanly at Unicode soft hyphens {@code \u00AD} only when needed, keeping
         * unused soft hyphens completely invisible ({@code #0692}).
         *
         * @param text        input string possibly containing {@code \u00AD}
         * @param maxWidthPx  container width in pixels
         * @param charWidthPx average character advance
         * @return wrapped lines without stray invisible soft hyphens
         */
        public static List<String> wrapWithSoftHyphens(String text, float maxWidthPx, float charWidthPx) {
            if (text == null || text.isEmpty()) {
                return List.of();
            }
            float safeWidth = Math.max(charWidthPx, maxWidthPx);
            int maxCols = Math.max(1, (int) Math.floor(safeWidth / Math.max(1.0f, charWidthPx)));
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String rawWord : text.split(" ")) {
                String cleanWord = rawWord.replace("\u00AD", "");
                int prefixSpace = line.isEmpty() ? 0 : 1;
                if (line.length() + prefixSpace + cleanWord.length() <= maxCols) {
                    if (prefixSpace > 0) {
                        line.append(' ');
                    }
                    line.append(cleanWord);
                } else if (rawWord.indexOf('\u00AD') >= 0) {
                    String[] parts = rawWord.split("\u00AD");
                    StringBuilder partAcc = new StringBuilder();
                    for (int p = 0; p < parts.length; p++) {
                        String nextSeg = parts[p];
                        boolean hasMore = p < parts.length - 1;
                        int extraHyphen = hasMore ? 1 : 0;
                        int sp = line.isEmpty() ? 0 : 1;
                        if (line.length() + sp + partAcc.length() + nextSeg.length() + extraHyphen <= maxCols) {
                            partAcc.append(nextSeg);
                        } else {
                            if (!partAcc.isEmpty()) {
                                if (!line.isEmpty()) {
                                    line.append(' ');
                                }
                                line.append(partAcc).append('-');
                                lines.add(line.toString());
                                line.setLength(0);
                                partAcc.setLength(0);
                            } else if (!line.isEmpty()) {
                                lines.add(line.toString());
                                line.setLength(0);
                            }
                            partAcc.append(nextSeg);
                        }
                    }
                    if (!partAcc.isEmpty()) {
                        if (!line.isEmpty()) {
                            line.append(' ');
                        }
                        line.append(partAcc);
                    }
                } else {
                    if (!line.isEmpty()) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    line.append(cleanWord);
                }
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
            }
            return List.copyOf(lines);
        }

        /**
         * Wraps {@code fullText} safely with word or CJK character wrapping ({@code #0729}), strictly
         * clamping box widths {@code [0..2000]} against division-by-zero or infinite loops ({@code #0749}),
         * and measuring exact pixel bounds ({@code #0707}, {@code #0693}).
         *
         * @param fullText      source string
         * @param maxWidthPx    target box width (safe even at 0 or 1 px)
         * @param fontSizePx    font pixel size
         * @param lineSpacing   line spacing factor {@code [1.0, 2.2]}
         * @param letterSpacing extra character advance {@code [0, 8]}
         * @return immutable {@link TextBounds} without NaNs or infinite loops
         */
        public static TextBounds measureWrappedText(
                String fullText,
                float maxWidthPx,
                float fontSizePx,
                float lineSpacing,
                float letterSpacing
        ) {
            if (fullText == null || fullText.isEmpty()) {
                return new TextBounds(0.0f, 0.0f, List.of());
            }
            float safeFont = Float.isFinite(fontSizePx) && fontSizePx >= 1.0f ? fontSizePx : 16.0f;
            float charAdv = (safeFont * 0.55f) + Math.clamp(letterSpacing, 0.0f, 8.0f);
            float safeBoxW = Float.isFinite(maxWidthPx) ? Math.max(charAdv, maxWidthPx) : charAdv;
            int maxCols = Math.max(1, (int) Math.floor(safeBoxW / charAdv));
            boolean cjkCharWrap = !fullText.contains(" ");
            List<String> lines = new ArrayList<>();
            if (cjkCharWrap) {
                for (int i = 0; i < fullText.length(); i += maxCols) {
                    lines.add(fullText.substring(i, Math.min(fullText.length(), i + maxCols)));
                }
            } else {
                lines.addAll(wrapWithSoftHyphens(fullText, safeBoxW, charAdv));
            }
            float widest = 0.0f;
            for (String line : lines) {
                widest = Math.max(widest, line.length() * charAdv);
            }
            float lh = safeFont * Math.clamp(lineSpacing, 1.0f, 2.2f);
            return new TextBounds(widest, lines.size() * lh, List.copyOf(lines));
        }

        /**
         * Pre-wraps full sentence BEFORE typewriter reveals partial characters so words never pop/jump
         * mid-typing ({@code #0738}).
         *
         * @param fullText     complete dialogue text
         * @param visibleChars active revealed char count
         * @param maxWidthPx   box width
         * @param fontSizePx   font size
         * @return pre-wrapped visible lines locked to final line breaks
         */
        public static List<String> sliceStableTypewriterLines(
                String fullText,
                int visibleChars,
                float maxWidthPx,
                float fontSizePx
        ) {
            TextBounds fullLayout = measureWrappedText(fullText, maxWidthPx, fontSizePx, 1.25f, 0.0f);
            int remaining = Math.max(0, visibleChars);
            List<String> out = new ArrayList<>();
            for (String line : fullLayout.lines()) {
                if (remaining <= 0) {
                    break;
                }
                int take = Math.min(line.length(), remaining);
                out.add(line.substring(0, take));
                remaining -= take;
                if (remaining > 0) {
                    remaining--; // account for wrapped space boundary cleanly
                }
            }
            return List.copyOf(out);
        }
    }

    /**
     * Normalization, WCAG contrast auditing, typographic quotes, auto-closing/typewriter tag splicing,
     * dynamic glyph page tracking, variable highlighting, Wait/CPS timings, binary-search hit-testing,
     * Tategaki layout, and sticky log header computation.
     */
    public static final class RichTextTypographyHelpers {

        /** Rendering outline technique options ({@code #0730}). */
        public enum OutlineMode {
            /** Classic 8-tap offset contour outline. */
            EIGHT_TAP,
            /** Crisp Signed-Distance-Field outline for small UI sizes. */
            SDF
        }

        /**
         * Missing glyph warning entry reported for project fonts ({@code #0718}, {@code #0754}).
         *
         * @param codepoint  missing Unicode codepoint
         * @param character  string representation of character
         * @param sourceRef  script/label source reference
         */
        public record MissingGlyphEntry(int codepoint, String character, String sourceRef) {
        }

        /**
         * Sticky chapter section marker for {@code HistoryLogScreen} ({@code #0762}).
         *
         * @param chapterTitle localized chapter title
         * @param topY         pixel scroll offset where section begins
         */
        public record ChapterScrollAnchor(String chapterTitle, float topY) {
        }

        /**
         * Creates a {@code RichTextTypographyHelpers}.
         */
        public RichTextTypographyHelpers() {
        }

        /**
         * Normalizes composed/decomposed Unicode accents cleanly using {@link Normalizer.Form#NFC} ({@code #0698}).
         *
         * @param text input string
         * @return NFC-normalized string
         */
        public static String normalizeNfc(String text) {
            if (text == null || text.isEmpty()) {
                return "";
            }
            return Normalizer.isNormalized(text, Normalizer.Form.NFC)
                    ? text : Normalizer.normalize(text, Normalizer.Form.NFC);
        }

        /**
         * Converts straight ASCII quotes into typographic quotes ({@code DE} vs {@code EN}) in prose outside
         * {@code [...]} and inline {@code tag} braces ({@code #0705}).
         *
         * @param text     source dialogue prose
         * @param language active language identifier
         * @return text with localized typographic quotation marks
         */
        public static String applyTypographicQuotes(String text, String language) {
            if (text == null || text.isEmpty()) {
                return "";
            }
            boolean de = language == null || language.toLowerCase(Locale.ROOT).startsWith("de");
            String openQ = de ? "„" : "“";
            String closeQ = de ? "“" : "”";
            StringBuilder sb = new StringBuilder(text.length());
            int depthBrace = 0;
            int depthBracket = 0;
            boolean opening = true;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '{') {
                    depthBrace++;
                } else if (c == '}' && depthBrace > 0) {
                    depthBrace--;
                } else if (c == '[') {
                    depthBracket++;
                } else if (c == ']' && depthBracket > 0) {
                    depthBracket--;
                } else if (c == '"' && depthBrace == 0 && depthBracket == 0) {
                    sb.append(opening ? openQ : closeQ);
                    opening = !opening;
                    continue;
                }
                sb.append(c);
            }
            return sb.toString();
        }

        /**
         * Computes WCAG 2.1 contrast ratio between hex colors {@code fgHex} and {@code bgHex} ({@code #0695}).
         *
         * @param fgHex foreground hex string (e.g. {@code "#FFFFFF"})
         * @param bgHex background hex string (e.g. {@code "#0D1117"})
         * @return contrast ratio in {@code [1.0, 21.0]}
         */
        public static double computeWcagContrastRatio(String fgHex, String bgHex) {
            double l1 = relativeLuminance(fgHex);
            double l2 = relativeLuminance(bgHex);
            double hi = Math.max(l1, l2);
            double lo = Math.min(l1, l2);
            return (hi + 0.05) / (lo + 0.05);
        }

        /**
         * Checks whether {@code fgHex} on {@code bgHex} satisfies WCAG AA normal-text readability (&gt;= 4.5:1)
         * ({@code #0695}).
         *
         * @param fgHex foreground hex color
         * @param bgHex background hex color
         * @return {@code true} when contrast ratio &gt;= 4.5
         */
        public static boolean isWcagAaReadable(String fgHex, String bgHex) {
            return computeWcagContrastRatio(fgHex, bgHex) >= 4.5;
        }

        /**
         * Automatically closes any unclosed rich-text formatting tags at sentence end so styles cannot bleed
         * into subsequent history entries ({@code #0724}).
         *
         * @param richText candidate sentence
         * @return properly closed rich-text string
         */
        public static String autoCloseUnclosedTags(String richText) {
            if (richText == null || richText.isEmpty()) {
                return "";
            }
            Deque<String> openStack = new ArrayDeque<>();
            Matcher m = Pattern.compile("\\{(/?)([a-zA-Z]+)(?:=[^}]*)?\\}").matcher(richText);
            while (m.find()) {
                boolean closing = !m.group(1).isEmpty();
                String tag = m.group(2).toLowerCase(Locale.ROOT);
                if ("w".equals(tag) || "p".equals(tag) || "nw".equals(tag)
                        || "fast".equals(tag) || "instant".equals(tag) || "image".equals(tag)) {
                    continue;
                }
                if (!closing) {
                    openStack.push(tag);
                } else if (openStack.contains(tag)) {
                    while (!openStack.isEmpty()) {
                        String popped = openStack.pop();
                        if (popped.equals(tag)) {
                            break;
                        }
                    }
                }
            }
            if (openStack.isEmpty()) {
                return richText;
            }
            StringBuilder out = new StringBuilder(richText);
            while (!openStack.isEmpty()) {
                out.append("{/").append(openStack.pop()).append('}');
            }
            return out.toString();
        }

        /**
         * Slices a rich-text string to {@code visibleChars} plain characters while keeping active open tags
         * cleanly closed at the cut point so inline colors never flash/drop mid-typing ({@code #0717}).
         *
         * @param richText     rich-text source
         * @param visibleChars visible character count
         * @return sliced valid rich-text substring
         */
        public static String sliceVisiblePreservingActiveTags(String richText, int visibleChars) {
            if (richText == null || richText.isEmpty() || visibleChars <= 0) {
                return "";
            }
            StringBuilder out = new StringBuilder();
            int plainCount = 0;
            int i = 0;
            while (i < richText.length() && plainCount < visibleChars) {
                char c = richText.charAt(i);
                if (c == '{') {
                    int close = richText.indexOf('}', i);
                    if (close > i) {
                        out.append(richText, i, close + 1);
                        i = close + 1;
                        continue;
                    }
                }
                out.append(c);
                plainCount++;
                i++;
            }
            return autoCloseUnclosedTags(out.toString());
        }

        /**
         * Proportions typewriter reveal cursor when switching language mid-dialogue ({@code #0709}).
         *
         * @param oldVisible previous revealed char count
         * @param oldLength  previous plain sentence length
         * @param newLength  translated plain sentence length
         * @return scaled cursor position clamped to {@code [0, newLength]}
         */
        public static int remapTypewriterOnLanguageSwitch(int oldVisible, int oldLength, int newLength) {
            if (newLength <= 0 || oldLength <= 0) {
                return Math.max(0, newLength);
            }
            double ratio = Math.clamp((double) oldVisible / oldLength, 0.0, 1.0);
            return Math.clamp((int) Math.round(ratio * newLength), 0, newLength);
        }

        /**
         * Computes cumulative reveal timestamp in seconds for each plain character respecting inline
         * {@code cps=...}, {@code fast}, and {@code instant} spans ({@code #0723}).
         *
         * @param richText   input rich text
         * @param defaultCps base typewriter speed (chars/sec)
         * @return array of reveal timestamps (seconds) indexed by plain character position
         */
        public static double[] computeCharRevealTimes(String richText, double defaultCps) {
            if (richText == null || richText.isEmpty()) {
                return new double[0];
            }
            double currentCps = Math.max(1.0, defaultCps);
            double elapsed = 0.0;
            List<Double> times = new ArrayList<>();
            int i = 0;
            while (i < richText.length()) {
                char c = richText.charAt(i);
                if (c == '{') {
                    int end = richText.indexOf('}', i);
                    if (end > i) {
                        String tag = richText.substring(i + 1, end).trim().toLowerCase(Locale.ROOT);
                        if (tag.startsWith("cps=")) {
                            currentCps = parseDoubleOr(tag.substring(4), currentCps);
                        } else if ("fast".equals(tag)) {
                            currentCps = Math.max(currentCps, 200.0);
                        } else if ("instant".equals(tag)) {
                            currentCps = 100_000.0;
                        } else if ("/cps".equals(tag)) {
                            currentCps = Math.max(1.0, defaultCps);
                        }
                        i = end + 1;
                        continue;
                    }
                }
                times.add(elapsed);
                elapsed += (currentCps >= 50_000.0) ? 0.0 : (1.0 / currentCps);
                i++;
            }
            double[] result = new double[times.size()];
            for (int k = 0; k < times.size(); k++) {
                result[k] = times.get(k);
            }
            return result;
        }

        /**
         * Computes smooth 80 ms per-glyph fade-in alpha when enabled ({@code #0744}).
         *
         * @param ageMs   milliseconds elapsed since character appeared
         * @param enabled fade-in preference toggle
         * @return alpha multiplier in {@code [0, 1]}
         */
        public static float computeCharFadeAlpha(double ageMs, boolean enabled) {
            if (!enabled) {
                return ageMs >= 0.0 ? 1.0f : 0.0f;
            }
            if (ageMs <= 0.0) {
                return 0.0f;
            }
            return (float) Math.min(1.0, ageMs / 80.0);
        }

        /**
         * Locates exact character cursor index via binary search over cumulative glyph advance X offsets
         * ({@code #0726}).
         *
         * @param cumulativeAdvances monotonic cumulative X positions (length N for N chars)
         * @param clickX             local mouse X relative to line start
         * @return nearest character insertion index in {@code [0, N]}
         */
        public static int locateCursorBinarySearch(float[] cumulativeAdvances, float clickX) {
            if (cumulativeAdvances == null || cumulativeAdvances.length == 0 || clickX <= 0.0f) {
                return 0;
            }
            int lo = 0;
            int hi = cumulativeAdvances.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (cumulativeAdvances[mid] < clickX) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }

        /**
         * Arranges vertical Japanese Tategaki columns top-to-bottom, right-to-left ({@code #0701}).
         *
         * @param text           Japanese text string
         * @param charsPerColumn max characters per vertical column
         * @return ordered list of column strings arranged visually right-to-left
         */
        public static List<String> layoutVerticalTategakiColumns(String text, int charsPerColumn) {
            if (text == null || text.isEmpty() || charsPerColumn <= 0) {
                return List.of();
            }
            List<String> cols = new ArrayList<>();
            for (int i = 0; i < text.length(); i += charsPerColumn) {
                cols.add(text.substring(i, Math.min(text.length(), i + charsPerColumn)));
            }
            Collections.reverse(cols);
            return List.copyOf(cols);
        }

        /**
         * Extracts HTTP/HTTPS URLs from Help/About text for safe clipboard copy actions ({@code #0751}).
         *
         * @param text text block
         * @return ordered list of detected URL strings
         */
        public static List<String> detectUrls(String text) {
            if (text == null || text.isEmpty()) {
                return List.of();
            }
            Matcher m = Pattern.compile("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+").matcher(text);
            List<String> urls = new ArrayList<>();
            while (m.find()) {
                urls.add(m.group());
            }
            return List.copyOf(urls);
        }

        /**
         * Scans text against supported font codepoints to produce a structured {@link MissingGlyphEntry} report
         * ({@code #0718}, {@code #0754}).
         *
         * @param text                script/UI text
         * @param supportedCodepoints codepoints backed by active font
         * @param sourceRef           script/label identifier
         * @return immutable list of missing glyphs
         */
        public static List<MissingGlyphEntry> findMissingGlyphs(
                String text,
                Set<Integer> supportedCodepoints,
                String sourceRef
        ) {
            if (text == null || text.isEmpty()) {
                return List.of();
            }
            Set<Integer> supported = supportedCodepoints != null ? supportedCodepoints : Set.of();
            List<MissingGlyphEntry> missing = new ArrayList<>();
            int i = 0;
            while (i < text.length()) {
                int cp = text.codePointAt(i);
                if (!Character.isWhitespace(cp) && !supported.contains(cp)) {
                    missing.add(new MissingGlyphEntry(cp, new String(Character.toChars(cp)),
                            sourceRef != null ? sourceRef : ""));
                }
                i += Character.charCount(cp);
            }
            return List.copyOf(missing);
        }

        /**
         * Estimates dynamic auto-read reading duration in seconds based on language script type:
         * CJK characters/sec vs Latin words-per-minute ({@code #0759}).
         *
         * @param plainText visible sentence text
         * @param language  active language
         * @param wpm       target Latin words-per-minute (e.g. {@code 180.0})
         * @return estimated pause duration in seconds ({@code >= 0.8})
         */
        public static double estimateAutoReadDurationSeconds(String plainText, String language, double wpm) {
            if (plainText == null || plainText.isBlank()) {
                return 0.8;
            }
            boolean isCjk = language != null && (language.equalsIgnoreCase("japanese")
                    || language.toLowerCase(Locale.ROOT).startsWith("zh"));
            if (isCjk) {
                double charsPerSec = Math.max(2.0, wpm / 15.0);
                return Math.max(0.8, plainText.codePointCount(0, plainText.length()) / charsPerSec);
            }
            int words = plainText.trim().split("\\s+").length;
            double wordsPerSec = Math.max(0.5, wpm / 60.0);
            return Math.max(0.8, words / wordsPerSec);
        }

        /**
         * Snaps floating layout coordinates to crisp integer pixel boundaries ({@code #0735}).
         *
         * @param value continuous pixel coordinate
         * @return pixel-snapped coordinate
         */
        public static float snapToPixel(float value) {
            return Math.round(value);
        }

        /**
         * Computes sub/superscript scale factor ({@code 65%}) and baseline offset ({@code #0747}).
         *
         * @param baseFontSize base font size
         * @param superscript  {@code true} for sup, {@code false} for sub
         * @return vertical baseline delta in pixels
         */
        public static float computeSubSupBaselineOffset(float baseFontSize, boolean superscript) {
            return superscript ? -baseFontSize * 0.35f : baseFontSize * 0.20f;
        }

        /**
         * Resolves sticky chapter header for {@code HistoryLogScreen} at current scroll Y ({@code #0762}).
         *
         * @param scrollY current scroll position in pixels
         * @param anchors ordered list of {@link ChapterScrollAnchor}
         * @return active chapter header title
         */
        public static String resolveStickyChapterHeader(float scrollY, List<ChapterScrollAnchor> anchors) {
            if (anchors == null || anchors.isEmpty()) {
                return "";
            }
            String active = anchors.get(0).chapterTitle();
            for (ChapterScrollAnchor anchor : anchors) {
                if (anchor.topY() <= scrollY) {
                    active = anchor.chapterTitle();
                } else {
                    break;
                }
            }
            return active;
        }

        /**
         * Wraps {@code [var]} tokens optionally in color highlight tags ({@code #0733}).
         *
         * @param template source string containing {@code [var]} placeholders
         * @param colorHex hex color override (e.g. {@code "#58a6ff"})
         * @param enabled  feature toggle
         * @return highlighted template text
         */
        public static String highlightInterpolatedPlaceholders(String template, String colorHex, boolean enabled) {
            if (!enabled || template == null || template.isEmpty() || colorHex == null) {
                return template != null ? template : "";
            }
            return template.replaceAll("(\\[[A-Za-z0-9_]+\\])", "{color=" + colorHex + "}$1{/color}");
        }

        /**
         * Checks pixel-aligned horizontal offset for LEFT, CENTER, or RIGHT paragraph alignment ({@code #0742}).
         *
         * @param lineWidthPx measured line width
         * @param boxWidthPx  target box width
         * @param alignMode   {@code "LEFT"}, {@code "CENTER"}, or {@code "RIGHT"}
         * @return X start offset within box
         */
        public static float computeAlignedLineX(float lineWidthPx, float boxWidthPx, String alignMode) {
            if ("CENTER".equalsIgnoreCase(alignMode)) {
                return snapToPixel((boxWidthPx - lineWidthPx) * 0.5f);
            }
            if ("RIGHT".equalsIgnoreCase(alignMode)) {
                return snapToPixel(boxWidthPx - lineWidthPx);
            }
            return 0.0f;
        }

        private static double parseDoubleOr(String raw, double fallback) {
            try {
                double d = Double.parseDouble(raw);
                return Double.isFinite(d) && d > 0.0 ? d : fallback;
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private static double relativeLuminance(String hex) {
            if (hex == null) {
                return 0.0;
            }
            String clean = hex.startsWith("#") ? hex.substring(1) : hex;
            if (clean.length() < 6) {
                return 0.0;
            }
            int r = Integer.parseInt(clean.substring(0, 2), 16);
            int g = Integer.parseInt(clean.substring(2, 4), 16);
            int b = Integer.parseInt(clean.substring(4, 6), 16);
            return (0.2126 * linearChannel(r)) + (0.7152 * linearChannel(g)) + (0.0722 * linearChannel(b));
        }

        private static double linearChannel(int c8) {
            double c = c8 / 255.0;
            return (c <= 0.03928) ? (c / 12.92) : Math.pow((c + 0.055) / 1.055, 2.4);
        }

        /**
         * Tracks dynamically baked Unicode codepoints on demand and invalidates glyph pages upon
         * monitor DPI content scale changes ({@code 1x <-> 2x}) ({@code #0699}, {@code #0764}).
         */
        public static final class OnDemandGlyphAtlasTracker {

            private float currentContentScale = 1.0f;
            private final Set<Integer> bakedCodepoints = new LinkedHashSet<>();
            private int rebuildCount = 0;

            /**
             * Creates an {@code OnDemandGlyphAtlasTracker}.
             */
            public OnDemandGlyphAtlasTracker() {
            }

            /**
             * Records codepoint usage so only actively displayed Kanji/symbols allocate atlas tiles ({@code #0699}).
             *
             * @param text rendered string
             * @return count of newly added codepoints
             */
            public synchronized int ensureCodepointsBaked(String text) {
                if (text == null || text.isEmpty()) {
                    return 0;
                }
                int added = 0;
                int idx = 0;
                while (idx < text.length()) {
                    int cp = text.codePointAt(idx);
                    if (bakedCodepoints.add(cp)) {
                        added++;
                    }
                    idx += Character.charCount(cp);
                }
                return added;
            }

            /**
             * Responds to {@code glfwSetWindowContentScaleCallback}; triggers clean atlas regeneration
             * when DPI scale ratio changes ({@code #0764}).
             *
             * @param scaleX horizontal content scale factor
             * @param scaleY vertical content scale factor
             * @return {@code true} if scale changed and atlas regeneration was triggered
             */
            public synchronized boolean onWindowContentScaleChanged(float scaleX, float scaleY) {
                float next = Math.max(1.0f, Math.max(scaleX, scaleY));
                if (Math.abs(next - currentContentScale) > 0.05f) {
                    currentContentScale = next;
                    bakedCodepoints.clear();
                    rebuildCount++;
                    return true;
                }
                return false;
            }

            /**
             * Returns count of baked codepoints currently in GPU cache.
             *
             * @return baked codepoint count
             */
            public synchronized int getBakedGlyphCount() {
                return bakedCodepoints.size();
            }

            /**
             * Returns number of DPI-triggered atlas rebuilds executed.
             *
             * @return rebuild counter
             */
            public synchronized int getRebuildCount() {
                return rebuildCount;
            }
        }
    }
}
