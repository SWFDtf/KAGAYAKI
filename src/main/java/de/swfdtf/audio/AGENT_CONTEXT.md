# Package `de.swfdtf.audio` — AGENT_CONTEXT

## Zweck des Pakets
LWJGL-3-/OpenAL-Audio-, DSP-, Lip-Sync- und Accessibility-TTS-Subsystem von KAGAYAKI (Meilenstein M5):
- **Ogg-/WAV-Dekodierung & Synthese (`AudioDecoder`)**: Reine `STBVorbis`- und RIFF-WAVE-Dekodierung (8/16-Bit PCM, 32-Bit IEEE-Float), 16-Bit-WAV-Encoder sowie prozedurale Synthesizer für Sinustöne, Akkorde und Charakter-Voice-Bleeps.
- **Digitale Signalverarbeitung (`AudioDsp`)**: Zustandsbehaftete Second-Order-IIR-Biquad-Filter (`LOWPASS`, `HIGHPASS`, `BANDPASS` in Direct Form II Transposed), RMS-Pegelmessung, Hann-gefensterte logarithmische Radix-2-DFT-Spektrumbänder (Music-Room-Visualizer) und Goertzel-Einzelfrequenzanalyse.
- **Kanal-Mixer & OpenAL-Backend (`AudioChannel`, `AudioEngine`)**: 4 Standardkanäle (`music`, `sound`, `voice`, `ambient`) plus dynamische benutzerdefinierte Kanäle mit Fade-In/Fade-Out, Dual-Slot-Crossfade, FIFO-Playlists (`queue`), Biquad-Filterung, `PreferencesState`-Lautstärkesynchronisation, `LocalizationManager`-`tl/<lang>/`-Pfadauflösung, `ScriptRuntime.AudioBridge`-Implementierung, deterministischem Headless-Virtual-Mixer-Fallback sowie M4-Snapshot/Restore-Unterstützung (`ChannelSnapshot`).
- **Lip-Sync & Self-Voicing (`LipSyncAnalyzer`, `TextToSpeechService`)**: Hüllkurven-Glättung (Attack/Release) vom Voice-RMS zu `MouthShape` (`CLOSED`, `HALF_OPEN`, `OPEN`) sowie nicht-blockierender Virtual-Thread-TTS-Dienst (`V`-Taste / `selfVoicing`) mit `TextTagParser`-Markup-Bereinigung und OS-Sprachausgabe (`say`, `spd-say`/`espeak`, PowerShell `SpeechSynthesizer`).

## Abhängigkeiten zu anderen `de.swfdtf.*`-Paketen
- `de.swfdtf.core` (`EngineLogger` in `AudioEngine`, `TextToSpeechService`)
- `de.swfdtf.graphics` (`TextTagParser` in `TextToSpeechService`)
- `de.swfdtf.parser` (`ScriptRuntime` in `AudioEngine` für `ScriptRuntime.AudioBridge`)
- `de.swfdtf.state` (`LocalizationManager`, `PreferencesState` in `AudioEngine`)
- Externe Bibliotheken: `org.lwjgl.openal.*` (`AL`, `AL10`, `AL11`, `ALC`, `ALC10`, `ALCCapabilities`), `org.lwjgl.stb.STBVorbis`, `org.lwjgl.system.*` (`MemoryStack`, `MemoryUtil`, `LibCStdlib`).

## Enthaltene Typen

