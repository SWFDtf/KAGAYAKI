package de.swfdtf.audio;

import de.swfdtf.core.EngineLogger;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.state.LocalizationManager;
import de.swfdtf.state.PreferencesState;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Central LWJGL 3 OpenAL audio engine and virtual mixer for KAGAYAKI.
 *
 * <p>Manages the four standard VN audio channels ({@code music}, {@code sound}, {@code voice},
 * {@code ambient}), gracefully degrades to deterministic headless virtual-mixer mode when
 * running headless or without an available audio device, resolves localized {@code tl/<lang>/}
 * audio assets, synchronizes mixer volumes with {@link PreferencesState}, implements
 * {@link ScriptRuntime.AudioBridge}, synthesizes character voice bleeps, exposes real-time
 * spectrum/RMS telemetry, and supports full channel snapshot/restore for M4 rollback.</p>
 */
public final class AudioEngine implements AutoCloseable, ScriptRuntime.AudioBridge {

    /** Standard background music channel name. */
    public static final String CHANNEL_MUSIC = "music";

    /** Standard sound-effects channel name. */
    public static final String CHANNEL_SOUND = "sound";

    /** Standard character voice channel name. */
    public static final String CHANNEL_VOICE = "voice";

    /** Standard ambient/environment audio channel name. */
    public static final String CHANNEL_AMBIENT = "ambient";

    /** Default voice bleep duration in seconds. */
    public static final double DEFAULT_VOICE_BLEEP_DURATION = 0.055;

    /** Default synthesis sample rate in Hz. */
    public static final int DEFAULT_SYNTH_SAMPLE_RATE = 44100;

    private static final String LOG_CATEGORY = "AudioEngine";
    private static final String BLEEP_PREFIX = "__bleep__:";

    private final EngineLogger logger;
    private final boolean headless;
    private final Map<String, AudioChannel> channels = new LinkedHashMap<>();
    private final Map<String, AudioDecoder.DecodedAudio> decodedFileCache = new LinkedHashMap<>();
    private final Map<String, AudioDecoder.DecodedAudio> registeredMemoryAudio = new LinkedHashMap<>();
    private final Map<String, AudioDecoder.DecodedAudio> bleepCache = new LinkedHashMap<>();

    private Path projectRoot;
    private PreferencesState preferencesState;
    private LocalizationManager localizationManager;

    private long alDevice = MemoryUtil.NULL;
    private long alContext = MemoryUtil.NULL;
    private boolean initialized = false;
    private boolean openAlActive = false;
    private boolean disposed = false;

    /**
     * Creates an {@code AudioEngine} with default preferences, no project root, and hardware OpenAL enabled.
     */
    public AudioEngine() {
        this(null, new PreferencesState(), null, new EngineLogger(), false);
    }

    /**
     * Creates an {@code AudioEngine} rooted at {@code projectRoot} with explicit headless mode.
     *
     * @param projectRoot project directory for resolving relative audio paths (nullable)
     * @param headless    {@code true} to force virtual-mixer mode without touching native OpenAL devices
     */
    public AudioEngine(Path projectRoot, boolean headless) {
        this(projectRoot, new PreferencesState(), null, new EngineLogger(), headless);
    }

    /**
     * Creates a fully wired {@code AudioEngine}.
     *
     * @param projectRoot         project root directory (nullable)
     * @param preferencesState    user audio preferences (defaults to fresh {@link PreferencesState} if {@code null})
     * @param localizationManager optional localization manager for {@code tl/<lang>/} audio overrides (nullable)
     * @param logger              engine logger (must not be {@code null})
     * @param headless            {@code true} to skip native OpenAL initialization and run purely in virtual-mixer mode
     */
    public AudioEngine(
            Path projectRoot,
            PreferencesState preferencesState,
            LocalizationManager localizationManager,
            EngineLogger logger,
            boolean headless
    ) {
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
        this.projectRoot = projectRoot != null ? projectRoot.toAbsolutePath().normalize() : null;
        this.preferencesState = preferencesState != null ? preferencesState : new PreferencesState();
        this.localizationManager = localizationManager;
        this.headless = headless;

        registerStandardChannels();
        syncVolumesFromPreferences();
    }

