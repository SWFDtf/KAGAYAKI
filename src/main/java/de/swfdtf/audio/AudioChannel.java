package de.swfdtf.audio;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Named logical audio mixer channel (such as {@code music}, {@code sound}, {@code voice}, or
 * {@code ambient}) supporting fade-in, fade-out, dual-slot crossfading, queued playlists,
 * optional {@link AudioDsp.BiquadFilter} DSP filtering, real-time RMS and spectrum extraction,
 * deterministic rollback snapshots, and optional LWJGL OpenAL source management.
 *
 * <p>When OpenAL is disabled or unavailable (headless environments), all OpenAL operations become
 * safe no-ops while playback time, fade envelopes, RMS, and spectrum metrics continue updating
 * deterministically on {@link #update(double)}.</p>
 */
public final class AudioChannel {

    private static final int ANALYSIS_WINDOW_SAMPLES = 1024;
    private static final int MAX_RECENT_AUDIO_CACHE = 8;

    /**
     * Immutable rollback and save/load snapshot of a channel's playback state.
     *
     * @param assetPath       active or last-played asset path (nullable)
     * @param loop            whether the active track loops
     * @param targetVolume    requested track volume in {@code [0, 1]}
     * @param playing         whether playback is actively running (not stopped or fading out to stop)
     * @param playbackSeconds current playback cursor position in seconds
     */
    public record ChannelSnapshot(
            String assetPath,
            boolean loop,
            float targetVolume,
            boolean playing,
            double playbackSeconds
    ) {
        /** Normalizes non-finite volume and negative playback cursor values. */
        public ChannelSnapshot {
            if (!Float.isFinite(targetVolume)) {
                targetVolume = 1.0f;
            }
            targetVolume = Math.clamp(targetVolume, 0.0f, 1.0f);
            if (!Double.isFinite(playbackSeconds) || playbackSeconds < 0.0) {
                playbackSeconds = 0.0;
            }
        }
    }

    private static final class TrackSlot {
        String assetPath;
        AudioDecoder.DecodedAudio audio;
        OggVorbisStream stream;
        float[] unfilteredMono;
        float[] filteredMono;
        boolean loop;
        double loopStartSeconds;
        float targetVolume;
        double playbackSeconds;
        boolean playing;

        float fadeGain;
        float fadeStartGain;
        float fadeTargetGain;
        double fadeDurationSeconds;
        double fadeElapsedSeconds;
        boolean equalPowerFade;
        boolean stopWhenFadeEnds;

        int alSourceId;
        int alBufferId;
    }

    private final String name;
    private final Deque<String> queuedPaths = new ArrayDeque<>();
    private final Map<String, AudioDecoder.DecodedAudio> recentAudioCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, AudioDecoder.DecodedAudio> eldest) {
            return size() > MAX_RECENT_AUDIO_CACHE;
        }
    };

    private volatile boolean openAlEnabled;
    private float channelMixGain = 1.0f;
    private float duckGain = 1.0f;
    private boolean equalPowerCrossfade = false;
    private AudioDsp.BiquadFilter filter;

    private TrackSlot primary;
    private TrackSlot outgoing;

    /**
     * Creates a channel in headless virtual-mixer mode ({@code openAlEnabled = false}).
     *
     * @param name logical channel name (must not be {@code null} or blank)
     */
    public AudioChannel(String name) {
        this(name, false);
    }

    /**
     * Creates a channel with explicit OpenAL activation flag.
     *
     * @param name          logical channel name (must not be {@code null} or blank)
     * @param openAlEnabled {@code true} to allocate and drive OpenAL sources/buffers when an AL context is current
     */
    public AudioChannel(String name, boolean openAlEnabled) {
        Objects.requireNonNull(name, "name must not be null");
        String trimmed = name.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Channel name must not be blank");
        }
        this.name = trimmed;
        this.openAlEnabled = openAlEnabled;
    }

    /**
     * Starts playing an audio clip on this channel, optionally crossfading from an active track
     * when {@code fadeInSeconds > 0}.
     *
     * @param assetPath     logical asset identifier or path (nullable)
     * @param audio         decoded audio payload (nullable for metadata-only headless fallback)
     * @param loop          whether playback loops continuously
     * @param fadeInSeconds fade-in / crossfade duration in seconds ({@code <= 0} for immediate)
     * @param targetVolume  per-clip target volume in {@code [0, 1]}
     */
    public synchronized void play(
            String assetPath,
            AudioDecoder.DecodedAudio audio,
            boolean loop,
            double fadeInSeconds,
            float targetVolume
    ) {
        play(assetPath, audio, loop, fadeInSeconds, targetVolume, 0.0);
    }

    /**
     * Starts playing an audio clip with an explicit {@code loopStartSeconds} loop restart point.
     *
     * @param assetPath        logical asset identifier or path (nullable)
     * @param audio            decoded audio payload (nullable for metadata-only headless fallback)
     * @param loop             whether playback loops continuously
     * @param fadeInSeconds    fade-in / crossfade duration in seconds ({@code <= 0} for immediate)
     * @param targetVolume     per-clip target volume in {@code [0, 1]}
     * @param loopStartSeconds loop restart offset in seconds ({@code >= 0})
     */
    public synchronized void play(
            String assetPath,
            AudioDecoder.DecodedAudio audio,
            boolean loop,
            double fadeInSeconds,
            float targetVolume,
            double loopStartSeconds
    ) {
        startSlot(assetPath, audio, null, loop, fadeInSeconds, targetVolume, loopStartSeconds, 0.0);
    }

    /**
     * Starts playing a chunk-streamed {@link OggVorbisStream} without materializing full PCM in heap.
     *
     * @param assetPath        logical asset identifier or path (nullable)
     * @param stream           open {@link OggVorbisStream} (ownership transferred to this channel)
     * @param loop             whether playback loops continuously
     * @param fadeInSeconds    fade-in / crossfade duration in seconds ({@code <= 0} for immediate)
     * @param targetVolume     per-clip target volume in {@code [0, 1]}
     * @param loopStartSeconds loop restart offset in seconds ({@code >= 0})
     */
    public synchronized void playStream(
            String assetPath,
            OggVorbisStream stream,
            boolean loop,
            double fadeInSeconds,
            float targetVolume,
            double loopStartSeconds
    ) {
        startSlot(assetPath, null, Objects.requireNonNull(stream, "stream must not be null"),
                loop, fadeInSeconds, targetVolume, loopStartSeconds, 0.0);
    }

    /**
     * Starts playing an audio clip immediately resumed at {@code startSeconds}.
     *
     * @param assetPath    logical asset identifier or path
     * @param audio        decoded audio payload (nullable)
     * @param loop         whether playback loops continuously
     * @param targetVolume target volume in {@code [0, 1]}
     * @param startSeconds resume cursor offset in seconds ({@code >= 0})
     */
    public synchronized void playAtOffset(
            String assetPath,
            AudioDecoder.DecodedAudio audio,
            boolean loop,
            float targetVolume,
            double startSeconds
    ) {
        startSlot(assetPath, audio, null, loop, 0.0, targetVolume, 0.0, Math.max(0.0, startSeconds));
    }

    private void startSlot(
            String assetPath,
            AudioDecoder.DecodedAudio audio,
            OggVorbisStream stream,
            boolean loop,
            double fadeInSeconds,
            float targetVolume,
            double loopStartSeconds,
            double startSeconds
    ) {
        if (!Double.isFinite(fadeInSeconds) || fadeInSeconds < 0.0) {
            throw new IllegalArgumentException("fadeInSeconds must be finite and >= 0, got: " + fadeInSeconds);
        }
        float clampedVolume = Float.isFinite(targetVolume) ? Math.clamp(targetVolume, 0.0f, 1.0f) : 1.0f;
        double safeLoopStart = (Double.isFinite(loopStartSeconds) && loopStartSeconds > 0.0) ? loopStartSeconds : 0.0;

        if (assetPath != null && audio != null) {
            recentAudioCache.put(assetPath, audio);
        }

        boolean crossfadeActive = fadeInSeconds > 0.0 && primary != null && primary.playing;
        if (crossfadeActive) {
            releaseSlotOpenAl(outgoing);
            outgoing = primary;
            outgoing.fadeStartGain = outgoing.fadeGain;
            outgoing.fadeTargetGain = 0.0f;
            outgoing.fadeDurationSeconds = fadeInSeconds;
            outgoing.fadeElapsedSeconds = 0.0;
            outgoing.equalPowerFade = equalPowerCrossfade;
            outgoing.stopWhenFadeEnds = true;
            primary = null;
        } else {
            releaseSlotOpenAl(outgoing);
            outgoing = null;
            releaseSlotOpenAl(primary);
            primary = null;
        }

        TrackSlot slot = new TrackSlot();
        slot.assetPath = assetPath;
        slot.audio = audio;
        slot.stream = stream;
        slot.unfilteredMono = audio != null ? audio.toMonoFloat() : null;
        slot.filteredMono = computeFilteredMono(slot.unfilteredMono, this.filter);
        slot.loop = loop;
        slot.loopStartSeconds = safeLoopStart;
        slot.targetVolume = clampedVolume;
        slot.playbackSeconds = Math.max(0.0, startSeconds);
        slot.playing = true;
        slot.equalPowerFade = crossfadeActive && equalPowerCrossfade;
        slot.stopWhenFadeEnds = false;

        if (audio != null && audio.durationSeconds() > 0.0 && slot.playbackSeconds >= audio.durationSeconds()) {
            slot.playbackSeconds = loop ? (slot.playbackSeconds % audio.durationSeconds()) : audio.durationSeconds();
        } else if (stream != null && stream.getDurationSeconds() > 0.0) {
            if (slot.playbackSeconds >= stream.getDurationSeconds()) {
                slot.playbackSeconds = loop
                        ? (slot.playbackSeconds % stream.getDurationSeconds())
                        : stream.getDurationSeconds();
            }
            if (slot.playbackSeconds > 0.0) {
                stream.seekSeconds(slot.playbackSeconds);
            }
        }

        if (fadeInSeconds > 0.0) {
            slot.fadeGain = 0.0f;
            slot.fadeStartGain = 0.0f;
            slot.fadeTargetGain = 1.0f;
            slot.fadeDurationSeconds = fadeInSeconds;
            slot.fadeElapsedSeconds = 0.0;
        } else {
            slot.fadeGain = 1.0f;
            slot.fadeStartGain = 1.0f;
            slot.fadeTargetGain = 1.0f;
            slot.fadeDurationSeconds = 0.0;
            slot.fadeElapsedSeconds = 0.0;
        }

        this.primary = slot;
        uploadAndStartOpenAl(slot, slot.playbackSeconds);
    }

    /**
     * Appends asset paths to this channel's playback queue.
     *
     * @param paths ordered list of asset paths to enqueue (must not be {@code null})
     */
    public synchronized void queue(List<String> paths) {
        Objects.requireNonNull(paths, "paths must not be null");
        for (String path : paths) {
            if (path != null && !path.isBlank()) {
                queuedPaths.addLast(path);
            }
        }
    }

    /**
     * Polls and removes the next queued asset path, if any.
     *
     * @return next queued asset path, or {@link Optional#empty()} if queue is empty
     */
    public synchronized Optional<String> pollNextQueued() {
        return Optional.ofNullable(queuedPaths.pollFirst());
    }

    /**
     * Clears all pending queued asset paths without stopping the currently playing track.
     */
    public synchronized void clearQueue() {
        queuedPaths.clear();
    }

    /**
     * Returns an immutable snapshot of the currently queued asset paths.
     *
     * @return list of queued paths in FIFO order
     */
    public synchronized List<String> getQueuedPaths() {
        return List.copyOf(queuedPaths);
    }

    /**
     * Stops playback on this channel immediately or after a linear fade-out, and clears any
     * queued follow-up paths.
     *
     * @param fadeOutSeconds fade-out duration in seconds ({@code <= 0} stops immediately)
     */
    public synchronized void stop(double fadeOutSeconds) {
        if (!Double.isFinite(fadeOutSeconds) || fadeOutSeconds < 0.0) {
            throw new IllegalArgumentException("fadeOutSeconds must be finite and >= 0, got: " + fadeOutSeconds);
        }
        queuedPaths.clear();

        if (fadeOutSeconds <= 0.0) {
            releaseSlotOpenAl(outgoing);
            outgoing = null;
            if (primary != null) {
                releaseSlotOpenAl(primary);
                primary.playing = false;
                primary.fadeGain = 0.0f;
                primary.fadeDurationSeconds = 0.0;
                primary.stopWhenFadeEnds = false;
                primary.playbackSeconds = 0.0;
            }
            return;
        }

        if (primary != null && primary.playing) {
            primary.fadeStartGain = primary.fadeGain;
            primary.fadeTargetGain = 0.0f;
            primary.fadeDurationSeconds = fadeOutSeconds;
            primary.fadeElapsedSeconds = 0.0;
            primary.stopWhenFadeEnds = true;
        }
        if (outgoing != null && outgoing.playing) {
            double remaining = outgoing.fadeDurationSeconds - outgoing.fadeElapsedSeconds;
            if (remaining > fadeOutSeconds) {
                outgoing.fadeStartGain = outgoing.fadeGain;
                outgoing.fadeTargetGain = 0.0f;
                outgoing.fadeDurationSeconds = fadeOutSeconds;
                outgoing.fadeElapsedSeconds = 0.0;
            }
        }
    }

    /**
     * Advances fade envelopes, crossfades, and playback cursors by {@code dt} seconds.
     *
     * @param dt elapsed time step in seconds ({@code >= 0})
     */
    public synchronized void update(double dt) {
        if (!Double.isFinite(dt) || dt < 0.0) {
            throw new IllegalArgumentException("dt must be finite and >= 0, got: " + dt);
        }

        if (outgoing != null) {
            boolean alive = updateSlot(outgoing, dt);
            if (!alive) {
                releaseSlotOpenAl(outgoing);
                outgoing = null;
            }
        }

        if (primary != null && primary.playing) {
            boolean alive = updateSlot(primary, dt);
            if (!alive) {
                releaseSlotOpenAl(primary);
                primary.playing = false;
                primary.fadeGain = 0.0f;
                primary.fadeDurationSeconds = 0.0;
                primary.stopWhenFadeEnds = false;
            }
        }
    }

    private boolean updateSlot(TrackSlot slot, double dt) {
        if (!slot.playing) {
            return false;
        }

        if (slot.fadeDurationSeconds > 0.0) {
            slot.fadeElapsedSeconds = Math.min(slot.fadeDurationSeconds, slot.fadeElapsedSeconds + dt);
            float t = (float) (slot.fadeElapsedSeconds / slot.fadeDurationSeconds);
            if (slot.equalPowerFade) {
                if (slot.fadeTargetGain > slot.fadeStartGain) {
                    float factor = (float) Math.sin(t * (Math.PI * 0.5));
                    slot.fadeGain = Math.clamp(
                            slot.fadeStartGain + (slot.fadeTargetGain - slot.fadeStartGain) * factor,
                            0.0f,
                            1.0f
                    );
                } else {
                    float factor = (float) Math.cos(t * (Math.PI * 0.5));
                    slot.fadeGain = Math.clamp(
                            slot.fadeTargetGain + (slot.fadeStartGain - slot.fadeTargetGain) * factor,
                            0.0f,
                            1.0f
                    );
                }
            } else {
                slot.fadeGain = Math.clamp(
                        slot.fadeStartGain + (slot.fadeTargetGain - slot.fadeStartGain) * t,
                        0.0f,
                        1.0f
                );
            }
            if (slot.fadeElapsedSeconds >= slot.fadeDurationSeconds) {
                slot.fadeGain = slot.fadeTargetGain;
                slot.fadeDurationSeconds = 0.0;
                slot.equalPowerFade = false;
                if (slot.stopWhenFadeEnds) {
                    slot.playing = false;
                    slot.playbackSeconds = 0.0;
                    return false;
                }
            }
        }

        slot.playbackSeconds += dt;
        double duration = slot.audio != null
                ? slot.audio.durationSeconds()
                : (slot.stream != null ? slot.stream.getDurationSeconds() : 0.0);
        if (duration > 0.0) {
            if (slot.loop) {
                if (slot.playbackSeconds >= duration) {
                    double effLoopStart = (Double.isFinite(slot.loopStartSeconds)
                            && slot.loopStartSeconds > 0.0
                            && slot.loopStartSeconds < duration - 1.0e-4)
                            ? slot.loopStartSeconds
                            : 0.0;
                    double span = Math.max(1.0e-6, duration - effLoopStart);
                    slot.playbackSeconds = effLoopStart + ((slot.playbackSeconds - effLoopStart) % span);
                    if (slot.stream != null) {
                        slot.stream.seekSeconds(slot.playbackSeconds);
                    }
                }
            } else if (slot.playbackSeconds >= duration) {
                slot.playbackSeconds = duration;
                slot.playing = false;
                slot.fadeGain = 0.0f;
                return false;
            }
        }

        syncOpenAlGain(slot);
        return true;
    }

    /**
     * Attaches or clears an optional {@link AudioDsp.BiquadFilter} on this channel.
     * Passing {@code null} disables filtering and restores dry playback/analysis.
     *
     * @param filter DSP filter to apply, or {@code null} to disable
     */
    public synchronized void setFilter(AudioDsp.BiquadFilter filter) {
        this.filter = filter;
        if (primary != null) {
            primary.filteredMono = computeFilteredMono(primary.unfilteredMono, filter);
            if (primary.playing && primary.audio != null && isOpenAlContextUsable()) {
                double resumeAt = primary.playbackSeconds;
                releaseSlotOpenAl(primary);
                uploadAndStartOpenAl(primary, resumeAt);
            }
        }
        if (outgoing != null) {
            outgoing.filteredMono = computeFilteredMono(outgoing.unfilteredMono, filter);
        }
    }

    /**
     * Returns the currently active DSP filter, or {@code null} if unfiltered.
     *
     * @return active filter or {@code null}
     */
    public synchronized AudioDsp.BiquadFilter getFilter() {
        return filter;
    }

    /**
     * Returns the primary playback cursor position in seconds.
     *
     * @return playback time in seconds ({@code >= 0})
     */
    public synchronized double getPlaybackSeconds() {
        return primary != null ? primary.playbackSeconds : 0.0;
    }

    /**
     * Computes the real-time effective RMS amplitude around the active playback cursor,
     * scaled by current fade and volume gains.
     *
     * @return current RMS amplitude in {@code [0, 1]}
     */
    public synchronized float getCurrentRms() {
        if (primary == null || !primary.playing) {
            return 0.0f;
        }
        if (primary.audio == null) {
            if (primary.stream == null) {
                return 0.0f;
            }
            float[] streamMono = primary.stream.readMonoAnalysisWindow(ANALYSIS_WINDOW_SAMPLES);
            float rawRms = AudioDsp.computeRms(streamMono, 0, streamMono.length);
            return Math.clamp(rawRms * computeEffectiveGain(primary), 0.0f, 1.0f);
        }
        float[] mono = primary.filteredMono != null ? primary.filteredMono : primary.unfilteredMono;
        if (mono == null || mono.length == 0) {
            return 0.0f;
        }
        int windowSize = Math.min(ANALYSIS_WINDOW_SAMPLES, mono.length);
        int start = computeAnalysisStartIndex(primary, mono.length, windowSize);
        float rawRms = AudioDsp.computeRms(mono, start, windowSize);
        float gain = computeEffectiveGain(primary);
        return Math.clamp(rawRms * gain, 0.0f, 1.0f);
    }

    /**
     * Computes real-time logarithmic frequency band amplitudes around the active playback cursor
     * for Music Room spectrum visualization.
     *
     * @param bands number of frequency bands ({@code > 0})
     * @return array of {@code bands} normalized amplitudes in {@code [0, 1]}
     */
    public synchronized float[] getCurrentSpectrumBands(int bands) {
        if (bands <= 0) {
            throw new IllegalArgumentException("bands must be > 0, got: " + bands);
        }
        if (primary == null || !primary.playing) {
            return new float[bands];
        }
        if (primary.audio == null) {
            if (primary.stream == null) {
                return new float[bands];
            }
            float[] streamMono = primary.stream.readMonoAnalysisWindow(ANALYSIS_WINDOW_SAMPLES);
            float[] spectrum = AudioDsp.computeSpectrumBands(
                    streamMono, 0, streamMono.length, bands, primary.stream.getSampleRate()
            );
            float gain = computeEffectiveGain(primary);
            if (gain < 1.0f) {
                for (int i = 0; i < spectrum.length; i++) {
                    spectrum[i] = Math.clamp(spectrum[i] * gain, 0.0f, 1.0f);
                }
            }
            return spectrum;
        }
        float[] mono = primary.filteredMono != null ? primary.filteredMono : primary.unfilteredMono;
        if (mono == null || mono.length == 0) {
            return new float[bands];
        }
        int windowSize = Math.min(ANALYSIS_WINDOW_SAMPLES, mono.length);
        int start = computeAnalysisStartIndex(primary, mono.length, windowSize);
        float[] spectrum = AudioDsp.computeSpectrumBands(
                mono,
                start,
                windowSize,
                bands,
                primary.audio.sampleRate()
        );
        float gain = computeEffectiveGain(primary);
        if (gain < 1.0f) {
            for (int i = 0; i < spectrum.length; i++) {
                spectrum[i] = Math.clamp(spectrum[i] * gain, 0.0f, 1.0f);
            }
        }
        return spectrum;
    }

    /**
     * Captures an immutable snapshot of this channel's playback state for rollback or save files.
     *
     * @return captured {@link ChannelSnapshot}
     */
    public synchronized ChannelSnapshot captureSnapshot() {
        if (primary == null) {
            return new ChannelSnapshot(null, false, 1.0f, false, 0.0);
        }
        boolean snapshotPlaying = primary.playing && !primary.stopWhenFadeEnds;
        return new ChannelSnapshot(
                primary.assetPath,
                primary.loop,
                primary.targetVolume,
                snapshotPlaying,
                snapshotPlaying ? primary.playbackSeconds : 0.0
        );
    }

    /**
     * Restores channel playback state from a previously captured {@link ChannelSnapshot},
     * reusing any matching decoded audio already cached on this channel.
     *
     * @param snapshot snapshot to restore (must not be {@code null})
     */
    public synchronized void restoreSnapshot(ChannelSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        AudioDecoder.DecodedAudio resolved = null;
        if (snapshot.assetPath() != null) {
            if (primary != null && snapshot.assetPath().equals(primary.assetPath) && primary.audio != null) {
                resolved = primary.audio;
            } else {
                resolved = recentAudioCache.get(snapshot.assetPath());
            }
        }
        restoreSnapshot(snapshot, resolved);
    }

    /**
     * Restores channel playback state from a {@link ChannelSnapshot} with an explicitly supplied
     * {@link AudioDecoder.DecodedAudio} buffer.
     *
     * @param snapshot snapshot to restore (must not be {@code null})
     * @param audio    decoded audio buffer for {@code snapshot.assetPath()} (nullable)
     */
    public synchronized void restoreSnapshot(ChannelSnapshot snapshot, AudioDecoder.DecodedAudio audio) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        releaseSlotOpenAl(outgoing);
        outgoing = null;
        releaseSlotOpenAl(primary);

        if (!snapshot.playing() || snapshot.assetPath() == null) {
            primary = null;
            return;
        }

        if (audio != null) {
            recentAudioCache.put(snapshot.assetPath(), audio);
        }

        TrackSlot slot = new TrackSlot();
        slot.assetPath = snapshot.assetPath();
        slot.audio = audio;
        slot.unfilteredMono = audio != null ? audio.toMonoFloat() : null;
        slot.filteredMono = computeFilteredMono(slot.unfilteredMono, this.filter);
        slot.loop = snapshot.loop();
        slot.targetVolume = snapshot.targetVolume();
        slot.playbackSeconds = snapshot.playbackSeconds();
        if (audio != null && audio.durationSeconds() > 0.0) {
            if (slot.loop) {
                slot.playbackSeconds = slot.playbackSeconds % audio.durationSeconds();
            } else if (slot.playbackSeconds >= audio.durationSeconds()) {
                slot.playbackSeconds = audio.durationSeconds();
            }
        }
        slot.playing = true;
        slot.fadeGain = 1.0f;
        slot.fadeStartGain = 1.0f;
        slot.fadeTargetGain = 1.0f;
        slot.fadeDurationSeconds = 0.0;
        slot.fadeElapsedSeconds = 0.0;
        slot.stopWhenFadeEnds = false;

        this.primary = slot;
        uploadAndStartOpenAl(slot, slot.playbackSeconds);
    }

    /**
     * Returns the normalized lowercase channel name.
     *
     * @return normalized lowercase channel name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns whether the primary slot is currently playing.
     *
     * @return {@code true} if the primary slot is currently playing (including fade-out)
     */
    public synchronized boolean isPlaying() {
        return primary != null && primary.playing;
    }

    /**
     * Returns whether the primary slot is configured to loop.
     *
     * @return {@code true} if the primary slot is configured to loop
     */
    public synchronized boolean isLooping() {
        return primary != null && primary.loop;
    }

    /**
     * Returns whether a crossfade is active.
     *
     * @return {@code true} if a crossfade between an outgoing and primary track is active
     */
    public synchronized boolean isCrossfading() {
        return primary != null && primary.playing && outgoing != null && outgoing.playing;
    }

    /**
     * Returns whether the primary track is fading out to stop.
     *
     * @return {@code true} if the primary track is currently fading out to stop
     */
    public synchronized boolean isFadingOut() {
        return primary != null && primary.playing && primary.stopWhenFadeEnds;
    }

    /**
     * Returns the active or last-played asset path.
     *
     * @return active or last-played asset path, or {@code null}
     */
    public synchronized String getAssetPath() {
        return primary != null ? primary.assetPath : null;
    }

    /**
     * Returns the active track's target volume.
     *
     * @return active track's target volume in {@code [0, 1]}
     */
    public synchronized float getTargetVolume() {
        return primary != null ? primary.targetVolume : 1.0f;
    }

    /**
     * Returns the primary track's current fade envelope gain.
     *
     * @return primary track's current fade envelope gain in {@code [0, 1]}
     */
    public synchronized float getFadeGain() {
        return primary != null ? primary.fadeGain : 0.0f;
    }

    /**
     * Returns the outgoing crossfade track's fade envelope gain.
     *
     * @return outgoing crossfade track's fade envelope gain in {@code [0, 1]}, or {@code 0} if none
     */
    public synchronized float getOutgoingFadeGain() {
        return (outgoing != null && outgoing.playing) ? outgoing.fadeGain : 0.0f;
    }

    /**
     * Returns the external channel mix gain.
     *
     * @return external channel mix gain (master * preference channel volume) in {@code [0, 1]}
     */
    public synchronized float getChannelMixGain() {
        return channelMixGain;
    }

    /**
     * Updates the channel-wide mix gain (typically {@code masterVolume * channelVolume} from preferences)
     * and refreshes OpenAL source gains immediately.
     *
     * @param channelMixGain gain multiplier clamped to {@code [0, 1]}
     */
    public synchronized void setChannelMixGain(float channelMixGain) {
        this.channelMixGain = Float.isFinite(channelMixGain) ? Math.clamp(channelMixGain, 0.0f, 1.0f) : 1.0f;
        syncOpenAlGain(primary);
        syncOpenAlGain(outgoing);
    }

    /**
     * Updates the dynamic voice-ducking gain multiplier clamped to {@code [0, 1]}.
     *
     * @param duckGain ducking gain multiplier in {@code [0, 1]}
     */
    public synchronized void setDuckGain(float duckGain) {
        this.duckGain = Float.isFinite(duckGain) ? Math.clamp(duckGain, 0.0f, 1.0f) : 1.0f;
        syncOpenAlGain(primary);
        syncOpenAlGain(outgoing);
    }

    /**
     * Returns the current dynamic voice-ducking gain multiplier in {@code [0, 1]}.
     *
     * @return current ducking gain multiplier
     */
    public synchronized float getDuckGain() {
        return duckGain;
    }

    /**
     * Enables or disables equal-power sine/cosine crossfading on this channel.
     *
     * @param equalPowerCrossfade {@code true} for equal-power crossfade curves
     */
    public synchronized void setEqualPowerCrossfade(boolean equalPowerCrossfade) {
        this.equalPowerCrossfade = equalPowerCrossfade;
    }

    /**
     * Returns whether equal-power crossfading is enabled on this channel.
     *
     * @return {@code true} if equal-power crossfading is active
     */
    public synchronized boolean isEqualPowerCrossfade() {
        return equalPowerCrossfade;
    }

    /**
     * Returns the current effective linear gain of the primary track
     * ({@code fadeGain * targetVolume * channelMixGain}), or {@code 0} if stopped.
     *
     * @return effective volume in {@code [0, 1]}
     */
    public synchronized float getCurrentEffectiveVolume() {
        if (primary == null || !primary.playing) {
            return 0.0f;
        }
        return computeEffectiveGain(primary);
    }

    /**
     * Returns whether OpenAL source management is enabled for this channel.
     *
     * @return {@code true} if OpenAL source management is enabled for this channel
     */
    public boolean isOpenAlEnabled() {
        return openAlEnabled;
    }

    /**
     * Enables or disables OpenAL source management on this channel.
     *
     * @param openAlEnabled whether OpenAL sources should be created during playback
     */
    public synchronized void setOpenAlEnabled(boolean openAlEnabled) {
        if (this.openAlEnabled && !openAlEnabled) {
            releaseSlotOpenAl(outgoing);
            releaseSlotOpenAl(primary);
        }
        this.openAlEnabled = openAlEnabled;
    }

    /**
     * Stops playback, clears queued items and cached audio, and deletes any native OpenAL resources.
     */
    public synchronized void dispose() {
        queuedPaths.clear();
        recentAudioCache.clear();
        releaseSlotOpenAl(outgoing);
        outgoing = null;
        releaseSlotOpenAl(primary);
        primary = null;
    }

    // ---- Internal Helpers ----

    private float computeEffectiveGain(TrackSlot slot) {
        return Math.clamp(slot.fadeGain * slot.targetVolume * channelMixGain * duckGain, 0.0f, 1.0f);
    }

    private static float[] computeFilteredMono(float[] unfilteredMono, AudioDsp.BiquadFilter templateFilter) {
        if (unfilteredMono == null || templateFilter == null) {
            return null;
        }
        float[] copy = unfilteredMono.clone();
        templateFilter.copy().processBuffer(copy);
        return copy;
    }

    private static short[] applyFilterToPcm16(AudioDecoder.DecodedAudio audio, AudioDsp.BiquadFilter templateFilter) {
        if (templateFilter == null) {
            return audio.pcm16();
        }
        int channels = audio.channels();
        float[] src = audio.monoOrInterleavedFloat();
        float[] filtered = new float[src.length];

        if (channels == 1) {
            AudioDsp.BiquadFilter f = templateFilter.copy();
            for (int i = 0; i < src.length; i++) {
                filtered[i] = f.processSample(src[i]);
            }
        } else {
            AudioDsp.BiquadFilter[] filters = new AudioDsp.BiquadFilter[channels];
            for (int c = 0; c < channels; c++) {
                filters[c] = templateFilter.copy();
            }
            for (int i = 0; i < src.length; i += channels) {
                for (int c = 0; c < channels; c++) {
                    filtered[i + c] = filters[c].processSample(src[i + c]);
                }
            }
        }
        return AudioDecoder.floatToPcm16(filtered);
    }

    private static int computeAnalysisStartIndex(TrackSlot slot, int monoLength, int windowSize) {
        int frameCursor = (int) Math.floor(slot.playbackSeconds * slot.audio.sampleRate());
        if (monoLength <= windowSize) {
            return 0;
        }
        if (slot.loop) {
            frameCursor = Math.floorMod(frameCursor, monoLength);
        }
        return Math.clamp(frameCursor, 0, monoLength - windowSize);
    }

    private boolean isOpenAlContextUsable() {
        if (!openAlEnabled) {
            return false;
        }
        try {
            return AL.getCapabilities() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void uploadAndStartOpenAl(TrackSlot slot, double startSeconds) {
        if (slot == null || slot.audio == null || !isOpenAlContextUsable()) {
            return;
        }
        int channels = slot.audio.channels();
        if (channels > 2) {
            return;
        }

        int alFormat = (channels == 1) ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
        short[] uploadPcm = applyFilterToPcm16(slot.audio, this.filter);

        int bufferId = AL10.alGenBuffers();
        AL10.alBufferData(bufferId, alFormat, uploadPcm, slot.audio.sampleRate());

        int sourceId = AL10.alGenSources();
        AL10.alSourcei(sourceId, AL10.AL_BUFFER, bufferId);
        AL10.alSourcei(sourceId, AL10.AL_LOOPING, slot.loop ? AL10.AL_TRUE : AL10.AL_FALSE);
        AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
        AL10.alSource3f(sourceId, AL10.AL_POSITION, 0.0f, 0.0f, 0.0f);
        AL10.alSourcef(sourceId, AL10.AL_GAIN, computeEffectiveGain(slot));
        if (startSeconds > 0.0 && slot.audio.durationSeconds() > 0.0) {
            float clampedOffset = (float) Math.min(startSeconds, Math.max(0.0, slot.audio.durationSeconds() - 0.001));
            AL10.alSourcef(sourceId, AL11.AL_SEC_OFFSET, clampedOffset);
        }
        AL10.alSourcePlay(sourceId);

        slot.alBufferId = bufferId;
        slot.alSourceId = sourceId;
    }

    private void syncOpenAlGain(TrackSlot slot) {
        if (slot == null || slot.alSourceId == 0 || !isOpenAlContextUsable()) {
            return;
        }
        AL10.alSourcef(slot.alSourceId, AL10.AL_GAIN, computeEffectiveGain(slot));
    }

    private void releaseSlotOpenAl(TrackSlot slot) {
        if (slot == null) {
            return;
        }
        if (slot.stream != null) {
            slot.stream.close();
            slot.stream = null;
        }
        if (slot.alSourceId == 0 && slot.alBufferId == 0) {
            return;
        }
        if (isOpenAlContextUsable()) {
            if (slot.alSourceId != 0) {
                AL10.alSourceStop(slot.alSourceId);
                AL10.alSourcei(slot.alSourceId, AL10.AL_BUFFER, 0);
                AL10.alDeleteSources(slot.alSourceId);
            }
            if (slot.alBufferId != 0) {
                AL10.alDeleteBuffers(slot.alBufferId);
            }
        }
        slot.alSourceId = 0;
        slot.alBufferId = 0;
    }
}