| Typ | Art | Beschreibung |
| :--- | :--- | :--- |
| `AudioDsp` | `final class` | DSP-Werkzeugklasse für RMS-Amplituden, logarithmische DFT-Spektrumbänder und Goertzel-Frequenzanalyse. |
| `AudioDsp.FilterType` | `enum` | Unterstützte Biquad-Filtertopologien (`LOWPASS`, `HIGHPASS`, `BANDPASS`). |
| `AudioDsp.BiquadFilter` | `final class` | Zustandsbehafteter IIR-Biquad-Filter zweiter Ordnung (Direct Form II Transposed, `processSample`, `processBuffer`, `reset`, `copy`). |
| `AudioDecoder` | `final class` | Dekodiert `.ogg` (`STBVorbis`) und `.wav` (`8/16-bit PCM`, `32-bit float`), kodiert 16-Bit-WAVs und synthetisiert Töne, Akkorde und Voice-Bleeps. |
| `AudioDecoder.DecodedAudio` | `record` | Unveränderlicher dekodierter Audiopuffer mit normalisierten Float-Samples (`[-1, 1]`), 16-Bit-PCM-Samples, Kanalzahl, Abtastrate und Dauer. |
| `AudioChannel` | `final class` | Benannter logischer Mixer-Kanal mit Fade-In/Fade-Out, Dual-Slot-Crossfading, Queue, optionalem `BiquadFilter`, RMS-/Spektrum-Analyse, OpenAL-Source-Verwaltung und Rollback-Snapshots. |
| `AudioChannel.ChannelSnapshot` | `record` | Unveränderlicher Wiedergabe-Schnappschuss (`assetPath`, `loop`, `targetVolume`, `playing`, `playbackSeconds`) eines Kanals für Rollback und Save/Load. |
| `AudioEngine` | `final class` | Zentraler OpenAL-Audio-Manager (`implements AutoCloseable, ScriptRuntime.AudioBridge`) für die Standardkanäle (`music`, `sound`, `voice`, `ambient`), Headless-Virtual-Mixer-Fallback, `tl/<lang>/`-Auflösung, Voice-Bleeps und Snapshot/Restore. |
| `LipSyncAnalyzer` | `final class` | Attack/Release-Hüllkurven-Analysator, der Echtzeit-Voice-RMS-Amplituden in geglättete Mundöffnung (`[0, 1]`) und `MouthShape` überführt. |
| `LipSyncAnalyzer.MouthShape` | `enum` | Diskrete Mundanimationszustände (`CLOSED`, `HALF_OPEN`, `OPEN`). |
| `TextToSpeechService` | `final class` | Nicht-blockierender Self-Voicing-TTS-Dienst (`implements AutoCloseable`) auf einem Virtual-Thread-Worker mit `TextTagParser`-Bereinigung und OS-Sprachausgabe. |
| `AudioDuckingController` | `final class` | Attack-/Release-Hüllkurve zur sanften Musik-/Ambient-Absenkung während aktiver Sprachausgabe (`applyToGain`). |
| `AmbientCrossfadeMixer` | `final class` | Mehrspur-Ambient-Soundscape-Mixer mit benannten Ebenen, Presets und sanfter Überblendung (`getActiveWeights`). |
| `ClosedCaptionQueue` | `final class` | Barrierefreie Untertitel-/Closed-Caption-Warteschlange (`DIALOGUE`, `SFX`, `MUSIC`) mit Ablauf-Timer und Kategorie-Filter. |
| `AudioCueRegistry` | `final class` | Logische `AudioCue`-Registry mit Kanal-Filterung, `Optional`-Lookup und Gson-JSON-Serialisierung. |
| `VoicePlaybackBookmark` | `final class` | Begrenzter Verlauf zuletzt abgespielter Voice-Clips (`VoiceClipRecord`) mit Lookup nach `lineId` und Sprecher. |

## Öffentliche API-Signaturen

### `AudioDsp`
```java
public final class AudioDsp {
    public static final float DEFAULT_SAMPLE_RATE = 44100.0f;

    public enum FilterType { LOWPASS, HIGHPASS, BANDPASS }

    public static final class BiquadFilter {
        public static BiquadFilter createLowpass(float sampleRate, float cutoffHz, float q)
        public static BiquadFilter createHighpass(float sampleRate, float cutoffHz, float q)
        public static BiquadFilter createBandpass(float sampleRate, float cutoffHz, float q)
        public float processSample(float sample)
        public void processBuffer(float[] samples)
        public void processBuffer(float[] samples, int offset, int length)
        public void reset()
        public BiquadFilter copy()
        public FilterType getType()
        public float getSampleRate()
        public float getCutoffHz()
        public float getQ()
    }

    public static float computeRms(float[] samples, int offset, int length)
    public static float computeRms(float[] samples)
    public static float[] computeSpectrumBands(float[] samples, int offset, int length, int bandCount)
    public static float[] computeSpectrumBands(float[] samples, int offset, int length, int bandCount, float sampleRate)
    public static float computeGoertzelMagnitude(float[] samples, int offset, int length, float targetFreqHz, float sampleRate)
}
```