    private void registerStandardChannels() {
        channels.put(CHANNEL_MUSIC, new AudioChannel(CHANNEL_MUSIC, false));
        channels.put(CHANNEL_SOUND, new AudioChannel(CHANNEL_SOUND, false));
        channels.put(CHANNEL_VOICE, new AudioChannel(CHANNEL_VOICE, false));
        channels.put(CHANNEL_AMBIENT, new AudioChannel(CHANNEL_AMBIENT, false));
    }

    /**
     * Initializes LWJGL 3 OpenAL ({@link ALC10} / {@link AL10}) unless {@code headless == true}
     * or no audio output device is available, in which case the engine falls back cleanly to
     * virtual-mixer mode ({@link #isOpenAlActive()}{@code == false}).
     */
    public synchronized void init() {
        if (disposed) {
            throw new IllegalStateException("AudioEngine has already been disposed");
        }
        if (initialized) {
            return;
        }

        if (headless) {
            this.openAlActive = false;
            logger.info(LOG_CATEGORY, "Initialized in headless virtual-mixer mode.");
        } else {
            initializeOpenAl();
        }

        for (AudioChannel channel : channels.values()) {
            channel.setOpenAlEnabled(this.openAlActive);
        }
        syncVolumesFromPreferences();
        this.initialized = true;
    }

    private void initializeOpenAl() {
        long device = MemoryUtil.NULL;
        long context = MemoryUtil.NULL;
        try {
            device = ALC10.alcOpenDevice((ByteBuffer) null);
            if (device == MemoryUtil.NULL) {
                logger.warn(LOG_CATEGORY, "No OpenAL audio device found; running in virtual-mixer mode.");
                this.openAlActive = false;
                return;
            }

            ALCCapabilities deviceCaps = ALC.createCapabilities(device);
            context = ALC10.alcCreateContext(device, (IntBuffer) null);
            if (context == MemoryUtil.NULL || !ALC10.alcMakeContextCurrent(context)) {
                if (context != MemoryUtil.NULL) {
                    ALC10.alcDestroyContext(context);
                }
                ALC10.alcCloseDevice(device);
                logger.warn(LOG_CATEGORY, "Failed to activate OpenAL context; running in virtual-mixer mode.");
                this.openAlActive = false;
                return;
            }

            AL.createCapabilities(deviceCaps);
            AL10.alListenerf(AL10.AL_GAIN, 1.0f);
            this.alDevice = device;
            this.alContext = context;
            this.openAlActive = true;
            logger.info(LOG_CATEGORY, "Initialized OpenAL hardware audio backend.");
        } catch (Throwable t) {
            if (context != MemoryUtil.NULL) {
                try {
                    ALC10.alcMakeContextCurrent(MemoryUtil.NULL);
                    ALC10.alcDestroyContext(context);
                } catch (Throwable ignored) {
                }
            }
            if (device != MemoryUtil.NULL) {
                try {
                    ALC10.alcCloseDevice(device);
                } catch (Throwable ignored) {
                }
            }
            this.alDevice = MemoryUtil.NULL;
            this.alContext = MemoryUtil.NULL;
            this.openAlActive = false;
            logger.warn(
                    LOG_CATEGORY,
                    "OpenAL unavailable (" + t.getMessage() + "); falling back to virtual-mixer mode."
            );
        }
    }

