package de.swfdtf.tools;

import de.swfdtf.audio.AudioDecoder;
import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.graphics.LayeredImage;
import de.swfdtf.graphics.RenderPipeline2D;
import de.swfdtf.math.Rect2f;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.ui.ThemePalette;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authoring utilities for KAGAYAKI Studio covering LayeredImage preview &amp; snippet building
 * ({@code #0113}), keyframe timeline scrubbing ({@code #0114}), aspect-ratio Screen simulation
 * ({@code #0123}), WCAG AA character contrast validation ({@code #0124}), form-based
 * {@code project.vjava.json} editing ({@code #0134}), voice auto-matching ({@code #0137}),
 * LipSync calibration ({@code #0142}), Transform presets ({@code #0143}), pixel screenshot diff
 * ({@code #0152}), audio clipping diagnostics ({@code #0160}), and Character/Codex Markdown export
 * ({@code #0167}).
 */
public final class StudioAuthoringToolkit {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");

    /** Target aspect ratio presets supported by the Studio Screen preview simulator ({@code #0123}). */
    public enum ScreenAspectPreset {
        /** Standard 16:9 widescreen ({@code 1920x1080}). */
        RATIO_16_9("16:9", 1920, 1080),
        /** Tall 16:10 laptop/tablet viewport ({@code 1920x1200}). */
        RATIO_16_10("16:10", 1920, 1200),
        /** Classic 4:3 viewport ({@code 1600x1200}). */
        RATIO_4_3("4:3", 1600, 1200),
        /** Ultrawide 21:9 cinema viewport ({@code 2560x1080}). */
        RATIO_21_9("21:9", 2560, 1080);

        private final String label;
        private final int width;
        private final int height;

        ScreenAspectPreset(String label, int width, int height) {
            this.label = label;
            this.width = width;
            this.height = height;
        }

        /**
         * Returns the human-readable aspect ratio label.
         *
         * @return aspect ratio label
         */
        public String label() {
            return label;
        }

        /**
         * Returns the simulated viewport width in pixels.
         *
         * @return simulated viewport width in pixels
         */
        public int width() {
            return width;
        }

        /**
         * Returns the simulated viewport height in pixels.
         *
         * @return simulated viewport height in pixels
         */
        public int height() {
            return height;
        }
    }

    /**
     * Computed letterbox viewport &amp; anchor simulation result ({@code #0123}).
     *
     * @param preset            tested aspect preset
     * @param letterboxViewport letterboxed stage rectangle inside simulated screen
     * @param hasLetterboxBars  {@code true} when vertical or horizontal pillarbox/letterbox bars appear
     * @param centerAnchorX     center anchor X in screen pixels
     * @param centerAnchorY     center anchor Y in screen pixels
     */
    public record ScreenAspectSimulation(
            ScreenAspectPreset preset,
            Rect2f letterboxViewport,
            boolean hasLetterboxBars,
            float centerAnchorX,
            float centerAnchorY
    ) {
    }

    /**
     * Single keyframe cue item on the Studio timeline scrubber ({@code #0114}).
     *
     * @param kind            cue category ({@code move}, {@code audio}, {@code say})
     * @param target          sprite tag, channel, or speaker
     * @param startSeconds    cue start time on label timeline
     * @param durationSeconds cue duration in seconds
     * @param startX          optional start X for motion cues
     * @param startY          optional start Y for motion cues
     * @param endX            optional end X for motion cues
     * @param endY            optional end Y for motion cues
     */
    public record TimelineCue(
            String kind,
            String target,
            double startSeconds,
            double durationSeconds,
            Double startX,
            Double startY,
            Double endX,
            Double endY
    ) {
    }

    /**
     * WCAG AA character color check result ({@code #0124}).
     *
     * @param characterId   character identifier
     * @param colorHex      configured display color hex
     * @param contrastRatio contrast against {@code backgroundHex}
     * @param wcagAaPassed  {@code true} if {@code contrastRatio >= 4.5}
     * @param warningText   human-readable warning when failing WCAG AA, else empty
     */
    public record CharacterColorCheck(
            String characterId,
            String colorHex,
            double contrastRatio,
            boolean wcagAaPassed,
            String warningText
    ) {
    }

    /**
     * Automatic voice-to-dialogue pairing proposal ({@code #0137}).
     *
     * @param sourceFile   script file containing the dialogue
     * @param lineNumber   1-based line number of dialogue
     * @param speaker      speaker ID (nullable for narrator)
     * @param dialogueText line text
     * @param matchedVoice matched voice file relative path, or {@code null} if missing
     */
    public record VoiceMatchProposal(
            String sourceFile,
            int lineNumber,
            String speaker,
            String dialogueText,
            String matchedVoice
    ) {
    }

    /**
     * Lip-sync RMS curve &amp; recommended thresholds calibrated from a WAV sample ({@code #0142}).
     *
     * @param rmsCurve             sampled RMS envelope windows
     * @param peakRms              maximum measured RMS
     * @param recommendedClosedMax suggested noise floor ({@code closed}) threshold
     * @param recommendedHalfOpen  suggested {@code half_open} threshold
     * @param recommendedOpen      suggested {@code open} threshold
     */
    public record LipSyncCalibration(
            List<Float> rmsCurve,
            float peakRms,
            float recommendedClosedMax,
            float recommendedHalfOpen,
            float recommendedOpen
    ) {
    }

    /**
     * Pixel-level screenshot comparison report with RGBA diff visualization buffer ({@code #0152}).
     *
     * @param width            image width in pixels
     * @param height           image height in pixels
     * @param differedPixels   number of pixels exceeding channel tolerance
     * @param diffPercentage   percentage of differing pixels in {@code [0, 100]}
     * @param diffRgba         high-contrast highlight RGBA image buffer
     */
    public record ScreenshotDiffResult(
            int width,
            int height,
            int differedPixels,
            double diffPercentage,
            byte[] diffRgba
    ) {
    }

    /**
     * Audio clipping / peak diagnostic entry emitted when a WAV sample exceeds {@code 0.99f} FS ({@code #0160}).
     *
     * @param assetPath relative asset path
     * @param code      diagnostic code ({@code "AUDIO_CLIPPING"} or {@code "OK"})
     * @param peakLevel peak absolute normalized sample amplitude
     * @param rmsLevel  overall RMS amplitude
     */
    public record AudioPeakDiagnostic(String assetPath, String code, float peakLevel, float rmsLevel) {
    }

    /**
     * Form-backed editable model for {@code project.vjava.json} avoiding raw JSON typos ({@code #0134}).
     */
    public static final class ProjectDescriptorFormModel {

        private String title;
        private String version;
        private String author;
        private String entryLabel;
        private int logicalWidth;
        private int logicalHeight;
        private String iconPath;
        private String renderMode;

        /**
         * Initializes a form model from an existing {@link ProjectDescriptor}.
         *
         * @param descriptor source descriptor
         */
        public ProjectDescriptorFormModel(ProjectDescriptor descriptor) {
            Objects.requireNonNull(descriptor, "descriptor must not be null");
            this.title = descriptor.getTitle();
            this.version = descriptor.getVersion();
            this.author = descriptor.getAuthor();
            this.entryLabel = descriptor.getEntryLabel();
            this.logicalWidth = descriptor.getLogicalWidth();
            this.logicalHeight = descriptor.getLogicalHeight();
            this.iconPath = descriptor.getIconPath();
            this.renderMode = descriptor.getRenderMode();
        }

        /**
         * Sets the project display title.
         *
         * @param title updated title
         * @return {@code this} for fluent chaining
         */
        public ProjectDescriptorFormModel setTitle(String title) {
            this.title = title;
            return this;
        }

        /**
         * Sets the semantic project version string.
         *
         * @param version updated version string
         * @return {@code this} for fluent chaining
         */
        public ProjectDescriptorFormModel setVersion(String version) {
            this.version = version;
            return this;
        }

        /**
         * Sets the author or studio name.
         *
         * @param author updated author
         * @return {@code this} for fluent chaining
         */
        public ProjectDescriptorFormModel setAuthor(String author) {
            this.author = author;
            return this;
        }

        /**
         * Sets the initial entry script label.
         *
         * @param entryLabel updated entry label
         * @return {@code this} for fluent chaining
         */
        public ProjectDescriptorFormModel setEntryLabel(String entryLabel) {
            this.entryLabel = entryLabel;
            return this;
        }

        /**
         * Updates target logical canvas dimensions.
         *
         * @param logicalWidth  width in pixels ({@code > 0})
         * @param logicalHeight height in pixels ({@code > 0})
         * @return {@code this}
         */
        public ProjectDescriptorFormModel setResolution(int logicalWidth, int logicalHeight) {
            this.logicalWidth = logicalWidth;
            this.logicalHeight = logicalHeight;
            return this;
        }

        /**
         * Sets the relative window icon asset path.
         *
         * @param iconPath relative icon path
         * @return {@code this} for fluent chaining
         */
        public ProjectDescriptorFormModel setIconPath(String iconPath) {
            this.iconPath = iconPath;
            return this;
        }

        /**
         * Sets the target render mode ({@code "2D"} or {@code "3D"}).
         *
         * @param renderMode {@code "2D"} or {@code "3D"}
         * @return {@code this} for fluent chaining
         */
        public ProjectDescriptorFormModel setRenderMode(String renderMode) {
            this.renderMode = renderMode;
            return this;
        }

        /**
         * Validates fields and persists normalized {@code project.vjava.json} into {@code projectDir}.
         *
         * @param projectDir target project root directory
         * @return normalized {@link ProjectDescriptor} saved to disk
         * @throws IOException if validation or saving fails
         */
        public ProjectDescriptor applyAndSave(Path projectDir) throws IOException {
            Objects.requireNonNull(projectDir, "projectDir must not be null");
            if (logicalWidth <= 0 || logicalHeight <= 0) {
                throw new IllegalArgumentException("Resolution must be positive");
            }
            ProjectDescriptor desc = Files.isRegularFile(projectDir.resolve(ProjectDescriptor.FILE_NAME))
                    ? ProjectDescriptor.load(projectDir)
                    : ProjectDescriptor.createDefault(title);
            desc.setTitle(title);
            desc.setVersion(version);
            desc.setAuthor(author);
            desc.setEntryLabel(entryLabel);
            desc.setLogicalWidth(logicalWidth);
            desc.setLogicalHeight(logicalHeight);
            desc.setIconPath(iconPath);
            desc.setRenderMode(renderMode);
            ProjectDescriptor.save(projectDir, desc);
            return ProjectDescriptor.load(projectDir);
        }
    }

    /** Creates a new {@code StudioAuthoringToolkit}. */
    public StudioAuthoringToolkit() {
    }

    /**
     * Resolves LayeredImage asset layer paths and builds the matching {@code show} command ({@code #0113}).
     *
     * @param def        parsed {@link VJavaAst.LayeredImageDef}
     * @param attributes selected attribute list
     * @param position   optional position name (nullable)
     * @return resolved layer asset list plus formatted {@code show} snippet
     */
    public Map.Entry<List<String>, String> buildLayeredPreviewAndSnippet(
            VJavaAst.LayeredImageDef def,
            List<String> attributes,
            String position
    ) {
        Objects.requireNonNull(def, "def must not be null");
        LayeredImage img = new LayeredImage(def, null);
        List<String> attrs = attributes != null ? attributes : List.of();
        img.setAttributes(attrs, null, null);
        List<String> resolved = img.getResolvedLayerAssets();
        StringBuilder snippet = new StringBuilder("show ").append(def.name());
        for (String attr : attrs) {
            if (attr != null && !attr.isBlank()) {
                snippet.append(' ').append(attr.trim());
            }
        }
        if (position != null && !position.isBlank()) {
            snippet.append(" at ").append(position.trim());
        }
        return Map.entry(resolved, snippet.toString());
    }

    /**
     * Extracts keyframe cues from a script label into a linear timeline ({@code #0114}).
     *
     * @param label source script label node
     * @return immutable list of {@link TimelineCue} entries
     */
    public List<TimelineCue> buildTimelineCues(VJavaAst.LabelNode label) {
        Objects.requireNonNull(label, "label must not be null");
        List<TimelineCue> cues = new ArrayList<>();
        double cursorSec = 0.0;
        for (VJavaAst.Statement stmt : label.statements()) {
            if (stmt instanceof VJavaAst.MoveStatement mv) {
                double dur = Math.max(0.0, mv.durationSeconds());
                cues.add(new TimelineCue("move", mv.tag(), cursorSec, dur,
                        null, null, mv.targetX(), mv.targetY()));
            } else if (stmt instanceof VJavaAst.PlayStatement pl) {
                cues.add(new TimelineCue("audio", pl.channel(), cursorSec,
                        Math.max(0.1, pl.fadeInSeconds()), null, null, null, null));
            } else if (stmt instanceof VJavaAst.PauseStatement ps) {
                cursorSec += ps.seconds() != null ? Math.max(0.0, ps.seconds()) : 0.5;
            } else if (stmt instanceof VJavaAst.SayStatement say) {
                double readDur = Math.max(0.5, say.text().length() * 0.04);
                cues.add(new TimelineCue("say", say.speakerId() != null ? say.speakerId() : "narrator",
                        cursorSec, readDur, null, null, null, null));
                cursorSec += readDur;
            }
        }
        return List.copyOf(cues);
    }

    /**
     * Deterministically samples a sprite's interpolated X/Y position along timeline {@code move}
     * cues at {@code timeSeconds} ({@code #0114}).
     *
     * @param cues        timeline cue list
     * @param targetTag   sprite tag
     * @param timeSeconds scrub timestamp in seconds
     * @param originX     initial sprite X
     * @param originY     initial sprite Y
     * @return interpolated {@code [x, y]} array
     */
    public float[] sampleStageAt(
            List<TimelineCue> cues,
            String targetTag,
            double timeSeconds,
            float originX,
            float originY
    ) {
        float x = originX;
        float y = originY;
        if (cues == null || targetTag == null) {
            return new float[]{x, y};
        }
        for (TimelineCue cue : cues) {
            if (!"move".equals(cue.kind()) || !targetTag.equals(cue.target())
                    || cue.endX() == null || cue.endY() == null) {
                continue;
            }
            if (timeSeconds < cue.startSeconds()) {
                continue;
            }
            if (cue.durationSeconds() <= 0.0 || timeSeconds >= cue.startSeconds() + cue.durationSeconds()) {
                x = cue.endX().floatValue();
                y = cue.endY().floatValue();
            } else {
                double t = (timeSeconds - cue.startSeconds()) / cue.durationSeconds();
                x = (float) (x + (cue.endX() - x) * t);
                y = (float) (y + (cue.endY() - y) * t);
            }
        }
        return new float[]{x, y};
    }

    /**
     * Simulates letterbox viewport and anchor coordinates across all {@link ScreenAspectPreset} ratios
     * ({@code #0123}).
     *
     * @return list of aspect ratio simulations
     */
    public List<ScreenAspectSimulation> simulateScreenAspects() {
        List<ScreenAspectSimulation> out = new ArrayList<>();
        for (ScreenAspectPreset preset : ScreenAspectPreset.values()) {
            Rect2f vp = RenderPipeline2D.computeLetterboxViewport(
                    preset.width(), preset.height(),
                    RenderPipeline2D.DEFAULT_LOGICAL_WIDTH, RenderPipeline2D.DEFAULT_LOGICAL_HEIGHT);
            boolean bars = Math.abs(vp.width() - preset.width()) > 1.0f
                    || Math.abs(vp.height() - preset.height()) > 1.0f;
            out.add(new ScreenAspectSimulation(
                    preset, vp, bars, vp.x() + vp.width() * 0.5f, vp.y() + vp.height() * 0.5f));
        }
        return List.copyOf(out);
    }

    /**
     * Checks character display color against {@code backgroundHex} for WCAG 2.1 AA contrast compliance
     * ({@code #0124}).
     *
     * @param characterId   character identifier
     * @param colorHex      foreground color hex
     * @param backgroundHex panel background hex (defaults to {@link ThemePalette#PANEL_SURFACE})
     * @return {@link CharacterColorCheck} result
     */
    public CharacterColorCheck checkCharacterContrast(String characterId, String colorHex, String backgroundHex) {
        String bg = (backgroundHex == null || backgroundHex.isBlank())
                ? ThemePalette.PANEL_SURFACE : backgroundHex;
        double ratio = ThemePalette.contrastRatio(colorHex, bg);
        boolean ok = ratio >= ThemePalette.WCAG_AA_NORMAL_CONTRAST;
        String warning = ok ? ""
                : String.format(Locale.ROOT, "Kontrast zu niedrig (%.2f:1 < 4.50:1 WCAG AA)", ratio);
        return new CharacterColorCheck(characterId, colorHex, ratio, ok, warning);
    }

    /**
     * Matches numbered voice files under {@code assets/audio/voice/} sequentially against project
     * dialogue lines ({@code #0137}).
     *
     * @param project scanned project AST
     * @return ordered list of {@link VoiceMatchProposal} entries
     */
    public List<VoiceMatchProposal> matchVoiceFiles(VJavaAst.ProjectAst project) {
        Objects.requireNonNull(project, "project must not be null");
        Map<Integer, String> numberedVoices = new LinkedHashMap<>();
        List<String> unnumberedVoices = new ArrayList<>();
        for (String asset : project.discoveredAssets()) {
            String low = asset.toLowerCase(Locale.ROOT);
            if (low.contains("assets/audio/voice/") && (low.endsWith(".wav") || low.endsWith(".ogg"))) {
                Matcher m = NUMBER_PATTERN.matcher(Path.of(asset).getFileName().toString());
                if (m.find()) {
                    int idx = Integer.parseInt(m.group(1));
                    numberedVoices.putIfAbsent(idx, asset);
                } else {
                    unnumberedVoices.add(asset);
                }
            }
        }
        List<VoiceMatchProposal> proposals = new ArrayList<>();
        int lineCounter = 1;
        for (VJavaAst.LabelNode label : project.labels().values()) {
            for (VJavaAst.Statement stmt : label.statements()) {
                if (stmt instanceof VJavaAst.SayStatement say) {
                    String match = numberedVoices.get(lineCounter);
                    if (match == null && lineCounter - 1 < unnumberedVoices.size()) {
                        match = unnumberedVoices.get(lineCounter - 1);
                    }
                    proposals.add(new VoiceMatchProposal(
                            say.sourceFile(), say.lineNumber(), say.speakerId(), say.text(), match));
                    lineCounter++;
                }
            }
        }
        return List.copyOf(proposals);
    }

    /**
     * Computes an RMS curve and recommends optimal {@code closed}, {@code half_open}, and {@code open}
     * mouth animation thresholds from a voice WAV/OGG asset ({@code #0142}).
     *
     * @param audioFile path to WAV/OGG file
     * @return {@link LipSyncCalibration} profile
     * @throws IOException if decoding fails
     */
    public LipSyncCalibration calibrateLipSync(Path audioFile) throws IOException {
        Objects.requireNonNull(audioFile, "audioFile must not be null");
        AudioDecoder.DecodedAudio decoded = AudioDecoder.decode(audioFile);
        float[] mono = decoded.toMonoFloat();
        int windowSize = Math.max(1, decoded.sampleRate() / 20);
        List<Float> curve = new ArrayList<>();
        float peakRms = 0.0f;
        for (int start = 0; start < mono.length; start += windowSize) {
            int end = Math.min(mono.length, start + windowSize);
            double sumSq = 0.0;
            for (int i = start; i < end; i++) {
                sumSq += mono[i] * mono[i];
            }
            float rms = (float) Math.sqrt(sumSq / (end - start));
            curve.add(rms);
            if (rms > peakRms) {
                peakRms = rms;
            }
        }
        float ref = Math.max(0.05f, peakRms);
        return new LipSyncCalibration(
                Collections.unmodifiableList(curve),
                peakRms,
                Math.min(0.05f, ref * 0.08f),
                ref * 0.25f,
                ref * 0.60f
        );
    }

    /**
     * Generates a valid {@code transform <name>:} DSL block for built-in or custom positions ({@code #0143}).
     *
     * @param presetName transform identifier
     * @param xalign     horizontal align fraction
     * @param yalign     vertical align fraction
     * @param zoom       sprite zoom factor
     * @return valid parser-ready {@code .vjava} transform block
     */
    public String buildTransformPresetBlock(String presetName, float xalign, float yalign, float zoom) {
        String name = (presetName == null || presetName.isBlank()) ? "custom_pos" : presetName.trim();
        return String.format(
                Locale.ROOT,
                "transform %s:%n    xalign %.3f%n    yalign %.3f%n    zoom %.3f%n",
                name, xalign, yalign, zoom);
    }

    /**
     * Compares two equal-sized RGBA buffers pixel-by-pixel and returns high-contrast visual diff data
     * ({@code #0152}).
     *
     * @param width     image width
     * @param height    image height
     * @param reference reference RGBA bytes
     * @param candidate candidate RGBA bytes
     * @param tolerance per-channel delta tolerance ({@code >= 0})
     * @return {@link ScreenshotDiffResult}
     */
    public ScreenshotDiffResult diffRgbaImages(
            int width,
            int height,
            byte[] reference,
            byte[] candidate,
            int tolerance
    ) {
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        int expectedLen = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (reference.length != expectedLen || candidate.length != expectedLen) {
            throw new IllegalArgumentException("Image buffer sizes do not match " + width + "x" + height);
        }
        byte[] diff = new byte[expectedLen];
        int changed = 0;
        int tol = Math.max(0, tolerance);
        for (int i = 0; i < expectedLen; i += 4) {
            int dr = Math.abs((reference[i] & 0xFF) - (candidate[i] & 0xFF));
            int dg = Math.abs((reference[i + 1] & 0xFF) - (candidate[i + 1] & 0xFF));
            int db = Math.abs((reference[i + 2] & 0xFF) - (candidate[i + 2] & 0xFF));
            int da = Math.abs((reference[i + 3] & 0xFF) - (candidate[i + 3] & 0xFF));
            if (dr > tol || dg > tol || db > tol || da > tol) {
                changed++;
                diff[i] = (byte) 0xFF;
                diff[i + 1] = (byte) 0x33;
                diff[i + 2] = (byte) 0x55;
                diff[i + 3] = (byte) 0xFF;
            } else {
                diff[i] = (byte) ((candidate[i] & 0xFF) / 4);
                diff[i + 1] = (byte) ((candidate[i + 1] & 0xFF) / 4);
                diff[i + 2] = (byte) ((candidate[i + 2] & 0xFF) / 4);
                diff[i + 3] = (byte) 0xFF;
            }
        }
        double pct = (width * height) == 0 ? 0.0 : (changed * 100.0) / (width * height);
        return new ScreenshotDiffResult(width, height, changed, pct, diff);
    }

    /**
     * Checks all WAV/OGG audio files in {@code projectDir} for PCM clipping ({@code > 0.99f} FS)
     * and returns structured {@code AUDIO_CLIPPING} diagnostics ({@code #0160}).
     *
     * @param projectDir project root directory
     * @return immutable list of audio diagnostics across discovered audio assets
     * @throws IOException if scanning or decoding fails
     */
    public List<AudioPeakDiagnostic> checkAudioClipping(Path projectDir) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Path root = projectDir.toAbsolutePath().normalize();
        Path audioDir = root.resolve("assets/audio");
        if (!Files.isDirectory(audioDir)) {
            return List.of();
        }
        List<AudioPeakDiagnostic> out = new ArrayList<>();
        try (var stream = Files.walk(audioDir)) {
            for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                String low = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!low.endsWith(".wav") && !low.endsWith(".ogg")) {
                    continue;
                }
                AudioDecoder.DecodedAudio decoded = AudioDecoder.decode(file);
                float[] samples = decoded.monoOrInterleavedFloat();
                float peak = 0.0f;
                double sumSq = 0.0;
                for (float s : samples) {
                    float abs = Math.abs(s);
                    if (abs > peak) {
                        peak = abs;
                    }
                    sumSq += s * s;
                }
                float rms = samples.length == 0 ? 0.0f : (float) Math.sqrt(sumSq / samples.length);
                String rel = root.relativize(file).toString().replace('\\', '/');
                String code = peak > 0.99f ? "AUDIO_CLIPPING" : "OK";
                out.add(new AudioPeakDiagnostic(rel, code, peak, rms));
            }
        }
        return List.copyOf(out);
    }

    /**
     * Generates a Markdown character sheet &amp; Codex glossary export from AST and Codex entries
     * ({@code #0167}).
     *
     * @param project      parsed project AST
     * @param codexEntries optional map of codex entry ID to description
     * @return formatted Markdown overview
     */
    public String exportCharacterAndCodexMarkdown(
            VJavaAst.ProjectAst project,
            Map<String, String> codexEntries
    ) {
        Objects.requireNonNull(project, "project must not be null");
        StringBuilder sb = new StringBuilder();
        sb.append("# Charaktere & Codex\n\n## Charaktere\n\n");
        if (project.characters().isEmpty()) {
            sb.append("(Keine Charaktere definiert)\n");
        } else {
            for (VJavaAst.CharacterDef ch : project.characters().values()) {
                sb.append("- **").append(ch.id()).append("** (`")
                        .append(ch.displayName()).append("`)");
                if (ch.colorHex() != null && !ch.colorHex().isBlank()) {
                    sb.append(" — Farbe: `").append(ch.colorHex()).append('`');
                }
                sb.append('\n');
            }
        }
        sb.append("\n## Codex & Glossar\n\n");
        if (codexEntries == null || codexEntries.isEmpty()) {
            sb.append("(Keine Codex-Einträge vorhanden)\n");
        } else {
            for (Map.Entry<String, String> e : codexEntries.entrySet()) {
                sb.append("- **").append(e.getKey()).append("**: ").append(e.getValue()).append('\n');
            }
        }
        return sb.toString();
    }
}