### `AudioDecoder`
```java
public final class AudioDecoder {
    public record DecodedAudio(
            float[] monoOrInterleavedFloat,
            short[] pcm16,
            int channels,
            int sampleRate,
            double durationSeconds
    ) {
        public int frameCount()
        public float[] toMonoFloat()
    }

    public static DecodedAudio decode(Path file) throws IOException
    public static DecodedAudio decodeOgg(byte[] oggBytes) throws IOException
    public static DecodedAudio decodeWav(byte[] wavBytes) throws IOException
    public static byte[] encodeWav(short[] pcm16, int channels, int sampleRate)
    public static DecodedAudio synthesizeTone(double freqHz, double durationSeconds, int sampleRate, float amplitude)
    public static DecodedAudio synthesizeChord(double[] freqsHz, double durationSeconds, int sampleRate, float amplitude)
    public static DecodedAudio synthesizeVoiceBleep(double baseFreqHz, double durationSeconds, int sampleRate)
    public static short[] floatToPcm16(float[] floats)
    public static float[] pcm16ToFloat(short[] pcm16)
}
```

### `AudioChannel`
```java
public final class AudioChannel {
    public record ChannelSnapshot(
            String assetPath,
            boolean loop,
            float targetVolume,
            boolean playing,
            double playbackSeconds
    ) {}

    public AudioChannel(String name)
    public AudioChannel(String name, boolean openAlEnabled)

    public synchronized void play(String assetPath, AudioDecoder.DecodedAudio audio, boolean loop, double fadeInSeconds, float targetVolume)
    public synchronized void queue(List<String> paths)
    public synchronized Optional<String> pollNextQueued()
    public synchronized void clearQueue()
    public synchronized List<String> getQueuedPaths()
    public synchronized void stop(double fadeOutSeconds)
    public synchronized void update(double dt)

    public synchronized void setFilter(AudioDsp.BiquadFilter filter)
    public synchronized AudioDsp.BiquadFilter getFilter()
    public synchronized double getPlaybackSeconds()
    public synchronized float getCurrentRms()
    public synchronized float[] getCurrentSpectrumBands(int bands)

    public synchronized ChannelSnapshot captureSnapshot()
    public synchronized void restoreSnapshot(ChannelSnapshot snapshot)
    public synchronized void restoreSnapshot(ChannelSnapshot snapshot, AudioDecoder.DecodedAudio audio)

    public String getName()
    public synchronized boolean isPlaying()
    public synchronized boolean isLooping()
    public synchronized boolean isCrossfading()
    public synchronized boolean isFadingOut()
    public synchronized String getAssetPath()
    public synchronized float getTargetVolume()
    public synchronized float getFadeGain()
    public synchronized float getOutgoingFadeGain()
    public synchronized float getChannelMixGain()
    public synchronized void setChannelMixGain(float channelMixGain)
    public synchronized float getCurrentEffectiveVolume()
    public boolean isOpenAlEnabled()
    public synchronized void setOpenAlEnabled(boolean openAlEnabled)
    public synchronized void dispose()
}
```