    /**
     * Returns the {@link AudioChannel} for {@code name} (case-insensitive), creating a new
     * custom channel if {@code name} is not one of the four pre-registered standard channels.
     *
     * @param name channel name (must not be {@code null} or blank)
     * @return matching {@link AudioChannel}
     */
    public synchronized AudioChannel getChannel(String name) {
        Objects.requireNonNull(name, "name must not be null");
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Channel name must not be blank");
        }
        AudioChannel existing = channels.get(normalized);
        if (existing != null) {
            return existing;
        }
        AudioChannel created = new AudioChannel(normalized, openAlActive);
        created.setChannelMixGain(computeChannelMixGain(normalized));
        channels.put(normalized, created);
        return created;
    }

    /**
     * Returns an unmodifiable snapshot map of all registered channels keyed by lowercase name.
     *
     * @return map of channel name to {@link AudioChannel}
     */
    public synchronized Map<String, AudioChannel> getChannels() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(channels));
    }

    /**
     * Resolves an audio asset path against {@link #getProjectRoot()} and optional
     * {@link LocalizationManager} ({@code tl/<lang>/...} fallback).
     *
     * @param assetPath relative or absolute audio asset path
     * @return resolved regular file {@link Path}, or {@code null} if not found on disk
     */
    public synchronized Path resolveAudioPath(String assetPath) {
        if (assetPath == null || assetPath.isBlank()) {
            return null;
        }
        String trimmed = assetPath.trim();
        try {
            Path directPath = Path.of(trimmed);
            if (directPath.isAbsolute() && Files.isRegularFile(directPath)) {
                return directPath.normalize();
            }
        } catch (InvalidPathException ignored) {
            return null;
        }

        String normalized = normalizeRelativeAssetPath(trimmed);

        if (projectRoot != null) {
            if (localizationManager != null) {
                String localizedDirect = localizationManager.resolveLocalizedAsset(projectRoot, normalized);
                Path localizedPath = tryRegularFile(projectRoot, localizedDirect);
                if (localizedPath != null) {
                    return localizedPath;
                }
                if (!normalized.startsWith("assets/")) {
                    String withAssets = "assets/" + normalized;
                    String localizedWithAssets = localizationManager.resolveLocalizedAsset(projectRoot, withAssets);
                    Path localizedWithAssetsPath = tryRegularFile(projectRoot, localizedWithAssets);
                    if (localizedWithAssetsPath != null) {
                        return localizedWithAssetsPath;
                    }
                }
            }

            Path direct = tryRegularFile(projectRoot, normalized);
            if (direct != null) {
                return direct;
            }
            if (!normalized.startsWith("assets/")) {
                Path underAssets = tryRegularFile(projectRoot, "assets/" + normalized);
                if (underAssets != null) {
                    return underAssets;
                }
            }
        }

        try {
            Path standalone = Path.of(assetPath.trim());
            if (Files.isRegularFile(standalone)) {
                return standalone.toAbsolutePath().normalize();
            }
        } catch (InvalidPathException ignored) {
            return null;
        }
        return null;
    }

    /**
     * Registers a decoded audio buffer in memory under {@code assetPath} so it can be played
     * via {@link #play} or {@link ScriptRuntime.AudioBridge} without reading from disk.
     *
     * @param assetPath logical asset path key (must not be {@code null} or blank)
     * @param audio     decoded audio buffer (must not be {@code null})
     */
    public synchronized void registerInMemoryAudio(String assetPath, AudioDecoder.DecodedAudio audio) {
        Objects.requireNonNull(assetPath, "assetPath must not be null");
        Objects.requireNonNull(audio, "audio must not be null");
        String trimmed = assetPath.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("assetPath must not be blank");
        }
        registeredMemoryAudio.put(trimmed, audio);
    }

    /**
     * Synchronizes all channel mix gains with the active {@link PreferencesState}
     * ({@code masterVolume * channelVolume}).
     */
    public synchronized void syncVolumesFromPreferences() {
        for (Map.Entry<String, AudioChannel> entry : channels.entrySet()) {
            entry.getValue().setChannelMixGain(computeChannelMixGain(entry.getKey()));
        }
    }

    /**
     * Returns a {@link ScriptRuntime.AudioBridge} backed by this {@code AudioEngine}.
     *
     * @return {@link ScriptRuntime.AudioBridge} instance
     */
    public ScriptRuntime.AudioBridge createScriptAudioBridge() {
        return this;
    }

    /**
     * Plays an audio asset on the specified channel, clearing any previously queued tracks on that channel.
     *
     * @param ch     channel name ({@code music}, {@code sound}, {@code voice}, {@code ambient}, etc.)
     * @param path   asset path
     * @param loop   whether playback loops continuously
     * @param fadeIn fade-in / crossfade duration in seconds
     * @param vol    target track volume in {@code [0, 1]}
     */
    @Override
    public synchronized void play(String ch, String path, boolean loop, double fadeIn, double vol) {
        if (disposed) {
            return;
        }
        syncVolumesFromPreferences();
        String targetChannel = (ch == null || ch.isBlank()) ? CHANNEL_SOUND : ch;
        AudioChannel channel = getChannel(targetChannel);
        channel.clearQueue();
        AudioDecoder.DecodedAudio decoded = loadOrResolveAudio(path);
        channel.play(path, decoded, loop, Math.max(0.0, fadeIn), (float) vol);
    }

    /**
     * Appends one or more audio asset paths to the specified channel's playback queue.
     *
     * @param ch    channel name
     * @param paths ordered asset paths to queue
     */
    @Override
    public synchronized void queue(String ch, List<String> paths) {
        if (disposed || paths == null || paths.isEmpty()) {
            return;
        }
        String targetChannel = (ch == null || ch.isBlank()) ? CHANNEL_MUSIC : ch;
        getChannel(targetChannel).queue(paths);
    }

    /**
     * Stops playback on {@code ch} (or all channels if {@code "all"}) with optional fade-out.
     *
     * @param ch      channel name (or {@code "all"})
     * @param fadeOut fade-out duration in seconds ({@code <= 0} stops immediately)
     */
    @Override
    public synchronized void stop(String ch, double fadeOut) {
        if (disposed) {
            return;
        }
        double safeFadeOut = Math.max(0.0, fadeOut);
        if (ch == null || ch.isBlank() || "all".equalsIgnoreCase(ch.trim())) {
            for (AudioChannel channel : channels.values()) {
                channel.stop(safeFadeOut);
            }
            return;
        }
        getChannel(ch).stop(safeFadeOut);
    }

    /**
     * Plays a character voice line on the {@code voice} channel.
     *
     * @param path voice audio asset path
     */
    @Override
    public synchronized void voice(String path) {
        if (disposed) {
            return;
        }
        syncVolumesFromPreferences();
        AudioChannel voiceChannel = getChannel(CHANNEL_VOICE);
        voiceChannel.clearQueue();
        AudioDecoder.DecodedAudio decoded = loadOrResolveAudio(path);
        voiceChannel.play(path, decoded, false, 0.0, 1.0f);
    }

    /**
     * Synthesizes and plays a short character voice bleep on the {@code voice} channel.
     *
     * @param freqHz fundamental bleep frequency in Hz ({@code > 0})
     */
    public synchronized void playVoiceBleep(double freqHz) {
        playVoiceBleep(freqHz, DEFAULT_VOICE_BLEEP_DURATION);
    }

    /**
     * Synthesizes and plays a character voice bleep with custom duration on the {@code voice} channel.
     *
     * @param freqHz          fundamental bleep frequency in Hz ({@code > 0})
     * @param durationSeconds bleep duration in seconds ({@code > 0})
     */
    public synchronized void playVoiceBleep(double freqHz, double durationSeconds) {
        if (disposed) {
            return;
        }
        if (!Double.isFinite(freqHz) || freqHz <= 0.0) {
            throw new IllegalArgumentException("freqHz must be finite and > 0, got: " + freqHz);
        }
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0.0) {
            throw new IllegalArgumentException("durationSeconds must be finite and > 0, got: " + durationSeconds);
        }
        syncVolumesFromPreferences();
        String key = buildBleepKey(freqHz, durationSeconds);
        AudioDecoder.DecodedAudio bleep = bleepCache.computeIfAbsent(
                key,
                ignored -> AudioDecoder.synthesizeVoiceBleep(freqHz, durationSeconds, DEFAULT_SYNTH_SAMPLE_RATE)
        );
        AudioChannel voiceChannel = getChannel(CHANNEL_VOICE);
        voiceChannel.clearQueue();
        voiceChannel.play(key, bleep, false, 0.0, 1.0f);
    }

    /**
     * Extracts real-time logarithmic frequency spectrum bands in {@code [0, 1]} from the
     * {@code music} channel for Music Room visualization.
     *
     * @param bandCount number of frequency bands ({@code > 0})
     * @return normalized spectrum array of length {@code bandCount}
     */
    public synchronized float[] getMusicSpectrumBands(int bandCount) {
        return getChannel(CHANNEL_MUSIC).getCurrentSpectrumBands(bandCount);
    }

    /**
     * Returns the real-time RMS amplitude of the {@code voice} channel for lip-sync animation.
     *
     * @return voice RMS amplitude in {@code [0, 1]}
     */
    public synchronized float getActiveVoiceRms() {
        return getChannel(CHANNEL_VOICE).getCurrentRms();
    }

    /**
     * Captures deterministic playback snapshots for every managed channel (used for M4 rollback and saves).
     *
     * @return immutable map of channel name to {@link AudioChannel.ChannelSnapshot}
     */
    public synchronized Map<String, AudioChannel.ChannelSnapshot> snapshotAllChannels() {
        Map<String, AudioChannel.ChannelSnapshot> snapshots = new LinkedHashMap<>();
        for (Map.Entry<String, AudioChannel> entry : channels.entrySet()) {
            snapshots.put(entry.getKey(), entry.getValue().captureSnapshot());
        }
        return Collections.unmodifiableMap(snapshots);
    }

    /**
     * Restores all channel playback states from a previously captured snapshot map.
     * Channels missing from {@code snapshots} are stopped immediately.
     *
     * @param snapshots map of channel name to {@link AudioChannel.ChannelSnapshot} (must not be {@code null})
     */
    public synchronized void restoreAllChannels(Map<String, AudioChannel.ChannelSnapshot> snapshots) {
        Objects.requireNonNull(snapshots, "snapshots must not be null");
        if (disposed) {
            return;
        }
        syncVolumesFromPreferences();

        for (Map.Entry<String, AudioChannel.ChannelSnapshot> entry : snapshots.entrySet()) {
            AudioChannel channel = getChannel(entry.getKey());
            AudioChannel.ChannelSnapshot snap = entry.getValue();
            if (snap == null) {
                channel.stop(0.0);
                continue;
            }
            if (snap.playing() && snap.assetPath() != null) {
                AudioDecoder.DecodedAudio audio = loadOrResolveAudio(snap.assetPath());
                channel.restoreSnapshot(snap, audio);
            } else {
                channel.restoreSnapshot(snap);
            }
        }

        for (Map.Entry<String, AudioChannel> entry : channels.entrySet()) {
            if (!snapshots.containsKey(entry.getKey())) {
                entry.getValue().stop(0.0);
            }
        }
    }

    /**
     * Advances all audio channels by {@code dt} seconds, syncs preference volume changes,
     * and starts queued tracks whenever an idle channel has pending entries.
     *
     * @param dt elapsed time step in seconds ({@code >= 0})
     */
    public synchronized void update(double dt) {
        if (disposed) {
            return;
        }
        syncVolumesFromPreferences();
        for (AudioChannel channel : channels.values()) {
            channel.update(dt);
            if (!channel.isPlaying()) {
                Optional<String> nextQueued = channel.pollNextQueued();
                if (nextQueued.isPresent()) {
                    String nextPath = nextQueued.get();
                    AudioDecoder.DecodedAudio nextAudio = loadOrResolveAudio(nextPath);
                    channel.play(nextPath, nextAudio, false, 0.0, 1.0f);
                }
            }
        }
    }

    /** @return {@code true} if native OpenAL output was successfully initialized and is active */
    public synchronized boolean isOpenAlActive() {
        return openAlActive;
    }

    /** @return {@code true} if {@link #init()} has been called */
    public synchronized boolean isInitialized() {
        return initialized;
    }

    /** @return {@code true} if {@link #dispose()} has been called */
    public synchronized boolean isDisposed() {
        return disposed;
    }

    /** @return {@code true} if configured to run headless without hardware OpenAL */
    public boolean isHeadless() {
        return headless;
    }

    /** @return configured project root directory, or {@code null} */
    public synchronized Path getProjectRoot() {
        return projectRoot;
    }

    /**
     * Updates the project root used to resolve relative audio asset paths and clears the file decode cache.
     *
     * @param projectRoot project root directory (nullable)
     */
    public synchronized void setProjectRoot(Path projectRoot) {
        this.projectRoot = projectRoot != null ? projectRoot.toAbsolutePath().normalize() : null;
        this.decodedFileCache.clear();
    }

    /** @return attached {@link PreferencesState} */
    public synchronized PreferencesState getPreferencesState() {
        return preferencesState;
    }

    /**
     * Attaches a {@link PreferencesState} and synchronizes channel volumes immediately.
     *
     * @param preferencesState preferences instance (defaults to fresh instance if {@code null})
     */
    public synchronized void setPreferencesState(PreferencesState preferencesState) {
        this.preferencesState = preferencesState != null ? preferencesState : new PreferencesState();
        syncVolumesFromPreferences();
    }

    /** @return attached {@link LocalizationManager}, or {@code null} */
    public synchronized LocalizationManager getLocalizationManager() {
        return localizationManager;
    }

    /**
     * Attaches an optional {@link LocalizationManager} for {@code tl/<lang>/} localized audio lookup.
     *
     * @param localizationManager localization manager (nullable)
     */
    public synchronized void setLocalizationManager(LocalizationManager localizationManager) {
        this.localizationManager = localizationManager;
    }

    /** @return structured logger used by this audio engine */
    public EngineLogger getLogger() {
        return logger;
    }

    /**
     * Stops all channels, clears caches, and cleanly releases any native OpenAL context and device.
     */
    public synchronized void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;

        for (AudioChannel channel : channels.values()) {
            channel.dispose();
        }
        decodedFileCache.clear();
        registeredMemoryAudio.clear();
        bleepCache.clear();

        if (openAlActive) {
            try {
                ALC10.alcMakeContextCurrent(MemoryUtil.NULL);
                if (alContext != MemoryUtil.NULL) {
                    ALC10.alcDestroyContext(alContext);
                }
                if (alDevice != MemoryUtil.NULL) {
                    ALC10.alcCloseDevice(alDevice);
                }
            } catch (Throwable t) {
                logger.warn(LOG_CATEGORY, "Error during OpenAL teardown: " + t.getMessage());
            } finally {
                alContext = MemoryUtil.NULL;
                alDevice = MemoryUtil.NULL;
                openAlActive = false;
            }
        }
    }

    /**
     * Disposes all audio resources; alias for {@link #dispose()}.
     */
    @Override
    public void close() {
        dispose();
    }

    // ---- Internal Helpers ----

    private float computeChannelMixGain(String normalizedChannelName) {
        if (preferencesState == null) {
            return 1.0f;
        }
        float master = preferencesState.getMasterVolume();
        float chanVol = switch (normalizedChannelName) {
            case CHANNEL_MUSIC -> preferencesState.getMusicVolume();
            case CHANNEL_VOICE -> preferencesState.getVoiceVolume();
            case CHANNEL_AMBIENT -> preferencesState.getAmbientVolume();
            case CHANNEL_SOUND -> preferencesState.getSoundVolume();
            default -> preferencesState.getSoundVolume();
        };
        return Math.clamp(master * chanVol, 0.0f, 1.0f);
    }

    private AudioDecoder.DecodedAudio loadOrResolveAudio(String assetPath) {
        if (assetPath == null || assetPath.isBlank()) {
            return null;
        }
        String trimmed = assetPath.trim();
        if (trimmed.startsWith(BLEEP_PREFIX)) {
            AudioDecoder.DecodedAudio cachedBleep = bleepCache.get(trimmed);
            if (cachedBleep != null) {
                return cachedBleep;
            }
        }
        AudioDecoder.DecodedAudio registered = registeredMemoryAudio.get(trimmed);
        if (registered != null) {
            return registered;
        }

        Path resolved = resolveAudioPath(trimmed);
        if (resolved == null) {
            logger.warn(LOG_CATEGORY, "Missing audio asset: " + assetPath);
            return null;
        }
        String cacheKey = resolved.toString();
        AudioDecoder.DecodedAudio cached = decodedFileCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        try {
            AudioDecoder.DecodedAudio decoded = AudioDecoder.decode(resolved);
            decodedFileCache.put(cacheKey, decoded);
            return decoded;
        } catch (IOException e) {
            logger.warn(LOG_CATEGORY, "Failed to decode audio asset '" + resolved + "': " + e.getMessage());
            return null;
        }
    }

    private static String normalizeRelativeAssetPath(String assetPath) {
        String normalized = assetPath.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static Path tryRegularFile(Path root, String relativePath) {
        if (root == null || relativePath == null || relativePath.isBlank()) {
            return null;
        }
        try {
            Path resolved = root.resolve(relativePath).normalize();
            return Files.isRegularFile(resolved) ? resolved : null;
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private static String buildBleepKey(double freqHz, double durationSeconds) {
        long freqTenths = Math.round(freqHz * 10.0);
        long durMillis = Math.round(durationSeconds * 1000.0);
        return BLEEP_PREFIX + freqTenths + ":" + durMillis;
    }
}
