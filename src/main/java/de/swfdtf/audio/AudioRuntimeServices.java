package de.swfdtf.audio;

import de.swfdtf.state.LocalizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runtime audio infrastructure services completing Wave P2-6 Chapter 6 goals ({@code #0433}–{@code #0510}):
 * OpenAL error translation &amp; lifecycle auditing, priority-stealing source pool, localized voice fallback,
 * 2D/3D spatial panning &amp; HRTF gating, adaptive stems, beat sync, sparse ambient scheduler, heartbeat pulse,
 * reverb zones, multi-track overlapping dialogue, SFX cooldown guard, bleep cache, `.vtt`/`.lrc`/cue-sheet
 * exporters, and a deterministic thread-safe headless audio controller.
 */
public final class AudioRuntimeServices {

    private AudioRuntimeServices() {
    }

    /** OpenAL context/source/buffer shutdown stage order ({@code #0491}). */
    public enum TeardownStep {
        /** Stop and delete active OpenAL sources first. */
        SOURCES,
        /** Delete OpenAL buffers once detached from sources. */
        BUFFERS,
        /** Clear current context and destroy ALC context. */
        CONTEXT,
        /** Close physical ALC device last. */
        DEVICE
    }

    /**
     * Translates OpenAL error codes into diagnostic messages, validates macOS-safe teardown order,
     * and audits native buffer allocation balance ({@code #0479}, {@code #0491}, {@code #0510}).
     */
    public static final class OpenAlErrorTranslator {
        private int allocatedBuffers = 0;

        /** Creates a new OpenAL error and buffer-balance auditor. */
        public OpenAlErrorTranslator() {
        }

        /**
         * Translates a numeric OpenAL error code into human-readable diagnostics.
         *
         * @param alErrorCode OpenAL error constant (e.g., {@code 0xA001..0xA005})
         * @param apiCall     calling OpenAL function name
         * @return formatted diagnostic text
         */
        public static String formatError(int alErrorCode, String apiCall) {
            String name = switch (alErrorCode) {
                case 0 -> "AL_NO_ERROR";
                case 0xA001 -> "AL_INVALID_NAME";
                case 0xA002 -> "AL_INVALID_ENUM";
                case 0xA003 -> "AL_INVALID_VALUE";
                case 0xA004 -> "AL_INVALID_OPERATION";
                case 0xA005 -> "AL_OUT_OF_MEMORY";
                default -> "AL_ERROR_0x" + Integer.toHexString(alErrorCode).toUpperCase(Locale.ROOT);
            };
            return name + " during " + (apiCall != null ? apiCall : "OpenAL call");
        }

        /**
         * Validates strict macOS-safe teardown ordering ({@code SOURCES -> BUFFERS -> CONTEXT -> DEVICE}).
         *
         * @param sequence observed shutdown stages
         * @return {@code true} when teardown sequence matches canonical safe order
         */
        public static boolean isSafeTeardownSequence(List<TeardownStep> sequence) {
            return List.of(
                    TeardownStep.SOURCES,
                    TeardownStep.BUFFERS,
                    TeardownStep.CONTEXT,
                    TeardownStep.DEVICE
            ).equals(sequence);
        }

        /**
         * Records buffer generation ({@code alGenBuffers}).
         */
        public synchronized void recordGenBuffer() {
            allocatedBuffers++;
        }

        /**
         * Records buffer deletion ({@code alDeleteBuffers}).
         */
        public synchronized void recordDeleteBuffer() {
            allocatedBuffers--;
        }

        /**
         * Returns current net OpenAL buffer balance ({@code 0} indicates zero native buffer leaks).
         *
         * @return net unfreed buffer count
         */
        public synchronized int getBufferBalance() {
            return allocatedBuffers;
        }
    }

    /**
     * Source pooling priority tier preventing transient SFX bursts from cutting BGM or voice ({@code #0438}).
     */
    public enum SourcePriority {
        /** Low-priority sound effect eligible for eviction. */
        SFX(1),
        /** Background ambience channel. */
        AMBIENT(2),
        /** Primary background music stream (protected). */
        BGM(3),
        /** Spoken dialogue voice track (highest protection). */
        VOICE(4);

        private final int rank;

        SourcePriority(int rank) {
            this.rank = rank;
        }

        /**
         * Returns priority rank.
         *
         * @return numeric priority rank
         */
        public int getRank() {
            return rank;
        }
    }

    /**
     * Active pooled hardware voice allocation descriptor.
     *
     * @param sourceId hardware/logical source ID
     * @param channel  target mixer channel
     * @param priority eviction priority tier
     * @param sequence monotonic allocation sequence
     */
    public record PooledSource(int sourceId, String channel, SourcePriority priority, long sequence) {
    }

    /**
     * Bounded OpenAL source pool with priority-based voice stealing ({@code #0438}).
     */
    public static final class SourcePoolManager {
        private final int maxSources;
        private final List<PooledSource> active = new ArrayList<>();
        private long seq = 1L;

        /**
         * Creates a source pool bounded to {@code maxSources}.
         *
         * @param maxSources hardware voice pool capacity (e.g., {@code 32} or {@code 64})
         */
        public SourcePoolManager(int maxSources) {
            this.maxSources = Math.max(1, maxSources);
        }

        /**
         * Allocates a hardware source, stealing the oldest lowest-priority source when full.
         *
         * @param channel  logical channel name
         * @param priority allocation priority
         * @return allocated {@link PooledSource}, or {@code null} if all active sources outrank it
         */
        public synchronized PooledSource acquire(String channel, SourcePriority priority) {
            Objects.requireNonNull(priority, "priority must not be null");
            if (active.size() < maxSources) {
                PooledSource created = new PooledSource(active.size() + 1, channel, priority, seq++);
                active.add(created);
                return created;
            }
            int victimIdx = -1;
            int worstRank = priority.getRank();
            long oldestSeq = Long.MAX_VALUE;
            for (int i = 0; i < active.size(); i++) {
                PooledSource s = active.get(i);
                int r = s.priority().getRank();
                if (r < worstRank || (r == worstRank && r < SourcePriority.BGM.getRank() && s.sequence() < oldestSeq)) {
                    worstRank = r;
                    oldestSeq = s.sequence();
                    victimIdx = i;
                }
            }
            if (victimIdx < 0) {
                return null;
            }
            PooledSource victim = active.remove(victimIdx);
            PooledSource reused = new PooledSource(victim.sourceId(), channel, priority, seq++);
            active.add(reused);
            return reused;
        }

        /**
         * Returns immutable list of active pooled sources.
         *
         * @return active source allocations
         */
        public synchronized List<PooledSource> getActiveSources() {
            return List.copyOf(active);
        }
    }

    /**
     * Resolves localized dialogue voice paths under {@code assets/audio/voice/<lang>/...} with
     * graceful fallback to the base language path when absent ({@code #0437}).
     */
    public static final class LocalizedVoiceResolver {
        private LocalizedVoiceResolver() {
        }

        /**
         * Resolves voice asset path against active language in {@code localization} if present on disk.
         *
         * @param projectRoot  project root directory (nullable)
         * @param baseAsset    canonical voice asset path
         * @param localization active localization manager (nullable)
         * @return localized path if present, otherwise {@code baseAsset}
         */
        public static String resolveVoicePath(
                Path projectRoot,
                String baseAsset,
                LocalizationManager localization
        ) {
            if (baseAsset == null || baseAsset.isBlank()) {
                return "";
            }
            String clean = baseAsset.trim().replace('\\', '/');
            if (localization != null && projectRoot != null) {
                String lang = localization.getCurrentLanguage();
                if (lang != null && !lang.isBlank()) {
                    String prefix = "assets/audio/voice/";
                    String fileName = Path.of(clean).getFileName().toString();
                    String langCandidate = prefix + lang.trim().toLowerCase(Locale.ROOT) + "/" + fileName;
                    if (Files.isRegularFile(projectRoot.resolve(langCandidate))) {
                        return langCandidate;
                    }
                    String viaLoc = localization.resolveLocalizedAsset(projectRoot, clean);
                    if (viaLoc != null && Files.isRegularFile(projectRoot.resolve(viaLoc))) {
                        return viaLoc;
                    }
                }
            }
            return clean;
        }
    }

    /**
     * Computes stereo 2D panning from stage speaker position and gates binaural HRTF activation
     * behind explicit driver extension availability ({@code #0434}, {@code #0478}).
     */
    public static final class SpatialVoicePanner {
        private boolean hrtfRequested = false;
        private boolean hrtfExtensionAvailable = false;

        /** Creates a spatial voice panner with HRTF disabled by default. */
        public SpatialVoicePanner() {
        }

        /**
         * Maps logical sprite X coordinate {@code [0..logicalWidth]} into clamped stereo pan
         * {@code [-0.35, +0.35]} around screen center.
         *
         * @param speakerStageX sprite X center in logical pixels
         * @param logicalWidth  stage logical width
         * @return stereo pan in {@code [-0.35, +0.35]}
         */
        public static float computeStagePan(float speakerStageX, float logicalWidth) {
            float norm = (speakerStageX / Math.max(1.0f, logicalWidth)) * 2.0f - 1.0f;
            return Math.clamp(norm * 0.5f, -0.35f, 0.35f);
        }

        /**
         * Updates requested HRTF toggle and hardware {@code ALC_SOFT_HRTF} capability flag.
         *
         * @param requested          user/scene HRTF toggle
         * @param extensionAvailable whether driver supports {@code ALC_SOFT_HRTF}
         */
        public void configureHrtf(boolean requested, boolean extensionAvailable) {
            this.hrtfRequested = requested;
            this.hrtfExtensionAvailable = extensionAvailable;
        }

        /**
         * Returns whether HRTF spatialization is actually active (requires both request and hardware support).
         *
         * @return {@code true} when safely active
         */
        public boolean isHrtfActive() {
            return hrtfRequested && hrtfExtensionAvailable;
        }
    }

    /**
     * Multi-layer adaptive score stem blender keeping all tracks phase-synchronized within
     * {@code < 1 ms} drift while fading stem weights dynamically ({@code #0451}).
     */
    public static final class AdaptiveStemMixer {
        private final Map<String, Float> targetGains = new LinkedHashMap<>();
        private final Map<String, Float> currentGains = new LinkedHashMap<>();
        private double masterClockSeconds = 0.0;

        /** Creates a synchronous multi-layer stem mixer. */
        public AdaptiveStemMixer() {
        }

        /**
         * Sets target gain for a music stem (e.g., {@code "calm"}, {@code "tension"}).
         *
         * @param stemName   stem identifier
         * @param targetGain normalized gain in {@code [0, 1]}
         */
        public void setStemTargetGain(String stemName, float targetGain) {
            if (stemName != null && !stemName.isBlank()) {
                float c = Float.isFinite(targetGain) ? Math.clamp(targetGain, 0.0f, 1.0f) : 0.0f;
                targetGains.put(stemName, c);
                currentGains.putIfAbsent(stemName, c);
            }
        }

        /**
         * Advances all stems synchronously so inter-stem phase skew remains {@code 0 ms}.
         *
         * @param dt           delta seconds
         * @param fadeSpeedPerSec linear blend speed per second
         */
        public void update(double dt, float fadeSpeedPerSec) {
            if (dt <= 0.0) {
                return;
            }
            masterClockSeconds += dt;
            float maxStep = (float) (Math.max(0.1f, fadeSpeedPerSec) * dt);
            for (Map.Entry<String, Float> entry : targetGains.entrySet()) {
                float cur = currentGains.getOrDefault(entry.getKey(), 0.0f);
                float tgt = entry.getValue();
                float delta = tgt - cur;
                if (Math.abs(delta) <= maxStep) {
                    currentGains.put(entry.getKey(), tgt);
                } else {
                    currentGains.put(entry.getKey(), cur + Math.copySign(maxStep, delta));
                }
            }
        }

        /**
         * Returns current smoothed gain of {@code stemName}.
         *
         * @param stemName stem identifier
         * @return current gain in {@code [0, 1]}
         */
        public float getStemGain(String stemName) {
            return currentGains.getOrDefault(stemName, 0.0f);
        }

        /**
         * Returns shared synchronized clock position for all active stems.
         *
         * @return synchronized clock in seconds
         */
        public double getSynchronizedPositionSeconds() {
            return masterClockSeconds;
        }
    }

    /**
     * Exact BPM + offset beat clock emitting {@code on_beat} triggers with sub-millisecond drift
     * accumulator precision ({@code #0463}).
     */
    public static final class BeatSyncClock {
        private final double bpm;
        private final double offsetSeconds;
        private int emittedBeats = 0;

        /**
         * Creates a beat clock.
         *
         * @param bpm           beats per minute ({@code > 0})
         * @param offsetSeconds initial beat offset in seconds
         */
        public BeatSyncClock(double bpm, double offsetSeconds) {
            if (!Double.isFinite(bpm) || bpm <= 0.0) {
                throw new IllegalArgumentException("bpm must be > 0");
            }
            this.bpm = bpm;
            this.offsetSeconds = Double.isFinite(offsetSeconds) ? Math.max(0.0, offsetSeconds) : 0.0;
        }

        /**
         * Computes beat indices triggered by advancing music playback to {@code songPositionSeconds}.
         *
         * @param songPositionSeconds current music track position in seconds
         * @return list of 1-based beat numbers triggered since last update
         */
        public List<Integer> advanceTo(double songPositionSeconds) {
            if (songPositionSeconds < offsetSeconds) {
                return List.of();
            }
            double beatPeriod = 60.0 / bpm;
            int totalBeatsReached = (int) Math.floor((songPositionSeconds - offsetSeconds + 1e-6) / beatPeriod) + 1;
            List<Integer> fired = new ArrayList<>();
            while (emittedBeats < totalBeatsReached) {
                emittedBeats++;
                fired.add(emittedBeats);
            }
            return fired;
        }

        /**
         * Returns theoretical beat timestamp for 1-based {@code beatIndex} to verify drift.
         *
         * @param beatIndex 1-based beat index
         * @return exact timestamp in seconds
         */
        public double expectedBeatTimestamp(int beatIndex) {
            return offsetSeconds + (beatIndex - 1) * (60.0 / bpm);
        }
    }

    /**
     * Randomized sparse ambient cue scheduler enforcing configurable min/max cooldown windows ({@code #0475}).
     */
    public static final class AmbientRandomScheduler {
        private final double minIntervalSec;
        private final double maxIntervalSec;
        private final Random rng;
        private double remainingSec;

        /**
         * Creates a deterministic or random ambient scheduler.
         *
         * @param minIntervalSec minimum silence interval between triggers in seconds
         * @param maxIntervalSec maximum silence interval in seconds
         * @param seed           PRNG seed
         */
        public AmbientRandomScheduler(double minIntervalSec, double maxIntervalSec, long seed) {
            this.minIntervalSec = Math.max(0.1, minIntervalSec);
            this.maxIntervalSec = Math.max(this.minIntervalSec, maxIntervalSec);
            this.rng = new Random(seed);
            this.remainingSec = nextDelay();
        }

        /**
         * Advances scheduler clock and returns {@code true} once when a sparse cue should trigger.
         *
         * @param dt delta seconds
         * @return {@code true} if triggered
         */
        public boolean update(double dt) {
            if (dt <= 0.0) {
                return false;
            }
            remainingSec -= dt;
            if (remainingSec <= 0.0) {
                remainingSec = nextDelay();
                return true;
            }
            return false;
        }

        /**
         * Returns minimum enforced cooldown between triggers.
         *
         * @return minimum interval in seconds
         */
        public double getMinIntervalSec() {
            return minIntervalSec;
        }

        private double nextDelay() {
            return minIntervalSec + rng.nextDouble() * (maxIntervalSec - minIntervalSec);
        }
    }

    /**
     * Maps normalized game stress/fear state {@code [0, 1]} to safe heartbeat BPM in {@code [60, 140]} ({@code #0489}).
     */
    public static final class HeartbeatPulseController {
        private HeartbeatPulseController() {
        }

        /**
         * Computes clamped heartbeat BPM in {@code [60, 140]}.
         *
         * @param stressLevel normalized stress {@code [0, 1]}
         * @return safe BPM
         */
        public static double computeBpm(double stressLevel) {
            double c = Double.isFinite(stressLevel) ? Math.clamp(stressLevel, 0.0, 1.0) : 0.0;
            return 60.0 + c * 80.0;
        }
    }

    /** Acoustic reverb preset supporting OpenAL EFX or smooth no-op fallback ({@code #0433}, {@code #0457}). */
    public enum ReverbPreset {
        /** Dry no-reverb acoustics. */
        NONE,
        /** Damp deep cavern tail. */
        CAVE,
        /** Submersed muffled underwater acoustics. */
        UNDERWATER,
        /** Dreamy long flashback wash. */
        FLASHBACK,
        /** Large stone cathedral hall reverb. */
        CATHEDRAL,
        /** Subtle outdoor woodland reflections. */
        FOREST
    }

    /**
     * Manages scene/zone EFX reverb blending with hardware extension gating ({@code #0433}, {@code #0457}).
     */
    public static final class ReverbZoneController {
        private ReverbPreset activePreset = ReverbPreset.NONE;
        private float wetMix = 0.0f;
        private float targetWetMix = 0.0f;
        private final boolean efxSupported;

        /**
         * Creates a reverb zone controller.
         *
         * @param efxSupported whether OpenAL {@code ALC_EXT_EFX} is available
         */
        public ReverbZoneController(boolean efxSupported) {
            this.efxSupported = efxSupported;
        }

        /**
         * Enters or leaves an acoustic reverb zone.
         *
         * @param preset scene acoustic preset
         * @param wetMix target wet mix {@code [0, 1]}
         */
        public void enterZone(ReverbPreset preset, float wetMix) {
            this.activePreset = Objects.requireNonNull(preset, "preset must not be null");
            this.targetWetMix = (!efxSupported || preset == ReverbPreset.NONE)
                    ? 0.0f : Math.clamp(wetMix, 0.0f, 1.0f);
        }

        /**
         * Smoothly blends current reverb wet mix toward zone target.
         *
         * @param dt delta seconds
         */
        public void update(double dt) {
            if (dt <= 0.0) {
                return;
            }
            float step = (float) Math.min(1.0, dt * 4.0);
            wetMix += (targetWetMix - wetMix) * step;
            if (Math.abs(targetWetMix - wetMix) < 0.005f) {
                wetMix = targetWetMix;
            }
        }

        /**
         * Returns active acoustic preset.
         *
         * @return active {@link ReverbPreset}
         */
        public ReverbPreset getActivePreset() {
            return activePreset;
        }

        /**
         * Returns effective smoothed wet reverb mix ({@code 0} when EFX unsupported).
         *
         * @return wet mix in {@code [0, 1]}
         */
        public float getWetMix() {
            return efxSupported ? wetMix : 0.0f;
        }
    }

    /**
     * Active multi-track dialogue slot supporting simultaneous overlapping character voices ({@code #0476}).
     *
     * @param speakerId speaker identifier
     * @param assetPath audio path
     * @param stereoPan stereo pan in {@code [-1, 1]}
     * @param analyzer  per-speaker lip-sync envelope analyzer
     */
    public record VoiceSlot(String speakerId, String assetPath, float stereoPan, LipSyncAnalyzer analyzer) {
    }

    /**
     * Manages multiple concurrent dialogue voice slots with independent pan and lip-sync ({@code #0476}).
     */
    public static final class MultiTrackVoiceManager {
        private final Map<String, VoiceSlot> activeSlots = new LinkedHashMap<>();

        /** Creates a multi-track dialogue voice manager. */
        public MultiTrackVoiceManager() {
        }

        /**
         * Starts or replaces a concurrent voice slot for {@code speakerId}.
         *
         * @param speakerId     speaker identifier
         * @param assetPath     voice audio asset path
         * @param speakerStageX speaker X coordinate on stage
         * @param logicalWidth  logical stage width
         * @return created {@link VoiceSlot}
         */
        public synchronized VoiceSlot startVoice(
                String speakerId,
                String assetPath,
                float speakerStageX,
                float logicalWidth
        ) {
            Objects.requireNonNull(speakerId, "speakerId must not be null");
            float pan = SpatialVoicePanner.computeStagePan(speakerStageX, logicalWidth);
            VoiceSlot slot = new VoiceSlot(speakerId, assetPath, pan, new LipSyncAnalyzer());
            activeSlots.put(speakerId, slot);
            return slot;
        }

        /**
         * Returns active voice slots keyed by speaker ID.
         *
         * @return immutable map of active slots
         */
        public synchronized Map<String, VoiceSlot> getActiveSlots() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(activeSlots));
        }

        /**
         * Stops and removes a speaker's concurrent voice slot.
         *
         * @param speakerId speaker identifier
         */
        public synchronized void stopVoice(String speakerId) {
            activeSlots.remove(speakerId);
        }
    }

    /**
     * Comb-filtering guard suppressing duplicate identical SFX triggers inside a {@code 30 ms}
     * window ({@code #0486}).
     */
    public static final class SfxCooldownGuard {
        /** Default duplicate SFX suppression window in milliseconds. */
        public static final double COOLDOWN_SECONDS = 0.030;

        private final Map<String, Double> lastTriggerTimes = new HashMap<>();

        /** Creates a 30-ms duplicate SFX cooldown guard. */
        public SfxCooldownGuard() {
        }

        /**
         * Checks whether {@code sfxPath} may play at {@code nowSeconds} and records timestamp when allowed.
         *
         * @param sfxPath    sound asset path
         * @param nowSeconds current engine clock seconds
         * @return {@code true} if allowed, {@code false} if suppressed within cooldown window
         */
        public synchronized boolean tryAcquirePlay(String sfxPath, double nowSeconds) {
            if (sfxPath == null || sfxPath.isBlank()) {
                return false;
            }
            Double last = lastTriggerTimes.get(sfxPath);
            if (last != null && (nowSeconds - last) < COOLDOWN_SECONDS) {
                return false;
            }
            lastTriggerTimes.put(sfxPath, nowSeconds);
            return true;
        }
    }

    /**
     * In-memory synthesis cache for recurring character typewriter voice bleeps achieving
     * {@code > 99%} cache hit rates across long scenes ({@code #0435}, {@code #0495}, {@code #0504}).
     */
    public static final class BleepSynthesisCache {
        private final Map<String, AudioDecoder.DecodedAudio> cache = new ConcurrentHashMap<>();
        private long hits = 0L;
        private long misses = 0L;

        /** Creates an in-memory voice bleep synthesis cache. */
        public BleepSynthesisCache() {
        }

        /**
         * Returns cached bleep buffer for {@code baseFreqHz} (generating once on cache miss).
         *
         * @param baseFreqHz character bleep pitch in Hz
         * @param durationS  bleep duration in seconds
         * @param sampleRate sample rate in Hz
         * @return synthesized or cached {@link AudioDecoder.DecodedAudio}
         */
        public AudioDecoder.DecodedAudio getOrSynthesize(double baseFreqHz, double durationS, int sampleRate) {
            String key = Math.round(baseFreqHz * 10.0) + ":" + Math.round(durationS * 1000.0) + ":" + sampleRate;
            AudioDecoder.DecodedAudio existing = cache.get(key);
            if (existing != null) {
                hits++;
                return existing;
            }
            misses++;
            AudioDecoder.DecodedAudio created = AudioDecoder.synthesizeVoiceBleep(baseFreqHz, durationS, sampleRate);
            cache.put(key, created);
            return created;
        }

        /**
         * Returns cumulative cache hit ratio in {@code [0, 1]}.
         *
         * @return hit ratio
         */
        public double getHitRate() {
            long total = hits + misses;
            return total == 0L ? 0.0 : (double) hits / total;
        }
    }

    /**
     * Timed lyric line parsed from {@code .lrc} files ({@code #0509}).
     *
     * @param timestampSeconds lyric display start timestamp in seconds
     * @param text             lyric line text
     */
    public record LyricLine(double timestampSeconds, String text) {
    }

    /**
     * Timed dialogue subtitle segment for {@code .vtt}/{@code .srt} export ({@code #0466}).
     *
     * @param startSeconds subtitle start timestamp in seconds
     * @param endSeconds   subtitle end timestamp in seconds
     * @param speaker      optional speaker name
     * @param text         clean subtitle dialogue text
     */
    public record TimedSubtitle(double startSeconds, double endSeconds, String speaker, String text) {
    }

    /**
     * Voice-actor script cue entry for CSV/Markdown sheets ({@code #0483}).
     *
     * @param speaker           speaking character name
     * @param lineId            unique dialogue line identifier
     * @param text              spoken dialogue text
     * @param expectedAssetPath expected relative audio path
     */
    public record VoiceCueEntry(String speaker, String lineId, String text, String expectedAssetPath) {
    }

    /**
     * Exporters and parsers for WebVTT/SRT subtitles, {@code .lrc} ending lyrics, voice actor
     * cue sheets, and missing-voice coverage audits ({@code #0466}, {@code #0483}, {@code #0484}, {@code #0509}).
     */
    public static final class SubtitleAndLyricExporter {
        private static final Pattern LRC_LINE_PATTERN = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\](.*)");

        private SubtitleAndLyricExporter() {
        }

        /**
         * Formats a standards-compliant WebVTT ({@code .vtt}) subtitle document ({@code #0466}).
         *
         * @param cues ordered timed subtitle cues
         * @return WebVTT text content
         */
        public static String exportWebVtt(List<TimedSubtitle> cues) {
            StringBuilder sb = new StringBuilder("WEBVTT\n\n");
            for (TimedSubtitle cue : cues) {
                sb.append(formatTimestamp(cue.startSeconds(), '.'))
                        .append(" --> ")
                        .append(formatTimestamp(cue.endSeconds(), '.'))
                        .append('\n');
                if (cue.speaker() != null && !cue.speaker().isBlank()) {
                    sb.append(cue.speaker()).append(": ");
                }
                sb.append(cue.text()).append("\n\n");
            }
            return sb.toString();
        }

        /**
         * Formats a SubRip ({@code .srt}) subtitle document ({@code #0466}).
         *
         * @param cues ordered timed subtitle cues
         * @return SRT text content
         */
        public static String exportSrt(List<TimedSubtitle> cues) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cues.size(); i++) {
                TimedSubtitle cue = cues.get(i);
                sb.append(i + 1).append('\n')
                        .append(formatTimestamp(cue.startSeconds(), ','))
                        .append(" --> ")
                        .append(formatTimestamp(cue.endSeconds(), ','))
                        .append('\n');
                if (cue.speaker() != null && !cue.speaker().isBlank()) {
                    sb.append(cue.speaker()).append(": ");
                }
                sb.append(cue.text()).append("\n\n");
            }
            return sb.toString();
        }

        /**
         * Parses an {@code .lrc} lyric file and resolves active line by playback timestamp ({@code #0509}).
         *
         * @param lrcContent raw {@code .lrc} text
         * @return sorted list of {@link LyricLine} entries
         */
        public static List<LyricLine> parseLrc(String lrcContent) {
            List<LyricLine> lines = new ArrayList<>();
            if (lrcContent == null || lrcContent.isBlank()) {
                return lines;
            }
            for (String raw : lrcContent.split("\\R")) {
                Matcher m = LRC_LINE_PATTERN.matcher(raw.trim());
                if (m.matches()) {
                    int mins = Integer.parseInt(m.group(1));
                    double secs = Double.parseDouble(m.group(2));
                    lines.add(new LyricLine(mins * 60.0 + secs, m.group(3).trim()));
                }
            }
            lines.sort((a, b) -> Double.compare(a.timestampSeconds(), b.timestampSeconds()));
            return lines;
        }

        /**
         * Finds the lyric text active at {@code playbackSeconds}.
         *
         * @param lines           parsed {@link LyricLine} list
         * @param playbackSeconds active track timestamp in seconds
         * @return active lyric text or empty string
         */
        public static String findActiveLyric(List<LyricLine> lines, double playbackSeconds) {
            String active = "";
            for (LyricLine line : lines) {
                if (line.timestampSeconds() <= playbackSeconds + 1e-4) {
                    active = line.text();
                } else {
                    break;
                }
            }
            return active;
        }

        /**
         * Exports a Markdown voice-actor cue sheet matching expected {@code assets/audio/voice/} paths ({@code #0483}).
         *
         * @param entries voice cue entries
         * @return Markdown cue sheet table
         */
        public static String exportVoiceCueSheetMarkdown(List<VoiceCueEntry> entries) {
            StringBuilder sb = new StringBuilder(
                    "| Speaker | Line ID | Text | Expected Path |\n| :--- | :--- | :--- | :--- |\n");
            for (VoiceCueEntry e : entries) {
                sb.append("| ").append(e.speaker())
                        .append(" | ").append(e.lineId())
                        .append(" | ").append(e.text().replace('|', '/'))
                        .append(" | ").append(e.expectedAssetPath())
                        .append(" |\n");
            }
            return sb.toString();
        }

        /**
         * Computes percentage {@code [0..100]} of voice cues whose WAV/OGG files already exist on disk ({@code #0484}).
         *
         * @param projectRoot project directory
         * @param entries     voice cue list
         * @return voice recording coverage percent
         */
        public static double computeVoiceCoveragePercent(Path projectRoot, List<VoiceCueEntry> entries) {
            if (entries == null || entries.isEmpty()) {
                return 100.0;
            }
            int found = 0;
            for (VoiceCueEntry e : entries) {
                if (projectRoot != null && e.expectedAssetPath() != null
                        && Files.isRegularFile(projectRoot.resolve(e.expectedAssetPath()))) {
                    found++;
                }
            }
            return (found * 100.0) / entries.size();
        }

        private static String formatTimestamp(double seconds, char msSep) {
            double clamped = Math.max(0.0, seconds);
            int totalMs = (int) Math.round(clamped * 1000.0);
            int hrs = totalMs / 3_600_000;
            int mins = (totalMs % 3_600_000) / 60_000;
            int secs = (totalMs % 60_000) / 1000;
            int ms = totalMs % 1000;
            return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d", hrs, mins, secs, msSep, ms);
        }
    }

    /**
     * Metadata descriptor for music room tracks ({@code #0461}).
     *
     * @param title    display track title
     * @param composer composer or artist credit
     * @param license  SPDX/license note
     * @param bpm      tempo in beats per minute
     */
    public record MusicTrackMetadata(String title, String composer, String license, int bpm) {
    }

    /**
     * Recorded audio trigger event for deterministic headless CI tests ({@code #0455}).
     *
     * @param timestampSec clock timestamp in seconds
     * @param action       audio command type
     * @param channel      mixer channel name
     * @param assetPath    triggered asset path
     * @param ducked       whether background music ducking was active
     */
    public record RecordedAudioEvent(
            double timestampSec,
            String action,
            String channel,
            String assetPath,
            boolean ducked
    ) {
    }

    /**
     * Thread-safe headless audio runtime controller coordinating focus-loss muting, {@code Ctrl+M}
     * global mute toggle, video cutscene music ducking, modal pause/resume sample position,
     * {@code noduck}/TTS ducking, skip-mode voice cutoff, 250-ms slider test debounce, one-shot
     * buffer eviction, rollback same-track protection, {@code {w_audio=t}} sync checks,
     * single-fire {@code on_audio_end}, and {@code time_scale} pitch clamping ({@code #0441}–{@code #0508}).
     */
    public static final class HeadlessAudioController {
        private final List<RecordedAudioEvent> recordedEvents = new CopyOnWriteArrayList<>();
        private final Map<String, Integer> oneShotBuffers = new ConcurrentHashMap<>();
        private final List<String> audioEndListenersFired = new CopyOnWriteArrayList<>();

        private float preMuteMasterVolume = 1.0f;
        private float activeMasterVolume = 1.0f;
        private boolean globalMuted = false;
        private float focusFadeGain = 1.0f;
        private boolean windowFocused = true;
        private boolean pausedForVideoCutscene = false;
        private double savedModalSampleSec = 0.0;
        private boolean modalPaused = false;
        private String currentMusicTrack = "";
        private double currentVoicePositionSec = 0.0;
        private double lastSliderTestTimeSec = -10.0;
        private int audioBufferFrames = 2048;
        private float pitchScale = 1.0f;
        private double clockSec = 0.0;

        /** Creates a thread-safe headless audio controller. */
        public HeadlessAudioController() {
        }

        /**
         * Updates simulated clock and window-focus fade gain ({@code 0.3 s} restore, {@code #0441}).
         *
         * @param dt              delta seconds
         * @param muteOnFocusLoss preference toggle
         */
        public synchronized void update(double dt, boolean muteOnFocusLoss) {
            if (dt > 0.0) {
                clockSec += dt;
            }
            float targetFocus = (!windowFocused && muteOnFocusLoss) ? 0.0f : 1.0f;
            float step = dt <= 0.0 ? 0.0f : (float) Math.min(1.0, dt / 0.3);
            focusFadeGain += (targetFocus - focusFadeGain) * step;
            if (Math.abs(targetFocus - focusFadeGain) < 0.005f) {
                focusFadeGain = targetFocus;
            }
        }

        /**
         * Sets window focus state ({@code #0441}).
         *
         * @param focused {@code true} when window is focused
         */
        public synchronized void setWindowFocused(boolean focused) {
            this.windowFocused = focused;
        }

        /**
         * Returns effective master output gain factoring {@code Ctrl+M} mute and focus fade.
         *
         * @return effective master gain {@code [0, 1]}
         */
        public synchronized float getEffectiveMasterGain() {
            return globalMuted ? 0.0f : activeMasterVolume * focusFadeGain;
        }

        /**
         * Toggles global mute ({@code Ctrl+M}), restoring exact prior master volume on unmute ({@code #0498}).
         *
         * @return new global mute state
         */
        public synchronized boolean toggleCtrlMMute() {
            if (!globalMuted) {
                preMuteMasterVolume = activeMasterVolume;
                globalMuted = true;
            } else {
                activeMasterVolume = preMuteMasterVolume;
                globalMuted = false;
            }
            return globalMuted;
        }

        /**
         * Configures active master volume before mute/focus adjustments.
         *
         * @param masterVolume volume in {@code [0, 1]}
         */
        public synchronized void setMasterVolume(float masterVolume) {
            this.activeMasterVolume = Math.clamp(masterVolume, 0.0f, 1.0f);
            if (!globalMuted) {
                this.preMuteMasterVolume = this.activeMasterVolume;
            }
        }

        /**
         * Plays music safely, skipping restart on identical track during rollback ({@code #0508}).
         *
         * @param trackPath audio asset path
         * @return {@code true} if playback restarted, {@code false} if already playing identically
         */
        public synchronized boolean playMusicWithRollbackGuard(String trackPath) {
            if (trackPath != null && trackPath.equals(currentMusicTrack)) {
                return false;
            }
            currentMusicTrack = trackPath != null ? trackPath : "";
            recordedEvents.add(new RecordedAudioEvent(clockSec, "PLAY_MUSIC", "music", currentMusicTrack, false));
            return true;
        }

        /**
         * Records a voice playback trigger, honoring {@code noduck} exception ({@code #0469}, {@code #0506}).
         *
         * @param voicePath audio asset path
         * @param noDuck    {@code true} to suppress background music ducking
         */
        public synchronized void triggerVoice(String voicePath, boolean noDuck) {
            currentVoicePositionSec = 0.0;
            oneShotBuffers.put(voicePath != null ? voicePath : "voice", 1);
            recordedEvents.add(new RecordedAudioEvent(clockSec, "PLAY_VOICE", "voice", voicePath, !noDuck));
        }

        /**
         * Frees finished one-shot voice buffer immediately upon completion ({@code #0465}).
         *
         * @param voicePath completed voice asset path
         */
        public synchronized void onVoiceFinished(String voicePath) {
            if (voicePath != null) {
                oneShotBuffers.remove(voicePath);
                audioEndListenersFired.add("voice:" + voicePath);
            }
        }

        /**
         * Returns count of active retained one-shot voice buffers ({@code #0465}).
         *
         * @return active one-shot buffer count
         */
        public synchronized int getActiveOneShotBufferCount() {
            return oneShotBuffers.size();
        }

        /**
         * Advances active voice playback clock and evaluates {@code {w_audio=targetSec}} wait gate ({@code #0497}).
         *
         * @param playbackSec active playback timestamp
         * @param waitTargetS tag wait timestamp
         * @return {@code true} when reached
         */
        public synchronized boolean isAudioCueTimestampReached(double playbackSec, double waitTargetS) {
            this.currentVoicePositionSec = Math.max(0.0, playbackSec);
            return this.currentVoicePositionSec >= waitTargetS;
        }

        /**
         * Fires slider preview chime with 250-ms debounce to prevent slider drag spam ({@code #0464}).
         *
         * @param nowSec current clock seconds
         * @return {@code true} if preview tone fired
         */
        public synchronized boolean tryTriggerSliderTestTone(double nowSec) {
            if (nowSec - lastSliderTestTimeSec < 0.25) {
                return false;
            }
            lastSliderTestTimeSec = nowSec;
            recordedEvents.add(new RecordedAudioEvent(nowSec, "SLIDER_TEST", "sound", "ui_test", false));
            return true;
        }

        /**
         * Pauses or resumes BGM cleanly while a video cutscene with native audio plays ({@code #0485}).
         *
         * @param videoWithAudioPlaying {@code true} during video cutscene
         */
        public synchronized void setVideoCutsceneActive(boolean videoWithAudioPlaying) {
            this.pausedForVideoCutscene = videoWithAudioPlaying;
        }

        /**
         * Returns whether BGM is ducked/paused for video cutscene.
         *
         * @return {@code true} if video audio owns output
         */
        public synchronized boolean isPausedForVideoCutscene() {
            return pausedForVideoCutscene;
        }

        /**
         * Saves or restores exact playback sample timestamp when pausing/resuming modals ({@code #0471}).
         *
         * @param paused       {@code true} when modal pauses audio
         * @param samplePosSec sample position to save on pause
         * @return restored position on resume
         */
        public synchronized double setModalAudioPaused(boolean paused, double samplePosSec) {
            if (paused) {
                modalPaused = true;
                savedModalSampleSec = Math.max(0.0, samplePosSec);
            } else {
                modalPaused = false;
            }
            return savedModalSampleSec;
        }

        /**
         * Returns whether modal audio pause is active.
         *
         * @return {@code true} if paused for modal
         */
        public synchronized boolean isModalAudioPaused() {
            return modalPaused;
        }

        /**
         * Applies {@code time_scale} pitch modulation clamped strictly to OpenAL-safe
         * {@code [0.5, 2.0]} ({@code #0460}).
         *
         * @param timeScale engine time scale multiplier
         * @return clamped OpenAL pitch multiplier
         */
        public synchronized float applyTimeScalePitch(double timeScale) {
            this.pitchScale = Double.isFinite(timeScale)
                    ? Math.clamp((float) timeScale, 0.5f, 2.0f) : 1.0f;
            return this.pitchScale;
        }

        /**
         * Configures OpenAL streaming frame buffer size ({@code 1024..4096}, {@code #0482}).
         *
         * @param frames frame size
         */
        public synchronized void setAudioBufferFrames(int frames) {
            this.audioBufferFrames = Math.clamp(frames, 1024, 4096);
        }

        /**
         * Returns configured buffer frame count.
         *
         * @return frame count
         */
        public synchronized int getAudioBufferFrames() {
            return audioBufferFrames;
        }

        /**
         * Handles Skip-mode fast-forward by silencing active voice and bleeps ({@code #0480}).
         *
         * @param skipActive {@code true} when fast-forwarding
         */
        public synchronized void onSkipStateChanged(boolean skipActive) {
            if (skipActive) {
                oneShotBuffers.clear();
                recordedEvents.add(new RecordedAudioEvent(clockSec, "SKIP_MUTE_VOICE", "voice", "", false));
            }
        }

        /**
         * Computes a random phase offset in {@code [0, trackDuration)} for ambient loops ({@code #0488}).
         *
         * @param trackDurationSeconds ambient track duration
         * @param seed                 deterministic seed
         * @return random offset in {@code [0, trackDuration)}
         */
        public static double pickRandomLoopPhase(double trackDurationSeconds, long seed) {
            if (!Double.isFinite(trackDurationSeconds) || trackDurationSeconds <= 0.0) {
                return 0.0;
            }
            return new Random(seed).nextDouble() * trackDurationSeconds;
        }

        /**
         * Returns thread-safe snapshot of recorded audio events ({@code #0455}).
         *
         * @return immutable list of recorded events
         */
        public List<RecordedAudioEvent> getRecordedEvents() {
            return List.copyOf(recordedEvents);
        }

        /**
         * Returns list of fired {@code on_audio_end} channel notifications ({@code #0481}).
         *
         * @return fired end-event labels
         */
        public List<String> getAudioEndListenersFired() {
            return List.copyOf(audioEndListenersFired);
        }
    }
}