### `AudioEngine`
```java
public final class AudioEngine implements AutoCloseable, ScriptRuntime.AudioBridge {
    public static final String CHANNEL_MUSIC = "music";
    public static final String CHANNEL_SOUND = "sound";
    public static final String CHANNEL_VOICE = "voice";
    public static final String CHANNEL_AMBIENT = "ambient";
    public static final double DEFAULT_VOICE_BLEEP_DURATION = 0.055;
    public static final int DEFAULT_SYNTH_SAMPLE_RATE = 44100;

    public AudioEngine()
    public AudioEngine(Path projectRoot, boolean headless)
    public AudioEngine(Path projectRoot, PreferencesState preferencesState, LocalizationManager localizationManager, EngineLogger logger, boolean headless)

    public synchronized void init()
    public synchronized AudioChannel getChannel(String name)
    public synchronized Map<String, AudioChannel> getChannels()
    public synchronized Path resolveAudioPath(String assetPath)
    public synchronized void registerInMemoryAudio(String assetPath, AudioDecoder.DecodedAudio audio)
    public synchronized void syncVolumesFromPreferences()
    public ScriptRuntime.AudioBridge createScriptAudioBridge()

    @Override public synchronized void play(String ch, String path, boolean loop, double fadeIn, double vol)
    @Override public synchronized void queue(String ch, List<String> paths)
    @Override public synchronized void stop(String ch, double fadeOut)
    @Override public synchronized void voice(String path)

    public synchronized void playVoiceBleep(double freqHz)
    public synchronized void playVoiceBleep(double freqHz, double durationSeconds)
    public synchronized float[] getMusicSpectrumBands(int bandCount)
    public synchronized float getActiveVoiceRms()
    public synchronized Map<String, AudioChannel.ChannelSnapshot> snapshotAllChannels()
    public synchronized void restoreAllChannels(Map<String, AudioChannel.ChannelSnapshot> snapshots)
    public synchronized void update(double dt)

    public synchronized boolean isOpenAlActive()
    public synchronized boolean isInitialized()
    public synchronized boolean isDisposed()
    public boolean isHeadless()
    public synchronized Path getProjectRoot() / public synchronized void setProjectRoot(Path projectRoot)
    public synchronized PreferencesState getPreferencesState() / public synchronized void setPreferencesState(PreferencesState preferencesState)
    public synchronized LocalizationManager getLocalizationManager() / public synchronized void setLocalizationManager(LocalizationManager localizationManager)
    public EngineLogger getLogger()
    public synchronized void dispose()
    @Override public void close()
}
```

### `LipSyncAnalyzer`
```java
public final class LipSyncAnalyzer {
    public static final double DEFAULT_ATTACK_SECONDS = 0.025;
    public static final double DEFAULT_RELEASE_SECONDS = 0.12;
    public static final float DEFAULT_NOISE_FLOOR_RMS = 0.01f;
    public static final float DEFAULT_MAX_RMS = 0.50f;
    public static final float DEFAULT_HALF_OPEN_THRESHOLD = 0.08f;
    public static final float DEFAULT_OPEN_THRESHOLD = 0.45f;

    public enum MouthShape { CLOSED, HALF_OPEN, OPEN }

    public LipSyncAnalyzer()
    public LipSyncAnalyzer(double attackSeconds, double releaseSeconds, float halfOpenThreshold, float openThreshold)
    public LipSyncAnalyzer(double attackSeconds, double releaseSeconds, float noiseFloorRms, float maxRms, float halfOpenThreshold, float openThreshold)

    public MouthShape update(AudioEngine audioEngine, double dt)
    public MouthShape updateFromRms(float rms, double dt)
    public void reset()
    public MouthShape getMouthShape()
    public float getOpenness()
    public double getAttackSeconds() / public void setAttackSeconds(double attackSeconds)
    public double getReleaseSeconds() / public void setReleaseSeconds(double releaseSeconds)
    public float getNoiseFloorRms() / public void setNoiseFloorRms(float noiseFloorRms)
    public float getMaxRms() / public void setMaxRms(float maxRms)
    public float getHalfOpenThreshold() / public void setHalfOpenThreshold(float halfOpenThreshold)
    public float getOpenThreshold() / public void setOpenThreshold(float openThreshold)
}
```

### `TextToSpeechService`
```java
public final class TextToSpeechService implements AutoCloseable {
    public static final int DEFAULT_MAX_HISTORY = 500;

    public TextToSpeechService()
    public TextToSpeechService(EngineLogger logger, boolean osCommandExecutionEnabled)

    public static String stripRichTextTags(String rawText)
    public static List<String> buildSpeechCommand(String osName, String utterance)

    public void setEnabled(boolean enabled) / public boolean isEnabled()
    public void setOsCommandExecutionEnabled(boolean osCommandExecutionEnabled) / public boolean isOsCommandExecutionEnabled()
    public void speak(String text)
    public void stop()
    public List<String> getSpokenHistory()
    public void clearSpokenHistory()
    public void setOnSpeakListener(Consumer<String> listener)
    public int getPendingCount()
    public boolean awaitIdle(long timeoutMillis)
    public boolean isDisposed()
    public void dispose()
    @Override public void close()
}
```
